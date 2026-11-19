package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.RepairSummary;
import cn.har01d.atmaintenance.dto.RepairCandidateSummary;
import cn.har01d.atmaintenance.dto.ReverseAnalysisRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReverseAnalysisTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MAINTENANCE_PYTHON", matches = ".+")
    void scriptWorkerRunsWithoutBrowserAndDoesNotForwardSensitivePageQuery() throws Exception {
        var service = new ReverseAnalysis(mapper, mock(RepairStore.class), mock(SourceService.class),
                "", "node-must-not-run", "", System.getenv("MAINTENANCE_PYTHON"), "", "");
        String page = "https://fixture.example.com/play?token=private-value";
        var payload = mapper.createObjectNode().put("scriptInspectionOnly", true)
                .put("repairId", "repair").put("runId", "run").put("artifactSha256", "a".repeat(64))
                .put("pageUrl", page).put("observedPageUrlSha256", Hashes.sha256(page));
        var report = service.observe(payload);
        ReverseAnalysis.requireScriptOperation(report);
        assertEquals("REVERSE_SENSITIVE_QUERY_NOT_FORWARDED", report.at("/failures/0/code").asText());
        assertTrue(report.path("requests").isEmpty());
        assertFalse(report.toString().contains("private-value"));
        assertTrue(report.path("cleanupConfirmed").asBoolean());
    }

    @Test void scriptReadinessDoesNotRequireBrowserAndCannotClaimExecutedConversion() throws Exception {
        var service = new ReverseAnalysis(mapper, mock(RepairStore.class), mock(SourceService.class),
                "", "node-must-not-run", "", "python", "", "");
        assertTrue(service.readiness().path("scriptInspectionReady").asBoolean());
        assertFalse(service.readiness().path("ready").asBoolean());
        var report = mapper.createObjectNode().put("authority", "host_public_scripts_observed")
                .put("operation", "script_resource_inspection").put("browserStarted", false)
                .put("sourceCodeExecuted", false).put("consumerExecutionVerified", false).put("transformationVerified", false);
        report.putArray("scripts");
        report.putArray("requests");
        ReverseAnalysis.requireScriptOperation(report);
        assertThrows(java.io.IOException.class, () -> ReverseAnalysis.requireOperation(report, false));
        report.put("transformationVerified", true);
        assertThrows(java.io.IOException.class, () -> ReverseAnalysis.requireScriptOperation(report));
    }

    @Test void scriptInspectionRejectsUnobservedPageBeforeNetworkOrStorage() {
        var store = mock(RepairStore.class);
        var sources = mock(SourceService.class);
        var frozen = mapper.createObjectNode();
        var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                "STOPPED", "ROUND_LIMIT", 4, 4, null, Instant.now(), frozen, "current", List.of());
        when(store.get("repair")).thenReturn(repair);
        when(store.candidates("repair")).thenReturn(List.of());
        var artifact = new cn.har01d.atmaintenance.entity.MaintenanceArtifact();
        artifact.setBody("HOST='https://fixture.example.com'");
        when(sources.artifact("source", "a".repeat(64))).thenReturn(artifact);
        var service = spy(new ReverseAnalysis(mapper, store, sources, "", "node", "", "python", "", ""));
        var failure = assertThrows(ResponseStatusException.class, () -> service.inspectScripts("repair",
                new ReverseAnalysisRequest("b".repeat(64), "https://fixture.example.com/play/guess", "", true)));
        assertEquals("REVERSE_OBSERVED_PAGE_REQUIRED", failure.getReason());
        verify(service, never()).observe(any());
        verify(store, never()).attachReverseAnalysis(anyString(), anyString(), any());
    }

    @Test void suppliedScriptMustMatchThisObservedPagesRecordedScriptHash() {
        String page = "https://fixture.example.com/play/123", script = "https://fixture.example.com/play.js?v=2";
        var store = mock(RepairStore.class);
        var sources = mock(SourceService.class);
        var frozen = mapper.createObjectNode();
        var baseline = frozen.putObject("baseline").putObject("result").putObject("managedSourceEvidence")
                .put("authority", "managed_source_host_observed").put("jobId", "run").put("inputSourceSha256", "a".repeat(64));
        var resources = baseline.putObject("resourceEvidence").put("artifactSha256", "a".repeat(64)).putArray("resources");
        resources.addObject().put("kind", "page").put("urlSha256", Hashes.sha256(page));
        resources.addObject().put("kind", "script").put("urlSha256", Hashes.sha256(script))
                .put("parentUrlSha256", Hashes.sha256(page));
        baseline.putArray("httpObservations").addObject().put("method", "GET").put("requestUrlSha256", Hashes.sha256(page))
                .put("responseStatus", 200).put("responseComplete", true);
        var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                "STOPPED", "ROUND_LIMIT", 4, 4, null, Instant.now(), frozen, "current", List.of());
        when(store.get("repair")).thenReturn(repair);
        when(store.candidates("repair")).thenReturn(List.of());
        var artifact = new cn.har01d.atmaintenance.entity.MaintenanceArtifact();
        artifact.setBody("HOST='https://fixture.example.com'");
        when(sources.artifact("source", "a".repeat(64))).thenReturn(artifact);
        var service = spy(new ReverseAnalysis(mapper, store, sources, "", "node", "", "python", "", ""));
        var input = new ReverseAnalysisRequest("b".repeat(64), page, "", true);
        var error = assertThrows(ResponseStatusException.class,
                () -> service.inspectScripts("repair", input, List.of(script + "&guess=1")));
        assertEquals("REVERSE_OBSERVED_SCRIPT_REQUIRED", error.getReason());
        doReturn(mapper.createObjectNode()).when(service).observe(any());
        when(store.attachReverseAnalysis(anyString(), anyString(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        service.inspectScripts("repair", input, List.of(script));
        verify(service).observe(argThat(payload -> payload.path("observedScriptUrls").get(0).asText().equals(script)));
        assertEquals(4, repair.usedRounds());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MAINTENANCE_PYTHON", matches = ".+")
    void originInspectionRunsFromBundledPythonWithoutNodeOrBrowser() throws Exception {
        var service = new ReverseAnalysis(mapper, mock(RepairStore.class), mock(SourceService.class),
                "", "node-must-not-run", "", System.getenv("MAINTENANCE_PYTHON"), "", "");
        assertFalse(service.readiness().path("ready").asBoolean());
        assertTrue(service.originReady());
        var payload = mapper.createObjectNode().put("originInspectionOnly", true)
                .put("repairId", "repair").put("runId", "run").put("artifactSha256", "a".repeat(64))
                .put("sourceContent", "HOST='https://fixture.example.com'\nraise RuntimeError('MUST_NOT_EXECUTE')")
                .put("pageUrl", "");
        var report = service.observe(payload);
        ReverseAnalysis.requireOperation(report, true);
        assertEquals("source_literal_origin", report.path("pageSourceBinding").asText());
        assertEquals(Hashes.sha256("https://fixture.example.com/"), report.path("pageUrlSha256").asText());
        assertFalse(report.path("browserStarted").asBoolean(true));
        assertFalse(report.path("sourceCodeExecuted").asBoolean(true));
        assertTrue(report.path("cleanupConfirmed").asBoolean());
        assertEquals("no_declared_alternate_origin", report.path("originPool").path("nextAction").asText());
    }

    @Test void publishedOriginContinuationIsAutomaticButStopsAfterOnePublishedHop() {
        var report = mapper.createObjectNode();
        var pool = report.putObject("originPool").put("inspectionStage", "source_declared_hosts");
        var redirect = pool.putArray("observations").addObject().put("status", "redirect").put("httpStatus", 301);
        assertTrue(ReverseAnalysis.requiresPublishedOriginFollowup(report));
        pool.put("inspectionStage", "published_redirects");
        assertFalse(ReverseAnalysis.requiresPublishedOriginFollowup(report));
        pool.put("inspectionStage", "source_declared_hosts");
        redirect.put("httpStatus", 302);
        assertFalse(ReverseAnalysis.requiresPublishedOriginFollowup(report));
        redirect.put("httpStatus", 308);
        assertTrue(ReverseAnalysis.requiresPublishedOriginFollowup(report));
    }

    @Test void publishedRedirectHintsNeedThisRepairsHostReceiptAndAreNotRecursivelyFollowed() {
        var frozen = mapper.createObjectNode();
        var report = frozen.putObject("reverseAnalysis").put("authority", "host_source_origin_observed")
                .put("repairId", "repair").put("runId", "run").put("artifactSha256", "a".repeat(64))
                .put("cleanupConfirmed", true);
        var pool = report.putObject("originPool");
        pool.putArray("observations").addObject().put("status", "redirect").put("httpStatus", 301)
                .put("origin", "https://primary.example").put("redirectOrigin", "https://published.example")
                .put("requestUrlSha256", "c".repeat(64)).put("redirectUrlSha256", "d".repeat(64))
                .put("unneededBody", "not forwarded");
        var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                "STOPPED", "REPEATED_FAILURE", 3, 3, null, Instant.now(), frozen, "current", List.of());
        assertEquals(1, ReverseAnalysis.publishedRedirects(repair).size());
        assertFalse(ReverseAnalysis.publishedRedirects(repair).get(0).has("unneededBody"));
        pool.put("inspectionStage", "published_redirects");
        assertTrue(ReverseAnalysis.publishedRedirects(repair).isEmpty());
        pool.remove("inspectionStage");
        report.put("runId", "foreign");
        assertTrue(ReverseAnalysis.publishedRedirects(repair).isEmpty());
    }

    @Test void originInspectionCannotClaimBrowserEvidenceOrACompletedRepair() throws Exception {
        var report = mapper.createObjectNode().put("authority", "host_source_origin_observed")
                .put("operation", "source_origin_inspection").put("browserStarted", false);
        var pool = report.putObject("originPool").put("selectionApplied", false).put("businessChainVerified", false);
        ReverseAnalysis.requireOperation(report, true);
        assertThrows(java.io.IOException.class, () -> ReverseAnalysis.requireOperation(report, false));
        report.put("authority", "host_reverse_browser_observed");
        assertThrows(java.io.IOException.class, () -> ReverseAnalysis.requireOperation(report, true));
        report.put("authority", "host_source_origin_observed");
        pool.put("selectionApplied", true);
        assertThrows(java.io.IOException.class, () -> ReverseAnalysis.requireOperation(report, true));
    }

    @Test void challengedReplaySchedulesDeclaredOriginsInspectionBeforeAnotherBrowserReplay() {
        var service = spy(new ReverseAnalysis(mapper, mock(RepairStore.class), mock(SourceService.class),
                "", "node", "", "python", "", ""));
        doReturn(mapper.createObjectNode().put("ready", true)).when(service).readiness();
        doReturn(mapper.createObjectNode()).when(service).inspectOrigins(anyString(), any());
        var frozen = mapper.createObjectNode();
        frozen.putObject("policy").putArray("targetNodes").add("R01");
        var previous = frozen.putObject("reverseAnalysis");
        previous.putObject("targetPage").put("challengeObserved", true).put("status", "challenge_pending");
        var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                "AWAITING_PROPOSAL", null, 1, 3, null, Instant.now(), frozen, "current", List.of());
        service.prepare(repair);
        verify(service).inspectOrigins(eq("repair"), argThat(request -> repair.faultSha256().equals(request.expectedFaultSha256())));
        verify(service, never()).capture(anyString(), any());
        previous.remove("targetPage");
        previous.put("operation", "source_origin_inspection").putObject("originPool")
                .put("nextAction", "no_usable_declared_alternate_origin");
        service.prepare(repair);
        verify(service, times(1)).inspectOrigins(anyString(), any());
    }

    @Test void newEvidenceNeedsCompleteNewSourceNotJustANewTaskIdentifier() {
        var report = mapper.createObjectNode().put("mode", "reverse_analysis_v1")
                .put("authority", "host_reverse_browser_observed").put("repairId", "repair").put("runId", "run")
                .put("artifactSha256", "a".repeat(64)).put("taskId", java.util.UUID.randomUUID().toString())
                .put("cleanupConfirmed", true);
        String body = "define(['services/http'], function(http) { return http; });";
        var script = report.putArray("scripts").addObject().put("sourceSha256", Hashes.sha256(body));
        var excerpt = script.putObject("excerpt").put("complete", true).put("text", body);
        var frozen = mapper.createObjectNode();
        frozen.set("reverseAnalysis", report);
        var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                "STOPPED", "REPEATED_FAILURE", 2, 3, null, Instant.now(), frozen, "current", List.of());
        var prior = mapper.createObjectNode().put("taskId", java.util.UUID.randomUUID().toString());
        assertTrue(ReverseAnalysis.newMaterial(repair, prior));
        prior.set("scripts", report.path("scripts").deepCopy());
        assertFalse(ReverseAnalysis.newMaterial(repair, prior));
        prior.remove("scripts");
        excerpt.put("complete", false);
        assertFalse(ReverseAnalysis.newMaterial(repair, prior));
        excerpt.put("complete", true).put("text", body + "changed");
        assertFalse(ReverseAnalysis.newMaterial(repair, prior));
        excerpt.put("text", body);
        report.put("cleanupConfirmed", false);
        assertFalse(ReverseAnalysis.newMaterial(repair, prior));
        report.put("cleanupConfirmed", true).put("runId", "another-run");
        assertFalse(ReverseAnalysis.newMaterial(repair, prior));
        assertFalse(ReverseAnalysis.newMaterial(repair, ReverseAnalysis.modelReport(mapper, "not-json")));
    }

    @Test void automaticReplaySelectsTheLatestObservedPageWithoutGuessingAnEndpointOrMethod() {
        String page = "https://canonical.example/player/1/?a=1&b=2", sha = Hashes.sha256(page);
        var frozen = mapper.createObjectNode();
        var baseline = frozen.putObject("baseline").putObject("result").putObject("managedSourceEvidence")
                .put("authority", "managed_source_host_observed").put("jobId", "run")
                .put("inputSourceSha256", "a".repeat(64));
        baseline.putObject("resourceEvidence").put("artifactSha256", "a".repeat(64))
                .putArray("resources").addObject().put("kind", "page").put("urlSha256", sha);
        var http = baseline.putArray("httpObservations").addObject().put("method", "GET")
                .put("requestUrlSha256", sha).put("responseStatus", 200).put("responseComplete", true);
        var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                "AWAITING_PROPOSAL", null, 1, 3, null, Instant.now(), frozen, "current", List.of());
        var result = mapper.createObjectNode().put("artifactSha256", "c".repeat(64))
                .put("baselineArtifactSha256", "a".repeat(64));
        var observed = result.putObject("runtimeEvidence").put("authority", "managed_source_host_observed")
                .put("jobId", "candidate").put("inputSourceSha256", "c".repeat(64));
        var pageResource = observed.putObject("resourceEvidence").put("artifactSha256", "c".repeat(64))
                .putArray("resources").addObject().put("kind", "page").put("urlSha256", sha);
        pageResource.putArray("excerpts").addObject().put("text",
                "partial JSON: {\"@id\":\"https://canonical.example/player/1/?a=1&amp;b=2#episode\"}");
        var candidate = new RepairCandidateSummary("candidate", "repair", 1, "c".repeat(64), "d".repeat(64),
                "FAILED", null, Instant.now(), Instant.now(), null, result, null, null, null);
        var request = ReverseAnalysis.automaticRequest(repair, List.of(candidate));
        assertEquals(page, request.pageUrl());
        assertEquals("", request.requestPattern());
        assertEquals(repair.faultSha256(), request.expectedFaultSha256());
        assertTrue(request.recoverScripts());
        assertEquals(1, repair.usedRounds());
        http.put("responseComplete", false);
        assertEquals("", ReverseAnalysis.automaticRequest(repair, List.of(candidate)).pageUrl());
        http.put("responseComplete", true);
        observed.put("inputSourceSha256", "e".repeat(64));
        assertEquals("", ReverseAnalysis.automaticRequest(repair, List.of(candidate)).pageUrl());
        observed.put("inputSourceSha256", "c".repeat(64)).put("jobId", "another-job");
        assertEquals("", ReverseAnalysis.automaticRequest(repair, List.of(candidate)).pageUrl());
        result.put("runtimeJobId", "another-job");
        assertEquals(page, ReverseAnalysis.automaticRequest(repair, List.of(candidate)).pageUrl());
        observed.put("jobId", "candidate");
        assertEquals("", ReverseAnalysis.automaticRequest(repair, List.of(candidate)).pageUrl());
    }

    @Test void automaticReplayPreservesExistingEvidenceWhenNoCurrentCandidatePageIsBound() {
        var repairs = mock(RepairStore.class);
        var service = spy(new ReverseAnalysis(mapper, repairs, mock(SourceService.class),
                "", "node", "", "python", "", ""));
        doReturn(mapper.createObjectNode().put("ready", true)).when(service).readiness();
        var frozen = mapper.createObjectNode();
        frozen.putObject("policy").putArray("targetNodes").add("R01");
        frozen.putObject("reverseAnalysis").put("pageUrlSha256", "a".repeat(64));
        var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                "AWAITING_PROPOSAL", null, 1, 3, null, Instant.now(), frozen, "current", List.of());
        when(repairs.candidates("repair")).thenReturn(List.of());
        assertEquals("", ReverseAnalysis.automaticRequest(repair, List.of()).pageUrl());
        service.prepare(repair);
        verify(service, never()).capture(anyString(), any());
        verify(repairs).candidates("repair");
    }

    @Test void emptyAutomaticTargetKeepsOriginDiscoveryInsteadOfThrowing() {
        var repair = mock(RepairSummary.class);
        when(repair.faultSha256()).thenReturn("f".repeat(64));
        when(repair.frozen()).thenReturn(mapper.createObjectNode());
        var input = ReverseAnalysis.automaticRequest(repair, List.of());
        assertEquals("", input.pageUrl());
        assertEquals("", ReverseAnalysis.observedPageSha(repair, input.pageUrl()));
        for (String value : List.of("", "/relative", "//example.com/page", "example.com/page"))
            assertFalse(ReverseAnalysis.publicUrl(value), value);
    }

    @Test void recoveryTargetsRelativeLinksFromTheCurrentHostObservedActionEvenWhenChallenged() {
        String page = "https://canonical.example/videos/one/", sha = Hashes.sha256(page);
        var frozen = mapper.createObjectNode();
        frozen.putObject("policy").putArray("targetNodes").add("R01");
        var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                "STOPPED", "REPEATED_FAILURE", 3, 3, null, Instant.now(), frozen, "current", List.of());
        var result = mapper.createObjectNode().put("artifactSha256", "c".repeat(64))
                .put("baselineArtifactSha256", "a".repeat(64)).put("runtimeJobId", "revalidation-job");
        var evidence = result.putObject("runtimeEvidence").put("authority", "managed_source_host_observed")
                .put("jobId", "revalidation-job").put("inputSourceSha256", "c".repeat(64));
        var resources = evidence.putObject("resourceEvidence").put("artifactSha256", "c".repeat(64)).putArray("resources");
        var homepage = resources.addObject().put("kind", "page").put("urlSha256", Hashes.sha256("https://canonical.example/"));
        homepage.putObject("observedDuringCall").put("method", "home");
        homepage.putArray("excerpts").addObject().put("text", "<a href='/videos/one/'>One</a>");
        var target = resources.addObject().put("kind", "page").put("urlSha256", sha).put("responseStatus", 403);
        target.putObject("observedDuringCall").put("method", "play");
        var http = evidence.putArray("httpObservations").addObject().put("method", "GET")
                .put("requestUrlSha256", sha).put("responseStatus", 403).put("responseComplete", true);
        var candidate = new RepairCandidateSummary("candidate", "repair", 3, "c".repeat(64), "d".repeat(64),
                "REJECTED", null, Instant.now(), Instant.now(), null, result, null, null, null);
        var candidates = List.of(candidate);
        String source = "host = 'https://canonical.example'";
        assertEquals(page, ReverseAnalysis.automaticRequest(repair, candidates, source).pageUrl());
        assertEquals(sha, ReverseAnalysis.requestedPageSha(repair, candidates, page));
        assertEquals("", ReverseAnalysis.observedPageSha(repair, page));
        assertEquals("", ReverseAnalysis.requestedPageSha(repair, candidates, page + "?unobserved=1"));
        http.put("method", "POST");
        assertEquals("", ReverseAnalysis.automaticRequest(repair, candidates, source).pageUrl());
        http.put("method", "GET").put("responseComplete", false);
        assertEquals("", ReverseAnalysis.requestedPageSha(repair, candidates, page));
        http.put("responseComplete", true);
        evidence.put("jobId", "foreign-job");
        assertEquals("", ReverseAnalysis.requestedPageSha(repair, candidates, page));
        assertEquals("", ReverseAnalysis.automaticRequest(repair, candidates, source).pageUrl());
        assertEquals(3, repair.usedRounds());
    }

    @Test void challengeScriptsAloneNeverUnlockAnotherEnhancedModelAttempt() {
        var report = mapper.createObjectNode().put("mode", "reverse_analysis_v1")
                .put("authority", "host_reverse_browser_observed").put("repairId", "repair").put("runId", "run")
                .put("artifactSha256", "a".repeat(64)).put("cleanupConfirmed", true)
                .put("taskId", "01900000-0000-7000-8000-000000000001");
        String script = "window.challengeFixture = true;";
        report.putArray("scripts").addObject().put("sourceSha256", Hashes.sha256(script))
                .putObject("excerpt").put("complete", true).put("text", script);
        var page = report.putObject("targetPage").put("challengeObserved", true).put("status", "challenge_pending");
        var frozen = mapper.createObjectNode();
        frozen.set("reverseAnalysis", report);
        var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                "STOPPED", "REPEATED_FAILURE", 3, 3, null, Instant.now(), frozen, "current", List.of());
        var previous = mapper.createObjectNode().put("taskId", "01900000-0000-7000-8000-000000000000");
        assertFalse(ReverseAnalysis.newMaterial(repair, previous));
        page.put("status", "content_observed");
        assertTrue(ReverseAnalysis.newMaterial(repair, previous));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MAINTENANCE_REVERSE_FIXTURE", matches = ".+")
    void automaticReplayConsumesAnExistingPlatformReceiptWithoutReexecutingItsSource() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var receipt = json.readTree(Files.readAllBytes(Path.of(System.getenv("MAINTENANCE_REVERSE_FIXTURE"))));
        var repair = json.treeToValue(receipt.path("repair"), RepairSummary.class);
        var candidates = new java.util.ArrayList<RepairCandidateSummary>();
        for (var item : receipt.path("candidates")) candidates.add(json.treeToValue(item, RepairCandidateSummary.class));
        var selected = ReverseAnalysis.automaticRequest(repair, candidates);
        assertFalse(selected.pageUrl().isEmpty());
        assertEquals(repair.frozen().path("reverseAnalysis").path("pageUrlSha256").asText(),
                Hashes.sha256(selected.pageUrl()));
        assertEquals("", selected.requestPattern());
        assertFalse(ReverseAnalysis.observedPageSha(repair, selected.pageUrl()).isEmpty());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MAINTENANCE_REVERSE_TARGET_FIXTURE", matches = ".+")
    void recoveryPageSelectionConsumesTheExistingReceiptAndBaselineLiteralsWithoutRunningSpiderCode() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var receipt = json.readTree(Files.readAllBytes(Path.of(System.getenv("MAINTENANCE_REVERSE_TARGET_FIXTURE"))));
        var repair = json.treeToValue(receipt.path("repair"), RepairSummary.class);
        var candidates = new ArrayList<RepairCandidateSummary>();
        for (JsonNode candidate : receipt.path("candidates")) candidates.add(json.treeToValue(candidate, RepairCandidateSummary.class));
        var request = ReverseAnalysis.automaticRequest(repair, candidates, receipt.path("sourceContent").asText());
        assertTrue(request.pageUrl().isEmpty(), "This receipt needs the existing Python AST resolver, not URL guessing");
        assertFalse(ReverseAnalysis.replayTargets(repair, candidates).isEmpty());
        assertFalse(ReverseAnalysis.replayHints(repair, candidates).isEmpty());
        assertEquals("", request.requestPattern());
        assertEquals(3, repair.usedRounds());
    }

    @Test void canonicalPageBindingRequiresThisRunsCompleteHttpAndPageResource() {
        String page = "https://canonical.example/player/1/", sha = Hashes.sha256(page);
        var frozen = mapper.createObjectNode();
        var evidence = frozen.putObject("baseline").putObject("result").putObject("managedSourceEvidence")
                .put("authority", "managed_source_host_observed").put("jobId", "run")
                .put("inputSourceSha256", "a".repeat(64));
        var resources = evidence.putObject("resourceEvidence").put("artifactSha256", "a".repeat(64));
        var resource = resources.putArray("resources").addObject().put("kind", "page").put("urlSha256", sha);
        var http = evidence.putArray("httpObservations").addObject().put("method", "GET")
                .put("requestUrlSha256", sha).put("responseStatus", 200).put("responseComplete", true);
        var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                "STOPPED", "ROUND_LIMIT", 3, 3, null, Instant.now(), frozen, "current", List.of());
        assertEquals(sha, ReverseAnalysis.observedPageSha(repair, page));
        assertEquals("", ReverseAnalysis.observedPageSha(repair, page + "?different=1"));
        http.put("responseComplete", false);
        assertEquals("", ReverseAnalysis.observedPageSha(repair, page));
        http.put("responseComplete", true).put("responseStatus", 302);
        assertEquals("", ReverseAnalysis.observedPageSha(repair, page));
        http.put("responseStatus", 200);
        resource.put("kind", "script");
        assertEquals("", ReverseAnalysis.observedPageSha(repair, page));
        resource.put("kind", "page");
        evidence.put("jobId", "another-run");
        assertEquals("", ReverseAnalysis.observedPageSha(repair, page));
        evidence.put("jobId", "run").put("inputSourceSha256", "c".repeat(64));
        assertEquals("", ReverseAnalysis.observedPageSha(repair, page));
        evidence.put("inputSourceSha256", "a".repeat(64)).put("authority", "untrusted_source_output");
        assertEquals("", ReverseAnalysis.observedPageSha(repair, page));
    }

    @Test void remoteTransportReceivesOnlyTheConfiguredSshEnvironmentWithoutSudoOrModelKeys() throws Exception {
        var ssh = mapper.createObjectNode().put("hostEnv", "TEST_NAS_HOST")
                .put("passwordEnv", "TEST_NAS_PASSWORD").put("sudoPasswordEnv", "TEST_SUDO_PASSWORD");
        var environment = Map.of("HOME", "home", "TEST_NAS_HOST", "configured-host",
                "TEST_NAS_PASSWORD", "private-test-marker", "TEST_SUDO_PASSWORD", "sudo-marker",
                "MODEL_API_KEY", "model-marker");
        assertEquals(Map.of("HOME", "home", "TEST_NAS_HOST", "configured-host",
                "TEST_NAS_PASSWORD", "private-test-marker"),
                ReverseAnalysis.transportEnvironment(ssh, environment::get));
        ssh.put("hostEnv", "invalid-name");
        assertThrows(java.io.IOException.class, () -> ReverseAnalysis.transportEnvironment(ssh, environment::get));
    }

    @Test void runnerHostRequiresItsTransportAndExposesTheSelectedLocation(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("node_modules/playwright-core"));
        Files.createDirectories(root.resolve("node_modules/webcrack"));
        for (String name : List.of("runner.mjs", "browser", "tunnels.json", "tunnel.py"))
            Files.createFile(root.resolve(name));
        var service = new ReverseAnalysis(mapper, mock(RepairStore.class), mock(SourceService.class),
                root.toString(), "node", root.resolve("browser").toString(), "python",
                root.resolve("tunnels.json").toString(), root.resolve("tunnel.py").toString());
        assertFalse(service.readiness().path("ready").asBoolean());
        assertEquals("source_runner_host", service.readiness().path("networkLocation").asText());
        Files.createFile(root.resolve("remote_broker.py"));
        assertTrue(service.readiness().path("ready").asBoolean());
    }

    @Test void nonJavascriptFailuresDoNotLaunchBrowserAndDisabledIsExplicit() {
        var repairs = mock(RepairStore.class);
        var sources = mock(SourceService.class);
        var service = new ReverseAnalysis(mapper, repairs, sources, "", "node", "", "python", "", "");
        var frozen = mapper.createObjectNode();
        frozen.putObject("policy").putArray("targetNodes").add("S06");
        var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                "AWAITING_PROPOSAL", null, 0, 3, null, Instant.now(), frozen, "current", List.of());
        service.prepare(repair);
        verifyNoInteractions(repairs, sources);
        assertFalse(service.readiness().path("ready").asBoolean());
        when(repairs.get("repair")).thenReturn(repair);
        var failure = assertThrows(ResponseStatusException.class, () -> service.capture("repair",
                new ReverseAnalysisRequest("b".repeat(64), "https://example.com/", "", true)));
        assertEquals("REVERSE_ENGINE_NOT_READY", failure.getReason());
        assertFalse(ReverseAnalysis.eligible(repair));
        frozen.withObject("policy").withArray("targetNodes").add("R01");
        assertTrue(ReverseAnalysis.eligible(repair));
    }

    @Test void pageInputIsHttpOnlyAndNeverContainsUserinfoOrControlCharacters() {
        assertTrue(ReverseAnalysis.publicUrl("https://example.com/page?a=1"));
        assertTrue(ReverseAnalysis.publicUrl("http://example.com:8080/a"));
        for (String value : List.of("file:///etc/passwd", "https://user:pass@example.com/",
                "https://example.com/#fragment", "https://example.com/\n", "javascript:alert(1)"))
            assertFalse(ReverseAnalysis.publicUrl(value), value);
    }

    @Test void workerFailureExposesOnlyAnApprovedStageCode() {
        var known = mapper.createObjectNode().put("errorCode", "REVERSE_BROWSER_SETUP_FAILED");
        assertEquals("REVERSE_BROWSER_SETUP_FAILED", assertThrows(ResponseStatusException.class,
                () -> ReverseAnalysis.requireSuccessfulWorker(known)).getReason());
        var unknown = mapper.createObjectNode().put("errorCode", "secret-bearing exception text");
        assertEquals("REVERSE_ANALYSIS_FAILED", assertThrows(ResponseStatusException.class,
                () -> ReverseAnalysis.requireSuccessfulWorker(unknown)).getReason());
        assertDoesNotThrow(() -> ReverseAnalysis.requireSuccessfulWorker(mapper.createObjectNode()));
    }

    @Test void explicitTargetsReuseOnlyTheirOwnEvidenceWhileDefaultUsesTheCurrentReport() {
        var first = mapper.createObjectNode().put("analysisRequestSha256", "a".repeat(64));
        var second = mapper.createObjectNode().put("analysisRequestSha256", "b".repeat(64));
        second.putArray("previousReports").add(first);
        assertSame(second, ReverseAnalysis.cachedReport(second, "c".repeat(64), true));
        assertSame(second, ReverseAnalysis.cachedReport(second, "b".repeat(64), false));
        assertEquals(first, ReverseAnalysis.cachedReport(second, "a".repeat(64), false));
        assertNull(ReverseAnalysis.cachedReport(second, "c".repeat(64), false));
    }

    @Test void completedRepairMayCollectEvidenceWithoutReopeningItsRoundsOrModelBudget() {
        for (String stop : List.of("REPEATED_FAILURE", "ROUND_LIMIT")) {
            var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                    "STOPPED", stop, 3, 3, null, Instant.now(), mapper.createObjectNode(), "current", List.of());
            assertDoesNotThrow(() -> ReverseAnalysis.requireCurrent(repair));
            assertEquals("STOPPED", repair.status());
            assertEquals(3, repair.usedRounds());
        }
        for (String status : List.of("STOPPED", "VALIDATING_RUNTIME", "CANCELLED", "RUNTIME_OBSERVED")) {
            var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                    status, null, 2, 3, null, Instant.now(), mapper.createObjectNode(), "current", List.of());
            assertEquals("REVERSE_REPAIR_NOT_READY", assertThrows(ResponseStatusException.class,
                    () -> ReverseAnalysis.requireCurrent(repair)).getReason());
        }
    }

    @Test void changedWorkerNeverReusesAnOlderCollectionEvenForTheDefaultTarget() {
        var report = mapper.createObjectNode().put("analysisRequestSha256", "a".repeat(64))
                .put("workerSha256", "b".repeat(64));
        assertSame(report, ReverseAnalysis.cachedReport(report, "a".repeat(64), false, "b".repeat(64)));
        assertNull(ReverseAnalysis.cachedReport(report, "a".repeat(64), false, "c".repeat(64)));
        assertNull(ReverseAnalysis.cachedReport(report, "a".repeat(64), true, "c".repeat(64)));
        assertFalse(ReverseAnalysis.cachedReport(report, "a".repeat(64), true, "b".repeat(64)).has("previousReports"));
    }

    @Test void historicalRuntimeChangesAllowOnlyDiagnosticsWithUnchangedSourceAndProfile() {
        for (List<String> changed : List.of(List.of("runtime"), List.of("execution_policy", "inspector"),
                List.of("runtime", "execution_policy", "inspector"))) {
            var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                    "STOPPED", "REPEATED_FAILURE", 3, 3, null, Instant.now(), mapper.createObjectNode(),
                    "runtime_changed", changed);
            assertDoesNotThrow(() -> ReverseAnalysis.requireCurrent(repair));
            assertEquals("runtime_changed", repair.freshness());
            assertEquals(3, repair.usedRounds());
            assertEquals("STOPPED", repair.status());
        }
        for (String freshness : List.of("runtime_changed", "source_changed", "profile_changed", "current")) {
            for (List<String> changed : List.of(List.<String>of(), List.of("source"),
                    List.of("profile"), List.of("runtime", "profile"), List.of("unknown"))) {
                if (freshness.equals("current") && changed.isEmpty()) continue;
                var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                        "STOPPED", "REPEATED_FAILURE", 3, 3, null, Instant.now(), mapper.createObjectNode(),
                        freshness, changed);
                assertEquals("REVERSE_REPAIR_NOT_READY", assertThrows(ResponseStatusException.class,
                        () -> ReverseAnalysis.requireCurrent(repair)).getReason());
            }
        }
    }
}
