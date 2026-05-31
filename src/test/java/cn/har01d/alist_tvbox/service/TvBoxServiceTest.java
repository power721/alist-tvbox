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
import org.springframework.boot.web.client.RestTemplateBuilder;
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
}
