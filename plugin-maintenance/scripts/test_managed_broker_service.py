"""Lifecycle checks against the real admission lock; no Docker, source or model calls."""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import managed_broker_service as O
import managed_source_broker as B

INSTANCE = "d0f4909a-5709-4e57-9a54-4ded3c19424a"
JOB = "b19d47a2-47de-4783-b8a6-233c4abf271a"
IMAGE = "sha256:" + "b" * 64


class LifecycleTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.broker = B.Broker(Path(self.directory.name) / "journal", INSTANCE, IMAGE,
                               lambda: self.fail("No Engine call expected"))
        self.control = O.Controller(self.broker, "f" * 64)

    def tearDown(self):
        self.broker.close()
        self.directory.cleanup()

    def call(self, operation="stop", **changes):
        value = {"schemaVersion": 1, "operation": operation, "configSha256": "f" * 64}
        if operation == "stop":
            value["serviceId"] = self.control.service_id
        return self.control.handle({**value, **changes})

    def test_idle_stop_closes_admission_without_creating_a_job(self):
        self.assertEqual("STOPPING", self.call()["state"])
        self.assertTrue(self.control.stopping.is_set())
        self.assertTrue(self.broker.closed)
        source = b"DO_NOT_EXECUTE = True\n"
        value = {"schemaVersion": 1, "operation": "start", "jobId": JOB, "instanceId": INSTANCE,
                 "imageId": IMAGE, "bundleSha256": self.broker.bundle_sha,
                 "executionPolicySha256": "d" * 64, "sourceBase64": base64.b64encode(source).decode(),
                 "sourceSha256": B.S.digest(source)}
        with self.assertRaisesRegex(B.S.Failure, "MANAGED_RUNNER_RECOVERY_REQUIRED"):
            self.broker.handle(value)
        self.assertEqual([], list(self.broker.journal.iterdir()))

    def test_busy_source_or_http_or_session_refuses_without_cancelling(self):
        for attribute, value in (("active", JOB), ("http_active", JOB),
                                 ("sessions", {JOB: object()})):
            with self.subTest(attribute=attribute):
                previous = getattr(self.broker, attribute)
                setattr(self.broker, attribute, value)
                try:
                    with self.assertRaisesRegex(O.Failure, "BROKER_SERVICE_BUSY"):
                        self.call()
                    self.assertFalse(self.broker.closed)
                    self.assertFalse(self.control.stopping.is_set())
                finally:
                    setattr(self.broker, attribute, previous)

    def test_running_journal_blocks_even_if_active_pointer_is_missing(self):
        for row in ({"status": "RUNNING"}, {"status": "COMPLETED",
                                           "httpAcceptance": {"status": "RUNNING"}}):
            self.broker.jobs[JOB] = row
            with self.assertRaisesRegex(O.Failure, "BROKER_SERVICE_BUSY"):
                self.call()
        self.broker.jobs.clear()

    def test_pending_result_is_not_discarded_by_operator_stop(self):
        pending = (JOB, b"PRIVATE_RESULT")
        self.broker.pending_rpc = pending
        with self.assertRaisesRegex(O.Failure, "BROKER_SERVICE_RESULT_PENDING"):
            self.call()
        self.assertIs(pending, self.broker.pending_rpc)
        self.assertFalse(self.broker.closed)

    def test_stale_owner_and_config_cannot_stop_current_process(self):
        for change, code in (({"serviceId": INSTANCE}, "BROKER_SERVICE_OWNER_CHANGED"),
                             ({"configSha256": "a" * 64}, "BROKER_SERVICE_REQUEST_INVALID"),
                             ({"schemaVersion": True}, "BROKER_SERVICE_REQUEST_INVALID"),
                             ({"command": "sh"}, "BROKER_SERVICE_REQUEST_INVALID")):
            with self.assertRaisesRegex(O.Failure, code):
                self.call(**change)
            self.assertFalse(self.broker.closed)

    def test_admission_lock_serializes_stop_with_concurrent_work(self):
        entered = threading.Event()
        result = []

        def stop():
            entered.set()
            try:
                self.call()
            except O.Failure as error:
                result.append(str(error))

        with self.broker.lock:
            thread = threading.Thread(target=stop)
            thread.start()
            self.assertTrue(entered.wait(1))
            self.broker.active = JOB
        thread.join(2)
        self.assertFalse(thread.is_alive())
        self.assertEqual(["BROKER_SERVICE_BUSY"], result)
        self.assertFalse(self.broker.closed)
        self.broker.active = None

    def test_status_and_blocked_idle_stop_preserve_journal_bytes(self):
        body = b'{"status":"CLEANUP_UNCONFIRMED"}'
        path = self.broker.journal / (JOB + ".json")
        path.write_bytes(body)
        self.broker.jobs[JOB] = {"status": "CLEANUP_UNCONFIRMED"}
        self.broker.blocked = True
        result = self.call("status")
        self.assertEqual("RUNNING", result["state"])
        self.assertTrue(result["identity"]["recoveryRequired"])
        self.call()
        self.assertEqual(body, path.read_bytes())
        self.assertTrue(self.broker.blocked)

    def test_only_the_socket_created_by_this_service_can_be_unlinked(self):
        path = Path(self.directory.name) / "executor.sock"
        path.write_bytes(b"DO_NOT_DELETE")
        info = path.stat()
        with self.assertRaisesRegex(O.Failure, "BROKER_SERVICE_SOCKET_CHANGED"):
            O.unlink_owned_socket(path, (info.st_dev, info.st_ino))
        self.assertEqual(b"DO_NOT_DELETE", path.read_bytes())

    def test_start_uses_fixed_python_entry_not_config_commands(self):
        config = {"root": self.directory.name}
        observed = {"pid": 123}
        with patch.object(O.subprocess, "Popen") as spawn, patch.object(O, "request", return_value=observed):
            spawn.return_value.pid = 123
            spawn.return_value.poll.return_value = None
            self.assertEqual(observed, O.start(Path("/operator.json"), config, "f" * 64))
        args, kwargs = spawn.call_args
        self.assertEqual(["serve", "--config", str(Path("/operator.json"))], args[0][-3:])
        self.assertFalse(kwargs.get("shell", False))
        self.assertTrue(kwargs["start_new_session"])
        self.assertNotIn("FN_NAS_PASSWORD", kwargs["env"])

    def test_large_aggregate_policy_keeps_hash_and_size_checks(self):
        root = Path(self.directory.name)
        runtime = root / "runtime"
        manifest = runtime / "server/src/main/resources/worker/managed-runtime.json"
        manifest.parent.mkdir(parents=True)
        manifest.write_bytes(b'{"files":{}}')
        policy = root / "egress-policy.json"
        policy.write_bytes(json.dumps({"routes": [
            {"origin": "https://site" + str(index) + ".example.com",
             "requestScope": "origin", "pinnedIpv4": ["8.8.8.8"] * 16}
            for index in range(64)]}).encode())
        self.assertGreater(policy.stat().st_size, 8192)
        config = {"schemaVersion": 1, "root": str(root), "runtimeRoot": str(runtime),
                  "instanceId": INSTANCE, "imageId": IMAGE, "bundleSha256": O.sha(manifest.read_bytes()),
                  "egressPolicyFile": str(policy), "egressPolicySha256": O.sha(policy.read_bytes()),
                  "httpImageId": None}
        path = root / "broker-service.json"
        path.write_text(json.dumps(config))
        system = SimpleNamespace(name="posix", getuid=lambda: 0, fsencode=os.fsencode)
        with patch.object(O, "os", system), patch.object(
                O, "owned_path", side_effect=lambda value, **unused: Path(value)), \
                patch.object(O.stat, "S_IMODE", return_value=0o711):
            self.assertEqual(config, O.configuration(path)[1])
            policy.write_bytes(policy.read_bytes() + b" ")
            with self.assertRaisesRegex(O.Failure, "^BROKER_SERVICE_POLICY_CHANGED$"):
                O.configuration(path)
            policy.write_bytes(b" " * 262145)
            config["egressPolicySha256"] = O.sha(policy.read_bytes())
            path.write_text(json.dumps(config))
            with self.assertRaisesRegex(O.Failure, "^BROKER_SERVICE_POLICY_CHANGED$"):
                O.configuration(path)


class CrashRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        (self.root / "journal").mkdir()
        self.journal = self.root / "journal" / (JOB + ".json")
        self.journal.write_bytes(b'{"status":"RUNNING","private":"preserve"}')
        self.config = {"root": str(self.root), "instanceId": INSTANCE}
        self.identities = []
        for index, name in enumerate(("executor.sock", "operator.sock")):
            (self.root / name).write_bytes(b"socket placeholder")
            self.identities.append({"name": name, "device": 1, "inode": index + 10, "ctimeNs": 100})
        self.owner = {"schemaVersion": 1, "serviceId": JOB, "configSha256": "f" * 64,
                      "instanceId": INSTANCE, "state": "RUNNING", "sockets": self.identities}
        self.save(self.root, self.owner)
        self.paths = patch.object(O, "owned_path", side_effect=lambda value, **_: Path(value))
        self.modes = patch.object(O.stat, "S_IMODE", return_value=0o700)
        self.locks = patch.object(O, "lock_file")
        self.saving = patch.object(O, "save_owner", side_effect=self.save)
        self.sockets = patch.object(O, "socket_identity",
                                    side_effect=lambda path: next(
                                        dict(item) for item in self.identities if item["name"] == path.name))
        self.paths.start()
        self.modes.start()
        self.lock = self.locks.start()
        self.saving.start()
        self.identity = self.sockets.start()

    @staticmethod
    def save(root, value):
        (root / "service-owner.json").write_bytes(json.dumps(value).encode())

    def tearDown(self):
        self.sockets.stop()
        self.saving.stop()
        self.locks.stop()
        self.paths.stop()
        self.modes.stop()
        self.directory.cleanup()

    def call(self, service_id=JOB):
        return O.recover(self.config, "f" * 64, service_id)

    def test_offline_release_preserves_running_journal_and_never_replays(self):
        before = self.journal.read_bytes()
        result = self.call()
        self.assertTrue(result["socketsAbsent"])
        self.assertFalse(result["tasksReplayed"])
        self.assertFalse(result["taskCleanupPerformed"])
        self.assertFalse(result["journalModified"])
        self.assertEqual(before, self.journal.read_bytes())
        self.assertFalse((self.root / "executor.sock").exists())
        self.assertFalse((self.root / "operator.sock").exists())
        self.assertEqual("RECOVERED", json.loads((self.root / "service-owner.json").read_bytes())["state"])
        self.assertEqual(2, self.lock.call_count)
        self.assertEqual(result, self.call())

    def test_live_lock_or_wrong_owner_refuses_without_changes(self):
        original = (self.root / "service-owner.json").read_bytes()
        self.lock.side_effect = O.Failure("BROKER_SERVICE_ALREADY_RUNNING")
        with self.assertRaisesRegex(O.Failure, "BROKER_SERVICE_ALREADY_RUNNING"):
            self.call()
        self.lock.side_effect = None
        for value in (None, INSTANCE):
            with self.assertRaisesRegex(O.Failure, "BROKER_SERVICE_OWNER_"):
                self.call(value)
        self.assertEqual(original, (self.root / "service-owner.json").read_bytes())
        self.assertTrue((self.root / "executor.sock").exists())

    def test_changed_second_endpoint_does_not_unlink_the_first(self):
        original = (self.root / "service-owner.json").read_bytes()
        baseline = self.identity.side_effect
        self.identity.side_effect = lambda path: {
            **baseline(path), "ctimeNs": 101 if path.name == "operator.sock" else 100}
        with self.assertRaisesRegex(O.Failure, "BROKER_SERVICE_SOCKET_CHANGED"):
            self.call()
        self.assertTrue((self.root / "executor.sock").exists())
        self.assertTrue((self.root / "operator.sock").exists())
        self.assertEqual(original, (self.root / "service-owner.json").read_bytes())

    def test_missing_endpoint_without_prior_intent_is_not_accepted(self):
        (self.root / "executor.sock").unlink()
        with self.assertRaisesRegex(O.Failure, "BROKER_SERVICE_SOCKET_CHANGED"):
            self.call()
        self.assertTrue((self.root / "operator.sock").exists())

    def test_interruption_between_unlinks_resumes_from_durable_intent(self):
        unlink = Path.unlink

        def interrupt(path, *args, **kwargs):
            if path.name == "operator.sock":
                raise OSError("simulated interruption")
            return unlink(path, *args, **kwargs)

        with patch.object(Path, "unlink", new=interrupt):
            with self.assertRaises(OSError):
                self.call()
        self.assertFalse((self.root / "executor.sock").exists())
        self.assertEqual("RECOVERING", json.loads((self.root / "service-owner.json").read_bytes())["state"])
        self.assertTrue(self.call()["socketsAbsent"])


if __name__ == "__main__":
    unittest.main()
