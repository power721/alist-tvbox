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
import org.springframework.boot.restclient.RestTemplateBuilder;
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
public class GuangyapanOfflineDownloadHandler implements OfflineDownloadHandler {
    private static final String API_BASE = "https://api.guangyapan.com";
    private static final String ACCOUNT_API = "https://account.guangyapan.com";
    private static final String CLIENT_ID = "aMe-8VSlkrbQXpUR";
    private static final String RESOLVE_URL = API_BASE + "/cloudcollection/v1/resolve_res";
    private static final String CREATE_TASK_URL = API_BASE + "/cloudcollection/v1/create_task";
    private static final String LIST_TASK_URL = API_BASE + "/cloudcollection/v1/list_task";
    private static final String FILE_LIST_URL = API_BASE + "/userres/v1/file/get_file_list";
    private static final String CREATE_DIR_URL = API_BASE + "/userres/v1/file/create_dir";
    private static final String ASSETS_URL = API_BASE + "/assets/v1/get_assets";
    private static final String TOKEN_URL = ACCOUNT_API + "/v1/auth/token";

    private final DriverAccountRepository driverAccountRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GuangyapanOfflineDownloadHandler(DriverAccountRepository driverAccountRepository,
                                            RestTemplateBuilder builder,
                                            ObjectMapper objectMapper) {
        this.driverAccountRepository = driverAccountRepository;
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public DriverType getDriverType() {
        return DriverType.GUANGYA;
    }

    @Override
    public String ensureOfflineFolder(DriverAccount account) {
        String parentId = requireParentFolderId(account);

        String existingId = findFolder(account, parentId, "alist-tvbox-offline");
        if (StringUtils.isNotBlank(existingId)) {
            return existingId;
        }

        ObjectNode createBody = objectMapper.createObjectNode();
        createBody.put("dirName", "alist-tvbox-offline");
        createBody.put("parentId", parentId);
        ObjectNode created = exchangeWithRetry(account, CREATE_DIR_URL, HttpMethod.POST, createBody);
        String folderId = created.path("data").path("fileId").asText("");
        if (StringUtils.isBlank(folderId)) {
            folderId = findFolder(account, parentId, "alist-tvbox-offline");
        }
        if (StringUtils.isBlank(folderId)) {
            throw new BadRequestException("创建光鸭离线下载目录失败");
        }
        return folderId;
    }

    private String findFolder(DriverAccount account, String parentId, String name) {
        ObjectNode listBody = objectMapper.createObjectNode();
        listBody.put("parentId", parentId);
        listBody.put("page", 0);
        listBody.put("pageSize", 100);
        listBody.put("orderBy", 0);
        listBody.put("sortType", 0);
        ObjectNode list = exchangeWithRetry(account, FILE_LIST_URL, HttpMethod.POST, listBody);
        log.info("findFolder response for parentId={}: {}", parentId, list);
        ArrayNode items = withArray(list, "data", "list");
        if (items != null) {
            for (var item : items) {
                if (name.equals(item.path("fileName").asText("")) && item.path("resType").asInt(0) != 1) {
                    String id = item.path("fileId").asText("");
                    if (StringUtils.isNotBlank(id)) {
                        return id;
                    }
                }
            }
        }
        return "";
    }

    @Override
    public TaskResult submitAndWait(DriverAccount account, String url, String folderId) {
        log.info("submitting guangyapan offline download: accountId={}, folderId={}", account.getId(), folderId);

        ObjectNode createBody = objectMapper.createObjectNode();
        createBody.put("url", url);
        createBody.put("parentId", folderId);
        ObjectNode createResult = exchangeWithRetry(account, CREATE_TASK_URL, HttpMethod.POST, createBody);
        log.debug("create task result: {}", createResult);

        String taskId = createResult.path("data").path("taskId").asText("");
        if (StringUtils.isBlank(taskId)) {
            throw new BadRequestException("光鸭云盘创建离线下载任务失败");
        }
        log.info("guangyapan task created: taskId={}", taskId);

        for (int i = 0; i < 30; i++) {
            ObjectNode taskListBody = objectMapper.createObjectNode();
            taskListBody.put("pageSize", 100);
            taskListBody.putArray("status").add(0).add(1).add(2).add(5);
            ObjectNode taskList = exchangeWithRetry(account, LIST_TASK_URL, HttpMethod.POST, taskListBody);

            ObjectNode task = findTaskInList(taskList, taskId);
            if (task != null) {
                int status = task.path("status").asInt(-1);
                if (status == 2) {
                    String name = task.path("fileName").asText("");
                    if (StringUtils.isBlank(name)) {
                        throw new BadRequestException("光鸭云盘离线下载任务成功但未返回名称");
                    }
                    boolean isFolder = task.path("isDir").asBoolean(false);
                    log.info("guangyapan task completed: taskId={}, name={}, isDir={}", taskId, name, isFolder);
                    return new TaskResult(name, "", isFolder);
                }
                if (status == 0 || status == 1) {
                    sleepOneSecond();
                    continue;
                }
                String errorMsg = task.path("failReason").asText("");
                if (StringUtils.isBlank(errorMsg)) {
                    errorMsg = "光鸭云盘离线下载任务失败";
                }
                throw new BadRequestException("task failed: " + errorMsg);
            }
            sleepOneSecond();
        }

        throw new BadRequestException("光鸭云盘离线下载任务未在30秒内完成");
    }

    @Override
    public QuotaResult getQuota(DriverAccount account) {
        try {
            ObjectNode result = exchangeWithRetry(account, ASSETS_URL, HttpMethod.POST, null);
            ObjectNode data = withObject(result, "data");
            if (data == null) {
                return QuotaResult.unsupported();
            }
            long total = data.path("totalSpaceSize").asLong(0);
            long used = data.path("usedSpaceSize").asLong(0);
            long free = total - used;
            String display = String.format("空间：%.1fTB/%.1fTB", free / 1099511627776.0, total / 1099511627776.0);
            int vipStatus = data.path("vipStatus").asInt(0);
            if (vipStatus > 0) {
                display += " VIP";
            }
            return new QuotaResult(true, display);
        } catch (BadRequestException | RestClientException e) {
            log.debug("failed to get guangyapan quota: {}", e.getMessage());
            return QuotaResult.unsupported();
        }
    }

    private ObjectNode exchangeWithRetry(DriverAccount account, String url, HttpMethod method, ObjectNode body) {
        String token = getAccessToken(account);
        try {
            return exchange(url, method, token, body);
        } catch (HttpClientErrorException.Unauthorized e) {
            log.info("guangyapan token expired, refreshing for accountId={}", account.getId());
            String newToken = refreshToken(account);
            return exchange(url, method, newToken, body);
        }
    }

    private String getAccessToken(DriverAccount account) {
        if (StringUtils.isNotBlank(account.getToken())) {
            return account.getToken().trim();
        }
        return getAdditionField(account, "access_token");
    }

    private String getRefreshToken(DriverAccount account) {
        return getAdditionField(account, "refresh_token");
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
                log.debug("failed to parse guangyapan addition: {}", e.getMessage());
            }
        }
        return "";
    }

    private String refreshToken(DriverAccount account) {
        String refreshTokenValue = getRefreshToken(account);
        if (StringUtils.isBlank(refreshTokenValue)) {
            throw new BadRequestException("光鸭云盘refresh_token为空，请重新登录");
        }

        HttpHeaders headers = accountHeaders();
        Map<String, Object> body = Map.of(
                "client_id", CLIENT_ID,
                "grant_type", "refresh_token",
                "refresh_token", refreshTokenValue
        );
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.exchange(TOKEN_URL, HttpMethod.POST, entity, String.class);
        ObjectNode json = parseJsonBody(response.getBody(), TOKEN_URL);

        String newAccessToken = json.path("access_token").asText("");
        String newRefreshToken = json.path("refresh_token").asText("");
        if (StringUtils.isBlank(newAccessToken)) {
            throw new BadRequestException("光鸭云盘Token刷新失败，请重新登录");
        }

        account.setToken(newAccessToken);
        try {
            Map<String, Object> addition = StringUtils.isNotBlank(account.getAddition())
                    ? objectMapper.readValue(account.getAddition(), Map.class)
                    : new java.util.HashMap<>();
            addition.put("access_token", newAccessToken);
            if (StringUtils.isNotBlank(newRefreshToken)) {
                addition.put("refresh_token", newRefreshToken);
            }
            account.setAddition(objectMapper.writeValueAsString(addition));
        } catch (Exception e) {
            log.warn("failed to update guangyapan addition: {}", e.getMessage());
        }
        driverAccountRepository.save(account);
        log.info("guangyapan token refreshed for accountId={}", account.getId());
        return newAccessToken;
    }

    private HttpHeaders accountHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, "*/*");
        headers.set("X-Client-Id", CLIENT_ID);
        headers.set("X-Client-Version", "0.0.1");
        headers.set("X-Device-Id", "alist-tvbox");
        headers.set("X-SDK-Version", "9.0.2");
        headers.set("X-Protocol-Version", "301");
        return headers;
    }

    private String requireParentFolderId(DriverAccount account) {
        if (StringUtils.isBlank(account.getFolder()) || "0".equals(account.getFolder().trim())) {
            return "";
        }
        return account.getFolder().trim();
    }

    private ObjectNode exchange(String url, HttpMethod method, String token, ObjectNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        headers.set(HttpHeaders.REFERER, "https://www.guangyapan.com/");
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36");
        headers.set("did", "alist-tvbox");
        headers.set("dt", "4");

        HttpEntity<?> entity = body != null ? new HttpEntity<>(body.toString(), headers) : new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, method, entity, String.class);
        return parseJsonBody(response.getBody(), url);
    }

    private ObjectNode parseJsonBody(String body, String url) {
        if (StringUtils.isBlank(body)) {
            throw new BadRequestException("光鸭云盘接口返回空响应: " + url);
        }
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(body);
            int code = node.path("code").asInt(-1);
            if (code != 0 && code != -1) {
                String msg = node.path("msg").asText("未知错误");
                throw new BadRequestException("光鸭云盘接口错误: " + msg);
            }
            return node;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            String snippet = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            throw new BadRequestException("光鸭云盘接口返回非JSON响应: " + snippet, e);
        }
    }

    private ObjectNode findTaskInList(ObjectNode taskList, String taskId) {
        ArrayNode tasks = withArray(taskList, "data", "list");
        if (tasks == null) {
            return null;
        }
        for (var item : tasks) {
            if (taskId.equals(item.path("taskId").asText(""))) {
                return (ObjectNode) item;
            }
        }
        return null;
    }

    private ArrayNode withArray(ObjectNode node, String... path) {
        com.fasterxml.jackson.databind.JsonNode current = node;
        for (int i = 0; i < path.length - 1; i++) {
            current = current.path(path[i]);
        }
        com.fasterxml.jackson.databind.JsonNode result = current.path(path[path.length - 1]);
        return result.isArray() ? (ArrayNode) result : null;
    }

    private ObjectNode withObject(ObjectNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode child = node.path(field);
        return child.isObject() ? (ObjectNode) child : null;
    }

    private void sleepOneSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("光鸭云盘离线下载任务被中断", e);
        }
    }
}
