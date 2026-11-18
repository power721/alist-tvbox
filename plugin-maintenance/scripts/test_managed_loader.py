"""Only deterministic returned values are used here; real NAS runs are separate."""
import copy
import hashlib
import json
import unittest
from unittest.mock import patch

import at_source_projection as projection
from managed_loader import (Loader, Invalid, decode_image, source_identity, validate_source_identity,
                            collect_resource_evidence, resource_links, resource_excerpts, MAX_RESOURCE_TEXT)
from managed_loader import json_structure, digest

SOURCE = b"class Spider: pass\n"
RUNTIME = "a" * 64


class Broker:
    events = []

    def fetch(self, request):
        import base64
        if "image.example.com" in request["url"]:
            return {"status": 200, "body": base64.b64encode(b"image").decode(),
                    "headers": {"Content-Type": "image/png"}}
        body = b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\none.m3u8\n"
        return {"status": 200, "body": base64.b64encode(body).decode(), "headers": {}}

    def verify_image(self, body):
        return {"sha256": hashlib.sha256(body).hexdigest(), "width": 2, "height": 2}


def card(ident="item-1"):
    return {"vod_id": ident, "vod_name": "name", "vod_pic": "https://image.example.com/cover.jpg"}


def answer(call):
    method, params = call["method"], call["params"]
    if method == "init":
        return {"name": "fixture", "result": None}
    if method == "home":
        return {"class": [{"type_id": "cat", "type_name": "category"}]}
    if method == "category":
        page = int(params["pg"])
        return {"page": page, "pagecount": 3, "total": 3, "limit": 1, "list": [card("item-" + str(page))]}
    if method == "detail":
        if not params["ids"]:
            return {"list": []}
        return {"list": [{**card(), "vod_play_from": "flag", "vod_play_url": "episode$opaque$tail"}]}
    if method == "play":
        return {"parse": 0, "url": "https://media.example.com/private.m3u8"}
    if method == "search":
        return {"list": [card()]}
    if method == "destroy":
        return {"status": "closed"}
    if method == "ping":
        return {"status": "ready"}
    raise ValueError(method)


def drive(transform=lambda c, v: v, *, ready=True, profile=None, jsonl=False, source=SOURCE, mode="full"):
    loader = Loader(source, RUNTIME, Broker(), profile, mode=mode)
    loader.jsonl_observed = jsonl
    command = loader.next()
    while command["kind"] == "call":
        if command["method"] == "init" and ready:
            loader.source_ready({"kind": "loader_source", "sourceTextSha256": hashlib.sha256(source).hexdigest()})
        try:
            if not isinstance(command["params"], dict):
                raise ValueError("params")
            value = transform(command, answer(command))
            frame = {"ok": True, "result": value}
        except ValueError:
            frame = {"ok": False, "code": "LOADER_METHOD_FAILED"}
        command = loader.accept({"kind": "call_result", "id": command["id"], **frame})
    return loader, {row["id"]: row for row in loader.receipt()["nodes"]}


class LoaderTest(unittest.TestCase):
    def test_nested_json_string_structure_is_bounded_one_level_and_redacted(self):
        embedded = json.dumps({"url": "https://media.example/PRIVATE", "name": "PRIVATE",
                               "cookie": "PRIVATE", "nested": json.dumps({"password": "PRIVATE"})})
        body = json.dumps({"data": {"list": [{"siteId": 1, "resData": embedded}] * 30}})
        structure = json_structure(body)
        self.assertTrue(structure["truncated"])
        self.assertLessEqual(len(json.dumps(structure, separators=(",", ":")).encode()), 2048)
        row = next(node for node in structure["nodes"] if node["path"] == ["data", "list", 0, "resData"])
        decoded = {tuple(node["path"]): node for node in row["embeddedJson"]["nodes"]}
        self.assertEqual("url_like", decoded[("url",)]["shape"])
        self.assertEqual("redacted", decoded[("cookie",)]["type"])
        self.assertEqual("json_like", decoded[("nested",)]["shape"])
        self.assertNotIn("embeddedJson", decoded[("nested",)])
        self.assertNotIn("PRIVATE", json.dumps(structure))
        self.assertNotIn("embeddedJson", json.dumps(json_structure(json.dumps({"resData": "{" + "x" * 9000}))))

    def test_episode_failure_pinpoints_detail_group_and_missing_field_without_identifiers(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S06", "C01", "C03", "C07", "C10", "R01"],
                   "paginationMode": "page-window"}
        for broken, missing in (("$PRIVATE", "namePresent"), ("episode", "separatorPresent"),
                                ("episode$", "identifierPresent")):
            with self.subTest(missing=missing):
                def malformed(call, value):
                    if call["method"] == "detail" and call["params"]["ids"]:
                        value["list"][0].update(vod_play_from="first$$$second",
                                                vod_play_url="valid$PRIVATE$$$valid$PRIVATE#" + broken)
                    return value
                loader, nodes = drive(malformed, profile=profile)
                self.assertEqual(("fail", "PLAY_EPISODE_INVALID"),
                                 (nodes["C10"]["status"], nodes["C10"]["reason"]))
                observed = nodes["C10"]["playbackContract"]
                self.assertEqual((1, 1, 2), (observed["groupIndex"], observed["episodeIndex"], observed["parsedEpisodes"]))
                self.assertFalse(observed[missing])
                call = loader.calls[observed["detailCallId"] - 1]
                self.assertEqual("detail", call["method"])
                self.assertEqual(digest({"ids": "item-1"}), call["inputSha256"])
                self.assertNotIn("PRIVATE", json.dumps(observed))
                self.assertFalse(any(call["method"] == "play" for call in loader.calls))

    def test_home_recommendations_enter_detail_preserving_duplicates_without_categories(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S06", "C02"], "detailIdentity": "exact"}
        def recommendations(call, value):
            return {"list": [card(), card()]} if call["method"] == "home" else value
        loader, nodes = drive(recommendations, profile=profile)
        self.assertEqual("pass", nodes["C02"]["status"])
        self.assertEqual(2, nodes["C02"]["items"])
        self.assertEqual("home", loader.calls[nodes["C02"]["homeCallId"] - 1]["method"])
        self.assertEqual("detail", loader.calls[nodes["C02"]["detailCallId"] - 1]["method"])
        self.assertEqual(["init", "home", "detail", "destroy"], [c["method"] for c in loader.calls])

    def test_home_recommendations_reject_empty_malformed_and_mismatched_detail(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S06", "C02"], "detailIdentity": "exact"}
        for rows in ([], None, [{"vod_id": "broken"}], [card("another")]):
            with self.subTest(rows=rows):
                _, nodes = drive(lambda call, value: {"list": rows} if call["method"] == "home" else value,
                                 profile=profile)
                self.assertEqual("fail", nodes["C02"]["status"])

    def test_api_json_projection_preserves_wrapper_status_and_not_payload_credentials_or_requests(self):
        import base64
        import os
        from pathlib import Path
        broker = Broker()
        call = {"id": 7, "method": "play", "inputSha256": "b" * 64}
        url = "https://reader.example.com/api/play?signature=PRIVATE"
        body = json.dumps({"code": 200, "success": True, "message": "PRIVATE_MESSAGE",
                           "data": {"payload": "QWxwaGFCZXRhR2FtbWFEZWx0YQ==", "id": 12345,
                                    "accessToken": "PRIVATE_TOKEN", "cookie": "PRIVATE_COOKIE",
                                    "url": "https://media.example.com/video?token=PRIVATE_URL"}}).encode()
        broker.snapshot_resources = lambda: [(url, {"status": 200, "requestMethod": "POST",
            "headers": {"Content-Type": "application/json", "Set-Cookie": "PRIVATE_HEADER"},
            "body": base64.b64encode(body).decode(), "observedDuringCall": call})]
        broker.fetch_resource = unittest.mock.Mock(side_effect=AssertionError("must not replay POST or discover links"))
        value = collect_resource_evidence(broker, "a" * 64, target_node="R01")
        row = value["resources"][0]
        self.assertEqual("api_json", row["kind"])
        self.assertEqual("POST", row["requestMethod"])
        self.assertEqual(call, row["observedDuringCall"])
        self.assertEqual([], row["excerpts"])
        self.assertEqual(hashlib.sha256(body).hexdigest(), row["responseSha256"])
        nodes = {tuple(node["path"]): node for node in row["jsonStructure"]["nodes"]}
        self.assertEqual(200, nodes[("code",)]["safeValue"])
        self.assertTrue(nodes[("success",)]["safeValue"])
        self.assertEqual("base64_like", nodes[("data", "payload")]["shape"])
        self.assertEqual("url_like", nodes[("data", "url")]["shape"])
        self.assertEqual("redacted", nodes[("data", "accessToken")]["type"])
        self.assertNotIn("safeValue", nodes[("data", "id")])
        self.assertNotIn("PRIVATE", json.dumps(value))
        self.assertNotIn("QWxwaG", json.dumps(value))
        self.assertEqual(len(json.dumps(row["jsonStructure"], separators=(",", ":")).encode()), value["textBytes"])
        broker.fetch_resource.assert_not_called()
        if os.environ.get("MAINTENANCE_JSON_REPLAY"):
            event = {"method": "POST", "requestUrlSha256": row["urlSha256"],
                     "responseSha256": row["responseSha256"], "responseBytes": row["responseBytes"],
                     "responseStatus": 200, "responseComplete": True, "responseContentType": "application/json"}
            Path(os.environ["MAINTENANCE_JSON_REPLAY"]).write_text(json.dumps({
                "resourceEvidence": value, "httpObservations": [event], "calls": [call]}), encoding="utf-8")

    def test_json_projection_bounds_depth_width_arrays_and_values_without_rejecting_source_variations(self):
        for value in ([{"code": 401, "status": "Unauthorized", "secret": {"payload": "PRIVATE"}}] * 10,
                      {"data": {"items": [{"value": "x" * 10000, "nested": {"a": {"b": {"c": {"d": 1}}}}}]}},
                      {"field_" + str(i): {"status": "unrecognized PRIVATE", "message": "PRIVATE"} for i in range(200)},
                      {"bad.key": "PRIVATE", "authorization": ["PRIVATE"]}, "e" * 1000, None):
            result = json_structure(json.dumps(value).encode())
            self.assertLessEqual(len(json.dumps(result, separators=(",", ":")).encode()), 2048)
            self.assertLessEqual(len(result["nodes"]), 48)
            self.assertTrue(all(len(node["path"]) <= 6 for node in result["nodes"]))
            self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertIsNone(json_structure(b"not-json"))
        self.assertIsNone(json_structure(b"[" * 2000))

    def test_invalid_json_never_falls_back_to_raw_body_and_get_json_is_projected(self):
        import base64
        broker = Broker()
        for method in ("GET", "POST"):
            broker.snapshot_resources = lambda: [("https://reader.example.com/api", {
                "requestMethod": method, "status": 500, "headers": {"Content-Type": "application/json"},
                "body": base64.b64encode(b'{"private":"PRIVATE"').decode()})]
            value = collect_resource_evidence(broker, "a" * 64, target_node="R01")
            self.assertEqual([], value["resources"])
            self.assertEqual("RESOURCE_JSON_UNAVAILABLE", value["failures"][0]["code"])
            self.assertNotIn("PRIVATE", json.dumps(value))
        broker.snapshot_resources = lambda: [("https://reader.example.com/api", {
            "requestMethod": "GET", "status": 403, "headers": {"Content-Type": "application/problem+json"},
            "body": base64.b64encode(b'{"code":401,"message":"PRIVATE"}').decode()})]
        value = collect_resource_evidence(broker, "a" * 64, target_node="R01")
        self.assertEqual("api_json", value["resources"][0]["kind"])
        self.assertEqual("GET", value["resources"][0]["requestMethod"])
        self.assertFalse(value["businessVerdictTrusted"])

    def test_json_and_page_script_evidence_share_one_budget_and_keep_script_discovery(self):
        import base64
        broker = Broker()
        page = b'<script src="/player.js"></script>' + b'x' * 20000
        payload = json.dumps({"field_" + str(i): "PRIVATE" for i in range(200)}).encode()
        broker.snapshot_resources = lambda: [("https://reader.example.com/api/" + str(i), {
            "status": 200, "requestMethod": "POST", "headers": {"Content-Type": "application/json"},
            "body": base64.b64encode(payload).decode()}) for i in range(3)] + [
                ("https://reader.example.com/watch", {"status": 200, "headers": {}, "body": base64.b64encode(page).decode()})]
        broker.fetch_resource = unittest.mock.Mock(return_value={"status": 200, "headers": {},
            "body": base64.b64encode(b'decrypt(buffer);' + b'x' * 20000).decode()})
        value = collect_resource_evidence(broker, "a" * 64, target_node="R01")
        self.assertEqual(3, sum(row["kind"] == "api_json" for row in value["resources"]))
        self.assertTrue(any(row["kind"] == "script" and row["excerpts"] for row in value["resources"]))
        total = sum(len(json.dumps(row["jsonStructure"], separators=(",", ":")).encode()) if "jsonStructure" in row
                    else sum(len(part["text"].encode()) for part in row["excerpts"]) for row in value["resources"])
        self.assertEqual(total, value["textBytes"])
        self.assertLessEqual(total, MAX_RESOURCE_TEXT)

    def test_source_call_context_is_host_owned_and_cleared_between_calls(self):
        broker = Broker()
        active = []
        broker.set_source_call = lambda call: active.append(copy.deepcopy(call))
        with patch(__name__ + ".Broker", return_value=broker):
            loader, _ = drive()
        self.assertEqual({"id": 1, "method": "init", "inputSha256": loader.calls[0]["inputSha256"]}, active[0])
        self.assertIsNone(active[-1])
        self.assertEqual(len(loader.calls), sum(row is not None for row in active))
        self.assertTrue(all(active[index + 1] is None for index, row in enumerate(active[:-1]) if row is not None))

    def test_error_page_body_and_action_survive_without_dependency_fetches(self):
        import base64
        broker = Broker()
        body = b'<html>Access denied<script src="/private.js"></script></html>'
        action = {"id": 4, "method": "detail", "inputSha256": "a" * 64}
        broker.snapshot_resources = lambda: [("https://reader.example.com/watch/42", {
            "status": 403, "headers": {}, "body": base64.b64encode(body).decode(), "observedDuringCall": action})]
        broker.fetch_resource = unittest.mock.Mock(side_effect=AssertionError("no new fetch"))
        value = collect_resource_evidence(broker, "a" * 64, target_node="C07")
        row = value["resources"][0]
        self.assertEqual(403, row["responseStatus"])
        self.assertEqual(action, row["observedDuringCall"])
        self.assertEqual(body.decode(), row["excerpts"][0]["text"])
        self.assertEqual(hashlib.sha256(body).hexdigest(), row["responseSha256"])
        broker.fetch_resource.assert_not_called()
        self.assertFalse(value["businessVerdictTrusted"])

    def test_error_script_text_is_not_lost_to_page_or_library_excerpt_budget(self):
        import base64
        broker = Broker()
        page = b'<script src="/player.js"></script>' + b'x' * 20000
        error = b'<html>Rate limited</html>'
        broker.snapshot_resources = lambda: [("https://reader.example.com/watch/42", {
            "status": 200, "headers": {}, "body": base64.b64encode(page).decode()})]
        broker.fetch_resource = unittest.mock.Mock(return_value={
            "status": 429, "headers": {}, "body": base64.b64encode(error).decode()})
        value = collect_resource_evidence(broker, "a" * 64, target_node="R01")
        self.assertEqual(429, value["resources"][1]["responseStatus"])
        self.assertEqual(error.decode(), value["resources"][1]["excerpts"][0]["text"])
        self.assertEqual(1, broker.fetch_resource.call_count)
        self.assertLessEqual(value["textBytes"], MAX_RESOURCE_TEXT)

    def test_metadata_navigation_checks_search_detail_without_any_playback(self):
        profile = {"schemaVersion": 1, "sourcePurpose": "metadata-navigation",
                   "requiredNodes": ["S06", "C01", "C03", "C05", "C07"]}
        def metadata(call, value):
            if call["method"] == "detail" and value["list"]:
                value["list"][0].pop("vod_play_from", None)
                value["list"][0].pop("vod_play_url", None)
            return value
        loader, nodes = drive(metadata, profile=profile)
        self.assertTrue(all(row["status"] == "pass" for row in nodes.values()))
        self.assertEqual("SEARCH_FIRST_PAGE_TO_DETAIL_CONTRACT", nodes["C05"]["reason"])
        calls = {call["id"]: call for call in loader.calls}
        self.assertEqual("search", calls[nodes["C05"]["searchCallId"]]["method"])
        self.assertEqual("detail", calls[nodes["C05"]["detailCallId"]]["method"])
        self.assertNotIn("play", [call["method"] for call in loader.calls])
        self.assertNotIn('"name"', json.dumps(loader.receipt()))

    def test_search_empty_or_wrong_detail_remains_a_real_failure(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S06", "C05", "C07"], "searchKeyword": "title"}
        def empty(call, value):
            return {"list": []} if call["method"] == "search" else value
        _, nodes = drive(empty, profile=profile)
        self.assertEqual("fail", nodes["C05"]["status"])
        def wrong(call, value):
            return {"list": [card("different-id")]} if call["method"] == "search" else value
        _, nodes = drive(wrong, profile=profile)
        self.assertEqual("DETAIL_IDENTITY_MISMATCH", nodes["C05"]["reason"])

    def test_search_only_metadata_uses_explicit_keyword_and_search_result_detail(self):
        profile = {"schemaVersion": 1, "sourcePurpose": "metadata-navigation",
                   "requiredNodes": ["S06", "C05", "C07"], "searchKeyword": "title"}
        def search_only(call, value):
            return {"class": []} if call["method"] == "home" else value
        loader, nodes = drive(search_only, profile=profile)
        self.assertEqual("pass", nodes["C05"]["status"])
        self.assertEqual("pass", nodes["C07"]["status"])
        self.assertNotIn("category", [call["method"] for call in loader.calls])
        for bad in ({**profile, "requiredNodes": ["S06", "C07"]},
                    {**profile, "optionalNodes": ["R01"]}):
            with self.assertRaisesRegex(Invalid, "LOADER_PROFILE_INVALID"):
                Loader(SOURCE, RUNTIME, Broker(), bad)

    def test_failed_init_keeps_only_an_allowlisted_exception_type(self):
        for code in ("ModuleNotFoundError", "TypeError", "PRIVATE_SECRET"):
            with self.subTest(code=code):
                loader = Loader(SOURCE, RUNTIME, Broker())
                command = loader.next()
                command = loader.accept({"kind": "call_result", "id": command["id"],
                                         "ok": False, "code": code})
                self.assertEqual("finish", command["kind"])
                node = loader.receipt()["nodes"][0]
                self.assertEqual("LOADER_INIT_FAILED", node["reason"])
                if code in Loader.FAILURE_TYPES:
                    self.assertEqual(code, node["failureType"])
                    self.assertEqual(code, loader.calls[0]["failureType"])
                else:
                    self.assertNotIn("failureType", node)
                    self.assertNotIn("PRIVATE_SECRET", json.dumps(loader.receipt()))

    def test_http_playback_selection_is_a_bounded_frozen_group_not_source_config(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S06"], "httpPlaybackGroup": 1}
        loader = Loader(SOURCE, RUNTIME, Broker(), profile)
        profile["httpPlaybackGroup"] = 2
        self.assertEqual(1, loader.profile["httpPlaybackGroup"])
        for value in (-1, 32, True, "1", 1.5, None):
            with self.subTest(value=value), self.assertRaisesRegex(Invalid, "LOADER_PROFILE_INVALID"):
                Loader(SOURCE, RUNTIME, Broker(), {**profile, "httpPlaybackGroup": value})
        Loader(SOURCE, RUNTIME, Broker(), {**profile, "httpDetailSample": "sample-id"})
        for value in ("", "bad\nid", "x" * 513, True, None):
            with self.subTest(sample=value), self.assertRaisesRegex(Invalid, "LOADER_PROFILE_INVALID"):
                Loader(SOURCE, RUNTIME, Broker(), {**profile, "httpDetailSample": value})

    def test_source_identity_matches_projection_and_rejects_partial_or_drifted_data(self):
        source = (b"import requests\n"
                  b"class Spider:\n"
                  b"    def init(self, extend=''):\n"
                  b"        requests.get('x')\n"
                  b"        requests.post('x')\n"
                  b"        requests.Session()\n"
                  b"        requests.session()\n")
        tree = projection.ast.parse(source.decode("utf-8-sig"))
        expected = {
            "sourceRuleSha256": projection._digest_json(projection._rule_summary(source, tree)),
            "dependencySha256": projection._digest_json(
                {"imports": projection._imports(tree)}),
        }
        self.assertEqual(expected, source_identity(source))
        self.assertEqual(expected, validate_source_identity(source, expected))
        for data in (
                {"sourceRuleSha256": expected["sourceRuleSha256"]},
                {**expected, "sourceRuleSha256": "0" * 64},
                {**expected, "dependencySha256": "0" * 64},
        ):
            with self.subTest(data=data), self.assertRaisesRegex(
                    Invalid, "T4_RPC_SOURCE_DATA_INVALID"):
                validate_source_identity(source, data)

    def test_runtime_configuration_reaches_full_and_cold_init_but_static_identity_does_not(self):
        import base64
        from managed_loader import digest
        data = {"label": "RUNTIME_CONFIG", "page_size": "20"}
        static = {"label": "STATIC_IDENTITY_SENTINEL"}
        for mode in ("full", "cold"):
            initialized = []
            def observe(call, value):
                if call["method"] == "init":
                    initialized.append(json.loads(base64.b64decode(call["params"]["extend"])))
                return value
            loader, _ = drive(observe, profile={"schemaVersion": 1, "requiredNodes": ["S06"],
                                               "publicSourceData": static, "sourceConfig": data}, mode=mode)
            self.assertEqual([{"source": "/work/managed-source.py", "raw": True, "data": data}], initialized)
            receipt = loader.receipt()
            self.assertTrue(receipt["sourceDataPresent"])
            self.assertEqual(digest(data), receipt["sourceDataSha256"])
            self.assertNotIn("STATIC_IDENTITY_SENTINEL", json.dumps(receipt))

    def test_public_profile_data_is_frozen_and_invalid_data_is_rejected_before_init(self):
        import base64
        profile = {"schemaVersion": 1, "requiredNodes": ["S06"],
                   "publicSourceData": {"label": "static"}, "sourceConfig": {"label": "original"}}
        loader = Loader(SOURCE, RUNTIME, Broker(), profile)
        profile["sourceConfig"]["label"] = "changed"
        self.assertEqual("original", json.loads(base64.b64decode(
            loader.next()["params"]["extend"]))["data"]["label"])
        for data in (None, [], {"token": "not public"}, {"label": 20}, {"label": "x" * 1025}):
            with self.subTest(data_type=type(data).__name__), self.assertRaisesRegex(
                    Invalid, "T4_RPC_SOURCE_DATA_INVALID"):
                Loader(SOURCE, RUNTIME, Broker(), {**profile, "sourceConfig": data})
        static_only = Loader(SOURCE, RUNTIME, Broker(),
                             {"schemaVersion": 1, "requiredNodes": ["S06"],
                              "publicSourceData": {"label": "static"}})
        self.assertNotIn("data", json.loads(base64.b64decode(static_only.next()["params"]["extend"])))
        absent, _ = drive()
        self.assertFalse(absent.receipt()["sourceDataPresent"])

    def test_cold_worker_only_initializes_compares_home_and_destroys(self):
        loader, nodes = drive(profile={"schemaVersion": 1, "requiredNodes": ["S06", "S08"]}, mode="cold")
        self.assertEqual(["init", "home", "home", "destroy"], [call["method"] for call in loader.calls])
        self.assertIsNotNone(loader.receipt()["hotHomeCategoriesSha256"])
        self.assertNotEqual("pass", nodes["S08"]["status"])

    def test_source_config_types_and_explicit_empty_override_the_static_projection(self):
        import base64
        from managed_loader import T4Call, digest
        data = {"proxy_gateway": "https://gateway.example.test", "gateway_timeout": 30,
                "subtitle_enabled": False}
        projection.validate_source_config(data)
        T4Call.validate_source_data(data)
        for config in ({}, data):
            for mode in ("full", "cold"):
                profile = {"schemaVersion": 1, "requiredNodes": ["S06"],
                           "publicSourceData": {"label": "STATIC_ONLY"}, "sourceConfig": config}
                loader = Loader(SOURCE, RUNTIME, Broker(), profile, mode=mode)
                profile["sourceConfig"] = {"label": "changed"}
                ext = json.loads(base64.b64decode(loader.next()["params"]["extend"]))
                self.assertEqual(config, ext.get("data", {}))
                self.assertNotIn("STATIC_ONLY", json.dumps(ext))
                self.assertEqual(digest(config), loader.receipt()["sourceDataSha256"])
        for config in ({"gateway_timeout": True}, {"subtitle_enabled": 1},
                       {"subtitle_enabled": "false"}, {"proxy_base": "https://callback.example.test"}):
            with self.subTest(config=config):
                with self.assertRaises(ValueError):
                    projection.validate_source_config(config)
                with self.assertRaises(Invalid):
                    T4Call.validate_source_data(config)

    def test_dependency_and_no_user_configuration_need_real_initialization_and_bounded_ast(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S04", "S05", "S06"]}
        source = (b"import json\nfrom base.spider import Spider\nclass Spider(Spider):\n"
                  b" def init(self, extend=''):\n  print('{}'.format(extend))\n  pass\n")
        _, nodes = drive(profile=profile, source=source)
        self.assertEqual("pass", nodes["S04"]["status"])
        self.assertEqual(2, nodes["S04"]["importStatements"])
        self.assertEqual("pass", nodes["S05"]["status"])
        self.assertFalse(nodes["S05"]["credentialUse"])
        for extra in (b" def later(self):\n  import optional\n",
                      b" def later(self):\n  __import__('optional')\n"):
            _, nodes = drive(profile=profile, source=source + extra)
            self.assertEqual("blocked", nodes["S04"]["status"])
        for body in (b"  self.config = json.loads(extend)\n",
                     b"  print(open('/work/secret').read())\n",
                     b"  print(self.secret)\n", b"  print('{}'.format(self.config))\n"):
            configured = source.split(b"  print")[0] + body
            _, nodes = drive(profile=profile, source=configured)
            self.assertEqual("blocked", nodes["S05"]["status"])
        _, nodes = drive(profile=profile, source=source, ready=False)
        self.assertNotEqual("pass", nodes["S04"]["status"])
        self.assertNotEqual("pass", nodes["S05"]["status"])

    def test_host_image_decoder_uses_only_fetched_bytes_and_pipe_protocol(self):
        from types import SimpleNamespace
        with patch("managed_loader.subprocess.run", return_value=SimpleNamespace(
                stdout=b'{"streams":[{"width":320,"height":180}]}')) as run:
            self.assertEqual({"width": 320, "height": 180}, decode_image(b"fetched-image"))
        self.assertEqual(2, run.call_count)
        for call in run.call_args_list:
            self.assertEqual(b"fetched-image", call.kwargs["input"])
            self.assertEqual({}, call.kwargs["env"])
            self.assertEqual(5, call.kwargs["timeout"])
            self.assertIn("pipe:0", call.args[0])
            self.assertIn("pipe", call.args[0])

    def test_host_image_decoder_rejects_invalid_dimensions_before_decoding(self):
        from types import SimpleNamespace
        for stream in ({}, {"width": True, "height": 1}, {"width": -1, "height": 1},
                       {"width": 10000, "height": 10000}):
            with patch("managed_loader.subprocess.run", return_value=SimpleNamespace(
                    stdout=json.dumps({"streams": [stream]}).encode())) as run:
                with self.assertRaises(Invalid):
                    decode_image(b"image")
            self.assertEqual(1, run.call_count)

    def test_class_only_home_and_real_method_chain_include_second_and_last(self):
        loader, nodes = drive()
        self.assertEqual("pass", nodes["C01"]["status"])
        self.assertEqual("pass", nodes["C03"]["status"])
        self.assertEqual("pass", nodes["C07"]["status"])
        self.assertEqual("pass", nodes["C10"]["status"])
        self.assertEqual("pass", nodes["R01"]["status"])
        self.assertEqual("pass", nodes["M01"]["status"])
        self.assertFalse(loader.receipt()["successful"])
        self.assertFalse(loader.receipt()["requirementsFrozen"])
        self.assertEqual("pass", nodes["C09"]["status"])
        self.assertEqual(["init", "home", "category", "category", "category", "detail", "detail", "play", "destroy"],
                         [call["method"] for call in loader.calls])

    def test_unverified_initialization_cannot_advance_to_business_nodes(self):
        loader, nodes = drive(ready=False)
        self.assertEqual("blocked", nodes["S06"]["status"])
        self.assertEqual(["init"], [call["method"] for call in loader.calls])
        self.assertTrue(all(nodes[n]["status"] == "not_tested" for n in Loader.OBSERVED[1:]))

    def test_frozen_required_nodes_are_not_replaced_by_supported_subset(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S01", "S02", "S03", "S06",
                   "S07", "S08", "C01", "C02", "C03", "C05", "C07", "C09", "C10", "R01", "M01"]}
        loader, nodes = drive(profile=profile)
        self.assertFalse(loader.receipt()["successful"])
        self.assertTrue(loader.receipt()["requirementsFrozen"])
        self.assertEqual(set(profile["requiredNodes"]), set(nodes))
        for node in ("S01", "S02", "S03", "S08"):
            self.assertEqual("not_tested", nodes[node]["status"])
        self.assertEqual("fail", nodes["C02"]["status"])
        self.assertEqual("pass", nodes["C05"]["status"])
        self.assertEqual("blocked", nodes["S07"]["status"])
        self.assertEqual(hashlib.sha256(json.dumps(profile, sort_keys=True,
                         separators=(",", ":")).encode()).hexdigest(), loader.receipt()["profileSha256"])

    def test_loader_source_sha_order_and_duplicates_are_rejected(self):
        loader = Loader(SOURCE, RUNTIME, Broker())
        with self.assertRaises(Invalid):
            loader.source_ready({"kind": "loader_source", "sourceTextSha256": hashlib.sha256(SOURCE).hexdigest()})
        loader.next()
        with self.assertRaises(Invalid):
            loader.source_ready({"kind": "loader_source", "sourceTextSha256": "f" * 64})
        good = {"kind": "loader_source", "sourceTextSha256": hashlib.sha256(SOURCE).hexdigest()}
        loader.source_ready(good)
        with self.assertRaises(Invalid):
            loader.source_ready(good)

    def test_loaded_text_normalizes_newlines_but_preserves_original_artifact_identity(self):
        for source in (b"class Spider:\r\n    pass\r\n", b"class Spider:\r    pass\r"):
            loader = Loader(source, RUNTIME, Broker())
            loader.next()
            expected = hashlib.sha256(b"class Spider:\n    pass\n").hexdigest()
            loader.source_ready({"kind": "loader_source", "sourceTextSha256": expected})
            self.assertEqual(expected, loader.text_sha)
            self.assertEqual(hashlib.sha256(source).hexdigest(), loader.source_sha)
            self.assertNotEqual(loader.text_sha, loader.source_sha)

    def test_unsolicited_boolean_replayed_and_malformed_call_ids_fail(self):
        for ident in (0, 2, True):
            loader = Loader(SOURCE, RUNTIME, Broker())
            loader.next()
            with self.subTest(ident=ident), self.assertRaises(Invalid):
                loader.accept({"kind": "call_result", "id": ident, "ok": True, "result": {}})

    def test_source_declared_passes_are_not_evidence(self):
        def fake(call, result):
            if call["method"] == "home":
                return {"successful": True, "loaderNodeResults": [{"id": "C01", "status": "pass"}]}
            return result
        loader, nodes = drive(fake)
        self.assertEqual("fail", nodes["C01"]["status"])
        self.assertFalse(loader.receipt()["successful"])

    def test_false_pagination_retains_failure_and_checks_independent_downstream_nodes(self):
        def bad(call, result):
            if call["method"] == "category":
                result.update(limit=90, pagecount=9999, total=999999)
            return result
        loader, nodes = drive(bad)
        self.assertEqual("PAGINATION_METADATA_INVALID", nodes["C03"]["reason"])
        self.assertEqual("pass", nodes["C07"]["status"])
        self.assertEqual("pass", nodes["R01"]["status"])
        self.assertEqual("pass", nodes["M01"]["status"])
        self.assertFalse(loader.receipt()["successful"])
        self.assertEqual("destroy", loader.calls[-1]["method"])
        self.assertEqual(1, sum(row["method"] == "category" for row in loader.calls))

    def test_second_page_metadata_count_and_last_page_overlap_are_checked(self):
        for fault in ("total", "limit", "pagecount", "count", "overlap"):
            def changed(call, value):
                if call["method"] == "category" and call["params"]["pg"] == "2":
                    if fault in {"total", "limit", "pagecount"}:
                        value[fault] += 1
                    elif fault == "count":
                        value["list"].append(card("extra"))
                if call["method"] == "category" and call["params"]["pg"] == "3" and fault == "overlap":
                    value["list"] = [card("item-2")]
                return value
            with self.subTest(fault=fault):
                loader, nodes = drive(changed)
                self.assertEqual("fail", nodes["C03"]["status"])
                self.assertEqual("pass", nodes["C07"]["status"])
                self.assertEqual("destroy", loader.calls[-1]["method"])

    def test_wrong_detail_identity_and_default_content_for_invalid_id_fail(self):
        for fault in ("wrong", "default"):
            def wrong(call, value):
                if call["method"] == "detail":
                    if fault == "wrong" and value["list"]:
                        value["list"][0]["vod_id"] = "unrelated"
                    elif fault == "default" and not call["params"]["ids"]:
                        value["list"] = [card("unrelated")]
                return value
            _, nodes = drive(wrong)
            self.assertEqual("fail", nodes["C07"]["status"])

    def test_reviewed_compound_id_requires_guid_and_exact_cover(self):
        def cctv(call, value):
            if call["method"] == "category":
                value["list"][0]["vod_id"] += "###" + value["list"][0]["vod_pic"]
            return value
        profile = {"schemaVersion": 1, "requiredNodes": ["S06", "C07"],
                   "detailIdentity": "cctv-guid-cover"}
        _, nodes = drive(cctv, profile=profile)
        self.assertEqual("pass", nodes["C07"]["status"])
        _, nodes = drive(cctv)
        self.assertEqual("pass", nodes["C07"]["status"])
        _, nodes = drive(cctv, profile={**profile, "detailIdentity": "exact"})
        self.assertEqual("DETAIL_IDENTITY_MISMATCH", nodes["C07"]["reason"])

    def test_atvp_folder_identity_unwraps_only_one_known_host_prefix(self):
        for fault in ("none", "wrong-id", "wrong-tag", "repeated-prefix", "exact-profile", "legacy-profile"):
            def folder(call, value):
                if call["method"] == "category":
                    row = value["list"][0]
                    row["vod_id"] = "atvp_detail:" + row["vod_id"]
                    row["vod_tag"] = "folder" if fault != "wrong-tag" else "file"
                    if fault == "repeated-prefix":
                        row["vod_id"] = "atvp_detail:" + row["vod_id"]
                if call["method"] == "detail" and value["list"] and fault == "wrong-id":
                    value["list"][0]["vod_id"] = "unrelated"
                return value
            profile = {"schemaVersion": 1, "requiredNodes": ["S06", "C07"]}
            if fault == "exact-profile":
                profile["detailIdentity"] = "exact"
            elif fault == "legacy-profile":
                profile["detailIdentity"] = "exact-or-guid-cover"
            with self.subTest(fault=fault):
                _, nodes = drive(folder, profile=profile)
            self.assertEqual("pass" if fault == "none" else "fail", nodes["C07"]["status"])
            evidence = nodes["C07"]["identityEvidence"]
            self.assertFalse(evidence["exactMatch"])
            self.assertNotIn("atvp_detail:", json.dumps(evidence))

    def test_url_tail_or_matching_names_are_not_identity_evidence(self):
        from managed_loader import detail_identity
        url = "https://site.example.test/wp/item-1.html"
        for ident in (url, "unrelated", "other-prefix:item-1", "atvp_detail:item-1"):
            evidence = detail_identity(card(ident), card())
            self.assertTrue(evidence["nameMatch"])
            self.assertTrue(evidence["coverMatch"])
            self.assertFalse(any(evidence[key] for key in ("exactMatch", "guidCoverMatch", "atvpDetailPrefixMatch")))

    def test_magnet_is_an_at_offline_handoff_not_a_direct_media_failure(self):
        for token, valid in (("a" * 40, True), ("A" * 32, True), ("a" * 39, False), ("1" * 32, False)):
            url = "magnet:?xt=urn:btih:" + token + "&dn=PRIVATE_TITLE"
            def magnet(call, value):
                return {**value, "url": url} if call["method"] == "play" else value
            with self.subTest(token=token):
                loader, nodes = drive(magnet)
            self.assertEqual("pass" if valid else "blocked", nodes["R01"]["status"])
            self.assertEqual("SELECTED_PLAY_ID_TO_AT_OFFLINE_CONTRACT" if valid else "PLAYER_URL_INVALID",
                             nodes["R01"]["reason"])
            self.assertEqual("magnet", nodes["R01"]["playerRouteKind"])
            self.assertEqual(valid, nodes["R01"]["offlineResolverRequired"])
            self.assertEqual("other", loader.summary["playerUrlKind"])
            self.assertEqual("not_requested", loader.summary["mediaStatus"])
            self.assertNotIn("PRIVATE_TITLE", json.dumps(loader.receipt()))
            self.assertNotEqual("pass", nodes["M01"]["status"])
            if valid:
                self.assertFalse(nodes["R01"]["offlineDownloadVerified"])
                self.assertFalse(nodes["R01"]["clientPlaybackVerified"])
                self.assertEqual("AT_OFFLINE_DOWNLOAD_NOT_VALIDATED", nodes["M01"]["reason"])

    def test_magnet_only_required_profile_passes_without_fetching_or_starting_downloads(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S06", "C01", "C03", "C07", "C10", "R01"]}
        def magnet(call, value):
            return {"url": "magnet:?xt=urn:btih:" + "a" * 40, "parse": 1} if call["method"] == "play" else value
        with patch.object(Broker, "fetch", side_effect=AssertionError("magnet must not be fetched")):
            loader, nodes = drive(magnet, profile=profile)
        self.assertTrue(loader.receipt()["successful"])
        self.assertEqual("pass", nodes["R01"]["status"])
        self.assertNotIn("M01", nodes)
        self.assertEqual("destroy", loader.calls[-1]["method"])

    def test_malformed_magnet_authorities_fragments_and_controls_do_not_pass(self):
        from managed_loader import player_contract
        valid = "magnet:?xt=urn:btih:" + "a" * 40
        for url in (valid.replace("magnet:?", "magnet://host/?"),
                    valid.replace("magnet:?", "magnet:/path?"), valid + "#fragment", valid + "\n"):
            with self.subTest(url=url):
                self.assertFalse(player_contract({"url": url})["magnetInfoHashValid"])

    def test_jsonl_errors_and_recovery_are_not_satisfied_by_direct_handle(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S06", "S07", "S08", "R09"]}
        loader, nodes = drive(profile=profile, jsonl=True)
        self.assertEqual("blocked", nodes["S07"]["status"])
        self.assertTrue(nodes["S07"]["protocolChecksPassed"])
        self.assertEqual(3, nodes["S07"]["rejectedRequests"])
        self.assertIsNotNone(loader.receipt()["hotHomeCategoriesSha256"])
        self.assertEqual("not_tested", nodes["S08"]["status"])
        self.assertFalse(loader.receipt()["successful"])
        _, direct = drive(profile=profile)
        self.assertEqual("TOP_LEVEL_JSONL_NOT_CONFIRMED", direct["S07"]["reason"])

    def test_destroy_is_not_cleanup_and_normal_cleanup_is_not_failure_scenario_coverage(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S06", "R09"]}
        loader, nodes = drive(profile=profile)
        self.assertEqual("blocked", nodes["R09"]["status"])
        value = loader.cleaned_receipt(loader.receipt(), {"confirmed": True, "containerAbsent": True})
        node = next(r for r in value["nodes"] if r["id"] == "R09")
        self.assertEqual("blocked", node["status"])
        self.assertTrue(node["normalCleanupConfirmed"])
        self.assertFalse(value["successful"])
        with self.assertRaises(Invalid):
            loader.cleaned_receipt(loader.receipt(), {"confirmed": True, "containerAbsent": False})

    def test_replayed_page_and_inconsistent_last_page_do_not_pass(self):
        for defect in ("replay", "last"):
            def bad(call, result):
                if call["method"] == "category":
                    if defect == "replay":
                        result["list"] = [card()]
                    elif call["params"]["pg"] == "3":
                        result["total"] = 4
                return result
            _, nodes = drive(bad)
            self.assertEqual("fail", nodes["C03"]["status"])
            self.assertEqual("pass", nodes["C07"]["status"])

    def test_page_window_accepts_unknown_totals_variable_sizes_and_pinned_rows(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S06", "C03"],
                   "paginationMode": "page-window"}
        for shape in ("page-total", "no-metadata", "empty-terminal", "pinned",
                      "unknown-metadata", "advisory-limit", "reported-page"):
            def window(call, value):
                if call["method"] == "category":
                    page = int(call["params"]["pg"])
                    if shape == "page-total":
                        value.update(total=len(value["list"]), limit=20, pagecount=999999)
                    elif shape == "no-metadata":
                        value = {"list": value["list"]}
                    elif shape == "empty-terminal" and page == 2:
                        value["list"] = []
                    elif shape == "pinned":
                        value.update(limit=3)
                        value["list"] = [card("pin"), *value["list"]]
                    elif shape == "unknown-metadata":
                        value.update(page="unknown", limit=0, total=-1, pagecount=-1)
                    elif shape == "advisory-limit":
                        value.update(page=0, limit=1)
                        value["list"] = [card("pin"), *value["list"]]
                    elif shape == "reported-page":
                        value["page"] = page
                return value
            with self.subTest(shape=shape):
                loader, nodes = drive(window, profile=profile)
                self.assertEqual("pass", nodes["C03"]["status"])
                self.assertFalse(nodes["C03"]["exactTotalVerified"])
                self.assertFalse(nodes["C03"]["lastPageVerified"])
                self.assertEqual(shape in {"page-total", "empty-terminal", "pinned", "reported-page"},
                                 nodes["C03"]["pageNumbersVerified"])
                self.assertEqual(2, nodes["C03"]["pagesObserved"])
                self.assertEqual(2, sum(row["method"] == "category" for row in loader.calls))

    def test_page_window_rejects_replayed_pages_wrong_page_and_invalid_cards(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S06", "C03", "C07"],
                   "paginationMode": "page-window"}
        for fault in ("replay", "wrong-page", "duplicate", "invalid", "failed"):
            def window(call, value):
                if call["method"] == "category" and call["params"]["pg"] == "2":
                    if fault == "replay":
                        value["list"] = [card("item-1")]
                    elif fault == "wrong-page":
                        value["page"] = 1
                    elif fault == "duplicate":
                        value["list"] *= 2
                    elif fault == "invalid":
                        value["list"] = [{"vod_id": "x"}]
                    else:
                        raise ValueError("transport")
                return value
            with self.subTest(fault=fault):
                loader, nodes = drive(window, profile=profile)
                self.assertEqual("fail", nodes["C03"]["status"])
                self.assertEqual("pass", nodes["C07"]["status"])
                self.assertFalse(loader.receipt()["successful"])

    def test_pagination_mode_is_explicit_and_frozen(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S06", "C03"],
                   "paginationMode": "page-window"}
        loader = Loader(SOURCE, RUNTIME, Broker(), profile)
        profile["paginationMode"] = "exact-total"
        self.assertEqual("page-window", loader.profile["paginationMode"])
        for value in ("", "unknown", True, None, {}):
            with self.subTest(value=value), self.assertRaisesRegex(Invalid, "LOADER_PROFILE_INVALID"):
                Loader(SOURCE, RUNTIME, Broker(), {**profile, "paginationMode": value})

    def test_category_scope_does_not_start_unselected_detail_or_media_work(self):
        profile = {"schemaVersion": 1, "requiredNodes": ["S06", "C03"],
                   "optionalNodes": ["C01", "R09"], "paginationMode": "page-window"}
        loader, nodes = drive(profile=profile)
        self.assertEqual("pass", nodes["C03"]["status"])
        self.assertEqual(["init", "home", "category", "category", "destroy"],
                         [row["method"] for row in loader.calls])
        loader, _ = drive(profile={**profile, "optionalNodes": ["C07"]})
        self.assertIn("detail", [row["method"] for row in loader.calls])
        self.assertNotIn("play", [row["method"] for row in loader.calls])

    def test_invalid_first_page_cannot_supply_downstream_identity(self):
        def invalid(call, value):
            if call["method"] == "category":
                value["list"] = []
            return value
        loader, nodes = drive(invalid)
        self.assertEqual("fail", nodes["C03"]["status"])
        self.assertEqual("not_tested", nodes["C07"]["status"])
        self.assertFalse(any(row["method"] == "detail" for row in loader.calls))

    def test_single_page_is_not_required_to_have_a_second_page(self):
        def single(call, result):
            if call["method"] == "category":
                result.update(pagecount=1, total=1)
            return result
        loader, nodes = drive(single)
        self.assertEqual("pass", nodes["C03"]["status"])
        self.assertEqual(1, sum(row["method"] == "category" for row in loader.calls))

    def test_opaque_episode_id_is_preserved_and_not_persisted(self):
        captured = []
        def record(call, value):
            if call["method"] == "play":
                captured.append(copy.deepcopy(call["params"]))
            return value
        loader, _ = drive(record)
        self.assertEqual([{"flag": "flag", "play": "opaque$tail"}], captured)
        serialized = json.dumps(loader.receipt())
        for text in ("opaque", "private.m3u8", "media.example.com"):
            self.assertNotIn(text, serialized)

    def test_external_parser_page_is_diagnostic_and_not_a_direct_media_pass(self):
        def parser(call, value):
            if call["method"] == "play":
                value["parse"] = 1
            return value
        with patch.object(Broker, "fetch") as fetch:
            _, nodes = drive(parser)
        self.assertEqual(2, fetch.call_count)  # Cover and one bounded parser-page observation.
        self.assertEqual("PLAYER_EXTERNAL_PARSER_REQUIRED", nodes["R01"]["reason"])
        self.assertEqual("not_tested", nodes["M01"]["status"])

    def test_parser_page_and_script_are_observed_with_player_headers_without_new_permissions(self):
        import base64
        url = "https://reader.example.com/watch/42.html"
        script = "https://reader.example.com/assets/player.js"
        body = b'<main><video id="actual"></video></main><script src="/assets/player.js"></script>'
        broker = Broker()
        original_fetch = broker.fetch
        broker.fetch = unittest.mock.Mock(side_effect=lambda request: {
            "status": 200, "body": base64.b64encode(body).decode(), "headers": {"Content-Type": "text/html"}
        } if request["url"] == url else original_fetch(request))
        broker.fetch_resource = unittest.mock.Mock(return_value={
            "status": 200, "body": base64.b64encode(b"const actualPlayer = true;").decode(), "headers": {}})

        def parser(call, value):
            return ({"parse": 1, "url": url, "header": {"Referer": "https://reader.example.com/"}}
                    if call["method"] == "play" else value)

        with patch(__name__ + ".Broker", return_value=broker):
            loader, nodes = drive(parser)
        self.assertEqual("PLAYER_EXTERNAL_PARSER_REQUIRED", nodes["R01"]["reason"])
        evidence = loader.resource_evidence
        self.assertEqual(("R01", "observed"), (evidence["targetNode"], evidence["collectionStatus"]))
        self.assertEqual(hashlib.sha256(url.encode()).hexdigest(), evidence["resources"][0]["urlSha256"])
        self.assertEqual(body.decode(), evidence["resources"][0]["excerpts"][0]["text"])
        broker.fetch.assert_any_call({"method": "GET", "url": url, "body": "",
                                     "headers": {"Referer": "https://reader.example.com/"}})
        broker.fetch_resource.assert_called_once_with(script, url)
        self.assertEqual("not_tested", nodes["M01"]["status"])
        self.assertNotIn("policy", broker.__dict__)

    def test_parser_page_refusal_preserves_the_dependency_and_bounded_failure(self):
        from source_egress import Denied
        broker = Broker()
        original_fetch = broker.fetch
        def fetch(request):
            if "image.example.com" in request["url"]:
                return original_fetch(request)
            raise Denied("EGRESS_URL_DENIED")
        broker.fetch = fetch
        def parser(call, value):
            if call["method"] == "play":
                return {"parse": 1, "url": "https://unregistered.example.com/page.html"}
            return value
        with patch(__name__ + ".Broker", return_value=broker):
            loader, nodes = drive(parser)
        self.assertEqual("PLAYER_EXTERNAL_PARSER_REQUIRED", nodes["R01"]["reason"])
        self.assertEqual("EGRESS_URL_DENIED", loader.resource_evidence["failures"][0]["code"])
        self.assertEqual("unavailable", loader.resource_evidence["collectionStatus"])
        self.assertEqual("not_tested", nodes["M01"]["status"])

    def test_long_player_script_excerpts_keep_the_observed_media_setup_within_budget(self):
        import base64
        broker = Broker()
        body = b"x" * 14000 + b'player.setup({file:"https://media.example.com/actual.m3u8"});' + b"x" * 14000
        url = "https://reader.example.com/watch/42.html"
        broker.snapshot_resources = lambda: [
            (url, {"status": 200, "body": base64.b64encode(
                b'<script src="/assets/player.js"></script>').decode(), "headers": {}})]
        broker.fetch_resource = lambda *_: {
            "status": 200, "body": base64.b64encode(body).decode(), "headers": {}}
        value = collect_resource_evidence(broker, "a" * 64, target_node="R01")
        self.assertLessEqual(value["textBytes"], MAX_RESOURCE_TEXT)
        self.assertIn("actual.m3u8", json.dumps(value))
        self.assertTrue(all(hashlib.sha256(part["text"].encode()).hexdigest() == part["textSha256"]
                            for row in value["resources"] for part in row["excerpts"]))

    def test_player_pages_keep_configuration_before_large_library_excerpts(self):
        import base64
        broker = Broker()
        pages = []
        for index, size in enumerate((34992, 76322, 47017, 52829)):
            content = (b'<html><head>' + b'x' * 18000 + b'</head><script>'
                       + ('const playConfig%d = {"url":"encoded-state-%d","encrypt":1};'
                          % (index, index)).encode() + b'</script></html>')
            content += b'x' * (size - len(content))
            pages.append(('https://reader.example.com/page/' + str(index), {
                'status': 200, 'headers': {}, 'body': base64.b64encode(content).decode()}))
        script = b'x' * 2000 + b'decrypt(buffer);' + b'x' * 5000
        scripts = [
            ('https://reader.example.com/lib/' + str(index) + '.js', {
                'status': 200, 'headers': {}, 'body': base64.b64encode(script * 12).decode()})
            for index in range(4)]
        broker.snapshot_resources = lambda: list(reversed(pages))
        broker.fetch_resource = unittest.mock.Mock(side_effect=AssertionError('must reuse observed pages'))
        with patch('managed_loader.resource_links', side_effect=lambda body, page=False:
                   [url for url, _ in scripts] if page else []):
            lookup = dict(scripts)
            broker.fetch_resource = unittest.mock.Mock(side_effect=lambda url, parent: lookup[url])
            result = collect_resource_evidence(broker, 'a' * 64, target_node='R01')
        rows = [row for row in result['resources'] if row['kind'] == 'page']
        self.assertEqual(4, len(rows))
        for row, index in zip(rows, range(3, -1, -1), strict=True):
            self.assertIn('encoded-state-' + str(index), json.dumps(row))
            self.assertTrue(row['excerpts'])
        self.assertTrue(any(row['excerpts'] for row in result['resources'] if row['kind'] == 'script'))
        self.assertLessEqual(result['textBytes'], MAX_RESOURCE_TEXT)
        self.assertFalse(result['sourceCodeExecuted'])
        self.assertEqual(8, len(result['resources']))

    def test_player_script_discovery_prefers_latest_page_setup_within_existing_fetch_limit(self):
        import base64
        broker = Broker()
        root = 'https://reader.example.com/'
        old = b'<script src="/old-image.js"></script>'
        latest = (b'<script src="/crypto.js"></script><script src="/image.js"></script>'
                  b'<script src="/player-config.js"></script>')
        broker.snapshot_resources = lambda: [
            (root + 'watch/42', {'status': 200, 'headers': {}, 'body': base64.b64encode(latest).decode()}),
            (root, {'status': 200, 'headers': {}, 'body': base64.b64encode(old).decode()})]
        fetched = []
        def fetch(url, parent):
            fetched.append((url, parent))
            body = b'player.setup({file:"https://media.example.com/actual.m3u8"});'
            return {'status': 200, 'headers': {}, 'body': base64.b64encode(body).decode()}
        broker.fetch_resource = fetch
        result = collect_resource_evidence(broker, 'a' * 64, target_node='R01')
        self.assertEqual((root + 'player-config.js', root + 'watch/42'), fetched[0])
        self.assertEqual(hashlib.sha256((root + 'watch/42').encode()).hexdigest(),
                         result['resources'][0]['urlSha256'])
        self.assertLessEqual(len(result['resources']) + len(result['failures']), 8)
        self.assertIn('actual.m3u8', json.dumps(result))

    def test_play_action_scripts_precede_other_pages_and_keep_each_setup_and_discovery_call(self):
        import base64
        root = 'https://reader.example.com/'
        call = {'id': 7, 'method': 'play', 'inputSha256': 'b' * 64}
        def response(body, action):
            return {'status': 200, 'headers': {}, 'body': base64.b64encode(body).decode(),
                    'observedDuringCall': action}
        page = b'<script src="/player-config.js"></script><script src="/player-small.js"></script>'
        broker = Broker()
        broker.snapshot_resources = lambda: [
            (root + 'detail', response(b'<script src="/old.js"></script>',
                                       {**call, 'id': 8, 'method': 'detail'})),
            (root + 'watch', response(page, call)),
            (root + 'category', response(b'<main>category</main>', {**call, 'id': 5, 'method': 'category'}))]
        bodies = {
            root + 'player-config.js': b'x' * 4000 + b'player.setup({file:"long-observed"});' + b'x' * 4000,
            root + 'player-small.js': b'x' * 3500 + b'player.load({file:"small-observed"});',
            root + 'old.js': b'decrypt(x);' * 2000}
        broker.fetch_resource = unittest.mock.Mock(side_effect=lambda url, parent: {
            'status': 200, 'headers': {}, 'body': base64.b64encode(bodies[url]).decode()})
        result = collect_resource_evidence(broker, 'a' * 64, target_node='R01')
        rows = result['resources']
        self.assertEqual(hashlib.sha256((root + 'watch').encode()).hexdigest(), rows[0]['urlSha256'])
        for row, marker in zip(rows[1:3], ('long-observed', 'small-observed'), strict=True):
            self.assertEqual('script', row['kind'])
            self.assertEqual(rows[0]['urlSha256'], row['parentUrlSha256'])
            self.assertEqual(call, row['discoveredFromCall'])
            self.assertNotIn('observedDuringCall', row)
            self.assertIn(marker, json.dumps(row))
        self.assertTrue(all(row['excerpts'] for row in rows))
        self.assertLessEqual(result['textBytes'], MAX_RESOURCE_TEXT)
        self.assertLessEqual(len(rows) + len(result['failures']), 8)

    def test_nested_play_scripts_inherit_discovery_without_claiming_execution(self):
        import base64
        root = 'https://reader.example.com/'
        call = {'id': 7, 'method': 'play', 'inputSha256': 'b' * 64}
        broker = Broker()
        broker.snapshot_resources = lambda: [(root + 'watch', {
            'status': 200, 'headers': {}, 'observedDuringCall': call,
            'body': base64.b64encode(b'<script src="/player.js"></script>').decode()})]
        broker.fetch_resource = lambda url, parent: {
            'status': 200, 'headers': {}, 'body': base64.b64encode(
                b'import "./setup.js";' if url.endswith('/player.js') else b'player.setup({file:"actual"});').decode()}
        result = collect_resource_evidence(broker, 'a' * 64, target_node='R01')
        self.assertEqual(3, len(result['resources']))
        self.assertTrue(all(row['discoveredFromCall'] == call for row in result['resources'][1:]))
        self.assertEqual(result['resources'][1]['urlSha256'], result['resources'][2]['parentUrlSha256'])
        self.assertFalse(result['sourceCodeExecuted'])

    def test_small_player_pages_keep_late_configuration_and_short_challenge_stays_complete(self):
        import base64
        broker = Broker()
        body = (b'<html>' + b'x' * 3500 + b'<script>var mediaConfig={"url":"late-state"};</script></html>')
        broker.snapshot_resources = lambda: [
            ('https://reader.example.com/' + str(index), {
                'status': 200, 'headers': {}, 'body': base64.b64encode(body).decode()})
            for index in range(4)]
        result = collect_resource_evidence(broker, 'a' * 64, target_node='R01')
        self.assertTrue(all('late-state' in json.dumps(row) for row in result['resources']))
        shell = b'<html><script>document.cookie="check=fixture";location.reload();</script><body>Loading</body></html>'
        broker.snapshot_resources = lambda: [('https://reader.example.com/', {
            'status': 200, 'headers': {}, 'body': base64.b64encode(shell).decode()})]
        result = collect_resource_evidence(broker, 'a' * 64, target_node='R01')
        self.assertEqual(1, len(result['resources'][0]['excerpts']))
        self.assertEqual(shell.decode(), result['resources'][0]['excerpts'][0]['text'])

    def test_player_excerpt_packing_keeps_utf8_hashes_and_does_not_repeat_overlapping_ranges(self):
        import base64
        broker = Broker()
        word = '\u6d4b\u8bd5'.encode()
        body = b'<html>' + word * 2000 + b'<script>var player_state={"url":"actual"};</script>' + word * 4000
        broker.snapshot_resources = lambda: [('https://reader.example.com/' + str(index), {
            'status': 200, 'headers': {}, 'body': base64.b64encode(body).decode()}) for index in range(4)]
        result = collect_resource_evidence(broker, 'a' * 64, target_node='R01')
        total = 0
        for row in result['resources']:
            ranges = []
            for part in row['excerpts']:
                encoded = part['text'].encode()
                self.assertEqual(hashlib.sha256(encoded).hexdigest(), part['textSha256'])
                self.assertFalse(any(part['offset'] < end and part['offset'] + len(encoded) > start
                                     for start, end in ranges))
                ranges.append((part['offset'], part['offset'] + len(encoded)))
                total += len(encoded)
            self.assertLessEqual(len(row['excerpts']), 48)
        self.assertEqual(total, result['textBytes'])
        self.assertLessEqual(total, MAX_RESOURCE_TEXT)

    def test_error_document_is_not_media(self):
        import base64
        with patch.object(Broker, "fetch", return_value={
            "status": 200, "headers": {}, "body": base64.b64encode(b"<html>failed</html>").decode()}):
            _, nodes = drive()
        self.assertEqual("MEDIA_PREFIX_NOT_RECOGNIZED", nodes["M01"]["reason"])

    def test_external_image_decoder_must_match_exact_fetched_bytes(self):
        import base64
        broker = Broker()
        broker.fetch = lambda value: {"status": 200, "headers": {"Content-Type": "image/png"},
                                     "body": base64.b64encode(b"fixture-image").decode()}
        loader = Loader(SOURCE, RUNTIME, broker)
        broker.verify_image = lambda body: {"sha256": "f" * 64, "width": 2, "height": 2}
        loader.image({"vod_pic": "https://image.example.com/image.png"})
        self.assertEqual("COVER_DECODER_IDENTITY_INVALID", loader.nodes[-1]["reason"])
        self.assertEqual("blocked", loader.nodes[-1]["status"])
        broker.verify_image = lambda body: {"sha256": hashlib.sha256(body).hexdigest(), "width": 2, "height": 2}
        loader.image({"vod_pic": "https://image.example.com/image.png"})
        self.assertEqual("pass", loader.nodes[-1]["status"])

    def test_cover_callback_uses_same_worker_and_host_decoder_with_call_identity(self):
        import base64
        body = b"\xff\xd8\xff" + b"x" * 160000 + b"\xff\xd9"
        loader = Loader(SOURCE, RUNTIME, Broker(), {"schemaVersion": 1, "requiredNodes": ["S06", "C09"]})
        loader.iterator = loader.sample_image({"vod_pic": "http://managed.invalid/local?type=image&url=a%2Bb"})
        command = loader.next()
        self.assertEqual("proxy", command["method"])
        self.assertEqual({"target": "/local?type=image&url=a%2Bb", "range": "", "head": False}, command["params"])
        result = {"status": 200, "mime": "image/jpeg", "headers": {}, "bytes": len(body),
                  "body": base64.b64encode(body).decode(), "sha256": hashlib.sha256(body).hexdigest()}
        with patch.object(loader.broker, "fetch", side_effect=AssertionError("must use source callback")):
            finish = loader.accept({"kind": "call_result", "id": command["id"], "ok": True, "result": result})
        self.assertEqual("finish", finish["kind"])
        node = loader.nodes[-1]
        self.assertEqual(("pass", len(body)), (node["status"], node["bytes"]))
        self.assertEqual(loader.calls[-1]["id"], node["proxyObservation"]["workerCallId"])
        self.assertEqual(loader.calls[-1]["inputSha256"], node["proxyObservation"]["paramsSha256"])
        self.assertEqual(loader.calls[-1]["outputSha256"], node["proxyObservation"]["resultSha256"])
        self.assertIsNone(loader.resource_evidence)

    def test_invalid_callback_body_cannot_be_marked_decoded(self):
        import base64
        for changed in ({"sha256": "f" * 64}, {"bytes": 99}, {"status": 206},
                        {"body": "invalid-base64"}, {"body": "x" * 700000}):
            loader = Loader(SOURCE, RUNTIME, Broker())
            loader.iterator = loader.sample_image({"vod_pic": "http://managed.invalid/local?type=image"})
            command = loader.next()
            result = {"status": 200, "mime": "image/png", "headers": {}, "bytes": 5,
                      "body": base64.b64encode(b"image").decode(), "sha256": hashlib.sha256(b"image").hexdigest()}
            result.update(changed)
            loader.accept({"kind": "call_result", "id": command["id"], "ok": True, "result": result})
            self.assertEqual(("blocked", "COVER_PROXY_RESULT_INVALID"),
                             (loader.nodes[-1]["status"], loader.nodes[-1]["reason"]))

    def test_invalid_image_bytes_are_a_repairable_failure_but_missing_decoder_is_blocked(self):
        import subprocess
        for error, status, reason in (
            (subprocess.CalledProcessError(1, ["ffprobe"]), "fail", "COVER_BYTES_INVALID"),
            (FileNotFoundError(), "blocked", "COVER_DECODE_UNAVAILABLE_OR_INVALID"),
            (subprocess.TimeoutExpired(["ffprobe"], 5), "blocked", "COVER_DECODE_UNAVAILABLE_OR_INVALID"),
        ):
            with self.subTest(error=type(error).__name__):
                broker = Broker()
                broker.verify_image = unittest.mock.Mock(side_effect=error)
                loader = Loader(SOURCE, RUNTIME, broker)
                loader.image({"vod_pic": "https://image.example.com/cover.jpg"})
                self.assertEqual((status, reason), (loader.nodes[-1]["status"], loader.nodes[-1]["reason"]))
                evidence = loader.resource_evidence
                self.assertEqual(loader.source_sha, evidence["artifactSha256"])
                self.assertEqual("not_configured", evidence["collectionStatus"])
                self.assertEqual(b"image".hex(), evidence["image"]["prefixHex"])
                self.assertEqual("unknown", evidence["image"]["signature"])

    def test_public_resource_discovery_is_automatic_bounded_and_never_executes_scripts(self):
        import base64
        broker = Broker()
        root = "https://reader.example.com/"
        broker.policy = {"resourceEvidence": {"entryUrls": [root]}}
        fetched = []
        bodies = {
            root: (b'<script type="module" src="/assets/app.js"></script>'
                   b'<link rel="modulepreload" href="/assets/image.js">'
                   b'<script src="https://other.example.com/foreign.js"></script>'),
            root + "assets/app.js": (b'import "./child.js";const deps=[`assets/lazy-image.js`];\n'
                                     + b"x" * 7000 + b"arrayBuffer();" + b"y" * 9000),
            root + "assets/image.js": b"throw Error('must not execute');createObjectURL(decoded);",
            root + "assets/child.js": b"/* public dependency */",
            root + "assets/lazy-image.js": b"decodeImage(buffer);",
        }
        def fetch(url, parent):
            fetched.append((url, parent))
            return {"status": 200, "body": base64.b64encode(bodies[url]).decode(), "headers": {}}
        broker.fetch_resource = fetch
        value = collect_resource_evidence(broker, "a" * 64, {})
        self.assertEqual(set(bodies), {url for url, _ in fetched})
        self.assertEqual("observed", value["collectionStatus"])
        self.assertFalse(value["sourceCodeExecuted"])
        self.assertLessEqual(value["textBytes"], MAX_RESOURCE_TEXT)
        text = json.dumps(value)
        self.assertIn("createObjectURL(decoded)", text)
        self.assertIn("arrayBuffer()", text)
        self.assertIn("foreign.js", text)  # A page link is evidence, not permission to fetch it.
        self.assertNotIn("https://other.example.com/foreign.js", {url for url, _ in fetched})
        for row in value["resources"]:
            for excerpt in row["excerpts"]:
                self.assertEqual(hashlib.sha256(excerpt["text"].encode()).hexdigest(), excerpt["textSha256"])
        self.assertEqual(["./dep.mjs"], resource_links(b'import "./dep.mjs";'))

    def test_spa_html_fallback_is_not_treated_as_a_javascript_excerpt(self):
        import base64
        broker = Broker()
        root = "https://reader.example.com/"
        broker.policy = {"resourceEvidence": {"entryUrls": [root]}}
        body = b'<!doctype html><html><script src="/missing.js"></script></html>'
        broker.fetch_resource = lambda *args: {
            "status": 200, "body": base64.b64encode(body).decode(), "headers": {}}
        value = collect_resource_evidence(broker, "a" * 64, {})
        self.assertEqual(1, len(value["resources"]))
        self.assertEqual(1, len(value["failures"]))
        self.assertEqual("RESOURCE_SCRIPT_RETURNED_HTML", value["failures"][0]["code"])
        self.assertGreater(value["textBytes"], 0)

    def test_category_failure_captures_observed_page_without_configuration_or_refetch(self):
        import base64
        url = "https://reader.example.com/category/one?page=1"
        body = b'<main><ul class="new-list"><li data-id="42">Actual item</li></ul></main>'
        broker = Broker()
        broker.snapshot_resources = lambda: [
            (url, {"status": 200, "body": base64.b64encode(body).decode(), "headers": {}})]
        broker.fetch_resource = unittest.mock.Mock(side_effect=AssertionError("must reuse response"))
        with patch(__name__ + ".Broker", return_value=broker):
            loader, nodes = drive(lambda call, value: {**value, "list": []}
                                 if call["method"] == "category" else value)
        self.assertEqual("fail", nodes["C03"]["status"])
        evidence = loader.resource_evidence
        self.assertEqual(("C03", None, "observed"),
                         (evidence["targetNode"], evidence["image"], evidence["collectionStatus"]))
        row = evidence["resources"][0]
        self.assertEqual(hashlib.sha256(body).hexdigest(), row["responseSha256"])
        self.assertEqual(body.decode(), row["excerpts"][0]["text"])
        self.assertEqual(hashlib.sha256(url.encode()).hexdigest(), row["urlSha256"])
        broker.fetch_resource.assert_not_called()
        loader.record("C07", "fail", "DETAIL_OUTPUT_INVALID")
        self.assertIs(evidence, loader.resource_evidence)

    def test_large_html_and_json_keep_structure_and_utf8_within_shared_excerpt_budget(self):
        import base64
        broker = Broker()
        html = (b"<html><head>" + b"x" * 10000 + b'</head><main class="new-list">'
                + b'<article data-id="1">actual item</article>' * 150
                + b"</main><script>window.__NEXT_DATA__={}</script></html>")
        payload = b'{"items":[' + b'{"title":"actual item","id":1},' * 1500 + b"{}]}"
        broker.snapshot_resources = lambda: [
            ("https://reader.example.com/list", {"status": 200, "headers": {},
                                               "body": base64.b64encode(html).decode()}),
            ("https://reader.example.com/api", {"status": 200, "headers": {},
                                              "body": base64.b64encode(payload).decode()})]
        result = collect_resource_evidence(broker, "a" * 64, target_node="C03")
        self.assertLessEqual(result["textBytes"], MAX_RESOURCE_TEXT)
        self.assertIn('class="new-list"', json.dumps(result).replace('\\"', '"'))
        self.assertIn('"items"', json.dumps(result).replace('\\"', '"'))
        self.assertEqual(result["textBytes"], sum(len(part["text"].encode())
                         for row in result["resources"] for part in row["excerpts"])
                         + sum(len(json.dumps(row["jsonStructure"], separators=(",", ":")).encode())
                               for row in result["resources"] if "jsonStructure" in row))
        self.assertTrue(resource_excerpts(payload, page=True))

    def test_evidence_failure_keeps_original_image_failure_and_stops_at_fetch_limit(self):
        import base64
        broker = Broker()
        root = "https://reader.example.com/"
        broker.policy = {"resourceEvidence": {"entryUrls": [root]}}
        seen = []
        def fetch(url, parent):
            seen.append(url)
            if parent is not None:
                raise OSError("PRIVATE")
            body = "".join('<script src="/%s.js"></script>' % i for i in range(20)).encode()
            return {"status": 200, "body": base64.b64encode(body).decode(), "headers": {}}
        broker.fetch_resource = fetch
        result = collect_resource_evidence(broker, "a" * 64, {})
        self.assertEqual(8, len(seen))
        self.assertEqual(7, len(result["failures"]))
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_hls_uses_selected_variant_and_first_middle_last_with_player_headers(self):
        import base64
        try:
            import m3u8
        except ImportError:
            self.skipTest("m3u8 is an explicit optional host dependency")
        seen = []
        broker = Broker()
        def fetch(value):
            seen.append(value)
            if value["url"].endswith("master.m3u8"):
                body = b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmedia.m3u8\n"
            elif value["url"].endswith("media.m3u8"):
                body = b"#EXTM3U\n#EXT-X-TARGETDURATION:6\n" + b"".join(
                    b"#EXTINF:6,\n" + str(i).encode() + b".ts\n" for i in range(5)) + b"#EXT-X-ENDLIST\n"
            else:
                body = bytes([0x47]) + bytes(187)
                body *= 3
            return {"status": 200, "headers": {}, "body": base64.b64encode(body).decode()}
        broker.fetch = fetch
        loader = Loader(SOURCE, RUNTIME, broker)
        loader.media({"url": "https://media.example.com/master.m3u8", "header": {"Referer": "https://site.example.com/"}})
        nodes = {row["id"]: row for row in loader.nodes}
        for node in ("M01", "M02", "M03", "M04", "M05", "R06"):
            self.assertEqual("pass", nodes[node]["status"])
        self.assertEqual([0, 2, 4], [row["index"] for row in nodes["M05"]["samples"]])
        self.assertTrue(all(row["headers"]["Referer"] == "https://site.example.com/" for row in seen))

        loader = Loader(SOURCE, RUNTIME, broker, {"schemaVersion": 1, "requiredNodes": ["S06", "M07"]})
        loader.media({"url": "https://media.example.com/master.m3u8", "header": {}})
        window = next(r for r in loader.nodes if r["id"] == "M07")
        self.assertEqual("pass", window["status"])
        self.assertEqual([0, 1, 2], [r["index"] for r in window["samples"]])
        self.assertFalse(window["fullSegments"])
        self.assertFalse(window["clientPlaybackVerified"])

    def test_encrypted_hls_does_not_claim_clear_segment_validation(self):
        try:
            import m3u8
        except ImportError:
            self.skipTest("m3u8 is an explicit optional host dependency")
        loader = Loader(SOURCE, RUNTIME, Broker())
        loader.hls("https://media.example.com/media.m3u8",
                   b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-KEY:METHOD=AES-128,URI="key"\n#EXTINF:6,\none.ts\n',
                   {})
        nodes = {row["id"]: row for row in loader.nodes}
        self.assertEqual("blocked", nodes["M04"]["status"])
        self.assertNotIn("M05", nodes)

    def hls_fixture(self, objects, *, required=(), ignore_range=False):
        import base64
        import re
        seen = []
        broker = Broker()
        def fetch(request):
            seen.append(request)
            body = objects[request["url"].rsplit("/", 1)[-1]]
            if isinstance(body, list):
                body = body.pop(0)
            response_headers, status = {}, 200
            if "Range" in request["headers"] and not ignore_range:
                start, end = map(int, re.fullmatch(r"bytes=(\d+)-(\d+)", request["headers"]["Range"]).groups())
                end = min(end, len(body) - 1)
                response_headers["Content-Range"] = "bytes %d-%d/%d" % (start, end, len(body))
                body, status = body[start:end + 1], 206
            return {"status": status, "headers": response_headers, "body": base64.b64encode(body).decode()}
        broker.fetch = fetch
        loader = Loader(SOURCE, RUNTIME, broker, {"schemaVersion": 1, "requiredNodes": list(required) or ["M04", "M05"]})
        return loader, seen

    def test_hls_aes_key_rotation_at_same_uri_and_none_keep_clear_byte_evidence(self):
        from Crypto.Cipher import AES
        from Crypto.Util.Padding import pad
        clear = (b"\x47" + bytes(187)) * 3
        key_a, key_b = bytes(range(16)), bytes(range(16, 32))
        objects = {"key": [key_a, key_b], "0.ts": AES.new(key_a, AES.MODE_CBC, (7).to_bytes(16, "big")).encrypt(pad(clear, 16)),
                   "2.ts": AES.new(key_b, AES.MODE_CBC, (99).to_bytes(16, "big")).encrypt(pad(clear, 16)), "4.ts": clear}
        loader, seen = self.hls_fixture(objects)
        loader.hls("https://media.example.com/list.m3u8", b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:7\n'
                   b'#EXT-X-KEY:METHOD=AES-128,URI="key"\n#EXTINF:6,\n0.ts\n#EXTINF:6,\n1.ts\n'
                   b'#EXT-X-KEY:METHOD=AES-128,URI="key",IV=0x63\n#EXTINF:6,\n2.ts\n'
                   b'#EXT-X-KEY:METHOD=NONE\n#EXTINF:6,\n3.ts\n#EXTINF:6,\n4.ts\n#EXT-X-ENDLIST\n',
                   {"Referer": "https://site.example.com/"})
        nodes = {row["id"]: row for row in loader.nodes}
        self.assertEqual("pass", nodes["M05"]["status"])
        self.assertEqual([1, 2], [r["declaration"] for r in nodes["M04"]["resources"]])
        self.assertEqual(2, len([r for r in seen if r["url"].endswith("/key")]))
        self.assertEqual([0, 2, 4], [r["index"] for r in nodes["M05"]["samples"]])
        for row in nodes["M05"]["samples"]:
            self.assertEqual(hashlib.sha256(clear).hexdigest(), row["clearSha256"])
            if "encryption" in row:
                self.assertTrue(row["encryption"]["paddingChecked"])
                self.assertFalse(row["encryption"]["authenticated"])
        self.assertNotIn("encryption", nodes["M05"]["samples"][-1])
        self.assertTrue(all(r["headers"]["Referer"] == "https://site.example.com/" for r in seen))

    def test_hls_aes_partial_segment_does_not_claim_padding_or_full_media(self):
        from Crypto.Cipher import AES
        from Crypto.Util.Padding import pad
        clear = (b"\x47" + bytes(187)) * 600
        key = bytes(range(16))
        loader, _ = self.hls_fixture({"key": key, "one.ts": AES.new(key, AES.MODE_CBC, bytes(16)).encrypt(pad(clear, 16))})
        loader.hls("https://media.example.com/list.m3u8",
                   b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-KEY:METHOD=AES-128,URI="key"\n#EXTINF:6,\none.ts\n', {})
        node = next(r for r in loader.nodes if r["id"] == "M05")
        self.assertEqual("pass", node["status"])
        self.assertTrue(node["samples"][0]["encryption"]["prefixOnly"])
        self.assertFalse(node["samples"][0]["encryption"]["paddingChecked"])
        self.assertFalse(node["fullSegments"])

    @staticmethod
    def iso_box(kind, body=b""):
        return (len(body) + 8).to_bytes(4, "big") + kind + body

    def test_hls_map_and_implicit_byte_ranges_cover_selected_and_continuous_samples(self):
        init = self.iso_box(b"ftyp", b"iso6\x00\x00\x00\x00") + self.iso_box(b"moov")
        segment = self.iso_box(b"moof") + self.iso_box(b"mdat", bytes(512))
        loader, seen = self.hls_fixture({"bundle": init + segment * 3}, required=("M07",))
        manifest = ('#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:6\n'
                    '#EXT-X-MAP:URI="bundle",BYTERANGE="%d@0"\n' % len(init))
        for index in range(3):
            manifest += '#EXTINF:6,\n#EXT-X-BYTERANGE:%d%s\nbundle\n' % (
                len(segment), "@%d" % len(init) if index == 0 else "")
        loader.hls("https://media.example.com/list.m3u8", manifest.encode(), {})
        nodes = {row["id"]: row for row in loader.nodes}
        self.assertTrue(all(nodes[id]["status"] == "pass" for id in ("M04", "M05", "M07")))
        self.assertEqual(7, len(seen))
        self.assertEqual([len(init) + len(segment) * i for i in range(3)],
                         [r["start"] for r in nodes["M05"]["samples"]])
        self.assertTrue(all(r["container"] == "fmp4" for r in nodes["M05"]["samples"]))
        self.assertEqual(1, len(nodes["M04"]["resources"]))

    def test_hls_encrypted_map_keeps_its_declaration_key_after_method_none(self):
        from Crypto.Cipher import AES
        from Crypto.Util.Padding import pad
        key = bytes(range(16))
        init = self.iso_box(b"ftyp", b"iso6\x00\x00\x00\x00") + self.iso_box(b"moov")
        fragment = self.iso_box(b"moof") + self.iso_box(b"mdat", bytes(512))
        loader, _ = self.hls_fixture({"key": key, "init": AES.new(key, AES.MODE_CBC, (1).to_bytes(16, "big")).encrypt(pad(init, 16)),
                                      "fragment": fragment})
        loader.hls("https://media.example.com/list.m3u8",
                   b'#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:6\n'
                   b'#EXT-X-KEY:METHOD=AES-128,URI="key",IV=0x1\n#EXT-X-MAP:URI="init"\n'
                   b'#EXT-X-KEY:METHOD=NONE\n#EXTINF:6,\nfragment\n', {})
        nodes = {r["id"]: r for r in loader.nodes}
        self.assertEqual("pass", nodes["M05"]["status"])
        self.assertEqual(["key", "init"], [r["kind"] for r in nodes["M04"]["resources"]])
        self.assertNotIn("encryption", nodes["M05"]["samples"][0])

    def test_hls_invalid_resources_keep_the_first_failed_gate(self):
        cases = (
            ('#EXT-X-KEY:METHOD=SAMPLE-AES,URI="key"\n', "M04", "HLS_ENCRYPTION_UNSUPPORTED", {}),
            ('#EXT-X-KEY:METHOD=AES-128,URI="key"\n', "M04", "HLS_KEY_RESPONSE_INVALID", {"key": bytes(17)}),
            ('#EXT-X-MAP:URI="init"\n', "M04", "HLS_FMP4_INVALID", {"init": b"<html>not media</html>"}),
            ('#EXT-X-KEY:METHOD=AES-128,URI="key"\n#EXT-X-MAP:URI="init"\n',
             "M04", "HLS_INIT_IV_REQUIRED", {"key": bytes(16), "init": bytes(16)}),
            ('#EXT-X-BYTERANGE:564\n', "M03", "HLS_BYTERANGE_INVALID", {}),
        )
        for tags, node, reason, objects in cases:
            with self.subTest(reason=reason):
                loader, _ = self.hls_fixture(objects)
                loader.hls("https://media.example.com/list.m3u8",
                           ('#EXTM3U\n#EXT-X-TARGETDURATION:6\n' + tags + '#EXTINF:6,\none.ts\n').encode(), {})
                failed = next(r for r in loader.nodes if r["id"] == node)
                self.assertEqual(("blocked", reason), (failed["status"], failed["reason"]))

    def test_hls_ignored_byte_range_is_not_accepted_as_the_selected_segment(self):
        clear = (b"\x47" + bytes(187)) * 3
        loader, _ = self.hls_fixture({"one.ts": clear * 2}, ignore_range=True)
        loader.hls("https://media.example.com/list.m3u8",
                   b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n#EXT-X-BYTERANGE:564@564\none.ts\n', {})
        self.assertEqual("HLS_RANGE_RESPONSE_INVALID", next(r for r in loader.nodes if r["id"] == "M05")["reason"])

    def test_mp4_ranges_reuse_initial_prefix_keep_headers_and_cover_short_resources(self):
        import base64
        import re
        for total in (12, 65536, 65537, 262144):
            with self.subTest(total=total):
                body = (b"\x00\x00\x00\x18ftypisom" + bytes(range(256)) * 1024)[:total]
                seen = []
                broker = Broker()
                def fetch(request):
                    seen.append(request)
                    start, end = map(int, re.fullmatch(r"bytes=(\d+)-(\d+)", request["headers"]["Range"]).groups())
                    end = min(end, total - 1)
                    return {"status": 206, "body": base64.b64encode(body[start:end + 1]).decode(),
                            "headers": {"content-range": "bytes %d-%d/%d" % (start, end, total)}}
                broker.fetch = fetch
                loader = Loader(SOURCE, RUNTIME, broker, {"schemaVersion": 1, "requiredNodes": ["M01", "M06"]})
                loader.media({"url": "https://media.example.com/asset?token=PRIVATE",
                              "header": {"Referer": "https://site.example.com/", "range": "bytes=0-10"}})
                nodes = {row["id"]: row for row in loader.nodes}
                self.assertEqual("pass", nodes["M01"]["status"])
                self.assertEqual("pass", nodes["M06"]["status"])
                self.assertEqual(total, nodes["M06"]["totalBytes"])
                starts = sorted({0, max(0, (total - 65536) // 2), max(0, total - 65536)})
                self.assertEqual(starts, [row["start"] for row in nodes["M06"]["samples"]])
                self.assertEqual(len(starts), len(seen))
                self.assertTrue(all(request["headers"]["Referer"] == "https://site.example.com/"
                                    and "range" not in request["headers"] for request in seen))
                self.assertFalse(nodes["M06"]["clientPlaybackVerified"])
                self.assertNotIn("PRIVATE", json.dumps(loader.receipt()))
                for sample in nodes["M06"]["samples"]:
                    self.assertEqual(hashlib.sha256(body[sample["start"]:sample["end"] + 1]).hexdigest(),
                                     sample["responseSha256"])

    def test_mp4_range_defects_do_not_erase_a_valid_prefix_or_claim_seek(self):
        import base64
        from source_egress import Denied
        for defect, reason in (("ignored", "MP4_RANGE_NOT_SUPPORTED"), ("missing", "MP4_CONTENT_RANGE_INVALID"),
                               ("short", "MP4_RANGE_MISMATCH"), ("wrong-offset", "MP4_RANGE_MISMATCH"),
                               ("changed-total", "MP4_TOTAL_CHANGED"), ("budget", "EGRESS_BYTE_BUDGET")):
            with self.subTest(defect=defect):
                count = []
                first = b"\x00\x00\x00\x18ftypisom" + bytes(65524)
                def fetch(request):
                    count.append(request)
                    if len(count) == 1:
                        return {"status": 206, "body": base64.b64encode(first).decode(),
                                "headers": {"Content-Range": "bytes 0-65535/262144"}}
                    if defect == "budget":
                        raise Denied("EGRESS_BYTE_BUDGET")
                    return {"status": 200 if defect == "ignored" else 206,
                            "body": base64.b64encode(bytes(12 if defect == "short" else 65536)).decode(),
                            "headers": {} if defect == "missing" else {"Content-Range": (
                                "bytes 0-65535/262144" if defect == "wrong-offset" else
                                "bytes 98304-163839/262145" if defect == "changed-total" else
                                "bytes 98304-163839/262144")}}
                broker = Broker()
                broker.fetch = fetch
                loader = Loader(SOURCE, RUNTIME, broker, {"schemaVersion": 1, "requiredNodes": ["M01", "M06"]})
                loader.media({"url": "https://media.example.com/asset.mp4", "header": {}})
                nodes = {row["id"]: row for row in loader.nodes}
                self.assertEqual("pass", nodes["M01"]["status"])
                self.assertEqual("prefix_returned", loader.summary["mediaStatus"])
                self.assertEqual("blocked", nodes["M06"]["status"])
                self.assertEqual(reason, nodes["M06"]["reason"])
                self.assertEqual(1, len(nodes["M06"]["samples"]))
                self.assertEqual(2, len(count))

    def test_mp4_without_range_requirement_does_not_add_requests(self):
        import base64
        broker = Broker()
        seen = []
        def fetch(request):
            seen.append(request)
            return {"status": 200, "body": base64.b64encode(b"\x00\x00\x00\x18ftypisom").decode(), "headers": {}}
        broker.fetch = fetch
        loader = Loader(SOURCE, RUNTIME, broker)
        loader.media({"url": "https://media.example.com/asset.mp4", "header": {}})
        self.assertEqual(1, len(seen))
        self.assertNotIn("M06", [row["id"] for row in loader.nodes])


if __name__ == "__main__":
    unittest.main()
