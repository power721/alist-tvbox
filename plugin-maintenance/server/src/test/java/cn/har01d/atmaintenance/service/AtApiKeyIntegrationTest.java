package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.BindAtRequest;
import cn.har01d.atmaintenance.dto.CreateSourceRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "maintenance.scheduling.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:maintenance-api-key-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only",
        "maintenance.at.auth-mode=api_key",
        "maintenance.at.api-key=SYNTHETIC_API_KEY_SECRET",
        "maintenance.at.session-token=",
        "maintenance.at.runtime-identity-path=/api/plugins/{pluginId}/runtime-identity"
})
class AtApiKeyIntegrationTest {
    static final HttpServer SERVER;
    static final AtomicInteger REQUESTS = new AtomicInteger();
    static volatile String authorization;
    static volatile String apiKey;
    static volatile String path;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/", exchange -> {
                REQUESTS.incrementAndGet();
                authorization = exchange.getRequestHeaders().getFirst("Authorization");
                apiKey = exchange.getRequestHeaders().getFirst("X-API-KEY");
                path = exchange.getRequestURI().getPath();
                byte[] body = AtReadClientTest.BODY.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            SERVER.start();
        } catch (java.io.IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("maintenance.at.origin", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("maintenance.at.instance-id", () -> "synthetic-api-key-at");
        registry.add("maintenance.at.allow-http", () -> true);
    }

    @AfterAll static void stop() { SERVER.stop(0); }
    @Autowired AtReadClient client;
    @Autowired AtBindingService bindings;
    @Autowired SourceService sources;

    @Test
    void configuredApiKeyCanBindMetadataButCannotUpgradeItToAdminRuntimeAuthority() {
        var observed = client.observe();
        assertEquals("SYNTHETIC_API_KEY_SECRET", apiKey);
        assertNull(authorization);
        assertEquals("/api/plugins", path);
        assertEquals("metadata_observed", observed.authority());
        String source = sources.create(new CreateSourceRequest("API key fixture", null, "fixture.py",
                null, "class Spider: pass\n")).id();
        var bound = bindings.bind(source, new BindAtRequest(null, observed.sha256(), 7));
        assertEquals("metadata_observed", bound.snapshot().path("authority").asText());
        int before = REQUESTS.get();
        assertEquals("AT_RUNTIME_IDENTITY_REQUIRES_SESSION", assertThrows(ResponseStatusException.class,
                () -> bindings.observeRuntimeIdentity(source, bound.sha256())).getReason());
        assertEquals(before, REQUESTS.get());
        assertEquals(bound, bindings.get(source), "denied runtime read must not change the binding");
        assertFalse(bound.toString().contains("SYNTHETIC_API_KEY_SECRET"));
    }
}
