package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.AtObservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AtReadClientTest {
    static final String BODY = """
            [{"id":7,"name":"中文重命名","externalId":"stable-key","url":"https://fixture.invalid/source.py?token=LOCATOR_SECRET",
              "enabled":true,"t4Visible":true,"version":12,"extend":"EXT_SECRET","lastError":"ERROR_SECRET",
              "content":"SOURCE_SECRET","configSchema":{"secret":"SCHEMA_SECRET"}},
             {"id":8,"name":"Fallback","url":"/static/plugins/fallback.txt","enabled":false,"t4Visible":false,"version":null},
             {"id":9,"name":"Web","externalId":"ignored","url":"/static/webhome/pages/a/b.html","enabled":true,"t4Visible":true}]
            """;
    HttpServer server;
    AtReadClient client;
    String body;
    String type;
    int status;
    boolean slow;
    AtomicInteger requests;
    String method;
    String path;
    String authorization;
    String apiKey;
    String runtimeIdentityBody;
    String sourceBody = "raise RuntimeError('SOURCE_MUST_NOT_EXECUTE')\nclass Spider: pass\n";
    String sourceType = "application/octet-stream";

    @BeforeEach
    void start() throws Exception {
        body = BODY; type = "application/json"; status = 200; requests = new AtomicInteger();
        runtimeIdentityBody = """
                {"pluginId":7,"siteKey":"stable-key","sourceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "runtimeRevision":"t4-direct-v1","loadState":"LOADED","loaded":true,"workerGeneration":"0123456789abcdef0123456789abcdef"}
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().toString();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            apiKey = exchange.getRequestHeaders().getFirst("X-API-KEY");
            boolean source = exchange.getRequestURI().getPath().startsWith("/static/");
            exchange.getResponseHeaders().set("Content-Type", source ? sourceType : type);
            if (status == 302) exchange.getResponseHeaders().set("Location", address() + "/credential-trap");
            byte[] bytes = source ? sourceBody.getBytes(StandardCharsets.UTF_8)
                    : exchange.getRequestURI().getPath().contains("runtime-identity")
                    ? runtimeIdentityBody.getBytes(StandardCharsets.UTF_8)
                    : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, slow ? 0 : bytes.length);
            try (var output = exchange.getResponseBody()) {
                if (slow) {
                    output.write('[');
                    output.flush();
                    try { Thread.sleep(750); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                output.write(bytes);
            } catch (java.io.IOException ignored) {
                // Expected when the tested client cancels a slow or oversized body.
            }
        });
        server.start();
    }

    @AfterEach
    void stop() { if (client != null) client.close(); server.stop(0); }

    @Test
    void explicitExclusionsAreVisibleWithoutGuessingPurposeFromNamesAndSurviveConnectionReplacement() {
        client = client(3000, 100_000);
        client.configureExclusions("416, 416");
        body = BODY.replace("中文重命名", "豆瓣TMDB助手");
        assertEquals(java.util.List.of(416), client.observe().excludedPluginIds());
        assertDoesNotThrow(() -> client.requireMaintenanceAllowed(7));
        int reads = requests.get();
        assertEquals("AT_PLUGIN_MAINTENANCE_EXCLUDED", assertThrows(ResponseStatusException.class,
                () -> client.readPythonSource("not-observed", 416)).getReason());
        assertEquals(reads, requests.get());
        client.replace(client(3000, 100_000));
        assertEquals(java.util.List.of(416), client.excludedPluginIds());
        assertEquals("AT_PLUGIN_MAINTENANCE_EXCLUDED", assertThrows(ResponseStatusException.class,
                () -> client.requireMaintenanceAllowed(416)).getReason());
        assertDoesNotThrow(() -> client.requireMaintenanceAllowed(7));
    }

    @Test
    void singleGetUsesOnlyConfiguredSessionAndReturnsSanitizedMetadataWithHonestKeys() {
        client = client(3000, 100_000);
        var observed = client.observe();
        assertEquals("GET", method);
        assertEquals("/api/plugins", path);
        assertEquals("SYNTHETIC_SESSION_SECRET", authorization);
        assertNull(apiKey);
        assertEquals(3, observed.plugins().size());
        assertEquals("stable-key", observed.plugins().get(0).derivedSiteKey());
        assertEquals("plugin-8", observed.plugins().get(1).derivedSiteKey());
        assertEquals("web_a_b", observed.plugins().get(2).derivedSiteKey());
        assertEquals("python", observed.plugins().get(0).kind());
        assertEquals("metadata_observed", observed.authority());
        for (String marker : new String[]{"LOCATOR_SECRET", "EXT_SECRET", "ERROR_SECRET", "SOURCE_SECRET",
                "SCHEMA_SECRET", "SYNTHETIC_SESSION_SECRET", "https://"}) {
            assertFalse(observed.toString().contains(marker), marker);
        }
        assertTrue(observed.plugins().getFirst().hasLastError());
        assertEquals(observed, client.observe());
        assertEquals(1, requests.get(), "30-second cache must suppress repeated remote calls");
        assertEquals(observed, client.requireObservation(observed.sha256()));
        assertEquals("AT_OBSERVATION_CHANGED", assertThrows(ResponseStatusException.class,
                () -> client.requireObservation("0".repeat(64))).getReason());
    }

    @Test
    void apiKeyModeReadsSanitizedMetadataWithoutSendingSessionOrClaimingRuntimeIdentity() {
        client = apiKeyClient("", "SYNTHETIC_API_KEY_SECRET", "api_key");
        var observed = client.observe();
        assertEquals("GET", method);
        assertEquals("/api/plugins", path);
        assertNull(authorization);
        assertEquals("SYNTHETIC_API_KEY_SECRET", apiKey);
        assertEquals(3, observed.plugins().size());
        assertEquals("metadata_observed", observed.authority());
        assertFalse(observed.toString().contains("SYNTHETIC_API_KEY_SECRET"));
        assertFalse(client.connection().toString().contains("SYNTHETIC_API_KEY_SECRET"));
        assertEquals(observed, client.observe());
        assertEquals(1, requests.get());
        assertEquals("AT_RUNTIME_IDENTITY_REQUIRES_SESSION", assertThrows(ResponseStatusException.class,
                () -> client.observeRuntimeIdentity(7, "stable-key")).getReason());
        assertEquals(1, requests.get(), "CLIENT key must never be sent to the ADMIN identity route");
    }

    @Test
    void apiKeyAuthenticationFailureHasNoSessionFallbackAndRedirectsAreNotFollowed() {
        for (int responseStatus : new int[]{401, 403, 302}) {
            if (client != null) client.close();
            client = apiKeyClient("", "SYNTHETIC_API_KEY_SECRET", "api_key");
            status = responseStatus;
            int before = requests.get();
            assertEquals(status == 302 ? "AT_REDIRECT_REFUSED" : "AT_AUTHENTICATION_FAILED",
                    assertThrows(ResponseStatusException.class, client::observe).getReason());
            assertEquals(before + 1, requests.get());
            assertEquals("/api/plugins", path);
            assertNull(authorization);
            assertEquals("SYNTHETIC_API_KEY_SECRET", apiKey);
            assertEquals("AT_READ_COOLDOWN", assertThrows(ResponseStatusException.class, client::observe).getReason());
            assertEquals(before + 1, requests.get());
        }
    }

    @Test
    void authenticationModeMustBeExplicitUnambiguousAndHeaderSafe() {
        for (String[] value : new String[][]{
                {"", "", "api_key"}, {"session-secret", "key-secret", "api_key"},
                {"session-secret", "key-secret", "session"}, {"", "key-secret", "automatic"},
                {"", "key-secret", ""}, {"", "invalid key", "api_key"},
                {"", "key\r\nAuthorization:secret", "api_key"}, {"", "key\u4e2d", "api_key"},
                {"", "a".repeat(8193), "api_key"}}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> apiKeyClient(value[0], value[1], value[2]));
            assertEquals("INVALID_AT_CONNECTION", error.getMessage());
        }
        assertEquals(0, requests.get());
    }

    @Test
    void redirectsNeverForwardCredentialsToAnotherPath() {
        status = 302;
        client = client(3000, 100_000);
        assertEquals("AT_REDIRECT_REFUSED", assertThrows(ResponseStatusException.class, client::observe).getReason());
        assertEquals(1, requests.get());
        assertEquals("/api/plugins", path);
    }

    @Test
    void classificationMatchesBaselineCaseInsensitiveAndUnescapedLocalPythonPaths() {
        for (String[] sample : new String[][]{
                {" https://fixture.invalid/source.PY?token=LOCATOR_SECRET ", "python"},
                {"/static/plugins/[中文 空格].PY", "python"},
                {"/static/plugins/source.TXT?token=LOCATOR_SECRET", "secspider"},
                {"https://fixture.invalid/download?name=source.py", "unknown"}}) {
            if (client != null) client.close();
            client = client(3000, 100_000);
            body = BODY.replace("https://fixture.invalid/source.py?token=LOCATOR_SECRET", sample[0]);
            assertEquals(sample[1], client.observe().plugins().getFirst().kind());
        }
    }

    @Test
    void expiredOrFailedRefreshCannotAuthorizeBindingsUsingAnEarlierObservation() {
        client = client(3000, 100_000);
        var observed = client.observe();
        org.springframework.test.util.ReflectionTestUtils.setField(client, "cached",
                new AtObservation(observed.sha256(), observed.connection(), Instant.now().minusSeconds(301),
                        observed.plugins(), observed.authority(), observed.boundary(), observed.excludedPluginIds()));
        assertEquals("AT_OBSERVATION_EXPIRED", assertThrows(ResponseStatusException.class,
                () -> client.requireObservation(observed.sha256())).getReason());
        org.springframework.test.util.ReflectionTestUtils.setField(client, "nextAttemptNanos", 0L);
        status = 401;
        assertEquals("AT_AUTHENTICATION_FAILED", assertThrows(ResponseStatusException.class, client::observe).getReason());
        assertEquals("AT_OBSERVATION_CHANGED", assertThrows(ResponseStatusException.class,
                () -> client.requireObservation(observed.sha256())).getReason());
        assertEquals("AT_READ_COOLDOWN", assertThrows(ResponseStatusException.class, client::observe).getReason());
        assertEquals(2, requests.get());
    }

    @Test
    void streamedBodyLimitAndFullBodyDeadlineAreEnforced() {
        client = client(3000, 20);
        assertEquals("AT_RESPONSE_TOO_LARGE", assertThrows(ResponseStatusException.class, client::observe).getReason());
        client.close();
        slow = true;
        client = client(200, 100_000);
        long start = System.nanoTime();
        assertEquals("AT_READ_TIMEOUT", assertThrows(ResponseStatusException.class, client::observe).getReason());
        assertTrue(System.nanoTime() - start < java.time.Duration.ofSeconds(2).toNanos());
    }

    @Test
    void authHtmlInvalidJsonAndIdentityCollisionsNeverBecomeSuccessfulObservations() {
        client = client(3000, 100_000);
        status = 401;
        assertEquals("AT_AUTHENTICATION_FAILED", assertThrows(ResponseStatusException.class, client::observe).getReason());
        assertEquals("AT_READ_COOLDOWN", assertThrows(ResponseStatusException.class, client::observe).getReason());
        assertEquals(1, requests.get());
        client.close(); client = client(3000, 100_000);
        status = 200; type = "text/html";
        assertEquals("AT_RESPONSE_TYPE", assertThrows(ResponseStatusException.class, client::observe).getReason());
        client.close(); client = client(3000, 100_000);
        type = "application/json"; body = "[invalid secret source";
        assertEquals("AT_RESPONSE_INVALID", assertThrows(ResponseStatusException.class, client::observe).getReason());
        client.close(); client = client(3000, 100_000);
        body = BODY.replace("\"id\":8", "\"id\":7");
        assertEquals("AT_IDENTITY_COLLISION", assertThrows(ResponseStatusException.class, client::observe).getReason());
        client.close(); client = client(3000, 100_000);
        body = BODY.replace("\"externalId\":\"stable-key\"", "\"externalId\":\"plugin-8\"");
        assertEquals("AT_IDENTITY_COLLISION", assertThrows(ResponseStatusException.class, client::observe).getReason());
        client.close(); client = client(3000, 100_000);
        body = "{}";
        assertEquals("AT_RESPONSE_INVALID", assertThrows(ResponseStatusException.class, client::observe).getReason());
        for (String invalid : new String[]{BODY + " []", BODY.replace("\"id\":7", "\"id\":6,\"id\":7")}) {
            client.close(); client = client(3000, 100_000);
            body = invalid;
            assertEquals("AT_RESPONSE_INVALID", assertThrows(ResponseStatusException.class, client::observe).getReason());
        }
    }

    @Test
    void connectionMustBeExplicitAndCannotContainCredentialUrlsOrUnapprovedHttp() {
        client = new AtReadClient(new ObjectMapper(), "", "", "", "", false);
        assertFalse(client.connection().configured());
        assertEquals("AT_NOT_CONFIGURED", assertThrows(ResponseStatusException.class, client::observe).getReason());
        for (String origin : new String[]{"http://127.0.0.1:123", "https://user:secret@example.invalid",
                "https://example.invalid/?token=secret", "https://example.invalid/other", "file:///etc/passwd",
                "https://example.invalid:0", "https://example.invalid:99999"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> new AtReadClient(new ObjectMapper(), origin, "fixture", "declared", "secret", false));
            assertEquals("INVALID_AT_CONNECTION", error.getMessage());
        }
        var invalidToken = assertThrows(IllegalArgumentException.class,
                () -> new AtReadClient(new ObjectMapper(), "https://example.invalid", "fixture",
                        "declared", "invalid token \u4e2d", false));
        assertEquals("INVALID_AT_CONNECTION", invalidToken.getMessage());
    }

    @Test
    void pythonReadUsesOnlyObservedStaticSameOriginAndExactBytes() {
        for (String locator : new String[]{"/static/plugins/source.py", address() + "/static/plugins/source.py",
                "/static/plugins/[\u4e2d\u6587 \u6d4b\u8bd5].PY", "/static/plugins/source.txt",
                address() + "/static/plugins/source.TXT"}) {
            if (client != null) client.close();
            body = BODY.replace("https://fixture.invalid/source.py?token=LOCATOR_SECRET", locator);
            client = client(3000, 100_000);
            var observed = client.observe();
            int before = requests.get();
            assertArrayEquals(sourceBody.getBytes(StandardCharsets.UTF_8),
                    client.readPythonSource(observed.sha256(), 7));
            assertEquals(before + 1, requests.get());
            assertEquals("GET", method);
            assertEquals("SYNTHETIC_SESSION_SECRET", authorization);
            assertTrue(path.startsWith("/static/"));
            assertFalse(observed.toString().contains("SOURCE_MUST_NOT_EXECUTE"));
        }
    }

    @Test
    void externalPythonUsesAnonymousReaderAndPreservesObservedIdentity() {
        String locator = "https://github.com/owner/repo/raw/refs/heads/main/source.py";
        body = BODY.replace("https://fixture.invalid/source.py?token=LOCATOR_SECRET", locator);
        client = client(3000, 100_000);
        var reader = mock(PublicPythonSourceReader.class);
        ReflectionTestUtils.setField(client, "publicSources", reader);
        byte[] expected = sourceBody.getBytes(StandardCharsets.UTF_8);
        when(reader.read(URI.create(locator), 100_000)).thenReturn(expected);
        var observed = client.observe();
        int before = requests.get();
        assertArrayEquals(expected, client.readPythonSource(observed.sha256(), 7));
        assertEquals(before, requests.get(), "external source must not use the credential-bearing AT client");
        assertEquals("at_public_https_source_read", client.sourceReadAuthority(7));
        verify(reader).read(URI.create(locator), 100_000);
        assertFalse(observed.toString().contains(locator));
    }

    @Test
    void externalEncryptedSourceUsesTheSameAnonymousReader() {
        String locator = "https://example.com/owned.txt?revision=1";
        body = BODY.replace("https://fixture.invalid/source.py?token=LOCATOR_SECRET", locator);
        client = client(3000, 100_000);
        var reader = mock(PublicPythonSourceReader.class);
        ReflectionTestUtils.setField(client, "publicSources", reader);
        byte[] expected = "//@format:secspider/1\npayload.base64:opaque\n".getBytes(StandardCharsets.UTF_8);
        when(reader.read(URI.create(locator), 100_000)).thenReturn(expected);
        var observed = client.observe();
        assertEquals("secspider", observed.plugins().getFirst().kind());
        int before = requests.get();
        assertArrayEquals(expected, client.readPythonSource(observed.sha256(), 7));
        assertEquals(before, requests.get());
        verify(reader).read(URI.create(locator), 100_000);
    }

    @Test
    void disabledExternalPluginCannotReachAnonymousReader() {
        body = BODY.replace("https://fixture.invalid/source.py?token=LOCATOR_SECRET", "https://example.com/source.py")
                .replace("\"enabled\":true", "\"enabled\":false");
        client = client(3000, 100_000);
        var reader = mock(PublicPythonSourceReader.class);
        ReflectionTestUtils.setField(client, "publicSources", reader);
        var observed = client.observe();
        assertEquals("AT_SOURCE_NOT_READABLE", assertThrows(ResponseStatusException.class,
                () -> client.readPythonSource(observed.sha256(), 7)).getReason());
        verifyNoInteractions(reader);
    }

    @Test
    void nonStaticExternalEncodedTraversalAndDisabledSourcesNeverReceiveCredentials() {
        for (String locator : new String[]{"https://fixture.invalid/static/source.py",
                "//fixture.invalid/static/source.py", "/api/plugins/source.py",
                "/static/../private/source.py", "/static/%2e%2e/private/source.py",
                "/static/%252e%252e/private/source.py", "/static/a/./source.py",
                "/static/source.py?token=SECRET", "/static/source.py#SECRET",
                "/static/a\\source.py", "/static//source.py"}) {
            if (client != null) client.close();
            body = BODY.replace("https://fixture.invalid/source.py?token=LOCATOR_SECRET",
                    locator.replace("\\", "\\\\"));
            client = client(3000, 100_000);
            var observed = client.observe();
            int before = requests.get();
            assertEquals("AT_SOURCE_NOT_READABLE", assertThrows(ResponseStatusException.class,
                    () -> client.readPythonSource(observed.sha256(), 7)).getReason(), locator);
            assertEquals(before, requests.get());
        }
        client.close();
        body = BODY.replace("https://fixture.invalid/source.py?token=LOCATOR_SECRET", "/static/source.py")
                .replace("\"enabled\":true", "\"enabled\":false");
        client = client(3000, 100_000);
        var observed = client.observe();
        assertEquals("AT_SOURCE_NOT_READABLE", assertThrows(ResponseStatusException.class,
                () -> client.readPythonSource(observed.sha256(), 7)).getReason());
    }

    @Test
    void sourceReadsRejectRedirectHtmlOversizeAndStaleObservations() {
        body = BODY.replace("https://fixture.invalid/source.py?token=LOCATOR_SECRET", "/static/source.py");
        client = client(3000, 100_000);
        var observed = client.observe();
        int before = requests.get();
        status = 302;
        assertEquals("AT_REDIRECT_REFUSED", assertThrows(ResponseStatusException.class,
                () -> client.readPythonSource(observed.sha256(), 7)).getReason());
        assertEquals(before + 1, requests.get());
        status = 200; sourceType = "text/html";
        assertEquals("AT_SOURCE_RESPONSE_TYPE", assertThrows(ResponseStatusException.class,
                () -> client.readPythonSource(observed.sha256(), 7)).getReason());
        sourceType = "text/plain"; sourceBody = "a".repeat(100_001);
        assertEquals("AT_RESPONSE_TOO_LARGE", assertThrows(ResponseStatusException.class,
                () -> client.readPythonSource(observed.sha256(), 7)).getReason());
        before = requests.get();
        assertEquals("AT_OBSERVATION_CHANGED", assertThrows(ResponseStatusException.class,
                () -> client.readPythonSource("0".repeat(64), 7)).getReason());
        assertEquals(before, requests.get());
    }

    @Test
    void runtimeIdentityRequiresExplicitPathAndReturnsOnlyBoundedIdentity() {
        client = new AtReadClient(new ObjectMapper(), address(), "synthetic-at", "baseline-declared",
                "SYNTHETIC_SESSION_SECRET", true, "/api/plugins/{pluginId}/runtime-identity",
                3000, 100_000);
        var identity = client.observeRuntimeIdentity(7, "stable-key");
        assertEquals(7, identity.pluginId());
        assertEquals("stable-key", identity.siteKey());
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                identity.sourceSha256());
        assertEquals("LOADED", identity.loadState());
        assertEquals("unspecified", identity.sourceIdentityKind());
        assertTrue(identity.boundary().contains("sourceIdentityKind"));
        assertTrue(identity.loaded());
        assertEquals("/api/plugins/7/runtime-identity", path);
        assertEquals("SYNTHETIC_SESSION_SECRET", authorization);
        assertEquals(identity, client.observeRuntimeIdentity(7, "stable-key"));
        assertEquals("AT_RUNTIME_SITE_KEY_MISMATCH",
                assertThrows(ResponseStatusException.class,
                        () -> client.observeRuntimeIdentity(7, "other-key")).getReason());
        assertFalse(identity.toString().contains("SYNTHETIC_SESSION_SECRET"));
    }

    @Test
    void runtimeIdentityRejectsUnconfiguredPathAndInconsistentLoadState() {
        client = client(3000, 100_000);
        assertEquals("AT_RUNTIME_IDENTITY_NOT_CONFIGURED",
                assertThrows(ResponseStatusException.class,
                        () -> client.observeRuntimeIdentity(7, "stable-key")).getReason());
        client.close();
        client = new AtReadClient(new ObjectMapper(), address(), "synthetic-at", "baseline-declared",
                "SYNTHETIC_SESSION_SECRET", true, "/api/plugins/{pluginId}/runtime-identity",
                3000, 100_000);
        runtimeIdentityBody = """
                {"pluginId":7,"siteKey":"stable-key","sourceSha256":null,"runtimeRevision":"t4-direct-v1",
                 "loadState":"LOADED","loaded":false}
                """;
        assertEquals("AT_RUNTIME_IDENTITY_RESPONSE_INVALID",
                assertThrows(ResponseStatusException.class,
                        () -> client.observeRuntimeIdentity(7, "stable-key")).getReason());
    }

    @Test
    void runtimeIdentityRejectsNonIntegerAndOutOfRangePluginIds() {
        String validBody = runtimeIdentityBody;
        for (String id : new String[]{"7.5", "7.0", "7e0", "\"7\"", "null", "true",
                "{}", "[]", "2147483648", "-2147483649", "6"}) {
            if (client != null) client.close();
            client = new AtReadClient(new ObjectMapper(), address(), "synthetic-at", "baseline-declared",
                    "SYNTHETIC_SESSION_SECRET", true, "/api/plugins/{pluginId}/runtime-identity",
                    3000, 100_000);
            runtimeIdentityBody = validBody.replace("\"pluginId\":7", "\"pluginId\":" + id);
            assertEquals("AT_RUNTIME_IDENTITY_RESPONSE_INVALID",
                    assertThrows(ResponseStatusException.class,
                            () -> client.observeRuntimeIdentity(7, "stable-key"), id).getReason(), id);
        }
    }

    @Test
    void runtimeIdentityPathCannotCarryQueryOrTraversal() {
        for (String path : new String[]{"/api/plugins/{pluginId}/runtime-identity?token=secret",
                "/api/../plugins/{pluginId}/runtime-identity", "/api/plugins/runtime-identity"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> new AtReadClient(new ObjectMapper(), address(), "synthetic-at", "declared",
                            "secret", true, path, 3000, 100_000));
            assertEquals("INVALID_AT_CONNECTION", error.getMessage());
        }
    }

    @Test
    void runtimeIdentityKeepsCompilationTextDistinctFromSourceBytes() {
        String original = runtimeIdentityBody;
        for (String kind : new String[]{"utf8_compilation_text", "source_bytes"}) {
            if (client != null) client.close();
            client = runtimeClient(3000, 100_000);
            runtimeIdentityBody = original.replace("\"pluginId\":7",
                    "\"sourceIdentityKind\":\"" + kind + "\",\"pluginId\":7");
            var identity = client.observeRuntimeIdentity(7, "stable-key");
            assertEquals(kind, identity.sourceIdentityKind());
            assertEquals("a".repeat(64), identity.sourceSha256());
        }
    }

    @Test
    void runtimeIdentityRejectsInvalidKindAndUnobservedSha() {
        String original = runtimeIdentityBody;
        for (String value : new String[]{"\"unobserved\"", "\"package_bytes\"", "\"\"", "7", "true", "{}", "[]"}) {
            if (client != null) client.close();
            client = runtimeClient(3000, 100_000);
            runtimeIdentityBody = original.replace("\"pluginId\":7",
                    "\"sourceIdentityKind\":" + value + ",\"pluginId\":7");
            assertEquals("AT_RUNTIME_IDENTITY_RESPONSE_INVALID", assertThrows(ResponseStatusException.class,
                    () -> client.observeRuntimeIdentity(7, "stable-key")).getReason(), value);
        }
    }

    @Test
    void runtimeIdentityAcceptsExplicitUnobservedWithoutFabricatingSha() {
        client = runtimeClient(3000, 100_000);
        runtimeIdentityBody = """
                {"pluginId":7,"siteKey":"stable-key","sourceIdentityKind":"unobserved",
                 "runtimeRevision":"worker-loader-v1:unobserved","loadState":"NOT_LOADED","loaded":false}
                """;
        var identity = client.observeRuntimeIdentity(7, "stable-key");
        assertFalse(identity.loaded());
        assertNull(identity.sourceSha256());
        assertNull(identity.workerGeneration());
        assertEquals("unobserved", identity.sourceIdentityKind());
    }

    @Test
    void runtimeIdentityTransportRefusesRedirectsOversizedBodiesAndSlowReads() {
        client = runtimeClient(3000, 100_000);
        status = 302;
        assertEquals("AT_REDIRECT_REFUSED", assertThrows(ResponseStatusException.class,
                () -> client.observeRuntimeIdentity(7, "stable-key")).getReason());
        assertEquals(1, requests.get());
        assertEquals("AT_RUNTIME_IDENTITY_COOLDOWN", assertThrows(ResponseStatusException.class,
                () -> client.observeRuntimeIdentity(7, "stable-key")).getReason());
        assertEquals(1, requests.get());
        client.close(); client = runtimeClient(3000, 20);
        status = 200;
        assertEquals("AT_RESPONSE_TOO_LARGE", assertThrows(ResponseStatusException.class,
                () -> client.observeRuntimeIdentity(7, "stable-key")).getReason());
        client.close(); client = runtimeClient(200, 100_000);
        slow = true;
        assertEquals("AT_RUNTIME_IDENTITY_TIMEOUT", assertThrows(ResponseStatusException.class,
                () -> client.observeRuntimeIdentity(7, "stable-key")).getReason());
    }

    private String address() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private AtReadClient apiKeyClient(String session, String key, String mode) {
        return new AtReadClient(new ObjectMapper(), address(), "synthetic-at", "baseline-declared",
                session, true, "/api/plugins/{pluginId}/runtime-identity", 3000, 100_000, mode, key);
    }
    private AtReadClient runtimeClient(int timeout, int limit) {
        return new AtReadClient(new ObjectMapper(), address(), "synthetic-at", "baseline-declared",
                "SYNTHETIC_SESSION_SECRET", true, "/api/plugins/{pluginId}/runtime-identity", timeout, limit);
    }
    private AtReadClient client(int timeout, int limit) {
        return new AtReadClient(new ObjectMapper(), address(), "synthetic-at", "baseline-declared",
                "SYNTHETIC_SESSION_SECRET", true, timeout, limit);
    }
}
