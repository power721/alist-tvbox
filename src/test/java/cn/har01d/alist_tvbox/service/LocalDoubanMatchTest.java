package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 豆瓣片单条目(s:)的本地库唯一匹配:纯名唯一直接要,同名翻拍靠 vod_id 内嵌年份消歧,
 * 消歧失败一律 null 回落(详情回落仅标题、订阅绑 id 回落 suggest),不赌首条。
 */
class LocalDoubanMatchTest {

    private final MovieRepository movieRepository = mock(MovieRepository.class);
    private final MediaSubscriptionService service = new MediaSubscriptionService(
            null, null, null, null, null, null,
            movieRepository, null, null, null, null, null, null, null,
            new AppProperties(), new ObjectMapper(), null, null, null);

    private static Movie movie(int id, String name, Integer year) {
        Movie movie = new Movie();
        movie.setId(id);
        movie.setName(name);
        movie.setYear(year);
        return movie;
    }

    @Test
    void subjectIdRoundTripsYearSuffix() {
        assertEquals("s:测试剧", PianDanService.subjectId("测试剧", null));
        assertEquals("s:测试剧@2024", PianDanService.subjectId("测试剧", 2024));
        assertEquals(new PianDanService.NameYear("测试剧", null), PianDanService.parseSubjectId("s:测试剧"));
        assertEquals(new PianDanService.NameYear("测试剧", 2024), PianDanService.parseSubjectId("s:测试剧@2024"));
        // 标题自带 @ 或后缀非数字:整体按标题解析,不误拆
        assertEquals(new PianDanService.NameYear("测试@剧", null), PianDanService.parseSubjectId("s:测试@剧"));
        assertEquals(new PianDanService.NameYear("测试剧@春夏", null), PianDanService.parseSubjectId("s:测试剧@春夏"));
    }

    @Test
    void uniqueNameMatchesWithoutYear() {
        when(movieRepository.getByName("孤剧")).thenReturn(List.of(movie(123, "孤剧", 2020)));
        assertEquals(123, service.localDoubanId("孤剧"));
        MovieDetail detail = service.localDoubanDetail("孤剧", null);
        assertEquals("孤剧", detail.getVod_name());
        assertEquals("2020", detail.getVod_year());
    }

    @Test
    void yearDisambiguatesSameNameRemakes() {
        when(movieRepository.getByName("翻拍")).thenReturn(List.of(
                movie(11, "翻拍", 1998), movie(22, "翻拍", 2023)));
        assertNull(service.localDoubanId("翻拍"));           // 无年份:同名两条不赌
        assertEquals(22, service.localDoubanId("翻拍", 2023)); // 年份命中唯一
        assertNull(service.localDoubanId("翻拍", 1999));       // 年份对不上:不是同一部,不赌
        assertEquals("2023", service.localDoubanDetail("翻拍", 2023).getVod_year());
    }

    @Test
    void yearMismatchRejectsOtherwiseUniqueName() {
        // 线上实例《醒来@2026》:本地库纯名唯一但年份是 2021(另一部),不得冒领
        when(movieRepository.getByName("醒来")).thenReturn(List.of(movie(33, "醒来", 2021)));
        assertNull(service.localDoubanId("醒来", 2026));
        assertNull(service.localDoubanDetail("醒来", 2026));
        assertEquals(33, service.localDoubanId("醒来", 2021));
        assertEquals(33, service.localDoubanId("醒来")); // 无年份:纯名唯一直接要
    }

    @Test
    void noMatchReturnsNullForFallback() {
        when(movieRepository.getByName("无此剧")).thenReturn(List.of());
        assertNull(service.localDoubanId("无此剧", 2024));
        assertNull(service.localDoubanDetail("无此剧", 2024));
    }
}
