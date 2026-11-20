package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.ModelCall;
import cn.har01d.atmaintenance.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:maintenance-model-cost-budget-tests;DB_CLOSE_DELAY=-1",
        "maintenance.at.origin=", "maintenance.scheduling.enabled=false",
        "maintenance.model.enabled=true",
        "maintenance.model.endpoint=http://127.0.0.1:9/v1/chat/completions",
        "maintenance.model.model=cost-fixture", "maintenance.model.key-env=",
        "maintenance.model.max-completion-tokens=128",
        "maintenance.model.daily-call-limit=100", "maintenance.model.daily-input-bytes=26214400",
        "maintenance.model.daily-output-tokens=1638400",
        "maintenance.model.source-daily-call-limit=100",
        "maintenance.model.source-daily-input-bytes=26214400",
        "maintenance.model.source-daily-output-tokens=1638400",
        "maintenance.model.model-daily-call-limit=100",
        "maintenance.model.model-daily-input-bytes=26214400",
        "maintenance.model.model-daily-output-tokens=1638400",
        "maintenance.model.input-price-usd-per-million=0.0001",
        "maintenance.model.output-price-usd-per-million=1",
        "maintenance.model.input-token-bound=request_utf8_bytes_v1",
        "maintenance.model.daily-cost-usd=0.0002",
        "maintenance.model.source-daily-cost-usd=0.0002",
        "maintenance.model.model-daily-cost-usd=0.0002"
})
class ModelCostBudgetIntegrationTest {
    static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2030-01-01T10:00:00Z"));

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        Clock costBudgetClock() {
            return new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

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
        row.setCostBreachDay(null);
        budgets.saveAndFlush(row);
    }

    @Test
    void reservesMaximumPotentialAmountAcrossAllScopesAndRejectsBeforeAnyPartialCharge() throws Exception {
        var repair = begin();
        ModelCall first = reserve(repair, "cost-budget-001");
        var expected = BigDecimal.valueOf(first.getRequestBytes()).multiply(new BigDecimal("0.0000000001"))
                .add(new BigDecimal("0.000128"));
        assertEquals(0, expected.compareTo(firstBudgetCost(first)));
        assertEquals(0, expected.compareTo(models.budget().reservedCostUsd()));
        assertEquals(0, expected.compareTo(models.sourceBudget(repair.sourceId()).reservedCostUsd()));
        assertEquals(0, expected.compareTo(models.namedBudget().reservedCostUsd()));
        assertEquals("maximum_reserved", firstBudget(first).path("costReservation").path("status").asText());
        assertEquals(128, firstBudget(first).path("costReservation").path("outputTokens").asInt());
        assertTrue(firstBudget(first).path("costReservation").path("inputTokensUpperBound").asInt() > 0);
        assertEquals("0.0002", firstBudget(first).path("globalLimits").path("costUsd").asText());
        models.cancel(first.getId());
        var global = models.budget();
        var source = models.sourceBudget(repair.sourceId());
        var named = models.namedBudget();
        var error = assertThrows(ResponseStatusException.class, () -> reserve(repair, "cost-budget-002"));
        assertEquals("MODEL_COST_DAILY_BUDGET", error.getReason());
        assertEquals(global, models.budget());
        assertEquals(source, models.sourceBudget(repair.sourceId()));
        assertEquals(named, models.namedBudget());
        assertEquals(1, models.list(repair.id()).size());
    }

    @Test
    void cancellationAndRestartDoNotRefundMaximumReservation() throws Exception {
        var repair = begin();
        var call = reserve(repair, "cost-budget-restart");
        assertTrue(models.start(call.getId()));
        var before = models.budget();
        models.recover();
        assertEquals("INTERRUPTED", models.get(call.getId()).status());
        var after = models.budget();
        assertEquals(before.budgetDay(), after.budgetDay());
        assertEquals(before.usedCalls(), after.usedCalls());
        assertEquals(before.reservedInputBytes(), after.reservedInputBytes());
        assertEquals(before.reservedOutputTokens(), after.reservedOutputTokens());
        assertEquals(before.reservedCostUsd(), after.reservedCostUsd());
        assertNull(after.activeCallId());
        assertEquals(before.reservedCostUsd(), models.sourceBudget(repair.sourceId()).reservedCostUsd());
        assertEquals(before.reservedCostUsd(), models.namedBudget().reservedCostUsd());
    }

    @Test
    void unknownCurrentReservationHistoryCannotBeTreatedAsZeroForHardBudget() throws Exception {
        var repair = begin();
        var call = reserve(repair, "cost-budget-unknown");
        models.cancel(call.getId());
        jdbc.update("UPDATE maintenance_model_budget SET reserved_cost_usd=NULL WHERE id=1");
        var error = assertThrows(ResponseStatusException.class, () -> reserve(repair, "cost-budget-unknown-2"));
        assertEquals("MODEL_COST_HISTORY_UNKNOWN", error.getReason());
        assertEquals(1, models.list(repair.id()).size());
        assertNull(models.budget().reservedCostUsd());
    }

    @Test
    void sourceAndModelAmountCapsRejectWithoutChargingAnyScope() throws Exception {
        var repair = begin();
        models.cancel(reserve(repair, "cost-scopes-seed").getId());
        for (String table : List.of("maintenance_model_source_budget", "maintenance_model_named_budget")) {
            jdbc.update("UPDATE " + table + " SET reserved_cost_usd=0.0002");
            jdbc.update("UPDATE maintenance_model_budget SET reserved_cost_usd=0");
            var global = models.budget();
            var source = models.sourceBudget(repair.sourceId());
            var named = models.namedBudget();
            String code = table.contains("_source_") ? "MODEL_SOURCE_COST_DAILY_BUDGET" : "MODEL_NAME_COST_DAILY_BUDGET";
            assertEquals(code, assertThrows(ResponseStatusException.class,
                    () -> reserve(repair, "cost-scope-" + table)).getReason());
            assertEquals(global, models.budget());
            assertEquals(source, models.sourceBudget(repair.sourceId()));
            assertEquals(named, models.namedBudget());
            jdbc.update("UPDATE " + table + " SET reserved_cost_usd=0");
        }
        assertEquals(1, models.list(repair.id()).size());
    }

    @Test
    void exactCapIsAcceptedAndTransactionRollbackRestoresAllAmountLedgers() throws Exception {
        var repair = begin();
        var empty = models.budget();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            var call = reserve(repair, "cost-rollback");
            assertNotNull(models.budget().reservedCostUsd());
            status.setRollbackOnly();
        });
        assertEquals(empty, models.budget());
        assertTrue(models.list(repair.id()).isEmpty());
        assertFalse(sourceBudgets.existsById(repair.sourceId()));
        assertEquals(0, namedBudgets.count());
        // An empty existing UTC row from an upgrade must allow its first priced call.
        jdbc.update("UPDATE maintenance_model_budget SET budget_day=DATE '2030-01-01', reserved_cost_usd=NULL");
        var seed = reserve(repair, "cost-exact-seed");
        models.cancel(seed.getId());
        var remaining = new BigDecimal("0.0002").subtract(firstBudgetCost(seed));
        jdbc.update("UPDATE maintenance_model_budget SET reserved_cost_usd=?", remaining);
        jdbc.update("UPDATE maintenance_model_source_budget SET reserved_cost_usd=?", remaining);
        jdbc.update("UPDATE maintenance_model_named_budget SET reserved_cost_usd=?", remaining);
        models.cancel(reserve(repair, "cost-exact-limit").getId());
        assertEquals(0, new BigDecimal("0.0002").compareTo(models.budget().reservedCostUsd()));
        assertEquals(0, new BigDecimal("0.0002").compareTo(models.sourceBudget(repair.sourceId()).reservedCostUsd()));
        assertEquals(0, new BigDecimal("0.0002").compareTo(models.namedBudget().reservedCostUsd()));
    }

    @Test
    void monetaryPeriodRollsAtUtcAndRejectsClockRollbackWithoutResettingHistory() throws Exception {
        var repair = begin();
        var first = reserve(repair, "cost-day-first");
        models.cancel(first.getId());
        NOW.set(Instant.parse("2030-01-02T00:00:00Z"));
        models.cancel(reserve(repair, "cost-day-next").getId());
        assertEquals(LocalDate.of(2030, 1, 2), models.budget().budgetDay());
        assertEquals(1, models.budget().usedCalls());
        var before = models.budget();
        NOW.set(Instant.parse("2030-01-01T23:59:59Z"));
        assertEquals("MODEL_CLOCK_ROLLBACK", assertThrows(ResponseStatusException.class,
                () -> reserve(repair, "cost-day-backward")).getReason());
        assertEquals(before, models.budget());
    }

    @Test
    void concurrentKeysCannotReserveBeyondLastAmountSlotAndSameKeyNeverChargesTwice() throws Exception {
        var repair = begin();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var latch = new CountDownLatch(1);
            Callable<String> a = () -> reserveConcurrent(repair, "cost-concurrent-a", latch);
            Callable<String> b = () -> reserveConcurrent(repair, "cost-concurrent-b", latch);
            var x = pool.submit(a);
            var y = pool.submit(b);
            latch.countDown();
            var results = List.of(x.get(5, TimeUnit.SECONDS), y.get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("ACCEPTED"::equals).count());
            assertTrue(results.contains("MODEL_BUSY") || results.contains("MODEL_COST_DAILY_BUDGET"));
        }
        assertEquals(1, models.budget().usedCalls());
        var saved = models.list(repair.id()).getFirst();
        var before = models.budget();
        assertEquals(saved.id(), reserve(repair, saved.requestKey()).getId());
        assertEquals(before, models.budget());
    }

    @Test
    void excessiveProviderUsageIsRecordedAndBlocksFurtherSendsUntilNextUtcDay() throws Exception {
        for (boolean output : List.of(false, true)) {
            reset();
            var repair = begin();
            var call = reserve(repair, "cost-overrun-" + output);
            assertTrue(models.start(call.getId()));
            var envelope = mapper.createObjectNode();
            long prompt = output ? 1 : call.getRequestBytes() + 1;
            long completion = output ? call.getReservedOutputTokens() + 1 : 0;
            envelope.putObject("usage").put("prompt_tokens", prompt)
                    .put("completion_tokens", completion).put("total_tokens", prompt + completion);
            models.finish(call.getId(), client.parse(mapper.writeValueAsBytes(envelope)), null);
            var finished = models.get(call.getId());
            assertEquals("MODEL_COST_RESERVATION_EXCEEDED", finished.errorCode());
            assertNotNull(finished.usage());
            assertNotNull(finished.costEstimate());
            assertNull(finished.proposal());
            assertEquals(LocalDate.of(2030, 1, 1), models.budget().costBreachDay());
            models.recover();
            var other = begin();
            assertEquals("MODEL_COST_RESERVATION_EXCEEDED", assertThrows(ResponseStatusException.class,
                    () -> reserve(other, "cost-overrun-block")).getReason());
            NOW.set(Instant.parse("2030-01-02T00:00:00Z"));
            models.cancel(reserve(other, "cost-overrun-next-day").getId());
        }
    }

    @Test
    void cancelledOverrunReceivedOnNextDayKeepsUsageAndBlocksTheReceiptDay() throws Exception {
        var repair = begin();
        var call = reserve(repair, "cost-late-overrun");
        assertTrue(models.start(call.getId()));
        models.cancel(call.getId());
        NOW.set(Instant.parse("2030-01-02T00:00:01Z"));
        var envelope = mapper.createObjectNode();
        envelope.putObject("usage").put("prompt_tokens", call.getRequestBytes() + 1)
                .put("completion_tokens", 0).put("total_tokens", call.getRequestBytes() + 1);
        models.finish(call.getId(), client.parse(mapper.writeValueAsBytes(envelope)), null);
        var finished = models.get(call.getId());
        assertEquals("CANCELLED", finished.status());
        assertEquals("MODEL_CANCELLED_OUTCOME_UNKNOWN", finished.errorCode());
        assertNotNull(finished.usage());
        assertNotNull(finished.costEstimate());
        assertNull(finished.proposal());
        assertEquals(LocalDate.of(2030, 1, 2), models.budget().costBreachDay());
        models.recover();
        assertEquals("MODEL_COST_RESERVATION_EXCEEDED", assertThrows(ResponseStatusException.class,
                () -> reserve(repair, "cost-late-blocked")).getReason());
        NOW.set(Instant.parse("2030-01-03T00:00:00Z"));
        models.cancel(reserve(repair, "cost-late-next-day").getId());
    }

    private String reserveConcurrent(RepairSummary repair, String key, CountDownLatch latch) throws Exception {
        latch.await();
        try {
            var call = reserve(repair, key);
            models.cancel(call.getId());
            return "ACCEPTED";
        } catch (ResponseStatusException e) { return e.getReason(); }
    }

    private BigDecimal firstBudgetCost(ModelCall call) {
        return new BigDecimal(firstBudget(call).path("costReservation").path("maximumUsd").asText());
    }

    private com.fasterxml.jackson.databind.JsonNode firstBudget(ModelCall call) {
        return models.get(call.getId()).budgetReservation();
    }

    private ModelCall reserve(RepairSummary repair, String key) {
        return models.reserve(repair.id(), new StartModelCallRequest(repair.faultSha256(),
                client.connection().configurationSha256(), key));
    }

    private RepairSummary begin() throws Exception {
        var source = sources.create(new CreateSourceRequest("金额预算", null, "repair.py", null,
                RepairIntegrationTest.BAD));
        var probe = probes.start(source.id());
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        ProbeSummary completed;
        do {
            completed = runs.get(probe.id());
            assertTrue(System.nanoTime() < until, probe.id() + "/" + completed.status() + "/" + completed.errorCode());
            if (Set.of("QUEUED", "RUNNING").contains(completed.status())) Thread.sleep(20);
        } while (Set.of("QUEUED", "RUNNING").contains(completed.status()));
        assertEquals("COMPLETED", completed.status(), probe.id() + "/" + completed.errorCode());
        var repair = repairs.create(source.id(), new StartRepairRequest(probe.id(), source.artifactSha256()));
        models.consent(source.id(), new UpdateModelConsentRequest(null, source.artifactSha256()));
        return repair;
    }
}
