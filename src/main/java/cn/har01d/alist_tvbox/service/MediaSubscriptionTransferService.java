package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.domain.DriveId;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.Setting;
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
import java.util.Comparator;
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
    private final AccountRepository aliAccountRepository;
    private final SiteRepository siteRepository;
    private final SettingRepository settingRepository;
    private final cn.har01d.alist_tvbox.entity.ShareRepository shareRepository;
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
                                            AccountRepository aliAccountRepository,
                                            SiteRepository siteRepository,
                                            SettingRepository settingRepository,
                                            cn.har01d.alist_tvbox.entity.ShareRepository shareRepository,
                                            AListService aListService,
                                            MediaSubscriptionCheckService checkService,
                                            TaskService taskService,
                                            AutoUpdateExecutor autoUpdateExecutor,
                                            AppProperties appProperties) {
        this.subscriptionRepository = subscriptionRepository;
        this.resourceRepository = resourceRepository;
        this.accountRepository = accountRepository;
        this.aliAccountRepository = aliAccountRepository;
        this.siteRepository = siteRepository;
        this.settingRepository = settingRepository;
        this.shareRepository = shareRepository;
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
        // 入口实体可能取自数分钟前(单线程执行器里 sweepDue 一轮多订阅排队):重取最新行,
        // 已删即收工 —— 无 @Version,detached save 会把已删行整行 INSERT 复活;模式被编辑改掉也以最新为准
        MediaSubscription current = subscriptionRepository.findById(subscription.getId()).orElse(null);
        if (current == null) {
            log.info("transfer subscription {} skipped: deleted", subscription.getId());
            return;
        }
        subscription = current;
        if (!MediaSubscription.MODE_TRANSFER.equals(subscription.getMode())) {
            return;
        }
        if (quotaLeft() <= 0) {
            log.info("transfer quota exhausted, skip subscription {}", subscription.getId());
            return;
        }
        List<TransferTarget> targets = resolveTargets(subscription);
        if (targets.isEmpty()) {
            checkService.addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "转存失败:未配置网盘账号");
            downgradeToFOLLOW(subscription, "账号不存在");
            return;
        }
        boolean anySuccess = false;  // 含"无需转存"(返回 0)——账号本来就已补齐也算成功
        boolean anyFailure = false;
        for (TransferTarget target : targets) {
            // 配额按"每个转存任务"计:多目标逐盘各起一个任务,逐目标复查防剩余 1 个名额被同一订阅的多个盘吃穿
            if (quotaLeft() <= 0) {
                log.info("transfer quota exhausted, stop at target {} of subscription {}", target.name(), subscription.getId());
                break;
            }
            // 单盘转存要等 AList copy 数分钟,期间订阅可能被删:不再往下一盘继续拷
            if (subscriptionRepository.findById(subscription.getId()).isEmpty()) {
                log.info("transfer subscription {} aborted: deleted", subscription.getId());
                return;
            }
            try {
                Integer transferred = transferToAccount(subscription, target);
                if (transferred != null) {
                    anySuccess = true;
                } else {
                    anyFailure = true;
                }
            } catch (Exception e) {
                log.warn("transfer subscription {} to {} failed: {}", subscription.getId(), target.name(), e.getMessage());
                anyFailure = true;
            }
        }
        // 全部目标都失败才降级(多目标时单盘失败不影响其余,追更不断);
        // 无需转存(0)是成功,不能因为"另一盘失败"而误降级
        if (anyFailure && !anySuccess) {
            downgradeToFOLLOW(subscription, "转存全部失败(检查账号配额与空间)");
        }
    }

    /** @return 转存成功的集数;null = 失败(事件已记);0 = 无需转存 */
    /** 源目录 → 分享类型映射(主源+补缺挂载),供按盘路由转存。 */
    private Map<String, Integer> sourceTypesByDir(MediaSubscription subscription) {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        try {
            cn.har01d.alist_tvbox.entity.Share primary = shareRepository.findByPath(subscription.getMountPath());
            if (primary != null && primary.getType() != null) {
                result.put(subscription.getMountPath(), primary.getType());
            }
            for (MediaSubscriptionResource resource : resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId())) {
                if (MediaSubscriptionResource.STATE_MOUNTED.equals(resource.getState()) && resource.getShareId() != null && StringUtils.isNotBlank(resource.getMountPath())) {
                    shareRepository.findById(resource.getShareId())
                            .filter(share -> share.getType() != null)
                            .ifPresent(share -> result.put(resource.getMountPath(), share.getType()));
                }
            }
        } catch (Exception e) {
            log.debug("resolve source types failed: {}", e.getMessage());
        }
        return result;
    }

    private static Integer sourceTypeFor(Map<String, Integer> sourceTypes, String dir) {
        Integer best = null;
        int bestLen = -1;
        for (var entry : sourceTypes.entrySet()) {
            if (dir.startsWith(entry.getKey()) && entry.getKey().length() > bestLen) {
                best = entry.getValue();
                bestLen = entry.getKey().length();
            }
        }
        return best;
    }

    /** 跨盘秒传配置的盘名(Setting {src}_to_{dst},如 quark_to_123);无配置体系的盘返回 null。 */
    private static String transferDriveName(int type) {
        return switch (type) {
            case 0 -> "ali";
            case 3 -> "123";
            case 5 -> "quark";
            case 7 -> "uc";
            case 8 -> "115";
            case 12 -> "guangya";
            default -> null;
        };
    }

    /** 转存路由:同盘恒允许;跨盘需订阅显式开启,或 AList 跨盘秒传配置允许该方向。 */
    private boolean crossAllowed(MediaSubscription subscription, Integer srcType, int dstType) {
        if (srcType == null || srcType == dstType) {
            return true;
        }
        if (subscription.isCrossDrive()) {
            return true;
        }
        String src = transferDriveName(srcType);
        String dst = transferDriveName(dstType);
        if (src == null || dst == null) {
            return false;
        }
        return "true".equals(settingRepository.findById(src + "_to_" + dst)
                .map(s -> s.getValue()).orElse("false"));
    }

    private Integer transferToAccount(MediaSubscription subscription, TransferTarget target) {
        Site site = siteRepository.findById(1).orElseThrow();
        String rootDir = target.mountPath() + "/" + transferRoot();
        String targetDir = targetDir(subscription, target);

        // 源覆盖(主源 + 补缺挂载)与目标已有集对比
        TreeMap<Integer, EpisodeFile> sources = checkService.walkEpisodeFiles(subscription, true);
        if (sources.isEmpty()) {
            return 0;
        }
        ensureDirs(site, rootDir);
        Set<Integer> existing = listTargetEpisodes(site, subscription, targetDir);
        TreeMap<Integer, EpisodeFile> missing = new TreeMap<>();
        sources.forEach((episode, file) -> {
            if (!existing.contains(episode)) {
                missing.put(episode, file);
            }
        });
        // 按盘路由:默认只转同盘;跨盘需显式开启或秒传配置允许,
        // 未路由的集继续走分享播放(合并列表不受影响)
        int dstType = target.shareType() == null ? -1 : target.shareType();
        Map<String, Integer> sourceTypes = sourceTypesByDir(subscription);
        List<Integer> skippedCross = new ArrayList<>();
        missing.entrySet().removeIf(entry -> {
            if (!crossAllowed(subscription, sourceTypeFor(sourceTypes, entry.getValue().dir()), dstType)) {
                skippedCross.add(entry.getKey());
                return true;
            }
            return false;
        });
        if (!skippedCross.isEmpty()) {
            log.info("subscription {} skip {} cross-drive episodes to account {} (仅同盘转存;分享源继续播放)",
                    subscription.getId(), skippedCross.size(), target.name());
        }
        if (missing.isEmpty()) {
            return 0;
        }

        // 任务名要过 Task.name 列(VARCHAR 255,TaskService 还会加「追剧订阅 - 」前缀):
        // 订阅名可到 250 + 账号昵称(网盘侧外部字符串)无界,超宽抛 22001 会被上轮 catch 记为
        // 「转存全部失败」连锁把 TRANSFER 模式静默降级 FOLLOW
        Task task = taskService.addSubscriptionTask(org.apache.commons.lang3.StringUtils.abbreviate(
                "转存 " + subscription.getName() + " → " + target.name(), 200));
        taskService.startTask(task.getId());
        todayCount.incrementAndGet();
        log.info("transfer subscription {} to {}: {} -> {} ({} episodes)", subscription.getId(), target.name(),
                sources.firstKey() + "-" + sources.lastKey(), targetDir, missing.size());
        try {
            // 按源目录分组
            Map<String, List<String>> byDir = new TreeMap<>();
            for (EpisodeFile file : missing.values()) {
                byDir.computeIfAbsent(file.dir(), k -> new ArrayList<>()).add(file.name());
            }
            boolean relayUsed = false;
            // 全新剧且支持服务端转存:覆盖缺集最多的源目录整目录秒转到固定根目录,
            // 再把分享原名目录 rename 成规范剧目录名(带元数据 id 标签,刮削按 id 匹配);
            // 其余源目录的缺集随后走文件模式补齐
            if (!target.relayOnly() && !dirExists(site, targetDir)) {
                String primary = byDir.entrySet().stream()
                        .max(Comparator.comparingInt(e -> e.getValue().size()))
                        .map(Map.Entry::getKey).orElse(null);
                if (primary != null && serverSavable(sourceTypeFor(sourceTypes, primary), dstType)) {
                    try {
                        int index = primary.lastIndexOf('/');
                        String parent = primary.substring(0, index);
                        String name = primary.substring(index + 1);
                        shareSaveObjects(site, parent, List.of(name), rootDir);
                        aListService.rename(site, rootDir + "/" + name, dirBaseName(subscription));
                        log.info("subscription {} dir-saved {} under {}", subscription.getId(), name, rootDir);
                        byDir.remove(primary);
                    } catch (Exception e) {
                        log.warn("dir share save of subscription {} failed, fallback to copy: {}",
                                subscription.getId(), e.getMessage());
                    }
                }
            }
            if (!byDir.isEmpty()) {
                ensureDirs(site, targetDir);
            }
            for (var entry : byDir.entrySet()) {
                if (!target.relayOnly() && serverSavable(sourceTypeFor(sourceTypes, entry.getKey()), dstType)) {
                    try {
                        shareSaveObjects(site, entry.getKey(), entry.getValue(), targetDir);
                        continue;
                    } catch (Exception e) {
                        log.warn("share save {} -> {} failed, fallback to copy: {}",
                                entry.getKey(), targetDir, e.getMessage());
                    }
                }
                aListService.copy(site, entry.getKey(), targetDir, entry.getValue());
                relayUsed = true;
            }
            boolean done = !relayUsed || aListService.awaitCopyTasks(site,
                    appProperties.getSubscription().getTransferTimeoutMinutes() * 60_000L);
            // 事后校验
            Set<Integer> after = listTargetEpisodes(site, subscription, targetDir);
            int transferred = (int) missing.keySet().stream().filter(after::contains).count();
            if (done && transferred == missing.size()) {
                taskService.completeTask(task.getId(), "转存 " + transferred + " 集", "");
                checkService.addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_TRANSFER_DONE,
                        "已转存 " + transferred + " 集到 " + target.name() + "(共 " + after.size() + " 集)");
                return transferred;
            }
            int failed = missing.size() - transferred;
            taskService.failTask(task.getId(), "转存 " + transferred + "/" + missing.size() + " 集");
            checkService.addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_TRANSFER_FAILED,
                    "转存不完整(" + target.name() + "):成功 " + transferred + " / " + missing.size() + "(可能配额/空间不足)");
            if (done && transferred < missing.size()) {
                // 任务已结束仍缺失 = 源里列得出、实际取不了(如夸克分享单集被和谐):
                // 登记为该源损坏集,下轮集数统计/转存取源跳过,自动从其他分享补(7 天过期重试)
                Map<Integer, String> broken = new java.util.LinkedHashMap<>();
                missing.forEach((episode, file) -> {
                    if (!after.contains(episode)) {
                        broken.put(episode, file.dir());
                    }
                });
                if (!broken.isEmpty()) {
                    // 损坏标记是集源行级落库,订阅行无字段改动不再 save —— detached save 只会把转存期间的过期快照 merge 回去
                    checkService.markTransferBroken(subscription, broken);
                    log.info("subscription {} marked {} broken episodes (listed but not copyable)", subscription.getId(), broken.size());
                }
            }
            return transferred == 0 ? null : transferred;
        } catch (Exception e) {
            taskService.failTask(task.getId(), e.getMessage());
            checkService.addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_TRANSFER_FAILED,
                    "转存失败(" + target.name() + "):" + e.getMessage());
            return null;
        }
    }

    /** 首个管理员 uid(与 UserService 的 id=1 管理员不变量一致)。 */
    private static final int adminUid = 1;

    /** 转存目标归属:本人账号;全局账号(ownerUid=0)仅当订阅人是首个管理员(管理员自己的订阅)时可转存。 */
    private boolean canTransfer(int ownerUid, int subscriptionUid) {
        if (ownerUid == subscriptionUid) {
            return true;
        }
        return ownerUid == 0 && subscriptionUid == adminUid;
    }

    private List<TransferTarget> resolveTargets(MediaSubscription subscription) {
        List<TransferTarget> result = new ArrayList<>();
        long aliCount = aliAccountRepository.count();
        for (String id : accountIds(subscription)) {
            try {
                if (id.startsWith("ali:")) {
                    Account account = aliAccountRepository.findById(Integer.parseInt(id.substring(4))).orElse(null);
                    // 与 AccountService.enableMyAli 同规则:未挂载"我的阿里云盘"的账号不可作为转存目标
                    // 归属门禁:转存目标必须是订阅人本人账号(uid=0 全局账号仅 admin 订阅可用,shared 全局账号对 USER 只读不转存)
                    if (account == null || !canTransfer(account.getOwnerUid(), subscription.getUid())
                            || !(account.isShowMyAli() || account.isMaster() || aliCount == 1)) {
                        continue;
                    }
                    String name = StringUtils.defaultIfBlank(account.getNickname(), "阿里#" + account.getId());
                    result.add(new TransferTarget("ali:" + account.getId(), name, aliMountPath(account), 0, false));
                } else {
                    DriverAccount account = accountRepository.findById(Integer.parseInt(id.startsWith("pan:") ? id.substring(4) : id)).orElse(null);
                    if (account != null && canTransfer(account.getOwnerUid(), subscription.getUid())) {
                        DriverType type = account.getType();
                        // 仅字节中转的目标:TV 账号无服务端转存;115 开放平台无分享接收接口,仅 cookie 版账号可转存
                        boolean relayOnly = type == DriverType.QUARK_TV || type == DriverType.UC_TV
                                || type == DriverType.OPEN115;
                        result.add(new TransferTarget("pan:" + account.getId(),
                                StringUtils.defaultIfBlank(account.getName(), "账号#" + account.getId()),
                                Storage.getMountPath(account),
                                MediaSubscriptionCheckService.driveCode(type),
                                relayOnly));
                    }
                }
            } catch (NumberFormatException ignored) {
                // 非法目标 id 跳过
            }
        }
        return result;
    }

    /** 转存目标 id:"pan:{id}"(网盘账号)/"ali:{id}"(阿里独立账号表);兼容旧整数 JSON(默认 pan)与单值 accountId。 */
    private List<String> accountIds(MediaSubscription subscription) {
        if (StringUtils.isNotBlank(subscription.getAccountIds())) {
            try {
                var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(subscription.getAccountIds());
                if (arr.isArray() && !arr.isEmpty()) {
                    List<String> ids = new ArrayList<>();
                    arr.forEach(node -> {
                        String value = node.isNumber() ? "pan:" + node.asInt() : node.asText();
                        if (StringUtils.isNotBlank(value)) {
                            ids.add(value);
                        }
                    });
                    if (!ids.isEmpty()) {
                        return ids;
                    }
                }
            } catch (Exception ignored) {
                // 回退旧单值
            }
        }
        return subscription.getAccountId() == null ? List.of() : List.of("pan:" + subscription.getAccountId());
    }

    /** 阿里账号挂载根(与 Storage(Account,type) 同规则,转存进资源盘)。 */
    private static String aliMountPath(Account account) {
        String name = StringUtils.defaultIfBlank(account.getNickname(), String.valueOf(account.getId()));
        return String.format("/\uD83D\uDCC0我的阿里云盘/%s/资源盘", name);
    }

    /** 转存固定根目录名(Setting msub_transfer_root,默认"我的追剧";去除路径分隔符防拼接逃逸)。 */
    private String transferRoot() {
        String root = settingRepository.findById("msub_transfer_root")
                .map(Setting::getValue).orElse("");
        root = root.replaceAll("[/\\\\]+", "").trim();
        return root.isEmpty() ? "我的追剧" : root;
    }

    private String targetDir(MediaSubscription subscription, TransferTarget target) {
        return target.mountPath() + "/" + transferRoot() + "/" + dirBaseName(subscription);
    }

    /** 转存目录名:剧名 + 季 + 元数据 id 标签(与挂载目录命名一致,刮削器可按 id 精准匹配)。 */
    private static String dirBaseName(MediaSubscription subscription) {
        String base = sanitize(subscription.getName())
                + (subscription.getSeason() != null && subscription.getSeason() > 1 ? "-第" + subscription.getSeason() + "季" : "");
        String tag = MediaSubscriptionService.metaIdTag(subscription);
        return tag == null ? base : base + " " + tag;
    }

    /** 源分享类型与目标账号同族即支持服务端转存(阿里 0/迅雷 2/123 3/夸克 5/UC 7/115 8/天翼 9/百度 10/光鸭 12/移动 6);TV/开放平台账号由 relayOnly 排除。 */
    private static boolean serverSavable(Integer srcType, int dstType) {
        return srcType != null && srcType == dstType
                && (dstType == 0 || dstType == 2 || dstType == 3 || dstType == 5 || dstType == 6
                || dstType == 7 || dstType == 8 || dstType == 9 || dstType == 10 || dstType == 12);
    }

    /** 目录是否存在(listFiles 失败视为不存在,含 object not found)。 */
    private boolean dirExists(Site site, String dir) {
        try {
            aListService.listFiles(site, dir, 1, 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 服务端转存对象(文件/目录):分批提交,每批 ≤10 控制同步请求时长。 */
    private void shareSaveObjects(Site site, String srcDir, List<String> names, String dstDir) {
        for (int i = 0; i < names.size(); i += 10) {
            aListService.shareSave(site, srcDir, names.subList(i, Math.min(i + 10, names.size())), dstDir);
        }
    }

    /** 转存不可用时自动降级挂载模式,追更不中断(§10.6 空间水位同理)。 */
    private void downgradeToFOLLOW(MediaSubscription subscription, String reason) {
        // 调用前可能已历数分钟转存:重取最新行再改,已删即跳过(detached save 复活整行),也避免覆盖巡检刚写的字段
        MediaSubscription current = subscriptionRepository.findById(subscription.getId()).orElse(null);
        if (current == null) {
            return;
        }
        current.setMode(MediaSubscription.MODE_FOLLOW);
        current.setUpdatedTime(System.currentTimeMillis());
        subscriptionRepository.save(current);
        checkService.addEvent(current.getId(), MediaSubscriptionEvent.TYPE_ERROR, "已降级为挂载模式(" + reason + "),追更不中断");
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

    /** 目标目录已转存的集数(容忍目录暂不存在;沿用订阅的单集体积策略保持口径一致)。 */
    private Set<Integer> listTargetEpisodes(Site site, MediaSubscription subscription, String targetDir) {
        try {
            return checkService.walkEpisodes(site, subscription.getSeason(), targetDir,
                    checkService.episodeSizePolicy(subscription));
        } catch (Exception e) {
            return new TreeSet<>();
        }
    }

    public record TransferredTarget(String account, String path, String drive) {
    }

    /** 转存目标:网盘账号(DriverAccount,"pan:{id}")或阿里独立账号表(Account,"ali:{id}");
     * mountPath 为 AList 挂载根,shareType 为分享类型码(跨盘路由/盘线路用),
     * relayOnly 标记无服务端转存能力的目标(TV 账号/开放平台 115),只走字节中转 copy。 */
    public record TransferTarget(String key, String name, String mountPath, Integer shareType, boolean relayOnly) {
        public String drive() {
            return shareType == null || shareType < 0 ? null : DriveId.toDrive(shareType);
        }
    }

    /** 转存账号网盘 → DriveId 盘 key(与分享资源 type 同一命名空间,供播放列表按盘分线)。 */
    static String driveKey(DriverAccount account) {
        if (account == null || account.getType() == null) {
            return null;
        }
        return switch (account.getType()) {
            case QUARK, QUARK_TV -> "quark";
            case UC, UC_TV -> "uc";
            case PAN115, OPEN115 -> "115";
            case PAN123, OPEN123 -> "123";
            case PAN139 -> "139";
            case CLOUD189 -> "189";
            case THUNDER -> "thunder";
            case BAIDU -> "baidu";
            case ALI -> "ali";
            case GUANGYA -> "duck";
            default -> null;
        };
    }

    /** 已配置转存目标(各账号)的 /追剧/{名称} 目录挂载路径,按目标顺序;供播放列表按集优先合并自有盘副本(§4.5 转存>主源>补缺)。 */
    public List<TransferredTarget> transferredTargets(int uid, int id) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new BadRequestException("订阅不存在: " + id);
        }
        List<TransferredTarget> result = new ArrayList<>();
        for (TransferTarget target : resolveTargets(subscription)) {
            result.add(new TransferredTarget(target.name(), targetDir(subscription, target), target.drive()));
        }
        return result;
    }

    /** 转存进度(前端显示):各目标网盘的已转存/源覆盖。 */
    public Map<String, Object> progress(int uid, int id) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new BadRequestException("订阅不存在: " + id);
        }
        List<TransferTarget> targets = resolveTargets(subscription);
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
        for (TransferTarget target : targets) {
            int transferred = listTargetEpisodes(site, subscription, targetDir(subscription, target)).size();
            accounts.add(Map.of("account", target.name(), "transferred", transferred));
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
                for (TransferTarget target : resolveTargets(subscription)) {
                    String targetDir = targetDir(subscription, target);
                    aListService.remove(site, targetDir);
                }
                // 上面远端删除要数分钟,期间订阅可能被删:落库前重取,不复活已删行
                MediaSubscription current = subscriptionRepository.findById(subscription.getId()).orElse(null);
                if (current == null) {
                    continue;
                }
                current.setMode(MediaSubscription.MODE_FOLLOW);
                current.setUpdatedTime(System.currentTimeMillis());
                subscriptionRepository.save(current);
                checkService.addEvent(current.getId(), MediaSubscriptionEvent.TYPE_ARCHIVED,
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
