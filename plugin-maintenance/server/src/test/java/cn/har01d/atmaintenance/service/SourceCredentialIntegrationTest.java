package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:source-credentials;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=admin", "maintenance.scheduling.enabled=false",
        "maintenance.model.enabled=false", "maintenance.managed-source.enabled=false"
})
class SourceCredentialIntegrationTest {
    static final Path directory = temporary();
    @Autowired SourceService sources;
    @Autowired SourceCredentialStore credentials;
    @Autowired ConnectionSettingsStore settings;
    @Autowired ProbeStore probes;
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;

    static Path temporary() {
        try { return Files.createTempDirectory("source-credentials-"); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("maintenance.connections-file", () -> directory.resolve("connections.json").toString());
    }

    String source() {
        return sources.create(new CreateSourceRequest("fixture", null, "fixture.py",
                null, "class Spider:\n    pass\n")).id();
    }

    UpdateSourceCredentialsRequest input(String revision, String cookie, String authorization) {
        return new UpdateSourceCredentialsRequest(revision, true, "https://example.com", cookie, false,
                authorization, false);
    }

    @Test
    void credentialsPersistPrivatelyAndChangingThemInvalidatesOldRuntimeEvidence() throws Exception {
        String id = source();
        var saved = credentials.update(id, input("", "session=private-cookie", "Bearer private-token"));
        assertTrue(saved.configured());
        assertFalse(mapper.writeValueAsString(sources.get(id)).contains("private-"));
        var run = probes.create(id, "a".repeat(64), "b".repeat(64), "managed_source");
        assertEquals("current", probes.get(run.getId()).freshness());
        assertEquals("session=private-cookie", probes.runtimeCredentials(run.getId()).path("cookie").asText());
        var reopened = new SourceCredentialStore(
                new ConnectionSettingsStore(mapper, environment, directory.resolve("connections.json").toString()), mapper);
        assertEquals("Bearer private-token", reopened.execution(id, saved.revision()).path("authorization").asText());
        var preserved = credentials.update(id, input(saved.revision(), "", ""));
        assertEquals("session=private-cookie", credentials.execution(id, preserved.revision()).path("cookie").asText());
        assertEquals("credentials_changed", probes.get(run.getId()).freshness());
        assertEquals("SOURCE_CREDENTIALS_CHANGED",
                assertThrows(java.io.IOException.class, () -> probes.runtimeCredentials(run.getId())).getMessage());
        assertThrows(ResponseStatusException.class, () -> credentials.update(id, input(saved.revision(), "", "")));
    }

    @Test
    void explicitRequirementAndObserved401StopRepairBut403AloneDoesNot() throws Exception {
        String id = source();
        var saved = credentials.update(id, input("", "", ""));
        assertEquals("missing", sources.get(id).credentials().status());
        assertEquals("SOURCE_CREDENTIALS_REQUIRED", credentials.blockedReason(id));
        saved = credentials.update(id, input(saved.revision(), "session=private-cookie", ""));
        ObjectNode evidence = mapper.createObjectNode();
        evidence.putArray("httpObservations").addObject().put("responseComplete", true).put("responseStatus", 403);
        credentials.observe(id, saved.revision(), sources.get(id).artifactSha256(), evidence);
        assertNull(credentials.blockedReason(id));
        ((ObjectNode) evidence.path("httpObservations").get(0)).put("responseStatus", 401);
        credentials.observe(id, saved.revision(), sources.get(id).artifactSha256(), evidence);
        assertEquals("rejected", credentials.summary(id).status());
        assertEquals("SOURCE_AUTHENTICATION_REJECTED", credentials.blockedReason(id));
        var updated = credentials.update(id, input(saved.revision(), "session=new-private-cookie", ""));
        credentials.observe(id, saved.revision(), sources.get(id).artifactSha256(), evidence); // late old result cannot reject new state
        assertEquals("configured", credentials.summary(id).status());
        credentials.update(id, new UpdateSourceCredentialsRequest(updated.revision(), true,
                updated.origin(), "", true, "", true));
        assertEquals("missing", credentials.summary(id).status());
    }

    @Test
    void destinationChangeAndHeaderInjectionAreRejectedWithoutLeakingInput() throws Exception {
        String id = source();
        var saved = credentials.update(id, input("", "session=private-cookie", ""));
        var moved = new UpdateSourceCredentialsRequest(saved.revision(), true, "https://other.example.com",
                "", false, "", false);
        assertThrows(ResponseStatusException.class, () -> credentials.update(id, moved));
        var injection = input(saved.revision(), "session=private\r\nX-Test: private", "");
        var error = assertThrows(ResponseStatusException.class, () -> credentials.update(id, injection));
        assertEquals("SOURCE_CREDENTIALS_INVALID", error.getReason());
    }

    @Test
    void adminHttpEntryRequiresCsrfAndNeverReturnsStoredValues() throws Exception {
        String id = source(), path = "/sources/" + id + "/credentials";
        var http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String base = "http://127.0.0.1:" + port + "/api/plugin-maintenance";
        var anonymous = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode());
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
        var csrfReply = http.send(HttpRequest.newBuilder(URI.create(base + "/csrf"))
                .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString());
        var csrf = mapper.readTree(csrfReply.body());
        var body = mapper.valueToTree(input("", "session=http-private-cookie", "")).toString();
        var denied = http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", auth)
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode());
        var saved = http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", auth)
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, saved.statusCode(), saved.body());
        assertFalse(saved.body().contains("http-private-cookie"));
        assertTrue(saved.headers().firstValue("cache-control").orElse("").contains("no-store"));
    }

    ObjectNode jsonEvidence(String sha, String text) {
        String urlSha = Hashes.sha256("https://example.com/api/play");
        String bodySha = Hashes.sha256(text);
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        var evidence = mapper.createObjectNode().put("authority", "managed_source_host_observed")
                .put("inputSourceSha256", sha);
        evidence.putArray("httpObservations").addObject().put("method", "GET")
                .put("requestUrlSha256", urlSha).put("responseComplete", true).put("responseStatus", 200)
                .put("responseBytes", bytes).put("responseSha256", bodySha);
        var loader = evidence.putObject("loaderValidation").put("authority", "host_loader_contract_observed")
                .put("inputSourceSha256", sha);
        loader.putArray("requiredNodes").add("R01");
        loader.putArray("nodes").addObject().put("id", "R01").put("status", "blocked");
        var resources = evidence.putObject("resourceEvidence").put("schemaVersion", 1)
                .put("authority", "host_public_resource_observed").put("artifactSha256", sha)
                .put("targetNode", "R01").putNull("image").put("textByteLimit", 18000).put("fetchLimit", 8)
                .put("sourceCodeExecuted", false).put("businessVerdictTrusted", false)
                .put("collectionStatus", "observed").put("textBytes", bytes);
        resources.putArray("failures");
        resources.putArray("resources").addObject().put("kind", "page").put("urlSha256", urlSha)
                .putNull("parentUrlSha256").put("responseSha256", bodySha).put("responseBytes", bytes)
                .putArray("excerpts").addObject().put("offset", 0).put("text", text).put("textSha256", bodySha);
        return evidence;
    }

    static final String AUTH_FAILURE = "{\"success\":0,\"action\":\"JWTVerificationError\","
            + "\"message\":\"\u8bf7\u767b\u5f55\u5e10\u53f7\",\"data\":null}";

    @Test
    void currentSanitizedJsonEvidenceStopsMaintenanceAndPreservesConfiguredSecrets() throws Exception {
        String id = source(), sha = sources.get(id).artifactSha256();
        var evidence = SourceAuthenticationEvidenceTest.jsonEvidence(mapper, sha);
        credentials.observe(id, "", sha, evidence);
        assertEquals("missing", credentials.summary(id).status());
        var saved = credentials.update(id, input("", "session=private-cookie", "Bearer private-token"));
        credentials.observe(id, saved.revision(), sha, evidence);
        assertEquals("rejected", credentials.summary(id).status());
        assertEquals(saved.revision(), credentials.revision(id));
        assertEquals("session=private-cookie", settings.sourceCredentials(id).path("cookie").asText());
        assertEquals("Bearer private-token", settings.sourceCredentials(id).path("authorization").asText());
    }

    @Test
    void completeStructuredJsonRejectionMarksMissingOrRejectedWithoutChangingSecrets() throws Exception {
        String id = source(), sha = sources.get(id).artifactSha256();
        credentials.observe(id, "", sha, jsonEvidence(sha, AUTH_FAILURE));
        assertEquals("missing", credentials.summary(id).status());
        assertEquals("", credentials.revision(id));
        assertEquals("SOURCE_CREDENTIALS_REQUIRED", credentials.blockedReason(id));
        var saved = credentials.update(id, input("", "session=private-cookie", "Bearer private-token"));
        credentials.observe(id, saved.revision(), sha, jsonEvidence(sha, "{\"code\":401,\"success\":false}"));
        assertEquals("rejected", credentials.summary(id).status());
        assertEquals(saved.revision(), credentials.revision(id));
        assertEquals("session=private-cookie", settings.sourceCredentials(id).path("cookie").asText());
        assertEquals("Bearer private-token", settings.sourceCredentials(id).path("authorization").asText());
    }

    @Test
    void optionalSuccessfulUnboundIncompleteOrTextualResponsesDoNotBlockCredentials() throws Exception {
        String id = source(), sha = sources.get(id).artifactSha256();
        var base = jsonEvidence(sha, AUTH_FAILURE);
        var optional = base.deepCopy();
        ((ObjectNode) optional.path("loaderValidation")).putArray("requiredNodes");
        var passed = base.deepCopy();
        ((ObjectNode) passed.path("loaderValidation").path("nodes").get(0)).put("status", "pass");
        var incomplete = base.deepCopy();
        ((ObjectNode) incomplete.path("httpObservations").get(0)).put("responseComplete", false);
        var changedUrl = base.deepCopy();
        ((ObjectNode) changedUrl.path("httpObservations").get(0)).put("requestUrlSha256", "a".repeat(64));
        var truncated = base.deepCopy();
        ((ObjectNode) truncated.path("resourceEvidence").path("resources").get(0)).put("responseBytes", 100);
        ((ObjectNode) truncated.path("httpObservations").get(0)).put("responseBytes", 100);
        var tampered = base.deepCopy();
        ((ObjectNode) tampered.path("resourceEvidence").path("resources").get(0)
                .path("excerpts").get(0)).put("text", "{\"code\":401}");
        for (var evidence : java.util.List.of(optional, passed, incomplete, changedUrl, truncated, tampered,
                jsonEvidence("a".repeat(64), AUTH_FAILURE),
                jsonEvidence(sha, "{\"success\":1,\"code\":401}"),
                jsonEvidence(sha, "{\"success\":false,\"message\":\"Please login\"}"),
                jsonEvidence(sha, "<html>Please login</html>"),
                jsonEvidence(sha, "{\"code\":401} {\"code\":200}"))) {
            credentials.observe(id, "", sha, evidence);
            assertEquals("optional", credentials.summary(id).status(), evidence.toString());
        }
    }

    @Test
    void savedObservationHttpEntryReusesBoundRunWithCsrfAndLeavesHistoryUnchanged() throws Exception {
        String id = source(), sha = sources.get(id).artifactSha256();
        var run = probes.create(id, "a".repeat(64), "b".repeat(64), "managed_source");
        var evidence = jsonEvidence(sha, AUTH_FAILURE).put("jobId", run.getId());
        var result = mapper.createObjectNode().set("managedSourceEvidence", evidence);
        probes.finish(run.getId(), "COMPLETED", result, null, null);
        var before = probes.get(run.getId());
        var input = new ObserveSourceCredentialsRequest(sha, run.getId(), "");
        var http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String base = "http://127.0.0.1:" + port + "/api/plugin-maintenance";
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
        var csrfReply = http.send(HttpRequest.newBuilder(URI.create(base + "/csrf"))
                .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString());
        var csrf = mapper.readTree(csrfReply.body());
        var request = HttpRequest.newBuilder(URI.create(base + "/sources/" + id + "/credentials/observations"))
                .header("Authorization", auth).header("Content-Type", "application/json");
        String body = mapper.writeValueAsString(input);
        var denied = http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode());
        var observed = http.send(request.header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, observed.statusCode(), observed.body());
        assertEquals("missing", mapper.readTree(observed.body()).path("status").asText());
        assertTrue(observed.headers().firstValue("cache-control").orElse("").contains("no-store"));
        assertEquals(before, probes.get(run.getId()));
        assertEquals("missing", probes.observeCredentials(id, input).status());
        String other = source();
        assertThrows(ResponseStatusException.class, () -> probes.observeCredentials(other, input));
        var updated = credentials.update(id, input("", "session=new", ""));
        var error = assertThrows(ResponseStatusException.class, () -> probes.observeCredentials(id, input));
        assertEquals("SOURCE_CREDENTIALS_CHANGED", error.getReason());
        assertEquals(updated, credentials.summary(id));
        assertThrows(ResponseStatusException.class, () -> probes.observeCredentials(id,
                new ObserveSourceCredentialsRequest("b".repeat(64), run.getId(), updated.revision())));
    }
}
