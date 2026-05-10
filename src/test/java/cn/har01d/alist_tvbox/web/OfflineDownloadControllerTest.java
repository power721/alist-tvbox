package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigDto;
import cn.har01d.alist_tvbox.dto.OfflineDownloadQuotaResponse;
import cn.har01d.alist_tvbox.service.OfflineDownloadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OfflineDownloadControllerTest {
    @Mock
    private OfflineDownloadService offlineDownloadService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OfflineDownloadController controller = new OfflineDownloadController(offlineDownloadService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void shouldGetOfflineDownloadConfig() throws Exception {
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(true, "PAN115", 12, "/115云盘/测试"));

        mockMvc.perform(get("/api/offline_download/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.driverType").value("PAN115"))
                .andExpect(jsonPath("$.accountId").value(12))
                .andExpect(jsonPath("$.folder").value("/115云盘/测试"));
    }

    @Test
    void shouldGetOfflineDownloadQuota() throws Exception {
        when(offlineDownloadService.getQuota()).thenReturn(new OfflineDownloadQuotaResponse(1371, 1500, 129));

        mockMvc.perform(get("/api/offline_download/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.surplus").value(1371))
                .andExpect(jsonPath("$.count").value(1500))
                .andExpect(jsonPath("$.used").value(129));
    }

    @Test
    void shouldSaveOfflineDownloadConfig() throws Exception {
        mockMvc.perform(post("/api/offline_download/config")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"driverType":"PAN115","accountId":12}
                                """))
                .andExpect(status().isOk());

        verify(offlineDownloadService).saveConfig(any());
    }
}
