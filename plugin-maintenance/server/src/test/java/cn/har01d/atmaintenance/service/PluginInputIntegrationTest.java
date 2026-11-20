package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:plugin-input-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=plugin-input-test-only",
        "maintenance.at.origin=",
        "maintenance.scheduling.enabled=false",
        "maintenance.model.enabled=false",
        "maintenance.media.enabled=false",
        "maintenance.container.enabled=false"
})
class PluginInputIntegrationTest {
    @Autowired PluginInputService inputs;
    @Autowired SourceService sources;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PluginInputInspector inspector;
    @MockitoBean AtReadClient at;
    @Value("${maintenance.python}") String python;
    @LocalServerPort int port;

    private static final String SOURCE = "# \u4e2d\u6587\r\nraise RuntimeError('PRIVATE_SOURCE_MUST_NOT_EXECUTE')\r\nclass Spider: pass\r\n";
    private PluginInputInspector ownedInspector;
    private byte[] packageBytes;

    @DynamicPropertySource
    static void python(DynamicPropertyRegistry registry) {
        registry.add("maintenance.python", () -> System.getProperty("maintenance.test.python",
                Path.of("../../.venv/Scripts/python.exe").toAbsolutePath().normalize().toString()));
    }

    @BeforeEach
    void ownEphemeralKeysAndActualTrustedProcessor() throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        String master = HexFormat.of().formatHex(random(32));
        Map<String, byte[]> files = PluginInputInspector.bundledFiles();
        String atvp = new String(files.get("runtime/Atvp.py"), StandardCharsets.UTF_8)
                + "\nSpider._public_key_chunks = Spider._obfuscate_text_for_test("
                + mapper.writeValueAsString(publicPem) + ", Spider.PUBLIC_KEY_XOR)\n"
                + "Spider._master_secret_chunks = Spider._obfuscate_text_for_test("
                + mapper.writeValueAsString(master) + ", Spider.MASTER_SECRET_XOR)\n";
        byte[] runtime = atvp.getBytes(StandardCharsets.UTF_8);
        ObjectNode manifest = (ObjectNode) mapper.readTree(files.get("runtime/manifest.json"));
        ((ObjectNode) manifest.path("files").path("Atvp.py"))
                .put("bytes", runtime.length).put("sha256", Hashes.sha256(runtime));
        files.put("runtime/Atvp.py", runtime);
        files.put("runtime/manifest.json", mapper.writeValueAsBytes(manifest));
        ownedInspector = new PluginInputInspector(mapper, python, files);
        when(inspector.inspect(any())).thenAnswer(call -> ownedInspector.inspect(call.getArgument(0)));

        var fields = new LinkedHashMap<String, String>();
        fields.put("name", "\u81ea\u6709\u79bb\u7ebf\u6837\u672c");
        fields.put("version", "1");
        fields.put("remark", "");
        fields.put("id", "owned-offline-test");
        fields.put("format", "secspider/1");
        fields.put("alg", "aes-256-gcm");
        fields.put("wrap", "hkdf-aes-keywrap");
        fields.put("sign", "ed25519");
        fields.put("kid", "ephemeral-only");
        byte[] nonce = random(12), contentKey = random(32);
        fields.put("nonce", "base64:" + encoded(nonce));
        fields.put("ek", "base64:" + encoded(encrypt(
                derive(master, fields, "wrap-key", 32), derive(master, fields, "wrap-nonce", 12), contentKey)));
        fields.put("hash", "sha256:" + Hashes.sha256(SOURCE));
        String payload = "payload.base64:" + encoded(encrypt(contentKey, nonce, SOURCE.getBytes(StandardCharsets.UTF_8)));
        String signing = String.join("\n", fields.entrySet().stream()
                .map(entry -> "//@" + entry.getKey() + ":" + entry.getValue()).toList()) + "\n" + payload;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(signing.getBytes(StandardCharsets.UTF_8));
        fields.put("sig", "base64:" + encoded(signer.sign()));
        packageBytes = (String.join("\n", fields.entrySet().stream()
                .map(entry -> "//@" + entry.getKey() + ":" + entry.getValue()).toList())
                + "\n" + payload + "\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void encryptedAtImportPreservesPackageAndPlaintextIdentitiesAndBinding() {
        var connection = new AtConnection(true, "owned-at", "a".repeat(64), "b".repeat(64), "fixture", "configured");
        var plugin = new AtPluginSummary(707, "owned encrypted", null, true, true, 1,
                "secspider", "plugin-707", "c".repeat(64), false);
        var observation = new AtObservation("d".repeat(64), connection, java.time.Instant.now(),
                List.of(plugin), "metadata_observed", "fixture", List.of());
        when(at.connection()).thenReturn(connection);
        when(at.requireObservation(observation.sha256())).thenReturn(observation);
        when(at.readPythonSource(observation.sha256(), 707)).thenReturn(packageBytes);
        when(at.sourceReadAuthority(707)).thenReturn("at_static_source_read");
        var preview = inputs.previewAt(new PreviewAtPluginInputRequest(observation.sha256(), 707, true));
        assertTrue(preview.evidence().path("signatureVerified").asBoolean());
        assertEquals(Hashes.sha256(packageBytes), preview.evidence().path("atSource").path("sourceSha256").asText());
        assertEquals(Hashes.sha256(SOURCE), preview.artifactSha256());
        var saved = inputs.confirmAt(new ConfirmAtPluginInputRequest(observation.sha256(), 707, true,
                preview.evidenceSha256(), key(), "owned encrypted", "owned.py"));
        assertEquals(SOURCE, sources.artifact(saved.sourceId(), saved.artifactSha256()).getBody());
        assertEquals("secspider/1", saved.evidence().path("inputFormat").asText());
        assertArrayEquals(packageBytes, Base64.getDecoder().decode(jdbc.queryForObject(
                "select input_base64 from maintenance_plugin_input where id = ?", String.class, saved.id())));
        assertEquals(707, jdbc.queryForObject("select plugin_id from maintenance_at_binding where source_id = ?",
                Integer.class, saved.sourceId()));
        when(at.readPythonSource(observation.sha256(), 707)).thenReturn(SOURCE.getBytes(StandardCharsets.UTF_8));
        assertError(400, "AT_SOURCE_FORMAT_INVALID", () -> inputs.previewAt(
                new PreviewAtPluginInputRequest(observation.sha256(), 707, true)));
    }

    @Test
    void encryptedPreviewConfirmAndHistoricalCoverageUseActualCryptoWithoutExecutingSource() throws Exception {
        long[] before = counts();
        var preview = inputs.preview(new PreviewPluginInputRequest(encoded(packageBytes), true));
        assertArrayEquals(before, counts());
        assertEquals("secspider/1", preview.evidence().path("inputFormat").asText());
        assertTrue(preview.evidence().path("signatureVerified").asBoolean());
        assertTrue(preview.evidence().path("declaredHashVerified").asBoolean());
        assertFalse(preview.evidence().path("sourceExecuted").asBoolean());
        assertFalse(preview.evidence().path("businessChainVerified").asBoolean());
        assertFalse(preview.evidence().path("publishEligible").asBoolean());
        assertNotEquals(preview.artifactSha256(), preview.evidence().path("inputSha256").asText());
        var request = confirm(packageBytes, preview.evidenceSha256(), key(), "owned");
        var saved = inputs.confirm(request);
        assertEquals(Hashes.sha256(SOURCE), saved.artifactSha256());
        assertEquals(SOURCE, sources.artifact(saved.sourceId(), saved.artifactSha256()).getBody());
        assertEquals(List.of(saved), inputs.list(saved.sourceId()));
        var source = sources.get(saved.sourceId());
        assertNull(source.externalId());
        assertNull(source.originUrl());
        var changed = sources.addArtifact(saved.sourceId(),
                new AddArtifactRequest(source.artifactSha256(), SOURCE + "# local edit\n"));
        long[] afterEdit = counts();
        assertEquals(saved, inputs.confirm(request));
        assertArrayEquals(afterEdit, counts());
        assertNotEquals(changed.artifactSha256(), saved.artifactSha256());
        assertEquals(saved.artifactSha256(), inputs.list(saved.sourceId()).getFirst().artifactSha256());
        String stored = jdbc.queryForObject("select input_base64 from maintenance_plugin_input where id = ?",
                String.class, saved.id());
        assertArrayEquals(packageBytes, Base64.getDecoder().decode(stored));
        var restored = inputs.original(source.id(), saved.id());
        assertArrayEquals(packageBytes, restored.getBody(), "restoring the original must never package the later edit");
        assertEquals("no-store", restored.getHeaders().getCacheControl());
        assertTrue(restored.getHeaders().getContentDisposition().getFilename().endsWith(".txt"));
        var unrelated = sources.create(new CreateSourceRequest("unrelated", null, "unrelated.py", null, SOURCE));
        assertError(404, "PLUGIN_INPUT_NOT_FOUND", () -> inputs.original(unrelated.id(), saved.id()));
        jdbc.update("update maintenance_plugin_input set input_base64 = ? where id = ?", encoded(new byte[]{1}), saved.id());
        assertEquals("PLUGIN_INPUT_PACKAGE_MISMATCH", assertThrows(IllegalStateException.class,
                () -> inputs.original(source.id(), saved.id())).getMessage());
        assertEquals(0, jdbc.queryForObject("select count(*) from maintenance_at_binding where source_id = ?",
                Integer.class, source.id()));
    }

    @Test
    void stockRuntimeRejectsAnOwnedButUnmatchedSigningKey() throws Exception {
        var stock = new PluginInputInspector(mapper, python);
        assertError(400, "PLUGIN_SIGNATURE_REJECTED", () -> stock.inspect(packageBytes));
    }

    @Test
    void tamperedPackagesAndChangedPreviewNeverCreateSourceRows() {
        long[] before = counts();
        byte[] tampered = new String(packageBytes, StandardCharsets.UTF_8)
                .replace("//@id:owned-offline-test", "//@id:tampered").getBytes(StandardCharsets.UTF_8);
        assertError(400, "PLUGIN_SIGNATURE_REJECTED",
                () -> inputs.preview(new PreviewPluginInputRequest(encoded(tampered), true)));
        assertError(409, "PLUGIN_INPUT_PREVIEW_CHANGED",
                () -> inputs.confirm(confirm(packageBytes, "f".repeat(64), key(), "not-created")));
        assertArrayEquals(before, counts());
    }

    @Test
    void rawSourceExactBytesAndLargePlaintextDoNotTravelThroughBoundedStdout() {
        String content = SOURCE + "#" + "a".repeat(300_000);
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        var preview = inputs.preview(new PreviewPluginInputRequest(encoded(body), true));
        assertTrue(preview.evidence().path("signatureVerified").isNull());
        var saved = inputs.confirm(confirm(body, preview.evidenceSha256(), key(), "large-raw"));
        assertEquals(content, sources.artifact(saved.sourceId(), saved.artifactSha256()).getBody());
    }

    @Test
    void authorizationSizeEncodingSyntaxAndReusedKeysAreRejected() {
        long[] before = counts();
        assertError(400, "PLUGIN_PLAINTEXT_AUTHORIZATION_REQUIRED",
                () -> inputs.preview(new PreviewPluginInputRequest(encoded(packageBytes), false)));
        assertError(400, "PLUGIN_INPUT_BASE64_INVALID",
                () -> inputs.preview(new PreviewPluginInputRequest("bad%", true)));
        assertError(413, "PLUGIN_INPUT_SIZE_INVALID",
                () -> inputs.preview(new PreviewPluginInputRequest(encoded(new byte[1_048_577]), true)));
        assertError(400, "PLUGIN_INPUT_ENCODING_INVALID",
                () -> inputs.preview(new PreviewPluginInputRequest(encoded(new byte[]{(byte) 255}), true)));
        assertError(400, "PLUGIN_PLAINTEXT_SYNTAX_INVALID", () -> inputs.preview(
                new PreviewPluginInputRequest(encoded("class Spider(:".getBytes(StandardCharsets.UTF_8)), true)));
        assertArrayEquals(before, counts());
        var preview = inputs.preview(new PreviewPluginInputRequest(encoded(packageBytes), true));
        String key = key();
        inputs.confirm(confirm(packageBytes, preview.evidenceSha256(), key, "first"));
        long[] after = counts();
        assertError(409, "PLUGIN_INPUT_REQUEST_KEY_REUSED",
                () -> inputs.confirm(confirm(packageBytes, preview.evidenceSha256(), key, "other")));
        assertArrayEquals(after, counts());
    }

    @Test
    void httpAdminCsrfAndSecretFreeResponsesCoverTheRealEncryptedEntry() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String auth = "Basic " + encoded("admin:plugin-input-test-only".getBytes(StandardCharsets.UTF_8));
        var body = new PreviewPluginInputRequest(encoded(packageBytes), true);
        assertEquals(401, client.send(HttpRequest.newBuilder(uri("/sources/no-source/plugin-inputs")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, client.send(HttpRequest.newBuilder(uri("/plugin-inputs/preview"))
                        .header("Authorization", auth).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        var csrfResponse = client.send(HttpRequest.newBuilder(uri("/csrf")).header("Authorization", auth).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode csrf = mapper.readTree(csrfResponse.body());
        var preview = post(client, auth, csrf, "/plugin-inputs/preview", body);
        assertEquals(200, preview.statusCode(), preview.body());
        JsonNode checked = mapper.readTree(preview.body());
        var confirmed = post(client, auth, csrf, "/plugin-inputs",
                confirm(packageBytes, checked.path("evidenceSha256").asText(), key(), "HTTP-own"));
        assertEquals(201, confirmed.statusCode(), confirmed.body());
        var saved = mapper.readTree(confirmed.body());
        URI original = uri("/sources/" + saved.path("sourceId").asText() + "/plugin-inputs/"
                + saved.path("id").asText() + "/original");
        assertEquals(401, client.send(HttpRequest.newBuilder(original).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        var download = client.send(HttpRequest.newBuilder(original).header("Authorization", auth).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, download.statusCode());
        assertArrayEquals(packageBytes, download.body());
        assertEquals("no-store", download.headers().firstValue("Cache-Control").orElseThrow());
        var listed = client.send(HttpRequest.newBuilder(uri("/sources/" + saved.path("sourceId").asText()
                + "/plugin-inputs")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listed.statusCode());
        for (var response : List.of(preview, confirmed, listed)) {
            assertFalse(response.body().contains("PRIVATE_SOURCE_MUST_NOT_EXECUTE"));
            assertFalse(response.body().contains(encoded(packageBytes)));
            assertFalse(response.body().contains("payload.base64"));
            assertFalse(response.body().contains("inputBase64"));
        }
        assertEquals(400, post(client, auth, csrf, "/plugin-inputs/preview",
                new PreviewPluginInputRequest(encoded(packageBytes), false)).statusCode());
        assertEquals(400, post(client, auth, csrf, "/plugin-inputs", new ConfirmPluginInputRequest(
                encoded(packageBytes), true, checked.path("evidenceSha256").asText(), key(), "bad", "../bad.py"))
                .statusCode());
    }

    private byte[] derive(String master, Map<String, String> fields, String suffix, int length) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(fields.get("kid").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] prk = hmac.doFinal(master.getBytes(StandardCharsets.UTF_8));
        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        hmac.update(("secspider:" + fields.get("name") + ":1:" + suffix).getBytes(StandardCharsets.UTF_8));
        return Arrays.copyOf(hmac.doFinal(new byte[]{1}), length);
    }

    private byte[] encrypt(byte[] key, byte[] nonce, byte[] content) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(content);
    }

    private static byte[] random(int size) {
        byte[] value = new byte[size];
        new SecureRandom().nextBytes(value);
        return value;
    }

    private ConfirmPluginInputRequest confirm(byte[] body, String evidenceSha, String key, String name) {
        return new ConfirmPluginInputRequest(encoded(body), true, evidenceSha, key, name, "source.py");
    }

    private String key() { return UUID.randomUUID().toString(); }
    private String encoded(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private long[] counts() {
        return new long[]{jdbc.queryForObject("select count(*) from maintenance_source", Long.class),
                jdbc.queryForObject("select count(*) from maintenance_artifact", Long.class),
                jdbc.queryForObject("select count(*) from maintenance_plugin_input", Long.class)};
    }
    private void assertError(int status, String code, org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(ResponseStatusException.class, action);
        assertEquals(status, error.getStatusCode().value());
        assertEquals(code, error.getReason());
    }
    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/api/plugin-maintenance" + path);
    }
    private HttpResponse<String> post(HttpClient client, String auth, JsonNode csrf, String path, Object body)
            throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", auth)
                        .header("Content-Type", "application/json")
                        .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
