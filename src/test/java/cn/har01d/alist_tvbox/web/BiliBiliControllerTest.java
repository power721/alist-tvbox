package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.BiliBiliService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BiliBiliControllerTest {
    @Mock
    private BiliBiliService biliBiliService;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private cn.har01d.alist_tvbox.live.service.LiveProxyService liveProxyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BiliBiliController controller = new BiliBiliController(biliBiliService, subscriptionService, liveProxyService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void proxyResolvesDurlAndDelegatesToStreamProxy() throws Exception {
        // T4 短代理:playId 实时解析单文件 durl 后交通用流代理(代理端补 Referer、Range 透传)
        when(biliBiliService.resolvePlayUrlForProxy("BV1u1no6hEiP"))
                .thenReturn("https://upos-sz-mirrorcos.bilivideo.com/upos/x.flv?e=exp");
        org.springframework.mock.web.MockHttpServletResponse resp =
                new org.springframework.mock.web.MockHttpServletResponse();
        var req = new org.springframework.mock.web.MockHttpServletRequest("GET", "/proxy/tok/BV1u1no6hEiP");
        req.setRequestURI("/proxy/tok/BV1u1no6hEiP");

        new BiliBiliController(biliBiliService, subscriptionService, liveProxyService)
                .proxy("tok", "BV1u1no6hEiP", req, resp);

        verify(biliBiliService).resolvePlayUrlForProxy("BV1u1no6hEiP");
        org.mockito.Mockito.verify(liveProxyService).proxy(
                org.mockito.ArgumentMatchers.eq("https://upos-sz-mirrorcos.bilivideo.com/upos/x.flv?e=exp"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void actionShouldDelegateToService() throws Exception {
        when(biliBiliService.runAction("116958703918865-40168587741", "like"))
                .thenReturn(Map.of("actions", java.util.List.of()));

        mockMvc.perform(post("/bilibili/tok/action")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"116958703918865-40168587741\",\"action\":\"like\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"actions\":[]}"));

        verify(subscriptionService).checkToken("tok");
        verify(biliBiliService).runAction("116958703918865-40168587741", "like");
    }

    @Test
    void refreshCookieShouldDelegateToService() throws Exception {
        when(biliBiliService.refreshCookie()).thenReturn(Map.of("isLogin", true));

        mockMvc.perform(post("/api/bilibili/refresh"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"isLogin\":true}"));

        verify(biliBiliService).refreshCookie();
    }
}
