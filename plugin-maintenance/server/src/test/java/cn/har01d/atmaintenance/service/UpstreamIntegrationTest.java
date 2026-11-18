package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:upstream-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=upstream-test-only",
        "maintenance.at.origin=", "maintenance.scheduling.enabled=false", "maintenance.model.enabled=false",
        "maintenance.media.enabled=false", "maintenance.container.enabled=false"
})
class UpstreamIntegrationTest {
    private static final String BASE = "\uFEFF# 中文基线\r\nraise RuntimeError('MUST_NOT_EXECUTE_UPSTREAM')\r\n";
    private static final String LOCAL = BASE + "# local fix\r\n";
    private static final String REMOTE = BASE + "# upstream change\r\n";
    @Autowired SourceService sources;
    @Autowired UpstreamService service;
    @Autowired ProbeService probes;
    @Autowired ProbeStore probeStore;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @Test
    void provenanceRequiresAnExplicitExistingBaselineAndNeverInfersFromOrigin() {
        var source = source();
        assertNull(service.get(source.id()));
        assertTrue(service.history(source.id()).isEmpty());
        assertError("ARTIFACT_NOT_FOUND", () -> service.initialize(source.id(),
                new InitializeUpstreamRequest(key(), source.artifactSha256(), "f".repeat(64))));
        assertNull(service.get(source.id()));
        var local = sources.addArtifact(source.id(), new AddArtifactRequest(source.artifactSha256(), LOCAL));
        var event = initialize(source.id(), local.artifactSha256(), source.artifactSha256());
        assertNull(event.before());
        assertEquals("LOCAL_AHEAD", event.after().status());
        assertEquals(source.artifactSha256(), event.after().baseSha256());
        assertEquals(local.artifactSha256(), event.after().effectiveSha256());
        assertFalse(event.after().runtimeApplied());
        assertFalse(event.after().publishEligible());
        assertEquals("operator_supplied_source", event.after().authority());
    }

    @Test
    void observedUpstreamIsRetainedWithoutChangingEffectiveSourceOrCreatingRuntimeEvidence() {
        var source = source();
        initialize(source.id(), source.artifactSha256(), source.artifactSha256());
        var event = observe(source.id(), REMOTE);
        assertEquals("UPSTREAM_AHEAD", event.after().status());
        assertEquals(source.artifactSha256(), sources.get(source.id()).artifactSha256());
        assertEquals(REMOTE, sources.artifact(source.id(), event.after().headSha256()).getBody());
        assertEquals(2, sources.versions(source.id()).size());
        assertTrue(probeStore.list(source.id()).isEmpty());
        assertEquals(event, service.history(source.id()).getFirst());
    }

    @Test
    void explicitFastForwardPreservesBytesAndMakesHistoricalProbeStaleWithoutRewritingIt() throws Exception {
        var source = source();
        var baseline = await(probes.start(source.id()).id());
        var stored = jdbc.queryForObject("select result_json from maintenance_probe_run where id = ?",
                String.class, baseline.id());
        initialize(source.id(), source.artifactSha256(), source.artifactSha256());
        observe(source.id(), REMOTE);
        var event = resolve(source.id(), "FAST_FORWARD");
        assertEquals("IN_SYNC", event.after().status());
        assertEquals(Hashes.sha256(REMOTE), event.after().baseSha256());
        assertEquals(Hashes.sha256(REMOTE), sources.get(source.id()).artifactSha256());
        assertEquals(REMOTE, sources.artifact(source.id(), event.after().effectiveSha256()).getBody());
        assertTrue(probeStore.get(baseline.id()).staleInputs().contains("source"));
        assertEquals(stored, jdbc.queryForObject("select result_json from maintenance_probe_run where id = ?",
                String.class, baseline.id()));
        assertFalse(event.after().runtimeApplied());
    }

    @Test
    void divergentBranchesCannotFastForwardAndFailureRollsBackAllState() {
        var source = source();
        initialize(source.id(), source.artifactSha256(), source.artifactSha256());
        sources.addArtifact(source.id(), new AddArtifactRequest(source.artifactSha256(), LOCAL));
        observe(source.id(), REMOTE);
        var before = service.get(source.id());
        var events = service.history(source.id());
        assertEquals("DIVERGED", before.status());
        assertError("UPSTREAM_LOCAL_CHANGES", () -> resolve(source.id(), "FAST_FORWARD"));
        assertEquals(before, service.get(source.id()));
        assertEquals(events, service.history(source.id()));
        assertEquals(Hashes.sha256(LOCAL), sources.get(source.id()).artifactSha256());
    }

    @Test
    void keepLocalExplicitlyAcknowledgesNewBaselineAndRetainsThePreviousThreeWayReceipt() {
        var source = source();
        initialize(source.id(), source.artifactSha256(), source.artifactSha256());
        sources.addArtifact(source.id(), new AddArtifactRequest(source.artifactSha256(), LOCAL));
        observe(source.id(), REMOTE);
        var request = decision(source.id(), "KEEP_LOCAL");
        var event = service.resolve(source.id(), request);
        assertEquals("DIVERGED", event.before().status());
        assertEquals("LOCAL_AHEAD", event.after().status());
        assertEquals(Hashes.sha256(REMOTE), event.after().baseSha256());
        assertEquals(Hashes.sha256(LOCAL), sources.get(source.id()).artifactSha256());
        assertEquals(BASE, sources.artifact(source.id(), event.before().baseSha256()).getBody());
        assertEquals(event, service.resolve(source.id(), request));
        assertEquals("DIVERGED", observe(source.id(), REMOTE + "# next upstream\n").after().status());
        assertEquals(event, service.history(source.id()).stream().filter(e -> e.id().equals(event.id())).findFirst().orElseThrow());
    }

    @Test
    void keepLocalRequiresDivergenceAndOtherStatesRemainUnchanged() {
        for (String status : List.of("IN_SYNC", "LOCAL_AHEAD", "UPSTREAM_AHEAD")) {
            var source = source();
            initialize(source.id(), source.artifactSha256(), source.artifactSha256());
            if (status.equals("LOCAL_AHEAD"))
                sources.addArtifact(source.id(), new AddArtifactRequest(source.artifactSha256(), LOCAL));
            if (status.equals("UPSTREAM_AHEAD")) observe(source.id(), REMOTE);
            var before = service.get(source.id());
            var events = service.history(source.id());
            var versions = sources.versions(source.id());
            assertEquals(status, before.status());
            assertError("UPSTREAM_NOT_DIVERGED", () -> resolve(source.id(), "KEEP_LOCAL"));
            assertEquals(before, service.get(source.id()));
            assertEquals(events, service.history(source.id()));
            assertEquals(versions, sources.versions(source.id()));
            assertEquals(status.equals("LOCAL_AHEAD") ? LOCAL : BASE,
                    sources.artifact(source.id(), before.effectiveSha256()).getBody());
        }
    }

    @Test
    void identicalEffectiveAndUpstreamBytesConvergeWithoutInventingNewArtifactOrValidation() {
        var source = source();
        initialize(source.id(), source.artifactSha256(), source.artifactSha256());
        sources.addArtifact(source.id(), new AddArtifactRequest(source.artifactSha256(), LOCAL));
        var event = observe(source.id(), LOCAL);
        assertEquals("IN_SYNC", event.after().status());
        assertEquals(Hashes.sha256(LOCAL), event.after().baseSha256());
        assertEquals(2, sources.versions(source.id()).size());
        assertTrue(probeStore.list(source.id()).isEmpty());
    }

    @Test
    void sameObservationAdvancesRevisionWithoutDuplicatingSourceBytes() {
        var source = source();
        initialize(source.id(), source.artifactSha256(), source.artifactSha256());
        var before = service.get(source.id());
        var event = observe(source.id(), BASE);
        assertEquals(before.revision() + 1, event.after().revision());
        assertEquals(1, sources.versions(source.id()).size());
        assertEquals(2, service.history(source.id()).size());
    }

    @Test
    void exactReplaysReturnHistoricalReceiptsAfterLaterEditsWithoutReapplyingAnyPointer() {
        var source = source();
        var init = new InitializeUpstreamRequest(key(), source.artifactSha256(), source.artifactSha256());
        var initialized = service.initialize(source.id(), init);
        var input = input(source.id(), REMOTE);
        var observed = service.observe(source.id(), input);
        var resolution = decision(source.id(), "FAST_FORWARD");
        var adopted = service.resolve(source.id(), resolution);
        sources.addArtifact(source.id(), new AddArtifactRequest(adopted.after().effectiveSha256(), LOCAL));
        var current = service.get(source.id());
        long count = eventCount(source.id());
        assertEquals(initialized, service.initialize(source.id(), init));
        assertEquals(observed, service.observe(source.id(), input));
        assertEquals(adopted, service.resolve(source.id(), resolution));
        assertEquals(current, service.get(source.id()));
        assertEquals(count, eventCount(source.id()));
    }

    @Test
    void keysCannotBeReusedForAnotherPayloadOrOperationButAreScopedToTheSource() {
        var source = source();
        String shared = key();
        var request = new InitializeUpstreamRequest(shared, source.artifactSha256(), source.artifactSha256());
        service.initialize(source.id(), request);
        assertError("UPSTREAM_REQUEST_KEY_REUSED", () -> service.observe(source.id(),
                new ObserveUpstreamRequest(shared, 0L, source.artifactSha256(), REMOTE)));
        var observation = input(source.id(), REMOTE);
        service.observe(source.id(), observation);
        assertError("UPSTREAM_REQUEST_KEY_REUSED", () -> service.observe(source.id(), new ObserveUpstreamRequest(
                observation.requestKey(), observation.expectedRevision(), observation.expectedArtifactSha256(), LOCAL)));
        var other = source();
        assertNotEquals(source.id(), service.initialize(other.id(), request).sourceId());
    }

    @Test
    void staleSourceOrTrackingRevisionRejectsBeforeRetainingAnArtifact() {
        var source = source();
        initialize(source.id(), source.artifactSha256(), source.artifactSha256());
        var old = input(source.id(), LOCAL);
        observe(source.id(), REMOTE);
        long count = eventCount(source.id());
        assertError("UPSTREAM_CHANGED", () -> service.observe(source.id(), old));
        var current = input(source.id(), LOCAL);
        sources.addArtifact(source.id(), new AddArtifactRequest(source.artifactSha256(), BASE + "# separate edit\n"));
        assertError("SOURCE_CHANGED", () -> service.observe(source.id(), current));
        assertEquals(count, eventCount(source.id()));
        assertEquals(3, sources.versions(source.id()).size());
        assertError("ARTIFACT_NOT_FOUND", () -> sources.artifact(source.id(), Hashes.sha256(LOCAL)));
    }

    @Test
    void concurrentIdenticalRequestsCommitOneObservationAndReturnOneImmutableReceipt() throws Exception {
        var source = source();
        initialize(source.id(), source.artifactSha256(), source.artifactSha256());
        var input = input(source.id(), REMOTE);
        var results = compete(() -> service.observe(source.id(), input), () -> service.observe(source.id(), input));
        assertEquals(results.get(0), results.get(1));
        assertEquals(2, eventCount(source.id()));
        assertEquals(1, service.get(source.id()).revision());
        assertEquals(2, sources.versions(source.id()).size());
    }

    @Test
    void competingDifferentObservationsCannotBothConsumeTheSameRevision() throws Exception {
        var source = source();
        initialize(source.id(), source.artifactSha256(), source.artifactSha256());
        var first = input(source.id(), REMOTE);
        var second = input(source.id(), LOCAL);
        var results = compete(() -> attempt(() -> service.observe(source.id(), first)),
                () -> attempt(() -> service.observe(source.id(), second)));
        assertEquals(1, results.stream().filter("UPSTREAM_CHANGED"::equals).count());
        assertEquals(1, results.stream().filter("accepted"::equals).count());
        assertEquals(2, eventCount(source.id()));
        assertEquals(2, sources.versions(source.id()).size());
    }

    @Test
    void manualSourceEditAndFastForwardUseTheSameLockAndCannotOverwriteEachOther() throws Exception {
        var source = source();
        initialize(source.id(), source.artifactSha256(), source.artifactSha256());
        observe(source.id(), REMOTE);
        var decision = decision(source.id(), "FAST_FORWARD");
        var results = compete(() -> attempt(() -> service.resolve(source.id(), decision)),
                () -> attempt(() -> sources.addArtifact(source.id(),
                        new AddArtifactRequest(source.artifactSha256(), LOCAL))));
        assertEquals(1, results.stream().filter("SOURCE_CHANGED"::equals).count());
        assertEquals(1, results.stream().filter("accepted"::equals).count());
        assertTrue(Set.of(Hashes.sha256(LOCAL), Hashes.sha256(REMOTE))
                .contains(sources.get(source.id()).artifactSha256()));
    }

    @Test
    void comparisonZipBindsAllThreeRolesToExactUtf8BytesAndChecksums() throws Exception {
        var source = source();
        initialize(source.id(), source.artifactSha256(), source.artifactSha256());
        sources.addArtifact(source.id(), new AddArtifactRequest(source.artifactSha256(), LOCAL));
        observe(source.id(), REMOTE);
        Map<String, byte[]> files = unzip(service.export(source.id()));
        assertEquals(Set.of("base.py", "upstream.py", "effective.py", "coordination.json", "manifest.json"), files.keySet());
        assertArrayEquals(BASE.getBytes(StandardCharsets.UTF_8), files.get("base.py"));
        assertArrayEquals(LOCAL.getBytes(StandardCharsets.UTF_8), files.get("effective.py"));
        assertArrayEquals(REMOTE.getBytes(StandardCharsets.UTF_8), files.get("upstream.py"));
        var manifest = mapper.readTree(files.get("manifest.json"));
        assertEquals("upstream_three_way_comparison", manifest.path("kind").asText());
        assertFalse(manifest.path("publishEligible").asBoolean());
        assertFalse(manifest.path("sourceHealthEligible").asBoolean());
        for (var entry : manifest.path("files").properties()) {
            assertEquals(files.get(entry.getKey()).length, entry.getValue().path("bytes").asInt());
            assertEquals(Hashes.sha256(files.get(entry.getKey())), entry.getValue().path("sha256").asText());
        }
        assertEquals(service.get(source.id()), mapper.readValue(files.get("coordination.json"), UpstreamSummary.class));
    }

    @Test
    void largerUtf8InputsAndUnknownSourceOrUninitializedStateDoNotWrite() {
        var source = source();
        assertError("UPSTREAM_NOT_INITIALIZED", () -> observe(source.id(), REMOTE));
        assertError("UPSTREAM_NOT_INITIALIZED", () -> service.export(source.id()));
        assertError("SOURCE_NOT_FOUND", () -> service.get(key()));
        initialize(source.id(), source.artifactSha256(), source.artifactSha256());
        var state = service.get(source.id());
        assertError("SOURCE_TOO_LARGE", () -> service.observe(source.id(),
                new ObserveUpstreamRequest(key(), state.revision(), state.effectiveSha256(), "中".repeat(349526))));
        assertEquals(state, service.get(source.id()));
        assertEquals(1, eventCount(source.id()));
        assertError("UPSTREAM_ALREADY_INITIALIZED",
                () -> initialize(source.id(), source.artifactSha256(), source.artifactSha256()));
    }

    @Test
    void httpRequiresAdminAndCsrfValidatesRevisionAndNeverEchoesSourceInReceipts() throws Exception {
        var source = source();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                "admin:upstream-test-only".getBytes(StandardCharsets.UTF_8));
        String path = "/sources/" + source.id() + "/upstream";
        assertNotEquals(200, client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(204, get(client, auth, path).statusCode());
        var input = new InitializeUpstreamRequest(key(), source.artifactSha256(), source.artifactSha256());
        assertEquals(403, post(client, auth, null, path + "/initialize", input).statusCode());
        JsonNode csrf = mapper.readTree(get(client, auth, "/csrf").body());
        var init = post(client, auth, csrf, path + "/initialize", input);
        assertEquals(200, init.statusCode(), init.body());
        var malformed = mapper.createObjectNode().put("requestKey", key())
                .put("expectedArtifactSha256", source.artifactSha256()).put("content", REMOTE);
        assertEquals(400, post(client, auth, csrf, path + "/observations", malformed).statusCode());
        var observed = post(client, auth, csrf, path + "/observations", input(source.id(), REMOTE));
        assertEquals(200, observed.statusCode(), observed.body());
        assertFalse(observed.body().contains("MUST_NOT_EXECUTE_UPSTREAM"));
        assertFalse(observed.body().contains("PRIVATE_LOCATOR"));
        var rejected = post(client, auth, csrf, path + "/resolve", decision(source.id(), "KEEP_LOCAL"));
        assertEquals(409, rejected.statusCode(), rejected.body());
        assertEquals("UPSTREAM_NOT_DIVERGED", mapper.readTree(rejected.body()).path("code").asText());
        var invalid = post(client, auth, csrf, path + "/resolve",
                new ResolveUpstreamRequest(key(), 1L, source.artifactSha256(), "DEPLOY"));
        assertEquals(400, invalid.statusCode());
        assertEquals(2, eventCount(source.id()));
    }

    private SourceSummary source() {
        return sources.create(new CreateSourceRequest("上游协调测试", null, "test.py",
                "https://example.invalid/source.py?token=PRIVATE_LOCATOR", BASE));
    }

    @Test
    void atFetchHttpEntryUsesAdminCsrfAndValidatesTheBoundSource() throws Exception {
        var source = source();
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    "admin:upstream-test-only".getBytes(StandardCharsets.UTF_8));
            String path = "/sources/" + source.id() + "/upstream/at-observations";
            var input = new FetchAtUpstreamRequest(key(), null, source.artifactSha256(), "a".repeat(64), true);
            assertEquals(403, post(client, auth, null, path, input).statusCode());
            JsonNode csrf = mapper.readTree(get(client, auth, "/csrf").body());
            assertEquals(400, post(client, auth, csrf, path, Map.of("requestKey", key())).statusCode());
            var rejected = post(client, auth, csrf, path, input);
            assertEquals(409, rejected.statusCode(), rejected.body());
            assertEquals("UPSTREAM_AT_BINDING_REQUIRED", mapper.readTree(rejected.body()).path("code").asText());
            assertNull(service.get(source.id()));
        }
    }

    @Test
    void observationMigrationKeepsAnExistingComparisonAndDefaultsItsProvenanceToNull() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:h2:mem:migration-" + key());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE maintenance_upstream (source_id VARCHAR(36) PRIMARY KEY, "
                    + "base_sha256 VARCHAR(64) NOT NULL, head_sha256 VARCHAR(64) NOT NULL, "
                    + "row_version BIGINT NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
            statement.execute("INSERT INTO maintenance_upstream VALUES ('old-source', 'base', 'head', 4, CURRENT_TIMESTAMP)");
            try (var stream = getClass().getResourceAsStream("/db/migration/h2/V32__UpstreamObservation.sql")) {
                assertNotNull(stream);
                statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var rows = statement.executeQuery("SELECT * FROM maintenance_upstream")) {
                assertTrue(rows.next());
                assertEquals("base", rows.getString("base_sha256"));
                assertEquals("head", rows.getString("head_sha256"));
                assertEquals(4, rows.getLong("row_version"));
                assertNull(rows.getString("head_observation_json"));
                assertFalse(rows.next());
            }
        }
    }

    private UpstreamEventSummary initialize(String id, String effective, String baseline) {
        return service.initialize(id, new InitializeUpstreamRequest(key(), effective, baseline));
    }

    private ObserveUpstreamRequest input(String id, String content) {
        var state = service.get(id);
        return new ObserveUpstreamRequest(key(), state == null ? 0L : state.revision(),
                sources.get(id).artifactSha256(), content);
    }

    private UpstreamEventSummary observe(String id, String content) { return service.observe(id, input(id, content)); }
    private ResolveUpstreamRequest decision(String id, String value) {
        var state = service.get(id);
        return new ResolveUpstreamRequest(key(), state.revision(), state.effectiveSha256(), value);
    }
    private UpstreamEventSummary resolve(String id, String value) { return service.resolve(id, decision(id, value)); }
    private String key() { return UUID.randomUUID().toString(); }
    private long eventCount(String id) {
        return jdbc.queryForObject("select count(*) from maintenance_upstream_event where source_id = ?", Long.class, id);
    }
    private String attempt(Callable<?> work) throws Exception {
        try { work.call(); return "accepted"; }
        catch (ResponseStatusException error) { return error.getReason(); }
    }
    private void assertError(String code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(ResponseStatusException.class, work).getReason());
    }
    private <T> List<T> compete(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<T>> pending = new ArrayList<>();
            for (var action : List.of(first, second)) pending.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return action.call();
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(pending.get(0).get(30, TimeUnit.SECONDS), pending.get(1).get(30, TimeUnit.SECONDS));
        }
    }
    private ProbeSummary await(String id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        ProbeSummary run;
        while (Set.of("QUEUED", "RUNNING").contains((run = probeStore.get(id)).status())) {
            assertTrue(System.nanoTime() < deadline);
            Thread.sleep(20);
        }
        assertEquals("COMPLETED", run.status());
        return run;
    }
    private Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), zip.readAllBytes());
        }
        return files;
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/plugin-maintenance" + path); }
    private HttpResponse<String> get(HttpClient client, String auth, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", auth).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client, String auth, JsonNode csrf, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Authorization", auth).header("Content-Type", "application/json");
        if (csrf != null) request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
        return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
