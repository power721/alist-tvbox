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
}
