"""Deterministic host orchestration tests; fake Engine, never import submitted Python."""
from __future__ import annotations

import base64
import copy
import json
import io
import struct
from types import SimpleNamespace
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import managed_source_broker as B
import test_managed_source_protocol as P

INSTANCE = "d0f4909a-5709-4e57-9a54-4ded3c19424a"
JOB = "b19d47a2-47de-4783-b8a6-233c4abf271a"
IMAGE = "sha256:" + "b" * 64
SOURCE = b"PRIVATE_SOURCE_MARKER = True\n"


class Engine:
    command = ["fake-engine"]

    def __init__(self):
        self.calls = []
        self.lost_ack = False

    def call(self, arguments, *args, **kwargs):
        self.calls.append(arguments)
        if arguments[:2] == ["container", "create"]:
            if self.lost_ack:
                raise B.S.Failure("ENGINE_TIMEOUT")
            return b"c" * 64
        if arguments[:2] == ["container", "ls"]:
            return b""
        raise AssertionError("Unexpected Engine operation")


class BrokerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.journal = Path(self.temporary.name) / "journal"
        self.engine = Engine()
        self.calls = []
        self.brokers = []

    def tearDown(self):
        for broker in self.brokers:
            broker.close()
        self.temporary.cleanup()

    def broker(self, execute=None):
        def completed(engine, request, source, entry_sha, payload_sha, *, cancelled):
            self.calls.append((request, source))
            engine.call(["container", "create"])
            return {"businessChainVerified": False}
        broker = B.Broker(self.journal, INSTANCE, IMAGE, lambda: self.engine,
                          execute=completed if execute is None else execute)
        self.brokers.append(broker)
        return broker

    def request(self, broker, operation="start", job=JOB):
        value = {"schemaVersion": 1, "operation": operation, "jobId": job, "instanceId": INSTANCE,
                 "imageId": broker.image_id, "bundleSha256": broker.bundle_sha, "executionPolicySha256": "d" * 64}
        if operation == "start":
            value.update(sourceBase64=base64.b64encode(SOURCE).decode(), sourceSha256=B.S.digest(SOURCE))
            if broker.egress_policy is not None:
                value["loaderProfileJson"] = json.dumps(
                    {"schemaVersion": 1, "runtime": "t4", "requiredNodes": ["S01", "S06", "C01", "C05"]})
        return value

    def await_job(self, broker, job=JOB):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            row = broker.handle(self.request(broker, "status", job))
            if row["status"] != "RUNNING":
                return row
            time.sleep(0.01)
        self.fail("Job did not finish")

    def test_one_execution_and_no_source_or_credentials_in_durable_journal(self):
        broker = self.broker()
        broker.handle(self.request(broker))
        done = self.await_job(broker)
        self.assertEqual("COMPLETED", done["status"])
        self.assertTrue(done["cleanup"]["containerAbsent"])
        self.assertEqual(SOURCE, self.calls[0][1])
        self.assertFalse(done["businessChainVerified"])
        with self.assertRaisesRegex(B.S.Failure, "JOB_ALREADY_EXISTS"):
            broker.handle(self.request(broker))
        self.assertEqual(1, len(self.calls))
        body = (self.journal / (JOB + ".json")).read_bytes()
        self.assertNotIn(SOURCE.strip(), body)
        self.assertNotIn(b"sourceBase64", body)
        done["status"] = "PRIVATE"
        self.assertEqual("COMPLETED", broker.handle(self.request(broker, "status"))["status"])

    def test_status_frames_allow_combined_receipts_without_expanding_rpc_bodies(self):
        for operation in (*B.STATUS_OPERATIONS, "take"):
            with self.subTest(operation=operation):
                request = B.S.encode({"operation": operation})
                output = io.BytesIO()
                connection = SimpleNamespace(settimeout=lambda _: None)
                handler = SimpleNamespace(connection=connection,
                    rfile=io.BytesIO(struct.pack("!I", len(request)) + request), wfile=output,
                    server=SimpleNamespace(broker=SimpleNamespace(handle=lambda _: {"evidence": "x" * 70000})))
                B.Handler.handle(handler)
                body = output.getvalue()
                self.assertEqual(len(body) - 4, struct.unpack("!I", body[:4])[0])
                reply = json.loads(body[4:])
                self.assertEqual(operation != "take", reply["ok"])
                if operation == "take":
                    self.assertEqual("MANAGED_FRAME_LIMIT", reply["code"])

    def test_journal_still_rejects_evidence_above_its_separate_bound(self):
        broker = self.broker()
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_JOURNAL_LIMIT"):
            broker.save({"jobId": JOB, "evidence": "x" * B.MAX_JOURNAL_BYTES})
        self.assertFalse((self.journal / (JOB + ".json")).exists())

    def test_cancel_before_start_is_durable_and_fences_late_start_after_restart(self):
        broker = self.broker()
        row = broker.handle(self.request(broker, "cancel"))
        self.assertEqual("CANCELLED", row["status"])
        self.assertTrue(row["cleanup"]["confirmed"])
        broker.close()
        restarted = self.broker()
        with self.assertRaisesRegex(B.S.Failure, "JOB_ALREADY_EXISTS"):
            restarted.handle(self.request(restarted))
        self.assertEqual([], self.calls)
        self.assertEqual([], self.engine.calls)

    def test_cancellation_waits_for_worker_cleanup_and_drops_late_success(self):
        entered, release = threading.Event(), threading.Event()
        def execute(engine, request, source, *args, cancelled):
            engine.call(["container", "create"])
            entered.set()
            release.wait(5)
            return {"businessChainVerified": False, "late": True}
        broker = self.broker(execute)
        broker.handle(self.request(broker))
        try:
            self.assertTrue(entered.wait(2))
            row = broker.handle(self.request(broker, "cancel"))
            self.assertEqual("RUNNING", row["status"])
            self.assertNotIn("cleanup", row)
        finally:
            release.set()
        done = self.await_job(broker)
        self.assertEqual("CANCELLED", done["status"])
        self.assertTrue(done["cleanup"]["confirmed"])
        self.assertNotIn("result", done)

    def test_lost_create_ack_blocks_new_jobs_even_after_empty_lookup(self):
        def execute(engine, *args, **kwargs):
            engine.call(["container", "create"])
        self.engine.lost_ack = True
        broker = self.broker(execute)
        broker.handle(self.request(broker))
        done = self.await_job(broker)
        self.assertEqual("CLEANUP_UNCONFIRMED", done["status"])
        self.assertFalse(done["cleanup"]["confirmed"])
        self.assertTrue(broker.identity()["recoveryRequired"])
        self.assertEqual("ENGINE_TIMEOUT", done["diagnostics"]["primaryCode"])
        self.assertEqual("container_create", done["diagnostics"]["failureStage"])
        self.assertTrue(done["diagnostics"]["createIssued"])
        self.assertFalse(done["diagnostics"]["createConfirmed"])
        self.assertEqual(0, done["diagnostics"]["createAckBytes"])
        self.assertEqual("failed", done["diagnostics"]["calls"][0]["outcome"])
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_RUNNER_RECOVERY_REQUIRED"):
            broker.handle(self.request(broker, job=INSTANCE))
        self.assertEqual("CLEANUP_UNCONFIRMED", broker.handle(self.request(broker, "cancel"))["status"])

    def test_runner_restart_does_not_replay_or_claim_orphan_cleanup(self):
        broker = self.broker()
        broker.save(broker.row(self.request(broker), "RUNNING"), exclusive=True)
        broker.close()
        restarted = self.broker()
        row = restarted.handle(self.request(restarted, "cancel"))
        self.assertEqual("CLEANUP_UNCONFIRMED", row["status"])
        self.assertEqual("MANAGED_RUNNER_RESTARTED", row["code"])
        self.assertFalse(row["cleanup"]["confirmed"])
        self.assertEqual([], self.engine.calls)

    def test_preexisting_container_is_not_mistaken_for_a_no_create_cleanup(self):
        def execute(*args, **kwargs):
            raise B.S.Failure("JOB_ALREADY_EXISTS")
        broker = self.broker(execute)
        broker.handle(self.request(broker))
        row = self.await_job(broker)
        self.assertEqual("CLEANUP_UNCONFIRMED", row["status"])
        self.assertFalse(row["cleanup"]["containerAbsent"])
        self.assertTrue(broker.identity()["recoveryRequired"])

    def test_identity_source_and_policy_drift_cannot_reach_executor(self):
        broker = self.broker()
        for changes in ({"instanceId": JOB}, {"imageId": "latest"}, {"bundleSha256": "f" * 64},
                        {"sourceSha256": "e" * 64}, {"sourceBase64": "!!"}, {"command": "sh"},
                        {"schemaVersion": True}, {"jobId": "../prod"}, {"sourceBase64": True}):
            with self.subTest(changes=changes), self.assertRaises(B.S.Failure):
                broker.handle({**self.request(broker), **changes})
        self.assertEqual([], self.calls)
        self.assertEqual([], self.engine.calls)
        broker.handle(self.request(broker, "cancel"))
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_JOB_IDENTITY_MISMATCH"):
            broker.handle({**self.request(broker, "status"), "executionPolicySha256": "e" * 64})

    def test_global_busy_does_not_queue_or_persist_another_job(self):
        entered, release = threading.Event(), threading.Event()
        def execute(engine, *args, **kwargs):
            entered.set()
            release.wait(5)
            return {}
        broker = self.broker(execute)
        broker.handle(self.request(broker))
        try:
            self.assertTrue(entered.wait(2))
            with self.assertRaisesRegex(B.S.Failure, "MANAGED_RUNNER_BUSY"):
                broker.handle(self.request(broker, job=INSTANCE))
            self.assertFalse((self.journal / (INSTANCE + ".json")).exists())
        finally:
            release.set()
        self.await_job(broker)

    def test_exception_text_and_source_are_not_error_codes(self):
        def execute(*args, **kwargs):
            raise RuntimeError("PRIVATE_RAW_ERROR")
        broker = self.broker(execute)
        broker.handle(self.request(broker))
        row = self.await_job(broker)
        self.assertEqual("FAILED", row["status"])
        self.assertEqual("MANAGED_BROKER_FAILED", row["code"])
        self.assertNotIn("PRIVATE", json.dumps(row))
        self.assertTrue(row["cleanup"]["confirmed"])
        self.assertNotIn("PRIVATE", json.dumps(row["diagnostics"]))

    def test_uppercase_exception_codes_cannot_smuggle_arbitrary_text(self):
        def execute(*args, **kwargs):
            raise B.S.Failure("PRIVATE_UPPERCASE_SECRET")
        broker = self.broker(execute)
        broker.handle(self.request(broker))
        row = self.await_job(broker)
        self.assertEqual("MANAGED_BROKER_FAILED", row["diagnostics"]["primaryCode"])
        self.assertNotIn("PRIVATE", json.dumps(row))

    def test_protocol_cleanup_wrapper_preserves_underlying_create_failure(self):
        engine = P.Engine()
        engine.create_error = "ENGINE_TIMEOUT"
        tracked = B.TrackedEngine(engine)
        with self.assertRaisesRegex(B.S.Failure, "CONTAINER_CLEANUP_UNCONFIRMED"):
            B.M.run(tracked, P.request(), P.SOURCE, P.ENTRY_SHA, P.PAYLOAD_SHA)
        diagnostic = tracked.diagnostics()
        self.assertEqual("ENGINE_TIMEOUT", diagnostic["primaryCode"])
        self.assertEqual("container_create", diagnostic["failureStage"])
        self.assertTrue(diagnostic["createIssued"])
        self.assertFalse(diagnostic["createConfirmed"])
        self.assertTrue(all(type(call["elapsedMillis"]) is int for call in diagnostic["calls"]))

    def test_exchange_failure_and_cleanup_failure_have_separate_fixed_codes(self):
        engine = P.Engine()
        engine.refuse_remove = True
        tracked = B.TrackedEngine(engine)
        with patch.object(B.M, "exchange", side_effect=B.S.Failure("ENGINE_TIMEOUT")), \
                self.assertRaisesRegex(B.S.Failure, "CONTAINER_CLEANUP_UNCONFIRMED"):
            B.M.run(tracked, P.request(), P.SOURCE, P.ENTRY_SHA, P.PAYLOAD_SHA)
        diagnostic = tracked.diagnostics()
        self.assertEqual("source_exchange", diagnostic["failureStage"])
        self.assertEqual("ENGINE_TIMEOUT", diagnostic["primaryCode"])
        self.assertEqual("CONTAINER_CLEANUP_UNCONFIRMED", diagnostic["cleanupCode"])
        self.assertTrue(diagnostic["createConfirmed"])

    def test_broker_does_not_repeat_protocol_remove_after_an_uncertain_reply(self):
        engine = P.Engine()
        original = engine.call
        removals = []
        def called(arguments, *args, **kwargs):
            if arguments[:2] == ["container", "rm"]:
                removals.append(arguments)
                engine.container = None
                raise B.S.Failure("ENGINE_TIMEOUT")
            return original(arguments, *args, **kwargs)
        engine.call = called
        def execute(tracked, request, source, _entry, _payload, *, cancelled):
            return B.M.run(tracked, request, source, P.ENTRY_SHA, P.PAYLOAD_SHA, cancelled=cancelled)
        broker = B.Broker(self.journal, INSTANCE, P.F.IMAGE, lambda: engine, execute=execute)
        self.brokers.append(broker)
        value = self.request(broker, job=P.F.JOB)
        value["imageId"] = P.F.IMAGE
        with patch.object(B.M, "exchange", side_effect=B.S.Failure("ENGINE_TIMEOUT")):
            broker.handle(value)
            done = self.await_job(broker, P.F.JOB)
        self.assertEqual("FAILED", done["status"])
        self.assertEqual("ENGINE_TIMEOUT", done["code"])
        self.assertTrue(done["cleanup"]["confirmed"])
        self.assertFalse(broker.identity()["recoveryRequired"])
        self.assertEqual(1, len(removals))
        self.assertEqual("ENGINE_TIMEOUT", done["diagnostics"]["cleanupCode"])

    def recovery_fixture(self, state="created", status="CLEANUP_UNCONFIRMED"):
        engine = P.Engine()
        broker = B.Broker(self.journal, INSTANCE, P.F.IMAGE, lambda: engine, recovery_only=True)
        self.brokers.append(broker)
        row = broker.row(self.request(broker, job=P.F.JOB), status)
        row["code"] = "MANAGED_CLEANUP_UNCONFIRMED"
        broker.save(row, exclusive=True)
        broker.jobs[P.F.JOB], broker.blocked = row, True
        request = {"jobId": P.F.JOB, "imageId": P.F.IMAGE,
                   "runtimeSha256": broker.bundle["runtimeSha256"]}
        engine.call(B.M.create_arguments(request, broker.bundle["entrySha256"], broker.bundle["payloadSha256"]))
        engine.container["State"]["Status"] = state
        digest = B.S.digest((self.journal / (P.F.JOB + ".json")).read_bytes())
        return broker, engine, digest

    def test_offline_recovery_checks_owned_isolation_and_retains_no_replay_history(self):
        broker, engine, digest = self.recovery_fixture("running", "RUNNING")
        done = broker.recover_owned(P.F.JOB, digest, P.F.ID)
        self.assertEqual("FAILED", done["status"])
        self.assertTrue(done["cleanup"]["confirmed"])
        self.assertEqual(digest, done["recovery"]["previousRowSha256"])
        self.assertFalse(done["recovery"]["journalReset"])
        self.assertFalse(done["recovery"]["sourceReplayed"])
        self.assertIsNone(engine.container)
        self.assertFalse(broker.identity()["recoveryRequired"])
        broker.close()
        reopened = B.Broker(self.journal, INSTANCE, P.F.IMAGE, lambda: engine)
        self.brokers.append(reopened)
        value = self.request(reopened, job=P.F.JOB)
        value["imageId"] = P.F.IMAGE
        with self.assertRaisesRegex(B.S.Failure, "JOB_ALREADY_EXISTS"):
            reopened.handle(value)

    def test_empty_or_foreign_or_changed_recovery_cannot_remove_or_unblock(self):
        broker, engine, digest = self.recovery_fixture()
        before = (self.journal / (P.F.JOB + ".json")).read_bytes()
        for defect in ("sha", "id", "memory", "network", "mount", "ownership", "missing"):
            saved = copy.deepcopy(engine.container)
            expected, ident = digest, P.F.ID
            if defect == "sha":
                expected = "0" * 64
            elif defect == "id":
                ident = "0" * 64
            elif defect == "memory":
                engine.container["HostConfig"]["Memory"] = 0
            elif defect == "network":
                engine.container["HostConfig"]["NetworkMode"] = "bridge"
            elif defect == "mount":
                engine.container["HostConfig"]["Binds"] = ["/production:/data"]
            elif defect == "ownership":
                engine.container["Config"]["Labels"][B.S.MANAGED + ".job"] = INSTANCE
            else:
                engine.container = None
            with self.subTest(defect=defect), self.assertRaises(B.S.Failure):
                broker.recover_owned(P.F.JOB, expected, ident)
            self.assertTrue(broker.identity()["recoveryRequired"])
            self.assertEqual(before, (self.journal / (P.F.JOB + ".json")).read_bytes())
            self.assertFalse(any(args[:2] == ["container", "rm"] for args, _ in engine.calls))
            engine.container = saved

    def test_recovery_refuses_a_live_broker_or_unconfirmed_removal(self):
        broker, engine, digest = self.recovery_fixture()
        broker.recovery_only = False
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_RECOVERY_OFFLINE_REQUIRED"):
            broker.recover_owned(P.F.JOB, digest, P.F.ID)
        broker.recovery_only = True
        engine.refuse_remove = True
        with self.assertRaisesRegex(B.S.Failure, "CONTAINER_CLEANUP_UNCONFIRMED"):
            broker.recover_owned(P.F.JOB, digest, P.F.ID)
        self.assertTrue(broker.identity()["recoveryRequired"])
        self.assertEqual(digest, B.S.digest((self.journal / (P.F.JOB + ".json")).read_bytes()))

    def test_corrupt_or_uncommitted_journal_fails_closed(self):
        broker = self.broker()
        row = broker.row(self.request(broker), "CANCELLED")
        broker.save(row, exclusive=True)
        broker.close()
        with (self.journal / (JOB + ".pending")).open("xb") as stream:
            stream.write(b"{}")
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_JOURNAL_INVALID"):
            self.broker()

    def test_job_status_has_no_unknown_success_and_duplicate_json_is_rejected(self):
        broker = self.broker()
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_JOB_UNKNOWN"):
            broker.handle(self.request(broker, "status"))
        with self.assertRaisesRegex(B.S.Failure, "JSON_DUPLICATE_FIELD"):
            B.S.decode(b'{"operation":"start","operation":"cancel"}')
        identity = broker.handle({"schemaVersion": 1, "operation": "identity"})
        self.assertNotIn("endpoint", identity)
        self.assertNotIn("credentials", identity)

    def test_business_sampling_uses_fixed_policy_and_existing_durable_lifecycle(self):
        policy = {"schemaVersion": 2, "origin": "https://example.com", "paths": ["/"],
                  "methods": ["GET", "HEAD"], "maxRequests": 6, "maxResponseBytes": 2097152,
                  "maxTotalResponseBytes": 4194304, "minIntervalSeconds": 1,
                  "queryKeys": [], "pinnedIpv4": ["93.184.216.34"]}
        broker = B.Broker(self.journal, INSTANCE, IMAGE, lambda: self.engine, egress_policy=policy)
        self.brokers.append(broker)
        request = self.request(broker)
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_RUNNER_IDENTITY_MISMATCH"):
            broker.handle(request)
        request["egressPolicySha256"] = broker.egress_sha
        def sample(engine, request, source, hashes, mediator, *, cancelled):
            self.assertEqual(policy, mediator.policy)
            self.assertEqual(broker.bundle["egressFiles"], hashes)
            self.assertEqual(SOURCE, source)
            self.assertFalse(cancelled())
            self.assertEqual(["S01", "S06", "C01", "C05"], mediator.loader_profile["requiredNodes"])
            engine.call(["container", "create"])
            return {"stdoutBytes": 12, "stderrBytes": 0, "policy": mediator.policy,
                    "untrustedRuntimeSummary": {"status": "detail_returned", "homeItems": 20},
                    "loaderValidation": {"successful": False}}
        with patch.object(B.G, "acceptance", side_effect=sample):
            broker.handle(request)
            deadline = time.monotonic() + 5
            while broker.active is not None and time.monotonic() < deadline:
                time.sleep(0.01)
        status = {**request, "operation": "status"}
        del status["sourceBase64"], status["sourceSha256"], status["loaderProfileJson"]
        done = broker.handle(status)
        self.assertEqual("COMPLETED", done["status"])
        self.assertEqual("t4_business_sample", done["result"]["executionKind"])
        self.assertFalse(done["result"]["businessChainVerified"])
        self.assertEqual(B.S.digest(request["loaderProfileJson"].encode()),
                         done["result"]["loaderValidation"]["inputProfileSha256"])
        self.assertTrue(done["cleanup"]["confirmed"])
        self.assertNotIn(SOURCE.decode().strip(), (self.journal / (JOB + ".json")).read_text())
        broker.close()
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_JOURNAL_INVALID"):
            B.Broker(self.journal, INSTANCE, IMAGE, lambda: self.engine,
                     egress_policy={**policy, "maxRequests": 5})
        with self.assertRaisesRegex(B.S.Failure, "EGRESS_POLICY_INVALID"):
            B.Broker(Path(self.temporary.name) / "other", INSTANCE, IMAGE, lambda: self.engine,
                     egress_policy={**policy, "methods": ["POST"]})

    def test_host_credentials_are_frozen_outside_source_profile_and_durable_journal(self):
        policy = {"schemaVersion": 2, "origin": "https://example.com", "paths": ["/"],
                  "methods": ["GET", "HEAD"], "maxRequests": 6, "maxResponseBytes": 2097152,
                  "maxTotalResponseBytes": 4194304, "minIntervalSeconds": 1,
                  "queryKeys": [], "pinnedIpv4": ["93.184.216.34"]}
        broker = B.Broker(self.journal, INSTANCE, IMAGE, lambda: self.engine, egress_policy=policy)
        self.brokers.append(broker)
        request = self.request(broker)
        request["egressPolicySha256"] = broker.egress_sha
        request["hostCredentials"] = {"origin": policy["origin"], "cookie": "session=private-cookie",
                                      "authorization": "Bearer private-token"}

        def sample(engine, worker_request, source, hashes, mediator, *, cancelled):
            self.assertNotIn("hostCredentials", worker_request)
            self.assertNotIn("private-", json.dumps(mediator.loader_profile))
            self.assertEqual("session=private-cookie", mediator.host_credentials["cookie"])
            engine.call(["container", "create"])
            return {"stdoutBytes": 0, "stderrBytes": 0, "loaderValidation": {"successful": False}}

        with patch.object(B.G, "acceptance", side_effect=sample):
            broker.handle(request)
            broker.threads[JOB].join(5)
        row = json.loads((self.journal / (JOB + ".json")).read_bytes())
        self.assertEqual("COMPLETED", row["status"])
        self.assertNotIn("private-", json.dumps(row))
        cancel = self.request(broker, "cancel_acceptance")
        cancel["egressPolicySha256"] = broker.egress_sha
        broker.handle(cancel)
        self.assertEqual({}, broker.job_credentials)

    def test_explicit_post_policy_is_preserved_without_enabling_other_methods(self):
        policy = {"schemaVersion": 2, "origin": "https://example.com", "paths": ["/"],
                  "methods": ["GET", "HEAD", "POST"], "maxRequests": 6, "maxResponseBytes": 2097152,
                  "maxTotalResponseBytes": 4194304, "minIntervalSeconds": 1,
                  "queryKeys": [], "pinnedIpv4": ["93.184.216.34"]}
        broker = B.Broker(self.journal, INSTANCE, IMAGE, lambda: self.engine, egress_policy=policy)
        self.brokers.append(broker)
        self.assertEqual(policy, broker.egress_policy)
        policy["methods"].remove("POST")
        self.assertEqual(["GET", "HEAD", "POST"], broker.egress_policy["methods"])
        with self.assertRaisesRegex(B.H.Denied, "EGRESS_POLICY_INVALID"):
            B.Broker(Path(self.temporary.name) / "other", INSTANCE, IMAGE, lambda: self.engine,
                     egress_policy={**policy, "methods": ["GET", "HEAD", "DELETE"]})
        self.assertEqual([], self.engine.calls)

    def test_runtime_gateway_settings_reach_host_only_and_cleanup_runs_on_execution_failure(self):
        policy = {"schemaVersion": 2, "origin": "https://example.com", "paths": ["/"],
                  "methods": ["GET", "HEAD"], "maxRequests": 6, "maxResponseBytes": 2097152,
                  "maxTotalResponseBytes": 4194304, "minIntervalSeconds": 1,
                  "queryKeys": [], "pinnedIpv4": ["93.184.216.34"]}
        broker = B.Broker(self.journal, INSTANCE, IMAGE, lambda: self.engine, egress_policy=policy)
        self.brokers.append(broker)
        request = self.request(broker)
        request["egressPolicySha256"] = broker.egress_sha
        settings = {"enabled": True, "adapter": "trawl", "endpoint": "http://private-gateway.invalid:8191",
                    "timeoutMillis": 30000}
        request["evidenceGatewayJson"] = json.dumps(settings)
        created = []
        factory = B.EG.RuntimeGateway

        def gateway(*args):
            instance = factory(*args)
            instance.close = unittest.mock.Mock(return_value=True)
            created.append(instance)
            return instance

        def fail(engine, worker_request, source, hashes, mediator, *, cancelled):
            self.assertNotIn("evidenceGatewayJson", worker_request)
            self.assertNotIn("private-gateway", json.dumps(mediator.loader_profile))
            self.assertEqual(settings, mediator.runtime_gateway.settings)
            error = B.S.Failure("ENGINE_TIMEOUT")
            error.exchange_context = {"phase": "fetch", "lastHttpId": 1, "loaderCallCount": 0,
                                      "policyDenied": False, "peerExitCode": None}
            raise error

        with patch.object(B.EG, "RuntimeGateway", side_effect=gateway), patch.object(B.G, "acceptance", side_effect=fail):
            broker.handle(request)
            broker.threads[JOB].join(5)
        row = json.loads((self.journal / (JOB + ".json")).read_bytes())
        self.assertEqual("FAILED", row["status"])
        self.assertEqual("ENGINE_TIMEOUT", row["code"])
        self.assertEqual(B.S.digest(request["evidenceGatewayJson"].encode()), row["evidenceGatewaySettingsSha256"])
        self.assertNotIn("private-gateway", json.dumps(row))
        summary = row["diagnostics"]["exchangeContext"]["runtimeGateway"]
        self.assertEqual("trawl", summary["adapter"])
        self.assertEqual(0, summary["requestCount"])
        self.assertTrue(summary["cleanupConfirmed"])
        created[0].close.assert_called_once()
        cancel = self.request(broker, "cancel_acceptance")
        cancel["egressPolicySha256"] = broker.egress_sha
        broker.handle(cancel)
        self.assertEqual({}, broker.job_gateways)

    def test_runtime_gateway_cleanup_failure_never_becomes_a_completed_job(self):
        policy = {"schemaVersion": 2, "origin": "https://example.com", "paths": ["/"],
                  "methods": ["GET", "HEAD"], "maxRequests": 6, "maxResponseBytes": 2097152,
                  "maxTotalResponseBytes": 4194304, "minIntervalSeconds": 1,
                  "queryKeys": [], "pinnedIpv4": ["93.184.216.34"]}
        broker = B.Broker(self.journal, INSTANCE, IMAGE, lambda: self.engine, egress_policy=policy)
        self.brokers.append(broker)
        request = self.request(broker)
        request["egressPolicySha256"] = broker.egress_sha
        request["evidenceGatewayJson"] = json.dumps({"enabled": True, "adapter": "trawl",
            "endpoint": "http://gateway.invalid:8191", "timeoutMillis": 30000})
        with patch.object(B.EG.RuntimeGateway, "close", return_value=False), \
                patch.object(B.G, "acceptance", return_value={"stdoutBytes": 0, "stderrBytes": 0}):
            broker.handle(request)
            broker.threads[JOB].join(5)
        row = json.loads((self.journal / (JOB + ".json")).read_bytes())
        self.assertEqual("FAILED", row["status"])
        self.assertEqual("EVIDENCE_GATEWAY_CLEANUP_UNCONFIRMED", row["code"])
        self.assertNotIn("result", row)

    def test_invalid_runtime_gateway_configuration_never_creates_a_job(self):
        policy = {"schemaVersion": 2, "origin": "https://example.com", "paths": ["/"],
                  "methods": ["GET", "HEAD"], "maxRequests": 6, "maxResponseBytes": 2097152,
                  "maxTotalResponseBytes": 4194304, "minIntervalSeconds": 1,
                  "queryKeys": [], "pinnedIpv4": ["93.184.216.34"]}
        broker = B.Broker(self.journal, INSTANCE, IMAGE, lambda: self.engine, egress_policy=policy)
        self.brokers.append(broker)
        request = self.request(broker)
        request["egressPolicySha256"] = broker.egress_sha
        for settings in ({}, {"enabled": True, "adapter": "trawl", "endpoint": "http://user:private@gateway/",
                              "timeoutMillis": 30000}):
            request["evidenceGatewayJson"] = json.dumps(settings)
            with self.assertRaisesRegex(B.S.Failure, "EVIDENCE_GATEWAY_CONFIGURATION_INVALID"):
                broker.handle(request)
        self.assertEqual({}, broker.jobs)
        self.assertEqual([], self.engine.calls)

    def test_invalid_frozen_profile_never_creates_a_job_or_executes_source(self):
        policy = {"schemaVersion": 2, "origin": "https://example.com", "paths": ["/"],
                  "methods": ["GET", "HEAD"], "maxRequests": 6, "maxResponseBytes": 2097152,
                  "maxTotalResponseBytes": 4194304, "minIntervalSeconds": 1,
                  "queryKeys": [], "pinnedIpv4": ["93.184.216.34"]}
        broker = B.Broker(self.journal, INSTANCE, IMAGE, lambda: self.engine, egress_policy=policy)
        self.brokers.append(broker)
        valid = self.request(broker)
        valid["egressPolicySha256"] = broker.egress_sha
        original = json.loads(valid["loaderProfileJson"])
        invalid = [None, "x" * 16385, '{"schemaVersion":1,"schemaVersion":1}']
        for change in ({"schemaVersion": True}, {"requiredNodes": []}, {"runtime": "fongmi"},
                       {"requiredNodes": ["S06", "S06"]}, {"requiredNodes": ["UNKNOWN"]},
                       {"extFingerprint": "credential-reference"}, {"detailIdentity": "invented"}):
            invalid.append(json.dumps({**original, **change}))
        for encoded in invalid:
            with self.subTest(encoded=encoded), self.assertRaises(B.S.Failure):
                broker.handle({**valid, "loaderProfileJson": encoded})
        self.assertFalse((self.journal / (JOB + ".json")).exists())
        self.assertEqual([], self.engine.calls)

    def test_business_exchange_cancellation_does_not_start_a_host_or_source_process(self):
        self.assertEqual(20, B.M.POLICY["runtimeTimeoutSeconds"])
        self.assertEqual(60, B.G.exchange.__kwdefaults__["timeout"])
        self.assertEqual(B.G.POLICY, B.B.document()["egressPolicy"])
        with patch.object(B.G.subprocess, "Popen") as process:
            with self.assertRaisesRegex(B.S.Failure, "MANAGED_CANCELLED"):
                B.G.exchange([], P.request(), P.SOURCE, {}, None, cancelled=lambda: True)
            process.assert_not_called()

    def test_multi_origin_policy_uses_the_same_broker_identity_and_execution_path(self):
        policy = {"schemaVersion": 3, "methods": ["GET", "HEAD"], "maxRequests": 12,
                  "maxResponseBytes": 2097152, "maxTotalResponseBytes": 4194304, "minIntervalSeconds": 1,
                  "routes": [{"origin": "https://example.com", "exactUrls": ["https://example.com/a"],
                              "pinnedIpv4": ["8.8.8.8"]},
                             {"origin": "https://media.example.com", "exactUrls": [],
                              "pinnedIpv4": ["1.1.1.1"]}]}
        broker = B.Broker(self.journal, INSTANCE, IMAGE, lambda: self.engine, egress_policy=policy)
        self.brokers.append(broker)
        self.assertEqual(B.S.digest(B.S.encode(policy)), broker.identity()["egressPolicySha256"])
        with patch.object(B.G, "acceptance", return_value={"stdoutBytes": 0, "stderrBytes": 0}) as run:
            broker.sample(self.engine, {"jobId": JOB}, SOURCE, "", "", cancelled=lambda: False)
        mediator = run.call_args.args[4]
        self.assertIsInstance(mediator, B.H.Routes)
        self.assertEqual(policy, mediator.policy)
        self.assertEqual(2, len(mediator.routes))

    def test_rpc_and_session_share_the_trusted_budget_deadline(self):
        policy = {"schemaVersion": 2, "origin": "https://example.com", "paths": ["/"],
                  "methods": ["GET", "HEAD"], "maxRequests": 64, "maxResponseBytes": 2097152,
                  "maxTotalResponseBytes": 4194304, "minIntervalSeconds": 1,
                  "queryKeys": [], "pinnedIpv4": ["93.184.216.34"]}
        broker = B.Broker(self.journal, INSTANCE, IMAGE, lambda: self.engine, egress_policy=policy)
        self.brokers.append(broker)
        for option in ("t4_call", "t4_session"):
            with self.subTest(option=option), patch.object(
                    B.G, "run", return_value={"stdoutBytes": 0, "stderrBytes": 0}) as run:
                call = object()
                broker.sample(self.engine, {"jobId": JOB}, SOURCE, "", "", cancelled=lambda: False,
                              **{option: call})
                self.assertIs(call, run.call_args.kwargs[option])
                self.assertEqual(124, run.call_args.kwargs["exchange_timeout"])
                self.assertEqual(policy, run.call_args.args[4].policy)
        self.assertEqual([], self.engine.calls)

    def test_later_create_without_ack_cannot_reuse_previous_container_identity(self):
        tracked = B.TrackedEngine(self.engine)
        tracked.call(["container", "create"])
        self.assertTrue(tracked.create_confirmed)
        self.engine.lost_ack = True
        with self.assertRaises(B.S.Failure):
            tracked.call(["container", "create"])
        self.assertFalse(tracked.create_confirmed)
        self.assertIsNone(tracked.create_id)
        self.assertEqual(0, tracked.create_ack_bytes)


if __name__ == "__main__":
    unittest.main()
