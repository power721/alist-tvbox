package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "maintenance.scheduling.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:maintenance-at-binding-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only"
})
class AtBindingIntegrationTest {
    static final HttpServer SERVER;
    static final String RUNTIME_IDENTITY = """
            {"pluginId":7,"siteKey":"stable-key","sourceSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
             "runtimeRevision":"t4-direct-v1","loadState":"LOADED","loaded":true,"workerGeneration":"fedcba9876543210fedcba9876543210"}
            """;
    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/api/plugins", exchange -> {
                assertEquals("GET", exchange.getRequestMethod());
                assertEquals("SYNTHETIC_SESSION_SECRET", exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = exchange.getRequestURI().getPath().contains("runtime-identity")
                        ? RUNTIME_IDENTITY.getBytes(StandardCharsets.UTF_8)
                        : AtReadClientTest.BODY.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            SERVER.start();
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("maintenance.at.origin", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("maintenance.at.instance-id", () -> "synthetic-at");
        registry.add("maintenance.at.session-token", () -> "SYNTHETIC_SESSION_SECRET");
        registry.add("maintenance.at.allow-http", () -> true);
        registry.add("maintenance.at.revision", () -> "synthetic-declared");
        registry.add("maintenance.at.runtime-identity-path",
                () -> "/api/plugins/{pluginId}/runtime-identity");
    }
    @AfterAll static void stop() { SERVER.stop(0); }
    @Autowired AtReadClient client;
    @Autowired AtBindingService bindings;
    @Autowired SourceService sources;
    @Autowired ProbeService probes;
    @Autowired ProbeStore store;
    @Autowired ExportService exports;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;

    @Test
    void httpApiEnforcesAuthenticationCsrfValidationAndUnboundNoContent() throws Exception {
        String source = create(null);
        String base = "http://127.0.0.1:" + port + "/api/plugin-maintenance";
        String bindingPath = "/sources/" + source + "/at-binding";
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                "admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
        try (var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            for (String path : List.of("/at/connection", bindingPath)) {
                assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            var csrf = mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/csrf"))
                    .header("Authorization", authorization).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            for (String path : List.of("/at/observations", bindingPath, bindingPath + "/unbind")) {
                assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Authorization", authorization).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            var empty = http.send(HttpRequest.newBuilder(URI.create(base + bindingPath))
                    .header("Authorization", authorization).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, empty.statusCode());
            assertEquals("", empty.body());
            var observation = mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/at/observations"))
                    .header("Authorization", authorization)
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).body());
            var request = HttpRequest.newBuilder(URI.create(base + bindingPath))
                    .header("Authorization", authorization).header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText());
            var invalid = http.send(request.POST(HttpRequest.BodyPublishers.ofString(
                    "{\"observationSha256\":\"PRIVATE_INVALID_MARKER\",\"pluginId\":0}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode());
            assertEquals("{\"code\":\"INVALID_REQUEST\"}", invalid.body());
            var saved = http.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                    new BindAtRequest(null, observation.path("sha256").asText(), 7)))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, saved.statusCode(), saved.body());
            var binding = mapper.readTree(saved.body());
            var conflict = http.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                    new BindAtRequest(null, observation.path("sha256").asText(), 8)))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, conflict.statusCode());
            assertEquals("{\"code\":\"AT_BINDING_CHANGED\"}", conflict.body());
            var removed = http.send(HttpRequest.newBuilder(URI.create(base + bindingPath + "/unbind"))
                    .header("Authorization", authorization).header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            new UnbindAtRequest(binding.path("sha256").asText())))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(204, removed.statusCode());
            assertEquals("", removed.body());
        }
        assertNull(bindings.get(source));
    }

    @Test
    void bindingsFreezeInReportsSurviveRebindAndExportBothIdentitiesWithoutSecrets() throws Exception {
        String source = create(null);
        var before = await(probes.start(source).id());
        assertNull(before.bindingSha256());
        var observed = client.observe();
        var first = bindings.bind(source, new BindAtRequest(null, observed.sha256(), 7));
        assertEquals("metadata_observed", first.snapshot().path("authority").asText());
        assertTrue(first.connectionMatches());
        assertEquals("binding_changed", store.get(before.id()).freshness());
        var submitted = probes.start(source);
        var second = bindings.bind(source, new BindAtRequest(first.sha256(), observed.sha256(), 8));
        var done = await(submitted.id());
        assertEquals(first.sha256(), done.bindingSha256());
        assertEquals(first.snapshot(), done.binding());
        assertEquals("binding_changed", done.freshness());
        assertTrue(done.staleInputs().contains("at_binding"));
        var s01 = done.result().path("nodes").get(0).path("evidence");
        assertEquals(first.sha256(), s01.path("bindingSha256").asText());
        Map<String, byte[]> files = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(exports.export(source, null)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), zip.readAllBytes());
        }
        assertEquals(second.sha256(), Hashes.sha256(files.get("at-binding.json")));
        assertEquals(first.sha256(), Hashes.sha256(files.get("probe-at-binding.json")));
        for (byte[] body : files.values()) {
            String text = new String(body, StandardCharsets.UTF_8);
            for (String secret : List.of("LOCATOR_SECRET", "EXT_SECRET", "ERROR_SECRET", "SYNTHETIC_SESSION_SECRET")) {
                assertFalse(text.contains(secret), secret);
            }
        }
        assertEquals("AT_BINDING_CHANGED", assertThrows(ResponseStatusException.class,
                () -> bindings.bind(source, new BindAtRequest(first.sha256(), observed.sha256(), 7))).getReason());
        bindings.unbind(source, second.sha256());
        assertNull(bindings.get(source));
        assertEquals(first.snapshot(), store.get(done.id()).binding());
    }

    @Test
    void externalIdentityTypeAndCrossSourceOwnershipConflictsAreExplicit() {
        var observed = client.observe();
        String mismatch = create("different-external-id");
        assertEquals("AT_EXTERNAL_ID_CONFLICT", assertThrows(ResponseStatusException.class,
                () -> bindings.bind(mismatch, new BindAtRequest(null, observed.sha256(), 7))).getReason());
        String source = create(null);
        assertEquals("AT_PLUGIN_TYPE_UNSUPPORTED", assertThrows(ResponseStatusException.class,
                () -> bindings.bind(source, new BindAtRequest(null, observed.sha256(), 9))).getReason());
        assertEquals("AT_OBSERVATION_CHANGED", assertThrows(ResponseStatusException.class,
                () -> bindings.bind(source, new BindAtRequest(null, "0".repeat(64), 7))).getReason());
        var bound = bindings.bind(source, new BindAtRequest(null, observed.sha256(), 7));
        String other = create(null);
        assertEquals("AT_PLUGIN_ALREADY_BOUND", assertThrows(ResponseStatusException.class,
                () -> bindings.bind(other, new BindAtRequest(null, observed.sha256(), 7))).getReason());
        bindings.unbind(source, bound.sha256());
    }

    @Test
    void runtimeIdentityIsExplicitlyObservedAndChangesBindingAuthority() {
        String source = create(null);
        var observed = client.observe();
        var bound = bindings.bind(source, new BindAtRequest(null, observed.sha256(), 7));
        assertEquals("metadata_observed", bound.snapshot().path("authority").asText());
        assertEquals("derived_from_baseline", bound.snapshot().path("siteKeyAuthority").asText());

        var runtime = bindings.observeRuntimeIdentity(source, bound.sha256());
        assertNotEquals(bound.sha256(), runtime.sha256());
        assertEquals("runtime_observed", runtime.snapshot().path("runtimeIdentityAuthority").asText());
        assertEquals("runtime_observed", runtime.snapshot().path("siteKeyAuthority").asText());
        assertEquals("LOADED", runtime.snapshot().path("runtimeIdentity").path("loadState").asText());
        assertEquals("stable-key", runtime.snapshot().path("runtimeIdentity").path("siteKey").asText());
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                runtime.snapshot().path("runtimeIdentity").path("sourceSha256").asText());
        assertEquals("AT_BINDING_CHANGED",
                assertThrows(ResponseStatusException.class,
                        () -> bindings.observeRuntimeIdentity(source, bound.sha256())).getReason());
        bindings.unbind(source, runtime.sha256());
    }

    @Test
    void databaseUniquenessPreventsConcurrentDoubleOwnership() throws Exception {
        var observation = client.observe();
        String a = create(null), b = create(null);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (String id : List.of(a, b)) tasks.add(pool.submit(() -> {
                start.await();
                try {
                    bindings.bind(id, new BindAtRequest(null, observation.sha256(), 8));
                    return true;
                } catch (ResponseStatusException | org.springframework.dao.DataIntegrityViolationException e) {
                    return false;
                }
            }));
            start.countDown();
            int successes = 0;
            for (var task : tasks) if (task.get(5, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        for (String id : List.of(a, b)) {
            var bound = bindings.get(id);
            if (bound != null) bindings.unbind(id, bound.sha256());
        }
    }

    private String create(String externalId) {
        return sources.create(new CreateSourceRequest("绑定夹具", externalId, "fixture.py", null,
                "raise RuntimeError('must not execute')\nclass Spider: pass\n")).id();
    }
    private ProbeSummary await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var run = store.get(id);
            if (!Set.of("RUNNING", "QUEUED", "CANCELLING").contains(run.status())) return run;
            Thread.sleep(20);
        }
        throw new AssertionError("Probe did not finish");
    }
}
