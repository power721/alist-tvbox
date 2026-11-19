package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.RepairCandidateSummary;
import cn.har01d.atmaintenance.dto.RepairSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepairEvidenceMaterialTest {
    private final ObjectMapper mapper = new ObjectMapper();

    static ObjectNode runtime(ObjectMapper mapper, RepairSummary repair, String sha) throws Exception {
        var value = mapper.createObjectNode().put("authority", "managed_source_host_observed")
                .put("inputSourceSha256", sha).put("jobId", "fixture-job")
                .put("bundleSha256", RepairReport.runtimeInputs(repair.frozen()).path("runtimeSha256").asText());
        value.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        value.putArray("deniedCodes");
        var loader = value.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                .put("inputSourceSha256", sha).put("inputProfileSha256",
                        repair.frozen().path("baseline").path("profileSha256").asText());
        loader.set("calls", ManagedResourceEvidenceTest.jsonCalls(mapper));
        loader.putArray("nodes").addObject().put("id", "R01").put("status", "blocked").put("reason", "PLAYER_URL_EMPTY");
        value.set("httpObservations", ManagedResourceEvidenceTest.jsonEvents(mapper));
        var resources = ManagedResourceEvidenceTest.jsonFixture(mapper, sha);
        var structure = (ObjectNode) resources.path("resources").get(0).path("jsonStructure");
        var nodes = structure.putArray("nodes");
        nodes.addObject().put("type", "object").put("size", 1).putArray("path");
        nodes.addObject().put("type", "object").put("size", 1).putArray("path").add("streams");
        nodes.addObject().put("type", "string").put("shape", "url_like").put("byteLength", 222)
                .putArray("path").add("streams").add("main");
        resources.put("textBytes", mapper.writeValueAsBytes(structure).length);
        value.set("resourceEvidence", resources);
        return value;
    }

    static ObjectNode result(ObjectMapper mapper, RepairSummary repair, String sha) throws Exception {
        var result = mapper.createObjectNode().put("artifactSha256", sha)
                .put("baselineArtifactSha256", repair.baselineArtifactSha256()).put("runtimeJobId", "fixture-job")
                .put("runtimeRequested", true).put("staticValidationPassed", true);
        result.set("runtimeEvidence", runtime(mapper, repair, sha));
        return result;
    }

    private RepairSummary repair() {
        var frozen = mapper.createObjectNode();
        frozen.putObject("policy").put("scope", "managed_loader_candidate").putArray("targetNodes").add("R01");
        var baseline = frozen.putObject("baseline").put("profileSha256", "b".repeat(64)).put("runtimeSha256", "c".repeat(64));
        baseline.putObject("profile").putArray("requiredNodes").add("R01");
        return new RepairSummary("repair", "source", "run", "a".repeat(64), "f".repeat(64),
                "STOPPED", "REPEATED_FAILURE", 3, 3, null, Instant.now(), frozen, "current", List.of());
    }

    private RepairCandidateSummary candidate(RepairSummary repair, ObjectNode result) {
        return new RepairCandidateSummary("candidate", repair.id(), 3, "d".repeat(64), "e".repeat(64),
                "REJECTED", "RUNTIME_TARGET_UNRESOLVED", Instant.now(), Instant.now(), null, result, "call", null, null);
    }

    static ObjectNode playerContract(ObjectMapper mapper) {
        return mapper.createObjectNode().put("detailCallId", 2).put("playCallId", 3)
                .put("selectedIdentifierShape", "opaque").put("returnedUrlShape", "opaque")
                .put("returnedUrlType", "string").put("returnedUrlBytes", 30)
                .put("returnedUrlMatchesIdentifier", true).put("parseMode", "direct")
                .put("hasWhitespace", false).put("hasFragment", false).put("hasUserInfo", false).put("hasHostname", false);
    }

    @Test void boundDownstreamPlayerFailureCanOfferEvidenceAfterEarlierTargetRecovered() throws Exception {
        var repair = repair();
        ((ObjectNode) repair.frozen().path("policy")).putArray("targetNodes").add("C10");
        var result = result(mapper, repair, "d".repeat(64));
        result.put("repairSuccessful", false).putObject("runtimeAcceptance").put("status", "required_incomplete");
        var runtime = result.withObject("runtimeEvidence");
        runtime.remove("resourceEvidence");
        var loader = runtime.withObject("loaderValidation");
        loader.putArray("calls").addObject().put("id", 2).put("method", "detail").put("ok", true);
        loader.withArray("calls").addObject().put("id", 3).put("method", "play").put("ok", true);
        var node = (ObjectNode) loader.path("nodes").get(0);
        node.set("playerContract", playerContract(mapper));
        var progressed = new RepairCandidateSummary("candidate", repair.id(), 3, "d".repeat(64), "e".repeat(64),
                "RUNTIME_OBSERVED", null, Instant.now(), Instant.now(), null, result, "call", null, null);
        var material = RepairEvidenceMaterial.current(mapper, repair, List.of(progressed));
        assertTrue(material.path("available").asBoolean(), material::toString);
        assertEquals("observed_player_output_contract", material.path("materialKind").asText());
        assertEquals("R01", material.path("targetNode").asText());
        assertEquals("C10", repair.frozen().path("policy").path("targetNodes").get(0).asText());
        ((ObjectNode) node.path("playerContract")).put("playCallId", 7);
        assertFalse(RepairEvidenceMaterial.current(mapper, repair, List.of(progressed)).path("available").asBoolean());
    }

    @Test void playerSemanticsIgnoreRotatingIdsAndSizesButNoticeChangedShape() {
        var node = mapper.createObjectNode().put("id", "R01");
        var contract = playerContract(mapper);
        node.set("playerContract", contract);
        var nodes = mapper.createArrayNode().add(node);
        var before = RepairEvidenceMaterial.playerSignatures(mapper, nodes);
        assertEquals(1, before.size());
        contract.put("detailCallId", 8).put("playCallId", 9).put("returnedUrlBytes", 900);
        assertEquals(before, RepairEvidenceMaterial.playerSignatures(mapper, nodes));
        contract.put("returnedUrlShape", "relative_path");
        assertNotEquals(before, RepairEvidenceMaterial.playerSignatures(mapper, nodes));
        node.set("contractObservations", mapper.createObjectNode().set("playerContract", contract));
        assertEquals(RepairEvidenceMaterial.playerSignatures(mapper, nodes),
                RepairEvidenceMaterial.playerSignatures(mapper, mapper.createArrayNode().add(node)));
    }

    static ObjectNode originReceipt(ObjectMapper mapper, RepairSummary repair) {
        var report = mapper.createObjectNode().put("mode", "reverse_analysis_v1")
                .put("authority", "host_source_origin_observed").put("operation", "source_origin_inspection")
                .put("repairId", repair.id()).put("runId", repair.baselineRunId())
                .put("artifactSha256", repair.baselineArtifactSha256()).put("cleanupConfirmed", true)
                .put("pageUrlSha256", "1".repeat(64));
        var pool = report.putObject("originPool").put("authority", "maintenance_pinned_http_origin_observed")
                .put("credentialsForwarded", false).put("requestedPageUrlSha256", "1".repeat(64))
                .put("preferredCandidateOrigin", "https://alternate.example.com");
        pool.putArray("candidates").addObject().put("origin", "https://alternate.example.com")
                .put("basis", "source_published_same_path_redirect");
        pool.putArray("observations").addObject().put("origin", "https://alternate.example.com")
                .put("status", "business_content_candidate").put("httpStatus", 200)
                .put("responseComplete", true).put("sameActionPath", true)
                .put("responseContentType", "text/html").put("responseBytes", 2000)
                .put("requestUrlSha256", "2".repeat(64)).put("responseSha256", "3".repeat(64))
                .putArray("matchedMarkerIndexes").add(0);
        return report;
    }

    @Test void observedAlternateOriginOffersOneRouteWithoutClaimingBusinessRepair() throws Exception {
        var repair = repair();
        var report = originReceipt(mapper, repair);
        ((ObjectNode) repair.frozen()).set("reverseAnalysis", report);
        var result = result(mapper, repair, "d".repeat(64));
        ((ObjectNode) result.path("runtimeEvidence")).remove("resourceEvidence");
        var material = RepairEvidenceMaterial.current(mapper, repair, List.of(candidate(repair, result)));
        assertTrue(material.path("available").asBoolean());
        assertEquals("verified_alternate_origin_route", material.path("materialKind").asText());
        assertEquals("https://alternate.example.com", material.path("verifiedAlternateOriginRoute")
                .path("candidateOrigin").asText());
        assertFalse(material.path("verifiedAlternateOriginRoute").path("businessChainVerified").asBoolean());
        assertFalse(material.path("verifiedAlternateOriginRoute").path("failureCauseEstablished").asBoolean());
        result.remove("runtimeJobId");
        ((ObjectNode) result.path("runtimeEvidence")).put("jobId", "candidate");
        assertTrue(RepairEvidenceMaterial.current(mapper, repair, List.of(candidate(repair, result)))
                .path("available").asBoolean());
        var original = RepairEvidenceMaterial.originSignatures(mapper, repair, report);
        var row = (ObjectNode) report.path("originPool").path("observations").get(0);
        row.put("responseSha256", "4".repeat(64)).put("responseBytes", 3000);
        assertEquals(original, RepairEvidenceMaterial.originSignatures(mapper, repair, report));
        var latest = mapper.createObjectNode();
        latest.putArray("previousReports").add(report);
        assertEquals(original, RepairEvidenceMaterial.originSignatures(mapper, repair, latest));
    }

    @Test void unboundIncompleteChallengeOrCredentialBearingOriginsAreNotContinuationMaterial() {
        for (String defect : List.of("repair", "run", "artifact", "cleanup", "authority", "credentials",
                "page", "basis", "challenge", "incomplete", "path", "marker", "type")) {
            var repair = repair();
            var report = originReceipt(mapper, repair);
            var pool = (ObjectNode) report.path("originPool");
            var row = (ObjectNode) pool.path("observations").get(0);
            switch (defect) {
                case "repair" -> report.put("repairId", "other");
                case "run" -> report.put("runId", "other");
                case "artifact" -> report.put("artifactSha256", "0".repeat(64));
                case "cleanup" -> report.put("cleanupConfirmed", false);
                case "authority" -> report.put("authority", "source_claimed");
                case "credentials" -> pool.put("credentialsForwarded", true);
                case "page" -> pool.put("requestedPageUrlSha256", "0".repeat(64));
                case "basis" -> ((ObjectNode) pool.path("candidates").get(0)).put("basis", "guessed");
                case "challenge" -> row.put("status", "challenge").put("httpStatus", 403);
                case "incomplete" -> row.put("responseComplete", false);
                case "path" -> row.put("sameActionPath", false);
                case "marker" -> row.putArray("matchedMarkerIndexes");
                case "type" -> row.put("responseContentType", "application/json");
            }
            assertTrue(RepairEvidenceMaterial.originRoute(mapper, repair, report).isMissingNode(), defect);
        }
    }

    @Test @EnabledIfSystemProperty(named = "maintenance.origin-continuation-replay", matches = ".+")
    void existingOriginReceiptOffersContinuationAndFitsTheActualModelRequestWithoutCallingIt() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var receipt = json.readTree(Files.readAllBytes(Path.of(System.getProperty("maintenance.origin-continuation-replay"))));
        var repair = json.treeToValue(receipt.path("repair"), RepairSummary.class);
        var history = new ArrayList<RepairCandidateSummary>();
        for (var row : receipt.path("candidates")) history.add(json.treeToValue(row, RepairCandidateSummary.class));
        var material = RepairEvidenceMaterial.current(json, repair, history);
        assertTrue(material.path("available").asBoolean(), material::toString);
        assertEquals("verified_alternate_origin_route", material.path("materialKind").asText());
        assertEquals("C07", material.path("targetNode").asText());
        assertEquals("https://jable666.net", material.path("verifiedAlternateOriginRoute").path("candidateOrigin").asText());
        String source = json.readTree(Files.readAllBytes(Path.of(System.getProperty("maintenance.origin-source-fixture"))))
                .path("sourceContent").asText();
        assertEquals(repair.baselineArtifactSha256(), Hashes.sha256(source));
        var settings = new cn.har01d.atmaintenance.config.ModelSettings();
        settings.setEnabled(true);
        settings.setEndpoint("http://127.0.0.1:9/v1/chat/completions");
        settings.setModel("fixture-model");
        var client = new ModelClient(json, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(), settings);
        try {
            var request = client.request(repair, source, history, true);
            var data = RepairEvidenceMaterial.requestData(json, request);
            assertEquals(material.path("verifiedAlternateOriginRoute"), data.path("verifiedAlternateOriginRoute"));
            assertEquals(repair.frozen().path("policy").path("targetNodes"), data.path("validationTargets"));
            assertEquals(source, data.path("source").asText());
            assertTrue(request.contains("retain existing fallback hosts"));
            assertFalse(data.path("verifiedAlternateOriginRoute").path("businessChainVerified").asBoolean());
        } finally { client.close(); }
    }

    @Test void completeBoundStructureProducesMaterialAndHighlightsStringLeafWithoutClaimingPlayback() throws Exception {
        var repair = repair();
        var result = result(mapper, repair, "d".repeat(64));
        String original = result.toString();
        var candidates = List.of(candidate(repair, result));
        var material = RepairEvidenceMaterial.current(mapper, repair, candidates);
        assertTrue(material.path("available").asBoolean());
        assertEquals(64, material.path("evidenceSha256").asText().length());
        var diagnostics = new RepairDiagnostics(mapper).forRepair(repair, candidates);
        var json = diagnostics.path("jsonResponseEvidence");
        assertEquals(mapper.readTree("[\"streams\",\"main\"]"), json.path("addressFields").get(0).path("path"));
        assertFalse(json.path("failureCauseEstablished").asBoolean());
        assertEquals("triage_only", diagnostics.path("conclusion").asText());
        assertEquals(original, result.toString());
    }

    @Test void episodeStructureMustComeFromDetailNotSearch() throws Exception {
        var resources = runtime(mapper, repair(), "d".repeat(64)).withObject("resourceEvidence");
        resources.put("targetNode", "C10");
        var call = (ObjectNode) resources.path("resources").get(0).path("observedDuringCall");
        call.put("method", "detail");
        assertFalse(RepairEvidenceMaterial.signatures(mapper, resources).isEmpty());
        call.put("method", "search");
        assertTrue(RepairEvidenceMaterial.signatures(mapper, resources).isEmpty());
    }

    @Test @EnabledIfSystemProperty(named = "maintenance.c10-replay", matches = ".+")
    void actualRejectedEpisodeReceiptsPreservePartialDetailEvidenceWithoutGrantingRound() throws Exception {
        var receipt = mapper.readTree(Files.readAllBytes(Path.of(System.getProperty("maintenance.c10-replay"))));
        assertEquals("REPEATED_FAILURE", receipt.path("maintenance").path("stopCode").asText());
        assertEquals(2, receipt.path("candidates").size());
        for (var candidate : receipt.path("candidates")) {
            var resources = candidate.path("result").path("runtimeEvidence").path("resourceEvidence");
            assertEquals("C10", resources.path("targetNode").asText());
            assertTrue(resources.path("resources").valueStream().anyMatch(row ->
                    "api_json".equals(row.path("kind").asText())
                        && "detail".equals(row.path("observedDuringCall").path("method").asText())
                        && row.path("jsonStructure").path("truncated").asBoolean()
                        && row.path("jsonStructure").path("nodes").valueStream().anyMatch(node ->
                            "json_like".equals(node.path("shape").asText())
                                && node.path("path").toString().contains("resData"))));
            assertTrue(RepairEvidenceMaterial.signatures(mapper, resources).isEmpty());
        }
        assertEquals(0, receipt.path("publicationTasks").size());
    }

    @Test void rotatingResponseHashesLengthsAndCallIdsDoNotCreateNewMaterial() throws Exception {
        var resource = runtime(mapper, repair(), "d".repeat(64)).withObject("resourceEvidence");
        var before = RepairEvidenceMaterial.signatures(mapper, resource);
        var row = (ObjectNode) resource.path("resources").get(0);
        row.put("responseSha256", "9".repeat(64)).put("urlSha256", "8".repeat(64));
        ((ObjectNode) row.path("observedDuringCall")).put("id", 8).put("inputSha256", "9".repeat(64));
        ((ObjectNode) row.path("jsonStructure").path("nodes").get(2)).put("byteLength", 333);
        assertEquals(before, RepairEvidenceMaterial.signatures(mapper, resource));
        ((ObjectNode) row.path("jsonStructure").path("nodes").get(2)).putArray("path").add("streams").add("alternate");
        assertNotEquals(before, RepairEvidenceMaterial.signatures(mapper, resource));
    }

    @Test void embeddedStructureTracksFieldsButNotValuesAndCannotGrantFromTruncatedEvidence() throws Exception {
        var resource = runtime(mapper, repair(), "d".repeat(64)).withObject("resourceEvidence");
        var leaf = (ObjectNode) resource.path("resources").get(0).path("jsonStructure").path("nodes").get(2);
        leaf.put("shape", "json_like");
        var embedded = leaf.putObject("embeddedJson").put("truncated", false);
        var nodes = embedded.putArray("nodes");
        nodes.addObject().put("type", "object").put("size", 1).putArray("path");
        var field = nodes.addObject().put("type", "string").put("shape", "url_like").put("byteLength", 10);
        field.putArray("path").add("url");
        var before = RepairEvidenceMaterial.signatures(mapper, resource);
        assertFalse(before.isEmpty());
        field.put("byteLength", 200);
        assertEquals(before, RepairEvidenceMaterial.signatures(mapper, resource));
        field.putArray("path").add("link");
        assertNotEquals(before, RepairEvidenceMaterial.signatures(mapper, resource));
        embedded.put("truncated", true);
        assertTrue(RepairEvidenceMaterial.signatures(mapper, resource).isEmpty());
    }

    @Test void truncatedUnmatchedOrUncleanRuntimeCannotOfferContinuation() throws Exception {
        for (String defect : List.of("truncated", "body", "job", "cleanup", "call", "loader", "network")) {
            var repair = repair();
            var result = result(mapper, repair, "d".repeat(64));
            var runtime = (ObjectNode) result.path("runtimeEvidence");
            var row = (ObjectNode) runtime.path("resourceEvidence").path("resources").get(0);
            switch (defect) {
                case "truncated" -> ((ObjectNode) row.path("jsonStructure")).put("truncated", true);
                case "body" -> row.put("responseSha256", "9".repeat(64));
                case "job" -> runtime.put("jobId", "another-job");
                case "cleanup" -> ((ObjectNode) runtime.path("cleanup")).put("confirmed", false);
                case "call" -> ((ObjectNode) row.path("observedDuringCall")).put("method", "detail");
                case "loader" -> ((ObjectNode) runtime.path("loaderValidation")).put("inputSourceSha256", "0".repeat(64));
                case "network" -> runtime.withArray("deniedCodes").add("EGRESS_HTTP_FAILED");
            }
            assertFalse(RepairEvidenceMaterial.current(mapper, repair, List.of(candidate(repair, result)))
                    .path("available").asBoolean(), defect);
        }
    }

    @Test @EnabledIfSystemProperty(named = "maintenance.continuation-replay", matches = ".+")
    void existingRealPlatformReceiptCanBeTriagedWithoutNewSourceOrModelCalls() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var receipt = json.readTree(Files.readAllBytes(Path.of(System.getProperty("maintenance.continuation-replay"))));
        var repair = json.treeToValue(receipt.path("repair"), RepairSummary.class);
        var history = new ArrayList<RepairCandidateSummary>();
        for (var row : receipt.path("candidates")) history.add(json.treeToValue(row, RepairCandidateSummary.class));
        var material = RepairEvidenceMaterial.current(json, repair, history);
        assertTrue(material.path("available").asBoolean(), material::toString);
        assertEquals(history.getLast().sha256(), material.path("candidateSha256").asText());
        assertEquals("R01", material.path("targetNode").asText());
        var diagnostic = new RepairDiagnostics(json).forRepair(repair, history);
        assertEquals(2, diagnostic.path("jsonResponseEvidence").path("addressFields").size());
        assertFalse(diagnostic.path("jsonResponseEvidence").path("failureCauseEstablished").asBoolean());
    }
}
