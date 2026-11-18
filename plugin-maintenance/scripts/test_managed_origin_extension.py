"""Exercise channel switching and resumable retirement, never execute a spider."""
import copy
import json
import unittest
from unittest.mock import patch

import managed_origin_extension as O
import managed_source_setup as S
from test_managed_source_setup import SetupTest


class OriginExtensionTest(SetupTest):
    # Only this class's new tests are selected for this change.
    def configure_extension(self, shared=False):
        old = self.tunnel_config["runners"][0]
        self.request["verifiedOriginRoute"] = {
            "candidateOrigin": "https://alternate.example.com", "requestedPageUrlSha256": "1" * 64,
            "candidatePageUrlSha256": "2" * 64, "businessChainVerified": False, "failureCauseEstablished": False}
        self.old_route = {"id": old["id"], "label": "source", "sourceIds": [self.request["sourceId"]],
                          "socket": "tcp://127.0.0.1:" + str(old["localPort"]), **{k: old[k] for k in (
                              "instanceId", "imageId", "egressPolicySha256", "httpImageId")}}
        if shared:
            self.old_route["sourceIds"].append("22222222-2222-4222-8222-222222222222")
        self.route_config["runners"] = [self.old_route]
        self.routes.write_text(json.dumps(self.route_config))

    def installed_extension(self, _config, plan, code):
        self.assertEqual(O.REMOTE, code)
        if plan.get("retire"):
            return {"ok": True, "retired": True, "journalModified": False}
        old = plan["previousTunnel"]
        return {"ok": True, "sourceExecuted": False, "remoteSocket": "/srv/new/executor.sock",
                "instanceId": plan["instanceId"], "imageId": old["imageId"], "bundleSha256": old["bundleSha256"],
                "httpImageId": old["httpImageId"], "egressPolicySha256": "9" * 64,
                "approvedOrigins": ["https://site.example.com", "https://alternate.example.com"], "unresolvedOrigins": []}

    def test_extension_switches_only_source_and_reuses_receipt_without_remote_replay(self):
        self.configure_extension()
        with patch.object(S, "remote_prepare", side_effect=self.installed_extension) as remote, patch.object(S, "wait_tunnel"):
            result = S.prepare(self.request, self.routes, self.tunnels)
            self.assertEqual(2, remote.call_count)
            self.assertTrue(result["previousChannelRetired"])
            self.assertEqual(result, S.prepare(self.request, self.routes, self.tunnels))
            self.assertEqual(2, remote.call_count)
        routes = json.loads(self.routes.read_bytes())["runners"]
        tunnels = json.loads(self.tunnels.read_bytes())["runners"]
        self.assertEqual(1, len(routes))
        self.assertEqual(1, len(tunnels))
        self.assertEqual(result["routeId"], routes[0]["id"])
        self.assertEqual(self.old_route["imageId"], routes[0]["imageId"])
        self.assertNotIn("MUST_NOT_EXECUTE", next((self.root / "source-channels").glob("*.json")).read_text())

    def test_shared_channel_retains_other_source_and_is_not_stopped(self):
        self.configure_extension(shared=True)
        with patch.object(S, "remote_prepare", side_effect=self.installed_extension) as remote, patch.object(S, "wait_tunnel"):
            result = S.prepare(self.request, self.routes, self.tunnels)
            self.assertEqual(1, remote.call_count)
        self.assertFalse(result["previousChannelRetired"])
        routes = json.loads(self.routes.read_bytes())["runners"]
        self.assertEqual(["22222222-2222-4222-8222-222222222222"], routes[0]["sourceIds"])
        self.assertEqual(2, len(json.loads(self.tunnels.read_bytes())["runners"]))

    def test_failed_retirement_resumes_same_generation_without_creating_another_broker(self):
        self.configure_extension()
        def fail_retire(config, plan, code):
            if plan.get("retire"):
                raise S.Failure("SOURCE_SETUP_CHANNEL_BUSY")
            return self.installed_extension(config, plan, code)
        with patch.object(S, "remote_prepare", side_effect=fail_retire), patch.object(S, "wait_tunnel"):
            with self.assertRaisesRegex(S.Failure, "SOURCE_SETUP_CHANNEL_BUSY"):
                S.prepare(self.request, self.routes, self.tunnels)
        routed = self.routes.read_bytes()
        with patch.object(S, "remote_prepare", side_effect=self.installed_extension) as remote, patch.object(S, "wait_tunnel"):
            result = S.prepare(self.request, self.routes, self.tunnels)
            self.assertEqual(1, remote.call_count)
            self.assertTrue(remote.call_args.args[1]["retire"])
        self.assertTrue(result["previousChannelRetired"])
        self.assertEqual(routed, self.routes.read_bytes())

    def test_invalid_grants_do_not_create_intent_or_contact_remote(self):
        self.configure_extension()
        for origin in ("http://alternate.example.com", "https://127.0.0.1", "https://user:pw@site.example.com",
                       "https://alternate.example.com/private", "https://alternate.example.com?token=secret"):
            value = copy.deepcopy(self.request)
            value["verifiedOriginRoute"]["candidateOrigin"] = origin
            with self.subTest(origin=origin), patch.object(S, "remote_prepare") as remote:
                with self.assertRaises((S.Failure, ValueError)):
                    S.prepare(value, self.routes, self.tunnels)
                remote.assert_not_called()
        self.assertFalse((self.root / "source-channels").exists())
        compile(O.REMOTE, "trusted-origin-extension", "exec")

    def test_existing_origin_is_idempotent_without_channel_or_tunnel_changes(self):
        self.configure_extension()
        def unchanged(config, plan, code):
            installed = self.installed_extension(config, plan, code)
            installed.update(unchanged=True, egressPolicySha256=plan["previousTunnel"]["egressPolicySha256"])
            return installed
        before = self.routes.read_bytes(), self.tunnels.read_bytes()
        with patch.object(S, "remote_prepare", side_effect=unchanged) as remote, patch.object(S, "wait_tunnel"):
            result = S.prepare(self.request, self.routes, self.tunnels)
            self.assertEqual(result, S.prepare(self.request, self.routes, self.tunnels))
            self.assertEqual(1, remote.call_count)
        self.assertEqual(before, (self.routes.read_bytes(), self.tunnels.read_bytes()))
