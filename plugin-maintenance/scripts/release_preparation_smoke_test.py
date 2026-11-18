"""Verify V10 -> V11 and persistent preparation plans with real file H2; never execute candidate code."""
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
    for name in ("java", "python", "jar", "previous-jar"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--demo-out", type=Path)
    args = parser.parse_args()
    java, python = shutil.which(args.java), shutil.which(args.python)
    if not java or not python:
        parser.error("Java and Python executables must exist")
    jar, previous = Path(args.jar).resolve(), Path(args.previous_jar).resolve()
    with zipfile.ZipFile(previous) as archive:
        assert "BOOT-INF/classes/db/migration/h2/V10__ModelSourceBudgets.sql" in archive.namelist()
        assert "BOOT-INF/classes/db/migration/h2/V11__ReleasePreparation.sql" not in archive.namelist()
    work = Path(tempfile.mkdtemp(prefix="at-release-preparation-"))
    checks = []
    password = secrets.token_urlsafe(24)
    auth = "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()
    # Do not inherit production endpoints or framework configuration from the invoking shell.
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().startswith(("MAINTENANCE_", "SPRING_"))}
    environment.update(SPRING_SECURITY_USER_NAME="admin", SPRING_SECURITY_USER_PASSWORD=password,
                       MAINTENANCE_PYTHON=str(Path(python).resolve()), MAINTENANCE_DB_PASSWORD="",
                       MAINTENANCE_AT_ORIGIN="", MAINTENANCE_AT_SESSION_TOKEN="",
                       MAINTENANCE_SCHEDULING_ENABLED="false", MAINTENANCE_CONTAINER_ENABLED="false",
                       MAINTENANCE_MODEL_ENABLED="false")
    datasource = "jdbc:h2:file:" + (work / "db").as_posix() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
    base, csrf = "", {}
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def stop(process, *, force=False):
        if process.poll() is None:
            if force:
                process.kill()
            else:
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
                    raise RuntimeError("Release preparation smoke server exited: " + str(log))
                match = re.search(r"Tomcat started on port (\d+)", log.read_text(encoding="utf-8", errors="replace"))
                if match:
                    return process, f"http://127.0.0.1:{match.group(1)}"
                time.sleep(0.2)
            raise RuntimeError("Release preparation startup timeout: " + str(log))
        except (RuntimeError, OSError):
            stop(process)
            raise

    def call(path, body=None, *, expected=200, binary=False):
        headers = {"Authorization": auth}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers.update({"Content-Type": "application/json", csrf["headerName"]: csrf["token"]})
        try:
            response = opener.open(urllib.request.Request(base + "/api/plugin-maintenance" + path,
                                                          data=data, headers=headers), timeout=20)
        except urllib.error.HTTPError as error:
            with error:
                payload = error.read()
                assert error.code == expected, (error.code, expected, payload)
                return json.loads(payload)
        with response:
            assert response.status == expected, (response.status, expected)
            payload = response.read()
            return payload if binary else json.loads(payload)

    def await_value(path):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            value = call(path)
            if value["status"] not in ("QUEUED", "RUNNING", "CANCELLING"):
                return value
            time.sleep(0.1)
        raise AssertionError("Static validation did not finish")

    def verify_export(body, plan):
        with zipfile.ZipFile(io.BytesIO(body)) as archive:
            assert len(archive.namelist()) == len(set(archive.namelist()))
            manifest = json.loads(archive.read("manifest.json"))
            assert manifest["authority"] == "preparation_only" and manifest["publishEligible"] is False
            assert set(archive.namelist()) == set(manifest["files"]) | {"manifest.json"}
            for name, record in manifest["files"].items():
                payload = archive.read(name)
                assert len(payload) == record["bytes"] and hashlib.sha256(payload).hexdigest() == record["sha256"]
                assert password.encode() not in payload and auth.encode() not in payload
            assert hashlib.sha256(archive.read("baseline.py")).hexdigest() == plan["baselineArtifactSha256"]
            assert hashlib.sha256(archive.read("candidate.py")).hexdigest() == plan["candidateSha256"]
            assert hashlib.sha256(archive.read("frozen.json")).hexdigest() == plan["frozenSha256"]
            assert b"REPAIR_SOURCE_MUST_NOT_EXECUTE" in archive.read("candidate.py")
            return json.loads(archive.read("frozen.json"))

    process, base = start(1, previous)
    try:
        csrf.update(call("/csrf"))
        fixture = call("/fixtures/repair")
        source = call("/sources", {key: fixture[key] for key in ("name", "filename", "content")}, expected=201)
        probe = call(f"/sources/{source['id']}/probe-runs", {"mode": "static"}, expected=202)
        probe = await_value(f"/probe-runs/{probe['id']}")
        repair = call(f"/sources/{source['id']}/repairs",
                      dict(baselineRunId=probe["id"], expectedArtifactSha256=source["artifactSha256"]))
        candidate = call(f"/repairs/{repair['id']}/proposals", fixture["proposal"], expected=202)
        candidate = await_value(f"/repair-candidates/{candidate['id']}")
        assert candidate["status"] == "STATIC_VALIDATED" and candidate["result"]["publishEligible"] is False
        source = call(f"/sources/{source['id']}")
        repair = call(f"/repairs/{repair['id']}")
        old_budget = call("/model/budget")
        checks.append("v10_baseline_candidate_and_budgets_saved_without_source_execution")
    finally:
        stop(process)

    process, base = start(2, jar)
    source_path = f"/sources/{source['id']}"
    try:
        csrf.update(call("/csrf"))
        assert call(source_path) == source
        assert call(f"/probe-runs/{probe['id']}") == probe
        assert call(f"/repairs/{repair['id']}") == repair
        assert call(f"/repair-candidates/{candidate['id']}") == candidate
        assert call("/model/budget") == old_budget
        assert call(source_path + "/release-plans") == []
        checks.append("v11_migration_preserves_existing_sources_reports_repairs_and_budgets")
        request = dict(candidateId=candidate["id"], expectedArtifactSha256=source["artifactSha256"],
                       expectedCandidateSha256=candidate["sha256"], requestKey="smoke-plan-001")
        plan = call(source_path + "/release-plans", request)
        assert plan["status"] == "AWAITING_RUNTIME_VALIDATION" and plan["publishEligible"] is False
        assert plan["freshness"] == "current" and len(plan["blockers"]) == 5
        assert call(source_path + "/release-plans", request) == plan
        assert call(source_path)["artifactSha256"] == source["artifactSha256"]
        assert len(call(source_path + "/artifacts")) == 1
        checks.append("preparation_is_idempotent_and_does_not_switch_effective_source")
        demo = call(f"/release-plans/{plan['id']}/export", binary=True)
        frozen = verify_export(demo, plan)
        assert frozen == call(f"/release-plans/{plan['id']}/frozen")
        if args.demo_out:
            args.demo_out.write_bytes(demo)
        checks.append("export_contains_exact_source_bytes_frozen_evidence_and_verified_manifest")
        second_request = dict(request, requestKey="smoke-plan-002")
        assert call(source_path + "/release-plans", second_request, expected=409)["code"] == "RELEASE_PLAN_ALREADY_ACTIVE"
        cancelled = call(f"/release-plans/{plan['id']}/cancel", {})
        assert cancelled == call(f"/release-plans/{plan['id']}/cancel", {})
        assert cancelled == call(source_path + "/release-plans", request)
        second = call(source_path + "/release-plans", second_request)
        second_frozen = call(f"/release-plans/{second['id']}/frozen")
        checks.append("cancellation_keeps_old_key_and_explicit_new_key_reserves_released_slot")
    finally:
        stop(process, force=True)

    # Expected execution configuration changes are visible even though the container remains disabled.
    environment["MAINTENANCE_CONTAINER_IMAGE_ID"] = "sha256:" + "1" * 64
    process, base = start(3, jar)
    try:
        csrf.update(call("/csrf"))
        records = call(source_path + "/release-plans")
        assert {item["id"] for item in records} == {plan["id"], second["id"]}
        replay = call(source_path + "/release-plans", request)
        assert replay["id"] == cancelled["id"] and replay["cancelledAt"] == cancelled["cancelledAt"]
        assert replay["status"] == "CANCELLED"
        pending = call(source_path + "/release-plans", second_request)
        assert pending["id"] == second["id"] and pending["status"] == "AWAITING_RUNTIME_VALIDATION"
        assert pending["staleInputs"] == ["execution_policy"] and pending["publishEligible"] is False
        assert call(f"/release-plans/{second['id']}/frozen") == second_frozen
        assert call("/model/budget") == old_budget
        checks.append("forced_restart_preserves_both_plan_states_keys_and_expected_configuration_drift")
        assert call(source_path + "/release-plans", dict(request, expectedCandidateSha256="0" * 64),
                    expected=409)["code"] == "RELEASE_REQUEST_KEY_CONFLICT"
        call(source_path + "/artifacts", dict(expectedSha256=source["artifactSha256"],
                                             content=fixture["content"] + "\n# drift after restart\n"))
        stale = call(f"/release-plans/{second['id']}")
        assert {"source", "execution_policy"} <= set(stale["staleInputs"])
        assert verify_export(call(f"/release-plans/{second['id']}/export", binary=True), stale) == second_frozen
        call(f"/release-plans/{second['id']}/cancel", {})
        assert call(source_path + "/release-plans", dict(request, requestKey="smoke-plan-003"),
                    expected=409)["code"] == "RELEASE_INPUTS_CHANGED"
        checks.append("source_drift_keeps_frozen_export_and_prevents_new_stale_preparation")
        assert call(source_path + "/release-plans", request)["id"] == plan["id"]
        checks.append("cancelled_stale_key_replay_cannot_reactivate_or_publish")
    finally:
        stop(process)

    result = dict(passed=len(checks), checks=checks, processes=3, logDirectory=str(work),
                  jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                  previousJarSha256=hashlib.sha256(previous.read_bytes()).hexdigest(),
                  boundary="Real file H2 upgrade and forced JVM restart; static fixture source only; "
                           "no candidate execution, real Engine, production AT, model calls, media or deployment")
    if args.json_out:
        args.json_out.write_bytes((json.dumps(result, indent=2) + "\n").encode())
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
