package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEventRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.metadata.MetadataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TVBox「追更」动作的幂等:同名订阅 create 已复用既有行,带 link 的资源入池同样要幂等 ——
 * 重试/另一入口重复 follow 时再插一行会撞 (subscription_id, link) 唯一索引把整个事务打挂。
 */
class MediaSubscriptionFollowTest {

    private final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
    private final MediaSubscriptionEpisodeSourceRepository episodeSourceRepository = Mockito.mock(MediaSubscriptionEpisodeSourceRepository.class);
    private final MovieRepository movieRepository = Mockito.mock(MovieRepository.class);
    private final MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);
    private final MediaSubscriptionService service = new MediaSubscriptionService(
            subscriptionRepository, resourceRepository, null, null, episodeSourceRepository, null, movieRepository, null, null, null, null,
            checkService, null, null, new AppProperties(), new ObjectMapper(), null, null);

    private MediaSubscription subscription() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(3);
        subscription.setUid(1);
        subscription.setName("测试剧");
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        return subscription;
    }

    @Test
    void repeatedFollowReusesExistingResourceAsNoOp() {
        when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription()));
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(9);
        resource.setSubscriptionId(3);
        resource.setLink("https://pan.example/s/abc");
        resource.setState(MediaSubscriptionResource.STATE_MOUNTED);
        when(resourceRepository.findBySubscriptionIdAndLink(3, "https://pan.example/s/abc"))
                .thenReturn(Optional.of(resource));

        Map<String, Object> result = service.handleAction(1, "follow",
                Map.of("name", "测试剧", "link", "https://pan.example/s/abc"));

        assertEquals(3, result.get("id"));
        verify(resourceRepository, never()).save(any());
        verify(checkService, never()).activateAsync(anyInt(), anyInt(), anyInt());
    }

    @Test
    void firstFollowSavesCandidateResourceAndActivates() {
        when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription()));
        when(resourceRepository.findBySubscriptionIdAndLink(3, "https://pan.example/s/abc"))
                .thenReturn(Optional.empty());
        when(resourceRepository.save(any(MediaSubscriptionResource.class))).thenAnswer(invocation -> {
            MediaSubscriptionResource saved = invocation.getArgument(0);
            saved.setId(11);
            return saved;
        });

        Map<String, Object> result = service.handleAction(1, "follow",
                Map.of("name", "测试剧", "link", "https://pan.example/s/abc"));

        assertEquals(3, result.get("id"));
        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository).save(captor.capture());
        MediaSubscriptionResource saved = captor.getValue();
        assertEquals(3, saved.getSubscriptionId());
        assertEquals("https://pan.example/s/abc", saved.getLink());
        assertEquals(1000, saved.getScore()); // 订阅即所见:当前源优先
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, saved.getState());
        verify(checkService).activateAsync(1, 3, 11);
    }

    @Test
    void followWithoutLinkJustRunsFirstCheck() {
        when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription()));

        service.handleAction(1, "follow", Map.of("name", "测试剧"));

        verify(resourceRepository, never()).save(any());
        verify(checkService).checkAsync(1, 3);
    }

    // ---------- 换季重置(2026-08-24):《末日地堡》第1季改第3季后点检查,候选/挂载/集数仍是第一季口径 ----------
    // 旧季的集源行继续冒领集号(computeMissing 判"已齐"→永不搜索新季),明标旧季候选永久躺在池里。
    // 季号一变即整体清空,首轮巡检按新季重搜重挂。

    @Test
    void seasonChangeResetsPoolAndTriggersRescan() {
        ShareService shareService = Mockito.mock(ShareService.class);
        when(checkService.allowedCandidateDrives(any())).thenReturn(java.util.Set.of());
        MediaSubscriptionService resetService = new MediaSubscriptionService(
                subscriptionRepository, resourceRepository,
                Mockito.mock(MediaSubscriptionEventRepository.class),
                Mockito.mock(MediaSubscriptionEpisodeRepository.class),
                Mockito.mock(MediaSubscriptionEpisodeSourceRepository.class),
                null, null, null, null, shareService, null, checkService, null, null,
                new AppProperties(), new ObjectMapper(), null, null);
        MediaSubscription subscription = subscription();
        subscription.setSeason(1);
        subscription.setShareId(5);
        subscription.setMountPath("/追剧/3-测试剧");
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription));
        MediaSubscriptionResource primary = new MediaSubscriptionResource();
        primary.setId(21);
        primary.setShareId(51);
        MediaSubscriptionResource aux = new MediaSubscriptionResource();
        aux.setId(22);
        aux.setShareId(52);
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(3)).thenReturn(List.of(primary, aux));

        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setSeason(3);
        resetService.update(1, 3, request);

        assertEquals(3, subscription.getSeason());
        // 重置细节(删行/重置快照/事件)委托 checkService.resetInventoryForSeason —— 行为由 CheckServiceTest 覆盖
        verify(checkService).resetInventoryForSeason(subscription, 3);
        // 无事务上下文:远程卸载与重搜同步执行(订阅 shareId 5 + 资源行 51/52 去重)
        verify(shareService).deleteShare(5);
        verify(shareService).deleteShare(51);
        verify(shareService).deleteShare(52);
        verify(checkService).checkAsync(1, 3);
    }

    @Test
    void sameSeasonUpdateSkipsReset() {
        when(checkService.allowedCandidateDrives(any())).thenReturn(java.util.Set.of());
        MediaSubscription subscription = subscription();
        subscription.setSeason(1);
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription));

        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setSeason(1);
        service.update(1, 3, request);

        verify(resourceRepository, never()).deleteBySubscriptionId(anyInt());
        verify(checkService, never()).checkAsync(anyInt(), anyInt());
    }

    @Test
    void metadataBindingUpdateSchedulesCrossSourcePrewarm() {
        MediaSubscription subscription = subscription();
        subscription.setMetaProvider("douban");
        subscription.setMetaId("36245887");
        subscription.setDoubanId(36245887);
        subscription.setCoverUrl("https://img9.doubanio.com/old.jpg");
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription));

        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setMetaProvider("tmdb");
        request.setMetaId("233295");
        service.update(1, 3, request);

        assertEquals("tmdb", subscription.getMetaProvider());
        assertEquals("233295", subscription.getMetaId());
        assertEquals(null, subscription.getDoubanId(), "切源先清旧豆瓣绑定,由异步元数据桥重新绑定");
        assertEquals(null, subscription.getCoverUrl());
        verify(checkService).prewarmCoverAsync(subscription);
    }

    @Test
    void unchangedMetadataBindingKeepsCoverAndSkipsPrewarm() {
        MediaSubscription subscription = subscription();
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("233295");
        subscription.setDoubanId(36245887);
        subscription.setCoverUrl("https://media.themoviedb.org/poster.jpg");
        subscription.setCoverFallbackUrl("https://img9.doubanio.com/poster.jpg");
        subscription.setMetaSyncTime(123L);
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription));

        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setName("测试剧");
        request.setMetaProvider("tmdb");
        request.setMetaId("233295");
        request.setDoubanId(36245887);
        service.update(1, 3, request);

        assertEquals("https://media.themoviedb.org/poster.jpg", subscription.getCoverUrl());
        assertEquals("https://img9.doubanio.com/poster.jpg", subscription.getCoverFallbackUrl());
        assertEquals(123L, subscription.getMetaSyncTime());
        verify(checkService, never()).prewarmCoverAsync(any());
    }

    @Test
    void providerOnlyChangeIsRejectedWithoutMutatingSubscription() {
        MediaSubscription subscription = subscription();
        subscription.setMetaProvider("douban");
        subscription.setMetaId("36245887");
        subscription.setDoubanId(36245887);
        subscription.setCoverUrl("https://img9.doubanio.com/old.jpg");
        subscription.setCoverFallbackUrl("https://media.themoviedb.org/old.jpg");
        subscription.setMetaSyncTime(123L);
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription));

        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setMetaProvider("tmdb");
        assertThrows(BadRequestException.class, () -> service.update(1, 3, request));

        assertEquals("douban", subscription.getMetaProvider());
        assertEquals("36245887", subscription.getMetaId());
        assertEquals(36245887, subscription.getDoubanId());
        assertEquals("https://img9.doubanio.com/old.jpg", subscription.getCoverUrl());
        assertEquals("https://media.themoviedb.org/old.jpg", subscription.getCoverFallbackUrl());
        assertEquals(123L, subscription.getMetaSyncTime());
        verify(checkService, never()).prewarmCoverAsync(any());
    }

    @Test
    void changingDoubanIdClearsStaleFallbackCover() {
        MediaSubscription subscription = subscription();
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("233295");
        subscription.setDoubanId(36245887);
        subscription.setCoverFallbackUrl("https://img9.doubanio.com/old.jpg");
        subscription.setMetaSyncTime(123L);
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription));

        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setDoubanId(37464007);
        service.update(1, 3, request);

        assertEquals(37464007, subscription.getDoubanId());
        assertEquals(null, subscription.getCoverFallbackUrl());
        assertEquals(null, subscription.getMetaSyncTime());
        verify(checkService).prewarmCoverAsync(subscription);
    }

    @Test
    void doubanProviderRejectsConflictingManualDoubanId() {
        MediaSubscription subscription = subscription();
        subscription.setMetaProvider("douban");
        subscription.setMetaId("36245887");
        subscription.setDoubanId(36245887);
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription));

        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setDoubanId(37464007);

        assertThrows(BadRequestException.class, () -> service.update(1, 3, request));
        assertEquals(36245887, subscription.getDoubanId());
        verify(checkService, never()).prewarmCoverAsync(any());
    }

    @Test
    void doubanProviderKeepsIdentityWhenDoubanIdIsExplicitlyNull() throws Exception {
        MediaSubscription subscription = subscription();
        subscription.setMetaProvider("douban");
        subscription.setMetaId("36245887");
        subscription.setDoubanId(36245887);
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription));
        MediaSubscriptionRequest request = new ObjectMapper().readValue(
                "{\"doubanId\":null}", MediaSubscriptionRequest.class);

        service.update(1, 3, request);

        assertEquals(36245887, subscription.getDoubanId());
        verify(checkService, never()).prewarmCoverAsync(any());
    }

    @Test
    void explicitJsonNullClearsMetadataBindingAndCovers() throws Exception {
        MediaSubscription subscription = subscription();
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("233295");
        subscription.setDoubanId(36245887);
        subscription.setCoverUrl("https://media.themoviedb.org/old.jpg");
        subscription.setCoverFallbackUrl("https://img9.doubanio.com/old.jpg");
        subscription.setMetaSyncTime(123L);
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription));
        MediaSubscriptionRequest request = new ObjectMapper().readValue(
                "{\"metaProvider\":null,\"metaId\":null,\"doubanId\":null}", MediaSubscriptionRequest.class);
        assertTrue(request.isMetaProviderSet());
        assertTrue(request.isMetaIdSet());
        assertTrue(request.isDoubanIdSet());

        service.update(1, 3, request);

        assertEquals(null, subscription.getMetaProvider());
        assertEquals(null, subscription.getMetaId());
        assertEquals(null, subscription.getDoubanId());
        assertEquals(null, subscription.getCoverUrl());
        assertEquals(null, subscription.getCoverFallbackUrl());
        assertEquals(null, subscription.getMetaSyncTime());
        verify(checkService, never()).prewarmCoverAsync(any());
    }

    @Test
    void createRejectsPartialMetadataBindings() {
        MediaSubscriptionRequest providerOnly = new MediaSubscriptionRequest();
        providerOnly.setName("测试剧");
        providerOnly.setMetaProvider("tmdb");
        assertThrows(BadRequestException.class, () -> service.create(1, providerOnly));

        MediaSubscriptionRequest idOnly = new MediaSubscriptionRequest();
        idOnly.setName("测试剧");
        idOnly.setMetaId("233295");
        assertThrows(BadRequestException.class, () -> service.create(1, idOnly));

        MediaSubscriptionRequest conflictingFallback = new MediaSubscriptionRequest();
        conflictingFallback.setName("测试剧");
        conflictingFallback.setMetaProvider("tmdb");
        conflictingFallback.setDoubanId(36245887);
        assertThrows(BadRequestException.class, () -> service.create(1, conflictingFallback));
        verify(subscriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void repeatedCreateRepairsHistoricalPartialMetadataBinding() {
        MediaSubscription subscription = subscription();
        subscription.setSeason(1);
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId(null);
        when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setName("测试剧");
        request.setSeason(1);
        request.setMetaProvider("tmdb");
        request.setMetaId("233295");

        service.create(1, request);

        assertEquals("tmdb", subscription.getMetaProvider());
        assertEquals("233295", subscription.getMetaId());
        verify(subscriptionRepository).save(subscription);
        verify(checkService).prewarmCoverAsync(subscription);
    }

    @Test
    void repeatedCreateEnrichesExistingTitleOnlySubscription() {
        MediaSubscription subscription = subscription();
        subscription.setSeason(1);
        when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));

        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setName("测试剧");
        request.setSeason(1);
        request.setMetaProvider("tmdb");
        request.setMetaId("233295");
        service.create(1, request);

        assertEquals("tmdb", subscription.getMetaProvider());
        assertEquals("233295", subscription.getMetaId());
        verify(subscriptionRepository).save(subscription);
        verify(checkService).prewarmCoverAsync(subscription);
        verify(subscriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void listPrewarmsTmdbSubscriptionMissingFallbackEvenWhenDoubanIdExists() {
        MediaSubscription subscription = subscription();
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("233295");
        subscription.setDoubanId(36245887);
        subscription.setCoverUrl("https://media.themoviedb.org/poster.jpg");
        subscription.setMetaSyncTime(null);
        when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(3)).thenReturn(List.of());
        when(checkService.allowedCandidateDrives(any())).thenReturn(java.util.Set.of());

        service.list(1);

        verify(checkService).prewarmCoverAsync(subscription);
    }

    @Test
    void listDtoUsesFallbackWhenPrimaryCoverIsMissing() {
        MediaSubscription subscription = subscription();
        String fallback = "https://img9.doubanio.com/view/photo/l/public/p1.jpg";
        subscription.setCoverFallbackUrl(fallback);
        when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription));
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(3)).thenReturn(List.of());
        when(checkService.allowedCandidateDrives(any())).thenReturn(java.util.Set.of());

        String cover = service.list(1).getFirst().getCover();

        assertEquals("/images?url="
                + java.net.URLEncoder.encode(fallback, java.nio.charset.StandardCharsets.UTF_8), cover);
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailUsesExternalFallbackWhenMetadataPrimaryCoverIsMissing() {
        MediaSubscription subscription = subscription();
        subscription.setSeason(1);
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("233295");
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription));
        MetadataService metadataService = Mockito.mock(MetadataService.class);
        MetadataDetails details = new MetadataDetails();
        details.setProvider("tmdb");
        details.setId("233295");
        String fallback = "https://img9.doubanio.com/view/photo/l/public/p1.jpg";
        details.setExternalCovers(new java.util.LinkedHashMap<>(Map.of("douban", fallback)));
        when(metadataService.cachedDetails("tmdb", "233295", 1)).thenReturn(details);
        MediaSubscriptionService detailService = new MediaSubscriptionService(
                subscriptionRepository, resourceRepository,
                Mockito.mock(MediaSubscriptionEventRepository.class),
                Mockito.mock(MediaSubscriptionEpisodeRepository.class), episodeSourceRepository,
                null, movieRepository, null, null, null, metadataService, checkService,
                null, null, new AppProperties(), new ObjectMapper(), null, null);

        Map<String, Object> media = (Map<String, Object>) detailService.detail(1, 3).get("media");

        assertEquals("/images?url="
                + java.net.URLEncoder.encode(fallback, java.nio.charset.StandardCharsets.UTF_8), media.get("cover"));
        verify(checkService).prewarmCoverAsync(subscription);
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailRejectsFallbackMappedToAnotherDoubanIdentity() {
        MediaSubscription subscription = subscription();
        subscription.setSeason(1);
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("233295");
        subscription.setDoubanId(222);
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription));
        MetadataService metadataService = Mockito.mock(MetadataService.class);
        MetadataDetails details = new MetadataDetails();
        details.setProvider("tmdb");
        details.setId("233295");
        details.setExternalIds(Map.of("douban", "111"));
        details.setExternalCovers(Map.of("douban", "https://img.example/douban-111.jpg"));
        when(metadataService.cachedDetails("tmdb", "233295", 1)).thenReturn(details);
        MediaSubscriptionService detailService = new MediaSubscriptionService(
                subscriptionRepository, resourceRepository,
                Mockito.mock(MediaSubscriptionEventRepository.class),
                Mockito.mock(MediaSubscriptionEpisodeRepository.class), episodeSourceRepository,
                null, movieRepository, null, null, null, metadataService, checkService,
                null, null, new AppProperties(), new ObjectMapper(), null, null);

        Map<String, Object> media = (Map<String, Object>) detailService.detail(1, 3).get("media");

        assertEquals(null, media.get("cover"));
    }
}
