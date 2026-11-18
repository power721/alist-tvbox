"""Verify V15 -> V16 and amount admission using temporary H2/JVMs and loopback providers."""
from __future__ import annotations

import argparse
import base64
from decimal import Decimal
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
    parser.add_argument("--demo-out", type=Path)
    args = parser.parse_args()
    java, python = shutil.which(args.java), shutil.which(args.python)
    if not java or not python:
        parser.error("Java and Python executables must exist")
    if args.demo_out and args.demo_out.exists():
        parser.error("Refuse to overwrite an existing evidence export")
    jar, previous = Path(args.jar).resolve(), Path(args.previous_jar).resolve()
    with zipfile.ZipFile(previous) as archive:
        assert "BOOT-INF/classes/db/migration/h2/V15__ModelUsageCosts.sql" in archive.namelist()
        assert "BOOT-INF/classes/db/migration/h2/V16__ModelCostBudgets.sql" not in archive.namelist()
    with zipfile.ZipFile(jar) as archive:
        assert "BOOT-INF/classes/db/migration/h2/V16__ModelCostBudgets.sql" in archive.namelist()
        h2 = next(n for n in archive.namelist() if n.startswith("BOOT-INF/lib/h2-"))
        h2_bytes = archive.read(h2)
    password = secrets.token_urlsafe(24)
    auth = "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()
    checks, received = [], []
    scenario = "good"

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            raw = self.rfile.read(int(self.headers["Content-Length"]))
            request = json.loads(raw)
            assert self.path == "/v1/chat/completions"
            assert request["stream"] is False and request["store"] is False and "tools" not in request
            received.append(dict(bytes=len(raw), model=request["model"], scenario=scenario))
            data = json.loads(request["messages"][1]["content"])
            proposal = dict(schemaVersion=1, expectedBaselineSha256=data["expectedBaselineSha256"],
                            targetFile="source.py", diagnosis="Remove duplicate", expectedEffect="Static only",
                            validationTargets=["S03"], edits=[dict(
                                oldText="    def homeContent(self, filter):\n        return {'class': []}\n", newText="")])
            prompt = len(raw) + 1 if scenario == "overrun" else 10
            body = json.dumps(dict(choices=[dict(finish_reason="stop", message=dict(
                role="assistant", content=json.dumps(proposal)))],
                usage=dict(prompt_tokens=prompt, completion_tokens=20, total_tokens=prompt + 20))).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    class FakeServer(ThreadingHTTPServer):
        daemon_threads = True

        def __exit__(self, *_):
            self.shutdown()
            self.server_close()

    with FakeServer(("127.0.0.1", 0), Provider) as fake_a, FakeServer(("127.0.0.1", 0), Provider) as fake_b, \
            tempfile.TemporaryDirectory(prefix="at-model-cost-budget-") as temporary:
        for fake in (fake_a, fake_b):
            threading.Thread(target=fake.serve_forever, daemon=True).start()
        work = Path(temporary)
        helper = work / "h2.jar"
        helper.write_bytes(h2_bytes)
        # Clear inherited model configuration so the smoke has no ambient provider/key/budget.
        environment = {k: v for k, v in os.environ.items() if not k.startswith("MAINTENANCE_MODEL_")}
        environment.update(SPRING_SECURITY_USER_NAME="admin", SPRING_SECURITY_USER_PASSWORD=password,
                           MAINTENANCE_PYTHON=str(Path(python).resolve()), MAINTENANCE_DB_PASSWORD="",
                           MAINTENANCE_AT_ORIGIN="", MAINTENANCE_AT_SESSION_TOKEN="",
                           MAINTENANCE_MEDIA_ENABLED="false", MAINTENANCE_SCHEDULING_ENABLED="false",
                           MAINTENANCE_CONTAINER_ENABLED="false", MAINTENANCE_MODEL_ENABLED="true",
                           MAINTENANCE_MODEL_AUTO_SUBMIT_ENABLED="false", MAINTENANCE_MODEL_KEY_ENV="",
                           MAINTENANCE_MODEL_NAME="cost-fixture", MAINTENANCE_MODEL_TIMEOUT_MILLIS="15000",
                           MAINTENANCE_MODEL_MAX_COMPLETION_TOKENS="2048",
                           MAINTENANCE_MODEL_INPUT_PRICE_USD_PER_MILLION="1.25",
                           MAINTENANCE_MODEL_OUTPUT_PRICE_USD_PER_MILLION="2.5")
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        base, csrf = "", {}
        process_count = 0

        def stop(process):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)

        def start(artifact, database, provider):
            nonlocal process_count
            process_count += 1
            environment["MAINTENANCE_MODEL_ENDPOINT"] = f"http://127.0.0.1:{provider.server_port}/v1/chat/completions"
            log = work / f"server-{process_count}.log"
            datasource = "jdbc:h2:file:" + (work / database).as_posix() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
            with log.open("wb") as output:
                process = subprocess.Popen([java, "-jar", str(artifact), "--server.port=0",
                                            "--server.address=127.0.0.1", "--spring.datasource.url=" + datasource],
                                           cwd=work, env=environment, stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 60
            try:
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError("Cost-budget smoke server exited")
                    match = re.search(r"Tomcat started on port (\d+)", log.read_text(encoding="utf-8", errors="replace"))
                    if match:
                        return process, f"http://127.0.0.1:{match.group(1)}"
                    time.sleep(0.2)
                raise RuntimeError("Cost-budget smoke startup timeout")
            except (RuntimeError, OSError):
                stop(process)
                print(log.read_text(encoding="utf-8", errors="replace")[-2000:].replace(password, "[REDACTED]"))
                raise

        def call(path, body=None, *, method=None, expected=200, binary=False):
            headers = {"Authorization": auth}
            data = None
            if body is not None:
                data = json.dumps(body).encode()
                headers.update({"Content-Type": "application/json", csrf["headerName"]: csrf["token"]})
            try:
                response = opener.open(urllib.request.Request(base + "/api/plugin-maintenance" + path,
                                                              data=data, headers=headers, method=method), timeout=20)
            except urllib.error.HTTPError as error:
                with error:
                    value = json.loads(error.read())
                assert error.code == expected, (error.code, expected, value)
                return value
            with response:
                assert response.status == expected
                body = response.read()
                return body if binary else json.loads(body)

        def await_value(path):
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                value = call(path)
                if value["status"] not in ("QUEUED", "RUNNING", "CANCELLING"):
                    return value
                time.sleep(0.1)
            raise AssertionError("Cost-budget smoke work did not finish")

        def new_source():
            fixture = call("/fixtures/repair")
            source = call("/sources", {key: fixture[key] for key in ("name", "filename", "content")}, expected=201)
            probe = call(f"/sources/{source['id']}/probe-runs", {"mode": "static"}, expected=202)
            assert await_value(f"/probe-runs/{probe['id']}")["status"] == "COMPLETED"
            repair = call(f"/sources/{source['id']}/repairs",
                          dict(baselineRunId=probe["id"], expectedArtifactSha256=source["artifactSha256"]))
            call(f"/sources/{source['id']}/model-consent", dict(
                expectedArtifactSha256=None, artifactSha256=source["artifactSha256"]), method="PUT")
            return source, repair

        def request(repair, config, key):
            return dict(expectedFaultSha256=repair["faultSha256"],
                        expectedConfigurationSha256=config["configurationSha256"], requestKey=key)

        def generate(repair, config, key):
            value = call(f"/repairs/{repair['id']}/model-calls", request(repair, config, key), expected=202)
            return await_value(f"/model-calls/{value['id']}")

        def legacy_json_hashes(call_id):
            columns = ("request_json", "budget_json", "usage_json", "cost_json")
            query = " UNION ALL ".join(
                f"SELECT '{column}' AS field, RAWTOHEX(HASH('SHA-256', STRINGTOUTF8({column}))) AS digest "
                f"FROM maintenance_model_call WHERE id='{call_id}'" for column in columns)
            result = subprocess.run([java, "-cp", str(helper), "org.h2.tools.Shell", "-url",
                                     "jdbc:h2:file:" + (work / "legacy").as_posix(), "-user", "sa", "-password", "",
                                     "-sql", query], capture_output=True, check=True, text=True, encoding="utf-8")
            assert "Error" not in result.stdout
            hashes = dict(re.findall(r"(?m)^(request_json|budget_json|usage_json|cost_json)\s+\|\s+([0-9a-fA-F]{64})\s*$",
                                     result.stdout))
            assert set(hashes) == set(columns), result.stdout
            return hashes

        # A genuine V15 call has usage/rates, but no contractual maximum reservation.
        process, base = start(previous, "legacy", fake_a)
        try:
            csrf.update(call("/csrf"))
            legacy_source, legacy_repair = new_source()
            legacy_config = call("/model/connection")
            legacy_call = generate(legacy_repair, legacy_config, "legacy-cost-001")
            assert legacy_call["status"] == "PROPOSED" and legacy_call["costEstimate"] is not None
        finally:
            stop(process)
        legacy_hashes = legacy_json_hashes(legacy_call["id"])
        environment.update(MAINTENANCE_MODEL_DAILY_COST_USD="1",
                           MAINTENANCE_MODEL_SOURCE_DAILY_COST_USD="1",
                           MAINTENANCE_MODEL_MODEL_DAILY_COST_USD="1",
                           MAINTENANCE_MODEL_INPUT_TOKEN_BOUND="request_utf8_bytes_v1")
        process, base = start(jar, "legacy", fake_a)
        try:
            csrf.update(call("/csrf"))
            assert call(f"/model-calls/{legacy_call['id']}") == legacy_call
            assert call("/model/budget")["reservedCostUsd"] is None
            before = len(received)
            assert call(f"/repairs/{legacy_repair['id']}/model-calls", request(
                legacy_repair, call("/model/connection"), "legacy-cost-002"), expected=409)["code"] == "MODEL_COST_HISTORY_UNKNOWN"
            assert call(f"/repairs/{legacy_repair['id']}/model-calls", request(
                legacy_repair, legacy_config, "legacy-cost-001"), expected=202)["id"] == legacy_call["id"]
            assert len(received) == before
            checks.append("v15_call_usage_cost_and_snapshot_unchanged_unknown_history_rejected_without_send")
        finally:
            stop(process)

        # A clean V16 file database records nonzero input/output rates and all three scopes.
        process, base = start(jar, "priced", fake_a)
        try:
            csrf.update(call("/csrf"))
            source, repair = new_source()
            config = call("/model/connection")
            accepted = generate(repair, config, "priced-cost-001")
            assert accepted["status"] == "PROPOSED"
            frozen = accepted["budgetReservation"]
            maximum = (Decimal(accepted["requestBytes"]) * Decimal("1.25") + Decimal(2048) * Decimal("2.5")) / 1000000
            assert frozen["schemaVersion"] == 4
            assert frozen["costReservation"]["inputTokenBound"] == "request_utf8_bytes_v1"
            assert Decimal(frozen["costReservation"]["maximumUsd"]) == maximum
            for path in ("/model/budget", f"/sources/{source['id']}/model-budget", "/model/named-budget"):
                assert Decimal(call(path)["reservedCostUsd"]) == maximum
            assert accepted["costEstimate"]["totalUsd"] == "0.0000625"
            frozen_global = call("/model/budget")
            checks.append("nonzero_input_output_rates_reserve_exact_maximum_atomically_in_three_scopes")
            candidate = call(f"/model-calls/{accepted['id']}/submit",
                             dict(expectedProposalSha256=accepted["proposalSha256"]), expected=202)
            candidate = await_value(f"/repair-candidates/{candidate['id']}")
            assert candidate["status"] == "STATIC_VALIDATED" and candidate["result"]["publishEligible"] is False
            if args.demo_out:
                args.demo_out.write_bytes(call(f"/repair-candidates/{candidate['id']}/export", binary=True))
            accepted = call(f"/model-calls/{accepted['id']}")
            checks.append("exported_candidate_keeps_reservation_and_remains_static_only")
        finally:
            stop(process)
        environment.update(MAINTENANCE_MODEL_DAILY_COST_USD=str(maximum),
                           MAINTENANCE_MODEL_INPUT_PRICE_USD_PER_MILLION="9",
                           MAINTENANCE_MODEL_OUTPUT_PRICE_USD_PER_MILLION="10")
        process, base = start(jar, "priced", fake_b)
        try:
            csrf.update(call("/csrf"))
            current = call("/model/connection")
            assert current["configurationSha256"] != config["configurationSha256"]
            assert call(f"/model-calls/{accepted['id']}") == accepted
            assert call("/model/budget")["reservedCostUsd"] == frozen_global["reservedCostUsd"]
            before = len(received)
            assert call(f"/repairs/{repair['id']}/model-calls", request(
                repair, config, "priced-cost-001"), expected=202)["id"] == accepted["id"]
            _, other_repair = new_source()
            assert call(f"/repairs/{other_repair['id']}/model-calls", request(
                other_repair, current, "priced-cost-002"), expected=429)["code"] == "MODEL_COST_DAILY_BUDGET"
            assert call("/model/budget")["usedCalls"] == 1 and len(received) == before
            checks.append("restart_endpoint_and_price_changes_preserve_amount_and_old_key_never_resends")
        finally:
            stop(process)

        environment["MAINTENANCE_MODEL_DAILY_COST_USD"] = "1"
        scenario = "overrun"
        process, base = start(jar, "breach", fake_a)
        try:
            csrf.update(call("/csrf"))
            _, breach_repair = new_source()
            breach_config = call("/model/connection")
            breach = generate(breach_repair, breach_config, "breach-cost-001")
            assert breach["status"] == "FAILED" and breach["errorCode"] == "MODEL_COST_RESERVATION_EXCEEDED"
            assert breach["usage"] is not None and breach["costEstimate"] is not None and breach["proposal"] is None
            breach_day = call("/model/budget")["costBreachDay"]
            assert breach_day is not None
        finally:
            stop(process)
        scenario = "good"
        process, base = start(jar, "breach", fake_b)
        try:
            csrf.update(call("/csrf"))
            assert call("/model/budget")["costBreachDay"] == breach_day
            before = len(received)
            assert call(f"/repairs/{breach_repair['id']}/model-calls", request(
                breach_repair, call("/model/connection"), "breach-cost-002"), expected=409)["code"] == "MODEL_COST_RESERVATION_EXCEEDED"
            assert len(received) == before and call("/model/budget")["usedCalls"] == 1
            checks.append("provider_overrun_retains_evidence_and_blocks_sends_across_process_restart")
        finally:
            stop(process)

        assert legacy_json_hashes(legacy_call["id"]) == legacy_hashes
        checks.append("v16_file_reopen_preserves_v15_request_budget_usage_cost_json_byte_hashes")

    result = dict(schemaVersion=1, passed=len(checks), processes=process_count, checks=checks,
                  providerRequests=len(received), jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                  previousJarSha256=hashlib.sha256(previous.read_bytes()).hexdigest(),
                  boundary="Loopback fake providers, temporary file H2 and Windows JVM termination only; "
                           "no real provider billing, production AT, unknown source execution, Linux isolation or runtime promotion")
    if args.json_out:
        args.json_out.write_bytes((json.dumps(result, indent=2) + "\n").encode())
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
