"""Protocol negative tests with real bounded local pipes, never importing submitted code."""
from __future__ import annotations

import json
import importlib.util
from pathlib import Path
import sys
import threading
import unittest
from unittest.mock import patch

import test_container_protocol as F

_spec = importlib.util.spec_from_file_location(
    "managed_protocol_test", Path(__file__).with_name("managed_source_protocol.py"))
M = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(M)
M.S = F.SUPERVISOR
M.E = F.ENTRY

SOURCE = b"# opaque source bytes\n"
ENTRY_SHA = "e" * 64
PAYLOAD_SHA = "f" * 64


def request():
    return {"schemaVersion": 1, "jobId": F.JOB, "imageId": F.IMAGE,
            "sourceBytes": len(SOURCE), "sourceSha256": M.S.digest(SOURCE),
            "runtimeSha256": M.S.digest(F.RUNTIME)}


def ready():
    return {"schemaVersion": 1, "phase": "ready_before_source", "jobId": F.JOB,
            "entrySha256": ENTRY_SHA, "payloadSha256": PAYLOAD_SHA,
            "runtimeSha256": request()["runtimeSha256"],
            "controls": M.E.validate_controls(F.controls())}


class Engine(F.EngineFixture):
    command = ["fake-engine"]

    def call(self, arguments, payload=b"", timeout=6, limit=196608):
        if arguments[:2] == ["image", "inspect"]:
            self.calls.append((arguments, payload))
            return M.S.encode([{"Id": F.IMAGE, "Os": "linux", "Config": {"Labels": {
                M.S.MANAGED + ".runtime": request()["runtimeSha256"],
                M.S.MANAGED + ".managed-entry": ENTRY_SHA,
                M.S.MANAGED + ".managed-payload": PAYLOAD_SHA}}}])
        body = super().call(arguments, payload, timeout, limit)
        if arguments[:2] == ["container", "create"]:
            self.container["Config"]["Cmd"] = ["-I", M.ENTRY]
        return body


class ProtocolTest(unittest.TestCase):
    def exchange(self, program, value=None, timeout=2):
        return M.exchange([sys.executable, "-I", "-c", program], request() if value is None else value,
                          SOURCE, ENTRY_SHA, PAYLOAD_SHA, timeout=timeout)

    def child(self, observation=None, tail=""):
        value = ready() if observation is None else observation
        return ("import json,sys,time; header=json.loads(sys.stdin.buffer.readline()); "
                "print(" + repr(json.dumps(value)) + ",flush=True); " + tail)

    def test_ack_before_source_and_post_ack_identity_claims_are_not_promoted(self):
        tail = ("ack=json.loads(sys.stdin.buffer.readline()); body=sys.stdin.buffer.read(); "
                "assert ack=={'operation':'execute','jobId':header['jobId']}; "
                "assert body==" + repr(SOURCE) + "; "
                "print(json.dumps({'status':'init_returned','authority':'kernel_trusted',"
                "'controls':{'allChecksPassed':True},'secret':'PRIVATE'}))")
        result = self.exchange(self.child(tail=tail))
        self.assertEqual("init_returned", result["untrustedRuntimeSummary"]["status"])
        self.assertEqual("untrusted_source_output", result["untrustedRuntimeSummary"]["evidenceClass"])
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertFalse(result["businessChainVerified"])

    def test_bad_controls_receive_no_source_or_ack(self):
        popen = M.subprocess.Popen
        written = []

        class RecordedInput:
            def __init__(self, stream):
                self.stream = stream

            def write(self, value):
                written.append(value)
                return self.stream.write(value)

            def __getattr__(self, name):
                return getattr(self.stream, name)

        def started(*args, **kwargs):
            process = popen(*args, **kwargs)
            process.stdin = RecordedInput(process.stdin)
            return process

        for changes in ({"uid": 0}, {"interfaces": ["lo", "eth0"]}, {"memoryMax": 0}, {"pidsMax": 0}):
            row = ready()
            row["controls"].update(changes)
            written.clear()
            with patch.object(M.subprocess, "Popen", side_effect=started), self.subTest(
                    changes=changes), self.assertRaises(M.S.Failure):
                self.exchange(self.child(row, "sys.stdin.buffer.read(); time.sleep(4)"), timeout=0.5)
            self.assertEqual(1, len(written))
            self.assertNotIn("operation", json.loads(written[0]))
            self.assertNotIn(SOURCE, written[0])

    def test_wrong_identity_and_duplicate_ready_fields_fail_before_source(self):
        for key in ("jobId", "entrySha256", "payloadSha256", "runtimeSha256", "phase"):
            row = ready()
            row[key] = "wrong"
            with self.subTest(key=key), self.assertRaises(M.S.Failure):
                self.exchange(self.child(row, "sys.stdin.buffer.read()"))
        with self.assertRaises(M.S.Failure):
            self.exchange("import sys; sys.stdin.buffer.readline(); print('{\"phase\":1,\"phase\":2}',flush=True)")

    def test_request_rejects_source_drift_extra_fields_and_boolean_lengths(self):
        for change in ({"sourceSha256": "0" * 64}, {"sourceBytes": True},
                       {"sourceBytes": len(SOURCE) + 1}, {"command": "anything"}, {"imageId": "latest"}):
            with self.subTest(change=change), self.assertRaises(M.S.Failure):
                M.validate_request({**request(), **change}, SOURCE)
        with self.assertRaises(M.S.Failure):
            M.validate_request(request(), b"x" * (M.SOURCE_LIMIT + 1))

    def test_real_pipe_deadline_and_output_limits_cover_both_phases(self):
        with self.assertRaisesRegex(M.S.Failure, "ENGINE_TIMEOUT"):
            self.exchange("import sys,time; sys.stdin.buffer.readline(); time.sleep(5)", timeout=0.1)
        with self.assertRaises(M.S.Failure):
            self.exchange("import sys; sys.stdin.buffer.readline(); print('x'*300000,flush=True)")
        with self.assertRaisesRegex(M.S.Failure, "ENGINE_OUTPUT_LIMIT"):
            self.exchange(self.child(tail="sys.stdin.buffer.readline(); sys.stdin.buffer.read(); "
                                     "sys.stderr.write('x'*300000); sys.stderr.flush()"))

    def test_environment_has_no_credentials_and_only_bounded_status_is_returned(self):
        tail = ("import os; sys.stdin.buffer.readline(); sys.stdin.buffer.read(); "
                "assert 'FN_NAS_PASSWORD' not in os.environ; "
                "assert 'MAINTENANCE_AT_SESSION_TOKEN' not in os.environ; "
                "print(json.dumps({'status':'PRIVATE_SECRET'}))")
        with patch.dict("os.environ", {"FN_NAS_PASSWORD": "PRIVATE",
                                      "MAINTENANCE_AT_SESSION_TOKEN": "PRIVATE"}):
            result = self.exchange(self.child(tail=tail))
        self.assertEqual("invalid_output", result["untrustedRuntimeSummary"]["status"])
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_container_creation_reuses_fixed_security_checks_and_distinct_ownership(self):
        engine = Engine()
        engine.command = ["fake-engine"]

        def exchanged(*args, **kwargs):
            engine.container["State"] = {"Status": "exited", "ExitCode": 0, "OOMKilled": False}
            return {"preSourceReady": ready(), "businessChainVerified": False}

        with patch.object(M, "exchange", side_effect=exchanged):
            result = M.run(engine, request(), SOURCE, ENTRY_SHA, PAYLOAD_SHA)
        self.assertTrue(result["cleanup"]["containerAbsent"])
        self.assertIsNone(engine.container)
        args = next(args for args, _ in engine.calls if args[:2] == ["container", "create"])
        self.assertIn(M.S.MANAGED + ".kind=" + M.KIND, args)
        self.assertNotIn("--mount", args)
        self.assertNotIn("--publish", args)

    def test_create_has_a_separate_fixed_budget_without_extending_source_runtime(self):
        engine = Engine()
        original = engine.call
        budgets = []
        def called(arguments, *args, **kwargs):
            if arguments[:2] == ["container", "create"]:
                budgets.append(kwargs.get("timeout"))
            return original(arguments, *args, **kwargs)
        engine.call = called
        def exchanged(*args, **kwargs):
            engine.container["State"] = {"Status": "exited", "ExitCode": 0, "OOMKilled": False}
            return {}
        with patch.object(M, "exchange", side_effect=exchanged):
            result = M.run(engine, request(), SOURCE, ENTRY_SHA, PAYLOAD_SHA)
        self.assertEqual([30], budgets)
        self.assertEqual(30, result["policy"]["createTimeoutSeconds"])
        self.assertEqual(20, result["policy"]["runtimeTimeoutSeconds"])
        self.assertEqual("none", result["policy"]["network"])

    def test_weakened_or_foreign_container_never_reaches_source_exchange(self):
        for field, value in (("NetworkMode", "bridge"), ("Memory", 0), ("PidsLimit", -1),
                             ("Binds", ["/production:/data"]), ("Privileged", True)):
            engine = Engine()
            engine.mutate_created = lambda row, k=field, v=value: row["HostConfig"].update({k: v})
            with patch.object(M, "exchange") as exchanged, self.assertRaises(M.S.Failure):
                M.run(engine, request(), SOURCE, ENTRY_SHA, PAYLOAD_SHA)
            exchanged.assert_not_called()
            self.assertIsNone(engine.container)
        engine = Engine()
        engine.mutate_created = lambda row: row["Config"]["Labels"].update({M.S.MANAGED + ".kind": "foreign"})
        with patch.object(M, "exchange") as exchanged, self.assertRaisesRegex(
                M.S.Failure, "CONTAINER_CLEANUP_UNCONFIRMED"):
            M.run(engine, request(), SOURCE, ENTRY_SHA, PAYLOAD_SHA)
        exchanged.assert_not_called()
        self.assertFalse(any(args[:2] == ["container", "rm"] for args, _ in engine.calls))

    def test_lost_create_ack_and_exchange_timeout_cannot_claim_success(self):
        engine = Engine()
        engine.create_error = "ENGINE_TIMEOUT"
        with self.assertRaisesRegex(M.S.Failure, "CONTAINER_CLEANUP_UNCONFIRMED"):
            M.run(engine, request(), SOURCE, ENTRY_SHA, PAYLOAD_SHA)
        engine = Engine()
        engine.command = ["fake-engine"]
        with patch.object(M, "exchange", side_effect=M.S.Failure("ENGINE_TIMEOUT")), self.assertRaisesRegex(
                M.S.Failure, "ENGINE_TIMEOUT"):
            M.run(engine, request(), SOURCE, ENTRY_SHA, PAYLOAD_SHA)
        self.assertIsNone(engine.container)

    def test_lost_remove_reply_is_reconciled_by_full_id_without_repeating_remove(self):
        engine = Engine()
        original = engine.call
        removals = []
        def called(arguments, *args, **kwargs):
            if arguments[:2] == ["container", "rm"]:
                removals.append(arguments)
                engine.container = None
                raise M.S.Failure("ENGINE_TIMEOUT")
            return original(arguments, *args, **kwargs)
        engine.call = called
        with patch.object(M, "exchange", side_effect=M.S.Failure("ENGINE_TIMEOUT")), \
                self.assertRaisesRegex(M.S.Failure, "^ENGINE_TIMEOUT$"):
            M.run(engine, request(), SOURCE, ENTRY_SHA, PAYLOAD_SHA)
        self.assertEqual(1, len(removals))
        self.assertTrue(any("id=" + F.ID in args for args, _ in engine.calls))
        self.assertIsNone(engine.container)

    def test_lost_remove_reply_with_present_container_or_failed_lookup_stays_unconfirmed(self):
        for defect in ("present", "query_failed"):
            engine = Engine()
            original = engine.call
            removals = []
            def called(arguments, *args, **kwargs):
                if arguments[:2] == ["container", "rm"]:
                    removals.append(arguments)
                    raise M.S.Failure("ENGINE_TIMEOUT")
                if defect == "query_failed" and "id=" + F.ID in arguments:
                    raise M.S.Failure("ENGINE_COMMAND_FAILED")
                return original(arguments, *args, **kwargs)
            engine.call = called
            with self.subTest(defect=defect), patch.dict(M.POLICY, cleanupConfirmationSeconds=0), \
                    patch.object(M, "exchange", side_effect=M.S.Failure("ENGINE_TIMEOUT")), \
                    self.assertRaisesRegex(M.S.Failure, "CONTAINER_CLEANUP_UNCONFIRMED"):
                M.run(engine, request(), SOURCE, ENTRY_SHA, PAYLOAD_SHA)
            self.assertEqual(1, len(removals))
            self.assertIsNotNone(engine.container)

    def test_pending_remove_only_polls_and_shares_a_bounded_lookup_budget(self):
        engine = Engine()
        engine.call(M.create_arguments(request(), ENTRY_SHA, PAYLOAD_SHA))
        original = engine.call
        removals, budgets, polls = [], [], []
        def called(arguments, *args, **kwargs):
            if arguments[:2] == ["container", "rm"]:
                removals.append(arguments)
                raise M.S.Failure("ENGINE_TIMEOUT")
            if "id=" + F.ID in arguments:
                polls.append(True)
                if len(polls) == 3:
                    engine.container = None
            if arguments[:2] == ["container", "ls"] and "timeout" in kwargs:
                budgets.append(kwargs["timeout"])
            return original(arguments, *args, **kwargs)
        engine.call = called
        with patch.object(M.time, "sleep"):
            result = M.cleanup(engine, request(), F.ID)
        self.assertTrue(result["confirmed"])
        self.assertEqual(1, len(removals))
        self.assertEqual(3, len(polls))
        self.assertTrue(all(0 < budget <= min(6, M.POLICY["cleanupConfirmationSeconds"]) for budget in budgets))

    def test_name_absence_alone_cannot_confirm_a_renamed_full_id_or_foreign_replacement(self):
        for defect in ("renamed", "replacement", "malformed_id"):
            engine = Engine()
            original = engine.call
            engine.call(M.create_arguments(request(), ENTRY_SHA, PAYLOAD_SHA))
            def called(arguments, *args, **kwargs):
                if "id=" + F.ID in arguments:
                    return (F.ID if defect != "malformed_id" else "foreign-output").encode()
                if arguments[:2] == ["container", "ls"]:
                    return b"" if defect != "replacement" else b"d" * 64
                return original(arguments, *args, **kwargs)
            engine.call = called
            with self.subTest(defect=defect), self.assertRaises(M.S.Failure):
                M.confirm_cleanup(engine, request(), F.ID)
            self.assertFalse(any(args[:2] == ["container", "rm"] for args, _ in engine.calls))

    def test_entry_header_is_bounded_strict_and_never_accepts_paths_or_commands(self):
        path = Path(__file__).resolve().parents[1] / "managed-source/entry.py"
        spec = importlib.util.spec_from_file_location("managed_test_entry", path)
        entry = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(entry)
        value = {key: val for key, val in request().items() if key != "imageId"}
        self.assertEqual(value, entry.header(M.S.encode(value) + b"\n"))
        for change in ({"sourceBytes": True}, {"sourceBytes": 1048577}, {"jobId": "../prod"},
                       {"path": "/production"}, {"command": "sh"}, {"schemaVersion": True}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                entry.header(M.S.encode({**value, **change}) + b"\n")
        with self.assertRaises(ValueError):
            entry.header(b'{"schemaVersion":1,"schemaVersion":1}\n')

    def test_large_duplex_source_and_malformed_post_ack_output_are_bounded(self):
        body = b"#" + b"x" * 400000
        value = {**request(), "sourceBytes": len(body), "sourceSha256": M.S.digest(body)}
        tail = ("sys.stdin.buffer.readline(); body=sys.stdin.buffer.read(); "
                "assert len(body)==400001; print(json.dumps({'status':{'secret':'PRIVATE'}}))")
        result = M.exchange([sys.executable, "-I", "-c", self.child(tail=tail)],
                            value, body, ENTRY_SHA, PAYLOAD_SHA, timeout=2)
        self.assertEqual("invalid_output", result["untrustedRuntimeSummary"]["status"])
        self.assertEqual(len(body), result["inputSourceBytes"])
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_cancelled_exchange_terminates_without_waiting_for_source_deadline(self):
        cancelled = threading.Event()
        timer = threading.Timer(0.1, cancelled.set)
        timer.start()
        try:
            with self.assertRaisesRegex(M.S.Failure, "MANAGED_CANCELLED"):
                M.exchange([sys.executable, "-I", "-c",
                            self.child(tail="sys.stdin.buffer.readline(); sys.stdin.buffer.read(); time.sleep(10)")],
                           request(), SOURCE, ENTRY_SHA, PAYLOAD_SHA, timeout=5, cancelled=cancelled.is_set)
        finally:
            timer.cancel()


if __name__ == "__main__":
    unittest.main()
