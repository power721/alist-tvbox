package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 跨源评分桥接:各源订阅详情按剧名互补 ratings/externalIds(详情页多源评分+外链)——
 * TMDB 订阅补豆瓣(suggest 定位 + rexxar 免 cookie 评分)+ Bangumi(搜索自带 score);
 * 豆瓣订阅只补 Bangumi、Bangumi 订阅只补豆瓣(源自身/已带外链跳过)。
 * 归一化整词同名 + 年份门禁(±1,多季放行)后只补 ratings/externalIds,条目身份字段不动;
 * 原名搜空按剔季缀基名补搜一轮;未命中/失败/未开分一律负缓存静默,不影响详情主链。
 */
class RatingBridgeTest {
    private static final String FANREN_SUGGEST =
            "https://movie.douban.com/j/subject_suggest?q=%E5%87%A1%E4%BA%BA%E4%BF%AE%E4%BB%99%E4%BC%A0";
    private static final String FANREN_REXXAR = "https://m.douban.com/rexxar/api/v2/tv/36245887";
    private static final String BANGUMI_SEARCH = "https://api.bgm.tv/v0/search/subjects";
    private static final String RICK_SUGGEST =
            "https://movie.douban.com/j/subject_suggest?q=%E7%91%9E%E5%85%8B%E5%92%8C%E8%8E%AB%E8%92%82";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final RatingBridge bridge;

    RatingBridgeTest() {
        MetadataHttp metadataHttp = Mockito.mock(MetadataHttp.class);
        Mockito.when(metadataHttp.create()).thenReturn(restTemplate);
        bridge = new RatingBridge(metadataHttp);
    }

    /** 线上形态:TMDB 凡人修仙传(2020),suggest 命中剧集条目(movie 干扰项与子串模仿者拦掉),双源评分并入。 */
    @Test
    void bridgesDoubanAndBangumiRatings() {
        server.expect(once(), requestTo(FANREN_SUGGEST)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"id":"999","title":"凡人修仙传","year":"2020","type":"movie"},
                         {"id":"36245887","title":"凡人修仙传","year":"2020","type":"episode"},
                         {"id":"888","title":"凡人修仙传之乱星海","year":"2020","type":"episode"}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FANREN_REXXAR)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"title\":\"凡人修仙传\",\"year\":2020,\"rating\":{\"value\":8.9}}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BANGUMI_SEARCH)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"data":[{"id":332432,"name_cn":"凡人修仙传","name":"fanren","date":"2020-07-25",
                          "rating":{"score":9.3}}]}
                        """, MediaType.APPLICATION_JSON));

        MetadataDetails details = tmdbDetails("凡人修仙传", "2020");
        bridge.enrich(details, 1);

        assertEquals("8.9", details.getRatings().get("douban"), "豆瓣评分并入");
        assertEquals("9.3", details.getRatings().get("bangumi"), "Bangumi 评分并入");
        assertEquals("8.5", details.getRatings().get("tmdb"), "TMDB 评分保留");
        assertEquals("36245887", details.getExternalIds().get("douban"), "取剧集条目而非同名片 movie 干扰项");
        assertEquals("332432", details.getExternalIds().get("bangumi"));
        assertTrue(details.getExternalIds().containsKey("tmdb"));
        assertEquals("凡人修仙传", details.getName(), "条目身份字段不动");
        assertEquals(60, details.getTotalEpisodes());
        assertNull(details.getNextAirTime());
        server.verify();
    }

    /** 同名异剧年份拦截:候选年份与 TMDB 全不沾(±1)→ 不桥,也不发 rexxar 二跳。 */
    @Test
    void yearGateRejectsSameNameDifferentYear() {
        server.expect(once(), requestTo(FANREN_SUGGEST)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"id\":\"123\",\"title\":\"凡人修仙传\",\"year\":\"2018\",\"type\":\"episode\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BANGUMI_SEARCH)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        MetadataDetails details = tmdbDetails("凡人修仙传", "2024");
        bridge.enrich(details, 1);

        assertFalse(details.getRatings().containsKey("douban"), "同名异剧不桥");
        assertFalse(details.getExternalIds().containsKey("douban"));
        server.verify();
    }

    /** 多季合一 TMDB 条目(瑞克和莫蒂 S1 是 2013,当前季 2024):season≥2 放行年份门禁。 */
    @Test
    void multiSeasonEntrySkipsYearGate() {
        server.expect(once(), requestTo(RICK_SUGGEST)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"id\":\"246971\",\"title\":\"瑞克和莫蒂\",\"year\":\"2013\",\"type\":\"episode\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://m.douban.com/rexxar/api/v2/tv/246971")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"title\":\"瑞克和莫蒂\",\"rating\":{\"value\":9.7}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BANGUMI_SEARCH)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        MetadataDetails details = tmdbDetails("瑞克和莫蒂", "2013");
        bridge.enrich(details, 10);

        assertEquals("9.7", details.getRatings().get("douban"), "多季条目年份放行后命中");
        server.verify();
    }

    /** 豆瓣侧失败静默(不影响 Bangumi 路),桥接结果负缓存,6h 内不再重试。 */
    @Test
    void doubanFailureIsSilentAndCached() {
        server.expect(once(), requestTo(FANREN_SUGGEST)).andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());
        server.expect(once(), requestTo(BANGUMI_SEARCH)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        MetadataDetails details = tmdbDetails("凡人修仙传", "2020");
        bridge.enrich(details, 1);
        bridge.enrich(details, 1); // 缓存:不再发请求

        assertFalse(details.getRatings().containsKey("douban"));
        assertEquals("8.5", details.getRatings().get("tmdb"), "主链评分不受桥接失败影响");
        server.verify();
    }

    /** rexxar 未开分(rating.value=0):同属无分,不并入也不崩。 */
    @Test
    void unratedDoubanSubjectSkipped() {
        server.expect(once(), requestTo(FANREN_SUGGEST)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"id\":\"36245887\",\"title\":\"凡人修仙传\",\"year\":\"2020\",\"type\":\"episode\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FANREN_REXXAR)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"title\":\"凡人修仙传\",\"rating\":{\"value\":0}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BANGUMI_SEARCH)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        MetadataDetails details = tmdbDetails("凡人修仙传", "2020");
        bridge.enrich(details, 1);

        assertFalse(details.getRatings().containsKey("douban"), "未开分不并入");
        server.verify();
    }

    /** 标题非整词相等(子串模仿者)不桥:不发 rexxar,标题不同也不发 Bangumi 匹配外的请求。 */
    @Test
    void titleMismatchSkipsDouban() {
        server.expect(once(), requestTo(FANREN_SUGGEST)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"id\":\"888\",\"title\":\"凡人修仙传之乱星海\",\"year\":\"2020\",\"type\":\"episode\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BANGUMI_SEARCH)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        MetadataDetails details = tmdbDetails("凡人修仙传", "2020");
        bridge.enrich(details, 1);

        assertFalse(details.getRatings().containsKey("douban"));
        server.verify();
    }

    /** 豆瓣订阅(诛仙第四季,线上 subject 37472443):豆瓣/TMDB 已就位只补 Bangumi;原名搜空按剔季缀基名补搜一轮。 */
    @Test
    void doubanEntryGainsBangumiOnlyViaBaseQuery() {
        server.expect(once(), requestTo(BANGUMI_SEARCH)).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"keyword\":\"诛仙 第四季\"")))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BANGUMI_SEARCH)).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"keyword\":\"诛仙\"")))
                .andRespond(withSuccess("""
                        {"data":[{"id":4567,"name_cn":"诛仙","name":"zhuxian","date":"2026-05-01",
                          "rating":{"score":8.7}}]}
                        """, MediaType.APPLICATION_JSON));

        MetadataDetails details = new MetadataDetails();
        details.setProvider(DoubanMetadataProvider.NAME);
        details.setId("37472443");
        details.setName("诛仙 第四季");
        details.setYear("2026");
        details.setRating("8.2");
        details.setRatings(new LinkedHashMap<>(Map.of("douban", "8.2", "tmdb", "7.9")));
        details.setExternalIds(new LinkedHashMap<>(Map.of("douban", "37472443", "tmdb", "206484")));
        bridge.enrich(details, 4);

        assertEquals("8.7", details.getRatings().get("bangumi"), "Bangumi 评分并入");
        assertEquals("4567", details.getExternalIds().get("bangumi"), "Bangumi 条目 id 并入(详情页外链用)");
        assertEquals("8.2", details.getRatings().get("douban"), "已有评分不动");
        assertEquals("206484", details.getExternalIds().get("tmdb"));
        server.verify(); // 未发豆瓣 suggest/rexxar:源自身与已带外链的源跳过
    }

    /** Bangumi 订阅:只补豆瓣(suggest+rexxar),不回搜 Bangumi 自身。 */
    @Test
    void bangumiEntryGainsDoubanOnly() {
        server.expect(once(), requestTo(FANREN_SUGGEST)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"id\":\"36245887\",\"title\":\"凡人修仙传\",\"year\":\"2020\",\"type\":\"episode\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FANREN_REXXAR)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"title\":\"凡人修仙传\",\"rating\":{\"value\":8.9}}", MediaType.APPLICATION_JSON));

        MetadataDetails details = new MetadataDetails();
        details.setProvider(BangumiMetadataProvider.NAME);
        details.setId("332432");
        details.setName("凡人修仙传");
        details.setYear("2020");
        details.setRating("9.3");
        details.setRatings(new LinkedHashMap<>(Map.of("bangumi", "9.3")));
        details.setExternalIds(new LinkedHashMap<>(Map.of("bangumi", "332432")));
        bridge.enrich(details, 1);

        assertEquals("8.9", details.getRatings().get("douban"), "豆瓣评分并入");
        assertEquals("36245887", details.getExternalIds().get("douban"));
        assertEquals("9.3", details.getRatings().get("bangumi"));
        server.verify();
    }

    private MetadataDetails tmdbDetails(String name, String year) {
        MetadataDetails details = new MetadataDetails();
        details.setProvider(TmdbMetadataProvider.NAME);
        details.setId("24637");
        details.setName(name);
        details.setYear(year);
        details.setRating("8.5");
        details.setRatings(new LinkedHashMap<>(Map.of(TmdbMetadataProvider.NAME, "8.5")));
        details.setExternalIds(new LinkedHashMap<>(Map.of(TmdbMetadataProvider.NAME, "24637")));
        details.setCover("https://media.themoviedb.org/t/p/cover.jpg");
        details.setTotalEpisodes(60);
        return details;
    }
}
