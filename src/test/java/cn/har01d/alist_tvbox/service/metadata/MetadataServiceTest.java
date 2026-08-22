package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.entity.MediaMetadata;
import cn.har01d.alist_tvbox.entity.MediaMetadataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * media_metadata 持久层语义:完结剧永久命中零网络;在播剧超 TTL 落到 provider 并回写;
 * provider 失败的空结果不覆盖已有快照;cachedDetails(详情页)对 stale 行仍给旧值。
 */
class MetadataServiceTest {

    private MediaMetadataRepository repository;
    private MetadataProvider provider;
    private MetadataService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(MediaMetadataRepository.class);
        provider = Mockito.mock(MetadataProvider.class);
        when(provider.getName()).thenReturn("tmdb");
        AppProperties properties = new AppProperties();
        properties.getSubscription().setAiringRefreshHours(6);
        service = new MetadataService(List.of(provider), repository, properties, mapper);
    }

    private MediaMetadata row(String status, long fetchTimeAgoMs, MetadataDetails details) throws Exception {
        MediaMetadata row = new MediaMetadata();
        row.setProvider("tmdb");
        row.setMetaId("123");
        row.setSeason(1);
        row.setStatus(status);
        row.setPayload(mapper.writeValueAsString(details));
        row.setFetchTime(System.currentTimeMillis() - fetchTimeAgoMs);
        return row;
    }

    private MetadataDetails details(String status, String name) {
        MetadataDetails details = new MetadataDetails();
        details.setProvider("tmdb");
        details.setId("123");
        details.setName(name);
        details.setStatus(status);
        details.setTotalEpisodes(12);
        details.setGenres(List.of("剧情")); // 新版快照形态(扩展字段在场);全缺 = 旧版,视为过期
        details.setRatings(java.util.Map.of("tmdb", "8.0")); // 多源评分扩展后的形态;缺 = 旧版
        return details;
    }

    @Test
    void endedRowHitsPersistedLayerWithoutNetwork() throws Exception {
        when(repository.findByProviderAndMetaIdAndSeason("tmdb", "123", 1))
                .thenReturn(Optional.of(row(MetadataDetails.STATUS_ENDED, 365L * 24 * 3600_000, details(MetadataDetails.STATUS_ENDED, "完结剧"))));
        MetadataDetails result = service.details("tmdb", "123", 1);
        assertNotNull(result);
        assertEquals("完结剧", result.getName());
        verify(provider, never()).details(anyString(), any());
    }

    @Test
    void freshReturningRowHitsPersistedLayer() throws Exception {
        when(repository.findByProviderAndMetaIdAndSeason("tmdb", "123", 1))
                .thenReturn(Optional.of(row(MetadataDetails.STATUS_RETURNING, 3600_000, details(MetadataDetails.STATUS_RETURNING, "在播剧"))));
        MetadataDetails result = service.details("tmdb", "123", 1);
        assertNotNull(result);
        assertEquals("在播剧", result.getName());
        verify(provider, never()).details(anyString(), any());
    }

    @Test
    void staleReturningRowFallsThroughToProviderAndPersists() throws Exception {
        when(repository.findByProviderAndMetaIdAndSeason("tmdb", "123", 1))
                .thenReturn(Optional.of(row(MetadataDetails.STATUS_RETURNING, 7L * 3600_000, details(MetadataDetails.STATUS_RETURNING, "旧值"))));
        MetadataDetails fresh = details(MetadataDetails.STATUS_RETURNING, "新值");
        when(provider.details("123", 1)).thenReturn(fresh);
        MetadataDetails result = service.details("tmdb", "123", 1);
        assertEquals("新值", result.getName());
        verify(repository).save(any(MediaMetadata.class));
    }

    @Test
    void emptyProviderResultDoesNotOverwriteSnapshot() throws Exception {
        when(repository.findByProviderAndMetaIdAndSeason("tmdb", "123", 1))
                .thenReturn(Optional.of(row(MetadataDetails.STATUS_RETURNING, 7L * 3600_000, details(MetadataDetails.STATUS_RETURNING, "旧值"))));
        MetadataDetails empty = new MetadataDetails(); // 网络失败形态:name/封面/集数全空
        when(provider.details("123", 1)).thenReturn(empty);
        MetadataDetails result = service.details("tmdb", "123", 1);
        assertNotNull(result);
        verify(repository, never()).save(any());
    }

    @Test
    void cachedDetailsServesStaleRowForDetailPage() throws Exception {
        when(repository.findByProviderAndMetaIdAndSeason("tmdb", "123", 1))
                .thenReturn(Optional.of(row(MetadataDetails.STATUS_RETURNING, 30L * 24 * 3600_000, details(MetadataDetails.STATUS_RETURNING, "旧值"))));
        MetadataDetails cached = service.cachedDetails("tmdb", "123", 1);
        assertNotNull(cached, "详情页对 stale 行应展示旧值,等后台刷新");
        assertEquals("旧值", cached.getName());
        // details() 同一行则判过期,应落 provider
        when(provider.details("123", 1)).thenReturn(details(MetadataDetails.STATUS_RETURNING, "新值"));
        assertEquals("新值", service.details("tmdb", "123", 1).getName());
    }

    @Test
    void refreshDetailsBypassesPersistedLayerAndRewrites() throws Exception {
        // DB 里已有 fresh 快照,refreshDetails 仍要直取 provider 并回写
        when(repository.findByProviderAndMetaIdAndSeason("tmdb", "123", 1))
                .thenReturn(Optional.of(row(MetadataDetails.STATUS_RETURNING, 1000, details(MetadataDetails.STATUS_RETURNING, "旧值"))));
        MetadataDetails fresh = details(MetadataDetails.STATUS_RETURNING, "新值");
        when(provider.refreshDetails("123", 1)).thenReturn(fresh);
        MetadataDetails result = service.refreshDetails("tmdb", "123", 1);
        assertEquals("新值", result.getName());
        verify(provider).refreshDetails("123", 1);
        verify(repository).save(any(MediaMetadata.class));
    }

    @Test
    void unknownProviderReturnsNull() {
        assertNull(service.details("nope", "123", 1));
    }

    @Test
    void legacySnapshotWithoutExtendedFieldsRefreshesEvenWhenEnded() throws Exception {
        MetadataDetails legacy = new MetadataDetails(); // V32 扩展前的快照形态:无 genres/rating/originalName
        legacy.setProvider("tmdb");
        legacy.setId("123");
        legacy.setName("完结剧");
        legacy.setStatus(MetadataDetails.STATUS_ENDED);
        when(repository.findByProviderAndMetaIdAndSeason("tmdb", "123", 1))
                .thenReturn(Optional.of(row(MetadataDetails.STATUS_ENDED, 1000, legacy)));
        MetadataDetails fresh = details(MetadataDetails.STATUS_ENDED, "完结剧");
        when(provider.details("123", 1)).thenReturn(fresh);
        MetadataDetails result = service.details("tmdb", "123", 1);
        assertEquals("完结剧", result.getName());
        verify(repository).save(any(MediaMetadata.class)); // 升级后回写新形态快照,下次不再触发
    }

    @Test
    void gluedDoubanSnapshotRefreshesEvenWhenEnded() throws Exception {
        MetadataDetails glued = details(MetadataDetails.STATUS_ENDED, "九门");
        glued.setGenres(List.of("剧情,奇幻,冒险")); // 豆瓣分隔符修复前的粘连形态(整串未拆)
        when(repository.findByProviderAndMetaIdAndSeason("tmdb", "123", 1))
                .thenReturn(Optional.of(row(MetadataDetails.STATUS_ENDED, 1000, glued)));
        when(provider.details("123", 1)).thenReturn(details(MetadataDetails.STATUS_ENDED, "九门"));
        service.details("tmdb", "123", 1);
        verify(repository).save(any(MediaMetadata.class)); // 坏形态视为过期,重拉回写拆分后的快照
    }

    @Test
    void searchReportSkipsNullProviderTarget() {
        MetadataService.SearchResult result = service.searchReport("nope", "kw");
        assertEquals(0, result.items().size());
        assertEquals(1, result.errors().size());
    }
}
