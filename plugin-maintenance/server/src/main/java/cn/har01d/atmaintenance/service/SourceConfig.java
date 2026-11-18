package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded public plugin configuration, kept separate from static source identities. */
final class SourceConfig {
    private static final Set<String> RESERVED = Set.of(
            "api", "source", "loader", "raw", "data", "extend", "preheaturl",
            "t4playtype", "playbacksourcekind", "playbacksourcekey",
            "playbacksourcename", "playbackconfigurl", "proxybase");
    private static final Set<String> NUMERIC = Set.of(
            "gateway_timeout", "gateway_total_timeout", "gateway_retries",
            "detail_gateway_retries", "session_ttl_minutes");

    private SourceConfig() {
    }

    static JsonNode read(JsonNode profile) throws IOException {
        JsonNode data = profile.has("sourceConfig")
                ? profile.get("sourceConfig")
                : JsonNodeFactory.instance.objectNode();
        validate(data);
        return data.deepCopy();
    }

    static void validate(JsonNode data) throws IOException {
        require(data != null && data.isObject() && data.size() <= 32);
        for (var entry : data.properties()) {
            String key = entry.getKey();
            String normalized = key.replace("_", "").toLowerCase(Locale.ROOT);
            JsonNode value = entry.getValue();
            require(key.matches("[A-Za-z][A-Za-z0-9_]{0,63}") && !RESERVED.contains(normalized));
            require(List.of("token", "secret", "password", "cookie", "authorization",
                    "credential", "apikey").stream().noneMatch(normalized::contains));
            if (key.equals("proxy_gateway")) {
                require(value.isTextual() && (value.textValue().startsWith("http://")
                                || value.textValue().startsWith("https://"))
                        && !value.textValue().matches(".*[@?#\\r\\n].*")
                        && value.textValue().codePointCount(0, value.textValue().length()) <= 1024);
            } else if (NUMERIC.contains(key)) {
                require((value.isIntegralNumber() && value.canConvertToInt()
                                && value.intValue() >= 0 && value.intValue() <= 100000));
            } else if (key.equals("subtitle_enabled")) {
                require(value.isBoolean());
            } else {
                require(value.isTextual()
                        && !normalized.contains("proxy")
                        && value.textValue().codePointCount(0, value.textValue().length()) <= 1024
                        && value.textValue().codePoints().allMatch(ch -> ch >= 32 && ch != 127
                        && (ch < 0xD800 || ch > 0xDFFF)));
            }
        }
        require(canonical(data).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 8192);
    }

    static String sha256(JsonNode data) throws IOException {
        validate(data);
        return Hashes.sha256(canonical(data));
    }

    private static String canonical(JsonNode value) throws IOException {
        if (value.isObject()) {
            List<String> keys = new ArrayList<>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.sort(Comparator.naturalOrder());
            StringBuilder result = new StringBuilder("{");
            for (String key : keys) {
                if (result.length() > 1) result.append(',');
                quoted(result, key);
                result.append(':').append(canonical(value.get(key)));
            }
            return result.append('}').toString();
        }
        if (value.isTextual()) {
            StringBuilder result = new StringBuilder();
            quoted(result, value.textValue());
            return result.toString();
        }
        if (value.isBoolean()) return value.booleanValue() ? "true" : "false";
        if (value.isIntegralNumber()) return value.toString();
        throw new IOException("SOURCE_CONFIG_INVALID");
    }

    private static void quoted(StringBuilder result, String value) {
        result.append('"');
        for (char ch : value.toCharArray()) {
            if (ch == '"' || ch == '\\') result.append('\\').append(ch);
            else if (ch >= 127) result.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
            else result.append(ch);
        }
        result.append('"');
    }

    private static void require(boolean condition) throws IOException {
        if (!condition) throw new IOException("SOURCE_CONFIG_INVALID");
    }
}
