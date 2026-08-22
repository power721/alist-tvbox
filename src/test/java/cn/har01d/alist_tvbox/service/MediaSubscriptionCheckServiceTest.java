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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        Setting mainDrives = new Setting();
        mainDrives.setName(MediaSubscriptionCheckService.MSUB_MAIN_DRIVES);
        mainDrives.setValue("5,10"); // 全局主网盘:夸克/百度
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
        Mockito.verify(fixture.resourceRepository, Mockito.times(3)).save(captor.capture());
        // 共同底分:近期+30 4K+25 归属+15 = 70;主网盘(夸克/百度)+15,百度另有免会员+15,115 追更弱-10
        int quark = captor.getAllValues().stream().filter(r -> r.getLink().endsWith("/q")).findFirst().orElseThrow().getScore();
        int baidu = captor.getAllValues().stream().filter(r -> r.getLink().endsWith("/b")).findFirst().orElseThrow().getScore();
        int pan115 = captor.getAllValues().stream().filter(r -> r.getLink().endsWith("/x")).findFirst().orElseThrow().getScore();
        assertEquals(85, quark, "主网盘夸克 = 70 + 主网盘15");
        assertEquals(100, baidu, "主网盘百度 = 70 + 主网盘15 + 免会员15");
        assertEquals(60, pan115, "非主网盘115 = 70 - 追更弱10(无主网盘加分)");
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
        assertFalse(MediaSubscriptionCheckService.isThrottleError("资源无可识别的剧集文件:某标题"));
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
        assertEquals(2, others, "非主网盘必须拿到席位(旧实现为 0 —— 备用盘永远进不了池)");
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
        files.put(1, new MediaSubscriptionCheckService.EpisodeFile(1, "/追剧/1-测试剧", "第01集.mkv", 500L));
        files.put(2, new MediaSubscriptionCheckService.EpisodeFile(2, "/追剧/1-测试剧", "第02集.mkv", 500L));

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
        files.put(17, new MediaSubscriptionCheckService.EpisodeFile(17, "/追剧/1-测试剧", "第17集-new.mkv", 500L));
        files.put(18, new MediaSubscriptionCheckService.EpisodeFile(18, "/追剧/1-测试剧", "第18集.mkv", 500L));

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
                .thenThrow(new RuntimeException("failed get link: 参数错误"));

        boolean dead = fixture.service.contagion(fixture.subscription, resource, 99);

        assertTrue(dead, "二次探测仍失败 = 整源死");
        assertEquals(MediaSubscriptionResource.STATE_RETIRED, resource.getState());
        Mockito.verify(fixture.shareService).deleteShare(9);
        assertEquals(MediaSubscriptionEpisodeSource.STATE_FAILED, liveRow.getState());
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
                .thenThrow(new RuntimeException("failed get link: 参数错误"));
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

    /** 失效确认/集源行分支的 mock 夹具:订阅已挂主源(shareId=5),仓储行为由各测试定制。 */
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
                    Mockito.mock(MetadataService.class), Mockito.mock(AutoUpdateExecutor.class),
                    Mockito.mock(cn.har01d.alist_tvbox.entity.HistoryRepository.class),
                    appProperties, new ObjectMapper());
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
