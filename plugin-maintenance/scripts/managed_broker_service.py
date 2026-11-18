"""Root-only lifecycle for an installed broker; no source execution or task replay."""
from __future__ import annotations

import argparse
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import socketserver
import stat
import struct
import subprocess
import sys
import tempfile
import threading
import time
import uuid

LIMIT = 4096
HASH = r"[a-f0-9]{64}"


class Failure(Exception):
    pass


def require(value, code):
    if not value:
        raise Failure(code)


def sha(body):
    return hashlib.sha256(body).hexdigest()


def exact(stream, size):
    body = bytearray()
    while len(body) < size:
        part = stream.read(size - len(body))
        require(part, "BROKER_SERVICE_FRAME_INVALID")
        body.extend(part)
    return bytes(body)


def receive(stream):
    size = struct.unpack("!I", exact(stream, 4))[0]
    require(0 < size <= LIMIT, "BROKER_SERVICE_FRAME_LIMIT")
    value = json.loads(exact(stream, size))
    require(isinstance(value, dict), "BROKER_SERVICE_FRAME_INVALID")
    return value


def send(stream, value):
    body = json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode()
    require(len(body) <= LIMIT, "BROKER_SERVICE_FRAME_LIMIT")
    stream.write(struct.pack("!I", len(body)) + body)
    stream.flush()


def owned_path(value, *, directory=False):
    path = Path(value)
    require(path.is_absolute() and path.resolve() == path and not path.is_symlink(),
            "BROKER_SERVICE_PATH_INVALID")
    info = path.stat()
    require(info.st_uid == 0 and not stat.S_IMODE(info.st_mode) & 0o022
            and (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)),
            "BROKER_SERVICE_PATH_INVALID")
    return path


def configuration(path):
    require(os.name == "posix" and os.getuid() == 0, "BROKER_SERVICE_ROOT_REQUIRED")
    path = owned_path(path)
    body = path.read_bytes()
    require(len(body) <= 8192, "BROKER_SERVICE_CONFIG_INVALID")
    value = json.loads(body)
    keys = {"schemaVersion", "root", "runtimeRoot", "instanceId", "imageId", "bundleSha256",
            "egressPolicyFile", "egressPolicySha256", "httpImageId"}
    require(isinstance(value, dict) and set(value) == keys and type(value["schemaVersion"]) is int
            and value["schemaVersion"] == 1, "BROKER_SERVICE_CONFIG_INVALID")
    require(str(uuid.UUID(value["instanceId"])) == value["instanceId"]
            and re.fullmatch("sha256:" + HASH, value["imageId"])
            and re.fullmatch(HASH, value["bundleSha256"])
            and (value["httpImageId"] is None
                 or re.fullmatch("sha256:" + HASH, value["httpImageId"])),
            "BROKER_SERVICE_CONFIG_INVALID")
    root = owned_path(value["root"], directory=True)
    require(stat.S_IMODE(root.stat().st_mode) == 0o711
            and len(os.fsencode(root / "operator.sock")) <= 100, "BROKER_SERVICE_PATH_INVALID")
    runtime = Path(value["runtimeRoot"])
    require(runtime.is_absolute() and runtime.resolve() == runtime and runtime.is_dir()
            and not runtime.is_symlink(), "BROKER_SERVICE_PATH_INVALID")
    manifest = runtime / "server/src/main/resources/worker/managed-runtime.json"
    raw = manifest.read_bytes()
    require(sha(raw) == value["bundleSha256"], "BROKER_SERVICE_BUNDLE_CHANGED")
    for name, record in json.loads(raw)["files"].items():
        target = runtime / name
        require(target.resolve().is_relative_to(runtime) and target.is_file()
                and not target.is_symlink() and sha(target.read_bytes()) == record["sha256"],
                "BROKER_SERVICE_BUNDLE_CHANGED")
    if value["egressPolicyFile"] is None:
        require(value["egressPolicySha256"] is None and value["httpImageId"] is None,
                "BROKER_SERVICE_CONFIG_INVALID")
    else:
        policy = owned_path(value["egressPolicyFile"])
        require(policy.stat().st_size <= 262144
                and sha(policy.read_bytes()) == value["egressPolicySha256"],
                "BROKER_SERVICE_POLICY_CHANGED")
    return path, value, sha(body)


class Controller:
    def __init__(self, broker, config_sha, *, service_id=None):
        self.broker, self.config_sha = broker, config_sha
        self.service_id = service_id or str(uuid.uuid4())
        self.stopping = threading.Event()

    def handle(self, value):
        require(isinstance(value, dict) and value.get("schemaVersion") == 1
                and type(value["schemaVersion"]) is int
                and value.get("configSha256") == self.config_sha
                and value.get("operation") in {"status", "stop"}
                and set(value) == ({"schemaVersion", "operation", "configSha256"}
                                   | ({"serviceId"} if value["operation"] == "stop" else set())),
                "BROKER_SERVICE_REQUEST_INVALID")
        broker = self.broker
        # This is the same lock used by source start and HTTP-driver admission.
        with broker.lock:
            if value["operation"] == "stop":
                require(value["serviceId"] == self.service_id, "BROKER_SERVICE_OWNER_CHANGED")
                running = any(row["status"] == "RUNNING"
                              or row.get("httpAcceptance", {}).get("status") == "RUNNING"
                              for row in broker.jobs.values())
                require(broker.active is None and broker.http_active is None
                        and not running and not broker.sessions, "BROKER_SERVICE_BUSY")
                require(broker.pending_rpc is None, "BROKER_SERVICE_RESULT_PENDING")
                broker.closed = True
                self.stopping.set()
            return {"schemaVersion": 1, "authority": "managed_broker_lifecycle_observed",
                    "serviceId": self.service_id, "pid": os.getpid(),
                    "lifecycleSha256": sha(Path(__file__).read_bytes()),
                    "configSha256": self.config_sha,
                    "state": "STOPPING" if self.stopping.is_set() else "RUNNING",
                    "active": broker.active is not None or broker.http_active is not None,
                    "pendingResult": broker.pending_rpc is not None, "journalJobs": len(broker.jobs),
                    "identity": broker.identity(), "sourceExecuted": False}


class ControlHandler(socketserver.StreamRequestHandler):
    def handle(self):
        self.connection.settimeout(3)
        try:
            result = {"ok": True, "result": self.server.controller.handle(receive(self.rfile))}
        except Failure as error:
            result = {"ok": False, "code": str(error)}
        except (OSError, ValueError, TypeError, KeyError, struct.error):
            result = {"ok": False, "code": "BROKER_SERVICE_REQUEST_INVALID"}
        try:
            send(self.wfile, result)
        except OSError:
            pass


class ControlServer(getattr(socketserver, "UnixStreamServer", socketserver.BaseServer)):
    timeout = 0.1

    def __init__(self, path, controller):
        self.controller = controller
        super().__init__(str(path), ControlHandler)


def unlink_owned_socket(path, identity):
    info = path.lstat()
    require(stat.S_ISSOCK(info.st_mode) and (info.st_dev, info.st_ino) == identity,
            "BROKER_SERVICE_SOCKET_CHANGED")
    path.unlink()


def lock_file(stack, path):
    import fcntl
    descriptor = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    stream = stack.enter_context(os.fdopen(descriptor, "a+b"))
    require(os.fstat(descriptor).st_uid == 0, "BROKER_SERVICE_PATH_INVALID")
    try:
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise Failure("BROKER_SERVICE_ALREADY_RUNNING") from None


def save_owner(root, value):
    path = root / "service-owner.json"
    if path.exists() or path.is_symlink():
        owned_path(path)
    descriptor, temporary = tempfile.mkstemp(prefix=".service-owner-", dir=root)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode())
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        descriptor = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def socket_identity(path):
    info = path.lstat()
    require(stat.S_ISSOCK(info.st_mode), "BROKER_SERVICE_SOCKET_CHANGED")
    return {"name": path.name, "device": info.st_dev, "inode": info.st_ino,
            "ctimeNs": info.st_ctime_ns}


def recover(config, config_sha, service_id):
    """Release only the last recorded endpoints under both offline lifecycle locks."""
    require(service_id is not None, "BROKER_SERVICE_OWNER_REQUIRED")
    root = Path(config["root"])
    journal = owned_path(root / "journal", directory=True)
    require(stat.S_IMODE(journal.stat().st_mode) == 0o700, "BROKER_SERVICE_PATH_INVALID")
    with ExitStack() as stack:
        lock_file(stack, root / ".service.lock")
        lock_file(stack, journal / ".lock")
        path = owned_path(root / "service-owner.json")
        require(path.stat().st_size <= LIMIT, "BROKER_SERVICE_OWNER_INVALID")
        owner = json.loads(path.read_bytes())
        require(isinstance(owner, dict) and owner.get("schemaVersion") == 1
                and owner.get("configSha256") == config_sha
                and owner.get("instanceId") == config["instanceId"],
                "BROKER_SERVICE_OWNER_INVALID")
        require(owner.get("serviceId") == service_id, "BROKER_SERVICE_OWNER_CHANGED")
        require(owner.get("state") in {"RUNNING", "RECOVERING", "RECOVERED"}
                and isinstance(owner.get("sockets"), list)
                and [item.get("name") for item in owner["sockets"]]
                == ["executor.sock", "operator.sock"], "BROKER_SERVICE_OWNER_INVALID")
        sockets = []
        for expected in owner["sockets"]:
            endpoint = root / expected["name"]
            if not endpoint.exists() and not endpoint.is_symlink():
                require(owner["state"] in {"RECOVERING", "RECOVERED"},
                        "BROKER_SERVICE_SOCKET_CHANGED")
                continue
            require(socket_identity(endpoint) == expected, "BROKER_SERVICE_SOCKET_CHANGED")
            sockets.append(endpoint)
        # The intent makes a lost reply or a crash between the two unlinks resumable.
        owner["state"] = "RECOVERING"
        save_owner(root, owner)
        for endpoint in sockets:
            expected = next(item for item in owner["sockets"] if item["name"] == endpoint.name)
            require(socket_identity(endpoint) == expected, "BROKER_SERVICE_SOCKET_CHANGED")
            endpoint.unlink()
        owner["state"] = "RECOVERED"
        save_owner(root, owner)
        return {"schemaVersion": 1, "authority": "managed_broker_offline_socket_recovery",
                "serviceId": service_id, "configSha256": config_sha, "state": "RECOVERED",
                "socketsAbsent": True, "tasksReplayed": False, "journalModified": False,
                "taskCleanupPerformed": False}


def serve(config, config_sha):
    root, runtime = Path(config["root"]), Path(config["runtimeRoot"])
    sys.path[:0] = [str(runtime / "scripts"), str(runtime / "vendor")]
    import managed_source_broker as B
    require(B.B.OUTPUT.read_bytes() == B.B.body()
            and sha(B.B.body()) == config["bundleSha256"], "BROKER_SERVICE_BUNDLE_CHANGED")
    journal = owned_path(root / "journal", directory=True)
    require(stat.S_IMODE(journal.stat().st_mode) == 0o700, "BROKER_SERVICE_PATH_INVALID")
    endpoint, operator = root / "executor.sock", root / "operator.sock"
    with ExitStack() as stack:
        lock_file(stack, root / ".service.lock")
        lock_file(stack, journal / ".lock")
        require(not endpoint.exists() and not endpoint.is_symlink()
                and not operator.exists() and not operator.is_symlink(), "BROKER_SERVICE_SOCKET_EXISTS")
        policy = (None if config["egressPolicyFile"] is None
                  else B.S.decode(Path(config["egressPolicyFile"]).read_bytes()))
        broker = B.Broker(journal, config["instanceId"], config["imageId"],
                          lambda: B.S.Docker(["/usr/bin/docker"], "unix:///var/run/docker.sock"),
                          egress_policy=policy, http_image_id=config["httpImageId"])
        controller = Controller(broker, config_sha)

        class Executor(B.Server):
            daemon_threads = False
            block_on_close = True

        sockets = []
        executor = control = thread = None
        stop_signal = threading.Event()
        previous = signal.signal(signal.SIGTERM, lambda *_: stop_signal.set())
        try:
            executor = Executor(endpoint, broker)
            info = endpoint.lstat()
            sockets.append((endpoint, (info.st_dev, info.st_ino)))
            os.chown(endpoint, 65532, 65532)
            os.chmod(endpoint, 0o600)
            control = ControlServer(operator, controller)
            info = operator.lstat()
            sockets.append((operator, (info.st_dev, info.st_ino)))
            os.chmod(operator, 0o600)
            save_owner(root, {"schemaVersion": 1, "serviceId": controller.service_id,
                             "configSha256": config_sha, "instanceId": config["instanceId"],
                             "pid": os.getpid(), "state": "RUNNING",
                             "sockets": [socket_identity(endpoint), socket_identity(operator)]})
            thread = threading.Thread(target=executor.serve_forever,
                                      kwargs={"poll_interval": 0.1}, daemon=False)
            thread.start()
            while not controller.stopping.is_set():
                control.handle_request()
                if stop_signal.is_set():
                    stop_signal.clear()
                    try:
                        controller.handle({"schemaVersion": 1, "operation": "stop",
                                           "configSha256": config_sha, "serviceId": controller.service_id})
                    except Failure as error:
                        print(json.dumps({"stopRefused": str(error)}), flush=True)
        finally:
            # No new admission; wait for already accepted handlers before releasing the journal lock.
            with broker.lock:
                broker.closed = True
            if thread is not None:
                if thread.is_alive():
                    executor.shutdown()
                thread.join()
            if executor is not None:
                executor.server_close()
            if control is not None:
                control.server_close()
            broker.close()
            for path, identity in sockets:
                unlink_owned_socket(path, identity)
            signal.signal(signal.SIGTERM, previous)


def request(config, config_sha, operation="status", service_id=None):
    value = {"schemaVersion": 1, "operation": operation, "configSha256": config_sha}
    if operation == "stop":
        value["serviceId"] = service_id
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(4)
        client.connect(str(Path(config["root"]) / "operator.sock"))
        with client.makefile("rwb", buffering=0) as stream:
            send(stream, value)
            result = receive(stream)
    require(result.get("ok") is True, result.get("code", "BROKER_SERVICE_REQUEST_FAILED"))
    return result["result"]


def wait_stopped(config, timeout=20):
    import fcntl
    deadline = time.monotonic() + timeout
    root = Path(config["root"])
    while time.monotonic() < deadline:
        with (root / ".service.lock").open("r+b") as stream:
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                require(not (root / "executor.sock").exists()
                        and not (root / "operator.sock").exists(), "BROKER_SERVICE_SOCKET_EXISTS")
                return
            except BlockingIOError:
                pass
        time.sleep(0.05)
    raise Failure("BROKER_SERVICE_STOP_TIMEOUT")


def start(path, config, config_sha):
    root = Path(config["root"])
    log_path = root / ("service-" + uuid.uuid4().hex + ".log")
    with log_path.open("xb") as log:
        child = subprocess.Popen([sys.executable, "-I", str(Path(__file__).resolve()),
                                  "serve", "--config", str(path)],
                                 stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                 start_new_session=True, close_fds=True,
                                 env={"PATH": "/usr/bin:/bin", "HOME": "/nonexistent", "LANG": "C.UTF-8"})
    deadline = time.monotonic() + 12
    while child.poll() is None and time.monotonic() < deadline:
        try:
            observed = request(config, config_sha)
            require(observed["pid"] == child.pid, "BROKER_SERVICE_ALREADY_RUNNING")
            return observed
        except (OSError, EOFError):
            time.sleep(0.05)
    require(child.poll() is not None, "BROKER_SERVICE_START_TIMEOUT")
    raise Failure("BROKER_SERVICE_START_FAILED")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("serve", "start", "status", "stop", "restart", "recover"))
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--service-id")
    args = parser.parse_args()
    path, config, config_sha = configuration(args.config)
    if args.operation == "serve":
        serve(config, config_sha)
        return
    if args.operation == "recover":
        print(json.dumps(recover(config, config_sha, args.service_id), ensure_ascii=True))
        return
    if args.operation in {"stop", "restart"}:
        require(args.service_id is not None, "BROKER_SERVICE_OWNER_REQUIRED")
        observed = request(config, config_sha, "stop", args.service_id)
        wait_stopped(config)
        if args.operation == "stop":
            observed["state"] = "STOPPED"
    if args.operation in {"start", "restart"}:
        observed = start(path, config, config_sha)
    elif args.operation == "status":
        observed = request(config, config_sha)
    print(json.dumps(observed, ensure_ascii=True))


if __name__ == "__main__":
    try:
        main()
    except Failure as error:
        print(json.dumps({"ok": False, "code": str(error)}))
        raise SystemExit(1) from None
    except BaseException:
        print(json.dumps({"ok": False, "code": "BROKER_SERVICE_FAILED"}))
        raise SystemExit(1) from None
