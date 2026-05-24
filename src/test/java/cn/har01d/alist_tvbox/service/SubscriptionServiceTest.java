package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.AccountRepository;
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
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.entity.SubscriptionRepository;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {
    @Mock
    private AppProperties appProperties;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private ShareRepository shareRepository;
    @Mock
    private DriverAccountRepository panAccountRepository;
    @Mock
    private EmbyRepository embyRepository;
    @Mock
    private FeiniuRepository feiniuRepository;
    @Mock
    private JellyfinRepository jellyfinRepository;
    @Mock
    private PluginRepository pluginRepository;
    @Mock
    private PluginFilterRepository pluginFilterRepository;
    @Mock
    private AListLocalService aListLocalService;
    @Mock
    private ConfigFileService configFileService;
    @Mock
    private TenantService tenantService;
    @Mock
    private UserService userService;
    @Mock
    private FileDownloader fileDownloader;
    @Mock
    private SubscriptionSourceService subscriptionSourceService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SubscriptionService subscriptionService;

    @BeforeEach
    void setUp() {
        subscriptionService = new SubscriptionService(
                null,
                appProperties,
                new RestTemplateBuilder(),
                objectMapper,
                jdbcTemplate,
                settingRepository,
                subscriptionRepository,
                accountRepository,
                siteRepository,
                shareRepository,
                panAccountRepository,
                embyRepository,
                feiniuRepository,
                jellyfinRepository,
                pluginRepository,
                pluginFilterRepository,
                aListLocalService,
                configFileService,
                tenantService,
                userService,
                fileDownloader,
                subscriptionSourceService
        );
    }

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void subscriptionShouldWrapPythonPluginWithPyProxy() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sub/test-token/0");
        request.setScheme("http");
        request.setServerName("192.168.50.60");
        request.setServerPort(4567);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Plugin plugin = new Plugin();
        plugin.setId(7);
        plugin.setName("YouTube");
        plugin.setExtend("original-python-ext");

        Map<String, Map<String, Object>> localProxyConfig = Map.of(
                "ALI", Map.of("enabled", true, "concurrency", 20, "chunk_size", 1024)
        );

        when(subscriptionSourceService.findEnabledSources()).thenReturn(List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("plugin-7", false, null, "YouTube", plugin)
        ));
        when(pluginFilterRepository.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        when(settingRepository.findById(Constants.ALI_SECRET)).thenReturn(Optional.of(new Setting(Constants.ALI_SECRET, "secret")));
        when(shareRepository.countByType(0)).thenReturn(0);
        when(panAccountRepository.findByTypeAndMasterTrue(DriverType.QUARK)).thenReturn(Optional.empty());
        lenient().when(appProperties.getLocalProxyConfig()).thenReturn(localProxyConfig);

        subscriptionService.checkToken("test-token");
        Map<String, Object> config = subscriptionService.subscription("test-token", "", "", "");
        List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
        Map<String, Object> site = sites.getFirst();

        assertThat(site).containsEntry("api", "csp_PyProxy");
        assertThat(site).containsEntry("jar", "http://192.168.50.60:4567/spring.jar");
        assertThat(site.get("ext")).isInstanceOf(Map.class);

        Map<String, Object> ext = (Map<String, Object>) site.get("ext");
        assertThat(ext).containsEntry("py_api", "http://192.168.50.60:4567/Atvp.py");
        assertThat(ext).containsEntry("local_proxy_config", localProxyConfig);

        Map<String, Object> pyExt = objectMapper.readValue(
                Base64.getDecoder().decode((String) ext.get("py_ext")),
                Map.class
        );
        assertThat(pyExt).containsEntry("api", "http://192.168.50.60:4567");
        assertThat(pyExt).containsEntry("source", "http://192.168.50.60:4567/plugins/test-token/7.txt");
        assertThat(pyExt).containsEntry("token", "test-token");
        assertThat(pyExt).containsEntry("data", "original-python-ext");
        assertThat(pyExt).containsEntry("local_proxy_config", localProxyConfig);
    }
}
