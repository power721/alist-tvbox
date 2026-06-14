package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.service.FeiniuService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FeiniuControllerTest {
    @Mock
    private FeiniuService feiniuService;
    @Mock
    private SubscriptionService subscriptionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FeiniuController controller = new FeiniuController(feiniuService, subscriptionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void browseShouldLoadCategoryByDefault() throws Exception {
        mockMvc.perform(get("/feiniu/test-token"))
                .andExpect(status().isOk());

        verify(subscriptionService).checkToken("test-token");
        verify(feiniuService).category(eq("test-token"), anyString());
    }

    @Test
    void playShouldDelegateToServiceWithoutProgress() throws Exception {
        mockMvc.perform(get("/feiniu-play/test-token").param("id", "1-155db03046e340909fd2659af90e2603"))
                .andExpect(status().isOk());

        verify(subscriptionService).checkToken("test-token");
        verify(feiniuService).play(eq("1-155db03046e340909fd2659af90e2603"), eq("test-token"), anyString());
    }

    @Test
    void imageProxyShouldDelegateToService() throws Exception {
        mockMvc.perform(get("/feiniu-img/test-token")
                        .param("site", "1")
                        .param("path", "/18/19/demo.webp"))
                .andExpect(status().isOk());

        verify(subscriptionService).checkToken("test-token");
        verify(feiniuService).proxyImage(eq(1), eq("/18/19/demo.webp"), anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
