package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.ShareService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ShareControllerTest {
    @Mock
    private ShareService shareService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ShareController controller = new ShareController(shareService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void cookiesShouldReturnAllAvailableCredentials() throws Exception {
        ObjectNode result = objectMapper.createObjectNode();
        result.putObject("quark").put("cookie", "quark-cookie");
        ObjectNode baidu = result.putObject("baidu");
        baidu.put("cookie", "BDUSS=abc");
        baidu.put("refreshToken", "baidu-refresh");
        result.putObject("bili").put("cookie", "SESSDATA=abc");
        when(shareService.getCookies("secret")).thenReturn(result);

        mockMvc.perform(get("/cookies/secret"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "quark":{"cookie":"quark-cookie"},
                          "baidu":{"cookie":"BDUSS=abc","refreshToken":"baidu-refresh"},
                          "bili":{"cookie":"SESSDATA=abc"}
                        }
                        """));

        verify(shareService).getCookies("secret");
    }
}
