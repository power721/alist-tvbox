package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallback;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallbackRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService;
import cn.har01d.alist_tvbox.service.SettingService;
import cn.har01d.alist_tvbox.service.StreamProbeClient;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 采集源兜底恢复编排(播放链路最后一级):候选源(转存/主源/补缺)全灭时,
 * 从 MacCMS 采集站搜直链补齐「当前集 + 后 3 集」缺口。
 * <p>
 * 原则(第一版定死):开关默认关;窗口固定 当前集+后3集;结果只写 msub_episode_fallback
 * 覆盖层,不改写追剧原始数据;当前集失效<b>同步</b>等兜底结果,当前集正常时后台补齐不阻塞起播。
 * <ul>
 *   <li>单飞锁:同订阅并发播放请求只触发一次采集(preheatAheadInFlight 同模式);</li>
 *   <li>负缓存:搜索无结果/匹配失败 30min 内不重搜(防用户连续点击反复抓);</li>
 *   <li>条目硬过滤在 CollectionGateway(宁可不补,不绑定异剧);一次搜索补整个窗口,
 *       单条目优先覆盖全缺口,不足才按集取第二条;</li>
 *   <li>预检 = StreamProbeClient 4KB Range 探测(verdictOf 同矩阵),死只删覆盖层行,
 *       不碰 AList/集源行/传染判定 —— 兜底体系与主状态机互不污染。</li>
 * </ul>
 */
@Service
public class EpisodeFallbackService {
    private static final Logger log = LoggerFactory.getLogger(EpisodeFallbackService.class);
    /** 窗口 = 当前集 + 后 3 集(第一版定死,不做成配置)。 */
    private static final int WINDOW_AHEAD = 3;
    /** 覆盖层行预检用的探测字节上限/超时,与 verifyStream 同参。 */
    private static final int PROBE_MAX_BYTES = 4096;
    private static final int PROBE_TIMEOUT_SECONDS = 8;
    /** 同步播放最多解析的详情条目,避免候选详情串行阻塞播放请求。 */
    private static final int MAX_DETAIL_CANDIDATES = 8;

    public static final String SETTING_KEY = "msub_collection_fallback";

    private final AppProperties appProperties;
    private final SettingService settingService;
    private final CollectionGateway gateway;
    private final MediaSubscriptionCheckService checkService;
    private final MediaSubscriptionEpisodeFallbackRepository fallbackRepository;
    /** 探测客户端:非 bean(CheckService 同款字段持有),单测经 setter 注桩。 */
    private StreamProbeClient streamProbeClient = new StreamProbeClient.Default();
    /** 单飞锁(订阅级):内存态,重启清零可接受(preheatAheadInFlight 先例)。 */
    private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();
    /** 负缓存(订阅级):值为到期 epoch ms。 */
    private final Map<Integer, Long> negativeUntil = new ConcurrentHashMap<>();
    private final Map<Integer, Long> inFlightTime = new ConcurrentHashMap<>();
    private static final long NEGATIVE_TTL_MS = 30 * 60_000L;
    /** 同订阅两次窗口补齐的最小间隔(分钟,防连播时每集都进一次网关)。 */
    private static final long REFILL_INTERVAL_MS = 10 * 60_000L;
    private static final AtomicInteger TASK_SEQ = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "collection-fallback-fill-" + TASK_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    public EpisodeFallbackService(AppProperties appProperties, SettingService settingService,
                                  CollectionGateway gateway, MediaSubscriptionCheckService checkService,
                                  MediaSubscriptionEpisodeFallbackRepository fallbackRepository) {
        this.appProperties = appProperties;
        this.settingService = settingService;
        this.gateway = gateway;
        this.checkService = checkService;
        this.fallbackRepository = fallbackRepository;
    }

    /** 单测注桩(CheckService.setStreamProbeClient 同款)。 */
    void setStreamProbeClient(StreamProbeClient client) {
        this.streamProbeClient = client;
    }

    /** 用户级开关(用户覆盖 > 管理员全局 > 部署默认):false 时整条兜底链路零调用。 */
    public boolean enabled(int uid) {
        String value = settingService.getUserSetting(SETTING_KEY, uid);
        if (StringUtils.isNotBlank(value)) {
            return "true".equalsIgnoreCase(value.trim());
        }
        return appProperties.getSubscription().isCollectionFallbackEnabled();
    }

    /**
     * 当前集同步兜底:playEpisode 候选全灭后的最后一搏。
     * 覆盖层已有行先探测缓存直链(死了回采集站重解析);仍缺才网关搜索一次。
     *
     * @return 播放结果({@code parse:0, url:直链});null = 不可恢复(调用方走原有失败路径)
     */
    public Map<String, Object> resolveEpisodeFallback(MediaSubscription subscription, int episode,
                                                      String client, String type) {
        // 开关关闭 = 与现状完全一致,整条链路零调用(服务侧自查,不依赖调用方)
        if (!enabled(subscription.getUid())) {
            return null;
        }
        if (!isEpisodeInSeason(subscription, episode)) {
            return null;
        }
        long now = System.currentTimeMillis();
        // 1) 覆盖层快路径:ACTIVE 未过期行,探测缓存 URL
        MediaSubscriptionEpisodeFallback row = activeRow(subscription.getId(), episode, now);
        if (row != null) {
            String url = probeOrRebuild(subscription, row, now);
            if (url != null) {
                return playResult(url);
            }
        }
        // 2) 负缓存窗口内不再进网关
        if (negative(subscription.getId(), now)) {
            return null;
        }
        // 3) 单飞:并发播放同一订阅只搜一次,后来者直接吃不到结果(下一次播放走覆盖层快路径)
        if (!inFlight.add(subscription.getId())) {
            return null;
        }
        try {
            return searchAndFill(subscription, episode, now);
        } catch (Exception e) {
            log.info("collection fallback for subscription {} episode {} failed: {}",
                    subscription.getId(), episode, e.getMessage());
            return null;
        } finally {
            inFlight.remove(subscription.getId());
        }
    }

    /** 后台补齐窗口内其余集(当前集已正常起播,不阻塞):fire-and-forget。 */
    public void fillWindowAsync(int uid, int subscriptionId, int playedEpisode) {
        long now = System.currentTimeMillis();
        Long last = inFlightTime.get(subscriptionId);
        if (last != null && now - last < REFILL_INTERVAL_MS) {
            return;
        }
        inFlightTime.put(subscriptionId, now);
        if (!inFlight.add(subscriptionId)) {
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    MediaSubscription subscription = checkService.subscriptionOf(subscriptionId);
                    if (subscription == null || subscription.getUid() != uid) {
                        return;
                    }
                    if (!enabled(uid) || negative(subscriptionId, now)) {
                        return;
                    }
                    searchAndFill(subscription, playedEpisode, now, true);
                } catch (Exception e) {
                    log.debug("fill window for subscription {} failed: {}", subscriptionId, e.getMessage());
                } finally {
                    inFlight.remove(subscriptionId);
                }
            });
        } catch (Exception e) {
            inFlight.remove(subscriptionId);
            log.debug("submit fill window for subscription {} failed: {}", subscriptionId, e.getMessage());
        }
    }

    /**
     * 订阅删除时清理:覆盖层行随行清空(否则 msub_episode_fallback 孤儿行无界累积),
     * 内存态(负缓存/补齐间隔/单飞锁)一并回收。
     */
    public void deleteForSubscription(int subscriptionId) {
        fallbackRepository.deleteBySubscriptionId(subscriptionId);
        negativeUntil.remove(subscriptionId);
        inFlightTime.remove(subscriptionId);
        inFlight.remove(subscriptionId);
    }

    /** 覆盖层可用行(ACTIVE 且未过期):详情/集数清单展示用 —— 已采集垫底的缺集对用户可见可播,
     *  播放走 msubep 逻辑链接由 resolveEpisodeFallback 快路径供流。 */
    public List<MediaSubscriptionEpisodeFallback> activeRows(int subscriptionId) {
        MediaSubscription subscription = checkService.subscriptionOf(subscriptionId);
        if (subscription == null || !enabled(subscription.getUid())) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        Integer seasonEnd = checkService.seasonWindowEnd(subscription);
        int seasonStart = subscription.getSeasonStartEpisode() != null && subscription.getSeasonStartEpisode() > 1
                ? subscription.getSeasonStartEpisode() : 1;
        return fallbackRepository.findBySubscriptionId(subscriptionId).stream()
                .filter(r -> MediaSubscriptionEpisodeFallback.STATE_ACTIVE.equals(r.getState()))
                .filter(r -> r.getExpiresAt() == null || r.getExpiresAt() > now)
                .filter(r -> r.getEpisode() >= seasonStart
                        && (seasonEnd == null || seasonEnd <= 0 || r.getEpisode() <= seasonEnd))
                .toList();
    }

    // ---------- 核心流程 ----------

    /** 网关搜索 + 窗口映射 + 预检 + 批量落覆盖层;返回当前集播放结果(可能 null)。 */
    private Map<String, Object> searchAndFill(MediaSubscription subscription, int currentEpisode, long now) {
        return searchAndFill(subscription, currentEpisode, now, false);
    }

    private Map<String, Object> searchAndFill(MediaSubscription subscription, int currentEpisode, long now,
                                              boolean recheckSubscription) {
        Set<Integer> window = window(subscription, currentEpisode);
        if (window.isEmpty()) {
            return null;
        }
        Set<Integer> missing = missingEpisodes(subscription, window, now);
        if (missing.isEmpty()) {
            return null;
        }
        List<CollectionGateway.CollectionItem> items = gateway.search(subscription, currentEpisode);
        if (items.isEmpty()) {
            negativeUntil.put(subscription.getId(), now + negativeTtlMs());
            return null;
        }
        // 条目按 rank 已排序;优先选能覆盖整个缺口的单条目,不足才按集取第二条(避免 3 集来自 3 个版本)
        List<MediaSubscriptionEpisodeFallback> filled = new ArrayList<>();
        Set<Integer> remaining = new TreeSet<>(missing);
        for (CollectionGateway.CollectionItem item : items.stream().limit(MAX_DETAIL_CANDIDATES).toList()) {
            if (remaining.isEmpty()) {
                break;
            }
            CollectionGateway.CollectionPlaylist playlist = gateway.loadPlaylist(subscription, item);
            if (playlist == null || playlist.episodes().isEmpty()) {
                continue;
            }
            // 集号范围门禁:采集列表集号显著超出官方总集数 = 同名异剧(与主链路 episodeNumbersForeign 同口径)
            if (MediaSubscriptionCheckService.episodeNumbersForeign(subscription, playlist.episodes().keySet())) {
                continue;
            }
            for (Integer episode : new TreeSet<>(remaining)) {
                String url = playlist.episodes().get(episode);
                if (url == null) {
                    continue;
                }
                ProbeVerdict verdict = probeUrl(url);
                if (verdict == ProbeVerdict.FAILED) {
                    continue; // 确证死链不补;瞬时/无结论放行(播放期自然纠偏)
                }
                filled.add(buildRow(subscription, playlist, episode, url, now));
                remaining.remove(episode);
            }
        }
        if (filled.isEmpty()) {
            negativeUntil.put(subscription.getId(), now + negativeTtlMs());
            return null;
        }
        if (recheckSubscription) {
            MediaSubscription currentSubscription = checkService.subscriptionOf(subscription.getId());
            if (currentSubscription == null || currentSubscription.getUid() != subscription.getUid()) {
                return null;
            }
        }
        fallbackRepository.saveAll(filled);
        log.info("collection fallback filled subscription {} episodes {} ({} rows)",
                subscription.getId(), filled.stream().map(MediaSubscriptionEpisodeFallback::getEpisode).toList(),
                filled.size());
        MediaSubscriptionEpisodeFallback current = filled.stream()
                .filter(r -> r.getEpisode() == currentEpisode)
                .findFirst().orElse(null);
        return current == null ? null : playResult(current.getUrl());
    }

    /** 窗口内仍是缺口的集:无 LIVE 集源行且无可用覆盖层行。 */
    private Set<Integer> missingEpisodes(MediaSubscription subscription, Set<Integer> window, long now) {
        Set<Integer> live = checkService.liveEpisodeNumbers(subscription);
        Set<Integer> missing = new TreeSet<>();
        for (Integer episode : window) {
            if (!live.contains(episode) && activeRow(subscription.getId(), episode, now) == null) {
                missing.add(episode);
            }
        }
        return missing;
    }

    /** 当前集+后3集,且不超过官方总集数(没播出的集不该补)。 */
    private Set<Integer> window(MediaSubscription subscription, int currentEpisode) {
        int total = subscription.effectiveTotalEpisodes();
        int seasonStart = subscription.getSeasonStartEpisode() != null && subscription.getSeasonStartEpisode() > 1
                ? subscription.getSeasonStartEpisode() : 1;
        if (currentEpisode < seasonStart) {
            return Set.of();
        }
        Integer seasonEnd = checkService.seasonWindowEnd(subscription);
        Set<Integer> window = new TreeSet<>();
        for (int episode = currentEpisode; episode <= currentEpisode + WINDOW_AHEAD; episode++) {
            if (total > 0 && episode > total) {
                break;
            }
            if (seasonEnd != null && seasonEnd > 0 && episode > seasonEnd) {
                break;
            }
            window.add(episode);
        }
        return window;
    }

    private boolean isEpisodeInSeason(MediaSubscription subscription, int episode) {
        int seasonStart = subscription.getSeasonStartEpisode() != null && subscription.getSeasonStartEpisode() > 1
                ? subscription.getSeasonStartEpisode() : 1;
        if (episode < seasonStart) {
            return false;
        }
        Integer seasonEnd = checkService.seasonWindowEnd(subscription);
        return seasonEnd == null || seasonEnd <= 0 || episode <= seasonEnd;
    }

    /** 覆盖层行播放期解析:先探测缓存直链;死了(或行残缺)回采集站重解析重建行。 */
    private String probeOrRebuild(MediaSubscription subscription, MediaSubscriptionEpisodeFallback row, long now) {
        if (StringUtils.isNotBlank(row.getUrl())) {
            if (probeUrl(row.getUrl()) == ProbeVerdict.VERIFIED) {
                row.setValidatedAt(now);
                fallbackRepository.save(row);
                return row.getUrl();
            }
            // 缓存死链:标 FAILED,尝试重解析
            row.setState(MediaSubscriptionEpisodeFallback.STATE_FAILED);
            fallbackRepository.save(row);
        }
        try {
            CollectionGateway.CollectionItem item = new CollectionGateway.CollectionItem(
                    row.getSiteId(), row.getSiteId(), row.getResourceId(), row.getTitle(), null, null, 0);
            CollectionGateway.CollectionPlaylist playlist = gateway.loadPlaylist(subscription, item);
            if (playlist == null) {
                return null;
            }
            String url = playlist.episodes().get(row.getEpisode());
            if (url == null || probeUrl(url) != ProbeVerdict.VERIFIED) {
                return null;
            }
            row.setUrl(url);
            row.setLine(playlist.line());
            row.setState(MediaSubscriptionEpisodeFallback.STATE_ACTIVE);
            row.setValidatedAt(now);
            row.setExpiresAt(now + rowTtlMs());
            fallbackRepository.save(row);
            return url;
        } catch (Exception e) {
            log.debug("rebuild fallback row {} failed: {}", row.getId(), e.getMessage());
            return null;
        }
    }

    // ---------- 覆盖层读写 ----------

    private MediaSubscriptionEpisodeFallback activeRow(int subscriptionId, int episode, long now) {
        return fallbackRepository.findBySubscriptionIdAndEpisode(subscriptionId, episode)
                .filter(r -> MediaSubscriptionEpisodeFallback.STATE_ACTIVE.equals(r.getState()))
                .filter(r -> r.getExpiresAt() == null || r.getExpiresAt() > now)
                .orElse(null);
    }

    private MediaSubscriptionEpisodeFallback buildRow(MediaSubscription subscription,
                                                      CollectionGateway.CollectionPlaylist playlist,
                                                      int episode, String url, long now) {
        MediaSubscriptionEpisodeFallback row = fallbackRepository
                .findBySubscriptionIdAndEpisode(subscription.getId(), episode).orElse(null);
        if (row == null) {
            row = new MediaSubscriptionEpisodeFallback();
            row.setSubscriptionId(subscription.getId());
            row.setEpisode(episode);
        }
        row.setSiteId(playlist.siteId());
        row.setResourceId(playlist.vodId());
        row.setLine(playlist.line());
        row.setTitle(playlist.line());
        row.setUrl(url);
        row.setState(MediaSubscriptionEpisodeFallback.STATE_ACTIVE);
        row.setValidatedAt(now);
        row.setExpiresAt(now + rowTtlMs());
        return row;
    }

    // ---------- 探测与工具 ----------

    /** 直链 4KB Range 探测(verifyStream 判定矩阵同口径,不经 AList)。 */
    private ProbeVerdict probeUrl(String url) {
        try {
            StreamProbeClient.ProbeResult probe = streamProbeClient.fetch(url,
                    cn.har01d.alist_tvbox.util.Constants.USER_AGENT, PROBE_MAX_BYTES, PROBE_TIMEOUT_SECONDS);
            String contentType = StringUtils.defaultString(probe.contentType());
            if ((probe.status() == 200 || probe.status() == 206) && !contentType.contains("text/html")) {
                return ProbeVerdict.VERIFIED;
            }
            if (probe.status() == 404 || probe.status() == 410) {
                return ProbeVerdict.FAILED;
            }
            return ProbeVerdict.NO_VERDICT; // 403 防盗链/3xx/未知形态:不下结论
        } catch (Exception e) {
            return ProbeVerdict.NO_VERDICT; // 超时/连接失败:瞬时,不下结论
        }
    }

    enum ProbeVerdict {
        VERIFIED, FAILED, NO_VERDICT
    }

    private Map<String, Object> playResult(String url) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parse", 0);
        result.put("playUrl", "");
        result.put("url", url);
        return result;
    }

    private boolean negative(int subscriptionId, long now) {
        Long until = negativeUntil.get(subscriptionId);
        return until != null && until > now;
    }

    private long negativeTtlMs() {
        return Math.max(1, appProperties.getSubscription().getCollectionFallbackNegativeTtlMinutes()) * 60_000L;
    }

    private long rowTtlMs() {
        return Math.max(1, appProperties.getSubscription().getCollectionFallbackRowTtlHours()) * 3600_000L;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
