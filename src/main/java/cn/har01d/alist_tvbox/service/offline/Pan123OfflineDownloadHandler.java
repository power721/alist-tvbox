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
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 123 网盘(cookie 版,yun.123pan.com 安卓头族)离线下载,契约对齐内嵌 PowerList drivers/123:
 * <ul>
 * <li>提交两步:resolve 解析资源(resource_id + select_file_id)→ submit 建任务,任务 id 为 int64
 *     —— 行的 info_hash 列直接存任务 id 字符串,清理定位零匹配成本;</li>
 * <li>轮询任务列表(status_arr [0,1,2,3]):2 成功 / 1 失败 / 0 下载中 / 3 重试中;</li>
 * <li>任务删除有 API(task/delete)但无「连文件删」参数 —— supportsTaskManagement=true、
 *     deletesFilesWithTask=false,文件由清理调度经内嵌 AList 删;</li>
 * <li>认证:Bearer token(account.token,Go 侧存储重登后同步回写),失效按 DriverAccountService
 *     同款 passport/mail 双形态 sign_in 重登自愈并回写。</li>
 * </ul>
 */
@Slf4j
@Component
public class Pan123OfflineDownloadHandler implements OfflineDownloadHandler {
    private static final String API = "https://yun.123pan.com/api";
    private static final String LOGIN_API = "https://login.123pan.com/api";
    private static final String RESOLVE_URL = API + "/v2/offline_download/task/resolve";
    private static final String SUBMIT_URL = API + "/v2/offline_download/task/submit";
    private static final String TASK_LIST_URL = API + "/offline_download/task/list";
    private static final String TASK_DELETE_URL = API + "/offline_download/task/delete";
    private static final String FILE_LIST_URL = API + "/file/list/new";
    private static final String MKDIR_URL = API + "/file/upload_request";
    private static final String SIGN_IN_URL = LOGIN_API + "/user/sign_in";
    private static final String USER_AGENT = "123pan/v2.4.8(Android_8.1.0;Xiaomi M1810E5A)";
    private static final int TASK_LIST_PAGE_SIZE = 100;

    private final DriverAccountRepository driverAccountRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public Pan123OfflineDownloadHandler(DriverAccountRepository driverAccountRepository,
                                         RestTemplateBuilder builder,
                                         ObjectMapper objectMapper) {
        this.driverAccountRepository = driverAccountRepository;
        this.restTemplate = builder.connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(30)).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public DriverType getDriverType() {
        return DriverType.PAN123;
    }

    @Override
    public boolean supportsTaskManagement() {
        return true;
    }

    @Override
    public String ensureOfflineFolder(DriverAccount account) {
        String parentId = requireParentFolderId(account);
        ObjectNode list = exchange(account, FILE_LIST_URL
                + "?driveId=0&limit=100&next=0&orderBy=file_id&orderDirection=desc&parentFileId="
                + parentId + "&trashed=false", HttpMethod.GET, null);
        ensureCode(list, "查询123网盘目录失败");
        for (var item : fileItems(list)) {
            if ("alist-tvbox-offline".equals(item.path("FileName").asText(item.path("fileName").asText("")))) {
                String id = StringUtils.firstNonBlank(item.path("FileId").asText(""), item.path("fileId").asText(""));
                if (StringUtils.isNotBlank(id)) {
                    return id;
                }
            }
        }

        String body = "{\"driveId\":0,\"etag\":\"\",\"fileName\":\"alist-tvbox-offline\","
                + "\"parentFileId\":" + parentId + ",\"size\":0,\"type\":1}";
        ObjectNode created = exchange(account, MKDIR_URL, HttpMethod.POST, body);
        ensureCode(created, "创建123网盘离线下载目录失败");
        String folderId = created.path("data").path("Info").path("FileId").asText(
                created.path("data").path("info").path("fileId").asText(""));
        if (StringUtils.isBlank(folderId)) {
            folderId = findCreatedFolder(account, parentId); // mkdir 响应形态不稳定时回落重查
        }
        if (StringUtils.isBlank(folderId)) {
            throw new BadRequestException("创建123网盘离线下载目录失败");
        }
        log.info("created 123pan offline folder: {}", folderId);
        return folderId;
    }

    private String findCreatedFolder(DriverAccount account, String parentId) {
        try {
            ObjectNode list = exchange(account, FILE_LIST_URL
                    + "?driveId=0&limit=100&next=0&orderBy=file_id&orderDirection=desc&parentFileId="
                    + parentId + "&trashed=false", HttpMethod.GET, null);
            for (var item : fileItems(list)) {
                if ("alist-tvbox-offline".equals(item.path("FileName").asText(item.path("fileName").asText("")))) {
                    return StringUtils.firstNonBlank(item.path("FileId").asText(""), item.path("fileId").asText(""));
                }
            }
        } catch (Exception e) {
            log.debug("re-list 123pan folder failed: {}", e.getMessage());
        }
        return "";
    }

    private ArrayNode fileItems(ObjectNode list) {
        var data = list.path("data");
        var items = data.path("FileList");
        if (!items.isArray()) {
            items = data.path("fileList");
        }
        return items.isArray() ? (ArrayNode) items : objectMapper.createArrayNode();
    }

    @Override
    public TaskResult submitAndWait(DriverAccount account, String url, String folderId) {
        return submitAndWait(account, url, folderId, 30);
    }

    @Override
    public TaskResult submitAndWait(DriverAccount account, String url, String folderId, int waitSeconds) {
        log.info("submitting 123pan offline download: accountId={}, folderId={}", account.getId(), folderId);
        Submitted submitted = resolveAndSubmit(account, url, folderId);
        boolean singleFile = submitted.fileCount() == 1; // 单文件产物平铺在离线目录,多文件走目录形态(实测点)

        for (int i = 0; i < Math.max(1, waitSeconds); i++) {
            ObjectNode task = findTaskById(account, submitted.taskId());
            if (task != null) {
                int status = task.path("status").asInt(-1);
                if (status == 2) {
                    String name = StringUtils.firstNonBlank(task.path("upload_name").asText(""), task.path("name").asText(""));
                    if (StringUtils.isBlank(name)) {
                        throw new BadRequestException("123云盘离线下载任务成功但未返回名称");
                    }
                    log.info("123pan task completed: taskId={}, name={}", submitted.taskId(), name);
                    // infoHash 位存任务 id:清理侧 taskStatus/deleteTask 直接用它,零匹配成本
                    return new TaskResult(name, submitted.taskId(), !singleFile);
                }
                if (status == 1) {
                    throw new BadRequestException("task failed: 123云盘离线下载任务失败");
                }
            }
            sleepOneSecond();
        }
        throw new OfflineTaskPendingException(
                "123云盘离线下载任务未在" + Math.max(1, waitSeconds) + "秒内完成", submitted.taskId());
    }

    private record Submitted(String taskId, int fileCount) {
    }

    /** 两步提交:resolve 拿资源 id 与文件清单 → submit 建任务,返回任务 id 与文件数。 */
    private Submitted resolveAndSubmit(DriverAccount account, String url, String folderId) {
        ObjectNode resolveResp = exchange(account, RESOLVE_URL, HttpMethod.POST, "{\"urls\":" + quote(url) + "}");
        ensureCode(resolveResp, "123网盘离线链接解析失败");
        var first = resolveResp.path("data").path("list").path(0);
        if (first.isMissingNode() || first.path("result").asInt(-1) != 0) {
            throw new BadRequestException("task failed: "
                    + StringUtils.defaultIfBlank(first.path("err_msg").asText(""), "123网盘离线链接解析失败"));
        }
        long resourceId = first.path("id").asLong(0);
        List<Long> fileIds = new ArrayList<>();
        for (var file : first.path("files")) {
            long id = file.path("id").asLong(0);
            if (id > 0) {
                fileIds.add(id);
            }
        }
        if (resourceId == 0 || fileIds.isEmpty()) {
            throw new BadRequestException("123网盘离线链接解析失败: 资源或文件清单为空");
        }

        long uploadDir = parseFolderId(folderId);
        String selectIds = fileIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        String body = "{\"resource_list\":[{\"resource_id\":" + resourceId
                + ",\"select_file_id\":[" + selectIds + "]}],\"upload_dir\":" + uploadDir + "}";
        ObjectNode submitResp = exchange(account, SUBMIT_URL, HttpMethod.POST, body);
        ensureCode(submitResp, "123网盘离线任务提交失败");
        var taskInfo = submitResp.path("data").path("task_list").path(0);
        if (taskInfo.isMissingNode() || taskInfo.path("result").asInt(-1) != 0) {
            throw new BadRequestException("task failed: 123网盘离线任务提交失败");
        }
        long taskId = taskInfo.path("task_id").asLong(0);
        if (taskId == 0) {
            throw new BadRequestException("123网盘离线任务提交失败: 未返回任务id");
        }
        return new Submitted(String.valueOf(taskId), fileIds.size());
    }

    @Override
    public QuotaResult getQuota(DriverAccount account) {
        return QuotaResult.unsupported();
    }

    /** 活体检查:按任务 id(缺失按产物名)翻页对账任务列表,映射 123 状态码。 */
    @Override
    public TaskStatus taskStatus(DriverAccount account, String infoHash, String taskName) {
        ObjectNode task = findTask(account, infoHash, taskName);
        if (task == null) {
            return TaskStatus.ABSENT;
        }
        return mapTaskStatus(task.path("status").asInt(-1));
    }

    static TaskStatus mapTaskStatus(int status) {
        return switch (status) {
            case 2 -> TaskStatus.SUCCEEDED;
            case 1 -> TaskStatus.FAILED;
            case 0, 3 -> TaskStatus.RUNNING; // 3 = 重试中
            default -> TaskStatus.ABSENT; // 未知状态按查无处理(保守:不驱动删除)
        };
    }

    /** 删除任务记录(123 无「连文件删」参数,文件由清理调度经 AList 删);任务查无 = 幂等成功。 */
    @Override
    public void deleteTask(DriverAccount account, String infoHash, String taskName, boolean deleteFiles) {
        String taskId = resolveTaskId(account, infoHash, taskName);
        if (StringUtils.isBlank(taskId)) {
            log.info("123pan offline task not found (id={}, name={}), nothing to delete", infoHash, taskName);
            return;
        }
        try {
            ObjectNode result = exchange(account, TASK_DELETE_URL, HttpMethod.POST, "{\"task_ids\":[" + taskId + "]}");
            ensureCode(result, "删除123网盘离线任务失败");
        } catch (BadRequestException e) {
            if (findTask(account, taskId, null) == null) {
                log.info("123pan offline task {} already gone, treat delete as success", taskId);
                return;
            }
            throw e;
        }
        log.info("123pan offline task {} deleted", taskId);
    }

    private String resolveTaskId(DriverAccount account, String infoHash, String taskName) {
        if (StringUtils.isNotBlank(infoHash) && infoHash.matches("\\d+")) {
            return infoHash;
        }
        ObjectNode task = findTask(account, null, taskName);
        return task == null ? null : task.path("task_id").asText("");
    }

    private ObjectNode findTaskById(DriverAccount account, String taskId) {
        return findTask(account, taskId, null);
    }

    /** 任务列表翻页定位:优先任务 id,缺失按产物名兜底(ed2k/旧行)。 */
    private ObjectNode findTask(DriverAccount account, String taskId, String taskName) {
        boolean byId = StringUtils.isNotBlank(taskId) && taskId.matches("\\d+");
        for (int page = 1; page <= 50; page++) {
            ObjectNode list = exchange(account, TASK_LIST_URL, HttpMethod.POST,
                    "{\"current_page\":" + page + ",\"page_size\":" + TASK_LIST_PAGE_SIZE
                            + ",\"status_arr\":[0,1,2,3]}");
            ensureCode(list, "查询123网盘离线任务失败");
            var items = list.path("data").path("list");
            if (!items.isArray() || items.isEmpty()) {
                return null;
            }
            for (var item : items) {
                if (byId && taskId.equals(item.path("task_id").asText(""))) {
                    return (ObjectNode) item;
                }
                if (!byId && StringUtils.isNotBlank(taskName)
                        && taskName.equals(item.path("upload_name").asText(item.path("name").asText("")))) {
                    return (ObjectNode) item;
                }
            }
            int total = list.path("data").path("total").asInt(0);
            if (total <= 0 || page * TASK_LIST_PAGE_SIZE >= total || items.size() < TASK_LIST_PAGE_SIZE) {
                return null;
            }
        }
        return null;
    }

    // ---------- 认证与请求(对齐 DriverAccountService.get123WebUserInfo 同款头族与重登) ----------

    private String requireToken(DriverAccount account) {
        if (StringUtils.isBlank(account.getToken())) {
            return login(account);
        }
        return account.getToken().trim();
    }

    private ObjectNode exchange(DriverAccount account, String url, HttpMethod method, String body) {
        String token = requireToken(account);
        ObjectNode json = doExchange(account, url, method, body, token);
        if (isUnauthorized(json)) {
            log.info("123pan token expired, re-login for accountId={}", account.getId());
            token = login(account);
            json = doExchange(account, url, method, body, token);
        }
        return json;
    }

    private ObjectNode doExchange(DriverAccount account, String url, HttpMethod method, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        headers.set(HttpHeaders.ORIGIN, "https://yun.123pan.com");
        headers.set(HttpHeaders.REFERER, "https://yun.123pan.com/");
        headers.set("platform", "android");
        headers.set("app-version", "70");
        headers.set("osversion", "Android_8.1.0");
        headers.set("devicetype", "M1810E5A");
        headers.set("devicename", "Xiaomi");
        headers.set("loginuuid", loginUuid(account));
        headers.set("x-channel", "1001");
        headers.set("x-app-version", "2.4.8");
        if (method == HttpMethod.POST) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        HttpEntity<?> entity = body != null ? new HttpEntity<>(body, headers) : new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(withAuthKey(url), method, entity, String.class);
        return parseJsonBody(response.getBody(), url);
    }

    /** 按账号稳定的设备标识(多账号共用 handler bean,不能实例级缓存)。 */
    private static String loginUuid(DriverAccount account) {
        return Utils.md5("pan123-offline-" + account.getId());
    }

    /** passport/mail 双形态重登(与 DriverAccountService.login123Web 同契约),成功后回写 account.token。 */
    private String login(DriverAccount account) {
        if (StringUtils.isBlank(account.getUsername()) || StringUtils.isBlank(account.getPassword())) {
            throw new BadRequestException("123网盘Token为空且账号未配置用户名密码,请先在网盘账号页保存账号");
        }
        boolean email = account.getUsername().contains("@");
        String body = "{\"" + (email ? "mail" : "passport") + "\":" + quote(account.getUsername())
                + ",\"password\":" + quote(account.getPassword()) + ",\"type\":" + (email ? 2 : 1) + "}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        headers.set(HttpHeaders.ORIGIN, "https://yun.123pan.com");
        headers.set(HttpHeaders.REFERER, "https://yun.123pan.com/");
        String url = SIGN_IN_URL + "?auth-key=" + authKey();
        ObjectNode json;
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            json = parseJsonBody(response.getBody(), url);
        } catch (HttpStatusCodeException e) {
            json = parseJsonBody(e.getResponseBodyAsString(), url);
        }
        if (json == null || (json.path("code").asInt(-1) != 0 && json.path("code").asInt(-1) != 200)) {
            throw new BadRequestException("123网盘登录失败: "
                    + (json == null ? "无响应" : json.path("message").asText("未知错误")));
        }
        String token = json.path("data").path("token").asText("");
        if (StringUtils.isBlank(token)) {
            throw new BadRequestException("123网盘登录成功但未返回token");
        }
        account.setToken(token);
        try {
            driverAccountRepository.save(account);
        } catch (Exception e) {
            log.warn("persist 123pan token failed: {}", e.getMessage());
        }
        log.info("123pan re-login succeeded for accountId={}", account.getId());
        return token;
    }

    private static boolean isUnauthorized(ObjectNode json) {
        if (json == null) {
            return false;
        }
        if (json.path("code").asInt(0) == 401) {
            return true;
        }
        String message = json.path("message").asText("");
        return message.contains("未登录") || message.contains("token") && message.contains("失效");
    }

    private static void ensureCode(ObjectNode json, String message) {
        if (json == null) {
            throw new BadRequestException(message);
        }
        int code = json.path("code").asInt(-1);
        if (code != 0 && code != 200) {
            throw new BadRequestException("task failed: " + StringUtils.defaultIfBlank(json.path("message").asText(), message));
        }
    }

    private static ObjectNode parseJsonBody(String body, String url) {
        if (StringUtils.isBlank(body)) {
            throw new BadRequestException("123网盘接口返回空响应: " + url);
        }
        try {
            return (ObjectNode) new ObjectMapper().readTree(body);
        } catch (Exception e) {
            String snippet = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            throw new BadRequestException("123网盘接口返回非JSON响应: " + snippet, e);
        }
    }

    private static String withAuthKey(String url) {
        return url + (url.contains("?") ? "&" : "?") + "auth-key=" + authKey();
    }

    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

    /** 对齐内嵌 AList 123Pan 驱动 generateAuthKey:秒级时间戳-9位随机-32位hex。 */
    private static String authKey() {
        int random = SECURE_RANDOM.nextInt(900000000) + 100000000;
        return System.currentTimeMillis() / 1000 + "-" + random + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static long parseFolderId(String folderId) {
        try {
            return Long.parseLong(folderId.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("123网盘目录ID无效: " + folderId);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String requireParentFolderId(DriverAccount account) {
        if (StringUtils.isBlank(account.getFolder())) {
            throw new BadRequestException("123网盘账号目录ID不能为空");
        }
        return account.getFolder().trim();
    }

    private static void sleepOneSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("123云盘离线下载任务被中断", e);
        }
    }
}
