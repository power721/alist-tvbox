package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:maintenance-model-unlimited-tests;DB_CLOSE_DELAY=-1",
        "maintenance.connections-file=./target/unlimited-test-connections.json",
        "maintenance.at.origin=", "maintenance.scheduling.enabled=false",
        "maintenance.model.enabled=true", "maintenance.model.model=fixture-model",
        "maintenance.model.endpoint=http://127.0.0.1:9/v1/chat/completions",
        "maintenance.model.key-env="
})
class ModelUnlimitedBudgetIntegrationTest {
    @Autowired SourceService sources;
    @Autowired ProbeService probes;
    @Autowired ProbeStore runs;
    @Autowired RepairStore repairs;
    @Autowired ModelStore models;
    @Autowired ModelClient client;
    @Autowired ModelBudgetRepository budgets;
    @Autowired ModelSourceBudgetRepository sourceBudgets;
    @Autowired ModelNamedBudgetRepository namedBudgets;

    @Test
    void defaultUnlimitedScopesKeepExistingUsageAndIdempotencyWithoutDailyRejection() throws Exception {
        var config = client.connection();
        assertEquals(0, config.dailyCallLimit());
        assertEquals(0, config.sourceDailyInputBytes());
        assertEquals(0, config.modelDailyOutputTokens());
        assertNull(config.dailyCostUsd());
        assertNull(config.sourceDailyCostUsd());
        assertNull(config.modelDailyCostUsd());
        var source = sources.create(new CreateSourceRequest("unlimited", null, "repair.py", null,
                RepairIntegrationTest.BAD));
        var run = probes.start(source.id());
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (Set.of("QUEUED", "RUNNING").contains(runs.get(run.id()).status())) {
            assertTrue(System.nanoTime() < deadline);
            Thread.sleep(20);
        }
        assertEquals("COMPLETED", runs.get(run.id()).status());
        var repair = repairs.create(source.id(), new StartRepairRequest(run.id(), source.artifactSha256()));
        models.consent(source.id(), new UpdateModelConsentRequest(null, source.artifactSha256()));
        var first = models.reserve(repair.id(), new StartModelCallRequest(repair.faultSha256(),
                config.configurationSha256(), "unlimited-seed"));
        models.cancel(first.getId());
        var global = budgets.findById(1).orElseThrow();
        global.setUsedCalls(101);
        global.setReservedInputBytes(30_000_000);
        global.setReservedOutputTokens(2_000_000);
        budgets.saveAndFlush(global);
        var perSource = sourceBudgets.findById(source.id()).orElseThrow();
        perSource.setUsedCalls(101);
        perSource.setReservedInputBytes(30_000_000);
        perSource.setReservedOutputTokens(2_000_000);
        sourceBudgets.saveAndFlush(perSource);
        var named = namedBudgets.findById(config.model()).orElseThrow();
        named.setUsedCalls(101);
        named.setReservedInputBytes(30_000_000);
        named.setReservedOutputTokens(2_000_000);
        namedBudgets.saveAndFlush(named);
        var input = new StartModelCallRequest(repair.faultSha256(), config.configurationSha256(), "unlimited-next");
        var accepted = models.reserve(repair.id(), input);
        assertEquals(accepted.getId(), models.reserve(repair.id(), input).getId());
        assertEquals(102, models.budget().usedCalls());
        assertEquals(102, models.sourceBudget(source.id()).usedCalls());
        assertEquals(102, models.namedBudget().usedCalls());
        assertEquals(30_000_000L + accepted.getRequestBytes(), models.budget().reservedInputBytes());
        assertEquals(2_000_000L + config.maxCompletionTokens(), models.namedBudget().reservedOutputTokens());
        var snapshot = models.get(accepted.getId()).budgetReservation();
        for (String scope : Set.of("globalLimits", "sourceLimits", "modelLimits")) {
            assertEquals(0, snapshot.path(scope).path("calls").asInt());
            assertEquals(0, snapshot.path(scope).path("inputBytes").asLong());
            assertEquals(0, snapshot.path(scope).path("outputTokens").asLong());
        }
        models.cancel(accepted.getId());
        models.recover();
        assertEquals(102, models.budget().usedCalls());
        assertEquals(2, models.list(repair.id()).size());
    }
}
