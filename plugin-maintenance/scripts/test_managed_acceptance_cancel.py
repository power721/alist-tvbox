"""Acceptance cancellation against real broker state; fake Engine, no submitted code execution."""
import copy
import json
import threading
import unittest
from unittest.mock import patch

import managed_source_broker as B
import test_managed_source_broker as F
import test_managed_t4_broker as T


class AcceptanceCancellationTest(unittest.TestCase):
    setUp = T.T4BrokerTest.setUp
    tearDown = T.T4BrokerTest.tearDown
    broker = T.T4BrokerTest.broker
    request = T.T4BrokerTest.request
    execute = T.T4BrokerTest.execute
    done = T.T4BrokerTest.done
    parent = T.AcceptanceParentTest.parent

    def cancel(self, broker, **changes):
        return broker.handle({**self.request(broker, "cancel_acceptance", job=F.INSTANCE), **changes})

    def child(self, broker):
        return broker.handle({**self.request(broker), "parentJobId": F.INSTANCE})

    def test_normal_parent_cleanup_does_not_cancel_child_or_future_admission(self):
        broker = self.broker()
        original = self.parent(broker)
        self.assertEqual(original, broker.handle(self.request(broker, "cancel", job=F.INSTANCE)))
        self.child(broker)
        self.assertEqual("COMPLETED", self.done(broker)["status"])
        self.assertEqual(self.output, broker.handle(self.request(broker, "take"))["result"])

    def test_completed_parent_keeps_history_but_discards_bound_body_and_fences_new_start(self):
        broker = self.broker()
        original = self.parent(broker)
        self.child(broker)
        self.done(broker)
        broker.threads[F.JOB].join(2)
        cancelled = self.cancel(broker)
        self.assertEqual(original["result"], cancelled["result"])
        self.assertEqual("COMPLETED", cancelled["status"])
        self.assertTrue(cancelled["acceptanceCancelled"])
        self.assertEqual({"confirmed": True, "childJobs": 1, "pendingJobs": 0,
                          "recoveryRequired": False}, cancelled["acceptanceCleanup"])
        self.assertIsNone(broker.pending_rpc)
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_ACCEPTANCE_CANCELLED"):
            broker.handle(self.request(broker, "take"))
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_ACCEPTANCE_CANCELLED"):
            broker.handle({**self.request(broker, job="00000000-0000-4000-8000-000000000001"),
                           "parentJobId": F.INSTANCE})
        self.assertEqual(cancelled, self.cancel(broker))  # lost ACK: intent is idempotent

    def test_parent_terminal_is_not_cleanup_confirmation_while_child_cleanup_is_running(self):
        broker = self.broker()
        self.parent(broker)
        entered, release = threading.Event(), threading.Event()
        def cleanup(*args, **kwargs):
            entered.set()
            self.assertTrue(release.wait(5))
            return {"confirmed": True, "containerAbsent": True}
        with patch.object(B.M, "confirm_cleanup", side_effect=cleanup):
            self.child(broker)
            try:
                self.assertTrue(entered.wait(2))
                cancelled = self.cancel(broker)
                self.assertEqual("COMPLETED", cancelled["status"])
                self.assertFalse(cancelled["acceptanceCleanup"]["confirmed"])
                self.assertEqual(1, cancelled["acceptanceCleanup"]["pendingJobs"])
                self.assertIsNotNone(broker.active)
                self.assertTrue(broker.events[F.JOB].is_set())
            finally:
                release.set()
            self.assertEqual("CANCELLED", self.done(broker)["status"])
        broker.threads[F.JOB].join(2)
        self.assertTrue(self.cancel(broker)["acceptanceCleanup"]["confirmed"])
        self.assertIsNone(broker.pending_rpc)

    def test_cancel_before_start_is_a_durable_fence(self):
        broker = self.broker()
        row = broker.handle(self.request(broker, "cancel_acceptance"))
        self.assertTrue(row["acceptanceCancelled"])
        self.assertEqual("CANCELLED", row["status"])
        self.assertTrue(row["acceptanceCleanup"]["confirmed"])
        broker.close()
        reopened = self.broker()
        with self.assertRaisesRegex(B.S.Failure, "JOB_ALREADY_EXISTS"):
            reopened.handle(self.request(reopened))
        self.assertEqual([], self.engine.calls)

    def test_restart_keeps_cancel_intent_without_invalidating_historical_parent_binding(self):
        broker = self.broker()
        self.parent(broker)
        self.child(broker)
        self.done(broker)
        broker.threads[F.JOB].join(2)
        self.cancel(broker)
        broker.close()
        reopened = self.broker()
        parent = reopened.handle(self.request(reopened, "status", job=F.INSTANCE))
        self.assertTrue(parent["acceptanceCleanup"]["confirmed"])
        self.assertEqual(F.INSTANCE, reopened.jobs[F.JOB]["parentJobId"])
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_ACCEPTANCE_CANCELLED"):
            reopened.handle({**self.request(reopened, job="00000000-0000-4000-8000-000000000001"),
                             "parentJobId": F.INSTANCE})
        self.assertEqual(1, len(self.calls))

    def test_journal_failure_cancels_children_but_never_confirms_or_admits_more_work(self):
        broker = self.broker()
        self.parent(broker)
        entered, release = threading.Event(), threading.Event()
        def cleanup(*args, **kwargs):
            entered.set()
            self.assertTrue(release.wait(5))
            return {"confirmed": True, "containerAbsent": True}
        with patch.object(B.M, "confirm_cleanup", side_effect=cleanup):
            self.child(broker)
            try:
                self.assertTrue(entered.wait(2))
                with patch.object(broker, "save", side_effect=OSError("PRIVATE")):
                    with self.assertRaisesRegex(B.S.Failure, "MANAGED_JOURNAL_COMMIT_FAILED"):
                        self.cancel(broker)
                self.assertTrue(broker.blocked)
                self.assertTrue(broker.events[F.JOB].is_set())
                status = broker.handle(self.request(broker, "status", job=F.INSTANCE))
                self.assertFalse(status["acceptanceCleanup"]["confirmed"])
                self.assertTrue(status["acceptanceCleanup"]["recoveryRequired"])
                with self.assertRaisesRegex(B.S.Failure, "MANAGED_RUNNER_RECOVERY_REQUIRED"):
                    broker.handle(self.request(broker, job="00000000-0000-4000-8000-000000000001"))
            finally:
                release.set()
            self.assertEqual("CANCELLED", self.done(broker)["status"])
        self.assertIsNone(broker.pending_rpc)
        persisted = B.S.decode((self.journal / (F.INSTANCE + ".json")).read_bytes())
        self.assertNotIn("acceptanceCancelled", persisted)

    def test_running_parent_waits_for_its_worker_and_discards_late_result(self):
        broker = self.broker()
        entered, release = threading.Event(), threading.Event()
        def acceptance(engine, *args, **kwargs):
            engine.call(["container", "create"])
            entered.set()
            self.assertTrue(release.wait(5))
            return {"stdoutBytes": 0, "stderrBytes": 0}
        value = self.request(broker, job=F.INSTANCE)
        del value["t4CallJson"]
        value["loaderProfileJson"] = json.dumps(
            {"schemaVersion": 1, "runtime": "t4", "requiredNodes": ["S06", "R04"]})
        with patch.object(B.G, "acceptance", side_effect=acceptance):
            broker.handle(value)
            try:
                self.assertTrue(entered.wait(2))
                row = self.cancel(broker)
                self.assertEqual("RUNNING", row["status"])
                self.assertFalse(row["acceptanceCleanup"]["confirmed"])
                self.assertEqual(1, row["acceptanceCleanup"]["pendingJobs"])
            finally:
                release.set()
            row = self.done(broker, F.INSTANCE)
        broker.threads[F.INSTANCE].join(2)
        self.assertEqual("CANCELLED", row["status"])
        self.assertNotIn("result", row)
        self.assertTrue(self.cancel(broker)["acceptanceCleanup"]["confirmed"])

    def test_child_cleanup_failure_blocks_acceptance_confirmation(self):
        broker = self.broker()
        self.parent(broker)
        with patch.object(B.M, "confirm_cleanup", return_value={
                "confirmed": False, "containerAbsent": False}):
            self.child(broker)
            self.assertEqual("CLEANUP_UNCONFIRMED", self.done(broker)["status"])
        broker.threads[F.JOB].join(2)
        row = self.cancel(broker)
        self.assertEqual("COMPLETED", row["status"])
        self.assertFalse(row["acceptanceCleanup"]["confirmed"])
        self.assertTrue(row["acceptanceCleanup"]["recoveryRequired"])
        self.assertIsNone(broker.pending_rpc)

    def test_cancelling_another_parent_does_not_discard_or_cancel_this_child(self):
        broker = self.broker()
        other = copy.deepcopy(self.parent(broker))
        other["jobId"] = "00000000-0000-4000-8000-000000000001"
        broker.save(other, exclusive=True)
        broker.jobs[other["jobId"]] = other
        broker.handle({**self.request(broker), "parentJobId": other["jobId"]})
        self.assertEqual("COMPLETED", self.done(broker)["status"])
        row = self.cancel(broker)
        self.assertTrue(row["acceptanceCleanup"]["confirmed"])
        self.assertEqual(0, row["acceptanceCleanup"]["childJobs"])
        self.assertFalse(broker.events[F.JOB].is_set())
        self.assertEqual(self.output, broker.handle(self.request(broker, "take"))["result"])

    def test_wrong_identity_or_child_target_cannot_cancel_an_acceptance(self):
        broker = self.broker()
        original = self.parent(broker)
        self.child(broker)
        self.done(broker)
        for change in ({"executionPolicySha256": "e" * 64}, {"bundleSha256": "e" * 64},
                       {"jobId": F.JOB}):
            with self.subTest(change=change), self.assertRaises(B.S.Failure):
                self.cancel(broker, **change)
        self.assertEqual(original, broker.jobs[F.INSTANCE])
        self.assertFalse(broker.events[F.JOB].is_set())
        self.assertIsNotNone(broker.pending_rpc)

    def test_cancel_start_race_never_leaves_an_admitted_uncancelled_child(self):
        broker = self.broker()
        self.parent(broker)
        barrier = threading.Barrier(3)
        errors = []
        def run(function):
            barrier.wait(timeout=3)
            try:
                function()
            except B.S.Failure as error:
                errors.append(error.code)
        threads = [threading.Thread(target=run, args=(function,))
                   for function in (lambda: self.child(broker), lambda: self.cancel(broker))]
        for thread in threads:
            thread.start()
        barrier.wait(timeout=3)
        for thread in threads:
            thread.join(3)
            self.assertFalse(thread.is_alive())
        self.assertTrue(set(errors) <= {"MANAGED_ACCEPTANCE_CANCELLED"})
        if F.JOB in broker.jobs:
            self.done(broker)
            broker.threads[F.JOB].join(2)
            self.assertTrue(broker.events[F.JOB].is_set())
        self.assertTrue(self.cancel(broker)["acceptanceCleanup"]["confirmed"])
        self.assertIsNone(broker.pending_rpc)

    def test_restart_rejects_non_boolean_cancel_marker(self):
        broker = self.broker()
        parent = copy.deepcopy(self.parent(broker))
        parent["acceptanceCancelled"] = 1
        broker.save(parent)
        broker.close()
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_JOURNAL_INVALID"):
            self.broker()


class AcceptanceRouteCancellationTest(unittest.TestCase):
    setUp = T.T4BrokerTest.setUp
    tearDown = T.T4BrokerTest.tearDown
    broker = T.RouteContinuationTest.broker
    request = T.RouteContinuationTest.request
    execute = T.RouteContinuationTest.execute
    done = T.T4BrokerTest.done
    discover = T.RouteContinuationTest.discover
    parent = T.AcceptanceParentTest.parent
    cancel = AcceptanceCancellationTest.cancel
    child = AcceptanceCancellationTest.child

    def test_bound_routes_are_cleared_but_another_parent_cannot_clear_them(self):
        broker = self.broker()
        self.parent(broker)
        self.behavior = self.discover
        self.child(broker)
        self.assertEqual("COMPLETED", self.done(broker)["status"])
        broker.threads[F.JOB].join(2)
        before = copy.deepcopy(broker.trusted_routes)
        self.assertIsNotNone(before)
        self.cancel(broker, jobId="00000000-0000-4000-8000-000000000001")
        self.assertEqual(before, broker.trusted_routes)
        self.assertIsNotNone(broker.pending_rpc)
        self.assertTrue(self.cancel(broker)["acceptanceCleanup"]["confirmed"])
        self.assertIsNone(broker.trusted_routes)
        self.assertIsNone(broker.staged_routes)
        self.assertIsNone(broker.pending_rpc)

    def test_routes_discovered_before_cancel_cannot_be_published_by_a_late_return(self):
        broker = self.broker()
        self.parent(broker)
        self.behavior = self.discover
        entered, release = threading.Event(), threading.Event()
        def late(*args, **kwargs):
            result = self.execute(*args, **kwargs)
            entered.set()
            self.assertTrue(release.wait(5))
            return result
        with patch.object(B.G, "run", side_effect=late):
            self.child(broker)
            try:
                self.assertTrue(entered.wait(2))
                self.assertFalse(self.cancel(broker)["acceptanceCleanup"]["confirmed"])
            finally:
                release.set()
            self.assertEqual("CANCELLED", self.done(broker)["status"])
        broker.threads[F.JOB].join(2)
        self.assertTrue(self.cancel(broker)["acceptanceCleanup"]["confirmed"])
        self.assertIsNone(broker.trusted_routes)
        self.assertIsNone(broker.staged_routes)
        self.assertIsNone(broker.pending_rpc)


if __name__ == "__main__":
    unittest.main()
