package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.EmbyRepository;
import cn.har01d.alist_tvbox.entity.FeiniuRepository;
import cn.har01d.alist_tvbox.entity.JellyfinRepository;
import cn.har01d.alist_tvbox.entity.PlaybackTokenRepository;
import cn.har01d.alist_tvbox.entity.PluginFilterRepository;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.SubscriptionRepository;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 猫影视(open)配置的内置源注入:
 * 后端 L2 端点(与 spring.jar 的 csp_* spider 同源)由通用 /cat/atv_open.js 适配,
 * addCatSites 注入全部内置源站点;replaceOpen 补齐公开源的夸克 cookie 占位符。
 */
class SubscriptionServiceCatSitesTest {

    private DriverAccountRepository panAccountRepository;
    private EmbyRepository embyRepository;
    private AccountRepository accountRepository;
    private SettingRepository settingRepository;
    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/open");
        request.setServerName("atv.example");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        panAccountRepository = mock(DriverAccountRepository.class);
        embyRepository = mock(EmbyRepository.class);
        accountRepository = mock(AccountRepository.class);
        settingRepository = mock(SettingRepository.class);
        SiteRepository siteRepository = mock(SiteRepository.class);
        Site site = new Site();
        ReflectionTestUtils.setField(site, "url", "http://atv.example:5344");
        when(siteRepository.findById(1)).thenReturn(Optional.of(site));
        service = new SubscriptionService(
                mock(Environment.class),
                new AppProperties(),
                new RestTemplateBuilder(),
                new ObjectMapper(),
                mock(JdbcTemplate.class),
                mock(SettingRepository.class),
                mock(SubscriptionRepository.class),
                mock(AccountRepository.class),
                siteRepository,
                mock(ShareRepository.class),
                        panAccountRepository,
                mock(EmbyRepository.class),
                mock(FeiniuRepository.class),
                mock(JellyfinRepository.class),
                mock(PluginRepository.class),
                mock(PluginFilterRepository.class),
                mock(AListLocalService.class),
                mock(ConfigFileService.class),
                mock(TenantService.class),
                mock(UserService.class),
                mock(FileDownloader.class),
                mock(SubscriptionSourceService.class),
                mock(PlaybackTokenRepository.class)
        );
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private Map<String, Object> invokeAddCatSites() {
        Map<String, Object> config = new HashMap<>();
        List<Map<String, Object>> sites = new ArrayList<>();
        Map<String, Object> existing = new HashMap<>();
        existing.put("key", "douban");
        existing.put("name", "豆瓣");
        sites.add(existing);
        config.put("video", Map.of("sites", sites));
        config.put("pan", Map.of("sites", List.of(panSite())));
        ReflectionTestUtils.invokeMethod(service, "addCatSites", config);
        return config;
    }

    private Map<String, Object> panSite() {
        Map<String, Object> site = new HashMap<>();
        site.put("key", "alist");
        site.put("name", "Alist");
        site.put("api", "./alist_open.js");
        site.put("ext", new ArrayList<>());
        return site;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> videoSites(Map<String, Object> config) {
        return (List<Map<String, Object>>) ((Map<String, Object>) config.get("video")).get("sites");
    }

    private Map<String, Object> findSite(Map<String, Object> config, String key) {
        return videoSites(config).stream().filter(s -> key.equals(s.get("key"))).findFirst().orElseThrow();
    }

    @Test
    void injectsAllBackendSites() {
        Map<String, Object> config = invokeAddCatSites();
        List<Map<String, Object>> sites = videoSites(config);

        // 11 个通用 js 内置源 + 3 个专用 js 源,原有站点保留
        List<String> keys = sites.stream().map(s -> (String) s.get("key")).toList();
        for (String key : List.of("atv-media", "atv-feiniu", "atv-emby", "atv-jellyfin", "atv-pansou",
                "atv-pansou-group", "atv-tgsc", "atv-tg-db", "atv-tg-search", "atv-tg-web", "atv-pian-dan",
                "bilibili", "xiaoya-alist", "xiaoya-tvbox", "douban")) {
            assertTrue(keys.contains(key), "missing site " + key);
        }
        // youtube.js 已随上游包移除,注入死链站点一并清理;片单是搜索索引,不作为源
        assertFalse(keys.contains("youtube"));
    }

    @Test
    void backendSitesShareGenericAdapterWithExtContract() {
        Map<String, Object> config = invokeAddCatSites();
        Map<String, Object> media = findSite(config, "atv-media");

        assertEquals(3, media.get("type"));
        assertEquals("/cat/atv_open.js", media.get("api"));
        Map<String, Object> ext = (Map<String, Object>) media.get("ext");
        assertEquals("http://atv.example/media", ext.get("api"));
        assertEquals("http://atv.example/play", ext.get("play"));
        assertEquals("0", ((Map<String, String>) ext.get("homeVod")).get("t"));
        assertEquals("open", ((Map<String, Object>) ext.get("params")).get("from"));

        // 飞牛/Emby/Jellyfin 的播放端点独立并带 t=0 进度参数
        Map<String, Object> feiniu = findSite(config, "atv-feiniu");
        Map<String, Object> feiniuExt = (Map<String, Object>) feiniu.get("ext");
        assertEquals("http://atv.example/feiniu-play", feiniuExt.get("play"));
        assertEquals("0", ((Map<String, Object>) feiniuExt.get("params")).get("t"));
        assertEquals("recommend", ((Map<String, String>) feiniuExt.get("homeVod")).get("ids"));
    }

    @Test
    void tgWebAddsQuery() {
        Map<String, Object> config = invokeAddCatSites();

        Map<String, Object> tgWeb = (Map<String, Object>) findSite(config, "atv-tg-web").get("ext");
        assertEquals("http://atv.example/tg-search", tgWeb.get("api"));
        assertEquals("true", ((Map<String, String>) tgWeb.get("query")).get("web"));
    }

    @Test
    void enabledTokenAppendsSecretToApiAndPlay() {
        AppProperties properties = new AppProperties();
        properties.setEnabledToken(true);
        ReflectionTestUtils.setField(service, "appProperties", properties);
        ReflectionTestUtils.setField(service, "tokens", "tok1,tok2");

        Map<String, Object> config = invokeAddCatSites();
        Map<String, Object> ext = (Map<String, Object>) findSite(config, "atv-media").get("ext");
        assertEquals("http://atv.example/media/tok1", ext.get("api"));
        assertEquals("http://atv.example/play/tok1", ext.get("play"));
    }


    @Test
    void replaceLegacyConfigFillsAtvPlaceholders() {
        AppProperties properties = new AppProperties();
        properties.setEnabledToken(true);
        ReflectionTestUtils.setField(service, "appProperties", properties);
        ReflectionTestUtils.setField(service, "tokens", "tok3");

        String json = "{url: 'ATV_MEDIA_URL', play: 'ATV_MEDIA_PLAY_URL', web: 'ATV_TG_WEB_URL', pan: 'ATV_PIAN_DAN_URL', api: 'ATV_API_URL', token: 'ATV_TOKEN'}";
        String result = ReflectionTestUtils.invokeMethod(service, "replaceLegacyConfig", json);

        assertTrue(result.contains("http://atv.example/media/tok3"), result);
        assertTrue(result.contains("http://atv.example/play/tok3"));
        // TG 网页与 TG 搜索共用端点;片单无播放端点
        assertTrue(result.contains("http://atv.example/tg-search/tok3"));
        // atv_pan 后端必须替换(盘搜文件夹化关键,曾在此丢失)
        assertTrue(result.contains("api: 'http://atv.example', token: 'tok3'"), result);
        assertFalse(result.contains("ATV_"));
    }

    @Test
    void replaceOpenFillsQuarkCookiePlaceholders() {
        DriverAccount account = new DriverAccount();
        account.setCookie("__quark_ct=y");
        when(panAccountRepository.findByTypeAndMasterTrue(DriverType.QUARK)).thenReturn(Optional.of(account));

        String json = "{\"ext\":{\"token\":\"夸克账号cookie\"},\"cookie\":\"夸克cookie\"}";
        String result = ReflectionTestUtils.invokeMethod(service, "replaceOpen", json);

        // 长占位符必须先替换,否则会被"夸克cookie"截断成残留"账号"
        assertTrue(result.contains("__quark_ct=y"));
        assertFalse(result.contains("夸克账号cookie"));
        assertFalse(result.contains("夸克cookie"));
        assertFalse(result.contains("账号cookie"));
    }


    @Test
    void replaceOpenKeepsPlaceholdersWhenNoQuarkAccount() {
        when(panAccountRepository.findByTypeAndMasterTrue(DriverType.QUARK)).thenReturn(Optional.empty());

        String json = "{\"cookie\":\"夸克cookie\"}";
        String result = ReflectionTestUtils.invokeMethod(service, "replaceOpen", json);

        // 无夸克账号时替换为空串,与"阿里token"占位符行为一致
        assertNotNull(result);
        assertFalse(result.contains("夸克cookie"));
    }
}
