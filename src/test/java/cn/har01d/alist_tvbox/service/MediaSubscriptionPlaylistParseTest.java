package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
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

    @Test
    void msubepPlaylistRewritesEveryEpisodeToLogicalId() {
        TreeMap<Integer, String> merged = new TreeMap<>();
        merged.put(1, "第01集(1.2G)$/proxy/100#storageId=9");
        merged.put(2, "第02集(1.2G)$/proxy/101");
        assertEquals("第01集(1.2G)$msubep-5-1#第02集(1.2G)$msubep-5-2",
                MediaSubscriptionService.buildMsubepPlaylist(5, merged));
    }

    @Test
    void msubepPlaylistFallsBackToGenericTitleWhenEntryHasNoUrl() {
        TreeMap<Integer, String> merged = new TreeMap<>();
        merged.put(7, "纯文本选集名");
        assertEquals("第7集$msubep-3-7", MediaSubscriptionService.buildMsubepPlaylist(3, merged));
    }

    @Test
    void dualVersionSeasonFoldersKeepLaterEpisodes() {
        // 复现线上:同挂载两版本季文件夹。HQ 组(14 集)公共前后缀全剥,标题纯集号;
        // SDR 组(17 集)mp4/mkv 混排公共后缀为空,标题残留 2026/50fps 等数字——
        // 原文件名规则"末个 ≤999 数字"把 SDR 全组解析成 50,客户端只见 HQ 的 1-14 集
        StringBuilder hq = new StringBuilder();
        for (int i = 1; i <= 14; i++) {
            if (i > 1) {
                hq.append('#');
            }
            hq.append(String.format("%02d(5.0 GB)$1@%d@0@%d", i, 100 + i, i - 1));
        }
        StringBuilder sdr = new StringBuilder();
        for (int i = 1; i <= 17; i++) {
            if (i > 1) {
                sdr.append('#');
            }
            String ext = i <= 12 ? "mp4" : "mkv";
            sdr.append(String.format("%02d.2026.2160p.50fps.WEB-DL.H.265.AAC.%s(8.0 GB)$1@%d@0@%d", i, ext, 200 + i, i - 1));
        }
        TreeMap<Integer, String> out = new TreeMap<>();
        assertTrue(service.parsePlayEntries(hq + "$$$" + sdr, 1, out));
        assertEquals(17, out.size(), "15-17 集应从 SDR 组并入:" + out.keySet());
        assertEquals(17, out.lastKey());
        assertTrue(out.get(15).contains("2026"), "第 15 集应取自 SDR 组条目");
    }

    @Test
    void episodeFromTitleTakesFirstNumber() {
        assertEquals(1, checkService.parseEpisodeFromTitle("01", null));
        assertEquals(2, checkService.parseEpisodeFromTitle("第02集", null));
        // 集号后残留年份/帧率/编码数字,首号即集号
        assertEquals(1, checkService.parseEpisodeFromTitle("01.2026.2160p.50fps.WEB-DL.H.265.AAC.mp4", null));
        assertEquals(17, checkService.parseEpisodeFromTitle("17.2026.2160p.50fps.WEB-DL.H.265.AAC.mkv", null));
        // SxxEyy 幸存(单条目组无公共前缀可剥)时优先精确命中
        assertEquals(5, checkService.parseEpisodeFromTitle("S01E05.4K", 1));
        assertEquals(-1, checkService.parseEpisodeFromTitle("S02E05.4K", 1), "他季条目应被季过滤丢弃");
        assertEquals(-1, checkService.parseEpisodeFromTitle("上", null), "无数字标题不产出集号");
    }

    @Test
    void tvboxPlayLinesGroupByDrive() {
        // 首条合并线路(默认 msubep)+ 按网盘分线的备用线路;同盘各源按集合并
        TreeMap<Integer, String> merged = new TreeMap<>();
        merged.put(1, "01(5G)$1@101@0@0");
        merged.put(2, "02(5G)$1@201@0@1"); // 第 2 集仅夸克补缺源有
        Map<String, TreeMap<Integer, String>> drives = new LinkedHashMap<>();
        TreeMap<Integer, String> baidu = new TreeMap<>();
        baidu.put(1, "01(5G)$1@101@0@0");
        drives.put("baidu", baidu);
        TreeMap<Integer, String> quark = new TreeMap<>();
        quark.put(1, "01(5G)$1@301@0@0");
        quark.put(2, "02(5G)$1@201@0@1");
        drives.put("quark", quark);
        String[] lines = MediaSubscriptionService.buildTvBoxPlayLines(12, merged, drives);
        assertEquals("我的追剧$$$百度网盘$$$夸克网盘", lines[0]);
        assertEquals("01(5G)$msubep-12-1#02(5G)$msubep-12-2"
                + "$$$01(5G)$1@101@0@0"
                + "$$$01(5G)$1@301@0@0#02(5G)$1@201@0@1", lines[1]);
    }

    @Test
    void tvboxPlayLinesSkipEmptyDrivesAndShowUnknownKeyRaw() {
        // 空盘线路(该盘无任何集)跳过;未知盘 key 原样展示不吞
        Map<String, TreeMap<Integer, String>> drives = new LinkedHashMap<>();
        drives.put("baidu", new TreeMap<>());
        drives.put("xx", new TreeMap<>(Map.of(1, "01(5G)$1@1@0@0")));
        String[] lines = MediaSubscriptionService.buildTvBoxPlayLines(3, new TreeMap<>(), drives);
        assertEquals("我的追剧$$$xx", lines[0]);
        assertEquals("$$$01(5G)$1@1@0@0", lines[1]);
    }
}
