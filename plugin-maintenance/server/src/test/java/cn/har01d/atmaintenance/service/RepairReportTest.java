package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RepairReportTest {
    @Test
    void hostConfigurationPausesWithoutMislabelingOutputOrNodeRegression() {
        var frozen = frozenLoaderProfile();
        var before = loaderEvidence(frozen);
        frozen.putObject("policy").put("scope", "managed_loader_candidate").putArray("targetNodes").add("C01");
        frozen.withObject("baseline").put("artifactSha256", "b".repeat(64))
                .putObject("result").set("managedSourceEvidence", before);
        var evidence = hostFailure(frozen);
        var report = reports.runtime(frozen, identifiedStaticReport(), evidence);
        assertTrue(report.path("runtimeValidationPaused").asBoolean(), report::toString);
        assertEquals("RUNTIME_HOST_CONFIGURATION_REQUIRED", report.path("runtimeValidationPauseReason").asText());
        assertFalse(report.path("repairSuccessful").asBoolean());
        assertTrue(report.path("observedRegressions").isEmpty(), report::toString);
        assertTrue(report.path("runtimeNodeProgress").path("regressed").isEmpty());
        assertEquals(0, report.path("runtimeAcceptance").path("requiredCoverage").path("fail").asInt());
        assertEquals(1, report.path("runtimeAcceptance").path("requiredCoverage").path("blocked").asInt());
        assertFalse(report.path("runtimeComparison").get(0).path("comparable").asBoolean());
        assertFalse(reports.networkRetryable(frozen, report));
    }

    @Test
    void independentSourceFailureOrContentLossIsStillRepairableBesideHostFailure() {
        var frozen = frozenLoaderProfile();
        frozen.withObject("baseline").withObject("profile").withArray("requiredNodes").add("C03");
        frozen.withObject("baseline").put("profileSha256", Hashes.sha256(frozen.path("baseline").path("profile").toString()));
        var evidence = hostFailure(frozen);
        evidence.withObject("loaderValidation").withArray("nodes").addObject()
                .put("id", "C03").put("status", "fail").put("reason", "CONTENT_LIST_EMPTY_OR_INVALID");
        var report = reports.runtime(frozen, identifiedStaticReport(), evidence);
        assertFalse(report.path("runtimeValidationPaused").asBoolean());
        assertFalse(report.path("repairSuccessful").asBoolean());
        assertTrue(RepairReport.repairableLoaderNode(evidence.at("/loaderValidation/nodes/2")));
        assertFalse(RepairReport.repairableLoaderNode(evidence.at("/loaderValidation/nodes/1")));
        evidence.withObject("loaderValidation").withArray("nodes").remove(2);
        frozen.withObject("baseline").putObject("result").putObject("managedSourceEvidence")
                .set("untrustedRuntimeSummary", summary(true));
        evidence.withObject("untrustedRuntimeSummary").put("categoryItems", 0);
        report = reports.runtime(frozen, identifiedStaticReport(), evidence);
        assertFalse(report.path("runtimeValidationPaused").asBoolean());
        assertTrue(report.path("observedRegressions").toString().contains("categoryItems"));
    }

    @Test
    void optionalOrUnboundHostFailuresCannotPauseRequiredSourceRepairs() {
        var frozen = frozenLoaderProfile();
        var evidence = hostFailure(frozen);
        var node = (ObjectNode) evidence.at("/loaderValidation/nodes/1");
        node.put("id", "R01");
        assertFalse(reports.runtime(frozen, identifiedStaticReport(), evidence).path("runtimeValidationPaused").asBoolean());
        node.put("id", "C01");
        for (String defect : List.of("source", "profile", "runtime", "cleanup")) {
            var invalid = evidence.deepCopy();
            switch (defect) {
                case "source" -> invalid.put("inputSourceSha256", "f".repeat(64));
                case "profile" -> invalid.withObject("loaderValidation").put("inputProfileSha256", "f".repeat(64));
                case "runtime" -> invalid.put("bundleSha256", "f".repeat(64));
                default -> invalid.withObject("cleanup").put("confirmed", false);
            }
            var report = reports.runtime(frozen, identifiedStaticReport(), invalid);
            assertFalse(report.path("runtimeValidationPaused").asBoolean(), defect);
            assertFalse(report.path("repairSuccessful").asBoolean(), defect);
        }
    }

    private ObjectNode hostFailure(ObjectNode frozen) {
        var evidence = loaderEvidence(frozen);
        var loader = evidence.withObject("loaderValidation");
        ((ObjectNode) loader.path("nodes").get(1)).put("status", "fail").put("reason", "LOADER_METHOD_FAILED")
                .putObject("failureContext").put("hostStage", "atvp_backend_endpoint");
        loader.putArray("calls").addObject().put("id", 1).put("method", "home").put("ok", false)
                .putObject("failureContext").put("hostStage", "atvp_backend_endpoint");
        evidence.withObject("untrustedRuntimeSummary").put("homeClasses", 0).put("homeItems", 0).put("homeCovers", 0);
        return evidence;
    }

    @Test
    void currentPageExactPlaylistCoverageReconcilesCountsButPartialOrUnboundEvidenceDoesNot() throws Exception {
        var frozen = frozenLoaderProfile();
        frozen.withObject("baseline").put("artifactSha256", "b".repeat(64));
        var evidence = loaderEvidence(frozen);
        var loader = evidence.withObject("loaderValidation");
        var snapshot = loader.putObject("contentSnapshot").put("schemaVersion", 1)
                .put("declaredCategories", 1).put("allCategoriesSampled", true).put("detailsSampled", true);
        var detail = snapshot.putArray("categories").addObject().put("categoryIdSha256", "c".repeat(64))
                .put("status", "returned").put("firstItems", 20).putObject("detail")
                .put("itemIdSha256", "d".repeat(64)).put("status", "returned")
                .put("episodes", 59).put("groups", 1).put("detailCallId", 8);
        frozen.withObject("baseline").putObject("result").putObject("managedSourceEvidence")
                .set("loaderValidation", loader.deepCopy());
        detail.put("episodes", 1);
        var call = mapper.createObjectNode().put("id", 8).put("method", "detail").put("inputSha256", "7".repeat(64));
        loader.putArray("calls").add(call.deepCopy().put("ok", true));
        var resources = ManagedResourceEvidenceTest.fixture(mapper, evidence.path("inputSourceSha256").asText())
                .put("targetNode", "C10").putNull("image");
        resources.withArray("resources").remove(1);
        var page = (ObjectNode) resources.path("resources").get(0);
        page.put("responseStatus", 200).set("observedDuringCall", call);
        var proof = page.putObject("playlistObservation").put("policy", "indexed_playlist_links_v1")
                .put("complete", true).put("groups", 1).put("maxEpisodes", 1)
                .put("playlistSha256", "9".repeat(64)).put("outputMatchesPage", true);
        proof.putArray("groupEpisodeCounts").add(1);
        resources.put("textBytes", mapper.writeValueAsBytes(proof).length);
        var events = ManagedResourceEvidenceTest.events(mapper);
        ((ObjectNode) events.get(1)).set("observedDuringCall", call.deepCopy());
        evidence.set("httpObservations", events);
        evidence.set("contentResourceEvidence", resources);
        var accepted = reports.runtime(frozen, identifiedStaticReport(), evidence);
        assertTrue(accepted.path("contentAcceptance").path("successful").asBoolean(), accepted::toString);
        assertTrue(accepted.path("contentAcceptance").path("regressions").isEmpty());
        assertEquals("host_current_page_exact_link_coverage", accepted.path("contentAcceptance")
                .path("comparison").get(0).path("pagePlaylistObservation").path("basis").asText());
        for (String defect : List.of("partial", "missingEpisode", "otherGroups", "otherCall", "otherResponse", "otherSource")) {
            var changed = evidence.deepCopy();
            var block = (ObjectNode) changed.path("contentResourceEvidence");
            var row = (ObjectNode) block.path("resources").get(0);
            var observed = (ObjectNode) row.path("playlistObservation");
            switch (defect) {
                case "partial" -> observed.put("complete", false).put("outputMatchesPage", false);
                case "missingEpisode" -> observed.put("outputMatchesPage", false);
                case "otherGroups" -> { observed.put("groups", 2); observed.withArray("groupEpisodeCounts").add(1); }
                case "otherCall" -> ((ObjectNode) changed.path("httpObservations").get(1).path("observedDuringCall")).put("id", 9);
                case "otherResponse" -> row.put("responseSha256", "f".repeat(64));
                default -> block.put("artifactSha256", "f".repeat(64));
            }
            block.put("textBytes", mapper.writeValueAsBytes(observed).length);
            var rejected = reports.runtime(frozen, identifiedStaticReport(), changed);
            assertFalse(rejected.path("contentAcceptance").path("successful").asBoolean(), defect);
        }
        var changed = evidence.deepCopy();
        var block = (ObjectNode) changed.path("contentResourceEvidence");
        var observed = (ObjectNode) block.path("resources").get(0).path("playlistObservation");
        observed.put("outputMatchesPage", false).putObject("linkCoverage")
                .put("pageUniqueLinks", 2).put("outputUniqueLinks", 1)
                .put("missingPageLinks", 1).put("extraOutputLinks", 0);
        block.put("textBytes", mapper.writeValueAsBytes(observed).length);
        ((ObjectNode) frozen.path("baseline").path("result").path("managedSourceEvidence")
                .path("loaderValidation").path("contentSnapshot").path("categories").get(0).path("detail"))
                .put("status", "error");
        var missing = reports.runtime(frozen, identifiedStaticReport(), changed);
        assertFalse(missing.path("contentAcceptance").path("successful").asBoolean());
        assertTrue(missing.path("contentAcceptance").path("regressions").toString()
                .contains("DETAIL_PAGE_PLAYBACK_LINKS_MISSING"));
    }

    @Test
    void historicalRepairCanCompareCurrentOriginalContentWithoutReplacingFrozenBaseline() {
        var frozen = frozenLoaderProfile();
        frozen.withObject("baseline").put("artifactSha256", "b".repeat(64));
        var evidence = loaderEvidence(frozen);
        evidence.put("inputSourceSha256", "b".repeat(64)).put("bundleSha256", "e".repeat(64));
        evidence.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        var loader = evidence.withObject("loaderValidation");
        var content = loader.putObject("contentSnapshot").put("schemaVersion", 1)
                .put("declaredCategories", 1).put("allCategoriesSampled", true).put("detailsSampled", true);
        content.putArray("categories").addObject().put("categoryIdSha256", "c".repeat(64))
                .put("status", "returned").put("firstItems", 20)
                .putObject("detail").put("itemIdSha256", "d".repeat(64))
                .put("status", "returned").put("episodes", 12).put("groups", 1);
        var run = org.mockito.Mockito.mock(cn.har01d.atmaintenance.dto.ProbeSummary.class);
        org.mockito.Mockito.when(run.id()).thenReturn("current-original-probe");
        org.mockito.Mockito.when(run.sourceId()).thenReturn("source");
        org.mockito.Mockito.when(run.artifactSha256()).thenReturn("b".repeat(64));
        org.mockito.Mockito.when(run.executionMode()).thenReturn("managed_source");
        org.mockito.Mockito.when(run.status()).thenReturn("COMPLETED");
        org.mockito.Mockito.when(run.freshness()).thenReturn("current");
        org.mockito.Mockito.when(run.runtimeSha256()).thenReturn("e".repeat(64));
        org.mockito.Mockito.when(run.executionPolicySha256()).thenReturn("f".repeat(64));
        org.mockito.Mockito.when(run.profileSha256()).thenReturn(frozen.path("baseline").path("profileSha256").asText());
        var result = mapper.createObjectNode().set("managedSourceEvidence", evidence);
        org.mockito.Mockito.when(run.result()).thenReturn(result);
        String original = frozen.path("baseline").toString();
        var supplement = reports.contentBaseline(run, frozen, "source", "b".repeat(64), "e".repeat(64), "f".repeat(64));
        assertNotNull(supplement);
        frozen.putObject("runtimeValidationContext").put("runtimeSha256", "e".repeat(64))
                .put("executionPolicySha256", "f".repeat(64)).set("contentBaseline", supplement);
        assertEquals(original, frozen.path("baseline").toString());
        assertFalse(reports.contentBaseline(frozen).isMissingNode());
        var accepted = reports.runtime(frozen, identifiedStaticReport(), evidence);
        assertTrue(accepted.path("contentAcceptance").path("successful").asBoolean());
        assertEquals("current-original-probe", accepted.path("contentAcceptance").path("baselineRunId").asText());
        ((ObjectNode) content.path("categories").get(0).path("detail")).put("episodes", 1);
        assertFalse(reports.runtime(frozen, identifiedStaticReport(), evidence)
                .path("contentAcceptance").path("successful").asBoolean());
        assertNull(reports.contentBaseline(run, frozen, "other-source", "b".repeat(64), "e".repeat(64), "f".repeat(64)));
        assertNull(reports.contentBaseline(run, frozen, "source", "c".repeat(64), "e".repeat(64), "f".repeat(64)));
        assertNull(reports.contentBaseline(run, frozen, "source", "b".repeat(64), "c".repeat(64), "f".repeat(64)));
        frozen.withObject("runtimeValidationContext").put("executionPolicySha256", "0".repeat(64));
        assertTrue(reports.contentBaseline(frozen).isMissingNode());
        evidence.withObject("cleanup").put("confirmed", false);
        assertNull(reports.contentBaseline(run, frozen, "source", "b".repeat(64), "e".repeat(64), "f".repeat(64)));
    }

    @org.junit.jupiter.api.Test
    void contentGateRejectsEmptyCategoriesAndSingletonCollapseEvenForAnImageOnlyRepair() {
        for (String defect : List.of("empty", "oneItem", "oneEpisode", "missingCategory", "detailError")) {
            var frozen = frozenLoaderProfile();
            frozen.withObject("baseline").put("artifactSha256", "b".repeat(64));
            var evidence = loaderEvidence(frozen);
            var loader = evidence.withObject("loaderValidation");
            var content = loader.putObject("contentSnapshot").put("schemaVersion", 1)
                    .put("declaredCategories", 1).put("allCategoriesSampled", true).put("detailsSampled", true);
            var row = content.putArray("categories").addObject().put("categoryIdSha256", "c".repeat(64))
                    .put("status", "returned").put("firstItems", 20);
            var detail = row.putObject("detail").put("itemIdSha256", "d".repeat(64))
                    .put("status", "returned").put("episodes", 12).put("groups", 1);
            frozen.withObject("baseline").putObject("result").putObject("managedSourceEvidence")
                    .set("loaderValidation", loader.deepCopy());
            switch (defect) {
                case "empty" -> { row.put("status", "empty").put("firstItems", 0); row.remove("detail"); }
                case "oneItem" -> row.put("firstItems", 1);
                case "oneEpisode" -> detail.put("episodes", 1);
                case "missingCategory" -> { content.put("declaredCategories", 0); content.withArray("categories").removeAll(); }
                default -> detail.put("status", "error");
            }
            var report = reports.runtime(frozen, identifiedStaticReport(), evidence);
            assertFalse(report.path("repairSuccessful").asBoolean(), defect);
            assertFalse(report.path("contentAcceptance").path("successful").asBoolean(), defect);
        }
    }

    @org.junit.jupiter.api.Test
    void contentGateAllowsOriginallyEmptySingleItemAndSingleEpisodeCategories() {
        var frozen = frozenLoaderProfile();
        frozen.withObject("baseline").put("artifactSha256", "b".repeat(64));
        var evidence = loaderEvidence(frozen);
        var loader = evidence.withObject("loaderValidation");
        var snapshot = loader.putObject("contentSnapshot").put("schemaVersion", 1)
                .put("declaredCategories", 2).put("allCategoriesSampled", true).put("detailsSampled", true);
        var categories = snapshot.putArray("categories");
        categories.addObject().put("categoryIdSha256", "c".repeat(64)).put("status", "empty").put("firstItems", 0);
        categories.addObject().put("categoryIdSha256", "e".repeat(64)).put("status", "returned").put("firstItems", 1)
                .putObject("detail").put("itemIdSha256", "d".repeat(64)).put("status", "returned")
                .put("episodes", 1).put("groups", 1);
        frozen.withObject("baseline").putObject("result").putObject("managedSourceEvidence")
                .set("loaderValidation", loader.deepCopy());
        assertTrue(reports.runtime(frozen, identifiedStaticReport(), evidence).path("repairSuccessful").asBoolean());
        var detail = (ObjectNode) snapshot.path("categories").get(1).path("detail");
        detail.put("itemIdSha256", "f".repeat(64));
        var report = reports.runtime(frozen, identifiedStaticReport(), evidence);
        assertTrue(report.path("contentAcceptance").path("regressions").isEmpty());
        assertFalse(report.path("contentAcceptance").path("comparison").get(1).path("detailComparable").asBoolean());
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final RepairReport reports = new RepairReport(mapper);

    @Test
    void originRecoveryRequiresVerifiedRouteAndAllCandidateNodesButNeverInventsBaselineSuccess() {
        var frozen = frozenLoaderProfile();
        frozen.put("originRecoveryRepairId", "repair");
        frozen.putObject("policy").put("scope", "managed_origin_candidate").putArray("targetNodes").add("S06").add("C01");
        var baseline = frozen.withObject("baseline").put("id", "run").put("artifactSha256", "c".repeat(64));
        baseline.putObject("result").putObject("sourcePreparation").put("status", "FAILED")
                .put("code", "SOURCE_SETUP_DNS_NO_ADDRESS").put("sourceExecuted", false).put("sourceHttpObserved", false);
        var evidence = loaderEvidence(frozen);
        var withoutRoute = reports.runtime(frozen, identifiedStaticReport(), evidence);
        assertFalse(withoutRoute.path("repairSuccessful").asBoolean());
        assertTrue(withoutRoute.path("runtimeAcceptance").path("blockers").toString().contains("VERIFIED_ORIGIN_ROUTE_REQUIRED"));
        var repair = new cn.har01d.atmaintenance.dto.RepairSummary("repair", "source", "run", "c".repeat(64),
                "d".repeat(64), "AWAITING_PROPOSAL", null, 0, 3, null, java.time.Instant.now(), frozen, "current", List.of());
        frozen.set("reverseAnalysis", RepairEvidenceMaterialTest.originReceipt(mapper, repair));
        var result = reports.runtime(frozen, identifiedStaticReport(), evidence);
        assertTrue(result.path("repairSuccessful").asBoolean(), result::toString);
        assertFalse(result.path("runtimeNodeProgress").path("baselineVerified").asBoolean(true));
        assertFalse(result.path("publishEligible").asBoolean());
        ((ObjectNode) evidence.path("loaderValidation").path("nodes").get(1)).put("status", "fail").put("reason", "HOME_OUTPUT_INVALID");
        assertFalse(reports.runtime(frozen, identifiedStaticReport(), evidence).path("repairSuccessful").asBoolean());
    }

    @Test
    void loaderCanAddImportsButCannotRemoveOrRebindExistingDependencies() {
        var original = importInspection();
        var added = original.deepCopy().put("importContractSha256", "b".repeat(64));
        added.withArray("importEntries").addObject().put("scopeSha256", "2".repeat(64))
                .put("bindingSha256", "3".repeat(64)).put("targetSha256", "4".repeat(64))
                .put("importSha256", "5".repeat(64));
        var frozen = importFrozen(original, "managed_loader_candidate");
        assertTrue(reports.build(frozen, "c".repeat(64), added).path("staticValidationPassed").asBoolean());
        assertFalse(reports.build(importFrozen(original, "static_candidate_validator"), "c".repeat(64), added)
                .path("staticValidationPassed").asBoolean());
        for (String defect : List.of("removed", "rebound", "scope", "truncated", "missing", "wildcard")) {
            var broken = added.deepCopy();
            var entry = (ObjectNode) broken.path("importEntries").get(1);
            switch (defect) {
                case "removed" -> broken.withArray("importEntries").remove(0);
                case "rebound" -> entry.put("scopeSha256", "0".repeat(64)).put("bindingSha256", "1".repeat(64));
                case "scope" -> ((ObjectNode) broken.path("importEntries").get(0)).put("scopeSha256", "9".repeat(64));
                case "truncated" -> broken.put("importsTruncated", true);
                case "missing" -> broken.remove("importEntries");
                case "wildcard" -> entry.put("bindingSha256", Hashes.sha256("*"));
            }
            assertFalse(reports.build(frozen, "c".repeat(64), broken).path("staticValidationPassed").asBoolean(), defect);
        }
    }

    private ObjectNode importInspection() {
        var value = mapper.createObjectNode().put("parseOk", true).put("pythonVersion", "fixture")
                .put("classContractSha256", "a".repeat(64)).put("importContractSha256", "a".repeat(64))
                .put("importsTruncated", false).put("methodsTruncated", false);
        value.putArray("issues");
        value.putObject("methodContracts").put("init", "a".repeat(64));
        value.putArray("importEntries").addObject().put("scopeSha256", "0".repeat(64))
                .put("bindingSha256", "1".repeat(64)).put("targetSha256", "2".repeat(64))
                .put("importSha256", "3".repeat(64));
        return value;
    }

    private ObjectNode importFrozen(ObjectNode inspection, String scope) {
        var frozen = mapper.createObjectNode();
        frozen.putObject("policy").put("scope", scope);
        frozen.putObject("baseline").putObject("result").putArray("nodes").addObject()
                .put("id", "S03").put("requirement", "required").put("status", "pass").set("evidence", inspection);
        return frozen;
    }

    @Test
    void recoveredHomeCannotHideLostPlayerOrMediaObservation() {
        for (String field : List.of("playerStatus", "mediaStatus")) {
            for (String replacement : field.equals("playerStatus")
                    ? List.of("policy_denied", "empty", "not_sampled", "missing")
                    : List.of("http_failed", "not_requested", "missing")) {
                var before = summary(false).put("playerStatus", "returned").put("mediaStatus", "prefix_returned");
                var after = summary(true).put("playerStatus", "returned").put("mediaStatus", "prefix_returned");
                if (replacement.equals("missing")) after.remove(field);
                else after.put(field, replacement);
                var report = report(before, after);
                assertTrue(report.path("targetObservedRecovered").asBoolean());
                assertEquals(mapper.valueToTree(List.of(field)), report.path("observedRegressions"));
                assertEquals("runtime_target_unresolved", report.path("verdict").asText());
                assertFalse(report.path("publishEligible").asBoolean());
            }
        }
    }

    @Test
    void stageComparisonPreservesUnknownsWithoutInventingSuccessOrRegression() {
        var report = report(summary(false), summary(true).put("playerStatus", "policy_denied")
                .put("mediaStatus", "not_requested"));
        assertTrue(report.path("observedRegressions").isEmpty());
        assertEquals("runtime_target_observed", report.path("verdict").asText());
        assertEquals(10, report.path("runtimeComparison").size());
        var stages = report.path("runtimeStageComparison");
        assertEquals(2, stages.size());
        assertEquals("playerStatus", stages.get(0).path("field").asText());
        assertTrue(stages.get(0).path("before").isNull());
        assertEquals("policy_denied", stages.get(0).path("after").asText());
        assertFalse(report.path("businessVerdictTrusted").asBoolean());
    }

    @Test
    void unchangedOrImprovedPlayerAndMediaObservationsRemainNonPublishing() {
        for (boolean alreadyReturned : List.of(false, true)) {
            var before = summary(false).put("playerStatus", alreadyReturned ? "returned" : "not_sampled")
                    .put("mediaStatus", alreadyReturned ? "prefix_returned" : "not_requested");
            var report = report(before, summary(true).put("playerStatus", "returned")
                    .put("mediaStatus", "prefix_returned"));
            assertTrue(report.path("observedRegressions").isEmpty());
            assertEquals("runtime_target_observed", report.path("verdict").asText());
            assertFalse(report.path("targetRecovered").asBoolean());
            assertFalse(report.path("businessVerdictTrusted").asBoolean());
            assertFalse(report.path("publishEligible").asBoolean());
        }
    }

    @Test
    void runtimeAcceptanceUsesAllFrozenRequirementsNotJustThePatchTargets() {
        var frozen = mapper.createObjectNode();
        frozen.putObject("policy").putArray("targetNodes").add("C01").add("C02");
        frozen.putObject("baseline").putObject("profile").putArray("requiredNodes")
                .add("S01").add("S02").add("S03").add("C01").add("C05").add("C07").add("C10").add("R01").add("M01");
        var report = reports.runtime(frozen, staticReport(), successfulSummary());
        var acceptance = report.path("runtimeAcceptance");
        assertEquals(10, acceptance.path("requiredCoverage").path("total").asInt());
        assertEquals(3, acceptance.path("requiredCoverage").path("pass").asInt());
        assertEquals(1, acceptance.path("requiredCoverage").path("blocked").asInt());
        assertEquals(6, acceptance.path("requiredCoverage").path("notTested").asInt());
        assertEquals("S06", acceptance.path("firstUnresolvedNode").asText());
        assertTrue(acceptance.path("nodes").valueStream().anyMatch(node -> node.path("id").asText().equals("C05")));
        assertFalse(acceptance.path("nodes").valueStream().anyMatch(node -> node.path("id").asText().equals("P04")));
        assertFalse(report.path("repairSuccessful").asBoolean());
    }

    @Test
    void allPositiveSourceCountersAndSelfDeclaredSuccessCannotVerifyLoaderNodes() {
        var frozen = mapper.createObjectNode();
        frozen.putObject("baseline").putObject("profile").putArray("requiredNodes")
                .add("S01").add("S02").add("S03").add("S06");
        var evidence = successfulSummary();
        evidence.put("businessChainVerified", true).put("repairSuccessful", true);
        evidence.putObject("runtimeAcceptance").put("successful", true);
        evidence.putArray("loaderNodeResults").addObject().put("id", "S06").put("status", "pass");
        evidence.withObject("untrustedRuntimeSummary").putArray("loaderNodeResults")
                .addObject().put("id", "S06").put("status", "pass").put("reason", "LOADER_INIT_RETURNED");
        var staticReport = staticReport();
        staticReport.withArray("nodes").addObject().put("id", "S06").put("status", "pass");
        var report = reports.runtime(frozen, staticReport, evidence);
        assertTrue(report.path("targetObservedRecovered").asBoolean());
        assertEquals(1, report.path("runtimeAcceptance").path("requiredCoverage").path("blocked").asInt());
        assertFalse(report.path("runtimeAcceptance").path("successful").asBoolean());
        assertFalse(report.path("repairSuccessful").asBoolean());
        assertFalse(report.path("businessVerdictTrusted").asBoolean());
        assertFalse(report.path("publishEligible").asBoolean());
        assertEquals("blocked", report.path("runtimeAcceptance").path("nodes").get(3).path("status").asText());
    }

    @Test
    void missingRequirementsAndUnknownRequiredNodesFailClosedWithoutUsingTargetNodes() {
        for (boolean missing : List.of(true, false)) {
            var frozen = mapper.createObjectNode();
            frozen.putObject("policy").putArray("targetNodes").add("C01");
            if (!missing) frozen.putObject("baseline").putObject("profile").putArray("requiredNodes").add("UNKNOWN");
            var report = reports.runtime(frozen, staticReport(), successfulSummary());
            var acceptance = report.path("runtimeAcceptance");
            assertFalse(acceptance.path("successful").asBoolean());
            assertEquals(missing ? 4 : 5, acceptance.path("requiredCoverage").path("total").asInt());
            if (missing) assertTrue(acceptance.path("blockers").valueStream()
                    .anyMatch(value -> value.asText().equals("REPAIR_REQUIREMENTS_UNAVAILABLE")));
            else assertEquals("not_tested", acceptance.path("nodes").get(4).path("status").asText());
        }
    }

    @Test
    void absentOrInvalidIdentitiesCannotMatchEachOther() {
        for (String identity : List.of("", "invalid", "a".repeat(64))) {
            var frozen = mapper.createObjectNode();
            var baseline = frozen.putObject("baseline");
            baseline.putObject("profile").putArray("requiredNodes").add("S06");
            var evidence = successfulSummary();
            var staticReport = staticReport();
            if (!identity.isEmpty()) {
                baseline.put("runtimeSha256", identity);
                staticReport.put("artifactSha256", identity);
                evidence.put("inputSourceSha256", identity).put("bundleSha256", identity);
            }
            var report = reports.runtime(frozen, staticReport, evidence);
            assertEquals(!identity.equals("a".repeat(64)), report.path("runtimeAcceptance").path("blockers")
                    .valueStream().anyMatch(value -> value.asText().equals("RUNTIME_INPUT_MISMATCH")));
            assertFalse(report.path("repairSuccessful").asBoolean());
        }
    }

    @Test
    void verifiedPartialLoaderNodesReplaceCountersWithoutHidingMissingCapabilities() {
        var frozen = frozenLoaderProfile();
        frozen.withObject("baseline").withObject("profile").withArray("requiredNodes").add("C05");
        frozen.withObject("baseline").put("profileSha256",
                Hashes.sha256(frozen.path("baseline").path("profile").toString()));
        var evidence = loaderEvidence(frozen);
        var report = reports.runtime(frozen, identifiedStaticReport(), evidence);
        var acceptance = report.path("runtimeAcceptance");
        assertEquals(5, acceptance.path("requiredCoverage").path("pass").asInt());
        assertEquals(1, acceptance.path("requiredCoverage").path("notTested").asInt());
        assertEquals("C05", acceptance.path("firstUnresolvedNode").asText());
        assertEquals("host_loader_contract_observed", acceptance.path("nodes").get(3).path("evidenceClass").asText());
        assertFalse(report.path("repairSuccessful").asBoolean());
        assertFalse(acceptance.path("blockers").valueStream()
                .anyMatch(value -> value.asText().equals("LOADER_REQUIRED_RESULTS_UNVERIFIED")));
    }

    @Test
    void onlyEveryFrozenRequiredContractPlusGatesCanFinishTheRepair() {
        var frozen = frozenLoaderProfile();
        var evidence = loaderEvidence(frozen);
        var report = reports.runtime(frozen, identifiedStaticReport(), evidence);
        assertTrue(report.path("repairSuccessful").asBoolean());
        assertTrue(report.path("runtimeAcceptance").path("successful").asBoolean());
        assertTrue(report.path("runtimeAcceptance").path("firstUnresolvedNode").isNull());
        assertEquals("loader_required_nodes_passed", report.path("verdict").asText());
        assertFalse(report.path("publishEligible").asBoolean());
        assertFalse(report.path("businessVerdictTrusted").asBoolean());
        // A loader boolean is neither necessary nor sufficient for the product verdict.
        evidence.withObject("loaderValidation").put("successful", false);
        assertTrue(reports.runtime(frozen, identifiedStaticReport(), evidence).path("repairSuccessful").asBoolean());
        evidence.withArray("deniedCodes").add("EGRESS_URL_DENIED");
        assertFalse(reports.runtime(frozen, identifiedStaticReport(), evidence).path("repairSuccessful").asBoolean());
    }

    @Test
    void handledChildNetworkFailureKeepsAuditWithoutBlockingPassedRequiredContracts() {
        var frozen = frozenLoaderProfile();
        var evidence = loaderEvidence(frozen);
        evidence.withArray("deniedCodes").add("EGRESS_NONPUBLIC_DNS");
        evidence.putArray("networkFailureCalls").addObject().put("denialIndex", 0).put("method", "home");
        assertFalse(RepairReport.networkUnavailable(evidence));
        assertTrue(reports.runtime(frozen, identifiedStaticReport(), evidence).path("repairSuccessful").asBoolean());
        assertEquals("EGRESS_NONPUBLIC_DNS", evidence.path("deniedCodes").get(0).asText());
        for (String defect : List.of("missing", "wrongAction", "failedAction", "policy")) {
            var changed = evidence.deepCopy();
            switch (defect) {
                case "missing" -> changed.remove("networkFailureCalls");
                case "wrongAction" -> ((ObjectNode) changed.path("networkFailureCalls").get(0)).put("method", "category");
                case "failedAction" -> ((ObjectNode) changed.path("loaderValidation").path("nodes").get(1))
                        .put("status", "fail");
                case "policy" -> changed.putArray("deniedCodes").add("EGRESS_URL_DENIED");
            }
            assertTrue(RepairReport.egressUnresolved(changed), defect);
            assertFalse(reports.runtime(frozen, identifiedStaticReport(), changed)
                    .path("repairSuccessful").asBoolean(), defect);
        }
    }

    @Test
    void passedCategoriesCannotDismissNetworkFailureWhenHomeOutputIsLost() {
        var frozen = frozenLoaderProfile();
        var evidence = loaderEvidence(frozen);
        var original = evidence.deepCopy().put("inputSourceSha256", "c".repeat(64));
        original.withObject("loaderValidation").put("inputSourceSha256", "c".repeat(64));
        frozen.withObject("baseline").put("artifactSha256", "c".repeat(64))
                .putObject("result").set("managedSourceEvidence", original);
        evidence.withObject("untrustedRuntimeSummary").put("homeItems", 0);
        evidence.withArray("deniedCodes").add("EGRESS_DNS_FAILED");
        evidence.putArray("networkFailureCalls").addObject().put("denialIndex", 0).put("method", "home");
        var report = reports.runtime(frozen, identifiedStaticReport(), evidence);
        assertTrue(report.path("runtimeValidationPaused").asBoolean());
        assertTrue(report.path("observedRegressions").isEmpty());
        assertFalse(report.path("repairSuccessful").asBoolean());
        assertTrue(reports.networkRetryable(frozen, report));
        assertEquals(5, report.path("runtimeAcceptance").path("requiredCoverage").path("pass").asInt());
        var home = report.path("runtimeComparison").valueStream()
                .filter(row -> row.path("field").asText().equals("homeItems")).findFirst().orElseThrow();
        assertFalse(home.path("comparable").asBoolean());
        assertEquals("RUNTIME_NETWORK_UNAVAILABLE", home.path("reason").asText());

        evidence.withObject("untrustedRuntimeSummary").put("detailItems", 0);
        report = reports.runtime(frozen, identifiedStaticReport(), evidence);
        assertEquals(mapper.createArrayNode().add("detailItems"), report.path("observedRegressions"));
        evidence.withObject("untrustedRuntimeSummary").put("detailItems", 20);

        for (String defect : List.of("wrongAction", "wrongIndex", "noBaselineLoss", "hasContent")) {
            var changed = evidence.deepCopy();
            var baseline = frozen.deepCopy();
            switch (defect) {
                case "wrongAction" -> {
                    ((ObjectNode) changed.path("networkFailureCalls").get(0)).put("method", "detail");
                    changed.withObject("loaderValidation").withArray("nodes").addObject()
                            .put("id", "C07").put("status", "pass");
                }
                case "wrongIndex" -> ((ObjectNode) changed.path("networkFailureCalls").get(0)).put("denialIndex", 5);
                case "noBaselineLoss" -> baseline.withObject("baseline").withObject("result")
                        .withObject("managedSourceEvidence").withObject("untrustedRuntimeSummary").put("homeItems", 0);
                case "hasContent" -> changed.withObject("untrustedRuntimeSummary").put("homeItems", 1);
            }
            var checked = reports.runtime(baseline, identifiedStaticReport(), changed);
            if (defect.equals("wrongAction")) {
                assertFalse(checked.path("runtimeValidationPaused").asBoolean());
                assertEquals(mapper.createArrayNode().add("homeItems"), checked.path("observedRegressions"));
            } else if (defect.equals("wrongIndex")) {
                assertTrue(RepairReport.egressUnresolved(changed));
            } else {
                assertFalse(checked.path("runtimeValidationPaused").asBoolean(), defect);
                assertTrue(checked.path("repairSuccessful").asBoolean(), defect);
            }
        }
    }

    @Test
    void privateAggregateAndStaleProfileNeverSupplyProductNodePasses() {
        var frozen = frozenLoaderProfile();
        for (String defect : List.of("private", "unprojected", "profile", "source")) {
            var evidence = loaderEvidence(frozen);
            var loader = evidence.withObject("loaderValidation");
            switch (defect) {
                case "private" -> loader.put("authority", "private_sample_host_acceptance");
                case "unprojected" -> loader.remove("projection");
                case "profile" -> loader.put("inputProfileSha256", "d".repeat(64));
                case "source" -> loader.put("inputSourceSha256", "d".repeat(64));
            }
            var report = reports.runtime(frozen, identifiedStaticReport(), evidence);
            assertFalse(report.path("repairSuccessful").asBoolean(), defect);
            assertEquals("S06", report.path("runtimeAcceptance").path("firstUnresolvedNode").asText(), defect);
        }
    }

    @Test
    void categoryOnlyHomeDoesNotRequireAnUndeclaredHomeCardCapability() {
        var frozen = frozenLoaderProfile();
        var evidence = loaderEvidence(frozen);
        evidence.withObject("untrustedRuntimeSummary").put("homeItems", 0);
        assertTrue(reports.runtime(frozen, identifiedStaticReport(), evidence).path("targetObservedRecovered").asBoolean());
        frozen.withObject("baseline").withObject("profile").withArray("requiredNodes").add("C02");
        assertFalse(reports.runtime(frozen, identifiedStaticReport(), evidence).path("targetObservedRecovered").asBoolean());
    }

    @Test
    void nonHomeRepairsAdvanceOnlyFromCurrentHostNodeReceipts() {
        for (String id : List.of("C03", "C05", "C07", "C10", "R01", "R04", "R05")) {
            var frozen = nodeRepair(id);
            var evidence = nodeCandidate(frozen, id, "pass");
            var report = reports.runtime(frozen, identifiedStaticReport(), evidence);
            assertTrue(report.path("runtimeProgressObserved").asBoolean(), id);
            assertEquals(mapper.valueToTree(List.of(id)), report.path("runtimeNodeProgress").path("recovered"), id);
            assertTrue(report.path("targetObservedRecovered").asBoolean(), id);
            assertFalse(report.path("repairSuccessful").asBoolean(), id);
            assertFalse(report.path("publishEligible").asBoolean(), id);
            assertEquals("runtime_nodes_progress_observed", report.path("verdict").asText(), id);
            assertEquals(0, report.path("runtimeObservation").path("homeItems").asInt(), id);
        }
    }

    @Test
    void unchangedNodeFailureCannotAdvanceBecauseHomeCountersArePositive() {
        var frozen = nodeRepair("C05");
        var evidence = nodeCandidate(frozen, "C05", "fail");
        evidence.withObject("untrustedRuntimeSummary").put("homeClasses", 20).put("homeItems", 20);
        var report = reports.runtime(frozen, identifiedStaticReport(), evidence);
        assertFalse(report.path("runtimeProgressObserved").asBoolean());
        assertFalse(report.path("targetObservedRecovered").asBoolean());
        assertTrue(report.path("runtimeNodeProgress").path("recovered").isEmpty());
    }

    @Test
    void verifiedSinglePageRecoveryDoesNotTreatTheOldRepeatedPageAsLostContent() {
        var frozen = paginationRepair();
        var report = reports.runtime(frozen, identifiedStaticReport(), singlePageEvidence(frozen));
        assertTrue(report.path("observedRegressions").isEmpty());
        assertTrue(report.path("repairSuccessful").asBoolean());
        var comparison = report.path("runtimeComparison").valueStream()
                .filter(row -> row.path("field").asText().equals("page2Items")).findFirst().orElseThrow();
        assertEquals(20, comparison.path("before").asInt());
        assertEquals(0, comparison.path("after").asInt());
        assertFalse(comparison.path("comparable").asBoolean());
        assertEquals("FAILED_PAGINATION_RECOVERED_AS_VERIFIED_SINGLE_PAGE", comparison.path("reason").asText());
    }

    @Test
    void missingUnboundOrPreviouslyHealthyPaginationCannotWaiveThePageLossCheck() {
        for (String defect : List.of("baseline_pass", "baseline_reason", "baseline_source", "candidate_source",
                "candidate_profile", "candidate_fail", "multiple_pages", "missing_count", "page_numbers", "legacy")) {
            var frozen = paginationRepair();
            var evidence = singlePageEvidence(frozen);
            var before = (ObjectNode) frozen.path("baseline").path("result").path("managedSourceEvidence")
                    .path("loaderValidation").path("nodes").get(2);
            var after = (ObjectNode) evidence.path("loaderValidation").path("nodes").get(2);
            switch (defect) {
                case "baseline_pass" -> before.put("status", "pass");
                case "baseline_reason" -> before.put("reason", "LOADER_METHOD_FAILED");
                case "baseline_source" -> frozen.withObject("baseline").put("artifactSha256", "d".repeat(64));
                case "candidate_source" -> evidence.withObject("loaderValidation").put("inputSourceSha256", "d".repeat(64));
                case "candidate_profile" -> evidence.withObject("loaderValidation").put("inputProfileSha256", "d".repeat(64));
                case "candidate_fail" -> after.put("status", "fail");
                case "multiple_pages" -> after.put("pagesObserved", 2);
                case "missing_count" -> after.remove("secondItems");
                case "page_numbers" -> after.put("pageNumbersVerified", false);
                case "legacy" -> frozen.withObject("policy").put("scope", "managed_home_candidate");
            }
            var report = reports.runtime(frozen, identifiedStaticReport(), evidence);
            assertTrue(report.path("observedRegressions").valueStream()
                    .anyMatch(value -> value.asText().equals("page2Items")), defect);
            assertFalse(report.path("repairSuccessful").asBoolean(), defect);
        }
    }

    @Test
    void verifiedSinglePageRecoveryStillRejectsUnrelatedContentLoss() {
        var frozen = paginationRepair();
        var evidence = singlePageEvidence(frozen);
        evidence.withObject("untrustedRuntimeSummary").put("detailItems", 0);
        var report = reports.runtime(frozen, identifiedStaticReport(), evidence);
        assertEquals(mapper.valueToTree(List.of("detailItems")), report.path("observedRegressions"));
        assertFalse(report.path("repairSuccessful").asBoolean());
    }

    private ObjectNode paginationRepair() {
        var frozen = frozenLoaderProfile();
        frozen.putObject("policy").put("scope", "managed_loader_candidate").putArray("targetNodes").add("C03");
        var baseline = frozen.withObject("baseline").put("artifactSha256", "c".repeat(64));
        baseline.withObject("profile").withArray("requiredNodes").add("C03");
        baseline.put("profileSha256", Hashes.sha256(baseline.path("profile").toString()));
        var before = loaderEvidence(frozen).put("inputSourceSha256", "c".repeat(64));
        before.withObject("loaderValidation").put("inputSourceSha256", "c".repeat(64)).withArray("nodes")
                .addObject().put("id", "C03").put("status", "fail").put("reason", "PAGINATION_REPLAY_OR_PAGE_MISMATCH");
        baseline.putObject("result").set("managedSourceEvidence", before);
        return frozen;
    }

    private ObjectNode singlePageEvidence(ObjectNode frozen) {
        var after = loaderEvidence(frozen);
        after.withObject("untrustedRuntimeSummary").put("page2Items", 0);
        after.withObject("loaderValidation").withArray("nodes").addObject().put("id", "C03")
                .put("status", "pass").put("reason", "FIRST_SECOND_PAGE_WINDOW_CONTRACT")
                .put("paginationMode", "page-window").put("firstItems", 20).put("secondItems", 0)
                .put("pagesObserved", 1).put("pageNumbersVerified", true);
        return after;
    }

    @Test
    void oldGreenLoaderNodeCannotDisappearBehindUnchangedSourceCounters() {
        for (boolean missing : List.of(false, true)) {
            var frozen = nodeRepair("C05");
            var beforeNodes = frozen.withObject("baseline").withObject("result")
                    .withObject("managedSourceEvidence").withObject("loaderValidation").withArray("nodes");
            ((ObjectNode) beforeNodes.get(3)).put("status", "pass").put("reason", "MEDIA_PREFIX");
            var evidence = nodeCandidate(frozen, "C05", "pass");
            if (missing) evidence.withObject("loaderValidation").withArray("nodes").remove(3);
            var report = reports.runtime(frozen, identifiedStaticReport(), evidence);
            assertFalse(report.path("runtimeProgressObserved").asBoolean());
            assertEquals(mapper.valueToTree(List.of("M01")), report.path("runtimeNodeProgress").path("regressed"));
            assertTrue(report.path("observedRegressions").valueStream().anyMatch(value -> value.asText().equals("M01")));
            assertFalse(report.path("repairSuccessful").asBoolean());
        }
    }

    @Test
    void partialNodeProgressRequiresBothBoundReceiptsAndAllExecutionGates() {
        for (String defect : List.of("baseline_source", "baseline_profile", "candidate_source", "duplicate",
                "invalid_state", "egress", "cleanup", "static")) {
            var frozen = nodeRepair("R04");
            var evidence = nodeCandidate(frozen, "R04", "pass");
            var original = frozen.withObject("baseline").withObject("result")
                    .withObject("managedSourceEvidence").withObject("loaderValidation");
            var staticResult = identifiedStaticReport();
            switch (defect) {
                case "baseline_source" -> original.put("inputSourceSha256", "d".repeat(64));
                case "baseline_profile" -> original.put("inputProfileSha256", "d".repeat(64));
                case "candidate_source" -> evidence.withObject("loaderValidation").put("inputSourceSha256", "d".repeat(64));
                case "duplicate" -> evidence.withObject("loaderValidation").withArray("nodes")
                        .addObject().put("id", "R04").put("status", "pass");
                case "invalid_state" -> ((ObjectNode) evidence.path("loaderValidation").path("nodes").get(2))
                        .put("status", "success");
                case "egress" -> evidence.withArray("deniedCodes").add("EGRESS_URL_DENIED");
                case "cleanup" -> evidence.withObject("cleanup").put("confirmed", false);
                case "static" -> staticResult.put("staticValidationPassed", false);
            }
            var report = reports.runtime(frozen, staticResult, evidence);
            assertFalse(report.path("runtimeProgressObserved").asBoolean(), defect);
            assertFalse(report.path("repairSuccessful").asBoolean(), defect);
        }
    }

    @Test
    void repeatedFailureTracksStableContractChangesButNotCountsOrPassingDetails() {
        var frozen = nodeRepair("C07");
        var first = nodeCandidate(frozen, "C07", "fail");
        var original = (ObjectNode) first.path("loaderValidation").path("nodes").get(2);
        original.put("reason", "DETAIL_IDENTITY_MISMATCH").put("items", 1);
        original.putObject("identityEvidence").put("exactMatch", false).put("coverMatch", true)
                .put("selectedIdSha256", "a".repeat(64));
        var second = first.deepCopy();
        var changed = (ObjectNode) second.path("loaderValidation").path("nodes").get(2);
        changed.put("items", 100).put("elapsedMillis", 500);
        changed.withObject("identityEvidence").put("selectedIdSha256", "f".repeat(64));
        ((ObjectNode) second.path("loaderValidation").path("nodes").get(1)).put("classes", 99);
        var initial = reports.runtime(frozen, identifiedStaticReport(), first).path("runtimeFailureSignature");
        assertEquals(initial, reports.runtime(frozen, identifiedStaticReport(), second).path("runtimeFailureSignature"));
        changed.withObject("identityEvidence").put("coverMatch", false);
        assertNotEquals(initial, reports.runtime(frozen, identifiedStaticReport(), second).path("runtimeFailureSignature"));
        changed.set("identityEvidence", original.path("identityEvidence").deepCopy());
        changed.put("failureType", "KeyError");
        assertNotEquals(initial, reports.runtime(frozen, identifiedStaticReport(), second).path("runtimeFailureSignature"));
        second.withObject("loaderValidation").put("inputProfileSha256", "e".repeat(64));
        assertFalse(reports.runtime(frozen, identifiedStaticReport(), second).path("runtimeFailureSignature")
                .toString().contains("contractObservations"));
    }

    @Test
    void repeatedResultSignatureIgnoresCountersAndReceiptIdsButTracksTheRemainingFailure() {
        var frozen = nodeRepair("R04");
        var first = nodeCandidate(frozen, "R04", "pass");
        var second = first.deepCopy();
        second.put("jobId", "second-job");
        second.withObject("loaderValidation").put("brokerJobId", "another-child");
        second.withObject("untrustedRuntimeSummary").put("categoryItems", 500);
        assertEquals(reports.runtime(frozen, identifiedStaticReport(), first).path("runtimeFailureSignature"),
                reports.runtime(frozen, identifiedStaticReport(), second).path("runtimeFailureSignature"));
        ((ObjectNode) second.path("loaderValidation").path("nodes").get(3)).put("reason", "NEW_MEDIA_FAILURE");
        assertNotEquals(reports.runtime(frozen, identifiedStaticReport(), first).path("runtimeFailureSignature"),
                reports.runtime(frozen, identifiedStaticReport(), second).path("runtimeFailureSignature"));
    }

    private ObjectNode nodeRepair(String id) {
        var frozen = frozenLoaderProfile();
        frozen.putObject("policy").put("scope", "managed_loader_candidate").putArray("targetNodes").add(id);
        var baseline = frozen.withObject("baseline").put("artifactSha256", "c".repeat(64));
        baseline.withObject("profile").withArray("requiredNodes").add(id).add("M01");
        baseline.put("profileSha256", Hashes.sha256(baseline.path("profile").toString()));
        var before = nodeCandidate(frozen, id, "fail");
        before.put("inputSourceSha256", "c".repeat(64));
        before.withObject("loaderValidation").put("inputSourceSha256", "c".repeat(64));
        baseline.putObject("result").set("managedSourceEvidence", before);
        return frozen;
    }

    private ObjectNode nodeCandidate(ObjectNode frozen, String id, String state) {
        var evidence = loaderEvidence(frozen);
        evidence.withObject("untrustedRuntimeSummary").put("homeClasses", 0).put("homeItems", 0);
        evidence.withObject("loaderValidation").withArray("nodes").addObject()
                .put("id", id).put("status", state).put("reason", state.equals("pass") ? "CONTRACT_OBSERVED" : "CONTRACT_FAILED");
        evidence.withObject("loaderValidation").withArray("nodes").addObject()
                .put("id", "M01").put("status", "fail").put("reason", "MEDIA_PREFIX_MISSING");
        return evidence;
    }

    private ObjectNode frozenLoaderProfile() {
        var frozen = mapper.createObjectNode();
        var baseline = frozen.putObject("baseline").put("runtimeSha256", "a".repeat(64));
        baseline.putObject("profile").putArray("requiredNodes").add("S01").add("S02").add("S03").add("S06").add("C01");
        baseline.put("profileSha256", Hashes.sha256(baseline.path("profile").toString()));
        return frozen;
    }

    private ObjectNode identifiedStaticReport() {
        return staticReport().put("artifactSha256", "b".repeat(64));
    }

    private ObjectNode loaderEvidence(ObjectNode frozen) {
        var evidence = successfulSummary().put("inputSourceSha256", "b".repeat(64)).put("bundleSha256", "a".repeat(64));
        evidence.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        var loader = evidence.putObject("loaderValidation").put("authority", "host_loader_contract_observed")
                .put("projection", ManagedLoaderReceipt.PROJECTION).put("scope", ManagedLoaderReceipt.SCOPE)
                .put("requirementsFrozen", true).put("inputSourceSha256", "b".repeat(64))
                .put("inputProfileSha256", frozen.path("baseline").path("profileSha256").asText());
        var nodes = loader.putArray("nodes");
        nodes.addObject().put("id", "S06").put("status", "pass").put("reason", "PRE_EXEC_SOURCE_SHA_AND_INIT_RETURNED");
        nodes.addObject().put("id", "C01").put("status", "pass").put("reason", "HOME_CATEGORY_CONTRACT");
        return evidence;
    }

    private ObjectNode staticReport() {
        var report = mapper.createObjectNode().put("staticValidationPassed", true);
        var nodes = report.putArray("nodes");
        for (String id : List.of("S01", "S02", "S03"))
            nodes.addObject().put("id", id).put("status", "pass").put("reason", "CANDIDATE_STATIC_CHECK");
        return report;
    }

    private ObjectNode successfulSummary() {
        var evidence = mapper.createObjectNode();
        evidence.set("untrustedRuntimeSummary", summary(true).put("playerStatus", "returned")
                .put("mediaStatus", "prefix_returned"));
        evidence.putArray("deniedCodes");
        return evidence;
    }

    private ObjectNode report(ObjectNode before, ObjectNode after) {
        var frozen = mapper.createObjectNode();
        frozen.putObject("baseline").putObject("result").putObject("managedSourceEvidence")
                .set("untrustedRuntimeSummary", before);
        var evidence = mapper.createObjectNode();
        evidence.set("untrustedRuntimeSummary", after);
        evidence.putArray("deniedCodes").add("EGRESS_NONPUBLIC_TARGET_DENIED");
        return reports.runtime(frozen, mapper.createObjectNode().put("staticValidationPassed", true), evidence);
    }

    private ObjectNode summary(boolean recovered) {
        var value = mapper.createObjectNode().put("status", recovered ? "detail_returned" : "home_empty");
        for (String field : List.of("homeClasses", "homeItems", "homeCovers", "categoryItems", "categoryCovers",
                "page2Items", "detailItems", "detailCovers", "playGroups", "playEpisodes"))
            value.put(field, recovered || !field.startsWith("home") ? 20 : 0);
        return value;
    }
}
