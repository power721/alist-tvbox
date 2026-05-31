package cn.har01d.alist_tvbox.service.offline;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.Utils;
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

@Slf4j
@Component
public class ThunderOfflineDownloadHandler implements OfflineDownloadHandler {
    private static final String API_BASE = "https://x-api-pan.xunlei.com/drive/v1";
    private static final String AUTH_BASE = "https://xluser-ssl.xunlei.com";
    private static final String CLIENT_ID = "ZUBzD9J_XPXfn7f7";
    private static final String CLIENT_VERSION = "1.40.0.7208";
    private static final String PACKAGE_NAME = "com.xunlei.browser";
    private static final String SDK_VERSION = "509300";
    private static final String APPID = "22062";

    private static final String SIGNIN_URL = AUTH_BASE + "/v1/auth/signin";
    private static final String TOKEN_URL = AUTH_BASE + "/v1/auth/token";
    private static final String CAPTCHA_INIT_URL = AUTH_BASE + "/v1/shield/captcha/init";
    private static final String FILES_URL = API_BASE + "/files";
    private static final String TASKS_URL = API_BASE + "/tasks";
    private static final String ABOUT_URL = API_BASE + "/about";

    private static final String[] ALGORITHMS = {
            "Cw4kArmKJ/aOiFTxnQ0ES+D4mbbrIUsFn",
            "HIGg0Qfbpm5ThZ/RJfjoao4YwgT9/M",
            "u/PUD",
            "OlAm8tPkOF1qO5bXxRN2iFttuDldrg",
            "FFIiM6sFhWhU7tIMVUKOF7CUv/KzgwwV8FE",
            "yN",
            "4m5mglrIHksI6wYdq",
            "LXEfS7",
            "T+p+C+F2yjgsUtiXWU/cMNYEtJI4pq7GofW",
            "14BrGIEMXkbvFvZ49nDUfVCRcHYFOJ1BP1Y",
            "kWIH3Row",
            "RAmRTKNCjucPWC",
    };

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
                FILES_URL + "?parent_id=" + parentId + "&page_token=",
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
        createBody.put("kind", "drive#file");
        createBody.put("parent_id", folderId);
        createBody.put("upload_type", "UPLOAD_TYPE_URL");

        ObjectNode urlObj = createBody.putObject("url");
        urlObj.put("url", url);

        ObjectNode createResult = exchangeWithRetry(account,
                FILES_URL + "?_from=cloudadd/", HttpMethod.POST, createBody);
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
                    TASKS_URL + "?type=offline&limit=10000&page_token=",
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
                    sleepOneSecond();
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
        String token = account.getToken();
        if (StringUtils.isBlank(token)) {
            throw new BadRequestException("迅雷云盘Token为空，请先配置账号Token");
        }
        String captchaToken = account.getCookie();
        String deviceId = getDeviceId(account);
        try {
            return exchange(url, method, token, captchaToken, deviceId, body);
        } catch (HttpClientErrorException.BadRequest e) {
            String respBody = e.getResponseBodyAsString();
            if (respBody.contains("captcha_invalid")) {
                log.info("thunder captcha token expired, refreshing for accountId={}", account.getId());
                String newCaptchaToken = refreshCaptchaToken(captchaToken, deviceId, getAction(method, url), getUserIdFromToken(token));
                account.setCookie(newCaptchaToken);
                driverAccountRepository.save(account);
                return exchange(url, method, token, newCaptchaToken, deviceId, body);
            }
            throw new BadRequestException("迅雷云盘接口错误: " + respBody);
        }
    }

    private ObjectNode exchange(String url, HttpMethod method, String token, String captchaToken, String deviceId, ObjectNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, "application/json;charset=UTF-8");
        headers.set(HttpHeaders.USER_AGENT, buildUserAgent());
        headers.set("X-Captcha-Token", captchaToken);
        headers.set("x-client-id", CLIENT_ID);
        headers.set("x-client-version", CLIENT_VERSION);
        headers.set("x-device-id", deviceId);
        headers.set("X-Space-Authorization", "");

        HttpEntity<?> entity = body != null ? new HttpEntity<>(body.toString(), headers) : new HttpEntity<>(headers);
        log.debug("exchange: {}", entity);
        ResponseEntity<String> response = restTemplate.exchange(url, method, entity, String.class);
        return parseJsonBody(response.getBody(), url);
    }

    private String refreshCaptchaToken(String currentCaptchaToken, String deviceId, String action, String userId) {
        long timestamp = System.currentTimeMillis();
        String captchaSign = getCaptchaSign(deviceId, timestamp);

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("client_version", CLIENT_VERSION);
        meta.put("package_name", PACKAGE_NAME);
        meta.put("user_id", userId);
        meta.put("timestamp", String.valueOf(timestamp));
        meta.put("captcha_sign", captchaSign);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("action", action);
        requestBody.put("captcha_token", currentCaptchaToken);
        requestBody.put("client_id", CLIENT_ID);
        requestBody.put("device_id", deviceId);
        requestBody.set("meta", meta);
        requestBody.put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, "application/json;charset=UTF-8");
        headers.set(HttpHeaders.USER_AGENT, buildUserAgent());
        headers.set("x-device-id", deviceId);
        headers.set("x-client-id", CLIENT_ID);
        headers.set("x-client-version", CLIENT_VERSION);

        HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);
        ResponseEntity<String> response = restTemplate.exchange(CAPTCHA_INIT_URL, HttpMethod.POST, entity, String.class);
        ObjectNode json = parseJsonBody(response.getBody(), CAPTCHA_INIT_URL);

        String newCaptchaToken = json.path("captcha_token").asText("");
        if (StringUtils.isBlank(newCaptchaToken)) {
            throw new BadRequestException("迅雷云盘刷新验证码Token失败");
        }
        log.info("thunder captcha token refreshed");
        return newCaptchaToken;
    }

    private String getCaptchaSign(String deviceId, long timestamp) {
        String str = CLIENT_ID + CLIENT_VERSION + PACKAGE_NAME + deviceId + timestamp;
        for (String algorithm : ALGORITHMS) {
            str = Utils.md5(str + algorithm);
        }
        return "1." + str;
    }

    private String getAction(HttpMethod method, String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return method.name() + ":" + url;
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) {
            return method.name() + ":/";
        }
        String path = url.substring(pathStart);
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }
        return method.name() + ":" + path;
    }

    private String getUserIdFromToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return "";
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
            ObjectNode json = (ObjectNode) objectMapper.readTree(payload);
            return json.path("sub").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String buildUserAgent() {
        return String.format("ANDROID-%s/%s networkType/WIFI appid/%s deviceName/Xiaomi_M2004j7ac deviceModel/M2004J7AC OSVersion/13 protocolVersion/301 platformversion/10 sdkVersion/%s Oauth2Client/0.9 (Linux 4_9_337-perf-sn-uotan-gd9d488809c3d) (JAVA 0)",
                PACKAGE_NAME, CLIENT_VERSION, APPID, SDK_VERSION);
    }

    private String getDeviceId(DriverAccount account) {
        String deviceId = getAdditionField(account, "device_id");
        if (StringUtils.isBlank(deviceId)) {
            deviceId = Utils.md5(account.getUsername() + account.getPassword());
            setAdditionField(account, "device_id", deviceId);
            driverAccountRepository.save(account);
        }
        return deviceId;
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
