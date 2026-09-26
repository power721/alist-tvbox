package cn.har01d.alist_tvbox.live.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 酷狗续租代理的纯解析逻辑:流地址 host 判定与 token=0-{roomId}- 房间号反解。
 */
class LiveProxyServiceTest {
    private static final String STREAM_URL = "https://tx105.liveplay.live.kugou.com/live/fx_hifi_123.flv"
            + "?cn=fx&ua=fx-flash&fx-ps=5-0-1790382110127&txSecret=abc&txTime=6AB7B8DE"
            + "&token=0-3960835-0-1010-7-1000-6ab7101e-6b99b2cf5f15f24385ff63209451c844";

    @Test
    void recognizesKugouMediaHostOnly() {
        assertTrue(LiveProxyService.isKugouStream(STREAM_URL));
        assertTrue(LiveProxyService.isKugouStream("https://liveplay.live.kugou.com/live/x.m3u8?token=0-1-"));
        // 相似域名/其他 CDN 不误判
        assertFalse(LiveProxyService.isKugouStream("https://liveplay.live.kugou.com.evil.io/live/x.flv?token=0-1-"));
        assertFalse(LiveProxyService.isKugouStream("https://huosa.douyucdn2.cn/live/9999.flv?wsAuth=x"));
        assertFalse(LiveProxyService.isKugouStream("::::"));
    }

    @Test
    void extractsRoomIdFromTokenPrefix() {
        assertEquals("3960835", LiveProxyService.kugouRoomId(STREAM_URL));
        // 无 token/畸形 token 返回 null,续租自然跳过
        assertNull(LiveProxyService.kugouRoomId("https://tx105.liveplay.live.kugou.com/live/a.flv"));
        assertNull(LiveProxyService.kugouRoomId("https://tx105.liveplay.live.kugou.com/live/a.flv?token=0"));
    }

    @Test
    void recognizesYyMediaHostOnly() {
        // YY 流域名:stream-manager 产出的 FLV CDN 与匿名 HLS 清单(4443 端口)
        assertTrue(LiveProxyService.isYyStream("https://tx-flv-web.yy.com/live/15013_xv_1_1_0.flv?codec=orig&t=1"));
        assertTrue(LiveProxyService.isYyStream("https://sslproxy.yy.com:4443/livesystem/15013_xv_1_1_0.m3u8?org=yyweb"));
        assertTrue(LiveProxyService.isYyStream("https://sslproxy.yy.com:4443/livesystem/6076_404.ts?org=yyweb&tk=x"));
        // 前缀伪装域名/其他 CDN 不误判
        assertFalse(LiveProxyService.isYyStream("https://yy.com.evil.io/live/x.flv"));
        assertFalse(LiveProxyService.isYyStream("https://notyy.com/live/x.flv"));
        assertFalse(LiveProxyService.isYyStream("https://tx-flv-web.yy.com.evil.io/live/x.flv"));
        assertFalse(LiveProxyService.isYyStream("::::"));
    }

    @Test
    void dualProxyModeFollowsConfiguredSetting() {
        cn.har01d.alist_tvbox.config.AppProperties properties = new cn.har01d.alist_tvbox.config.AppProperties();
        cn.har01d.alist_tvbox.service.SubscriptionService subscriptionService = org.mockito.Mockito.mock(cn.har01d.alist_tvbox.service.SubscriptionService.class);
        LiveProxyService proxy = new LiveProxyService(subscriptionService, properties, null, null, null, null);
        assertTrue(proxy.isDualProxyMode(), "默认应为直连优先模式(dual)");
        properties.setLiveProxyMode("proxy");
        assertFalse(proxy.isDualProxyMode(), "显式全代理配置应生效");
        properties.setLiveProxyMode("dual");
        assertTrue(proxy.isDualProxyMode(), "切回直连优先应生效");
    }

    @Test
    void buildPlayLinesProducesDualOrSingleLines() {
        LivePlatform platform = new LivePlatform() {
            @Override
            public String getType() {
                return "test";
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public cn.har01d.alist_tvbox.tvbox.MovieList home() {
                return null;
            }

            @Override
            public cn.har01d.alist_tvbox.tvbox.CategoryList category() {
                return null;
            }

            @Override
            public cn.har01d.alist_tvbox.tvbox.MovieList list(String id, String ac, String sort, Integer pg) {
                return null;
            }

            @Override
            public cn.har01d.alist_tvbox.tvbox.MovieList search(String wd) {
                return null;
            }

            @Override
            public cn.har01d.alist_tvbox.tvbox.MovieList detail(String tid, String client) {
                return null;
            }
        };
        java.util.List<String> direct = java.util.List.of("蓝光$https://cdn.example/1.flv", "流畅$https://cdn.example/2.flv");
        java.util.List<String> proxy = java.util.List.of("蓝光$http://h/live-proxy?u=1", "流畅$http://h/live-proxy?u=2");

        // dual=双线路:线路1「直连优先」同档直连/代理交错分集(OK 影视切下一集兜底),
        // 线路2「代理」纯代理全档(FongMi 错误切线路兜底),标签带后缀
        String[] dual = platform.buildPlayLines(direct, proxy, "dual");
        assertEquals("直连优先$$$代理", dual[0]);
        assertEquals("蓝光·直连$https://cdn.example/1.flv"
                + "#蓝光·代理$http://h/live-proxy?u=1"
                + "#流畅·直连$https://cdn.example/2.flv"
                + "#流畅·代理$http://h/live-proxy?u=2"
                + "$$$蓝光$http://h/live-proxy?u=1#流畅$http://h/live-proxy?u=2", dual[1]);

        // proxy 模式=单线路全代理
        String[] proxied = platform.buildPlayLines(direct, proxy, "proxy");
        assertEquals("线路1", proxied[0]);
        assertEquals("蓝光$http://h/live-proxy?u=1#流畅$http://h/live-proxy?u=2", proxied[1]);

        // 代理条目缺失(探针/降级)回落直连单线路
        String[] fallback = platform.buildPlayLines(direct, java.util.List.of(), "proxy");
        assertEquals("线路1", fallback[0]);
        assertTrue(fallback[1].startsWith("蓝光$https://cdn.example"), "应回落直连条目");
    }

    /**
     * 复现线上形态:CDN 对单连接寿命截断可以是优雅关闭(FIN)——transferTo 正常返回不抛异常,
     * 旧版把 EOF 当"直播结束"直接终止(实测 23 分钟停止且无任何 warn)。断言:上游两段流各自
     * 正常 EOF 后,续租被触发并续写(重连剥 13 字节 FLV 头),renewer 返回 null(下播)才终止。
     */
    @Test
    void flvUpstreamGracefulEofStillRenews() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        byte[] chunk = new byte[8192];
        new java.security.SecureRandom().nextBytes(chunk);
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/a.flv", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(chunk);
            exchange.getResponseBody().close();
        });
        server.createContext("/b.flv", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(chunk);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            cn.har01d.alist_tvbox.service.SubscriptionService subscriptionService =
                    org.mockito.Mockito.mock(cn.har01d.alist_tvbox.service.SubscriptionService.class);
            LiveProxyService proxy = new LiveProxyService(subscriptionService,
                    new cn.har01d.alist_tvbox.config.AppProperties(), null, null, null, null);
            org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
            java.util.List<String> renewed = java.util.List.of(base + "/b.flv");

            proxy.proxyWithRenew(base + "/a.flv", response, "https://fanxing.kugou.com/",
                    () -> hits.incrementAndGet() <= renewed.size() ? renewed.get(hits.get() - 1) : null);

            // 两次上游 EOF:第一次续租换 b.flv 续写(剥 13 字节重发头),第二次 renewer 返回 null 终止
            assertEquals(2, hits.get(), "EOF 后应触发续租复核,下播才终止");
            assertEquals(chunk.length + chunk.length - 13, response.getContentAsByteArray().length,
                    "续写应剥掉重连的 13 字节 FLV 头");
            assertEquals("video/x-flv", response.getContentType());
        } finally {
            server.stop(0);
        }
    }
}
