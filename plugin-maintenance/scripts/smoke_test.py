"""Exercise the packaged app and restart it against an actual H2 file."""
from __future__ import annotations

import argparse
import base64
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
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
import threading
import time
import urllib.error
import urllib.request
import zipfile


@contextmanager
def synthetic_at():
    calls = []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            calls.append((self.command, self.path, self.headers.get("Authorization")))
            if self.path != "/api/plugins" or self.headers.get("Authorization") != "SYNTHETIC_SESSION_SECRET":
                self.send_error(403)
                return
            body = json.dumps([
                dict(id=i, name="Synthetic " + str(i), externalId=None, enabled=True, t4Visible=True,
                     version=1, url=f"https://fixture.invalid/{i}.py?token=LOCATOR_SECRET",
                     extend="EXT_SECRET", lastError="ERROR_SECRET", content="SOURCE_SECRET")
                for i in (7, 8)
            ]).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", calls
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--python", required=True)
    parser.add_argument("--archive-out", type=Path)
    parser.add_argument("--previous-jar", type=Path, help="Create a database with the previous release, then upgrade")
    parser.add_argument("--database-backup-roundtrip", action="store_true",
                        help="Also verify cold whole-database backup, restore and interrupted work recovery")
    args = parser.parse_args()
    java = shutil.which(args.java)
    python = shutil.which(args.python)
    if java is None or python is None:
        parser.error("--java and --python must name existing executable files or PATH commands")
    password = secrets.token_urlsafe(24)
    auth = "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()
    checks = []
    with synthetic_at() as (at_origin, at_calls), tempfile.TemporaryDirectory(prefix="at-maintenance-smoke-") as temp:
        work = Path(temp)
        environment = dict(os.environ, SPRING_SECURITY_USER_PASSWORD=password,
                           MAINTENANCE_PYTHON=str(Path(python).resolve()),
                           MAINTENANCE_AT_ORIGIN=at_origin, MAINTENANCE_AT_INSTANCE_ID="synthetic-at",
                           MAINTENANCE_AT_REVISION="synthetic-declared",
                           MAINTENANCE_AT_SESSION_TOKEN="SYNTHETIC_SESSION_SECRET", MAINTENANCE_AT_ALLOW_HTTP="true",
                           MAINTENANCE_DB_PASSWORD="", MAINTENANCE_SCHEDULING_ENABLED="false",
                           MAINTENANCE_SCHEDULING_DAILY_LIMIT="1")
        datasource = "jdbc:h2:file:" + (work / "maintenance").as_posix() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"

        def start(index, jar=None, disconnected=False, scheduling=False, database_url=None, local_only=False):
            log_path = work / f"server-{index}.log"
            output = log_path.open("wb")
            instance_environment = dict(environment, MAINTENANCE_SCHEDULING_ENABLED=str(scheduling).lower())
            if disconnected:
                instance_environment["MAINTENANCE_AT_ORIGIN"] = ""
            command = [str(Path(java).resolve()), "-jar", str((jar or args.jar).resolve()), "--server.port=0",
                       "--server.address=127.0.0.1", "--spring.datasource.url=" + (database_url or datasource)]
            if local_only:
                command += ["--maintenance.at.origin=", "--maintenance.at.runtime-identity-path=",
                            "--maintenance.at.session-token=", "--maintenance.model.enabled=false",
                            "--maintenance.model.auto-submit-enabled=false", "--maintenance.container.enabled=false",
                            "--maintenance.container.executable=", "--maintenance.container.endpoint=",
                            "--maintenance.container.image-id=", "--maintenance.scheduling.enabled=false",
                            "--maintenance.media.enabled=false"]
            process = subprocess.Popen(
                command,
                cwd=work, env=instance_environment,
                stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 60
            try:
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError("Server exited during startup")
                    match = re.search(r"Tomcat started on port (\d+)", log_path.read_text(encoding="utf-8", errors="replace"))
                    if match:
                        return process, f"http://127.0.0.1:{match.group(1)}"
                    time.sleep(0.2)
                raise RuntimeError("Server startup timeout")
            except (RuntimeError, OSError):
                stop(process)
                tail = log_path.read_text(encoding="utf-8", errors="replace")[-4000:]
                # Only this synthetic smoke instance's log is shown; redact its password.
                print(tail.replace(password, "[REDACTED]"))
                raise
            finally:
                output.close()

        def stop(process):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)

        def data_tool(operation, *options, expected=None):
            result = subprocess.run(
                [java, "-Dloader.main=cn.har01d.atmaintenance.tools.DatabaseArchiveTool",
                 "-cp", str(args.jar.resolve()), "org.springframework.boot.loader.launch.PropertiesLauncher",
                 operation, *map(str, options)],
                cwd=work, env=dict(environment, MAINTENANCE_DB_PASSWORD=""),
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
            payload = json.loads((result.stderr if result.returncode else result.stdout).decode("utf-8"))
            if expected:
                assert result.returncode != 0 and payload["code"] == expected, payload
            else:
                assert result.returncode == 0 and payload["ok"], payload
            return payload

        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        csrf = {}

        def call(base, path, body=None, binary=False):
            headers = {"Authorization": auth}
            data = None
            if body is not None:
                headers.update({"Content-Type": "application/json", csrf["headerName"]: csrf["token"]})
                data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            request = urllib.request.Request(base + "/api/plugin-maintenance" + path, data=data, headers=headers)
            with opener.open(request, timeout=15) as response:
                payload = response.read()
            return payload if binary else json.loads(payload) if payload else None

        def await_run(base, run):
            deadline = time.monotonic() + 15
            while run["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
                time.sleep(0.1)
                run = call(base, f"/probe-runs/{run['id']}")
            assert run["status"] == "COMPLETED", {
                key: run[key] for key in ("id", "status", "errorCode")
            }
            return run

        def profile_update(profile, ext):
            return dict(expectedSha256=profile["sha256"], runtime="fongmi",
                        runtimeRevision="offline-fixture-1",
                        requiredNodes=["S01", "S02", "S03", "C01"], optionalNodes=[], ext=ext)

        def make_schedule_due(source_id):
            # Only this script's stopped temporary H2 database. Product intervals remain >= 30 minutes.
            assert re.fullmatch(r"[a-f0-9-]{36}", source_id)
            h2 = work / "h2-smoke-tool.jar"
            if not h2.exists():
                with zipfile.ZipFile(args.jar) as zf:
                    entry = next(name for name in zf.namelist()
                                 if name.startswith("BOOT-INF/lib/h2-") and name.endswith(".jar"))
                    h2.write_bytes(zf.read(entry))
            result = subprocess.run([java, "-cp", str(h2), "org.h2.tools.Shell", "-url", datasource,
                                     "-user", "sa", "-password", "", "-sql",
                                     "UPDATE maintenance_probe_schedule SET next_due_at="
                                     "TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00Z' WHERE source_id='" + source_id + "'"],
                                    cwd=work, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)
            assert result.returncode == 0 and b"Update count: 1" in result.stdout, result.stdout.decode(errors="replace")

        process, base = start(1, args.previous_jar)
        try:
            try:
                urllib.request.urlopen(base + "/api/plugin-maintenance/sources", timeout=10)
                raise AssertionError("Unauthenticated API unexpectedly succeeded")
            except urllib.error.HTTPError as error:
                assert error.code == 401
            checks.append("unauthenticated_api_401")
            csrf.update(call(base, "/csrf"))
            body = "# \u4e2d\u6587\u5b57\u8282\r\nraise RuntimeError('must not execute')\r\nclass Spider: pass\r\n"
            source = call(base, "/sources", {"name": "\u79bb\u7ebf\u6d4b\u8bd5", "filename": "fixture.py", "content": body})
            expected_sha = hashlib.sha256(body.encode("utf-8")).hexdigest()
            assert source["artifactSha256"] == expected_sha
            checks.append("exact_utf8_crlf_source_identity")
            run = await_run(base, call(base, f"/sources/{source['id']}/probe-runs", {}))
            assert run["result"]["verdict"] == "static_checked"
            statuses = [node["status"] for node in run["result"]["nodes"]]
            assert statuses.count("pass") == 3 and statuses.count("not_tested") == 41
            checks.append("async_static_probe_3_pass_41_not_tested")
            archive = call(base, f"/sources/{source['id']}/export", binary=True)
            with zipfile.ZipFile(io.BytesIO(archive)) as zf:
                manifest = json.loads(zf.read("manifest.json"))
                assert zf.read("source.py") == body.encode("utf-8")
                for name, record in manifest["files"].items():
                    assert hashlib.sha256(zf.read(name)).hexdigest() == record["sha256"]
                assert hashlib.sha256(zf.read("knowledge/catalog.json")).hexdigest() == run["knowledgeSha256"]
            checks.append("offline_zip_all_file_hashes")
            if args.archive_out:
                args.archive_out.parent.mkdir(parents=True, exist_ok=True)
                args.archive_out.write_bytes(archive)
            homepage = urllib.request.Request(base + "/", headers={"Authorization": auth})
            with opener.open(homepage, timeout=10) as response:
                assert b'<div id="app">' in response.read()
            checks.append("packaged_vue_homepage")
            profile_path = f"/sources/{source['id']}/profile"
            profiles_path = f"/sources/{source['id']}/profiles"
            secret_ext = {"token": "FIXTURE_SECRET_NOT_FOR_PERSISTENCE", "options": {"b": 2, "a": 1}}
            had_profile = run.get("profileSha256") is not None
            if had_profile:
                baseline = call(base, profile_path)
                assert baseline["sha256"] == run["profileSha256"]
                updated = call(base, profiles_path, profile_update(baseline, secret_ext))
                assert "FIXTURE_SECRET_NOT_FOR_PERSISTENCE" not in json.dumps(updated)
                assert call(base, f"/probe-runs/{run['id']}")["freshness"] == "profile_changed"
                checks.append("profile_update_redacts_ext_and_invalidates_old_run")
        finally:
            stop(process)
        process, base = start(2)
        try:
            restored = call(base, f"/sources/{source['id']}")
            restored_run = call(base, f"/probe-runs/{run['id']}")
            assert restored["artifactSha256"] == expected_sha
            assert restored_run["status"] == "COMPLETED"
            assert restored_run["context"] == run["context"]
            checks.append("h2_file_restart_preserves_source_report_and_context")
            csrf.update(call(base, "/csrf"))
            current_profile = call(base, profile_path)
            if args.previous_jar and not had_profile:
                assert restored_run["freshness"] == "legacy_unbound"
                assert restored_run["profileSha256"] is None and restored_run["profile"] is None
                checks.append("v1_to_v2_migration_keeps_legacy_report_unbound")
                updated = call(base, profiles_path, profile_update(current_profile, secret_ext))
            else:
                assert current_profile["sha256"] == updated["sha256"]
                assert restored_run["freshness"] == "profile_changed"
                checks.append("h2_restart_preserves_profile_and_staleness")
            assert restored_run["executionMode"] == "static"
            assert restored_run["runtimeSha256"] is None and restored_run["runtime"] is None
            checks.append("migration_preserves_static_reports_without_fabricating_runtime")
            assert restored_run["bindingSha256"] is None and restored_run["binding"] is None
            checks.append("v4_migration_preserves_legacy_reports_without_fabricating_at_binding")
            assert restored_run["triggerKind"] == "manual"
            assert restored_run["scheduleSha256"] is None and restored_run["schedule"] is None
            checks.append("v5_migration_marks_old_runs_manual_without_fabricating_schedule")
            reordered_ext = {"options": {"a": 1, "b": 2}, "token": "FIXTURE_SECRET_NOT_FOR_PERSISTENCE"}
            deduped = call(base, profiles_path, profile_update(updated, reordered_ext))
            assert deduped["sha256"] == updated["sha256"]
            checks.append("canonical_ext_fingerprint_after_migration" if args.previous_jar
                          else "canonical_ext_fingerprint_and_salt_survive_restart")
            bound_run = await_run(base, call(base, f"/sources/{source['id']}/probe-runs", {}))
            assert bound_run["freshness"] == "current"
            assert bound_run["profileSha256"] == updated["sha256"]
            assert bound_run["result"]["requiredCoverage"]["notTested"] == 1
            assert bound_run["result"]["requiredCoverage"]["conclusion"] == "required_incomplete"
            assert bound_run["context"]["runtime"] == "fongmi"
            checks.append("required_untested_capability_never_passes")
            cancelled = call(base, f"/probe-runs/{bound_run['id']}/cancel", {})
            repeated = call(base, f"/probe-runs/{bound_run['id']}/cancel", {})
            assert cancelled == repeated == bound_run
            checks.append("terminal_cancel_is_idempotent_and_preserves_report")
            current_archive = call(base, f"/sources/{source['id']}/export", binary=True)
            with zipfile.ZipFile(io.BytesIO(current_archive)) as zf:
                assert hashlib.sha256(zf.read("profile.json")).hexdigest() == updated["sha256"]
                assert hashlib.sha256(zf.read("probe-profile.json")).hexdigest() == bound_run["profileSha256"]
                assert all(b"FIXTURE_SECRET_NOT_FOR_PERSISTENCE" not in zf.read(name) for name in zf.namelist())
            checks.append("export_binds_profiles_without_ext_values")
            if args.archive_out:
                args.archive_out.write_bytes(current_archive)
            runtime = call(base, "/runtime")
            assert runtime["manifest"]["authority"] == "expected_bundle"
            fixture = call(base, "/fixtures/demo")
            fixture_source = call(base, "/sources", fixture)
            fixture_run = await_run(base, call(base, f"/sources/{fixture_source['id']}/probe-runs",
                                               {"mode": "offline_fixture"}))
            assert fixture_run["executionMode"] == "offline_fixture"
            assert fixture_run["runtimeSha256"] == runtime["sha256"]
            assert fixture_run["result"]["evidenceClass"] == "offline_fixture"
            statuses = [node["status"] for node in fixture_run["result"]["nodes"]]
            assert statuses.count("pass") == 8 and statuses.count("not_tested") == 36
            receipt = fixture_run["result"]["runtimeEvidence"]["receipt"]
            assert receipt["authority"] == "bootstrap_observed"
            assert receipt["sourceBytesSha256"] == receipt["loadedTextSha256"] == fixture["sha256"]
            checks.append("product_api_trusted_worker_receipt_8_pass_36_untested")
            fixture_archive = call(base, f"/sources/{fixture_source['id']}/export", binary=True)
            with zipfile.ZipFile(io.BytesIO(fixture_archive)) as zf:
                assert hashlib.sha256(zf.read("probe-runtime.json")).hexdigest() == fixture_run["runtimeSha256"]
                fixture_manifest = json.loads(zf.read("manifest.json"))
                for name, record in fixture_manifest["files"].items():
                    assert hashlib.sha256(zf.read(name)).hexdigest() == record["sha256"]
            checks.append("fixture_export_binds_exact_runtime_manifest")
            if args.archive_out:
                args.archive_out.write_bytes(fixture_archive)
            blocked = call(base, f"/sources/{source['id']}/probe-runs", {"mode": "offline_fixture"})
            deadline = time.monotonic() + 15
            while blocked["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
                time.sleep(0.1)
                blocked = call(base, f"/probe-runs/{blocked['id']}")
            assert blocked["status"] == "BLOCKED" and blocked["errorCode"] == "TRUSTED_FIXTURE_REQUIRED"
            checks.append("product_api_rejects_unknown_source_execution")
            assert at_calls == [], "AT must not be contacted during startup, probing or source import"
            assert call(base, "/at/connection")["configured"]
            binding_path = f"/sources/{fixture_source['id']}/at-binding"
            assert call(base, binding_path) is None
            observation = call(base, "/at/observations", {})
            assert call(base, "/at/observations", {}) == observation
            assert at_calls == [("GET", "/api/plugins", "SYNTHETIC_SESSION_SECRET")]
            checks.append("explicit_single_origin_read_and_30_second_cache")
            first_binding = call(base, binding_path, dict(observationSha256=observation["sha256"], pluginId=7))
            frozen_fixture = await_run(base, call(base, f"/sources/{fixture_source['id']}/probe-runs",
                                                 {"mode": "offline_fixture"}))
            assert frozen_fixture["bindingSha256"] == first_binding["sha256"]
            assert frozen_fixture["binding"] == first_binding["snapshot"]
            current_binding = call(base, binding_path, dict(expectedSha256=first_binding["sha256"],
                                   observationSha256=observation["sha256"], pluginId=8))
            frozen_fixture = call(base, f"/probe-runs/{frozen_fixture['id']}")
            assert frozen_fixture["binding"] == first_binding["snapshot"]
            assert frozen_fixture["freshness"] == "binding_changed"
            checks.append("binding_frozen_at_submission_and_rebind_invalidates_report")
            binding_archive = call(base, f"/sources/{fixture_source['id']}/export", binary=True)
            with zipfile.ZipFile(io.BytesIO(binding_archive)) as zf:
                assert hashlib.sha256(zf.read("at-binding.json")).hexdigest() == current_binding["sha256"]
                assert hashlib.sha256(zf.read("probe-at-binding.json")).hexdigest() == first_binding["sha256"]
                for name, record in json.loads(zf.read("manifest.json"))["files"].items():
                    assert hashlib.sha256(zf.read(name)).hexdigest() == record["sha256"]
                    assert all(secret.encode() not in zf.read(name) for secret in
                               ("LOCATOR_SECRET", "EXT_SECRET", "ERROR_SECRET", "SOURCE_SECRET",
                                "SYNTHETIC_SESSION_SECRET"))
            checks.append("export_separates_current_and_frozen_at_binding_without_secrets")
            if args.archive_out:
                args.archive_out.write_bytes(binding_archive)
        finally:
            stop(process)
        process, base = start(3)
        try:
            restored_fixture = call(base, f"/probe-runs/{frozen_fixture['id']}")
            assert restored_fixture == frozen_fixture
            checks.append("h2_restart_preserves_frozen_runtime_and_worker_evidence")
            assert call(base, binding_path) == current_binding
            csrf.update(call(base, "/csrf"))
            try:
                call(base, binding_path, dict(expectedSha256=current_binding["sha256"],
                     observationSha256=observation["sha256"], pluginId=7))
                raise AssertionError("A previous-process observation must not authorize a new binding")
            except urllib.error.HTTPError as error:
                assert error.code == 409
                assert json.loads(error.read())["code"] == "AT_OBSERVATION_CHANGED"
            checks.append("h2_restart_preserves_binding_but_not_ephemeral_observation_authority")
        finally:
            stop(process)
        process, base = start(4, disconnected=True)
        try:
            assert not call(base, "/at/connection")["configured"]
            assert not call(base, binding_path)["connectionMatches"]
            stale_fixture = call(base, f"/probe-runs/{frozen_fixture['id']}")
            assert "at_connection" in stale_fixture["staleInputs"]
            assert stale_fixture["binding"] == first_binding["snapshot"]
            csrf.update(call(base, "/csrf"))
            assert call(base, binding_path + "/unbind", dict(expectedSha256=current_binding["sha256"])) is None
            assert call(base, binding_path) is None
            unbound_fixture = call(base, f"/probe-runs/{frozen_fixture['id']}")
            assert unbound_fixture["binding"] == first_binding["snapshot"]
            assert "at_connection" in unbound_fixture["staleInputs"], "Frozen connection must also be checked"
            assert at_calls == [("GET", "/api/plugins", "SYNTHETIC_SESSION_SECRET")]
            checks.append("disconnected_instance_invalidates_current_and_frozen_binding_even_after_unbind")
            schedule_path = f"/sources/{fixture_source['id']}/schedule"
            assert not call(base, "/scheduling")["enabled"]
            default_schedule = call(base, schedule_path)
            assert default_schedule["sha256"] is None and not default_schedule["policy"]["enabled"]
            configured_schedule = call(base, schedule_path, dict(
                enabled=True, mode="offline_fixture", intervalSeconds=1800, dailyLimit=1))
            assert configured_schedule["nextDueAt"] is not None and configured_schedule["activeRunId"] is None
            assert configured_schedule["usedAttempts"] == 0
            checks.append("explicit_policy_stays_future_and_global_scheduler_defaults_disabled")
        finally:
            stop(process)
        make_schedule_due(fixture_source["id"])
        process, base = start(5, disconnected=True, scheduling=True)
        try:
            assert call(base, "/scheduling")["enabled"]
            deadline = time.monotonic() + 30
            state = call(base, schedule_path)
            while not state["activeRunId"] and time.monotonic() < deadline:
                time.sleep(0.2)
                state = call(base, schedule_path)
            assert state["activeRunId"], "Real @Scheduled coordinator did not submit the due fixture"
            scheduled_run = await_run(base, call(base, f"/probe-runs/{state['activeRunId']}"))
            assert scheduled_run["triggerKind"] == "scheduled"
            assert scheduled_run["scheduleSha256"] == configured_schedule["sha256"]
            assert scheduled_run["schedule"] == configured_schedule["policy"]
            assert scheduled_run["scheduleOrdinal"] == state["usedAttempts"] == 1
            assert call(base, "/scheduling")["usedAttempts"] == 1
            statuses = [node["status"] for node in scheduled_run["result"]["nodes"]]
            assert statuses.count("pass") == 8 and statuses.count("not_tested") == 36
            checks.append("real_background_coordinator_executes_trusted_worker_with_frozen_policy_and_budget")
        finally:
            stop(process)
        make_schedule_due(fixture_source["id"])
        process, base = start(6, disconnected=True, scheduling=True)
        try:
            deadline = time.monotonic() + 30
            state = call(base, schedule_path)
            while state["activeRunId"] and time.monotonic() < deadline:
                time.sleep(0.2)
                state = call(base, schedule_path)
            assert state["activeRunId"] is None and state["lastRunId"] == scheduled_run["id"]
            assert state["lastOutcome"] == "declared_checks_passed"
            assert state["usedAttempts"] == call(base, "/scheduling")["usedAttempts"] == 1
            scheduled = [run for run in call(base, f"/sources/{fixture_source['id']}/probe-runs")
                         if run["triggerKind"] == "scheduled"]
            assert len(scheduled) == 1
            checks.append("h2_restart_recovers_ownership_and_daily_budget_blocks_second_due_job")
            csrf.update(call(base, "/csrf"))
            edited_schedule = call(base, schedule_path, dict(expectedSha256=state["sha256"],
                                   enabled=False, mode="static", intervalSeconds=3600, dailyLimit=2))
            assert edited_schedule["usedAttempts"] == 1
            schedule_archive = call(base, f"/sources/{fixture_source['id']}/export", binary=True)
            with zipfile.ZipFile(io.BytesIO(schedule_archive)) as zf:
                assert hashlib.sha256(zf.read("schedule-policy.json")).hexdigest() == edited_schedule["sha256"]
                assert hashlib.sha256(zf.read("probe-schedule-policy.json")).hexdigest() == scheduled_run["scheduleSha256"]
                assert json.loads(zf.read("schedule-state.json"))["usedAttempts"] == 1
                for name, record in json.loads(zf.read("manifest.json"))["files"].items():
                    assert hashlib.sha256(zf.read(name)).hexdigest() == record["sha256"]
            if args.archive_out:
                args.archive_out.write_bytes(schedule_archive)
            assert at_calls == [("GET", "/api/plugins", "SYNTHETIC_SESSION_SECRET")]
            checks.append("schedule_edit_preserves_spent_budget_and_export_separates_frozen_policy")
            if args.database_backup_roundtrip:
                data_tool("backup", "--database", work / "maintenance.mv.db",
                          "--output", work / "in-use.zip", expected="DATABASE_IN_USE")
                assert not (work / "in-use.zip").exists()
                checks.append("whole_database_backup_refuses_a_real_running_jvm")
                repair_fixture = call(base, "/fixtures/repair")
                repair_source = call(base, "/sources", {key: repair_fixture[key]
                                     for key in ("name", "filename", "content")})
                repair_probe = await_run(base, call(base, f"/sources/{repair_source['id']}/probe-runs", {}))
                repair = call(base, f"/sources/{repair_source['id']}/repairs", dict(
                    baselineRunId=repair_probe["id"], expectedArtifactSha256=repair_source["artifactSha256"]))
                candidate = call(base, f"/repairs/{repair['id']}/proposals", repair_fixture["proposal"])
                deadline = time.monotonic() + 15
                while candidate["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
                    time.sleep(0.1)
                    candidate = call(base, f"/repair-candidates/{candidate['id']}")
                assert candidate["status"] == "STATIC_VALIDATED", candidate["status"]
                plan = call(base, f"/sources/{repair_source['id']}/release-plans", dict(
                    candidateId=candidate["id"], expectedArtifactSha256=repair_source["artifactSha256"],
                    expectedCandidateSha256=candidate["sha256"], requestKey="database-archive-smoke-plan"))
                assert not plan["publishEligible"]
                preserved_paths = [f"/sources/{source['id']}", profile_path, profiles_path,
                                   f"/probe-runs/{run['id']}", f"/probe-runs/{frozen_fixture['id']}",
                                   f"/probe-runs/{scheduled_run['id']}", schedule_path, "/scheduling",
                                   f"/repairs/{repair['id']}", f"/repair-candidates/{candidate['id']}",
                                   f"/release-plans/{plan['id']}", f"/release-plans/{plan['id']}/frozen"]
                preserved = {path: call(base, path) for path in preserved_paths}
                preserved_export = call(base, f"/repair-candidates/{candidate['id']}/export", binary=True)
                container_policy = call(base, "/container-runtime")
        finally:
            stop(process)
        if args.database_backup_roundtrip:
            # Fault injection modifies only this stopped, script-owned temporary database.
            sql = ("UPDATE maintenance_model_budget SET used_calls=3,reserved_input_bytes=321,"
                   "reserved_output_tokens=456,reserved_cost_usd=0.12;"
                   "UPDATE maintenance_probe_run SET status='RUNNING',execution_mode='container_fixture',"
                   "result_json=NULL,finished_at=NULL,error_code=NULL,"
                   f"execution_policy_sha256='{container_policy['sha256']}',"
                   "execution_policy_json='" + json.dumps(container_policy["snapshot"]).replace("'", "''") + "'"
                   f" WHERE id='{blocked['id']}'")
            result = subprocess.run([java, "-cp", str(work / "h2-smoke-tool.jar"),
                                     "org.h2.tools.Shell", "-url", datasource,
                                     "-user", "sa", "-password", "", "-sql", sql],
                                    cwd=work, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)
            assert result.returncode == 0 and result.stdout.count(b"Update count: 1") == 2
            whole_archive = work / "whole-database.zip"
            backup = data_tool("backup", "--database", work / "maintenance.mv.db", "--output", whole_archive)
            archive_sha = hashlib.sha256(whole_archive.read_bytes()).hexdigest()
            assert backup["archiveSha256"] == archive_sha and backup["privateData"]
            assert backup["manifest"]["schema"]["tables"]["MAINTENANCE_REPAIR_CANDIDATE"] == 1
            assert backup["manifest"]["schema"]["tables"]["MAINTENANCE_RELEASE_PLAN"] == 1
            inspected = data_tool("inspect", "--archive", whole_archive, "--trusted-sha256", archive_sha)
            assert not inspected["databaseOpened"] and inspected["manifest"] == backup["manifest"]
            checks.append("cold_backup_contains_every_table_migration_and_exact_private_database_identity")
            restored_dir = work / "restored"
            restored = data_tool("restore", "--archive", whole_archive, "--destination", restored_dir,
                                 "--trusted-sha256", archive_sha)
            assert not restored["applicationStarted"] and restored["requiresLocalOnlyStartup"]
            with zipfile.ZipFile(whole_archive) as archive:
                assert archive.read("maintenance.mv.db") == (restored_dir / "maintenance.mv.db").read_bytes()
            original_sha = hashlib.sha256((work / "maintenance.mv.db").read_bytes()).hexdigest()
            checks.append("whole_database_restored_to_new_directory_without_altering_its_bytes")
            data_tool("restore", "--archive", whole_archive, "--destination", restored_dir,
                      "--trusted-sha256", archive_sha, expected="ARCHIVE_TARGET_MUST_NOT_EXIST")
            assert hashlib.sha256((work / "maintenance.mv.db").read_bytes()).hexdigest() == original_sha
            checks.append("restore_refuses_existing_directory_and_leaves_original_database_unchanged")
            restored_url = ("jdbc:h2:file:" + (restored_dir / "maintenance").as_posix()
                            + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0")
            process, base = start(7, disconnected=True, database_url=restored_url, local_only=True)
            try:
                for path, before in preserved.items():
                    current = call(base, path)
                    if path == "/scheduling":
                        assert not current["enabled"]
                        assert current["usedAttempts"] == before["usedAttempts"] == 1
                    else:
                        assert current == before, path
                checks.append("restored_jvm_preserves_source_profile_frozen_reports_repairs_and_release_preparation")
                restored_export = call(base, f"/repair-candidates/{candidate['id']}/export", binary=True)
                with zipfile.ZipFile(io.BytesIO(preserved_export)) as old, \
                        zipfile.ZipFile(io.BytesIO(restored_export)) as new:
                    assert set(old.namelist()) == set(new.namelist())
                    assert all(old.read(name) == new.read(name) for name in old.namelist())
                checks.append("full_restore_preserves_every_frozen_candidate_export_file")
                recovered = call(base, f"/probe-runs/{blocked['id']}")
                assert recovered["status"] == "FAILED"
                assert recovered["errorCode"] == "CONTAINER_CLEANUP_UNCONFIRMED"
                assert recovered["result"] is None
                checks.append("restored_unfinished_container_is_unconfirmed_not_replayed_or_reported_clean")
                budget = call(base, "/model/budget")
                assert (budget["usedCalls"], budget["reservedInputBytes"], budget["reservedOutputTokens"]) == (3, 321, 456)
                assert budget["reservedCostUsd"] == "0.120000000000000000"
                assert not call(base, "/model/connection")["enabled"]
                assert not call(base, "/at/connection")["configured"]
                assert call(base, "/container-runtime")["blockedReason"] == "CONTAINER_EXECUTION_DISABLED"
                assert call(base, "/media-policy")["blockedReason"] == "MEDIA_EXECUTION_DISABLED"
                assert at_calls == [("GET", "/api/plugins", "SYNTHETIC_SESSION_SECRET")]
                checks.append("restored_local_instance_preserves_spent_budget_without_external_reactivation")
            finally:
                stop(process)
            assert hashlib.sha256((work / "maintenance.mv.db").read_bytes()).hexdigest() == original_sha
            checks.append("starting_the_restored_clone_does_not_modify_the_original_database")
    print(json.dumps({"passed": len(checks), "checks": checks}, indent=2))


if __name__ == "__main__":
    main()
