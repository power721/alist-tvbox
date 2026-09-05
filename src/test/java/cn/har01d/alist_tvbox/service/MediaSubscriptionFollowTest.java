package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEventRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private final cn.har01d.alist_tvbox.entity.UserPreferenceRepository preferenceRepository = Mockito.mock(cn.har01d.alist_tvbox.entity.UserPreferenceRepository.class);
    private final MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);
    private final MediaSubscriptionService service = new MediaSubscriptionService(
            subscriptionRepository, resourceRepository, null, null, episodeSourceRepository, preferenceRepository, null, null, null, null, null,
            checkService, null, null, new AppProperties(), new ObjectMapper(), null, null, null);

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
                new AppProperties(), new ObjectMapper(), null, null, null);
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

    // ---------- 片单一键追更的语义去重(2026-08-30):web TMDB 订「末日地堡」S3 与豆瓣片单
    // 「末日地堡 第三季」裸名同为「末日地堡」且季号一致,精确名匹配会开出第二条重复订阅

    @Test
    void createReusesSeasonSuffixedSameShowSubscription() {
        MediaSubscription existing = subscription();
        existing.setName("末日地堡");
        existing.setSeason(3);
        when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(existing));

        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setName("末日地堡 第三季");
        var dto = service.create(1, request);

        assertEquals(3, dto.getId());
        verify(subscriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createKeepsDifferentSeasonOfSameShowSeparate() {
        MediaSubscription existing = subscription();
        existing.setName("末日地堡");
        existing.setSeason(3);
        when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(existing));
        when(subscriptionRepository.saveAndFlush(any(MediaSubscription.class))).thenAnswer(invocation -> {
            MediaSubscription saved = invocation.getArgument(0);
            saved.setId(99);
            return saved;
        });

        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setName("末日地堡 第一季");
        var dto = service.create(1, request);

        assertEquals(99, dto.getId()); // S1 不复用 S3 行
    }

    // ---------- 手动添加候选资源(2026-09-01):用户反馈"一启用就变成主资源" ----------
    // 添加与启用是两个动作:粘贴链接只入候选池(不挂载不动主源),想立即挂载再点「启用」。

    private MediaSubscriptionService manualService(ShareService shareService,
                                                   MediaSubscriptionEventRepository eventRepository) {
        return new MediaSubscriptionService(
                subscriptionRepository, resourceRepository, eventRepository,
                Mockito.mock(MediaSubscriptionEpisodeRepository.class),
                episodeSourceRepository, preferenceRepository, null, null, null,
                shareService, null, checkService, null, null,
                new AppProperties(), new ObjectMapper(), null, null, null);
    }

    private cn.har01d.alist_tvbox.entity.Share quarkShare() {
        cn.har01d.alist_tvbox.entity.Share probe = new cn.har01d.alist_tvbox.entity.Share();
        probe.setType(5); // 夸克
        return probe;
    }

    @Test
    void addResourceSavesManualCandidateWithoutActivating() {
        ShareService shareService = Mockito.mock(ShareService.class);
        MediaSubscriptionEventRepository eventRepository = Mockito.mock(MediaSubscriptionEventRepository.class);
        MediaSubscriptionService manual = manualService(shareService, eventRepository);
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription()));
        when(resourceRepository.findBySubscriptionIdAndLink(3, "https://pan.quark.cn/s/abc"))
                .thenReturn(Optional.empty());
        when(shareService.parseShareLink("https://pan.quark.cn/s/abc")).thenReturn(quarkShare());
        when(resourceRepository.save(any(MediaSubscriptionResource.class))).thenAnswer(invocation -> {
            MediaSubscriptionResource saved = invocation.getArgument(0);
            saved.setId(31);
            return saved;
        });

        Map<String, Object> result = manual.addResource(1, 3, " https://pan.quark.cn/s/abc ", "1a2b");

        assertEquals(31, result.get("resourceId"));
        assertEquals(false, result.get("existed"));
        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository).save(captor.capture());
        MediaSubscriptionResource saved = captor.getValue();
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, saved.getState());
        assertEquals(MediaSubscriptionResource.SOURCE_MANUAL, saved.getSource());
        assertEquals(5, saved.getType());
        assertEquals(1000, saved.getScore()); // 手动源候选序置顶(同 follow「订阅即所见」档)
        assertEquals("1a2b", saved.getPassword());
        verify(checkService, never()).activateAsync(anyInt(), anyInt(), anyInt()); // 关键:不转主源
        verify(checkService, never()).checkAsync(anyInt(), anyInt());
    }

    @Test
    void addResourceReusesExistingCandidateAndOnlyUpdatesPassword() {
        ShareService shareService = Mockito.mock(ShareService.class);
        MediaSubscriptionEventRepository eventRepository = Mockito.mock(MediaSubscriptionEventRepository.class);
        MediaSubscriptionService manual = manualService(shareService, eventRepository);
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription()));
        MediaSubscriptionResource existing = new MediaSubscriptionResource();
        existing.setId(9);
        existing.setSubscriptionId(3);
        existing.setLink("https://pan.quark.cn/s/abc");
        existing.setScore(40);
        existing.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        when(resourceRepository.findBySubscriptionIdAndLink(3, "https://pan.quark.cn/s/abc"))
                .thenReturn(Optional.of(existing));
        when(shareService.parseShareLink("https://pan.quark.cn/s/abc")).thenReturn(quarkShare());

        Map<String, Object> result = manual.addResource(1, 3, "https://pan.quark.cn/s/abc", "x9y8");

        assertEquals(true, result.get("existed"));
        assertEquals(40, existing.getScore()); // 已在池:打分/状态一概不动
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, existing.getState());
        assertEquals("x9y8", existing.getPassword());
        verify(resourceRepository).save(existing);
    }

    @Test
    void addResourceRevivesRemovedTombstone() {
        ShareService shareService = Mockito.mock(ShareService.class);
        MediaSubscriptionEventRepository eventRepository = Mockito.mock(MediaSubscriptionEventRepository.class);
        MediaSubscriptionService manual = manualService(shareService, eventRepository);
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription()));
        MediaSubscriptionResource tomb = new MediaSubscriptionResource();
        tomb.setId(10);
        tomb.setSubscriptionId(3);
        tomb.setLink("https://pan.quark.cn/s/abc");
        tomb.setState(MediaSubscriptionResource.STATE_REMOVED);
        tomb.setCheckedTime(1L);
        when(resourceRepository.findBySubscriptionIdAndLink(3, "https://pan.quark.cn/s/abc"))
                .thenReturn(Optional.of(tomb));
        when(shareService.parseShareLink("https://pan.quark.cn/s/abc")).thenReturn(quarkShare());

        Map<String, Object> result = manual.addResource(1, 3, "https://pan.quark.cn/s/abc", null);

        assertEquals(true, result.get("revived"));
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, tomb.getState());
        assertNull(tomb.getCheckedTime(), "清冷却计时:手动加回的源下轮巡检即可重探");
    }

    @Test
    void addResourceRejectsUnrecognizedLink() {
        ShareService shareService = Mockito.mock(ShareService.class);
        MediaSubscriptionEventRepository eventRepository = Mockito.mock(MediaSubscriptionEventRepository.class);
        MediaSubscriptionService manual = manualService(shareService, eventRepository);
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription()));
        when(shareService.parseShareLink("https://example.com/nope")).thenReturn(null);

        assertThrows(cn.har01d.alist_tvbox.exception.BadRequestException.class,
                () -> manual.addResource(1, 3, "https://example.com/nope", null));
        verify(resourceRepository, never()).save(any());
    }
}
