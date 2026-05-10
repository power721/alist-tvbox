package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class OfflineDownloadService {
    static final String SETTING_NAME = "offline_download_config";
    static final String DRIVER_TYPE = "PAN115";
    static final String OFFLINE_DIR = "/alist-tvbox-offline";

    public record ConfigRequest(boolean enabled, String driverType, Integer accountId) {
    }

    public record ConfigResponse(boolean enabled, String driverType, Integer accountId, String folder) {
    }

    public record DownloadRequest(String url) {
    }

    private final SettingRepository settingRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final AListLocalService aListLocalService;
    private final AccountService accountService;
    private final TvBoxService tvBoxService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OfflineDownloadService(SettingRepository settingRepository,
                                  DriverAccountRepository driverAccountRepository,
                                  AListLocalService aListLocalService,
                                  AccountService accountService,
                                  TvBoxService tvBoxService,
                                  RestTemplate restTemplate,
                                  ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.aListLocalService = aListLocalService;
        this.accountService = accountService;
        this.tvBoxService = tvBoxService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public ConfigResponse getConfig() {
        Optional<Setting> setting = settingRepository.findById(SETTING_NAME);
        if (setting.isEmpty() || StringUtils.isBlank(setting.get().getValue())) {
            return new ConfigResponse(false, DRIVER_TYPE, null, "");
        }

        ConfigRequest config = parseConfig(setting.get().getValue());
        String folder = "";
        if (config.accountId() != null) {
            folder = driverAccountRepository.findById(config.accountId())
                    .map(DriverAccount::getFolder)
                    .orElse("");
        }
        return new ConfigResponse(config.enabled(), defaultDriverType(config.driverType()), config.accountId(), folder);
    }

    public ConfigResponse saveConfig(ConfigRequest request) {
        validateConfig(request);
        String driverType = defaultDriverType(request.driverType());
        ConfigRequest normalized = new ConfigRequest(request.enabled(), driverType, request.accountId());
        settingRepository.save(new Setting(SETTING_NAME, writeConfig(normalized)));
        if (!normalized.enabled()) {
            return new ConfigResponse(false, driverType, normalized.accountId(), "");
        }

        DriverAccount account = get115Account(normalized.accountId());
        aListLocalService.set115TempDir(buildOfflinePath(account.getFolder()));
        return new ConfigResponse(true, driverType, account.getId(), account.getFolder());
    }

    public Object download(DownloadRequest request) {
        validateUrl(request.url());
        ConfigRequest config = loadEnabledConfig();
        DriverAccount account = get115Account(config.accountId());
        String folder = account.getFolder();
        if (StringUtils.isBlank(folder)) {
            throw new BadRequestException("115账号挂载目录不能为空");
        }
        String path = buildOfflinePath(folder);
        HttpEntity<Map<String, Object>> entity = createAuthorizedEntity(Map.of(
                "urls", List.of(request.url()),
                "path", path,
                "tool", "115 Cloud",
                "delete_policy", "delete_never"
        ));
        Map<String, Object> response = restTemplate.postForObject("/api/fs/add_offline_download", entity, Map.class);
        String taskId = extractTaskId(response);
        for (int i = 0; i < 10; i++) {
            Map<String, Object> taskInfo = restTemplate.postForObject("/api/task/offline_download/info?tid=" + taskId,
                    createAuthorizedEntity(null), Map.class);
            Map<String, Object> data = getData(taskInfo);
            int state = ((Number) data.getOrDefault("state", -1)).intValue();
            if (state == 2) {
                String name = Objects.toString(data.get("name"), "");
                if (StringUtils.isBlank(name)) {
                    throw new BadRequestException("离线下载任务成功但未返回名称");
                }
                return tvBoxService.getDetail("", "1$" + path + "/" + name + "/~playlist");
            }
            if (state == 5 || state == 6 || state == 7) {
                String error = Objects.toString(data.get("error"), "");
                String status = Objects.toString(data.get("status"), "");
                throw new BadRequestException("task failed: " + (StringUtils.isNotBlank(error) ? error : status));
            }
            sleepOneSecond();
        }
        throw new BadRequestException("离线下载任务未在10秒内完成");
    }

    public void syncSelectedAccountTempDir(Integer accountId) {
        Optional<Setting> setting = settingRepository.findById(SETTING_NAME);
        if (setting.isEmpty() || StringUtils.isBlank(setting.get().getValue())) {
            return;
        }
        ConfigRequest config = parseConfig(setting.get().getValue());
        if (!config.enabled() || !Objects.equals(config.accountId(), accountId)) {
            return;
        }
        DriverAccount account = get115Account(accountId);
        if (StringUtils.isBlank(account.getFolder())) {
            return;
        }
        aListLocalService.set115TempDir(buildOfflinePath(account.getFolder()));
    }

    private ConfigRequest loadEnabledConfig() {
        Optional<Setting> setting = settingRepository.findById(SETTING_NAME);
        if (setting.isEmpty() || StringUtils.isBlank(setting.get().getValue())) {
            throw new BadRequestException("离线下载未开启");
        }
        ConfigRequest config = parseConfig(setting.get().getValue());
        if (!config.enabled()) {
            throw new BadRequestException("离线下载未开启");
        }
        if (!DRIVER_TYPE.equals(defaultDriverType(config.driverType()))) {
            throw new BadRequestException("当前仅支持115云盘离线下载");
        }
        if (config.accountId() == null) {
            throw new BadRequestException("未配置115账号");
        }
        return config;
    }

    private void validateConfig(ConfigRequest request) {
        String driverType = defaultDriverType(request.driverType());
        if (request.enabled()) {
            if (!DRIVER_TYPE.equals(driverType)) {
                throw new BadRequestException("当前仅支持115云盘离线下载");
            }
            if (request.accountId() == null) {
                throw new BadRequestException("请选择115账号");
            }
            DriverAccount account = get115Account(request.accountId());
            if (StringUtils.isBlank(account.getFolder())) {
                throw new BadRequestException("115账号挂载目录不能为空");
            }
        }
    }

    private DriverAccount get115Account(Integer accountId) {
        DriverAccount account = driverAccountRepository.findById(accountId)
                .orElseThrow(() -> new BadRequestException("115账号不存在"));
        if (account.getType() != DriverType.PAN115) {
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

    private String buildOfflinePath(String folder) {
        String value = folder.endsWith("/") ? folder.substring(0, folder.length() - 1) : folder;
        return value + OFFLINE_DIR;
    }

    private String defaultDriverType(String driverType) {
        return StringUtils.isBlank(driverType) ? DRIVER_TYPE : driverType;
    }

    private ConfigRequest parseConfig(String value) {
        try {
            return objectMapper.readValue(value, ConfigRequest.class);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("离线下载配置无效", e);
        }
    }

    private String writeConfig(ConfigRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("保存离线下载配置失败", e);
        }
    }

    private HttpEntity<Map<String, Object>> createAuthorizedEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, accountService.login());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(Map<String, Object> response) {
        if (response == null || !(response.get("data") instanceof Map<?, ?> data)) {
            throw new BadRequestException("AList返回数据无效");
        }
        return (Map<String, Object>) data;
    }

    @SuppressWarnings("unchecked")
    private String extractTaskId(Map<String, Object> response) {
        Map<String, Object> data = getData(response);
        Object tasksObject = data.get("tasks");
        if (!(tasksObject instanceof List<?> tasks) || tasks.isEmpty()) {
            throw new BadRequestException("AList未返回离线下载任务");
        }
        Object first = tasks.getFirst();
        if (!(first instanceof Map<?, ?> task) || task.get("id") == null) {
            throw new BadRequestException("AList未返回离线下载任务");
        }
        return task.get("id").toString();
    }
    private void sleepOneSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("离线下载轮询被中断", e);
        }
    }
}
