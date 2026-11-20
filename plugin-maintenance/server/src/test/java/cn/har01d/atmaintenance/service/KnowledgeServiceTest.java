package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reviewedResourcesExtendDataOnlyCatalogWithoutChangingCapabilityNodes() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        var catalog = mapper.readTree(knowledge.catalogBytes());
        assertEquals(20, catalog.path("rules").size());
        assertEquals(44, knowledge.nodeCatalog().path("nodes").size());
        assertEquals(8, catalog.path("rules").valueStream()
                .filter(rule -> rule.path("citation").path("skill").asText().equals("project-reviewed-resources"))
                .count());
        for (var rule : catalog.path("rules")) {
            assertEquals("reference_only", rule.path("authority").asText());
            assertEquals("guidance", rule.path("evidenceClass").asText());
            assertTrue(rule.path("citation").path("sha256").asText().matches("[a-f0-9]{64}"));
            assertFalse(rule.path("citation").path("excerpt").asText().isBlank());
        }
    }

    @Test
    void exactDenialGuidanceWinsButDoesNotDiagnoseUnrelatedNetworkFailures() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        var selected = knowledge.context("M01", "EGRESS_HEADER_ORIGIN_DENIED", "t4", 8);
        assertEquals("session-header-scope", selected.path("rules").get(0).path("id").asText());
        var player = knowledge.context("R01", "PLAYER_HEADERS_INVALID", "t4", 8);
        assertEquals("session-header-scope", player.path("rules").get(0).path("id").asText());
        var unrelated = knowledge.context("M01", "MEDIA_FETCH_FAILED", "t4", 8);
        assertFalse(unrelated.path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("session-header-scope")));
        assertFalse(knowledge.context("S03", "EGRESS_HEADER_ORIGIN_DENIED", "t4", 8)
                .path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("session-header-scope")));
    }

    @Test
    void fieldIdentityFailuresRetrieveHoldoutGuidanceWithinTheirNodeScope() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var failure : java.util.Map.of(
                "C01", "CONTENT_CARD_INVALID", "C03", "CONTENT_IDS_DUPLICATED",
                "C07", "DETAIL_IDENTITY_MISMATCH", "C09", "COVER_URL_EMPTY").entrySet()) {
            var selected = knowledge.context(failure.getKey(), failure.getValue(), "t4", 8);
            assertEquals("learned-rules-holdout", selected.path("rules").get(0).path("id").asText());
            assertTrue(selected.path("rules").get(0).path("citation").path("excerpt").asText().contains("zip"));
            assertTrue(selected.path("ruleBytes").asInt() <= 16_384);
        }
        assertFalse(knowledge.context("M01", "CONTENT_IDS_DUPLICATED", "t4", 8).path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("learned-rules-holdout")));
        var general = knowledge.context("C03", "selector", "t4", 8);
        assertTrue(general.path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("learned-rules-holdout")));
    }

    @Test
    void sourceDerivedValidationBoundariesRemainRetrievableWithinExistingBudgets() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var scope : java.util.List.of(
                new String[]{"C09", "COVER_URL_EMPTY", "learned-rules-holdout", "逐行"},
                new String[]{"S08", "cache", "snapshot-cache-boundary", "hit_fallback"},
                new String[]{"R09", "timeout", "bounded-retry-at-owner", "底层任务"},
                new String[]{"C03", "schema", "schema-candidate-replay", "未知 ID"})) {
            var selected = knowledge.context(scope[0], scope[1], "t4", 12);
            var rule = selected.path("rules").valueStream()
                    .filter(item -> item.path("id").asText().equals(scope[2]))
                    .findFirst().orElseThrow();
            assertTrue(rule.path("citation").path("excerpt").asText().contains(scope[3]));
            assertTrue(selected.path("ruleBytes").asInt() <= 16_384);
            assertEquals("reference_only", rule.path("authority").asText());
        }
    }

    @Test
    void networkEvidenceGuidancePreservesBodySessionAndCleanupBoundaries() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var scope : java.util.List.of(
                new String[]{"M01", "mime", "response-contract-first", "重定向 hop"},
                new String[]{"R06", "EGRESS_HEADER_ORIGIN_DENIED", "session-header-scope", "discard_cookies"},
                new String[]{"R01", "xhr", "browser-evidence-only", "redactNetworkHeaders"},
                new String[]{"R09", "timeout", "bounded-retry-at-owner", "chunk_size"})) {
            var selected = knowledge.context(scope[0], scope[1], "t4", 12);
            var rule = selected.path("rules").valueStream()
                    .filter(item -> item.path("id").asText().equals(scope[2]))
                    .findFirst().orElseThrow();
            assertTrue(rule.path("citation").path("excerpt").asText().contains(scope[3]));
            assertTrue(selected.path("ruleBytes").asInt() <= 16_384);
            assertEquals("reference_only", rule.path("authority").asText());
        }
    }

    @Test
    void opaqueAndTransportGuidanceRemainsReferenceOnlyAndBounded() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var scope : java.util.List.of(
                new String[]{"S04", "decode", "opaque-data-scope", "ERR_MODULE_NOT_FOUND"},
                new String[]{"S02", "font", "opaque-data-scope", "getBestCmap"},
                new String[]{"M01", "mime", "response-contract-first", "解压后"},
                new String[]{"R06", "EGRESS_HEADER_ORIGIN_DENIED", "session-header-scope", "hostname"},
                new String[]{"S08", "cache", "snapshot-cache-boundary", "Cookie"},
                new String[]{"R09", "timeout", "bounded-retry-at-owner", "可 seek"})) {
            var selected = knowledge.context(scope[0], scope[1], "t4", 12);
            var rule = selected.path("rules").valueStream()
                    .filter(item -> item.path("id").asText().equals(scope[2]))
                    .findFirst().orElseThrow();
            assertTrue(rule.path("citation").path("excerpt").asText().contains(scope[3]));
            assertEquals("reference_only", rule.path("authority").asText());
            assertTrue(selected.path("ruleBytes").asInt() <= 16_384);
        }
        assertFalse(knowledge.context("C01", "decode", "t4", 12).path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("opaque-data-scope")));
    }

    @Test
    void extractionTraceAndProcessFindingsRemainDataOnlyAndDoNotExpandScope() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var scope : java.util.List.of(
                new String[]{"C03", "schema", "schema-candidate-replay", "parseWithSchema"},
                new String[]{"C09", "COVER_URL_EMPTY", "learned-rules-holdout", "next"},
                new String[]{"R01", "xhr", "browser-evidence-only", "finish"},
                new String[]{"R06", "EGRESS_HEADER_ORIGIN_DENIED", "session-header-scope", "isLogin"},
                new String[]{"S08", "cache", "snapshot-cache-boundary", "本次 HTML"},
                new String[]{"R09", "timeout", "bounded-retry-at-owner", "推送队列"})) {
            var selected = knowledge.context(scope[0], scope[1], "t4", 12);
            var rule = selected.path("rules").valueStream()
                    .filter(item -> item.path("id").asText().equals(scope[2]))
                    .findFirst().orElseThrow();
            assertTrue(rule.path("citation").path("excerpt").asText().contains(scope[3]));
            assertEquals("reference_only", rule.path("authority").asText());
            assertEquals("guidance", rule.path("evidenceClass").asText());
            assertTrue(selected.path("ruleBytes").asInt() <= 16_384);
        }
        assertFalse(knowledge.context("M05", "schema", "t4", 12).path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("schema-candidate-replay")));
    }

    @Test
    void taskAndMixedModeFindingsRemainScopedReferenceData() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var scope : java.util.List.of(
                new String[]{"M01", "mime", "response-contract-first", "NavResult.ok"},
                new String[]{"R06", "EGRESS_HEADER_ORIGIN_DENIED", "session-header-scope", "trust_env=False"},
                new String[]{"R01", "xhr", "browser-evidence-only", "RunnerV2"},
                new String[]{"R09", "timeout", "bounded-retry-at-owner", "killProcessRecursive"})) {
            var selected = knowledge.context(scope[0], scope[1], "t4", 12);
            var rule = selected.path("rules").valueStream()
                    .filter(item -> item.path("id").asText().equals(scope[2]))
                    .findFirst().orElseThrow();
            assertTrue(rule.path("citation").path("excerpt").asText().contains(scope[3]));
            assertEquals("reference_only", rule.path("authority").asText());
            assertEquals("guidance", rule.path("evidenceClass").asText());
            assertTrue(selected.path("ruleBytes").asInt() <= 16_384);
        }
        assertFalse(knowledge.context("S03", "xhr", "t4", 12).path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("browser-evidence-only")));
    }

    @Test
    void browserToolDeliveryAndObservationFindingsRemainBoundedGuidance() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var scope : java.util.List.of(
                new String[]{"C03", "schema", "schema-candidate-replay", "check_delivery"},
                new String[]{"R01", "xhr", "browser-evidence-only", "static=false"},
                new String[]{"R01", "xhr", "browser-evidence-only", "outputMaxSize"},
                new String[]{"M01", "mime", "response-contract-first", "addError"},
                new String[]{"R09", "timeout", "bounded-retry-at-owner", "DOMContentLoaded"})) {
            var selected = knowledge.context(scope[0], scope[1], "t4", 12);
            var rule = selected.path("rules").valueStream()
                    .filter(item -> item.path("id").asText().equals(scope[2]))
                    .findFirst().orElseThrow();
            assertTrue(rule.path("citation").path("excerpt").asText().contains(scope[3]));
            assertEquals("reference_only", rule.path("authority").asText());
            assertEquals("guidance", rule.path("evidenceClass").asText());
            assertTrue(selected.path("ruleBytes").asInt() <= 16_384);
        }
        assertFalse(knowledge.context("S03", "xhr", "t4", 12).path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("browser-evidence-only")));
    }

    @Test
    void healthAndRepairStatisticsDoNotBecomeBusinessAcceptance() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var scope : java.util.List.of(
                new String[]{"M01", "mime", "response-contract-first", "calculate_metrics"},
                new String[]{"R06", "EGRESS_HEADER_ORIGIN_DENIED", "session-header-scope", "httpsTimeOutValidator"},
                new String[]{"R06", "EGRESS_HEADER_ORIGIN_DENIED", "session-header-scope", "last_time/check_count"},
                new String[]{"C03", "schema", "schema-candidate-replay", "fired_unverified"},
                new String[]{"C03", "schema", "schema-candidate-replay", "resolve_block_outcome"},
                new String[]{"R01", "xhr", "browser-evidence-only", "has_proxy"})) {
            var selected = knowledge.context(scope[0], scope[1], "t4", 12);
            var rule = selected.path("rules").valueStream()
                    .filter(item -> item.path("id").asText().equals(scope[2]))
                    .findFirst().orElseThrow();
            assertTrue(rule.path("citation").path("excerpt").asText().contains(scope[3]));
            assertEquals("reference_only", rule.path("authority").asText());
            assertEquals("guidance", rule.path("evidenceClass").asText());
            assertTrue(selected.path("ruleBytes").asInt() <= 16_384);
        }
        assertFalse(knowledge.context("M05", "schema", "t4", 12).path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("schema-candidate-replay")));
    }

    @Test
    void ruleProcessingAndStaticTransformFindingsRemainScopedGuidance() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var scope : java.util.List.of(
                new String[]{"C07", "extract", "learned-rules-holdout", "exclude_cont_urls"},
                new String[]{"C09", "COVER_URL_EMPTY", "learned-rules-holdout", "first_loop_field"},
                new String[]{"R01", "xhr", "browser-evidence-only", "matchAction"},
                new String[]{"S04", "decode", "opaque-data-scope", "ArrayUnpacker"},
                new String[]{"S04", "decode", "opaque-data-scope", "局部绑定"})) {
            var selected = knowledge.context(scope[0], scope[1], "t4", 12);
            var rule = selected.path("rules").valueStream()
                    .filter(item -> item.path("id").asText().equals(scope[2]))
                    .findFirst().orElseThrow();
            assertTrue(rule.path("citation").path("excerpt").asText().contains(scope[3]));
            assertEquals("reference_only", rule.path("authority").asText());
            assertEquals("guidance", rule.path("evidenceClass").asText());
            assertTrue(selected.path("ruleBytes").asInt() <= 16_384);
        }
        assertFalse(knowledge.context("C01", "decode", "t4", 12).path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("opaque-data-scope")));
    }

    @Test
    void requestDeduplicationAndPartialBodiesRemainScopedReferenceData() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var scope : java.util.List.of(
                new String[]{"C03", "metadata", "response-contract-first", "partial/dataloss"},
                new String[]{"C03", "selector", "learned-rules-holdout", "useExtendedUniqueKey"},
                new String[]{"S08", "cache", "snapshot-cache-boundary", "调度去重身份"})) {
            var selected = knowledge.context(scope[0], scope[1], "t4", 12);
            var rule = selected.path("rules").valueStream()
                    .filter(item -> item.path("id").asText().equals(scope[2]))
                    .findFirst().orElseThrow();
            assertTrue(rule.path("citation").path("excerpt").asText().contains(scope[3]));
            assertEquals("reference_only", rule.path("authority").asText());
            assertEquals("guidance", rule.path("evidenceClass").asText());
            assertTrue(selected.path("ruleBytes").asInt() <= 16_384);
        }
        assertFalse(knowledge.context("M05", "selector", "t4", 12).path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("learned-rules-holdout")));
    }

    @Test
    void requestFormsAndSessionScopesGuideMissingAtContractsWithoutChangingAcceptance() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        var request = knowledge.context("R04", "redirect", "t4", 12);
        assertEquals("response-contract-first", request.path("rules").get(0).path("id").asText());
        assertTrue(request.path("rules").get(0).path("citation").path("excerpt").asText().contains("pg/flag"));
        var mode = knowledge.context("R05", "", "t4", 12);
        var session = mode.path("rules").valueStream()
                .filter(rule -> rule.path("id").asText().equals("session-header-scope"))
                .findFirst().orElseThrow();
        var excerpt = session.path("citation").path("excerpt").asText();
        for (var marker : java.util.List.of(
                "dont_merge_cookies", "context.request", "TokenFilter", "proxy_base", "Base64", "403")) {
            assertTrue(excerpt.contains(marker), marker);
        }
        for (var context : java.util.List.of(request, mode)) {
            assertTrue(context.path("ruleBytes").asInt() <= 16_384);
            for (var rule : context.path("rules")) {
                assertEquals("reference_only", rule.path("authority").asText());
                assertEquals("guidance", rule.path("evidenceClass").asText());
            }
        }
        assertFalse(knowledge.context("R05", "MEDIA_FETCH_FAILED", "t4", 12).path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("session-header-scope")));
    }

    @Test
    void cdpCaptureAndLifecycleFindingsRemainGuidanceRatherThanRuntimeProof() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var scope : java.util.List.of(
                new String[]{"M01", "mime", "response-contract-first", "base64Encoded"},
                new String[]{"M01", "mime", "response-contract-first", "body=None"},
                new String[]{"R01", "xhr", "browser-evidence-only", "tx_id=None"},
                new String[]{"R01", "xhr", "browser-evidence-only", "ExtraInfo"},
                new String[]{"R09", "timeout", "bounded-retry-at-owner", "动作块之后"},
                new String[]{"R09", "timeout", "bounded-retry-at-owner", "stop_process"},
                new String[]{"R09", "timeout", "bounded-retry-at-owner", "AsyncCamoufox"},
                new String[]{"S08", "cache", "snapshot-cache-boundary", "verify_sha256"})) {
            var context = knowledge.context(scope[0], scope[1], "t4", 12);
            var rule = context.path("rules").valueStream()
                    .filter(item -> item.path("id").asText().equals(scope[2]))
                    .findFirst().orElseThrow();
            assertTrue(rule.path("citation").path("excerpt").asText().contains(scope[3]));
            assertEquals("reference_only", rule.path("authority").asText());
            assertEquals("guidance", rule.path("evidenceClass").asText());
            assertTrue(context.path("ruleBytes").asInt() <= 16_384);
        }
        assertFalse(knowledge.context("S03", "xhr", "t4", 12).path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("browser-evidence-only")));
    }

    @Test
    void adaptiveRoutingFindingsDoNotReplaceValidityOrAuthorizeBrowserFallback() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var scope : java.util.List.of(
                new String[]{"C03", "schema", "schema-candidate-replay", "resultComparator"},
                new String[]{"C03", "schema", "schema-candidate-replay", "两份空结果"},
                new String[]{"R01", "render", "browser-evidence-only", "shouldPropagateError"},
                new String[]{"R01", "render", "browser-evidence-only", "inconclusive"},
                new String[]{"S08", "cache", "snapshot-cache-boundary", "urlComponents"},
                new String[]{"R09", "timeout", "bounded-retry-at-owner", "MutationJournal"})) {
            var context = knowledge.context(scope[0], scope[1], "t4", 12);
            var rule = context.path("rules").valueStream()
                    .filter(item -> item.path("id").asText().equals(scope[2]))
                    .findFirst().orElseThrow();
            assertTrue(rule.path("citation").path("excerpt").asText().contains(scope[3]));
            assertEquals("reference_only", rule.path("authority").asText());
            assertEquals("guidance", rule.path("evidenceClass").asText());
            assertTrue(context.path("ruleBytes").asInt() <= 16_384);
        }
        assertFalse(knowledge.context("S03", "render", "t4", 12).path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("browser-evidence-only")));
    }

    @Test
    void callbackContractsAndLearnedRuleIdentityRemainScopedGuidance() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var scope : java.util.List.of(
                new String[]{"C03", "metadata", "response-contract-first", "@scrapes"},
                new String[]{"C03", "schema", "schema-candidate-replay", "_clean_req"},
                new String[]{"C03", "schema", "schema-candidate-replay", "零项 OK"},
                new String[]{"C07", "selector", "learned-rules-holdout", "get_result_exact"},
                new String[]{"C07", "selector", "learned-rules-holdout", "unique_stack_list"},
                new String[]{"C07", "selector", "learned-rules-holdout", "update=False"},
                new String[]{"C07", "selector", "learned-rules-holdout", "html=\"\""},
                new String[]{"S08", "cache", "snapshot-cache-boundary", "stack_id"})) {
            var context = knowledge.context(scope[0], scope[1], "t4", 12);
            var rule = context.path("rules").valueStream()
                    .filter(item -> item.path("id").asText().equals(scope[2]))
                    .findFirst().orElseThrow();
            assertTrue(rule.path("citation").path("excerpt").asText().contains(scope[3]));
            assertEquals("reference_only", rule.path("authority").asText());
            assertEquals("guidance", rule.path("evidenceClass").asText());
            assertTrue(context.path("ruleBytes").asInt() <= 16_384);
        }
        assertFalse(knowledge.context("M05", "selector", "t4", 12).path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("learned-rules-holdout")));
    }

    @Test
    void actionReplayFindingsRemainReferenceOnlyAndWithinExistingBudget() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        for (var scope : java.util.List.of(
                new String[]{"C03", "schema", "schema-candidate-replay", "successfulActionResult"},
                new String[]{"C03", "schema", "schema-candidate-replay", "resolvedArgs"},
                new String[]{"R09", "retry", "bounded-retry-at-owner", "replay_failed"},
                new String[]{"R09", "retry", "bounded-retry-at-owner", "start/take"})) {
            var context = knowledge.context(scope[0], scope[1], "t4", 12);
            var rule = context.path("rules").valueStream()
                    .filter(item -> item.path("id").asText().equals(scope[2]))
                    .findFirst().orElseThrow();
            assertTrue(rule.path("citation").path("excerpt").asText().contains(scope[3]));
            assertEquals("reference_only", rule.path("authority").asText());
            assertEquals("guidance", rule.path("evidenceClass").asText());
            assertTrue(context.path("ruleBytes").asInt() <= 16_384);
        }
    }

    @Test
    void callerMutationsAndExpandedCatalogCannotExceedContextOrChangeSnapshots() throws Exception {
        var knowledge = new KnowledgeService(mapper);
        byte[] frozen = knowledge.catalogBytes();
        var value = knowledge.context(null, "", "t4", 100);
        assertTrue(value.path("rules").size() <= 12);
        assertTrue(value.path("ruleBytes").asInt() <= 16_384);
        assertTrue(value.path("omittedRules").asInt() >= 8);
        ((com.fasterxml.jackson.databind.node.ObjectNode) value.path("rules").get(0)).put("guidance", "CALLER_MUTATION");
        frozen[0] = 0;
        assertFalse(new String(knowledge.catalogBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("CALLER_MUTATION"));
        assertEquals(Hashes.sha256(knowledge.catalogBytes()), knowledge.sha256());
    }
}
