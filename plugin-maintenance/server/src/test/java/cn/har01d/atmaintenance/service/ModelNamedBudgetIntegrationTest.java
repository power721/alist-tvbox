package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.ModelCall;
import cn.har01d.atmaintenance.entity.ModelNamedBudget;
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
        "spring.datasource.url=jdbc:h2:mem:maintenance-model-named-budget-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only", "maintenance.at.origin=",
        "maintenance.scheduling.enabled=false", "maintenance.model.enabled=true",
        "maintenance.model.endpoint=http://127.0.0.1:9/v1/chat/completions",
        "maintenance.model.model=fixture-model", "maintenance.model.key-env=",
        "maintenance.model.daily-call-limit=100", "maintenance.model.daily-input-bytes=26214400",
        "maintenance.model.daily-output-tokens=1638400",
        "maintenance.model.source-daily-call-limit=3", "maintenance.model.source-daily-input-bytes=131072",
        "maintenance.model.source-daily-output-tokens=6144",
        "maintenance.model.model-daily-call-limit=4", "maintenance.model.model-daily-input-bytes=131072",
        "maintenance.model.model-daily-output-tokens=8192"
})
class ModelNamedBudgetIntegrationTest {
    static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2030-01-01T10:00:00Z"));
    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary Clock namedBudgetClock() {
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
    void threeScopesCommitWithFrozenLimitsAndCancellationOrRecoveryNeverRefunds() throws Exception {
        var repair = begin();
        assertNull(models.namedBudget().budgetDay());
        assertEquals(0, namedBudgets.count());
        var call = reserve(repair, "named-snapshot-001");
        var value = models.get(call.getId());
        var named = models.namedBudget();
        assertEquals(1, named.usedCalls());
        assertEquals(value.requestBytes(), named.reservedInputBytes());
        assertEquals(value.reservedOutputTokens(), named.reservedOutputTokens());
        assertEquals(models.budget().reservedInputBytes(), named.reservedInputBytes());
        assertEquals(models.sourceBudget(repair.sourceId()).reservedInputBytes(), named.reservedInputBytes());
        assertEquals(4, value.budgetReservation().path("schemaVersion").asInt());
        assertEquals("fixture-model", value.budgetReservation().path("modelName").asText());
        assertEquals(4, value.budgetReservation().path("modelLimits").path("calls").asInt());
        models.cancel(call.getId());
        models.recover();
        assertEquals(named, models.namedBudget());
        assertEquals(value.budgetReservation(), models.get(call.getId()).budgetReservation());
    }

    @Test
    void sharedModelQuotaStopsAnotherSourceWithoutChargingAndOldKeyStillReplays() throws Exception {
        var first = begin();
        ModelCall original = null;
        for (int i = 0; i < 3; i++) {
            var call = reserve(first, "named-first-00" + i);
            if (original == null) original = call;
            models.cancel(call.getId());
        }
        var second = begin();
        models.cancel(reserve(second, "named-second-001").getId());
        var global = models.budget();
        var source = models.sourceBudget(second.sourceId());
        var named = models.namedBudget();
        assertCode("MODEL_NAME_DAILY_BUDGET", () -> reserve(second, "named-over-limit-001"));
        assertEquals(global, models.budget());
        assertEquals(source, models.sourceBudget(second.sourceId()));
        assertEquals(named, models.namedBudget());
        assertEquals(original.getId(), reserve(first, "named-first-000").getId());
        assertEquals(1, models.list(second.id()).size());
    }

    @Test
    void modelRequestBytesAndOutputLimitsRejectWithoutPartialCharge() throws Exception {
        var repair = begin();
        models.cancel(reserve(repair, "named-byte-seed").getId());
        var original = models.namedBudget();
        var global = models.budget();
        var source = models.sourceBudget(repair.sourceId());
        for (int mode = 0; mode < 2; mode++) {
            var row = namedBudgets.findById("fixture-model").orElseThrow();
            row.setReservedInputBytes(mode == 0 ? original.dailyInputBytes() : original.reservedInputBytes());
            row.setReservedOutputTokens(mode == 1 ? original.dailyOutputTokens() : original.reservedOutputTokens());
            namedBudgets.saveAndFlush(row);
            var snapshot = models.namedBudget();
            String key = "named-byte-token-" + mode;
            assertCode("MODEL_NAME_DAILY_BUDGET", () -> reserve(repair, key));
            assertEquals(snapshot, models.namedBudget());
            assertEquals(global, models.budget());
            assertEquals(source, models.sourceBudget(repair.sourceId()));
        }
    }

    @Test
    void utcDayRolloverAndClockRollbackApplyToAllThreeScopes() throws Exception {
        var repair = begin();
        NOW.set(Instant.parse("2030-01-01T23:59:59Z"));
        models.cancel(reserve(repair, "named-before-midnight").getId());
        NOW.set(Instant.parse("2030-01-02T00:00:00Z"));
        models.cancel(reserve(repair, "named-after-midnight").getId());
        var named = models.namedBudget();
        assertEquals(LocalDate.of(2030, 1, 2), named.budgetDay());
        assertEquals(1, named.usedCalls());
        assertEquals(1, models.budget().usedCalls());
        assertEquals(1, models.sourceBudget(repair.sourceId()).usedCalls());
        NOW.set(Instant.parse("2030-01-01T23:59:59Z"));
        assertCode("MODEL_CLOCK_ROLLBACK", () -> reserve(repair, "named-backward"));
        assertEquals(named, models.namedBudget());
    }

    @Test
    void futureModelDayRollsBackTentativeGlobalAndSourceRollover() throws Exception {
        var repair = begin();
        models.cancel(reserve(repair, "named-future-seed").getId());
        var row = namedBudgets.findById("fixture-model").orElseThrow();
        row.setBudgetDay(LocalDate.of(2030, 1, 3));
        namedBudgets.saveAndFlush(row);
        var named = models.namedBudget();
        var global = models.budget();
        var source = models.sourceBudget(repair.sourceId());
        NOW.set(Instant.parse("2030-01-02T00:00:00Z"));
        assertCode("MODEL_CLOCK_ROLLBACK", () -> reserve(repair, "named-future-reject"));
        assertEquals(named, models.namedBudget());
        assertEquals(global, models.budget());
        assertEquals(source, models.sourceBudget(repair.sourceId()));
    }

    @Test
    void concurrentRetryUsesOneSlotAndConcurrentDifferentSourcesCannotExceedLastSlot() throws Exception {
        var repair = begin();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<ModelCall> work = () -> { start.await(); return reserve(repair, "named-same-key"); };
            var a = pool.submit(work);
            var b = pool.submit(work);
            start.countDown();
            assertEquals(a.get(5, TimeUnit.SECONDS).getId(), b.get(5, TimeUnit.SECONDS).getId());
        }
        assertEquals(1, models.namedBudget().usedCalls());
        models.cancel(models.list(repair.id()).getFirst().id());
        var row = namedBudgets.findById("fixture-model").orElseThrow();
        row.setUsedCalls(3);
        namedBudgets.saveAndFlush(row);
        var other = begin();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<String> a = () -> reserveAndCancel(start, repair, "named-last-a");
            Callable<String> b = () -> reserveAndCancel(start, other, "named-last-b");
            var x = pool.submit(a);
            var y = pool.submit(b);
            start.countDown();
            var results = List.of(x.get(5, TimeUnit.SECONDS), y.get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("ACCEPTED"::equals).count());
            assertTrue(results.contains("MODEL_BUSY") || results.contains("MODEL_NAME_DAILY_BUDGET"), results.toString());
        }
        assertEquals(4, models.namedBudget().usedCalls());
        assertEquals(2, models.budget().usedCalls());
    }

    @Test
    void transactionRollbackLeavesNoCallOrAnyScopeCharge() throws Exception {
        var repair = begin();
        var before = models.budget();
        String discarded = new TransactionTemplate(transactions).execute(status -> {
            var call = reserve(repair, "named-rollback");
            assertEquals(1, models.namedBudget().usedCalls());
            status.setRollbackOnly();
            return call.getId();
        });
        assertFalse(calls.existsById(discarded));
        assertEquals(0, namedBudgets.count());
        assertFalse(sourceBudgets.existsById(repair.sourceId()));
        assertEquals(before, models.budget());
        models.cancel(reserve(repair, "named-rollback").getId());
        assertEquals(1, models.namedBudget().usedCalls());
    }

    @Test
    void anotherExactModelNameHasIndependentLedgerAndAdminGetHasNoWriteSideEffects() throws Exception {
        var other = new ModelNamedBudget();
        other.setModelName("Fixture-model");
        other.setBudgetDay(LocalDate.of(2030, 1, 1));
        other.setUsedCalls(4);
        namedBudgets.saveAndFlush(other);
        String url = "http://127.0.0.1:" + port + "/api/plugin-maintenance/model/named-budget";
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                "admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
        try (var http = HttpClient.newHttpClient()) {
            assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var response = http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", auth).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"modelName\":\"fixture-model\""));
            assertTrue(response.body().contains("\"budgetDay\":null"));
            assertTrue(response.body().contains("\"dailyCallLimit\":4"));
            assertFalse(response.body().contains("endpoint"));
        }
        assertEquals(1, namedBudgets.count());
        var repair = begin();
        models.cancel(reserve(repair, "named-case-sensitive").getId());
        assertEquals(1, models.namedBudget().usedCalls());
        assertEquals(4, namedBudgets.findById("Fixture-model").orElseThrow().getUsedCalls());
    }

    private String reserveAndCancel(CountDownLatch start, RepairSummary repair, String key) throws Exception {
        start.await();
        try {
            var call = reserve(repair, key);
            models.cancel(call.getId());
            return "ACCEPTED";
        } catch (ResponseStatusException e) { return e.getReason(); }
    }
    private ModelCall reserve(RepairSummary repair, String key) {
        return models.reserve(repair.id(), new StartModelCallRequest(repair.faultSha256(),
                client.connection().configurationSha256(), key));
    }
    private RepairSummary begin() throws Exception {
        var source = sources.create(new CreateSourceRequest("模型预算", null, "repair.py", null, RepairIntegrationTest.BAD));
        var probe = probes.start(source.id());
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        ProbeSummary completed;
        do {
            completed = runs.get(probe.id());
            assertTrue(System.nanoTime() < until, probe.id() + "/" + completed.status());
            if (Set.of("QUEUED", "RUNNING").contains(completed.status())) Thread.sleep(20);
        } while (Set.of("QUEUED", "RUNNING").contains(completed.status()));
        assertEquals("COMPLETED", completed.status(), probe.id() + "/" + completed.errorCode());
        var repair = repairs.create(source.id(), new StartRepairRequest(probe.id(), source.artifactSha256()));
        var consent = models.consent(source.id());
        models.consent(source.id(), new UpdateModelConsentRequest(consent.artifactSha256(), source.artifactSha256()));
        return repair;
    }
    private void assertCode(String code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(ResponseStatusException.class, work).getReason());
    }
}
