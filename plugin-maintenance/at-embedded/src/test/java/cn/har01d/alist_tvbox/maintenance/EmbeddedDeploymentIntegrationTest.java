package cn.har01d.alist_tvbox.maintenance;

import cn.har01d.alist_tvbox.auth.SessionTokenService;
import cn.har01d.alist_tvbox.auth.TokenFilter;
import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.config.EmbeddedMaintenanceConfiguration;
import cn.har01d.alist_tvbox.config.WebSecurityConfiguration;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.service.PluginWorkerService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.SubscriptionSourceService;
import cn.har01d.alist_tvbox.service.PluginService;
import cn.har01d.alist_tvbox.service.PluginMutationGuard;
import cn.har01d.alist_tvbox.service.sync.SyncService;
import cn.har01d.alist_tvbox.dto.sync.MergeStrategy;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.backup.DatabaseBackupService;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.EmbeddedDeployment;
import cn.har01d.atmaintenance.repository.EmbeddedDeploymentRepository;
import cn.har01d.atmaintenance.repository.AtBindingRepository;
import cn.har01d.atmaintenance.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EmbeddedDeploymentIntegrationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({EmbeddedMaintenanceConfiguration.class, WebSecurityConfiguration.class, TokenFilter.class,
            SessionTokenService.class, PluginWorkerService.class, PluginMutationGuard.class, ContentEndpoint.class})
    static class Harness {
        @Bean @Primary AtReadClient metadataFixture() { return mock(AtReadClient.class); }
        @Bean @Primary SubscriptionService contextBuilder() { return mock(SubscriptionService.class); }
        @Bean AppProperties appProperties() { return new AppProperties(); }
        @Bean RestTemplate hostDownloads() { return new RestTemplate(); }
        @Bean PluginService hostPluginService(PluginRepository plugins, SettingRepository settings,
                TransactionTemplate transaction, PluginMutationGuard guard, RestTemplate hostDownloads) {
            var builder = mock(RestTemplateBuilder.class);
            when(builder.build()).thenReturn(hostDownloads);
            return new PluginService(plugins, settings, builder, new ObjectMapper(), transaction,
                    mock(SubscriptionSourceService.class),
                    mock(cn.har01d.alist_tvbox.service.GitHubProxyService.class), guard);
        }
    }

    @RestController
    static class ContentEndpoint {
        private final PluginRepository plugins;
        ContentEndpoint(PluginRepository plugins) { this.plugins = plugins; }
        @GetMapping(value = "/plugins/{token}/{id}.py", produces = "text/plain;charset=UTF-8")
        String content(@PathVariable String token, @PathVariable int id) {
            if (!token.equals("fixture-token")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            return plugins.findById(id).orElseThrow().getContent();
        }
    }

    @Test
    void activatesSavedVersionWithoutRewritingAndRecoversFailedLoadAfterRestart() throws Exception {
        String before = spider("baseline", false);
        String after = spider("candidate", false)
                + "    def playerContent(self, flag, id, vip):\n        return {'url': ''}\n";
        String interrupted;
        int pluginId;
        Path flag = directory.resolve("allow-init.flag");
        String recoverable = spider("recovered", false).replace("return ''", "from pathlib import Path\n"
                + "        if not Path(" + mapper.writeValueAsString(flag.toString().replace('\\', '/'))
                + ").exists():\n            raise ValueError('fixture not ready')\n        return ''");
        try (var context = open(true)) {
            configure(context);
            var plugins = context.getBean(PluginRepository.class);
            Plugin plugin = plugins.saveAndFlush(plugin(before));
            pluginId = plugin.getId();
            var workers = context.getBean(PluginWorkerService.class);
            var deployments = context.getBean(EmbeddedDeploymentService.class);
            String extend = context.getBean(SubscriptionService.class).buildT4PluginExtend(plugin, "fixture-token", "all");
            assertEquals("baseline", workers.call("deployment-fixture", extend, "home", Map.of()).path("marker").asText());
            String oldGeneration = generation(workers);
            plugin.setContent(after);
            plugins.saveAndFlush(plugin);
            assertEquals("baseline", workers.call("deployment-fixture", extend, "home", Map.of()).path("marker").asText());
            var target = deployments.savedRuntime(pluginId);
            assertEquals(Hashes.sha256(after), target.pluginSha256());
            assertEquals(Hashes.sha256(before), target.runtime().sourceSha256());
            var stale = deployments.activate(pluginId, new StartAtRuntimeActivationRequest(target.pluginSha256(),
                    target.configurationSha256(), "0".repeat(32), "stale-activation", false));
            assertEquals("REJECTED", stale.state());
            assertEquals("AT_WORKER_GENERATION_CHANGED", stale.errorCode());
            var request = new StartAtRuntimeActivationRequest(target.pluginSha256(), target.configurationSha256(),
                    oldGeneration, "saved-activation", false);
            var activated = deployments.activate(pluginId, request);
            assertEquals("DEPLOYED", activated.state());
            assertNotEquals(oldGeneration, activated.loadedGeneration());
            assertEquals(after, plugins.findById(pluginId).orElseThrow().getContent());
            assertEquals("candidate", workers.call("deployment-fixture", extend, "home", Map.of()).path("marker").asText());
            assertEquals(activated, deployments.activate(pluginId, request));
            assertFalse(context.getBean(PluginMutationGuard.class).managed(pluginId));
            workers.call("deployment-fixture", extend, "play", Map.of("play", "fixture"));
            target = deployments.savedRuntime(pluginId);
            var denied = deployments.activate(pluginId, new StartAtRuntimeActivationRequest(target.pluginSha256(),
                    target.configurationSha256(), generation(workers), "playing-activation", false));
            assertEquals("REJECTED", denied.state());
            assertEquals("AT_PLAYBACK_INTERRUPTION_CONFIRMATION_REQUIRED", denied.errorCode());
            plugin = plugins.findById(pluginId).orElseThrow();
            plugin.setContent(recoverable);
            plugins.saveAndFlush(plugin);
            target = deployments.savedRuntime(pluginId);
            var failed = deployments.activate(pluginId, new StartAtRuntimeActivationRequest(target.pluginSha256(),
                    target.configurationSha256(), generation(workers), "failed-activation", true));
            assertEquals("RECOVERY_REQUIRED", failed.state());
            interrupted = failed.id();
            assertTrue(deployments.blocked("deployment-fixture"));
            assertThrows(IllegalStateException.class,
                    () -> workers.call("deployment-fixture", extend, "home", Map.of()));
        }
        Files.createFile(flag);
        try (var context = open(true)) {
            configure(context);
            var deployments = context.getBean(EmbeddedDeploymentService.class);
            var workers = context.getBean(PluginWorkerService.class);
            assertTrue(deployments.blocked("deployment-fixture"));
            assertNull(ReflectionTestUtils.getField(workers, "workerScript"));
            var recovered = deployments.resumeActivation(interrupted, new RollbackEmbeddedDeploymentRequest(null, false));
            assertEquals("DEPLOYED", recovered.state());
            assertFalse(deployments.blocked("deployment-fixture"));
            assertEquals(Hashes.sha256(recoverable), deployments.savedRuntime(pluginId).runtime().sourceSha256());
            assertEquals(recoverable, context.getBean(PluginRepository.class).findById(pluginId).orElseThrow().getContent());
            context.getBean(Flyway.class).validate();
        }
    }

    @Test
    void nativeDeploymentAndRollbackUseDurableIntentActualWorkerAndRestartRecovery() throws Exception {
        String admin;
        String interruptedId;
        int pluginId;
        String before = spider("baseline", false) + "    def getName(self):\n        return 'fixture'\n";
        String after = spider("candidate", false);
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
        try (var context = open(true)) {
            configure(context);
            var tokens = context.getBean(SessionTokenService.class);
            admin = tokens.encodeToken(1, "deployment-fixture-admin", "ADMIN", "127.0.0.1", "test");
            String user = tokens.encodeToken(2, "deployment-fixture-user", "USER", "127.0.0.1", "test");
            var plugins = context.getBean(PluginRepository.class);
            Plugin plugin = plugin(before);
            plugin = plugins.saveAndFlush(plugin);
            pluginId = plugin.getId();
            String base = origin(context) + "/api/plugin-maintenance/at-native";
            String url = base + "/plugins/" + pluginId + "/deployments";
            var workers = context.getBean(PluginWorkerService.class);
            String extend = context.getBean(SubscriptionService.class).buildT4PluginExtend(plugin, "fixture-token", "all");
            assertEquals("baseline", workers.call("deployment-fixture", extend, "home", Map.of()).path("marker").asText());
            String generation = generation(workers);
            var plan = accepted(context, pluginId, before, after);
            var request = request(plan, before, generation, "successful");
            assertEquals(401, get(client, url, null).statusCode());
            String targetUrl = base + "/release-plans/" + plan.id() + "/target";
            assertEquals(401, get(client, targetUrl, null).statusCode());
            assertEquals(403, get(client, targetUrl, user).statusCode());
            var targetResponse = get(client, targetUrl, admin);
            assertEquals(200, targetResponse.statusCode(), targetResponse.body());
            assertEquals("no-store", targetResponse.headers().firstValue("cache-control").orElse(""));
            var target = mapper.readTree(targetResponse.body());
            assertEquals(pluginId, target.path("pluginId").asInt());
            assertTrue(target.path("enabled").asBoolean());
            assertEquals(Hashes.sha256(before), target.path("pluginSha256").asText());
            assertEquals(generation, target.path("runtime").path("workerGeneration").asText());
            assertFalse(targetResponse.body().contains("class Spider"));
            JsonNode csrf = csrf(client, context, admin);
            assertEquals(403, get(client, origin(context) + "/api/plugin-maintenance/csrf", user).statusCode());
            assertEquals(403, post(client, url, user, request, csrf).statusCode());
            assertEquals(403, post(client, url, admin, request, null).statusCode());
            var response = postWithCsrf(client, context, url, admin, request);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode deployed = mapper.readTree(response.body());
            assertEquals("DEPLOYED", deployed.path("state").asText(), response.body());
            String deployedId = deployed.path("id").asText();
            assertNotEquals(generation, deployed.path("loadedGeneration").asText());
            assertEquals(after, plugins.findById(pluginId).orElseThrow().getContent());
            assertEquals("candidate", workers.call("deployment-fixture", extend, "home", Map.of()).path("marker").asText());
            assertFalse(response.body().contains("class Spider"));
            assertFalse(response.body().contains("fixture-token"));
            assertFalse(response.body().contains("https://private"));
            var replayed = postWithCsrf(client, context, url, admin, request);
            assertEquals(200, replayed.statusCode(), replayed.body());
            JsonNode repeated = mapper.readTree(replayed.body());
            assertEquals(deployedId, repeated.path("id").asText());
            assertEquals(deployed.path("loadedGeneration"), repeated.path("loadedGeneration"));
            assertManagedWrites(context, pluginId, after);
            var ready = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            var staleWriter = executor.submit(() -> context.getBean(TransactionTemplate.class).execute(status -> {
                assertEquals(after, plugins.findById(pluginId).orElseThrow().getContent());
                ready.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                Plugin fresh = context.getBean(PluginMutationGuard.class).writable(pluginId);
                fresh.setName("Edited after rollback");
                plugins.saveAndFlush(fresh);
                return fresh.getContent();
            }));
            try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            var rollback = postWithCsrf(client, context, base + "/deployments/" + deployedId + "/rollback", admin,
                    mapper.createObjectNode().put("expectedWorkerGeneration", generation(workers)));
            assertEquals(200, rollback.statusCode(), rollback.body());
            assertEquals("ROLLED_BACK", mapper.readTree(rollback.body()).path("state").asText());
            assertEquals(before, plugins.findById(pluginId).orElseThrow().getContent());
            assertEquals("baseline", workers.call("deployment-fixture", extend, "home", Map.of()).path("marker").asText());
            release.countDown();
            assertEquals(before, staleWriter.get(10, TimeUnit.SECONDS));
            assertEquals(before, plugins.findById(pluginId).orElseThrow().getContent());
            assertFalse(context.getBean(PluginMutationGuard.class).managed(pluginId));
            } finally {
                release.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
            var hostPlugins = context.getBean(PluginService.class);
            assertEquals(before + "# refreshed after rollback\n",
                    hostPlugins.upsertFromContent(plugin.getUrl(), before + "# refreshed after rollback\n").getContent());
            hostPlugins.upsertFromContent(plugin.getUrl(), before);

            var stale = postWithCsrf(client, context, url, admin, request(plan, before, "0".repeat(32), "wrong-generation"));
            assertEquals(200, stale.statusCode(), stale.body());
            assertEquals("REJECTED", mapper.readTree(stale.body()).path("state").asText());
            assertEquals("AT_WORKER_GENERATION_CHANGED", mapper.readTree(stale.body()).path("errorCode").asText());
            assertEquals(before, plugins.findById(pluginId).orElseThrow().getContent());
            var jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("UPDATE maintenance_repair_candidate SET status = 'STATIC_VALIDATED' WHERE id = ?", plan.candidateId());
            assertEquals(409, postWithCsrf(client, context, url, admin,
                    request(plan, before, generation(workers), "not-ready")).statusCode());
            jdbc.update("UPDATE maintenance_repair_candidate SET status = 'RUNTIME_OBSERVED' WHERE id = ?", plan.candidateId());
            assertEquals(409, postWithCsrf(client, context, base + "/plugins/2147483647/deployments", admin,
                    request(plan, before, generation(workers), "wrong-plugin")).statusCode());

            var failingPlan = accepted(context, pluginId, before, spider("failure", true));
            var failed = postWithCsrf(client, context, url, admin,
                    request(failingPlan, before, generation(workers), "failed-init"));
            assertEquals(200, failed.statusCode(), failed.body());
            assertEquals("ROLLED_BACK", mapper.readTree(failed.body()).path("state").asText(), failed.body());
            assertEquals("AT_DEPLOYMENT_EXECUTION_FAILED", mapper.readTree(failed.body()).path("errorCode").asText());
            assertEquals(before, plugins.findById(pluginId).orElseThrow().getContent());
            assertEquals("baseline", workers.call("deployment-fixture", extend, "home", Map.of()).path("marker").asText());
            assertEquals("71", context.getBean(Flyway.class).info().current().getVersion().getVersion());

            // Simulate a committed SAVED intent at process loss; no external operation is replayed on reopen.
            var rows = context.getBean(EmbeddedDeploymentRepository.class);
            var completed = rows.findById(deployedId).orElseThrow();
            var interrupted = new EmbeddedDeployment();
            interrupted.setPluginId(pluginId);
            interrupted.setSiteKey(completed.getSiteKey());
            interrupted.setPlanId(completed.getPlanId());
            interrupted.setRequestSha256(completed.getRequestSha256());
            interrupted.setBaselineContent(before);
            interrupted.setCandidateContent(after);
            interrupted.setBaselineSha256(completed.getBaselineSha256());
            interrupted.setCandidateSha256(completed.getCandidateSha256());
            interrupted.setConfigurationSha256(completed.getConfigurationSha256());
            interrupted.setRequestKey("interrupted-fixture");
            interrupted.setState("SAVED");
            interrupted.setActivePluginId(pluginId);
            interrupted.setLoadedGeneration(null);
            interrupted = rows.saveAndFlush(interrupted);
            interruptedId = interrupted.getId();
            var stored = plugins.findById(pluginId).orElseThrow();
            stored.setContent(after);
            plugins.saveAndFlush(stored);
            assertEquals("maintenance_blocked", workers.runtimeStatus("deployment-fixture", null).path("configurationState").asText());
            target = mapper.readTree(get(client, targetUrl, admin).body());
            assertEquals("BLOCKED", target.path("runtime").path("loadState").asText());
            assertEquals(generation(workers), target.path("runtime").path("workerGeneration").asText());
        }
        try (var context = open(true)) {
            configure(context);
            var workers = context.getBean(PluginWorkerService.class);
            assertNull(ReflectionTestUtils.getField(workers, "workerScript"));
            String base = origin(context) + "/api/plugin-maintenance/at-native";
            var restored = get(client, base + "/deployments/" + interruptedId, admin);
            assertEquals(200, restored.statusCode());
            assertEquals("SAVED", mapper.readTree(restored.body()).path("state").asText());
            var guard = context.getBean(PluginMutationGuard.class);
            assertTrue(guard.managed(pluginId));
            assertEquals("AT_PLUGIN_MAINTENANCE_ACTIVE",
                    assertThrows(ResponseStatusException.class, () -> context.getBean(PluginService.class)
                            .upsertFromContent("https://private-fixture.invalid/source.py", "must not overwrite"))
                            .getReason());
            assertThrows(IllegalStateException.class, () -> workers.call("deployment-fixture", "", "home", Map.of()));
            assertNull(ReflectionTestUtils.getField(workers, "workerScript"));
            var recovered = postWithCsrf(client, context, base + "/deployments/" + interruptedId + "/rollback", admin,
                    mapper.createObjectNode());
            assertEquals(200, recovered.statusCode(), recovered.body());
            assertEquals("ROLLED_BACK", mapper.readTree(recovered.body()).path("state").asText(), recovered.body());
            assertEquals(before, context.getBean(PluginRepository.class).findById(pluginId).orElseThrow().getContent());
            assertEquals(Hashes.sha256(before), workers.runtimeStatus("deployment-fixture", null)
                    .path("identity").path("sourceTextSha256").asText());
            context.getBean(Flyway.class).validate();
        }
        try (var context = open(false)) {
            configure(context);
            String url = origin(context) + "/api/plugin-maintenance/at-native/plugins/" + pluginId + "/deployments";
            var disabled = postWithCsrf(client, context, url, admin,
                    request(accepted(context, pluginId, before, after), before, null, "disabled"));
            assertEquals(409, disabled.statusCode());
            assertTrue(disabled.body().contains("AT_DEPLOYMENT_DISABLED"));
            assertNull(ReflectionTestUtils.getField(context.getBean(PluginWorkerService.class), "workerScript"));
        }
    }

    private void assertManagedWrites(ConfigurableApplicationContext context, int id, String candidate) throws Exception {
        var plugins = context.getBean(PluginRepository.class);
        var service = context.getBean(PluginService.class);
        var guard = context.getBean(PluginMutationGuard.class);
        var transaction = context.getBean(TransactionTemplate.class);
        Plugin stored = plugins.findById(id).orElseThrow();
        assertTrue(guard.managed(id));
        List<Runnable> rejected = List.of(
                () -> service.refresh(id),
                () -> service.update(id, stored),
                () -> service.upsertFromContent(stored.getUrl(), "must not overwrite"),
                () -> service.delete(id),
                () -> service.deleteBatch(List.of(id)));
        for (Runnable action : rejected) {
            assertEquals("AT_PLUGIN_MAINTENANCE_ACTIVE",
                    assertThrows(ResponseStatusException.class, action::run).getReason());
        }
        var subscriptions = new SubscriptionSourceService(new AppProperties(), plugins, null, null, null,
                null, null, mapper, guard);
        assertEquals("AT_PLUGIN_MAINTENANCE_ACTIVE", assertThrows(ResponseStatusException.class,
                () -> transaction.execute(status -> subscriptions.update("plugin-" + id,
                        new SubscriptionSourceService.ManagedSourceUpdate("changed", false, false, null, "{}"))))
                .getReason());
        assertFalse(guard.updateExtend(id, stored.getExtend(), "must not overwrite"));
        service.reorder(List.of(id)); // Sorting is allowed, but must not merge stale content.
        var sync = new SyncService(null, null, null, null, null, null, null, plugins, null,
                null, null, null, null, mapper, null, null, guard, transaction);
        Plugin remote = plugin("remote");
        assertEquals(1, sync.importPlugins(List.of(remote), MergeStrategy.MERGE).getFailed());
        assertEquals(1, sync.importPlugins(List.of(remote), MergeStrategy.OVERWRITE).getFailed());
        assertEquals(candidate, plugins.findById(id).orElseThrow().getContent());
        Path backup = directory.resolve("managed-restore.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(backup))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write("{\"formatVersion\":1,\"modules\":{\"plugins\":\"Plugin\"}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var restore = new DatabaseBackupService(null, null, null, null, guard);
        transaction.executeWithoutResult(status -> assertEquals("AT_PLUGIN_MAINTENANCE_ACTIVE",
                assertThrows(ResponseStatusException.class,
                        () -> restore.restoreBackupZip(backup.toFile(), BackupRestoreMode.MERGE)).getReason()));

        Plugin ordinary = plugin("ordinary");
        ordinary.setExternalId("ordinary-fixture");
        ordinary.setUrl("https://example.com/ordinary.py");
        ordinary = plugins.saveAndFlush(ordinary);
        var server = MockRestServiceServer.bindTo(context.getBean(RestTemplate.class)).build();
        String index = "https://example.com/spiders_v2.json";
        server.expect(requestTo(index)).andRespond(withSuccess("[{\"file\":\"" + stored.getUrl()
                + "\",\"id\":\"deployment-fixture\",\"version\":999},{\"file\":\"" + ordinary.getUrl()
                + "\",\"id\":\"ordinary-fixture\",\"version\":999}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ordinary.getUrl())).andRespond(withSuccess(
                "PLUGIN_ID = 'ordinary-fixture'\n# ordinary update", MediaType.TEXT_PLAIN));
        var imported = service.importFromSource(index);
        assertEquals(1, imported.skippedCount());
        assertEquals(1, imported.refreshedCount());
        assertEquals(0, imported.failedCount());
        server.verify();
        assertEquals(candidate, plugins.findById(id).orElseThrow().getContent());
        assertEquals("PLUGIN_ID = 'ordinary-fixture'\n# ordinary update",
                plugins.findById(ordinary.getId()).orElseThrow().getContent());
        plugins.deleteById(ordinary.getId());
    }

    private ReleasePlanSummary accepted(ConfigurableApplicationContext context, int pluginId, String before, String after)
            throws Exception {
        var sources = context.getBean(SourceService.class);
        var source = sources.create(new CreateSourceRequest("Native plan fixture", null, "source.py", null, before));
        var client = context.getBean(AtReadClient.class);
        var connection = client.connection();
        context.getBean(AtBindingRepository.class).findByInstanceKeyAndPluginId(connection.instanceKey(), pluginId)
                .ifPresent(old -> context.getBean(AtBindingService.class).unbind(old.getSourceId(), old.getSha256()));
        var observation = new AtObservation(UUID.randomUUID().toString(), connection, Instant.now(),
                List.of(new AtPluginSummary(pluginId, "fixture", null, true, true, 1, "python",
                        "deployment-fixture", "d".repeat(64), false)), "metadata_observed", "synthetic metadata only", List.of());
        when(client.requireObservation(observation.sha256())).thenReturn(observation);
        context.getBean(AtBindingService.class).bind(source.id(), new BindAtRequest(null, observation.sha256(), pluginId));
        var probes = context.getBean(ProbeStore.class);
        String runId = context.getBean(ProbeService.class).start(source.id()).id();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (Set.of("QUEUED", "RUNNING").contains(probes.get(runId).status())
                && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals("COMPLETED", probes.get(runId).status(), probes.get(runId).toString());
        var repairs = context.getBean(RepairStore.class);
        var repair = repairs.create(source.id(), new StartRepairRequest(runId, source.artifactSha256()));
        String candidateId = context.getBean(RepairService.class).propose(repair.id(),
                new RepairProposal(1, source.artifactSha256(), "source.py", "Remove duplicate method",
                        "Trusted deployment fixture", List.of("S03"), List.of(new RepairEdit(before, after)), null)).id();
        deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (Set.of("QUEUED", "RUNNING").contains(repairs.candidate(candidateId).status())
                && System.nanoTime() < deadline) Thread.sleep(20);
        var candidate = repairs.candidate(candidateId);
        assertEquals("STATIC_VALIDATED", candidate.status(), String.valueOf(candidate.result()));
        // Only the isolated acceptance receipt is synthetic; all plan/store/patch services are real.
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) candidate.result().deepCopy();
        result.put("repairSuccessful", true).put("evidenceClass", "synthetic_native_plan_fixture");
        result.putObject("runtimeAcceptance").put("successful", true);
        var jdbc = context.getBean(JdbcTemplate.class);
        jdbc.update("UPDATE maintenance_repair_candidate SET status = 'RUNTIME_OBSERVED', result_json = ? WHERE id = ?",
                result.toString(), candidateId);
        jdbc.update("UPDATE maintenance_repair SET status = 'RUNTIME_OBSERVED' WHERE id = ?", repair.id());
        var plan = context.getBean(ReleasePlanService.class).create(source.id(),
                new StartReleasePlanRequest(candidateId, source.artifactSha256(), candidate.sha256(), UUID.randomUUID().toString()));
        assertTrue(plan.localApplyEligible());
        assertEquals(pluginId, context.getBean(ReleasePlanService.class).frozen(plan.id())
                .path("repair").path("frozen").path("baseline").path("binding").path("plugin").path("id").asInt());
        return plan;
    }

    private JsonNode request(ReleasePlanSummary plan, String baseline, String generation, String key) {
        var json = mapper.createObjectNode().put("planId", plan.id()).put("expectedFrozenSha256", plan.frozenSha256())
                .put("expectedPluginSha256", Hashes.sha256(baseline)).put("expectedWorkerGeneration", generation)
                .put("requestKey", key).put("interruptIssuedPlayback", false);
        return json;
    }
    private static Plugin plugin(String content) {
        var plugin = new Plugin();
        plugin.setName("Deployment fixture");
        plugin.setExternalId("deployment-fixture");
        plugin.setUrl("https://private-fixture.invalid/source.py");
        plugin.setSortOrder(0);
        plugin.setVersion(1);
        plugin.setContent(content);
        return plugin;
    }
    private static String spider(String marker, boolean fail) {
        return "PLUGIN_ID = 'deployment-fixture'\nclass Spider:\n    def init(self, extend):\n        "
                + (fail ? "raise ValueError('trusted fixture')\n" : "return ''\n")
                + "    def getName(self):\n        return 'fixture'\n"
                + "    def homeContent(self, filter):\n        return {'marker': '" + marker + "'}\n";
    }
    private void configure(ConfigurableApplicationContext context) throws Exception {
        when(context.getBean(AtReadClient.class).connection()).thenReturn(
                new AtConnection(true, "deployment-fixture", "a".repeat(64), "b".repeat(64), "fixture", "configured"));
        var subscriptions = context.getBean(SubscriptionService.class);
        ReflectionTestUtils.setField(subscriptions, "objectMapper", mapper);
        ReflectionTestUtils.setField(subscriptions, "pluginFilterRepository",
                context.getBean(cn.har01d.alist_tvbox.entity.PluginFilterRepository.class));
        doCallRealMethod().when(subscriptions).buildT4PluginExtend(any(Plugin.class), anyString(), anyString());
        doReturn(origin(context)).when(subscriptions).readHostAddress("");
        doReturn("fixture-token").when(subscriptions).getCurrentOrFirstToken();
        ReflectionTestUtils.setField(context.getBean(PluginWorkerService.class), "cacheDir", directory);
    }
    private ConfigurableApplicationContext open(boolean enabled) {
        return new SpringApplicationBuilder(Harness.class).run(
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("deployment").toString().replace('\\', '/'),
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.sql.init.mode=never",
                "--spring.http.converters.preferred-json-mapper=jackson2", "--maintenance.scheduling.enabled=false",
                "--maintenance.at-native.deployment-enabled=" + enabled, "--maintenance.model.enabled=false",
                "--maintenance.at.origin=", "--maintenance.media.enabled=false",
                "--maintenance.container.enabled=false", "--maintenance.managed-source.enabled=false");
    }
    private static String generation(PluginWorkerService workers) {
        return workers.runtimeStatus("deployment-fixture", null).path("identity").path("generationId").asText();
    }
    private static String origin(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }
    private JsonNode csrf(HttpClient client, ConfigurableApplicationContext context, String admin) throws Exception {
        var response = get(client, origin(context) + "/api/plugin-maintenance/csrf", admin);
        assertEquals(200, response.statusCode(), response.body());
        var csrf = mapper.readTree(response.body());
        assertFalse(csrf.path("headerName").asText().isBlank());
        assertFalse(csrf.path("token").asText().isBlank());
        return csrf;
    }
    private HttpResponse<String> postWithCsrf(HttpClient client, ConfigurableApplicationContext context,
            String url, String admin, JsonNode body) throws Exception {
        return post(client, url, admin, body, csrf(client, context, admin));
    }
    private static HttpResponse<String> get(HttpClient client, String url, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        if (token != null) request.header("Authorization", token);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> post(HttpClient client, String url, String token, JsonNode body, JsonNode csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json").header("Authorization", token);
        if (csrf != null) request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
