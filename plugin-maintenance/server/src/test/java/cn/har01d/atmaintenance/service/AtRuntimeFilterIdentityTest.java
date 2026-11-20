package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AtRuntimeFilterIdentityTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private AtReadClientTest fixture;
    private ObjectNode body;

    @BeforeEach
    void start() throws Exception {
        fixture = new AtReadClientTest();
        fixture.start();
        body = (ObjectNode) mapper.readTree(fixture.runtimeIdentityBody);
        body.put("sourceIdentityKind", "utf8_compilation_text");
    }

    @AfterEach
    void stop() { fixture.stop(); }

    private ObjectNode filters() {
        var value = body.putObject("filterIdentity");
        value.put("schemaVersion", 1).put("authority", "worker_loader_observed")
                .put("sourceIdentityKind", "utf8_compilation_text")
                .put("configurationSha256", "b".repeat(64)).put("configurationValid", true)
                .put("allConfiguredFiltersLoaded", true);
        value.putArray("filters");
        return value;
    }

    private ObjectNode entry(ObjectNode filters, int index) {
        return filters.withArray("filters").addObject().put("index", index)
                .put("configurationSha256", "c".repeat(64))
                .put("loadState", "LOADED").put("sourceSha256", "d".repeat(64));
    }

    private AtReadClient client() {
        if (fixture.client != null) fixture.client.close();
        fixture.client = new AtReadClient(mapper, "http://127.0.0.1:" + fixture.server.getAddress().getPort(),
                "synthetic-at", "declared", "SYNTHETIC_SESSION_SECRET", true,
                "/api/plugins/{pluginId}/runtime-identity", 3000, 2_097_152);
        fixture.runtimeIdentityBody = body.toString();
        return fixture.client;
    }

    private void rejected() {
        assertEquals("AT_RUNTIME_FILTER_IDENTITY_RESPONSE_INVALID",
                assertThrows(ResponseStatusException.class,
                        () -> client().observeRuntimeIdentity(7, "stable-key")).getReason());
    }

    @Test
    void orderedLoadedAndSkippedIdentitiesAreCopiedWithoutPrivateFields() throws Exception {
        var filters = filters();
        entry(filters, 1).put("source", "PRIVATE_SOURCE").put("extend", "PRIVATE_EXT");
        entry(filters, 2).put("loadState", "NOT_LOADED").putNull("sourceSha256");
        filters.put("allConfiguredFiltersLoaded", false).put("url", "PRIVATE_URL");
        var current = client();
        var value = current.observeRuntimeIdentity(7, "stable-key");
        assertEquals(2, value.filterIdentity().filters().size());
        assertEquals("d".repeat(64), value.filterIdentity().filters().getFirst().sourceSha256());
        assertNull(value.filterIdentity().filters().getLast().sourceSha256());
        assertFalse(value.filterIdentity().allConfiguredFiltersLoaded());
        assertEquals("utf8_compilation_text", value.filterIdentity().sourceIdentityKind());
        assertFalse(mapper.writeValueAsString(value).contains("PRIVATE_"));
        assertThrows(UnsupportedOperationException.class, () -> value.filterIdentity().filters().clear());
        assertSame(value, current.observeRuntimeIdentity(7, "stable-key"));
        assertEquals(1, fixture.requests.get());
    }

    @Test
    void missingOrNullFiltersStayUnobservedRatherThanAnEmptyLoadedList() throws Exception {
        var first = client().observeRuntimeIdentity(7, "stable-key");
        assertNull(first.filterIdentity());
        assertFalse(mapper.valueToTree(first).has("filterIdentity"));
        body.putNull("filterIdentity");
        assertNull(client().observeRuntimeIdentity(7, "stable-key").filterIdentity());
        assertTrue(first.boundary().contains("未知"));
    }

    @Test
    void explicitEmptyAndInvalidConfigurationRemainDistinct() {
        var filters = filters();
        var empty = client().observeRuntimeIdentity(7, "stable-key").filterIdentity();
        assertTrue(empty.configurationValid());
        assertTrue(empty.allConfiguredFiltersLoaded());
        assertEquals(List.of(), empty.filters());
        filters.put("configurationValid", false).put("allConfiguredFiltersLoaded", false);
        var invalid = client().observeRuntimeIdentity(7, "stable-key").filterIdentity();
        assertFalse(invalid.configurationValid());
        assertFalse(invalid.allConfiguredFiltersLoaded());
        entry(filters, 1);
        rejected();
    }

    @Test
    void malformedOrIncompleteSummaryNeverBecomesAnObservation() throws Exception {
        ObjectNode valid = filters().deepCopy();
        for (String replacement : List.of("7", "[]", "\"private\"", "true",
                "{\"schemaVersion\":1}", "null")) {
            body.set("filterIdentity", mapper.readTree(replacement));
            if (replacement.equals("null")) assertNull(client().observeRuntimeIdentity(7, "stable-key").filterIdentity());
            else rejected();
        }
        for (String field : List.of("schemaVersion", "authority", "sourceIdentityKind",
                "configurationSha256", "configurationValid", "allConfiguredFiltersLoaded", "filters")) {
            var value = valid.deepCopy();
            value.remove(field);
            body.set("filterIdentity", value);
            rejected();
        }
        for (String json : List.of("1.0", "\"1\"", "2147483648", "true")) {
            var value = valid.deepCopy();
            value.set("schemaVersion", mapper.readTree(json));
            body.set("filterIdentity", value);
            rejected();
        }
        for (String field : List.of("authority", "sourceIdentityKind", "configurationSha256")) {
            var value = valid.deepCopy();
            value.put(field, "unverified");
            body.set("filterIdentity", value);
            rejected();
        }
    }

    @Test
    void entryOrderTypesHashesAndStateConsistencyAreRequired() throws Exception {
        ObjectNode valid = filters();
        entry(valid, 1);
        valid = valid.deepCopy();
        for (String index : List.of("0", "2", "1.0", "\"1\"", "2147483648", "null")) {
            var value = valid.deepCopy();
            ((ObjectNode) value.path("filters").get(0)).set("index", mapper.readTree(index));
            body.set("filterIdentity", value);
            rejected();
        }
        for (String field : List.of("configurationSha256", "loadState", "sourceSha256")) {
            var value = valid.deepCopy();
            ((ObjectNode) value.path("filters").get(0)).put(field, "invalid");
            body.set("filterIdentity", value);
            rejected();
        }
        var value = valid.deepCopy();
        ((ObjectNode) value.path("filters").get(0)).put("loadState", "NOT_LOADED");
        value.put("allConfiguredFiltersLoaded", false);
        body.set("filterIdentity", value);
        rejected();
        ((ObjectNode) value.path("filters").get(0)).putNull("sourceSha256");
        value.put("allConfiguredFiltersLoaded", true);
        rejected();
    }

    @Test
    void listsAreBoundedAndDuplicatedPositionsAreRejected() {
        var filters = filters();
        for (int i = 1; i <= 1025; i++) entry(filters, i);
        rejected();
        filters = filters();
        entry(filters, 1);
        entry(filters, 1);
        rejected();
    }

    @Test
    void unobservedOuterLoadCannotCarryAFilterObservation() {
        filters();
        body.put("loadState", "NOT_LOADED").put("loaded", false).putNull("sourceSha256");
        body.put("sourceIdentityKind", "unobserved");
        rejected();
    }

    @Test
    void rejectedSnapshotDoesNotAuthorizeLaterBindingDuringCooldown() {
        filters().put("allConfiguredFiltersLoaded", false);
        var current = client();
        assertThrows(ResponseStatusException.class, () -> current.observeRuntimeIdentity(7, "stable-key"));
        filters();
        fixture.runtimeIdentityBody = body.toString();
        assertEquals("AT_RUNTIME_IDENTITY_COOLDOWN", assertThrows(ResponseStatusException.class,
                () -> current.observeRuntimeIdentity(7, "stable-key")).getReason());
        assertEquals(1, fixture.requests.get());
    }
}
