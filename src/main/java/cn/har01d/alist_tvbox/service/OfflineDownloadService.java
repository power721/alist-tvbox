package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigRequest;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigDto;
import cn.har01d.alist_tvbox.dto.OfflineDownloadQuotaResponse;
import cn.har01d.alist_tvbox.dto.ParseRequest;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTask;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTaskRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.offline.OfflineDownloadHandler;
import cn.har01d.alist_tvbox.storage.Storage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OfflineDownloadService {
    static final String SETTING_NAME = "offline_download_config";
    static final String OFFLINE_DIR_NAME = "alist-tvbox-offline";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private record StoredConfig(boolean enabled, String driverType, Integer accountId, String offlineFolderId) {
    }

    public record DownloadTarget(String path, boolean folder) {
    }

    private final SettingRepository settingRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final OfflineDownloadTaskRepository offlineDownloadTaskRepository;
    private final ObjectMapper objectMapper;
    private final Map<DriverType, OfflineDownloadHandler> handlerMap;

    public OfflineDownloadService(SettingRepository settingRepository,
                                  DriverAccountRepository driverAccountRepository,
                                  OfflineDownloadTaskRepository offlineDownloadTaskRepository,
                                  ObjectMapper objectMapper,
                                  List<OfflineDownloadHandler> handlers) {
        this.settingRepository = settingRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.offlineDownloadTaskRepository = offlineDownloadTaskRepository;
        this.objectMapper = objectMapper;
        this.handlerMap = handlers.stream().collect(Collectors.toMap(OfflineDownloadHandler::getDriverType, Function.identity()));
    }

    public OfflineDownloadConfigDto getConfig() {
        Optional<Setting> setting = settingRepository.findById(SETTING_NAME);
        if (setting.isEmpty() || StringUtils.isBlank(setting.get().getValue())) {
            return new OfflineDownloadConfigDto(false, DriverType.PAN115.name(), null, "");
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
        OfflineDownloadHandler handler = getHandler(driverType);
        String offlineFolderId = handler.ensureOfflineFolder(account);
        settingRepository.save(new Setting(SETTING_NAME, writeConfig(new StoredConfig(true, driverType, account.getId(), offlineFolderId))));
        log.info("offline download config saved: driverType={}, accountId={}, offlineFolderId={}",
                driverType, account.getId(), offlineFolderId);
        return new OfflineDownloadConfigDto(true, driverType, account.getId(), Storage.getMountPath(account));
    }

    public OfflineDownloadQuotaResponse getQuota() {
        StoredConfig config = loadEnabledConfig();
        DriverAccount account = getAccount(config.accountId(), config.driverType());
        OfflineDownloadHandler handler = getHandler(config.driverType());
        OfflineDownloadHandler.QuotaResult result = handler.getQuota(account);
        return new OfflineDownloadQuotaResponse(result.supported(), 0, 0, result.displayText());
    }

    public String downloadPath(ParseRequest request) {
        return downloadTarget(request).path();
    }

    public DownloadTarget downloadTarget(ParseRequest request) {
        validateUrl(request.url());
        StoredConfig config = loadEnabledConfig();
        DriverAccount account = getAccount(config.accountId(), config.driverType());
        String urlHash = hashUrl(request.url());
        Optional<OfflineDownloadTask> localTask = offlineDownloadTaskRepository
                .findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(account.getId(), urlHash);
        if (localTask.isPresent() && STATUS_COMPLETED.equals(localTask.get().getStatus()) && StringUtils.isNotBlank(localTask.get().getTargetPath())) {
            return new DownloadTarget(resolveTargetPath(account, localTask.get()), localTask.get().isFolder());
        }

        OfflineDownloadHandler handler = getHandler(config.driverType());
        String pathId = requireOfflineFolderId(config);
        log.info("submitting offline download: driverType={}, accountId={}, pathId={}, urlHash={}", config.driverType(), account.getId(), pathId, urlHash);

        OfflineDownloadHandler.TaskResult result = handler.submitAndWait(account, request.url(), pathId);
        String targetPath = buildTargetPath(account, result.taskName());
        saveTask(account.getId(), urlHash, result, targetPath);
        log.info("offline download task completed: driverType={}, accountId={}, urlHash={}, targetPath={}", config.driverType(), account.getId(), urlHash, targetPath);
        return new DownloadTarget(targetPath, result.folder());
    }

    public void syncSelectedAccountTempDir(Integer accountId) {
        refreshOfflineFolderId(accountId);
    }

    public void syncConfiguredTempDirOnStartup() {
        try {
            StoredConfig config = loadEnabledConfig();
            refreshOfflineFolderId(config.accountId());
        } catch (BadRequestException e) {
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

    private OfflineDownloadHandler getHandler(String driverType) {
        DriverType type = DriverType.valueOf(driverType);
        OfflineDownloadHandler handler = handlerMap.get(type);
        if (handler == null) {
            throw new BadRequestException("不支持的离线下载类型: " + driverType);
        }
        return handler;
    }

    private DriverAccount getAccount(Integer accountId, String driverType) {
        DriverAccount account = driverAccountRepository.findById(accountId)
                .orElseThrow(() -> new BadRequestException("离线下载账号不存在"));
        DriverType type = DriverType.valueOf(driverType);
        if (account.getType() != type) {
            throw new BadRequestException("离线下载账号类型不匹配");
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
        String value = StringUtils.isBlank(driverType) ? DriverType.PAN115.name() : driverType;
        try {
            DriverType type = DriverType.valueOf(value);
            if (!handlerMap.containsKey(type)) {
                throw new BadRequestException("不支持的离线下载类型: " + value);
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("不支持的离线下载类型: " + value);
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
        OfflineDownloadHandler handler = getHandler(driverType);
        String offlineFolderId = handler.ensureOfflineFolder(account);
        settingRepository.save(new Setting(SETTING_NAME, writeConfig(new StoredConfig(true, driverType, account.getId(), offlineFolderId))));
    }

    private String requireOfflineFolderId(StoredConfig config) {
        if (StringUtils.isBlank(config.offlineFolderId())) {
            throw new BadRequestException("离线下载目录ID不能为空");
        }
        return config.offlineFolderId().trim();
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

    private void saveTask(Integer accountId, String urlHash, OfflineDownloadHandler.TaskResult result, String targetPath) {
        OfflineDownloadTask entity = offlineDownloadTaskRepository
                .findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(accountId, urlHash)
                .orElseGet(OfflineDownloadTask::new);
        Instant now = Instant.now();
        if (entity.getCreatedTime() == null) {
            entity.setCreatedTime(now);
        }
        entity.setAccountId(accountId);
        entity.setUrlHash(urlHash);
        entity.setInfoHash(StringUtils.firstNonBlank(result.infoHash(), entity.getInfoHash()));
        entity.setTargetPath(targetPath);
        entity.setTaskName(result.taskName());
        entity.setStatus(STATUS_COMPLETED);
        entity.setFolder(result.folder());
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
}
