"""Same-host integration tests; no NAS, source execution, or Docker commands."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import managed_local_setup as L
import managed_source_setup as S


class LocalSetupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.transport = L.LocalTransport(self.root, 65532)
        self.transport.api.mkdir()
        self.template = {"id": "template", "localPort": 19003,
                         "remoteSocket": str(self.root / "template/executor.sock"),
                         "instanceId": "11111111-1111-4111-8111-111111111111",
                         "imageId": "sha256:" + "a" * 64, "bundleSha256": "b" * 64,
                         "egressPolicySha256": "", "httpImageId": ""}
        (self.root / "channels.json").write_text(json.dumps({"schemaVersion": 1, "runners": [self.template]}))
        (self.root / "routes.json").write_text('{"schemaVersion":1,"runners":[]}')
        content = "ORIGIN='https://www.cctv.com'\nraise RuntimeError('MUST_NOT_EXECUTE')\n"
        self.request = {"sourceId": "22222222-2222-4222-8222-222222222222", "content": content,
                        "sourceSha256": hashlib.sha256(content.encode()).hexdigest(), "bundleSha256": "b" * 64}

    def installed(self, config, plan, *args):
        return {"ok": True, "remoteSocket": str(self.root / ("w-auto-" + plan["instanceId"].replace("-", "")[:12]) / "executor.sock"),
                "instanceId": plan["instanceId"], "imageId": "sha256:" + "a" * 64,
                "bundleSha256": plan["bundleSha256"], "egressPolicySha256": "c" * 64,
                "httpImageId": "", "approvedOrigins": plan["origins"], "unresolvedOrigins": [],
                "sourceExecuted": False}

    def test_reuses_existing_setup_and_publishes_internal_routes_without_ssh_or_source_execution(self):
        with patch.object(self.transport, "install", side_effect=self.installed) as install, patch.object(self.transport, "wait"):
            result = self.transport.prepare(self.request)
        self.assertTrue(result["ready"])
        self.assertFalse(result["sourceExecuted"])
        plan = install.call_args.args[1]
        self.assertNotIn("content", plan)
        self.assertEqual(["https://www.cctv.com"], plan["origins"])
        published = json.loads((self.transport.api / "routes.json").read_bytes())
        self.assertEqual(self.transport.endpoint({"instanceId": plan["instanceId"]}), published["runners"][0]["socket"])
        self.assertNotIn("ssh", (self.root / "channels.json").read_text())
        self.assertNotIn("MUST_NOT_EXECUTE", json.dumps(plan))

    def test_lost_setup_reply_reuses_same_intent_and_never_adds_another_route(self):
        with patch.object(self.transport, "install", side_effect=self.installed) as install, patch.object(self.transport, "wait"):
            first = self.transport.prepare(self.request)
            second = self.transport.prepare(self.request)
        self.assertEqual(first, second)
        self.assertEqual(install.call_args_list[0].args[1], install.call_args_list[1].args[1])
        self.assertEqual(1, len(json.loads((self.root / "routes.json").read_bytes())["runners"]))
        self.assertEqual(1, len(list((self.root / "source-channels").glob("*.json"))))

    def test_public_configuration_uses_the_existing_fingerprint_contract(self):
        self.request["sourceConfig"] = {"base_url": "https://www.cctv.com/catalog?marker=NOT_IN_PLAN"}
        self.request["sourceConfigSha256"] = S.digest(self.request["sourceConfig"])
        with patch.object(self.transport, "install", side_effect=self.installed) as install, patch.object(self.transport, "wait"):
            result = self.transport.prepare(self.request)
        self.assertEqual(self.request["sourceConfigSha256"], result["sourceConfigSha256"])
        self.assertNotIn("NOT_IN_PLAN", json.dumps(install.call_args.args[1]))

    def test_setup_rejects_extra_fields_private_targets_and_changed_source_before_install(self):
        for change in ({"command": "whoami"}, {"sourceSha256": "0" * 64},
                       {"sourceConfig": {"url": "https://127.0.0.1"}, "sourceConfigSha256": "0" * 64}):
            request = {**self.request, **change}
            with self.subTest(change=list(change)), patch.object(self.transport, "install") as install:
                with self.assertRaises((S.Failure, ValueError)):
                    self.transport.prepare(request)
                install.assert_not_called()

    def test_registry_and_previous_channel_cannot_escape_the_owned_host_root(self):
        value = {"schemaVersion": 1, "runners": [copy.deepcopy(self.template)]}
        value["runners"][0]["remoteSocket"] = "/other-project/executor.sock"
        (self.root / "channels.json").write_text(json.dumps(value))
        with self.assertRaisesRegex(S.Failure, "SOURCE_SETUP_CONFIGURATION_INVALID"):
            self.transport.config(self.root / "channels.json")
        plan = {"previousTunnel": {"remoteSocket": "/other-project/executor.sock"}}
        with patch.object(L.subprocess, "run") as run, self.assertRaises(S.Failure):
            self.transport.install(None, plan)
        run.assert_not_called()

    def test_local_install_runs_only_trusted_code_with_data_on_stdin(self):
        plan = {"sourceId": self.request["sourceId"], "sourceSha256": self.request["sourceSha256"],
                "bundleSha256": "b" * 64, "instanceId": self.template["instanceId"],
                "origins": ["https://www.cctv.com"], "templateSocket": self.template["remoteSocket"]}
        expected = self.installed(None, plan)
        with patch.object(L.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(expected).encode())) as run:
            observed = self.transport.install(None, plan)
        self.assertEqual(expected, observed)
        self.assertEqual(S.REMOTE, run.call_args.args[0][-1])
        self.assertEqual(plan["sourceSha256"], json.loads(run.call_args.kwargs["input"])["sourceSha256"])
        self.assertNotIn("content", json.loads(run.call_args.kwargs["input"]))

    def test_failed_install_preserves_original_routes_for_recovery(self):
        before = (self.root / "routes.json").read_bytes()
        with patch.object(self.transport, "install", side_effect=S.Failure("SOURCE_SETUP_DNS_UNAVAILABLE")):
            with self.assertRaisesRegex(S.Failure, "SOURCE_SETUP_DNS_UNAVAILABLE"):
                self.transport.prepare(self.request)
        self.assertEqual(before, (self.root / "routes.json").read_bytes())
        self.assertEqual(1, len(list((self.root / "source-channels").glob("*.json"))))

    def test_manager_restart_reuses_a_live_broker_without_replaying_any_job(self):
        import managed_broker_service as broker
        route = {**self.template, "remoteSocket": str(self.root / "w-owned/executor.sock")}
        config = {key: route[key] for key in ("instanceId", "imageId", "bundleSha256")}
        with patch.object(broker, "configuration", return_value=(self.root / "config", config, "d" * 64)), \
                patch.object(broker, "request") as status, patch.object(broker, "start") as start, \
                patch.object(broker, "recover") as recover, patch.object(self.transport, "wait") as wait:
            self.transport.restore(route)
        status.assert_called_once()
        start.assert_not_called()
        recover.assert_not_called()
        wait.assert_called_once_with(route)

    def test_stopped_owned_broker_is_restarted_without_deleting_history(self):
        import managed_broker_service as broker
        route = {**self.template, "remoteSocket": str(self.root / "w-owned/executor.sock")}
        config = {key: route[key] for key in ("instanceId", "imageId", "bundleSha256")}
        with patch.object(broker, "configuration", return_value=(self.root / "config", config, "d" * 64)), \
                patch.object(broker, "request", side_effect=ConnectionRefusedError), patch.object(broker, "start") as start, \
                patch.object(broker, "recover") as recover, patch.object(self.transport, "wait"):
            self.transport.restore(route)
        start.assert_called_once_with(self.root / "config", config, "d" * 64)
        recover.assert_not_called()


if __name__ == "__main__":
    unittest.main()
