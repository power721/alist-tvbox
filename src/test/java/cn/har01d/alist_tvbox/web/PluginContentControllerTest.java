package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.PluginService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PluginContentControllerTest {
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private PluginService pluginService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PluginContentController controller = new PluginContentController(subscriptionService, pluginService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void pythonContentShouldAuthenticateAndReturnCachedSource() throws Exception {
        when(pluginService.readContent(7)).thenReturn("class Spider:\n    pass\n");

        mockMvc.perform(get("/plugins/test-token/7.py"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/x-python;charset=UTF-8"))
                .andExpect(content().string("class Spider:\n    pass\n"));

        verify(subscriptionService).checkToken("test-token");
        verify(pluginService).readContent(7);
    }

    @Test
    void pythonContentShouldNotReadPluginWhenTokenIsRejected() throws Exception {
        doThrow(new BadRequestException("Token不正确"))
                .when(subscriptionService).checkToken("bad-token");

        mockMvc.perform(get("/plugins/bad-token/7.py"))
                .andExpect(status().isBadRequest());

        verify(pluginService, never()).readContent(7);
    }
}
