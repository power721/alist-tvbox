"""Host-owned credentials: real local HTTP serialization, no production or model calls."""
import copy
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import socket
import threading
import unittest
from unittest.mock import patch

import source_egress as E
from test_source_egress import policy, request, FakeConnection


class CredentialTransportTest(unittest.TestCase):
    def test_saved_cookie_and_token_reach_http_peer_and_server_cookie_rotation_wins(self):
        observed = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                observed.append((self.headers.get("Cookie"), self.headers.get("Authorization")))
                body = b'{"ok":true}'
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Set-Cookie", "session=rotated-private; Path=/; Secure")
                self.end_headers()
                self.wfile.write(body)

        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.02})
        thread.start()

        class Loopback(http.client.HTTPConnection):
            def connect(self):
                self.sock = socket.create_connection(server.server_address, timeout=2)

        mediator = E.Broker(policy())
        mediator.bind_credentials({"origin": policy()["origin"], "cookie": "session=saved-private",
                                   "authorization": "Bearer private-token"})
        value = {**request(), "method": "GET", "body": "",
                 "headers": {"Cookie": "stale=source", "Authorization": "stale-source"}}
        try:
            with patch.object(E.http.client, "HTTPSConnection",
                              side_effect=lambda *a, **k: Loopback("reader.example.com", 443)), \
                    patch.object(E.time, "sleep"):
                mediator.fetch(value)
                mediator.fetch(value)
            self.assertEqual([("session=saved-private", "Bearer private-token"),
                              ("session=rotated-private", "Bearer private-token")], observed)
            self.assertNotIn("private", json.dumps(mediator.events))
            self.assertNotIn("hostCredentials", json.dumps(mediator.policy))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(3)

    def test_credentials_never_follow_another_origin(self):
        mediator = E.Broker(policy())
        original = copy.deepcopy(mediator.policy)
        mediator.bind_credentials({"origin": "https://other.example.com", "cookie": "session=private",
                                   "authorization": "Bearer private"})
        connection = FakeConnection()
        with patch.object(E.http.client, "HTTPSConnection", return_value=connection):
            mediator.fetch({**request(), "method": "GET", "body": "", "headers": {}})
        self.assertNotIn("Cookie", connection.sent[3])
        self.assertNotIn("Authorization", connection.sent[3])
        self.assertEqual(original, mediator.policy)

    def test_invalid_header_and_cookie_scope_are_not_accepted(self):
        for change in ({"authorization": "Bearer private\r\nX-Test: value"},
                       {"cookie": "session=private; Domain=other.example.com"},
                       {"origin": "https://reader.example.com/path"}):
            with self.subTest(change=change), self.assertRaises(E.Denied):
                E.Broker(policy()).bind_credentials({
                    "origin": policy()["origin"], "cookie": "session=private", "authorization": "", **change})


if __name__ == "__main__":
    unittest.main()
