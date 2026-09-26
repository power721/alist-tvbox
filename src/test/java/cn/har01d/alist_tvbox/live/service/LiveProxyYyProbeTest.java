package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * YY 代理续租端到端探针(诊断用,默认不跑):
 * 真实走 LiveProxyService YY 分支——HLS 清单请求每次重取房间当前清单(renewHlsUrl),
 * 分片地址重写为代理地址;FLV 分支与酷狗/映客共用 proxyWithRenew 生产路径。
 * <pre>
 * mvn test -Dtest=LiveProxyYyProbeTest -Dlive.probe=1
 * </pre>
 */
@EnabledIfSystemProperty(named = "live.probe", matches = "1")
class LiveProxyYyProbeTest {
    private final RestTemplateBuilder builder = new RestTemplateBuilder();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void proxyManifestRenewsAndRewritesSegments() throws IOException {
        YyService yyService = new YyService(builder, objectMapper, null);
        // 直链 detail 取一个在播房间的 HLS 清单地址(探针同款降级:无代理实例时保留原始地址)
        var home = yyService.home();
        assertTrue(!home.getList().isEmpty(), "YY首页无房间,无法测试");
        String roomId = home.getList().get(0).getVod_id().split("\\$")[1];
        String manifest = yyService.renewHlsUrl(roomId, "4000");
        assertTrue(manifest != null && manifest.contains(".m3u8"), "HLS清单重取失败: " + manifest);
        System.out.printf("[yy-proxy] room=%s manifest=%s%n", roomId, abbreviate(manifest, 100));

        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        when(subscriptionService.getCurrentToken()).thenReturn("probe-token");
        LiveProxyService proxyService = new LiveProxyService(subscriptionService, new AppProperties(),
                null, null, null, fixedProvider(yyService));

        // 模拟代理请求:清单目标地址 + yy/yyr 续租参数(条目生成端同款拼法)
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/live-proxy/probe-token");
        request.setServerName("localhost");
        request.setParameter("u", manifest);
        request.setParameter("yy", roomId);
        request.setParameter("yyr", "4000");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MockHttpServletResponse response = new MockHttpServletResponse();

        proxyService.proxy(manifest, request, response);

        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        System.out.printf("[yy-proxy] status=%d bytes=%d content-type=%s%n",
                response.getStatus(), response.getContentAsByteArray().length, response.getContentType());
        assertTrue(body.startsWith("#EXTM3U"), "代理清单应为 m3u8: " + abbreviate(body, 80));
        // 分片行已被重写为本服务代理地址(带 yy 参数的清单请求重取新地址,分片带独立 tk 签名即刻有效)
        assertTrue(body.contains("/live-proxy/probe-token?"), "分片地址未重写为代理地址: " + abbreviate(body, 200));
        String segmentLine = java.util.Arrays.stream(body.split("\n"))
                .map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).findFirst().orElse("");
        System.out.printf("[yy-proxy] rewritten segment=%s%n", abbreviate(segmentLine, 140));
    }

    @Test
    void proxyFlvRenewProducesPlayableUrl() throws IOException {
        YyService yyService = new YyService(builder, objectMapper, null);
        var home = yyService.home();
        assertTrue(!home.getList().isEmpty(), "YY首页无房间,无法测试");
        String roomId = home.getList().get(0).getVod_id().split("\\$")[1];
        // FLV 续租入口(代理端 proxyWithRenew 的 renewer 同款调用):产出地址应可直接拉流
        String flv = yyService.renewStreamUrl(roomId, "1");
        assertTrue(flv != null && flv.contains(".flv"), "FLV续租失败: " + flv);
        System.out.printf("[yy-proxy] flv renewed=%s%n", abbreviate(flv, 120));

        // 实际拉流 3 秒验证续租产物可播
        Process curl = new ProcessBuilder("curl", "-s", "--max-time", "3", "-o", "/dev/null",
                "-w", "%{http_code} %{size_download}", flv).start();
        String result = new String(curl.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        System.out.printf("[yy-proxy] flv pull=%s%n", result);
        assertTrue(result.startsWith("200"), "FLV续租地址不可播: " + result);
    }

    @Test
    void dualProxyModeProducesDirectAndProxyLines() throws IOException {
        // dual 模式:detail 应产出「直连$$$代理」双线路,直连线路为平台 CDN 原始地址(零服务器带宽),
        // 代理线路带 yy 续租参数,直连失败由播放器自动切换(FongMi VodFallbackPolicy.fallbackToNextLine)
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        when(subscriptionService.getCurrentToken()).thenReturn("probe-token");
        AppProperties properties = new AppProperties();
        properties.setLiveProxyMode("dual");
        LiveProxyService proxyService = new LiveProxyService(subscriptionService, properties, null, null, null, null);
        assertTrue(proxyService.isDualProxyMode(), "dual 模式未生效");
        YyService yyService = new YyService(builder, objectMapper, proxyService);

        var home = yyService.home();
        assertTrue(!home.getList().isEmpty(), "YY首页无房间,无法测试");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/live/yy");
        request.setServerName("localhost");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        var detail = yyService.detail(home.getList().get(0).getVod_id(), null).getList().get(0);
        System.out.printf("[yy-dual] playFrom=%s%n", detail.getVod_play_from());
        System.out.printf("[yy-dual] playUrl=%s%n", abbreviate(detail.getVod_play_url() == null ? "" : detail.getVod_play_url(), 200));
        assertEquals("直连优先$$$代理", detail.getVod_play_from(), "dual 模式应为双线路(直连优先+代理)");
        String[] lines = detail.getVod_play_url().split("\\$\\$\\$");
        assertEquals(2, lines.length, "应有两条线路");
        // 线路1=同档「直连,代理」交错分集:OK 影视类内核直连失败切下一集落到同档代理条目
        String[] episodes = lines[0].split("#");
        assertTrue(episodes.length >= 2, "线路1应有多条分集条目");
        assertTrue(episodes[0].contains("·直连$") && episodes[0].contains("yy.com"), "首条应为直连: " + episodes[0]);
        assertFalse(episodes[0].contains("live-proxy"), "直连条目不应是代理地址");
        assertTrue(episodes[1].contains("·代理$") && episodes[1].contains("/live-proxy/probe-token?") && episodes[1].contains("&yy="),
                "第二条应为同档代理条目: " + episodes[1]);
        // 线路2=纯代理全档:FongMi 类内核错误自动切线路(fallbackToNextLine)落到该线路
        assertFalse(lines[1].contains("·直连"), "线路2应为纯代理: " + abbreviate(lines[1], 80));
        assertTrue(lines[1].contains("/live-proxy/probe-token?"), "线路2应包代理: " + abbreviate(lines[1], 80));
        // 网页端恒走代理(浏览器直连受 CORS 限制):client=web 不出交错分集
        var webDetail = yyService.detail(home.getList().get(0).getVod_id(), "web").getList().get(0);
        assertEquals("线路1", webDetail.getVod_play_from());
        assertFalse(webDetail.getVod_play_url().contains("·直连"), "网页端应为纯代理条目");
        assertTrue(webDetail.getVod_play_url().contains("/live-proxy/"), "网页端应为代理地址");
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    /** 固定实例的 ObjectProvider(生产环境由 Spring 注入,此处包真实 YyService 供代理续租分支调用)。 */
    private static <T> org.springframework.beans.factory.ObjectProvider<T> fixedProvider(T instance) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getObject() {
                return instance;
            }
        };
    }
}
