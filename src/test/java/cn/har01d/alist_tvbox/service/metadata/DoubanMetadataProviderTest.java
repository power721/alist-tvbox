package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.EpisodeAirDate;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
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
    void mergeTmdbCoverWinsOverDoubanImageHost() {
        MetadataDetails douban = new MetadataDetails();
        douban.setName("九门");
        douban.setCover("https://img9.doubanio.com/view/photo/m_ratio_poster/public/p1.jpg");
        MetadataDetails tmdb = new MetadataDetails();
        tmdb.setCover("https://media.themoviedb.org/t/p/w300_and_h450_bestv2/tmdb.jpg");
        DoubanMetadataProvider.mergeTmdbDetails(douban, tmdb);
        assertEquals("https://media.themoviedb.org/t/p/w300_and_h450_bestv2/tmdb.jpg", douban.getCover(),
                "豆瓣 view/photo 图床防盗链频发,桥接命中时 TMDB 封面优先");

        // 桥接未提供封面时保持豆瓣(无 TMDB 可选)
        douban.setCover("https://img9.doubanio.com/view/photo/m_ratio_poster/public/p1.jpg");
        DoubanMetadataProvider.mergeTmdbDetails(douban, new MetadataDetails());
        assertEquals("https://img9.doubanio.com/view/photo/m_ratio_poster/public/p1.jpg", douban.getCover());
    }

    @Test
    void mergeTmdbFillsOverviewWhenDoubanBlank() {
        // 豆瓣侧无简介来源(rexxar/本地库/详情页都没有该字段),桥接命中时 TMDB 简介补上
        MetadataDetails douban = new MetadataDetails();
        douban.setName("邻人可疑");
        MetadataDetails tmdb = new MetadataDetails();
        tmdb.setOverview("一次搬家让两个家庭狭路相逢……");
        DoubanMetadataProvider.mergeTmdbDetails(douban, tmdb);
        assertEquals("一次搬家让两个家庭狭路相逢……", douban.getOverview());

        // 豆瓣已有简介不被覆盖;TMDB 缺简介时保持原状
        douban.setOverview("豆瓣简介");
        DoubanMetadataProvider.mergeTmdbDetails(douban, new MetadataDetails());
        assertEquals("豆瓣简介", douban.getOverview());
    }

    @Test
    void splitNamesHandlesCommaAndSlashSeparators() {
        // 豆瓣库分隔符混杂:类型/演员逗号(中/英文),地区/语言 " / "
        assertEquals(List.of("剧情", "奇幻", "冒险"), DoubanMetadataProvider.splitNames("剧情,奇幻,冒险", 8));
        assertEquals(List.of("陈伟霆", "陈瑶", "曾舜晞"), DoubanMetadataProvider.splitNames("陈伟霆,陈瑶,曾舜晞", 8));
        assertEquals(List.of("中国大陆", "中国香港"), DoubanMetadataProvider.splitNames("中国大陆 / 中国香港", 8));
        assertNull(DoubanMetadataProvider.splitNames(null, 8));
        assertNull(DoubanMetadataProvider.splitNames("  ", 8));
    }

    @Test
    void mergeTmdbCombinesRatingsLinksAndReplacesAvatarlessCast() {
        MetadataDetails douban = new MetadataDetails();
        douban.setProvider("douban");
        douban.setName("九门");
        douban.setRating("6.8");
        douban.setRatings(new java.util.LinkedHashMap<>(java.util.Map.of("douban", "6.8")));
        douban.setExternalIds(new java.util.LinkedHashMap<>(java.util.Map.of("douban", "37123")));
        douban.setCast(List.of(new cn.har01d.alist_tvbox.dto.CastMember("陈伟霆,陈瑶", null, null))); // 本地库纯名字形态

        MetadataDetails tmdb = new MetadataDetails();
        tmdb.setId("141888");
        tmdb.setRating("7.5");
        tmdb.setRatings(new java.util.LinkedHashMap<>(java.util.Map.of("tmdb", "7.5")));
        tmdb.setCast(List.of(
                new cn.har01d.alist_tvbox.dto.CastMember("陈伟霆", "二月红", "https://media.themoviedb.org/t/p/w185/a.jpg"),
                new cn.har01d.alist_tvbox.dto.CastMember("陈瑶", "尹新月", "https://media.themoviedb.org/t/p/w185/b.jpg")));

        DoubanMetadataProvider.mergeTmdbDetails(douban, tmdb);

        assertEquals("6.8", douban.getRating()); // 主评分仍是豆瓣
        assertEquals(2, douban.getRatings().size(), "多源评分并存:豆瓣 6.8 + TMDB 7.5");
        assertEquals("7.5", douban.getRatings().get("tmdb"));
        assertEquals("141888", douban.getExternalIds().get("tmdb"), "跨源条目 id 并入(详情页外链)");
        assertEquals("37123", douban.getExternalIds().get("douban"));
        assertEquals(2, douban.getCast().size(), "无头像的本地库卡司被 TMDB 头像卡司替换");
        assertEquals("二月红", douban.getCast().get(0).getRole());
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
                null, new MetadataHttp(null), new MetadataHealth(), empty, null, null, null, null);
        assertNull(off.doubanCookie()); // 未配置 → 详情页增强关闭

        Setting setting = new Setting();
        setting.setName("douban_cookie");
        setting.setValue("bid=abc; ll=118211");
        SettingRepository configured = Mockito.mock(SettingRepository.class);
        Mockito.when(configured.findById("douban_cookie")).thenReturn(Optional.of(setting));
        DoubanMetadataProvider on = new DoubanMetadataProvider(
                null, new MetadataHttp(null), new MetadataHealth(), configured, null, null, null, null);
        assertEquals("bid=abc; ll=118211", on.doubanCookie());
    }

    // ---------- 名称桥接(播出时间轴豆瓣源兜底) ----------

    @Test
    void seasonHintParsesChineseAndArabicAndTrailingDigit() {
        assertEquals(4, DoubanMetadataProvider.seasonHintOf("诛仙 第四季"));
        assertEquals(9, DoubanMetadataProvider.seasonHintOf("瑞克和莫蒂 第九季"));
        assertEquals(2, DoubanMetadataProvider.seasonHintOf("庆余年 第二部"));
        assertEquals(2, DoubanMetadataProvider.seasonHintOf("杀人者的购物中心2"));
        assertEquals(23, DoubanMetadataProvider.seasonHintOf("某番 第二十三季"));
        assertEquals(0, DoubanMetadataProvider.seasonHintOf("九门"));
        assertEquals(0, DoubanMetadataProvider.seasonHintOf("  "));
    }

    @Test
    void stripSeasonMarkLeavesCleanQuery() {
        assertEquals("诛仙", DoubanMetadataProvider.stripSeasonMark("诛仙 第四季"));
        assertEquals("瑞克和莫蒂", DoubanMetadataProvider.stripSeasonMark("瑞克和莫蒂 第九季"));
        assertEquals("杀人者的购物中心", DoubanMetadataProvider.stripSeasonMark("杀人者的购物中心2"));
        assertEquals("九门", DoubanMetadataProvider.stripSeasonMark("九门"));
        assertNull(DoubanMetadataProvider.stripSeasonMark(null));
    }

    @Test
    void parseChineseNumeralCoversSeasonRange() {
        assertEquals(1, DoubanMetadataProvider.parseChineseNumeral("一"));
        assertEquals(9, DoubanMetadataProvider.parseChineseNumeral("九"));
        assertEquals(10, DoubanMetadataProvider.parseChineseNumeral("十"));
        assertEquals(19, DoubanMetadataProvider.parseChineseNumeral("十九"));
        assertEquals(23, DoubanMetadataProvider.parseChineseNumeral("二十三"));
        assertEquals(0, DoubanMetadataProvider.parseChineseNumeral("第"));
        assertEquals(4, DoubanMetadataProvider.parseNumber("4"));
        assertEquals(0, DoubanMetadataProvider.parseNumber(""));
    }

    @Test
    void nameBridgePrefersExactSameNameWithMatchingYear() {
        // 「悬案」线上实测形态:同名 2026 正主 + 2018 旧片,年份门禁必须选 2026
        TmdbMetadataProvider tmdb = Mockito.mock(TmdbMetadataProvider.class);
        Mockito.when(tmdb.search("悬案")).thenReturn(List.of(
                item("273114", "悬案", "2026"), item("245703", "悬案解码", "2025"), item("76582", "悬案", "2018")));
        Mockito.when(tmdb.details("273114", 1)).thenReturn(tmdbDetails());
        DoubanMetadataProvider provider = new DoubanMetadataProvider(
                null, new MetadataHttp(null), new MetadataHealth(), null, tmdb, null, null, null);

        MetadataDetails douban = new MetadataDetails();
        douban.setName("悬案");
        douban.setYear("2026");
        provider.bridgeTmdbByName(douban, 1);

        Mockito.verify(tmdb).details("273114", 1); // 2018 同名旧片被年份拦下
        assertEquals(MetadataDetails.STATUS_RETURNING, douban.getStatus());
        assertEquals(456000L, douban.getNextAirTime());
    }

    @Test
    void nameBridgeGivesUpWhenAllSameNameCandidatesMissYear() {
        TmdbMetadataProvider tmdb = Mockito.mock(TmdbMetadataProvider.class);
        Mockito.when(tmdb.search("悬案")).thenReturn(List.of(item("76582", "悬案", "2018")));
        DoubanMetadataProvider provider = new DoubanMetadataProvider(
                null, new MetadataHttp(null), new MetadataHealth(), null, tmdb, null, null, null);

        MetadataDetails douban = new MetadataDetails();
        douban.setName("悬案");
        douban.setYear("2026");
        provider.bridgeTmdbByName(douban, 1);

        Mockito.verify(tmdb, Mockito.never()).details(Mockito.anyString(), Mockito.anyInt());
        assertNull(douban.getNextAirTime());
    }

    @Test
    void nameBridgeSkipsSubstringImitation() {
        // 「悬案」⊂「悬案解码」:归一化整词相等才算命中,子串嵌套的模仿者不碰
        TmdbMetadataProvider tmdb = Mockito.mock(TmdbMetadataProvider.class);
        Mockito.when(tmdb.search("悬案")).thenReturn(List.of(item("245703", "悬案解码 Dept. Q", "2025")));
        DoubanMetadataProvider provider = new DoubanMetadataProvider(
                null, new MetadataHttp(null), new MetadataHealth(), null, tmdb, null, null, null);

        MetadataDetails douban = new MetadataDetails();
        douban.setName("悬案");
        douban.setYear("2026");
        provider.bridgeTmdbByName(douban, 1);

        Mockito.verify(tmdb, Mockito.never()).details(Mockito.anyString(), Mockito.anyInt());
    }

    @Test
    void nameBridgeRelaxesYearGateForLongRunningSeason() {
        // 「诛仙 第四季」:TMDB 条目首播 2022,与豆瓣年份 2026 必然对不上,多季放行
        TmdbMetadataProvider tmdb = Mockito.mock(TmdbMetadataProvider.class);
        Mockito.when(tmdb.search("诛仙")).thenReturn(List.of(
                item("206484", "诛仙", "2022"), item("293875", "诛仙合集篇", "2025")));
        Mockito.when(tmdb.details("206484", 4)).thenReturn(tmdbDetails());
        DoubanMetadataProvider provider = new DoubanMetadataProvider(
                null, new MetadataHttp(null), new MetadataHealth(), null, tmdb, null, null, null);

        MetadataDetails douban = new MetadataDetails();
        douban.setName("诛仙 第四季");
        douban.setYear("2026");
        provider.bridgeTmdbByName(douban, 4);

        Mockito.verify(tmdb).details("206484", 4);
        assertEquals(456000L, douban.getNextAirTime());
    }

    @Test
    void nameBridgeUsesTitleSeasonHintWhenSubscriptionSeasonIsOne() {
        // 豆瓣分条目形态:「瑞克和莫蒂 第九季」season=1,标题季标 9 才是 TMDB 的季号
        TmdbMetadataProvider tmdb = Mockito.mock(TmdbMetadataProvider.class);
        Mockito.when(tmdb.search("瑞克和莫蒂")).thenReturn(List.of(
                item("60625", "瑞克和莫蒂", "2013"), item("202282", "瑞克和莫蒂：日漫版", "2024")));
        Mockito.when(tmdb.details("60625", 9)).thenReturn(tmdbDetails());
        DoubanMetadataProvider provider = new DoubanMetadataProvider(
                null, new MetadataHttp(null), new MetadataHealth(), null, tmdb, null, null, null);

        MetadataDetails douban = new MetadataDetails();
        douban.setName("瑞克和莫蒂 第九季");
        douban.setYear("2026");
        provider.bridgeTmdbByName(douban, 1);

        Mockito.verify(tmdb).details("60625", 9);
        assertEquals(456000L, douban.getNextAirTime());
    }

    @Test
    void nameBridgeMatchesTrailingDigitSeasonAgainstBaseName() {
        // 「杀人者的购物中心2」:TMDB 是同名条目的 S2,基名整词命中 + 尾数字季号
        TmdbMetadataProvider tmdb = Mockito.mock(TmdbMetadataProvider.class);
        Mockito.when(tmdb.search("杀人者的购物中心")).thenReturn(List.of(item("215072", "杀人者的购物中心", "2024")));
        Mockito.when(tmdb.details("215072", 2)).thenReturn(tmdbDetails());
        DoubanMetadataProvider provider = new DoubanMetadataProvider(
                null, new MetadataHttp(null), new MetadataHealth(), null, tmdb, null, null, null);

        MetadataDetails douban = new MetadataDetails();
        douban.setName("杀人者的购物中心2");
        douban.setYear("2026");
        provider.bridgeTmdbByName(douban, 1);

        Mockito.verify(tmdb).details("215072", 2);
        assertEquals(456000L, douban.getNextAirTime());
    }

    @Test
    void nameBridgeSkippedWhenImdbBridgeAlreadyProvidedSchedule() {
        TmdbMetadataProvider tmdb = Mockito.mock(TmdbMetadataProvider.class);
        DoubanMetadataProvider provider = new DoubanMetadataProvider(
                null, new MetadataHttp(null), new MetadataHealth(), null, tmdb, null, null, null);

        MetadataDetails douban = new MetadataDetails();
        douban.setName("九门");
        douban.setNextAirTime(1L); // IMDb 桥接已带出日程
        provider.bridgeTmdbByName(douban, 1);

        Mockito.verifyNoInteractions(tmdb);
    }

    @Test
    void nameBridgeRejectsSeasonMissingOnTmdb() {
        // TMDB 无该季(季标误判):details 无集数 → 宁缺毋滥,不合并
        TmdbMetadataProvider tmdb = Mockito.mock(TmdbMetadataProvider.class);
        Mockito.when(tmdb.search("某剧")).thenReturn(List.of(item("123", "某剧", "2026")));
        Mockito.when(tmdb.details("123", 7)).thenReturn(new MetadataDetails()); // 无 totalEpisodes
        DoubanMetadataProvider provider = new DoubanMetadataProvider(
                null, new MetadataHttp(null), new MetadataHealth(), null, tmdb, null, null, null);

        MetadataDetails douban = new MetadataDetails();
        douban.setName("某剧 第七季");
        douban.setYear("2026");
        provider.bridgeTmdbByName(douban, 1);

        Mockito.verify(tmdb).details("123", 7);
        assertNull(douban.getNextAirTime());
    }

    private static MetadataSearchItem item(String id, String name, String year) {
        MetadataSearchItem entry = new MetadataSearchItem();
        entry.setProvider("tmdb");
        entry.setId(id);
        entry.setName(name);
        entry.setYear(year);
        return entry;
    }

    private static MetadataDetails tmdbDetails() {
        MetadataDetails tmdb = new MetadataDetails();
        tmdb.setProvider("tmdb");
        tmdb.setStatus(MetadataDetails.STATUS_RETURNING);
        tmdb.setTotalEpisodes(33);
        tmdb.setAiredEpisodes(29);
        tmdb.setNextAirTime(456000L);
        tmdb.setUpcoming(List.of(new EpisodeAirDate(30, 456000L)));
        return tmdb;
    }
}
