"""Acceptance-tool contract tests using EngineFixture only; no real Engine or Linux verdict."""
from __future__ import annotations

import contextlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import container_acceptance_test as acceptance
from test_container_protocol import EngineFixture, ENTRY_SHA, ID, IMAGE, JOB, RUNTIME, request

S = acceptance.supervisor


class CompatibleFixture(EngineFixture):
    """Re-raise errors in the actual imported owner's Failure type."""
    command = ["never-executed-fixture"]

    def __init__(self):
        super().__init__()
        self.info["ServerVersion"] = "fixture-27.0.0"

    def call(self, arguments, payload=b"", timeout=6, limit=196608):
        # The historical fixture imported its own copy of the same supervisor module.
        from test_container_protocol import SUPERVISOR
        try:
            result = super().call(arguments, payload, timeout, limit)
        except SUPERVISOR.Failure as error:
            raise S.Failure(error.code) from None
        if arguments[:2] == ["container", "create"]:
            self.container["Name"] = "/" + arguments[arguments.index("--name") + 1]
            for option, field in (("--memory", "Memory"), ("--pids-limit", "PidsLimit")):
                self.container["HostConfig"][field] = int(arguments[arguments.index(option) + 1])
        return result


class ContainerAcceptanceTest(unittest.TestCase):
    def test_source_check_never_constructs_an_engine(self):
        with patch("sys.argv", ["acceptance", "--check"]), patch.object(S, "Docker") as engine:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                acceptance.main()
            engine.assert_not_called()
            result = json.loads(output.getvalue())
            self.assertFalse(result["engineContacted"])
            self.assertEqual("source_only", result["authority"])

    def test_execution_requires_explicit_run_complete_arguments_and_fresh_output(self):
        cases = [[], ["--check", "--run"], ["--run"], ["--recover", "missing"],
                 ["--check", "--image-id", IMAGE], ["--check", "--host", "tcp://remote:2375"]]
        for args in cases:
            with self.subTest(args=args), patch("sys.argv", ["acceptance", *args]), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                acceptance.main()

    def test_suite_calls_real_supervisor_owners_with_fixture_and_never_starts_bad_configs(self):
        engine = CompatibleFixture()

        def cancelled(observed_engine, ident):
            self.assertEqual(ID, ident)
            self.assertIs(engine, observed_engine)
            return {"runningBeforeCliKill": True, "cliExited": True,
                    "containerStateAfterCliKill": "running", "authority": "protocol_fixture"}

        rows = acceptance.exercise(engine, RUNTIME, IMAGE, ENTRY_SHA,
                                   interruptor=cancelled, job_factory=lambda: JOB)
        self.assertEqual(8, len(rows))
        self.assertEqual("fixture-27.0.0", rows[0]["ServerVersion"])
        self.assertIsNone(engine.container)
        self.assertEqual(1, sum(args[:2] == ["container", "start"] for args, _ in engine.calls))
        self.assertEqual("CONTAINER_OWNERSHIP_MISMATCH", rows[-1]["result"]["rejectedCode"])
        self.assertFalse(rows[3]["result"]["containerWasStarted"])
        self.assertFalse(rows[4]["result"]["containerWasStarted"])
        self.assertTrue(all(args[0] not in {"pull", "build", "exec"} for args, _ in engine.calls))

    def test_missing_or_unsafe_engine_version_stops_before_creation(self):
        for version in (None, "", "v" * 81, "27.0.0\nPRIVATE", {"unexpected": "PRIVATE"}):
            engine = CompatibleFixture()
            engine.info["ServerVersion"] = version
            with self.subTest(version=version), self.assertRaisesRegex(S.Failure, "ENGINE_VERSION_UNCONFIRMED"):
                acceptance.exercise(engine, RUNTIME, IMAGE, ENTRY_SHA)
            self.assertEqual([["info", "--format", "{{json .}}"]], [args for args, _ in engine.calls])

    def test_real_local_child_is_interrupted_with_clean_environment_after_running_observation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            script = root / "attach_fixture.py"
            marker = root / "running"
            script.write_text(
                "import os, pathlib, sys\n"
                "assert 'PRIVATE_SENTINEL' not in os.environ and 'DOCKER_HOST' not in os.environ\n"
                "assert pathlib.Path(os.environ['DOCKER_CONFIG']).is_dir()\n"
                "pathlib.Path(sys.argv[1]).write_text('ready')\n"
                "sys.stdin.buffer.read()\n", encoding="utf-8")

            def inspect(arguments):
                self.assertEqual(["container", "inspect", ID], arguments)
                return [{"Id": ID, "State": {"Status": "running" if marker.exists() else "created"}}]

            engine = SimpleNamespace(command=[sys.executable, str(script), str(marker)], document=inspect)
            with patch.dict(os.environ, {"PRIVATE_SENTINEL": "PRIVATE", "DOCKER_HOST": "tcp://remote:2375"}):
                result = acceptance.interrupt_attach(engine, ID)
            self.assertTrue(result["runningBeforeCliKill"])
            self.assertTrue(result["cliExited"])

    def test_cli_rejects_output_conflicts_missing_parent_and_remote_endpoint_without_engine_commands(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "docker"
            executable.write_bytes(b"fixture-not-executed")
            output = root / "existing.json"
            output.write_bytes(b"retained")
            journal_output = root / "journal-conflict.json"
            journal = journal_output.with_name(journal_output.name + ".journal.jsonl")
            journal.write_bytes(b"retained-journal")
            base = ["acceptance", "--run", "--docker", str(executable), "--image-id", IMAGE]
            cases = [
                [*base, "--json-out", str(output)],
                [*base, "--json-out", str(journal_output)],
                [*base, "--json-out", str(root / "missing" / "result.json")],
                [*base, "--json-out", str(root / "fresh.json"), "--host", "tcp://remote:2375"],
            ]
            for args in cases:
                with self.subTest(args=args), patch("sys.argv", args), \
                        patch.object(S, "cli_run") as transport, \
                        contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    acceptance.main()
                transport.assert_not_called()
            self.assertEqual(b"retained", output.read_bytes())
            self.assertEqual(b"retained-journal", journal.read_bytes())

    def test_cli_failure_preserves_partial_observations_and_journal_without_raw_errors(self):
        engine = CompatibleFixture()
        engine.start_error = "ENGINE_TIMEOUT"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "docker"
            executable.write_bytes(b"fixture-not-executed")
            output = root / "result.json"
            with patch("sys.argv", ["acceptance", "--run", "--docker", str(executable),
                                    "--image-id", IMAGE, "--json-out", str(output)]), \
                    patch.object(S, "Docker", return_value=engine), \
                    contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit):
                acceptance.main()
            result = json.loads(output.read_bytes())
            self.assertFalse(result["completed"])
            self.assertFalse(result["candidateExecutionAllowed"])
            self.assertEqual("ENGINE_TIMEOUT", result["errorCode"])
            self.assertEqual("engine_identity", result["observations"][0]["check"])
            journal = output.with_name(output.name + ".journal.jsonl")
            rows = [json.loads(line) for line in journal.read_bytes().splitlines()]
            self.assertEqual(["PREPARED", "ACKNOWLEDGED"], [row["phase"] for row in rows[1:]])
            self.assertIsNone(engine.container)

    def test_invalid_recovery_header_is_recorded_without_any_engine_call(self):
        for body in (b"", b"{", b"[]", b'{"imageId":"PRIVATE"}'):
            with self.subTest(body=body), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                executable = root / "docker"
                executable.write_bytes(b"fixture-not-executed")
                journal = root / "run.jsonl"
                journal.write_bytes(body)
                output = root / "result.json"
                engine = CompatibleFixture()
                with patch("sys.argv", ["acceptance", "--recover", str(journal), "--docker", str(executable),
                                        "--json-out", str(output)]), \
                        patch.object(S, "Docker", return_value=engine), \
                        contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit):
                    acceptance.main()
                result = json.loads(output.read_bytes())
                self.assertFalse(result["completed"])
                self.assertTrue(result["recoveryOnly"])
                self.assertIn(result["errorCode"], {"JOURNAL_INVALID", "JSON_INVALID"})
                self.assertEqual([], engine.calls)
                self.assertNotIn("PRIVATE", output.read_text())

    def test_create_intent_and_acknowledgement_are_durable_before_return(self):
        with tempfile.TemporaryFile("w+b") as journal:
            engine = acceptance.JournalEngine(CompatibleFixture(), journal)
            arguments = S.create_arguments(request(), JOB.replace("-", ""), ENTRY_SHA)
            raw = engine.call(arguments)
            self.assertEqual(ID, raw.decode().strip())
            journal.seek(0)
            rows = [json.loads(line) for line in journal]
            self.assertEqual(["PREPARED", "ACKNOWLEDGED"], [row["phase"] for row in rows])
            self.assertEqual(ID, rows[-1]["containerId"])
            self.assertEqual(JOB.replace("-", ""), rows[-1]["attempt"])

    def test_lost_create_ack_stays_unknown_even_when_lookup_is_empty(self):
        engine = CompatibleFixture()
        engine.create_error = "ENGINE_TIMEOUT"
        with tempfile.TemporaryFile("w+b") as journal:
            recording = acceptance.JournalEngine(engine, journal)
            with self.assertRaisesRegex(S.Failure, "ENGINE_TIMEOUT"):
                recording.call(S.create_arguments(request(), JOB.replace("-", ""), ENTRY_SHA))
            journal.seek(0)
            rows = [json.loads(line) for line in journal]
            self.assertEqual(["PREPARED"], [row["phase"] for row in rows])
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "journal.jsonl"
            identity = self.identity()
            path.write_bytes(S.encode(identity) + b"\n" + S.encode(rows[0]) + b"\n")
            result, confirmed = acceptance.recover(engine, path, identity)
            self.assertFalse(confirmed)
            self.assertFalse(result[0]["creationAcknowledged"])
            self.assertTrue(result[0]["cleanup"]["containerAbsent"])

    def test_recovery_checks_full_journal_before_any_engine_action(self):
        identity = self.identity()
        prepared = dict(phase="PREPARED", jobId=JOB, attempt=JOB.replace("-", ""))
        cases = [
            [{**identity, "runtimeSha256": "f" * 64}, prepared],
            [identity, {**prepared, "jobId": "../foreign"}],
            [identity, prepared, prepared],
            [identity, {**prepared, "phase": "ACKNOWLEDGED", "containerId": ID}],
            [identity, prepared, {**prepared, "phase": "ACKNOWLEDGED", "containerId": ID,
                                 "attempt": "f" * 32}],
            [identity, prepared, {**prepared, "unexpectedCommand": "PRIVATE"}],
        ]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "journal.jsonl"
            for rows in cases:
                with self.subTest(rows=rows):
                    engine = CompatibleFixture()
                    path.write_bytes(b"\n".join(S.encode(row) for row in rows) + b"\n")
                    with self.assertRaises(S.Failure):
                        acceptance.recover(engine, path, identity)
                    self.assertEqual([], engine.calls)

    def test_recovery_requires_exact_container_id_image_and_labels_and_preserves_wrong_target(self):
        identity = self.identity()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "journal.jsonl"
            rows = [identity, dict(phase="PREPARED", jobId=JOB, attempt=JOB.replace("-", "")),
                    dict(phase="ACKNOWLEDGED", jobId=JOB, attempt=JOB.replace("-", ""), containerId=ID)]
            path.write_bytes(b"\n".join(S.encode(row) for row in rows) + b"\n")
            for mutation in ("Id", "Image", "entry", "attempt"):
                engine = CompatibleFixture()
                engine.call(S.create_arguments(request(), JOB.replace("-", ""), ENTRY_SHA))
                if mutation in ("Id", "Image"):
                    engine.container[mutation] = "f" * 64
                else:
                    engine.container["Config"]["Labels"][S.MANAGED + "." + mutation] = "f" * 64
                with self.subTest(mutation=mutation), self.assertRaises(S.Failure):
                    acceptance.recover(engine, path, identity)
                self.assertIsNotNone(engine.container)
                self.assertFalse(any(args[:2] == ["container", "rm"] for args, _ in engine.calls))

    def test_acknowledged_recovery_and_repeat_cleanup_are_idempotent(self):
        identity = self.identity()
        engine = CompatibleFixture()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "journal.jsonl"
            with path.open("w+b") as journal:
                recording = acceptance.JournalEngine(engine, journal)
                recording.event(identity)
                recording.call(S.create_arguments(request(), JOB.replace("-", ""), ENTRY_SHA))
            for _ in range(2):
                result, confirmed = acceptance.recover(engine, path, identity)
                self.assertTrue(confirmed)
                self.assertTrue(result[0]["cleanup"]["containerAbsent"])
            self.assertIsNone(engine.container)

    @staticmethod
    def identity():
        return {"imageId": IMAGE, "runtimeSha256": request()["runtimeSha256"], "entrySha256": ENTRY_SHA}


if __name__ == "__main__":
    unittest.main()
