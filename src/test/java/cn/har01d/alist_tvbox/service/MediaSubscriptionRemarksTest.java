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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第三段体验层:vod_remarks 的 🆕 新集角标(通知同门槛:验证过 + 未看)与集数页签的逐集资源矩阵。
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
            new AppProperties(), new ObjectMapper());

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

    @Test
    void badgeMarksUnwatchedVerifiedEpisodes() {
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(17);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(List.of(16, 17, 18, 19)); // 18/19 已验证未看

        MovieList list = service.contentList(1);

        assertEquals("🆕2 · 已更新至 18 集", list.getList().getFirst().getVod_remarks());
    }

    @Test
    void badgeHiddenWhenUserCaughtUpOrNotStarted() {
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(List.of(16, 17, 18));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(18); // 已追平

        assertEquals("已更新至 18 集", service.contentList(1).getList().getFirst().getVod_remarks());

        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(0); // 还没开始看:角标无信息量
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

    @Test
    void badgeOnlyCountsVerifiedNotListed() {
        // LISTED(列得出没取过链)不算"新集可看" —— 角标与通知共用取链事实口径
        Mockito.when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        Mockito.when(checkService.watchedEpisode(subscription)).thenReturn(17);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7),
                        Mockito.argThat(states -> states.contains(MediaSubscriptionEpisodeSource.STATE_VERIFIED))))
                .thenReturn(List.of(17));
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7),
                        Mockito.argThat(states -> states.contains(MediaSubscriptionEpisodeSource.STATE_LISTED))))
                .thenReturn(List.of(18));

        assertEquals("已更新至 18 集", service.contentList(1).getList().getFirst().getVod_remarks());
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
}
