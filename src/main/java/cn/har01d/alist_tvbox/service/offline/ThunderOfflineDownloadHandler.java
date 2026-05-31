package cn.har01d.alist_tvbox.service.offline;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class ThunderOfflineDownloadHandler implements OfflineDownloadHandler {
    private static final String API_BASE = "https://api-pan.xunlei.com";
    private static final String AUTH_BASE = "https://xluser-ssl.xunlei.com";
    private static final String CLIENT_ID = "Xqp0kJBXWhwaTpB6";

    private static final String SIGNIN_URL = AUTH_BASE + "/v1/auth/signin";
    private static final String TOKEN_URL = AUTH_BASE + "/v1/auth/token";
    private static final String FILES_URL = API_BASE + "/drive/v1/files";
    private static final String TASKS_URL = API_BASE + "/drive/v1/tasks";
    private static final String ABOUT_URL = API_BASE + "/drive/v1/about";

    private final DriverAccountRepository driverAccountRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ThunderOfflineDownloadHandler(DriverAccountRepository driverAccountRepository,
                                         RestTemplateBuilder builder,
                                         ObjectMapper objectMapper) {
        this.driverAccountRepository = driverAccountRepository;
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public DriverType getDriverType() {
        return DriverType.THUNDER;
    }

    @Override
    public String ensureOfflineFolder(DriverAccount account) {
        String parentId = requireParentFolderId(account);

        ObjectNode listResult = exchangeWithRetry(account,
                FILES_URL + "?parent_id=" + parentId + "&page_token=&limit=100",
                HttpMethod.GET, null);
        log.debug("list files response: {}", listResult);

        ArrayNode files = withArray(listResult, "files");
        if (files != null) {
            for (var item : files) {
                if ("alist-tvbox-offline".equals(item.path("name").asText(""))
                        && "drive#folder".equals(item.path("kind").asText(""))) {
                    return item.path("id").asText("");
                }
            }
        }

        ObjectNode createBody = objectMapper.createObjectNode();
        createBody.put("kind", "drive#folder");
        createBody.put("name", "alist-tvbox-offline");
        createBody.put("parent_id", parentId);

        ObjectNode created = exchangeWithRetry(account, FILES_URL, HttpMethod.POST, createBody);
        String folderId = created.path("file").path("id").asText("");
        if (StringUtils.isBlank(folderId)) {
            throw new BadRequestException("创建迅雷离线下载目录失败");
        }
        log.info("created thunder offline folder: {}", folderId);
        return folderId;
    }

    @Override
    public TaskResult submitAndWait(DriverAccount account, String url, String folderId) {
        log.info("submitting thunder offline download: accountId={}, folderId={}", account.getId(), folderId);

        ObjectNode createBody = objectMapper.createObjectNode();
        createBody.put("upload_type", "UPLOAD_TYPE_URL");
        createBody.put("kind", "drive#file");
        createBody.put("parent_id", folderId);
        createBody.put("name", url);
        createBody.put("hash", "");
        createBody.put("size", 0);
        createBody.put("unionId", "");

        ObjectNode urlObj = createBody.putObject("url");
        urlObj.put("url", url);
        urlObj.putArray("files");

        ObjectNode params = createBody.putObject("params");
        params.put("require_links", "false");

        ObjectNode createResult = exchangeWithRetry(account, FILES_URL, HttpMethod.POST, createBody);
        log.debug("create task result: {}", createResult);

        ObjectNode task = createResult.path("task").isObject() ? (ObjectNode) createResult.path("task") : null;
        if (task == null) {
            throw new BadRequestException("迅雷云盘创建离线下载任务失败");
        }

        String taskId = task.path("id").asText("");
        if (StringUtils.isBlank(taskId)) {
            throw new BadRequestException("迅雷云盘创建离线下载任务失败");
        }
        log.info("thunder task created: taskId={}", taskId);

        for (int i = 0; i < 30; i++) {
            ObjectNode taskList = exchangeWithRetry(account,
                    TASKS_URL + "?limit=100&phaseCheck=false&page_token=&type=offline",
                    HttpMethod.GET, null);

            ObjectNode found = findTaskInList(taskList, taskId);
            if (found != null) {
                String phase = found.path("phase").asText("");
                if ("PHASE_TYPE_COMPLETE".equals(phase)) {
                    String name = found.path("file_name").asText("");
                    if (StringUtils.isBlank(name)) {
                        name = found.path("name").asText("");
                    }
                    if (StringUtils.isBlank(name)) {
                        throw new BadRequestException("迅雷云盘离线下载任务成功但未返回名称");
                    }
                    boolean isFolder = "drive#folder".equals(found.path("reference_resource").path("kind").asText(""));
                    log.info("thunder task completed: taskId={}, name={}", taskId, name);
                    return new TaskResult(name, "", isFolder);
                }
                if ("PHASE_TYPE_ERROR".equals(phase)) {
                    String message = found.path("message").asText("迅雷云盘离线下载任务失败");
                    throw new BadRequestException("task failed: " + message);
                }
            }
            sleepOneSecond();
        }

        throw new BadRequestException("迅雷云盘离线下载任务未在30秒内完成");
    }

    @Override
    public QuotaResult getQuota(DriverAccount account) {
        try {
            ObjectNode result = exchangeWithRetry(account,
                    ABOUT_URL + "?with_quotas=CREATE_OFFLINE_TASK_LIMIT",
                    HttpMethod.GET, null);

            ObjectNode quota = result.path("quota").isObject() ? (ObjectNode) result.path("quota") : null;
            if (quota == null) {
                return QuotaResult.unsupported();
            }

            long limit = Long.parseLong(quota.path("limit").asText("0"));
            long usage = Long.parseLong(quota.path("usage").asText("0"));
            long free = limit - usage;
            String display = String.format("空间：%.1fGB/%.1fGB", free / 1073741824.0, limit / 1073741824.0);

            ObjectNode offlineQuota = result.path("quotas").path("CREATE_OFFLINE_TASK_LIMIT").isObject()
                    ? (ObjectNode) result.path("quotas").path("CREATE_OFFLINE_TASK_LIMIT") : null;
            if (offlineQuota != null) {
                int offlineLimit = Integer.parseInt(offlineQuota.path("limit").asText("0"));
                int offlineUsage = Integer.parseInt(offlineQuota.path("usage").asText("0"));
                display += String.format(" 离线：剩%d/总%d个", offlineLimit - offlineUsage, offlineLimit);
            }

            return new QuotaResult(true, display);
        } catch (BadRequestException | RestClientException e) {
            log.debug("failed to get thunder quota: {}", e.getMessage());
            return QuotaResult.unsupported();
        }
    }

    private ObjectNode exchangeWithRetry(DriverAccount account, String url, HttpMethod method, ObjectNode body) {
        String token = getAccessToken(account);
        String deviceId = getDeviceId(account);
        try {
            return exchange(url, method, token, deviceId, body);
        } catch (HttpClientErrorException.Unauthorized e) {
            log.info("thunder token expired, refreshing for accountId={}", account.getId());
            String newToken = refreshToken(account);
            return exchange(url, method, newToken, deviceId, body);
        }
    }

    private ObjectNode exchange(String url, HttpMethod method, String token, String deviceId, ObjectNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, "*/*");
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36");
        headers.set("x-client-id", CLIENT_ID);
        headers.set("x-device-id", deviceId);

        HttpEntity<?> entity = body != null ? new HttpEntity<>(body.toString(), headers) : new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, method, entity, String.class);
        return parseJsonBody(response.getBody(), url);
    }

    private String getAccessToken(DriverAccount account) {
        if (StringUtils.isNotBlank(account.getToken())) {
            return account.getToken().trim();
        }
        return authenticate(account);
    }

    private String getDeviceId(DriverAccount account) {
        String deviceId = getAdditionField(account, "device_id");
        if (StringUtils.isBlank(deviceId)) {
            deviceId = UUID.randomUUID().toString().replace("-", "");
            setAdditionField(account, "device_id", deviceId);
            driverAccountRepository.save(account);
        }
        return deviceId;
    }

    private String authenticate(DriverAccount account) {
        if (StringUtils.isBlank(account.getUsername()) || StringUtils.isBlank(account.getPassword())) {
            throw new BadRequestException("迅雷云盘用户名或密码不能为空，请先配置账号");
        }

        String deviceId = getDeviceId(account);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("captcha_token", "");
        body.put("client_id", CLIENT_ID);
        body.put("device_id", deviceId);
        body.put("username", account.getUsername());
        body.put("password", account.getPassword());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, "*/*");
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36");

        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);
        ResponseEntity<String> response = restTemplate.exchange(SIGNIN_URL, HttpMethod.POST, entity, String.class);
        ObjectNode json = parseJsonBody(response.getBody(), SIGNIN_URL);

        String accessToken = json.path("token").path("access_token").asText("");
        String refreshTokenValue = json.path("token").path("refresh_token").asText("");

        if (StringUtils.isBlank(accessToken)) {
            accessToken = json.path("access_token").asText("");
            refreshTokenValue = json.path("refresh_token").asText("");
        }

        if (StringUtils.isBlank(accessToken)) {
            String message = json.path("error_msg").asText("");
            if (StringUtils.isBlank(message)) {
                message = json.path("message").asText("迅雷云盘登录失败");
            }
            throw new BadRequestException("迅雷云盘登录失败: " + message);
        }

        account.setToken(accessToken);
        setAdditionField(account, "refresh_token", refreshTokenValue);
        driverAccountRepository.save(account);
        log.info("thunder authenticated for accountId={}", account.getId());
        return accessToken;
    }

    private String refreshToken(DriverAccount account) {
        String refreshTokenValue = getAdditionField(account, "refresh_token");
        if (StringUtils.isBlank(refreshTokenValue)) {
            return authenticate(account);
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("client_id", CLIENT_ID);
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshTokenValue);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, "*/*");
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36");

        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);
        ResponseEntity<String> response = restTemplate.exchange(TOKEN_URL, HttpMethod.POST, entity, String.class);
        ObjectNode json = parseJsonBody(response.getBody(), TOKEN_URL);

        String accessToken = json.path("token").path("access_token").asText("");
        String newRefreshToken = json.path("token").path("refresh_token").asText("");

        if (StringUtils.isBlank(accessToken)) {
            accessToken = json.path("access_token").asText("");
            newRefreshToken = json.path("refresh_token").asText("");
        }

        if (StringUtils.isBlank(accessToken)) {
            log.warn("thunder token refresh failed, re-authenticating for accountId={}", account.getId());
            return authenticate(account);
        }

        account.setToken(accessToken);
        if (StringUtils.isNotBlank(newRefreshToken)) {
            setAdditionField(account, "refresh_token", newRefreshToken);
        }
        driverAccountRepository.save(account);
        log.info("thunder token refreshed for accountId={}", account.getId());
        return accessToken;
    }

    private String getAdditionField(DriverAccount account, String field) {
        if (StringUtils.isNotBlank(account.getAddition())) {
            try {
                Map<String, Object> addition = objectMapper.readValue(account.getAddition(), Map.class);
                String value = (String) addition.get(field);
                if (StringUtils.isNotBlank(value)) {
                    return value.trim();
                }
            } catch (Exception e) {
                log.debug("failed to parse thunder addition: {}", e.getMessage());
            }
        }
        return "";
    }

    private void setAdditionField(DriverAccount account, String field, String value) {
        try {
            Map<String, Object> addition = StringUtils.isNotBlank(account.getAddition())
                    ? objectMapper.readValue(account.getAddition(), Map.class)
                    : new java.util.HashMap<>();
            addition.put(field, value);
            account.setAddition(objectMapper.writeValueAsString(addition));
        } catch (Exception e) {
            log.warn("failed to update thunder addition: {}", e.getMessage());
        }
    }

    private String requireParentFolderId(DriverAccount account) {
        if (StringUtils.isBlank(account.getFolder())) {
            return "";
        }
        return account.getFolder().trim();
    }

    private ObjectNode parseJsonBody(String body, String url) {
        if (StringUtils.isBlank(body)) {
            throw new BadRequestException("迅雷云盘接口返回空响应: " + url);
        }
        try {
            return (ObjectNode) objectMapper.readTree(body);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            String snippet = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            throw new BadRequestException("迅雷云盘接口返回非JSON响应: " + snippet, e);
        }
    }

    private ObjectNode findTaskInList(ObjectNode taskList, String taskId) {
        ArrayNode tasks = withArray(taskList, "tasks");
        if (tasks == null) {
            return null;
        }
        for (var item : tasks) {
            if (taskId.equals(item.path("id").asText(""))) {
                return (ObjectNode) item;
            }
        }
        return null;
    }

    private ArrayNode withArray(ObjectNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode result = node.path(field);
        return result.isArray() ? (ArrayNode) result : null;
    }

    private void sleepOneSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("迅雷云盘离线下载任务被中断", e);
        }
    }
}
