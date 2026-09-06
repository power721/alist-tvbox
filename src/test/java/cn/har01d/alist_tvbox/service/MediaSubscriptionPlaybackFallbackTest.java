package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.sitesearch.EpisodeFallbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 播放链路采集源兜底的介入时机:零候选缺集(无转存文件、无集源行,attempted 不涨)
 * 也必须走兜底 —— 这正是兜底要恢复的形态之一;兜底无果才抛错。
 * 当前集从真源正常起播时同样要触发窗口补齐(当前集+后3集,类注释口径:
 * 「当前集正常时后台补齐不阻塞起播」—— 线上:海贼王 837 集无源,播 836 后兜底毫无动作)。
 */
class MediaSubscriptionPlaybackFallbackTest {

    private final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);
    private final EpisodeFallbackService episodeFallbackService = Mockito.mock(EpisodeFallbackService.class);
    private final TvBoxService tvBoxService = Mockito.mock(TvBoxService.class);

    private final MediaSubscriptionService service = new MediaSubscriptionService(
            subscriptionRepository, Mockito.mock(MediaSubscriptionResourceRepository.class), null, null,
            Mockito.mock(MediaSubscriptionEpisodeSourceRepository.class),
            null, null, null, tvBoxService, null, null, checkService, null, null,
            new AppProperties(), new ObjectMapper(), null, null, episodeFallbackService);

    private final MediaSubscription subscription = new MediaSubscription();

    @BeforeEach
    void setUp() {
        subscription.setId(7);
        subscription.setUid(1);
        subscription.setName("测试剧");
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        Mockito.when(subscriptionRepository.findById(7)).thenReturn(Optional.of(subscription));
        // 零候选:playCandidates 默认空桩,mode 非转存不列转存盘 → attempted == 0
    }

    @Test
    void zeroCandidateEpisodeStillInvokesCollectionFallback() {
        Mockito.when(episodeFallbackService.resolveEpisodeFallback(subscription, 10, null, null))
                .thenReturn(Map.of("parse", 0, "url", "http://x.example/10.m3u8"));

        Map<String, Object> result = service.playEpisode(1, 7, 10, null, null);

        assertEquals("http://x.example/10.m3u8", result.get("url"));
        verify(episodeFallbackService).resolveEpisodeFallback(subscription, 10, null, null);
        verify(episodeFallbackService).fillWindowAsync(1, 7, 10);
        // 零候选不算"源播放失败":不打播放失败标(没有可归咎的源)
        verify(checkService, never()).markPlaybackFailure(anyInt());
    }

    @Test
    void zeroCandidateWithoutFallbackResultStillThrows() {
        Mockito.when(episodeFallbackService.resolveEpisodeFallback(subscription, 10, null, null))
                .thenReturn(null);

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.playEpisode(1, 7, 10, null, null));
        assertTrue(error.getMessage().contains("已尝试 0 个源"), error.getMessage());
        verify(episodeFallbackService).resolveEpisodeFallback(subscription, 10, null, null);
    }

    @Test
    void normalPlaybackKicksCollectionWindowFill() {
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(3);
        resource.setState(MediaSubscriptionResource.STATE_MOUNTED);
        resource.setMountPath("/追剧/7-测试剧");
        MediaSubscriptionEpisodeSource source = new MediaSubscriptionEpisodeSource();
        source.setId(30);
        source.setResourceId(3);
        source.setRelPath("10.mp4");
        source.setState(MediaSubscriptionEpisodeSource.STATE_LISTED);
        when(checkService.playCandidates(subscription, 10))
                .thenReturn(List.of(new MediaSubscriptionCheckService.PlayCandidate(resource, source)));
        when(tvBoxService.getPlayUrl(1, "/追剧/7-测试剧/10.mp4", false, null, null))
                .thenReturn(Map.of("url", "http://pan.example/10.mp4"));

        Map<String, Object> result = service.playEpisode(1, 7, 10, null, null);

        assertEquals("http://pan.example/10.mp4", result.get("url"));
        // 真源起播:不走同步兜底,但后台窗口补齐(当前集+后3集缺口)必须触发 —— 后一集是洞时提前垫好
        verify(episodeFallbackService, never()).resolveEpisodeFallback(Mockito.any(), anyInt(), Mockito.any(), Mockito.any());
        verify(episodeFallbackService).fillWindowAsync(1, 7, 10);
        verify(checkService).preheatAheadAsync(1, 7, 10);
        verify(checkService).recordPlaySuccess(source);
    }
}
