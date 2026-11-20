package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.AddArtifactRequest;
import cn.har01d.atmaintenance.dto.CreateSourceRequest;
import cn.har01d.atmaintenance.dto.ProbeSummary;
import cn.har01d.atmaintenance.dto.SourceSummary;
import cn.har01d.atmaintenance.dto.ProfileSummary;
import cn.har01d.atmaintenance.dto.UpdateProfileRequest;
import cn.har01d.atmaintenance.repository.SourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "maintenance.scheduling.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:maintenance-tests;DB_CLOSE_DELAY=-1",
        "maintenance.at.origin=",
        "spring.security.user.password=integration-test-only"
})
class MaintenanceIntegrationTest {
    @Autowired SourceService sources;
    @Autowired ProbeService probes;
    @Autowired ProbeStore store;
    @Autowired KnowledgeService knowledge;
    @Autowired ExportService exports;
    @Autowired ObjectMapper mapper;
    @Autowired StaticInspector inspector;
    @Autowired ProfileService profiles;
    @Autowired SourceRepository sourceRepository;
    @LocalServerPort int port;

    private static final String GOOD = """
            # 中文名称与 UTF-8 源码必须保持原字节
            raise RuntimeError("This submitted source must never execute")
            class Spider:
                def init(self, extend=""):
                    return ""
                def homeContent(self, filter):
                    return {"class": []}
            """;

    @Test
    void immutableVersionsRejectStaleEditsAndPreserveChineseBytes() {
        SourceSummary source = create(GOOD);
        assertEquals(Hashes.sha256(GOOD.getBytes(StandardCharsets.UTF_8)), source.artifactSha256());
        String next = GOOD + "\n# second version\n";
        var updated = sources.addArtifact(source.id(), new AddArtifactRequest(source.artifactSha256(), next));
        assertNotEquals(source.artifactSha256(), updated.artifactSha256());
        assertEquals(GOOD, sources.artifact(source.id(), source.artifactSha256()).getBody());
        assertEquals(2, sources.versions(source.id()).size());
        var conflict = assertThrows(ResponseStatusException.class, () -> sources.addArtifact(
                source.id(), new AddArtifactRequest(source.artifactSha256(), next + "# stale")));
        assertEquals(409, conflict.getStatusCode().value());
        sources.addArtifact(source.id(), new AddArtifactRequest(updated.artifactSha256(), next));
        assertEquals(2, sources.versions(source.id()).size());
    }

    @Test
    void sourceByteBudgetIsCheckedBeforePersistence() {
        var error = assertThrows(ResponseStatusException.class, () -> create("中".repeat(400_000)));
        assertEquals(413, error.getStatusCode().value());
    }

    @Test
    void snapshotProbeDoesNotExecuteSourceOrCertifyRuntimeNodes() throws Exception {
        var source = create(GOOD);
        var submitted = probes.start(source.id());
        sources.addArtifact(source.id(), new AddArtifactRequest(source.artifactSha256(), GOOD + "# changed"));
        var done = await(submitted.id());
        assertEquals("COMPLETED", done.status());
        assertEquals(source.artifactSha256(), done.artifactSha256());
        assertEquals("static_checked", done.result().path("verdict").asText());
        assertEquals("pass", node(done, "S03").path("status").asText());
        assertEquals(44, done.result().path("nodes").size());
        assertEquals("not_tested", node(done, "S04").path("status").asText());
        assertEquals("not_tested", node(done, "P02").path("status").asText());
        assertEquals(done.artifactSha256(), done.context().path("artifactSha256").asText());
        assertEquals(knowledge.sha256(), done.knowledgeSha256());
        assertEquals(inspector.sha256(), done.inspectorSha256());
    }

    @Test
    void isolatedPythonProcessPreservesUtf8BomChineseAndCrLf() throws Exception {
        String body = "\uFEFF# 中文源码\r\nclass Spider: pass\r\n";
        JsonNode result = inspector.inspect(body);
        assertTrue(result.path("parseOk").asBoolean());
        assertEquals(Hashes.sha256(body.getBytes(StandardCharsets.UTF_8)), result.path("sha256").asText());
    }

    @Test
    void duplicateMethodsFailAndRetrieveRelevantRepairGuidance() throws Exception {
        var source = create("class Spider:\n def init(self): pass\n def init(self): pass\n");
        var run = await(probes.start(source.id()).id());
        assertEquals("fail", node(run, "S03").path("status").asText());
        assertEquals("duplicate_method", node(run, "S03").path("evidence").path("issues").get(0).path("code").asText());
        assertEquals("duplicate-methods", run.context().path("rules").get(0).path("id").asText());
        assertEquals("reference_only", run.context().path("rules").get(0).path("authority").asText());
    }

    @Test
    void syntaxFailureDoesNotExposeTheSourceLine() throws Exception {
        var source = create("class Spider:\n    secret = 'PRIVATE_SOURCE_MARKER'\n    def init(\n");
        var run = await(probes.start(source.id()).id());
        assertEquals("fail", node(run, "S03").path("status").asText());
        assertEquals("invalid_syntax", node(run, "S03").path("evidence").path("issues").get(0).path("code").asText());
        assertFalse(run.result().toString().contains("PRIVATE_SOURCE_MARKER"));
    }

    @Test
    void offlineArchiveContainsExactSourcePinnedKnowledgeAndVerifiableManifest() throws Exception {
        var source = create(GOOD);
        assertEquals("COMPLETED", await(probes.start(source.id()).id()).status());
        Map<String, byte[]> files = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(exports.export(source.id(), null)), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertFalse(entry.getName().contains(".."));
                files.put(entry.getName(), zip.readAllBytes());
            }
        }
        assertArrayEquals(GOOD.getBytes(StandardCharsets.UTF_8), files.get("source.py"));
        JsonNode manifest = mapper.readTree(files.get("manifest.json"));
        assertEquals(source.artifactSha256(), manifest.path("artifactSha256").asText());
        assertEquals(knowledge.sha256(), Hashes.sha256(files.get("knowledge/catalog.json")));
        assertEquals(knowledge.nodeCatalogSha256(), Hashes.sha256(files.get("capability-nodes.json")));
        assertEquals(knowledge.sha256(), manifest.path("probeKnowledgeSha256").asText());
        var fields = manifest.path("files").fields();
        while (fields.hasNext()) {
            var field = fields.next();
            assertEquals(field.getValue().path("sha256").asText(), Hashes.sha256(files.get(field.getKey())));
            assertEquals(field.getValue().path("bytes").asInt(), files.get(field.getKey()).length);
        }
        JsonNode probe = mapper.readTree(files.get("probe.json"));
        assertEquals(source.artifactSha256(), probe.path("artifactSha256").asText());
        assertEquals(Hashes.sha256(files.get("capability-nodes.json")),
                probe.path("result").path("nodeCatalogSha256").asText());
    }

    @Test
    void unfinishedRunsAreMarkedInterruptedOnRecovery() {
        var source = create(GOOD);
        var run = store.create(source.id(), inspector.sha256(), knowledge.sha256());
        store.start(run.getId());
        store.interruptUnfinished();
        assertEquals("INTERRUPTED", store.get(run.getId()).status());
        assertEquals("SERVICE_RESTARTED", store.get(run.getId()).errorCode());
        assertNotNull(store.get(run.getId()).finishedAt());
    }

    @Test
    void skillRetrievalHasBoundedContextAndExactCitations() {
        JsonNode context = knowledge.context("M06", "range", "t4", 8);
        assertEquals("mp4-ranges", context.path("rules").get(0).path("id").asText());
        assertEquals("data_only", context.path("authority").asText());
        assertTrue(context.path("ruleBytes").asInt() <= 16_384);
        for (JsonNode rule : context.path("rules")) {
            assertTrue(rule.path("citation").path("sha256").asText().matches("[a-f0-9]{64}"));
            assertFalse(rule.path("citation").path("excerpt").asText().isBlank());
        }
        assertEquals(0, knowledge.context("UNKNOWN", "", "t4", 8).path("rules").size());
        assertThrows(IllegalArgumentException.class, () -> knowledge.context(null, "", "unknown", 8));
    }

    @Test
    void profileCanonicalizesExtWithoutPersistingOrExportingItsValuesOrSalt() throws Exception {
        var source = create(GOOD);
        var baseline = profiles.get(source.id());
        JsonNode ext = mapper.readTree("{\"token\":\"PRIVATE_EXT_MARKER\",\"nested\":{\"b\":2,\"a\":1}}");
        var saved = profiles.update(source.id(), profileInput(baseline,
                List.of("S03", "S01", "S02"), List.of("C01"), ext));
        var reordered = mapper.readTree("{\"nested\":{\"a\":1,\"b\":2},\"token\":\"PRIVATE_EXT_MARKER\"}");
        var same = profiles.update(source.id(), profileInput(saved,
                List.of("S02", "S03", "S01"), List.of("C01"), reordered));
        assertEquals(saved.sha256(), same.sha256());
        assertEquals(1, profiles.history(source.id()).size());
        assertFalse(saved.snapshot().toString().contains("PRIVATE_EXT_MARKER"));
        var second = create(GOOD);
        var other = profiles.update(second.id(), profileInput(profiles.get(second.id()),
                List.of("S01", "S02", "S03"), List.of("C01"), ext));
        assertNotEquals(saved.snapshot().path("extFingerprint"), other.snapshot().path("extFingerprint"));
        var salt = sourceRepository.findById(source.id()).orElseThrow().getConfigFingerprintSalt();
        assertNotNull(salt);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(exports.export(source.id(), null)))) {
            while (zip.getNextEntry() != null) {
                String body = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(body.contains("PRIVATE_EXT_MARKER"));
                assertFalse(body.contains(salt));
            }
        }
    }

    @Test
    void profileUpdatesRejectStaleUnknownDuplicateOverlappingAndMissingFixedNodes() {
        var source = create(GOOD);
        var baseline = profiles.get(source.id());
        var invalid = List.of(
                profileInput(baseline, List.of("S01", "S02"), List.of(), null),
                profileInput(baseline, List.of("S01", "S02", "S03", "UNKNOWN"), List.of(), null),
                profileInput(baseline, List.of("S01", "S02", "S03", "S03"), List.of(), null),
                profileInput(baseline, List.of("S01", "S02", "S03"), List.of("S01"), null));
        for (var input : invalid) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> profiles.update(source.id(), input)).getStatusCode().value());
        }
        profiles.update(source.id(), profileInput(baseline, List.of("S01", "S02", "S03", "C01"), List.of(), null));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> profiles.update(source.id(),
                profileInput(baseline, List.of("S01", "S02", "S03"), List.of(), null))).getStatusCode().value());
    }

    @Test
    void probeFreezesProfileAndCountsRequiredUntestedNodesWithoutCertifyingThem() throws Exception {
        var source = create(GOOD);
        var baseline = profiles.get(source.id());
        var first = probes.start(source.id());
        var changed = profiles.update(source.id(), new UpdateProfileRequest(baseline.sha256(), "fongmi",
                "fixture-runtime", List.of("S01", "S02", "S03", "C01"), List.of(), null, null));
        var old = await(first.id());
        assertEquals(baseline.sha256(), old.profileSha256());
        assertEquals("profile_changed", old.freshness());
        assertEquals(3, old.result().path("requiredCoverage").path("total").asInt());
        var current = await(probes.start(source.id()).id());
        assertEquals("current", current.freshness());
        assertEquals(changed.sha256(), current.profileSha256());
        assertEquals("fongmi", current.context().path("runtime").asText());
        assertEquals(changed.sha256(), current.context().path("profileSha256").asText());
        assertEquals("required", node(current, "C01").path("requirement").asText());
        assertEquals("not_tested", node(current, "C01").path("status").asText());
        assertEquals(1, current.result().path("requiredCoverage").path("notTested").asInt());
        assertEquals("required_incomplete", current.result().path("requiredCoverage").path("conclusion").asText());
        sources.addArtifact(source.id(), new AddArtifactRequest(source.artifactSha256(), GOOD + "# revised"));
        assertEquals("source_changed", store.get(current.id()).freshness());
        assertEquals("source_and_profile_changed", store.get(old.id()).freshness());
    }

    @Test
    void changedExtInvalidatesEvidenceAndOmittedExtPreservesItsFingerprint() throws Exception {
        var source = create(GOOD);
        var baseline = profiles.get(source.id());
        var first = profiles.update(source.id(), profileInput(baseline, List.of("S01", "S02", "S03"),
                List.of(), mapper.readTree("{\"secret\":\"first\"}")));
        var run = await(probes.start(source.id()).id());
        var same = profiles.update(source.id(), profileInput(first, List.of("S01", "S02", "S03"), List.of(), null));
        assertEquals(first.sha256(), same.sha256());
        assertEquals("current", store.get(run.id()).freshness());
        var second = profiles.update(source.id(), profileInput(first, List.of("S01", "S02", "S03"),
                List.of(), mapper.readTree("{\"secret\":\"second\"}")));
        assertNotEquals(first.sha256(), second.sha256());
        assertEquals("profile_changed", store.get(run.id()).freshness());
        assertEquals(first.snapshot(), store.get(run.id()).profile());
        try (var zip = new ZipInputStream(new ByteArrayInputStream(exports.export(source.id(), null)))) {
            Map<String, byte[]> files = new HashMap<>();
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), zip.readAllBytes());
            assertEquals(second.sha256(), Hashes.sha256(files.get("profile.json")));
            assertEquals(first.sha256(), Hashes.sha256(files.get("probe-profile.json")));
        }
    }

    @Test
    void extMustBeAnObjectAndRespectTheByteBudget() throws Exception {
        var source = create(GOOD);
        var baseline = profiles.get(source.id());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> profiles.update(source.id(),
                profileInput(baseline, List.of("S01", "S02", "S03"), List.of(), mapper.createArrayNode()))).getStatusCode().value());
        var tooLarge = mapper.createObjectNode().put("value", "中".repeat(30_000));
        assertEquals(413, assertThrows(ResponseStatusException.class, () -> profiles.update(source.id(),
                profileInput(baseline, List.of("S01", "S02", "S03"), List.of(), tooLarge))).getStatusCode().value());
        assertEquals(baseline.sha256(), profiles.get(source.id()).sha256());
    }

    @Test
    void publicSourceDataIsCanonicalFrozenPreservedAndExplicitlyClearable() throws Exception {
        var source = create(GOOD);
        var baseline = profiles.get(source.id());
        var data = mapper.readTree("{\"page_size\":\"20\",\"label\":\"公开测试\"}");
        var saved = profiles.update(source.id(), new UpdateProfileRequest(baseline.sha256(), "t4",
                "public-config", List.of("S01", "S02", "S03"), List.of(), null, data));
        var run = await(probes.start(source.id()).id());
        var same = profiles.update(source.id(), new UpdateProfileRequest(saved.sha256(), "t4",
                "public-config", List.of("S01", "S02", "S03"), List.of(), null,
                mapper.readTree("{\"label\":\"公开测试\",\"page_size\":\"20\"}")));
        assertEquals(saved.sha256(), same.sha256());
        assertEquals(data, profiles.get(source.id()).snapshot().path("publicSourceData"));
        var retained = profiles.update(source.id(), profileInput(saved, List.of("S01", "S02", "S03"),
                List.of(), null));
        assertEquals(saved.sha256(), retained.sha256());
        var cleared = profiles.update(source.id(), new UpdateProfileRequest(saved.sha256(), "t4",
                "public-config", List.of("S01", "S02", "S03"), List.of(), null, mapper.createObjectNode()));
        assertFalse(cleared.snapshot().has("publicSourceData"));
        assertEquals("profile_changed", store.get(run.id()).freshness());
        assertEquals(data, store.get(run.id()).profile().path("publicSourceData"));
    }

    @Test
    void httpPlaybackGroupIsFrozenPreservedAndBounded() throws Exception {
        var source = create(GOOD);
        var initial = profiles.get(source.id());
        var saved = profiles.update(source.id(), new UpdateProfileRequest(initial.sha256(), "t4",
                "playback-groups", List.of("S01", "S02", "S03"), List.of(), null, null, null, 1, "sample-id"));
        assertEquals(1, saved.snapshot().path("httpPlaybackGroup").asInt());
        assertEquals("sample-id", saved.snapshot().path("httpDetailSample").asText());
        var retained = profiles.update(source.id(), profileInput(saved, List.of("S01", "S02", "S03"),
                List.of(), null));
        assertEquals(saved.sha256(), retained.sha256());
        for (int invalid : List.of(-1, 32)) {
            var error = assertThrows(ResponseStatusException.class, () -> profiles.update(source.id(),
                    new UpdateProfileRequest(saved.sha256(), "t4", "playback-groups",
                            List.of("S01", "S02", "S03"), List.of(), null, null, null, invalid)));
            assertEquals(400, error.getStatusCode().value());
            assertEquals(saved.sha256(), profiles.get(source.id()).sha256());
        }
        var reset = profiles.update(source.id(), new UpdateProfileRequest(saved.sha256(), "t4",
                "playback-groups", List.of("S01", "S02", "S03"), List.of(), null, null, null, 0, ""));
        assertEquals(0, reset.snapshot().path("httpPlaybackGroup").asInt());
        assertFalse(reset.snapshot().has("httpDetailSample"));
        assertNotEquals(saved.sha256(), reset.sha256());
        for (String invalid : List.of(" ", "bad\nid", "x".repeat(513))) {
            assertThrows(ResponseStatusException.class, () -> profiles.update(source.id(),
                    new UpdateProfileRequest(reset.sha256(), "t4", "playback-groups",
                            List.of("S01", "S02", "S03"), List.of(), null, null, null, 0, invalid)));
            assertEquals(reset.sha256(), profiles.get(source.id()).sha256());
        }
    }

    @Test
    void paginationModeIsFrozenPreservedAndExplicitlyResettable() throws Exception {
        var source = create(GOOD);
        var initial = profiles.get(source.id());
        var saved = profiles.update(source.id(), new UpdateProfileRequest(initial.sha256(), "t4",
                "pagination", List.of("S01", "S02", "S03"), List.of(),
                null, null, null, null, null, "page-window"));
        assertEquals("page-window", saved.snapshot().path("paginationMode").asText());
        var retained = profiles.update(source.id(), profileInput(saved,
                List.of("S01", "S02", "S03"), List.of(), null));
        assertEquals(saved.sha256(), retained.sha256());
        assertFalse(initial.snapshot().has("paginationMode"));
        for (String invalid : List.of("", "unknown", "PAGE-WINDOW")) {
            var error = assertThrows(ResponseStatusException.class, () -> profiles.update(source.id(),
                    new UpdateProfileRequest(saved.sha256(), "t4", "pagination",
                            List.of("S01", "S02", "S03"), List.of(),
                            null, null, null, null, null, invalid)));
            assertEquals("PAGINATION_MODE_INVALID", error.getReason());
            assertEquals(saved.sha256(), profiles.get(source.id()).sha256());
        }
        var reset = profiles.update(source.id(), new UpdateProfileRequest(saved.sha256(), "t4",
                "pagination", List.of("S01", "S02", "S03"), List.of(),
                null, null, null, null, null, "exact-total"));
        assertNotEquals(saved.sha256(), reset.sha256());
        assertEquals("exact-total", reset.snapshot().path("paginationMode").asText());
    }

    @Test
    void sourceConfigIsCanonicalFrozenPreservedAndExplicitlyClearable() throws Exception {
        var source = create(GOOD);
        var baseline = profiles.get(source.id());
        var config = mapper.readTree("""
                {"proxy_gateway":"https://gateway.example.test",
                 "gateway_retries":2,"subtitle_enabled":true}
                """);
        var saved = profiles.update(source.id(), new UpdateProfileRequest(baseline.sha256(), "t4",
                "source-config", List.of("S01", "S02", "S03"), List.of(), null, null, config));
        var same = profiles.update(source.id(), new UpdateProfileRequest(saved.sha256(), "t4",
                "source-config", List.of("S01", "S02", "S03"), List.of(), null, null,
                mapper.readTree("""
                        {"subtitle_enabled":true,"gateway_retries":2,
                         "proxy_gateway":"https://gateway.example.test"}
                        """)));
        assertEquals(saved.sha256(), same.sha256());
        assertEquals(config, profiles.get(source.id()).snapshot().path("sourceConfig"));
        var retained = profiles.update(source.id(), profileInput(saved, List.of("S01", "S02", "S03"),
                List.of(), null));
        assertEquals(saved.sha256(), retained.sha256());
        var cleared = profiles.update(source.id(), new UpdateProfileRequest(saved.sha256(), "t4",
                "source-config", List.of("S01", "S02", "S03"), List.of(), null, null,
                mapper.createObjectNode()));
        assertTrue(cleared.snapshot().has("sourceConfig"));
        assertTrue(cleared.snapshot().path("sourceConfig").isEmpty());
    }

    @Test
    void invalidPublicSourceDataNeverChangesTheCurrentProfile() throws Exception {
        var source = create(GOOD);
        var baseline = profiles.get(source.id());
        for (JsonNode data : List.of(mapper.createArrayNode(), mapper.nullNode(),
                mapper.createObjectNode().put("label", 20), mapper.createObjectNode().put("cookie", "PRIVATE"),
                mapper.createObjectNode().put("label", "x".repeat(1025)),
                mapper.createObjectNode().put("label", "bad\nvalue"),
                mapper.createObjectNode().put("label", "\uD800"))) {
            var error = assertThrows(ResponseStatusException.class, () -> profiles.update(source.id(),
                    new UpdateProfileRequest(baseline.sha256(), "t4", "public-config",
                            List.of("S01", "S02", "S03"), List.of(), null, data)));
            assertEquals(400, error.getStatusCode().value());
            assertEquals(baseline.sha256(), profiles.get(source.id()).sha256());
        }
    }

    @Test
    void apiRequiresAuthenticationAndCsrfAndNeverReturnsSourceInListing() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        URI url = URI.create("http://127.0.0.1:" + port + "/api/plugin-maintenance/sources");
        assertEquals(401, client.send(HttpRequest.newBuilder(url).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        String authorization = "Basic " + Base64.getEncoder()
                .encodeToString("admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
        URI containerUrl = URI.create("http://127.0.0.1:" + port + "/api/plugin-maintenance/container-runtime");
        assertEquals(401, client.send(HttpRequest.newBuilder(containerUrl).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        var containerResponse = client.send(HttpRequest.newBuilder(containerUrl)
                        .header("Authorization", authorization).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, containerResponse.statusCode());
        assertEquals("expected_configuration", mapper.readTree(containerResponse.body())
                .path("snapshot").path("authority").asText());
        String body = mapper.writeValueAsString(new CreateSourceRequest("HTTP 测试", null, "demo.py", null, GOOD));
        var noCsrf = HttpRequest.newBuilder(url).header("Authorization", authorization)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(403, client.send(noCsrf, HttpResponse.BodyHandlers.ofString()).statusCode());
        var csrfResponse = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/plugin-maintenance/csrf")).header("Authorization", authorization).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csrfResponse.statusCode());
        JsonNode csrf = mapper.readTree(csrfResponse.body());
        var post = HttpRequest.newBuilder(url).header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var created = client.send(post, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode(), created.body());
        assertFalse(created.body().contains("must never execute"));
        var listed = client.send(HttpRequest.newBuilder(url).header("Authorization", authorization).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listed.statusCode());
        assertFalse(listed.body().contains("must never execute"));
        var source = mapper.readTree(created.body());
        var done = await(probes.start(source.path("id").asText()).id());
        URI cancelUrl = URI.create("http://127.0.0.1:" + port
                + "/api/plugin-maintenance/probe-runs/" + done.id() + "/cancel");
        assertEquals(403, HttpClient.newHttpClient().send(HttpRequest.newBuilder(cancelUrl)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, client.send(HttpRequest.newBuilder(cancelUrl).header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var cancelled = client.send(HttpRequest.newBuilder(cancelUrl).header("Authorization", authorization)
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cancelled.statusCode());
        assertEquals("COMPLETED", mapper.readTree(cancelled.body()).path("status").asText());
        URI probeUrl = URI.create("http://127.0.0.1:" + port
                + "/api/plugin-maintenance/sources/" + source.path("id").asText() + "/probe-runs");
        for (String payload : List.of("", "{}", "{\"mode\":\"static\"}", "{\"mode\":\"container_fixture\"}")) {
            var accepted = client.send(HttpRequest.newBuilder(probeUrl).header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, accepted.statusCode(), accepted.body());
            var complete = await(mapper.readTree(accepted.body()).path("id").asText());
            if (payload.contains("container_fixture")) {
                assertEquals("container_fixture", complete.executionMode());
                assertEquals("TRUSTED_FIXTURE_REQUIRED", complete.errorCode());
            } else {
                assertEquals("static", complete.executionMode());
            }
        }
        var invalidMode = client.send(HttpRequest.newBuilder(probeUrl).header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"unknown\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalidMode.statusCode());
    }

    private SourceSummary create(String body) {
        return sources.create(new CreateSourceRequest("测试源", null, "demo.py", null, body));
    }

    private UpdateProfileRequest profileInput(ProfileSummary baseline, List<String> required,
                                              List<String> optional, JsonNode ext) {
        return new UpdateProfileRequest(baseline.sha256(), baseline.snapshot().path("runtime").asText(),
                baseline.snapshot().path("runtimeRevision").asText(), required, optional, ext, null);
    }

    private ProbeSummary await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        ProbeSummary run;
        do {
            run = store.get(id);
            if (!Set.of("QUEUED", "RUNNING").contains(run.status())) {
                return run;
            }
            Thread.sleep(30);
        } while (System.nanoTime() < deadline);
        fail("Probe did not finish within 15 seconds");
        return run;
    }

    private JsonNode node(ProbeSummary run, String id) {
        for (JsonNode node : run.result().path("nodes")) {
            if (node.path("id").asText().equals(id)) {
                return node;
            }
        }
        throw new AssertionError("Missing node " + id);
    }
}
