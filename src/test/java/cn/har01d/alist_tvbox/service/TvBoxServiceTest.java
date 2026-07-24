package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
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
                pikPakAccountRepository
        );
    }

    @AfterEach
    void clearRequestContext() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
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
}
