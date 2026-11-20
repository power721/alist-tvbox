"""Verify V8 -> V9, model provenance and explicit auto submission across temporary file-DB restarts."""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import http.cookiejar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
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
    parser.add_argument("--java", required=True)
    parser.add_argument("--python", required=True)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--previous-jar", required=True, type=Path, help="The retained 0.9 JAR (V8)")
    parser.add_argument("--archive-out", type=Path)
    args = parser.parse_args()
    java, python = shutil.which(args.java), shutil.which(args.python)
    if not java or not python:
        parser.error("--java and --python must name existing executables or PATH commands")
    jar, previous = args.jar.resolve(), args.previous_jar.resolve()
    identities = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in (jar, previous)}
    with zipfile.ZipFile(previous) as archive:
        assert "BOOT-INF/classes/db/migration/h2/V8__ModelProposals.sql" in archive.namelist()
        assert "BOOT-INF/classes/db/migration/h2/V9__ModelCandidateSubmission.sql" not in archive.namelist()
    password, fake_key = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
    auth = "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()
    received, checks, fixture = [], [], {}
    entered, release = threading.Event(), threading.Event()
    scenario = "good"

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            assert self.path == "/v1/chat/completions"
            assert self.headers.get("Authorization") == "Bearer " + fake_key
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            received.append(request)
            assert request["stream"] is False and request["store"] is False and "tools" not in request
            data = json.loads(request["messages"][1]["content"])
            assert data["source"] == fixture["content"]
            proposal = copy.deepcopy(fixture["proposal"])
            proposal["expectedBaselineSha256"] = data["expectedBaselineSha256"]
            proposal["edits"] = [dict(oldText="    def homeContent(self, filter):\n        return {'class': []}\n",
                                      newText="")]
            if scenario == "held":
                entered.set()
                assert release.wait(40)
            body = json.dumps(dict(
                choices=[dict(finish_reason="stop", message=dict(role="assistant", content=json.dumps(proposal)))],
                usage=dict(prompt_tokens=10, completion_tokens=20, total_tokens=30)), ensure_ascii=False).encode()
            try:
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Provider) as fake, tempfile.TemporaryDirectory(
            prefix="at-maintenance-submission-smoke-") as temporary:
        fake.daemon_threads = True
        thread = threading.Thread(target=fake.serve_forever, daemon=True)
        thread.start()
        work = Path(temporary)
        environment = dict(os.environ, SPRING_SECURITY_USER_NAME="admin", SPRING_SECURITY_USER_PASSWORD=password,
                           MAINTENANCE_PYTHON=str(Path(python).resolve()), MAINTENANCE_DB_PASSWORD="",
                           MAINTENANCE_AT_ORIGIN="", MAINTENANCE_AT_SESSION_TOKEN="",
                           MAINTENANCE_SCHEDULING_ENABLED="false", MAINTENANCE_CONTAINER_ENABLED="false",
                           MAINTENANCE_MODEL_ENABLED="true",
                           MAINTENANCE_MODEL_ENDPOINT=f"http://127.0.0.1:{fake.server_port}/v1/chat/completions",
                           MAINTENANCE_MODEL_NAME="offline-fixture", MAINTENANCE_MODEL_KEY_ENV="MAINTENANCE_FAKE_MODEL_KEY",
                           MAINTENANCE_FAKE_MODEL_KEY=fake_key, MAINTENANCE_MODEL_TIMEOUT_MILLIS="15000",
                           MAINTENANCE_MODEL_DAILY_CALL_LIMIT="8", MAINTENANCE_MODEL_MAX_REQUEST_BYTES="65536",
                           MAINTENANCE_MODEL_MAX_RESPONSE_BYTES="65536", MAINTENANCE_MODEL_MAX_COMPLETION_TOKENS="2048",
                           MAINTENANCE_MODEL_DAILY_INPUT_BYTES="262144", MAINTENANCE_MODEL_DAILY_OUTPUT_TOKENS="16384")
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

        def start(index, artifact, automatic):
            log = work / f"server-{index}.log"
            env = dict(environment, MAINTENANCE_MODEL_AUTO_SUBMIT_ENABLED=str(automatic).lower())
            with log.open("wb") as output:
                process = subprocess.Popen([java, "-jar", str(artifact), "--server.port=0", "--server.address=127.0.0.1",
                                            "--spring.datasource.url=" + datasource],
                                           cwd=work, env=env, stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError("Submission smoke server exited")
                    match = re.search(r"Tomcat started on port (\d+)", log.read_text(encoding="utf-8", errors="replace"))
                    if match:
                        return process, f"http://127.0.0.1:{match.group(1)}"
                    time.sleep(0.2)
                raise RuntimeError("Submission smoke startup timeout")
            except (RuntimeError, OSError):
                stop(process)
                print(log.read_text(encoding="utf-8", errors="replace")[-4000:].replace(password, "[REDACTED]"))
                raise

        def call(path, body=None, *, method=None, binary=False, expected=200):
            headers = {"Authorization": auth}
            data = None
            if body is not None:
                data = json.dumps(body, ensure_ascii=False).encode("utf-8")
                headers.update({"Content-Type": "application/json", csrf["headerName"]: csrf["token"]})
            request = urllib.request.Request(base + "/api/plugin-maintenance" + path, data=data,
                                             headers=headers, method=method)
            try:
                response = opener.open(request, timeout=20)
            except urllib.error.HTTPError as error:
                assert error.code == expected, (error.code, expected, error.read().decode("utf-8", errors="replace"))
                with error:
                    return json.loads(error.read())
            with response:
                assert response.status == expected, response.status
                body = response.read()
            return body if binary else json.loads(body)

        def await_value(path, submission=False):
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                value = call(path)
                if submission:
                    if value["submissionStatus"] == "SUBMITTED":
                        return value
                    assert value["submissionStatus"] not in ("FAILED", "INTERRUPTED", "SKIPPED"), value
                elif value["status"] not in ("QUEUED", "RUNNING", "CANCELLING"):
                    return value
                time.sleep(0.1)
            raise AssertionError("Submission smoke work did not finish")

        def begin():
            source = call("/sources", {key: fixture[key] for key in ("name", "filename", "content")}, expected=201)
            probe = call(f"/sources/{source['id']}/probe-runs", {"mode": "static"}, expected=202)
            assert await_value(f"/probe-runs/{probe['id']}")["status"] == "COMPLETED"
            repair = call(f"/sources/{source['id']}/repairs",
                          dict(baselineRunId=probe["id"], expectedArtifactSha256=source["artifactSha256"]))
            call(f"/sources/{source['id']}/model-consent",
                 dict(expectedArtifactSha256=None, artifactSha256=source["artifactSha256"]), method="PUT")
            return source, repair

        def request(repair, config, key, automatic=False):
            result = dict(expectedFaultSha256=repair["faultSha256"],
                          expectedConfigurationSha256=config["configurationSha256"], requestKey=key)
            if automatic:
                result["autoSubmit"] = True
            return result

        def generate(repair, config, key, automatic=False):
            result = call(f"/repairs/{repair['id']}/model-calls",
                          request(repair, config, key, automatic), expected=202)
            return await_value(f"/model-calls/{result['id']}", submission=automatic)

        process, base = start(1, previous, False)
        try:
            csrf.update(call("/csrf"))
            fixture.update(call("/fixtures/repair"))
            config_old = call("/model/connection")
            source_old, repair_old = begin()
            call_old = generate(repair_old, config_old, "legacy-review-001")
            source_unbound, repair_unbound = begin()
            unbound_call = generate(repair_unbound, config_old, "legacy-unbound-001")
            unbound = call(f"/repairs/{repair_unbound['id']}/proposals", unbound_call["proposal"], expected=202)
            unbound = await_value(f"/repair-candidates/{unbound['id']}")
            budget_old = call("/model/budget")
        finally:
            stop(process)
        process, base = start(2, jar, False)
        try:
            csrf.update(call("/csrf"))
            config_off = call("/model/connection")
            assert config_off["configurationSha256"] == config_old["configurationSha256"]
            assert config_off["autoSubmitEnabled"] is False
            legacy = call(f"/model-calls/{call_old['id']}")
            assert {key: legacy[key] for key in call_old} == call_old
            assert legacy["autoSubmit"] is False and legacy["submissionStatus"] == "NOT_REQUESTED"
            assert legacy["candidateId"] is None and legacy["submissionMethod"] is None
            assert call("/model/budget") == budget_old
            migrated_unbound = call(f"/repair-candidates/{unbound['id']}")
            assert {key: migrated_unbound[key] for key in unbound} == unbound
            assert migrated_unbound["modelCallId"] is None
            checks.append("v8_to_v9_preserves_legacy_call_candidate_budget_and_disabled_configuration_identity")
            assert call(f"/repairs/{repair_old['id']}/model-calls",
                        request(repair_old, config_off, "auto-disabled-001", True), expected=409)[
                            "code"] == "MODEL_AUTO_SUBMIT_DISABLED"
            assert call("/model/budget") == budget_old and len(received) == 2
            checks.append("global_auto_switch_off_rejects_opt_in_before_model_spend")
            assert call(f"/model-calls/{legacy['id']}/submit",
                        dict(expectedProposalSha256="0" * 64), expected=409)["code"] == "MODEL_PROPOSAL_CHANGED"
            bound = call(f"/model-calls/{legacy['id']}/submit",
                         dict(expectedProposalSha256=legacy["proposalSha256"]), expected=202)
            bound = await_value(f"/repair-candidates/{bound['id']}")
            assert bound["modelCallId"] == legacy["id"] and bound["status"] == "STATIC_VALIDATED"
            assert call(f"/sources/{source_old['id']}")["artifactSha256"] == source_old["artifactSha256"]
            assert call("/model/budget") == budget_old
            checks.append("explicit_reviewed_submission_binds_legacy_proposal_without_model_cost_or_source_switch")
            with zipfile.ZipFile(io.BytesIO(call(f"/repair-candidates/{unbound['id']}/export", binary=True))) as archive:
                assert json.loads(archive.read("provenance.json"))["origin"] == "unbound_proposal"
            checks.append("historical_pasted_proposal_never_gains_inferred_model_provenance")
        finally:
            stop(process)
        process, base = start(3, jar, True)
        try:
            csrf.update(call("/csrf"))
            config_auto = call("/model/connection")
            assert config_auto["autoSubmitEnabled"] is True
            assert config_auto["configurationSha256"] != config_off["configurationSha256"]
            source_auto, repair_auto = begin()
            automatic = generate(repair_auto, config_auto, "automatic-good-001", True)
            candidate = await_value(f"/repair-candidates/{automatic['candidateId']}")
            assert automatic["submissionMethod"] == "AUTO" and candidate["modelCallId"] == automatic["id"]
            assert candidate["status"] == "STATIC_VALIDATED" and candidate["result"]["publishEligible"] is False
            assert call(f"/repairs/{repair_auto['id']}")["status"] == "AWAITING_RUNTIME_VALIDATION"
            assert call(f"/repairs/{repair_auto['id']}")["usedRounds"] == 1
            assert call(f"/sources/{source_auto['id']}")["artifactSha256"] == source_auto["artifactSha256"]
            checks.append("two_explicit_switches_submit_once_through_static_gate_without_runtime_promotion")
            exported = call(f"/repair-candidates/{candidate['id']}/export", binary=True)
            with zipfile.ZipFile(io.BytesIO(exported)) as archive:
                provenance = json.loads(archive.read("provenance.json"))
                assert provenance["modelCallId"] == automatic["id"]
                assert provenance["proposalSha256"] == candidate["proposalSha256"] == automatic["proposalSha256"]
                assert provenance["submissionMethod"] == "AUTO"
                assert json.loads(archive.read("model-calls.json")) == [automatic]
                manifest = json.loads(archive.read("manifest.json"))
                for name, item in manifest["files"].items():
                    body = archive.read(name)
                    assert len(body) == item["bytes"] and hashlib.sha256(body).hexdigest() == item["sha256"]
                    assert fake_key.encode() not in body
            if args.archive_out:
                args.archive_out.write_bytes(exported)
            checks.append("candidate_export_contains_exact_model_request_proposal_and_submission_provenance")
            source_pending, repair_pending = begin()
            scenario = "held"
            pending_input = request(repair_pending, config_auto, "automatic-held-001", True)
            pending = call(f"/repairs/{repair_pending['id']}/model-calls", pending_input, expected=202)
            assert entered.wait(5)
            budget = call("/model/budget")
            assert budget["usedCalls"] == 4 and budget["activeCallId"] == pending["id"]
        finally:
            stop(process)
            release.set()
        process, base = start(4, jar, True)
        try:
            csrf.update(call("/csrf"))
            restored = call(f"/model-calls/{pending['id']}")
            assert restored["status"] == "INTERRUPTED" and restored["submissionStatus"] == "SKIPPED"
            assert restored["errorCode"] == "MODEL_RESTARTED_OUTCOME_UNKNOWN"
            assert restored["candidateId"] is None and restored["proposalSha256"] is None
            assert call(f"/repairs/{repair_pending['id']}")["usedRounds"] == 0
            assert call("/model/budget")["usedCalls"] == budget["usedCalls"]
            assert call("/model/budget")["reservedInputBytes"] == budget["reservedInputBytes"]
            assert call("/model/budget")["reservedOutputTokens"] == budget["reservedOutputTokens"]
            assert call("/model/budget")["activeCallId"] is None
            assert call(f"/repairs/{repair_pending['id']}/model-calls", pending_input, expected=202) == restored
            assert len(received) == 4
            checks.append("forced_restart_skips_unknown_model_result_preserves_spend_and_never_replays_opt_in")
            assert call(f"/model-calls/{automatic['id']}") == automatic
            assert call(f"/repair-candidates/{candidate['id']}") == candidate
            retried = call(f"/model-calls/{automatic['id']}/submit",
                           dict(expectedProposalSha256=automatic["proposalSha256"]), expected=202)
            assert retried == candidate and call(f"/repairs/{repair_auto['id']}")["usedRounds"] == 1
            assert call(f"/model-calls/{legacy['id']}")["candidateId"] == bound["id"]
            assert len(received) == 4
            checks.append("submitted_provenance_survives_restart_and_repeated_submit_keeps_one_candidate_and_round")
        finally:
            stop(process)
            fake.shutdown()
            thread.join(timeout=5)
    for path, identity in identities.items():
        assert hashlib.sha256(path.read_bytes()).hexdigest() == identity
    print(json.dumps(dict(jarSha256=identities[jar], previousJarSha256=identities[previous],
                         passed=len(checks), checks=checks), indent=2))


if __name__ == "__main__":
    main()
