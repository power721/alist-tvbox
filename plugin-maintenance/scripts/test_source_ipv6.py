"""IPv6 source contracts with fixed DNS replies and socket pairs, no external requests."""
import base64
import json
import socket
import threading
import time
import unittest
from unittest.mock import MagicMock, Mock, patch

import source_egress as E
from source_origin_discovery import http_origin, source_origins


IP = "2606:4700:4700::1111"


def policy(origin="https://catalog.example.com"):
    return {"schemaVersion": 2, "origin": origin, "exactUrls": [origin + "/catalog"],
            "paths": [], "methods": ["GET"], "queryKeys": [], "maxRequests": 2,
            "maxResponseBytes": 1024, "maxTotalResponseBytes": 2048,
            "minIntervalSeconds": 1, **E.address_pins([IP])}


class Ipv6Test(unittest.TestCase):
    def test_static_and_configured_origins_preserve_ipv6_brackets_and_ports(self):
        expected = "https://[" + IP + "]:9443"
        self.assertEqual(expected, http_origin(expected + "/catalog?x=1"))
        self.assertEqual([expected], source_origins("HOST=" + repr(expected + "/catalog")))
        self.assertEqual("http://[" + IP + "]", http_origin("http://[" + IP + "]/"))

    def test_private_scoped_multicast_and_mapped_private_addresses_are_never_origins(self):
        for ip in ("::", "::1", "fc00::1", "fe80::1", "fe80::1%25eth0", "fec0::1",
                   "ff02::1", "2001:db8::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1",
                   "2002:7f00:1::1"):
            with self.subTest(ip=ip):
                self.assertFalse(E.public_address(ip))
                self.assertIsNone(http_origin("https://[" + ip + "]/"))
                with self.assertRaises(E.Denied):
                    E.public_dns_addresses(ip)

    def test_policy_supports_ipv6_only_and_mixed_pins_without_changing_ipv4_format(self):
        self.assertEqual({"pinnedIpv4": ["8.8.8.8"]}, E.address_pins(["8.8.8.8"]))
        pins = E.address_pins(["8.8.8.8", IP])
        self.assertEqual({"pinnedIpv4": ["8.8.8.8"], "pinnedIpv6": [IP]}, pins)
        self.assertEqual(("8.8.8.8", IP), E.Broker({**policy(), **pins}).addresses)
        self.assertEqual((IP,), E.Broker(policy()).addresses)
        for changes in ({"pinnedIpv6": ["::1"]}, {"pinnedIpv6": ["8.8.8.8"]},
                        {"pinnedIpv6": []}, {"pinnedIpv6": [IP] * 17}):
            with self.subTest(changes=changes), self.assertRaises(E.Denied):
                E.policy_from({**policy(), **changes})

    def test_literal_ipv6_policy_requires_matching_pins(self):
        origin = "https://[" + IP + "]:9443"
        self.assertEqual((IP,), E.Broker(policy(origin)).addresses)
        with self.assertRaisesRegex(E.Denied, "EGRESS_POLICY_INVALID"):
            E.policy_from({**policy(origin), "pinnedIpv6": ["2606:4700:4700::1001"]})
        for url in ("https://[" + IP + "%25eth0]/", "https://user:secret@[" + IP + "]/",
                    "https://[" + IP + "]:0/", "https://[broken"):
            with self.subTest(url=url), self.assertRaises(E.Denied):
                E.http_authority(url)

    def test_literal_public_ipv6_needs_no_dns_query(self):
        with patch.object(E, "build_opener", side_effect=AssertionError("NO_DNS_FOR_LITERAL")):
            self.assertEqual([IP], E.public_dns_addresses(IP))

    def dns_reply(self, records, status=0):
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = json.dumps({"Status": status, "Answer": records}).encode()
        return response

    def test_aaaa_only_name_queries_aaaa_after_empty_a_and_canonicalizes_answers(self):
        opener = Mock()
        opener.open.side_effect = [self.dns_reply([]), self.dns_reply([
            {"type": 28, "data": "2606:4700:4700:0:0:0:0:1111"}, {"type": 28, "data": IP}])]
        with patch.object(E, "build_opener", return_value=opener):
            self.assertEqual([IP], E.public_dns_addresses("v6.example.com"))
        self.assertEqual(["https://dns.alidns.com/resolve?name=v6.example.com&type=A",
                          "https://dns.alidns.com/resolve?name=v6.example.com&type=AAAA"],
                         [call.args[0] for call in opener.open.call_args_list])

    def test_private_or_mixed_aaaa_answers_fail_with_ipv6_diagnostics(self):
        for addresses in (["::1"], [IP, "fd00::1"]):
            opener = Mock()
            opener.open.side_effect = [self.dns_reply([]), self.dns_reply([
                {"type": 28, "data": address} for address in addresses])]
            with self.subTest(addresses=addresses), patch.object(E, "build_opener", return_value=opener), \
                    self.assertRaises(E.Denied) as caught:
                E.public_dns_addresses("v6.example.com")
            self.assertEqual("EGRESS_NONPUBLIC_DNS", caught.exception.code)
            self.assertEqual("AAAA", caught.exception.dns_observation["recordType"])
            self.assertEqual(len(addresses) - 1, caught.exception.dns_observation["publicIpv6Count"])
            self.assertEqual(0, caught.exception.dns_observation["publicIpv4Count"])

    def test_invalid_aaaa_address_family_is_not_treated_as_public_ipv6(self):
        opener = Mock()
        opener.open.side_effect = [self.dns_reply([]), self.dns_reply([{"type": 28, "data": "8.8.8.8"}])]
        with patch.object(E, "build_opener", return_value=opener), self.assertRaises(E.Denied) as caught:
            E.public_dns_addresses("v6.example.com")
        self.assertEqual("EGRESS_DNS_FAILED", caught.exception.code)

    def test_aaaa_does_not_override_nxdomain_resolver_errors_or_private_a(self):
        for response in (self.dns_reply([], 3), self.dns_reply([], 2),
                         self.dns_reply([{"type": 1, "data": "127.0.0.1"}])):
            opener = Mock()
            opener.open.return_value = response
            with patch.object(E, "build_opener", return_value=opener), self.assertRaises(E.Denied):
                E.public_dns_addresses("v6.example.com")
            opener.open.assert_called_once()

    def test_system_resolver_uses_both_families_and_rejects_mixed_private_addresses(self):
        rows = [(socket.AF_INET6, socket.SOCK_STREAM, 6, "", (IP, 9443, 0, 0))]
        resolver = Mock(return_value=rows)
        self.assertEqual([IP], E.approved_addresses("v6.example.com", resolver, port=9443))
        resolver.assert_called_once_with("v6.example.com", 9443,
                                         family=socket.AF_UNSPEC, type=socket.SOCK_STREAM)
        resolver.return_value = rows + [(socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("::1", 9443, 0, 0))]
        with self.assertRaises(E.Denied):
            E.approved_addresses("v6.example.com", resolver, port=9443)

    def routes_policy(self):
        configured = policy()
        return {key: value for key, value in configured.items() if key in E.LIMITS} | {
            "schemaVersion": 3, "routes": [{"origin": configured["origin"],
                "exactUrls": configured["exactUrls"], "requestScope": "origin",
                "pinnedIpv4": ["8.8.8.8"]}], "responseRedirects": "public_http_https",
            "responseApiOrigins": "public_http_https", "playerResources": "public_http_https"}

    def test_response_and_player_resource_routes_keep_ipv6_pins_and_existing_budgets(self):
        configured = self.routes_policy()
        broker = E.Routes(configured)
        issuer = broker.routes["https://catalog.example.com"]
        with patch.object(E, "public_dns_addresses", return_value=[IP]):
            broker.register_public_origin("https://api.example.com", issuer)
            broker.register_player_resource("https://cdn.example.com/movie.mp4", {})
        for origin in ("https://api.example.com", "https://cdn.example.com"):
            self.assertEqual([], broker.routes[origin].policy["pinnedIpv4"])
            self.assertEqual([IP], broker.routes[origin].policy["pinnedIpv6"])
            self.assertEqual(configured["maxRequests"], broker.routes[origin].policy["maxRequests"])
        self.assertEqual(0, broker.count)
        self.assertEqual(0, broker.total)

    def test_pending_origin_can_resolve_to_ipv6_and_private_ipv6_remains_denied(self):
        from test_source_egress import FakeConnection
        configured = self.routes_policy()
        configured["pendingOrigins"] = ["https://pending.example.com"]
        broker = E.Routes(configured)
        with patch.object(E, "public_dns_addresses", return_value=[IP]), \
                patch.object(E.http.client, "HTTPSConnection", return_value=FakeConnection()), \
                patch.object(E.time, "sleep"):
            response = broker.fetch({"url": "https://pending.example.com/catalog", "method": "GET",
                                     "headers": {}, "body": ""})
        self.assertEqual(200, response["status"])
        self.assertEqual((IP,), broker.routes["https://pending.example.com"].addresses)
        self.assertEqual(1, broker.count)
        with self.assertRaisesRegex(E.Denied, "EGRESS_NONPUBLIC_TARGET_DENIED"):
            broker.fetch({"url": "https://[::1]/catalog", "method": "GET", "headers": {}, "body": ""})

    def test_actual_http_connection_keeps_bracketed_host_and_normalizes_peer_address(self):
        origin = "http://[" + IP + "]:8080"
        client, server = socket.socketpair()
        self.addCleanup(client.close)
        self.addCleanup(server.close)
        client.settimeout(3)
        server.settimeout(3)
        received = []

        class Socket:
            def getpeername(self):
                return ("2606:4700:4700:0:0:0:0:1111", 8080, 0, 0)

            def __getattr__(self, name):
                return getattr(client, name)

        def respond():
            body = b""
            while b"\r\n\r\n" not in body:
                body += server.recv(4096)
            received.append(body)
            server.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Type: text/plain\r\n\r\nok")

        thread = threading.Thread(target=respond, daemon=True)
        thread.start()
        connector = Mock(return_value=Socket())
        broker = E.Broker(policy(origin), connector=connector)
        response = broker.fetch({"method": "GET", "url": origin + "/catalog", "headers": {}, "body": ""})
        thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertEqual(b"ok", base64.b64decode(response["body"]))
        self.assertIn(("Host: [" + IP + "]:8080\r\n").encode(), received[0])
        self.assertEqual((IP, 8080), connector.call_args.args[0])

    def test_https_pins_ipv6_but_retains_domain_for_certificate_and_sni(self):
        sock = Mock()
        sock.getpeername.return_value = (IP, 443, 0, 0)
        context = Mock()
        broker = E.Broker(policy(), connector=Mock(return_value=sock))
        with patch.object(E.ssl, "create_default_context", return_value=context):
            connection, index = broker.connect(policy()["exactUrls"][0], time.monotonic() + 3,
                                                time.monotonic())
        context.wrap_socket.assert_called_once_with(sock, server_hostname="catalog.example.com")
        self.assertEqual(0, index)
        self.assertEqual((IP, 443), broker.connector.call_args.args[0])
        connection.close()


if __name__ == "__main__":
    unittest.main()
