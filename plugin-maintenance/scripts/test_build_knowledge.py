"""Check reviewed resource citations; no network, model or spider execution."""
import hashlib
import unittest
import build_knowledge as builder


class ProjectKnowledgeTest(unittest.TestCase):
    def test_project_rules_use_exact_local_citations_and_known_nodes(self):
        lines = (builder.REPO / builder.RECIPES).read_text(encoding="utf-8").splitlines()
        nodes = set()
        for line in (builder.REPO / "docs/planning/plugin-maintenance/capability-matrix.md").read_text(
                encoding="utf-8").splitlines():
            cells = [cell.strip() for cell in line.split("|")[1:-1]]
            if len(cells) == 5:
                nodes.add(cells[0])
        self.assertEqual(8, len(builder.PROJECT_RULES))
        self.assertEqual(20, len(builder.RULES) + len(builder.PROJECT_RULES))
        for ident, _, required, _, ref, anchor, count, _, checks in builder.PROJECT_RULES:
            self.assertEqual(builder.RECIPES, ref)
            self.assertEqual([i for i, line in enumerate(lines) if line == anchor],
                             [i for i, line in enumerate(lines) if anchor in line], ident)
            self.assertEqual(1, sum(anchor in line for line in lines), ident)
            self.assertTrue(set(required) <= nodes, ident)
            self.assertGreater(count, 1)
            self.assertEqual(2, len(checks))

    def test_recipe_citations_include_the_complete_reviewed_section_only(self):
        lines = (builder.REPO / builder.RECIPES).read_text(encoding="utf-8").splitlines()
        for ident, _, _, _, _, anchor, count, _, _ in builder.PROJECT_RULES:
            start = lines.index(anchor)
            excerpt = lines[start:start + count]
            self.assertEqual(count, len(excerpt), ident)
            self.assertTrue(all(line.strip() for line in excerpt), ident)
            self.assertTrue(all(not line.startswith("### ") for line in excerpt[1:]), ident)
            self.assertEqual("", lines[start + count], ident)
            if ident == "bounded-retry-at-owner":
                for marker in ("Crawler.stop_async", "AsyncCrawlerRunner.stop",
                               "stop→join", "父 broker 行 COMPLETED", "子 job",
                               "unrouteAll", "ignoreErrors", "_didThrow",
                               "LongStandingScope", "正常收尾", "结果晋级"):
                    self.assertIn(marker, "\n".join(excerpt))

    def test_checked_in_snapshot_matches_reviewed_recipe_bytes(self):
        import json
        catalog = json.loads((builder.OUTPUT / "catalog.json").read_bytes())
        expected = hashlib.sha256((builder.REPO / builder.RECIPES).read_bytes()).hexdigest()
        rules = [rule for rule in catalog["rules"] if rule["citation"]["skill"] == "project-reviewed-resources"]
        self.assertEqual({rule[0] for rule in builder.PROJECT_RULES}, {rule["id"] for rule in rules})
        self.assertTrue(all(rule["citation"]["sha256"] == expected for rule in rules))
        self.assertTrue(all(rule["authority"] == "reference_only" for rule in rules))

    def test_discovery_guidance_does_not_grant_runtime_authority(self):
        import json
        catalog = json.loads((builder.OUTPUT / "catalog.json").read_bytes())
        rules = {rule["id"]: rule for rule in catalog["rules"]}
        scope = rules["session-header-scope"]
        contract = rules["response-contract-first"]
        self.assertIn("链接发现不授予出站权限", scope["guidance"])
        for marker in ("dont_filter/allow_offsite", "SameHostname/SameDomain", "EGRESS_URL_DENIED"):
            self.assertIn(marker, scope["citation"]["excerpt"])
        for marker in ("LinkExtractor.matches", "transformRequestFunction", "不猜高清路径"):
            self.assertIn(marker, contract["citation"]["excerpt"])
        self.assertEqual("reference_only", scope["authority"])
        self.assertEqual("reference_only", contract["authority"])

    def test_trace_guidance_keeps_completion_identity_and_redaction_separate(self):
        import json
        catalog = json.loads((builder.OUTPUT / "catalog.json").read_bytes())
        rules = {rule["id"]: rule for rule in catalog["rules"]}
        for ident, markers in (
                ("response-contract-first", ("Tracing.flushHarEntries", "onEntryFinished", "size=-1")),
                ("browser-evidence-only", ("_apiRequestRef", "跨 chunk", "omitCookies/slimMode", "TR-003"))):
            rule = rules[ident]
            for marker in markers:
                self.assertIn(marker, rule["citation"]["excerpt"])
            self.assertEqual("reference_only", rule["authority"])

    def test_context_guidance_does_not_equate_session_names_with_runtime_evidence(self):
        import json
        catalog = json.loads((builder.OUTPUT / "catalog.json").read_bytes())
        rules = {rule["id"]: rule for rule in catalog["rules"]}
        for ident, markers in (
                ("response-contract-first", ("js_only", "status_code=200")),
                ("session-header-scope", ("proxy_session_id", "配置签名", "init→destroy",
                                          "localProxy", "缺测", "BrowserPool.newPage",
                                          "useIncognitoPages=false", "finally", "test.skip",
                                          "第二次实发 Cookie")),
                ("bounded-retry-at-owner", ("create_task(kill_session)", "created_at",
                                            "arun_many", "可立即复用", "PAGE_CLOSED",
                                            "registerPageClosed", "releaseAllBrowsers",
                                            "外部注入池", "独立清理"))):
            rule = rules[ident]
            for marker in markers:
                self.assertIn(marker, rule["citation"]["excerpt"])
            self.assertEqual("reference_only", rule["authority"])

    def test_throttle_guidance_keeps_pacing_and_handled_separate_from_success(self):
        import json
        catalog = json.loads((builder.OUTPUT / "catalog.json").read_bytes())
        rules = {rule["id"]: rule for rule in catalog["rules"]}
        for ident, markers in (
                ("response-contract-first", ("markRequestAsHandled", "底层提交前", "重试耗尽")),
                ("bounded-retry-at-owner", ("AutoThrottle", "jitter", "crawlDelayUntil",
                                            "backoffUntil", "Retry-After", "keepAlive",
                                            "不重置预算"))):
            rule = rules[ident]
            for marker in markers:
                self.assertIn(marker, rule["citation"]["excerpt"])
            self.assertEqual("reference_only", rule["authority"])
        self.assertTrue({"429", "retry_after", "throttle"} <= set(rules["bounded-retry-at-owner"]["tags"]))

    def test_field_guidance_keeps_record_scope_and_processor_stages_separate(self):
        import json
        catalog = json.loads((builder.OUTPUT / "catalog.json").read_bytes())
        rules = {rule["id"]: rule for rule in catalog["rules"]}
        for ident, markers in (
                ("learned-rules-holdout", ("_values", "相对 .//", "MapCompose",
                                           "TakeFirst", "baseFields", "nested_list")),
                ("browser-evidence-only", ("_track_rule", "字段处理前", "value repr"))):
            rule = rules[ident]
            for marker in markers:
                self.assertIn(marker, rule["citation"]["excerpt"])
            self.assertEqual("reference_only", rule["authority"])
        self.assertIn("每张卡片独立记录", rules["learned-rules-holdout"]["guidance"])

    def test_cookie_guidance_keeps_session_state_and_authorization_separate(self):
        import json
        catalog = json.loads((builder.OUTPUT / "catalog.json").read_bytes())
        rule = next(row for row in catalog["rules"] if row["id"] == "session-header-scope")
        for marker in ("saveResponseCookies=false", "克隆 CookieJar", "临时副本",
                       "正文与业务检查前", "Session.setCookie", "过滤 Set-Cookie",
                       "journal 提交边界"):
            self.assertIn(marker, rule["citation"]["excerpt"])
        self.assertIn("实际发送与失效", rule["guidance"])
        self.assertTrue({"cookie", "session"} <= set(rule["tags"]))
        self.assertEqual("reference_only", rule["authority"])

    def test_referrer_guidance_keeps_generation_redirects_and_authority_separate(self):
        import json
        catalog = json.loads((builder.OUTPUT / "catalog.json").read_bytes())
        rule = next(row for row in catalog["rules"] if row["id"] == "session-header-scope")
        for marker in ("Referrer-Policy", "无效 meta", "setdefault", "no-referrer",
                       "handle_referer", "当前重定向响应", "strip_url", "保留 path/query",
                       "headerOrigins", "不自动裁头"):
            self.assertIn(marker, rule["citation"]["excerpt"])
        self.assertTrue({"Referer", "Referrer-Policy"} <= set(rule["tags"]))
        self.assertIn("R05", rule["nodes"])
        self.assertEqual("reference_only", rule["authority"])

    def test_delivery_guidance_keeps_body_reference_callback_and_cleanup_separate(self):
        import json
        catalog = json.loads((builder.OUTPUT / "catalog.json").read_bytes())
        rules = {rule["id"]: rule for rule in catalog["rules"]}
        for ident, markers in (
                ("response-contract-first", ("Buffer.concat", "Network.loadNetworkResource",
                                               "media", "补取来源")),
                ("session-header-scope", ("fetchResponseUid", "RO-100", "proxy_base",
                                          "可重复提取", "客户端收到字节")),
                ("bounded-retry-at-owner", ("APIResponse.dispose", "_disposeImpl",
                                            "BrowserContext.Close", "socket"))):
            rule = rules[ident]
            for marker in markers:
                self.assertIn(marker, rule["citation"]["excerpt"])
            self.assertEqual("reference_only", rule["authority"])
        self.assertIn("R05", rules["session-header-scope"]["nodes"])
        self.assertIn("fetchResponseUid", rules["session-header-scope"]["tags"])

    def test_node_debug_guidance_keeps_attempt_observation_and_stop_separate(self):
        import json
        catalog = json.loads((builder.OUTPUT / "catalog.json").read_bytes())
        rules = {rule["id"]: rule for rule in catalog["rules"]}
        for ident, markers in (
                ("browser-evidence-only", ("nodeId/event/key", "request.execute 之前",
                                            "tableMap", "运行批次身份", "FastJsonSerializer")),
                ("bounded-retry-at-owner", ("notifyAll", "不检查 running", "取消栅栏",
                                             "持有单槽无限等待"))):
            rule = rules[ident]
            for marker in markers:
                self.assertIn(marker, rule["citation"]["excerpt"])
            self.assertEqual("reference_only", rule["authority"])
        self.assertIn("表达式值不是实发证据", rules["browser-evidence-only"]["guidance"])
        self.assertTrue({"debug", "node", "spider-flow"} <= set(rules["browser-evidence-only"]["tags"]))

    def test_selector_repair_guidance_keeps_diagnostic_and_business_evidence_separate(self):
        import json
        catalog = json.loads((builder.OUTPUT / "catalog.json").read_bytes())
        rules = {rule["id"]: rule for rule in catalog["rules"]}
        for ident, markers in (
                ("schema-candidate-replay", ("tooStrictSelectors", "querySelector", "卡片作用域",
                                             "runWithRepair", "删掉查询", "原页/独立页")),
                ("browser-evidence-only", ("simplifyHtml", "flattenWrappers", "uniqueSelector",
                                            "truncateUrl", "压缩而非脱敏"))):
            rule = rules[ident]
            for marker in markers:
                self.assertIn(marker, rule["citation"]["excerpt"])
            self.assertEqual("reference_only", rule["authority"])
        self.assertIn("selector_repair", rules["schema-candidate-replay"]["tags"])

    def test_code_correction_guidance_binds_each_candidate_to_its_own_output(self):
        import json
        catalog = json.loads((builder.OUTPUT / "catalog.json").read_bytes())
        rule = next(row for row in catalog["rules"] if row["id"] == "schema-candidate-replay")
        for marker in ("CodeGeneratorGraph", "旧 execution_result", "空 differences",
                       "候选 SHA", "reference_answer", "are_content_equal",
                       "required/minItems", "__builtins__", "ScriptCreatorGraph"):
            self.assertIn(marker, rule["citation"]["excerpt"])
        self.assertIn("新执行输出", rule["guidance"])
        self.assertTrue({"code_generator", "execution_result"} <= set(rule["tags"]))
        self.assertEqual("reference_only", rule["authority"])


if __name__ == "__main__":
    unittest.main()
