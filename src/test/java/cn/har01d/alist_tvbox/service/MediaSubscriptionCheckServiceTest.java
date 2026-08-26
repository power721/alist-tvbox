package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.DeadLinkRepository;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.IndexTemplateRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisode;
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
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.util.TextUtils;
import cn.har01d.alist_tvbox.service.metadata.MetadataService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final MediaSubscriptionCheckService service = new MediaSubscriptionCheckService(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            new AppProperties(), new ObjectMapper());

    @Test
    void seasonEpisodePattern() {
        assertEquals(5, service.parseEpisode("Show.S01E05.1080p.mkv", null));
        assertEquals(5, service.parseEpisode("Show.S01E05.1080p.mkv", 1));
        assertEquals(-1, service.parseEpisode("Show.S01E05.1080p.mkv", 2));
        assertEquals(12, service.parseEpisode("剧名.S02E12.2160p.WEB-DL.mkv", 2));
    }

    @Test
    void chineseEpisodeSuffix() {
        assertEquals(3, service.parseEpisode("边水往事.第03集.4K.mkv", null));
        assertEquals(12, service.parseEpisode("边水往事 第12集.mp4", null));
        assertEquals(20, service.parseEpisode("某剧.更新至20集", null));
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
        service.scheduleNext(subscription);
        assertClose(now + 3600_000L, subscription.getNextCheckTime());
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
        assertFalse(MediaSubscriptionCheckService.behindAiredEpisodes(subscription), "官方无数据不判缺");
        subscription.setOfficialEpisodes(55);
        assertFalse(MediaSubscriptionCheckService.behindAiredEpisodes(subscription), "本地未知不判缺");
        subscription.setCurrentEpisodes(55);
        assertFalse(MediaSubscriptionCheckService.behindAiredEpisodes(subscription), "追平不算缺");
        subscription.setCurrentEpisodes(10);
        assertTrue(MediaSubscriptionCheckService.behindAiredEpisodes(subscription));
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
        Mockito.verify(fixture.resourceRepository, Mockito.never())
                .findBySubscriptionIdOrderByScoreDesc(Mockito.anyInt()); // 未走换源
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
                .findBySubscriptionIdOrderByScoreDesc(Mockito.anyInt());
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
    void matchesTitleToleratesSingleCharObfuscation() {
        List<String> names = List.of("漫长的季节");
        assertTrue(MediaSubscriptionCheckService.matchesTitle(names, "漫氦的季节 全12集 4K")); // 1 字防审查变形
        assertFalse(MediaSubscriptionCheckService.matchesTitle(names, "漫长的授夜 全12集")); // 2 字差:别剧
    }

    @Test
    void matchesTitleWithoutNamesKeepsOldBehavior() {
        assertTrue(MediaSubscriptionCheckService.matchesTitle(List.of(), "随便什么标题"));
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
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean()))
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
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean()))
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
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/q", "苍兰诀 第01-08集 4K"),
                        message("https://pan.baidu.com/s/b", "苍兰诀 第01-08集 4K", "10"),
                        message("https://115.com/s/x", "苍兰诀 第01-08集 4K", "8")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(Mockito.eq(1), Mockito.anyString()))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository, Mockito.times(2)).save(captor.capture());
        // 共同底分:近期+30 4K+25 归属+15 = 70;主网盘(夸克/百度)+15,百度另有免会员+17(含夸克易和谐加成)
        int quark = captor.getAllValues().stream().filter(r -> r.getLink().endsWith("/q")).findFirst().orElseThrow().getScore();
        int baidu = captor.getAllValues().stream().filter(r -> r.getLink().endsWith("/b")).findFirst().orElseThrow().getScore();
        assertEquals(85, quark, "主网盘夸克 = 70 + 主网盘15");
        assertEquals(102, baidu, "主网盘百度 = 70 + 主网盘15 + 免会员17");
        assertTrue(captor.getAllValues().stream().noneMatch(r -> r.getLink().endsWith("/x")),
                "未配置扩展网盘:非主网盘 115 不入候选池(默认只有主网盘的源)");
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
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean()))
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
        assertEquals(60, pan115, "扩展盘 115 = 70 - 追更弱10(无主网盘加分)");
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
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(List.of(message("https://pan.quark.cn/s/q", "苍兰诀 第01-08集 4K")));
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of());
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdAndLink(1, "https://pan.quark.cn/s/q"))
                .thenReturn(Optional.empty());

        fixture.service.fillPool(fixture.subscription, true, null);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        Mockito.verify(fixture.resourceRepository).save(captor.capture());
        // 底分 = 近期30 + 归属30(权重表覆盖 15) ;4K 默认 25 被调没
        assertEquals(60, captor.getValue().getScore(), "权重表覆盖打分:quality.uhd=0 不加分,match.title=30");
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, captor.getValue().getState());
    }

    @Test
    void fillPoolRejectsWrongSeasonTitle() {
        Fixture fixture = new Fixture();
        fixture.subscription.setName("苍兰诀");
        fixture.subscription.setSeason(2);
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean()))
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
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean())).thenReturn(results);
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
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean())).thenReturn(results);
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
        assertEquals(MediaSubscriptionCheckService.ProbeFailure.GONE,
                MediaSubscriptionCheckService.classifyProbeFailure(new RuntimeException("failed get link: 参数错误")));
        assertEquals(MediaSubscriptionCheckService.ProbeFailure.GONE,
                MediaSubscriptionCheckService.classifyProbeFailure(new RuntimeException("分享已失效")));
        assertEquals(MediaSubscriptionCheckService.ProbeFailure.GONE,
                MediaSubscriptionCheckService.classifyProbeFailure(new RuntimeException("object not found")));
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
        Mockito.when(fixture.telegramService.searchAggregated(Mockito.anyString(), Mockito.anyInt(), Mockito.anyBoolean()))
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
            service = new MediaSubscriptionCheckService(subscriptionRepository, resourceRepository, eventRepository,
                    episodeRepository, episodeSourceRepository, deadLinkRepository,
                    shareRepository, siteRepository, Mockito.mock(DriverAccountRepository.class),
                    Mockito.mock(IndexTemplateRepository.class), settingRepository,
                    shareService, aListService, telegramService, null, null, null, null,
                    metadataService, Mockito.mock(AutoUpdateExecutor.class),
                    historyRepository,
                    appProperties, new ObjectMapper(), transferService);
            subscription.setId(1);
            subscription.setName("测试剧");
            subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
            subscription.setMountPath("/追剧/1-测试剧");
            subscription.setShareId(5);
            Mockito.when(subscriptionRepository.findById(1)).thenReturn(Optional.of(subscription));
            Mockito.when(shareRepository.findById(5)).thenReturn(Optional.of(new Share()));
            Mockito.when(siteRepository.findById(1)).thenReturn(Optional.of(new Site()));
            Mockito.when(deadLinkRepository.findByLink(Mockito.anyString())).thenReturn(Optional.empty());
            Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.anyInt(), Mockito.anyCollection()))
                    .thenReturn(List.of());
        }
    }
}
