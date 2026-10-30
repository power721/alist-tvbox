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
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4 订阅配置(/t4/{token}/{id}):内置 csp_* 站点变换为 type 4 + api 指回本服务
 * /t4 适配层(去 jar/ext);插件源与 WebHome 保持 type 3 混跑。
 */
class SubscriptionServiceT4Test {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SettingRepository settingRepository;
    private SubscriptionSourceService subscriptionSourceService;
    private AppProperties appProperties;

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
    void builtinSitesBecomeType4AndType3SitesRemoved() {
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

        // type 3 站点(插件源等)整体移除:无 jar 壳子用不了,留着是死重;全局 spider 字段一并移除
        assertTrue(findSiteKeys(config).stream().noneMatch("plugin-9"::equals));
        assertFalse(config.containsKey("spider"));
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
    void webHomeType3Removed() {
        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-atv_home", true, "atv_home", "影视首页", null));
        SubscriptionService service = newService("{}", sources, mock(UserService.class));

        Map<String, Object> config = service.subscriptionT4("", "0");

        assertTrue(findSiteKeys(config).stream().noneMatch("atv_home"::equals));
        assertFalse(config.containsKey("spider"));
    }

    @Test
    void pluginSitesBecomeType4WhenRuntimeAvailable() {
        // 服务端 Python 运行时就绪:插件源(密文 spider 插件)T4 化,api 指回 /t4/{token}/v/{key}
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setUrl("https://up.example/spider.txt");
        plugin.setName("上游源");
        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("plugin-9", false, "上游源", "上游源", plugin));
        SubscriptionService service = newService("{}", sources, mock(UserService.class));

        Map<String, Object> config = service.subscriptionT4("", "0", true);

        Map<String, Object> upstream = findSite(config, "plugin-9");
        assertEquals(4, upstream.get("type"));
        assertEquals("http://atv.example/t4/-/v/plugin-9", upstream.get("api"));
        assertFalse(upstream.containsKey("jar"));
        assertFalse(upstream.containsKey("ext"));

        // 运行时不可用:维持移除口径
        Map<String, Object> config2 = service.subscriptionT4("", "0", false);
        assertTrue(findSiteKeys(config2).stream().noneMatch("plugin-9"::equals));
    }

    @Test
    void t4HiddenPluginSourceRemovedFromT4Config() {
        // 订阅源管理「T4」开关关闭:插件源不 T4 化,随 type 3 移除;T3 订阅不受影响
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setUrl("https://up.example/spider.txt");
        plugin.setName("上游源");
        plugin.setT4Visible(false);
        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_AList", true, "csp_AList", "网盘", null),
                new SubscriptionSourceService.SubscriptionSourceRef("plugin-9", false, "上游源", "上游源", plugin));
        SubscriptionService service = newService("{}", sources, mock(UserService.class));
        when(subscriptionSourceService.t4HiddenSiteKeys()).thenReturn(java.util.Set.of("plugin-9"));

        Map<String, Object> config = service.subscriptionT4("", "0", true);

        assertTrue(findSiteKeys(config).stream().noneMatch("plugin-9"::equals));
        // 未隐藏的内置源不受牵连
        assertEquals(4, findSite(config, "csp_AList").get("type"));
    }

    @Test
    void t4HiddenBuiltinSiteRemovedFromT4Config() {
        // 内置源「T4」开关关闭:站点直接从 T4 配置删除(独立地址与订阅 T4 同一变换)
        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_AList", true, "csp_AList", "网盘", null),
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_Media", true, "csp_Media", "我的追剧", null));
        SubscriptionService service = newService("{}", sources, mock(UserService.class));
        when(subscriptionSourceService.t4HiddenSiteKeys()).thenReturn(java.util.Set.of("csp_AList"));

        Map<String, Object> config = service.subscriptionT4("", "0", true);

        assertTrue(findSiteKeys(config).stream().noneMatch("csp_AList"::equals));
        assertEquals(4, findSite(config, "csp_Media").get("type"));
    }

    @Test
    void plainHttpUpstreamSitesKept() {
        // type 0/1/2 的普通 HTTP 站点不依赖 jar,保留
        String upstream = "{\"sites\":[{\"key\":\"cms\",\"name\":\"CMS采集\",\"type\":1,\"api\":\"https://cj.example/api.php/provide/vod\"}]}";
        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_AList", true, "csp_AList", "网盘", null));
        SubscriptionService service = newService(upstream, sources, mock(UserService.class));

        Map<String, Object> config = service.subscriptionT4("", "0");

        Map<String, Object> cms = findSite(config, "cms");
        assertEquals(1, cms.get("type"));
        assertEquals("https://cj.example/api.php/provide/vod", cms.get("api"));
    }

    @Test
    void standaloneT4EmbedsDedicatedTokenWithoutSubscription() {
        // 独立 T4 配置(/t4/{t4token}):空上游自配置 + 变换,站点 api 嵌 T4 专用 token,
        // 不挂订阅号、零上游网络(空上游跳过合并循环)
        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_AList", true, "csp_AList", "网盘", null),
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_Media", true, "csp_Media", "我的追剧", null));
        SubscriptionService service = newService("{}", sources, mock(UserService.class));
        ReflectionTestUtils.setField(service, "t4Token", "t4abcd12");

        Map<String, Object> config = service.standaloneT4("t4abcd12", false);

        Map<String, Object> alist = findSite(config, "csp_AList");
        assertEquals(4, alist.get("type"));
        assertEquals("http://atv.example/t4/t4abcd12/v/AList", alist.get("api"));
        assertFalse(alist.containsKey("jar"));
        assertFalse(config.containsKey("spider"));
    }

    @Test
    void resolveT4TokenMapsDedicatedTokenToBackingVodToken() {
        SubscriptionService service = newService("{}", List.of(), mock(UserService.class));
        ReflectionTestUtils.setField(service, "t4Token", "t4abcd12");

        // 未命中:原样返回(旧 vod token 形态继续走 checkToken)
        assertEquals("abc", service.resolveT4Token("abc"));
        // 命中但安全订阅关/无共享 token:空串(无 token 口径)
        assertEquals("", service.resolveT4Token("t4abcd12"));
        // 命中且安全订阅开:首个共享 token
        appProperties.setEnabledToken(true);
        ReflectionTestUtils.setField(service, "tokens", "shared1,shared2");
        assertEquals("shared1", service.resolveT4Token("t4abcd12"));
        assertTrue(service.isT4Token("t4abcd12"));
        assertFalse(service.isT4Token("abc"));
    }

    @Test
    void regenerateT4TokenRotatesAndPersists() {
        SubscriptionService service = newService("{}", List.of(), mock(UserService.class));

        String token = service.regenerateT4Token();

        assertEquals(token, service.getT4Token());
        assertTrue(service.isT4Token(token));
        assertFalse(service.isT4Token("t4abcd12"));
        org.mockito.ArgumentCaptor<Setting> captor = org.mockito.ArgumentCaptor.forClass(Setting.class);
        verify(settingRepository).save(captor.capture());
        assertEquals(Constants.T4_TOKEN, captor.getValue().getName());
        assertEquals(token, captor.getValue().getValue());
    }

    @Test
    void t4DisabledRejectsConfigGeneration() {
        // t4_enabled 关:配置生成(独立/订阅形态)一律 404
        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_AList", true, "csp_AList", "网盘", null));
        SubscriptionService service = newService("{}", sources, mock(UserService.class));
        ReflectionTestUtils.setField(service, "t4Token", "t4abcd12");
        appProperties.setT4Enabled(false);

        assertThrows(NotFoundException.class, () -> service.standaloneT4("t4abcd12", false));
        assertThrows(NotFoundException.class, () -> service.subscriptionT4("", "0"));
    }

    @Test
    void updateT4TokenValidatesAndPersists() {
        SubscriptionService service = newService("{}", List.of(), mock(UserService.class));

        assertEquals("my-t4-key_9", service.updateT4Token("my-t4-key_9"));
        assertTrue(service.isT4Token("my-t4-key_9"));

        // 纯数字遮蔽旧裸订阅号形态/短值/非法字符/u- 前缀(用户 token 保留空间) → 拒绝
        assertThrows(BadRequestException.class, () -> service.updateT4Token("123"));
        assertThrows(BadRequestException.class, () -> service.updateT4Token("ab1"));
        assertThrows(BadRequestException.class, () -> service.updateT4Token("a b!"));
        assertThrows(BadRequestException.class, () -> service.updateT4Token("u-alice"));
    }

    @Test
    void subscriptionOverrideWhitelistFiltersT4Config() {
        // T3 订阅 override 的 sites-whitelist 对 T4 订阅形态天然生效(subscriptionT4 先跑完整
        // subscription() 含 resolveAndApplyFilters,再 applyT4):多入口分站点集复用 T3 机制,
        // 每个订阅一份白名单,各自的 /t4/{token}/{订阅号} 即不同的站点集
        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_AList", true, "csp_AList", "网盘", null),
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_Media", true, "csp_Media", "我的追剧", null));
        SubscriptionService service = newService("{}", sources, mock(UserService.class), 0,
                "{\"sites-whitelist\":[\"csp_AList\"]}");

        Map<String, Object> config = service.subscriptionT4("", "0", true);

        assertEquals(4, findSite(config, "csp_AList").get("type"));
        assertTrue(findSiteKeys(config).stream().noneMatch("csp_Media"::equals));
    }

    @Test
    void subscriptionOverrideBlacklistFiltersT4Config() {
        // sites-blacklist 同理:黑名单里的插件源在 T4 订阅形态不出现(即使 Python 运行时就绪)
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setUrl("https://up.example/spider.txt");
        plugin.setName("上游源");
        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_AList", true, "csp_AList", "网盘", null),
                new SubscriptionSourceService.SubscriptionSourceRef("plugin-9", false, "上游源", "上游源", plugin));
        SubscriptionService service = newService("{}", sources, mock(UserService.class), 0,
                "{\"sites-blacklist\":[\"plugin-9\"]}");

        Map<String, Object> config = service.subscriptionT4("", "0", true);

        assertEquals(4, findSite(config, "csp_AList").get("type"));
        assertTrue(findSiteKeys(config).stream().noneMatch("plugin-9"::equals));
    }

    private static Map<String, Object> findSite(Map<String, Object> config, String key) {
        List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
        return sites.stream().filter(site -> key.equals(site.get("key"))).findFirst().orElseThrow();
    }

    private static List<String> findSiteKeys(Map<String, Object> config) {
        List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
        return sites.stream().map(site -> String.valueOf(site.get("key"))).toList();
    }

    private SubscriptionService newService(String upstreamJson,
                                           List<SubscriptionSourceService.SubscriptionSourceRef> sources,
                                           UserService userService) {
        return newService(upstreamJson, sources, userService, 0);
    }

    private SubscriptionService newService(String upstreamJson,
                                           List<SubscriptionSourceService.SubscriptionSourceRef> sources,
                                           UserService userService, int ownerUid) {
        return newService(upstreamJson, sources, userService, ownerUid, null);
    }

    private SubscriptionService newService(String upstreamJson,
                                           List<SubscriptionSourceService.SubscriptionSourceRef> sources,
                                           UserService userService, int ownerUid, String override) {
        settingRepository = mock(SettingRepository.class);
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
        subscription.setOverride(override);
        SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
        when(subscriptionRepository.findBySid("0")).thenReturn(Optional.of(subscription));

        subscriptionSourceService = mock(SubscriptionSourceService.class);
        when(subscriptionSourceService.findEnabledSources()).thenReturn(sources);
        when(subscriptionSourceService.t4HiddenSiteKeys()).thenReturn(java.util.Set.of());

        appProperties = new AppProperties();
        SubscriptionService service = new SubscriptionService(
                mock(Environment.class),
                appProperties,
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
