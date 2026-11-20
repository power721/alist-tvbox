package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PublicSourceDataTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void canonicalDigestMatchesPythonAsciiJsonIncludingUnicodeAndEscapes() throws Exception {
        var data = mapper.createObjectNode().put("page_size", "20")
                .put("label", "\u6d4b\u8bd5 + & \uD83D\uDE00")
                .put("escaped", "\"\\/");
        String canonical = "{\"escaped\":\"\\\"\\\\/\",\"label\":\"\\u6d4b\\u8bd5 + & \\ud83d\\ude00\","
                + "\"page_size\":\"20\"}";
        assertEquals(Hashes.sha256(canonical), PublicSourceData.sha256(data));
        assertEquals(Hashes.sha256("{}"), PublicSourceData.sha256(mapper.createObjectNode()));
    }

    @Test
    void readingFreezesDataAndOmissionIsAnEmptyPublicConfiguration() throws Exception {
        var profile = mapper.createObjectNode();
        assertTrue(PublicSourceData.read(profile).isEmpty());
        profile.putObject("publicSourceData").put("label", "original");
        var frozen = PublicSourceData.read(profile);
        profile.withObject("publicSourceData").put("label", "changed");
        assertEquals("original", frozen.path("label").asText());
    }

    @Test
    void privateReservedMalformedAndNonStringSettingsAreRejected() {
        for (String key : List.of("api", "source", "loader", "raw", "data", "extend", "preheatUrl",
                "t4_play_type", "Playback_Config_Url", "access_token", "api_key", "Local_Proxy_Config",
                "authorization", "cookie", "credentials", "1label", "bad-key", "\u6807\u7b7e")) {
            assertThrows(IOException.class, () -> PublicSourceData.validate(
                    mapper.createObjectNode().put(key, "PRIVATE")), key);
        }
        for (var data : List.of(mapper.createArrayNode(), mapper.nullNode(),
                mapper.createObjectNode().put("label", 1),
                mapper.createObjectNode().put("label", "bad\nvalue"),
                mapper.createObjectNode().put("label", "\uD800"),
                mapper.createObjectNode().put("label", "\u007f"))) {
            assertThrows(IOException.class, () -> PublicSourceData.validate(data));
        }
    }

    @Test
    void fieldCountCodePointAndAsciiByteBudgetsAreIndependent() throws Exception {
        var data = mapper.createObjectNode().put("label", "a".repeat(1024));
        PublicSourceData.validate(data);
        data.put("label", "a".repeat(1025));
        assertThrows(IOException.class, () -> PublicSourceData.validate(data));
        data.removeAll();
        for (int i = 0; i < 32; i++) data.put("k" + i, "x");
        PublicSourceData.validate(data);
        data.put("extra", "x");
        assertThrows(IOException.class, () -> PublicSourceData.validate(data));
        data.removeAll();
        data.put("one", "\uD83D\uDE00".repeat(512));
        PublicSourceData.validate(data);
        data.put("two", "\uD83D\uDE00".repeat(512));
        assertThrows(IOException.class, () -> PublicSourceData.validate(data));
    }
}
