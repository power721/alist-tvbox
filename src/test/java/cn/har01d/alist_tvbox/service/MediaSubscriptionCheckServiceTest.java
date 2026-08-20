package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.IndexTemplateRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEventRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
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
import org.mockito.Mockito;

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
            null, null, null, null, null, null, null, null, null, null, null, null, null, new AppProperties(), new ObjectMapper());

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

    /** 失效确认分支的 mock 夹具:订阅已挂主源(shareId=5),AList 列目录行为由各测试定制。 */
    private static class Fixture {
        final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
        final MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
        final MediaSubscriptionEventRepository eventRepository = Mockito.mock(MediaSubscriptionEventRepository.class);
        final ShareRepository shareRepository = Mockito.mock(ShareRepository.class);
        final SiteRepository siteRepository = Mockito.mock(SiteRepository.class);
        final SettingRepository settingRepository = Mockito.mock(SettingRepository.class);
        final AListService aListService = Mockito.mock(AListService.class);
        final MediaSubscriptionCheckService service;
        final MediaSubscription subscription = new MediaSubscription();

        Fixture() {
            AppProperties appProperties = new AppProperties();
            appProperties.setFormats(Set.of("mkv", "mp4")); // 生产由 yaml 绑定,裸实例需手动补
            service = new MediaSubscriptionCheckService(subscriptionRepository, resourceRepository, eventRepository,
                    shareRepository, siteRepository, Mockito.mock(DriverAccountRepository.class),
                    Mockito.mock(IndexTemplateRepository.class), settingRepository,
                    Mockito.mock(ShareService.class), aListService, Mockito.mock(TelegramService.class),
                    Mockito.mock(MetadataService.class), Mockito.mock(AutoUpdateExecutor.class),
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
