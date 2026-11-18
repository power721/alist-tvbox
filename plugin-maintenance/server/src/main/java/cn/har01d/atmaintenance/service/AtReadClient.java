package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Read-only metadata, static Python inputs and optional runtime snapshots from one configured origin. */
@Service
public class AtReadClient {
    private final ObjectMapper mapper;
    private URI origin;
    private String token;
    private boolean apiKeyAuthentication;
    private AtConnection connection;
    private final int timeoutMillis;
    private final int bodyLimit;
    private String runtimeIdentityPath;
    private HttpClient client;
    private PublicPythonSourceReader publicSources;
    private AtObservation cached;
    private final Map<Integer, URI> sourceLocations = new HashMap<>();
    private long nextAttemptNanos;
    private final Map<Integer, AtRuntimeIdentity> runtimeIdentityCache = new HashMap<>();
    private final Map<Integer, Long> runtimeIdentityNextAttemptNanos = new HashMap<>();
    private Set<Integer> excludedPluginIds = Set.of();
    private AtHostAccess host;
    private boolean preferHost;

    @Autowired(required = false)
    void hostAccess(AtHostAccess host) { this.host = host; }

    public boolean embeddedAvailable() { return host != null; }
    public boolean usingEmbedded() { return host != null && preferHost; }

    @Value("${maintenance.at.excluded-plugin-ids:}")
    void configureExclusions(String ids) {
        var parsed = new HashSet<Integer>();
        if (!ids.isBlank()) {
            for (String id : ids.split(",", -1)) {
                int value = Integer.parseInt(id.trim());
                if (value < 1) throw new IllegalArgumentException("INVALID_AT_EXCLUDED_PLUGIN_ID");
                parsed.add(value);
            }
        }
        excludedPluginIds = Set.copyOf(parsed);
    }

    public List<Integer> excludedPluginIds() { return excludedPluginIds.stream().sorted().toList(); }

    public void requireMaintenanceAllowed(int pluginId) {
        if (excludedPluginIds.contains(pluginId)) throw error(HttpStatus.CONFLICT, "AT_PLUGIN_MAINTENANCE_EXCLUDED");
    }

    @Autowired
    public AtReadClient(ObjectMapper mapper, ConnectionSettingsStore settings, PublicPythonSourceReader publicSources) {
        this(mapper, settings.at());
        this.publicSources = publicSources;
    }

    AtReadClient(ObjectMapper mapper, AtConnectionSettings settings) {
        this(mapper, settings.origin(), settings.instanceId(), settings.revision(),
                "api_key".equals(settings.authMode()) ? "" : settings.credential(),
                settings.allowHttp(), settings.runtimeIdentityPath(), 8000, 2_097_152,
                settings.authMode(), "api_key".equals(settings.authMode()) ? settings.credential() : "");
        preferHost = Boolean.TRUE.equals(settings.useEmbedded())
                || settings.useEmbedded() == null && settings.origin().isBlank();
    }

    public AtReadClient(ObjectMapper mapper, @Value("${maintenance.at.origin:}") String origin,
                        @Value("${maintenance.at.instance-id:}") String instanceId,
                        @Value("${maintenance.at.revision:unverified}") String revision,
                        @Value("${maintenance.at.session-token:}") String token,
                        @Value("${maintenance.at.allow-http:false}") boolean allowHttp,
                        @Value("${maintenance.at.runtime-identity-path:}") String runtimeIdentityPath,
                        @Value("${maintenance.at.auth-mode:session}") String authMode,
                        @Value("${maintenance.at.api-key:}") String apiKey) {
        this(mapper, origin, instanceId, revision, token, allowHttp, runtimeIdentityPath,
                8000, 2_097_152, authMode, apiKey);
    }

    AtReadClient(ObjectMapper mapper, String address, String instanceId, String revision, String token,
                 boolean allowHttp) {
        this(mapper, address, instanceId, revision, token, allowHttp, "", 8000, 2_097_152);
    }

    AtReadClient(ObjectMapper mapper, String address, String instanceId, String revision, String token,
                 boolean allowHttp, int timeoutMillis, int bodyLimit) {
        this(mapper, address, instanceId, revision, token, allowHttp, "", timeoutMillis, bodyLimit);
    }

    AtReadClient(ObjectMapper mapper, String address, String instanceId, String revision, String token,
                 boolean allowHttp, String runtimeIdentityPath, int timeoutMillis, int bodyLimit) {
        this(mapper, address, instanceId, revision, token, allowHttp, runtimeIdentityPath,
                timeoutMillis, bodyLimit, "session", "");
    }

    AtReadClient(ObjectMapper mapper, String address, String instanceId, String revision, String token,
                 boolean allowHttp, String runtimeIdentityPath, int timeoutMillis, int bodyLimit,
                 String authMode, String apiKey) {
        this.mapper = mapper;
        this.apiKeyAuthentication = "api_key".equals(authMode);
        this.token = apiKeyAuthentication ? apiKey : token;
        this.timeoutMillis = timeoutMillis;
        this.bodyLimit = bodyLimit;
        this.runtimeIdentityPath = validateRuntimeIdentityPath(runtimeIdentityPath);
        if (address.isBlank()) {
            origin = null;
            client = null;
            connection = new AtConnection(false, null, null, null, null, "configured");
            return;
        }
        URI parsed;
        try {
            parsed = URI.create(address);
            if (parsed.getHost() == null || parsed.getUserInfo() != null || parsed.getQuery() != null
                    || parsed.getFragment() != null || !Set.of("", "/").contains(parsed.getPath())
                    || !("https".equals(parsed.getScheme()) || allowHttp && "http".equals(parsed.getScheme()))
                    || !instanceId.matches("[A-Za-z0-9_-]{1,100}") || revision.length() > 200
                    || !("session".equals(authMode) || apiKeyAuthentication)
                    || apiKeyAuthentication && !token.isEmpty()
                    || !apiKeyAuthentication && !apiKey.isEmpty()
                    || !this.token.matches("[\\x21-\\x7e]{1,8192}")
                    || parsed.getPort() == 0 || parsed.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
            int port = parsed.getPort();
            if (port == ("https".equals(parsed.getScheme()) ? 443 : 80)) port = -1;
            origin = new URI(parsed.getScheme(), null, parsed.getHost().toLowerCase(Locale.ROOT), port, "/", null, null);
        } catch (IllegalArgumentException | java.net.URISyntaxException error) {
            // Do not echo a credential-bearing misconfigured URI or token.
            throw new IllegalArgumentException("INVALID_AT_CONNECTION");
        }
        String originSha = Hashes.sha256(origin.toString());
        connection = new AtConnection(true, instanceId, Hashes.sha256(instanceId + "\n" + originSha),
                originSha, revision, "configured");
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    public synchronized AtConnection connection() { return usingEmbedded() ? host.connection() : connection; }

    synchronized void replace(AtReadClient replacement) {
        close();
        origin = replacement.origin;
        token = replacement.token;
        apiKeyAuthentication = replacement.apiKeyAuthentication;
        connection = replacement.connection;
        runtimeIdentityPath = replacement.runtimeIdentityPath;
        client = replacement.client;
        preferHost = replacement.preferHost;
        cached = null;
        nextAttemptNanos = 0;
        sourceLocations.clear();
        runtimeIdentityCache.clear();
        runtimeIdentityNextAttemptNanos.clear();
    }

    public synchronized AtObservation observe() {
        if (usingEmbedded()) {
            if (cached != null && cached.observedAt().plusSeconds(30).isAfter(Instant.now())) return cached;
            Instant now = Instant.now();
            var plugins = host.plugins();
            var identity = mapper.createObjectNode().put("instanceKey", connection().instanceKey())
                    .put("observedAt", now.toString());
            identity.set("plugins", mapper.valueToTree(plugins));
            identity.set("excludedPluginIds", mapper.valueToTree(excludedPluginIds()));
            cached = new AtObservation(Hashes.sha256(identity.toString()), connection(), now, plugins,
                    "at_embedded_repository", "当前 AT 宿主插件快照；未执行爬虫，不代表联网健康。", excludedPluginIds());
            return cached;
        }
        if (origin == null) throw error(HttpStatus.CONFLICT, "AT_NOT_CONFIGURED");
        if (cached != null && cached.observedAt().plusSeconds(30).isAfter(Instant.now())) return cached;
        if (nextAttemptNanos != 0 && nextAttemptNanos - System.nanoTime() > 0) {
            throw error(HttpStatus.TOO_MANY_REQUESTS, "AT_READ_COOLDOWN");
        }
        nextAttemptNanos = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        // A failed refresh must not leave an earlier observation available for a new binding.
        cached = null;
        sourceLocations.clear();
        var request = HttpRequest.newBuilder(origin.resolve("api/plugins"))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header(apiKeyAuthentication ? "X-API-KEY" : "Authorization", token)
                .header("Accept", "application/json").GET().build();
        var pending = client.sendAsync(request, ignored -> new LimitedHttpBody(bodyLimit));
        try {
            HttpResponse<byte[]> response = pending.get(timeoutMillis, TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (status == 401 || status == 403) throw error(HttpStatus.BAD_GATEWAY, "AT_AUTHENTICATION_FAILED");
            if (status >= 300 && status < 400) throw error(HttpStatus.BAD_GATEWAY, "AT_REDIRECT_REFUSED");
            if (status != 200) throw error(HttpStatus.BAD_GATEWAY, "AT_HTTP_ERROR");
            String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if (!type.equalsIgnoreCase("application/json")) throw error(HttpStatus.BAD_GATEWAY, "AT_RESPONSE_TYPE");
            JsonNode array;
            try {
                array = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(response.body());
            } catch (IOException e) {
                throw error(HttpStatus.BAD_GATEWAY, "AT_RESPONSE_INVALID");
            }
            List<AtPluginSummary> plugins = parse(array);
            for (JsonNode item : array) {
                URI location = staticPythonLocation(item.path("url").asText());
                if (location == null) location = publicPythonLocation(item.path("url").asText());
                if (location != null) sourceLocations.put(item.path("id").asInt(), location);
            }
            Instant now = Instant.now();
            var identity = mapper.createObjectNode();
            identity.put("instanceKey", connection.instanceKey());
            identity.put("observedAt", now.toString());
            identity.set("plugins", mapper.valueToTree(plugins));
            identity.set("excludedPluginIds", mapper.valueToTree(excludedPluginIds()));
            cached = new AtObservation(Hashes.sha256(identity.toString()), connection, now, List.copyOf(plugins),
                    "metadata_observed", "管理 API 元数据；siteKey 按本地 AT 基线推导，"
                    + "没有插件正文、实际源码 SHA 或部署运行时回执。", excludedPluginIds());
            return cached;
        } catch (TimeoutException e) {
            throw error(HttpStatus.GATEWAY_TIMEOUT, "AT_READ_TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "AT_READ_INTERRUPTED");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause.getCause() != null) cause = cause.getCause();
            String code = "AT_RESPONSE_TOO_LARGE".equals(cause.getMessage()) ? "AT_RESPONSE_TOO_LARGE"
                    : cause instanceof java.net.http.HttpTimeoutException ? "AT_READ_TIMEOUT" : "AT_IO_ERROR";
            throw error(HttpStatus.BAD_GATEWAY, code);
        } finally {
            pending.cancel(true);
        }
    }

    public synchronized AtObservation requireObservation(String sha) {
        if (cached == null || !cached.sha256().equals(sha)) throw error(HttpStatus.CONFLICT, "AT_OBSERVATION_CHANGED");
        if (cached.observedAt().plusSeconds(300).isBefore(Instant.now())) {
            throw error(HttpStatus.CONFLICT, "AT_OBSERVATION_EXPIRED");
        }
        return cached;
    }

    public synchronized byte[] readPythonSource(String observationSha, int pluginId) {
        requireMaintenanceAllowed(pluginId);
        var observation = requireObservation(observationSha);
        var plugin = observation.plugins().stream().filter(item -> item.id() == pluginId).findFirst()
                .orElseThrow(() -> error(HttpStatus.CONFLICT, "AT_PLUGIN_NOT_OBSERVED"));
        if (usingEmbedded()) {
            if (!plugin.enabled() || !plugin.t4Visible() || !"python".equals(plugin.kind()))
                throw error(HttpStatus.CONFLICT, "AT_SOURCE_NOT_READABLE");
            var current = host.plugins().stream().filter(item -> item.id() == pluginId).findFirst().orElse(null);
            if (!plugin.equals(current)) throw error(HttpStatus.CONFLICT, "AT_OBSERVATION_CHANGED");
            return host.readPythonSource(pluginId);
        }
        URI location = sourceLocations.get(pluginId);
        if (!plugin.enabled() || !plugin.t4Visible() || !"python".equals(plugin.kind()) || location == null) {
            throw error(HttpStatus.CONFLICT, "AT_SOURCE_NOT_READABLE");
        }
        if (staticPythonLocation(location.toASCIIString()) == null) {
            if (publicSources == null) throw error(HttpStatus.CONFLICT, "AT_SOURCE_NOT_READABLE");
            return publicSources.read(location, Math.min(bodyLimit, 1_048_576));
        }
        var request = HttpRequest.newBuilder(location).timeout(Duration.ofMillis(timeoutMillis))
                .header(apiKeyAuthentication ? "X-API-KEY" : "Authorization", token)
                .header("Accept", "text/plain, application/octet-stream, text/x-python").GET().build();
        var pending = client.sendAsync(request, ignored -> new LimitedHttpBody(Math.min(bodyLimit, 1_048_576)));
        try {
            var response = pending.get(timeoutMillis, TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (status == 401 || status == 403) throw error(HttpStatus.BAD_GATEWAY, "AT_AUTHENTICATION_FAILED");
            if (status >= 300 && status < 400) throw error(HttpStatus.BAD_GATEWAY, "AT_REDIRECT_REFUSED");
            if (status != 200) throw error(HttpStatus.BAD_GATEWAY, "AT_SOURCE_HTTP_ERROR");
            String type = response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!Set.of("text/plain", "text/x-python", "application/x-python-code",
                    "application/octet-stream").contains(type)) {
                throw error(HttpStatus.BAD_GATEWAY, "AT_SOURCE_RESPONSE_TYPE");
            }
            if (response.body().length == 0) throw error(HttpStatus.BAD_GATEWAY, "AT_SOURCE_EMPTY");
            return response.body();
        } catch (TimeoutException e) {
            throw error(HttpStatus.GATEWAY_TIMEOUT, "AT_READ_TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "AT_READ_INTERRUPTED");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause.getCause() != null) cause = cause.getCause();
            String code = "AT_RESPONSE_TOO_LARGE".equals(cause.getMessage()) ? "AT_RESPONSE_TOO_LARGE"
                    : cause instanceof java.net.http.HttpTimeoutException ? "AT_READ_TIMEOUT" : "AT_IO_ERROR";
            throw error(HttpStatus.BAD_GATEWAY, code);
        } finally {
            pending.cancel(true);
        }
    }

    synchronized String sourceReadAuthority(int pluginId) {
        if (usingEmbedded()) return "at_embedded_source_read";
        URI location = sourceLocations.get(pluginId);
        return location != null && staticPythonLocation(location.toASCIIString()) == null
                ? "at_public_https_source_read" : "at_static_source_read";
    }

    private URI publicPythonLocation(String locator) {
        try {
            if (locator.contains("\\") || locator.codePoints().anyMatch(c -> c < 32 || c == 127)) return null;
            URI value = URI.create(locator.replace(" ", "%20"));
            if (!"https".equals(value.getScheme()) || value.getHost() == null || value.getUserInfo() != null
                    || value.getFragment() != null || value.getPath() == null
                    || !value.getPath().toLowerCase(Locale.ROOT).endsWith(".py")
                    || value.getPort() == 0 || value.getPort() > 65535
                    || value.toASCIIString().length() > 2048
                    || MediaNetworkPolicy.origin(value).equals(MediaNetworkPolicy.origin(origin))) return null;
            return value;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private URI staticPythonLocation(String locator) {
        if (locator.contains("?") || locator.contains("#") || locator.contains("\\")
                || locator.codePoints().anyMatch(c -> c < 32 || c == 127)) return null;
        try {
            URI value;
            try {
                value = URI.create(locator);
            } catch (IllegalArgumentException error) {
                if (!locator.startsWith("/static/")) return null;
                value = new URI(null, null, locator, null);
            }
            URI resolved = origin.resolve(value);
            String path = resolved.getPath();
            int port = resolved.getPort() == -1 ? ("https".equals(resolved.getScheme()) ? 443 : 80)
                    : resolved.getPort();
            int originPort = origin.getPort() == -1 ? ("https".equals(origin.getScheme()) ? 443 : 80)
                    : origin.getPort();
            if (!origin.getScheme().equals(resolved.getScheme())
                    || !origin.getHost().equalsIgnoreCase(Objects.toString(resolved.getHost(), ""))
                    || port != originPort || value.getRawUserInfo() != null
                    || value.getRawQuery() != null || value.getRawFragment() != null
                    || path == null || !path.startsWith("/static/") || !path.toLowerCase(Locale.ROOT).endsWith(".py")
                    || path.contains("\\") || path.contains("%") || path.contains("//")
                    || path.codePoints().anyMatch(c -> c < 32 || c == 127)
                    || Arrays.stream(path.split("/")).anyMatch(part -> part.equals(".") || part.equals(".."))
                    || value.getPath() != null && Arrays.stream(value.getPath().split("/"))
                    .anyMatch(part -> part.equals(".") || part.equals(".."))) return null;
            return new URI(origin.getScheme(), null, origin.getHost(), origin.getPort(), path, null, null);
        } catch (IllegalArgumentException | java.net.URISyntaxException error) {
            return null;
        }
    }

    /**
     * Observe a runtime identity only through an explicitly configured,
     * read-only endpoint.  The normal /api/plugins metadata route remains
     * independent, so an AT instance without this endpoint keeps the old
     * metadata-only behavior.
     */
    public synchronized AtRuntimeIdentity observeRuntimeIdentity(int pluginId, String expectedSiteKey) {
        if (usingEmbedded()) {
            var value = host.runtimeIdentity(pluginId);
            if (!value.siteKey().equals(expectedSiteKey)) throw error(HttpStatus.CONFLICT, "AT_RUNTIME_SITE_KEY_MISMATCH");
            return value;
        }
        if (origin == null) throw error(HttpStatus.CONFLICT, "AT_NOT_CONFIGURED");
        if (apiKeyAuthentication) throw error(HttpStatus.CONFLICT, "AT_RUNTIME_IDENTITY_REQUIRES_SESSION");
        if (runtimeIdentityPath == null) throw error(HttpStatus.CONFLICT, "AT_RUNTIME_IDENTITY_NOT_CONFIGURED");
        if (pluginId < 1 || expectedSiteKey == null || expectedSiteKey.isBlank()
                || expectedSiteKey.length() > 200) {
            throw error(HttpStatus.BAD_REQUEST, "AT_RUNTIME_IDENTITY_REQUEST_INVALID");
        }
        AtRuntimeIdentity previous = runtimeIdentityCache.get(pluginId);
        if (previous != null && previous.observedAt().plusSeconds(30).isAfter(Instant.now())) {
            if (!previous.siteKey().equals(expectedSiteKey)) {
                throw error(HttpStatus.CONFLICT, "AT_RUNTIME_SITE_KEY_MISMATCH");
            }
            return previous;
        }
        long next = runtimeIdentityNextAttemptNanos.getOrDefault(pluginId, 0L);
        if (next - System.nanoTime() > 0) {
            throw error(HttpStatus.TOO_MANY_REQUESTS, "AT_RUNTIME_IDENTITY_COOLDOWN");
        }
        runtimeIdentityNextAttemptNanos.put(pluginId, System.nanoTime()
                + Duration.ofSeconds(30).toNanos());
        runtimeIdentityCache.remove(pluginId);
        String path = runtimeIdentityPath.replace("{pluginId}", Integer.toString(pluginId));
        var request = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofMillis(timeoutMillis)).header("Authorization", token)
                .header("Accept", "application/json").GET().build();
        var pending = client.sendAsync(request, ignored -> new LimitedHttpBody(bodyLimit));
        try {
            HttpResponse<byte[]> response = pending.get(timeoutMillis, TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (status == 401 || status == 403) throw error(HttpStatus.BAD_GATEWAY, "AT_AUTHENTICATION_FAILED");
            if (status >= 300 && status < 400) throw error(HttpStatus.BAD_GATEWAY, "AT_REDIRECT_REFUSED");
            if (status != 200) throw error(HttpStatus.BAD_GATEWAY, "AT_RUNTIME_IDENTITY_HTTP_ERROR");
            String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if (!type.equalsIgnoreCase("application/json")) {
                throw error(HttpStatus.BAD_GATEWAY, "AT_RUNTIME_IDENTITY_RESPONSE_TYPE");
            }
            JsonNode object;
            try {
                object = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(response.body());
            } catch (IOException e) {
                throw error(HttpStatus.BAD_GATEWAY, "AT_RUNTIME_IDENTITY_RESPONSE_INVALID");
            }
            AtRuntimeIdentity observed = parseRuntimeIdentity(object, pluginId, expectedSiteKey);
            runtimeIdentityCache.put(pluginId, observed);
            return observed;
        } catch (TimeoutException e) {
            throw error(HttpStatus.GATEWAY_TIMEOUT, "AT_RUNTIME_IDENTITY_TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "AT_RUNTIME_IDENTITY_INTERRUPTED");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause.getCause() != null) cause = cause.getCause();
            String code = "AT_RESPONSE_TOO_LARGE".equals(cause.getMessage())
                    ? "AT_RESPONSE_TOO_LARGE"
                    : cause instanceof java.net.http.HttpTimeoutException
                    ? "AT_RUNTIME_IDENTITY_TIMEOUT" : "AT_RUNTIME_IDENTITY_IO_ERROR";
            throw error(HttpStatus.BAD_GATEWAY, code);
        } finally {
            pending.cancel(true);
        }
    }

    private List<AtPluginSummary> parse(JsonNode array) {
        if (array == null || !array.isArray() || array.size() > 5000) throw error(HttpStatus.BAD_GATEWAY, "AT_RESPONSE_INVALID");
        List<AtPluginSummary> result = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode item : array) {
            if (!item.isObject() || !item.path("id").isIntegralNumber() || !item.path("id").canConvertToInt()
                    || item.path("id").asInt() < 1 || !item.path("enabled").isBoolean()
                    || !item.path("t4Visible").isBoolean()) throw error(HttpStatus.BAD_GATEWAY, "AT_RESPONSE_INVALID");
            int id = item.path("id").asInt();
            String name = text(item, "name", 200, true);
            String externalId = text(item, "externalId", 200, false);
            String locator = text(item, "url", 16384, true);
            JsonNode version = item.path("version");
            if (!(version.isNull() || version.isMissingNode() || version.isIntegralNumber() && version.canConvertToInt())) {
                throw error(HttpStatus.BAD_GATEWAY, "AT_RESPONSE_INVALID");
            }
            String kind;
            String key = externalId == null || externalId.isBlank() ? "plugin-" + id : externalId;
            if (locator.startsWith("/static/webhome/pages/")) {
                kind = "webpage";
                String part = locator.substring("/static/webhome/pages/".length());
                int dot = part.lastIndexOf('.');
                if (dot > 0) part = part.substring(0, dot);
                part = part.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_+|_+$", "");
                key = "web_" + (part.isEmpty() ? id : part);
            } else {
                String path;
                String trimmed = locator.trim();
                try {
                    path = URI.create(trimmed).getPath();
                } catch (IllegalArgumentException e) {
                    // AT's static-directory scanner can emit unescaped spaces or brackets.
                    path = trimmed.split("[?#]", 2)[0];
                    path = path.substring(path.lastIndexOf('/') + 1);
                }
                String suffixPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
                kind = suffixPath.endsWith(".py") ? "python"
                        : suffixPath.endsWith(".txt") ? "secspider" : "unknown";
            }
            if (!ids.add(id) || !keys.add(key)) throw error(HttpStatus.BAD_GATEWAY, "AT_IDENTITY_COLLISION");
            result.add(new AtPluginSummary(id, name, externalId, item.path("enabled").asBoolean(),
                    item.path("t4Visible").asBoolean(), version.isIntegralNumber() ? version.asInt() : null,
                    kind, key, Hashes.sha256(locator), item.hasNonNull("lastError") && !item.path("lastError").asText().isBlank()));
        }
        result.sort(Comparator.comparingInt(AtPluginSummary::id));
        return result;
    }

    private String text(JsonNode item, String field, int limit, boolean required) {
        JsonNode value = item.path(field);
        if (!required && (value.isNull() || value.isMissingNode())) return null;
        if (!value.isTextual() || value.asText().length() > limit || required && value.asText().isBlank()) {
            throw error(HttpStatus.BAD_GATEWAY, "AT_RESPONSE_INVALID");
        }
        return value.asText();
    }

    private AtRuntimeIdentity parseRuntimeIdentity(JsonNode object, int pluginId, String expectedSiteKey) {
        if (object == null || !object.isObject()
                || !object.path("pluginId").isIntegralNumber()
                || !object.path("pluginId").canConvertToInt()
                || object.path("pluginId").asInt() != pluginId) {
            throw error(HttpStatus.BAD_GATEWAY, "AT_RUNTIME_IDENTITY_RESPONSE_INVALID");
        }
        String siteKey = text(object, "siteKey", 200, true);
        if (!siteKey.equals(expectedSiteKey)) {
            throw error(HttpStatus.CONFLICT, "AT_RUNTIME_SITE_KEY_MISMATCH");
        }
        String sourceSha = text(object, "sourceSha256", 64, false);
        if (sourceSha != null && !sourceSha.matches("[a-f0-9]{64}")) {
            throw error(HttpStatus.BAD_GATEWAY, "AT_RUNTIME_IDENTITY_RESPONSE_INVALID");
        }
        JsonNode kindNode = object.path("sourceIdentityKind");
        if (!kindNode.isMissingNode() && !kindNode.isNull() && !kindNode.isTextual()) {
            throw error(HttpStatus.BAD_GATEWAY, "AT_RUNTIME_IDENTITY_RESPONSE_INVALID");
        }
        String identityKind = text(object, "sourceIdentityKind", 64, false);
        if (identityKind == null) identityKind = "unspecified";
        if (!Set.of("utf8_compilation_text", "source_bytes", "unobserved", "unspecified").contains(identityKind)
                || identityKind.equals("unobserved") && sourceSha != null) {
            throw error(HttpStatus.BAD_GATEWAY, "AT_RUNTIME_IDENTITY_RESPONSE_INVALID");
        }
        String revision = text(object, "runtimeRevision", 200, true);
        String state = text(object, "loadState", 32, true);
        if (!Set.of("LOADED", "NOT_LOADED", "BLOCKED", "UNKNOWN").contains(state)
                || !object.path("loaded").isBoolean()
                || object.path("loaded").asBoolean() != state.equals("LOADED")
                || state.equals("LOADED") && sourceSha == null) {
            throw error(HttpStatus.BAD_GATEWAY, "AT_RUNTIME_IDENTITY_RESPONSE_INVALID");
        }
        String generation = text(object, "workerGeneration", 64, false);
        if (generation != null && !generation.matches("[A-Za-z0-9_-]{1,64}")) {
            throw error(HttpStatus.BAD_GATEWAY, "AT_RUNTIME_IDENTITY_RESPONSE_INVALID");
        }
        JsonNode rawFilters = object.path("filterIdentity");
        JsonNode protectedFilters = object.path("protectedFilterIdentity");
        if (!rawFilters.isMissingNode() && !rawFilters.isNull()
                && !protectedFilters.isMissingNode() && !protectedFilters.isNull()) {
            throw error(HttpStatus.BAD_GATEWAY, "AT_RUNTIME_FILTER_IDENTITY_MIXED");
        }
        AtRuntimeFiltersIdentity filters = parseRuntimeFilters(rawFilters, state);
        AtProtectedFiltersIdentity protectedIdentity = parseProtectedRuntimeFilters(protectedFilters, state);
        Instant observedAt = Instant.now();
        return new AtRuntimeIdentity(pluginId, siteKey, sourceSha, revision, state,
                state.equals("LOADED"), generation, observedAt, "runtime_observed",
                state.equals("LOADED")
                        ? "只读 runtime identity 回执；源码 SHA 的含义见 sourceIdentityKind，"
                        + "编译文本或未声明的 SHA 不能当作下载原字节身份；"
                        + "过滤器身份未声明时保持未知，不能视为空列表或全部装载；"
                        + "不证明媒体、客户端或发布已成功。"
                        : "runtime identity 回执显示尚未完成可用装载；不允许将管理元数据当作运行时通过。",
                identityKind, filters, protectedIdentity);
    }

    private AtRuntimeFiltersIdentity parseRuntimeFilters(JsonNode value, String state) {
        if (value.isMissingNode() || value.isNull()) return null;
        String code = "AT_RUNTIME_FILTER_IDENTITY_RESPONSE_INVALID";
        if (!state.equals("LOADED") || !value.isObject()
                || !value.path("schemaVersion").isIntegralNumber()
                || !value.path("schemaVersion").canConvertToInt()
                || value.path("schemaVersion").asInt() != 1
                || !value.path("authority").isTextual()
                || !value.path("authority").asText().equals("worker_loader_observed")
                || !value.path("sourceIdentityKind").isTextual()
                || !value.path("sourceIdentityKind").asText().equals("utf8_compilation_text")
                || !value.path("configurationValid").isBoolean()
                || !value.path("allConfiguredFiltersLoaded").isBoolean()
                || !value.path("filters").isArray() || value.path("filters").size() > 1024) {
            throw error(HttpStatus.BAD_GATEWAY, code);
        }
        String configurationSha = filterHash(value.path("configurationSha256"), false);
        boolean valid = value.path("configurationValid").asBoolean();
        List<AtRuntimeFilterIdentity> filters = new ArrayList<>();
        for (JsonNode item : value.path("filters")) {
            if (!item.isObject() || !item.path("index").isIntegralNumber()
                    || !item.path("index").canConvertToInt() || item.path("index").asInt() != filters.size() + 1
                    || !item.path("loadState").isTextual()
                    || !Set.of("LOADED", "NOT_LOADED").contains(item.path("loadState").asText())) {
                throw error(HttpStatus.BAD_GATEWAY, code);
            }
            boolean loaded = item.path("loadState").asText().equals("LOADED");
            String sourceSha = filterHash(item.path("sourceSha256"), !loaded);
            if (!loaded && sourceSha != null) throw error(HttpStatus.BAD_GATEWAY, code);
            filters.add(new AtRuntimeFilterIdentity(filters.size() + 1,
                    filterHash(item.path("configurationSha256"), false),
                    item.path("loadState").asText(), sourceSha));
        }
        boolean allLoaded = valid && filters.stream().allMatch(item -> item.loadState().equals("LOADED"));
        if (!valid && !filters.isEmpty() || value.path("allConfiguredFiltersLoaded").asBoolean() != allLoaded) {
            throw error(HttpStatus.BAD_GATEWAY, code);
        }
        return new AtRuntimeFiltersIdentity(1, "worker_loader_observed", "utf8_compilation_text",
                configurationSha, valid, allLoaded, filters);
    }

    private String filterHash(JsonNode value, boolean nullable) {
        if (nullable && value.isNull()) return null;
        if (!value.isTextual() || !value.asText().matches("[a-f0-9]{64}")) {
            throw error(HttpStatus.BAD_GATEWAY, "AT_RUNTIME_FILTER_IDENTITY_RESPONSE_INVALID");
        }
        return value.asText();
    }

    private AtProtectedFiltersIdentity parseProtectedRuntimeFilters(JsonNode value, String state) {
        if (value.isMissingNode() || value.isNull()) return null;
        String code = "AT_RUNTIME_FILTER_IDENTITY_RESPONSE_INVALID";
        if (!state.equals("LOADED") || !value.isObject()
                || !value.path("schemaVersion").isIntegralNumber()
                || !value.path("schemaVersion").canConvertToInt()
                || value.path("schemaVersion").asInt() != 1
                || !value.path("authority").isTextual()
                || !value.path("authority").asText().equals("worker_loader_observed")
                || !value.path("sourceIdentityKind").isTextual()
                || !value.path("sourceIdentityKind").asText().equals("utf8_compilation_text")
                || !value.path("fingerprintScheme").isTextual()
                || !value.path("fingerprintScheme").asText().equals("hmac_sha256_loader_configuration_v1")
                || !value.path("fingerprintScope").isTextual()
                || !value.path("fingerprintScope").asText().matches("[a-f0-9]{32}")
                || value.has("configurationSha256")
                || !value.path("configurationValid").isBoolean()
                || !value.path("allConfiguredFiltersLoaded").isBoolean()
                || !value.path("filters").isArray() || value.path("filters").size() > 1024) {
            throw error(HttpStatus.BAD_GATEWAY, code);
        }
        String fingerprint = filterHash(value.path("configurationFingerprint"), false);
        boolean valid = value.path("configurationValid").asBoolean();
        List<AtProtectedFilterIdentity> filters = new ArrayList<>();
        for (JsonNode item : value.path("filters")) {
            if (!item.isObject() || !item.path("index").isIntegralNumber()
                    || !item.path("index").canConvertToInt() || item.path("index").asInt() != filters.size() + 1
                    || item.has("configurationSha256") || !item.path("loadState").isTextual()
                    || !Set.of("LOADED", "NOT_LOADED").contains(item.path("loadState").asText())) {
                throw error(HttpStatus.BAD_GATEWAY, code);
            }
            boolean loaded = item.path("loadState").asText().equals("LOADED");
            String sourceSha = filterHash(item.path("sourceSha256"), !loaded);
            if (!loaded && sourceSha != null) throw error(HttpStatus.BAD_GATEWAY, code);
            filters.add(new AtProtectedFilterIdentity(filters.size() + 1,
                    filterHash(item.path("configurationFingerprint"), false),
                    item.path("loadState").asText(), sourceSha));
        }
        boolean allLoaded = valid && filters.stream().allMatch(item -> item.loadState().equals("LOADED"));
        if (!valid && !filters.isEmpty() || value.path("allConfiguredFiltersLoaded").asBoolean() != allLoaded) {
            throw error(HttpStatus.BAD_GATEWAY, code);
        }
        return new AtProtectedFiltersIdentity(1, "worker_loader_observed", "utf8_compilation_text",
                "hmac_sha256_loader_configuration_v1", value.path("fingerprintScope").asText(),
                fingerprint, valid, allLoaded, filters);
    }

    private String validateRuntimeIdentityPath(String value) {
        if (value == null || value.isBlank()) return null;
        String path = value.trim();
        if (path.length() > 256 || !path.startsWith("/") || path.contains("?")
                || path.contains("#") || path.contains("..")
                || !path.contains("{pluginId}") || path.indexOf("{pluginId}") != path.lastIndexOf("{pluginId}")) {
            throw new IllegalArgumentException("INVALID_AT_CONNECTION");
        }
        try {
            URI parsed = URI.create(path.replace("{pluginId}", "1"));
            if (parsed.getRawQuery() != null || parsed.getRawFragment() != null
                    || parsed.getRawUserInfo() != null || parsed.getHost() != null
                    || !parsed.getPath().equals(path.replace("{pluginId}", "1"))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("INVALID_AT_CONNECTION");
        }
        return path;
    }

    private ResponseStatusException error(HttpStatus status, String code) { return new ResponseStatusException(status, code); }
    @PreDestroy
    public void close() { if (client != null) client.shutdownNow(); }
}
