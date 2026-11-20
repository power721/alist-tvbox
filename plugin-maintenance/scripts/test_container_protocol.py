"""Protocol fixtures only: no Docker daemon, image or container isolation is exercised."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

WORKER = Path(__file__).resolve().parents[1] / "server/src/main/resources/worker"


def load(name):
    spec = importlib.util.spec_from_file_location(name, WORKER / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SUPERVISOR = load("container_supervisor")
ENTRY = load("container_entry")
RUNTIME = (WORKER / "runtime/manifest.json").read_bytes()
JOB = "00000000-0000-4000-8000-000000000001"
ID = "c" * 64
IMAGE = "sha256:" + "a" * 64
ENTRY_SHA = hashlib.sha256((WORKER / "container_entry.py").read_bytes()).hexdigest()


def request():
    return {"schemaVersion": 1, "operation": "run", "jobId": JOB, "imageId": IMAGE,
            "sourceSha256": json.loads(RUNTIME)["fixtureSha256"],
            "runtimeSha256": hashlib.sha256(RUNTIME).hexdigest()}


def controls():
    return {"uid": 65532, "gid": 65532, "capEffective": 0, "noNewPrivileges": 1, "seccomp": 2,
            "memoryMax": 268435456, "swapMax": 0, "pidsMax": 64, "cpuQuota": 50000, "cpuPeriod": 100000,
            "interfaces": ["lo"], "rootReadOnly": True, "workMountOptions": ["rw", "noexec", "nosuid", "nodev"],
            "workSizeBytes": 33554432, "coreLimit": [0, 0], "openFileLimit": [128, 128]}


class EngineFixture:
    """Deterministic CLI/inspect double. This object never launches a container."""
    def __init__(self):
        self.calls = []
        self.container = None
        self.info = {"OSType": "linux", "CgroupVersion": "2", "CgroupDriver": "systemd"}
        self.start_error = None
        self.create_error = None
        self.mutate_created = None
        self.mutate_result = None
        self.refuse_remove = False

    def document(self, arguments):
        return SUPERVISOR.decode(self.call(arguments))

    def call(self, arguments, payload=b"", timeout=6, limit=196608):
        self.calls.append((arguments, payload))
        if arguments[0] == "info":
            return SUPERVISOR.encode(self.info)
        if arguments[:2] == ["image", "inspect"]:
            return SUPERVISOR.encode([{"Id": IMAGE, "Os": "linux", "Config": {"Labels": {
                SUPERVISOR.MANAGED + ".runtime": request()["runtimeSha256"],
                SUPERVISOR.MANAGED + ".entry": ENTRY_SHA}}}])
        if arguments[:2] == ["container", "ls"]:
            return ((ID + "\n") if self.container else "").encode()
        if arguments[:2] == ["container", "create"]:
            if self.create_error:
                raise SUPERVISOR.Failure(self.create_error)
            labels = {}
            for index, value in enumerate(arguments):
                if value == "--label":
                    key, item = arguments[index + 1].split("=", 1)
                    labels[key] = item
            self.container = {
                "Id": ID, "Name": "/at-maintenance-" + JOB, "Image": IMAGE,
                "Config": {"Labels": labels, "Image": IMAGE, "User": "65532:65532",
                           "Entrypoint": ["python"], "Cmd": ["-I", "/opt/at-maintenance/container_entry.py"],
                           "WorkingDir": "/work", "OpenStdin": True, "Tty": False,
                           "Healthcheck": {"Test": ["NONE"]}, "Env": [
                               "PATH=/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8", "HOME=/work",
                               "TMPDIR=/work", "PYTHONDONTWRITEBYTECODE=1", "PYTHON_VERSION=3.12.14"]},
                "HostConfig": {"ReadonlyRootfs": True, "NetworkMode": "none", "Privileged": False,
                               "Memory": 268435456, "MemorySwap": 268435456, "NanoCpus": 500000000,
                               "PidsLimit": 64, "Init": True, "CgroupnsMode": "private", "IpcMode": "private",
                               "CapDrop": ["ALL"], "SecurityOpt": ["no-new-privileges:true"],
                               "Tmpfs": {"/work": SUPERVISOR.TMPFS}, "PublishAllPorts": False,
                               "RestartPolicy": {"Name": "no"}, "LogConfig": {"Type": "none"},
                               "Ulimits": [{"Name": "nofile", "Soft": 128, "Hard": 128},
                                           {"Name": "core", "Soft": 0, "Hard": 0}]},
                "Mounts": [], "State": {"Status": "created"},
            }
            if self.mutate_created:
                self.mutate_created(self.container)
            return (ID + "\n").encode()
        if arguments[:2] == ["container", "inspect"]:
            return SUPERVISOR.encode([self.container])
        if arguments[:2] == ["container", "start"]:
            if self.start_error:
                raise SUPERVISOR.Failure(self.start_error)
            value = json.loads(payload)
            self.container["State"] = {"Status": "exited", "ExitCode": 0, "OOMKilled": False}
            result = {"schemaVersion": 1, "authority": "container_entry_observed", **value,
                      "entrySha256": ENTRY_SHA, "controls": ENTRY.validate_controls(controls()),
                      "runtimeResult": {"evidenceClass": "offline_fixture", "passedNodes": ["S06", "C01", "C07", "C10", "R01"],
                                        "receipt": {"authority": "bootstrap_observed",
                                                    "sourceBytesSha256": value["sourceSha256"],
                                                    "loadedTextSha256": value["sourceSha256"],
                                                    "runtimeSha256": value["runtimeSha256"]}}}
            if self.mutate_result:
                self.mutate_result(result)
            return SUPERVISOR.encode(result)
        if arguments[:2] == ["container", "rm"]:
            if not self.refuse_remove:
                self.container = None
            return ID.encode()
        raise AssertionError(arguments)


class ContainerProtocolTest(unittest.TestCase):
    def test_request_only_accepts_pinned_trusted_fixture_and_fixed_fields(self):
        value = request()
        self.assertEqual(value, SUPERVISOR.request_from(SUPERVISOR.encode(value), RUNTIME))
        for key, replacement in [("imageId", "python:latest"), ("imageId", IMAGE.upper()),
                                 ("sourceSha256", "f" * 64), ("runtimeSha256", "f" * 64),
                                 ("jobId", "../owner"), ("schemaVersion", True), ("source", "raise Exception()")]:
            bad = {**value, key: replacement}
            with self.subTest(key=key, value=replacement), self.assertRaises(SUPERVISOR.Failure):
                SUPERVISOR.request_from(SUPERVISOR.encode(bad), RUNTIME)
        with self.assertRaisesRegex(SUPERVISOR.Failure, "JSON_DUPLICATE_FIELD"):
            SUPERVISOR.request_from(b'{"operation":"run","operation":"cleanup"}', RUNTIME)

    def test_protocol_fixture_checks_limits_before_start_and_engine_cleanup_after_exit(self):
        engine = EngineFixture()
        result = SUPERVISOR.run(engine, request(), ENTRY_SHA)
        self.assertIsNone(engine.container)
        self.assertTrue(result["cleanup"]["confirmed"])
        self.assertEqual(IMAGE, result["imageId"])
        create = next(args for args, _ in engine.calls if args[:2] == ["container", "create"])
        self.assertEqual("never", create[create.index("--pull") + 1])
        self.assertNotIn("--privileged", create)
        self.assertNotIn("--mount", create)
        self.assertNotIn("--publish", create)
        payload = next(body for args, body in engine.calls if args[:2] == ["container", "start"])
        self.assertEqual({"jobId", "sourceSha256", "runtimeSha256"}, set(json.loads(payload)))
        self.assertFalse(any(args[0] in {"pull", "build", "exec"} for args, _ in engine.calls))

    def test_weakened_engine_settings_never_start_and_owned_created_container_is_removed(self):
        mutations = [("NetworkMode", "host"), ("ReadonlyRootfs", False), ("Memory", 0),
                     ("MemorySwap", -1), ("NanoCpus", 0), ("PidsLimit", -1),
                     ("Privileged", True), ("CapAdd", ["SYS_ADMIN"]), ("Devices", [{"PathOnHost": "/dev/kvm"}]),
                     ("Binds", ["/:/host:rw"]), ("VolumesFrom", ["production"]),
                     ("SecurityOpt", ["seccomp=unconfined"]), ("PortBindings", {"80/tcp": [{}]}),
                     ("PidMode", "host"), ("IpcMode", "host"), ("UsernsMode", "host")]
        for key, value in mutations:
            engine = EngineFixture()
            engine.mutate_created = lambda doc, k=key, v=value: doc["HostConfig"].update({k: v})
            with self.subTest(key=key), self.assertRaises(SUPERVISOR.Failure):
                SUPERVISOR.run(engine, request(), ENTRY_SHA)
            self.assertFalse(any(args[:2] == ["container", "start"] for args, _ in engine.calls))
            self.assertIsNone(engine.container)

    def test_image_volume_and_env_injection_cannot_enter_the_fixture_process(self):
        for mutate in [
            lambda doc: doc["Mounts"].append({"Type": "volume", "Destination": "/data"}),
            lambda doc: doc["Config"]["Env"].append("HTTP_PROXY=http://production.invalid"),
            lambda doc: doc["Config"]["Env"].append("MAINTENANCE_AT_SESSION_TOKEN=SECRET"),
            lambda doc: doc["Config"]["Env"].append("PATH=/untrusted"),
            lambda doc: doc["Config"].update({"User": "0"}),
            lambda doc: doc["Config"].update({"Entrypoint": ["/bin/sh"]}),
        ]:
            engine = EngineFixture()
            engine.mutate_created = mutate
            with self.assertRaises(SUPERVISOR.Failure):
                SUPERVISOR.run(engine, request(), ENTRY_SHA)
            self.assertFalse(any(args[:2] == ["container", "start"] for args, _ in engine.calls))
            self.assertIsNone(engine.container)

    def test_no_existing_job_is_replaced_and_foreign_ownership_is_never_deleted(self):
        engine = EngineFixture()
        engine.container = {"already": "exists"}
        with self.assertRaisesRegex(SUPERVISOR.Failure, "JOB_ALREADY_EXISTS"):
            SUPERVISOR.run(engine, request(), ENTRY_SHA)
        self.assertFalse(any(args[:2] == ["container", "rm"] for args, _ in engine.calls))
        engine = EngineFixture()
        engine.mutate_created = lambda doc: doc["Config"]["Labels"].update(
            {SUPERVISOR.MANAGED + ".attempt": "another-process"})
        with self.assertRaisesRegex(SUPERVISOR.Failure, "CONTAINER_CLEANUP_UNCONFIRMED"):
            SUPERVISOR.run(engine, request(), ENTRY_SHA)
        self.assertFalse(any(args[:2] == ["container", "rm"] for args, _ in engine.calls))
        with self.assertRaisesRegex(SUPERVISOR.Failure, "CONTAINER_OWNERSHIP_MISMATCH"):
            SUPERVISOR.cleanup(engine, JOB)
        self.assertFalse(any(args[:2] == ["container", "rm"] for args, _ in engine.calls))

    def test_start_timeout_cleans_container_and_failed_cleanup_cannot_claim_success(self):
        engine = EngineFixture()
        engine.start_error = "ENGINE_TIMEOUT"
        with self.assertRaisesRegex(SUPERVISOR.Failure, "ENGINE_TIMEOUT"):
            SUPERVISOR.run(engine, request(), ENTRY_SHA)
        self.assertIsNone(engine.container)
        engine = EngineFixture()
        engine.refuse_remove = True
        with self.assertRaisesRegex(SUPERVISOR.Failure, "CONTAINER_CLEANUP_UNCONFIRMED"):
            SUPERVISOR.run(engine, request(), ENTRY_SHA)

    def test_unacknowledged_create_cannot_claim_cleanup_from_an_empty_lookup(self):
        engine = EngineFixture()
        engine.create_error = "ENGINE_TIMEOUT"
        with self.assertRaisesRegex(SUPERVISOR.Failure, "CONTAINER_CLEANUP_UNCONFIRMED"):
            SUPERVISOR.run(engine, request(), ENTRY_SHA)
        self.assertFalse(any(args[:2] == ["container", "start"] for args, _ in engine.calls))

    def test_unsupported_daemon_is_blocked_before_any_create(self):
        for changed in [{"OSType": "windows"}, {"CgroupVersion": "1"}, {"CgroupDriver": "none"}]:
            engine = EngineFixture()
            engine.info.update(changed)
            with self.assertRaisesRegex(SUPERVISOR.Failure, "LINUX_CGROUP_V2_REQUIRED"):
                SUPERVISOR.run(engine, request(), ENTRY_SHA)
            self.assertFalse(any(args[:2] == ["container", "create"] for args, _ in engine.calls))

    def test_only_local_engine_endpoints_are_accepted(self):
        for host in ["unix:///var/run/docker.sock", "unix:///run/user/1000/docker.sock",
                     "npipe:////./pipe/docker_engine"]:
            SUPERVISOR.Docker(["docker"], host)
        for host in ["tcp://localhost:2375", "ssh://production", "unix:///tmp/../docker.sock", "--context=prod"]:
            with self.assertRaisesRegex(SUPERVISOR.Failure, "LOCAL_ENGINE_REQUIRED"):
                SUPERVISOR.Docker(["docker"], host)

    def test_engine_default_timeout_preserves_explicit_operation_budget(self):
        engine = SUPERVISOR.Docker(["docker"], "unix:///var/run/docker.sock")
        with patch.object(SUPERVISOR, "cli_run", return_value=b"[]") as run:
            self.assertEqual([], engine.document(["container", "inspect", ID]))
            run.assert_called_once_with(
                [*engine.command, "container", "inspect", ID], b"", 15, 196608)
            run.reset_mock()
            engine.call(["container", "create"], timeout=30, limit=4096)
            run.assert_called_once_with(
                [*engine.command, "container", "create"], b"", 30, 4096)

    def test_entry_control_contract_rejects_missing_kernel_enforcement(self):
        good = controls()
        self.assertTrue(ENTRY.validate_controls(good)["allChecksPassed"])
        for key, value in [("uid", 0), ("gid", 0), ("capEffective", 1), ("noNewPrivileges", 0), ("seccomp", 0),
                           ("memoryMax", 0), ("swapMax", 100), ("pidsMax", -1), ("cpuQuota", 0),
                           ("cpuPeriod", 1), ("interfaces", ["lo", "eth0"]), ("rootReadOnly", False),
                           ("workMountOptions", ["rw"]), ("workSizeBytes", 33554433),
                           ("coreLimit", [-1, -1]), ("openFileLimit", [1024, 1024])]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                ENTRY.validate_controls({**good, key: value})

    def test_entry_rejects_extra_paths_or_code_before_writing_or_executing(self):
        value = {key: request()[key] for key in ("jobId", "sourceSha256", "runtimeSha256")}
        self.assertEqual(value, ENTRY.validate_request(SUPERVISOR.encode(value), RUNTIME)[0])
        with self.assertRaises(ValueError):
            ENTRY.validate_request(SUPERVISOR.encode({**value, "source": "raise Exception()"}), RUNTIME)
        manifest = json.loads(RUNTIME)
        manifest["files"]["../arbitrary.py"] = {"sha256": "0" * 64, "bytes": 1}
        altered = SUPERVISOR.encode(manifest)
        with self.assertRaises(ValueError):
            ENTRY.validate_request(SUPERVISOR.encode({**value, "runtimeSha256": SUPERVISOR.digest(altered)}), altered)

    def test_supervisor_rejects_boolean_only_receipts_and_wrong_runtime_identity(self):
        for mutate in [
            lambda value: value.update({"controls": {"allChecksPassed": True}}),
            lambda value: value["controls"].update({"memoryMax": 0}),
            lambda value: value["runtimeResult"]["receipt"].update({"loadedTextSha256": "f" * 64}),
            lambda value: value["runtimeResult"]["passedNodes"].append("S06"),
            lambda value: value.update({"entrySha256": "f" * 64}),
        ]:
            engine = EngineFixture()
            engine.mutate_result = mutate
            with self.assertRaises(SUPERVISOR.Failure):
                SUPERVISOR.run(engine, request(), ENTRY_SHA)
            self.assertIsNone(engine.container)

    def test_build_context_pins_base_and_includes_only_verified_runtime_and_build_inputs(self):
        path = Path(__file__).with_name("build_container_context.py")
        spec = importlib.util.spec_from_file_location("build_container_context", path)
        builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(builder)
        for image in ["python:latest", "python:3.12-slim", "python@sha256:short"]:
            with self.assertRaises(ValueError):
                builder.prepare(image)
        # A synthetic reference tests context generation; no registry/image existence is asserted.
        files, manifest = builder.prepare("python@sha256:" + "f" * 64)
        self.assertEqual({"Dockerfile", "requirements.txt", "container_entry.py", "runtime/manifest.json"}
                         | {"runtime/" + name for name in ENTRY.FILES}, set(files))
        self.assertEqual(ENTRY_SHA, manifest["buildArgs"]["ENTRY_SHA256"])
        self.assertEqual(request()["runtimeSha256"], manifest["buildArgs"]["RUNTIME_SHA256"])
        for name, record in manifest["files"].items():
            self.assertEqual(record["bytes"], len(files[name]))
            self.assertEqual(record["sha256"], hashlib.sha256(files[name]).hexdigest())

    def test_real_cli_transport_strips_secrets_context_and_applies_time_and_output_limits(self):
        python = getattr(sys, "_base_executable", sys.executable)
        code = "import os,json; print(json.dumps(sorted(os.environ)))"
        with patch.dict(os.environ, {"MAINTENANCE_AT_SESSION_TOKEN": "SECRET", "OPENAI_API_KEY": "SECRET",
                                     "DOCKER_HOST": "tcp://production:2375", "DOCKER_CONTEXT": "production"}):
            keys = json.loads(SUPERVISOR.cli_run([python, "-I", "-c", code], b"", 3, 4096))
        self.assertNotIn("MAINTENANCE_AT_SESSION_TOKEN", keys)
        self.assertNotIn("OPENAI_API_KEY", keys)
        self.assertNotIn("DOCKER_HOST", keys)
        self.assertNotIn("DOCKER_CONTEXT", keys)
        self.assertIn("DOCKER_CONFIG", keys)
        with self.assertRaisesRegex(SUPERVISOR.Failure, "ENGINE_OUTPUT_LIMIT"):
            SUPERVISOR.cli_run([python, "-I", "-c", "import sys; sys.stdout.write('x'*20000)"], b"", 3, 1024)
        with self.assertRaisesRegex(SUPERVISOR.Failure, "ENGINE_OUTPUT_LIMIT"):
            SUPERVISOR.cli_run([python, "-I", "-c",
                               "import sys; sys.stdout.write('x'*700); sys.stderr.write('y'*700)"], b"", 3, 1024)
        with self.assertRaisesRegex(SUPERVISOR.Failure, "ENGINE_TIMEOUT"):
            SUPERVISOR.cli_run([python, "-I", "-c", "import time; time.sleep(5)"], b"", 0.05, 1024)


if __name__ == "__main__":
    unittest.main()
