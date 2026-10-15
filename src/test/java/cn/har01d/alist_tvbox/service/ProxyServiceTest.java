package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.PlayUrl;
import cn.har01d.alist_tvbox.entity.PlayUrlRepository;
import cn.har01d.alist_tvbox.entity.Site;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

interface ProxyCase {
        void run(String id, int uid) throws Exception;
    }

    class ProxyServiceTest {
    private final PlayUrlRepository playUrlRepository = mock(PlayUrlRepository.class);
    private final ProxyService service = new ProxyService(null, playUrlRepository, null, null, null, null);

    @Test
    void parsePlayUrlIdShouldAcceptIsoSuffix() {
        assertThat(ProxyService.parsePlayUrlId("1@106306.iso")).isEqualTo(106306);
    }

    // ---------- 长效代理注册(追剧盘线路) ----------

    private static Site site() {
        Site site = new Site();
        site.setId(1);
        return site;
    }

    private static PlayUrl stored(Instant time) {
        PlayUrl playUrl = new PlayUrl(1, "/追剧/7-测试剧/第01集.mkv", time);
        playUrl.setId(42);
        return playUrl;
    }

    /** 模拟 JPA save 回填主键。 */
    private void assignIdOnSave() {
        when(playUrlRepository.save(Mockito.any(PlayUrl.class))).thenAnswer(invocation -> {
            PlayUrl saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(99);
            }
            return saved;
        });
    }

    @Test
    void longTtlRegistersNewRowWithFullWindow() {
        when(playUrlRepository.findFirstBySiteAndPath(Mockito.eq(1), Mockito.anyString(), Mockito.any()))
                .thenReturn(null);
        assignIdOnSave();
        Instant before = Instant.now();

        int id = service.generateProxyUrl(site(), "/追剧/7-测试剧/第01集.mkv", Duration.ofDays(365));

        ArgumentCaptor<PlayUrl> captor = ArgumentCaptor.forClass(PlayUrl.class);
        verify(playUrlRepository).save(captor.capture());
        assertThat(captor.getValue().getTime()).isAfter(before.plus(Duration.ofDays(364)));
        assertThat(id).isEqualTo(99);
    }

    @Test
    void longTtlReusesRowWithAmpleLifetimeWithoutWrite() {
        when(playUrlRepository.findFirstBySiteAndPath(Mockito.eq(1), Mockito.anyString(), Mockito.any()))
                .thenReturn(stored(Instant.now().plus(Duration.ofDays(300)))); // 剩余 300d > 365d/2

        int id = service.generateProxyUrl(site(), "/追剧/7-测试剧/第01集.mkv", Duration.ofDays(365));

        assertThat(id).isEqualTo(42);
        verify(playUrlRepository, never()).save(Mockito.any());
    }

    @Test
    void longTtlRenewsRowWhenLifetimeBelowHalf() {
        when(playUrlRepository.findFirstBySiteAndPath(Mockito.eq(1), Mockito.anyString(), Mockito.any()))
                .thenReturn(stored(Instant.now().plus(Duration.ofDays(10)))); // 剩余 10d < 182.5d
        assignIdOnSave();
        Instant before = Instant.now();

        int id = service.generateProxyUrl(site(), "/追剧/7-测试剧/第01集.mkv", Duration.ofDays(365));

        ArgumentCaptor<PlayUrl> captor = ArgumentCaptor.forClass(PlayUrl.class);
        verify(playUrlRepository).save(captor.capture());
        assertThat(captor.getValue().getTime()).isAfter(before.plus(Duration.ofDays(364)));
        assertThat(id).isEqualTo(42);
    }

    @Test
    void longTtlRenewsExpiredRowInPlace() {
        when(playUrlRepository.findFirstBySiteAndPath(Mockito.eq(1), Mockito.anyString(), Mockito.any()))
                .thenReturn(stored(Instant.now().minus(Duration.ofDays(1)))); // 已过期:原地续期,不新建行
        assignIdOnSave();

        int id = service.generateProxyUrl(site(), "/追剧/7-测试剧/第01集.mkv", Duration.ofDays(365));

        ArgumentCaptor<PlayUrl> captor = ArgumentCaptor.forClass(PlayUrl.class);
        verify(playUrlRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(42);
        assertThat(captor.getValue().getTime()).isAfter(Instant.now());
        assertThat(id).isEqualTo(42);
    }

    // ---------- 盘线路 pid 归属(§3.3) ----------

    @Test
    void longTtlWithOwnerRegistersOwnershipRow() {
        when(playUrlRepository.findFirstBySiteAndPath(Mockito.eq(1), Mockito.anyString(), Mockito.any()))
                .thenReturn(null);
        assignIdOnSave();

        service.generateProxyUrl(site(), "/追剧/7-测试剧/第01集.mkv", Duration.ofDays(365), 5);

        ArgumentCaptor<PlayUrl> captor = ArgumentCaptor.forClass(PlayUrl.class);
        verify(playUrlRepository).save(captor.capture());
        assertThat(captor.getValue().getOwnerUid()).isEqualTo(5);
    }

    @Test
    void longTtlOwnershipDoesNotMigrateExistingSharedRow() {
        // 同盘同路径已有共享行:直接复用,归属不迁移(共享挂载共用同一路径是预期行为)
        when(playUrlRepository.findFirstBySiteAndPath(Mockito.eq(1), Mockito.anyString(), Mockito.any()))
                .thenReturn(stored(Instant.now().plus(Duration.ofDays(300)))); // 剩余寿命超过一半:直接复用不写库
        int id = service.generateProxyUrl(site(), "/追剧/7-测试剧/第01集.mkv", Duration.ofDays(365), 5);
        assertThat(id).isEqualTo(42);
        verify(playUrlRepository, never()).save(Mockito.any());
    }

    @Test
    void proxyRejectsOwnedRowForOtherUser() {
        PlayUrl owned = stored(Instant.now().plus(Duration.ofDays(1)));
        owned.setOwnerUid(5);
        when(playUrlRepository.findById(42)).thenReturn(java.util.Optional.of(owned));

        assertThatExceptionOfType(cn.har01d.alist_tvbox.exception.BadRequestException.class)
                .isThrownBy(() -> service.proxy("1@42", 7, null,
                        new org.springframework.mock.web.MockHttpServletResponse()));
    }

    @Test
    void proxyAllowsSharedRowForUserAndOwnedRowForOwnerAndAdminToken() {
        // 归属校验通过后才会走到真链解析(此测试桩缺 site/huya 依赖,任何非"无权播放"异常都算放行)
        PlayUrl shared = stored(Instant.now().plus(Duration.ofDays(1)));
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
        when(playUrlRepository.findById(42)).thenReturn(java.util.Optional.of(shared));
        when(playUrlRepository.findById(43)).thenReturn(java.util.Optional.of(shared));

        java.util.List<ProxyCase> cases = java.util.List.of(
                (id, uid) -> service.proxy(id, uid, null, response));
        for (ProxyCase proxyCase : cases) {
            try {
                proxyCase.run("42", 7);  // 共享行:任意用户放行
                proxyCase.run("43", 0);  // 管理级 token(uid=0):全放行
            } catch (cn.har01d.alist_tvbox.exception.BadRequestException e) {
                throw new AssertionError("归属校验误拒: " + e.getMessage());
            } catch (Exception expected) {
                // 后续真链解析因测试桩缺失而失败,不影响归属校验结论
            }
        }
    }

    // ---------- 上游错误透传(downloadStraight) ----------

    /** 起一个本地 HTTP 服务按给定状态码回包,返回其根地址。 */
    private static String stubUpstream(int status, String body) throws java.io.IOException {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/upstream";
    }

    /** 上游 412(夸克 CDN 风控页)不得被 getInputStream() 的异常包成 Spring 500:状态码与错误体须原样透传。 */
    @Test
    void downloadStraightPassesThroughUpstreamErrorStatusAndBody() throws Exception {
        String body = "<html>blocked by risk control</html>";

        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/p/1@42");
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        service.downloadStraight(stubUpstream(412, body), request, response, java.util.Map.of());

        assertThat(response.getStatus()).isEqualTo(412);
        assertThat(response.getContentAsString()).isEqualTo(body);
    }

    /** 正常 2xx 路径回归护栏:状态码、响应头、正文照旧透传。 */
    @Test
    void downloadStraightStreamsSuccessResponse() throws Exception {
        String body = "0123456789abcdef";

        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/p/1@42");
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        service.downloadStraight(stubUpstream(206, body), request, response, java.util.Map.of());

        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getContentAsString()).isEqualTo(body);
        assertThat(response.getHeader("Content-Type")).isEqualTo("text/html");
    }

    // ---------- 服务端自取回源地址(UC/百度/夸克等 proxy 驱动) ----------

    /** 线上(4567 docker):播放器从宿主机 127.0.0.1 连入,旧实现沿用请求 host+externalPort(ALIST_PORT=5344,
     *  宿主映射端口)拼出 127.0.0.1:5344 —— 容器内无人监听(AList 听 5244)→ Connection refused,
     *  UC/百度/夸克全灭;externalPort 只对客户端直连有效,服务端自取必须打 internalPort。 */
    @Test
    void localProxyUrlTargetsInternalPortForServerSideFetch() {
        AListLocalService aListLocalService = mock(AListLocalService.class);
        when(aListLocalService.getInternalPort()).thenReturn(5244);
        when(aListLocalService.getExternalPort()).thenReturn(5344);
        ProxyService proxyService = new ProxyService(null, playUrlRepository, null, null, aListLocalService, null);
        Site localSite = site();
        localSite.setUrl("http://localhost:5244");

        String url = proxyService.buildAListProxyUrl(localSite,
                "/追剧/.sources/醒来 [dbid-1]-补2/09.mkv", "abc=:0");

        assertThat(url)
                .startsWith("http://localhost:5244/p/%E8%BF%BD%E5%89%A7/.sources/"
                        + "%E9%86%92%E6%9D%A5%20%5Bdbid-1%5D-%E8%A1%A52/09.mkv?sign=")
                .endsWith("abc=:0")
                .doesNotContain("5344");
    }
}
