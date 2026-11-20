"""Exercise a prebuilt pinned image on a local Linux cgroup v2 Engine; no build/pull."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid

MODULE = Path(__file__).resolve().parents[1]
WORKER = MODULE / "server/src/main/resources/worker"
spec = importlib.util.spec_from_file_location("acceptance_supervisor", WORKER / "container_supervisor.py")
supervisor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(supervisor)


def digest(body):
    return hashlib.sha256(body).hexdigest()


class JournalEngine:
    """Persist create intent before submission and the acknowledgement before further commands."""
    def __init__(self, engine, journal):
        self.engine, self.journal = engine, journal
        self.command = engine.command

    def event(self, value):
        self.journal.write(supervisor.encode(value) + b"\n")
        self.journal.flush()
        os.fsync(self.journal.fileno())

    def call(self, arguments, payload=b"", timeout=6, limit=196608):
        prepared = None
        if arguments[:2] == ["container", "create"]:
            labels = dict(arguments[index + 1].split("=", 1) for index, item in enumerate(arguments) if item == "--label")
            prepared = {"phase": "PREPARED", "jobId": labels[supervisor.MANAGED + ".job"],
                        "attempt": labels[supervisor.MANAGED + ".attempt"]}
            self.event(prepared)
        result = self.engine.call(arguments, payload, timeout, limit)
        if prepared is not None:
            ident = result.decode("ascii").strip()
            supervisor.require(re.fullmatch(r"[a-f0-9]{64}", ident), "CONTAINER_CREATE_INVALID")
            self.event({**prepared, "phase": "ACKNOWLEDGED", "containerId": ident})
        return result

    def document(self, arguments):
        return supervisor.decode(self.call(arguments))


def recover(engine, journal_path, identity, *, observations=None):
    supervisor.require(journal_path.stat().st_size <= 65536, "JOURNAL_TOO_LARGE")
    rows = [supervisor.decode(line) for line in journal_path.read_bytes().splitlines()]
    supervisor.require(bool(rows) and rows[0] == identity, "JOURNAL_IDENTITY_MISMATCH")
    jobs = {}
    for row in rows[1:]:
        supervisor.require(isinstance(row, dict), "JOURNAL_INVALID")
        phase = row.get("phase")
        supervisor.require(set(row) == ({"phase", "jobId", "attempt", "containerId"}
                                       if phase == "ACKNOWLEDGED" else {"phase", "jobId", "attempt"})
                           and phase in {"PREPARED", "ACKNOWLEDGED"}, "JOURNAL_INVALID")
        supervisor.require(isinstance(row["jobId"], str) and re.fullmatch(
            r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", row["jobId"])
            and isinstance(row["attempt"], str) and re.fullmatch(r"[a-f0-9]{32}", row["attempt"]), "JOURNAL_INVALID")
        previous = jobs.get(row["jobId"])
        if phase == "PREPARED":
            supervisor.require(previous is None, "JOURNAL_INVALID")
        else:
            supervisor.require(previous is not None and previous["phase"] == "PREPARED"
                               and previous["attempt"] == row["attempt"]
                               and isinstance(row["containerId"], str)
                               and re.fullmatch(r"[a-f0-9]{64}", row["containerId"]), "JOURNAL_INVALID")
        jobs[row["jobId"]] = row
    # Validate the complete journal before allowing any Engine action.
    if observations is None:
        observations = []
    for row in jobs.values():
        ident = supervisor.container_id(engine, row["jobId"])
        if ident is not None:
            inspected = engine.document(["container", "inspect", ident])
            supervisor.require(isinstance(inspected, list) and len(inspected) == 1
                               and inspected[0].get("Id") == ident
                               and inspected[0].get("Image") == identity["imageId"]
                               and inspected[0].get("Config", {}).get("Labels", {}).get(
                                   supervisor.MANAGED + ".runtime") == identity["runtimeSha256"]
                               and inspected[0].get("Config", {}).get("Labels", {}).get(
                                   supervisor.MANAGED + ".entry") == identity["entrySha256"]
                               and (row["phase"] != "ACKNOWLEDGED" or ident == row["containerId"]),
                               "RECOVERY_CONTAINER_IDENTITY_MISMATCH")
        result = supervisor.cleanup(engine, row["jobId"], row["attempt"])
        # An empty lookup cannot settle a create whose daemon acknowledgement was lost.
        observations.append({"jobId": row["jobId"], "cleanup": result,
                             "creationAcknowledged": row["phase"] == "ACKNOWLEDGED"})
    return observations, all(row["phase"] == "ACKNOWLEDGED" for row in jobs.values())


def checked_bundle():
    bundle_bytes = (WORKER / "container-manifest.json").read_bytes()
    bundle = supervisor.decode(bundle_bytes)
    runtime_bytes = (WORKER / "runtime/manifest.json").read_bytes()
    supervisor.require(bundle["runtimeSha256"] == digest(runtime_bytes), "BUNDLE_IDENTITY_MISMATCH")
    for name in ("container_supervisor.py", "container_entry.py"):
        body = (WORKER / name).read_bytes()
        supervisor.require(bundle["files"][name] == {"bytes": len(body), "sha256": digest(body)},
                           "BUNDLE_IDENTITY_MISMATCH")
    runtime = supervisor.decode(runtime_bytes)
    for name, record in runtime["files"].items():
        supervisor.require(re.fullmatch(r"[A-Za-z0-9_.]+", name), "RUNTIME_IDENTITY_MISMATCH")
        body = (WORKER / "runtime" / name).read_bytes()
        supervisor.require(record == {"bytes": len(body), "sha256": digest(body)},
                           "RUNTIME_IDENTITY_MISMATCH")
    return runtime_bytes, bundle_bytes


def interrupt_attach(engine, ident):
    """Kill only our attached CLI after observing the daemon-owned container running."""
    environment = {k: v for k, v in os.environ.items()
                   if k.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP"}}
    with tempfile.TemporaryDirectory(prefix="at-maintenance-acceptance-config-") as config:
        environment["DOCKER_CONFIG"] = config
        process = subprocess.Popen([*engine.command, "container", "start", "--attach", "--interactive", ident],
                                   stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   env=environment)
        observed = False
        try:
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                state = engine.document(["container", "inspect", ident])
                if isinstance(state, list) and len(state) == 1 and state[0].get("Id") == ident:
                    observed = state[0].get("State", {}).get("Status") == "running"
                    if observed:
                        break
                supervisor.require(process.poll() is None, "ATTACH_EXITED_BEFORE_OBSERVATION")
                time.sleep(0.05)
            supervisor.require(observed, "RUNNING_CONTAINER_NOT_OBSERVED")
        finally:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)
            process.stdin.close()
        state = engine.document(["container", "inspect", ident])
        supervisor.require(isinstance(state, list) and len(state) == 1 and state[0].get("Id") == ident,
                           "CONTAINER_INSPECT_INVALID")
        return {"runningBeforeCliKill": observed, "cliExited": process.returncode is not None,
                "containerStateAfterCliKill": state[0].get("State", {}).get("Status")}


def exercise(engine, runtime_bytes, image_id, entry_sha, *, interruptor=interrupt_attach, job_factory=uuid.uuid4,
             observations=None):
    """Invoke the production supervisor owners; injected dependencies are only unit-test seams."""
    manifest = supervisor.decode(runtime_bytes)
    if observations is None:
        observations = []
    info = engine.document(["info", "--format", "{{json .}}"])
    supervisor.require(isinstance(info, dict) and info.get("OSType") == "linux"
                       and info.get("CgroupVersion") == "2"
                       and info.get("CgroupDriver") in {"systemd", "cgroupfs"}, "LINUX_CGROUP_V2_REQUIRED")
    version = info.get("ServerVersion")
    supervisor.require(isinstance(version, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,79}", version),
                       "ENGINE_VERSION_UNCONFIRMED")
    observations.append({"check": "engine_identity", "authority": "engine_metadata_observed",
                         **{key: info[key] for key in ("OSType", "CgroupVersion", "CgroupDriver", "ServerVersion")}})

    def request():
        value = {"schemaVersion": 1, "operation": "run", "jobId": str(job_factory()), "imageId": image_id,
                 "sourceSha256": manifest["fixtureSha256"], "runtimeSha256": digest(runtime_bytes)}
        return supervisor.request_from(supervisor.encode(value), runtime_bytes)

    good = request()
    receipt = supervisor.run(engine, good, entry_sha)
    observations.append({"check": "trusted_fixture_and_entry_controls", "result": receipt})
    observations.append({"check": "repeated_cleanup", "result": supervisor.cleanup(engine, good["jobId"])})

    def with_created(value, arguments, action, *, owned_attempt=None):
        """A lost create acknowledgement stays unconfirmed even if the next lookup is empty."""
        supervisor.require(supervisor.container_id(engine, value["jobId"]) is None, "JOB_ALREADY_EXISTS")
        confirmed = False
        try:
            ident = engine.call(arguments).decode("ascii").strip()
            supervisor.require(re.fullmatch(r"[a-f0-9]{64}", ident), "CONTAINER_CREATE_INVALID")
            confirmed = True
            inspected = engine.document(["container", "inspect", ident])
            supervisor.require(isinstance(inspected, list) and len(inspected) == 1
                               and inspected[0].get("Id") == ident, "CONTAINER_INSPECT_INVALID")
            return action(ident, inspected[0])
        finally:
            supervisor.cleanup(engine, value["jobId"], owned_attempt)
            supervisor.require(confirmed, "CONTAINER_CLEANUP_UNCONFIRMED")

    # Every negative configuration is more restrictive, and is never started.
    for option, replacement in (("--memory", "134217728"), ("--pids-limit", "32")):
        value = request()
        attempt = value["jobId"].replace("-", "")
        arguments = supervisor.create_arguments(value, attempt, entry_sha)
        arguments[arguments.index(option) + 1] = replacement

        def reject(_ident, document):
            supervisor.require(document.get("State", {}).get("Status") == "created", "CONTAINER_STATE_MISMATCH")
            try:
                supervisor.validate_created(document, value, attempt)
            except supervisor.Failure as error:
                supervisor.require(error.code == "CONTAINER_LIMITS_MISMATCH", "UNEXPECTED_REJECTION")
                return {"rejectedCode": error.code, "containerWasStarted": False}
            raise supervisor.Failure("MISMATCHED_CONTROLS_ACCEPTED")

        result = with_created(value, arguments, reject)
        observations.append({"check": "reject_" + option[2:], "result": result})

    value = request()
    attempt = value["jobId"].replace("-", "")

    def cancel(ident, document):
        supervisor.validate_created(document, value, attempt)
        return interruptor(engine, ident)

    interruption = with_created(value, supervisor.create_arguments(value, attempt, entry_sha), cancel)
    observations.append({"check": "cli_interruption_then_owned_cleanup", "result": interruption})
    observations.append({"check": "repeated_cleanup_after_interruption",
                         "result": supervisor.cleanup(engine, value["jobId"])})

    value = request()
    foreign_attempt = uuid.uuid4().hex

    def refuse_wrong_owner(_ident, _document):
        try:
            supervisor.cleanup(engine, value["jobId"])
        except supervisor.Failure as error:
            supervisor.require(error.code == "CONTAINER_OWNERSHIP_MISMATCH", "UNEXPECTED_REJECTION")
            supervisor.require(supervisor.container_id(engine, value["jobId"]) is not None,
                               "WRONG_OWNER_WAS_REMOVED")
            return {"rejectedCode": error.code, "wrongOwnerWasRemoved": False}
        raise supervisor.Failure("WRONG_OWNER_CLEANUP_ACCEPTED")

    result = with_created(value, supervisor.create_arguments(value, foreign_attempt, entry_sha),
                          refuse_wrong_owner, owned_attempt=foreign_attempt)
    observations.append({"check": "refuse_wrong_attempt_ownership", "result": result})
    return observations


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Validate local bundle only; never call an Engine")
    parser.add_argument("--run", action="store_true", help="Explicitly create and run the trusted local image")
    parser.add_argument("--recover", type=Path, help="Clean journal-owned attempts; never create or run a container")
    parser.add_argument("--docker", type=Path)
    parser.add_argument("--host", help="Local endpoint; defaults to unix:///var/run/docker.sock")
    parser.add_argument("--image-id")
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()
    if sum((args.check, args.run, args.recover is not None)) != 1:
        parser.error("Choose exactly one of --check, --run or --recover")
    if args.check and (args.docker or args.host or args.image_id or args.json_out):
        parser.error("--check cannot be combined with Engine or evidence arguments")
    if args.run and (not args.docker or not args.image_id or not args.json_out):
        parser.error("--run requires --docker, --image-id and --json-out")
    if args.recover and (not args.docker or not args.json_out or args.image_id):
        parser.error("--recover requires --docker and --json-out; image identity comes from the journal")
    runtime_bytes, bundle_bytes = checked_bundle()
    identity = {"runtimeSha256": digest(runtime_bytes), "bundleSha256": digest(bundle_bytes),
                "entrySha256": digest((WORKER / "container_entry.py").read_bytes()),
                "supervisorSha256": digest((WORKER / "container_supervisor.py").read_bytes()),
                "acceptanceScriptSha256": digest(Path(__file__).read_bytes())}
    if args.check:
        print(json.dumps({"authority": "source_only", **identity, "engineContacted": False}))
        return
    if not args.docker.is_absolute() or not args.docker.is_file():
        parser.error("--docker must be an existing absolute executable path")
    if args.run and not re.fullmatch(r"sha256:[a-f0-9]{64}", args.image_id):
        parser.error("--image-id must be an exact local sha256 image identity")
    if args.json_out.exists():
        parser.error("Refuse to overwrite existing evidence")
    if not args.json_out.parent.is_dir():
        parser.error("--json-out parent directory must already exist")
    # Constructing Docker validates a local endpoint before any command is issued.
    host = args.host or "unix:///var/run/docker.sock"
    try:
        engine = supervisor.Docker([str(args.docker)], host)
    except supervisor.Failure as error:
        parser.error(error.code)
    cli_sha = digest(args.docker.read_bytes())
    run_identity = {"schemaVersion": 1, "authority": "expected_acceptance_intent", **identity,
                    "engineExecutableSha256": cli_sha, "endpointSha256": digest(host.encode()),
                    "imageId": args.image_id}
    journal_path = args.json_out.with_name(args.json_out.name + ".journal.jsonl")
    if args.run and journal_path.exists():
        parser.error("Refuse to overwrite an existing run journal")
    started = time.time()
    observations = []
    code = None
    try:
        if args.recover:
            supervisor.require(args.recover.stat().st_size <= 65536, "JOURNAL_TOO_LARGE")
            lines = args.recover.read_bytes().splitlines()
            supervisor.require(bool(lines), "JOURNAL_INVALID")
            header = supervisor.decode(lines[0])
            supervisor.require(isinstance(header, dict) and isinstance(header.get("imageId"), str)
                               and re.fullmatch(r"sha256:[a-f0-9]{64}", header["imageId"]), "JOURNAL_INVALID")
            run_identity["imageId"] = header["imageId"]
            _, confirmed = recover(engine, args.recover, run_identity, observations=observations)
            if not confirmed:
                code = "CONTAINER_CLEANUP_UNCONFIRMED"
        else:
            with journal_path.open("xb") as journal:
                recording = JournalEngine(engine, journal)
                recording.event(run_identity)
                exercise(recording, runtime_bytes, args.image_id, identity["entrySha256"],
                         observations=observations)
        supervisor.require(digest(args.docker.read_bytes()) == cli_sha, "ENGINE_BINARY_CHANGED")
    except (supervisor.Failure, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        code = error.code if isinstance(error, supervisor.Failure) else "ACCEPTANCE_EXECUTION_ERROR"
    result = {"schemaVersion": 1, "authority": "acceptance_tool_record",
              "startedAtEpochSeconds": started, "finishedAtEpochSeconds": time.time(), **identity,
              "engineExecutableSha256": cli_sha, "endpointSha256": digest(host.encode()),
              "imageId": run_identity["imageId"], "completed": code is None, "errorCode": code,
              "recoveryOnly": bool(args.recover),
              "observations": observations, "candidateExecutionAllowed": False,
              "notVerified": ["OOM/PID exhaustion/CPU contention", "daemon late-create race",
                              "full JVM/OS restart and process tree", "candidate or managed-source execution",
                              "network mediation and real media/client behavior"],
              "boundary": "Trusted fixture and exact container controls only. Only completed observations "
                          "describe actions actually reached; interrupted runs may leave journal-owned cleanup. "
                          "No general sandbox qualification, candidate execution or deployment authorization."}
    # Exclusive creation prevents accidental replacement of previous observations.
    with args.json_out.open("xb") as output:
        output.write(json.dumps(result, indent=2).encode() + b"\n")
    print(json.dumps({"completed": result["completed"], "errorCode": code,
                      "observations": len(observations), "candidateExecutionAllowed": False}))
    if code is not None:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
