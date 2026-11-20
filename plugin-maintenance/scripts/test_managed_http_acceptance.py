"""Formal HTTP orchestration with fake drivers/Engine; never execute submitted source."""
import base64
import copy
import json
import sys
import threading
import time
import unittest
from unittest.mock import Mock, patch

import managed_http_acceptance as H
import managed_source_broker as B
import test_managed_source_broker as F
import test_managed_t4_broker as T
import test_managed_t4_rpc as R

HTTP_IMAGE = "sha256:" + "e" * 64


class FakeDriver:
    def __init__(self, owner):
        self.owner = owner
        self.ident = "f" * 64
        self.cleanup = {"confirmed": True, "containerAbsent": True}

    def run(self, plan, cancelled):
        self.owner.plans.append(copy.deepcopy(plan))
        return self.owner.drive(plan, cancelled)


class HttpBrokerTest(unittest.TestCase):
    setUp = T.T4BrokerTest.setUp
    tearDown = T.T4BrokerTest.tearDown
    request = T.T4BrokerTest.request
    execute = T.T4BrokerTest.execute
    done = T.T4BrokerTest.done
    parent = T.AcceptanceParentTest.parent

    def broker(self):
        self.plans = []
        self.driver = FakeDriver(self)
        broker = B.Broker(self.journal, F.INSTANCE, F.IMAGE, lambda: self.engine,
                          egress_policy=T.POLICY, http_image_id=HTTP_IMAGE,
                          http_driver=lambda *args: self.driver)
        self.brokers.append(broker)
        self.current = broker
        self.drive = self.matrix
        return broker

    def start(self, broker):
        value = self.request(broker, "start_http", job=F.INSTANCE)
        profile = {"schemaVersion": 1, "runtime": "t4",
                   "runtimeRevision": broker.bundle["runtimeSha256"],
                   "requiredNodes": ["S06", "R04", "R05"]}
        value.update(sourceSha256=B.S.digest(F.SOURCE),
                     sourceBase64=base64.b64encode(F.SOURCE).decode(),
                     loaderProfileJson=json.dumps(profile))
        return broker.handle(value)

    def finish(self, broker):
        broker.http_threads[F.INSTANCE].join(15)
        self.assertFalse(broker.http_threads[F.INSTANCE].is_alive())
        return broker.handle(self.request(broker, "status", job=F.INSTANCE))

    def matrix(self, plan, cancelled):
        broker = self.current
        self.assertIsNone(broker.active)
        parent = broker.jobs[F.INSTANCE]
        self.assertEqual("COMPLETED", parent["status"])
        self.assertTrue(parent["cleanup"]["containerAbsent"])
        self.assertEqual(F.INSTANCE, plan["parentJobId"])
        observed = {key: parent[key] for key in (
            "jobId", "instanceId", "imageId", "bundleSha256", "executionPolicySha256",
            "egressPolicySha256", "sourceSha256")}
        observed.update(schemaVersion=1, authority="at_t4_http_owner_observed",
                        scope="t4-http-parameters-v1", runtimeSha256=broker.bundle["runtimeSha256"],
                        inputProfileSha256=parent["loaderProfileSha256"], allPlannedCallsObserved=True,
                        rawOutputStored=False, formalAcceptanceIntegrated=False,
                        productNodePassesAdded=0, publishEligible=False, calls=[])
        for index in range(36):
            B.M.check_cancelled(cancelled)
            call = copy.deepcopy(R.CALLS[(0, 1, 1, 2, 3, 4)[index % 6]])
            call["playType"] = ("t4", "t4-direct")[index // 18]
            if index % 6 in (1, 2):
                call["params"]["pg"] = str(index % 6)
            job = "00000000-0000-4000-8000-%012d" % (index + 1)
            broker.handle({**self.request(broker, call=call, job=job), "parentJobId": F.INSTANCE})
            self.assertEqual("COMPLETED", self.done(broker, job)["status"])
            broker.threads[job].join(2)
            delivery = broker.handle(self.request(broker, "take", call, job))
            body = B.S.encode(delivery["result"])
            observed["calls"].append({
                "ordinal": index + 1, "shape": ("get", "form", "json")[(index // 6) % 3],
                "playType": call["playType"], "method": call["method"], "httpStatus": 200,
                "t4CallSha256": delivery["t4CallSha256"], "brokerJobId": job,
                "resultSha256": delivery["resultSha256"], "httpResponseSha256": B.S.digest(body),
                "httpResponseBytes": len(body), "workerReturnedAfterCleanup": True})
        return observed

    def test_completed_loader_runs_matrix_then_verifies_every_current_child(self):
        broker = self.broker()
        parent = self.parent(broker)
        self.start(broker)
        row = self.finish(broker)
        phase = row["httpAcceptance"]
        self.assertEqual("COMPLETED", phase["status"])
        self.assertTrue(phase["currentChildJournalsVerified"])
        self.assertEqual(36, len(phase["observation"]["calls"]))
        self.assertEqual(parent["result"], row["result"])
        self.assertIsNone(broker.active)
        self.assertIsNone(broker.http_active)
        self.assertIsNone(broker.pending_rpc)
        self.assertEqual(36, len(self.calls))
        self.assertEqual(1, len(self.plans))
        for path in self.journal.glob("*.json"):
            self.assertNotIn(b"PRIVATE", path.read_bytes())
            self.assertNotIn(b"sourceBase64", path.read_bytes())
        with self.assertRaisesRegex(B.S.Failure, "T4_HTTP_ALREADY_STARTED"):
            self.start(broker)

    def test_missing_cancelled_or_busy_parent_never_starts_driver(self):
        broker = self.broker()
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_ACCEPTANCE_PARENT_INVALID"):
            self.start(broker)
        self.parent(broker)
        broker.active = F.JOB
        try:
            with self.assertRaisesRegex(B.S.Failure, "MANAGED_RUNNER_BUSY"):
                self.start(broker)
        finally:
            broker.active = None
        broker.handle(self.request(broker, "cancel_acceptance", job=F.INSTANCE))
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_ACCEPTANCE_CANCELLED"):
            self.start(broker)
        self.assertEqual([], self.plans)

    def test_driver_failure_is_not_relabelled_as_operator_cancellation(self):
        broker = self.broker()
        self.parent(broker)
        self.drive = Mock(side_effect=B.S.Failure("ENGINE_TIMEOUT"))
        self.start(broker)
        row = self.finish(broker)
        self.assertEqual("FAILED", row["httpAcceptance"]["status"])
        self.assertEqual("ENGINE_TIMEOUT", row["httpAcceptance"]["code"])
        self.assertTrue(row["acceptanceCancelled"])
        self.assertTrue(row["acceptanceCleanup"]["confirmed"])
        self.assertEqual([], self.calls)

    def test_safe_driver_failure_is_preserved_without_reclassifying_the_source(self):
        broker = self.broker()
        self.parent(broker)
        self.drive = Mock(side_effect=H.DriverFailure({
            "code": "T4_BROKER_TIMEOUT", "actions": 0, "cleanupConfirmed": True,
            "rawError": "PRIVATE_PROVIDER_OR_SOURCE_DATA"}))
        self.start(broker)
        row = self.finish(broker)
        phase = row["httpAcceptance"]
        self.assertEqual("FAILED", phase["status"])
        self.assertEqual("T4_HTTP_DRIVER_FAILED", phase["code"])
        self.assertEqual("T4_BROKER_TIMEOUT", phase["driverFailure"]["code"])
        self.assertEqual(0, phase["driverFailure"]["completedActions"])
        self.assertNotIn("PRIVATE", json.dumps(row))
        self.assertNotIn("sourceRequestFailure", phase)

    def test_only_a_current_bound_child_with_exact_query_separator_evidence_is_promoted(self):
        broker = self.broker()
        parent = {"jobId": F.INSTANCE, "loaderProfileSha256": "1" * 64,
                  "instanceId": broker.instance_id, "imageId": broker.image_id,
                  "bundleSha256": broker.bundle_sha, "executionPolicySha256": "d" * 64,
                  "egressPolicySha256": broker.egress_sha, "sourceSha256": B.S.digest(F.SOURCE)}
        job = "00000000-0000-4000-8000-000000000123"
        child = {**{key: parent[key] for key in (
            "instanceId", "imageId", "bundleSha256", "executionPolicySha256",
            "egressPolicySha256", "sourceSha256")},
            "jobId": job, "parentJobId": F.INSTANCE, "parentProfileSha256": "1" * 64,
            "status": "FAILED", "code": "EGRESS_PROTOCOL_INVALID",
            "cleanup": {"confirmed": True, "containerAbsent": True},
            "diagnostics": {"primaryCode": "EGRESS_PROTOCOL_INVALID", "exchangeContext": {
                "policyDenied": True, "lastDeniedCode": "EGRESS_URL_DENIED", "lastHttpId": 4,
                "requestDiagnostic": {"method": "GET", "requestUrlSha256": "a" * 64,
                                      "registeredUrlSha256": "b" * 64,
                                      "registeredQuerySeparatorMissing": True}}}}
        broker.save(child, exclusive=True)
        failure = broker.http_request_failure(parent, {job: child})
        self.assertEqual("host_registered_request_mismatch", failure["authority"])
        self.assertEqual(B.S.digest((broker.journal / (job + ".json")).read_bytes()),
                         failure["childJournalSha256"])
        self.assertNotIn("https://", json.dumps(failure).lower())
        self.assertNotIn('"url"', json.dumps(failure).lower())
        for changed in (
                {**child, "parentJobId": "00000000-0000-4000-8000-000000000124"},
                {**child, "status": "COMPLETED"},
                {**child, "cleanup": {"confirmed": True, "containerAbsent": False}},
                {**child, "diagnostics": {"primaryCode": "EGRESS_URL_DENIED"}},
        ):
            with self.subTest(changed=changed["status"]):
                self.assertIsNone(broker.http_request_failure(parent, {job: changed}))

    def test_cancel_waits_for_driver_and_discards_late_success(self):
        broker = self.broker()
        self.parent(broker)
        entered, release = threading.Event(), threading.Event()
        def late(*args):
            entered.set()
            self.assertTrue(release.wait(5))
            return {"late": True}
        self.drive = late
        self.start(broker)
        try:
            self.assertTrue(entered.wait(2))
            row = broker.handle(self.request(broker, "cancel_acceptance", job=F.INSTANCE))
            self.assertEqual("COMPLETED", row["status"])
            self.assertFalse(row["acceptanceCleanup"]["confirmed"])
            self.assertEqual(1, row["acceptanceCleanup"]["pendingJobs"])
            with self.assertRaisesRegex(B.S.Failure, "MANAGED_ACCEPTANCE_CANCELLED"):
                broker.handle({**self.request(broker), "parentJobId": F.INSTANCE})
        finally:
            release.set()
        row = self.finish(broker)
        self.assertEqual("CANCELLED", row["httpAcceptance"]["status"])
        self.assertNotIn("observation", row["httpAcceptance"])
        self.assertTrue(row["acceptanceCleanup"]["confirmed"])

    def test_receipt_cannot_borrow_a_missing_child_or_stale_result(self):
        broker = self.broker()
        self.parent(broker)
        matrix = self.matrix
        def changed(plan, cancelled):
            value = matrix(plan, cancelled)
            value["calls"][0]["resultSha256"] = "0" * 64
            return value
        self.drive = changed
        self.start(broker)
        row = self.finish(broker)
        self.assertEqual("FAILED", row["httpAcceptance"]["status"])
        self.assertEqual("T4_HTTP_RECEIPT_MISMATCH", row["httpAcceptance"]["code"])
        self.assertNotIn("observation", row["httpAcceptance"])
        self.assertTrue(row["acceptanceCancelled"])

    def test_cleanup_failure_and_journal_failure_never_deliver_observation(self):
        broker = self.broker()
        self.parent(broker)
        self.drive = Mock(return_value={})
        self.driver.cleanup = {"confirmed": False, "containerAbsent": False}
        self.start(broker)
        row = self.finish(broker)
        self.assertEqual("CLEANUP_UNCONFIRMED", row["httpAcceptance"]["status"])
        self.assertTrue(broker.blocked)
        self.assertFalse(row["acceptanceCleanup"]["confirmed"])
        self.assertNotIn("observation", row["httpAcceptance"])

    def test_final_http_journal_failure_blocks_reuse_and_observation(self):
        broker = self.broker()
        self.parent(broker)
        save = broker.save
        def failed(row, **kwargs):
            if row.get("httpAcceptance", {}).get("status") == "COMPLETED":
                raise OSError("PRIVATE_WRITE_FAILURE")
            return save(row, **kwargs)
        with patch.object(broker, "save", side_effect=failed):
            self.start(broker)
            row = self.finish(broker)
        self.assertEqual("CLEANUP_UNCONFIRMED", row["httpAcceptance"]["status"])
        self.assertEqual("MANAGED_JOURNAL_COMMIT_FAILED", row["httpAcceptance"]["code"])
        self.assertTrue(broker.blocked)
        self.assertNotIn("observation", row["httpAcceptance"])
        self.assertNotIn("PRIVATE", json.dumps(row))

    def test_combined_loader_and_http_receipts_survive_large_journal_and_restart(self):
        broker = self.broker()
        self.parent(broker)
        parent = broker.jobs[F.INSTANCE]
        parent["result"]["boundedHostEvidence"] = "x" * 60000
        self.assertLess(len(B.S.encode(parent)), 65536)
        broker.save(parent)
        self.start(broker)
        row = self.finish(broker)
        self.assertEqual("COMPLETED", row["httpAcceptance"]["status"])
        self.assertEqual(36, len(row["httpAcceptance"]["observation"]["calls"]))
        raw = (self.journal / (F.INSTANCE + ".json")).read_bytes()
        self.assertGreater(len(raw), 65536)
        self.assertLessEqual(len(raw), B.MAX_JOURNAL_BYTES)
        broker.close()
        restarted = self.broker()
        self.assertEqual(json.loads(raw), restarted.jobs[F.INSTANCE])
        self.assertFalse(restarted.blocked)

    def test_unrelated_work_cannot_consume_the_driver_single_source_slot(self):
        broker = self.broker()
        self.parent(broker)
        entered, release = threading.Event(), threading.Event()
        def waiting(*args):
            entered.set()
            self.assertTrue(release.wait(5))
            raise B.S.Failure("ENGINE_TIMEOUT")
        self.drive = waiting
        self.start(broker)
        try:
            self.assertTrue(entered.wait(2))
            with self.assertRaisesRegex(B.S.Failure, "MANAGED_RUNNER_BUSY"):
                broker.handle(self.request(broker))
            self.assertNotIn(F.JOB, broker.jobs)
        finally:
            release.set()
        self.finish(broker)

    def test_restart_keeps_http_cleanup_block_and_does_not_replay(self):
        broker = self.broker()
        self.parent(broker)
        row = broker.jobs[F.INSTANCE]
        row["httpAcceptance"] = {
            "schemaVersion": 1, "authority": "managed_t4_http_acceptance_observed",
            "imageId": HTTP_IMAGE, "status": "RUNNING",
            "cleanup": {"confirmed": False, "containerAbsent": False}}
        broker.save(row)
        broker.close()
        reopened = self.broker()
        phase = reopened.jobs[F.INSTANCE]["httpAcceptance"]
        self.assertEqual("CLEANUP_UNCONFIRMED", phase["status"])
        self.assertTrue(reopened.identity()["recoveryRequired"])
        self.assertEqual([], self.plans)
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_RUNNER_RECOVERY_REQUIRED"):
            self.start(reopened)


class DriverSafetyTest(unittest.TestCase):
    def test_nonzero_driver_exit_keeps_only_the_structured_failure_projection(self):
        report = {"completed": False, "httpOwners": "real_AT_lab_owners",
                  "pythonBroker": True, "fullAtApplication": False, "actions": 0,
                  "code": "T4_BROKER_TIMEOUT", "cleanupConfirmed": True, "rawError": "PRIVATE"}
        command = [sys.executable, "-c",
                   "import json,sys;sys.stdin.buffer.read();print(json.dumps("
                   + repr(report) + "));sys.exit(1)"]
        with self.assertRaises(H.DriverFailure) as raised:
            H.exchange(command, b"{}", lambda: False)
        self.assertEqual("T4_BROKER_TIMEOUT", raised.exception.diagnostic["code"])
        self.assertNotIn("PRIVATE", json.dumps(raised.exception.diagnostic))
        report["code"] = "PRIVATE_UPPERCASE_SECRET"
        self.assertEqual("HTTP_DRIVER_FAILED", H.DriverFailure(report).diagnostic["code"])
        for defect in ({"actions": True}, {"actions": 37}, {"completed": True},
                       {"pythonBroker": False}, {"cleanupConfirmed": "true"}):
            self.assertFalse(H.failed_driver_report({**report, **defect}))

    def test_preexisting_driver_is_not_reported_as_cleaned_or_removed(self):
        engine = Mock()
        engine.document.return_value = [{"Id": HTTP_IMAGE, "Os": "linux", "Config": {}}]
        engine.call.return_value = b"a" * 64
        driver = H.Driver(engine, HTTP_IMAGE, "/run/lab/executor.sock", F.JOB)
        with self.assertRaisesRegex(B.S.Failure, "JOB_ALREADY_EXISTS"):
            driver.run({}, lambda: False)
        self.assertFalse(driver.cleanup["confirmed"])
        self.assertFalse(driver.issued)
        self.assertFalse(any(call.args[0][:2] == ["container", "rm"] for call in engine.call.call_args_list))

    def test_cancel_before_start_never_touches_engine(self):
        engine = Mock()
        driver = H.Driver(engine, HTTP_IMAGE, "/run/lab/executor.sock", F.JOB)
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_CANCELLED"):
            driver.run({}, lambda: True)
        engine.document.assert_not_called()
        engine.call.assert_not_called()
        self.assertTrue(driver.cleanup["confirmed"])

    def test_unknown_create_identity_cannot_be_removed_by_name(self):
        driver = H.Driver(Mock(), HTTP_IMAGE, "/run/lab/executor.sock", F.JOB)
        driver.issued = True
        driver.cleanup = {"confirmed": False, "containerAbsent": False}
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_CLEANUP_UNCONFIRMED"):
            driver.remove()
        driver.engine.call.assert_not_called()


class HttpRecoveryTest(unittest.TestCase):
    setUp = F.BrokerTest.setUp
    tearDown = F.BrokerTest.tearDown
    request = F.BrokerTest.request
    recovery_fixture = F.BrokerTest.recovery_fixture

    def test_source_recovery_cannot_clear_an_unconfirmed_http_driver(self):
        broker, engine, digest = self.recovery_fixture()
        row = broker.row(self.request(broker, job=F.INSTANCE), "COMPLETED")
        row["httpAcceptance"] = {
            "status": "CLEANUP_UNCONFIRMED", "imageId": HTTP_IMAGE,
            "cleanup": {"confirmed": False, "containerAbsent": False}}
        broker.save(row, exclusive=True)
        broker.jobs[F.INSTANCE] = row
        broker.recover_owned(F.P.F.JOB, digest, F.P.F.ID)
        self.assertIsNone(engine.container)
        self.assertTrue(broker.identity()["recoveryRequired"])


def session_fixture(proxies=True, public_data=None, hls=False, source_identity=None):
    binding = dict(jobId=F.INSTANCE, instanceId=F.INSTANCE, imageId=F.IMAGE,
                   bundleSha256="b" * 64, runtimeSha256="c" * 64, executionPolicySha256="d" * 64,
                   egressPolicySha256="e" * 64, sourceSha256="a" * 64, inputProfileSha256="f" * 64)
    value = {**binding, "schemaVersion": 1, "authority": "at_t4_http_owner_observed",
             "scope": "t4-http-parameters-v1", "allPlannedCallsObserved": True, "rawOutputStored": False,
             "formalAcceptanceIntegrated": False, "productNodePassesAdded": 0, "publishEligible": False,
             "sessionMode": True, "sessionReceipts": [], "calls": [], "proxyCalls": []}
    binding.update(sourceDataPresent=bool(public_data), sourceDataSha256=B.rpc_digest(public_data or {}))
    children = {}
    for segment, mode in enumerate(("t4", "t4-direct")):
        job = "00000000-0000-4000-8000-%012d" % (segment + 100)
        session = dict(brokerJobId=job, runtimeSha256=binding["runtimeSha256"], loadedTextSha256="a" * 64,
                       playType=mode, sourceDataPresent=binding["sourceDataPresent"],
                       sourceDataSha256=binding["sourceDataSha256"],
                       initCount=1, destroyReturned=True, actions=[],
                       cleanup={"confirmed": True, "containerAbsent": True},
                       initializationKind="credential_free_local_source_projection", fullGeneratedExtForwarded=False)
        if source_identity:
            session.update(source_identity)
        value["sessionReceipts"].append(session)
        def action(method):
            ordinal = len(session["actions"]) + 1
            row = dict(ordinal=ordinal, workerCallId=ordinal + 1, method=method,
                       t4CallSha256=B.rpc_digest({"fixture": str(ordinal), "mode": mode}),
                       paramsSha256="1" * 64, resultSha256="2" * 64)
            session["actions"].append(row)
            return {key: row[key] for key in ("t4CallSha256", "resultSha256")} | {
                "brokerJobId": job, "sessionOrdinal": ordinal}
        for index in range(segment * 18, (segment + 1) * 18):
            method = ("home", "category", "category", "detail", "search", "play")[index % 6]
            value["calls"].append({
                **action(method), "ordinal": index + 1, "shape": ("get", "form", "json")[(index // 6) % 3],
                "playType": mode, "method": method, "httpStatus": 200, "httpResponseBytes": 2,
                "httpResponseSha256": B.S.digest(b"{}"), "workerReturnedAfterCleanup": False, "cleanupPending": True})
            if method == "play" and proxies:
                for head in (False, True):
                    manifest = hls and not head
                    media_body = b"\x47" * 564
                    manifest_body = (b"#EXTM3U\n#EXTINF:5,\n"
                                     b"http://127.0.0.1/session/fixture/local?type=segment\n")
                    body = manifest_body if manifest else media_body
                    value["proxyCalls"].append({
                        **action("proxy"), "ordinal": len(value["proxyCalls"]) + 1,
                        "playerOrdinal": index + 1,
                        "httpMethod": "HEAD" if head and not hls else "GET",
                        "httpStatus": 200 if manifest else 206, "targetSha256": "3" * 64,
                        "responseSha256": B.S.digest(b"" if head and not hls else body),
                        "responseBytes": 0 if head and not hls else len(body),
                        "contentLength": len(body),
                        "mime": "application/vnd.apple.mpegurl" if manifest else "video/mp2t",
                        "contentRange": "" if manifest else "bytes 0-563/564"})
                    if manifest:
                        value["proxyCalls"][-1].update(
                            sourceBodySha256="4" * 64, sourceBytes=len(manifest_body),
                            segmentTargetSha256="3" * 64)
        receipt = {key: copy.deepcopy(item) for key, item in session.items() if key != "brokerJobId"}
        receipt.update(schemaVersion=1, authority="host_t4_session_observed", inputSourceSha256=binding["sourceSha256"],
                       rawOutputStored=False, businessChainVerified=False, publishEligible=False)
        children[job] = {
            **{key: binding[key] for key in ("instanceId", "imageId", "bundleSha256", "executionPolicySha256",
                                           "egressPolicySha256", "sourceSha256")},
            "schemaVersion": 1, "authority": "managed_source_broker_observed", "jobId": job,
            "parentJobId": binding["jobId"], "parentProfileSha256": binding["inputProfileSha256"],
            "status": "COMPLETED", "executionKind": "t4_bounded_session", "businessChainVerified": False,
            "publishEligible": False, "t4CallSha256": session["actions"][0]["t4CallSha256"],
            "sessionActions": copy.deepcopy(session["actions"]),
            "cleanup": {"confirmed": True, "containerAbsent": True,
                        "basis": "create_ack_exact_id_absent_and_worker_exited"},
            "result": {"schemaVersion": 1, "authority": "managed_source_host_observed",
                       "executionKind": "t4_bounded_session", "rawOutputStored": False,
                       "businessChainVerified": False, "publishEligible": False, "t4SessionValidation": receipt}}
    return value, binding, children


class SessionVerificationTests(unittest.TestCase):
    def test_source_identity_is_carried_by_both_sessions_and_current_child_receipts(self):
        identity = {"sourceRuleSha256": "1" * 64, "dependencySha256": "2" * 64}
        value, binding, children = session_fixture(source_identity=identity)
        self.assertEqual(value, H.verify_observation(value, binding, children))
        value["sessionReceipts"][1]["sourceRuleSha256"] = "0" * 64
        with self.assertRaisesRegex(B.S.Failure, "T4_HTTP_RECEIPT_MISMATCH"):
            H.verify_observation(value, binding, children)

        value, binding, children = session_fixture(source_identity=identity)
        children[next(iter(children))]["result"]["t4SessionValidation"]["dependencySha256"] = "0" * 64
        with self.assertRaisesRegex(B.S.Failure, "T4_HTTP_RECEIPT_MISMATCH"):
            H.verify_observation(value, binding, children)

    def test_public_configuration_is_bound_to_the_parent_not_only_between_sessions(self):
        value, binding, children = session_fixture(public_data={"label": "PUBLIC_DATA_SENTINEL"})
        self.assertEqual(value, H.verify_observation(value, binding, children))
        self.assertNotIn("PUBLIC_DATA_SENTINEL", json.dumps(value))
        for session in value["sessionReceipts"]:
            session["sourceDataSha256"] = B.rpc_digest({"label": "other public settings"})
            children[session["brokerJobId"]]["result"]["t4SessionValidation"]["sourceDataSha256"] = (
                session["sourceDataSha256"])
        with self.assertRaisesRegex(B.S.Failure, "T4_HTTP_RECEIPT_MISMATCH"):
            H.verify_observation(value, binding, children)

    def test_missing_malformed_or_stale_parent_configuration_identity_is_rejected(self):
        for defect in ("missing", "type", "digest", "present"):
            with self.subTest(defect=defect):
                value, binding, children = session_fixture()
                if defect == "missing": binding.pop("sourceDataPresent")
                elif defect == "type": binding["sourceDataPresent"] = 0
                elif defect == "digest": binding["sourceDataSha256"] = "0" * 64
                else: binding["sourceDataPresent"] = True
                with self.assertRaisesRegex(B.S.Failure, "T4_HTTP_RECEIPT_MISMATCH"):
                    H.verify_observation(value, binding, children)

    def test_two_current_session_rows_correlate_all_http_and_proxy_actions_without_raw_body(self):
        for proxies in (False, True):
            value, binding, children = session_fixture(proxies)
            self.assertEqual(value, H.verify_observation(value, binding, children))
            self.assertEqual(36, len(value["calls"]))
            self.assertEqual(12 if proxies else 0, len(value["proxyCalls"]))
            self.assertLess(len(B.S.encode(value)), 65536)

    def test_hls_manifest_and_same_chain_segment_are_accepted(self):
        value, binding, children = session_fixture(hls=True)
        for row in value["proxyCalls"]:
            if row["mime"].startswith("application/vnd.apple.mpegurl"):
                row.update(sourceBytes=190000, responseBytes=250000, contentLength=250000)
        self.assertEqual(value, H.verify_observation(value, binding, children))
        self.assertEqual(6, sum(
            1 for row in value["proxyCalls"]
            if row["mime"].startswith("application/vnd.apple.mpegurl")))

    def test_hls_missing_rewrite_identity_unbound_segment_and_head_substitution_are_rejected(self):
        for defect in ("source", "length", "unbound", "head", "twoManifests", "wrongPlayer", "raw"):
            with self.subTest(defect=defect):
                value, binding, children = session_fixture(hls=True)
                manifest, segment = value["proxyCalls"][:2]
                if defect == "source": manifest.pop("sourceBodySha256")
                elif defect == "length": manifest["sourceBytes"] = 524289
                elif defect == "unbound": segment["targetSha256"] = "0" * 64
                elif defect == "head": segment["httpMethod"] = "HEAD"
                elif defect == "twoManifests": segment["mime"] = manifest["mime"]
                elif defect == "wrongPlayer": segment["playerOrdinal"] = 12
                else: manifest["body"] = "PRIVATE"
                with self.assertRaisesRegex(B.S.Failure, "T4_HTTP_RECEIPT_MISMATCH"):
                    H.verify_observation(value, binding, children)
    def test_missing_stale_cancelled_or_changed_current_session_cannot_be_borrowed(self):
        for defect in ("missing", "extra", "cancel", "parent", "profile", "cleanup", "actions", "runtime", "data"):
            with self.subTest(defect=defect):
                value, binding, children = session_fixture()
                job, child = next(iter(children.items()))
                if defect == "missing":
                    children.pop(job)
                elif defect == "extra":
                    children[F.JOB] = child
                elif defect == "cancel":
                    child["status"] = "CANCELLED"
                elif defect == "parent":
                    child["parentJobId"] = F.JOB
                elif defect == "profile":
                    child["parentProfileSha256"] = "0" * 64
                elif defect == "cleanup":
                    child["cleanup"]["containerAbsent"] = False
                elif defect == "actions":
                    child["sessionActions"].pop()
                else:
                    child["result"]["t4SessionValidation"][
                        "runtimeSha256" if defect == "runtime" else "sourceDataSha256"] = "0" * 64
                with self.assertRaisesRegex(B.S.Failure, "T4_HTTP_RECEIPT_MISMATCH"):
                    H.verify_observation(value, binding, children)

    def test_reordered_duplicated_extra_or_forged_http_proxy_evidence_is_rejected(self):
        for defect in ("mode", "job", "ordinal", "result", "earlyCleanup", "proxyMissing", "proxyDuplicate",
                       "proxyPlayer", "proxySession", "proxyBytes", "proxyHead", "proxyRange", "proxyMime", "raw"):
            with self.subTest(defect=defect):
                value, binding, children = session_fixture()
                call, proxy = value["calls"][0], value["proxyCalls"][0]
                if defect == "mode": call["playType"] = "t4-direct"
                elif defect == "job": call["brokerJobId"] = F.JOB
                elif defect == "ordinal": call["sessionOrdinal"] = 2
                elif defect == "result": call["resultSha256"] = "0" * 64
                elif defect == "earlyCleanup": call["workerReturnedAfterCleanup"] = True
                elif defect == "proxyMissing": value["proxyCalls"].pop()
                elif defect == "proxyDuplicate": value["proxyCalls"][2] = copy.deepcopy(proxy)
                elif defect == "proxyPlayer": proxy["playerOrdinal"] = 12
                elif defect == "proxySession": proxy["sessionOrdinal"] += 1
                elif defect == "proxyBytes": proxy["responseBytes"] -= 1
                elif defect == "proxyHead": value["proxyCalls"][1]["responseSha256"] = "0" * 64
                elif defect == "proxyRange": proxy["contentRange"] = "bytes 0-999/1000"
                elif defect == "proxyMime": proxy["mime"] = "text/html"
                elif defect == "raw": proxy["body"] = "PRIVATE"
                with self.assertRaisesRegex(B.S.Failure, "T4_HTTP_RECEIPT_MISMATCH"):
                    H.verify_observation(value, binding, children)


if __name__ == "__main__":
    unittest.main()
