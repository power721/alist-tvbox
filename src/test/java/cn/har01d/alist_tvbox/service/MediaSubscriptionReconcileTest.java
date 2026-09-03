package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisode;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集源行集号重算自愈(reconcileEpisodeRows):集号解析口径升级后,存量 LISTED 行的假集号
 * (60fps→60、「贴贴188男大」→188)不会因补缺探测的「已探测过跳过」而消失 —— 假集号推高
 * 观测上限、缺口雪崩。按当前口径重算,非正片词命中或集号变化的行删除,合法行不动。
 */
class MediaSubscriptionReconcileTest {

    private final AppProperties appProperties = new AppProperties();
    private final MediaSubscriptionEpisodeRepository episodeRepository =
            Mockito.mock(MediaSubscriptionEpisodeRepository.class);
    private final MediaSubscriptionEpisodeSourceRepository episodeSourceRepository =
            Mockito.mock(MediaSubscriptionEpisodeSourceRepository.class);

    private final MediaSubscriptionCheckService service = new MediaSubscriptionCheckService(
            null, null, null, episodeRepository, episodeSourceRepository,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            appProperties, new ObjectMapper(), null, null);

    @BeforeEach
    void setUp() {
        appProperties.getSubscription().setPrimeCheckTimes(List.of());
        appProperties.getSubscription().setNightCheckTimes(List.of());
    }

    private MediaSubscription subscription() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(69);
        subscription.setSeason(9);
        return subscription;
    }

    private MediaSubscriptionEpisode episode(int id, int number) {
        MediaSubscriptionEpisode episode = new MediaSubscriptionEpisode();
        episode.setId(id);
        episode.setSubscriptionId(69);
        episode.setSeason(9);
        episode.setNumber(number);
        return episode;
    }

    private MediaSubscriptionEpisodeSource row(int episodeId, String relPath) {
        MediaSubscriptionEpisodeSource row = new MediaSubscriptionEpisodeSource();
        row.setEpisodeId(episodeId);
        row.setResourceId(1937);
        row.setRelPath(relPath);
        row.setState(MediaSubscriptionEpisodeSource.STATE_LISTED);
        return row;
    }

    @Test
    void removesRowsPoisonedByCopyNumbersAndFrameRate() {
        MediaSubscriptionEpisodeSource row188 = row(81, "X 心动S8/2025-08-18 第3期上纯享：元气辣妹主动贴贴188男大.mkv");
        MediaSubscriptionEpisodeSource row60 = row(82, "S09/2026.07.31_先导片上_4K_60fps.mp4");
        MediaSubscriptionEpisodeSource row5 = row(83, "S09/2026.09.01_第5期下.mp4");
        when(episodeRepository.findBySubscriptionIdOrderByNumber(69))
                .thenReturn(List.of(episode(81, 188), episode(82, 60), episode(83, 5)));
        when(episodeSourceRepository.findBySubscriptionAndStatesIn(69, List.of(MediaSubscriptionEpisodeSource.STATE_LISTED)))
                .thenReturn(List.of(row188, row60, row5));

        service.reconcileEpisodeRows(subscription());

        // 188 行重算为第 3 期(集号变化)删除;先导片命中非正片词删除;第 5 期正片保留
        verify(episodeSourceRepository, times(2)).delete(any());
        verify(episodeSourceRepository).delete(row188);
        verify(episodeSourceRepository).delete(row60);
        verify(episodeSourceRepository, never()).delete(row5);
    }

    @Test
    void noLiveRowsOrNoSeasonIsNoOp() {
        when(episodeSourceRepository.findBySubscriptionAndStatesIn(anyInt(), any()))
                .thenReturn(List.of());

        service.reconcileEpisodeRows(subscription());
        MediaSubscription seasonless = subscription();
        seasonless.setSeason(null);
        service.reconcileEpisodeRows(seasonless);

        verify(episodeSourceRepository, never()).delete(any());
    }
}
