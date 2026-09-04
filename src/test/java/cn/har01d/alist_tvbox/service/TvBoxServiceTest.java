package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.entity.DeviceRepository;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.PikPakAccountRepository;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.model.FsDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TvBoxServiceTest {
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AListAliasRepository aliasRepository;
    @Mock
    private ShareRepository shareRepository;
    @Mock
    private MetaRepository metaRepository;
    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private AListService aListService;
    @Mock
    private IndexService indexService;
    @Mock
    private SiteService siteService;
    @Mock
    private AppProperties appProperties;
    @Mock
    private DoubanService doubanService;
    @Mock
    private TmdbService tmdbService;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private ConfigFileService configFileService;
    @Mock
    private TenantService tenantService;
    @Mock
    private SettingService settingService;
    @Mock
    private AListLocalService aListLocalService;
    @Mock
    private ShareService shareService;
    @Mock
    private DriverAccountRepository driverAccountRepository;
    @Mock
    private ProxyService proxyService;
    @Mock
    private cn.har01d.alist_tvbox.service.Index115TvBoxAdapter index115Adapter;
    @Mock
    private PikPakAccountRepository pikPakAccountRepository;

    private TvBoxService tvBoxService;

    @BeforeEach
    void setUp() {
        tvBoxService = new TvBoxService(
                accountRepository,
                aliasRepository,
                shareRepository,
                metaRepository,
                deviceRepository,
                aListService,
                indexService,
                siteService,
                appProperties,
                doubanService,
                tmdbService,
                subscriptionService,
                configFileService,
                tenantService,
                settingService,
                aListLocalService,
                shareService,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                driverAccountRepository,
                proxyService,
                index115Adapter,
                new RestTemplateBuilder(),
                pikPakAccountRepository,
                org.mockito.Mockito.mock(cn.har01d.alist_tvbox.service.AccountAccessGuard.class)
        );
    }

    @AfterEach
    void clearRequestContext() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void getDetailRoutesHttpShareLinkToMountedPlaylist() {
        // 播放同步回放:ids 为网盘分享链接 → 挂载后转成 "1$<path>/~playlist" 走 getPlaylist。
        // 用 spy 桩掉递归的 getDetail,隔离 dfs/aListService,只验证路由与挂载。
        TvBoxService spied = spy(tvBoxService);
        String link = "https://pan.baidu.com/s/abc?pwd=HAO8";
        String mounted = "/temp/BaiduShare2@abc@HAO8/folder";
        when(shareService.add(argThat((ShareLink s) -> link.equals(s.getLink())))).thenReturn(mounted);
        when(shareService.resolveShareTitle(eq(link), isNull())).thenReturn("马背上的银行");
        MovieList expected = new MovieList();
        doReturn(expected).when(spied).getDetail(eq("web"), eq("1$" + mounted + "/~playlist"),
                eq("马背上的银行"), isNull(), eq(0));

        MovieList result = spied.getDetail("web", link, null, null, 0);

        assertThat(result).isSameAs(expected);
        verify(shareService).add(argThat((ShareLink s) -> link.equals(s.getLink())));
    }

    @Test
    @Disabled
    void getPlayUrlShouldUseBackendProxyWhenClientProxyRequestedButLocalProxyDisabled() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/play");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8080);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Site site = new Site();
        site.setId(1);
        site.setName("AList");
        FsDetail detail = new FsDetail();
        detail.setProvider("Quark");
        detail.setName("video.mkv");
        detail.setRawUrl("http://raw.example/video.mkv#storageId=4001");
        DriverAccount account = new DriverAccount();
        account.setUseProxy(true);
        account.setCookie("quark-cookie");

        when(siteService.getById(1)).thenReturn(site);
        when(aListService.getFile(site, "/video.mkv")).thenReturn(detail);
        when(driverAccountRepository.findById(1)).thenReturn(java.util.Optional.of(account));
        when(appProperties.isEnableHttps()).thenReturn(false);
        when(appProperties.getFormats()).thenReturn(Set.of("mkv"));
        when(appProperties.getLocalProxyConfig()).thenReturn((Map) Map.of(
                "QUARK", Map.of("enabled", false, "concurrency", 20, "chunk_size", 1024)
        ));
        when(subscriptionService.getCurrentToken()).thenReturn("test-token");
        when(proxyService.generateProxyUrl(site, "/video.mkv")).thenReturn(99);

        Map<String, Object> result = tvBoxService.getPlayUrl(1, "/video.mkv", false, null, "client-proxy");

        assertThat((String) result.get("url")).contains("/p/test-token/1@99");
    }

    @Test
    @Disabled
    void getPlayUrlShouldAppendIsoSuffixForBackendProxyUrl() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/play");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8080);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Site site = new Site();
        site.setId(1);
        site.setName("AList");
        FsDetail detail = new FsDetail();
        detail.setProvider("Quark");
        detail.setName("disc.iso");
        detail.setRawUrl("http://raw.example/disc.iso#storageId=4001");
        DriverAccount account = new DriverAccount();
        account.setUseProxy(true);
        account.setCookie("quark-cookie");

        when(siteService.getById(1)).thenReturn(site);
        when(aListService.getFile(site, "/disc.iso")).thenReturn(detail);
        when(driverAccountRepository.findById(1)).thenReturn(java.util.Optional.of(account));
        when(appProperties.isEnableHttps()).thenReturn(false);
        when(appProperties.getFormats()).thenReturn(Set.of("iso"));
        when(appProperties.getLocalProxyConfig()).thenReturn((Map) Map.of(
                "QUARK", Map.of("enabled", false, "concurrency", 20, "chunk_size", 1024)
        ));
        when(subscriptionService.getCurrentToken()).thenReturn("test-token");
        when(proxyService.generateProxyUrl(site, "/disc.iso")).thenReturn(106306);

        Map<String, Object> result = tvBoxService.getPlayUrl(1, "/disc.iso", false, null, "client-proxy");

        assertThat((String) result.get("url")).contains("/p/test-token/1@106306.iso");
    }

    @Test
    @Disabled
    void getPlayUrlShouldProxyGuangYaShareWhenLocalProxyDisabled() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/play");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8080);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Site site = new Site();
        site.setId(1);
        site.setName("AList");
        FsDetail detail = new FsDetail();
        detail.setProvider("GuangYaPanShare");
        detail.setName("video.mkv");
        detail.setRawUrl("http://raw.example/video.mkv#storageId=4001");
        DriverAccount account = new DriverAccount();
        account.setUseProxy(true);

        when(siteService.getById(1)).thenReturn(site);
        when(aListService.getFile(site, "/video.mkv")).thenReturn(detail);
        when(driverAccountRepository.findById(1)).thenReturn(java.util.Optional.of(account));
        when(appProperties.isEnableHttps()).thenReturn(false);
        when(appProperties.getFormats()).thenReturn(Set.of("mkv"));
        when(appProperties.getLocalProxyConfig()).thenReturn((Map) Map.of(
                "GUANGYA", Map.of("enabled", false, "concurrency", 4, "chunk_size", 1024)
        ));
        when(subscriptionService.getCurrentToken()).thenReturn("test-token");
        when(proxyService.generateProxyUrl(site, "/video.mkv")).thenReturn(99);

        Map<String, Object> result = tvBoxService.getPlayUrl(1, "/video.mkv", false, null, "client-proxy");

        assertThat((String) result.get("url")).contains("/p/test-token/1@99");
        assertThat(result.get("type")).isEqualTo(DriverType.GUANGYA);
    }

    // Regression for commit 22902e09 (#806): getPlaylist switched vod_id from
    // encodeUrl(path) to a numeric proxy pid, so vod_id no longer ends with
    // "playlist$1". The old name-recovery gate became dead code, leaving
    // vod_name as the raw folder name (e.g. "S01") instead of the matched
    // Douban title even when every other field (pic/actor/director/dbid) was set.
    @Test
    void getPlaylistShouldRenameVodNameToDoubanTitleForPlaylistPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/detail");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8080);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Site site = new Site();
        site.setId(1);
        site.setName("丫仙女");

        String playlistPath = "/share/百.花.杀（2026）/S01/~playlist";
        String folderPath = "/share/百.花.杀（2026）/S01";

        FsDetail detail = new FsDetail();
        detail.setName("S01");
        detail.setModified("2026-07-24T17:59:07+08:00");

        cn.har01d.alist_tvbox.entity.Movie movie = new cn.har01d.alist_tvbox.entity.Movie();
        movie.setId(34815019);
        movie.setName("百花杀");
        movie.setYear(2026);
        movie.setDbScore("8.6");

        when(tenantService.valid(folderPath)).thenReturn(true);
        when(aListService.getFile(site, folderPath)).thenReturn(detail);
        when(proxyService.generatePath(site, playlistPath)).thenReturn(170885);
        when(appProperties.isEnableHttps()).thenReturn(false);
        when(doubanService.getByName(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Integer>any())).thenReturn(movie);
        when(aListService.listFiles(site, folderPath, 1, 0)).thenReturn(new cn.har01d.alist_tvbox.model.FsResponse());

        cn.har01d.alist_tvbox.tvbox.MovieList result = tvBoxService.getPlaylist("detail", site, playlistPath);

        cn.har01d.alist_tvbox.tvbox.MovieDetail md = result.getList().get(0);
        assertThat(md.getVod_name()).isEqualTo("百花杀");
        assertThat(md.getDbid()).isEqualTo(34815019);
    }

    // When the show is not in the local Douban DB, the detail must still surface the
    // show name derived from the parent folder instead of leaving the bare season token.
    @Test
    void getPlaylistFallsBackToCleanedParentNameWhenNoDoubanMatch() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/detail");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8080);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Site site = new Site();
        site.setId(1);
        site.setName("丫仙女");

        String playlistPath = "/share/百.花.杀（2026）/S01/~playlist";
        String folderPath = "/share/百.花.杀（2026）/S01";

        FsDetail detail = new FsDetail();
        detail.setName("S01");
        detail.setModified("2026-07-24T17:59:07+08:00");

        when(tenantService.valid(folderPath)).thenReturn(true);
        when(aListService.getFile(site, folderPath)).thenReturn(detail);
        when(proxyService.generatePath(site, playlistPath)).thenReturn(170885);
        when(appProperties.isEnableHttps()).thenReturn(false);
        // no Douban match for any name (show not in local DB)
        when(doubanService.getByName(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Integer>any())).thenReturn(null);
        when(aListService.listFiles(site, folderPath, 1, 0)).thenReturn(new cn.har01d.alist_tvbox.model.FsResponse());

        cn.har01d.alist_tvbox.tvbox.MovieList result = tvBoxService.getPlaylist("detail", site, playlistPath);

        cn.har01d.alist_tvbox.tvbox.MovieDetail md = result.getList().get(0);
        assertThat(md.getVod_name()).isEqualTo("百花杀");
    }

    @Test
    void getPlaylistTriesSourceTitleThenSearchKeywordBeforeShareFolder() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/detail");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8080);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Site site = new Site();
        site.setId(1);
        site.setName("AList");
        String playlistPath = "/temp/quark@7058d8d58066@/~playlist";
        String folderPath = "/temp/quark@7058d8d58066@";
        FsDetail detail = new FsDetail();
        detail.setName("quark@7058d8d58066@");

        cn.har01d.alist_tvbox.entity.Movie keywordMovie = new cn.har01d.alist_tvbox.entity.Movie();
        keywordMovie.setId(1);
        keywordMovie.setName("天才女友");
        keywordMovie.setYear(2026);
        java.util.List<String> attemptedNames = new java.util.ArrayList<>();

        when(tenantService.valid(folderPath)).thenReturn(true);
        when(aListService.getFile(site, folderPath)).thenReturn(detail);
        when(aListService.listFiles(site, folderPath, 1, 0))
                .thenReturn(new cn.har01d.alist_tvbox.model.FsResponse());
        when(proxyService.generatePath(site, playlistPath)).thenReturn(7);
        when(doubanService.getByName(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Integer>nullable(Integer.class)))
                .thenAnswer(invocation -> {
                    String name = invocation.getArgument(0);
                    attemptedNames.add(name);
                    return "天才女友".equals(name) ? keywordMovie : null;
                });

        cn.har01d.alist_tvbox.tvbox.MovieList result = tvBoxService.getPlaylist(
                "detail",
                site,
                playlistPath,
                "天才，女友(2026) 4K 更新至12集",
                "天才女友",
                0
        );

        assertThat(result.getList().getFirst().getVod_name()).isEqualTo("天才女友");
        assertThat(attemptedNames.getFirst()).isEqualTo("天才，女友(2026) 4K 更新至12集");
        assertThat(attemptedNames).contains("天才女友");
        assertThat(attemptedNames).doesNotContain("quark@7058d8d58066@");
    }

    @Test
    void getPlaylistKeepsCleanedSourceTitleWhenMetadataIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/detail");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8080);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Site site = new Site();
        site.setId(1);
        site.setName("AList");
        String playlistPath = "/temp/T 忝財钕伖/~playlist";
        String folderPath = "/temp/T 忝財钕伖";
        FsDetail detail = new FsDetail();
        detail.setName("T 忝財钕伖");
        java.util.List<String> attemptedNames = new java.util.ArrayList<>();

        when(tenantService.valid(folderPath)).thenReturn(true);
        when(aListService.getFile(site, folderPath)).thenReturn(detail);
        when(aListService.listFiles(site, folderPath, 1, 0))
                .thenReturn(new cn.har01d.alist_tvbox.model.FsResponse());
        when(proxyService.generatePath(site, playlistPath)).thenReturn(8);
        when(doubanService.getByName(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Integer>nullable(Integer.class)))
                .thenAnswer(invocation -> {
                    attemptedNames.add(invocation.getArgument(0));
                    return null;
                });

        cn.har01d.alist_tvbox.tvbox.MovieList result = tvBoxService.getPlaylist(
                "detail",
                site,
                playlistPath,
                "天才，女友/天才女友 (2026) 4K 更新至12集【田曦薇/胡一天】",
                "天才，女友",
                0
        );

        assertThat(result.getList().getFirst().getVod_name()).isEqualTo("天才，女友(2026)");
        assertThat(attemptedNames).contains(
                "天才，女友/天才女友 (2026) 4K 更新至12集【田曦薇/胡一天】",
                "天才，女友",
                "T 忝財钕伖");
    }

    @Test
    void getMovieListDegradesToEmptyWhenDirectoryListingFails() {
        // 订阅主源分享被取消(UC errno -21)一类的目录级失效:浏览接口降级为空列表,
        // 不把上游错误原文炸成 400 Bad Request(TVBox 端整页报错)
        org.mockito.Mockito.when(siteService.getById(1)).thenReturn(new cn.har01d.alist_tvbox.entity.Site());
        org.mockito.Mockito.when(tenantService.valid(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        org.mockito.Mockito.when(aListService.listFiles(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("{\"errno\":-21,\"show_msg\":\"来晚啦，该分享已被取消\"}"));

        cn.har01d.alist_tvbox.tvbox.MovieList result =
                tvBoxService.getMovieList(null, "web", "1$/追剧/沧元图-第三季 [bgmid-575244] S03$1", null, null, 1, 50);

        org.junit.jupiter.api.Assertions.assertNotNull(result);
        org.junit.jupiter.api.Assertions.assertTrue(result.getList().isEmpty());
    }

    @Test
    void getSubtitleMarksAssFileAsSsaMimeType() {
        // 播放端 MediaItemFactory 对非空 format 直用不嗅探:ass 误标 application/x-subrip
        // 会被 ExoPlayer 的 SubRipDecoder 解析失败,表现为字幕轨可选但无字幕渲染
        cn.har01d.alist_tvbox.entity.Site site = new cn.har01d.alist_tvbox.entity.Site();
        String dir = "/叶卡捷琳娜大帝";
        String video = "叶卡捷琳娜大帝.2014.S01E01.1080p.WEB-DL.H.264.mkv";
        String sub = "叶卡捷琳娜大帝.2014.S01E01.1080p.WEB-DL.H.264.chs.ass";

        cn.har01d.alist_tvbox.model.FsResponse listing = new cn.har01d.alist_tvbox.model.FsResponse();
        cn.har01d.alist_tvbox.model.FsInfo videoInfo = new cn.har01d.alist_tvbox.model.FsInfo();
        videoInfo.setName(video);
        cn.har01d.alist_tvbox.model.FsInfo subInfo = new cn.har01d.alist_tvbox.model.FsInfo();
        subInfo.setName(sub);
        listing.setFiles(java.util.List.of(videoInfo, subInfo));
        when(aListService.listFiles(site, dir, 1, 100)).thenReturn(listing);
        when(appProperties.getFormats()).thenReturn(Set.of("mkv"));
        when(appProperties.getSubtitles()).thenReturn(Set.of("srt", "ass", "vtt", "ttml"));

        FsDetail subDetail = new FsDetail();
        subDetail.setName(sub);
        subDetail.setRawUrl("http://cdn.example/sub.chs.ass");
        when(aListService.getFile(site, dir + "/" + sub)).thenReturn(subDetail);

        cn.har01d.alist_tvbox.dto.Subtitle result = tvBoxService.getSubtitle(site, dir, video);

        assertThat(result).isNotNull();
        assertThat(result.getFormat()).isEqualTo("text/x-ssa");
        assertThat(result.getExt()).isEqualTo("ass");
        assertThat(result.getLang()).isEqualTo("chs");
        assertThat(result.getName()).isEqualTo("简体中文");
        assertThat(result.getUrl()).isEqualTo("http://cdn.example/sub.chs.ass");
    }
}
