package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.config.ModelSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:connection-settings;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=admin", "maintenance.at.origin=", "maintenance.at.auth-mode=session",
        "maintenance.at.api-key=", "maintenance.at.session-token=", "maintenance.at.instance-id=",
        "maintenance.model.enabled=false", "maintenance.scheduling.enabled=false"
})
class ConnectionSettingsIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path directory = temporary();
    @LocalServerPort int port;
    @Autowired Environment environment;
    @Autowired ModelSettings defaults;
    @Autowired AtReadClient at;
    @Autowired ModelClient model;
    final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    String csrf;

    static Path temporary() {
        try { return Files.createTempDirectory("maintenance-connections-"); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("maintenance.connections-file", () -> directory.resolve("connections.json").toString());
    }

    @Test
    void administratorCanConfigureReadOnlyAtAndRelayLiveWithoutExposingSecrets() throws Exception {
        var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/settings/connections"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode());
        assertEquals(200, send("GET", "/sources", null, false).statusCode()); // default admin/admin
        JsonNode token = JSON.readTree(send("GET", "/csrf", null, false).body());
        csrf = token.path("token").asText();
        var atInput = JSON.createObjectNode().put("origin", "").put("instanceId", "fixture-at")
                .put("revision", "test").put("authMode", "api_key").put("credential", "at-private")
                .put("clearCredential", false).put("allowHttp", true);
        assertEquals(403, send("PUT", "/settings/connections/at", atInput, false).statusCode());
        var receivedAuth = new AtomicReference<String>();
        var receivedMethod = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedAuth.set(exchange.getRequestHeaders().getFirst(
                    exchange.getRequestURI().getPath().equals("/api/plugins") ? "X-API-KEY" : "Authorization"));
            byte[] body = (exchange.getRequestURI().getPath().equals("/api/plugins")
                    ? "[{\"id\":7,\"name\":\"fixture\",\"url\":\"/static/source.py\",\"enabled\":true,\"t4Visible\":true}]"
                    : "{\"choices\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            atInput.put("origin", origin);
            var savedAt = send("PUT", "/settings/connections/at", atInput, true);
            assertEquals(200, savedAt.statusCode(), savedAt.body());
            assertFalse(savedAt.body().contains("at-private"));
            assertTrue(at.connection().configured());
            assertEquals(200, send("POST", "/at/observations", JSON.createObjectNode(), true).statusCode());
            assertEquals("at-private", receivedAuth.get());
            assertEquals("GET", receivedMethod.get());
            String previousObservation = at.observe().sha256();
            atInput.put("credential", "");
            assertEquals(200, send("PUT", "/settings/connections/at", atInput, true).statusCode());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> at.requireObservation(previousObservation));
            at.observe();
            assertEquals("at-private", receivedAuth.get()); // blank preserves a saved key
            var input = JSON.createObjectNode().put("enabled", true).put("endpoint", "")
                    .put("baseUrl", origin + "/relay/v1").put("model", "relay-model")
                    .put("apiKey", "model-private").put("clearApiKey", false).put("keyEnv", "")
                    .put("timeoutMillis", 8000).put("maxCompletionTokens", 2048)
                    .put("maxTokenParameter", "max_tokens").put("jsonMode", "none")
                    .put("includeStoreParameter", false);
            var savedModel = send("PUT", "/settings/connections/model", input, true);
            assertEquals(200, savedModel.statusCode(), savedModel.body());
            assertFalse(savedModel.body().contains("model-private"));
            assertTrue(model.connection().enabled());
            assertEquals("MODEL_RESPONSE_CHOICES_INVALID", model.generate("{}").errorCode());
            assertEquals("Bearer model-private", receivedAuth.get());
            var reopened = new ConnectionSettingsStore(JSON, environment, directory.resolve("connections.json").toString());
            assertEquals("at-private", reopened.at().credential());
            assertEquals("model-private", reopened.model(defaults).getApiKey());
            assertEquals("relay-model", reopened.model(defaults).getModel());
            var limits = (ObjectNode) JSON.readTree(send("GET", "/settings/connections", null, false).body()).path("limits");
            limits.put("dailyCallLimit", 12).put("sourceDailyCallLimit", 0).put("modelDailyCallLimit", 8);
            assertEquals(403, send("PUT", "/settings/connections/model-limits", limits, false).statusCode());
            String ledger = send("GET", "/model/budget", null, false).body();
            assertEquals(200, send("PUT", "/settings/connections/model-limits", limits, true).statusCode());
            assertEquals(12, model.connection().dailyCallLimit());
            assertEquals(8, model.connection().modelDailyCallLimit());
            var afterLimits = new ConnectionSettingsStore(JSON, environment, directory.resolve("connections.json").toString());
            assertEquals(12, afterLimits.model(defaults).getDailyCallLimit());
            assertEquals(0, afterLimits.model(defaults).getSourceDailyCallLimit());
            assertEquals("model-private", afterLimits.model(defaults).getApiKey());
            assertEquals(ledger, send("GET", "/model/budget", null, false).body());
            limits.put("dailyCallLimit", -1);
            assertEquals(400, send("PUT", "/settings/connections/model-limits", limits, true).statusCode());
            assertEquals(12, model.connection().dailyCallLimit());
            assertFalse(send("GET", "/settings/connections", null, false).body().contains("model-private"));
            String previous = model.connection().configurationSha256();
            input.put("baseUrl", "https://user:secret@example.invalid/v1");
            assertEquals(400, send("PUT", "/settings/connections/model", input, true).statusCode());
            assertEquals(previous, model.connection().configurationSha256());
        } finally { server.stop(0); }
    }

    URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/plugin-maintenance" + path); }

    HttpResponse<String> send(String method, String path, ObjectNode body, boolean withCsrf) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8)));
        if (withCsrf) builder.header("X-CSRF-TOKEN", csrf);
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
