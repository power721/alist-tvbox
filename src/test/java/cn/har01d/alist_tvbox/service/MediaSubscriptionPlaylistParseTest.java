package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
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
            null, null, null, null, null, null, null, null, null, null, emptySettings(),
            null, null, null, null, null, null, null, null, null, null, null, new AppProperties(), new ObjectMapper(),
            (MediaSubscriptionNotificationService) null);

    /** 全局 Setting 空 stub:未配置 msub_main_drives */
    private static SettingRepository emptySettings() {
        SettingRepository repository = Mockito.mock(SettingRepository.class);
        Mockito.when(repository.findById(Mockito.anyString())).thenReturn(java.util.Optional.empty());
        return repository;
    }

    private final MediaSubscriptionService service = new MediaSubscriptionService(
            null, null, null, null, null, null, null, null, null, null, null, checkService, null, null,
            new AppProperties(), new ObjectMapper(), null, null, null);

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
    void episodeDisplayTitleFormatsNumberTitleAndSize() {
        // 元数据分集标题 + 从原标题尾部提取的大小(线上形态:fixName(796.08 MB))
        assertEquals("01. 噗噗先生(796.08 MB)",
                MediaSubscriptionService.episodeDisplayTitle(1, "噗噗先生", "瑞克和莫蒂.S09E01.1080p.mkv(796.08 MB)"));
        // 无分集标题退回原文件名(剥出的大小重新拼回,不重复)
        assertEquals("02. 48 4K.mkv(964.88 MB)",
                MediaSubscriptionService.episodeDisplayTitle(2, null, "48 4K.mkv(964.88 MB)"));
        // 无大小省略括号;百集以上不补零
        assertEquals("105. 大结局", MediaSubscriptionService.episodeDisplayTitle(105, "大结局", null));
        // byte2size(size<=0) 为空串,装配残留的空括号剥掉
        assertEquals("03. 第3集", MediaSubscriptionService.episodeDisplayTitle(3, null, "第3集()"));
        // 标题里的播放列表分隔符($/#)必须洗掉,否则条目被截断
        assertEquals("01. 第1集 下集", MediaSubscriptionService.episodeDisplayTitle(1, "第1集$下集#", null));
    }

    @Test
    void rewriteTitlesKeepsUrlPartIntact() {
        TreeMap<Integer, String> merged = new TreeMap<>();
        merged.put(47, "47 4K.mp4(796.08 MB)$1@191854@1@20");
        merged.put(48, "48 4K.mkv(964.88 MB)$1@191855@1@21");
        MediaSubscriptionService.rewriteTitles(merged, Map.of(48, "终末之始"));
        // 标题改写、URL 原样;无元数据标题的集退回原文件名
        assertEquals("47. 47 4K.mp4(796.08 MB)$1@191854@1@20", merged.get(47));
        assertEquals("48. 终末之始(964.88 MB)$1@191855@1@21", merged.get(48));
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

    // ---------- 缺陷 12 回归:夸克 4K 转码标注标题不得把三集塌成一集 ----------
    // 线上事故(邻人可疑 2026):fixName 剥公共后缀 GB].mkv 后,标题残留未闭合技术段
    // 「上集：… [322155_maxplus_50fps_tv_6.72(7.68 GB)」——旧首号规则把 322155 拆成 3221+55,
    // 三集全部撞成 55 去重塌成一集,「我的追剧」线路只剩 msubep-35-55 且播放报"已尝试 0 个源"。
    @Test
    void quarkTranscodeBracketTitlesParseToChapterNumbers() {
        String playUrl = String.join("#",
                "上集：喜迁新居，竟遇“诡”邻 [322155_maxplus_50fps_tv_6.72(7.68 GB)$1@501@0@0",
                "中集：双面丈夫，究竟谁在说谎？ [322155_maxplus_50fps_tv_6.60(7.52 GB)$1@502@0@1",
                "下集：终极反转！全员恶人互搏 [322155_maxplus_50fps_tv_6.45(7.38 GB)$1@503@0@2");
        TreeMap<Integer, String> out = new TreeMap<>();
        assertTrue(service.parsePlayEntries(playUrl, 1, out));
        assertEquals(Set.of(1, 2, 3), out.keySet(), "三集应按 上/中/下 章节各自成集:" + out.keySet());
        assertEquals("上集：喜迁新居，竟遇“诡”邻 [322155_maxplus_50fps_tv_6.72(7.68 GB)$msubep-35-1"
                        + "#中集：双面丈夫，究竟谁在说谎？ [322155_maxplus_50fps_tv_6.60(7.52 GB)$msubep-35-2"
                        + "#下集：终极反转！全员恶人互搏 [322155_maxplus_50fps_tv_6.45(7.38 GB)$msubep-35-3",
                MediaSubscriptionService.buildMsubepPlaylist(35, out));
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
        // 同为主网盘:夸克(2 集)覆盖多于百度(1 集),居前
        assertEquals("我的追剧$$$夸克网盘$$$百度网盘", lines[0]);
        assertEquals("01(5G)$msubep-12-1#02(5G)$msubep-12-2"
                + "$$$01(5G)$1@301@0@0#02(5G)$1@201@0@1"
                + "$$$01(5G)$1@101@0@0", lines[1]);
    }

    @Test
    void tvboxPlayLinesShowPartialDrivesOrderedByCoverage() {
        // 主网盘线路固定居前(允许暂不完整);其它盘线路非空即上(115 每集一链的线路就是该盘可用集清单),
        // 按集数降序排在主网盘之后
        TreeMap<Integer, String> merged = new TreeMap<>();
        merged.put(1, "01(5G)$1@101@0@0");
        merged.put(2, "02(5G)$1@201@0@1");
        Map<String, TreeMap<Integer, String>> drives = new LinkedHashMap<>();
        TreeMap<Integer, String> baidu = new TreeMap<>();
        baidu.put(1, "01(5G)$1@101@0@0"); // 百度为主网盘但只有第 1 集 → 仍居首条盘线路
        drives.put("baidu", baidu);
        TreeMap<Integer, String> uc = new TreeMap<>();
        uc.put(1, "01(5G)$1@401@0@0"); // UC 集不齐也出线路(单集源盘)
        drives.put("uc", uc);
        TreeMap<Integer, String> quark = new TreeMap<>();
        quark.put(1, "01(5G)$1@301@0@0");
        quark.put(2, "02(5G)$1@201@0@1"); // 夸克集齐,非主网盘 → 按覆盖数排在 UC 之前
        drives.put("quark", quark);
        TreeMap<Integer, String> unknown = new TreeMap<>(); // 空盘线路不上
        drives.put("empty", unknown);
        String[] lines = MediaSubscriptionService.buildTvBoxPlayLines(12, merged, drives, Set.of("baidu"));
        assertEquals("我的追剧$$$百度网盘$$$夸克网盘$$$UC网盘", lines[0]);
        assertTrue(lines[1].contains("1@401@0@0"), "UC 集不齐也应有线路(该盘可用集清单)");
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
                null, null, null, null, null, null, null, null, null, null, settingRepository,
                null, null, null, null, null, null, null, null, null, null, null, new AppProperties(), new ObjectMapper(),
                (MediaSubscriptionNotificationService) null);

        MediaSubscription subscription = new MediaSubscription();
        assertEquals(List.of("quark", "115"), service.mainDrives(subscription), "订阅未覆盖时用全局配置");

        subscription.setMainDrives("10"); // 覆盖全局
        assertEquals(List.of("baidu"), service.mainDrives(subscription));
    }

    @Test
    void accountIdsSupportPanAndAliTargets() throws Exception {
        MediaSubscription subscription = new MediaSubscription();
        assertEquals(List.of(), service.parseAccountIds(subscription));

        subscription.setAccountId(7);
        assertEquals(List.of("pan:7"), service.parseAccountIds(subscription), "旧单值兼容为 pan");

        subscription.setAccountIds("[\"pan:5\",\"ali:3\"]");
        assertEquals(List.of("pan:5", "ali:3"), service.parseAccountIds(subscription));

        subscription.setAccountIds("[5,8]");
        assertEquals(List.of("pan:5", "pan:8"), service.parseAccountIds(subscription), "旧整数 JSON 兼容为 pan");

        assertEquals("[\"pan:5\",\"ali:3\"]", service.serializeAccountIds(List.of("5", "ali:3"), null), "裸数字补 pan 前缀");
        assertEquals("[\"pan:9\"]", service.serializeAccountIds(List.of(), 9), "空列表回退单值");
    }

    @Test
    void singleEpisodeLinkDetection() {
        // 线上形态:115 每集一链("S01E16"/"第16集"/"EP16"),区别于整季包
        assertEquals(16, checkService.singleEpisodeOf("📺 悬案 (2026) S01E16 ✨4K WEB-DL AAC"));
        assertEquals(16, checkService.singleEpisodeOf("悬案 第16集 4K"));
        assertEquals(16, checkService.singleEpisodeOf("悬案 EP16"));
        assertNull(checkService.singleEpisodeOf("悬案 更新至16集"));
        assertNull(checkService.singleEpisodeOf("悬案 (2026) 4K [17集全]"));
        assertNull(checkService.singleEpisodeOf("悬案 第1-17集 合集"));
        assertNull(checkService.singleEpisodeOf("悬案 4K 高码率"), "无进度标记不判单集");
    }

    @Test
    void singleEpisodeResourceNotUsableAsPrimary() {
        // 探测覆盖来自集源行(coverageOf);未探测的资源无行,靠标题标记兜底
        cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository rows =
                Mockito.mock(cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository.class);
        Mockito.when(rows.findNumbersByResourceIdAndStatesIn(Mockito.anyInt(), Mockito.anyCollection()))
                .thenReturn(List.of());
        MediaSubscriptionCheckService probed = new MediaSubscriptionCheckService(
                null, null, null, null, rows, null, null, null, null, null, emptySettings(),
                null, null, null, null, null, null, null, null, null, null, null, new AppProperties(), new ObjectMapper(),
                (MediaSubscriptionNotificationService) null);
        MediaSubscriptionResource single = new MediaSubscriptionResource();
        single.setId(4);
        single.setTitle("📺 悬案 (2026) S01E16 ✨4K WEB-DL AAC");
        assertFalse(probed.usableAsPrimary(single, 17), "本地 17 集时单集链接不得挂主源");
        assertTrue(probed.usableAsPrimary(single, 1), "新剧首集/单集剧不受限");

        // 标题无标记但探测已知仅 1 集(集源行覆盖 = {16})
        Mockito.when(rows.findNumbersByResourceIdAndStatesIn(Mockito.eq(5), Mockito.anyCollection()))
                .thenReturn(List.of(16));
        MediaSubscriptionResource known = new MediaSubscriptionResource();
        known.setId(5);
        known.setTitle("悬案 16");
        assertFalse(probed.usableAsPrimary(known, 17));

        MediaSubscriptionResource pack = new MediaSubscriptionResource();
        pack.setId(6);
        pack.setTitle("悬案 (2026) 4K 高码率 [HQ.DV.60fps] [17集全]");
        assertTrue(probed.usableAsPrimary(pack, 17));
    }
}
