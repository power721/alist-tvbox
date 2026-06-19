package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreResponse;
import cn.har01d.alist_tvbox.service.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SettingControllerBackupTest {
    private MockMvc mockMvc;
    private SettingService settingService;

    @BeforeEach
    void setUp() throws Exception {
        settingService = Mockito.mock(SettingService.class);
        File temp = File.createTempFile("backup-", ".zip");
        Mockito.when(settingService.exportYamlDatabase()).thenReturn(new FileSystemResource(temp));
        Mockito.when(settingService.importYamlDatabase(any(), eq(BackupRestoreMode.OVERWRITE)))
            .thenReturn(new BackupRestoreResponse());
        mockMvc = MockMvcBuilders.standaloneSetup(new SettingController(settingService, null)).build();
    }

    @Test
    void shouldDownloadYamlZip() throws Exception {
        mockMvc.perform(get("/api/settings/export-yaml"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("database-yaml-")));
    }

    @Test
    void shouldAcceptYamlZipUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "database-yaml.zip", "application/zip", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/settings/import-yaml")
                .file(file)
                .param("mode", "OVERWRITE")
                .contentType(MediaType.MULTIPART_FORM_DATA))
            .andExpect(status().isOk());
    }
}
