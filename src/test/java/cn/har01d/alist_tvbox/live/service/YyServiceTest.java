package cn.har01d.alist_tvbox.live.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * YY 取流双 id 纯函数:子频道号从 liveInfoDetail 的 ssid 字段派生(缺失回落房间号),
 * 代理续租参数 yys 解析。子频道房 cid≠sid,流接口双填房间号会取错流(pure_live _channelIds 契约)。
 */
class YyServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void subChannelIdPrefersValidSsid() throws Exception {
        var item = objectMapper.readTree("{\"sid\":\"1352910112\",\"ssid\":\"1829524111\"}");
        assertEquals("1829524111", YyService.subChannelId("1352910112", item));
    }

    @Test
    void subChannelIdFallsBackToRoomId() throws Exception {
        // 单频道房无 ssid/ssid 非数字/空对象/缺节点:一律回落房间号(存量行为)
        assertEquals("1352910112", YyService.subChannelId("1352910112", objectMapper.readTree("{\"sid\":\"1352910112\"}")));
        assertEquals("1352910112", YyService.subChannelId("1352910112", objectMapper.readTree("{\"ssid\":\"\"}")));
        assertEquals("1352910112", YyService.subChannelId("1352910112", objectMapper.readTree("{\"ssid\":\"abc\"}")));
        assertEquals("1352910112", YyService.subChannelId("1352910112", objectMapper.readTree("{}")));
        assertEquals("1352910112", YyService.subChannelId("1352910112", objectMapper.missingNode()));
    }

    @Test
    void proxyRenewResolvesSubChannelParam() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/live-proxy/tok");
        request.setParameter("yy", "1352910112");
        assertEquals("1352910112", LiveProxyService.yySubChannelId(request, "1352910112"), "无 yys 回落房间号");
        request.setParameter("yys", "1829524111");
        assertEquals("1829524111", LiveProxyService.yySubChannelId(request, "1352910112"));
        request.setParameter("yys", "abc");
        assertEquals("1352910112", LiveProxyService.yySubChannelId(request, "1352910112"), "非法 yys 回落房间号");
    }
}
