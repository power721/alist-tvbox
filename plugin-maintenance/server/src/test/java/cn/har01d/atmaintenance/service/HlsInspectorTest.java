package cn.har01d.atmaintenance.service;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class HlsInspectorTest {
    static String list(String tags) {
        return "#EXTM3U\n#EXT-X-VERSION:5\n#EXT-X-TARGETDURATION:6\n" + tags + "#EXTINF:6,\ns0\n";
    }

    @Test
    void quotedCommasRotationAndNoneKeepPerSegmentDeclarations() {
        var parsed = HlsInspector.parse(list("#EXT-X-KEY:METHOD=AES-128,URI=\"key,a?b=c\",IV=0X01\n")
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key,a?b=c\"\n#EXTINF:6,\ns1\n"
                + "#EXT-X-KEY:METHOD=NONE\n#EXTINF:6,\ns2\n");
        assertTrue(parsed.encrypted());
        assertFalse(parsed.unsupportedEncryption());
        assertEquals("key,a?b=c", parsed.segmentKeys().getFirst().uri());
        assertEquals(1, parsed.segmentKeys().getFirst().declaration());
        assertEquals(2, parsed.segmentKeys().get(1).declaration());
        assertFalse(parsed.segmentKeys().get(2).encrypted());
    }

    @Test
    void sequenceSupportsUnsigned64BitsAndRejectsSegmentOverflow() {
        var parsed = HlsInspector.parse(list("#EXT-X-MEDIA-SEQUENCE:18446744073709551615\n"));
        assertEquals(new BigInteger("18446744073709551615"), parsed.mediaSequence());
        assertThrows(IllegalArgumentException.class,
                () -> HlsInspector.parse(list("#EXT-X-MEDIA-SEQUENCE:18446744073709551615\n") + "#EXTINF:6,\ns1\n"));
        assertThrows(IllegalArgumentException.class,
                () -> HlsInspector.parse(list("") + "#EXT-X-MEDIA-SEQUENCE:7\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "URI=\"k\"", "METHOD=AES-128", "METHOD=AES-128,URI=k",
            "METHOD=AES-128,URI=\"k\",URI=\"other\"", "METHOD=AES-128,URI=\"k\",",
            "METHOD=AES-128,URI=\"k\",IV=0x", "METHOD=AES-128,URI=\"k\",IV=0xg",
            "METHOD=AES-128,URI=\"k\",IV=0x100000000000000000000000000000000",
            "METHOD=AES-128,URI=\"k\",IV=\"0x1\"", "METHOD=AES-128,URI=\"k\"garbage\"",
            "METHOD=NONE,URI=\"k\"", "METHOD=\"AES-128\",URI=\"k\"",
            "METHOD=AES-128, URI=\"k\"", "METHOD=AES-128,URI=\"\"",
            "METHOD=AES-128,URI=\"k\",KEYFORMAT=identity",
            "METHOD=AES-128,URI=\"k\",KEYFORMATVERSIONS=\"0\""
    })
    void malformedKeyDeclarationsFail(String attributes) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> HlsInspector.parse(list("#EXT-X-KEY:" + attributes + "\n")));
        assertEquals("MEDIA_HLS_INVALID", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "METHOD=SAMPLE-AES,URI=\"k\"", "METHOD=FUTURE,URI=\"k\"",
            "METHOD=AES-128,URI=\"k\",KEYFORMAT=\"com.example.drm\"",
            "METHOD=AES-128,URI=\"k\",KEYFORMATVERSIONS=\"1/2\"",
            "METHOD=AES-128,URI=\"k\",FUTURE=YES"
    })
    void unsupportedFormsAreExplicitlyMarked(String attributes) {
        assertTrue(HlsInspector.parse(list("#EXT-X-KEY:" + attributes + "\n")).unsupportedEncryption());
    }

    @Test
    void longMalformedAttributesAreRejectedWithoutRepeatedSearch() {
        assertTimeout(java.time.Duration.ofSeconds(2), () -> {
            for (String attributes : new String[] {"A".repeat(60000),
                    "METHOD=AES-128,URI=\"k\"," + "A".repeat(60000),
                    "METHOD=AES-128,URI=\"" + "a".repeat(60000)}) {
                assertThrows(IllegalArgumentException.class,
                        () -> HlsInspector.parse(list("#EXT-X-KEY:" + attributes + "\n")));
            }
        });
    }

    @Test
    void longFormatVersionListsDoNotOverflowTheStackOrAcceptBadTokens() {
        assertTimeout(java.time.Duration.ofSeconds(2), () -> {
            String formats = "1/".repeat(30000) + "1";
            assertTrue(HlsInspector.parse(list("#EXT-X-KEY:METHOD=AES-128,URI=\"k\",KEYFORMATVERSIONS=\""
                    + formats + "\"\n")).unsupportedEncryption());
            for (String suffix : new String[] {"/", "/0", "/a", "//1"}) {
                assertThrows(IllegalArgumentException.class, () -> HlsInspector.parse(list(
                        "#EXT-X-KEY:METHOD=AES-128,URI=\"k\",KEYFORMATVERSIONS=\"" + formats + suffix + "\"\n")));
            }
        });
    }

    @Test
    void explicitKeyAttributesRequireCompatibleVersion() {
        assertThrows(IllegalArgumentException.class, () -> HlsInspector.parse(
                list("#EXT-X-KEY:METHOD=AES-128,URI=\"k\",IV=0x1\n").replace("#EXT-X-VERSION:5\n", "")));
        assertThrows(IllegalArgumentException.class, () -> HlsInspector.parse(
                list("#EXT-X-KEY:METHOD=AES-128,URI=\"k\",KEYFORMAT=\"identity\"\n")
                        .replace("VERSION:5", "VERSION:2")));
        assertFalse(HlsInspector.parse(list(
                "#EXT-X-KEY:METHOD=AES-128,URI=\"k\",KEYFORMAT=\"identity\",KEYFORMATVERSIONS=\"1\"\n"))
                .unsupportedEncryption());
    }
}
