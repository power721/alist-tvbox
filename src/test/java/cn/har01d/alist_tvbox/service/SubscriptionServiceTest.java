package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.entity.SubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {
    @Mock
    private Environment environment;
    @Mock
    private AppProperties appProperties;
    @Mock
    private RestTemplate restTemplate;
    @Spy
    private RestTemplateBuilder builder = new RestTemplateBuilder();
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private PluginRepository pluginRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private AListLocalService aListLocalService;
    @Mock
    private TenantService tenantService;
    @Mock
    private UserService userService;

    @InjectMocks
    private SubscriptionService subscriptionService;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void buildSiteShouldEmitCachedLocalProxyConfigWhenSettingMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/subscriptions");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(4567);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        when(appProperties.isEnableHttps()).thenReturn(false);
        when(appProperties.getLocalProxyConfig()).thenReturn((Map) Map.of(
                "QUARK", Map.of("enabled", true, "concurrency", 33, "chunk_size", 768),
                "UC", Map.of("enabled", false, "concurrency", 7, "chunk_size", 384)
        ));
        Map<String, Object> site = ReflectionTestUtils.invokeMethod(
                subscriptionService,
                "buildSite",
                "test-token",
                "test-uid",
                "csp_AList",
                "AList"
        );

        String ext = (String) site.get("ext");
        String json = new String(Base64.getDecoder().decode(ext), StandardCharsets.UTF_8);
        Map<String, Object> extMap = objectMapper.readValue(json, Map.class);

        assertThat(extMap).containsEntry("api", "http://127.0.0.1:4567");
        assertThat(extMap).containsEntry("token", "test-token");
        assertThat(extMap).containsEntry("uid", "test-uid");
        Map<String, Object> localProxyConfig = (Map<String, Object>) extMap.get("local_proxy_config");
        assertThat(localProxyConfig).containsKeys("QUARK", "UC");
        assertThat(localProxyConfig).doesNotContainKeys("ALI", "PAN115", "PAN123", "PAN139", "BAIDU");
        assertThat(((Map<String, Object>) localProxyConfig.get("QUARK"))).containsEntry("enabled", true);
        assertThat(((Map<String, Object>) localProxyConfig.get("QUARK"))).containsEntry("concurrency", 33);
        assertThat(((Map<String, Object>) localProxyConfig.get("QUARK"))).containsEntry("chunk_size", 768);
        assertThat(((Map<String, Object>) localProxyConfig.get("UC"))).containsEntry("enabled", false);
        assertThat(((Map<String, Object>) localProxyConfig.get("UC"))).containsEntry("concurrency", 7);
        assertThat(((Map<String, Object>) localProxyConfig.get("UC"))).containsEntry("chunk_size", 384);
    }

    @Test
    void buildSiteShouldEmitCachedLocalProxyConfig() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/subscriptions");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(4567);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        when(appProperties.isEnableHttps()).thenReturn(false);
        when(appProperties.getLocalProxyConfig()).thenReturn((Map) Map.of(
                "QUARK", Map.of("enabled", true, "concurrency", 20, "chunk_size", 1024),
                "UC", Map.of("enabled", false, "concurrency", 10, "chunk_size", 256)
        ));
        Map<String, Object> site = ReflectionTestUtils.invokeMethod(
                subscriptionService,
                "buildSite",
                "test-token",
                "test-uid",
                "csp_AList",
                "AList"
        );

        String ext = (String) site.get("ext");
        String json = new String(Base64.getDecoder().decode(ext), StandardCharsets.UTF_8);
        Map<String, Object> extMap = objectMapper.readValue(json, Map.class);
        Map<String, Object> localProxyConfig = (Map<String, Object>) extMap.get("local_proxy_config");

        assertThat(localProxyConfig).containsKey("QUARK");
        assertThat(localProxyConfig).containsKey("UC");
        assertThat(((Map<String, Object>) localProxyConfig.get("QUARK"))).containsEntry("concurrency", 20);
        assertThat(((Map<String, Object>) localProxyConfig.get("QUARK"))).containsEntry("chunk_size", 1024);
        assertThat(((Map<String, Object>) localProxyConfig.get("UC"))).containsEntry("enabled", false);
        assertThat(((Map<String, Object>) localProxyConfig.get("UC"))).containsEntry("chunk_size", 256);
    }

    @Test
    void buildPluginSiteShouldAppendExtendSuffixToTokenizedPluginUrl() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/subscriptions");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(4567);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(appProperties.isEnableHttps()).thenReturn(false);
        when(appProperties.isEnabledToken()).thenReturn(false);

        subscriptionService.checkToken("abc123");

        Plugin plugin = new Plugin();
        plugin.setId(12);
        plugin.setName("4K指南");
        plugin.setExtend("foo=bar");

        Map<String, Object> site = ReflectionTestUtils.invokeMethod(subscriptionService, "buildPluginSite", plugin);

        assertThat(site).containsEntry("name", "4K指南");
        assertThat(site).containsEntry("key", "4K指南");
        assertThat(site).containsEntry("api", "http://127.0.0.1:4567/Atvp.py");
        assertThat(site).containsEntry("ext", "http://127.0.0.1:4567/plugins/abc123/12.txt@@foo=bar");
    }

    @Test
    void addPluginSitesShouldUseEnabledPluginsInSortOrder() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/subscriptions");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(4567);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(appProperties.isEnableHttps()).thenReturn(false);
        when(appProperties.isEnabledToken()).thenReturn(false);

        subscriptionService.checkToken("abc123");

        Plugin first = new Plugin();
        first.setId(1);
        first.setName("插件A");
        first.setSortOrder(1);
        first.setEnabled(true);
        Plugin second = new Plugin();
        second.setId(2);
        second.setName("插件B");
        second.setSortOrder(2);
        second.setEnabled(true);

        when(pluginRepository.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(first, second));

        Map<String, Object> config = new HashMap<>();
        config.put("sites", new ArrayList<Map<String, Object>>());

        ReflectionTestUtils.invokeMethod(subscriptionService, "addPluginSites", config, 0);

        List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
        assertThat(sites).extracting(site -> site.get("name")).containsExactly("插件A", "插件B");
        assertThat(sites).extracting(site -> site.get("ext")).containsExactly(
                "http://127.0.0.1:4567/plugins/abc123/1.txt",
                "http://127.0.0.1:4567/plugins/abc123/2.txt"
        );
    }

    @Test
    void addPluginSitesShouldInsertBeforeExistingSites() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/subscriptions");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(4567);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(appProperties.isEnableHttps()).thenReturn(false);
        when(appProperties.isEnabledToken()).thenReturn(false);

        subscriptionService.checkToken("abc123");

        Plugin plugin = new Plugin();
        plugin.setId(1);
        plugin.setName("插件A");
        plugin.setSortOrder(1);
        plugin.setEnabled(true);

        when(pluginRepository.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(plugin));

        Map<String, Object> builtIn = new HashMap<>();
        builtIn.put("name", "AList");
        Map<String, Object> config = new HashMap<>();
        config.put("sites", new ArrayList<>(List.of(builtIn)));

        ReflectionTestUtils.invokeMethod(subscriptionService, "addPluginSites", config, 0);

        List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
        assertThat(sites).extracting(site -> site.get("name")).containsExactly("插件A", "AList");
        assertThat(sites.getFirst()).containsEntry("ext", "http://127.0.0.1:4567/plugins/abc123/1.txt");
    }
}
