"""Verify V17->V18 upstream coordination and immutable receipts on a local file H2 database."""
from __future__ import annotations

import argparse
import base64
import contextlib
import hashlib
import http.cookiejar
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
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
    for output in (args.json_out, args.demo_out):
        if output and (output.exists() or not output.parent.is_dir()):
            parser.error("Output must be a new file in an existing directory")
    java, python = str(Path(java).resolve()), str(Path(python).resolve())
    jar, previous = Path(args.jar).resolve(), Path(args.previous_jar).resolve()
    migration = "BOOT-INF/classes/db/migration/h2/V18__UpstreamCoordination.sql"
    with zipfile.ZipFile(previous) as archive:
        assert migration not in archive.namelist()
        assert "BOOT-INF/classes/db/migration/h2/V17__SourceArchiveImports.sql" in archive.namelist()
    with zipfile.ZipFile(jar) as archive:
        assert migration in archive.namelist()
    password = secrets.token_urlsafe(24)
    credential = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().startswith(("MAINTENANCE_", "SPRING_"))}
    environment.update(SPRING_SECURITY_USER_PASSWORD=password, SPRING_SECURITY_USER_NAME="admin",
                       MAINTENANCE_PYTHON=python, MAINTENANCE_DB_PASSWORD="",
                       MAINTENANCE_AT_ORIGIN="", MAINTENANCE_AT_SESSION_TOKEN="",
                       MAINTENANCE_SCHEDULING_ENABLED="false", MAINTENANCE_CONTAINER_ENABLED="false",
                       MAINTENANCE_MODEL_ENABLED="false", MAINTENANCE_MEDIA_ENABLED="false")
    base_source = "\ufeff# \u4e0a\u6e38\u57fa\u7ebf\r\nraise RuntimeError('MUST_NOT_EXECUTE_UPSTREAM')\r\n"
    local_source = base_source + "# local maintained version\r\n"
    upstream_source = base_source + "# upstream changes\r\n"
    sha = lambda body: hashlib.sha256(body).hexdigest()
    checks, launches = [], 0
    demo = None
    work = Path(tempfile.mkdtemp(prefix="at-upstream-smoke-"))

    @contextlib.contextmanager
    def service(executable_jar, *, force_stop=False):
        nonlocal launches
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        base = f"http://127.0.0.1:{port}/api/plugin-maintenance"
        datasource = "jdbc:h2:file:" + (work / "db").as_posix() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        csrf = {}

        def request(path, body=None, *, raw=False, expected=200):
            headers = {"Authorization": credential}
            if body is not None:
                if not csrf:
                    csrf.update(request("/csrf"))
                headers.update({"Content-Type": "application/json", csrf["headerName"]: csrf["token"]})
            value = None if body is None else json.dumps(body, ensure_ascii=True).encode()
            outgoing = urllib.request.Request(base + path, data=value, headers=headers)
            try:
                with opener.open(outgoing, timeout=20) as response:
                    status, payload = response.status, response.read()
            except urllib.error.HTTPError as error:
                status, payload = error.code, error.read()
            assert status == expected, (path, status, expected)
            return payload if raw else json.loads(payload) if payload else None

        launches += 1
        with (work / f"jvm-{launches}.log").open("wb") as log:
            process = subprocess.Popen([java, "-jar", str(executable_jar),
                "--server.address=127.0.0.1", f"--server.port={port}",
                "--spring.datasource.url=" + datasource], cwd=work, env=environment,
                stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 60
                while True:
                    assert process.poll() is None, "Local test JVM exited during startup"
                    try:
                        request("/sources")
                        break
                    except (urllib.error.URLError, TimeoutError, ConnectionError):
                        assert time.monotonic() < deadline, "Local test JVM startup timed out"
                        time.sleep(0.1)
                yield request
            finally:
                (process.kill if force_stop else process.terminate)()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)

    with service(previous) as api:
        clean = api("/sources", dict(name="upstream clean fixture", filename="clean.py",
                    content=base_source), expected=201)
        maintained = api("/sources", dict(name="upstream divergent fixture", filename="maintained.py",
                         content=base_source), expected=201)
        clean_path, local_path = ("/sources/" + item["id"] for item in (clean, maintained))
        api(local_path + "/artifacts", dict(expectedSha256=maintained["artifactSha256"], content=local_source))
        profile = api(clean_path + "/profile")
        probe = api(clean_path + "/probe-runs", {}, expected=202)
        deadline = time.monotonic() + 20
        while True:
            old_probe = api("/probe-runs/" + probe["id"])
            if old_probe["status"] not in {"QUEUED", "RUNNING"}:
                break
            assert time.monotonic() < deadline
            time.sleep(0.05)
        assert old_probe["status"] == "COMPLETED"
        checks.append("old_v17_sources_local_branch_profile_and_probe_seeded")

    with service(jar, force_stop=True) as api:
        assert api(clean_path + "/upstream", expected=204) is None
        assert api(local_path + "/upstream", expected=204) is None
        assert api(clean_path + "/profile") == profile
        assert api("/probe-runs/" + probe["id"])["result"] == old_probe["result"]
        for path in (clean_path, local_path):
            current = api(path)
            api(path + "/upstream/initialize", dict(requestKey="declare-baseline",
                expectedArtifactSha256=current["artifactSha256"], baselineSha256=sha(base_source.encode())))
        checks.append("v17_to_v18_preserves_old_evidence_and_does_not_infer_upstream_provenance")

        state = api(clean_path + "/upstream")
        observe_request = dict(requestKey="clean-upstream-observation", expectedRevision=state["revision"],
            expectedArtifactSha256=state["effectiveSha256"], content=upstream_source)
        observation = api(clean_path + "/upstream/observations", observe_request)
        assert observation["after"]["status"] == "UPSTREAM_AHEAD"
        assert api(clean_path)["artifactSha256"] == clean["artifactSha256"]
        state = observation["after"]
        fast_forward = dict(requestKey="clean-fast-forward", expectedRevision=state["revision"],
            expectedArtifactSha256=state["effectiveSha256"], decision="FAST_FORWARD")
        adopted = api(clean_path + "/upstream/resolve", fast_forward)
        assert adopted["after"]["status"] == "IN_SYNC"
        assert not adopted["after"]["runtimeApplied"] and not adopted["after"]["publishEligible"]
        stale_probe = api("/probe-runs/" + probe["id"])
        assert stale_probe["result"] == old_probe["result"] and "source" in stale_probe["staleInputs"]
        api(clean_path + "/artifacts", dict(expectedSha256=adopted["after"]["effectiveSha256"], content=local_source))
        assert api(clean_path + "/upstream/observations", observe_request) == observation
        assert api(clean_path + "/upstream/resolve", fast_forward) == adopted
        assert api(clean_path)["artifactSha256"] == sha(local_source.encode())
        checks.append("observation_does_not_apply_and_explicit_adoption_stales_report_without_rewriting")
        checks.append("idempotent_observation_and_adoption_never_revert_later_local_edits")

        state = api(local_path + "/upstream")
        assert state["status"] == "LOCAL_AHEAD"
        observed_local = api(local_path + "/upstream/observations", dict(requestKey="divergent-observation",
            expectedRevision=state["revision"], expectedArtifactSha256=state["effectiveSha256"],
            content=upstream_source))
        state = observed_local["after"]
        assert state["status"] == "DIVERGED"
        before_events = api(local_path + "/upstream/events")
        rejected = api(local_path + "/upstream/resolve", dict(requestKey="rejected-fast-forward",
            expectedRevision=state["revision"], expectedArtifactSha256=state["effectiveSha256"],
            decision="FAST_FORWARD"), expected=409)
        assert rejected == {"code": "UPSTREAM_LOCAL_CHANGES"}
        assert api(local_path + "/upstream") == state
        assert api(local_path + "/upstream/events") == before_events
        checks.append("divergent_fast_forward_rejected_without_state_or_receipt_changes")

        demo = api(local_path + "/upstream/export", raw=True)
        with zipfile.ZipFile(io.BytesIO(demo)) as archive:
            assert archive.testzip() is None
            assert set(archive.namelist()) == {"base.py", "upstream.py", "effective.py", "coordination.json", "manifest.json"}
            for name, expected in (("base.py", base_source), ("upstream.py", upstream_source),
                                   ("effective.py", local_source)):
                assert archive.read(name) == expected.encode()
            manifest = json.loads(archive.read("manifest.json"))
            assert manifest["kind"] == "upstream_three_way_comparison"
            assert not manifest["runtimeApplied"] and not manifest["publishEligible"]
            for name, record in manifest["files"].items():
                assert len(archive.read(name)) == record["bytes"] and sha(archive.read(name)) == record["sha256"]
            assert json.loads(archive.read("coordination.json")) == state
        rejected_package = api("/source-imports/preview", dict(archiveBase64=base64.b64encode(demo).decode()), expected=400)
        assert rejected_package == {"code": "IMPORT_ENTRY_INVALID"}
        normal_package = api(local_path + "/export", raw=True)
        preview = api("/source-imports/preview", dict(archiveBase64=base64.b64encode(normal_package).decode()))
        assert preview["artifactSha256"] == sha(local_source.encode())
        checks.append("three_way_export_binds_original_bytes_and_is_distinct_from_normal_source_restore")

        api(local_path + "/upstream/resolve", dict(requestKey="keep-local-explicit",
            expectedRevision=state["revision"], expectedArtifactSha256=state["effectiveSha256"], decision="KEEP_LOCAL"))
        local_final = api(local_path + "/upstream")
        clean_final = api(clean_path + "/upstream")
        assert local_final["status"] == "LOCAL_AHEAD"
        assert local_final["baseSha256"] == sha(upstream_source.encode())
        assert api(local_path)["artifactSha256"] == sha(local_source.encode())
        history = api(local_path + "/upstream/events")
        assert len(history) == 3
        checks.append("keep_local_records_before_and_after_without_claiming_merge_or_runtime_success")

    with service(jar) as api:
        assert api(local_path + "/upstream") == local_final
        assert api(clean_path + "/upstream") == clean_final
        assert api(local_path + "/upstream/events") == history
        assert api(clean_path + "/upstream/observations", observe_request) == observation
        assert api(clean_path + "/upstream/resolve", fast_forward) == adopted
        assert api(clean_path)["artifactSha256"] == sha(local_source.encode())
        assert api("/probe-runs/" + probe["id"])["result"] == old_probe["result"]
        assert not api(local_path + "/probe-runs")
        checks.append("strong_stop_restart_preserves_coordination_receipts_and_never_reapplies_replays")

    if args.demo_out:
        args.demo_out.write_bytes(demo)
    result = dict(schemaVersion=1, migration="V17->V18", passed=len(checks), processes=launches, checks=checks,
                  jarSha256=sha(jar.read_bytes()), previousJarSha256=sha(previous.read_bytes()),
                  demoSha256=sha(demo), demoBytes=len(demo), logDirectory=str(work),
                  boundary="Operator-supplied source bytes and local registry coordination only. "
                  "No source execution, upstream network access, production AT, model calls, runtime promotion or deployment.")
    if args.json_out:
        args.json_out.write_bytes(json.dumps(result, indent=2).encode() + b"\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
