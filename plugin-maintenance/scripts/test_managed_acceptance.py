"""Bounded orchestration checks; NAS execution remains the acceptance evidence."""
import copy
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import managed_egress_protocol as G


CLEAN = {"confirmed": True, "containerAbsent": True}


class Mediator:
    policy = {"maxRequests": 6, "minIntervalSeconds": 1}
    loader_profile = {"schemaVersion": 1, "requiredNodes": ["S06", "S07", "S08", "R09"]}

    def __init__(self):
        self.events = []

    def fetch(self, value):
        self.events.append({"status": 200})
        return {"status": 200}


def receipt():
    rows = [
        {"id": "S06", "status": "pass", "reason": "PRE_EXEC_SOURCE_SHA_AND_INIT_RETURNED"},
        {"id": "S07", "status": "blocked", "protocolChecksPassed": True},
        {"id": "S08", "status": "not_tested"},
        {"id": "R09", "status": "blocked", "normalCleanupConfirmed": True},
    ]
    return {
        "requiredNodes": ["S06", "S07", "S08", "R09"], "requirementsFrozen": True,
        **{key: "a" * 64 for key in ("inputSourceSha256", "loadedTextSha256", "runtimeSha256",
                                     "profileSha256", "hotHomeCategoriesSha256")},
        "nodes": rows, "observedNodes": copy.deepcopy(rows), "successful": False,
    }


class AcceptanceTest(unittest.TestCase):
    def execute(self, defect=None, policy=None):
        self.engine = SimpleNamespace(
            create_id=None, document=lambda args: [{"Id": self.engine.create_id}],
            call=lambda args: None)
        self.calls = []
        self.timeouts = []

        def run(engine, request, source, hashes, broker, *, cancelled, exchange_timeout=None):
            self.calls.append((request, source, getattr(broker, "loader_mode", "full")))
            self.timeouts.append(exchange_timeout)
            engine.create_id = ("%x" % len(self.calls)) * 64
            if len(self.calls) <= 2:
                value = receipt()
                if defect == "business":
                    value["nodes"][0].update(status="blocked")
                if len(self.calls) == 2 and defect in {
                        "inputSourceSha256", "runtimeSha256", "profileSha256", "hotHomeCategoriesSha256"}:
                    value[defect] = "b" * 64
                return {"loaderValidation": value, "containerId": engine.create_id,
                        "imageId": "sha256:" + "c" * 64, "cleanup": dict(CLEAN)}
            if defect == "cleanup":
                raise G.S.Failure("CONTAINER_CLEANUP_UNCONFIRMED")
            if defect != "no_http":
                broker.fetch({})
            if defect == "no_fault":
                return {}
            code = ("MANAGED_CANCELLED" if cancelled() else "ENGINE_TIMEOUT"
                    if exchange_timeout is not None else "EGRESS_PROTOCOL_INVALID")
            raise G.S.Failure(code)

        with patch.object(G, "run", side_effect=run), \
                patch("source_egress.mediator", side_effect=lambda p: Mediator()), \
                patch.object(G.time, "sleep"), patch.object(G.S, "container_id",
                    side_effect=lambda e, j: e.create_id), patch.object(G, "owned", return_value=True), \
                patch.object(G.M, "confirm_cleanup", return_value=dict(CLEAN)):
            broker = Mediator()
            if policy is not None:
                broker.policy = copy.deepcopy(policy)
            return G.acceptance(self.engine, {"jobId": "fixed-job"}, b"source", {}, broker)

    def test_full_scan_uses_trusted_budget_without_extending_cold_or_fault_deadlines(self):
        for requests, interval, expected in ((6, 1, 60), (24, 1, 60),
                                              (64, 1, 124), (64, 5, 180)):
            with self.subTest(requests=requests, interval=interval):
                value = self.execute(policy={"maxRequests": requests, "minIntervalSeconds": interval})
                self.assertTrue(value["loaderValidation"]["successful"])
                self.assertEqual([expected, None, None, 5, None], self.timeouts)

    def test_same_job_sequential_workers_need_cold_match_and_all_three_observed_faults(self):
        value = self.execute()
        self.assertEqual(5, len(self.calls))
        self.assertEqual(["full", "cold", "full", "full", "full"], [call[2] for call in self.calls])
        self.assertTrue(all(call[:2] == ({"jobId": "fixed-job"}, b"source") for call in self.calls))
        loader = value["loaderValidation"]
        self.assertTrue(loader["successful"])
        nodes = {row["id"]: row for row in loader["nodes"]}
        self.assertEqual(["cancel", "timeout", "exit"],
                         [row["scenario"] for row in nodes["R09"]["scenarios"]])
        self.assertTrue(all(row["cleanup"] == CLEAN for row in nodes["R09"]["scenarios"]))
        self.assertEqual(2, len(set(nodes["S08"]["workerContainerIds"])))

    def test_cold_identity_drift_cannot_become_a_pass(self):
        for defect in ("inputSourceSha256", "runtimeSha256", "profileSha256", "hotHomeCategoriesSha256"):
            with self.subTest(defect=defect), self.assertRaisesRegex(
                    G.S.Failure, "LOADER_COLD_IDENTITY_MISMATCH"):
                self.execute(defect)
            self.assertEqual(2, len(self.calls))

    def test_missing_fault_http_or_cleanup_cannot_become_success(self):
        for defect in ("no_http", "no_fault", "cleanup"):
            with self.subTest(defect=defect), self.assertRaises(G.S.Failure):
                self.execute(defect)
            self.assertEqual(3, len(self.calls))

    def test_blocked_business_stops_at_the_observed_fault_without_extra_workers(self):
        value = self.execute("business")
        self.assertEqual(1, len(self.calls))
        self.assertFalse(value["loaderValidation"]["successful"])


if __name__ == "__main__":
    unittest.main()
