package cn.har01d.alist_tvbox.web;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import cn.har01d.alist_tvbox.util.Utils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.SettingService;
import cn.har01d.alist_tvbox.service.UserService;
import lombok.RequiredArgsConstructor;

/**
 * 容器内本地 API（供部署脚本调用），均 permitAll（见 {@code WebSecurityConfiguration}），
 * 安全性由一次性 token 文件保证（宿主写入 /data/atv/*_token，调用时带对应 header 比对）。
 * <ul>
 *   <li>/api/local/admin/password — 重置管理员密码（X-ADMIN-RESET-TOKEN）</li>
 *   <li>/api/local/backup — 触发数据库备份（X-BACKUP-TOKEN）</li>
 *   <li>/api/local/db-test — 测试目标数据库连接（X-BACKUP-TOKEN）</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class LocalApiController {
    private static final String ADMIN_RESET_TOKEN_FILE = "admin_reset_token";
    private static final String BACKUP_TOKEN_FILE = "backup_token";
    private final UserService userService;
    private final SettingService settingService;

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

    /** 校验一次性备份令牌（X-BACKUP-TOKEN 与 /data/atv/backup_token 比对）。无效抛 BadRequestException。 */
    private void requireBackupToken(HttpServletRequest request) {
        String requestToken = request.getHeader("X-BACKUP-TOKEN");
        Path tokenFile = Utils.getDataPath("atv", BACKUP_TOKEN_FILE);
        try {
            if (requestToken == null || requestToken.isBlank() || !Files.exists(tokenFile)) {
                throw new BadRequestException("备份令牌无效");
            }
            String expected = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            if (!requestToken.equals(expected)) {
                throw new BadRequestException("备份令牌无效");
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("备份令牌无效", e);
        }
    }

    /**
     * 本地（容器内）测试目标数据库连接：用应用自带的 JDBC 驱动（MySQL/PostgreSQL 均在 classpath）
     * 真实 DriverManager 连接并执行 SELECT 1。能抓住 TCP 测不出来的问题：主机未授权(Host not allowed)、
     * 账号密码错、库不存在，并返回数据库原始错误（sqlState/errorCode）。
     * 鉴权与 {@link #backupLocal} 一致（X-BACKUP-TOKEN）。
     */
    @PostMapping("/api/local/db-test")
    public Map<String, Object> testDatabaseConnection(HttpServletRequest request,
                                                      @RequestBody Map<String, String> body) {
        requireBackupToken(request);
        String url = body.get("url");
        String username = body.get("username");
        String password = body.get("password");
        if (url == null || url.isBlank()) {
            throw new BadRequestException("url 不能为空");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(url, username, password);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT 1")) {
                rs.next();
            }
            result.put("success", true);
        } catch (SQLException e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("sqlState", String.valueOf(e.getSQLState()));
            result.put("errorCode", e.getErrorCode());
        }
        return result;
    }
}
