package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/** Explicitly public, frozen source settings. This never substitutes for private EXT. */
final class PublicSourceData {
    private PublicSourceData() { }

    static JsonNode read(JsonNode profile) throws IOException {
        JsonNode data = profile.has("publicSourceData") ? profile.get("publicSourceData")
                : JsonNodeFactory.instance.objectNode();
        validate(data);
        return data.deepCopy();
    }

    static void validate(JsonNode data) throws IOException {
        require(data != null && data.isObject() && data.size() <= 32);
        Set<String> reserved = Set.of("api", "source", "loader", "raw", "data", "extend", "preheaturl",
                "t4playtype", "playbacksourcekind", "playbacksourcekey", "playbacksourcename", "playbackconfigurl");
        for (var entry : data.properties()) {
            String key = entry.getKey(), normalized = key.replace("_", "").toLowerCase(Locale.ROOT);
            JsonNode value = entry.getValue();
            require(key.matches("[A-Za-z][A-Za-z0-9_]{0,63}") && !reserved.contains(normalized)
                    && List.of("token", "secret", "password", "cookie", "authorization", "proxy", "credential", "apikey")
                        .stream().noneMatch(normalized::contains)
                    && value.isTextual() && value.textValue().codePointCount(0, value.textValue().length()) <= 1024
                    && value.textValue().codePoints().allMatch(ch -> ch >= 32 && ch != 127
                        && (ch < 0xD800 || ch > 0xDFFF)));
        }
        require(canonical(data).length() <= 8192);
    }

    static String sha256(JsonNode data) throws IOException {
        validate(data);
        return Hashes.sha256(canonical(data));
    }

    private static String canonical(JsonNode data) {
        var fields = new TreeMap<String, String>();
        data.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue().textValue()));
        var result = new StringBuilder("{");
        fields.forEach((key, value) -> {
            if (result.length() > 1) result.append(',');
            quoted(result, key);
            result.append(':');
            quoted(result, value);
        });
        return result.append('}').toString();
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
        if (!condition) throw new IOException("PUBLIC_SOURCE_DATA_INVALID");
    }
}
