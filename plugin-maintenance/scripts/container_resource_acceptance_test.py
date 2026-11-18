"""Opt-in CPU, memory, PID and timeout checks against the existing pinned trusted image."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time

import container_acceptance_test as acceptance
import container_resource_checks as resources

S = acceptance.supervisor


def identity(runtime_bytes, image_id, endpoint, docker, payload_bytes):
    _, bundle = acceptance.checked_bundle()
    return {"schemaVersion": 1, "authority": "expected_resource_acceptance_intent",
            "runtimeSha256": hashlib.sha256(runtime_bytes).hexdigest(),
            "bundleSha256": hashlib.sha256(bundle).hexdigest(),
            "entrySha256": hashlib.sha256((acceptance.WORKER / "container_entry.py").read_bytes()).hexdigest(),
            "supervisorSha256": hashlib.sha256((acceptance.WORKER / "container_supervisor.py").read_bytes()).hexdigest(),
            "acceptanceScriptSha256": hashlib.sha256(Path(acceptance.__file__).read_bytes()).hexdigest(),
            "resourceAcceptanceScriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            **resources.identities(payload_bytes),
            "engineExecutableSha256": hashlib.sha256(docker.read_bytes()).hexdigest(),
            "endpointSha256": hashlib.sha256(endpoint.encode()).hexdigest(), "imageId": image_id}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--check", action="store_true")
    modes.add_argument("--run", action="store_true")
    modes.add_argument("--recover", type=Path)
    parser.add_argument("--docker", type=Path)
    parser.add_argument("--host")
    parser.add_argument("--image-id")
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()
    if args.check:
        if args.docker or args.host or args.image_id or args.json_out:
            parser.error("--check cannot be combined with Engine or evidence arguments")
        runtime, bundle = acceptance.checked_bundle()
        print(json.dumps({"authority": "source_only", "engineContacted": False,
                          "runtimeSha256": hashlib.sha256(runtime).hexdigest(),
                          "bundleSha256": hashlib.sha256(bundle).hexdigest(),
                          "resourceAcceptanceScriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                          **resources.identities(resources.PAYLOAD.read_bytes())}))
        return
    if not args.docker or not args.json_out or (args.run and not args.image_id) \
            or (args.recover and args.image_id):
        parser.error("--run requires docker, image-id and json-out; --recover requires docker and json-out")
    if not args.docker.is_absolute() or not args.docker.is_file():
        parser.error("--docker must be an existing absolute executable path")
    if args.run and not re.fullmatch(r"sha256:[a-f0-9]{64}", args.image_id):
        parser.error("--image-id must be an exact local sha256 image identity")
    if args.json_out.exists() or not args.json_out.parent.is_dir():
        parser.error("--json-out must be new and its parent must exist")
    journal = args.json_out.with_name(args.json_out.name + ".journal.jsonl")
    if args.run and journal.exists():
        parser.error("Refuse to overwrite an existing run journal")
    host = args.host or ("npipe:////./pipe/docker_engine" if sys.platform == "win32"
                         else "unix:///var/run/docker.sock")
    try:
        engine = S.Docker([str(args.docker)], host)
    except S.Failure as error:
        parser.error(error.code)
    runtime, _ = acceptance.checked_bundle()
    entry_sha = hashlib.sha256((acceptance.WORKER / "container_entry.py").read_bytes()).hexdigest()
    # Commands and the frozen intent use the same bytes, even if the local file changes later.
    payload_bytes = resources.PAYLOAD.read_bytes()
    current = identity(runtime, args.image_id or "", host, args.docker, payload_bytes)
    started = time.time()
    observations = []
    code = None
    try:
        if args.recover:
            S.require(args.recover.stat().st_size <= 65536, "JOURNAL_TOO_LARGE")
            rows = [S.decode(line) for line in args.recover.read_bytes().splitlines()]
            S.require(bool(rows) and isinstance(rows[0], dict)
                      and isinstance(rows[0].get("imageId"), str)
                      and re.fullmatch(r"sha256:[a-f0-9]{64}", rows[0]["imageId"]), "JOURNAL_INVALID")
            current["imageId"] = rows[0]["imageId"]
            _, confirmed = acceptance.recover(engine, args.recover, current, observations=observations)
            if not confirmed:
                code = "CONTAINER_CLEANUP_UNCONFIRMED"
        else:
            with journal.open("xb") as stream:
                recording = acceptance.JournalEngine(engine, stream)
                recording.event(current)
                acceptance.exercise(recording, runtime, current["imageId"], entry_sha,
                                    observations=observations)
                resources.exercise(recording, runtime, current["imageId"], entry_sha, supervisor=S,
                                   payload_bytes=payload_bytes, observations=observations)
        S.require(identity(runtime, current["imageId"], host, args.docker, resources.PAYLOAD.read_bytes()) == current,
                  "ACCEPTANCE_INPUT_CHANGED")
    except (S.Failure, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        code = error.code if isinstance(error, S.Failure) else "ACCEPTANCE_EXECUTION_ERROR"
    result = {"schemaVersion": 1, "authority": "resource_acceptance_tool_record",
              "startedAtEpochSeconds": started, "finishedAtEpochSeconds": time.time(),
              "imageId": current["imageId"], "completed": code is None, "errorCode": code,
              "intentIdentity": current,
              "recoveryOnly": bool(args.recover), "observations": observations,
              "candidateExecutionAllowed": False,
              "notVerified": ["full JVM/OS restart and daemon late-create race",
                              "managed-source/candidate execution", "network mediation",
                              "AT/T4/media/client behavior"],
              "boundary": "Fixed authored memory/PID/CPU/timeout workloads in the pinned fixture image. "
                          "No submitted source, production AT, model, network or deployment authorization."}
    with args.json_out.open("xb") as output:
        output.write(json.dumps(result, indent=2).encode() + b"\n")
    print(json.dumps({"completed": result["completed"], "errorCode": code,
                      "observations": len(observations), "candidateExecutionAllowed": False}))
    if code:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
