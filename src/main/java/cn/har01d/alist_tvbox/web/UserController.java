package cn.har01d.alist_tvbox.web;

import java.io.File;
import java.util.List;
import java.util.Map;

import cn.har01d.alist_tvbox.dto.UserDto;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.har01d.alist_tvbox.auth.LoginDto;
import cn.har01d.alist_tvbox.auth.UserToken;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import cn.har01d.alist_tvbox.service.SettingService;
import cn.har01d.alist_tvbox.service.UserService;
import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequiredArgsConstructor
public class UserController {
    private static final String ADMIN_RESET_TOKEN_FILE = "admin_reset_token";
    private static final String BACKUP_TOKEN_FILE = "backup_token";
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final SettingService settingService;

    @GetMapping("/api/users")
    public List<User> list() {
        return userService.list();
    }

    @PostMapping("/api/users")
    public User create(@RequestBody UserDto user) {
        return userService.create(user);
    }

    @PostMapping("/api/users/{id}")
    public User update(@PathVariable int id, @RequestBody UserDto user) {
        return userService.update(id, user);
    }

    @DeleteMapping("/api/users/{id}")
    public void delete(@PathVariable int id) {
        userService.delete(id);
    }

    @PostMapping("/api/accounts/login")
    public UserToken login(@RequestBody LoginDto account) {
        User user = userService.findByUsername(account.getUsername());
        if (user == null || !passwordEncoder.matches(account.getPassword(), user.getPassword())) {
            throw new UserUnauthorizedException("用户或密码错误", 40001);
        }
        return userService.generateToken(user);
    }

    @PostMapping("/api/accounts/logout")
    public void logout() {
        userService.logout();
    }

    @GetMapping("/api/accounts/principal")
    public Authentication principal() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @PostMapping("/api/accounts/update")
    public UserToken updateAccount(@RequestBody UserDto user) {
        return userService.updateAccount(user);
    }

    @PostMapping("/api/local/admin/password")
    public Map<String, String> resetAdminPassword(HttpServletRequest request) {
        String requestToken = request.getHeader("X-ADMIN-RESET-TOKEN");
        Path tokenFile = Utils.getDataPath("atv", ADMIN_RESET_TOKEN_FILE);
        boolean deleteTokenFile = false;
        try {
            if (requestToken == null || requestToken.isBlank() || !Files.exists(tokenFile)) {
                throw new BadRequestException("管理员重置令牌无效");
            }
            String expectedToken = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            if (!requestToken.equals(expectedToken)) {
                throw new BadRequestException("管理员重置令牌无效");
            }
            deleteTokenFile = true;
            String password = userService.resetAdminPassword(Utils.generateSecurePassword());
            return Map.of("username", "admin", "password", password);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("管理员重置失败", e);
        } finally {
            if (deleteTokenFile) {
                try {
                    Files.deleteIfExists(tokenFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 本地（容器内）触发数据库备份，供部署脚本调用。JSON 为主、SQL 为 fallback。
     * 鉴权方式与 {@link #resetAdminPassword} 一致：宿主先写 /data/atv/backup_token，再带 X-BACKUP-TOKEN 调用。
     * permitAll（见 {@code WebSecurityConfiguration}），安全性由一次性 token 文件保证。
     */
    @PostMapping("/api/local/backup")
    public Map<String, String> backupLocal(HttpServletRequest request,
                                           @RequestParam(name = "type", defaultValue = "json") String type) {
        String requestToken = request.getHeader("X-BACKUP-TOKEN");
        Path tokenFile = Utils.getDataPath("atv", BACKUP_TOKEN_FILE);
        boolean authorized = false;
        try {
            if (requestToken == null || requestToken.isBlank() || !Files.exists(tokenFile)) {
                throw new BadRequestException("备份令牌无效");
            }
            String expected = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            if (!requestToken.equals(expected)) {
                throw new BadRequestException("备份令牌无效");
            }
            authorized = true;
            File out = "sql".equalsIgnoreCase(type)
                    ? settingService.backupDatabase()
                    : settingService.backupJsonDatabase();
            if (out == null) {
                throw new BadRequestException("备份失败（SQL 备份仅 H2 可用，或备份过程出错）");
            }
            return Map.of("file", out.getName(), "path", out.getAbsolutePath());
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("备份失败", e);
        } finally {
            if (authorized) {
                try {
                    Files.deleteIfExists(tokenFile);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
