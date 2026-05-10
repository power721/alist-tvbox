package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.storage.Storage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

@Service
public class OfflineDownloadService {
    static final String SETTING_NAME = "offline_download_config";
    static final String DEFAULT_DRIVER_TYPE = "PAN115";
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
    private final Map<String, DriverConf> drivers = new HashMap<>();

    private record DriverConf(DriverType accountType, String tool, Consumer<String> tempDirSync) {
    }

    public OfflineDownloadService(SettingRepository settingRepository,
                                  DriverAccountRepository driverAccountRepository,
                                  AListLocalService aListLocalService,
                                  AccountService accountService,
                                  @Lazy TvBoxService tvBoxService,
                                  RestTemplateBuilder builder,
                                  ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.aListLocalService = aListLocalService;
        this.accountService = accountService;
        this.tvBoxService = tvBoxService;
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
        drivers.put(DriverType.PAN115.name(), new DriverConf(DriverType.PAN115, "115 Cloud", aListLocalService::set115TempDir));
        drivers.put(DriverType.THUNDER.name(), new DriverConf(DriverType.THUNDER, "ThunderBrowser", aListLocalService::setThunderBrowserTempDir));
    }

    public ConfigResponse getConfig() {
        Optional<Setting> setting = settingRepository.findById(SETTING_NAME);
        if (setting.isEmpty() || StringUtils.isBlank(setting.get().getValue())) {
            return new ConfigResponse(false, DEFAULT_DRIVER_TYPE, null, "");
        }

        ConfigRequest config = parseConfig(setting.get().getValue());
        String folder = "";
        if (config.accountId() != null) {
            folder = driverAccountRepository.findById(config.accountId())
                    .map(Storage::getMountPath)
                    .orElse("");
        }
        return new ConfigResponse(config.enabled(), normalizeDriverType(config.driverType()), config.accountId(), folder);
    }

    public ConfigResponse saveConfig(ConfigRequest request) {
        validateConfig(request);
        String driverType = normalizeDriverType(request.driverType());
        ConfigRequest normalized = new ConfigRequest(request.enabled(), driverType, request.accountId());
        settingRepository.save(new Setting(SETTING_NAME, writeConfig(normalized)));
        if (!normalized.enabled()) {
            return new ConfigResponse(false, driverType, normalized.accountId(), "");
        }

        DriverAccount account = getAccount(normalized.accountId(), driverType);
        String folder = Storage.getMountPath(account);
        syncTempDir(driverType, folder);
        return new ConfigResponse(true, driverType, account.getId(), folder);
    }

    public Object download(DownloadRequest request) {
        validateUrl(request.url());
        ConfigRequest config = loadEnabledConfig();
        String driverType = normalizeDriverType(config.driverType());
        DriverConf conf = getDriverConf(driverType);
        DriverAccount account = getAccount(config.accountId(), driverType);
        String folder = Storage.getMountPath(account);
        if (StringUtils.isBlank(folder)) {
            throw new BadRequestException(conf.accountType() == DriverType.PAN115 ? "115账号挂载目录不能为空" : "迅雷账号挂载目录不能为空");
        }
        String path = buildOfflinePath(folder);
        HttpEntity<Map<String, Object>> entity = createAuthorizedEntity(Map.of(
                "urls", List.of(request.url()),
                "path", path,
                "tool", conf.tool(),
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
        String driverType = normalizeDriverType(config.driverType());
        DriverAccount account = getAccount(accountId, driverType);
        String folder = Storage.getMountPath(account);
        if (StringUtils.isBlank(folder)) {
            return;
        }
        syncTempDir(driverType, folder);
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
        normalizeDriverType(config.driverType());
        if (config.accountId() == null) {
            throw new BadRequestException("未配置离线下载账号");
        }
        return config;
    }

    private void validateConfig(ConfigRequest request) {
        String driverType = normalizeDriverType(request.driverType());
        if (request.enabled()) {
            if (request.accountId() == null) {
                throw new BadRequestException("请选择离线下载账号");
            }
            DriverAccount account = getAccount(request.accountId(), driverType);
            if (StringUtils.isBlank(Storage.getMountPath(account))) {
                if (account.getType() == DriverType.PAN115) {
                    throw new BadRequestException("115账号挂载目录不能为空");
                }
                throw new BadRequestException("迅雷账号挂载目录不能为空");
            }
        }
    }

    private DriverAccount getAccount(Integer accountId, String driverType) {
        DriverAccount account = driverAccountRepository.findById(accountId)
                .orElseThrow(() -> new BadRequestException("离线下载账号不存在"));
        DriverConf conf = getDriverConf(driverType);
        if (account.getType() != conf.accountType()) {
            throw new BadRequestException("离线下载账号类型和所选网盘类型不匹配");
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

    private String normalizeDriverType(String driverType) {
        String value = StringUtils.isBlank(driverType) ? DEFAULT_DRIVER_TYPE : driverType;
        getDriverConf(value);
        return value;
    }

    private DriverConf getDriverConf(String driverType) {
        DriverConf conf = drivers.get(driverType);
        if (conf == null) {
            throw new BadRequestException("当前仅支持115云盘和迅雷云盘离线下载");
        }
        return conf;
    }

    private void syncTempDir(String driverType, String folder) {
        getDriverConf(driverType).tempDirSync().accept(buildOfflinePath(folder));
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
