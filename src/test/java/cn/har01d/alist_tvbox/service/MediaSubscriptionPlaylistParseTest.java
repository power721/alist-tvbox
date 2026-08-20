package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多源合并播放列表的条目解析:选集分隔符 '#' 与 URL 内嵌 "#storageId=..." 片段的区分
 * (后者不含 '$',必须拼回上一条,否则合并时 URL 被截断)。
 */
class MediaSubscriptionPlaylistParseTest {

    private final MediaSubscriptionCheckService checkService = new MediaSubscriptionCheckService(
            null, null, null, null, null, null, null, null, null, null, null, null, null, new AppProperties(), new ObjectMapper());

    private final MediaSubscriptionService service = new MediaSubscriptionService(
            null, null, null, null, null, null, null, null, null, checkService, null, null, null, new ObjectMapper());

    @Test
    void urlWithStorageIdFragmentNotSplit() {
        TreeMap<Integer, String> out = new TreeMap<>();
        String playUrl = "第01集(1.2G)$/proxy/100#storageId=9#第02集(1.2G)$/proxy/101#storageId=9";
        boolean any = service.parsePlayEntries(playUrl, null, out);
        assertTrue(any);
        assertEquals(2, out.size());
        assertTrue(out.get(1).endsWith("#storageId=9"), "storageId 片段应拼回上一条:" + out.get(1));
        assertEquals("第02集(1.2G)$/proxy/101#storageId=9", out.get(2));
    }

    @Test
    void multipleGroupsMergedByEpisode() {
        TreeMap<Integer, String> out = new TreeMap<>();
        String playUrl = "第02集(1G)$/p/2#第03集(1G)$/p/3$$$第01集(500M)$/g/1";
        assertTrue(service.parsePlayEntries(playUrl, null, out));
        assertEquals(3, out.size());
        assertEquals("[1, 2, 3]", out.keySet().toString());
    }
}
