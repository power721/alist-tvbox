package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTask;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTaskRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.offline.OfflineDownloadHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 定时离线清理(docs/pan115-offline-auto-delete-design.md):删除 115 离线任务+文件,
 * 面向离线配额大的重度用户(任务槽位被完成态任务占满、离线目录无限膨胀)。
 * <p>
 * 删除时机三分法(v2.2 定案):
 * <ul>
 * <li>msub 磁力行 + 固化开关开:删除前先固化永久分享({@link MediaSubscriptionCheckService#selfifyOfflineProduct},
 *     分享快照接管播放)→ 固化成功即删,不等观看进度,空间立刻回收;</li>
 * <li>msub 磁力行 + 固化开关关:追平门禁——产物覆盖集在所有挂载它的订阅里全部看完才删,
 *     没看完之前文件就是唯一正片(40 集的剧不能 24 小时一刀切);</li>
 * <li>通用入口(/parse、/offline_download):无进度可依,按完成时间 + TTL 兜底。</li>
 * </ul>
 * FAILED 行即清(115 残留失败任务挡同磁力重提,提交路径另有当场钩子);PENDING 行不盲删,
 * 先活体检查 115 任务列表(盲删会砍死仍在下载的任务、白瞎已完成的产物)。
 * <p>
 * 只删本系统提交的任务(行内有 info_hash/产物名);严禁 task_clear 批量清空,会误删用户在
 * 115 客户端自建的任务。本地行永不物理删(配额计数、urlHash 查重、FAILED 记忆都依赖行),
 * 清理完成只置 {@code cleanup_state=DONE} —— 提交短路随之放行同磁力重提(这正是删任务的目的)。
 * <p>
 * 调度:每小时 :10 cron 直跑(2026-09-28 用户定规,原每日一次;2026-09-29 曾试加密到
 * 10 分钟,用户定规改回每小时——TTL 到期最长 1 小时内回收,关机错过的整点下次在线补上;
 * 零候选时只花一次 DB 查询,幂等无副作用);连续失败超限进入 7 天冷却,冷却期满重试一轮
 * (cookie 换新后自愈),不永久放弃。
 * <p>
 * 多盘差异(按 {@link OfflineDownloadHandler#supportsTaskManagement()} 分叉):115/迅雷有任务
 * 删除契约,删任务+文件一体,固化分享仅 cookie 115;光鸭无契约(重复提交直接建新任务、无
 * 「任务已存在」限制),任务记录留存、经内嵌 AList 删产物文件回收空间,PENDING 只按滞留天数兜底
 * (预测产物名对不上任务列表,防误判查无)。
 */
@Slf4j
@Service
public class OfflineCleanupService {
    static final String CLEANUP_FAILED = "FAILED";
    static final String LAST_RUN_SETTING = "offline_cleanup_last_run";
    private static final int MAX_CLEANUP_ATTEMPTS = 5;
    /** 连续失败超限后的冷却时长:冷却期满重置计数重试一轮(cookie 换新等环境修复后自愈)。 */
    private static final long RETRY_COOLDOWN_HOURS = 7 * 24L;
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_FAILED = "FAILED";
    /** 集源行可播状态(与巡检 LIVE_STATES 同口径):追平门禁的覆盖集取值范围。 */
    private static final List<String> LIVE_SOURCE_STATES = List.of(
            MediaSubscriptionEpisodeSource.STATE_LISTED, MediaSubscriptionEpisodeSource.STATE_VERIFIED);

    private final OfflineDownloadService offlineDownloadService;
    private final OfflineDownloadTaskRepository taskRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final MediaSubscriptionRepository subscriptionRepository;
    private final MediaSubscriptionResourceRepository resourceRepository;
    private final MediaSubscriptionEpisodeSourceRepository episodeSourceRepository;
    private final MediaSubscriptionCheckService checkService;
    private final AppProperties appProperties;
    private final AListService aListService;
    private final SiteService siteService;
    private final SettingRepository settingRepository;

    public OfflineCleanupService(OfflineDownloadService offlineDownloadService,
                                 OfflineDownloadTaskRepository taskRepository,
                                 DriverAccountRepository driverAccountRepository,
                                 MediaSubscriptionRepository subscriptionRepository,
                                 MediaSubscriptionResourceRepository resourceRepository,
                                 MediaSubscriptionEpisodeSourceRepository episodeSourceRepository,
                                 MediaSubscriptionCheckService checkService,
                                 AppProperties appProperties,
                                 AListService aListService,
                                 SiteService siteService,
                                 SettingRepository settingRepository) {
        this.offlineDownloadService = offlineDownloadService;
        this.taskRepository = taskRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.resourceRepository = resourceRepository;
        this.episodeSourceRepository = episodeSourceRepository;
        this.checkService = checkService;
        this.appProperties = appProperties;
        this.aListService = aListService;
        this.siteService = siteService;
        this.settingRepository = settingRepository;
    }

    /**
     * 每小时 :10 清理(用户定规:cron 直跑,不做代码内节流/闸门——TTL 到期最长 1 小时内回收;
     * 清理幂等:行 DONE 不再进候选,重复触发最多多一次任务列表查询,无副作用)。
     * 电视盒子晚上用完关机错过的整点在下次在线的 :10 自动补上;autoDelete 与固化开关全关时零动作。
     */
    @Scheduled(cron = "0 10 * * * *")
    public void hourlyCleanup() {
        try {
            doCleanup();
        } finally {
            writeLastRun(Instant.now()); // 仅诊断展示用(DiagnosticsService「上次清理距今」),不参与调度判定
        }
    }

    private void writeLastRun(Instant time) {
        try {
            settingRepository.save(new Setting(LAST_RUN_SETTING, time.toString()));
        } catch (Exception e) {
            log.warn("record offline cleanup last-run failed: {}", e.getMessage());
        }
    }

    private void doCleanup() {
        OfflineDownloadService.CleanupConfig config = offlineDownloadService.cleanupConfig();
        if (config == null || (!config.autoDelete() && !config.selfShare())) {
            log.debug("skip offline cleanup: config disabled (autoDelete/selfShare both off or offline not configured)");
            return;
        }
        // 候选按行自身账号全量查:离线配置切换账号后(多 115 账号常见),旧账号提交的行不能
        // 沦为清理孤儿——按配置账号过滤会让旧账号目录的文件永不清理,且候选恒空零日志
        List<OfflineDownloadTask> tasks = taskRepository.findCleanupCandidates();
        if (tasks.isEmpty()) {
            return;
        }
        Map<Integer, List<OfflineDownloadTask>> byAccount = new LinkedHashMap<>();
        for (OfflineDownloadTask task : tasks) {
            byAccount.computeIfAbsent(task.getAccountId() == null ? -1 : task.getAccountId(),
                    key -> new ArrayList<>()).add(task);
        }
        int cleaned = 0;
        for (Map.Entry<Integer, List<OfflineDownloadTask>> entry : byAccount.entrySet()) {
            DriverAccount account = driverAccountRepository.findById(entry.getKey()).orElse(null);
            if (account == null) {
                // 账号行已删:常见形态是换 cookie 删号重建(同一网盘,新行 id 变)——按当前配置
                // 账号的离线根尝试补删产物文件(路径不在则 AList 报错,无害),行标 DONE 免占候选
                String fallbackRoot = currentOfflineRootPathOrNull();
                for (OfflineDownloadTask task : entry.getValue()) {
                    log.warn("offline task {} belongs to deleted account {}, fallback file delete via alist: {}",
                            task.getId(), entry.getKey(), task.getTaskName());
                    if (fallbackRoot != null && StringUtils.isNotBlank(task.getTaskName())) {
                        try {
                            aListService.remove(siteService.getById(1), fallbackRoot + "/" + task.getTaskName());
                        } catch (Exception e) {
                            log.debug("fallback file delete for task {} failed (path gone?): {}", task.getId(), e.getMessage());
                        }
                    }
                    task.setCleanupState(OfflineDownloadService.CLEANUP_DONE);
                    task.setCleanupAttempts(0);
                    task.setCleanupTime(Instant.now());
                    task.setUpdatedTime(Instant.now());
                    taskRepository.save(task);
                }
                cleaned += entry.getValue().size();
                continue;
            }
            OfflineDownloadHandler handler;
            try {
                handler = offlineDownloadService.getHandler(account.getType().name());
            } catch (Exception e) {
                log.warn("skip offline cleanup for account {} ({}): {}", account.getId(), account.getType(), e.getMessage());
                continue;
            }
            boolean managed = handler.supportsTaskManagement(); // 115/迅雷删任务;光鸭无契约只删文件
            // 固化(永久分享)仅 cookie 115,按行账号类型判;其它盘固化开关忽略(UI 也只在 115 显示)
            OfflineDownloadService.CleanupConfig effective = config;
            if (config.selfShare() && account.getType() != DriverType.PAN115) {
                effective = new OfflineDownloadService.CleanupConfig(config.accountId(), config.driverType(),
                        config.autoDelete(), config.ttlHours(), false);
            }
            for (OfflineDownloadTask task : entry.getValue()) {
                try {
                    if (process(task, account, handler, managed, effective)) {
                        cleaned++;
                    }
                } catch (Exception e) {
                    log.warn("cleanup offline task {} failed: {}", task.getId(), e.getMessage());
                    markFailed(task);
                }
            }
        }
        log.info("offline cleanup finished: {} candidate(s), {} cleaned (autoDelete={}, selfShare={})",
                tasks.size(), cleaned, config.autoDelete(), config.selfShare());
    }

    /** 账号已删行的兜底文件删除用:当前配置账号的离线根;离线未配置/配置账号异常返回 null。 */
    private String currentOfflineRootPathOrNull() {
        try {
            return offlineDownloadService.offlineRootPath();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean process(OfflineDownloadTask task, DriverAccount account, OfflineDownloadHandler handler,
                            boolean managed, OfflineDownloadService.CleanupConfig config) {
        if (OfflineDownloadService.CLEANUP_DONE.equals(task.getCleanupState())) {
            return false;
        }
        if (CLEANUP_FAILED.equals(task.getCleanupState()) && task.getCleanupAttempts() >= MAX_CLEANUP_ATTEMPTS) {
            if (!olderThan(task.getUpdatedTime(), RETRY_COOLDOWN_HOURS)) {
                return false; // 连续失败冷却中:每天一次调度 5 次即耗尽,防无效重试刷 115 接口
            }
            // 冷却期满重试一轮:失败根因多为 cookie 过期,用户换新 cookie 后应自愈,不能永久放弃
            log.warn("offline task {} exceeded {} cleanup attempts, cooldown elapsed - retrying",
                    task.getId(), MAX_CLEANUP_ATTEMPTS);
            task.setCleanupAttempts(0);
            taskRepository.save(task);
        }
        switch (StringUtils.defaultString(task.getStatus())) {
            case STATUS_FAILED -> {
                // FAILED 即清(不等 TTL):残留失败任务挡同磁力重提(115/迅雷);无产物,连文件删只清残文件
                if (config.autoDelete()) {
                    return deleteRemote(task, account, handler, managed, true);
                }
            }
            case STATUS_PENDING -> {
                return processPending(task, account, handler, managed, config);
            }
            case STATUS_COMPLETED -> {
                return processCompleted(task, account, handler, managed, config);
            }
            default -> {
                // 未知状态不动
            }
        }
        return false;
    }

    /**
     * PENDING(未收割)活体检查:按 info_hash 对账 115 任务列表(ed2k 无 btih 只按滞留天数兜底)。
     * 已完成且订阅仍在 → 等巡检收割(PENDING 归属闸门靠这行),不动;仍在下载 → 跳过,
     * 提交超滞留阈值才清;终态失败/查无 → 删任务。行保持 PENDING 不翻转状态:收割归属闸门按
     * PENDING 行对账,翻转会挡住产物登记;同集换磁力提交后 settle 会自然把行收走。
     * <p>
     * 返回是否实际删除(汇总计数用):与 {@link #processCompleted} 一样把 deleteRemote 的
     * 结果传出——否则 "N candidate(s), 0 cleaned" 汇总行与实际删除数矛盾,误导排障。
     */
    private boolean processPending(OfflineDownloadTask task, DriverAccount account, OfflineDownloadHandler handler,
                                   boolean managed, OfflineDownloadService.CleanupConfig config) {
        if (!config.autoDelete()) {
            return false;
        }
        // 无 btih(ed2k)或该盘无任务管理契约(光鸭,预测名对不上任务列表会误判查无):只按滞留天数兜底
        if (StringUtils.isBlank(task.getInfoHash()) || !managed) {
            return olderThan(task.getCreatedTime(), stuckDays() * 24L)
                    && deleteRemote(task, account, handler, managed, true);
        }
        boolean subAlive = task.getSubscriptionId() != null
                && subscriptionRepository.existsById(task.getSubscriptionId());
        switch (handler.taskStatus(account, task.getInfoHash(), task.getTaskName())) {
            case SUCCEEDED -> {
                if (!subAlive) {
                    // 通用入口超时行(subscriptionId=null)无 completedTime,按提交时间近似 TTL——
                    // 与 COMPLETED 行同口径,防止刚提交不久就被下一轮清掉(用户还没来得及看);
                    // msub 残行(订阅已删)无人收割,即清
                    if (task.getSubscriptionId() == null
                            && !olderThan(task.getCreatedTime(), config.ttlHours())) {
                        return false;
                    }
                    return deleteRemote(task, account, handler, managed, true);
                }
            }
            case RUNNING -> {
                if (olderThan(task.getCreatedTime(), stuckDays() * 24L)) {
                    log.info("offline task {} stuck over {} days, clean it up", task.getId(), stuckDays());
                    return deleteRemote(task, account, handler, managed, true);
                }
            }
            case FAILED, ABSENT -> {
                return deleteRemote(task, account, handler, managed, true);
            }
            default -> {
            }
        }
        return false;
    }

    /** COMPLETED:通用入口按完成时间+TTL;msub 行按引用状态(固化开关 → 固化后删 / 追平门禁)。 */
    private boolean processCompleted(OfflineDownloadTask task, DriverAccount account, OfflineDownloadHandler handler,
                                     boolean managed, OfflineDownloadService.CleanupConfig config) {
        if (task.getSubscriptionId() == null) {
            // 通用入口(/parse、/offline_download)即看即走:无进度可依,TTL 兜底
            return config.autoDelete() && olderThan(
                    firstNonNull(task.getCompletedTime(), task.getUpdatedTime(), task.getCreatedTime()),
                    config.ttlHours())
                    && deleteRemote(task, account, handler, managed, true);
        }
        List<MediaSubscriptionResource> rows = resourceRepository.findByLink("offline:" + task.getTaskName()).stream()
                .filter(row -> MediaSubscriptionResource.STATE_MOUNTED.equals(row.getState()))
                .filter(MediaSubscriptionCheckService::isMagnetResource)
                .toList();
        if (rows.isEmpty()) {
            // 行已退役/订阅已删:无播放要保护,直接删
            return config.autoDelete() && deleteRemote(task, account, handler, managed, true);
        }
        if (config.selfShare()) {
            if (StringUtils.isBlank(task.getShareUrl())) {
                try {
                    String shareUrl = checkService.selfifyOfflineProduct(task.getSubscriptionId(), account, task.getTaskName());
                    if (shareUrl == null) {
                        // 订阅已删(残行来自其它订阅):仅当再无任何挂载行才删
                        return config.autoDelete() && rows.stream()
                                .allMatch(row -> Objects.equals(row.getSubscriptionId(), task.getSubscriptionId()))
                                && deleteRemote(task, account, handler, managed, true);
                    }
                    task.setShareUrl(shareUrl);
                    taskRepository.save(task);
                } catch (Exception e) {
                    log.warn("selfify offline product {} failed, keep files and retry next day: {}",
                            task.getTaskName(), e.getMessage());
                    return false; // 固化失败:文件原地保留,次日重试,绝不先删
                }
            }
            if (!config.autoDelete()) {
                return false; // 纯固化模式:分享银行化即可,不删
            }
            // 删除守卫:其它订阅的磁力行仍挂载同产物(未固化/未追平)→ 推迟
            if (rows.stream().anyMatch(row -> !Objects.equals(row.getSubscriptionId(), task.getSubscriptionId()))) {
                log.info("offline product {} still referenced by other subscriptions, defer cleanup", task.getTaskName());
                return false;
            }
            return deleteRemote(task, account, handler, managed, true);
        }
        // 固化开关关:追平门禁——覆盖集在所有仍挂载它的订阅里全部看完才删
        return config.autoDelete() && allCaughtUp(rows) && deleteRemote(task, account, handler, managed, true);
    }

    /** 追平门禁判定:观看进度口径与 🆕 追平角标同源(播放记录实时聚合,未看完的当前集折算前一集)。 */
    private boolean allCaughtUp(List<MediaSubscriptionResource> rows) {
        for (MediaSubscriptionResource row : rows) {
            MediaSubscription subscription = subscriptionRepository.findById(row.getSubscriptionId()).orElse(null);
            if (subscription == null) {
                continue;
            }
            int watched = checkService.watchedEpisode(subscription);
            int maxCovered = episodeSourceRepository.findNumbersByResourceIdAndStatesIn(row.getId(), LIVE_SOURCE_STATES)
                    .stream().max(Integer::compareTo).orElse(0);
            if (maxCovered > 0 && watched < maxCovered) {
                return false;
            }
        }
        return true;
    }

    /** 删网盘侧任务(+文件)并记账;handler 层保证任务不存在=幂等成功。
     *  任务删除与文件删除解耦:即便 115/迅雷 deleteTask 声明连文件删,网盘侧文件删除也是
     *  尽力而为(2026-09-29 线上实测 115 task_del 17 个 state=true 仍残留 2 个产物),删任务后
     *  统一经内嵌 AList 对产物路径兜底核删——路径已随任务删净时 AList 报 not found,属正常;
     *  123 任务删除无文件参数、光鸭无任务删除契约,兜底核删即主删除通道。超时 PENDING 行
     *  taskName=null(产物名未知),删任务前先按 info_hash 从任务列表解析回填。 */
    private boolean deleteRemote(OfflineDownloadTask task, DriverAccount account, OfflineDownloadHandler handler,
                                 boolean managed, boolean deleteFiles) {
        if (managed) {
            if (StringUtils.isBlank(task.getTaskName())) {
                String resolved = handler.resolveTaskName(account, task.getInfoHash(), task.getTaskName());
                if (StringUtils.isNotBlank(resolved)) {
                    task.setTaskName(resolved);
                }
            }
            handler.deleteTask(account, task.getInfoHash(), task.getTaskName(), deleteFiles);
        }
        if (deleteFiles && StringUtils.isNotBlank(task.getTaskName())) {
            String productPath = offlineDownloadService.offlineRootPath(account) + "/" + task.getTaskName();
            try {
                aListService.remove(siteService.getById(1), productPath);
            } catch (Exception e) {
                String message = StringUtils.defaultString(e.getMessage());
                if (StringUtils.contains(message, "not found")) {
                    // 路径已随任务删净(115 flag=1 生效的正常形态),幂等收尾
                    log.debug("offline product {} already gone (deleted with task)", productPath);
                } else {
                    // 产物可能真残留(115 兜底通道/123 光鸭主通道):markFailed 下轮重试,deleteTask 幂等
                    throw e;
                }
            }
        }
        task.setCleanupState(OfflineDownloadService.CLEANUP_DONE);
        task.setCleanupAttempts(0);
        task.setCleanupTime(Instant.now());
        task.setUpdatedTime(Instant.now());
        taskRepository.save(task);
        log.info("offline task cleaned: id={}, hash={}, name={}, files={}, share={}",
                task.getId(), task.getInfoHash(), task.getTaskName(), deleteFiles, task.getShareUrl());
        return true;
    }

    private void markFailed(OfflineDownloadTask task) {
        try {
            task.setCleanupState(CLEANUP_FAILED);
            task.setCleanupAttempts(task.getCleanupAttempts() + 1);
            task.setUpdatedTime(Instant.now());
            taskRepository.save(task);
        } catch (Exception e) {
            log.warn("mark cleanup failed on task {} failed: {}", task.getId(), e.getMessage());
        }
    }

    private boolean olderThan(Instant time, long hours) {
        return time != null && time.plusSeconds(hours * 3600).isBefore(Instant.now());
    }

    private static Instant firstNonNull(Instant... values) {
        for (Instant value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private int stuckDays() {
        return Math.max(1, appProperties.getSubscription().getOfflinePendingStuckDays());
    }
}
