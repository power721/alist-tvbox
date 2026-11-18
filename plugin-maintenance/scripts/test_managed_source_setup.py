"""Setup tests use local files and fake infrastructure; no crawler or NAS execution."""
from __future__ import annotations

import copy
import ast
import base64
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import managed_source_setup as S
import test_managed_runner_tunnel as F


class SetupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.routes = self.root / "routes.json"
        self.tunnels = self.root / "tunnels.json"
        self.route_config = {"schemaVersion": 1, "runners": []}
        self.tunnel_config = {"schemaVersion": 1, "ssh": {
            "hostEnv": "SETUP_HOST", "userEnv": "SETUP_USER", "passwordEnv": "SETUP_PASSWORD"},
            "runners": [F.route()]}
        self.routes.write_text(json.dumps(self.route_config))
        self.tunnels.write_text(json.dumps(self.tunnel_config))
        self.request = {"sourceId": "11111111-1111-4111-8111-111111111111",
                        "content": "raise RuntimeError('MUST_NOT_EXECUTE')\nORIGIN='https://site.example.com'\n",
                        "sourceSha256": "", "bundleSha256": "e" * 64}
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()

    def installed(self, _config, plan):
        return {"remoteSocket": "/srv/w-auto/executor.sock", "instanceId": plan["instanceId"],
                "imageId": plan.get("imageImports", {}).get("imageId", "sha256:" + "c" * 64),
                "bundleSha256": plan["bundleSha256"],
                "egressPolicySha256": "f" * 64, "httpImageId": "",
                "approvedOrigins": plan["origins"], "unresolvedOrigins": [], "sourceExecuted": False}

    def test_ast_only_skips_metadata_docstrings_and_retains_runtime_host_alternatives(self):
        content = '''
"""https://documentation.example.com"""
META = {"icon": "https://icons.example.com/a.png", "contact": "https://contact.example.com"}
ORIGIN = "https://reader.example.com"
HOSTS = ["https://reader.example.com", "https://second.example.com:9443"]
raise RuntimeError("NOT_EXECUTED")
def target(value):
    return f"https://api.example.com/details/{value}"
requests.get("https://direct.example.com")
'''
        self.assertEqual(["https://reader.example.com", "https://second.example.com:9443",
                          "https://direct.example.com", "https://api.example.com"], S.source_origins(content))

    def test_private_addresses_and_url_userinfo_do_not_grant_origins(self):
        for value in ("https://127.0.0.1/x", "https://user:pass@site.example.com",
                      "https://10.1.2.3", "http://127.0.0.1/x", "http://10.1.2.3",
                      "ftp://site.example.com", "https://site.example.com\\evil"):
            with self.subTest(value=value), self.assertRaises(S.Failure):
                S.source_origins("ORIGIN=" + repr(value))

    def test_composed_initial_origin_is_frozen_into_the_channel_plan_without_execution(self):
        self.request["content"] = (
            "SCHEME='https'\nDOMAIN='catalog.example.com'\n"
            "HOST=f'{SCHEME}://{DOMAIN}'\nraise RuntimeError('MUST_NOT_EXECUTE')\n")
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        with patch.object(S, "remote_prepare", side_effect=self.installed) as remote, patch.object(S, "wait_tunnel"):
            S.prepare(self.request, self.routes, self.tunnels)
        plan = remote.call_args.args[1]
        self.assertEqual(["https://catalog.example.com"], plan["origins"])
        self.assertEqual(self.request["sourceSha256"], plan["sourceSha256"])
        self.assertEqual(plan, json.loads(next((self.root / "source-channels").glob("*.json")).read_bytes()))

    def configure_source(self, config):
        self.request["sourceConfig"] = config
        self.request["sourceConfigSha256"] = S.digest(config)

    def test_ipv6_source_and_config_origins_bind_the_frozen_plan_without_execution(self):
        self.request["content"] = "HOST='https://[2606:4700:4700::1111]'\nraise RuntimeError('MUST_NOT_EXECUTE')"
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        self.configure_source({"backup_url": "http://[2606:4700:4700::1001]:8080/catalog"})
        with patch.object(S, "remote_prepare", side_effect=self.installed) as remote, patch.object(S, "wait_tunnel"):
            S.prepare(self.request, self.routes, self.tunnels)
        self.assertEqual(["https://[2606:4700:4700::1111]", "http://[2606:4700:4700::1001]:8080"],
                         remote.call_args.args[1]["origins"])
        self.assertEqual(self.request["sourceConfigSha256"], remote.call_args.args[1]["sourceConfigSha256"])

    def test_configuration_only_origin_reaches_the_plan_without_storing_configuration_values(self):
        self.request["content"] = "raise RuntimeError('MUST_NOT_EXECUTE')\n"
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        self.configure_source({"base_url": "http://configured.example.com:8080/catalog?sample=PRIVATE_MARKER",
                               "title": "\u914d\u7f6e\u6765\u6e90"})
        with patch.object(S, "remote_prepare", side_effect=self.installed) as remote, patch.object(S, "wait_tunnel"):
            result = S.prepare(self.request, self.routes, self.tunnels)
        plan = remote.call_args.args[1]
        self.assertEqual(["http://configured.example.com:8080"], plan["origins"])
        self.assertEqual(self.request["sourceConfigSha256"], plan["sourceConfigSha256"])
        self.assertEqual(plan["sourceConfigSha256"], result["sourceConfigSha256"])
        self.assertNotIn("sourceConfig", plan)
        self.assertNotIn("PRIVATE_MARKER", json.dumps(plan))
        self.assertEqual(plan, json.loads(next((self.root / "source-channels").glob("*.json")).read_bytes()))

    def test_configuration_origins_merge_after_source_literals_without_duplicates(self):
        self.configure_source({"base_url": "https://site.example.com/search?q=sample",
                               "alternate": "https://alternate.example.com/api",
                               "subtitle_enabled": True, "gateway_timeout": 30})
        with patch.object(S, "remote_prepare", side_effect=self.installed) as remote, patch.object(S, "wait_tunnel"):
            S.prepare(self.request, self.routes, self.tunnels)
        self.assertEqual(["https://site.example.com", "https://alternate.example.com"],
                         remote.call_args.args[1]["origins"])

    def test_invalid_or_changed_public_configuration_stops_before_infrastructure(self):
        for config in ({"cookie": "PRIVATE_MARKER"}, {"api_key": "PRIVATE_MARKER"},
                       {"gateway_timeout": True}, {"base_url": ["https://configured.example.com"]}):
            self.configure_source(config)
            with self.subTest(config=config), patch.object(S, "remote_prepare") as remote:
                with self.assertRaisesRegex(S.Failure, "^SOURCE_SETUP_SOURCE_CONFIG_INVALID$"):
                    S.prepare(self.request, self.routes, self.tunnels)
                remote.assert_not_called()
        self.configure_source({"base_url": "https://configured.example.com"})
        self.request["sourceConfigSha256"] = "0" * 64
        with patch.object(S, "remote_prepare") as remote:
            with self.assertRaisesRegex(S.Failure, "^SOURCE_SETUP_SOURCE_CONFIG_CHANGED$"):
                S.prepare(self.request, self.routes, self.tunnels)
            remote.assert_not_called()
        self.assertFalse((self.root / "source-channels").exists())

    def test_private_and_userinfo_configuration_urls_never_grant_an_initial_origin(self):
        self.request["content"] = "raise RuntimeError('MUST_NOT_EXECUTE')\n"
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        for url in ("http://127.0.0.1/api", "https://10.0.0.1/api", "https://user:pass@site.example.com",
                    "https://[broken", "file:///private/config"):
            self.configure_source({"base_url": url})
            with self.subTest(url=url), patch.object(S, "remote_prepare") as remote:
                with self.assertRaisesRegex(S.Failure, "^SOURCE_SETUP_ORIGINS_REQUIRED$"):
                    S.prepare(self.request, self.routes, self.tunnels)
                remote.assert_not_called()

    def test_changed_configuration_after_failed_preparation_gets_its_own_intent(self):
        with patch.object(S, "remote_prepare", side_effect=S.Failure("SOURCE_SETUP_DNS_UNAVAILABLE")) as remote:
            for suffix in ("first", "second"):
                self.configure_source({"base_url": "https://configured.example.com/" + suffix})
                with self.assertRaisesRegex(S.Failure, "^SOURCE_SETUP_DNS_UNAVAILABLE$"):
                    S.prepare(self.request, self.routes, self.tunnels)
        plans = [call.args[1] for call in remote.call_args_list]
        self.assertEqual(2, len(plans))
        self.assertNotEqual(plans[0]["instanceId"], plans[1]["instanceId"])
        self.assertEqual(plans[0]["origins"], plans[1]["origins"])
        self.assertNotEqual(plans[0]["sourceConfigSha256"], plans[1]["sourceConfigSha256"])
        self.assertEqual(2, len(list((self.root / "source-channels").glob("*.json"))))
        self.assertEqual(self.route_config, json.loads(self.routes.read_bytes()))

    def test_literal_embedded_python_is_parsed_without_executing_or_importing_it(self):
        inner = "raise RuntimeError('MUST_NOT_EXECUTE')\nAPI='https://embedded.example.com/list'\n"
        content = ("import base64 as codec\nROOT='https://outer.example.com'\n"
                   "module=codec.b64decode(" + repr(base64.b64encode(inner.encode()).decode()) + ").decode()\n"
                   "exec(compile(module, '<embedded>', 'exec'))\n")
        self.assertEqual(["https://outer.example.com", "https://embedded.example.com"],
                         S.source_origins(content))
        hidden = "API='https://metadata.example.com'\n"
        content += "META={'module':codec.b64decode(" + repr(base64.b64encode(hidden.encode()).decode()) + ")}\n"
        content += "broken=codec.b64decode('not base64')\n"
        content += "binary=codec.b64decode(" + repr(base64.b64encode(b"\x00\xff").decode()) + ")\n"
        self.assertEqual(["https://outer.example.com", "https://embedded.example.com"],
                         S.source_origins(content))

    def test_embedded_source_depth_and_total_size_are_bounded(self):
        text = "API='https://depth3.example.com'\n"
        for depth in (2, 1, 0):
            text = ("import base64\nAPI='https://depth" + str(depth) + ".example.com'\n"
                    "code=base64.b64decode(" + repr(base64.b64encode(text.encode()).decode()) + ")\n")
        self.assertEqual(["https://depth0.example.com", "https://depth1.example.com",
                          "https://depth2.example.com"], S.source_origins(text))
        large = ("# padding\n" * 80000) + "API='https://oversize.example.com'\n"
        content = ("import base64\nAPI='https://outer.example.com'\ncode=base64.b64decode("
                   + repr(base64.b64encode(large.encode()).decode()) + ")\n")
        self.assertEqual(["https://outer.example.com"], S.source_origins(content))

    def test_token_group_follows_literal_guest_issuer_and_authorization_consumer(self):
        content = '''
class API:
    BASE = "https://catalog.example.com"
    LOGIN = "https://login.example.com/guest"
    OTHER = "https://images.example.com"
    def headers(self):
        return {"Authorization": self._token}
    def login(self):
        self._token = self.sess.post_json(self.LOGIN, {})["token"]
    def categories(self):
        return self.sess.get_json(self.BASE + "/items", headers=self.headers())
class Other:
    BASE = "https://outside.example.com"
'''
        self.assertEqual([["https://catalog.example.com", "https://login.example.com"]],
                         S.source_session_token_groups(content))
        self.assertEqual([], S.source_session_token_groups(content.replace(
            'headers=self.headers()', 'headers={}')))

    def test_prepare_keeps_embedded_origins_and_token_groups_in_the_same_intent(self):
        inner = '''
class API:
    BASE = "https://catalog.example.com"
    LOGIN = "https://login.example.com/guest"
    def headers(self):
        return {"Authorization": self._token}
    def login(self):
        self._token = self.sess.post_json(self.LOGIN, {})["token"]
    def categories(self):
        return self.sess.get_json(self.BASE + "/items", headers=self.headers())
'''
        self.request["content"] = (
            "import base64\nROOT='https://outer.example.com'\n"
            "code=base64.b64decode(" + repr(base64.b64encode(inner.encode()).decode()) + ")\n")
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        with patch.object(S, "remote_prepare", side_effect=self.installed) as remote, patch.object(S, "wait_tunnel"):
            S.prepare(self.request, self.routes, self.tunnels)
        plan = remote.call_args.args[1]
        self.assertEqual(["https://outer.example.com", "https://catalog.example.com",
                          "https://login.example.com"], plan["origins"])
        self.assertEqual([["https://catalog.example.com", "https://login.example.com"]],
                         plan["sessionTokenGroups"])
        saved = json.loads(next((self.root / "source-channels").glob("*.json")).read_bytes())
        self.assertEqual(plan, saved)

    def test_wrapped_guest_sessions_keep_each_source_token_separate(self):
        content = '''
raise RuntimeError("MUST_NOT_EXECUTE")
SOURCES = [
    {"id": "first", "base": "https://first.example.com"},
    {"id": "second", "base": "https://second.example.com"},
    {"id": "outside", "base": "https://outside.example.com"},
]
AUX = {"first": ("https://login-first.example.com",),
       "second": ("https://login-second.example.com",)}
class API:
    def request_json(self, source, path, **kwargs):
        return {}
    def first_json(self, path):
        response = self.request_json("first", AUX["first"][0] + "/guest")
        self._first_token = response["token"]
        token = self._first_token
        return self.request_json("first", path, headers={"Authorization": "Bearer " + token})
    def second_json(self, path):
        response = self.request_json("second", AUX["second"][0] + "/guest")
        self._second_token, self._expiry = response["token"], 0
        token = self._second_token
        return self.request_json("second", path, headers={"authorization": token})
'''
        expected = [["https://first.example.com", "https://login-first.example.com"],
                    ["https://login-second.example.com", "https://second.example.com"]]
        self.assertEqual(expected, S.source_session_token_groups(content))
        self.assertEqual([], S.source_session_token_groups(content.replace(
            '"Authorization"', '"X-Other"').replace('"authorization"', '"X-Other"')))
        self.assertEqual([], S.source_session_token_groups(content.replace(
            'token = self._first_token', 'token = self._first_token\n        token = "imported"').replace(
            'token = self._second_token', 'token = self._second_token\n        token = "imported"')))

    def test_wrapped_guest_sessions_do_not_guess_ambiguous_or_dynamic_source_bases(self):
        content = '''
SOURCES = [{"id": "first", "base": "https://first.example.com"},
           {"id": "first", "base": "https://other.example.com"}]
LOGIN = {"first": ["https://login.example.com"]}
class API:
    def request_json(self, source, path, **kwargs):
        return {}
    def fetch(self, path):
        response = self.request_json("first", LOGIN["first"][0] + "/guest")
        self._token = response["token"]
        token = self._token
        return self.request_json("first", path, headers={"authorization": token})
'''
        self.assertEqual([], S.source_session_token_groups(content))
        dynamic = content.replace(
            '{"id": "first", "base": "https://other.example.com"}', '{"id": "outside", "base": "https://other.example.com"}')
        dynamic = dynamic.replace('LOGIN["first"][0] + "/guest"', 'resolve_login()')
        self.assertEqual([], S.source_session_token_groups(dynamic))

    def test_wrapped_guest_token_can_be_read_back_after_bootstrap_assignment(self):
        content = '''
SOURCES = [{"id": "first", "base": "https://first.example.com"}]
LOGIN = {"first": ["https://login.example.com"]}
class API:
    def _json(self, source, path, **kwargs):
        return {}
    def fetch(self, path):
        with self._lock:
            if not self._token:
                response = self._json("first", LOGIN["first"][0] + "/guest")
                token = response.get("token")
                self._token, self._expiry = token, 0
            token = self._token
        return self._json("first", path, headers={"authorization": token})
'''
        self.assertEqual([["https://first.example.com", "https://login.example.com"]],
                         S.source_session_token_groups(content))
        for replacement in ('token = "imported"',
                            'if self.use_external:\n                token = "imported"',
                            'token = self._token\n            if self.use_external:\n                token = "imported"',
                            'def nested():\n                token = self._token'):
            with self.subTest(replacement=replacement):
                changed = content.replace('token = self._token', replacement)
                self.assertEqual([], S.source_session_token_groups(changed))

    def test_http_origins_keep_the_declared_scheme_and_port_without_upgrading_the_source(self):
        self.assertEqual(["http://reader.example.com", "http://api.example.com:8080",
                          "https://reader.example.com"],
                         S.source_origins("HOSTS=" + repr([
                             "http://reader.example.com/catalog", "http://api.example.com:8080/list",
                             "https://reader.example.com/catalog"])))

    def test_aggregate_sources_accept_twenty_and_sixty_four_literal_origins_without_execution(self):
        self.assertEqual(64, S.MAX_ROUTES)
        for count in (20, 64):
            origins = ["https://site" + str(index) + ".example.com" for index in range(count)]
            with self.subTest(count=count):
                self.assertEqual(origins, S.source_origins(
                    "raise RuntimeError('MUST_NOT_EXECUTE')\nHOSTS=" + repr(origins)))

    def test_too_many_origins_report_capacity_before_remote_work_or_intent_creation(self):
        origins = ["https://site" + str(index) + ".example.com" for index in range(65)]
        self.request["content"] = "HOSTS=" + repr(origins)
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        with patch.object(S, "remote_prepare") as remote:
            with self.assertRaisesRegex(S.Failure, "^SOURCE_SETUP_ORIGIN_LIMIT$"):
                S.prepare(self.request, self.routes, self.tunnels)
            remote.assert_not_called()
        self.assertFalse((self.root / "source-channels").exists())
        with self.assertRaisesRegex(S.Failure, "^SOURCE_SETUP_ORIGINS_REQUIRED$"):
            S.source_origins("HOSTS=[]")

    def test_source_identity_is_checked_before_any_remote_work(self):
        self.request["sourceSha256"] = "a" * 64
        with patch.object(S, "remote_prepare") as remote:
            with self.assertRaisesRegex(S.Failure, "^SOURCE_SETUP_SOURCE_CHANGED$"):
                S.prepare(self.request, self.routes, self.tunnels)
            remote.assert_not_called()

    def companion_registry(self, modules):
        definitions = {}
        for name, body in modules.items():
            path = self.root / (name + ".py")
            path.write_bytes(body)
            definitions[name] = {"path": path.name, "sha256": hashlib.sha256(body).hexdigest(),
                                 "imageIds": [self.tunnel_config["runners"][0]["imageId"]]}
        path = self.root / "source-companions.json"
        path.write_text(json.dumps({"schemaVersion": 1, "modules": definitions}))
        return path

    def test_imported_companions_and_transitive_modules_extend_one_frozen_plan_without_execution(self):
        self.companion_registry({
            "subtitles": b"raise RuntimeError('DO_NOT_EXECUTE')\nfrom tracks import lookup\nAPI='https://subtitle.example.com/find'\n",
            "tracks": b"import subtitles\nAPI='https://tracks.example.com/list'\n",
            "unrelated": b"raise RuntimeError('DO_NOT_READ_OR_EXECUTE')\nAPI='https://unrelated.example.com'\n"})
        (self.root / "unrelated.py").unlink()
        self.request["content"] += "from subtitles import lookup\n"
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        with patch.object(S, "remote_prepare", side_effect=self.installed) as remote, patch.object(S, "wait_tunnel"):
            result = S.prepare(self.request, self.routes, self.tunnels)
        plan = remote.call_args.args[1]
        self.assertEqual(["https://site.example.com", "https://subtitle.example.com",
                          "https://tracks.example.com"], plan["origins"])
        self.assertEqual(["subtitles", "tracks"], [row["module"] for row in plan["companionModules"]])
        self.assertEqual(plan["companionModules"], result["companionModules"])
        self.assertNotIn("DO_NOT_EXECUTE", json.dumps(plan))

    def test_companion_image_or_bytes_mismatch_blocks_before_remote_work(self):
        path = self.companion_registry({"subtitles": b"API='https://subtitle.example.com'\n"})
        self.request["content"] += "import subtitles\n"
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        registry = json.loads(path.read_bytes())
        registry["modules"]["subtitles"]["imageIds"] = ["sha256:" + "0" * 64]
        path.write_text(json.dumps(registry))
        with patch.object(S, "remote_prepare") as remote:
            with self.assertRaisesRegex(S.Failure, "SOURCE_SETUP_COMPANION_IMAGE_MISMATCH"):
                S.prepare(self.request, self.routes, self.tunnels)
            remote.assert_not_called()
        registry["modules"]["subtitles"]["imageIds"] = [self.tunnel_config["runners"][0]["imageId"]]
        path.write_text(json.dumps(registry))
        (self.root / "subtitles.py").write_bytes(b"API='https://changed.example.com'\n")
        with patch.object(S, "remote_prepare") as remote:
            with self.assertRaisesRegex(S.Failure, "SOURCE_SETUP_COMPANION_CHANGED"):
                S.prepare(self.request, self.routes, self.tunnels)
            remote.assert_not_called()

    def test_companion_without_network_still_discovers_its_imported_network_module(self):
        path = self.companion_registry({"helper": b"import tracks\n",
                                        "tracks": b"API='https://tracks.example.com'\n"})
        origins, receipts = S.companion_origins(
            "import helper\n", path, self.tunnel_config["runners"][0]["imageId"])
        self.assertEqual(["https://tracks.example.com"], origins)
        self.assertEqual([], receipts[0]["origins"])

    def test_companion_selects_its_registered_image_instead_of_the_first_plain_template(self):
        path = self.companion_registry({"subtitles": b"API='https://subtitle.example.com'\n"})
        second = {**self.tunnel_config["runners"][0], "id": "with-modules", "localPort": 19088,
                  "remoteSocket": "/srv/with-modules/executor.sock", "imageId": "sha256:" + "9" * 64}
        self.tunnel_config["runners"].append(second)
        self.tunnels.write_text(json.dumps(self.tunnel_config))
        registry = json.loads(path.read_bytes())
        registry["modules"]["subtitles"]["imageIds"] = [second["imageId"]]
        path.write_text(json.dumps(registry))
        self.request["content"] += "import subtitles\n"
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        with patch.object(S, "remote_prepare", side_effect=self.installed) as remote, patch.object(S, "wait_tunnel"):
            S.prepare(self.request, self.routes, self.tunnels)
        self.assertEqual(second["remoteSocket"], remote.call_args.args[1]["templateSocket"])
        self.assertEqual(second["imageId"], remote.call_args.args[1]["companionModules"][0]["imageId"])

    def image_registry(self):
        plain = self.tunnel_config["runners"][0]
        second = {**plain, "id": "with-dependencies", "localPort": 19088,
                  "remoteSocket": "/srv/with-dependencies/executor.sock", "imageId": "sha256:" + "9" * 64}
        self.tunnel_config["runners"].append(second)
        self.tunnels.write_text(json.dumps(self.tunnel_config))
        images = {plain["imageId"]: ["requests"], second["imageId"]: ["requests", "bs4"]}
        path = self.root / "source-image-imports.json"
        path.write_text(json.dumps({"schemaVersion": 1, "images": images}))
        return second, path

    def test_registered_imports_select_existing_dependency_image_and_freeze_identity(self):
        second, _ = self.image_registry()
        inner = "from bs4 import BeautifulSoup\nimport requests\n"
        self.request["content"] += ("import base64\ncode=base64.b64decode("
                                    + repr(base64.b64encode(inner.encode()).decode()) + ")\n")
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        with patch.object(S, "remote_prepare", side_effect=self.installed) as remote, patch.object(S, "wait_tunnel"):
            result = S.prepare(self.request, self.routes, self.tunnels)
        plan = remote.call_args.args[1]
        self.assertEqual(second["remoteSocket"], plan["templateSocket"])
        self.assertEqual({"imageId": second["imageId"], "requiredModules": ["bs4", "requests"]},
                         plan["imageImports"])
        self.assertEqual(plan["imageImports"], result["imageImports"])
        self.assertFalse(result["sourceExecuted"])
        self.assertEqual(plan, json.loads(next((self.root / "source-channels").glob("*.json")).read_bytes()))

    def test_companion_imports_are_included_when_selecting_a_dependency_image(self):
        second, _ = self.image_registry()
        path = self.companion_registry({"helper": b"from bs4 import BeautifulSoup\n"})
        registry = json.loads(path.read_bytes())
        registry["modules"]["helper"]["imageIds"] = [second["imageId"]]
        path.write_text(json.dumps(registry))
        self.request["content"] += "import helper\n"
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        with patch.object(S, "remote_prepare", side_effect=self.installed) as remote, patch.object(S, "wait_tunnel"):
            S.prepare(self.request, self.routes, self.tunnels)
        self.assertEqual(["bs4"], remote.call_args.args[1]["imageImports"]["requiredModules"])
        self.assertEqual(second["remoteSocket"], remote.call_args.args[1]["templateSocket"])

    def test_missing_matching_dependency_template_does_not_fall_back_or_create_an_intent(self):
        _, _ = self.image_registry()
        self.tunnel_config["runners"].pop()
        self.tunnels.write_text(json.dumps(self.tunnel_config))
        self.request["content"] += "import bs4\n"
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        with patch.object(S, "remote_prepare") as remote, self.assertRaisesRegex(
                S.Failure, "^SOURCE_SETUP_DEPENDENCY_IMAGE_MISMATCH$"):
            S.prepare(self.request, self.routes, self.tunnels)
        remote.assert_not_called()
        self.assertFalse((self.root / "source-channels").exists())

    def test_unregistered_imports_are_not_misclassified_as_missing_registered_dependencies(self):
        self.image_registry()
        self.request["content"] += "import json\nimport optional_vendor\n"
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        with patch.object(S, "remote_prepare", side_effect=self.installed) as remote, patch.object(S, "wait_tunnel"):
            S.prepare(self.request, self.routes, self.tunnels)
        self.assertEqual([], remote.call_args.args[1]["imageImports"]["requiredModules"])
        self.assertEqual(self.tunnel_config["runners"][0]["remoteSocket"],
                         remote.call_args.args[1]["templateSocket"])

    def test_changed_image_capability_selection_gets_a_new_owner_after_failed_preparation(self):
        second, path = self.image_registry()
        self.request["content"] += "import bs4\n"
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        with patch.object(S, "remote_prepare", side_effect=S.Failure("SOURCE_SETUP_REMOTE_FAILED")):
            with self.assertRaises(S.Failure):
                S.prepare(self.request, self.routes, self.tunnels)
        previous = next((self.root / "source-channels").glob("*.json"))
        previous_body = previous.read_bytes()
        registry = json.loads(path.read_bytes())
        registry["images"][self.tunnel_config["runners"][0]["imageId"]].append("bs4")
        path.write_text(json.dumps(registry))
        with patch.object(S, "remote_prepare", side_effect=self.installed), patch.object(S, "wait_tunnel"):
            S.prepare(self.request, self.routes, self.tunnels)
        plans = [json.loads(p.read_bytes()) for p in (self.root / "source-channels").glob("*.json")]
        self.assertEqual(previous_body, previous.read_bytes())
        self.assertEqual(2, len({p["instanceId"] for p in plans}))
        self.assertEqual(2, len({p["imageImports"]["imageId"] for p in plans}))

    def test_remote_image_must_match_the_frozen_dependency_selection(self):
        self.image_registry()
        self.request["content"] += "import bs4\n"
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        def wrong_image(config, plan):
            return {**self.installed(config, plan), "imageId": "sha256:" + "0" * 64}
        with patch.object(S, "remote_prepare", side_effect=wrong_image), self.assertRaisesRegex(
                S.Failure, "^SOURCE_SETUP_REMOTE_IDENTITY_CHANGED$"):
            S.prepare(self.request, self.routes, self.tunnels)
        self.assertEqual(self.route_config, json.loads(self.routes.read_bytes()))
        self.assertEqual(self.tunnel_config, json.loads(self.tunnels.read_bytes()))

    def test_invalid_image_registry_is_rejected_before_remote_work(self):
        _, path = self.image_registry()
        for images in ({"mutable-tag": ["bs4"]}, {"sha256:" + "9" * 64: ["bs4", "bs4"]},
                       {"sha256:" + "9" * 64: ["bs4;execute()"]}):
            path.write_text(json.dumps({"schemaVersion": 1, "images": images}))
            with self.subTest(images=images), patch.object(S, "remote_prepare") as remote:
                with self.assertRaisesRegex(S.Failure, "^SOURCE_SETUP_IMAGE_REGISTRY_INVALID$"):
                    S.prepare(self.request, self.routes, self.tunnels)
                remote.assert_not_called()

    def test_thin_source_can_declare_all_network_endpoints_in_its_registered_companion(self):
        self.companion_registry({"adapter": b"API='https://adapter.example.com'\n"})
        self.request["content"] = "from adapter import Spider\n"
        self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
        with patch.object(S, "remote_prepare", side_effect=self.installed) as remote, patch.object(S, "wait_tunnel"):
            S.prepare(self.request, self.routes, self.tunnels)
        self.assertEqual(["https://adapter.example.com"], remote.call_args.args[1]["origins"])

    def test_pending_intents_reserve_their_port_before_any_tunnel_is_installed(self):
        with patch.object(S, "remote_prepare", side_effect=S.Failure("SOURCE_SETUP_REMOTE_FAILED")):
            with self.assertRaises(S.Failure):
                S.prepare(self.request, self.routes, self.tunnels)
        second = {**self.request, "sourceId": "22222222-2222-4222-8222-222222222222"}
        with patch.object(S, "remote_prepare", side_effect=self.installed), patch.object(S, "wait_tunnel"):
            S.prepare(second, self.routes, self.tunnels)
        intents = [json.loads(path.read_bytes()) for path in (self.root / "source-channels").glob("*.json")]
        self.assertEqual(2, len({intent["localPort"] for intent in intents}))

    def test_setup_adds_one_channel_without_overwriting_existing_configuration(self):
        existing = copy.deepcopy(self.tunnel_config["runners"][0])
        with patch.object(S, "remote_prepare", side_effect=self.installed), patch.object(S, "wait_tunnel"):
            result = S.prepare(self.request, self.routes, self.tunnels)
        self.assertTrue(result["ready"])
        self.assertFalse(result["sourceExecuted"])
        routes = json.loads(self.routes.read_bytes())
        tunnels = json.loads(self.tunnels.read_bytes())
        self.assertEqual(1, len(routes["runners"]))
        self.assertEqual(existing, tunnels["runners"][0])
        self.assertEqual(2, len(tunnels["runners"]))
        self.assertEqual([self.request["sourceId"]], routes["runners"][0]["sourceIds"])
        intent = next((self.root / "source-channels").glob("*.json")).read_text()
        self.assertNotIn("MUST_NOT_EXECUTE", intent)
        self.assertNotIn("content", intent)

    def test_lost_tunnel_observation_resumes_same_infrastructure_intent(self):
        observed = []
        def install(config, plan):
            observed.append(copy.deepcopy(plan))
            return self.installed(config, plan)
        with patch.object(S, "remote_prepare", side_effect=install), patch.object(
                S, "wait_tunnel", side_effect=[S.Failure("SOURCE_SETUP_TUNNEL_PENDING"), None]):
            with self.assertRaisesRegex(S.Failure, "SOURCE_SETUP_TUNNEL_PENDING"):
                S.prepare(self.request, self.routes, self.tunnels)
            self.assertEqual([], json.loads(self.routes.read_bytes())["runners"])
            S.prepare(self.request, self.routes, self.tunnels)
        self.assertEqual(observed[0], observed[1])
        self.assertEqual(2, len(json.loads(self.tunnels.read_bytes())["runners"]))
        self.assertEqual(1, len(json.loads(self.routes.read_bytes())["runners"]))

    def test_a_changed_source_does_not_silently_expand_an_existing_intent(self):
        with patch.object(S, "remote_prepare", side_effect=self.installed), patch.object(S, "wait_tunnel"):
            S.prepare(self.request, self.routes, self.tunnels)
            self.request["content"] = "ORIGIN='https://different.example.com'\n"
            self.request["sourceSha256"] = hashlib.sha256(self.request["content"].encode()).hexdigest()
            with self.assertRaisesRegex(S.Failure, "SOURCE_SETUP_SOURCE_ALREADY_ROUTED"):
                S.prepare(self.request, self.routes, self.tunnels)

    def test_failed_old_bundle_intent_is_preserved_and_new_bundle_gets_an_independent_owner(self):
        with patch.object(S, "remote_prepare", side_effect=S.Failure("SOURCE_SETUP_REMOTE_FAILED")):
            with self.assertRaises(S.Failure):
                S.prepare(self.request, self.routes, self.tunnels)
        directory = self.root / "source-channels"
        previous = next(directory.glob("*.json"))
        previous_body = previous.read_bytes()
        configuration = json.loads(self.tunnels.read_bytes())
        self.request["bundleSha256"] = "d" * 64
        configuration["runners"][0]["bundleSha256"] = self.request["bundleSha256"]
        self.tunnels.write_text(json.dumps(configuration))
        with patch.object(S, "remote_prepare", side_effect=self.installed), patch.object(S, "wait_tunnel"):
            S.prepare(self.request, self.routes, self.tunnels)
        self.assertEqual(previous_body, previous.read_bytes())
        plans = [json.loads(path.read_bytes()) for path in directory.glob("*.json")]
        self.assertEqual(2, len(plans))
        self.assertEqual(2, len({plan["instanceId"] for plan in plans}))
        self.assertEqual(2, len({plan["localPort"] for plan in plans}))

    def test_concurrent_user_configuration_changes_are_not_overwritten(self):
        original = self.tunnel_config
        current = copy.deepcopy(original)
        current["runners"].append(F.route(19002, "other"))
        self.tunnels.write_text(json.dumps(current))
        with self.assertRaisesRegex(S.Failure, "SOURCE_SETUP_CONFIGURATION_CHANGED"):
            S.append_runner(self.tunnels, original, F.route(19003, "new"))
        self.assertEqual(current, json.loads(self.tunnels.read_bytes()))

    def test_new_bundle_requires_a_matching_installed_template_before_remote_work(self):
        self.request["bundleSha256"] = "0" * 64
        with patch.object(S, "remote_prepare") as remote:
            with self.assertRaisesRegex(S.Failure, "SOURCE_SETUP_RUNTIME_UNAVAILABLE"):
                S.prepare(self.request, self.routes, self.tunnels)
            remote.assert_not_called()

    def test_remote_program_is_syntactically_valid_without_executing_it(self):
        compile(S.REMOTE, "<trusted-setup>", "exec")
        route = next(node.value for node in ast.walk(ast.parse(S.REMOTE))
                     if isinstance(node, ast.Assign) and any(
                         isinstance(target, ast.Name) and target.id == "route" for target in node.targets))
        fields = {key.value: value for key, value in zip(route.keys, route.values) if key is not None}
        self.assertTrue(any(key is None and isinstance(value, ast.Call)
                            and isinstance(value.func, ast.Attribute) and value.func.attr == "address_pins"
                            for key, value in zip(route.keys, route.values)))
        self.assertEqual("origin", ast.literal_eval(fields["requestScope"]))
        self.assertEqual({"responseScope": "origin"}, ast.literal_eval(fields["sessionToken"]))
        policy = next(node.value.args[0] for node in ast.walk(ast.parse(S.REMOTE))
                      if isinstance(node, ast.Assign) and any(
                          isinstance(target, ast.Name) and target.id == "policy" for target in node.targets)
                      and isinstance(node.value, ast.Call) and node.value.args
                      and isinstance(node.value.args[0], ast.Dict))
        fields = {key.value: value for key, value in zip(policy.keys, policy.values)}
        self.assertEqual("public_http_https", ast.literal_eval(fields["responseRedirects"]))
        self.assertEqual("public_http_https", ast.literal_eval(fields["responseApiOrigins"]))
        self.assertEqual("public_http_https", ast.literal_eval(fields["playerResources"]))

    def test_remote_dns_failure_is_classified_without_building_policy_or_starting_a_broker(self):
        program = ast.parse(S.REMOTE)
        block = next(node for node in ast.walk(program)
                     if isinstance(node, ast.If) and ast.unparse(node.test) == "not routes")
        code = compile(ast.Module(body=[block], type_ignores=[]), "<dns-preparation>", "exec")
        request = {"bundleSha256": "b" * 64, "instanceId": self.request["sourceId"]}
        for observed, expected in (
                ({"EGRESS_DNS_NO_ADDRESS"}, "SOURCE_SETUP_DNS_NO_ADDRESS"),
                ({"EGRESS_NONPUBLIC_DNS"}, "SOURCE_SETUP_DNS_NONPUBLIC"),
                ({"EGRESS_DNS_FAILED"}, "SOURCE_SETUP_DNS_UNAVAILABLE"),
                ({"EGRESS_DNS_NO_ADDRESS", "EGRESS_NONPUBLIC_DNS"}, "SOURCE_SETUP_DNS_UNAVAILABLE")):
            output = io.StringIO()
            with self.subTest(observed=observed), patch("sys.stdout", output), self.assertRaises(SystemExit):
                exec(code, {"routes": [], "dns_codes": observed, "json": json, "v": request})
            reply = json.loads(output.getvalue())
            self.assertEqual(expected, reply["code"])
            self.assertFalse(reply["sourceExecuted"])
            self.assertEqual(request["instanceId"], reply["instanceId"])
            self.assertNotIn("origin", reply)
        exec(code, {"routes": [{}]})

    def test_remote_dns_reply_is_identity_bound_and_unknown_errors_are_not_exposed(self):
        plan = {"bundleSha256": "b" * 64, "instanceId": self.request["sourceId"]}
        cases = [(code, code, {}) for code in (
            "SOURCE_SETUP_DNS_NO_ADDRESS", "SOURCE_SETUP_DNS_NONPUBLIC", "SOURCE_SETUP_DNS_UNAVAILABLE")]
        cases += [("private endpoint and credential", "SOURCE_SETUP_REMOTE_FAILED", {}),
                  ("SOURCE_SETUP_DNS_NO_ADDRESS", "SOURCE_SETUP_REMOTE_IDENTITY_CHANGED",
                   {"instanceId": "another-owner"})]
        for reported, expected, changes in cases:
            reply = {**plan, "ok": False, "sourceExecuted": False, "code": reported, **changes}
            channel = MagicMock()
            channel.makefile.return_value = io.BytesIO(json.dumps(reply).encode())
            channel.recv_exit_status.return_value = 0
            transport = MagicMock()
            client = transport.lease.return_value.__enter__.return_value
            client.get_transport.return_value.open_session.return_value = channel
            config = MagicMock()
            config.ssh = {}
            with self.subTest(reported=reported, changes=changes), patch.object(
                    S.T, "Transport", return_value=transport):
                with self.assertRaisesRegex(S.Failure, "^" + expected + "$"):
                    S.remote_prepare(config, plan)
            channel.close.assert_called_once()
            transport.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
