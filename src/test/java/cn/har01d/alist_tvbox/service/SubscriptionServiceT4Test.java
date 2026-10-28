package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.PlaybackTokenRepository;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.EmbyRepository;
import cn.har01d.alist_tvbox.entity.FeiniuRepository;
import cn.har01d.alist_tvbox.entity.JellyfinRepository;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginFilterRepository;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Subscription;
import cn.har01d.alist_tvbox.entity.SubscriptionRepository;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.entity.HistoryRepository;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4 订阅配置(/t4/{token}/{id}):内置 csp_* 站点变换为 type 4 + api 指回本服务
 * /t4 适配层(去 jar/ext);插件源与 WebHome 保持 type 3 混跑。
 */
class SubscriptionServiceT4Test {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/t4/token/0");
        request.setServerName("atv.example");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void builtinSitesBecomeType4AndPluginsStayType3() {
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setUrl("https://up.example/spider.txt");
        plugin.setName("上游源");
        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_AList", true, "csp_AList", "网盘", null),
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_Media", true, "csp_Media", "我的追剧", null),
                new SubscriptionSourceService.SubscriptionSourceRef("plugin-9", false, "上游源", "上游源", plugin));
        SubscriptionService service = newService("{}", sources, mock(UserService.class));

        Map<String, Object> config = service.subscriptionT4("", "0");

        Map<String, Object> alist = findSite(config, "csp_AList");
        assertEquals(4, alist.get("type"));
        assertEquals("http://atv.example/t4/-/v/AList", alist.get("api"));
        assertFalse(alist.containsKey("jar"));
        assertFalse(alist.containsKey("ext"));

        Map<String, Object> media = findSite(config, "csp_Media");
        assertEquals(4, media.get("type"));
        assertEquals("http://atv.example/t4/-/v/Media", media.get("api"));

        // 插件源保持 type 3 + jar(跨端壳子无 jar 能力时不可用,属预期)
        Map<String, Object> upstream = findSite(config, "plugin-9");
        assertEquals(3, upstream.get("type"));
        assertEquals("http://atv.example/spring.jar", upstream.get("jar"));
    }

    @Test
    void userTokenEmbedsCredentialForm() {
        UserService userService = mock(UserService.class);
        cn.har01d.alist_tvbox.entity.User user = new cn.har01d.alist_tvbox.entity.User();
        user.setId(7);
        user.setUsername("alice");
        when(userService.findUserByCredentialToken("u-alice")).thenReturn(null);
        when(userService.findByUserVodToken("u-alice")).thenReturn(user);
        when(userService.vodSecretOf(user)).thenReturn("s3cret");
        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_AList", true, "csp_AList", "网盘", null));
        SubscriptionService service = newService("{}", sources, userService, 7);

        Map<String, Object> config = service.subscriptionT4("u-alice", "0");

        Map<String, Object> alist = findSite(config, "csp_AList");
        assertEquals("http://atv.example/t4/u-alice-s3cret/v/AList", alist.get("api"));
    }

    @Test
    void upstreamSiteWithoutApiIsSkipped() {
        // 上游配置可能存在无 api 字段的条目;变换须跳过而非 NPE
        String upstream = "{\"sites\":[{\"key\":\"noapi\",\"name\":\"无api站点\",\"type\":0}]}";
        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_AList", true, "csp_AList", "网盘", null));
        SubscriptionService service = newService(upstream, sources, mock(UserService.class));

        Map<String, Object> config = service.subscriptionT4("", "0");

        assertEquals(0, findSite(config, "noapi").get("type"));
        assertEquals(4, findSite(config, "csp_AList").get("type"));
    }

    @Test
    void webHomeStaysType3() {
        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-atv_home", true, "atv_home", "影视首页", null));
        SubscriptionService service = newService("{}", sources, mock(UserService.class));

        Map<String, Object> config = service.subscriptionT4("", "0");

        Map<String, Object> webHome = findSite(config, "atv_home");
        assertEquals(3, webHome.get("type"));
        assertEquals("csp_WebHome", webHome.get("api"));
        assertTrue(((String) webHome.get("jar")).endsWith("/spring.jar"));
    }

    private static Map<String, Object> findSite(Map<String, Object> config, String key) {
        List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
        return sites.stream().filter(site -> key.equals(site.get("key"))).findFirst().orElseThrow();
    }

    private SubscriptionService newService(String upstreamJson,
                                           List<SubscriptionSourceService.SubscriptionSourceRef> sources,
                                           UserService userService) {
        return newService(upstreamJson, sources, userService, 0);
    }

    private SubscriptionService newService(String upstreamJson,
                                           List<SubscriptionSourceService.SubscriptionSourceRef> sources,
                                           UserService userService, int ownerUid) {
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

        PluginFilterRepository pluginFilterRepository = mock(PluginFilterRepository.class);
        when(pluginFilterRepository.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());

        Subscription subscription = new Subscription();
        subscription.setSid("0");
        subscription.setUrl("http://up.example/config.json");
        subscription.setOwnerUid(ownerUid);
        SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
        when(subscriptionRepository.findBySid("0")).thenReturn(Optional.of(subscription));

        SubscriptionSourceService subscriptionSourceService = mock(SubscriptionSourceService.class);
        when(subscriptionSourceService.findEnabledSources()).thenReturn(sources);

        SubscriptionService service = new SubscriptionService(
                mock(Environment.class),
                new AppProperties(),
                new RestTemplateBuilder(),
                objectMapper,
                mock(JdbcTemplate.class),
                settingRepository,
                subscriptionRepository,
                mock(AccountRepository.class),
                mock(SiteRepository.class),
                shareRepository,
                driverAccountRepository,
                mock(EmbyRepository.class),
                mock(FeiniuRepository.class),
                mock(JellyfinRepository.class),
                mock(PluginRepository.class),
                pluginFilterRepository,
                mock(AListLocalService.class),
                mock(ConfigFileService.class),
                mock(TenantService.class),
                userService,
                mock(FileDownloader.class),
                subscriptionSourceService,
                mock(PlaybackTokenRepository.class),
                mock(WebHomeService.class),
                mock(HistoryRepository.class)
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
