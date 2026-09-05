package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisode;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第三段体验层:vod_remarks 的 🆕 新集角标(追平门槛:看到过最新播出集后,新播出且未看才亮)
 * 与集数页签的逐集资源矩阵。
 */
class MediaSubscriptionRemarksTest {

    private final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
    private final MediaSubscriptionEpisodeSourceRepository episodeSourceRepository = Mockito.mock(MediaSubscriptionEpisodeSourceRepository.class);
    private final SettingRepository settingRepository = Mockito.mock(SettingRepository.class);
    private final MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);
    private final MediaSubscriptionService service = new MediaSubscriptionService(
            subscriptionRepository, resourceRepository, null, null, episodeSourceRepository,
            null, null, null, null, null, null, checkService, null, settingRepository,
            new AppProperties(), new ObjectMapper(), null, null, null);

    private final MediaSubscription subscription = subscription();

    private static MediaSubscription subscription() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(7);
        subscription.setUid(1);
        subscription.setName("测试剧");
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setCurrentEpisodes(18);
        subscription.setMountPath("/追剧/7-测试剧");
        return subscription;
    }

    // ---------- 🆕 角标 ----------

    // ---------- 「最近更新」虚拟分类(updatedTime 近 7 天) ----------

    @Test
    void airingSeasonWindowedShowsNoAutoDenominator() {
        // 分季订阅对齐的在播季不显示自动分母:全剧总集数(TMDB 200)与腾讯分季登记数都不可信,
        // 推本季体量是假精度 —— 「已更新至 N 集」;完结(status=ENDED)后「N集完结」
        subscription.setCurrentEpisodes(9);
        subscription.setOfficialTotal(200);
        subscription.setSeasonStartEpisode(166);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));

        assertEquals("已更新至 9 集", service.contentList(1).getList().getFirst().getVod_remarks());

        // 手填期望集数仍是唯一合法的自动分母来源
        subscription.setExpectedEpisodes(16);
        assertEquals("9/16集", service.contentList(1).getList().getFirst().getVod_remarks());

        subscription.setExpectedEpisodes(0);
        subscription.setStatus(MediaSubscription.STATUS_ENDED);
        assertEquals("9集完结", service.contentList(1).getList().getFirst().getVod_remarks());
    }

    @Test
    void recentCategoryKeepsRecentlyUpdatedSubscriptions() {
        MediaSubscription stale = subscription();
        stale.setId(8);
        long now = System.currentTimeMillis();
        subscription.setUpdatedTime(now - 3L * 24 * 3600 * 1000);
        stale.setUpdatedTime(now - 8L * 24 * 3600 * 1000);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription, stale));

        List<String> names = service.contentList(1, "recent", null).getList()
                .stream().map(cn.har01d.alist_tvbox.tvbox.MovieDetail::getVod_name).toList();
        assertEquals(List.of("测试剧"), names, "7 天窗口外的订阅不进「最近更新」");
    }

    @Test
    void recentCategoryIncludesAllStatuses() {
        // 完结/暂停订阅只要有近期变动也在列 —— 口径是「有更新」而非「在播」
        subscription.setStatus(MediaSubscription.STATUS_ENDED);
        subscription.setUpdatedTime(System.currentTimeMillis() - 3600_000L);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));

        assertEquals(1, service.contentList(1, "recent", null).getList().size());
    }

    // ---------- 操作线路「订阅信息」缺集行 ----------

    @Test
    void statusTextIncludesMissingEpisodes() {
        subscription.setOfficialEpisodes(20);
        Mockito.when(subscriptionRepository.findById(7)).thenReturn(Optional.of(subscription));
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(java.util.stream.IntStream.rangeClosed(1, 19).boxed().toList());

        String text = service.subscriptionStatusText(1, 7);
        assertTrue(text.contains("官方已播至第 20 集,缺第 20 集"), text);
    }

    @Test
    void statusTextAllSyncedWhenNoGap() {
        subscription.setOfficialEpisodes(5);
        Mockito.when(subscriptionRepository.findById(7)).thenReturn(Optional.of(subscription));
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(java.util.stream.IntStream.rangeClosed(1, 5).boxed().toList());

        String text = service.subscriptionStatusText(1, 7);
        assertTrue(text.contains("本地已全部同步"), text);
    }

    @Test
    void statusTextSkipsMissingLineWithoutOfficialSnapshot() {
        subscription.setOfficialEpisodes(null);
        Mockito.when(subscriptionRepository.findById(7)).thenReturn(Optional.of(subscription));

        String text = service.subscriptionStatusText(1, 7);
        assertFalse(text.contains("官方已播"), "官方快照缺失不臆测: " + text);
    }

    @Test
    void statusTextIncludesNextAirWithWeekday() {
        // 2026-09-05 是周六,20:00 北京时间 —— 周播剧的周几是更新锚点,文案必须带出
        subscription.setNextAirTime(java.time.ZonedDateTime.of(2026, 9, 5, 20, 0, 0, 0,
                        java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
        Mockito.when(subscriptionRepository.findById(7)).thenReturn(Optional.of(subscription));

        String text = service.subscriptionStatusText(1, 7);
        assertTrue(text.contains("下集播出:09-05 周六 20:00"), text);
    }

    @Test
    void statusTextOmitsNextAirWhenEnded() {
        subscription.setStatus(MediaSubscription.STATUS_ENDED);
        subscription.setNextAirTime(System.currentTimeMillis() + 3600_000L);
        Mockito.when(subscriptionRepository.findById(7)).thenReturn(Optional.of(subscription));

        String text = service.subscriptionStatusText(1, 7);
        assertFalse(text.contains("下集播出"), "完结剧无下集播出: " + text);
    }

    @Test
    void statusTextIncludesManualUpdateDays() {
        subscription.setAirWeekdays("2,4");
        Mockito.when(subscriptionRepository.findById(7)).thenReturn(Optional.of(subscription));

        String text = service.subscriptionStatusText(1, 7);
        assertTrue(text.contains("更新日:周二、周四"), text);
    }

    @Test
    void serializeAirWeekdaysRoundTrip() {
        assertEquals("2,4", MediaSubscriptionService.serializeAirWeekdays(List.of(4, 2, 2)));
        assertNull(MediaSubscriptionService.serializeAirWeekdays(java.util.Arrays.asList(8, 0, null)), "全非法归 null(未配置)");
        assertNull(MediaSubscriptionService.serializeAirWeekdays(null));
    }

    @Test
    void badgeCountsNewEpisodesAiredAfterCaughtUp() {
        // 用户故事:追平(看到第18集=当时最新)→ 第19集新播出 → 🆕1
        subscription.setCaughtUpEpisode(18);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(18);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(List.of(17, 18, 19));

        assertEquals("🆕1 · 已更新至 18 集", service.contentList(1).getList().getFirst().getVod_remarks());
    }

    @Test
    void badgeNotCountedWhenRewoundToEarlierEpisode() {
        // 线上形态:33集完结追平(标记33)后跳回前面集,History 当前进度掉到11 ——
        // 12~33 是看过的旧集,不得误报 🆕22
        subscription.setStatus(MediaSubscription.STATUS_ENDED);
        subscription.setCurrentEpisodes(33);
        subscription.setCaughtUpEpisode(33);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(11);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(java.util.stream.IntStream.rangeClosed(1, 33).boxed().toList());

        assertEquals("33集完结", service.contentList(1).getList().getFirst().getVod_remarks());
    }

    @Test
    void badgeCountsOnlyEpisodesBeyondCaughtUpLineWhileRewatching() {
        // 回看途中真有新集播出(34):只计追平线之外的 —— 🆕1 而不是 🆕23
        subscription.setCurrentEpisodes(34);
        subscription.setCaughtUpEpisode(33);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(11);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(java.util.stream.IntStream.rangeClosed(1, 34).boxed().toList());

        assertEquals("🆕1 · 已更新至 34 集", service.contentList(1).getList().getFirst().getVod_remarks());
    }

    @Test
    void badgeHiddenWhileUserBehindBeforeEverCaughtUp() {
        // 线上诛仙形态:在看第1集、盘上已有3集,从未追平 —— 落后补看途中不亮灯,也不登记追平标记
        subscription.setCurrentEpisodes(3);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(1);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(List.of(1, 2, 3));

        assertEquals("已更新至 3 集", service.contentList(1).getList().getFirst().getVod_remarks());
        Mockito.verify(subscriptionRepository, Mockito.never()).markCaughtUp(Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    void caughtUpMarkerPersistedWhenWatchingReachesLatest() {
        // 进度追上资源侧最新集:登记追平标记(定向 update + 实体就地同步),角标为空
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(18);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(List.of(17, 18));

        assertEquals("已更新至 18 集", service.contentList(1).getList().getFirst().getVod_remarks());
        Mockito.verify(subscriptionRepository).markCaughtUp(7, 18);
        assertEquals(18, subscription.getCaughtUpEpisode());

        // 标记已登记且未更高:后续请求不重复写库
        Mockito.clearInvocations(subscriptionRepository);
        service.contentList(1);
        Mockito.verify(subscriptionRepository, Mockito.never()).markCaughtUp(Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    void caughtUpMarkerNeverDowngraded() {
        // 资源侧集数回落(换源/补缺丢集)最新只剩17,标记停在18:只升不降,不回写
        subscription.setCaughtUpEpisode(18);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(18);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(List.of(17));

        assertEquals("已更新至 18 集", service.contentList(1).getList().getFirst().getVod_remarks());
        Mockito.verify(subscriptionRepository, Mockito.never()).markCaughtUp(Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    void badgeDedupesSameEpisodeAcrossResources() {
        // 同集在多个资源上都有 LIVE 行(集源行按 集×资源 粒度):去重后按集号计数
        subscription.setCaughtUpEpisode(18);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(18);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(List.of(18, 19, 19, 19, 20));

        assertEquals("🆕2 · 已更新至 18 集", service.contentList(1).getList().getFirst().getVod_remarks());
    }

    @Test
    void badgeCountsListedEpisodesAsAired() {
        // 播出口径 = 资源侧可播(LISTED/VERIFIED 均算),不再等取链验证 —— 与详情列表能点到的集一致
        subscription.setCaughtUpEpisode(17);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(17);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7),
                        Mockito.argThat(states -> states.contains(MediaSubscriptionEpisodeSource.STATE_LISTED))))
                .thenReturn(List.of(17, 18));

        assertEquals("🆕1 · 已更新至 18 集", service.contentList(1).getList().getFirst().getVod_remarks());
    }

    @Test
    void badgeClearedWhenUserCatchesUpAgain() {
        // 追平过的订阅落后一集,看完(19=最新)后角标消除并抬升标记
        subscription.setCaughtUpEpisode(18);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(19);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(List.of(18, 19));

        assertEquals("已更新至 18 集", service.contentList(1).getList().getFirst().getVod_remarks());
        Mockito.verify(subscriptionRepository).markCaughtUp(7, 19);
    }

    @Test
    void badgeHiddenWhenUserNotStarted() {
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(0); // 还没开始看:角标无信息量
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(List.of(18));

        assertEquals("已更新至 18 集", service.contentList(1).getList().getFirst().getVod_remarks());
    }

    @Test
    void badgeDisabledBySetting() {
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(17);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(List.of(18));
        cn.har01d.alist_tvbox.entity.Setting off = new cn.har01d.alist_tvbox.entity.Setting();
        off.setName("msub_tvbox_badge");
        off.setValue("false");
        Mockito.when(settingRepository.findById("msub_tvbox_badge")).thenReturn(Optional.of(off));

        assertEquals("已更新至 18 集", service.contentList(1).getList().getFirst().getVod_remarks(),
                "Setting msub_tvbox_badge=false 关闭角标");
    }

    // ---------- 集数进度文案 ----------

    @Test
    void remarksShowProgressFraction() {
        MediaSubscription sub = subscription();
        sub.setCurrentEpisodes(10);
        sub.setExpectedEpisodes(30);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(sub));

        assertEquals("10/30集", service.contentList(1).getList().getFirst().getVod_remarks());
    }

    @Test
    void remarksShowCompletedTotal() {
        MediaSubscription sub = subscription();
        sub.setCurrentEpisodes(30);
        sub.setExpectedEpisodes(30);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(sub));

        assertEquals("30集完结", service.contentList(1).getList().getFirst().getVod_remarks());
    }

    @Test
    void remarksKeepUpdatedTextWithoutExpectedTotal() {
        // 总集数未知(元数据未绑定/官方未公布):维持「已更新至 N 集」,不臆造分母
        MediaSubscription sub = subscription();
        sub.setCurrentEpisodes(18);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(sub));

        assertEquals("已更新至 18 集", service.contentList(1).getList().getFirst().getVod_remarks());
    }

    @Test
    void remarksFallBackToOfficialTotal() {
        // 手填期望为空/0 时总数走官方总集数(与 web 列表同口径):线上订阅几乎都不填期望
        MediaSubscription sub = subscription();
        sub.setCurrentEpisodes(28);
        sub.setExpectedEpisodes(0);
        sub.setOfficialEpisodes(28);
        sub.setOfficialTotal(33);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(sub));

        assertEquals("28/33集", service.contentList(1).getList().getFirst().getVod_remarks());
    }

    @Test
    void remarksShowCompletedByStatusOrSeasonAiredOut() {
        // 状态 ENDED(自动/手动完结)即完结展示,不依赖手填期望
        MediaSubscription sub = subscription();
        sub.setCurrentEpisodes(30);
        sub.setStatus(MediaSubscription.STATUS_ENDED);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(sub));

        assertEquals("30集完结", service.contentList(1).getList().getFirst().getVod_remarks());

        // 本季已播完且收齐(isSeasonAiredOut,shouldAutoEnd 第三路同条件)同样完结展示
        MediaSubscription airing = subscription();
        airing.setCurrentEpisodes(10);
        airing.setOfficialEpisodes(10);
        airing.setOfficialTotal(10);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(airing));

        assertEquals("10集完结", service.contentList(1).getList().getFirst().getVod_remarks());
    }

    @Test
    void remarksInProgressSeasonNotMarkedEnded() {
        // 年番形态:官方已知集数已收齐但季未播完(还有下集播出时间)→ 仍是 x/y集 追更中
        MediaSubscription sub = subscription();
        sub.setCurrentEpisodes(188);
        sub.setOfficialEpisodes(188);
        sub.setOfficialTotal(188);
        sub.setNextAirTime(System.currentTimeMillis() + 7L * 24 * 3600_000);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(sub));

        assertEquals("188/188集", service.contentList(1).getList().getFirst().getVod_remarks());
    }

    // ---------- 标题季标去重 ----------

    @Test
    void titleSeasonSuffixNotDuplicated() {
        // 豆瓣条目名自带「第九季」,resolveSeason 从名字解析出 season=9 后不应再追加「第9季」
        MediaSubscription sub = subscription();
        sub.setName("瑞克和莫蒂 第九季");
        sub.setSeason(9);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(sub));

        assertEquals("瑞克和莫蒂 第九季", service.contentList(1).getList().getFirst().getVod_name());
    }

    @Test
    void titleSeasonSuffixStillAppendedWithoutTitleMark() {
        MediaSubscription sub = subscription();
        sub.setSeason(2);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(sub));

        assertEquals("测试剧 第2季", service.contentList(1).getList().getFirst().getVod_name());
    }

    // ---------- 封面代理 ----------

    @Test
    void coverDirectLinkRoutedThroughImagesProxy() {
        // 线上形态:巡检回填的封面快照是 TMDB 直链,TVBox 客户端直连图床被墙 ——
        // vod_pic 必须包进后端 /images 代理(单测无请求上下文,绝对化回落相对地址,代理形态即断言点)
        String tmdbCover = "https://media.themoviedb.org/t/p/w300_and_h450_bestv2/abc.jpg";
        subscription.setCoverUrl(tmdbCover);
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));

        String pic = service.contentList(1).getList().getFirst().getVod_pic();

        assertTrue(pic.startsWith("/images?url="), "直链封面须走 /images 代理: " + pic);
        assertTrue(pic.contains(java.net.URLEncoder.encode(tmdbCover, java.nio.charset.StandardCharsets.UTF_8)),
                "代理参数携带原始图地址");
    }

    // ---------- 逐集资源矩阵 ----------

    @Test
    void episodesExposePerSourceMatrix() {
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(3);
        primary.setTitle("主源标题");
        primary.setType(10);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/7-测试剧");
        MediaSubscriptionResource aux = new MediaSubscriptionResource();
        aux.setId(4);
        aux.setTitle("补缺标题");
        aux.setType(5);
        aux.setState(MediaSubscriptionResource.STATE_MOUNTED);
        aux.setMountPath("/追剧/.sources/7-测试剧-补1");
        MediaSubscriptionResource candidate = new MediaSubscriptionResource(); // 池内候选:不入矩阵
        candidate.setId(5);
        candidate.setTitle("候选标题");
        candidate.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        Mockito.when(subscriptionRepository.findById(7)).thenReturn(Optional.of(subscription));
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of(primary, aux, candidate));
        MediaSubscriptionEpisode ep17 = new MediaSubscriptionEpisode();
        ep17.setId(70);
        ep17.setNumber(17);
        MediaSubscriptionEpisodeSource verified = new MediaSubscriptionEpisodeSource();
        verified.setResourceId(3);
        verified.setState(MediaSubscriptionEpisodeSource.STATE_VERIFIED);
        verified.setSuccessCount(12);
        verified.setFailCount(0);
        verified.setLastVerifiedTime(456000L);
        MediaSubscriptionEpisodeSource failed = new MediaSubscriptionEpisodeSource();
        failed.setResourceId(4);
        failed.setState(MediaSubscriptionEpisodeSource.STATE_FAILED);
        failed.setSuccessCount(0);
        failed.setFailCount(3);
        MediaSubscriptionEpisodeSource probeOnly = new MediaSubscriptionEpisodeSource(); // 候选探测行:不展示
        probeOnly.setResourceId(5);
        probeOnly.setState(MediaSubscriptionEpisodeSource.STATE_LISTED);
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(java.util.Arrays.<Object[]>asList(
                new Object[]{17, verified}, new Object[]{17, failed}, new Object[]{17, probeOnly}));

        List<Map<String, Object>> result = service.episodes(1, 7);

        Map<String, Object> episode17 = result.stream().filter(e -> e.get("episode").equals(17)).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matrix = (List<Map<String, Object>>) episode17.get("sources");
        assertEquals(2, matrix.size(), "只展示挂载资源(主源+补缺),候选探测行不入矩阵");
        assertEquals("主源标题", matrix.get(0).get("title"));
        assertEquals(Boolean.TRUE, matrix.get(0).get("primary"));
        assertEquals("VERIFIED", matrix.get(0).get("state"));
        assertEquals(12, matrix.get(0).get("successCount"));
        assertEquals(456000L, matrix.get(0).get("lastVerifiedTime"));
        assertEquals("补缺标题", matrix.get(1).get("title"));
        assertEquals(Boolean.FALSE, matrix.get(1).get("primary"));
        assertEquals("FAILED", matrix.get(1).get("state"));
        assertEquals("主源", episode17.get("source"), "来源摘要优先主源");
        assertTrue((Boolean) episode17.get("present"));
    }

    @Test
    void episodesMarkAllFailedAsDamaged() {
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(3);
        primary.setType(10);
        primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
        primary.setMountPath("/追剧/7-测试剧");
        Mockito.when(subscriptionRepository.findById(7)).thenReturn(Optional.of(subscription));
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of(primary));
        MediaSubscriptionEpisodeSource failed = new MediaSubscriptionEpisodeSource();
        failed.setResourceId(3);
        failed.setState(MediaSubscriptionEpisodeSource.STATE_FAILED);
        Mockito.when(episodeSourceRepository.findNumberAndSource(7))
                .thenReturn(java.util.Arrays.<Object[]>asList(new Object[]{17, failed}));

        List<Map<String, Object>> result = service.episodes(1, 7);

        Map<String, Object> episode17 = result.stream().filter(e -> e.get("episode").equals(17)).findFirst().orElseThrow();
        assertFalse((Boolean) episode17.get("present"));
        assertEquals("源损坏(待补源)", episode17.get("source"));
    }

    /** 时间轴同日多集合并:同订阅同时段一行(区间/离散压缩),不同订阅/不同时段不混。 */
    @Test
    void scheduleDayItemsMergeSameSubscriptionSameSlot() {
        long t20 = java.time.LocalDate.of(2026, 8, 23).atTime(20, 0)
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        long t21 = t20 + 3600_000;
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>(java.util.Arrays.asList(
                item(1, "重器", 29, t20), item(1, "重器", 33, t20), item(1, "重器", 30, t20),
                item(1, "重器", 32, t20), item(1, "重器", 31, t20),
                item(2, "师兄太稳健", 10, t20), item(2, "师兄太稳健", 11, t20),
                item(3, "午夜档", 5, t21), item(4, "待定", 0, t20)));

        List<Map<String, Object>> merged = MediaSubscriptionService.mergeDayItems(items);

        assertEquals(4, merged.size(), "5连集+2连集+2单条 → 4 行");
        assertEquals("29-33", merged.get(0).get("episodes"));
        assertEquals("重器", merged.get(0).get("name"));
        assertEquals("10-11", merged.get(1).get("episodes"));
        assertEquals("5", merged.get(2).get("episodes"));
        assertNull(merged.get(3).get("episodes"), "集数未知(episode=0)不显示集数");
        assertEquals("10,12-14,20", MediaSubscriptionService.compactEpisodes(java.util.Arrays.asList(12, 10, 20, 13, 14)));
        assertEquals("7", MediaSubscriptionService.compactEpisodes(List.of(7)));
    }

    /** 时间轴「昨天」分组:已播出的集靠 schedule 快照里的昨日条目上墙(provider 只收严格未来会把刚播的集洗掉)。 */
    @Test
    void schedulePutsYesterdayAiredEpisodeInYesterdayBucket() {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        long yesterday20 = java.time.LocalDate.now(zone).minusDays(1).atTime(20, 0)
                .atZone(zone).toInstant().toEpochMilli();
        subscription.setSchedule("[{\"episode\":12,\"airTime\":" + yesterday20 + "}]");
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));

        List<Map<String, Object>> days = service.schedule(1);

        assertEquals("昨天", days.get(0).get("label"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) days.get(0).get("items");
        assertEquals(1, items.size(), "昨日 20:00 已播的集要落「昨天」分组");
        assertEquals("测试剧", items.get(0).get("name"));
        assertEquals(12, items.get(0).get("episode"));
        assertEquals("12", items.get(0).get("episodes"));
    }

    private static Map<String, Object> item(int subscriptionId, String name, int episode, long airTime) {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("subscriptionId", subscriptionId);
        item.put("name", name);
        item.put("episode", episode);
        item.put("airTime", airTime);
        item.put("paused", false);
        return item;
    }
}
