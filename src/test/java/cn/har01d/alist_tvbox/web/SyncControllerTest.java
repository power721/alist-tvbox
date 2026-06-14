package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.sync.*;
import cn.har01d.alist_tvbox.exception.VersionMismatchException;
import cn.har01d.alist_tvbox.service.sync.SyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)  // 禁用所有过滤器包括安全过滤器
class SyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SyncService syncService;

    @Test
    void testPush_Success() throws Exception {
        // Given
        SyncResponse response = new SyncResponse();
        response.setSuccess(true);
        Map<String, SyncResult> results = new HashMap<>();
        SyncResult result = new SyncResult();
        result.setImported(5);
        results.put("sites", result);
        response.setResults(results);

        when(syncService.push(anyString(), anyString(), anyString(), anyList(), anyBoolean()))
            .thenReturn(response);

        // When & Then
        mockMvc.perform(post("/api/sync/push")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "remoteUrl": "http://remote:4567",
                        "username": "admin",
                        "password": "password",
                        "modules": ["sites"],
                        "force": false
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.results.sites.imported").value(5));
    }

    @Test
    void testPush_VersionMismatch() throws Exception {
        // Given
        when(syncService.push(anyString(), anyString(), anyString(), anyList(), anyBoolean()))
            .thenThrow(new VersionMismatchException("1.0", "2.0"));

        // When & Then
        mockMvc.perform(post("/api/sync/push")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "remoteUrl": "http://remote:4567",
                        "username": "admin",
                        "password": "password",
                        "modules": ["sites"],
                        "force": false
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.results.version_error.failed").value(1))
            .andExpect(jsonPath("$.results.version_error.errors[0]").value(org.hamcrest.Matchers.containsString("VERSION_MISMATCH")));
    }

    @Test
    void testPull_VersionMismatch() throws Exception {
        // Given
        when(syncService.pull(anyString(), anyString(), anyString(), anyList(), any(MergeStrategy.class), anyBoolean()))
            .thenThrow(new VersionMismatchException("1.0", "2.0"));

        // When & Then
        mockMvc.perform(post("/api/sync/pull")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "remoteUrl": "http://remote:4567",
                        "username": "admin",
                        "password": "password",
                        "modules": ["sites"],
                        "strategy": "MERGE",
                        "force": false
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.results.version_error.failed").value(1))
            .andExpect(jsonPath("$.results.version_error.errors[0]").value(org.hamcrest.Matchers.containsString("VERSION_MISMATCH")));
    }

    @Test
    void testImportData_Success() throws Exception {
        // Given
        Map<String, SyncResult> results = new HashMap<>();
        SyncResult result = new SyncResult();
        result.setImported(3);
        result.setUpdated(2);
        results.put("sites", result);

        when(syncService.importData(any(SyncData.class), any(MergeStrategy.class), anyBoolean()))
            .thenReturn(results);

        // When & Then
        mockMvc.perform(post("/api/sync/import")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "data": {
                            "appVersion": "1.0",
                            "modules": {
                                "sites": []
                            }
                        },
                        "strategy": "MERGE",
                        "force": false
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.results.sites.imported").value(3))
            .andExpect(jsonPath("$.results.sites.updated").value(2));
    }

    @Test
    @WithMockUser
    void testExport_Success() throws Exception {
        // Given
        SyncData data = new SyncData();
        data.setAppVersion("1.0");

        when(syncService.exportData(anyList())).thenReturn(data);

        // When & Then
        mockMvc.perform(post("/api/sync/export")
                .with(csrf())
                .param("modules", "sites", "shares")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.appVersion").value("1.0"));
    }
}
