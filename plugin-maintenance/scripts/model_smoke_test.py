"""Verify model proposals with a local fake provider and a temporary H2 file across JAR restarts."""
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
    parser.add_argument("--previous-jar", type=Path)
    parser.add_argument("--archive-out", type=Path)
    parser.add_argument("--browser", help="Run only the packaged UI workflow using this agent-browser executable")
    parser.add_argument("--evidence-dir", type=Path, help="Required with --browser; stores non-secret UI evidence")
    parser.add_argument("--managed-home-source", type=Path, help="Run only one real managed home-repair workflow")
    parser.add_argument("--managed-home-proposal", type=Path, help="Reviewed exact edits returned by the loopback fixture")
    parser.add_argument("--managed-home-plan", type=Path, help="Existing authorized lab runner plan")
    args = parser.parse_args()
    java, python = shutil.which(args.java), shutil.which(args.python)
    if not java or not python:
        parser.error("--java and --python must name existing executables or PATH commands")
    browser = shutil.which(args.browser) if args.browser else None
    if args.browser and (not browser or not args.evidence_dir):
        parser.error("--browser must name an executable and requires --evidence-dir")
    managed_inputs = (args.managed_home_source, args.managed_home_proposal, args.managed_home_plan)
    managed_home = all(managed_inputs)
    if any(managed_inputs) and (not managed_home or browser or args.previous_jar or not args.evidence_dir):
        parser.error("managed home mode requires all three inputs and --evidence-dir, without --browser/--previous-jar")
    if managed_home:
        plan = json.loads(args.managed_home_plan.read_bytes())
        if (not re.fullmatch(r"[a-f0-9-]{36}", plan["instanceId"])
                or not re.fullmatch(r"sha256:[a-f0-9]{64}", plan["sourceImageId"])
                or not re.fullmatch(r"[a-f0-9]{64}", plan["egressPolicySha256"])):
            parser.error("managed home plan has invalid runner identity")
    jar = args.jar.resolve()
    previous = args.previous_jar.resolve() if args.previous_jar else jar
    identities = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in {jar, previous}}
    with zipfile.ZipFile(jar) as current_zip, zipfile.ZipFile(previous) as previous_zip:
        catalog_changed = current_zip.read("BOOT-INF/classes/knowledge/nodes.json") != previous_zip.read(
            "BOOT-INF/classes/knowledge/nodes.json")
    password = secrets.token_urlsafe(24)
    fake_key = secrets.token_urlsafe(24)
    auth = "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()
    received = []
    entered, release = threading.Event(), threading.Event()
    scenario = "good"
    fixture = {}
    if managed_home:
        content = args.managed_home_source.read_text(encoding="utf-8")
        proposal = json.loads(args.managed_home_proposal.read_bytes())
        if (hashlib.sha256(content.encode("utf-8")).hexdigest() != proposal["expectedBaselineSha256"]
                or proposal["validationTargets"] != ["C01", "C02"]):
            parser.error("managed home source and reviewed proposal do not match")
        fixture.update(name="managed-model-home", filename="source.py", content=content, proposal=proposal)
    checks = []

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            assert self.path == "/v1/chat/completions"
            assert self.headers.get("Authorization") == "Bearer " + fake_key
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            received.append(request)
            assert request["stream"] is False and request["store"] is False
            assert request["response_format"] == {"type": "json_object"}
            assert "tools" not in request and "max_completion_tokens" in request
            data = json.loads(request["messages"][1]["content"])
            assert data["source"] == fixture["content"]
            proposal = copy.deepcopy(fixture["proposal"])
            proposal["expectedBaselineSha256"] = data["expectedBaselineSha256"]
            if managed_home:
                assert data["repairScope"] == "managed_home_candidate"
                assert data["failureCodes"] == ["T4_HOME_EMPTY"]
                assert data["validationTargets"] == ["C01", "C02"]
            else:
                proposal["edits"] = [dict(oldText="    def homeContent(self, filter):\n        return {'class': []}\n",
                                          newText="")]
            selected = scenario
            if selected == "held":
                entered.set()
                assert release.wait(30)
            if selected == "invalid":
                proposal["deploymentAllowed"] = True
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
            prefix="at-maintenance-model-smoke-") as temporary:
        fake.daemon_threads = True
        thread = threading.Thread(target=fake.serve_forever, daemon=True)
        thread.start()
        work = Path(temporary)
        environment = dict({key: value for key, value in os.environ.items() if not key.startswith("MAINTENANCE_")},
                           SPRING_SECURITY_USER_NAME="admin", SPRING_SECURITY_USER_PASSWORD=password,
                           MAINTENANCE_PYTHON=str(Path(python).resolve()), MAINTENANCE_DB_PASSWORD="",
                           MAINTENANCE_AT_ORIGIN="", MAINTENANCE_AT_SESSION_TOKEN="",
                           MAINTENANCE_SCHEDULING_ENABLED="false", MAINTENANCE_CONTAINER_ENABLED="false",
                           MAINTENANCE_MANAGED_SOURCE_ENABLED="false", MAINTENANCE_MEDIA_ENABLED="false",
                           MAINTENANCE_MODEL_AUTO_SUBMIT_ENABLED="false",
                           MAINTENANCE_MODEL_ENABLED="false",
                           MAINTENANCE_MODEL_ENDPOINT=f"http://127.0.0.1:{fake.server_port}/v1/chat/completions",
                           MAINTENANCE_MODEL_NAME="offline-fixture", MAINTENANCE_MODEL_KEY_ENV="MAINTENANCE_FAKE_MODEL_KEY",
                           MAINTENANCE_FAKE_MODEL_KEY=fake_key,
                           MAINTENANCE_MODEL_TIMEOUT_MILLIS="15000", MAINTENANCE_MODEL_DAILY_CALL_LIMIT="8",
                           MAINTENANCE_MODEL_MAX_REQUEST_BYTES="65536", MAINTENANCE_MODEL_MAX_RESPONSE_BYTES="65536",
                           MAINTENANCE_MODEL_MAX_COMPLETION_TOKENS="2048", MAINTENANCE_MODEL_DAILY_INPUT_BYTES="262144",
                           MAINTENANCE_MODEL_DAILY_OUTPUT_TOKENS="16384")
        if managed_home:
            environment.update(MAINTENANCE_MANAGED_SOURCE_ENABLED="true",
                               MAINTENANCE_MANAGED_SOURCE_SOCKET="/run/at-maintenance/executor.sock",
                               MAINTENANCE_MANAGED_SOURCE_INSTANCE_ID=plan["instanceId"],
                               MAINTENANCE_MANAGED_SOURCE_IMAGE_ID=plan["sourceImageId"],
                               MAINTENANCE_MANAGED_SOURCE_EGRESS_POLICY_SHA256=plan["egressPolicySha256"])
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

        def start(index, artifact, enabled):
            log = work / f"server-{index}.log"
            env = dict(environment, MAINTENANCE_MODEL_ENABLED=str(enabled).lower())
            with log.open("wb") as output:
                limits = ["-Xms32m", "-Xmx192m", "-XX:ActiveProcessorCount=2"] if managed_home else []
                process = subprocess.Popen([java, *limits, "-jar", str(artifact), "--server.port=0", "--server.address=127.0.0.1",
                                            "--spring.datasource.url=" + datasource],
                                           cwd=work, env=env, stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + (150 if managed_home else 60)
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError("Model smoke server exited")
                    match = re.search(r"Tomcat started on port (\d+)", log.read_text(encoding="utf-8", errors="replace"))
                    if match:
                        return process, f"http://127.0.0.1:{match.group(1)}"
                    time.sleep(0.2)
                raise RuntimeError("Model smoke startup timeout")
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
                assert error.code == expected, (error.code, expected)
                with error:
                    return json.loads(error.read())
            with response:
                assert response.status == expected, response.status
                body = response.read()
            return body if binary else json.loads(body)

        def await_value(path):
            deadline = time.monotonic() + (110 if managed_home else 20)
            while time.monotonic() < deadline:
                value = call(path)
                if value["status"] not in ("QUEUED", "RUNNING", "RUNTIME_QUEUED", "RUNTIME_RUNNING", "CANCELLING"):
                    return value
                time.sleep(0.1)
            raise AssertionError("Model smoke work did not finish")

        def begin():
            source = call("/sources", {key: fixture[key] for key in ("name", "filename", "content")}, expected=201)
            probe = call(f"/sources/{source['id']}/probe-runs", {"mode": "static"}, expected=202)
            assert await_value(f"/probe-runs/{probe['id']}")["status"] == "COMPLETED"
            repair = call(f"/sources/{source['id']}/repairs",
                          dict(baselineRunId=probe["id"], expectedArtifactSha256=source["artifactSha256"]))
            return source, repair

        def consent(source):
            return call(f"/sources/{source['id']}/model-consent",
                        dict(expectedArtifactSha256=None, artifactSha256=source["artifactSha256"]), method="PUT")

        def request(repair, config, key):
            return dict(expectedFaultSha256=repair["faultSha256"],
                        expectedConfigurationSha256=config["configurationSha256"], requestKey=key)

        if managed_home:
            evidence_dir = args.evidence_dir.resolve()
            evidence_dir.mkdir(parents=True, exist_ok=True)
            receipt = dict(evidenceClass="packaged_java_http_loopback_fixture_real_managed_t4",
                           completed=False, jarSha256=identities[jar], realModelCalled=False, published=False,
                           businessVerdictTrusted=False, playbackVerified=False)
            process, base = start("managed-home", jar, True)
            try:
                csrf.update(call("/csrf"))
                runtime = call("/managed-source-runtime")
                assert (not runtime["blockedReason"] and runtime["sha256"] == plan["bundleSha256"]
                        and runtime["snapshot"]["instanceId"] == plan["instanceId"]
                        and runtime["snapshot"]["imageId"] == plan["sourceImageId"]
                        and runtime["snapshot"]["egressPolicySha256"] == plan["egressPolicySha256"])
                assert call("/at/connection")["configured"] is False
                config = call("/model/connection")
                assert config["enabled"] and config["autoSubmitEnabled"] is False
                source = call("/sources", {key: fixture[key] for key in ("name", "filename", "content")}, expected=201)
                run = call(f"/sources/{source['id']}/probe-runs", {"mode": "managed_source"}, expected=202)
                baseline = await_value(f"/probe-runs/{run['id']}")
                original = baseline["result"]["managedSourceEvidence"]
                receipt["baseline"] = original["untrustedRuntimeSummary"]
                assert baseline["status"] == "COMPLETED" and not original["deniedCodes"]
                assert original["cleanup"] == {"confirmed": True, "containerAbsent": True}
                assert original["untrustedRuntimeSummary"]["status"] == "home_empty"
                repair = call(f"/sources/{source['id']}/repairs",
                              dict(baselineRunId=baseline["id"], expectedArtifactSha256=source["artifactSha256"]))
                consent(source)
                model_input = request(repair, config, "managed-home-once")
                model = call(f"/repairs/{repair['id']}/model-calls", model_input, expected=202)
                model = await_value(f"/model-calls/{model['id']}")
                receipt["modelStatus"] = model["status"]
                assert model["status"] == "PROPOSED" and len(received) == 1
                submit = dict(expectedProposalSha256=model["proposalSha256"])
                candidate = call(f"/model-calls/{model['id']}/submit", submit, expected=202)
                candidate = await_value(f"/repair-candidates/{candidate['id']}")
                assert candidate["status"] == "STATIC_VALIDATED" and candidate["modelCallId"] == model["id"]
                path = f"/repair-candidates/{candidate['id']}"
                call(path + "/runtime-validation", dict(expectedCandidateSha256=candidate["sha256"]), expected=202)
                candidate = await_value(path)
                result = candidate["result"]
                observed = result["runtimeEvidence"]
                receipt.update(candidateStatus=candidate["status"], candidate=observed["untrustedRuntimeSummary"],
                               deniedCodes=observed["deniedCodes"], cleanup=observed["cleanup"])
                assert candidate["status"] == "RUNTIME_OBSERVED" and result["targetObservedRecovered"]
                assert not result["observedRegressions"]
                assert all(result[key] is False for key in ("targetRecovered", "businessVerdictTrusted", "publishEligible"))
                assert observed["jobId"] == candidate["id"] and observed["inputSourceSha256"] == candidate["sha256"]
                assert observed["bundleSha256"] == baseline["runtimeSha256"] == plan["bundleSha256"]
                assert observed["egressPolicySha256"] == plan["egressPolicySha256"]
                assert observed["imageId"] == plan["sourceImageId"]
                assert observed["cleanup"] == {"confirmed": True, "containerAbsent": True}
                assert call(f"/sources/{source['id']}")["artifactSha256"] == source["artifactSha256"]
                assert call(f"/repairs/{repair['id']}")["usedRounds"] == 1
                assert call(f"/model-calls/{model['id']}/submit", submit, expected=202)["id"] == candidate["id"]
                assert call(f"/repairs/{repair['id']}/model-calls", model_input, expected=202)["id"] == model["id"]
                assert call("/model/budget")["usedCalls"] == len(received) == 1
                archive = call(path + "/export", binary=True)
                with zipfile.ZipFile(io.BytesIO(archive)) as exported:
                    manifest = json.loads(exported.read("manifest.json"))
                    provenance = json.loads(exported.read("provenance.json"))
                    assert manifest["runtimeEvidencePresent"] and manifest["candidateStatus"] == candidate["status"]
                    assert provenance["modelCallId"] == model["id"] and provenance["submissionMethod"] == "MANUAL"
                    assert provenance["proposalSha256"] == model["proposalSha256"] == candidate["proposalSha256"]
                    assert json.loads(exported.read("candidate.json"))["result"] == result
                    for name, item in manifest["files"].items():
                        body = exported.read(name)
                        assert len(body) == item["bytes"] and hashlib.sha256(body).hexdigest() == item["sha256"]
                        assert password.encode() not in body and fake_key.encode() not in body
                if args.archive_out:
                    args.archive_out.write_bytes(archive)
                assert hashlib.sha256(jar.read_bytes()).hexdigest() == identities[jar]
                receipt.update(completed=True, sourceId=source["id"], baselineRunId=baseline["id"],
                               repairId=repair["id"], modelCallId=model["id"], candidateId=candidate["id"],
                               candidateSha256=candidate["sha256"], fakeProviderRequests=len(received),
                               repeatedRequestsReusedExistingRecords=True, sourceExecutedOnHost=False,
                               sameRunnerAndPolicy=True, effectiveSourceUnchanged=True, exportProvenanceVerified=True)
                print(json.dumps(receipt, indent=2))
            finally:
                stop(process)
                fake.shutdown()
                thread.join(timeout=5)
                (evidence_dir / "managed-home-receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
            return

        if browser:
            evidence = args.evidence_dir.resolve()
            evidence.mkdir(parents=True, exist_ok=True)
            session = "maintenance-http-" + secrets.token_hex(4)

            def browse(*command, input=None):
                # The Windows browser daemon can inherit pipes after the CLI exits.
                with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as output:
                    completed = subprocess.run(
                        [browser, "--session", session, "--allowed-domains", "127.0.0.1", "--json", *command],
                        input=input, stdout=output, stderr=output, text=True, encoding="utf-8", timeout=60)
                    output.seek(0)
                    message = output.read().replace(password, "[REDACTED]").replace(fake_key, "[REDACTED]")
                if completed.returncode:
                    raise RuntimeError(f"Browser {command[0]} failed: {message}")
                result = json.loads(message)
                assert result["success"], result
                return result.get("data", {})

            def evaluate(script):
                encoded = base64.b64encode(script.encode("utf-8")).decode("ascii")
                return browse("eval", "-b", encoded)["result"]

            process, base = start("ui", jar, True)
            try:
                csrf.update(call("/csrf"))
                fixture.update(call("/fixtures/repair"))
                assert call("/at/connection")["configured"] is False
                assert call("/model/connection")["autoSubmitEnabled"] is False
                browse("set", "viewport", "1365", "900")
                browse("open", base + "/login")
                # Temporary login material goes over stdin, never command arguments or receipts.
                login = ("document.querySelector('#username').value = 'admin';"
                         f"document.querySelector('#password').value = {json.dumps(password)};"
                         "document.querySelector('form').requestSubmit();")
                browse("eval", "--stdin", input=login)
                browse("wait", "--url", base + "/")
                script = (Path(__file__).resolve().parents[1] / "web-ui" / "tests"
                          / "browser-model-proposal-check.js").read_text(encoding="utf-8")
                ui = evaluate(script)
                assert ui["evidenceClass"] == "packaged_ui_real_java_http_fake_provider"
                model = call(f"/model-calls/{ui['modelCallId']}")
                candidate = await_value(f"/repair-candidates/{ui['candidateId']}")
                assert model["autoSubmit"] is False and model["submissionMethod"] == "MANUAL"
                assert model["candidateId"] == candidate["id"] and candidate["modelCallId"] == model["id"]
                assert model["proposalSha256"] == candidate["proposalSha256"]
                assert candidate["status"] == "STATIC_VALIDATED" and candidate["result"]["publishEligible"] is False
                assert len(received) == 1 and call("/model/budget")["usedCalls"] == 1
                assert call(f"/sources/{ui['sourceId']}")["artifactSha256"] == fixture["artifactSha256"]
                assert call(f"/sources/{ui['sourceId']}/model-consent")["artifactSha256"] is None
                assert call(f"/repairs/{ui['repairId']}")["usedRounds"] == 1
                again = call(f"/model-calls/{model['id']}/submit",
                             dict(expectedProposalSha256=model["proposalSha256"]), expected=202)
                assert again["id"] == candidate["id"] and len(received) == 1
                assert call(f"/repairs/{ui['repairId']}")["usedRounds"] == 1
                browse("screenshot", str(evidence / "desktop.png"), "--full")
                browse("set", "viewport", "390", "844")
                assert evaluate("document.documentElement.scrollWidth <= innerWidth + 1")
                browse("screenshot", str(evidence / "mobile.png"), "--full")
                exported = call(f"/repair-candidates/{candidate['id']}/export", binary=True)
                with zipfile.ZipFile(io.BytesIO(exported)) as archive:
                    provenance = json.loads(archive.read("provenance.json"))
                    assert provenance["modelCallId"] == model["id"]
                    assert provenance["proposalSha256"] == model["proposalSha256"]
                    assert provenance["submissionMethod"] == "MANUAL"
                    for name in archive.namelist():
                        assert fake_key.encode() not in archive.read(name) and password.encode() not in archive.read(name)
                if args.archive_out:
                    args.archive_out.write_bytes(exported)
                receipt = dict(ui, jarSha256=identities[jar], fakeProviderRequests=len(received),
                               candidateStatus=candidate["status"], repeatedSubmitReusedCandidate=True,
                               exportProvenanceVerified=True, mobileFits=True, realModelCalled=False,
                               sourceExecuted=False, nasAccessed=False, published=False)
                (evidence / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
                assert hashlib.sha256(jar.read_bytes()).hexdigest() == identities[jar]
                print(json.dumps(receipt, indent=2))
            finally:
                try:
                    browse("close")
                finally:
                    stop(process)
                    fake.shutdown()
                    thread.join(timeout=5)
            return

        process, base = start(1, previous, False)
        try:
            csrf.update(call("/csrf"))
            fixture.update(call("/fixtures/repair"))
            source, repair = begin()
            repair = call(f"/repairs/{repair['id']}")
        finally:
            stop(process)
        process, base = start(2, jar, False)
        try:
            csrf.update(call("/csrf"))
            legacy = call(f"/repairs/{repair['id']}")
            differences = [key for key in legacy if key not in ("freshness", "staleInputs") and legacy[key] != repair[key]]
            assert differences == [], differences
            assert legacy["freshness"] == ("repair_tools_changed" if catalog_changed else "current")
            assert legacy["staleInputs"] == (["node_catalog"] if catalog_changed else [])
            source, repair = begin()
            config = call("/model/connection")
            assert config["enabled"] is False
            assert call(f"/sources/{source['id']}/model-consent")["artifactSha256"] is None
            assert call("/model/budget")["usedCalls"] == 0
            assert call(f"/repairs/{repair['id']}/model-calls", request(repair, config, "disabled-001"),
                        expected=409)["code"] == "MODEL_DISABLED"
            assert received == []
            consent(source)
            checks.append("v8_upgrade_preserves_old_repair_and_starts_without_consent_or_model_calls")
            checks.append("catalog_provenance_correction_preserves_frozen_legacy_identity_and_marks_staleness")
            checks.append("disabled_provider_cannot_send_or_spend_even_after_explicit_source_consent")
        finally:
            stop(process)
        process, base = start(3, jar, True)
        try:
            csrf.update(call("/csrf"))
            config = call("/model/connection")
            good = request(repair, config, "model-good-001")
            result = call(f"/repairs/{repair['id']}/model-calls", good, expected=202)
            result = await_value(f"/model-calls/{result['id']}")
            assert result["status"] == "PROPOSED" and len(received) == 1
            assert call(f"/repairs/{repair['id']}")["usedRounds"] == 0
            assert call(f"/repairs/{repair['id']}/model-calls", good, expected=202) == result
            assert len(received) == 1
            checks.append("local_http_proposal_is_persisted_without_execution_or_duplicate_send")
            candidate = call(f"/repairs/{repair['id']}/proposals", result["proposal"], expected=202)
            candidate = await_value(f"/repair-candidates/{candidate['id']}")
            assert candidate["status"] == "STATIC_VALIDATED" and candidate["result"]["publishEligible"] is False
            assert call(f"/sources/{source['id']}")["artifactSha256"] == source["artifactSha256"]
            archive = call(f"/repair-candidates/{candidate['id']}/export", binary=True)
            with zipfile.ZipFile(io.BytesIO(archive)) as zf:
                assert json.loads(zf.read("model-calls.json")) == [result]
                manifest = json.loads(zf.read("manifest.json"))
                for name, item in manifest["files"].items():
                    assert len(zf.read(name)) == item["bytes"]
                    assert hashlib.sha256(zf.read(name)).hexdigest() == item["sha256"]
                    assert fake_key.encode() not in zf.read(name)
            if args.archive_out:
                args.archive_out.write_bytes(archive)
            checks.append("reviewed_proposal_uses_existing_static_gate_and_export_binds_model_evidence")
            source2, repair2 = begin()
            consent(source2)
            scenario = "held"
            pending_input = request(repair2, config, "model-held-001")
            pending = call(f"/repairs/{repair2['id']}/model-calls", pending_input, expected=202)
            assert entered.wait(5)
            budget = call("/model/budget")
            assert budget["usedCalls"] == 2 and budget["activeCallId"] == pending["id"]
        finally:
            stop(process)
            release.set()
        process, base = start(4, jar, True)
        try:
            csrf.update(call("/csrf"))
            restored = call(f"/model-calls/{pending['id']}")
            assert restored["status"] == "INTERRUPTED" and restored["errorCode"] == "MODEL_RESTARTED_OUTCOME_UNKNOWN"
            assert restored["proposal"] is None
            assert call("/model/budget")["usedCalls"] == budget["usedCalls"]
            assert call("/model/budget")["reservedOutputTokens"] == budget["reservedOutputTokens"]
            assert call("/model/budget")["activeCallId"] is None
            assert call(f"/repairs/{repair2['id']}/model-calls", pending_input, expected=202) == restored
            assert len(received) == 2
            checks.append("forced_restart_preserves_spend_marks_unknown_outcome_and_never_replays_old_key")
            assert call(f"/model-calls/{result['id']}") == result
            assert call(f"/repair-candidates/{candidate['id']}") == candidate
            scenario = "invalid"
            invalid = call(f"/repairs/{repair2['id']}/model-calls",
                           request(repair2, config, "model-invalid-002"), expected=202)
            invalid = await_value(f"/model-calls/{invalid['id']}")
            assert invalid["status"] == "FAILED" and invalid["errorCode"] == "MODEL_RESPONSE_INVALID"
            assert call("/model/budget")["usedCalls"] == 3
            assert call(f"/repairs/{repair2['id']}")["usedRounds"] == 0
            assert len(received) == 3
            checks.append("new_key_is_explicit_new_spend_invalid_authority_output_never_becomes_a_candidate")
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
