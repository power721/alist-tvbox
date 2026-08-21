package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多源合并播放列表的条目解析:选集分隔符 '#' 与 URL 内嵌 "#storageId=..." 片段的区分
 * (后者不含 '$',必须拼回上一条,否则合并时 URL 被截断)。
 */
class MediaSubscriptionPlaylistParseTest {

    private final MediaSubscriptionCheckService checkService = new MediaSubscriptionCheckService(
            null, null, null, null, null, null, null, emptySettings(),
            null, null, null, null, null, new AppProperties(), new ObjectMapper());

    /** 全局 Setting 空 stub:未配置 msub_main_drives */
    private static SettingRepository emptySettings() {
        SettingRepository repository = Mockito.mock(SettingRepository.class);
        Mockito.when(repository.findById(Mockito.anyString())).thenReturn(java.util.Optional.empty());
        return repository;
    }

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
        String[] lines = MediaSubscriptionService.buildTvBoxPlayLines(12, merged, drives, Set.of("quark", "baidu"));
        assertEquals("我的追剧$$$百度网盘$$$夸克网盘", lines[0]);
        assertEquals("01(5G)$msubep-12-1#02(5G)$msubep-12-2"
                + "$$$01(5G)$1@101@0@0"
                + "$$$01(5G)$1@301@0@0#02(5G)$1@201@0@1", lines[1]);
    }

    @Test
    void tvboxPlayLinesKeepMainDrivesAndCompleteOthersOnly() {
        // 主网盘线路固定展示(允许暂不完整);非主网盘须覆盖齐 merged 全部集才上线路
        TreeMap<Integer, String> merged = new TreeMap<>();
        merged.put(1, "01(5G)$1@101@0@0");
        merged.put(2, "02(5G)$1@201@0@1");
        Map<String, TreeMap<Integer, String>> drives = new LinkedHashMap<>();
        TreeMap<Integer, String> baidu = new TreeMap<>();
        baidu.put(1, "01(5G)$1@101@0@0"); // 百度为主网盘但只有第 1 集 → 仍出线路
        drives.put("baidu", baidu);
        TreeMap<Integer, String> uc = new TreeMap<>();
        uc.put(1, "01(5G)$1@401@0@0"); // UC 非主网盘且集不齐 → 不上线路
        drives.put("uc", uc);
        TreeMap<Integer, String> quark = new TreeMap<>();
        quark.put(1, "01(5G)$1@301@0@0");
        quark.put(2, "02(5G)$1@201@0@1"); // 夸克非主网盘但集齐 → 上线路
        drives.put("quark", quark);
        String[] lines = MediaSubscriptionService.buildTvBoxPlayLines(12, merged, drives, Set.of("baidu"));
        assertEquals("我的追剧$$$百度网盘$$$夸克网盘", lines[0]);
        assertFalse(lines[1].contains("1@401@0@0"), "UC 集不齐不应出线路");
    }

    @Test
    void mainDrivesPreferSubscriptionOverrideOverGlobalSetting() {
        MediaSubscription subscription = new MediaSubscription();
        assertEquals(List.of(), checkService.mainDrives(subscription), "订阅与全局均未配置时无主网盘");

        // 订阅级覆盖(逗号分隔分享类型码,去重取前 2)
        subscription.setMainDrives("10,5,0"); // 百度/夸克/阿里 → 前 2 个
        assertEquals(List.of("baidu", "quark"), checkService.mainDrives(subscription));

        // 序列化辅助:类型码列表 ↔ 存储 CSV,空清空(回归全局)
        assertEquals("10,5", MediaSubscriptionService.serializeMainDrives(List.of(10, 5, 10, 0)));
        assertNull(MediaSubscriptionService.serializeMainDrives(List.of()));
        assertEquals(List.of(10, 5), MediaSubscriptionService.parseMainDrives("10, 5"));
        assertEquals(List.of(), MediaSubscriptionService.parseMainDrives(null));
    }

    @Test
    void mainDrivesFallBackToGlobalSetting() {
        SettingRepository settingRepository = Mockito.mock(SettingRepository.class);
        Setting global = new Setting();
        global.setName(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES);
        global.setValue("5,8"); // 夸克/115
        Mockito.when(settingRepository.findById(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES))
                .thenReturn(java.util.Optional.of(global));
        MediaSubscriptionCheckService service = new MediaSubscriptionCheckService(
                null, null, null, null, null, null, null, settingRepository,
                null, null, null, null, null, new AppProperties(), new ObjectMapper());

        MediaSubscription subscription = new MediaSubscription();
        assertEquals(List.of("quark", "115"), service.mainDrives(subscription), "订阅未覆盖时用全局配置");

        subscription.setMainDrives("10"); // 覆盖全局
        assertEquals(List.of("baidu"), service.mainDrives(subscription));
    }
}
