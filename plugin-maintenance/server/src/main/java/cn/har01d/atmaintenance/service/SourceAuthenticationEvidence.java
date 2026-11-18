package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Authentication signals from bound host evidence, without returning response bodies or credentials. */
final class SourceAuthenticationEvidence {
    private SourceAuthenticationEvidence() { }

    static ObjectNode detect(ObjectMapper mapper, String artifactSha, JsonNode evidence) {
        if (evidence == null) return null;
        JsonNode loader = evidence.path("loaderValidation");
        JsonNode resources = evidence.path("resourceEvidence");
        String target = resources.path("targetNode").asText();
        if (artifactSha == null || !artifactSha.matches("[a-f0-9]{64}")
                || !"managed_source_host_observed".equals(evidence.path("authority").asText())
                || !artifactSha.equals(evidence.path("inputSourceSha256").asText())
                || !"host_loader_contract_observed".equals(loader.path("authority").asText())
                || !artifactSha.equals(loader.path("inputSourceSha256").asText())
                || !loader.path("requiredNodes").valueStream().anyMatch(node -> target.equals(node.asText()))
                || !loader.path("nodes").valueStream().anyMatch(node -> target.equals(node.path("id").asText())
                    && Set.of("blocked", "fail").contains(node.path("status").asText()))) return null;
        try {
            resources = new ManagedResourceEvidence(mapper).project(resources, artifactSha,
                    evidence.path("httpObservations"), loader.path("calls"));
        } catch (IOException invalidEvidence) {
            return null;
        }
        for (JsonNode resource : resources.path("resources")) {
            JsonNode call = resource.path("observedDuringCall");
            if (!call.isMissingNode() && !method(target).equals(call.path("method").asText())) continue;
            boolean rejected = false;
            String basis;
            if ("api_json".equals(resource.path("kind").asText())) {
                // A wrapper from another action or a truncated wrapper cannot establish this prerequisite.
                if (call.isMissingNode() || resource.path("jsonStructure").path("truncated").asBoolean()) continue;
                rejected = authenticationError(statusWrapper(mapper, resource.path("jsonStructure")));
                basis = "matched_json_status_structure";
            } else if ("page".equals(resource.path("kind").asText())) {
                basis = "matched_complete_json_body";
                for (JsonNode excerpt : resource.path("excerpts")) {
                    String text = excerpt.path("text").asText();
                    if (excerpt.path("offset").asInt(-1) != 0
                            || text.getBytes(StandardCharsets.UTF_8).length != resource.path("responseBytes").asInt()
                            || !Hashes.sha256(text).equals(resource.path("responseSha256").asText())) continue;
                    try {
                        JsonNode body = mapper.reader()
                                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readTree(text);
                        if (authenticationError(body)) { rejected = true; break; }
                    } catch (IOException notJson) {
                        // HTML and incomplete or textual login hints are not machine authentication errors.
                    }
                }
            } else continue;
            if (!rejected) continue;
            var finding = mapper.createObjectNode().put("targetNode", target).put("basis", basis)
                    .put("responseSha256", resource.path("responseSha256").asText())
                    .put("failureCauseEstablished", false);
            if (!call.isMissingNode()) finding.set("observedDuringCall", call.deepCopy());
            return finding;
        }
        return null;
    }

    private static String method(String node) {
        return switch (node) {
            case "C01" -> "home";
            case "C03" -> "category";
            case "C07", "C10" -> "detail";
            case "R01" -> "play";
            default -> "";
        };
    }

    private static ObjectNode statusWrapper(ObjectMapper mapper, JsonNode structure) {
        var body = mapper.createObjectNode();
        if (!"object".equals(structure.path("nodes").path(0).path("type").asText())) return body;
        for (JsonNode node : structure.path("nodes")) {
            if (!node.has("safeValue")) continue;
            JsonNode path = node.path("path");
            if (path.size() == 1 && Set.of("success", "ok", "code", "action", "status", "error")
                    .contains(path.path(0).asText())) body.set(path.path(0).asText(), node.path("safeValue"));
            else if (path.size() == 2 && "error".equals(path.path(0).asText())
                    && "code".equals(path.path(1).asText()))
                body.withObject("error").set("code", node.path("safeValue"));
        }
        return body;
    }

    private static boolean authenticationError(JsonNode body) {
        if (body == null || !body.isObject() || body.path("success").asBoolean() || body.path("ok").asBoolean()
                || body.path("success").isNumber() && body.path("success").asInt() != 0) return false;
        Set<String> codes = Set.of("401", "unauthorized", "unauthenticated", "authenticationrequired",
                "loginrequired", "notloggedin", "jwtverificationerror", "invalidtoken", "tokeninvalid",
                "tokenexpired", "expiredtoken");
        for (JsonNode code : List.of(body.path("code"), body.path("action"), body.path("status"),
                body.path("error"), body.path("error").path("code")))
            if (code.isValueNode() && codes.contains(code.asText().toLowerCase(Locale.ROOT)
                    .replace("_", "").replace("-", ""))) return true;
        return false;
    }
}
