package cn.har01d.alist_tvbox.service.offline;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.storage.Storage;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class Pan115OfflineDownloadHandler implements OfflineDownloadHandler {
    private static final String SPACE_URL = "https://115.com/?ct=clouddownload&ac=space";
    private static final String ADD_TASK_URL = "https://clouddownload.115.com/web/?ac=add_task_urls";
    private static final String QUOTA_URL = "https://clouddownload.115.com/web/?ac=get_quota_package_info&uid=%s";
    private static final int TASK_LIST_PAGE_SIZE = 1000;
    private static final String FILE_LIST_URL = "https://webapi.115.com/files?aid=1&cid=%s&offset=0&limit=20&type=0&show_dir=1&fc_mix=0&natsort=1&count_folders=1&format=json&custom_order=0";
    private static final String FILE_ADD_URL = "https://webapi.115.com/files/add";
    private static final Pattern UID_PATTERN = Pattern.compile("UID=(\\d+)");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public Pan115OfflineDownloadHandler(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public DriverType getDriverType() {
        return DriverType.PAN115;
    }

    @Override
    public String ensureOfflineFolder(DriverAccount account) {
        String cookie = requireCookie(account);
        String parentId = requireParentFolderId(account);
        ObjectNode list = exchange(String.format(FILE_LIST_URL, parentId), HttpMethod.GET, cookie, "https://115.com/", null);
        ensureState(list, "查询115目录失败");
        if (list.has("data") && list.get("data").isArray()) {
            for (var item : list.get("data")) {
                if ("alist-tvbox-offline".equals(item.path("n").asText())) {
                    return StringUtils.firstNonBlank(item.path("file_id").asText(), item.path("cid").asText());
                }
            }
        }

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("pid", parentId);
        form.add("cname", "alist-tvbox-offline");
        ObjectNode body = exchangeMultipart(FILE_ADD_URL, cookie, "https://115.com/", form);
        ensureState(body, "创建115离线下载目录失败");
        String folderId = StringUtils.firstNonBlank(body.path("file_id").asText(), body.path("cid").asText());
        if (StringUtils.isBlank(folderId)) {
            throw new BadRequestException("创建115离线下载目录失败");
        }
        return folderId;
    }

    @Override
    public TaskResult submitAndWait(DriverAccount account, String url, String folderId) {
        String cookie = requireCookie(account);
        String uid = extractUid(cookie);

        log.info("submitting 115 offline download: accountId={}, folderId={}", account.getId(), folderId);

        ObjectNode space = exchange(SPACE_URL, HttpMethod.GET, cookie, "https://115.com/", null);
        log.debug("space: {}", space);
        ensureState(space, "获取115离线下载签名失败");
        String sign = space.path("sign").asText("");
        long time = space.path("time").asLong(0);
        if (StringUtils.isBlank(sign) || time <= 0) {
            throw new BadRequestException("115离线下载签名无效");
        }

        String body = buildAddTaskBody(sign, time, uid, url, folderId);
        ObjectNode addTask = exchange(ADD_TASK_URL, HttpMethod.POST, cookie, "https://115.com/", body);
        log.debug("add task: {}", addTask);
        boolean duplicateTask = isDuplicateTask(addTask);
        if (duplicateTask) {
            log.info("115 offline download task already exists: accountId={}", account.getId());
        }
        if (!duplicateTask) {
            ensureState(addTask, "提交115离线下载任务失败");
        }
        if (!duplicateTask && addTask.path("errno").asInt(0) != 0) {
            String message = StringUtils.firstNonBlank(addTask.path("error_msg").asText(), "115离线下载任务提交失败");
            log.info("115 offline download task submit failed: accountId={}, message={}", account.getId(), message);
            throw new BadRequestException("task failed: " + message);
        }

        for (int i = 0; i < 10; i++) {
            ObjectNode task = findTask(url, cookie, duplicateTask ? 2 : 1);
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
                log.info("115 offline download task completed: accountId={}, name={}", account.getId(), name);
                return new TaskResult(name, task.path("info_hash").asText(""), task.path("file_category").asInt(1) == 0);
            }

            if (status == -1 || status == 4) {
                String message = StringUtils.firstNonBlank(task.path("errtype").asText(), task.path("name").asText(), "115离线下载任务失败");
                log.info("115 offline download task failed: accountId={}, message={}", account.getId(), message);
                throw new BadRequestException("task failed: " + message);
            }

            sleepOneSecond();
        }

        throw new BadRequestException("离线下载任务未在10秒内完成");
    }

    @Override
    public QuotaResult getQuota(DriverAccount account) {
        String cookie = requireCookie(account);
        String uid = extractUid(cookie);
        ObjectNode quota = exchange(String.format(QUOTA_URL, uid), HttpMethod.POST, cookie, "https://115.com/", "");
        return new QuotaResult(true,
                String.format("本月配额：剩%d/总%d个", quota.path("surplus").asInt(0), quota.path("count").asInt(0)));
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

    private String requireParentFolderId(DriverAccount account) {
        if (StringUtils.isBlank(account.getFolder())) {
            throw new BadRequestException("115账号目录ID不能为空");
        }
        return account.getFolder().trim();
    }

    private ObjectNode exchange(String url, HttpMethod method, String cookie, String referer, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        headers.set(HttpHeaders.REFERER, referer);
        headers.set(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + ", text/html, */*");
        if (method == HttpMethod.POST) {
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        }
        HttpEntity<?> entity = method == HttpMethod.POST ? new HttpEntity<>(body, headers) : new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, method, entity, String.class);
        return parseJsonBody(response.getBody(), url);
    }

    private ObjectNode exchangeMultipart(String url, String cookie, String referer, MultiValueMap<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        headers.set(HttpHeaders.REFERER, referer);
        headers.set(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
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

    private void sleepOneSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("离线下载任务被中断", e);
        }
    }
}
