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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BiliBiliController controller = new BiliBiliController(biliBiliService, subscriptionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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
