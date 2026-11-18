package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:maintenance-schedule-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only", "maintenance.at.origin=",
        "maintenance.scheduling.enabled=false", "maintenance.scheduling.daily-limit=3"
})
class ProbeScheduleIntegrationTest {
    static final MutableClock CLOCK = new MutableClock();
    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary Clock testSchedulingClock() { return CLOCK; }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> value = new AtomicReference<>();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        public Instant instant() { return value.get(); }
        void at(Instant value) { this.value.set(value); }
    }
    @Autowired ProbeScheduleStore schedules;
    @Autowired ProbeScheduleRepository scheduleRepository;
    @Autowired ScheduleBudgetRepository budgetRepository;
    @Autowired ProbeStore runs;
    @Autowired ProbeService probes;
    @Autowired SourceService sources;
    @Autowired ProfileService profiles;
    @Autowired ExportService exports;
    @Autowired ObjectMapper mapper;
    @Autowired StaticInspector inspector;
    @Autowired KnowledgeService knowledge;
    @Autowired ProbeRunRepository runRepository;
    @MockitoSpyBean ManagedSourceRuntime managed;
    @MockitoSpyBean ModelClient modelClient;
    @Autowired ModelStore models;
    @MockitoBean MaintenanceWorkflowService workflow;
    private ModelConnection modelConnection;
    @LocalServerPort int port;

    @BeforeEach
    void reset() {
        CLOCK.at(Instant.parse("2030-01-01T10:00:00Z"));
        runs.interruptUnfinished();
        scheduleRepository.deleteAll();
        var budget = budgetRepository.findById(1).orElseThrow();
        budget.setBudgetDay(LocalDate.of(2030, 1, 1));
        budget.setUsedAttempts(0);
        budgetRepository.saveAndFlush(budget);
        doReturn(null).when(managed).blockedReason(any());
        modelConnection = mock(ModelConnection.class);
        when(modelConnection.configurationSha256()).thenReturn("a".repeat(64));
        when(modelConnection.autoSubmitEnabled()).thenReturn(true);
        doNothing().when(modelClient).requireEnabled();
        doReturn(modelConnection).when(modelClient).connection();
    }

    @Test
    void chosenStartTimePersistsAndCannotClearCoolingOrBudget() {
        String source = source();
        var approved = approval(source);
        Instant start = CLOCK.instant().plusSeconds(600);
        var saved = schedules.update(source, new UpdateScheduleRequest(null, true, "managed_source", 1800, 2,
                new ManagedScheduleApproval(approved.id(), approved.sourceRevision()), null, start));
        assertEquals(start, saved.nextDueAt());
        assertEquals(start.toString(), schedules.get(source).policy().path("firstRunAt").asText());
        CLOCK.at(start.minusSeconds(1));
        assertNull(schedules.claim(source));
        CLOCK.at(start);
        var run = schedules.claim(source);
        assertNotNull(run);
        runs.finish(run.getId(), "FAILED", null, null, "RUNNER_TEMPORARILY_UNAVAILABLE");
        schedules.reconcile(source);
        var held = schedules.get(source);
        var changed = schedules.update(source, new UpdateScheduleRequest(saved.sha256(), true, "managed_source", 3600, 2,
                null, null, CLOCK.instant().plusSeconds(60)));
        assertFalse(changed.nextDueAt().isBefore(held.nextDueAt()));
        assertEquals(1, changed.usedAttempts());
        assertEquals(1, changed.consecutiveFailures());
        assertEquals("SCHEDULE_START_TIME_IN_PAST", assertThrows(ResponseStatusException.class, () ->
                schedules.update(source, new UpdateScheduleRequest(changed.sha256(), true, "managed_source", 3600, 2,
                        null, null, CLOCK.instant().minusSeconds(1)))).getReason());
    }

    @Test
    void reservationFreezesPolicyAndExportKeepsCurrentAndHistoricalPolicies() throws Exception {
        String source = source();
        assertNull(schedules.get(source).sha256());
        var policy = configure(source, "static", 4);
        assertTrue(policy.nextDueAt().isAfter(CLOCK.instant().plusSeconds(1799)));
        assertTrue(policy.nextDueAt().isBefore(CLOCK.instant().plusSeconds(2161)));
        assertNull(schedules.claim(source));
        CLOCK.at(policy.nextDueAt().plusSeconds(1));
        var run = schedules.claim(source);
        assertNotNull(run);
        assertEquals("scheduled", run.getTriggerKind());
        assertEquals(policy.sha256(), run.getScheduleSha256());
        assertEquals(1, run.getScheduleOrdinal());
        assertEquals(LocalDate.of(2030, 1, 1), run.getScheduleBudgetDay());
        assertEquals(1, schedules.global().usedAttempts());
        assertEquals(run.getId(), schedules.get(source).activeRunId());
        assertNull(schedules.claim(source));
        probes.submit(run);
        var complete = await(run.getId());
        assertEquals("COMPLETED", complete.status());
        assertEquals(policy.policy(), complete.schedule());
        schedules.reconcile(source);
        assertEquals("declared_checks_passed", schedules.get(source).lastOutcome());
        assertNull(schedules.get(source).activeRunId());
        var changed = schedules.update(source, new UpdateScheduleRequest(policy.sha256(), true, "static", 3600, 2, null));
        assertEquals(1, changed.usedAttempts());
        assertEquals(policy.sha256(), runs.get(run.getId()).scheduleSha256());
        try (var zip = new ZipInputStream(new ByteArrayInputStream(exports.export(source, null)))) {
            Map<String, byte[]> files = new HashMap<>();
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), zip.readAllBytes());
            assertEquals(changed.sha256(), Hashes.sha256(files.get("schedule-policy.json")));
            assertEquals(policy.sha256(), Hashes.sha256(files.get("probe-schedule-policy.json")));
            assertEquals(1, mapper.readTree(files.get("schedule-state.json")).path("usedAttempts").asInt());
        }
    }

    @Test
    void concurrentClaimsRespectGlobalAndSourceCapsWithoutRefundOnEditingOrCancellation() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String source = source();
            ids.add(source);
            configure(source, "static", 1);
        }
        CLOCK.at(CLOCK.instant().plusSeconds(2200));
        CountDownLatch start = new CountDownLatch(1);
        List<cn.har01d.atmaintenance.entity.ProbeRun> accepted = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<cn.har01d.atmaintenance.entity.ProbeRun>> pending = new ArrayList<>();
            for (String id : ids) for (int i = 0; i < 2; i++) pending.add(pool.submit(() -> {
                start.await();
                return schedules.claim(id);
            }));
            start.countDown();
            for (var task : pending) {
                var run = task.get(10, TimeUnit.SECONDS);
                if (run != null) accepted.add(run);
            }
        }
        assertEquals(3, accepted.size());
        assertEquals(3, accepted.stream().map(r -> r.getSourceId()).distinct().count());
        assertEquals(3, schedules.global().usedAttempts());
        for (var run : accepted) {
            probes.cancel(run.getId());
            schedules.reconcile(run.getSourceId());
            var current = schedules.get(run.getSourceId());
            var disabled = schedules.update(run.getSourceId(), new UpdateScheduleRequest(
                    current.sha256(), false, "static", 1800, 1, null));
            var enabled = schedules.update(run.getSourceId(), new UpdateScheduleRequest(
                    disabled.sha256(), true, "static", 1800, 1, null));
            assertEquals(1, enabled.usedAttempts());
            assertEquals(1, enabled.consecutiveFailures());
        }
        CLOCK.at(CLOCK.instant().plusSeconds(9000));
        for (String id : ids) assertNull(schedules.claim(id));
        assertEquals(3, schedules.global().usedAttempts());
    }

    @Test
    void incompleteDeclaredChecksAndFailuresBackOffWithoutBeingReportedAsHealthy() throws Exception {
        String source = source();
        configure(source, "static", 24);
        var profile = profiles.get(source);
        profiles.update(source, new UpdateProfileRequest(profile.sha256(), "t4", "unverified",
                List.of("S01", "S02", "S03", "C01"), List.of(), null, null));
        for (int failure = 1; failure <= 4; failure++) {
            var current = schedules.get(source);
            CLOCK.at(current.nextDueAt().plusSeconds(1));
            if (failure == 4) CLOCK.at(Instant.parse("2030-01-02T20:00:00Z"));
            Instant reserved = CLOCK.instant();
            var run = schedules.claim(source);
            assertNotNull(run);
            if (failure == 1) {
                probes.submit(run);
                assertEquals("required_incomplete", await(run.getId()).result()
                        .path("requiredCoverage").path("conclusion").asText());
            } else runs.finish(run.getId(), "FAILED", null, null, "SYNTHETIC_FAILURE");
            schedules.reconcile(source);
            var outcome = schedules.get(source);
            assertEquals(failure, outcome.consecutiveFailures());
            assertNotEquals("declared_checks_passed", outcome.lastOutcome());
            int minimum = switch (failure) { case 1 -> 1800; case 2 -> 7200; case 3 -> 21600; default -> 86400; };
            assertFalse(outcome.nextDueAt().isBefore(reserved.plusSeconds(minimum)));
            assertNull(schedules.claim(source));
        }
    }

    @Test
    void unsupportedExecutionAndUnconfirmedCleanupSuspendUntilExplicitResume() throws Exception {
        String source = source();
        configure(source, "offline_fixture", 4);
        CLOCK.at(schedules.get(source).nextDueAt().plusSeconds(1));
        var run = schedules.claim(source);
        probes.submit(run);
        assertEquals("BLOCKED", await(run.getId()).status());
        schedules.reconcile(source);
        var held = schedules.get(source);
        assertTrue(held.suspended());
        assertEquals("TRUSTED_FIXTURE_REQUIRED", held.suspensionReason());
        CLOCK.at(CLOCK.instant().plusSeconds(172800));
        assertNull(schedules.claim(source));
        var resumed = schedules.resume(source, held.sha256());
        assertFalse(resumed.suspended());
        assertEquals(1, resumed.usedAttempts(), "Resume must not erase the last budget ledger");
        assertEquals(1, resumed.consecutiveFailures());
        CLOCK.at(resumed.nextDueAt().plusSeconds(1));
        var cleanup = schedules.claim(source);
        runs.finish(cleanup.getId(), "FAILED", null, null, "INSPECTOR_CLEANUP_FAILED");
        schedules.reconcile(source);
        assertEquals("INSPECTOR_CLEANUP_FAILED", schedules.get(source).suspensionReason());
    }

    @Test
    void committedButNotEnqueuedWorkIsRecoveredWithoutDuplicateReservation() {
        String source = source();
        configure(source, "static", 4);
        CLOCK.at(schedules.get(source).nextDueAt().plusSeconds(1));
        var lost = schedules.claim(source);
        assertEquals("QUEUED", runs.get(lost.getId()).status());
        runs.interruptUnfinished();
        schedules.reconcile(source);
        var recovered = schedules.get(source);
        assertEquals(lost.getId(), recovered.lastRunId());
        assertEquals("SERVICE_RESTARTED", recovered.lastOutcome());
        assertNull(recovered.activeRunId());
        assertEquals(1, recovered.usedAttempts());
        assertNull(schedules.claim(source));
        CLOCK.at(recovered.nextDueAt().plusSeconds(1));
        var next = schedules.claim(source);
        assertNotEquals(lost.getId(), next.getId());
        assertEquals(2, schedules.get(source).usedAttempts());
    }

    @Test
    void utcDayRolloverResetsCapacityButClockRollbackCannotGrantCapacityAgain() {
        String source = source();
        configure(source, "static", 1);
        CLOCK.at(schedules.get(source).nextDueAt().plusSeconds(1));
        var first = schedules.claim(source);
        probes.cancel(first.getId());
        schedules.reconcile(source);
        CLOCK.at(schedules.get(source).nextDueAt().plusSeconds(1));
        assertNull(schedules.claim(source), "Per-source daily cap is independent of global remaining capacity");
        CLOCK.at(Instant.parse("2030-01-02T10:00:00Z"));
        var second = schedules.claim(source);
        assertNotNull(second);
        assertEquals(1, schedules.get(source).usedAttempts());
        assertEquals(1, schedules.global().usedAttempts());
        probes.cancel(second.getId());
        schedules.reconcile(source);
        CLOCK.at(Instant.parse("2030-01-01T23:00:00Z"));
        assertNull(schedules.claim(source));
        assertEquals(LocalDate.of(2030, 1, 2), schedules.global().budgetDay());
        assertEquals(1, schedules.global().usedAttempts());
    }

    @Test
    void coordinatorRunsCommittedJobsAndPollingDoesNotResubmitThem() throws Exception {
        String source = source();
        configure(source, "static", 4);
        CLOCK.at(schedules.get(source).nextDueAt().plusSeconds(1));
        var coordinator = new ProbeScheduler(schedules, probes);
        coordinator.poll();
        String id = schedules.get(source).activeRunId();
        assertNotNull(id);
        assertEquals("scheduled", await(id).triggerKind());
        coordinator.poll();
        coordinator.poll();
        assertNull(schedules.get(source).activeRunId());
        assertEquals(id, schedules.get(source).lastRunId());
        assertEquals(1, schedules.global().usedAttempts());
        assertEquals(1, runs.list(source).size());
    }

    @Test
    void httpConfigurationRequiresAuthenticationCsrfAndValidatedLimits() throws Exception {
        String source = source();
        String base = "http://127.0.0.1:" + port + "/api/plugin-maintenance";
        String path = "/sources/" + source + "/schedule";
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                "admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
        try (var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            for (String uri : List.of("/scheduling", path)) {
                assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(base + uri)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            for (String uri : List.of(path, path + "/resume")) {
                assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + uri))
                        .header("Authorization", auth).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            var csrf = mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/csrf"))
                    .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            var request = HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", auth)
                    .header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText());
            for (String body : List.of(
                    "{\"enabled\":true,\"mode\":\"static\",\"intervalSeconds\":1,\"dailyLimit\":1}",
                    "{\"enabled\":true,\"mode\":\"static\",\"intervalSeconds\":1800,\"dailyLimit\":25}",
                    "{\"enabled\":true,\"mode\":\"managed_source\",\"intervalSeconds\":1800,\"dailyLimit\":1,\"approval\":{\"approvedRunId\":\"bad\",\"expectedSourceRevision\":0}}",
                    "{\"enabled\":true,\"mode\":\"managed_source\",\"intervalSeconds\":1800,\"dailyLimit\":1,\"approval\":{\"approvedRunId\":\"00000000-0000-0000-0000-000000000000\",\"expectedSourceRevision\":-1}}",
                    "{\"enabled\":true,\"mode\":\"PRIVATE_INVALID_MARKER\",\"intervalSeconds\":1800,\"dailyLimit\":1}")) {
                var invalid = http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(400, invalid.statusCode());
                assertEquals("{\"code\":\"INVALID_REQUEST\"}", invalid.body());
            }
            var saved = http.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                    new UpdateScheduleRequest(null, true, "static", 1800, 1, null)))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, saved.statusCode(), saved.body());
            var stale = http.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                    new UpdateScheduleRequest(null, false, "static", 1800, 1, null)))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, stale.statusCode());
            assertEquals("{\"code\":\"SCHEDULE_CHANGED\"}", stale.body());
        }
    }

    @Test
    void managedScheduleRequiresExplicitCurrentManualReceiptAndConfirmedCleanup() throws Exception {
        String source = source();
        assertConflict("MANAGED_SCHEDULE_APPROVAL_REQUIRED", () -> schedules.update(source,
                new UpdateScheduleRequest(null, true, "managed_source", 1800, 1, null)));
        for (String defect : List.of("source", "mode", "trigger", "status", "static", "cleanup", "revision")) {
            var approved = approval(source);
            var run = runRepository.findById(approved.id()).orElseThrow();
            switch (defect) {
                case "source" -> run.setSourceId(source());
                case "mode" -> run.setExecutionMode("static");
                case "trigger" -> run.setTriggerKind("scheduled");
                case "status" -> run.setStatus("BLOCKED");
                case "revision" -> run.setSourceRevision(null);
                default -> {
                    var report = mapper.createObjectNode();
                    report.putArray("nodes").addObject().put("id", "S03")
                            .put("status", defect.equals("static") ? "fail" : "pass");
                    report.putObject("managedSourceEvidence").putObject("cleanup")
                            .put("confirmed", !defect.equals("cleanup")).put("containerAbsent", true);
                    run.setResultJson(report.toString());
                }
            }
            runRepository.saveAndFlush(run);
            assertThrows(ResponseStatusException.class, () -> approveSchedule(source, approved));
        }
        assertNull(schedules.get(source).sha256());
        assertEquals(0, schedules.global().usedAttempts());
        verify(managed, never()).inspect(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void managedReservationFreezesApprovalAndUsesExistingExecutorWithoutRequiringHealthyBusiness() throws Exception {
        String source = source();
        var profile = profiles.get(source);
        profiles.update(source, new UpdateProfileRequest(profile.sha256(), "t4", "unverified",
                List.of("S01", "S02", "S03", "C01"), List.of(), null, null));
        var approved = approval(source);
        var policy = approveSchedule(source, approved);
        CLOCK.at(policy.nextDueAt().plusSeconds(1));
        var run = schedules.claim(source);
        assertNotNull(run);
        assertEquals(approved.sourceRevision(), run.getSourceRevision());
        assertEquals(approved.id(), mapper.readTree(run.getScheduleJson()).path("approval").path("approvedRunId").asText());
        assertNull(schedules.executionBlockedReason(run));
        var evidence = mapper.createObjectNode();
        evidence.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        doReturn(evidence).when(managed).inspect(eq(run.getId()), anyString(), any(), anyString(), anyString(), any());
        probes.submit(run);
        assertEquals("COMPLETED", await(run.getId()).status());
        verify(managed).inspect(eq(run.getId()), eq(sources.artifact(source, approved.artifactSha256()).getBody()),
                eq(approved.profile()), eq(approved.runtimeSha256()), eq(approved.executionPolicySha256()), any());
        schedules.reconcile(source);
        assertNotEquals("declared_checks_passed", schedules.get(source).lastOutcome());
        assertFalse(schedules.get(source).suspended(), "Business failure remains eligible for bounded retries");
        assertEquals(1, schedules.global().usedAttempts());
    }

    @Test
    void sourceAbaAndProfileDriftSuspendBeforeBudgetReservationAndOldApprovalCannotResume() {
        for (boolean aba : List.of(true, false)) {
            String source = source();
            var approved = approval(source);
            var policy = approveSchedule(source, approved);
            if (aba) {
                var next = sources.addArtifact(source, new AddArtifactRequest(approved.artifactSha256(),
                        "class Spider:\n    pass\n"));
                sources.addArtifact(source, new AddArtifactRequest(next.artifactSha256(),
                        sources.artifact(source, approved.artifactSha256()).getBody()));
            } else {
                var profile = profiles.get(source);
                profiles.update(source, new UpdateProfileRequest(profile.sha256(), "t4", "changed",
                        List.of("S01", "S02", "S03"), List.of(), null, null));
            }
            CLOCK.at(policy.nextDueAt().plusSeconds(1));
            assertNull(schedules.claim(source));
            var held = schedules.get(source);
            assertTrue(held.suspended());
            assertEquals("MANAGED_SCHEDULE_INPUTS_CHANGED", held.suspensionReason());
            assertEquals(0, held.usedAttempts());
            assertConflict("MANAGED_SCHEDULE_INPUTS_CHANGED", () -> schedules.resume(source, held.sha256()));
            assertThrows(ResponseStatusException.class, () -> approveSchedule(source, approved));
        }
        assertEquals(0, schedules.global().usedAttempts());
    }

    @Test
    void runtimePolicyAnalyzerKnowledgeAndBindingIdentityChangesRejectFrozenApproval() {
        for (String field : List.of("runtime", "policy", "inspector", "knowledge", "binding")) {
            String source = source();
            var approved = approval(source);
            var policy = approveSchedule(source, approved);
            var run = runRepository.findById(approved.id()).orElseThrow();
            switch (field) {
                case "runtime" -> run.setRuntimeSha256("b".repeat(64));
                case "policy" -> run.setExecutionPolicySha256("b".repeat(64));
                case "inspector" -> run.setInspectorSha256("b".repeat(64));
                case "knowledge" -> run.setKnowledgeSha256("b".repeat(64));
                case "binding" -> run.setBindingSha256("b".repeat(64));
            }
            runRepository.saveAndFlush(run);
            CLOCK.at(policy.nextDueAt().plusSeconds(1));
            assertNull(schedules.claim(source), field);
            assertTrue(schedules.get(source).suspended(), field);
        }
        assertEquals(0, schedules.global().usedAttempts());
    }

    @Test
    void queuedManagedRunRechecksDriftAndReapprovalPreservesBudgetBackoffAndDeadline() throws Exception {
        String source = source();
        var approved = approval(source);
        var policy = approveSchedule(source, approved);
        CLOCK.at(policy.nextDueAt().plusSeconds(1));
        var queued = schedules.claim(source);
        var profile = profiles.get(source);
        profiles.update(source, new UpdateProfileRequest(profile.sha256(), "t4", "changed",
                List.of("S01", "S02", "S03"), List.of(), null, null));
        probes.submit(queued);
        assertEquals("MANAGED_SCHEDULE_INPUTS_CHANGED", await(queued.getId()).errorCode());
        verify(managed, never()).inspect(anyString(), anyString(), any(), anyString(), anyString());
        schedules.reconcile(source);
        var held = schedules.get(source);
        assertTrue(held.suspended());
        var changed = approveSchedule(source, approval(source));
        var resumed = schedules.resume(source, changed.sha256());
        assertEquals(1, resumed.usedAttempts());
        assertEquals(1, resumed.consecutiveFailures());
        assertFalse(resumed.nextDueAt().isBefore(held.nextDueAt()));
        assertFalse(resumed.suspended());
    }

    @Test
    void disablingQueuedManagedWorkDoesNotExecuteOrSuspendReplacementPolicy() throws Exception {
        String source = source();
        var policy = approveSchedule(source, approval(source));
        CLOCK.at(policy.nextDueAt().plusSeconds(1));
        var queued = schedules.claim(source);
        var disabled = schedules.update(source, new UpdateScheduleRequest(policy.sha256(), false,
                "managed_source", 1800, 2, null));
        probes.submit(queued);
        assertEquals("MANAGED_SCHEDULE_APPROVAL_CHANGED", await(queued.getId()).errorCode());
        schedules.reconcile(source);
        assertFalse(schedules.get(source).suspended());
        assertEquals(disabled.sha256(), schedules.get(source).sha256());
        assertEquals(1, schedules.get(source).usedAttempts());
        verify(managed, never()).inspect(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void cleanupFailureRequiresNewCleanManualApprovalAndConfigurationFailurePausesWithoutBudget() {
        String source = source();
        var policy = approveSchedule(source, approval(source));
        CLOCK.at(policy.nextDueAt().plusSeconds(1));
        var run = schedules.claim(source);
        runs.finish(run.getId(), "FAILED", null, null, "MANAGED_CLEANUP_UNCONFIRMED");
        schedules.reconcile(source);
        var held = schedules.get(source);
        assertTrue(held.suspended());
        assertConflict("MANAGED_SCHEDULE_FRESH_APPROVAL_REQUIRED", () -> schedules.resume(source, held.sha256()));
        var replaced = approveSchedule(source, approval(source));
        var resumed = schedules.resume(source, replaced.sha256());
        assertEquals(1, resumed.usedAttempts());
        doReturn("MANAGED_EXECUTION_DISABLED").when(managed).blockedReason(any());
        CLOCK.at(resumed.nextDueAt().plusSeconds(1));
        assertNull(schedules.claim(source));
        assertEquals("MANAGED_EXECUTION_DISABLED", schedules.get(source).suspensionReason());
        assertEquals(1, schedules.get(source).usedAttempts());
        assertConflict("MANAGED_EXECUTION_DISABLED", () -> schedules.resume(source, replaced.sha256()));
    }

    // Synthetic trusted host receipts test only scheduling gates; no source or broker executes.
    @Test
    void automaticRepairIsExplicitLiveOnlyAndRequiresExistingSourceConsent() {
        String source = source();
        var approved = approval(source);
        assertConflict("MANAGED_SCHEDULE_REPAIR_REQUIRES_LIVE_MODE", () -> schedules.update(source,
                new UpdateScheduleRequest(null, true, "static", 1800, 2, null,
                        new ScheduleRepairApproval("a".repeat(64)))));
        assertConflict("MODEL_SOURCE_NOT_AUTHORIZED", () -> repairSchedule(source, approved));
        assertNull(schedules.get(source).sha256());
        models.consent(source, new UpdateModelConsentRequest(null, approved.artifactSha256()));
        var policy = repairSchedule(source, approved);
        CLOCK.at(policy.nextDueAt().plusSeconds(1));
        var run = schedules.claim(source);
        assertNotNull(run);
        assertEquals("CHECKING", runs.maintenance(run.getId()).status());
        assertEquals("a".repeat(64), run.getMaintenanceConfigurationSha256());
        assertEquals(approved.profileSha256(), run.getProfileSha256());
        runs.finish(run.getId(), "COMPLETED", mapper.createObjectNode(), null, null);
        schedules.reconcile(source);
        assertEquals(run.getId(), schedules.get(source).activeRunId());
        runs.finishMaintenance(run.getId(), "REPAIRING", "synthetic-repair", null);
        schedules.reconcile(source);
        assertEquals(run.getId(), schedules.get(source).activeRunId());
        assertEquals(1, schedules.get(source).usedAttempts());
        runs.finishMaintenance(run.getId(), "SUCCEEDED", "synthetic-repair", null);
        schedules.reconcile(source);
        assertNull(schedules.get(source).activeRunId());
        assertEquals("repair_candidate_verified", schedules.get(source).lastOutcome());
        assertNotEquals("declared_checks_passed", schedules.get(source).lastOutcome());
    }

    @Test
    void modelDriftOrConsentRevocationSuspendsBeforeAnyNewReservation() {
        for (boolean revoke : List.of(false, true)) {
            when(modelConnection.configurationSha256()).thenReturn("a".repeat(64));
            String source = source();
            var approved = approval(source);
            models.consent(source, new UpdateModelConsentRequest(null, approved.artifactSha256()));
            var policy = repairSchedule(source, approved);
            if (revoke) models.consent(source, new UpdateModelConsentRequest(approved.artifactSha256(), null));
            else when(modelConnection.configurationSha256()).thenReturn("b".repeat(64));
            CLOCK.at(policy.nextDueAt().plusSeconds(1));
            assertNull(schedules.claim(source));
            var held = schedules.get(source);
            assertTrue(held.suspended());
            assertEquals(revoke ? "MODEL_SOURCE_NOT_AUTHORIZED" : "MODEL_CONFIGURATION_CHANGED",
                    held.suspensionReason());
            assertEquals(0, held.usedAttempts());
        }
        assertEquals(0, schedules.global().usedAttempts());
    }

    @Test
    void manualMaintenanceDefersScheduledReservationWithoutChargingOrCreatingAnotherTask() {
        String source = source();
        var approved = approval(source);
        var policy = approveSchedule(source, approved);
        var manual = runRepository.findById(approved.id()).orElseThrow();
        manual.setMaintenanceKey("synthetic-manual");
        manual.setMaintenanceStatus("REPAIRING");
        runRepository.saveAndFlush(manual);
        CLOCK.at(policy.nextDueAt().plusSeconds(1));
        assertNull(schedules.claim(source));
        assertEquals(0, schedules.global().usedAttempts());
        assertEquals(1, runs.list(source).size());
        runs.finishMaintenance(manual.getId(), "CANCELLED", null, "USER_CANCELLED");
    }

    private ScheduleSummary repairSchedule(String source, ProbeSummary approved) {
        return schedules.update(source, new UpdateScheduleRequest(schedules.get(source).sha256(), true,
                "managed_source", 1800, 2, new ManagedScheduleApproval(approved.id(), approved.sourceRevision()),
                new ScheduleRepairApproval("a".repeat(64))));
    }

    private ProbeSummary approval(String source) {
        var run = runs.create(source, inspector.sha256(), knowledge.sha256(), "managed_source");
        var report = mapper.createObjectNode();
        report.putArray("nodes").addObject().put("id", "S03").put("status", "pass");
        report.withArray("nodes").addObject().put("id", "C01").put("status", "fail");
        report.putObject("managedSourceEvidence").putObject("cleanup")
                .put("confirmed", true).put("containerAbsent", true);
        runs.finish(run.getId(), "COMPLETED", report, null, null);
        return runs.get(run.getId());
    }

    private ScheduleSummary approveSchedule(String source, ProbeSummary run) {
        return schedules.update(source, new UpdateScheduleRequest(schedules.get(source).sha256(), true,
                "managed_source", 1800, 2, new ManagedScheduleApproval(run.id(), run.sourceRevision())));
    }

    private void assertConflict(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ResponseStatusException.class, action).getReason());
    }

    private String source() {
        return sources.create(new CreateSourceRequest("巡检夹具", null, "fixture.py", null,
                "raise RuntimeError('submitted source must not execute')\nclass Spider: pass\n")).id();
    }
    private ScheduleSummary configure(String id, String mode, int daily) {
        return schedules.update(id, new UpdateScheduleRequest(null, true, mode, 1800, daily, null));
    }
    private ProbeSummary await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var run = runs.get(id);
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(run.status())) return run;
            Thread.sleep(20);
        }
        throw new AssertionError("Scheduled probe did not finish");
    }
}
