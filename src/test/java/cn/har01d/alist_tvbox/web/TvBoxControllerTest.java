package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.entity.DeviceRepository;
import cn.har01d.alist_tvbox.service.HistoryService;
import cn.har01d.alist_tvbox.service.SettingService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TvBoxControllerTest {
    @Mock
    private TvBoxService tvBoxService;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private HistoryService historyService;
    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private SettingService settingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TvBoxController controller = new TvBoxController(
                tvBoxService,
                subscriptionService,
                historyService,
                deviceRepository,
                new ObjectMapper(),
                settingService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void openShouldReturnConfig() throws Exception {
        when(subscriptionService.open()).thenReturn(Map.of("video", "x"));

        mockMvc.perform(get("/open"))
                .andExpect(status().isOk());
    }

    @Test
    void m3u8ShouldReadIdQueryParameter() throws Exception {
        when(tvBoxService.m3u8("1$2$3")).thenReturn("#EXTM3U");

        mockMvc.perform(get("/m3u8/test-token").param("id", "1$2$3"))
                .andExpect(status().isOk())
                .andExpect(content().string("#EXTM3U"));

        verify(subscriptionService).checkToken("test-token");
        verify(tvBoxService).m3u8("1$2$3");
    }
}
