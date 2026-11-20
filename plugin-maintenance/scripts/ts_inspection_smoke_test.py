"""Verify V17 H2 media evidence survives 0.18->0.19 PSI/PES inspection upgrade."""
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

from ts_fixture import mpeg2_crc32, sample_ts


PACKET_BYTES = 188


def _check_fixture(data: bytes) -> None:
    assert len(data) == 3 * PACKET_BYTES
    pat = data[5:21]
    pmt = data[PACKET_BYTES + 5:PACKET_BYTES + 26]
    assert pat[:3] == b"\x00\xb0\x0d" and mpeg2_crc32(pat) == 0
    assert pmt[:3] == b"\x02\xb0\x12" and mpeg2_crc32(pmt) == 0
    assert pmt[12:17] == b"\x1b\xe1\x01\xf0\x00"
    pes = data[2 * PACKET_BYTES + 174:2 * PACKET_BYTES + 188]
    assert pes[:9] == b"\x00\x00\x01\xe0\x00\x08\x80\x00\x00"
    assert pes[9:14] == b"\x00\x00\x01\x65\x88"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("java", "python", "jar", "previous-jar"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--demo-out", type=Path)
    args = parser.parse_args()
    for output_path in (args.json_out, args.demo_out):
        if output_path is not None and output_path.exists():
            raise FileExistsError(output_path)
    java, python = shutil.which(args.java), shutil.which(args.python)
    if not java or not python:
        parser.error("Java and Python must exist")
    java, python = str(Path(java).resolve()), str(Path(python).resolve())
    jar, previous = Path(args.jar).resolve(), Path(args.previous_jar).resolve()
    if hashlib.sha256(jar.read_bytes()).digest() == hashlib.sha256(previous.read_bytes()).digest():
        raise AssertionError("The new and previous JARs must differ")
    for artifact in (jar, previous):
        with zipfile.ZipFile(artifact) as archive:
            names = set(archive.namelist())
            assert "BOOT-INF/classes/db/migration/h2/V17__SourceArchiveImports.sql" in names
            migrations = {
                int(match[1])
                for name in names
                if (match := re.fullmatch(r"BOOT-INF/classes/db/migration/h2/V(\d+)__.*\.sql", name))
            }
            assert migrations == set(range(1, 18)), migrations

    fixture = sample_ts()
    _check_fixture(fixture)
    fake_ts = b"".join(bytes((0x47, 0, 0, 0x10)) + bytes(184) for _ in range(20))
    incomplete_ts = fixture[2 * PACKET_BYTES:] * 400

    work = Path(tempfile.mkdtemp(prefix="at-ts-inspection-smoke-"))
    password = secrets.token_urlsafe(24)
    credential = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()
    marker = "ts-input-secret-" + secrets.token_hex(12)
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().startswith(("MAINTENANCE_", "SPRING_"))}
    environment.update(
        SPRING_SECURITY_USER_NAME="admin",
        SPRING_SECURITY_USER_PASSWORD=password,
        MAINTENANCE_PYTHON=python,
        MAINTENANCE_DB_PASSWORD="",
        MAINTENANCE_AT_ORIGIN="",
        MAINTENANCE_AT_SESSION_TOKEN="",
        MAINTENANCE_SCHEDULING_ENABLED="false",
        MAINTENANCE_CONTAINER_ENABLED="false",
        MAINTENANCE_MODEL_ENABLED="false",
        MAINTENANCE_MEDIA_ENABLED="true",
        MAINTENANCE_MEDIA_ALLOW_HTTP="true",
        MAINTENANCE_MEDIA_ALLOWED_ORIGINS="",
    )
    datasource = "jdbc:h2:file:" + (work / "db").as_posix() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                         urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    base, csrf = "", {}
    requests: list[tuple[str, str]] = []
    checks: list[str] = []
    process = None
    fixture_servers = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            path = self.path.split("?", 1)[0]
            requests.append((self.server.fixture_label, path))
            if path.endswith("/list"):
                body = ("#EXTM3U\n#EXT-X-TARGETDURATION:6\n"
                        "#EXTINF:6,\ns0\n#EXTINF:6,\ns1\n#EXTINF:6,\ns2\n#EXT-X-ENDLIST\n").encode()
            elif path.endswith("/s0") or path.endswith("/s1") or path.endswith("/s2"):
                if path.startswith("/valid/"):
                    body = fixture
                elif path.startswith("/legacy/") or path.startswith("/fake/"):
                    body = fake_ts
                elif path.startswith("/incomplete/"):
                    body = incomplete_ts[:65536]
                    self.send_response(206)
                    self.send_header("Content-Range", f"bytes 0-{len(body) - 1}/{len(incomplete_ts)}")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                else:
                    self.send_error(404)
                    return
            else:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    origins = []
    for host, label in ((10, "legacy"), (11, "fake"), (12, "valid"), (13, "incomplete")):
        server = ThreadingHTTPServer((f"127.0.0.{host}", 0), Handler)
        server.daemon_threads = True
        server.fixture_label = label
        fixture_servers.append(server)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        origins.append(f"http://127.0.0.{host}:{server.server_port}")
    legacy_origin, *new_origins = origins
    environment["MAINTENANCE_MEDIA_ALLOWED_ORIGINS"] = ",".join(origins)

    def stop(current, force=False):
        if current is not None and current.poll() is None:
            (current.kill if force else current.terminate)()
            try:
                current.wait(timeout=15)
            except subprocess.TimeoutExpired:
                current.kill()
                current.wait(timeout=5)

    def start(index: int, artifact: Path):
        log = work / f"server-{index}.log"
        with log.open("wb") as output:
            current = subprocess.Popen(
                [java, "-jar", str(artifact), "--server.port=0", "--server.address=127.0.0.1",
                 "--spring.datasource.url=" + datasource],
                cwd=work, env=environment, stdout=output, stderr=subprocess.STDOUT,
            )
        deadline = time.monotonic() + 60
        try:
            while time.monotonic() < deadline:
                if current.poll() is not None:
                    raise RuntimeError("Server exited: " + str(log))
                match = re.search(r"Tomcat started on port (\d+)",
                                  log.read_text(encoding="utf-8", errors="replace"))
                if match:
                    return current, f"http://127.0.0.1:{match[1]}"
                time.sleep(0.2)
            raise RuntimeError("Startup timeout: " + str(log))
        except (RuntimeError, OSError):
            stop(current)
            raise

    def call(path: str, body=None, expected=200, binary=False):
        headers = {"Authorization": credential}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers.update({"Content-Type": "application/json",
                            csrf["headerName"]: csrf["token"]})
        request = urllib.request.Request(base + "/api/plugin-maintenance" + path,
                                         data=data, headers=headers)
        try:
            response = opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            with error:
                payload = error.read()
                assert error.code == expected, (error.code, expected, payload)
                return json.loads(payload)
        with response:
            assert response.status == expected, (response.status, expected)
            payload = response.read()
            return payload if binary else json.loads(payload)

    def wait(run_id: str):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            value = call("/media-probes/" + run_id)
            if value["status"] not in ("QUEUED", "RUNNING", "CANCELLING"):
                return value
            time.sleep(0.1)
        raise AssertionError("Media probe did not finish: " + run_id)

    def source(name: str):
        return call("/sources", {"name": name, "filename": "source.py",
                                 "content": "class Spider:\n    pass\n"}, expected=201)

    def submit(source_record, origin: str, path: str):
        return call(f"/sources/{source_record['id']}/media-probes", {
            "expectedArtifactSha256": source_record["artifactSha256"],
            "player": {"parse": 0, "jx": 0, "playUrl": "",
                       "url": origin + path + "?" + marker,
                       "header": {"Cookie": marker}},
        }, expected=202)

    def node(run, ident: str):
        return next(item for item in run["result"]["nodes"] if item["id"] == ident)

    def verify_export(run):
        body = call(f"/media-probes/{run['id']}/export", binary=True)
        with zipfile.ZipFile(io.BytesIO(body)) as archive:
            assert set(archive.namelist()) == {"manifest.json", "media-probe.json"}
            manifest = json.loads(archive.read("manifest.json"))
            assert manifest["sourceHealthEligible"] is False and manifest["publishEligible"] is False
            assert json.loads(archive.read("media-probe.json")) == run
            for name, entry in manifest["files"].items():
                content = archive.read(name)
                assert hashlib.sha256(content).hexdigest() == entry["sha256"]
                assert len(content) == entry["bytes"]
            for name in archive.namelist():
                content = archive.read(name)
                assert marker.encode() not in content and password.encode() not in content
                assert legacy_origin.encode() not in content and all(
                    origin.encode() not in content for origin in new_origins)
        return body

    try:
        process, base = start(1, previous)
        csrf = call("/csrf")
        legacy_source = source("0.18 legacy TS fixture")
        legacy = wait(submit(legacy_source, legacy_origin, "/legacy/list")["id"])
        assert legacy["status"] == "COMPLETED"
        assert node(legacy, "M05")["status"] == "pass", node(legacy, "M05")
        assert requests == [("legacy", "/legacy/list"), ("legacy", "/legacy/s0"),
                            ("legacy", "/legacy/s1"), ("legacy", "/legacy/s2")]
        legacy_result = legacy["result"]
        stop(process)
        process = None
        checks.append("v18_v17_file_database_seeded_with_legacy_sync_only_m05_pass")

        process, base = start(2, jar)
        csrf = call("/csrf")
        upgraded = call("/media-probes/" + legacy["id"])
        assert upgraded["result"] == legacy_result
        assert "media_policy" in upgraded["staleInputs"]
        assert upgraded["policy"] == legacy["policy"] and upgraded["budget"] == legacy["budget"]
        assert upgraded["issuedRequests"] == legacy["issuedRequests"] == 4
        assert len(requests) == 4
        checks.append("v19_keeps_old_report_json_and_marks_policy_stale_without_v18_migration")

        fake_source = source("0.19 invalid sync-only TS fixture")
        fake = wait(submit(fake_source, new_origins[0], "/fake/list")["id"])
        assert fake["status"] == "COMPLETED" and node(fake, "M05")["status"] == "fail"
        assert fake["result"]["verdict"] == "sample_failed"
        assert fake["issuedRequests"] == 2
        assert not any("transport" in sample for sample in node(fake, "M05").get("samples", []))
        checks.append("v19_rejects_sync_bytes_without_pat_pmt_crc_and_pes")

        valid_source = source("0.19 PAT PMT PES fixture")
        valid = wait(submit(valid_source, new_origins[1], "/valid/list")["id"])
        valid_node = node(valid, "M05")
        assert valid["status"] == "COMPLETED" and valid_node["status"] == "pass", valid_node
        samples = valid_node["samples"]
        assert len(samples) == 3
        assert all(sample["transport"]["psiCrcChecked"] is True for sample in samples)
        assert all(sample["transport"]["packetBytes"] == PACKET_BYTES for sample in samples)
        assert all(sample["transport"]["mediaStreamsWithPesPrefix"] == 1 for sample in samples)
        assert all(any(stream["streamType"] == 27 and stream["pesPrefixObserved"]
                       for stream in sample["transport"]["streams"]) for sample in samples)
        assert all(sample["transport"]["codecDecoded"] is False for sample in samples)
        assert valid["issuedRequests"] == 4
        assert valid["policySha256"] != legacy["policySha256"]
        assert valid["policy"]["allowedOriginsSha256"] == legacy["policy"]["allowedOriginsSha256"]
        demo = verify_export(valid)
        checks.append("v19_accepts_crc_valid_pat_pmt_h264_pes_and_exports_transport_evidence")

        incomplete_source = source("0.19 truncated missing-PSI TS fixture")
        incomplete = wait(submit(incomplete_source, new_origins[2], "/incomplete/list")["id"])
        assert incomplete["status"] == "COMPLETED", incomplete
        assert node(incomplete, "M05")["status"] == "not_tested", node(incomplete, "M05")
        assert node(incomplete, "M05")["reason"] == "MEDIA_TS_PREFIX_INCOMPLETE"
        assert incomplete["result"]["verdict"] == "sample_incomplete"
        assert incomplete["issuedRequests"] == 2
        checks.append("v19_keeps_range_prefix_without_psi_incomplete_instead_of_passing")

        assert requests == [
            ("legacy", "/legacy/list"), ("legacy", "/legacy/s0"),
            ("legacy", "/legacy/s1"), ("legacy", "/legacy/s2"),
            ("fake", "/fake/list"), ("fake", "/fake/s0"),
            ("valid", "/valid/list"), ("valid", "/valid/s0"),
            ("valid", "/valid/s1"), ("valid", "/valid/s2"),
            ("incomplete", "/incomplete/list"), ("incomplete", "/incomplete/s0"),
        ], requests
        request_count = len(requests)
        saved_budget = call("/media-budget")
        assert saved_budget["usedProbes"] == 4
        old_result_after_runs = call("/media-probes/" + legacy["id"])
        assert old_result_after_runs["result"] == legacy_result
        stop(process)
        process = None

        process, base = start(3, jar)
        csrf = call("/csrf")
        assert call("/media-probes/" + legacy["id"])["result"] == legacy_result
        assert call("/media-probes/" + valid["id"])["result"] == valid["result"]
        assert call("/media-probes/" + fake["id"])["result"] == fake["result"]
        assert call("/media-probes/" + incomplete["id"])["result"] == incomplete["result"]
        assert call("/media-budget") == saved_budget
        assert len(requests) == request_count
        checks.append("v19_restart_retains_old_and_new_transport_evidence_without_network_replay")

        if args.demo_out:
            if args.demo_out.exists():
                raise FileExistsError(args.demo_out)
            args.demo_out.parent.mkdir(parents=True, exist_ok=True)
            args.demo_out.write_bytes(demo)
        result = {
            "schemaVersion": 1,
            "migration": "V17->V17",
            "processes": 3,
            "passed": len(checks),
            "checks": checks,
            "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "previousJarSha256": hashlib.sha256(previous.read_bytes()).hexdigest(),
            "fixtureSha256": hashlib.sha256(fixture).hexdigest(),
            "demoSha256": hashlib.sha256(demo).hexdigest(),
            "demoBytes": len(demo),
            "loopbackHosts": ["127.0.0.10", "127.0.0.11", "127.0.0.12", "127.0.0.13"],
            "fixtureRequestCount": len(requests),
            "fixtureRequests": [{"fixture": label, "path": path} for label, path in requests],
            "budgetIntervalsOverridden": False,
            "logDirectory": str(work),
            "boundary": "Local file-H2 JVMs, loopback HTTP and synthetic MPEG-TS bytes only; "
                        "no remote site, production AT, real provider, decoded video or client playback.",
        }
        if args.json_out:
            args.json_out.parent.mkdir(parents=True, exist_ok=True)
            args.json_out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, indent=2))
    finally:
        stop(process)
        for server in fixture_servers:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
