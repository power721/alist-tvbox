package cn.har01d.alist_tvbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PluginWorkerIdentityTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void observingAnAbsentWorkerDoesNotPrepareRuntime() {
        var service = new PluginWorkerService(mapper);
        var status = service.runtimeStatus("not-started", null);
        assertEquals("not_running", status.path("configurationState").asText());
        assertFalse(status.path("configurationCompared").asBoolean());
        assertNull(ReflectionTestUtils.getField(service, "workerScript"));
        assertNull(((AtomicReference<?>) ReflectionTestUtils.getField(service, "available")).get());
    }

    @Test
    void snapshotBindsActualLoadAndNeverReloadsChangedSourceOrTouchesIdleTime() throws Exception {
        var service = new PluginWorkerService(mapper);
        try {
            ReflectionTestUtils.setField(service, "cacheDir", directory);
            assertTrue(service.isAvailable(), "Set T4_PYTHON to the prepared project Python");
            Path source = directory.resolve("trusted.py");
            String first = spider("first");
            Files.writeString(source, first);
            String extend = extend(source);
            assertEquals("first", service.call("identity", extend, "home", Map.of()).path("marker").asText());
            var workers = (Map<?, ?>) ReflectionTestUtils.getField(service, "workers");
            Object worker = workers.get("identity");
            Instant lastUsed = (Instant) ReflectionTestUtils.getField(worker, "lastUsed");
            var snapshot = service.runtimeStatus("identity", extend);
            var identity = snapshot.path("identity");
            assertTrue(identity.path("filterConfigurationValid").asBoolean());
            assertTrue(identity.path("allConfiguredFiltersLoaded").asBoolean());
            assertEquals(0, identity.path("filters").size());
            assertEquals("matching", snapshot.path("configurationState").asText());
            assertEquals(sha(first), identity.path("sourceTextSha256").asText());
            assertEquals(sha(first), identity.path("packageTextSha256").asText());
            assertEquals(sha(Files.readAllBytes((Path) ReflectionTestUtils.getField(service, "workerScript"))),
                    identity.path("workerSha256").asText());
            assertEquals(sha(Files.readAllBytes((Path) ReflectionTestUtils.getField(service, "atvpScript"))),
                    identity.path("atvpSha256").asText());
            String generation = identity.path("generationId").asText();
            assertTrue(generation.matches("[a-f0-9]{32}"));
            ((ObjectNode) identity).put("sourceTextSha256", "caller-mutated");
            assertEquals(sha(first), service.runtimeStatus("identity", null)
                    .path("identity").path("sourceTextSha256").asText());
            assertEquals(lastUsed, ReflectionTestUtils.getField(worker, "lastUsed"));

            Files.writeString(source, spider("second"));
            assertEquals("pending_restart", service.runtimeStatus("identity", extend + " ")
                    .path("configurationState").asText());
            assertEquals("loaded", service.runtimeStatus("identity", null).path("configurationState").asText());
            assertEquals("first", service.call("identity", extend, "home", Map.of()).path("marker").asText());
            assertEquals(sha(first), service.runtimeStatus("identity", null)
                    .path("identity").path("sourceTextSha256").asText());

            service.shutdown();
            assertEquals("not_running", service.runtimeStatus("identity", null).path("configurationState").asText());
            assertEquals("second", service.call("identity", extend, "home", Map.of()).path("marker").asText());
            var reloaded = service.runtimeStatus("identity", null).path("identity");
            assertEquals(sha(spider("second")), reloaded.path("sourceTextSha256").asText());
            assertNotEquals(generation, reloaded.path("generationId").asText());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void failedInitCannotPublishCompletedIdentity() throws Exception {
        var service = new PluginWorkerService(mapper);
        try {
            ReflectionTestUtils.setField(service, "cacheDir", directory);
            assertTrue(service.isAvailable(), "Set T4_PYTHON to the prepared project Python");
            Path source = directory.resolve("broken.py");
            Files.writeString(source, "class Spider:\n    def init(self, extend):\n        raise ValueError('fixture')\n");
            assertThrows(cn.har01d.alist_tvbox.exception.BadGatewayException.class,
                    () -> service.call("broken-identity", extend(source), "home", Map.of()));
            var status = service.runtimeStatus("broken-identity", null);
            assertEquals("not_running", status.path("configurationState").asText());
            assertFalse(status.has("identity"));
            assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(service, "workers")).isEmpty());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void maintenanceWindowFencesCallsChecksGenerationAndRequiresPlaybackConsent() throws Exception {
        var service = new PluginWorkerService(mapper);
        try {
            ReflectionTestUtils.setField(service, "cacheDir", directory);
            Path source = directory.resolve("maintenance.py");
            Files.writeString(source, spider("before"));
            String extend = extend(source);
            service.call("window", extend, "home", Map.of());
            String generation = service.runtimeStatus("window", null).path("identity").path("generationId").asText();
            assertThrows(IllegalStateException.class, () -> service.openMaintenance("window", "0".repeat(32), true));
            Object worker = ((Map<?, ?>) ReflectionTestUtils.getField(service, "workers")).get("window");
            ReflectionTestUtils.setField(worker, "activeCalls", 1);
            assertEquals("AT_WORKER_CALL_BUSY", assertThrows(IllegalStateException.class,
                    () -> service.openMaintenance("window", generation, true)).getMessage());
            ReflectionTestUtils.setField(worker, "activeCalls", 0);
            ReflectionTestUtils.setField(worker, "playIssued", true);
            assertEquals("AT_PLAYBACK_INTERRUPTION_CONFIRMATION_REQUIRED", assertThrows(IllegalStateException.class,
                    () -> service.openMaintenance("window", generation, false)).getMessage());
            try (var window = service.openMaintenance("window", generation, true)) {
                assertEquals("maintenance_blocked", service.runtimeStatus("window", null)
                        .path("configurationState").asText());
                assertThrows(IllegalStateException.class, () -> service.call("window", extend, "home", Map.of()));
                assertThrows(IllegalStateException.class, () -> service.openMaintenance("window", generation, true));
                window.stop();
                assertNull(window.identity());
                Files.writeString(source, spider("after"));
                var identity = window.load(extend, sha(spider("after")));
                assertNotEquals(generation, identity.path("generationId").asText());
            }
            assertEquals("after", service.call("window", extend, "home", Map.of()).path("marker").asText());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void homeMergesRecommendationsInTheActualWorkerAndKeepsItsGeneration() throws Exception {
        var service = new PluginWorkerService(mapper);
        try {
            ReflectionTestUtils.setField(service, "cacheDir", directory);
            Path source = directory.resolve("home.py");
            Files.writeString(source, homeSpider(false));
            String extend = extend(source);
            var home = service.call("home-merge", extend, "home", Map.of("filter", false));
            assertEquals("lab", home.path("class").get(0).path("type_id").asText());
            assertTrue(home.path("filters").has("lab"));
            assertFalse(home.path("filtered").asBoolean());
            assertEquals(2, home.path("list").size());
            assertEquals("rec", home.path("list").get(0).path("vod_id").asText());
            assertEquals("rec", home.path("list").get(1).path("vod_id").asText());
            assertEquals("second", home.path("list").get(1).path("vod_name").asText());
            assertFalse(home.has("ignored"));
            String generation = service.runtimeStatus("home-merge", null)
                    .path("identity").path("generationId").asText();
            var category = service.call("home-merge", extend, "category", Map.of("t", "lab"));
            assertEquals(mapper.readTree("[\"home\",\"video\"]"), category.path("events"));
            assertEquals(generation, service.runtimeStatus("home-merge", null)
                    .path("identity").path("generationId").asText());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void homeRecommendationFailureRemainsAnErrorWithoutRetryingOrLosingTheWorker() throws Exception {
        var service = new PluginWorkerService(mapper);
        try {
            ReflectionTestUtils.setField(service, "cacheDir", directory);
            Path source = directory.resolve("home-error.py");
            Files.writeString(source, homeSpider(true));
            String extend = extend(source);
            assertThrows(cn.har01d.alist_tvbox.exception.BadGatewayException.class,
                    () -> service.call("home-error", extend, "home", Map.of()));
            var category = service.call("home-error", extend, "category", Map.of("t", "lab"));
            assertEquals(mapper.readTree("[\"home\",\"video\"]"), category.path("events"));
        } finally {
            service.shutdown();
        }
    }

    private static String homeSpider(boolean fail) {
        return """
                import json
                class Spider:
                    def init(self, extend): self.events = []
                    def getName(self): return 'home-fixture'
                    def homeContent(self, filter):
                        self.events.append('home')
                        return json.dumps({'class': [{'type_id': 'lab'}], 'filters': {'lab': []},
                                           'filtered': filter, 'list': [{'vod_id': 'base'}]})
                    def homeVideoContent(self):
                        self.events.append('video')
                """ + (fail ? "        raise ValueError('HOME_RECOMMENDATION_FIXTURE_FAILED')\n" : """
                        return json.dumps({'list': [{'vod_id': 'rec', 'vod_name': 'first'},
                                                    {'vod_id': 'rec', 'vod_name': 'second'}], 'ignored': True})
                """) + """
                    def categoryContent(self, tid, pg, filter, extend): return {'list': [], 'events': self.events}
                """;
    }

    @Test
    void maintenanceLoadRejectsWrongSourceAndRetiresRejectedReplacement() throws Exception {
        var service = new PluginWorkerService(mapper);
        try {
            ReflectionTestUtils.setField(service, "cacheDir", directory);
            Path source = directory.resolve("wrong-source.py");
            Files.writeString(source, spider("actual"));
            try (var window = service.openMaintenance("mismatch", null, false)) {
                window.stop();
                assertEquals("AT_WORKER_SOURCE_IDENTITY_MISMATCH", assertThrows(IllegalStateException.class,
                        () -> window.load(extend(source), "0".repeat(64))).getMessage());
                assertNull(window.identity());
            }
            assertEquals("not_running", service.runtimeStatus("mismatch", null).path("configurationState").asText());
        } finally {
            service.shutdown();
        }
    }

    private String extend(Path source) throws Exception {
        return Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(
                Map.of("source", source.toString(), "raw", true)));
    }

    private static String spider(String marker) {
        return "class Spider:\n    def init(self, extend):\n        return ''\n"
                + "    def getName(self):\n        return 'identity-fixture'\n"
                + "    def homeContent(self, filter):\n        return {'marker': '" + marker + "'}\n";
    }

    private static String sha(String value) throws Exception { return sha(value.getBytes(StandardCharsets.UTF_8)); }
    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
