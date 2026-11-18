package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.MediaTarget;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URI;
import java.util.*;

/** Explicit administrator origin allowlist, applied to every requested resource. */
@Service
public class MediaNetworkPolicy {
    public static final int MAX_REQUESTS = 12;
    public static final int MAX_RESPONSE_BYTES = 65_536;
    public static final int MAX_TOTAL_BYTES = 524_288;
    public static final int TOTAL_MILLIS = 15_000;
    public static final int REQUEST_MILLIS = 3_000;
    public static final int MAX_REDIRECTS = 3;
    private static final Set<String> HEADER_NAMES = Set.of("user-agent", "referer", "origin", "cookie", "authorization");
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final boolean allowHttp;
    private final Set<String> origins;
    private final ObjectNode snapshot;

    public MediaNetworkPolicy(ObjectMapper mapper,
            @Value("${maintenance.media.enabled:false}") boolean enabled,
            @Value("${maintenance.media.allowed-origins:}") String origins,
            @Value("${maintenance.media.allow-http:false}") boolean allowHttp) throws IOException {
        this.mapper = mapper;
        this.enabled = enabled;
        this.allowHttp = allowHttp;
        Set<String> parsed = new TreeSet<>();
        for (String entry : origins.split(",")) {
            if (entry.isBlank()) continue;
            URI uri = parseUri(entry.trim());
            if (uri.getRawQuery() != null || !Set.of("", "/").contains(uri.getRawPath()))
                throw new IllegalArgumentException("INVALID_MEDIA_ORIGIN");
            parsed.add(origin(uri));
        }
        this.origins = Set.copyOf(parsed);
        snapshot = mapper.createObjectNode();
        snapshot.put("schemaVersion", 1);
        snapshot.put("engineRevision", "bounded-media-v3-aes");
        Map<String, String> engineFiles = new TreeMap<>();
        for (String name : List.of("MediaProbeEngine", "MediaProbeEngine$Session", "MediaProbeEngine$1",
                "HlsInspector", "HlsAes128", "HlsPlaylist", "HlsKey",
                "TsInspector", "TsInspector$Packet", "TsInspector$Sections",
                "MediaPrefixBody", "MediaNetworkPolicy", "MediaBudgetStore", "MediaBudgetPolicy",
                "MediaProbeStore", "MediaProbeService", "MediaProbeRepository", "MediaBudgetRepository",
                "MediaHostBudgetRepository", "MediaProbeRun", "MediaBudget", "MediaHostBudget")) {
            engineFiles.put(name + ".class", Hashes.sha256(new ClassPathResource(
                    classLocation(name)).getContentAsByteArray()));
        }
        snapshot.set("engineClasses", mapper.valueToTree(engineFiles));
        snapshot.put("enabled", enabled);
        snapshot.put("allowHttp", allowHttp);
        snapshot.put("allowedOriginCount", parsed.size());
        snapshot.put("allowedOriginsSha256", Hashes.sha256(String.join("\n", parsed)));
        snapshot.put("maxRequests", MAX_REQUESTS);
        snapshot.put("maxResponseBytes", MAX_RESPONSE_BYTES);
        snapshot.put("maxRetainedBodyBytes", MAX_TOTAL_BYTES);
        snapshot.put("totalMillis", TOTAL_MILLIS);
        snapshot.put("requestMillis", REQUEST_MILLIS);
        snapshot.put("maxRedirects", MAX_REDIRECTS);
        snapshot.put("authority", "operator_supplied_player");
        snapshot.put("sourceHealthEligible", false);
    }

    public ObjectNode snapshot() { return snapshot.deepCopy(); }
    public String sha256() { return Hashes.sha256(snapshot.toString()); }
    public String blockedReason() {
        return !enabled ? "MEDIA_EXECUTION_DISABLED" : origins.isEmpty() ? "MEDIA_ORIGINS_REQUIRED" : null;
    }

    public MediaTarget target(JsonNode player) {
        if (!player.isObject() || !zero(player.path("parse")) || !zero(player.path("jx"))
                || player.has("playUrl") && (!player.path("playUrl").isTextual() || !player.path("playUrl").asText().isEmpty())
                || !player.path("url").isTextual()) throw new IllegalArgumentException("MEDIA_DIRECT_PLAYER_REQUIRED");
        URI uri = parseUri(player.path("url").asText());
        JsonNode headers = player.path("header");
        if (headers.isTextual()) {
            try {
                headers = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(headers.asText());
                if (headers == null || !headers.isObject()) throw new IllegalArgumentException("INVALID_MEDIA_HEADERS");
            } catch (java.io.IOException error) {
                throw new IllegalArgumentException("INVALID_MEDIA_HEADERS");
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (!(headers.isMissingNode() || headers.isNull())) {
            if (!headers.isObject() || headers.size() > HEADER_NAMES.size())
                throw new IllegalArgumentException("INVALID_MEDIA_HEADERS");
            int bytes = 0;
            for (var field : headers.properties()) {
                String name = field.getKey().toLowerCase(Locale.ROOT);
                JsonNode value = field.getValue();
                if (!HEADER_NAMES.contains(name) || values.containsKey(name) || !value.isTextual()
                        || !value.asText().matches("[\\x20-\\x7e]{0,8192}"))
                    throw new IllegalArgumentException("INVALID_MEDIA_HEADERS");
                bytes += name.length() + value.asText().length();
                values.put(name, value.asText());
            }
            if (bytes > 16_384) throw new IllegalArgumentException("INVALID_MEDIA_HEADERS");
        }
        return new MediaTarget(uri, Map.copyOf(values));
    }

    public void check(URI uri) {
        parseUri(uri.toASCIIString());
        if (!origins.contains(origin(uri))) throw new IllegalArgumentException("MEDIA_ORIGIN_REFUSED");
    }

    URI parseUri(String text) {
        try {
            URI uri = URI.create(text);
            if (text.length() > 16_384 || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || !("https".equals(uri.getScheme()) || allowHttp && "http".equals(uri.getScheme())))
                throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("INVALID_MEDIA_URI");
        }
    }

    static String origin(URI uri) {
        int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
        return uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private boolean zero(JsonNode value) {
        return value.isMissingNode() || value.isIntegralNumber() && value.canConvertToInt() && value.asInt() == 0
                || value.isTextual() && value.asText().equals("0");
    }

    private static String classLocation(String name) {
        String packageName = Set.of("MediaProbeRun", "MediaBudget", "MediaHostBudget").contains(name)
                ? "entity/" : Set.of("MediaProbeRepository", "MediaBudgetRepository",
                "MediaHostBudgetRepository").contains(name) ? "repository/"
                : Set.of("HlsPlaylist", "HlsKey").contains(name) ? "dto/" : "service/";
        return "cn/har01d/atmaintenance/" + packageName + name + ".class";
    }
}
