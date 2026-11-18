"""Broker RPC lifecycle doubles; submitted source bytes are never imported."""
import base64
import copy
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

import managed_source_broker as B
from managed_loader import digest
import test_managed_source_broker as F
import test_managed_t4_rpc as R
import test_source_egress as E


POLICY = {"schemaVersion": 2, "origin": "https://example.com", "paths": ["/"],
          "methods": ["GET", "HEAD"], "maxRequests": 6, "maxResponseBytes": 2097152,
          "maxTotalResponseBytes": 4194304, "minIntervalSeconds": 1,
          "queryKeys": [], "pinnedIpv4": ["93.184.216.34"]}


class T4BrokerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.journal = Path(self.temporary.name) / "journal"
        self.engine = F.Engine()
        self.brokers = []
        self.calls = []
        self.output = {"list": [{"vod_id": "PRIVATE_RESULT", "vod_name": "fixture"}]}
        self.run = patch.object(B.G, "run", side_effect=self.execute)
        self.mock_run = self.run.start()
        self.addCleanup(self.run.stop)

    def tearDown(self):
        for broker in self.brokers:
            broker.close()
        self.temporary.cleanup()

    def broker(self):
        broker = B.Broker(self.journal, F.INSTANCE, F.IMAGE, lambda: self.engine, egress_policy=POLICY)
        self.brokers.append(broker)
        return broker

    def request(self, broker, operation="start", call=None, job=F.JOB):
        call = copy.deepcopy(R.CALLS[3] if call is None else call)
        value = {"schemaVersion": 1, "operation": operation, "jobId": job,
                 "instanceId": F.INSTANCE, "imageId": broker.image_id, "bundleSha256": broker.bundle_sha,
                 "executionPolicySha256": "d" * 64, "egressPolicySha256": broker.egress_sha}
        if operation == "start":
            value.update(sourceBase64=base64.b64encode(F.SOURCE).decode(),
                         sourceSha256=B.S.digest(F.SOURCE), t4CallJson=json.dumps(call))
        elif operation == "take":
            value.update(sourceSha256=B.S.digest(F.SOURCE), t4CallSha256=digest(call))
        return value

    def execute(self, engine, request, source, hashes, mediator, *, cancelled, t4_call):
        self.assertEqual(getattr(self, "policy", POLICY), mediator.policy)
        self.assertEqual(getattr(self, "source", F.SOURCE), source)
        self.assertFalse(cancelled())
        self.calls.append(copy.deepcopy(t4_call))
        engine.call(["container", "create"])
        driver = B.G.T4Call(source, request["runtimeSha256"], mediator, t4_call)
        call = driver.next()
        driver.source_ready({"kind": "loader_source", "sourceTextSha256": driver.text_sha})
        for result in ({"name": "fixture"}, self.output, {"status": "closed"}):
            call = driver.accept({"kind": "call_result", "id": call["id"], "ok": True, "result": result})
        self.assertEqual({"kind": "finish"}, call)
        driver.jsonl_observed = True
        receipt = driver.receipt()
        cleaned = {"confirmed": True, "containerAbsent": True}
        receipt["cleanup"] = cleaned
        return {"stdoutBytes": 12, "stderrBytes": 0, "cleanup": cleaned,
                "t4RpcValidation": receipt, "t4RpcResult": driver.take_result(),
                "PRIVATE_EXTRA": "PRIVATE_IGNORED"}

    def done(self, broker, job=F.JOB):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            row = broker.handle(self.request(broker, "status", job=job))
            if row["status"] != "RUNNING":
                return row
            time.sleep(0.01)
        self.fail("Broker job did not finish")

    def test_rpc_body_never_enters_journal_or_status_and_can_be_taken_only_once(self):
        broker = self.broker()
        broker.handle(self.request(broker))
        row = self.done(broker)
        self.assertEqual("COMPLETED", row["status"])
        self.assertEqual(digest(R.CALLS[3]), row["t4CallSha256"])
        self.assertEqual("t4_single_action_rpc", row["result"]["executionKind"])
        self.assertFalse(row["result"]["businessChainVerified"])
        self.assertEqual(0, row["result"]["t4RpcValidation"]["productNodePassesAdded"])
        before = (self.journal / (F.JOB + ".json")).read_bytes()
        self.assertNotIn(b"PRIVATE", before)
        self.assertNotIn(b"PRIVATE", B.S.encode(row))
        self.assertNotIn(b"t4CallJson", before)
        self.assertNotIn(b"t4RpcResult", before)
        value = broker.handle(self.request(broker, "take"))
        self.assertEqual(self.output, value["result"])
        self.assertEqual(digest(self.output), value["resultSha256"])
        self.assertEqual(B.S.digest(F.SOURCE), value["sourceSha256"])
        self.assertEqual(digest(R.CALLS[3]), value["t4CallSha256"])
        self.assertEqual(broker.bundle["runtimeSha256"], value["runtimeSha256"])
        self.assertEqual("untrusted_source_output", value["evidenceClass"])
        self.assertFalse(value["businessChainVerified"])
        self.assertEqual(before, (self.journal / (F.JOB + ".json")).read_bytes())
        with self.assertRaisesRegex(B.S.Failure, "T4_RPC_RESULT_UNAVAILABLE"):
            broker.handle(self.request(broker, "take"))
        self.assertEqual([R.CALLS[3]], self.calls)

    def test_five_actions_and_both_modes_reach_the_existing_egress_runner(self):
        broker = self.broker()
        for index, original in enumerate(R.CALLS):
            for mode in ("t4", "t4-direct"):
                call = {**original, "playType": mode}
                job = "00000000-0000-4000-8000-%012d" % (index * 2 + (mode == "t4") + 1)
                with self.subTest(method=call["method"], mode=mode):
                    broker.handle(self.request(broker, call=call, job=job))
                    row = self.done(broker, job)
                    self.assertEqual("COMPLETED", row["status"])
                    self.assertEqual(digest(call["params"]), row["result"]["t4RpcValidation"]["paramsSha256"])
                    self.assertEqual(self.output, broker.handle(self.request(broker, "take", call, job))["result"])
        self.assertEqual(10, self.mock_run.call_count)

    def test_public_data_is_bound_to_the_current_call_and_raw_values_never_enter_the_journal(self):
        broker = self.broker()
        call = {**R.CALLS[0], "sourceData": {"label": "PUBLIC_CONFIG_SENTINEL", "page_size": "20"}}
        broker.handle(self.request(broker, call=call))
        row = self.done(broker)
        self.assertEqual("COMPLETED", row["status"])
        self.assertEqual(call, self.calls[0])
        receipt = row["result"]["t4RpcValidation"]
        self.assertTrue(receipt["sourceDataPresent"])
        self.assertEqual(digest(call["sourceData"]), receipt["sourceDataSha256"])
        self.assertNotIn(b"PUBLIC_CONFIG_SENTINEL", (self.journal / (F.JOB + ".json")).read_bytes())
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_JOB_IDENTITY_MISMATCH"):
            broker.handle(self.request(broker, "take", R.CALLS[0]))
        self.assertEqual(self.output, broker.handle(self.request(broker, "take", call))["result"])

    def test_invalid_rpc_or_mixed_profile_never_persists_or_starts(self):
        broker = self.broker()
        request = self.request(broker)
        invalid = [None, "", "x" * 32769, "{}", '{"method":"home","method":"search"}']
        for call in ({**R.CALLS[0], "token": "PRIVATE"},
                     {**R.CALLS[4], "params": {**R.CALLS[4]["params"], "proxy_base": "PRIVATE"}},
                     {**R.CALLS[3], "params": {"wd": "word", "quick": True, "pg": "0"}}):
            invalid.append(json.dumps(call))
        for encoded in invalid:
            with self.subTest(encoded=encoded), self.assertRaises(B.S.Failure):
                broker.handle({**request, "t4CallJson": encoded})
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_REQUEST_INVALID"):
            broker.handle({**request, "loaderProfileJson": "{}"})
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_SOURCE_IDENTITY_MISMATCH"):
            broker.handle({**request, "sourceSha256": "e" * 64})
        self.assertEqual([], self.engine.calls)
        self.mock_run.assert_not_called()
        self.assertFalse((self.journal / (F.JOB + ".json")).exists())

    def test_take_checks_frozen_source_call_and_policy_without_consuming_on_mismatch(self):
        broker = self.broker()
        broker.handle(self.request(broker))
        self.done(broker)
        take = self.request(broker, "take")
        for key in ("sourceSha256", "t4CallSha256", "executionPolicySha256",
                    "bundleSha256", "egressPolicySha256"):
            with self.subTest(key=key), self.assertRaises(B.S.Failure):
                broker.handle({**take, key: "f" * 64})
        self.assertEqual(self.output, broker.handle(take)["result"])

    def test_take_is_unavailable_until_independent_cleanup_finishes_and_cancel_wins(self):
        broker = self.broker()
        entered, release = threading.Event(), threading.Event()
        def cleanup(*args, **kwargs):
            entered.set()
            self.assertTrue(release.wait(5))
            return {"confirmed": True, "containerAbsent": True}
        with patch.object(B.M, "confirm_cleanup", side_effect=cleanup):
            broker.handle(self.request(broker))
            try:
                self.assertTrue(entered.wait(2))
                with self.assertRaisesRegex(B.S.Failure, "T4_RPC_RESULT_UNAVAILABLE"):
                    broker.handle(self.request(broker, "take"))
                self.assertEqual("RUNNING", broker.handle(self.request(broker, "cancel"))["status"])
            finally:
                release.set()
            row = self.done(broker)
        self.assertEqual("CANCELLED", row["status"])
        self.assertTrue(row["cleanup"]["confirmed"])
        self.assertNotIn("result", row)
        self.assertIsNone(broker.pending_rpc)
        self.assertNotIn(b"PRIVATE", (self.journal / (F.JOB + ".json")).read_bytes())

    def test_cancel_after_completion_discards_body_but_retains_summary(self):
        broker = self.broker()
        broker.handle(self.request(broker))
        before = self.done(broker)
        self.assertEqual(before, broker.handle(self.request(broker, "cancel")))
        with self.assertRaisesRegex(B.S.Failure, "T4_RPC_RESULT_UNAVAILABLE"):
            broker.handle(self.request(broker, "take"))
        self.assertIsNone(broker.pending_rpc)

    def test_cancel_tombstone_fences_a_late_rpc_start(self):
        broker = self.broker()
        broker.handle(self.request(broker, "cancel"))
        with self.assertRaisesRegex(B.S.Failure, "JOB_ALREADY_EXISTS"):
            broker.handle(self.request(broker))
        self.mock_run.assert_not_called()
        self.assertIsNone(broker.pending_rpc)

    def test_shutdown_during_cleanup_discards_the_late_body_and_joins_worker(self):
        broker = self.broker()
        entered, release = threading.Event(), threading.Event()
        def cleanup(*args, **kwargs):
            entered.set()
            self.assertTrue(release.wait(5))
            return {"confirmed": True, "containerAbsent": True}
        with patch.object(B.M, "confirm_cleanup", side_effect=cleanup):
            broker.handle(self.request(broker))
            self.assertTrue(entered.wait(2))
            # close() must set its cancellation barrier before it waits for the worker.
            timer = threading.Timer(0.1, release.set)
            timer.start()
            try:
                broker.close()
            finally:
                release.set()
                timer.cancel()
                timer.join(timeout=2)
        self.assertEqual("CANCELLED", self.done(broker)["status"])
        self.assertTrue(all(not thread.is_alive() for thread in broker.threads.values()))
        self.assertIsNone(broker.pending_rpc)

    def test_initialization_only_broker_cannot_accept_rpc_or_take(self):
        broker = B.Broker(self.journal, F.INSTANCE, F.IMAGE, lambda: self.engine)
        self.brokers.append(broker)
        for operation in ("start", "take"):
            value = self.request(broker, operation)
            del value["egressPolicySha256"]
            with self.assertRaisesRegex(B.S.Failure, "MANAGED_REQUEST_INVALID"):
                broker.handle(value)
        self.mock_run.assert_not_called()
        self.assertEqual([], self.engine.calls)

    def test_restart_keeps_summary_loses_body_and_never_reexecutes(self):
        broker = self.broker()
        broker.handle(self.request(broker))
        before = self.done(broker)
        broker.close()
        self.assertIsNone(broker.pending_rpc)
        reopened = self.broker()
        self.assertEqual(before, reopened.handle(self.request(reopened, "status")))
        with self.assertRaisesRegex(B.S.Failure, "T4_RPC_RESULT_UNAVAILABLE"):
            reopened.handle(self.request(reopened, "take"))
        with self.assertRaisesRegex(B.S.Failure, "JOB_ALREADY_EXISTS"):
            reopened.handle(self.request(reopened))
        self.assertEqual(1, self.mock_run.call_count)

    def test_next_start_invalidates_the_previous_unclaimed_body(self):
        broker = self.broker()
        broker.handle(self.request(broker))
        self.done(broker)
        broker.handle(self.request(broker, job=F.INSTANCE))
        self.done(broker, F.INSTANCE)
        with self.assertRaisesRegex(B.S.Failure, "T4_RPC_RESULT_UNAVAILABLE"):
            broker.handle(self.request(broker, "take"))
        self.assertEqual(F.INSTANCE, broker.pending_rpc[0])
        self.assertLessEqual(len(broker.pending_rpc[1]), B.MAX_REPLY)
        self.assertEqual(self.output, broker.handle(self.request(broker, "take", job=F.INSTANCE))["result"])

    def test_concurrent_claims_release_only_one_body(self):
        broker = self.broker()
        broker.handle(self.request(broker))
        self.done(broker)
        barrier = threading.Barrier(3)
        results = []
        def claim():
            barrier.wait(timeout=3)
            try:
                results.append(broker.handle(self.request(broker, "take")))
            except B.S.Failure as error:
                results.append(error.code)
        threads = [threading.Thread(target=claim) for _ in range(2)]
        for thread in threads:
            thread.start()
        barrier.wait(timeout=3)
        for thread in threads:
            thread.join(timeout=3)
            self.assertFalse(thread.is_alive())
        self.assertEqual(1, sum(isinstance(item, dict) for item in results))
        self.assertIn("T4_RPC_RESULT_UNAVAILABLE", results)

    def test_unconfirmed_cleanup_never_releases_or_journals_body(self):
        broker = self.broker()
        with patch.object(B.M, "confirm_cleanup", side_effect=B.S.Failure("CONTAINER_CLEANUP_UNCONFIRMED")):
            broker.handle(self.request(broker))
            row = self.done(broker)
        self.assertEqual("CLEANUP_UNCONFIRMED", row["status"])
        self.assertTrue(broker.blocked)
        self.assertIsNone(broker.pending_rpc)
        self.assertNotIn("result", row)
        self.assertNotIn(b"PRIVATE", (self.journal / (F.JOB + ".json")).read_bytes())

    def test_final_journal_failure_does_not_make_body_available(self):
        broker = self.broker()
        save = broker.save
        def failed(row, **kwargs):
            if row["status"] == "COMPLETED":
                self.assertIsNone(broker.pending_rpc)
                raise OSError("PRIVATE_WRITE_FAILURE")
            return save(row, **kwargs)
        with patch.object(broker, "save", side_effect=failed):
            broker.handle(self.request(broker))
            row = self.done(broker)
        self.assertEqual("MANAGED_JOURNAL_COMMIT_FAILED", row["code"])
        self.assertTrue(broker.blocked)
        self.assertIsNone(broker.pending_rpc)
        self.assertNotIn(b"PRIVATE", B.S.encode(row))

    def test_result_limit_counts_full_ascii_json_envelope_without_truncation(self):
        broker = self.broker()
        for index, body in enumerate(("x" * 65536, "\u6d4b" * 12000)):
            self.output = {"list": body}
            job = "00000000-0000-4000-8000-%012d" % index
            broker.handle(self.request(broker, job=job))
            row = self.done(broker, job)
            self.assertEqual("FAILED", row["status"])
            self.assertEqual("T4_RPC_RESULT_LIMIT", row["code"])
            self.assertTrue(row["cleanup"]["confirmed"])
            self.assertIsNone(broker.pending_rpc)

    def test_rpc_failures_keep_fixed_code_but_not_exception_text(self):
        broker = self.broker()
        self.mock_run.side_effect = B.S.Failure("T4_RPC_INCOMPLETE")
        broker.handle(self.request(broker))
        row = self.done(broker)
        self.assertEqual("T4_RPC_INCOMPLETE", row["code"])
        self.assertIsNone(broker.pending_rpc)

    def test_invalid_transport_receipt_never_persists_the_untrusted_body(self):
        broker = self.broker()
        for index, (key, value) in enumerate((
                ("actionReturned", 1), ("resultSha256", "f" * 64),
                ("inputSourceSha256", "e" * 64), ("businessChainVerified", True),
                ("sourceDataPresent", True), ("sourceDataSha256", "e" * 64))):
            def changed(*args, **kwargs):
                result = self.execute(*args, **kwargs)
                result["t4RpcValidation"][key] = value
                return result
            self.mock_run.side_effect = changed
            job = "00000000-0000-4000-8000-%012d" % index
            broker.handle(self.request(broker, job=job))
            row = self.done(broker, job)
            self.assertEqual("T4_RPC_RECEIPT_INVALID", row["code"])
            self.assertIsNone(broker.pending_rpc)
            self.assertNotIn(b"PRIVATE", (self.journal / (job + ".json")).read_bytes())

    def test_handler_uses_existing_length_prefixed_frame_for_one_shot_delivery(self):
        broker = self.broker()
        broker.handle(self.request(broker))
        self.done(broker)
        request = B.S.encode(self.request(broker, "take"))
        handler = B.Handler.__new__(B.Handler)
        handler.server = Mock(broker=broker)
        handler.connection = Mock()
        handler.rfile = io.BytesIO(struct.pack("!I", len(request)) + request)
        handler.wfile = io.BytesIO()
        handler.handle()
        frame = handler.wfile.getvalue()
        size = struct.unpack("!I", frame[:4])[0]
        self.assertEqual(len(frame) - 4, size)
        self.assertLessEqual(size, B.MAX_REPLY)
        self.assertEqual(self.output, B.S.decode(frame[4:])["result"]["result"])
        self.assertIsNone(broker.pending_rpc)

    def test_broker_to_protocol_uses_real_trusted_child_pipes_not_submitted_source(self):
        self.run.stop()
        self.engine = R.Engine()
        broker = B.Broker(self.journal, F.INSTANCE, R.F.F.IMAGE, lambda: self.engine, egress_policy=POLICY)
        self.brokers.append(broker)
        job = R.F.F.JOB
        exchange = B.G.exchange
        def attached(command, *args, **kwargs):
            self.assertEqual([*self.engine.command, "container", "start", "--attach", "--interactive", R.F.F.ID],
                             command)
            result = exchange([sys.executable, "-I", "-c", R.child()], *args, **kwargs)
            self.engine.container["State"] = {"Status": "exited", "ExitCode": 0, "OOMKilled": False}
            return result
        with patch.object(R, "HASHES", broker.bundle["egressFiles"]), \
                patch.object(B.G, "S", B.M.S), patch.object(B.G, "exchange", side_effect=attached):
            broker.handle(self.request(broker, job=job))
            row = self.done(broker, job)
            self.assertEqual("COMPLETED", row["status"])
            delivery = broker.handle(self.request(broker, "take", job=job))
        self.assertTrue(delivery["result"]["transportFixtureOnly"])
        self.assertEqual(R.CALLS[3]["params"], delivery["result"]["params"])
        self.assertIsNone(self.engine.container)
        self.assertTrue(row["cleanup"]["confirmed"])


class AcceptanceParentTest(unittest.TestCase):
    setUp = T4BrokerTest.setUp
    tearDown = T4BrokerTest.tearDown
    broker = T4BrokerTest.broker
    request = T4BrokerTest.request
    execute = T4BrokerTest.execute
    done = T4BrokerTest.done

    def parent(self, broker, public_data=None):
        profile = {"schemaVersion": 1, "runtime": "t4",
                   "runtimeRevision": broker.bundle["runtimeSha256"],
                   "requiredNodes": ["S06", "R04", "R05"]}
        if public_data:
            profile["publicSourceData"] = {"label": "STATIC_IDENTITY_ONLY"}
            profile["sourceConfig"] = public_data
        value = self.request(broker, job=F.INSTANCE)
        del value["t4CallJson"]
        value["loaderProfileJson"] = json.dumps(profile)

        def acceptance(engine, request, source, _hashes, mediator, *, cancelled):
            engine.call(["container", "create"])
            return {
                "stdoutBytes": 0, "stderrBytes": 0,
                "loaderValidation": {
                    "authority": "host_loader_contract_observed", "scope": B.G.Loader.SCOPE,
                    "requirementsFrozen": True, "inputSourceSha256": request["sourceSha256"],
                    "runtimeSha256": request["runtimeSha256"], "requiredNodes": profile["requiredNodes"],
                    "sourceDataPresent": bool(public_data), "sourceDataSha256": digest(public_data or {}),
                    "nodes": [{"id": "S06", "status": "pass"},
                              {"id": "R04", "status": "not_tested"},
                              {"id": "R05", "status": "not_tested"}],
                    "successful": False,
                },
            }

        with patch.object(B.G, "acceptance", side_effect=acceptance):
            broker.handle(value)
            row = self.done(broker, F.INSTANCE)
        self.assertEqual("COMPLETED", row["status"])
        self.assertFalse(row["result"]["loaderValidation"]["successful"])
        return row

    def test_completed_formal_loader_binds_child_journal_and_one_time_delivery(self):
        broker = self.broker()
        parent = self.parent(broker)
        broker.handle({**self.request(broker), "parentJobId": F.INSTANCE})
        child = self.done(broker)
        expected = {"parentJobId": F.INSTANCE, "parentProfileSha256": parent["loaderProfileSha256"]}
        for key, value in expected.items():
            self.assertEqual(value, child[key])
        delivery = broker.handle(self.request(broker, "take"))
        for key, value in expected.items():
            self.assertEqual(value, delivery[key])
        self.assertEqual(self.output, delivery["result"])
        self.assertFalse(delivery["businessChainVerified"])
        self.assertFalse(delivery["publishEligible"])
        with self.assertRaisesRegex(B.S.Failure, "T4_RPC_RESULT_UNAVAILABLE"):
            broker.handle(self.request(broker, "take"))
        self.assertNotIn(b"PRIVATE", (self.journal / (F.JOB + ".json")).read_bytes())

    def test_parent_configuration_mismatch_never_starts_a_child_or_session(self):
        broker = self.broker()
        data = {"label": "PUBLIC_PARENT_SETTINGS"}
        self.parent(broker, data)
        before = len(self.calls)
        for operation in ("start", "start_session"):
            for call in (R.CALLS[3], {**R.CALLS[3], "sourceData": {"label": "wrong public settings"}}):
                with self.subTest(operation=operation), self.assertRaisesRegex(B.S.Failure, "T4_RPC_CALL_INVALID"):
                    broker.handle({**self.request(broker, call=call), "operation": operation,
                                   "parentJobId": F.INSTANCE})
                self.assertNotIn(F.JOB, broker.jobs)
                self.assertEqual(before, len(self.calls))
        call = {**R.CALLS[3], "sourceData": data}
        broker.handle({**self.request(broker, call=call), "parentJobId": F.INSTANCE})
        self.assertEqual("COMPLETED", self.done(broker)["status"])
        self.assertNotIn(b"PUBLIC_PARENT_SETTINGS", (self.journal / (F.JOB + ".json")).read_bytes())

    def test_missing_self_or_malformed_parent_never_starts_or_persists_child(self):
        broker = self.broker()
        for parent in (F.INSTANCE, F.JOB, "not-a-uuid", None, True):
            with self.subTest(parent=parent), self.assertRaisesRegex(
                    B.S.Failure, "MANAGED_ACCEPTANCE_PARENT_INVALID"):
                broker.handle({**self.request(broker), "parentJobId": parent})
        self.assertFalse((self.journal / (F.JOB + ".json")).exists())
        self.assertEqual([], self.calls)
        self.assertEqual([], self.engine.calls)

    def test_parent_source_policy_profile_cleanup_or_loader_drift_cannot_bind(self):
        broker = self.broker()
        original = self.parent(broker)
        changes = [
            {"sourceSha256": "f" * 64},
            {"executionPolicySha256": "f" * 64},
            {"loaderProfileSha256": "f" * 64},
            {"cleanup": {"confirmed": True, "containerAbsent": True}},
            {"status": "CANCELLED"},
            {"status": "RUNNING"},
            {"parentJobId": F.JOB},
            {"t4CallSha256": "f" * 64},
        ]
        for field, value in (
                ("inputSourceSha256", "e" * 64), ("runtimeSha256", "e" * 64),
                ("requirementsFrozen", 1), ("requiredNodes", ["S06"]),
                ("scope", "other"), ("nodes", [{"id": "S06", "status": "blocked"}])):
            result = copy.deepcopy(original["result"])
            result["loaderValidation"][field] = value
            changes.append({"result": result})
        try:
            for change in changes:
                with self.subTest(fields=list(change)):
                    broker.jobs[F.INSTANCE] = {**copy.deepcopy(original), **change}
                    with self.assertRaisesRegex(B.S.Failure, "MANAGED_ACCEPTANCE_PARENT_INVALID"):
                        broker.handle({**self.request(broker), "parentJobId": F.INSTANCE})
                    self.assertFalse((self.journal / (F.JOB + ".json")).exists())
        finally:
            broker.jobs[F.INSTANCE] = original
        self.assertEqual([], self.calls)

    def test_parent_field_is_not_accepted_on_profile_or_control_requests(self):
        broker = self.broker()
        value = self.request(broker)
        del value["t4CallJson"]
        value["loaderProfileJson"] = "{}"
        for request in (value, self.request(broker, "status"), self.request(broker, "cancel"),
                        self.request(broker, "take")):
            with self.subTest(operation=request["operation"]), self.assertRaisesRegex(
                    B.S.Failure, "MANAGED_REQUEST_INVALID"):
                broker.handle({**request, "parentJobId": F.INSTANCE})
        self.assertEqual([], self.engine.calls)

    def test_restart_preserves_binding_but_not_body_and_refuses_tampered_relationship(self):
        broker = self.broker()
        self.parent(broker)
        broker.handle({**self.request(broker), "parentJobId": F.INSTANCE})
        child = self.done(broker)
        broker.close()
        reopened = self.broker()
        self.assertEqual(child, reopened.handle(self.request(reopened, "status")))
        with self.assertRaisesRegex(B.S.Failure, "T4_RPC_RESULT_UNAVAILABLE"):
            reopened.handle(self.request(reopened, "take"))
        reopened.close()
        child["parentProfileSha256"] = "e" * 64
        reopened.save(child)
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_JOURNAL_INVALID"):
            self.broker()
        self.assertEqual(1, len(self.calls))


class RouteContinuationTest(unittest.TestCase):
    setUp = T4BrokerTest.setUp
    tearDown = T4BrokerTest.tearDown
    done = T4BrokerTest.done

    def broker(self):
        self.policy = E.RoutesTest().policy()
        self.policy["methods"] = ["GET", "HEAD"]
        self.source = F.SOURCE
        self.execution_sha = "d" * 64
        self.entries = []
        self.behavior = lambda mediator: None
        broker = B.Broker(self.journal, F.INSTANCE, F.IMAGE, lambda: self.engine, egress_policy=self.policy)
        self.brokers.append(broker)
        return broker

    def request(self, broker, operation="start", call=None, job=F.JOB):
        value = T4BrokerTest.request(self, broker, operation, call, job)
        value["executionPolicySha256"] = self.execution_sha
        if operation in {"start", "take"}:
            value["sourceSha256"] = B.S.digest(self.source)
        if operation == "start":
            value["sourceBase64"] = base64.b64encode(self.source).decode()
        return value

    def execute(self, engine, request, source, hashes, mediator, *, cancelled, t4_call):
        self.entries.append((mediator.snapshot_urls(), mediator.count, mediator.total,
                             list(mediator.events), mediator.next_request))
        self.behavior(mediator)
        return T4BrokerTest.execute(self, engine, request, source, hashes, mediator,
                                    cancelled=cancelled, t4_call=t4_call)

    def action(self, broker, index, call=None):
        job = "00000000-0000-4000-8000-%012d" % index
        broker.handle(self.request(broker, call=call, job=job))
        return self.done(broker, job)

    def discover(self, mediator, target="https://media.example.com/one.m3u8?signature=PRIVATE"):
        connection = E.FakeConnection()
        connection.response.read = io.BytesIO(json.dumps({"data": {"hls_url": target}}).encode()).read
        with patch.object(B.H.http.client, "HTTPSConnection", return_value=connection):
            mediator.fetch(E.RoutesTest().request())

    def seed(self, broker):
        self.behavior = self.discover
        self.assertEqual("COMPLETED", self.action(broker, 1, R.CALLS[2])["status"])
        self.behavior = lambda mediator: None
        self.assertIsNotNone(broker.trusted_routes)

    def test_detail_to_play_continues_only_host_discovered_urls_and_terminal_cancel_keeps_them(self):
        broker = self.broker()
        self.seed(broker)
        job = "00000000-0000-4000-8000-000000000001"
        broker.handle(self.request(broker, "take", R.CALLS[2], job))
        broker.handle(self.request(broker, "cancel", job=job))
        self.assertIsNotNone(broker.trusted_routes)
        media = "https://media.example.com/one.m3u8?signature=PRIVATE"
        def play(mediator):
            connection = E.FakeConnection()
            with patch.object(B.H.http.client, "HTTPSConnection", return_value=connection), \
                    patch.object(B.H.time, "sleep"):
                mediator.fetch(E.RoutesTest().request(media))
            with self.assertRaisesRegex(B.H.Denied, "EGRESS_URL_DENIED"):
                mediator.fetch(E.RoutesTest().request(media + "&extra=1"))
        self.behavior = play
        row = self.action(broker, 2, R.CALLS[4])
        self.assertEqual("COMPLETED", row["status"])
        self.assertEqual((0, 0, []), self.entries[-1][1:4])
        self.assertIn(media, self.entries[-1][0]["https://media.example.com"])
        for path in self.journal.glob("*.json"):
            self.assertNotIn(b"PRIVATE", path.read_bytes())
            self.assertNotIn(b"media.example.com", path.read_bytes())
        self.assertNotIn(b"media.example.com", B.S.encode(row))
        self.assertEqual([], self.policy["routes"][1]["exactUrls"])

    def test_source_mode_and_execution_policy_switch_discard_old_state_even_when_switching_back(self):
        broker = self.broker()
        for index, field in enumerate(("source", "mode", "execution", "data")):
            self.behavior = self.discover
            self.assertEqual("COMPLETED", self.action(broker, index * 3 + 1)["status"])
            old_source, old_execution = self.source, self.execution_sha
            if field == "source":
                self.source += b"# other source\n"
            if field == "execution":
                self.execution_sha = "e" * 64
            call = {**R.CALLS[3], "playType": "t4-direct"} if field == "mode" else None
            if field == "data":
                call = {**R.CALLS[3], "sourceData": {"label": "new config"}}
            self.behavior = lambda mediator: None
            self.assertEqual("COMPLETED", self.action(broker, index * 3 + 2, call)["status"])
            self.assertEqual((), self.entries[-1][0]["https://media.example.com"])
            self.source, self.execution_sha = old_source, old_execution
            self.assertEqual("COMPLETED", self.action(broker, index * 3 + 3)["status"])
            self.assertEqual((), self.entries[-1][0]["https://media.example.com"])

    def test_identity_includes_instance_image_bundle_runtime_source_mode_and_both_policies(self):
        broker = self.broker()
        request = {"sourceSha256": "a" * 64, "runtimeSha256": "b" * 64}
        call = R.CALLS[3]
        identity = broker.route_identity(request, call, self.execution_sha)
        for field in ("instance_id", "image_id", "bundle_sha", "egress_sha"):
            old = getattr(broker, field)
            setattr(broker, field, "changed")
            self.assertNotEqual(identity, broker.route_identity(request, call, self.execution_sha))
            setattr(broker, field, old)
        for field in request:
            self.assertNotEqual(identity, broker.route_identity({**request, field: "c" * 64},
                                                               call, self.execution_sha))
        self.assertNotEqual(identity, broker.route_identity(request, call, "e" * 64))
        self.assertNotEqual(identity, broker.route_identity(request, {**call, "playType": "t4-direct"},
                                                           self.execution_sha))
        self.assertNotEqual(identity, broker.route_identity(request, {**call, "sourceData": {"label": "new"}},
                                                           self.execution_sha))
        parent_identity = broker.route_identity(request, call, self.execution_sha, F.INSTANCE)
        self.assertNotEqual(identity, parent_identity)
        self.assertNotEqual(parent_identity, broker.route_identity(request, call, self.execution_sha, F.JOB))
        self.assertIsNone(broker.route_identity(request, None, self.execution_sha))

    def test_acceptance_cancel_does_not_discard_an_unrelated_unbound_continuation(self):
        broker = self.broker()
        self.seed(broker)
        before = copy.deepcopy(broker.trusted_routes)
        broker.handle(self.request(broker, "cancel_acceptance", job=F.INSTANCE))
        self.assertEqual(before, broker.trusted_routes)
        self.assertEqual("COMPLETED", self.action(broker, 2)["status"])
        self.assertIn("https://media.example.com/one.m3u8?signature=PRIVATE",
                      self.entries[-1][0]["https://media.example.com"])

    def test_failed_action_drops_previously_committed_and_new_urls(self):
        broker = self.broker()
        self.seed(broker)
        def fail(mediator):
            self.discover(mediator, "https://media.example.com/new")
            raise B.S.Failure("T4_RPC_INCOMPLETE")
        self.behavior = fail
        self.assertEqual("FAILED", self.action(broker, 2)["status"])
        self.assertIsNone(broker.trusted_routes)
        self.assertIsNone(broker.staged_routes)
        self.behavior = lambda mediator: None
        self.assertEqual("COMPLETED", self.action(broker, 3)["status"])
        self.assertEqual((), self.entries[-1][0]["https://media.example.com"])

    def test_cleanup_is_a_commit_barrier_and_running_cancel_invalidates_late_routes(self):
        broker = self.broker()
        self.seed(broker)
        entered, release = threading.Event(), threading.Event()
        def cleanup(*args, **kwargs):
            entered.set()
            self.assertTrue(release.wait(5))
            return {"confirmed": True, "containerAbsent": True}
        self.behavior = lambda mediator: self.discover(mediator, "https://media.example.com/new")
        job = "00000000-0000-4000-8000-000000000002"
        with patch.object(B.M, "confirm_cleanup", side_effect=cleanup):
            broker.handle(self.request(broker, job=job))
            try:
                self.assertTrue(entered.wait(2))
                self.assertNotIn("https://media.example.com/new",
                                 broker.trusted_routes[1]["https://media.example.com"])
                broker.handle(self.request(broker, "cancel", job=job))
                self.assertIsNone(broker.trusted_routes)
            finally:
                release.set()
            self.assertEqual("CANCELLED", self.done(broker, job)["status"])
        self.assertIsNone(broker.trusted_routes)
        self.assertIsNone(broker.staged_routes)

    def test_unconfirmed_cleanup_or_journal_failure_never_promotes_routes(self):
        for failure in ("cleanup", "journal"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                self.journal = Path(directory) / "journal"
                broker = self.broker()
                self.seed(broker)
                self.behavior = self.discover
                save = broker.save
                def checked_save(row, **kwargs):
                    if row["status"] == "COMPLETED":
                        raise OSError("PRIVATE_WRITE_FAILURE")
                    return save(row, **kwargs)
                target = (patch.object(B.M, "confirm_cleanup",
                                       side_effect=B.S.Failure("CONTAINER_CLEANUP_UNCONFIRMED"))
                          if failure == "cleanup" else patch.object(broker, "save", side_effect=checked_save))
                with target:
                    row = self.action(broker, 2)
                self.assertEqual("CLEANUP_UNCONFIRMED", row["status"])
                self.assertIsNone(broker.trusted_routes)
                self.assertIsNone(broker.staged_routes)
                broker.close()

    def test_close_and_reopen_do_not_restore_routes_from_journal(self):
        broker = self.broker()
        self.seed(broker)
        broker.close()
        self.assertIsNone(broker.trusted_routes)
        reopened = self.broker()
        self.assertIsNone(reopened.trusted_routes)
        self.assertEqual("COMPLETED", self.action(reopened, 2)["status"])
        self.assertEqual((), self.entries[-1][0]["https://media.example.com"])

    def test_action_budgets_reset_but_rate_limit_survives_success_and_failure(self):
        from types import SimpleNamespace
        broker = self.broker()
        sleep = Mock()
        clock = SimpleNamespace(monotonic=lambda: 100, sleep=sleep)
        with patch.object(B.H, "time", clock):
            self.seed(broker)
            self.behavior = self.discover
            self.assertEqual("COMPLETED", self.action(broker, 2)["status"])
            def failed(mediator):
                self.discover(mediator)
                raise B.S.Failure("ENGINE_TIMEOUT")
            self.behavior = failed
            self.assertEqual("FAILED", self.action(broker, 3)["status"])
            self.behavior = self.discover
            self.assertEqual("COMPLETED", self.action(broker, 4)["status"])
        self.assertEqual([0, 1, 1, 1], [call.args[0] for call in sleep.call_args_list])
        self.assertEqual([0, 0, 0, 0], [entry[1] for entry in self.entries])
        self.assertEqual([0, 0, 0, 0], [entry[2] for entry in self.entries])
        self.assertEqual([[], [], [], []], [entry[3] for entry in self.entries])

    def test_failed_thread_start_clears_continuation_and_close_can_join_remaining_workers(self):
        broker = self.broker()
        self.seed(broker)
        with patch.object(threading.Thread, "start", side_effect=RuntimeError("fixture")):
            row = self.action(broker, 2)
        self.assertEqual("MANAGED_THREAD_START_FAILED", row["code"])
        self.assertIsNone(broker.trusted_routes)
        broker.close()


if __name__ == "__main__":
    unittest.main()
