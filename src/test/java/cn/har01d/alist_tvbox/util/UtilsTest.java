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

}
