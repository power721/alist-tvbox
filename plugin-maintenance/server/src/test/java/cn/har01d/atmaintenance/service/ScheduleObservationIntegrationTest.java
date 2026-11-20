package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.ProbeRun;
import cn.har01d.atmaintenance.repository.ProbeRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:schedule-observation-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=observation-test-only", "maintenance.at.origin=",
        "maintenance.scheduling.enabled=false", "maintenance.model.enabled=false",
        "maintenance.media.enabled=false", "maintenance.container.enabled=false"
})
class ScheduleObservationIntegrationTest {
    static final Instant NOW = Instant.parse("2030-02-14T12:00:00Z");
    static final String PROFILE = "{\"requiredNodes\":[\"S01\",\"S02\"]}";
    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary Clock observationClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @Autowired ScheduleObservationService observations;
    @Autowired ProbeRunRepository runs;
    @Autowired ProbeStore store;
    @Autowired SourceService sources;
    @Autowired ProbeScheduleStore schedules;
    @Autowired StaticInspector inspector;
    @Autowired KnowledgeService knowledge;
    @Autowired ExportService exports;
    @Autowired SourceImportService imports;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;

    @Test
    void utcWindowExcludesManualOtherSourcesAndFutureRowsWithoutChangingState() {
        var template = template();
        var policy = schedules.update(template.getSourceId(),
                new UpdateScheduleRequest(null, false, "static", 1800, 2, null));
        var old = row(template, 1, "pass");
        old.setCreatedAt(Instant.parse("2030-01-31T23:59:59Z"));
        var future = row(template, 2, "pass");
        future.setCreatedAt(NOW.plusSeconds(1));
        var manual = row(template, 3, "pass");
        manual.setTriggerKind("manual");
        var first = row(template, 4, "pass");
        first.setCreatedAt(Instant.parse("2030-02-01T00:00:00Z"));
        first.setFinishedAt(first.getCreatedAt().plusSeconds(60));
        first.setScheduleSha256(policy.sha256());
        var last = row(template, 5, "blocked");
        var foreign = row(template(), 6, "pass");
        runs.saveAllAndFlush(List.of(old, future, manual, first, last, foreign));
        var scheduleBefore = schedules.get(template.getSourceId());
        var budgetBefore = schedules.global();
        long countBefore = runs.count();
        var result = observations.get(template.getSourceId());
        assertEquals("2030-02-01T00:00:00Z", result.path("windowStart").asText());
        assertEquals(14, result.path("days").size());
        assertEquals(2, result.path("totals").path("attempts").asInt());
        assertEquals(1, result.path("totals").path("declared_checks_passed").asInt());
        assertEquals(1, result.path("totals").path("declared_checks_incomplete").asInt());
        assertEquals(1, result.path("currentPolicyTotals").path("attempts").asInt());
        assertEquals(last.getId(), result.path("runs").get(0).path("id").asText());
        assertEquals(2, result.path("observedUtcDays").asInt());
        assertEquals(1, result.path("days").get(0).path("attempts").asInt());
        assertFalse(result.path("trialAccepted").asBoolean());
        assertFalse(result.path("publishEligible").asBoolean());
        assertEquals(countBefore, runs.count());
        assertEquals(scheduleBefore, schedules.get(template.getSourceId()));
        assertEquals(budgetBefore, schedules.global());
    }

    @Test
    void regressionComparisonRequiresSamePolicyRevisionRuntimeAndRequiredNodeSet() {
        var template = template();
        var baseline = row(template, 1, "pass");
        var regression = row(template, 2, "fail");
        var restored = row(template, 3, "pass");
        var newRevision = row(template, 4, "fail");
        newRevision.setSourceRevision(template.getSourceRevision() + 1);
        var newPolicy = row(template, 5, "fail");
        newPolicy.setScheduleSha256(Hashes.sha256("new-policy"));
        var newRuntime = row(template, 6, "fail");
        newRuntime.setRuntimeSha256(Hashes.sha256("new-runtime"));
        var newRequired = row(template, 7, "fail");
        newRequired.setProfileJson("{\"requiredNodes\":[\"S01\"]}");
        newRequired.setProfileSha256(Hashes.sha256(newRequired.getProfileJson()));
        runs.saveAllAndFlush(List.of(baseline, regression, restored, newRevision, newPolicy, newRuntime, newRequired));
        var result = observations.get(template.getSourceId());
        var regressed = find(result, regression);
        assertEquals(baseline.getId(), regressed.path("comparedRunId").asText());
        assertEquals(List.of("S02"), mapper.convertValue(regressed.path("regressedNodes"), List.class));
        assertEquals(regression.getId(), find(result, restored).path("comparedRunId").asText());
        assertTrue(find(result, restored).path("regressedNodes").isEmpty());
        for (var changed : List.of(newRevision, newPolicy, newRuntime, newRequired))
            assertFalse(find(result, changed).has("comparedRunId"));
        assertEquals(1, result.path("totals").path("regressions").asInt());
    }

    @Test
    void pendingCancellationFailureAndUnconfirmedCleanupStaySeparateFromBusinessResults() {
        var template = template();
        var pending = row(template, 1, "pass");
        pending.setStatus("RUNNING");
        pending.setFinishedAt(null);
        var cancelled = row(template, 2, "pass");
        cancelled.setStatus("CANCELLED");
        cancelled.setErrorCode("USER_CANCELLED");
        var failed = row(template, 3, "pass");
        failed.setStatus("INTERRUPTED");
        failed.setErrorCode("SERVICE_RESTARTED");
        var clean = row(template, 4, "pass");
        managed(clean, true);
        var unclean = row(template, 5, "pass");
        managed(unclean, false);
        unclean.setStatus("FAILED");
        unclean.setErrorCode("MANAGED_CLEANUP_UNCONFIRMED");
        var missing = row(template, 6, "pass");
        managed(missing, true);
        missing.setResultJson("{\"nodes\":[]}");
        var legacy = row(template, 7, "pass");
        legacy.setSourceRevision(null);
        var duplicate = row(template, 8, "pass");
        var report = (ObjectNode) read(duplicate.getResultJson());
        report.withArray("nodes").addObject().put("id", "S02").put("status", "pass");
        duplicate.setResultJson(report.toString());
        runs.saveAllAndFlush(List.of(pending, cancelled, failed, clean, unclean, missing, legacy, duplicate));
        var result = observations.get(template.getSourceId());
        var totals = result.path("totals");
        assertEquals(1, totals.path("pending").asInt());
        assertEquals(1, totals.path("cancelled").asInt());
        assertEquals(1, totals.path("execution_failed").asInt());
        assertEquals(2, totals.path("cleanup_unconfirmed").asInt());
        assertEquals("confirmed", find(result, clean).path("cleanup").asText());
        assertEquals("not_observed", find(result, missing).path("cleanup").asText());
        assertFalse(find(result, legacy).has("comparedRunId"));
        assertEquals(1, find(result, duplicate).path("requiredCoverage").path("not_tested").asInt());
        assertFalse(find(result, unclean).has("comparedRunId"));
        assertFalse(result.toString().contains("PRIVATE_REPORT_MARKER"));
    }

    @Test
    void boundedHistoryReturnsLatest512AndMarksPartialTotals() {
        var template = template();
        List<ProbeRun> history = new ArrayList<>();
        for (int i = 0; i < 513; i++) history.add(row(template, i, "pass"));
        runs.saveAllAndFlush(history);
        var result = observations.get(template.getSourceId());
        assertFalse(result.path("historyComplete").asBoolean());
        assertEquals(512, result.path("runs").size());
        assertEquals(512, result.path("totals").path("attempts").asInt());
        assertEquals(history.getLast().getId(), result.path("runs").get(0).path("id").asText());
        assertTrue(result.path("runs").valueStream().noneMatch(n -> n.path("id").asText().equals(history.getFirst().getId())));
    }

    @Test
    void repairProgressIsShownSeparatelyWithoutTurningTheFailedBaselineGreen() {
        var template = template();
        var run = row(template, 1, "fail");
        run.setMaintenanceKey("scheduled-repair");
        run.setMaintenanceStatus("SUCCEEDED");
        run.setMaintenanceRepairId(UUID.randomUUID().toString());
        runs.saveAndFlush(run);
        var observed = find(observations.get(template.getSourceId()), run);
        assertEquals("SUCCEEDED", observed.path("maintenance").path("status").asText());
        assertEquals(run.getMaintenanceRepairId(), observed.path("maintenance").path("repairId").asText());
        assertEquals("declared_checks_incomplete", observed.path("outcome").asText());
        assertEquals(1, observed.path("requiredCoverage").path("fail").asInt());
        assertFalse(observations.get(template.getSourceId()).path("publishEligible").asBoolean());
    }

    @Test
    void exportHashesHistoryAndImportPreservesOnlyTheArchiveWithoutRestoringJobsOrBudgets() throws Exception {
        var template = template();
        schedules.update(template.getSourceId(), new UpdateScheduleRequest(null, false, "static", 1800, 2, null));
        runs.saveAndFlush(row(template, 1, "pass"));
        byte[] archive = exports.export(template.getSourceId(), null);
        var files = unzip(archive);
        byte[] history = files.get("schedule-observations.json");
        assertNotNull(history);
        assertEquals(Hashes.sha256(history), read(new String(files.get("manifest.json"), StandardCharsets.UTF_8))
                .path("files").path("schedule-observations.json").path("sha256").asText());
        String encoded = Base64.getEncoder().encodeToString(archive);
        assertTrue(imports.preview(new PreviewSourceImportRequest(encoded)).fileCount() > 5);
        var restored = imports.confirm(new ConfirmSourceImportRequest(encoded, Hashes.sha256(archive),
                UUID.randomUUID().toString(), "Archived observation", "restored.py"));
        assertArrayEquals(history, unzip(imports.archive(restored.id())).get("schedule-observations.json"));
        assertTrue(store.list(restored.sourceId()).isEmpty());
        assertNull(schedules.get(restored.sourceId()).sha256());
        assertEquals(0, observations.get(restored.sourceId()).path("totals").path("attempts").asInt());
    }

    @Test
    void observationEndpointRequiresAuthenticationAndReturnsNoRawReportOrSource() throws Exception {
        var template = template();
        runs.saveAndFlush(row(template, 1, "pass"));
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/plugin-maintenance/sources/"
                + template.getSourceId() + "/schedule/observations");
        try (var client = HttpClient.newHttpClient()) {
            assertEquals(401, client.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            String auth = Base64.getEncoder().encodeToString("admin:observation-test-only".getBytes(StandardCharsets.UTF_8));
            var response = client.send(HttpRequest.newBuilder(uri).header("Authorization", "Basic " + auth)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(template.getSourceId(), read(response.body()).path("sourceId").asText());
            assertFalse(response.body().contains("PRIVATE_REPORT_MARKER"));
            assertFalse(response.body().contains("MUST_NOT_EXECUTE"));
        }
    }

    private ProbeRun template() {
        var source = sources.create(new CreateSourceRequest("Observation fixture", null, "fixture.py", null,
                "raise RuntimeError('MUST_NOT_EXECUTE')\nclass Spider: pass\n"));
        return store.create(source.id(), inspector.sha256(), knowledge.sha256());
    }

    private ProbeRun row(ProbeRun template, int minute, String state) {
        var run = new ProbeRun();
        run.setSourceId(template.getSourceId());
        run.setSourceRevision(template.getSourceRevision());
        run.setArtifactId(template.getArtifactId());
        run.setArtifactSha256(template.getArtifactSha256());
        run.setInspectorSha256(template.getInspectorSha256());
        run.setKnowledgeSha256(template.getKnowledgeSha256());
        run.setProfileJson(PROFILE);
        run.setProfileSha256(Hashes.sha256(PROFILE));
        run.setScheduleSha256(Hashes.sha256("observation-policy"));
        run.setScheduleBudgetDay(LocalDate.of(2030, 2, 14));
        run.setScheduleOrdinal(minute + 1);
        run.setTriggerKind("scheduled");
        run.setStatus("COMPLETED");
        run.setCreatedAt(NOW.minusSeconds(36000).plusSeconds(minute * 60L));
        run.setFinishedAt(run.getCreatedAt().plusSeconds(1));
        var report = mapper.createObjectNode().put("privateBody", "PRIVATE_REPORT_MARKER");
        var nodes = report.putArray("nodes");
        nodes.addObject().put("id", "S01").put("status", "pass");
        nodes.addObject().put("id", "S02").put("status", state);
        run.setResultJson(report.toString());
        return run;
    }

    private void managed(ProbeRun run, boolean confirmed) {
        run.setExecutionMode("managed_source");
        run.setRuntimeSha256(Hashes.sha256("runtime"));
        run.setExecutionPolicySha256(Hashes.sha256("execution-policy"));
        var report = (ObjectNode) read(run.getResultJson());
        report.putObject("managedSourceEvidence").putObject("cleanup")
                .put("confirmed", confirmed).put("containerAbsent", confirmed);
        run.setResultJson(report.toString());
    }

    private JsonNode find(JsonNode result, ProbeRun run) {
        return result.path("runs").valueStream().filter(n -> n.path("id").asText().equals(run.getId()))
                .findFirst().orElseThrow();
    }

    private JsonNode read(String json) {
        try { return mapper.readTree(json); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), zip.readAllBytes());
        }
        return files;
    }
}
