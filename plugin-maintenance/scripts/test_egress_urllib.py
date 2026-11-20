"""Trusted urllib adapter tests only; no submitted source or public network."""
from __future__ import annotations

import base64
from contextlib import contextmanager
import http.cookiejar
import importlib.util
import io
import json
from pathlib import Path
import ssl
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request


class UrllibBridgeTest(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).resolve().parents[1] / "managed-source/egress_bridge.py"
        spec = importlib.util.spec_from_file_location("trusted_urllib_bridge_test", path)
        self.bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.bridge)
        self.frames = []
        self.replies = []

    @contextmanager
    def installed(self, responses):
        def write(fd, raw):
            self.assertEqual(1, fd)
            frame = json.loads(raw)
            self.frames.append(frame)
            response = responses[len(self.frames) - 1]
            reply = {"id": frame["id"], **response}
            self.replies.append(json.dumps(reply).encode() + b"\n")
            return len(raw)

        stream = type("Input", (), {"readline": lambda _, limit: self.replies.pop(0)})()
        with patch.object(urllib.request.HTTPHandler, "http_open"), \
                patch.object(urllib.request.HTTPSHandler, "https_open"), \
                patch.object(self.bridge.requests.adapters.HTTPAdapter, "send"), \
                patch.object(urllib.request, "getproxies", return_value={}), \
                patch.object(urllib.request, "_opener", None), \
                patch.object(self.bridge.os, "write", side_effect=write):
            self.bridge.install(stream)
            yield

    @staticmethod
    def response(body=b"example", *, status=200, cookies=()):
        return {"ok": True, "response": {
            "status": status, "headers": {"Content-Type": "text/plain",
                                          "Content-Length": str(len(body))},
            "body": base64.b64encode(body).decode(), "setCookies": list(cookies)}}

    def test_urlopen_and_custom_opener_share_bounded_rpc_not_native_network(self):
        with self.installed([self.response(), self.response(b'{"ok":true}')]):
            with urllib.request.urlopen("https://reader.example.com/list", timeout=2) as response:
                self.assertEqual(200, response.getcode())
                self.assertEqual("https://reader.example.com/list", response.geturl())
                self.assertEqual("text/plain", response.info().get_content_type())
                self.assertEqual(b"exam", response.read(4))
                self.assertEqual(b"ple", response.read())
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            request = urllib.request.Request("https://reader.example.com/detail", data=b'{"id":1}',
                                             headers={"Content-Type": "application/json"})
            with opener.open(request, timeout=2) as response:
                self.assertEqual(b'{"ok":true}', response.read())
        self.assertEqual(["GET", "POST"], [row["request"]["method"] for row in self.frames])
        self.assertEqual(b'{"id":1}', base64.b64decode(self.frames[1]["request"]["body"]))
        self.assertFalse(any(key.lower() == "host" for row in self.frames
                             for key in row["request"]["headers"]))

    def test_source_cookiejar_preserves_rotation_deletion_and_host_isolation(self):
        jar = http.cookiejar.CookieJar()
        responses = [self.response(cookies=["visit=first; Path=/; Secure; HttpOnly"]),
                     self.response(cookies=["visit=second; Path=/; Secure"]),
                     self.response(cookies=["visit=; Path=/; Max-Age=0; Secure"]),
                     self.response(), self.response()]
        with self.installed(responses):
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                                 urllib.request.HTTPCookieProcessor(jar))
            for host in ("reader", "reader", "reader", "reader", "other"):
                with opener.open("https://" + host + ".example.com/list") as response:
                    self.assertEqual(b"example", response.read())
        cookies = [{key.lower(): value for key, value in row["request"]["headers"].items()}.get("cookie")
                   for row in self.frames]
        self.assertEqual([None, "visit=first", "visit=second", None, None], cookies)
        self.assertEqual([], list(jar))

    def test_http_error_is_native_but_policy_denial_cannot_trigger_source_fallback(self):
        responses = [self.response(b"missing", status=404),
                     {"ok": False, "code": "EGRESS_URL_DENIED", "retryAfter": {"seconds": 2}},
                     {"ok": False, "code": "EGRESS_HTTP_FAILED"}]
        with self.installed(responses):
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with self.assertRaises(urllib.error.HTTPError) as failure:
                opener.open("https://reader.example.com/missing")
            self.assertEqual(404, failure.exception.code)
            self.assertEqual(b"missing", failure.exception.read())
            failure.exception.close()
            with self.assertRaises(self.bridge.PolicyDenied) as denial:
                opener.open("https://reader.example.com/denied")
            self.assertNotIsInstance(denial.exception, Exception)
            self.assertEqual({"seconds": 2}, denial.exception.retry_after)
            with self.assertRaises(urllib.error.URLError):
                opener.open("https://reader.example.com/failed")

    def test_insecure_tls_proxy_and_unbounded_payload_are_rejected_before_rpc(self):
        with self.installed([]):
            insecure = urllib.request.build_opener(
                urllib.request.ProxyHandler({}),
                urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))
            with self.assertRaises(urllib.error.URLError):
                insecure.open("https://reader.example.com/list")
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            for payload in (io.BytesIO(b"data"), b"x" * 65537):
                with self.subTest(kind=type(payload).__name__), self.assertRaises(urllib.error.URLError):
                    opener.open(urllib.request.Request("https://reader.example.com/list", data=payload))
            proxied = urllib.request.Request("http://reader.example.com/list")
            proxied.set_proxy("proxy.example.com:8888", "http")
            with self.assertRaises(urllib.error.URLError):
                opener.open(proxied)
            forged = urllib.request.Request("https://reader.example.com/list", headers={"Host": "other.example.com"})
            with self.assertRaises(urllib.error.URLError):
                opener.open(forged)
        self.assertEqual([], self.frames)

    def test_nonpublic_dns_is_catchable_but_a_private_literal_target_is_still_fatal(self):
        with self.installed([{"ok": False, "code": "EGRESS_NONPUBLIC_DNS"},
                             self.response(),
                             {"ok": False, "code": "EGRESS_NONPUBLIC_TARGET_DENIED"}]):
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with self.assertRaises(urllib.error.URLError):
                opener.open("https://unavailable.example.com/")
            with opener.open("https://reader.example.com/list") as response:
                self.assertEqual(b"example", response.read())
            with self.assertRaises(self.bridge.PolicyDenied):
                opener.open("http://127.0.0.1/")

    def test_head_and_range_are_forwarded_unchanged_for_the_host_policy_to_decide(self):
        with self.installed([self.response(b"", status=200),
                             {"ok": False, "code": "EGRESS_RANGE_DENIED"}]):
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(urllib.request.Request("https://reader.example.com/media", method="HEAD")) as reply:
                self.assertEqual(b"", reply.read())
            with self.assertRaises(self.bridge.PolicyDenied):
                opener.open(urllib.request.Request("https://reader.example.com/media", method="HEAD",
                                                   headers={"Range": "bytes=0-32767"}))
        self.assertEqual(["HEAD", "HEAD"], [row["request"]["method"] for row in self.frames])
        headers = {key.lower(): value for key, value in self.frames[1]["request"]["headers"].items()}
        self.assertEqual("bytes=0-32767", headers["range"])

    def test_requests_verify_false_uses_the_same_host_verified_rpc(self):
        with self.installed([self.response(), self.response()]):
            with self.bridge.requests.Session() as session:
                session.trust_env = False
                for verify in (True, False):
                    with session.get("https://reader.example.com/list", verify=verify) as reply:
                        self.assertEqual(b"example", reply.content)
        self.assertEqual(self.frames[0]["request"], self.frames[1]["request"])
        self.assertNotIn("verify", self.frames[1]["request"])

    def test_requests_cannot_forward_client_certificates_or_proxies_to_the_host(self):
        request = self.bridge.requests.Request("GET", "https://reader.example.com/list").prepare()
        with self.installed([]):
            for options in ({"proxies": {"https": "http://proxy.example.com"}},
                            {"cert": "client.pem"}):
                with self.subTest(options=tuple(options)), self.assertRaises(self.bridge.requests.RequestException):
                    self.bridge.send(None, request, **options)
        self.assertEqual([], self.frames)


if __name__ == "__main__":
    unittest.main()
