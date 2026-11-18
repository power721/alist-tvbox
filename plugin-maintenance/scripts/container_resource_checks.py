"""Opt-in resource acceptance using fixed authored code and unchanged product container controls."""
from __future__ import annotations

import errno
import hashlib
import importlib.util
from pathlib import Path
import re
import uuid

PAYLOAD = Path(__file__).with_name("container_resource_payload.py")
MODES = ("memory", "pids", "cpu", "timeout")
spec = importlib.util.spec_from_file_location(
    "resource_entry_controls", Path(__file__).resolve().parents[1]
    / "server/src/main/resources/worker/container_entry.py")
entry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entry)


def identities(payload_bytes):
    return {"resourceChecksSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "resourcePayloadSha256": hashlib.sha256(payload_bytes).hexdigest()}


def command(mode, entry_sha, payload_bytes):
    if mode not in MODES:
        raise ValueError("RESOURCE_SCENARIO_INVALID")
    return ["-I", "-c", payload_bytes.decode("utf-8"), mode, entry_sha]


def validate_created(s, document, request, attempt, mode, entry_sha, payload_bytes):
    expected = command(mode, entry_sha, payload_bytes)
    s.require(document.get("Config", {}).get("Cmd") == expected, "RESOURCE_COMMAND_MISMATCH")
    # Only the fixed acceptance command differs; all product access/resource checks still run.
    normalized = {**document, "Config": {**document["Config"], "Cmd": ["-I", s.ENTRY]}}
    s.validate_created(normalized, request, attempt)


def validate_observation(s, result, request, mode, entry_sha):
    s.require(isinstance(result, dict) and type(result.get("schemaVersion")) is int
              and result["schemaVersion"] == 1 and result.get("authority") == "fixed_resource_probe_observed"
              and result.get("scenario") == mode and result.get("entrySha256") == entry_sha
              and all(result.get(key) == request[key] for key in ("jobId", "sourceSha256", "runtimeSha256")),
              "RESOURCE_RECEIPT_MISMATCH")
    controls = result.get("controls")
    try:
        checked = entry.validate_controls(controls)
    except (ValueError, KeyError, TypeError):
        raise s.Failure("RESOURCE_CONTROLS_UNCONFIRMED") from None
    s.require(controls.get("allChecksPassed") is True and checked == controls, "RESOURCE_CONTROLS_UNCONFIRMED")
    value = result.get("observation")
    s.require(isinstance(value, dict), "RESOURCE_OBSERVATION_INVALID")
    before, after = value.get("before"), value.get("after")
    s.require(isinstance(before, dict) and isinstance(after, dict)
              and all(isinstance(key, str) and type(count) is int and count >= 0
                      for row in (before, after) for key, count in row.items()), "RESOURCE_COUNTER_INVALID")

    def increased(key):
        return key in before and key in after and after[key] > before[key]

    s.require(all(key in after and after[key] >= count for key, count in before.items()),
              "RESOURCE_COUNTER_RESET")
    if mode == "memory":
        s.require(type(value.get("childExitCode")) is int and value["childExitCode"] == -9
                  and increased("max") and increased("oom") and increased("oom_kill"),
                  "RESOURCE_OOM_UNCONFIRMED")
    elif mode == "pids":
        s.require(type(value.get("forkErrno")) is int and value["forkErrno"] == errno.EAGAIN
                  and value.get("expectedErrno") == errno.EAGAIN and increased("max")
                  and type(value.get("childrenCreated")) is int and 0 < value["childrenCreated"] <= 64
                  and type(value.get("pidsAtRefusal")) is int and value["pidsAtRefusal"] == 64,
                  "RESOURCE_PIDS_UNCONFIRMED")
    elif mode == "cpu":
        s.require(increased("nr_throttled") and increased("throttled_usec") and increased("usage_usec")
                  and type(value.get("elapsedMilliseconds")) is int
                  and 2000 <= value["elapsedMilliseconds"] <= 10000, "RESOURCE_CPU_UNCONFIRMED")
    else:
        raise s.Failure("RESOURCE_SCENARIO_INVALID")


def exercise(engine, runtime_bytes, image_id, entry_sha, *, payload_bytes, supervisor, observations,
             job_factory=uuid.uuid4):
    s = supervisor
    manifest = s.decode(runtime_bytes)
    payload_identity = identities(payload_bytes)
    for mode in MODES:
        request = s.request_from(s.encode({
            "schemaVersion": 1, "operation": "run", "jobId": str(job_factory()),
            "imageId": image_id, "sourceSha256": manifest["fixtureSha256"],
            "runtimeSha256": s.digest(runtime_bytes)}), runtime_bytes)
        attempt = request["jobId"].replace("-", "")
        s.require(s.container_id(engine, request["jobId"]) is None, "JOB_ALREADY_EXISTS")
        arguments = s.create_arguments(request, attempt, entry_sha)
        arguments[-2:] = command(mode, entry_sha, payload_bytes)
        confirmed = False
        row = None
        try:
            ident = engine.call(arguments).decode("ascii").strip()
            s.require(re.fullmatch(r"[a-f0-9]{64}", ident), "CONTAINER_CREATE_INVALID")
            confirmed = True
            inspected = engine.document(["container", "inspect", ident])
            s.require(isinstance(inspected, list) and len(inspected) == 1 and inspected[0].get("Id") == ident,
                      "CONTAINER_INSPECT_INVALID")
            validate_created(s, inspected[0], request, attempt, mode, entry_sha, payload_bytes)
            payload = s.encode({key: request[key] for key in ("jobId", "sourceSha256", "runtimeSha256")})
            if mode == "timeout":
                try:
                    engine.call(["container", "start", "--attach", "--interactive", ident], payload, timeout=2)
                except s.Failure as error:
                    s.require(error.code == "ENGINE_TIMEOUT", "RESOURCE_TIMEOUT_UNCONFIRMED")
                else:
                    raise s.Failure("RESOURCE_TIMEOUT_UNCONFIRMED")
                observation = {"cliTimeoutCode": "ENGINE_TIMEOUT", "cliDeadlineSeconds": 2,
                               "entryControlsObserved": False}
            else:
                raw = engine.call(["container", "start", "--attach", "--interactive", ident], payload,
                                  timeout=20, limit=32768)
                observation = s.decode(raw)
                validate_observation(s, observation, request, mode, entry_sha)
            inspected = engine.document(["container", "inspect", ident])
            s.require(isinstance(inspected, list) and len(inspected) == 1 and inspected[0].get("Id") == ident
                      and inspected[0].get("Image") == request["imageId"]
                      and s.owned(inspected[0], request["jobId"], attempt), "CONTAINER_OWNERSHIP_MISMATCH")
            state = inspected[0].get("State", {})
            s.require(state.get("Status") == ("running" if mode == "timeout" else "exited")
                      and (mode == "timeout" or type(state.get("ExitCode")) is int and state["ExitCode"] == 0)
                      and type(state.get("OOMKilled")) is bool
                      and (mode == "memory" or state["OOMKilled"] is False), "RESOURCE_EXECUTION_UNCONFIRMED")
            row = {"check": "resource_" + mode, "jobId": request["jobId"], "containerId": ident,
                   "authority": "fixed_resource_acceptance_observation", "observation": observation,
                   "containerState": {key: state[key] for key in ("Status", "OOMKilled")},
                   **payload_identity}
        finally:
            try:
                cleanup = s.cleanup(engine, request["jobId"], attempt)
            except (s.Failure, OSError, ValueError, KeyError, TypeError):
                raise s.Failure("CONTAINER_CLEANUP_UNCONFIRMED") from None
            s.require(confirmed, "CONTAINER_CLEANUP_UNCONFIRMED")
        observations.append({**row, "cleanup": cleanup})
    return observations
