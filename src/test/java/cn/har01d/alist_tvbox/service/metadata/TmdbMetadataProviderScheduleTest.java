package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.EpisodeAirDate;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.util.Constants;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * TMDB 播出日程收录口径:昨日/今日已播的分集仍进 upcoming —— 时间轴「昨天/今天」分组靠它,
 * 只收严格未来会把刚播出的集在播出日当天的元数据刷新时从 schedule 快照里洗掉(air_date 日期粒度,
 * 凌晨刷新即判"已播");nextAirTime 仍严格取未来集。
 */
class TmdbMetadataProviderScheduleTest {
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final TmdbMetadataProvider provider;

    TmdbMetadataProviderScheduleTest() {
        MetadataHttp metadataHttp = Mockito.mock(MetadataHttp.class);
        Mockito.when(metadataHttp.create()).thenReturn(restTemplate);
        provider = new TmdbMetadataProvider(Mockito.mock(SettingRepository.class), metadataHttp, new MetadataHealth());
    }

    @Test
    void upcomingKeepsYesterdayAndTodayAiredEpisodes() {
        LocalDate today = LocalDate.now(ZONE);
        String key = Constants.TMDB_API_KEY;
        server.expect(once(), requestTo("https://api.themoviedb.org/3/tv/9521?api_key=" + key + "&language=zh-CN"))
                .andRespond(withSuccess("{\"id\":9521,\"name\":\"慕兰之战\",\"status\":\"Returning Series\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/tv/9521/alternative_titles?api_key=" + key))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/tv/9521/credits?api_key=" + key + "&language=zh-CN"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/tv/9521/season/1?api_key=" + key + "&language=zh-CN"))
                .andRespond(withSuccess(seasonBody(today), MediaType.APPLICATION_JSON));

        MetadataDetails details = provider.details("9521", 1);

        assertEquals(3, details.getAiredEpisodes(), "日期粒度:7天前/昨日/今日都算已播");
        assertEquals(List.of(11, 12, 13),
                details.getUpcoming().stream().map(EpisodeAirDate::getEpisode).toList(),
                "昨日/今日已播 + 未来集都在日程,7 天前的已播集不进");
        assertEquals(today.minusDays(1).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getUpcoming().get(0).getAirTime(), "昨日集落昨日 20:00 桶");
        assertEquals(today.plusDays(6).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getNextAirTime(), "nextAirTime 仍严格取未来集");
    }

    private static String seasonBody(LocalDate today) {
        return "{\"episodes\":[" + String.join(",",
                episode(10, today.minusDays(7)), episode(11, today.minusDays(1)),
                episode(12, today), episode(13, today.plusDays(6))) + "]}";
    }

    private static String episode(int number, LocalDate date) {
        return "{\"episode_number\":" + number + ",\"air_date\":\"" + date + "\"}";

    }
}
