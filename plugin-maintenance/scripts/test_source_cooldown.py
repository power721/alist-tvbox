"""Trusted Retry-After timing; fake HTTP, no public requests or real waiting."""
import base64
import json
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import source_egress as E
import test_source_egress as F


class Clock:
    def __init__(self):
        self.current = 100.0
        self.waits = []

    def monotonic(self):
        return self.current

    def time(self):
        return 1794398400.0

    def sleep(self, delay):
        self.waits.append(delay)
        self.current += delay


class CooldownTest(unittest.TestCase):
    def limited(self, value="12"):
        connection = F.FakeConnection()
        connection.response.status = 429
        connection.response.read = Mock(side_effect=AssertionError("must not read a limited body"))
        connection.response.getheader = lambda key, default=None: value if key == "Retry-After" else default
        return connection

    def test_delay_date_past_and_invalid_headers_have_no_raw_projection(self):
        import datetime
        from email.utils import format_datetime
        now = 1794398400.25
        date = format_datetime(datetime.datetime.fromtimestamp(now + 10, datetime.timezone.utc), usegmt=True)
        self.assertEqual({"kind": "delay", "seconds": 120}, E.retry_after(" 120 ", now))
        self.assertEqual({"kind": "date", "seconds": 10}, E.retry_after(date, now))
        self.assertEqual(0, E.retry_after("Fri, 31 Dec 1999 23:59:59 GMT", now)["seconds"])
        for value in ("-1", "0.5", "PRIVATE", "\r\nPRIVATE", "1" * 129, [], "Wed, 11 Nov 2026 00:00:00 +0800"):
            with self.subTest(value=value):
                result = E.retry_after(value, now)
                self.assertEqual({"kind": "invalid", "seconds": None}, result)
                self.assertNotIn("PRIVATE", json.dumps(result))

    def test_429_returns_timing_without_reading_or_retrying_then_waits_in_full(self):
        clock = Clock()
        with patch.object(E, "time", clock):
            broker = E.Broker(F.policy())
            broker.bind_execution(130, lambda: False)
            limited = self.limited()
            with patch.object(E.http.client, "HTTPSConnection", return_value=limited) as connected:
                with self.assertRaises(E.Denied) as error:
                    broker.fetch(F.request())
            self.assertEqual({"kind": "delay", "seconds": 12}, error.exception.retry_after)
            self.assertEqual(112, broker.next_request)
            self.assertEqual(1, connected.call_count)
            limited.response.read.assert_not_called()
            self.assertEqual((1, 0, []), (broker.count, broker.total, broker.events))
            following = F.FakeConnection()
            with patch.object(E.http.client, "HTTPSConnection", return_value=following):
                broker.fetch(F.request())
            self.assertGreaterEqual(clock.current, 112)
            self.assertAlmostEqual(12, sum(clock.waits), places=6)
            self.assertFalse(broker.rate_limits[0]["automaticRetry"])

    def test_wait_exceeding_budget_is_not_shortened_or_spent_as_a_request(self):
        clock = Clock()
        with patch.object(E, "time", clock):
            broker = E.Broker(F.policy())
            broker.bind_execution(105, lambda: False)
            with patch.object(E.http.client, "HTTPSConnection", return_value=self.limited("9" * 128)):
                with self.assertRaises(E.Denied):
                    broker.fetch(F.request())
            self.assertEqual(int("9" * 128), broker.rate_limits[0]["retryAfter"]["seconds"])
            with patch.object(E.http.client, "HTTPSConnection") as connected:
                with self.assertRaisesRegex(E.Denied, "EGRESS_COOLDOWN_BUDGET"):
                    broker.fetch(F.request())
                connected.assert_not_called()
            self.assertEqual(1, broker.count)
            self.assertEqual([], clock.waits)

    def test_cancellation_interrupts_cooldown_without_connecting(self):
        clock = Clock()
        cancelled = threading.Event()
        sleep = clock.sleep
        def interrupt(delay):
            sleep(delay)
            cancelled.set()
        clock.sleep = interrupt
        with patch.object(E, "time", clock):
            broker = E.Broker(F.policy())
            broker.next_request = 120
            broker.bind_execution(130, cancelled.is_set)
            with patch.object(E.http.client, "HTTPSConnection") as connected:
                with self.assertRaisesRegex(E.Denied, "EGRESS_CANCELLED"):
                    broker.fetch(F.request())
                connected.assert_not_called()
            self.assertLessEqual(sum(clock.waits), 0.05)
            self.assertEqual(0, broker.count)

    def test_routes_and_later_actions_keep_longer_cooldown(self):
        configured = F.RoutesTest().policy()
        configured["routes"][1]["exactUrls"] = ["https://media.example.com/one"]
        clock = Clock()
        with patch.object(E, "time", clock):
            broker = E.Routes(configured)
            broker.bind_execution(130, lambda: False)
            with patch.object(E.http.client, "HTTPSConnection", return_value=self.limited("10")):
                with self.assertRaises(E.Denied):
                    broker.fetch(F.RoutesTest().request())
            self.assertEqual(110, broker.next_request)
            with patch.object(E.http.client, "HTTPSConnection", return_value=F.FakeConnection()):
                broker.fetch(F.RoutesTest().request("https://media.example.com/one"))
            self.assertGreaterEqual(clock.current, 110)
            self.assertEqual(1, len(broker.rate_limits))
            self.assertEqual(2, broker.count)

    def test_concurrent_callers_cannot_bypass_the_first_cooldown(self):
        clock = Clock()
        with patch.object(E, "time", clock):
            broker = E.Broker(F.policy())
            broker.bind_execution(130, lambda: False)
            results = []
            starts = []
            def connect(*args, **kwargs):
                starts.append(clock.current)
                return self.limited("5") if len(starts) == 1 else F.FakeConnection()
            def fetch():
                try:
                    results.append(broker.fetch(F.request())["status"])
                except E.Denied as error:
                    results.append(error.code)
            with patch.object(E.http.client, "HTTPSConnection", side_effect=connect):
                threads = [threading.Thread(target=fetch) for _ in range(2)]
                for thread in threads:
                    thread.start()
                for thread in threads:
                    thread.join(2)
                    self.assertFalse(thread.is_alive())
            self.assertEqual(["EGRESS_RATE_LIMITED", 200], results)
            self.assertGreaterEqual(starts[1] - starts[0], 5)

    def test_protocol_returns_safe_timing_to_the_source(self):
        from test_source_egress import ExchangeTest
        broker = SimpleNamespace(events=[], rate_limits=[],
                                 fetch=Mock(side_effect=E.Denied(
                                     "EGRESS_RATE_LIMITED", retry_after={"kind": "delay", "seconds": 12})))
        fetch = {"kind": "fetch", "id": 1, "request": F.request()}
        program = ("print(" + repr(json.dumps(fetch)) + ",flush=True); "
                   "r=json.loads(sys.stdin.buffer.readline()); "
                   "assert r['retryAfter']=={'kind':'delay','seconds':12}; "
                   "print('{\"kind\":\"result\",\"summary\":{\"status\":\"init_returned\"}}',flush=True)")
        helper = ExchangeTest()
        result = helper.call(helper.child(program), broker)
        self.assertEqual(["EGRESS_RATE_LIMITED"], result["deniedCodes"])


if __name__ == "__main__":
    unittest.main()
