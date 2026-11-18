"""Verify fresh JAR setup and one real file-database upgrade, using loopback fixtures only."""
from __future__ import annotations

import argparse
import base64
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import zipfile


def sha(body):
    return hashlib.sha256(body).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("java", "python", "jar", "previous-jar", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    java, python, jar, previous = (getattr(args, name).resolve() for name in ("java", "python", "jar", "previous_jar"))
    assert all(p.is_file() for p in (java, python, jar, previous)), "INSTALL_INPUT_MISSING"
    output = args.output.resolve()
    output.mkdir(exist_ok=False)
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().startswith(("MAINTENANCE_", "SPRING_"))}
    environment.update(MAINTENANCE_PYTHON=str(python), MAINTENANCE_DB_PASSWORD="",
                       MAINTENANCE_MODEL_ENABLED="false", MAINTENANCE_MODEL_AUTO_SUBMIT_ENABLED="false",
                       MAINTENANCE_SCHEDULING_ENABLED="false", MAINTENANCE_CONTAINER_ENABLED="false",
                       MAINTENANCE_MANAGED_SOURCE_ENABLED="false", MAINTENANCE_AT_AUTO_PUBLISH_ENABLED="false",
                       MAINTENANCE_MEDIA_ENABLED="false")
    checks, received, launches = [], [], []
    credential = "fixture-" + secrets.token_hex(12)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            received.append((self.command, self.path, self.headers.get("X-API-KEY") == credential))
            body = b"[]"
            self.send_response(200 if self.path == "/api/plugins" else 404)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            received.append((self.command, self.path, False))
            self.send_error(409)

        def log_message(self, *_):
            pass

    fixture = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=fixture.serve_forever, daemon=True)
    thread.start()
    origin = "http://127.0.0.1:" + str(fixture.server_port)

    @contextmanager
    def service(artifact, data, label):
        data.mkdir(exist_ok=True)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        base = "http://127.0.0.1:" + str(port)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        csrf = {}
        auth = "Basic " + base64.b64encode(b"admin:admin").decode()

        def request(path, body=None, method=None, raw=False):
            headers = {"Authorization": auth}
            if body is not None:
                if not csrf:
                    csrf.update(request("/api/plugin-maintenance/csrf"))
                headers.update({"Content-Type": "application/json", csrf["headerName"]: csrf["token"]})
            payload = None if body is None else json.dumps(body, ensure_ascii=True).encode()
            outgoing = urllib.request.Request(base + path, data=payload, headers=headers,
                                              method=method or ("GET" if body is None else "POST"))
            try:
                with opener.open(outgoing, timeout=20) as response:
                    content = response.read()
            except urllib.error.HTTPError as error:
                raise AssertionError("INSTALL_HTTP_" + str(error.code) + ": " + path) from None
            return content if raw else json.loads(content)

        database = "jdbc:h2:file:" + (data / "maintenance").as_posix() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
        command = [str(java), "-Dfile.encoding=UTF-8", "-jar", str(artifact), "--server.address=127.0.0.1",
                   "--server.port=" + str(port), "--spring.datasource.url=" + database,
                   "--maintenance.connections-file=" + (data / "connections.json").as_posix()]
        with (output / (label + ".log")).open("xb") as log:
            process = subprocess.Popen(command, cwd=output, env=environment, stdin=subprocess.DEVNULL,
                                       stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        launches.append({"label": label, "pid": process.pid, "jarSha256": sha(artifact.read_bytes()), "port": port})
        try:
            deadline = time.monotonic() + 90
            while True:
                assert process.poll() is None, "INSTALL_SERVER_EXITED: " + label
                try:
                    request("/api/plugin-maintenance/sources")
                    break
                except (urllib.error.URLError, TimeoutError, AssertionError):
                    assert time.monotonic() < deadline, "INSTALL_START_TIMEOUT: " + label
                    time.sleep(0.3)
            with zipfile.ZipFile(artifact) as archive:
                assert request("/", raw=True) == archive.read("BOOT-INF/classes/static/index.html"), "INSTALL_UI_MISMATCH"
                for name in archive.namelist():
                    if name.startswith("BOOT-INF/classes/static/assets/") and not name.endswith("/"):
                        # Only the candidate's current UI is checked; old hashed assets are not requested.
                        if artifact == jar:
                            assert request("/" + name.split("static/", 1)[1], raw=True) == archive.read(name)
            yield request
        finally:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(10)
            launches[-1]["stopped"] = process.poll() is not None

    source_content = "# \u65b0\u76ee\u5f55\u5b89\u88c5\u6837\u672c\r\nraise RuntimeError('must not execute')\r\n"
    prefix = "/api/plugin-maintenance"

    def configure(request):
        assert request(prefix + "/sources") == [], "NEW_DATABASE_NOT_EMPTY"
        view = request(prefix + "/settings/connections")
        assert not view["at"]["configured"] and not view["model"]["enabled"], "NEW_INSTALL_NOT_DISCONNECTED"
        request(prefix + "/settings/connections/at", {"origin": origin, "instanceId": "install-fixture",
                "revision": "fixture", "authMode": "api_key", "credential": credential,
                "clearCredential": False, "allowHttp": True}, method="PUT")
        request(prefix + "/settings/connections/model", {"enabled": True, "endpoint": "", "baseUrl": origin + "/v1",
                "model": "install-fixture", "apiKey": credential, "clearApiKey": False, "keyEnv": "",
                "timeoutMillis": 8000, "maxCompletionTokens": 2048, "maxTokenParameter": "max_tokens",
                "jsonMode": "none", "includeStoreParameter": False}, method="PUT")
        request(prefix + "/at/observations", {})
        source = request(prefix + "/sources", {"name": "\u5b89\u88c5\u9a8c\u8bc1", "filename": "install.py",
                         "content": source_content})
        assert source["artifactSha256"] == sha(source_content.encode()), "INSTALL_SOURCE_BYTES_CHANGED"
        view = request(prefix + "/settings/connections")
        assert credential not in json.dumps(view), "INSTALL_SECRET_ECHOED"
        assert view["model"]["enabled"] and view["at"]["credentialConfigured"], "INSTALL_CONNECTION_NOT_SAVED"
        assert request(prefix + "/sources/" + source["id"] + "/probe-runs") == [], "INSTALL_SOURCE_EXECUTED"
        return source

    try:
        with service(jar, output / "fresh-data", "fresh-current") as request:
            configure(request)
            assert request(prefix + "/automation/tasks") == []
            assert not request(prefix + "/automation/publication-policy")["enabled"]
            assert request(prefix + "/managed-source-runtime")["blockedReason"], "EXECUTOR_UNEXPECTEDLY_ENABLED"
            checks.extend(["fresh_current_jar_boot_and_ui", "new_data_disconnected_by_default",
                           "at_and_relay_settings_via_platform", "source_bytes_saved_without_execution",
                           "execution_and_automatic_publication_not_silently_enabled"])
        upgrade_data = output / "upgrade-data"
        with service(previous, upgrade_data, "upgrade-old") as request:
            old_source = configure(request)
        saved_settings = (upgrade_data / "connections.json").read_bytes()
        with service(jar, upgrade_data, "upgrade-current") as request:
            source = request(prefix + "/sources/" + old_source["id"])
            assert source["artifactSha256"] == old_source["artifactSha256"] and source["filename"] == "install.py"
            view = request(prefix + "/settings/connections")
            assert view["model"]["model"] == "install-fixture" and view["model"]["apiKeyConfigured"]
            assert view["model"]["maxTokenParameter"] == "max_tokens" and view["model"]["jsonMode"] == "none"
            assert view["at"]["instanceId"] == "install-fixture" and view["at"]["credentialConfigured"]
            assert credential not in json.dumps(view)
            request(prefix + "/at/observations", {})
            assert request(prefix + "/automation/tasks") == []
            assert (upgrade_data / "connections.json").read_bytes() == saved_settings, "UPGRADE_SETTINGS_REWRITTEN"
            checks.extend(["old_database_upgraded_by_current_jar", "source_identity_survived_upgrade",
                           "at_and_model_credentials_and_dialect_preserved", "connections_file_byte_preserved",
                           "new_automation_storage_available_after_upgrade"])
        assert received and all(method == "GET" and path == "/api/plugins" and authenticated
                                for method, path, authenticated in received), "UNEXPECTED_FIXTURE_REQUEST"
        result = {"completed": True, "checks": checks, "launches": launches,
                  "jarSha256": sha(jar.read_bytes()), "previousJarSha256": sha(previous.read_bytes()),
                  "loopbackAtGets": len(received), "modelRequests": 0, "sourceExecutions": 0,
                  "productionAccess": False, "allOwnedServersStopped": all(row["stopped"] for row in launches),
                  "scope": "JAR installation/configuration and this old-version upgrade only; not live source repair"}
        (output / "result.json").write_text(json.dumps(result, ensure_ascii=True, indent=2), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=True), flush=True)
    finally:
        fixture.shutdown()
        fixture.server_close()
        thread.join(5)


if __name__ == "__main__":
    main()
