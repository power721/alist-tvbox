package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.EpisodeAirDate;
import cn.har01d.alist_tvbox.dto.EpisodeInfo;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.util.Constants;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Bangumi 分集标题桥接:TMDB 中文标题「第 N 集」占位回填(非占位不覆盖)+ 集号超上界整行补齐
 * (盗妖行形态:TMDB 41 集、Bangumi 已排播 60 集全量真实标题);bangumi 自源/未桥接/失败静默。
 */
class BangumiEpisodeBridgeTest {
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);
    private static final String EPISODES_URL = "https://api.bgm.tv/v0/episodes?subject_id=608049&limit=100&offset=0";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final BangumiEpisodeBridge bridge;

    BangumiEpisodeBridgeTest() {
        MetadataHttp metadataHttp = Mockito.mock(MetadataHttp.class);
        Mockito.when(metadataHttp.create()).thenReturn(restTemplate);
        bridge = new BangumiEpisodeBridge(metadataHttp);
    }

    /** 盗妖行线上形态:TMDB 41 集标题全占位,Bangumi 60 集真实标题 + 收官排播。 */
    @Test
    void fillsPlaceholderTitlesAndAppendsBeyondSourceHorizon() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(EPISODES_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(bangumiBody(today), MediaType.APPLICATION_JSON));

        MetadataDetails details = tmdbDetails(today);
        bridge.merge(details);

        assertEquals("来世，你可以找我报仇", details.getEpisodes().get(0).getTitle(), "占位标题回填(name_cn 空回落 name)");
        assertEquals(60, details.getEpisodes().size(), "42-60 集(TMDB 未建行)整行补齐");
        assertEquals("危机纷乱，行踏南域", details.getEpisodes().get(56).getTitle(), "第 57 集标题");
        assertEquals(today.plusDays(1).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getEpisodes().get(41).getAirTime(), "补入行播出日期按 20:00 约定落位");
        assertEquals(60, details.getTotalEpisodes(), "总数延展到 Bangumi 排播上界");
        assertEquals(41, details.getAiredEpisodes(), "补入行均未播,已播数不变");
        assertEquals(List.of(42, 43, 44), details.getUpcoming().stream()
                .map(EpisodeAirDate::getEpisode).limit(3).toList(), "日程延展包含补入行");
        assertEquals(today.plusDays(1).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getNextAirTime(), "源侧无日程时补入行给出下集播出");
        assertEquals(MetadataDetails.STATUS_RETURNING, details.getStatus());
    }

    /** TMDB 非占位标题是更权威源,不覆盖。 */
    @Test
    void keepsRealTmdbTitles() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(EPISODES_URL))
                .andRespond(withSuccess(bangumiBody(today), MediaType.APPLICATION_JSON));

        MetadataDetails details = tmdbDetails(today);
        details.getEpisodes().get(0).setTitle("TMDB 真实标题");
        bridge.merge(details);

        assertEquals("TMDB 真实标题", details.getEpisodes().get(0).getTitle());
    }

    /** bangumi 自源详情跳过:不发请求(bangumi provider 的章节本就带全量标题)。 */
    @Test
    void skipsBangumiSourcedDetails() {
        MetadataDetails details = tmdbDetails(LocalDate.now(ZONE));
        details.setProvider(BangumiMetadataProvider.NAME);
        bridge.merge(details);
        server.verify();
        assertEquals(41, details.getEpisodes().size());
    }

    /** 未桥接 bangumi id(externalIds 缺)跳过;请求失败静默不炸详情链。 */
    @Test
    void failsSilentlyWithoutBangumiIdOrOnHttpError() {
        MetadataDetails noId = tmdbDetails(LocalDate.now(ZONE));
        noId.setExternalIds(new LinkedHashMap<>(Map.of("douban", "123")));
        bridge.merge(noId);
        server.verify();

        server.expect(once(), requestTo(EPISODES_URL)).andRespond(withServerError());
        MetadataDetails failing = tmdbDetails(LocalDate.now(ZONE));
        bridge.merge(failing);
        assertEquals("第 1 集", failing.getEpisodes().get(0).getTitle(), "失败保留原标题");
        assertEquals(41, failing.getEpisodes().size());
        assertNull(failing.getNextAirTime());
    }

    /** TMDB 快照形态:41 集占位标题,无日程(nextAirTime 空)。 */
    private static MetadataDetails tmdbDetails(LocalDate today) {
        MetadataDetails details = new MetadataDetails();
        details.setProvider("tmdb");
        details.setName("盗妖行");
        details.setExternalIds(new LinkedHashMap<>(Map.of(
                "tmdb", "315088", "bangumi", "608049", "douban", "27123456")));
        long aired = today.minusDays(10).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli();
        List<EpisodeInfo> episodes = new ArrayList<>();
        for (int i = 1; i <= 41; i++) {
            episodes.add(new EpisodeInfo(i, "第 " + i + " 集", aired));
        }
        details.setEpisodes(episodes);
        details.setTotalEpisodes(41);
        details.setAiredEpisodes(41);
        details.setUpcoming(new ArrayList<>());
        return details;
    }

    /** Bangumi 章节(线上 608049 实际形态节选):1-41 已播,42 起隔日排播到 60。 */
    private static String bangumiBody(LocalDate today) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 1; i <= 60; i++) {
            String title;
            if (i == 1) {
                title = "来世，你可以找我报仇";
            } else if (i == 57) {
                title = "危机纷乱，行踏南域";
            } else {
                title = i <= 41 ? "已播" + i : "补齐" + i;
            }
            LocalDate date = i <= 41 ? today.minusDays(10) : today.plusDays(1 + (i - 42) * 2L);
            sb.append("{\"ep\":").append(i).append(",\"sort\":").append(i)
                    .append(",\"type\":0,\"airdate\":\"").append(date)
                    .append("\",\"name\":\"").append(title).append("\",\"name_cn\":\"\"}");
            if (i < 60) {
                sb.append(',');
            }
        }
        return sb.append("]}").toString();
    }
}
