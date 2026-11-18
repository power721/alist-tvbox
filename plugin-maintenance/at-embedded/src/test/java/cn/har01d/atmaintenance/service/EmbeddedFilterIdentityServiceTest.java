package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedFilterIdentityServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode empty() {
        var value = mapper.createObjectNode();
        value.put("schemaVersion", 1).put("filtersConfigSha256", "a".repeat(64))
                .put("filterConfigurationValid", true).put("allConfiguredFiltersLoaded", true);
        value.putArray("filters");
        return value;
    }

    @Test
    void absenceAndMalformedReceiptsRemainUnknownInsteadOfEmptySuccess() {
        var service = new EmbeddedFilterIdentityService();
        assertNull(service.observe(1, "site", mapper.createObjectNode()));
        var missing = empty();
        missing.remove("filters");
        assertNull(service.observe(1, "site", missing));
        var invalid = empty();
        invalid.put("schemaVersion", 1.5);
        assertNull(service.observe(1, "site", invalid));
        var contradictory = empty();
        contradictory.put("allConfiguredFiltersLoaded", false);
        assertNull(service.observe(1, "site", contradictory));
        var invalidConfig = empty();
        invalidConfig.put("filterConfigurationValid", false).put("allConfiguredFiltersLoaded", false);
        assertFalse(service.observe(1, "site", invalidConfig).configurationValid());
    }

    @Test
    void scopePluginSiteAndPositionAreBoundWithoutExposingConfigurationHashes() throws Exception {
        var service = new EmbeddedFilterIdentityService();
        var value = empty();
        for (int i = 1; i <= 2; i++) {
            value.withArray("filters").addObject().put("index", i)
                    .put("configurationSha256", "b".repeat(64)).put("state", "loaded")
                    .put("sourceSha256", "c".repeat(64));
        }
        var result = service.observe(1, "site", value);
        assertEquals(result, service.observe(1, "site", value));
        assertNotEquals(result.configurationFingerprint(), service.observe(2, "site", value).configurationFingerprint());
        assertNotEquals(result.configurationFingerprint(), service.observe(1, "other", value).configurationFingerprint());
        assertNotEquals(result.filters().getFirst().configurationFingerprint(),
                result.filters().getLast().configurationFingerprint());
        var another = new EmbeddedFilterIdentityService().observe(1, "site", value);
        assertNotEquals(result.fingerprintScope(), another.fingerprintScope());
        assertNotEquals(result.configurationFingerprint(), another.configurationFingerprint());
        assertFalse(mapper.writeValueAsString(result).contains("b".repeat(64)));
        assertThrows(UnsupportedOperationException.class, () -> result.filters().clear());
    }

    @Test
    void partialLoadsPreserveOrderAndRejectContradictoryOrMisindexedEntries() {
        var service = new EmbeddedFilterIdentityService();
        var value = empty();
        value.put("allConfiguredFiltersLoaded", false);
        var entry = value.withArray("filters").addObject().put("index", 1)
                .put("configurationSha256", "b".repeat(64)).put("state", "not_loaded").putNull("sourceSha256");
        var result = service.observe(1, "site", value);
        assertFalse(result.allConfiguredFiltersLoaded());
        assertEquals("NOT_LOADED", result.filters().getFirst().loadState());
        entry.put("sourceSha256", "c".repeat(64));
        assertNull(service.observe(1, "site", value));
        entry.putNull("sourceSha256").put("index", 2);
        assertNull(service.observe(1, "site", value));
    }
}
