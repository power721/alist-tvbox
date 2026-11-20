package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded public resource excerpts remain diagnostic data, never executable instructions. */
final class ManagedResourceEvidence {
    private static final Pattern PRIVATE_FIELD = Pattern.compile(
            "token|cookie|authorization|password|passwd|secret|session|credential|api.?key", Pattern.CASE_INSENSITIVE);
    private static final Set<String> STATUS_FIELDS = Set.of("code", "status", "success", "ok", "error", "errno", "ret", "retcode");
    private static final Set<String> STATUS_VALUES = Set.of("ok", "success", "error", "failed", "failure",
            "unauthorized", "forbidden", "login_required", "token_expired", "pending");
    private final ObjectWriter compactJson;

    ManagedResourceEvidence(ObjectMapper mapper) {
        compactJson = mapper.writer().without(SerializationFeature.INDENT_OUTPUT);
    }

    ObjectNode project(JsonNode input, String sourceSha, JsonNode events, JsonNode calls) throws IOException {
        var inputFields = new HashSet<>(Set.of("schemaVersion", "authority", "artifactSha256", "targetNode", "image",
                    "resources", "failures", "textByteLimit", "fetchLimit", "sourceCodeExecuted",
                    "businessVerdictTrusted", "collectionStatus"));
        if (input.has("textBytes")) inputFields.add("textBytes");
        if (input.has("playerContext")) inputFields.add("playerContext");
        require(fields(input, inputFields));
        require(number(input.path("schemaVersion"), 1, 1)
                && input.path("authority").asText().equals("host_public_resource_observed")
                && input.path("artifactSha256").asText().equals(sourceSha)
                && Set.of("S06", "C01", "C02", "C03", "C07", "C09", "C10", "R01")
                    .contains(input.path("targetNode").asText())
                && number(input.path("textByteLimit"), 18000, 18000)
                && number(input.path("fetchLimit"), 8, 8)
                && flag(input, "sourceCodeExecuted", false) && flag(input, "businessVerdictTrusted", false)
                && Set.of("not_configured", "observed", "unavailable")
                    .contains(input.path("collectionStatus").asText())
                && input.path("resources").isArray() && input.path("failures").isArray()
                && input.path("resources").size() + input.path("failures").size() <= 8);
        JsonNode playerContext = input.path("playerContext");
        if (input.has("playerContext")) {
            require(input.path("targetNode").asText().equals("R01")
                    && fields(playerContext, Set.of("detailCallId", "playCallId"))
                    && number(playerContext.path("detailCallId"), 1, 32)
                    && number(playerContext.path("playCallId"), playerContext.path("detailCallId").asInt() + 1, 32));
            for (String method : Set.of("detail", "play"))
                require(calls.isArray() && calls.valueStream().anyMatch(call ->
                        call.path("id").equals(playerContext.path(method + "CallId"))
                            && method.equals(call.path("method").asText()) && flag(call, "ok", true)));
        }
        JsonNode image = input.path("image");
        if (!input.path("targetNode").asText().equals("C09")) {
            require(image.isNull());
        } else {
            require(fields(image, Set.of("urlSha256", "responseStatus", "responseBytes", "responseSha256",
                    "contentType", "prefixHex", "suffixHex", "signature"))
                && observed(image, events, "responseStatus")
                && hex(image.path("prefixHex"), Math.min(64, image.path("responseBytes").asInt()))
                && hex(image.path("suffixHex"), Math.min(32, image.path("responseBytes").asInt()))
                && image.path("contentType").isTextual()
                && image.path("contentType").asText().matches("[a-z0-9.+/-]{0,100}")
                && Set.of("jpeg", "png", "webp", "gif", "text", "unknown")
                    .contains(image.path("signature").asText()));
        }
        var safe = ((ObjectNode) input).deepCopy();
        var seen = new HashSet<String>();
        var discoveryCalls = new HashMap<String, JsonNode>();
        int textBytes = 0;
        for (JsonNode row : input.path("resources")) {
            var rowFields = new HashSet<>(Set.of("kind", "urlSha256", "parentUrlSha256", "responseSha256",
                    "responseBytes", "excerpts"));
            if (row.has("responseStatus")) rowFields.add("responseStatus");
            if (row.has("observedDuringCall")) rowFields.add("observedDuringCall");
            if (row.has("discoveredFromCall")) rowFields.add("discoveredFromCall");
            if (row.has("playlistObservation")) rowFields.add("playlistObservation");
            boolean json = row.path("kind").asText().equals("api_json");
            if (json) rowFields.addAll(Set.of("requestMethod", "jsonStructure"));
            require(fields(row, rowFields)
                    && Set.of("page", "script", "api_json").contains(row.path("kind").asText())
                    && (!row.has("responseStatus") || number(row.path("responseStatus"), 100, 599))
                    && observed(row, events, row.has("responseStatus") ? "responseStatus" : null)
                    && !seen.contains(row.path("urlSha256").asText())
                    && (row.path("kind").asText().equals("page") || json && row.path("parentUrlSha256").isNull()
                        ? row.path("parentUrlSha256").isNull()
                        : sha(row.path("parentUrlSha256")) && seen.contains(row.path("parentUrlSha256").asText()))
                    && row.path("excerpts").isArray() && row.path("excerpts").size() <= 48);
            if (json) {
                require(row.has("responseStatus") && row.path("excerpts").isEmpty()
                        && Set.of("GET", "POST").contains(row.path("requestMethod").asText())
                        && !row.has("discoveredFromCall"));
                JsonNode call = row.path("observedDuringCall");
                boolean playerValues = Set.of("detail", "play").contains(call.path("method").asText())
                        && playerContext.has(call.path("method").asText() + "CallId")
                        && call.path("id").equals(playerContext.path(call.path("method").asText() + "CallId"));
                validateStructure(row.path("jsonStructure"), true, playerValues);
                textBytes += compactJson.writeValueAsBytes(row.path("jsonStructure")).length;
                require(textBytes <= 18000);
            }
            if (row.has("playlistObservation")) {
                JsonNode playlist = row.path("playlistObservation");
                var playlistFields = new HashSet<>(Set.of("policy", "complete", "groups", "maxEpisodes",
                        "groupEpisodeCounts", "playlistSha256", "outputMatchesPage"));
                if (playlist.has("linkCoverage")) playlistFields.add("linkCoverage");
                require(input.path("targetNode").asText().equals("C10")
                        && row.path("kind").asText().equals("page") && row.path("responseStatus").asInt() == 200
                        && row.path("observedDuringCall").path("method").asText().equals("detail")
                        && fields(playlist, playlistFields)
                        && playlist.path("policy").asText().equals("indexed_playlist_links_v1")
                        && playlist.path("complete").isBoolean() && playlist.path("outputMatchesPage").isBoolean()
                        && (!playlist.path("outputMatchesPage").asBoolean() || playlist.path("complete").asBoolean())
                        && sha(playlist.path("playlistSha256")) && number(playlist.path("groups"), 1, 128)
                        && playlist.path("groupEpisodeCounts").isArray()
                        && playlist.path("groupEpisodeCounts").size() == playlist.path("groups").asInt());
                int maximum = 0;
                for (JsonNode count : playlist.path("groupEpisodeCounts")) {
                    require(number(count, playlist.path("complete").asBoolean() ? 1 : 0, 10000));
                    maximum = Math.max(maximum, count.asInt());
                }
                require(number(playlist.path("maxEpisodes"), maximum, maximum));
                if (playlist.has("linkCoverage")) {
                    JsonNode coverage = playlist.path("linkCoverage");
                    require(playlist.path("complete").asBoolean()
                            && fields(coverage, Set.of("pageUniqueLinks", "outputUniqueLinks", "missingPageLinks", "extraOutputLinks"))
                            && number(coverage.path("pageUniqueLinks"), 1, 1280000)
                            && number(coverage.path("outputUniqueLinks"), 1, 1280000)
                            && number(coverage.path("missingPageLinks"), 0, coverage.path("pageUniqueLinks").asInt())
                            && number(coverage.path("extraOutputLinks"), 0, coverage.path("outputUniqueLinks").asInt())
                            && coverage.path("pageUniqueLinks").asInt() - coverage.path("missingPageLinks").asInt()
                                == coverage.path("outputUniqueLinks").asInt() - coverage.path("extraOutputLinks").asInt()
                            && (!playlist.path("outputMatchesPage").asBoolean()
                                || coverage.path("missingPageLinks").asInt() + coverage.path("extraOutputLinks").asInt() == 0));
                }
                textBytes += compactJson.writeValueAsBytes(playlist).length;
                require(textBytes <= 18000);
            }
            if (row.has("observedDuringCall")) {
                JsonNode call = row.path("observedDuringCall");
                require(fields(call, Set.of("id", "method", "inputSha256"))
                        && number(call.path("id"), 1, 32) && sha(call.path("inputSha256"))
                        && calls.isArray() && calls.valueStream().anyMatch(observed ->
                            observed.path("id").equals(call.path("id"))
                            && observed.path("method").equals(call.path("method"))
                            && observed.path("inputSha256").equals(call.path("inputSha256"))));
            }
            if (row.has("discoveredFromCall")) {
                require(row.path("kind").asText().equals("script")
                        && row.path("discoveredFromCall").equals(
                            discoveryCalls.get(row.path("parentUrlSha256").asText())));
            }
            JsonNode discoveryCall = row.has("observedDuringCall") ? row.path("observedDuringCall")
                    : row.get("discoveredFromCall");
            if (discoveryCall != null) discoveryCalls.put(row.path("urlSha256").asText(), discoveryCall);
            seen.add(row.path("urlSha256").asText());
            for (JsonNode excerpt : row.path("excerpts")) {
                require(fields(excerpt, Set.of("offset", "text", "textSha256"))
                        && number(excerpt.path("offset"), 0, row.path("responseBytes").asInt())
                        && excerpt.path("text").isTextual() && !excerpt.path("text").asText().isEmpty()
                        && sha(excerpt.path("textSha256"))
                        && Hashes.sha256(excerpt.path("text").asText()).equals(excerpt.path("textSha256").asText()));
                textBytes += excerpt.path("text").asText().getBytes(StandardCharsets.UTF_8).length;
                require(textBytes <= 18000);
            }
        }
        for (JsonNode failure : input.path("failures"))
            require(fields(failure, Set.of("urlSha256", "code")) && sha(failure.path("urlSha256"))
                    && failure.path("code").isTextual()
                    && failure.path("code").asText().matches(
                        "EGRESS_[A-Z_]{1,64}|RESOURCE_FETCH_UNAVAILABLE|RESOURCE_SCRIPT_RETURNED_HTML|RESOURCE_JSON_UNAVAILABLE"));
        require((input.has("textBytes") ? number(input.path("textBytes"), textBytes, textBytes) : textBytes == 0)
                && (input.path("collectionStatus").asText().equals("observed") == !seen.isEmpty()));
        safe.put("textBytes", textBytes);
        return safe;
    }

    private boolean observed(JsonNode row, JsonNode events, String status) {
        if (!sha(row.path("urlSha256")) || !sha(row.path("responseSha256"))
                || !number(row.path("responseBytes"), 0, 2097152)) return false;
        return events.isArray() && events.valueStream().anyMatch(event ->
                event.path("method").asText().equals(row.path("kind").asText().equals("api_json")
                    ? row.path("requestMethod").asText() : "GET")
                && event.path("requestUrlSha256").equals(row.path("urlSha256"))
                && event.path("responseSha256").equals(row.path("responseSha256"))
                && event.path("responseBytes").equals(row.path("responseBytes"))
                && event.path("responseComplete").isBoolean() && event.path("responseComplete").booleanValue()
                && (status == null ? event.path("responseStatus").asInt() == 200
                    : event.path("responseStatus").equals(row.path(status))));
    }

    private void validateStructure(JsonNode structure, boolean embedded, boolean playerValues) throws IOException {
        require(fields(structure, Set.of("nodes", "truncated")) && structure.path("truncated").isBoolean()
                && structure.path("nodes").isArray() && !structure.path("nodes").isEmpty()
                && structure.path("nodes").size() <= 48 && compactJson.writeValueAsBytes(structure).length <= 2048);
        var parents = new HashMap<String, JsonNode>();
        for (JsonNode node : structure.path("nodes")) {
            JsonNode path = node.path("path");
            String type = node.path("type").asText();
            require(path.isArray() && path.size() <= 6
                    && Set.of("object", "array", "string", "integer", "number", "boolean", "null", "redacted").contains(type)
                    && !parents.containsKey(path.toString()));
            String field = "";
            if (parents.isEmpty()) require(path.isEmpty());
            else {
                require(!path.isEmpty());
                var parentPath = ((com.fasterxml.jackson.databind.node.ArrayNode) path).deepCopy();
                JsonNode key = parentPath.remove(parentPath.size() - 1);
                JsonNode parent = parents.get(parentPath.toString());
                require(parent != null);
                if (parent.path("type").asText().equals("object")) {
                    require(key.isTextual() && key.asText().matches("[A-Za-z_][A-Za-z0-9_-]{0,47}"));
                    field = key.asText();
                } else require(parent.path("type").asText().equals("array")
                        && number(key, 0, Math.min(2, parent.path("size").asInt() - 1)));
            }
            require(!PRIVATE_FIELD.matcher(field).find() || type.equals("redacted"));
            var names = new HashSet<>(Set.of("path", "type"));
            if (type.equals("object") || type.equals("array")) {
                names.add("size");
                require(number(node.path("size"), 0, 2097152));
            } else if (type.equals("string")) {
                names.addAll(Set.of("byteLength", "shape"));
                require(number(node.path("byteLength"), 0, 2097152)
                        && Set.of("empty", "url_like", "json_like", "hex_like", "base64_like", "text")
                            .contains(node.path("shape").asText()));
                if (node.has("embeddedJson")) {
                    require(embedded && "json_like".equals(node.path("shape").asText())
                            && number(node.path("byteLength"), 0, 8192));
                    names.add("embeddedJson");
                    validateStructure(node.path("embeddedJson"), false, playerValues);
                }
                if (node.has("playerValueMatches")) {
                    names.add("playerValueMatches");
                    JsonNode matches = node.path("playerValueMatches");
                    require(playerValues && number(node.path("byteLength"), 1, 32768)
                            && matches.isObject() && !matches.isEmpty() && matches.size() <= 2);
                    for (var match : matches.properties())
                        require(Set.of("selectedIdentifier", "returnedUrl").contains(match.getKey())
                                && match.getValue().isTextual()
                                && Set.of("exact", "percent_decode", "base64_decode").contains(match.getValue().asText()));
                }
            }
            if (node.has("safeValue")) {
                names.add("safeValue");
                JsonNode value = node.path("safeValue");
                require(STATUS_FIELDS.contains(field.toLowerCase(java.util.Locale.ROOT))
                        && (type.equals("boolean") && value.isBoolean()
                            || type.equals("integer") && number(value, -999999, 999999)
                            || type.equals("string") && value.isTextual() && STATUS_VALUES.contains(value.asText())));
            }
            require(fields(node, names));
            parents.put(path.toString(), node);
        }
    }

    private static boolean fields(JsonNode value, Set<String> names) {
        return value.isObject() && value.size() == names.size() && names.stream().allMatch(value::has);
    }
    private static boolean flag(JsonNode value, String name, boolean expected) {
        return value.path(name).isBoolean() && value.path(name).booleanValue() == expected;
    }
    private static boolean sha(JsonNode value) {
        return value.isTextual() && value.asText().matches("[a-f0-9]{64}");
    }
    private static boolean hex(JsonNode value, int bytes) {
        return value.isTextual() && value.asText().length() == bytes * 2 && value.asText().matches("[a-f0-9]*");
    }
    private static boolean number(JsonNode value, int min, int max) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= min
                && value.intValue() <= max;
    }
    private static void require(boolean valid) throws IOException {
        if (!valid) throw new IOException("MANAGED_RESOURCE_EVIDENCE_INVALID");
    }
}
