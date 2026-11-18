package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceAuthenticationEvidenceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String sha = "a".repeat(64);

    static ObjectNode jsonEvidence(ObjectMapper mapper, String sha) throws Exception {
        var evidence = mapper.createObjectNode().put("authority", "managed_source_host_observed")
                .put("inputSourceSha256", sha).put("bundleSha256", "c".repeat(64));
        evidence.set("resourceEvidence", ManagedResourceEvidenceTest.jsonFixture(mapper, sha));
        evidence.set("httpObservations", ManagedResourceEvidenceTest.jsonEvents(mapper));
        var loader = evidence.putObject("loaderValidation").put("authority", "host_loader_contract_observed")
                .put("projection", ManagedLoaderReceipt.PROJECTION).put("inputSourceSha256", sha)
                .put("inputProfileSha256", "b".repeat(64));
        loader.putArray("requiredNodes").add("R01");
        loader.putArray("nodes").addObject().put("id", "R01").put("status", "blocked")
                .put("reason", "PLAYER_URL_EMPTY");
        loader.set("calls", ManagedResourceEvidenceTest.jsonCalls(mapper));
        return evidence;
    }

    static ObjectNode legacyEvidence(ObjectMapper mapper, String sha, String text) throws Exception {
        var evidence = jsonEvidence(mapper, sha);
        var resource = (ObjectNode) evidence.path("resourceEvidence").path("resources").get(0);
        resource.remove(List.of("jsonStructure", "requestMethod", "observedDuringCall"));
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        resource.put("kind", "page").put("responseBytes", bytes).put("responseSha256", Hashes.sha256(text));
        resource.withArray("excerpts").addObject().put("offset", 0).put("text", text)
                .put("textSha256", Hashes.sha256(text));
        evidence.withObject("resourceEvidence").put("textBytes", bytes);
        var event = (ObjectNode) evidence.path("httpObservations").get(1);
        event.put("method", "GET").put("responseBytes", bytes).put("responseSha256", Hashes.sha256(text));
        return evidence;
    }

    @Test
    void safeJsonStatusDetectsAuthenticationWithoutRawBodiesOrCredentialFields() throws Exception {
        var evidence = jsonEvidence(mapper, sha);
        String before = evidence.toString();
        var result = SourceAuthenticationEvidence.detect(mapper, sha, evidence);
        assertNotNull(result);
        assertEquals("matched_json_status_structure", result.path("basis").asText());
        assertEquals("R01", result.path("targetNode").asText());
        assertEquals("play", result.path("observedDuringCall").path("method").asText());
        assertFalse(result.path("failureCauseEstablished").asBoolean());
        assertFalse(result.toString().contains("authorization"));
        assertEquals(before, evidence.toString());
    }

    @Test
    void incompleteUnboundUnrelatedSuccessfulOrPayloadStatusDoesNotRequireCredentials() throws Exception {
        for (String defect : List.of("truncated", "call", "passed", "optional", "sha", "http",
                "success", "ok", "payload", "forbidden", "missing-call")) {
            var evidence = jsonEvidence(mapper, sha);
            var resource = (ObjectNode) evidence.path("resourceEvidence").path("resources").get(0);
            var structure = resource.withObject("jsonStructure");
            switch (defect) {
                case "truncated" -> structure.put("truncated", true);
                case "call" -> {
                    ((ObjectNode) resource.path("observedDuringCall")).put("method", "detail");
                    ((ObjectNode) evidence.path("loaderValidation").path("calls").get(0)).put("method", "detail");
                }
                case "passed" -> ((ObjectNode) evidence.path("loaderValidation").path("nodes").get(0))
                        .put("status", "pass");
                case "optional" -> evidence.withObject("loaderValidation").putArray("requiredNodes");
                case "sha" -> evidence.put("inputSourceSha256", "d".repeat(64));
                case "http" -> ((ObjectNode) evidence.path("httpObservations").get(1)).put("responseComplete", false);
                case "success", "ok" -> structure.withArray("nodes").addObject()
                        .put("type", "boolean").put("safeValue", true).putArray("path").add(defect);
                case "payload" -> {
                    var nodes = structure.putArray("nodes");
                    nodes.addObject().put("type", "object").put("size", 1).putArray("path");
                    nodes.addObject().put("type", "object").put("size", 1).putArray("path").add("data");
                    nodes.addObject().put("type", "integer").put("safeValue", 401)
                            .putArray("path").add("data").add("code");
                }
                case "forbidden" -> ((ObjectNode) structure.path("nodes").get(1)).put("safeValue", 403);
                case "missing-call" -> resource.remove("observedDuringCall");
            }
            evidence.withObject("resourceEvidence").put("textBytes", mapper.writeValueAsBytes(structure).length);
            assertNull(SourceAuthenticationEvidence.detect(mapper, sha, evidence), defect);
        }
    }

    @Test
    void completeLegacyBodyUsesTheSameClassifierAndRemainsReadOnly() throws Exception {
        var evidence = legacyEvidence(mapper, sha, SourceCredentialIntegrationTest.AUTH_FAILURE);
        String before = evidence.toString();
        var result = SourceAuthenticationEvidence.detect(mapper, sha, evidence);
        assertNotNull(result);
        assertEquals("matched_complete_json_body", result.path("basis").asText());
        assertFalse(result.toString().contains("JWTVerificationError"));
        assertEquals(before, evidence.toString());
        assertNull(SourceAuthenticationEvidence.detect(mapper, sha,
                legacyEvidence(mapper, sha, "{\"success\":1,\"code\":401}")));
    }
}
