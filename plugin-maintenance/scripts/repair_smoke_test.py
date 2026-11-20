"""Exercise manual/fixture proposals, immutable candidates and H2 restart via the final JAR."""
from __future__ import annotations

import argparse
import base64
import copy
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
    with tempfile.TemporaryDirectory(prefix="at-maintenance-repair-smoke-") as temp:
        work = Path(temp)
        environment = dict(os.environ, SPRING_SECURITY_USER_NAME="admin", SPRING_SECURITY_USER_PASSWORD=password,
                           MAINTENANCE_PYTHON=str(Path(python).resolve()), MAINTENANCE_DB_PASSWORD="",
                           MAINTENANCE_AT_ORIGIN="", MAINTENANCE_AT_INSTANCE_ID="synthetic-repair",
                           MAINTENANCE_AT_REVISION="synthetic", MAINTENANCE_AT_SESSION_TOKEN="",
                           MAINTENANCE_AT_ALLOW_HTTP="false", MAINTENANCE_SCHEDULING_ENABLED="false",
                           MAINTENANCE_SCHEDULING_DAILY_LIMIT="1")
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

        def start(index):
            log = work / f"server-{index}.log"
            with log.open("wb") as output:
                process = subprocess.Popen([java, "-jar", str(jar), "--server.port=0", "--server.address=127.0.0.1",
                                            "--spring.datasource.url=" + datasource],
                                           cwd=work, env=environment, stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 60
            try:
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError("Repair smoke server exited")
                    match = re.search(r"Tomcat started on port (\d+)", log.read_text(encoding="utf-8", errors="replace"))
                    if match:
                        return process, f"http://127.0.0.1:{match.group(1)}"
                    time.sleep(0.2)
                raise RuntimeError("Repair smoke server startup timeout")
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
                assert response.status == expected, response.status
                value = response.read()
            return value if binary else json.loads(value)

        def await_value(path):
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                value = call(path)
                if value["status"] not in ("QUEUED", "RUNNING", "CANCELLING"):
                    return value
                time.sleep(0.1)
            raise AssertionError("Packaged repair work did not finish")

        def create_source(fixture):
            return call("/sources", {key: fixture[key] for key in ("name", "filename", "content")}, expected=201)

        def begin(source):
            submitted = call(f"/sources/{source['id']}/probe-runs", {"mode": "static"}, expected=202)
            probe = await_value(f"/probe-runs/{submitted['id']}")
            assert probe["status"] == "COMPLETED"
            return call(f"/sources/{source['id']}/repairs",
                        dict(baselineRunId=probe["id"], expectedArtifactSha256=source["artifactSha256"]))

        process, base = start(1)
        try:
            csrf.update(call("/csrf"))
            fixture = call("/fixtures/repair")
            source = create_source(fixture)
            profile = call(f"/sources/{source['id']}/profile")
            profile = call(f"/sources/{source['id']}/profiles", dict(
                expectedSha256=profile["sha256"], runtime="t4", runtimeRevision="unverified",
                requiredNodes=["S01", "S02", "S03", "C01"], optionalNodes=[]))
            repair = begin(source)
            assert repair["frozen"]["baseline"]["context"]["rules"][0]["id"] == "duplicate-methods"
            assert repair["frozen"]["policy"]["externalModelCalls"] is False
            checks.append("baseline_failure_freezes_profile_skill_context_and_non_model_policy")
            wrong = copy.deepcopy(fixture["proposal"])
            wrong["edits"].append(dict(oldText="def init(self, extend=''):", newText="def init(self):"))
            rejected = call(f"/repairs/{repair['id']}/proposals", wrong, expected=202)
            rejected = await_value(f"/repair-candidates/{rejected['id']}")
            assert rejected["status"] == "REJECTED" and rejected["errorCode"] == "CONTRACT_REGRESSION"
            checks.append("fixture_proposal_that_removes_input_parameter_is_rejected")
            accepted = call(f"/repairs/{repair['id']}/proposals", fixture["proposal"], expected=202)
            candidate = await_value(f"/repair-candidates/{accepted['id']}")
            assert candidate["status"] == "STATIC_VALIDATED" and candidate["roundNumber"] == 2
            assert candidate["result"]["requiredCoverage"] == dict(
                total=4, **{"pass": 3, "fail": 0, "notTested": 1, "conclusion": "required_incomplete"})
            assert candidate["result"]["publishEligible"] is False
            assert call(f"/repairs/{repair['id']}")["status"] == "AWAITING_RUNTIME_VALIDATION"
            checks.append("second_round_restores_static_target_without_certifying_runtime_or_publication")
            assert call(f"/sources/{source['id']}")["artifactSha256"] == source["artifactSha256"]
            assert len(call(f"/sources/{source['id']}/artifacts")) == 1
            checks.append("candidate_storage_preserves_effective_source_and_version_history")
            archive = call(f"/repair-candidates/{candidate['id']}/export", binary=True)
            with zipfile.ZipFile(io.BytesIO(archive)) as zf:
                manifest = json.loads(zf.read("manifest.json"))
                assert zf.read("baseline.py") == fixture["content"].encode("utf-8")
                override = fixture["proposal"]["edits"][0]["oldText"]
                assert zf.read("candidate.py") == fixture["content"].replace(override, "").encode("utf-8")
                for name, record in manifest["files"].items():
                    assert len(zf.read(name)) == record["bytes"]
                    assert hashlib.sha256(zf.read(name)).hexdigest() == record["sha256"]
                assert hashlib.sha256(zf.read("proposal.json")).hexdigest() == candidate["proposalSha256"]
                assert manifest["publishEligible"] is False
                assert [item["status"] for item in json.loads(zf.read("rounds.json"))] == ["REJECTED", "STATIC_VALIDATED"]
            if args.archive_out:
                args.archive_out.write_bytes(archive)
            checks.append("repair_archive_binds_exact_baseline_candidate_proposal_and_all_file_hashes")
        finally:
            stop(process)

        process, base = start(2)
        try:
            csrf.update(call("/csrf"))
            restored = call(f"/repairs/{repair['id']}")
            assert restored["usedRounds"] == 2 and restored["frozen"] == repair["frozen"]
            assert restored["freshness"] == "current"
            assert call(f"/repair-candidates/{candidate['id']}") == candidate
            assert [item["status"] for item in call(f"/repairs/{repair['id']}/candidates")] == ["REJECTED", "STATIC_VALIDATED"]
            assert begin(source)["id"] == repair["id"]
            checks.append("h2_restart_preserves_candidates_evidence_and_deduplicated_fault_budget")
            assert call(f"/repairs/{repair['id']}/cancel", {})["status"] == "CANCELLED"
            resumed = call(f"/repairs/{repair['id']}/resume", dict(expectedFaultSha256=repair["faultSha256"]))
            assert resumed["status"] == "AWAITING_RUNTIME_VALIDATION" and resumed["usedRounds"] == 2
            assert call(f"/repairs/{repair['id']}/proposals", fixture["proposal"], expected=409)["code"] == "REPAIR_NOT_ACCEPTING_PROPOSALS"
            checks.append("resume_preserves_runtime_gate_and_cannot_reopen_successful_static_patch_loop")
            second_source = create_source(fixture)
            second = begin(second_source)
            broken = copy.deepcopy(fixture["proposal"])
            broken["edits"][0]["newText"] = "    def broken(\n"
            first = call(f"/repairs/{second['id']}/proposals", broken, expected=202)
            assert await_value(f"/repair-candidates/{first['id']}")["status"] == "REJECTED"
            repeat = call(f"/repairs/{second['id']}/proposals", broken, expected=202)
            assert repeat["errorCode"] == "REPEATED_PATCH"
            assert call(f"/repairs/{second['id']}")["usedRounds"] == 2
            assert begin(second_source)["id"] == second["id"]
            checks.append("repeated_patch_stops_and_identical_probe_cannot_reset_rounds")
            call(f"/sources/{source['id']}/profiles", dict(
                expectedSha256=profile["sha256"], runtime="fongmi", runtimeRevision="changed",
                requiredNodes=["S01", "S02", "S03"], optionalNodes=[]))
            assert call(f"/repairs/{repair['id']}")["freshness"] == "profile_changed"
            stale_archive = call(f"/repair-candidates/{candidate['id']}/export", binary=True)
            with zipfile.ZipFile(io.BytesIO(stale_archive)) as zf:
                assert json.loads(zf.read("profile.json")) == profile["snapshot"]
                assert json.loads(zf.read("repair.json"))["freshness"] == "profile_changed"
            checks.append("later_profile_edits_invalidate_session_without_rewriting_frozen_export")
        finally:
            stop(process)

        process, base = start(3)
        try:
            assert call(f"/repairs/{repair['id']}")["freshness"] == "profile_changed"
            stopped = call(f"/repairs/{second['id']}")
            assert stopped["status"] == "STOPPED" and stopped["usedRounds"] == 2 and stopped["stopCode"] == "REPEATED_PATCH"
            assert call(f"/sources/{source['id']}")["artifactSha256"] == source["artifactSha256"]
            checks.append("second_file_restart_keeps_stop_reason_drift_and_effective_source")
        finally:
            stop(process)
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == jar_sha
    print(json.dumps(dict(jarSha256=jar_sha, passed=len(checks), checks=checks), indent=2))


if __name__ == "__main__":
    main()
