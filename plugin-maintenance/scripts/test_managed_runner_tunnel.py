"""Local transport checks; fake SSH, no NAS, models or submitted source execution."""
from __future__ import annotations

import copy
import io
import json
import os
from pathlib import Path
import shlex
import socket
import struct
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import managed_runner_tunnel as T

PASSWORD = "PRIVATE_PASSWORD_MARKER"
SOURCE = "PRIVATE_SOURCE_MARKER"


def route(port=19001, ident="image-api"):
    return {"id": ident, "localPort": port, "remoteSocket": "/srv/" + ident + "/executor.sock",
            "instanceId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "imageId": "sha256:" + "c" * 64,
            "bundleSha256": "e" * 64, "egressPolicySha256": "a" * 64, "httpImageId": ""}


def identity(selected):
    return {"schemaVersion": 1, "authority": "managed_source_broker_configuration",
            **{key: selected[key] for key in ("instanceId", "imageId", "bundleSha256", "egressPolicySha256")},
            "recoveryRequired": False}


def reply(value):
    body = json.dumps(value).encode()
    return struct.pack("!I", len(body)) + body


class Channel:
    def __init__(self, client):
        self.client = client
        self.response = b""
        self.closed = False

    def settimeout(self, _timeout):
        pass

    def exec_command(self, command):
        self.client.commands.append(command)
        self.endpoint = shlex.split(command)[-1]

    def sendall(self, body):
        expected = (PASSWORD + "\n").encode()
        assert body.startswith(expected)
        body = body[len(expected):]
        value = json.loads(body[4:])
        self.client.frames.append(body)
        if value["operation"] == "identity":
            observed = identity(self.client.routes[self.endpoint])
            observed.update(self.client.identity_changes)
            self.response = reply({"ok": True, "result": observed})
        else:
            self.response = reply({"ok": True, "result": {"echo": value}})
            if self.client.lose_reply:
                self.response = b""

    def shutdown_write(self):
        pass

    def makefile(self, _mode):
        return io.BytesIO(self.response)

    def close(self):
        self.closed = True


class Client:
    def __init__(self, routes):
        self.routes = {item["remoteSocket"]: item for item in routes}
        self.active, self.closed, self.lose_reply = True, False, False
        self.frames, self.commands = [], []
        self.identity_changes = {}

    def connect(self, **options):
        assert options["password"] == PASSWORD
        assert options["look_for_keys"] is False and options["allow_agent"] is False

    def get_transport(self):
        return None if self.closed else self

    def is_active(self):
        return self.active

    def set_keepalive(self, _seconds):
        pass

    def open_session(self, timeout):
        return Channel(self)

    def close(self):
        self.closed = True


class TunnelTest(unittest.TestCase):
    def test_live_retirement_closes_only_removed_listener_without_retargeting_survivor(self):
        def port():
            with socket.socket() as peer:
                peer.bind(("127.0.0.1", 0))
                return peer.getsockname()[1]
        first, second = route(port()), route(port(), "new-origin")
        self.value["runners"] = [first, second]
        transport, _, _ = self.transport([Client([first, second])])
        tunnels = T.Tunnels(transport)
        try:
            tunnels.start()
            self.value["runners"] = [second]
            self.config()
            self.assertTrue(tunnels.refresh())
            self.assertEqual([second], [server.route for server in tunnels.servers])
            with self.assertRaises(OSError):
                socket.create_connection(("127.0.0.1", first["localPort"]), timeout=.2)
            with socket.create_connection(("127.0.0.1", second["localPort"])) as peer:
                peer.sendall(T.frame(b'{"operation":"status"}'))
                self.assertEqual("status", json.loads(T.read_frame(peer.makefile("rb"), T.MAX_REPLY)[4:])
                                 ["result"]["echo"]["operation"])
        finally:
            tunnels.close()

    def test_live_refresh_adds_listener_and_preserves_existing_listener(self):
        def port():
            with socket.socket() as peer:
                peer.bind(("127.0.0.1", 0))
                return peer.getsockname()[1]
        first, second = route(port()), route(port(), "new-origin")
        self.value["runners"] = [first]
        client = Client([first, second])
        transport, _, _ = self.transport([client])
        tunnels = T.Tunnels(transport)
        try:
            tunnels.start()
            self.value["runners"].append(second)
            self.config()
            self.assertTrue(tunnels.refresh())
            self.assertFalse(tunnels.refresh())
            for selected in (first, second):
                with socket.create_connection(("127.0.0.1", selected["localPort"])) as peer:
                    peer.sendall(T.frame(b'{"operation":"status"}'))
                    received = T.read_frame(peer.makefile("rb"), T.MAX_REPLY)
                    self.assertEqual("status", json.loads(received[4:])["result"]["echo"]["operation"])
        finally:
            tunnels.close()

    def test_reload_cannot_retarget_existing_route_or_change_ssh_configuration(self):
        transport, _, _ = self.transport()
        tunnels = T.Tunnels(transport)
        for change in ("route", "ssh"):
            value = copy.deepcopy(self.value)
            if change == "route":
                value["runners"][0]["instanceId"] = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            else:
                value["ssh"]["hostEnv"] = "OTHER_HOST"
            self.config(value)
            with self.subTest(change=change), self.assertRaisesRegex(T.Failure, "TUNNEL_RELOAD_NOT_ADDITIVE"):
                tunnels.refresh()
        self.assertFalse(transport.closed)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "tunnels.json"
        self.value = {"schemaVersion": 1, "ssh": {
            "hostEnv": "TEST_NAS_HOST", "userEnv": "TEST_NAS_USER",
            "passwordEnv": "TEST_NAS_PASSWORD", "sudoPasswordEnv": "TEST_NAS_PASSWORD"},
            "runners": [route()]}
        self.env = patch.dict(os.environ, {"TEST_NAS_HOST": "nas.test", "TEST_NAS_USER": "operator",
                                         "TEST_NAS_PASSWORD": PASSWORD})
        self.env.start()
        self.transports = []

    def tearDown(self):
        for transport in self.transports:
            transport.close()
        self.env.stop()
        self.temp.cleanup()

    def config(self, value=None):
        self.path.write_text(json.dumps(self.value if value is None else value), encoding="utf-8")
        return T.Config(self.path)

    def transport(self, clients=None):
        config = self.config()
        clients = list(clients or [Client(config.routes)])
        factory = iter(clients)
        events = []
        transport = T.Transport(config, client_factory=lambda: next(factory), emit=events.append)
        self.transports.append(transport)
        return transport, clients, events

    def test_config_contains_only_environment_names_and_fixed_socket(self):
        config = self.config()
        self.assertNotIn(PASSWORD, self.path.read_text())
        self.assertEqual(PASSWORD, config.credentials()["password"])
        self.assertEqual(22, config.credentials()["port"])

    def test_rejects_duplicate_json_fields(self):
        self.path.write_text('{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaises(T.Failure):
            T.Config(self.path)

    def test_rejects_duplicate_ports_and_source_command_configuration(self):
        for edit in ("port", "command"):
            value = copy.deepcopy(self.value)
            if edit == "port":
                value["runners"].append(route(19001, "second"))
            else:
                value["ssh"]["command"] = "arbitrary command"
            with self.subTest(edit=edit), self.assertRaises(T.Failure):
                self.config(value)

    def test_rejects_public_bind_or_noncanonical_socket(self):
        for extra in ({"bind": "0.0.0.0"}, {"remoteSocket": "/srv/../other/executor.sock"}):
            value = copy.deepcopy(self.value)
            value["runners"][0].update(extra)
            with self.subTest(extra=extra), self.assertRaises(T.Failure):
                self.config(value)

    def test_missing_credential_fails_without_printing_value(self):
        config = self.config()
        with patch.dict(os.environ, {"TEST_NAS_PASSWORD": ""}):
            with self.assertRaisesRegex(T.Failure, "^TUNNEL_ENVIRONMENT_REQUIRED$"):
                config.credentials()

    def test_explicit_optional_port_and_noninteger_schema(self):
        value = copy.deepcopy(self.value)
        value["ssh"]["portEnv"] = "TEST_NAS_PORT"
        with patch.dict(os.environ, {"TEST_NAS_PORT": "2222"}):
            self.assertEqual(2222, self.config(value).credentials()["port"])
        value["schemaVersion"] = True
        with self.assertRaises(T.Failure):
            self.config(value)

    def test_frame_roundtrip_and_limits(self):
        body = b"a" * T.MAX_FRAME
        self.assertEqual(T.frame(body), T.read_frame(io.BytesIO(T.frame(body)), T.MAX_FRAME))
        for wire in (struct.pack("!I", 0), struct.pack("!I", T.MAX_FRAME + 1), b"\x00",
                     struct.pack("!I", 3) + b"x"):
            with self.subTest(length=len(wire)), self.assertRaises(T.Failure):
                T.read_frame(io.BytesIO(wire), T.MAX_FRAME)

    def test_identity_checks_every_bound_dimension(self):
        selected = route()
        for key, replacement in (("instanceId", "wrong"), ("imageId", "wrong"),
                                 ("bundleSha256", "wrong"), ("egressPolicySha256", "wrong"),
                                 ("recoveryRequired", "false"), ("httpImageId", "wrong")):
            observed = identity(selected)
            observed[key] = replacement
            with self.subTest(key=key), self.assertRaises(T.Failure):
                T.identity_reply(reply({"ok": True, "result": observed}), selected)

    def test_recovery_required_is_observed_not_replaced_or_replayed(self):
        observed = identity(route())
        observed["recoveryRequired"] = True
        checked = T.identity_reply(reply({"ok": True, "result": observed}), route())
        self.assertFalse(checked["ready"])
        self.assertFalse(checked["sourceExecuted"])

    def test_transparent_request_and_no_password_in_command_or_events(self):
        transport, clients, events = self.transport()
        request = T.frame(json.dumps({"operation": "start", "sourceBase64": SOURCE}).encode())
        received = transport.exchange(route(), request)
        self.assertEqual(SOURCE, json.loads(received[4:])["result"]["echo"]["sourceBase64"])
        self.assertEqual(request, clients[0].frames[-1])
        self.assertNotIn(PASSWORD, "\n".join(clients[0].commands))
        self.assertNotIn(SOURCE, json.dumps(events))
        self.assertNotIn(PASSWORD, json.dumps(events))

    def test_dead_transport_reconnects_before_next_request(self):
        clients = [Client([route()]), Client([route()])]
        transport, _, _ = self.transport(clients)
        request = T.frame(b'{"operation":"status"}')
        transport.exchange(route(), request)
        clients[0].close()
        transport.exchange(route(), request)
        self.assertEqual(2, transport.connections)
        self.assertEqual(["identity", "status"], [json.loads(body[4:])["operation"] for body in clients[1].frames])

    def test_lost_reply_never_resends_start(self):
        clients = [Client([route()]), Client([route()])]
        transport, _, _ = self.transport(clients)
        clients[0].lose_reply = True
        request = T.frame(b'{"operation":"start"}')
        with self.assertRaises(T.Failure):
            transport.exchange(route(), request)
        self.assertEqual(0, len(clients[1].frames))
        transport.exchange(route(), T.frame(b'{"operation":"status"}'))
        sent = [json.loads(body[4:])["operation"] for client in clients for body in client.frames]
        self.assertEqual(1, sent.count("start"))
        self.assertEqual(1, sent.count("status"))

    def test_reconnected_identity_mismatch_blocks_source_delivery(self):
        clients = [Client([route()]), Client([route()])]
        transport, _, _ = self.transport(clients)
        transport.exchange(route(), T.frame(b'{"operation":"status"}'))
        clients[0].close()
        clients[1].identity_changes["instanceId"] = "wrong"
        with self.assertRaisesRegex(T.Failure, "TUNNEL_BROKER_IDENTITY_MISMATCH"):
            transport.exchange(route(), T.frame(b'{"operation":"start"}'))
        self.assertEqual(["identity"], [json.loads(body[4:])["operation"] for body in clients[1].frames])

    def test_one_offline_broker_does_not_block_other_route(self):
        selected = [route(), route(19002, "second")]
        self.value["runners"] = selected
        clients = [Client(selected), Client(selected)]
        transport, _, _ = self.transport(clients)
        original = transport.relay
        def relay(client, current, request):
            if current["id"] == "second":
                raise OSError("PRIVATE_BROKER_OFFLINE")
            return original(client, current, request)
        with patch.object(transport, "relay", side_effect=relay):
            observed = transport.check()
            self.assertTrue(observed[0]["ready"])
            self.assertFalse(observed[1]["ready"])
            response = transport.exchange(selected[0], T.frame(b'{"operation":"status"}'))
            self.assertEqual("status", json.loads(response[4:])["result"]["echo"]["operation"])
        self.assertNotIn("start", [json.loads(body[4:])["operation"]
                                    for client in clients for body in client.frames])

    def test_failed_channel_does_not_close_another_active_lease(self):
        clients = [Client([route()]), Client([route()])]
        transport, _, _ = self.transport(clients)
        with transport.lease() as leased:
            clients[0].lose_reply = True
            with self.assertRaises(T.Failure):
                transport.exchange(route(), T.frame(b'{"operation":"status"}'))
            self.assertFalse(leased.closed)
            transport.exchange(route(), T.frame(b'{"operation":"status"}'))
            self.assertFalse(leased.closed)
        self.assertTrue(clients[0].closed)

    def test_group_serves_loopback_and_closes_listener(self):
        with socket.socket() as spare:
            spare.bind(("127.0.0.1", 0))
            port = spare.getsockname()[1]
        self.value["runners"][0]["localPort"] = port
        transport, _, _ = self.transport([Client([route(port)])])
        group = T.Tunnels(transport)
        try:
            self.assertTrue(group.start()[0]["connected"])
            with socket.create_connection(("127.0.0.1", port), timeout=2) as client:
                client.sendall(T.frame(b'{"operation":"status"}'))
                with client.makefile("rb") as stream:
                    observed = T.read_frame(stream, T.MAX_REPLY)
            self.assertEqual("status", json.loads(observed[4:])["result"]["echo"]["operation"])
            self.assertEqual("127.0.0.1", group.servers[0].server_address[0])
        finally:
            group.close()
        self.assertTrue(transport.closed)

    def test_second_port_collision_rolls_back_first_listener(self):
        with socket.socket() as occupied, socket.socket() as spare:
            occupied.bind(("127.0.0.1", 0))
            occupied.listen()
            spare.bind(("127.0.0.1", 0))
            free_port = spare.getsockname()[1]
            busy_port = occupied.getsockname()[1]
        # Keep only the occupied listener alive during startup.
        with socket.socket() as occupied:
            occupied.bind(("127.0.0.1", busy_port))
            occupied.listen()
            self.value["runners"] = [route(free_port), route(busy_port, "second")]
            transport, _, _ = self.transport([Client(self.value["runners"])])
            group = T.Tunnels(transport)
            with self.assertRaises(OSError):
                group.start()
            self.assertEqual([], group.servers)
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", free_port))

    def test_offline_nas_does_not_prevent_management_startup(self):
        with socket.socket() as spare:
            spare.bind(("127.0.0.1", 0))
            port = spare.getsockname()[1]
        selected = route(port)
        transport = SimpleNamespace(
            config=SimpleNamespace(routes=[selected]), emit=lambda event: None,
            check=lambda: (_ for _ in ()).throw(OSError("PRIVATE_CONNECTION_ERROR")),
            close=lambda: None)
        group = T.Tunnels(transport)
        try:
            observed = group.start()
            self.assertFalse(observed[0]["ready"])
            self.assertEqual("TUNNEL_CONNECTION_UNAVAILABLE", observed[0]["code"])
            self.assertTrue(group.threads[0].is_alive())
        finally:
            group.close()

    def test_replies_support_bounded_session_payloads_above_64k(self):
        body = b"x" * 100_000
        wire = struct.pack("!I", len(body)) + body
        self.assertEqual(wire, T.read_frame(io.BytesIO(wire), T.MAX_REPLY))


if __name__ == "__main__":
    unittest.main()
