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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * B站排播时刻校正口径:TMDB air_date 只有日期(默认填当日 20:00),B站独播番剧实际更新时刻
 * 各剧不同(凡人修仙传每周六 11:00,线上第 189 集 airTime 被填成 8-29 20:00 偏晚 9 小时)。
 * 时刻 = 官方分集已上线集(status=13)pub_time 最近 8 集众数;未上线集(status=2)pub_time
 * 是占位值不参与;日期仍以 TMDB 为准只换时分;aired/nextAirTime 按校正后时刻重数。
 */
class BilibiliScheduleRefinerTest {
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);
    private static final String SEARCH_URL =
            "https://search.bilibili.com/bangumi?keyword=%E5%87%A1%E4%BA%BA%E4%BF%AE%E4%BB%99%E4%BC%A0";
    private static final String SEASON_URL = "https://api.bilibili.com/pgc/view/web/season?season_id=28747";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final BilibiliScheduleRefiner refiner;

    BilibiliScheduleRefinerTest() {
        MetadataHttp metadataHttp = Mockito.mock(MetadataHttp.class);
        Mockito.when(metadataHttp.create()).thenReturn(restTemplate);
        refiner = new BilibiliScheduleRefiner(metadataHttp);
    }

    /** 线上凡人修仙传形态:186 已播(昨日 20:00)、189 未来(+6 天 20:00),官方周六 11:00 更新。 */
    @Test
    void refineRewritesAirTimeToOfficialClock() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate next = today.plusDays(6);
        server.expect(once(), requestTo(SEARCH_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(searchHtml("凡人修仙传"), MediaType.TEXT_HTML));
        server.expect(once(), requestTo(SEASON_URL))
                .andRespond(withSuccess(seasonBody(
                        episode(186, 13, today.minusDays(1).atTime(11, 0)),
                        episode(187, 13, today.minusDays(8).atTime(11, 0)),
                        episode(188, 13, today.minusDays(15).atTime(11, 0)),
                        episode(189, 2, today.minusDays(1).atTime(11, 15))),
                        MediaType.APPLICATION_JSON));

        MetadataDetails details = tmdbBridgedDetails(today, next);
        refiner.refine(details);

        assertEquals(today.minusDays(1).atTime(11, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getEpisodes().get(0).getAirTime(), "已播集时刻校正为官方 11:00");
        assertEquals(next.atTime(11, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getEpisodes().get(1).getAirTime(), "未来集保留 TMDB 日期,只换官方时分");
        assertEquals(next.atTime(11, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getNextAirTime(), "nextAirTime 按校正后时刻重算");
        assertEquals(1, details.getAiredEpisodes(), "昨日 11:00 已播,+6 天未播");
        assertEquals(today.minusDays(1).atTime(11, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getUpcoming().get(0).getAirTime(), "日程快照(upcoming)时刻同步校正");
        assertEquals(next.atTime(11, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getUpcoming().get(1).getAirTime());
        assertEquals("ss28747", details.getExternalIds().get("bilibili"),
                "定位到 ss 即登记 B站条目 id(详情页 links 展开 B站官方链接)");
    }

    /** season 接口失败:静默保留 20:00 默认时刻,不炸详情链;B站条目 id 已登记不回滚。 */
    @Test
    void seasonFailureKeepsDefaultClock() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(SEARCH_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(searchHtml("凡人修仙传"), MediaType.TEXT_HTML));
        server.expect(once(), requestTo(SEASON_URL)).andRespond(withServerError());

        MetadataDetails details = tmdbBridgedDetails(today, today.plusDays(6));
        refiner.refine(details);

        assertEquals(today.minusDays(1).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getEpisodes().get(0).getAirTime(), "失败保留 20:00");
        assertEquals(today.plusDays(6).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getNextAirTime());
        assertEquals("ss28747", details.getExternalIds().get("bilibili"), "时刻取不到不影响条目外链登记");
    }

    /** 搜索卡标题非整词相等(番外/续作「星海飞驰篇」):不发 season 请求,时刻不动。 */
    @Test
    void titleMismatchSkipsSeasonLookup() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(SEARCH_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(searchHtml("凡人修仙传 星海飞驰篇"), MediaType.TEXT_HTML));

        MetadataDetails details = tmdbBridgedDetails(today, today.plusDays(6));
        refiner.refine(details);

        server.verify();
        assertEquals(today.plusDays(6).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getNextAirTime(), "未命中不校正");
    }

    /** 时刻规律 = 最近 8 集众数:早年 20:00 老集(超过 8 集)不稀释,未上线占位时刻不参与。 */
    @Test
    void clockTakesRecentModeIgnoringLegacyAndPendingSlots() {
        LocalDate today = LocalDate.now(ZONE);
        StringBuilder episodes = new StringBuilder();
        for (int i = 0; i < 10; i++) { // 早年 20:00 更新档,已超过最近 8 集窗口
            episodes.append(episode(100 + i, 13, today.minusWeeks(30 + i).atTime(20, 0))).append(',');
        }
        for (int i = 0; i < 8; i++) { // 近期档 11:00
            episodes.append(episode(180 + i, 13, today.minusWeeks(8 - i).atTime(11, 0))).append(',');
        }
        episodes.append(episode(189, 2, today.minusDays(1).atTime(23, 45))); // 未上线占位时刻
        server.expect(once(), requestTo(SEASON_URL))
                .andRespond(withSuccess("{\"result\":{\"episodes\":[" + episodes + "]}}", MediaType.APPLICATION_JSON));

        assertEquals(LocalTime.of(11, 0), refiner.seasonClock("ss28747"));
    }

    /** 播出时刻口径联动:当日 20:00 时刻校正到 11:00 后,12:00 刷新即算已播(TMDB 20:00 口径会偏晚)。 */
    @Test
    void applyScheduleClockRecountsAiredAndNext() {
        LocalDate today = LocalDate.now(ZONE);
        long now = today.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();
        MetadataDetails details = tmdbBridgedDetails(today, today);
        details.getEpisodes().get(0).setAirTime(today.minusDays(1).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli());

        BilibiliScheduleRefiner.applyScheduleClock(details, LocalTime.of(11, 0), now);

        assertEquals(2, details.getAiredEpisodes(), "当日集 11:00 已过 12:00 判定线,算已播");
        assertNull(details.getNextAirTime(), "全部已播,nextAirTime 清空");
        assertEquals(today.atTime(11, 0).atZone(ZONE).toInstant().toEpochMilli(),
                details.getEpisodes().get(1).getAirTime());
    }

    // ---------- 官方已播集数校正(2026-08-27):TMDB 对超长连载滞后 ----------
    // 线上形态:柯南 B站已上线到 1270,TMDB 停在 1212/1210,官方"已播"落后现实 60 集。
    // 口径=已上线最大集号而非计数:B站老集转会员/下架后 status 变化(柯南 status=13 仅
    // 523 条、首条 751),计数只反映"当前可看",不是官方播出进度。

    @Test
    void airedCountTakesMaxAiredEpisodeNumberNotCount() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(SEARCH_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(searchHtml("凡人修仙传"), MediaType.TEXT_HTML));
        server.expect(once(), requestTo(SEASON_URL))
                .andRespond(withSuccess(seasonBody(
                        episode(1268, 13, today.minusWeeks(2).atTime(11, 0)),
                        episode(1269, 13, today.minusWeeks(1).atTime(11, 0)),
                        episode(1270, 13, today.minusDays(2).atTime(11, 0)),
                        episode(1271, 2, today.plusDays(5).atTime(11, 15)),
                        "{\"title\":\"特别篇\",\"status\":13,\"pub_time\":"
                                + today.minusDays(3).atTime(11, 0).atZone(ZONE).toInstant().getEpochSecond() + "}"),
                        MediaType.APPLICATION_JSON));

        MetadataDetails details = new MetadataDetails();
        details.setName("凡人修仙传");
        details.setAiredEpisodes(1210); // TMDB 滞后值

        assertTrue(refiner.refineAiredCount(details));
        assertEquals(1270, details.getAiredEpisodes(), "已播=B站已上线最大集号,预告与文字条目不参与");
        assertEquals("ss28747", details.getExternalIds().get("bilibili"), "定位到 ss 即登记外链");
    }

    @Test
    void airedCountNeverShrinksFasterSource() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(SEARCH_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(searchHtml("凡人修仙传"), MediaType.TEXT_HTML));
        server.expect(once(), requestTo(SEASON_URL))
                .andRespond(withSuccess(seasonBody(episode(10, 13, today.minusDays(1).atTime(11, 0))),
                        MediaType.APPLICATION_JSON));

        MetadataDetails details = new MetadataDetails();
        details.setName("凡人修仙传");
        details.setAiredEpisodes(11); // 现值更快(B站尚未同步)

        assertFalse(refiner.refineAiredCount(details), "取大不减小:B站 10 < 现 11 不动");
        assertEquals(11, details.getAiredEpisodes());
    }

    /** TMDB 名称桥接产出的详情形态:分集列表 + 日程快照,时刻均为默认 20:00。 */
    private static MetadataDetails tmdbBridgedDetails(LocalDate today, LocalDate next) {
        MetadataDetails details = new MetadataDetails();
        details.setName("凡人修仙传");
        details.setExternalIds(new java.util.LinkedHashMap<>(java.util.Map.of("tmdb", "9521")));
        details.setEpisodes(List.of(
                new EpisodeInfo(186, "第 186 集", today.minusDays(1).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli()),
                new EpisodeInfo(189, "第 189 集", next.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli())));
        details.setUpcoming(List.of(
                new EpisodeAirDate(186, today.minusDays(1).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli()),
                new EpisodeAirDate(189, next.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli())));
        details.setAiredEpisodes(1);
        details.setNextAirTime(next.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli());
        return details;
    }

    private static String seasonBody(String... episodes) {
        return "{\"result\":{\"episodes\":[" + String.join(",", episodes) + "]}}";
    }

    /** 搜索结果卡 SSR 形态:封面 alt 标题(HTML 转义 + em 高亮)+ ss 主链接。 */
    private static String searchHtml(String title) {
        return "<div class=\"media-card\"><img src=\"//i0.hdslb.com/bfs/x.png\" alt=\"&lt;em class=&quot;keyword&quot;&gt;"
                + title + "&lt;/em&gt;\">"
                + "<a href=\"https://www.bilibili.com/bangumi/play/ss28747\"><button>立即观看</button></a></div>";
    }

    private static String episode(int number, int status, java.time.LocalDateTime air) {
        return "{\"title\":\"" + number + "\",\"status\":" + status
                + ",\"pub_time\":" + air.atZone(ZONE).toInstant().getEpochSecond() + "}";
    }
}
