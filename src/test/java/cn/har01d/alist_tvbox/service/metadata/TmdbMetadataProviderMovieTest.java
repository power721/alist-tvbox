package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.TmdbEndpoint;
import cn.har01d.alist_tvbox.util.Constants;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * TMDB 电影条目(metaId {@code movie:{id}})的订阅建模:电影按 1 个资源位追更 ——
 * 总集数恒 1,已映(Released)=已播 1 且视作 ENDED(收齐 1 个文件即与剧集
 * endedByCollectedAll 同路完结);未映=已播 0、上映日进 nextAirTime(待上映电影进播放时间轴)。
 */
class TmdbMetadataProviderMovieTest {
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final TmdbMetadataProvider provider;

    TmdbMetadataProviderMovieTest() {
        MetadataHttp metadataHttp = Mockito.mock(MetadataHttp.class);
        Mockito.when(metadataHttp.create()).thenReturn(restTemplate);
        provider = new TmdbMetadataProvider(new TmdbEndpoint(Mockito.mock(SettingRepository.class)), metadataHttp, new MetadataHealth(), null, null,
                null, null);
    }

    @Test
    void releasedMovieModeledAsSingleEndedSlot() {
        String key = Constants.TMDB_API_KEY;
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/438631?language=zh-CN&append_to_response=images&api_key=" + key))
                .andRespond(withSuccess("{\"id\":438631,\"title\":\"沙丘2\",\"original_title\":\"Dune: Part Two\","
                        + "\"release_date\":\"2024-03-01\",\"status\":\"Released\",\"runtime\":167,"
                        + "\"vote_average\":8.2,\"original_language\":\"en\","
                        + "\"genres\":[{\"id\":878,\"name\":\"科幻\"}],"
                        + "\"poster_path\":\"/poster.jpg\",\"backdrop_path\":\"/backdrop.jpg\",\"overview\":\"保罗·厄崔迪的旅程\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/438631/alternative_titles?api_key=" + key))
                .andRespond(withSuccess("{\"results\":[{\"title\":\"Dune: Part Two\"}]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/438631/credits?language=zh-CN&api_key=" + key))
                .andRespond(withSuccess("{\"cast\":[{\"name\":\"提莫西·查拉梅\",\"character\":\"Paul\",\"profile_path\":\"/p.jpg\"}],"
                        + "\"crew\":[{\"name\":\"丹尼斯·维伦纽瓦\",\"job\":\"Director\"}]}", MediaType.APPLICATION_JSON));

        MetadataDetails details = provider.details("movie:438631", null);

        assertEquals("沙丘2", details.getName());
        assertEquals("Dune: Part Two", details.getOriginalName());
        assertEquals("2024", details.getYear());
        assertEquals(MetadataDetails.STATUS_ENDED, details.getStatus());
        assertEquals(1, details.getTotalEpisodes(), "电影按 1 个资源位建模");
        assertEquals(1, details.getAiredEpisodes(), "已映=已播 1");
        assertNull(details.getNextAirTime(), "已映无下集排播");
        assertEquals(167, details.getRuntimeMinutes());
        assertEquals("8.2", details.getRating());
        assertEquals(java.util.List.of("科幻"), details.getGenres());
        assertEquals("438631", details.getExternalIds().get("tmdb"), "外链 id 不带 movie: 前缀");
        assertEquals(java.util.List.of("Dune: Part Two"), details.getAliases());
        assertNotNull(details.getCast() != null && !details.getCast().isEmpty() ? details.getCast().get(0) : null);
        assertEquals(java.util.List.of("丹尼斯·维伦纽瓦"), details.getDirectors());
        server.verify();
    }

    @Test
    void unreleasedMovieKeepsReleaseDateAsNextAirTime() {
        LocalDate release = LocalDate.now(ZONE).plusDays(30);
        String key = Constants.TMDB_API_KEY;
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/693134?language=zh-CN&append_to_response=images&api_key=" + key))
                .andRespond(withSuccess("{\"id\":693134,\"title\":\"未映片\",\"release_date\":\"" + release + "\","
                        + "\"status\":\"In Production\",\"vote_average\":0,\"genres\":[]}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/693134/alternative_titles?api_key=" + key))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/693134/credits?language=zh-CN&api_key=" + key))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        MetadataDetails details = provider.details("movie:693134", 1);

        assertEquals(MetadataDetails.STATUS_UNKNOWN, details.getStatus(), "未映不算完结");
        assertEquals(1, details.getTotalEpisodes());
        assertEquals(0, details.getAiredEpisodes(), "未映=已播 0");
        assertEquals(release.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(), details.getNextAirTime(),
                "上映日进时间轴(当日 20:00 口径)");
        assertEquals(1, details.getUpcoming().get(0).getEpisode(), "资源位 1 的排播行");
        server.verify();
    }
}
