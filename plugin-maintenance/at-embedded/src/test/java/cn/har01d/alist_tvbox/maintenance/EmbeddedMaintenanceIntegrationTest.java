package cn.har01d.alist_tvbox.maintenance;

import cn.har01d.alist_tvbox.auth.SessionTokenService;
import cn.har01d.alist_tvbox.auth.TokenFilter;
import cn.har01d.alist_tvbox.config.EmbeddedMaintenanceConfiguration;
import cn.har01d.alist_tvbox.config.WebSecurityConfiguration;
import cn.har01d.alist_tvbox.entity.SessionRepository;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.service.PluginWorkerService;
import cn.har01d.alist_tvbox.service.PluginMutationGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedMaintenanceIntegrationTest {
    @TempDir Path directory;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({EmbeddedMaintenanceConfiguration.class, WebSecurityConfiguration.class,
            TokenFilter.class, SessionTokenService.class, PluginWorkerService.class, PluginMutationGuard.class, HostEndpoint.class})
    static class Harness {}

    @RestController
    static class HostEndpoint {
        @GetMapping("/api/embedded-harness")
        Map<String, Boolean> get() { return Map.of("host", true); }
    }

    @Test
    void nativeRuntimeEndpointUsesAdminAndSavedLoaderSnapshot() throws Exception {
        var mapper = new ObjectMapper();
        try (var context = open()) {
            var client = HttpClient.newHttpClient();
            var tokens = context.getBean(SessionTokenService.class);
            String admin = tokens.encodeToken(1, "fixture-admin", "ADMIN", "127.0.0.1", "test");
            String user = tokens.encodeToken(2, "fixture-user", "USER", "127.0.0.1", "test");
            context.getBean(TokenFilter.class).setApiKey("fixture-client");
            var plugins = context.getBean(PluginRepository.class);
            var plugin = new Plugin();
            plugin.setName("Native identity fixture");
            plugin.setExternalId("native-identity-fixture");
            plugin.setSortOrder(0);
            plugin.setVersion(1);
            plugin.setUrl("private fixture locator");
            plugin.setContent("not the loaded source");
            plugin = plugins.saveAndFlush(plugin);
            String key = "native-identity-fixture";
            String url = origin(context) + "/api/plugin-maintenance/at-native/plugins/"
                    + plugin.getId() + "/runtime-identity";
            assertEquals(401, get(client, url, null).statusCode());
            assertEquals(403, get(client, url, user).statusCode());
            var denied = client.send(HttpRequest.newBuilder(URI.create(url))
                    .header("X-API-KEY", "fixture-client").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(403, denied.statusCode());
            var unloaded = get(client, url, admin);
            assertEquals(200, unloaded.statusCode());
            assertEquals("no-store", unloaded.headers().firstValue("cache-control").orElse(""));
            assertEquals("NOT_LOADED", mapper.readTree(unloaded.body()).path("loadState").asText());
            var workers = context.getBean(PluginWorkerService.class);
            org.springframework.test.util.ReflectionTestUtils.setField(workers, "cacheDir", directory);
            assertTrue(workers.isAvailable(), "Set T4_PYTHON to the prepared project Python");
            Path source = directory.resolve("trusted-runtime.py");
            String body = "class Spider:\n    def init(self, extend):\n        return ''\n"
                    + "    def getName(self):\n        return 'fixture'\n"
                    + "    def homeContent(self, filter):\n        return {'fixture': True}\n";
            Files.writeString(source, body);
            Path goodFilter = directory.resolve("trusted-filter.py");
            Path failedFilter = directory.resolve("failed-filter.py");
            String filterBody = "class Filter:\n    def detail(self, result):\n        return result\n";
            Files.writeString(goodFilter, filterBody);
            Files.writeString(failedFilter,
                    "class Filter:\n    def init(self, data):\n        raise ValueError('fixture init')\n");
            String extend = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(Map.of(
                    "source", source.toString(), "raw", true, "data", "private-fixture-configuration",
                    "filters", List.of(
                            Map.of("source", goodFilter.toString(), "data", "PRIVATE_FILTER_CONFIG"),
                            Map.of("source", failedFilter.toString()),
                            Map.of("source", goodFilter.toString(), "stages", "play")))));
            workers.call(key, extend, "home", Map.of());
            var response = get(client, url, admin);
            assertEquals(200, response.statusCode());
            JsonNode loaded = mapper.readTree(response.body());
            assertEquals(key, loaded.path("siteKey").asText());
            assertEquals("LOADED", loaded.path("loadState").asText());
            assertTrue(loaded.path("loaded").asBoolean());
            assertEquals(cn.har01d.atmaintenance.service.Hashes.sha256(body), loaded.path("sourceSha256").asText());
            assertEquals("utf8_compilation_text", loaded.path("sourceIdentityKind").asText());
            assertTrue(loaded.path("workerGeneration").asText().matches("[a-f0-9]{32}"));
            String wire = new String(response.body(), StandardCharsets.UTF_8);
            assertFalse(wire.contains(source.toString()));
            assertFalse(wire.contains("private-fixture"));
            assertFalse(wire.contains("not the loaded source"));
            assertFalse(loaded.has("identity"));
            assertFalse(loaded.has("extendSha256"));
            assertFalse(loaded.has("packageTextSha256"));
            assertFalse(loaded.has("filterIdentity"));
            JsonNode filterIdentity = loaded.path("protectedFilterIdentity");
            assertEquals("hmac_sha256_loader_configuration_v1", filterIdentity.path("fingerprintScheme").asText());
            assertTrue(filterIdentity.path("configurationValid").asBoolean());
            assertFalse(filterIdentity.path("allConfiguredFiltersLoaded").asBoolean());
            assertEquals(3, filterIdentity.path("filters").size());
            assertEquals("LOADED", filterIdentity.path("filters").get(0).path("loadState").asText());
            assertEquals(cn.har01d.atmaintenance.service.Hashes.sha256(filterBody),
                    filterIdentity.path("filters").get(0).path("sourceSha256").asText());
            assertEquals("NOT_LOADED", filterIdentity.path("filters").get(1).path("loadState").asText());
            assertTrue(filterIdentity.path("filters").get(1).path("sourceSha256").isNull());
            assertEquals("LOADED", filterIdentity.path("filters").get(2).path("loadState").asText());
            assertEquals(cn.har01d.atmaintenance.service.Hashes.sha256(filterBody),
                    filterIdentity.path("filters").get(2).path("sourceSha256").asText());
            assertNotEquals(filterIdentity.path("filters").get(0).path("configurationFingerprint"),
                    filterIdentity.path("filters").get(2).path("configurationFingerprint"));
            assertFalse(wire.contains("PRIVATE_FILTER_CONFIG"));
            assertFalse(wire.contains("configurationSha256"));
            assertFalse(wire.contains(goodFilter.toString()));
            var reader = new cn.har01d.atmaintenance.service.AtReadClient(mapper,
                    origin(context), "embedded-fixture", "declared", admin, true,
                    "/api/plugin-maintenance/at-native/plugins/{pluginId}/runtime-identity", "session", "");
            try {
                var observed = reader.observeRuntimeIdentity(plugin.getId(), key);
                assertEquals(filterIdentity, mapper.valueToTree(observed.protectedFilterIdentity()));
            } finally {
                reader.close();
            }
            assertEquals(filterIdentity, mapper.readTree(get(client, url, admin).body()).path("protectedFilterIdentity"));
            String changedFilter = filterBody + "\n# actual new compilation text\n";
            Files.writeString(goodFilter, changedFilter);
            assertEquals(filterIdentity, mapper.readTree(get(client, url, admin).body()).path("protectedFilterIdentity"));
            plugin.setContent("new database contents, still not loaded");
            plugins.saveAndFlush(plugin);
            assertEquals(loaded.path("sourceSha256"), mapper.readTree(get(client, url, admin).body()).path("sourceSha256"));
            assertEquals(loaded.path("workerGeneration"), mapper.readTree(get(client, url, admin).body()).path("workerGeneration"));
            workers.shutdown();
            workers.call(key, extend, "home", Map.of());
            JsonNode reloaded = mapper.readTree(get(client, url, admin).body());
            assertNotEquals(loaded.path("workerGeneration"), reloaded.path("workerGeneration"));
            assertEquals(cn.har01d.atmaintenance.service.Hashes.sha256(changedFilter),
                    reloaded.path("protectedFilterIdentity").path("filters").get(0).path("sourceSha256").asText());
            assertEquals(filterIdentity.path("configurationFingerprint"),
                    reloaded.path("protectedFilterIdentity").path("configurationFingerprint"));
            assertEquals(404, get(client, url.replace("/" + plugin.getId() + "/", "/2147483647/"), admin).statusCode());
        }
    }

    @Test
    void embeddedWorkflowUsesAtDatabasePermissionsAndIndependentWeb() throws Exception {
        String id;
        String admin;
        String source = "raise RuntimeError('must not execute on host')\nclass Spider: pass\n";
        ObjectMapper mapper = new ObjectMapper();
        try (var context = open()) {
            String origin = origin(context);
            var client = HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .connectTimeout(Duration.ofSeconds(5)).build();
            var tokens = context.getBean(SessionTokenService.class);
            admin = tokens.encodeToken(1, "embedded-admin", "ADMIN", "127.0.0.1", "test");
            String user = tokens.encodeToken(2, "embedded-user", "USER", "127.0.0.1", "test");
            var filter = context.getBean(TokenFilter.class);
            filter.setApiKey("embedded-test-client");
            String path = "/api/plugin-maintenance";

            assertEquals(401, get(client, origin + path + "/sources", null).statusCode());
            assertEquals(403, get(client, origin + path + "/sources", user).statusCode());
            var clientResponse = client.send(HttpRequest.newBuilder(URI.create(origin + path + "/sources"))
                    .header("X-API-KEY", "embedded-test-client").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(403, clientResponse.statusCode());
            assertEquals(200, get(client, origin + "/api/embedded-harness", admin).statusCode());
            assertEquals(200, get(client, origin + path + "/sources", admin).statusCode());
            var connectionSettings = mapper.readTree(get(client, origin + path + "/settings/connections", admin).body());
            assertTrue(connectionSettings.path("at").path("embeddedAvailable").asBoolean());
            assertTrue(connectionSettings.path("at").path("useEmbedded").asBoolean());
            assertTrue(connectionSettings.path("at").path("configured").asBoolean());
            var hostPlugin = new Plugin();
            hostPlugin.setName("Host source snapshot");
            hostPlugin.setUrl("https://fixture.invalid/never-fetch.py");
            hostPlugin.setContent(source);
            hostPlugin.setVersion(1);
            hostPlugin.setSortOrder(0);
            hostPlugin = context.getBean(PluginRepository.class).saveAndFlush(hostPlugin);
            var hostReader = context.getBean(cn.har01d.atmaintenance.service.AtReadClient.class);
            var observation = hostReader.observe();
            assertEquals("at_embedded_repository", observation.authority());
            assertArrayEquals(source.getBytes(StandardCharsets.UTF_8),
                    hostReader.readPythonSource(observation.sha256(), hostPlugin.getId()));
            assertEquals(200, get(client, origin + path + "/sources/overview", admin).statusCode());
            assertEquals(401, get(client, origin + path + "/sources?X-ACCESS-TOKEN=" + admin, null).statusCode());

            var page = get(client, origin + "/plugin-maintenance/", null);
            assertEquals(200, page.statusCode());
            assertEquals("SAMEORIGIN", page.headers().firstValue("x-frame-options").orElse(""));
            assertEquals(mapper.createArrayNode(), mapper.readTree(get(client, origin + path + "/automation/tasks", admin).body()));
            String html = new String(page.body(), StandardCharsets.UTF_8);
            var assets = Pattern.compile("(?:src|href)=\"(\\./assets/[^\"]+)\"").matcher(html);
            int found = 0;
            while (assets.find()) {
                found++;
                assertEquals(200, get(client, origin + "/plugin-maintenance/" + assets.group(1).substring(2), null)
                        .statusCode());
            }
            assertTrue(found >= 2, "Built JS and CSS must be served below the independent page");
            assertFalse(html.contains("src=\"/assets/"));

            String body = mapper.writeValueAsString(Map.of("name", "Embedded MVP", "filename", "source.py",
                    "content", source));
            var noCsrf = client.send(HttpRequest.newBuilder(URI.create(origin + path + "/sources"))
                    .header("Authorization", admin).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(403, noCsrf.statusCode());
            JsonNode csrf = mapper.readTree(get(client, origin + path + "/csrf", admin).body());
            var created = client.send(HttpRequest.newBuilder(URI.create(origin + path + "/sources"))
                    .header("Authorization", admin).header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(201, created.statusCode(), new String(created.body(), StandardCharsets.UTF_8));
            JsonNode record = mapper.readTree(created.body());
            id = record.path("id").asText();
            String sha = record.path("artifactSha256").asText();
            assertEquals(source, new String(get(client, origin + path + "/sources/" + id + "/artifacts/" + sha, admin)
                    .body(), StandardCharsets.UTF_8));
            var exported = get(client, origin + path + "/sources/" + id + "/export?sha=" + sha, admin);
            assertEquals(200, exported.statusCode());
            assertEquals("PK", new String(exported.body(), 0, 2, StandardCharsets.US_ASCII));
            var jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(1, jdbc.queryForObject("select count(*) from maintenance_source", Integer.class));
            assertEquals(2, jdbc.queryForObject("select count(*) from session", Integer.class));
            assertEquals("74", context.getBean(Flyway.class).info().current().getVersion().getVersion());
            assertEquals("YES", jdbc.queryForObject("select is_nullable from information_schema.columns "
                    + "where table_name = 'MAINTENANCE_AT_PUBLICATION' and column_name = 'ORIGINAL_PLUGIN_ID'", String.class));
            assertFalse(context.containsBean("securityFilterChain"), "Standalone authentication must not be loaded");
            assertEquals(1, context.getBeansOfType(javax.sql.DataSource.class).size());
        }
        try (var context = open()) {
            var client = HttpClient.newHttpClient();
            String url = origin(context) + "/api/plugin-maintenance/sources/" + id;
            assertEquals(200, get(client, url, admin).statusCode());
            var sessions = context.getBean(SessionRepository.class);
            sessions.delete(sessions.findByToken(admin).orElseThrow());
            assertEquals(401, get(client, url, admin).statusCode());
            context.getBean(Flyway.class).validate();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "maintenance.test.import-archive", matches = ".+")
    void realCandidateArchiveEntersEmbeddedDatabaseAsDataThroughAdminHttp() throws Exception {
        byte[] archive = Files.readAllBytes(Path.of(System.getProperty("maintenance.test.import-archive")));
        var mapper = new ObjectMapper();
        try (var context = open()) {
            var client = HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            var tokens = context.getBean(SessionTokenService.class);
            String admin = tokens.encodeToken(1, "transfer-admin", "ADMIN", "127.0.0.1", "test");
            String user = tokens.encodeToken(2, "transfer-user", "USER", "127.0.0.1", "test");
            String root = origin(context) + "/api/plugin-maintenance";
            String body = mapper.writeValueAsString(Map.of("archiveBase64", Base64.getEncoder().encodeToString(archive),
                    "expectedArchiveSha256", cn.har01d.atmaintenance.service.Hashes.sha256(archive),
                    "requestKey", UUID.randomUUID().toString(), "name", "Real candidate transfer",
                    "filename", "source.py"));
            var rejected = client.send(HttpRequest.newBuilder(URI.create(root + "/source-imports"))
                    .header("Authorization", admin).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, rejected.statusCode());
            JsonNode csrf = mapper.readTree(get(client, root + "/csrf", admin).body());
            var response = client.send(HttpRequest.newBuilder(URI.create(root + "/source-imports"))
                    .header("Authorization", admin).header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
            JsonNode saved = mapper.readTree(response.body());
            String sourceId = saved.path("sourceId").asText();
            String imported = root + "/sources/" + sourceId + "/imported-candidates";
            assertEquals(401, get(client, imported, null).statusCode());
            assertEquals(403, get(client, imported, user).statusCode());
            var candidateResponse = get(client, imported, admin);
            assertEquals(200, candidateResponse.statusCode());
            var candidate = mapper.readTree(candidateResponse.body()).get(0);
            assertEquals("archived_candidate_data_only", candidate.path("authority").asText());
            assertEquals(System.getProperty("maintenance.test.candidate-sha"), candidate.path("candidateSha256").asText());
            assertEquals("d11c0ce3-7ff4-42d6-8556-780668fa7da1", candidate.path("originalModelCallId").asText());
            assertArrayEquals(archive, get(client,
                    root + "/source-imports/" + saved.path("id").asText() + "/archive", admin).body());
            var jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(1, jdbc.queryForObject("select count(*) from maintenance_source_import", Integer.class));
            for (String table : java.util.List.of("maintenance_probe_run", "maintenance_repair_candidate",
                    "maintenance_model_call", "maintenance_at_binding", "maintenance_probe_schedule", "plugin"))
                assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class), table);
            assertEquals("73", context.getBean(Flyway.class).info().current().getVersion().getVersion());
            assertEquals(1, context.getBeansOfType(javax.sql.DataSource.class).size());
            assertFalse(context.containsBean("securityFilterChain"));
        }
        try (var context = open()) {
            assertEquals(1, context.getBean(JdbcTemplate.class).queryForObject(
                    "select count(*) from maintenance_source_import", Integer.class));
        }
    }

    private ConfigurableApplicationContext open() {
        return new SpringApplicationBuilder(Harness.class).run(
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("embedded").toString().replace('\\', '/'),
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.sql.init.mode=never",
                "--spring.http.converters.preferred-json-mapper=jackson2",
                "--maintenance.connections-file=" + directory.resolve("connections.json"),
                "--maintenance.scheduling.enabled=false", "--maintenance.model.enabled=false",
                "--maintenance.at.origin=", "--maintenance.media.enabled=false",
                "--maintenance.container.enabled=false", "--maintenance.managed-source.enabled=false");
    }

    private static String origin(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private static HttpResponse<byte[]> get(HttpClient client, String url, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
        if (token != null) request.header("Authorization", token);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
