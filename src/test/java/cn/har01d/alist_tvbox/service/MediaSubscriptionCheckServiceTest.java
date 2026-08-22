package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.IndexTemplateRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
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
import cn.har01d.alist_tvbox.service.metadata.MetadataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 追剧订阅巡检:集数解析启发式、调度(短轮窗口/退避封顶)、补搜节制、ENDED 重开判定、BAD 冷却、主源失效确认。
 */
class MediaSubscriptionCheckServiceTest {

    private final MediaSubscriptionCheckService service = new MediaSubscriptionCheckService(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, new AppProperties(), new ObjectMapper());

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

    // ---------- ENDED 重开判定 ----------

    @Test
    void shouldReopenWhenOfficialOrExpectedRaised() {
        MediaSubscription subscription = subscription();
        subscription.setCurrentEpisodes(24);
        subscription.setOfficialEpisodes(26);
        assertTrue(service.shouldReopen(subscription));
        subscription.setOfficialEpisodes(24);
        assertFalse(service.shouldReopen(subscription));
        subscription.setExpectedEpisodes(30);
        assertTrue(service.shouldReopen(subscription));
    }

    @Test
    void shouldReopenFalseWithoutData() {
        MediaSubscription subscription = subscription();
        subscription.setCurrentEpisodes(24);
        assertFalse(service.shouldReopen(subscription));
    }

    // ---------- BAD 冷却重探 ----------

    @Test
    void badCooldownAllowsReprobeAfter7Days() {
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setValidity(MediaSubscriptionResource.VALIDITY_BAD);
        long now = System.currentTimeMillis();
        resource.setCheckedTime(now - 8L * 24 * 3600_000);
        assertTrue(service.isBadCooled(resource, now));
        resource.setCheckedTime(now - 24 * 3600_000);
        assertFalse(service.isBadCooled(resource, now));
        resource.setCheckedTime(null);
        assertTrue(service.isBadCooled(resource, now));
        resource.setValidity(MediaSubscriptionResource.VALIDITY_OK);
        assertFalse(service.isBadCooled(resource, now));
    }

    // ---------- 主源失效确认:AList 整体故障不误杀 ----------

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
    void invalidConfirmedAfterRetryMarksBad() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource active = new MediaSubscriptionResource();
        active.setSubscriptionId(1);
        active.setActive(true);
        active.setValidity(MediaSubscriptionResource.VALIDITY_OK);
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
        assertEquals(MediaSubscriptionResource.VALIDITY_BAD, active.getValidity()); // 确认失效才标 BAD
        assertEquals(MediaSubscription.STATUS_ERROR, fixture.subscription.getStatus()); // 池空且搜索无果
    }

    @Test
    void transientListingFailureDoesNotInvalidate() {
        Fixture fixture = new Fixture();
        FsResponse response = new FsResponse();
        FsInfo file = new FsInfo();
        file.setName("第01集.mkv");
        file.setType(0);
        file.setSize(500L * 1024 * 1024);
        response.setFiles(List.of(file));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(response);
        fixture.service.check(1);
        assertEquals(MediaSubscription.STATUS_ACTIVE, fixture.subscription.getStatus());
        assertEquals(1, fixture.subscription.getCurrentEpisodes()); // 重试成功继续本轮
        Mockito.verify(fixture.resourceRepository, Mockito.never()).save(Mockito.any()); // 未标 BAD
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

    // ---------- 缺陷 1 回归:同集多个失效源必须各自累积,不能互相覆盖 ----------
    // 旧版每集只存一个源目录且覆盖写,playEpisode 的候选循环里"试 A 失败记 A,试 B 失败记 B
    // 把 A 覆盖掉",下次播放 A 不被跳过、重蹈覆辙 —— 两个以上失效源时永不收敛。

    @Test
    void brokenRegistryAccumulatesMultipleSourcesPerEpisode() {
        MediaSubscriptionCheckService svc = new MediaSubscriptionCheckService(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                new AppProperties(), new ObjectMapper());
        MediaSubscription subscription = subscription();

        svc.addBrokenEpisodes(subscription, java.util.Map.of(17, "/追剧/剧-补1"));
        svc.addBrokenEpisodes(subscription, java.util.Map.of(17, "/追剧/剧-补2"));

        var broken = svc.parseBroken(subscription);
        assertEquals(java.util.Set.of("/追剧/剧-补1", "/追剧/剧-补2"), broken.get(17),
                "同集两个失效源都要记住 —— 旧版第二个会覆盖第一个");
        assertTrue(MediaSubscriptionCheckService.isBroken(broken, 17, "/追剧/剧-补1"));
        assertTrue(MediaSubscriptionCheckService.isBroken(broken, 17, "/追剧/剧-补2"));
        assertFalse(MediaSubscriptionCheckService.isBroken(broken, 17, "/追剧/剧-主源"));
        assertFalse(MediaSubscriptionCheckService.isBroken(broken, 16, "/追剧/剧-补1"));
    }

    @Test
    void brokenRegistryReadsLegacyScalarFormat() {
        // 旧数据是 {集号: "源目录|时间戳"} 标量,升级后必须还读得出来
        MediaSubscriptionCheckService svc = new MediaSubscriptionCheckService(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                new AppProperties(), new ObjectMapper());
        MediaSubscription subscription = subscription();
        subscription.setBrokenEpisodes("{\"17\":\"/追剧/剧-补1|" + System.currentTimeMillis() + "\"}");

        var broken = svc.parseBroken(subscription);
        assertEquals(java.util.Set.of("/追剧/剧-补1"), broken.get(17));
    }

    @Test
    void brokenRegistryDropsExpiredEntries() {
        MediaSubscriptionCheckService svc = new MediaSubscriptionCheckService(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                new AppProperties(), new ObjectMapper());
        MediaSubscription subscription = subscription();
        long old = System.currentTimeMillis() - 8L * 24 * 3600_000; // 8 天前,超 7 天保留期
        subscription.setBrokenEpisodes("{\"17\":[\"/追剧/剧-补1|" + old + "\"]}");

        assertTrue(svc.parseBroken(subscription).isEmpty(), "过期登记应释放,给源一次重试机会");
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
        // 夸克「分享地址已失效」是真失效,必须继续判 BAD —— 别把限流保护扩大成"什么都不判死"
        assertFalse(MediaSubscriptionCheckService.isThrottleError("/追剧/悬案 [dbid-36624136]: 分享地址已失效"));
        assertFalse(MediaSubscriptionCheckService.isThrottleError("资源无可识别的剧集文件:某标题"));
        assertFalse(MediaSubscriptionCheckService.isThrottleError("挂载失败:https://pan.quark.cn/s/x"));
        assertFalse(MediaSubscriptionCheckService.isThrottleError(null));
    }

    @Test
    void searchSourceValidityIsNormalizedOnAdmission() {
        // 各源状态词大小写不一(盘检返回小写 ok),直接存原值会让 VALIDITY_OK.equals 静默失配
        assertEquals(MediaSubscriptionResource.VALIDITY_BAD, MediaSubscriptionCheckService.normalizeValidity("bad"));
        assertEquals(MediaSubscriptionResource.VALIDITY_BAD, MediaSubscriptionCheckService.normalizeValidity("Invalid"));
        assertEquals(MediaSubscriptionResource.VALIDITY_BAD, MediaSubscriptionCheckService.normalizeValidity(" EXPIRED "));
        // 盘检 ok 只证明链接可达,不证明挂得上 —— 不许冒充"已验证可用"
        assertEquals(MediaSubscriptionResource.VALIDITY_UNKNOWN, MediaSubscriptionCheckService.normalizeValidity("ok"));
        assertEquals(MediaSubscriptionResource.VALIDITY_UNKNOWN, MediaSubscriptionCheckService.normalizeValidity("OK"));
        assertEquals(MediaSubscriptionResource.VALIDITY_UNKNOWN, MediaSubscriptionCheckService.normalizeValidity(null));
        assertEquals(MediaSubscriptionResource.VALIDITY_UNKNOWN, MediaSubscriptionCheckService.normalizeValidity(""));
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

    // ---------- 缺陷 4 回归:死掉的补缺挂载不得再"冒领"集数 ----------
    // 线上事故:补缺源标题「10集全」,分享已死(取链 参数错误),但旧覆盖快照仍声称覆盖 1~10 集。
    // 刷新时 walkEpisodes 抛异常被静默吞掉、快照保持不变,随后 missingStill 被这份陈旧快照扣光
    // → 不触发补搜 → 池永远补不上 → 播放时"已尝试 1 个源"失败。系统自认健康,用户一集都播不了。

    @Test
    void deadGapMountStopsClaimingEpisodesAndIsRetired() {
        Fixture fixture = new Fixture();
        MediaSubscriptionResource gap = new MediaSubscriptionResource();
        gap.setId(9);
        gap.setSubscriptionId(1);
        gap.setLink("https://pan.quark.cn/s/dead");
        gap.setTitle("测试剧 10集全");
        gap.setType(5);
        gap.setGap(true);
        gap.setShareId(7);
        gap.setMountPath("/追剧/.sources/1-测试剧-补1");
        gap.setEpisodeList("[1,2,3,4,5,6,7,8,9,10]"); // 陈旧快照:声称覆盖全 10 集
        gap.setValidity(MediaSubscriptionResource.VALIDITY_OK);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(gap));
        // 分享已死:列目录抛错
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenThrow(new IllegalStateException("failed get link: 参数错误"));

        Set<Integer> missing = new TreeSet<>(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        fixture.service.fillGaps(fixture.subscription, missing);

        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), missing,
                "死源不得从缺口里扣掉任何一集 —— 扣掉就不会触发补搜");
        assertEquals(MediaSubscriptionResource.VALIDITY_BAD, gap.getValidity(), "列不出内容的源必须标 BAD");
        Mockito.verify(fixture.shareService).deleteShare(7); // 退役,腾出 maxGapMounts 名额
        assertFalse(gap.isGap());
        assertNull(gap.getShareId());
    }

    @Test
    void liveGapMountStillCoversItsEpisodes() {
        // 对照组:分享活着就照常扣减缺口,不能因为修缺陷 4 把正常补缺源也误杀
        Fixture fixture = new Fixture();
        MediaSubscriptionResource gap = new MediaSubscriptionResource();
        gap.setId(9);
        gap.setSubscriptionId(1);
        gap.setLink("https://pan.quark.cn/s/live");
        gap.setType(5);
        gap.setGap(true);
        gap.setShareId(7);
        gap.setMountPath("/追剧/.sources/1-测试剧-补1");
        gap.setEpisodeList("[1,2]");
        gap.setValidity(MediaSubscriptionResource.VALIDITY_OK);
        Mockito.when(fixture.resourceRepository.findBySubscriptionIdOrderByScoreDesc(1)).thenReturn(List.of(gap));
        Mockito.when(fixture.aListService.listFiles(Mockito.any(), Mockito.anyString(),
                        Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(files("测试剧.第01集.mkv", "测试剧.第02集.mkv"));

        Set<Integer> missing = new TreeSet<>(Set.of(1, 2, 3));
        fixture.service.fillGaps(fixture.subscription, missing);

        assertEquals(Set.of(3), missing, "活着的补缺源正常扣减它覆盖的集");
        assertEquals(MediaSubscriptionResource.VALIDITY_OK, gap.getValidity());
        Mockito.verify(fixture.shareService, Mockito.never()).deleteShare(Mockito.anyInt());
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

    /** 失效确认分支的 mock 夹具:订阅已挂主源(shareId=5),AList 列目录行为由各测试定制。 */
    private static class Fixture {
        final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
        final MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
        final MediaSubscriptionEventRepository eventRepository = Mockito.mock(MediaSubscriptionEventRepository.class);
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
                    shareRepository, siteRepository, Mockito.mock(DriverAccountRepository.class),
                    Mockito.mock(IndexTemplateRepository.class), settingRepository,
                    shareService, aListService, telegramService, null, null, null, null,
                    Mockito.mock(MetadataService.class), Mockito.mock(AutoUpdateExecutor.class), Mockito.mock(cn.har01d.alist_tvbox.entity.HistoryRepository.class),
                    appProperties, new ObjectMapper());
            subscription.setId(1);
            subscription.setName("测试剧");
            subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
            subscription.setMountPath("/追剧/1-测试剧");
            subscription.setShareId(5);
            Mockito.when(subscriptionRepository.findById(1)).thenReturn(Optional.of(subscription));
            Mockito.when(shareRepository.findById(5)).thenReturn(Optional.of(new Share()));
            Mockito.when(siteRepository.findById(1)).thenReturn(Optional.of(new Site()));
        }
    }
}
