package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.AtProtectedFilterIdentity;
import cn.har01d.atmaintenance.dto.AtProtectedFiltersIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
public class EmbeddedFilterIdentityService {
    private final SecretKeySpec key = new SecretKeySpec(randomBytes(32), "HmacSHA256");
    private final String scope = HexFormat.of().formatHex(randomBytes(16));

    public AtProtectedFiltersIdentity observe(int pluginId, String siteKey, JsonNode identity) {
        if (!identity.path("schemaVersion").isIntegralNumber()
                || !identity.path("schemaVersion").canConvertToInt()
                || identity.path("schemaVersion").asInt() != 1
                || !hash(identity.path("filtersConfigSha256"))
                || !identity.path("filterConfigurationValid").isBoolean()
                || !identity.path("allConfiguredFiltersLoaded").isBoolean()
                || !identity.path("filters").isArray() || identity.path("filters").size() > 1024) return null;
        var filters = new ArrayList<AtProtectedFilterIdentity>();
        for (JsonNode item : identity.path("filters")) {
            if (!item.isObject() || !item.path("index").isIntegralNumber()
                    || !item.path("index").canConvertToInt() || item.path("index").asInt() != filters.size() + 1
                    || !hash(item.path("configurationSha256")) || !item.path("state").isTextual()
                    || !Set.of("loaded", "not_loaded").contains(item.path("state").asText())) return null;
            boolean loaded = item.path("state").asText().equals("loaded");
            if (loaded ? !hash(item.path("sourceSha256")) : !item.path("sourceSha256").isNull()) return null;
            int index = filters.size() + 1;
            filters.add(new AtProtectedFilterIdentity(index,
                    fingerprint(pluginId, siteKey, "filter:" + index, item.path("configurationSha256").asText()),
                    loaded ? "LOADED" : "NOT_LOADED", loaded ? item.path("sourceSha256").asText() : null));
        }
        boolean valid = identity.path("filterConfigurationValid").asBoolean();
        boolean allLoaded = valid && filters.stream().allMatch(item -> item.loadState().equals("LOADED"));
        if ((!valid && !filters.isEmpty()) || identity.path("allConfiguredFiltersLoaded").asBoolean() != allLoaded) {
            return null;
        }
        return new AtProtectedFiltersIdentity(1, "worker_loader_observed", "utf8_compilation_text",
                "hmac_sha256_loader_configuration_v1", scope,
                fingerprint(pluginId, siteKey, "filters", identity.path("filtersConfigSha256").asText()),
                valid, allLoaded, filters);
    }

    private static boolean hash(JsonNode value) {
        return value.isTextual() && value.asText().matches("[a-f0-9]{64}");
    }

    private String fingerprint(int pluginId, String siteKey, String slot, String sha) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            for (String field : List.of("AT_FILTER_CONFIGURATION_V1", scope,
                    Integer.toString(pluginId), siteKey, slot, sha)) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                mac.update(bytes);
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("CONFIGURATION_FINGERPRINT_UNAVAILABLE");
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
