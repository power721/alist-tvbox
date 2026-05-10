package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.dto.OfflineDownloadRequest;
import cn.har01d.alist_tvbox.service.OfflineDownloadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

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
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadService.ConfigResponse(true, "PAN115", 12, "/115云盘/测试"));

        mockMvc.perform(get("/api/offline_download/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.driverType").value("PAN115"))
                .andExpect(jsonPath("$.accountId").value(12))
                .andExpect(jsonPath("$.folder").value("/115云盘/测试"));
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

    @Test
    void shouldReturnPlaylistForOfflineDownload() throws Exception {
        when(offlineDownloadService.download(any(OfflineDownloadRequest.class))).thenReturn((Map<String, Object>) (Map<?, ?>) Map.of(
                "page", 1,
                "pagecount", 1,
                "limit", 100,
                "total", 1,
                "list", java.util.List.of(Map.of("vod_id", "1"))
        ));

        mockMvc.perform(post("/api/offline_download")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"url":"magnet:?xt=urn:btih:test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].vod_id").value("1"));
    }
}
