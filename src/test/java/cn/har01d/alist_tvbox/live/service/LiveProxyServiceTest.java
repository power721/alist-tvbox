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
        assertFalse(proxy.isDualProxyMode(), "默认应为全代理模式");
        properties.setLiveProxyMode("dual");
        assertTrue(proxy.isDualProxyMode(), "dual 配置应生效");
        properties.setLiveProxyMode("proxy");
        assertFalse(proxy.isDualProxyMode(), "回落 proxy 应生效");
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
}
