package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:at-plugin-input-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=at-input-test-only",
        "maintenance.scheduling.enabled=false", "maintenance.model.enabled=false",
        "maintenance.media.enabled=false", "maintenance.container.enabled=false"
})
class AtPluginInputIntegrationTest {
    private static final String SOURCE = "# \u4e2d\u6587\r\nraise RuntimeError('AT_SOURCE_MUST_NOT_EXECUTE')\r\nclass Spider: pass\r\n";
    private static final AtomicInteger READS = new AtomicInteger();
    private static final HttpServer SERVER;
    private static volatile String source = SOURCE;
    private static volatile String sourceLocation = "/static/owned.py";
    private static volatile int sourceStatus = 200;
    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/", exchange -> {
                assertEquals("GET", exchange.getRequestMethod());
                assertEquals("SYNTHETIC_AT_KEY", exchange.getRequestHeaders().getFirst("X-API-KEY"));
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                boolean metadata = exchange.getRequestURI().getPath().equals("/api/plugins");
                if (!metadata) READS.incrementAndGet();
                byte[] body = (metadata ? """
                        [{"id":7,"name":"owned","url":"%s","enabled":true,"t4Visible":true}]
                        """.formatted(sourceLocation) : source).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", metadata ? "application/json" : "application/octet-stream");
                exchange.sendResponseHeaders(metadata ? 200 : sourceStatus, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            SERVER.start();
        } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("maintenance.at.origin", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("maintenance.at.instance-id", () -> "owned-at");
        registry.add("maintenance.at.allow-http", () -> true);
        registry.add("maintenance.at.auth-mode", () -> "api_key");
        registry.add("maintenance.at.api-key", () -> "SYNTHETIC_AT_KEY");
        registry.add("maintenance.at.session-token", () -> "");
    }
    @AfterAll static void stop() { SERVER.stop(0); }
    @Autowired PluginInputService inputs;
    @Autowired AtReadClient at;
    @Autowired AtBindingService bindings;
    @Autowired SourceService sources;
    @Autowired ProbeService probes;
    @Autowired ProbeStore probeStore;
    @Autowired ManagedSourceSetup setup;
    @Autowired RepairStore repairs;
    @Autowired ModelStore models;
    @Autowired MaintenanceWorkflowService workflows;
    @Autowired UpstreamService upstream;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean PublicPythonSourceReader publicSources;
    @LocalServerPort int port;
    private AtObservation observation;

    @BeforeEach
    void resetObservation() {
        at.configureExclusions("");
        source = SOURCE;
        sourceStatus = 200;
        sourceLocation = "/static/owned.py";
        jdbc.update("delete from maintenance_at_binding");
        ReflectionTestUtils.setField(at, "cached", null);
        ReflectionTestUtils.setField(at, "nextAttemptNanos", 0L);
        observation = at.observe();
    }

    @Test
    void explicitExclusionRejectsPreviewConfirmationAndBindingBeforeAnySourceRead() {
        var preview = preview();
        var request = confirm(preview);
        var source = sources.create(new CreateSourceRequest("owned", null, "owned.py", null, SOURCE));
        int reads = READS.get();
        long[] before = counts();
        at.configureExclusions("7");
        assertError("AT_PLUGIN_MAINTENANCE_EXCLUDED", this::preview);
        assertError("AT_PLUGIN_MAINTENANCE_EXCLUDED", () -> inputs.confirmAt(request));
        assertError("AT_PLUGIN_MAINTENANCE_EXCLUDED", () -> bindings.bind(source.id(),
                new BindAtRequest(null, observation.sha256(), 7)));
        assertEquals(reads, READS.get());
        assertArrayEquals(before, counts());
    }

    @Test
    void exclusionAlsoBlocksExistingImportRetriesAndMaintenanceButLeavesStoredEvidenceReadable() {
        var preview = preview();
        var request = confirm(preview);
        var saved = inputs.confirmAt(request);
        String id = saved.sourceId();
        int reads = READS.get();
        long[] before = counts();
        at.configureExclusions("7");
        ReflectionTestUtils.setField(at, "cached", null);
        assertError("AT_PLUGIN_MAINTENANCE_EXCLUDED", () -> inputs.confirmAt(request));
        assertError("AT_PLUGIN_MAINTENANCE_EXCLUDED", () -> probes.start(id, "managed_source"));
        assertError("AT_PLUGIN_MAINTENANCE_EXCLUDED", () -> probes.start(id));
        assertError("AT_PLUGIN_MAINTENANCE_EXCLUDED", () -> probes.startMaintenance(id, null));
        assertError("AT_PLUGIN_MAINTENANCE_EXCLUDED", () -> workflows.start(id, null));
        assertError("AT_PLUGIN_MAINTENANCE_EXCLUDED", () -> probeStore.create(id, "inspector", "knowledge"));
        assertError("AT_PLUGIN_MAINTENANCE_EXCLUDED", () -> setup.ensure(id, saved.artifactSha256()));
        assertError("AT_PLUGIN_MAINTENANCE_EXCLUDED", () -> repairs.create(id, null));
        var repair = mock(RepairSummary.class);
        when(repair.sourceId()).thenReturn(id);
        assertError("AT_PLUGIN_MAINTENANCE_EXCLUDED", () -> ReflectionTestUtils.invokeMethod(models, "requireInputs", repair));
        assertEquals(saved.artifactSha256(), sources.get(id).artifactSha256());
        assertEquals(SOURCE, sources.artifact(id, saved.artifactSha256()).getBody());
        assertEquals(saved, inputs.list(id).getFirst());
        assertEquals(reads, READS.get());
        assertArrayEquals(before, counts());
    }

    @Test
    void previewConfirmAndRetryPreserveExactBytesProvenanceAndLocalBinding() throws Exception {
        long[] before = counts();
        var preview = preview();
        assertArrayEquals(before, counts());
        var request = confirm(preview);
        var saved = inputs.confirmAt(request);
        assertEquals(SOURCE, sources.artifact(saved.sourceId(), saved.artifactSha256()).getBody());
        assertEquals(Hashes.sha256(SOURCE), saved.evidence().path("atSource").path("sourceSha256").asText());
        assertEquals(observation.sha256(), saved.evidence().path("atSource").path("observationSha256").asText());
        assertFalse(saved.evidence().path("sourceExecuted").asBoolean());
        assertFalse(saved.evidence().path("atSource").path("productionModified").asBoolean());
        assertEquals(7, bindings.get(saved.sourceId()).snapshot().path("plugin").path("id").asInt());
        assertEquals(observation.sha256(), bindings.get(saved.sourceId()).snapshot().path("observationSha256").asText());
        int reads = READS.get();
        long[] after = counts();
        ReflectionTestUtils.setField(at, "cached", null);
        assertEquals(saved, inputs.confirmAt(request), "successful retries must work after cache expiry/restart");
        assertEquals(reads, READS.get());
        assertArrayEquals(after, counts());
        String response = mapper.writeValueAsString(saved);
        for (String secret : new String[]{"AT_SOURCE_MUST_NOT_EXECUTE", "SYNTHETIC_AT_KEY", "/static/owned.py",
                Base64.getEncoder().encodeToString(SOURCE.getBytes(StandardCharsets.UTF_8))}) {
            assertFalse(response.contains(secret), secret);
        }
    }

    @Test
    void publicSourcePreviewConfirmAndDriftKeepProvenanceWithoutExecutingSource() {
        sourceLocation = "https://example.com/owned.py";
        ReflectionTestUtils.setField(at, "cached", null);
        ReflectionTestUtils.setField(at, "nextAttemptNanos", 0L);
        observation = at.observe();
        byte[] bytes = SOURCE.getBytes(StandardCharsets.UTF_8);
        when(publicSources.read(URI.create(sourceLocation), 1_048_576)).thenReturn(bytes);
        int reads = READS.get();
        var preview = preview();
        long[] before = counts();
        when(publicSources.read(URI.create(sourceLocation), 1_048_576))
                .thenReturn((SOURCE + "# drift\n").getBytes(StandardCharsets.UTF_8));
        assertError("PLUGIN_INPUT_PREVIEW_CHANGED", () -> inputs.confirmAt(confirm(preview)));
        assertArrayEquals(before, counts());
        when(publicSources.read(URI.create(sourceLocation), 1_048_576)).thenReturn(bytes);
        var saved = inputs.confirmAt(confirm(preview));
        assertEquals("at_public_https_source_read", saved.evidence().path("atSource").path("authority").asText());
        assertTrue(saved.evidence().path("atSource").path("atCredentialsForwarded").isBoolean());
        assertFalse(saved.evidence().path("atSource").path("atCredentialsForwarded").asBoolean());
        assertFalse(saved.evidence().path("sourceExecuted").asBoolean());
        assertEquals(Hashes.sha256(SOURCE), saved.artifactSha256());
        assertEquals(7, bindings.get(saved.sourceId()).snapshot().path("plugin").path("id").asInt());
        assertEquals(reads, READS.get());
    }

    @Test
    void sourceDriftAndBindingConflictRollbackEveryCreatedRow() {
        var preview = preview();
        long[] before = counts();
        source = SOURCE + "# changed\n";
        assertError("PLUGIN_INPUT_PREVIEW_CHANGED", () -> inputs.confirmAt(confirm(preview)));
        assertArrayEquals(before, counts());
        source = SOURCE;
        var saved = inputs.confirmAt(confirm(preview));
        long[] after = counts();
        assertError("AT_PLUGIN_ALREADY_BOUND", () -> inputs.confirmAt(confirm(preview)));
        assertArrayEquals(after, counts(), "failed binding must not leave an orphan source, artifact or input");
        assertEquals(7, bindings.get(saved.sourceId()).snapshot().path("plugin").path("id").asInt());
    }

    @Test
    void authorizationAndKeyReuseCannotReadProductionOrCreateRows() {
        int reads = READS.get();
        assertError("PLUGIN_PLAINTEXT_AUTHORIZATION_REQUIRED", () -> inputs.previewAt(
                new PreviewAtPluginInputRequest(observation.sha256(), 7, false)));
        assertEquals(reads, READS.get());
        var preview = preview();
        var request = confirm(preview);
        inputs.confirmAt(request);
        reads = READS.get();
        long[] before = counts();
        assertError("PLUGIN_INPUT_REQUEST_KEY_REUSED", () -> inputs.confirmAt(new ConfirmAtPluginInputRequest(
                request.observationSha256(), 7, true, request.expectedEvidenceSha256(),
                request.requestKey(), "different", "source.py")));
        assertEquals(reads, READS.get());
        assertArrayEquals(before, counts());
    }

    @Test
    void httpEntryRequiresAdminCsrfAndValidIdentityWithoutReturningSource() throws Exception {
        try (var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    "admin:at-input-test-only".getBytes(StandardCharsets.UTF_8));
            String path = "/at/plugin-inputs/preview";
            var body = new PreviewAtPluginInputRequest(observation.sha256(), 7, true);
            assertEquals(401, http.send(HttpRequest.newBuilder(uri("/sources")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, http.send(HttpRequest.newBuilder(uri(path)).POST(
                    HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", auth)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var csrf = mapper.readTree(http.send(HttpRequest.newBuilder(uri("/csrf")).header("Authorization", auth)
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            var previewResponse = http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", auth)
                    .header("Content-Type", "application/json").header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, previewResponse.statusCode(), previewResponse.body());
            var preview = mapper.readValue(previewResponse.body(), PluginInputSummary.class);
            var confirmed = http.send(HttpRequest.newBuilder(uri("/at/plugin-inputs")).header("Authorization", auth)
                    .header("Content-Type", "application/json").header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(confirm(preview)))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, confirmed.statusCode(), confirmed.body());
            assertFalse(confirmed.body().contains("AT_SOURCE_MUST_NOT_EXECUTE"));
            var invalid = http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", auth)
                    .header("Content-Type", "application/json").header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"pluginId\":0,\"authorized\":true}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode());
        }
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/plugin-maintenance" + path); }

    @Test
    void fetchedAtUpstreamUsesImportBaseAndPreservesLocalRepairWithAnImmutableReplay() {
        var saved = inputs.confirmAt(confirm(preview()));
        String id = saved.sourceId();
        var local = sources.addArtifact(id, new AddArtifactRequest(saved.artifactSha256(), SOURCE + "# local fix\n"));
        source = SOURCE + "# upstream change\r\n";
        var binding = bindings.get(id);
        var request = new FetchAtUpstreamRequest(UUID.randomUUID().toString(), null,
                local.artifactSha256(), binding.sha256(), true);
        int reads = READS.get();
        var event = upstream.fetchAt(id, request);
        assertEquals("FETCH_AT", event.operation());
        assertEquals(event.after(), upstream.get(id));
        assertEquals("DIVERGED", event.after().status());
        assertEquals(saved.artifactSha256(), event.after().baseSha256());
        assertEquals(Hashes.sha256(source), event.after().headSha256());
        assertEquals(local.artifactSha256(), sources.get(id).artifactSha256());
        assertEquals("at_readonly_source", event.after().authority());
        assertEquals("at_static_source_read", event.after().headObservation().path("authority").asText());
        assertEquals(binding.sha256(), event.after().headObservation().path("bindingSha256").asText());
        assertEquals(3, sources.versions(id).size());
        assertTrue(probeStore.list(id).isEmpty());
        assertEquals(reads + 1, READS.get());
        source = SOURCE + "# another remote edit\n";
        assertEquals(event, upstream.fetchAt(id, request));
        assertEquals(reads + 1, READS.get(), "replay must not read AT again");
        assertEquals(1, upstream.history(id).size());
        assertEquals(event, upstream.history(id).getFirst());
    }

    @Test
    void fetchedIdenticalBytesAndExportPreserveReadEvidenceWithoutClaimingExecution() throws Exception {
        var saved = inputs.confirmAt(confirm(preview()));
        var binding = bindings.get(saved.sourceId());
        var event = upstream.fetchAt(saved.sourceId(), new FetchAtUpstreamRequest(UUID.randomUUID().toString(),
                null, saved.artifactSha256(), binding.sha256(), true));
        assertEquals("IN_SYNC", event.after().status());
        assertEquals(event.after(), upstream.get(saved.sourceId()));
        assertEquals(1, sources.versions(saved.sourceId()).size());
        assertFalse(event.after().runtimeApplied());
        assertFalse(event.after().publishEligible());
        assertFalse(event.after().headObservation().path("sourceExecuted").asBoolean());
        assertFalse(mapper.writeValueAsString(event).contains("SYNTHETIC_AT_KEY"));
        assertFalse(mapper.writeValueAsString(event).contains("AT_SOURCE_MUST_NOT_EXECUTE"));
        try (var zip = new ZipInputStream(new ByteArrayInputStream(upstream.export(saved.sourceId())))) {
            boolean found = false;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("upstream-observation.json")) {
                    found = true;
                    assertEquals(event.after().headObservation(), mapper.readTree(zip.readAllBytes()));
                }
            }
            assertTrue(found);
        }
        var manual = upstream.observe(saved.sourceId(), new ObserveUpstreamRequest(UUID.randomUUID().toString(),
                event.after().revision(), saved.artifactSha256(), SOURCE));
        assertNull(manual.after().headObservation());
        assertEquals("operator_supplied_source", manual.after().authority());
        assertNotNull(upstream.history(saved.sourceId()).get(1).after().headObservation());
    }

    @Test
    void atUpstreamRequiresPermissionExpectedBindingAndMaintenanceAdmissionBeforeReading() {
        var saved = inputs.confirmAt(confirm(preview()));
        String id = saved.sourceId();
        var binding = bindings.get(id);
        int reads = READS.get();
        assertError("PLUGIN_PLAINTEXT_AUTHORIZATION_REQUIRED", () -> upstream.fetchAt(id,
                new FetchAtUpstreamRequest(UUID.randomUUID().toString(), null, saved.artifactSha256(), binding.sha256(), false)));
        assertError("AT_BINDING_CHANGED", () -> upstream.fetchAt(id,
                new FetchAtUpstreamRequest(UUID.randomUUID().toString(), null, saved.artifactSha256(), "f".repeat(64), true)));
        at.configureExclusions("7");
        assertError("AT_PLUGIN_MAINTENANCE_EXCLUDED", () -> upstream.fetchAt(id,
                new FetchAtUpstreamRequest(UUID.randomUUID().toString(), null, saved.artifactSha256(), binding.sha256(), true)));
        assertEquals(reads, READS.get());
        assertNull(upstream.get(id));
        assertTrue(upstream.history(id).isEmpty());
    }

    @Test
    void atUpstreamDoesNotGuessAnImportBaseForAnArbitrarilyBoundSource() {
        var saved = sources.create(new CreateSourceRequest("manual", null, "manual.py", null, SOURCE));
        var binding = bindings.bind(saved.id(), new BindAtRequest(null, observation.sha256(), 7));
        int reads = READS.get();
        assertError("UPSTREAM_AT_BASELINE_REQUIRED", () -> upstream.fetchAt(saved.id(),
                new FetchAtUpstreamRequest(UUID.randomUUID().toString(), null, saved.artifactSha256(), binding.sha256(), true)));
        assertEquals(reads, READS.get());
        assertNull(upstream.get(saved.id()));
        var initialized = upstream.initialize(saved.id(), new InitializeUpstreamRequest(UUID.randomUUID().toString(),
                saved.artifactSha256(), saved.artifactSha256()));
        assertEquals("IN_SYNC", upstream.fetchAt(saved.id(), new FetchAtUpstreamRequest(UUID.randomUUID().toString(),
                initialized.after().revision(), saved.artifactSha256(), binding.sha256(), true)).after().status());
    }

    @Test
    void failedAtUpstreamFetchRollsBackAndAnExpectedRevisionIsConsumedOnlyOnce() {
        var saved = inputs.confirmAt(confirm(preview()));
        String id = saved.sourceId();
        var binding = bindings.get(id);
        var request = new FetchAtUpstreamRequest(UUID.randomUUID().toString(), null,
                saved.artifactSha256(), binding.sha256(), true);
        sourceStatus = 503;
        assertError("AT_SOURCE_HTTP_ERROR", () -> upstream.fetchAt(id, request));
        assertNull(upstream.get(id));
        assertTrue(upstream.history(id).isEmpty());
        assertEquals(1, sources.versions(id).size());
        sourceStatus = 200;
        var observed = upstream.fetchAt(id, request);
        int reads = READS.get();
        assertError("UPSTREAM_CHANGED", () -> upstream.fetchAt(id, new FetchAtUpstreamRequest(UUID.randomUUID().toString(),
                null, saved.artifactSha256(), binding.sha256(), true)));
        assertEquals(reads, READS.get());
        assertEquals(observed, upstream.history(id).getFirst());
    }

    @Test
    void publicAtUpstreamRetainsAnonymousPublicReaderProvenance() {
        sourceLocation = "https://example.com/upstream.py";
        ReflectionTestUtils.setField(at, "cached", null);
        ReflectionTestUtils.setField(at, "nextAttemptNanos", 0L);
        observation = at.observe();
        when(publicSources.read(URI.create(sourceLocation), 1_048_576)).thenReturn(SOURCE.getBytes(StandardCharsets.UTF_8));
        var saved = inputs.confirmAt(confirm(preview()));
        var binding = bindings.get(saved.sourceId());
        var event = upstream.fetchAt(saved.sourceId(), new FetchAtUpstreamRequest(UUID.randomUUID().toString(),
                null, saved.artifactSha256(), binding.sha256(), true));
        assertEquals("at_public_https_source_read", event.after().headObservation().path("authority").asText());
        assertFalse(event.after().headObservation().path("atCredentialsForwarded").asBoolean());
    }

    private PluginInputSummary preview() {
        return inputs.previewAt(new PreviewAtPluginInputRequest(observation.sha256(), 7, true));
    }
    private ConfirmAtPluginInputRequest confirm(PluginInputSummary preview) {
        return new ConfirmAtPluginInputRequest(observation.sha256(), 7, true, preview.evidenceSha256(),
                UUID.randomUUID().toString(), "owned", "source.py");
    }
    private long[] counts() {
        return new long[]{jdbc.queryForObject("select count(*) from maintenance_source", Long.class),
                jdbc.queryForObject("select count(*) from maintenance_artifact", Long.class),
                jdbc.queryForObject("select count(*) from maintenance_plugin_input", Long.class),
                jdbc.queryForObject("select count(*) from maintenance_at_binding", Long.class)};
    }
    private void assertError(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ResponseStatusException.class, action).getReason());
    }
}
