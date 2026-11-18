package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.ModelCall;
import cn.har01d.atmaintenance.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:maintenance-model-source-budget-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only", "maintenance.at.origin=",
        "maintenance.scheduling.enabled=false", "maintenance.model.enabled=true",
        "maintenance.model.endpoint=http://127.0.0.1:9/v1/chat/completions",
        "maintenance.model.model=fixture-model", "maintenance.model.key-env=",
        "maintenance.model.daily-call-limit=100", "maintenance.model.daily-input-bytes=26214400",
        "maintenance.model.daily-output-tokens=1638400",
        "maintenance.model.source-daily-call-limit=3", "maintenance.model.source-daily-input-bytes=131072",
        "maintenance.model.source-daily-output-tokens=6144",
        "maintenance.model.model-daily-call-limit=100", "maintenance.model.model-daily-input-bytes=26214400",
        "maintenance.model.model-daily-output-tokens=1638400"
})
class ModelSourceBudgetIntegrationTest {
    static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2030-01-01T10:00:00Z"));
    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary Clock modelBudgetClock() {
            return new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
                public Instant instant() { return NOW.get(); }
            };
        }
    }
    @Autowired SourceService sources;
    @Autowired ProbeService probes;
    @Autowired ProbeStore runs;
    @Autowired RepairStore repairs;
    @Autowired ModelStore models;
    @Autowired ModelClient client;
    @Autowired ModelBudgetRepository budgets;
    @Autowired ModelSourceBudgetRepository sourceBudgets;
    @Autowired ModelNamedBudgetRepository namedBudgets;
    @Autowired ModelCallRepository calls;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;

    @BeforeEach
    void reset() {
        NOW.set(Instant.parse("2030-01-01T10:00:00Z"));
        models.recover();
        sourceBudgets.deleteAll();
        namedBudgets.deleteAll();
        var row = budgets.findById(1).orElseThrow();
        row.setBudgetDay(null);
        row.setUsedCalls(0);
        row.setReservedInputBytes(0);
        row.setReservedOutputTokens(0);
        row.setReservedCostUsd(null);
        budgets.saveAndFlush(row);
    }

    @Test
    void bothBudgetsAndImmutableSnapshotCommitTogetherAndCancellationDoesNotRefund() throws Exception {
        var repair = begin();
        assertNull(models.sourceBudget(repair.sourceId()).budgetDay());
        assertFalse(sourceBudgets.existsById(repair.sourceId())); // GET has no write side effect.
        var call = reserve(repair, "budget-snapshot-001");
        var value = models.get(call.getId());
        var source = models.sourceBudget(repair.sourceId());
        assertEquals(1, source.usedCalls());
        assertEquals(value.requestBytes(), source.reservedInputBytes());
        assertEquals(value.reservedOutputTokens(), source.reservedOutputTokens());
        assertEquals(models.budget().reservedInputBytes(), source.reservedInputBytes());
        assertEquals(repair.sourceId(), value.budgetReservation().path("sourceId").asText());
        assertEquals(3, value.budgetReservation().path("sourceLimits").path("calls").asInt());
        assertEquals("2030-01-01", value.budgetReservation().path("budgetDay").asText());
        models.cancel(call.getId());
        models.recover();
        assertEquals(source, models.sourceBudget(repair.sourceId()));
        assertEquals(value.budgetReservation(), models.get(call.getId()).budgetReservation());
    }

    @Test
    void sourceQuotaSurvivesNewArtifactAndFaultWhileOtherSourcesKeepTheirOwnCapacity() throws Exception {
        var repair = begin();
        ModelCall first = null;
        for (int i = 0; i < 3; i++) {
            var call = reserve(repair, "source-quota-00" + i);
            if (first == null) first = call;
            models.cancel(call.getId());
        }
        var before = models.budget();
        assertEquals(first.getId(), reserve(repair, "source-quota-000").getId());
        var next = revise(repair);
        var error = assertThrows(ResponseStatusException.class, () -> reserve(next, "new-fault-001"));
        assertEquals(429, error.getStatusCode().value());
        assertEquals("MODEL_SOURCE_DAILY_BUDGET", error.getReason());
        assertEquals(before, models.budget());
        assertTrue(models.list(next.id()).isEmpty());
        var other = begin();
        models.cancel(reserve(other, "other-source-001").getId());
        assertEquals(1, models.sourceBudget(other.sourceId()).usedCalls());
        assertEquals(3, models.sourceBudget(repair.sourceId()).usedCalls());
    }

    @Test
    void sourceInputAndOutputExhaustionRejectWithoutChargingEitherScope() throws Exception {
        var repair = begin();
        models.cancel(reserve(repair, "seed-limits-001").getId());
        var original = models.sourceBudget(repair.sourceId());
        var global = models.budget();
        for (int mode = 0; mode < 2; mode++) {
            var row = sourceBudgets.findById(repair.sourceId()).orElseThrow();
            row.setReservedInputBytes(mode == 0 ? original.dailyInputBytes() : original.reservedInputBytes());
            row.setReservedOutputTokens(mode == 1 ? original.dailyOutputTokens() : original.reservedOutputTokens());
            sourceBudgets.saveAndFlush(row);
            var snapshot = models.sourceBudget(repair.sourceId());
            String key = "byte-token-limit-00" + mode;
            assertCode("MODEL_SOURCE_DAILY_BUDGET", () -> reserve(repair, key));
            assertEquals(snapshot, models.sourceBudget(repair.sourceId()));
            assertEquals(global, models.budget());
        }
        assertEquals(1, models.list(repair.id()).size());
    }

    @Test
    void utcRolloverResetsBothScopesOnceAndBackwardTimeCannotGrantAnotherDay() throws Exception {
        NOW.set(Instant.parse("2030-01-01T23:59:59Z"));
        var repair = begin();
        models.cancel(reserve(repair, "before-midnight-001").getId());
        NOW.set(Instant.parse("2030-01-02T00:00:00Z"));
        models.cancel(reserve(repair, "after-midnight-001").getId());
        var source = models.sourceBudget(repair.sourceId());
        assertEquals(LocalDate.of(2030, 1, 2), source.budgetDay());
        assertEquals(1, source.usedCalls());
        assertEquals(1, models.budget().usedCalls());
        var global = models.budget();
        NOW.set(Instant.parse("2030-01-01T23:59:59Z"));
        assertCode("MODEL_CLOCK_ROLLBACK", () -> reserve(repair, "backward-time-001"));
        assertEquals(source, models.sourceBudget(repair.sourceId()));
        assertEquals(global, models.budget());
        assertEquals(2, models.list(repair.id()).size());
    }

    @Test
    void sourceDayAheadOfGlobalDayAlsoRejectsAndRollsBackGlobalChanges() throws Exception {
        var repair = begin();
        models.cancel(reserve(repair, "source-future-001").getId());
        var row = sourceBudgets.findById(repair.sourceId()).orElseThrow();
        row.setBudgetDay(LocalDate.of(2030, 1, 3));
        sourceBudgets.saveAndFlush(row);
        var source = models.sourceBudget(repair.sourceId());
        var global = models.budget();
        NOW.set(Instant.parse("2030-01-02T00:00:00Z"));
        assertCode("MODEL_CLOCK_ROLLBACK", () -> reserve(repair, "source-future-002"));
        assertEquals(source, models.sourceBudget(repair.sourceId()));
        assertEquals(global, models.budget());
    }

    @Test
    void concurrentRetrySharesOneChargeAndSourceQuotaStopsANewFault() throws Exception {
        var repair = begin();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<ModelCall> work = () -> { start.await(); return reserve(repair, "same-key-001"); };
            var a = pool.submit(work);
            var b = pool.submit(work);
            start.countDown();
            assertEquals(a.get(5, TimeUnit.SECONDS).getId(), b.get(5, TimeUnit.SECONDS).getId());
        }
        var first = models.list(repair.id()).getFirst();
        assertEquals(1, models.sourceBudget(repair.sourceId()).usedCalls());
        models.cancel(first.id());
        models.cancel(reserve(repair, "last-slot-seed-001").getId());
        var finalCall = reserve(repair, "last-slot-001");
        models.cancel(finalCall.getId());
        var next = revise(repair);
        assertCode("MODEL_SOURCE_DAILY_BUDGET", () -> reserve(next, "over-limit-001"));
        assertEquals(3, models.sourceBudget(repair.sourceId()).usedCalls());
        assertEquals(3, models.budget().usedCalls());
    }

    @Test
    void rollbackAfterFlushLeavesNoCallOrQuotaAndTheSameKeyCanThenCommit() throws Exception {
        var repair = begin();
        var global = models.budget();
        String discarded = new TransactionTemplate(transactions).execute(status -> {
            var call = reserve(repair, "rollback-key-001");
            assertEquals(1, models.sourceBudget(repair.sourceId()).usedCalls());
            assertEquals(1, models.budget().usedCalls());
            status.setRollbackOnly();
            return call.getId();
        });
        assertFalse(calls.existsById(discarded));
        assertFalse(sourceBudgets.existsById(repair.sourceId()));
        assertEquals(global, models.budget());
        var accepted = reserve(repair, "rollback-key-001");
        assertNotEquals(discarded, accepted.getId());
        assertEquals(1, models.sourceBudget(repair.sourceId()).usedCalls());
        models.cancel(accepted.getId());
    }

    @Test
    void sourceBudgetEndpointRequiresAdminAndUnknownSourceDoesNotCreateRows() throws Exception {
        var repair = begin();
        String base = "http://127.0.0.1:" + port + "/api/plugin-maintenance/sources/";
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                "admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
        try (var http = HttpClient.newHttpClient()) {
            assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(base + repair.sourceId() + "/model-budget"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var response = http.send(HttpRequest.newBuilder(URI.create(base + repair.sourceId() + "/model-budget"))
                    .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"dailyCallLimit\":3"));
            assertFalse(response.body().contains("requestJson"));
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + UUID.randomUUID() + "/model-budget"))
                    .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals(0, sourceBudgets.count());
    }

    private ModelCall reserve(RepairSummary repair, String key) {
        return models.reserve(repair.id(), new StartModelCallRequest(repair.faultSha256(),
                client.connection().configurationSha256(), key));
    }
    private RepairSummary begin() throws Exception {
        var source = sources.create(new CreateSourceRequest("源级预算", null, "repair.py", null, RepairIntegrationTest.BAD));
        return repair(source);
    }
    private RepairSummary revise(RepairSummary repair) throws Exception {
        var source = sources.addArtifact(repair.sourceId(), new AddArtifactRequest(
                repair.baselineArtifactSha256(), RepairIntegrationTest.BAD + "\n# next fault\n"));
        return repair(source);
    }
    private RepairSummary repair(SourceSummary source) throws Exception {
        var probe = probes.start(source.id());
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (Set.of("QUEUED", "RUNNING").contains(runs.get(probe.id()).status())) {
            assertTrue(System.nanoTime() < until);
            Thread.sleep(20);
        }
        var baseline = runs.get(probe.id());
        assertEquals("COMPLETED", baseline.status(),
                () -> "Baseline probe status=" + baseline.status() + " error=" + baseline.errorCode());
        var repair = repairs.create(source.id(), new StartRepairRequest(probe.id(), source.artifactSha256()));
        var consent = models.consent(source.id());
        models.consent(source.id(), new UpdateModelConsentRequest(consent.artifactSha256(), source.artifactSha256()));
        return repair;
    }
    private void assertCode(String code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(ResponseStatusException.class, work).getReason());
    }
}
