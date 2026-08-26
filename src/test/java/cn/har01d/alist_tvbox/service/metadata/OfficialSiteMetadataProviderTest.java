package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.util.Constants;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 官方平台"更新至"文案解析(实测样例:优酷"更新至22/22集"、爱奇艺"更新至第 12 集"、完结"全集/全24集")。
 */
class OfficialSiteMetadataProviderTest {

    private final OfficialSiteMetadataProvider provider =
            new OfficialSiteMetadataProvider(null, new MetadataHttp(null), new MetadataHealth());

    @Test
    void progressWithTotal() {
        MetadataDetails details = new MetadataDetails();
        OfficialSiteMetadataProvider.applyUpdateText(details, "更新至22/22集");
        assertEquals(22, details.getAiredEpisodes());
        assertEquals(22, details.getTotalEpisodes());
        assertEquals(MetadataDetails.STATUS_ENDED, details.getStatus());
    }

    @Test
    void progressOngoing() {
        MetadataDetails details = new MetadataDetails();
        OfficialSiteMetadataProvider.applyUpdateText(details, "更新至12/24集");
        assertEquals(12, details.getAiredEpisodes());
        assertEquals(24, details.getTotalEpisodes());
        assertEquals(MetadataDetails.STATUS_RETURNING, details.getStatus());
    }

    @Test
    void updateToOnly() {
        MetadataDetails details = new MetadataDetails();
        OfficialSiteMetadataProvider.applyUpdateText(details, "每周五六更新至第 18 集");
        assertEquals(18, details.getAiredEpisodes());
        assertNull(details.getTotalEpisodes());
        assertEquals(MetadataDetails.STATUS_RETURNING, details.getStatus());
    }

    @Test
    void endedText() {
        MetadataDetails details = new MetadataDetails();
        OfficialSiteMetadataProvider.applyUpdateText(details, "全24集");
        assertEquals(24, details.getAiredEpisodes());
        assertEquals(24, details.getTotalEpisodes());
        assertEquals(MetadataDetails.STATUS_ENDED, details.getStatus());
    }

    @Test
    void noMatch() {
        MetadataDetails details = new MetadataDetails();
        OfficialSiteMetadataProvider.applyUpdateText(details, "预告片");
        assertNull(details.getAiredEpisodes());
        assertEquals(MetadataDetails.STATUS_UNKNOWN, details.getStatus());
    }

    @Test
    void episodeDatesKeepRecentAiredForTimeline() {
        ZoneId zone = ZoneId.of(Constants.ZONE_ID);
        LocalDate today = LocalDate.now(zone);
        MetadataDetails details = new MetadataDetails();
        OfficialSiteMetadataProvider.applyEpisodeDates(details,
                List.of(today.minusDays(7), today.minusDays(1), today, today.plusDays(6)),
                today.atTime(21, 0).atZone(zone).toInstant().toEpochMilli());

        assertEquals(3, details.getAiredEpisodes(), "20:00 后刷新,昨日/今日集都算已播");
        assertEquals(3, details.getUpcoming().size(), "昨日/今日已播 + 未来集都进日程,7 天前的已播集不进");
        assertEquals(today.plusDays(6).atTime(20, 0).atZone(zone).toInstant().toEpochMilli(),
                details.getNextAirTime(), "nextAirTime 仍严格取未来日期");
    }

    @Test
    void episodeDatesNotAiredBeforeAirHourOnAirDay() {
        ZoneId zone = ZoneId.of(Constants.ZONE_ID);
        LocalDate today = LocalDate.now(zone);
        // 线上形态:点映礼 5 集同日 20:00 上架,前 28 集已播;中午刷新不能把当日集算进已播
        List<LocalDate> dates = new java.util.ArrayList<>();
        for (int i = 1; i <= 28; i++) {
            dates.add(today.minusDays(30 - i));
        }
        for (int i = 0; i < 5; i++) {
            dates.add(today);
        }
        MetadataDetails details = new MetadataDetails();
        OfficialSiteMetadataProvider.applyEpisodeDates(details, dates,
                today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli());

        assertEquals(28, details.getAiredEpisodes(), "播出日当天 20:00 前,当日集不算已播");
        assertEquals(5, details.getUpcoming().size(), "当日待播集进日程(时间轴「今天」分组)");
        assertEquals(today.atTime(20, 0).atZone(zone).toInstant().toEpochMilli(),
                details.getNextAirTime());
        assertEquals(MetadataDetails.STATUS_RETURNING, details.getStatus());
    }

    @Test
    void episodeDatesAiredAfterAirHourOnAirDay() {
        ZoneId zone = ZoneId.of(Constants.ZONE_ID);
        LocalDate today = LocalDate.now(zone);
        List<LocalDate> dates = new java.util.ArrayList<>();
        for (int i = 1; i <= 28; i++) {
            dates.add(today.minusDays(30 - i));
        }
        for (int i = 0; i < 5; i++) {
            dates.add(today);
        }
        MetadataDetails details = new MetadataDetails();
        OfficialSiteMetadataProvider.applyEpisodeDates(details, dates,
                today.atTime(21, 0).atZone(zone).toInstant().toEpochMilli());

        assertEquals(33, details.getAiredEpisodes(), "播出时刻(20:00)一过即算已播");
        assertEquals(5, details.getUpcoming().size(), "当日已播集仍在日程(昨天/今天窗口)");
        assertNull(details.getNextAirTime());
    }
}
