package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.SettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserControllerLocalBackupTest {
    @TempDir
    Path dataDir;

    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private cn.har01d.alist_tvbox.service.UserService userService;
    @Mock
    private SettingService settingService;

    private UserController controller;

    @BeforeEach
    void setUp() {
        System.setProperty("atv.data.dir", dataDir.toString());
        controller = new UserController(userService, passwordEncoder, settingService);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("atv.data.dir");
    }

    @Test
    void jsonBackupSucceedsWithValidToken() throws Exception {
        doNothing().when(settingService).backupJsonDatabase();
        Files.createDirectories(dataDir.resolve("atv"));
        Files.writeString(dataDir.resolve("atv").resolve("backup_token"), "tok");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-BACKUP-TOKEN", "tok");

        Map<String, String> result = controller.backupLocal(request, "json");
        assertTrue(result.get("file").startsWith("database-json-"));
        verify(settingService).backupJsonDatabase();
    }

    @Test
    void rejectsMissingToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThrows(BadRequestException.class, () -> controller.backupLocal(request, "json"));
    }

    @Test
    void rejectsWrongToken() throws Exception {
        Files.createDirectories(dataDir.resolve("atv"));
        Files.writeString(dataDir.resolve("atv").resolve("backup_token"), "tok");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-BACKUP-TOKEN", "wrong");

        assertThrows(BadRequestException.class, () -> controller.backupLocal(request, "json"));
    }
}
