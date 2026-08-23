package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.EpisodeAirDate;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.util.Constants;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
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
 * Bangumi 播出日程收录口径与 TMDB 对齐:昨日/今日档期(状态已翻转的已播集、当日待播集)仍进 upcoming,
 * 时间轴「昨天/今天」分组才有内容;nextAirTime 只认状态未翻且日期在未来的集。
 */
class BangumiMetadataProviderScheduleTest {
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);

    private final RestTemplate restTemplate = restTemplateWithJackson2();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final BangumiMetadataProvider provider;

    BangumiMetadataProviderScheduleTest() {
        MetadataHttp metadataHttp = Mockito.mock(MetadataHttp.class);
        Mockito.when(metadataHttp.create()).thenReturn(restTemplate);
        provider = new BangumiMetadataProvider(metadataHttp, new MetadataHealth(), null, null);
    }

    /** Bangumi 主体接口直接 exchange(JsonNode.class):默认转换器表的 Jackson3 会抢读 com.fasterxml JsonNode,
     *  整表换成 [String, Jackson2] 保证 String 走 String、JsonNode 走 Jackson2。 */
    private static RestTemplate restTemplateWithJackson2() {
        RestTemplate template = new RestTemplate();
        template.setMessageConverters(java.util.Arrays.asList(
                new org.springframework.http.converter.StringHttpMessageConverter(),
                new MappingJackson2HttpMessageConverter()));
        return template;
    }

    @Test
    void upcomingKeepsRecentlyAiredEpisodes() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo("https://api.bgm.tv/v0/subjects/6000"))
                .andRespond(withSuccess("{\"name\":\"慕兰之战\",\"name_cn\":\"慕兰之战\",\"date\":\"2026-07-01\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.bgm.tv/v0/subjects/6000/episodes"))
                .andRespond(withSuccess(episodesBody(today), MediaType.APPLICATION_JSON));

        MetadataDetails details = provider.details("6000", null);

        assertEquals(2, details.getAiredEpisodes(), "状态 0(已播)只有 7天前/昨日两条");
        assertEquals(List.of(11, 12, 13),
                details.getUpcoming().stream().map(EpisodeAirDate::getEpisode).toList(),
                "昨日已播(status=0)、今日待播(status=1)、未来集都在日程");
        assertEquals(today.plusDays(6).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getNextAirTime(), "nextAirTime 只认未播且日期在未来的集");
    }

    private static String episodesBody(LocalDate today) {
        return "[" + String.join(",",
                episode(10, 0, today.minusDays(7)), episode(11, 0, today.minusDays(1)),
                episode(12, 1, today), episode(13, 1, today.plusDays(6))) + "]";
    }

    private static String episode(int number, int status, LocalDate date) {
        return "{\"ep\":" + number + ",\"type\":0,\"status\":" + status + ",\"air_date\":\"" + date + "\"}";
    }
}
