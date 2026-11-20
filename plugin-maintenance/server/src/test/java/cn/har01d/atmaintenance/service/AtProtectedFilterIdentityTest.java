package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AtProtectedFilterIdentityTest {
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
        var value = body.putObject("protectedFilterIdentity");
        value.put("schemaVersion", 1).put("authority", "worker_loader_observed")
                .put("sourceIdentityKind", "utf8_compilation_text")
                .put("fingerprintScheme", "hmac_sha256_loader_configuration_v1")
                .put("fingerprintScope", "e".repeat(32))
                .put("configurationFingerprint", "b".repeat(64))
                .put("configurationValid", true).put("allConfiguredFiltersLoaded", true);
        value.putArray("filters");
        return value;
    }

    private ObjectNode entry(ObjectNode filters, int index) {
        return filters.withArray("filters").addObject().put("index", index)
                .put("configurationFingerprint", "c".repeat(64))
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

    private void rejected(String code) {
        assertEquals(code, assertThrows(ResponseStatusException.class,
                () -> client().observeRuntimeIdentity(7, "stable-key")).getReason());
    }

    private void rejected() { rejected("AT_RUNTIME_FILTER_IDENTITY_RESPONSE_INVALID"); }

    @Test
    void orderedProtectedSnapshotHasExplicitScopeAndCopiesOnlyAllowedFields() throws Exception {
        var filters = filters();
        entry(filters, 1).put("extend", "PRIVATE_EXT");
        entry(filters, 2).put("loadState", "NOT_LOADED").putNull("sourceSha256");
        filters.put("allConfiguredFiltersLoaded", false).put("key", "PRIVATE_KEY");
        var current = client();
        var value = current.observeRuntimeIdentity(7, "stable-key");
        assertNull(value.filterIdentity());
        var protectedIdentity = value.protectedFilterIdentity();
        assertEquals("e".repeat(32), protectedIdentity.fingerprintScope());
        assertEquals("hmac_sha256_loader_configuration_v1", protectedIdentity.fingerprintScheme());
        assertEquals("b".repeat(64), protectedIdentity.configurationFingerprint());
        assertEquals(2, protectedIdentity.filters().size());
        assertNull(protectedIdentity.filters().getLast().sourceSha256());
        assertFalse(protectedIdentity.allConfiguredFiltersLoaded());
        assertFalse(mapper.writeValueAsString(value).contains("PRIVATE_"));
        assertFalse(mapper.valueToTree(value).has("filterIdentity"));
        assertThrows(UnsupportedOperationException.class, () -> protectedIdentity.filters().clear());
        assertSame(value, current.observeRuntimeIdentity(7, "stable-key"));
        assertEquals(1, fixture.requests.get());
    }

    @Test
    void absentProtectedReceiptDoesNotTurnPrivateReceiptIntoAProtectedOne() {
        var raw = body.putObject("filterIdentity");
        raw.put("schemaVersion", 1).put("authority", "worker_loader_observed")
                .put("sourceIdentityKind", "utf8_compilation_text")
                .put("configurationSha256", "b".repeat(64)).put("configurationValid", true)
                .put("allConfiguredFiltersLoaded", true).putArray("filters");
        var observed = client().observeRuntimeIdentity(7, "stable-key");
        assertNotNull(observed.filterIdentity());
        assertNull(observed.protectedFilterIdentity());
        assertFalse(mapper.valueToTree(observed).has("protectedFilterIdentity"));
        body.remove("filterIdentity");
        body.putNull("protectedFilterIdentity");
        assertNull(client().observeRuntimeIdentity(7, "stable-key").protectedFilterIdentity());
    }

    @Test
    void schemeScopeAndRequiredMetadataCannotBeOmittedOrReinterpreted() {
        var valid = filters().deepCopy();
        for (String field : List.of("schemaVersion", "authority", "sourceIdentityKind", "fingerprintScheme",
                "fingerprintScope", "configurationFingerprint", "configurationValid",
                "allConfiguredFiltersLoaded", "filters")) {
            var value = valid.deepCopy();
            value.remove(field);
            body.set("protectedFilterIdentity", value);
            rejected();
        }
        for (String scope : List.of("", "e".repeat(31), "e".repeat(33), "E".repeat(32), "not-a-scope")) {
            var value = valid.deepCopy().put("fingerprintScope", scope);
            body.set("protectedFilterIdentity", value);
            rejected();
        }
        body.set("protectedFilterIdentity", valid.deepCopy().put("fingerprintScheme", "sha256"));
        rejected();
    }

    @Test
    void typesHashesAndUnprotectedFieldsAreRejected() throws Exception {
        var valid = filters().deepCopy();
        for (String version : List.of("1.0", "\"1\"", "true", "2147483648", "null")) {
            var value = valid.deepCopy();
            value.set("schemaVersion", mapper.readTree(version));
            body.set("protectedFilterIdentity", value);
            rejected();
        }
        for (String field : List.of("authority", "sourceIdentityKind", "configurationFingerprint")) {
            body.set("protectedFilterIdentity", valid.deepCopy().put(field, "invalid"));
            rejected();
        }
        var value = valid.deepCopy().put("configurationSha256", "f".repeat(64));
        body.set("protectedFilterIdentity", value);
        rejected();
        value.remove("configurationSha256");
        entry(value, 1).putNull("configurationSha256");
        rejected();
    }

    @Test
    void positionsAndLoadStatesAreStrictAndOrdered() throws Exception {
        var valid = filters();
        entry(valid, 1);
        valid = valid.deepCopy();
        for (String index : List.of("0", "2", "1.0", "\"1\"", "2147483648", "null")) {
            var value = valid.deepCopy();
            ((ObjectNode) value.path("filters").get(0)).set("index", mapper.readTree(index));
            body.set("protectedFilterIdentity", value);
            rejected();
        }
        for (String field : List.of("configurationFingerprint", "loadState", "sourceSha256")) {
            var value = valid.deepCopy();
            ((ObjectNode) value.path("filters").get(0)).put(field, "invalid");
            body.set("protectedFilterIdentity", value);
            rejected();
        }
    }

    @Test
    void emptyInvalidSkippedAndUnavailableSnapshotsRemainDistinct() {
        var filters = filters();
        assertTrue(client().observeRuntimeIdentity(7, "stable-key")
                .protectedFilterIdentity().allConfiguredFiltersLoaded());
        filters.put("configurationValid", false).put("allConfiguredFiltersLoaded", false);
        assertFalse(client().observeRuntimeIdentity(7, "stable-key").protectedFilterIdentity().configurationValid());
        entry(filters, 1);
        rejected();
        filters = filters();
        entry(filters, 1).put("loadState", "NOT_LOADED").putNull("sourceSha256");
        filters.put("allConfiguredFiltersLoaded", false);
        assertNull(client().observeRuntimeIdentity(7, "stable-key")
                .protectedFilterIdentity().filters().getFirst().sourceSha256());
        body.put("loadState", "NOT_LOADED").put("loaded", false).putNull("sourceSha256");
        body.put("sourceIdentityKind", "unobserved");
        rejected();
    }

    @Test
    void mixedPrivateAndProtectedReceiptsCannotSilentlyChooseASchema() {
        filters();
        body.putObject("filterIdentity");
        rejected("AT_RUNTIME_FILTER_IDENTITY_MIXED");
    }

    @Test
    void failedProtectedReceiptCannotFallBackDuringCooldown() {
        filters().put("fingerprintScope", "invalid");
        var current = client();
        assertThrows(ResponseStatusException.class, () -> current.observeRuntimeIdentity(7, "stable-key"));
        filters();
        fixture.runtimeIdentityBody = body.toString();
        assertEquals("AT_RUNTIME_IDENTITY_COOLDOWN", assertThrows(ResponseStatusException.class,
                () -> current.observeRuntimeIdentity(7, "stable-key")).getReason());
        assertEquals(1, fixture.requests.get());
    }

    @Test
    void acceptsTheExactLimitAndRejectsOversizeOrDuplicatedPositions() {
        var filters = filters();
        for (int i = 1; i <= 1024; i++) entry(filters, i);
        assertEquals(1024, client().observeRuntimeIdentity(7, "stable-key")
                .protectedFilterIdentity().filters().size());
        entry(filters, 1025);
        rejected();
        filters = filters();
        entry(filters, 1);
        entry(filters, 1);
        rejected();
    }
}
