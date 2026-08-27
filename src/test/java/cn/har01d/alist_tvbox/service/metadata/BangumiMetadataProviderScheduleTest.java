package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.EpisodeAirDate;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Bangumi 章节收录口径:章节端点是 /v0/episodes?subject_id=(「/v0/subjects/{id}/episodes」404,
 * 曾致线上分集全空);v0 章节无 status 字段,已播/日程与 TMDB applySeasonEpisodes 对齐 ——
 * 按播出时刻(airdate 当日 20:00)判定,昨日/今日已播仍进 upcoming(时间轴「昨天/今天」分组),
 * nextAirTime 严格取 20:00 未过的集(当日待播集不漏)。
 */
class BangumiMetadataProviderScheduleTest {
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);
    private static final String EPISODES_URL = "https://api.bgm.tv/v0/episodes?subject_id=6000&limit=100&offset=0";

    private final RestTemplate restTemplate = restTemplateWithJackson2();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final BangumiMetadataProvider provider;

    BangumiMetadataProviderScheduleTest() {
        MetadataHttp metadataHttp = Mockito.mock(MetadataHttp.class);
        Mockito.when(metadataHttp.create()).thenReturn(restTemplate);
        provider = new BangumiMetadataProvider(metadataHttp, new MetadataHealth(), null, null, null);
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
    void detailsFetchesEpisodesFromV0Endpoint() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo("https://api.bgm.tv/v0/subjects/6000"))
                .andRespond(withSuccess("{\"name\":\"慕兰之战\",\"name_cn\":\"\",\"date\":\"2026-07-01\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(EPISODES_URL))
                .andRespond(withSuccess("{\"data\":[" + String.join(",",
                        episode(10, today.minusDays(7), "七日前"),
                        episode(11, today.minusDays(1), "昨日"),
                        episode(12, today.plusDays(1), "明日"),
                        episode(101, today.plusDays(2), "预告", 2)) + "]}",
                        MediaType.APPLICATION_JSON));

        MetadataDetails details = provider.details("6000", null);

        assertEquals(3, details.getTotalEpisodes(), "type=2 预告不计正片");
        assertEquals(2, details.getAiredEpisodes(), "7天前/昨日已播(明日/后日未到 20:00)");
        assertEquals(List.of(11, 12),
                details.getUpcoming().stream().map(EpisodeAirDate::getEpisode).toList(),
                "昨日已播进日程(昨天/今天窗口),7 天前不进");
        assertEquals(today.plusDays(1).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getNextAirTime());
        assertEquals(MetadataDetails.STATUS_RETURNING, details.getStatus());
        assertEquals("七日前", details.getEpisodes().get(0).getTitle(), "name_cn 空串回落 name(盗妖行形态)");
    }

    /** 分页:首页满 100 条继续翻页,offset 递增;不足一页即止。 */
    @Test
    void episodesFetchPaginatesUntilShortPage() throws Exception {
        StringBuilder page1 = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            page1.append(episode(i, LocalDate.now(ZONE).minusDays(60), "第" + i + "回")).append(',');
        }
        server.expect(once(), requestTo(EPISODES_URL))
                .andRespond(withSuccess("{\"data\":[" + page1.substring(0, page1.length() - 1) + "]}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.bgm.tv/v0/episodes?subject_id=6000&limit=100&offset=100"))
                .andRespond(withSuccess("{\"data\":[" + episode(101, LocalDate.now(ZONE).plusDays(1), "第101回") + "]}",
                        MediaType.APPLICATION_JSON));

        List<JsonNode> episodes = BangumiMetadataProvider.fetchEpisodePages(restTemplate, "6000");

        assertEquals(101, episodes.size());
        server.verify();
    }

    /** 千集级连载(bgm 航海王 1191 集)必须翻过第 5 页:旧上限 5 页把分集截到 500,
     *  桥接据此把官方总/已播钉死 500,千集级真资源反被集号门禁当同名异剧拦截(线上订阅 48)。 */
    @Test
    void episodesFetchBeyondFivePagesForLongRunningShows() throws Exception {
        LocalDate aired = LocalDate.now(ZONE).minusDays(60);
        int page = 0;
        for (; page < 6; page++) { // 6 个满页(600 行),第 7 页 91 行收尾 → 691 行
            StringBuilder body = new StringBuilder();
            for (int i = 1; i <= 100; i++) {
                body.append(episode(page * 100 + i, aired, "第" + (page * 100 + i) + "回")).append(',');
            }
            server.expect(once(), requestTo("https://api.bgm.tv/v0/episodes?subject_id=6000&limit=100&offset=" + (page * 100)))
                    .andRespond(withSuccess("{\"data\":[" + body.substring(0, body.length() - 1) + "]}",
                            MediaType.APPLICATION_JSON));
        }
        StringBuilder tail = new StringBuilder();
        for (int i = 1; i <= 91; i++) {
            tail.append(episode(600 + i, aired, "第" + (600 + i) + "回")).append(',');
        }
        server.expect(once(), requestTo("https://api.bgm.tv/v0/episodes?subject_id=6000&limit=100&offset=600"))
                .andRespond(withSuccess("{\"data\":[" + tail.substring(0, tail.length() - 1) + "]}",
                        MediaType.APPLICATION_JSON));

        List<JsonNode> episodes = BangumiMetadataProvider.fetchEpisodePages(restTemplate, "6000");

        assertEquals(691, episodes.size(), "6 个满页 + 91 行收尾:5 页旧上限会停在 500");
        server.verify();
    }

    /** 已播口径按播出时刻:当日 20:00 前刷新,当日集不算已播且是 nextAir(墙钟相关用例直测覆盖)。 */
    @Test
    void todayEpisodeAirsAtEightPm() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        long beforeAir = today.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();
        MetadataDetails details = new MetadataDetails();
        BangumiMetadataProvider.applyEpisodes(details, episodes(
                episode(10, today.minusDays(1), "昨日"), episode(11, today, "今日")), beforeAir);

        assertEquals(1, details.getAiredEpisodes(), "当日 20:00 前不算已播");
        assertEquals(today.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getNextAirTime(), "当日待播集参与 nextAir");
        assertEquals(List.of(10, 11), details.getUpcoming().stream().map(EpisodeAirDate::getEpisode).toList());
    }

    /** airdate 未登记的章节:计入总数、进分集列表(标题仍可用),不参与已播/日程统计。 */
    @Test
    void episodesWithoutAirDateKeepTitleOnly() throws Exception {
        long now = LocalDate.now(ZONE).atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();
        MetadataDetails details = new MetadataDetails();
        BangumiMetadataProvider.applyEpisodes(details, episodes(
                "{\"ep\":10,\"sort\":10,\"type\":0,\"airdate\":\"\",\"name\":\"待定\"}",
                episode(11, LocalDate.now(ZONE).plusDays(2), "后日")), now);

        assertEquals(2, details.getTotalEpisodes(), "总数与分集列表行数一致");
        assertEquals("待定", details.getEpisodes().get(0).getTitle());
        assertNull(details.getEpisodes().get(0).getAirTime());
        assertEquals(0, details.getAiredEpisodes());
        assertEquals(List.of(11), details.getUpcoming().stream().map(EpisodeAirDate::getEpisode).toList());
    }

    private static List<JsonNode> episodes(String... items) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (String item : items) {
            result.add(new ObjectMapper().readTree(item));
        }
        return result;
    }

    /** v0 章节字段形态:ep/sort/type/airdate(注意不是 air_date)/name_cn/name,无 status。 */
    private static String episode(int number, LocalDate date, String title) {
        return episode(number, date, title, 0);
    }

    private static String episode(int number, LocalDate date, String title, int type) {
        return "{\"ep\":" + number + ",\"sort\":" + number + ",\"type\":" + type
                + ",\"airdate\":\"" + date + "\",\"name\":\"" + title + "\",\"name_cn\":\"\"}";
    }
}
