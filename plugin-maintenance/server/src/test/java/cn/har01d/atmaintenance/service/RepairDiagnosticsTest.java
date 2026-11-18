package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.ProbeSummary;
import cn.har01d.atmaintenance.dto.RepairCandidateSummary;
import cn.har01d.atmaintenance.dto.RepairSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairDiagnosticsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RepairDiagnostics diagnostics = new RepairDiagnostics(mapper);
    private final String sha = "a".repeat(64), profile = "b".repeat(64), runtime = "c".repeat(64);

    ObjectNode evidence(String source, String node, String reason) {
        var value = mapper.createObjectNode().put("inputSourceSha256", source).put("bundleSha256", runtime);
        value.putArray("deniedCodes");
        var loader = value.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                .put("inputSourceSha256", source).put("inputProfileSha256", profile);
        loader.putArray("nodes").addObject().put("id", node).put("status", "fail").put("reason", reason);
        return value;
    }

    RepairSummary repair(ObjectNode evidence) {
        var frozen = mapper.createObjectNode();
        var baseline = frozen.putObject("baseline").put("profileSha256", profile).put("runtimeSha256", runtime)
                .put("executionPolicySha256", "f".repeat(64));
        baseline.putObject("result").set("managedSourceEvidence", evidence);
        var repair = mock(RepairSummary.class);
        when(repair.id()).thenReturn("repair");
        when(repair.baselineRunId()).thenReturn("run");
        when(repair.baselineArtifactSha256()).thenReturn(sha);
        when(repair.freshness()).thenReturn("current");
        when(repair.frozen()).thenReturn(frozen);
        when(repair.maxRounds()).thenReturn(3);
        return repair;
    }

    RepairCandidateSummary candidate(String candidateSha, int round, ObjectNode evidence) {
        var candidate = mock(RepairCandidateSummary.class);
        when(candidate.id()).thenReturn("candidate-" + round);
        when(candidate.repairId()).thenReturn("repair");
        when(candidate.sha256()).thenReturn(candidateSha);
        when(candidate.roundNumber()).thenReturn(round);
        var result = mapper.createObjectNode().put("artifactSha256", candidateSha).put("runtimeRequested", true);
        result.set("runtimeEvidence", evidence);
        when(candidate.result()).thenReturn(result);
        return candidate;
    }

    @Test
    void authenticationIsAPrerequisiteNotASelectorRepairOrAnotherModelRound() throws Exception {
        for (var evidence : List.of(SourceAuthenticationEvidenceTest.jsonEvidence(mapper, sha),
                SourceAuthenticationEvidenceTest.legacyEvidence(mapper, sha, SourceCredentialIntegrationTest.AUTH_FAILURE))) {
            String before = evidence.toString();
            var repair = repair(evidence);
            var candidate = candidate(sha, 1, evidence);
            var report = diagnostics.forRepair(repair, List.of(candidate));
            assertEquals("credentials", report.path("findings").get(0).path("route").asText());
            assertTrue(report.path("authenticationEvidence").isObject());
            assertFalse(report.path("missingMaterials").toString().contains("json_response_structure"));
            assertFalse(report.path("automaticEnhancementRecommended").asBoolean());
            assertFalse(report.path("acceptanceChanged").asBoolean());
            assertEquals(before, evidence.toString());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "maintenance.authentication-replay", matches = ".+")
    void savedRealCandidateAuthenticationIsDiagnosedWithoutReexecutingTheSource() throws Exception {
        var saved = mapper.readTree(Files.readAllBytes(Path.of(System.getProperty("maintenance.authentication-replay"))));
        var json = mapper.copy().findAndRegisterModules();
        var original = json.treeToValue(saved.path("repair"), RepairSummary.class);
        var candidate = json.treeToValue(saved.path("candidate"), RepairCandidateSummary.class);
        String before = saved.toString();
        var report = diagnostics.forRepair(original, List.of(candidate));
        assertTrue(report.path("loaderReceiptMatched").asBoolean());
        assertEquals(candidate.id(), report.path("candidateId").asText());
        assertEquals("R01", report.path("authenticationEvidence").path("targetNode").asText());
        assertEquals("matched_complete_json_body", report.path("authenticationEvidence").path("basis").asText());
        assertEquals("credentials", report.path("findings").get(0).path("route").asText());
        assertEquals(0, report.path("newNetworkRequests").asInt());
        assertEquals(0, report.path("newModelCalls").asInt());
        assertFalse(report.path("acceptanceChanged").asBoolean());
        assertEquals(before, saved.toString());
    }

    @Test
    void passingLoaderNodesStillExposeTheExistingCandidateRegressionWithoutChangingAcceptance() {
        var original = evidence(sha, "R01", "PLAYER_URL_EMPTY");
        original.putObject("untrustedRuntimeSummary").put("homeItems", 22);
        String next = "d".repeat(64);
        var runtimeEvidence = evidence(next, "R01", "SELECTED_PLAY_ID_TO_DIRECT_PLAYER_CONTRACT");
        ((ObjectNode) runtimeEvidence.path("loaderValidation").path("nodes").get(0)).put("status", "pass");
        runtimeEvidence.putObject("untrustedRuntimeSummary").put("homeItems", 0);
        var candidate = candidate(next, 1, runtimeEvidence);
        var stored = (ObjectNode) candidate.result();
        stored.putArray("observedRegressions").add("homeItems").add("Cookie=PRIVATE");
        String before = stored.toString();
        var report = diagnostics.forRepair(repair(original), List.of(candidate));
        assertEquals("triage_only", report.path("conclusion").asText());
        assertEquals(mapper.createArrayNode().add("source_regression"), report.path("routes"));
        var finding = report.path("findings").get(0);
        assertEquals("", finding.path("node").asText());
        assertEquals("RUNTIME_REGRESSION", finding.path("reason").asText());
        assertEquals("matched_runtime_comparison", finding.path("basis").asText());
        assertFalse(finding.path("failureCauseEstablished").asBoolean());
        assertEquals("homeItems", finding.path("regression").path("field").asText());
        assertEquals(22, finding.path("regression").path("before").asInt());
        assertEquals(0, finding.path("regression").path("after").asInt());
        assertEquals(1, report.path("findings").size());
        assertFalse(report.toString().contains("PRIVATE"));
        assertFalse(report.path("acceptanceChanged").asBoolean());
        assertFalse(diagnostics.automaticEnhancement(repair(original), List.of(candidate)));
        assertEquals(before, stored.toString());
    }

    @Test
    void regressionTriageDoesNotInventLossFromCountVariationPausedRunsOrUnmatchedEvidence() {
        var original = evidence(sha, "R01", "PLAYER_URL_EMPTY");
        original.putObject("untrustedRuntimeSummary").put("homeItems", 22);
        String next = "d".repeat(64);
        var observed = evidence(next, "R01", "PLAYER_URL_EMPTY");
        var counts = observed.putObject("untrustedRuntimeSummary").put("homeItems", 21);
        var candidate = candidate(next, 1, observed);
        var stored = (ObjectNode) candidate.result();
        stored.putArray("observedRegressions").add("homeItems");
        var repair = repair(original);
        assertFalse(diagnostics.forRepair(repair, List.of(candidate)).path("routes").toString().contains("source_regression"));
        counts.put("homeItems", 0);
        assertTrue(diagnostics.forRepair(repair, List.of(candidate)).path("routes").toString().contains("source_regression"));
        stored.put("runtimeValidationPaused", true);
        assertFalse(diagnostics.forRepair(repair, List.of(candidate)).path("routes").toString().contains("source_regression"));
        stored.remove("runtimeValidationPaused");
        observed.put("inputSourceSha256", "e".repeat(64));
        var mismatch = diagnostics.forRepair(repair, List.of(candidate));
        assertTrue(mismatch.path("latestCandidateEvidenceUnavailable").asBoolean());
        assertFalse(mismatch.path("routes").toString().contains("source_regression"));
        observed.put("inputSourceSha256", next);
        original.put("inputSourceSha256", "e".repeat(64));
        assertFalse(diagnostics.forRepair(repair, List.of(candidate)).path("routes").toString().contains("source_regression"));
        original.put("inputSourceSha256", sha);
        stored.putArray("observedRegressions");
        assertFalse(diagnostics.forRepair(repair, List.of(candidate)).path("routes").toString().contains("source_regression"));
    }

    @Test
    @EnabledIfSystemProperty(named = "maintenance.regression-replay", matches = ".+")
    void frozenFourthCandidateShowsNetworkAssociatedHomeLossWithoutChangingStoredAcceptance() throws Exception {
        var terminal = mapper.readTree(Files.readAllBytes(Path.of(System.getProperty("maintenance.regression-replay"))));
        var saved = terminal.path("candidates").get(terminal.path("candidates").size() - 1);
        var frozen = terminal.path("repair").path("frozen");
        var repair = repair((ObjectNode) frozen.path("baseline").path("result").path("managedSourceEvidence"));
        when(repair.id()).thenReturn(saved.path("repairId").asText());
        when(repair.baselineArtifactSha256()).thenReturn(terminal.path("repair").path("baselineArtifactSha256").asText());
        when(repair.maxRounds()).thenReturn(4);
        when(repair.frozen()).thenReturn(frozen);
        var candidate = candidate(saved.path("sha256").asText(), 4,
                (ObjectNode) saved.path("result").path("runtimeEvidence"));
        when(candidate.repairId()).thenReturn(saved.path("repairId").asText());
        when(candidate.result()).thenReturn(saved.path("result"));
        String before = terminal.toString();
        var report = diagnostics.forRepair(repair, List.of(candidate));
        assertEquals(9, saved.path("result").path("runtimeAcceptance").path("requiredCoverage").path("pass").asInt());
        assertTrue(report.path("loaderReceiptMatched").asBoolean());
        assertEquals(1, report.path("findings").size());
        assertEquals("RUNTIME_NETWORK_UNAVAILABLE", report.path("findings").get(0).path("reason").asText());
        assertEquals(mapper.createArrayNode().add("network"), report.path("routes"));
        assertFalse(report.path("findings").get(0).path("failureCauseEstablished").asBoolean());
        assertEquals("homeItems", report.path("findings").get(0).path("regression").path("field").asText());
        assertEquals(22, report.path("findings").get(0).path("regression").path("before").asInt());
        assertEquals("triage_only", report.path("conclusion").asText());
        assertFalse(report.path("acceptanceChanged").asBoolean());
        assertFalse(saved.path("result").path("repairSuccessful").asBoolean());
        assertEquals(before, terminal.toString());
        var reports = new RepairReport(mapper);
        var reassessed = reports.runtime(frozen, saved.path("result"), saved.path("result").path("runtimeEvidence"));
        assertTrue(reassessed.path("runtimeValidationPaused").asBoolean());
        assertTrue(reassessed.path("observedRegressions").isEmpty());
        assertFalse(reassessed.path("repairSuccessful").asBoolean());
        assertTrue(reports.networkRetryable(frozen, saved.path("result")));
    }

    @Test
    void pageEvidenceDoesNotHideAnObservedJsonResponseGapAndJsonDoesNotInventCredentials() throws Exception {
        var evidence = evidence(sha, "R01", "PLAYER_URL_EMPTY");
        var resources = ManagedResourceEvidenceTest.fixture(mapper, sha).put("targetNode", "R01").putNull("image");
        evidence.set("resourceEvidence", resources);
        evidence.set("httpObservations", ManagedResourceEvidenceTest.events(mapper));
        var event = evidence.withArray("httpObservations").addObject().put("method", "POST")
                .put("requestUrlSha256", "7".repeat(64)).put("responseSha256", "8".repeat(64))
                .put("responseStatus", 200).put("responseBytes", 940).put("responseComplete", true)
                .put("responseContentType", "application/json");
        var gap = diagnostics.forRepair(repair(evidence), List.of());
        assertTrue(gap.path("resourceEvidenceAvailable").asBoolean());
        assertTrue(gap.path("missingMaterials").toString().contains("json_response_structure"));
        assertEquals(1, gap.path("jsonResponseEvidence").path("responsesWithoutStructure").asInt());
        evidence.set("resourceEvidence", ManagedResourceEvidenceTest.jsonFixture(mapper, sha));
        evidence.set("httpObservations", ManagedResourceEvidenceTest.jsonEvents(mapper));
        ((ObjectNode) evidence.path("loaderValidation")).set("calls", ManagedResourceEvidenceTest.jsonCalls(mapper));
        String before = evidence.toString();
        var complete = diagnostics.forRepair(repair(evidence), List.of());
        assertTrue(complete.path("missingMaterials").isEmpty());
        assertTrue(complete.path("resourceEvidenceAvailable").asBoolean());
        assertEquals(1, complete.path("jsonResponseEvidence").path("playCallAssociatedResponses").asInt());
        assertEquals(0, complete.path("jsonResponseEvidence").path("responsesWithoutStructure").asInt());
        assertEquals("source_contract", complete.path("findings").get(0).path("route").asText());
        assertFalse(complete.path("jsonResponseEvidence").path("failureCauseEstablished").asBoolean());
        assertFalse(complete.path("acceptanceChanged").asBoolean());
        assertEquals(before, evidence.toString());
    }

    @Test
    void nonApiAndMagnetSourcesDoNotAcquireAnInventedJsonRequirement() {
        for (String reason : List.of("PLAYER_URL_EMPTY", "PLAYER_OFFLINE_RESOLVER_REQUIRED")) {
            var evidence = evidence(sha, "R01", reason);
            evidence.putArray("httpObservations").addObject().put("responseComplete", true)
                    .put("method", "GET").put("responseStatus", 200).put("responseContentType", "text/html");
            var result = diagnostics.forRepair(repair(evidence), List.of());
            assertFalse(result.path("missingMaterials").toString().contains("json_response_structure"));
            assertEquals(reason.equals("PLAYER_URL_EMPTY") ? "source_contract" : "at_offline",
                    result.path("findings").get(0).path("route").asText());
        }
    }

    @Test
    void networkFailureRoutesOnlyTheMatchingSourceMethodWithoutClaimingCausality() {
        var evidence = evidence(sha, "C03", "CONTENT_LIST_EMPTY_OR_INVALID");
        ((com.fasterxml.jackson.databind.node.ArrayNode) evidence.path("loaderValidation").path("nodes"))
                .addObject().put("id", "R01").put("status", "blocked").put("reason", "PLAYER_URL_EMPTY");
        evidence.withArray("deniedCodes").add("EGRESS_HTTP_FAILED");
        evidence.putArray("networkFailureCalls").addObject().put("denialIndex", 0).put("method", "category");
        String before = evidence.toString();
        var result = diagnostics.forRepair(repair(evidence), List.of());
        var category = result.path("findings").get(0);
        assertEquals("network", category.path("route").asText());
        assertEquals("CONTENT_LIST_EMPTY_OR_INVALID", category.path("reason").asText());
        assertTrue(category.path("networkFailureObserved").asBoolean());
        assertEquals("source_method_observation", category.path("networkAssociation").asText());
        assertEquals("category", category.path("networkDenials").get(0).path("method").asText());
        assertEquals("EGRESS_HTTP_FAILED", category.path("networkDenials").get(0).path("code").asText());
        assertFalse(category.path("networkDenials").get(0).path("connectionAssociationEstablished").asBoolean(true));
        assertFalse(category.path("failureCauseEstablished").asBoolean());
        assertEquals("source_contract", result.path("findings").get(1).path("route").asText());
        assertFalse(result.path("findings").get(1).has("networkFailureObserved"));
        assertFalse(result.path("findings").get(1).has("networkDenials"));
        assertEquals(before, evidence.toString());
        for (int index : List.of(-1, 1)) {
            ((ObjectNode) evidence.path("networkFailureCalls").get(0)).put("denialIndex", index);
            assertEquals("source_contract", diagnostics.forRepair(repair(evidence), List.of())
                    .path("findings").get(0).path("route").asText());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "maintenance.player-replay", matches = ".+")
    void frozenPlayerCandidateNetworkFailureDoesNotBecomeAnExtractionDiagnosis() throws Exception {
        var terminal = mapper.readTree(Files.readAllBytes(Path.of(System.getProperty("maintenance.player-replay"))));
        var candidate = terminal.path("candidates").get(0);
        var evidence = (ObjectNode) candidate.path("result").path("runtimeEvidence");
        var run = mock(ProbeSummary.class);
        when(run.id()).thenReturn(terminal.path("run").path("id").asText());
        when(run.artifactSha256()).thenReturn(candidate.path("sha256").asText());
        when(run.profileSha256()).thenReturn(evidence.path("loaderValidation").path("inputProfileSha256").asText());
        when(run.runtimeSha256()).thenReturn(evidence.path("bundleSha256").asText());
        when(run.freshness()).thenReturn("current");
        when(run.result()).thenReturn(mapper.createObjectNode().set("managedSourceEvidence", evidence));
        String before = evidence.toString();
        var result = diagnostics.forRun(run);
        assertTrue(result.path("loaderReceiptMatched").asBoolean());
        assertEquals("network", result.path("findings").get(0).path("route").asText());
        assertEquals("C03", result.path("findings").get(0).path("node").asText());
        assertEquals(9, result.path("http").path("completedResponses").asInt());
        assertEquals(mapper.createArrayNode().add("network"), result.path("routes"));
        assertFalse(result.path("acceptanceChanged").asBoolean());
        assertEquals(before, evidence.toString());
    }

    @Test
    void confirmedChallengeRoutesTheFailedNodeWithoutChangingItsVerdict() {
        var evidence = evidence(sha, "C03", "CONTENT_LIST_EMPTY_OR_INVALID");
        AccessPageClassifierTest.attach(mapper, evidence, sha, AccessPageClassifierTest.CHALLENGE);
        String before = evidence.toString();
        var result = diagnostics.forRepair(repair(evidence), List.of());
        assertEquals(mapper.createArrayNode().add("access_evidence"), result.path("routes"));
        assertEquals("CONTENT_LIST_EMPTY_OR_INVALID", result.path("findings").get(0).path("reason").asText());
        assertEquals("fail", result.path("findings").get(0).path("status").asText());
        assertEquals("javascript_cookie_challenge", result.path("findings").get(0).path("accessClassification").asText());
        assertEquals("complete_http_page_body", result.path("accessPage").path("basis").asText());
        assertTrue(result.path("accessPage").path("repairBlocked").asBoolean());
        assertEquals("authorized_access_or_manual_verification", result.path("accessPage").path("nextAction").asText());
        assertFalse(result.path("accessPage").path("automaticChallengeSolving").asBoolean());
        assertFalse(result.toString().contains("private-value"));
        assertFalse(result.path("acceptanceChanged").asBoolean());
        assertEquals(before, evidence.toString());
        ((ObjectNode) evidence.path("httpObservations").get(0)).put("responseComplete", false);
        assertFalse(diagnostics.forRepair(repair(evidence), List.of()).has("accessPage"));
    }

    @Test
    void cloudflareDenialPreservesOriginalFailureAndOnlyBlocksTheObservedFailedAction() {
        var evidence = evidence(sha, "C03", "CONTENT_LIST_EMPTY_OR_INVALID");
        AccessPageClassifierTest.denied(mapper, evidence, sha);
        AccessPageClassifierTest.observeDuring(mapper, evidence, "category");
        String before = evidence.toString();
        var result = diagnostics.forRepair(repair(evidence), List.of());
        assertEquals("cloudflare_access_denied", result.path("accessPage").path("classification").asText());
        assertTrue(result.path("accessPage").path("repairBlocked").asBoolean());
        assertEquals("authorized_access_or_verified_origin", result.path("accessPage").path("nextAction").asText());
        assertEquals("CONTENT_LIST_EMPTY_OR_INVALID", result.path("findings").get(0).path("reason").asText());
        assertEquals("access_evidence", result.path("findings").get(0).path("route").asText());
        assertFalse(result.path("automaticEnhancementRecommended").asBoolean());
        assertEquals(before, evidence.toString());
        ((ObjectNode) evidence.path("loaderValidation").path("nodes").get(0)).put("id", "C07");
        assertFalse(diagnostics.forRepair(repair(evidence), List.of()).path("accessPage").path("repairBlocked").asBoolean());
    }

    @Test
    void challengeObservationUsesCallScopeRatherThanCollectionTargetAndDoesNotEstablishCausality() {
        for (String html : List.of(AccessPageClassifierTest.CHALLENGE, AccessPageClassifierTest.CF_CHALLENGE)) {
            var evidence = evidence(sha, "C07", "DETAIL_IDENTITY_MISMATCH");
            AccessPageClassifierTest.attach(mapper, evidence, sha, html).put("targetNode", "C07");
            AccessPageClassifierTest.observeDuring(mapper, evidence, "category");
            String before = evidence.toString();
            var result = diagnostics.forRepair(repair(evidence), List.of());
            assertEquals("source_contract", result.path("findings").get(0).path("route").asText());
            assertFalse(result.path("findings").get(0).has("accessClassification"));
            assertTrue(result.path("routes").toString().contains("access_evidence"));
            assertEquals("C03", result.path("accessPage").path("observedNode").asText());
            assertFalse(result.path("accessPage").path("repairBlocked").asBoolean());
            assertEquals("continue_independent_repair", result.path("accessPage").path("nextAction").asText());
            assertFalse(result.path("accessPage").path("failureCauseEstablished").asBoolean());
            ((ObjectNode) evidence.path("loaderValidation").path("nodes").get(0)).put("id", "C03");
            var matched = diagnostics.forRepair(repair(evidence), List.of()).path("findings").get(0);
            assertEquals("access_evidence", matched.path("route").asText());
            assertEquals("observed_during_call", matched.path("accessAssociation").asText());
            assertFalse(matched.path("failureCauseEstablished").asBoolean());
            ((ObjectNode) evidence.path("loaderValidation").path("nodes").get(0)).put("id", "C07");
            assertEquals(before, evidence.toString());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "maintenance.cf-replay", matches = ".+")
    void frozenReal403IsClassifiedWithoutAttributingItToDetailOrPlayerFailures() throws Exception {
        var replay = mapper.readTree(Files.readAllBytes(Path.of(System.getProperty("maintenance.cf-replay"))));
        assertTrue(replay.path("projected").asBoolean());
        assertFalse(replay.path("sourceExecuted").asBoolean());
        assertEquals(0, replay.path("networkRequests").asInt());
        var evidence = replay.path("evidence");
        var run = mock(ProbeSummary.class);
        when(run.id()).thenReturn(replay.path("jobId").asText());
        when(run.artifactSha256()).thenReturn(evidence.path("inputSourceSha256").asText());
        when(run.profileSha256()).thenReturn(evidence.path("loaderValidation").path("inputProfileSha256").asText());
        when(run.runtimeSha256()).thenReturn(evidence.path("bundleSha256").asText());
        when(run.freshness()).thenReturn("current");
        when(run.result()).thenReturn(mapper.createObjectNode().set("managedSourceEvidence", evidence));
        String before = evidence.toString();
        var result = diagnostics.forRun(run);
        var access = result.path("accessPage");
        assertEquals("cloudflare_challenge", access.path("classification").asText());
        assertEquals(403, access.path("responseStatus").asInt());
        assertEquals(5710, access.path("responseBytes").asInt());
        assertEquals("C07", access.path("targetNode").asText());
        assertEquals("C03", access.path("observedNode").asText());
        assertEquals(3, access.path("observedDuringCall").path("id").asInt());
        assertFalse(access.path("failureCauseEstablished").asBoolean());
        assertTrue(result.path("findings").valueStream().anyMatch(item -> item.path("node").asText().equals("C07")));
        var detail = result.path("findings").valueStream().filter(item -> item.path("node").asText().equals("C07"))
                .findFirst().orElseThrow().path("contractObservations").path("identityEvidence");
        assertFalse(detail.path("exactMatch").asBoolean(true));
        assertTrue(detail.path("coverMatch").asBoolean());
        assertFalse(detail.has("selectedIdSha256"));
        for (var finding : result.path("findings")) {
            if (List.of("C07", "R01").contains(finding.path("node").asText()))
                assertFalse(finding.has("accessClassification"));
        }
        assertEquals(before, evidence.toString());
    }

    @Test
    void latestCandidateContentOverridesHistoricalChallengeAndOnlyItsOwnNodeIsRouted() {
        var original = evidence(sha, "C03", "CONTENT_LIST_EMPTY_OR_INVALID");
        AccessPageClassifierTest.attach(mapper, original, sha, AccessPageClassifierTest.CHALLENGE);
        String nextSha = "d".repeat(64);
        var current = evidence(nextSha, "C07", "DETAIL_IDENTITY_MISMATCH");
        AccessPageClassifierTest.attach(mapper, current, nextSha, "<html><body>Forum topics</body></html>");
        assertFalse(diagnostics.forRepair(repair(original), List.of(candidate(nextSha, 1, current))).has("accessPage"));
        AccessPageClassifierTest.attach(mapper, current, nextSha, AccessPageClassifierTest.CHALLENGE);
        var result = diagnostics.forRepair(repair(original), List.of(candidate(nextSha, 1, current)));
        assertEquals("source_contract", result.path("findings").get(0).path("route").asText());
        assertFalse(result.path("findings").get(0).has("accessClassification"));
    }

    @Test
    void triageUsesExistingMatchedEvidenceWithoutChangingAcceptance() {
        var evidence = evidence(sha, "C03", "CONTENT_LIST_EMPTY_OR_INVALID");
        evidence.putArray("httpObservations").addObject().put("responseComplete", true).put("responseStatus", 200)
                .put("requestUrl", "https://private.invalid/?token=SECRET");
        String before = evidence.toString();
        var result = diagnostics.forRepair(repair(evidence), List.of());
        assertTrue(result.path("loaderReceiptMatched").asBoolean());
        assertTrue(result.path("routes").toString().contains("source_contract"));
        assertTrue(result.path("missingMaterials").toString().contains("public_response_structure"));
        assertEquals(1, result.path("http").path("completedResponses").asInt());
        assertEquals(0, result.path("newNetworkRequests").asInt());
        assertFalse(result.path("acceptanceChanged").asBoolean());
        assertFalse(result.toString().contains("SECRET"));
        assertEquals(before, evidence.toString());
    }

    @Test
    void contractDetailsExposeSampledCountsIdentityAndMagnetHandoffWithoutPrivateValues() {
        var evidence = evidence(sha, "C07", "DETAIL_IDENTITY_MISMATCH");
        var nodes = evidence.withObject("loaderValidation").withArray("nodes");
        var identity = ((ObjectNode) nodes.get(0)).putObject("identityEvidence")
                .put("selectedIdSha256", "SECRET").put("returnedIdSha256", "SECRET")
                .put("exactMatch", false).put("guidCoverMatch", false).put("atvpDetailPrefixMatch", false)
                .put("nameMatch", false).put("coverMatch", true).put("name", "SECRET");
        nodes.addObject().put("id", "C03").put("status", "fail").put("reason", "PAGINATION_METADATA_INVALID")
                .put("firstItems", 20).put("secondItems", 20).put("pagesObserved", 2).put("pages", -1)
                .put("lastItems", 40_000_001).put("paginationMode", "page-window").put("exactTotalVerified", false)
                .put("lastPageVerified", "SECRET").put("url", "SECRET");
        nodes.addObject().put("id", "R01").put("status", "blocked").put("reason", "PLAYER_OFFLINE_RESOLVER_REQUIRED")
                .put("playerRouteKind", "magnet").put("offlineResolverRequired", true).put("magnetInfoHashValid", true)
                .put("playerUrlSha256", "SECRET");
        String before = evidence.toString();
        var result = diagnostics.forRepair(repair(evidence), List.of());
        var findings = result.path("findings");
        var detail = findings.get(0).path("contractObservations").path("identityEvidence");
        assertEquals(5, detail.size());
        assertEquals(identity.path("coverMatch"), detail.path("coverMatch"));
        assertFalse(detail.path("exactMatch").asBoolean(true));
        var pagination = findings.get(1).path("contractObservations");
        assertEquals(20, pagination.path("firstItems").asInt());
        assertEquals(2, pagination.path("pagesObserved").asInt());
        assertFalse(pagination.has("pages"));
        assertFalse(pagination.has("lastItems"));
        assertFalse(pagination.has("lastPageVerified"));
        assertEquals("at_offline", findings.get(2).path("route").asText());
        assertEquals("magnet", findings.get(2).path("contractObservations").path("playerRouteKind").asText());
        assertFalse(result.toString().contains("SECRET"));
        assertEquals(before, evidence.toString());
    }

    @Test
    void playerDiagnosticsReachRepairContextWithoutCopyingRawUrlsOrChangingAcceptance() {
        var evidence = evidence(sha, "R01", "PLAYER_URL_INVALID");
        ((ObjectNode) evidence.path("loaderValidation").path("nodes").get(0)).putObject("playerContract")
                .put("playCallId", 7).put("detailCallId", 5).put("returnedUrlBytes", 55)
                .put("returnedUrlType", "string").put("selectedIdentifierShape", "opaque")
                .put("returnedUrlShape", "protocol_relative").put("returnedUrlMatchesIdentifier", true)
                .put("parseMode", "direct").put("hasWhitespace", false).put("hasFragment", true)
                .put("hasUserInfo", false).put("hasHostname", true).put("url", "SECRET");
        String before = evidence.toString();
        var result = diagnostics.forRepair(repair(evidence), List.of());
        var contract = result.path("findings").get(0).path("contractObservations").path("playerContract");
        assertEquals("protocol_relative", contract.path("returnedUrlShape").asText());
        assertTrue(contract.path("returnedUrlMatchesIdentifier").asBoolean());
        assertEquals(7, contract.path("playCallId").asInt());
        assertFalse(result.toString().contains("SECRET"));
        assertFalse(result.path("acceptanceChanged").asBoolean());
        assertEquals(before, evidence.toString());
    }

    @Test
    void initializationUsesSafeTypeAndNeverCopiesExceptionText() {
        var evidence = evidence(sha, "S06", "LOADER_INIT_FAILED");
        ((ObjectNode) evidence.path("loaderValidation").path("nodes").get(0))
                .put("failureType", "NameError").put("error", "Cookie=SECRET");
        var result = diagnostics.forRepair(repair(evidence), List.of());
        assertEquals("initialization", result.path("findings").get(0).path("route").asText());
        assertEquals("NameError", result.path("findings").get(0).path("failureType").asText());
        assertFalse(result.toString().contains("SECRET"));
    }

    @Test
    void missingImportsAreRuntimeDependenciesRatherThanGuessedSourceFailures() {
        var evidence = evidence(sha, "S06", "LOADER_METHOD_FAILED");
        ((ObjectNode) evidence.path("loaderValidation").path("nodes").get(0)).put("failureType", "ModuleNotFoundError");
        assertEquals("dependency", diagnostics.forRepair(repair(evidence), List.of())
                .path("findings").get(0).path("route").asText());
    }

    @Test
    void latestBoundCandidateSupersedesBaselineButAnotherRepairsCandidateCannot() {
        var original = evidence(sha, "C03", "CONTENT_LIST_EMPTY_OR_INVALID");
        var first = candidate("d".repeat(64), 1, evidence("d".repeat(64), "C07", "DETAIL_IDENTITY_MISMATCH"));
        var second = candidate("e".repeat(64), 2, evidence("e".repeat(64), "R01", "PLAYER_EXTERNAL_PARSER_REQUIRED"));
        var foreign = candidate("f".repeat(64), 3, evidence("f".repeat(64), "S06", "LOADER_INIT_FAILED"));
        when(foreign.repairId()).thenReturn("other-repair");
        var result = diagnostics.forRepair(repair(original), List.of(second, foreign, first));
        assertEquals("candidate-2", result.path("candidateId").asText());
        assertEquals("player_resource", result.path("findings").get(0).path("route").asText());
        assertEquals("e".repeat(64), result.path("artifactSha256").asText());
        assertEquals(sha, result.path("baselineArtifactSha256").asText());
    }

    @Test
    void mismatchedLatestCandidateNeverBecomesCurrentEvidence() {
        var bad = candidate("d".repeat(64), 2, evidence("e".repeat(64), "S06", "LOADER_INIT_FAILED"));
        var result = diagnostics.forRepair(repair(evidence(sha, "C07", "DETAIL_IDENTITY_MISMATCH")), List.of(bad));
        assertTrue(result.path("latestCandidateEvidenceUnavailable").asBoolean());
        assertFalse(result.has("candidateId"));
        assertEquals("C07", result.path("findings").get(0).path("node").asText());
    }

    @Test
    void automaticEnhancementRequiresAnObservedCurrentPlayerFailureNotTheOriginalAlone() {
        var repair = repair(evidence(sha, "R01", "PLAYER_URL_EMPTY"));
        assertFalse(diagnostics.automaticEnhancement(repair, List.of()));
        String next = "d".repeat(64);
        var latest = candidate(next, 1, evidence(next, "R01", "PLAYER_URL_EMPTY"));
        assertTrue(diagnostics.automaticEnhancement(repair, List.of(latest)));
        var parser = candidate(next, 1, evidence(next, "R01", "PLAYER_EXTERNAL_PARSER_REQUIRED"));
        assertTrue(diagnostics.automaticEnhancement(repair, List.of(parser)));
        var wrong = candidate(next, 2, evidence("e".repeat(64), "R01", "PLAYER_URL_EMPTY"));
        assertFalse(diagnostics.automaticEnhancement(repair, List.of(latest, wrong)));
        when(repair.freshness()).thenReturn("stale");
        assertFalse(diagnostics.automaticEnhancement(repair, List.of(latest)));
    }

    @Test
    void automaticEnhancementCoversObservedCategoryAndDetailFailuresSupportedByTheBrowser() {
        String next = "d".repeat(64);
        for (var failure : java.util.Map.of(
                "C01", "HOME_CATEGORIES_INVALID",
                "C03", "PAGINATION_REPLAY_OR_PAGE_MISMATCH",
                "C07", "DETAIL_IDENTITY_MISMATCH",
                "C10", "PLAY_GROUPS_INVALID").entrySet()) {
            var repair = repair(evidence(sha, failure.getKey(), failure.getValue()));
            assertFalse(diagnostics.automaticEnhancement(repair, List.of()), failure.getKey());
            var observed = evidence(next, failure.getKey(), failure.getValue());
            assertTrue(diagnostics.automaticEnhancement(repair,
                    List.of(candidate(next, 1, observed))), failure.getKey());
            var wrong = candidate(next, 2, evidence("e".repeat(64), failure.getKey(), failure.getValue()));
            assertFalse(diagnostics.automaticEnhancement(repair,
                    List.of(candidate(next, 1, observed), wrong)), failure.getKey());
            ((ObjectNode) observed.path("loaderValidation").path("nodes").get(0)).put("status", "pass");
            assertFalse(diagnostics.automaticEnhancement(repair,
                    List.of(candidate(next, 1, observed))), failure.getKey());
        }
    }

    @Test
    void automaticCategoryAndDetailEnhancementDoesNotOverrideAccessOrNetworkFailures() {
        String next = "d".repeat(64);
        for (String node : List.of("C01", "C03", "C07", "C10")) {
            var repair = repair(evidence(sha, node, "LOADER_METHOD_FAILED"));
            for (int status : List.of(401, 403, 429)) {
                var observed = evidence(next, node, "LOADER_METHOD_FAILED");
                observed.putArray("httpObservations").addObject().put("responseComplete", true)
                        .put("responseStatus", status);
                assertFalse(diagnostics.automaticEnhancement(repair,
                        List.of(candidate(next, 1, observed))), node + ":" + status);
            }
            for (String reason : List.of("EGRESS_HTTP_FAILED", "EGRESS_HEADERS_DENIED",
                    "EGRESS_RATE_LIMITED", "EGRESS_REQUEST_BUDGET")) {
                assertFalse(diagnostics.automaticEnhancement(repair,
                        List.of(candidate(next, 1, evidence(next, node, reason)))), node + ":" + reason);
            }
        }
        for (String node : List.of("C05", "C09", "R06", "M01", "S06")) {
            assertFalse(diagnostics.automaticEnhancement(repair(evidence(sha, node, "LOADER_METHOD_FAILED")),
                    List.of(candidate(next, 1, evidence(next, node, "LOADER_METHOD_FAILED")))), node);
        }
    }

    @Test
    void handledChallengeOnAPassingActionDoesNotSuppressAnotherActionsEnhancement() {
        String next = "d".repeat(64);
        var repair = repair(evidence(sha, "C07", "DETAIL_IDENTITY_MISMATCH"));
        var current = evidence(next, "C07", "DETAIL_IDENTITY_MISMATCH");
        var category = current.withObject("loaderValidation").withArray("nodes").addObject()
                .put("id", "C03").put("status", "pass");
        var resource = AccessPageClassifierTest.attach(mapper, current, next, AccessPageClassifierTest.CF_CHALLENGE);
        resource.put("targetNode", "C07");
        ((ObjectNode) resource.path("resources").get(0)).put("responseStatus", 403);
        ((ObjectNode) current.path("httpObservations").get(0)).put("responseStatus", 403);
        current.withArray("httpObservations").addObject().put("responseComplete", true).put("responseStatus", 200);
        AccessPageClassifierTest.observeDuring(mapper, current, "category");
        var candidate = candidate(next, 1, current);
        var result = diagnostics.forRepair(repair, List.of(candidate));
        assertTrue(result.path("automaticEnhancementRecommended").asBoolean());
        assertEquals("pass", result.path("accessPage").path("observedNodeStatus").asText());
        assertEquals("source_contract", result.path("findings").get(0).path("route").asText());
        assertFalse(result.path("accessPage").path("failureCauseEstablished").asBoolean());
        assertTrue(result.path("routes").toString().contains("access_evidence"));
        category.put("status", "fail").put("reason", "CONTENT_LIST_EMPTY_OR_INVALID");
        assertFalse(diagnostics.automaticEnhancement(repair, List.of(candidate)));
        category.put("status", "not_tested");
        assertFalse(diagnostics.automaticEnhancement(repair, List.of(candidate)));
        category.put("status", "pass");
        ((ObjectNode) current.path("httpObservations").get(1)).put("responseStatus", 403);
        assertFalse(diagnostics.automaticEnhancement(repair, List.of(candidate)));
    }

    @Test
    @EnabledIfSystemProperty(named = "maintenance.detail-replay", matches = ".+")
    void realCurrentDetailReceiptSelectsEnhancementWithoutReclassifyingTheCategoryChallenge() throws Exception {
        var snapshot = mapper.readTree(Files.readAllBytes(Path.of(System.getProperty("maintenance.detail-replay"))));
        var stored = snapshot.path("repair");
        var repair = mock(RepairSummary.class);
        when(repair.id()).thenReturn(stored.path("id").asText());
        when(repair.baselineRunId()).thenReturn(stored.path("baselineRunId").asText());
        when(repair.baselineArtifactSha256()).thenReturn(stored.path("baselineArtifactSha256").asText());
        when(repair.frozen()).thenReturn(stored.path("frozen"));
        when(repair.freshness()).thenReturn(stored.path("freshness").asText());
        when(repair.usedRounds()).thenReturn(stored.path("usedRounds").asInt());
        when(repair.maxRounds()).thenReturn(stored.path("maxRounds").asInt());
        var candidates = new java.util.ArrayList<RepairCandidateSummary>();
        for (var row : snapshot.path("candidates")) {
            var candidate = mock(RepairCandidateSummary.class);
            when(candidate.id()).thenReturn(row.path("id").asText());
            when(candidate.repairId()).thenReturn(row.path("repairId").asText());
            when(candidate.sha256()).thenReturn(row.path("sha256").asText());
            when(candidate.roundNumber()).thenReturn(row.path("roundNumber").asInt());
            when(candidate.result()).thenReturn(row.path("result"));
            candidates.add(candidate);
        }
        String before = snapshot.toString();
        var diagnostic = diagnostics.forRepair(repair, candidates);
        assertEquals("current", diagnostic.path("freshness").asText());
        assertTrue(diagnostic.path("loaderReceiptMatched").asBoolean());
        assertTrue(diagnostic.path("automaticEnhancementRecommended").asBoolean());
        assertEquals("cloudflare_challenge", diagnostic.path("accessPage").path("classification").asText());
        assertEquals("C03", diagnostic.path("accessPage").path("observedNode").asText());
        assertEquals("pass", diagnostic.path("accessPage").path("observedNodeStatus").asText());
        assertFalse(diagnostic.path("accessPage").path("repairBlocked").asBoolean());
        assertEquals("continue_independent_repair", diagnostic.path("accessPage").path("nextAction").asText());
        assertTrue(diagnostic.path("findings").valueStream().anyMatch(finding ->
                "C07".equals(finding.path("node").asText()) && "source_contract".equals(finding.path("route").asText())));
        assertFalse(diagnostic.path("accessPage").path("failureCauseEstablished").asBoolean());
        assertFalse(candidates.getLast().result().path("repairSuccessful").asBoolean());
        assertEquals(before, snapshot.toString());
    }

    @Test
    void automaticEnhancementDoesNotTreatTransportPolicyOrOfflineHandoffsAsExtractionFailures() {
        String next = "d".repeat(64);
        var repair = repair(evidence(sha, "R01", "PLAYER_URL_EMPTY"));
        for (String reason : List.of("EGRESS_HTTP_FAILED", "EGRESS_HEADERS_DENIED", "EGRESS_RATE_LIMITED",
                "EGRESS_REQUEST_BUDGET", "PLAYER_OFFLINE_RESOLVER_REQUIRED", "LOADER_NODE_UNRESOLVED")) {
            var latest = candidate(next, 1, evidence(next, "R01", reason));
            assertFalse(diagnostics.automaticEnhancement(repair, List.of(latest)), reason);
        }
        for (int status : List.of(401, 403, 429)) {
            var observed = evidence(next, "R01", "PLAYER_URL_EMPTY");
            observed.putArray("httpObservations").addObject().put("responseComplete", true)
                    .put("responseStatus", status);
            assertFalse(diagnostics.automaticEnhancement(repair, List.of(candidate(next, 1, observed))));
        }
    }

    @Test
    void latestFailedExecutionSupersedesBaselineOnlyAsDiagnosticData() {
        var original = evidence(sha, "C01", "HOME_CATEGORIES_INVALID");
        var latest = candidate("d".repeat(64), 2, mapper.createObjectNode());
        var result = (ObjectNode) latest.result();
        var failure = result.putObject("managedExecutionFailure").put("candidateId", latest.id())
                .put("jobId", latest.id()).put("inputSourceSha256", latest.sha256())
                .put("inputProfileSha256", profile).put("bundleSha256", runtime)
                .put("executionPolicySha256", "f".repeat(64)).put("code", "EGRESS_BYTE_BUDGET");
        var diagnostic = failure.putObject("diagnostic").put("authority", "managed_source_broker_observed")
                .put("rawUrl", "https://private.invalid/?token=SECRET");
        var rows = diagnostic.putObject("exchangeContext").putArray("completedHttpObservations");
        rows.addObject().put("responseComplete", true).put("responseStatus", 200).put("responseBytes", 1405883);
        rows.addObject().put("responseComplete", true).put("responseStatus", 302).put("responseBytes", 0);
        rows.addObject().put("responseComplete", false).put("responseStatus", 200).put("responseBytes", 2000000);
        var report = diagnostics.forRepair(repair(original), List.of(latest));
        assertEquals(latest.id(), report.path("candidateId").asText());
        assertEquals(latest.sha256(), report.path("artifactSha256").asText());
        assertTrue(report.path("hostFailureDiagnosticMatched").asBoolean());
        assertFalse(report.path("latestCandidateEvidenceUnavailable").asBoolean());
        assertFalse(report.path("loaderReceiptMatched").asBoolean());
        assertEquals(2, report.path("http").path("completedResponses").asInt());
        assertEquals(1405883, report.path("http").path("completedResponseBytes").asInt());
        assertTrue(report.path("routes").toString().contains("request_budget"));
        assertEquals("EGRESS_BYTE_BUDGET", report.path("findings").get(0).path("reason").asText());
        assertEquals(mapper.createArrayNode().add("matched_loader_receipt"), report.path("missingMaterials"));
        assertFalse(report.toString().contains("SECRET"));
        assertFalse(report.path("acceptanceChanged").asBoolean());
        failure.put("code", "EGRESS_HEADERS_DENIED");
        var header = diagnostic.withObject("exchangeContext").putObject("requestDiagnostic")
                .put("kind", "header").put("reason", "header_not_allowed")
                .put("headerName", "x-requested-with").put("value", "SECRET");
        var headerReport = diagnostics.forRepair(repair(original), List.of(latest));
        var headerFinding = headerReport.path("findings").get(0);
        assertEquals("EGRESS_HEADERS_DENIED", headerFinding.path("reason").asText());
        assertEquals("request_policy", headerFinding.path("route").asText());
        assertEquals("x-requested-with", headerFinding.path("requestDiagnostic").path("headerName").asText());
        assertFalse(headerReport.toString().contains("SECRET"));
        header.put("headerName", "Cookie=SECRET");
        assertFalse(diagnostics.forRepair(repair(original), List.of(latest)).path("findings").get(0)
                .has("requestDiagnostic"));
        var current = failure.deepCopy();
        for (String field : List.of("candidateId", "jobId", "inputSourceSha256", "inputProfileSha256",
                "bundleSha256", "executionPolicySha256")) {
            failure.put(field, "changed");
            var rejected = diagnostics.forRepair(repair(original), List.of(latest));
            assertTrue(rejected.path("latestCandidateEvidenceUnavailable").asBoolean(), field);
            assertFalse(rejected.has("candidateId"), field);
            failure.setAll(current);
        }
        failure.put("code", "Cookie=SECRET");
        var unknown = diagnostics.forRepair(repair(original), List.of(latest));
        assertEquals("LOADER_NODE_UNRESOLVED", unknown.path("findings").get(0).path("reason").asText());
        assertFalse(unknown.toString().contains("SECRET"));
    }

    static ObjectNode interruptedContext(ObjectMapper mapper) {
        var context = mapper.createObjectNode().put("phase", "fetch").put("lastHttpId", 3);
        context.putObject("networkObservation").put("requestNumber", 2).put("addressIndex", 0)
                .put("stage", "tls_handshake").put("kind", "timeout").putNull("errno")
                .put("elapsedMilliseconds", 5032).put("rawUrl", "PRIVATE");
        context.putObject("dnsObservation").put("dnsStatus", 3).put("answerCount", 0)
                .put("publicIpv4Count", 0).put("recordType", "AAAA").put("publicIpv6Count", 0)
                .put("kind", "no_address").put("hostname", "PRIVATE");
        context.putObject("rateLimit").put("responseStatus", 429).put("automaticRetry", false)
                .put("rawHeader", "PRIVATE").putObject("retryAfter").put("kind", "delay").put("seconds", 12);
        return context;
    }

    private ObjectNode interruptedReport() {
        var report = mapper.createObjectNode().put("artifactSha256", sha).put("profileSha256", profile)
                .put("runtimeSha256", runtime).put("executionPolicySha256", "f".repeat(64))
                .put("verdict", "managed_source_blocked");
        report.putObject("managedExecutionDiagnostic").put("schemaVersion", 1)
                .put("authority", "managed_source_broker_observed").set("exchangeContext", interruptedContext(mapper));
        return report;
    }

    private ProbeSummary interruptedRun(ObjectNode report) {
        var run = mock(ProbeSummary.class);
        when(run.id()).thenReturn("failed-run");
        when(run.artifactSha256()).thenReturn(sha);
        when(run.profileSha256()).thenReturn(profile);
        when(run.runtimeSha256()).thenReturn(runtime);
        when(run.executionPolicySha256()).thenReturn("f".repeat(64));
        when(run.executionMode()).thenReturn("managed_source");
        when(run.freshness()).thenReturn("runtime_changed");
        when(run.errorCode()).thenReturn("EGRESS_URL_DENIED");
        when(run.result()).thenReturn(report);
        return run;
    }

    @Test
    void interruptedRunExposesStoredDnsTransportAndRateDetailsWithoutAssigningTerminalCause() {
        var report = interruptedReport();
        String before = report.toString();
        var result = diagnostics.forRun(interruptedRun(report));
        assertTrue(result.path("hostFailureDiagnosticMatched").asBoolean());
        assertFalse(result.path("loaderReceiptMatched").asBoolean());
        assertFalse(result.path("acceptanceChanged").asBoolean());
        assertEquals("runtime_changed", result.path("freshness").asText());
        assertEquals("request_policy", result.path("findings").get(0).path("route").asText());
        assertEquals("fetch", result.path("findings").get(0).path("executionPhase").asText());
        assertEquals("matched_host_failure_diagnostic", result.path("transportEvidence").path("basis").asText());
        assertEquals("last_recorded_observation", result.path("transportEvidence").path("scope").asText());
        assertEquals("timeout", result.path("transportEvidence").path("observations").get(0).path("kind").asText());
        assertEquals("no_address", result.path("dnsEvidence").path("observations").get(0).path("kind").asText());
        assertEquals(0, result.path("dnsEvidence").path("observations").get(0).path("publicIpv6Count").asInt(-1));
        assertFalse(result.path("dnsEvidence").path("failureCauseEstablished").asBoolean(true));
        assertEquals("12", result.path("cooldownEvidence").path("observations").get(0).path("retryAfter").path("seconds").textValue());
        assertTrue(result.path("missingMaterials").toString().contains("matched_loader_receipt"));
        assertFalse(result.path("routes").toString().contains("\"network\""));
        assertEquals(0, result.path("newNetworkRequests").asInt());
        assertEquals(0, result.path("newModelCalls").asInt());
        assertFalse(result.toString().contains("PRIVATE"));
        assertEquals(before, report.toString());
    }

    @Test
    void interruptedRunIdentityMustMatchAndCompletedLoaderEvidenceIsNotOverridden() {
        var report = interruptedReport();
        var run = interruptedRun(report);
        for (String field : List.of("artifactSha256", "profileSha256", "runtimeSha256", "executionPolicySha256")) {
            String original = report.path(field).asText();
            report.put(field, "d".repeat(64));
            assertFalse(diagnostics.forRun(run).has("hostFailureDiagnosticMatched"), field);
            report.put(field, original);
        }
        var diagnostic = (ObjectNode) report.path("managedExecutionDiagnostic");
        diagnostic.put("authority", "PRIVATE");
        assertFalse(diagnostics.forRun(run).has("hostFailureDiagnosticMatched"));
        diagnostic.put("authority", "managed_source_broker_observed");
        when(run.executionMode()).thenReturn("static");
        assertFalse(diagnostics.forRun(run).has("hostFailureDiagnosticMatched"));
        when(run.executionMode()).thenReturn("managed_source");
        report.set("managedSourceEvidence", evidence(sha, "C01", "HOME_CATEGORIES_INVALID"));
        assertFalse(diagnostics.forRun(run).has("hostFailureDiagnosticMatched"));
        assertTrue(diagnostics.forRun(run).path("loaderReceiptMatched").asBoolean());
    }

    @Test
    void interruptedBaselineAndCandidateUseSameDetailsButLatestCandidateKeepsPrecedence() {
        var original = repair(evidence(sha, "R01", "PLAYER_URL_EMPTY"));
        var baseline = ((ObjectNode) original.frozen()).withObject("baseline");
        baseline.set("result", interruptedReport());
        baseline.put("errorCode", "EGRESS_DNS_NO_ADDRESS");
        var first = diagnostics.forRepair(original, List.of());
        assertTrue(first.path("hostFailureDiagnosticMatched").asBoolean());
        assertTrue(first.has("dnsEvidence"));
        var latest = candidate(sha, 1, evidence(sha, "R01", "PLAYER_URL_EMPTY"));
        assertFalse(diagnostics.forRepair(original, List.of(latest)).has("dnsEvidence"));
        var result = (ObjectNode) latest.result();
        result.remove("runtimeEvidence");
        var failure = result.putObject("managedExecutionFailure").put("candidateId", latest.id())
                .put("jobId", latest.id()).put("inputSourceSha256", sha).put("inputProfileSha256", profile)
                .put("bundleSha256", runtime).put("executionPolicySha256", "f".repeat(64)).put("code", "EGRESS_URL_DENIED");
        failure.putObject("diagnostic").put("authority", "managed_source_broker_observed")
                .set("exchangeContext", interruptedContext(mapper));
        var after = diagnostics.forRepair(original, List.of(latest));
        assertEquals(latest.id(), after.path("candidateId").asText());
        assertEquals("request_policy", after.path("findings").get(0).path("route").asText());
        assertTrue(after.has("dnsEvidence"));
        assertTrue(after.has("transportEvidence"));
        assertTrue(after.has("cooldownEvidence"));
        failure.put("jobId", "other");
        var rejected = diagnostics.forRepair(original, List.of(latest));
        assertTrue(rejected.path("latestCandidateEvidenceUnavailable").asBoolean());
        assertFalse(rejected.has("dnsEvidence"));
    }

    @Test
    void dnsDetailsRejectUnknownFieldsAndPreserveUnknownStatusInsteadOfInventingZero() {
        var report = interruptedReport();
        var run = interruptedRun(report);
        var dns = (ObjectNode) report.at("/managedExecutionDiagnostic/exchangeContext/dnsObservation");
        dns.putNull("dnsStatus").put("kind", "transport_error");
        assertTrue(diagnostics.forRun(run).path("dnsEvidence").path("observations").get(0).path("dnsStatus").isNull());
        var original = dns.deepCopy();
        for (String field : List.of("dnsStatus", "kind", "answerCount", "publicIpv4Count")) {
            dns.put(field, "PRIVATE");
            assertFalse(diagnostics.forRun(run).has("dnsEvidence"), field);
            dns.setAll(original);
        }
        dns.put("publicIpv6Count", 17);
        assertFalse(diagnostics.forRun(run).path("dnsEvidence").path("observations").get(0).has("publicIpv6Count"));
    }

    @Test
    void deniedAccessDoesNotInventLoginRequirementsAndIncompleteResponsesDoNotCount() {
        for (int status : List.of(401, 403, 444, 429)) {
            var evidence = evidence(sha, "C01", "LOADER_METHOD_FAILED");
            var responses = evidence.putArray("httpObservations");
            responses.addObject().put("responseComplete", true).put("responseStatus", status);
            responses.addObject().put("responseComplete", false).put("responseStatus", 401);
            var result = diagnostics.forRepair(repair(evidence), List.of());
            assertEquals(1, result.path("http").path("completedResponses").asInt());
            assertEquals(status == 401, result.path("routes").toString().contains("\"credentials\""));
            assertEquals(status == 403 || status == 444, result.path("routes").toString().contains("access_evidence"));
            assertEquals(status == 429, result.path("routes").toString().contains("cooldown"));
        }
    }

    static void actionResponse(ObjectMapper mapper, ObjectNode evidence, String source, String method,
                               int callId, int status) {
        var sample = mapper.createObjectNode();
        var resource = AccessPageClassifierTest.attach(mapper, sample, source,
                "<html><body>Response " + method + " " + callId + " " + status + "</body></html>");
        var event = (ObjectNode) sample.path("httpObservations").get(0);
        var row = (ObjectNode) resource.path("resources").get(0);
        String url = Hashes.sha256(method + ":" + callId + ":" + status);
        event.put("responseStatus", status).put("requestUrlSha256", url);
        row.put("responseStatus", status).put("urlSha256", url);
        var call = mapper.createObjectNode().put("id", callId).put("method", method)
                .put("inputSha256", Hashes.sha256(method + ":" + callId));
        row.set("observedDuringCall", call.deepCopy());
        var calls = evidence.withObject("loaderValidation").withArray("calls");
        if (calls.valueStream().noneMatch(existing -> existing.path("id").equals(call.path("id")))) calls.add(call);
        evidence.withArray("httpObservations").add(event);
        if (!evidence.has("resourceEvidence")) evidence.set("resourceEvidence", resource);
        else {
            var target = evidence.withObject("resourceEvidence");
            target.withArray("resources").add(row);
            target.put("textBytes", target.path("textBytes").asInt() + resource.path("textBytes").asInt());
        }
    }

    @Test
    void redirectsDoNotHideTerminalDenialRateLimitOrUpstreamFailure() {
        for (int status : List.of(401, 403, 444, 429, 503, 200, 304)) {
            var observed = evidence(sha, "C01", "HOME_CATEGORIES_INVALID");
            var responses = observed.putArray("httpObservations");
            for (int redirect : List.of(301, 302, 303, 307, 308))
                responses.addObject().put("responseComplete", true).put("responseStatus", redirect);
            responses.addObject().put("responseComplete", true).put("responseStatus", status);
            String before = observed.toString();
            var report = diagnostics.forRepair(repair(observed), List.of());
            assertEquals(6, report.path("http").path("completedResponses").asInt());
            assertEquals(6, report.path("http").path("statusCounts").size());
            assertEquals(mapper.createObjectNode().put("" + status, 1), report.path("http").path("terminalStatusCounts"));
            String route = status == 401 ? "credentials" : status == 429 ? "cooldown"
                    : status == 503 ? "network" : status == 403 || status == 444 ? "access_evidence" : "";
            if (!route.isEmpty()) assertTrue(report.path("routes").toString().contains(route));
            else assertEquals(mapper.createArrayNode().add("source_contract"), report.path("routes"));
            assertEquals("source_contract", report.path("findings").get(0).path("route").asText());
            assertFalse(report.has("accessPage"));
            assertFalse(report.has("authenticationEvidence"));
            assertFalse(report.path("acceptanceChanged").asBoolean());
            assertEquals(before, observed.toString());
        }
        var mixed = evidence(sha, "C01", "HOME_CATEGORIES_INVALID");
        var responses = mixed.putArray("httpObservations");
        responses.addObject().put("responseComplete", true).put("responseStatus", 301);
        assertEquals(mapper.createArrayNode().add("source_contract"), diagnostics.forRepair(repair(mixed), List.of()).path("routes"));
        responses.addObject().put("responseComplete", true).put("responseStatus", 403);
        responses.addObject().put("responseComplete", true).put("responseStatus", 200);
        assertEquals(mapper.createArrayNode().add("source_contract"), diagnostics.forRepair(repair(mixed), List.of()).path("routes"));
    }

    static void transportObservation(ObjectNode observed) {
        observed.withArray("networkObservations").addObject().put("requestNumber", 2).put("addressIndex", 1)
                .put("stage", "tls_handshake").put("kind", "timeout").putNull("errno")
                .put("elapsedMilliseconds", 10111).put("requestUrlSha256", "e".repeat(64))
                .put("url", "PRIVATE").put("error", "PRIVATE");
        observed.put("networkObservationsDropped", 3);
    }

    @Test
    void runtimeStagesUseExactCallIdentityAndKeepCooldownAsAnExecutionSnapshot() {
        var observed = evidence(sha, "C03", "EGRESS_HTTP_FAILED");
        var call = ((ObjectNode) observed.path("loaderValidation")).putArray("calls").addObject()
                .put("id", 3).put("method", "category").put("inputSha256", "d".repeat(64));
        var association = mapper.createObjectNode().put("id", 3).put("method", "category")
                .put("inputSha256", "d".repeat(64));
        transportObservation(observed);
        ((ObjectNode) observed.path("networkObservations").get(0)).set("observedDuringCall", association);
        var dns = observed.putArray("dnsObservations").addObject().put("dnsStatus", 0).put("answerCount", 1)
                .put("publicIpv4Count", 0).put("publicIpv6Count", 1).put("kind", "success")
                .put("recordType", "AAAA").put("resolver", "cloudflare").put("fallbackFrom", "alidns")
                .put("elapsedMilliseconds", 75).put("cacheHit", false);
        dns.set("observedDuringCall", association);
        observed.put("dnsObservationsDropped", 0);
        var rate = observed.putArray("rateLimitObservations").addObject().put("responseStatus", 429)
                .put("automaticRetry", false).put("observedAtEpochMilliseconds", 1795000000000L);
        rate.putObject("retryAfter").put("kind", "delay").put("seconds", 120);
        rate.putObject("cooldownContext").put("phase", "receipt").put("recordedAtEpochMilliseconds", 1795000002000L)
                .put("remainingWaitMilliseconds", 118000).put("taskBudgetRemainingMilliseconds", 5000)
                .put("fitsTaskBudget", false);
        rate.set("observedDuringCall", association);
        String before = observed.toString();
        var report = diagnostics.forRepair(repair(observed), List.of());
        assertEquals(association, report.at("/transportEvidence/observations/0/observedDuringCall"));
        assertEquals("host_call_interval", report.at("/transportEvidence/actionAssociation").asText());
        assertEquals(75, report.at("/dnsEvidence/observations/0/elapsedMilliseconds").asInt());
        assertEquals(association, report.at("/dnsEvidence/observations/0/observedDuringCall"));
        assertEquals("118000", report.at("/cooldownEvidence/observations/0/cooldownContext/remainingWaitMilliseconds").asText());
        assertFalse(report.at("/cooldownEvidence/currentRemainingWaitKnown").asBoolean(true));
        assertFalse(report.at("/acceptanceChanged").asBoolean(true));
        assertEquals(before, observed.toString());
        call.put("inputSha256", "e".repeat(64));
        report = diagnostics.forRepair(repair(observed), List.of());
        for (String field : List.of("transportEvidence", "dnsEvidence", "cooldownEvidence"))
            assertFalse(report.path(field).path("observations").get(0).has("observedDuringCall"));
        dns.put("cacheHit", true);
        assertFalse(diagnostics.forRepair(repair(observed), List.of())
                .at("/dnsEvidence/observations/0").has("elapsedMilliseconds"));
    }

    @Test
    void transportDetailsAreBoundedWhitelistedAndNotCurrentAvailabilityOrActionProof() {
        var observed = evidence(sha, "R01", "EGRESS_HTTP_FAILED");
        transportObservation(observed);
        String before = observed.toString();
        var report = diagnostics.forRepair(repair(observed), List.of());
        var transport = report.path("transportEvidence");
        var row = transport.path("observations").get(0);
        assertEquals(2, row.path("requestNumber").asInt());
        assertEquals(1, row.path("addressIndex").asInt());
        assertEquals("tls_handshake", row.path("stage").asText());
        assertEquals("timeout", row.path("kind").asText());
        assertEquals(10111, row.path("elapsedMilliseconds").asInt());
        assertTrue(row.path("errno").isNull());
        assertEquals(3, transport.path("observationsDropped").asInt());
        assertEquals("not_established", transport.path("actionAssociation").asText());
        assertFalse(transport.path("currentAvailabilityEstablished").asBoolean(true));
        assertFalse(transport.path("failureCauseEstablished").asBoolean(true));
        assertFalse(row.has("requestUrlSha256"));
        assertFalse(report.toString().contains("PRIVATE"));
        assertEquals(before, observed.toString());
        ((ObjectNode) observed.path("loaderValidation")).put("inputProfileSha256", "f".repeat(64));
        assertFalse(diagnostics.forRepair(repair(observed), List.of()).has("transportEvidence"));
    }

    @Test
    void missingInvalidOrRecoveredTransportDoesNotInventNetworkFailuresOrBlockRepair() {
        var observed = evidence(sha, "C07", "DETAIL_OUTPUT_INVALID");
        assertFalse(diagnostics.forRepair(repair(observed), List.of()).has("transportEvidence"));
        transportObservation(observed);
        var row = (ObjectNode) observed.path("networkObservations").get(0);
        var original = row.deepCopy();
        for (String field : List.of("requestNumber", "addressIndex", "stage", "kind", "elapsedMilliseconds")) {
            row.put(field, "PRIVATE");
            var transport = diagnostics.forRepair(repair(observed), List.of()).path("transportEvidence");
            assertTrue(transport.path("observations").isEmpty());
            assertEquals(3, transport.path("observationsDropped").asInt());
            row.setAll(original);
        }
        observed.put("networkObservationsDropped", 0);
        actionResponse(mapper, observed, sha, "detail", 3, 200);
        var report = diagnostics.forRepair(repair(observed), List.of(candidate(sha, 1, observed)));
        assertEquals(mapper.createArrayNode().add("source_contract"), report.path("routes"));
        assertTrue(report.path("automaticEnhancementRecommended").asBoolean());
        assertFalse(report.path("acceptanceChanged").asBoolean());
        var rows = observed.withArray("networkObservations");
        for (int i = 0; i < 40; i++) rows.add(original.deepCopy());
        assertEquals(32, diagnostics.forRepair(repair(observed), List.of()).path("transportEvidence").path("observations").size());
    }

    @Test
    void retryAfterIsHistoricalDecimalStringTimingNotAnInstructionToRetry() {
        var observed = evidence(sha, "C01", "EGRESS_RATE_LIMITED");
        var rates = observed.putArray("rateLimitObservations");
        String huge = "9".repeat(128);
        for (String kind : List.of("delay", "date", "absent", "invalid")) {
            var timing = rates.addObject().put("responseStatus", 429).put("automaticRetry", false)
                    .put("rawHeader", "PRIVATE").putObject("retryAfter").put("kind", kind);
            if (kind.equals("delay")) timing.put("seconds", new java.math.BigInteger(huge));
            else if (kind.equals("date")) timing.put("seconds", 0);
            else timing.putNull("seconds");
        }
        String before = observed.toString();
        var report = diagnostics.forRepair(repair(observed), List.of());
        var cooldown = report.path("cooldownEvidence");
        assertEquals("at_response", cooldown.path("timing").asText());
        assertFalse(cooldown.path("currentRemainingWaitKnown").asBoolean(true));
        assertFalse(cooldown.path("automaticRetry").asBoolean(true));
        assertEquals(4, cooldown.path("observations").size());
        assertEquals(huge, cooldown.path("observations").get(0).path("retryAfter").path("seconds").textValue());
        assertEquals("0", cooldown.path("observations").get(1).path("retryAfter").path("seconds").textValue());
        assertTrue(cooldown.path("observations").get(2).path("retryAfter").path("seconds").isNull());
        assertFalse(report.toString().contains("PRIVATE"));
        assertEquals(0, report.path("newNetworkRequests").asInt());
        assertEquals(before, observed.toString());
        ((ObjectNode) observed.path("loaderValidation")).put("inputSourceSha256", "f".repeat(64));
        assertFalse(diagnostics.forRepair(repair(observed), List.of()).has("cooldownEvidence"));
    }

    @Test
    void malformedRetryTimingIsNotForwardedAndItsAbsenceIsNotZero() {
        var observed = evidence(sha, "C01", "HOME_CATEGORIES_INVALID");
        assertFalse(diagnostics.forRepair(repair(observed), List.of()).has("cooldownEvidence"));
        var rates = observed.putArray("rateLimitObservations");
        var rate = rates.addObject().put("responseStatus", 429).put("automaticRetry", false);
        var timing = rate.putObject("retryAfter").put("kind", "delay");
        for (var invalid : List.of(mapper.getNodeFactory().numberNode(-1), mapper.getNodeFactory().numberNode(1.5),
                mapper.getNodeFactory().textNode("PRIVATE"), mapper.nullNode(),
                mapper.getNodeFactory().numberNode(new java.math.BigInteger("9".repeat(129))))) {
            timing.set("seconds", invalid);
            assertFalse(diagnostics.forRepair(repair(observed), List.of()).has("cooldownEvidence"));
        }
        timing.put("seconds", 5);
        rate.put("responseStatus", 429L + (1L << 32));
        assertFalse(diagnostics.forRepair(repair(observed), List.of()).has("cooldownEvidence"));
        rate.put("responseStatus", 429).put("automaticRetry", true);
        assertFalse(diagnostics.forRepair(repair(observed), List.of()).has("cooldownEvidence"));
        rate.put("automaticRetry", false);
        for (int i = 0; i < 70; i++) rates.add(rate.deepCopy());
        assertEquals(64, diagnostics.forRepair(repair(observed), List.of()).path("cooldownEvidence").path("observations").size());
    }

    @Test
    void emptyHttpEventsAssociateWithoutResourceSamplesOrInventedChallenges() {
        for (int status : List.of(401, 403, 444, 429, 503)) {
            var observed = evidence(sha, "C03", "CONTENT_LIST_EMPTY_OR_INVALID");
            actionResponse(mapper, observed, sha, "home", 2, 200);
            actionResponse(mapper, observed, sha, "category", 3, status);
            var event = (ObjectNode) observed.path("httpObservations").get(1);
            event.set("observedDuringCall", observed.at("/resourceEvidence/resources/1/observedDuringCall").deepCopy());
            event.put("responseBytes", 0).put("responseContentType", "");
            observed.withArray("httpObservations").add(event.deepCopy());
            observed.remove("resourceEvidence");
            String before = observed.toString();
            var report = diagnostics.forRepair(repair(observed), List.of());
            var finding = report.path("findings").get(0);
            assertEquals(status == 401 ? "credentials" : status == 429 ? "cooldown"
                    : status >= 500 ? "network" : "access_evidence", finding.path("route").asText());
            var http = finding.path("httpEvidence");
            assertEquals("matched_http_call_interval", http.path("basis").asText());
            assertEquals(1, http.path("calls").size());
            assertEquals(3, http.at("/calls/0/id").asInt());
            assertEquals(2, http.at("/calls/0/statusCounts/" + status).asInt());
            assertFalse(http.path("completeCallCoverage").asBoolean());
            assertFalse(http.path("failureCauseEstablished").asBoolean());
            assertFalse(report.has("accessPage"));
            assertFalse(report.has("authenticationEvidence"));
            assertFalse(report.path("missingMaterials").toString().contains("action_http_association"));
            assertFalse(report.path("acceptanceChanged").asBoolean());
            assertEquals(before, observed.toString());
        }
    }

    @Test
    void httpIntervalsCountEventsOnceAndPreservePaginationAndHandledFallbacks() {
        var observed = evidence(sha, "C03", "CONTENT_LIST_EMPTY_OR_INVALID");
        actionResponse(mapper, observed, sha, "category", 3, 200);
        actionResponse(mapper, observed, sha, "category", 4, 503);
        var response = (ObjectNode) observed.path("httpObservations").get(1);
        response.set("observedDuringCall", observed.at("/resourceEvidence/resources/1/observedDuringCall").deepCopy());
        var report = diagnostics.forRepair(repair(observed), List.of());
        var http = report.at("/findings/0/httpEvidence");
        assertEquals("matched_http_and_resource_call_intervals", http.path("basis").asText());
        assertEquals("network", report.at("/findings/0/route").asText());
        assertEquals(2, http.path("calls").size());
        assertEquals(1, http.at("/calls/1/statusCounts/503").asInt());
        observed.withArray("httpObservations").add(response.deepCopy());
        assertEquals(2, diagnostics.forRepair(repair(observed), List.of())
                .at("/findings/0/httpEvidence/calls/1/statusCounts/503").asInt());
        actionResponse(mapper, observed, sha, "category", 4, 200);
        var handled = diagnostics.forRepair(repair(observed), List.of());
        assertEquals("source_contract", handled.at("/findings/0/route").asText());
        assertTrue(handled.at("/findings/0/httpEvidence/calls/1/successfulResponseObserved").asBoolean());
        assertFalse(handled.at("/findings/0/httpEvidence").has("unavailableRoute"));
    }

    @Test
    void httpIntervalsRejectMismatchedCallsAndKeepOldMissingAssociationExplicit() {
        for (String defect : List.of("id", "method", "inputSha256", "extra", "missing", "incomplete", "redirect")) {
            var observed = evidence(sha, "C03", "CONTENT_LIST_EMPTY_OR_INVALID");
            actionResponse(mapper, observed, sha, "category", 3, 444);
            var response = (ObjectNode) observed.path("httpObservations").get(0);
            var association = observed.at("/resourceEvidence/resources/0/observedDuringCall").deepCopy();
            response.set("observedDuringCall", association);
            observed.remove("resourceEvidence");
            switch (defect) {
                case "id" -> ((ObjectNode) association).put("id", 4);
                case "method" -> ((ObjectNode) association).put("method", "detail");
                case "inputSha256" -> ((ObjectNode) association).put("inputSha256", "e".repeat(64));
                case "extra" -> ((ObjectNode) association).put("raw", "PRIVATE");
                case "missing" -> response.remove("observedDuringCall");
                case "incomplete" -> response.put("responseComplete", false);
                case "redirect" -> response.put("responseStatus", 302);
            }
            var report = diagnostics.forRepair(repair(observed), List.of());
            assertEquals("source_contract", report.at("/findings/0/route").asText(), defect);
            assertFalse(report.path("findings").get(0).has("httpEvidence"), defect);
            if (!List.of("incomplete", "redirect").contains(defect))
                assertTrue(report.path("missingMaterials").toString().contains("action_http_association"), defect);
            assertFalse(report.toString().contains("PRIVATE"));
        }
    }

    @Test
    void mixedHttpFailuresAreAssociatedWithTheFailedActionWithoutInventingAChallenge() {
        for (int status : List.of(401, 403, 444, 429, 500, 502, 503, 504)) {
            var observed = evidence(sha, "C07", "DETAIL_OUTPUT_INVALID");
            actionResponse(mapper, observed, sha, "home", 2, 200);
            actionResponse(mapper, observed, sha, "detail", 3, status);
            String before = observed.toString();
            var report = diagnostics.forRepair(repair(observed), List.of(candidate(sha, 1, observed)));
            var finding = report.path("findings").get(0);
            String route = status == 401 ? "credentials" : status == 429 ? "cooldown"
                    : status >= 500 ? "network" : "access_evidence";
            assertEquals(route, finding.path("route").asText(), "HTTP " + status);
            var http = finding.path("httpEvidence");
            assertEquals("matched_resource_call_interval", http.path("basis").asText());
            assertEquals(1, http.path("calls").size());
            assertEquals(3, http.path("calls").get(0).path("id").asInt());
            assertEquals(1, http.path("calls").get(0).path("statusCounts").path("" + status).asInt());
            assertFalse(http.path("completeCallCoverage").asBoolean());
            assertFalse(http.path("failureCauseEstablished").asBoolean());
            assertFalse(finding.has("accessClassification"));
            assertFalse(report.has("authenticationEvidence"));
            assertFalse(report.path("automaticEnhancementRecommended").asBoolean());
            assertFalse(report.path("acceptanceChanged").asBoolean());
            assertEquals(before, observed.toString());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "maintenance.action-http-replay", matches = ".+")
    void storedMixedHttpReceiptRequiresActionEvidenceInsteadOfInventingAFailedRequest() throws Exception {
        var snapshot = mapper.readTree(Files.readAllBytes(Path.of(System.getProperty("maintenance.action-http-replay"))));
        var run = mapper.copy().findAndRegisterModules().treeToValue(snapshot.path("run"), ProbeSummary.class);
        String before = snapshot.toString();
        var report = diagnostics.forRun(run);
        assertTrue(report.path("loaderReceiptMatched").asBoolean());
        assertEquals(mapper.readTree("{\"200\":4,\"403\":3,\"404\":4}"), report.path("http").path("statusCounts"));
        assertEquals("source_contract", report.path("findings").get(0).path("route").asText());
        assertFalse(report.path("findings").get(0).has("httpEvidence"));
        assertTrue(report.path("missingMaterials").toString().contains("action_http_association"));
        assertFalse(report.path("acceptanceChanged").asBoolean());
        assertEquals(before, snapshot.toString());
    }

    @Test
    void aLaterFailedPageIsNotHiddenByAnEarlierSuccessfulCallOfTheSameMethod() {
        var observed = evidence(sha, "C03", "CONTENT_LIST_EMPTY_OR_INVALID");
        actionResponse(mapper, observed, sha, "category", 3, 200);
        actionResponse(mapper, observed, sha, "category", 4, 503);
        var report = diagnostics.forRepair(repair(observed), List.of(candidate(sha, 1, observed)));
        var finding = report.path("findings").get(0);
        assertEquals("network", finding.path("route").asText());
        assertEquals(2, finding.path("httpEvidence").path("calls").size());
        assertTrue(finding.path("httpEvidence").path("calls").get(0).path("successfulResponseObserved").asBoolean());
        assertFalse(report.path("automaticEnhancementRecommended").asBoolean());
    }

    @Test
    void handledHttpErrorsAndOtherActionsDoNotBlockAnIndependentSourceRepair() {
        for (boolean sameCall : List.of(false, true)) {
            var observed = evidence(sha, "C07", "DETAIL_OUTPUT_INVALID");
            actionResponse(mapper, observed, sha, sameCall ? "detail" : "category", 3, 503);
            actionResponse(mapper, observed, sha, "detail", sameCall ? 3 : 4, 200);
            var report = diagnostics.forRepair(repair(observed), List.of(candidate(sha, 1, observed)));
            assertEquals("source_contract", report.path("findings").get(0).path("route").asText());
            assertTrue(report.path("automaticEnhancementRecommended").asBoolean());
            assertFalse(report.path("findings").get(0).path("httpEvidence").has("unavailableRoute"));
        }
    }

    @Test
    void unboundIncompleteAndUnassociatedHttpErrorsAreNotAttributedToSourceActions() {
        for (String defect : List.of("call", "hash", "incomplete", "unassociated")) {
            var observed = evidence(sha, "R01", "PLAYER_URL_EMPTY");
            actionResponse(mapper, observed, sha, "home", 2, 200);
            actionResponse(mapper, observed, sha, "play", 3, 403);
            var row = (ObjectNode) observed.path("resourceEvidence").path("resources").get(1);
            switch (defect) {
                case "call" -> ((ObjectNode) row.path("observedDuringCall")).put("id", 32);
                case "hash" -> row.put("responseSha256", "e".repeat(64));
                case "incomplete" -> ((ObjectNode) observed.path("httpObservations").get(1)).put("responseComplete", false);
                case "unassociated" -> row.remove("observedDuringCall");
            }
            var report = diagnostics.forRepair(repair(observed), List.of(candidate(sha, 1, observed)));
            var finding = report.path("findings").get(0);
            assertEquals("source_contract", finding.path("route").asText(), defect);
            assertFalse(finding.has("httpEvidence"), defect);
            if (!defect.equals("incomplete")) assertTrue(report.path("missingMaterials").toString()
                    .contains("action_http_association"), defect);
        }
    }

    @Test
    void unknownRawReasonsAndTypesAreNotForwardedAndBudgetIsNotRaised() {
        var evidence = evidence(sha, "C03", "Cookie=SECRET");
        ((ObjectNode) evidence.path("loaderValidation").path("nodes").get(0)).put("failureType", "Token=SECRET");
        evidence.withArray("deniedCodes").add("EGRESS_REQUEST_BUDGET").add("Authorization=SECRET");
        var result = diagnostics.forRepair(repair(evidence), List.of());
        assertTrue(result.path("routes").toString().contains("request_budget"));
        assertEquals("runtime_evidence", result.path("findings").get(0).path("route").asText());
        assertFalse(result.toString().contains("SECRET"));
        assertFalse(result.path("bypassImplemented").asBoolean());
    }

    @Test
    void runWithoutReceiptOrWithStaleInputsReportsMissingMaterials() {
        var run = mock(ProbeSummary.class);
        when(run.id()).thenReturn("run");
        when(run.artifactSha256()).thenReturn(sha);
        when(run.freshness()).thenReturn("stale");
        var result = diagnostics.forRun(run);
        assertFalse(result.path("loaderReceiptMatched").asBoolean());
        assertTrue(result.path("missingMaterials").toString().contains("current_input_receipt"));
        assertTrue(result.path("missingMaterials").toString().contains("matched_loader_receipt"));
    }
}
