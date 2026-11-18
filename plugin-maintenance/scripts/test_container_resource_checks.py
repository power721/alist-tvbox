"""Resource acceptance policy tests use only deterministic receipts and EngineFixture."""
from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import container_resource_checks as checks
import container_resource_acceptance_test as cli
import container_resource_payload as payload_module
from test_container_acceptance import CompatibleFixture
from test_container_protocol import ENTRY_SHA, IMAGE, RUNTIME, controls, request
import container_acceptance_test as acceptance

S = acceptance.supervisor


def receipt(mode, observation):
    value = request()
    return {"schemaVersion": 1, "authority": "fixed_resource_probe_observed",
            "scenario": mode, "entrySha256": ENTRY_SHA, **{key: value[key] for key in (
                "jobId", "sourceSha256", "runtimeSha256")},
            "controls": {**controls(), "allChecksPassed": True}, "observation": observation}


class ResourceFixture(CompatibleFixture):
    resource_mutation = None

    def call(self, arguments, payload=b"", timeout=6, limit=196608):
        if arguments[:2] == ["container", "create"]:
            result = super().call(arguments, payload, timeout, limit)
            self.container["Config"]["Cmd"] = arguments[arguments.index(IMAGE) + 1:]
            if self.resource_mutation:
                self.resource_mutation(self.container)
            return result
        if arguments[:2] == ["container", "start"] and self.container["Config"]["Cmd"][1] == "-c":
            mode = self.container["Config"]["Cmd"][3]
            self.calls.append((arguments, payload))
            if self.start_error:
                raise S.Failure(self.start_error)
            if mode == "timeout":
                self.container["State"] = {"Status": "running", "OOMKilled": False}
                raise S.Failure("ENGINE_TIMEOUT")
            value = S.decode(payload)
            observation = {
                "memory": {"before": {"max": 0, "oom": 0, "oom_kill": 0},
                           "after": {"max": 1, "oom": 1, "oom_kill": 1}, "childExitCode": -9},
                "pids": {"before": {"max": 0}, "after": {"max": 1}, "forkErrno": 11,
                         "expectedErrno": 11, "childrenCreated": 62, "pidsAtRefusal": 64},
                "cpu": {"before": {"nr_throttled": 1, "throttled_usec": 1000, "usage_usec": 1000},
                        "after": {"nr_throttled": 2, "throttled_usec": 3000, "usage_usec": 3000},
                        "elapsedMilliseconds": 2000},
            }[mode]
            self.container["State"] = {"Status": "exited", "ExitCode": 0, "OOMKilled": False}
            result = {**receipt(mode, observation), **value, "entrySha256": ENTRY_SHA,
                      "controls": {**controls(), "allChecksPassed": True}}
            if self.mutate_result:
                self.mutate_result(result)
            return S.encode(result)
        return super().call(arguments, payload, timeout, limit)


class ResourceChecksTest(unittest.TestCase):
    def validate(self, mode, observation):
        checks.validate_observation(S, receipt(mode, observation), request(), mode, ENTRY_SHA)

    def test_memory_requires_kernel_oom_counters_and_sigkill(self):
        self.validate("memory", {"before": {"max": 0, "oom": 0, "oom_kill": 0},
                                 "after": {"max": 1, "oom": 1, "oom_kill": 1}, "childExitCode": -9})
        for change in ({"childExitCode": 0},
                       {"after": {"max": 1, "oom": 1, "oom_kill": 0}}):
            value = {"before": {"max": 0, "oom": 0, "oom_kill": 0},
                     "after": {"max": 1, "oom": 1, "oom_kill": 1}, "childExitCode": -9}
            value.update(change)
            with self.subTest(change=change), self.assertRaisesRegex(S.Failure, "RESOURCE_OOM_UNCONFIRMED"):
                self.validate("memory", value)

    def test_pids_requires_limit_event_and_refused_fork_at_exact_cap(self):
        self.validate("pids", {"before": {"max": 0}, "after": {"max": 1}, "forkErrno": 11,
                              "expectedErrno": 11, "childrenCreated": 62, "pidsAtRefusal": 64})
        for change in ({"forkErrno": 12}, {"pidsAtRefusal": 63}, {"after": {"max": 0}}):
            value = {"before": {"max": 0}, "after": {"max": 1}, "forkErrno": 11,
                     "expectedErrno": 11, "childrenCreated": 62, "pidsAtRefusal": 64}
            value.update(change)
            with self.subTest(change=change), self.assertRaisesRegex(S.Failure, "RESOURCE_PIDS_UNCONFIRMED"):
                self.validate("pids", value)

    def test_cpu_requires_kernel_throttling_counters_during_bounded_load(self):
        self.validate("cpu", {"before": {"nr_throttled": 1, "throttled_usec": 1000, "usage_usec": 1000},
                              "after": {"nr_throttled": 2, "throttled_usec": 3000, "usage_usec": 3000},
                              "elapsedMilliseconds": 2000})
        with self.assertRaisesRegex(S.Failure, "RESOURCE_CPU_UNCONFIRMED"):
            self.validate("cpu", {"before": {"nr_throttled": 1, "throttled_usec": 1000, "usage_usec": 1000},
                                  "after": {"nr_throttled": 1, "throttled_usec": 1000, "usage_usec": 3000},
                                  "elapsedMilliseconds": 2000})

    def test_invalid_receipt_controls_counters_and_command_cannot_pass(self):
        value = receipt("memory", {"before": {"oom": 0}, "after": {"oom": 1, "oom_kill": 1},
                                   "childExitCode": -9})
        value["controls"]["memoryMax"] = 0
        with self.assertRaisesRegex(S.Failure, "RESOURCE_CONTROLS_UNCONFIRMED"):
            checks.validate_observation(S, value, request(), "memory", ENTRY_SHA)
        value["controls"] = {**controls(), "allChecksPassed": True}
        value["scenario"] = "pids"
        with self.assertRaisesRegex(S.Failure, "RESOURCE_PIDS_UNCONFIRMED"):
            checks.validate_observation(S, value, request(), "pids", ENTRY_SHA)
        with self.assertRaisesRegex(ValueError, "RESOURCE_SCENARIO_INVALID"):
            checks.command("source", ENTRY_SHA, checks.PAYLOAD.read_bytes())

    def test_all_fixed_scenarios_use_supervisor_owners_and_confirm_cleanup(self):
        engine = ResourceFixture()
        rows = checks.exercise(engine, RUNTIME, IMAGE, ENTRY_SHA, payload_bytes=checks.PAYLOAD.read_bytes(),
                               supervisor=S, observations=[])
        self.assertEqual(["resource_" + mode for mode in checks.MODES], [row["check"] for row in rows])
        self.assertEqual(["exited", "exited", "exited", "running"],
                         [row["containerState"]["Status"] for row in rows])
        self.assertTrue(all(row["cleanup"]["containerAbsent"] for row in rows))
        self.assertIsNone(engine.container)
        self.assertEqual(4, sum(args[:2] == ["container", "create"] for args, _ in engine.calls))

    def test_weak_controls_fail_before_start_and_owned_container_is_removed(self):
        for index, mutation in enumerate((
            lambda doc: doc["HostConfig"].update(NetworkMode="host"),
            lambda doc: doc["HostConfig"].update(Memory=0),
            lambda doc: doc["Config"]["Env"].append("PRIVATE_SENTINEL=secret"),
        )):
            engine = ResourceFixture()
            engine.resource_mutation = mutation
            with self.subTest(index=index), self.assertRaises(S.Failure):
                checks.exercise(engine, RUNTIME, IMAGE, ENTRY_SHA, payload_bytes=checks.PAYLOAD.read_bytes(),
                                supervisor=S, observations=[])
            self.assertFalse(any(args[:2] == ["container", "start"] for args, _ in engine.calls))
            self.assertIsNone(engine.container)

    def test_failed_or_unacknowledged_runs_never_add_passed_observations(self):
        for field, value, expected in (
            ("create_error", "ENGINE_TIMEOUT", "CONTAINER_CLEANUP_UNCONFIRMED"),
            ("start_error", "ENGINE_TIMEOUT", "ENGINE_TIMEOUT"),
            ("refuse_remove", True, "CONTAINER_CLEANUP_UNCONFIRMED"),
        ):
            engine = ResourceFixture()
            setattr(engine, field, value)
            rows = []
            with self.subTest(field=field), self.assertRaisesRegex(S.Failure, expected):
                checks.exercise(engine, RUNTIME, IMAGE, ENTRY_SHA, payload_bytes=checks.PAYLOAD.read_bytes(),
                                supervisor=S, observations=rows)
            self.assertEqual([], rows)

    def test_wrong_command_or_attempt_is_not_accepted_or_deleted(self):
        engine = ResourceFixture()
        engine.call(S.create_arguments(request(), "f" * 32, ENTRY_SHA))
        with self.assertRaisesRegex(S.Failure, "RESOURCE_COMMAND_MISMATCH"):
            checks.validate_created(S, engine.container, request(), "f" * 32, "memory", ENTRY_SHA,
                                    checks.PAYLOAD.read_bytes())
        with self.assertRaisesRegex(S.Failure, "CONTAINER_OWNERSHIP_MISMATCH"):
            S.cleanup(engine, request()["jobId"])
        self.assertIsNotNone(engine.container)

    def test_counter_reset_booleans_and_receipt_identity_cannot_claim_enforcement(self):
        good = {"before": {"max": 1, "oom": 1, "oom_kill": 1},
                "after": {"max": 2, "oom": 2, "oom_kill": 2}, "childExitCode": -9}
        for change in ({"after": {"max": 0, "oom": 2, "oom_kill": 2}},
                       {"after": {"max": True, "oom": 2, "oom_kill": 2}}):
            with self.subTest(change=change), self.assertRaises(S.Failure):
                self.validate("memory", {**good, **change})
        for change in ({"jobId": "foreign"}, {"runtimeSha256": "f" * 64}, {"schemaVersion": True}):
            with self.subTest(change=change), self.assertRaisesRegex(S.Failure, "RESOURCE_RECEIPT_MISMATCH"):
                checks.validate_observation(S, {**receipt("memory", good), **change},
                                            request(), "memory", ENTRY_SHA)

    def test_payload_cannot_run_workload_on_the_host_or_before_image_validation(self):
        with patch.object(sys, "argv", ["resource_payload.py", "memory", ENTRY_SHA]), \
                patch.object(payload_module, "memory_observation") as workload, self.assertRaisesRegex(
                    ValueError, "RESOURCE_IMAGE_REQUIRED"):
            payload_module.execute()
        workload.assert_not_called()

    def test_cli_source_check_never_contacts_engine_or_runs_payload(self):
        with patch.object(sys, "argv", ["resources", "--check"]), patch.object(S, "Docker") as docker, \
                patch.object(payload_module, "execute") as payload:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                cli.main()
        docker.assert_not_called()
        payload.assert_not_called()
        self.assertFalse(json.loads(output.getvalue())["engineContacted"])

    def test_cli_records_all_observations_journal_and_repeated_recovery(self):
        real_exercise = acceptance.exercise
        engine = ResourceFixture()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "docker"
            executable.write_bytes(b"fixture-only")
            output = root / "result.json"

            def original_checks(*args, **kwargs):
                return real_exercise(*args, **kwargs, interruptor=lambda *_: {
                    "authority": "protocol_fixture", "runningBeforeCliKill": True, "cliExited": True})

            with patch.object(sys, "argv", ["resources", "--run", "--docker", str(executable),
                                            "--image-id", IMAGE, "--json-out", str(output)]), \
                    patch.object(S, "Docker", return_value=engine), \
                    patch.object(acceptance, "exercise", side_effect=original_checks), \
                    contextlib.redirect_stdout(io.StringIO()):
                cli.main()
            result = json.loads(output.read_bytes())
            self.assertTrue(result["completed"])
            self.assertFalse(result["candidateExecutionAllowed"])
            self.assertEqual(12, len(result["observations"]))
            journal = output.with_name(output.name + ".journal.jsonl")
            self.assertEqual(19, len(journal.read_bytes().splitlines()))
            for sequence in (1, 2):
                recovered = root / f"recover-{sequence}.json"
                with patch.object(sys, "argv", ["resources", "--recover", str(journal),
                                                "--docker", str(executable), "--json-out", str(recovered)]), \
                        patch.object(S, "Docker", return_value=engine), \
                        contextlib.redirect_stdout(io.StringIO()):
                    cli.main()
                self.assertTrue(json.loads(recovered.read_bytes())["completed"])

    def test_cli_rejects_bad_recovery_without_engine_action_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "docker"
            executable.write_bytes(b"fixture-only")
            journal = root / "invalid.jsonl"
            journal.write_bytes(b"[]\n")
            output = root / "result.json"
            engine = ResourceFixture()
            arguments = ["resources", "--recover", str(journal), "--docker", str(executable),
                         "--json-out", str(output)]
            with patch.object(sys, "argv", arguments), patch.object(S, "Docker", return_value=engine), \
                    contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit):
                cli.main()
            self.assertFalse(json.loads(output.read_bytes())["completed"])
            self.assertEqual([], engine.calls)
            before = output.read_bytes()
            with patch.object(sys, "argv", arguments), contextlib.redirect_stderr(io.StringIO()), \
                    self.assertRaises(SystemExit):
                cli.main()
            self.assertEqual(before, output.read_bytes())

    def test_payload_change_after_intent_never_changes_submitted_code(self):
        frozen = checks.PAYLOAD.read_bytes()
        real_exercise = acceptance.exercise
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "docker"
            executable.write_bytes(b"fixture-only")
            changed_payload = root / "payload.py"
            changed_payload.write_bytes(frozen)
            output = root / "result.json"

            class MutatingFixture(ResourceFixture):
                def call(self, arguments, *args, **kwargs):
                    if arguments[0] == "info":
                        changed_payload.write_bytes(b"raise RuntimeError('MUTATED_PAYLOAD_SENTINEL')\n")
                    return super().call(arguments, *args, **kwargs)

            engine = MutatingFixture()

            def original_checks(*args, **kwargs):
                return real_exercise(*args, **kwargs, interruptor=lambda *_: {
                    "authority": "protocol_fixture", "runningBeforeCliKill": True, "cliExited": True})

            with patch.object(checks, "PAYLOAD", changed_payload), \
                    patch.object(sys, "argv", ["resources", "--run", "--docker", str(executable),
                                                "--image-id", IMAGE, "--json-out", str(output)]), \
                    patch.object(S, "Docker", return_value=engine), \
                    patch.object(acceptance, "exercise", side_effect=original_checks), \
                    contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit):
                cli.main()
            result = json.loads(output.read_bytes())
            self.assertFalse(result["completed"])
            self.assertEqual("ACCEPTANCE_INPUT_CHANGED", result["errorCode"])
            resource_commands = [args[args.index(IMAGE) + 1:] for args, _ in engine.calls
                                 if args[:2] == ["container", "create"] and "-c" in args]
            self.assertEqual(4, len(resource_commands))
            self.assertTrue(all(cmd[2] == frozen.decode("utf-8") for cmd in resource_commands))
            self.assertTrue(all("MUTATED_PAYLOAD_SENTINEL" not in cmd[2] for cmd in resource_commands))


if __name__ == "__main__":
    unittest.main()
