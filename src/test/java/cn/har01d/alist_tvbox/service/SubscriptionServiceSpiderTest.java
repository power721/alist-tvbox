package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.SourceKeyUsageCount;
import cn.har01d.alist_tvbox.entity.EmbyRepository;
import cn.har01d.alist_tvbox.entity.FeiniuRepository;
import cn.har01d.alist_tvbox.entity.HistoryRepository;
import cn.har01d.alist_tvbox.entity.JellyfinRepository;
import cn.har01d.alist_tvbox.entity.PlaybackTokenRepository;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.PluginFilterRepository;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.SubscriptionRepository;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.ResponseBody;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上游订阅 spider 与本项目 spring.jar 的主 spider 位之争:
 * TVBox 只有唯一的全局 spider jar 位,上游订阅自带的 spider 若占据全局位,
 * 本项目 spring.jar(代理/播放同步等常驻服务)就不会随订阅加载。
 */
class SubscriptionServiceSpiderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 订阅源管理里的 WebHome 内置源条目(builtin-atv_home,findEnabledSources 消费形态)。 */
    private static final SubscriptionSourceService.SubscriptionSourceRef WEB_HOME_SOURCE =
            new SubscriptionSourceService.SubscriptionSourceRef("builtin-atv_home", true, "atv_home", "影视首页", null);

    @Test
    void webPagePluginEmitsWebHomeStyleSite() throws Exception {
        // 自定义网页源(webhome/pages/*.html 自动注册):csp_WebHome 同款单形态站点,
        // 中文文件名 key 回落插件 id(web_5),页面地址经 /webhome/** no-cache,无 token/版本号
        Plugin page = new Plugin();
        page.setId(5);
        page.setUrl("/static/webhome/pages/电影库.html");
        page.setName("我的电影库");
        SubscriptionService service = newService("{}", mock(WebHomeService.class), List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("plugin-5", false, "我的电影库", "我的电影库", page)));

        Map<String, Object> config = service.subscription("", "http://up.example/config.json", "", null);
        Map<String, Object> site = findSite(config, "web_5");

        assertEquals("csp_WebHome", site.get("api"));
        assertEquals("我的电影库", site.get("name"));
        assertEquals("http://atv.example/webhome/pages/电影库.html", site.get("homePage"));
        assertEquals("http://atv.example/spring.jar", site.get("jar"));
        String pageExt = new String(java.util.Base64.getDecoder().decode((String) site.get("ext")));
        assertTrue(pageExt.contains("\"url\":\"http://atv.example/webhome/pages/电影库.html\""));
        // 裸订阅(无 token)ext.token 空串:spider 侧盘检/盘搜后端随关闭
        assertTrue(pageExt.contains("\"token\":\"\""));
        assertEquals(0, site.get("searchable"));
    }

    @Test
    void webPageSiteEmbedsVodTokenForPanBackends() throws Exception {
        // 盘检(/check-links)与盘搜后端(/pan-search)共用 ext.token,与页面 URL 同 token 空间
        Plugin page = new Plugin();
        page.setId(6);
        page.setUrl("/static/webhome/pages/玩偶.html");
        page.setName("玩偶盘链");
        SubscriptionService service = newService("{}", mock(WebHomeService.class), List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("plugin-6", false, "玩偶盘链", "玩偶盘链", page),
                WEB_HOME_SOURCE));

        Map<String, Object> config = service.subscription("tok9", "http://up.example/config.json", "", null);
        String pageExt = new String(java.util.Base64.getDecoder().decode((String) findSite(config, "web_6").get("ext")));
        assertTrue(pageExt.contains("\"token\":\"tok9\""));
        Map<String, Object> home = findSite(config, "atv_home");
        String homeExt = new String(java.util.Base64.getDecoder().decode((String) home.get("ext")));
        assertTrue(homeExt.contains("\"token\":\"tok9\""));
    }

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sub/token/1");
        request.setServerName("atv.example");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void upstreamSpiderYieldsGlobalSlotToSpringJar() {
        SubscriptionService service = newService("""
                {
                  "spider": "http://up.example/spider.jar",
                  "sites": [
                    {"key": "up_csp", "name": "上游CSP", "type": 3, "api": "csp_XYQ"},
                    {"key": "up_t4", "name": "上游T4", "type": 3, "api": "http://up.example/api?ac=videolist"},
                    {"key": "up_selfjar", "name": "自带jar", "type": 3, "api": "csp_Other", "jar": "http://up.example/other.jar"}
                  ]
                }
                """);

        Map<String, Object> config = service.subscription("", "http://up.example/config.json", "", null);

        // 全局主 spider 位回归本项目 spring.jar,其代理服务随 TVBox 启动加载
        assertEquals("http://atv.example/spring.jar", config.get("spider"));

        Map<String, Object> upCsp = findSite(config, "up_csp");
        // 上游无 jar 的 csp 站点降级为站点级 jar,指向上游自己的 spider
        assertEquals("http://up.example/spider.jar", upCsp.get("jar"));

        // t4 接口(api 为 http)不需要 jar
        assertNull(findSite(config, "up_t4").get("jar"));

        // 上游站点自带的 jar 保持不变
        assertEquals("http://up.example/other.jar", findSite(config, "up_selfjar").get("jar"));
    }

    @Test
    void explicitOverrideSpiderWins() {
        SubscriptionService service = newService("""
                {"spider": "http://up.example/spider.jar", "sites": []}
                """);

        Map<String, Object> config = service.subscription("", "http://up.example/config.json",
                "{\"spider\":\"http://mine.example/my.jar\"}", null);

        // 用户显式指定的 spider 优先于默认值
        assertEquals("http://mine.example/my.jar", config.get("spider"));
    }

    @Test
    void globalSpiderDefaultsToSpringJarWithoutUpstream() {
        SubscriptionService service = newService("{}");

        Map<String, Object> config = service.subscription("", "", "", null);

        // 无上游时同样默认注入,保证 spring.jar 常驻服务随订阅加载
        assertEquals("http://atv.example/spring.jar", config.get("spider"));
        assertNotNull(config.get("sites"));
    }

    @Test
    void webHomeSiteSingleFormForAllClients() {
        // 单形态通吃:homePage 字段(webhtv/fish 按 site.hasHomePage() 字段驱动原生渲染,与 api 名无关)
        // + api=csp_WebHome(普通端由 spring.jar spider 弹窗加载 ext 同一 URL)。
        // 不再按 token 能力记忆二选一 —— 配置拉取无法区分客户端,同 token 多设备混用
        // (一台 webhtv 标记能力端后,同 token 的原版端拿到解析不了的原生形态)必错一边
        WebHomeService capable = mock(WebHomeService.class);
        when(capable.isCapable(anyString())).thenReturn(true);
        SubscriptionService service = newService("{}", capable, List.of(WEB_HOME_SOURCE));
        Map<String, Object> config = service.subscription("", "http://up.example/config.json", "", null);
        Map<String, Object> atvHome = findSite(config, "atv_home");
        assertEquals("csp_WebHome", atvHome.get("api"));
        assertEquals("http://atv.example/webhome/app.html?token=-&v=20", atvHome.get("homePage"));
        // 显式 jar(与其他内置源一致):防宿主不回落全局 spider 或全局位被覆盖
        assertEquals("http://atv.example/spring.jar", atvHome.get("jar"));
        // ext = base64(JSON)(与 csp_Media 等其它源一致;url + pt 播放同步专用令牌,测试桩下 pt 为空)
        String ext = new String(java.util.Base64.getDecoder().decode((String) atvHome.get("ext")));
        assertTrue(ext.contains("\"url\":\"http://atv.example/webhome/app.html?token=-&v=20\""));
        assertTrue(ext.contains("\"pt\":\"\""));

        // 普通端(未标记能力):同一形态
        SubscriptionService plain = newService("{}", mock(WebHomeService.class), List.of(WEB_HOME_SOURCE));
        Map<String, Object> config2 = plain.subscription("", "http://up.example/config.json", "", null);
        Map<String, Object> plainHome = findSite(config2, "atv_home");
        assertEquals("csp_WebHome", plainHome.get("api"));
        assertEquals(atvHome.get("homePage"), plainHome.get("homePage"));
    }

    @Test
    void webHomeSiteFollowsSubscriptionSourceSwitch() {
        // 订阅源管理里禁用 atv_home:即便客户端能力达标也不再注入
        WebHomeService capable = mock(WebHomeService.class);
        when(capable.isCapable(anyString())).thenReturn(true);
        SubscriptionService service = newService("{}", capable, List.of());
        Map<String, Object> config = service.subscription("", "http://up.example/config.json", "", null);
        for (Map<String, Object> site : (List<Map<String, Object>>) config.get("sites")) {
            assertEquals(false, "atv_home".equals(site.get("key")));
        }
    }

    @Test
    void preheatManifestPutsFrequentlyUsedPluginsFirst() {
        PluginRepository pluginRepository = mock(PluginRepository.class);
        Plugin a = preheatPlugin(1, "ext-a", 10, 4);
        Plugin b = preheatPlugin(2, "ext-b", 20, null);
        Plugin c = preheatPlugin(3, "ext-c", 30, 7);
        when(pluginRepository.findByEnabledTrueOrderBySortOrderAscIdAsc())
                .thenReturn(new ArrayList<>(List.of(a, b, c)));

        HistoryRepository historyRepository = mock(HistoryRepository.class);
        when(historyRepository.countBySourceKey("spider_plugin"))
                .thenReturn(List.of(usage("ext-c", 5), usage("ext-a", 2)));

        SubscriptionService service = newService("{}", mock(WebHomeService.class), List.of(),
                pluginRepository, historyRepository);
        Map<String, Object> manifest = service.buildPreheatManifest();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plugins = (List<Map<String, Object>>) manifest.get("plugins");
        assertEquals(3, plugins.size());
        // 常用(播放记录聚合次数)在前;未用过的保持 sortOrder 原序垫后
        assertEquals("ext-c", plugins.get(0).get("key"));
        assertEquals("ext-a", plugins.get(1).get("key"));
        assertEquals("ext-b", plugins.get(2).get("key"));
        // 地址与 ext 的 source 同一拼法(缓存命中前提),带 ?v= 版本参数
        assertEquals("http://atv.example/plugins/-/3.txt?v=7", plugins.get(0).get("url"));
        assertEquals("http://atv.example/plugins/-/1.txt?v=4", plugins.get(1).get("url"));
        assertEquals("http://atv.example/plugins/-/2.txt", plugins.get(2).get("url"));
        assertEquals(
                SubscriptionService.buildPluginExtPayload(c, "http://atv.example", "-", "", "", false, Map.of())
                        .get("source"),
                plugins.get(0).get("url"));
    }

    private Plugin preheatPlugin(int id, String externalId, int sortOrder, Integer version) {
        Plugin plugin = new Plugin();
        plugin.setId(id);
        plugin.setExternalId(externalId);
        plugin.setName(externalId);
        plugin.setUrl("https://example.com/" + externalId + ".txt");
        plugin.setSortOrder(sortOrder);
        plugin.setVersion(version);
        plugin.setEnabled(true);
        return plugin;
    }

    private SourceKeyUsageCount usage(String sourceKey, long total) {
        return new SourceKeyUsageCount() {
            @Override
            public String getSourceKey() {
                return sourceKey;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
    }

    private Map<String, Object> findSite(Map<String, Object> config, String key) {
        List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
        return sites.stream().filter(s -> key.equals(s.get("key"))).findFirst().orElseThrow();
    }

    private SubscriptionService newService(String upstreamJson) {
        return newService(upstreamJson, mock(WebHomeService.class));
    }

    private SubscriptionService newService(String upstreamJson, WebHomeService webHomeService) {
        return newService(upstreamJson, webHomeService, List.of());
    }

    private SubscriptionService newService(String upstreamJson, WebHomeService webHomeService,
                                           List<SubscriptionSourceService.SubscriptionSourceRef> sources) {
        return newService(upstreamJson, webHomeService, sources, mock(PluginRepository.class),
                mock(HistoryRepository.class));
    }

    private SubscriptionService newService(String upstreamJson, WebHomeService webHomeService,
                                           List<SubscriptionSourceService.SubscriptionSourceRef> sources,
                                           PluginRepository pluginRepository, HistoryRepository historyRepository) {
        SettingRepository settingRepository = mock(SettingRepository.class);
        when(settingRepository.findById(anyString())).thenAnswer(invocation -> {
            Object key = invocation.getArgument(0);
            if (Constants.ALI_SECRET.equals(key)) {
                return Optional.of(new Setting(Constants.ALI_SECRET, "secret"));
            }
            return Optional.empty();
        });

        ShareRepository shareRepository = mock(ShareRepository.class);
        when(shareRepository.countByType(anyInt())).thenReturn(0);

        DriverAccountRepository driverAccountRepository = mock(DriverAccountRepository.class);
        when(driverAccountRepository.findByTypeAndMasterTrue(any())).thenReturn(Optional.empty());

        SubscriptionSourceService subscriptionSourceService = mock(SubscriptionSourceService.class);
        when(subscriptionSourceService.findEnabledSources()).thenReturn(sources);

        SubscriptionService service = new SubscriptionService(
                mock(Environment.class),
                new AppProperties(),
                new RestTemplateBuilder(),
                objectMapper,
                mock(JdbcTemplate.class),
                settingRepository,
                mock(SubscriptionRepository.class),
                mock(AccountRepository.class),
                mock(SiteRepository.class),
                shareRepository,
                driverAccountRepository,
                mock(EmbyRepository.class),
                mock(FeiniuRepository.class),
                mock(JellyfinRepository.class),
                pluginRepository,
                mock(PluginFilterRepository.class),
                mock(AListLocalService.class),
                mock(ConfigFileService.class),
                mock(TenantService.class),
                mock(UserService.class),
                mock(FileDownloader.class),
                subscriptionSourceService,
                mock(PlaybackTokenRepository.class),
                webHomeService,
                historyRepository
        );

        ReflectionTestUtils.setField(service, "okHttpClient", httpServerReturning(upstreamJson));
        return service;
    }

    private static OkHttpClient httpServerReturning(String body) {
        return new OkHttpClient.Builder()
                .addInterceptor(chain -> new okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(body, MediaType.get("application/json")))
                        .build())
                .build();
    }
}
