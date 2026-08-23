package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.PlayUrl;
import cn.har01d.alist_tvbox.entity.PlayUrlRepository;
import cn.har01d.alist_tvbox.entity.Site;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProxyServiceTest {
    private final PlayUrlRepository playUrlRepository = mock(PlayUrlRepository.class);
    private final ProxyService service = new ProxyService(null, playUrlRepository, null, null, null, null);

    @Test
    void parsePlayUrlIdShouldAcceptIsoSuffix() {
        assertThat(ProxyService.parsePlayUrlId("1@106306.iso")).isEqualTo(106306);
    }

    // ---------- 长效代理注册(追剧盘线路) ----------

    private static Site site() {
        Site site = new Site();
        site.setId(1);
        return site;
    }

    private static PlayUrl stored(Instant time) {
        PlayUrl playUrl = new PlayUrl(1, "/追剧/7-测试剧/第01集.mkv", time);
        playUrl.setId(42);
        return playUrl;
    }

    /** 模拟 JPA save 回填主键。 */
    private void assignIdOnSave() {
        when(playUrlRepository.save(Mockito.any(PlayUrl.class))).thenAnswer(invocation -> {
            PlayUrl saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(99);
            }
            return saved;
        });
    }

    @Test
    void longTtlRegistersNewRowWithFullWindow() {
        when(playUrlRepository.findFirstBySiteAndPath(Mockito.eq(1), Mockito.anyString(), Mockito.any()))
                .thenReturn(null);
        assignIdOnSave();
        Instant before = Instant.now();

        int id = service.generateProxyUrl(site(), "/追剧/7-测试剧/第01集.mkv", Duration.ofDays(365));

        ArgumentCaptor<PlayUrl> captor = ArgumentCaptor.forClass(PlayUrl.class);
        verify(playUrlRepository).save(captor.capture());
        assertThat(captor.getValue().getTime()).isAfter(before.plus(Duration.ofDays(364)));
        assertThat(id).isEqualTo(99);
    }

    @Test
    void longTtlReusesRowWithAmpleLifetimeWithoutWrite() {
        when(playUrlRepository.findFirstBySiteAndPath(Mockito.eq(1), Mockito.anyString(), Mockito.any()))
                .thenReturn(stored(Instant.now().plus(Duration.ofDays(300)))); // 剩余 300d > 365d/2

        int id = service.generateProxyUrl(site(), "/追剧/7-测试剧/第01集.mkv", Duration.ofDays(365));

        assertThat(id).isEqualTo(42);
        verify(playUrlRepository, never()).save(Mockito.any());
    }

    @Test
    void longTtlRenewsRowWhenLifetimeBelowHalf() {
        when(playUrlRepository.findFirstBySiteAndPath(Mockito.eq(1), Mockito.anyString(), Mockito.any()))
                .thenReturn(stored(Instant.now().plus(Duration.ofDays(10)))); // 剩余 10d < 182.5d
        assignIdOnSave();
        Instant before = Instant.now();

        int id = service.generateProxyUrl(site(), "/追剧/7-测试剧/第01集.mkv", Duration.ofDays(365));

        ArgumentCaptor<PlayUrl> captor = ArgumentCaptor.forClass(PlayUrl.class);
        verify(playUrlRepository).save(captor.capture());
        assertThat(captor.getValue().getTime()).isAfter(before.plus(Duration.ofDays(364)));
        assertThat(id).isEqualTo(42);
    }

    @Test
    void longTtlRenewsExpiredRowInPlace() {
        when(playUrlRepository.findFirstBySiteAndPath(Mockito.eq(1), Mockito.anyString(), Mockito.any()))
                .thenReturn(stored(Instant.now().minus(Duration.ofDays(1)))); // 已过期:原地续期,不新建行
        assignIdOnSave();

        int id = service.generateProxyUrl(site(), "/追剧/7-测试剧/第01集.mkv", Duration.ofDays(365));

        ArgumentCaptor<PlayUrl> captor = ArgumentCaptor.forClass(PlayUrl.class);
        verify(playUrlRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(42);
        assertThat(captor.getValue().getTime()).isAfter(Instant.now());
        assertThat(id).isEqualTo(42);
    }
}
