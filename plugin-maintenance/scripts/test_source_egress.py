"""Public-HTTP broker tests, no LAN scans or production requests."""
from __future__ import annotations

import base64
import copy
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import importlib.util
import io
import json
from pathlib import Path
import socket
import ssl
import sys
import threading
import unittest
from unittest.mock import patch

import managed_egress_protocol as P
import source_egress as E
import test_managed_source_protocol as F

P.S = F.M.S


def policy():
    return {"schemaVersion": 2, "origin": "https://reader.example.com",
            "paths": ["/api/drama/list", "/api/drama/detail"],
            "methods": ["GET", "HEAD", "POST"], "maxRequests": 3, "maxResponseBytes": 512,
            "maxTotalResponseBytes": 1024, "minIntervalSeconds": 1, "queryKeys": [],
            "pinnedIpv4": ["8.8.8.8"]}


def request():
    return {"method": "POST", "url": policy()["origin"] + policy()["paths"][0],
            "headers": {"Content-Type": "application/json", "token": ""},
            "body": base64.b64encode(b'{"page":1}').decode()}


def dns(*args, **kwargs):
    return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("8.8.8.8", 443))]


HASHES = {"egress_entry.py": "1" * 64, "egress_payload.py": "2" * 64, "egress_bridge.py": "3" * 64}


def ready():
    old = F.ready()
    return {key: val for key, val in old.items() if key not in {"entrySha256", "payloadSha256"}} | {"files": HASHES}


class FakeResponse:
    status = 200

    def getheader(self, key, default=None):
        return default

    def getheaders(self):
        return [("Content-Type", "application/json"), ("Set-Cookie", "PRIVATE")]

    def read(self, limit):
        return b'{"data":[]}'


class FakeConnection:
    def __init__(self, *args, **kwargs):
        self.sock = None
        self.response = FakeResponse()

    def connect(self):
        pass

    def request(self, method, target, body, headers):
        self.sent = (method, target, body, headers)

    def getresponse(self):
        return self.response

    def close(self):
        pass


class PolicyTest(unittest.TestCase):
    def test_dns_stage_timings_separate_primary_fallback_and_aaaa(self):
        responses = [b'{"Status":0,"Answer":[{"type":1,"data":"0.0.0.0"}]}',
                     b'{"Status":0,"Answer":[]}',
                     b'{"Status":0,"Answer":[{"type":28,"data":"2606:4700:4700::1111"}]}']
        primary = unittest.mock.MagicMock()
        primary.__enter__.return_value = primary
        primary.read.return_value = responses[0]
        opener = unittest.mock.Mock()
        opener.open.return_value = primary
        fallback = unittest.mock.Mock()
        fallback.getresponse.return_value.status = 200
        fallback.getresponse.return_value.read.side_effect = responses[1:]
        rows = []
        with patch.object(E, "build_opener", return_value=opener), \
                patch.object(E.http.client, "HTTPSConnection", return_value=fallback), \
                patch.object(E.time, "monotonic", side_effect=[1, 1.025, 2, 2.05, 3, 3.075]):
            self.assertEqual(["2606:4700:4700::1111"], E.public_dns_addresses(
                "example.com", observations=rows))
        self.assertEqual(["alidns", "cloudflare", "cloudflare"], [row["resolver"] for row in rows])
        self.assertEqual(["A", "A", "AAAA"], [row["recordType"] for row in rows])
        self.assertEqual(["nonpublic_address", "no_address", "success"], [row["kind"] for row in rows])
        self.assertEqual([25, 50, 75], [row["elapsedMilliseconds"] for row in rows])
        self.assertNotIn("example.com", json.dumps(rows))

    def test_ajax_header_reaches_only_the_approved_endpoint_case_insensitively(self):
        for name in ("X-Requested-With", "x-requested-with", "X-REQUESTED-WITH"):
            with self.subTest(name=name):
                value = {**request(), "headers": {name: "XMLHttpRequest"}}
                connection = FakeConnection()
                with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
                    E.Broker(policy()).fetch(value)
                self.assertEqual("XMLHttpRequest", connection.sent[3][name])
                with self.assertRaisesRegex(E.Denied, "^EGRESS_URL_DENIED$"):
                    E.validate_request({**value, "url": "https://other.example.com/"}, policy())
                with self.assertRaisesRegex(E.Denied, "^EGRESS_NONPUBLIC_TARGET_DENIED$"):
                    E.validate_request({**value, "url": "http://127.0.0.1/"}, policy())

    def test_ajax_header_preserves_value_and_duplicate_checks(self):
        cases = [
            ({"X-Requested-With": "x" * 2049}, "value_size"),
            ({"X-Requested-With": "XMLHttpRequest\r\nInjected: yes"}, "value_control"),
            ({"X-Requested-With": "\u4e2d"}, "value_encoding"),
            ({"X-Requested-With": "XMLHttpRequest", "x-requested-with": "other"}, "duplicate_name"),
        ]
        for headers, reason in cases:
            with self.subTest(reason=reason), self.assertRaises(E.Denied) as caught:
                E.validate_request({**request(), "headers": headers}, policy())
            self.assertEqual("EGRESS_HEADERS_DENIED", caught.exception.code)
            self.assertEqual({"kind": "header", "reason": reason, "headerName": "x-requested-with"},
                             caught.exception.request_diagnostic)

    def test_header_origin_refusal_identifies_origins_without_values_or_queries(self):
        value = request()
        for name in ("Origin", "Referer"):
            with self.subTest(name=name), self.assertRaises(E.Denied) as caught:
                E.validate_request({**value, "headers": {
                    name: "https://frontend.example.com/watch?token=PRIVATE"}}, policy())
            self.assertEqual("EGRESS_HEADER_ORIGIN_DENIED", caught.exception.code)
            detail = caught.exception.request_diagnostic
            self.assertEqual("origin_not_allowed", detail["reason"])
            self.assertEqual(name.lower(), detail["headerName"])
            self.assertEqual(E.digest(value["url"].encode()), detail["requestUrlSha256"])
            self.assertEqual(E.digest(policy()["origin"].encode()), detail["requestOriginSha256"])
            self.assertEqual(E.digest(b"https://frontend.example.com"), detail["headerOriginSha256"])
            for secret in ("frontend.example.com", "reader.example.com", "PRIVATE", "token", "watch"):
                self.assertNotIn(secret, json.dumps(detail))

    def test_header_refusals_identify_the_field_and_reason_without_its_value(self):
        cases = [
            ({"X-App-Unknown": "PRIVATE"}, "header_not_allowed", "x-app-unknown"),
            ({"User-Agent": "PRIVATE", "user-agent": "PRIVATE"}, "duplicate_name", "user-agent"),
            ({"User-Agent": 123}, "value_type", "user-agent"),
            ({"User-Agent": "PRIVATE" * 400}, "value_size", "user-agent"),
            ({"User-Agent": "\u4e2dPRIVATE"}, "value_encoding", "user-agent"),
            ({"User-Agent": "PRIVATE\r\nInjected: yes"}, "value_control", "user-agent"),
        ]
        for headers, reason, name in cases:
            with self.subTest(reason=reason), self.assertRaises(E.Denied) as caught:
                E.validate_request({**request(), "headers": headers}, policy())
            self.assertEqual("EGRESS_HEADERS_DENIED", caught.exception.code)
            self.assertEqual({"kind": "header", "reason": reason, "headerName": name},
                             caught.exception.request_diagnostic)
            self.assertNotIn("PRIVATE", json.dumps(caught.exception.request_diagnostic))
        with self.assertRaises(E.Denied) as caught:
            E.validate_request({**request(), "headers": {"PRIVATE_bad\nname": "PRIVATE"}}, policy())
        self.assertNotIn("headerName", caught.exception.request_diagnostic)

    def test_cookie_state_refusal_identifies_only_the_cookie_header(self):
        broker = E.Broker(policy())
        with self.assertRaises(E.Denied) as caught:
            broker.fetch({**request(), "headers": {"Cookie": "private=PRIVATE"}})
        self.assertEqual({"kind": "header", "reason": "cookie_not_observed", "headerName": "cookie"},
                         {key: value for key, value in caught.exception.request_diagnostic.items()
                          if key != "cookieState"})
        self.assertEqual({"storedCount": 0, "expectedPresent": False, "suppliedParsed": True,
                          "expectedParsed": False, "suppliedCount": 1, "expectedCount": 0,
                          "matchingStoredCount": 0}, caught.exception.request_diagnostic["cookieState"])
        self.assertNotIn("PRIVATE", json.dumps(caught.exception.request_diagnostic))
        self.assertEqual(0, broker.count)

    def test_app_platform_client_and_salt_headers_reach_only_the_approved_endpoint(self):
        value = request()
        value["headers"].update({"platform": "1", "client": "app", "X-Salt": "public-client-salt"})
        connection = FakeConnection()
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            E.Broker(policy()).fetch(value)
        for key in ("platform", "client", "X-Salt"):
            self.assertEqual(value["headers"][key], connection.sent[3][key])
        with self.assertRaisesRegex(E.Denied, "^EGRESS_URL_DENIED$"):
            E.validate_request({**value, "url": "https://other.example.com/"}, policy())

    def test_app_headers_do_not_grant_transport_or_account_credentials(self):
        for field in ("Host", "Proxy-Authorization", "Authorization"):
            with self.subTest(field=field), self.assertRaisesRegex(E.Denied, "^EGRESS_HEADERS_DENIED$"):
                E.validate_request({**request(), "headers": {field: "not-authorized"}}, policy())
        with self.assertRaisesRegex(E.Denied, "^EGRESS_ACCOUNT_TOKEN_DENIED$"):
            E.validate_request({**request(), "headers": {"token": "not-authorized"}}, policy())
        with self.assertRaisesRegex(E.Denied, "^EGRESS_HEADERS_DENIED$"):
            E.validate_request({**request(), "headers": {"platform": "1\r\nInjected: yes"}}, policy())

    def test_source_declared_application_fields_work_without_a_site_specific_allowlist(self):
        source = b'''
config = {"headers": {"app": {"alg": "public", "datas": "public", "Talos-Module-Name": "drama"}}}
headers = {"X-Client-Release": "one"}
self.more_headers.update({"X-Page-Signature": "public"})
headers["X-Route-Name"] = "public"
unrelated = {"X-Unrelated": "not-a-header"}
'''
        broker = E.Broker(policy())
        broker.bind_source(source)
        connection = FakeConnection()
        headers = {"alg": "public", "datas": "source-encrypted-request",
                   "Talos-Module-Name": "drama", "X-Client-Release": "one",
                   "X-Page-Signature": "public", "X-Route-Name": "public"}
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            broker.fetch({**request(), "headers": headers})
        self.assertTrue(headers.items() <= connection.sent[3].items())
        self.assertNotIn("source-encrypted-request", json.dumps(broker.events))
        for target in (broker, E.Broker(policy())):
            with self.subTest(fresh=target is not broker), self.assertRaisesRegex(
                    E.Denied, "^EGRESS_HEADERS_DENIED$"):
                target.fetch({**request(), "headers": {"X-Unrelated": "not-a-header"}})
        with self.assertRaisesRegex(E.Denied, "^EGRESS_URL_DENIED$"):
            broker.fetch({**request(), "headers": headers, "url": "https://other.example.com/"})

    def test_declared_fields_cannot_grant_transport_or_unobserved_credentials(self):
        names = ("Host", "Content-Length", "Transfer-Encoding", "Proxy-Authorization", "Authorization",
                 "Cookie", "X-API-Key", "Access-Token", "X-Forwarded-For", "X-Http-Method-Override")
        broker = E.Broker(policy())
        broker.bind_source(("headers = " + repr({name: "PRIVATE" for name in names})).encode())
        self.assertFalse(set(name.lower() for name in names) & broker.source_headers)
        for name in names:
            with self.subTest(name=name), self.assertRaises(E.Denied):
                broker.fetch({**request(), "headers": {name: "PRIVATE"}})
        for val in ("PRIVATE\r\nInjected: yes", "x" * 2049):
            broker = E.Broker(policy())
            broker.bind_source(b'headers = {"alg": "public"}')
            with self.assertRaisesRegex(E.Denied, "^EGRESS_HEADERS_DENIED$"):
                broker.fetch({**request(), "headers": {"alg": val}})

    def test_observed_anonymous_token_supports_bearer_raw_and_token_header_formats(self):
        configured = {**self.token_policy(), "maxRequests": 4, "maxTotalResponseBytes": 2048}
        broker = E.Broker(configured)
        connection = FakeConnection()
        connection.response.getheader = lambda key, default=None: (
            "application/json" if key == "Content-Type" else default)
        connection.response.read = lambda limit: b'{"data":{"token":"anonymous-issued"}}'
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), patch.object(E.time, "sleep"):
            broker.fetch({"method": "POST", "url": configured["exactUrls"][0], "headers": {}, "body": ""})
            for headers in ({"Authorization": "Bearer anonymous-issued"},
                            {"Authorization": "anonymous-issued"}, {"token": "anonymous-issued"}):
                broker.fetch({"method": "GET", "url": configured["exactUrls"][1], "headers": headers, "body": ""})
                self.assertTrue(headers.items() <= connection.sent[3].items())
        self.assertEqual(["Bearer anonymous-issued"], broker.session_tokens)
        self.assertNotIn("anonymous-issued", json.dumps(broker.events))
        for headers in ({"Authorization": "imported"}, {"token": "imported"}, {"token": "anonymous-issued"}):
            with self.subTest(headers=headers), self.assertRaises(E.Denied):
                E.Broker(configured).fetch({"method": "GET", "url": configured["exactUrls"][1],
                                            "headers": headers, "body": ""})

    def test_unobserved_authorization_keeps_only_request_identity(self):
        broker = E.Broker(self.token_policy())
        url = self.token_policy()["exactUrls"][1]
        with self.assertRaisesRegex(E.Denied, "^EGRESS_SESSION_TOKEN_DENIED$") as caught:
            broker.fetch({"method": "GET", "url": url, "headers": {"Authorization": "PRIVATE"}, "body": ""})
        self.assertEqual({
            "kind": "header", "reason": "session_token_not_observed", "headerName": "authorization",
            "requestUrlSha256": E.digest(url.encode()),
            "requestOriginSha256": E.digest(broker.policy["origin"].encode()),
        }, caught.exception.request_diagnostic)
        self.assertNotIn("PRIVATE", json.dumps(caught.exception.request_diagnostic))
        self.assertEqual(0, broker.count)

    def test_empty_authorization_is_anonymous_but_nonempty_unobserved_values_remain_denied(self):
        configured = self.token_policy()
        broker, connection = E.Broker(configured), FakeConnection()
        value = {"method": "POST", "url": configured["exactUrls"][0], "body": "",
                 "headers": {"Authorization": ""}}
        connection.response.status = 201
        connection.response.read = lambda limit: b'{"data":{"token":"guest-issued"}}'
        connection.response.getheader = lambda key, default=None: (
            "application/json" if key == "Content-Type" else default)
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), patch.object(E.time, "sleep"):
            broker.fetch(value)
            self.assertEqual("", connection.sent[3]["Authorization"])
            self.assertEqual(["Bearer guest-issued"], broker.session_tokens)
            broker.fetch({**value, "headers": {"Authorization": "Bearer guest-issued"}})
        self.assertNotIn("guest-issued", json.dumps(broker.events))
        for headers in ({"Authorization": " "}, {"Authorization": "Bearer "},
                        {"Authorization": "PRIVATE"}, {"Authorization": "", "authorization": ""}):
            with self.subTest(headers=headers), self.assertRaisesRegex(
                    E.Denied, "^EGRESS_SESSION_TOKEN_DENIED$"):
                E.Broker(configured).fetch({**value, "headers": headers})

    def test_public_dns_uses_the_fixed_service_and_does_not_use_local_fake_dns(self):
        response = unittest.mock.MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b'{"Status":0,"Answer":[{"type":1,"data":"8.8.8.8"},{"type":1,"data":"8.8.8.8"}]}'
        opener = unittest.mock.Mock()
        opener.open.return_value = response
        with patch.object(E, "build_opener", return_value=opener), \
                patch.object(E.socket, "getaddrinfo", side_effect=AssertionError("LOCAL_DNS_NOT_USED")):
            self.assertEqual(["8.8.8.8"], E.public_dns_addresses("canonical.example.com", port=9443))
        opener.open.assert_called_once_with(
            "https://dns.alidns.com/resolve?name=canonical.example.com&type=A", timeout=5)
        response.read.assert_called_once_with(16385)

    def test_public_dns_rejects_nonpublic_mixed_empty_invalid_and_oversize_responses(self):
        for body, code, kind, status in (
                (b'{"Status":0,"Answer":[{"type":1,"data":"127.0.0.1"}]}',
                 "EGRESS_NONPUBLIC_DNS", "nonpublic_address", 0),
                (b'{"Status":0,"Answer":[{"type":1,"data":"8.8.8.8"},{"type":1,"data":"198.18.0.1"}]}',
                 "EGRESS_NONPUBLIC_DNS", "nonpublic_address", 0),
                (b'{"Status":0,"Answer":[]}', "EGRESS_DNS_NO_ADDRESS", "no_address", 0),
                (b'{"Status":3}', "EGRESS_DNS_NO_ADDRESS", "no_address", 3),
                (b'{"Status":2}', "EGRESS_DNS_FAILED", "resolver_error", 2),
                (b'{"Status":0,"Answer":[{"type":1,"data":"PRIVATE"}]}',
                 "EGRESS_DNS_FAILED", "invalid_response", 0),
                (b"invalid", "EGRESS_DNS_FAILED", "invalid_response", None),
                (b"[]" * 8193, "EGRESS_DNS_FAILED", "invalid_response", None)):
            with self.subTest(bodyBytes=len(body)):
                response = unittest.mock.MagicMock()
                response.__enter__.return_value = response
                response.read.return_value = body
                opener = unittest.mock.Mock()
                opener.open.return_value = response
                with patch.object(E, "build_opener", return_value=opener), \
                        patch.object(E.http.client, "HTTPSConnection") as secondary, \
                        self.assertRaises(E.Denied) as caught:
                    E.public_dns_addresses("canonical.example.com")
                secondary.assert_not_called()
                self.assertEqual(code, caught.exception.code)
                self.assertEqual(kind, caught.exception.dns_observation["kind"])
                self.assertEqual(status, caught.exception.dns_observation["dnsStatus"])
                self.assertNotIn("PRIVATE", json.dumps(caught.exception.dns_observation))

    def test_public_dns_placeholders_use_one_verified_secondary_query(self):
        for placeholder in ("0.0.0.0", "198.18.1.99"):
            with self.subTest(placeholder=placeholder):
                primary = unittest.mock.MagicMock()
                primary.__enter__.return_value = primary
                primary.read.return_value = json.dumps({"Status": 0, "Answer": [
                    {"type": 1, "data": placeholder}]}).encode()
                opener = unittest.mock.Mock()
                opener.open.return_value = primary
                response = unittest.mock.Mock(status=200)
                response.read.return_value = b'{"Status":0,"Answer":[{"type":1,"data":"8.8.8.8"}]}'
                connection = unittest.mock.Mock()
                connection.getresponse.return_value = response
                with patch.object(E, "build_opener", return_value=opener), \
                        patch.object(E.http.client, "HTTPSConnection", return_value=connection) as secondary, \
                        patch.object(E.socket, "getaddrinfo", side_effect=AssertionError("LOCAL_DNS_NOT_USED")):
                    self.assertEqual(["8.8.8.8"], E.public_dns_addresses("canonical.example.com"))
                self.assertEqual(("1.1.1.1",), secondary.call_args.args)
                self.assertEqual(5, secondary.call_args.kwargs["timeout"])
                context = secondary.call_args.kwargs["context"]
                self.assertTrue(context.check_hostname)
                self.assertEqual(ssl.CERT_REQUIRED, context.verify_mode)
                secondary.assert_called_once()
                connection.request.assert_called_once_with("GET", "/dns-query?name=canonical.example.com&type=A",
                    headers={"Accept": "application/dns-json"})
                response.read.assert_called_once_with(16385)
                connection.close.assert_called_once()

    def test_public_dns_secondary_failure_never_releases_placeholder_or_private_addresses(self):
        cases = [(200, b'{"Status":0,"Answer":[{"type":1,"data":"127.0.0.1"}]}'),
                 (200, b'{"Status":0,"Answer":[{"type":1,"data":"198.18.1.99"}]}'),
                 (200, b'{"Status":0,"Answer":[{"type":1,"data":"8.8.8.8"},{"type":1,"data":"10.0.0.1"}]}'),
                 (200, b'{"Status":3}'), (200, b'invalid'), (200, b'[]' * 8193), (302, b'')]
        for status, body in cases:
            with self.subTest(status=status, bodyBytes=len(body)):
                primary = unittest.mock.MagicMock()
                primary.__enter__.return_value = primary
                primary.read.return_value = b'{"Status":0,"Answer":[{"type":1,"data":"0.0.0.0"}]}'
                opener = unittest.mock.Mock()
                opener.open.return_value = primary
                response = unittest.mock.Mock(status=status)
                response.read.return_value = body
                connection = unittest.mock.Mock()
                connection.getresponse.return_value = response
                with patch.object(E, "build_opener", return_value=opener), \
                        patch.object(E.http.client, "HTTPSConnection", return_value=connection) as secondary, \
                        self.assertRaises(E.Denied) as caught:
                    E.public_dns_addresses("canonical.example.com")
                secondary.assert_called_once()
                connection.close.assert_called_once()
                self.assertEqual("cloudflare", caught.exception.dns_observation["resolver"])
                self.assertEqual("alidns", caught.exception.dns_observation["fallbackFrom"])

    def test_public_dns_secondary_tls_and_transport_errors_fail_closed(self):
        primary = unittest.mock.MagicMock()
        primary.__enter__.return_value = primary
        primary.read.return_value = b'{"Status":0,"Answer":[{"type":1,"data":"0.0.0.0"}]}'
        opener = unittest.mock.Mock()
        opener.open.return_value = primary
        for error in (ssl.SSLCertVerificationError("PRIVATE"), TimeoutError("PRIVATE"),
                      http.client.BadStatusLine("PRIVATE")):
            with self.subTest(error=type(error).__name__):
                connection = unittest.mock.Mock()
                connection.getresponse.side_effect = error
                with patch.object(E, "build_opener", return_value=opener), \
                        patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                        self.assertRaises(E.Denied) as caught:
                    E.public_dns_addresses("canonical.example.com")
                self.assertEqual("EGRESS_DNS_FAILED", caught.exception.code)
                self.assertEqual("transport_error", caught.exception.dns_observation["kind"])
                self.assertNotIn("PRIVATE", json.dumps(caught.exception.dns_observation))
                connection.close.assert_called_once()

    def test_origin_scope_accepts_dynamic_paths_and_queries_only_on_the_pinned_origin(self):
        configured = {**policy(), "requestScope": "origin", "exactUrls": []}
        E.policy_from(configured, allow_empty=True)
        for path in ("/list/second?page=2&tag=latest", "/detail/a-32", "/play/a%2Fb?token=public-value"):
            value = {**request(), "method": "GET", "url": configured["origin"] + path, "body": "",
                     "headers": {"Referer": configured["origin"] + "/detail/a-32"}}
            self.assertEqual(configured["origin"].split("://")[1], E.validate_request(value, configured)[0].netloc)
        for target in ("https://127.0.0.1/a", "https://other.example.com/a",
                       "https://reader.example.com:444/a", "http://reader.example.com/a",
                       "https://user:pass@reader.example.com/a", "https://reader.example.com/a#part"):
            with self.subTest(target=target), self.assertRaises(E.Denied):
                E.validate_request({**request(), "url": target}, configured)

    def test_origin_scope_does_not_relax_methods_headers_body_or_existing_exact_policies(self):
        configured = {**policy(), "requestScope": "origin", "exactUrls": []}
        for changes in ({"method": "DELETE"}, {"headers": {"Authorization": "private"}},
                        {"headers": {"Referer": "https://unapproved.example.com/a"}},
                        {"method": "GET", "body": "eA=="}):
            with self.subTest(changes=changes), self.assertRaises(E.Denied):
                E.validate_request({**request(), **changes}, configured)
        with self.assertRaises(E.Denied):
            E.validate_request({**request(), "url": policy()["origin"] + "/unregistered"}, policy())
        with self.assertRaises(E.Denied):
            E.policy_from({**configured, "requestScope": "all"}, allow_empty=True)

    def test_approved_pool_tries_tcp_then_tls_failures_before_sending_one_request(self):
        configured = {**policy(), "pinnedIpv4": ["8.8.8.8", "1.1.1.1", "4.2.2.2"]}
        original = copy.deepcopy(configured)
        calls, connections, sent = [], [], []

        class Sock:
            def __init__(self, address):
                self.address = address
            def getpeername(self):
                return self.address
            def settimeout(self, timeout):
                self.timeout = timeout
            def close(self):
                pass

        class Connection(FakeConnection):
            def connect(self):
                self.sock = self._create_connection(("reader.example.com", 443), timeout=5)
                if self.sock.address[0] == "1.1.1.1":
                    raise ssl.SSLCertVerificationError("PRIVATE certificate message")
            def request(self, *args, **kwargs):
                sent.append(True)
                super().request(*args, **kwargs)

        def connector(address, **kwargs):
            calls.append(address[0])
            if address[0] == "8.8.8.8":
                raise TimeoutError("PRIVATE timeout message")
            return Sock(address)

        def factory(host, port, **kwargs):
            self.assertEqual(("reader.example.com", 443), (host, port))
            self.assertTrue(kwargs["context"].check_hostname)
            self.assertEqual(ssl.CERT_REQUIRED, kwargs["context"].verify_mode)
            connection = Connection()
            connections.append(connection)
            return connection

        broker = E.Broker(configured, connector=connector)
        with patch.object(E.http.client, "HTTPSConnection", side_effect=factory), patch.object(E.time, "sleep"):
            broker.fetch(request())
            broker.fetch(request())
        self.assertEqual(["8.8.8.8", "1.1.1.1", "4.2.2.2", "4.2.2.2"], calls)
        self.assertEqual(2, len(sent))
        self.assertEqual(2, broker.count)
        self.assertEqual(["tcp_connect", "tls_handshake"], [row["stage"] for row in broker.network_observations])
        self.assertEqual(["timeout", "certificate"], [row["kind"] for row in broker.network_observations])
        self.assertEqual([2, 2], [row["connectedAddressIndex"] for row in broker.events])
        self.assertNotIn("PRIVATE", json.dumps(broker.network_observations))
        self.assertEqual(original, configured)

    def test_sent_post_is_not_replayed_after_response_timeout(self):
        configured = {**policy(), "pinnedIpv4": ["8.8.8.8", "1.1.1.1"]}
        connection = FakeConnection()
        connection.getresponse = unittest.mock.Mock(side_effect=TimeoutError("PRIVATE response timeout"))
        broker = E.Broker(configured)
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection) as factory, \
                self.assertRaises(TimeoutError):
            broker.fetch(request())
        self.assertEqual(1, factory.call_count)
        self.assertEqual(1, connection.getresponse.call_count)
        self.assertEqual("response_headers", broker.network_observations[0]["stage"])
        self.assertNotIn("PRIVATE", json.dumps(broker.network_observations))

    def test_response_timeout_uses_remaining_request_budget_after_connect(self):
        for execution_seconds, expected in ((None, 30), (12, 12)):
            with self.subTest(execution_seconds=execution_seconds):
                connection = FakeConnection()
                connection.sock = unittest.mock.Mock()
                broker = E.Broker(policy())
                if execution_seconds is not None:
                    broker.bind_execution(E.time.monotonic() + execution_seconds, None)
                with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
                    broker.fetch(request())
                timeout = connection.sock.settimeout.call_args.args[0]
                self.assertGreater(timeout, expected - 2)
                self.assertLessEqual(timeout, expected)

    def test_network_diagnostics_remain_bounded_and_execution_deadline_wins(self):
        configured = {**policy(), "pinnedIpv4": ["8.8.8.8", "1.1.1.1", "4.2.2.2"], "maxRequests": 12}
        connection = FakeConnection()
        connection.connect = unittest.mock.Mock(side_effect=TimeoutError("PRIVATE"))
        broker = E.Broker(configured)
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), patch.object(E.time, "sleep"):
            for _ in range(12):
                with self.assertRaises(TimeoutError):
                    broker.fetch(request())
        self.assertEqual(32, len(broker.network_observations))
        self.assertEqual(4, broker.network_observations_dropped)
        self.assertEqual(36, connection.connect.call_count)
        broker = E.Broker(configured)
        broker.bind_execution(E.time.monotonic() - 1, None)
        with patch.object(broker, "wait_for_slot"), patch.object(E.http.client, "HTTPSConnection") as factory, \
                self.assertRaisesRegex(E.Denied, "EGRESS_HTTP_DEADLINE"):
            broker.fetch(request())
        factory.assert_not_called()

    def token_policy(self):
        origin = policy()["origin"]
        return {**policy(), "paths": [], "exactUrls": [origin + "/device", origin + "/list"],
                "sessionToken": {"requestUrl": origin + "/device", "jsonPath": ["data", "token"]}}

    def test_only_current_origin_response_token_can_be_sent_as_bearer(self):
        configured = self.token_policy()
        broker = E.Broker(configured)
        value = {"method": "GET", "url": configured["exactUrls"][1], "body": "",
                 "headers": {"Authorization": "Bearer session|issued"}}
        with self.assertRaisesRegex(E.Denied, "EGRESS_SESSION_TOKEN_DENIED"):
            broker.fetch(value)
        connection = FakeConnection()
        connection.response.getheader = lambda key, default=None: (
            "application/json" if key == "Content-Type" else default)
        connection.response.read = lambda limit: b'{"data":{"token":"session|issued"}}'
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), patch.object(E.time, "sleep"):
            broker.fetch({**value, "method": "POST", "url": configured["exactUrls"][0], "headers": {}})
            broker.fetch(value)
        self.assertEqual("Bearer session|issued", connection.sent[3]["Authorization"])
        self.assertNotIn("session|issued", json.dumps(broker.events))
        self.assertNotIn("session|issued", json.dumps(configured))
        for headers in ({"Authorization": "Bearer other"},
                        {"Authorization": "Bearer session|issued", "authorization": "Bearer session|issued"}):
            with self.subTest(headers=headers), self.assertRaisesRegex(E.Denied, "EGRESS_SESSION_TOKEN_DENIED"):
                broker.fetch({**value, "headers": headers})
        other = {**configured, "origin": "https://other.example.com",
                 "exactUrls": [url.replace("reader.example.com", "other.example.com")
                               for url in configured["exactUrls"]],
                 "sessionToken": {"requestUrl": "https://other.example.com/device", "jsonPath": ["data", "token"]}}
        with self.assertRaisesRegex(E.Denied, "EGRESS_SESSION_TOKEN_DENIED"):
            E.Broker(other).fetch({**value, "url": other["exactUrls"][1]})
        self.assertEqual([], E.Broker(configured).session_tokens)

    def test_session_token_rules_cannot_grant_unapproved_origins_or_bad_fields(self):
        configured = self.token_policy()
        for grant in ({"requestUrl": "https://other.example.com/device", "jsonPath": ["token"]},
                      {"requestUrl": configured["exactUrls"][0], "jsonPath": []},
                      {"requestUrl": configured["exactUrls"][0], "jsonPath": ["token"], "value": "literal"},
                      {"requestUrl": configured["exactUrls"][0], "jsonPath": ["token\n"]},
                      {"responseScope": "origin"},
                      {"responseScope": "origin", "value": "literal"}):
            with self.subTest(grant=grant), self.assertRaisesRegex(E.Denied, "EGRESS_POLICY_INVALID"):
                E.policy_from({**configured, "sessionToken": grant})
        self.assertEqual(configured, E.policy_from(configured))
        scoped = {**configured, "requestScope": "origin", "exactUrls": [],
                  "sessionToken": {"responseScope": "origin"}}
        self.assertEqual(scoped, E.policy_from(scoped, allow_empty=True))

    def test_origin_token_projection_accepts_common_json_shapes_with_a_bounded_result(self):
        grant = {"responseScope": "origin"}
        body = json.dumps({"token": "top", "data": {"access_token": "nested",
                          "authToken": "third", "token": "fourth", "accessToken": "fifth"}}).encode()
        self.assertEqual(["Bearer top", "Bearer nested", "Bearer third", "Bearer fourth"],
                         E.response_session_tokens(body, grant))
        for body in (b"not-json", b'{"token":null}', b'{"token":"a b"}', b'{"token":"a\\nb"}',
                     json.dumps({"token": "x" * 2042}).encode()):
            with self.subTest(bodyBytes=len(body)):
                self.assertEqual([], E.response_session_tokens(body, grant))

    def test_origin_tokens_require_a_complete_successful_json_response(self):
        configured = {**self.token_policy(), "requestScope": "origin",
                      "sessionToken": {"responseScope": "origin"}}
        body = b'{"token":"not-issued"}'
        for status, mime, declared, content in (
                (401, "application/json", None, body),
                (200, "text/plain", None, body),
                (200, "application/json", str(len(body) + 1), body),
                (200, "application/json", None, b'{"token":')):
            with self.subTest(status=status, mime=mime, declared=declared):
                broker = E.Broker(configured)
                connection = FakeConnection()
                connection.response.status = status
                connection.response.read = lambda limit: content
                connection.response.getheader = lambda key, default=None: (
                    mime if key == "Content-Type" else declared if key == "Content-Length" else default)
                with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
                    broker.fetch({"method": "POST", "url": configured["origin"] + "/auth/device",
                                  "body": "", "headers": {}})
                self.assertEqual([], broker.session_tokens)

    def test_token_capture_is_bounded_and_requires_the_selected_json_response(self):
        configured = {**self.token_policy(), "maxRequests": 64,
                      "maxResponseBytes": 4096, "maxTotalResponseBytes": 8192}
        broker = E.Broker(configured)
        connection = FakeConnection()
        connection.response.getheader = lambda key, default=None: (
            "application/json" if key == "Content-Type" else default)
        value = {"method": "GET", "url": configured["exactUrls"][0], "body": "", "headers": {}}
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), patch.object(E.time, "sleep"):
            for index in range(6):
                connection.response.read = lambda limit: json.dumps({"data": {"token": "issued-" + str(index)}}).encode()
                broker.fetch(value)
            self.assertEqual(["Bearer issued-" + str(index) for index in range(2, 6)], broker.session_tokens)
            for token in ("a b", "a\nb", "", "x" * 2042):
                connection.response.read = lambda limit: json.dumps({"data": {"token": token}}).encode()
                broker.fetch(value)
            connection.response.read = lambda limit: b'{"data":{"token":"wrong-url-token"}}'
            broker.fetch({**value, "url": configured["exactUrls"][1]})
        self.assertEqual(["Bearer issued-" + str(index) for index in range(2, 6)], broker.session_tokens)

    def test_multihop_request_budget_is_explicit_and_bounded(self):
        configured = {**policy(), "maxRequests": 64}
        self.assertEqual(64, E.policy_from(configured)["maxRequests"])
        for count in (0, 65, True):
            with self.subTest(count=count), self.assertRaises(E.Denied):
                E.policy_from({**configured, "maxRequests": count})

    def test_exact_urls_allow_only_reviewed_query_and_path_bytes(self):
        url = "https://reader.example.com/api.php?classname=%E6%8E%A8%E8%8D%90&offset=0"
        configured = {**policy(), "paths": [], "queryKeys": [], "exactUrls": [url],
                      "headerOrigins": ["https://www.example.com"],
                      "exactReferers": ["https://www.example.com/player/episode.html?source=reader"]}
        E.policy_from(configured)
        value = {**request(), "method": "GET", "body": "", "url": url,
                 "headers": {"Referer": configured["exactReferers"][0], "sec-ch-ua-mobile": "?1"}}
        self.assertEqual("/api.php", E.validate_request(value, configured)[0].path)
        for changed in (url.replace("offset=0", "offset=1"), url + "&token=secret",
                        url.replace("reader.example.com", "other.example.com"),
                        "http://127.0.0.1/api.php"):
            with self.subTest(url=changed), self.assertRaises(E.Denied):
                E.validate_request({**value, "url": changed}, configured)
        with self.assertRaisesRegex(E.Denied, "EGRESS_HEADER_ORIGIN_DENIED"):
            E.validate_request({**value, "headers": {"Referer": "https://other.example.com/"}}, configured)
        with self.assertRaisesRegex(E.Denied, "EGRESS_HEADER_ORIGIN_DENIED"):
            E.validate_request({**value, "headers": {"Referer": "https://www.example.com/player/other.html"}}, configured)
        for headers, current in (
            (value["headers"], {key: val for key, val in configured.items() if key != "exactReferers"}),
            ({"Origin": configured["exactReferers"][0]}, configured),
            ({"Referer": configured["exactReferers"][0] + "&extra=1"}, configured),
        ):
            with self.subTest(headers=headers), self.assertRaisesRegex(E.Denied, "EGRESS_HEADER_ORIGIN_DENIED"):
                E.validate_request({**value, "headers": headers}, current)
        connection = FakeConnection()
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            E.Broker(configured).fetch(value)
        self.assertEqual(configured["exactReferers"][0], connection.sent[3]["Referer"])
        for header in ("Authorization", "Cookie", "X-API-KEY"):
            with self.subTest(header=header), self.assertRaises(E.Denied):
                E.validate_request({**value, "headers": {header: "secret"}}, configured)

    def test_exact_policy_cannot_add_a_second_origin_or_private_header_origin(self):
        for changes in ({"exactUrls": ["https://other.example.com/api.php"]},
                        {"exactUrls": ["https://reader.example.com/a#fragment"]},
                        {"exactUrls": []},
                        {"headerOrigins": ["https://127.0.0.1"]},
                        {"exactReferers": ["http://www.example.com/player"]},
                        {"exactReferers": ["https://www.example.com/player/#fragment"]},
                        {"exactReferers": ["https://www.example.com:443/player"]},
                        {"exactReferers": ["https://127.0.0.1/player"]},
                        {"exactReferers": ["https://other.example.com/player"]},
                        {"exactReferers": [None]},
                        {"exactReferers": ["https://www.example.com/player",
                                            "https://www.example.com/player"]}):
            with self.subTest(changes=changes), self.assertRaises(E.Denied):
                E.policy_from({**policy(), **changes})

    def test_private_player_dependency_is_classified_without_resolving_or_connecting(self):
        with self.assertRaisesRegex(E.Denied, "EGRESS_NONPUBLIC_TARGET_DENIED"):
            E.validate_request({**request(), "method": "GET", "body": "",
                                "url": "http://192.168.1.20:8800/api/videomodel?vid=1"}, policy())

    def test_media_prefix_range_is_exact_and_has_its_own_response_bound(self):
        configured = {**policy(), "maxResponseBytes": 2097152}
        ranged = {**request(), "method": "GET", "body": "",
                  "headers": {"Range": "bytes=0-65535"}}
        E.validate_request(ranged, configured)
        for value in ("bytes=2-1", "bytes=0-", "bytes=0-65536", "bytes=0-1,3-4",
                      "bytes=-12", "bytes=9223372036854775807-9223372036854775807"):
            with self.subTest(value=value), self.assertRaisesRegex(E.Denied, "EGRESS_RANGE_DENIED"):
                E.validate_request({**ranged, "headers": {"Range": value}}, configured)
        with self.assertRaisesRegex(E.Denied, "EGRESS_RANGE_DENIED"):
            E.validate_request({**ranged, "method": "POST"}, configured)
        connection = FakeConnection()
        connection.response.status = 206
        connection.response.read = lambda limit: b"\x00\x00\x00\x18ftypisom"
        connection.response.getheader = lambda key, default=None: (
            "bytes 0-11/1000000" if key == "Content-Range" else default)
        broker = E.Broker(configured)
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            broker.fetch(ranged)
        self.assertTrue(broker.events[0]["rangeRequested"])
        self.assertEqual("mp4", broker.events[0]["prefixFormat"])
        self.assertFalse(broker.events[0]["resourceComplete"])
        connection.response.read = lambda limit: b"x" * limit
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), self.assertRaisesRegex(
                E.Denied, "EGRESS_BYTE_BUDGET"):
            E.Broker(configured).fetch(ranged)
        connection.response.read = lambda limit: b"not media"
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), self.assertRaisesRegex(
                E.Denied, "EGRESS_RANGE_INVALID"):
            E.Broker(configured).fetch(ranged)

    def test_nonzero_range_is_bounded_by_length_and_checks_response_coordinates(self):
        configured = {**policy(), "maxResponseBytes": 2097152}
        start, end, total = 4_000_000_000, 4_000_000_006, 8_000_000_000
        ranged = {**request(), "method": "GET", "body": "",
                  "headers": {"Range": "bytes=%d-%d" % (start, end)}}
        E.validate_request(ranged, configured)
        connection = FakeConnection()
        connection.response.status = 206
        observed = []
        def read(limit):
            observed.append(limit)
            return b"[binary"
        connection.response.read = read
        connection.response.getheader = lambda key, default=None: (
            "bytes %d-%d/%d" % (start, end, total) if key == "Content-Range" else default)
        broker = E.Broker(configured)
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            result = broker.fetch(ranged)
        self.assertEqual(b"[binary", base64.b64decode(result["body"]))
        self.assertEqual([8], observed)
        self.assertEqual(7, broker.total)
        self.assertFalse(broker.events[0]["resourceComplete"])
        for header in ("bytes 0-6/8000000000", "bytes 4000000000-4000000007/8000000000",
                       "bytes 4000000000-4000000006/4000000006", "bytes 4000000000-4000000006/*"):
            connection.response.getheader = lambda key, default=None: header if key == "Content-Range" else default
            with self.subTest(header=header), patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                    self.assertRaisesRegex(E.Denied, "EGRESS_RANGE_INVALID"):
                E.Broker(configured).fetch(ranged)
        connection.response.status = 200
        connection.response.read = lambda limit: b"x" * limit
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            result = E.Broker(configured).fetch(ranged)
        self.assertEqual(7, len(base64.b64decode(result["body"])))

    def test_server_ignoring_range_returns_a_bounded_prefix_not_a_page_budget_failure(self):
        configured = {**policy(), "maxResponseBytes": 2097152, "maxTotalResponseBytes": 4194304}
        ranged = {**request(), "method": "GET", "body": "",
                  "headers": {"Range": "bytes=0-65535"}}
        for size, declared_length, complete in (
            (70000, "70000", False), (70000, None, False),
            (65536, "65536", True), (65536, None, False), (12, None, True),
            (12, "70000", False),
        ):
            with self.subTest(size=size, declared_length=declared_length):
                connection = FakeConnection()
                content = b"\x00\x00\x00\x18ftypisom" + b"x" * (size - 12)
                stream = io.BytesIO(content)
                connection.response.read = stream.read
                connection.response.getheader = lambda key, default=None: (
                    declared_length if key == "Content-Length" else default)
                broker = E.Broker(configured)
                with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
                    response = broker.fetch(ranged)
                prefix = base64.b64decode(response["body"], validate=True)
                self.assertEqual(content[:65536], prefix)
                self.assertEqual(len(prefix), stream.tell())
                self.assertLessEqual(stream.tell(), 65536)
                self.assertEqual(len(prefix), broker.total)
                self.assertEqual("mp4", broker.events[0]["prefixFormat"])
                self.assertEqual(complete, broker.events[0]["resourceComplete"])

        connection.response.getheader = lambda key, default=None: (
            "invalid" if key == "Content-Length" else default)
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), self.assertRaisesRegex(
                E.Denied, "EGRESS_CONTENT_LENGTH_INVALID"):
            E.Broker(configured).fetch(ranged)

    def test_ignored_range_still_obeys_the_remaining_total_budget(self):
        configured = {**policy(), "maxResponseBytes": 2097152, "maxTotalResponseBytes": 70000}
        ranged = {**request(), "method": "GET", "body": "",
                  "headers": {"Range": "bytes=0-65535"}}
        connection = FakeConnection()
        stream = io.BytesIO(b"<html>" + b"x" * 70000)
        connection.response.read = stream.read
        broker = E.Broker(configured)
        broker.total = 69900
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection) as connected:
            response = broker.fetch(ranged)
            self.assertEqual(100, len(base64.b64decode(response["body"], validate=True)))
            self.assertEqual(100, stream.tell())
            self.assertEqual(70000, broker.total)
            self.assertEqual("error_document", broker.events[0]["prefixFormat"])
            self.assertFalse(broker.events[0]["resourceComplete"])
            with self.assertRaisesRegex(E.Denied, "EGRESS_BYTE_BUDGET"):
                broker.fetch(ranged)
            self.assertEqual(1, connected.call_count)

    def test_larger_html_requires_an_explicit_bounded_policy(self):
        E.policy_from({**policy(), "maxResponseBytes": 8388608, "maxTotalResponseBytes": 67108864})
        for changes in ({"maxResponseBytes": 8388609}, {"maxTotalResponseBytes": 67108865}):
            with self.subTest(changes=changes), self.assertRaises(E.Denied):
                E.policy_from({**policy(), **changes})

    def test_explicit_large_page_policy_delivers_full_bytes_across_the_old_total_limit(self):
        configured = {**policy(), "maxResponseBytes": 8388608, "maxTotalResponseBytes": 67108864}
        broker = E.Broker(configured)
        body = b"<html>" + b"x" * (3 * 1024 * 1024)
        connection = FakeConnection()
        connection.response.getheader = lambda key, default=None: (
            str(len(body)) if key == "Content-Length" else default)
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                patch.object(broker, "wait_for_slot"):
            for _ in range(2):
                connection.response.read = io.BytesIO(body).read
                response = broker.fetch(request())
                self.assertEqual(body, base64.b64decode(response["body"], validate=True))
        self.assertEqual(2 * len(body), broker.total)
        self.assertEqual([len(body)] * 2, [event["responseBytes"] for event in broker.events])
        self.assertTrue(all(event["responseComplete"] for event in broker.events))

    def test_byte_budget_diagnostics_distinguish_single_total_and_streamed_refusals(self):
        for total, declared, reason in ((0, True, "single_response_bytes"),
                                        (900, True, "total_response_bytes"),
                                        (900, False, "total_response_bytes")):
            with self.subTest(total=total, declared=declared):
                broker, connection = E.Broker(policy()), FakeConnection()
                broker.total = total
                connection.response.read = io.BytesIO(b"x" * 513).read
                connection.response.getheader = lambda key, default=None: (
                    "513" if declared and key == "Content-Length" else default)
                with patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                        self.assertRaises(E.Denied) as caught:
                    broker.fetch(request())
                error = caught.exception
                self.assertEqual("EGRESS_BYTE_BUDGET", error.code)
                self.assertEqual({
                    "kind": "byte_budget", "reason": reason,
                    "stage": "content_length" if declared else "body_read",
                    "maxResponseBytes": 512, "maxTotalResponseBytes": 1024,
                    "totalResponseBytesBefore": total,
                    "effectiveResponseLimitBytes": min(512, 1024 - total),
                    "observedResponseBytes": 513 if declared else 125}, error.request_diagnostic)
                self.assertEqual([], broker.events)
        broker = E.Broker(policy())
        broker.total = 1024
        with patch.object(E.http.client, "HTTPSConnection") as connected, self.assertRaises(E.Denied) as caught:
            broker.fetch(request())
        connected.assert_not_called()
        self.assertEqual("before_request", caught.exception.request_diagnostic["stage"])
        self.assertEqual("total_response_bytes", caught.exception.request_diagnostic["reason"])

    def test_large_response_still_respects_per_page_and_remaining_total_limits(self):
        configured = {**policy(), "maxResponseBytes": 2097152, "maxTotalResponseBytes": 4194304}
        connection = FakeConnection()
        connection.response.read = lambda limit: b"x" * min(limit, 600000)
        broker = E.Broker(configured)
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            body = broker.fetch(request())
        self.assertEqual(600000, len(base64.b64decode(body["body"], validate=True)))
        broker = E.Broker(configured)
        broker.total = 4000000
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), self.assertRaisesRegex(
                E.Denied, "EGRESS_BYTE_BUDGET"):
            broker.fetch(request())
        self.assertEqual([], broker.events)

    def test_head_entity_size_does_not_spend_the_response_body_budget(self):
        connection = FakeConnection()
        connection.response.read = io.BytesIO(b"").read
        connection.response.getheader = lambda key, default=None: (
            "70000000" if key == "Content-Length" else default)
        broker = E.Broker(policy())
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            response = broker.fetch({**request(), "method": "HEAD", "body": ""})
        self.assertEqual(b"", base64.b64decode(response["body"], validate=True))
        self.assertEqual(0, broker.total)
        self.assertTrue(broker.events[0]["responseComplete"])

    def test_head_missing_resource_is_observed_without_returning_error_bodies(self):
        connection = FakeConnection()
        connection.response.status = 404
        connection.response.read = io.BytesIO(b"").read
        connection.response.getheader = lambda key, default=None: (
            "70000000" if key == "Content-Length" else default)
        broker = E.Broker(policy())
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            response = broker.fetch({**request(), "method": "HEAD", "body": ""})
        self.assertEqual(404, response["status"])
        self.assertEqual("", response["body"])
        self.assertEqual("head_not_found", broker.events[0]["responseClass"])
        self.assertEqual(0, broker.total)
        connection.response.getheader = lambda key, default=None: "0" if key == "Content-Length" else default
        for method, status in (("GET", 404), ("HEAD", 401), ("HEAD", 403)):
            connection.response.status = status
            with self.subTest(method=method, status=status), \
                    patch.object(E.http.client, "HTTPSConnection", return_value=connection):
                reply = E.Broker(policy()).fetch({**request(), "method": method, "body": ""})
                self.assertEqual(status, reply["status"])
                self.assertEqual("", reply["body"])

    def test_url_method_and_paths_are_exact_before_any_connection(self):
        E.validate_request(request(), policy())
        for changes in (
            {"url": "http://reader.example.com/api/drama/list"},
            {"url": "https://reader.example.com.evil.test/api/drama/list"},
            {"url": "https://127.0.0.1/api/drama/list"},
            {"url": "https://reader.example.com:443/api/drama/list"},
            {"url": "https://reader.example.com/api/drama/list?target=http://127.0.0.1"},
            {"url": "https://reader.example.com/api/drama/%6cist"},
            {"url": "https://reader.example.com/api/drama/list#fragment"},
            {"url": "https://reader.example.com/api/drama/../list"},
            {"url": "https://reader.example.com\\@127.0.0.1/api/drama/list"},
            {"method": "CONNECT"}, {"method": "DELETE"}, {"extra": True},
        ):
            with self.subTest(changes=changes), self.assertRaises(E.Denied):
                E.validate_request({**request(), **changes}, policy())

    def test_headers_body_and_nonempty_account_tokens_are_denied(self):
        for key, value in (
            ("Authorization", "Bearer PRIVATE"), ("Cookie", "PRIVATE"), ("Host", "127.0.0.1"),
            ("token", "PRIVATE"), ("Referer", "http://127.0.0.1/"), ("User-Agent", "x\r\nHost:127.0.0.1"),
        ):
            with self.subTest(key=key), self.assertRaises(E.Denied):
                E.validate_request({**request(), "headers": {key: value}}, policy())
        for body in ("!", base64.b64encode(b"x" * 65537).decode()):
            with self.assertRaises(E.Denied):
                E.validate_request({**request(), "body": body}, policy())
        with self.assertRaises(E.Denied):
            E.validate_request({**request(), "method": "GET"}, policy())

    def test_private_loopback_shared_linklocal_and_mixed_dns_are_refused(self):
        for ip in ("127.0.0.1", "10.0.0.1", "192.168.31.1", "169.254.169.254",
                   "100.64.0.1", "0.0.0.0", "224.0.0.1", "240.0.0.1", "198.51.100.1"):
            self.assertFalse(E.public_ipv4(ip))
            mixed = lambda *a, **k: dns() + [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, 443))]
            with self.subTest(ip=ip), self.assertRaises(E.Denied):
                E.approved_addresses("reader.example.com", mixed)
        self.assertEqual(["8.8.8.8"], E.approved_addresses("reader.example.com", dns))
        for ip in ("127.0.0.1", "198.18.0.10", "100.64.0.1", "::1"):
            with self.subTest(ip=ip), self.assertRaises(E.Denied):
                E.policy_from({**policy(), "pinnedIpv4": [ip]})
        seen = []

        def resolver(host, port, **kwargs):
            seen.append((host, port))
            return dns(host, port, **kwargs)

        self.assertEqual(["8.8.8.8"], E.approved_addresses("reader.example.com", resolver, port=15666))
        self.assertEqual([("reader.example.com", 15666)], seen)

    def test_numeric_query_contract_rejects_duplicates_unexpected_keys_and_malformed_fields(self):
        configured = {**policy(), "queryKeys": ["series_id"]}
        base = "https://reader.example.com/api/drama/detail"
        E.validate_request({**request(), "url": base + "?series_id=123456"}, configured)
        for query in ("series_id=1&series_id=2", "series_id=http://127.0.0.1", "target=123",
                      "series_id", "series_id=", "series_id=%2fetc%2fpasswd"):
            with self.subTest(query=query), self.assertRaises(E.Denied):
                E.validate_request({**request(), "url": base + "?" + query}, configured)

    def test_fetch_uses_pinned_numeric_ip_preserves_tls_hostname_and_hides_cookies(self):
        broker = E.Broker(policy())
        connection = FakeConnection()
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection) as factory:
            response = broker.fetch(request())
        self.assertEqual("reader.example.com", factory.call_args.args[0])
        self.assertEqual({"Content-Type": "application/json"}, response["headers"])
        self.assertNotIn("PRIVATE", json.dumps(broker.events))
        self.assertEqual("identity", connection.sent[3]["Accept-Encoding"])
        class Sock:
            def getpeername(self):
                return ("8.8.8.8", 443)
        calls = []
        broker.connector = lambda address, **kwargs: calls.append(address) or Sock()
        connection._create_connection(("reader.example.com", 443))
        self.assertEqual([("8.8.8.8", 443)], calls)
        with self.assertRaises(E.Denied):
            connection._create_connection(("127.0.0.1", 443))

    def test_non_http_private_and_invalid_declared_ports_are_refused(self):
        for origin in ("ftp://reader.example.com", "https://reader.example.com:0",
                       "https://reader.example.com:65536", "https://reader.example.com:bad",
                       "http://reader.example.com:0", "http://reader.example.com:65536",
                       "http://127.0.0.1", "https://127.0.0.1",
                       "http://reader.example.com@127.0.0.1"):
            with self.subTest(origin=origin), self.assertRaises(E.Denied):
                E.policy_from({**policy(), "origin": origin})

    def test_http_uses_pinned_cleartext_transport_and_keeps_origin_and_port_bound(self):
        for declared_port in (None, 8080):
            with self.subTest(port=declared_port):
                origin = "http://reader.example.com" + (":" + str(declared_port) if declared_port else "")
                configured = {**policy(), "origin": origin, "paths": [], "queryKeys": [],
                              "exactUrls": [origin + "/catalog?page=2"],
                              "headerOrigins": ["https://www.example.com"],
                              "exactReferers": [origin + "/browse", "https://www.example.com/player"]}
                broker, connection = E.Broker(configured), FakeConnection()
                value = {**request(), "method": "GET", "url": configured["exactUrls"][0],
                         "body": "", "headers": {"Referer": origin + "/browse"}}
                with patch.object(E.http.client, "HTTPConnection", return_value=connection) as factory, \
                        patch.object(E.http.client, "HTTPSConnection") as tls, \
                        patch.object(E.ssl, "create_default_context") as context:
                    broker.fetch(value)
                self.assertEqual(("reader.example.com", declared_port or 80), factory.call_args.args[:2])
                tls.assert_not_called()
                context.assert_not_called()
                self.assertEqual("/catalog?page=2", connection.sent[1])
                calls = []
                class Sock:
                    def getpeername(self):
                        return ("8.8.8.8", declared_port or 80)
                broker.connector = lambda address, **kwargs: calls.append(address) or Sock()
                connection._create_connection(("reader.example.com", declared_port or 80))
                self.assertEqual([("8.8.8.8", declared_port or 80)], calls)
                for target in (value["url"].replace("http://", "https://"),
                               "http://other.example.com/catalog?page=2"):
                    with self.assertRaises(E.Denied):
                        E.validate_request({**value, "url": target}, configured)
                with self.assertRaisesRegex(E.Denied, "EGRESS_POLICY_INVALID"):
                    E.policy_from({**configured, "exactUrls": [value["url"].replace("http://", "https://")]})

    def test_declared_https_port_binds_policy_urls_tls_and_pinned_socket(self):
        configured = {**policy(), "origin": "https://reader.example.com:15666",
                      "paths": [], "queryKeys": [],
                      "exactUrls": ["https://reader.example.com:15666/api/drama/list"]}
        E.policy_from(configured)
        E.validate_request({**request(), "url": configured["exactUrls"][0],
                            "headers": {}, "body": "", "method": "GET"}, configured)
        for url in ("https://reader.example.com/api/drama/list",
                    "https://reader.example.com:443/api/drama/list",
                    "https://reader.example.com:15667/api/drama/list"):
            with self.subTest(url=url), self.assertRaises(E.Denied):
                E.validate_request({**request(), "url": url, "headers": {}, "body": "",
                                    "method": "GET"}, configured)
        connection = FakeConnection()
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection) as factory:
            broker = E.Broker(configured)
            broker.fetch({**request(), "url": configured["exactUrls"][0],
                          "headers": {}, "body": "", "method": "GET"})
        self.assertEqual(("reader.example.com", 15666), factory.call_args.args[:2])
        class Sock:
            def getpeername(self):
                return ("8.8.8.8", 15666)
        calls = []
        broker.connector = lambda address, **kwargs: calls.append(address) or Sock()
        connection._create_connection(("reader.example.com", 15666))
        self.assertEqual([("8.8.8.8", 15666)], calls)
        with self.assertRaises(E.Denied):
            connection._create_connection(("reader.example.com", 443))

    def test_server_error_document_is_bounded_and_never_recorded_as_success(self):
        for status, content_type, content, expected_code in (
            (502, "text/html", b"<html>upstream unavailable PRIVATE</html>", None),
            (503, "application/json", b'{"error":"PRIVATE"}', None),
            (502, "application/octet-stream", b"PRIVATE", None),
            (502, "text/html", b"\xff", None),
            (403, "", b"\xffPRIVATE", None),
            (204, "", b"", None),
            (502, "text/html", b"x" * 513, "EGRESS_BYTE_BUDGET"),
            (401, "application/json", b'{"error":"PRIVATE"}', None),
            (403, "text/html", b"PRIVATE", None),
            (404, "application/json", b'{"error":"PRIVATE"}', None),
        ):
            with self.subTest(status=status, content_type=content_type, expected_code=expected_code):
                connection = FakeConnection()
                connection.response.status = status
                connection.response.read = io.BytesIO(content).read
                connection.response.getheader = lambda key, default=None: (
                    content_type if key == "Content-Type" else default)
                broker = E.Broker(policy())
                with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
                    if expected_code:
                        with self.assertRaisesRegex(E.Denied, expected_code):
                            broker.fetch(request())
                        self.assertEqual([], broker.events)
                    else:
                        response = broker.fetch(request())
                        self.assertEqual(status, response["status"])
                        self.assertEqual(content, base64.b64decode(response["body"], validate=True))
                        expected = ("http_2xx" if status < 400 else
                                    ("textual_" if content_type.startswith(("text/", "application/json")) else "")
                                    + ("server_error" if status >= 500 else "client_error"))
                        self.assertEqual(expected, broker.events[0]["responseClass"])
                        self.assertEqual(content_type, broker.events[0]["responseContentType"])
                        self.assertTrue(broker.events[0]["responseComplete"])
                        self.assertNotIn("PRIVATE", json.dumps(broker.events))
                    self.assertEqual(status, broker.last_http_observation["responseStatus"])
                    self.assertNotIn("PRIVATE", json.dumps(broker.last_http_observation))

    def test_rate_limit_is_classified_without_reading_body_or_retrying(self):
        connection = FakeConnection()
        connection.response.status = 429
        connection.response.read = unittest.mock.Mock(side_effect=AssertionError("429 body must not be read"))
        broker = E.Broker(policy())
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection) as connected:
            with self.assertRaisesRegex(E.Denied, "EGRESS_RATE_LIMITED"):
                broker.fetch(request())
        self.assertEqual(1, connected.call_count)
        connection.response.read.assert_not_called()
        self.assertEqual(1, broker.count)
        self.assertEqual(0, broker.total)
        self.assertEqual([], broker.events)
        self.assertEqual(429, broker.last_http_observation["responseStatus"])

    def test_redirect_compression_and_byte_budgets_do_not_return_body(self):
        for mode in ("redirect", "compression", "oversize"):
            broker = E.Broker(policy())
            connection = FakeConnection()
            if mode == "redirect":
                connection.response.status = 300
            elif mode == "compression":
                connection.response.getheader = lambda *a: "gzip"
            else:
                connection.response.read = lambda limit: b"x" * limit
            with patch.object(E.http.client, "HTTPSConnection", return_value=connection), self.assertRaises(E.Denied):
                broker.fetch(request())
            self.assertEqual([], broker.events)
            self.assertEqual(connection.response.status, broker.last_http_observation["responseStatus"])
        broker = E.Broker(policy())
        broker.count = 3
        with self.assertRaisesRegex(E.Denied, "EGRESS_REQUEST_BUDGET"):
            broker.fetch(request())


class RoutesTest(unittest.TestCase):
    def test_connection_and_rate_observations_follow_only_the_active_call_interval(self):
        broker = E.Routes(self.policy())
        call = {"id": 2, "method": "category", "inputSha256": "a" * 64}
        broker.set_source_call(call)
        connection = FakeConnection()
        connection.response.status = 429
        connection.response.getheader = lambda key, default=None: "120" if key == "Retry-After" else default
        broker.bind_execution(E.time.monotonic() + 10, None)
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            with self.assertRaisesRegex(E.Denied, "EGRESS_RATE_LIMITED"):
                broker.fetch(self.request())
        row = broker.rate_limits[0]
        self.assertEqual(call, row["observedDuringCall"])
        self.assertFalse(row["cooldownContext"]["fitsTaskBudget"])
        before = row["cooldownContext"]["remainingWaitMilliseconds"]
        broker.set_source_call(None)
        with self.assertRaisesRegex(E.Denied, "EGRESS_COOLDOWN_BUDGET"):
            broker.fetch(self.request())
        self.assertEqual(1, broker.count)
        self.assertEqual("before_wait", row["cooldownContext"]["phase"])
        self.assertLessEqual(row["cooldownContext"]["remainingWaitMilliseconds"], before)
        self.assertEqual(call, row["observedDuringCall"])
        broker.next_request, broker.cooldown_state = 0, None
        broker.set_source_call({**call, "id": 3})
        child = broker.routes[self.policy()["routes"][0]["origin"]]
        def failure(value):
            child.count += 1
            child.record_network_failure(value["url"], 0, "tls_handshake", TimeoutError("PRIVATE"), E.time.monotonic())
            raise TimeoutError("PRIVATE")
        with patch.object(child, "fetch", side_effect=failure), self.assertRaises(TimeoutError):
            broker.fetch(self.request())
        self.assertEqual(3, broker.network_observations[0]["observedDuringCall"]["id"])
        self.assertNotIn("PRIVATE", json.dumps(broker.network_observations))

    def test_pending_dns_cache_retains_no_new_elapsed_time_and_tracks_each_call(self):
        configured = self.policy()
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        origin = "https://pending.example.com"
        configured["pendingOrigins"] = [origin]
        broker = E.Routes(configured)
        def resolve(host, *, port, observations):
            row = {"dnsStatus": 3, "answerCount": 0, "publicIpv4Count": 0, "kind": "no_address",
                   "resolver": "alidns", "recordType": "A", "elapsedMilliseconds": 37}
            observations.append(row)
            raise E.Denied("EGRESS_DNS_NO_ADDRESS", dns_observation=row)
        with patch.object(E, "public_dns_addresses", side_effect=resolve) as resolver:
            for ident in (2, 3):
                broker.set_source_call({"id": ident, "method": "category", "inputSha256": "b" * 64})
                with self.assertRaisesRegex(E.Denied, "EGRESS_DNS_NO_ADDRESS"):
                    broker.fetch(self.request(origin + "/"))
        resolver.assert_called_once()
        self.assertEqual([2, 3], [row["observedDuringCall"]["id"] for row in broker.dns_observations])
        self.assertEqual(37, broker.dns_observations[0]["elapsedMilliseconds"])
        self.assertTrue(broker.dns_observations[1]["cacheHit"])
        self.assertNotIn("elapsedMilliseconds", broker.dns_observations[1])

    def test_player_playlist_registers_exact_keys_maps_and_sampled_cdn_resources(self):
        configured = self.policy()
        configured["playerResources"] = "public_https"
        frozen = copy.deepcopy(configured)
        broker = E.Routes(configured)
        url = "https://player.example.com/list.m3u8"
        headers = {"Referer": configured["routes"][0]["origin"] + "/"}
        with patch.object(E, "public_dns_addresses", return_value=["8.8.8.8"]):
            broker.register_player_resource(url, headers)
            broker.events.append({"responseComplete": True})
            broker.register_playlist_urls(url, {"status": 200, "body": base64.b64encode(
                b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n'
                b'#EXT-X-KEY:METHOD=AES-128,URI="https://key.example.com/key?token=PRIVATE",IV=0x1\n'
                b'#EXT-X-MAP:URI="https://cdn.example.com/init"\n#EXT-X-KEY:METHOD=NONE\n'
                b'#EXTINF:6,\nhttps://cdn.example.com/one\n#EXT-X-ENDLIST\n').decode()})
        for origin, target in (("https://key.example.com", "https://key.example.com/key?token=PRIVATE"),
                               ("https://cdn.example.com", "https://cdn.example.com/init"),
                               ("https://cdn.example.com", "https://cdn.example.com/one")):
            self.assertIn(target, broker.routes[origin].policy["exactUrls"])
            self.assertNotEqual("origin", broker.routes[origin].policy.get("requestScope"))
        self.assertEqual(frozen, broker.policy)
        self.assertEqual(0, broker.count)
        self.assertNotIn("PRIVATE", json.dumps(broker.events))
        self.assertEqual(headers, broker.player_resources["https://cdn.example.com/one"])
        with self.assertRaises(E.Denied):
            E.validate_request(self.request("https://cdn.example.com/other"), broker.routes["https://cdn.example.com"].policy)

    def test_host_player_resource_grants_one_exact_public_url_with_the_shared_budget(self):
        configured = self.policy()
        configured["playerResources"] = "public_https"
        configured["maxRequests"] = 2
        frozen = copy.deepcopy(configured)
        broker = E.Routes(configured)
        site = configured["routes"][0]["origin"]
        target = "https://cdn.example.com/file.mp4?signature=PRIVATE"
        headers = {"Referer": site + "/"}
        with patch.object(E, "public_dns_addresses", return_value=["8.8.8.8"]) as resolve:
            broker.register_player_resource(target, headers)
        resolve.assert_called_once_with("cdn.example.com", port=443, observations=unittest.mock.ANY)
        route = broker.routes["https://cdn.example.com"].policy
        self.assertEqual([target], route["exactUrls"])
        self.assertNotEqual("origin", route.get("requestScope"))
        self.assertEqual([site], route["headerOrigins"])
        with patch.object(E.http.client, "HTTPSConnection", side_effect=lambda *a, **k: FakeConnection()), \
                patch.object(E.time, "sleep"):
            broker.fetch(self.request())
            broker.fetch({"method": "GET", "url": target, "body": "", "headers": headers})
            with self.assertRaisesRegex(E.Denied, "EGRESS_REQUEST_BUDGET"):
                broker.fetch({"method": "GET", "url": target, "body": "", "headers": headers})
        self.assertEqual(2, broker.count)
        self.assertNotIn("PRIVATE", json.dumps(broker.events))
        self.assertEqual(frozen, broker.policy)
        with self.assertRaisesRegex(E.Denied, "EGRESS_URL_DENIED"):
            E.validate_request({"method": "GET", "url": "https://cdn.example.com/other", "body": "", "headers": {}}, route)
        legacy = E.Routes(self.policy())
        with patch.object(E, "public_dns_addresses") as resolve:
            legacy.register_player_resource(target, headers)
            resolve.assert_not_called()
        self.assertNotIn("https://cdn.example.com", legacy.routes)
        for target in ("http://cdn.example.com/file.mp4", "https://user:PRIVATE@cdn.example.com/file.mp4"):
            with self.subTest(target=target), self.assertRaises(E.Denied):
                broker.register_player_resource(target, headers)
        with patch.object(E, "public_dns_addresses", side_effect=E.Denied("EGRESS_NONPUBLIC_DNS")), \
                self.assertRaisesRegex(E.Denied, "EGRESS_NONPUBLIC_DNS"):
            broker.register_player_resource("https://private.example.com/file.mp4", {})

    def many_routes(self, count):
        configured = self.policy()
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        configured["routes"].extend({
            "origin": "https://site" + str(index) + ".example.com", "exactUrls": [],
            "requestScope": "origin", "pinnedIpv4": ["8.8.8.8"],
        } for index in range(count - len(configured["routes"])))
        return configured

    def test_many_declared_routes_share_the_existing_request_and_byte_budgets(self):
        for count in (20, 64):
            with self.subTest(count=count):
                configured = self.many_routes(count)
                configured["maxRequests"] = 2
                broker = E.Routes(configured)
                self.assertEqual(count, len(broker.routes))
                with patch.object(E.http.client, "HTTPSConnection", side_effect=lambda *a, **k: FakeConnection()), \
                        patch.object(E.time, "sleep"):
                    broker.fetch(self.request())
                    broker.fetch(self.request(configured["routes"][-1]["origin"] + "/list"))
                    with self.assertRaisesRegex(E.Denied, "^EGRESS_REQUEST_BUDGET$"):
                        broker.fetch(self.request())
                self.assertEqual(2, broker.count)
                self.assertEqual(2 * len(b'{"data":[]}'), broker.total)
                self.assertEqual(2, len(broker.events))
                for route in broker.routes.values():
                    for key in E.LIMITS:
                        self.assertEqual(configured[key], route.policy[key])

    def test_route_capacity_rejects_sixty_five_declared_origins(self):
        self.assertEqual(64, E.MAX_ROUTES)
        with self.assertRaisesRegex(E.Denied, "^EGRESS_POLICY_INVALID$"):
            E.policy_from(self.many_routes(65))

    def test_redirect_can_add_route_sixty_four_but_not_sixty_five(self):
        configured = self.many_routes(63)
        configured["responseRedirects"] = "public_http_https"
        broker = E.Routes(configured)
        issuer = broker.routes[configured["routes"][0]["origin"]]
        url = self.request()["url"]
        response = {"status": 302, "headers": {"Location": "https://canonical.example.com/list"}}
        with patch.object(E, "public_dns_addresses", return_value=["4.2.2.2"]) as resolve:
            broker.register_redirect(url, response, issuer)
            broker.register_redirect(url, response, issuer)
            self.assertEqual(64, len(broker.routes))
            self.assertEqual(["4.2.2.2"], broker.routes["https://canonical.example.com"].policy["pinnedIpv4"])
            response["headers"]["Location"] = "https://extra.example.com/list"
            with self.assertRaisesRegex(E.Denied, "^EGRESS_REQUEST_BUDGET$"):
                broker.register_redirect(url, response, issuer)
            resolve.assert_called_once_with("canonical.example.com", port=443, observations=unittest.mock.ANY)
        self.assertEqual(64, len(broker.routes))
        self.assertEqual(0, broker.count)

    def test_observed_public_redirect_adds_a_pinned_job_local_route_without_importing_state(self):
        configured = self.policy()
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        configured["responseRedirects"] = "public_https"
        frozen = copy.deepcopy(configured)
        broker = E.Routes(configured)
        deadline = E.time.monotonic() + 30
        broker.bind_execution(deadline, None)
        before = broker.snapshot_urls()
        target = "https://canonical.example.com:9443/catalog?ticket=PRIVATE#view"
        connection = FakeConnection()
        connection.response.status = 302
        connection.response.getheader = lambda key, default=None: target if key == "Location" else default
        connection.response.getheaders = lambda: []
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                patch.object(E, "public_dns_addresses", return_value=["4.2.2.2"]) as resolve:
            response = broker.fetch(self.request())
        resolve.assert_called_once_with("canonical.example.com", port=9443, observations=unittest.mock.ANY)
        self.assertEqual(target.removesuffix("#view"), response["headers"]["Location"])
        receiver = broker.routes["https://canonical.example.com:9443"]
        self.assertEqual(["4.2.2.2"], receiver.policy["pinnedIpv4"])
        self.assertEqual(deadline, receiver.deadline)
        self.assertEqual([], receiver.session_tokens)
        self.assertIsNot(receiver.session_tokens, broker.routes[configured["routes"][0]["origin"]].session_tokens)
        self.assertEqual(before, broker.snapshot_urls())
        self.assertEqual(frozen, broker.policy)
        self.assertNotIn("PRIVATE", json.dumps(broker.last_redirect))
        self.assertEqual("https://canonical.example.com:9443", broker.last_redirect["targetOrigin"])
        connection.response.status = 200
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), patch.object(E.time, "sleep"):
            broker.fetch(self.request(target.removesuffix("#view")))
        with self.assertRaises(E.Denied):
            E.Routes(configured).fetch(self.request(target.removesuffix("#view")))

    def test_public_redirect_permission_is_explicit_and_requires_origin_scoped_routes(self):
        configured = self.policy()
        for value in ("public_https", "public_http_https", "any", True):
            with self.subTest(value=value), self.assertRaisesRegex(E.Denied, "EGRESS_POLICY_INVALID"):
                E.policy_from({**configured, "responseRedirects": value})
        broker = E.Routes(configured)
        connection = FakeConnection()
        target = "https://canonical.example.com/list"
        connection.response.status = 302
        connection.response.getheader = lambda key, default=None: target if key == "Location" else default
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                patch.object(E, "public_dns_addresses") as resolve:
            broker.fetch(self.request())
        resolve.assert_not_called()
        self.assertNotIn("https://canonical.example.com", broker.routes)
        self.assertEqual("https://canonical.example.com", broker.last_redirect["targetOrigin"])
        with self.assertRaises(E.Denied):
            broker.fetch(self.request(target))

    def test_observed_http_redirect_is_pinned_only_when_http_redirects_are_enabled(self):
        configured = self.policy()
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        configured["responseRedirects"] = "public_http_https"
        target = "http://canonical.example.com:8080/catalog?ticket=PRIVATE"
        broker, connection = E.Routes(configured), FakeConnection()
        connection.response.status = 302
        connection.response.getheader = lambda key, default=None: target if key == "Location" else default
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                patch.object(E, "public_dns_addresses", return_value=["4.2.2.2"]) as resolve:
            broker.fetch(self.request())
        resolve.assert_called_once_with("canonical.example.com", port=8080, observations=unittest.mock.ANY)
        receiver = broker.routes["http://canonical.example.com:8080"]
        self.assertEqual(["4.2.2.2"], receiver.policy["pinnedIpv4"])
        self.assertEqual([], receiver.session_tokens)
        self.assertNotIn("PRIVATE", json.dumps(broker.last_redirect))
        connection.response.status = 200
        with patch.object(E.http.client, "HTTPConnection", return_value=connection), patch.object(E.time, "sleep"):
            broker.fetch(self.request(target))
        self.assertEqual("/catalog?ticket=PRIVATE", connection.sent[1])
        with self.assertRaises(E.Denied):
            E.Routes(configured).fetch(self.request(target))

    def test_public_redirects_do_not_grant_http_userinfo_private_dns_or_extra_origins(self):
        configured = self.policy()
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        configured["responseRedirects"] = "public_https"
        for target in ("http://canonical.example.com/end", "https://user:secret@canonical.example.com/end",
                       "https://127.0.0.1/end", "https://private.example.com/end"):
            with self.subTest(target=target):
                broker = E.Routes(configured)
                connection = FakeConnection()
                connection.response.status = 302
                connection.response.getheader = lambda key, default=None: target if key == "Location" else default
                with patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                        patch.object(E, "public_dns_addresses", side_effect=E.Denied(
                            "EGRESS_NONPUBLIC_DNS", dns_observation={"dnsStatus": 0,
                            "answerCount": 1, "publicIpv4Count": 0, "kind": "nonpublic_address"})):
                    if target.startswith(("http:", "https://user:")):
                        broker.fetch(self.request())
                    else:
                        with self.assertRaisesRegex(E.Denied, "EGRESS_NONPUBLIC_DNS"):
                            broker.fetch(self.request())
                self.assertEqual(2, len(broker.routes))
                self.assertNotIn("secret", json.dumps(broker.last_redirect))
                with self.assertRaises(E.Denied):
                    broker.fetch(self.request(target))

    def test_dynamic_origin_tokens_are_job_local_and_token_responses_are_not_page_evidence(self):
        configured = self.policy()
        route = configured["routes"][0]
        route.update(requestScope="origin", sessionToken={"responseScope": "origin"})
        configured["routes"][1]["requestScope"] = "origin"
        broker = E.Routes(configured)
        connection = FakeConnection()
        connection.response.getheader = lambda key, default=None: (
            "application/json" if key == "Content-Type" else default)
        connection.response.read = lambda limit: b'{"token":"device-issued"}'
        headers = {"Authorization": "Bearer device-issued"}
        with self.assertRaisesRegex(E.Denied, "EGRESS_SESSION_TOKEN_DENIED"):
            broker.fetch({**self.request(), "headers": headers})
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), patch.object(E.time, "sleep"):
            broker.fetch({**self.request(route["origin"] + "/api/v1/auth/device"), "method": "POST"})
            self.assertEqual([], broker.snapshot_resources())
            connection.response.read = lambda limit: b'{"data":{"accessToken":"authenticated-refresh"}}'
            broker.fetch({**self.request(route["origin"] + "/api/v1/categories"), "headers": headers})
            self.assertEqual("Bearer device-issued", connection.sent[3]["Authorization"])
            self.assertEqual([], broker.snapshot_resources())
            self.assertEqual(["Bearer device-issued"], broker.routes[route["origin"]].session_tokens)
            connection.response.read = lambda limit: b'{"data":[{"id":1}]}'
            broker.fetch({**self.request(), "headers": headers})
        self.assertEqual(1, len(broker.snapshot_resources()))
        self.assertNotIn("device-issued", json.dumps(broker.events))
        self.assertNotIn("authenticated-refresh", json.dumps(broker.snapshot_resources()))
        for target, bearer in ((self.request()["url"], "Bearer imported"),
                               (self.request()["url"], "Bearer authenticated-refresh"),
                               ("https://media.example.com/list", "Bearer device-issued")):
            with self.subTest(target=target, bearer=bearer), self.assertRaisesRegex(
                    E.Denied, "EGRESS_SESSION_TOKEN_DENIED"):
                broker.fetch({**self.request(target), "headers": {"Authorization": bearer}})
        with self.assertRaisesRegex(E.Denied, "EGRESS_SESSION_TOKEN_DENIED"):
            E.Routes(configured).fetch({**self.request(), "headers": headers})

    def test_explicit_api_group_shares_only_observed_tokens_with_declared_audiences(self):
        configured = self.policy()
        issuer, audience = configured["routes"][:2]
        issuer["sessionToken"] = {"requestUrl": issuer["exactUrls"][0], "jsonPath": ["token"]}
        audience["exactUrls"] = [audience["origin"] + "/list"]
        outside = {"origin": "https://images.example.com", "pinnedIpv4": ["8.8.8.8"],
                   "exactUrls": ["https://images.example.com/cover"]}
        configured["routes"].append(outside)
        configured["sessionTokenGroups"] = [[issuer["origin"], audience["origin"]]]
        frozen = copy.deepcopy(configured)
        broker = E.Routes(configured)
        value = {**self.request(audience["exactUrls"][0]),
                 "headers": {"Authorization": "Bearer api-issued"}}
        with self.assertRaisesRegex(E.Denied, "EGRESS_SESSION_TOKEN_DENIED"):
            broker.fetch(value)
        connection = FakeConnection()
        connection.response.getheader = lambda key, default=None: (
            "application/json" if key == "Content-Type" else default)
        connection.response.read = lambda limit: b'{"token":"api-issued"}'
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), patch.object(E.time, "sleep"):
            broker.fetch(self.request())
            broker.fetch(value)
        self.assertEqual("Bearer api-issued", connection.sent[3]["Authorization"])
        self.assertNotIn("api-issued", json.dumps(broker.events))
        self.assertEqual(frozen, configured)
        for selected, headers in (
                (outside["exactUrls"][0], value["headers"]),
                (value["url"], {"Authorization": "Bearer imported"}),
        ):
            with self.subTest(url=selected), self.assertRaisesRegex(E.Denied, "EGRESS_SESSION_TOKEN_DENIED"):
                broker.fetch({**self.request(selected), "headers": headers})
        with self.assertRaisesRegex(E.Denied, "EGRESS_SESSION_TOKEN_DENIED"):
            E.Routes(configured).fetch(value)

    def test_session_token_groups_require_declared_nonoverlapping_routes_and_an_issuer(self):
        configured = self.policy()
        origins = [route["origin"] for route in configured["routes"]]
        for groups in ([origins], [[origins[0], "https://unknown.example.com"]],
                       [[origins[0]]], [[origins[0], origins[0]]]):
            with self.subTest(groups=groups), self.assertRaisesRegex(E.Denied, "EGRESS_POLICY_INVALID"):
                E.policy_from({**configured, "sessionTokenGroups": groups})
        configured["routes"][0]["sessionToken"] = {
            "requestUrl": configured["routes"][0]["exactUrls"][0], "jsonPath": ["token"]}
        with self.assertRaisesRegex(E.Denied, "EGRESS_POLICY_INVALID"):
            E.policy_from({**configured, "sessionTokenGroups": [origins, origins]})

    def test_route_session_token_stays_with_its_issuer(self):
        configured = self.policy()
        route = configured["routes"][0]
        route["sessionToken"] = {"requestUrl": route["exactUrls"][0], "jsonPath": ["token"]}
        configured["routes"][1]["exactUrls"] = ["https://media.example.com/image.jpg"]
        frozen = copy.deepcopy(configured)
        broker = E.Routes(configured)
        connection = FakeConnection()
        connection.response.getheader = lambda key, default=None: (
            "application/json" if key == "Content-Type" else default)
        connection.response.read = lambda limit: b'{"token":"route-issued"}'
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), patch.object(E.time, "sleep"):
            broker.fetch(self.request())
            broker.fetch({**self.request(), "headers": {"Authorization": "Bearer route-issued"}})
        self.assertEqual("Bearer route-issued", connection.sent[3]["Authorization"])
        self.assertEqual(frozen, configured)
        self.assertNotIn("route-issued", json.dumps(broker.events))

    def test_pending_declared_origin_resolves_once_and_shares_the_existing_budget(self):
        configured = self.policy()
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        origin = "https://pending.example.com"
        configured["pendingOrigins"] = [origin]
        configured["maxRequests"] = 2
        broker, connection = E.Routes(configured), FakeConnection()
        with patch.object(E, "public_dns_addresses", return_value=["4.2.2.2"]) as resolve, \
                patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                patch.object(E.time, "sleep"):
            broker.fetch(self.request(origin + "/list"))
            broker.fetch(self.request())
            with self.assertRaisesRegex(E.Denied, "^EGRESS_REQUEST_BUDGET$"):
                broker.fetch(self.request(origin + "/second"))
        resolve.assert_called_once_with("pending.example.com", port=443, observations=unittest.mock.ANY)
        self.assertEqual(("4.2.2.2",), broker.routes[origin].addresses)
        self.assertEqual(2, broker.count)
        self.assertNotIn(origin, [route["origin"] for route in broker.policy["routes"]])

    def test_pending_dns_failures_are_observed_once_and_unknown_or_private_targets_stay_denied(self):
        configured = self.policy()
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        origin = "https://pending.example.com"
        configured["pendingOrigins"] = [origin]
        broker = E.Routes(configured)
        observation = {"dnsStatus": 3, "answerCount": 0, "publicIpv4Count": 0, "kind": "no_address"}
        with patch.object(E, "public_dns_addresses", side_effect=E.Denied(
                "EGRESS_DNS_NO_ADDRESS", dns_observation=observation)) as resolve:
            for index in range(2):
                with self.assertRaisesRegex(E.Denied, "^EGRESS_DNS_NO_ADDRESS$"):
                    broker.fetch(self.request(origin + "/"))
                self.assertTrue(all(broker.last_dns_observation[key] == value for key, value in observation.items()))
                self.assertEqual(bool(index), broker.last_dns_observation["cacheHit"])
            with self.assertRaisesRegex(E.Denied, "^EGRESS_URL_DENIED$"):
                broker.fetch(self.request("https://unknown.example.com/"))
        resolve.assert_called_once()
        self.assertEqual(0, broker.count)
        for pending in ([origin, origin], ["https://127.0.0.1"], [origin + "/path"],
                        [configured["routes"][0]["origin"]]):
            with self.subTest(pending=pending), self.assertRaisesRegex(E.Denied, "^EGRESS_POLICY_INVALID$"):
                E.policy_from({**configured, "pendingOrigins": pending})
        with patch.object(E, "public_dns_addresses", side_effect=E.Denied("EGRESS_NONPUBLIC_DNS")):
            with self.assertRaisesRegex(E.Denied, "^EGRESS_NONPUBLIC_DNS$"):
                E.Routes(configured).fetch(self.request(origin + "/"))

    def test_pending_guest_issuer_links_only_its_explicit_audience(self):
        configured = self.policy()
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        origin = "https://login.example.com"
        audience = configured["routes"][0]["origin"]
        configured["pendingOrigins"] = [origin]
        configured["sessionTokenGroups"] = [[origin, audience]]
        configured["maxRequests"] = 4
        broker, connection = E.Routes(configured), FakeConnection()
        connection.response.status = 201
        connection.response.getheader = lambda key, default=None: (
            "application/json" if key == "Content-Type" else default)
        connection.response.read = lambda limit: b'{"token":"guest-issued"}'
        with patch.object(E, "public_dns_addresses", return_value=["4.2.2.2"]), \
                patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                patch.object(E.time, "sleep"):
            broker.fetch({**self.request(origin + "/guest"), "method": "POST"})
            broker.fetch({**self.request(), "headers": {"Authorization": "guest-issued"}})
            with self.assertRaisesRegex(E.Denied, "^EGRESS_SESSION_TOKEN_DENIED$"):
                broker.fetch({**self.request("https://media.example.com/a"),
                              "headers": {"Authorization": "guest-issued"}})
        self.assertNotIn("guest-issued", json.dumps(broker.events))
        other = "https://media.example.com/a"
        with self.assertRaisesRegex(E.Denied, "EGRESS_SESSION_TOKEN_DENIED"):
            broker.fetch({**self.request(other), "headers": {"Authorization": "Bearer route-issued"}})

    def json_url_policy(self):
        configured = self.policy()
        configured.pop("responseUrls")
        endpoint = "https://reader.example.com/api/resolve.php"
        configured["responseQueries"] = [
            {"property": "apiToken", "requestUrl": configured["routes"][0]["exactUrls"][0],
             "targetUrl": endpoint},
            {"property": "jsonUrl", "requestUrl": endpoint, "jsonPath": ["url"],
             "targetOrigin": "https://media.example.com"}]
        return configured

    def test_json_media_rule_is_bound_to_approved_derived_endpoint_and_exact_target(self):
        configured = self.json_url_policy()
        frozen = copy.deepcopy(configured)
        broker = E.Routes(configured)
        endpoint = configured["responseQueries"][0]["targetUrl"] + "?token=public"
        media = "https://media.example.com/exact.m3u8?signature=PRIVATE"
        bodies = [b'<script>auth={apiToken:"public"}</script>', json.dumps({"url": media}).encode()]
        connections = []
        for body in bodies:
            connection = FakeConnection()
            connection.response.read = io.BytesIO(body).read
            connections.append(connection)
        with patch.object(E.http.client, "HTTPSConnection", side_effect=connections), \
                patch.object(E.time, "sleep"):
            broker.fetch(self.request())
            broker.fetch(self.request(endpoint))
        self.assertIn(media, broker.snapshot_urls()["https://media.example.com"])
        self.assertEqual(frozen, configured)
        self.assertEqual(frozen, broker.policy)
        self.assertNotIn("PRIVATE", json.dumps(broker.events))
        for url in ("https://other.example.com/api/resolve.php?token=public",
                    "https://reader.example.com/other?token=public"):
            self.assertEqual([], E.response_json_urls(
                url, bodies[1], configured["responseQueries"]))
        for url in (media + "&extra=1", media.replace("exact", "unapproved")):
            with self.assertRaises(E.Denied):
                broker.fetch(self.request(url))

    def test_json_media_rules_reject_unbound_endpoint_origin_and_invalid_bodies(self):
        configured = self.json_url_policy()
        for changed in ({"requestUrl": "https://other.example.com/api/resolve.php"},
                        {"requestUrl": "https://reader.example.com/unapproved"},
                        {"targetOrigin": "https://unapproved.example.com"},
                        {"jsonPath": ["url", 1]}):
            invalid = copy.deepcopy(configured)
            invalid["responseQueries"][1].update(changed)
            with self.subTest(changed=changed), self.assertRaises(E.Denied):
                E.policy_from(invalid)
        endpoint = configured["responseQueries"][1]["requestUrl"] + "?token=public"
        for body in (b"invalid", b'{"url":42}', b"[]" * 1048577):
            self.assertEqual([], E.response_json_urls(endpoint, body, configured["responseQueries"]))
        for target in ("http://media.example.com/a", "https://127.0.0.1/a",
                       "https://other.example.com/a", "https://media.example.com:443/a",
                       "https://user:secret@media.example.com/a",
                       "https://media.example.com/a#fragment", "https://media.example.com/a\r\nx"):
            broker = E.Routes(configured)
            broker.events.append({})
            for url, origin in E.response_json_urls(
                    endpoint, json.dumps({"url": target}).encode(), configured["responseQueries"]):
                broker.register_url(url, origin)
            self.assertEqual((), broker.snapshot_urls()["https://media.example.com"])

    def test_public_response_nonce_grants_only_exact_same_origin_query(self):
        configured = self.policy()
        source_url = configured["routes"][0]["exactUrls"][0]
        target = "https://reader.example.com/api/resolve.php"
        configured["responseQueries"] = [{"requestUrl": source_url, "targetUrl": target,
                                           "property": "apiToken"}]
        frozen = copy.deepcopy(configured)
        broker = E.Routes(configured)
        connection = FakeConnection()
        connection.response.read = io.BytesIO(b'<script>window.auth={apiToken:"public+/="};</script>').read
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            broker.fetch(self.request())
        exact = target + "?token=public%2B%2F%3D"
        self.assertIn(exact, broker.snapshot_urls()[configured["routes"][0]["origin"]])
        self.assertEqual(frozen, configured)
        self.assertEqual(frozen, broker.policy)
        self.assertNotIn("public", json.dumps(broker.events))
        with patch.object(E.http.client, "HTTPSConnection", return_value=FakeConnection()), \
                patch.object(E.time, "sleep"):
            broker.fetch(self.request(exact))
        for changed in (target, exact + "&extra=1", exact.replace("public", "forged")):
            with self.subTest(changed=changed), self.assertRaises(E.Denied):
                broker.fetch(self.request(changed))
        for body in (b'<div>apiToken:"outside"</div>', b'<script src="/x.js"></script>',
                     b'<script>apiToken:"first";apiToken:"second"</script>',
                     b'<script>apiToken:"bad\\nvalue"</script>'):
            self.assertEqual([], E.response_query_urls(source_url, body, configured["responseQueries"]))
        for changed in ({"requestUrl": source_url + "&changed=1"},
                        {"targetUrl": "https://other.example.com/api/resolve.php"},
                        {"targetUrl": target + "?token=preselected"},
                        {"property": "accountToken"}):
            invalid = copy.deepcopy(configured)
            invalid["responseQueries"][0].update(changed)
            with self.subTest(changed=changed), self.assertRaises(E.Denied):
                E.policy_from(invalid)

    def test_denial_shape_requires_an_exact_registered_url_with_only_the_query_separator_missing(self):
        configured = self.json_url_policy()
        target = configured["responseQueries"][0]["targetUrl"] + "?token=public"
        configured["routes"][0]["exactUrls"].append(target)
        broker = E.Routes(configured)
        request_url = target.replace("?", "", 1)
        with self.assertRaises(E.Denied) as caught:
            broker.fetch({"method": "GET", "url": request_url, "headers": {}, "body": ""})
        diagnostic = caught.exception.request_diagnostic
        self.assertEqual({
            "method": "GET",
            "requestUrlSha256": E.digest(request_url.encode("ascii")),
            "registeredUrlSha256": E.digest(target.encode("ascii")),
            "registeredQuerySeparatorMissing": True,
        }, diagnostic)
        self.assertNotIn("public", json.dumps(diagnostic))
        for changed in (request_url + "x", "https://other.example.com/" + request_url.split("/", 3)[-1]):
            with self.subTest(changed=changed):
                self.assertIsNone(E.registered_query_separator_mismatch(changed, "GET",
                                                                        configured["routes"][0]))

    def test_url_denial_diagnoses_missing_origin_and_invalid_address_without_secrets(self):
        broker = E.Routes(self.policy())
        for url, reason in (
                ("https://unknown.example.com/private?token=PRIVATE", "origin_not_registered"),
                ("https://[invalid/private?token=PRIVATE", "url_syntax"),
                ("https://reader.example.com/private?token=PRIVATE#fragment", "url_fragment")):
            with self.subTest(reason=reason), self.assertRaises(E.Denied) as caught:
                broker.fetch({"method": "GET", "url": url, "headers": {}, "body": ""})
            diagnostic = caught.exception.request_diagnostic
            self.assertEqual(reason, diagnostic["reason"])
            self.assertEqual("url", diagnostic["kind"])
            self.assertEqual(E.digest(url.encode()), diagnostic["requestUrlSha256"])
            self.assertNotIn("PRIVATE", json.dumps(diagnostic))
            self.assertNotIn("example.com", json.dumps(diagnostic))
        for url, reason in (
                ("https://reader.example.com/" + "x" * 2048, "url_length"),
                ("https://reader.example.com/\u4e2d", "url_encoding"),
                ("https://reader.example.com/private\n", "url_control")):
            self.assertEqual(reason, E.url_denial_diagnostic(url, "GET")["reason"])

    def test_response_resources_grant_exact_returned_urls_with_public_pins_and_no_tokens(self):
        configured = self.policy()
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        configured["responseApiOrigins"] = "public_https"
        configured["maxRequests"] = 6
        exact = "https://images.example.com/cover.png?sign=PRIVATE"
        broker = E.Routes(configured)
        source = FakeConnection()
        source.response.read = io.BytesIO(json.dumps({"data": [{"cover": exact}]}).encode()).read
        target = FakeConnection()
        with patch.object(E.http.client, "HTTPSConnection", side_effect=[source, target]), \
                patch.object(E, "public_dns_addresses", return_value=["8.8.8.8"]) as dns, \
                patch.object(E.time, "sleep"):
            broker.fetch(self.request())
            dns.assert_not_called()
            broker.fetch(self.request(exact))
            dns.assert_called_once_with("images.example.com", port=443, observations=unittest.mock.ANY)
        route = broker.routes["https://images.example.com"]
        self.assertNotIn("requestScope", route.policy)
        self.assertNotIn("sessionToken", route.policy)
        self.assertIn("resourceUrlObservation", broker.events[-1])
        self.assertNotIn("PRIVATE", json.dumps(broker.events))
        for url in (exact + "&extra=1", exact.replace("cover", "other")):
            with self.assertRaises(E.Denied):
                broker.fetch(self.request(url))
        with self.assertRaisesRegex(E.Denied, "EGRESS_METHOD_DENIED|EGRESS_SESSION_TOKEN_DENIED"):
            broker.fetch({**self.request(exact), "headers": {"Authorization": "imported"}})
        with self.assertRaisesRegex(E.Denied, "EGRESS_METHOD_DENIED"):
            broker.fetch({**self.request(exact), "method": "POST"})

    def test_response_resources_ignore_secret_fields_invalid_targets_and_unobserved_requests(self):
        self.assertEqual(["https://images.example.com/cover"], E.response_resource_urls(
            b'{"cover":"https://images.example.com/cover",'
            b'"token":"https://secret.example.com/x",'
            b'"bad":"https://user:PRIVATE@images.example.com/x",'
            b'"fragment":"https://images.example.com/x#private"}', "application/json"))
        self.assertEqual([], E.response_resource_urls(b'not a url https://unseen.example.com', "text/plain"))
        configured = self.policy()
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        configured["responseApiOrigins"] = "public_https"
        broker = E.Routes(configured)
        broker.resource_urls["https://private.example.com/x"] = (next(iter(broker.routes.values())), "1" * 64)
        with patch.object(E, "public_dns_addresses", side_effect=E.Denied("EGRESS_NONPUBLIC_TARGET_DENIED")), \
                self.assertRaisesRegex(E.Denied, "EGRESS_NONPUBLIC_TARGET_DENIED"):
            broker.fetch(self.request("https://private.example.com/x"))
        with self.assertRaisesRegex(E.Denied, "EGRESS_URL_DENIED"):
            broker.fetch({**self.request("https://private.example.com/x"), "method": "POST"})

    def test_response_resource_accepts_full_referer_from_registered_origin_only(self):
        configured = self.policy()
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        configured["responseApiOrigins"] = "public_https"
        configured["maxRequests"] = 6
        broker = E.Routes(configured)
        issuer = next(iter(broker.routes.values()))
        exact = "https://images.example.com/cover.png?sign=PRIVATE"
        broker.resource_urls[exact] = (issuer, "a" * 64)
        referer = issuer.policy["origin"] + "/details/123?view=poster"
        with patch.object(E.http.client, "HTTPSConnection", return_value=FakeConnection()), \
                patch.object(E, "public_dns_addresses", return_value=["8.8.8.8"]), \
                patch.object(E.time, "sleep"):
            broker.fetch({**self.request(exact), "headers": {"Referer": referer}})
        self.assertEqual([referer], broker.routes["https://images.example.com"].policy["exactReferers"])
        self.assertNotIn("PRIVATE", json.dumps(broker.events))
        for invalid in ("https://unregistered.example.com/private", referer + "#fragment"):
            with self.subTest(invalid=invalid), self.assertRaisesRegex(E.Denied, "EGRESS_HEADER_ORIGIN_DENIED"):
                broker.fetch({**self.request(exact), "headers": {"Referer": invalid}})

    def test_literal_response_decoder_observes_bridge_origin_without_executing_source(self):
        from Crypto.Cipher import AES
        from Crypto.Util.Padding import pad
        source = (b"from Crypto.Cipher import AES as Cipher\nimport base64\n"
                  b"KEY = b'0123456789abcdef'\nIV = b'fedcba9876543210'\n"
                  b"raise RuntimeError('MUST_NOT_EXECUTE')\n"
                  b"def decode(data):\n return Cipher.new(KEY, Cipher.MODE_CBC, IV).decrypt(base64.b64decode(data))\n")
        decoders = E.source_response_decoders(source)
        self.assertEqual([(b"0123456789abcdef", b"fedcba9876543210")], decoders)
        conditional = source.replace(b"from Crypto.Cipher import AES as Cipher\n",
            b"try:\n from Crypto.Cipher import AES as Cipher\nexcept ImportError:\n"
            b" from Cryptodome.Cipher import AES as Cipher\n")
        self.assertEqual(decoders, E.source_response_decoders(conditional))
        document = b'{"method":"POST","url":"https://next.example.com/api?nonce=PRIVATE","body":{}}'
        encrypted = base64.b64encode(AES.new(decoders[0][0], AES.MODE_CBC, decoders[0][1])
                                     .encrypt(pad(document, 16))).decode()
        envelope = json.dumps({"data": encrypted}).encode()
        self.assertEqual(document, E.decoded_response_json(envelope, decoders))
        self.assertEqual(["https://next.example.com"], E.response_api_origins(document, "application/json"))
        for invalid in (b'{}', b'{"data":"not base64"}', b'{"data":"AAAA"}',
                        json.dumps({"data": encrypted, "extra": True}).encode()):
            self.assertIsNone(E.decoded_response_json(invalid, decoders))
        for method in ([], {}, 1, None, "DELETE"):
            body = json.dumps({"method": method, "url": "https://next.example.com/api?nonce=PRIVATE"}).encode()
            self.assertEqual([], E.response_api_origins(body, "application/json"))
        self.assertEqual([], E.source_response_decoders(source.replace(
            b"KEY = b'0123456789abcdef'", b"KEY = '0123456789abcdef'.encode()")))

    def test_encrypted_post_response_registers_next_bridge_with_existing_budget(self):
        from Crypto.Cipher import AES
        from Crypto.Util.Padding import pad
        configured = self.policy()
        configured["methods"] = ["GET", "HEAD", "POST"]
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        configured["responseApiOrigins"] = "public_https"
        broker = E.Routes(configured)
        broker.bind_source(b"from Crypto.Cipher import AES\nimport base64\nKEY=b'0123456789abcdef'\n"
                           b"IV=b'fedcba9876543210'\ndef decode(x):\n"
                           b" return AES.new(KEY,AES.MODE_CBC,IV).decrypt(base64.b64decode(x))\n")
        target = "https://next.example.com/api?nonce=PRIVATE"
        raw = json.dumps({"method": "POST", "url": target}).encode()
        encrypted = AES.new(b"0123456789abcdef", AES.MODE_CBC, b"fedcba9876543210").encrypt(pad(raw, 16))
        first = FakeConnection()
        first.response.read = io.BytesIO(json.dumps({"data": base64.b64encode(encrypted).decode()}).encode()).read
        with patch.object(E.http.client, "HTTPSConnection", side_effect=[first, FakeConnection()]), \
                patch.object(E, "public_dns_addresses", return_value=["8.8.8.8"]), \
                patch.object(E.time, "sleep"):
            broker.fetch({**self.request(), "method": "POST"})
            broker.fetch({**self.request(target), "method": "POST"})
        self.assertEqual(2, broker.count)
        self.assertEqual("origin", broker.routes["https://next.example.com"].policy["requestScope"])
        self.assertIn("apiOriginObservation", broker.events[-1])
        self.assertNotIn("PRIVATE", json.dumps(broker.events))

    def test_api_origins_are_read_from_json_and_nuxt_data_without_running_javascript(self):
        expected = ["https://api.example.com"]
        self.assertEqual(expected, E.response_api_origins(
            b'{"config":{"apiBaseURL":"https://api.example.com/api"}}', "application/json"))
        self.assertEqual(expected, E.response_api_origins(
            b'<script id="__NUXT_DATA__" type="application/json">'
            b'[{"public":1},{"apiBaseURL":2},"https://api.example.com/api"]</script>', "text/html"))
        self.assertEqual(expected, E.response_api_origins(
            b'<script>apiBaseURL="https://api.example.com"</script>', "text/html"))
        for body in (
                b'<a href="https://api.example.com">link</a>',
                b'<script id="__NUXT_DATA__">[{"apiBaseURL":true},"https://api.example.com"]</script>',
                b'<script id="__NUXT_DATA__">[{"apiBaseURL":-1},"https://api.example.com"]</script>',
                b'<script id="__NUXT_DATA__">[{"apiBaseURL":0}]</script>',
                b'<script id="__NUXT_DATA__">[{"apiBaseURL":9}]</script>',
                b'<script id="__NUXT_DATA__">[{"apiBaseURL":"https://user:PRIVATE@api.example.com"}]</script>'):
            with self.subTest(body=body):
                self.assertEqual([], E.response_api_origins(body, "text/html"))

    def test_url_refusal_origin_fingerprint_does_not_include_path_or_credentials(self):
        origin = "https://unknown.example.com"
        expected = E.digest(origin.encode())
        for suffix in ("/first?token=PRIVATE", "/second?session=DIFFERENT"):
            row = E.url_denial_diagnostic(origin + suffix, "GET")
            self.assertEqual(expected, row["requestOriginSha256"])
            self.assertEqual("https", row["requestScheme"])
            self.assertEqual("origin_not_registered", row["reason"])
            for secret in ("unknown.example.com", "PRIVATE", "DIFFERENT", "token", "session"):
                self.assertNotIn(secret, json.dumps(row))
        for url in ("https://user:PRIVATE@unknown.example.com/private",
                    "https://[invalid/private", "//unknown.example.com/private",
                    "file:///private"):
            row = E.url_denial_diagnostic(url, "GET")
            self.assertNotIn("requestOriginSha256", row)
            self.assertNotIn("PRIVATE", json.dumps(row))
        self.assertEqual("url_scheme", E.url_denial_diagnostic("/private", "GET")["reason"])

    def test_api_origins_are_read_from_javascript_literals_without_executing_code(self):
        script = br'''
// "https://comment.example.com"
/* const ignored = "https://comment.example.net"; */
const api = "https://api.example.com";
const fallback = 'https:\/\/fallback.example.com/api';
const same = `https://api.example.com/api`;
const dynamic = `https://${account}.example.com`;
const relative = "//relative.example.com/api";
const privateUrl = "https://user:PRIVATE@private.example.com";
const requestOnly = "https://query.example.com/?token=PRIVATE";
execute("https://observed.example.com");
'''
        expected = ["https://api.example.com", "https://fallback.example.com", "https://observed.example.com"]
        for mime in ("application/javascript", "text/javascript"):
            self.assertEqual(expected, E.response_api_origins(script, mime))
        self.assertEqual([], E.response_api_origins(script, "text/plain"))
        self.assertEqual([], E.response_api_origins(b'const api=" https://trimmed.example.com"', "application/javascript"))
        self.assertEqual([], E.response_api_origins(b'const api="https://api.example.com";\xff',
                                                  "application/javascript"))
        self.assertEqual([], E.response_api_origins(
            b'/*' * 50000 + b' "https://comment.example.com"', "application/javascript"))
        self.assertEqual(["https://api.example.com"], E.response_api_origins(
            b'// ignored\rconst api="https://api.example.com"', "application/javascript"))

    def test_observed_api_origin_is_pinned_lazily_and_shares_the_existing_budget(self):
        configured = self.policy()
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        configured["responseApiOrigins"] = "public_https"
        frozen = copy.deepcopy(configured)
        broker = E.Routes(configured)
        site = configured["routes"][0]["origin"]
        document = b'const discoveredApi = "https://api.example.com/api";'

        class Response(FakeResponse):
            def getheaders(self):
                return [("Content-Type", "application/javascript")]

            def read(self, limit):
                return document

        connection = FakeConnection()
        connection.getresponse = lambda: Response()
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                patch.object(E, "public_dns_addresses", return_value=["8.8.8.8"]) as resolve, \
                patch.object(E.time, "sleep"):
            broker.fetch({"method": "GET", "url": site + "/", "headers": {}, "body": ""})
            resolve.assert_not_called()
            self.assertNotIn("https://api.example.com", broker.routes)
            broker.fetch({"method": "GET", "url": "https://api.example.com/api/list",
                          "headers": {"Referer": site + "/"}, "body": ""})
            resolve.assert_called_once_with("api.example.com", port=443, observations=unittest.mock.ANY)
        self.assertEqual(2, broker.count)
        self.assertEqual(frozen, configured)
        self.assertEqual(frozen, broker.policy)
        self.assertEqual(["8.8.8.8"], broker.routes["https://api.example.com"].policy["pinnedIpv4"])
        self.assertIn("apiOriginObservation", broker.events[-1])
        self.assertNotIn("api.example.com", json.dumps(broker.events))
        with self.assertRaises(E.Denied):
            broker.fetch({"method": "GET", "url": "https://unseen.example.com/list",
                          "headers": {}, "body": ""})

    def test_javascript_regex_literals_do_not_hide_following_endpoint_data(self):
        script = br'''
const os=/^(Win32|Linux)/i;
if(/CriOS\/|FxiOS\/|OPiOS\//.test(agent)) {}
const quote=/["'`]/g;
const hidden=/https:\/\/regex.example.com\/["']/;
const divided = value / 2;
const after = (value + 1) / 3;
if (ready) /["']/.test(value);
const api="https://api.example.com";
const next=first++ / 2;
const second='https://second.example.com';
'''
        self.assertEqual(["https://api.example.com", "https://second.example.com"],
                         E.response_api_origins(script, "application/javascript"))
        self.assertEqual([], E.response_api_origins(
            br'const broken=/["https://not-observed.example.com"',
            "application/javascript"))

    def test_javascript_static_json_endpoint_arrays_are_data_not_evaluated_code(self):
        script = br'''
const endpoints=JSON.parse('["https://first.example.com","https://api.example.com/api"]');
const escaped=JSON.parse("[\"https:\\/\\/escaped.example.com/api\"]");
const object='{"routes":["https://object.example.com"],"nested":{"api":"https://nested.example.com"}}';
const template=`["https://template.example.com"]`;
const bad='["https://user:PRIVATE@private.example.com","https://query.example.com/?token=PRIVATE"]';
const dynamic=`["https://${account}.example.com"]`;
const malformed='["https://broken.example.com"';
const expression='["https://expression.example.com"] + execute()';
/* JSON.parse('["https://comment.example.com"]') */
'''
        self.assertEqual([
            "https://first.example.com", "https://api.example.com",
            "https://escaped.example.com", "https://object.example.com",
            "https://nested.example.com", "https://template.example.com",
        ], E.response_api_origins(script, "application/javascript"))

    def test_exact_referer_is_preserved_only_on_its_approved_route(self):
        configured = self.policy()
        configured["routes"][1]["exactUrls"] = ["https://media.example.com/media"]
        broker = E.Routes(configured)
        referer = configured["routes"][0]["exactReferers"][0]
        value = {**self.request(), "headers": {"Referer": referer}}
        with patch.object(E.http.client, "HTTPSConnection", return_value=FakeConnection()):
            broker.fetch(value)
        with self.assertRaisesRegex(E.Denied, "EGRESS_HEADER_ORIGIN_DENIED"):
            broker.fetch({**value, "url": "https://media.example.com/media"})

    def test_origin_routes_preserve_registered_referers_beyond_static_header_limit(self):
        configured = self.policy()
        configured.pop("responseUrls")
        configured["maxRequests"] = 8
        for index in range(6):
            configured["routes"].append({
                "origin": "https://site" + str(index) + ".example.com", "exactUrls": [],
                "requestScope": "origin", "pinnedIpv4": ["8.8.8.8"]})
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        frozen = copy.deepcopy(configured)
        broker = E.Routes(configured)
        referer = configured["routes"][-1]["origin"] + "/player/episode?id=public"
        connection = FakeConnection()
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                patch.object(E.time, "sleep"):
            for route in configured["routes"]:
                broker.fetch(self.request(route["origin"] + "/api/list") | {"headers": {"Referer": referer}})
                self.assertEqual(referer, connection.sent[3]["Referer"])
        self.assertEqual(8, broker.count)
        self.assertEqual(frozen, configured)
        self.assertEqual(frozen, broker.policy)
        self.assertNotIn("session_tokens", json.dumps(broker.events))
        self.assertTrue(all(not route.session_tokens for route in broker.routes.values()))

    def test_registered_referer_does_not_grant_origins_tokens_or_exact_route_access(self):
        configured = self.policy()
        configured.pop("responseUrls")
        configured["routes"][0]["requestScope"] = "origin"
        configured["routes"].append({
            "origin": "https://third.example.com", "exactUrls": [],
            "requestScope": "origin", "pinnedIpv4": ["8.8.8.8"]})
        broker = E.Routes(configured)
        for headers, code in (
            ({"Referer": "https://third.example.com/episode"}, None),
            ({"Referer": "https://media.example.com/episode"}, "EGRESS_HEADER_ORIGIN_DENIED"),
            ({"Referer": "https://unregistered.example.com/episode"}, "EGRESS_HEADER_ORIGIN_DENIED"),
            ({"Referer": "https://127.0.0.1/episode"}, "EGRESS_HEADER_ORIGIN_DENIED"),
            ({"Referer": "http://third.example.com/episode"}, "EGRESS_HEADER_ORIGIN_DENIED"),
            ({"Referer": "https://third.example.com:443/episode"}, "EGRESS_HEADER_ORIGIN_DENIED"),
            ({"Referer": "https://third.example.com/episode#part"}, "EGRESS_HEADER_ORIGIN_DENIED"),
            ({"Origin": "https://third.example.com"}, "EGRESS_HEADER_ORIGIN_DENIED"),
            ({"Authorization": "PRIVATE"}, "EGRESS_SESSION_TOKEN_DENIED"),
        ):
            value = self.request() | {"headers": headers}
            with self.subTest(headers=headers):
                if code:
                    with self.assertRaisesRegex(E.Denied, "^" + code + "$"):
                        broker.fetch(value)
                else:
                    with patch.object(E.http.client, "HTTPSConnection", return_value=FakeConnection()):
                        broker.fetch(value)
        self.assertEqual(1, broker.count)

    def test_discovered_urls_keep_exact_bytes_and_never_trim_into_permission(self):
        configured = self.policy()
        broker = E.Routes(configured)
        origin = configured["routes"][0]["origin"]
        broker.events.append({})
        exact = origin + "/MiXeD?ID=AbC"
        broker.register_url(exact, origin)
        self.assertIn(exact, broker.snapshot_urls()[origin])
        altered = origin + "/unreviewed?id=2 "
        broker.register_url(altered, origin)
        self.assertNotIn(altered, broker.snapshot_urls()[origin])
        self.assertNotIn(altered.strip(), broker.snapshot_urls()[origin])
        self.assertEqual([E.digest(exact.encode())], broker.events[-1]["registeredUrlSha256"])

    def policy(self):
        return {"schemaVersion": 3, **{key: policy()[key] for key in E.LIMITS},
                "routes": [
                    {"origin": "https://reader.example.com",
                     "exactUrls": ["https://reader.example.com/detail?id=1"], "pinnedIpv4": ["8.8.8.8"],
                     "exactReferers": ["https://reader.example.com/player/episode.html"]},
                    {"origin": "https://media.example.com", "exactUrls": [], "pinnedIpv4": ["1.1.1.1"]}],
                "responseUrls": [{"requestUrl": "https://reader.example.com/detail?id=1",
                                  "jsonPath": ["data", "hls_url"], "targetOrigin": "https://media.example.com"}]}

    def request(self, url="https://reader.example.com/detail?id=1"):
        return {"method": "GET", "url": url, "headers": {}, "body": ""}

    def test_public_assets_share_budget_without_granting_spider_urls_or_cross_origin_access(self):
        configured = self.policy()
        root = "https://reader.example.com/"
        configured["routes"][0]["exactUrls"].append(root)
        configured["resourceEvidence"] = {"entryUrls": [root]}
        broker = E.mediator(configured)
        asset = root + "assets/image.js"
        snapshot = broker.snapshot_urls()
        with patch.object(E.http.client, "HTTPSConnection", side_effect=lambda *a, **kw: FakeConnection()), \
                patch.object(E.time, "sleep"):
            broker.fetch_resource(root)
            broker.fetch_resource(asset, root)
        self.assertEqual(2, broker.count)
        self.assertEqual(snapshot, broker.snapshot_urls())
        self.assertEqual(E.digest(asset.encode()), broker.events[-1]["requestUrlSha256"])
        for url, parent in ((asset, None), (asset, root + "unread.js"),
                            ("https://media.example.com/foreign.js", root),
                            (root + "api/private", root), ("https://127.0.0.1/a.js", root)):
            with self.subTest(url=url, parent=parent), self.assertRaises(E.Denied):
                broker.fetch_resource(url, parent)
        with self.assertRaisesRegex(E.Denied, "EGRESS_URL_DENIED"):
            broker.fetch(self.request(asset))
        for setting in ({"entryUrls": [asset]}, {"entryUrls": [[]]}, {"entryUrls": [root], "extra": True}):
            with self.subTest(setting=setting), self.assertRaisesRegex(E.Denied, "EGRESS_POLICY_INVALID"):
                E.policy_from({**configured, "resourceEvidence": setting})

    def test_observed_text_cache_is_job_local_bounded_and_does_not_refetch(self):
        configured = self.policy()
        configured["routes"][0]["requestScope"] = "origin"
        configured["maxRequests"] = 8
        broker = E.mediator(configured)
        url = self.request()["url"]
        with patch.object(E.http.client, "HTTPSConnection", side_effect=lambda *a, **kw: FakeConnection()), \
                patch.object(E.time, "sleep"):
            for index in range(6):
                broker.fetch(self.request(url + "&page=" + str(index)))
        snapshot = broker.snapshot_resources()
        self.assertEqual(6, broker.count)
        self.assertEqual([url + "&page=" + str(i) for i in (5, 4, 3, 2)], [row[0] for row in snapshot])
        self.assertEqual(6, len(broker.events))
        snapshot[0][1]["body"] = "changed"
        self.assertNotEqual("changed", broker.snapshot_resources()[0][1]["body"])
        self.assertEqual([], E.mediator(configured).snapshot_resources())
        # A previously observed page may supply same-origin scripts, not arbitrary URLs.
        with patch.object(E.http.client, "HTTPSConnection", side_effect=lambda *a, **kw: FakeConnection()), \
                patch.object(E.time, "sleep"):
            broker.fetch_resource("https://reader.example.com/app.js", url + "&page=5")
        self.assertEqual(7, broker.count)
        with self.assertRaises(E.Denied):
            broker.fetch_resource("https://media.example.com/app.js", url + "&page=5")

    def test_binary_response_is_not_cached_as_page_evidence(self):
        configured = self.policy()
        broker = E.mediator(configured)
        connection = FakeConnection()
        connection.response.getheader = lambda key, default=None: (
            "image/jpeg" if key == "Content-Type" else default)
        connection.response.getheaders = lambda: [("Content-Type", "image/jpeg")]
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            broker.fetch(self.request())
        self.assertEqual([], broker.snapshot_resources())

    def test_empty_mimeless_http_events_keep_exact_call_intervals_without_caching_or_refetching(self):
        configured = self.policy()
        configured["maxRequests"] = 6
        broker = E.mediator(configured)
        connection = FakeConnection()
        connection.response.status = 444
        connection.response.read = lambda limit: b""
        connection.response.getheaders = lambda: []
        connection.response.getheader = lambda key, default=None: default
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                patch.object(E.time, "sleep"):
            for ident in (3, 4):
                call = {"id": ident, "method": "category", "inputSha256": str(ident) * 64}
                broker.set_source_call(call)
                for _ in range(2):
                    response = broker.fetch(self.request())
                    self.assertNotIn("observedDuringCall", response)
                call["method"] = "changed"
            broker.set_source_call(None)
            broker.fetch(self.request())
        self.assertEqual(5, broker.count)
        self.assertEqual([], broker.snapshot_resources())
        for row, ident in zip(broker.events[:4], (3, 3, 4, 4)):
            self.assertEqual({"id": ident, "method": "category", "inputSha256": str(ident) * 64},
                             row["observedDuringCall"])
            self.assertEqual((444, 0, "", True), (row["responseStatus"], row["responseBytes"],
                             row["responseContentType"], row["responseComplete"]))
        self.assertNotIn("observedDuringCall", broker.events[4])

    def test_post_json_cache_keeps_same_session_response_without_replaying_or_granting_urls(self):
        for status, mime in ((200, "application/json"), (403, "application/problem+json"), (500, "text/plain")):
            with self.subTest(status=status):
                broker = E.mediator(self.policy())
                action = {"id": 7, "method": "play", "inputSha256": "a" * 64}
                broker.set_source_call(action)
                body = b'{"code":401,"data":{"hls_url":"https://media.example.com/private.m3u8"}}'
                connection = FakeConnection()
                connection.response.status = status
                connection.response.read = io.BytesIO(body).read
                connection.response.getheaders = lambda: [("Content-Type", mime)]
                connection.response.getheader = lambda key, default=None: mime if key == "Content-Type" else default
                with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
                    broker.fetch({**self.request(), "method": "POST"})
                row = broker.snapshot_resources()[0][1]
                self.assertEqual("POST", row["requestMethod"])
                self.assertEqual(action, row["observedDuringCall"])
                self.assertEqual(body, base64.b64decode(row["body"]))
                self.assertEqual(1, broker.count)
                self.assertEqual(1, len(broker.events))
                self.assertFalse(broker.resource_documents)
                self.assertFalse(broker.routes["https://media.example.com"].policy["exactUrls"])
                self.assertNotIn("registeredUrlSha256", broker.events[0])
                with self.assertRaisesRegex(E.Denied, "EGRESS_URL_DENIED"):
                    broker.fetch_resource("https://reader.example.com/private.js", self.request()["url"])

    def test_post_html_and_incomplete_json_do_not_enter_response_cache(self):
        for body, mime in ((b"<html>private</html>", "text/html"), (b"x" * 600, "application/json")):
            broker = E.mediator(self.policy())
            connection = FakeConnection()
            connection.response.read = io.BytesIO(body).read
            connection.response.getheaders = lambda: [("Content-Type", mime)]
            with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
                if len(body) > 512:
                    with self.assertRaisesRegex(E.Denied, "EGRESS_BYTE_BUDGET"):
                        broker.fetch({**self.request(), "method": "POST"})
                else:
                    broker.fetch({**self.request(), "method": "POST"})
            self.assertEqual([], broker.snapshot_resources())

    def test_non_success_text_cache_keeps_host_action_without_granting_links(self):
        configured = self.policy()
        configured["responseApiOrigins"] = "public_https"
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        broker = E.mediator(configured)
        action = {"id": 4, "method": "detail", "inputSha256": "a" * 64}
        broker.set_source_call(action)
        connection = FakeConnection()
        connection.response.status = 403
        body = b'<html>Access denied<script src="/private.js"></script></html>'
        connection.response.read = io.BytesIO(body).read
        connection.response.getheaders = lambda: [("Content-Type", "text/html")]
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            response = broker.fetch(self.request())
        self.assertNotIn("observedDuringCall", response)
        broker.set_source_call(None)
        action["method"] = "changed"
        snapshot = broker.snapshot_resources()
        self.assertEqual(403, snapshot[0][1]["status"])
        self.assertEqual("detail", snapshot[0][1]["observedDuringCall"]["method"])
        self.assertEqual(body, base64.b64decode(snapshot[0][1]["body"]))
        self.assertEqual(1, broker.count)
        self.assertNotIn(self.request()["url"], broker.resource_documents)
        self.assertFalse(broker.api_origins)
        with self.assertRaisesRegex(E.Denied, "EGRESS_URL_DENIED"):
            broker.fetch_resource("https://reader.example.com/private.js", self.request()["url"])

    def test_response_field_grants_only_exact_url_and_does_not_mutate_frozen_policy(self):
        configured = self.policy()
        frozen = copy.deepcopy(configured)
        broker = E.mediator(configured)
        media = "https://media.example.com/one.m3u8?signature=PRIVATE"
        with self.assertRaisesRegex(E.Denied, "EGRESS_URL_DENIED"):
            broker.fetch(self.request(media))
        connection = FakeConnection()
        connection.response.read = io.BytesIO(json.dumps({"data": {"hls_url": media}}).encode()).read
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            broker.fetch(self.request())
        self.assertEqual(frozen, broker.policy)
        self.assertEqual(frozen, configured)
        self.assertEqual([E.digest(media.encode())], broker.events[0]["registeredUrlSha256"])
        self.assertNotIn("PRIVATE", json.dumps(broker.events))
        with patch.object(E.http.client, "HTTPSConnection", return_value=FakeConnection()), \
                patch.object(E.time, "sleep"):
            broker.fetch(self.request(media))
        self.assertEqual(2, broker.count)
        self.assertEqual(2, len(broker.events))
        for target in (media + "&extra=1", media.replace("/one.", "/two.")):
            with self.subTest(target=target), self.assertRaisesRegex(E.Denied, "EGRESS_URL_DENIED"):
                broker.fetch(self.request(target))

    def test_unapproved_origins_invalid_fields_and_other_responses_never_grant_urls(self):
        for target in ("http://media.example.com/a", "https://127.0.0.1/a", "https://other.example.com/a",
                       "https://media.example.com:443/a", "https://user:secret@media.example.com/a",
                       "https://media.example.com/a#fragment", "https://media.example.com/a\r\nx", 12):
            with self.subTest(target=target):
                broker = E.mediator(self.policy())
                connection = FakeConnection()
                connection.response.read = io.BytesIO(json.dumps({"data": {"hls_url": target}}).encode()).read
                with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
                    broker.fetch(self.request())
                self.assertEqual([], broker.routes["https://media.example.com"].policy["exactUrls"])
                self.assertNotIn("registeredUrlSha256", broker.events[0])
        for body in (b"invalid", b'{"hls_url":"https://media.example.com/a"}'):
            broker = E.mediator(self.policy())
            connection = FakeConnection()
            connection.response.read = io.BytesIO(body).read
            with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
                broker.fetch(self.request())
            self.assertNotIn("registeredUrlSha256", broker.events[0])
        broker = E.mediator(self.policy())
        broker.events.append({})
        broker.register_response_urls("https://reader.example.com/other", {
            "body": base64.b64encode(b'{"data":{"hls_url":"https://media.example.com/a"}}').decode()})
        self.assertEqual([{}], broker.events)

    def test_origins_share_request_byte_and_interval_limits_in_chronological_order(self):
        configured = self.policy()
        configured["routes"][1]["exactUrls"] = ["https://media.example.com/one"]
        configured["maxRequests"] = 2
        broker = E.mediator(configured)
        with patch.object(E.http.client, "HTTPSConnection", side_effect=lambda *a, **k: FakeConnection()), \
                patch.object(E.time, "monotonic", return_value=100), patch.object(E.time, "sleep") as sleep:
            broker.fetch(self.request())
            broker.fetch(self.request("https://media.example.com/one"))
            with self.assertRaisesRegex(E.Denied, "EGRESS_REQUEST_BUDGET"):
                broker.fetch(self.request())
        self.assertEqual([0, 1], [call.args[0] for call in sleep.call_args_list])
        self.assertEqual([E.digest(b"/detail"), E.digest(b"/one")],
                         [row["pathSha256"] for row in broker.events])
        configured["maxTotalResponseBytes"] = len(b'{"data":[]}')
        broker = E.mediator(configured)
        with patch.object(E.http.client, "HTTPSConnection", return_value=FakeConnection()) as connected:
            broker.fetch(self.request())
            with self.assertRaisesRegex(E.Denied, "EGRESS_BYTE_BUDGET"):
                broker.fetch(self.request("https://media.example.com/one"))
            self.assertEqual(1, connected.call_count)

    def test_route_and_response_rules_cannot_expand_methods_dns_or_unknown_origins(self):
        changes = [
            {"routes": self.policy()["routes"] * 2},
            {"responseUrls": [{"requestUrl": "https://reader.example.com/other",
                               "jsonPath": ["url"], "targetOrigin": "https://media.example.com"}]},
            {"responseUrls": [{"requestUrl": "https://reader.example.com/detail?id=1",
                               "jsonPath": ["url"], "targetOrigin": "https://other.example.com"}]},
            {"responseUrls": [{"requestUrl": "https://reader.example.com/detail?id=1",
                               "jsonPath": ["data", "../url"], "targetOrigin": "https://media.example.com"}]},
        ]
        for field, value in (("methods", ["POST"]), ("pinnedIpv4", ["127.0.0.1"]),
                             ("exactUrls", ["https://other.example.com/a"])):
            routes = copy.deepcopy(self.policy()["routes"])
            routes[0][field] = value
            changes.append({"routes": routes})
        routes = copy.deepcopy(self.policy()["routes"])
        routes[0]["queryKeys"] = ["token"]
        changes.append({"routes": routes})
        for change in changes:
            with self.subTest(change=change), self.assertRaisesRegex(E.Denied, "EGRESS_POLICY_INVALID"):
                E.policy_from({**self.policy(), **change})

    def test_hls_grants_only_selected_same_origin_resources_with_fixed_shared_budgets(self):
        configured = self.policy()
        configured["hlsOrigins"] = ["https://media.example.com"]
        configured["maxRequests"] = 12
        configured["maxResponseBytes"] = 2048
        configured["maxTotalResponseBytes"] = 4096
        master = "https://media.example.com/master.m3u8?signature=PRIVATE"
        configured["routes"][1]["exactUrls"] = [master]
        frozen = copy.deepcopy(configured)
        broker = E.mediator(configured)
        connection = FakeConnection()
        connection.response.read = io.BytesIO(
            b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nselected.m3u8\n"
            b"#EXT-X-STREAM-INF:BANDWIDTH=2\nother.m3u8\n").read
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            broker.fetch(self.request(master))
        urls = broker.routes["https://media.example.com"].policy["exactUrls"]
        self.assertIn("https://media.example.com/selected.m3u8", urls)
        self.assertNotIn("https://media.example.com/other.m3u8", urls)
        body = b"#EXTM3U\n#EXT-X-TARGETDURATION:6\n" + b"".join(
            b"#EXTINF:6,\n" + str(i).encode() + b".ts\n" for i in range(7)) + b"#EXT-X-ENDLIST\n"
        connection.response.read = io.BytesIO(body).read
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), patch.object(E.time, "sleep"):
            broker.fetch(self.request("https://media.example.com/selected.m3u8"))
        self.assertEqual({master, "https://media.example.com/selected.m3u8",
                          *("https://media.example.com/" + str(i) + ".ts" for i in (0, 1, 2, 3, 6))}, set(urls))
        self.assertEqual(2, broker.count)
        self.assertEqual(frozen, configured)
        self.assertEqual(frozen, broker.policy)
        self.assertNotIn("PRIVATE", json.dumps(broker.events))
        for url in ("https://media.example.com/4.ts", "https://media.example.com/0.ts?extra=1",
                    "https://other.example.com/0.ts", "http://127.0.0.1/0.ts"):
            with self.subTest(url=url), self.assertRaises(E.Denied), \
                    patch.object(E.http.client, "HTTPSConnection") as connect:
                broker.fetch(self.request(url))
            connect.assert_not_called()

    def test_hls_never_grants_from_incomplete_error_or_unapproved_responses(self):
        url = "https://media.example.com/master.m3u8"
        body = (b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nselected.m3u8\n"
                b"#EXT-X-STREAM-INF:BANDWIDTH=2\nhttps://127.0.0.1/private\n")
        for enabled, status, ranged, complete in (
                (False, 200, False, True), (True, 502, False, True),
                (True, 206, True, False), (True, 200, True, False)):
            configured = self.policy()
            if enabled:
                configured["hlsOrigins"] = ["https://media.example.com"]
            configured["routes"][1]["exactUrls"] = [url]
            broker = E.mediator(configured)
            broker.events.append({"responseComplete": True, "rangeRequested": ranged,
                                  "resourceComplete": complete})
            response = {"status": status, "body": base64.b64encode(body).decode(), "headers": {}}
            broker.register_playlist_urls(url, response)
            self.assertEqual([url], broker.routes["https://media.example.com"].policy["exactUrls"])
        for target in ("http://media.example.com/a", "https://127.0.0.1/a",
                       "https://other.example.com/a", "https://media.example.com:443/a"):
            configured = self.policy()
            configured["hlsOrigins"] = ["https://media.example.com"]
            configured["routes"][1]["exactUrls"] = [url]
            broker = E.mediator(configured)
            broker.events.append({"responseComplete": True})
            response = {"status": 200, "headers": {}, "body": base64.b64encode(
                b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n" + target.encode() + b"\n").decode()}
            broker.register_playlist_urls(url, response)
            self.assertEqual([url], broker.routes["https://media.example.com"].policy["exactUrls"])

    def test_hls_selected_segments_can_use_a_separately_pinned_cdn(self):
        configured = self.policy()
        master = "https://media.example.com/list.m3u8"
        configured["routes"][1]["exactUrls"] = [master]
        configured["routes"].append({
            "origin": "https://cdn.example.com", "exactUrls": [], "pinnedIpv4": ["8.8.8.8"]})
        configured["hlsOrigins"] = ["https://media.example.com", "https://cdn.example.com"]
        broker = E.mediator(configured)
        body = (b"#EXTM3U\n#EXT-X-TARGETDURATION:6\n"
                b"#EXTINF:6,\nhttps://cdn.example.com/first.png\n"
                b"#EXTINF:6,\nhttps://other.example.com/second.ts\n"
                b"#EXTINF:6,\nhttps://127.0.0.1/private\n#EXT-X-ENDLIST\n")
        broker.events.append({"responseComplete": True})
        broker.register_playlist_urls(master, {
            "status": 200, "body": base64.b64encode(body).decode()})
        self.assertEqual(["https://cdn.example.com/first.png"],
                         broker.routes["https://cdn.example.com"].policy["exactUrls"])
        self.assertEqual(["8.8.8.8"], broker.routes["https://cdn.example.com"].policy["pinnedIpv4"])
        for target in ("https://cdn.example.com/other.png", "https://other.example.com/second.ts",
                       "https://127.0.0.1/private"):
            with self.subTest(target=target), self.assertRaises(E.Denied), \
                    patch.object(E.http.client, "HTTPSConnection") as connect:
                broker.fetch(self.request(target))
            connect.assert_not_called()
    def test_hls_opt_in_cannot_add_origins_or_change_dns_and_methods(self):
        for origins in (None, "https://media.example.com", ["https://other.example.com"],
                        ["https://media.example.com"] * 2, ["http://127.0.0.1"]):
            with self.subTest(origins=origins), self.assertRaisesRegex(E.Denied, "EGRESS_POLICY_INVALID"):
                E.policy_from({**self.policy(), "hlsOrigins": origins})

    def test_url_snapshot_copies_only_routes_and_preserves_frozen_authorization(self):
        configured = self.policy()
        first = E.mediator(configured)
        first.events.append({})
        media = "https://media.example.com/one.m3u8?signature=PRIVATE"
        first.register_url(media, "https://media.example.com")
        first.count, first.total, first.next_request = 2, 100, 101
        snapshot = first.snapshot_urls()
        first.register_url("https://media.example.com/later", "https://media.example.com")
        second = E.mediator(configured)
        second.restore_urls(snapshot)
        self.assertEqual((media,), snapshot["https://media.example.com"])
        self.assertEqual([media], second.routes["https://media.example.com"].policy["exactUrls"])
        self.assertEqual((0, 0, 0, []), (second.count, second.total, second.next_request, second.events))
        self.assertEqual(configured, second.policy)
        self.assertEqual(["1.1.1.1"], second.routes["https://media.example.com"].policy["pinnedIpv4"])
        self.assertEqual(["GET", "HEAD", "POST"], second.routes["https://media.example.com"].policy["methods"])
        self.assertEqual(1, len(configured["routes"][0]["exactUrls"]))
        self.assertEqual([], configured["routes"][1]["exactUrls"])

    def test_restore_rejects_changed_origins_frozen_urls_and_route_limits_atomically(self):
        broker = E.mediator(self.policy())
        original = broker.snapshot_urls()
        invalid = [
            {**original, "https://other.example.com": ()},
            {"https://reader.example.com": original["https://reader.example.com"]},
            {**original, "https://reader.example.com": ()},
            {**original, "https://media.example.com": ["https://media.example.com/a"]},
            {**original, "https://media.example.com": ("https://other.example.com/a",)},
            {**original, "https://media.example.com": tuple(
                "https://media.example.com/" + str(i) for i in range(17))},
        ]
        for snapshot in invalid:
            with self.subTest(snapshot=snapshot), self.assertRaisesRegex(E.Denied, "EGRESS_POLICY_INVALID"):
                broker.restore_urls(snapshot)
            self.assertEqual(original, broker.snapshot_urls())


class ExchangeTest(unittest.TestCase):
    def test_jsonl_failure_context_reaches_loader_without_the_private_message(self):
        class Broker:
            events = []
        tail = (
            "print(json.dumps({'kind':'loader_available'}),flush=True)\n"
            "call=json.loads(sys.stdin.buffer.readline())\n"
            "print(json.dumps({'id':str(call['id']),'ok':False,'error':"
            "{'code':'OSError','message':'PRIVATE_TOKEN','context':{'sourceLine':27,'errno':38}}}),flush=True)\n"
            "assert json.loads(sys.stdin.buffer.readline())=={'kind':'finish'}\n"
            "print(json.dumps({'kind':'result','summary':{'status':'source_exception'}}),flush=True)\n")
        receipt = self.call(self.child("\n" + tail), Broker())["loaderValidation"]
        self.assertEqual({"sourceLine": 27, "errno": 38}, receipt["nodes"][0]["failureContext"])
        self.assertNotIn("PRIVATE_TOKEN", json.dumps(receipt))

    def test_jsonl_failure_retains_exception_type_but_discards_the_message(self):
        class Broker:
            events = []
        tail = (
            "print(json.dumps({'kind':'loader_available'}),flush=True)\n"
            "call=json.loads(sys.stdin.buffer.readline())\n"
            "print(json.dumps({'id':str(call['id']),'ok':False,'error':"
            "{'code':'ModuleNotFoundError','message':'PRIVATE_TOKEN'}}),flush=True)\n"
            "assert json.loads(sys.stdin.buffer.readline())=={'kind':'finish'}\n"
            "print(json.dumps({'kind':'result','summary':{'status':'source_exception'}}),flush=True)\n")
        receipt = self.call(self.child("\n" + tail), Broker())["loaderValidation"]
        self.assertEqual("ModuleNotFoundError", receipt["nodes"][0]["failureType"])
        self.assertNotIn("PRIVATE_TOKEN", json.dumps(receipt))

    def test_network_denial_is_bound_to_the_host_issued_action_not_source_summary(self):
        from types import SimpleNamespace
        loader = SimpleNamespace(awaiting={"method": "home"})
        self.assertEqual({"denialIndex": 1, "method": "home"}, P.network_failure_call(
            ["EGRESS_HTTP_FAILED", "EGRESS_NONPUBLIC_DNS"], loader))
        self.assertIsNone(P.network_failure_call(["EGRESS_URL_DENIED"], loader))
        self.assertIsNone(P.network_failure_call(["EGRESS_HTTP_FAILED"], None))
        self.assertIsNone(P.network_failure_call(["EGRESS_HTTP_FAILED"], SimpleNamespace(awaiting=None)))
        loader.awaiting = {"method": "destroy"}
        self.assertIsNone(P.network_failure_call(["EGRESS_HTTP_FAILED"], loader))

    def test_prior_header_refusals_keep_request_identity_after_a_later_success(self):
        class Broker:
            events = []
            def fetch(self, value):
                E.validate_request(value, policy())
                return {"status": 200, "headers": {}, "body": ""}

        values = [
            {**request(), "headers": {name: "https://frontend.example.com/?token=PRIVATE"}}
            for name in ("Origin", "Referer")]
        values.append(request())
        tail = (
            "for index,request in enumerate(" + repr(values) + ",1):\n"
            " print(json.dumps({'kind':'fetch','id':index,'request':request}),flush=True)\n"
            " reply=json.loads(sys.stdin.buffer.readline())\n"
            " assert reply['ok']==(index==3)\n"
            "sys.exit(1)\n")
        with self.assertRaises(P.S.Failure) as caught:
            self.call(self.child("\n" + tail), Broker())
        context = caught.exception.exchange_context
        self.assertFalse(context["lastFetchDenied"])
        self.assertNotIn("requestDiagnostic", context)
        self.assertEqual([1, 2], [row["httpId"] for row in context["denialObservations"]])
        self.assertEqual(["origin", "referer"], [
            row["requestDiagnostic"]["headerName"] for row in context["denialObservations"]])
        self.assertTrue(all(row["code"] == "EGRESS_HEADER_ORIGIN_DENIED"
                            for row in context["denialObservations"]))
        self.assertNotIn("PRIVATE", json.dumps(context))
        self.assertNotIn("frontend.example.com", json.dumps(context))

    def test_protocol_failure_keeps_completed_http_summaries_and_header_refusal_not_values(self):
        class Broker:
            events = [{
                "method": "POST", "requestUrlSha256": "a" * 64, "responseStatus": 200,
                "responseBytes": 12, "responseSha256": "b" * 64, "responseComplete": True,
                "responseContentType": "application/json", "headers": "PRIVATE",
                "body": "PRIVATE", "url": "https://PRIVATE.example.com",
                "transport": "external_evidence_gateway", "representation": "gateway_rendered_document",
                "gatewaySessionReused": True, "cookiesExported": False,
            }]

            def fetch(self, value):
                E.require_header(False, "header_not_allowed", "X-App-Unknown")

        tail = ("print(json.dumps({'kind':'fetch','id':1,'request':{}}),flush=True); "
                "sys.stdin.buffer.readline(); sys.exit(1)")
        with self.assertRaises(P.S.Failure) as caught:
            self.call(self.child(tail), Broker())
        context = caught.exception.exchange_context
        self.assertEqual(1, context["completedHttpCount"])
        self.assertEqual([{
            key: val for key, val in Broker.events[0].items()
            if key not in {"headers", "body", "url"}}], context["completedHttpObservations"])
        self.assertEqual("x-app-unknown", context["requestDiagnostic"]["headerName"])
        self.assertNotIn("PRIVATE", json.dumps(context))

    def test_protocol_failure_preserves_byte_budget_diagnostics(self):
        class Broker:
            events = []

            def fetch(self, value):
                raise E.byte_budget_error(policy(), 900, 124, 513, "content_length", "total_response_bytes")

        tail = ("print(json.dumps({'kind':'fetch','id':1,'request':{}}),flush=True); "
                "sys.stdin.buffer.readline(); sys.exit(1)")
        with self.assertRaises(P.S.Failure) as caught:
            self.call(self.child(tail), Broker())
        context = caught.exception.exchange_context
        self.assertEqual("EGRESS_BYTE_BUDGET", context["lastDeniedCode"])
        self.assertEqual("total_response_bytes", context["requestDiagnostic"]["reason"])
        self.assertEqual(124, context["denialObservations"][0]["requestDiagnostic"]["effectiveResponseLimitBytes"])

    def test_exchange_accepts_more_than_24_sequential_fetch_frames_but_stays_bounded(self):
        class Broker:
            events = []
            count = 0
            def fetch(self, request):
                self.count += 1
                return {"status": 200, "body": "", "headers": {}}
        tail = (
            "for index in range(1,COUNT+1):\n"
            " print(json.dumps({'kind':'fetch','id':index,'request':{}}),flush=True)\n"
            " assert json.loads(sys.stdin.buffer.readline())['ok']\n"
            "print(json.dumps({'kind':'result','summary':{'status':'init_returned'}}),flush=True)\n")
        broker = Broker()
        self.call(self.child("COUNT=32\n" + tail), broker)
        self.assertEqual(32, broker.count)
        with self.assertRaisesRegex(P.S.Failure, "EGRESS_PROTOCOL_INVALID"):
            self.call(self.child("COUNT=65\n" + tail), Broker())

    def call(self, program, broker=None, timeout=2):
        return P.exchange([sys.executable, "-I", "-c", program], F.request(), F.SOURCE,
                          HASHES, broker, timeout=timeout)

    def child(self, tail, pre=None):
        row = ready() if pre is None else pre
        return ("import sys,json,time; h=json.loads(sys.stdin.buffer.readline()); "
                "print(" + repr(json.dumps(row)) + ",flush=True); "
                "ack=json.loads(sys.stdin.buffer.readline()); "
                "body=sys.stdin.buffer.read(h['sourceBytes']+1); " + tail)

    def test_broker_exchange_relays_validated_response_and_projects_only_safe_result(self):
        class Broker:
            events = []
            def fetch(self, value):
                E.validate_request(value, policy())
                return {"status": 200, "body": "", "headers": {}}
        fetch = {"kind": "fetch", "id": 1, "request": request()}
        final = {"kind": "result", "summary": {"status": "detail_returned", "homeClasses": 1,
                                              "categoryItems": 2, "detailItems": 1, "playGroups": 1,
                                              "secret": "PRIVATE"}}
        tail = ("print(" + repr(json.dumps(fetch)) + ",flush=True); "
                "r=json.loads(sys.stdin.buffer.readline()); assert r['ok']; "
                "print(" + repr(json.dumps(final)) + ",flush=True)")
        result = self.call(self.child(tail), Broker())
        self.assertEqual("detail_returned", result["untrustedRuntimeSummary"]["status"])
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertFalse(result["businessVerdictTrusted"])

    def test_forged_ready_invalid_frames_and_counter_replays_fail(self):
        wrong = copy.deepcopy(ready())
        wrong["controls"]["interfaces"] = ["eth0", "lo"]
        with self.assertRaises(P.S.Failure):
            self.call(self.child("", wrong))
        for value in ({"kind": "fetch", "id": True, "request": {}},
                      {"kind": "fetch", "id": 2, "request": {}}, {"secret": "PRIVATE"}):
            with self.subTest(value=value), self.assertRaises(P.S.Failure):
                self.call(self.child("print(" + repr(json.dumps(value)) + ",flush=True)"))

    def test_source_flood_stall_and_early_exit_never_pass(self):
        for tail in ("print('x'*200000,flush=True)", "time.sleep(5)", "sys.exit(0)"):
            with self.subTest(tail=tail), self.assertRaises(P.S.Failure):
                self.call(self.child(tail), timeout=0.2)

    def test_peer_exit_while_host_sends_fetch_response_is_a_protocol_failure(self):
        import time
        class Broker:
            events = []
            def fetch(self, request):
                time.sleep(0.1)
                return {"status": 200, "body": "", "headers": {}}
        fetch = {"kind": "fetch", "id": 1, "request": request()}
        tail = ("import os; print(" + repr(json.dumps(fetch)) + ",flush=True); "
                "os.close(0); time.sleep(0.2)")
        with self.assertRaisesRegex(P.S.Failure, "EGRESS_PROTOCOL_INVALID"):
            self.call(self.child(tail), Broker())

    def test_policy_refusal_before_peer_exit_retains_bounded_context_without_request(self):
        class Broker:
            events = []
            last_redirect = {"responseStatus": 302, "requestUrlSha256": "c" * 64,
                             "targetUrlSha256": "d" * 64, "targetScheme": "https",
                             "targetOrigin": "https://canonical.example.com"}
            last_http_observation = {"method": "GET", "requestUrlSha256": "c" * 64,
                                     "requestNumber": 1, "responseStatus": 302,
                                     "responseContentType": "text/html"}
            last_dns_observation = {"dnsStatus": 3, "answerCount": 0, "publicIpv4Count": 0,
                                    "kind": "no_address"}
            def fetch(self, request):
                error = E.Denied("EGRESS_URL_DENIED")
                error.request_diagnostic = {
                    "method": "GET", "requestUrlSha256": "a" * 64,
                    "registeredUrlSha256": "b" * 64,
                    "registeredQuerySeparatorMissing": True}
                raise error
        fetch = {"kind": "fetch", "id": 1, "request": request()}
        tail = ("print(" + repr(json.dumps(fetch)) + ",flush=True); "
                "r=json.loads(sys.stdin.buffer.readline()); assert not r['ok']; sys.exit(1)")
        with self.assertRaises(P.S.Failure) as caught:
            self.call(self.child(tail), Broker())
        context = caught.exception.exchange_context
        self.assertTrue(context["policyDenied"])
        self.assertTrue(context["lastFetchDenied"])
        self.assertEqual("EGRESS_URL_DENIED", context["lastDeniedCode"])
        self.assertEqual(1, context["lastHttpId"])
        self.assertEqual(0, context["loaderCallCount"])
        self.assertEqual(Broker.last_redirect, context["redirectObservation"])
        self.assertEqual(Broker.last_http_observation, context["httpObservation"])
        self.assertEqual(Broker.last_dns_observation, context["dnsObservation"])
        self.assertEqual(0, context["completedHttpCount"])
        self.assertNotIn("reader.example.com", json.dumps(context))
        self.assertEqual("a" * 64, context["requestDiagnostic"]["requestUrlSha256"])
        self.assertNotIn("requestUrl", context)
        self.assertNotIn("reader.example.com", json.dumps(context["requestDiagnostic"]))

    def test_success_after_refusal_does_not_attribute_a_later_protocol_exit_to_the_old_denial(self):
        class Broker:
            events = []
            count = 0
            def fetch(self, value):
                self.count += 1
                if self.count == 1:
                    raise E.Denied("EGRESS_HEADERS_DENIED")
                return {"status": 200, "headers": {}, "body": ""}
        tail = (
            "for index in (1,2):\n"
            " print(json.dumps({'kind':'fetch','id':index,'request':{}}),flush=True)\n"
            " sys.stdin.buffer.readline()\n"
            "sys.exit(1)\n")
        with self.assertRaises(P.S.Failure) as caught:
            self.call(self.child("\n" + tail), Broker())
        self.assertEqual("EGRESS_HEADERS_DENIED", caught.exception.exchange_context["lastDeniedCode"])
        self.assertFalse(caught.exception.exchange_context["lastFetchDenied"])

    def test_deadline_during_host_loader_validation_remains_a_timeout(self):
        import time
        accepted = []

        class SlowLoader:
            def __init__(self, *args, **kwargs):
                pass

            def next(self):
                return {"kind": "call", "id": 1, "method": "home", "params": {}}

            def accept(self, frame):
                accepted.append(frame["id"])
                time.sleep(1.2)
                return {"kind": "finish"}

        tail = ("print('{\"kind\":\"loader_available\"}',flush=True); "
                "sys.stdin.buffer.readline(); "
                "print('{\"kind\":\"call_result\",\"id\":1,\"ok\":true,\"result\":{}}',flush=True); "
                "sys.stdin.buffer.readline()")
        with patch.object(P, "Loader", SlowLoader), self.assertRaisesRegex(P.S.Failure, "ENGINE_TIMEOUT"):
            self.call(self.child(tail), timeout=1)
        self.assertEqual([1], accepted)

    def test_deadline_kill_before_final_poll_remains_a_timeout(self):
        import time
        from types import SimpleNamespace
        popen = P.subprocess.Popen
        expired = False

        def start(*args, **kwargs):
            process = popen(*args, **kwargs)
            poll = process.poll

            def deadline_poll():
                nonlocal expired
                if process.stdin.closed and not expired:
                    expired = True
                    process.kill()
                    process.wait(timeout=2)
                return poll()

            process.poll = deadline_poll
            return process

        clock = SimpleNamespace(monotonic=lambda: time.monotonic() + (10 if expired else 0),
                                sleep=time.sleep)
        final = {"kind": "result", "summary": {"status": "init_returned"}}
        tail = "print(" + repr(json.dumps(final)) + ",flush=True); time.sleep(5)"
        with patch.object(P.subprocess, "Popen", start), patch.object(P, "time", clock):
            with self.assertRaisesRegex(P.S.Failure, "ENGINE_TIMEOUT"):
                self.call(self.child(tail), timeout=2)

    def test_lost_create_ack_and_cleanup_failures_stay_failed(self):
        class Engine(F.Engine):
            def call(self, arguments, payload=b"", timeout=6, limit=196608):
                if arguments[:2] == ["image", "inspect"]:
                    return P.S.encode([{"Id": F.F.IMAGE, "Os": "linux", "Config": {"Labels": {
                        **{P.S.MANAGED + "." + key.removesuffix(".py").replace("_", "-"): val
                           for key, val in HASHES.items()},
                        P.S.MANAGED + ".runtime": F.request()["runtimeSha256"]}}}])
                result = super().call(arguments, payload, timeout, limit)
                if arguments[:2] == ["container", "create"]:
                    self.container["Config"]["Cmd"] = ["-I", P.ENTRY]
                return result

        class Broker:
            policy = policy()
        engine = Engine()
        engine.create_error = "ENGINE_TIMEOUT"
        with patch.object(P, "exchange") as exchanged, self.assertRaisesRegex(
                P.S.Failure, "CONTAINER_CLEANUP_UNCONFIRMED"):
            P.run(engine, F.request(), F.SOURCE, HASHES, Broker())
        exchanged.assert_not_called()
        engine = Engine()
        engine.command = ["fake"]
        with patch.object(P, "exchange", side_effect=P.S.Failure("ENGINE_TIMEOUT")), self.assertRaisesRegex(
                P.S.Failure, "ENGINE_TIMEOUT"):
            P.run(engine, F.request(), F.SOURCE, HASHES, Broker())
        self.assertIsNone(engine.container)
        engine = Engine()
        engine.command = ["fake"]
        engine.refuse_remove = True
        with patch.object(P, "exchange", side_effect=P.S.Failure("ENGINE_TIMEOUT")), self.assertRaisesRegex(
                P.S.Failure, "CONTAINER_CLEANUP_UNCONFIRMED"):
            P.run(engine, F.request(), F.SOURCE, HASHES, Broker())

    def test_cleanup_reconciles_lost_remove_reply_without_repeating_remove(self):
        class Engine(F.Engine):
            def call(self, arguments, *args, **kwargs):
                result = super().call(arguments, *args, **kwargs)
                if arguments[:2] == ["container", "rm"]:
                    raise P.S.Failure("ENGINE_TIMEOUT")
                return result

        engine = Engine()
        engine.call(P.create_arguments(F.request(), HASHES))
        ident = engine.container["Id"]
        result = P.cleanup(engine, F.request(), ident)
        self.assertTrue(result["containerAbsent"])
        removes = [row for row, _ in engine.calls if row[:2] == ["container", "rm"]]
        self.assertEqual(1, len(removes))
        filters = [row for row, _ in engine.calls if row[:2] == ["container", "ls"]]
        self.assertTrue(any("id=" + ident in row for row in filters))

    def test_cleanup_rejects_foreign_kind_and_fixed_identity_replacement(self):
        for mutation in ("kind", "identity"):
            engine = F.Engine()
            engine.call(P.create_arguments(F.request(), HASHES))
            ident = engine.container["Id"]
            if mutation == "kind":
                engine.container["Config"]["Labels"][P.S.MANAGED + ".kind"] = "foreign"
            else:
                engine.container["Id"] = "9" * 64
            with self.subTest(mutation=mutation), self.assertRaises(P.S.Failure):
                P.cleanup(engine, F.request(), ident)
            self.assertFalse(any(row[:2] == ["container", "rm"] for row, _ in engine.calls))


class RouteCookieScopeTest(unittest.TestCase):
    def configured(self):
        configured = RoutesTest().policy()
        configured.pop("responseUrls")
        configured["maxRequests"] = 8
        configured["routes"] = [
            {"origin": origin, "exactUrls": [], "requestScope": "origin", "pinnedIpv4": ["8.8.8.8"]}
            for origin in ("https://example.com", "https://www.example.com", "https://other.example.net",
                           "http://www.example.com")]
        return configured

    def connection(self, *cookies):
        connection = FakeConnection()
        connection.response.getheaders = lambda: [("Set-Cookie", cookie) for cookie in cookies]
        return connection

    def fetch(self, mediator, connection, url, cookie=None):
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), \
                patch.object(E.http.client, "HTTPConnection", return_value=connection), \
                patch.object(E.time, "sleep"):
            return mediator.fetch({"method": "GET", "url": url, "body": "",
                                   "headers": {} if cookie is None else {"Cookie": cookie}})

    def test_domain_cookie_reaches_registered_subdomain_and_parent_deletion_propagates(self):
        mediator = E.Routes(self.configured())
        self.fetch(mediator, self.connection("visit=observed; Domain=example.com; Path=/; Secure"),
                   "https://example.com/list")
        connection = self.connection("visit=; Domain=example.com; Path=/; Max-Age=0; Secure")
        self.fetch(mediator, connection, "https://www.example.com/list", "visit=observed")
        self.assertEqual("visit=observed", connection.sent[3]["Cookie"])
        connection = self.connection()
        self.fetch(mediator, connection, "https://example.com/list")
        self.assertNotIn("Cookie", connection.sent[3])
        self.assertEqual({}, mediator.server_cookies)
        self.assertNotIn("observed", json.dumps(mediator.events))

    def test_parent_cookie_is_accepted_only_with_registered_parent_and_never_copies_host_credentials(self):
        mediator = E.Routes(self.configured())
        mediator.bind_credentials({"origin": "https://example.com", "cookie": "admin=private",
                                   "authorization": "Bearer private"})
        self.fetch(mediator, self.connection("visit=observed; Domain=example.com; Path=/; Secure"),
                   "https://www.example.com/list")
        connection = self.connection()
        self.fetch(mediator, connection, "https://example.com/list")
        self.assertIn("admin=private", connection.sent[3]["Cookie"])
        self.assertIn("visit=observed", connection.sent[3]["Cookie"])
        self.fetch(mediator, connection, "https://www.example.com/list")
        self.assertEqual("visit=observed", connection.sent[3]["Cookie"])
        self.assertNotIn("Authorization", connection.sent[3])
        with self.assertRaisesRegex(E.Denied, "EGRESS_HEADERS_DENIED"):
            self.fetch(mediator, connection, "https://www.example.com/list", "admin=private")
        self.assertNotIn("private", json.dumps(mediator.events))

    def test_out_of_scope_observed_cookie_is_dropped_but_unknown_cookie_is_not_admitted(self):
        mediator = E.Routes(self.configured())
        self.fetch(mediator, self.connection("visit=observed; Path=/; Secure"), "https://example.com/list")
        connection = self.connection()
        self.fetch(mediator, connection, "https://www.example.com/list", "visit=observed")
        self.assertNotIn("Cookie", connection.sent[3])
        for cookie in ("visit=imported", "visit=observed; unknown=imported"):
            with self.subTest(cookie=cookie), self.assertRaisesRegex(E.Denied, "EGRESS_HEADERS_DENIED"):
                self.fetch(mediator, connection, "https://www.example.com/list", cookie)
        with self.assertRaisesRegex(E.Denied, "EGRESS_HEADERS_DENIED"):
            self.fetch(E.Routes(self.configured()), connection, "https://www.example.com/list", "visit=observed")

    def test_cookie_path_secure_and_unrelated_domain_remain_effective(self):
        mediator = E.Routes(self.configured())
        self.fetch(mediator, self.connection("visit=observed; Domain=example.com; Path=/private; Secure",
                                           "foreign=blocked; Domain=example.net; Path=/"),
                   "https://example.com/private/list")
        connection = self.connection()
        for url in ("https://www.example.com/public", "http://www.example.com/private/list",
                    "https://other.example.net/private/list"):
            self.fetch(mediator, connection, url, "visit=observed")
            self.assertNotIn("Cookie", connection.sent[3])
        self.fetch(mediator, connection, "https://www.example.com/private/list", "visit=observed")
        self.assertEqual("visit=observed", connection.sent[3]["Cookie"])
        self.assertFalse(any(cookie.name == "foreign" for cookie in mediator.server_cookies.values()))

    def test_requests_fallback_after_444_does_not_fail_on_its_host_only_cookie(self):
        import requests
        mediator, bridge = E.Routes(self.configured()), BridgeTest().bridge()
        connections = [self.connection("visit=observed; Path=/; Secure"), self.connection()]
        connections[0].response.status = 444
        replies, frames = {}, []

        def send_frame(fd, raw):
            frame = json.loads(raw)
            frames.append(frame)
            result = mediator.fetch(frame["request"])
            replies["next"] = json.dumps({"id": frame["id"], "ok": True, "response": result}).encode() + b"\n"
            return len(raw)

        bridge.INPUT = type("Input", (), {"readline": lambda self, limit: replies.pop("next")})()
        with patch.object(requests.adapters.HTTPAdapter, "send", bridge.send), \
                patch.object(bridge.os, "write", side_effect=send_frame), \
                patch.object(E.http.client, "HTTPSConnection", side_effect=connections), \
                patch.object(E.time, "sleep"), requests.Session() as session:
            session.trust_env = False
            self.assertEqual(444, session.get("https://example.com/list").status_code)
            self.assertEqual(200, session.get("https://www.example.com/list").status_code)
        self.assertEqual(2, len(frames))
        self.assertNotIn("Cookie", connections[1].sent[3])


class BridgeTest(unittest.TestCase):
    def bridge(self):
        path = Path(__file__).resolve().parents[1] / "managed-source/egress_bridge.py"
        spec = importlib.util.spec_from_file_location("egress_bridge_test", path)
        bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bridge)
        return bridge

    def test_http_client_uses_broker_for_http_https_and_preserves_response_contract(self):
        for secure in (False, True):
            bridge, frames, replies = self.bridge(), [], {}

            def send_frame(fd, raw):
                frame = json.loads(raw)
                frames.append(frame)
                replies["next"] = json.dumps({"id": frame["id"], "ok": True, "response": {
                    "status": 206, "headers": {"Content-Type": "application/octet-stream"},
                    "body": base64.b64encode(b"media-prefix").decode(),
                    "setCookies": ["a=1; Path=/", "b=2; Path=/"]}}).encode() + b"\n"
                return len(raw)

            bridge.INPUT = type("Input", (), {"readline": lambda self, limit: replies.pop("next")})()
            cls = bridge.BrokerHTTPSConnection if secure else bridge.BrokerHTTPConnection
            options = {"context": ssl._create_unverified_context()} if secure else {}
            connection = cls("media.example.com", **options)
            with self.subTest(secure=secure), patch.object(bridge.os, "write", side_effect=send_frame), \
                    patch.object(socket, "create_connection", side_effect=AssertionError("SOCKET_NOT_ALLOWED")):
                connection.connect()
                connection.request("POST", "/media?id=1", body=b"query", headers={
                    "Host": "media.example.com", "Range": "bytes=0-11", "Accept-Encoding": "gzip"})
                with self.assertRaises(http.client.CannotSendRequest):
                    connection.request("GET", "/second")
                with connection.getresponse() as response:
                    self.assertEqual(206, response.status)
                    self.assertEqual("application/octet-stream", response.getheader("content-type"))
                    self.assertEqual(["a=1; Path=/", "b=2; Path=/"], response.headers.get_all("Set-Cookie"))
                    self.assertEqual(2, len([row for row in response.getheaders() if row[0] == "Set-Cookie"]))
                    self.assertEqual(b"media", response.read(5))
                    self.assertEqual(b"-prefix", response.read())
                self.assertTrue(response.isclosed())
                with self.assertRaises(http.client.ResponseNotReady):
                    connection.getresponse()
                connection.close()
            sent = frames[0]["request"]
            self.assertEqual(("https" if secure else "http") + "://media.example.com/media?id=1", sent["url"])
            self.assertEqual(b"query", base64.b64decode(sent["body"]))
            self.assertEqual({"Range": "bytes=0-11"}, sent["headers"])

    def test_http_client_rejects_transport_overrides_and_preserves_broker_refusals(self):
        bridge = self.bridge()
        for target, headers in (("https://other.example.com/", {}), ("//other.example.com/", {}),
                                ("/", {"Host": "other.example.com"}),
                                ("/", {"Host": "media.example.com", "host": "media.example.com"})):
            with self.subTest(target=target, headers=headers), patch.object(bridge, "send") as send:
                with self.assertRaises(http.client.HTTPException):
                    bridge.BrokerHTTPConnection("media.example.com").request("GET", target, headers=headers)
                send.assert_not_called()
        with patch.object(bridge, "send") as send:
            with self.assertRaises(http.client.HTTPException):
                bridge.BrokerHTTPConnection("media.example.com").set_tunnel("other.example.com")
            with self.assertRaises(http.client.HTTPException):
                bridge.BrokerHTTPConnection("media.example.com", source_address=("127.0.0.1", 0)).connect()
            send.assert_not_called()
        denied = bridge.PolicyDenied("EGRESS_DENIED", retry_after=30)
        with patch.object(bridge, "send", side_effect=denied), self.assertRaises(bridge.PolicyDenied) as caught:
            bridge.BrokerHTTPConnection("media.example.com").request("GET", "/")
        self.assertIs(denied, caught.exception)
        self.assertEqual(30, caught.exception.retry_after)

    def test_http_client_install_is_source_side_and_keeps_existing_adapters(self):
        bridge = self.bridge()
        import requests
        import urllib.request
        with patch.object(http.client, "HTTPConnection"), patch.object(http.client, "HTTPSConnection"), \
                patch.object(requests.adapters.HTTPAdapter, "send"), \
                patch.object(urllib.request.HTTPHandler, "http_open"), \
                patch.object(urllib.request.HTTPSHandler, "https_open"):
            bridge.install(io.BytesIO())
            self.assertIs(bridge.BrokerHTTPConnection, http.client.HTTPConnection)
            self.assertIs(bridge.BrokerHTTPSConnection, http.client.HTTPSConnection)
            self.assertIs(bridge.send, requests.adapters.HTTPAdapter.send)
            self.assertIs(bridge._http_open, urllib.request.HTTPHandler.http_open)
            self.assertIs(bridge._https_open, urllib.request.HTTPSHandler.https_open)

    def test_source_cookie_seed_is_origin_bound_and_server_updates_and_deletions_win(self):
        configured = {**policy(), "maxRequests": 4, "maxTotalResponseBytes": 2048}
        mediator = E.Broker(configured)
        source = b'headers = {"Cookie": "visit=source-initial; mode=public"}\n'
        mediator.bind_source(source)
        original = copy.deepcopy(configured)
        connections = [FakeConnection() for _ in range(4)]
        connections[0].response.getheaders = lambda: [
            ("Set-Cookie", "visit=server-updated; Path=/; Secure")]
        connections[1].response.getheaders = lambda: [
            ("Set-Cookie", "visit=; Max-Age=0; Path=/; Secure")]
        for connection in connections[2:]:
            connection.response.getheaders = lambda: []
        value = {**request(), "headers": {"Cookie": "visit=source-initial; mode=public"}}
        with patch.object(E.http.client, "HTTPSConnection", side_effect=connections), patch.object(E.time, "sleep"):
            mediator.fetch(value)
            mediator.bind_source(source)
            mediator.fetch(value)
            mediator.fetch(value)
            mediator.fetch({**value, "headers": {}})
        self.assertEqual("visit=source-initial; mode=public", connections[0].sent[3]["Cookie"])
        self.assertEqual("visit=server-updated; mode=public", connections[1].sent[3]["Cookie"])
        self.assertEqual("mode=public", connections[2].sent[3]["Cookie"])
        self.assertEqual("mode=public", connections[3].sent[3]["Cookie"])
        self.assertEqual(original, mediator.policy)
        self.assertNotIn("source-initial", json.dumps(mediator.events))
        self.assertNotIn("server-updated", json.dumps(mediator.events))
        with self.assertRaisesRegex(E.Denied, "EGRESS_POLICY_INVALID"):
            mediator.bind_source(b'headers = {"Cookie": "visit=different-source"}\n')

    def test_literal_cookie_helper_and_header_assignment_are_source_owned(self):
        source = (
            b"def _forum_cookie(self):\n"
            b"    values = []\n"
            b"    values.append('ismob=0')\n"
            b"    return '; '.join(values)\n"
            b"headers['Cookie'] = 'display=full'\n"
            b"unrelated = 'hostCredential=PRIVATE'\n")
        declared = E.source_cookie_declarations(source)
        self.assertEqual({"ismob=0", "display=full"}, set(declared))
        mediator = E.Broker(policy())
        mediator.bind_source(source)
        connection = FakeConnection()
        connection.response.getheaders = lambda: []
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            mediator.fetch({**request(), "headers": {"Cookie": "ismob=0"}})
        self.assertEqual("ismob=0", connection.sent[3]["Cookie"])
        self.assertNotIn("PRIVATE", json.dumps(mediator.events))

    def test_observed_html_embeds_and_inline_player_data_discover_public_origins_only(self):
        body = (b'<iframe src="https://parse.example.com/player?url=opaque"></iframe>'
                b'<script src="https://assets.example.com/app.js"></script>'
                b'<video><source src="https://cdn.example.com/movie.m3u8"></video>'
                b'<script>const player={"url":"https://inline.example.com/watch?id=1"};'
                b'/* "https://comment.example.com/" */</script>'
                b'<a href="https://unrelated.example.com/">link</a>')
        self.assertEqual([
            "https://parse.example.com", "https://assets.example.com", "https://cdn.example.com",
            "https://inline.example.com",
        ], E.response_api_origins(body, "text/html"))

    def test_data_media_resolver_prefix_and_relative_assets_use_the_observed_page(self):
        body = (b'<video data-source="//media.example.com/movie.m3u8" '
                b'data-poster="/poster.jpg"></video>'
                b'<div data-prefix="https://resolver.example.com/player/?url="></div>'
                b'<meta property="og:video:secure_url" content="https://progressive.example.com/file.mp4">'
                b'<img data-src="//images.example.com/cover.jpg">'
                b'<a href="https://not-an-asset.example.com">unrelated</a>')
        self.assertEqual(["https://media.example.com", "https://site.example.com",
                          "https://resolver.example.com", "https://progressive.example.com",
                          "https://images.example.com"],
                         E.response_api_origins(body, "text/html", "https://site.example.com/detail/1"))
        self.assertEqual([], E.response_api_origins(
            b'<div data-prefix="https://user:PRIVATE@bad.example.com/"></div>'
            b'<video data-source="file:///private"></video>',
            "text/html", "https://site.example.com/detail/1"))

    def test_source_cookie_aliases_seed_independently_without_sharing_server_state(self):
        configured = RoutesTest().policy()
        configured["maxRequests"] = 4
        for route in configured["routes"]:
            route["requestScope"] = "origin"
        mediator = E.Routes(configured)
        mediator.bind_source(b'headers = {"Cookie": "visit=source-initial"}\n')
        value = {**request(), "headers": {"Cookie": "visit=source-initial"}}
        connection = FakeConnection()
        connection.response.getheaders = lambda: [
            ("Set-Cookie", "visit=first-origin-server-state; Path=/; Secure")]
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection), patch.object(E.time, "sleep"):
            mediator.fetch(value)
            connection.response.getheaders = lambda: []
            mediator.fetch({**value, "url": "https://media.example.com/another"})
            self.assertEqual("visit=source-initial", connection.sent[3]["Cookie"])
            mediator.fetch({**value, "url": "https://media.example.com/another", "headers": {}})
        self.assertEqual("visit=source-initial", connection.sent[3]["Cookie"])
        self.assertNotIn("first-origin-server-state", connection.sent[3]["Cookie"])
        for supplied in ("visit=imported", ["visit=source-initial"], None):
            with self.subTest(supplied=supplied), self.assertRaisesRegex(E.Denied, "EGRESS_HEADERS_DENIED"):
                mediator.fetch({**value, "headers": {"Cookie": supplied}})
        fresh = E.Broker(policy())
        fresh.bind_source(b'headers = {"Cookie": "visit=other-source"}\n')
        with self.assertRaisesRegex(E.Denied, "EGRESS_HEADERS_DENIED"):
            fresh.fetch(value)

    def test_same_origin_redirect_cookie_subset_and_order_are_validated_against_host_state(self):
        import requests
        configured = {**policy(), "requestScope": "origin", "exactUrls": []}
        mediator, bridge = E.Broker(configured, allow_empty=True), self.bridge()
        mediator.bind_source(b'headers = {"Cookie": "mode=public; visit=source-initial"}\n')
        connections = [FakeConnection() for _ in range(3)]
        connections[0].response.status = 302
        connections[0].response.read = lambda limit: b""
        target = configured["origin"] + "/redirected"
        connections[0].response.getheader = lambda key, default=None: target if key == "Location" else default
        connections[0].response.getheaders = lambda: [
            ("Location", target), ("Set-Cookie", "visit=server-updated; Path=/; Secure")]
        connections[1].response.getheaders = lambda: []
        replies, frames = {}, []
        def send_frame(fd, raw):
            frame = json.loads(raw)
            frames.append(frame)
            result = mediator.fetch(frame["request"])
            replies["next"] = json.dumps({"id": frame["id"], "ok": True, "response": result}).encode() + b"\n"
            return len(raw)
        bridge.INPUT = type("Input", (), {"readline": lambda self, limit: replies.pop("next")})()
        with patch.object(requests.adapters.HTTPAdapter, "send", bridge.send), \
                patch.object(bridge.os, "write", side_effect=send_frame), \
                patch.object(E.http.client, "HTTPSConnection", side_effect=connections), \
                patch.object(E.time, "sleep"), requests.Session() as session:
            session.trust_env = False
            result = session.get(configured["origin"] + "/entry",
                                 headers={"Cookie": "mode=public; visit=source-initial"})
            self.assertEqual(target, result.url)
            self.assertEqual("visit=server-updated", frames[1]["request"]["headers"]["Cookie"])
            self.assertEqual("mode=public; visit=server-updated", connections[1].sent[3]["Cookie"])
            mediator.fetch({**request(), "headers": {"Cookie": "visit=server-updated; mode=public"}})
        with self.assertRaisesRegex(E.Denied, "EGRESS_HEADERS_DENIED"):
            mediator.fetch({**request(), "headers": {"Cookie": "visit=imported; mode=public"}})

    def test_empty_cookie_is_noop_and_cookie_attributes_cannot_import_a_domain(self):
        value = {**request(), "headers": {"Cookie": ""}}
        mediator = E.Broker(policy())
        connection = FakeConnection()
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            mediator.fetch(value)
        self.assertNotIn("Cookie", connection.sent[3])
        for cookie in ("visit=literal; Domain=example.com", "visit=literal; Path=/",
                       "visit=literal; Secure"):
            mediator = E.Broker(policy())
            mediator.bind_source(('headers = {"Cookie": ' + repr(cookie) + '}\n').encode())
            with self.subTest(cookie=cookie), self.assertRaisesRegex(E.Denied, "EGRESS_HEADERS_DENIED"):
                mediator.fetch({**value, "headers": {"Cookie": cookie}})
            self.assertEqual([], list(mediator.cookies))

    def test_requests_follow_redirects_through_broker_and_strip_cross_origin_state(self):
        import requests
        for status, scheme in ((status, scheme) for status in (301, 302, 303, 307, 308)
                               for scheme in ("https", "http")):
            with self.subTest(status=status, targetScheme=scheme):
                configured = RoutesTest().policy()
                configured["routes"][1]["origin"] = scheme + "://media.example.com"
                configured["responseUrls"][0]["targetOrigin"] = configured["routes"][1]["origin"]
                configured["maxTotalResponseBytes"] = 2048
                issuer = configured["routes"][0]
                issuer.update(requestScope="origin", sessionToken={"responseScope": "origin"})
                configured["routes"][1]["requestScope"] = "origin"
                mediator, bridge = E.Routes(configured), self.bridge()
                connections = [FakeConnection() for _ in range(3)]
                connections[0].response.read = lambda limit: b'{"token":"issued"}'
                connections[1].response.status = status
                connections[1].response.read = lambda limit: b""
                target = configured["routes"][1]["origin"] + "/final"
                for connection in connections:
                    connection.response.getheader = lambda key, default=None: (
                        "application/json" if key == "Content-Type" else default)
                    connection.response.getheaders = lambda: [("Content-Type", "application/json")]
                connections[1].response.getheader = lambda key, default=None: (
                    target if key == "Location" else "application/json" if key == "Content-Type" else default)
                connections[1].response.getheaders = lambda: [
                    ("Location", target), ("Set-Cookie", "visit=source-only; Path=/; Secure")]
                replies, frames = {}, []
                remaining = iter(connections)
                def connect(*args, **kwargs):
                    return next(remaining)
                def send_frame(fd, raw):
                    frame = json.loads(raw)
                    frames.append(frame)
                    result = mediator.fetch(frame["request"])
                    replies["next"] = json.dumps({"id": frame["id"], "ok": True, "response": result}).encode() + b"\n"
                    return len(raw)
                bridge.INPUT = type("Input", (), {"readline": lambda self, limit: replies.pop("next")})()
                with patch.object(requests.adapters.HTTPAdapter, "send", bridge.send), \
                        patch.object(bridge.os, "write", side_effect=send_frame), \
                        patch.object(E.http.client, "HTTPSConnection", side_effect=connect), \
                        patch.object(E.http.client, "HTTPConnection", side_effect=connect), \
                        patch.object(E.time, "sleep"), requests.Session() as session:
                    session.trust_env = False
                    session.get(issuer["origin"] + "/auth")
                    result = session.post(issuer["origin"] + "/entry", data=b'{"page":1}',
                                          headers={"Authorization": "Bearer issued"})
                self.assertEqual(200, result.status_code)
                self.assertEqual(target, result.url)
                self.assertEqual(1, len(result.history))
                follow = frames[-1]["request"]
                self.assertEqual("POST" if status in (307, 308) else "GET", follow["method"])
                self.assertEqual(b'{"page":1}' if status in (307, 308) else b"",
                                 base64.b64decode(follow["body"]))
                self.assertFalse(any(key.lower() in {"cookie", "authorization"} for key in follow["headers"]))
                self.assertEqual(3, mediator.count)
                self.assertEqual("http_redirect", mediator.events[1]["responseClass"])
                self.assertNotIn("source-only", json.dumps(mediator.events))
                self.assertNotIn(target, json.dumps(mediator.events))

    def test_redirect_targets_and_loops_do_not_expand_origins_or_request_budget(self):
        import requests
        for target, expected_count in (("https://unknown.example.com/end", 1),
                                       ("https://127.0.0.1/end", 1), (policy()["origin"] + "/loop", 3)):
            with self.subTest(target=target):
                configured = {**policy(), "requestScope": "origin", "exactUrls": []}
                mediator, bridge = E.Broker(configured, allow_empty=True), self.bridge()
                connection = FakeConnection()
                connection.response.status = 302
                connection.response.read = lambda limit: b""
                connection.response.getheader = lambda key, default=None: target if key == "Location" else default
                connection.response.getheaders = lambda: [("Location", target)]
                replies = {}
                def send_frame(fd, raw):
                    frame = json.loads(raw)
                    try:
                        reply = {"id": frame["id"], "ok": True, "response": mediator.fetch(frame["request"])}
                    except E.Denied as error:
                        reply = {"id": frame["id"], "ok": False, "code": error.code}
                    replies["next"] = json.dumps(reply).encode() + b"\n"
                    return len(raw)
                bridge.INPUT = type("Input", (), {"readline": lambda self, limit: replies.pop("next")})()
                with patch.object(requests.adapters.HTTPAdapter, "send", bridge.send), \
                        patch.object(bridge.os, "write", side_effect=send_frame), \
                        patch.object(E.http.client, "HTTPSConnection", return_value=connection) as connect, \
                        patch.object(E.time, "sleep"), requests.Session() as session:
                    session.trust_env = False
                    with self.assertRaises(bridge.PolicyDenied):
                        session.get(configured["origin"] + "/entry")
                self.assertEqual(expected_count, mediator.count)
                self.assertEqual(expected_count, connect.call_count)
                self.assertEqual([], list(mediator.cookies))

    def test_response_cookies_survive_source_session_actions_without_import_or_cross_origin_leak(self):
        import requests
        bridge = self.bridge()
        configured = policy()
        mediator = E.Broker(configured)
        connections = [FakeConnection(), FakeConnection()]
        connections[0].response.getheaders = lambda: [
            ("Set-Cookie", "visit=server-issued; Path=/; Secure; HttpOnly"),
            ("Set-Cookie", "other=forbidden; Domain=example.com; Path=/")]
        connections[1].response.getheaders = lambda: []
        holder, frames = {}, []
        def send_frame(fd, raw):
            frame = json.loads(raw)
            frames.append(frame)
            result = mediator.fetch(frame["request"])
            holder["reply"] = json.dumps({"id": frame["id"], "ok": True, "response": result}).encode() + b"\n"
            return len(raw)
        bridge.INPUT = type("Input", (), {"readline": lambda self, limit: holder.pop("reply")})()
        with patch.object(requests.adapters.HTTPAdapter, "send", bridge.send), \
                patch.object(bridge.os, "write", side_effect=send_frame), \
                patch.object(E.http.client, "HTTPSConnection", side_effect=connections), \
                requests.Session() as source_session:
            source_session.trust_env = False
            home = source_session.get(configured["origin"] + configured["paths"][0], allow_redirects=False)
            self.assertEqual("server-issued", home.cookies.get("visit"))
            self.assertEqual("server-issued", source_session.cookies.get("visit"))
            source_session.get(configured["origin"] + configured["paths"][1], allow_redirects=False)
        self.assertEqual("visit=server-issued", frames[1]["request"]["headers"]["Cookie"])
        self.assertEqual("visit=server-issued", connections[1].sent[3]["Cookie"])
        self.assertNotIn("server-issued", json.dumps(mediator.events))
        self.assertNotIn("other", connections[1].sent[3]["Cookie"])
        self.assertEqual([], list(E.Broker(configured).cookies))
        forged = {**request(), "method": "GET", "body": "", "headers": {"Cookie": "visit=imported"}}
        with self.assertRaisesRegex(E.Denied, "EGRESS_HEADERS_DENIED"):
            mediator.fetch(forged)
        for malformed in (None, [], {"headers": None}, {"headers": {1: "x"}}, {**forged, "url": None}):
            with self.subTest(malformed=malformed), self.assertRaises(E.Denied):
                mediator.fetch(malformed)

    def test_cookiejar_opaque_values_can_roundtrip_exactly_without_simplecookie_reparsing(self):
        import requests
        bridge = self.bridge()
        configured = policy()
        mediator = E.Broker(configured)
        connections = [FakeConnection(), FakeConnection()]
        issued = 'opaque={"anonymous":true}; Path=/; Secure; HttpOnly'
        connections[0].response.getheaders = lambda: [("Set-Cookie", issued)]
        connections[1].response.getheaders = lambda: []
        holder, frames = {}, []

        def send_frame(fd, raw):
            frame = json.loads(raw)
            frames.append(frame)
            result = mediator.fetch(frame["request"])
            holder["reply"] = json.dumps({"id": frame["id"], "ok": True, "response": result}).encode() + b"\n"
            return len(raw)

        bridge.INPUT = type("Input", (), {"readline": lambda self, limit: holder.pop("reply")})()
        with patch.object(requests.adapters.HTTPAdapter, "send", bridge.send), \
                patch.object(bridge.os, "write", side_effect=send_frame), \
                patch.object(E.http.client, "HTTPSConnection", side_effect=connections), \
                patch.object(E.time, "sleep"), requests.Session() as session:
            session.trust_env = False
            for path in configured["paths"]:
                session.get(configured["origin"] + path, allow_redirects=False)
        supplied = frames[1]["request"]["headers"]["Cookie"]
        self.assertEqual('opaque={"anonymous":true}', supplied)
        self.assertEqual(supplied, connections[1].sent[3]["Cookie"])
        self.assertEqual(2, mediator.count)
        for broker, header in (
                (mediator, 'opaque={"anonymous":false}'), (E.Broker(configured), supplied)):
            with self.subTest(fresh=broker is not mediator), self.assertRaisesRegex(
                    E.Denied, "^EGRESS_HEADERS_DENIED$"):
                broker.fetch({**request(), "headers": {"Cookie": header}})

    def test_server_cookie_deletions_reach_the_source_session_before_its_next_action(self):
        import requests
        for scope, expiry in (
                ("Path=/", "Max-Age=0"),
                ("Path=/", "Expires=Thu, 01 Jan 1970 00:00:00 GMT"),
                ("Path=/; Domain=reader.example.com", "Max-Age=0"),
                ("", "Max-Age=0")):
            with self.subTest(scope=scope, expiry=expiry):
                bridge = self.bridge()
                mediator = E.Broker(policy())
                connections = [FakeConnection() for _ in range(3)]
                issued = f"visit=server-issued; {scope}; Secure; HttpOnly"
                deleted = f"visit=; {scope}; {expiry}; Secure; HttpOnly"
                connections[0].response.getheaders = lambda: [("Set-Cookie", issued)]
                connections[1].response.getheaders = lambda: [("Set-Cookie", deleted)]
                connections[2].response.getheaders = lambda: []
                replies, frames = {}, []
                def send_frame(fd, raw):
                    frame = json.loads(raw)
                    frames.append(frame)
                    result = mediator.fetch(frame["request"])
                    replies["next"] = json.dumps({
                        "id": frame["id"], "ok": True, "response": result}).encode() + b"\n"
                    return len(raw)
                bridge.INPUT = type("Input", (), {"readline": lambda self, limit: replies.pop("next")})()
                with patch.object(requests.adapters.HTTPAdapter, "send", bridge.send), \
                        patch.object(bridge.os, "write", side_effect=send_frame), \
                        patch.object(E.http.client, "HTTPSConnection", side_effect=connections), \
                        patch.object(E.time, "sleep"), requests.Session() as session:
                    session.trust_env = False
                    for path in (policy()["paths"][0], policy()["paths"][1], policy()["paths"][0]):
                        session.get(policy()["origin"] + path, allow_redirects=False)
                        if len(frames) == 2:
                            self.assertEqual([], list(session.cookies))
                self.assertEqual("visit=server-issued", connections[1].sent[3]["Cookie"])
                self.assertNotIn("Cookie", frames[2]["request"]["headers"])
                self.assertNotIn("Cookie", connections[2].sent[3])
                self.assertEqual([], list(mediator.cookies))
                self.assertNotIn("server-issued", json.dumps(mediator.events))

    def test_deleting_one_cookie_path_does_not_remove_other_server_issued_state(self):
        configured = policy()
        mediator = E.Broker(configured)
        connections = [FakeConnection() for _ in range(3)]
        connections[0].response.getheaders = lambda: [
            ("Set-Cookie", "visit=root; Path=/; Secure"),
            ("Set-Cookie", "visit=narrow; Path=/api/drama; Secure")]
        connections[1].response.getheaders = lambda: [
            ("Set-Cookie", "visit=; Path=/; Domain=example.com; Max-Age=0"),
            ("Set-Cookie", "visit=; Path=/api/drama; Max-Age=0")]
        connections[2].response.getheaders = lambda: []
        replies = []
        with patch.object(E.http.client, "HTTPSConnection", side_effect=connections), \
                patch.object(E.time, "sleep"):
            for _ in connections:
                replies.append(mediator.fetch({**request(), "method": "GET", "body": "", "headers": {}}))
        self.assertEqual(["visit=; Path=/api/drama; Max-Age=0"], replies[1]["setCookies"])
        self.assertEqual("visit=root", connections[2].sent[3]["Cookie"])
        self.assertEqual([("visit", "root", "/")],
                         [(cookie.name, cookie.value, cookie.path) for cookie in mediator.cookies])

    def test_same_response_cookie_deletion_does_not_resurrect_in_requests_session(self):
        import requests
        mediator, bridge = E.Broker(policy()), self.bridge()
        connections = [FakeConnection(), FakeConnection()]
        connections[0].response.getheaders = lambda: [
            ("Set-Cookie", "visit=current; Path=/; Secure"),
            ("Set-Cookie", "retired=old; Path=/; Secure"),
            ("Set-Cookie", "retired=; Path=/; Max-Age=0; Secure")]
        connections[1].response.getheaders = lambda: []
        replies, frames = {}, []

        def send_frame(fd, raw):
            frame = json.loads(raw)
            frames.append(frame)
            result = mediator.fetch(frame["request"])
            replies["next"] = json.dumps({
                "id": frame["id"], "ok": True, "response": result}).encode() + b"\n"
            return len(raw)

        bridge.INPUT = type("Input", (), {"readline": lambda self, limit: replies.pop("next")})()
        with patch.object(requests.adapters.HTTPAdapter, "send", bridge.send), \
                patch.object(bridge.os, "write", side_effect=send_frame), \
                patch.object(E.http.client, "HTTPSConnection", side_effect=connections), \
                patch.object(E.time, "sleep"), requests.Session() as session:
            session.trust_env = False
            session.get(policy()["origin"] + policy()["paths"][0], allow_redirects=False)
            self.assertEqual({"visit": "current"}, session.cookies.get_dict())
            session.get(policy()["origin"] + policy()["paths"][1], allow_redirects=False)
        self.assertEqual("visit=current", frames[1]["request"]["headers"]["Cookie"])
        self.assertEqual("visit=current", connections[1].sent[3]["Cookie"])
        self.assertEqual({"visit": "current"}, {cookie.name: cookie.value for cookie in mediator.cookies})

    def test_upstream_head_range_remains_denied_before_connecting(self):
        with patch.object(E.http.client, "HTTPSConnection") as connect:
            with self.assertRaisesRegex(E.Denied, "EGRESS_RANGE_DENIED"):
                E.Broker(policy()).fetch({
                    **request(), "method": "HEAD", "body": "", "headers": {"Range": "bytes=0-32767"}})
        connect.assert_not_called()

    def test_cookie_rotation_deletion_and_range_bytes_reach_a_real_loopback_http_peer(self):
        import requests
        observed = []
        media = b"\x47" + b"\x00" * 187
        media *= 3
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                observed.append((self.path, self.headers.get("Cookie"), self.headers.get("Range")))
                ordinal = len(observed)
                body = media if ordinal == 3 else b'{"data":[]}'
                self.send_response(206 if ordinal == 3 else 200)
                self.send_header("Content-Type", "video/mp2t" if ordinal == 3 else "application/json")
                self.send_header("Content-Length", str(len(body)))
                if ordinal <= 2:
                    self.send_header("Set-Cookie", f"visit=wire-{ordinal}; Path=/; Secure; HttpOnly")
                if ordinal == 3:
                    self.send_header("Content-Range", "bytes 0-563/1128")
                    self.send_header("Set-Cookie", "visit=; Path=/; Max-Age=0; Secure; HttpOnly")
                self.end_headers()
                self.wfile.write(body)

        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.02})
        thread.start()
        class LoopbackConnection(http.client.HTTPConnection):
            # Exercise real HTTP serialization only; TLS/pinned-peer checks have separate tests.
            def connect(self):
                self.sock = socket.create_connection(server.server_address, timeout=2)

        configured = {**policy(), "maxRequests": 4, "maxResponseBytes": 1024,
                      "maxTotalResponseBytes": 4096}
        mediator, bridge = E.Broker(configured), self.bridge()
        replies = {}
        def send_frame(fd, raw):
            frame = json.loads(raw)
            result = mediator.fetch(frame["request"])
            replies["next"] = json.dumps({
                "id": frame["id"], "ok": True, "response": result}).encode() + b"\n"
            return len(raw)
        bridge.INPUT = type("Input", (), {"readline": lambda self, limit: replies.pop("next")})()
        try:
            with patch.object(requests.adapters.HTTPAdapter, "send", bridge.send), \
                    patch.object(bridge.os, "write", side_effect=send_frame), \
                    patch.object(E.http.client, "HTTPSConnection",
                                 side_effect=lambda *args, **kwargs: LoopbackConnection(
                                     "reader.example.com", 443, timeout=2)), \
                    patch.object(E.time, "sleep"), requests.Session() as session:
                session.trust_env = False
                urls = [configured["origin"] + configured["paths"][index] for index in (0, 1, 1, 0)]
                for ordinal, url in enumerate(urls, 1):
                    response = session.get(url, allow_redirects=False,
                                           headers={"Range": "bytes=0-32767"} if ordinal == 3 else {})
                    if ordinal == 3:
                        self.assertEqual(206, response.status_code)
                        self.assertEqual(media, response.content)
                        self.assertEqual([], list(session.cookies))
            self.assertEqual([
                (configured["paths"][0], None, None),
                (configured["paths"][1], "visit=wire-1", None),
                (configured["paths"][1], "visit=wire-2", "bytes=0-32767"),
                (configured["paths"][0], None, None)], observed)
            self.assertEqual("mpeg_ts", mediator.events[2]["prefixFormat"])
            self.assertEqual(E.digest(media), mediator.events[2]["responseSha256"])
            self.assertFalse(mediator.events[2]["resourceComplete"])
            self.assertNotIn("wire-", json.dumps(mediator.events))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(3)
            self.assertFalse(thread.is_alive())

    def test_policy_denial_stops_source_fallback_but_transport_failure_remains_an_exception(self):
        import requests
        bridge = self.bridge()
        prepared = requests.Request("GET", "https://reader.example.com/api/drama/list").prepare()
        for code in ("EGRESS_URL_DENIED", "EGRESS_RANGE_DENIED", "PRIVATE_UNRECOGNIZED"):
            bridge.COUNTER = 0
            bridge.INPUT = io.BytesIO(json.dumps({"id": 1, "ok": False, "code": code}).encode() + b"\n")
            with patch.object(bridge.os, "write", return_value=1), self.assertRaises(bridge.PolicyDenied) as failure:
                bridge.send(None, prepared)
            self.assertEqual("EGRESS_DENIED", str(failure.exception))
            self.assertNotIsInstance(failure.exception, Exception)
        for code in ("EGRESS_HTTP_FAILED", "EGRESS_HTTP_DEADLINE",
                     "EGRESS_DNS_FAILED", "EGRESS_DNS_NO_ADDRESS", "EGRESS_NONPUBLIC_DNS"):
            bridge.COUNTER = 0
            bridge.INPUT = io.BytesIO(json.dumps({"id": 1, "ok": False, "code": code}).encode() + b"\n")
            with self.subTest(code=code), patch.object(bridge.os, "write", return_value=1), \
                    self.assertRaises(requests.RequestException):
                bridge.send(None, prepared)

    def test_eight_mib_reply_fits_transport_but_oversize_frames_fail(self):
        path = Path(__file__).resolve().parents[1] / "managed-source/egress_bridge.py"
        spec = importlib.util.spec_from_file_location("egress_bridge_test", path)
        bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bridge)
        import requests
        prepared = requests.Request("GET", "https://reader.example.com/api/drama/list").prepare()
        frame = json.dumps({"id": 1, "ok": True, "response": {"status": 200, "headers": {},
                            "body": base64.b64encode(b"x" * 8388608).decode()}}).encode() + b"\n"
        bridge.INPUT = bridge.BrokerInput(io.BytesIO(frame))
        with patch.object(bridge.os, "write", return_value=1):
            self.assertEqual(8388608, len(bridge.send(None, prepared).content))
        bridge.INPUT = io.BytesIO(b"x" * (bridge.MAX_LINE + 1))
        with patch.object(bridge.os, "write", return_value=1), self.assertRaisesRegex(
                requests.RequestException, "EGRESS_RESPONSE_LIMIT"):
            bridge.send(None, prepared)


class PlayerSampleTest(unittest.TestCase):
    def setUp(self):
        self.bridge = BridgeTest().bridge()
        path = Path(__file__).resolve().parents[1] / "managed-source/egress_payload.py"
        spec = importlib.util.spec_from_file_location("managed_player_sample_test", path)
        self.payload = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.payload)
        self.modules = patch.dict(sys.modules, {"egress_bridge": self.bridge})
        self.modules.start()
        self.addCleanup(self.modules.stop)
        self.detail = {"vod_play_from": "first$$$other",
                       "vod_play_url": "one$opaque$tail#two$next$$$other$last"}

    def test_failure_observer_keeps_source_line_errno_and_no_traceback_or_message(self):
        frames, namespace = [], {}
        worker = {"_protocol_write": frames.append}
        self.payload.install_failure_observer(worker)
        exec(compile("def fail():\n    raise OSError(38, 'PRIVATE_TOKEN')\n", "<atvp-inner>", "exec"), namespace)
        try:
            namespace["fail"]()
        except OSError:
            worker["_protocol_write"]({"ok": False, "error": {"code": "OSError", "message": "existing"}})
        self.assertEqual({"sourceLine": 2, "errno": 38}, frames[0]["error"]["context"])
        self.assertNotIn("PRIVATE_TOKEN", json.dumps(frames))
        self.assertNotIn("atvp-inner", json.dumps(frames))
        worker["_protocol_write"]({"ok": True, "result": {}})
        self.assertEqual({"ok": True, "result": {}}, frames[1])

    def test_real_worker_play_contract_uses_first_group_and_retains_opaque_id(self):
        import requests
        calls, result = [], {}
        def handle(method, params):
            calls.append((method, params))
            return {"parse": 0, "url": "https://reader.example.com/media.mp4?private=signed",
                    "header": {"User-Agent": "sample"}}
        response = requests.Response()
        response.status_code, response._content = 206, b"\x00\x00\x00\x18ftypisom"
        response._content_consumed = True
        with patch.object(requests, "get", return_value=response) as get:
            self.payload.sample_player({"handle": handle}, self.detail, result)
        self.assertEqual([("play", {"flag": "first", "play": "opaque$tail"})], calls)
        self.assertEqual("returned", result["playerStatus"])
        self.assertEqual("prefix_returned", result["mediaStatus"])
        self.assertEqual("bytes=0-65535", get.call_args.kwargs["headers"]["Range"])
        self.assertFalse(get.call_args.kwargs["allow_redirects"])
        self.assertNotIn("private", json.dumps(result))
        self.assertNotIn("reader.example.com", json.dumps(result))

    def test_empty_parse_required_and_invalid_selection_do_not_request_media(self):
        import requests
        for value in ({"url": "about:blank"}, {"parse": 1, "url": "https://reader.example.com/"},
                      {"url": "https://reader.example.com/", "jx": 1}, {"url": ["invalid"]}):
            result = {}
            with patch.object(requests, "get") as get:
                self.payload.sample_player({"handle": lambda *args: value}, self.detail, result)
            get.assert_not_called()
            self.assertEqual("not_requested", result["mediaStatus"])
        result = {}
        with patch.object(requests, "get") as get:
            self.payload.sample_player({}, {"vod_play_url": "missing-separator",
                                            "vod_play_from": "first"}, result)
        get.assert_not_called()
        self.assertEqual("selection_unavailable", result["playerStatus"])

    def test_player_and_media_refusals_keep_distinct_bounded_results(self):
        import requests
        def refused(*args):
            raise self.bridge.PolicyDenied("EGRESS_DENIED")
        result = {}
        self.payload.sample_player({"handle": refused}, self.detail, result)
        self.assertEqual("policy_denied", result["playerStatus"])
        self.assertEqual("not_requested", result["mediaStatus"])
        with patch.object(requests, "get", side_effect=self.bridge.PolicyDenied("EGRESS_DENIED")):
            self.payload.sample_player({"handle": lambda *args: {
                "parse": 0, "url": "https://reader.example.com/"}}, self.detail, result)
        self.assertEqual("returned", result["playerStatus"])
        self.assertEqual("policy_denied", result["mediaStatus"])
        projected = P.summary({"kind": "result", "summary": {
            **result, "url": "PRIVATE", "playerStatus": "PRIVATE",
            "mediaStatus": {"secret": "PRIVATE"}}})
        self.assertNotIn("PRIVATE", json.dumps(projected))
        self.assertEqual("invalid_output", projected["playerStatus"])


if __name__ == "__main__":
    unittest.main()
