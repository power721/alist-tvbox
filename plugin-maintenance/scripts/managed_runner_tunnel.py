"""Fixed loopback-to-Unix broker transport; never retries a submitted frame."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import signal
import socket
import socketserver
import struct
import threading

MAX_FRAME = 1_500_000
MAX_REPLY = 786_432
TIMEOUT = 8
UUID = r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
SHA = r"[a-f0-9]{64}"
EVENT_LOG = None

RELAY = r'''
import pathlib,socket,struct,sys
p=pathlib.Path(sys.argv[1])
assert p.is_absolute() and p.resolve()==p and p.name=="executor.sock"
def exact(stream,n):
 out=bytearray()
 while len(out)<n:
  part=stream.read(n-len(out));assert part;out.extend(part)
 return bytes(out)
header=exact(sys.stdin.buffer,4);size=struct.unpack("!I",header)[0]
assert 0<size<=1500000
body=exact(sys.stdin.buffer,size)
with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as c:
 c.settimeout(6);c.connect(str(p));c.sendall(header+body)
 with c.makefile("rb") as stream:
  length=exact(stream,4);size=struct.unpack("!I",length)[0]
  assert 0<size<=786432
  response=exact(stream,size)
sys.stdout.buffer.write(length+response);sys.stdout.buffer.flush()
'''


class Failure(Exception):
    pass


def require(value, code):
    if not value:
        raise Failure(code)


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "TUNNEL_CONFIG_INVALID")
        result[key] = value
    return result


def decode(body):
    return json.loads(body, object_pairs_hook=unique_pairs)


def read_exact(stream, size):
    result = bytearray()
    while len(result) < size:
        part = stream.read(size - len(result))
        require(bool(part), "TUNNEL_FRAME_INCOMPLETE")
        result.extend(part)
    return bytes(result)


def frame(body):
    require(0 < len(body) <= MAX_FRAME, "TUNNEL_FRAME_LIMIT")
    return struct.pack("!I", len(body)) + body


def read_frame(stream, limit):
    header = read_exact(stream, 4)
    size = struct.unpack("!I", header)[0]
    require(0 < size <= limit, "TUNNEL_FRAME_LIMIT")
    return header + read_exact(stream, size)


def environment(name, optional=False):
    require(isinstance(name, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,99}", name),
            "TUNNEL_CONFIG_INVALID")
    value = os.environ.get(name, "")
    require(optional or bool(value), "TUNNEL_ENVIRONMENT_REQUIRED")
    require("\n" not in value and "\r" not in value, "TUNNEL_ENVIRONMENT_INVALID")
    return value


class Config:
    def __init__(self, path):
        self.path = Path(path).resolve()
        raw = self.path.read_bytes()
        require(len(raw) <= 65_536, "TUNNEL_CONFIG_INVALID")
        self.sha256 = hashlib.sha256(raw).hexdigest()
        self.document = decode(raw)
        value = self.document
        require(isinstance(value, dict) and set(value) == {"schemaVersion", "ssh", "runners"}
                and type(value["schemaVersion"]) is int and value["schemaVersion"] == 1,
                "TUNNEL_CONFIG_INVALID")
        ssh = value["ssh"]
        require(isinstance(ssh, dict) and set(ssh) <= {
            "hostEnv", "portEnv", "userEnv", "passwordEnv", "sudoPasswordEnv", "knownHostsFile"}
            and {"hostEnv", "userEnv", "passwordEnv"} <= set(ssh), "TUNNEL_CONFIG_INVALID")
        for name in ("hostEnv", "portEnv", "userEnv", "passwordEnv", "sudoPasswordEnv"):
            if name in ssh:
                require(isinstance(ssh[name], str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,99}", ssh[name]),
                        "TUNNEL_CONFIG_INVALID")
        if "knownHostsFile" in ssh:
            require(isinstance(ssh["knownHostsFile"], str) and Path(ssh["knownHostsFile"]).is_absolute()
                    and Path(ssh["knownHostsFile"]).is_file(), "TUNNEL_KNOWN_HOSTS_REQUIRED")
        self.ssh = ssh
        self.routes = value["runners"]
        require(isinstance(self.routes, list) and 0 < len(self.routes) <= 64, "TUNNEL_CONFIG_INVALID")
        ports, ids = set(), set()
        for route in self.routes:
            require(isinstance(route, dict) and set(route) == {
                "id", "localPort", "remoteSocket", "instanceId", "imageId", "bundleSha256",
                "egressPolicySha256", "httpImageId"}, "TUNNEL_CONFIG_INVALID")
            require(isinstance(route["id"], str) and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", route["id"])
                    and route["id"] not in ids, "TUNNEL_CONFIG_INVALID")
            require(type(route["localPort"]) is int and 1024 <= route["localPort"] <= 65535
                    and route["localPort"] not in ports, "TUNNEL_CONFIG_INVALID")
            endpoint = route["remoteSocket"]
            require(isinstance(endpoint, str) and re.fullmatch(r"/[A-Za-z0-9_./-]+/executor\.sock", endpoint)
                    and str(PurePosixPath(endpoint)) == endpoint
                    and ".." not in endpoint.split("/") and len(endpoint.encode()) <= 100,
                    "TUNNEL_CONFIG_INVALID")
            for key, pattern in (("instanceId", UUID), ("imageId", "sha256:" + SHA),
                                 ("bundleSha256", SHA)):
                require(isinstance(route[key], str) and re.fullmatch(pattern, route[key]),
                        "TUNNEL_CONFIG_INVALID")
            for key, pattern in (("egressPolicySha256", SHA), ("httpImageId", "sha256:" + SHA)):
                require(isinstance(route[key], str) and (not route[key] or re.fullmatch(pattern, route[key])),
                        "TUNNEL_CONFIG_INVALID")
            require(not route["httpImageId"] or bool(route["egressPolicySha256"]), "TUNNEL_CONFIG_INVALID")
            ids.add(route["id"])
            ports.add(route["localPort"])

    def credentials(self):
        ssh = self.ssh
        port = environment(ssh["portEnv"], optional=True) if "portEnv" in ssh else ""
        require(not port or port.isascii() and port.isdigit() and 1 <= int(port) <= 65535,
                "TUNNEL_CONFIG_INVALID")
        return {
            "hostname": environment(ssh["hostEnv"]),
            "port": int(port or "22"),
            "username": environment(ssh["userEnv"]),
            "password": environment(ssh["passwordEnv"]),
        }


def identity_reply(reply, route):
    value = decode(reply[4:])
    require(isinstance(value, dict) and value.get("ok") is True
            and isinstance(value.get("result"), dict), "TUNNEL_BROKER_IDENTITY_MISMATCH")
    identity = value["result"]
    require(type(identity.get("schemaVersion")) is int and identity["schemaVersion"] == 1
            and identity.get("authority") == "managed_source_broker_configuration"
            and type(identity.get("recoveryRequired")) is bool, "TUNNEL_BROKER_IDENTITY_MISMATCH")
    for key in ("instanceId", "imageId", "bundleSha256"):
        require(identity.get(key) == route[key], "TUNNEL_BROKER_IDENTITY_MISMATCH")
    require(identity.get("egressPolicySha256") == (route["egressPolicySha256"] or None)
            and identity.get("httpImageId", "") == route["httpImageId"], "TUNNEL_BROKER_IDENTITY_MISMATCH")
    return {"id": route["id"], "localPort": route["localPort"],
            "connected": True, "ready": not identity["recoveryRequired"], "sourceExecuted": False}


class Transport:
    def __init__(self, config, client_factory=None, emit=None):
        self.config = config
        self.client_factory = client_factory
        self.emit = emit or (lambda event: None)
        self.lock = threading.RLock()
        self.current = None
        self.leases = {}
        self.verified = {}
        self.retired = set()
        self.closed = False
        self.connections = 0

    def connect(self):
        if self.client_factory is None:
            import paramiko
            client = paramiko.SSHClient()
            client.load_system_host_keys()
            if "knownHostsFile" in self.config.ssh:
                client.load_host_keys(self.config.ssh["knownHostsFile"])
            client.set_missing_host_key_policy(paramiko.RejectPolicy())
        else:
            client = self.client_factory()
        try:
            client.connect(**self.config.credentials(), look_for_keys=False, allow_agent=False,
                           timeout=5, auth_timeout=5, banner_timeout=5)
            client.get_transport().set_keepalive(30)
            self.verified[client] = set()
            self.connections += 1
            self.emit({"event": "SSH_CONNECTED", "connections": self.connections})
            return client
        except BaseException:
            self.verified.pop(client, None)
            client.close()
            raise

    def relay(self, client, route, request):
        channel = client.get_transport().open_session(timeout=TIMEOUT)
        try:
            channel.settimeout(TIMEOUT)
            command = shlex.join(["/usr/bin/python3", "-I", "-c", RELAY, route["remoteSocket"]])
            password = self.config.ssh.get("sudoPasswordEnv")
            if password:
                command = "sudo -k -S -p '' " + command
            channel.exec_command(command)
            prefix = (environment(password) + "\n").encode() if password else b""
            channel.sendall(prefix + request)
            channel.shutdown_write()
            return read_frame(channel.makefile("rb"), MAX_REPLY)
        finally:
            channel.close()

    def observe(self, client, route):
        request = frame(b'{"schemaVersion":1,"operation":"identity"}')
        observed = identity_reply(self.relay(client, route, request), route)
        self.verified[client].add(route["id"])
        return observed

    @contextmanager
    def lease(self):
        with self.lock:
            require(not self.closed, "TUNNEL_STOPPED")
            peer = self.current.get_transport() if self.current is not None else None
            if self.current is not None and (peer is None or not peer.is_active()):
                self.retired.add(self.current)
                if not self.leases.get(self.current):
                    self.current.close()
                    self.verified.pop(self.current, None)
                    self.retired.remove(self.current)
                self.current = None
            if self.current is None:
                self.current = self.connect()
            client = self.current
            self.leases[client] = self.leases.get(client, 0) + 1
        try:
            yield client
        except BaseException:
            with self.lock:
                if self.current is client:
                    self.current = None
                self.retired.add(client)
            raise
        finally:
            with self.lock:
                self.leases[client] -= 1
                if not self.leases[client]:
                    del self.leases[client]
                    if client in self.retired:
                        self.retired.remove(client)
                        client.close()
                        self.verified.pop(client, None)

    def exchange(self, route, request):
        # A lost start reply is resolved by the broker journal, never by replaying this frame.
        with self.lease() as client:
            with self.lock:
                if route["id"] not in self.verified[client]:
                    self.observe(client, route)
            return self.relay(client, route, request)

    def check(self):
        result = []
        for route in self.config.routes:
            try:
                with self.lease() as client:
                    with self.lock:
                        result.append(self.observe(client, route))
            except Exception as error:
                code = str(error) if isinstance(error, Failure) else "TUNNEL_CONNECTION_UNAVAILABLE"
                self.emit({"event": "CONNECTION_UNAVAILABLE", "routeId": route["id"], "code": code})
                result.append({"id": route["id"], "localPort": route["localPort"], "connected": False,
                               "ready": False, "sourceExecuted": False, "code": code})
        return result

    def close(self):
        with self.lock:
            self.closed = True
            if self.current is not None:
                self.retired.add(self.current)
                self.current = None
            for client in tuple(self.retired):
                if not self.leases.get(client):
                    self.retired.remove(client)
                    client.close()
                    self.verified.pop(client, None)


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.request.settimeout(TIMEOUT)
        try:
            request = read_frame(self.rfile, MAX_FRAME)
            self.wfile.write(self.server.transport.exchange(self.server.route, request))
            self.wfile.flush()
        except Exception as error:
            code = str(error) if isinstance(error, Failure) else "TUNNEL_REQUEST_FAILED"
            self.server.transport.emit({"event": "REQUEST_FAILED", "routeId": self.server.route["id"], "code": code})


class Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    daemon_threads = False
    block_on_close = True

    def __init__(self, route, transport):
        self.route, self.transport = route, transport
        self.capacity = threading.BoundedSemaphore(4)
        super().__init__(("127.0.0.1", route["localPort"]), Handler)

    def server_bind(self):
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        else:
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        super().server_bind()

    def process_request(self, request, address):
        if not self.capacity.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.capacity.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.capacity.release()


class Tunnels:
    def __init__(self, transport):
        self.transport = transport
        self.servers, self.threads = [], []

    def start(self):
        try:
            for route in self.transport.config.routes:
                self.servers.append(Server(route, self.transport))
            for server in self.servers:
                thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": .1}, daemon=True)
                thread.start()
                self.threads.append(thread)
        except BaseException:
            self.close()
            raise
        # The management page must still open while the NAS is offline.
        try:
            return self.transport.check()
        except Exception as error:
            code = str(error) if isinstance(error, Failure) else "TUNNEL_CONNECTION_UNAVAILABLE"
            self.transport.emit({"event": "CONNECTION_UNAVAILABLE", "code": code})
            return [{"id": route["id"], "localPort": route["localPort"], "connected": False,
                     "ready": False, "sourceExecuted": False, "code": code}
                    for route in self.transport.config.routes]

    def refresh(self):
        previous = self.transport.config
        selected = Config(previous.path)
        if selected.sha256 == previous.sha256:
            return False
        # Retired listeners drain existing frames; surviving listeners cannot be retargeted.
        by_id = {route["id"]: route for route in selected.routes}
        require(selected.ssh == previous.ssh and all(
            route["id"] not in by_id or by_id[route["id"]] == route for route in previous.routes),
            "TUNNEL_RELOAD_NOT_ADDITIVE")
        existing = {route["id"] for route in previous.routes}
        added, threads = [], []
        try:
            for route in selected.routes:
                if route["id"] not in existing:
                    added.append(Server(route, self.transport))
            for server in added:
                thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": .1}, daemon=True)
                thread.start()
                threads.append(thread)
        except BaseException:
            for server in added[:len(threads)]:
                server.shutdown()
            for server in added:
                server.server_close()
            for thread in threads:
                thread.join()
            raise
        self.servers.extend(added)
        self.threads.extend(threads)
        for server, thread in tuple(zip(self.servers, self.threads)):
            if server.route["id"] not in by_id:
                server.shutdown()
                server.server_close()
                thread.join()
                self.servers.remove(server)
                self.threads.remove(thread)
        self.transport.config = selected
        self.transport.emit({"event": "TUNNELS_EXTENDED", "added": len(added),
                             "configSha256": selected.sha256})
        return True

    def close(self):
        for server in self.servers[:len(self.threads)]:
            server.shutdown()
        for server in self.servers:
            server.server_close()
        for thread in self.threads:
            thread.join()
        self.servers.clear()
        self.threads.clear()
        self.transport.close()


def emit(value):
    line = json.dumps(value, ensure_ascii=True, separators=(",", ":"))
    if EVENT_LOG is not None:
        EVENT_LOG.info(line)
    if EVENT_LOG is None or value["event"] in {"FAILED", "TUNNELS_READY", "TUNNELS_STOPPED"}:
        print(line, flush=True)


def main():
    global EVENT_LOG
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("check", "run"))
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--state-file", type=Path)
    parser.add_argument("--event-log", type=Path)
    args = parser.parse_args()
    config = Config(args.config)
    if args.event_log:
        require(args.event_log.resolve() != config.path
                and (args.state_file is None or args.event_log.resolve() != args.state_file.resolve()),
                "TUNNEL_CONFIG_INVALID")
        EVENT_LOG = logging.getLogger("managed_runner_tunnels")
        EVENT_LOG.propagate = False
        EVENT_LOG.setLevel(logging.INFO)
        EVENT_LOG.addHandler(RotatingFileHandler(args.event_log, maxBytes=1_048_576,
                                                backupCount=2, encoding="utf-8"))
    transport = Transport(config, emit=emit)
    if args.operation == "check":
        try:
            emit({"event": "CHECKED", "runners": transport.check(), "sourceExecuted": False})
        finally:
            transport.close()
        return
    state = {"kind": "managed-runner-tunnels", "pid": os.getpid(), "configSha256": config.sha256,
             "status": "STARTING", "sourceExecuted": False}
    stop = threading.Event()
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: stop.set())
    tunnels = Tunnels(transport)
    written = False
    try:
        state.update(status="RUNNING", runners=tunnels.start())
        if args.state_file:
            with args.state_file.open("x", encoding="utf-8") as stream:
                json.dump(state, stream, ensure_ascii=True)
            written = True
        emit({"event": "TUNNELS_READY", **state})
        rejected_sha = None
        while not stop.wait(1):
            current_sha = None
            try:
                current_sha = hashlib.sha256(config.path.read_bytes()).hexdigest()
                if current_sha in {transport.config.sha256, rejected_sha}:
                    continue
                if tunnels.refresh():
                    state.update(configSha256=transport.config.sha256,
                                 runners=[{"id": route["id"], "localPort": route["localPort"]}
                                          for route in transport.config.routes])
                    if args.state_file:
                        temporary = args.state_file.with_name(args.state_file.name + ".reload")
                        with temporary.open("w", encoding="utf-8") as stream:
                            json.dump(state, stream, ensure_ascii=True)
                        os.replace(temporary, args.state_file)
                rejected_sha = None
            except Exception as error:
                rejected_sha = current_sha
                emit({"event": "RELOAD_REJECTED", "code": str(error) if isinstance(error, Failure)
                      else "TUNNEL_RELOAD_FAILED"})
    finally:
        tunnels.close()
        if written:
            state["status"] = "STOPPED"
            temporary = args.state_file.with_name(args.state_file.name + "." + str(os.getpid()) + ".tmp")
            with temporary.open("x", encoding="utf-8") as stream:
                json.dump(state, stream, ensure_ascii=True)
            os.replace(temporary, args.state_file)
        emit({"event": "TUNNELS_STOPPED", "pid": os.getpid()})


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        emit({"event": "FAILED", "code": str(error) if isinstance(error, Failure) else "TUNNEL_OPERATION_FAILED"})
        raise SystemExit(1) from None
