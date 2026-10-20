package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.dto.ClientSyncRule;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.env.Environment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * #1084 第三方插件网盘凭证同步开关:client_cookie_sync 关闭后,
 * /cookies/{secret} 即使密钥合法也必须返回空——spring.jar 只写服务端下发的
 * 内容,空响应即零写入(饭太硬/摸鱼/潇洒/wex/VOX/OK影视/傲物兼容位置全停),
 * 且不能触发任何账号查询(凭证不进内存更不出网)。
 */
class ShareServiceClientCookieSyncTest {

    private AppProperties appProperties;
    private SettingRepository settingRepository;
    private DriverAccountRepository driverAccountRepository;
    private ShareService service;

    @BeforeEach
    void setUp() {
        appProperties = mock(AppProperties.class);
        settingRepository = mock(SettingRepository.class);
        driverAccountRepository = mock(DriverAccountRepository.class);
        when(settingRepository.findById("ali_secret")).thenReturn(Optional.of(new Setting("ali_secret", "topsecret")));

        AListLocalService aListLocalService = mock(AListLocalService.class);
        when(aListLocalService.getInternalPort()).thenReturn(4567);

        service = new ShareService(
                appProperties,
                mock(ShareRepository.class),
                mock(MetaRepository.class),
                mock(AListAliasRepository.class),
                settingRepository,
                mock(SiteRepository.class),
                mock(AccountRepository.class),
                driverAccountRepository,
                mock(AListService.class),
                mock(DriverAccountService.class),
                mock(AccountService.class),
                aListLocalService,
                mock(ConfigFileService.class),
                mock(PikPakService.class),
                mock(OfflineDownloadService.class),
                new RestTemplateBuilder(),
                mock(Environment.class),
                new ObjectMapper(),
                mock(UserService.class));
    }

    private void stubQuarkAccount() {
        DriverAccount quark = new DriverAccount();
        quark.setType(DriverType.QUARK);
        quark.setCookie("__pus=abc");
        when(driverAccountRepository.findByTypeAndMasterTrue(any(DriverType.class))).thenReturn(Optional.empty());
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.QUARK)).thenReturn(Optional.of(quark));
    }

    /** 开关关闭:合法共享 secret 也拿不到任何凭证,且账号仓库零查询。 */
    @Test
    void getCookiesReturnsEmptyWhenDisabled() {
        when(appProperties.isClientCookieSync()).thenReturn(false);
        stubQuarkAccount();

        ObjectNode result = service.getCookies("topsecret");

        assertThat(result.size()).isZero();
        verifyNoInteractions(driverAccountRepository);
    }

    /** 开关关闭:legacy 单盘直链端点同样返回空(客户端缓存的旧注入直链随之失效)。 */
    @Test
    void legacyCookieEndpointsReturnEmptyWhenDisabled() {
        when(appProperties.isClientCookieSync()).thenReturn(false);
        stubQuarkAccount();

        assertThat(service.getQuarkCookie("topsecret")).isEmpty();
        assertThat(service.getUcCookie("topsecret")).isEmpty();
        assertThat(service.get115Cookie("topsecret")).isEmpty();
        assertThat(service.getBaiduCookie("topsecret")).isEmpty();
        verifyNoInteractions(driverAccountRepository);
    }

    /** 开关开启(默认行为不变):凭证照常下发。 */
    @Test
    void getCookiesStillDistributesWhenEnabled() {
        when(appProperties.isClientCookieSync()).thenReturn(true);
        stubQuarkAccount();

        ObjectNode result = service.getCookies("topsecret");

        assertThat(result.has("quark")).isTrue();
        assertThat(result.get("quark").get("cookie").asText()).isEqualTo("__pus=abc");
    }

    /** 矩阵未配置(默认):不下发 syncPolicy 键(旧版 jar 全量,新版 jar 也全量)。 */
    @Test
    void getCookiesOmitsSyncPolicyWhenScopeEmpty() {
        when(appProperties.isClientCookieSync()).thenReturn(true);
        when(appProperties.getClientCookieScope()).thenReturn(new java.util.HashMap<>());
        stubQuarkAccount();

        ObjectNode result = service.getCookies("topsecret");

        assertThat(result.has("syncPolicy")).isFalse();
        assertThat(result.has("quark")).isTrue();
    }

    /** 矩阵配置:只下发非默认行(禁用行/盘白名单行);默认行与 pg 行不下发。 */
    @Test
    void getCookiesEmbedsNonDefaultSyncPolicyRows() {
        when(appProperties.isClientCookieSync()).thenReturn(true);
        java.util.Map<String, ClientSyncRule> scope = new java.util.HashMap<>();
        ClientSyncRule xs = new ClientSyncRule();
        xs.setEnabled(false);
        scope.put("xs", xs);
        ClientSyncRule fty = new ClientSyncRule();
        fty.getDrives().add("quark");
        scope.put("fty", fty);
        ClientSyncRule wexDefault = new ClientSyncRule(); // 启用+全盘 = 默认行,不下发
        scope.put("wex", wexDefault);
        ClientSyncRule pg = new ClientSyncRule(); // pg 行服务端直接消费,不下发给 spider
        pg.setEnabled(false);
        scope.put("pg", pg);
        when(appProperties.getClientCookieScope()).thenReturn(scope);
        stubQuarkAccount();

        ObjectNode result = service.getCookies("topsecret");

        assertThat(result.has("syncPolicy")).isTrue();
        var policy = result.get("syncPolicy");
        assertThat(policy.has("xs")).isTrue();
        assertThat(policy.get("xs").get("enabled").asBoolean()).isFalse();
        assertThat(policy.get("xs").has("drives")).isFalse();
        assertThat(policy.has("fty")).isTrue();
        assertThat(policy.get("fty").get("enabled").asBoolean()).isTrue();
        assertThat(policy.get("fty").get("drives").get(0).asText()).isEqualTo("quark");
        assertThat(policy.has("wex")).isFalse();
        assertThat(policy.has("pg")).isFalse();
        // 策略键不参与凭证下发,quark 凭证照常在
        assertThat(result.has("quark")).isTrue();
    }

    /** ClientSyncRule 语义:drives 空 = 允许全部;默认行判定。 */
    @Test
    void clientSyncRuleSemantics() {
        ClientSyncRule rule = new ClientSyncRule();
        assertThat(rule.isDefault()).isTrue();
        assertThat(rule.allowsDrive("quark")).isTrue();
        rule.getDrives().add("quark");
        assertThat(rule.isDefault()).isFalse();
        assertThat(rule.allowsDrive("quark")).isTrue();
        assertThat(rule.allowsDrive("115")).isFalse();
        rule.setEnabled(false);
        assertThat(rule.isDefault()).isFalse();
    }

    /** 总开关默认关(#1084 定规:凭证不出服务端,安全优先;需要第三方插件免扫码的用户自行开启)。 */
    @Test
    void clientCookieSyncDefaultsToDisabled() {
        assertThat(new AppProperties().isClientCookieSync()).isFalse();
    }
}
