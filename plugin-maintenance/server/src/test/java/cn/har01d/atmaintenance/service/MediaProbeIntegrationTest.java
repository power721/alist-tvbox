package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.repository.MediaProbeRepository;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:maintenance-media-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only", "maintenance.at.origin=",
        "maintenance.scheduling.enabled=false", "maintenance.model.enabled=false",
        "maintenance.media.enabled=false", "maintenance.media.allowed-origins="
})
class MediaProbeIntegrationTest {
    @Autowired SourceService sources;
    @Autowired MediaProbeService service;
    @Autowired MediaProbeStore store;
    @Autowired MediaProbeExportService exports;
    @Autowired MediaProbeRepository repository;
    @Autowired MediaNetworkPolicy policy;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @Test
    void defaultDisabledTaskPersistsOnlyRedactedEvidenceAndDoesNotChangeSourceHistory() throws Exception {
        var source = source();
        var run = service.start(source.id(), input(source));
        run = await(run.id());
        assertEquals("COMPLETED", run.status());
        assertFalse(run.sourceHealthEligible());
        assertEquals("operator_supplied_player", run.authority());
        assertEquals("sample_blocked", run.result().path("verdict").asText());
        assertEquals(0, run.result().path("usage").path("requests").asInt());
        assertFalse(run.budget().path("reserved").asBoolean());
        assertEquals(0, run.issuedRequests());
        assertEquals(0, jdbc.queryForObject("select used_probes from maintenance_media_budget where id='global'", Integer.class));
        assertEquals(source.artifactSha256(), sources.get(source.id()).artifactSha256());
        assertEquals(1, sources.versions(source.id()).size());
        assertEquals(0, jdbc.queryForObject("select count(*) from maintenance_probe_run where source_id=?",
                Integer.class, source.id()));
        String stored = jdbc.queryForObject("select result_json from maintenance_media_probe where id=?",
                String.class, run.id());
        assertFalse(stored.contains("media-secret"));
        assertFalse(stored.contains("example.invalid"));
        assertEquals("current", run.freshness());
    }

    @Test
    void exportUsesExactStoredSnapshotWithManifestAndNoTransportInput() throws Exception {
        var source = source();
        var run = await(service.start(source.id(), input(source)).id());
        Map<String, byte[]> files = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(exports.export(run.id())))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                assertNull(files.put(entry.getName(), zip.readAllBytes()));
        }
        assertEquals(Set.of("media-probe.json", "manifest.json"), files.keySet());
        var manifest = mapper.readTree(files.get("manifest.json"));
        assertFalse(manifest.path("sourceHealthEligible").asBoolean());
        assertFalse(manifest.path("publishEligible").asBoolean());
        assertEquals(Hashes.sha256(files.get("media-probe.json")),
                manifest.path("files").path("media-probe.json").path("sha256").asText());
        assertEquals(mapper.valueToTree(run), mapper.readTree(files.get("media-probe.json")));
        for (byte[] bytes : files.values()) assertFalse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("media-secret"));
    }

    @Test
    void rejectsSourceDriftBeforeCreatingAnExecutableTaskAndDetectsLaterChanges() throws Exception {
        var source = source();
        var run = await(service.start(source.id(), input(source)).id());
        sources.addArtifact(source.id(), new AddArtifactRequest(source.artifactSha256(), "class Spider:\n    changed = True\n"));
        assertTrue(store.get(run.id()).staleInputs().contains("source"));
        assertEquals("SOURCE_CHANGED", assertThrows(ResponseStatusException.class,
                () -> service.start(source.id(), input(source))).getReason());
        assertEquals(1, store.list(source.id()).size());
    }

    @Test
    void queuedCancellationAndRunningCancellationCannotBeOverwrittenByLateCompletion() throws Exception {
        var source = source();
        var queued = store.create(source.id(), source.artifactSha256());
        assertEquals("CANCELLED", service.cancel(queued.getId()).status());
        assertFalse(store.start(queued.getId()));
        var active = store.create(source.id(), source.artifactSha256());
        assertTrue(store.start(active.getId()));
        assertEquals("CANCELLING", service.cancel(active.getId()).status());
        store.finish(active.getId(), "COMPLETED", mapper.createObjectNode().put("late", true), null);
        var cancelled = store.get(active.getId());
        assertEquals("CANCELLED", cancelled.status());
        assertNull(cancelled.result());
        assertEquals(cancelled, service.cancel(active.getId()));
    }

    @Test
    void restartRecoveryDoesNotRetainOrReplayInputs() {
        var source = source();
        var run = store.create(source.id(), source.artifactSha256());
        assertTrue(store.start(run.getId()));
        store.recover();
        assertEquals("INTERRUPTED", store.get(run.getId()).status());
        assertEquals("SERVICE_RESTARTED_INPUT_NOT_RETAINED", store.get(run.getId()).errorCode());
        assertNull(store.get(run.getId()).result());
    }

    @Test
    void routesRequireAuthenticationAndCsrfAndNeverEchoSensitiveInvalidInputs() throws Exception {
        var source = source();
        URI url = URI.create("http://127.0.0.1:" + port + "/api/plugin-maintenance/sources/" + source.id() + "/media-probes");
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            String body = mapper.writeValueAsString(input(source));
            assertEquals(403, client.send(HttpRequest.newBuilder(url).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            String auth = "Basic " + Base64.getEncoder().encodeToString("admin:integration-test-only".getBytes());
            for (String route : List.of("/media-budget", "/sources/" + source.id() + "/media-budget")) {
                URI budgetUri = URI.create("http://127.0.0.1:" + port + "/api/plugin-maintenance" + route);
                assertEquals(401, client.send(HttpRequest.newBuilder(budgetUri).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                var budgetResponse = client.send(HttpRequest.newBuilder(budgetUri).header("Authorization", auth).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, budgetResponse.statusCode());
                assertFalse(budgetResponse.body().contains("media-secret"));
            }
            assertEquals(403, client.send(HttpRequest.newBuilder(url).header("Authorization", auth)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            URI csrfUri = URI.create("http://127.0.0.1:" + port + "/api/plugin-maintenance/csrf");
            var csrf = mapper.readTree(client.send(HttpRequest.newBuilder(csrfUri).header("Authorization", auth).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            var response = client.send(HttpRequest.newBuilder(url).header("Authorization", auth)
                    .header("Content-Type", "application/json").header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, response.statusCode());
            String invalid = body.replace("https://example.invalid/video?signature=media-secret", "file:///media-secret");
            var rejected = client.send(HttpRequest.newBuilder(url).header("Authorization", auth)
                    .header("Content-Type", "application/json").header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(invalid)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, rejected.statusCode());
            assertFalse(rejected.body().contains("media-secret"));
        }
    }

    private SourceSummary source() {
        return sources.create(new CreateSourceRequest("media", null, "source.py", null, "class Spider:\n    pass\n"));
    }

    private StartMediaProbeRequest input(SourceSummary source) throws Exception {
        return new StartMediaProbeRequest(source.artifactSha256(), mapper.readTree(
                "{\"parse\":0,\"jx\":0,\"url\":\"https://example.invalid/video?signature=media-secret\","
                        + "\"header\":{\"Cookie\":\"media-secret\"}}"));
    }

    private MediaProbeSummary await(String id) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        MediaProbeSummary run;
        do {
            run = store.get(id);
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(run.status())) return run;
            Thread.sleep(20);
        } while (Instant.now().isBefore(deadline));
        fail("Media task not finished: " + run.id() + " " + run.status() + " " + run.errorCode());
        return run;
    }
}
