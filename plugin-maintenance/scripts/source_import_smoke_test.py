"""Verify V16->V17 and cross-database source archive restoration with local JVMs only."""
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


def digest(body):
    return hashlib.sha256(body).hexdigest()


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
    jar, previous = Path(args.jar).resolve(), Path(args.previous_jar).resolve()
    migration = "BOOT-INF/classes/db/migration/h2/V17__SourceArchiveImports.sql"
    with zipfile.ZipFile(previous) as archive:
        assert migration not in archive.namelist()
        assert "BOOT-INF/classes/db/migration/h2/V16__ModelCostBudgets.sql" in archive.namelist()
    with zipfile.ZipFile(jar) as archive:
        assert migration in archive.namelist()
    password = secrets.token_urlsafe(24)
    credential = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().startswith(("MAINTENANCE_", "SPRING_"))}
    environment.update(SPRING_SECURITY_USER_PASSWORD=password, SPRING_SECURITY_USER_NAME="admin",
                       MAINTENANCE_PYTHON=str(Path(python).resolve()), MAINTENANCE_DB_PASSWORD="",
                       MAINTENANCE_AT_ORIGIN="", MAINTENANCE_AT_SESSION_TOKEN="",
                       MAINTENANCE_SCHEDULING_ENABLED="false", MAINTENANCE_CONTAINER_ENABLED="false",
                       MAINTENANCE_MODEL_ENABLED="false", MAINTENANCE_MEDIA_ENABLED="false")
    checks = []
    launches = 0

    with tempfile.TemporaryDirectory(prefix="at-source-import-") as temporary:
        work = Path(temporary)

        @contextlib.contextmanager
        def service(executable_jar, database):
            nonlocal launches
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                port = listener.getsockname()[1]
            base = f"http://127.0.0.1:{port}/api/plugin-maintenance"
            datasource = "jdbc:h2:file:" + (work / database).as_posix() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
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
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)

        content = "\ufeff# " + "\u79bb\u7ebf\u6062\u590d" + "\r\nraise RuntimeError('never execute')\r\nclass Spider: pass\r\n"
        with service(previous, "upgrade") as api:
            original = api("/sources", dict(name="\u539f\u59cb\u6e90", filename="original.py", content=content),
                           expected=201)
            profile = api("/sources/" + original["id"] + "/profile")
            saved_profile = api("/sources/" + original["id"] + "/profiles", dict(
                expectedSha256=profile["sha256"], runtime="t4", runtimeRevision="fixture-declaration",
                requiredNodes=["S01", "S02", "S03", "C01"], optionalNodes=["M01"],
                ext={"private": "SOURCE_IMPORT_CONFIG_SENTINEL"}))
            run = api("/sources/" + original["id"] + "/probe-runs", {}, expected=202)
            deadline = time.monotonic() + 20
            while True:
                saved_run = api("/probe-runs/" + run["id"])
                if saved_run["status"] not in {"QUEUED", "RUNNING"}:
                    break
                assert time.monotonic() < deadline
                time.sleep(0.05)
            assert saved_run["status"] == "COMPLETED"
            package = api("/sources/" + original["id"] + "/export", raw=True)
            old_profile_bytes = json.dumps(saved_profile["snapshot"], sort_keys=True).encode()
            old_run_bytes = json.dumps(saved_run, sort_keys=True).encode()
            with zipfile.ZipFile(io.BytesIO(package)) as archive:
                assert archive.read("source.py") == content.encode()
                assert not any(b"SOURCE_IMPORT_CONFIG_SENTINEL" in archive.read(name)
                               for name in archive.namelist())
            checks.append("old V16 source/profile/report and exact source package created")

        encoded = base64.b64encode(package).decode()
        confirmed = dict(archiveBase64=encoded, expectedArchiveSha256=digest(package),
                         requestKey="file-upgrade-source-import", name="\u6062\u590d\u6e90", filename="restored.py")
        with service(jar, "upgrade") as api:
            assert len(api("/sources")) == 1
            assert api("/sources/" + original["id"] + "/artifacts/" + original["artifactSha256"],
                       raw=True) == content.encode()
            assert json.dumps(api("/sources/" + original["id"] + "/profile")["snapshot"],
                              sort_keys=True).encode() == old_profile_bytes
            assert json.dumps(api("/probe-runs/" + saved_run["id"]), sort_keys=True).encode() == old_run_bytes
            preview = api("/source-imports/preview", dict(archiveBase64=encoded))
            assert preview["configRebindRequired"] and preview["reportPresent"]
            assert len(api("/sources")) == 1
            receipt = api("/source-imports", confirmed, expected=201)
            restored_id = receipt["sourceId"]
            assert restored_id != original["id"]
            assert receipt["originalSourceId"] == original["id"]
            assert api("/source-imports/" + receipt["id"] + "/archive", raw=True) == package
            restored_profile = api("/sources/" + restored_id + "/profile")
            assert restored_profile["snapshot"]["extFingerprint"] is None
            assert restored_profile["snapshot"]["requiredNodes"] == ["C01", "S01", "S02", "S03"]
            assert api("/sources/" + restored_id + "/probe-runs") == []
            assert api("/sources/" + restored_id + "/at-binding", raw=True, expected=204) == b""
            assert api("/sources/" + restored_id + "/schedule")["policy"]["enabled"] is False
            changed = content + "# local edit\r\n"
            api("/sources/" + restored_id + "/artifacts", dict(
                expectedSha256=receipt["artifactSha256"], content=changed))
            assert api("/source-imports", confirmed, expected=201) == receipt
            assert api("/sources/" + restored_id)["artifactSha256"] == digest(changed.encode())
            assert len(api("/sources")) == 2
            checks.append("V17 migration preserves old evidence; preview is read-only and restore clears EXT")
            checks.append("same-key replay returns historical receipt without overwriting local edits")

        with service(jar, "upgrade") as api:
            assert api("/source-imports", confirmed, expected=201) == receipt
            assert api("/source-imports/" + receipt["id"] + "/archive", raw=True) == package
            assert api("/sources/" + restored_id + "/imports") == [receipt]
            assert api("/sources/" + restored_id)["artifactSha256"] == digest(changed.encode())
            assert len(api("/sources")) == 2
            rejected = api("/source-imports", {**confirmed, "name": "changed-name"}, expected=409)
            assert rejected["code"] == "IMPORT_REQUEST_KEY_REUSED"
            checks.append("file H2 restart preserves receipt, original ZIP, edits and request-key conflict")

        with service(jar, "other-machine") as api:
            other = api("/source-imports", confirmed, expected=201)
            assert other["sourceId"] not in {original["id"], restored_id}
            assert api("/source-imports/" + other["id"] + "/archive", raw=True) == package
            assert api("/sources/" + other["sourceId"] + "/artifacts/" + other["artifactSha256"],
                       raw=True) == content.encode()
            assert api("/sources/" + other["sourceId"] + "/probe-runs") == []
            checks.append("fresh database uses new local identities and preserves exact source/archive bytes")

        result = dict(schemaVersion=1, processes=launches, passed=len(checks), checks=checks,
                      jarSha256=digest(jar.read_bytes()), previousJarSha256=digest(previous.read_bytes()),
                      demoSha256=digest(package), demoBytes=len(package), realModelCalled=False,
                      productionAtCalled=False, submittedSourceExecuted=False,
                      boundary="Local file-H2 JVMs and static AST only; no Linux, real source runtime or publication.")
        if args.demo_out:
            with args.demo_out.open("xb") as output:
                output.write(package)
        if args.json_out:
            with args.json_out.open("xb") as output:
                output.write(json.dumps(result, indent=2).encode() + b"\n")
        print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
