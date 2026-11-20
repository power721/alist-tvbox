package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManagedResourceEvidenceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    static ObjectNode fixture(ObjectMapper mapper, String sourceSha) {
        var value = mapper.createObjectNode().put("schemaVersion", 1)
                .put("authority", "host_public_resource_observed").put("artifactSha256", sourceSha)
                .put("targetNode", "C09").put("textByteLimit", 18000).put("fetchLimit", 8)
                .put("sourceCodeExecuted", false).put("businessVerdictTrusted", false)
                .put("collectionStatus", "observed").put("textBytes", 12);
        value.putObject("image").put("urlSha256", "1".repeat(64)).put("responseStatus", 200)
                .put("responseBytes", 100).put("responseSha256", "2".repeat(64))
                .put("contentType", "image/jpeg").put("prefixHex", "ab".repeat(64))
                .put("suffixHex", "ab".repeat(32)).put("signature", "unknown");
        var rows = value.putArray("resources");
        rows.addObject().put("kind", "page").put("urlSha256", "3".repeat(64)).putNull("parentUrlSha256")
                .put("responseSha256", "4".repeat(64)).put("responseBytes", 100).putArray("excerpts");
        rows.addObject().put("kind", "script").put("urlSha256", "5".repeat(64))
                .put("parentUrlSha256", "3".repeat(64)).put("responseSha256", "6".repeat(64))
                .put("responseBytes", 100).putArray("excerpts").addObject().put("offset", 10)
                .put("text", "decrypt(x); ").put("textSha256", Hashes.sha256("decrypt(x); "));
        value.putArray("failures");
        return value;
    }

    static com.fasterxml.jackson.databind.node.ArrayNode events(ObjectMapper mapper) {
        var events = mapper.createArrayNode();
        for (int i : List.of(1, 3, 5))
            events.addObject().put("method", "GET").put("requestUrlSha256", Integer.toString(i).repeat(64))
                    .put("responseSha256", Integer.toString(i + 1).repeat(64)).put("responseBytes", 100)
                    .put("responseStatus", 200).put("responseComplete", true).put("pathSha256", "a".repeat(64))
                    .put("elapsedMilliseconds", 1);
        return events;
    }

    static ObjectNode jsonFixture(ObjectMapper mapper, String sha) throws Exception {
        var value = fixture(mapper, sha).put("targetNode", "R01").putNull("image");
        var row = value.putArray("resources").addObject().put("kind", "api_json")
                .put("urlSha256", "3".repeat(64)).putNull("parentUrlSha256")
                .put("responseSha256", "4".repeat(64)).put("responseBytes", 100)
                .put("responseStatus", 200).put("requestMethod", "POST");
        row.putArray("excerpts");
        row.set("observedDuringCall", jsonCalls(mapper).get(0));
        var structure = row.putObject("jsonStructure").put("truncated", false);
        var nodes = structure.putArray("nodes");
        nodes.addObject().put("type", "object").put("size", 3).putArray("path");
        nodes.addObject().put("type", "integer").put("safeValue", 401).putArray("path").add("code");
        nodes.addObject().put("type", "string").put("byteLength", 64).put("shape", "base64_like")
                .putArray("path").add("data");
        nodes.addObject().put("type", "redacted").putArray("path").add("authorization");
        value.put("textBytes", mapper.writeValueAsBytes(structure).length);
        return value;
    }

    static com.fasterxml.jackson.databind.node.ArrayNode jsonCalls(ObjectMapper mapper) {
        return mapper.createArrayNode().add(mapper.createObjectNode().put("id", 7).put("method", "play")
                .put("inputSha256", "7".repeat(64)));
    }

    static com.fasterxml.jackson.databind.node.ArrayNode jsonEvents(ObjectMapper mapper) {
        var events = events(mapper);
        ((ObjectNode) events.get(1)).put("method", "POST").put("responseContentType", "application/json");
        return events;
    }

    static ObjectNode playerFixture(ObjectMapper mapper, String sha) throws Exception {
        var value = jsonFixture(mapper, sha);
        value.putObject("playerContext").put("detailCallId", 5).put("playCallId", 7);
        var structure = value.path("resources").get(0).path("jsonStructure");
        var leaf = (ObjectNode) structure.path("nodes").get(2);
        leaf.put("shape", "json_like");
        var embedded = leaf.putObject("embeddedJson").put("truncated", false).putArray("nodes");
        embedded.addObject().put("type", "object").put("size", 1).putArray("path");
        var url = embedded.addObject().put("type", "string").put("byteLength", 55).put("shape", "text");
        url.putArray("path").add("url");
        url.putObject("playerValueMatches").put("selectedIdentifier", "exact").put("returnedUrl", "percent_decode");
        value.put("textBytes", mapper.writeValueAsBytes(structure).length);
        return value;
    }

    static com.fasterxml.jackson.databind.node.ArrayNode playerCalls(ObjectMapper mapper) {
        var calls = mapper.createArrayNode();
        calls.addObject().put("id", 5).put("method", "detail").put("ok", true).put("inputSha256", "5".repeat(64));
        calls.add(((ObjectNode) jsonCalls(mapper).get(0)).deepCopy().put("ok", true));
        return calls;
    }

    @Test
    void playerValueMatchesRequireSuccessfulExactCallContextAndRedactedStringFields() throws Exception {
        var value = playerFixture(mapper, "a".repeat(64));
        var host = new ManagedResourceEvidence(mapper);
        assertEquals(value, host.project(value, "a".repeat(64), jsonEvents(mapper), playerCalls(mapper)));
        for (String defect : List.of("context", "order", "failed", "call", "role", "transform", "private")) {
            var bad = value.deepCopy();
            var calls = playerCalls(mapper);
            var row = (ObjectNode) bad.path("resources").get(0);
            var field = (ObjectNode) row.at("/jsonStructure/nodes/2/embeddedJson/nodes/1");
            switch (defect) {
                case "context" -> bad.remove("playerContext");
                case "order" -> bad.withObject("playerContext").put("detailCallId", 7);
                case "failed" -> ((ObjectNode) calls.get(0)).put("ok", false);
                case "call" -> row.withObject("observedDuringCall").put("id", 5).put("method", "detail")
                        .put("inputSha256", "7".repeat(64));
                case "role" -> field.withObject("playerValueMatches").put("rawUrl", "exact");
                case "transform" -> field.withObject("playerValueMatches").put("returnedUrl", "eval");
                case "private" -> field.putArray("path").add("accessToken");
            }
            bad.put("textBytes", mapper.writeValueAsBytes(row.path("jsonStructure")).length);
            assertThrows(IOException.class, () -> host.project(bad, "a".repeat(64), jsonEvents(mapper), calls), defect);
        }
    }

    @Test
    void projectsOnlyHttpAndActionBoundJsonStructureWithoutRawValues() throws Exception {
        var value = jsonFixture(mapper, "a".repeat(64));
        assertEquals(value, new ManagedResourceEvidence(mapper)
                .project(value, "a".repeat(64), jsonEvents(mapper), jsonCalls(mapper)));
        for (String defect : List.of("method", "body", "call", "raw", "secret", "parent", "status", "budget", "path")) {
            var bad = value.deepCopy();
            var events = jsonEvents(mapper);
            var row = (ObjectNode) bad.path("resources").get(0);
            var nodes = row.path("jsonStructure").path("nodes");
            switch (defect) {
                case "method" -> ((ObjectNode) events.get(1)).put("method", "GET");
                case "body" -> ((ObjectNode) events.get(1)).put("responseSha256", "f".repeat(64));
                case "call" -> ((ObjectNode) row.path("observedDuringCall")).put("method", "detail");
                case "raw" -> ((ObjectNode) nodes.get(2)).put("value", "PRIVATE");
                case "secret" -> ((ObjectNode) nodes.get(3)).put("type", "string")
                        .put("byteLength", 10).put("shape", "text");
                case "parent" -> row.put("parentUrlSha256", "f".repeat(64));
                case "status" -> ((ObjectNode) nodes.get(2)).put("safeValue", "PRIVATE");
                case "budget" -> bad.put("textBytes", bad.path("textBytes").asInt() + 1);
                case "path" -> ((ObjectNode) nodes.get(2)).putArray("path").add("missing").add("data");
            }
            assertThrows(IOException.class, () -> new ManagedResourceEvidence(mapper)
                    .project(bad, "a".repeat(64), events, jsonCalls(mapper)), defect);
        }
    }

    @Test
    void embeddedPrettyPrintingDoesNotChangeTheExecutorTextBudget() throws Exception {
        var value = jsonFixture(mapper, "a".repeat(64));
        var embeddedMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        assertEquals(value, new ManagedResourceEvidence(embeddedMapper)
                .project(value, "a".repeat(64), jsonEvents(mapper), jsonCalls(mapper)));
        assertTrue(embeddedMapper.isEnabled(SerializationFeature.INDENT_OUTPUT));
        value.put("textBytes", value.path("textBytes").asInt() + 1);
        assertThrows(IOException.class, () -> new ManagedResourceEvidence(embeddedMapper)
                .project(value, "a".repeat(64), jsonEvents(mapper), jsonCalls(mapper)));
    }

    @Test
    void embeddedJsonAllowsOneRedactedLevelAndPreservesTheCombinedBudget() throws Exception {
        var value = jsonFixture(mapper, "a".repeat(64));
        var structure = value.path("resources").get(0).path("jsonStructure");
        var leaf = (ObjectNode) structure.path("nodes").get(2);
        leaf.put("shape", "json_like").put("byteLength", 100);
        var embedded = leaf.putObject("embeddedJson").put("truncated", false);
        var nodes = embedded.putArray("nodes");
        nodes.addObject().put("type", "object").put("size", 2).putArray("path");
        nodes.addObject().put("type", "string").put("byteLength", 20).put("shape", "json_like")
                .putArray("path").add("nested");
        nodes.addObject().put("type", "redacted").putArray("path").add("cookie");
        value.put("textBytes", mapper.writeValueAsBytes(structure).length);
        var host = new ManagedResourceEvidence(mapper);
        assertEquals(value, host.project(value, "a".repeat(64), jsonEvents(mapper), jsonCalls(mapper)));
        for (String defect : List.of("nested", "raw", "secret", "shape", "length", "budget")) {
            var bad = value.deepCopy();
            var badStructure = bad.path("resources").get(0).path("jsonStructure");
            var badLeaf = (ObjectNode) badStructure.path("nodes").get(2);
            var inner = badLeaf.path("embeddedJson").path("nodes");
            switch (defect) {
                case "nested" -> ((ObjectNode) inner.get(1)).set("embeddedJson", embedded.deepCopy());
                case "raw" -> ((ObjectNode) inner.get(1)).put("value", "PRIVATE");
                case "secret" -> ((ObjectNode) inner.get(2)).put("type", "string")
                        .put("byteLength", 1).put("shape", "text");
                case "shape" -> badLeaf.put("shape", "text");
                case "length" -> badLeaf.put("byteLength", 8193);
                case "budget" -> ((ObjectNode) inner.get(0)).put("size", 200);
            }
            if (!defect.equals("budget")) bad.put("textBytes", mapper.writeValueAsBytes(badStructure).length);
            assertThrows(IOException.class, () -> host.project(bad, "a".repeat(64), jsonEvents(mapper), jsonCalls(mapper)), defect);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "maintenance.json-replay", matches = ".+")
    void pythonProducerEvidenceProjectsThroughTheJavaHostUnchanged() throws Exception {
        var replay = mapper.readTree(Files.readAllBytes(Path.of(System.getProperty("maintenance.json-replay"))));
        var value = replay.path("resourceEvidence");
        assertEquals(value, new ManagedResourceEvidence(mapper).project(value, "a".repeat(64),
                replay.path("httpObservations"), replay.path("calls")));
        assertFalse(value.toString().contains("PRIVATE"));
    }

    @Test
    @EnabledIfSystemProperty(named = "maintenance.player-values-replay", matches = ".+")
    void pythonPlayerValueProducerProjectsThroughTheJavaHostUnchanged() throws Exception {
        var replay = mapper.readTree(Files.readAllBytes(Path.of(System.getProperty("maintenance.player-values-replay"))));
        var value = replay.path("resourceEvidence");
        assertEquals(value, new ManagedResourceEvidence(mapper).project(value, "a".repeat(64),
                replay.path("httpObservations"), replay.path("calls")));
        assertTrue(value.has("playerContext"));
        assertTrue(value.toString().contains("playerValueMatches"));
        assertFalse(value.toString().contains("PRIVATE"));
    }

    @Test
    void projectsOnlyBoundedSourceAndHttpBoundPublicEvidence() throws Exception {
        var input = fixture(mapper, "a".repeat(64));
        assertEquals(input, new ManagedResourceEvidence(mapper).project(input, "a".repeat(64), events(mapper), mapper.createArrayNode()));
        assertFalse(input.path("businessVerdictTrusted").asBoolean());
    }

    @Test
    void projectsInitializationAndRecommendationEvidenceWithoutRelaxingHttpBinding() throws Exception {
        for (String target : List.of("S06", "C02")) {
            var value = fixture(mapper, "a".repeat(64)).put("targetNode", target).putNull("image");
            assertEquals(value, new ManagedResourceEvidence(mapper)
                    .project(value, "a".repeat(64), events(mapper), mapper.createArrayNode()));
            var unmatched = events(mapper);
            ((ObjectNode) unmatched.get(1)).put("responseComplete", false);
            assertThrows(IOException.class, () -> new ManagedResourceEvidence(mapper)
                    .project(value, "a".repeat(64), unmatched, mapper.createArrayNode()));
        }
        var unsupported = fixture(mapper, "a".repeat(64)).put("targetNode", "C04").putNull("image");
        assertThrows(IOException.class, () -> new ManagedResourceEvidence(mapper)
                .project(unsupported, "a".repeat(64), events(mapper), mapper.createArrayNode()));
    }

    @Test
    void projectsPageStructureWithoutRequiringAnImageAndKeepsHttpBinding() throws Exception {
        var value = fixture(mapper, "a".repeat(64));
        value.put("targetNode", "C03").putNull("image");
        assertEquals(value, new ManagedResourceEvidence(mapper).project(value, "a".repeat(64), events(mapper), mapper.createArrayNode()));
        var events = events(mapper);
        ((ObjectNode) events.get(1)).put("responseComplete", false);
        assertThrows(IOException.class, () -> new ManagedResourceEvidence(mapper)
                .project(value, "a".repeat(64), events, mapper.createArrayNode()));
        value.put("targetNode", "C09");
        assertThrows(IOException.class, () -> new ManagedResourceEvidence(mapper)
                .project(value, "a".repeat(64), events(mapper), mapper.createArrayNode()));
    }

    @Test
    void projectsPlaybackPagesAndScriptsSharingTheExistingTextBudget() throws Exception {
        var value = fixture(mapper, "a".repeat(64));
        value.put("targetNode", "R01").putNull("image").put("textBytes", 18000);
        var rows = value.putArray("resources");
        var events = mapper.createArrayNode();
        int[] sizes = {34992, 76322, 47017, 52829, 85000, 85000};
        for (int i = 0; i < sizes.length; i++) {
            String url = Integer.toString(i + 1).repeat(64);
            String response = Integer.toString(i + 2).repeat(64);
            String prefix = i < 4 ? "var player_config={url:'observed'};" : "player.setup({file:'observed'});";
            String text = prefix + "x".repeat((i < 4 ? 1600 : 5800) - prefix.length());
            var row = rows.addObject().put("kind", i < 4 ? "page" : "script")
                    .put("urlSha256", url).put("responseSha256", response)
                    .put("responseBytes", sizes[i]);
            if (i < 4) row.putNull("parentUrlSha256");
            else row.put("parentUrlSha256", "1".repeat(64));
            row.putArray("excerpts").addObject().put("offset", 18000)
                    .put("text", text).put("textSha256", Hashes.sha256(text));
            events.addObject().put("method", "GET").put("requestUrlSha256", url)
                    .put("responseSha256", response).put("responseBytes", sizes[i])
                    .put("responseStatus", 200).put("responseComplete", true);
        }
        assertEquals(value, new ManagedResourceEvidence(mapper).project(value, "a".repeat(64), events, mapper.createArrayNode()));
    }

    @Test
    void rejectsChangedIdentityTextBudgetUnobservedBodiesAndExtraPrivateFields() {
        for (String defect : List.of("source", "text", "count", "parent", "bytes", "prefix", "private", "event")) {
            var value = fixture(mapper, "a".repeat(64));
            var events = events(mapper);
            switch (defect) {
                case "source" -> value.put("artifactSha256", "b".repeat(64));
                case "text" -> ((ObjectNode) value.path("resources").get(1).path("excerpts").get(0))
                        .put("text", "changed");
                case "count" -> value.put("textBytes", 18001);
                case "parent" -> ((ObjectNode) value.path("resources").get(1)).put("parentUrlSha256", "0".repeat(64));
                case "bytes" -> ((ObjectNode) value.path("image")).put("responseBytes", 101);
                case "prefix" -> ((ObjectNode) value.path("image")).put("prefixHex", "ff");
                case "private" -> value.put("authorization", "PRIVATE");
                case "event" -> ((ObjectNode) events.get(0)).put("responseComplete", false);
            }
            assertThrows(IOException.class, () -> new ManagedResourceEvidence(mapper)
                    .project(value, "a".repeat(64), events, mapper.createArrayNode()), defect);
        }
    }

    @Test
    void projectsScriptDiscoveryOnlyThroughItsValidatedParentCall() throws Exception {
        var value = fixture(mapper, "a".repeat(64)).put("targetNode", "R01").putNull("image");
        var page = (ObjectNode) value.path("resources").get(0);
        var script = (ObjectNode) value.path("resources").get(1);
        var call = page.putObject("observedDuringCall").put("id", 7).put("method", "play")
                .put("inputSha256", "7".repeat(64));
        script.set("discoveredFromCall", call.deepCopy());
        var calls = mapper.createArrayNode().add(call.deepCopy());
        assertEquals(value, new ManagedResourceEvidence(mapper).project(value, "a".repeat(64), events(mapper), calls));
        for (String defect : List.of("method", "parent-call", "body", "page-discovery")) {
            var bad = value.deepCopy();
            var badEvents = events(mapper);
            switch (defect) {
                case "method" -> ((ObjectNode) bad.path("resources").get(1).path("discoveredFromCall"))
                        .put("method", "detail");
                case "parent-call" -> ((ObjectNode) bad.path("resources").get(0)).remove("observedDuringCall");
                case "body" -> ((ObjectNode) badEvents.get(2)).put("responseComplete", false);
                case "page-discovery" -> ((ObjectNode) bad.path("resources").get(0))
                        .set("discoveredFromCall", call.deepCopy());
            }
            assertThrows(IOException.class, () -> new ManagedResourceEvidence(mapper)
                    .project(bad, "a".repeat(64), badEvents, calls), defect);
        }
    }

    @Test
    void projectsNonSuccessTextOnlyWithMatchingStatusBodyAndHostAction() throws Exception {
        for (int status : List.of(301, 403, 429, 503)) {
            var value = fixture(mapper, "a".repeat(64)).put("targetNode", "C07").putNull("image");
            var page = (ObjectNode) value.path("resources").get(0);
            page.put("responseStatus", status);
            var action = page.putObject("observedDuringCall").put("id", 4).put("method", "detail")
                    .put("inputSha256", "7".repeat(64));
            var calls = mapper.createArrayNode().add(action.deepCopy());
            var events = events(mapper);
            ((ObjectNode) events.get(1)).put("responseStatus", status);
            assertEquals(value, new ManagedResourceEvidence(mapper).project(value, "a".repeat(64), events, calls));
            assertFalse(value.path("businessVerdictTrusted").asBoolean());
            for (String defect : List.of("status", "body", "incomplete", "call-id", "call-method", "call-input")) {
                var badEvents = events.deepCopy();
                var badCalls = calls.deepCopy();
                switch (defect) {
                    case "status" -> ((ObjectNode) badEvents.get(1)).put("responseStatus", 200);
                    case "body" -> ((ObjectNode) badEvents.get(1)).put("responseSha256", "8".repeat(64));
                    case "incomplete" -> ((ObjectNode) badEvents.get(1)).put("responseComplete", false);
                    case "call-id" -> ((ObjectNode) badCalls.get(0)).put("id", 5);
                    case "call-method" -> ((ObjectNode) badCalls.get(0)).put("method", "home");
                    case "call-input" -> ((ObjectNode) badCalls.get(0)).put("inputSha256", "9".repeat(64));
                }
                assertThrows(IOException.class, () -> new ManagedResourceEvidence(mapper)
                        .project(value, "a".repeat(64), badEvents, badCalls), defect);
            }
        }
    }
}
