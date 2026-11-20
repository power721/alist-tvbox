"""Same-host T4 setup and socket relay; never execute source in the workbench process."""
from __future__ import annotations

import argparse
import base64
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import socketserver
import subprocess
import sys
import threading
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))
import managed_source_setup as S
import managed_runner_tunnel as T

MAX_REQUEST = 1_500_000


class RelayHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(130)
        with self.request.makefile("rb") as source:
            frame = T.read_frame(source, MAX_REQUEST)
        # Forward exactly once. A lost reply is resolved by the broker's durable job journal.
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as peer:
            peer.settimeout(8)
            peer.connect(str(self.server.target))
            peer.sendall(frame)
            with peer.makefile("rb") as source:
                reply = T.read_frame(source, T.MAX_REPLY)
        self.request.sendall(reply)


class RelayServer(socketserver.ThreadingMixIn, getattr(socketserver, "UnixStreamServer", socketserver.BaseServer)):
    daemon_threads = True

    def handle_error(self, request, client_address):
        print("LOCAL_T4_RELAY_REQUEST_FAILED", file=sys.stderr, flush=True)


class LocalTransport:
    def __init__(self, root, uid):
        self.root = Path(root).resolve()
        self.api = self.root / "api"
        self.uid = uid
        self.relays = {}

    def config(self, path):
        path = Path(path)
        S.require(path == self.root / "channels.json" and path.stat().st_size <= 65536,
                  "SOURCE_SETUP_CONFIGURATION_INVALID")
        value = T.decode(path.read_bytes())
        S.require(isinstance(value, dict) and set(value) == {"schemaVersion", "runners"}
                  and value["schemaVersion"] == 1 and isinstance(value["runners"], list)
                  and 0 < len(value["runners"]) <= 64, "SOURCE_SETUP_CONFIGURATION_INVALID")
        for route in value["runners"]:
            S.require(re.fullmatch(S.UUID, route.get("instanceId", ""))
                      and re.fullmatch("sha256:" + S.SHA, route.get("imageId", ""))
                      and re.fullmatch(S.SHA, route.get("bundleSha256", "")),
                      "SOURCE_SETUP_CONFIGURATION_INVALID")
            target = Path(route["remoteSocket"])
            S.require(target.name == "executor.sock" and target.is_relative_to(self.root)
                      and target.resolve() == target, "SOURCE_SETUP_CONFIGURATION_INVALID")
        return SimpleNamespace(document=value, routes=value["runners"])

    def install(self, config, plan, code=S.REMOTE):
        service = Path(__file__).with_name("managed_broker_service.py").read_bytes()
        payload = {**plan, "useTemplateHttp": True, "serviceBase64": base64.b64encode(service).decode(),
                   "serviceSha256": hashlib.sha256(service).hexdigest()}
        for key in ("templateSocket",):
            if key in plan:
                S.require(Path(plan[key]).is_relative_to(self.root), "SOURCE_SETUP_CONFIGURATION_INVALID")
        if "previousTunnel" in plan:
            S.require(Path(plan["previousTunnel"]["remoteSocket"]).is_relative_to(self.root),
                      "SOURCE_SETUP_CONFIGURATION_INVALID")
        completed = subprocess.run([sys.executable, "-I", "-c", code],
                                   input=json.dumps(payload, ensure_ascii=True).encode(),
                                   capture_output=True, timeout=110, check=False)
        S.require(completed.returncode == 0 and len(completed.stdout) <= 65536,
                  "SOURCE_SETUP_REMOTE_FAILED")
        result = T.decode(completed.stdout)
        S.require(result.get("bundleSha256") == plan["bundleSha256"]
                  and result.get("instanceId") == plan["instanceId"]
                  and result.get("sourceExecuted") is False, "SOURCE_SETUP_REMOTE_IDENTITY_CHANGED")
        if result.get("ok") is not True:
            code = result.get("code", "")
            raise S.Failure(code if re.fullmatch(r"SOURCE_SETUP_[A-Z_]{1,80}", code)
                            else "SOURCE_SETUP_REMOTE_FAILED")
        return result

    def endpoint(self, route):
        S.require(re.fullmatch(S.UUID, route["instanceId"]), "SOURCE_SETUP_CONFIGURATION_INVALID")
        return str(self.api / ("w-" + route["instanceId"].replace("-", "")[:12]) / "executor.sock")

    def wait(self, route):
        target = Path(route["remoteSocket"])
        S.require(target.resolve() == target and target.is_relative_to(self.root)
                  and target.name == "executor.sock", "SOURCE_SETUP_CONFIGURATION_INVALID")
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as peer:
            peer.settimeout(8)
            peer.connect(str(target))
            peer.sendall(T.frame(b'{"schemaVersion":1,"operation":"identity"}'))
            with peer.makefile("rb") as stream:
                observed = T.identity_reply(T.read_frame(stream, T.MAX_REPLY), route)
        S.require(observed["ready"], "SOURCE_SETUP_CHANNEL_NOT_READY")
        endpoint = Path(self.endpoint(route))
        if str(endpoint) in self.relays:
            S.require(self.relays[str(endpoint)].target == target, "SOURCE_SETUP_CONFIGURATION_CHANGED")
            return
        endpoint.parent.mkdir(mode=0o755, exist_ok=True)
        endpoint.parent.chmod(0o755)
        S.require(len(str(endpoint).encode()) <= 100, "SOURCE_SETUP_CONFIGURATION_INVALID")
        # This directory is root-owned and contains only internal IPC, never app data or credentials.
        if endpoint.exists():
            S.require(not endpoint.is_symlink() and endpoint.is_socket() and endpoint.stat().st_uid == self.uid,
                      "SOURCE_SETUP_CONFIGURATION_INVALID")
            endpoint.unlink()
        server = RelayServer(str(endpoint), RelayHandler)
        server.target = target
        os.chown(endpoint, self.uid, self.uid)
        endpoint.chmod(0o600)
        self.relays[str(endpoint)] = server
        threading.Thread(target=server.serve_forever, name="maintenance-t4-relay", daemon=True).start()

    def prepare(self, request):
        result = S.prepare(request, self.root / "routes.json", self.root / "channels.json", transport=self)
        # Publish a read-only copy. The workbench cannot rewrite host routes or broker ownership.
        S.atomic_json(self.api / "routes.json", T.decode((self.root / "routes.json").read_bytes()))
        (self.api / "routes.json").chmod(0o644)
        return result

    def restore(self, route):
        import managed_broker_service as broker
        target = Path(route["remoteSocket"])
        S.require(target.resolve() == target and target.is_relative_to(self.root),
                  "SOURCE_SETUP_CONFIGURATION_INVALID")
        path, config, sha = broker.configuration(target.parent / "broker-service.json")
        S.require(all(config[key] == route[key] for key in ("instanceId", "imageId", "bundleSha256")),
                  "SOURCE_SETUP_CONFIGURATION_CHANGED")
        try:
            broker.request(config, sha)
        except (OSError, EOFError):
            if target.exists() or (target.parent / "operator.sock").exists():
                owner = T.decode((target.parent / "service-owner.json").read_bytes())
                broker.recover(config, sha, owner["serviceId"])
            broker.start(path, config, sha)
        self.wait(route)

    def close(self):
        for endpoint, server in self.relays.items():
            server.shutdown()
            server.server_close()
            Path(endpoint).unlink(missing_ok=True)


class SetupHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(130)
        try:
            with self.request.makefile("rb") as source:
                raw = T.read_frame(source, MAX_REQUEST)[4:]
            request = T.decode(raw)
            if request == {"operation": "identity"}:
                result = {"ok": True, "ready": True, "sourceExecuted": False,
                          "backend": "same_host_t4", "automaticPreparation": True}
            else:
                result = self.server.transport.prepare(request)
        except (S.Failure, T.Failure) as error:
            result = {"ok": False, "code": str(error), "sourceExecuted": False}
        except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
            result = {"ok": False, "code": "SOURCE_SETUP_UNAVAILABLE", "sourceExecuted": False}
        try:
            self.request.sendall(T.frame(json.dumps(result, ensure_ascii=True).encode()))
        except OSError:
            pass


class SetupServer(getattr(socketserver, "UnixStreamServer", socketserver.BaseServer)):
    def handle_error(self, request, client_address):
        print("LOCAL_T4_SETUP_REQUEST_FAILED", file=sys.stderr, flush=True)


def serve(root, uid):
    S.require(os.geteuid() == 0, "SOURCE_SETUP_ROOT_REQUIRED")
    root = Path(root).resolve()
    S.require(root.is_dir() and root.stat().st_uid == 0 and not root.stat().st_mode & 0o022,
              "SOURCE_SETUP_CONFIGURATION_INVALID")
    with ExitStack() as stack:
        lock = stack.enter_context((root / "local-setup.lock").open("a+b"))
        import fcntl
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        transport = LocalTransport(root, uid)
        stack.callback(transport.close)
        transport.api.mkdir(mode=0o755, exist_ok=True)
        transport.api.chmod(0o755)
        transport.config(root / "channels.json")
        # Recover only recorded broker owners; never replay jobs, reset journals, or execute source.
        active = {r["id"] for r in T.decode((root / "routes.json").read_bytes())["runners"]}
        for route in transport.config(root / "channels.json").routes:
            if route["id"] in active:
                try:
                    transport.restore(route)
                except Exception:
                    print("LOCAL_T4_CHANNEL_RECOVERY_REQUIRED " + route["id"], file=sys.stderr, flush=True)
        S.atomic_json(transport.api / "routes.json", T.decode((root / "routes.json").read_bytes()))
        (transport.api / "routes.json").chmod(0o644)
        endpoint = transport.api / "setup.sock"
        if endpoint.exists():
            S.require(not endpoint.is_symlink() and endpoint.is_socket() and endpoint.stat().st_uid == uid,
                      "SOURCE_SETUP_CONFIGURATION_INVALID")
            endpoint.unlink()
        server = stack.enter_context(SetupServer(str(endpoint), SetupHandler))
        server.transport = transport
        os.chown(endpoint, uid, uid)
        endpoint.chmod(0o600)
        stack.callback(lambda: endpoint.unlink(missing_ok=True))
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
        server.serve_forever(poll_interval=0.25)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--uid", type=int, default=65532)
    args = parser.parse_args()
    serve(args.root, args.uid)


if __name__ == "__main__":
    main()
