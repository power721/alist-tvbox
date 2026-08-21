package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriveId;
import cn.har01d.alist_tvbox.dto.IndexRequest;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionFilter;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.IndexTemplate;
import cn.har01d.alist_tvbox.entity.IndexTemplateRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEventRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.service.metadata.MetadataService;
import cn.har01d.alist_tvbox.util.TextUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 追剧订阅巡检:三级递进(重列主源 → 失效换源 → 搜索补源)把搜索开销压到最低;
 * 官方元数据(§4.8)提供缺集检测权威触发与播出日程调度;缺集时探测候选并挂"补缺"源合并播放。
 * 换源 = 删旧挂载后在同一固定路径重挂新分享(mount_path 不变,播放历史不断链)。
 */
@Slf4j
@Service
public class MediaSubscriptionCheckService {
    /** 分享类型码(Message.type):网盘类;magnet/ed2k/video 候选直接丢弃 */
    private static final Set<String> PAN_TYPES = Set.of("0", "1", "2", "3", "5", "6", "7", "8", "9", "10", "12");
    private static final Pattern SEASON_EPISODE = Pattern.compile("[Ss](\\d{1,2})[Ee](\\d{1,3})");
    private static final Pattern NUMBER = Pattern.compile("(\\d{1,4})");
    /** 全局主网盘 Setting key(逗号分隔分享类型码;订阅级 main_drives 覆盖) */
    public static final String MSUB_MAIN_DRIVES = "msub_main_drives";
    /** 预告/花絮等非正片 */
    private static final Pattern EXTRA = Pattern.compile("(?i)(pv|ncop|nced|sample|trailer|menu|预告|花絮|彩蛋|ost)");
    /** 完结资源包形态:对追更中的订阅不会持续更新 */
    private static final Pattern COMPLETE_PACK = Pattern.compile("全\\s*\\d{1,3}\\s*集|全集|完整版|已?完结");
    /** 扫集号前先剥掉的技术标签(避免 1080/2160/4K 被当成集数) */
    private static final Pattern TECH_TAGS = Pattern.compile(
            "(?i)(2160p|1080p|720p|480p|4k|8k|h\\.?26[45]|x\\.?26[45]|hevc|avc|aac|dts|flac|ac3|10bit|8bit|sdr|hdr10?|dolby|dv|web-?dl|bdrip|blu-?ray|remux|国语|粤语|中字|简体|繁体|双语|字幕)");
    /** 标题级季标记:中文"第N季"、SxxEyy 的季、独立 Sxx、Season N */
    private static final Pattern TITLE_SEASON_CN = Pattern.compile("第\\s*([0-9一二三四五六七八九十]{1,3})\\s*季");
    private static final Pattern TITLE_SEASON_SXXE = Pattern.compile("[Ss](\\d{1,2})\\s*[Ee]\\d{1,3}");
    private static final Pattern TITLE_SEASON_ALONE = Pattern.compile("(?:^|[^A-Za-z0-9])[Ss](\\d{1,2})(?![\\dEe])");
    private static final Pattern TITLE_SEASON_EN = Pattern.compile("(?i)season\\s*(\\d{1,2})");
    /** 标题宣称的集数进度:更新至N / 全N集 / 第A-B集 / 第N集 / EPn(取最大值) */
    private static final Pattern TITLE_PROGRESS = Pattern.compile(
            "(?i)更新?至\\s*(\\d{1,3})|全\\s*(\\d{1,3})\\s*集|第\\s*(\\d{1,3})\\s*[-~至]\\s*(\\d{1,3})\\s*集|第\\s*(\\d{1,3})\\s*集|(?:^|[^a-z])e(?:p)?\\s*(\\d{1,3})(?!\\d)");
    private static final String INDEX_TEMPLATE_NAME = "追剧";
    /** 补缺源内部目录(藏于 /追剧/ 下的点目录,用户视角每部剧只有一个文件夹入口) */
    private static final String GAP_SOURCES_ROOT = cn.har01d.alist_tvbox.util.Constants.SUBSCRIPTION_MOUNT_ROOT + ".sources/";
    /** AList 整体不可用时本轮跳过后的短间隔重试(15min,下个每小时 sweep 即可捞到) */
    private static final long INVALID_RETRY_DELAY_MS = 15 * 60_000L;

    private final MediaSubscriptionRepository subscriptionRepository;
    private final MediaSubscriptionResourceRepository resourceRepository;
    private final MediaSubscriptionEventRepository eventRepository;
    private final ShareRepository shareRepository;
    private final SiteRepository siteRepository;
    private final cn.har01d.alist_tvbox.entity.DriverAccountRepository driverAccountRepository;
    private final IndexTemplateRepository indexTemplateRepository;
    private final SettingRepository settingRepository;
    private final ShareService shareService;
    private final AListService aListService;
    private final TelegramService telegramService;
    private final MetadataService metadataService;
    private final AutoUpdateExecutor autoUpdateExecutor;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();
    /** 缺集补搜关键词轮次(0=整季,1+=单集),内存态即可 */
    private final Map<Integer, Integer> gapSearchRounds = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "msub-check");
        thread.setDaemon(true);
        return thread;
    });

    public MediaSubscriptionCheckService(MediaSubscriptionRepository subscriptionRepository,
                                         MediaSubscriptionResourceRepository resourceRepository,
                                         MediaSubscriptionEventRepository eventRepository,
                                         ShareRepository shareRepository,
                                         SiteRepository siteRepository,
                                         cn.har01d.alist_tvbox.entity.DriverAccountRepository driverAccountRepository,
                                         IndexTemplateRepository indexTemplateRepository,
                                         SettingRepository settingRepository,
                                         ShareService shareService,
                                         AListService aListService,
                                         TelegramService telegramService,
                                         MetadataService metadataService,
                                         AutoUpdateExecutor autoUpdateExecutor,
                                         AppProperties appProperties,
                                         ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.resourceRepository = resourceRepository;
        this.eventRepository = eventRepository;
        this.shareRepository = shareRepository;
        this.siteRepository = siteRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.indexTemplateRepository = indexTemplateRepository;
        this.settingRepository = settingRepository;
        this.shareService = shareService;
        this.aListService = aListService;
        this.telegramService = telegramService;
        this.metadataService = metadataService;
        this.autoUpdateExecutor = autoUpdateExecutor;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 每小时第 20 分钟扫描到期订阅(避开 :00/:30 分享校验高峰),jitter 后转单线程执行器串行处理。 */
    @Scheduled(cron = "0 20 * * * *")
    public void sweep() {
        if (!appProperties.getSubscription().isEnabled()) {
            return;
        }
        autoUpdateExecutor.scheduleWithJitter(() -> executor.submit(this::sweepDue));
    }

    void sweepDue() {
        int limit = appProperties.getSubscription().getMaxChecksPerRound();
        List<MediaSubscription> due = new ArrayList<>(subscriptionRepository
                .findByStatusAndNextCheckTimeLessThanEqualOrderByNextCheckTimeAsc(
                        MediaSubscription.STATUS_ACTIVE, System.currentTimeMillis(), PageRequest.of(0, limit))
                .getContent());
        // ENDED 订阅按到期时间参与轻量复查(官方加更/集数修正自动重开,每日节奏)
        due.addAll(subscriptionRepository
                .findByStatusAndNextCheckTimeLessThanEqualOrderByNextCheckTimeAsc(
                        MediaSubscription.STATUS_ENDED, System.currentTimeMillis(), PageRequest.of(0, limit))
                .getContent());
        log.info("media subscription sweep: {} due (limit {})", due.size(), limit);
        for (MediaSubscription subscription : due) {
            try {
                check(subscription.getId());
            } catch (Exception e) {
                log.warn("check subscription {} failed: {}", subscription.getId(), e.getMessage());
            }
        }
        retryErrors();
    }

    /** ERROR 自愈(§10.6):每日自动重试一次;连续 7 天失败提示人工检查。 */
    private void retryErrors() {
        long now = System.currentTimeMillis();
        for (MediaSubscription subscription : subscriptionRepository.findAll()) {
            if (!MediaSubscription.STATUS_ERROR.equals(subscription.getStatus())) {
                continue;
            }
            long last = subscription.getLastCheckTime() == null ? 0 : subscription.getLastCheckTime();
            if (now - last < 20L * 3600_000) {
                continue;
            }
            if (subscription.getUpdatedTime() != null && now - subscription.getUpdatedTime() > 7L * 24 * 3600_000
                    && now - last < 26L * 3600_000) {
                addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "连续失败超过 7 天,请人工检查(关键词/源可用性)");
            }
            log.info("retry ERROR subscription {}", subscription.getId());
            try {
                check(subscription.getId());
            } catch (Exception e) {
                log.warn("retry subscription {} failed: {}", subscription.getId(), e.getMessage());
            }
        }
    }

    /** 手动触发检查(异步,前端刷新列表看结果)。 */
    public void checkAsync(int uid, int id) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new cn.har01d.alist_tvbox.exception.BadRequestException("订阅不存在: " + id);
        }
        executor.submit(() -> check(id));
    }

    /** 手动激活候选池中的指定资源(异步换源)。 */
    public void activateAsync(int uid, int id, int resourceId) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new cn.har01d.alist_tvbox.exception.BadRequestException("订阅不存在: " + id);
        }
        MediaSubscriptionResource resource = resourceRepository.findById(resourceId).orElse(null);
        if (resource == null || resource.getSubscriptionId() != id) {
            throw new cn.har01d.alist_tvbox.exception.BadRequestException("候选资源不存在: " + resourceId);
        }
        executor.submit(() -> {
            if (!tryLock(id)) {
                return;
            }
            try {
                activate(subscription, resource);
                subscriptionRepository.save(subscription);
            } catch (Exception e) {
                log.warn("activate resource {} failed: {}", resourceId, e.getMessage());
                resource.setValidity(MediaSubscriptionResource.VALIDITY_BAD);
                resource.setCheckedTime(System.currentTimeMillis());
                resourceRepository.save(resource);
                addEvent(id, MediaSubscriptionEvent.TYPE_ERROR, "手动换源失败:" + e.getMessage());
            } finally {
                inFlight.remove(id);
            }
        });
    }

    public void check(Integer id) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || MediaSubscription.STATUS_PAUSED.equals(subscription.getStatus())) {
            return;
        }
        if (MediaSubscription.STATUS_ENDED.equals(subscription.getStatus()) && !reopenEnded(subscription)) {
            subscription.setNextCheckTime(System.currentTimeMillis() + 24 * 3600_000L); // 每日复查一次
            subscriptionRepository.save(subscription);
            return;
        }
        if (!tryLock(id)) {
            log.debug("subscription {} check already running", id);
            return;
        }
        try {
            doCheck(subscription);
            subscriptionRepository.save(subscription);
        } catch (Exception e) {
            log.warn("check subscription {} failed: {}", id, e.getMessage(), e);
            subscription.setStatus(MediaSubscription.STATUS_ERROR);
            subscription.setUpdatedTime(System.currentTimeMillis());
            addEvent(id, MediaSubscriptionEvent.TYPE_ERROR, "巡检失败:" + e.getMessage());
            scheduleNext(subscription);
            subscriptionRepository.save(subscription);
        } finally {
            inFlight.remove(id);
        }
    }

    /** ENDED 订阅每日轻量复查(只刷元数据,不列源不搜索):官方已播/手填期望超过本地集数 = 加更或集数修正,自动回 ACTIVE。 */
    private boolean reopenEnded(MediaSubscription subscription) {
        refreshMetadata(subscription);
        if (!shouldReopen(subscription)) {
            return false;
        }
        int local = subscription.getCurrentEpisodes() == null ? 0 : subscription.getCurrentEpisodes();
        int target = Math.max(
                subscription.getOfficialEpisodes() == null ? 0 : subscription.getOfficialEpisodes(),
                subscription.getExpectedEpisodes() == null ? 0 : subscription.getExpectedEpisodes());
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setStallCount(0);
        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_RESUMED,
                "官方集数上调(" + local + " → " + target + "),自动重开追更");
        log.info("subscription {} reopened: {} -> {}", subscription.getId(), local, target);
        return true;
    }

    /** 重开判定:官方已播或手填期望集数 > 本地集数。 */
    boolean shouldReopen(MediaSubscription subscription) {
        int local = subscription.getCurrentEpisodes() == null ? 0 : subscription.getCurrentEpisodes();
        Integer official = subscription.getOfficialEpisodes();
        Integer expected = subscription.getExpectedEpisodes();
        return (official != null && official > local) || (expected != null && expected > local);
    }

    private void doCheck(MediaSubscription subscription) {
        subscription.setLastCheckTime(System.currentTimeMillis());
        refreshMetadata(subscription);

        if (subscription.getShareId() == null || shareRepository.findById(subscription.getShareId()).isEmpty()) {
            ensureSource(subscription);
            scheduleNext(subscription);
            return;
        }

        // 失效确认:列目录失败先静默重试一次(瞬时抖动);仍失败再探测 AList 健康,
        // 服务整体不可用时不能把失败归因于主源(防误杀好源+BAD 污染候选池)
        Set<Integer> episodes = null;
        String invalidReason = null;
        for (int attempt = 1; attempt <= 2 && episodes == null; attempt++) {
            try {
                episodes = listEpisodes(subscription);
            } catch (Exception e) {
                invalidReason = e.getMessage();
                log.info("subscription {} primary listing failed (attempt {}): {}", subscription.getId(), attempt, e.getMessage());
            }
        }
        if (episodes == null) {
            if (!isAListHealthy()) {
                log.warn("subscription {} skipped: AList unavailable, retry later", subscription.getId());
                subscription.setNextCheckTime(System.currentTimeMillis() + INVALID_RETRY_DELAY_MS);
                return;
            }
            onInvalid(subscription, invalidReason);
            scheduleNext(subscription);
            return;
        }

        List<Integer> added = applyInventory(subscription, episodes);

        // 缺集检测:官方已播集数是权威触发源(§4.8);无官方数据回退期望集数/观测范围
        episodes.removeAll(preheatEpisodes(subscription, added));
        Set<Integer> missing = computeMissing(subscription, episodes);
        if (!missing.isEmpty()) {
            fillGaps(subscription, new TreeSet<>(missing));
        } else {
            retireGapMounts(subscription, episodes);
            // 停滞多轮且池中无可用备胎 → 搜索补池(主源未失效不主动换源,避免频繁扰动播放列表)
            if (subscription.getStallCount() >= appProperties.getSubscription().getStallRoundsBeforeSearch()) {
                fillPool(subscription, false, null);
            }
            detectUpgrade(subscription, episodes);
        }
        ensureMainDrives(subscription, episodes);
        scheduleNext(subscription);
    }

    /** 版本升级提醒(§10.7):主源无 4K 而池中出现 4K 完整候选 → 提示(不自动替换)。 */
    private void detectUpgrade(MediaSubscription subscription, Set<Integer> present) {
        if (subscription.getStallCount() < appProperties.getSubscription().getStallRoundsBeforeSearch() || present.isEmpty()) {
            return;
        }
        List<MediaSubscriptionResource> resources = resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId());
        MediaSubscriptionResource active = resources.stream().filter(MediaSubscriptionResource::isActive).findFirst().orElse(null);
        if (active == null || hasUhd(active.getTitle())) {
            return;
        }
        MediaSubscriptionResource candidate = resources.stream()
                .filter(r -> !r.isActive() && !r.isGap() && !MediaSubscriptionResource.VALIDITY_BAD.equals(r.getValidity())
                        && r.getEpisodeList() == null && hasUhd(r.getTitle()))
                .findFirst().orElse(null);
        if (candidate == null) {
            return;
        }
        try {
            Set<Integer> coverage = probeShare(subscription, candidate);
            candidate.setEpisodeList(serializeEpisodes(coverage));
            candidate.setCheckedTime(System.currentTimeMillis());
            resourceRepository.save(candidate);
            if (!coverage.isEmpty() && coverage.containsAll(present)) {
                addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_UPGRADE_AVAILABLE,
                        "发现更优画质完整源:" + StringUtils.defaultIfBlank(candidate.getTitle(), candidate.getLink())
                                + "(" + coverage.size() + "集 · 4K),可在候选池手动启用");
            }
        } catch (Exception e) {
            log.debug("upgrade probe failed: {}", e.getMessage());
        }
    }

    private static boolean hasUhd(String title) {
        return StringUtils.containsIgnoreCase(title, "4K") || StringUtils.containsIgnoreCase(title, "2160");
    }

    // ---------- 元数据(§4.8) ----------

    /** 每日至多一次刷新官方集数/状态/下集播出时间;失败静默降级,不影响巡检。 */
    private void refreshMetadata(MediaSubscription subscription) {
        if (StringUtils.isBlank(subscription.getMetaProvider()) || StringUtils.isBlank(subscription.getMetaId())) {
            return;
        }
        long now = System.currentTimeMillis();
        long interval = appProperties.getSubscription().getMetaRefreshIntervalHours() * 3600_000L;
        if (subscription.getMetaSyncTime() != null && now - subscription.getMetaSyncTime() < interval) {
            return;
        }
        subscription.setMetaSyncTime(now);
        MetadataDetails details = metadataService.details(subscription.getMetaProvider(), subscription.getMetaId(), subscription.getSeason());
        if (details == null) {
            return;
        }
        subscription.setOfficialEpisodes(details.getAiredEpisodes());
        subscription.setOfficialTotal(details.getTotalEpisodes());
        subscription.setOfficialStatus(details.getStatus());
        subscription.setNextAirTime(details.getNextAirTime());
        if (details.getAliases() != null) {
            // 别名快照(换行分隔):标题归属匹配用;单条过长/为空的丢弃,总量限幅
            String joined = details.getAliases().stream()
                    .map(String::trim)
                    .filter(a -> a.length() >= 2 && a.length() <= 100)
                    .distinct()
                    .limit(12)
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse(null);
            subscription.setAliases(joined);
        }
        // 播出日程快照(昨日 00:00 ~ +14 天窗口,播放时间轴用);provider 未提供日程时保留旧快照
        if (details.getUpcoming() != null) {
            java.time.ZoneId zone = java.time.ZoneId.of(cn.har01d.alist_tvbox.util.Constants.ZONE_ID);
            long windowStart = java.time.LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long windowEnd = System.currentTimeMillis() + 14L * 24 * 3600_000;
            List<cn.har01d.alist_tvbox.dto.EpisodeAirDate> windowed = details.getUpcoming().stream()
                    .filter(e -> e.getAirTime() >= windowStart && e.getAirTime() <= windowEnd)
                    .limit(60).toList();
            try {
                subscription.setSchedule(objectMapper.writeValueAsString(windowed));
            } catch (Exception e) {
                log.debug("serialize schedule failed: {}", e.getMessage());
            }
        }
    }

    /** 缺口 = 1..base 中本地没有的集;base = max(观测最大, 官方已播, 期望集数)。 */
    Set<Integer> computeMissing(MediaSubscription subscription, Set<Integer> present) {
        int base = present.stream().max(Integer::compareTo).orElse(0);
        if (subscription.getOfficialEpisodes() != null) {
            base = Math.max(base, subscription.getOfficialEpisodes());
        }
        if (subscription.getExpectedEpisodes() != null) {
            base = Math.max(base, subscription.getExpectedEpisodes());
        }
        // base 上限保护:官方数据异常时不至于搜上百集
        if (base <= 0 || base > 500) {
            return Set.of();
        }
        Set<Integer> missing = new TreeSet<>();
        for (int i = 1; i <= base; i++) {
            if (!present.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    // ---------- 缺集补搜与补缺挂载(需求 1) ----------

    /**
     * 探测候选池(临时挂载列集数,用后即删),覆盖缺口的资源挂为"补缺"源(mountPath-补N,常驻,清理豁免)。
     * 已挂载的补缺源:直接刷新其挂载目录覆盖快照(挂载原地增长)并从缺口扣除 —— 不重复探测、不重复挂载、不重复事件。
     * 挂载数达上限(maxGapMounts)后不再探测新候选;池耗尽仍缺 → 搜索:先整季关键词,再逐集降级(第N集)。
     */
    private void fillGaps(MediaSubscription subscription, Set<Integer> missingStill) {
        int gapMounted = 0;
        int maxMounts = appProperties.getSubscription().getMaxGapMounts();
        for (MediaSubscriptionResource resource : candidatesOrdered(subscription)) {
            if (resource.isGap() && resource.getShareId() != null && StringUtils.isNotBlank(resource.getMountPath())) {
                gapMounted++;
                migrateLegacyGapMount(subscription, resource);
                // 补缺挂载原位刷新覆盖快照(不临时挂载探测),并从剩余缺口中扣除
                try {
                    Set<Integer> coverage = walkEpisodes(site(), subscription.getSeason(),
                            resource.getMountPath(), maxEpisodeBytes(subscription));
                    if (!coverage.isEmpty()) {
                        resource.setEpisodeList(serializeEpisodes(coverage));
                        resource.setCheckedTime(System.currentTimeMillis());
                        resourceRepository.save(resource);
                    }
                } catch (Exception e) {
                    log.debug("refresh gap mount coverage failed: {} {}", resource.getMountPath(), e.getMessage());
                }
                missingStill.removeAll(new TreeSet<>(parseEpisodeList(resource.getEpisodeList())));
            }
        }

        int probed = 0;
        int maxProbes = appProperties.getSubscription().getMaxGapProbesPerRound();
        for (MediaSubscriptionResource resource : candidatesOrdered(subscription)) {
            if (probed >= maxProbes || missingStill.isEmpty() || gapMounted >= maxMounts) {
                break;
            }
            if (resource.isActive() || resource.getShareId() != null) {
                continue; // 主源或已挂载(补缺)的不在此处理
            }
            Set<Integer> coverage = new TreeSet<>(parseEpisodeList(resource.getEpisodeList()));
            boolean known = resource.getEpisodeList() != null;
            Set<Integer> useful = intersection(coverage, missingStill);
            if (known && useful.isEmpty()) {
                continue; // 已探测过且不覆盖剩余缺口
            }
            try {
                coverage = probeShare(subscription, resource);
                resource.setEpisodeList(serializeEpisodes(coverage));
                resource.setCheckedTime(System.currentTimeMillis());
                probed++;
            } catch (Exception e) {
                log.info("probe candidate {} failed: {}", resource.getId(), e.getMessage());
                resource.setValidity(MediaSubscriptionResource.VALIDITY_BAD);
                resource.setCheckedTime(System.currentTimeMillis());
                resourceRepository.save(resource);
                continue;
            }
            useful = intersection(coverage, missingStill);
            if (!useful.isEmpty()) {
                try {
                    if (mountGap(subscription, resource)) {
                        gapMounted++;
                        missingStill.removeAll(useful);
                        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_GAP_FILLED,
                                "补缺 第" + joinNumbers(new ArrayList<>(useful)) + " 集(来自 " + StringUtils.defaultIfBlank(resource.getTitle(), "候选源") + ")");
                        gapSearchRounds.remove(subscription.getId());
                    }
                } catch (Exception e) {
                    log.warn("mount gap source failed: {}", e.getMessage());
                    addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "补缺挂载失败:" + e.getMessage());
                }
            }
            resourceRepository.save(resource);
        }

        if (!missingStill.isEmpty()) {
            int round = gapSearchRounds.merge(subscription.getId(), 1, Integer::sum);
            String keyword = gapSearchKeyword(subscription, missingStill, round);
            if (keyword != null) {
                fillPool(subscription, true, keyword);
            }
        }
    }

    /** 补搜关键词决策:播出窗口内且缺口只含官方已播最新一集 = 资源大概率未上线,
     * 保持整季关键词且隔轮限频(空搜节制,窗口过后恢复逐集降级);其余场景整季(首轮)→单集降级。
     * @return null = 本轮跳过搜索(限频) */
    String gapSearchKeyword(MediaSubscription subscription, Set<Integer> missing, int round) {
        if (inPostAirWindow(subscription) && latestOnlyGap(subscription, missing)) {
            return round % 2 == 1 ? seasonKeyword(subscription) : null;
        }
        if (round == 1) {
            return seasonKeyword(subscription);
        }
        // 单集降级:逐次尝试不同缺失集
        List<Integer> list = new ArrayList<>(missing);
        int index = Math.min(round - 2, list.size() - 1);
        return subscription.getName() + " 第" + list.get(Math.max(index, 0)) + "集";
    }

    private String seasonKeyword(MediaSubscription subscription) {
        return StringUtils.defaultIfBlank(subscription.getKeyword(), subscription.getName());
    }

    /** 是否处于播出后短轮窗口:窗口内新集资源可能尚未上线,缺最新集不构成"资源缺失"。 */
    boolean inPostAirWindow(MediaSubscription subscription) {
        Long air = subscription.getNextAirTime();
        if (air == null || air <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        return now >= air && now < air + appProperties.getSubscription().getShortPollWindowHours() * 3600_000L;
    }

    /** 缺口只含官方已播的最新一集(老集都齐,只差刚播的这集)。 */
    boolean latestOnlyGap(MediaSubscription subscription, Set<Integer> missing) {
        Integer aired = subscription.getOfficialEpisodes();
        return aired != null && aired > 0 && !missing.isEmpty()
                && missing.stream().allMatch(episode -> episode.equals(aired));
    }

    private List<MediaSubscriptionResource> candidatesOrdered(MediaSubscription subscription) {
        long now = System.currentTimeMillis();
        return resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId()).stream()
                .filter(r -> !MediaSubscriptionResource.VALIDITY_BAD.equals(r.getValidity()) || isBadCooled(r, now))
                .toList();
    }

    /** 主网盘:订阅级 main_drives 覆盖 > 全局 Setting msub_main_drives(均为逗号分隔分享类型码,取前 2)。
     * 巡检保证该盘完整剧集覆盖,播放列表固定出该盘线路。 */
    List<String> mainDrives(MediaSubscription subscription) {
        String raw = subscription.getMainDrives();
        if (StringUtils.isBlank(raw)) {
            raw = settingRepository.findById(MSUB_MAIN_DRIVES).map(s -> s.getValue()).orElse("");
        }
        if (StringUtils.isBlank(raw)) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(DriveId::toTypeOrNull)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .limit(2)
                .map(DriveId::toDrive)
                .toList();
    }

    /** 当前主源所在盘(active 资源行的分享类型;旧数据无 type 返回 null)。 */
    String activeDrive(MediaSubscription subscription) {
        return resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId()).stream()
                .filter(MediaSubscriptionResource::isActive).findFirst()
                .filter(r -> r.getType() != null)
                .map(r -> DriveId.toDrive(r.getType()))
                .orElse(null);
    }

    /** 主网盘完整覆盖保障:观测全集(主源 ∪ 各补缺挂载快照)按盘核算,主网盘缺口从候选池**同盘**资源探则挂
     * (与 fillGaps 同机制但按盘约束,主源所在盘天然计为已覆盖)。池内无该盘资源不强制搜索——
     * driveTypes 偏好已让搜索召回偏向主网盘,靠常规搜索周期自然补池;转存副本不计入(自有事后校验保障)。
     * 分享挂载均为游客态(免登录);需登录态才稳定的盘探测会失败落 BAD,自然退出候选。 */
    void ensureMainDrives(MediaSubscription subscription, Set<Integer> primaryEpisodes) {
        List<String> mains = mainDrives(subscription);
        if (mains.isEmpty() || primaryEpisodes.isEmpty()) {
            return;
        }
        List<MediaSubscriptionResource> resources = resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId());
        String active = activeDrive(subscription);
        Set<Integer> union = new TreeSet<>(primaryEpisodes);
        for (MediaSubscriptionResource resource : resources) {
            if (resource.isGap() && resource.getShareId() != null) {
                union.addAll(parseEpisodeList(resource.getEpisodeList()));
            }
        }
        int mounted = 0;
        int maxMounts = appProperties.getSubscription().getMaxGapMounts();
        for (String drive : mains) {
            Set<Integer> coverage = new TreeSet<>();
            if (drive.equals(active)) {
                coverage.addAll(primaryEpisodes);
            }
            for (MediaSubscriptionResource resource : resources) {
                if (resource.isGap() && resource.getShareId() != null && StringUtils.isNotBlank(resource.getMountPath())
                        && resource.getType() != null && drive.equals(DriveId.toDrive(resource.getType()))) {
                    coverage.addAll(parseEpisodeList(resource.getEpisodeList()));
                }
            }
            Set<Integer> missing = new TreeSet<>(union);
            missing.removeAll(coverage);
            if (missing.isEmpty()) {
                continue;
            }
            int probed = 0;
            int maxProbes = appProperties.getSubscription().getMaxGapProbesPerRound();
            for (MediaSubscriptionResource resource : resources) {
                if (missing.isEmpty() || probed >= maxProbes || mounted >= maxMounts) {
                    break;
                }
                if (resource.isActive() || resource.isGap() || resource.getShareId() != null
                        || resource.getType() == null || !drive.equals(DriveId.toDrive(resource.getType()))) {
                    continue;
                }
                Set<Integer> candidate = new TreeSet<>(parseEpisodeList(resource.getEpisodeList()));
                if (resource.getEpisodeList() != null && intersection(candidate, missing).isEmpty()) {
                    continue; // 已探测过且不覆盖主网盘缺口
                }
                try {
                    candidate = probeShare(subscription, resource);
                    resource.setEpisodeList(serializeEpisodes(candidate));
                    resource.setCheckedTime(System.currentTimeMillis());
                    probed++;
                } catch (Exception e) {
                    log.info("probe main-drive candidate {} failed: {}", resource.getId(), e.getMessage());
                    resource.setValidity(MediaSubscriptionResource.VALIDITY_BAD);
                    resource.setCheckedTime(System.currentTimeMillis());
                    resourceRepository.save(resource);
                    continue;
                }
                Set<Integer> useful = intersection(candidate, missing);
                if (!useful.isEmpty()) {
                    try {
                        if (mountGap(subscription, resource)) {
                            mounted++;
                            missing.removeAll(useful);
                            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_GAP_FILLED,
                                    "主网盘[" + drive + "] 补齐 第" + joinNumbers(new ArrayList<>(useful)) + " 集(来自 "
                                            + StringUtils.defaultIfBlank(resource.getTitle(), "候选源") + ")");
                        }
                    } catch (Exception e) {
                        log.warn("mount main-drive source failed: {}", e.getMessage());
                        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "主网盘补缺挂载失败:" + e.getMessage());
                    }
                }
                resourceRepository.save(resource);
            }
            if (!missing.isEmpty()) {
                log.info("subscription {} main drive [{}] still missing episodes {} (pool has no covering candidate)",
                        subscription.getId(), drive, missing);
            }
        }
    }

    /** BAD 冷却超期 = 允许重探一次(历史误标自愈;池仅 TopN 席位,BAD 永久退出会耗尽池);重探再失败会刷新计时。 */
    boolean isBadCooled(MediaSubscriptionResource resource, long now) {
        if (!MediaSubscriptionResource.VALIDITY_BAD.equals(resource.getValidity())) {
            return false;
        }
        Long checked = resource.getCheckedTime();
        long cooldown = appProperties.getSubscription().getBadCooldownDays() * 24L * 3600_000;
        return checked == null || now - checked >= cooldown;
    }

    /** 临时挂载候选列集数,用后即删(不常驻,不占 AList 挂载)。 */
    private Set<Integer> probeShare(MediaSubscription subscription, MediaSubscriptionResource resource) {
        ShareLink shareLink = new ShareLink();
        shareLink.setLink(resource.getLink());
        shareLink.setCode(StringUtils.defaultString(resource.getPassword()));
        shareService.add(shareLink);
        Share probe = shareService.parseShareLink(resource.getLink());
        Share share = null;
        if (probe != null) {
            List<Share> temps = shareRepository.findByTypeAndShareIdAndTempTrue(probe.getType(), probe.getShareId());
            if (!temps.isEmpty()) {
                share = temps.get(temps.size() - 1);
            }
        }
        if (share == null) {
            throw new IllegalStateException("临时挂载失败:" + resource.getLink());
        }
        try {
            return walkEpisodes(site(), subscription.getSeason(), share.getPath(), maxEpisodeBytes(subscription));
        } finally {
            try {
                shareService.deleteShare(share.getId());
            } catch (Exception e) {
                log.warn("delete probe share failed: {}", e.getMessage());
            }
        }
    }

    /** 旧版补缺挂载(/追剧/{mount}-补N 与主源并排暴露给用户)迁移到内部目录 /追剧/.sources/ 下;失败保旧路径下轮重试。 */
    private void migrateLegacyGapMount(MediaSubscription subscription, MediaSubscriptionResource resource) {
        String legacyPrefix = subscription.getMountPath() + "-补";
        if (!resource.getMountPath().startsWith(legacyPrefix)) {
            return;
        }
        try {
            String slug = subscription.getMountPath().substring(cn.har01d.alist_tvbox.util.Constants.SUBSCRIPTION_MOUNT_ROOT.length());
            int n = 1;
            String path = GAP_SOURCES_ROOT + slug + "-补" + n;
            while (shareRepository.existsByPath(path)) {
                n++;
                path = GAP_SOURCES_ROOT + slug + "-补" + n;
            }
            Share old = shareRepository.findByPath(resource.getMountPath());
            if (old != null) {
                shareService.deleteShare(old.getId());
            }
            ShareLink shareLink = new ShareLink();
            shareLink.setLink(resource.getLink());
            shareLink.setCode(StringUtils.defaultString(resource.getPassword()));
            shareLink.setPath(path);
            shareService.add(shareLink);
            Share share = shareRepository.findByPath(path);
            if (share == null) {
                throw new IllegalStateException("迁移补缺挂载失败:" + resource.getLink());
            }
            resource.setMountPath(path);
            resource.setShareId(share.getId());
            resourceRepository.save(resource);
            log.info("migrated gap mount of subscription {} to {}", subscription.getId(), path);
        } catch (Exception e) {
            log.warn("migrate legacy gap mount failed, keep old path: {}", e.getMessage());
            // 旧挂载已删而新挂载失败:清掉挂载字段并作废 stale 覆盖快照,
            // 否则幽灵 episode_list 会把这些集从缺口中扣除、从播放列表消失;下轮作普通候选重探自愈
            resource.setGap(false);
            resource.setMountPath(null);
            resource.setShareId(null);
            resource.setEpisodeList(null);
            resource.setCheckedTime(System.currentTimeMillis());
            resourceRepository.save(resource);
        }
    }

    /** 挂补缺源到内部目录 /追剧/.sources/{slug}-补N(用户视角 /追剧/ 下每部剧只有一个入口;常驻非 temp,清理豁免)。@return 是否真正新挂载(false=已挂载) */
    private boolean mountGap(MediaSubscription subscription, MediaSubscriptionResource resource) {
        if (resource.getShareId() != null && StringUtils.isNotBlank(resource.getMountPath())) {
            return false; // 已挂载
        }
        String slug = subscription.getMountPath().substring(cn.har01d.alist_tvbox.util.Constants.SUBSCRIPTION_MOUNT_ROOT.length());
        int n = 1;
        String path = GAP_SOURCES_ROOT + slug + "-补" + n;
        while (shareRepository.existsByPath(path)) {
            n++;
            path = GAP_SOURCES_ROOT + slug + "-补" + n;
        }
        ShareLink shareLink = new ShareLink();
        shareLink.setLink(resource.getLink());
        shareLink.setCode(StringUtils.defaultString(resource.getPassword()));
        shareLink.setPath(path);
        shareService.add(shareLink);
        Share share = shareRepository.findByPath(path);
        if (share == null) {
            throw new IllegalStateException("补缺挂载失败:" + resource.getLink());
        }
        resource.setGap(true);
        resource.setMountPath(path);
        resource.setShareId(share.getId());
        resource.setValidity(MediaSubscriptionResource.VALIDITY_OK);
        return true;
    }

    /** 主源已覆盖补缺源全部集数 → 退役补缺挂载(删 share,资源保留为普通候选)。 */
    private void retireGapMounts(MediaSubscription subscription, Set<Integer> present) {
        List<String> mains = mainDrives(subscription);
        String active = activeDrive(subscription);
        for (MediaSubscriptionResource resource : resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId())) {
            if (!resource.isGap() || resource.getShareId() == null) {
                continue;
            }
            String drive = resource.getType() == null ? null : DriveId.toDrive(resource.getType());
            if (drive != null && mains.contains(drive) && !drive.equals(active)) {
                continue; // 主网盘冗余挂载:即使主源已覆盖也保留,主源换盘/失效时该盘线路不断供
            }
            Set<Integer> coverage = new TreeSet<>(parseEpisodeList(resource.getEpisodeList()));
            if (coverage.isEmpty() || present.containsAll(coverage)) {
                try {
                    shareService.deleteShare(resource.getShareId());
                } catch (Exception e) {
                    log.warn("retire gap mount failed: {}", e.getMessage());
                    continue;
                }
                resource.setGap(false);
                resource.setMountPath(null);
                resource.setShareId(null);
                resourceRepository.save(resource);
                log.info("subscription {} retired gap mount (fully covered by primary)", subscription.getId());
            }
        }
    }

    // ---------- 换源 ----------

    /** 首次挂载或主源行丢失:搜一遍填池并激活最优候选。 */
    private void ensureSource(MediaSubscription subscription) {
        fillPool(subscription, true, null);
        if (!activateNextCandidate(subscription)) {
            subscription.setStatus(MediaSubscription.STATUS_ERROR);
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "未找到可用资源,请检查关键词或稍后重试");
        }
    }

    private void onInvalid(MediaSubscription subscription, String reason) {
        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_SOURCE_INVALID, "主源失效:" + StringUtils.defaultString(reason));
        resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId()).stream()
                .filter(MediaSubscriptionResource::isActive)
                .forEach(r -> {
                    r.setValidity(MediaSubscriptionResource.VALIDITY_BAD);
                    r.setActive(false);
                    r.setCheckedTime(System.currentTimeMillis());
                    resourceRepository.save(r);
                });
        if (!activateNextCandidate(subscription)) {
            fillPool(subscription, true, null);
            if (!activateNextCandidate(subscription)) {
                subscription.setStatus(MediaSubscription.STATUS_ERROR);
                addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "主源失效且无可用候选");
            }
        }
    }

    /** 按分数依次尝试候选,失败标记 BAD 换下一个;成功则重挂到同一固定路径。 */
    private boolean activateNextCandidate(MediaSubscription subscription) {
        for (MediaSubscriptionResource resource : candidatesOrdered(subscription)) {
            if (!resource.isActive()) {
                try {
                    activate(subscription, resource);
                    return true;
                } catch (Exception e) {
                    log.info("candidate {} invalid: {}", resource.getId(), e.getMessage());
                    resource.setValidity(MediaSubscriptionResource.VALIDITY_BAD);
                    resource.setCheckedTime(System.currentTimeMillis());
                    resourceRepository.save(resource);
                }
            }
        }
        return false;
    }

    /** 换源核心:删旧挂载 → 同路径挂新分享 → 重列验证。mount_path 不变,播放历史不断链。 */
    protected void activate(MediaSubscription subscription, MediaSubscriptionResource resource) {
        String mountPath = subscription.getMountPath();
        Share old = shareRepository.findByPath(mountPath);
        if (old != null) {
            shareService.deleteShare(old.getId());
        }
        ShareLink shareLink = new ShareLink();
        shareLink.setLink(resource.getLink());
        shareLink.setCode(StringUtils.defaultString(resource.getPassword()));
        shareLink.setPath(mountPath);
        shareService.add(shareLink);

        Share share = shareRepository.findByPath(mountPath);
        if (share == null) {
            throw new IllegalStateException("挂载失败:" + resource.getLink());
        }
        Set<Integer> episodes = walkEpisodes(site(), subscription.getSeason(), mountPath, maxEpisodeBytes(subscription));
        if (episodes.isEmpty()) {
            throw new IllegalStateException("资源无可识别的剧集文件:" + resource.getTitle());
        }

        subscription.setShareId(share.getId());
        subscription.setStallCount(0);
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setBrokenEpisodes(null); // 换源 = 干净起点,旧源的损坏登记全部作废
        applyInventory(subscription, episodes);

        resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId()).forEach(r -> {
            if (r.getId().equals(resource.getId())) {
                r.setActive(true);
                r.setGap(false);
                r.setValidity(MediaSubscriptionResource.VALIDITY_OK);
                r.setEpisodesFound(episodes.size());
                r.setEpisodeList(serializeEpisodes(episodes));
                r.setCheckedTime(System.currentTimeMillis());
            } else {
                r.setActive(false);
            }
            resourceRepository.save(r);
        });
        String drive = resource.getType() == null ? "" : DriveId.toDrive(resource.getType());
        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_SOURCE_REPLACED,
                "已挂载:" + StringUtils.defaultIfBlank(resource.getTitle(), resource.getLink())
                        + "(" + episodes.size() + "集 · " + drive + ")");
        ensureIndexTemplate();
    }

    /** 索引模板联动:首次成功挂载后确保"追剧"索引模板存在(增量,排除 .sources 内部目录,否则补缺源会造成索引重复条目);已存在的旧模板补上排除项。 */
    private void ensureIndexTemplate() {
        try {
            String root = cn.har01d.alist_tvbox.util.Constants.SUBSCRIPTION_MOUNT_ROOT;
            String excludePath = "-" + GAP_SOURCES_ROOT; // IndexService 约定:paths 中 "-" 前缀 = 排除
            var existing = indexTemplateRepository.findAll().stream()
                    .filter(t -> INDEX_TEMPLATE_NAME.equals(t.getName())).findFirst().orElse(null);
            if (existing != null) {
                IndexRequest request;
                try {
                    request = objectMapper.readValue(existing.getData(), IndexRequest.class);
                } catch (Exception e) {
                    return;
                }
                if (request.getPaths() == null || !request.getPaths().contains(excludePath)) {
                    List<String> paths = new ArrayList<>(request.getPaths() == null
                            ? List.of(root) : request.getPaths().stream().filter(p -> !p.startsWith("-")).toList());
                    paths.add(excludePath);
                    request.setPaths(paths);
                    existing.setData(objectMapper.writeValueAsString(request));
                    indexTemplateRepository.save(existing);
                    log.info("updated subscription index template excludes: {}", GAP_SOURCES_ROOT);
                }
                return;
            }
            IndexRequest request = new IndexRequest();
            request.setSiteId(1);
            request.setIndexName(INDEX_TEMPLATE_NAME);
            request.setIncremental(true);
            request.setMaxDepth(3);
            request.setPaths(new ArrayList<>(List.of(root, excludePath)));
            IndexTemplate template = new IndexTemplate();
            template.setSiteId(1);
            template.setName(INDEX_TEMPLATE_NAME);
            template.setData(objectMapper.writeValueAsString(request));
            template.setScheduled(true);
            template.setScrape(false);
            template.setScheduleTime("10,12,14,16,18,19,20,21,22,23");
            template.setCreatedTime(Instant.now());
            indexTemplateRepository.save(template);
            log.info("created subscription index template: {}", INDEX_TEMPLATE_NAME);
        } catch (Exception e) {
            log.warn("ensure index template failed: {}", e.getMessage());
        }
    }

    // ---------- 集数清单 ----------

    /** 递归列出挂载目录,解析集数清单(SxxEyy 优先,否则取剥离技术标签后的最后一个数字)。
     * 统一走 walkEpisodeFiles:损坏集(被和谐)按文件的【实际目录】过滤 —— 嵌套子目录(Season 1/第N季)也能命中。 */
    Set<Integer> listEpisodes(MediaSubscription subscription) {
        return walkEpisodeFiles(subscription, false).keySet();
    }

    /** AList 健康探测:列根目录。用于区分"主源失效"与"服务整体不可用"(嵌入式 AList 重启/网络断)。 */
    private boolean isAListHealthy() {
        try {
            aListService.listFiles(site(), "/", 1, 0, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 损坏集登记表:JSON {集号: "源目录|时间戳"};解析并剔除 7 天以上过期项。 */
    Map<Integer, String> parseBroken(MediaSubscription subscription) {
        if (StringUtils.isBlank(subscription.getBrokenEpisodes())) {
            return Map.of();
        }
        try {
            Map<String, String> raw = objectMapper.readValue(subscription.getBrokenEpisodes(),
                    new TypeReference<java.util.LinkedHashMap<String, String>>() {
                    });
            Map<Integer, String> result = new java.util.LinkedHashMap<>();
            long expireBefore = System.currentTimeMillis() - 7L * 24 * 3600_000;
            raw.forEach((episode, value) -> {
                int index = value.lastIndexOf('|');
                long timestamp = 0;
                try {
                    timestamp = Long.parseLong(value.substring(index + 1));
                } catch (NumberFormatException ignored) {
                    // 无时间戳按过期前处理,重新登记
                }
                if (timestamp >= expireBefore) {
                    result.put(Integer.parseInt(episode), value.substring(0, index < 0 ? value.length() : index));
                }
            });
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 登记损坏集(转存校验发现:源里列得出、实际拷不过去)。 */
    void addBrokenEpisodes(MediaSubscription subscription, Map<Integer, String> additions) {
        Map<Integer, String> merged = new java.util.LinkedHashMap<>(parseBroken(subscription));
        long now = System.currentTimeMillis();
        additions.forEach((episode, dir) -> merged.put(episode, dir + "|" + now));
        try {
            subscription.setBrokenEpisodes(objectMapper.writeValueAsString(merged));
        } catch (Exception e) {
            log.warn("serialize broken episodes failed: {}", e.getMessage());
        }
    }

    private static String brokenDir(Map<Integer, String> broken, int episode) {
        return broken.getOrDefault(episode, "");
    }

    private Site site() {
        return siteRepository.findById(1).orElseThrow();
    }

    /** 订阅的单集大小上限(字节);未配置返回 0 = 不限。 */
    long maxEpisodeBytes(MediaSubscription subscription) {
        MediaSubscriptionFilter filter = parseFilter(subscription);
        if (filter == null || filter.getMaxEpisodeSizeMb() == null || filter.getMaxEpisodeSizeMb() <= 0) {
            return 0;
        }
        return (long) filter.getMaxEpisodeSizeMb() * 1024 * 1024;
    }

    Set<Integer> walkEpisodes(Site site, Integer season, String path, long maxEpisodeBytes) {
        TreeSet<Integer> episodes = new TreeSet<>();
        walk(site, season, path, 1, episodes, maxEpisodeBytes);
        return episodes;
    }

    /** 任意挂载路径的集数清单(转存目录等非本订阅挂载点)。目录不存在/为空返回空集而非抛错。 */
    public Set<Integer> walkEpisodesAt(String path, Integer season, long maxEpisodeBytes) {
        try {
            return walkEpisodes(site(), season, path, maxEpisodeBytes);
        } catch (Exception e) {
            return new TreeSet<>();
        }
    }

    /** 集 → 文件信息(转存增量 copy 需要:目录 + 文件名)。主源缺集时合并补缺挂载;损坏集跳过其登记源,让其他源供给。 */
    TreeMap<Integer, EpisodeFile> walkEpisodeFiles(MediaSubscription subscription, boolean includeGaps) {
        Site site = site();
        long maxBytes = maxEpisodeBytes(subscription);
        Map<Integer, String> broken = parseBroken(subscription);
        TreeMap<Integer, EpisodeFile> result = new TreeMap<>();
        collectEpisodeFiles(site, subscription.getSeason(), subscription.getMountPath(), 1, result, maxBytes, true);
        if (includeGaps) {
            for (MediaSubscriptionResource resource : resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId())) {
                if (resource.isGap() && resource.getShareId() != null && StringUtils.isNotBlank(resource.getMountPath())) {
                    try {
                        collectEpisodeFiles(site, subscription.getSeason(), resource.getMountPath(), 1, result, maxBytes, true);
                    } catch (Exception e) {
                        log.warn("walk gap files failed: {} {}", resource.getMountPath(), e.getMessage());
                    }
                }
            }
        }
        if (!broken.isEmpty()) {
            result.entrySet().removeIf(entry -> entry.getValue().dir().equals(brokenDir(broken, entry.getKey())));
        }
        return result;
    }

    /** 任意挂载路径的 集→文件 映射(转存目录/播放解析用);refresh=false 走 AList 列表缓存,轻量。 */
    public TreeMap<Integer, EpisodeFile> episodeFilesAt(String path, MediaSubscription subscription) {
        TreeMap<Integer, EpisodeFile> result = new TreeMap<>();
        try {
            collectEpisodeFiles(site(), subscription.getSeason(), path, 1, result,
                    maxEpisodeBytes(subscription), false);
        } catch (Exception e) {
            log.debug("episodeFilesAt {} failed: {}", path, e.getMessage());
        }
        return result;
    }

    private void collectEpisodeFiles(Site site, Integer season, String path, int depth, TreeMap<Integer, EpisodeFile> result,
                                     long maxEpisodeBytes, boolean refresh) {
        if (depth > appProperties.getSubscription().getMaxListDepth()) {
            return;
        }
        FsResponse response = aListService.listFiles(site, path, 1, 0, refresh);
        List<FsInfo> files = response.getFiles();
        if (files == null || files.isEmpty()) {
            throw new IllegalStateException("目录为空或不可访问: " + path);
        }
        long minSize = (long) appProperties.getSubscription().getMinEpisodeSizeMb() * 1024 * 1024;
        for (FsInfo file : files) {
            if (file.getType() == 1) {
                continue;
            }
            if (file.getSize() < minSize || !isMediaFormat(file.getName()) || EXTRA.matcher(file.getName()).find()) {
                continue;
            }
            if (maxEpisodeBytes > 0 && file.getSize() > maxEpisodeBytes) {
                continue; // 超过单集上限:过滤捆绑大文件/异常资源
            }
            int episode = parseEpisode(file.getName(), season);
            if (episode > 0) {
                result.putIfAbsent(episode, new EpisodeFile(episode, path, file.getName(), file.getSize()));
            }
        }
        for (FsInfo file : files) {
            if (file.getType() == 1 && depth < appProperties.getSubscription().getMaxListDepth()
                    && !EXTRA.matcher(file.getName()).find()) {
                collectEpisodeFiles(site, season, path + "/" + file.getName(), depth + 1, result, maxEpisodeBytes, refresh);
            }
        }
    }

    public record EpisodeFile(int episode, String dir, String name, long size) {
    }

    private void walk(Site site, Integer season, String path, int depth, Set<Integer> episodes, long maxEpisodeBytes) {
        if (depth > appProperties.getSubscription().getMaxListDepth()) {
            return;
        }
        FsResponse response = aListService.listFiles(site, path, 1, 0, true);
        List<FsInfo> files = response.getFiles();
        if (files == null || files.isEmpty()) {
            throw new IllegalStateException("目录为空或不可访问: " + path);
        }
        long minSize = (long) appProperties.getSubscription().getMinEpisodeSizeMb() * 1024 * 1024;
        for (FsInfo file : files) {
            if (file.getType() == 1) {
                continue;
            }
            if (file.getSize() < minSize || !isMediaFormat(file.getName()) || EXTRA.matcher(file.getName()).find()) {
                continue;
            }
            if (maxEpisodeBytes > 0 && file.getSize() > maxEpisodeBytes) {
                continue; // 超过单集上限:过滤捆绑大文件/异常资源
            }
            int episode = parseEpisode(file.getName(), season);
            if (episode > 0) {
                episodes.add(episode);
            }
        }
        for (FsInfo file : files) {
            if (file.getType() == 1 && depth < appProperties.getSubscription().getMaxListDepth()
                    && !EXTRA.matcher(file.getName()).find()) {
                walk(site, season, path + "/" + file.getName(), depth + 1, episodes, maxEpisodeBytes);
            }
        }
    }

    int parseEpisode(String name, Integer season) {
        String base = name;
        int index = base.lastIndexOf('.');
        // 仅当末段是纯字母数字扩展名(mkv/mp4/4k…)才剥离,避免"剧名.更新至20集"这类无扩展名被截断
        if (index > 0 && index < base.length() - 1 && base.substring(index + 1).matches("[a-zA-Z0-9]{1,5}")) {
            base = base.substring(0, index);
        }
        Matcher matcher = SEASON_EPISODE.matcher(base);
        if (matcher.find()) {
            int s = Integer.parseInt(matcher.group(1));
            int ep = Integer.parseInt(matcher.group(2));
            if (season != null && season > 0 && season != s) {
                return -1;
            }
            return ep >= 1 && ep <= 999 ? ep : -1;
        }
        String cleaned = TECH_TAGS.matcher(base).replaceAll(" ");
        int episode = -1;
        Matcher numbers = NUMBER.matcher(cleaned);
        while (numbers.find()) {
            try {
                int value = Integer.parseInt(numbers.group(1));
                if (value >= 1 && value <= 999) {
                    episode = value;
                }
            } catch (NumberFormatException ignored) {
                // 4 位以上已被 \d{1,4} + 范围过滤兜底
            }
        }
        return episode;
    }

    /** 播放列表显示标题(fixName 已剥公共前后缀)解析集号:剥掉的是集号前的公共前缀,
     * 集号必在标题最前,取首个 1-999 数字即返回——文件名走 {@link #parseEpisode} 的"末个数字"规则,
     * 但标题里集号后残留的年份/50fps 等未被 TECH_TAGS 覆盖的数字会盖过集号
     * (如 "S01E15.2026.2160p.50fps.WEB-DL.H.265.AAC.mkv" 剥前缀后解析成 50,15-17 集全部丢失)。 */
    int parseEpisodeFromTitle(String title, Integer season) {
        String base = title;
        int index = base.lastIndexOf('.');
        if (index > 0 && index < base.length() - 1 && base.substring(index + 1).matches("[a-zA-Z0-9]{1,5}")) {
            base = base.substring(0, index);
        }
        Matcher matcher = SEASON_EPISODE.matcher(base);
        if (matcher.find()) {
            int s = Integer.parseInt(matcher.group(1));
            int ep = Integer.parseInt(matcher.group(2));
            if (season != null && season > 0 && season != s) {
                return -1;
            }
            return ep >= 1 && ep <= 999 ? ep : -1;
        }
        String cleaned = TECH_TAGS.matcher(base).replaceAll(" ");
        Matcher numbers = NUMBER.matcher(cleaned);
        while (numbers.find()) {
            try {
                int value = Integer.parseInt(numbers.group(1));
                if (value >= 1 && value <= 999) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 4 位以上已被 \d{1,4} + 范围过滤兜底
            }
        }
        return -1;
    }

    private boolean isMediaFormat(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            String suffix = name.substring(index + 1).toLowerCase();
            return appProperties.getFormats().contains(suffix) || "strm".equals(suffix) || "cas".equals(suffix);
        }
        return false;
    }

    /** 对比快照:新集写事件、停滞计数/退避;官方状态完结且清集达标自动完结。@return 本轮新增的集(供预热验证)。 */
    private List<Integer> applyInventory(MediaSubscription subscription, Set<Integer> episodes) {
        List<Integer> old = parseEpisodeList(subscription.getEpisodeList());
        boolean initial = old.isEmpty();
        List<Integer> added = episodes.stream().filter(e -> !old.contains(e)).sorted().toList();

        subscription.setCurrentEpisodes(episodes.size());
        subscription.setLastEpisode(episodes.stream().max(Integer::compareTo).orElse(null));
        subscription.setEpisodeList(serializeEpisodes(episodes));
        if (!initial && !added.isEmpty()) {
            subscription.setStallCount(0);
            subscription.setUpdatedTime(System.currentTimeMillis());
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_NEW_EPISODE, "更新 第" + joinNumbers(added) + " 集(共 " + episodes.size() + " 集)");
        } else if (initial && !added.isEmpty()) {
            subscription.setUpdatedTime(System.currentTimeMillis());
        } else {
            subscription.setStallCount(subscription.getStallCount() + 1);
        }

        Integer expected = subscription.getExpectedEpisodes();
        boolean endedByExpected = expected != null && expected > 0 && episodes.size() >= expected;
        boolean endedByOfficial = MetadataDetails.STATUS_ENDED.equals(subscription.getOfficialStatus())
                && subscription.getOfficialEpisodes() != null && subscription.getOfficialEpisodes() > 0
                && episodes.size() >= subscription.getOfficialEpisodes();
        if ((endedByExpected || endedByOfficial) && !MediaSubscription.STATUS_ENDED.equals(subscription.getStatus())) {
            subscription.setStatus(MediaSubscription.STATUS_ENDED);
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ENDED, "已完结(共 " + episodes.size() + " 集)");
        }
        return added;
    }

    /** 新集播放预热验证(atv-player V82/V85 思想):对新增集做链接解析探测(getFile),
     * 失败判损坏(如夸克分享单集被和谐:列表在、实际取不了)→ 登记待补源,补上 FOLLOW 模式的盲区。
     * @return 本轮判定损坏的集(调用方需从清单中扣除,使缺集补源当轮生效) */
    private Set<Integer> preheatEpisodes(MediaSubscription subscription, List<Integer> added) {
        var config = appProperties.getSubscription();
        Set<Integer> brokenNew = new TreeSet<>();
        if (!config.isPreheatEnabled() || added == null || added.isEmpty()) {
            return brokenNew;
        }
        TreeMap<Integer, EpisodeFile> files;
        try {
            files = walkEpisodeFiles(subscription, true);
        } catch (Exception e) {
            log.debug("preheat walk failed: {}", e.getMessage());
            return brokenNew;
        }
        Map<Integer, String> broken = new java.util.LinkedHashMap<>();
        int probed = 0;
        for (Integer episode : added) {
            if (probed >= config.getPreheatMaxPerRound()) {
                break;
            }
            EpisodeFile file = files.get(episode);
            if (file == null) {
                continue;
            }
            probed++;
            try {
                aListService.getFile(site(), file.dir() + "/" + file.name());
            } catch (Exception e) {
                log.info("subscription {} episode {} preheat failed: {}", subscription.getId(), episode, e.getMessage());
                broken.put(episode, file.dir());
            }
        }
        if (!broken.isEmpty()) {
            addBrokenEpisodes(subscription, broken);
            brokenNew.addAll(broken.keySet());
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR,
                    "第" + joinNumbers(new ArrayList<>(broken.keySet())) + " 集链接验证失败(疑似被和谐),已登记自动补源");
        }
        return brokenNew;
    }

    // ---------- 候选池与打分 ----------

    /**
     * 填充候选池:复用 TelegramService 三级搜索(PanSou → TG-Search → t.me 网页),按偏好打分取 TopN。
     *
     * @param keywordOverride 缺集补搜的单集关键词(空 = 默认订阅关键词)
     */
    void fillPool(MediaSubscription subscription, boolean force, String keywordOverride) {
        List<MediaSubscriptionResource> existing = resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId());
        long usable = existing.stream()
                .filter(r -> !r.getValidity().equals(MediaSubscriptionResource.VALIDITY_BAD) && !r.isActive())
                .count();
        if (!force && usable >= 1) {
            return;
        }

        String keyword = StringUtils.defaultIfBlank(keywordOverride,
                StringUtils.defaultIfBlank(subscription.getKeyword(), subscription.getName()));
        List<Message> messages;
        try {
            messages = telegramService.search(keyword, 50, false, false);
        } catch (Exception e) {
            log.warn("subscription {} search failed: {}", subscription.getId(), e.getMessage());
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "搜索失败:" + e.getMessage());
            return;
        }

        MediaSubscriptionFilter filter = parseFilter(subscription);
        List<String> names = matchNames(subscription);
        int irrelevant = 0;
        List<Scored> scored = new ArrayList<>();
        String activeLink = existing.stream().filter(MediaSubscriptionResource::isActive)
                .map(MediaSubscriptionResource::getLink).findFirst().orElse(null);
        for (Message message : messages) {
            if (StringUtils.isBlank(message.getLink()) || !PAN_TYPES.contains(StringUtils.defaultString(message.getType()))) {
                continue;
            }
            String title = StringUtils.defaultIfBlank(message.getName(), message.getLink());
            if (matchesKeywords(title, filter == null ? null : filter.getExcludeKeywords())) {
                continue;
            }
            if (activeLink != null && activeLink.equals(message.getLink())) {
                continue;
            }
            if (!names.isEmpty() && !matchesTitle(names, title)) {
                irrelevant++; // 标题与剧名/别名均不沾边,大概率是同名召回噪声,挡在池外省去挂载试错
                continue;
            }
            Integer titleSeason = parseTitleSeason(title);
            if (subscription.getSeason() != null && subscription.getSeason() > 0
                    && titleSeason != null && !titleSeason.equals(subscription.getSeason())) {
                irrelevant++; // 标题明确标注其它季(常见同名剧前季资源)
                continue;
            }
            scored.add(score(subscription, message, title, filter));
        }
        scored.sort((a, b) -> Integer.compare(b.score, a.score));

        int poolSize = appProperties.getSubscription().getCandidatePoolSize();
        int added = 0;
        for (Scored candidate : scored) {
            if (added >= poolSize) {
                break;
            }
            String link = candidate.message.getLink();
            if (resourceRepository.findBySubscriptionIdAndLink(subscription.getId(), link).isPresent()) {
                continue;
            }
            MediaSubscriptionResource resource = new MediaSubscriptionResource();
            resource.setSubscriptionId(subscription.getId());
            resource.setLink(link);
            try {
                resource.setType(Integer.parseInt(candidate.message.getType()));
            } catch (NumberFormatException e) {
                resource.setType(null);
            }
            resource.setTitle(StringUtils.abbreviate(candidate.title, 250)); // 列 VARCHAR(255),TG 消息名可超长
            resource.setScore(candidate.score);
            resource.setValidity(StringUtils.isNotBlank(candidate.message.getValidityState())
                    ? candidate.message.getValidityState() : MediaSubscriptionResource.VALIDITY_UNKNOWN);
            resource.setActive(false);
            resource.setCreatedTime(System.currentTimeMillis());
            resourceRepository.save(resource);
            added++;
        }
        if (added > 0) {
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_POOL_FILLED,
                    "候选池新增 " + added + " 个资源(" + keyword + (irrelevant > 0 ? ",过滤 " + irrelevant + " 条不相关结果" : "") + ")");
        } else if (force) {
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_POOL_FILLED,
                    "搜索无新增候选(共 " + messages.size() + " 条结果" + (irrelevant > 0 ? ",过滤 " + irrelevant + " 条不相关结果" : "") + ")");
        }
    }

    /** dry-run 预览(§10.2):按关键词+偏好即时搜索,返回候选与打分明细,不落库。 */
    public List<Map<String, Object>> preview(String keyword, Integer season, MediaSubscriptionFilter filter) {
        List<Message> messages;
        try {
            messages = telegramService.search(keyword, 50, false, true);
        } catch (Exception e) {
            return List.of(Map.of("error", StringUtils.defaultString(e.getMessage())));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> names = matchNames(keyword, keyword, null);
        for (Message message : messages) {
            if (StringUtils.isBlank(message.getLink()) || !PAN_TYPES.contains(StringUtils.defaultString(message.getType()))) {
                continue;
            }
            String title = StringUtils.defaultIfBlank(message.getName(), message.getLink());
            if (matchesKeywords(title, filter == null ? null : filter.getExcludeKeywords())) {
                continue;
            }
            if (!names.isEmpty() && !matchesTitle(names, title)) {
                continue;
            }
            Integer titleSeason = parseTitleSeason(title);
            if (season != null && season > 0 && titleSeason != null && !titleSeason.equals(season)) {
                continue;
            }
            Scored scored = score(null, message, title, filter);
            result.add(Map.of(
                    "title", title,
                    "link", message.getLink(),
                    "drive", DriveId.toDrive(parseIntOr(message.getType(), 0)),
                    "score", scored.score,
                    "reasons", String.join(";", scored.reasons),
                    "validity", StringUtils.defaultIfBlank(message.getValidityState(), "UNKNOWN"),
                    "time", message.getTime() == null ? "" : message.getTime().toString()));
        }
        return result;
    }

    /** 元数据级打分(挂载前粗排):新近度 + 清晰度 + 盘偏好 + 账号/VIP感知 + 资源形态 + 体积合理 + 包含词。
     * 账号感知:已配置该盘账号 +8(可转存/账号播放加速),VIP 账号再 +15(Setting msub_vip_accounts 勾选)。
     * 资源形态:百度分享本身免会员 +15;115 分享与"全N集"完结包对追更中订阅减分(不持续更新)。 */
    private Scored score(MediaSubscription subscription, Message message, String title, MediaSubscriptionFilter filter) {
        int result = 0;
        List<String> reasons = new ArrayList<>();
        boolean ongoing = subscription == null || isOngoing(subscription);
        int type = parseIntOr(StringUtils.defaultString(message.getType()), -1);
        Set<Integer> accountTypes = driveAccountTypes();
        Set<Integer> vipTypes = vipDriveTypes(accountTypes);
        if (message.getTime() != null) {
            Duration age = Duration.between(message.getTime(), Instant.now());
            if (age.toDays() <= 30) {
                result += 30;
                reasons.add("近期资源+30");
            } else if (age.toDays() <= 90) {
                result += 15;
                reasons.add("3个月内+15");
            } else {
                result += 5;
                reasons.add("较旧+5");
            }
        }
        if (StringUtils.containsIgnoreCase(title, "4K") || StringUtils.containsIgnoreCase(title, "2160")) {
            result += 25;
            reasons.add("4K+25");
        } else if (StringUtils.containsIgnoreCase(title, "1080")) {
            result += 15;
            reasons.add("1080P+15");
        } else if (StringUtils.containsIgnoreCase(title, "720")) {
            result += 8;
            reasons.add("720P+8");
        }
        if (filter != null && filter.getDriveTypes() != null && message.getType() != null) {
            try {
                int index = filter.getDriveTypes().indexOf(Integer.parseInt(message.getType()));
                if (index >= 0) {
                    int bonus = Math.max(20 - index * 5, 5);
                    result += bonus;
                    reasons.add("盘偏好+" + bonus);
                } else {
                    result -= 10; // 盘偏好之外的候选降权(不硬过滤,降级可用)
                    reasons.add("偏好外盘-10");
                }
            } catch (NumberFormatException ignored) {
                // 非数字类型不会进入候选
            }
        }
        if (type >= 0 && accountTypes.contains(type)) {
            result += 8;
            reasons.add("已配置账号+8");
            if (vipTypes.contains(type)) {
                result += 15;
                reasons.add("VIP账号+15");
            }
        }
        if (type == 10 /* 百度,DriveId:分享本身免会员,人人可看 */) {
            result += 15;
            reasons.add("百度分享免会员+15");
        }
        if (ongoing) {
            if (type == 8 /* 115 分享码,见 DriveId */) {
                result -= 10;
                reasons.add("115分享追更弱-10");
            }
            if (COMPLETE_PACK.matcher(title).find()) {
                result -= 6;
                reasons.add("完结包不更新-6");
            }
        }
        Long size = message.getSize();
        if (size != null && size > 1024L * 1024 * 1024 && size < 2L * 1024 * 1024 * 1024 * 1024) {
            result += 10;
            reasons.add("体积合理+10");
        }
        if (filter != null && filter.getIncludeKeywords() != null) {
            for (String keyword : filter.getIncludeKeywords()) {
                if (StringUtils.isNotBlank(keyword) && StringUtils.containsIgnoreCase(title, keyword)) {
                    result += 10;
                    reasons.add("包含词+10");
                    break;
                }
            }
        }
        if (subscription != null) {
            List<String> names = matchNames(subscription);
            if (!names.isEmpty() && matchesTitle(names, title)) {
                result += 15;
                reasons.add("标题归属+15");
            }
            Integer titleSeason = parseTitleSeason(title);
            if (subscription.getSeason() != null && subscription.getSeason() > 0
                    && titleSeason != null && titleSeason.equals(subscription.getSeason())) {
                result += 10;
                reasons.add("季标记匹配+10");
            }
            Integer progress = parseTitleProgress(title);
            int current = subscription.getCurrentEpisodes() != null ? subscription.getCurrentEpisodes() : 0;
            if (progress != null && current > 0) {
                if (progress > current) {
                    result += 8;
                    reasons.add("集数领先+8");
                } else if (progress < current) {
                    result -= 8;
                    reasons.add("集数落后-8");
                }
            }
        }
        return new Scored(message, title, result, reasons);
    }

    /** 订阅是否仍在追更(未完结且未达期望)。 */
    private boolean isOngoing(MediaSubscription subscription) {
        if (MediaSubscription.STATUS_ENDED.equals(subscription.getStatus())) {
            return false;
        }
        if (cn.har01d.alist_tvbox.dto.MetadataDetails.STATUS_RETURNING.equals(subscription.getOfficialStatus())) {
            return true;
        }
        Integer expected = subscription.getExpectedEpisodes();
        return expected == null || expected <= 0
                || subscription.getCurrentEpisodes() == null || subscription.getCurrentEpisodes() < expected;
    }

    /** 系统已配置的网盘账号类型集合(账号全局,与订阅归属用户无关)。DriverType 枚举 → 分享类型码。 */
    private Set<Integer> driveAccountTypes() {
        Set<Integer> types = new java.util.HashSet<>();
        try {
            driverAccountRepository.findAll().forEach(account -> {
                int code = driveCode(account.getType());
                if (code >= 0) {
                    types.add(code);
                }
            });
        } catch (Exception e) {
            log.debug("load accounts failed: {}", e.getMessage());
        }
        return types;
    }

    /** DriverType → 分享类型码(DriveId);同系账号合并(OPEN115/QUARK_TV 等并入主盘),未知 -1。 */
    static int driveCode(cn.har01d.alist_tvbox.domain.DriverType type) {
        if (type == null) {
            return -1;
        }
        return switch (type) {
            case QUARK, QUARK_TV -> 5;
            case UC, UC_TV -> 7;
            case PAN115, OPEN115 -> 8;
            case PAN123, OPEN123 -> 3;
            case PAN139 -> 6;
            case CLOUD189 -> 9;
            case THUNDER -> 2;
            case BAIDU -> 10;
            case ALI -> 0;
            case GUANGYA -> 12;
            default -> -1;
        };
    }

    /** VIP 账号类型集合(Setting msub_vip_accounts 勾选的账号 id CSV)。 */
    private Set<Integer> vipDriveTypes(Set<Integer> accountTypes) {
        Set<Integer> vip = new java.util.HashSet<>();
        if (accountTypes.isEmpty()) {
            return vip;
        }
        try {
            String csv = settingRepository.findById("msub_vip_accounts").map(s -> s.getValue()).orElse("");
            Set<Integer> vipIds = new java.util.HashSet<>();
            for (String id : csv.split(",")) {
                if (StringUtils.isNotBlank(id)) {
                    vipIds.add(Integer.parseInt(id.trim()));
                }
            }
            if (vipIds.isEmpty()) {
                return vip;
            }
            driverAccountRepository.findAll().forEach(account -> {
                if (vipIds.contains(account.getId())) {
                    int code = driveCode(account.getType());
                    if (code >= 0) {
                        vip.add(code);
                    }
                }
            });
        } catch (Exception e) {
            log.debug("load vip accounts failed: {}", e.getMessage());
        }
        return vip;
    }

    private boolean matchesKeywords(String title, List<String> keywords) {
        if (keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.isNotBlank(keyword) && StringUtils.containsIgnoreCase(title, keyword)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 标题归属匹配(§4.7 候选过滤) ----------

    /** 归属匹配名称集:剧名 + 搜索词(整串与最长词) + 元数据别名(换行分隔)。 */
    static List<String> matchNames(String name, String keyword, String aliases) {
        List<String> names = new ArrayList<>();
        if (StringUtils.isNotBlank(name)) {
            names.add(name.trim());
        }
        if (StringUtils.isNotBlank(keyword)) {
            String trimmed = keyword.trim();
            if (!names.contains(trimmed)) {
                names.add(trimmed);
            }
            // 搜索词常是"剧名 + 盘名/限定词"组合,再取最长一段参与包含匹配
            String longest = "";
            for (String part : trimmed.split("\\s+")) {
                if (part.length() > longest.length()) {
                    longest = part;
                }
            }
            if (longest.length() >= 2 && !names.contains(longest)) {
                names.add(longest);
            }
        }
        if (StringUtils.isNotBlank(aliases)) {
            for (String alias : aliases.split("\\n")) {
                String trimmed = alias.trim();
                if (trimmed.length() >= 2 && !names.contains(trimmed)) {
                    names.add(trimmed);
                }
            }
        }
        return names;
    }

    List<String> matchNames(MediaSubscription subscription) {
        return matchNames(subscription.getName(), subscription.getKeyword(), subscription.getAliases());
    }

    /** 归一化:小写、剥技术标签、非字母数字/汉字转空格、汉字间空格塌缩 —— 抵消 TG 标题的 .【】·等防审查写法。 */
    static String normalizeForMatch(String text) {
        if (text == null) {
            return "";
        }
        String s = TECH_TAGS.matcher(text.toLowerCase()).replaceAll(" ");
        s = s.replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", " ");
        s = TextUtils.collapseCjkSpaces(s);
        return s.trim().replaceAll("\\s+", " ");
    }

    /**
     * 标题归属匹配:候选资源标题是否属于本剧。归一化包含(剧名/搜索词/别名任一)为主;
     * 全部未命中时对中文名做编辑距离滑窗兜底(防审查变形字,如"蒼蘭訣"对"苍兰诀"差 2 字)。
     */
    static boolean matchesTitle(List<String> names, String title) {
        if (names == null || names.isEmpty()) {
            return true; // 无可用名称时不拦截,保持纯搜索召回
        }
        String normalized = normalizeForMatch(title);
        if (normalized.isBlank()) {
            return false;
        }
        for (String name : names) {
            String n = normalizeForMatch(name);
            if (n.length() >= 2 && normalized.contains(n)) {
                return true;
            }
        }
        return fuzzyChineseMatch(names, normalized);
    }

    /** 中文变形兜底:标题紧凑串中存在与某中文名编辑距离 ≤ max(1, len/4) 的滑窗即命中。 */
    private static boolean fuzzyChineseMatch(List<String> names, String normalizedTitle) {
        String compactTitle = normalizedTitle.replace(" ", "");
        for (String name : names) {
            String n = normalizeForMatch(name).replace(" ", "");
            if (!TextUtils.isChinese(n) || n.length() < 3 || n.length() > 20) {
                continue;
            }
            int tolerance = Math.max(1, n.length() / 4);
            for (int len = n.length() - tolerance; len <= n.length() + tolerance; len++) {
                for (int start = 0; start + len <= compactTitle.length(); start++) {
                    if (TextUtils.minDistance(n, compactTitle.substring(start, start + len)) <= tolerance) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 标题级季标记解析:返回标题明确标注的季号;无标记或多个不同季号(跨季合集)返回 null 不参与判定。 */
    static Integer parseTitleSeason(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        Set<Integer> seasons = new TreeSet<>();
        Matcher cn = TITLE_SEASON_CN.matcher(title);
        while (cn.find()) {
            int value = cn.group(1).matches("\\d+") ? Integer.parseInt(cn.group(1)) : parseChineseNumber(cn.group(1));
            if (value > 0) {
                seasons.add(value);
            }
        }
        collectSeason(title, seasons, TITLE_SEASON_SXXE);
        collectSeason(title, seasons, TITLE_SEASON_ALONE);
        collectSeason(title, seasons, TITLE_SEASON_EN);
        return seasons.size() == 1 ? seasons.iterator().next() : null;
    }

    private static void collectSeason(String title, Set<Integer> seasons, Pattern pattern) {
        Matcher matcher = pattern.matcher(title);
        while (matcher.find()) {
            seasons.add(Integer.parseInt(matcher.group(1)));
        }
    }

    /** 中文数字(一~九十九)转阿拉伯;不可解析返回 0。 */
    static int parseChineseNumber(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int result = 0;
        int current = 0;
        for (char c : text.toCharArray()) {
            int digit = "零一二三四五六七八九".indexOf(c);
            if (digit >= 0) {
                current = digit;
            } else if (c == '十') {
                result += current == 0 ? 10 : current * 10;
                current = 0;
            } else {
                return 0;
            }
        }
        return result + current;
    }

    /** 标题宣称的集数进度(TITLE_PROGRESS 各形态的最大值);无信息返回 null。 */
    static Integer parseTitleProgress(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        int max = -1;
        Matcher matcher = TITLE_PROGRESS.matcher(title);
        while (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                String group = matcher.group(i);
                if (group != null) {
                    max = Math.max(max, Integer.parseInt(group));
                }
            }
        }
        return max > 0 ? max : null;
    }

    // ---------- 调度 ----------

    void scheduleNext(MediaSubscription subscription) {
        Long air = subscription.getNextAirTime();
        long now = System.currentTimeMillis();
        if (air != null && air > 0) {
            if (air > now + 15 * 60_000L) {
                // 播出前休眠:播出时刻 +15min 起查(上限 24h,防日程异常导致长眠漏检)
                subscription.setNextCheckTime(Math.min(air + 15 * 60_000L, now + 24 * 3600_000L));
                return;
            }
            if (now < air + appProperties.getSubscription().getShortPollWindowHours() * 3600_000L) {
                subscription.setNextCheckTime(now + 3600_000L); // 播后短轮:窗口内每小时一查(资源常在播后 1~12h 上线)
                return;
            }
        }
        int hours = subscription.getCheckIntervalHours() != null && subscription.getCheckIntervalHours() > 0
                ? subscription.getCheckIntervalHours() : appProperties.getSubscription().getCheckIntervalHours();
        // 无更新退避 ×1.5/轮;追更中(官方 RETURNING)封顶收紧(重列主源零成本,不该隔一天才发现),
        // 完结/无元数据维持 24h
        int cap = MetadataDetails.STATUS_RETURNING.equals(subscription.getOfficialStatus())
                ? appProperties.getSubscription().getReturningBackoffCapHours() : 24;
        double factor = Math.min(Math.pow(1.5, Math.min(subscription.getStallCount(), 6)), 4);
        long interval = (long) (Math.min(hours * factor, cap) * 3600_000L);
        subscription.setNextCheckTime(now + interval);
    }

    // ---------- 工具 ----------

    MediaSubscriptionFilter parseFilter(MediaSubscription subscription) {
        if (StringUtils.isBlank(subscription.getFilterConfig())) {
            return null;
        }
        try {
            return objectMapper.readValue(subscription.getFilterConfig(), MediaSubscriptionFilter.class);
        } catch (Exception e) {
            return null;
        }
    }

    List<Integer> parseEpisodeList(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Integer>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    String serializeEpisodes(Set<Integer> episodes) {
        try {
            return objectMapper.writeValueAsString(new ArrayList<>(episodes));
        } catch (Exception e) {
            return "[]";
        }
    }

    private static Set<Integer> intersection(Set<Integer> a, Set<Integer> b) {
        Set<Integer> result = new TreeSet<>(a);
        result.retainAll(b);
        return result;
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String joinNumbers(List<Integer> numbers) {
        StringBuilder sb = new StringBuilder();
        for (int number : numbers) {
            if (!sb.isEmpty()) {
                sb.append(",");
            }
            sb.append(number);
        }
        return sb.toString();
    }

    private boolean tryLock(Integer id) {
        return inFlight.add(id);
    }

    void addEvent(int subscriptionId, String type, String detail) {
        try {
            MediaSubscriptionEvent event = new MediaSubscriptionEvent();
            event.setSubscriptionId(subscriptionId);
            event.setType(type);
            event.setDetail(detail);
            event.setCreatedTime(System.currentTimeMillis());
            eventRepository.save(event);
            log.info("media subscription {} event: {} {}", subscriptionId, type, detail);
            notifyTelegram(subscriptionId, type, detail);
        } catch (Exception e) {
            log.warn("add event failed: {}", e.getMessage());
        }
    }

    /** Telegram 通知专用(带超时;builder 走 classpath 探测的 Jackson2 转换器即可) */
    private final org.springframework.web.client.RestTemplate notifyRestTemplate =
            new cn.har01d.alist_tvbox.service.metadata.MetadataHttp(null).create();

    /** 可选 Telegram Bot 通知(P3):Setting msub_telegram_bot_token / msub_telegram_chat_id,配好即启用;只推重要事件。 */
    private void notifyTelegram(int subscriptionId, String type, String detail) {
        if (!MediaSubscriptionEvent.TYPE_NEW_EPISODE.equals(type)
                && !MediaSubscriptionEvent.TYPE_ERROR.equals(type)
                && !MediaSubscriptionEvent.TYPE_ENDED.equals(type)
                && !MediaSubscriptionEvent.TYPE_TRANSFER_DONE.equals(type)) {
            return;
        }
        try {
            String token = settingRepository.findById("msub_telegram_bot_token").map(s -> s.getValue()).orElse(null);
            String chatId = settingRepository.findById("msub_telegram_chat_id").map(s -> s.getValue()).orElse(null);
            if (StringUtils.isBlank(token) || StringUtils.isBlank(chatId)) {
                return;
            }
            String name = subscriptionRepository.findById(subscriptionId).map(MediaSubscription::getName).orElse("#" + subscriptionId);
            String text = "\uD83D\uDCFA " + name + "\n" + detail;
            java.net.URI uri = java.net.URI.create("https://api.telegram.org/bot" + token + "/sendMessage");
            var body = objectMapper.createObjectNode();
            body.put("chat_id", chatId);
            body.put("text", text);
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    var headers = new org.springframework.http.HttpHeaders();
                    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                    notifyRestTemplate.exchange(uri, org.springframework.http.HttpMethod.POST,
                            new org.springframework.http.HttpEntity<>(body.toString(), headers), String.class);
                } catch (Exception e) {
                    log.debug("telegram notify failed: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.debug("telegram notify skipped: {}", e.getMessage());
        }
    }

    private record Scored(Message message, String title, int score, List<String> reasons) {
    }
}
