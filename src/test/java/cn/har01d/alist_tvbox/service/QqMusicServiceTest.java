package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.qqmusic.QqMusicLoginStatus;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QqMusicServiceTest {

    @Mock
    private PluginRepository pluginRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private QqMusicService newService() {
        return new QqMusicService(objectMapper, pluginRepository);
    }

    private Plugin plugin(String name, String extend) {
        Plugin plugin = new Plugin();
        plugin.setName(name);
        plugin.setExtend(extend);
        return plugin;
    }

    @Test
    void hash33MatchesPythonImplementation() {
        // 期望值由 py/QQ音乐.py 的 hash33 生成
        assertThat(QqMusicService.hash33("abc", 0)).isEqualTo(108966);
        assertThat(QqMusicService.hash33("qrsig-demo-123", 0)).isEqualTo(2026555355);
        assertThat(QqMusicService.hash33("p_skey-demo", 5381)).isEqualTo(1404090882);
        assertThat(QqMusicService.hash33("", 0)).isEqualTo(0);
        assertThat(QqMusicService.hash33("中文测试", 0)).isEqualTo(748470484);
    }

    @Test
    void parsesPtuiCallbackStates() {
        String[] waiting = QqMusicService.parsePtuiCallback("ptuiCB('66','0','','0','二维码未失效。', '')");
        assertThat(waiting).isNotNull();
        assertThat(waiting[0]).isEqualTo("66");
        assertThat(waiting[4]).isEqualTo("二维码未失效。");

        String[] confirmed = QqMusicService.parsePtuiCallback("ptuiCB('67','0','','0','二维码认证中。', '')");
        assertThat(confirmed[0]).isEqualTo("67");

        String[] done = QqMusicService.parsePtuiCallback(
                "ptuiCB('0','0','https://ssl.ptlogin2.graph.qq.com/check_sig?pttype=1&uin=123456"
                        + "&service=ptqrlogin&nodirect=0&ptsigx=abc123&s_url=https%3A%2F%2Fgraph.qq.com','0','登录成功！', '')");
        assertThat(done[0]).isEqualTo("0");
        assertThat(done[2]).contains("uin=123456").contains("ptsigx=abc123");
    }

    @Test
    void parsePtuiCallbackReturnsNullForInvalidBody() {
        assertThat(QqMusicService.parsePtuiCallback("")).isNull();
        assertThat(QqMusicService.parsePtuiCallback(null)).isNull();
        assertThat(QqMusicService.parsePtuiCallback("window.wx_errcode=408;")).isNull();
    }

    @Test
    void extractsParamsFromCallbackUrl() {
        String url = "https://ssl.ptlogin2.graph.qq.com/check_sig?pttype=1&uin=123456"
                + "&service=ptqrlogin&nodirect=0&ptsigx=sig99&s_url=https%3A%2F%2Fgraph.qq.com";
        assertThat(QqMusicService.extractParam(url, "&uin=", "&service")).isEqualTo("123456");
        assertThat(QqMusicService.extractParam(url, "ptsigx=", "&s_url")).isEqualTo("sig99");
        assertThat(QqMusicService.extractParam(url, "missing=", "&")).isEmpty();
    }

    @Test
    void extractsCodeFromOAuthRedirect() {
        String location = "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/&code=ABCDEF&state=state";
        assertThat(QqMusicService.extractParam(location, "code=", "&")).isEqualTo("ABCDEF");
        assertThat(QqMusicService.extractParam("https://y.qq.com/?code=XYZ", "code=", null)).isEqualTo("XYZ");
    }

    @Test
    void buildsCredentialExtendCompatibleWithSpiderConfig() throws IOException {
        String data = "{\"musicid\": 2469696018, \"musickey\": \"ABC\", \"refresh_token\": \"RT\", "
                + "\"openid\": \"OID\", \"encryptUin\": \"EIN\", \"expired_at\": 1755500000}";
        String extend = QqMusicService.buildCredentialExtend(objectMapper.readTree(data), 2);
        var node = objectMapper.readTree(extend);
        assertThat(node.path("musicid").asText()).isEqualTo("2469696018");
        assertThat(node.path("musickey").asText()).isEqualTo("ABC");
        assertThat(node.path("loginType").asInt()).isEqualTo(2);
        assertThat(node.path("login_type").asInt()).isEqualTo(2);
        assertThat(node.path("refresh_token").asText()).isEqualTo("RT");
    }

    @Test
    void buildsWxCredentialWithTypeOne() throws IOException {
        String extend = QqMusicService.buildCredentialExtend(objectMapper.readTree("{\"musicid\": 1, \"musickey\": \"K\"}"), 1);
        var node = objectMapper.readTree(extend);
        assertThat(node.path("loginType").asInt()).isEqualTo(1);
        assertThat(node.path("login_type").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsEmptyCredentialData() {
        assertThatThrownBy(() -> QqMusicService.buildCredentialExtend(objectMapper.readTree("{}"), 2))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> QqMusicService.buildCredentialExtend(null, 2))
                .isInstanceOf(IOException.class);
    }

    @Test
    void checkLoginReportsExpiredForUnknownKey() {
        QqMusicLoginStatus status = newService().checkLogin("missing");
        assertThat(status.status()).isEqualTo("expired");
        assertThat(status.extend()).isNull();
    }

    @Test
    void checksCredentialRefreshable() throws IOException {
        String full = "{\"refresh_key\":\"RK\",\"refresh_token\":\"RT\",\"musickey\":\"MK\",\"musicid\":\"123\",\"expired_at\":1755500000}";
        assertThat(QqMusicService.isCredentialRefreshable(objectMapper.readTree(full))).isTrue();
        assertThat(QqMusicService.isCredentialRefreshable(objectMapper.readTree("{}"))).isFalse();
        assertThat(QqMusicService.isCredentialRefreshable(
                objectMapper.readTree("{\"refresh_token\":\"RT\",\"musickey\":\"MK\",\"musicid\":\"123\"}"))).isFalse();
        assertThat(QqMusicService.isCredentialRefreshable(null)).isFalse();
    }

    @Test
    void mergesRefreshedCredentialFields() throws IOException {
        var current = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(
                "{\"musicid\":123,\"musickey\":\"OLD\",\"refresh_key\":\"RK\",\"extra\":\"keep\",\"loginType\":2}");
        String data = "{\"musickey\":\"NEW\",\"refresh_key\":\"RK2\",\"musicid\":123,\"expired_at\":1755500999}";
        String merged = QqMusicService.mergeRefreshedCredential(current, objectMapper.readTree(data));
        var node = objectMapper.readTree(merged);
        assertThat(node.path("musickey").asText()).isEqualTo("NEW");
        assertThat(node.path("refresh_key").asText()).isEqualTo("RK2");
        assertThat(node.path("musicid").asText()).isEqualTo("123");
        assertThat(node.path("str_musicid").asText()).isEqualTo("123");
        assertThat(node.path("extra").asText()).isEqualTo("keep");
        assertThat(node.path("loginType").asInt()).isEqualTo(2);
    }

    @Test
    void mergeReturnsNullForEmptyData() throws IOException {
        var current = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("{\"musicid\":123}");
        assertThat(QqMusicService.mergeRefreshedCredential(current, objectMapper.readTree("{}"))).isNull();
        assertThat(QqMusicService.mergeRefreshedCredential(current, null)).isNull();
    }

    @Test
    void refreshCredentialReturnsNullWhenNotRefreshable() throws IOException {
        assertThat(newService().refreshCredential(null)).isNull();
        assertThat(newService().refreshCredential(objectMapper.readTree("{\"musicid\":\"1\"}"))).isNull();
    }

    @Test
    void refreshesCredentialAndSavesExtend() throws IOException {
        Plugin qq = plugin("QQ音乐[音]",
                "{\"musicid\":\"123\",\"musickey\":\"OLD\",\"refresh_key\":\"RK\",\"refresh_token\":\"RT\"}");
        Plugin other = plugin("低端影视", "{\"cookie\":\"x\"}");
        when(pluginRepository.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(other, qq));
        QqMusicService service = spy(newService());
        doReturn("{\"musicid\":\"123\",\"musickey\":\"NEW\"}").when(service).refreshCredential(any(JsonNode.class));

        int refreshed = service.refreshAll();

        assertThat(refreshed).isEqualTo(1);
        ArgumentCaptor<Plugin> captor = ArgumentCaptor.forClass(Plugin.class);
        verify(pluginRepository).save(captor.capture());
        assertThat(captor.getValue().getExtend()).isEqualTo("{\"musicid\":\"123\",\"musickey\":\"NEW\"}");
    }

    @Test
    void skipsNonRefreshableCredential() throws IOException {
        Plugin qq = plugin("QQ音乐[音]", "{\"musicid\":\"123\",\"musickey\":\"MK\"}");
        when(pluginRepository.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(qq));
        QqMusicService service = spy(newService());

        assertThat(service.refreshAll()).isEqualTo(0);
        verify(service, never()).refreshCredential(any());
        verify(pluginRepository, never()).save(any());
    }

    @Test
    void skipsBlankOrInvalidExtend() throws IOException {
        Plugin blank = plugin("QQ音乐[音]", "");
        Plugin invalid = plugin("QQ音乐[音]", "not-json[]");
        when(pluginRepository.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(blank, invalid));
        QqMusicService service = spy(newService());

        assertThat(service.refreshAll()).isEqualTo(0);
        verify(service, never()).refreshCredential(any());
    }

    @Test
    void keepsOldExtendWhenRefreshFails() throws IOException {
        Plugin qq = plugin("QQ音乐[音]",
                "{\"musicid\":\"123\",\"musickey\":\"MK\",\"refresh_key\":\"RK\",\"refresh_token\":\"RT\"}");
        when(pluginRepository.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(qq));
        QqMusicService service = spy(newService());
        doReturn(null).when(service).refreshCredential(any(JsonNode.class));

        assertThat(service.refreshAll()).isEqualTo(0);
        verify(pluginRepository, never()).save(any());
    }

    @Test
    void matchesQqMusicPluginsByNameOrSourceName() {
        assertThat(QqMusicService.isQqMusicPlugin(plugin("QQ音乐[音]", null))).isTrue();
        assertThat(QqMusicService.isQqMusicPlugin(plugin("其他", null))).isFalse();
        Plugin bySource = plugin("自定义名", null);
        bySource.setSourceName("QQ音乐");
        assertThat(QqMusicService.isQqMusicPlugin(bySource)).isTrue();
        assertThat(QqMusicService.isQqMusicPlugin(null)).isFalse();
    }
}
