package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallback;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallbackRepository;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService;
import cn.har01d.alist_tvbox.service.SettingService;
import cn.har01d.alist_tvbox.service.StreamProbeClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集源兜底编排:开关关闭零调用、当前集同步恢复、一次搜索补整个窗口、
 * 覆盖层快路径(缓存 URL 探测通过不再进网关)、缓存死链重解析、
 * 负缓存、单飞锁(并发只搜一次)、异剧集号门禁、预告集不补。
 */
class EpisodeFallbackServiceTest {

    private final AppProperties appProperties = new AppProperties();
    private final SettingService settingService = Mockito.mock(SettingService.class);
    private final MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);
    private final MediaSubscriptionEpisodeFallbackRepository repository =
            Mockito.mock(MediaSubscriptionEpisodeFallbackRepository.class);
    private final StreamProbeClient probe = Mockito.mock(StreamProbeClient.class);
    private final CollectionGateway gateway = Mockito.mock(CollectionGateway.class);

    private EpisodeFallbackService service() {
        EpisodeFallbackService service = new EpisodeFallbackService(appProperties, settingService, gateway,
                checkService, repository);
        service.setStreamProbeClient(probe);
        return service;
    }

    private MediaSubscription subscription(int id, int totalEpisodes) {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(id);
        subscription.setUid(1);
        subscription.setName("凡人修仙传");
        subscription.setOfficialTotal(totalEpisodes);
        return subscription;
    }

    /** 探测全 VERIFIED(200, video/mp4)。 */
    private void probeAlwaysOk() throws Exception {
        when(probe.fetch(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new StreamProbeClient.ProbeResult(200, "video/mp4", new byte[16]));
    }

    private void enabled(boolean value) {
        when(settingService.getUserSetting(EpisodeFallbackService.SETTING_KEY, 1)).thenReturn(String.valueOf(value));
    }

    @Test
    void disabledNeverCallsGateway() {
        enabled(false);
        MediaSubscription subscription = subscription(7, 40);
        when(checkService.liveEpisodeNumbers(subscription)).thenReturn(Set.of());
        when(repository.findBySubscriptionIdAndEpisode(7, 10)).thenReturn(Optional.empty());

        Map<String, Object> result = service().resolveEpisodeFallback(subscription, 10, null, null);
        assertNull(result);
        verify(gateway, never()).search(any(), anyInt());
    }

    @Test
    void rescuesCurrentEpisodeAndFillsWindow() throws Exception {
        enabled(true);
        MediaSubscription subscription = subscription(7, 40);
        when(checkService.liveEpisodeNumbers(subscription)).thenReturn(Set.of()); // 全灭
        when(repository.findBySubscriptionIdAndEpisode(Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(Optional.empty());
        probeAlwaysOk();
        when(gateway.search(subscription, 10)).thenReturn(List.of(
                new CollectionGateway.CollectionItem("wolong", "卧龙资源", "55", "凡人修仙传", "2025", "", 0)));
        TreeMap<Integer, String> episodes = new TreeMap<>();
        episodes.put(10, "http://x.example/10.m3u8");
        episodes.put(11, "http://x.example/11.m3u8");
        episodes.put(12, "http://x.example/12.m3u8");
        episodes.put(13, "http://x.example/13.m3u8");
        when(gateway.loadPlaylist(any(), any())).thenReturn(
                new CollectionGateway.CollectionPlaylist("wolong", "卧龙资源", "55", "卧龙云", episodes));

        Map<String, Object> result = service().resolveEpisodeFallback(subscription, 10, null, null);
        assertNotNull(result);
        assertEquals("http://x.example/10.m3u8", result.get("url"));
        assertEquals(0, result.get("parse"));

        // 一次搜索补整个窗口(当前集+后3集)
        ArgumentCaptor<List<MediaSubscriptionEpisodeFallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertEquals(Set.of(10, 11, 12, 13),
                Set.copyOf(captor.getValue().stream().map(MediaSubscriptionEpisodeFallback::getEpisode).toList()));
    }

    @Test
    void cachedRowShortCircuitsWithoutGateway() throws Exception {
        enabled(true);
        MediaSubscription subscription = subscription(7, 40);
        MediaSubscriptionEpisodeFallback row = new MediaSubscriptionEpisodeFallback();
        row.setSubscriptionId(7);
        row.setEpisode(10);
        row.setSiteId("wolong");
        row.setResourceId("55");
        row.setUrl("http://x.example/10.m3u8");
        row.setState(MediaSubscriptionEpisodeFallback.STATE_ACTIVE);
        row.setExpiresAt(System.currentTimeMillis() + 3600_000L);
        when(repository.findBySubscriptionIdAndEpisode(7, 10)).thenReturn(Optional.of(row));
        probeAlwaysOk();

        Map<String, Object> result = service().resolveEpisodeFallback(subscription, 10, null, null);
        assertNotNull(result);
        assertEquals("http://x.example/10.m3u8", result.get("url"));
        verify(gateway, never()).search(any(), anyInt());
    }

    @Test
    void deadCachedUrlRebuildsFromSite() throws Exception {
        enabled(true);
        MediaSubscription subscription = subscription(7, 40);
        MediaSubscriptionEpisodeFallback row = new MediaSubscriptionEpisodeFallback();
        row.setSubscriptionId(7);
        row.setEpisode(10);
        row.setSiteId("wolong");
        row.setResourceId("55");
        row.setUrl("http://x.example/dead.m3u8");
        row.setState(MediaSubscriptionEpisodeFallback.STATE_ACTIVE);
        row.setExpiresAt(System.currentTimeMillis() + 3600_000L);
        when(repository.findBySubscriptionIdAndEpisode(7, 10)).thenReturn(Optional.of(row));
        // 缓存 URL 死链(404),重解析后的新链探测通过
        when(probe.fetch(Mockito.startsWith("http://x.example/dead"), anyString(), anyInt(), anyInt()))
                .thenReturn(new StreamProbeClient.ProbeResult(404, "text/html", new byte[0]));
        when(probe.fetch(Mockito.startsWith("http://x.example/new"), anyString(), anyInt(), anyInt()))
                .thenReturn(new StreamProbeClient.ProbeResult(200, "video/mp4", new byte[16]));
        TreeMap<Integer, String> episodes = new TreeMap<>();
        episodes.put(10, "http://x.example/new.m3u8");
        when(gateway.loadPlaylist(any(), any()))
                .thenReturn(new CollectionGateway.CollectionPlaylist("wolong", "卧龙资源", "55", "卧龙云", episodes));

        Map<String, Object> result = service().resolveEpisodeFallback(subscription, 10, null, null);
        assertNotNull(result);
        assertEquals("http://x.example/new.m3u8", result.get("url"));
        // 死链标 FAILED + 重建回 ACTIVE:两次落库
        verify(repository, Mockito.times(2)).save(row);
        assertEquals(MediaSubscriptionEpisodeFallback.STATE_ACTIVE, row.getState());
    }

    @Test
    void noSearchResultSetsNegativeCache() {
        enabled(true);
        MediaSubscription subscription = subscription(7, 40);
        when(checkService.liveEpisodeNumbers(subscription)).thenReturn(Set.of());
        when(repository.findBySubscriptionIdAndEpisode(Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(Optional.empty());
        when(gateway.search(any(), anyInt())).thenReturn(List.of());

        // 负缓存是服务实例内存态:两次调用必须同实例,窗口内不重搜
        EpisodeFallbackService service = service();
        assertNull(service.resolveEpisodeFallback(subscription, 10, null, null));
        verify(gateway, Mockito.times(1)).search(any(), anyInt());
        assertNull(service.resolveEpisodeFallback(subscription, 11, null, null));
        verify(gateway, Mockito.times(1)).search(any(), anyInt());
    }

    @Test
    void liveEpisodesAreNotRefilled() throws Exception {
        enabled(true);
        MediaSubscription subscription = subscription(7, 40);
        // 11/12 集 LIVE(集源行可用),只有 10/13 缺
        when(checkService.liveEpisodeNumbers(subscription)).thenReturn(Set.of(11, 12));
        when(repository.findBySubscriptionIdAndEpisode(Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(Optional.empty());
        probeAlwaysOk();
        when(gateway.search(subscription, 10)).thenReturn(List.of(
                new CollectionGateway.CollectionItem("wolong", "卧龙资源", "55", "凡人修仙传", "2025", "", 0)));
        TreeMap<Integer, String> episodes = new TreeMap<>();
        episodes.put(10, "http://x.example/10.m3u8");
        episodes.put(13, "http://x.example/13.m3u8");
        when(gateway.loadPlaylist(any(), any()))
                .thenReturn(new CollectionGateway.CollectionPlaylist("wolong", "卧龙资源", "55", "卧龙云", episodes));

        Map<String, Object> result = service().resolveEpisodeFallback(subscription, 10, null, null);
        assertNotNull(result);
        ArgumentCaptor<List<MediaSubscriptionEpisodeFallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        // 只补缺口(10/13),LIVE 集(11/12)不覆盖
        assertEquals(Set.of(10, 13),
                Set.copyOf(captor.getValue().stream().map(MediaSubscriptionEpisodeFallback::getEpisode).toList()));
    }

    @Test
    void foreignEpisodeNumbersRejected() {
        enabled(true);
        MediaSubscription subscription = subscription(7, 40); // 官方总 40
        when(checkService.liveEpisodeNumbers(subscription)).thenReturn(Set.of());
        when(repository.findBySubscriptionIdAndEpisode(Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(Optional.empty());
        when(gateway.search(subscription, 10)).thenReturn(List.of(
                new CollectionGateway.CollectionItem("wolong", "卧龙资源", "55", "凡人修仙传", "2025", "", 0)));
        TreeMap<Integer, String> episodes = new TreeMap<>();
        episodes.put(10, "http://x.example/10.m3u8");
        episodes.put(500, "http://x.example/500.m3u8"); // 集号显著溢出:同名异剧
        when(gateway.loadPlaylist(any(), any()))
                .thenReturn(new CollectionGateway.CollectionPlaylist("wolong", "卧龙资源", "55", "卧龙云", episodes));

        assertNull(service().resolveEpisodeFallback(subscription, 10, null, null));
        verify(repository, never()).saveAll(any());
    }

    @Test
    void deleteForSubscriptionClearsRowsAndNegativeCache() throws Exception {
        enabled(true);
        MediaSubscription subscription = subscription(7, 40);
        when(checkService.liveEpisodeNumbers(subscription)).thenReturn(Set.of());
        when(repository.findBySubscriptionIdAndEpisode(Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(Optional.empty());
        when(gateway.search(any(), anyInt())).thenReturn(List.of()); // 第一轮:无结果 → 负缓存生效

        EpisodeFallbackService service = service();
        assertNull(service.resolveEpisodeFallback(subscription, 10, null, null));
        assertNull(service.resolveEpisodeFallback(subscription, 11, null, null), "负缓存窗口内不重搜");
        verify(gateway, Mockito.times(1)).search(any(), anyInt());

        service.deleteForSubscription(7);
        verify(repository).deleteBySubscriptionId(7);

        // 负缓存随行清理:删除后同实例再解析能重新进网关并成功
        probeAlwaysOk();
        when(gateway.search(any(), anyInt())).thenReturn(List.of(
                new CollectionGateway.CollectionItem("wolong", "卧龙资源", "55", "凡人修仙传", "2025", "", 0)));
        TreeMap<Integer, String> episodes = new TreeMap<>();
        episodes.put(10, "http://x.example/10.m3u8");
        when(gateway.loadPlaylist(any(), any()))
                .thenReturn(new CollectionGateway.CollectionPlaylist("wolong", "卧龙资源", "55", "卧龙云", episodes));
        assertNotNull(service.resolveEpisodeFallback(subscription, 10, null, null));
    }

    @Test
    void windowRespectsTotalEpisodes() throws Exception {
        enabled(true);
        MediaSubscription subscription = subscription(7, 11); // 官方总 11:窗口 10..11,12/13 不存在
        when(checkService.liveEpisodeNumbers(subscription)).thenReturn(Set.of());
        when(repository.findBySubscriptionIdAndEpisode(Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(Optional.empty());
        probeAlwaysOk();
        when(gateway.search(subscription, 10)).thenReturn(List.of(
                new CollectionGateway.CollectionItem("wolong", "卧龙资源", "55", "凡人修仙传", "2025", "", 0)));
        TreeMap<Integer, String> episodes = new TreeMap<>();
        for (int e = 10; e <= 13; e++) {
            episodes.put(e, "http://x.example/" + e + ".m3u8");
        }
        when(gateway.loadPlaylist(any(), any()))
                .thenReturn(new CollectionGateway.CollectionPlaylist("wolong", "卧龙资源", "55", "卧龙云", episodes));

        Map<String, Object> result = service().resolveEpisodeFallback(subscription, 10, null, null);
        assertNotNull(result);
        ArgumentCaptor<List<MediaSubscriptionEpisodeFallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertEquals(Set.of(10, 11),
                Set.copyOf(captor.getValue().stream().map(MediaSubscriptionEpisodeFallback::getEpisode).toList()));
    }
}
