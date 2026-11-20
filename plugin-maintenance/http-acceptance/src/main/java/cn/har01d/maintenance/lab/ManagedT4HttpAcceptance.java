package cn.har01d.maintenance.lab;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.config.WebSecurityConfiguration;
import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.entity.*;
import cn.har01d.alist_tvbox.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.DispatcherType;
import org.apache.catalina.LifecycleState;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Fixed AT HTTP owners in an isolated driver; submitted source is stored, never imported here. */
public final class ManagedT4HttpAcceptance {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final String SITE = "managed-http";
    private final String apiKey = UUID.randomUUID().toString();
    private final String vodToken = UUID.randomUUID().toString().replace("-", "");
    private AnnotationConfigApplicationContext persistence;
    private AnnotationConfigWebApplicationContext web;
    private WebServer server;
    private ManagedT4BrokerClient broker;
    private ManagedT4WorkerService worker;
    private ManagedT4HttpObservation observation;
    private JsonNode result;
    private HttpClient http;
    private String origin, t4Token;
    private JsonNode publicData = JSON.createObjectNode();
    private JsonNode frozenProfile;
    private int actions;
    private boolean cleaned;

    private static void check(boolean value, String code) {
        if (!value) throw new IllegalStateException(code);
    }

    void start(JsonNode plan, Path work, Path endpoint) throws Exception {
        publicData = approvedSourceConfig(plan);
        frozenProfile = JSON.readTree(plan.path("loaderProfileJson").asText());
        Files.createDirectories(work.resolve("tmp"));
        System.setProperty("lab.workDirectory", work.toString());
        System.setProperty("atv.data.dir", Files.createDirectory(work.resolve("data")).toString());
        byte[] source = Base64.getDecoder().decode(plan.path("sourceBase64").asText());
        check(source.length > 0 && source.length <= 1_048_576, "HTTP_SOURCE_LIMIT");
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source)).toString();
        broker = new ManagedT4BrokerClient(JSON, endpoint,
                plan.path("instanceId").asText(), plan.path("sourceImageId").asText(),
                plan.path("bundleSha256").asText(), plan.path("runtimeSha256").asText(),
                plan.path("executionPolicySha256").asText(), plan.path("egressPolicySha256").asText(),
                source, Duration.ofSeconds(100));
        check(broker.sourceSha256().equals(plan.path("sourceSha256").asText()), "HTTP_SOURCE_IDENTITY_MISMATCH");
        broker.verify();
        persistence = new AnnotationConfigApplicationContext();
        persistence.registerBean(HttpAcceptanceConfiguration.Database.class,
                () -> new HttpAcceptanceConfiguration.Database("jdbc:h2:file:" + work.resolve("atv")
                        + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"));
        persistence.register(HttpAcceptanceConfiguration.Persistence.class);
        persistence.refresh();
        var admin = new User();
        admin.setUsername("isolated-http-admin");
        admin.setPassword(new BCryptPasswordEncoder().encode(UUID.randomUUID().toString()));
        admin.setRole(Role.ADMIN);
        admin.setVodSecret(UserService.generateVodSecret());
        check(persistence.getBean(UserRepository.class).saveAndFlush(admin).getId() == 1,
                "HTTP_DATABASE_ID_INVALID");
        persistence.getBean(SettingRepository.class).saveAll(
                List.of(new Setting("token", vodToken), new Setting("api_key", apiKey)));
        var subscription = new Subscription();
        subscription.setSid("managed-http");
        subscription.setName("Managed HTTP");
        persistence.getBean(SubscriptionRepository.class).saveAndFlush(subscription);
        Plugin plugin = new Plugin();
        plugin.setUrl("/static/plugins/managed-http.py");
        plugin.setContent(text);
        plugin.setExternalId(SITE);
        plugin.setName("Managed HTTP");
        plugin.setSortOrder(0);
        plugin.setVersion(1);
        plugin.setExtend(publicData.isEmpty() ? "" : JSON.writeValueAsString(publicData));
        plugin = persistence.getBean(PluginRepository.class).saveAndFlush(plugin);
        Integer pluginId = plugin.getId();
        web = new AnnotationConfigWebApplicationContext();
        web.setParent(persistence);
        web.register(HttpAcceptanceConfiguration.Mvc.class, WebSecurityConfiguration.class);
        web.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("managedT4Broker", broker);
            factory.registerSingleton("managedPluginId", pluginId);
            factory.registerSingleton("managedApprovedPublicData", publicData);
        });
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getByName("127.0.0.1"));
        factory.setBaseDirectory(Files.createDirectory(work.resolve("tomcat")).toFile());
        server = factory.getWebServer(context -> {
            web.setServletContext(context);
            var utf8 = context.addFilter("utf8", new CharacterEncodingFilter("UTF-8", true));
            utf8.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            var security = context.addFilter("security", new DelegatingFilterProxy("springSecurityFilterChain", web));
            security.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            var servlet = context.addServlet("managed-http", new DispatcherServlet(web));
            servlet.setLoadOnStartup(1);
            servlet.addMapping("/");
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getPort();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var properties = web.getBean(AppProperties.class);
        properties.setT4Enabled(true);
        properties.setT4StandaloneOnly(true);
        properties.setT4DetailEnhance(false);
        worker = web.getBean(ManagedT4WorkerService.class);
        check(worker.isAvailable(), "HTTP_WORKER_UNAVAILABLE");
        var subscriptions = web.getBean(SubscriptionService.class);
        t4Token = subscriptions.getT4Token();
        check(vodToken.equals(subscriptions.resolveT4Token(t4Token)), "HTTP_TOKEN_MISMATCH");
        observation = worker.openHttpObservation(plan.path("parentJobId").asText(),
                plan.path("loaderProfileJson").asText(), true);
    }

    private HttpResponse<byte[]> request(String shape, Map<String, Object> values, boolean authority) throws Exception {
        String query = values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");
        String path = origin + "/t4/" + t4Token + "/v/" + SITE;
        var builder = HttpRequest.newBuilder().timeout(Duration.ofSeconds(110));
        if (shape.equals("get")) builder.uri(URI.create(path + (query.isEmpty() ? "" : "?" + query))).GET();
        else if (shape.equals("form")) builder.uri(URI.create(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(query, StandardCharsets.UTF_8));
        else builder.uri(URI.create(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(values)));
        if (authority) builder.header("X-API-KEY", apiKey);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode call(String shape, String mode, String method,
                          Map<String, Object> values, Map<String, Object> parameters) throws Exception {
        var expected = JSON.createObjectNode().put("method", method).put("playType", mode);
        expected.set("params", JSON.valueToTree(parameters));
        if (!publicData.isEmpty()) expected.set("sourceData", publicData.deepCopy());
        observation.begin(shape, expected);
        var response = request(shape, values, !shape.equals("get"));
        observation.complete(response.statusCode(), response.body());
        JsonNode value = JSON.readTree(response.body());
        check(value.isObject() && !value.has("transportFixtureOnly"), "HTTP_RESULT_INVALID");
        actions++;
        return value;
    }

    static JsonNode approvedSourceConfig(JsonNode plan) throws Exception {
        JsonNode profile = JSON.readTree(plan.path("loaderProfileJson").asText());
        check(profile != null && profile.isObject(), "HTTP_SOURCE_CONFIG_INVALID");
        if (!profile.has("sourceConfig")) return JSON.createObjectNode();
        JsonNode data = profile.path("sourceConfig");
        check(data.isObject() && data.size() <= 32, "HTTP_SOURCE_CONFIG_INVALID");
        if (!data.isEmpty()) ManagedT4BrokerClient.validateSourceData(data);
        return data.deepCopy();
    }

    static JsonNode cards(JsonNode value) {
        JsonNode rows = value.path("list");
        check(rows.isArray() && !rows.isEmpty(), "HTTP_CARDS_EMPTY");
        Set<String> ids = new HashSet<>();
        for (JsonNode row : rows)
            check(row.path("vod_id").isTextual() && !row.path("vod_id").asText().isBlank()
                    && row.path("vod_name").isTextual() && !row.path("vod_name").asText().isBlank()
                    && ids.add(row.path("vod_id").textValue()),
                    "HTTP_CARD_CONTRACT_FAILED");
        return rows;
    }

    static List<String> playbackEpisode(JsonNode detail, JsonNode profile) {
        JsonNode selected = profile.get("httpPlaybackGroup");
        check(selected == null || selected.isInt() && selected.intValue() >= 0
                && selected.intValue() <= 31, "HTTP_PLAYBACK_GROUP_INVALID");
        int index = selected == null ? 0 : selected.intValue();
        String[] lines = detail.path("vod_play_from").asText().split("\\$\\$\\$", -1);
        String[] groups = detail.path("vod_play_url").asText().split("\\$\\$\\$", -1);
        check(index < lines.length && index < groups.length, "HTTP_PLAYBACK_GROUP_ABSENT");
        String episode = groups[index].split("#", 2)[0];
        int separator = episode.indexOf('$');
        check(!lines[index].isBlank() && separator > 0 && separator < episode.length() - 1,
                "HTTP_DETAIL_EPISODE_MISSING");
        return List.of(lines[index], episode.substring(separator + 1));
    }

    static String detailSample(JsonNode firstRows, JsonNode profile) {
        JsonNode sample = profile.get("httpDetailSample");
        check(sample == null || sample.isTextual() && !sample.asText().isBlank()
                && sample.asText().length() <= 512
                && sample.asText().chars().noneMatch(c -> Character.isISOControl(c)
                    || Character.isSurrogate((char) c)), "HTTP_DETAIL_SAMPLE_INVALID");
        return sample == null ? firstRows.get(0).path("vod_id").asText() : sample.textValue();
    }

    static void validatePages(JsonNode first, JsonNode second) {
        Set<String> firstIds = new HashSet<>();
        for (JsonNode row : cards(first)) firstIds.add(row.path("vod_id").textValue());
        for (JsonNode row : cards(second))
            check(!firstIds.contains(row.path("vod_id").textValue()), "HTTP_PAGINATION_FAILED");
        check(first.path("page").asText().equals("1") && second.path("page").asText().equals("2"),
                "HTTP_PAGINATION_FAILED");
    }

    void runMatrix() throws Exception {
        Map<String, String> filters = Map.of("note", "\u6d4b\u8bd5 + & #=%", "year", "2026");
        String ext = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(filters));
        for (String proxy : List.of("all", "direct")) {
            web.getBean(AppProperties.class).setT4ProxyMode(proxy);
            String mode = proxy.equals("direct") ? "t4-direct" : "t4";
            for (String shape : List.of("get", "form", "json")) {
                var home = call(shape, mode, "home", Map.of("filter", true), Map.of());
                check(home.path("class").isArray() && !home.path("class").isEmpty(), "HTTP_HOME_EMPTY");
                String type = home.path("class").get(0).path("type_id").asText();
                var first = call(shape, mode, "category", Map.of("t", type, "pg", 1, "ext", ext),
                        Map.of("t", type, "pg", "1", "extend", filters));
                var second = call(shape, mode, "category", Map.of("t", type, "pg", 2, "ext", ext),
                        Map.of("t", type, "pg", "2", "extend", filters));
                validatePages(first, second);
                JsonNode firstRows = first.path("list");
                String id = detailSample(firstRows, frozenProfile);
                var detail = cards(call(shape, mode, "detail", Map.of("ids", id), Map.of("ids", id))).get(0);
                if (frozenProfile.has("httpDetailSample"))
                    check(id.equals(detail.path("vod_id").asText()), "HTTP_DETAIL_SAMPLE_MISMATCH");
                var selected = playbackEpisode(detail, frozenProfile);
                String line = selected.get(0);
                String keyword = "\u4e2d\u534e\u60c5 + & #=%";
                var search = call(shape, mode, "search", Map.of("wd", keyword, "quick", true, "pg", 3),
                        Map.of("wd", keyword, "quick", true, "pg", "3"));
                check(search.path("list").isArray(), "HTTP_SEARCH_SHAPE_INVALID");
                String play = selected.get(1);
                var player = call(shape, mode, "play", Map.of("play", play, "flag", line),
                        Map.of("play", play, "flag", line));
                check(player.path("parse").isIntegralNumber() && player.path("parse").intValue() == 0,
                        "HTTP_PLAYER_INVALID");
                URI url = URI.create(player.path("url").asText());
                if (player.path("url").asText().startsWith(
                        origin + "/t4cenc/" + t4Token + "/" + SITE + "/session/")) {
                    readProxy(url);
                } else check(player.path("url").asText().startsWith("https://"), "HTTP_PLAYER_INVALID");
            }
            worker.finishHttpSession();
        }
        check(actions == 36, "HTTP_MATRIX_INCOMPLETE");
        for (String shape : List.of("form", "json"))
            check(request(shape, Map.of(), false).statusCode() == 403, "HTTP_CLIENT_AUTH_BROKEN");
        result = worker.finishHttpObservation();
    }

    private void readProxy(URI url) throws Exception {
        // No external fetch: only this driver's real AT callback and the same source session.
        var first = proxyRequest(url, "bytes=0-32767");
        String firstMime = first.headers().firstValue("Content-Type").orElse("");
        byte[] firstBody = first.body();
        boolean manifest = firstMime.split(";", 2)[0].trim().equalsIgnoreCase("application/vnd.apple.mpegurl")
                || firstMime.split(";", 2)[0].trim().equalsIgnoreCase("application/x-mpegurl");
        observation.completeProxy(first.statusCode(), firstMime,
                first.headers().firstValue("Content-Range").orElse(""),
                first.headers().firstValueAsLong("Content-Length").orElse(-1), firstBody);
        if (manifest) {
            URI segment = firstManifestUri(firstBody, url);
            var second = proxyRequest(segment, "bytes=0-32767");
            observation.completeProxy(second.statusCode(),
                    second.headers().firstValue("Content-Type").orElse(""),
                    second.headers().firstValue("Content-Range").orElse(""),
                    second.headers().firstValueAsLong("Content-Length").orElse(-1), second.body());
        } else {
            var head = proxyRequest(url, "bytes=0-32767", "HEAD");
            observation.completeProxy(head.statusCode(),
                    head.headers().firstValue("Content-Type").orElse(""),
                    head.headers().firstValue("Content-Range").orElse(""),
                    head.headers().firstValueAsLong("Content-Length").orElse(-1), head.body());
        }
    }

    private ProxyResponse proxyRequest(URI url, String range) throws Exception {
        return proxyRequest(url, range, "GET");
    }

    private ProxyResponse proxyRequest(URI url, String range, String method) throws Exception {
        var builder = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(110))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (!range.isEmpty()) builder.header("Range", range);
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        String mime = response.headers().firstValue("Content-Type").orElse("")
                .split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        int limit = Set.of("application/vnd.apple.mpegurl", "application/x-mpegurl").contains(mime)
                ? 524288 : 32768;
        byte[] body;
        try (var stream = response.body()) {
            body = stream.readNBytes(limit + 1);
        }
        check(body.length <= limit, "HTTP_PROXY_BODY_LIMIT");
        return new ProxyResponse(response.statusCode(), response.headers(), body);
    }

    static URI firstManifestUri(byte[] body, URI manifest) throws Exception {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(
                java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        if (text.startsWith("\ufeff")) text = text.substring(1);
        for (String line : text.split("\\R")) {
            String value = line.strip();
            if (value.isEmpty() || value.startsWith("#")) continue;
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException("HTTP_MANIFEST_URI_INVALID");
            }
            check(Objects.equals(uri.getScheme(), manifest.getScheme())
                    && Objects.equals(uri.getRawAuthority(), manifest.getRawAuthority())
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && Objects.equals(uri.getRawPath(), manifest.getRawPath())
                    && uri.getRawPath() != null && uri.getRawPath().endsWith("/local")
                    && uri.getRawQuery() != null,
                    "HTTP_MANIFEST_URI_INVALID");
            return uri;
        }
        throw new IllegalStateException("HTTP_MANIFEST_EMPTY");
    }

    private record ProxyResponse(int statusCode, HttpHeaders headers, byte[] body) {}

    void stop() throws Exception {
        int port = server == null ? 0 : server.getPort();
        try {
            if (http != null) {
                http.shutdownNow();
                check(http.awaitTermination(Duration.ofSeconds(3)), "HTTP_CLIENT_NOT_STOPPED");
            }
        } finally {
            try {
                if (server != null) {
                    var tomcat = (TomcatWebServer) server;
                    try { tomcat.stop(); } finally { tomcat.destroy(); }
                    check(tomcat.getTomcat().getServer().getState() == LifecycleState.DESTROYED,
                            "HTTP_SERVER_NOT_DESTROYED");
                }
            } finally {
                try {
                    if (web != null && web.isActive()) web.close();
                    if (worker != null) worker.shutdown();
                    if (broker != null) broker.close();
                } finally {
                    if (persistence != null && persistence.isActive()) {
                        EntityManagerFactory factory = persistence.getBean(EntityManagerFactory.class);
                        persistence.close();
                        check(!factory.isOpen(), "HTTP_DATABASE_NOT_CLOSED");
                    }
                }
            }
        }
        if (port != 0) {
            try (var socket = new Socket()) {
                try {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                    throw new IllegalStateException("HTTP_PORT_NOT_CLOSED");
                } catch (ConnectException expected) { }
            }
        }
        cleaned = true;
    }

    public static void main(String[] args) throws Exception {
        PrintStream report = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        var driver = new ManagedT4HttpAcceptance();
        String failure = null;
        try {
            byte[] input = System.in.readNBytes(1_500_001);
            check(input.length > 0 && input.length <= 1_500_000, "HTTP_INPUT_LIMIT");
            driver.start(JSON.readTree(input), Files.createTempDirectory("at-http-acceptance-"),
                    Path.of("/run/at-maintenance/executor.sock"));
            driver.runMatrix();
        } catch (Throwable error) {
            String code = error.getMessage();
            failure = code != null && code.matches("[A-Z_0-9]{1,100}") ? code : "HTTP_DRIVER_FAILED";
        } finally {
            try { driver.stop(); }
            catch (Throwable error) { failure = "HTTP_CLEANUP_UNCONFIRMED"; }
        }
        report.println(JSON.writeValueAsString(JSON.createObjectNode().put("completed", failure == null)
                .put("actions", driver.actions).put("code", failure == null ? "" : failure)
                .put("httpOwners", "real_AT_lab_owners").put("pythonBroker", true)
                .put("sourceExecutedInContainer", driver.actions > 0).put("cleanupConfirmed", driver.cleaned)
                .put("sampleSearchSupported", false).put("fullAtApplication", false)
                .put("configurationDataConnected", true).put("playbackVerified", false)
                .set("httpObservation", driver.result == null ? JSON.nullNode() : driver.result)));
        if (failure != null) System.exit(1);
    }

    JsonNode observation() { return result == null ? JSON.nullNode() : result.deepCopy(); }
    boolean cleanupConfirmed() { return cleaned; }
}
