"""Verify V12->V13 media reservations, host limits and strong-stop recovery on file H2."""
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
    java, python = str(Path(java).resolve()), str(Path(python).resolve())
    jar, previous = Path(args.jar).resolve(), Path(args.previous_jar).resolve()
    with zipfile.ZipFile(previous) as archive:
        assert "BOOT-INF/classes/db/migration/h2/V12__MediaDiagnostics.sql" in archive.namelist()
        assert "BOOT-INF/classes/db/migration/h2/V13__MediaBudgets.sql" not in archive.namelist()
    work = Path(tempfile.mkdtemp(prefix="at-media-budget-smoke-"))
    password = secrets.token_urlsafe(24)
    credential = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()
    marker = "budget-input-secret-" + secrets.token_hex(12)
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().startswith(("MAINTENANCE_", "SPRING_"))}
    environment.update(SPRING_SECURITY_USER_NAME="admin", SPRING_SECURITY_USER_PASSWORD=password,
                       MAINTENANCE_PYTHON=python, MAINTENANCE_DB_PASSWORD="",
                       MAINTENANCE_AT_ORIGIN="", MAINTENANCE_AT_SESSION_TOKEN="",
                       MAINTENANCE_SCHEDULING_ENABLED="false", MAINTENANCE_CONTAINER_ENABLED="false",
                       MAINTENANCE_MODEL_ENABLED="false", MAINTENANCE_MEDIA_ENABLED="false",
                       MAINTENANCE_MEDIA_ALLOWED_ORIGINS="", MAINTENANCE_MEDIA_ALLOW_HTTP="false",
                       MAINTENANCE_MEDIA_DAILY_PROBE_LIMIT="4", MAINTENANCE_MEDIA_DAILY_REQUEST_LIMIT="48",
                       MAINTENANCE_MEDIA_DAILY_RETAINED_BYTES="2097152",
                       MAINTENANCE_MEDIA_SOURCE_DAILY_PROBE_LIMIT="4",
                       MAINTENANCE_MEDIA_SOURCE_DAILY_REQUEST_LIMIT="48",
                       MAINTENANCE_MEDIA_SOURCE_DAILY_RETAINED_BYTES="2097152",
                       MAINTENANCE_MEDIA_SOURCE_INTERVAL_SECONDS="21600",
                       MAINTENANCE_MEDIA_HOST_INTERVAL_SECONDS="1800")
    datasource = "jdbc:h2:file:" + (work / "db").as_posix() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    base, csrf = "", {}
    checks, requests = [], []
    stalled, release = threading.Event(), threading.Event()
    packets = sample_ts()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            path = self.path.split("?", 1)[0]
            requests.append(path)
            if path == "/stall":
                stalled.set()
                release.wait(30)
                return
            if path == "/list":
                body = b"#EXTM3U\n#EXT-X-TARGETDURATION:6\n" + b"".join(
                    f"#EXTINF:6,\ns{index}\n".encode() for index in range(3)) + b"#EXT-X-ENDLIST\n"
            else:
                body = packets
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    fixture = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    fixture.daemon_threads = True
    threading.Thread(target=fixture.serve_forever, daemon=True).start()
    origin = f"http://127.0.0.1:{fixture.server_port}"
    alias = f"http://localhost:{fixture.server_port}"

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

    def source():
        return call("/sources", {"name": "budget file fixture", "filename": "source.py",
                                 "content": "class Spider:\n    pass\n"}, expected=201)

    def submit(source, url, expected=202):
        return call(f"/sources/{source['id']}/media-probes", {
            "expectedArtifactSha256": source["artifactSha256"],
            "player": {"parse": 0, "jx": 0, "url": url + "?" + marker,
                       "header": {"Cookie": marker}}}, expected=expected)

    def wait(run):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            value = call("/media-probes/" + run["id"])
            if value["status"] not in ("QUEUED", "RUNNING", "CANCELLING"):
                return value
            time.sleep(0.1)
        raise AssertionError("Task did not finish: " + run["id"])

    process = None
    try:
        process, base = start(1, previous)
        csrf = call("/csrf")
        original = source()
        old = wait(submit(original, "https://example.invalid/video"))
        assert old["result"]["verdict"] == "sample_blocked"
        checks.append("old_v12_media_record_seeded_without_invented_budget")
        stop(process)

        environment.update(MAINTENANCE_MEDIA_ENABLED="true", MAINTENANCE_MEDIA_ALLOW_HTTP="true",
                           MAINTENANCE_MEDIA_ALLOWED_ORIGINS=origin + "," + alias)
        process, base = start(2, jar)
        csrf = call("/csrf")
        migrated = call("/media-probes/" + old["id"])
        assert migrated["budget"] is None and migrated["issuedRequests"] == 0
        assert migrated["result"] == old["result"]
        assert call("/media-budget")["usedProbes"] == 0
        checks.append("v12_to_v13_preserves_old_result_with_unknown_reservation")
        hls = wait(submit(original, origin + "/list"))
        assert hls["result"]["verdict"] == "sample_checked" and hls["issuedRequests"] == 4
        assert hls["budget"]["reserved"] is True and hls["budget"]["refundable"] is False
        assert requests == ["/list", "/s0", "/s1", "/s2"]
        budget = call("/media-budget")
        assert (budget["usedProbes"], budget["reservedRequests"], budget["reservedBodyBytes"]) == (1, 12, 524288)
        assert call(f"/sources/{original['id']}/media-budget")["usedProbes"] == 1
        checks.append("real_hls_requests_use_one_persisted_nonrefundable_reservation")
        rejected = submit(original, origin + "/list", expected=429)
        assert rejected["code"] == "MEDIA_SOURCE_COOLDOWN" and call("/media-budget") == budget
        checks.append("source_cooldown_rejects_before_another_run_or_charge")
        another = source()
        host_blocked = wait(submit(another, origin + "/list"))
        assert host_blocked["result"]["verdict"] == "sample_blocked" and host_blocked["issuedRequests"] == 0
        assert host_blocked["result"]["nodes"][0]["reason"] == "MEDIA_HOST_COOLDOWN"
        assert len(requests) == 4 and call("/media-budget")["usedProbes"] == 2
        checks.append("another_source_cannot_bypass_host_cooldown")
        interrupted_source = source()
        interrupted = submit(interrupted_source, alias + "/stall")
        assert stalled.wait(5), call("/media-probes/" + interrupted["id"])
        active = call("/media-probes/" + interrupted["id"])
        assert active["status"] == "RUNNING" and active["issuedRequests"] == 1
        stop(process, force=True)
        release.set()
        request_count = len(requests)
        checks.append("request_permit_is_committed_before_strong_stop")

        process, base = start(3, jar)
        csrf = call("/csrf")
        recovered = call("/media-probes/" + interrupted["id"])
        assert recovered["status"] == "INTERRUPTED" and recovered["issuedRequests"] == 1
        assert recovered["budget"] == active["budget"]
        assert call("/media-budget")["usedProbes"] == 3 and len(requests) == request_count
        assert call("/media-probes/" + hls["id"])["result"] == hls["result"]
        checks.append("restart_retains_reservations_and_permits_without_replaying_input")
        fourth = source()
        after_restart = wait(submit(fourth, origin + "/list"))
        assert after_restart["result"]["nodes"][0]["reason"] == "MEDIA_HOST_COOLDOWN"
        assert after_restart["issuedRequests"] == 0 and len(requests) == request_count
        final_budget = call("/media-budget")
        assert (final_budget["usedProbes"], final_budget["reservedRequests"], final_budget["reservedBodyBytes"]) == (4, 48, 2097152)
        fifth = source()
        assert submit(fifth, origin + "/list", expected=429)["code"] == "MEDIA_GLOBAL_DAILY_PROBE_LIMIT"
        assert call("/media-budget") == final_budget
        checks.append("host_cooldown_survives_restart_and_daily_limit_rejects_without_charge")
        stop(process)

        environment["MAINTENANCE_MEDIA_HOST_INTERVAL_SECONDS"] = "3600"
        process, base = start(4, jar)
        csrf = call("/csrf")
        drifted = call("/media-probes/" + hls["id"])
        assert "media_budget_policy" in drifted["staleInputs"]
        assert drifted["budget"] == hls["budget"] and drifted["result"] == hls["result"]
        assert call("/media-budget") == final_budget and len(requests) == request_count
        demo = call("/media-probes/" + hls["id"] + "/export", binary=True)
        with zipfile.ZipFile(io.BytesIO(demo)) as archive:
            assert set(archive.namelist()) == {"manifest.json", "media-probe.json"}
            manifest = json.loads(archive.read("manifest.json"))
            assert manifest["sourceHealthEligible"] is False and manifest["publishEligible"] is False
            assert json.loads(archive.read("media-probe.json")) == drifted
            for name, item in manifest["files"].items():
                body = archive.read(name)
                assert hashlib.sha256(body).hexdigest() == item["sha256"] and len(body) == item["bytes"]
            for name in archive.namelist():
                body = archive.read(name)
                assert marker.encode() not in body and password.encode() not in body
                assert origin.encode() not in body and alias.encode() not in body
        checks.append("configuration_drift_preserves_frozen_reservations_and_redacted_export")
        if args.demo_out:
            assert not args.demo_out.exists()
            args.demo_out.parent.mkdir(parents=True, exist_ok=True)
            args.demo_out.write_bytes(demo)
        result = dict(schemaVersion=1, passed=len(checks), processes=4, checks=checks,
                      jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                      previousJarSha256=hashlib.sha256(previous.read_bytes()).hexdigest(),
                      finalBudget=final_budget, fixtureRequestCount=len(requests), logDirectory=str(work),
                      boundary="Trusted local HTTP and operator-supplied media input only; reserved response-body "
                               "capacity is not wire traffic. No real source, Linux isolation or playback evidence.")
        if args.json_out:
            args.json_out.parent.mkdir(parents=True, exist_ok=True)
            args.json_out.write_bytes((json.dumps(result, indent=2) + "\n").encode())
        print(json.dumps(result, indent=2))
    finally:
        release.set()
        if process is not None:
            stop(process)
        fixture.shutdown()
        fixture.server_close()


if __name__ == "__main__":
    main()
