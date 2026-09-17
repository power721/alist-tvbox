package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.WatchlistItem;
import cn.har01d.alist_tvbox.entity.WatchlistItemRepository;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {
    @Mock
    private WatchlistItemRepository repository;
    @Mock
    private PianDanSubscriptionService pianDanSubscriptionService;
    @Mock
    private PianDanService pianDanService;
    @Mock
    private MediaSubscriptionService mediaSubscriptionService;

    private WatchlistService service() {
        return new WatchlistService(repository, pianDanSubscriptionService, pianDanService, mediaSubscriptionService);
    }

    private static PianDanSubscriptionService.PianDanEntry entry(String vodId, String name, Integer season, Integer year, Integer doubanId) {
        return new PianDanSubscriptionService.PianDanEntry(vodId, name, season, year, doubanId);
    }

    @Test
    void addIsIdempotentForExistingVodId() {
        when(pianDanSubscriptionService.pianDanEntry("tmdb:tv:42|测试剧"))
                .thenReturn(entry("tmdb:tv:42", "测试剧", null, null, null));
        when(repository.findByUidAndVodId(7, "tmdb:tv:42"))
                .thenReturn(Optional.of(new WatchlistItem()));

        var result = service().add(7, "tmdb:tv:42|测试剧");

        assertTrue(result.existed());
        verify(repository, never()).save(any());
    }

    @Test
    void addParsesPayloadAndEnrichesTmdbSnapshot() {
        when(pianDanSubscriptionService.pianDanEntry("tmdb:tv:42|测试剧|2"))
                .thenReturn(entry("tmdb:tv:42", "测试剧", 2, null, null));
        when(repository.findByUidAndVodId(7, "tmdb:tv:42")).thenReturn(Optional.empty());
        MovieDetail meta = new MovieDetail();
        meta.setVod_name("测试剧");
        meta.setVod_pic("https://image.tmdb.org/t/p/w500/x.jpg");
        meta.setVod_remarks("8.5");
        meta.setVod_year("2024");
        when(pianDanService.tmdbDetail("tv", 42)).thenReturn(meta);

        var result = service().add(7, "tmdb:tv:42|测试剧|2");

        assertFalse(result.existed());
        ArgumentCaptor<WatchlistItem> captor = ArgumentCaptor.forClass(WatchlistItem.class);
        verify(repository).save(captor.capture());
        WatchlistItem item = captor.getValue();
        assertEquals(7, item.getUid());
        assertEquals("tmdb:tv:42", item.getVodId());
        assertEquals("测试剧", item.getTitle());
        assertEquals(2, item.getSeason());
        assertEquals(2024, item.getYear());
        assertEquals("https://image.tmdb.org/t/p/w500/x.jpg", item.getPic());
        assertEquals("8.5", item.getRemarks());
        assertEquals(WatchlistItem.STATUS_WANT, item.getStatus());
    }

    @Test
    void addSurvivesSnapshotEnrichmentFailure() {
        when(pianDanSubscriptionService.pianDanEntry("tmdb:tv:42|测试剧"))
                .thenReturn(entry("tmdb:tv:42", "测试剧", null, null, null));
        when(repository.findByUidAndVodId(7, "tmdb:tv:42")).thenReturn(Optional.empty());
        when(pianDanService.tmdbDetail("tv", 42)).thenThrow(new RuntimeException("tmdb down"));

        var result = service().add(7, "tmdb:tv:42|测试剧");

        assertFalse(result.existed());
        ArgumentCaptor<WatchlistItem> captor = ArgumentCaptor.forClass(WatchlistItem.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getPic());
    }

    @Test
    void addTreatsUniqueViolationAsExisted() {
        // 并发双击:findByUidAndVodId 未命中但 save 撞唯一键 —— 回执仍为已在,不抛错
        when(pianDanSubscriptionService.pianDanEntry("tmdb:tv:42|测试剧"))
                .thenReturn(entry("tmdb:tv:42", "测试剧", null, null, null));
        when(repository.findByUidAndVodId(7, "tmdb:tv:42")).thenReturn(Optional.empty());
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("uk_watchlist_item"));

        var result = service().add(7, "tmdb:tv:42|测试剧");

        assertTrue(result.existed());
    }

    @Test
    void removeReportsMissingEntry() {
        when(pianDanSubscriptionService.pianDanEntry("tmdb:tv:42|测试剧"))
                .thenReturn(entry("tmdb:tv:42", "测试剧", null, null, null));
        when(repository.deleteByUidAndVodId(7, "tmdb:tv:42")).thenReturn(0L);

        var result = service().remove(7, "tmdb:tv:42|测试剧");

        assertFalse(result.existed());
        assertTrue(result.msg().contains("不在稍后再看中"));
    }

    @Test
    void removeDeletesByVodId() {
        when(pianDanSubscriptionService.pianDanEntry("tmdb:tv:42|测试剧"))
                .thenReturn(entry("tmdb:tv:42", "测试剧", null, null, null));
        when(repository.deleteByUidAndVodId(7, "tmdb:tv:42")).thenReturn(1L);

        var result = service().remove(7, "tmdb:tv:42|测试剧");

        assertTrue(result.existed());
        assertEquals("已移出稍后再看《测试剧》", result.msg());
    }

    @Test
    void contentRendersSeasonAndYearRemarks() {
        var first = item(1, "tmdb:tv:1", "剧A", null, 2024, "8.5");
        var second = item(2, "tmdb:tv:2", "剧B", 2, 2023, "");
        var third = item(3, "s:剧C", "剧C", null, null, "");
        when(repository.findByUidAndStatusOrderByCreatedTimeDesc(7, WatchlistItem.STATUS_WANT))
                .thenReturn(List.of(first, second, third));

        MovieList page1 = service().content(7, 1);

        assertEquals(3, page1.getTotal());
        assertEquals(3, page1.getList().size());
        assertEquals("tmdb:tv:1", page1.getList().get(0).getVod_id());
        assertEquals("2024 · 8.5", page1.getList().get(0).getVod_remarks());
        assertEquals("剧B 第2季", page1.getList().get(1).getVod_name());
        assertEquals("2023", page1.getList().get(1).getVod_remarks());
        assertEquals("", page1.getList().get(2).getVod_remarks());
    }

    @Test
    void contentSecondPageIsEmptyBeyondTotal() {
        when(repository.findByUidAndStatusOrderByCreatedTimeDesc(7, WatchlistItem.STATUS_WANT))
                .thenReturn(List.of(item(1, "tmdb:tv:1", "剧A", null, null, "")));

        MovieList page2 = service().content(7, 2);

        assertTrue(page2.getList().isEmpty());
        assertEquals(1, page2.getTotal());
    }

    @Test
    void updateStatusRejectsUnknownValue() {
        assertFalse(service().updateStatus(7, 1, "BOGUS"));
        verify(repository, never()).save(any());
    }

    @Test
    void deleteIgnoresRowsOwnedByOtherUid() {
        WatchlistItem foreign = item(9, "tmdb:tv:9", "别人的", null, null, "");
        foreign.setUid(9);
        when(repository.findById(1)).thenReturn(Optional.of(foreign));

        service().delete(7, 1);

        verify(repository, never()).delete(any(WatchlistItem.class));
    }

    @Test
    void existsBlankVodIdIsFalseWithoutQuery() {
        assertFalse(service().exists(7, ""));
        verify(repository, never()).existsByUidAndVodId(anyInt(), anyString());
    }

    private static WatchlistItem item(int id, String vodId, String title, Integer season, Integer year, String remarks) {
        WatchlistItem item = new WatchlistItem();
        item.setId(id);
        item.setUid(7);
        item.setVodId(vodId);
        item.setTitle(title);
        item.setSeason(season);
        item.setYear(year);
        item.setRemarks(remarks);
        item.setStatus(WatchlistItem.STATUS_WANT);
        item.setCreatedTime(System.currentTimeMillis() - id);
        return item;
    }
}
