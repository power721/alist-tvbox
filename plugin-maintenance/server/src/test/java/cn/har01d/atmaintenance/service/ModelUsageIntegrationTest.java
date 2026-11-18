package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.ModelCall;
import cn.har01d.atmaintenance.repository.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:maintenance-model-usage-tests;DB_CLOSE_DELAY=-1",
        "maintenance.at.origin=", "maintenance.scheduling.enabled=false", "maintenance.model.enabled=true",
        "maintenance.model.endpoint=http://127.0.0.1:9/v1/chat/completions", "maintenance.model.model=usage-fixture",
        "maintenance.model.key-env=", "maintenance.model.input-price-usd-per-million=0.00000001",
        "maintenance.model.output-price-usd-per-million=2.5"
})
class ModelUsageIntegrationTest {
    static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2030-01-01T23:59:59Z"));
    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary Clock usageClock() {
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
    @Autowired ModelNamedBudgetRepository namedBudgets;
    @Autowired ModelSourceBudgetRepository sourceBudgets;
    @Autowired ModelCallRepository calls;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        models.recover();
        namedBudgets.deleteAll();
        sourceBudgets.deleteAll();
        var global = budgets.findById(1).orElseThrow();
        global.setBudgetDay(null);
        global.setUsedCalls(0);
        global.setReservedInputBytes(0);
        global.setReservedOutputTokens(0);
        global.setReservedCostUsd(null);
        budgets.saveAndFlush(global);
        // Each scenario owns a distinct UTC day so prior call records remain immutable.
        NOW.updateAndGet(value -> value.plus(Duration.ofDays(2)));
    }

    @Test
    void concurrentTerminalDeliveryPersistsRejectedUsageAndSubCentEstimateExactlyOnce() throws Exception {
        var call = reserve(begin(), "usage-concurrent");
        assertTrue(models.start(call.getId()));
        var reply = rejected(1, 0);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Void> finish = () -> { start.await(); models.finish(call.getId(), reply, null); return null; };
            var first = pool.submit(finish);
            var second = pool.submit(finish);
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        var saved = models.get(call.getId());
        assertEquals("FAILED", saved.status());
        assertEquals("MODEL_RESPONSE_CHOICES_INVALID", saved.errorCode());
        assertNull(saved.proposal());
        assertEquals("0.00000001", saved.budgetReservation().path("pricing").path("inputPerMillion").asText());
        assertEquals("0.00000000000001", saved.costEstimate().path("totalUsd").asText());
        var summary = models.namedBudget();
        assertEquals(1, summary.usedCalls());
        assertEquals(1, summary.reportedUsageCalls());
        assertEquals(1, summary.pricedCalls());
        assertEquals(1, summary.totalTokens());
        assertEquals("0.00000000000001", summary.estimatedCostUsd());
        models.finish(call.getId(), rejected(99, 1), null);
        assertEquals(summary, models.namedBudget());
        assertEquals(saved, models.get(call.getId()));
    }

    @Test
    void receivedUsageSurvivesCancellationAndCannotAuthorizeProposalOrRefundReservation() throws Exception {
        var call = reserve(begin(), "usage-cancelled");
        assertTrue(models.start(call.getId()));
        models.cancel(call.getId());
        models.finish(call.getId(), rejected(10, 20), null);
        var result = models.get(call.getId());
        assertEquals("CANCELLED", result.status());
        assertNull(result.proposal());
        assertEquals(30, result.usage().path("total_tokens").asLong());
        assertNotNull(result.costEstimate());
        assertEquals(1, models.budget().usedCalls());
        assertEquals(1, models.namedBudget().pricedCalls());
    }

    @Test
    void restartLeavesUnknownUsageAndLateCompletionCannotRewriteInterruptedCall() throws Exception {
        var repair = begin();
        var call = reserve(repair, "usage-interrupted");
        assertTrue(models.start(call.getId()));
        models.recover();
        models.finish(call.getId(), rejected(10, 20), null);
        var result = models.get(call.getId());
        assertEquals("INTERRUPTED", result.status());
        assertNull(result.usage());
        assertNull(result.costEstimate());
        assertEquals(1, models.namedBudget().callsWithoutUsage());
        assertNull(models.namedBudget().totalTokens());
        assertNull(models.namedBudget().estimatedCostUsd());
        assertEquals(call.getId(), reserve(repair, "usage-interrupted").getId());
    }

    @Test
    void terminalTransactionRollbackLeavesUsageUnknownUntilDurableRetry() throws Exception {
        var call = reserve(begin(), "usage-rollback");
        assertTrue(models.start(call.getId()));
        var before = models.namedBudget();
        var reply = rejected(5, 7);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            models.finish(call.getId(), reply, null);
            assertEquals(1, models.namedBudget().pricedCalls());
            status.setRollbackOnly();
        });
        assertEquals(before, models.namedBudget());
        assertEquals("RUNNING", models.get(call.getId()).status());
        assertNull(models.get(call.getId()).usage());
        models.finish(call.getId(), reply, null);
        assertEquals(12, models.namedBudget().totalTokens());
        assertEquals(1, models.namedBudget().pricedCalls());
    }

    @Test
    void midnightCompletionBelongsToReservationDayAndReadDoesNotRollPeriodForward() throws Exception {
        var repair = begin();
        var call = reserve(repair, "usage-before-midnight");
        assertTrue(models.start(call.getId()));
        LocalDate day = LocalDate.ofInstant(NOW.get(), ZoneOffset.UTC);
        NOW.updateAndGet(value -> value.plusSeconds(2));
        models.finish(call.getId(), rejected(10, 20), null);
        assertEquals(day, models.namedBudget().budgetDay());
        assertEquals(30, models.namedBudget().totalTokens());
        models.cancel(reserve(repair, "usage-after-midnight").getId());
        assertEquals(day.plusDays(1), models.namedBudget().budgetDay());
        assertNull(models.namedBudget().totalTokens());
        assertNull(models.namedBudget().estimatedCostUsd());
        assertEquals(1, models.namedBudget().callsWithoutUsage());
        assertEquals(30, models.get(call.getId()).usage().path("total_tokens").asLong());
    }

    @Test
    void legacyUsageIsSummedWithoutInventingPricesOrModifyingFrozenSnapshot() throws Exception {
        var first = reserve(begin(), "usage-legacy");
        models.cancel(first.getId());
        String frozen = mapper.createObjectNode().put("schemaVersion", 2)
                .put("budgetDay", models.namedBudget().budgetDay().toString()).toString();
        jdbc.update("UPDATE maintenance_model_call SET budget_json=? WHERE id=?", frozen, first.getId());
        var row = calls.findById(first.getId()).orElseThrow();
        row.setUsageJson(mapper.createObjectNode().put("prompt_tokens", 0)
                .put("completion_tokens", 0).put("total_tokens", 0).toString());
        calls.saveAndFlush(row);
        var unknown = reserve(begin(), "usage-legacy-null");
        models.cancel(unknown.getId());
        var missing = calls.findById(unknown.getId()).orElseThrow();
        missing.setUsageJson("null");
        calls.saveAndFlush(missing);
        var second = reserve(begin(), "usage-current");
        assertTrue(models.start(second.getId()));
        models.finish(second.getId(), rejected(3, 2), null);
        var summary = models.namedBudget();
        assertEquals(2, summary.reportedUsageCalls());
        assertEquals(1, summary.pricedCalls());
        assertEquals(1, summary.callsWithoutUsage());
        assertEquals(1, summary.usageCallsWithoutPrice());
        assertEquals(5, summary.totalTokens());
        assertEquals(0, new BigDecimal("0.00000500000003").compareTo(new BigDecimal(summary.estimatedCostUsd())));
        assertEquals(frozen, calls.findById(first.getId()).orElseThrow().getBudgetJson());
        assertNull(models.get(first.getId()).costEstimate());
    }

    private ModelReply rejected(long prompt, long completion) throws Exception {
        var envelope = mapper.createObjectNode();
        envelope.putObject("usage").put("prompt_tokens", prompt)
                .put("completion_tokens", completion).put("total_tokens", prompt + completion);
        // Syntactically valid response with no proposal: usage still belongs to the completed request.
        return client.parse(mapper.writeValueAsBytes(envelope));
    }
    private ModelCall reserve(RepairSummary repair, String key) {
        return models.reserve(repair.id(), new StartModelCallRequest(repair.faultSha256(),
                client.connection().configurationSha256(), key));
    }
    private RepairSummary begin() throws Exception {
        var source = sources.create(new CreateSourceRequest("用量测试", null, "repair.py", null, RepairIntegrationTest.BAD));
        var probe = probes.start(source.id());
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        ProbeSummary done;
        do {
            done = runs.get(probe.id());
            assertTrue(System.nanoTime() < until, probe.id() + "/" + done.status() + "/" + done.errorCode());
            if (Set.of("QUEUED", "RUNNING").contains(done.status())) Thread.sleep(20);
        } while (Set.of("QUEUED", "RUNNING").contains(done.status()));
        assertEquals("COMPLETED", done.status(), probe.id() + "/" + done.errorCode());
        var repair = repairs.create(source.id(), new StartRepairRequest(probe.id(), source.artifactSha256()));
        models.consent(source.id(), new UpdateModelConsentRequest(null, source.artifactSha256()));
        return repair;
    }
}
