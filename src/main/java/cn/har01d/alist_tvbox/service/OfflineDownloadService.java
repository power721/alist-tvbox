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
import cn.har01d.alist_tvbox.model.DownloadTarget;
import cn.har01d.alist_tvbox.model.MagnetSubmitResult;
import cn.har01d.alist_tvbox.model.StoredConfig;
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
import java.time.YearMonth;
import java.time.ZoneId;
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
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_FAILED = "FAILED";

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
            return new OfflineDownloadConfigDto(false, DriverType.PAN115.name(), null, null, "");
        }

        StoredConfig config = parseConfig(setting.get().getValue());
        String folder = "";
        String accountName = null;
        if (config.accountId() != null) {
            Optional<DriverAccount> account = driverAccountRepository.findById(config.accountId());
            folder = account.map(Storage::getMountPath).orElse("");
            accountName = account.map(DriverAccount::getName).orElse(null);
        }
        return new OfflineDownloadConfigDto(config.enabled(), normalizeDriverType(config.driverType()), config.accountId(), accountName, folder);
    }

    public OfflineDownloadConfigDto saveConfig(OfflineDownloadConfigRequest request) {
        validateConfig(request);
        String driverType = normalizeDriverType(request.driverType());
        StoredConfig normalized = new StoredConfig(request.enabled(), driverType, request.accountId(), "");
        if (!normalized.enabled()) {
            settingRepository.save(new Setting(SETTING_NAME, writeConfig(normalized)));
            log.info("offline download config disabled");
            return new OfflineDownloadConfigDto(false, driverType, normalized.accountId(), null, "");
        }

        DriverAccount account = getAccount(normalized.accountId(), driverType);
        OfflineDownloadHandler handler = getHandler(driverType);
        String offlineFolderId = handler.ensureOfflineFolder(account);
        settingRepository.save(new Setting(SETTING_NAME, writeConfig(new StoredConfig(true, driverType, account.getId(), offlineFolderId))));
        log.info("offline download config saved: driverType={}, accountId={}, offlineFolderId={}",
                driverType, account.getId(), offlineFolderId);
        return new OfflineDownloadConfigDto(true, driverType, account.getId(), account.getName(), Storage.getMountPath(account));
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
        saveTask(account.getId(), urlHash, result, targetPath, null, null);
        log.info("offline download task completed: driverType={}, accountId={}, urlHash={}, targetPath={}", config.driverType(), account.getId(), urlHash, targetPath);
        return new DownloadTarget(targetPath, result.folder());
    }

    public void syncSelectedAccountTempDir(Integer accountId) {
        refreshOfflineFolderId(accountId);
    }

    /** 追剧磁力兜底前置:离线下载已配置并开启(布尔版,不抛)。 */
    public boolean isConfigured() {
        try {
            loadEnabledConfig();
            return true;
        } catch (BadRequestException e) {
            return false;
        }
    }

    /** 离线产物根目录(配置账号挂载根/alist-tvbox-offline),巡检扫描收割用。 */
    public String offlineRootPath() {
        StoredConfig config = loadEnabledConfig();
        DriverAccount account = getAccount(config.accountId(), config.driverType());
        return buildRootPath(account);
    }

    /** 离线配置账号的盘型代码(DriveId,磁力产物资源行的 type 用);未配置返回 null。 */
    public Integer configuredDriveType() {
        try {
            StoredConfig config = loadEnabledConfig();
            return switch (config.driverType()) {
                case "PAN115" -> 8;   // DriveId: 115
                case "THUNDER" -> 2;  // DriveId: thunder
                case "GUANGYA" -> 12; // DriveId: duck(广雅/光鸭)
                default -> null;
            };
        } catch (BadRequestException e) {
            return null;
        }
    }

    /** 配置账号上未收割的磁力任务数(PENDING),配额闸门用。 */
    public long pendingMagnetCount() {
        StoredConfig config = loadEnabledConfig();
        return offlineDownloadTaskRepository.countByAccountIdAndStatus(config.accountId(), STATUS_PENDING);
    }

    /** 单集离线配额已用量:该订阅该集当月的提交尝试次数(含 FAILED)。 */
    public long episodeMagnetCount(Integer subscriptionId, Integer episode) {
        return offlineDownloadTaskRepository.countBySubscriptionIdAndEpisodeAndCreatedTimeGreaterThanEqual(subscriptionId, episode, currentMonthStart());
    }

    /** 单订阅离线配额已用量(当月)。 */
    public long subscriptionMagnetCount(Integer subscriptionId) {
        return offlineDownloadTaskRepository.countBySubscriptionIdAndCreatedTimeGreaterThanEqual(subscriptionId, currentMonthStart());
    }

    /** 追剧总离线配额已用量(当月;磁力兜底提交的行才带 subscription_id)。 */
    public long totalMagnetCount() {
        return offlineDownloadTaskRepository.countBySubscriptionIdNotNullAndCreatedTimeGreaterThanEqual(currentMonthStart());
    }

    /** 三档配额按自然月计窗口:每月1号零点(本地时区)后计数自动归零,无需定时清行。 */
    private static Instant currentMonthStart() {
        return YearMonth.now().atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    /**
     * 追剧磁力兜底提交(三态,见 {@link MagnetSubmitResult})。waitSeconds 为同步等待时长
     * (app.subscription.magnet-submit-timeout-seconds,默认 30 秒;超时仍按 PENDING 落行
     * 等收割,手动离线走各盘默认等待不受影响)。
     * <p>
     * 与 {@link #downloadTarget} 的差异:超时(submitAndWait 等不到完成)不当失败——网盘侧任务
     * 已创建,记 PENDING 等巡检下轮扫描收割,重复提交会在网盘侧重复建任务烧离线配额。
     * FAILED 也落行(带订阅/集号):试错同样消耗配额与网盘侧风控信任,且同磁力不再重试。
     */
    public MagnetSubmitResult submitMagnet(String url, Integer subscriptionId, Integer episode, int waitSeconds) {
        return doSubmitMagnet(url, subscriptionId, episode, waitSeconds, false);
    }

    /** 手动补缺路径(用户明确动作):FAILED 记忆不拦重试 —— 重贴失败磁力=重新提交,行按 urlHash 原地更新。 */
    public MagnetSubmitResult submitMagnetRetryFailed(String url, Integer subscriptionId, Integer episode, int waitSeconds) {
        return doSubmitMagnet(url, subscriptionId, episode, waitSeconds, true);
    }

    private MagnetSubmitResult doSubmitMagnet(String url, Integer subscriptionId, Integer episode, int waitSeconds,
                                              boolean retryFailed) {
        validateUrl(url);
        StoredConfig config = loadEnabledConfig();
        DriverAccount account = getAccount(config.accountId(), config.driverType());
        String urlHash = hashUrl(url);
        Optional<OfflineDownloadTask> localTask = offlineDownloadTaskRepository
                .findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(account.getId(), urlHash);
        if (localTask.isPresent()) {
            OfflineDownloadTask task = localTask.get();
            if (STATUS_COMPLETED.equals(task.getStatus()) && StringUtils.isNotBlank(task.getTargetPath())) {
                return MagnetSubmitResult.completed(task.getTaskName());
            }
            if (STATUS_PENDING.equals(task.getStatus())) {
                return MagnetSubmitResult.submitted("任务进行中,等待网盘下载");
            }
            if (STATUS_FAILED.equals(task.getStatus()) && !retryFailed) {
                return MagnetSubmitResult.failed("该磁力此前提交失败,已跳过");
            }
        }

        OfflineDownloadHandler handler = getHandler(config.driverType());
        String pathId = requireOfflineFolderId(config);
        log.info("submitting magnet offline download: driverType={}, accountId={}, urlHash={}, subscription={}, episode={}, waitSeconds={}",
                config.driverType(), account.getId(), urlHash, subscriptionId, episode, waitSeconds);
        OfflineDownloadHandler.TaskResult result;
        try {
            result = handler.submitAndWait(account, url, pathId, waitSeconds);
        } catch (BadRequestException e) {
            if (isTimeoutMessage(e.getMessage())) {
                saveAttempt(account.getId(), urlHash, subscriptionId, episode, STATUS_PENDING, predictProductName(url), null, false);
                return MagnetSubmitResult.submitted("已提交,等待网盘下载");
            }
            saveAttempt(account.getId(), urlHash, subscriptionId, episode, STATUS_FAILED, null, null, false);
            return MagnetSubmitResult.failed(e.getMessage());
        } catch (Exception e) {
            saveAttempt(account.getId(), urlHash, subscriptionId, episode, STATUS_FAILED, null, null, false);
            return MagnetSubmitResult.failed(StringUtils.defaultIfBlank(e.getMessage(), "离线下载提交失败"));
        }
        String targetPath = buildTargetPath(account, result.taskName());
        saveTask(account.getId(), urlHash, result, targetPath, subscriptionId, episode);
        log.info("magnet offline download completed: urlHash={}, taskName={}", urlHash, result.taskName());
        return MagnetSubmitResult.completed(result.taskName());
    }

    public void syncConfiguredTempDirOnStartup() {
        try {
            StoredConfig config = loadEnabledConfig();
            refreshOfflineFolderId(config.accountId());
        } catch (BadRequestException e) {
            log.debug("skip syncing offline folder on startup: {}", e.getMessage());
        }
    }

    /**
     * 收割入账后结算超时 PENDING 行:该订阅该集最新一条 PENDING 置 COMPLETED 并补 taskName/targetPath ——
     * pending 闸门(countByAccountIdAndStatus)与 urlHash 查重语义随之恢复,不会再被已完成任务永久占满;
     * 同步完成路径本就有 COMPLETED 行,此处自然 no-op。结算失败只记日志,不影响收割入账。
     */
    public void settlePendingTask(Integer subscriptionId, Integer episode, String taskName, String targetPath) {
        if (subscriptionId == null || episode == null || StringUtils.isBlank(taskName)) {
            return;
        }
        try {
            offlineDownloadTaskRepository
                    .findFirstBySubscriptionIdAndEpisodeAndStatusOrderByUpdatedTimeDesc(subscriptionId, episode, STATUS_PENDING)
                    .ifPresent(task -> {
                        task.setStatus(STATUS_COMPLETED);
                        task.setTaskName(taskName);
                        task.setTargetPath(StringUtils.defaultString(targetPath));
                        task.setUpdatedTime(Instant.now());
                        offlineDownloadTaskRepository.save(task);
                        log.info("settled pending offline download task {} to product {}", task.getId(), taskName);
                    });
        } catch (Exception e) {
            log.warn("settle pending offline download task failed: {}", e.getMessage());
        }
    }

    /** 该订阅是否有未收割的 PENDING 离线任务(巡检 PENDING 感知收割的判定,不限订阅 mode)。 */
    public boolean hasPendingTask(Integer subscriptionId) {
        return offlineDownloadTaskRepository.existsBySubscriptionIdAndStatus(subscriptionId, STATUS_PENDING);
    }

    /** 该订阅全部 PENDING 行:收割归属对账用(按集号/预测产物名匹配未知产物,防共享离线目录跨订阅冒领)。 */
    public List<OfflineDownloadTask> pendingTasks(Integer subscriptionId) {
        return offlineDownloadTaskRepository.findBySubscriptionIdAndStatus(subscriptionId, STATUS_PENDING);
    }

    /**
     * 收割入账后结算手动路径(集号留空)的 PENDING 行:episode=null 的行不会被按集结算
     * ({@link #settlePendingTask} 按 episode 匹配),不结算会永久占住 pending 闸门。
     * 优先按预测产物名(PENDING 落行时从 ed2k/磁力 dn 存入的 taskName)精确/前缀匹配,
     * 防自动路径产物错配结算别条磁力的手动行;无预测名的行(旧构建/dn 缺失)回退最新一条近似结算。
     * 失败只记日志,不影响收割入账。
     */
    public void settleManualPendingTask(Integer subscriptionId, String taskName, String targetPath) {
        if (subscriptionId == null || StringUtils.isBlank(taskName)) {
            return;
        }
        try {
            Optional<OfflineDownloadTask> row = offlineDownloadTaskRepository
                    .findFirstBySubscriptionIdAndEpisodeIsNullAndStatusAndTaskName(subscriptionId, STATUS_PENDING, taskName);
            if (row.isEmpty()) {
                row = offlineDownloadTaskRepository.findFirstManualPendingByNameLenient(subscriptionId, STATUS_PENDING, taskName);
            }
            if (row.isEmpty()) {
                row = offlineDownloadTaskRepository
                        .findFirstBySubscriptionIdAndEpisodeIsNullAndStatusAndTaskNameIsNull(subscriptionId, STATUS_PENDING);
            }
            row.ifPresent(task -> {
                task.setStatus(STATUS_COMPLETED);
                task.setTaskName(taskName);
                task.setTargetPath(StringUtils.defaultString(targetPath));
                task.setUpdatedTime(Instant.now());
                offlineDownloadTaskRepository.save(task);
                log.info("settled manual pending offline download task {} to product {}", task.getId(), taskName);
            });
        } catch (Exception e) {
            log.warn("settle manual pending offline download task failed: {}", e.getMessage());
        }
    }

    /** PENDING 行的产物名预测(ed2k 文件名段/磁力 dn= 解码):收割归属对账与手动行结算的匹配锚点;预测不出为 null。 */
    private static String predictProductName(String url) {
        String value = StringUtils.trimToEmpty(url);
        if (StringUtils.startsWithIgnoreCase(value, "ed2k:")) {
            String[] parts = value.split("\\|", 6);
            if (parts.length >= 3 && "file".equals(parts[1]) && StringUtils.isNotBlank(parts[2])) {
                return parts[2];
            }
            return null;
        }
        int dn = value.toLowerCase().indexOf("dn=");
        if (dn < 0) {
            return null;
        }
        String tail = value.substring(dn + 3);
        int end = tail.indexOf('&');
        try {
            String decoded = java.net.URLDecoder.decode(end < 0 ? tail : tail.substring(0, end), StandardCharsets.UTF_8);
            return StringUtils.trimToNull(decoded);
        } catch (IllegalArgumentException e) {
            return null; // 编码畸形:预测不出,收割结算回退近似口径
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
        return buildRootPath(account) + "/" + taskName;
    }

    private String buildRootPath(DriverAccount account) {
        return Storage.getMountPath(account) + "/" + OFFLINE_DIR_NAME;
    }

    /** 三 handler 的超时文案(「未在N秒内完成」,N 随等待时长动态)。 */
    private static boolean isTimeoutMessage(String message) {
        return message != null && message.contains("未在") && message.contains("内完成");
    }

    private void saveTask(Integer accountId, String urlHash, OfflineDownloadHandler.TaskResult result, String targetPath,
                          Integer subscriptionId, Integer episode) {
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
        entity.setSubscriptionId(subscriptionId);
        entity.setEpisode(episode);
        entity.setUpdatedTime(now);
        offlineDownloadTaskRepository.save(entity);
    }

    /** 提交尝试落行(超时 PENDING/被拒 FAILED):无 taskName/targetPath(产物名未知),扫描收割按目录对账。 */
    private void saveAttempt(Integer accountId, String urlHash, Integer subscriptionId, Integer episode,
                             String status, String taskName, String targetPath, boolean folder) {
        OfflineDownloadTask entity = offlineDownloadTaskRepository
                .findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(accountId, urlHash)
                .orElseGet(OfflineDownloadTask::new);
        Instant now = Instant.now();
        if (entity.getCreatedTime() == null) {
            entity.setCreatedTime(now);
        }
        entity.setAccountId(accountId);
        entity.setUrlHash(urlHash);
        entity.setTaskName(taskName);
        entity.setTargetPath(targetPath);
        entity.setStatus(status);
        entity.setFolder(folder);
        entity.setSubscriptionId(subscriptionId);
        entity.setEpisode(episode);
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
