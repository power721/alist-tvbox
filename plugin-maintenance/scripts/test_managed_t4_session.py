"""Persistent protocol tests use a trusted child, never import submitted source."""
import base64
import copy
import hashlib
import json
import sys
import threading
import time
import unittest
import zlib
from unittest.mock import patch

import managed_egress_protocol as G
import managed_source_broker as B
from managed_loader import Invalid, SessionChannel, digest
import test_managed_source_broker as F
import test_managed_t4_broker as T
import test_managed_t4_rpc as R

try:
    import m3u8 as _m3u8
except ImportError:
    _m3u8 = None


def manifest_result(body):
    return {"status": 200, "mime": "application/vnd.apple.mpegurl", "headers": {},
            "bytes": len(body), "body": base64.b64encode(body).decode(),
            "sha256": hashlib.sha256(body).hexdigest()}


def child():
    return R.child().replace("assert count<=3", "assert count<=26").replace(
        "result=[] if False else {'transportFixtureOnly':True,'params':call['params']}",
        "result={'sameInstanceCounter':count,'params':call['params']}")


class SessionTest(unittest.TestCase):
    setUp = F.BrokerTest.setUp
    tearDown = F.BrokerTest.tearDown

    done = T.T4BrokerTest.done
    parent = T.AcceptanceParentTest.parent

    def test_session_deadline_follows_trusted_egress_budget(self):
        for requests, interval, expected in ((24, 1, 60), (64, 1, 124), (64, 5, 180)):
            timeout = G.session_timeout({"maxRequests": requests, "minIntervalSeconds": interval})
            self.assertEqual(expected, timeout)
            with patch("managed_loader.time.monotonic", return_value=10):
                channel = SessionChannel(R.CALLS[0], lambda: False, lambda *args: None,
                                         timeout=timeout - 5)
            self.assertEqual(10 + expected - 5, channel.deadline)
            channel.close()
        for timeout in (True, 0, 176, float("inf"), float("nan")):
            with self.subTest(timeout=timeout), self.assertRaises(Invalid):
                SessionChannel(R.CALLS[0], lambda: False, lambda *args: None, timeout=timeout)

    def request(self, broker, operation="start", job=F.JOB, **extra):
        value = T.T4BrokerTest.request(self, broker, operation, job=job)
        if operation == "start_session":
            value = T.T4BrokerTest.request(self, broker, job=job)
            value["operation"] = operation
        return {**value, **extra}

    def setup_broker(self):
        broker = B.Broker(self.journal, F.INSTANCE, F.IMAGE, lambda: self.engine, egress_policy=T.POLICY)
        self.brokers.append(broker)
        def run(engine, request, source, hashes, mediator, *, cancelled, t4_session):
            engine.call(["container", "create"])
            return {**G.exchange([sys.executable, "-I", "-c", child()], R.F.request(), R.F.SOURCE,
                                 R.HASHES, mediator, cancelled=cancelled, t4_session=t4_session, timeout=4),
                    "stdoutBytes": 0, "stderrBytes": 0}
        self.patch = patch.object(B.G, "run", side_effect=run)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        # Match the trusted peer's fixed source/request without ever executing those source bytes.
        original = self.request
        def request(*args, **kwargs):
            value = original(*args, **kwargs)
            if value["operation"] in {"start", "start_session"}:
                import base64
                value.update(sourceBase64=base64.b64encode(R.F.SOURCE).decode(),
                             sourceSha256=B.S.digest(R.F.SOURCE))
            return value
        self.request = request
        return broker

    def take(self, broker, ordinal, call):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            result = broker.handle(self.request(broker, "session_take", ordinal=ordinal, t4CallSha256=digest(call)))
            if result["ready"]:
                self.assertEqual(result["resultSha256"], B.S.digest(result["resultJson"].encode()))
                result["result"] = json.loads(result["resultJson"])
                return result
            time.sleep(0.01)
        self.fail("session reply missing")

    def test_same_worker_multiple_actions_one_shot_results_and_explicit_cleanup(self):
        broker = self.setup_broker()
        first = R.CALLS[3]
        broker.handle(self.request(broker, "start_session"))
        one = self.take(broker, 1, first)
        self.assertTrue(one["cleanupPending"])
        self.assertEqual(2, one["result"]["sameInstanceCounter"])
        second = R.CALLS[2]
        broker.handle(self.request(broker, "session_call", ordinal=2, t4CallJson=json.dumps(second)))
        two = self.take(broker, 2, second)
        self.assertEqual(3, two["result"]["sameInstanceCounter"])
        with self.assertRaisesRegex(B.S.Failure, "T4_SESSION_RESULT_UNAVAILABLE"):
            self.take(broker, 2, second)
        self.assertEqual("RUNNING", broker.jobs[F.JOB]["status"])
        self.assertNotIn("sameInstanceCounter", (self.journal / (F.JOB + ".json")).read_text())
        broker.handle(self.request(broker, "session_close"))
        broker.threads[F.JOB].join(3)
        self.assertFalse(broker.threads[F.JOB].is_alive())
        row = broker.jobs[F.JOB]
        self.assertEqual("COMPLETED", row["status"])
        self.assertTrue(row["cleanup"]["confirmed"])
        self.assertEqual(1, row["result"]["t4SessionValidation"]["initCount"])
        self.assertEqual(2, len(row["result"]["t4SessionValidation"]["actions"]))
        self.assertEqual(1, len([c for c in self.engine.calls if c[:2] == ["container", "create"]]))

    def test_cancel_invalidates_body_and_fences_further_actions(self):
        broker = self.setup_broker()
        broker.handle(self.request(broker, "start_session"))
        self.take(broker, 1, R.CALLS[3])
        broker.handle(self.request(broker, "cancel"))
        with self.assertRaises(B.S.Failure):
            broker.handle(self.request(broker, "session_call", ordinal=2, t4CallJson=json.dumps(R.CALLS[2])))
        broker.threads[F.JOB].join(3)
        self.assertEqual("CANCELLED", broker.jobs[F.JOB]["status"])
        self.assertTrue(broker.jobs[F.JOB]["cleanup"]["confirmed"])

    def test_formal_parent_session_binds_each_delivery_and_normal_close_without_cancelling_parent(self):
        broker = self.setup_broker()
        parent = self.parent(broker)
        binding = {"parentJobId": F.INSTANCE, "parentProfileSha256": parent["loaderProfileSha256"]}
        row = broker.handle(self.request(broker, "start_session", parentJobId=F.INSTANCE))
        for key, value in binding.items():
            self.assertEqual(value, row[key])
        for ordinal, call in enumerate((R.CALLS[3], R.CALLS[2]), 1):
            if ordinal > 1:
                broker.handle(self.request(broker, "session_call", ordinal=ordinal, t4CallJson=json.dumps(call)))
            reply = self.take(broker, ordinal, call)
            for key, value in binding.items():
                self.assertEqual(value, reply[key])
        broker.handle(self.request(broker, "session_close"))
        row = self.done(broker)
        self.assertEqual("COMPLETED", row["status"])
        self.assertEqual(row["sessionActions"], row["result"]["t4SessionValidation"]["actions"])
        self.assertTrue(row["result"]["t4SessionValidation"]["destroyReturned"])
        self.assertFalse(broker.events[F.JOB].is_set())
        self.assertNotIn("acceptanceCancelled", broker.jobs[F.INSTANCE])
        broker.threads[F.JOB].join(3)
        broker.close()
        reopened = B.Broker(self.journal, F.INSTANCE, F.IMAGE, lambda: self.engine, egress_policy=T.POLICY)
        self.brokers.append(reopened)
        self.assertEqual(row, reopened.handle(self.request(reopened, "status")))
        with self.assertRaises(B.S.Failure):
            reopened.handle(self.request(reopened, "session_take", ordinal=2, t4CallSha256=digest(R.CALLS[2])))

    def test_parent_cancellation_discards_unclaimed_session_body_and_blocks_normal_close(self):
        broker = self.setup_broker()
        self.parent(broker)
        broker.handle(self.request(broker, "start_session", parentJobId=F.INSTANCE))
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            with broker.lock:
                channel = broker.sessions.get(F.JOB)
                if channel is not None and channel.reply is not None:
                    break
            time.sleep(0.01)
        self.assertTrue(broker.jobs[F.JOB].get("sessionActions"))
        channel = broker.sessions[F.JOB]
        self.assertIsNotNone(channel.reply)
        broker.handle(self.request(broker, "cancel_acceptance", job=F.INSTANCE))
        self.assertIsNone(channel.reply)
        for operation, extra in (
                ("session_take", {"ordinal": 1, "t4CallSha256": digest(R.CALLS[3])}),
                ("session_call", {"ordinal": 2, "t4CallJson": json.dumps(R.CALLS[2])}),
                ("session_close", {})):
            with self.assertRaises(B.S.Failure):
                broker.handle(self.request(broker, operation, **extra))
        row = self.done(broker)
        self.assertEqual("CANCELLED", row["status"])
        self.assertNotIn("result", row)
        self.assertTrue(row["cleanup"]["confirmed"])

    def test_session_parent_must_be_valid_and_cannot_be_supplied_on_followup_controls(self):
        broker = self.setup_broker()
        for parent in (F.INSTANCE, F.JOB, "bad", None, True):
            with self.subTest(parent=parent), self.assertRaisesRegex(
                    B.S.Failure, "MANAGED_ACCEPTANCE_PARENT_INVALID"):
                broker.handle(self.request(broker, "start_session", parentJobId=parent))
        self.assertEqual([], self.engine.calls)
        self.assertFalse((self.journal / (F.JOB + ".json")).exists())
        for operation, extra in (
                ("session_take", {"ordinal": 1, "t4CallSha256": digest(R.CALLS[3])}),
                ("session_call", {"ordinal": 2, "t4CallJson": json.dumps(R.CALLS[2])}),
                ("session_close", {})):
            with self.assertRaisesRegex(B.S.Failure, "MANAGED_REQUEST_INVALID"):
                broker.handle(self.request(broker, operation, parentJobId=F.INSTANCE, **extra))

    def test_normal_close_requires_the_last_result_to_have_been_claimed(self):
        channel = SessionChannel(R.CALLS[0], lambda: False, lambda *args: None)
        with self.assertRaisesRegex(Invalid, "T4_SESSION_ACTION_UNAVAILABLE"):
            channel.close(require_idle=True)
        channel.next()
        channel.finish({"private": True})
        with self.assertRaisesRegex(Invalid, "T4_SESSION_ACTION_UNAVAILABLE"):
            channel.close(require_idle=True)
        self.assertFalse(channel.closing)
        channel.receive(1, digest(R.CALLS[0]))
        channel.close(require_idle=True)
        self.assertIsNone(channel.next())

    def test_context_sequence_concurrent_action_and_expired_claim_fail_closed(self):
        channel = SessionChannel(R.CALLS[0], lambda: False, lambda *args: None)
        for ordinal, call in ((1, R.CALLS[0]), (2, R.CALLS[0]),
                              (2, {**R.CALLS[0], "playType": "t4-direct"})):
            with self.assertRaises(Invalid):
                channel.submit(ordinal, call)
        channel.finish({"ready": True})
        with self.assertRaises(Invalid):
            channel.receive(True, digest(R.CALLS[0]) + "0")
        self.assertEqual({"ready": True}, channel.receive(1, digest(R.CALLS[0])))
        channel.deadline = 0
        with self.assertRaises(Invalid):
            channel.submit(2, R.CALLS[0])

    def test_two_simultaneous_claims_deliver_only_one_body(self):
        channel = SessionChannel(R.CALLS[0], lambda: False, lambda *args: None)
        channel.finish({"private": "one-shot"})
        gate, received, rejected = threading.Barrier(3), [], []
        def take():
            gate.wait()
            try:
                received.append(channel.receive(1, digest(R.CALLS[0])))
            except Invalid as error:
                rejected.append(str(error))
        threads = [threading.Thread(target=take) for _ in range(2)]
        for thread in threads:
            thread.start()
        gate.wait()
        for thread in threads:
            thread.join(2)
            self.assertFalse(thread.is_alive())
        self.assertEqual([{"private": "one-shot"}], received)
        self.assertEqual(["T4_SESSION_RESULT_UNAVAILABLE"], rejected)

    def test_ttl_and_close_wake_idle_owner_and_discard_unclaimed_body(self):
        channel = SessionChannel(R.CALLS[0], lambda: False, lambda *args: None)
        self.assertEqual(R.CALLS[0], channel.next())
        channel.finish({"private": True})
        channel.deadline = 0
        with self.assertRaisesRegex(Invalid, "T4_SESSION_RESULT_UNAVAILABLE"):
            channel.receive(1, digest(R.CALLS[0]))
        with self.assertRaisesRegex(Invalid, "T4_SESSION_EXPIRED"):
            channel.next()
        channel.close()
        self.assertIsNone(channel.reply)
        self.assertIsNone(channel.next())

    def test_action_budget_and_proxy_capability_are_exact_and_context_bound(self):
        channel = SessionChannel(R.CALLS[0], lambda: False, lambda *args: None)
        for ordinal in range(1, 25):
            self.assertEqual(R.CALLS[0], channel.next())
            channel.finish({"ordinal": ordinal})
            channel.receive(ordinal, digest(R.CALLS[0]))
            if ordinal < 24:
                channel.submit(ordinal + 1, R.CALLS[0])
        with self.assertRaises(Invalid):
            channel.submit(25, R.CALLS[0])
        channel = SessionChannel(R.CALLS[0], lambda: False, lambda *args: None)
        channel.next()
        channel.finish({})
        channel.receive(1, digest(R.CALLS[0]))
        proxy = {"method": "proxy", "playType": "t4", "params": {
            "target": "/local?type=video", "range": "bytes=0-32767", "head": False}}
        with self.assertRaisesRegex(Invalid, "T4_SESSION_PROXY_DENIED"):
            channel.submit(2, proxy)
        channel.proxy_targets.add(proxy["params"]["target"])
        with self.assertRaisesRegex(Invalid, "T4_SESSION_CONTEXT_CHANGED"):
            channel.submit(2, {**proxy, "playType": "t4-direct"})
        channel.submit(2, proxy)
        self.assertEqual(proxy, channel.next())

    def test_manifest_children_are_registered_before_delivery_in_the_same_trusted_pipe_session(self):
        if _m3u8 is None:
            self.skipTest("m3u8 is an explicit optional host dependency")
        body = b"#EXTM3U\n" + b"#EXTINF:5,\nhttp://managed.invalid/local?type=segment&id=first\n" * 1700
        self.assertGreater(len(body), 98304)
        manifest = manifest_result(body)
        segment_body = (b"G" + bytes(187)) * 3
        segment = {"status": 200, "mime": "video/mp2t", "headers": {}, "bytes": len(segment_body),
                   "body": base64.b64encode(segment_body).decode(),
                   "sha256": hashlib.sha256(segment_body).hexdigest()}
        program = child().replace(
            "result={'sameInstanceCounter':count,'params':call['params']}",
            "result=({'parse':0,'url':'http://managed.invalid/local?type=m3u8&id=fixture'}"
            " if method=='play' else __import__('json').loads(__import__('zlib').decompress("
            "__import__('base64').b64decode(" + repr(base64.b64encode(
                zlib.compress(json.dumps(manifest).encode())).decode()) + ")))"
            + " if call['params'].get('target')=='/local?type=m3u8&id=fixture' else "
            + repr(segment) + " if method=='proxy' else {'sameInstanceCounter':count})")
        with patch.object(sys.modules[__name__], "child", return_value=program):
            broker = self.setup_broker()
            broker.handle(self.request(broker, "start_session"))
            self.take(broker, 1, R.CALLS[3])
            broker.handle(self.request(broker, "session_call", ordinal=2, t4CallJson=json.dumps(R.CALLS[4])))
            self.take(broker, 2, R.CALLS[4])
            target = "/local?type=segment&id=first"
            self.assertNotIn(target, broker.sessions[F.JOB].proxy_targets)
            call = {"method": "proxy", "playType": "t4", "params": {
                "target": "/local?type=m3u8&id=fixture", "range": "", "head": False}}
            broker.handle(self.request(broker, "session_call", ordinal=3, t4CallJson=json.dumps(call)))
            delivered = self.take(broker, 3, call)
            self.assertEqual(manifest, delivered["result"])
            self.assertEqual([target], delivered["proxyManifestTargets"])
            self.assertIn(target, broker.sessions[F.JOB].proxy_targets)
            call["params"] = {"target": target, "range": "bytes=0-32767", "head": False}
            broker.handle(self.request(broker, "session_call", ordinal=4, t4CallJson=json.dumps(call)))
            delivered = self.take(broker, 4, call)
            self.assertEqual(segment, delivered["result"])
            self.assertNotIn("proxyManifestTargets", delivered)
            broker.handle(self.request(broker, "session_close"))
            row = self.done(broker)
            self.assertEqual("COMPLETED", row["status"])
            self.assertEqual(["search", "play", "proxy", "proxy"],
                             [action["method"] for action in row["sessionActions"]])
            self.assertNotIn(target, (self.journal / (F.JOB + ".json")).read_text())
            self.assertNotIn("proxyManifestTargets", (self.journal / (F.JOB + ".json")).read_text())
            self.assertTrue(row["cleanup"]["confirmed"])

    def test_journal_commit_failure_blocks_delivery_and_fences_new_jobs(self):
        broker = self.setup_broker()
        save = broker.save
        def failed_commit(row, **kwargs):
            if row.get("sessionActions"):
                raise OSError("fixture-commit-failure")
            return save(row, **kwargs)
        with patch.object(broker, "save", side_effect=failed_commit):
            broker.handle(self.request(broker, "start_session"))
            broker.threads[F.JOB].join(3)
        self.assertFalse(broker.threads[F.JOB].is_alive())
        self.assertTrue(broker.blocked)
        self.assertEqual({}, broker.sessions)
        self.assertNotIn(b"sameInstanceCounter", (self.journal / (F.JOB + ".json")).read_bytes())
        with self.assertRaises(B.S.Failure):
            broker.handle(self.request(broker, "session_take", ordinal=1, t4CallSha256=digest(R.CALLS[3])))

    def test_thread_start_failure_and_restart_do_not_replay_source(self):
        broker = self.setup_broker()
        with patch.object(B.threading.Thread, "start", side_effect=RuntimeError("fixture-start-failure")):
            row = broker.handle(self.request(broker, "start_session"))
        self.assertEqual("FAILED", row["status"])
        self.assertTrue(row["cleanup"]["confirmed"])
        self.assertEqual({}, broker.sessions)
        self.assertIsNone(broker.active)
        self.assertEqual([], self.engine.calls)
        broker.close()
        reopened = B.Broker(self.journal, F.INSTANCE, F.IMAGE, lambda: self.engine, egress_policy=T.POLICY)
        self.brokers.append(reopened)
        with self.assertRaisesRegex(B.S.Failure, "JOB_ALREADY_EXISTS"):
            reopened.handle(self.request(reopened, "start_session"))
        self.assertEqual([], self.engine.calls)


class ManifestCapabilityTest(unittest.TestCase):
    def setUp(self):
        if _m3u8 is None:
            self.skipTest("m3u8 is an explicit optional host dependency")
        self.channel = SessionChannel(R.CALLS[0], lambda: False, lambda *args: None)
        self.channel.proxy_targets.add("/local?type=m3u8")
        self.params = {"target": "/local?type=m3u8", "range": "", "head": False}

    def register(self, body):
        self.channel.register_proxy_manifest(self.params, manifest_result(body))

    def test_only_selected_exact_local_children_are_granted_and_close_revokes_them(self):
        body = "#EXTM3U\n" + "".join(
            "#EXTINF:5,\nhttp://managed.invalid/local?type=segment&id=%d\n" % index for index in range(8))
        self.register(body.encode())
        self.assertEqual({"/local?type=m3u8"} | {
            "/local?type=segment&id=%d" % index for index in (0, 1, 2, 4, 7)},
            self.channel.proxy_targets)
        self.channel.close()
        self.assertEqual(set(), self.channel.proxy_targets)
        with self.assertRaises(Invalid):
            self.register(body.encode())
        self.assertEqual(set(), self.channel.proxy_targets)

    def test_incomplete_forged_or_external_manifests_never_grant_partial_capabilities(self):
        good = b"#EXTM3U\n#EXTINF:5,\nhttp://managed.invalid/local?type=segment&id=a\n"
        bad = [
            b"<html>not a playlist</html>",
            good + b"#EXTINF:5,\nhttps://public.example.com/segment.ts\n",
            good + b'#EXT-X-KEY:METHOD=AES-128,URI="https://public.example.com/key"\n',
            good.replace(b"#EXTINF:5,", b"#EXTINF:5,\n#EXT-X-BYTERANGE:3@0"),
            good.replace(b"?type=segment", b"?id=b&type=segment"),
            good.replace(b"id=a", b"id=a#fragment"),
            b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nhttp://managed.invalid/local?type=m3u8\n",
        ]
        for body in bad:
            with self.subTest(body=body[:80]), self.assertRaises(Invalid):
                self.register(body)
            self.assertEqual({"/local?type=m3u8"}, self.channel.proxy_targets)
        for field, value in (("sha256", "0" * 64), ("bytes", True), ("bytes", 1), ("body", "!invalid!")):
            result = {**manifest_result(good), field: value}
            with self.subTest(field=field, value=value), self.assertRaises(Invalid):
                self.channel.register_proxy_manifest(self.params, result)
        self.assertEqual({"/local?type=m3u8"}, self.channel.proxy_targets)

    def test_head_and_error_do_not_grant_and_range_or_capability_overflow_fail_closed(self):
        body = b"#EXTM3U\n#EXTINF:5,\nhttp://managed.invalid/local?type=segment&id=a\n"
        self.channel.register_proxy_manifest({**self.params, "head": True}, manifest_result(body))
        self.channel.register_proxy_manifest(self.params, {**manifest_result(body), "status": 404})
        self.assertEqual({"/local?type=m3u8"}, self.channel.proxy_targets)
        result = manifest_result(body)
        result["headers"] = {"content-range": "bytes 0-99/100"}
        with self.assertRaises(Invalid):
            self.channel.register_proxy_manifest({**self.params, "range": "bytes=0-32767"}, result)
        self.channel.proxy_targets.update("/local?id=%d" % index for index in range(15))
        before = self.channel.proxy_targets.copy()
        with self.assertRaises(Invalid):
            self.register(body)
        self.assertEqual(before, self.channel.proxy_targets)

    def test_range_request_ignored_by_complete_manifest_still_grants_only_same_session_children(self):
        body = b"#EXTM3U\n#EXTINF:5,\nhttp://managed.invalid/local?type=segment&id=a\n"
        selected = self.channel.register_proxy_manifest(
            {**self.params, "range": "bytes=0-32767"}, manifest_result(body))
        self.assertEqual(["/local?type=segment&id=a"], selected)
        large = b"#EXTM3U\n" + b"#comment\n" * 60000
        with self.assertRaises(Invalid):
            self.register(large)


if __name__ == "__main__":
    unittest.main()
