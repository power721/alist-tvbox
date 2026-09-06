package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionFilter;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionPoolFilter;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.DeadLinkRepository;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.History;
import cn.har01d.alist_tvbox.entity.HistoryRepository;
import cn.har01d.alist_tvbox.entity.IndexTemplateRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisode;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallbackRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEventRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.service.sitesearch.WanouSearchService;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.util.TextUtils;
import cn.har01d.alist_tvbox.service.metadata.MetadataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 追剧订阅巡检:集数解析启发式、调度(短轮窗口/退避封顶)、补搜节制、ENDED 重开判定、
 * 退役冷却、主源失效确认、集源行生命周期(LISTED/VERIFIED/FAILED/MISSING)与失败传染。
 */
class MediaSubscriptionCheckServiceTest {

    private final AppProperties appProperties = new AppProperties();

    private final MediaSubscriptionCheckService service = new MediaSubscriptionCheckService(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            appProperties, new ObjectMapper(), (MediaSubscriptionNotificationService) null);

    @BeforeEach
    void disablePrimeCheckSlots() {
        // 高峰/凌晨档位兜底让常规间隔断言随一天内的时刻漂移:默认关闭,档位专项测试自行开启
        appProperties.getSubscription().setPrimeCheckTimes(java.util.List.of());
        appProperties.getSubscription().setNightCheckTimes(java.util.List.of());
    }

    @Test
    void seasonEpisodePattern() {
        assertEquals(5, service.parseEpisode("Show.S01E05.1080p.mkv", null));
        assertEquals(5, service.parseEpisode("Show.S01E05.1080p.mkv", 1));
        assertEquals(-1, service.parseEpisode("Show.S01E05.1080p.mkv", 2));
        assertEquals(12, service.parseEpisode("剧名.S02E12.2160p.WEB-DL.mkv", 2));
    }

    // ---------- 进度感知的观看进度(2026-08-27):刚点开几十秒的试看不算看完 ----------
    // 线上形态:33 集只看了几十秒就被算成"看完 33 集",追平标记被试看抬到 33,
    // 用户回看时「还没看完的最后一集」从此不亮角标。当前集进度不足折算前一集。

    @Test
    void watchedEpisodeRequiresSubstantialProgress() {
        HistoryRepository historyRepository = Mockito.mock(HistoryRepository.class);
        MediaSubscriptionCheckService svc = watchService(historyRepository);
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(7);
        subscription.setUid(1);

        // 33 集只看几十秒(45 分钟一集)→ 折算 32,最后一集保持"未看完"
        Mockito.when(historyRepository.findByUidAndVodId(1, "msub:7"))
                .thenReturn(List.of(history(33, 30_000, 45 * 60_000L)));
        assertEquals(32, svc.watchedEpisode(subscription));

        // completed/看完:position 夹紧到 duration → 33
        Mockito.when(historyRepository.findByUidAndVodId(1, "msub:7"))
                .thenReturn(List.of(history(33, 45 * 60_000L, 45 * 60_000L)));
        assertEquals(33, svc.watchedEpisode(subscription));

        // 跳片头片尾的完整观看(24 分钟番在 5 分钟片尾处停止,79%)也算看完
        Mockito.when(historyRepository.findByUidAndVodId(1, "msub:7"))
                .thenReturn(List.of(history(33, 1_140_000, 24 * 60_000L)));
        assertEquals(33, svc.watchedEpisode(subscription));
    }

    @Test
    void watchedEpisodeFallsBackToAbsolutePosition() {
        HistoryRepository historyRepository = Mockito.mock(HistoryRepository.class);
        MediaSubscriptionCheckService svc = watchService(historyRepository);
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(7);
        subscription.setUid(1);

        // 时长未知:按绝对播放位置判 —— 位置小只可能发生在片头附近
        Mockito.when(historyRepository.findByUidAndVodId(1, "msub:7"))
                .thenReturn(List.of(history(33, 6 * 60_000L, 0)));
        assertEquals(33, svc.watchedEpisode(subscription), "时长未知但已播 6 分钟:算看完");

        Mockito.when(historyRepository.findByUidAndVodId(1, "msub:7"))
                .thenReturn(List.of(history(33, 90_000, 0)));
        assertEquals(32, svc.watchedEpisode(subscription), "时长未知只播 90 秒:折算前一集");

        // 下标兜底路径(分盘线路/物理地址,无 msubep 逻辑标记)同折算规则
        History indexed = new History();
        indexed.setEpisode(10); // 选集下标从 0 起,第 11 集
        indexed.setPosition(30_000);
        indexed.setDuration(45 * 60_000L);
        Mockito.when(historyRepository.findByUidAndVodId(1, "msub:7")).thenReturn(List.of(indexed));
        assertEquals(10, svc.watchedEpisode(subscription));
    }

    private MediaSubscriptionCheckService watchService(HistoryRepository historyRepository) {
        return new MediaSubscriptionCheckService(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                historyRepository, new AppProperties(), new ObjectMapper(), (MediaSubscriptionNotificationService) null);
    }

    private static History history(int episode, long positionMs, long durationMs) {
        History history = new History();
        history.setEpisodeUrl("/p/token/1@1$msubep-7-" + episode);
        history.setPosition(positionMs);
        history.setDuration(durationMs);
        return history;
    }

    // ---------- 长番缺集检测(2026-08-27):base 上限 500 误伤 1200+ 集长番 ----------
    // 线上形态:柯南官方已播 1210、观测到 1270,computeMissing 的 base>500 保护整轮返回空,
    // 27 个真实缺口(全部落在官方已播范围内)从未触发补缺。上限抬到与网页清单同口径 5000。

    @Test
    void computeMissingCoversLongSeriesBeyondFiveHundred() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setOfficialEpisodes(1210);
        Set<Integer> present = IntStream.rangeClosed(1, 1270).boxed()
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        present.removeAll(Set.of(257, 926, 1008));

        assertEquals(Set.of(257, 926, 1008), service.computeMissing(subscription, present));
    }

    // ---------- 站点源档位(2026-09-01):玩偶略大于蜗牛 > 盘链/盘聚/观影 > TG 系 0 基准 ----------
    // 原先一刀切 siteSourceBonus(部署级,不按订阅调),现拆成权重表 source.* 键,可订阅级覆盖。

    @Test
    void siteSourceWeightTiers() {
        assertEquals(22, MediaSubscriptionCheckService.weight(null, "source.wanou"));
        assertEquals(20, MediaSubscriptionCheckService.weight(null, "source.woniu"));
        assertEquals(12, MediaSubscriptionCheckService.weight(null, "source.panlian"));
        assertEquals(12, MediaSubscriptionCheckService.weight(null, "source.panju"));
        assertEquals(12, MediaSubscriptionCheckService.weight(null, "source.guanying"));
        // TG 系(盘搜/TG-Search/电报网页)走 telegram 聚合路,无 sourceKind,基准 0
        assertEquals(0, MediaSubscriptionCheckService.weight(null, "source.telegram"));
        // 订阅级覆盖 > 内置默认
        MediaSubscriptionFilter filter = new MediaSubscriptionFilter();
        filter.setWeights(java.util.Map.of("source.wanou", 99));
        assertEquals(99, MediaSubscriptionCheckService.weight(filter, "source.wanou"));
    }

    @Test
    void computeMissingStillIgnoresAbsurdOfficialCount() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setOfficialEpisodes(9999);

        assertTrue(service.computeMissing(subscription, Set.of(1, 2, 3)).isEmpty());
    }

    @Test
    void computeMissingClampsProjectedRangeByOfficialTotal() {
        // 瑞克 S9 形态:官方总 10 完结、官方已播 11(上游 S1 分集污染)——
        // 不夹住则巡检每轮报缺第 11 集、fillGaps 空转攒 stallCount
        MediaSubscription subscription = new MediaSubscription();
        subscription.setOfficialTotal(10);
        subscription.setOfficialEpisodes(11);
        Set<Integer> present = IntStream.rangeClosed(1, 10).boxed()
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(service.computeMissing(subscription, present).isEmpty());
    }

    @Test
    void computeMissingIncludesScheduleAiredBeyondStaleOfficial() {
        // 播后首查:官方已播还是 refresh 前的旧值(8),schedule 快照里第 9 集播出时刻已到 ——
        // 缺口按直播径算出第 9 集,fillGaps 立即搜索,不等下一轮元数据刷新
        MediaSubscription subscription = new MediaSubscription();
        subscription.setOfficialEpisodes(8);
        long now = System.currentTimeMillis();
        subscription.setSchedule("[{\"episode\":8,\"airTime\":" + (now - 2 * 3600_000L)
                + "},{\"episode\":9,\"airTime\":" + (now - 15 * 60_000L)
                + "},{\"episode\":10,\"airTime\":" + (now + 24 * 3600_000L) + "}]");
        Set<Integer> present = IntStream.rangeClosed(1, 8).boxed()
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of(9), service.computeMissing(subscription, present), "未到播出时刻的第 10 集不算缺");
    }

    @Test
    void chineseEpisodeSuffix() {
        assertEquals(3, service.parseEpisode("边水往事.第03集.4K.mkv", null));
        assertEquals(12, service.parseEpisode("边水往事 第12集.mp4", null));
        assertEquals(20, service.parseEpisode("某剧.更新至20集", null));
    }

    // ---------- 综艺期号回归(线上:心动的信号 第九季) ----------
    // 正片标题拖长文案(「第2期上:告白夜来临～如益CP十指相扣」),文案数字(188男大=身高)
    // 被末号规则当集号:第 3 期纯享解析成 188、先导片 60fps 解析成 60,假集号推高观测上限,
    // missing 一路列到 188,补缺逐集空转、真资源与假缺口无交集被跳过探测。

    @Test
    void varietyEpisodeMarkAnchorsOverTrailingCopyNumbers() {
        assertEquals(3, service.parseEpisode("2025-08-18 第3期上纯享：元气辣妹主动贴贴188男大.mkv", null));
        assertEquals(2, service.parseEpisode("2026.08.10-第2期上：告白夜来临～如益CP十指相扣.mkv", null));
        assertEquals(5, service.parseEpisode("2026.09.01 第5期下(1).mp4", null));
        // 「更新至N集」不带「第」字,仍走末号规则,不受锚定影响
        assertEquals(20, service.parseEpisode("某剧.更新至20集", null));
    }

    @Test
    void fpsFrameRateIsNotAnEpisodeNumber() {
        assertEquals(1, service.parseEpisode("剧名 第01集 2160p 50fps.mkv", null));
        assertEquals(2, service.parseEpisode("2026.08.08 第02期 60fps 4K.mp4", null));
    }

    @Test
    void varietyShareYieldsOnlyMainEpisodeNumbers() {
        // 线上「心动的信号 第九季 更0902」分享全量文件名:正片(第N期上/中/下)只产集号,
        // 陪看/纯享/加更/先导/花絮/彩蛋全剔,先导片的 60fps 不再产出 60
        List<String> files = List.of(
                "2026.07.31-先导片上.mp4", "2026.07.31-先导片下.mp4",
                "2026.08.07-第1期陪看上.mp4", "2026.08.07-先导陪看上.mp4",
                "2026.08.08-第1期陪看下.mp4", "2026.08.08-先导陪看下.mp4",
                "2026.08.10-第2期上.mp4", "2026.08.10-第2期上纯享.mp4", "2026.08.10-第2期中.mp4",
                "2026.08.10-第2期中纯享.mp4", "2026.08.11-第2期下.mp4", "2026.08.11-第2期下纯享.mp4",
                "2026.08.13-第2期加更上.mp4", "2026.08.13-第2期加更下.mp4",
                "2026.08.14-第2期陪看上.mp4", "2026.08.15-第2期陪看下.mp4",
                "2026.08.16-花絮特辑.mp4",
                "2026.08.17-第3期上.mp4", "2026.08.17-第3期上纯享.mp4", "2026.08.17-第3期中（上）.mp4",
                "2026.08.17-第3期中（上）纯享.mp4", "2026.08.18-第3期下.mp4", "2026.08.18-第3期中（下）(1).mp4",
                "2026.08.18-第3期中（下）.mp4", "2026.08.18-第3期中（下）纯享.mp4",
                "2026.08.23-花絮特辑-4K.高码率.mp4", "2026.08.26-超前彩蛋-4K.高码率.mp4",
                "2026.08.24-第4期上.mp4", "2026.08.24-第4期中（上）.mp4", "2026.08.27-第4期加更下.mp4",
                "2026.08.27第4期加更上.mp4", "2026.08.28-第4期陪看上.mp4", "2026.08.28-第4期陪看中.mp4",
                "2026.08.29-第4期陪看下.mp4", "2026.08.30花絮特辑-4K.高码率.mp4",
                "2026.08.31-第5期上.mp4", "2026.08.31-第5期上纯享.mp4", "2026.08.31-第5期中上.mp4",
                "2026.08.31-第5期中上纯享.mp4", "2026.09.01-第5期下(1).mp4", "2026.09.01-第5期下.mp4",
                "2026.09.01-第5期中（下）.mp4", "2026.09.01-第5期中（下）纯享.mp4",
                "2026.09.02-超前彩蛋-4K.高码率.mp4", "2026.09.02-第5期下纯享.mp4",
                "20260812(超前彩蛋).mp4", "20260825(第4期下).mp4", "20260825(第4期中（下）).mp4");
        String[] extras = {"先导", "陪看", "纯享", "加更", "花絮", "彩蛋"};
        Set<Integer> episodes = new TreeSet<>();
        for (String file : files) {
            boolean extra = java.util.Arrays.stream(extras).anyMatch(file::contains);
            int parsed = extra ? -1 : service.parseEpisode(file, 9);
            if (!extra && parsed > 0) {
                episodes.add(parsed);
            }
        }
        // 该分享实缺第 1 期正片(只有第 1 期陪看),正片期号 2-5 全部干净识别
        assertEquals(Set.of(2, 3, 4, 5), episodes);
    }

    @Test
    void trailingNumber() {
        assertEquals(7, service.parseEpisode("剧名.E07.mkv", null));
        assertEquals(9, service.parseEpisode("show.09.mkv", null));
    }

    @Test
    void techTagsAndYearsIgnored() {
        // 1080p/2160p/HEVC 等标签剥离,4 位年份超界 → 无集号
        assertEquals(-1, service.parseEpisode("Movie.2024.1080p.WEB-DL.HEVC.mkv", null));
        // 标签剥离后集号仍可识别
        assertEquals(6, service.parseEpisode("剧名.2024.1080p.第06集.mkv", null));
    }

    // ---------- 缺陷 10 回归:文件名里的日期戳不得被当成集号 ----------
    // 线上事故(诛仙 第四季):目录里三个文件 01/02/03,全部解析成第 21 集 —— 末号规则取到了
    // [2026.08.21] 里的"21"。集数清单从 3 集塌成 1 集,而播放请求第 1 集时清单里没有这个 key,
    // 报"第 1 集暂无可用播放源(已尝试 0 个源)"。一个日期后缀同时造成漏集与全盘不可播。

    @Test
    void dateStampInFileNameIsNotMistakenForEpisode() {
        assertEquals(1, service.parseEpisode("01 [4K][HEVC.AAC][2026.08.21].mp4", null));
        assertEquals(2, service.parseEpisode("02~[4K][HEVC.AAC][2026.08.21].mp4", null));
        assertEquals(3, service.parseEpisode("03~[4K][HEVC.AAC][2026.08.21].mp4", null));
        // 其它常见日期写法
        assertEquals(7, service.parseEpisode("剧名 第07集 2026-08-21.mkv", null));
        assertEquals(7, service.parseEpisode("剧名 第07集 2026年08月21日.mkv", null));
        assertEquals(7, service.parseEpisode("剧名 第07集 20260821.mkv", null));
    }

    @Test
    void dateStampStrippingKeepsRealEpisodeNumbers() {
        // 不能矫枉过正:日期之外的数字仍按末号规则取
        assertEquals(12, service.parseEpisode("剧名.2024.1080p.第12集.mkv", null));
        assertEquals(5, service.parseEpisode("Show.S01E05.2160p.mkv", 1));
    }

    // ---------- 广告域名水印(2026-09-02):下载站推广尾巴毒化末号规则 ----------
    // 线上事故(醒来 01-06 合集磁力,itorrents 实拉种子):六个集文件全部带
    // [最新电影www.dyg7.com] 水印,域名里的 7 是末号规则取到的最后一个可信数字 ——
    // 01~06 全部解析成第 7 集:磁力预筛缺 1-6 集时匹配不到,缺第 7 集时误匹配
    // (1-6 的合集被当第 7 集提交,收割后六文件又塌成一个集源行)。

    @Test
    void adDomainWatermarkInBracketsIsNotMistakenForEpisode() {
        assertEquals(1, service.parseEpisode("01.2160p.HD国语中字无水印[最新电影www.dyg7.com].mkv", null));
        assertEquals(4, service.parseEpisode("04.2160p.HD国语中字无水印[最新电影www.dyg7.com].mkv", null));
        assertEquals(6, service.parseEpisode("06.1080p.HD国语中字无水印[最新电影www.dyg7.com].mkv", null));
        // 无 www 前缀的域名形态同样剥离
        assertEquals(2, service.parseEpisode("醒来02[电影天堂dygod.net].mkv", null));
    }

    @Test
    void adDomainWatermarkStrippingKeepsRealMarks() {
        // 显式集号段(BRACKET_EPISODE_MARK 守卫优先于域名信号)与发布组标签不受影响
        assertEquals(5, service.parseEpisode("剧名[第05集 1080P].mkv", null));
        assertEquals(3, service.parseEpisode("Show[SubsPlease]S01E03.1080p.mkv", 1));
    }

    // ---------- 四位数集号(2026-08-27):长寿动漫集号早已过千,999 上限整目录拒识 ----------
    // 线上事故(名侦探柯南,官方登记总 1212 集):百度主源 189 个文件全部四位集号命名
    // (1173.mp4/1178国语.mp4/1245 4KHDR日语.mp4),999 上限下零识别;唯一"识别"出的 1 集
    // 是剧场版子目录的电影文件(TrueHD.5.1 的 1 被末号规则当集号)—— 订阅显示 1 集。
    // 修复:1000-9999 收入可信域,但年份形态(1900-2099,如 2024/2025)继续排除。

    @Test
    void fourDigitEpisodeNumbersAreRecognized() {
        assertEquals(1173, service.parseEpisode("1173.mp4", null));
        assertEquals(1178, service.parseEpisode("1178国语.mp4", null));
        assertEquals(1194, service.parseEpisode("1194.国语.mp4", null));
        assertEquals(1217, service.parseEpisode("1217国语4K.mp4", null));
        assertEquals(1245, service.parseEpisode("1245 4KHDR日语.mp4", null));
        assertEquals(1270, service.parseEpisode("1270 4KHDR国语.mp4", null));
        assertEquals(1237, service.parseEpisode("1237-国语_4K.mp4", null));
        assertEquals(1021, service.parseEpisode("1021.mp4", null));
        assertEquals(1173, service.parseEpisode("Show.S01E1173.4K.mp4", 1));
        assertEquals(1178, service.parseEpisodeFromTitle("1178 国语", null));
    }

    @Test
    void yearShapedFourDigitNumbersStayRejected() {
        assertEquals(-1, service.parseEpisode("Movie.2025.1080p.WEB-DL.HEVC.mkv", null));
        // 剧场版电影名(2025.V2...TrueHD.5.1):剥 TrueHD/版本号/声道位后只剩年份形态 →
        // 电影不再混进剧集清单冒充「第1集」
        assertEquals(-1, service.parseEpisode("2025.V2.1080p.BluRay.Remux.AVC.TrueHD.5.1-Nest@ADE.mkv", null));
        assertEquals(-1, service.parseEpisode(
                "Detective.Conan.One-eyed.Flashback.2025.1080p.BluRay.Remux.AVC.TrueHD.5.1.mkv", null));
        assertFalse(MediaSubscriptionCheckService.plausibleEpisodeNumber(2025));
        assertTrue(MediaSubscriptionCheckService.plausibleEpisodeNumber(1270));
        assertFalse(MediaSubscriptionCheckService.plausibleEpisodeNumber(10000));
    }

    @Test
    void channelStripKeepsDateStampsAndEpisodeNumbers() {
        // 声道位剥离带数字边界:单/双位月的日期戳不被吃掉,真实集号照常识别(缺陷 10 不回归)
        assertEquals(5, service.parseEpisode("剧名 第05集 2026.8.21.mkv", null));
        assertEquals(7, service.parseEpisode("剧名 第07集 2026年08月21日.mkv", null));
        assertEquals(1, service.parseEpisode("01 [4K][HEVC.AAC][2026.08.21].mp4", null));
        // 版本号剥离不伤正常集号(前置分隔符限定,词中 vN 不剥)
        assertEquals(9, service.parseEpisode("show.09.v2.mp4", null));
    }

    // ---------- 单集最小体积接线(2026-08-27):过滤器字段后端从未消费,手填 200MB 形同虚设 ----------
    // 语义 = 偏好层而非硬门:同集存在达标文件时小版本不得顶上;该集只有小文件时照收
    // (实在找不到合规资源才忽略限制,线上:柯南 1173-1216 仅 130-160MB、1217+ 有 4K 版,
    // 硬门会把前段整段丢掉);显式调低于全局底线则覆盖底线;垃圾防护底线(默认 20MB)保留。

    @Test
    void episodeSizePolicyForms() {
        Fixture fixture = new Fixture();
        MediaSubscription subscription = new MediaSubscription();
        MediaSubscriptionCheckService.EpisodeSizePolicy policy = fixture.service.episodeSizePolicy(subscription);
        assertEquals(20L * 1024 * 1024, policy.floorBytes(), "无过滤:全局 20MB 底线");
        assertEquals(0, policy.preferredBytes());
        subscription.setFilterConfig("{\"minEpisodeSizeMb\":200,\"maxEpisodeSizeMb\":0}");
        policy = fixture.service.episodeSizePolicy(subscription);
        assertEquals(20L * 1024 * 1024, policy.floorBytes(), "调高 = 偏好层,底线不动");
        assertEquals(200L * 1024 * 1024, policy.preferredBytes());
        assertTrue(policy.preferredHit(200L * 1024 * 1024));
        assertFalse(policy.preferredHit(199L * 1024 * 1024));
        subscription.setFilterConfig("{\"minEpisodeSizeMb\":5}");
        policy = fixture.service.episodeSizePolicy(subscription);
        assertEquals(5L * 1024 * 1024, policy.floorBytes(), "显式调低:覆盖全局底线");
        assertEquals(0, policy.preferredBytes());
        subscription.setFilterConfig("{\"maxEpisodeSizeMb\":1000}");
        policy = fixture.service.episodeSizePolicy(subscription);
        assertEquals(1000L * 1024 * 1024, policy.maxBytes());
        assertEquals(0, policy.preferredBytes(), "单集上限独立接线,不产生偏好层");
    }

    @Test
    void minEpisodeSizePreferenceAppliedPerEpisode() {
        Fixture fixture = new Fixture();
        fixture.subscription.setFilterConfig("{\"minEpisodeSizeMb\":200}");
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/追剧/1-测试剧"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(filesOfSize(
                        new String[]{"1173.mp4", "1178国语.mp4", "1178国语4K.mp4", "1180国语.mp4", "1211国语4K.mp4", "1211国语.mp4"},
                        145, 139, 500, 5, 500, 190));
        var files = fixture.service.episodeFilesAt("/追剧/1-测试剧", fixture.subscription);
        assertEquals(3, files.size());
        assertEquals(145L * 1024 * 1024, files.get(1173).size(), "该集只有不达标文件:照收(缺额兜底)");
        assertEquals("1178国语4K.mp4", files.get(1178).name(), "同集存在达标文件:小版本不得顶上(小在前也会被顶换)");
        assertEquals("1211国语4K.mp4", files.get(1211).name(), "达标文件先到:不达标后来者不得顶上");
        assertFalse(files.containsKey(1180), "低于硬底线(全局 20MB 垃圾防护):仍然硬拒");
    }

    private static FsResponse filesOfSize(String[] names, long... sizeMb) {
        FsResponse response = new FsResponse();
        List<FsInfo> list = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            FsInfo info = new FsInfo();
            info.setName(names[i]);
            info.setType(0);
            info.setSize(sizeMb[i] * 1024 * 1024);
            list.add(info);
        }
        response.setFiles(list);
        return response;
    }

    // ---------- 全局资源筛选(2026-08-28):msub_pool_filter 单行 JSON,三道门 + 存量复筛 + 体积全局回退 ----------
    // include=硬门禁(须含其一)/ exclude=与订阅级并集 / 清晰度=门槛(仅拒标题明确标注低于门槛的,未标注放行);
    // 单集下限替换部署默认底线,订阅级显式配置优先 —— 与「主网盘:清空=跟随全局」同款合并惯例。

    @Test
    void titleQualityForms() {
        assertEquals("uhd", MediaSubscriptionCheckService.titleQuality("苍兰诀 全12集 4K"));
        assertEquals("uhd", MediaSubscriptionCheckService.titleQuality("苍兰诀 2160p HEVC"));
        assertEquals("fhd", MediaSubscriptionCheckService.titleQuality("苍兰诀 1080P 全集"));
        assertEquals("hd", MediaSubscriptionCheckService.titleQuality("苍兰诀 720p 标清版"));
        assertNull(MediaSubscriptionCheckService.titleQuality("苍兰诀 第01-08集 国语"));
    }

    @Test
    void poolFilterNormalize() {
        MediaSubscriptionPoolFilter filter = new MediaSubscriptionPoolFilter();
        filter.setIncludeKeywords(java.util.Arrays.asList("  ", "国语", "国语", ""));
        filter.setMinQuality("4K");
        filter.setMinEpisodeSizeMb(-5);
        filter.setMaxEpisodeSizeMb(100);
        filter.normalize();
        assertEquals(List.of("国语"), filter.getIncludeKeywords());
        assertEquals("uhd", filter.getMinQuality());
        assertEquals(0, filter.getMinEpisodeSizeMb());
        assertEquals(100, filter.getMaxEpisodeSizeMb());
        filter.setMinEpisodeSizeMb(200);
        filter.setMaxEpisodeSizeMb(100);
        filter.normalize();
        assertEquals(0, filter.getMaxEpisodeSizeMb(), "上下限矛盾:max 视为不限");
        assertEquals("fhd", MediaSubscriptionPoolFilter.normalizeQuality("1080"));
        assertEquals("", MediaSubscriptionPoolFilter.normalizeQuality("蓝光"));
    }

    @Test
    void episodeSizePolicyGlobalFallback() {
        Fixture fixture = new Fixture();
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_POOL_FILTER))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_POOL_FILTER,
                        "{\"minEpisodeSizeMb\":50,\"maxEpisodeSizeMb\":800}")));
        MediaSubscription subscription = new MediaSubscription();
        MediaSubscriptionCheckService.EpisodeSizePolicy policy = fixture.service.episodeSizePolicy(subscription);
        assertEquals(50L * 1024 * 1024, policy.floorBytes(), "全局下限:替换部署默认 20MB 底线");
        assertEquals(800L * 1024 * 1024, policy.maxBytes(), "全局上限:订阅未配置时回退");
        assertEquals(0, policy.preferredBytes());
        subscription.setFilterConfig("{\"minEpisodeSizeMb\":30,\"maxEpisodeSizeMb\":100}");
        policy = fixture.service.episodeSizePolicy(subscription);
        assertEquals(30L * 1024 * 1024, policy.floorBytes(), "订阅级显式调低:覆盖全局底线(订阅优先)");
        assertEquals(100L * 1024 * 1024, policy.maxBytes(), "订阅级上限优先");
        subscription.setFilterConfig("{\"minEpisodeSizeMb\":500}");
        policy = fixture.service.episodeSizePolicy(subscription);
        assertEquals(500L * 1024 * 1024, policy.preferredBytes(), "订阅级调高全局底线:仍走偏好层而非硬门");
        assertEquals(800L * 1024 * 1024, policy.maxBytes());
    }

    @Test
    void fillPoolAllKeywordsStopsWhenPrimaryFoundCandidates() {
        // 主词已搜到候选入池 → 自定义词不再搜索(扩展召回面只服务"主词召回不足",不为备胎翻倍烧请求)
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        fixture.subscription.setCustomKeywords("英文名");
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/ok", "苍兰诀 第01-08集 1080P")));
        MediaSubscriptionResource admitted = new MediaSubscriptionResource();
        admitted.setLink("https://pan.quark.cn/s/ok");
        admitted.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of()) // 主词填池前:池空
                .thenReturn(List.of(admitted)); // 自定义词填池前:主词已入池 → 闸门生效
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(1, "https://pan.quark.cn/s/ok"))
                .thenReturn(Optional.empty());

        fixture.service.fillPoolAllKeywords(fixture.subscription, true, null);

        Mockito.verify(fixture.telegramService, Mockito.times(1))
                .searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any());
    }

    @Test
    void fillPoolAllKeywordsContinuesWhenPrimaryExhausted() {
        // 主词搜不到任何候选(池仍枯竭)→ 自定义词逐个补搜
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        fixture.subscription.setCustomKeywords("英文名\n别名");
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());

        fixture.service.fillPoolAllKeywords(fixture.subscription, true, null);

        ArgumentCaptor<String> keywords = ArgumentCaptor.forClass(String.class);
        Mockito.verify(fixture.telegramService, Mockito.times(3))
                .searchAggregated(keywords.capture(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any());
        assertEquals(List.of("苍兰诀", "英文名", "别名"), keywords.getAllValues());
    }

    @Test
    void fillPoolAppliesGlobalFilterGates() {
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_POOL_FILTER))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_POOL_FILTER,
                        "{\"includeKeywords\":[\"国语\"],\"excludeKeywords\":[\"短剧\"],\"minQuality\":\"fhd\"}")));
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(
                        message("https://pan.quark.cn/s/ok", "苍兰诀 第01-08集 国语 1080P"),
                        message("https://pan.quark.cn/s/no-kw", "苍兰诀 第01-08集 4K"),
                        message("https://pan.quark.cn/s/drama", "苍兰诀 短剧合集 1080P 国语"),
                        message("https://pan.quark.cn/s/lowq", "苍兰诀 第01-08集 720P 国语")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(1, "https://pan.quark.cn/s/ok"))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository).save(captor.capture());
        assertEquals("https://pan.quark.cn/s/ok", captor.getValue().getLink(), "三道门只有全过的资源入池");
        ArgumentCaptor<MediaSubscriptionEvent> events = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository).save(events.capture());
        assertTrue(events.getValue().getDetail().contains("命中排除词 1"), "全局排除词拦截有审计: " + events.getValue().getDetail());
        assertTrue(events.getValue().getDetail().contains("缺包含词 1"), "全局包含词硬门禁拦截有审计");
        assertTrue(events.getValue().getDetail().contains("清晰度不足 1"), "清晰度门槛拦截有审计");
    }

    @Test
    void fillPoolGlobalQualityFloorPassesUnmarkedTitles() {
        // 只拒「明确标注」低于门槛的:未标注清晰度的放行(挂载前无从判断,避免误杀召回)
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_POOL_FILTER))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_POOL_FILTER, "{\"minQuality\":\"uhd\"}")));
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(
                        message("https://pan.quark.cn/s/plain", "苍兰诀 第01-08集 国语"),
                        message("https://pan.quark.cn/s/hd", "苍兰诀 第01-08集 720P")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(1, "https://pan.quark.cn/s/plain"))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository).save(captor.capture());
        assertEquals("https://pan.quark.cn/s/plain", captor.getValue().getLink(), "未标注清晰度的资源放行");
    }

    @Test
    void fillPoolMergesGlobalAndSubscriptionExcludes() {
        // 排除词并集:全局排「短剧」+ 订阅排「抢先版」,命中任一即拒
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        fixture.subscription.setFilterConfig("{\"excludeKeywords\":[\"抢先版\"]}");
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_POOL_FILTER))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_POOL_FILTER, "{\"excludeKeywords\":[\"短剧\"]}")));
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(
                        message("https://pan.quark.cn/s/ok", "苍兰诀 第01-08集 1080P"),
                        message("https://pan.quark.cn/s/drama", "苍兰诀 短剧合集"),
                        message("https://pan.quark.cn/s/ts", "苍兰诀 抢先版 1080P")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(1, "https://pan.quark.cn/s/ok"))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository).save(captor.capture());
        assertEquals("https://pan.quark.cn/s/ok", captor.getValue().getLink(), "两层排除词并集生效");
    }

    @Test
    void fillPoolSubscriptionQualitiesBonus() {
        // 订阅级「清晰度」关键词此前后端从未消费,只存不读;接线为加分(命中 +quality.prefer 默认 10)
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        fixture.subscription.setFilterConfig("{\"qualities\":[\"杜比视界\"]}");
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(
                        message("https://pan.quark.cn/s/dv", "苍兰诀 第01-08集 4K 杜比视界"),
                        message("https://pan.quark.cn/s/plain", "苍兰诀 第01-08集 4K")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.eq(1), Mockito.anyString()))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository, Mockito.times(2)).save(captor.capture());
        int dv = captor.getAllValues().stream().filter(r -> r.getLink().endsWith("/dv")).findFirst().orElseThrow().getScore();
        int plain = captor.getAllValues().stream().filter(r -> r.getLink().endsWith("/plain")).findFirst().orElseThrow().getScore();
        assertEquals(10, dv - plain, "同条件下命中清晰度偏好词 +10");
    }

    @Test
    void candidatesOrderedHonorsGlobalFilter() {
        // 存量候选复筛:配置收紧后池内已有资源不再被选为主源(行不删除,只是不再参与换源)
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_POOL_FILTER))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_POOL_FILTER,
                        "{\"excludeKeywords\":[\"短剧\"],\"minQuality\":\"fhd\"}")));
        MediaSubscriptionResource fine = resource("苍兰诀 第01-08集 4K");
        MediaSubscriptionResource drama = resource("苍兰诀 短剧合集");
        MediaSubscriptionResource lowQ = resource("苍兰诀 第01-08集 720P");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(fine, drama, lowQ));

        List<MediaSubscriptionResource> candidates = fixture.service.candidatesOrdered(fixture.subscription);

        assertEquals(List.of(fine), candidates, "排除词/清晰度门槛对存量候选同样生效");
    }

    @Test
    void candidatesOrderedExemptsManuallyAddedResource() {
        // 手动粘贴入池的源豁免自动门禁(盘白名单/排除词/清晰度):这些门禁针对搜索召回噪声,
        // 拦它等于手动添加的资源永远探测不到/换不上 —— 用户反馈"可不可以手动添加候选资源"的核心诉求
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        fixture.subscription.setMainDrives("10"); // 主网盘只配百度:夸克源域外
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_POOL_FILTER))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_POOL_FILTER,
                        "{\"excludeKeywords\":[\"短剧\"],\"minQuality\":\"fhd\"}")));
        MediaSubscriptionResource autoQuark = resource("苍兰诀 第01-08集 4K");
        autoQuark.setType(5); // 夸克:白名单外,自动门禁正常滤掉
        MediaSubscriptionResource manual = resource("苍兰诀 短剧合集 720P"); // 白名单外 + 排除词 + 低清,三重全撞
        manual.setType(5);
        manual.setSource(MediaSubscriptionResource.SOURCE_MANUAL);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(autoQuark, manual));

        List<MediaSubscriptionResource> candidates = fixture.service.candidatesOrdered(fixture.subscription);

        assertEquals(List.of(manual), candidates, "手动添加的源豁免盘白名单/排除词/清晰度门禁,自动源照常复筛");
    }

    @Test
    void previewAppliesGlobalFilterGates() {
        // 预览与入池同规:全局门禁在 preview 也生效(「预览看到的即能入池的」)
        Fixture fixture = new Fixture();
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_POOL_FILTER))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_POOL_FILTER, "{\"excludeKeywords\":[\"短剧\"]}")));
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(
                        message("https://pan.quark.cn/s/ok", "苍兰诀 第01-08集 4K"),
                        message("https://pan.quark.cn/s/drama", "苍兰诀 短剧合集")));

        List<Map<String, Object>> result = fixture.service.preview("苍兰诀", null, null);

        assertEquals(1, result.size());
        assertEquals("https://pan.quark.cn/s/ok", result.get(0).get("link"));
    }

    @Test
    void previewFreshUpdateOutscoresMonthOldPeer() {
        // 3 天内更新的最新档(recency.fresh 叠加在 recent 之上):同盘同文案的两条候选,
        // 只差发布时间 —— 线上「更0902」(消息 9/1 深夜,内容更新到 9/2 播出)此前与 8/5
        // 旧包同 +30 拉不开,时间必须成为显式加分项
        Fixture fixture = new Fixture();
        Message fresh = message("https://pan.quark.cn/s/fresh", "测试剧 全集");
        Message stale = message("https://pan.quark.cn/s/stale", "测试剧 全集");
        stale.setTime(Instant.now().minus(java.time.Duration.ofDays(10)));
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(fresh, stale));

        List<Map<String, Object>> result = fixture.service.preview("测试剧", null, null);

        Map<String, Object> freshRow = result.stream()
                .filter(r -> "https://pan.quark.cn/s/fresh".equals(r.get("link"))).findFirst().orElseThrow();
        Map<String, Object> staleRow = result.stream()
                .filter(r -> "https://pan.quark.cn/s/stale".equals(r.get("link"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(freshRow.get("reasons")).contains("3天内更新"), String.valueOf(freshRow.get("reasons")));
        assertFalse(String.valueOf(staleRow.get("reasons")).contains("3天内更新"), String.valueOf(staleRow.get("reasons")));
        assertEquals(20, (int) freshRow.get("score") - (int) staleRow.get("score"),
                "fresh 档应恰好拉大 20 分:" + freshRow.get("score") + " vs " + staleRow.get("score"));
    }

    @Test
    void previewMainDriveExemptFromOutsidePenalty() {
        // 订阅盘偏好 [UC,123] 与全局主盘 [百度,夸克] 冲突:主盘夸克不再吃「偏好外盘 -10」
        //(此前与 drive.main +15 对冲后仍比不配偏好时低 10 分,订阅偏好的存在净惩罚主盘候选);
        // 非主盘非偏好的盘照常降权,偏好语义对非主盘不变
        Fixture fixture = new Fixture();
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES, "10,5")));
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_EXTENDED_DRIVES))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_EXTENDED_DRIVES, "9")));
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(
                        message("https://pan.quark.cn/s/main", "测试剧 全集", "5"),
                        message("https://cloud.189.cn/s/out", "测试剧 全集", "9")));
        MediaSubscriptionFilter filter = new MediaSubscriptionFilter();
        filter.setDriveTypes(List.of(7, 3));

        List<Map<String, Object>> result = fixture.service.preview("测试剧", null, filter);

        assertEquals(2, result.size());
        Map<String, Object> quark = result.stream()
                .filter(r -> "https://pan.quark.cn/s/main".equals(r.get("link"))).findFirst().orElseThrow();
        Map<String, Object> tianyi = result.stream()
                .filter(r -> "https://cloud.189.cn/s/out".equals(r.get("link"))).findFirst().orElseThrow();
        assertFalse(String.valueOf(quark.get("reasons")).contains("偏好外盘"), String.valueOf(quark.get("reasons")));
        assertTrue(String.valueOf(quark.get("reasons")).contains("主网盘"), String.valueOf(quark.get("reasons")));
        assertTrue(String.valueOf(tianyi.get("reasons")).contains("偏好外盘"), String.valueOf(tianyi.get("reasons")));
    }

    private static MediaSubscriptionResource resource(String title) {
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setSubscriptionId(1);
        resource.setLink("https://pan.quark.cn/s/" + title.hashCode());
        resource.setTitle(title);
        resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        return resource;
    }

    // ---------- 缺陷 12 回归:方括号技术标注段(夸克 4K 转码命名)不得污染集号 ----------
    // 线上事故(邻人可疑 2026,三集迷你剧):文件名「上集：喜迁新居，竟遇“诡”邻 [322155_maxplus_50fps_tv_6.72GB].mkv」
    // 末号规则取到体积 6.72 的 72,三集各成 45/60/72(maxEpisode=72),与官方 3 集对不上,详情页分集全缺失。
    // 技术段剔除后无数字,按 上/中/下 章节推定集序(与 TMDB S1E1-3 标题一一对应)。

    @Test
    void bracketTechAnnotationIsNotMistakenForEpisode() {
        assertEquals(1, service.parseEpisode("上集：喜迁新居，竟遇“诡”邻 [322155_maxplus_50fps_tv_6.72GB].mkv", null));
        assertEquals(2, service.parseEpisode("中集：双面丈夫，究竟谁在说谎？ [322155_maxplus_50fps_tv_6.60GB].mkv", null));
        assertEquals(3, service.parseEpisode("下集：终极反转！全员恶人互搏 [322155_maxplus_50fps_tv_6.45GB].mkv", null));
        // 无单位体积/纯模板 id 段(闭合与未闭合形态)同样剔除
        assertEquals(1, service.parseEpisode("上集 [322155_tv_6.72].mkv", null));
        assertEquals(-1, service.parseEpisode("正片 [322155_maxplus].mkv", null), "剔除技术段后无集号来源");
    }

    @Test
    void chapterFallbackOnlyWithoutExplicitNumber() {
        // 显式集号优先:一集拆上下两部(第05集 上部/下部)不被章节标记覆盖
        assertEquals(5, service.parseEpisode("第05集 上部.mp4", null));
        assertEquals(5, service.parseEpisode("第05集 下部.mp4", null));
        assertEquals(2, service.parseEpisode("邻人可疑 中篇.mp4", null));
        // 无章节无数字仍不产出
        assertEquals(-1, service.parseEpisode("邻人可疑 完整版.mp4", null));
    }

    @Test
    void bracketWithExplicitEpisodeMarkKept() {
        // 段内混有技术词但显式写了集号 → 不剔
        assertEquals(5, service.parseEpisode("剧名 [第05集 1080P].mkv", null));
        assertEquals(5, service.parseEpisode("剧名 [S01E05 4K].mkv", 1));
        // 纯内容段保留:[01] 仍是集号来源;纯技术段剔后无数字
        assertEquals(1, service.parseEpisode("剧名 [01].mkv", null));
        assertEquals(-1, service.parseEpisode("剧名 [1080P HEVC].mkv", null));
    }

    // ---------- 缺陷 11 回归:多季合集目录必须按季隔离 ----------
    // 同一分享里带 第1-3季/ 目录(52+26 集),那些文件多半只写"第01集"不写 SxxEyy,
    // 文件名级季过滤挡不住,会直接冒充目标季的集数。目录名是唯一可靠的季信号。

    @Test
    void subdirectoryDeclaringAnotherSeasonIsSkipped() {
        assertTrue(MediaSubscriptionCheckService.otherSeasonDir("第1-3季", 4));
        assertTrue(MediaSubscriptionCheckService.otherSeasonDir("第1-2季.4K.全52集", 4));
        assertTrue(MediaSubscriptionCheckService.otherSeasonDir("第3季 (2025)4K.全26集", 4));
        assertTrue(MediaSubscriptionCheckService.otherSeasonDir("第一季", 4));
    }

    @Test
    void subdirectoryOfTargetOrUnknownSeasonIsKept() {
        assertFalse(MediaSubscriptionCheckService.otherSeasonDir("第四季 (最终季)", 4));
        assertFalse(MediaSubscriptionCheckService.otherSeasonDir("第1-4季 合集", 4)); // 区间包含目标季
        assertFalse(MediaSubscriptionCheckService.otherSeasonDir("4K", 4));          // 无季标记不误伤
        assertFalse(MediaSubscriptionCheckService.otherSeasonDir("【Z】诛丨仙 第四季 (最终季)", 4));
        assertFalse(MediaSubscriptionCheckService.otherSeasonDir("第一季", null));    // 未指定季不过滤
    }

    // ---------- 首播年份目录门禁:裸数字命名的 S1 打包资源不得冒领后续季集号 ----------
    // 线上:末日地堡 S3 订阅挂载根下的「M 末日地堡4K英语中英字幕2023/01-10.mp4」(S1 全 10 集),
    // 无季标记文件顺着挂载语境记成 S3 集源行,未播的 S3E10 被假 S1E10 顶上、观测集数冲到 10/10。

    @Test
    void firstSeasonYearDirectoryIsSkippedForLaterSeasons() {
        assertTrue(MediaSubscriptionCheckService.otherSeasonDir("M 末日地堡4K英语中英字幕2023", 3, 2023));
        assertTrue(MediaSubscriptionCheckService.firstSeasonYearDir("剧名 (2019)", 5, 2019));
    }

    @Test
    void firstSeasonYearGateKeepsLegitimateDirectories() {
        assertFalse(MediaSubscriptionCheckService.otherSeasonDir("M 末日地堡4K英语中英字幕2023", 1, 2023)); // S1 订阅照收
        assertFalse(MediaSubscriptionCheckService.otherSeasonDir("末日地堡 (2026) 更新中", 3, 2023));       // 当前季年份
        assertFalse(MediaSubscriptionCheckService.otherSeasonDir("鬼灭之刃 (2019) 全集", 5, 2019));         // 全系列包豁免
        assertFalse(MediaSubscriptionCheckService.otherSeasonDir("鬼灭之刃 (2019) 合集", 5, 2019));
        assertFalse(MediaSubscriptionCheckService.otherSeasonDir("S03 4K 2023", 3, 2023));               // 显式季标记优先:声明目标季不进年份门禁
        assertTrue(MediaSubscriptionCheckService.otherSeasonDir("S01 4K 2023", 3, 2023));                // 声明其它季照拒
        assertFalse(MediaSubscriptionCheckService.firstSeasonYearDir("末日地堡 4K", 3, 2023));             // 无年份不判
        assertFalse(MediaSubscriptionCheckService.firstSeasonYearDir("剧名 (2023)", null, 2023));
    }

    // ---------- 调度:播出短轮窗口与退避封顶 ----------

    @Test
    void scheduleNextPreAirSleep() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        subscription.setNextAirTime(now + 5 * 3600_000L);
        service.scheduleNext(subscription);
        assertEquals(now + 5 * 3600_000L + 15 * 60_000L, subscription.getNextCheckTime());
    }

    @Test
    void scheduleNextShortPollInsideWindow() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        subscription.setNextAirTime(now - 2 * 3600_000L); // 播出 2h:12h 窗口内
        subscription.setOfficialEpisodes(10);
        subscription.setCurrentEpisodes(9); // 已播集仍缺
        service.scheduleNext(subscription);
        assertClose(now + 3600_000L, subscription.getNextCheckTime());
    }

    @Test
    void scheduleNextShortPollExitsWhenAiredCaughtUp() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        subscription.setNextAirTime(now - 2 * 3600_000L);
        subscription.setOfficialEpisodes(10);
        subscription.setCurrentEpisodes(10); // 已播集全部在手:播后轮询收工
        service.scheduleNext(subscription);
        assertClose(now + 6 * 3600_000L, subscription.getNextCheckTime(), "追平已播集不该继续小时轮");
    }

    @Test
    void scheduleNextFirstPostAirLookMissRetriesIn30min() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        // 线上诉求:20:00 播、20:15 首查未命中 → 20:45 重试一次
        long air = now - 15 * 60_000L; // 刚播 15min(首查 = 播出+15min 槽位)
        subscription.setSchedule("[{\"episode\":10,\"airTime\":" + air + "}]");
        subscription.setLastCheckTime(now); // 本轮首查刚开始
        subscription.setCurrentEpisodes(9); // 已播的第 10 集仍未到手(缺口驱动)
        service.scheduleNext(subscription);
        assertClose(now + 30 * 60_000L, subscription.getNextCheckTime(), "首查仍有缺集应 +30min 快速重试");
    }

    @Test
    void scheduleNextFirstPostAirLookHitExitsPolling() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        long air = now - 15 * 60_000L;
        subscription.setSchedule("[{\"episode\":10,\"airTime\":" + air + "}]");
        subscription.setLastCheckTime(now);
        subscription.setCurrentEpisodes(10); // 本轮已找到新集,已播集追平
        service.scheduleNext(subscription);
        assertClose(now + 6 * 3600_000L, subscription.getNextCheckTime(), "已追平应收工回常规间隔");
    }

    @Test
    void scheduleNextQuickRetryOnlyOnce() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        long air = now - 45 * 60_000L; // 播出 45min:20:45 重试轮,本轮仍未命中
        subscription.setSchedule("[{\"episode\":10,\"airTime\":" + air + "}]");
        subscription.setLastCheckTime(now);
        subscription.setCurrentEpisodes(9);
        service.scheduleNext(subscription);
        assertClose(now + 3600_000L, subscription.getNextCheckTime(), "快速重试仅一次,之后回小时节奏");
    }

    @Test
    void scheduleNextShortPollAnchoredOnScheduleDespiteAdvancedAir() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        // 播后检查恰好触发元数据刷新:nextAirTime 已前移到下一集,但 schedule 快照仍留刚播条目 ——
        // 短轮不因 air 前移被播出前休眠截断
        subscription.setNextAirTime(now + 24 * 3600_000L);
        subscription.setSchedule("[{\"episode\":10,\"airTime\":" + (now - 15 * 60_000L) + "}]");
        subscription.setLastCheckTime(now);
        subscription.setCurrentEpisodes(9);
        service.scheduleNext(subscription);
        assertClose(now + 30 * 60_000L, subscription.getNextCheckTime(), "快照锚定的播后窗口优先于下一集的播出前休眠");
    }

    @Test
    void scheduleNextImminentAirSleepsToAirPlus15() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        subscription.setNextAirTime(now + 10 * 60_000L); // 距播出 <15min:直接睡到播出+15min,不再隔 1h
        service.scheduleNext(subscription);
        assertEquals(now + 10 * 60_000L + 15 * 60_000L, subscription.getNextCheckTime());
    }

    // ---------- 无日程订阅的高峰档位兜底 + 手动播出时刻校正 ----------

    @Test
    void scheduleNextPrimeSlotFallbackForScheduleless() {
        java.time.ZoneId zone = java.time.ZoneId.of(cn.har01d.alist_tvbox.util.Constants.ZONE_ID);
        java.time.LocalDateTime slotTime = java.time.LocalDateTime.now(zone).plusHours(2)
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        appProperties.getSubscription().setPrimeCheckTimes(java.util.List.of(slotTime.toLocalTime().toString()));
        long slot = slotTime.toLocalDate().atTime(slotTime.toLocalTime()).atZone(zone).toInstant().toEpochMilli();
        MediaSubscription subscription = subscription(); // 无 nextAirTime 无 schedule
        service.scheduleNext(subscription);
        assertClose(slot, subscription.getNextCheckTime(), "无日程订阅排到最近高峰档位(2h 后,早于 6h 常规间隔)");
    }

    @Test
    void scheduleNextPrimeSlotWithinHourFloorIgnored() {
        java.time.ZoneId zone = java.time.ZoneId.of(cn.har01d.alist_tvbox.util.Constants.ZONE_ID);
        java.time.LocalDateTime nearTime = java.time.LocalDateTime.now(zone).plusMinutes(30)
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        // 半小时后的档被 1h 地板跳过,同刻明天档在 24h 外 → 回常规 6h
        appProperties.getSubscription().setPrimeCheckTimes(java.util.List.of(nearTime.toLocalTime().toString()));
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        service.scheduleNext(subscription);
        assertClose(now + 6 * 3600_000L, subscription.getNextCheckTime(), "1h 内的档位不算,回常规间隔");
    }

    // ---------- 手动更新日(airWeekdays):官方日程缺失/不可信时只在配置周几查 ----------

    /** 北京时间固定时刻(2026-09 参照:09-09 周三、09-10 周四、09-16 下周三)。 */
    private static long atBeijing(int year, int month, int day, int hour, int minute) {
        return java.time.ZonedDateTime.of(year, month, day, hour, minute, 0, 0,
                java.time.ZoneId.of(cn.har01d.alist_tvbox.util.Constants.ZONE_ID)).toInstant().toEpochMilli();
    }

    @Test
    void nextManualAirSlotPicksNearestConfiguredDay() {
        MediaSubscription subscription = subscription();
        subscription.setAirWeekdays("2,4"); // 周二/周四
        assertEquals(atBeijing(2026, 9, 10, 20, 0),
                service.nextManualAirSlot(subscription, atBeijing(2026, 9, 9, 10, 0)),
                "周三 10:00 → 最近的更新日是明天(周四)20:00");
    }

    @Test
    void nextManualAirSlotTodayWhenClockAhead() {
        MediaSubscription subscription = subscription();
        subscription.setAirWeekdays("3"); // 周三
        assertEquals(atBeijing(2026, 9, 9, 20, 0),
                service.nextManualAirSlot(subscription, atBeijing(2026, 9, 9, 10, 0)),
                "更新日当天、播出时刻未到 → 今天 20:00");
    }

    @Test
    void nextManualAirSlotPastClockRollsToNextWeek() {
        MediaSubscription subscription = subscription();
        subscription.setAirWeekdays("3");
        assertEquals(atBeijing(2026, 9, 16, 20, 0),
                service.nextManualAirSlot(subscription, atBeijing(2026, 9, 9, 21, 0)),
                "更新日当天 20:00 已过 → 下周三 20:00");
    }

    @Test
    void nextManualAirSlotHonorsCustomClock() {
        MediaSubscription subscription = subscription();
        subscription.setAirWeekdays("2,4");
        subscription.setCustomAirClock("00:30"); // 追番凌晨档
        assertEquals(atBeijing(2026, 9, 10, 0, 30),
                service.nextManualAirSlot(subscription, atBeijing(2026, 9, 9, 10, 0)));
    }

    @Test
    void nextManualAirSlotEndedAndUnconfiguredReturnZero() {
        MediaSubscription subscription = subscription();
        assertEquals(0, service.nextManualAirSlot(subscription, System.currentTimeMillis()), "未配置返回 0");
        subscription.setAirWeekdays("2");
        subscription.setStatus(MediaSubscription.STATUS_ENDED);
        assertEquals(0, service.nextManualAirSlot(subscription, System.currentTimeMillis()), "完结剧不接管");
    }

    @Test
    void scheduleNextManualWeekdaysTakesOverOfficialAir() {
        MediaSubscription subscription = subscription();
        subscription.setAirWeekdays("2,4");
        long now = System.currentTimeMillis();
        subscription.setNextAirTime(now + 5 * 3600_000L); // 官方日程落在非更新日:被手动更新日接管
        service.scheduleNext(subscription);
        long manualSlot = service.nextManualAirSlot(subscription, now);
        assertEquals(manualSlot, subscription.getNextAirTime(), "nextAirTime 接管为下一更新日(详情页/时间轴同口径)");
        assertEquals(manualSlot + 15 * 60_000L, subscription.getNextCheckTime(), "休眠到更新日播出时刻+15min");
    }

    @Test
    void scheduleNextManualWeekdaysGapDoesNotWaitForUpdateDay() {
        MediaSubscription subscription = subscription();
        subscription.setAirWeekdays("1"); // 周一:远近随跑测时刻变,断言用 min 表达式稳健
        long now = System.currentTimeMillis();
        subscription.setOfficialEpisodes(10);
        subscription.setCurrentEpisodes(9); // 已播集有缺口:不死等更新日
        service.scheduleNext(subscription);
        long manualSlot = service.nextManualAirSlot(subscription, now);
        assertEquals(Math.min(now + 6 * 3600_000L, manualSlot + 15 * 60_000L), subscription.getNextCheckTime(),
                "缺口按常规退避尽快补,但不睡穿更新日");
    }

    @Test
    void scheduleNextManualWeekdaysEndedKeepsOfficialAir() {
        MediaSubscription subscription = subscription();
        subscription.setAirWeekdays("2");
        subscription.setStatus(MediaSubscription.STATUS_ENDED);
        long now = System.currentTimeMillis();
        long officialAir = now + 5 * 3600_000L;
        subscription.setNextAirTime(officialAir);
        service.scheduleNext(subscription);
        assertEquals(officialAir, subscription.getNextAirTime(), "完结剧不被更新日接管(周轻查继续)");
    }

    // ---------- 完结剧凌晨档:巡检避开高峰期 ----------

    @Test
    void scheduleNextEndedSlotsToNightTimeNotPrime() {
        java.time.ZoneId zone = java.time.ZoneId.of(cn.har01d.alist_tvbox.util.Constants.ZONE_ID);
        // prime 档 90min 后(更早)、night 档 2h 后:仍在追看的完结剧须落凌晨档,不被高峰档吸附
        java.time.LocalDateTime primeTime = java.time.LocalDateTime.now(zone).plusMinutes(90)
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        java.time.LocalDateTime nightTime = java.time.LocalDateTime.now(zone).plusHours(2)
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        appProperties.getSubscription().setPrimeCheckTimes(java.util.List.of(primeTime.toLocalTime().toString()));
        appProperties.getSubscription().setNightCheckTimes(java.util.List.of(nightTime.toLocalTime().toString()));
        MediaSubscription subscription = subscription();
        subscription.setStatus(MediaSubscription.STATUS_ENDED);
        service.scheduleNext(subscription);
        long slot = nightTime.toLocalDate().atTime(nightTime.toLocalTime()).atZone(zone).toInstant().toEpochMilli();
        assertClose(slot, subscription.getNextCheckTime(), "ENDED 巡检排凌晨档,90min 后的高峰档更早也不抢");
    }

    @Test
    void scheduleNextActiveKeepsPrimeSlotDespiteNight() {
        // 反向门禁:完结分流不能误伤在播剧 —— ACTIVE 无日程仍按高峰档排(新集发现优先)
        java.time.ZoneId zone = java.time.ZoneId.of(cn.har01d.alist_tvbox.util.Constants.ZONE_ID);
        java.time.LocalDateTime primeTime = java.time.LocalDateTime.now(zone).plusMinutes(90)
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        java.time.LocalDateTime nightTime = java.time.LocalDateTime.now(zone).plusHours(2)
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        appProperties.getSubscription().setPrimeCheckTimes(java.util.List.of(primeTime.toLocalTime().toString()));
        appProperties.getSubscription().setNightCheckTimes(java.util.List.of(nightTime.toLocalTime().toString()));
        MediaSubscription subscription = subscription();
        service.scheduleNext(subscription);
        long slot = primeTime.toLocalDate().atTime(primeTime.toLocalTime()).atZone(zone).toInstant().toEpochMilli();
        assertClose(slot, subscription.getNextCheckTime(), "ACTIVE 仍排高峰档,凌晨档不参与在播剧调度");
    }

    @Test
    void weeklyLiteCheckAlignsToNightSlot() {
        java.time.ZoneId zone = java.time.ZoneId.of(cn.har01d.alist_tvbox.util.Constants.ZONE_ID);
        appProperties.getSubscription().setNightCheckTimes(java.util.List.of("03:15"));
        long now = System.currentTimeMillis();
        long next = service.nextWeeklyLiteCheckTime(now);
        java.time.ZonedDateTime at = java.time.Instant.ofEpochMilli(next).atZone(zone);
        assertEquals(3, at.getHour(), "每周轻查对齐凌晨档小时");
        assertEquals(15, at.getMinute(), "每周轻查对齐凌晨档分钟");
        long sevenDays = now + 7 * 24 * 3600_000L;
        assertTrue(next >= sevenDays && next <= sevenDays + 25 * 3600_000L,
                "对齐到 7 天后的第一个凌晨档(至多顺延一天)");
    }

    @Test
    void applyCustomAirClockRewritesScheduleClocks() {
        java.time.ZoneId zone = java.time.ZoneId.of(cn.har01d.alist_tvbox.util.Constants.ZONE_ID);
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        long yesterdayDefault = today.minusDays(1).atTime(20, 0).atZone(zone).toInstant().toEpochMilli();
        long tomorrowDefault = today.plusDays(1).atTime(20, 0).atZone(zone).toInstant().toEpochMilli();
        MediaSubscription subscription = subscription();
        subscription.setSchedule("[{\"episode\":9,\"airTime\":" + yesterdayDefault
                + "},{\"episode\":10,\"airTime\":" + tomorrowDefault + "}]");
        subscription.setNextAirTime(tomorrowDefault);
        subscription.setCustomAirClock("11:30");
        service.applyCustomAirClock(subscription);
        long tomorrow1130 = today.plusDays(1).atTime(11, 30).atZone(zone).toInstant().toEpochMilli();
        assertTrue(subscription.getSchedule().contains(String.valueOf(tomorrow1130)), "日程条目时分改写为 11:30(日期不动)");
        assertEquals(Long.valueOf(tomorrow1130), subscription.getNextAirTime(), "nextAirTime 取改写后第一个未来条目");
    }

    @Test
    void applyCustomAirClockRewritesBareNextAirTime() {
        java.time.ZoneId zone = java.time.ZoneId.of(cn.har01d.alist_tvbox.util.Constants.ZONE_ID);
        java.time.LocalDate day = java.time.LocalDate.now(zone).plusDays(2);
        MediaSubscription subscription = subscription();
        subscription.setNextAirTime(day.atTime(20, 0).atZone(zone).toInstant().toEpochMilli());
        subscription.setCustomAirClock("18:00");
        service.applyCustomAirClock(subscription);
        assertEquals(Long.valueOf(day.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()),
                subscription.getNextAirTime(), "无日程时只改写 nextAirTime 的时分");
    }

    @Test
    void normalizeAirClockFormats() {
        assertEquals("09:05", MediaSubscriptionCheckService.normalizeAirClock("9:05"));
        assertEquals("11:30", MediaSubscriptionCheckService.normalizeAirClock("11:30"));
        assertNull(MediaSubscriptionCheckService.normalizeAirClock("24:00"));
        assertNull(MediaSubscriptionCheckService.normalizeAirClock("11:60"));
        assertNull(MediaSubscriptionCheckService.normalizeAirClock("中午"));
        assertNull(MediaSubscriptionCheckService.normalizeAirClock(" "));
        assertNull(MediaSubscriptionCheckService.normalizeAirClock(null));
    }

    @Test
    void scheduleNextOutsideWindowFallsBackToBackoff() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        subscription.setNextAirTime(now - 13 * 3600_000L); // 出窗:回退 6h 退避
        subscription.setStallCount(0);
        service.scheduleNext(subscription);
        assertClose(now + 6 * 3600_000L, subscription.getNextCheckTime());
    }

    @Test
    void scheduleNextReturningBackoffCap12h() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        subscription.setOfficialStatus(MetadataDetails.STATUS_RETURNING);
        subscription.setStallCount(20); // factor 已封顶 4,6h*4=24h → 追更中封顶 12h
        service.scheduleNext(subscription);
        assertClose(now + 12 * 3600_000L, subscription.getNextCheckTime());
    }

    @Test
    void scheduleNextNoMetadataStays24h() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        subscription.setStallCount(20);
        service.scheduleNext(subscription);
        assertClose(now + 24 * 3600_000L, subscription.getNextCheckTime());
    }

    // ---------- 播出前休眠让位于老集缺口(线上:盗妖行换到只留尾部10集的分享, ----------
    // ---------- 缺45集却因"下一集后天播"睡到播出前,新订阅首日无补缺)                ----------

    @Test
    void scheduleNextPreAirSleepYieldsToAiredGap() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        subscription.setNextAirTime(now + 48 * 3600_000L); // 后天才播下一集
        subscription.setOfficialEpisodes(55);
        subscription.setCurrentEpisodes(10); // 换源只挂到尾部 10 集
        subscription.setStallCount(0);
        service.scheduleNext(subscription);
        assertClose(now + 6 * 3600_000L, subscription.getNextCheckTime(), // 不睡到播出前:常规间隔让补缺尽早跑
                "缺官方已播老集时播出前休眠应让位");
    }

    @Test
    void scheduleNextAiredGapYieldDoesNotSleepPastAirTime() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        subscription.setNextAirTime(now + (long) (2.5 * 3600_000L)); // 2.5h 后开播(线上:08:25 排程 11:00 播)
        subscription.setOfficialEpisodes(55);
        subscription.setCurrentEpisodes(10); // 缺官方已播老集
        subscription.setCheckIntervalHours(12); // 常规间隔比距播出时间长:不能睡穿播出时刻
        subscription.setStallCount(0);
        service.scheduleNext(subscription);
        assertEquals(now + (long) (2.5 * 3600_000L) + 15 * 60_000L, subscription.getNextCheckTime(),
                "缺老集让位常规间隔时也要取与播出时刻的较早者");
    }

    @Test
    void scheduleNextPreAirSleepKeptWhenAiredCaughtUp() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        subscription.setNextAirTime(now + 5 * 3600_000L);
        subscription.setOfficialEpisodes(55);
        subscription.setCurrentEpisodes(55); // 已追平官方已播
        service.scheduleNext(subscription);
        assertEquals(now + 5 * 3600_000L + 15 * 60_000L, subscription.getNextCheckTime());
    }

    @Test
    void behindAiredEpisodesRequiresBothSides() {
        MediaSubscription subscription = subscription();
        assertFalse(MediaSubscriptionCheckService.behindAiredEpisodes(subscription, 0), "官方无数据不判缺");
        assertFalse(MediaSubscriptionCheckService.behindAiredEpisodes(subscription, 55), "本地未知不判缺");
        subscription.setCurrentEpisodes(55);
        assertFalse(MediaSubscriptionCheckService.behindAiredEpisodes(subscription, 55), "追平不算缺");
        subscription.setCurrentEpisodes(10);
        assertTrue(MediaSubscriptionCheckService.behindAiredEpisodes(subscription, 55));
    }

    @Test
    void airedTargetCombinesOfficialAndSchedule() {
        MediaSubscription subscription = subscription();
        long now = System.currentTimeMillis();
        assertEquals(0, service.airedTarget(subscription, now), "无官方无日程:0");
        subscription.setOfficialEpisodes(8);
        assertEquals(8, service.airedTarget(subscription, now));
        // schedule 已到时刻的第 10 集 > 官方旧值 8;未到的第 11 集不计;总集数夹住超登集
        subscription.setSchedule("[{\"episode\":10,\"airTime\":" + (now - 60_000L)
                + "},{\"episode\":11,\"airTime\":" + (now + 24 * 3600_000L) + "}]");
        assertEquals(10, service.airedTarget(subscription, now));
        subscription.setOfficialTotal(9);
        assertEquals(9, service.airedTarget(subscription, now), "官方总集数夹紧");
    }

    // ---------- 补搜节制:播出窗口内只缺最新集不降级、隔轮限频 ----------

    @Test
    void gapSearchKeywordRestraintInAirWindow() {
        MediaSubscription subscription = subscription();
        subscription.setNextAirTime(System.currentTimeMillis() - 3600_000L);
        subscription.setOfficialEpisodes(5);
        Set<Integer> missing = Set.of(5); // 老集都齐,只缺刚播的第 5 集
        assertEquals("测试剧 4K", service.gapSearchKeyword(subscription, missing, 1)); // 首轮整季
        assertNull(service.gapSearchKeyword(subscription, missing, 2)); // 限频:隔轮
        assertEquals("测试剧 4K", service.gapSearchKeyword(subscription, missing, 3));
    }

    @Test
    void gapSearchKeywordDegradesWhenOldGapExists() {
        MediaSubscription subscription = subscription();
        subscription.setNextAirTime(System.currentTimeMillis() - 3600_000L);
        subscription.setOfficialEpisodes(5);
        assertEquals("测试剧 第3集", service.gapSearchKeyword(subscription, new TreeSet<>(Set.of(3, 5)), 2));
    }

    @Test
    void gapSearchKeywordDegradesOutsideWindow() {
        MediaSubscription subscription = subscription();
        subscription.setNextAirTime(System.currentTimeMillis() - 13 * 3600_000L);
        subscription.setOfficialEpisodes(5);
        assertEquals("测试剧 4K", service.gapSearchKeyword(subscription, Set.of(5), 1));
        assertEquals("测试剧 第5集", service.gapSearchKeyword(subscription, Set.of(5), 2));
    }

    @Test
    void gapSearchKeywordDegradesWithoutOfficialData() {
        MediaSubscription subscription = subscription();
        subscription.setNextAirTime(System.currentTimeMillis() - 3600_000L);
        assertEquals("测试剧 第5集", service.gapSearchKeyword(subscription, Set.of(5), 2));
    }

    // ---------- 自定义搜索词:解析、补搜轮转、归属匹配 ----------

    @Test
    void customKeywordsSplitTrimsDedupesAndLimits() {
        List<String> split = MediaSubscriptionCheckService.splitCustomKeywords(
                " 英文名 \n英文2，别名、Alias\n\n英文2 \n 第五 \n第六 \n第七 \n第八");
        assertEquals(List.of("英文名", "英文2", "别名", "Alias", "第五"), split, "多分隔符+trim+去重,至多 5 个");
        assertEquals(List.of(), MediaSubscriptionCheckService.splitCustomKeywords(null));
        assertEquals(List.of(), MediaSubscriptionCheckService.splitCustomKeywords(" \n ,,、"));
    }

    @Test
    void customKeywordsDropsPrimaryWord() {
        MediaSubscription subscription = subscription(); // keyword = "测试剧 4K"
        subscription.setCustomKeywords("测试剧 4K\n英文名\n测试剧");
        // 与主搜索词相同的词剔除(重复搜索纯浪费);与剧名相同但主词不同时保留(剧名兜底词仍要独立成路)
        assertEquals(List.of("英文名", "测试剧"), MediaSubscriptionCheckService.customKeywords(subscription));
        assertEquals(List.of(), MediaSubscriptionCheckService.customKeywords(new MediaSubscription()));
    }

    @Test
    void gapSearchKeywordInsertsCustomKeywordRoundsBeforeEpisodeDegrade() {
        MediaSubscription subscription = subscription();
        subscription.setNextAirTime(System.currentTimeMillis() - 13 * 3600_000L);
        subscription.setOfficialEpisodes(5);
        subscription.setCustomKeywords("英文名\n别名");
        Set<Integer> missing = new TreeSet<>(Set.of(3, 5));
        assertEquals("测试剧 4K", service.gapSearchKeyword(subscription, missing, 1)); // 首轮整季主词
        assertEquals("英文名", service.gapSearchKeyword(subscription, missing, 2)); // 自定义词轮
        assertEquals("别名", service.gapSearchKeyword(subscription, missing, 3));
        assertEquals("测试剧 第3集", service.gapSearchKeyword(subscription, missing, 4)); // 单集降级起点推后
        assertEquals("测试剧 第5集", service.gapSearchKeyword(subscription, missing, 5));
    }

    @Test
    void matchNamesIncludeCustomKeywords() {
        MediaSubscription subscription = subscription();
        subscription.setCustomKeywords("The Test Drama\n测试剧别名");
        List<String> names = service.matchNames(subscription);
        assertTrue(names.contains("The Test Drama"));
        assertTrue(names.contains("测试剧别名"));
        // 自定义词召回的资源标题(不含剧名本名)须过剧名门禁,否则多词召回被整条误杀
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "The Test Drama 4K 全集"));
    }

    // ---------- ENDED 重开判定(本地集数 = 集源行 LIVE 并集) ----------

    @Test
    void shouldReopenWhenOfficialOrExpectedRaised() {
        Fixture fixture = new Fixture();
        Mockito.when(fixture.episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(numbers(1, 24));
        fixture.subscription.setCurrentEpisodes(24);
        fixture.subscription.setOfficialEpisodes(26);
        assertTrue(fixture.service.shouldReopen(fixture.subscription));
        fixture.subscription.setOfficialEpisodes(24);
        assertFalse(fixture.service.shouldReopen(fixture.subscription));
        fixture.subscription.setExpectedEpisodes(30);
        assertTrue(fixture.service.shouldReopen(fixture.subscription));
    }

    @Test
    void shouldReopenFalseWithoutData() {
        Fixture fixture = new Fixture();
        fixture.subscription.setCurrentEpisodes(24);
        assertFalse(fixture.service.shouldReopen(fixture.subscription));
    }

    // ---------- 自动完结的季级口径(多季剧剧级 status 恒 RETURNING) ----------

    @Test
    void seasonAiredOutEndsSubscriptionEvenWhenShowStillReturning() {
        MediaSubscription subscription = subscription();
        subscription.setOfficialStatus(MetadataDetails.STATUS_RETURNING);
        subscription.setOfficialEpisodes(10);
        subscription.setOfficialTotal(10); // nextAirTime 空:本季无下集播出
        assertTrue(subscription.isSeasonAiredOut());
        assertTrue(MediaSubscriptionCheckService.shouldAutoEnd(subscription, 10));
        // 缺集不完结:播完 ≠ 收齐,收齐前继续追缺
        assertFalse(MediaSubscriptionCheckService.shouldAutoEnd(subscription, 8));
    }

    @Test
    void seasonAiredOutRequiresFullAiredSeasonWithoutNextAir() {
        MediaSubscription subscription = subscription();
        subscription.setOfficialEpisodes(4);
        subscription.setOfficialTotal(10);
        assertFalse(subscription.isSeasonAiredOut());
        // 集数已播满但还有下集播出时间(加更/季中):不算播完
        subscription.setOfficialEpisodes(10);
        subscription.setNextAirTime(System.currentTimeMillis() + 48 * 3600_000L);
        assertFalse(subscription.isSeasonAiredOut());
        assertFalse(MediaSubscriptionCheckService.shouldAutoEnd(subscription, 10));
    }

    // ---------- 退役/拒绝冷却重探 ----------

    @Test
    void badCooldownAllowsReprobeAfter7Days() {
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setState(MediaSubscriptionResource.STATE_RETIRED);
        long now = System.currentTimeMillis();
        resource.setCheckedTime(now - 8L * 24 * 3600_000);
        assertTrue(service.isBadCooled(resource, now));
        resource.setCheckedTime(now - 24 * 3600_000);
        assertFalse(service.isBadCooled(resource, now));
        resource.setCheckedTime(null);
        assertTrue(service.isBadCooled(resource, now));
        resource.setState(MediaSubscriptionResource.STATE_MOUNTED);
        assertFalse(service.isBadCooled(resource, now));
        resource.setState(MediaSubscriptionResource.STATE_REJECTED);
        assertTrue(service.isBadCooled(resource, now), "REJECTED 与 RETIRED 同享冷却重探");
    }

    // ---------- 主源失效确认:AList 整体故障/限流不误杀 ----------

    @Test
    void invalidNotConfirmedWhenAListDown() {
        Fixture fixture = new Fixture();
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new RuntimeException("connection refused"));
        long now = System.currentTimeMillis();
        fixture.service.check(1);
        assertEquals(MediaSubscription.STATUS_ACTIVE, fixture.subscription.getStatus()); // 未误判失效
        assertClose(now + 15 * 60_000L, fixture.subscription.getNextCheckTime()); // 短间隔重试
        Mockito.verify(fixture.resourceRepository, Mockito.never()).save(Mockito.any()); // 未换源未退役
    }

    @Test
    void invalidConfirmedAfterRetryRetiresPrimary() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource active = new MediaSubscriptionResource();
        active.setSubscriptionId(1);
        active.setState(MediaSubscriptionResource.STATE_MOUNTED);
        active.setMountPath("/追剧/1-测试剧");
        active.setLink("https://pan.quark.cn/s/dead-primary");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(active));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/追剧/1-测试剧"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new RuntimeException("share not found"));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(new FsResponse());
        fixture.service.check(1);
        Mockito.verify(fixture.aListService, Mockito.times(2)).listFiles(Mockito.any(), Mockito.eq("/追剧/1-测试剧"),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()); // 静默重试了一次
        assertEquals(MediaSubscriptionResource.STATE_RETIRED, active.getState()); // 确认失效才退役
        assertNull(active.getMountPath());
        assertEquals(MediaSubscription.STATUS_ERROR, fixture.subscription.getStatus()); // 池空且搜索无果
    }

    @Test
    void throttledListingFailureDoesNotInvalidate() {
        // 限流(百度 errno -62)不是主源的错:退避重试,不判失效不换源
        Fixture fixture = new Fixture();
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new RuntimeException("{\"errno\":-62}"));
        long now = System.currentTimeMillis();
        fixture.service.check(1);
        assertEquals(MediaSubscription.STATUS_ACTIVE, fixture.subscription.getStatus());
        assertClose(now + 15 * 60_000L, fixture.subscription.getNextCheckTime());
        Mockito.verify(fixture.resourceRepository, Mockito.never())
                .save(Mockito.any()); // 未换源未退役(主源解析查询不算换源)
    }

    @Test
    void baiduSekeyExpiredListingDoesNotInvalidate() {
        // 线上事故(2026-09-03):分享在网盘 App 里可正常访问,巡检列目录撞百度 sekey 会话过期
        // (errno -9「提取码验证失败,请重试」,瞬时态、重验证可自愈)。错误 JSON 携带 "expired_type":0
        // 字段名(值 0=非过期),曾被 GONE_ERROR 的无边界 expired 误判死链:主源 RETIRED + 90 天黑名单,
        // 换不出候选后订阅落 ERROR(「已退役」+「异常」)。会话过期须退避重试,不退役不换源。
        Fixture fixture = new Fixture();
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new RuntimeException("{\"errno\":-9,\"request_id\":9149375969895791869,"
                        + "\"server_time\":1788395979,\"cfrom_id\":0,\"hitrisk\":0,\"appeal_status\":0,"
                        + "\"is_zombie\":0,\"vip_point\":9779,\"vip_level\":4,\"svip10_id\":\"\","
                        + "\"vip_type\":2,\"sharetype\":1,\"expired_type\":0,\"newno\":\"100000010001\","
                        + "\"show_msg\":\"提取码验证失败,请重试\"}"));
        long now = System.currentTimeMillis();
        fixture.service.check(1);
        assertEquals(MediaSubscription.STATUS_ACTIVE, fixture.subscription.getStatus(), "会话过期不是主源失效");
        assertClose(now + 15 * 60_000L, fixture.subscription.getNextCheckTime());
        Mockito.verify(fixture.resourceRepository, Mockito.never()).save(Mockito.any()); // 不退役不换源
    }

    @Test
    void quarkRiskControlShareAliveDoesNotInvalidate() {
        // 线上事故(2026-09-03,别的用户实例):夸克分享用户可正常访问,但 AList 挂载列目录报
        // 「分享地址已失效」被无条件判死(主源 RETIRED + 90 天黑名单 + 订阅 ERROR)。
        // 夸克对真死链与风控目标(挂载路径带的兜底 Cookie 被风控)返回同文案 —— 游客匿名
        // token 探测是独立第二信源(实测同机活链 code:0 ok),分享活着就不能判死。
        Fixture fixture = new Fixture();
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(11);
        primary.setSubscriptionId(1);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        primary.setLink("https://pan.quark.cn/s/riskcontrolled");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(primary));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/追剧/1-测试剧"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new RuntimeException("failed get share files: 分享地址已失效"));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(new FsResponse());
        fixture.service.quarkTokenFetcher = (pwdId, passcode) ->
                "{\"status\":200,\"code\":0,\"message\":\"ok\",\"data\":{\"stoken\":\"fresh\",\"share_type\":0}}";
        long now = System.currentTimeMillis();
        fixture.service.check(1);
        assertEquals(MediaSubscription.STATUS_ACTIVE, fixture.subscription.getStatus(), "游客探测分享活着,不判主源失效");
        assertClose(now + 15 * 60_000L, fixture.subscription.getNextCheckTime());
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, primary.getState(), "不退役");
        Mockito.verify(fixture.deadLinkRepository, Mockito.never()).save(Mockito.any()); // 不进黑名单
    }

    @Test
    void quarkGuestDeadVerdictStillRetires() {
        // 对照:游客探测确认死链(code 410xx 家族)时第二信源不拦截,原判死换源路径照走
        Fixture fixture = new Fixture();
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(11);
        primary.setSubscriptionId(1);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        primary.setLink("https://pan.quark.cn/s/really-dead");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(primary));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/追剧/1-测试剧"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new RuntimeException("failed get share files: 分享地址已失效"));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(new FsResponse());
        fixture.service.quarkTokenFetcher = (pwdId, passcode) ->
                "{\"status\":404,\"code\":41012,\"message\":\"好友已取消了分享\"}";
        fixture.service.check(1);
        assertEquals(MediaSubscriptionResource.STATE_RETIRED, primary.getState(), "确认死链照常退役");
        assertEquals(MediaSubscription.STATUS_ERROR, fixture.subscription.getStatus()); // 池空且搜索无果
    }

    @Test
    void quarkShareAliveVerdictClassification() {
        // 响应分类:code 0 + stoken = 活;410xx = 死;其它/网络异常(null)= 无结论
        Fixture alive = new Fixture();
        alive.service.quarkTokenFetcher = (p, c) -> "{\"status\":200,\"code\":0,\"message\":\"ok\",\"data\":{\"stoken\":\"fresh\"}}";
        assertEquals(Boolean.TRUE, alive.service.quarkShareAlive("https://pan.quark.cn/s/abcd1234efgh", ""));
        Fixture dead = new Fixture();
        dead.service.quarkTokenFetcher = (p, c) -> "{\"status\":404,\"code\":41012,\"message\":\"好友已取消了分享\"}";
        assertEquals(Boolean.FALSE, dead.service.quarkShareAlive("https://pan.quark.cn/s/x", ""));
        Fixture weird = new Fixture();
        weird.service.quarkTokenFetcher = (p, c) -> "{\"status\":429,\"code\":4294967,\"message\":\"too many\"}";
        assertNull(weird.service.quarkShareAlive("https://pan.quark.cn/s/x", ""), "非 410xx 错误=无结论");
        assertNull(new Fixture().service.quarkShareAlive("https://pan.baidu.com/s/1xyz", ""), "非夸克链接不探测");
    }

    @Test
    void transientListingFailureDoesNotInvalidate() {
        Fixture fixture = new Fixture();
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(files("测试剧.第01集.mkv"));
        fixture.service.check(1);
        assertEquals(MediaSubscription.STATUS_ACTIVE, fixture.subscription.getStatus());
        Mockito.verify(fixture.resourceRepository, Mockito.never()).save(Mockito.any()); // 未判死
    }

    // ---------- 补缺挂载当轮刷新集数口径 ----------
    // 线上「重器」:主源是 4K 组滚动窗(只留最新 4 集),巡检补缺挂上 28 集整季源后,
    // 旧行为 currentEpisodes 停在主源口径 4,页面"已更新至 4 集"要等下轮巡检(6-24h)才追平。

    @Test
    void gapFilledEpisodesCountInSameRound() {
        Fixture fixture = new Fixture();
        fixture.subscription.setOfficialEpisodes(28);
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(2);
        primary.setSubscriptionId(1);
        primary.setType(5);
        primary.setScore(120);
        primary.setTitle("测试剧 4K 滚动更新");
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        MediaSubscriptionResource fullSeason = new MediaSubscriptionResource();
        fullSeason.setId(9);
        fullSeason.setSubscriptionId(1);
        fullSeason.setLink("https://pan.baidu.com/s/full?pwd=9527");
        fullSeason.setType(10);
        fullSeason.setScore(110);
        fullSeason.setTitle("测试剧 (2026) 全28集");
        fullSeason.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(primary, fullSeason));
        // 主源列目录:滚动窗只剩最新 4 集;补缺候选的临时挂载:整季 28 集
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/追剧/1-测试剧"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files("S01E25.mp4", "S01E26.mp4", "S01E27.mp4", "S01E28.mp4"));
        String[] all = IntStream.rangeClosed(1, 28).mapToObj(i -> String.format("S01E%02d.mp4", i)).toArray(String[]::new);
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/probe/full"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files(all));

        // 分集/集源行内存库:save 落 Map,派生查询等价真实库
        RowStore store = new RowStore();
        store.install(fixture);

        Share probe = new Share();
        probe.setType(10);
        probe.setShareId("full");
        Mockito.when(fixture.shareService.parseShareLink("https://pan.baidu.com/s/full?pwd=9527")).thenReturn(probe);
        Share temp = new Share();
        temp.setId(55);
        temp.setPath("/probe/full");
        Mockito.when(fixture.shareRepository.findByTypeAndShareIdAndTempTrue(10, "full")).thenReturn(List.of(temp));
        Mockito.when(fixture.shareRepository.existsByPath(Mockito.anyString())).thenReturn(false);
        Mockito.when(fixture.shareRepository.findByPath(Mockito.startsWith("/追剧/.sources/"))).thenAnswer(inv -> {
            Share share = new Share();
            share.setId(66);
            share.setPath(inv.getArgument(0));
            return share;
        });

        fixture.service.check(1);

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, fullSeason.getState(), "补缺源应已挂载");
        assertEquals(28, fixture.subscription.getCurrentEpisodes(),
                "补缺行本轮已落库,集数口径必须当轮追平,不能停在主源的 4 集等下轮巡检");
        assertEquals(28, fixture.subscription.getMaxEpisode());
    }

    // ---------- 首轮巡检挂上主源后继续走缺集补全 ----------
    // 线上事故(盗妖行):新订阅首轮 doCheck 在 ensureSource 挂上主源后直接 scheduleNext+return,
    // 缺集补全/分盘线路全部跳过;主源偏偏是只留尾部 10 集的分享,而 scheduleNext 又因
    // "下一集后天播"睡到播出前 —— 新订阅首日缺 45 集无人补。

    @Test
    void firstRoundFillsGapsAfterMountingPrimary() {
        Fixture fixture = new Fixture();
        fixture.subscription.setShareId(null); // 首轮:主源未挂
        fixture.subscription.setOfficialEpisodes(55);
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(2);
        primary.setSubscriptionId(1);
        primary.setType(10);
        primary.setScore(120);
        primary.setLink("https://pan.baidu.com/s/tail?pwd=9527");
        primary.setTitle("测试剧 (2026) 更新至55集");
        primary.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        MediaSubscriptionResource fullSeason = new MediaSubscriptionResource();
        fullSeason.setId(9);
        fullSeason.setSubscriptionId(1);
        fullSeason.setLink("https://pan.quark.cn/s/full");
        fullSeason.setType(5);
        fullSeason.setScore(110);
        fullSeason.setTitle("测试剧 (2026) 全55集");
        fullSeason.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(primary, fullSeason));
        // 主源列目录:只留尾部 5 集;补缺候选的临时挂载:整季 55 集
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/追剧/1-测试剧"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files("S01E51.mp4", "S01E52.mp4", "S01E53.mp4", "S01E54.mp4", "S01E55.mp4"));
        String[] all = IntStream.rangeClosed(1, 55).mapToObj(i -> String.format("S01E%02d.mp4", i)).toArray(String[]::new);
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/probe/full"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files(all));

        RowStore store = new RowStore();
        store.install(fixture);

        // activate 主源:挂载落 share 行
        Share mounted = new Share();
        mounted.setId(5);
        mounted.setPath("/追剧/1-测试剧");
        Mockito.when(fixture.shareRepository.findByPath("/追剧/1-测试剧")).thenReturn(mounted);
        // probeShare 复用全集候选的临时挂载
        Share probe = new Share();
        probe.setType(5);
        probe.setShareId("quark-full");
        Mockito.when(fixture.shareService.parseShareLink("https://pan.quark.cn/s/full")).thenReturn(probe);
        Share temp = new Share();
        temp.setId(55);
        temp.setPath("/probe/full");
        Mockito.when(fixture.shareRepository.findByTypeAndShareIdAndTempTrue(5, "quark-full")).thenReturn(List.of(temp));
        Mockito.when(fixture.shareRepository.existsByPath(Mockito.anyString())).thenReturn(false);
        Mockito.when(fixture.shareRepository.findByPath(Mockito.startsWith("/追剧/.sources/"))).thenAnswer(inv -> {
            Share share = new Share();
            share.setId(66);
            share.setPath(inv.getArgument(0));
            return share;
        });

        fixture.service.check(1);

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, fullSeason.getState(),
                "首轮挂上主源后应继续缺集补全,不等下一轮巡检");
        assertEquals(55, fixture.subscription.getCurrentEpisodes(), "首轮即追平全集口径");
        assertEquals(55, fixture.subscription.getMaxEpisode());
    }

    // ---------- 分盘线路落行后轻刷集数快照 ----------
    // 线上事故(盗妖行):详情触发的 ensureDriveLinesAsync 把夸克/UC 全集行落了库,
    // 但 currentEpisodes 停在首轮 activate 写的 10 —— 数据齐了、显示没齐,列表 remarks
    // 「10/60集」要等下轮巡检(6~24h)才追平。

    @Test
    void refreshEpisodeCountersSyncsFromLiveRows() {
        Fixture fixture = new Fixture();
        RowStore store = new RowStore();
        store.install(fixture);
        // 主源行:尾部 10 集(首轮 activate 写的快照也是 10)
        for (int n : List.of(33, 34, 35, 36, 37, 38, 40, 53, 54, 55)) {
            store.addEpisodeAndRow(2, n, MediaSubscriptionEpisodeSource.STATE_LISTED);
        }
        // 分盘线路探测落的全集行
        for (int n = 1; n <= 55; n++) {
            store.addEpisodeAndRow(9, n, MediaSubscriptionEpisodeSource.STATE_LISTED);
        }
        fixture.subscription.setCurrentEpisodes(10);
        fixture.subscription.setMaxEpisode(55);

        fixture.service.refreshEpisodeCounters(fixture.subscription);

        assertEquals(55, fixture.subscription.getCurrentEpisodes(), "行并集口径应即时反映");
        assertEquals(55, fixture.subscription.getMaxEpisode());
        Mockito.verify(fixture.subscriptionRepository).save(fixture.subscription);

        // 已一致时不再写库(避免详情高频触发空写)
        Mockito.clearInvocations(fixture.subscriptionRepository);
        fixture.service.refreshEpisodeCounters(fixture.subscription);
        Mockito.verify(fixture.subscriptionRepository, Mockito.never()).save(Mockito.any());
    }

    private static List<Integer> numbers(Map<Integer, MediaSubscriptionEpisodeSource> rows,
                                         Map<Integer, MediaSubscriptionEpisode> episodes,
                                         java.util.function.Predicate<MediaSubscriptionEpisodeSource> scope,
                                         Collection<String> states) {
        return rows.values().stream()
                .filter(scope::test)
                .filter(r -> states.contains(r.getState()))
                .map(r -> episodes.values().stream()
                        .filter(e -> e.getId().equals(r.getEpisodeId()))
                        .findFirst().map(MediaSubscriptionEpisode::getNumber).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    // ---------- 补缺源转正(候选资源抽屉对已挂载补缺源出「转主源」按钮) ----------
    // 线上「重器」:主源夸克滚动窗越删越少,想把完整 28 集的百度补缺源转正当主源。
    // 缺陷:activate 只删固定路径的旧主源挂载,转正资源自己的 .sources 补缺挂载(常驻、清理豁免)
    // 成无人认领的孤儿 AList 存储,且同链双挂目录重复。

    @Test
    void activatingAuxMountCleansUpItsOldGapShare() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(2);
        primary.setSubscriptionId(1);
        primary.setType(5);
        primary.setTitle("测试剧 4K 滚动更新");
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        primary.setShareId(50);
        MediaSubscriptionResource aux = new MediaSubscriptionResource();
        aux.setId(9);
        aux.setSubscriptionId(1);
        aux.setType(10);
        aux.setLink("https://pan.baidu.com/s/full?pwd=9527");
        aux.setTitle("测试剧 (2026) 全28集");
        aux.setState(MediaSubscriptionResource.STATE_MOUNTED);
        aux.setMountPath("/追剧/.sources/1-测试剧-补1");
        aux.setShareId(52);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(primary, aux));
        // 固定路径:第一次查到旧主源挂载(删除让位),新分享挂上后再查返回新 share
        Share oldShare = new Share();
        oldShare.setId(50);
        oldShare.setPath("/追剧/1-测试剧");
        Share newShare = new Share();
        newShare.setId(53);
        newShare.setPath("/追剧/1-测试剧");
        Mockito.when(fixture.shareRepository.findByPath("/追剧/1-测试剧"))
                .thenReturn(oldShare)
                .thenReturn(newShare);
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/追剧/1-测试剧"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files("S01E01.mp4", "S01E02.mp4", "S01E03.mp4"));
        RowStore store = new RowStore();
        store.install(fixture);

        fixture.service.activate(fixture.subscription, aux);

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, aux.getState());
        assertEquals("/追剧/1-测试剧", aux.getMountPath(), "补缺源接管固定路径");
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, primary.getState(), "旧主源回候选池");
        assertNull(primary.getMountPath());
        Mockito.verify(fixture.shareService).deleteShare(50); // 固定路径旧挂载删除
        Mockito.verify(fixture.shareService).deleteShare(52); // 补缺源旧 .sources 挂载删除,不留孤儿
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(53); // 新挂载不能误删
    }

    // ---------- 标题归属匹配(候选池过滤) ----------

    @Test
    void matchNamesCombinesNameKeywordAndAliases() {
        List<String> names = MediaSubscriptionCheckService.matchNames("苍兰诀", "苍兰诀 夸克", "The Blue Whisper\n短");
        assertEquals(List.of("苍兰诀", "苍兰诀 夸克", "The Blue Whisper"), names);
    }

    @Test
    void matchesTitleAcceptsDecoratedAndAliasedTitles() {
        List<String> names = MediaSubscriptionCheckService.matchNames("苍兰诀", null, "The Blue Whisper");
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "【4K高清】苍兰诀 第01-08集 1080P"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "苍.兰.诀.更至08 / 夸克"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "苍 兰 诀 4K 中字"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "The Blue Whisper S01E05 1080p WEB-DL"));
    }

    @Test
    void matchesTitleRejectsIrrelevantTitles() {
        List<String> names = MediaSubscriptionCheckService.matchNames("苍兰诀", null, null);
        assertFalse(MediaSubscriptionCheckService.matchesTitle(names, "乘风破浪 全12集 4K"));
        assertFalse(MediaSubscriptionCheckService.matchesTitle(names, "庆余年2 更新至06集"));
        assertFalse(MediaSubscriptionCheckService.matchesTitle(names, "1080P 高清资源合集"));
    }

    @Test
    void isNovelTitleRejectsNovelSharesAndKeepsVideoTitles() {
        assertTrue(MediaSubscriptionCheckService.isNovelTitle("《一念永恒》(校对版全本)作者:耳根.txt"));
        assertTrue(MediaSubscriptionCheckService.isNovelTitle("《一念永恒》作者:耳根.txt"));
        assertTrue(MediaSubscriptionCheckService.isNovelTitle("《一念永恒》[精校]作者:耳根.txt"));
        assertTrue(MediaSubscriptionCheckService.isNovelTitle("一念永恒版全本作者耳根.txt"));
        assertTrue(MediaSubscriptionCheckService.isNovelTitle("《一念永恒》(校对版全本)作者:耳根"));
        assertTrue(MediaSubscriptionCheckService.isNovelTitle("一念永恒 by 耳根.txt"));
        assertTrue(MediaSubscriptionCheckService.isNovelTitle("一念永恒.epub"));
        assertFalse(MediaSubscriptionCheckService.isNovelTitle("一念永恒 完结季 [更新至08集] 4K"));
        assertFalse(MediaSubscriptionCheckService.isNovelTitle("一念永恒 全212集 完整版 1080P"));
        assertFalse(MediaSubscriptionCheckService.isNovelTitle("The Last of Us S01E05 1080p WEB-DL"));
        assertFalse(MediaSubscriptionCheckService.isNovelTitle(""));
    }

    @Test
    void matchesTitleToleratesSingleCharObfuscation() {
        List<String> names = List.of("漫长的季节");
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "漫氦的季节 全12集 4K")); // 1 字防审查变形
        assertFalse(MediaSubscriptionCheckService.matchesTitle(names, "漫长的授夜 全12集")); // 2 字差:别剧
    }

    @Test
    void matchesTitleWithoutNamesKeepsOldBehavior() {
        assertTrue(MediaSubscriptionCheckService.matchesTitle(List.of(), "随便什么标题"));
    }

    // ---------- 线上事故回归:归一化后为空的别名打穿标题门禁 ----------
    // 订阅航海王(线上 48):别名快照含 ワンピース/ون بيس/Едно Парче 等纯假名/阿拉伯/西里尔别名,
    // normalizeForMatch 只留 [a-z0-9 汉字] → 这些别名归一化为空串,contains("") 恒真 +
    // isChinese("") 空串真空真 → matchesTitle 对任意标题放行,「年8月16日 短剧更新目录1」
    // 畅通入池并挂成主源,集数清单全是短剧文件。

    @Test
    void matchesTitleIgnoresAliasesThatNormalizeToEmpty() {
        List<String> names = MediaSubscriptionCheckService.matchNames("航海王", "航海王", "海贼王\nワンピース\nون بيس\nONE PIECE");
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "海贼王(1999) 更新至1156集"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "ONE PIECE 1114-1136 1080P"));
        assertFalse(MediaSubscriptionCheckService.matchesTitle(names, "年8月16日 短剧更新目录1"));
        assertFalse(MediaSubscriptionCheckService.matchesTitle(names, "年8月11日 短剧更新目录8"));
    }

    /** 别名快照限幅 12 席不得被死别名挤占:归一化为空的别名 matchesTitle 永不命中,
     *  白占席位会把「海贼王」这类常用旧译名挤出快照,旧译名分享反被标题门禁误杀(航海王线上案)。 */
    @Test
    void aliasSnapshotDropsUnmatchableAliases() throws Exception {
        MetadataDetails details = new MetadataDetails();
        details.setAliases(List.of("ون بيس", "Едно Парче", "ワンピース", "海贼王", "海賊王"));
        MediaSubscription subscription = new MediaSubscription();
        java.lang.reflect.Method snapshot = MediaSubscriptionCheckService.class
                .getDeclaredMethod("applyMetadataSnapshot", MediaSubscription.class, MetadataDetails.class);
        snapshot.setAccessible(true);
        snapshot.invoke(service, subscription, details);

        List<String> joined = List.of(subscription.getAliases().split("\\n"));
        assertTrue(joined.contains("海贼王"), "常用旧译名必须保留");
        assertFalse(joined.contains("ワンピース"), "归一化为空的死别名不得占用席位");
        assertFalse(joined.contains("ون بيس"));
    }

    // ---------- 线上事故回归:单字中文剧名 ----------
    // 订阅《蝉》(2026,name/keyword 均单字):元数据别名快照只留下英文名等 ≥2 字别名,
    // 单字中文名被长度门槛排除出匹配名单 → matchesTitle 只认别名,759 条搜索结果中
    // 453 条中文标题(如"蝉 全21集 [2026][4K]")全被判"剧名不符",订阅 ERROR 无资源。

    @Test
    void matchNamesKeepsSingleCharChineseName() {
        List<String> names = MediaSubscriptionCheckService.matchNames("蝉", "蝉", "Cicada");
        assertTrue(names.contains("蝉"), "单字中文剧名必须进入匹配名单");
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "蝉 全21集 [2026][4K]"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "【4K】蝉 更新至16集 夸克"));
    }

    @Test
    void matchNamesStillRejectsSingleLatinChar() {
        // 单拉丁字符会子串命中大量无关标题,长度门槛必须保留
        List<String> names = MediaSubscriptionCheckService.matchNames("A", "A", null);
        assertTrue(names.isEmpty());
    }

    // ---------- 缺陷 5 回归:剧名带季号后缀时的归属匹配 ----------
    // 线上事故:订阅名/关键词均为"诛仙 第四季",搜索召回 31 条,全部被判不相关。
    // 根因 ①"最长片段"启发式按字符长度取到了"第四季"(3字)而非"诛仙"(2字);
    //      ② 裸剧名从未进入匹配名单,匹配退化为"标题必须含连续的『诛仙第四季』五字"。

    @Test
    void matchNamesIncludesBareShowNameWhenNameCarriesSeason() {
        List<String> names = MediaSubscriptionCheckService.matchNames("诛仙 第四季", "诛仙 第四季", null);
        assertTrue(names.contains("诛仙"), "裸剧名必须进入匹配名单");
    }

    @Test
    void matchNamesRejectsBareSeasonWordAsMatchName() {
        // "第四季"作为匹配名会命中任意一部第四季的剧,必须排除
        List<String> names = MediaSubscriptionCheckService.matchNames("诛仙 第四季", "诛仙 第四季", null);
        assertFalse(names.contains("第四季"), "纯季号词不得作为匹配名");
        assertFalse(MediaSubscriptionCheckService.matchesTitle(names, "斗罗大陆 第四季 全26集"),
                "别剧不得因共享季号词而入池");
    }

    @Test
    void matchesTitleAcceptsRealWorldSeasonVariants() {
        // 线上召回结果的真实形态:季号写法五花八门,归属匹配不该为此背锅
        List<String> names = MediaSubscriptionCheckService.matchNames("诛仙 第四季", "诛仙 第四季", null);
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "诛仙 第四季 全10集 4K"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "诛仙 第4季 1080P 国语"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "诛仙 S04 2160p WEB-DL"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "诛仙4 全10集"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "诛仙动画 第四季 更新至05"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "【4K】诛仙 全集 夸克"));
    }

    @Test
    void parseTitleProgressVariants() {
        assertEquals(8, MediaSubscriptionCheckService.parseTitleProgress("剧名 更新至08集 4K"));
        assertEquals(8, MediaSubscriptionCheckService.parseTitleProgress("剧名 更至08"));
        assertEquals(24, MediaSubscriptionCheckService.parseTitleProgress("剧名 全24集 完结"));
        assertEquals(12, MediaSubscriptionCheckService.parseTitleProgress("剧名 第01-12集"));
        assertEquals(7, MediaSubscriptionCheckService.parseTitleProgress("剧名 第07集"));
        assertEquals(6, MediaSubscriptionCheckService.parseTitleProgress("剧名 EP06"));
        assertEquals(5, MediaSubscriptionCheckService.parseTitleProgress("Show S01E05 1080p"));
        assertNull(MediaSubscriptionCheckService.parseTitleProgress("1080P.HEVC 中字"));
    }

    @Test
    void fillPoolFiltersIrrelevantResults() {
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/other", "乘风破浪 全12集 4K"),
                        message("https://pan.quark.cn/s/mine", "苍兰诀 第01-08集 4K")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(1, "https://pan.quark.cn/s/mine"))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository).save(captor.capture());
        assertEquals("苍兰诀 第01-08集 4K", captor.getValue().getTitle());
        ArgumentCaptor<MediaSubscriptionEvent> events = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository).save(events.capture());
        assertTrue(events.getValue().getDetail().contains("剧名不符 1(例:乘风破浪 全12集 4K)"),
                "落选审计分原因计数并带样例: " + events.getValue().getDetail());
    }

    @Test
    void fillPoolSkipsBlacklistedLinks() {
        // 失效黑名单(dead_link):其它订阅已用取链事实判死过的分享不再入池
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/dead", "苍兰诀 第01-08集 4K"),
                        message("https://pan.quark.cn/s/alive", "苍兰诀 第01-08集 4K")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.eq(1), Mockito.anyString()))
                .thenReturn(Optional.empty());
        Mockito.when(fixture.deadLinkRepository.findByLink("https://pan.quark.cn/s/dead"))
                .thenReturn(Optional.of(new cn.har01d.alist_tvbox.entity.DeadLink()));

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository).save(captor.capture());
        assertEquals("https://pan.quark.cn/s/alive", captor.getValue().getLink(), "黑名单链接不得入池");
    }

    @Test
    void fillPoolBoostsMainDriveCandidates() {
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        Setting mainDrives = setting(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES, "5,10"); // 全局主网盘:夸克/百度
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES))
                .thenReturn(Optional.of(mainDrives));
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/q", "苍兰诀 第01-08集 4K"),
                        message("https://pan.baidu.com/s/b", "苍兰诀 第01-08集 4K", "10"),
                        message("https://115.com/s/x", "苍兰诀 第01-08集 4K", "8")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.eq(1), Mockito.anyString()))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository, Mockito.times(2)).save(captor.capture());
        // 共同底分:近期30+3天内更新20 4K+25 归属+15 = 90;主网盘(夸克/百度)+15,百度另有免会员+17(含夸克易和谐加成)
        int quark = captor.getAllValues().stream().filter(r -> r.getLink().endsWith("/q")).findFirst().orElseThrow().getScore();
        int baidu = captor.getAllValues().stream().filter(r -> r.getLink().endsWith("/b")).findFirst().orElseThrow().getScore();
        assertEquals(105, quark, "主网盘夸克 = 90 + 主网盘15");
        assertEquals(122, baidu, "主网盘百度 = 90 + 主网盘15 + 免会员17");
        assertTrue(captor.getAllValues().stream().noneMatch(r -> r.getLink().endsWith("/x")),
                "未配置扩展网盘:非主网盘 115 不入候选池(默认只有主网盘的源)");
    }

    @Test
    void fillPoolSkipsDuplicateKeywordWithinWindow() {
        // 同轮 ensureSource/fillGaps/ensureMainDrives 连发用的都是订阅词(线上:一念永恒 id=66
        // 一轮巡检 3 次同词全量搜索):窗口内第二次直接跳过,换词(单集降级「第N集」)放行
        Fixture fixture = new Fixture();
        fixture.subscription.setName("一念永恒");
        MediaSubscriptionResource candidate = new MediaSubscriptionResource();
        candidate.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        candidate.setLink("https://pan.quark.cn/s/pooled");
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/ok", "一念永恒 完结季 4K")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(candidate));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.eq(1), Mockito.anyString()))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);
        fixture.service.fillPool(fixture.subscription, true, "一念永恒");
        Mockito.verify(fixture.telegramService, Mockito.times(1))
                .searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any());

        fixture.service.fillPool(fixture.subscription, true, "一念永恒 第9集");
        Mockito.verify(fixture.telegramService, Mockito.times(2))
                .searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any());
    }

    @Test
    void fillPoolAdmitsExtendedDrivesCandidates() {
        // 扩展网盘:全局配置 msub_extended_drives 后,主网盘以外的盘才进候选池
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES, "5,10")));
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_EXTENDED_DRIVES))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_EXTENDED_DRIVES, "8")));
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/q", "苍兰诀 第01-08集 4K"),
                        message("https://pan.baidu.com/s/b", "苍兰诀 第01-08集 4K", "10"),
                        message("https://115.com/s/x", "苍兰诀 第01-08集 4K", "8")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.eq(1), Mockito.anyString()))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository, Mockito.times(3)).save(captor.capture());
        int pan115 = captor.getAllValues().stream().filter(r -> r.getLink().endsWith("/x")).findFirst().orElseThrow().getScore();
        assertEquals(80, pan115, "扩展盘 115 = 90 - 追更弱10(无主网盘加分)");
    }

    private static Setting setting(String name, String value) {
        Setting setting = new Setting();
        setting.setName(name);
        setting.setValue(value);
        return setting;
    }

    @Test
    void weightTableOverridesScoring() {
        // 权重表(Q14):排序偏好可调;调 0 只是不再优先,不会把候选筛空
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        fixture.subscription.setFilterConfig("{\"weights\":{\"quality.uhd\":0,\"match.title\":30}}"); // 4K 不加分,标题归属加到 30
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/q", "苍兰诀 第01-08集 4K")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(1, "https://pan.quark.cn/s/q"))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository).save(captor.capture());
        // 底分 = 近期30+3天内更新20 + 归属30(权重表覆盖 15) ;4K 默认 25 被调没
        assertEquals(80, captor.getValue().getScore(), "权重表覆盖打分:quality.uhd=0 不加分,match.title=30");
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, captor.getValue().getState());
    }

    @Test
    void fillPoolRejectsWrongSeasonTitle() {
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        fixture.subscription.setSeason(2);
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/s1", "苍兰诀 第一季 全36集"),
                        message("https://pan.quark.cn/s/s2", "苍兰诀 第二季 更新至08集")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(1, "https://pan.quark.cn/s/s2"))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository).save(captor.capture());
        assertEquals("苍兰诀 第二季 更新至08集", captor.getValue().getTitle());
    }

    private static Message message(String link, String name) {
        return message(link, name, "5"); // 夸克,在 PAN_TYPES 内
    }

    private static Message message(String link, String name, String type) {
        Message message = new Message();
        message.setLink(link);
        message.setName(name);
        message.setType(type);
        message.setTime(Instant.now());
        return message;
    }

    private MediaSubscription subscription() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(1);
        subscription.setName("测试剧");
        subscription.setKeyword("测试剧 4K");
        return subscription;
    }

    private static void assertClose(long expected, long actual) {
        assertTrue(Math.abs(expected - actual) < 60_000L, "expected ~" + expected + " but was " + actual);
    }

    private static void assertClose(long expected, long actual, String message) {
        assertTrue(Math.abs(expected - actual) < 60_000L, message + ": expected ~" + expected + " but was " + actual);
    }

    private static List<Integer> numbers(int from, int to) {
        List<Integer> list = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            list.add(i);
        }
        return list;
    }

    // ---------- 缺陷 9 回归:网盘限流不是资源失效 ----------
    // 线上事故:主网盘+免会员加分把 3 个百度候选顶到最前,271ms 内连敲百度三次,
    // 触发 errno -62(验证次数过多),3 个可能健康的分享被标 BAD 冷却 7 天。

    @Test
    void baiduThrottleErrorIsNotResourceFailure() {
        assertTrue(MediaSubscriptionCheckService.isThrottleError(
                "/追剧/悬案 [dbid-36624136]: {\"errno\":-62,\"request_id\":8875087666781770331}"));
        assertTrue(MediaSubscriptionCheckService.isThrottleError("验证次数过多,请稍后再试"));
        assertTrue(MediaSubscriptionCheckService.isThrottleError("操作频繁,请稍候"));
        assertTrue(MediaSubscriptionCheckService.isThrottleError("HTTP 429 Too Many Requests"));
    }

    @Test
    void realShareFailureIsStillTreatedAsBad() {
        // 夸克「分享地址已失效」是真失效,必须继续判死 —— 别把限流保护扩大成"什么都不判死"
        assertFalse(MediaSubscriptionCheckService.isThrottleError("/追剧/悬案 [dbid-36624136]: 分享地址已失效"));
        assertFalse(MediaSubscriptionCheckService.isThrottleError("疑似同名异剧(无可识别的本季剧集文件):某标题"));
        assertFalse(MediaSubscriptionCheckService.isThrottleError("挂载失败:https://pan.quark.cn/s/x"));
        assertFalse(MediaSubscriptionCheckService.isThrottleError(null));
    }

    @Test
    void searchSourceValidityIsNormalizedOnAdmission() {
        // 各源状态词大小写不一(盘检返回小写 ok),明确判失效的 → REJECTED(保留行防重复入池)
        assertEquals(MediaSubscriptionResource.STATE_REJECTED, MediaSubscriptionCheckService.admissionState("bad"));
        assertEquals(MediaSubscriptionResource.STATE_REJECTED, MediaSubscriptionCheckService.admissionState("Invalid"));
        assertEquals(MediaSubscriptionResource.STATE_REJECTED, MediaSubscriptionCheckService.admissionState(" EXPIRED "));
        // 盘检 ok 只证明链接可达,不证明挂得上 —— 不许冒充任何更强结论,落 CANDIDATE 等探测
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, MediaSubscriptionCheckService.admissionState("ok"));
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, MediaSubscriptionCheckService.admissionState("OK"));
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, MediaSubscriptionCheckService.admissionState(null));
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, MediaSubscriptionCheckService.admissionState(""));
    }

    // ---------- 候选池分层配额:备用盘必须有保底席位 ----------
    // 主网盘打分领先是结构性的(主网盘+15、百度免会员+15、盘偏好+20/-10),
    // 纯 top-N 会被主网盘包圆 → 主网盘一挂就无源可换。

    @Test
    void poolQuotaReservesSeatsForNonMainDrives() {
        Fixture fixture = new Fixture();
        fixture.subscription.setName("测试剧");
        Setting mainDrives = new Setting();
        mainDrives.setName(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES);
        mainDrives.setValue("5,10"); // 主网盘:夸克/百度
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES))
                .thenReturn(Optional.of(mainDrives));
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_EXTENDED_DRIVES))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_EXTENDED_DRIVES, "8"))); // 115 列为扩展盘
        // 10 条百度 + 10 条夸克(高分,足以占满全池) + 2 条 115(低分,旧实现永远进不来)
        List<Message> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(message("https://pan.baidu.com/s/b" + i, "测试剧 第01-08集 4K", "10"));
            results.add(message("https://pan.quark.cn/s/q" + i, "测试剧 第01-08集 4K", "5"));
        }
        results.add(message("https://115.com/s/x0", "测试剧 第01-08集", "8"));
        results.add(message("https://115.com/s/x1", "测试剧 第01-08集", "8"));
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any())).thenReturn(results);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.eq(1), Mockito.anyString()))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository, Mockito.atLeastOnce()).save(captor.capture());
        long baidu = captor.getAllValues().stream().filter(r -> r.getType() == 10).count();
        long quark = captor.getAllValues().stream().filter(r -> r.getType() == 5).count();
        long others = captor.getAllValues().stream().filter(r -> r.getType() == 8).count();
        assertEquals(3, baidu, "主网盘百度保底 3 席");
        assertEquals(3, quark, "主网盘夸克保底 3 席");
        assertEquals(2, others, "扩展盘必须拿到席位(旧实现为 0 —— 备用盘永远进不了池)");
    }

    @Test
    void poolQuotaFallsBackToGlobalSizeWithoutMainDrives() {
        // 未配置主网盘:退化为单一全局档位,行为与旧的 candidatePoolSize 一致
        Fixture fixture = new Fixture();
        fixture.subscription.setName("测试剧");
        List<Message> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(message("https://pan.quark.cn/s/q" + i, "测试剧 第01-08集 4K"));
        }
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any())).thenReturn(results);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.eq(1), Mockito.anyString()))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        Mockito.verify(fixture.resourceRepository, Mockito.times(5)).save(Mockito.any());
    }

    // ---------- 缺陷 4 回归(v2):死掉的补缺挂载就地退役,行全部判死 ----------
    // 线上事故:补缺源标题「10集全」,分享已死(取链 参数错误),但旧覆盖快照仍声称覆盖 1~10 集,
    // 缺口被陈旧快照扣光 → 不触发补搜 → 播放时"已尝试 1 个源"失败。
    // v2 里覆盖快照 = 集源行;退役把行统一翻 FAILED,liveEpisodeNumbers 自然不再被死源冒领。

    @Test
    void deadAuxMountIsRetiredAndItsRowsKilled() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource aux = new MediaSubscriptionResource();
        aux.setId(9);
        aux.setSubscriptionId(1);
        aux.setLink("https://pan.quark.cn/s/dead");
        aux.setTitle("测试剧 10集全");
        aux.setType(5);
        aux.setState(MediaSubscriptionResource.STATE_MOUNTED);
        aux.setShareId(7);
        aux.setMountPath("/追剧/.sources/1-测试剧-补1");
        // 旧快照声称覆盖 1~10 集(现在体现为 LISTED 行)
        List<MediaSubscriptionEpisodeSource> rows = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            rows.add(sourceRow(20 + i, 100 + i, 9, MediaSubscriptionEpisodeSource.STATE_LISTED, "第" + i + "集.mkv"));
        }
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(aux));
        Mockito.when(fixture.episodeSourceRepository.findByResourceId(9)).thenReturn(rows);
        // 分享已死:列目录抛错
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new IllegalStateException("failed get link: 参数错误"));

        fixture.service.refreshAuxMounts(fixture.subscription);

        Mockito.verify(fixture.shareService).deleteShare(7); // 退役,腾出 maxGapMounts 名额
        assertEquals(MediaSubscriptionResource.STATE_RETIRED, aux.getState(), "列不出内容的挂载必须退役");
        assertNull(aux.getShareId());
        assertNull(aux.getMountPath());
        assertTrue(rows.stream().allMatch(r -> MediaSubscriptionEpisodeSource.STATE_FAILED.equals(r.getState())),
                "退役资源的全部行判 FAILED —— 它不再向任何缺口/播放候选供集");
        ArgumentCaptor<cn.har01d.alist_tvbox.entity.DeadLink> dead =
                ArgumentCaptor.forClass(cn.har01d.alist_tvbox.entity.DeadLink.class);
        Mockito.verify(fixture.deadLinkRepository).save(dead.capture());
        assertEquals("https://pan.quark.cn/s/dead", dead.getValue().getLink(), "判死要写失效黑名单(跨订阅共享)");
    }

    @Test
    void liveAuxMountRefreshesRowsInPlace() {
        // 对照组:分享活着就原位刷新行,不能因为修缺陷 4 把正常补缺源也误杀
        Fixture fixture = new Fixture();
        MediaSubscriptionResource aux = new MediaSubscriptionResource();
        aux.setId(9);
        aux.setSubscriptionId(1);
        aux.setLink("https://pan.quark.cn/s/live");
        aux.setType(5);
        aux.setState(MediaSubscriptionResource.STATE_MOUNTED);
        aux.setShareId(7);
        aux.setMountPath("/追剧/.sources/1-测试剧-补1");
        MediaSubscriptionEpisode ep1 = episode(101, 1);
        MediaSubscriptionEpisode ep2 = episode(102, 2);
        Mockito.when(fixture.episodeRepository.findBySubscriptionIdOrderByNumber(1)).thenReturn(List.of(ep1, ep2));
        MediaSubscriptionEpisodeSource row1 = sourceRow(21, ep1.getId(), 9, MediaSubscriptionEpisodeSource.STATE_LISTED, "第01集.mkv");
        MediaSubscriptionEpisodeSource row2 = sourceRow(22, ep2.getId(), 9, MediaSubscriptionEpisodeSource.STATE_LISTED, "第02集.mkv");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(aux));
        Mockito.when(fixture.episodeSourceRepository.findByResourceId(9)).thenReturn(List.of(row1, row2));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files("测试剧.第01集.mkv", "测试剧.第02集.mkv"));

        fixture.service.refreshAuxMounts(fixture.subscription);

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, aux.getState());
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(Mockito.anyInt());
        assertTrue(row1.getState().equals(MediaSubscriptionEpisodeSource.STATE_LISTED)
                || row1.getState().equals(MediaSubscriptionEpisodeSource.STATE_VERIFIED));
        assertEquals(2, aux.getEpisodesFound(), "行数与目录观测一致");
    }

    @Test
    void baiduSekeyExpiredAuxMountNotRetired() {
        // 线上事故(2026-09-03)同因:补缺挂载刷新撞 errno -9(sekey 过期,expired_type 字段名
        // 曾命中无边界 expired)被整源退役+拉黑 —— 分享与文件都活着,挂载必须原样保留
        Fixture fixture = new Fixture();
        MediaSubscriptionResource aux = new MediaSubscriptionResource();
        aux.setId(9);
        aux.setSubscriptionId(1);
        aux.setLink("https://pan.baidu.com/s/live");
        aux.setType(10);
        aux.setState(MediaSubscriptionResource.STATE_MOUNTED);
        aux.setShareId(7);
        aux.setMountPath("/追剧/.sources/1-测试剧-补1");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(aux));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new IllegalStateException("{\"errno\":-9,\"expired_type\":0,"
                        + "\"show_msg\":\"提取码验证失败,请重试\"}"));

        fixture.service.refreshAuxMounts(fixture.subscription);

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, aux.getState(), "会话过期不退役");
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(Mockito.anyInt());
        Mockito.verify(fixture.deadLinkRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void baiduRateLimitAuxMountNotRetired() {
        // 线上事故(2026-09-04,4567 一晚 7 起):百度 IP 级风控 errno -19「访问频率太快」的中文
        // show_msg 是 \\uXXXX 转义,「请稍后/访问频繁」等中文限流词全部匹配不到,errno 数字又无
        // ASCII 模式 —— 补缺挂载刷新撞 -19 被当死链整源退役 + 90 天黑名单,而分享与文件都活着
        // (用户反馈:资源很全、评分最高的分享被退役,游客实测列目录 errno=0)
        Fixture fixture = new Fixture();
        MediaSubscriptionResource aux = new MediaSubscriptionResource();
        aux.setId(9);
        aux.setSubscriptionId(1);
        aux.setLink("https://pan.baidu.com/s/ratelimited");
        aux.setType(10);
        aux.setState(MediaSubscriptionResource.STATE_MOUNTED);
        aux.setShareId(7);
        aux.setMountPath("/追剧/.sources/1-测试剧-补1");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(aux));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new IllegalStateException("{\"errno\":-19,\"show_msg\":"
                        + "\"\\u8bbf\\u95ee\\u9891\\u7387\\u592a\\u5feb\\u5566\\uff0c\\u8bf7\\u7a0d\\u540e\\u518d\\u8bd5\"}"));

        fixture.service.refreshAuxMounts(fixture.subscription);

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, aux.getState(), "盘级限流不退役");
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(Mockito.anyInt());
        Mockito.verify(fixture.deadLinkRepository, Mockito.never()).save(Mockito.any());
        assertTrue(MediaSubscriptionCheckService.isThrottleError(
                "{\"errno\":-19,\"show_msg\":\"\\u8bbf\\u95ee\\u9891\\u7387\\u592a\\u5feb\\u5566\\uff0c\\u8bf7\\u7a0d\\u540e\\u518d\\u8bd5\"}"),
                "errno -19 转义形态必须命中限流(中文词全转义,只有 ASCII errno 可识别)");
        assertTrue(MediaSubscriptionCheckService.isThrottleError(
                "{\"errno\":-65,\"show_msg\":\"\\u64cd\\u4f5c\\u9891\\u7e41\"}"), "errno -65 同族");
        assertTrue(MediaSubscriptionCheckService.isThrottleError("访问频率太快,请稍后重试(errno=-19)"),
                "驱动翻译后的明文案也要命中");
    }

    @Test
    void quarkRiskControlAuxMountNotRetired() {
        // 夸克「分享地址已失效」同路:游客探测证实分享活着时,补缺挂载原样保留
        // (本机实证:事件「补缺源失效已退役:早春晴朗(分享地址已失效)」,分享页实际可访问)
        Fixture fixture = new Fixture();
        MediaSubscriptionResource aux = new MediaSubscriptionResource();
        aux.setId(9);
        aux.setSubscriptionId(1);
        aux.setLink("https://pan.quark.cn/s/riskcontrolled");
        aux.setType(5);
        aux.setState(MediaSubscriptionResource.STATE_MOUNTED);
        aux.setShareId(7);
        aux.setMountPath("/追剧/.sources/1-测试剧-补1");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(aux));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new IllegalStateException("failed get share files: 分享地址已失效"));
        fixture.service.quarkTokenFetcher = (p, c) ->
                "{\"status\":200,\"code\":0,\"message\":\"ok\",\"data\":{\"stoken\":\"fresh\"}}";

        fixture.service.refreshAuxMounts(fixture.subscription);

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, aux.getState(), "分享活着不退役");
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(Mockito.anyInt());
        Mockito.verify(fixture.deadLinkRepository, Mockito.never()).save(Mockito.any());
    }

    // ---------- 集源行生命周期:syncInventory ----------

    @Test
    void syncInventoryCreatesListedRowsAndMarksMissing() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(2);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        MediaSubscriptionEpisode ep1 = episode(10, 1);
        MediaSubscriptionEpisode ep2 = episode(11, 2);
        MediaSubscriptionEpisode ep3 = episode(12, 3);
        Mockito.when(fixture.episodeRepository.findBySubscriptionIdOrderByNumber(1)).thenReturn(List.of(ep1, ep2, ep3));
        Mockito.when(fixture.episodeRepository.findBySubscriptionIdAndSeasonAndNumber(Mockito.eq(1), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(Optional.of(ep1));
        MediaSubscriptionEpisodeSource row2 = sourceRow(21, ep2.getId(), 2, MediaSubscriptionEpisodeSource.STATE_LISTED, "第02集.mkv");
        MediaSubscriptionEpisodeSource row3 = sourceRow(22, ep3.getId(), 2, MediaSubscriptionEpisodeSource.STATE_LISTED, "第03集.mkv");
        Mockito.when(fixture.episodeSourceRepository.findByResourceId(2)).thenReturn(List.of(row2, row3));
        Mockito.when(fixture.episodeSourceRepository.findByEpisodeIdAndResourceId(Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(Optional.empty());
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files = new TreeMap<>();
        files.put(1, new MediaSubscriptionCheckService.EpisodeFile(1, "/追剧/1-测试剧", "第01集.mkv", 500L, 0L));
        files.put(2, new MediaSubscriptionCheckService.EpisodeFile(2, "/追剧/1-测试剧", "第02集.mkv", 500L, 0L));

        fixture.service.syncInventory(fixture.subscription, primary, "/追剧/1-测试剧", files);

        ArgumentCaptor<MediaSubscriptionEpisodeSource> captor = ArgumentCaptor.forClass(MediaSubscriptionEpisodeSource.class);
        Mockito.verify(fixture.episodeSourceRepository, Mockito.atLeastOnce()).save(captor.capture());
        // 新文件 → 新 LISTED 行,rel_path 是挂载点内相对路径
        MediaSubscriptionEpisodeSource created = captor.getAllValues().stream()
                .filter(r -> r.getEpisodeId() == 10).findFirst().orElseThrow();
        assertEquals(MediaSubscriptionEpisodeSource.STATE_LISTED, created.getState());
        assertEquals("第01集.mkv", created.getRelPath());
        // 目录里消失的文件 → MISSING(不再供集)
        assertEquals(MediaSubscriptionEpisodeSource.STATE_MISSING, row3.getState());
    }

    @Test
    void failedRowRecoversOnNewFileOrAfterCooldown() {
        // FAILED 行的翻案路径:换了文件(路径变化)= 新事实立即重试;同路径判决过 7 天也重试
        Fixture fixture = new Fixture();
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(2);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        MediaSubscriptionEpisode ep17 = episode(10, 17);
        MediaSubscriptionEpisode ep18 = episode(11, 18);
        Mockito.when(fixture.episodeRepository.findBySubscriptionIdOrderByNumber(1)).thenReturn(List.of(ep17, ep18));
        MediaSubscriptionEpisodeSource freshFailed = sourceRow(21, ep17.getId(), 2, MediaSubscriptionEpisodeSource.STATE_FAILED, "第17集-old.mkv");
        freshFailed.setLastVerifiedTime(System.currentTimeMillis());
        MediaSubscriptionEpisodeSource staleFailed = sourceRow(22, ep18.getId(), 2, MediaSubscriptionEpisodeSource.STATE_FAILED, "第18集.mkv");
        staleFailed.setLastVerifiedTime(System.currentTimeMillis() - 8L * 24 * 3600_000);
        Mockito.when(fixture.episodeSourceRepository.findByResourceId(2)).thenReturn(List.of(freshFailed, staleFailed));
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files = new TreeMap<>();
        files.put(17, new MediaSubscriptionCheckService.EpisodeFile(17, "/追剧/1-测试剧", "第17集-new.mkv", 500L, 0L));
        files.put(18, new MediaSubscriptionCheckService.EpisodeFile(18, "/追剧/1-测试剧", "第18集.mkv", 500L, 0L));

        fixture.service.syncInventory(fixture.subscription, primary, "/追剧/1-测试剧", files);

        assertEquals(MediaSubscriptionEpisodeSource.STATE_LISTED, freshFailed.getState(), "文件被换过 = 新事实,回 LISTED 重探");
        assertEquals(MediaSubscriptionEpisodeSource.STATE_LISTED, staleFailed.getState(), "判决超 7 天 = 过期重试(对齐旧损坏登记语义)");
    }

    // ---------- 失败传染(Q11):区分单集损坏与整源失效 ----------

    @Test
    void contagionRetiresWholeShareOnSecondFailure() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource resource = mountedPrimary(2, 9);
        MediaSubscriptionEpisodeSource liveRow = sourceRow(21, 10, 2, MediaSubscriptionEpisodeSource.STATE_LISTED, "第02集.mkv");
        Mockito.when(fixture.episodeSourceRepository.findByResourceId(2)).thenReturn(List.of(liveRow));
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString()))
                .thenThrow(new RuntimeException("failed get link: 链接已过期"));

        boolean dead = fixture.service.contagion(fixture.subscription, resource, 99);

        assertTrue(dead, "二次探测仍失败 = 整源死");
        assertEquals(MediaSubscriptionResource.STATE_RETIRED, resource.getState());
        Mockito.verify(fixture.shareService).deleteShare(9);
        assertEquals(MediaSubscriptionEpisodeSource.STATE_FAILED, liveRow.getState());
    }

    @Test
    void contagionAmbiguousParamErrorDoesNotRetire() {
        // 线上事故回归:主源取链撞"参数错误"反爬窗口(半小时前还在正常拉流),样本+传染同窗同错
        // 被判死删挂载。同文案两义 → 不下结论,挂载与黑名单都不动。
        Fixture fixture = new Fixture();
        MediaSubscriptionResource resource = mountedPrimary(2, 9);
        MediaSubscriptionEpisodeSource liveRow = sourceRow(21, 10, 2, MediaSubscriptionEpisodeSource.STATE_LISTED, "第02集.mkv");
        Mockito.when(fixture.episodeSourceRepository.findByResourceId(2)).thenReturn(List.of(liveRow));
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString()))
                .thenThrow(new RuntimeException("failed get link: 参数错误"));

        assertFalse(fixture.service.contagion(fixture.subscription, resource, 99), "参数错误不下结论");
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, resource.getState());
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(Mockito.anyInt());
    }

    @Test
    void contagionSingleEpisodeDamageKeepsResource() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource resource = mountedPrimary(2, 9);
        MediaSubscriptionEpisodeSource liveRow = sourceRow(21, 10, 2, MediaSubscriptionEpisodeSource.STATE_LISTED, "第02集.mkv");
        Mockito.when(fixture.episodeSourceRepository.findByResourceId(2)).thenReturn(List.of(liveRow));
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString())).thenReturn(new cn.har01d.alist_tvbox.model.FsDetail());

        boolean dead = fixture.service.contagion(fixture.subscription, resource, 99);

        assertFalse(dead, "另一集取链成功 = 仅单集损坏");
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, resource.getState());
        assertEquals(MediaSubscriptionEpisodeSource.STATE_VERIFIED, liveRow.getState(), "探测成功顺带升 VERIFIED");
    }

    @Test
    void contagionThrottleDoesNotRetire() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource resource = mountedPrimary(2, 9);
        MediaSubscriptionEpisodeSource liveRow = sourceRow(21, 10, 2, MediaSubscriptionEpisodeSource.STATE_LISTED, "第02集.mkv");
        Mockito.when(fixture.episodeSourceRepository.findByResourceId(2)).thenReturn(List.of(liveRow));
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString()))
                .thenThrow(new RuntimeException("{\"errno\":-62}"));

        assertFalse(fixture.service.contagion(fixture.subscription, resource, 99), "限流期间不下结论");
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, resource.getState());
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(Mockito.anyInt());
    }

    // ---------- v3:字节级流验证(verifyStream)与故障分级 ----------
    // 解析级验证(AList getFile)只证明"取得到链接";和谐资源的常见形态是目录在、解析过、
    // 拉流 403/HTML。判定矩阵保守:HTML 假页/404/410 才判死,401/403 可能是防盗链要求。

    @Test
    void streamVerdictMatrix() {
        byte[] video = new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3}; // EBML 头(mkv)
        assertEquals(MediaSubscriptionCheckService.StreamVerdict.VERIFIED,
                MediaSubscriptionCheckService.verdictOf(new StreamProbeClient.ProbeResult(200, "video/mp4", video)));
        assertEquals(MediaSubscriptionCheckService.StreamVerdict.VERIFIED,
                MediaSubscriptionCheckService.verdictOf(new StreamProbeClient.ProbeResult(206, "application/octet-stream", video)));
        assertEquals(MediaSubscriptionCheckService.StreamVerdict.FAILED,
                MediaSubscriptionCheckService.verdictOf(new StreamProbeClient.ProbeResult(404, "", new byte[0])));
        assertEquals(MediaSubscriptionCheckService.StreamVerdict.FAILED,
                MediaSubscriptionCheckService.verdictOf(new StreamProbeClient.ProbeResult(410, "", new byte[0])));
        assertEquals(MediaSubscriptionCheckService.StreamVerdict.INCONCLUSIVE,
                MediaSubscriptionCheckService.verdictOf(new StreamProbeClient.ProbeResult(403, "", new byte[0])), "403 可能是防盗链,无结论");
        assertEquals(MediaSubscriptionCheckService.StreamVerdict.INCONCLUSIVE,
                MediaSubscriptionCheckService.verdictOf(new StreamProbeClient.ProbeResult(500, "", new byte[0])));
        assertEquals(MediaSubscriptionCheckService.StreamVerdict.TRANSIENT,
                MediaSubscriptionCheckService.verdictOf(null));
    }

    @Test
    void htmlTrapRequiresDoubleEvidence() {
        byte[] html = "<html><head>".getBytes();
        assertTrue(MediaSubscriptionCheckService.isHtmlTrap(
                new StreamProbeClient.ProbeResult(200, "text/html; charset=utf-8", html)), "ct=html 且实体含 html 标记");
        assertFalse(MediaSubscriptionCheckService.isHtmlTrap(
                new StreamProbeClient.ProbeResult(200, "text/html", new byte[0])), "ct=html 但空体:不下死判");
        assertFalse(MediaSubscriptionCheckService.isHtmlTrap(
                new StreamProbeClient.ProbeResult(200, "video/mp4", html)), "ct=视频,实体像 html 也不判死");
        assertTrue(MediaSubscriptionCheckService.isHtmlTrap(
                new StreamProbeClient.ProbeResult(200, "text/html", "<!DOCTYPE html>".getBytes())));
    }

    @Test
    void probeFailureClassification() {
        assertEquals(MediaSubscriptionCheckService.ProbeFailure.THROTTLED,
                MediaSubscriptionCheckService.classifyProbeFailure(new RuntimeException("{\"errno\":-62}")));
        assertEquals(MediaSubscriptionCheckService.ProbeFailure.TRANSIENT,
                MediaSubscriptionCheckService.classifyProbeFailure(new RuntimeException(
                        "{\"errno\":-9,\"expired_type\":0,\"show_msg\":\"提取码验证失败,请重试\"}")),
                "百度 sekey 过期(errno -9)是瞬时态,不判死");
        assertEquals(MediaSubscriptionCheckService.ProbeFailure.GONE,
                MediaSubscriptionCheckService.classifyProbeFailure(new RuntimeException("failed get link: 参数错误")));
        assertEquals(MediaSubscriptionCheckService.ProbeFailure.GONE,
                MediaSubscriptionCheckService.classifyProbeFailure(new RuntimeException("分享已失效")));
        assertEquals(MediaSubscriptionCheckService.ProbeFailure.GONE,
                MediaSubscriptionCheckService.classifyProbeFailure(new RuntimeException("object not found")));
        assertEquals(MediaSubscriptionCheckService.ProbeFailure.GONE,
                MediaSubscriptionCheckService.classifyProbeFailure(new RuntimeException("share link is expired")),
                "真死链英文文案仍判死(词边界不粘连)");
        assertEquals(MediaSubscriptionCheckService.ProbeFailure.TRANSIENT,
                MediaSubscriptionCheckService.classifyProbeFailure(new RuntimeException("{\"expired_type\":0}")),
                "expired_type 是字段名(值 0=非过期),不得当死链证据");
        assertEquals(MediaSubscriptionCheckService.ProbeFailure.TRANSIENT,
                MediaSubscriptionCheckService.classifyProbeFailure(new RuntimeException("connect timed out")));
        // 未识别错误默认按瞬时:误判瞬时只晚一轮,误判失效会 RETIRED + 跨订阅黑名单
        assertEquals(MediaSubscriptionCheckService.ProbeFailure.TRANSIENT,
                MediaSubscriptionCheckService.classifyProbeFailure(new RuntimeException("奇怪的未知错误")));
    }

    @Test
    void verifyStreamFetchesRealBytesAndStripsFragment() throws Exception {
        Fixture fixture = new Fixture();
        String[] fetchedUrl = {null};
        fixture.service.setStreamProbeClient((url, userAgent, maxBytes, timeoutSeconds) -> {
            fetchedUrl[0] = url;
            return new StreamProbeClient.ProbeResult(206, "video/mp4", new byte[]{0x1A, 0x45});
        });
        cn.har01d.alist_tvbox.model.FsDetail detail = new cn.har01d.alist_tvbox.model.FsDetail();
        detail.setRawUrl("https://dl.quark.cn/x.mp4#x-referer=raw");
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString())).thenReturn(detail);
        MediaSubscriptionEpisodeSource row = sourceRow(21, 10, 2, MediaSubscriptionEpisodeSource.STATE_LISTED, "第02集.mkv");

        assertEquals(MediaSubscriptionCheckService.StreamVerdict.VERIFIED,
                fixture.service.verifyStream("/追剧/1-测试剧", row));
        assertEquals("https://dl.quark.cn/x.mp4", fetchedUrl[0], "quark #x-referer 一类 URL 片段必须剥掉再探测");
    }

    @Test
    void verifyStreamHtmlTrapIsFailed() {
        Fixture fixture = new Fixture();
        fixture.service.setStreamProbeClient((url, userAgent, maxBytes, timeoutSeconds) ->
                new StreamProbeClient.ProbeResult(200, "text/html", "<html>请登录</html>".getBytes()));
        cn.har01d.alist_tvbox.model.FsDetail detail = new cn.har01d.alist_tvbox.model.FsDetail();
        detail.setRawUrl("https://pan.baidu.com/share/x.mp4");
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString())).thenReturn(detail);
        MediaSubscriptionEpisodeSource row = sourceRow(21, 10, 2, MediaSubscriptionEpisodeSource.STATE_LISTED, "第02集.mkv");

        assertEquals(MediaSubscriptionCheckService.StreamVerdict.FAILED,
                fixture.service.verifyStream("/追剧/1-测试剧", row), "和谐登录页 = 链死");
    }

    @Test
    void verifyStreamBlankRawUrlStaysResolutionLevel() {
        // 代理型驱动无直链:解析成功即 VERIFIED(维持解析级语义,不倒退),且不发字节请求
        Fixture fixture = new Fixture();
        fixture.service.setStreamProbeClient((url, userAgent, maxBytes, timeoutSeconds) -> {
            throw new AssertionError("无直链不应发起字节探测");
        });
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString()))
                .thenReturn(new cn.har01d.alist_tvbox.model.FsDetail());
        MediaSubscriptionEpisodeSource row = sourceRow(21, 10, 2, MediaSubscriptionEpisodeSource.STATE_LISTED, "第02集.mkv");

        assertEquals(MediaSubscriptionCheckService.StreamVerdict.VERIFIED,
                fixture.service.verifyStream("/追剧/1-测试剧", row));
    }

    @Test
    void verifyStreamNetworkBlipIsTransientNotFailed() {
        Fixture fixture = new Fixture();
        fixture.service.setStreamProbeClient((url, userAgent, maxBytes, timeoutSeconds) -> {
            throw new java.io.IOException("connect timed out");
        });
        cn.har01d.alist_tvbox.model.FsDetail detail = new cn.har01d.alist_tvbox.model.FsDetail();
        detail.setRawUrl("https://dl.quark.cn/x.mp4");
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString())).thenReturn(detail);
        MediaSubscriptionEpisodeSource row = sourceRow(21, 10, 2, MediaSubscriptionEpisodeSource.STATE_LISTED, "第02集.mkv");

        assertEquals(MediaSubscriptionCheckService.StreamVerdict.TRANSIENT,
                fixture.service.verifyStream("/追剧/1-测试剧", row));
    }

    @Test
    void verifyStreamGoneResolveFailureIsFailed() {
        Fixture fixture = new Fixture();
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString()))
                .thenThrow(new RuntimeException("failed get link: 分享已失效"));
        MediaSubscriptionEpisodeSource row = sourceRow(21, 10, 2, MediaSubscriptionEpisodeSource.STATE_LISTED, "第02集.mkv");

        assertEquals(MediaSubscriptionCheckService.StreamVerdict.FAILED,
                fixture.service.verifyStream("/追剧/1-测试剧", row));
    }

    @Test
    void contagionInconclusiveProbeDoesNotRetire() {
        // 二次探测 403(防盗链):无结论不判整源死 —— 误判失效会进跨订阅黑名单
        Fixture fixture = new Fixture();
        MediaSubscriptionResource resource = mountedPrimary(2, 9);
        MediaSubscriptionEpisodeSource liveRow = sourceRow(21, 10, 2, MediaSubscriptionEpisodeSource.STATE_LISTED, "第02集.mkv");
        Mockito.when(fixture.episodeSourceRepository.findByResourceId(2)).thenReturn(List.of(liveRow));
        fixture.service.setStreamProbeClient((url, userAgent, maxBytes, timeoutSeconds) ->
                new StreamProbeClient.ProbeResult(403, "", new byte[0]));
        cn.har01d.alist_tvbox.model.FsDetail detail = new cn.har01d.alist_tvbox.model.FsDetail();
        detail.setRawUrl("https://dl.quark.cn/x.mp4");
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString())).thenReturn(detail);

        assertFalse(fixture.service.contagion(fixture.subscription, resource, 99), "无结论不判死");
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, resource.getState());
        assertEquals(MediaSubscriptionEpisodeSource.STATE_LISTED, liveRow.getState(), "行状态不动");
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(Mockito.anyInt());
    }

    @Test
    void transientStreakEscalatesAfterLimitAndResets() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource resource = mountedPrimary(2, 9);

        assertFalse(fixture.service.transientStreakReached(resource), "第 1 次瞬时:不下结论");
        assertFalse(fixture.service.transientStreakReached(resource), "第 2 次瞬时:不下结论");
        assertTrue(fixture.service.transientStreakReached(resource), "第 3 次(默认上限)按失效处理");
        assertFalse(fixture.service.transientStreakReached(resource), "计数已清零,重新累计");
    }

    @Test
    void fillPoolDeadLinkWindowExpires() {
        // 黑名单窗口(默认 90 天):过期判死记录不再拦截入池,该链可重新试错
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/dead", "苍兰诀 第01-08集 4K"),
                        message("https://pan.quark.cn/s/alive", "苍兰诀 第01-08集 4K")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.eq(1), Mockito.anyString()))
                .thenReturn(Optional.empty());
        cn.har01d.alist_tvbox.entity.DeadLink stale = new cn.har01d.alist_tvbox.entity.DeadLink();
        stale.setLink("https://pan.quark.cn/s/dead");
        stale.setTime(System.currentTimeMillis() - 91L * 24 * 3600_000);
        Mockito.when(fixture.deadLinkRepository.findByLink("https://pan.quark.cn/s/dead"))
                .thenReturn(Optional.of(stale));

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository, Mockito.atLeastOnce()).save(captor.capture());
        assertEquals("https://pan.quark.cn/s/alive", captor.getValue().getLink(), "过期死链放行重新试错");
    }

    // ---------- 播放选源:集源行索引 + VERIFIED 优先 ----------

    @Test
    void playCandidatesPreferVerifiedRowOverHigherScoredListed() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource listed = mountedPrimary(3, 9);
        listed.setScore(90);
        MediaSubscriptionResource verified = mountedPrimary(4, 10);
        verified.setScore(10);
        MediaSubscriptionEpisodeSource listedRow = sourceRow(21, 100, 3, MediaSubscriptionEpisodeSource.STATE_LISTED, "第17集.mkv");
        MediaSubscriptionEpisodeSource verifiedRow = sourceRow(22, 100, 4, MediaSubscriptionEpisodeSource.STATE_VERIFIED, "第17集.mkv");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(listed, verified));
        Mockito.when(fixture.episodeSourceRepository.findBySubscriptionAndNumber(1, 17))
                .thenReturn(List.of(listedRow, verifiedRow));

        List<MediaSubscriptionCheckService.PlayCandidate> candidates = fixture.service.playCandidates(fixture.subscription, 17);

        assertEquals(2, candidates.size());
        assertEquals(4, candidates.getFirst().resource().getId(), "VERIFIED 行先于高分 LISTED 行");
    }

    @Test
    void playCandidatesExcludeFailedRowsAndUnmountedResources() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource mounted = mountedPrimary(3, 9);
        MediaSubscriptionResource retired = mountedPrimary(4, 10);
        retired.setState(MediaSubscriptionResource.STATE_RETIRED);
        retired.setMountPath(null);
        MediaSubscriptionEpisodeSource live = sourceRow(21, 100, 3, MediaSubscriptionEpisodeSource.STATE_LISTED, "第17集.mkv");
        MediaSubscriptionEpisodeSource failed = sourceRow(22, 100, 3, MediaSubscriptionEpisodeSource.STATE_FAILED, "第17集.old.mkv");
        MediaSubscriptionEpisodeSource retiredRow = sourceRow(23, 100, 4, MediaSubscriptionEpisodeSource.STATE_LISTED, "第17集.mkv");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(mounted, retired));
        Mockito.when(fixture.episodeSourceRepository.findBySubscriptionAndNumber(1, 17))
                .thenReturn(List.of(live, failed, retiredRow));

        List<MediaSubscriptionCheckService.PlayCandidate> candidates = fixture.service.playCandidates(fixture.subscription, 17);

        assertEquals(1, candidates.size(), "FAILED 行与未挂载资源的行都不参与选源");
        assertEquals(3, candidates.getFirst().resource().getId());
    }

    // ---------- 转存校验回灌:列得出、拷不过去 → 行级 FAILED(旧 broken_episodes 的替代品) ----------

    @Test
    void markTransferBrokenMarksOnlyOwningResourceRow() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource primary = mountedPrimary(3, 9); // /追剧/1-测试剧
        MediaSubscriptionResource aux = mountedPrimary(4, 10);
        aux.setMountPath("/追剧/.sources/1-测试剧-补1");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(primary, aux));
        MediaSubscriptionEpisodeSource primaryRow = sourceRow(21, 100, 3, MediaSubscriptionEpisodeSource.STATE_LISTED, "第17集.mkv");
        MediaSubscriptionEpisodeSource auxRow = sourceRow(22, 100, 4, MediaSubscriptionEpisodeSource.STATE_LISTED, "第17集.mkv");
        Mockito.when(fixture.episodeSourceRepository.findBySubscriptionAndNumber(1, 17))
                .thenReturn(List.of(primaryRow, auxRow));

        fixture.service.markTransferBroken(fixture.subscription, Map.of(17, "/追剧/.sources/1-测试剧-补1/第17集.mkv"));

        assertEquals(MediaSubscriptionEpisodeSource.STATE_FAILED, auxRow.getState(), "拷不过去的行判 FAILED");
        assertEquals(MediaSubscriptionEpisodeSource.STATE_LISTED, primaryRow.getState(), "其它资源的同集行不受牵连");
    }

    // ---------- 工具 ----------

    private static MediaSubscriptionResource mountedPrimary(int id, int shareId) {
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(id);
        resource.setSubscriptionId(1);
        resource.setLink("https://pan.quark.cn/s/r" + id);
        resource.setState(MediaSubscriptionResource.STATE_MOUNTED);
        resource.setMountPath("/追剧/1-测试剧");
        resource.setShareId(shareId);
        return resource;
    }

    private static MediaSubscriptionEpisode episode(int id, int number) {
        MediaSubscriptionEpisode episode = new MediaSubscriptionEpisode();
        episode.setId(id);
        episode.setSubscriptionId(1);
        episode.setSeason(1);
        episode.setNumber(number);
        return episode;
    }

    private static MediaSubscriptionEpisodeSource sourceRow(int id, int episodeId, int resourceId, String state, String relPath) {
        MediaSubscriptionEpisodeSource row = new MediaSubscriptionEpisodeSource();
        row.setId(id);
        row.setEpisodeId(episodeId);
        row.setResourceId(resourceId);
        row.setState(state);
        row.setRelPath(relPath);
        return row;
    }

    /** 构造一个 AList 目录响应:全部按视频文件计,体积过 minEpisodeSizeMb 下限。 */
    private static FsResponse files(String... names) {
        FsResponse response = new FsResponse();
        List<FsInfo> list = new ArrayList<>();
        for (String name : names) {
            FsInfo info = new FsInfo();
            info.setName(name);
            info.setType(0);
            info.setSize(500L * 1024 * 1024);
            list.add(info);
        }
        response.setFiles(list);
        return response;
    }

    // ---------- 画质标记惩罚(线上「悬案」主源:Season 1（HQ.DV.60fps）14 集 + Season 1（SDR.50fps）17 集,
    // 两个季文件夹、文件名不带标记,先到先得选中 DV 版 → 整屏泛绿) ----------

    @Test
    void picturePenaltyRanksDolbyVisionWorst() {
        assertEquals(2, TextUtils.picturePenalty("悬案.E05.4K.HQ.DV.60fps.mkv"));
        assertEquals(2, TextUtils.picturePenalty("Show.S01E05.DoVi.2160p.mkv"));
        assertEquals(2, TextUtils.picturePenalty("悬案.E05.Dolby Vision.mkv"));
        assertEquals(2, TextUtils.picturePenalty("悬案.E05.杜比视界.mkv"));
    }

    @Test
    void picturePenaltyRanksHdrBelowPlain() {
        assertEquals(1, TextUtils.picturePenalty("悬案.E05.4K.HDR10.mkv"));
        assertEquals(1, TextUtils.picturePenalty("Show.E05.HDR.mkv"));
    }

    @Test
    void picturePenaltyFallsBackToNearestFolderMarker() {
        // 线上形态:标记在季文件夹名上,文件名本身不带
        assertEquals(2, TextUtils.picturePenalty("/追剧/悬案/Season 1（HQ.DV.60fps）/01.4K.60fps.mkv"),
                "最近的目录段带 DV → 判 DV");
        assertEquals(0, TextUtils.picturePenalty("/追剧/悬案/Season 1（SDR.50fps）/01.4K.50fps.mkv"));
        // 深处的显式标记胜过外层:文件自带 DV 压过 SDR 目录名;SDR 目录终答压过 DV 根目录噪声
        assertEquals(2, TextUtils.picturePenalty("/pack/Season 1（SDR）/05.DV.60fps.mkv"));
        assertEquals(0, TextUtils.picturePenalty("[HQ.DV.60fps&SDR.50fps]/Season 1（SDR.50fps）/01.mkv"));
        // 双压包根目录混标区分不到文件级:跳过,不惩罚
        assertEquals(0, TextUtils.picturePenalty("[HQ.DV.60fps&SDR.50fps]/01.mkv"));
    }

    @Test
    void picturePenaltyIgnoresPlainSdrAndDirMarkers() {
        assertEquals(0, TextUtils.picturePenalty("悬案.E05.4K.SDR.50fps.mkv"));
        assertEquals(0, TextUtils.picturePenalty("悬案.E05.1080p.mkv"));
        assertEquals(0, TextUtils.picturePenalty("Movie.DVDRip.x264.mkv"), "DVDRip 的 DV 无词边界,不误伤");
    }

    // ---------- 分盘线路挂载回收:同盘冗余清理,保住线路挂载 ----------

    @Test
    void retireCoveredAuxMountsKeepsDriveLineAndDropsRedundant() {
        // 线上形态:百度主源 17 集全,夸克整季线路挂载 + 同盘冗余挂载 + 115 单集线路挂载。
        // 旧规则"主源已覆盖即退役"会把线路挂载整批回收 → TVBox 永远只有 2 条一样的线路。
        Fixture fixture = new Fixture();
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(1);
        primary.setSubscriptionId(1);
        primary.setType(10);
        primary.setScore(108);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        MediaSubscriptionResource quarkLine = new MediaSubscriptionResource();
        quarkLine.setId(9);
        quarkLine.setSubscriptionId(1);
        quarkLine.setType(5);
        quarkLine.setScore(108);
        quarkLine.setState(MediaSubscriptionResource.STATE_MOUNTED);
        quarkLine.setShareId(71);
        quarkLine.setMountPath("/追剧/.sources/1-测试剧-补1");
        MediaSubscriptionResource quarkRedundant = new MediaSubscriptionResource();
        quarkRedundant.setId(10);
        quarkRedundant.setSubscriptionId(1);
        quarkRedundant.setType(5);
        quarkRedundant.setScore(100);
        quarkRedundant.setState(MediaSubscriptionResource.STATE_MOUNTED);
        quarkRedundant.setShareId(72);
        quarkRedundant.setMountPath("/追剧/.sources/1-测试剧-补2");
        MediaSubscriptionResource pan115Line = new MediaSubscriptionResource();
        pan115Line.setId(11);
        pan115Line.setSubscriptionId(1);
        pan115Line.setType(8);
        pan115Line.setScore(93);
        pan115Line.setState(MediaSubscriptionResource.STATE_MOUNTED);
        pan115Line.setShareId(73);
        pan115Line.setMountPath("/追剧/.sources/1-测试剧-补3");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(primary, quarkLine, quarkRedundant, pan115Line));
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(Mockito.eq(9), Mockito.anyCollection()))
                .thenReturn(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17));
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(Mockito.eq(10), Mockito.anyCollection()))
                .thenReturn(List.of(1, 2)); // 夸克冗余挂载:覆盖是同盘线路挂载的子集
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(Mockito.eq(11), Mockito.anyCollection()))
                .thenReturn(List.of(15));
        Mockito.when(fixture.episodeSourceRepository.findByResourceId(10)).thenReturn(List.of(
                sourceRow(30, 101, 10, MediaSubscriptionEpisodeSource.STATE_LISTED, "第01集.mkv"),
                sourceRow(31, 102, 10, MediaSubscriptionEpisodeSource.STATE_LISTED, "第02集.mkv")));

        fixture.service.retireCoveredAuxMounts(fixture.subscription,
                new TreeSet<>(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17)));

        Mockito.verify(fixture.shareService).deleteShare(72); // 同盘纯冗余:退
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(71); // 夸克线路挂载:主源已覆盖也保留
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(73); // 115 单集线路:有独占集,保留
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, quarkRedundant.getState());
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, quarkLine.getState());
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, pan115Line.getState());
    }

    // ---------- 流探测误杀事故(2026-08-22 20:09):主源半小时前可正常拉流,取链撞"参数错误"
    // 反爬窗口,样本+传染 2.4s 内同错被判死 → 删挂载+90 天黑名单,固定路径空到下轮(详情 404) ----------
    @Test
    void streamVerifyTreatsAmbiguousParamErrorAsTransient() {
        // "参数错误"两义(真死链/百度游客反爬窗口):单次不下结论,防相关双探连带误杀
        Fixture fixture = new Fixture();
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString()))
                .thenThrow(new IllegalStateException("failed link: failed get link: 参数错误"));
        MediaSubscriptionEpisodeSource row = sourceRow(99, 101, 212, MediaSubscriptionEpisodeSource.STATE_LISTED, "第01集.mkv");
        assertEquals(MediaSubscriptionCheckService.StreamVerdict.TRANSIENT, fixture.service.verifyStream("/追剧/悬案", row));
    }

    @Test
    void streamVerifyStillFailsOnExplicitExpiry() {
        // 对照:明确"链接已过期"(115 errno 4100018 形态)仍判死,真死链不能漏
        Fixture fixture = new Fixture();
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString()))
                .thenThrow(new IllegalStateException("failed get link: {\"state\":false,\"msg\":\"链接已过期\",\"errno\":4100018}"));
        MediaSubscriptionEpisodeSource row = sourceRow(99, 101, 212, MediaSubscriptionEpisodeSource.STATE_LISTED, "第01集.mkv");
        assertEquals(MediaSubscriptionCheckService.StreamVerdict.FAILED, fixture.service.verifyStream("/追剧/悬案", row));
    }

    @Test
    void probeShareRejectsLinkDeadResourceBeforeMount() {
        // 列得出 ≠ 播得了:115 单集分享分享页活着、文件链已过期 —— 探测期就按"链接已过期"判废,
        // 不挂载占名额(旧形态:挂上后下轮采样才死,白挂一轮)
        Fixture fixture = new Fixture();
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(9);
        resource.setSubscriptionId(1);
        resource.setLink("https://115.com/s/dead");
        resource.setTitle("📺 悬案 (2026) S01E16 ✨4K WEB-DL AAC");
        resource.setType(8);
        resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Share temp = new Share();
        temp.setId(77);
        temp.setPath("/我的115分享/temp/115@dead@");
        Share probe = new Share();
        probe.setType(8);
        probe.setShareId("dead");
        Mockito.when(fixture.shareService.parseShareLink("https://115.com/s/dead")).thenReturn(probe);
        Mockito.when(fixture.shareRepository.findByTypeAndShareIdAndTempTrue(8, "dead")).thenReturn(List.of(temp));
        Mockito.when(fixture.shareRepository.findByPath("/我的115分享/temp/115@dead@")).thenReturn(temp);
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files("悬案.S01E16.4K.WEB-DL.AAC.mkv"));
        Mockito.when(fixture.episodeSourceRepository.findByResourceId(9))
                .thenReturn(List.of(sourceRow(30, 101, 9, MediaSubscriptionEpisodeSource.STATE_LISTED, "悬案.S01E16.4K.WEB-DL.AAC.mkv")));
        Mockito.when(fixture.episodeRepository.findBySubscriptionIdOrderByNumber(1))
                .thenReturn(List.of(episode(101, 16)));
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString()))
                .thenThrow(new IllegalStateException("failed get link: {\"state\":false,\"msg\":\"链接已过期\",\"errno\":4100018}"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> fixture.service.probeShare(fixture.subscription, resource));
        assertTrue(error.getMessage().contains("链接已过期"), "判废文案须含 GONE 措辞,让调用方退役+黑名单: " + error.getMessage());
        Mockito.verify(fixture.shareService).deleteShare(77); // 临时挂载窗口用后即删
    }

    // ---------- 年份门禁(2026-08-22 20:31):「悬案」2026 的池子被「悬案解码 Dept. Q (2025)」
    // 以子串包含骗过归属校验并挂成主源 —— 标题标注年份与元数据年份全不符即拒 ----------

    @Test
    void titleYearGateForms() {
        Integer expected = 2026;
        List<String> names = List.of("悬案");
        assertTrue(MediaSubscriptionCheckService.titleYearMatches(null, names, "随便什么 2025"), "未绑元数据年份:门禁关闭");
        assertTrue(MediaSubscriptionCheckService.titleYearMatches(expected, names, "悬案 4K 高码率 17集全"), "标题无年份:放行");
        assertTrue(MediaSubscriptionCheckService.titleYearMatches(expected, names, "悬案 (2026) 4K [17集全]"), "年份相符:放行");
        assertTrue(MediaSubscriptionCheckService.titleYearMatches(expected, names, "2025年度盘点 悬案 (2026) 4K"),
                "标题含多个年份且其一相符:放行");
        assertFalse(MediaSubscriptionCheckService.titleYearMatches(expected, names,
                "【悬疑迷必看】2025悬疑剧大赏（4K收藏！）[英剧]悬案解码 第一季 Dept. Q Season 1 (2025) 4k中字百度"),
                "年份全不符且剧名仅子串嵌入更长词(悬案⊂悬案解码):拒");
        assertTrue(MediaSubscriptionCheckService.titleYearMatches(expected, names, "悬案 1080p 60fps 2160p"),
                "分辨率/帧率数字不得误配为年份");
    }

    @Test
    void titleYearGateAllowsFranchisePackWithFirstSeasonYear() {
        // 动漫多季:全系列包常标第一季年代,剧名整词命中 = 同名作,放行(交给季过滤/探测定夺)
        List<String> names = List.of("鬼灭之刃 锻刀村篇", "鬼灭之刃");
        assertTrue(MediaSubscriptionCheckService.titleYearMatches(2023, names, "鬼灭之刃 (2019) 1-26季 合集 4K"),
                "整词命中剧名+首季年代:放行");
        assertTrue(MediaSubscriptionCheckService.titleYearMatches(2023, names, "鬼灭之刃 (2019) 全集"),
                "无季标记的全系列包:放行");
        // 同名翻拍/异版(整词命中但年代不符)也放行:名字层面无法区分,季过滤/探测兜底
        assertTrue(MediaSubscriptionCheckService.titleYearMatches(2026, List.of("悬案"), "悬案 (1999) 国产老版 全20集"));
        // 反例:整词命中不了(嵌在更长词里)且年份不符 → 仍拒
        assertFalse(MediaSubscriptionCheckService.titleYearMatches(2023, List.of("鬼灭之刃 锻刀村篇"),
                "鬼灭之刃花街篇 (2021) 4K"), "别名未收录的花街篇+年代不符:拒");
    }

    @Test
    void candidatesOrderedFiltersWrongYearResources() {
        Fixture fixture = new Fixture();
        fixture.subscription.setMetaProvider("douban");
        fixture.subscription.setMetaId("36624136");
        MetadataDetails details = new MetadataDetails();
        details.setYear("2026");
        Mockito.when(fixture.metadataService.details(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(details);
        MediaSubscriptionResource wrongYear = new MediaSubscriptionResource();
        wrongYear.setId(31);
        wrongYear.setSubscriptionId(1);
        wrongYear.setTitle("[英剧]悬案解码 第一季 Dept. Q Season 1 (2025) 4k中字");
        wrongYear.setType(10);
        wrongYear.setScore(120);
        wrongYear.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        MediaSubscriptionResource rightYear = new MediaSubscriptionResource();
        rightYear.setId(32);
        rightYear.setSubscriptionId(1);
        rightYear.setTitle("悬案 (2026) 4K 高码率 [17集全]");
        rightYear.setType(10);
        rightYear.setScore(108);
        rightYear.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        MediaSubscriptionResource noYear = new MediaSubscriptionResource();
        noYear.setId(33);
        noYear.setSubscriptionId(1);
        noYear.setTitle("悬案 4K 高码率 更17集");
        noYear.setType(5);
        noYear.setScore(100);
        noYear.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        MediaSubscriptionResource franchisePack = new MediaSubscriptionResource();
        franchisePack.setId(34);
        franchisePack.setSubscriptionId(1);
        franchisePack.setTitle("测试剧 (2015) 1-3季 合集 4K"); // 动漫全系列包:首季年代+剧名整词命中
        franchisePack.setType(10);
        franchisePack.setScore(95);
        franchisePack.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(wrongYear, rightYear, noYear, franchisePack));

        List<MediaSubscriptionResource> candidates = fixture.service.candidatesOrdered(fixture.subscription);

        assertEquals(List.of(32, 33, 34), candidates.stream().map(MediaSubscriptionResource::getId).toList(),
                "前缀异剧(2025 悬案解码)被挡,年份相符/无年份/首季年代全系列包照常入列");
    }

    @Test
    void candidatesOrderedFiltersOffPoolDrives() {
        // 盘白名单:配置主网盘后,非主/扩展盘的存量候选不再被探测/换源/补线;未配置时不限盘
        Fixture fixture = new Fixture();
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES, "5")));
        MediaSubscriptionResource quark = new MediaSubscriptionResource();
        quark.setId(41);
        quark.setSubscriptionId(1);
        quark.setTitle("测试剧 4K 更新至10集");
        quark.setType(5);
        quark.setScore(100);
        quark.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        MediaSubscriptionResource pan115 = new MediaSubscriptionResource();
        pan115.setId(42);
        pan115.setSubscriptionId(1);
        pan115.setTitle("测试剧 4K 全集");
        pan115.setType(8);
        pan115.setScore(95);
        pan115.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        MediaSubscriptionResource legacy = new MediaSubscriptionResource();
        legacy.setId(43);
        legacy.setSubscriptionId(1);
        legacy.setTitle("测试剧 全集");
        legacy.setType(null); // 旧数据无 type:判不了盘,白名单配置后视为域外
        legacy.setScore(90);
        legacy.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(quark, pan115, legacy));

        assertEquals(List.of(41), fixture.service.candidatesOrdered(fixture.subscription).stream()
                        .map(MediaSubscriptionResource::getId).toList(),
                "主盘夸克保留,非白名单 115 与无 type 旧资源出局");

        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES))
                .thenReturn(Optional.empty());
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_EXTENDED_DRIVES))
                .thenReturn(Optional.empty());
        assertEquals(List.of(41, 42, 43), fixture.service.candidatesOrdered(fixture.subscription).stream()
                        .map(MediaSubscriptionResource::getId).toList(),
                "主/扩展均未配置:不限盘(兼容旧行为)");
    }

    @Test
    void primaryOwnershipRecheckForms() {
        // 主源归属复核:误挂异业主源列目录/流探测都正常,靠标题+年份门禁发现
        Fixture fixture = new Fixture();
        fixture.subscription.setName("悬案"); // 与线上同形:剧名被异剧标题子串包含,归属校验放行、靠年份门禁拦截
        fixture.subscription.setMetaProvider("douban");
        fixture.subscription.setMetaId("36624136");
        MetadataDetails details = new MetadataDetails();
        details.setYear("2026");
        Mockito.when(fixture.metadataService.details(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(details);
        MediaSubscriptionResource alien = new MediaSubscriptionResource();
        alien.setTitle("[英剧]悬案解码 第一季 Dept. Q Season 1 (2025) 4k中字");
        alien.setType(10);
        assertFalse(fixture.service.belongsToShow(fixture.subscription, alien), "年份全不符+子串嵌入:异剧");

        MediaSubscriptionResource right = new MediaSubscriptionResource();
        right.setTitle("悬案 (2026) 4K 高码率 [17集全]");
        assertTrue(fixture.service.belongsToShow(fixture.subscription, right), "年份相符:本剧");

        MediaSubscriptionResource noYear = new MediaSubscriptionResource();
        noYear.setTitle("悬案 4K 高码率 更17集");
        assertTrue(fixture.service.belongsToShow(fixture.subscription, noYear), "无年份:放行");

        MediaSubscriptionResource blank = new MediaSubscriptionResource();
        assertTrue(fixture.service.belongsToShow(fixture.subscription, blank), "无标题旧数据:保守放行");

        MediaSubscriptionResource alienNoMeta = new MediaSubscriptionResource();
        alienNoMeta.setTitle("[英剧]悬案解码 第一季 Dept. Q Season 1 (2025)");
        fixture.subscription.setMetaProvider(null); // 未绑元数据:门禁关闭
        assertTrue(fixture.service.belongsToShow(fixture.subscription, alienNoMeta), "未绑元数据无从判定:放行");
    }

    // ---------- 季号门禁(2026-08-24):订阅《末日地堡》第1季改第3季后点检查,候选/挂载仍是第一季资源 ----------
    // 标题明标「第一季」的资源同剧不同季,对本订阅就是"异剧":标题/年份门禁全放行(名字剥季缀后匹配、
    // 集号也不超官方总集数),只有季号门禁拦得住。裸标题(无季标记)不判 —— 内容是哪季无从得知。

    @Test
    void belongsToShowSeasonGateForms() {
        Fixture fixture = new Fixture();
        fixture.subscription.setName("末日地堡");
        fixture.subscription.setSeason(3);
        MediaSubscriptionResource s1 = new MediaSubscriptionResource();
        s1.setTitle("末日地堡第一季");
        assertFalse(fixture.service.belongsToShow(fixture.subscription, s1), "明标第1季:对第3季订阅是异季资源");

        MediaSubscriptionResource s2 = new MediaSubscriptionResource();
        s2.setTitle("末日地堡 第二季 4K");
        assertFalse(fixture.service.belongsToShow(fixture.subscription, s2), "明标第2季:拒");

        MediaSubscriptionResource s3 = new MediaSubscriptionResource();
        s3.setTitle("末日地堡 第三季 4K");
        assertTrue(fixture.service.belongsToShow(fixture.subscription, s3), "明标本季:放行");

        MediaSubscriptionResource bare = new MediaSubscriptionResource();
        bare.setTitle("末日地堡 (2023)");
        assertTrue(fixture.service.belongsToShow(fixture.subscription, bare), "裸标题无从判定:放行");

        MediaSubscriptionResource collection = new MediaSubscriptionResource();
        collection.setTitle("末日地堡 第1-2季合集");
        assertTrue(fixture.service.belongsToShow(fixture.subscription, collection), "跨季区间(解析不定季):放行");

        fixture.subscription.setSeason(1);
        assertTrue(fixture.service.belongsToShow(fixture.subscription, s1), "第1季订阅:第1季资源放行");
        assertFalse(fixture.service.belongsToShow(fixture.subscription, s3), "门禁双向:第1季订阅拒第3季资源");

        fixture.subscription.setSeason(null);
        assertTrue(fixture.service.belongsToShow(fixture.subscription, s2), "订阅未指定季:门禁关闭");
    }

    // ---------- 本剧季包放行(2026-08-31,线上:一念永恒,TMDB 单季装全剧/豆瓣分 4 季) ----------
    // 元数据 totalSeasons==1 且订阅季≤1 时,「第N季/完结季/合集」资源是本剧自己的季包:
    // 季包年份是该季年份(完结季 2026 vs 首播 2020)、季号≠订阅季,年份/季号门禁全是误杀,
    // 72 条「它季资源」+49 条「年份不符」在入池前就被扔掉,资源级起始集号根本没机会跑。

    private static MetadataDetails stubAbsoluteSeries(Fixture fixture, String name) {
        MetadataDetails details = new MetadataDetails();
        details.setTotalSeasons(1);
        details.setYear("2020");
        Mockito.when(fixture.metadataService.details(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(details);
        fixture.subscription.setMetaProvider("tmdb");
        fixture.subscription.setMetaId("107371");
        fixture.subscription.setName(name);
        fixture.subscription.setKeyword(name);
        fixture.subscription.setSeason(1);
        return details;
    }

    @Test
    void fillPoolAdmitsOwnSeasonPackTitles() {
        Fixture fixture = new Fixture();
        stubAbsoluteSeries(fixture, "一念永恒");
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/s1", "一念永恒 完结季(2026) 【更08集】【4K】"),
                        message("https://pan.quark.cn/s/s2", "一念永恒 第四季 4K [更新至08集]"),
                        message("https://pan.quark.cn/s/s3", "一念永恒 第1-4季 合集 2160P")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.anyInt(), Mockito.anyString()))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository, Mockito.times(3)).save(captor.capture());
        assertEquals(3, captor.getAllValues().size(), "完结季(2026 年份门禁)/第四季(季号门禁)/1-4季合集 全部入池");
    }

    @Test
    void fillPoolStillRejectsForeignSeasonWithoutAbsoluteMetadata() {
        // 元数据未知/分季:季号门禁照旧 —— 同名异剧的前季资源不能借「季包」名义混进来
        Fixture fixture = new Fixture();
        fixture.subscription.setName("悬案");
        fixture.subscription.setSeason(1);
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/s1", "悬案 第三季(2026) 全8集")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.anyInt(), Mockito.anyString()))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        Mockito.verify(fixture.resourceRepository, Mockito.never()).save(Mockito.any(MediaSubscriptionResource.class));
    }

    /** 腾讯分季表桩:一念永恒 S1=52/S2=54/S3=59/完结季=16(起点 166),与线上实测一致。 */
    private static void stubTencentSeasons(Fixture fixture) {
        fixture.service.setTencentSeasonAligner(new cn.har01d.alist_tvbox.service.metadata.TencentSeasonAligner(null) {
            @Override
            public com.fasterxml.jackson.databind.JsonNode search(String keyword) {
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            "{\"normalList\":{\"itemList\":[{\"doc\":{\"dataType\":2},\"videoInfo\":{\"title\":\"一念永恒 第1季\",\"year\":2020,"
                                    + "\"playSites\":[{\"totalEpisode\":52,\"episodeInfoList\":[{\"url\":\"https://v.qq.com/x/cover/a/e.html\"}]}]}},"
                                    + "{\"doc\":{\"dataType\":2},\"videoInfo\":{\"title\":\"一念永恒 第2季\",\"year\":2022,"
                                    + "\"playSites\":[{\"totalEpisode\":54,\"episodeInfoList\":[{\"url\":\"https://v.qq.com/x/cover/b/e.html\"}]}]}},"
                                    + "{\"doc\":{\"dataType\":2},\"videoInfo\":{\"title\":\"一念永恒 第3季\",\"year\":2024,"
                                    + "\"playSites\":[{\"totalEpisode\":59,\"episodeInfoList\":[{\"url\":\"https://v.qq.com/x/cover/c/e.html\"}]}]}},"
                                    + "{\"doc\":{\"dataType\":2},\"videoInfo\":{\"title\":\"一念永恒 完结季\",\"year\":2026,"
                                    + "\"playSites\":[{\"totalEpisode\":16,\"episodeInfoList\":[{\"url\":\"https://v.qq.com/x/cover/d/e.html\"}]}]}}]}}");
                } catch (Exception e) {
                    return null;
                }
            }
        });
    }

    @Test
    void candidatesOrderedKeepsFinalePacksForSeasonSplit() {
        // 线上(2026-08-31,订阅 66 一念永恒 第 4 季):9 条完结季(2026)候选全部入池后,
        // 激活侧 candidatesOrdered 的季包豁免只认 absolute 形态(分季订阅恒 false),季包被
        // 年份门禁(2026≠首播 2020,且汉字空格塌缩令整词救援失效)静默过滤 —— POOL_FILLED
        // 后紧跟「未找到可用资源」,全程零探测记录。激活侧豁免必须与入池同口径。
        Fixture fixture = new Fixture();
        stubAbsoluteSeries(fixture, "一念永恒");
        fixture.subscription.setSeason(4);
        fixture.subscription.setOfficialEpisodes(173);
        stubTencentSeasons(fixture);
        MediaSubscriptionResource finale = new MediaSubscriptionResource();
        finale.setId(71);
        finale.setSubscriptionId(1);
        finale.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        finale.setTitle("一念永恒 完结季 2026 4K 更新至08集");
        MediaSubscriptionResource declared = new MediaSubscriptionResource();
        declared.setId(72);
        declared.setSubscriptionId(1);
        declared.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        declared.setTitle("一念永恒 完结季 第四季(2026) 4K 第8集/国漫");
        MediaSubscriptionResource otherSeason = new MediaSubscriptionResource();
        otherSeason.setId(73);
        otherSeason.setSubscriptionId(1);
        otherSeason.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        otherSeason.setTitle("一念永恒 第三季 (2024) 更新EP59 4K 国漫");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(finale, declared, otherSeason));

        List<MediaSubscriptionResource> candidates = fixture.service.candidatesOrdered(fixture.subscription);

        assertEquals(Set.of(71, 72),
                candidates.stream().map(MediaSubscriptionResource::getId).collect(java.util.stream.Collectors.toSet()),
                "完结季季包(纯完结季标记/显式第四季)不受年份门禁误杀,第三季仍被排除");
    }

    @Test
    void fillPoolAdmitsFinalePackInternalNumberingForSeasonSplit() {
        // 线上同年同订阅:完结季包内 S01E01-E08 是季内编号(S01=包内第 1 集),旧逻辑
        // declared=1 短路完结归位 → 年份门禁误杀。剧级完结标记(完结季/最终季)下归位优先。
        Fixture fixture = new Fixture();
        stubAbsoluteSeries(fixture, "一念永恒");
        fixture.subscription.setSeason(4);
        fixture.subscription.setOfficialEpisodes(173);
        stubTencentSeasons(fixture);
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/s9", "一念永恒 完结季（2026） 4K 臻彩 S01E01 - E08 HIF")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.anyInt(), Mockito.anyString()))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        Mockito.verify(fixture.resourceRepository).save(Mockito.any(MediaSubscriptionResource.class));
    }

    @Test
    void effectiveTitleSeasonPrefersFinaleAlignmentOverInternalNumbering() {
        Fixture fixture = new Fixture();
        stubAbsoluteSeries(fixture, "一念永恒");
        fixture.subscription.setSeason(4);
        fixture.subscription.setOfficialEpisodes(173);
        stubTencentSeasons(fixture);

        assertEquals(4, fixture.service.effectiveTitleSeason(fixture.subscription,
                "一念永恒 完结季（2026） 4K 臻彩 S01E01 - E08 HIF"), "S01Exx=完结季包内季内编号,归位优先");
        assertEquals(4, fixture.service.effectiveTitleSeason(fixture.subscription, "一念永恒 完结季(2026) 【更08集】"),
                "无季号完结季标记:照旧走归位");
        assertEquals(2, fixture.service.effectiveTitleSeason(fixture.subscription, "剧名 第二季 完结篇"),
                "完结篇是弧级标记:声明季号不被归位覆盖");
        assertEquals(3, fixture.service.effectiveTitleSeason(fixture.subscription, "剧名 第三季 (2024)"),
                "非完结季标题照旧返回声明季号");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fillPoolRunsSiteSourcesThroughLinkCheck() {
        // 站点源(玩偶/盘链/观影/蜗牛/盘聚)是聚合站抓取,链接新鲜度未知 —— 聚合层单点统一过盘检:
        // telegram 聚合内部已过检不重复送检;bad/uncertain 剔除、ok/locked 盖 validityState 供入池消费
        Fixture fixture = new Fixture();
        fixture.subscription.setName("悬案");
        WanouSearchService wanou = Mockito.mock(WanouSearchService.class);
        PanLinkCheckService remote = Mockito.mock(PanLinkCheckService.class);
        AppProperties appProperties = new AppProperties();
        appProperties.setFormats(Set.of("mkv", "mp4"));
        appProperties.getSubscription().setPrimeCheckTimes(java.util.List.of());
        MediaSubscriptionCheckService service = new MediaSubscriptionCheckService(
                fixture.subscriptionRepository, fixture.resourceRepository, fixture.eventRepository,
                fixture.episodeRepository, fixture.episodeSourceRepository, fixture.deadLinkRepository,
                fixture.shareRepository, fixture.siteRepository,
                Mockito.mock(DriverAccountRepository.class), Mockito.mock(IndexTemplateRepository.class),
                fixture.settingRepository, fixture.shareService, fixture.aListService,
                fixture.telegramService, wanou, null, null, null, null,
                fixture.metadataService, Mockito.mock(AutoUpdateExecutor.class), fixture.historyRepository,
                appProperties, new ObjectMapper(), fixture.transferService, null);
        service.setPanLinkCheckService(remote);
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/tg1", "悬案 (2026) 4K [全8集]")));
        Mockito.when(wanou.search("悬案")).thenReturn(List.of(
                message("https://pan.quark.cn/s/w1", "悬案 (2026) 4K 更新至08集"),
                message("https://pan.baidu.com/s/w2?pwd=xx", "悬案 (2026) 1080P 全8集", "10")));
        Mockito.when(remote.filterInvalidPanSouLinks(Mockito.anyList())).thenAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(0);
            return messages.stream()
                    .filter(message -> !"10".equals(message.getType())) // 百度判 bad:剔除
                    .peek(message -> message.setValidityState("ok"))
                    .toList();
        });
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.anyInt(), Mockito.anyString()))
                .thenReturn(Optional.empty());

        service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<List<Message>> checked = ArgumentCaptor.forClass((Class<List<Message>>) (Class<?>) List.class);
        Mockito.verify(remote).filterInvalidPanSouLinks(checked.capture());
        assertEquals(2, checked.getValue().size(), "只送检站点源结果,telegram 已过检不重复送检");
        ArgumentCaptor<MediaSubscriptionResource> saved = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository, Mockito.times(2)).save(saved.capture());
        assertEquals(Set.of("https://pan.quark.cn/s/tg1", "https://pan.quark.cn/s/w1"),
                saved.getAllValues().stream().map(MediaSubscriptionResource::getLink).collect(java.util.stream.Collectors.toSet()),
                "百度 bad 不入池,telegram/玩偶存活结果各 1 条入池");
    }

    @Test
    void belongsToShowAndPurgeKeepOwnSeasonPacks() {
        Fixture fixture = new Fixture();
        stubAbsoluteSeries(fixture, "一念永恒");
        MediaSubscriptionResource finale = new MediaSubscriptionResource();
        finale.setId(31);
        finale.setTitle("一念永恒 完结季(2026) 【更08集】");
        MediaSubscriptionResource s4 = new MediaSubscriptionResource();
        s4.setId(32);
        s4.setTitle("一念永恒 第四季 4K");
        assertTrue(fixture.service.belongsToShow(fixture.subscription, finale), "完结季年份 2026≠2020:季包放行");
        assertTrue(fixture.service.belongsToShow(fixture.subscription, s4), "第四季≠订阅季 1:季包放行");

        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(finale, s4));
        fixture.service.purgeForeignSeasonResources(fixture.subscription);
        Mockito.verify(fixture.resourceRepository, Mockito.never()).delete(Mockito.any(MediaSubscriptionResource.class));
    }

    // ---------- 搜索定向(docs/msub-search-drive-targeting.md):订阅生效盘 + 磁力兜底开关 ----------

    @Test
    void fillPoolPassesSearchTargetsToSources() {
        Fixture fixture = new Fixture();
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES, "5")));
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_EXTENDED_DRIVES))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_EXTENDED_DRIVES, "8")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<cn.har01d.alist_tvbox.domain.SearchTargets> targets =
                ArgumentCaptor.forClass(cn.har01d.alist_tvbox.domain.SearchTargets.class);
        Mockito.verify(fixture.telegramService)
                .searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), targets.capture());
        assertEquals(Set.of("quark", "115"), targets.getValue().drives(), "定向集 = 主∪扩展(夸克/115)");
        assertFalse(targets.getValue().offlineIncluded(), "离线服务未注入(兜底不生效):magnet/ed2k 不并入");
    }

    @Test
    void fillPoolIncludesOfflineTypesWhenMagnetFallbackEnabled() {
        Fixture fixture = new Fixture();
        fixture.subscription.setMode(MediaSubscription.MODE_TRANSFER);
        fixture.subscription.setMagnetOffline(true);
        OfflineDownloadService offline = Mockito.mock(OfflineDownloadService.class);
        Mockito.when(offline.isConfigured()).thenReturn(true);
        fixture.service.setOfflineDownloadService(offline);
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES, "5")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<cn.har01d.alist_tvbox.domain.SearchTargets> targets =
                ArgumentCaptor.forClass(cn.har01d.alist_tvbox.domain.SearchTargets.class);
        Mockito.verify(fixture.telegramService)
                .searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), targets.capture());
        assertTrue(targets.getValue().offlineIncluded(), "磁力兜底生效(开关+TRANSFER+离线已配置):并入 magnet/ed2k");
        assertEquals(Set.of("quark"), targets.getValue().drives());
    }

    @Test
    void fillPoolHarvestsSiteSourceMagnetsIntoFallbackPool() {
        // 站点源(观影 downlist / 盘聚 seed)产出的 magnet/ed2k:兜底生效时经定向集闸门,
        // fillPool 的 NON_PAN 收割进磁力候选池(供 submitMagnetForEpisode 优先消费),不入池
        Fixture fixture = new Fixture();
        fixture.subscription.setName("难哄");
        fixture.subscription.setMode(MediaSubscription.MODE_TRANSFER);
        fixture.subscription.setMagnetOffline(true);
        OfflineDownloadService offline = Mockito.mock(OfflineDownloadService.class);
        Mockito.when(offline.isConfigured()).thenReturn(true);
        fixture.service.setOfflineDownloadService(offline);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.guanYingSearchService.search(Mockito.anyString())).thenReturn(List.of(
                message("magnet:?xt=urn:btih:abc123&dn=%E9%9A%BE%E5%93%8404", "难哄 04集 1080P", "magnet")));
        Mockito.when(fixture.panjuSearchService.search(Mockito.anyString(), Mockito.anyBoolean())).thenReturn(List.of(
                message("ed2k://|file|难哄.EP05.mp4|123456|hash|/", "难哄 第05集", "ed2k")));

        fixture.service.fillPool(fixture.subscription, true, null);

        List<Message> pool = fixture.service.magnetCandidatesOf(1);
        assertEquals(2, pool.size(), "两源磁力/ed2k 都收割进兜底候选池");
        assertTrue(pool.stream().anyMatch(m -> m.getLink().startsWith("magnet:?")));
        assertTrue(pool.stream().anyMatch(m -> m.getLink().startsWith("ed2k://")));
        Mockito.verify(fixture.resourceRepository, Mockito.never()).save(Mockito.any(MediaSubscriptionResource.class));
    }

    @Test
    void fillPoolDropsSiteSourceMagnetsWhenFallbackDisabled() {
        // 兜底未开(开关关/离线未配置):magnet 在站点源闸门(盘检送检之前)即剔除,不进候选池
        Fixture fixture = new Fixture();
        fixture.subscription.setName("难哄");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.guanYingSearchService.search(Mockito.anyString())).thenReturn(List.of(
                message("magnet:?xt=urn:btih:abc123", "难哄", "magnet")));

        fixture.service.fillPool(fixture.subscription, true, null);

        assertTrue(fixture.service.magnetCandidatesOf(1).isEmpty(), "兜底未开:闸门剔除,零收割");
    }

    @Test
    @SuppressWarnings("unchecked")
    void siteSourcesOffWhitelistDroppedBeforeLinkCheck() {
        // 定向集的站点源闸门:白名单以外的盘在盘检送检之前剔除 —— 域外盘不烧盘检配额,
        // 也不会借盘检结果混进候选(fillPool 的 OFF_POOL 只是纵深防御)
        Fixture fixture = new Fixture();
        fixture.subscription.setName("悬案");
        Mockito.when(fixture.settingRepository.findById(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES))
                .thenReturn(Optional.of(setting(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES, "5"))); // 主盘只认夸克
        WanouSearchService wanou = Mockito.mock(WanouSearchService.class);
        PanLinkCheckService remote = Mockito.mock(PanLinkCheckService.class);
        AppProperties appProperties = new AppProperties();
        appProperties.setFormats(Set.of("mkv", "mp4"));
        appProperties.getSubscription().setPrimeCheckTimes(java.util.List.of());
        MediaSubscriptionCheckService service = new MediaSubscriptionCheckService(
                fixture.subscriptionRepository, fixture.resourceRepository, fixture.eventRepository,
                fixture.episodeRepository, fixture.episodeSourceRepository, fixture.deadLinkRepository,
                fixture.shareRepository, fixture.siteRepository,
                Mockito.mock(DriverAccountRepository.class), Mockito.mock(IndexTemplateRepository.class),
                fixture.settingRepository, fixture.shareService, fixture.aListService,
                fixture.telegramService, wanou, null, null, null, null,
                fixture.metadataService, Mockito.mock(AutoUpdateExecutor.class), fixture.historyRepository,
                appProperties, new ObjectMapper(), fixture.transferService, null);
        service.setPanLinkCheckService(remote);
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/tg1", "悬案 (2026) 4K [全8集]")));
        Mockito.when(wanou.search("悬案")).thenReturn(List.of(
                message("https://pan.quark.cn/s/w1", "悬案 (2026) 4K 更新至08集"),
                message("https://pan.baidu.com/s/w2?pwd=xx", "悬案 (2026) 1080P 全8集", "10")));
        Mockito.when(remote.filterInvalidPanSouLinks(Mockito.anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.anyInt(), Mockito.anyString()))
                .thenReturn(Optional.empty());

        service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<List<Message>> checked = ArgumentCaptor.forClass((Class<List<Message>>) (Class<?>) List.class);
        Mockito.verify(remote).filterInvalidPanSouLinks(checked.capture());
        assertEquals(List.of("https://pan.quark.cn/s/w1"),
                checked.getValue().stream().map(Message::getLink).toList(),
                "百度不在定向集:盘检送检前已剔除,只送夸克");
        ArgumentCaptor<MediaSubscriptionResource> saved = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository, Mockito.times(2)).save(saved.capture());
        assertEquals(Set.of("https://pan.quark.cn/s/tg1", "https://pan.quark.cn/s/w1"),
                saved.getAllValues().stream().map(MediaSubscriptionResource::getLink).collect(java.util.stream.Collectors.toSet()),
                "telegram/玩偶的夸克结果各 1 条入池,百度不占席位");
    }

    @Test
    void seasonPackMapForms() {
        // SINGLE:完结季包(包季 4,起点 153)—— 裸编号/S01Eyy(季内编号习惯)都按包季平移,别季拒收
        MediaSubscriptionCheckService.SeasonPackMap single =
                new MediaSubscriptionCheckService.SeasonPackMap(java.util.Map.of(4, 153), 4, false);
        assertEquals(153, single.map("第01集 4K.mkv", "", 1));
        assertEquals(160, single.map("S01E08.mkv", "", 8), "S01Eyy=季内编号,不是第 1 季");
        assertEquals(-1, single.map("S02E01.mkv", "", 1), "别季文件:SINGLE 包只供声明的季");

        // MULTI:1-4 季合集 —— SxxEyy 按各自季起点,裸编号按目录季,无目录季按最高季
        MediaSubscriptionCheckService.SeasonPackMap multi = new MediaSubscriptionCheckService.SeasonPackMap(
                java.util.Map.of(1, 1, 2, 53, 3, 105, 4, 153), null, true);
        assertEquals(1, multi.map("S01E01.mkv", "", 1));
        assertEquals(53, multi.map("S02E01.mkv", "", 1));
        assertEquals(165, multi.map("S03E61.mkv", "", 61));
        assertEquals(153, multi.map("S04E01.mkv", "", 1));
        assertEquals(53, multi.map("第01集.mkv", "第二季", 1), "裸编号在「第二季」目录:按目录季");
        assertEquals(161, multi.map("第09集.mkv", "", 9), "裸编号无目录季:兜底最高季(零散更新=最新季,153-1+9)");
        assertEquals(-1, multi.map("S05E01.mkv", "", 1), "起点表外的季拒收");

        // 持久化编解码回环
        MediaSubscriptionCheckService.SeasonPackMap parsed =
                MediaSubscriptionCheckService.SeasonPackMap.parse(multi.encode(), null, true);
        assertEquals(107, parsed.map("S03E03.mkv", "", 3), "S03E03 = 105-1+3");
        assertNull(MediaSubscriptionCheckService.SeasonPackMap.parse("junk", null, true));
    }

    @Test
    void seasonPackMapPersistsAndCaches() {
        Fixture fixture = new Fixture();
        stubAbsoluteSeries(fixture, "一念永恒");
        fixture.service.setSeasonAligner(new cn.har01d.alist_tvbox.service.metadata.DoubanSeasonAligner(null) {
            @Override
            public List<cn.har01d.alist_tvbox.service.metadata.DoubanSeasonAligner.DoubanCandidate> suggest(String keyword) {
                return List.of(
                        new cn.har01d.alist_tvbox.service.metadata.DoubanSeasonAligner.DoubanCandidate("1", "一念永恒", "", "2020"),
                        new cn.har01d.alist_tvbox.service.metadata.DoubanSeasonAligner.DoubanCandidate("2", "一念永恒 第二季", "", "2021"),
                        new cn.har01d.alist_tvbox.service.metadata.DoubanSeasonAligner.DoubanCandidate("3", "一念永恒 第三季", "", "2023"));
            }

            @Override
            public Optional<Integer> fetchEpisodeCount(String doubanId) {
                return switch (doubanId) {
                    case "1" -> Optional.of(52);
                    case "2" -> Optional.of(52);
                    case "3" -> Optional.of(48);
                    default -> Optional.empty();
                };
            }
        });
        MediaSubscriptionResource finale = new MediaSubscriptionResource();
        finale.setId(41);
        finale.setSubscriptionId(1);
        finale.setTitle("一念永恒 完结季 4K臻彩MAX [更新至08集]");
        fixture.subscription.setOfficialEpisodes(173);

        MediaSubscriptionCheckService.SeasonPackMap map = fixture.service.seasonPackMap(fixture.subscription, finale);

        assertNotNull(map, "已播 173 > 已登记 152:完结季目标 = S4,起点 153");
        assertEquals(153, map.map("S01E01.mkv", "", 1));
        assertEquals("4:153", finale.getSeasonStarts(), "映射表持久化到资源行");
        Mockito.verify(fixture.episodeSourceRepository).deleteByResourceId(41);

        // 二次取用走持久化表,不再依赖豆瓣
        MediaSubscriptionCheckService.SeasonPackMap again = fixture.service.seasonPackMap(fixture.subscription, finale);
        assertEquals(160, again.map("第08集.mkv", "", 8));

        // 手动声明优先:清自动映射
        finale.setStartEpisode(166);
        assertNull(fixture.service.seasonPackMap(fixture.subscription, finale));
    }

    @Test
    void seasonPackMapPrefersTencentOverDouban() {
        // 腾讯分季集数与绝对集号严格对齐(线上:一念永恒完结季起点 166),豆瓣累推 153 有漏登 —— 首选腾讯
        Fixture fixture = new Fixture();
        stubAbsoluteSeries(fixture, "一念永恒");
        fixture.service.setTencentSeasonAligner(new cn.har01d.alist_tvbox.service.metadata.TencentSeasonAligner(null) {
            @Override
            public com.fasterxml.jackson.databind.JsonNode search(String keyword) {
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            "{\"normalList\":{\"itemList\":[{\"doc\":{\"dataType\":2},\"videoInfo\":{\"title\":\"一念永恒 第1季\",\"year\":2020,"
                                    + "\"playSites\":[{\"totalEpisode\":52,\"episodeInfoList\":[{\"url\":\"https://v.qq.com/x/cover/a/e.html\"}]}]}},"
                                    + "{\"doc\":{\"dataType\":2},\"videoInfo\":{\"title\":\"一念永恒 第2季\",\"year\":2022,"
                                    + "\"playSites\":[{\"totalEpisode\":54,\"episodeInfoList\":[{\"url\":\"https://v.qq.com/x/cover/b/e.html\"}]}]}},"
                                    + "{\"doc\":{\"dataType\":2},\"videoInfo\":{\"title\":\"一念永恒 第3季\",\"year\":2024,"
                                    + "\"playSites\":[{\"totalEpisode\":59,\"episodeInfoList\":[{\"url\":\"https://v.qq.com/x/cover/c/e.html\"}]}]}},"
                                    + "{\"doc\":{\"dataType\":2},\"videoInfo\":{\"title\":\"一念永恒 完结季\",\"year\":2026,"
                                    + "\"playSites\":[{\"totalEpisode\":16,\"episodeInfoList\":[{\"url\":\"https://v.qq.com/x/cover/d/e.html\"}]}]}}]}}");
                } catch (Exception e) {
                    return null;
                }
            }
        });
        fixture.service.setSeasonAligner(new cn.har01d.alist_tvbox.service.metadata.DoubanSeasonAligner(null) {
            @Override
            public List<cn.har01d.alist_tvbox.service.metadata.DoubanSeasonAligner.DoubanCandidate> suggest(String keyword) {
                return List.of(); // 豆瓣即使有数据也不该被用到
            }
        });
        MediaSubscriptionResource finale = new MediaSubscriptionResource();
        finale.setId(61);
        finale.setSubscriptionId(1);
        finale.setTitle("一念永恒 完结季 4K [更新至08集]");
        fixture.subscription.setOfficialEpisodes(173);

        MediaSubscriptionCheckService.SeasonPackMap map = fixture.service.seasonPackMap(fixture.subscription, finale);

        assertEquals("4:166", finale.getSeasonStarts(), "腾讯口径:完结季起点 166(豆瓣口径是 153)");
        assertEquals(166, map.map("S01E01.mkv", "", 1));
        assertEquals(173, map.map("第08集.mkv", "", 8));
    }

    @Test
    void fillGapsProbesDespiteFullMountSlotsAndEvictsWeakest() {
        // 线上形态(id=64):6 个补缺挂载各有独占集顶满 maxGapMounts=6,旧逻辑槽满即 break,
        // 连探测都不做。现在:照常探测(LISTED 行可供播),有用候选挂载时挤掉独占覆盖最小的弱挂载
        Fixture fixture = new Fixture();
        List<MediaSubscriptionResource> resources = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            MediaSubscriptionResource aux = new MediaSubscriptionResource();
            aux.setId(300 + i);
            aux.setSubscriptionId(1);
            aux.setTitle("补缺挂载" + i);
            aux.setState(MediaSubscriptionResource.STATE_MOUNTED);
            aux.setMountPath("/追剧/.sources/1-测试剧-补" + i);
            aux.setShareId(900 + i);
            resources.add(aux);
        }
        MediaSubscriptionResource s3 = new MediaSubscriptionResource();
        s3.setId(90);
        s3.setSubscriptionId(1);
        s3.setTitle("一念永恒 第三季");
        s3.setType(5); // 夸克:无盘类型的资源 driveThrottledThisRound 一律跳过(真实资源必有类型)
        s3.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        s3.setScore(25);
        resources.add(s3);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(resources);
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(Mockito.anyInt(), Mockito.anyCollection()))
                .thenReturn(List.of());
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(Mockito.eq(90), Mockito.anyCollection()))
                .thenReturn(numbers(107, 165));
        Mockito.when(fixture.subscriptionRepository.existsByShareIdAndIdNot(Mockito.anyInt(), Mockito.anyInt())).thenReturn(false);
        Mockito.when(fixture.resourceRepository.existsByShareIdAndSubscriptionIdNot(Mockito.anyInt(), Mockito.anyInt())).thenReturn(false);
        Mockito.when(fixture.shareRepository.existsByPath(Mockito.anyString())).thenReturn(false);
        Share mount = new Share();
        mount.setId(66);
        Mockito.when(fixture.shareRepository.findByPath(Mockito.anyString())).thenReturn(mount);
        MediaSubscriptionCheckService spy = Mockito.spy(fixture.service);
        Mockito.doReturn(MediaSubscriptionCheckService.ProbeOutcome.PROBED)
                .when(spy).probeCandidateSafely(Mockito.any(), Mockito.any());

        spy.fillGaps(fixture.subscription, new java.util.TreeSet<>(numbers(107, 165)));

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, s3.getState(), "第三季候选照常探测并挂载(挤掉独占覆盖 0 的弱挂载)");
        Mockito.verify(fixture.shareService, Mockito.atLeastOnce()).deleteShare(Mockito.intThat(id -> id >= 900 && id < 906));
        ArgumentCaptor<MediaSubscriptionEvent> events = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository, Mockito.atLeastOnce()).save(events.capture());
        assertTrue(events.getAllValues().stream().anyMatch(e ->
                        MediaSubscriptionEvent.TYPE_GAP_FILLED.equals(e.getType()) && e.getDetail().contains("107")),
                "LISTED 行已补上缺口:GAP_FILLED 记录 107 起");
    }

    // ---------- 缺集主源的完整性升级(线上反馈:金色 2026,缺集的当主源、不缺的停在候补上不去) ----------

    @Test
    void fillGapsUpgradesIncompletePrimaryToCompleteCandidate() {
        // 主源活着但缺第 13 集:分数更高的完整候选(1-13 独力覆盖主源 1-12 ∪ 缺口)探测通过后
        // 直接转正当主源,不再只挂补缺 —— 旧主源回候选池,缺口全消不再触发补搜
        Fixture fixture = new Fixture();
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(400);
        primary.setSubscriptionId(1);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        primary.setShareId(5);
        primary.setScore(93);
        MediaSubscriptionResource complete = new MediaSubscriptionResource();
        complete.setId(401);
        complete.setSubscriptionId(1);
        complete.setLink("https://pan.baidu.com/s/jinse");
        complete.setTitle("测试剧 (2026)【13集全】【4K HDR】完结");
        complete.setType(10);
        complete.setScore(110);
        complete.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(complete, primary));
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(Mockito.eq(400), Mockito.anyCollection()))
                .thenReturn(numbers(1, 12));
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(Mockito.eq(401), Mockito.anyCollection()))
                .thenReturn(numbers(1, 13));
        Mockito.when(fixture.episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(numbers(1, 12));
        MediaSubscriptionCheckService spy = Mockito.spy(fixture.service);
        Mockito.doReturn(MediaSubscriptionCheckService.ProbeOutcome.PROBED)
                .when(spy).probeCandidateSafely(Mockito.any(), Mockito.any());
        Mockito.doAnswer(invocation -> {
            MediaSubscription sub = invocation.getArgument(0);
            MediaSubscriptionResource upgraded = invocation.getArgument(1);
            upgraded.setState(MediaSubscriptionResource.STATE_MOUNTED);
            upgraded.setMountPath(sub.getMountPath());
            primary.setState(MediaSubscriptionResource.STATE_CANDIDATE);
            primary.setMountPath(null);
            primary.setShareId(null);
            return null;
        }).when(spy).activate(Mockito.any(), Mockito.any());

        spy.fillGaps(fixture.subscription, new java.util.TreeSet<>(Set.of(13)));

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, complete.getState(), "完整候选直接转正当主源");
        assertEquals("/追剧/1-测试剧", complete.getMountPath(), "转正 = 挂到订阅固定路径");
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, primary.getState(), "旧主源回候选池兜底");
        Mockito.verify(spy).activate(fixture.subscription, complete);
        Mockito.verify(spy, Mockito.never()).fillPool(Mockito.any(), Mockito.anyBoolean(), Mockito.any());
        ArgumentCaptor<MediaSubscriptionEvent> events = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository, Mockito.atLeastOnce()).save(events.capture());
        assertTrue(events.getAllValues().stream().anyMatch(e ->
                        MediaSubscriptionEvent.TYPE_SOURCE_REPLACED.equals(e.getType()) && e.getDetail().contains("更完整")),
                "换源事件说明这是完整性升级而非失效换源");
    }

    @Test
    void fillGapsKeepsPrimaryWhenCompleteCandidateScoredLower() {
        // 分数编码画质/盘偏好:完整但分数更低的候选(90 < 93)不越权转正,仍走补缺挂载
        Fixture fixture = new Fixture();
        MediaSubscriptionResource primary = gapUpgradePrimary(null);
        MediaSubscriptionResource candidate = gapUpgradeCandidate(90);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(primary, candidate));
        stubGapUpgradeCoverages(fixture, numbers(1, 13));
        MediaSubscriptionCheckService spy = spyForGapUpgrade(fixture);
        spy.fillGaps(fixture.subscription, new java.util.TreeSet<>(Set.of(13)));

        Mockito.verify(spy, Mockito.never()).activate(Mockito.any(), Mockito.any());
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, candidate.getState(), "低分完整源挂补缺");
        assertEquals("/追剧/.sources/1-测试剧-补1", candidate.getMountPath(), "补缺挂载走 .sources 内部目录");
        assertEquals("/追剧/1-测试剧", primary.getMountPath(), "主源不动");
    }

    @Test
    void fillGapsKeepsPinnedPrimaryEvenForCompleteCandidate() {
        // 钉选主源是用户锁定:更优完整候选也不自动换,仍走补缺
        Fixture fixture = new Fixture();
        MediaSubscriptionResource primary = gapUpgradePrimary(true);
        MediaSubscriptionResource candidate = gapUpgradeCandidate(110);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(candidate, primary));
        stubGapUpgradeCoverages(fixture, numbers(1, 13));
        MediaSubscriptionCheckService spy = spyForGapUpgrade(fixture);
        spy.fillGaps(fixture.subscription, new java.util.TreeSet<>(Set.of(13)));

        Mockito.verify(spy, Mockito.never()).activate(Mockito.any(), Mockito.any());
        assertEquals("/追剧/.sources/1-测试剧-补1", candidate.getMountPath(), "钉选压过自动判定,完整源挂补缺");
        assertEquals("/追剧/1-测试剧", primary.getMountPath(), "钉选主源不动");
    }

    @Test
    void fillGapsKeepsPrimaryWhenCandidateNotSuperset() {
        // 候选只覆盖缺口附近(12-13)不含主源全集:转正会让第 1-11 集失去供流,只配补缺
        Fixture fixture = new Fixture();
        MediaSubscriptionResource primary = gapUpgradePrimary(null);
        MediaSubscriptionResource candidate = gapUpgradeCandidate(110);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(candidate, primary));
        stubGapUpgradeCoverages(fixture, List.of(12, 13));
        MediaSubscriptionCheckService spy = spyForGapUpgrade(fixture);
        spy.fillGaps(fixture.subscription, new java.util.TreeSet<>(Set.of(13)));

        Mockito.verify(spy, Mockito.never()).activate(Mockito.any(), Mockito.any());
        assertEquals("/追剧/.sources/1-测试剧-补1", candidate.getMountPath(), "非完整超集只做补缺挂载");
        assertEquals("/追剧/1-测试剧", primary.getMountPath(), "主源不动");
    }

    private MediaSubscriptionResource gapUpgradePrimary(Boolean pinned) {
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(400);
        primary.setSubscriptionId(1);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        primary.setShareId(5);
        primary.setScore(93);
        primary.setPinned(pinned);
        return primary;
    }

    private MediaSubscriptionResource gapUpgradeCandidate(int score) {
        MediaSubscriptionResource candidate = new MediaSubscriptionResource();
        candidate.setId(401);
        candidate.setSubscriptionId(1);
        candidate.setLink("https://pan.baidu.com/s/jinse");
        candidate.setTitle("测试剧 (2026)【13集全】【4K HDR】完结");
        candidate.setType(10);
        candidate.setScore(score);
        candidate.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        return candidate;
    }

    private void stubGapUpgradeCoverages(Fixture fixture, List<Integer> candidateCoverage) {
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(Mockito.eq(400), Mockito.anyCollection()))
                .thenReturn(numbers(1, 12));
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(Mockito.eq(401), Mockito.anyCollection()))
                .thenReturn(candidateCoverage);
        Mockito.when(fixture.episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(numbers(1, 12));
    }

    private MediaSubscriptionCheckService spyForGapUpgrade(Fixture fixture) {
        Share auxMount = new Share();
        auxMount.setId(66);
        Mockito.when(fixture.shareRepository.findByPath("/追剧/.sources/1-测试剧-补1")).thenReturn(auxMount);
        MediaSubscriptionCheckService spy = Mockito.spy(fixture.service);
        Mockito.doReturn(MediaSubscriptionCheckService.ProbeOutcome.PROBED)
                .when(spy).probeCandidateSafely(Mockito.any(), Mockito.any());
        return spy;
    }

    // ---------- 手动启用候选:挂为补缺源,不动主源(回应"点启用就变成主源") ----------

    @Test
    void mountCandidateMountsAuxWithoutTouchingPrimary() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(400);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        primary.setShareId(5);
        MediaSubscriptionResource candidate = new MediaSubscriptionResource();
        candidate.setId(401);
        candidate.setSubscriptionId(1);
        candidate.setLink("https://pan.quark.cn/s/abc");
        candidate.setTitle("测试剧 4K [12集全]");
        candidate.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(primary, candidate));
        Mockito.when(fixture.shareRepository.existsByPath(Mockito.anyString())).thenReturn(false);
        Share auxMount = new Share();
        auxMount.setId(66);
        Mockito.when(fixture.shareRepository.findByPath("/追剧/.sources/1-测试剧-补1")).thenReturn(auxMount);
        MediaSubscriptionCheckService spy = Mockito.spy(fixture.service);
        Mockito.doReturn(MediaSubscriptionCheckService.ProbeOutcome.PROBED)
                .when(spy).probeCandidateSafely(Mockito.any(), Mockito.any());

        spy.mountCandidate(fixture.subscription, candidate);

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, candidate.getState(), "启用=挂为补缺源(MOUNTED)");
        assertEquals("/追剧/.sources/1-测试剧-补1", candidate.getMountPath(), "挂到内部补缺目录,不占主源路径");
        assertEquals(66, candidate.getShareId().intValue());
        assertEquals("/追剧/1-测试剧", fixture.subscription.getMountPath(), "订阅主路径不动");
        assertEquals(5, fixture.subscription.getShareId().intValue(), "订阅主 share 不动(activate 会顶替,这里必须原样)");
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, primary.getState());
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(Mockito.anyInt()); // 不删旧主挂载
        ArgumentCaptor<MediaSubscriptionEvent> events = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository).save(events.capture());
        assertTrue(events.getAllValues().stream().anyMatch(e ->
                        MediaSubscriptionEvent.TYPE_GAP_FILLED.equals(e.getType()) && e.getDetail().contains("主源未动")),
                "挂载成功记补缺事件并明确主源未动");
    }

    @Test
    void mountCandidateSkipsMountWhenProbeFails() {
        // 探测失败(失效/异剧/限流)已由 probeCandidateSafely 按分级处置:启用流程不接管挂载
        Fixture fixture = new Fixture();
        MediaSubscriptionResource candidate = new MediaSubscriptionResource();
        candidate.setId(401);
        candidate.setSubscriptionId(1);
        candidate.setLink("https://pan.quark.cn/s/dead");
        candidate.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        MediaSubscriptionCheckService spy = Mockito.spy(fixture.service);
        Mockito.doReturn(MediaSubscriptionCheckService.ProbeOutcome.DEAD)
                .when(spy).probeCandidateSafely(Mockito.any(), Mockito.any());

        spy.mountCandidate(fixture.subscription, candidate);

        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, candidate.getState(), "探测失败不再挂载");
        Mockito.verify(fixture.shareService, Mockito.never()).add(Mockito.any());
        Mockito.verifyNoInteractions(fixture.eventRepository);
    }

    @Test
    void mountCandidateKeepsCandidateWhenMountFails() {
        // 探测已证明链接活着,挂载失败(AList 侧)不退役不拉黑:留候选池下轮补缺重探
        Fixture fixture = new Fixture();
        MediaSubscriptionResource candidate = new MediaSubscriptionResource();
        candidate.setId(401);
        candidate.setSubscriptionId(1);
        candidate.setLink("https://pan.quark.cn/s/alive");
        candidate.setTitle("测试剧 4K");
        candidate.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.shareRepository.existsByPath(Mockito.anyString())).thenReturn(false);
        Mockito.when(fixture.shareRepository.findByPath(Mockito.anyString())).thenReturn(null); // 挂载失败
        MediaSubscriptionCheckService spy = Mockito.spy(fixture.service);
        Mockito.doReturn(MediaSubscriptionCheckService.ProbeOutcome.PROBED)
                .when(spy).probeCandidateSafely(Mockito.any(), Mockito.any());

        spy.mountCandidate(fixture.subscription, candidate);

        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, candidate.getState(), "挂载失败不退役");
        Mockito.verify(fixture.deadLinkRepository, Mockito.never()).save(Mockito.any());
        ArgumentCaptor<MediaSubscriptionEvent> events = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository).save(events.capture());
        assertTrue(events.getAllValues().stream().anyMatch(e ->
                        MediaSubscriptionEvent.TYPE_ERROR.equals(e.getType()) && e.getDetail().contains("启用挂载失败")),
                "挂载失败记 ERROR 事件给用户回执");
    }

    @Test
    void mountAsyncRejectsForeignOwnerOrResource() {
        // 归属校验(同步抛,不进线程池):订阅/资源不是本人的拒绝
        Fixture fixture = new Fixture();
        MediaSubscriptionResource candidate = new MediaSubscriptionResource();
        candidate.setId(401);
        candidate.setSubscriptionId(2); // 不属于订阅 1
        Mockito.when(fixture.resourceRepository.findById(401)).thenReturn(Optional.of(candidate));

        assertThrows(cn.har01d.alist_tvbox.exception.BadRequestException.class,
                () -> fixture.service.mountAsync(1, 1, 401), "资源不属于该订阅:拒");
    }

    @Test
    void evictWeakestAuxMountRefusesNetLoss() {
        // 候选可用覆盖(1 集)≤ 被挤者独占覆盖(5 集):挤了净亏,不挤 —— 候选退化行级供流
        Fixture fixture = new Fixture();
        fixture.subscription.setMountPath("/追剧/1-测试剧");
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(400);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        MediaSubscriptionResource aux = new MediaSubscriptionResource();
        aux.setId(401);
        aux.setState(MediaSubscriptionResource.STATE_MOUNTED);
        aux.setMountPath("/追剧/.sources/1-测试剧-补1");
        aux.setShareId(901);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(primary, aux));
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(Mockito.eq(400), Mockito.anyCollection()))
                .thenReturn(List.of());
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(Mockito.eq(401), Mockito.anyCollection()))
                .thenReturn(numbers(1, 5));

        assertNull(fixture.service.evictWeakestAuxMount(fixture.subscription, new java.util.TreeSet<>(List.of(107))));

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, aux.getState());
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(Mockito.anyInt());
    }

    @Test
    void gapProbesPreferCandidatesCoveringMissingRange() {
        // 线上形态:缺 107-165(第三季),池里高分完结季包(166 起)压着低分第三季包 —— 探测
        // 预算每轮 3 个,按分数序永远轮不到能补缺的第三季;区间可推断且不沾缺口的直接跳过
        Fixture fixture = new Fixture();
        stubAbsoluteSeries(fixture, "一念永恒");
        fixture.service.setTencentSeasonAligner(new cn.har01d.alist_tvbox.service.metadata.TencentSeasonAligner(null) {
            @Override
            public com.fasterxml.jackson.databind.JsonNode search(String keyword) {
                return null; // seasonStarts 走不了网络,直接覆写起点表所在的调用链以下
            }

            @Override
            public java.util.Map<Integer, Integer> seasonStarts(String seriesName, Integer firstYear) {
                return java.util.Map.of(1, 1, 2, 53, 3, 107, 4, 166);
            }

            @Override
            public Integer finaleSeason(String seriesName, Integer firstYear, Integer officialAired) {
                return 4;
            }
        });
        MediaSubscriptionResource finale = new MediaSubscriptionResource();
        finale.setId(71);
        finale.setSubscriptionId(1);
        finale.setTitle("一念永恒 完结季(2026) 【更08集】");
        finale.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        finale.setScore(100);
        MediaSubscriptionResource s3 = new MediaSubscriptionResource();
        s3.setId(72);
        s3.setSubscriptionId(1);
        s3.setTitle("一念永恒 第三季");
        s3.setType(5); // 夸克:无盘类型的资源 driveThrottledThisRound 一律跳过(真实资源必有类型)
        s3.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        s3.setScore(25);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(finale, s3));

        Set<Integer> missing = new java.util.TreeSet<>(numbers(107, 165));
        assertEquals(Boolean.FALSE, fixture.service.likelyCoversMissing(fixture.subscription, finale, missing),
                "完结季包 166 起:区间可推断且与缺口 107-165 无交集");
        assertEquals(Boolean.TRUE, fixture.service.likelyCoversMissing(fixture.subscription, s3, missing),
                "第三季包 107 起:正中缺口");

        List<MediaSubscriptionResource> ordered = fixture.service.orderForGapProbes(fixture.subscription, missing);
        assertEquals(72, ordered.get(0).getId(), "低分第三季包压过高分完结季包:补缺优先能补缺的");
        assertEquals(71, ordered.get(1).getId());

        // 「第一季」标题(declared==订阅季,不是 widened 形态)区间起点 1 可推断 → 也排前;
        // 豆瓣表缺 S4 行时完结季起点走 inferSeasonStart 兜底,照样能判 FALSE 跳过省预算
        MediaSubscriptionResource s1 = new MediaSubscriptionResource();
        s1.setId(73);
        s1.setSubscriptionId(1);
        s1.setTitle("一念永恒 第一季 4K");
        s1.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        s1.setScore(10);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(finale, s3, s1));
        assertEquals(Boolean.FALSE, fixture.service.likelyCoversMissing(fixture.subscription, s1, missing),
                "缺 107-165 时第 1 季包(区间 1-52)不沾缺口:跳过省预算");
        Set<Integer> lowMissing = new java.util.TreeSet<>(numbers(1, 165));
        assertEquals(Boolean.TRUE, fixture.service.likelyCoversMissing(fixture.subscription, s1, lowMissing));
        assertEquals(Boolean.FALSE, fixture.service.likelyCoversMissing(fixture.subscription, finale, lowMissing),
                "完结季包 166 起:跳过不烧预算");

        // 分季表缺 S4 行(豆瓣完结季常无条目):inferSeasonStart 兜底推 166 → FALSE
        fixture.service.setTencentSeasonAligner(null);
        fixture.service.setSeasonAligner(new cn.har01d.alist_tvbox.service.metadata.DoubanSeasonAligner(null) {
            @Override
            public java.util.Map<Integer, Integer> seasonStarts(String seriesName, Integer firstYear) {
                return java.util.Map.of(1, 1, 2, 53, 3, 107); // 无 S4 行
            }

            @Override
            public Integer inferSeasonStart(String seriesName, Integer firstYear, String resourceTitle, Integer officialAired) {
                return resourceTitle != null && resourceTitle.contains("完结季") ? 166 : null;
            }
        });
        assertEquals(Boolean.FALSE, fixture.service.likelyCoversMissing(fixture.subscription, finale, lowMissing),
                "表缺 S4 行:inferSeasonStart 推出 166,完结季包照样判 FALSE");
    }

    @Test
    void tencentNumbersSkipEndedSeries() {
        // 完结剧不被腾讯登记口径抬总数(2026-09-01 线上 sub45:百花杀 36 集完结,腾讯三个重复
        // 条目登记 75/54/21 取 max 得 75,ENDED 剧被抬成「缺 39 集」假缺口,只升不降让污染
        // 永久化):ENDED 门禁跳过补正,存量污染(total>已播)夹回已播数自愈;在播滞后补正
        // 场景由 tencentOfficialNumbersOnlyRaiseTotal 覆盖
        Fixture fixture = new Fixture();
        MetadataDetails details = stubAbsoluteSeries(fixture, "百花杀");
        details.setStatus(MetadataDetails.STATUS_ENDED);
        fixture.subscription.setOfficialEpisodes(36);
        fixture.subscription.setOfficialTotal(75); // 已被腾讯口径污染的存量
        fixture.service.setTencentSeasonAligner(new cn.har01d.alist_tvbox.service.metadata.TencentSeasonAligner(null) {
            @Override
            public java.util.Map<Integer, Integer> seasonCounts(String seriesName, Integer firstYear) {
                return java.util.Map.of(1, 75);
            }
        });

        fixture.service.applyTencentOfficialNumbers(fixture.subscription);

        assertEquals(36, fixture.subscription.getOfficialTotal(), "ENDED 剧总数不被腾讯登记抬高,存量污染夹回已播 36");
        assertEquals(36, fixture.subscription.getOfficialEpisodes(), "已播口径不受影响");
    }

    @Test
    void tencentOfficialNumbersOnlyRaiseTotal() {
        // MbSearch 的 totalEpisode 是条目登记的分季集数,在播季含未上线分集(完结季登记 16、
        // 实更 8):求和只能当总集数下界,绝不能当已播 —— 当已播会凭空造缺口(线上:已播被推到
        // 181,列表「缺第 174-181 集」而 174 当晚才播)。已播滞后由 B站 refineAiredCount/schedule 兜底。
        Fixture fixture = new Fixture();
        stubAbsoluteSeries(fixture, "一念永恒");
        fixture.subscription.setOfficialEpisodes(173);
        fixture.subscription.setOfficialTotal(200);
        fixture.service.setTencentSeasonAligner(new cn.har01d.alist_tvbox.service.metadata.TencentSeasonAligner(null) {
            @Override
            public java.util.Map<Integer, Integer> seasonCounts(String seriesName, Integer firstYear) {
                return java.util.Map.of(1, 52, 2, 54, 3, 59, 4, 16);
            }
        });

        fixture.service.applyTencentOfficialNumbers(fixture.subscription);
        assertEquals(173, fixture.subscription.getOfficialEpisodes(), "登记总集数(181)不得覆盖已播(173)");
        assertEquals(200, fixture.subscription.getOfficialTotal(), "max(200,181) = 200 不倒退");

        // 腾讯之和超总数(完结季登记 47 集 = 212):总数跟着抬,已播仍不动
        fixture.service.setTencentSeasonAligner(new cn.har01d.alist_tvbox.service.metadata.TencentSeasonAligner(null) {
            @Override
            public java.util.Map<Integer, Integer> seasonCounts(String seriesName, Integer firstYear) {
                return java.util.Map.of(1, 52, 2, 54, 3, 59, 4, 47);
            }
        });
        fixture.service.applyTencentOfficialNumbers(fixture.subscription);
        assertEquals(173, fixture.subscription.getOfficialEpisodes(), "已播始终只认 provider/排播口径");
        assertEquals(212, fixture.subscription.getOfficialTotal());
    }

    @Test
    void computeMissingClampsToSeasonWindowEnd() {
        // 第 3 季订阅(起点 107,下一季起点 166):缺集窗口夹到 165 —— 不夹会把 S4 的
        // 166-181 算成本订阅缺口,补缺永远填不上、空转攒 stallCount
        Fixture fixture = new Fixture();
        stubAbsoluteSeries(fixture, "一念永恒");
        fixture.subscription.setSeason(3);
        fixture.subscription.setSeasonStartEpisode(107);
        fixture.subscription.setOfficialEpisodes(181);
        fixture.subscription.setOfficialTotal(200);
        fixture.service.setTencentSeasonAligner(new cn.har01d.alist_tvbox.service.metadata.TencentSeasonAligner(null) {
            @Override
            public java.util.Map<Integer, Integer> seasonStarts(String seriesName, Integer firstYear) {
                return java.util.Map.of(1, 1, 2, 53, 3, 107, 4, 166);
            }
        });

        Set<Integer> missing = fixture.service.computeMissing(fixture.subscription, new java.util.TreeSet<>(List.of(107, 108)));
        assertEquals(numbers(107, 165).stream().filter(e -> e > 108).toList(), new ArrayList<>(missing),
                "缺口上界 165(S4 的 166-181 不算)");
    }

    @Test
    void perSeasonSubscriptionAlignsSeasonStartAndGates() {
        // 分季订阅一念永恒形态:TMDB 单季装全剧,订阅第 3/4 季 —— ①元数据回落第 1 季,
        // ②seasonStartEpisode 按腾讯分季表自动推导,③「完结季」归位第 4 季(第 4 季订阅收/第 2 季订阅拒)
        Fixture fixture = new Fixture();
        stubAbsoluteSeries(fixture, "一念永恒");
        fixture.service.setTencentSeasonAligner(new cn.har01d.alist_tvbox.service.metadata.TencentSeasonAligner(null) {
            @Override
            public java.util.Map<Integer, Integer> seasonStarts(String seriesName, Integer firstYear) {
                return java.util.Map.of(1, 1, 2, 53, 3, 107, 4, 166);
            }

            @Override
            public Integer finaleSeason(String seriesName, Integer firstYear, Integer officialAired) {
                return 4;
            }
        });
        assertEquals(1, fixture.service.effectiveMetaSeason(fixture.subscription), "totalSeasons==1:第 1 季全剧口径");

        fixture.subscription.setSeason(3);
        fixture.service.ensureSeasonStartEpisode(fixture.subscription);
        assertEquals(107, fixture.subscription.getSeasonStartEpisode(), "第 3 季第 1 集 = 全剧第 107 集(腾讯分季表)");

        fixture.subscription.setSeason(4);
        fixture.subscription.setSeasonStartEpisode(null);
        fixture.service.ensureSeasonStartEpisode(fixture.subscription);
        assertEquals(166, fixture.subscription.getSeasonStartEpisode(), "完结季(第 4 季)起点 166");

        assertEquals(4, fixture.service.effectiveTitleSeason(fixture.subscription, "一念永恒 完结季(2026) 【更08集】"));
        assertEquals(2, fixture.service.effectiveTitleSeason(fixture.subscription, "一念永恒 第二季"));
        MediaSubscriptionResource finale = new MediaSubscriptionResource();
        finale.setTitle("一念永恒 完结季(2026) 【更08集】");
        assertTrue(fixture.service.belongsToShow(fixture.subscription, finale), "第 4 季订阅:完结季包放行");
        fixture.subscription.setSeason(2);
        fixture.subscription.setSeasonStartEpisode(53);
        assertFalse(fixture.service.belongsToShow(fixture.subscription, finale), "第 2 季订阅:完结季包(=第 4 季)拒绝");

        // 多季元数据的剧不走回落:season 透传
        MetadataDetails multi = new MetadataDetails();
        multi.setTotalSeasons(4);
        Mockito.when(fixture.metadataService.details(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(multi);
        fixture.subscription.setSeason(3);
        assertEquals(3, fixture.service.effectiveMetaSeason(fixture.subscription));
    }

    @Test
    void sanitizeDiscardsSeasonPackMappedBeyondOfficialRange() {
        // 线上(订阅 65):「完结季」标题的分享内是 S1 的 52 个裸编号文件,SINGLE 映射整体平移成
        // 166-217 —— 未播的 174-217 全被冒领成有源。映射后最大集号超 min(总集数,已播+容差)
        // = 包内容不是标题声明的季,整体弃收(与旧 alignResourceNumbering 平移后门禁同判据)
        Fixture fixture = new Fixture();
        stubAbsoluteSeries(fixture, "一念永恒");
        fixture.subscription.setOfficialEpisodes(173);
        fixture.subscription.setOfficialTotal(200);
        MediaSubscriptionResource mapped = new MediaSubscriptionResource();
        mapped.setId(81);
        mapped.setTitle("一念永恒 完结季(2026) 【更08集】");
        mapped.setSeasonStarts("4:166");
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> bogus = new TreeMap<>();
        for (int ep = 166; ep <= 217; ep++) { // 52 个裸编号文件平移后的结果
            bogus.put(ep, new MediaSubscriptionCheckService.EpisodeFile(ep, "/x", "第" + (ep - 165) + "集.mkv", 1, 0));
        }
        fixture.service.sanitizeEpisodeFiles(fixture.subscription, mapped, bogus, mapped.getTitle());
        assertTrue(bogus.isEmpty(), "映射后最大 217 超 min(200, 173+20):整体弃收");

        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> legit = new TreeMap<>();
        for (int ep = 166; ep <= 173; ep++) { // 真 完结季 8 集在官方口径内
            legit.put(ep, new MediaSubscriptionCheckService.EpisodeFile(ep, "/x", "第" + (ep - 165) + "集.mkv", 1, 0));
        }
        fixture.service.sanitizeEpisodeFiles(fixture.subscription, mapped, legit, mapped.getTitle());
        assertEquals(166, legit.firstKey());
        assertEquals(173, legit.lastKey(), "口径内的真季包照常保留");
    }

    @Test
    void sanitizeKeepsAlreadyMappedFilesUnshifted() {
        Fixture fixture = new Fixture();
        stubAbsoluteSeries(fixture, "一念永恒");
        MediaSubscriptionResource mapped = new MediaSubscriptionResource();
        mapped.setId(51);
        mapped.setTitle("一念永恒 完结季 4K [更新至08集]");
        mapped.setSeasonStarts("4:166"); // 已按文件级映射列举:文件已在全剧连续集号空间
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files = new TreeMap<>();
        for (int ep = 166; ep <= 173; ep++) {
            files.put(ep, new MediaSubscriptionCheckService.EpisodeFile(ep, "/x", "第" + (ep - 165) + "集.mkv", 1, 0));
        }

        fixture.service.sanitizeEpisodeFiles(fixture.subscription, mapped, files, mapped.getTitle());

        assertEquals(166, files.firstKey());
        assertEquals(173, files.lastKey(), "已映射资源:不再平移、不清池");
    }

    @Test
    void purgeForeignSeasonResourcesRemovesStaleSeasonRows() {
        Fixture fixture = new Fixture();
        fixture.subscription.setName("末日地堡");
        fixture.subscription.setSeason(3);
        MediaSubscriptionResource mountedS1 = new MediaSubscriptionResource();
        mountedS1.setId(11);
        mountedS1.setTitle("末日地堡第一季");
        mountedS1.setState(MediaSubscriptionResource.STATE_MOUNTED);
        mountedS1.setShareId(21);
        mountedS1.setMountPath("/追剧/.sources/uc@x@");
        MediaSubscriptionResource candidateS1 = new MediaSubscriptionResource();
        candidateS1.setId(12);
        candidateS1.setTitle("末日地堡 第一季");
        candidateS1.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        MediaSubscriptionResource bare = new MediaSubscriptionResource();
        bare.setId(13);
        bare.setTitle("末日地堡");
        bare.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        MediaSubscriptionResource rightSeason = new MediaSubscriptionResource();
        rightSeason.setId(14);
        rightSeason.setTitle("末日地堡 第三季 4K");
        rightSeason.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(mountedS1, candidateS1, bare, rightSeason));

        fixture.service.purgeForeignSeasonResources(fixture.subscription);

        Mockito.verify(fixture.resourceRepository).delete(mountedS1);
        Mockito.verify(fixture.resourceRepository).delete(candidateS1);
        Mockito.verify(fixture.resourceRepository, Mockito.never()).delete(bare);
        Mockito.verify(fixture.resourceRepository, Mockito.never()).delete(rightSeason);
        Mockito.verify(fixture.shareService).deleteShare(21); // 有挂载的才远程卸载
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(Mockito.eq(5));
        Mockito.verify(fixture.episodeSourceRepository).deleteByResourceIdIn(List.of(11));
        Mockito.verify(fixture.episodeSourceRepository).deleteByResourceIdIn(List.of(12));
        assertNull(fixture.subscription.getShareId(), "主源被清:shareId 置空走 ensureSource 重挂");
        ArgumentCaptor<MediaSubscriptionEvent> events = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository).save(events.capture());
        assertTrue(events.getValue().getDetail().contains("其它季资源 2 条"),
                "事件汇总清理条数: " + events.getValue().getDetail());
    }

    @Test
    void activateEmptyFilesRejectedAsForeignShow() {
        // activate 挂上后列不出本季任何文件:消息带 FOREIGN_SHOW_MARK,activateNextCandidate 的
        // 异剧分流退役不拉黑 —— 按瞬时故障累积会把换季残留的活链接烧成跨订阅黑名单
        assertTrue(MediaSubscriptionCheckService.isForeignShowRejection("疑似同名异剧(无可识别的本季剧集文件):末日地堡第一季"));
        assertFalse(MediaSubscriptionCheckService.isThrottleError("疑似同名异剧(无可识别的本季剧集文件):末日地堡第一季"));
    }

    // ---------- 改季残留检测(2026-08-24 二轮,线上末日地堡 S1→S3 播放实锤) ----------
    // 改季发生在重置功能上线之前,编辑路径不会再触发 —— 「检查」必须自己发现"集源行还挂在旧季
    // episode 行上"(可用性聚合不按季过滤,S1 的 LISTED 行冒领 S3 集号:逻辑线路标题是新季分集
    // 标题、点开播的是 S01E01)并就地全量重置,存量订阅点一次检查即恢复。

    @Test
    void staleSeasonInventoryForms() {
        Fixture fixture = new Fixture();
        fixture.subscription.setSeason(3);
        MediaSubscriptionEpisode s1Episode = new MediaSubscriptionEpisode();
        s1Episode.setId(101);
        s1Episode.setSeason(1);
        s1Episode.setNumber(1);
        MediaSubscriptionEpisode s3Episode = new MediaSubscriptionEpisode();
        s3Episode.setId(301);
        s3Episode.setSeason(3);
        s3Episode.setNumber(1);
        Mockito.when(fixture.episodeRepository.findBySubscriptionIdOrderByNumber(1))
                .thenReturn(List.of(s1Episode, s3Episode));
        MediaSubscriptionEpisodeSource s1Row = new MediaSubscriptionEpisodeSource();
        s1Row.setEpisodeId(101);
        MediaSubscriptionEpisodeSource s3Row = new MediaSubscriptionEpisodeSource();
        s3Row.setEpisodeId(301);
        Mockito.when(fixture.episodeSourceRepository.findBySubscriptionAndStatesIn(Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(List.of(s3Row));
        assertFalse(fixture.service.staleSeasonInventory(fixture.subscription), "集源行全挂本季:无残留");

        Mockito.when(fixture.episodeSourceRepository.findBySubscriptionAndStatesIn(Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(List.of(s1Row, s3Row));
        assertTrue(fixture.service.staleSeasonInventory(fixture.subscription), "旧季 LIVE 行冒领集号:残留");

        MediaSubscriptionEpisode special = new MediaSubscriptionEpisode();
        special.setId(1);
        special.setSeason(0); // 特别篇 season=0:合法跨季附属,不算残留
        special.setNumber(1);
        MediaSubscriptionEpisodeSource specialRow = new MediaSubscriptionEpisodeSource();
        specialRow.setEpisodeId(1);
        Mockito.when(fixture.episodeRepository.findBySubscriptionIdOrderByNumber(1)).thenReturn(List.of(special));
        Mockito.when(fixture.episodeSourceRepository.findBySubscriptionAndStatesIn(Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(List.of(specialRow));
        assertFalse(fixture.service.staleSeasonInventory(fixture.subscription), "特别篇不算残留");

        Mockito.when(fixture.episodeSourceRepository.findBySubscriptionAndStatesIn(Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(List.of());
        assertFalse(fixture.service.staleSeasonInventory(fixture.subscription), "无 LIVE 行:无从判定");

        fixture.subscription.setSeason(null);
        Mockito.when(fixture.episodeSourceRepository.findBySubscriptionAndStatesIn(Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(List.of(s1Row));
        assertFalse(fixture.service.staleSeasonInventory(fixture.subscription), "订阅未指定季:门禁关闭");
    }

    @Test
    void staleSeasonReopenForms() {
        Fixture fixture = new Fixture();
        fixture.subscription.setSeason(3);
        fixture.subscription.setStatus(MediaSubscription.STATUS_ENDED);
        MediaSubscriptionEpisode s1Episode = new MediaSubscriptionEpisode();
        s1Episode.setId(101);
        s1Episode.setSeason(1);
        s1Episode.setNumber(1);
        MediaSubscriptionEpisodeSource s1Row = new MediaSubscriptionEpisodeSource();
        s1Row.setEpisodeId(101);
        Mockito.when(fixture.episodeRepository.findBySubscriptionIdOrderByNumber(1)).thenReturn(List.of(s1Episode));
        Mockito.when(fixture.episodeSourceRepository.findBySubscriptionAndStatesIn(Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(List.of(s1Row));

        assertTrue(fixture.service.staleSeasonReopen(fixture.subscription),
                "ENDED+旧季行冒领:强制重开(shouldReopen 被本地=官方堵死,唯一出路)");
        assertEquals(MediaSubscription.STATUS_ACTIVE, fixture.subscription.getStatus());
        assertEquals(0, fixture.subscription.getStallCount());

        Mockito.when(fixture.episodeSourceRepository.findBySubscriptionAndStatesIn(Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(List.of());
        fixture.subscription.setStatus(MediaSubscription.STATUS_ENDED);
        assertFalse(fixture.service.staleSeasonReopen(fixture.subscription), "无残留:维持每日轻量复查");
        assertEquals(MediaSubscription.STATUS_ENDED, fixture.subscription.getStatus());
    }

    @Test
    void resetInventoryForSeasonClearsWorld() {
        Fixture fixture = new Fixture();
        MediaSubscription subscription = fixture.subscription;
        subscription.setSeason(3);
        subscription.setStatus(MediaSubscription.STATUS_ENDED);
        subscription.setShareId(5);
        subscription.setCurrentEpisodes(10);
        subscription.setMaxEpisode(10);
        subscription.setMetaSyncTime(123L);
        subscription.setCaughtUpEpisode(10);
        subscription.setCoverUrl("https://example/old-season.jpg");
        MediaSubscriptionResource stale = new MediaSubscriptionResource();
        stale.setId(21);
        stale.setShareId(51);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(stale));

        fixture.service.resetInventoryForSeason(subscription, 3);

        Mockito.verify(fixture.episodeSourceRepository).deleteByResourceIdIn(List.of(21));
        Mockito.verify(fixture.episodeRepository).deleteBySubscriptionId(1);
        Mockito.verify(fixture.resourceRepository).deleteBySubscriptionId(1);
        assertNull(subscription.getShareId(), "主源挂载引用断开");
        assertNull(subscription.getCoverUrl(), "旧季封面快照作废");
        assertNull(subscription.getMetaSyncTime(), "旧季元数据快照作废,首轮巡检重拉");
        assertNull(subscription.getCaughtUpEpisode(), "追平门槛按旧季观看进度累计,新季口径作废");
        assertEquals(0, subscription.getCurrentEpisodes());
        assertNull(subscription.getMaxEpisode());
        assertEquals(0, subscription.getStallCount());
        assertEquals(MediaSubscription.STATUS_ACTIVE, subscription.getStatus(), "旧季完结状态随换季作废");
        ArgumentCaptor<MediaSubscriptionEvent> events = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository).save(events.capture());
        assertTrue(events.getValue().getDetail().contains("第3季"), "事件说明换季重置: " + events.getValue().getDetail());
    }

    @Test
    void resetInventoryForSeasonClearsFallbackOverlay() {
        // 覆盖层行只按 (subscription, episode) 键、无季列:换季重置不连带清,
        // 旧季直链会在新季首轮搜索前经播放兜底冒领集号
        MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
        MediaSubscriptionEventRepository eventRepository = Mockito.mock(MediaSubscriptionEventRepository.class);
        MediaSubscriptionEpisodeRepository episodeRepository = Mockito.mock(MediaSubscriptionEpisodeRepository.class);
        MediaSubscriptionEpisodeSourceRepository episodeSourceRepository = Mockito.mock(MediaSubscriptionEpisodeSourceRepository.class);
        MediaSubscriptionEpisodeFallbackRepository fallbackRepository = Mockito.mock(MediaSubscriptionEpisodeFallbackRepository.class);
        MediaSubscriptionCheckService svc = new MediaSubscriptionCheckService(
                null, resourceRepository, eventRepository, episodeRepository, episodeSourceRepository, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, appProperties, new ObjectMapper(), null, null, null, null,
                fallbackRepository
                );
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(9);
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(9)).thenReturn(List.of());

        svc.resetInventoryForSeason(subscription, 3);

        Mockito.verify(fallbackRepository).deleteBySubscriptionId(9);
    }

    @Test
    void purgeForeignSeasonResourcesSkipsWhenSeasonUnknown() {
        Fixture fixture = new Fixture();
        fixture.subscription.setName("末日地堡");
        fixture.subscription.setSeason(null); // 未指定季:门禁关闭,池子不动
        MediaSubscriptionResource s1 = new MediaSubscriptionResource();
        s1.setId(11);
        s1.setTitle("末日地堡第一季");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(s1));

        fixture.service.purgeForeignSeasonResources(fixture.subscription);

        Mockito.verify(fixture.resourceRepository, Mockito.never()).delete(Mockito.any());
        Mockito.verify(fixture.eventRepository, Mockito.never()).save(Mockito.any());
    }

    // ---------- 集号范围门禁(2026-08-23):真人版《仙剑奇侠传三》37 集顶在动画版订阅(官方 26 集)上 ----------
    // 标题「仙剑奇侠传三 2160P」无年份无类型词,标题/年份门禁全部放行 —— 真人版资源挂成主源后
    // maxEpisode=37、误判 ENDED,补缺逻辑还去找 27-37 集。探测出的集号超出官方总集数是唯一可靠信号。

    @Test
    void episodeRangeGateForms() {
        MediaSubscription subscription = new MediaSubscription();
        assertFalse(MediaSubscriptionCheckService.episodeNumbersForeign(subscription, episodeRange(1, 99)),
                "官方总集数未知:门禁关闭(零误伤)");
        assertFalse(MediaSubscriptionCheckService.episodeNumbersForeign(subscription, Set.of()),
                "无集号无从判定:放行");
        subscription.setOfficialTotal(26);
        assertFalse(MediaSubscriptionCheckService.episodeNumbersForeign(subscription, episodeRange(1, 26)),
                "集号不超官方:放行");
        // 本季已播完(26/26 且无下集排播):超出即拒 —— 播完的季不可能再冒新集号
        subscription.setOfficialEpisodes(26);
        assertTrue(MediaSubscriptionCheckService.episodeNumbersForeign(subscription, episodeRange(1, 27)),
                "已播完+超 1 集:异剧(线上形态 26 官方 vs 真人版 37)");
        assertTrue(MediaSubscriptionCheckService.episodeNumbersForeign(subscription, episodeRange(1, 37)));
        // 未播完:+2 容差(TMDB 登记总集数滞后于实际排播),超出 3 拒
        subscription.setOfficialEpisodes(20);
        assertFalse(MediaSubscriptionCheckService.episodeNumbersForeign(subscription, episodeRange(1, 28)),
                "未播完+2 集容差:放行(排播滞后)");
        assertTrue(MediaSubscriptionCheckService.episodeNumbersForeign(subscription, episodeRange(1, 29)),
                "未播完+超容差:拒");
        subscription.setNextAirTime(System.currentTimeMillis() + 3600_000);
        assertFalse(MediaSubscriptionCheckService.episodeNumbersForeign(subscription, episodeRange(1, 28)),
                "有下集排播 = 未播完口径,容差内放行");
    }

    // ---------- 长寿剧登记滞后容差(2026-08-27):固定 +2 容差误杀千集动漫正确主源 ----------
    // 线上事故(名侦探柯南):TMDB 登记总 1212,网盘实际更至 1270(滞后 58 集),集号门禁按
    // "溢出 > 2 = 同名异剧" 把正确主源整体退役。登记滞后量级与体量相关:容差随总集数放大
    // (每满 10 集容忍 1 集,下限 2),小体量区间(26 vs 37 真人版)判别力不变。

    @Test
    void episodeRangeGateToleratesLongShowRegistrationLag() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setOfficialTotal(1212);
        subscription.setOfficialEpisodes(1210); // 未播完(RETURNING,有下集排播)
        assertFalse(MediaSubscriptionCheckService.episodeNumbersForeign(subscription, episodeRange(1173, 1270)),
                "千集动漫登记滞后 58 集(≤ 体量容差 121):放行,线上柯南形态");
        assertTrue(MediaSubscriptionCheckService.episodeNumbersForeign(subscription, episodeRange(1, 1400)),
                "溢出 188 超出体量容差:仍判异剧拒");
        assertFalse(MediaSubscriptionCheckService.episodeNumbersForeign(subscription, episodeRange(1, 1214)),
                "溢出 2 在下限容差内:放行");
    }

    @Test
    void titleProgressGateToleratesLongShowRegistrationLag() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setOfficialTotal(1212);
        subscription.setOfficialEpisodes(1210);
        assertFalse(MediaSubscriptionCheckService.titleProgressForeign(subscription, "名侦探柯南 更新至1270集"),
                "宣称 1270 vs 登记 1212,滞后在体量容差内:放行");
        assertTrue(MediaSubscriptionCheckService.titleProgressForeign(subscription, "名侦探柯南 更新至1400集"),
                "宣称超出体量容差:拒");
    }

    @Test
    void probeShareRetiresForeignEpisodeRange() {
        // 探测期拦截:37 集真人版资源(官方 26 已播完)临时挂载列出 1-37 → 就地退役(不拉黑)再抛
        Fixture fixture = new Fixture();
        fixture.subscription.setOfficialTotal(26);
        fixture.subscription.setOfficialEpisodes(26);
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(9);
        resource.setSubscriptionId(1);
        resource.setLink("https://pan.quark.cn/s/xianjian37");
        resource.setTitle("仙剑奇侠传三 2160P");
        resource.setType(5);
        resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Share temp = new Share();
        temp.setId(77);
        temp.setPath("/我的夸克分享/temp/quark@xianjian37@");
        Share probe = new Share();
        probe.setType(5);
        probe.setShareId("xianjian37");
        Mockito.when(fixture.shareService.parseShareLink("https://pan.quark.cn/s/xianjian37")).thenReturn(probe);
        Mockito.when(fixture.shareRepository.findByTypeAndShareIdAndTempTrue(5, "xianjian37")).thenReturn(List.of(temp));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files(s01EpisodeFiles(37)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> fixture.service.probeShare(fixture.subscription, resource));

        assertTrue(MediaSubscriptionCheckService.isForeignShowRejection(error.getMessage()),
                "拒绝消息须含异剧标记,让换源调用方退役分流: " + error.getMessage());
        assertEquals(MediaSubscriptionResource.STATE_RETIRED, resource.getState(), "异剧候选就地退役");
        assertNotNull(resource.getCheckedTime(), "退役计时:冷却期满重探自愈(官方集数修正后可恢复)");
        Mockito.verifyNoInteractions(fixture.deadLinkRepository); // 链接没死:不进跨订阅黑名单
        Mockito.verify(fixture.shareService).deleteShare(77); // 临时挂载窗口用后即删
    }

    // ---------- 统一探测失败分级(2026-08-24 review):限流/异剧不得落入退役+拉黑 ----------

    @Test
    void probeThrottleRetiresNothingAndSkipsDrive() {
        // 限流(errno -62)不是资源失效:统一探测入口只记盘限流退避 —— 此前 fillGaps/主盘/线路
        // 三路 catch 只保护 TRANSIENT,限流直接 RETIRED+markDeadLink,好源被烧成 90 天黑名单
        Fixture fixture = new Fixture();
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(11);
        resource.setSubscriptionId(1);
        resource.setLink("https://pan.baidu.com/s/throttled");
        resource.setTitle("测试剧 全26集");
        resource.setType(10);
        resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.shareService.add(Mockito.any()))
                .thenThrow(new IllegalStateException("分享添加失败:{\"errno\":-62}"));

        assertEquals(MediaSubscriptionCheckService.ProbeOutcome.THROTTLED,
                fixture.service.probeCandidateSafely(fixture.subscription, resource));
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, resource.getState(), "限流不退役");
        assertNotNull(resource.getCheckedTime(), "退避计时照记");
        Mockito.verifyNoInteractions(fixture.deadLinkRepository); // 更不进跨订阅黑名单
    }

    @Test
    void probeForeignRejectionRetiresWithoutBlacklistViaUnifiedEntry() {
        // 异剧标记消息经统一探测入口:幂等重放就地退役(RETIRED 冷却重探),链接活着不拉黑
        Fixture fixture = new Fixture();
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(12);
        resource.setSubscriptionId(1);
        resource.setLink("https://pan.baidu.com/s/alien37");
        resource.setTitle("测试剧真人版 全37集");
        resource.setType(10);
        resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.shareService.add(Mockito.any()))
                .thenThrow(new IllegalStateException("集号超出官方范围(第37集 > 官方26集),疑似同名异剧:测试剧真人版 全37集"));

        assertEquals(MediaSubscriptionCheckService.ProbeOutcome.ALIEN,
                fixture.service.probeCandidateSafely(fixture.subscription, resource));
        assertEquals(MediaSubscriptionResource.STATE_RETIRED, resource.getState(), "异剧退役冷却");
        Mockito.verifyNoInteractions(fixture.deadLinkRepository); // 链接没死:不进跨订阅黑名单
    }

    @Test
    void belongsToShowPrefersObservedFilesOverStaleRows() {
        // 噪声剔除上线前的存量毒行(26+142 落库)不应把主体正确的主源误判异剧:doCheck 复核
        // 传本轮清洗后的文件集;旧行口径(2 参重载)按 DB 行判 —— 印证毒行必须被洗掉
        Fixture fixture = new Fixture();
        fixture.subscription.setOfficialTotal(26);
        fixture.subscription.setOfficialEpisodes(26);
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(3);
        primary.setTitle("测试剧 4K 高码率");
        primary.setType(10);
        List<Integer> poisonRows = new ArrayList<>();
        for (int i = 1; i <= 26; i++) {
            poisonRows.add(i);
        }
        poisonRows.add(142);
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(
                        Mockito.eq(3), Mockito.any())).thenReturn(poisonRows);

        assertTrue(fixture.service.belongsToShow(fixture.subscription, primary, episodeRange(1, 26)),
                "复核以本轮清洗后的文件集为准:主体正确的主源不是异剧");
        assertFalse(fixture.service.belongsToShow(fixture.subscription, primary),
                "回落 DB 行口径时 142 毒行仍会误判异剧 —— 无候选路径须先 syncInventory 洗行");
    }

    // ---------- 文件级噪声剔除(2026-08-23 深夜,线上 142 集)----------
    // 正确的百度补缺资源目录里被分享者塞进《都市仙医》S01E142:parseEpisode 解析出 142 落库
    // present=true,详情分集列表被撑到 1-142;且会让资源级门禁把这个主体正确的资源整体误杀。
    // 剔除判据:超出官方总集数且与范围断裂(26→142 跳变)= 噪声;从 total+1 连续衔接的尾部
    // (真人版 1-37 / TMDB 滞后真集)保留,交给资源级门禁判真伪。

    @Test
    void stripForeignEpisodeNoiseForms() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setOfficialTotal(26);
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files = episodeFiles(1, 26);
        files.put(142, episodeFile(142));
        MediaSubscriptionCheckService.stripForeignEpisodeNoise(subscription, files);
        assertEquals(episodeRange(1, 26), files.keySet(), "断裂跳号 142 剔除,主体 1-26 保留");

        files = episodeFiles(1, 37);
        MediaSubscriptionCheckService.stripForeignEpisodeNoise(subscription, files);
        assertEquals(episodeRange(1, 37), files.keySet(), "衔接链尾部(27-37 连续)保留:由资源级门禁判真伪");

        files = episodeFiles(1, 28);
        files.put(142, episodeFile(142));
        MediaSubscriptionCheckService.stripForeignEpisodeNoise(subscription, files);
        assertEquals(episodeRange(1, 28), files.keySet(), "衔接(27/28)保留 + 断裂(142)剔除");

        MediaSubscription noTotal = new MediaSubscription(); // 官方总集数未知
        files = episodeFiles(1, 26);
        files.put(142, episodeFile(142));
        MediaSubscriptionCheckService.stripForeignEpisodeNoise(noTotal, files);
        assertEquals(episodeRange(1, 26).size() + 1, files.size(), "官方总集数未知:不剔(零误伤)");
    }

    @Test
    void probeShareKeepsCorrectResourceWithPoisonFile() {
        // 毒文件不误杀:主体 1-26 正确 + 混入 S01E142 → 剔噪后资源干净通过门禁,142 不落行
        Fixture fixture = new Fixture();
        fixture.subscription.setOfficialTotal(26);
        fixture.subscription.setOfficialEpisodes(26);
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(9);
        resource.setSubscriptionId(1);
        resource.setLink("https://pan.baidu.com/s/anim26");
        resource.setTitle("仙剑奇侠传叁/仙剑奇侠传三/仙剑奇侠传(2025)【更26集】【4K.臻彩MAX】");
        resource.setType(10);
        resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Share temp = new Share();
        temp.setId(77);
        temp.setPath("/我的百度分享/temp/baidu@anim26@");
        Share probe = new Share();
        probe.setType(10);
        probe.setShareId("anim26");
        Mockito.when(fixture.shareService.parseShareLink("https://pan.baidu.com/s/anim26")).thenReturn(probe);
        Mockito.when(fixture.shareRepository.findByTypeAndShareIdAndTempTrue(10, "anim26")).thenReturn(List.of(temp));
        List<String> names = new ArrayList<>(List.of(s01EpisodeFiles(26)));
        names.add("Immortal Doctor In Modern City S01E142 - 第 142 集 - 2160p WEB-DL H265 AAC.mp4");
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files(names.toArray(new String[0])));
        RowStore store = new RowStore();
        store.install(fixture);
        fixture.service.setStreamProbeClient((url, userAgent, maxBytes, timeoutSeconds) ->
                new StreamProbeClient.ProbeResult(206, "video/mp4", new byte[]{0x1A, 0x45}));
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString())).thenReturn(rawUrlDetail());

        fixture.service.probeShare(fixture.subscription, resource); // 不抛:资源通过门禁

        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, resource.getState(), "主体正确的资源不因毒文件退役");
        assertFalse(store.episodes.containsKey(142), "142 噪声集号不落 episode/集源行");
        assertTrue(store.episodes.containsKey(26), "主体集号照常落行");
        Mockito.verifyNoInteractions(fixture.deadLinkRepository);
    }

    // ---------- 元数据信号增强(2026-08-23,用户提议"集数/播出时间/单集长度/类型/演员应帮助判断匹配度")----------
    // 可落地的三路:标题宣称集数(入池即拦,不等挂载探测)、单集时长(补集号门禁未播完容差盲区,
    // 时长是内容属性不受码率影响)、版本词(动画订阅拒显式「真人版」)。年份已有门禁;演员
    // 负向不可枚举(不知道对面剧的演员表)正向太罕见,不做。

    @Test
    void titleProgressForeignForms() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setOfficialTotal(26);
        subscription.setOfficialEpisodes(26); // 26/26 已播完
        assertFalse(MediaSubscriptionCheckService.titleProgressForeign(subscription, "仙剑奇侠传三 2160P"),
                "标题不宣称集数:交探测层集号门禁");
        assertFalse(MediaSubscriptionCheckService.titleProgressForeign(subscription, "仙剑奇侠传三 全26集"),
                "宣称与官方一致:放行");
        assertTrue(MediaSubscriptionCheckService.titleProgressForeign(subscription, "仙剑奇侠传三 全37集 4K"),
                "已播完+宣称 37 > 官方 26:真人版全集包,入池即拒");
        assertFalse(MediaSubscriptionCheckService.titleProgressForeign(subscription, "鬼灭之刃 全52集 合集 4K"),
                "合集词在场:宣称的是跨季总数,交探测层季过滤");
        assertFalse(MediaSubscriptionCheckService.titleProgressForeign(subscription, "鬼灭之刃 第1-3季 全52集"),
                "季区间标记:多季合一包,跳过");
        assertFalse(MediaSubscriptionCheckService.titleProgressForeign(subscription, "瑞克和莫蒂 第二季 全10集"),
                "单季标记:订的是别的季,季过滤管,不在此拦");
        // 未播完:+2 容差(TMDB 登记滞后),超出 3 拒
        subscription.setOfficialEpisodes(20);
        assertFalse(MediaSubscriptionCheckService.titleProgressForeign(subscription, "测试剧 更新至28集"),
                "未播完容差内:放行");
        assertTrue(MediaSubscriptionCheckService.titleProgressForeign(subscription, "测试剧 更新至30集"),
                "未播完+超容差:拒");
        MediaSubscription unknown = new MediaSubscription(); // 官方总集数未知
        assertFalse(MediaSubscriptionCheckService.titleProgressForeign(unknown, "随便 全999集"), "官方未知:门禁关闭");
    }

    // ---------- 非剧本内容豁免(2026-08-27,借鉴 Node.js 追更助手 shouldUseTmdbReferenceScoring)----------
    // TMDB 对综艺/纪实的季总集数登记天然不可靠(随录随播、加更/删减常态),集数类门禁对这类
    // 内容整体豁免;只认 genres 正向证据(不做标题词兜底:「新闻女王」是剧本剧),genres 缺失不豁免。

    @Test
    void varietyShowEpisodeGatesRelaxed() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setOfficialTotal(12);
        subscription.setOfficialEpisodes(12); // TMDB 登记 12/12 已播完,实际播出 20 集
        List<String> variety = List.of("真人秀");
        assertTrue(MediaSubscriptionCheckService.episodeNumbersForeign(subscription, episodeRange(1, 20)),
                "剧本内容对照组:已播完+超 8 集 = 异剧");
        assertFalse(MediaSubscriptionCheckService.episodeNumbersForeign(subscription, episodeRange(1, 20), variety),
                "综艺:登记总集数不可靠,集号超界不再是异剧信号");
        assertTrue(MediaSubscriptionCheckService.titleProgressForeign(subscription, "乘风破浪 全20集 4K"),
                "剧本内容对照组:宣称 20 > 登记 12 且已播完 = 拒");
        assertFalse(MediaSubscriptionCheckService.titleProgressForeign(subscription, "乘风破浪 全20集 4K", variety),
                "综艺:「全N集」宣称不据以拒");
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files = episodeFiles(1, 12);
        files.put(16, episodeFile(16)); // 断裂跳号(13-15 缺):剧本形态是噪声,综艺形态是常态缺号
        MediaSubscriptionCheckService.stripForeignEpisodeNoise(subscription, files, variety);
        assertTrue(files.containsKey(16), "综艺:断裂跳号不剔(整季缺号是常态)");
        assertFalse(MediaSubscriptionCheckService.nonScriptedContent(List.of("剧情", "悬疑")),
                "剧本类型不豁免");
        assertFalse(MediaSubscriptionCheckService.nonScriptedContent(List.of()),
                "genres 缺失(豆瓣纯源):不豁免,门禁维持");
    }

    @Test
    void episodeDurationForeignForms() {
        // 线上形态:真人版单集 45min(duration 2700s,夸克返回)vs 动画版官方 20min
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> liveAction = new TreeMap<>();
        for (int i = 1; i <= 37; i++) {
            liveAction.put(i, episodeFile(i, 2700));
        }
        assertTrue(MediaSubscriptionCheckService.episodeDurationForeign(20, liveAction.values()),
                "时长 45min vs 官方 20min(差>50%):异剧");
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> anime = new TreeMap<>();
        for (int i = 1; i <= 26; i++) {
            anime.put(i, episodeFile(i, 1200));
        }
        assertFalse(MediaSubscriptionCheckService.episodeDurationForeign(20, anime.values()),
                "时长 20min 与官方一致:放行");
        anime.put(26, episodeFile(26, 2700)); // 单集加长(季终特番常见)
        assertFalse(MediaSubscriptionCheckService.episodeDurationForeign(20, anime.values()),
                "中位数抗单集异常:个别加长集不误判");
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> noDuration = new TreeMap<>();
        for (int i = 1; i <= 26; i++) {
            noDuration.put(i, episodeFile(i, 0)); // 百度形态:驱动不返回 duration
        }
        assertFalse(MediaSubscriptionCheckService.episodeDurationForeign(20, noDuration.values()),
                "时长覆盖不足:门禁跳过零误伤");
        assertFalse(MediaSubscriptionCheckService.episodeDurationForeign(null, liveAction.values()),
                "元数据无单集时长:门禁关闭");
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> few = new TreeMap<>();
        few.put(1, episodeFile(1, 2700));
        few.put(2, episodeFile(2, 2700));
        assertFalse(MediaSubscriptionCheckService.episodeDurationForeign(20, few.values()),
                "文件少于 3 个(新剧首集/单集链接):样本太少不判");
    }

    @Test
    void liveActionForeignForms() {
        List<String> animationGenres = List.of("动画", "动作冒险");
        assertTrue(MediaSubscriptionCheckService.liveActionForeign(animationGenres, "仙剑奇侠传三 真人版 全集 4K"),
                "动画订阅 + 标题显式真人版:拒");
        assertFalse(MediaSubscriptionCheckService.liveActionForeign(animationGenres, "仙剑奇侠传三 2160P"),
                "无版本词:集数/时长门禁管");
        assertFalse(MediaSubscriptionCheckService.liveActionForeign(List.of(), "仙剑奇侠传三 真人版"),
                "genres 缺失(豆瓣订阅):无正向证据不判");
        assertFalse(MediaSubscriptionCheckService.liveActionForeign(List.of("古装", "剧情"), "仙剑奇侠传三 真人版"),
                "真人剧订阅遇真人版资源:单向门禁不拦反向");
        assertTrue(MediaSubscriptionCheckService.liveActionForeign(List.of("动漫"), "测试剧【真人连续剧】高清"),
                "「真人连续剧」词形与「动漫」genres 同样命中");
    }

    @Test
    void probeShareRejectsLiveActionByDuration() {
        // 探测期时长拦截:夸克真人版 37 文件 duration 2727s,官方单集 20min —— 集号门禁
        // 的未播完容差盲区(1-28 集号合法)由时长信号补刀
        Fixture fixture = new Fixture();
        fixture.subscription.setOfficialTotal(30); // 未播完场景:官方 30,资源集号 1-37 恰在容差内?
        // 容差口径:37-30=7 > 2 → 集号门禁已拒。改官方 34:34+2=36 < 37 仍拒;要让集号门禁放行
        // 需 claimed ≤ total+2,这里只验时长路:officialTotal 置 40(集号门禁关),纯时长判
        fixture.subscription.setOfficialTotal(40);
        fixture.subscription.setMetaProvider("tmdb");
        fixture.subscription.setMetaId("233295");
        MetadataDetails details = new MetadataDetails();
        details.setRuntimeMinutes(20);
        Mockito.when(fixture.metadataService.details(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(details);
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(9);
        resource.setSubscriptionId(1);
        resource.setLink("https://pan.quark.cn/s/live37");
        resource.setTitle("仙剑奇侠传三 2160P");
        resource.setType(5);
        resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Share temp = new Share();
        temp.setId(77);
        temp.setPath("/我的夸克分享/temp/quark@live37@");
        Share probe = new Share();
        probe.setType(5);
        probe.setShareId("live37");
        Mockito.when(fixture.shareService.parseShareLink("https://pan.quark.cn/s/live37")).thenReturn(probe);
        Mockito.when(fixture.shareRepository.findByTypeAndShareIdAndTempTrue(5, "live37")).thenReturn(List.of(temp));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(filesWithDuration(2727, s01EpisodeFiles(37)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> fixture.service.probeShare(fixture.subscription, resource));

        assertTrue(MediaSubscriptionCheckService.isForeignShowRejection(error.getMessage()),
                "时长拒绝消息须含异剧标记: " + error.getMessage());
        assertEquals(MediaSubscriptionResource.STATE_RETIRED, resource.getState(), "就地退役冷却");
        Mockito.verifyNoInteractions(fixture.deadLinkRepository); // 不拉黑
    }

    /** 构造带单集时长(秒)的目录响应:夸克等驱动返回 duration,百度等返回 0。 */
    private static FsResponse filesWithDuration(long durationSeconds, String... names) {
        FsResponse response = new FsResponse();
        List<FsInfo> list = new ArrayList<>();
        for (String name : names) {
            FsInfo info = new FsInfo();
            info.setName(name);
            info.setType(0);
            info.setSize(500L * 1024 * 1024);
            info.setDuration((int) durationSeconds);
            list.add(info);
        }
        response.setFiles(list);
        return response;
    }

    @Test
    void activateRejectsForeignEpisodeRangeAndCleansMount() {
        // 换源挂载期拦截:门禁抛错前卸掉刚挂的分享,固定路径不能残留异剧目录
        Fixture fixture = new Fixture();
        fixture.subscription.setOfficialTotal(26);
        fixture.subscription.setOfficialEpisodes(26);
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(9);
        resource.setSubscriptionId(1);
        resource.setLink("https://pan.quark.cn/s/xianjian37");
        resource.setTitle("仙剑奇侠传三 2160P");
        resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Share mounted = new Share();
        mounted.setId(66);
        Mockito.when(fixture.shareRepository.findByPath("/追剧/1-测试剧"))
                .thenReturn(null)      // 无旧主源挂载
                .thenReturn(mounted);  // 新分享挂上后
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files(s01EpisodeFiles(37)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> fixture.service.activate(fixture.subscription, resource));

        assertTrue(MediaSubscriptionCheckService.isForeignShowRejection(error.getMessage()),
                "拒绝消息须含异剧标记: " + error.getMessage());
        Mockito.verify(fixture.shareService).deleteShare(66); // 刚挂的异剧分享就地下掉
    }

    @Test
    void activateNextCandidateSkipsForeignAndMountsNext() {
        // 异剧候选被拒退役不拉黑,同轮继续尝试下一个候选(正确 26 集资源接管主源)
        Fixture fixture = new Fixture();
        fixture.subscription.setOfficialTotal(26);
        fixture.subscription.setOfficialEpisodes(26);
        MediaSubscriptionResource foreign = new MediaSubscriptionResource();
        foreign.setId(51);
        foreign.setSubscriptionId(1);
        foreign.setLink("https://pan.quark.cn/s/xianjian37");
        foreign.setTitle("测试剧 2160P 全集");
        foreign.setType(5);
        foreign.setScore(120);
        foreign.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        MediaSubscriptionResource right = new MediaSubscriptionResource();
        right.setId(52);
        right.setSubscriptionId(1);
        right.setLink("https://pan.baidu.com/s/anim26");
        right.setTitle("测试剧 (2025) 4K 全26集");
        right.setType(10);
        right.setScore(100);
        right.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(foreign, right));
        Share firstMount = new Share();
        firstMount.setId(66);
        Share secondMount = new Share();
        secondMount.setId(88);
        Mockito.when(fixture.shareRepository.findByPath("/追剧/1-测试剧"))
                .thenReturn(null).thenReturn(firstMount)   // 异剧:无旧挂载 → 挂上
                .thenReturn(firstMount).thenReturn(secondMount); // 正确源:卸异剧残留 → 挂上
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files(s01EpisodeFiles(37)))
                .thenReturn(files(s01EpisodeFiles(26)));
        RowStore store = new RowStore();
        store.install(fixture);

        assertTrue(fixture.service.activateNextCandidate(fixture.subscription), "异剧被跳过后正确候选挂上");

        assertEquals(MediaSubscriptionResource.STATE_RETIRED, foreign.getState(), "异剧候选退役冷却");
        assertNotNull(foreign.getCheckedTime());
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, right.getState(), "第二个候选接管主源");
        Mockito.verifyNoInteractions(fixture.deadLinkRepository); // 异剧不拉黑:链接没死,真人版订阅可能正用着;
    }

    // ---------- 待看集覆盖入主源排序(2026-08-27,借鉴追更助手 coversExpectedEpisode)----------
    // 换源时用户要续看的正是 watched+1 那集:集源行已知含待看集的候选提前于分数序;
    // 观看进度未知(无播放记录)时零侵入,维持原分数序。

    @Test
    void activatePrefersCandidateCoveringNextWatchEpisode() {
        // 看过第4集:集源行已知含第5集的低分候选(100)先于高分但覆盖未知的候选(120)接管主源
        Fixture fixture = new Fixture();
        Mockito.when(fixture.historyRepository.findByUidAndVodId(Mockito.anyInt(), Mockito.eq("msub:1")))
                .thenReturn(List.of(playHistory("msubep-1-4", System.currentTimeMillis())));
        MediaSubscriptionResource high = new MediaSubscriptionResource();
        high.setId(61);
        high.setSubscriptionId(1);
        high.setLink("https://pan.quark.cn/s/high");
        high.setTitle("测试剧 4K 全集");
        high.setType(5);
        high.setScore(120);
        high.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        MediaSubscriptionResource covering = new MediaSubscriptionResource();
        covering.setId(62);
        covering.setSubscriptionId(1);
        covering.setLink("https://pan.baidu.com/s/next5");
        covering.setTitle("测试剧 (2025) 4K 全集");
        covering.setType(10);
        covering.setScore(100);
        covering.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(high, covering));
        RowStore store = new RowStore();
        store.install(fixture);
        store.addEpisodeAndRow(62, 4, MediaSubscriptionEpisodeSource.STATE_LISTED);
        store.addEpisodeAndRow(62, 5, MediaSubscriptionEpisodeSource.STATE_LISTED);
        Share mount = new Share();
        mount.setId(66);
        Mockito.when(fixture.shareRepository.findByPath("/追剧/1-测试剧"))
                .thenReturn(null).thenReturn(mount).thenReturn(mount);
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files(s01EpisodeFiles(12)));

        assertTrue(fixture.service.activateNextCandidate(fixture.subscription));

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, covering.getState(), "已知覆盖待看集的低分候选接管主源");
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, high.getState(), "高分但覆盖未知的候选不被先试");
    }

    @Test
    void activateKeepsScoreOrderWithoutWatchProgress() {
        // 无播放记录(进度未知):待看集信号零侵入,高分候选先试先挂
        Fixture fixture = new Fixture();
        MediaSubscriptionResource high = new MediaSubscriptionResource();
        high.setId(61);
        high.setSubscriptionId(1);
        high.setLink("https://pan.quark.cn/s/high");
        high.setTitle("测试剧 4K 全集");
        high.setType(5);
        high.setScore(120);
        high.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        MediaSubscriptionResource covering = new MediaSubscriptionResource();
        covering.setId(62);
        covering.setSubscriptionId(1);
        covering.setLink("https://pan.baidu.com/s/next5");
        covering.setTitle("测试剧 (2025) 4K 全集");
        covering.setType(10);
        covering.setScore(100);
        covering.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(high, covering));
        RowStore store = new RowStore();
        store.install(fixture);
        store.addEpisodeAndRow(62, 4, MediaSubscriptionEpisodeSource.STATE_LISTED);
        store.addEpisodeAndRow(62, 5, MediaSubscriptionEpisodeSource.STATE_LISTED);
        Share mount = new Share();
        mount.setId(66);
        Mockito.when(fixture.shareRepository.findByPath("/追剧/1-测试剧"))
                .thenReturn(null).thenReturn(mount).thenReturn(mount);
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files(s01EpisodeFiles(12)));

        assertTrue(fixture.service.activateNextCandidate(fixture.subscription));

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, high.getState(), "进度未知:高分候选先试先挂");
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, covering.getState(), "低分候选维持原位");
    }

    @Test
    void belongsToShowFlagsEpisodeRangeOverflow() {
        // 已挂资源归属复核:标题无年份(标题/年份门禁放行形态),集号超出官方总集数即判异剧
        Fixture fixture = new Fixture();
        fixture.subscription.setName("仙剑奇侠传三"); // 与资源同名:标题门禁放行,靠集号区分
        fixture.subscription.setOfficialTotal(26);
        fixture.subscription.setOfficialEpisodes(26);
        MediaSubscriptionResource liveAction = new MediaSubscriptionResource();
        liveAction.setId(51);
        liveAction.setTitle("仙剑奇侠传三 2160P");
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(
                        Mockito.eq(51), Mockito.anyCollection()))
                .thenReturn(new ArrayList<>(episodeRange(1, 37)));
        assertFalse(fixture.service.belongsToShow(fixture.subscription, liveAction),
                "集号 1-37 > 官方 26:异剧(触发主源换源/线路卸载)");

        MediaSubscriptionResource right = new MediaSubscriptionResource();
        right.setId(52);
        right.setTitle("仙剑奇侠传三 2160P");
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(
                        Mockito.eq(52), Mockito.anyCollection()))
                .thenReturn(new ArrayList<>(episodeRange(1, 26)));
        assertTrue(fixture.service.belongsToShow(fixture.subscription, right),
                "集号在官方范围内:标题/年份门禁照旧放行");
    }

    @Test
    void reopenEndedReopensWhenPrimaryIsAlien() {
        // ENDED 自愈:真人版把集数撑到 37 反向堵死重开条件(官方 26 ≤ 本地 37),
        // 主源集号超范围 = 误挂异剧 → 重开 ACTIVE 走完整巡检换源
        Fixture fixture = new Fixture();
        fixture.subscription.setName("仙剑奇侠传三");
        fixture.subscription.setStatus(MediaSubscription.STATUS_ENDED);
        fixture.subscription.setOfficialTotal(26);
        fixture.subscription.setOfficialEpisodes(26);
        Mockito.when(fixture.episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(
                        Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(new ArrayList<>(episodeRange(1, 37))); // 本地 37 集(含真人版行)
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(51);
        primary.setTitle("仙剑奇侠传三 2160P");
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(primary));
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(
                        Mockito.eq(51), Mockito.anyCollection()))
                .thenReturn(new ArrayList<>(episodeRange(1, 37)));

        assertTrue(fixture.service.reopenEnded(fixture.subscription), "异剧污染的 ENDED 订阅须能重开");
        assertEquals(MediaSubscription.STATUS_ACTIVE, fixture.subscription.getStatus());
        ArgumentCaptor<MediaSubscriptionEvent> captor = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository).save(captor.capture());
        assertEquals(MediaSubscriptionEvent.TYPE_RESUMED, captor.getValue().getType());

        // 对照:主源正常(1-26 在官方范围)且官方集数未上调 → 保持 ENDED
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(
                        Mockito.eq(51), Mockito.anyCollection()))
                .thenReturn(new ArrayList<>(episodeRange(1, 26)));
        fixture.subscription.setStatus(MediaSubscription.STATUS_ENDED);
        assertFalse(fixture.service.reopenEnded(fixture.subscription), "正常完结:不重开");
    }

    // ---------- 手动钉选主源(2026-08-27,借鉴追更助手 exportManual:用户指定压过自动判定)----------
    // 钉选 = 换源候选序置顶 + 主源归属复核豁免(误挂异剧不再自动换走);失效换源不受影响,
    // 钉选行保留,恢复可用后优先回归;每订阅一个钉选位,钉新清旧。

    @Test
    void shouldReplacePrimaryForms() {
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        assertFalse(MediaSubscriptionCheckService.shouldReplacePrimary(primary, true, false),
                "归属正常:不换");
        assertTrue(MediaSubscriptionCheckService.shouldReplacePrimary(primary, false, false),
                "误挂异剧:换源");
        primary.setPinned(true);
        assertFalse(MediaSubscriptionCheckService.shouldReplacePrimary(primary, false, false),
                "钉选豁免归属复核:用户否决自动判定");
        assertTrue(MediaSubscriptionCheckService.shouldReplacePrimary(primary, true, true),
                "空壳主源(列不出本季文件)不豁免:挂不上内容的钉选没有意义");
        assertTrue(MediaSubscriptionCheckService.shouldReplacePrimary(primary, false, true),
                "空壳 + 异剧:必换");
    }

    @Test
    void activateTopsPinnedCandidateRegardlessOfScore() {
        // 钉选候选(分数 100)置顶于高分候选(120)之前接管主源;观看进度未知也不影响钉选层
        Fixture fixture = new Fixture();
        MediaSubscriptionResource high = new MediaSubscriptionResource();
        high.setId(61);
        high.setSubscriptionId(1);
        high.setLink("https://pan.quark.cn/s/high");
        high.setTitle("测试剧 4K 全集");
        high.setType(5);
        high.setScore(120);
        high.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        MediaSubscriptionResource pinned = new MediaSubscriptionResource();
        pinned.setId(62);
        pinned.setSubscriptionId(1);
        pinned.setLink("https://pan.baidu.com/s/pinned");
        pinned.setTitle("测试剧 (2025) 4K 全集");
        pinned.setType(10);
        pinned.setScore(100);
        pinned.setPinned(true);
        pinned.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(high, pinned));
        RowStore store = new RowStore();
        store.install(fixture);
        Share mount = new Share();
        mount.setId(66);
        Mockito.when(fixture.shareRepository.findByPath("/追剧/1-测试剧"))
                .thenReturn(null).thenReturn(mount).thenReturn(mount);
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files(s01EpisodeFiles(12)));

        assertTrue(fixture.service.activateNextCandidate(fixture.subscription));

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, pinned.getState(), "钉选候选压过分数序接管主源");
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, high.getState(), "高分候选不被先试");
    }

    @Test
    void reopenEndedKeepsPinnedAlienPrimary() {
        // ENDED 异剧重开路径的钉选豁免:用户钉住的"异剧"主源保持 ENDED,不被重开换源
        Fixture fixture = new Fixture();
        fixture.subscription.setName("仙剑奇侠传三");
        fixture.subscription.setStatus(MediaSubscription.STATUS_ENDED);
        fixture.subscription.setOfficialTotal(26);
        fixture.subscription.setOfficialEpisodes(26);
        Mockito.when(fixture.episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(
                        Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(new ArrayList<>(episodeRange(1, 37)));
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(51);
        primary.setTitle("仙剑奇侠传三 2160P");
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        primary.setPinned(true);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(primary));
        Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(
                        Mockito.eq(51), Mockito.anyCollection()))
                .thenReturn(new ArrayList<>(episodeRange(1, 37)));

        assertFalse(fixture.service.reopenEnded(fixture.subscription), "钉选主源:异剧重开豁免");
        assertEquals(MediaSubscription.STATUS_ENDED, fixture.subscription.getStatus(), "保持 ENDED");
        Mockito.verify(fixture.eventRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void applyPinClearsOtherPinsAndUnpinRestores() {
        // 钉选位唯一:applyPin 目标置位、其余清除(只写有变化的行);unpinAsync 清标记并发事件
        Fixture fixture = new Fixture();
        MediaSubscriptionResource stale = new MediaSubscriptionResource();
        stale.setId(71);
        stale.setSubscriptionId(1);
        stale.setPinned(true);
        MediaSubscriptionResource target = new MediaSubscriptionResource();
        target.setId(72);
        target.setSubscriptionId(1);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1))
                .thenReturn(List.of(stale, target));
        Mockito.when(fixture.resourceRepository.findById(72)).thenReturn(Optional.of(target));

        fixture.service.applyPin(1, 72);

        assertTrue(Boolean.TRUE.equals(target.getPinned()), "目标行钉选置位");
        assertFalse(Boolean.TRUE.equals(stale.getPinned()), "旧钉选位清除");
        assertEquals(2, Mockito.mockingDetails(fixture.resourceRepository).getInvocations().stream()
                        .filter(i -> "save".equals(i.getMethod().getName())).count(), "两行各保存一次");

        fixture.service.unpinAsync(0, 1, 72);

        assertFalse(Boolean.TRUE.equals(target.getPinned()), "取消钉选清除标记");
        ArgumentCaptor<MediaSubscriptionEvent> events = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository).save(events.capture());
        assertEquals(MediaSubscriptionEvent.TYPE_PINNED, events.getValue().getType());
        assertTrue(String.valueOf(events.getValue().getDetail()).contains("取消钉选"));
    }

    // ---------- 线上事故回归:短中文名被前缀异剧冒领 ----------
    // 订阅《醒来》(2026,两字剧名):标题门禁用归一化包含,「醒来就成了千古一帝」(同名
    // 短剧)包含「醒来」直接放行,补缺挂载冒领 16/18/19 集位;fuzzy 兜底要求名长 ≥3,
    // 短名被包含匹配放行后没有第二道防线。包含命中必须整词/白名单粘连。

    @Test
    void matchesTitleShortNameRejectsLongerPrefixTitle() {
        List<String> names = MediaSubscriptionCheckService.matchNames("醒来", "醒来", null);
        assertFalse(MediaSubscriptionCheckService.matchesTitle(names, "醒来就成了千古一帝"));
        assertFalse(MediaSubscriptionCheckService.matchesTitle(names, "醒来就成了千古一帝 全86集"));
        assertFalse(MediaSubscriptionCheckService.matchesTitle(names, "短剧 醒来就成了千古一帝"));
        // ≤4 字前缀异剧(悬案⊂悬案解码)不归标题门禁管,留给年份门禁 —— 阈值是刻意的
        List<String> xuan = MediaSubscriptionCheckService.matchNames("悬案", "悬案", null);
        assertTrue(MediaSubscriptionCheckService.matchesTitle(xuan, "悬案解码 第一季 Dept. Q (2025)"));
    }

    @Test
    void matchesTitleShortNameKeepsWordBoundaryAndGlueVariants() {
        List<String> names = MediaSubscriptionCheckService.matchNames("醒来", "醒来", null);
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "醒来 更新至14集 4K"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "【4K】醒来(真彩) 第13集"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "醒来4 全22集"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "醒来2026 全集 夸克"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "醒来全集"));
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "醒来第2季 1080P"));
        List<String> xuan = MediaSubscriptionCheckService.matchNames("悬案", "悬案", null);
        assertTrue(MediaSubscriptionCheckService.matchesTitle(xuan, "悬案 4K 高码率 更17集"),
                "高码率类 3-4 字装饰词不得误杀(悬案线上形态)");
    }

    // ---------- 手动移除资源:误挂异剧源此前没有任何移除入口(「删不掉」) ----------

    @Test
    void removeResourceUnmountsAuxAndDeletesRows() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource aux = new MediaSubscriptionResource();
        aux.setId(81);
        aux.setSubscriptionId(1);
        aux.setTitle("醒来就成了千古一帝");
        aux.setState(MediaSubscriptionResource.STATE_MOUNTED);
        aux.setMountPath("/追剧/.sources/x");
        aux.setShareId(9);
        Mockito.when(fixture.resourceRepository.findById(81)).thenReturn(Optional.of(aux));
        Mockito.when(fixture.subscriptionRepository.existsByShareIdAndIdNot(9, 1)).thenReturn(false);
        Mockito.when(fixture.resourceRepository.existsByShareIdAndSubscriptionIdNot(9, 1)).thenReturn(false);

        fixture.service.removeResource(0, 1, 81);

        Mockito.verify(fixture.shareService).deleteShare(9); // 先 AList 侧卸载
        Mockito.verify(fixture.episodeSourceRepository).deleteByResourceId(81); // 再清本地集源行
        assertEquals(MediaSubscriptionResource.STATE_REMOVED, aux.getState(), "墓碑态防重新入池");
        assertNull(aux.getShareId());
        assertNull(aux.getMountPath());
        assertFalse(Boolean.TRUE.equals(aux.getPinned()));
    }

    @Test
    void removeResourceRejectsPrimary() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(82);
        primary.setSubscriptionId(1);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        Mockito.when(fixture.resourceRepository.findById(82)).thenReturn(Optional.of(primary));

        assertThrows(cn.har01d.alist_tvbox.exception.BadRequestException.class,
                () -> fixture.service.removeResource(0, 1, 82), "主源移除会掏空订阅主路径,必须拒绝");
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, primary.getState());
    }

    @Test
    void restoreResourceReturnsTombstoneToCandidate() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource tomb = new MediaSubscriptionResource();
        tomb.setId(83);
        tomb.setSubscriptionId(1);
        tomb.setState(MediaSubscriptionResource.STATE_REMOVED);
        Mockito.when(fixture.resourceRepository.findById(83)).thenReturn(Optional.of(tomb));

        fixture.service.restoreResource(0, 1, 83);

        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, tomb.getState());
    }

    private static Set<Integer> episodeRange(int from, int to) {
        Set<Integer> numbers = new TreeSet<>();
        for (int i = from; i <= to; i++) {
            numbers.add(i);
        }
        return numbers;
    }

    private static TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> episodeFiles(int from, int to) {
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files = new TreeMap<>();
        for (int i = from; i <= to; i++) {
            files.put(i, episodeFile(i));
        }
        return files;
    }

    private static MediaSubscriptionCheckService.EpisodeFile episodeFile(int number) {
        return new MediaSubscriptionCheckService.EpisodeFile(number, "", String.format("第%02d集.mkv", number), 1, 0);
    }

    private static MediaSubscriptionCheckService.EpisodeFile episodeFile(int number, long durationSeconds) {
        return new MediaSubscriptionCheckService.EpisodeFile(number, "", String.format("第%02d集.mkv", number), 1,
                durationSeconds);
    }

    private static String[] s01EpisodeFiles(int count) {
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = String.format("测试剧.S01E%02d.4K.mkv", i + 1);
        }
        return names;
    }

    // ---------- 播放后前瞻验证:连播前提前发现死集并自动补源 ----------

    @Test
    void preheatAheadVerifiesUpcomingEpisodes() {
        Fixture fixture = new Fixture();
        RowStore store = new RowStore();
        installMountedResource(fixture, store);
        AtomicInteger probed = new AtomicInteger();
        fixture.service.setStreamProbeClient((url, userAgent, maxBytes, timeoutSeconds) -> {
            probed.incrementAndGet();
            return new StreamProbeClient.ProbeResult(206, "video/mp4", new byte[]{0x1A, 0x45});
        });
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString())).thenReturn(rawUrlDetail());
        for (int number = 4; number <= 6; number++) {
            store.addEpisodeAndRow(7, number, MediaSubscriptionEpisodeSource.STATE_LISTED);
        }

        fixture.service.preheatAhead(fixture.subscription, 3);

        assertEquals(3, probed.get(), "后面 3 集各探测最优行一次");
        for (MediaSubscriptionEpisodeSource row : store.rows.values()) {
            assertEquals(MediaSubscriptionEpisodeSource.STATE_VERIFIED, row.getState());
            assertEquals(1, row.getSuccessCount());
            assertNotNull(row.getLastVerifiedTime());
        }
        Mockito.verifyNoInteractions(fixture.eventRepository);
    }

    @Test
    void preheatAheadFailedRowTriggersRescue() {
        Fixture fixture = new Fixture();
        RowStore store = new RowStore();
        installMountedResource(fixture, store);
        fixture.service.setStreamProbeClient((url, userAgent, maxBytes, timeoutSeconds) ->
                new StreamProbeClient.ProbeResult(200, "text/html", "<html>登录</html>".getBytes()));
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString())).thenReturn(rawUrlDetail());
        store.addEpisodeAndRow(7, 4, MediaSubscriptionEpisodeSource.STATE_LISTED);

        fixture.service.preheatAhead(fixture.subscription, 3);

        // 假页判死 → 资源再无其他 LIVE 集,传染判整源死 → 第 4 集无候选 → 自动补源事件
        assertEquals(MediaSubscriptionEpisodeSource.STATE_FAILED, store.rows.values().iterator().next().getState());
        ArgumentCaptor<MediaSubscriptionEvent> events = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository, Mockito.atLeastOnce()).save(events.capture());
        assertTrue(events.getAllValues().stream().anyMatch(e -> e.getDetail().contains("已自动补源")),
                "应写自动补源事件,实际:" + events.getAllValues());
    }

    @Test
    void preheatAheadThrottledWithinWindow() throws InterruptedException {
        Fixture fixture = new Fixture();
        RowStore store = new RowStore();
        installMountedResource(fixture, store);
        AtomicInteger probed = new AtomicInteger();
        fixture.service.setStreamProbeClient((url, userAgent, maxBytes, timeoutSeconds) -> {
            probed.incrementAndGet();
            return new StreamProbeClient.ProbeResult(206, "video/mp4", new byte[]{0x1A, 0x45});
        });
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString())).thenReturn(rawUrlDetail());
        store.addEpisodeAndRow(7, 4, MediaSubscriptionEpisodeSource.STATE_LISTED);

        fixture.service.preheatAheadAsync(0, 1, 3);
        for (int i = 0; i < 100 && probed.get() == 0; i++) {
            Thread.sleep(50);
        }
        assertEquals(1, probed.get(), "首次触发应完成一次探测");

        fixture.service.preheatAheadAsync(0, 1, 3); // 限频窗口(默认 1h)内:不再探测
        Thread.sleep(500);
        assertEquals(1, probed.get(), "窗口内二次触发不得重复探测");
    }

    @Test
    void preheatAheadSkipsUnairedEpisodes() {
        Fixture fixture = new Fixture();
        AtomicInteger probed = new AtomicInteger();
        fixture.service.setStreamProbeClient((url, userAgent, maxBytes, timeoutSeconds) -> {
            probed.incrementAndGet();
            return new StreamProbeClient.ProbeResult(206, "video/mp4", new byte[]{0x1A, 0x45});
        });
        Mockito.when(fixture.episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(List.of(1, 2, 3)); // 已追平:后面没有已上架集

        fixture.service.preheatAhead(fixture.subscription, 3);

        assertEquals(0, probed.get(), "未上架的集无行可探");
        Mockito.verifyNoInteractions(fixture.eventRepository);
    }

    @Test
    void preheatAheadMissingEpisodeInWindowTriggersRescue() {
        Fixture fixture = new Fixture();
        RowStore store = new RowStore();
        installMountedResource(fixture, store);
        AtomicInteger probed = new AtomicInteger();
        fixture.service.setStreamProbeClient((url, userAgent, maxBytes, timeoutSeconds) -> {
            probed.incrementAndGet();
            return new StreamProbeClient.ProbeResult(206, "video/mp4", new byte[]{0x1A, 0x45});
        });
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString())).thenReturn(rawUrlDetail());
        // 第 4、6 集有行,第 5 集从未上架(线上:海贼王 837 集无任何源)—— 探测循环看不见它
        store.addEpisodeAndRow(7, 4, MediaSubscriptionEpisodeSource.STATE_LISTED);
        store.addEpisodeAndRow(7, 6, MediaSubscriptionEpisodeSource.STATE_LISTED);

        fixture.service.preheatAhead(fixture.subscription, 3);

        assertEquals(2, probed.get(), "只探测已有行的 4、6 两集");
        ArgumentCaptor<MediaSubscriptionEvent> events = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository, Mockito.atLeastOnce()).save(events.capture());
        MediaSubscriptionEvent event = events.getAllValues().stream()
                .filter(e -> e.getDetail().contains("缺集"))
                .findFirst().orElseThrow(() -> new AssertionError("应写缺集补源事件,实际:" + events.getAllValues()));
        assertEquals(MediaSubscriptionEvent.TYPE_GAP_FILLED, event.getType());
        assertTrue(event.getDetail().contains("第5 集缺集"), event.getDetail());
        // 播放上下文自愈不外发通知:notificationService 未注入,事件只落时间线(push=false 由实现保证)
    }

    @Test
    void preheatAheadMissingRescueHonoursCooldown() {
        Fixture fixture = new Fixture();
        RowStore store = new RowStore();
        installMountedResource(fixture, store);
        fixture.service.setStreamProbeClient((url, userAgent, maxBytes, timeoutSeconds) ->
                new StreamProbeClient.ProbeResult(206, "video/mp4", new byte[]{0x1A, 0x45}));
        Mockito.when(fixture.aListService.getFile(Mockito.any(), Mockito.anyString())).thenReturn(rawUrlDetail());
        store.addEpisodeAndRow(7, 4, MediaSubscriptionEpisodeSource.STATE_LISTED);
        store.addEpisodeAndRow(7, 6, MediaSubscriptionEpisodeSource.STATE_LISTED);

        fixture.service.preheatAhead(fixture.subscription, 3);
        fixture.service.preheatAhead(fixture.subscription, 3); // 2h 冷却内:洞还在也不重复触发

        // 只数缺集事件(首次 submitCheck 的后台巡检可能并发写别的事件,不参与断言)
        ArgumentCaptor<MediaSubscriptionEvent> events = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository, Mockito.atLeastOnce()).save(events.capture());
        assertEquals(1, events.getAllValues().stream().filter(e -> e.getDetail().contains("缺集")).count(),
                "冷却窗口内缺集事件只写一次");
    }

    /** 挂载资源 + RowStore 内存库 + playCandidates 依赖的 findBySubscriptionAndNumber 派生查询。 */
    private static MediaSubscriptionResource installMountedResource(Fixture fixture, RowStore store) {
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(7);
        resource.setState(MediaSubscriptionResource.STATE_MOUNTED);
        resource.setMountPath("/追剧/1-测试剧");
        resource.setScore(100);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(resource));
        store.install(fixture);
        Mockito.when(fixture.episodeSourceRepository.findBySubscriptionAndNumber(Mockito.eq(1), Mockito.anyInt()))
                .thenAnswer(inv -> store.rows.values().stream()
                        .filter(r -> store.episodes.values().stream()
                                .anyMatch(e -> e.getId() == r.getEpisodeId() && e.getNumber() == (int) inv.getArgument(1)))
                        .toList());
        return resource;
    }

    private static cn.har01d.alist_tvbox.model.FsDetail rawUrlDetail() {
        cn.har01d.alist_tvbox.model.FsDetail detail = new cn.har01d.alist_tvbox.model.FsDetail();
        detail.setRawUrl("https://dl.quark.cn/a.mp4");
        return detail;
    }

    /** 失效确认/集源行分支的 mock 夹具:订阅已挂主源(shareId=5),仓储行为由各测试定制。 */
    /** 集源行内存库:save 落 Map,派生查询等价真实库 —— 供整轮 check/activate 的流程测试用。 */
    private static final class RowStore {
        final Map<Integer, MediaSubscriptionEpisode> episodes = new HashMap<>();
        final Map<Integer, MediaSubscriptionEpisodeSource> rows = new LinkedHashMap<>();
        private final AtomicInteger episodeIds = new AtomicInteger(100);
        private final AtomicInteger rowIds = new AtomicInteger(200);

        void install(Fixture fixture) {
            Mockito.when(fixture.episodeRepository.findBySubscriptionIdOrderByNumber(1)).thenAnswer(inv ->
                    episodes.values().stream().sorted(Comparator.comparing(MediaSubscriptionEpisode::getNumber)).toList());
            Mockito.when(fixture.episodeRepository.findBySubscriptionIdAndSeasonAndNumber(Mockito.eq(1), Mockito.anyInt(), Mockito.anyInt()))
                    .thenAnswer(inv -> Optional.ofNullable(episodes.get(inv.getArgument(2))));
            Mockito.when(fixture.episodeRepository.save(Mockito.any())).thenAnswer(inv -> {
                MediaSubscriptionEpisode episode = inv.getArgument(0);
                if (episode.getId() == null) {
                    episode.setId(episodeIds.incrementAndGet());
                }
                episodes.put(episode.getNumber(), episode);
                return episode;
            });
            Mockito.when(fixture.episodeSourceRepository.save(Mockito.any())).thenAnswer(inv -> {
                MediaSubscriptionEpisodeSource row = inv.getArgument(0);
                if (row.getId() == null) {
                    row.setId(rowIds.incrementAndGet());
                }
                rows.put(row.getId(), row);
                return row;
            });
            Mockito.when(fixture.episodeSourceRepository.findByResourceId(Mockito.anyInt())).thenAnswer(inv ->
                    rows.values().stream().filter(r -> r.getResourceId() == (int) inv.getArgument(0)).toList());
            Mockito.when(fixture.episodeSourceRepository.findByEpisodeIdAndResourceId(Mockito.anyInt(), Mockito.anyInt()))
                    .thenAnswer(inv -> rows.values().stream().filter(r -> r.getEpisodeId() == (int) inv.getArgument(0)
                            && r.getResourceId() == (int) inv.getArgument(1)).findFirst());
            Mockito.when(fixture.episodeSourceRepository.countByResourceId(Mockito.anyInt())).thenAnswer(inv ->
                    rows.values().stream().filter(r -> r.getResourceId() == (int) inv.getArgument(0)).count());
            Mockito.when(fixture.episodeSourceRepository.findNumbersByResourceIdAndStatesIn(Mockito.anyInt(), Mockito.anyCollection()))
                    .thenAnswer(inv -> numbers(rows, episodes, r -> r.getResourceId() == (int) inv.getArgument(0),
                            (Collection<String>) inv.getArgument(1)));
            Mockito.when(fixture.episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.anyInt(), Mockito.anyCollection()))
                    .thenAnswer(inv -> numbers(rows, episodes, r -> true, (Collection<String>) inv.getArgument(1))
                            .stream().distinct().toList());
        }

        void addEpisodeAndRow(int resourceId, int number, String state) {
            MediaSubscriptionEpisode ep = episode(episodeIds.incrementAndGet(), number);
            episodes.put(number, ep);
            MediaSubscriptionEpisodeSource row = sourceRow(rowIds.incrementAndGet(), ep.getId(), resourceId, state,
                    String.format("第%02d集.mkv", number));
            rows.put(row.getId(), row);
        }

        List<Integer> episodeNumbers(int resourceId) {
            return rows.values().stream()
                    .filter(r -> r.getResourceId() == resourceId)
                    .map(r -> episodes.values().stream()
                            .filter(e -> e.getId().equals(r.getEpisodeId()))
                            .findFirst().map(MediaSubscriptionEpisode::getNumber).orElse(null))
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
        }
    }

    // ---------- 删除与巡检并发(线上 #40):创建即触发首轮巡检,删除后必须中止 ----------
    // 不复活订阅(实体无 @Version,detached save 会 INSERT 整行)、不再搜索挂载,
    // 并回收本轮已写入的资源行/挂载 share(清理豁免的常驻挂载,不回收就永久顶在 AList 目录里)

    @Test
    void stopIfDeletedCleansOrphanResourcesAndMounts() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource mounted = new MediaSubscriptionResource();
        mounted.setId(11);
        mounted.setShareId(50);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(mounted));
        fixture.service.onDeleted(1);
        assertTrue(fixture.service.stopIfDeleted(1), "已删除订阅应命中中止");
        Mockito.verify(fixture.shareService).deleteShare(50); // 本轮巡检挂的 share 随行回收
        Mockito.verify(fixture.episodeSourceRepository).deleteByResourceIdIn(List.of(11));
        Mockito.verify(fixture.episodeRepository).deleteBySubscriptionId(1);
        Mockito.verify(fixture.resourceRepository).deleteBySubscriptionId(1);
        Mockito.verify(fixture.eventRepository).deleteBySubscriptionId(1);
    }

    @Test
    void stopIfDeletedKeepsSilentForLiveSubscription() {
        Fixture fixture = new Fixture();
        fixture.service.onDeleted(2); // 删的是别的订阅
        assertFalse(fixture.service.stopIfDeleted(1), "存活订阅不触发清理");
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(Mockito.anyInt());
        Mockito.verify(fixture.resourceRepository, Mockito.never()).deleteBySubscriptionId(Mockito.anyInt());
    }

    @Test
    void checkAbortsWhenSubscriptionDeletedMidFlight() {
        Fixture fixture = new Fixture();
        fixture.service.onDeleted(1); // check() 取到实体之后、doCheck 执行之前订阅被删
        fixture.service.check(1);
        Mockito.verify(fixture.subscriptionRepository, Mockito.never()).save(Mockito.any()); // 整行不复活
        Mockito.verify(fixture.shareService, Mockito.never()).add(Mockito.any()); // 不再挂载
        Mockito.verify(fixture.aListService, Mockito.never()).listFiles(Mockito.any(), Mockito.anyString(),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()); // 不再列目录搜索
    }

    // ---------- ENDED 订阅播放失败自愈:完结≠看完,分享失效须能重开完整巡检 ----------

    @Test
    void endedSubscriptionSkipsFullCheckWithoutPlaybackFailure() {
        // 完结且无播放失败信号:维持轻查短路(不列目录不搜索),每周再查(100+ 规模:闲置完结剧不花日查开销)
        Fixture fixture = new Fixture();
        fixture.subscription.setStatus(MediaSubscription.STATUS_ENDED);
        long now = System.currentTimeMillis();
        fixture.service.check(1);
        Mockito.verify(fixture.aListService, Mockito.never()).listFiles(Mockito.any(), Mockito.anyString(),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()); // 完整巡检未跑
        assertEquals(MediaSubscription.STATUS_ENDED, fixture.subscription.getStatus());
        assertClose(now + 7 * 24 * 3600_000L, fixture.subscription.getNextCheckTime());
    }

    @Test
    void playbackFailureReopensEndedSubscriptionForFullCheck() {
        // 播放全源失败:轻查只看集数发现不了可播性问题,须越过短路回 ACTIVE 走完整巡检
        Fixture fixture = new Fixture();
        fixture.subscription.setStatus(MediaSubscription.STATUS_ENDED);
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(11);
        primary.setSubscriptionId(1);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(primary));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/追剧/1-测试剧"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new RuntimeException("share not found"));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(new FsResponse());
        fixture.service.markPlaybackFailure(1);

        fixture.service.check(1);

        assertNotNull(fixture.subscription.getLastCheckTime(), "完整巡检 doCheck 已执行");
        assertNotEquals(MediaSubscription.STATUS_ENDED, fixture.subscription.getStatus(), "不能再停在 ENDED 轻查路径");
        ArgumentCaptor<MediaSubscriptionEvent> captor = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        Mockito.verify(fixture.eventRepository, Mockito.atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(e -> MediaSubscriptionEvent.TYPE_RESUMED.equals(e.getType())),
                "播放失败重开须留 RESUMED 事件");
    }

    @Test
    void endedStillWatchingRunsFullCheck() {
        // 完结≠看完:7 天内有播放且未看完(看了 3/12 集)→ 保持 ENDED 跑完整巡检,资源失效能被发现
        Fixture fixture = new Fixture();
        fixture.subscription.setStatus(MediaSubscription.STATUS_ENDED);
        fixture.subscription.setUid(1);
        Mockito.when(fixture.episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(
                        Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(new ArrayList<>(episodeRange(1, 12)));
        Mockito.when(fixture.historyRepository.findByUidAndVodId(Mockito.eq(1), Mockito.anyString()))
                .thenReturn(List.of(playHistory("msubep-1-3", System.currentTimeMillis())));
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(11);
        primary.setSubscriptionId(1);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/1-测试剧");
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(primary));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/追剧/1-测试剧"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new RuntimeException("share not found"));

        fixture.service.check(1);

        assertNotNull(fixture.subscription.getLastCheckTime(), "仍在追看的完结剧须跑完整巡检");
        Mockito.verify(fixture.aListService, Mockito.atLeastOnce()).listFiles(Mockito.any(), Mockito.eq("/追剧/1-测试剧"),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean());
    }

    @Test
    void endedStaleOrWatchedThroughSkipsFullCheck() {
        Fixture fixture = new Fixture();
        fixture.subscription.setStatus(MediaSubscription.STATUS_ENDED);
        fixture.subscription.setUid(1);
        Mockito.when(fixture.episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(
                        Mockito.eq(1), Mockito.anyCollection()))
                .thenReturn(new ArrayList<>(episodeRange(1, 12)));
        long now = System.currentTimeMillis();

        // 看完(12/12):即使昨天还在播也不必维护资源
        Mockito.when(fixture.historyRepository.findByUidAndVodId(Mockito.eq(1), Mockito.anyString()))
                .thenReturn(List.of(playHistory("msubep-1-12", now)));
        fixture.service.check(1);
        assertNull(fixture.subscription.getLastCheckTime(), "已看完的完结剧维持轻查短路");

        // 没看完(3/12)但 30 天没播:闲置完结剧不花巡检开销
        Mockito.when(fixture.historyRepository.findByUidAndVodId(Mockito.eq(1), Mockito.anyString()))
                .thenReturn(List.of(playHistory("msubep-1-3", now - 30L * 24 * 3600_000)));
        fixture.service.check(1);
        assertNull(fixture.subscription.getLastCheckTime(), "越窗未再看的完结剧维持轻查短路");
        Mockito.verify(fixture.aListService, Mockito.never()).listFiles(Mockito.any(), Mockito.anyString(),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean());
    }

    private static cn.har01d.alist_tvbox.entity.History playHistory(String episodeUrl, long updatedAt) {
        cn.har01d.alist_tvbox.entity.History history = new cn.har01d.alist_tvbox.entity.History();
        history.setEpisodeUrl(episodeUrl);
        history.setUpdatedAt(updatedAt);
        // 进度感知口径:播放行带足进度(completed 形态 position 夹紧 duration),裸 0/0 会被折算成前一集
        history.setPosition(45 * 60_000L);
        history.setDuration(45 * 60_000L);
        return history;
    }

    // ---------- 巡检联动转存:设计口径「发现新集后 copy」——新建订阅首轮巡检挂载完即转,
    // 不再空等每小时 :40 的自愈 sweep(线上:订阅建完 3 分钟用户查看,「根本没有转存」) ----------

    @Test
    void transferModeSubscriptionQueuesTransferAfterCheck() {
        Fixture fixture = new Fixture();
        fixture.subscription.setUid(7);
        fixture.subscription.setMode(MediaSubscription.MODE_TRANSFER);
        fixture.service.check(1);
        Mockito.verify(fixture.transferService).transferAsync(7, 1);
    }

    @Test
    void nonTransferModeSubscriptionDoesNotQueueTransfer() {
        Fixture fixture = new Fixture();
        fixture.subscription.setUid(7);
        fixture.service.check(1);
        Mockito.verify(fixture.transferService, Mockito.never()).transferAsync(Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    void joinNumbersCompressesConsecutiveRuns() {
        // 千集动漫整源覆盖:动态文案逐集列出会长到没法看,连续段(≥3)压成区间
        assertEquals("1-36", MediaSubscriptionCheckService.joinNumbers(IntStream.rangeClosed(1, 36).boxed().toList()));
        assertEquals("1-1000", MediaSubscriptionCheckService.joinNumbers(IntStream.rangeClosed(1, 1000).boxed().toList()));
        // 稀疏覆盖:只压连续段,散点保持逗号;乱序输入先排再去重
        assertEquals("4,8,11-15,19,21,29,31,33-36", MediaSubscriptionCheckService.joinNumbers(
                new ArrayList<>(List.of(36, 8, 11, 12, 13, 14, 15, 4, 19, 21, 29, 31, 33, 34, 35))));
        // 两集连续不压(等长,保持原样),单集/空集照旧
        assertEquals("1,2", MediaSubscriptionCheckService.joinNumbers(List.of(1, 2)));
        assertEquals("5", MediaSubscriptionCheckService.joinNumbers(List.of(5)));
        assertEquals("", MediaSubscriptionCheckService.joinNumbers(List.of()));
    }

    // ---------- 年番全剧连续编号(线上:沧元图 S3 订阅,分享按全剧 67-87 组织而订阅按季内 1-23 找,
    // 正片集号全被超界剔除,番外篇 27-30 反倒冒充正片入库;主源还被「片头尾」目录的 OP/ED 片段冒领) ----------

    private MediaSubscription cangYuanTu() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(1);
        subscription.setName("沧元图 第三季");
        subscription.setKeyword("沧元图 第三季");
        subscription.setSeason(3);
        subscription.setOfficialEpisodes(23);
        subscription.setOfficialTotal(50);
        return subscription;
    }

    private static TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> absoluteFiles(String dir, int from, int to) {
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files = new TreeMap<>();
        for (int i = from; i <= to; i++) {
            files.put(i, new MediaSubscriptionCheckService.EpisodeFile(i, dir, i + ".mp4", 2_500L * 1024 * 1024, 0L));
        }
        return files;
    }

    @Test
    void applySeasonStartOffsetMapsIntraSeasonToContinuousNumbering() {
        // 一念永恒形态:TMDB 单季连续总集数(全剧),网盘按「第二季/第01集」季内编号。
        // 用户声明本季第 1 集 = 全剧第 63 集:季内 1-5 → 全剧 63-67
        MediaSubscription subscription = cangYuanTu();
        subscription.setSeason(2);
        subscription.setSeasonStartEpisode(63);
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files =
                absoluteFiles("/temp/quark@nyhdt/第二季", 1, 5);
        MediaSubscriptionCheckService.applySeasonStartOffset(subscription, files);
        assertEquals(List.of(63, 64, 65, 66, 67), new ArrayList<>(files.keySet()));
        assertEquals("1.mp4", files.get(63).name(), "第 63 集应指向季内 1 号文件");

        // 缺集检测下界钳到 63:季前旧集(1-62)不算缺,缺口只在 63..base
        Set<Integer> present = Set.of(63, 64, 65, 67);
        assertEquals(Set.of(66), service.computeMissing(subscription, present));

        // 未声明(null)时行为不变:1..base 全集号空间,季前集 1/62 都算缺
        subscription.setSeasonStartEpisode(null);
        Set<Integer> plainMissing = service.computeMissing(subscription, present);
        assertTrue(plainMissing.contains(1) && plainMissing.contains(62));
        assertFalse(plainMissing.contains(63));
    }

    @Test
    void applySeasonStartOffsetNoOpWhenStartAtOne() {
        MediaSubscription subscription = cangYuanTu();
        subscription.setSeasonStartEpisode(1);
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files =
                absoluteFiles("/temp/quark@nyhdt/第二季", 3, 4);
        MediaSubscriptionCheckService.applySeasonStartOffset(subscription, files);
        assertEquals(List.of(3, 4), new ArrayList<>(files.keySet()), "起始 1 = 无偏移");
    }

    @Test
    void applyNumberingPrefersResourceLevelStart() {
        // 资源级起始集号优先:同一订阅混多套编号语义(完结季季包 vs 连续合集),
        // 完结季资源声明 153 → 裸 1-8 平移为全剧 153-160;订阅级偏移与自动重映射都不参与
        MediaSubscription subscription = cangYuanTu();
        subscription.setSeason(1);
        subscription.setOfficialTotal(200);
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setStartEpisode(153);
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files =
                absoluteFiles("/temp/quark@nyhdt/完结季", 1, 8);
        MediaSubscriptionCheckService.applyNumbering(subscription, resource, files, "一念永恒 完结季 更新至08集");
        assertEquals(List.of(153, 154, 155, 156, 157, 158, 159, 160), new ArrayList<>(files.keySet()));

        // 未声明的资源走原逻辑(此处订阅级也未设 → 不动)
        MediaSubscriptionResource plain = new MediaSubscriptionResource();
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> raw =
                absoluteFiles("/temp/quark@nyhdt/合集", 166, 168);
        MediaSubscriptionCheckService.applyNumbering(subscription, plain, raw, "一念永恒 更至168集");
        assertEquals(List.of(166, 167, 168), new ArrayList<>(raw.keySet()));
    }

        @Test
    void collectSeasonWidensForSeasonPackOfSingleSeasonMeta() {
        // 一念永恒形态:TMDB 单季(totalSeasons=1)连续编号订阅,完结季季包文件是 S04Eyy ——
        // 列目录季按资源形态放宽,否则 parseEpisode 季过滤把整包拒成「无可识别」
        var metadataService = Mockito.mock(cn.har01d.alist_tvbox.service.metadata.MetadataService.class);
        var details = new cn.har01d.alist_tvbox.dto.MetadataDetails();
        details.setTotalSeasons(1);
        Mockito.when(metadataService.details(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(details);
        MediaSubscriptionCheckService svc = new MediaSubscriptionCheckService(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, metadataService, null, null,
                new AppProperties(), new ObjectMapper(), null, null);

        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(64);
        subscription.setName("一念永恒");
        subscription.setSeason(1);
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("107371");
        MediaSubscriptionResource finale = new MediaSubscriptionResource();
        finale.setTitle("一念永恒 完结季 4K臻彩MAX [更新至08集]");
        assertNull(svc.collectSeason(subscription, finale), "完结季无季号:接受任意 SxxEyy");

        MediaSubscriptionResource declared = new MediaSubscriptionResource();
        declared.setTitle("一念永恒 第4季 2160P");
        assertNull(svc.collectSeason(subscription, declared),
                "标题声明季也放宽到 null:包内文件常标 S01Eyy(季内编号),按 4 收会整包拒收;季归属交给文件级映射");

        // 多季元数据/多季订阅:季过滤是防冒领的正确语义,不放宽
        details.setTotalSeasons(4);
        assertEquals(1, svc.collectSeason(subscription, finale));
        assertEquals(1, svc.collectSeason(subscription, declared));
        details.setTotalSeasons(1);
        subscription.setSeason(2);
        assertEquals(2, svc.collectSeason(subscription, declared));

        // 无资源上下文(共享挂载收编/转存路径):维持订阅季
        assertEquals(2, svc.collectSeason(subscription, null));
    }

    @Test
    void remapAbsoluteNumberingUsesSeasonFolderRangeStart() {
        // 「067-更新中 4K 第三季」目录自带全剧起点 067:基准 66,67-87 → 1-21
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files =
                absoluteFiles("/temp/quark@cy3/067-更新中 4K 第三季", 67, 87);
        MediaSubscriptionCheckService.remapAbsoluteNumbering(cangYuanTu(), files, "沧元图 第三季 / 沧元图年番 (2026) 更新87集");
        assertEquals(numbers(1, 21), new ArrayList<>(files.keySet()));
        assertEquals("67.mp4", files.get(1).name(), "第 1 集应指向全剧 67 号文件");
    }

    @Test
    void remapAbsoluteNumberingUsesSeasonFolderWithoutRange() {
        // 季目录无区间文本(「5）第3季 (2026)」):段内连续且块长≈已播 → 最小集号起步
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files =
                absoluteFiles("/temp/quark@cy3/【C】沧元图/4K [防失效]/5）第3季 (2026)", 70, 92);
        MediaSubscriptionCheckService.remapAbsoluteNumbering(cangYuanTu(), files, "沧元图 第三季 妖圣降临番外篇 更至 EP92集");
        assertEquals(numbers(1, 23), new ArrayList<>(files.keySet()));
    }

    @Test
    void remapAbsoluteNumberingUsesTitleClaimForFlatFiles() {
        // 无季目录的散文件:连续块终点 == 标题宣称进度 且块长≈已播 → 块尾倒推基准
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files = absoluteFiles("/temp/quark@cy3", 70, 92);
        MediaSubscriptionCheckService.remapAbsoluteNumbering(cangYuanTu(), files, "沧元图3 (2026) 更至92集 4K");
        assertEquals(numbers(1, 23), new ArrayList<>(files.keySet()));
    }

    @Test
    void remapAbsoluteNumberingSkipsPartialFlatBlock() {
        // 残缺散文件(松散 75-92,块长 8 远小于已播 23):错位风险大,宁可不重映射
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files = new TreeMap<>();
        files.putAll(absoluteFiles("/temp/quark@cy3", 75, 76));
        files.putAll(absoluteFiles("/temp/quark@cy3", 85, 92));
        MediaSubscriptionCheckService.remapAbsoluteNumbering(cangYuanTu(), files, "沧元图3 更至 EP92集");
        assertEquals(List.of(75, 76, 85, 86, 87, 88, 89, 90, 91, 92), new ArrayList<>(files.keySet()), "维持原集号语义");
    }

    @Test
    void remapAbsoluteNumberingKeepsRelativeNumberingAndLagTail() {
        // 相对编号的正片(1-23)与登记滞后尾巴(51-55 连续衔接)都不受影响
        MediaSubscription subscription = cangYuanTu();
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files = absoluteFiles("/追剧/沧元图-第三季 S03", 1, 23);
        MediaSubscriptionCheckService.remapAbsoluteNumbering(subscription, files, null);
        assertEquals(numbers(1, 23), new ArrayList<>(files.keySet()));
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> tail = absoluteFiles("/追剧/柯南 S01", 1173, 1216);
        subscription.setSeason(1); // 柯南形态:单季订阅 + 千集级登记滞后,重映射不参与
        MediaSubscriptionCheckService.remapAbsoluteNumbering(subscription, tail, null);
        assertEquals(numbers(1173, 1216), new ArrayList<>(tail.keySet()));
    }

    @Test
    void remapAbsoluteNumberingRequiresMultiSeasonAndOfficialData() {
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files = absoluteFiles("/temp/quark@cy3", 70, 92);
        MediaSubscription firstSeason = cangYuanTu();
        firstSeason.setSeason(1);
        MediaSubscriptionCheckService.remapAbsoluteNumbering(firstSeason, files, "更至92集");
        assertEquals(numbers(70, 92), new ArrayList<>(files.keySet()), "season=1 无重映射需求(绝对==相对)");
        MediaSubscription noTotal = cangYuanTu();
        noTotal.setOfficialTotal(null);
        MediaSubscriptionCheckService.remapAbsoluteNumbering(noTotal, files, "更至92集");
        assertEquals(numbers(70, 92), new ArrayList<>(files.keySet()), "官方总集数未知:无从判界,不动");
    }

    @Test
    void remapAbsoluteNumberingPrefersAnchoredOverStrays() {
        // 季目录锚定的正片优先占位,目录外散件不抢占同集位
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files =
                absoluteFiles("/temp/quark@cy3/067-更新中 4K 第三季", 67, 87);
        files.put(1, new MediaSubscriptionCheckService.EpisodeFile(1, "/temp/quark@cy3", "第01集.mkv", 500L, 0L));
        MediaSubscriptionCheckService.remapAbsoluteNumbering(cangYuanTu(), files, null);
        assertEquals("67.mp4", files.get(1).name(), "季目录锚定文件优先");
    }

    @Test
    void remapAbsoluteNumberingUsesDeepestSeasonSegmentAnchor() {
        // 挂载路径名自带季字样(「/追剧/沧元图-第三季 S03」),不得遮蔽更深的显式区间目录;
        // 且滞后分享(块长 21 vs 已播 30)在显式区间下依然可用(T1b 容差本会放弃)
        MediaSubscription subscription = cangYuanTu();
        subscription.setOfficialEpisodes(30);
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files =
                absoluteFiles("/追剧/沧元图-第三季 [bgmid-575244] S03/xxx蒼xx缘xxTUxxx/067-更新中 4K 第三季", 67, 87);
        MediaSubscriptionCheckService.remapAbsoluteNumbering(subscription, files, null);
        assertEquals(numbers(1, 21), new ArrayList<>(files.keySet()));
    }

    @Test
    void spinOffDirSkipsDerivativeFoldersForMultiSeasonSubscription() {
        assertTrue(MediaSubscriptionCheckService.spinOffDir("027-030 4K 东宁府番外篇", 3));
        assertTrue(MediaSubscriptionCheckService.spinOffDir("060-066 4K 元初山番外篇", 3));
        assertTrue(MediaSubscriptionCheckService.spinOffDir("4）前传 东宁府的夏天 (2026)", 3));
        assertFalse(MediaSubscriptionCheckService.spinOffDir("第2季&元初山番外篇 (2024-2025)", 2), "声明目标季的目录是正片本体");
        assertFalse(MediaSubscriptionCheckService.spinOffDir("番外篇", 1), "season=1 的订阅自身可能就是衍生篇目条目");
        assertFalse(MediaSubscriptionCheckService.spinOffDir("4K 高码率", 3), "无篇目标记不误伤");
        assertFalse(MediaSubscriptionCheckService.spinOffDir("067-更新中 4K 第三季", 3), "季标记目录走季门禁,不归篇目门禁管");
    }

    @Test
    void titleProgressForeignRelaxedForMultiSeasonSubscription() {
        // 年番文化:多季订阅的标题宣称是全剧进度(更至81集 = 全剧,本季官方总 50),放行给探测层
        MediaSubscription subscription = cangYuanTu();
        assertFalse(MediaSubscriptionCheckService.titleProgressForeign(subscription, "沧元图3 (2026)【更至81集】4K/HDR"));
        // 单季订阅维持原门禁:真人版全集包 81 > 50+容差 → 拒
        MediaSubscription firstSeason = cangYuanTu();
        firstSeason.setSeason(1);
        assertTrue(MediaSubscriptionCheckService.titleProgressForeign(firstSeason, "沧元图 (2026)【更至81集】4K/HDR"));
    }

    private static FsResponse folders(String... names) {
        FsResponse response = new FsResponse();
        List<FsInfo> list = new ArrayList<>();
        for (String name : names) {
            FsInfo info = new FsInfo();
            info.setName(name);
            info.setType(1);
            info.setSize(0L);
            list.add(info);
        }
        response.setFiles(list);
        return response;
    }

    @Test
    void probeShareRemapsContinuousNumberingAndSkipsSpinOffs() {
        // 线上沧元图分享实貌:根下五个目录(两季+两番外+第三季),第三季按全剧 67-87 编号
        Fixture fixture = new Fixture();
        fixture.subscription.setSeason(3);
        fixture.subscription.setOfficialEpisodes(23);
        fixture.subscription.setOfficialTotal(50);
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(9);
        resource.setSubscriptionId(1);
        resource.setLink("https://pan.quark.cn/s/cy3");
        resource.setTitle("沧元图 第三季 / 沧元图年番 (2026) 更新87集");
        resource.setType(5);
        resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Share temp = new Share();
        temp.setId(77);
        temp.setPath("/temp/quark@cy3");
        Share probe = new Share();
        probe.setType(5);
        probe.setShareId("cy3");
        Mockito.when(fixture.shareService.parseShareLink("https://pan.quark.cn/s/cy3")).thenReturn(probe);
        Mockito.when(fixture.shareRepository.findByTypeAndShareIdAndTempTrue(5, "cy3")).thenReturn(List.of(temp));
        Mockito.when(fixture.shareRepository.findByPath("/temp/quark@cy3")).thenReturn(temp);
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/temp/quark@cy3"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(folders("001-026 4K 第一季", "027-030 4K 东宁府番外篇", "031-059 4K 第二季",
                        "060-066 4K 元初山番外篇", "067-更新中 4K 第三季"));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/temp/quark@cy3/067-更新中 4K 第三季"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files(IntStream.rangeClosed(67, 87).mapToObj(i -> i + ".mp4").toArray(String[]::new)));
        Mockito.when(fixture.episodeSourceRepository.findByResourceId(9)).thenReturn(List.of());
        RowStore store = new RowStore();
        store.install(fixture);

        fixture.service.probeShare(fixture.subscription, resource);

        assertEquals(numbers(1, 21), store.episodeNumbers(9), "全剧 67-87 应重映射为季内 1-21");
        Mockito.verify(fixture.aListService, Mockito.never()).listFiles(Mockito.any(),
                Mockito.eq("/temp/quark@cy3/027-030 4K 东宁府番外篇"),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean());
    }

    @Test
    void collectEpisodeFilesSkipsOpeningEndingClips() {
        // 「片头尾/」目录装 OP/ED 片段:线上 UC 主源把 片尾2/片尾3 当成第 2、3 集
        Fixture fixture = new Fixture();
        fixture.subscription.setSeason(3);
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files = new TreeMap<>();
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/temp/uc@cy"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(folders("【C】沧元图", "片头尾"));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/temp/uc@cy/【C】沧元图"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files("70.mp4", "71.mp4"));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.eq("/temp/uc@cy/片头尾"),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files("片头.mp4", "片尾.mp4", "片尾2.mp4"));
        fixture.service.collectEpisodeFiles(new Site(), 3, "/temp/uc@cy", 1, files,
                new MediaSubscriptionCheckService.EpisodeSizePolicy(0, 0, 0), true, null);
        assertEquals(List.of(70, 71), new ArrayList<>(files.keySet()), "OP/ED 片段不得冒充剧集");
    }

    private static class Fixture {
        final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
        final MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
        final MediaSubscriptionEventRepository eventRepository = Mockito.mock(MediaSubscriptionEventRepository.class);
        final MediaSubscriptionEpisodeRepository episodeRepository = Mockito.mock(MediaSubscriptionEpisodeRepository.class);
        final MediaSubscriptionEpisodeSourceRepository episodeSourceRepository = Mockito.mock(MediaSubscriptionEpisodeSourceRepository.class);
        final DeadLinkRepository deadLinkRepository = Mockito.mock(DeadLinkRepository.class);
        final ShareRepository shareRepository = Mockito.mock(ShareRepository.class);
        final SiteRepository siteRepository = Mockito.mock(SiteRepository.class);
        final SettingRepository settingRepository = Mockito.mock(SettingRepository.class);
        final AListService aListService = Mockito.mock(AListService.class);
        final TelegramService telegramService = Mockito.mock(TelegramService.class);
        final cn.har01d.alist_tvbox.service.sitesearch.GuanYingSearchService guanYingSearchService =
                Mockito.mock(cn.har01d.alist_tvbox.service.sitesearch.GuanYingSearchService.class);
        final cn.har01d.alist_tvbox.service.sitesearch.PanjuSearchService panjuSearchService =
                Mockito.mock(cn.har01d.alist_tvbox.service.sitesearch.PanjuSearchService.class);
    final ShareService shareService = Mockito.mock(ShareService.class);
    final MetadataService metadataService = Mockito.mock(MetadataService.class);
    final cn.har01d.alist_tvbox.entity.HistoryRepository historyRepository =
            Mockito.mock(cn.har01d.alist_tvbox.entity.HistoryRepository.class);
    final MediaSubscriptionTransferService transferService = Mockito.mock(MediaSubscriptionTransferService.class);
        final MediaSubscriptionCheckService service;
        final MediaSubscription subscription = new MediaSubscription();

        Fixture() {
            AppProperties appProperties = new AppProperties();
            appProperties.setFormats(Set.of("mkv", "mp4")); // 生产由 yaml 绑定,裸实例需手动补
            appProperties.getSubscription().setPrimeCheckTimes(java.util.List.of()); // 档位兜底关闭,断言确定性
            appProperties.getSubscription().setNightCheckTimes(java.util.List.of());
            service = new MediaSubscriptionCheckService(subscriptionRepository, resourceRepository, eventRepository,
                    episodeRepository, episodeSourceRepository, deadLinkRepository,
                    shareRepository, siteRepository, Mockito.mock(DriverAccountRepository.class),
                    Mockito.mock(IndexTemplateRepository.class), settingRepository,
                    shareService, aListService, telegramService, null, null, guanYingSearchService, null, panjuSearchService,
                    metadataService, Mockito.mock(AutoUpdateExecutor.class),
                    historyRepository,
                    appProperties, new ObjectMapper(), transferService, null);
            subscription.setId(1);
            subscription.setName("测试剧");
            subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
            subscription.setMountPath("/追剧/1-测试剧");
            subscription.setShareId(5);
            // 游客 token 探测默认无结论桩:存量判死路径测试零网络依赖,需要测活/死的用例显式换桩
            service.quarkTokenFetcher = (pwdId, passcode) -> null;
            Mockito.when(subscriptionRepository.findById(1)).thenReturn(Optional.of(subscription));
            Mockito.when(shareRepository.findById(5)).thenReturn(Optional.of(new Share()));
            Mockito.when(siteRepository.findById(1)).thenReturn(Optional.of(new Site()));
            Mockito.when(deadLinkRepository.findByLink(Mockito.anyString())).thenReturn(Optional.empty());
            Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.anyInt(), Mockito.anyCollection()))
                    .thenReturn(List.of());
        }
    }

    // ---------- MoviePilot 借鉴(2026-09-01):手动锁总集数 / 总集数回落保护 / 失败语义冷却 ----------

    @Test
    void computeMissingManualTotalOverridesPollutedOfficial() {
        // 官方总 12/已播 11(桥接污染)而用户锁定总 10:缺口封在第 10 集,不再搜不存在的 11/12
        MediaSubscription subscription = new MediaSubscription();
        subscription.setOfficialTotal(12);
        subscription.setOfficialEpisodes(11);
        subscription.setManualTotalEpisodes(10);
        Set<Integer> present = IntStream.rangeClosed(1, 9).boxed()
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of(10), service.computeMissing(subscription, present));
    }

    @Test
    void computeMissingManualTotalDoesNotClampObservations() {
        // 观测不夹(与官方口径同规):锁 10 但资源真有 11(官方低估),已持有的 11 不算缺
        MediaSubscription subscription = new MediaSubscription();
        subscription.setOfficialTotal(8);
        subscription.setManualTotalEpisodes(10);
        Set<Integer> present = IntStream.rangeClosed(1, 11).boxed()
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(service.computeMissing(subscription, present).isEmpty());
    }

    @Test
    void shouldAutoEndByManualTotal() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setManualTotalEpisodes(10);
        subscription.setOfficialStatus("RETURNING");
        subscription.setOfficialEpisodes(12);
        assertTrue(MediaSubscriptionCheckService.shouldAutoEnd(subscription, 10), "锁 10 收齐 10 即完结");
        assertFalse(MediaSubscriptionCheckService.shouldAutoEnd(subscription, 9));
    }

    @Test
    void clampTotalShrinkKeepsHeldEpisodes() {
        MediaSubscriptionEpisodeSourceRepository episodeSourceRepository =
                Mockito.mock(MediaSubscriptionEpisodeSourceRepository.class);
        MediaSubscriptionEventRepository eventRepository = Mockito.mock(MediaSubscriptionEventRepository.class);
        MediaSubscriptionCheckService svc = new MediaSubscriptionCheckService(
                null, null, eventRepository, null, episodeSourceRepository,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                appProperties, new ObjectMapper(), (MediaSubscriptionNotificationService) null);
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(3);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(3), Mockito.anyCollection()))
                .thenReturn(List.of());

        // 首次写入(旧值未知)与增长不设限
        assertEquals(12, svc.clampTotalShrink(subscription, 12));
        subscription.setOfficialTotal(12);
        assertEquals(15, svc.clampTotalShrink(subscription, 15));

        // 官方回落 12→10 而本地已持有 11:只允许回落到 11 —— 已持有的集不因总数缩水变"不存在"
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(3), Mockito.anyCollection()))
                .thenReturn(List.of(1, 2, 11, 13));
        assertEquals(11, svc.clampTotalShrink(subscription, 10));
        assertEquals(11, svc.clampTotalShrink(subscription, 8));
        Mockito.verify(eventRepository, Mockito.atLeastOnce()).save(Mockito.any(MediaSubscriptionEvent.class));
    }

    @Test
    void isBadCooledByFailKind() {
        long now = System.currentTimeMillis();
        MediaSubscriptionResource dead = new MediaSubscriptionResource();
        dead.setState(MediaSubscriptionResource.STATE_RETIRED);
        dead.setCheckedTime(now - 2L * 24 * 3600_000);
        assertFalse(service.isBadCooled(dead, now), "链接死走 badCooldownDays(7 天),2 天未到");

        MediaSubscriptionResource transientRetired = new MediaSubscriptionResource();
        transientRetired.setState(MediaSubscriptionResource.STATE_RETIRED);
        transientRetired.setFailKind(MediaSubscriptionResource.FAIL_KIND_TRANSIENT);
        transientRetired.setCheckedTime(now - 2L * 24 * 3600_000);
        assertTrue(service.isBadCooled(transientRetired, now), "瞬时连击退役走 24h 短冷却,2 天已到");

        // 存量行无 failKind:按 DEAD 保守,与旧口径一致
        MediaSubscriptionResource legacy = new MediaSubscriptionResource();
        legacy.setState(MediaSubscriptionResource.STATE_REJECTED);
        legacy.setCheckedTime(now - 2L * 24 * 3600_000);
        assertFalse(service.isBadCooled(legacy, now));
    }
}
