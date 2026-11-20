"""Verify V11->V12, bounded local media diagnostics and strong-stop recovery using real file H2."""
from __future__ import annotations

import argparse
import base64
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
import struct
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import zipfile

from ts_fixture import sample_ts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("java", "python", "jar", "previous-jar"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--demo-out", type=Path)
    args = parser.parse_args()
    java, python = shutil.which(args.java), shutil.which(args.python)
    if not java or not python:
        parser.error("Java and Python must exist")
    jar, previous = Path(args.jar).resolve(), Path(args.previous_jar).resolve()
    with zipfile.ZipFile(previous) as archive:
        assert "BOOT-INF/classes/db/migration/h2/V11__ReleasePreparation.sql" in archive.namelist()
        assert "BOOT-INF/classes/db/migration/h2/V12__MediaDiagnostics.sql" not in archive.namelist()
    work = Path(tempfile.mkdtemp(prefix="at-media-smoke-"))
    password = secrets.token_urlsafe(24)
    credential = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()
    marker = "media-input-secret-" + secrets.token_hex(12)
    checks = []
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().startswith(("MAINTENANCE_", "SPRING_"))}
    environment.update(SPRING_SECURITY_USER_NAME="admin", SPRING_SECURITY_USER_PASSWORD=password,
                       MAINTENANCE_PYTHON=str(Path(python).resolve()), MAINTENANCE_DB_PASSWORD="",
                       MAINTENANCE_AT_ORIGIN="", MAINTENANCE_AT_SESSION_TOKEN="",
                       MAINTENANCE_SCHEDULING_ENABLED="false", MAINTENANCE_CONTAINER_ENABLED="false",
                       MAINTENANCE_MODEL_ENABLED="false", MAINTENANCE_MEDIA_ENABLED="false",
                       MAINTENANCE_MEDIA_ALLOWED_ORIGINS="", MAINTENANCE_MEDIA_ALLOW_HTTP="false")
    datasource = "jdbc:h2:file:" + (work / "db").as_posix() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    base, csrf = "", {}
    stalled = threading.Event()
    release = threading.Event()
    fixture_requests = []
    packets = sample_ts()
    video = struct.pack(">I", 24) + b"ftypisom" + bytes(300000 - 12)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            fixture_requests.append(self.path.split("?", 1)[0])
            path = self.path.split("?", 1)[0]
            if path == "/stall":
                stalled.set()
                release.wait(30)
                return
            if path == "/redirect":
                self.send_response(302)
                self.send_header("Location", "http://127.0.0.1:1/private?" + marker)
                self.end_headers()
                return
            if path == "/master":
                body = b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nnested/list\n"
            elif path == "/nested/list":
                body = b"#EXTM3U\n#EXT-X-TARGETDURATION:6\n" + b"".join(
                    f"#EXTINF:6,\ns{index}\n".encode() for index in range(5)) + b"#EXT-X-ENDLIST\n"
            elif path.startswith("/nested/s"):
                body = packets
            elif path == "/video":
                match = re.fullmatch(r"bytes=(\d+)-(\d+)", self.headers.get("Range", ""))
                assert match
                start, end = int(match[1]), min(int(match[2]), len(video) - 1)
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{len(video)}")
                self.send_header("ETag", '"local-media-fixture"')
                self.send_header("Content-Length", str(end - start + 1))
                self.end_headers()
                self.wfile.write(video[start:end + 1])
                return
            else:
                body = b"<html>not media</html>"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    fixture = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    fixture.daemon_threads = True
    threading.Thread(target=fixture.serve_forever, daemon=True).start()
    origin = f"http://127.0.0.1:{fixture.server_port}"

    def stop(process, force=False):
        if process.poll() is None:
            (process.kill if force else process.terminate)()
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
                    raise RuntimeError("Server exited: " + str(log))
                match = re.search(r"Tomcat started on port (\d+)", log.read_text(encoding="utf-8", errors="replace"))
                if match:
                    return process, f"http://127.0.0.1:{match[1]}"
                time.sleep(0.2)
            raise RuntimeError("Startup timeout: " + str(log))
        except (RuntimeError, OSError):
            stop(process)
            raise

    def call(path, body=None, expected=200, binary=False):
        headers = {"Authorization": credential}
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

    def wait(path):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            value = call(path)
            if value["status"] not in ("QUEUED", "RUNNING", "CANCELLING"):
                return value
            time.sleep(0.1)
        raise AssertionError("Task did not finish: " + path)

    def submit(path, sha):
        return call(f"/sources/{source['id']}/media-probes", {
            "expectedArtifactSha256": sha,
            "player": {"parse": 0, "jx": 0, "playUrl": "", "url": path,
                       "header": {"Cookie": marker}}}, expected=202)

    def node(run, ident):
        return next(item for item in run["result"]["nodes"] if item["id"] == ident)

    def verify_export(run):
        body = call(f"/media-probes/{run['id']}/export", binary=True)
        with zipfile.ZipFile(io.BytesIO(body)) as archive:
            assert set(archive.namelist()) == {"manifest.json", "media-probe.json"}
            manifest = json.loads(archive.read("manifest.json"))
            assert manifest["sourceHealthEligible"] is False and manifest["publishEligible"] is False
            for name, item in manifest["files"].items():
                content = archive.read(name)
                assert hashlib.sha256(content).hexdigest() == item["sha256"] and len(content) == item["bytes"]
            assert json.loads(archive.read("media-probe.json")) == run
            for name in archive.namelist():
                content = archive.read(name)
                assert marker.encode() not in content and password.encode() not in content and origin.encode() not in content
        return body

    process = None
    try:
        process, base = start(1, previous)
        csrf = call("/csrf")
        source = call("/sources", {"name": "media upgrade fixture", "filename": "source.py",
                                  "content": "class Spider:\n    pass\n"}, expected=201)
        old = call(f"/sources/{source['id']}/probe-runs", {}, expected=202)
        old = wait("/probe-runs/" + old["id"])
        assert old["status"] == "COMPLETED", (old["status"], old["errorCode"])
        stop(process)
        checks.append("old_v11_file_database_seeded")

        process, base = start(2, jar)
        csrf = call("/csrf")
        assert call("/probe-runs/" + old["id"])["result"] == old["result"]
        assert call(f"/sources/{source['id']}")["artifactSha256"] == source["artifactSha256"]
        checks.append("v11_to_v12_preserves_source_and_old_probe")
        default = submit("https://example.invalid/video?" + marker, source["artifactSha256"])
        default = wait("/media-probes/" + default["id"])
        assert default["result"]["verdict"] == "sample_blocked" and default["result"]["usage"]["requests"] == 0
        verify_export(default)
        assert not fixture_requests
        checks.append("default_disabled_without_network_or_sensitive_persistence")
        stop(process)

        environment.update(MAINTENANCE_MEDIA_ENABLED="true", MAINTENANCE_MEDIA_ALLOW_HTTP="true",
                           MAINTENANCE_MEDIA_ALLOWED_ORIGINS=origin)
        process, base = start(3, jar)
        csrf = call("/csrf")
        assert "media_policy" in call("/media-probes/" + default["id"])["staleInputs"]
        hls = wait("/media-probes/" + submit(origin + "/master?" + marker, source["artifactSha256"])["id"])
        assert hls["status"] == "COMPLETED" and hls["result"]["verdict"] == "sample_checked"
        assert all(node(hls, ident)["status"] == "pass" for ident in ("M01", "M02", "M03", "M05"))
        assert fixture_requests == ["/master", "/nested/list", "/nested/s0", "/nested/s2", "/nested/s4"]
        assert hls["sourceHealthEligible"] is False
        demo = verify_export(hls)
        checks.append("real_local_http_hls_variant_and_three_ts_samples_with_redacted_export")
        mp4 = wait("/media-probes/" + submit(origin + "/video", source["artifactSha256"])["id"])
        assert node(mp4, "M06")["status"] == "pass" and mp4["result"]["usage"]["requests"] == 3
        checks.append("real_local_http_mp4_three_coherent_ranges")
        html = wait("/media-probes/" + submit(origin + "/html", source["artifactSha256"])["id"])
        assert node(html, "M01")["reason"] == "MEDIA_ERROR_DOCUMENT"
        redirect = wait("/media-probes/" + submit(origin + "/redirect", source["artifactSha256"])["id"])
        assert node(redirect, "M01")["reason"] == "MEDIA_ORIGIN_REFUSED"
        checks.append("successful_error_document_and_unapproved_redirect_rejected")
        stalled.clear()
        release.clear()
        cancel = submit(origin + "/stall", source["artifactSha256"])
        assert stalled.wait(3)
        call("/media-probes/" + cancel["id"] + "/cancel", {})
        cancel = wait("/media-probes/" + cancel["id"])
        assert cancel["status"] == "CANCELLED" and cancel["result"] is None
        release.set()
        checks.append("cancel_inflight_request_preserves_cancelled_terminal_state")
        stalled.clear()
        release.clear()
        interrupted = submit(origin + "/stall", source["artifactSha256"])
        assert stalled.wait(3)
        assert call("/media-probes/" + interrupted["id"])["status"] == "RUNNING"
        stop(process, force=True)
        release.set()

        process, base = start(4, jar)
        csrf = call("/csrf")
        recovered = call("/media-probes/" + interrupted["id"])
        assert recovered["status"] == "INTERRUPTED"
        assert recovered["errorCode"] == "SERVICE_RESTARTED_INPUT_NOT_RETAINED" and recovered["result"] is None
        assert call("/media-probes/" + hls["id"])["result"] == hls["result"]
        assert call("/media-probes/" + mp4["id"])["result"] == mp4["result"]
        checks.append("strong_stop_recovery_preserves_evidence_and_never_replays_transport_input")
        call(f"/sources/{source['id']}/artifacts", {"expectedSha256": source["artifactSha256"],
                                                "content": "class Spider:\n    changed = True\n"})
        assert "source" in call("/media-probes/" + hls["id"])["staleInputs"]
        rejected = call(f"/sources/{source['id']}/media-probes", {
            "expectedArtifactSha256": source["artifactSha256"], "player": {"url": origin + "/html"}}, expected=409)
        assert rejected["code"] == "SOURCE_CHANGED"
        assert call("/probe-runs/" + old["id"])["result"] == old["result"]
        checks.append("source_drift_blocks_stale_submission_without_mutating_historical_evidence")
        if args.demo_out:
            assert not args.demo_out.exists()
            args.demo_out.parent.mkdir(parents=True, exist_ok=True)
            args.demo_out.write_bytes(demo)
        result = {"schemaVersion": 1, "passed": len(checks), "processes": 4, "checks": checks,
                  "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                  "previousJarSha256": hashlib.sha256(previous.read_bytes()).hexdigest(),
                  "fixtureRequestCount": len(fixture_requests), "logDirectory": str(work),
                  "boundary": "Local trusted HTTP bytes and operator-supplied player only; no managed spider, "
                              "candidate execution, production service, real media decode or player verification."}
        if args.json_out:
            args.json_out.parent.mkdir(parents=True, exist_ok=True)
            args.json_out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, indent=2))
    finally:
        release.set()
        if process is not None:
            stop(process)
        fixture.shutdown()
        fixture.server_close()


if __name__ == "__main__":
    main()
