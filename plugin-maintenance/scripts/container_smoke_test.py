"""Packaged H2/configuration/restart checks. No Docker, image or Linux isolation is exercised."""
from __future__ import annotations

import argparse
import base64
import hashlib
import http.cookiejar
import io
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--python", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--previous-jar", type=Path, help="Upgrade fixture requires the 0.7.0 repair API")
    parser.add_argument("--archive-out", type=Path)
    args = parser.parse_args()
    java, python = shutil.which(args.java), shutil.which(args.python)
    if not java or not python:
        parser.error("--java and --python must name existing executables or PATH commands")
    jar = args.jar.resolve()
    jar_sha = hashlib.sha256(jar.read_bytes()).hexdigest()
    password = secrets.token_urlsafe(24)
    auth = "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()
    checks = []
    with tempfile.TemporaryDirectory(prefix="at-maintenance-container-smoke-") as temp:
        work = Path(temp)
        environment = dict(os.environ, SPRING_SECURITY_USER_NAME="admin", SPRING_SECURITY_USER_PASSWORD=password,
                           MAINTENANCE_PYTHON=str(Path(python).resolve()), MAINTENANCE_DB_PASSWORD="",
                           MAINTENANCE_AT_ORIGIN="", MAINTENANCE_AT_INSTANCE_ID="synthetic-container",
                           MAINTENANCE_AT_SESSION_TOKEN="", MAINTENANCE_SCHEDULING_ENABLED="false",
                           MAINTENANCE_CONTAINER_ENABLED="false", MAINTENANCE_CONTAINER_EXECUTABLE="",
                           MAINTENANCE_CONTAINER_ENDPOINT="", MAINTENANCE_CONTAINER_IMAGE_ID="")
        datasource = "jdbc:h2:file:" + (work / "db").as_posix() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        csrf = {}
        base = ""

        def stop(process):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)

        def start(index, previous=False, drift=False):
            log = work / f"server-{index}.log"
            env = dict(environment, MAINTENANCE_CONTAINER_IMAGE_ID="sha256:" + "a" * 64 if drift else "")
            with log.open("wb") as output:
                process = subprocess.Popen([java, "-jar", str(args.previous_jar.resolve() if previous else jar),
                                            "--server.port=0", "--server.address=127.0.0.1",
                                            "--spring.datasource.url=" + datasource],
                                           cwd=work, env=env, stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 60
            try:
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError("Container smoke server exited")
                    match = re.search(r"Tomcat started on port (\d+)", log.read_text(encoding="utf-8", errors="replace"))
                    if match:
                        return process, f"http://127.0.0.1:{match.group(1)}"
                    time.sleep(0.2)
                raise RuntimeError("Container smoke startup timeout")
            except (RuntimeError, OSError):
                stop(process)
                print(log.read_text(encoding="utf-8", errors="replace")[-4000:].replace(password, "[REDACTED]"))
                raise

        def call(path, body=None, binary=False, expected=200):
            headers = {"Authorization": auth}
            data = None
            if body is not None:
                data = json.dumps(body, ensure_ascii=False).encode("utf-8")
                headers.update({"Content-Type": "application/json", csrf["headerName"]: csrf["token"]})
            request = urllib.request.Request(base + "/api/plugin-maintenance" + path, data=data, headers=headers)
            try:
                response = opener.open(request, timeout=15)
            except urllib.error.HTTPError as error:
                assert error.code == expected, error.code
                with error:
                    return json.loads(error.read())
            with response:
                assert response.status == expected
                value = response.read()
            return value if binary else json.loads(value)

        def probe(source_id, mode):
            submitted = call(f"/sources/{source_id}/probe-runs", dict(mode=mode), expected=202)
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                run = call(f"/probe-runs/{submitted['id']}")
                if run["status"] not in ("QUEUED", "RUNNING", "CANCELLING"):
                    return run
                time.sleep(0.1)
            raise AssertionError("Packaged container task did not finish")

        def create(content, name="container fixture"):
            return call("/sources", dict(name=name, filename="demo.py", content=content), expected=201)

        legacy = None
        if args.previous_jar:
            process, base = start(1, previous=True)
            try:
                csrf.update(call("/csrf"))
                fixture = call("/fixtures/demo")
                source = create(fixture["content"])
                legacy = probe(source["id"], "static")
                assert legacy["status"] == "COMPLETED"
                repair_fixture = call("/fixtures/repair")
                repair_source = create(repair_fixture["content"], "legacy repair data")
                repair_probe = probe(repair_source["id"], "static")
                repair_input = dict(baselineRunId=repair_probe["id"],
                                    expectedArtifactSha256=repair_source["artifactSha256"])
                old_repair = call(f"/sources/{repair_source['id']}/repairs", repair_input)
                old_candidate = call(f"/repairs/{old_repair['id']}/proposals",
                                     repair_fixture["proposal"], expected=202)
                deadline = time.monotonic() + 20
                while time.monotonic() < deadline:
                    old_candidate = call(f"/repair-candidates/{old_candidate['id']}")
                    if old_candidate["status"] not in ("QUEUED", "RUNNING", "CANCELLING"):
                        break
                    time.sleep(0.1)
                assert old_candidate["status"] == "STATIC_VALIDATED"
                old_repair = call(f"/repairs/{old_repair['id']}")
                assert old_repair["usedRounds"] == 1
            finally:
                stop(process)

        process, base = start(2)
        try:
            csrf.update(call("/csrf"))
            config = call("/container-runtime")
            assert config["blockedReason"] == "CONTAINER_EXECUTION_DISABLED"
            assert config["snapshot"]["authority"] == "expected_configuration"
            assert config["snapshot"]["engineExecutableSha256"] is None
            assert config["snapshot"]["bundle"]["policy"]["unknownSourceAllowed"] is False
            checks.append("configuration_endpoint_is_expected_only_and_default_execution_is_disabled")
            if legacy:
                restored = call(f"/probe-runs/{legacy['id']}")
                for key, value in legacy.items():
                    assert restored[key] == value, key
                assert restored["executionPolicy"] is restored["executionPolicySha256"] is None
                checks.append("v6_to_v7_preserves_legacy_report_without_fabricating_execution_policy")
                upgraded_repair = call(f"/repairs/{old_repair['id']}")
                assert upgraded_repair == old_repair
                assert call(f"/sources/{repair_source['id']}/repairs", repair_input) == old_repair
                assert call(f"/repair-candidates/{old_candidate['id']}") == old_candidate
                checks.append("v7_nullable_execution_policy_preserves_prior_fault_identity_frozen_evidence_and_round_budget")
            else:
                fixture = call("/fixtures/demo")
                source = create(fixture["content"])
            blocked = probe(source["id"], "container_fixture")
            assert blocked["status"] == "BLOCKED" and blocked["errorCode"] == "CONTAINER_EXECUTION_DISABLED"
            assert blocked["executionPolicy"] == config["snapshot"]
            assert blocked["executionPolicySha256"] == config["sha256"]
            assert blocked["result"]["verdict"] == "container_fixture_blocked"
            assert "runtimeEvidence" not in blocked["result"]
            assert sum(node["status"] == "pass" for node in blocked["result"]["nodes"]) == 3
            checks.append("disabled_container_task_persists_frozen_policy_without_host_fallback_or_extra_passes")
            unknown = create("raise RuntimeError('never execute')\nclass Spider: pass\n", "unknown static data")
            assert probe(unknown["id"], "container_fixture")["errorCode"] == "TRUSTED_FIXTURE_REQUIRED"
            checks.append("unknown_source_rejected_before_any_container_or_host_execution")
        finally:
            stop(process)

        process, base = start(3, drift=True)
        try:
            csrf.update(call("/csrf"))
            restored = call(f"/probe-runs/{blocked['id']}")
            assert restored["executionPolicy"] == blocked["executionPolicy"]
            assert restored["executionPolicySha256"] == blocked["executionPolicySha256"]
            assert restored["freshness"] == "execution_policy_changed"
            assert restored["staleInputs"] == ["execution_policy"]
            assert call("/container-runtime")["sha256"] != blocked["executionPolicySha256"]
            checks.append("h2_restart_preserves_frozen_policy_and_exposes_configuration_drift")
            archive = call(f"/sources/{source['id']}/export", binary=True)
            with zipfile.ZipFile(io.BytesIO(archive)) as zf:
                manifest = json.loads(zf.read("manifest.json"))
                for name, record in manifest["files"].items():
                    body = zf.read(name)
                    assert len(body) == record["bytes"]
                    assert hashlib.sha256(body).hexdigest() == record["sha256"]
                assert hashlib.sha256(zf.read("probe-execution-policy.json")).hexdigest() == blocked["executionPolicySha256"]
                assert manifest["probeExecutionPolicySha256"] == blocked["executionPolicySha256"]
                assert json.loads(zf.read("probe.json"))["executionPolicy"] == blocked["executionPolicy"]
            if args.archive_out:
                with args.archive_out.open("xb") as output:
                    output.write(archive)
            checks.append("source_export_binds_frozen_execution_policy_and_exact_file_hashes")
            pending = probe(source["id"], "container_fixture")
            assert pending["status"] == "BLOCKED" and pending["freshness"] == "current"
        finally:
            stop(process)

        # Only this script's stopped temporary database: model a crash during cancellation.
        h2 = work / "h2-smoke-tool.jar"
        with zipfile.ZipFile(jar) as zf:
            h2.write_bytes(zf.read(next(name for name in zf.namelist()
                                      if name.startswith("BOOT-INF/lib/h2-") and name.endswith(".jar"))))
        assert re.fullmatch(r"[a-f0-9-]{36}", pending["id"])
        sql = ("UPDATE maintenance_probe_run SET status='CANCELLING',finished_at=NULL,error_code=NULL,"
               "result_json=NULL,context_json=NULL WHERE id='" + pending["id"] + "'")
        changed = subprocess.run([java, "-cp", str(h2), "org.h2.tools.Shell", "-url", datasource,
                                  "-user", "sa", "-password", "", "-sql", sql],
                                 cwd=work, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)
        assert changed.returncode == 0 and b"Update count: 1" in changed.stdout
        process, base = start(4, drift=True)
        try:
            recovered = call(f"/probe-runs/{pending['id']}")
            assert recovered["status"] == "FAILED" and recovered["errorCode"] == "CONTAINER_CLEANUP_UNCONFIRMED"
            assert recovered["result"] is None and recovered["finishedAt"]
            assert recovered["executionPolicy"] == pending["executionPolicy"]
            checks.append("restart_without_engine_cannot_claim_successful_cleanup_or_cancellation")
        finally:
            stop(process)
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == jar_sha
    print(json.dumps(dict(passed=len(checks), checks=checks, jarSha256=jar_sha,
                          boundary="Packaged persistence/protocol checks only; no Docker or Linux isolation"),
                     indent=2))


if __name__ == "__main__":
    main()
