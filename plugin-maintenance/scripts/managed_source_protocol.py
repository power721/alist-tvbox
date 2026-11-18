"""Network-none source execution; source output never becomes trusted runtime evidence."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time

WORKER = Path(__file__).resolve().parents[1] / "server/src/main/resources/worker"


def load(name):
    spec = importlib.util.spec_from_file_location("managed_fixed_" + name, WORKER / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


S = load("container_supervisor")
E = load("container_entry")
KIND = "managed-source-nonet-v1"
ENTRY = "/opt/at-maintenance/managed/entry.py"
OUTPUT_LIMIT = 196608
SOURCE_LIMIT = 1048576
POLICY = {**S.POLICY, "mode": KIND, "unknownSourceAllowed": True,
          "runtimeVerdictTrusted": False, "createTimeoutSeconds": 30, "cleanupConfirmationSeconds": 10}


def validate_request(value, body):
    S.require(isinstance(value, dict) and set(value) == {
        "schemaVersion", "jobId", "imageId", "sourceSha256", "sourceBytes", "runtimeSha256"}, "MANAGED_REQUEST_INVALID")
    S.require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1
              and isinstance(value["jobId"], str) and re.fullmatch(
                  r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", value["jobId"]),
              "MANAGED_REQUEST_INVALID")
    S.require(type(body) is bytes and 0 < len(body) <= SOURCE_LIMIT
              and type(value["sourceBytes"]) is int and value["sourceBytes"] == len(body)
              and value["sourceSha256"] == S.digest(body), "MANAGED_SOURCE_IDENTITY_MISMATCH")
    S.require(isinstance(value["imageId"], str) and re.fullmatch(r"sha256:[a-f0-9]{64}", value["imageId"])
              and isinstance(value["runtimeSha256"], str)
              and re.fullmatch(r"[a-f0-9]{64}", value["runtimeSha256"]), "MANAGED_REQUEST_INVALID")


def owned(document, request):
    labels = document.get("Config", {}).get("Labels") or {}
    return (document.get("Name") == "/" + S.name_for(request["jobId"])
            and labels.get(S.MANAGED + ".kind") == KIND
            and labels.get(S.MANAGED + ".job") == request["jobId"]
            and labels.get(S.MANAGED + ".attempt") == request["jobId"].replace("-", ""))


def create_arguments(request, entry_sha, payload_sha):
    args = S.create_arguments(request, request["jobId"].replace("-", ""), entry_sha)
    index = args.index(S.MANAGED + ".kind=trusted-fixture-v1")
    args[index] = S.MANAGED + ".kind=" + KIND
    args[-1] = ENTRY
    index = args.index(request["imageId"])
    args[index:index] = ["--label", S.MANAGED + ".managed-payload=" + payload_sha]
    return args


def validate_created(document, request, entry_sha, payload_sha):
    S.require(owned(document, request), "CONTAINER_OWNERSHIP_MISMATCH")
    config = document.get("Config", {})
    labels = config.get("Labels") or {}
    S.require(config.get("Cmd") == ["-I", ENTRY]
              and labels.get(S.MANAGED + ".entry") == entry_sha
              and labels.get(S.MANAGED + ".runtime") == request["runtimeSha256"]
              and labels.get(S.MANAGED + ".managed-payload") == payload_sha, "MANAGED_BUNDLE_MISMATCH")
    normalized = {**document, "Config": {
        **config, "Cmd": ["-I", S.ENTRY], "Labels": {**labels, S.MANAGED + ".kind": "trusted-fixture-v1"}}}
    S.validate_created(normalized, request, request["jobId"].replace("-", ""))


def validate_ready(body, request, entry_sha, payload_sha):
    value = S.decode(body)
    S.require(isinstance(value, dict) and set(value) == {
        "schemaVersion", "phase", "jobId", "entrySha256", "payloadSha256", "runtimeSha256", "controls"}
        and type(value["schemaVersion"]) is int and value["schemaVersion"] == 1
        and value["phase"] == "ready_before_source" and value["jobId"] == request["jobId"]
        and value["entrySha256"] == entry_sha and value["payloadSha256"] == payload_sha
        and value["runtimeSha256"] == request["runtimeSha256"], "MANAGED_READY_MISMATCH")
    try:
        controls = value["controls"]
        checked = E.validate_controls(controls)
        S.require(checked == controls and controls.get("allChecksPassed") is True,
                  "CONTAINER_CONTROLS_UNCONFIRMED")
    except (ValueError, KeyError, TypeError):
        raise S.Failure("CONTAINER_CONTROLS_UNCONFIRMED") from None
    return value


def check_cancelled(cancelled):
    S.require(cancelled is None or not cancelled(), "MANAGED_CANCELLED")


def exchange(command, request, source, entry_sha, payload_sha, *, timeout=20, cancelled=None):
    """Release source only after verified pre-source controls; bound duplex IO and time."""
    validate_request(request, source)
    env = {key: value for key, value in os.environ.items()
           if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP"}}
    with tempfile.TemporaryDirectory(prefix="at-managed-docker-config-") as config:
        env["DOCKER_CONFIG"] = config
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, env=env)
        output, errors = bytearray(), bytearray()
        condition = threading.Condition()
        exceeded = threading.Event()
        write_failed = threading.Event()
        eof = threading.Event()
        deadline = time.monotonic() + timeout
        writer = None

        def read(stream, target, is_output):
            with stream:
                while True:
                    chunk = stream.read1(4096)
                    if not chunk:
                        if is_output:
                            eof.set()
                        with condition:
                            condition.notify_all()
                        return
                    with condition:
                        available = max(0, OUTPUT_LIMIT - len(output) - len(errors))
                        target.extend(chunk[:available])
                        if len(chunk) > available:
                            exceeded.set()
                            process.kill()
                        condition.notify_all()
                    if exceeded.is_set():
                        return

        def write_source():
            try:
                check_cancelled(cancelled)
                process.stdin.write(S.encode({"operation": "execute", "jobId": request["jobId"]}) + b"\n")
                process.stdin.write(source)
                process.stdin.close()
            except (OSError, ValueError, S.Failure):
                write_failed.set()

        readers = [threading.Thread(target=read, args=(process.stdout, output, True), daemon=True),
                   threading.Thread(target=read, args=(process.stderr, errors, False), daemon=True)]
        for reader in readers:
            reader.start()
        try:
            header = {key: value for key, value in request.items() if key != "imageId"}
            process.stdin.write(S.encode(header) + b"\n")
            process.stdin.flush()
            with condition:
                while b"\n" not in output:
                    check_cancelled(cancelled)
                    S.require(not exceeded.is_set(), "ENGINE_OUTPUT_LIMIT")
                    S.require(len(output) <= 8192, "MANAGED_READY_MISMATCH")
                    S.require(not eof.is_set(), "MANAGED_READY_MISMATCH")
                    S.require(time.monotonic() < deadline, "ENGINE_TIMEOUT")
                    condition.wait(timeout=0.02)
                ready_bytes = bytes(output).split(b"\n", 1)[0]
                S.require(len(ready_bytes) <= 8192, "MANAGED_READY_MISMATCH")
                ready = validate_ready(ready_bytes, request, entry_sha, payload_sha)
            check_cancelled(cancelled)
            writer = threading.Thread(target=write_source, daemon=True)
            writer.start()
            while process.poll() is None:
                check_cancelled(cancelled)
                S.require(time.monotonic() < deadline, "ENGINE_TIMEOUT")
                time.sleep(0.02)
            writer.join(timeout=2)
            for reader in readers:
                reader.join(timeout=2)
            S.require(not writer.is_alive() and not any(reader.is_alive() for reader in readers),
                      "ENGINE_STREAM_UNCLOSED")
            S.require(not exceeded.is_set(), "ENGINE_OUTPUT_LIMIT")
            S.require(not write_failed.is_set(), "MANAGED_SOURCE_SEND_FAILED")
            S.require(process.returncode == 0, "ENGINE_COMMAND_FAILED")
            raw = bytes(output).split(b"\n", 1)[1]
            try:
                value = S.decode(raw)
            except S.Failure:
                value = None
            # A source can spoof any output after ACK. Project only fixed safe enums.
            status = value.get("status") if isinstance(value, dict) else None
            if not isinstance(status, str) or status not in {"init_returned", "missing_dependency", "source_exception"}:
                status = "invalid_output"
            return {"preSourceReady": ready, "sourceSentAfterControlAck": True,
                    "inputSourceSha256": S.digest(source), "inputSourceBytes": len(source),
                    "untrustedRuntimeSummary": {"status": status,
                                               "evidenceClass": "untrusted_source_output"},
                    "untrustedStdoutBytes": len(raw), "untrustedStderrBytes": len(errors),
                    "rawOutputStored": False, "businessChainVerified": False}
        finally:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=3)
            if writer:
                writer.join(timeout=3)
            if not process.stdin.closed:
                process.stdin.close()
            for reader in readers:
                reader.join(timeout=3)


def confirm_cleanup(engine, request, ident, *, wait=False):
    S.require(isinstance(ident, str) and re.fullmatch(r"[a-f0-9]{64}", ident),
              "CONTAINER_CLEANUP_UNCONFIRMED")
    deadline = time.monotonic() + POLICY["cleanupConfirmationSeconds"]
    def lookup(filter_value):
        remaining = deadline - time.monotonic()
        S.require(remaining > 0, "CONTAINER_CLEANUP_UNCONFIRMED")
        return engine.call(["container", "ls", "--all", "--no-trunc", "--filter",
                            filter_value, "--format", "{{.ID}}"], timeout=min(6, remaining)).decode("ascii").strip()
    while True:
        body = lookup("id=" + ident)
        S.require(not body or body == ident, "CONTAINER_LOOKUP_INVALID")
        named = lookup("name=^/" + S.name_for(request["jobId"]) + "$")
        S.require(named in {"", ident}, "CONTAINER_OWNERSHIP_MISMATCH")
        if not body and not named:
            return {"confirmed": True, "containerAbsent": True}
        S.require(wait and time.monotonic() < deadline, "CONTAINER_CLEANUP_UNCONFIRMED")
        time.sleep(0.1)


def cleanup(engine, request, expected_id=None):
    named = S.container_id(engine, request["jobId"])
    S.require(expected_id is None or named in {None, expected_id}, "CONTAINER_OWNERSHIP_MISMATCH")
    ident = expected_id or named
    uncertain = False
    if named:
        rows = engine.document(["container", "inspect", named])
        S.require(isinstance(rows, list) and len(rows) == 1 and rows[0].get("Id") == named
                  and owned(rows[0], request), "CONTAINER_OWNERSHIP_MISMATCH")
        try:
            engine.call(["container", "rm", "--force", named])
        except S.Failure as error:
            if error.code not in {"ENGINE_TIMEOUT", "ENGINE_COMMAND_FAILED", "ENGINE_STREAM_UNCLOSED"}:
                raise
            observer = getattr(engine, "record_failure", None)
            if observer is not None:
                observer("cleanup", error)
            uncertain = True
    if ident is not None:
        # A confirmed create binds a fixed ID. Reconcile a lost remove reply by reads, never another rm.
        return confirm_cleanup(engine, request, ident, wait=uncertain)
    S.require(S.container_id(engine, request["jobId"]) is None, "CONTAINER_CLEANUP_UNCONFIRMED")
    return {"confirmed": True, "containerAbsent": True}


def run(engine, request, source, entry_sha, payload_sha, *, cancelled=None):
    validate_request(request, source)
    check_cancelled(cancelled)
    info = engine.document(["info", "--format", "{{json .}}"])
    S.require(info.get("OSType") == "linux" and info.get("CgroupVersion") == "2"
              and info.get("CgroupDriver") in {"systemd", "cgroupfs"}, "LINUX_CGROUP_V2_REQUIRED")
    rows = engine.document(["image", "inspect", request["imageId"]])
    S.require(isinstance(rows, list) and len(rows) == 1 and rows[0].get("Id") == request["imageId"]
              and rows[0].get("Os") == "linux" and not rows[0].get("Config", {}).get("Volumes"),
              "LOCAL_IMAGE_MISMATCH")
    labels = rows[0].get("Config", {}).get("Labels") or {}
    S.require(labels.get(S.MANAGED + ".runtime") == request["runtimeSha256"]
              and labels.get(S.MANAGED + ".managed-entry") == entry_sha
              and labels.get(S.MANAGED + ".managed-payload") == payload_sha, "MANAGED_BUNDLE_MISMATCH")
    S.require(S.container_id(engine, request["jobId"]) is None, "JOB_ALREADY_EXISTS")
    check_cancelled(cancelled)
    confirmed = False
    ident = None
    stage = "container_create"
    try:
        ident = engine.call(create_arguments(request, entry_sha, payload_sha),
                            timeout=POLICY["createTimeoutSeconds"]).decode("ascii").strip()
        S.require(re.fullmatch(r"[a-f0-9]{64}", ident), "CONTAINER_CREATE_INVALID")
        confirmed = True
        check_cancelled(cancelled)
        stage = "container_validate"
        rows = engine.document(["container", "inspect", ident])
        S.require(isinstance(rows, list) and len(rows) == 1 and rows[0].get("Id") == ident,
                  "CONTAINER_INSPECT_INVALID")
        validate_created(rows[0], request, entry_sha, payload_sha)
        stage = "source_exchange"
        result = exchange([*engine.command, "container", "start", "--attach", "--interactive", ident],
                          request, source, entry_sha, payload_sha, cancelled=cancelled)
        stage = "container_exit_validate"
        rows = engine.document(["container", "inspect", ident])
        S.require(isinstance(rows, list) and len(rows) == 1 and rows[0].get("Id") == ident
                  and owned(rows[0], request), "CONTAINER_OWNERSHIP_MISMATCH")
        state = rows[0].get("State", {})
        S.require(state.get("Status") == "exited" and type(state.get("ExitCode")) is int
                  and state["ExitCode"] == 0 and state.get("OOMKilled") is False,
                  "CONTAINER_EXECUTION_FAILED")
        result.update({"authority": "managed_source_host_observed", "schemaVersion": 1,
                       "jobId": request["jobId"], "containerId": ident, "imageId": request["imageId"],
                       "policy": POLICY,
                       "containerState": {"status": state["Status"], "exitCode": state["ExitCode"],
                                          "oomKilled": state["OOMKilled"]}})
    except BaseException as error:
        observer = getattr(engine, "record_failure", None)
        if observer is not None:
            observer(stage, error)
        raise
    finally:
        try:
            cleaned = cleanup(engine, request, ident if confirmed else None)
        except (S.Failure, OSError, ValueError, KeyError, TypeError) as error:
            observer = getattr(engine, "record_failure", None)
            if observer is not None:
                observer("cleanup", error)
            raise S.Failure("CONTAINER_CLEANUP_UNCONFIRMED") from None
        S.require(confirmed, "CONTAINER_CLEANUP_UNCONFIRMED")
    result["cleanup"] = cleaned
    return result
