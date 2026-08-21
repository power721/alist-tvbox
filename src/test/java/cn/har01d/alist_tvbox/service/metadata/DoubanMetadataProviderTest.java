package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.EpisodeAirDate;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 豆瓣 provider 详情页解析与 IMDb→TMDB 桥接合并:又名/IMDb 提取(真实 #info 结构样本)、
 * 字段合并优先级(豆瓣保身份,TMDB 补日程)、详情页全局限速。
 */
class DoubanMetadataProviderTest {

    private static final String SUBJECT_HTML = """
            <html><body>
            <div id="info">
                <span ><span class='pl'>类型:</span> 剧情 / 科幻</span><br/>
                <span class="pl">制片国家/地区:</span> 中国大陆<br/>
                <span class="pl">语言:</span> 汉语普通话<br/>
                <span class="pl">首播:</span> 2024-06-01(中国大陆)<br/>
                <span class="pl">集数:</span> 24<br/>
                <span class="pl">单集片长:</span> 45分钟<br/>
                <span class="pl">又名:</span> 三体第一季&nbsp;/&nbsp;Three-Body Season 1<br/>
                <span class="pl">IMDb:</span> tt1234567<br/>
            </div>
            </body></html>
            """;

    @Test
    void parseAliasesFromSubjectInfo() {
        List<String> aliases = DoubanMetadataProvider.parseAliases(SUBJECT_HTML);
        assertEquals(List.of("三体第一季", "Three-Body Season 1"), aliases);
    }

    @Test
    void parseAliasesEmptyWhenFieldMissing() {
        String html = "<html><body><div id='info'><span class='pl'>集数:</span> 12<br/></div></body></html>";
        assertTrue(DoubanMetadataProvider.parseAliases(html).isEmpty());
        assertTrue(DoubanMetadataProvider.parseAliases("").isEmpty());
        assertTrue(DoubanMetadataProvider.parseAliases(null).isEmpty());
    }

    @Test
    void parseImdbIdFromSubjectInfo() {
        assertEquals("tt1234567", DoubanMetadataProvider.parseImdbId(SUBJECT_HTML));
        String linked = "<div id='info'><span class='pl'>IMDb:</span> <a href='https://www.imdb.com/title/tt7654321/'>tt7654321</a><br/></div>";
        assertEquals("tt7654321", DoubanMetadataProvider.parseImdbId(linked));
        assertNull(DoubanMetadataProvider.parseImdbId("<div id='info'>集数: 12</div>"));
        assertNull(DoubanMetadataProvider.parseImdbId(null));
    }

    @Test
    void mergeTmdbKeepsDoubanIdentityAndFillsSchedule() {
        MetadataDetails douban = new MetadataDetails();
        douban.setProvider("douban");
        douban.setName("三体");
        douban.setTotalEpisodes(24); // 豆瓣 rexxar 集数优先
        douban.setAliases(List.of("三体第一季"));

        MetadataDetails tmdb = new MetadataDetails();
        tmdb.setStatus(MetadataDetails.STATUS_RETURNING);
        tmdb.setTotalEpisodes(8);
        tmdb.setAiredEpisodes(6);
        tmdb.setNextAirTime(456000L);
        tmdb.setUpcoming(List.of(new EpisodeAirDate(7, 400000L), new EpisodeAirDate(8, 456000L)));
        tmdb.setTotalSeasons(2);
        tmdb.setAliases(List.of("三体", "3 Body Problem"));

        DoubanMetadataProvider.mergeTmdbDetails(douban, tmdb);

        assertEquals(24, douban.getTotalEpisodes()); // TMDB 集数不覆盖豆瓣
        assertEquals(MetadataDetails.STATUS_RETURNING, douban.getStatus());
        assertEquals(6, douban.getAiredEpisodes());
        assertEquals(456000L, douban.getNextAirTime());
        assertEquals(2, douban.getUpcoming().size());
        assertEquals(2, douban.getTotalSeasons());
        assertEquals(List.of("三体第一季", "三体", "3 Body Problem"), douban.getAliases()); // 豆瓣又名在前,重名去重
    }

    @Test
    void mergeTmdbToleratesNullSource() {
        MetadataDetails douban = new MetadataDetails();
        douban.setName("三体");
        DoubanMetadataProvider.mergeTmdbDetails(douban, null);
        assertEquals("三体", douban.getName());
        assertEquals(MetadataDetails.STATUS_UNKNOWN, douban.getStatus()); // 字段默认值,未被污染
    }

    @Test
    void pageRateLimiterEnforcesMinimumInterval() throws Exception {
        DoubanMetadataProvider.PageRateLimiter limiter = new DoubanMetadataProvider.PageRateLimiter(120);
        long start = System.currentTimeMillis();
        limiter.acquire();
        limiter.acquire();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 110, "second acquire should wait for interval, elapsed=" + elapsed);
    }

    @Test
    void cookieSettingControlsSubjectPageFetching() {
        SettingRepository empty = Mockito.mock(SettingRepository.class);
        Mockito.when(empty.findById(Mockito.anyString())).thenReturn(Optional.empty());
        DoubanMetadataProvider off = new DoubanMetadataProvider(
                null, new MetadataHttp(null), new MetadataHealth(), empty, null);
        assertNull(off.doubanCookie()); // 未配置 → 详情页增强关闭

        Setting setting = new Setting();
        setting.setName("douban_cookie");
        setting.setValue("bid=abc; ll=118211");
        SettingRepository configured = Mockito.mock(SettingRepository.class);
        Mockito.when(configured.findById("douban_cookie")).thenReturn(Optional.of(setting));
        DoubanMetadataProvider on = new DoubanMetadataProvider(
                null, new MetadataHttp(null), new MetadataHealth(), configured, null);
        assertEquals("bid=abc; ll=118211", on.doubanCookie());
    }
}
