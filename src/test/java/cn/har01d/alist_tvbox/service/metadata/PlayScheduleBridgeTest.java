package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.EpisodeAirDate;
import cn.har01d.alist_tvbox.dto.EpisodeInfo;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.util.Constants;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 平台排播时刻桥接:豆瓣「在哪儿看」vendors(线上形态:师兄太稳健 douban 36406417,双平台 12:00 更新)
 * → 平台页真实 HH:mm 校正 TMDB 日程的 20:00 约定。线上事实口径:爱奇艺 vendors url 是 www 域名
 * 播放页(须换 m 域名且带手机 UA —— m 站对桌面 UA 302 回 www 空壳,醒来 36126289 实测;
 * 分集 type=1 正片/3 预告,免费转免线时刻略早靠众数压掉);优酷 vendors url
 * 是豆瓣小程序 scheme(showId 以 URL 编码形态嵌在 path,须抠出后走 show 页)。校正复用
 * BilibiliScheduleRefiner.applyScheduleClock:只换时分、日期不动,airedEpisodes/nextAirTime 重数;
 * externalIds 无豆瓣 id 未经桥接直接跳过;失败/未命中负缓存静默。
 */
class PlayScheduleBridgeTest {
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);
    private static final String REXXAR = "https://m.douban.com/rexxar/api/v2/tv/36406417";
    private static final String REXXAR_36810153 = "https://m.douban.com/rexxar/api/v2/tv/36810153";
    private static final String REXXAR_36624136 = "https://m.douban.com/rexxar/api/v2/tv/36624136";
    private static final String TENCENT_EPISODE =
            "https://pbaccess.video.qq.com/trpc.universal_backend_service.page_server_rpc.PageServer/GetPageData"
                    + "?video_appid=3000010&vplatform=2&vversion_name=8.2.96";
    private static final String IQIYI_M =
            "https://m.iqiyi.com/v_19hly1wd1gg.html?vfm=m_331_dbdy&fv=4904d94982104144a1548dd9040df241";
    private static final String YK_SHOW = "https://www.youku.com/show/id_fcad042e84ef43ce8309.html";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final PlayScheduleBridge bridge;

    PlayScheduleBridgeTest() {
        MetadataHttp metadataHttp = Mockito.mock(MetadataHttp.class);
        Mockito.when(metadataHttp.create()).thenReturn(restTemplate);
        bridge = new PlayScheduleBridge(metadataHttp);
    }

    /**
     * 线上师兄太稳健形态:爱奇艺 vendor url 是 www 域名(换 m 域名拉),分集 type=1 正片 12:00、
     * type=3 预告 14:31 混入被滤 —— 众数取 12:00,昨日已播/明日未播的分集时刻与 nextAirTime 重数。
     */
    @Test
    void iqiyiVendorClockApplied() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(REXXAR)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"vendors\":[{\"title\":\"爱奇艺视频\",\"url\":"
                                + "\"http://www.iqiyi.com/v_19hly1wd1gg.html?vfm=m_331_dbdy&fv=4904d94982104144a1548dd9040df241\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(IQIYI_M)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.USER_AGENT, containsString("iPhone")))
                .andRespond(withSuccess("{\"videoList\":{\"videos\":["
                                + "{\"type\":1,\"shortTitle\":\"师兄太稳健第10集\",\"issueTime\":" + at(today.minusDays(1), LocalTime.of(12, 0)) + "},"
                                + "{\"type\":3,\"shortTitle\":\"师兄太稳健 第12集预告\",\"issueTime\":" + at(today, LocalTime.of(14, 31)) + "},"
                                + "{\"type\":1,\"shortTitle\":\"师兄太稳健第11集\",\"issueTime\":" + at(today, LocalTime.of(12, 0)) + "}"
                                + "]}},\"albumInfo\":{\"latestVideoOrder\":11}",
                        MediaType.TEXT_HTML));

        MetadataDetails details = scheduled(today);
        bridge.refine(details);

        assertEquals(at(today.minusDays(1), LocalTime.of(12, 0)), details.getEpisodes().get(0).getAirTime(),
                "昨日集 20:00 校正为平台 12:00");
        assertEquals(at(today.plusDays(1), LocalTime.of(12, 0)), details.getEpisodes().get(1).getAirTime(),
                "明日集日期不动,只换时分");
        assertEquals(at(today.plusDays(1), LocalTime.of(12, 0)), details.getNextAirTime(),
                "nextAirTime 按校正后时刻重数");
        assertEquals(at(today.minusDays(1), LocalTime.of(12, 0)), details.getUpcoming().get(0).getAirTime(),
                "upcoming 日程同步校正");
        assertEquals(1, details.getAiredEpisodes(), "已播集按校正后时刻重数(昨日 12:00 已过)");
        assertEquals(Map.of("爱奇艺", "https://www.iqiyi.com/v_19hly1wd1gg.html"), details.getPlayLinks(),
                "播放地址剥豆瓣引流参数并升级 https");
        server.verify();
    }

    /** 优酷 vendor url 是豆瓣小程序 scheme:URL 编码形态的 showId 抠出 → show 页 videoPublishTime 取 HH:mm。 */
    @Test
    void youkuVendorShowIdResolvedFromMiniProgramUrl() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(REXXAR)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"vendors\":[{\"title\":\"优酷视频\",\"url\":"
                                + "\"douban://douban.com/goToWXMiniProgram?path=/pages/play/play%3FshowId%3Dfcad042e84ef43ce8309%26refer%3Desfhz\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(YK_SHOW)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "window.__INITIAL_DATA__={\"pageMap\":{\"extra\":{\"videoPublishTime\":\"2026-08-19 12:00:00\"}}};",
                        MediaType.TEXT_HTML));

        MetadataDetails details = scheduled(today);
        bridge.refine(details);

        assertEquals(at(today.minusDays(1), LocalTime.of(12, 0)), details.getEpisodes().get(0).getAirTime());
        assertEquals(at(today.plusDays(1), LocalTime.of(12, 0)), details.getNextAirTime());
        assertEquals(Map.of("优酷", "https://www.youku.com/show/id_fcad042e84ef43ce8309.html"), details.getPlayLinks(),
                "小程序 scheme 抠 showId 构造 show 页链接(浏览器访问 302 落播放页)");
        server.verify();
    }

    /**
     * 线上花开锦绣形态(douban 36810153,腾讯独播):vendors url 小程序 scheme 抠 cid → GetPageData
     * 分集列表 sub_title 更新文案「会员周一至周三18点更新1集,周四至周日18点更新2集,SVIP抢先看1集…」
     * 抽 18:00;分集条目的 duration 数字形态(2817)不参与 —— 时刻只在文案字段内找。
     */
    @Test
    void tencentVendorClockFromUpdateText() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(REXXAR_36810153)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"vendors\":[{\"title\":\"腾讯视频\",\"url\":"
                                + "\"douban://douban.com/goToWXMiniProgram?path=preload_play/play/index?cid=mzc00200seo6p1w&vid=c4102rnuw55\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TENCENT_EPISODE)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":{\"module_list_datas\":[{\"module_datas\":[{"
                        + "\"module_params\":{\"sub_title\":\"会员周一至周三18点更新1集,周四至周日18点更新2集,SVIP抢先看1集,点映礼抢先看大结局/更新至29集\"},"
                        + "\"item_data_lists\":{\"item_datas\":[{\"item_params\":{\"play_title\":\"花开锦绣 第29集\",\"duration\":\"2817\",\"publish_date\":\"\"}}]}"
                        + "}]}]}},\"ret\":0}", MediaType.APPLICATION_JSON));

        MetadataDetails details = scheduled(today);
        details.setId("36810153");
        details.setName("花开锦绣");
        details.getExternalIds().put(DoubanMetadataProvider.NAME, "36810153");
        bridge.refine(details);

        assertEquals(at(today.minusDays(1), LocalTime.of(18, 0)), details.getEpisodes().get(0).getAirTime(),
                "20:00 校正为腾讯更新文案的 18:00");
        assertEquals(at(today.plusDays(1), LocalTime.of(18, 0)), details.getNextAirTime());
        assertEquals(Map.of("腾讯视频", "https://v.qq.com/x/cover/mzc00200seo6p1w.html"), details.getPlayLinks(),
                "小程序 scheme 抠 cid 构造 cover 页链接");
        server.verify();
    }

    /** 完结剧形态(庆余年第二季实测):sub_title=「会员看全集」无时刻词 → 时刻不校正,播放链接照带。 */
    @Test
    void tencentEndedShowGivesLinksWithoutClock() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(REXXAR)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"vendors\":[{\"title\":\"腾讯视频\",\"url\":"
                                + "\"douban://douban.com/goToWXMiniProgram?path=preload_play/play/index?cid=mzc002002kqssyu&vid=x4102abc\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TENCENT_EPISODE)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":{\"module_list_datas\":[{\"module_datas\":[{"
                        + "\"module_params\":{\"sub_title\":\"会员看全集\"},"
                        + "\"item_data_lists\":{\"item_datas\":[]}}]}]}},\"ret\":0}", MediaType.APPLICATION_JSON));

        MetadataDetails details = scheduled(today);
        long before = details.getEpisodes().get(0).getAirTime();

        bridge.refine(details);

        assertEquals(before, details.getEpisodes().get(0).getAirTime(), "完结剧无排播文案,20:00 保留");
        assertEquals(Map.of("腾讯视频", "https://v.qq.com/x/cover/mzc002002kqssyu.html"), details.getPlayLinks(),
                "无时刻仍有官方播放地址");
        server.verify();
    }

    /** externalIds 无豆瓣 id(TMDB 订阅未经 RatingBridge 桥接等):没有「在哪儿看」入口,整链路跳过。 */
    @Test
    void doubanIdMissingSkipsEntirely() {
        LocalDate today = LocalDate.now(ZONE);
        MetadataDetails details = scheduled(today);
        details.setExternalIds(new LinkedHashMap<>(Map.of("tmdb", "123456")));
        long before = details.getEpisodes().get(0).getAirTime();

        bridge.refine(details);

        assertEquals(before, details.getEpisodes().get(0).getAirTime());
        assertEquals(at(today, LocalTime.of(20, 0)), details.getNextAirTime(), "20:00 约定保留");
        server.verify();
    }

    /**
     * 线上悬案形态(douban 36624136,咪咕+优酷双源完结剧):咪咕 vendors url 本就是 https 播放页
     * 直接入 links(不尝试时刻路 —— 不打腾讯 GetPageData);优酷小程序 scheme 抠 showId。
     */
    @Test
    void miguVendorUrlGoesToLinksWithoutClockProbe() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(REXXAR_36624136)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"vendors\":[{\"title\":\"咪咕视频\",\"episodes_info\":\"17集全\","
                                + "\"url\":\"https://m.miguvideo.com/mgs/msite/prd/detail.html?cid=965887286&pwId=PRO_3358b609d33b4eeda90df21a3ad8a573&pkgId=null\"},"
                                + "{\"title\":\"优酷视频\",\"episodes_info\":\"17集全\","
                                + "\"url\":\"douban://douban.com/goToWXMiniProgram?path=/pages/play/play%3FshowId%3Dacbefaed57994c07b881\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://www.youku.com/show/id_acbefaed57994c07b881.html"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "window.__INITIAL_DATA__={\"pageMap\":{\"extra\":{\"videoPublishTime\":\"2026-07-19 12:00:00\"}}};",
                        MediaType.TEXT_HTML));

        MetadataDetails details = scheduled(today);
        details.setId("36624136");
        details.setName("悬案");
        details.getExternalIds().put(DoubanMetadataProvider.NAME, "36624136");
        bridge.refine(details);

        assertEquals(Map.of(
                "咪咕视频", "https://m.miguvideo.com/mgs/msite/prd/detail.html?cid=965887286&pwId=PRO_3358b609d33b4eeda90df21a3ad8a573&pkgId=null",
                "优酷", "https://www.youku.com/show/id_acbefaed57994c07b881.html"), details.getPlayLinks(),
                "咪咕 https 直链原样入 links,优酷抠 showId 拼 show 页");
        assertEquals(at(today.plusDays(1), LocalTime.of(12, 0)), details.getNextAirTime(),
                "时刻从优酷路取,咪咕不参与");
        server.verify();
    }

    /** 无分集日程(未桥接 TMDB 的纯豆瓣详情):没有可校正对象时刻不动,官方播放地址照常带出。 */
    @Test
    void noEpisodesStillGivesPlayLinks() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(REXXAR)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"vendors\":[{\"title\":\"腾讯视频\",\"url\":"
                        + "\"douban://douban.com/goToWXMiniProgram?path=preload_play/play/index?cid=mzc00200seo6p1w\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TENCENT_EPISODE)).andExpect(method(HttpMethod.POST))
                .andRespond(withServerError()); // 时刻路失败不影响链接(clock null + links 并存)
        MetadataDetails details = scheduled(today);
        details.setEpisodes(null);
        long before = details.getNextAirTime();

        bridge.refine(details);

        assertEquals(before, details.getNextAirTime(), "无日程时刻不动");
        assertEquals(Map.of("腾讯视频", "https://v.qq.com/x/cover/mzc00200seo6p1w.html"), details.getPlayLinks());
        server.verify();
    }

    /** rexxar 失败静默(20:00 保留),负缓存 6h 内不再重试。 */
    @Test
    void failureIsSilentAndNegativelyCached() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(REXXAR)).andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());
        MetadataDetails details = scheduled(today);
        long before = details.getEpisodes().get(0).getAirTime();

        bridge.refine(details);
        bridge.refine(details); // 负缓存:不再发请求

        assertEquals(before, details.getEpisodes().get(0).getAirTime(), "失败保留原时刻");
        assertEquals(at(today, LocalTime.of(20, 0)), details.getNextAirTime());
        server.verify();
    }

    /** 爱奇艺分集列表只有预告/花絮(type=3)无正片:取不到时刻不校正,播放链接照带。 */
    @Test
    void iqiyiPreviewOnlyGivesLinksWithoutClock() {
        LocalDate today = LocalDate.now(ZONE);
        server.expect(once(), requestTo(REXXAR)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"vendors\":[{\"title\":\"爱奇艺视频\","
                        + "\"url\":\"http://www.iqiyi.com/v_19hly1wd1gg.html\"}]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://m.iqiyi.com/v_19hly1wd1gg.html")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"videoList\":{\"videos\":["
                        + "{\"type\":3,\"shortTitle\":\"师兄太稳健 第12集预告\",\"issueTime\":"
                        + at(today, LocalTime.of(14, 31)) + "}]}}", MediaType.TEXT_HTML));

        MetadataDetails details = scheduled(today);
        long before = details.getEpisodes().get(0).getAirTime();

        bridge.refine(details);

        assertEquals(before, details.getEpisodes().get(0).getAirTime(), "无正片时刻不动");
        assertTrue(details.getUpcoming().get(0).getAirTime() == at(today.minusDays(1), LocalTime.of(20, 0)),
                "upcoming 保留 20:00");
        assertEquals(Map.of("爱奇艺", "https://www.iqiyi.com/v_19hly1wd1gg.html"), details.getPlayLinks());
        server.verify();
    }

    /** TMDB 桥接后的订阅形态:episodes 昨日 20:00(已播)/明日 20:00(未播),upcoming 同窗口。 */
    private static MetadataDetails scheduled(LocalDate today) {
        MetadataDetails details = new MetadataDetails();
        details.setProvider(DoubanMetadataProvider.NAME);
        details.setId("36406417");
        details.setName("师兄太稳健");
        details.setYear("2026");
        details.setExternalIds(new LinkedHashMap<>(Map.of(DoubanMetadataProvider.NAME, "36406417")));
        details.setEpisodes(new ArrayList<>(List.of(
                new EpisodeInfo(10, "第10集", at(today.minusDays(1), LocalTime.of(20, 0))),
                new EpisodeInfo(12, "第12集", at(today.plusDays(1), LocalTime.of(20, 0))))));
        details.setUpcoming(new ArrayList<>(List.of(
                new EpisodeAirDate(10, at(today.minusDays(1), LocalTime.of(20, 0))),
                new EpisodeAirDate(12, at(today.plusDays(1), LocalTime.of(20, 0))))));
        details.setAiredEpisodes(9);
        details.setNextAirTime(at(today, LocalTime.of(20, 0)));
        return details;
    }

    private static long at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(ZONE).toInstant().toEpochMilli();
    }
}
