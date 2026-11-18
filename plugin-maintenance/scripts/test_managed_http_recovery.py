"""Offline HTTP recovery against inspect-shaped Engine doubles; no Docker or source execution."""
import copy
import unittest
from unittest.mock import patch

import managed_http_acceptance as H
import managed_source_broker as B
import test_managed_source_broker as F
import test_managed_t4_broker as T

IMAGE = "sha256:" + "e" * 64
IDENT = "f" * 64


class Engine:
    command = ["fake-engine"]

    def __init__(self, endpoint):
        self.calls, self.lost_remove = [], False
        self.container = {
            "Id": IDENT, "Name": "/at-maintenance-http-" + F.JOB, "Image": IMAGE,
            "State": {"Status": "running"},
            "Config": {"User": "65532:65532", "Entrypoint": ["java"],
                       "Cmd": ["-cp", H.CLASSPATH, H.MAIN], "Env": ["PATH=/usr/bin"],
                       "Labels": {B.S.MANAGED + ".kind": "at-http-acceptance-v1",
                                  B.S.MANAGED + ".job": F.JOB}},
            "Mounts": [{"Type": "bind", "Source": str(endpoint),
                        "Destination": "/run/at-maintenance/executor.sock", "RW": False}],
            "HostConfig": {
                "NetworkMode": "none", "ReadonlyRootfs": True, "Privileged": False, "CapDrop": ["ALL"],
                "SecurityOpt": ["no-new-privileges:true"], "Memory": H.POLICY["memoryBytes"],
                "MemorySwap": H.POLICY["memoryBytes"], "NanoCpus": 1_000_000_000, "PidsLimit": 128,
                "RestartPolicy": {"Name": "no"}, "LogConfig": {"Type": "none"},
                "Tmpfs": {"/tmp": "rw,noexec,nosuid,nodev,size=67108864,mode=1777"}}}

    def call(self, args, **kwargs):
        self.calls.append(args)
        if args[:2] == ["container", "ls"]:
            return (IDENT if self.container is not None else "").encode()
        if args[:2] == ["container", "inspect"]:
            return B.S.encode([self.container])
        if args == ["container", "rm", "--force", IDENT]:
            self.container = None
            if self.lost_remove:
                raise B.S.Failure("ENGINE_TIMEOUT")
            return IDENT.encode()
        raise AssertionError(args)

    @property
    def removals(self):
        return [call for call in self.calls if call[:2] == ["container", "rm"]]


class RecoveryTest(unittest.TestCase):
    setUp = F.BrokerTest.setUp
    tearDown = F.BrokerTest.tearDown

    def broker(self, recovery=True):
        broker = B.Broker(self.journal, F.INSTANCE, F.IMAGE, lambda: self.engine,
                          recovery_only=recovery, egress_policy=T.POLICY, http_image_id=IMAGE)
        self.brokers.append(broker)
        return broker

    def fixture(self, bound=False):
        self.engine = Engine(self.journal.parent / "executor.sock")
        broker = self.broker()
        request = T.T4BrokerTest.request(self, broker, job=F.JOB)
        row = broker.row(request, "COMPLETED")
        row.update(result={"loader": "preserved"}, cleanup={"confirmed": True, "containerAbsent": True})
        row["httpAcceptance"] = {
            "imageId": IMAGE, "status": "CLEANUP_UNCONFIRMED",
            "cleanup": {"confirmed": False, "containerAbsent": False}}
        if bound:
            row["httpAcceptance"]["containerId"] = IDENT
        broker.save(row, exclusive=True)
        broker.jobs[F.JOB], broker.blocked = row, True
        return broker, self.sha()

    def sha(self):
        return B.S.digest((self.journal / (F.JOB + ".json")).read_bytes())

    def test_owned_recovery_preserves_parent_and_reopen_does_not_replay(self):
        broker, digest = self.fixture()
        done = broker.recover_http_owned(F.JOB, digest, IDENT)
        self.assertEqual({"loader": "preserved"}, done["result"])
        self.assertEqual("COMPLETED", done["status"])
        self.assertEqual("MANAGED_HTTP_OPERATOR_RECOVERED", done["httpAcceptance"]["code"])
        self.assertTrue(done["httpAcceptance"]["cleanup"]["confirmed"])
        self.assertTrue(done["acceptanceCancelled"])
        self.assertFalse(done["httpAcceptance"]["recovery"]["sourceReplayed"])
        self.assertFalse(done["httpAcceptance"]["recovery"]["journalReset"])
        self.assertEqual(1, len(self.engine.removals))
        self.assertFalse(broker.blocked)
        broker.close()
        restarted = self.broker(False)
        self.assertFalse(restarted.blocked)
        self.assertEqual(done, restarted.jobs[F.JOB])
        self.assertEqual(1, len(self.engine.removals))

    def test_wrong_sha_id_identity_isolation_command_environment_or_state_never_removes(self):
        broker, digest = self.fixture()
        before = self.sha()
        mutations = [
            ("Image", "sha256:" + "0" * 64), ("Name", "/foreign"),
            ("State.Status", "removing"), ("Config.Labels." + B.S.MANAGED + ".job", F.INSTANCE),
            ("HostConfig.NetworkMode", "bridge"), ("HostConfig.Memory", 1),
            ("Config.Cmd", ["sh"]), ("Config.Env", ["JAVA_TOOL_OPTIONS=-javaagent:private"]),
            ("Mounts", self.engine.container["Mounts"] + [{"Type": "volume", "Source": "foreign"}])]
        original = copy.deepcopy(self.engine.container)
        for key, value in mutations:
            self.engine.container = copy.deepcopy(original)
            if key.startswith("Config.Labels."):
                self.engine.container["Config"]["Labels"][B.S.MANAGED + ".job"] = value
            else:
                target = self.engine.container
                parts = key.split(".")
                for part in parts[:-1]:
                    target = target[part]
                target[parts[-1]] = value
            with self.subTest(key=key), self.assertRaises(B.S.Failure):
                broker.recover_http_owned(F.JOB, digest, IDENT)
        self.engine.container = original
        for expected, ident in (("0" * 64, IDENT), (digest, "0" * 64)):
            with self.assertRaises(B.S.Failure):
                broker.recover_http_owned(F.JOB, expected, ident)
        self.assertEqual([], self.engine.removals)
        self.assertEqual(before, self.sha())
        self.assertTrue(broker.blocked)

    def test_live_broker_and_unknown_absence_cannot_be_recovered(self):
        broker, digest = self.fixture()
        broker.recovery_only = False
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_RECOVERY_OFFLINE_REQUIRED"):
            broker.recover_http_owned(F.JOB, digest, IDENT)
        broker.recovery_only = True
        self.engine.container = None
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_RECOVERY_CONTAINER_REQUIRED"):
            broker.recover_http_owned(F.JOB, digest, IDENT)
        self.assertTrue(broker.blocked)
        self.assertEqual([], self.engine.removals)

    def test_previously_bound_absence_can_be_confirmed_without_removal(self):
        broker, digest = self.fixture(bound=True)
        self.engine.container = None
        done = broker.recover_http_owned(F.JOB, digest, IDENT)
        self.assertEqual("absent", done["httpAcceptance"]["recovery"]["observedState"])
        self.assertFalse(broker.blocked)
        self.assertEqual([], self.engine.removals)

    def test_lost_remove_reply_is_reconciled_without_retry(self):
        broker, digest = self.fixture()
        self.engine.lost_remove = True
        done = broker.recover_http_owned(F.JOB, digest, IDENT)
        self.assertTrue(done["httpAcceptance"]["cleanup"]["confirmed"])
        self.assertEqual(1, len(self.engine.removals))

    def test_intent_must_commit_before_remove_and_final_failure_can_be_reconciled(self):
        broker, digest = self.fixture()
        with patch.object(broker, "save", side_effect=OSError("private")):
            with self.assertRaises(OSError):
                broker.recover_http_owned(F.JOB, digest, IDENT)
        self.assertEqual([], self.engine.removals)
        self.assertEqual(digest, self.sha())
        save = broker.save
        def fail_final(row, **kwargs):
            if row["httpAcceptance"]["status"] == "FAILED":
                raise OSError("private")
            return save(row, **kwargs)
        with patch.object(broker, "save", side_effect=fail_final):
            with self.assertRaises(OSError):
                broker.recover_http_owned(F.JOB, digest, IDENT)
        self.assertTrue(broker.blocked)
        self.assertEqual(IDENT, broker.jobs[F.JOB]["httpAcceptance"]["recoveryIntent"]["containerId"])
        broker.close()
        restarted = self.broker()
        done = restarted.recover_http_owned(F.JOB, self.sha(), IDENT)
        self.assertTrue(done["httpAcceptance"]["cleanup"]["confirmed"])
        self.assertEqual(1, len(self.engine.removals))
        self.assertFalse(restarted.blocked)

    def test_other_unclean_job_keeps_global_block(self):
        broker, digest = self.fixture()
        other = copy.deepcopy(broker.jobs[F.JOB])
        other.update(jobId=F.INSTANCE, status="CLEANUP_UNCONFIRMED")
        other.pop("httpAcceptance")
        broker.save(other, exclusive=True)
        broker.jobs[F.INSTANCE] = other
        broker.recover_http_owned(F.JOB, digest, IDENT)
        self.assertTrue(broker.blocked)


if __name__ == "__main__":
    unittest.main()
