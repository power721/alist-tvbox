package cn.har01d.alist_tvbox.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UtilsTest {
    @Test
    void test() {
        String[] parts = "1~~~/xad/xs/1.mp4".split("~~~");
        System.out.println(Arrays.asList(parts));
        parts = "1^/xad/xs/1.mp4".split("\\^");
        System.out.println(Arrays.asList(parts));
        System.out.println("axaxa".replace("a", "H"));
        System.out.println(Utils.byte2size(4096));
        System.out.println(Utils.byte2size(6000));
        System.out.println(Utils.byte2size(600*1024*1024));
        System.out.println(Utils.byte2size(512*1024*1024L));
        System.out.println(Utils.byte2size((long) (715.3*1024*1024)));
        System.out.println(Utils.byte2size((long) (12.163*1024*1024*1024L)));
        System.out.println(Utils.byte2size(24*1024*1024*1024L));
    }

    @Test
    void getCommonPrefix() {
        System.out.println(Utils.getCommonPrefix(List.of("Test S01E1", "Test S01E2", "Test S01E3")));
    }

    @Test
    void getMixinKey() {
        assertEquals("ea1db124af3c7062474693fa704f4ff8", Utils.getMixinKey("7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45"));
    }

    @Test
    void getDataPathShouldRespectSystemPropertyOverride() {
        System.setProperty("atv.data.dir", "/tmp/atv-test-data");
        try {
            assertEquals(Path.of("/tmp/atv-test-data", "atv", "tmdb_failed.txt"), Utils.getDataPath("atv", "tmdb_failed.txt"));
        } finally {
            System.clearProperty("atv.data.dir");
        }
    }

    @Test
    void toAsciiUrl_nullReturnsNull() {
        assertNull(Utils.toAsciiUrl(null));
    }

    @Test
    void toAsciiUrl_blankReturnsOriginal() {
        assertEquals("", Utils.toAsciiUrl(""));
    }

    @Test
    void toAsciiUrl_asciiUrlUnchanged() {
        assertEquals("https://github.com/user/repo", Utils.toAsciiUrl("https://github.com/user/repo"));
    }

    @Test
    void toAsciiUrl_alreadyEncodedIsIdempotent() {
        assertEquals("https://host/%E4%B8%AD.json", Utils.toAsciiUrl("https://host/%E4%B8%AD.json"));
    }

    @Test
    void toAsciiUrl_encodesChineseInPath() {
        assertEquals("https://github.com/%E7%94%A8%E6%88%B7/%E4%BB%93%E5%BA%93",
                Utils.toAsciiUrl("https://github.com/用户/仓库"));
    }

    @Test
    void toAsciiUrl_encodesChineseInQuery() {
        assertEquals("https://host/api?name=%E4%B8%AD%E6%96%87",
                Utils.toAsciiUrl("https://host/api?name=中文"));
    }

    @Test
    void toAsciiUrl_preservesPortAndEncodesPath() {
        assertEquals("https://host:8080/%E4%B8%AD%E6%96%87", Utils.toAsciiUrl("https://host:8080/中文"));
    }

    @Test
    void toAsciiUrl_preservesFragment() {
        assertEquals("https://host/x#%E9%94%9A", Utils.toAsciiUrl("https://host/x#锚"));
    }

    @Test
    void toAsciiUrl_relativePathEncodesSegments() {
        assertEquals("spiders/%E4%B8%AD%E6%96%87.py", Utils.toAsciiUrl("spiders/中文.py"));
    }

    @Test
    void toAsciiUrl_garbageDoesNotThrow() {
        assertDoesNotThrow(() -> Utils.toAsciiUrl("https://[unclosed-bracket"));
    }

}
