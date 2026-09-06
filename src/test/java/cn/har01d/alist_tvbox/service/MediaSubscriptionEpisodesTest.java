package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 网页集数清单(episodes)的 present 口径:只认真实 LIVE 集源行 ——
 * 「首轮巡检前按 currentEpisodes 兜底显示」只在集源行完全未同步时生效;
 * 行已存在(主源失效/换源后行躺在补缺资源上)时 currentEpisodes 是旧值,兜底=伪造全量「主源已有」。
 * 线上形态:柯南主源目录被清空,兜底伪造 1..1243 + 补缺源真实行 1244..1270,
 * 集数清单 1270 行全 present、缺失 0,与进度 1243/1270、TVBox 1243 集完全对不上。
 */
class MediaSubscriptionEpisodesTest {

    private final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
    private final MediaSubscriptionEpisodeSourceRepository episodeSourceRepository =
            Mockito.mock(MediaSubscriptionEpisodeSourceRepository.class);
    private final MediaSubscriptionTransferService transferService = Mockito.mock(MediaSubscriptionTransferService.class);
    private final cn.har01d.alist_tvbox.service.sitesearch.EpisodeFallbackService episodeFallbackService =
            Mockito.mock(cn.har01d.alist_tvbox.service.sitesearch.EpisodeFallbackService.class);
    private final MediaSubscriptionService service = new MediaSubscriptionService(
            subscriptionRepository, resourceRepository, null, null, episodeSourceRepository,
            null, null, null, null, null, null, null, transferService, null,
            new AppProperties(), new ObjectMapper(), null, null, episodeFallbackService);

    private MediaSubscription subscription() {
        MediaSubscription sub = new MediaSubscription();
        sub.setId(7);
        sub.setUid(1);
        sub.setName("测试剧");
        sub.setStatus(MediaSubscription.STATUS_ACTIVE);
        sub.setMountPath("/追剧/7-测试剧");
        Mockito.when(subscriptionRepository.findById(7)).thenReturn(Optional.of(sub));
        return sub;
    }

    @Test
    void fabricatesNothingWhenPrimaryGoneButRowsExist() {
        MediaSubscription sub = subscription();
        sub.setCurrentEpisodes(3); // 主源时代的旧值,主源资源已不在池里
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of(gapSource()));
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(List.<Object[]>of(
                new Object[]{5, sourceRow(21, "LISTED")}));

        List<Map<String, Object>> rows = service.episodes(1, 7);

        // 只有第 5 集真实可播;1~4 不得凭 currentEpisodes=3 伪造「主源已有」
        assertEquals(5, rows.size());
        assertTrue((boolean) rows.get(4).get("present"));
        assertEquals("补缺:补缺源", rows.get(4).get("source"));
        for (int i = 0; i < 4; i++) {
            assertFalse((boolean) rows.get(i).get("present"), "第" + (i + 1) + "集不应伪造已有");
            assertEquals("", rows.get(i).get("source"));
        }
    }

    @Test
    void fallsBackToCurrentEpisodesOnlyBeforeFirstSync() {
        MediaSubscription sub = subscription();
        sub.setCurrentEpisodes(3); // 行完全未同步(首轮巡检前):按 currentEpisodes 兜底显示
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of());
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(List.of());

        List<Map<String, Object>> rows = service.episodes(1, 7);

        assertEquals(3, rows.size());
        assertTrue(rows.stream().allMatch(row -> (boolean) row.get("present")));
        assertEquals("主源", rows.get(0).get("source"));
    }

    /** 挂载中的补缺资源:挂载路径与订阅主挂载不同 → 非主源 */
    private static MediaSubscriptionResource gapSource() {
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(21);
        resource.setSubscriptionId(7);
        resource.setState(MediaSubscriptionResource.STATE_MOUNTED);
        resource.setMountPath("/追剧/.sources/7-测试剧-补1");
        resource.setTitle("补缺源");
        return resource;
    }

    @Test
    void collectionFallbackOverlayShowsAsPlayableSource() {
        MediaSubscription sub = subscription();
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of());
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(List.<Object[]>of(
                new Object[]{1, sourceRow(21, "LISTED")}));
        // 第 21 号资源不在池内(行被跳过):全清单只有第 3 集的采集兜底覆盖层行是「已有」
        cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallback overlay =
                new cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallback();
        overlay.setSubscriptionId(7);
        overlay.setEpisode(3);
        overlay.setSiteId("feifan");
        overlay.setResourceId("99");
        overlay.setLine("非凡线路");
        overlay.setState(cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallback.STATE_ACTIVE);
        overlay.setValidatedAt(System.currentTimeMillis());
        Mockito.when(episodeFallbackService.activeRows(7)).thenReturn(List.of(overlay));

        List<Map<String, Object>> rows = service.episodes(1, 7);

        // 第 3 集呈现为已有(来源「采集兜底」),矩阵带 FALLBACK 状态行;第 2 集仍缺
        Map<String, Object> overlayRow = rows.stream().filter(r -> r.get("episode").equals(3)).findFirst().orElseThrow();
        assertTrue((boolean) overlayRow.get("present"));
        assertEquals("采集兜底:非凡线路", overlayRow.get("source"));
        List<Map<String, Object>> sources = (List<Map<String, Object>>) overlayRow.get("sources");
        assertEquals("FALLBACK", sources.getFirst().get("state"));
        Map<String, Object> missingRow = rows.stream().filter(r -> r.get("episode").equals(2)).findFirst().orElseThrow();
        assertFalse((boolean) missingRow.get("present"));
    }

    private static MediaSubscriptionEpisodeSource sourceRow(int resourceId, String state) {
        MediaSubscriptionEpisodeSource row = new MediaSubscriptionEpisodeSource();
        row.setResourceId(resourceId);
        row.setState(state);
        return row;
    }
}
