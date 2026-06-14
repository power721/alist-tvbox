package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.sync.*;
import cn.har01d.alist_tvbox.exception.VersionMismatchException;
import cn.har01d.alist_tvbox.service.sync.SyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/sync")
public class SyncController {
    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * 验证用户身份但不创建会话
     * 使用 Basic Auth，避免导致现有会话失效
     */
    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestHeader("Authorization") String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Basic ")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "缺少 Basic Auth"));
            }

            String base64Credentials = authHeader.substring("Basic ".length());
            String credentials = new String(Base64.getDecoder().decode(base64Credentials));
            String[] parts = credentials.split(":", 2);

            if (parts.length != 2) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "无效的认证格式"));
            }

            String username = parts[0];
            String password = parts[1];

            // 验证用户名和密码（不创建会话）
            boolean valid = syncService.validateCredentials(username, password);

            if (valid) {
                return ResponseEntity.ok(Map.of("success", true, "message", "验证成功"));
            } else {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "用户名或密码错误"));
            }
        } catch (Exception e) {
            log.error("验证失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "验证失败: " + e.getMessage()));
        }
    }

    @PostMapping("/connect")
    public ConnectionResult connect(@RequestBody ConnectionInfo info) {
        ConnectionResult result = new ConnectionResult();

        try {
            // 登录测试
            String token = syncService.getRemoteClient().login(info.getUrl(),
                info.getUsername(), info.getPassword());

            // 获取远端版本
            SyncData data = syncService.getRemoteClient().fetchRemoteData(
                info.getUrl(), token, List.of());

            result.setSuccess(true);
            result.setToken(token);
            result.setAppVersion(data.getAppVersion());
            result.setMessage("连接成功");

            log.info("连接远端成功: {}", info.getUrl());
        } catch (Exception e) {
            log.error("连接远端失败: {}", info.getUrl(), e);
            result.setSuccess(false);
            result.setMessage(e.getMessage());
        }

        return result;
    }

    @GetMapping("/export")
    public SyncData export(@RequestParam("modules") List<String> modules) {
        log.info("导出数据，模块: {}", modules);
        return syncService.exportData(modules);
    }

    @PostMapping("/import")
    public SyncResponse importData(@RequestBody SyncRequest request) {
        log.info("导入数据，策略: {}, force: {}", request.getStrategy(), request.isForce());

        SyncResponse response = new SyncResponse();
        try {
            Map<String, SyncResult> results = syncService.importData(
                request.getData(),
                request.getStrategy(),
                request.isForce());

            response.setSuccess(true);
            response.setResults(results);
        } catch (VersionMismatchException e) {
            response.setSuccess(false);
            SyncResult errorResult = new SyncResult();
            errorResult.setFailed(1);
            errorResult.getErrors().add(e.getMessage());
            response.addResult("version_error", errorResult);
        } catch (Exception e) {
            log.error("导入数据失败", e);
            response.setSuccess(false);
            SyncResult errorResult = new SyncResult();
            errorResult.setFailed(1);
            errorResult.getErrors().add("导入失败: " + e.getMessage());
            response.addResult("error", errorResult);
        }

        return response;
    }

    @PostMapping("/push")
    public SyncResponse push(@RequestBody SyncRequest request) {
        log.info("推送到远端: {}, 模块: {}", request.getRemoteUrl(), request.getModules());
        return syncService.push(
            request.getRemoteUrl(),
            request.getUsername(),
            request.getPassword(),
            request.getModules()
        );
    }

    @PostMapping("/pull")
    public SyncResponse pull(@RequestBody SyncRequest request) {
        log.info("从远端拉取: {}, 模块: {}, 策略: {}",
            request.getRemoteUrl(), request.getModules(), request.getStrategy());

        try {
            return syncService.pull(
                request.getRemoteUrl(),
                request.getUsername(),
                request.getPassword(),
                request.getModules(),
                request.getStrategy(),
                request.isForce()
            );
        } catch (VersionMismatchException e) {
            // 版本不匹配，返回特殊响应让前端处理
            SyncResponse response = new SyncResponse();
            response.setSuccess(false);
            SyncResult errorResult = new SyncResult();
            errorResult.setFailed(1);
            errorResult.getErrors().add("VERSION_MISMATCH:" + e.getMessage());
            response.addResult("version_error", errorResult);
            return response;
        }
    }
}
