package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.entity.Task;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.storage.Storage;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService.EpisodeFile;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自动转存(§6.5,需求 2 的 TRANSFER 模式):把主源+补缺源的剧集增量 copy 到用户自己的网盘
 * (DriverAccount 挂载下的 /追剧/{名称}),转存后永久免疫分享失效。
 * 自愈式调度:每小时对比源覆盖与目标已有集,差集非空才提交 AList copy 任务(带日限额)。
 */
@Slf4j
@Service
public class MediaSubscriptionTransferService {
    private final MediaSubscriptionRepository subscriptionRepository;
    private final MediaSubscriptionResourceRepository resourceRepository;
    private final DriverAccountRepository accountRepository;
    private final SiteRepository siteRepository;
    private final SettingRepository settingRepository;
    private final AListService aListService;
    private final MediaSubscriptionCheckService checkService;
    private final TaskService taskService;
    private final AutoUpdateExecutor autoUpdateExecutor;
    private final AppProperties appProperties;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "msub-transfer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger todayCount = new AtomicInteger();
    private volatile String todayKey = "";

    public MediaSubscriptionTransferService(MediaSubscriptionRepository subscriptionRepository,
                                            MediaSubscriptionResourceRepository resourceRepository,
                                            DriverAccountRepository accountRepository,
                                            SiteRepository siteRepository,
                                            SettingRepository settingRepository,
                                            AListService aListService,
                                            MediaSubscriptionCheckService checkService,
                                            TaskService taskService,
                                            AutoUpdateExecutor autoUpdateExecutor,
                                            AppProperties appProperties) {
        this.subscriptionRepository = subscriptionRepository;
        this.resourceRepository = resourceRepository;
        this.accountRepository = accountRepository;
        this.siteRepository = siteRepository;
        this.settingRepository = settingRepository;
        this.aListService = aListService;
        this.checkService = checkService;
        this.taskService = taskService;
        this.autoUpdateExecutor = autoUpdateExecutor;
        this.appProperties = appProperties;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 转存自愈扫描:每小时 :40(避开 :20 巡检),TRANSFER 且未完结/完结均可补齐。 */
    @Scheduled(cron = "0 40 * * * *")
    public void sweep() {
        if (!appProperties.getSubscription().isEnabled()) {
            return;
        }
        autoUpdateExecutor.scheduleWithJitter(() -> executor.submit(this::sweepDue));
    }

    private void sweepDue() {
        for (MediaSubscription subscription : subscriptionRepository.findAll()) {
            if (!MediaSubscription.MODE_TRANSFER.equals(subscription.getMode())
                    || MediaSubscription.STATUS_PAUSED.equals(subscription.getStatus())) {
                continue;
            }
            try {
                transfer(subscription);
            } catch (Exception e) {
                log.warn("transfer subscription {} failed: {}", subscription.getId(), e.getMessage());
            }
        }
    }

    public void transferAsync(int uid, int id) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new BadRequestException("订阅不存在: " + id);
        }
        executor.submit(() -> {
            try {
                transfer(subscription);
            } catch (Exception e) {
                log.warn("transfer subscription {} failed: {}", id, e.getMessage());
            }
        });
    }

    private int quotaLeft() {
        String today = LocalDate.now().toString();
        if (!today.equals(todayKey)) {
            todayKey = today;
            todayCount.set(0);
        }
        return appProperties.getSubscription().getMaxTransfersPerDay() - todayCount.get();
    }

    /** 增量转存(多网盘目标):逐账号只 copy 目标目录缺的集;按源目录分组提交 AList copy 任务并等待完成,事后校验。 */
    void transfer(MediaSubscription subscription) {
        if (!MediaSubscription.MODE_TRANSFER.equals(subscription.getMode())) {
            return;
        }
        if (quotaLeft() <= 0) {
            log.info("transfer quota exhausted, skip subscription {}", subscription.getId());
            return;
        }
        List<DriverAccount> targets = resolveTargets(subscription);
        if (targets.isEmpty()) {
            checkService.addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "转存失败:未配置网盘账号");
            downgradeToFOLLOW(subscription, "账号不存在");
            return;
        }
        boolean anyTransferred = false;
        boolean anyFailure = false;
        for (DriverAccount account : targets) {
            try {
                Integer transferred = transferToAccount(subscription, account);
                if (transferred != null && transferred > 0) {
                    anyTransferred = true;
                }
                if (transferred == null) {
                    anyFailure = true;
                }
            } catch (Exception e) {
                log.warn("transfer subscription {} to {} failed: {}", subscription.getId(), account.getName(), e.getMessage());
                anyFailure = true;
            }
        }
        // 全部目标都颗粒无收才降级(多目标时单盘失败不影响其余,追更不断)
        if (!anyTransferred && anyFailure) {
            downgradeToFOLLOW(subscription, "转存全部失败(检查账号配额与空间)");
        }
    }

    /** @return 转存成功的集数;null = 失败(事件已记);0 = 无需转存 */
    private Integer transferToAccount(MediaSubscription subscription, DriverAccount account) {
        Site site = siteRepository.findById(1).orElseThrow();
        migrateLegacyTransferDir(subscription, site, account);
        String targetDir = targetDir(subscription, account);

        // 源覆盖(主源 + 补缺挂载)与目标已有集对比
        TreeMap<Integer, EpisodeFile> sources = checkService.walkEpisodeFiles(subscription, true);
        if (sources.isEmpty()) {
            return 0;
        }
        ensureDirs(site, targetDir);
        Set<Integer> existing = listTargetEpisodes(site, subscription, targetDir);
        TreeMap<Integer, EpisodeFile> missing = new TreeMap<>();
        sources.forEach((episode, file) -> {
            if (!existing.contains(episode)) {
                missing.put(episode, file);
            }
        });
        if (missing.isEmpty()) {
            return 0;
        }

        Task task = taskService.addSubscriptionTask("转存 " + subscription.getName() + " → " + account.getName());
        taskService.startTask(task.getId());
        todayCount.incrementAndGet();
        log.info("transfer subscription {} to {}: {} -> {} ({} episodes)", subscription.getId(), account.getName(),
                sources.firstKey() + "-" + sources.lastKey(), targetDir, missing.size());
        try {
            // 按源目录分组提交
            Map<String, List<String>> byDir = new TreeMap<>();
            for (EpisodeFile file : missing.values()) {
                byDir.computeIfAbsent(file.dir(), k -> new ArrayList<>()).add(file.name());
            }
            for (var entry : byDir.entrySet()) {
                aListService.copy(site, entry.getKey(), targetDir, entry.getValue());
            }
            boolean done = aListService.awaitCopyTasks(site, appProperties.getSubscription().getTransferTimeoutMinutes() * 60_000L);
            // 事后校验
            Set<Integer> after = listTargetEpisodes(site, subscription, targetDir);
            int transferred = (int) missing.keySet().stream().filter(after::contains).count();
            if (done && transferred == missing.size()) {
                taskService.completeTask(task.getId(), "转存 " + transferred + " 集", "");
                checkService.addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_TRANSFER_DONE,
                        "已转存 " + transferred + " 集到 " + account.getName() + "(共 " + after.size() + " 集)");
                return transferred;
            }
            int failed = missing.size() - transferred;
            taskService.failTask(task.getId(), "转存 " + transferred + "/" + missing.size() + " 集");
            checkService.addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_TRANSFER_FAILED,
                    "转存不完整(" + account.getName() + "):成功 " + transferred + " / " + missing.size() + "(可能配额/空间不足)");
            return transferred == 0 ? null : transferred;
        } catch (Exception e) {
            taskService.failTask(task.getId(), e.getMessage());
            checkService.addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_TRANSFER_FAILED,
                    "转存失败(" + account.getName() + "):" + e.getMessage());
            return null;
        }
    }

    private List<DriverAccount> resolveTargets(MediaSubscription subscription) {
        List<DriverAccount> result = new ArrayList<>();
        for (Integer id : accountIds(subscription)) {
            DriverAccount account = accountRepository.findById(id == null ? -1 : id).orElse(null);
            if (account != null) {
                result.add(account);
            }
        }
        return result;
    }

    private List<Integer> accountIds(MediaSubscription subscription) {
        if (StringUtils.isNotBlank(subscription.getAccountIds())) {
            try {
                List<Integer> ids = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(subscription.getAccountIds(), new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() {
                        });
                if (!ids.isEmpty()) {
                    return ids;
                }
            } catch (Exception ignored) {
                // 回退旧单值
            }
        }
        return subscription.getAccountId() == null ? List.of() : List.of(subscription.getAccountId());
    }

    private static String targetDir(MediaSubscription subscription, DriverAccount account) {
        return Storage.getMountPath(account) + "/追剧/" + dirBaseName(subscription);
    }

    /** 转存目录名:剧名 + 季 + 元数据 id 标签(与挂载目录命名一致,刮削器可按 id 精准匹配)。 */
    private static String dirBaseName(MediaSubscription subscription) {
        String base = sanitize(subscription.getName())
                + (subscription.getSeason() != null && subscription.getSeason() > 1 ? "-第" + subscription.getSeason() + "季" : "");
        String tag = MediaSubscriptionService.metaIdTag(subscription);
        return tag == null ? base : base + " " + tag;
    }

    /** 旧命名(无 id 标签)目录一次性原地 rename 迁移,避免带标签的新目录整季重拷。 */
    private void migrateLegacyTransferDir(MediaSubscription subscription, Site site, DriverAccount account) {
        try {
            String tag = MediaSubscriptionService.metaIdTag(subscription);
            if (tag == null) {
                return;
            }
            String oldDir = Storage.getMountPath(account) + "/追剧/" + sanitize(subscription.getName())
                    + (subscription.getSeason() != null && subscription.getSeason() > 1 ? "-第" + subscription.getSeason() + "季" : "");
            String newDir = targetDir(subscription, account);
            if (oldDir.equals(newDir)) {
                return;
            }
            if (!listTargetEpisodes(site, subscription, oldDir).isEmpty()
                    && listTargetEpisodes(site, subscription, newDir).isEmpty()) {
                aListService.rename(site, oldDir, newDir.substring(newDir.lastIndexOf('/') + 1));
                log.info("renamed legacy transfer dir of subscription {} to {}", subscription.getId(), newDir);
            }
        } catch (Exception e) {
            log.warn("migrate legacy transfer dir failed: {}", e.getMessage());
        }
    }

    /** 转存不可用时自动降级挂载模式,追更不中断(§10.6 空间水位同理)。 */
    private void downgradeToFOLLOW(MediaSubscription subscription, String reason) {
        subscription.setMode(MediaSubscription.MODE_FOLLOW);
        subscription.setUpdatedTime(System.currentTimeMillis());
        subscriptionRepository.save(subscription);
        checkService.addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "已降级为挂载模式(" + reason + "),追更不中断");
    }

    /** 逐级确保目标目录存在(已存在时 listFiles 成功即跳过)。 */
    private void ensureDirs(Site site, String targetDir) {
        String[] segments = targetDir.split("/");
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
            if (StringUtils.isBlank(segment)) {
                continue;
            }
            current.append("/").append(segment);
            try {
                aListService.listFiles(site, current.toString(), 1, 1);
            } catch (Exception e) {
                try {
                    aListService.mkdir(site, current.toString());
                } catch (Exception mkdirError) {
                    throw new BadRequestException("创建目录失败 " + current + ": " + mkdirError.getMessage());
                }
            }
        }
    }

    /** 目标目录已转存的集数(容忍目录暂不存在;沿用订阅的单集大小上限保持口径一致)。 */
    private Set<Integer> listTargetEpisodes(Site site, MediaSubscription subscription, String targetDir) {
        try {
            return checkService.walkEpisodes(site, subscription.getSeason(), targetDir,
                    checkService.maxEpisodeBytes(subscription));
        } catch (Exception e) {
            return new TreeSet<>();
        }
    }

    public record TransferredTarget(String account, String path) {
    }

    /** 已配置转存目标(各账号)的 /追剧/{名称} 目录挂载路径,按目标顺序;供播放列表按集优先合并自有盘副本(§4.5 转存>主源>补缺)。 */
    public List<TransferredTarget> transferredTargets(int uid, int id) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new BadRequestException("订阅不存在: " + id);
        }
        List<TransferredTarget> result = new ArrayList<>();
        for (DriverAccount account : resolveTargets(subscription)) {
            result.add(new TransferredTarget(account.getName(), targetDir(subscription, account)));
        }
        return result;
    }

    /** 转存进度(前端显示):各目标网盘的已转存/源覆盖。 */
    public Map<String, Object> progress(int uid, int id) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new BadRequestException("订阅不存在: " + id);
        }
        List<DriverAccount> targets = resolveTargets(subscription);
        if (targets.isEmpty()) {
            return Map.of("covered", 0, "accounts", List.of());
        }
        Site site = siteRepository.findById(1).orElseThrow();
        int covered;
        try {
            covered = checkService.walkEpisodeFiles(subscription, true).size();
        } catch (Exception e) {
            covered = 0;
        }
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (DriverAccount account : targets) {
            int transferred = listTargetEpisodes(site, subscription, targetDir(subscription, account)).size();
            accounts.add(Map.of("account", account.getName(), "transferred", transferred));
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("covered", covered);
        result.put("accounts", accounts);
        return result;
    }

    /** 归档清理(§10.7):完结 N 天后自动释放转存文件(Setting msub_archive_days,默认 0=关闭)。 */
    @Scheduled(cron = "0 50 6 * * *")
    public void archiveSweep() {
        int days;
        try {
            days = Integer.parseInt(settingRepository.findById("msub_archive_days")
                    .map(s -> s.getValue()).filter(StringUtils::isNotBlank).orElse("0"));
        } catch (NumberFormatException e) {
            days = 0;
        }
        if (days <= 0) {
            return;
        }
        long threshold = System.currentTimeMillis() - days * 24L * 3600_000;
        for (MediaSubscription subscription : subscriptionRepository.findAll()) {
            if (!MediaSubscription.STATUS_ENDED.equals(subscription.getStatus())
                    || !MediaSubscription.MODE_TRANSFER.equals(subscription.getMode())
                    || subscription.getUpdatedTime() == null || subscription.getUpdatedTime() > threshold) {
                continue;
            }
            try {
                Site site = siteRepository.findById(1).orElseThrow();
                for (DriverAccount account : resolveTargets(subscription)) {
                    migrateLegacyTransferDir(subscription, site, account); // 旧命名目录先归位,确保能删干净
                    String targetDir = targetDir(subscription, account);
                    aListService.remove(site, targetDir);
                }
                subscription.setMode(MediaSubscription.MODE_FOLLOW);
                subscription.setUpdatedTime(System.currentTimeMillis());
                subscriptionRepository.save(subscription);
                checkService.addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ARCHIVED,
                        "已归档:完结 " + days + " 天,转存文件已释放");
                log.info("archived subscription {} transfer folder", subscription.getId());
            } catch (Exception e) {
                log.warn("archive subscription {} failed: {}", subscription.getId(), e.getMessage());
            }
        }
    }

    static String sanitize(String name) {
        String slug = name.replaceAll("[\\s/\\\\:*?\"<>|#@$%\\.、,]+", "-");
        slug = StringUtils.strip(slug, "-");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        return slug.isEmpty() ? "sub" : slug;
    }
}
