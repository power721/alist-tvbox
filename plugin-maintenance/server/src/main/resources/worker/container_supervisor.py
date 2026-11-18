"""Local Docker protocol for the bundled fixture only. Never accepts submitted Python."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import time

MANAGED = "org.alist-tvbox.maintenance"
ENTRY = "/opt/at-maintenance/container_entry.py"
TMPFS = "rw,noexec,nosuid,nodev,size=33554432,mode=0700,uid=65532,gid=65532"
POLICY = {
    "schemaVersion": 1, "mode": "container_fixture", "network": "none", "user": "65532:65532",
    "readOnlyRootfs": True, "memoryBytes": 268435456, "memorySwapBytes": 268435456,
    "nanoCpus": 500000000, "pidsLimit": 64, "workTmpfs": TMPFS,
    "maxOutputBytes": 196608, "runtimeTimeoutSeconds": 20,
    "unknownSourceAllowed": False, "imagePullAllowed": False,
}
SAFE_ENV = {"PATH": "/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8",
            "HOME": "/work", "TMPDIR": "/work", "PYTHONDONTWRITEBYTECODE": "1"}
IMAGE_ENV = set(SAFE_ENV) | {"PYTHON_VERSION", "PYTHON_SHA256", "GPG_KEY"}


class Failure(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def require(condition, code):
    if not condition:
        raise Failure(code)


def digest(body):
    return hashlib.sha256(body).hexdigest()


def encode(value):
    return json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode("utf-8")


def unique_fields(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, "JSON_DUPLICATE_FIELD")
        value[key] = item
    return value


def decode(body):
    try:
        return json.loads(body, object_pairs_hook=unique_fields)
    except (ValueError, UnicodeError):
        raise Failure("JSON_INVALID") from None


def request_from(body, runtime_bytes):
    require(len(body) <= 2048, "REQUEST_TOO_LARGE")
    value = decode(body)
    require(isinstance(value, dict), "REQUEST_INVALID")
    cleanup = value.get("operation") == "cleanup"
    keys = {"schemaVersion", "operation", "jobId"}
    if not cleanup:
        keys |= {"sourceSha256", "runtimeSha256", "imageId"}
    require(set(value) == keys and type(value.get("schemaVersion")) is int
            and value["schemaVersion"] == 1 and value.get("operation") in {"run", "cleanup"}, "REQUEST_INVALID")
    require(isinstance(value["jobId"], str) and re.fullmatch(
        r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", value["jobId"]), "JOB_ID_INVALID")
    if not cleanup:
        manifest = decode(runtime_bytes)
        require(value["sourceSha256"] == manifest["fixtureSha256"], "TRUSTED_FIXTURE_REQUIRED")
        require(value["runtimeSha256"] == digest(runtime_bytes), "RUNTIME_IDENTITY_MISMATCH")
        require(isinstance(value["imageId"], str) and re.fullmatch(r"sha256:[a-f0-9]{64}", value["imageId"]),
                "PINNED_IMAGE_REQUIRED")
    return value


def cli_run(command, payload, timeout, limit):
    """Bound both streams while reading; never persist raw engine error output."""
    env = {key: value for key, value in os.environ.items()
           if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP"}}
    with tempfile.TemporaryDirectory(prefix="at-maintenance-docker-config-") as config:
        env["DOCKER_CONFIG"] = config
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, env=env)
        output, errors = bytearray(), bytearray()
        exceeded = threading.Event()
        budget_lock = threading.Lock()

        def read(stream, target):
            with stream:
                while True:
                    chunk = stream.read1(4096)
                    if not chunk:
                        return
                    with budget_lock:
                        available = max(0, limit - len(output) - len(errors))
                        target.extend(chunk[:available])
                        if len(chunk) > available:
                            exceeded.set()
                    if exceeded.is_set():
                        process.kill()
                        return

        readers = [threading.Thread(target=read, args=(process.stdout, output), daemon=True),
                   threading.Thread(target=read, args=(process.stderr, errors), daemon=True)]
        for thread in readers:
            thread.start()
        try:
            # Generated protocol payloads are smaller than 2 KiB, including on Windows pipes.
            require(len(payload) <= 2048, "REQUEST_TOO_LARGE")
            try:
                process.stdin.write(payload)
                process.stdin.close()
            except BrokenPipeError:
                pass
            deadline = time.monotonic() + timeout
            while process.poll() is None:
                if time.monotonic() >= deadline:
                    raise Failure("ENGINE_TIMEOUT")
                time.sleep(0.02)
            for thread in readers:
                thread.join(timeout=2)
            require(not any(thread.is_alive() for thread in readers), "ENGINE_STREAM_UNCLOSED")
            require(not exceeded.is_set(), "ENGINE_OUTPUT_LIMIT")
            require(process.returncode == 0, "ENGINE_COMMAND_FAILED")
            return bytes(output)
        finally:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=2)
            if not process.stdin.closed:
                process.stdin.close()
            for thread in readers:
                thread.join(timeout=2)


class Docker:
    def __init__(self, command, host):
        require(host == "npipe:////./pipe/docker_engine" or isinstance(host, str)
                and re.fullmatch(r"unix:///[A-Za-z0-9_./-]+\.sock", host)
                and ".." not in host.split("/"), "LOCAL_ENGINE_REQUIRED")
        self.command = [*command, "--host", host]

    def call(self, arguments, payload=b"", timeout=15, limit=196608):
        return cli_run([*self.command, *arguments], payload, timeout, limit)

    def document(self, arguments):
        return decode(self.call(arguments))


def name_for(job_id):
    return "at-maintenance-" + job_id


def labels_for(job_id, attempt=None):
    value = {MANAGED + ".kind": "trusted-fixture-v1", MANAGED + ".job": job_id}
    if attempt is not None:
        value[MANAGED + ".attempt"] = attempt
    return value


def container_id(engine, job_id):
    body = engine.call(["container", "ls", "--all", "--no-trunc", "--filter",
                        "name=^/" + name_for(job_id) + "$", "--format", "{{.ID}}"]).decode("ascii").strip()
    require(not body or re.fullmatch(r"[a-f0-9]{64}", body), "CONTAINER_LOOKUP_INVALID")
    return body or None


def owned(document, job_id, attempt=None):
    labels = document.get("Config", {}).get("Labels") or {}
    return document.get("Name") == "/" + name_for(job_id) and all(
        labels.get(key) == value for key, value in labels_for(job_id, attempt).items())


def cleanup(engine, job_id, attempt=None):
    # Jobs have random persistent UUIDs and are submitted exactly once. Derive the
    # attempt from that identity so Java/restart cleanup cannot lose its nonce.
    attempt = job_id.replace("-", "") if attempt is None else attempt
    ident = container_id(engine, job_id)
    if ident is not None:
        document = engine.document(["container", "inspect", ident])
        require(isinstance(document, list) and len(document) == 1 and document[0].get("Id") == ident
                and owned(document[0], job_id, attempt),
                "CONTAINER_OWNERSHIP_MISMATCH")
        engine.call(["container", "rm", "--force", ident])
    require(container_id(engine, job_id) is None, "CONTAINER_CLEANUP_UNCONFIRMED")
    return {"confirmed": True, "containerAbsent": True}


def create_arguments(request, attempt, entry_sha):
    arguments = ["container", "create", "--interactive", "--pull", "never", "--name", name_for(request["jobId"]),
                 "--read-only", "--network", "none", "--user", POLICY["user"], "--cap-drop", "ALL",
                 "--security-opt", "no-new-privileges:true", "--cgroupns", "private", "--ipc", "private",
                 "--memory", str(POLICY["memoryBytes"]), "--memory-swap", str(POLICY["memorySwapBytes"]),
                 "--cpus", "0.5", "--pids-limit", str(POLICY["pidsLimit"]), "--tmpfs", "/work:" + TMPFS,
                 "--ulimit", "nofile=128:128", "--ulimit", "core=0:0", "--init", "--restart", "no",
                 "--no-healthcheck", "--log-driver", "none", "--workdir", "/work", "--entrypoint", "python"]
    labels = labels_for(request["jobId"], attempt)
    labels[MANAGED + ".runtime"] = request["runtimeSha256"]
    labels[MANAGED + ".entry"] = entry_sha
    for key, value in labels.items():
        arguments += ["--label", key + "=" + value]
    for key, value in SAFE_ENV.items():
        arguments += ["--env", key + "=" + value]
    return [*arguments, request["imageId"], "-I", ENTRY]


def validate_created(document, request, attempt):
    require(owned(document, request["jobId"], attempt), "CONTAINER_OWNERSHIP_MISMATCH")
    config, host = document.get("Config", {}), document.get("HostConfig", {})
    require(document.get("Image") == request["imageId"] and config.get("Image") == request["imageId"],
            "CONTAINER_IMAGE_MISMATCH")
    require(config.get("User") == POLICY["user"] and config.get("Entrypoint") == ["python"]
            and config.get("Cmd") == ["-I", ENTRY] and config.get("WorkingDir") == "/work"
            and config.get("OpenStdin") is True and config.get("Tty") is False
            and not config.get("Volumes") and config.get("Healthcheck", {}).get("Test") == ["NONE"],
            "CONTAINER_CONFIG_MISMATCH")
    environment = config.get("Env")
    require(isinstance(environment, list), "CONTAINER_ENV_MISMATCH")
    observed = {}
    for item in environment:
        require(isinstance(item, str) and "=" in item, "CONTAINER_ENV_MISMATCH")
        key, value = item.split("=", 1)
        require(key in IMAGE_ENV and key not in observed, "CONTAINER_ENV_MISMATCH")
        observed[key] = value
    require(all(observed.get(key) == value for key, value in SAFE_ENV.items()), "CONTAINER_ENV_MISMATCH")
    require(host.get("ReadonlyRootfs") is True and host.get("NetworkMode") == "none"
            and host.get("Privileged") is False and host.get("Memory") == POLICY["memoryBytes"]
            and host.get("MemorySwap") == POLICY["memorySwapBytes"] and host.get("NanoCpus") == POLICY["nanoCpus"]
            and host.get("PidsLimit") == POLICY["pidsLimit"] and host.get("Init") is True
            and host.get("CgroupnsMode") == "private" and host.get("IpcMode") == "private"
            and host.get("PidMode") in ("", None) and host.get("UTSMode") in ("", None)
            and host.get("UsernsMode") in ("", None), "CONTAINER_LIMITS_MISMATCH")
    require(set(host.get("CapDrop") or []) == {"ALL"} and not host.get("CapAdd")
            and host.get("SecurityOpt") == ["no-new-privileges:true"]
            and host.get("Tmpfs") == {"/work": TMPFS} and not host.get("Binds") and not host.get("Mounts")
            and not host.get("Devices") and not host.get("DeviceRequests") and not host.get("VolumesFrom")
            and not host.get("PortBindings") and host.get("PublishAllPorts") is False
            and host.get("RestartPolicy", {}).get("Name") == "no"
            and host.get("LogConfig", {}).get("Type") == "none", "CONTAINER_ACCESS_MISMATCH")
    limits = host.get("Ulimits")
    require(isinstance(limits, list) and len(limits) == 2
            and {item["Name"]: (item["Soft"], item["Hard"]) for item in limits}
            == {"nofile": (128, 128), "core": (0, 0)}, "CONTAINER_LIMITS_MISMATCH")
    require(all(item.get("Type") == "tmpfs" and item.get("Destination") == "/work"
                for item in document.get("Mounts", [])), "CONTAINER_ACCESS_MISMATCH")
    require(document.get("State", {}).get("Status") == "created", "CONTAINER_STATE_MISMATCH")


def validate_entry(result, request, entry_sha):
    require(isinstance(result, dict) and result.get("schemaVersion") == 1
            and result.get("jobId") == request["jobId"] and result.get("sourceSha256") == request["sourceSha256"]
            and result.get("runtimeSha256") == request["runtimeSha256"] and result.get("entrySha256") == entry_sha
            and result.get("authority") == "container_entry_observed", "CONTAINER_RECEIPT_MISMATCH")
    controls = result.get("controls", {})
    expected = {"uid": 65532, "gid": 65532, "capEffective": 0, "noNewPrivileges": 1, "seccomp": 2,
                "memoryMax": POLICY["memoryBytes"], "swapMax": 0, "pidsMax": POLICY["pidsLimit"],
                "cpuQuota": 50000, "cpuPeriod": 100000, "interfaces": ["lo"], "rootReadOnly": True,
                "coreLimit": [0, 0], "openFileLimit": [128, 128], "allChecksPassed": True}
    require(all(controls.get(key) == value for key, value in expected.items())
            and set(controls.get("workMountOptions", [])) >= {"rw", "noexec", "nosuid", "nodev"}
            and 0 < controls.get("workSizeBytes", 0) <= 33554432, "CONTAINER_CONTROLS_UNCONFIRMED")
    evidence = result.get("runtimeResult", {})
    receipt = evidence.get("receipt", {})
    require(evidence.get("evidenceClass") == "offline_fixture"
            and sorted(evidence.get("passedNodes", [])) == ["C01", "C07", "C10", "R01", "S06"]
            and receipt.get("authority") == "bootstrap_observed"
            and receipt.get("sourceBytesSha256") == receipt.get("loadedTextSha256") == request["sourceSha256"]
            and receipt.get("runtimeSha256") == request["runtimeSha256"], "FIXTURE_RECEIPT_MISMATCH")


def run(engine, request, entry_sha):
    info = engine.document(["info", "--format", "{{json .}}"])
    require(info.get("OSType") == "linux" and info.get("CgroupVersion") == "2"
            and info.get("CgroupDriver") in {"systemd", "cgroupfs"}, "LINUX_CGROUP_V2_REQUIRED")
    image = engine.document(["image", "inspect", request["imageId"]])
    require(isinstance(image, list) and len(image) == 1 and image[0].get("Id") == request["imageId"]
            and image[0].get("Os") == "linux" and not image[0].get("Config", {}).get("Volumes"),
            "LOCAL_IMAGE_MISMATCH")
    labels = image[0].get("Config", {}).get("Labels") or {}
    require(labels.get(MANAGED + ".runtime") == request["runtimeSha256"]
            and labels.get(MANAGED + ".entry") == entry_sha, "IMAGE_BUNDLE_MISMATCH")
    require(container_id(engine, request["jobId"]) is None, "JOB_ALREADY_EXISTS")
    attempt = request["jobId"].replace("-", "")
    result = None
    creation_confirmed = False
    try:
        ident = engine.call(create_arguments(request, attempt, entry_sha)).decode("ascii").strip()
        require(re.fullmatch(r"[a-f0-9]{64}", ident), "CONTAINER_CREATE_INVALID")
        creation_confirmed = True
        document = engine.document(["container", "inspect", ident])
        require(isinstance(document, list) and len(document) == 1 and document[0].get("Id") == ident,
                "CONTAINER_INSPECT_INVALID")
        validate_created(document[0], request, attempt)
        payload = encode({key: request[key] for key in ("jobId", "sourceSha256", "runtimeSha256")})
        raw = engine.call(["container", "start", "--attach", "--interactive", ident], payload,
                          timeout=POLICY["runtimeTimeoutSeconds"], limit=POLICY["maxOutputBytes"])
        result = decode(raw)
        validate_entry(result, request, entry_sha)
        exited = engine.document(["container", "inspect", ident])
        require(isinstance(exited, list) and len(exited) == 1 and owned(exited[0], request["jobId"], attempt),
                "CONTAINER_OWNERSHIP_MISMATCH")
        state = exited[0].get("State", {})
        require(state.get("Status") == "exited" and state.get("ExitCode") == 0
                and state.get("OOMKilled") is False, "CONTAINER_EXECUTION_FAILED")
        result = {"schemaVersion": 1, "authority": "container_supervisor_observed",
                  "jobId": request["jobId"], "imageId": request["imageId"], "containerId": ident,
                  "policy": POLICY, "requestSha256": digest(encode(request)), "responseSha256": digest(raw),
                  "engine": {key: info[key] for key in ("OSType", "CgroupVersion", "CgroupDriver")}, "entry": result}
    finally:
        # Killing the CLI does not kill daemon-owned containers. Cleanup uses the engine,
        # verifies job/attempt ownership, then confirms absence. Errors must not report success.
        try:
            cleaned = cleanup(engine, request["jobId"], attempt)
        except (Failure, OSError, ValueError, KeyError, TypeError):
            raise Failure("CONTAINER_CLEANUP_UNCONFIRMED") from None
        # A timed-out create may still complete in the daemon after an empty list
        # response. Absence alone cannot acknowledge that outstanding operation.
        require(creation_confirmed, "CONTAINER_CLEANUP_UNCONFIRMED")
    result["cleanup"] = cleaned
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", required=True)
    parser.add_argument("--host", required=True)
    args = parser.parse_args()
    try:
        require(Path(args.docker).is_absolute() and Path(args.docker).is_file(), "ENGINE_EXECUTABLE_REQUIRED")
        root = Path(__file__).resolve().parent
        body = sys.stdin.buffer.read(2049)
        runtime = (root / "runtime/manifest.json").read_bytes()
        request = request_from(body, runtime)
        engine = Docker([args.docker], args.host)
        if request["operation"] == "cleanup":
            result = {"schemaVersion": 1, "jobId": request["jobId"], "cleanup": cleanup(engine, request["jobId"])}
        else:
            result = run(engine, request, digest((root / "container_entry.py").read_bytes()))
        print(encode({"ok": True, "result": result}).decode("ascii"))
    except Failure as error:
        print(encode({"ok": False, "code": error.code}).decode("ascii"))
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print('{"ok":false,"code":"CONTAINER_PROTOCOL_ERROR"}')


if __name__ == "__main__":
    main()
