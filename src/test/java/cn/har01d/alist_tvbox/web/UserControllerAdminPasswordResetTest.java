package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.auth.TokenService;
import cn.har01d.alist_tvbox.entity.SessionRepository;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.entity.UserRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerAdminPasswordResetTest {
    @TempDir
    Path dataDir;

    @Mock
    private UserRepository userRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenService tokenService;
    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private UserController userController;

    @BeforeEach
    void setUp() {
        System.setProperty("atv.data.dir", dataDir.toString());
        UserService userService = new UserService(userRepository, sessionRepository, passwordEncoder, tokenService,
            new cn.har01d.alist_tvbox.service.backup.RestoreState("/data/does-not-exist-database-yaml.zip"),
            jdbcTemplate);
        userController = new UserController(userService, passwordEncoder);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("atv.data.dir");
    }

    @Test
    void resetAdminPasswordShouldReturnNewPasswordWithValidToken() {
        User admin = new User();
        admin.setId(1);
        admin.setUsername("admin");
        admin.setPassword("old");

        when(userRepository.findById(1)).thenReturn(java.util.Optional.of(admin));
        when(passwordEncoder.encode(any())).thenAnswer(invocation -> "encoded-" + invocation.getArgument(0));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-ADMIN-RESET-TOKEN", "reset-token");

        try {
            java.nio.file.Files.createDirectories(dataDir.resolve("atv"));
            java.nio.file.Files.writeString(dataDir.resolve("atv").resolve("admin_reset_token"), "reset-token");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Map<String, String> response = userController.resetAdminPassword(request);

        assertEquals("admin", response.get("username"));
        assertEquals(12, response.get("password").length());
        assertFalse(java.nio.file.Files.exists(dataDir.resolve("atv").resolve("admin_reset_token")));
    }

    @Test
    void resetAdminPasswordShouldRejectInvalidToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-ADMIN-RESET-TOKEN", "bad-token");

        try {
            java.nio.file.Files.createDirectories(dataDir.resolve("atv"));
            java.nio.file.Files.writeString(dataDir.resolve("atv").resolve("admin_reset_token"), "reset-token");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThrows(BadRequestException.class, () -> userController.resetAdminPassword(request));
        assertTrue(java.nio.file.Files.exists(dataDir.resolve("atv").resolve("admin_reset_token")));
    }
}
