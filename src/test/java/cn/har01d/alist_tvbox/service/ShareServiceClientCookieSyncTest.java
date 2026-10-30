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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * #1084 第三方插件网盘凭证同步开关:client_cookie_sync 关闭后,
 * /cookies/{secret} 即使密钥合法也必须返回空——spring.jar 只写服务端下发的
 * 内容,空响应即零写入,且不能触发任何账号查询(凭证不进内存更不出网)。
 * 出厂默认(2026-10-21 定规):饭太硬/真心/PG/猫源开,摸鱼/潇洒/王二小/VOX/OK影视/嗷呜关。
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
        when(appProperties.getClientCookieScope()).thenReturn(new HashMap<>());
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
        when(appProperties.getClientCookieScope()).thenReturn(new HashMap<>());
        stubQuarkAccount();

        ObjectNode result = service.getCookies("topsecret");

        assertThat(result.has("quark")).isTrue();
        assertThat(result.get("quark").get("cookie").asText()).isEqualTo("__pus=abc");
    }

    /**
     * 矩阵未配置:syncPolicy 显式下发 6 个出厂关行(spider 侧「行缺失」语义是启用,
     * 必须显式禁用);出厂开的 fty 与 server 端行(zx/pg/cat)不下发。
     */
    @Test
    void getCookiesEmitsFactoryDisabledRowsWhenScopeEmpty() {
        when(appProperties.isClientCookieSync()).thenReturn(true);
        when(appProperties.getClientCookieScope()).thenReturn(new HashMap<>());
        stubQuarkAccount();

        ObjectNode result = service.getCookies("topsecret");

        assertThat(result.has("syncPolicy")).isTrue();
        var policy = result.get("syncPolicy");
        for (String plugin : new String[]{"fish", "xs", "wex", "vox", "ok", "aowu"}) {
            assertThat(policy.has(plugin)).as("plugin %s 应显式下发 enabled:false", plugin).isTrue();
            assertThat(policy.get(plugin).get("enabled").asBoolean()).isFalse();
        }
        assertThat(policy.has("fty")).isFalse();
        assertThat(policy.has("zx")).isFalse();
        assertThat(policy.has("pg")).isFalse();
        assertThat(policy.has("cat")).isFalse();
        assertThat(result.has("quark")).isTrue();
    }

    /** 矩阵配置:只下发与 spider 缺省(启用+全部网盘)不等价的行;等价行(含显式开+全盘)不下发。 */
    @Test
    void getCookiesEmbedsNonFactoryDefaultRows() {
        when(appProperties.isClientCookieSync()).thenReturn(true);
        Map<String, ClientSyncRule> scope = new HashMap<>();
        ClientSyncRule fty = new ClientSyncRule(); // 出厂开,显式关 → 下发
        fty.setEnabled(false);
        scope.put("fty", fty);
        ClientSyncRule wex = new ClientSyncRule(); // 出厂关,显式开+全盘 = spider 缺省等价 → 不下发(效果同开)
        scope.put("wex", wex);
        ClientSyncRule xs = new ClientSyncRule(); // 显式关+全盘,出厂也是关 → 下发(与 spider 缺省不等价)
        xs.setEnabled(false);
        scope.put("xs", xs);
        ClientSyncRule fish = new ClientSyncRule(); // 出厂关,显式开+盘白名单 → 下发带 drives
        fish.getDrives().add("quark");
        scope.put("fish", fish);
        when(appProperties.getClientCookieScope()).thenReturn(scope);
        stubQuarkAccount();

        ObjectNode result = service.getCookies("topsecret");

        assertThat(result.has("syncPolicy")).isTrue();
        var policy = result.get("syncPolicy");
        assertThat(policy.get("fty").get("enabled").asBoolean()).isFalse();
        assertThat(policy.has("wex")).isFalse();
        assertThat(policy.get("fish").get("enabled").asBoolean()).isTrue();
        assertThat(policy.get("fish").get("drives").get(0).asText()).isEqualTo("quark");
        // 出厂关且未配置的行仍显式禁用;显式关的 xs 同样禁用
        assertThat(policy.get("xs").get("enabled").asBoolean()).isFalse();
        assertThat(policy.get("aowu").get("enabled").asBoolean()).isFalse();
    }

    /**
     * 123 账号持有 token(Go 401 重登回写)时随账密一并下发:
     * 摸鱼 pan123 拿到 user.token 即免在线登录,绕开 123 登录风控(#1088)。
     */
    @Test
    void getCookiesDistributesPan123Token() {
        when(appProperties.isClientCookieSync()).thenReturn(true);
        when(appProperties.getClientCookieScope()).thenReturn(new HashMap<>());
        DriverAccount pan123 = new DriverAccount();
        pan123.setType(DriverType.PAN123);
        pan123.setUsername("user");
        pan123.setPassword("pass");
        pan123.setToken("tok_live");
        when(driverAccountRepository.findByTypeAndMasterTrue(any(DriverType.class))).thenReturn(Optional.empty());
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN123)).thenReturn(Optional.of(pan123));

        ObjectNode result = service.getCookies("topsecret");

        assertThat(result.has("123")).isTrue();
        assertThat(result.get("123").get("username").asText()).isEqualTo("user");
        assertThat(result.get("123").get("password").asText()).isEqualTo("pass");
        assertThat(result.get("123").get("token").asText()).isEqualTo("tok_live");
    }

    /**
     * 迅雷 refresh_token 真值化(#1088 摸鱼未登录):cookie 列是 captchaToken,
     * PL SetTokenResp 回传的真 refresh_token 落 addition,无真值时宁缺毋滥不下发该字段。
     */
    @Test
    void getCookiesDistributesXunleiRealRefreshToken() {
        when(appProperties.isClientCookieSync()).thenReturn(true);
        when(appProperties.getClientCookieScope()).thenReturn(new HashMap<>());
        DriverAccount thunder = new DriverAccount();
        thunder.setType(DriverType.THUNDER);
        thunder.setToken("access_live");
        thunder.setCookie("captcha_token");
        thunder.setAddition("{\"refresh_token\":\"rt_live\",\"device_id\":\"dev1\"}");
        when(driverAccountRepository.findByTypeAndMasterTrue(any(DriverType.class))).thenReturn(Optional.empty());
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.THUNDER)).thenReturn(Optional.of(thunder));

        ObjectNode result = service.getCookies("topsecret");

        var xunlei = result.get("xunlei");
        assertThat(xunlei.get("access_token").asText()).isEqualTo("access_live");
        assertThat(xunlei.get("refresh_token").asText()).isEqualTo("rt_live");
        assertThat(xunlei.get("device_id").asText()).isEqualTo("dev1");
    }

    /** 迅雷存量无真 refresh_token:addition 缺键时不下发 refresh_token(不发 captchaToken 冒充)。 */
    @Test
    void getCookiesOmitsXunleiRefreshTokenWhenLegacy() {
        when(appProperties.isClientCookieSync()).thenReturn(true);
        when(appProperties.getClientCookieScope()).thenReturn(new HashMap<>());
        DriverAccount thunder = new DriverAccount();
        thunder.setType(DriverType.THUNDER);
        thunder.setToken("access_live");
        thunder.setCookie("captcha_token");
        thunder.setAddition("{\"device_id\":\"dev1\"}");
        when(driverAccountRepository.findByTypeAndMasterTrue(any(DriverType.class))).thenReturn(Optional.empty());
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.THUNDER)).thenReturn(Optional.of(thunder));

        ObjectNode result = service.getCookies("topsecret");

        var xunlei = result.get("xunlei");
        assertThat(xunlei.has("refresh_token")).isFalse();
        assertThat(xunlei.get("access_token").asText()).isEqualTo("access_live");
    }

    /** 天翼完整会话下发:PL 登录/刷新回写的 sessionKey 体系(addition.tianyi_session)。 */
    @Test
    void getCookiesDistributes189Session() {
        when(appProperties.isClientCookieSync()).thenReturn(true);
        when(appProperties.getClientCookieScope()).thenReturn(new HashMap<>());
        DriverAccount cloud189 = new DriverAccount();
        cloud189.setType(DriverType.CLOUD189);
        cloud189.setCookie("JSESSIONID=x");
        cloud189.setUsername("u");
        cloud189.setPassword("p");
        cloud189.setAddition("{\"tianyi_session\":\"{\\\"sessionKey\\\":\\\"sk\\\",\\\"sessionSecret\\\":\\\"ss\\\"}\"}");
        when(driverAccountRepository.findByTypeAndMasterTrue(any(DriverType.class))).thenReturn(Optional.empty());
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.CLOUD189)).thenReturn(Optional.of(cloud189));

        ObjectNode result = service.getCookies("topsecret");

        var node189 = result.get("189");
        assertThat(node189.get("username").asText()).isEqualTo("u");
        assertThat(node189.get("session").asText()).contains("sessionKey");
    }

    /** 出厂默认与统一判定语义(AppProperties 真实实例,非 mock)。 */
    @Test
    void factoryDefaultsAndAllowPluginDrive() {
        AppProperties props = new AppProperties();
        // 总开关默认开(存量行为不变)
        assertThat(props.isClientCookieSync()).isTrue();
        // 出厂默认:fty/zx/pg/cat 开,其余关
        assertThat(ClientSyncRule.factoryEnabled("fty")).isTrue();
        assertThat(ClientSyncRule.factoryEnabled("zx")).isTrue();
        assertThat(ClientSyncRule.factoryEnabled("pg")).isTrue();
        assertThat(ClientSyncRule.factoryEnabled("cat")).isTrue();
        assertThat(ClientSyncRule.factoryEnabled("fish")).isFalse();
        assertThat(ClientSyncRule.factoryEnabled("aowu")).isFalse();
        // 未配置:出厂开的全盘允许,出厂关的全拒
        assertThat(props.allowPluginDrive("fty", "ali")).isTrue();
        assertThat(props.allowPluginDrive("fish", "quark")).isFalse();
        // 总开关关:全部拒绝
        props.setClientCookieSync(false);
        assertThat(props.allowPluginDrive("fty", "ali")).isFalse();
        assertThat(props.allowPlugin("pg")).isFalse();
        // 行显式开+盘白名单:白名单外拒绝
        props.setClientCookieSync(true);
        ClientSyncRule fty = new ClientSyncRule();
        fty.getDrives().add("quark");
        Map<String, ClientSyncRule> scope = new HashMap<>();
        scope.put("fty", fty);
        props.setClientCookieScope(scope);
        assertThat(props.allowPluginDrive("fty", "quark")).isTrue();
        assertThat(props.allowPluginDrive("fty", "ali")).isFalse();
        assertThat(props.allowPlugin("fty")).isTrue();
        // 出厂等价判定:isFactoryDefault
        assertThat(ClientSyncRule.isFactoryDefault("fty", null)).isTrue();
        assertThat(ClientSyncRule.isFactoryDefault("fish", null)).isTrue(); // null 行=出厂=等价
        assertThat(ClientSyncRule.isFactoryDefault("fty", fty)).isFalse();
        ClientSyncRule fishOn = new ClientSyncRule(); // 开+全盘,出厂是关 → 不等价
        assertThat(ClientSyncRule.isFactoryDefault("fish", fishOn)).isFalse();
        // spider 缺省等价判定(下发依据,与出厂无关)
        assertThat(ClientSyncRule.matchesSpiderDefault("fty", null)).isTrue(); // 出厂开未配置=缺省等价,不发
        assertThat(ClientSyncRule.matchesSpiderDefault("fish", null)).isFalse(); // 出厂关必须显式发 false
    }
}
