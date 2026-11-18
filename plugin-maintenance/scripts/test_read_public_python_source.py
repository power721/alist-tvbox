import base64
import io
import json
import ssl
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import read_public_python_source as reader


class PublicSourceReadTest(unittest.TestCase):
    def response(self, status=200, body=b"raise RuntimeError('MUST_NOT_EXECUTE')\r\nclass Spider: pass\r\n",
                 mime="text/plain", location=None):
        headers = {"Content-Type": mime}
        if location is not None:
            headers["Location"] = location
        return {"status": status, "headers": headers, "body": base64.b64encode(body).decode()}

    def read(self, replies, url="https://github.com/owner/repo/raw/main/source.py", resolver=None):
        self.requests = []
        self.policies = []
        test = self

        class Broker:
            def __init__(self, policy):
                reader.egress.policy_from(policy)
                test.policies.append(policy)

            def bind_execution(self, deadline, cancelled):
                test.assertFalse(cancelled())

            def fetch(self, request):
                test.requests.append(request)
                return replies.pop(0)

        return reader.read_source(url, resolver=resolver or (lambda host, port: ["1.1.1.1"]),
                                  broker_factory=Broker)

    def test_public_redirect_retains_exact_source_bytes_without_credentials(self):
        final = self.response()
        body = self.read([self.response(302, body=b"", location=
                                      "https://raw.githubusercontent.com/owner/repo/main/source.py"), final])
        self.assertEqual(body, base64.b64decode(final["body"]))
        self.assertEqual(len(self.requests), 2)
        for request, policy in zip(self.requests, self.policies):
            self.assertEqual(request["method"], "GET")
            self.assertEqual(set(request["headers"]), {"Accept"})
            self.assertEqual(request["body"], "")
            self.assertEqual(policy["exactUrls"], [request["url"]])
            self.assertEqual(policy["pinnedIpv4"], ["1.1.1.1"])

    def test_invalid_urls_are_rejected_before_dns_or_fetch(self):
        for url in ("http://example.com/a.py", "file:///tmp/a.py", "https://user:secret@example.com/a.py",
                    "https://example.com/a.py#fragment", "https://localhost/a.py",
                    "https://example.com/a\n.py", "https://example.com/a\\b.py"):
            with self.subTest(url=url), self.assertRaises(reader.egress.Denied):
                self.read([], url=url, resolver=lambda *args, **kwargs: self.fail("DNS must not run"))
            self.assertEqual(self.requests, [])

    def test_public_ipv6_source_uses_separate_pins_and_preserves_exact_https_authority(self):
        url = "https://[2606:4700:4700::1111]:9443/source.py"
        body = self.read([self.response()], url=url,
                         resolver=lambda *args, **kwargs: ["2606:4700:4700::1111"])
        self.assertIn(b"class Spider", body)
        self.assertEqual([], self.policies[0]["pinnedIpv4"])
        self.assertEqual(["2606:4700:4700::1111"], self.policies[0]["pinnedIpv6"])
        self.assertEqual([url], self.policies[0]["exactUrls"])

    def test_redirect_to_private_destination_is_rejected_before_connect(self):
        def resolve(host, port):
            return ["127.0.0.1"] if host == "private.example.com" else ["1.1.1.1"]

        with self.assertRaises(reader.egress.Denied):
            self.read([self.response(302, body=b"", location="https://private.example.com/source.py")],
                      resolver=resolve)
        self.assertEqual(len(self.requests), 1)

    def test_redirect_downgrade_and_loop_are_not_followed(self):
        for location in ("http://example.com/source.py", "https://github.com/owner/repo/raw/main/source.py"):
            with self.subTest(location=location), self.assertRaises(reader.egress.Denied):
                self.read([self.response(302, body=b"", location=location)])
            self.assertEqual(len(self.requests), 1)

    def test_redirect_count_is_bounded(self):
        with self.assertRaisesRegex(reader.egress.Denied, "AT_PUBLIC_SOURCE_REDIRECT_LIMIT"):
            self.read([self.response(302, body=b"", location=f"https://example.com/{index}.py")
                       for index in range(4)])
        self.assertEqual(len(self.requests), 4)

    def test_http_errors_html_empty_and_oversized_inputs_are_not_sources(self):
        for response, code in ((self.response(404), "AT_SOURCE_HTTP_ERROR"),
                               (self.response(mime="text/html"), "AT_SOURCE_RESPONSE_TYPE"),
                               (self.response(body=b""), "AT_SOURCE_EMPTY"),
                               (self.response(body=b"x" * (reader.MAX_BYTES + 1)), "AT_RESPONSE_TOO_LARGE")):
            with self.subTest(code=code), self.assertRaisesRegex(reader.egress.Denied, code):
                self.read([response])

    def test_worker_reports_transport_stage_without_secret_error_messages(self):
        for error, code in ((ssl.SSLError("SECRET"), "AT_PUBLIC_SOURCE_TLS_FAILED"),
                            (TimeoutError("SECRET"), "AT_PUBLIC_SOURCE_TIMEOUT"),
                            (OSError("SECRET"), "AT_PUBLIC_SOURCE_NETWORK_FAILED")):
            with self.subTest(code=code), patch.object(reader, "read_source", side_effect=error), \
                    patch.object(reader.sys, "stdin", SimpleNamespace(buffer=io.BytesIO(
                        b'{"url":"https://example.com/source.py","maxBytes":1024}'))), \
                    patch.object(reader.sys, "stdout", io.StringIO()) as output:
                reader.main()
                self.assertEqual(json.loads(output.getvalue()), {"status": "rejected", "code": code})
                self.assertNotIn("SECRET", output.getvalue())


if __name__ == "__main__":
    unittest.main()
