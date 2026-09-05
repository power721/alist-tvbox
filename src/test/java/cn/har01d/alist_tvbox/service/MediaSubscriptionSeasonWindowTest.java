package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.EpisodeInfo;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.service.metadata.MetadataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分季订阅对齐(seasonStartEpisode)后的视图口径:
 * <ul>
 * <li>集数清单/详情分集从季窗口下界起步 —— 季前旧集(1..165)不属于本订阅,不得再顶着
 *     「缺失」的名头出现(线上:一念永恒 sub 66,第 4 季对齐 166 起,旧口径列出 1..173);</li>
 * <li>元数据读取按「TMDB 单季装全剧」(totalSeasons==1)形态回落第 1 季行 —— 第 N 季行只有
 *     剧集级字段,分集标题/播出时间只在与巡检 effectiveMetaSeason 同口径的第 1 季(绝对集号)
 *     行里,详情页单集信息(标题/播出时间/剧照)全靠这次回落。</li>
 * </ul>
 */
class MediaSubscriptionSeasonWindowTest {

    private final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionResourceRepository resourceRepository =
            Mockito.mock(MediaSubscriptionResourceRepository.class);
    private final MediaSubscriptionEpisodeRepository episodeRepository =
            Mockito.mock(MediaSubscriptionEpisodeRepository.class);
    private final MediaSubscriptionEpisodeSourceRepository episodeSourceRepository =
            Mockito.mock(MediaSubscriptionEpisodeSourceRepository.class);
    private final MetadataService metadataService = Mockito.mock(MetadataService.class);
    private final MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);

    private final MediaSubscriptionService service = new MediaSubscriptionService(
            subscriptionRepository, resourceRepository, null, episodeRepository, episodeSourceRepository,
            null, null, null, null, null, metadataService, checkService, null, null,
            new AppProperties(), new ObjectMapper(), null, null, null);

    private MediaSubscription subscription() {
        MediaSubscription sub = new MediaSubscription();
        sub.setId(66);
        sub.setUid(1);
        sub.setName("一念永恒");
        sub.setSeason(4);
        sub.setSeasonStartEpisode(166);
        sub.setMetaProvider("tmdb");
        sub.setMetaId("107371");
        sub.setStatus(MediaSubscription.STATUS_ACTIVE);
        sub.setMountPath("/追剧/一念永恒");
        sub.setOfficialEpisodes(168);
        Mockito.when(subscriptionRepository.findById(66)).thenReturn(Optional.of(sub));
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(66)).thenReturn(List.of(primary()));
        Mockito.when(episodeSourceRepository.findNumberAndSource(66)).thenReturn(List.<Object[]>of(
                new Object[]{166, sourceRow("LISTED")},
                new Object[]{167, sourceRow("LISTED")},
                new Object[]{168, sourceRow("LISTED")}));
        return sub;
    }

    /** 第 4 季行:剧集级字段齐全但没有分集(totalSeasons==1 是形态信号)。 */
    private static MetadataDetails seasonRow() {
        MetadataDetails details = new MetadataDetails();
        details.setName("一念永恒");
        details.setYear("2020");
        details.setTotalSeasons(1);
        details.setStatus(MetadataDetails.STATUS_RETURNING);
        return details;
    }

    /** 第 1 季(全剧)行:绝对集号空间的分集标题/播出时间与总数。 */
    private static MetadataDetails wholeRow() {
        MetadataDetails details = seasonRow();
        long now = System.currentTimeMillis();
        details.setEpisodes(List.of(
                new EpisodeInfo(166, "第166集", now - 3L * 24 * 3600_000),
                new EpisodeInfo(167, "第167集", now - 2L * 24 * 3600_000),
                new EpisodeInfo(168, "第168集", now - 24L * 3600_000),
                new EpisodeInfo(169, "第169集", now + 24L * 3600_000)));
        details.setTotalEpisodes(170);
        details.setAiredEpisodes(168);
        return details;
    }

    @Test
    void episodesListStartsAtSeasonWindow() {
        subscription();

        List<Map<String, Object>> rows = service.episodes(1, 66);

        assertEquals(3, rows.size());
        assertEquals(166, rows.get(0).get("episode"), "清单必须从季起始集号起步");
        assertTrue(rows.stream().allMatch(row -> (boolean) row.get("present")));
        assertEquals("主源", rows.get(0).get("source"));
    }

    @Test
    void detailFallsBackToWholeShowRowForPerEpisodeInfo() {
        subscription();
        when(metadataService.cachedDetails("tmdb", "107371", 4)).thenReturn(seasonRow());
        when(metadataService.cachedDetails("tmdb", "107371", 1)).thenReturn(wholeRow());

        Map<String, Object> result = service.detail(1, 66);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> episodes = (List<Map<String, Object>>) result.get("episodes");
        assertEquals(166, episodes.get(0).get("episode"), "详情分集从季窗口下界起步,季前旧集不出现");
        assertEquals(5, episodes.size(), "166..170(官方总数口径)");
        assertEquals("第166集", episodes.get(0).get("title"));
        assertTrue((boolean) episodes.get(0).get("aired"), "已播分集按播出时间点亮");
        assertTrue((boolean) episodes.get(2).get("present"));
        assertFalse((boolean) episodes.get(3).get("present"), "169 尚未入库");
        assertFalse((boolean) episodes.get(3).get("aired"), "169 未播");
        assertEquals("第169集", episodes.get(3).get("title"), "未播分集也带单集标题(季内日程)");
        verify(metadataService).cachedDetails("tmdb", "107371", 4);
        verify(metadataService).cachedDetails("tmdb", "107371", 1);
    }

    @Test
    void multiSeasonShapeReadsItsOwnSeasonRow() {
        MediaSubscription sub = subscription();
        sub.setSeason(3);
        sub.setSeasonStartEpisode(null);
        sub.setMetaProvider("tmdb");
        sub.setMetaId("42");
        MetadataDetails season3 = seasonRow();
        season3.setTotalSeasons(5); // 真多季:不回落
        when(metadataService.cachedDetails("tmdb", "42", 3)).thenReturn(season3);

        Map<String, Object> result = service.detail(1, 66);

        verify(metadataService).cachedDetails("tmdb", "42", 3);
        verify(metadataService, never()).cachedDetails("tmdb", "42", 1);
        assertEquals("一念永恒", ((Map<?, ?>) result.get("media")).get("name"));
    }

    private static MediaSubscriptionResource primary() {
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(9);
        resource.setSubscriptionId(66);
        resource.setState(MediaSubscriptionResource.STATE_MOUNTED);
        resource.setMountPath("/追剧/一念永恒");
        resource.setTitle("一念永恒 完结季(2026)");
        return resource;
    }

    private static MediaSubscriptionEpisodeSource sourceRow(String state) {
        MediaSubscriptionEpisodeSource row = new MediaSubscriptionEpisodeSource();
        row.setResourceId(9);
        row.setState(state);
        return row;
    }
}
