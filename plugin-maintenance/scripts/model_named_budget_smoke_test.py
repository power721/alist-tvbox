"""Verify V13 -> V14 and model switching with temporary H2/JVMs and loopback fake providers."""
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
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import zipfile


def main():
    parser = argparse.ArgumentParser(description=(
        "Verify V14 -> V15 provider usage and frozen-rate estimates with temporary H2/JVMs and loopback fake providers."
        if "--usage-costs" in sys.argv else __doc__))
    for name in ("java", "python", "jar", "previous-jar"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--usage-costs", action="store_true",
                        help=argparse.SUPPRESS)
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
        migrations = archive.namelist()
        if args.usage_costs:
            assert "BOOT-INF/classes/db/migration/h2/V14__ModelNamedBudgets.sql" in migrations
            assert "BOOT-INF/classes/db/migration/h2/V15__ModelUsageCosts.sql" not in migrations
        else:
            assert "BOOT-INF/classes/db/migration/h2/V13__MediaBudgets.sql" in migrations
            assert "BOOT-INF/classes/db/migration/h2/V14__ModelNamedBudgets.sql" not in migrations
    with zipfile.ZipFile(jar) as archive:
        assert ("BOOT-INF/classes/db/migration/h2/V15__ModelUsageCosts.sql" in archive.namelist()) == args.usage_costs
    checks, received = [], []
    held = threading.Event()
    entered, release = threading.Event(), threading.Event()
    password = secrets.token_urlsafe(24)
    auth = "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            assert self.path == "/v1/chat/completions"
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            received.append(request["model"])
            data = json.loads(request["messages"][1]["content"])
            assert request["stream"] is False and request["store"] is False and "tools" not in request
            if held.is_set():
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
        daemon_threads = True

        def __exit__(self, *_):
            self.shutdown()
            self.server_close()

    with FakeServer(("127.0.0.1", 0), Provider) as fake_a, FakeServer(("127.0.0.1", 0), Provider) as fake_b, \
            tempfile.TemporaryDirectory(prefix="at-model-named-budget-") as temporary:
        for fake in (fake_a, fake_b):
            threading.Thread(target=fake.serve_forever, daemon=True).start()
        work = Path(temporary)
        environment = dict(os.environ, SPRING_SECURITY_USER_NAME="admin", SPRING_SECURITY_USER_PASSWORD=password,
                           MAINTENANCE_PYTHON=str(Path(python).resolve()), MAINTENANCE_DB_PASSWORD="",
                           MAINTENANCE_AT_ORIGIN="", MAINTENANCE_AT_SESSION_TOKEN="",
                           MAINTENANCE_MEDIA_ENABLED="false", MAINTENANCE_SCHEDULING_ENABLED="false",
                           MAINTENANCE_CONTAINER_ENABLED="false", MAINTENANCE_MODEL_ENABLED="true",
                           MAINTENANCE_MODEL_AUTO_SUBMIT_ENABLED="false", MAINTENANCE_MODEL_KEY_ENV="",
                           MAINTENANCE_MODEL_TIMEOUT_MILLIS="15000",
                           MAINTENANCE_MODEL_MAX_REQUEST_BYTES="65536", MAINTENANCE_MODEL_MAX_RESPONSE_BYTES="65536",
                           MAINTENANCE_MODEL_MAX_COMPLETION_TOKENS="2048",
                           MAINTENANCE_MODEL_DAILY_CALL_LIMIT="8", MAINTENANCE_MODEL_DAILY_INPUT_BYTES="262144",
                           MAINTENANCE_MODEL_DAILY_OUTPUT_TOKENS="16384",
                           MAINTENANCE_MODEL_SOURCE_DAILY_CALL_LIMIT="3",
                           MAINTENANCE_MODEL_SOURCE_DAILY_INPUT_BYTES="131072",
                           MAINTENANCE_MODEL_SOURCE_DAILY_OUTPUT_TOKENS="6144",
                           MAINTENANCE_MODEL_MODEL_DAILY_CALL_LIMIT="4",
                           MAINTENANCE_MODEL_MODEL_DAILY_INPUT_BYTES="131072",
                           MAINTENANCE_MODEL_MODEL_DAILY_OUTPUT_TOKENS="8192",
                           MAINTENANCE_MODEL_INPUT_PRICE_USD_PER_MILLION="",
                           MAINTENANCE_MODEL_OUTPUT_PRICE_USD_PER_MILLION="")
        datasource = "jdbc:h2:file:" + (work / "db").as_posix() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        base, csrf = "", {}

        def stop(process):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)

        def start(index, artifact, model, provider):
            environment["MAINTENANCE_MODEL_NAME"] = model
            environment["MAINTENANCE_MODEL_ENDPOINT"] = f"http://127.0.0.1:{provider.server_port}/v1/chat/completions"
            log = work / f"server-{index}.log"
            with log.open("wb") as output:
                process = subprocess.Popen([java, "-jar", str(artifact), "--server.port=0",
                                            "--server.address=127.0.0.1", "--spring.datasource.url=" + datasource],
                                           cwd=work, env=environment, stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 60
            try:
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError("Named-budget smoke server exited")
                    match = re.search(r"Tomcat started on port (\d+)", log.read_text(encoding="utf-8", errors="replace"))
                    if match:
                        return process, f"http://127.0.0.1:{match.group(1)}"
                    time.sleep(0.2)
                raise RuntimeError("Named-budget smoke startup timeout")
            except (RuntimeError, OSError):
                stop(process)
                print(log.read_text(encoding="utf-8", errors="replace")[-4000:].replace(password, "[REDACTED]"))
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
                assert error.code == expected, (error.code, expected, error.read())
                with error:
                    return json.loads(error.read())
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
            raise AssertionError("Named-budget smoke work did not finish")

        def new_source():
            source = call("/sources", {key: fixture[key] for key in ("name", "filename", "content")}, expected=201)
            probe = call(f"/sources/{source['id']}/probe-runs", {"mode": "static"}, expected=202)
            assert await_value(f"/probe-runs/{probe['id']}")["status"] == "COMPLETED"
            repair = call(f"/sources/{source['id']}/repairs",
                          dict(baselineRunId=probe["id"], expectedArtifactSha256=source["artifactSha256"]))
            consent = call(f"/sources/{source['id']}/model-consent")
            call(f"/sources/{source['id']}/model-consent", dict(
                expectedArtifactSha256=consent["artifactSha256"], artifactSha256=source["artifactSha256"]), method="PUT")
            return source, repair

        def request(repair, config, key):
            return dict(expectedFaultSha256=repair["faultSha256"],
                        expectedConfigurationSha256=config["configurationSha256"], requestKey=key)

        def generate(repair, config, key):
            value = call(f"/repairs/{repair['id']}/model-calls", request(repair, config, key), expected=202)
            return await_value(f"/model-calls/{value['id']}")

        process, base = start(1, previous, "fixture-A", fake_a)
        try:
            csrf.update(call("/csrf"))
            fixture = call("/fixtures/repair")
            old_config = call("/model/connection")
            source, old_repair = new_source()
            old_call = generate(old_repair, old_config, "old-named-call-001")
            failed = generate(old_repair, old_config, "old-named-call-002")
            assert old_call["status"] == "PROPOSED"
            assert failed["errorCode"] == "REPEATED_MODEL_PROPOSAL"
            old_budget = call("/model/budget")
            old_source_budget = call(f"/sources/{source['id']}/model-budget")
            assert old_call["budgetReservation"]["schemaVersion"] == (2 if args.usage_costs else 1)
            checks.append(("v14" if args.usage_costs else "v13") + "_two_accepted_calls_include_repeated_failure")
        finally:
            stop(process)

        if args.usage_costs:
            environment["MAINTENANCE_MODEL_INPUT_PRICE_USD_PER_MILLION"] = "1.25"
            environment["MAINTENANCE_MODEL_OUTPUT_PRICE_USD_PER_MILLION"] = "2.5"
        process, base = start(2, jar, "fixture-A", fake_a)
        try:
            csrf.update(call("/csrf"))
            config = call("/model/connection")
            assert config["configurationSha256"] != old_config["configurationSha256"]
            assert call("/model/budget") == old_budget
            assert call(f"/sources/{source['id']}/model-budget") == old_source_budget
            restored_call = call(f"/model-calls/{old_call['id']}")
            if args.usage_costs:
                assert restored_call.pop("costEstimate") is None
            assert restored_call == old_call
            named = call("/model/named-budget")
            assert named["usedCalls"] == 2 and named["reservedOutputTokens"] == 4096
            assert named["reservedInputBytes"] == old_call["requestBytes"] + failed["requestBytes"]
            if args.usage_costs:
                assert named["reportedUsageCalls"] == 1 and named["pricedCalls"] == 0
                assert named["promptTokens"] == 10 and named["completionTokens"] == 20
                assert named["totalTokens"] == 30 and named["estimatedCostUsd"] is None
                assert named["callsWithoutUsage"] == 1
                checks.append("v15_reads_legacy_provider_tokens_without_inventing_old_rates")
            assert ("modelLimits" in old_call["budgetReservation"]) == args.usage_costs
            checks.append("upgrade_retains_all_existing_scopes_and_original_call_snapshot")
            before = len(received)
            replay = call(f"/repairs/{old_repair['id']}/model-calls",
                          request(old_repair, old_config, "old-named-call-001"), expected=202)
            assert replay["id"] == old_call["id"] and len(received) == before
            assert call("/model/named-budget") == named
            checks.append("old_configuration_key_replay_never_sends_or_charges")
            candidate_source, candidate_repair = new_source()
            accepted = generate(candidate_repair, config, "new-named-call-001")
            assert accepted["status"] == "PROPOSED"
            assert accepted["budgetReservation"]["schemaVersion"] == (3 if args.usage_costs else 2)
            assert accepted["budgetReservation"]["modelName"] == "fixture-A"
            assert accepted["budgetReservation"]["modelLimits"]["calls"] == 4
            if args.usage_costs:
                assert accepted["budgetReservation"]["pricing"] == {
                    "currency": "USD", "inputPerMillion": "1.25", "outputPerMillion": "2.5"}
                assert accepted["costEstimate"]["totalUsd"] == "0.0000625"
                priced = call("/model/named-budget")
                assert priced["reportedUsageCalls"] == 2 and priced["pricedCalls"] == 1
                assert priced["totalTokens"] == 60 and priced["estimatedCostUsd"] == "0.0000625"
                checks.append("new_usage_is_priced_from_frozen_rates_and_aggregated")
            candidate = call(f"/model-calls/{accepted['id']}/submit",
                             {"expectedProposalSha256": accepted["proposalSha256"]}, expected=202)
            candidate = await_value(f"/repair-candidates/{candidate['id']}")
            assert candidate["status"] == "STATIC_VALIDATED"
            assert candidate["result"]["publishEligible"] is False
            assert call(f"/sources/{candidate_source['id']}")["artifactSha256"] == candidate_source["artifactSha256"]
            demo = call(f"/repair-candidates/{candidate['id']}/export", binary=True)
            if args.demo_out:
                args.demo_out.write_bytes(demo)
            checks.append("candidate_export_retains_model_reservation_without_runtime_promotion")
            held_source, held_repair = new_source()
            held.set()
            held_input = request(held_repair, config, "held-named-call-001")
            active = call(f"/repairs/{held_repair['id']}/model-calls", held_input, expected=202)
            assert entered.wait(10)
            held_named = call("/model/named-budget")
            held_global = call("/model/budget")
            held_source_budget = call(f"/sources/{held_source['id']}/model-budget")
            assert held_named["usedCalls"] == 4
        finally:
            stop(process)
            release.set()
            held.clear()

        process, base = start(3, jar, "fixture-A", fake_b)
        try:
            csrf.update(call("/csrf"))
            moved_config = call("/model/connection")
            assert moved_config["configurationSha256"] != config["configurationSha256"]
            assert call(f"/model-calls/{active['id']}")["status"] == "INTERRUPTED"
            assert call("/model/named-budget") == held_named
            assert call(f"/sources/{held_source['id']}/model-budget") == held_source_budget
            restored_global = call("/model/budget")
            assert {k: v for k, v in restored_global.items() if k != "activeCallId"} == {
                k: v for k, v in held_global.items() if k != "activeCallId"}
            before = len(received)
            assert call(f"/repairs/{held_repair['id']}/model-calls", held_input, expected=202)["id"] == active["id"]
            assert call(f"/repairs/{held_repair['id']}/model-calls",
                        request(held_repair, moved_config, "held-named-call-001"), expected=409)["code"] == "MODEL_REQUEST_KEY_CONFLICT"
            checks.append("in_flight_stop_keeps_three_scopes_and_configuration_conflict_never_retries")
            assert call(f"/repairs/{held_repair['id']}/model-calls",
                        request(held_repair, moved_config, "changed-endpoint-001"), expected=429)["code"] == "MODEL_NAME_DAILY_BUDGET"
            assert call("/model/budget") == restored_global and call("/model/named-budget") == held_named
            assert len(received) == before
            checks.append("same_model_new_endpoint_cannot_restore_capacity")
        finally:
            stop(process)

        if args.usage_costs:
            environment["MAINTENANCE_MODEL_INPUT_PRICE_USD_PER_MILLION"] = "0"
            environment["MAINTENANCE_MODEL_OUTPUT_PRICE_USD_PER_MILLION"] = "0"
        process, base = start(4, jar, "fixture-B", fake_b)
        try:
            csrf.update(call("/csrf"))
            other_config = call("/model/connection")
            assert call("/model/named-budget")["budgetDay"] is None
            other_source, other_repair = new_source()
            other_call = generate(other_repair, other_config, "other-model-001")
            assert other_call["status"] == "PROPOSED"
            assert call("/model/named-budget")["usedCalls"] == 1
            assert call("/model/budget")["usedCalls"] == 5
            checks.append("different_model_gets_own_bucket_while_global_budget_accumulates")
            if args.usage_costs:
                assert float(other_call["costEstimate"]["totalUsd"]) == 0
                repeat = generate(other_repair, other_config, "other-model-002")
                assert repeat["errorCode"] == "REPEATED_MODEL_PROPOSAL"
                assert repeat["usage"]["total_tokens"] == 30
                assert float(repeat["costEstimate"]["totalUsd"]) == 0
                assert call("/model/named-budget")["reportedUsageCalls"] == 2
                assert call("/model/named-budget")["totalTokens"] == 60
                checks.append("repeated_rejected_proposal_retains_usage_and_explicit_zero_price")
        finally:
            stop(process)

        # Changing a limit changes configuration identity, never the model ledger identity.
        environment["MAINTENANCE_MODEL_MODEL_DAILY_CALL_LIMIT"] = "3"
        if args.usage_costs:
            environment["MAINTENANCE_MODEL_INPUT_PRICE_USD_PER_MILLION"] = "9"
            environment["MAINTENANCE_MODEL_OUTPUT_PRICE_USD_PER_MILLION"] = "10"
        process, base = start(5, jar, "fixture-A", fake_a)
        try:
            csrf.update(call("/csrf"))
            current = call("/model/connection")
            assert current["configurationSha256"] != config["configurationSha256"]
            restored = call("/model/named-budget")
            assert restored["usedCalls"] == 4 and restored["dailyCallLimit"] == 3
            before = len(received)
            assert call(f"/repairs/{held_repair['id']}/model-calls",
                        request(held_repair, current, "return-model-001"), expected=429)["code"] == "MODEL_NAME_DAILY_BUDGET"
            assert call("/model/budget")["usedCalls"] == (6 if args.usage_costs else 5) and len(received) == before
            checks.append("return_to_model_and_lower_limit_keeps_previous_reservations")
            if args.usage_costs:
                unchanged = call(f"/model-calls/{accepted['id']}")
                assert unchanged["costEstimate"] == accepted["costEstimate"]
                assert unchanged["budgetReservation"]["pricing"] == accepted["budgetReservation"]["pricing"]
                assert restored["estimatedCostUsd"] == "0.0000625"
                assert restored["reportedUsageCalls"] == 2 and restored["pricedCalls"] == 1
                checks.append("price_changes_and_restarts_never_reprice_accepted_calls")
        finally:
            stop(process)

    result = dict(schemaVersion=1, passed=len(checks), processes=5, checks=checks,
                  providerRequests=len(received), modelsReceived=received,
                  jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                  previousJarSha256=hashlib.sha256(previous.read_bytes()).hexdigest(),
                  boundary="Loopback fake providers, temporary file H2, Windows process termination and static data only; "
                           "no real model, production AT, unknown crawler execution or runtime promotion")
    if args.json_out:
        args.json_out.write_bytes((json.dumps(result, indent=2) + "\n").encode())
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
