"""Verify V9 -> V10 backfill and persistent source quotas using only a local fake provider."""
from __future__ import annotations

import argparse
import base64
import hashlib
import http.cookiejar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("java", "python", "jar", "previous-jar"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()
    java, python = shutil.which(args.java), shutil.which(args.python)
    if not java or not python:
        parser.error("Java and Python executables must exist")
    jar, previous = Path(args.jar).resolve(), Path(args.previous_jar).resolve()
    with zipfile.ZipFile(previous) as archive:
        assert "BOOT-INF/classes/db/migration/h2/V9__ModelCandidateSubmission.sql" in archive.namelist()
        assert "BOOT-INF/classes/db/migration/h2/V10__ModelSourceBudgets.sql" not in archive.namelist()
    password = secrets.token_urlsafe(24)
    auth = "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()
    received, checks = [], []
    scenario = "good"
    entered, release = threading.Event(), threading.Event()

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            assert self.path == "/v1/chat/completions"
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            received.append(request)
            data = json.loads(request["messages"][1]["content"])
            assert request["stream"] is False and request["store"] is False and "tools" not in request
            assert "not-forwarded" not in json.dumps(request)
            if scenario == "held":
                entered.set()
                assert release.wait(45)
            proposal = dict(schemaVersion=1, expectedBaselineSha256=data["expectedBaselineSha256"],
                            targetFile="source.py", diagnosis="Remove duplicate", expectedEffect="Static only",
                            validationTargets=["S03"], edits=[dict(
                                oldText="    def homeContent(self, filter):\n        return {'class': []}\n", newText="")])
            body = json.dumps(dict(choices=[dict(finish_reason="stop", message=dict(
                role="assistant", content=json.dumps(proposal)))],
                usage=dict(prompt_tokens=10, completion_tokens=20, total_tokens=30))).encode()
            try:
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

    class FakeServer(ThreadingHTTPServer):
        def __exit__(self, *_):
            self.shutdown()
            self.server_close()

    with FakeServer(("127.0.0.1", 0), Provider) as fake, tempfile.TemporaryDirectory(
            prefix="at-model-source-budget-") as temporary:
        fake.daemon_threads = True
        threading.Thread(target=fake.serve_forever, daemon=True).start()
        work = Path(temporary)
        environment = dict(os.environ, SPRING_SECURITY_USER_NAME="admin", SPRING_SECURITY_USER_PASSWORD=password,
                           MAINTENANCE_PYTHON=str(Path(python).resolve()), MAINTENANCE_DB_PASSWORD="",
                           MAINTENANCE_AT_ORIGIN="", MAINTENANCE_AT_SESSION_TOKEN="",
                           MAINTENANCE_SCHEDULING_ENABLED="false", MAINTENANCE_CONTAINER_ENABLED="false",
                           MAINTENANCE_MODEL_ENABLED="true", MAINTENANCE_MODEL_AUTO_SUBMIT_ENABLED="false",
                           MAINTENANCE_MODEL_ENDPOINT=f"http://127.0.0.1:{fake.server_port}/v1/chat/completions",
                           MAINTENANCE_MODEL_NAME="offline-fixture", MAINTENANCE_MODEL_KEY_ENV="",
                           MAINTENANCE_MODEL_TIMEOUT_MILLIS="15000",
                           MAINTENANCE_MODEL_MAX_REQUEST_BYTES="65536", MAINTENANCE_MODEL_MAX_RESPONSE_BYTES="65536",
                           MAINTENANCE_MODEL_MAX_COMPLETION_TOKENS="2048",
                           MAINTENANCE_MODEL_DAILY_CALL_LIMIT="8", MAINTENANCE_MODEL_DAILY_INPUT_BYTES="262144",
                           MAINTENANCE_MODEL_DAILY_OUTPUT_TOKENS="16384",
                           MAINTENANCE_MODEL_SOURCE_DAILY_CALL_LIMIT="3",
                           MAINTENANCE_MODEL_SOURCE_DAILY_INPUT_BYTES="131072",
                           MAINTENANCE_MODEL_SOURCE_DAILY_OUTPUT_TOKENS="6144")
        datasource = "jdbc:h2:file:" + (work / "db").as_posix() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        base, csrf = "", {}

        def stop(process):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)

        def start(index, artifact):
            log = work / f"server-{index}.log"
            with log.open("wb") as output:
                process = subprocess.Popen([java, "-jar", str(artifact), "--server.port=0",
                                            "--server.address=127.0.0.1", "--spring.datasource.url=" + datasource],
                                           cwd=work, env=environment, stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 60
            try:
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError("Budget smoke server exited")
                    match = re.search(r"Tomcat started on port (\d+)", log.read_text(encoding="utf-8", errors="replace"))
                    if match:
                        return process, f"http://127.0.0.1:{match.group(1)}"
                    time.sleep(0.2)
                raise RuntimeError("Budget smoke startup timeout")
            except (RuntimeError, OSError):
                stop(process)
                print(log.read_text(encoding="utf-8", errors="replace")[-4000:].replace(password, "[REDACTED]"))
                raise

        def call(path, body=None, *, method=None, expected=200):
            headers = {"Authorization": auth}
            data = None
            if body is not None:
                data = json.dumps(body).encode()
                headers.update({"Content-Type": "application/json", csrf["headerName"]: csrf["token"]})
            try:
                response = opener.open(urllib.request.Request(base + "/api/plugin-maintenance" + path,
                                                              data=data, headers=headers, method=method), timeout=20)
            except urllib.error.HTTPError as error:
                assert error.code == expected, (error.code, expected, error.read())
                with error:
                    return json.loads(error.read())
            with response:
                assert response.status == expected, (response.status, expected)
                return json.loads(response.read())

        def await_value(path):
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                value = call(path)
                if value["status"] not in ("QUEUED", "RUNNING", "CANCELLING"):
                    return value
                time.sleep(0.1)
            raise AssertionError("Budget smoke work did not finish")

        def repair(source):
            probe = call(f"/sources/{source['id']}/probe-runs", {"mode": "static"}, expected=202)
            assert await_value(f"/probe-runs/{probe['id']}")["status"] == "COMPLETED"
            result = call(f"/sources/{source['id']}/repairs",
                          dict(baselineRunId=probe["id"], expectedArtifactSha256=source["artifactSha256"]))
            consent = call(f"/sources/{source['id']}/model-consent")
            call(f"/sources/{source['id']}/model-consent", dict(
                expectedArtifactSha256=consent["artifactSha256"], artifactSha256=source["artifactSha256"]), method="PUT")
            return result

        def new_source():
            source = call("/sources", {key: fixture[key] for key in ("name", "filename", "content")}, expected=201)
            return source, repair(source)

        def request(repair, config, key):
            return dict(expectedFaultSha256=repair["faultSha256"],
                        expectedConfigurationSha256=config["configurationSha256"], requestKey=key)

        def generate(repair, config, key):
            value = call(f"/repairs/{repair['id']}/model-calls", request(repair, config, key), expected=202)
            return await_value(f"/model-calls/{value['id']}")

        def revise(source, suffix):
            updated = call(f"/sources/{source['id']}/artifacts", dict(
                expectedSha256=source["artifactSha256"], content=fixture["content"] + suffix))
            return updated, repair(updated)

        process, base = start(1, previous)
        try:
            csrf.update(call("/csrf"))
            fixture = call("/fixtures/repair")
            old_config = call("/model/connection")
            source, first_repair = new_source()
            old_call = generate(first_repair, old_config, "old-call-001")
            assert old_call["status"] == "PROPOSED"
            # Second accepted call has a repeated proposal and still consumes budget.
            failed = generate(first_repair, old_config, "old-call-002")
            assert failed["errorCode"] == "REPEATED_MODEL_PROPOSAL"
            source, fresh_repair = revise(source, "\n# upgraded fault\n")
            old_budget = call("/model/budget")
            checks.append("old_v9_calls_include_failed_reservation")
        finally:
            stop(process)

        process, base = start(2, jar)
        try:
            csrf.update(call("/csrf"))
            config = call("/model/connection")
            assert config["configurationSha256"] != old_config["configurationSha256"]
            assert call("/model/budget") == old_budget
            quota_path = f"/sources/{source['id']}/model-budget"
            source_budget = call(quota_path)
            assert source_budget["usedCalls"] == 2, (source_budget, old_budget,
                                                     old_call["createdAt"], failed["createdAt"])
            assert source_budget["reservedInputBytes"] == old_call["requestBytes"] + failed["requestBytes"]
            assert source_budget["reservedOutputTokens"] == 4096
            assert call(f"/model-calls/{old_call['id']}")["budgetReservation"] is None
            checks.append("v10_backfill_preserves_source_charges_and_unknown_historical_limits")
            before = len(received)
            repeated = call(f"/repairs/{first_repair['id']}/model-calls",
                            request(first_repair, old_config, "old-call-001"), expected=202)
            assert repeated["id"] == old_call["id"] and len(received) == before
            assert call(quota_path) == source_budget
            checks.append("old_key_replay_does_not_send_or_charge")
            accepted = generate(fresh_repair, config, "new-call-001")
            assert accepted["status"] == "PROPOSED"
            assert accepted["budgetReservation"]["sourceLimits"]["calls"] == 3
            assert accepted["budgetReservation"]["reservation"]["inputBytes"] == accepted["requestBytes"]
            global_before = call("/model/budget")
            rejected = call(f"/repairs/{fresh_repair['id']}/model-calls",
                            request(fresh_repair, config, "new-call-002"), expected=429)
            assert rejected["code"] == "MODEL_SOURCE_DAILY_BUDGET"
            assert call("/model/budget") == global_before
            assert call(quota_path)["usedCalls"] == 3 and len(received) == before + 1
            checks.append("same_source_new_fault_shares_quota_and_rejection_is_atomic")
            other, other_repair = new_source()
            assert generate(other_repair, config, "other-source-001")["status"] == "PROPOSED"
            checks.append("another_source_has_independent_capacity")
            scenario = "held"
            held_input = request(other_repair, config, "held-source-002")
            held = call(f"/repairs/{other_repair['id']}/model-calls", held_input, expected=202)
            assert entered.wait(10), "Fake provider did not receive held call"
            other_quota_path = f"/sources/{other['id']}/model-budget"
            held_source_budget = call(other_quota_path)
            held_global_budget = call("/model/budget")
            assert held_source_budget["usedCalls"] == 2
        finally:
            stop(process)
            release.set()
            scenario = "good"

        process, base = start(3, jar)
        try:
            csrf.update(call("/csrf"))
            interrupted = call(f"/model-calls/{held['id']}")
            assert interrupted["status"] == "INTERRUPTED"
            assert call(other_quota_path) == held_source_budget
            current_global = call("/model/budget")
            assert {k: v for k, v in current_global.items() if k != "activeCallId"} == {
                k: v for k, v in held_global_budget.items() if k != "activeCallId"}
            before = len(received)
            assert call(f"/repairs/{other_repair['id']}/model-calls", held_input, expected=202)["id"] == held["id"]
            assert len(received) == before
            checks.append("in_flight_process_stop_preserves_both_scopes_and_never_replays")
            source, another_repair = revise(source, "\n# another fault after restart\n")
            assert call(f"/repairs/{another_repair['id']}/model-calls",
                        request(another_repair, config, "restart-source-001"), expected=429)["code"] == "MODEL_SOURCE_DAILY_BUDGET"
            assert call(quota_path)["usedCalls"] == 3
            checks.append("restart_and_source_edits_do_not_restore_capacity")
        finally:
            stop(process)

    result = dict(passed=len(checks), checks=checks, providerRequests=len(received),
                  jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                  previousJarSha256=hashlib.sha256(previous.read_bytes()).hexdigest(),
                  boundary="Local fake provider; file H2 upgrade and three processes; no production API or source execution")
    if args.json_out:
        args.json_out.write_bytes((json.dumps(result, indent=2) + "\n").encode())
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
