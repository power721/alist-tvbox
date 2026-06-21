package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.SettingService;
import cn.har01d.alist_tvbox.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalApiBackupTest {
    @TempDir
    Path dataDir;

    @Mock
    private UserService userService;
    @Mock
    private SettingService settingService;

    private LocalApiController controller;

    @BeforeEach
    void setUp() {
        System.setProperty("atv.data.dir", dataDir.toString());
        controller = new LocalApiController(userService, settingService);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("atv.data.dir");
    }

    @Test
    void jsonBackupSucceedsWithValidToken() throws Exception {
        java.io.File backupFile = new java.io.File("/data/backup/database-json-2026-06-20-124705.zip");
        when(settingService.backupJsonDatabase(false)).thenReturn(backupFile);
        Files.createDirectories(dataDir.resolve("atv"));
        Files.writeString(dataDir.resolve("atv").resolve("backup_token"), "tok");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-BACKUP-TOKEN", "tok");

        Map<String, String> result = controller.backupLocal(request, "json", false);
        assertTrue(result.get("file").startsWith("database-json-"));
        assertTrue(result.get("file").endsWith(".zip"));
        assertTrue(result.get("path").endsWith("database-json-2026-06-20-124705.zip"));
        verify(settingService).backupJsonDatabase(false);
    }

    @Test
    void jsonBackupIncludesDoubanWhenRequested() throws Exception {
        java.io.File backupFile = new java.io.File("/data/backup/database-json-2026-06-20-124705.zip");
        when(settingService.backupJsonDatabase(true)).thenReturn(backupFile);
        Files.createDirectories(dataDir.resolve("atv"));
        Files.writeString(dataDir.resolve("atv").resolve("backup_token"), "tok");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-BACKUP-TOKEN", "tok");

        controller.backupLocal(request, "json", true);
        // migration path passes includeDouban=true through to the JSON backup service
        verify(settingService).backupJsonDatabase(true);
    }

    @Test
    void rejectsMissingToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThrows(BadRequestException.class, () -> controller.backupLocal(request, "json", false));
    }

    @Test
    void rejectsWrongToken() throws Exception {
        Files.createDirectories(dataDir.resolve("atv"));
        Files.writeString(dataDir.resolve("atv").resolve("backup_token"), "tok");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-BACKUP-TOKEN", "wrong");

        assertThrows(BadRequestException.class, () -> controller.backupLocal(request, "json", false));
    }

    @Test
    void dbTestRejectsMissingToken() {
        // token guard is shared with backup; a missing token must be rejected before any JDBC attempt.
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThrows(BadRequestException.class, () ->
                controller.testDatabaseConnection(request, java.util.Map.of("url", "jdbc:h2:mem:x", "username", "sa", "password", "")));
    }
}
