"""Verify 0.20->0.21 AES media evidence and budgets across three local file-H2 JVMs."""
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

from Crypto.Cipher import AES
from Crypto.Util.Padding import pad
from ts_fixture import sample_ts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("java", "python", "jar", "previous-jar"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--demo-out", type=Path)
    args = parser.parse_args()
    for output in (args.json_out, args.demo_out):
        if output is not None and output.exists():
            raise FileExistsError(output)
    java, python = shutil.which(args.java), shutil.which(args.python)
    if not java or not python:
        parser.error("Java and Python must exist")
    java, python = str(Path(java).resolve()), str(Path(python).resolve())
    jar, previous = Path(args.jar).resolve(), Path(args.previous_jar).resolve()
    for artifact in (jar, previous):
        with zipfile.ZipFile(artifact) as archive:
            versions = {int(match[1]) for name in archive.namelist()
                        if (match := re.fullmatch(r"BOOT-INF/classes/db/migration/h2/V(\d+)__.*\.sql", name))}
            assert versions == set(range(1, 19)), versions

    work = Path(tempfile.mkdtemp(prefix="at-hls-aes-smoke-"))
    password, marker = secrets.token_urlsafe(24), "aes-input-secret-" + secrets.token_hex(12)
    credential = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()
    key1, key2 = b"HLS-fixture-key1", b"HLS-fixture-key2"
    fixture = sample_ts()
    null_packet = bytes((0x47, 0x1f, 0xff, 0x10)) + bytes(184)
    large = fixture + null_packet * 500
    incomplete = null_packet * 500

    def encrypt(body, key=key1, iv=0):
        return AES.new(key, AES.MODE_CBC, iv.to_bytes(16, "big")).encrypt(pad(body, 16))

    encrypted = {
        "/valid/s0": encrypt(fixture, iv=3),
        "/valid/s1": encrypt(fixture, key2, 4),
        "/prefix/s0": encrypt(large),
        "/incomplete/s0": encrypt(incomplete),
    }
    requests, checks, fixture_servers = [], [], []
    key_count = 0
    process = None

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            nonlocal key_count
            path = self.path.split("?", 1)[0]
            requests.append((self.server.fixture_label, path))
            if path.endswith("/list"):
                body = "#EXTM3U\n#EXT-X-VERSION:2\n#EXT-X-TARGETDURATION:6\n"
                if path.startswith("/valid/"):
                    body += ('#EXT-X-KEY:METHOD=AES-128,URI="key,private",IV=0x3\n#EXTINF:6,\ns0\n'
                             '#EXT-X-KEY:METHOD=AES-128,URI="key,private",IV=0x4\n#EXTINF:6,\ns1\n'
                             '#EXT-X-KEY:METHOD=NONE\n#EXTINF:6,\ns2\n')
                else:
                    body += '#EXT-X-KEY:METHOD=AES-128,URI="key,private"\n#EXTINF:6,\ns0\n'
                body = (body + "#EXT-X-ENDLIST\n").encode()
            elif path.endswith("/key,private"):
                assert self.headers.get("Range") is None
                if path.startswith("/valid/"):
                    key_count += 1
                    body = key1 if key_count == 1 else key2
                elif path.startswith("/badkey/"):
                    body = key1 + b"!"
                else:
                    body = key1
            elif path == "/valid/s2":
                body = fixture
            elif path in encrypted:
                body = encrypted[path]
                if path.startswith(("/prefix/", "/incomplete/")):
                    assert self.headers.get("Range") == "bytes=0-65535"
                    total = len(body)
                    body = body[:65536]
                    self.send_response(206)
                    self.send_header("Content-Range", f"bytes 0-{len(body) - 1}/{total}")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
            else:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    origins = []
    for host, label in enumerate(("legacy", "valid", "badkey", "prefix", "incomplete"), 20):
        server = ThreadingHTTPServer((f"127.0.0.{host}", 0), Handler)
        server.daemon_threads = True
        server.fixture_label = label
        fixture_servers.append(server)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        origins.append(f"http://127.0.0.{host}:{server.server_port}")
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().startswith(("MAINTENANCE_", "SPRING_"))}
    environment.update(
        SPRING_SECURITY_USER_NAME="admin", SPRING_SECURITY_USER_PASSWORD=password,
        MAINTENANCE_PYTHON=python, MAINTENANCE_DB_PASSWORD="", MAINTENANCE_AT_ORIGIN="",
        MAINTENANCE_AT_SESSION_TOKEN="", MAINTENANCE_SCHEDULING_ENABLED="false",
        MAINTENANCE_CONTAINER_ENABLED="false", MAINTENANCE_MODEL_ENABLED="false",
        MAINTENANCE_MEDIA_ENABLED="true", MAINTENANCE_MEDIA_ALLOW_HTTP="true",
        MAINTENANCE_MEDIA_ALLOWED_ORIGINS=",".join(origins),
    )
    datasource = "jdbc:h2:file:" + (work / "db").as_posix() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                         urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    base, csrf = "", {}

    def stop(current):
        if current is not None and current.poll() is None:
            current.terminate()
            try:
                current.wait(timeout=15)
            except subprocess.TimeoutExpired:
                current.kill()
                current.wait(timeout=5)

    def start(index, artifact):
        log = work / f"server-{index}.log"
        with log.open("wb") as output:
            current = subprocess.Popen(
                [java, "-jar", str(artifact), "--server.port=0", "--server.address=127.0.0.1",
                 "--spring.datasource.url=" + datasource],
                cwd=work, env=environment, stdout=output, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                if current.poll() is not None:
                    raise RuntimeError("Server exited: " + str(log))
                match = re.search(r"Tomcat started on port (\d+)", log.read_text(encoding="utf-8", errors="replace"))
                if match:
                    return current, f"http://127.0.0.1:{match[1]}"
                time.sleep(0.2)
            raise RuntimeError("Startup timeout: " + str(log))
        except (RuntimeError, OSError):
            stop(current)
            raise

    def call(path, body=None, expected=200, binary=False):
        headers, data = {"Authorization": credential}, None
        if body is not None:
            data = json.dumps(body).encode()
            headers.update({"Content-Type": "application/json", csrf["headerName"]: csrf["token"]})
        request = urllib.request.Request(base + "/api/plugin-maintenance" + path, data=data, headers=headers)
        try:
            response = opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            with error:
                payload = error.read()
                assert error.code == expected, (error.code, expected, payload)
                return json.loads(payload)
        with response:
            assert response.status == expected
            payload = response.read()
            return payload if binary else json.loads(payload)

    def submit(record, origin, path, expected=202):
        return call(f"/sources/{record['id']}/media-probes", {
            "expectedArtifactSha256": record["artifactSha256"],
            "player": {"parse": 0, "url": origin + path + "?" + marker, "header": {"Cookie": marker}},
        }, expected=expected)

    def wait(run_id):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            run = call("/media-probes/" + run_id)
            if run["status"] not in ("QUEUED", "RUNNING", "CANCELLING"):
                assert run["status"] == "COMPLETED", run
                return run
            time.sleep(0.1)
        raise AssertionError("Unfinished media task: " + run_id)

    def create_source(label):
        return call("/sources", {"name": label, "filename": "source.py",
                                 "content": "class Spider:\n    pass\n"}, expected=201)

    def node(run, ident):
        return next(item for item in run["result"]["nodes"] if item["id"] == ident)

    def exported(run):
        body = call(f"/media-probes/{run['id']}/export", binary=True)
        with zipfile.ZipFile(io.BytesIO(body)) as archive:
            assert set(archive.namelist()) == {"manifest.json", "media-probe.json"}
            manifest = json.loads(archive.read("manifest.json"))
            assert not manifest["sourceHealthEligible"] and not manifest["publishEligible"]
            assert json.loads(archive.read("media-probe.json")) == run
            for name, entry in manifest["files"].items():
                value = archive.read(name)
                assert len(value) == entry["bytes"] and hashlib.sha256(value).hexdigest() == entry["sha256"]
            for name in archive.namelist():
                value = archive.read(name)
                sensitive = [marker.encode(), password.encode(), b"key,private"]
                sensitive.extend(origin.encode() for origin in origins)
                for key in (key1, key2):
                    sensitive.extend((key, key.hex().encode(), base64.b64encode(key), hashlib.sha256(key).hexdigest().encode()))
                assert all(secret not in value for secret in sensitive)
        return body

    try:
        process, base = start(1, previous)
        csrf = call("/csrf")
        legacy = wait(submit(create_source("0.20 encrypted fixture"), origins[0], "/legacy/list")["id"])
        assert node(legacy, "M04")["status"] == "blocked" and legacy["issuedRequests"] == 1
        legacy_result = legacy["result"]
        stop(process)
        process = None
        checks.append("v20_file_h2_seed_preserves_unsupported_encryption_evidence")

        process, base = start(2, jar)
        csrf = call("/csrf")
        old = call("/media-probes/" + legacy["id"])
        assert old["result"] == legacy_result and old["policy"] == legacy["policy"] and old["budget"] == legacy["budget"]
        assert "media_policy" in old["staleInputs"] and len(requests) == 1
        checks.append("v21_preserves_old_result_and_budget_marks_policy_stale_without_replay")
        runs = []
        for index, label in enumerate(("valid", "badkey", "prefix", "incomplete"), 1):
            record = create_source("0.21 AES " + label)
            run = wait(submit(record, origins[index], "/" + label + "/list")["id"])
            runs.append(run)
            assert not run["result"]["sourceHealthEligible"] and not run["result"]["publishEligible"]
            if label == "valid":
                assert node(run, "M04")["status"] == node(run, "M05")["status"] == "pass"
                samples = node(run, "M05")["samples"]
                assert len(samples) == 3 and key_count == 2
                assert samples[0]["encryption"]["keyResourceId"] != samples[1]["encryption"]["keyResourceId"]
                assert all(sample["encryption"]["paddingChecked"] for sample in samples[:2])
                assert "encryption" not in samples[2]
                assert all(sample["transport"]["psiCrcChecked"] and not sample["transport"]["codecDecoded"]
                           for sample in samples)
                assert run["issuedRequests"] == 6
                assert run["policy"]["engineRevision"] == "bounded-media-v3-aes"
                assert len(run["policy"]["engineClasses"]) == 22
                demo = exported(run)
                before = len(requests)
                submit(record, origins[index], "/" + label + "/list", expected=429)
                assert len(requests) == before
            elif label == "badkey":
                assert node(run, "M04")["reason"] == "MEDIA_HLS_KEY_RESPONSE_INVALID"
                assert node(run, "M05")["status"] == "not_tested" and run["issuedRequests"] == 2
            elif label == "prefix":
                sample = node(run, "M05")["samples"][0]
                assert node(run, "M05")["status"] == "pass"
                assert sample["encryption"]["prefixOnly"] and not sample["encryption"]["paddingChecked"]
                assert not sample["transport"]["codecDecoded"] and run["issuedRequests"] == 3
            else:
                assert node(run, "M05")["reason"] == "MEDIA_TS_PREFIX_INCOMPLETE"
                assert run["result"]["verdict"] == "sample_incomplete" and run["issuedRequests"] == 3
            key_resources = [item for item in run["result"]["resources"] if item["role"] == "key"]
            assert key_resources and all(item["retainedBytes"] <= 17 and "bodySha256" not in item
                                         and item["bodyIdentity"] == "omitted_sensitive_key" for item in key_resources)
            exported(run)
            checks.append("v21_" + label + "_evidence_and_redacted_export")
        assert len(requests) == 15
        budget = call("/media-budget")
        assert budget["usedProbes"] == 5
        stop(process)
        process = None

        process, base = start(3, jar)
        csrf = call("/csrf")
        assert call("/media-probes/" + legacy["id"])["result"] == legacy_result
        for run in runs:
            restored = call("/media-probes/" + run["id"])
            assert restored["result"] == run["result"] and restored["budget"] == run["budget"]
            assert restored["issuedRequests"] == run["issuedRequests"]
        assert call("/media-budget") == budget and len(requests) == 15
        checks.append("v21_restart_keeps_all_reports_and_budgets_without_network_replay")
        if args.demo_out:
            args.demo_out.parent.mkdir(parents=True, exist_ok=True)
            with args.demo_out.open("xb") as output:
                output.write(demo)
        result = dict(
            schemaVersion=1, migration="V18->V18", processes=3, passed=len(checks), checks=checks,
            jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
            previousJarSha256=hashlib.sha256(previous.read_bytes()).hexdigest(),
            demoSha256=hashlib.sha256(demo).hexdigest(), demoBytes=len(demo),
            loopbackHosts=[f"127.0.0.{index}" for index in range(20, 25)],
            fixtureRequestCount=len(requests),
            fixtureRequests=[dict(fixture=label, path=path.replace("key,private", "key-redacted")) for label, path in requests],
            budgetIntervalsOverridden=False, logDirectory=str(work),
            boundary="Local file-H2 JVMs, authored AES ciphertext and synthetic PSI/PES only; no remote site, "
                     "production AT, real provider, unknown crawler, media decoding or client playback.")
        if args.json_out:
            args.json_out.parent.mkdir(parents=True, exist_ok=True)
            with args.json_out.open("x", encoding="utf-8") as output:
                output.write(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))
    finally:
        stop(process)
        for server in fixture_servers:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
