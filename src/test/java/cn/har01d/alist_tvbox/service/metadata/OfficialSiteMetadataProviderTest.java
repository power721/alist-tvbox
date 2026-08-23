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
                List.of(today.minusDays(7), today.minusDays(1), today, today.plusDays(6)));

        assertEquals(3, details.getAiredEpisodes());
        assertEquals(3, details.getUpcoming().size(), "昨日/今日已播 + 未来集都进日程,7 天前的已播集不进");
        assertEquals(today.plusDays(6).atTime(20, 0).atZone(zone).toInstant().toEpochMilli(),
                details.getNextAirTime(), "nextAirTime 仍严格取未来日期");
    }
}
