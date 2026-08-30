package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.EpisodeAirDate;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.TmdbEndpoint;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * TMDB 播出日程收录口径:昨日/今日已播的分集仍进 upcoming —— 时间轴「昨天/今天」分组靠它,
 * 只收严格未来会把刚播出的集在播出日当天的元数据刷新时从 schedule 快照里洗掉。
 * 已播判定按播出时刻(air_date 当日 20:00)而非日期粒度:播出日当天 20:00 前刷新即算已播,
 * 点映礼 N 集同日上架的剧已播虚高(线上 28 被记成 33)。
 */
class TmdbMetadataProviderScheduleTest {
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final TmdbMetadataProvider provider;

    TmdbMetadataProviderScheduleTest() {
        MetadataHttp metadataHttp = Mockito.mock(MetadataHttp.class);
        Mockito.when(metadataHttp.create()).thenReturn(restTemplate);
        provider = new TmdbMetadataProvider(new TmdbEndpoint(Mockito.mock(SettingRepository.class)), metadataHttp, new MetadataHealth(), null, null,
                null, null);
    }

    @Test
    void upcomingKeepsYesterdayAiredEpisodes() {
        LocalDate today = LocalDate.now(ZONE);
        String key = Constants.TMDB_API_KEY;
        server.expect(once(), requestTo("https://api.themoviedb.org/3/tv/9521?language=zh-CN&append_to_response=images&api_key=" + key))
                .andRespond(withSuccess("{\"id\":9521,\"name\":\"慕兰之战\",\"status\":\"Returning Series\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/tv/9521/alternative_titles?api_key=" + key))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/tv/9521/credits?language=zh-CN&api_key=" + key))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/tv/9521/season/1?language=zh-CN&api_key=" + key))
                .andRespond(withSuccess(seasonBody(today), MediaType.APPLICATION_JSON));

        MetadataDetails details = provider.details("9521", 1);

        assertEquals(2, details.getAiredEpisodes(), "7天前/昨日已播(当日集的已播口径墙钟相关,直测覆盖)");
        assertEquals(List.of(11, 13),
                details.getUpcoming().stream().map(EpisodeAirDate::getEpisode).toList(),
                "昨日已播 + 未来集都在日程,7 天前的已播集不进");
        assertEquals(today.minusDays(1).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getUpcoming().get(0).getAirTime(), "昨日集落昨日 20:00 桶");
        assertEquals(today.plusDays(6).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getNextAirTime(), "nextAirTime 仍严格取未来集");
    }

    @Test
    void massReleaseNotAiredBeforeAirHour() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        MetadataDetails details = new MetadataDetails();
        TmdbMetadataProvider.applySeasonEpisodes(details, massReleaseSeason(today),
                today.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli());

        assertEquals(33, details.getTotalEpisodes());
        assertEquals(28, details.getAiredEpisodes(), "播出日当天 20:00 前,当日点映集不算已播");
        assertEquals(today.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getNextAirTime(), "下集播出 = 当日 20:00");
        assertEquals(List.of(29, 30, 31, 32, 33),
                details.getUpcoming().stream().map(EpisodeAirDate::getEpisode).toList(),
                "当日待播集进日程(时间轴「今天」分组)");
    }

    @Test
    void massReleaseAiredAfterAirHour() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        MetadataDetails details = new MetadataDetails();
        TmdbMetadataProvider.applySeasonEpisodes(details, massReleaseSeason(today),
                today.atTime(21, 0).atZone(ZONE).toInstant().toEpochMilli());

        assertEquals(33, details.getAiredEpisodes(), "播出时刻(20:00)一过即算已播");
        assertNull(details.getNextAirTime());
        assertEquals(List.of(29, 30, 31, 32, 33),
                details.getUpcoming().stream().map(EpisodeAirDate::getEpisode).toList(),
                "当日已播集仍在日程(昨天/今天窗口),时间轴不空档");
    }

    private static String seasonBody(LocalDate today) {
        return "{\"episodes\":[" + String.join(",",
                episode(10, today.minusDays(7)), episode(11, today.minusDays(1)),
                episode(13, today.plusDays(6))) + "]}";
    }

    /** 线上点映礼形态:1-28 集已播,29-33 集全部排在同一天(今天 20:00)。 */
    private static JsonNode massReleaseSeason(LocalDate today) throws Exception {
        StringBuilder sb = new StringBuilder("{\"episodes\":[");
        for (int i = 1; i <= 28; i++) {
            sb.append(episode(i, today.minusDays(30 - i))).append(',');
        }
        for (int i = 29; i <= 33; i++) {
            sb.append(episode(i, today));
            if (i < 33) {
                sb.append(',');
            }
        }
        return new ObjectMapper().readTree(sb.append("]}").toString());
    }

    private static String episode(int number, LocalDate date) {
        return "{\"episode_number\":" + number + ",\"air_date\":\"" + date + "\"}";

    }
}
