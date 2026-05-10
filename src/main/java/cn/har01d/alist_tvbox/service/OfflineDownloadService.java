package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigRequest;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigDto;
import cn.har01d.alist_tvbox.dto.OfflineDownloadQuotaResponse;
import cn.har01d.alist_tvbox.dto.OfflineDownloadRequest;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTask;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTaskRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.storage.Storage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.security.MessageDigest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class OfflineDownloadService {
    static final String SETTING_NAME = "offline_download_config";
    static final String DEFAULT_DRIVER_TYPE = DriverType.PAN115.name();
    static final String OFFLINE_DIR_NAME = "alist-tvbox-offline";
    private static final String SPACE_URL = "https://115.com/?ct=clouddownload&ac=space";
    private static final String ADD_TASK_URL = "https://clouddownload.115.com/web/?ac=add_task_urls";
    private static final String QUOTA_URL = "https://clouddownload.115.com/web/?ac=get_quota_package_info&uid=%s";
    private static final int TASK_LIST_PAGE_SIZE = 1000;
    private static final String FILE_LIST_URL = "https://webapi.115.com/files?aid=1&cid=%s&offset=0&limit=20&type=0&show_dir=1&fc_mix=0&natsort=1&count_folders=1&format=json&custom_order=0";
    private static final String FILE_ADD_URL = "https://webapi.115.com/files/add";
    private static final Pattern UID_PATTERN = Pattern.compile("UID=(\\d+)");
    private static final String STATUS_COMPLETED = "COMPLETED";

    private record StoredConfig(boolean enabled, String driverType, Integer accountId, String offlineFolderId) {
    }

    private record DownloadTarget(String path, boolean folder) {
    }

    private final SettingRepository settingRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final TvBoxService tvBoxService;
    private final SubscriptionService subscriptionService;
    private final OfflineDownloadTaskRepository offlineDownloadTaskRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OfflineDownloadService(SettingRepository settingRepository,
                                  DriverAccountRepository driverAccountRepository,
                                  @Lazy TvBoxService tvBoxService,
                                  SubscriptionService subscriptionService,
                                  OfflineDownloadTaskRepository offlineDownloadTaskRepository,
                                  RestTemplateBuilder builder,
                                  ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.tvBoxService = tvBoxService;
        this.subscriptionService = subscriptionService;
        this.offlineDownloadTaskRepository = offlineDownloadTaskRepository;
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
    }

    public OfflineDownloadConfigDto getConfig() {
        Optional<Setting> setting = settingRepository.findById(SETTING_NAME);
        if (setting.isEmpty() || StringUtils.isBlank(setting.get().getValue())) {
            return new OfflineDownloadConfigDto(false, DEFAULT_DRIVER_TYPE, null, "");
        }

        StoredConfig config = parseConfig(setting.get().getValue());
        String folder = "";
        if (config.accountId() != null) {
            folder = driverAccountRepository.findById(config.accountId())
                    .map(Storage::getMountPath)
                    .orElse("");
        }
        return new OfflineDownloadConfigDto(config.enabled(), normalizeDriverType(config.driverType()), config.accountId(), folder);
    }

    public OfflineDownloadConfigDto saveConfig(OfflineDownloadConfigRequest request) {
        validateConfig(request);
        String driverType = normalizeDriverType(request.driverType());
        StoredConfig normalized = new StoredConfig(request.enabled(), driverType, request.accountId(), "");
        if (!normalized.enabled()) {
            settingRepository.save(new Setting(SETTING_NAME, writeConfig(normalized)));
            log.info("offline download config disabled");
            return new OfflineDownloadConfigDto(false, driverType, normalized.accountId(), "");
        }

        DriverAccount account = getAccount(normalized.accountId(), driverType);
        String offlineFolderId = ensureOfflineFolder(account);
        settingRepository.save(new Setting(SETTING_NAME, writeConfig(new StoredConfig(true, driverType, account.getId(), offlineFolderId))));
        log.info("offline download config saved: driverType={}, accountId={}, offlineFolderId={}",
                driverType, account.getId(), offlineFolderId);
        return new OfflineDownloadConfigDto(true, driverType, account.getId(), Storage.getMountPath(account));
    }

    public OfflineDownloadQuotaResponse getQuota() {
        StoredConfig config = loadEnabledConfig();
        DriverAccount account = getAccount(config.accountId(), normalizeDriverType(config.driverType()));
        String cookie = requireCookie(account);
        String uid = extractUid(cookie);
        ObjectNode quota = exchange(String.format(QUOTA_URL, uid), HttpMethod.POST, cookie, "https://115.com/", "");
        return new OfflineDownloadQuotaResponse(
                quota.path("surplus").asInt(0),
                quota.path("count").asInt(0),
                quota.path("used").asInt(0)
        );
    }

    public Object download(OfflineDownloadRequest request, String ac) {
        DownloadTarget target = downloadTarget(request);
        String targetPath = target.path();
        if (target.folder()) {
            targetPath += "/~playlist";
        }
        return tvBoxService.getDetail(ac, "1$" + targetPath);
    }

    public String downloadPath(OfflineDownloadRequest request) {
        return downloadTarget(request).path();
    }

    private DownloadTarget downloadTarget(OfflineDownloadRequest request) {
        validateUrl(request.url());
        StoredConfig config = loadEnabledConfig();
        DriverAccount account = getAccount(config.accountId(), normalizeDriverType(config.driverType()));
        String urlHash = hashUrl(request.url());
        Optional<OfflineDownloadTask> localTask = offlineDownloadTaskRepository
                .findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(account.getId(), urlHash);
        if (localTask.isPresent() && STATUS_COMPLETED.equals(localTask.get().getStatus()) && StringUtils.isNotBlank(localTask.get().getTargetPath())) {
            return new DownloadTarget(resolveTargetPath(account, localTask.get()), localTask.get().isFolder());
        }

        String cookie = requireCookie(account);
        String uid = extractUid(cookie);
        String pathId = requireOfflineFolderId(config);
        String mountPath = Storage.getMountPath(account);
        log.info("submitting offline download: accountId={}, pathId={}, urlHash={}", account.getId(), pathId, urlHash);

        ObjectNode space = exchange(SPACE_URL, HttpMethod.GET, cookie, "https://115.com/", null);
        log.debug("space: {}", space);
        ensureState(space, "获取115离线下载签名失败");
        String sign = text(space, "sign");
        long time = number(space, "time");
        if (StringUtils.isBlank(sign) || time <= 0) {
            throw new BadRequestException("115离线下载签名无效");
        }

        String body = buildAddTaskBody(sign, time, uid, request.url(), pathId);
        ObjectNode addTask = exchange(ADD_TASK_URL, HttpMethod.POST, cookie, "https://115.com/", body);
        log.debug("add task: {}", addTask);
        boolean duplicateTask = isDuplicateTask(addTask);
        if (duplicateTask) {
            log.info("offline download task already exists: accountId={}, urlHash={}", account.getId(), urlHash);
        }
        if (!duplicateTask) {
            ensureState(addTask, "提交115离线下载任务失败");
        }
        if (!duplicateTask && addTask.path("errno").asInt(0) != 0) {
            String message = StringUtils.firstNonBlank(addTask.path("error_msg").asText(), "115离线下载任务提交失败");
            log.info("offline download task submit failed: accountId={}, urlHash={}, message={}", account.getId(), urlHash, message);
            throw new BadRequestException("task failed: " + message);
        }

        for (int i = 0; i < 10; i++) {
            ObjectNode task = findTask(request.url(), cookie, duplicateTask ? 2 : 1);
            if (task == null) {
                sleepOneSecond();
                continue;
            }

            int status = task.path("status").asInt(-1);
            if (status == 2) {
                String name = task.path("name").asText("");
                if (StringUtils.isBlank(name)) {
                    throw new BadRequestException("离线下载任务成功但未返回名称");
                }
                String targetPath = buildTargetPath(account, name);
                saveTask(account.getId(), urlHash, task, targetPath);
                log.info("offline download task completed: accountId={}, urlHash={}, targetPath={}", account.getId(), urlHash, targetPath);
                return new DownloadTarget(targetPath, isFolderTask(task));
            }

            if (status == -1 || status == 4) {
                String message = StringUtils.firstNonBlank(task.path("errtype").asText(), task.path("name").asText(), "115离线下载任务失败");
                log.info("offline download task failed: accountId={}, urlHash={}, message={}", account.getId(), urlHash, message);
                throw new BadRequestException("task failed: " + message);
            }

            sleepOneSecond();
        }

        throw new BadRequestException("离线下载任务未在10秒内完成");
    }

    public void syncSelectedAccountTempDir(Integer accountId) {
        refreshOfflineFolderId(accountId);
    }

    public void syncConfiguredTempDirOnStartup() {
        try {
            StoredConfig config = loadEnabledConfig();
            refreshOfflineFolderId(config.accountId());
        } catch (BadRequestException | RestClientException e) {
            log.debug("skip syncing offline folder on startup: {}", e.getMessage());
        }
    }

    private StoredConfig loadEnabledConfig() {
        Optional<Setting> setting = settingRepository.findById(SETTING_NAME);
        if (setting.isEmpty() || StringUtils.isBlank(setting.get().getValue())) {
            throw new BadRequestException("离线下载未开启");
        }
        StoredConfig config = parseConfig(setting.get().getValue());
        if (!config.enabled()) {
            throw new BadRequestException("离线下载未开启");
        }
        if (config.accountId() == null) {
            throw new BadRequestException("未配置离线下载账号");
        }
        normalizeDriverType(config.driverType());
        return config;
    }

    private void validateConfig(OfflineDownloadConfigRequest request) {
        String driverType = normalizeDriverType(request.driverType());
        if (!request.enabled()) {
            return;
        }
        if (request.accountId() == null) {
            throw new BadRequestException("请选择离线下载账号");
        }
        DriverAccount account = getAccount(request.accountId(), driverType);
        if (StringUtils.isBlank(account.getName()) || StringUtils.isBlank(Storage.getMountPath(account))) {
            throw new BadRequestException("离线下载账号挂载目录不能为空");
        }
    }

    private DriverAccount getAccount(Integer accountId, String driverType) {
        DriverAccount account = driverAccountRepository.findById(accountId)
                .orElseThrow(() -> new BadRequestException("离线下载账号不存在"));
        if (!DriverType.PAN115.name().equals(driverType) || account.getType() != DriverType.PAN115) {
            throw new BadRequestException("当前仅支持115云盘离线下载");
        }
        return account;
    }

    private void validateUrl(String url) {
        if (StringUtils.isBlank(url)) {
            throw new BadRequestException("离线下载链接不能为空");
        }
        String value = url.toLowerCase();
        if (!(value.startsWith("magnet:") || value.startsWith("ed2k:") || value.startsWith("http:") || value.startsWith("https:"))) {
            throw new BadRequestException("不支持的离线下载链接");
        }
    }

    private String normalizeDriverType(String driverType) {
        String value = StringUtils.isBlank(driverType) ? DEFAULT_DRIVER_TYPE : driverType;
        if (!DriverType.PAN115.name().equals(value)) {
            throw new BadRequestException("当前仅支持115云盘离线下载");
        }
        return value;
    }

    private StoredConfig parseConfig(String value) {
        try {
            return objectMapper.readValue(value, StoredConfig.class);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("离线下载配置无效", e);
        }
    }

    private String writeConfig(StoredConfig request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("保存离线下载配置失败", e);
        }
    }

    private void refreshOfflineFolderId(Integer accountId) {
        Optional<Setting> setting = settingRepository.findById(SETTING_NAME);
        if (setting.isEmpty() || StringUtils.isBlank(setting.get().getValue())) {
            return;
        }
        StoredConfig config = parseConfig(setting.get().getValue());
        if (!config.enabled() || config.accountId() == null || !Objects.equals(config.accountId(), accountId)) {
            return;
        }

        String driverType = normalizeDriverType(config.driverType());
        DriverAccount account = getAccount(config.accountId(), driverType);
        String offlineFolderId = ensureOfflineFolder(account);
        settingRepository.save(new Setting(SETTING_NAME, writeConfig(new StoredConfig(true, driverType, account.getId(), offlineFolderId))));
    }

    private String requireCookie(DriverAccount account) {
        if (StringUtils.isBlank(account.getCookie())) {
            throw new BadRequestException("115账号Cookie不能为空");
        }
        return account.getCookie().trim();
    }

    private String extractUid(String cookie) {
        Matcher matcher = UID_PATTERN.matcher(cookie);
        if (!matcher.find()) {
            throw new BadRequestException("115账号Cookie缺少UID");
        }
        return matcher.group(1);
    }

    private String requireOfflineFolderId(StoredConfig config) {
        if (StringUtils.isBlank(config.offlineFolderId())) {
            throw new BadRequestException("115离线下载目录ID不能为空");
        }
        return config.offlineFolderId().trim();
    }

    private ObjectNode exchange(String url, HttpMethod method, String cookie, String referer, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        headers.set(HttpHeaders.REFERER, referer);
        headers.set(HttpHeaders.USER_AGENT, cn.har01d.alist_tvbox.util.Constants.USER_AGENT);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + ", text/html, */*");
        if (method == HttpMethod.POST) {
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        }
        HttpEntity<?> entity = method == HttpMethod.POST ? new HttpEntity<>(body, headers) : new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, method, entity, String.class);
        return parseJsonBody(response.getBody(), url);
    }

    private String ensureOfflineFolder(DriverAccount account) {
        String cookie = requireCookie(account);
        String parentId = requireParentFolderId(account);
        ObjectNode list = exchange(String.format(FILE_LIST_URL, parentId), HttpMethod.GET, cookie, "https://115.com/", null);
        ensureState(list, "查询115目录失败");
        if (list.has("data") && list.get("data").isArray()) {
            for (var item : list.get("data")) {
                if (OFFLINE_DIR_NAME.equals(item.path("n").asText())) {
                    return StringUtils.firstNonBlank(item.path("file_id").asText(), item.path("cid").asText());
                }
            }
        }

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("pid", parentId);
        form.add("cname", OFFLINE_DIR_NAME);
        ObjectNode body = exchangeMultipart(FILE_ADD_URL, cookie, "https://115.com/", form);
        ensureState(body, "创建115离线下载目录失败");
        String folderId = StringUtils.firstNonBlank(body.path("file_id").asText(), body.path("cid").asText());
        if (StringUtils.isBlank(folderId)) {
            throw new BadRequestException("创建115离线下载目录失败");
        }
        return folderId;
    }

    private ObjectNode exchangeMultipart(String url, String cookie, String referer, MultiValueMap<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        headers.set(HttpHeaders.REFERER, referer);
        headers.set(HttpHeaders.USER_AGENT, cn.har01d.alist_tvbox.util.Constants.USER_AGENT);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + ", text/html, */*");
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        return parseJsonBody(response.getBody(), url);
    }

    private ObjectNode parseJsonBody(String body, String url) {
        if (StringUtils.isBlank(body)) {
            throw new BadRequestException("115接口返回空响应: " + url);
        }
        try {
            return (ObjectNode) objectMapper.readTree(body);
        } catch (Exception e) {
            String snippet = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            throw new BadRequestException("115接口返回非JSON响应: " + snippet, e);
        }
    }

    private String requireParentFolderId(DriverAccount account) {
        if (StringUtils.isBlank(account.getFolder())) {
            throw new BadRequestException("115账号目录ID不能为空");
        }
        return account.getFolder().trim();
    }

    private void ensureState(ObjectNode node, String message) {
        if (node == null || !node.path("state").asBoolean(false)) {
            String error = node == null ? "" : StringUtils.firstNonBlank(node.path("error_msg").asText(), node.path("errtype").asText(), node.path("msg").asText());
            throw new BadRequestException("task failed: " + StringUtils.firstNonBlank(error, message));
        }
    }

    private String buildAddTaskBody(String sign, long time, String uid, String url, String pathId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("sign", sign);
        form.add("time", String.valueOf(time));
        form.add("uid", uid);
        form.add("url[0]", url);
        form.add("savepath", "");
        form.add("wp_path_id", pathId);
        return form.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(value -> encode(entry.getKey()) + "=" + encode(value)))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private ObjectNode findTask(String url, String cookie, int pages) {
        for (int page = 1; page <= pages; page++) {
            ObjectNode taskList = exchange(taskListUrl(page), HttpMethod.POST, cookie, "https://115.com/", "");
            ensureState(taskList, "查询115离线下载任务失败");
            ObjectNode task = findTaskInPage(taskList, url);
            if (task != null) {
                return task;
            }
        }
        return null;
    }

    private ObjectNode findTaskInPage(ObjectNode taskList, String url) {
        if (!taskList.has("tasks") || !taskList.get("tasks").isArray()) {
            return null;
        }
        for (var item : taskList.get("tasks")) {
            if (Objects.equals(item.path("url").asText(""), url)) {
                return (ObjectNode) item;
            }
        }
        return null;
    }

    private boolean isFolderTask(ObjectNode task) {
        return task.path("file_category").asInt(1) == 0;
    }

    private String taskListUrl(int page) {
        return "https://clouddownload.115.com/web/?ac=task_lists&page=" + page + "&page_size=" + TASK_LIST_PAGE_SIZE + "&stat=11";
    }

    private boolean isDuplicateTask(ObjectNode addTask) {
        if (addTask == null) {
            return false;
        }
        String message = StringUtils.firstNonBlank(addTask.path("error_msg").asText(), addTask.path("errtype").asText(), addTask.path("msg").asText());
        return StringUtils.contains(message, "已存在") || StringUtils.contains(message, "重复");
    }

    private String resolveTargetPath(DriverAccount account, OfflineDownloadTask task) {
        if (StringUtils.isNotBlank(task.getTaskName())) {
            return buildTargetPath(account, task.getTaskName());
        }
        return task.getTargetPath();
    }

    private String buildTargetPath(DriverAccount account, String taskName) {
        return Storage.getMountPath(account) + "/" + OFFLINE_DIR_NAME + "/" + taskName;
    }

    private void saveTask(Integer accountId, String urlHash, ObjectNode task, String targetPath) {
        OfflineDownloadTask entity = offlineDownloadTaskRepository
                .findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(accountId, urlHash)
                .orElseGet(OfflineDownloadTask::new);
        Instant now = Instant.now();
        if (entity.getCreatedTime() == null) {
            entity.setCreatedTime(now);
        }
        entity.setAccountId(accountId);
        entity.setUrlHash(urlHash);
        entity.setInfoHash(StringUtils.firstNonBlank(task.path("info_hash").asText(), entity.getInfoHash()));
        entity.setTargetPath(targetPath);
        entity.setTaskName(task.path("name").asText(entity.getTaskName()));
        entity.setStatus(STATUS_COMPLETED);
        entity.setFolder(isFolderTask(task));
        entity.setUpdatedTime(now);
        offlineDownloadTaskRepository.save(entity);
    }

    private String hashUrl(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(url.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BadRequestException("计算离线下载链接摘要失败", e);
        }
    }

    private String text(ObjectNode node, String field) {
        return node.path(field).asText("");
    }

    private long number(ObjectNode node, String field) {
        return node.path(field).asLong(0);
    }

    private void sleepOneSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("离线下载任务被中断", e);
        }
    }
}
