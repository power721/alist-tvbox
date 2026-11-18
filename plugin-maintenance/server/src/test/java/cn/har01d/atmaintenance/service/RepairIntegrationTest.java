package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:maintenance-repair-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only", "maintenance.at.origin=",
        "maintenance.scheduling.enabled=false",
        "maintenance.managed-source.enabled=true",
        "maintenance.managed-source.socket=/run/repair/executor.sock",
        "maintenance.managed-source.instance-id=d0f4909a-5709-4e57-9a54-4ded3c19424a",
        "maintenance.managed-source.image-id=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "maintenance.managed-source.egress-policy-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
})
class RepairIntegrationTest {
    static final String OVERRIDE = "    def homeContent(self, filter):\n        return {'class': [], 'duplicate': True}\n";
    static final String BAD = """
            # 修复候选只能作为数据，不得执行
            raise RuntimeError("REPAIR_SOURCE_MUST_NOT_EXECUTE")
            class Spider:
                def init(self, extend=""):
                    return None
                def homeContent(self, filter):
                    return {'class': []}
            """ + OVERRIDE;
    @Autowired SourceService sources;
    @Autowired ProfileService profiles;
    @Autowired ProbeService probes;
    @Autowired ProbeStore probeStore;
    @Autowired cn.har01d.atmaintenance.repository.ProbeRunRepository probeRuns;
    @Autowired cn.har01d.atmaintenance.repository.RepairRepository repairRows;
    @Autowired cn.har01d.atmaintenance.repository.RepairCandidateRepository candidateRows;
    @Autowired RepairStore repairs;
    @Autowired RepairReport reports;
    @Autowired RepairService service;
    @Autowired RepairExportService exports;
    @Autowired ReleasePlanService releasePlans;
    @Autowired ReleasePlanExportService releaseExports;
    @Autowired ExtractionReplay extraction;
    @Autowired ModelStore models;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean StaticInspector inspector;
    @MockitoSpyBean ManagedSourceRuntime managed;
    @MockitoSpyBean ManagedSourceSetup setup;
    @LocalServerPort int port;

    @Test
    void reverseEvidencePersistsOnTheSameRepairWithoutChangingSourceBudgetOrAcceptance() throws Exception {
        var source = sources.create(new CreateSourceRequest("reverse-evidence", null, "reverse.py", null, BAD));
        var run = awaitProbe(probes.start(source.id()).id());
        var repair = repairs.create(source.id(), new StartRepairRequest(run.id(), source.artifactSha256()));
        var evidence = mapper.createObjectNode().put("repairId", repair.id()).put("runId", run.id())
                .put("artifactSha256", source.artifactSha256()).put("cleanupConfirmed", true)
                .put("mode", "reverse_analysis_v1").put("businessChainVerified", false);
        assertEquals(evidence, repairs.attachReverseAnalysis(repair.id(), repair.faultSha256(), evidence));
        var observed = repairs.get(repair.id());
        assertEquals(evidence, observed.frozen().path("reverseAnalysis"));
        assertFalse(mapper.readTree(repairRows.findById(repair.id()).orElseThrow().getFrozenJson()).has("reverseAnalysis"));
        assertEquals(repair.faultSha256(), observed.faultSha256());
        assertEquals(0, observed.usedRounds());
        assertEquals("AWAITING_PROPOSAL", observed.status());
        assertEquals(BAD, sources.artifact(source.id(), source.artifactSha256()).getBody());
        assertEquals(evidence, repairs.attachReverseAnalysis(repair.id(), repair.faultSha256(), evidence));
        assertThrows(ResponseStatusException.class, () -> repairs.attachReverseAnalysis(repair.id(),
                repair.faultSha256(), evidence.deepCopy().put("runId", "another-run")));
        var targeted = evidence.deepCopy().put("analysisRequestSha256", "1".repeat(64))
                .put("pageUrlSha256", "2".repeat(64));
        var stored = repairs.attachReverseAnalysis(repair.id(), repair.faultSha256(), targeted);
        assertEquals(evidence, stored.path("previousReports").path(0));
        assertEquals(targeted.path("pageUrlSha256"), stored.path("pageUrlSha256"));
        assertEquals(stored, repairs.get(repair.id()).frozen().path("reverseAnalysis"));
        assertEquals(stored, repairs.attachReverseAnalysis(repair.id(), repair.faultSha256(), targeted));
        assertEquals(0, repairs.get(repair.id()).usedRounds());
        assertFalse(mapper.readTree(repairRows.findById(repair.id()).orElseThrow().getFrozenJson()).has("reverseAnalysis"));
        var budget = models.budget();
        var row = repairRows.findById(repair.id()).orElseThrow();
        row.setStatus("STOPPED");
        row.setStopCode("REPEATED_FAILURE");
        row.setUsedRounds(2);
        repairRows.saveAndFlush(row);
        var upgraded = targeted.deepCopy().put("analysisRequestSha256", "3".repeat(64))
                .put("workerSha256", "4".repeat(64));
        var stoppedEvidence = repairs.attachReverseAnalysis(repair.id(), repair.faultSha256(), upgraded);
        assertEquals(2, stoppedEvidence.path("previousReports").size());
        var stopped = repairs.get(repair.id());
        assertEquals("STOPPED", stopped.status());
        assertEquals("REPEATED_FAILURE", stopped.stopCode());
        assertEquals(2, stopped.usedRounds());
        assertEquals(repair.faultSha256(), stopped.faultSha256());
        assertEquals(budget, models.budget());
        assertEquals(BAD, sources.artifact(source.id(), source.artifactSha256()).getBody());
        assertEquals("REPAIR_NOT_RESUMABLE", assertThrows(ResponseStatusException.class,
                () -> repairs.resume(repair.id(), repair.faultSha256())).getReason());
        for (int index = 0; index < 6; index++) {
            var next = evidence.deepCopy().put("analysisRequestSha256", Hashes.sha256("history-" + index))
                    .put("boundedScriptExcerpt", "x".repeat(210000));
            repairs.attachReverseAnalysis(repair.id(), repair.faultSha256(), next);
        }
        var accumulated = repairs.get(repair.id()).frozen().path("reverseAnalysis");
        assertTrue(accumulated.toString().getBytes(StandardCharsets.UTF_8).length > 1048576);
        assertEquals(8, accumulated.path("previousReports").size());
        assertEquals(evidence, accumulated.path("previousReports").path(0));
        assertEquals(targeted, accumulated.path("previousReports").path(1));
        assertEquals(upgraded, accumulated.path("previousReports").path(2));
        var oversized = evidence.deepCopy().put("boundedScriptExcerpt", "x".repeat(262144));
        assertEquals("REVERSE_RECEIPT_INVALID", assertThrows(ResponseStatusException.class,
                () -> repairs.attachReverseAnalysis(repair.id(), repair.faultSha256(), oversized)).getReason());
        var unchanged = repairs.get(repair.id());
        assertEquals(accumulated, unchanged.frozen().path("reverseAnalysis"));
        assertEquals("STOPPED", unchanged.status());
        assertEquals("REPEATED_FAILURE", unchanged.stopCode());
        assertEquals(2, unchanged.usedRounds());
        assertEquals(repair.faultSha256(), unchanged.faultSha256());
        assertEquals(budget, models.budget());
        assertEquals(BAD, sources.artifact(source.id(), source.artifactSha256()).getBody());
    }

    @Test
    void replayEvidenceIsFrozenToRunAndSelectorCandidatesPersistProofWithoutSwitchingSource() throws Exception {
        String sourceText = BAD.replace(OVERRIDE, "") + """
                \n    def categoryContent(self, tid, pg, filter, extend):
                        return row.select('span.old')
                """;
        var source = sources.create(new CreateSourceRequest("规则回放夹具", null, "selector.py", null, sourceText));
        var run = awaitProbe(probes.start(source.id()).id());
        assertThrows(ResponseStatusException.class,
                () -> repairs.create(source.id(), new StartRepairRequest(run.id(), source.artifactSha256())));
        var rule = new ExtractionRule("article.card", Map.of(
                "vod_id", new ExtractionField("", "data-id", List.of("trim")),
                "vod_name", new ExtractionField("span.old", "text", List.of("trim"))), List.of("vod_id", "vod_name"));
        String original = "<article class=card data-id=1><span class=title>one</span><span>noise</span></article>";
        String holdout = "<article class=card data-id=2><span>noise2</span><span class=title>two</span></article>";
        var replay = new ExtractionReplayRequest(source.artifactSha256(), rule, List.of(
                new ExtractionPage("original", original, Hashes.sha256(original), List.of(Map.of("vod_id", "1", "vod_name", "one"))),
                new ExtractionPage("holdout", holdout, Hashes.sha256(holdout), List.of(Map.of("vod_id", "2", "vod_name", "two")))),
                Map.of());
        var report = extraction.replay(replay);
        assertFalse(report.path("passed").asBoolean());
        var frozen = probeStore.attachEvidence(source.id(), run.id(), source.artifactSha256(), "extractionReplay", report);
        assertEquals(run.id(), frozen.path("runId").asText());
        assertEquals(frozen, probeStore.get(run.id()).context().path("auxiliaryEvidence").path("extractionReplay"));
        assertEquals(frozen, probeStore.attachEvidence(source.id(), run.id(), source.artifactSha256(), "extractionReplay", report));
        assertThrows(ResponseStatusException.class, () -> probeStore.attachEvidence(source.id(), run.id(),
                source.artifactSha256(), "extractionReplay", report.deepCopy().put("passed", true)));
        assertThrows(ResponseStatusException.class, () -> probeStore.requireEvidenceRun("other", run.id(), source.artifactSha256()));
        var repair = repairs.create(source.id(), new StartRepairRequest(run.id(), source.artifactSha256()));
        assertEquals("selector_candidate_validator", repair.frozen().path("policy").path("scope").asText());
        var changedExpectations = new ExtractionReplayRequest(source.artifactSha256(), rule, List.of(
                replay.pages().getFirst(), new ExtractionPage("holdout", holdout, Hashes.sha256(holdout),
                List.of(Map.of("vod_id", "2", "vod_name", "different expectation")))), Map.of());
        var mismatch = assertThrows(ResponseStatusException.class, () -> service.proposeSelector(repair.id(),
                new SelectorLearningRequest(changedExpectations, "vod_name", "span.old")));
        assertEquals("SELECTOR_REPLAY_NOT_FROZEN", mismatch.getReason());
        assertEquals(0, repairs.get(repair.id()).usedRounds());
        var candidate = await(service.proposeSelector(repair.id(),
                new SelectorLearningRequest(replay, "vod_name", "span.old")).id());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertTrue(repairs.content(candidate.id()).contains("row.select('span.title')"));
        assertEquals("STATIC_VALIDATED", candidate.status());
        assertTrue(candidate.result().path("sourceSelectorCallVerified").asBoolean());
        assertEquals(sourceText, sources.artifact(source.id(), source.artifactSha256()).getBody());
        var evidence = candidate.selectorEvidence();
        assertTrue(evidence.path("passed").asBoolean());
        assertEquals(candidate.sha256(), evidence.path("candidateSha256").asText());
        assertEquals(run.id(), evidence.path("baselineRunId").asText());
        assertFalse(evidence.path("sourceRuleBindingVerified").asBoolean());
        assertFalse(evidence.path("sourceExecutionObserved").asBoolean());
        assertTrue(evidence.path("sourceSelectorCallVerified").asBoolean());
        assertFalse(candidate.result().path("repairSuccessful").asBoolean());
        assertEquals(evidence, mapper.readTree(unzip(exports.export(candidate.id())).get("candidate.json")).path("selectorEvidence"));
        var altered = ((com.fasterxml.jackson.databind.node.ObjectNode) candidate.result()).deepCopy();
        altered.withObject("inspection").putArray("selectorCalls");
        reports.applySelectorBinding(altered, evidence);
        assertFalse(altered.path("staticValidationPassed").asBoolean());
        assertEquals("SELECTOR_CANDIDATE_CALL_MISMATCH", altered.path("contractViolations").get(0).asText());
    }

    @Test
    void xpathCandidateEscapesPythonLiteralAndRechecksTheExactCallContract() throws Exception {
        String old = "xpath:.//span/text()";
        String sourceText = BAD.replace(OVERRIDE, "") + """
                \n    def categoryContent(self, tid, pg, filter, extend):
                        return row.xpath('.//span/text()')
                """;
        var source = sources.create(new CreateSourceRequest("XPath binding fixture", null, "xpath.py", null, sourceText));
        var run = awaitProbe(probes.start(source.id()).id());
        var rule = new ExtractionRule("xpath://article", Map.of(
                "vod_id", new ExtractionField("xpath:@data-id", "data-id", List.of()),
                "vod_name", new ExtractionField(old, "text", List.of("trim"))), List.of("vod_id", "vod_name"));
        String original = "<article data-id=1><span class=title>one</span><span>noise</span></article>";
        String holdout = "<article data-id=2><span>noise2</span><span class=title>two</span></article>";
        var replay = new ExtractionReplayRequest(source.artifactSha256(), rule, List.of(
                new ExtractionPage("original", original, Hashes.sha256(original), List.of(Map.of("vod_id", "1", "vod_name", "one"))),
                new ExtractionPage("holdout", holdout, Hashes.sha256(holdout), List.of(Map.of("vod_id", "2", "vod_name", "two")))),
                Map.of());
        probeStore.attachEvidence(source.id(), run.id(), source.artifactSha256(), "extractionReplay", extraction.replay(replay));
        var repair = repairs.create(source.id(), new StartRepairRequest(run.id(), source.artifactSha256()));
        var candidate = await(service.proposeSelector(repair.id(), new SelectorLearningRequest(replay, "vod_name", old)).id());
        assertEquals("STATIC_VALIDATED", candidate.status());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertTrue(repairs.content(candidate.id()).contains("row.xpath('.//span[contains(@class,\\'title\\')]/text()')"));
        assertTrue(candidate.result().path("sourceSelectorCallVerified").asBoolean());
        assertEquals("xpath", candidate.selectorEvidence().path("selectorType").asText());
        assertEquals(Hashes.sha256(".//span/text()"), candidate.selectorEvidence().path("oldSelectorSha256").asText());
        assertFalse(candidate.result().path("repairSuccessful").asBoolean());
        assertEquals(sourceText, sources.artifact(source.id(), source.artifactSha256()).getBody());
        for (String key : List.of("call", "selectorType", "ordinal", "callContractSha256", "selectorSha256", "literalSha256")) {
            var changed = ((com.fasterxml.jackson.databind.node.ObjectNode) candidate.result()).deepCopy();
            var call = (com.fasterxml.jackson.databind.node.ObjectNode) changed.path("inspection").path("selectorCalls").get(0);
            if (key.equals("ordinal")) call.put(key, call.path(key).asInt() + 1);
            else call.put(key, "changed");
            reports.applySelectorBinding(changed, candidate.selectorEvidence());
            assertFalse(changed.path("staticValidationPassed").asBoolean(), key);
        }
    }

    @Test
    void xpathReplayCannotBindAnIdenticalCssCallOrSpendACandidateRound() throws Exception {
        String old = "xpath:.//span/text()";
        var source = sources.create(new CreateSourceRequest("XPath call mismatch", null, "xpath.py", null,
                BAD.replace(OVERRIDE, "") + "\n    def parse(self):\n        return row.select('.//span/text()')\n"));
        var run = awaitProbe(probes.start(source.id()).id());
        var rule = new ExtractionRule("article.card", Map.of(
                "vod_id", new ExtractionField("", "data-id", List.of()),
                "vod_name", new ExtractionField(old, "text", List.of("trim"))), List.of("vod_id", "vod_name"));
        String original = "<article class=card data-id=1><span class=title>one</span><span>noise</span></article>";
        String holdout = "<article class=card data-id=2><span>noise2</span><span class=title>two</span></article>";
        var replay = new ExtractionReplayRequest(source.artifactSha256(), rule, List.of(
                new ExtractionPage("original", original, Hashes.sha256(original), List.of(Map.of("vod_id", "1", "vod_name", "one"))),
                new ExtractionPage("holdout", holdout, Hashes.sha256(holdout), List.of(Map.of("vod_id", "2", "vod_name", "two")))),
                Map.of());
        probeStore.attachEvidence(source.id(), run.id(), source.artifactSha256(), "extractionReplay", extraction.replay(replay));
        var repair = repairs.create(source.id(), new StartRepairRequest(run.id(), source.artifactSha256()));
        var error = assertThrows(ResponseStatusException.class, () -> service.proposeSelector(repair.id(),
                new SelectorLearningRequest(replay, "vod_name", old)));
        assertEquals("SELECTOR_SOURCE_CALL_UNVERIFIED", error.getReason());
        assertEquals(0, repairs.get(repair.id()).usedRounds());
    }

    @Test
    void unrelatedSelectorLiteralAndUnfrozenReplayCannotSpendACandidateRound() throws Exception {
        String sourceText = BAD + "\nSELECTOR = 'span.old'\n";
        var source = sources.create(new CreateSourceRequest("selector binding", null, "selector.py", null, sourceText));
        var run = awaitProbe(probes.start(source.id()).id());
        var rule = new ExtractionRule("article.card", Map.of(
                "vod_id", new ExtractionField("", "data-id", List.of("trim")),
                "vod_name", new ExtractionField("span.old", "text", List.of("trim"))), List.of("vod_id", "vod_name"));
        String original = "<article class=card data-id=1><span class=title>one</span><span>noise</span></article>";
        String holdout = "<article class=card data-id=2><span>noise</span><span class=title>two</span></article>";
        var replay = new ExtractionReplayRequest(source.artifactSha256(), rule, List.of(
                new ExtractionPage("original", original, Hashes.sha256(original), List.of(Map.of("vod_id", "1", "vod_name", "one"))),
                new ExtractionPage("holdout", holdout, Hashes.sha256(holdout), List.of(Map.of("vod_id", "2", "vod_name", "two")))), Map.of());
        var repair = repairs.create(source.id(), new StartRepairRequest(run.id(), source.artifactSha256()));
        var error = assertThrows(ResponseStatusException.class, () -> service.proposeSelector(repair.id(),
                new SelectorLearningRequest(replay, "vod_name", "span.old")));
        assertEquals("SELECTOR_REPLAY_NOT_FROZEN", error.getReason());
        probeStore.attachEvidence(source.id(), run.id(), source.artifactSha256(), "extractionReplay", extraction.replay(replay));
        var boundRepair = repairs.create(source.id(), new StartRepairRequest(run.id(), source.artifactSha256()));
        error = assertThrows(ResponseStatusException.class, () -> service.proposeSelector(boundRepair.id(),
                new SelectorLearningRequest(replay, "vod_name", "span.old")));
        assertEquals("SELECTOR_SOURCE_CALL_UNVERIFIED", error.getReason());
        assertEquals(0, repairs.get(boundRepair.id()).usedRounds());
        assertTrue(repairs.candidates(boundRepair.id()).isEmpty());
    }

    @Test
    void browserEvidenceDisabledReturnsSafeCodeAndDoesNotFreezeAnObservation() throws Exception {
        var repair = begin(false);
        String base = "http://127.0.0.1:" + port + "/api/plugin-maintenance";
        String path = "/sources/" + repair.sourceId() + "/browser-evidence";
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
        var input = new BrowserEvidenceRequest(repair.baselineRunId(), repair.baselineArtifactSha256(),
                "frame-1", "a".repeat(64), "article.card", false, List.of(), 32768, false);
        try (var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            var csrf = mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/csrf"))
                    .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            var response = http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", auth)
                    .header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode(), response.body());
            assertEquals("{\"code\":\"BROWSER_EVIDENCE_DISABLED\"}", response.body());
            assertFalse(probeStore.get(repair.baselineRunId()).context().path("auxiliaryEvidence").has("browserEvidence"));
        }
    }

    @Test
    void immutableCandidateRestoresStaticTargetButNeverPublishesOrSwitchesEffectiveSource() throws Exception {
        var repair = begin(true);
        var candidate = await(service.propose(repair.id(), good(repair)).id());
        assertEquals("STATIC_VALIDATED", candidate.status());
        assertEquals("AWAITING_RUNTIME_VALIDATION", repairs.get(repair.id()).status());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals("current", repairs.get(repair.id()).freshness());
        assertEquals(repair.baselineArtifactSha256(), sources.get(repair.sourceId()).artifactSha256());
        assertEquals(1, sources.versions(repair.sourceId()).size());
        assertEquals(BAD.replace(OVERRIDE, ""), repairs.content(candidate.id()));
        assertEquals(44, candidate.result().path("nodes").size());
        assertEquals(1, candidate.result().path("requiredCoverage").path("notTested").asInt());
        assertFalse(candidate.result().path("publishEligible").asBoolean());
        assertEquals("isolated_runtime_and_AT_validation", candidate.result().path("nextRequired").asText());
        assertEquals("duplicate-methods", repair.frozen().path("baseline").path("context").path("rules").get(0).path("id").asText());
        var files = unzip(exports.export(candidate.id()));
        var manifest = mapper.readTree(files.get("manifest.json"));
        assertEquals("STATIC_VALIDATED", manifest.path("candidateStatus").asText());
        assertFalse(manifest.path("runtimeRequested").asBoolean(true));
        assertFalse(manifest.path("runtimeEvidencePresent").asBoolean(true));
        assertTrue(manifest.path("boundary").asText().contains("未执行候选"));
        assertEquals(candidate.sha256(), Hashes.sha256(files.get("candidate.py")));
        var delivery = exports.delivery(candidate.id());
        assertEquals("1.0.1", delivery.version());
        assertTrue(delivery.pluginName().codePoints().anyMatch(
                c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN));
        assertFalse(delivery.verified());
        assertTrue(delivery.entryPath().startsWith("候选/"));
        assertArrayEquals(files.get("candidate.py"), files.get(delivery.entryPath()));
        assertTrue(files.containsKey("交付说明.md"));
        assertEquals(candidate.sha256(), manifest.path("delivery").path("candidateSha256").asText());
        assertEquals(delivery, exports.delivery(candidate.id()));
        assertEquals(candidate.proposalSha256(), Hashes.sha256(files.get("proposal.json")));
        assertEquals(BAD, new String(files.get("baseline.py"), StandardCharsets.UTF_8));
        assertEquals(candidate.id(), mapper.readTree(files.get("rounds.json")).get(0).path("id").asText());
        for (var record : manifest.path("files").properties()) {
            assertEquals(record.getValue().path("sha256").asText(), Hashes.sha256(files.get(record.getKey())));
            assertEquals(record.getValue().path("bytes").asInt(), files.get(record.getKey()).length);
        }
        assertThrows(ResponseStatusException.class, () -> service.propose(repair.id(), good(repair)));
        assertEquals("CANCELLED", service.cancel(repair.id()).status());
        assertEquals("AWAITING_RUNTIME_VALIDATION", repairs.resume(repair.id(), repair.faultSha256()).status());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertThrows(ResponseStatusException.class, () -> service.propose(repair.id(), good(repair)));
    }

    @Test
    void signatureAndImportRegressionsAreRejectedBeforeAnIndependentGoodRound() throws Exception {
        var repair = begin(false);
        var removedParameter = proposal(repair, List.of(new RepairEdit(OVERRIDE, ""),
                new RepairEdit("def init(self, extend=\"\"):", "def init(self):")));
        var first = await(service.propose(repair.id(), removedParameter).id());
        assertEquals("CONTRACT_REGRESSION", first.errorCode());
        assertTrue(first.result().path("contractViolations").toString().contains("METHOD_CONTRACT_CHANGED:init"));
        assertEquals("AWAITING_PROPOSAL", repairs.get(repair.id()).status());
        var addedImport = proposal(repair, List.of(new RepairEdit(OVERRIDE, ""),
                new RepairEdit("class Spider:", "import os\nclass Spider:")));
        var second = await(service.propose(repair.id(), addedImport).id());
        assertEquals("CONTRACT_REGRESSION", second.errorCode());
        assertTrue(second.result().path("contractViolations").toString().contains("IMPORT_CONTRACT_CHANGED"));
        var fixed = await(service.propose(repair.id(), good(repair)).id());
        assertEquals("STATIC_VALIDATED", fixed.status());
        assertEquals(3, repairs.get(repair.id()).usedRounds());
    }

    @Test
    void searchPageSignatureUpgradeIsLimitedToFrozenT4LoaderR04() throws Exception {
        String legacy = "class Spider:\n def searchContent(self, key, quick): return {'list': []}\n";
        var original = inspector.inspect(legacy);
        var frozen = mapper.createObjectNode();
        frozen.putObject("policy").put("scope", "managed_loader_candidate").putArray("targetNodes").add("R04");
        var baseline = frozen.putObject("baseline");
        baseline.putObject("profile").put("runtime", "t4").putArray("requiredNodes").add("R04");
        baseline.putObject("result").putArray("nodes").addObject().put("id", "S03").set("evidence", original);
        String fixed = legacy.replace("quick):", "quick, pg='1'):");
        var inspected = inspector.inspect(fixed);
        assertTrue(reports.build(frozen, Hashes.sha256(fixed), inspected).path("staticValidationPassed").asBoolean());
        assertFalse(reports.build(frozen, Hashes.sha256(fixed), inspected).path("repairSuccessful").asBoolean());
        for (String signature : List.of("key, quick, pg", "key, quick, pg=1", "key, quick, pg='2'",
                "renamed, quick, pg='1'", "key, quick, pg='1', **kwargs")) {
            String invalid = legacy.replace("key, quick", signature);
            assertFalse(reports.build(frozen, Hashes.sha256(invalid), inspector.inspect(invalid))
                    .path("staticValidationPassed").asBoolean(), signature);
        }
        for (String scope : List.of("static_candidate_validator", "managed_home_candidate")) {
            var other = frozen.deepCopy();
            other.withObject("policy").put("scope", scope);
            assertFalse(reports.build(other, Hashes.sha256(fixed), inspected).path("staticValidationPassed").asBoolean());
        }
        var wrongNode = frozen.deepCopy();
        wrongNode.withObject("baseline").withObject("profile").putArray("requiredNodes").add("C03");
        assertFalse(reports.build(wrongNode, Hashes.sha256(fixed), inspected).path("staticValidationPassed").asBoolean());
        var wrongRuntime = frozen.deepCopy();
        wrongRuntime.withObject("baseline").withObject("profile").put("runtime", "atvp");
        assertFalse(reports.build(wrongRuntime, Hashes.sha256(fixed), inspected).path("staticValidationPassed").asBoolean());
    }

    @Test
    void blockedRequiredHttpNodeAllowsSearchPageUpgradeThroughTheRealRepairEntry() throws Exception {
        String legacy = "raise RuntimeError('MUST_NOT_EXECUTE')\nclass Spider:\n"
                + " def searchContent(self, key, quick): return {'list': []}\n";
        var source = sources.create(new CreateSourceRequest("search-page-fixture", null, "search.py", null, legacy));
        var profile = profiles.get(source.id());
        profiles.update(source.id(), new UpdateProfileRequest(profile.sha256(), "t4", "unverified",
                List.of("S01", "S02", "S03", "C03", "R04"), List.of(), null, null));
        var staticProbe = awaitProbe(probes.start(source.id()).id());
        var run = probeStore.create(source.id(), staticProbe.inspectorSha256(),
                staticProbe.knowledgeSha256(), "managed_source");
        assertTrue(probeStore.start(run.getId()));
        var baseline = probeStore.get(run.getId());
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) staticProbe.result().deepCopy();
        var evidence = runtimeEvidence(baseline, source.artifactSha256(), false, 20);
        var loader = evidence.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                .put("inputSourceSha256", baseline.artifactSha256()).put("inputProfileSha256", baseline.profileSha256());
        var nodes = loader.putArray("nodes");
        nodes.addObject().put("id", "C03").put("status", "fail").put("reason", "PAGINATION_METADATA_INVALID");
        nodes.addObject().put("id", "R04").put("status", "blocked").put("reason", "T4_HTTP_ACCEPTANCE_FAILED");
        report.set("managedSourceEvidence", evidence);
        probeStore.finish(run.getId(), "COMPLETED", report, staticProbe.context(), null);
        var repair = repairs.create(source.id(), new StartRepairRequest(run.getId(), source.artifactSha256()));
        assertEquals(mapper.valueToTree(List.of("C03")), repair.frozen().path("policy").path("targetNodes"));
        var proposal = new RepairProposal(1, source.artifactSha256(), "source.py",
                "Preserve the T4 search input contract", "Accept an optional page without removing original arguments",
                List.of("C03"), List.of(new RepairEdit("quick):", "quick, pg='1'):")), null);
        var candidate = await(service.propose(repair.id(), proposal).id());
        assertEquals("STATIC_VALIDATED", candidate.status(), () -> String.valueOf(candidate.result()));
        assertFalse(candidate.result().path("repairSuccessful").asBoolean());
        assertFalse(candidate.result().path("publishEligible").asBoolean());
        assertEquals(source.artifactSha256(), sources.get(source.id()).artifactSha256());
    }

    @Test
    void registeredQuerySeparatorMismatchEnablesOnlyTheFrozenR04RepairTarget() throws Exception {
        String sourceText = "class Spider:\n"
                + " def homeContent(self, filter): return {'class': []}\n"
                + " def categoryContent(self, tid, pg, filter, extend): return {'list': []}\n"
                + " def detailContent(self, ids): return {'list': []}\n"
                + " def searchContent(self, key, quick): return {'list': []}\n"
                + " def playerContent(self, flag, id, vipFlags): return {'parse': 0, 'url': id}\n";
        var source = sources.create(new CreateSourceRequest("registered-query-fixture", null, "source.py", null,
                sourceText));
        var profile = profiles.get(source.id());
        profiles.update(source.id(), new UpdateProfileRequest(profile.sha256(), "t4", "unverified",
                List.of("S01", "S02", "S03", "R04", "R05"), List.of(), null, null));
        var staticProbe = awaitProbe(probes.start(source.id()).id());
        var run = probeStore.create(source.id(), staticProbe.inspectorSha256(),
                staticProbe.knowledgeSha256(), "managed_source");
        assertTrue(probeStore.start(run.getId()));
        var baseline = probeStore.get(run.getId());
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) staticProbe.result().deepCopy();
        var evidence = runtimeEvidence(baseline, source.artifactSha256(), false, 0);
        var loader = evidence.putObject("loaderValidation")
                .put("projection", ManagedLoaderReceipt.PROJECTION)
                .put("inputSourceSha256", source.artifactSha256())
                .put("inputProfileSha256", baseline.profileSha256());
        loader.putArray("nodes").addObject().put("id", "R04").put("status", "fail")
                .put("reason", "SOURCE_REGISTERED_QUERY_SEPARATOR_MISSING");
        report.set("managedSourceEvidence", evidence);
        probeStore.finish(run.getId(), "COMPLETED", report, staticProbe.context(), null);
        var repair = repairs.create(source.id(),
                new StartRepairRequest(run.getId(), source.artifactSha256()));
        assertEquals("managed_loader_candidate", repair.frozen().path("policy").path("scope").asText());
        assertEquals(mapper.valueToTree(List.of("R04")), repair.frozen().path("policy").path("targetNodes"));
    }

    @Test
    void blockedEmptyPlayerIsAnEligibleFrozenRuntimeRepairWithoutRewritingHistory() throws Exception {
        var source = sources.create(new CreateSourceRequest("empty-player-fixture", null, "source.py", null,
                "class Spider:\n def playerContent(self, flag, id, vipFlags): return {'url': ''}\n"));
        var profile = profiles.get(source.id());
        profiles.update(source.id(), new UpdateProfileRequest(profile.sha256(), "t4", "unverified",
                List.of("S01", "S02", "S03", "R01"), List.of(), null, null));
        var staticProbe = awaitProbe(probes.start(source.id()).id());
        var run = probeStore.create(source.id(), staticProbe.inspectorSha256(),
                staticProbe.knowledgeSha256(), "managed_source");
        assertTrue(probeStore.start(run.getId()));
        var baseline = probeStore.get(run.getId());
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) staticProbe.result().deepCopy();
        var evidence = runtimeEvidence(baseline, source.artifactSha256(), false, 20);
        var loader = evidence.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                .put("inputSourceSha256", source.artifactSha256())
                .put("inputProfileSha256", baseline.profileSha256());
        loader.putArray("nodes").addObject().put("id", "R01").put("status", "blocked")
                .put("reason", "PLAYER_URL_EMPTY");
        report.set("managedSourceEvidence", evidence);
        probeStore.finish(run.getId(), "COMPLETED", report, staticProbe.context(), null);
        var repair = repairs.create(source.id(), new StartRepairRequest(run.getId(), source.artifactSha256()));
        assertEquals("managed_loader_candidate", repair.frozen().path("policy").path("scope").asText());
        assertEquals(mapper.valueToTree(List.of("R01")), repair.frozen().path("policy").path("targetNodes"));
        assertEquals("blocked", probeStore.get(run.getId()).result().path("managedSourceEvidence")
                .path("loaderValidation").path("nodes").get(0).path("status").asText());
    }

    @Test
    void explicitMetadataPlayerWithLiveEmptyUrlDoesNotCreateARepairOrChangeTheProfile() throws Exception {
        var source = sources.create(new CreateSourceRequest("metadata-fixture", null, "source.py", null,
                "class Spider:\n def playerContent(self, flag, id, vipFlags):\n"
                    + "  return {'url': '', 'msg': 'metadata only'}\n"));
        var profile = profiles.get(source.id());
        profile = profiles.update(source.id(), new UpdateProfileRequest(profile.sha256(), "t4", "unverified",
                List.of("S01", "S02", "S03", "R01"), List.of(), null, null));
        var staticProbe = awaitProbe(probes.start(source.id()).id());
        var run = probeStore.create(source.id(), staticProbe.inspectorSha256(),
                staticProbe.knowledgeSha256(), "managed_source");
        assertTrue(probeStore.start(run.getId()));
        var baseline = probeStore.get(run.getId());
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) staticProbe.result().deepCopy();
        var evidence = runtimeEvidence(baseline, source.artifactSha256(), false, 20);
        evidence.putArray("httpObservations").addObject().put("responseComplete", true)
                .put("responseStatus", 200).put("responseBytes", 512);
        report.set("managedSourceEvidence", evidence);
        var player = (com.fasterxml.jackson.databind.node.ObjectNode) node(report, "R01");
        player.put("requirement", "required").put("status", "blocked").put("reason", "PLAYER_URL_EMPTY")
                .put("evidenceClass", "host_loader_contract_observed");
        probeStore.finish(run.getId(), "COMPLETED", report, staticProbe.context(), null);
        var error = assertThrows(ResponseStatusException.class, () -> repairs.create(source.id(),
                new StartRepairRequest(run.getId(), source.artifactSha256())));
        assertEquals("LIVE_INSPECTION_METADATA_ONLY", error.getReason());
        assertTrue(repairs.list(source.id()).isEmpty());
        assertEquals(profile.sha256(), profiles.get(source.id()).sha256());
        assertEquals("blocked", node(probeStore.get(run.getId()).result(), "R01").path("status").asText());
    }

    @Test
    void changedInspectorInterpreterCannotValidateAgainstAnOldSignatureSnapshot() throws Exception {
        var repair = begin(false);
        doAnswer(call -> {
            var result = (com.fasterxml.jackson.databind.node.ObjectNode) call.callRealMethod();
            return result.put("pythonVersion", "different-interpreter");
        }).when(inspector).inspect(anyString());
        var rejected = await(service.propose(repair.id(), good(repair)).id());
        assertEquals("CONTRACT_REGRESSION", rejected.errorCode());
        assertTrue(rejected.result().path("contractViolations").toString().contains("INSPECTOR_PYTHON_CHANGED"));
        var nextProbe = awaitProbe(probes.start(repair.sourceId()).id());
        var refreshed = repairs.create(repair.sourceId(),
                new StartRepairRequest(nextProbe.id(), repair.baselineArtifactSha256()));
        assertNotEquals(repair.id(), refreshed.id());
        assertNotEquals(repair.faultSha256(), refreshed.faultSha256());
        assertEquals(0, refreshed.usedRounds());
        assertEquals("STATIC_VALIDATED", await(service.propose(refreshed.id(), good(refreshed)).id()).status());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
    }
    @Test
    void repeatedPatchAndRepeatedFailureStopAndNewIdenticalProbeCannotResetFaultBudget() throws Exception {
        var repair = begin(false);
        var bad = proposal(repair, List.of(new RepairEdit(OVERRIDE, "    def broken(\n")));
        var rejected = await(service.propose(repair.id(), bad).id());
        assertEquals("REJECTED", rejected.status(),
                () -> "Candidate status=" + rejected.status() + " error=" + rejected.errorCode());
        var repeated = service.propose(repair.id(), bad);
        assertEquals("REJECTED", repeated.status());
        assertEquals("REPEATED_PATCH", repeated.errorCode());
        assertEquals("STOPPED", repairs.get(repair.id()).status());
        var nextProbe = awaitProbe(probes.start(repair.sourceId()).id());
        var same = repairs.create(repair.sourceId(), new StartRepairRequest(nextProbe.id(), repair.baselineArtifactSha256()));
        assertEquals(repair.id(), same.id());
        assertEquals(2, same.usedRounds());
        var another = begin(false);
        var first = proposal(another, List.of(new RepairEdit(OVERRIDE, "    def broken(\n")));
        var second = proposal(another, List.of(new RepairEdit(OVERRIDE, "    def different(\n")));
        assertEquals("REJECTED", await(service.propose(another.id(), first).id()).status());
        var secondRejected = await(service.propose(another.id(), second).id());
        assertEquals("REJECTED", secondRejected.status(),
                () -> "Candidate status=" + secondRejected.status() + " error=" + secondRejected.errorCode());
        assertEquals("REPEATED_FAILURE", repairs.get(another.id()).stopCode());
    }

    @Test
    void exactPatchChecksRejectAmbiguityOverlapAndByteOverflowWithoutConsumingRounds() throws Exception {
        var repair = begin(false);
        for (var edits : List.of(
                List.of(new RepairEdit("class Missing:", "")),
                List.of(new RepairEdit("return", "pass")),
                List.of(new RepairEdit(OVERRIDE, ""), new RepairEdit("'duplicate': True", "'duplicate': False")))) {
            assertEquals(422, assertThrows(ResponseStatusException.class,
                    () -> service.propose(repair.id(), proposal(repair, edits))).getStatusCode().value());
            assertEquals(0, repairs.get(repair.id()).usedRounds());
        }
        var oversized = proposal(repair, List.of(new RepairEdit(OVERRIDE, "中".repeat(15000))));
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> service.propose(repair.id(), oversized)).getStatusCode().value());
        var unchanged = service.propose(repair.id(), proposal(repair, List.of(new RepairEdit(OVERRIDE, OVERRIDE))));
        assertEquals("PATCH_NO_CHANGE", unchanged.errorCode());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals("STOPPED", repairs.get(repair.id()).status());
    }

    @Test
    void concurrentReservationsCommitOnlyOneActiveCandidateAndInterruptedRecoveryKeepsItsBudget() throws Exception {
        var repair = begin(false);
        CountDownLatch start = new CountDownLatch(1);
        List<Object> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> attempts = new ArrayList<>();
            for (int i = 0; i < 2; i++) attempts.add(pool.submit(() -> {
                start.await();
                try { return repairs.reserve(repair.id(), good(repair)); }
                catch (ResponseStatusException e) { return e.getReason(); }
            }));
            start.countDown();
            for (var attempt : attempts) results.add(attempt.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, results.stream().filter(v -> v instanceof cn.har01d.atmaintenance.entity.RepairCandidate).count());
        assertTrue(results.contains("REPAIR_NOT_ACCEPTING_PROPOSALS"));
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        var id = repairs.get(repair.id()).activeCandidateId();
        repairs.interruptUnfinished();
        assertEquals("INTERRUPTED", repairs.get(repair.id()).status());
        assertEquals("SERVICE_RESTARTED", repairs.candidate(id).errorCode());
        assertEquals(1, repairs.resume(repair.id(), repair.faultSha256()).usedRounds());
        assertEquals("STATIC_VALIDATED", await(service.propose(repair.id(), good(repair)).id()).status());
        assertEquals(2, repairs.get(repair.id()).usedRounds());
    }

    @Test
    void thirdDistinctRejectedRoundExhaustsBudgetAndIsNotResumable() throws Exception {
        var repair = begin(false);
        for (int i = 1; i <= 3; i++) {
            String replacement = "\n".repeat(i) + "    def broken(\n";
            var next = proposal(repair, List.of(new RepairEdit(OVERRIDE, replacement)));
            assertEquals("REJECTED", await(service.propose(repair.id(), next).id()).status());
        }
        assertEquals("STOPPED", repairs.get(repair.id()).status());
        assertEquals("ROUND_LIMIT", repairs.get(repair.id()).stopCode());
        assertThrows(ResponseStatusException.class, () -> repairs.resume(repair.id(), repair.faultSha256()));
    }

    @Test
    void profileDriftDuringValidationBlocksCandidateAndKeepsFrozenRequirements() throws Exception {
        var repair = begin(true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(); return call.callRealMethod(); })
                .when(inspector).inspect(anyString());
        var candidate = service.propose(repair.id(), good(repair));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var profile = profiles.get(repair.sourceId());
            profiles.update(repair.sourceId(), new UpdateProfileRequest(profile.sha256(), "fongmi", "changed",
                    List.of("S01", "S02", "S03"), List.of(), null, null));
        } finally { release.countDown(); }
        var finished = await(candidate.id());
        assertEquals("REPAIR_INPUTS_CHANGED", finished.errorCode());
        assertEquals("BLOCKED", finished.status());
        assertEquals(1, finished.result().path("requiredCoverage").path("notTested").asInt());
        assertEquals("profile_changed", repairs.get(repair.id()).freshness());
        assertEquals("required", node(finished.result(), "C01").path("requirement").asText());
    }

    @Test
    void acceptedCancellationWaitsForCleanupAndCannotBecomeALateSuccess() throws Exception {
        var repair = begin(false);
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1), cleanup = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            while (cleanup.getCount() != 0) {
                try { cleanup.await(); } catch (InterruptedException e) { interrupted.countDown(); }
            }
            return call.callRealMethod();
        }).when(inspector).inspect(anyString());
        var candidate = service.propose(repair.id(), good(repair));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("CANCELLING", service.cancel(repair.id()).status());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertEquals("CANCELLING", repairs.candidate(candidate.id()).status());
        } finally { cleanup.countDown(); }
        assertEquals("CANCELLED", await(candidate.id()).status());
        assertNull(repairs.candidate(candidate.id()).result());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals("CANCELLED", service.cancel(repair.id()).status());
    }

    @Test
    void unconfirmedCleanupIsFailedEvenAfterCancellation() throws Exception {
        var repair = begin(false);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { throw new IOException("INSPECTOR_CLEANUP_FAILED", e); }
            return call.callRealMethod();
        }).when(inspector).inspect(anyString());
        var candidate = service.propose(repair.id(), good(repair));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.cancel(repair.id());
            assertEquals("FAILED", await(candidate.id()).status());
            assertEquals("INSPECTOR_CLEANUP_FAILED", repairs.candidate(candidate.id()).errorCode());
            assertEquals("BLOCKED", repairs.get(repair.id()).status());
        } finally { release.countDown(); }
    }

    @Test
    void queuedCancellationFreesCapacityButRejectedWorkStillConsumesItsReservedRound() throws Exception {
        List<RepairSummary> sessions = new ArrayList<>();
        for (int i = 0; i < 10; i++) sessions.add(begin(false));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(); return call.callRealMethod(); })
                .when(inspector).inspect(anyString());
        var running = service.propose(sessions.getFirst().id(), good(sessions.getFirst()));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 1; i <= 8; i++) service.propose(sessions.get(i).id(), good(sessions.get(i)));
            var rejected = sessions.get(9);
            assertEquals(429, assertThrows(ResponseStatusException.class,
                    () -> service.propose(rejected.id(), good(rejected))).getStatusCode().value());
            assertEquals(1, repairs.get(rejected.id()).usedRounds());
            assertEquals("BLOCKED", repairs.get(rejected.id()).status());
            assertEquals("CANCELLED", service.cancel(sessions.get(1).id()).status());
            repairs.resume(rejected.id(), rejected.faultSha256());
            service.propose(rejected.id(), good(rejected));
            assertEquals(2, repairs.get(rejected.id()).usedRounds());
        } finally {
            for (var repair : sessions) service.cancel(repair.id());
            release.countDown();
            await(running.id());
        }
    }

    @Test
    void httpEndpointsRequireAuthCsrfAndRejectNonSourceFilesWithoutEchoingPayload() throws Exception {
        var repair = begin(false);
        String base = "http://127.0.0.1:" + port + "/api/plugin-maintenance";
        String path = "/repairs/" + repair.id() + "/proposals";
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
        try (var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(base + "/repairs/" + repair.id())).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", auth)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var csrf = mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/csrf"))
                    .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            var body = mapper.valueToTree(good(repair));
            ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("targetFile", "../PRIVATE_POLICY.json");
            var result = http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", auth)
                    .header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, result.statusCode());
            assertEquals("{\"code\":\"INVALID_REQUEST\"}", result.body());
            assertEquals(0, repairs.get(repair.id()).usedRounds());
            ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("targetFile", "source.py")
                    .put("deploymentAllowed", true).put("maxRounds", 999);
            var accepted = http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", auth)
                    .header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, accepted.statusCode(), accepted.body());
            var candidate = await(mapper.readTree(accepted.body()).path("id").asText());
            assertEquals("STATIC_VALIDATED", candidate.status(),
                    () -> "Candidate " + candidate.id() + " status=" + candidate.status() + " error=" + candidate.errorCode());
            assertNotNull(candidate.result(),
                    () -> "Missing result for candidate " + candidate.id() + " error=" + candidate.errorCode());
            assertFalse(candidate.result().path("publishEligible").asBoolean());
            assertEquals(3, repairs.get(repair.id()).frozen().path("policy").path("maxRounds").asInt());
            assertFalse(repairs.get(repair.id()).frozen().path("policy").path("deploymentAllowed").asBoolean());
        }
    }

    @Test
    void homeCandidateUsesFrozenRuntimeViaAuthenticatedEndpointWithoutSwitchingOrPublishing() throws Exception {
        var baseline = homeBaseline(0, false);
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        assertEquals(List.of("C01", "C02"), mapper.convertValue(
                repair.frozen().path("policy").path("targetNodes"), List.class));
        var candidate = await(service.propose(repair.id(), homeProposal(repair, "# fixed\n")).id());
        assertEquals("STATIC_VALIDATED", candidate.status());
        assertFalse(candidate.result().path("targetRecovered").asBoolean());
        doAnswer(call -> {
            assertEquals(candidate.id(), call.getArgument(0));
            assertEquals(candidate.sha256(), Hashes.sha256((String) call.getArgument(1)));
            assertEquals(baseline.profile(), call.getArgument(2));
            assertEquals(baseline.runtimeSha256(), call.getArgument(3));
            assertEquals(baseline.executionPolicySha256(), call.getArgument(4));
            return runtimeEvidence(baseline, candidate.sha256(), true, 20);
        }).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString());
        String base = "http://127.0.0.1:" + port + "/api/plugin-maintenance";
        String path = "/repair-candidates/" + candidate.id() + "/runtime-validation";
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
        try (var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + path))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", auth)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var csrf = mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/csrf"))
                    .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            var hashes = List.of("invalid", "0".repeat(64), candidate.sha256(), candidate.sha256());
            var statuses = List.of(400, 409, 202, 409);
            for (int i = 0; i < hashes.size(); i++) {
                var response = http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", auth)
                        .header("Content-Type", "application/json")
                        .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                                new RuntimeValidationRequest(hashes.get(i))))).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(statuses.get(i), response.statusCode());
                if (response.statusCode() == 202) assertEquals("RUNTIME_OBSERVED", await(candidate.id()).status());
            }
        }
        verify(managed, times(1)).inspect(anyString(), anyString(), any(), anyString(), anyString());
        var finished = repairs.candidate(candidate.id());
        assertEquals("AWAITING_PROPOSAL", repairs.get(repair.id()).status());
        assertEquals("REQUIRED_RUNTIME_NODES_INCOMPLETE", repairs.get(repair.id()).stopCode());
        assertTrue(finished.result().path("targetObservedRecovered").asBoolean());
        for (String field : List.of("targetRecovered", "businessVerdictTrusted", "publishEligible", "repairSuccessful"))
            assertFalse(finished.result().path(field).asBoolean(), field);
        assertFalse(finished.result().path("runtimeAcceptance").path("successful").asBoolean());
        assertEquals("S06", finished.result().path("runtimeAcceptance").path("firstUnresolvedNode").asText());
        assertEquals(10, finished.result().path("runtimeComparison").size());
        assertEquals(0, finished.result().path("runtimeComparison").get(1).path("before").asInt());
        assertEquals(20, finished.result().path("runtimeComparison").get(1).path("after").asInt());
        assertEquals(baseline.artifactSha256(), sources.get(baseline.sourceId()).artifactSha256());
        assertEquals(1, sources.versions(baseline.sourceId()).size());
        var manifest = candidateExportManifest(finished);
        assertEquals("RUNTIME_OBSERVED", manifest.path("candidateStatus").asText());
        assertFalse(manifest.path("repairSuccessful").asBoolean(true));
        assertTrue(manifest.path("runtimeRequested").asBoolean());
        assertTrue(manifest.path("runtimeEvidencePresent").asBoolean());
        assertTrue(manifest.path("boundary").asText().contains("运行回执"));
        assertFalse(manifest.path("boundary").asText().contains("未执行候选"));
    }

    @Test
    void policyDeniedEmptyHomeIsNotMisdiagnosedAsAParserFailure() throws Exception {
        var baseline = homeBaseline(0, true);
        assertEquals("REPAIR_FAILURE_UNSUPPORTED", assertThrows(ResponseStatusException.class, () -> repairs.create(
                baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()))).getReason());
        verify(managed, never()).inspect(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void partiallyObservedHomeCannotPrepareReleaseAndCanContinueRepair() throws Exception {
        var baseline = homeBaseline(0, false);
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), homeProposal(repair, "# release observation\n")).id());
        var staticPlan = releasePlans.create(repair.sourceId(), new StartReleasePlanRequest(candidate.id(),
                baseline.artifactSha256(), candidate.sha256(), "before-runtime-observation"));
        var staticFrozen = releasePlans.frozen(staticPlan.id());
        doReturn(runtimeEvidence(baseline, candidate.sha256(), true, 20)).when(managed)
                .inspect(anyString(), anyString(), any(), anyString(), anyString());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var observed = await(candidate.id());
        assertEquals("RUNTIME_OBSERVED", observed.status());
        assertTrue(releasePlans.get(staticPlan.id()).staleInputs().contains("repair_state"));
        assertEquals(staticFrozen, releasePlans.frozen(staticPlan.id()));
        releasePlans.cancel(staticPlan.id());

        assertEquals("AWAITING_PROPOSAL", repairs.get(repair.id()).status());
        assertEquals("RELEASE_CANDIDATE_NOT_READY", assertThrows(ResponseStatusException.class, () ->
                releasePlans.create(repair.sourceId(), new StartReleasePlanRequest(candidate.id(),
                        baseline.artifactSha256(), candidate.sha256(), "after-runtime-observation"))).getReason());
        assertEquals(1, releasePlans.list(repair.sourceId()).size());
        assertEquals(baseline.artifactSha256(), sources.get(repair.sourceId()).artifactSha256());
        assertEquals(1, sources.versions(repair.sourceId()).size());
        var next = await(service.propose(repair.id(), homeProposal(repair, "# next bounded repair\n")).id());
        assertEquals("STATIC_VALIDATED", next.status());
        assertEquals(2, repairs.get(repair.id()).usedRounds());
        assertNull(repairs.get(repair.id()).stopCode());
        assertEquals(observed.result(), repairs.candidate(candidate.id()).result());
        verify(managed, times(1)).inspect(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void partialRuntimeObservationsStopAtRoundLimitWithoutClaimingSuccess() throws Exception {
        var baseline = homeBaseline(0, false);
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        doAnswer(call -> runtimeEvidence(baseline, Hashes.sha256((String) call.getArgument(1)), true, 20))
                .when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString());
        for (int i = 1; i <= 3; i++) {
            var candidate = await(service.propose(repair.id(), homeProposal(repair, "# partial " + i + "\n")).id());
            service.validateRuntime(candidate.id(), candidate.sha256());
            var observed = await(candidate.id());
            assertEquals("RUNTIME_OBSERVED", observed.status());
            assertFalse(observed.result().path("repairSuccessful").asBoolean());
            assertEquals(i == 3 ? "STOPPED" : "AWAITING_PROPOSAL", repairs.get(repair.id()).status());
        }
        assertEquals("ROUND_LIMIT", repairs.get(repair.id()).stopCode());
        assertEquals(3, repairs.candidates(repair.id()).size());
        assertTrue(repairs.candidates(repair.id()).stream().allMatch(c -> c.result().has("runtimeEvidence")));
        assertEquals(baseline.artifactSha256(), sources.get(repair.sourceId()).artifactSha256());
    }

    @Test
    void frozenLoaderFailureCanBeRepairedAndPartialContractsKeepTheSessionOpen() throws Exception {
        var baseline = homeBaseline(20, false);
        var entity = probeRuns.findById(baseline.id()).orElseThrow();
        var original = (com.fasterxml.jackson.databind.node.ObjectNode) baseline.result().deepCopy();
        var observed = (com.fasterxml.jackson.databind.node.ObjectNode) original.path("managedSourceEvidence");
        ((com.fasterxml.jackson.databind.node.ObjectNode) observed.path("untrustedRuntimeSummary"))
                .put("status", "detail_returned").put("homeClasses", 46);
        var loader = observed.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                .put("authority", "host_loader_contract_observed").put("scope", ManagedLoaderReceipt.SCOPE)
                .put("requirementsFrozen", true)
                .put("inputSourceSha256", baseline.artifactSha256()).put("inputProfileSha256", baseline.profileSha256());
        loader.putArray("nodes").addObject().put("id", "C01").put("status", "fail").put("reason", "HOME_CATEGORIES_INVALID");
        entity.setResultJson(original.toString());
        probeRuns.saveAndFlush(entity);
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        assertEquals("managed_loader_candidate", repair.frozen().path("policy").path("scope").asText());
        assertEquals(mapper.valueToTree(List.of("C01")), repair.frozen().path("policy").path("targetNodes"));
        var proposal = homeProposal(repair, "# loader contract repair\n");
        proposal = new RepairProposal(proposal.schemaVersion(), proposal.expectedBaselineSha256(), proposal.targetFile(),
                proposal.diagnosis(), proposal.expectedEffect(), List.of("C01"), proposal.edits(), proposal.deliveryName());
        var candidate = await(service.propose(repair.id(), proposal).id());
        assertEquals("STATIC_VALIDATED", candidate.status());
        assertFalse(candidate.result().path("targetRecovered").asBoolean());
        var partial = runtimeEvidence(baseline, candidate.sha256(), true, 20);
        var candidateLoader = partial.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                .put("authority", "host_loader_contract_observed").put("scope", ManagedLoaderReceipt.SCOPE)
                .put("requirementsFrozen", true).put("inputSourceSha256", candidate.sha256())
                .put("inputProfileSha256", baseline.profileSha256());
        var candidateNodes = candidateLoader.putArray("nodes");
        candidateNodes.addObject().put("id", "C01").put("status", "pass").put("reason", "HOME_CATEGORY_CONTRACT");
        candidateNodes.addObject().put("id", "S06").put("status", "pass").put("reason", "LOADER_INIT_RETURNED");
        doReturn(partial).when(managed)
                .inspect(anyString(), anyString(), any(), anyString(), anyString());
        service.validateRuntime(candidate.id(), candidate.sha256());
        assertEquals("RUNTIME_OBSERVED", await(candidate.id()).status());
        assertFalse(repairs.candidate(candidate.id()).result().path("repairSuccessful").asBoolean());
        assertEquals("AWAITING_PROPOSAL", repairs.get(repair.id()).status());
    }

    @Test
    void repeatedNodeFailureStopsUntilToolsChangeThenKeepsRemainingRoundAndHistory() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        for (int round = 1; round <= 2; round++) {
            var candidate = await(service.propose(repair.id(), nodeProposal(repair, round)).id());
            var evidence = nodeEvidence(baseline, candidate.sha256(), false, true, false);
            evidence.put("jobId", "different-job-" + round);
            ((com.fasterxml.jackson.databind.node.ObjectNode) evidence.path("untrustedRuntimeSummary"))
                    .put("categoryItems", 20 * round);
            doReturn(evidence).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
            service.validateRuntime(candidate.id(), candidate.sha256());
            var result = await(candidate.id());
            assertEquals("RUNTIME_OBSERVED", result.status());
            assertTrue(result.result().path("runtimeProgressObserved").asBoolean());
            assertFalse(result.result().path("targetObservedRecovered").asBoolean());
            assertFalse(result.result().path("repairSuccessful").asBoolean());
            assertEquals(mapper.valueToTree(List.of("C05")), result.result().path("runtimeNodeProgress").path("recovered"));
            assertEquals(round == 1 ? "AWAITING_PROPOSAL" : "STOPPED", repairs.get(repair.id()).status());
        }
        assertEquals("REPEATED_FAILURE", repairs.get(repair.id()).stopCode());
        assertEquals(2, repairs.get(repair.id()).usedRounds());
        assertEquals(2, repairs.candidates(repair.id()).size());
        assertEquals(baseline.artifactSha256(), sources.get(repair.sourceId()).artifactSha256());
        assertTrue(repairs.candidates(repair.id()).stream().allMatch(c -> c.result().has("runtimeEvidence")));
        var latest = repairs.candidates(repair.id()).getLast();
        var previous = latest.result().deepCopy();
        var budget = models.budget();
        upgradeRevalidationEnvironment();
        var evidence = nodeEvidence(baseline, latest.sha256(), false, true, false)
                .put("bundleSha256", managed.sha256());
        doReturn(evidence).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.revalidateRuntime(latest.id(), latest.sha256(), repair.faultSha256());
        var revalidated = await(latest.id());
        var current = repairs.get(repair.id());
        assertEquals("AWAITING_PROPOSAL", current.status());
        assertEquals("current", current.freshness());
        assertEquals(2, current.usedRounds());
        assertEquals(2, repairs.candidates(repair.id()).size());
        assertEquals(latest.sha256(), revalidated.sha256());
        assertEquals(repair.faultSha256(), current.faultSha256());
        assertEquals(previous.path("runtimeEvidence"), revalidated.result().path("runtimeAttempts").get(0).path("runtimeEvidence"));
        assertEquals(budget, models.budget());
        var third = await(service.propose(repair.id(), nodeProposal(current, 3)).id());
        doReturn(nodeEvidence(baseline, third.sha256(), false, true, false).put("bundleSha256", managed.sha256()))
                .when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.validateRuntime(third.id(), third.sha256());
        await(third.id());
        assertEquals("STOPPED", repairs.get(repair.id()).status());
        assertEquals("REPEATED_FAILURE", repairs.get(repair.id()).stopCode());
        assertEquals(3, repairs.get(repair.id()).usedRounds());
        assertThrows(ResponseStatusException.class, () -> service.propose(repair.id(), nodeProposal(current, 4)));
    }

    @Test
    void distinctNodeRecoveryCanReachAllFrozenRequirementsOnThirdRound() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        for (int round = 1; round <= 3; round++) {
            var candidate = await(service.propose(repair.id(), nodeProposal(repair, round)).id());
            doReturn(nodeEvidence(baseline, candidate.sha256(), round >= 2, true, round == 3)).when(managed)
                    .inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
            service.validateRuntime(candidate.id(), candidate.sha256());
            var result = await(candidate.id());
            assertEquals("RUNTIME_OBSERVED", result.status());
            assertEquals(round == 3, result.result().path("repairSuccessful").asBoolean());
            assertEquals(round == 3 ? "RUNTIME_OBSERVED" : "AWAITING_PROPOSAL", repairs.get(repair.id()).status());
        }
        assertNull(repairs.get(repair.id()).stopCode());
        assertEquals(3, repairs.get(repair.id()).usedRounds());
        assertEquals(baseline.artifactSha256(), sources.get(repair.sourceId()).artifactSha256());
    }

    private ProbeSummary nodeBaseline() throws Exception {
        var baseline = homeBaseline(20, false, null, null,
                List.of("S01", "S02", "S03", "S06", "C01", "C02", "C05", "R04"));
        var entity = probeRuns.findById(baseline.id()).orElseThrow();
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) baseline.result().deepCopy();
        result.set("managedSourceEvidence", nodeEvidence(baseline, baseline.artifactSha256(), false, false, false));
        entity.setResultJson(result.toString());
        probeRuns.saveAndFlush(entity);
        return probeStore.get(baseline.id());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode nodeEvidence(ProbeSummary baseline, String sha,
                                                                       boolean home, boolean search, boolean http) {
        var evidence = runtimeEvidence(baseline, sha, false, 20);
        var loader = evidence.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                .put("authority", "host_loader_contract_observed").put("scope", ManagedLoaderReceipt.SCOPE)
                .put("requirementsFrozen", true).put("inputSourceSha256", sha)
                .put("inputProfileSha256", baseline.profileSha256());
        var nodes = loader.putArray("nodes");
        for (String id : List.of("S06", "C01", "C02", "C05", "R04")) {
            boolean passed = id.equals("S06") || id.equals("C01") || id.equals("C02") && home
                    || id.equals("C05") && search || id.equals("R04") && http;
            nodes.addObject().put("id", id).put("status", passed ? "pass" : "fail")
                    .put("reason", passed ? "HOST_CONTRACT_OBSERVED" : "HOST_CONTRACT_FAILED");
        }
        return evidence;
    }

    private RepairProposal nodeProposal(RepairSummary repair, int round) {
        var proposal = homeProposal(repair, "# node repair " + round + "\n");
        return new RepairProposal(proposal.schemaVersion(), proposal.expectedBaselineSha256(), proposal.targetFile(),
                "Repair frozen loader nodes", "Preserve all frozen required contracts",
                List.of("C02", "C05", "R04"), proposal.edits(), proposal.deliveryName());
    }

    private void upgradeRevalidationEnvironment() {
        doReturn("e".repeat(64)).when(managed).sha256();
        doReturn("f".repeat(64)).when(managed).policySha256();
        doReturn("d".repeat(64)).when(inspector).sha256();
    }

    private void failCandidateRuntime(RepairSummary repair, RepairCandidateSummary candidate) {
        repairs.reserveRuntime(candidate.id(), candidate.sha256());
        assertTrue(repairs.start(repair.id(), candidate.id()));
        repairs.finishRuntime(repair.id(), candidate.id(), null, "EGRESS_BYTE_BUDGET",
                mapper.createObjectNode().put("authority", "managed_source_broker_observed"));
    }

    @Test
    void verifiedOriginIsPreparedBeforeAutomaticValidationFreezesTheCurrentRuntime() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        repairs.attachReverseAnalysis(repair.id(), repair.faultSha256(), RepairEvidenceMaterialTest.originReceipt(mapper, repair));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        var budget = models.budget();
        doAnswer(call -> {
            upgradeRevalidationEnvironment();
            return true;
        }).when(setup).ensureVerifiedOrigin(any());
        doAnswer(call -> nodeEvidence(baseline, candidate.sha256(), true, true, true)
                .put("bundleSha256", managed.sha256())).when(managed)
                .inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var result = await(candidate.id());
        assertEquals("RUNTIME_OBSERVED", result.status());
        assertEquals(managed.policySha256(), result.result().path("runtimeValidationContext")
                .path("executionPolicySha256").asText());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals(1, repairs.candidates(repair.id()).size());
        assertEquals(budget, models.budget());
        verify(setup).ensureVerifiedOrigin(any());
    }

    @Test
    void revalidationPreparesMissingSourceRouteBeforeFreezingWithoutModelOrBudgetReset() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        failCandidateRuntime(repair, candidate);
        var previous = repairs.candidate(candidate.id()).result().deepCopy();
        var budget = models.budget();
        clearInvocations(setup);
        doAnswer(call -> {
            upgradeRevalidationEnvironment();
            return null;
        }).when(setup).ensure(repair.sourceId(), repair.baselineArtifactSha256());
        doAnswer(call -> nodeEvidence(baseline, candidate.sha256(), true, true, true)
                .put("bundleSha256", managed.sha256())).when(managed)
                .inspect(anyString(), anyString(), any(), anyString(), anyString(), any());

        service.revalidateRuntime(candidate.id(), candidate.sha256(), repair.faultSha256());

        var result = await(candidate.id());
        assertEquals("RUNTIME_OBSERVED", result.status());
        assertTrue(result.result().path("repairSuccessful").asBoolean());
        assertEquals(managed.policySha256(), result.result().path("runtimeValidationContext")
                .path("executionPolicySha256").asText());
        assertEquals(managed.sha256(), result.result().path("runtimeValidationContext").path("runtimeSha256").asText());
        assertEquals(previous.path("managedExecutionFailure"), result.result().path("runtimeAttempts")
                .get(0).path("managedExecutionFailure"));
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals(1, repairs.candidates(repair.id()).size());
        assertEquals(budget, models.budget());
        assertEquals(baseline.result(), probeStore.get(baseline.id()).result());
        verify(setup).ensure(repair.sourceId(), repair.baselineArtifactSha256());
    }

    @Test
    void revalidatedSuccessClosesOnlyItsStoppedWorkflowWithoutModelCallOrSourceReplay() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        failCandidateRuntime(repair, candidate);
        var run = probeRuns.findById(baseline.id()).orElseThrow();
        run.setMaintenanceKey("revalidation-test");
        run.setMaintenanceRepairId(repair.id());
        run.setMaintenanceStatus("STOPPED");
        run.setMaintenanceStopCode("EGRESS_BYTE_BUDGET");
        probeRuns.saveAndFlush(run);
        var session = repairRows.findById(repair.id()).orElseThrow();
        session.setAutomaticStatus("STOPPED");
        session.setAutomaticStopCode("EGRESS_BYTE_BUDGET");
        repairRows.saveAndFlush(session);
        var budget = models.budget();
        assertThrows(ResponseStatusException.class, () -> repairs.reconcileRuntimeSuccess(
                candidate.id(), candidate.sha256(), repair.faultSha256()));
        assertEquals("STOPPED", probeStore.maintenance(baseline.id()).status());
        upgradeRevalidationEnvironment();
        doReturn(nodeEvidence(baseline, candidate.sha256(), true, true, true)
                .put("bundleSha256", managed.sha256())).when(managed)
                .inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.revalidateRuntime(candidate.id(), candidate.sha256(), repair.faultSha256());
        var result = await(candidate.id());
        // The asynchronous worker completes status reconciliation just after storing the receipt.
        for (int i = 0; i < 100 && !"SUCCEEDED".equals(probeStore.maintenance(baseline.id()).status()); i++)
            Thread.sleep(20);
        assertEquals("RUNTIME_OBSERVED", result.status());
        assertEquals("SUCCEEDED", probeStore.maintenance(baseline.id()).status());
        assertEquals("SUCCEEDED", repairRows.findById(repair.id()).orElseThrow().getAutomaticStatus());
        assertNull(probeStore.maintenance(baseline.id()).stopCode());
        assertThrows(ResponseStatusException.class, () -> repairs.reconcileRuntimeSuccess(
                candidate.id(), "0".repeat(64), repair.faultSha256()));
        repairs.reconcileRuntimeSuccess(candidate.id(), candidate.sha256(), repair.faultSha256());
        assertEquals(budget, models.budget());
        verify(managed, times(1)).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals(1, result.result().path("runtimeAttempts").size());
        run = probeRuns.findById(baseline.id()).orElseThrow();
        run.setMaintenanceStatus("CANCELLED");
        run.setMaintenanceStopCode("USER_CANCELLED");
        probeRuns.saveAndFlush(run);
        session = repairRows.findById(repair.id()).orElseThrow();
        session.setAutomaticStatus("CANCELLED");
        repairRows.saveAndFlush(session);
        repairs.reconcileRuntimeSuccess(candidate.id(), candidate.sha256(), repair.faultSha256());
        assertEquals("CANCELLED", probeStore.maintenance(baseline.id()).status());
        assertEquals("CANCELLED", repairRows.findById(repair.id()).orElseThrow().getAutomaticStatus());
        assertEquals(budget, models.budget());
        session = repairRows.findById(repair.id()).orElseThrow();
        session.setAutomaticStatus("STOPPED");
        repairRows.saveAndFlush(session);
        repairs.reconcileRuntimeSuccess(candidate.id(), candidate.sha256(), repair.faultSha256());
        assertEquals("CANCELLED", probeStore.maintenance(baseline.id()).status());
        assertEquals("STOPPED", repairRows.findById(repair.id()).orElseThrow().getAutomaticStatus());
    }

    @Test
    void revalidatedDirectAutomaticSuccessClosesWithoutCreatingAWorkflowOrCallingTheModel() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        failCandidateRuntime(repair, candidate);
        var session = repairRows.findById(repair.id()).orElseThrow();
        session.setAutomaticStatus("STOPPED");
        session.setAutomaticStopCode("REPEATED_FAILURE");
        repairRows.saveAndFlush(session);
        var budget = models.budget();
        assertNull(probeRuns.findById(baseline.id()).orElseThrow().getMaintenanceKey());
        assertThrows(ResponseStatusException.class, () -> probeStore.completeRevalidatedMaintenance(
                baseline.id(), baseline.sourceId(), "0".repeat(64), repair.id()));
        upgradeRevalidationEnvironment();
        doReturn(nodeEvidence(baseline, candidate.sha256(), true, true, true)
                .put("bundleSha256", managed.sha256())).when(managed)
                .inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.revalidateRuntime(candidate.id(), candidate.sha256(), repair.faultSha256());
        var result = await(candidate.id());
        for (int i = 0; i < 100 && !"SUCCEEDED".equals(
                repairRows.findById(repair.id()).orElseThrow().getAutomaticStatus()); i++) Thread.sleep(20);
        assertEquals("RUNTIME_OBSERVED", result.status());
        session = repairRows.findById(repair.id()).orElseThrow();
        assertEquals("SUCCEEDED", session.getAutomaticStatus());
        assertNull(session.getAutomaticStopCode());
        assertNull(probeRuns.findById(baseline.id()).orElseThrow().getMaintenanceKey());
        assertEquals(budget, models.budget());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals(1, result.result().path("runtimeAttempts").size());
        repairs.reconcileRuntimeSuccess(candidate.id(), candidate.sha256(), repair.faultSha256());
        verify(managed, times(1)).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        session = repairRows.findById(repair.id()).orElseThrow();
        session.setAutomaticStatus("CANCELLED");
        session.setAutomaticStopCode("USER_CANCELLED");
        repairRows.saveAndFlush(session);
        repairs.reconcileRuntimeSuccess(candidate.id(), candidate.sha256(), repair.faultSha256());
        assertEquals("CANCELLED", repairRows.findById(repair.id()).orElseThrow().getAutomaticStatus());
    }

    @Test
    void runtimeRevalidationKeepsOriginalBaselineHistoryRoundsAndModelBudget() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        failCandidateRuntime(repair, candidate);
        var previous = repairs.candidate(candidate.id()).result();
        String originalFrozen = repairRows.findById(repair.id()).orElseThrow().getFrozenJson();
        var budget = models.budget();
        upgradeRevalidationEnvironment();
        assertEquals(Set.of("runtime", "execution_policy", "inspector"),
                new HashSet<>(repairs.get(repair.id()).staleInputs()));
        var observed = nodeEvidence(baseline, candidate.sha256(), true, true, true).put("bundleSha256", managed.sha256());
        doReturn(observed).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());

        service.revalidateRuntime(candidate.id(), candidate.sha256(), repair.faultSha256());
        var result = await(candidate.id());
        var current = repairs.get(repair.id());
        assertEquals("RUNTIME_OBSERVED", current.status());
        assertEquals("current", current.freshness());
        assertTrue(current.staleInputs().isEmpty());
        assertTrue(result.result().path("repairSuccessful").asBoolean());
        assertEquals(8, result.result().path("runtimeAcceptance").path("requiredCoverage").path("pass").asInt());
        assertEquals(1, current.usedRounds());
        assertEquals(1, repairs.candidates(repair.id()).size());
        assertEquals(candidate.sha256(), result.sha256());
        assertEquals(candidate.proposalSha256(), result.proposalSha256());
        assertEquals(repair.faultSha256(), current.faultSha256());
        assertEquals(originalFrozen, repairRows.findById(repair.id()).orElseThrow().getFrozenJson());
        assertEquals(repair.frozen().path("baseline"), current.frozen().path("baseline"));
        assertEquals(budget, models.budget());
        assertEquals(baseline.artifactSha256(), sources.get(repair.sourceId()).artifactSha256());
        assertEquals(previous.path("managedExecutionFailure"), result.result().path("runtimeAttempts").get(0)
                .path("managedExecutionFailure"));
        assertEquals("FAILED", result.result().path("runtimeAttempts").get(0).path("candidateStatus").asText());
        String job = result.result().path("runtimeJobId").asText();
        assertNotEquals(candidate.id(), job);
        assertEquals(baseline.artifactSha256(), current.frozen().path("runtimeValidationContext")
                .path("originalInspection").path("sha256").asText());
        String currentRuntime = managed.sha256(), currentPolicy = managed.policySha256();
        verify(managed).inspect(eq(job), eq(repairs.content(candidate.id())), eq(baseline.profile()),
                eq(currentRuntime), eq(currentPolicy), any());
        var plan = releasePlans.create(repair.sourceId(), new StartReleasePlanRequest(candidate.id(),
                baseline.artifactSha256(), candidate.sha256(), "revalidated-release"));
        assertTrue(plan.localApplyEligible());
    }

    @Test
    void revalidatedNetworkPauseCanResumeAndNewCandidatesInheritCurrentEnvironment() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        failCandidateRuntime(repair, candidate);
        upgradeRevalidationEnvironment();
        var unavailable = nodeEvidence(baseline, candidate.sha256(), false, false, false)
                .put("bundleSha256", managed.sha256());
        unavailable.withArray("deniedCodes").add("EGRESS_HTTP_FAILED");
        doReturn(unavailable).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.revalidateRuntime(candidate.id(), candidate.sha256(), repair.faultSha256());
        var paused = await(candidate.id());
        assertTrue(paused.result().path("runtimeValidationPaused").asBoolean());
        assertEquals("AWAITING_RUNTIME_VALIDATION", repairs.resume(repair.id(), repair.faultSha256()).status());
        var partial = nodeEvidence(baseline, candidate.sha256(), false, true, false)
                .put("bundleSha256", managed.sha256());
        doReturn(partial).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var retried = await(candidate.id());
        assertEquals(2, retried.result().path("runtimeAttempts").size());
        assertEquals("AWAITING_PROPOSAL", repairs.get(repair.id()).status());
        var next = await(service.propose(repair.id(), nodeProposal(repairs.get(repair.id()), 2)).id());
        assertEquals("STATIC_VALIDATED", next.status());
        assertEquals(managed.sha256(), next.result().path("runtimeValidationContext").path("runtimeSha256").asText());
        assertEquals(inspector.sha256(), next.result().path("inspectorSha256").asText());
        assertFalse(next.result().has("runtimeEvidence"));
        assertEquals(2, repairs.get(repair.id()).usedRounds());
    }

    @Test
    void revalidationRejectsOldBundleAndBindsFailureDiagnosticsAndRestartCleanupToNewContext() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        upgradeRevalidationEnvironment();
        doReturn(nodeEvidence(baseline, candidate.sha256(), true, true, true)).when(managed)
                .inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.revalidateRuntime(candidate.id(), candidate.sha256(), repair.faultSha256());
        var failed = await(candidate.id());
        assertEquals("RUNTIME_INPUT_MISMATCH", failed.errorCode());
        assertFalse(failed.result().has("runtimeEvidence"));
        var diagnostic = mapper.createObjectNode().put("authority", "managed_source_broker_observed");
        doThrow(new ManagedExecutionFailure("EGRESS_BYTE_BUDGET", diagnostic)).when(managed)
                .inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.revalidateRuntime(candidate.id(), candidate.sha256(), repair.faultSha256());
        failed = await(candidate.id());
        assertEquals(managed.sha256(), failed.result().path("managedExecutionFailure").path("bundleSha256").asText());
        assertEquals(managed.policySha256(), failed.result().path("managedExecutionFailure")
                .path("executionPolicySha256").asText());
        assertTrue(new RepairDiagnostics(mapper).forRepair(repairs.get(repair.id()), repairs.candidates(repair.id()))
                .path("hostFailureDiagnosticMatched").asBoolean());
        var queued = repairs.reserveRevalidation(candidate.id(), candidate.sha256(), repair.faultSha256(),
                inspector.inspect(sources.artifact(repair.sourceId(), baseline.artifactSha256()).getBody()),
                inspector.inspect(repairs.content(candidate.id())));
        assertTrue(repairs.start(repair.id(), queued.getId()));
        var policy = RepairReport.runtimeInputs(repairs.get(repair.id()).frozen()).path("executionPolicy");
        doNothing().when(managed).cancelAcceptance(anyString(), eq(policy));
        service.recover();
        verify(managed).cancelAcceptance(eq(repairs.candidate(candidate.id()).result().path("runtimeJobId").asText()), eq(policy));
        assertEquals("INTERRUPTED", repairs.get(repair.id()).status());
        assertEquals(3, repairs.candidate(candidate.id()).result().path("runtimeAttempts").size());
        assertEquals(2, repairs.get(repair.id()).frozen().path("runtimeValidationContext").path("previousContexts").size());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
    }

    @Test
    void correctedAssessmentCanRevalidateAnIdenticalStoppedCandidateWithoutResettingHistory() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var proposal = nodeProposal(repair, 1);
        var candidate = await(service.propose(repair.id(), proposal).id());
        var evidence = nodeEvidence(baseline, candidate.sha256(), true, true, true);
        doReturn(evidence).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var observed = await(candidate.id());
        assertTrue(observed.result().path("repairSuccessful").asBoolean());
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) observed.result().deepCopy();
        legacy.put("repairSuccessful", false);
        legacy.withObject("runtimeAcceptance").put("successful", false).put("status", "required_incomplete")
                .withArray("blockers").add("RUNTIME_REGRESSION");
        legacy.withArray("observedRegressions").add("page2Items");
        var row = candidateRows.findById(candidate.id()).orElseThrow();
        row.setStatus("REJECTED"); row.setErrorCode("RUNTIME_TARGET_UNRESOLVED"); row.setResultJson(legacy.toString());
        candidateRows.saveAndFlush(row);
        var session = repairRows.findById(repair.id()).orElseThrow();
        session.setStatus("AWAITING_PROPOSAL"); repairRows.saveAndFlush(session);
        var repeated = new RepairProposal(proposal.schemaVersion(), proposal.expectedBaselineSha256(), proposal.targetFile(),
                "Recheck the identical source candidate", proposal.expectedEffect(), proposal.validationTargets(),
                proposal.edits(), proposal.deliveryName());
        var duplicate = await(service.propose(repair.id(), repeated).id());
        assertEquals("REPEATED_CANDIDATE", duplicate.errorCode());
        assertEquals("STOPPED", repairs.get(repair.id()).status());
        var budget = models.budget();
        service.revalidateRuntime(candidate.id(), candidate.sha256(), repair.faultSha256());
        var revalidated = await(candidate.id());
        assertTrue(revalidated.result().path("repairSuccessful").asBoolean());
        assertEquals("RUNTIME_OBSERVED", repairs.get(repair.id()).status());
        assertEquals(2, repairs.get(repair.id()).usedRounds());
        assertEquals(duplicate, repairs.candidate(duplicate.id()));
        assertEquals(candidate.sha256(), revalidated.sha256());
        assertEquals(legacy.path("runtimeEvidence"), revalidated.result().path("runtimeAttempts").get(0).path("runtimeEvidence"));
        assertEquals("REJECTED", revalidated.result().path("runtimeAttempts").get(0).path("candidateStatus").asText());
        assertEquals(budget, models.budget());
        assertEquals(baseline.artifactSha256(), sources.get(repair.sourceId()).artifactSha256());
    }

    @Test
    void correctedNetworkAssessmentRevalidatesStoppedBytesWithoutSpendingAnotherRound() throws Exception {
        var baseline = nodeBaseline();
        var probe = probeRuns.findById(baseline.id()).orElseThrow();
        var baselineResult = (com.fasterxml.jackson.databind.node.ObjectNode) baseline.result().deepCopy();
        baselineResult.withObject("managedSourceEvidence").withObject("untrustedRuntimeSummary").put("homeItems", 22);
        probe.setResultJson(baselineResult.toString());
        probeRuns.saveAndFlush(probe);
        baseline = probeStore.get(baseline.id());
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        var interrupted = nodeEvidence(baseline, candidate.sha256(), true, true, true);
        interrupted.withArray("deniedCodes").add("EGRESS_DNS_FAILED");
        interrupted.putArray("networkFailureCalls").addObject().put("denialIndex", 0).put("method", "home");
        doReturn(interrupted).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) await(candidate.id()).result().deepCopy();
        assertTrue(legacy.path("runtimeValidationPaused").asBoolean());
        legacy.put("runtimeValidationPaused", false);
        legacy.withArray("observedRegressions").add("homeItems");
        var row = candidateRows.findById(candidate.id()).orElseThrow();
        row.setStatus("REJECTED"); row.setErrorCode("RUNTIME_TARGET_UNRESOLVED"); row.setResultJson(legacy.toString());
        candidateRows.saveAndFlush(row);
        var session = repairRows.findById(repair.id()).orElseThrow();
        session.setStatus("STOPPED"); session.setStopCode("ROUND_LIMIT");
        repairRows.saveAndFlush(session);
        var budget = models.budget();
        var environment = RepairReport.runtimeInputs(repair.frozen());
        var restored = nodeEvidence(baseline, candidate.sha256(), true, true, true);
        restored.withObject("untrustedRuntimeSummary").put("homeItems", 22);
        doReturn(restored).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.revalidateRuntime(candidate.id(), candidate.sha256(), repair.faultSha256());
        var revalidated = await(candidate.id());
        assertTrue(revalidated.result().path("repairSuccessful").asBoolean());
        assertEquals("RUNTIME_OBSERVED", repairs.get(repair.id()).status());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals(1, repairs.candidates(repair.id()).size());
        assertEquals(candidate.sha256(), revalidated.sha256());
        assertNotEquals(candidate.id(), revalidated.result().path("runtimeJobId").asText());
        assertEquals(legacy.path("runtimeEvidence"), revalidated.result().path("runtimeAttempts").get(0).path("runtimeEvidence"));
        assertEquals("REJECTED", revalidated.result().path("runtimeAttempts").get(0).path("candidateStatus").asText());
        assertEquals(environment.path("runtimeSha256"), RepairReport.runtimeInputs(repairs.get(repair.id()).frozen()).path("runtimeSha256"));
        assertEquals(budget, models.budget());
        assertEquals(baseline.artifactSha256(), sources.get(repair.sourceId()).artifactSha256());
    }

    @Test
    void revalidationCannotResetStoppedRepairOrAcceptChangedSourceProfileOrCandidateIdentity() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        var original = inspector.inspect(sources.artifact(repair.sourceId(), baseline.artifactSha256()).getBody());
        var inspection = inspector.inspect(repairs.content(candidate.id()));
        assertEquals("REPAIR_CANDIDATE_CHANGED", assertThrows(ResponseStatusException.class, () ->
                repairs.reserveRevalidation(candidate.id(), "0".repeat(64), repair.faultSha256(), original, inspection)).getReason());
        assertEquals("REPAIR_FAULT_CHANGED", assertThrows(ResponseStatusException.class, () ->
                repairs.reserveRevalidation(candidate.id(), candidate.sha256(), "0".repeat(64), original, inspection)).getReason());
        var row = repairRows.findById(repair.id()).orElseThrow();
        row.setStatus("STOPPED"); row.setStopCode("REPEATED_FAILURE");
        repairRows.saveAndFlush(row);
        assertEquals("REPAIR_REVALIDATION_NOT_READY", assertThrows(ResponseStatusException.class, () ->
                repairs.reserveRevalidation(candidate.id(), candidate.sha256(), repair.faultSha256(), original, inspection)).getReason());
        doReturn("f".repeat(64)).when(managed).policySha256();
        assertEquals(List.of("execution_policy"), repairs.get(repair.id()).staleInputs());
        assertEquals("REPAIR_REVALIDATION_NOT_READY", assertThrows(ResponseStatusException.class, () ->
                repairs.reserveRevalidation(candidate.id(), candidate.sha256(), repair.faultSha256(), original, inspection)).getReason());
        row = repairRows.findById(repair.id()).orElseThrow();
        row.setStatus("BLOCKED"); repairRows.saveAndFlush(row);
        var probe = probeRuns.findById(baseline.id()).orElseThrow();
        String profileSha = probe.getProfileSha256();
        probe.setProfileSha256("0".repeat(64)); probeRuns.saveAndFlush(probe);
        assertEquals("REPAIR_INPUTS_CHANGED", assertThrows(ResponseStatusException.class, () ->
                repairs.reserveRevalidation(candidate.id(), candidate.sha256(), repair.faultSha256(), original, inspection)).getReason());
        probe.setProfileSha256(profileSha); probeRuns.saveAndFlush(probe);
        sources.addArtifact(repair.sourceId(), new AddArtifactRequest(baseline.artifactSha256(), BAD + "\n# source drift\n"));
        assertEquals("REPAIR_INPUTS_CHANGED", assertThrows(ResponseStatusException.class, () ->
                repairs.reserveRevalidation(candidate.id(), candidate.sha256(), repair.faultSha256(), original, inspection)).getReason());
        assertNull(repairRows.findById(repair.id()).orElseThrow().getRuntimeContextJson());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        verify(managed, never()).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void revalidationStaticFailureDoesNotQueueRuntimeOrReuseOldAcceptance() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        var original = inspector.inspect(sources.artifact(repair.sourceId(), baseline.artifactSha256()).getBody());
        var inspection = (com.fasterxml.jackson.databind.node.ObjectNode) inspector.inspect(repairs.content(candidate.id()));
        inspection.put("parseOk", false);
        repairs.reserveRevalidation(candidate.id(), candidate.sha256(), repair.faultSha256(), original, inspection);
        var rejected = repairs.candidate(candidate.id());
        assertEquals("REVALIDATION_STATIC_CHECK_FAILED", rejected.errorCode());
        assertFalse(rejected.result().path("runtimeRequested").asBoolean());
        assertFalse(rejected.result().has("runtimeEvidence"));
        assertFalse(rejected.result().path("repairSuccessful").asBoolean());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertNull(repairs.get(repair.id()).activeCandidateId());
    }

    @Test
    void unresolvedRuntimeCandidateCannotPrepareARelease() throws Exception {
        var baseline = homeBaseline(0, false);
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), homeProposal(repair, "# unresolved release\n")).id());
        doReturn(runtimeEvidence(baseline, candidate.sha256(), false, 0)).when(managed)
                .inspect(anyString(), anyString(), any(), anyString(), anyString());
        service.validateRuntime(candidate.id(), candidate.sha256());
        assertEquals("REJECTED", await(candidate.id()).status());
        assertEquals("RELEASE_CANDIDATE_NOT_READY", assertThrows(ResponseStatusException.class, () ->
                releasePlans.create(repair.sourceId(), new StartReleasePlanRequest(candidate.id(),
                        baseline.artifactSha256(), candidate.sha256(), "unresolved-release"))).getReason());
        assertTrue(releasePlans.list(repair.sourceId()).isEmpty());
    }

    @Test
    void unchangedHomeFailureStopsRepeatedRuntimeFailureButKeepsAllEvidence() throws Exception {
        var baseline = homeBaseline(0, false);
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        doAnswer(call -> runtimeEvidence(baseline, Hashes.sha256((String) call.getArgument(1)), false, 0))
                .when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString());
        for (int i = 1; i <= 2; i++) {
            var candidate = await(service.propose(repair.id(), homeProposal(repair, "# attempt " + i + "\n")).id());
            service.validateRuntime(candidate.id(), candidate.sha256());
            assertEquals("REJECTED", await(candidate.id()).status());
            assertEquals(i == 1 ? "AWAITING_PROPOSAL" : "STOPPED", repairs.get(repair.id()).status());
        }
        assertEquals("REPEATED_FAILURE", repairs.get(repair.id()).stopCode());
        assertEquals(2, repairs.get(repair.id()).usedRounds());
        assertTrue(repairs.candidates(repair.id()).stream().allMatch(c -> c.result().has("runtimeEvidence")));
    }

    @Test
    void networkFailuresPauseWithoutRegressionAndRetrySameBytesWithFreshJobsAndUnchangedRounds() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        var unavailable = nodeEvidence(baseline, candidate.sha256(), false, false, false);
        unavailable.withArray("deniedCodes").add("EGRESS_HTTP_FAILED");
        unavailable.putArray("httpObservations");
        doReturn(unavailable).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var first = await(candidate.id());
        assertEquals("BLOCKED", first.status());
        assertEquals("RUNTIME_NETWORK_UNAVAILABLE", repairs.get(repair.id()).stopCode());
        assertTrue(first.result().path("runtimeValidationPaused").asBoolean());
        assertTrue(first.result().path("observedRegressions").isEmpty());
        assertTrue(first.result().path("runtimeNodeProgress").path("regressed").isEmpty());
        assertFalse(first.result().path("repairSuccessful").asBoolean());
        assertEquals(unavailable, first.result().path("runtimeEvidence"));
        assertTrue(first.result().path("runtimeAcceptance").path("nodes").valueStream()
                .noneMatch(node -> node.path("status").asText().equals("fail")));
        assertThrows(ResponseStatusException.class, () -> service.propose(repair.id(), nodeProposal(repair, 2)));
        assertEquals("AWAITING_RUNTIME_VALIDATION", repairs.resume(repair.id(), repair.faultSha256()).status());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var second = await(candidate.id());
        String retryJob = second.result().path("runtimeJobId").asText();
        assertNotEquals(candidate.id(), retryJob);
        verify(managed).inspect(eq(retryJob), eq(repairs.content(candidate.id())), any(), anyString(), anyString(), any());
        assertEquals(first.result().path("runtimeEvidence"),
                second.result().path("runtimeAttempts").get(0).path("runtimeEvidence"));
        assertEquals("BLOCKED", repairs.get(repair.id()).status());
        assertNull(candidateRows.findById(candidate.id()).orElseThrow().getFailureSha256());

        repairs.resume(repair.id(), repair.faultSha256());
        doReturn(nodeEvidence(baseline, candidate.sha256(), true, true, true)).when(managed)
                .inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var recovered = await(candidate.id());
        assertTrue(recovered.result().path("repairSuccessful").asBoolean());
        assertNotEquals(retryJob, recovered.result().path("runtimeJobId").asText());
        assertEquals(2, recovered.result().path("runtimeAttempts").size());
        assertEquals(candidate.sha256(), recovered.sha256());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals(1, repairs.candidates(repair.id()).size());
        assertEquals(baseline.artifactSha256(), sources.get(repair.sourceId()).artifactSha256());
    }

    @Test
    void automaticNetworkRecoveryWaitsThenReusesBytesRoundAndDurableFailureHistory() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        var unavailable = nodeEvidence(baseline, candidate.sha256(), false, false, false);
        unavailable.withArray("deniedCodes").add("EGRESS_HTTP_FAILED");
        doReturn(unavailable).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var first = await(candidate.id());
        var row = repairRows.findById(repair.id()).orElseThrow();
        row.setAutomaticStatus("ACTIVE");
        repairRows.saveAndFlush(row);
        assertNull(service.retryAutomaticRuntime(repair.id()));
        assertEquals(first.result(), repairs.candidate(candidate.id()).result());
        assertEquals("BLOCKED", repairs.get(repair.id()).status());

        var held = candidateRows.findById(candidate.id()).orElseThrow();
        held.setFinishedAt(java.time.Instant.now().minusSeconds(31));
        candidateRows.saveAndFlush(held);
        doReturn(nodeEvidence(baseline, candidate.sha256(), true, true, true)).when(managed)
                .inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.retryAutomaticRuntime(repair.id());
        var recovered = await(candidate.id());
        assertTrue(recovered.result().path("repairSuccessful").asBoolean());
        assertEquals(first.result().path("runtimeEvidence"),
                recovered.result().path("runtimeAttempts").get(0).path("runtimeEvidence"));
        assertEquals(1, recovered.result().path("runtimeAttempts").size());
        String job = recovered.result().path("runtimeJobId").asText();
        assertNotEquals(candidate.id(), job);
        verify(managed).inspect(eq(job), eq(repairs.content(candidate.id())), any(), anyString(), anyString(), any());
        assertEquals(candidate.sha256(), recovered.sha256());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals(1, repairs.candidates(repair.id()).size());
        assertEquals(baseline.artifactSha256(), sources.get(repair.sourceId()).artifactSha256());
    }

    @Test
    void automaticNetworkRecoveryIsOnceOnlyAndCannotReviveCancellationOrUnconfirmedCleanup() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        var unavailable = nodeEvidence(baseline, candidate.sha256(), false, false, false);
        unavailable.withArray("deniedCodes").add("EGRESS_HTTP_FAILED");
        doReturn(unavailable).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var first = await(candidate.id());
        assertEquals("REPAIR_RUNTIME_NOT_READY", assertThrows(ResponseStatusException.class,
                () -> service.retryAutomaticRuntime(repair.id())).getReason());
        var row = repairRows.findById(repair.id()).orElseThrow();
        row.setAutomaticStatus("ACTIVE");
        repairRows.saveAndFlush(row);
        var held = candidateRows.findById(candidate.id()).orElseThrow();
        held.setFinishedAt(java.time.Instant.now().minusSeconds(31));
        var unsafe = ((com.fasterxml.jackson.databind.node.ObjectNode) first.result()).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsafe.path("runtimeEvidence").path("cleanup"))
                .put("confirmed", false);
        held.setResultJson(unsafe.toString());
        candidateRows.saveAndFlush(held);
        assertEquals("RUNTIME_NETWORK_UNAVAILABLE", assertThrows(ResponseStatusException.class,
                () -> service.retryAutomaticRuntime(repair.id())).getReason());
        assertEquals("BLOCKED", repairs.get(repair.id()).status());
        held.setResultJson(first.result().toString());
        candidateRows.saveAndFlush(held);
        service.retryAutomaticRuntime(repair.id());
        var second = await(candidate.id());
        assertEquals(1, second.result().path("runtimeAttempts").size());
        assertEquals("RUNTIME_NETWORK_UNAVAILABLE", assertThrows(ResponseStatusException.class,
                () -> service.retryAutomaticRuntime(repair.id())).getReason());
        assertEquals(second.result(), repairs.candidate(candidate.id()).result());
        row = repairRows.findById(repair.id()).orElseThrow();
        row.setStatus("CANCELLED");
        row.setStopCode("USER_CANCELLED");
        repairRows.saveAndFlush(row);
        assertEquals("REPAIR_RUNTIME_NOT_READY", assertThrows(ResponseStatusException.class,
                () -> service.retryAutomaticRuntime(repair.id())).getReason());
        assertEquals("CANCELLED", repairs.get(repair.id()).status());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
    }

    @Test
    void legacyRepeatedNetworkFailureCanResumeButCleanupAndSourceFailuresCannot() throws Exception {
        var baseline = nodeBaseline();
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), nodeProposal(repair, 1)).id());
        var unavailable = nodeEvidence(baseline, candidate.sha256(), false, false, false);
        unavailable.withArray("deniedCodes").add("EGRESS_HTTP_FAILED");
        doReturn(unavailable).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var held = await(candidate.id());
        var row = repairRows.findById(repair.id()).orElseThrow();
        row.setStatus("STOPPED"); row.setStopCode("REPEATED_FAILURE");
        repairRows.saveAndFlush(row);
        var legacy = candidateRows.findById(candidate.id()).orElseThrow();
        legacy.setStatus("REJECTED"); legacy.setErrorCode("RUNTIME_TARGET_UNRESOLVED");
        candidateRows.saveAndFlush(legacy);
        assertEquals("AWAITING_RUNTIME_VALIDATION", repairs.resume(repair.id(), repair.faultSha256()).status());
        repairs.reserveRuntime(candidate.id(), candidate.sha256());
        assertEquals("REJECTED", repairs.candidate(candidate.id()).result().path("runtimeAttempts")
                .get(0).path("candidateStatus").asText());
        assertTrue(repairs.start(repair.id(), candidate.id()));
        doNothing().when(managed).cancelAcceptance(anyString(), eq(baseline.executionPolicy()));
        service.recover();
        verify(managed).cancelAcceptance(eq(repairs.candidate(candidate.id()).result().path("runtimeJobId").asText()),
                eq(baseline.executionPolicy()));
        var unsafe = ((com.fasterxml.jackson.databind.node.ObjectNode) held.result()).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsafe.path("runtimeEvidence").path("cleanup"))
                .put("confirmed", false);
        assertFalse(reports.networkRetryable(repair.frozen(), unsafe));
        unsafe = ((com.fasterxml.jackson.databind.node.ObjectNode) held.result()).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsafe.path("runtimeEvidence")).putArray("deniedCodes")
                .add("EGRESS_HTTP_FAILED").add("EGRESS_URL_DENIED");
        assertFalse(reports.networkRetryable(repair.frozen(), unsafe));
    }

    @Test
    void homeRecoveryCannotHideAPreviouslyObservedCategoryRegression() throws Exception {
        var baseline = homeBaseline(20, false);
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), homeProposal(repair, "# regression\n")).id());
        doReturn(runtimeEvidence(baseline, candidate.sha256(), true, 0)).when(managed)
                .inspect(anyString(), anyString(), any(), anyString(), anyString());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var rejected = await(candidate.id());
        assertEquals("REJECTED", rejected.status());
        assertTrue(rejected.result().path("targetObservedRecovered").asBoolean());
        assertEquals("categoryItems", rejected.result().path("observedRegressions").get(0).asText());
        assertEquals("AWAITING_PROPOSAL", repairs.get(repair.id()).status());
    }

    @Test
    void homeRecoveryWithLostPlayerAndMediaCannotBecomeObservedOrPreparePublication() throws Exception {
        var baseline = homeBaseline(20, false, "returned", "prefix_returned");
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), homeProposal(repair, "# player regression\n")).id());
        var evidence = runtimeEvidence(baseline, candidate.sha256(), true, 20);
        ((com.fasterxml.jackson.databind.node.ObjectNode) evidence.path("untrustedRuntimeSummary"))
                .put("playerStatus", "policy_denied").put("mediaStatus", "not_requested");
        evidence.withArray("deniedCodes").add("EGRESS_NONPUBLIC_TARGET_DENIED");
        doReturn(evidence).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var rejected = await(candidate.id());
        assertEquals("REJECTED", rejected.status());
        assertTrue(rejected.result().path("targetObservedRecovered").asBoolean());
        assertEquals(mapper.valueToTree(List.of("playerStatus", "mediaStatus")),
                rejected.result().path("observedRegressions"));
        assertEquals(evidence, rejected.result().path("runtimeEvidence"));
        assertEquals("AWAITING_PROPOSAL", repairs.get(repair.id()).status());
        assertEquals("RELEASE_CANDIDATE_NOT_READY", assertThrows(ResponseStatusException.class, () ->
                releasePlans.create(repair.sourceId(), new StartReleasePlanRequest(candidate.id(),
                        baseline.artifactSha256(), candidate.sha256(), "player-regression-release"))).getReason());
        assertEquals(baseline.artifactSha256(), sources.get(repair.sourceId()).artifactSha256());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        var manifest = candidateExportManifest(rejected);
        assertEquals("REJECTED", manifest.path("candidateStatus").asText());
        assertTrue(manifest.path("runtimeEvidencePresent").asBoolean());
        assertFalse(manifest.path("boundary").asText().contains("未执行候选"));
    }

    @Test
    void staleCandidateOutputCannotBecomeAnObservedPartialRecovery() throws Exception {
        for (String mismatch : List.of("source", "runtime")) {
            var baseline = homeBaseline(0, false);
            var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
            var candidate = await(service.propose(repair.id(), homeProposal(repair, "# fresh output " + mismatch + "\n")).id());
            var evidence = runtimeEvidence(baseline, candidate.sha256(), true, 20);
            evidence.put(mismatch.equals("source") ? "inputSourceSha256" : "bundleSha256", "f".repeat(64));
            doReturn(evidence).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString());
            service.validateRuntime(candidate.id(), candidate.sha256());
            var rejected = await(candidate.id());
            assertEquals("FAILED", rejected.status());
            assertEquals("RUNTIME_INPUT_MISMATCH", rejected.errorCode());
            assertEquals("BLOCKED", repairs.get(repair.id()).status());
            assertFalse(rejected.result().has("runtimeEvidence"));
            assertFalse(rejected.result().path("repairSuccessful").asBoolean());
        }
    }

    @Test
    void runtimeReservationIsExclusiveAndDriftBlocksLateObservation() throws Exception {
        var baseline = homeBaseline(0, false);
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), homeProposal(repair, "# exclusive\n")).id());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> attempt = () -> {
                start.await();
                try { repairs.reserveRuntime(candidate.id(), candidate.sha256()); return true; }
                catch (ResponseStatusException e) {
                    assertEquals("REPAIR_RUNTIME_NOT_READY", e.getReason()); return false;
                }
            };
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertTrue(repairs.start(repair.id(), candidate.id()));
        var profile = profiles.get(baseline.sourceId());
        profiles.update(baseline.sourceId(), new UpdateProfileRequest(profile.sha256(), "t4", "changed",
                List.of("S01", "S02", "S03"), List.of(), null, null));
        repairs.finishRuntime(repair.id(), candidate.id(), runtimeEvidence(baseline, candidate.sha256(), true, 20), null);
        assertEquals("REPAIR_INPUTS_CHANGED", repairs.candidate(candidate.id()).errorCode());
        assertEquals("BLOCKED", repairs.candidate(candidate.id()).status());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
    }

    @Test
    void runtimeCancellationWaitsAndCleanupFailureWinsOverCancellation() throws Exception {
        for (boolean cleanupFails : List.of(false, true)) {
            var baseline = homeBaseline(0, false);
            var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
            var candidate = await(service.propose(repair.id(), homeProposal(repair, "# cancellation\n")).id());
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            doAnswer(call -> {
                entered.countDown();
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                if (cleanupFails) throw new IOException("MANAGED_CLEANUP_UNCONFIRMED");
                return runtimeEvidence(baseline, candidate.sha256(), true, 20);
            }).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
            service.validateRuntime(candidate.id(), candidate.sha256());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals("CANCELLING", service.cancel(repair.id()).status());
                assertEquals("CANCELLING", repairs.candidate(candidate.id()).status());
            } finally { release.countDown(); }
            assertEquals(cleanupFails ? "FAILED" : "CANCELLED", await(candidate.id()).status());
            assertEquals(cleanupFails ? "MANAGED_CLEANUP_UNCONFIRMED" : "USER_CANCELLED",
                    repairs.candidate(candidate.id()).errorCode());
            assertFalse(repairs.candidate(candidate.id()).result().has("runtimeEvidence"));
            var manifest = candidateExportManifest(repairs.candidate(candidate.id()));
            assertEquals(cleanupFails ? "FAILED" : "CANCELLED", manifest.path("candidateStatus").asText());
            assertTrue(manifest.path("runtimeRequested").asBoolean());
            assertFalse(manifest.path("runtimeEvidencePresent").asBoolean(true));
            assertTrue(manifest.path("boundary").asText().contains("尚无完整运行回执"));
            assertFalse(manifest.path("boundary").asText().contains("未执行候选"));
        }
    }

    @Test
    void runtimeFailuresKeepBoundHostDiagnosticsWithoutPromotingAcceptanceOrUsingAnotherRound() throws Exception {
        var budget = models.budget();
        for (String code : List.of("EGRESS_BYTE_BUDGET", "EGRESS_NONPUBLIC_TARGET_DENIED")) {
            var baseline = homeBaseline(0, false);
            var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
            var candidate = await(service.propose(repair.id(), homeProposal(repair, "# failure evidence\n")).id());
            var diagnostic = mapper.createObjectNode().put("schemaVersion", 1)
                    .put("authority", "managed_source_broker_observed").put("failureStage", "execution")
                    .put("primaryCode", "EGRESS_PROTOCOL_INVALID").putNull("cleanupCode")
                    .put("createIssued", true).put("createConfirmed", true).put("createAckBytes", 65)
                    .put("rawUrl", "https://private.invalid/?token=SECRET");
            diagnostic.putArray("calls");
            var exchange = diagnostic.putObject("exchangeContext").put("phase", "frame")
                    .put("lastHttpId", 6).put("loaderCallCount", 4).put("peerExitCode", 1)
                    .put("policyDenied", true).put("lastDeniedCode", code).put("completedHttpCount", 1);
            exchange.putArray("completedHttpObservations").addObject().put("method", "GET")
                    .put("requestUrlSha256", "d".repeat(64)).put("responseStatus", 200)
                    .put("responseBytes", 1405883).put("responseSha256", "e".repeat(64))
                    .put("responseComplete", true).put("responseContentType", "text/html");
            var projected = managed.projectDiagnostic(diagnostic);
            assertNotNull(projected);
            doThrow(new ManagedExecutionFailure(code, projected)).when(managed).inspect(
                    eq(candidate.id()), anyString(), any(), anyString(), anyString(), any());
            service.validateRuntime(candidate.id(), candidate.sha256());
            var failed = await(candidate.id());
            assertEquals("FAILED", failed.status());
            assertEquals(code, failed.errorCode());
            var binding = failed.result().path("managedExecutionFailure");
            assertEquals(candidate.id(), binding.path("candidateId").asText());
            assertEquals(candidate.id(), binding.path("jobId").asText());
            assertEquals(candidate.sha256(), binding.path("inputSourceSha256").asText());
            assertEquals(baseline.profileSha256(), binding.path("inputProfileSha256").asText());
            assertEquals(baseline.runtimeSha256(), binding.path("bundleSha256").asText());
            assertEquals(baseline.executionPolicySha256(), binding.path("executionPolicySha256").asText());
            assertEquals(projected, binding.path("diagnostic"));
            assertFalse(failed.result().has("runtimeEvidence"));
            assertFalse(failed.result().path("repairSuccessful").asBoolean());
            assertFalse(failed.result().toString().contains("SECRET"));
            var current = repairs.get(repair.id());
            assertEquals("BLOCKED", current.status());
            assertEquals(1, current.usedRounds());
            assertEquals(repair.frozen(), current.frozen());
            var triage = new RepairDiagnostics(mapper).forRepair(current, repairs.candidates(repair.id()));
            assertEquals(candidate.id(), triage.path("candidateId").asText());
            assertTrue(triage.path("hostFailureDiagnosticMatched").asBoolean());
            assertFalse(triage.path("loaderReceiptMatched").asBoolean());
            assertEquals(1, triage.path("http").path("completedResponses").asInt());
            assertEquals(1405883, triage.path("http").path("completedResponseBytes").asInt());
            assertTrue(triage.path("routes").toString().contains(code.equals("EGRESS_BYTE_BUDGET")
                    ? "request_budget" : "request_policy"));
            candidateExportManifest(failed);
        }
        assertEquals(budget, models.budget());
    }

    @Test
    void ordinaryRuntimeIoFailureDoesNotInventHostDiagnostics() throws Exception {
        var baseline = homeBaseline(0, false);
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
        var candidate = await(service.propose(repair.id(), homeProposal(repair, "# no diagnostic\n")).id());
        doThrow(new IOException("EGRESS_HTTP_FAILED")).when(managed).inspect(
                eq(candidate.id()), anyString(), any(), anyString(), anyString(), any());
        service.validateRuntime(candidate.id(), candidate.sha256());
        var failed = await(candidate.id());
        assertEquals("FAILED", failed.status());
        assertFalse(failed.result().has("managedExecutionFailure"));
        assertFalse(failed.result().has("runtimeEvidence"));
        assertEquals(1, repairs.get(repair.id()).usedRounds());
    }

    @Test
    void restartCleansRuntimeCandidatesBeforeInterruptionAndPreservesCleanupBlock() throws Exception {
        for (boolean cleanupFails : List.of(false, true)) {
            var baseline = homeBaseline(0, false);
            var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(baseline.id(), baseline.artifactSha256()));
            var candidate = await(service.propose(repair.id(), homeProposal(repair, "# recovery\n")).id());
            repairs.reserveRuntime(candidate.id(), candidate.sha256());
            assertTrue(repairs.start(repair.id(), candidate.id()));
            if (cleanupFails) doThrow(new IOException("MANAGED_CLEANUP_UNCONFIRMED")).when(managed)
                    .cancelAcceptance(eq(candidate.id()), eq(baseline.executionPolicy()));
            else doNothing().when(managed).cancelAcceptance(eq(candidate.id()), eq(baseline.executionPolicy()));
            service.recover();
            verify(managed).cancelAcceptance(candidate.id(), baseline.executionPolicy());
            verify(managed, never()).cleanup(anyString(), any());
            assertEquals(cleanupFails ? "BLOCKED" : "INTERRUPTED", repairs.get(repair.id()).status());
            assertEquals(cleanupFails ? "MANAGED_CLEANUP_UNCONFIRMED" : "SERVICE_RESTARTED",
                    repairs.candidate(candidate.id()).errorCode());
            assertEquals(1, repairs.get(repair.id()).usedRounds());
        }
    }

    private JsonNode candidateExportManifest(RepairCandidateSummary candidate) throws Exception {
        var files = unzip(exports.export(candidate.id()));
        var manifest = mapper.readTree(files.get("manifest.json"));
        assertEquals(candidate.result(), mapper.readTree(files.get("candidate.json")).path("result"));
        assertEquals(candidate.sha256(), Hashes.sha256(files.get("candidate.py")));
        assertFalse(manifest.path("publishEligible").asBoolean(true));
        for (var record : manifest.path("files").properties()) {
            assertEquals(record.getValue().path("sha256").asText(), Hashes.sha256(files.get(record.getKey())));
            assertEquals(record.getValue().path("bytes").asInt(), files.get(record.getKey()).length);
        }
        return manifest;
    }

    private ProbeSummary homeBaseline(int categoryItems, boolean denied) throws Exception {
        return homeBaseline(categoryItems, denied, null, null);
    }

    private ProbeSummary homeBaseline(int categoryItems, boolean denied, String playerStatus, String mediaStatus)
            throws Exception {
        return homeBaseline(categoryItems, denied, playerStatus, mediaStatus, List.of("S01", "S02", "S03", "C01", "C02"));
    }

    private ProbeSummary homeBaseline(int categoryItems, boolean denied, String playerStatus, String mediaStatus,
                                      List<String> required) throws Exception {
        var source = sources.create(new CreateSourceRequest("首页修复夹具", null, "home.py", null, BAD.replace(OVERRIDE, "")));
        var profile = profiles.get(source.id());
        profiles.update(source.id(), new UpdateProfileRequest(profile.sha256(), "t4", "unverified",
                required, List.of(), null, null));
        var staticProbe = awaitProbe(probes.start(source.id()).id());
        assertEquals("COMPLETED", staticProbe.status());
        var run = probeStore.create(source.id(), staticProbe.inspectorSha256(), staticProbe.knowledgeSha256(), "managed_source");
        assertTrue(probeStore.start(run.getId()));
        var baseline = probeStore.get(run.getId());
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) staticProbe.result().deepCopy();
        var evidence = runtimeEvidence(baseline, source.artifactSha256(), false, categoryItems);
        var observations = (com.fasterxml.jackson.databind.node.ObjectNode) evidence.path("untrustedRuntimeSummary");
        if (playerStatus != null) observations.put("playerStatus", playerStatus);
        if (mediaStatus != null) observations.put("mediaStatus", mediaStatus);
        if (denied) evidence.withArray("deniedCodes").add("EGRESS_URL_DENIED");
        report.set("managedSourceEvidence", evidence);
        probeStore.finish(run.getId(), "COMPLETED", report, staticProbe.context(), null);
        return probeStore.get(run.getId());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode runtimeEvidence(ProbeSummary baseline, String sha,
                                                                          boolean recovered, int categoryItems) {
        var evidence = mapper.createObjectNode().put("authority", "managed_source_host_observed")
                .put("executionKind", "t4_business_sample").put("inputSourceSha256", sha)
                .put("bundleSha256", baseline.runtimeSha256());
        evidence.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        evidence.putArray("deniedCodes");
        var counts = evidence.putObject("untrustedRuntimeSummary").put("status", recovered ? "detail_returned" : "home_empty")
                .put("evidenceClass", "untrusted_source_output");
        for (String field : List.of("homeClasses", "homeItems", "homeCovers", "categoryItems", "categoryCovers",
                "page2Items", "detailItems", "detailCovers", "playGroups", "playEpisodes"))
            counts.put(field, field.equals("categoryItems") ? categoryItems : recovered ? 20 : 0);
        return evidence;
    }

    private RepairProposal homeProposal(RepairSummary repair, String comment) {
        return new RepairProposal(1, repair.baselineArtifactSha256(), "source.py", "首页解析为空",
                "只修首页解析并保留合同", List.of("C01", "C02"), List.of(new RepairEdit(
                "return {'class': []}", "return {'class': [{'type_id': 'one', 'type_name': 'One'}]}\n" + comment)), null);
    }

    private RepairSummary begin(boolean requireRuntime) throws Exception {
        var source = sources.create(new CreateSourceRequest("修复夹具", null, "repair.py", null, BAD));
        if (requireRuntime) {
            var profile = profiles.get(source.id());
            profiles.update(source.id(), new UpdateProfileRequest(profile.sha256(), "t4", "unverified",
                    List.of("S01", "S02", "S03", "C01"), List.of(), null, null));
        }
        var probe = awaitProbe(probes.start(source.id()).id());
        assertEquals("COMPLETED", probe.status(),
                () -> "Probe " + probe.id() + " status=" + probe.status() + " error=" + probe.errorCode());
        assertNotNull(probe.result(),
                () -> "Missing probe report " + probe.id() + " error=" + probe.errorCode());
        assertEquals("fail", node(probe.result(), "S03").path("status").asText());
        return repairs.create(source.id(), new StartRepairRequest(probe.id(), source.artifactSha256()));
    }
    private RepairProposal good(RepairSummary repair) { return proposal(repair, List.of(new RepairEdit(OVERRIDE, ""))); }
    private RepairProposal proposal(RepairSummary repair, List<RepairEdit> edits) {
        return new RepairProposal(1, repair.baselineArtifactSha256(), "source.py", "移除重复方法",
                "保留原输入合同，静态消除重复定义；运行时仍待测", List.of("S03"), edits, null);
    }
    private ProbeSummary awaitProbe(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var value = probeStore.get(id);
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(value.status())) return value;
            Thread.sleep(20);
        }
        throw new AssertionError("Probe did not finish");
    }
    private RepairCandidateSummary await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var value = repairs.candidate(id);
            if (!Set.of("QUEUED", "RUNNING", "RUNTIME_QUEUED", "RUNTIME_RUNNING", "CANCELLING").contains(value.status())) return value;
            Thread.sleep(20);
        }
        throw new AssertionError("Candidate did not finish");
    }
    private JsonNode node(JsonNode report, String id) {
        for (JsonNode node : report.path("nodes")) if (node.path("id").asText().equals(id)) return node;
        throw new AssertionError("Missing node " + id);
    }
    private Map<String, byte[]> unzip(byte[] body) throws IOException {
        var files = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), zip.readAllBytes());
        }
        return files;
    }
}
