package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.auth.TokenService;
import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.playback.PlaybackDeleteInput;
import cn.har01d.alist_tvbox.dto.playback.PlaybackSyncInput;
import cn.har01d.alist_tvbox.dto.playback.PlaybackSyncPage;
import cn.har01d.alist_tvbox.dto.playback.PlaybackTokenDto;
import cn.har01d.alist_tvbox.entity.History;
import cn.har01d.alist_tvbox.entity.HistoryRepository;
import cn.har01d.alist_tvbox.entity.PlaybackChangeSequence;
import cn.har01d.alist_tvbox.entity.PlaybackChangeSequenceRepository;
import cn.har01d.alist_tvbox.entity.PlaybackToken;
import cn.har01d.alist_tvbox.entity.PlaybackTokenRepository;
import cn.har01d.alist_tvbox.entity.PlaybackTombstone;
import cn.har01d.alist_tvbox.entity.PlaybackTombstoneRepository;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 多端播放记录同步核心服务。
 * <p>
 * 本服务**显式接收 uid**(由 controller 从令牌解析),不读取 {@code SecurityContextHolder},
 * 故可在 {@code permitAll} 的令牌端点安全调用。
 * <p>
 * 身份 = (uid, sourceKind, sourceKey, vodId);冲突 = updatedAt LWW;删除 = 90 天墓碑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybackSyncService {
    private static final long TOMBSTONE_TTL_MS = 90L * 24 * 60 * 60 * 1000;
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;
    private static final int SYNC_HISTORY_LIMIT = 100;
    private static final String SCOPE_ALL = "all";
    private static final String SCOPE_SITE = "site";
    private static final String SCOPE_ITEM = "item";
    private static final String KIND_SITE = "site";
    private static final String KIND_SPIDER_PLUGIN = "spider_plugin";
    private static final Set<String> TELEGRAM_SITE_KEYS = Set.of(
            "csp_TgDouBan", "csp_TgSearch", "csp_TgWeb", "csp_FishPanSou", "csp_FishPanSouGroup");
    private static final Set<String> BROWSE_SITE_KEYS = Set.of("csp_AList", "csp_XiaoYa");
    // 网页端可直接播放的站点 key:电报系 + 浏览系 + atv-player(TvBox)/电报频道。供「网页播放」筛选。
    private static final Set<String> WEB_PLAYABLE_SITE_KEYS;
    static {
        Set<String> keys = new HashSet<>(TELEGRAM_SITE_KEYS);
        keys.addAll(BROWSE_SITE_KEYS);
        keys.add("TvBox");
        keys.add("csp_TgChannel");
        WEB_PLAYABLE_SITE_KEYS = Set.copyOf(keys);
    }

    private final HistoryRepository historyRepository;
    private final PlaybackTokenRepository tokenRepository;
    private final PlaybackTombstoneRepository tombstoneRepository;
    private final PlaybackChangeSequenceRepository changeSequenceRepository;
    private final TokenService tokenService;
    private final AppProperties appProperties;

    private final Cache<String, Boolean> idempotency = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(50_000)
            .build();

    // ── 令牌解析(双源:playback_token 表 ∪ session) ──────────────────────────

    /** 令牌解析结果:uid + 订阅分区(空=uid 级,所有订阅互通;非空=按 vod token 分区)。 */
    public record TokenIdentity(int uid, String syncScope) {
    }

    public TokenIdentity resolveIdentity(String token) {
        if (token == null || token.isBlank()) {
            throw new UserUnauthorizedException("缺少播放同步令牌", 40100);
        }
        PlaybackToken pt = tokenRepository.findByToken(token).orElse(null);
        if (pt != null) {
            pt.setLastUsedAt(System.currentTimeMillis());
            tokenRepository.save(pt);
            return new TokenIdentity(pt.getUid(), pt.getSyncScope());
        }
        try {
            // session 令牌(网页/atv-player 未带 vod token 时):无分区归属,按 uid 级处理。
            // 客户端带上具体 vod token 时走上面的 playback_token 路径,拿到该订阅的分区。
            return new TokenIdentity(tokenService.extractToken(token).getUserId(), null);
        } catch (Exception e) {
            throw new UserUnauthorizedException("播放同步令牌无效", 40100, e);
        }
    }

    public int resolveUid(String token) {
        return resolveIdentity(token).uid();
    }

    // ── PUSH:upsert / delete ───────────────────────────────────────────────

    @Transactional
    public void apply(TokenIdentity id, Map<String, Object> record, String eventId, String dedupeKey) {
        // PUSH 始终落库:网页端自身的续看进度不应受跨端同步开关影响。
        // 跨端分发(PULL)仍由 playbackSyncEnabled 在 pull() 门控;令牌派发由 SubscriptionService 门控。
        applyRecord(id, record, eventId, dedupeKey);
        trimHistory(id.uid(), id.syncScope());
    }

    @Transactional
    public void applyAll(TokenIdentity id, List<Map<String, Object>> records) {
        if (records != null) {
            for (Map<String, Object> record : records) {
                applyRecord(id, record, null, null);
            }
        }
        trimHistory(id.uid(), id.syncScope());
    }

    private void applyRecord(TokenIdentity id, Map<String, Object> record, String eventId, String dedupeKey) {
        if (record == null || record.isEmpty()) {
            return;
        }
        if (isDelete(record)) {
            delete(id.uid(), id.syncScope(), PlaybackDeleteInput.fromMap(record));
            return;
        }
        upsert(id.uid(), id.syncScope(), PlaybackSyncInput.fromMap(record), eventId, dedupeKey);
    }

    /** 同步数据每个分区按播放时间排序只保留最新 100 条；窗口外记录直接丢弃。 */
    private void trimHistory(int uid, String syncScope) {
        List<History> rows = new ArrayList<>(
                findAllSync(uid, syncScope, Sort.unsorted()));
        rows.sort(Comparator.comparingLong(this::timeOf).reversed());
        Set<PlaybackIdentity> identities = new HashSet<>();
        List<History> removed = new ArrayList<>();
        for (History row : rows) {
            if (!identities.add(identityOf(row)) || identities.size() > SYNC_HISTORY_LIMIT) {
                removed.add(row);
            }
        }
        if (!removed.isEmpty()) {
            historyRepository.deleteAll(removed);
            log.debug("trimmed playback sync history: uid={} scope={} removed={}", uid, syncScope, removed.size());
        }
    }

    private void upsert(int uid, String syncScope, PlaybackSyncInput in, String eventId, String dedupeKey) {
        if (in.getVodId() == null || in.getVodId().isBlank()) {
            log.debug("skip upsert: missing vodId (uid={})", uid);
            return;
        }
        normalizeSource(in);
        String sourceKind = in.getSourceKind() != null ? in.getSourceKind() : KIND_SITE;
        String dedupe = dedupeKey != null && !dedupeKey.isBlank() ? dedupeKey
                : (eventId != null && !eventId.isBlank() ? eventId : null);
        if (dedupe != null) {
            String cacheKey = uid + ":" + syncScope + ":" + dedupe;
            if (idempotency.getIfPresent(cacheKey) != null) {
                return;
            }
            markIdempotentAfterCommit(cacheKey);
        }

        long now = System.currentTimeMillis();
        long updatedAt = in.getUpdatedAt() > 0 ? in.getUpdatedAt() : now;
        // 序列表既分配游标也充当同步写入的全局互斥锁。必须先加锁再读墓碑/历史:
        // 否则两个事务都可能先读到“不存在”,随后各建一行;DELETE 与 UPSERT 也可能交错复活旧数据。
        long changeSeq = nextChangeSeq();

        long deletedAt = tombstoneWatermark(uid, syncScope, sourceKind, in.getSourceKey(), in.getVodId());
        if (updatedAt <= deletedAt) {
            log.debug("skip resurrect (tombstone newer): uid={} vodId={}", uid, in.getVodId());
            return;
        }

        List<History> matches = findByIdentity(uid, syncScope, sourceKind, in.getSourceKey(), in.getVodId());
        History exist = newestHistory(matches);
        deleteDuplicateHistories(matches, exist);
        if (exist != null) {
            long existTime = timeOf(exist);
            if (updatedAt < existTime) {
                log.debug("skip not newer: uid={} vodId={} remote={} local={}", uid, in.getVodId(), updatedAt, existTime);
                return;
            }
            if (updatedAt == existTime) {
                String sourceName = in.getSourceName() == null ? null : clamp(in.getSourceName(), 255);
                if (sourceName != null && !sourceName.isBlank() && !sourceName.equals(exist.getSourceName())) {
                    exist.setSourceName(sourceName);
                    exist.setChangeSeq(changeSeq);
                    historyRepository.save(exist);
                    log.debug("repaired source name: uid={} vodId={} sourceName={}",
                            uid, in.getVodId(), sourceName);
                } else {
                    log.debug("skip not newer: uid={} vodId={} remote={} local={}",
                            uid, in.getVodId(), updatedAt, existTime);
                }
                return;
            }
        }

        History h = exist != null ? exist : new History();
        if (exist == null) {
            h.setUid(uid);
            h.setSyncScope(syncScope);
            h.setSourceKind(sourceKind);
            h.setSourceKey(in.getSourceKey());
            h.setVodId(in.getVodId());
            h.setKey(buildKey(sourceKind, in.getSourceKey(), in.getVodId()));
        }
        if (in.getVodName() != null) {
            h.setVodName(clamp(in.getVodName(), 255));
        }
        if (in.getSourceName() != null) {
            h.setSourceName(clamp(in.getSourceName(), 255));
        }
        // 图片是 URL:截断只会得到坏链接,超长干脆不写
        if (in.getVodPic() != null && in.getVodPic().length() <= 255) {
            h.setVodPic(in.getVodPic());
        }
        if (in.getVodFlag() != null) {
            h.setVodFlag(clamp(in.getVodFlag(), 255));
        }
        if (in.getEpisodeName() != null) {
            h.setVodRemarks(clamp(in.getEpisodeName(), 255));
        }
        if (in.getEpisode() != null) {
            h.setEpisode(in.getEpisode());
        }
        if (in.getEpisodeUrl() != null) {
            h.setEpisodeUrl(in.getEpisodeUrl());
        }
        if (in.getSpeed() > 0) {
            h.setSpeed(in.getSpeed());
        }
        // 跳过点仅在非零时更新:避免每个播放 tick 用 0 覆盖已设置的片头/片尾
        if (in.getOpeningMs() > 0) {
            h.setOpening(in.getOpeningMs());
        }
        if (in.getEndingMs() > 0) {
            h.setEnding(in.getEndingMs());
        }
        // completed:夹紧到结尾
        if (in.isCompleted() && in.getDurationMs() > 0) {
            h.setPosition(in.getDurationMs());
        } else {
            h.setPosition(in.getPositionMs());
        }
        h.setDuration(in.getDurationMs());
        h.setClientKey(clamp(in.getClientKey(), 64));
        if (in.getPlaylistIndex() != null) {
            h.setPlaylistIndex(in.getPlaylistIndex());
        }
        if (in.getSourceGroupIndex() != null) {
            h.setSourceGroupIndex(in.getSourceGroupIndex());
        }
        if (in.getSourceIndex() != null) {
            h.setSourceIndex(in.getSourceIndex());
        }
        if (in.getSourceSubgroupIndex() != null) {
            h.setSourceSubgroupIndex(in.getSourceSubgroupIndex());
        }
        if (in.getSourceSubgroupName() != null) {
            h.setSourceSubgroupName(clamp(in.getSourceSubgroupName(), 255));
        }
        if (in.getDriveDirId() != null) {
            h.setDriveDirId(in.getDriveDirId());
        }
        h.setUpdatedAt(updatedAt);
        h.setChangeSeq(changeSeq);
        h.setCreateTime(updatedAt);
        historyRepository.save(h);
    }

    /**
     * 删除事件。scope 决定作用域,不是所有 scope 都带条目身份:
     * all 清空该用户全部记录、site 清空某来源、item(默认)按 (kind,key,vodId) 删单条。
     */
    @Transactional
    public void delete(int uid, String syncScope, PlaybackDeleteInput in) {
        normalizeSource(in);
        String sourceKind = in.getSourceKind() != null ? in.getSourceKind() : KIND_SITE;
        long deletedAt = in.getDeletedAt() > 0 ? in.getDeletedAt() : System.currentTimeMillis();
        String scope = in.getScope() == null ? SCOPE_ITEM : in.getScope().trim().toLowerCase();
        long changeSeq = nextChangeSeq();
        switch (scope) {
            case SCOPE_ALL -> deleteAll(uid, syncScope, deletedAt, changeSeq);
            case SCOPE_SITE -> deleteSite(uid, syncScope, sourceKind, in.getSourceKey(), deletedAt, changeSeq);
            default -> deleteItem(uid, syncScope, sourceKind, in, deletedAt, changeSeq);
        }
    }

    private void deleteAll(int uid, String syncScope, long deletedAt, long changeSeq) {
        PlaybackTombstone tomb = findAllTomb(uid, syncScope);
        if (tomb == null) {
            tomb = new PlaybackTombstone();
            tomb.setUid(uid);
            tomb.setSyncScope(syncScope);
            tomb.setScope(SCOPE_ALL);
        }
        forceUidGlobalScope(tomb, syncScope);
        saveTombstone(tomb, deletedAt, changeSeq);
        removeHistory(findAllSync(uid, syncScope, Sort.unsorted()), deletedAt);
    }

    private void deleteSite(int uid, String syncScope, String sourceKind, String sourceKey, long deletedAt, long changeSeq) {
        if (sourceKey == null || sourceKey.isBlank()) {
            log.debug("skip site delete: missing sourceKey (uid={})", uid);
            return;
        }
        PlaybackTombstone tomb = findSiteTomb(uid, syncScope, sourceKind, sourceKey);
        if (tomb == null) {
            tomb = new PlaybackTombstone();
            tomb.setUid(uid);
            tomb.setSyncScope(syncScope);
            tomb.setScope(SCOPE_SITE);
            tomb.setSourceKind(sourceKind);
            tomb.setSourceKey(sourceKey);
        }
        forceUidGlobalScope(tomb, syncScope);
        saveTombstone(tomb, deletedAt, changeSeq);
        removeHistory(findBySite(uid, syncScope, sourceKind, sourceKey), deletedAt);
    }

    private void deleteItem(int uid, String syncScope, String sourceKind, PlaybackDeleteInput in, long deletedAt, long changeSeq) {
        if (in.getVodId() == null || in.getVodId().isBlank()) {
            log.debug("skip item delete: missing vodId (uid={})", uid);
            return;
        }
        List<History> histories = findByIdentity(uid, syncScope, sourceKind, in.getSourceKey(), in.getVodId());
        List<PlaybackTombstone> tombstones = findTombsByIdentity(uid, syncScope, sourceKind, in.getSourceKey(), in.getVodId());
        PlaybackTombstone tomb = newestTombstone(tombstones);
        if (tomb == null) {
            tomb = new PlaybackTombstone();
            tomb.setUid(uid);
            tomb.setSyncScope(syncScope);
            tomb.setScope(SCOPE_ITEM);
            tomb.setSourceKind(sourceKind);
            tomb.setSourceKey(in.getSourceKey());
            tomb.setVodId(in.getVodId());
        }
        forceUidGlobalScope(tomb, syncScope);
        String historyKey = portableHistoryKey(sourceKind, in.getSourceKey(), in.getVodId(), in.getHistoryKey());
        if (historyKey != null) {
            tomb.setHistoryKey(historyKey);
        }
        saveTombstone(tomb, deletedAt, changeSeq);
        if (tombstones.size() > 1) {
            PlaybackTombstone keep = tomb;
            tombstoneRepository.deleteAll(tombstones.stream().filter(row -> row != keep).toList());
        }
        removeHistory(histories, deletedAt);
    }

    private void saveTombstone(PlaybackTombstone tomb, long deletedAt, long changeSeq) {
        if (deletedAt > tomb.getDeletedAt()) {
            tomb.setDeletedAt(deletedAt);
            tomb.setChangeSeq(changeSeq);
        } else if (tomb.getChangeSeq() == null) {
            tomb.setChangeSeq(changeSeq);
        }
        tomb.setExpireAt(tomb.getDeletedAt() + TOMBSTONE_TTL_MS);
        tombstoneRepository.save(tomb);
    }

    /**
     * uid 级删除(management/网页,syncScope=null)复用到一个 scoped 墓碑时,必须把它降级回 uid 全局分区
     * (sync_scope=NULL),否则该删除只对原分区可见,其他 scoped 客户端收不到 → 记录被下次 PUSH 复活。
     * scoped 调用不动墓碑既有分区,避免把全局墓碑“俘获”进某个分区。
     */
    private void forceUidGlobalScope(PlaybackTombstone tomb, String syncScope) {
        if (syncScope == null) {
            tomb.setSyncScope(null);
        }
    }

    /**
     * LWW:只删不比该删除事件更新的记录。墓碑仅能挡住后续的过期 upsert,
     * 无法还原已被删掉的行,所以迟到的删除必须在此让位于更新的本地记录。
     */
    private void removeHistory(List<History> rows, long deletedAt) {
        List<History> stale = new ArrayList<>();
        for (History h : rows) {
            if (timeOf(h) <= deletedAt) {
                stale.add(h);
            } else {
                log.debug("keep newer history: uid={} vodId={} local={} delete={}",
                        h.getUid(), h.getVodId(), timeOf(h), deletedAt);
            }
        }
        if (!stale.isEmpty()) {
            historyRepository.deleteAll(stale);
        }
    }

    /** 覆盖某条目的最新删除时间:item ∪ site ∪ all 三种作用域取最大值(同分区内)。 */
    private long tombstoneWatermark(int uid, String syncScope, String sourceKind, String sourceKey, String vodId) {
        long watermark = 0;
        PlaybackTombstone item = newestTombstone(findTombsByIdentity(uid, syncScope, sourceKind, sourceKey, vodId));
        if (item != null) {
            watermark = item.getDeletedAt();
        }
        if (sourceKey != null) {
            PlaybackTombstone site = findSiteTomb(uid, syncScope, sourceKind, sourceKey);
            if (site != null && site.getDeletedAt() > watermark) {
                watermark = site.getDeletedAt();
            }
        }
        PlaybackTombstone all = findAllTomb(uid, syncScope);
        if (all != null && all.getDeletedAt() > watermark) {
            watermark = all.getDeletedAt();
        }
        return watermark;
    }

    // ── PULL:游标增量下发 ─────────────────────────────────────────────────

    /**
     * 游标增量下发。items 与 tombstones 必须作为**同一条按时间排序的变更流**截断:
     * 两边各自截断、游标取两边最大值时,较新的墓碑会把未下发的 history 尾巴推过游标,
     * 而后续查询用的是 GreaterThan,那些记录将被永久跳过。
     * 同一时间戳的变更整组下发(组内无法用 GreaterThan 断点续传),故允许轻微超出 limit。
     */
    public PlaybackSyncPage pull(int uid, long since, int limit, String sourceKind) {
        return pull(uid, null, since, limit, sourceKind, null, false);
    }

    /** 返回该用户数据库中的全部同步记录，仅供诊断；不应用同步窗口和分页限制。 */
    @Transactional(readOnly = true)
    public List<PlaybackSyncInput> listAll(int uid) {
        return historyRepository.findAllByUidAndSourceKindIsNotNull(
                        uid, Sort.by(Sort.Direction.DESC, "updatedAt", "id"))
                .stream()
                .map(this::toInput)
                .toList();
    }

    /** 分页返回该用户数据库中的同步记录，页码从 0 开始。 */
    @Transactional(readOnly = true)
    public Page<PlaybackSyncInput> list(int uid, int page, int pageSize) {
        return list(uid, page, pageSize, false);
    }

    /**
     * 分页返回该用户数据库中的同步记录，页码从 0 开始。
     *
     * @param webPlayable true = 仅返回网页端可播放的来源(「网页播放」tab);false = 全部(「多端同步」tab)
     */
    @Transactional(readOnly = true)
    public Page<PlaybackSyncInput> list(int uid, int page, int pageSize, boolean webPlayable) {
        int safePage = Math.max(page, 0);
        int safePageSize = pageSize > 0 && pageSize <= MAX_LIMIT ? pageSize : DEFAULT_LIMIT;
        PageRequest pageable = PageRequest.of(safePage, safePageSize,
                Sort.by(Sort.Direction.DESC, "updatedAt", "id"));
        Page<History> rows = webPlayable
                ? historyRepository.findPageByUidAndSourceKeyIn(uid, WEB_PLAYABLE_SITE_KEYS, pageable)
                : historyRepository.findPageByUidAndSourceKindIsNotNull(uid, pageable);
        return rows.map(this::toInput);
    }

    /** 按跨端身份返回单条记录，供网页播放器恢复其他设备上报的进度。 */
    @Transactional(readOnly = true)
    public PlaybackSyncInput getRecord(int uid, String sourceKind, String sourceKey, String vodId) {
        PlaybackSyncInput input = new PlaybackSyncInput();
        input.setSourceKind(sourceKind);
        input.setSourceKey(sourceKey);
        input.setVodId(vodId);
        normalizeSource(input);
        String normalizedKind = input.getSourceKind() != null ? input.getSourceKind() : KIND_SITE;
        return historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(
                        uid, normalizedKind, input.getSourceKey(), vodId)
                .stream()
                .max(Comparator.comparingLong(this::timeOf))
                .map(this::toInput)
                .orElse(null);
    }

    /** 管理页面按身份删除同步记录并生成墓碑，确保离线客户端不会重新上传。 */
    @Transactional
    public void deleteRecords(int uid, List<PlaybackDeleteInput> records) {
        if (records == null) {
            return;
        }
        long deletedAt = System.currentTimeMillis();
        for (PlaybackDeleteInput record : records) {
            if (record == null) {
                continue;
            }
            record.setScope(SCOPE_ITEM);
            record.setDeletedAt(deletedAt);
            delete(uid, null, record);
        }
    }

    /** 管理页面清空该用户的全部同步记录并生成全局墓碑。 */
    @Transactional
    public void deleteAllRecords(int uid) {
        PlaybackDeleteInput input = new PlaybackDeleteInput();
        input.setScope(SCOPE_ALL);
        input.setDeletedAt(System.currentTimeMillis());
        delete(uid, null, input);
    }

    public PlaybackSyncPage pull(int uid, long since, int limit, String sourceKind, boolean latest) {
        return pull(uid, null, since, limit, sourceKind, null, latest);
    }

    public PlaybackSyncPage pull(int uid, long since, int limit, String sourceKind,
                                 String siteKeyHeader, boolean latest) {
        return pull(uid, null, since, limit, sourceKind, siteKeyHeader, latest);
    }

    @Transactional(readOnly = true)
    public PlaybackSyncPage pull(int uid, String syncScope, long since, int limit, String sourceKind,
                                 String siteKeyHeader, boolean latest) {
        if (!appProperties.isPlaybackSyncEnabled()) {
            // 同步关闭时返回空页;游标推进到当前水位,避免 webhtv 客户端因 nextSince 不前进而死循环重试
            return emptySyncPage(currentHighWater(since));
        }
        int cap = limit > 0 && limit <= MAX_LIMIT ? limit : DEFAULT_LIMIT;
        List<String> sourceKinds = sourceKinds(sourceKind);
        Set<String> siteKeys = new HashSet<>(csvValues(siteKeyHeader));
        Sort sort = Sort.by("changeSeq").ascending();
        List<History> rows;
        if (sourceKinds.isEmpty()) {
            rows = findHistoryCursor(uid, syncScope, since, sort);
        } else if (sourceKinds.size() == 1) {
            rows = findHistoryCursorKind(uid, syncScope, sourceKinds.getFirst(), since, sort);
        } else {
            rows = findHistoryCursorKinds(uid, syncScope, sourceKinds, since, sort);
        }
        List<PlaybackTombstone> tombs = tombstones(uid, syncScope, since, sourceKinds);
        if (latest && since <= 0) {
            return latestPage(rows, tombs, cap, since, siteKeys);
        }

        PlaybackSyncPage page = new PlaybackSyncPage();
        long highWater = since;
        int i = 0;
        int j = 0;
        int sent = 0;
        while (i < rows.size() || j < tombs.size()) {
            boolean takeHistory = j >= tombs.size()
                    || (i < rows.size() && changeSeqOf(rows.get(i)) <= changeSeqOf(tombs.get(j)));
            long time = takeHistory ? changeSeqOf(rows.get(i)) : changeSeqOf(tombs.get(j));
            boolean selected = takeHistory
                    ? selectedForSiteKeys(rows.get(i), siteKeys)
                    : selectedForSiteKeys(tombs.get(j), siteKeys);
            if (selected && sent >= cap && time != highWater) {
                break;
            }
            if (takeHistory) {
                History row = rows.get(i++);
                if (selected) {
                    page.getItems().add(toInput(row));
                }
            } else {
                PlaybackTombstone tomb = tombs.get(j++);
                if (selected) {
                    page.getDeleted().add(toDelete(tomb));
                }
            }
            highWater = time;
            if (selected) {
                sent++;
            }
        }

        page.setNextSince(String.valueOf(highWater));
        return page;
    }

    private PlaybackSyncPage emptySyncPage(long highWater) {
        PlaybackSyncPage page = new PlaybackSyncPage();
        page.setNextSince(String.valueOf(highWater));
        return page;
    }

    /** 当前变更流水位(已分配的最大 change_seq);用作空响应的游标,让客户端游标前进。 */
    private long currentHighWater(long fallback) {
        return changeSequenceRepository.findById(1)
                .map(PlaybackChangeSequence::getNextVal)
                .orElse(fallback);
    }

    /** 首次同步只下发按实际播放时间排序的最新记录，并把游标推进到当前变更流水位。 */
    private PlaybackSyncPage latestPage(List<History> rows, List<PlaybackTombstone> tombs, int cap,
                                        long since, Set<String> siteKeys) {
        long highWater = since;
        for (History row : rows) {
            highWater = Math.max(highWater, changeSeqOf(row));
        }
        for (PlaybackTombstone tomb : tombs) {
            highWater = Math.max(highWater, changeSeqOf(tomb));
        }

        rows = new ArrayList<>(rows);
        rows.sort(Comparator.comparingLong(this::timeOf).reversed());
        PlaybackSyncPage page = new PlaybackSyncPage();
        Set<PlaybackIdentity> identities = new HashSet<>();
        for (History row : rows) {
            if (selectedForSiteKeys(row, siteKeys) && identities.add(identityOf(row))) {
                page.getItems().add(toInput(row));
                if (page.getItems().size() >= cap) {
                    break;
                }
            }
        }
        page.setNextSince(String.valueOf(highWater));
        return page;
    }

    /** 分源拉取时墓碑同样要按 sourceKind 过滤,否则会把别的来源的删除下发给该客户端。 */
    private List<PlaybackTombstone> tombstones(int uid, String syncScope, long since, List<String> sourceKinds) {
        Sort sort = Sort.by("changeSeq").ascending();
        if (sourceKinds.isEmpty()) {
            return findTombsCursor(uid, syncScope, since, sort);
        }
        List<PlaybackTombstone> list = new ArrayList<>(sourceKinds.size() == 1
                ? findTombsCursorKind(uid, syncScope, sourceKinds.getFirst(), since, sort)
                : findTombsCursorKinds(uid, syncScope, sourceKinds, since, sort));
        // all 作用域的墓碑不带 sourceKind,但对所有来源都生效,必须一并下发
        list.addAll(findTombsAllBreadth(uid, syncScope, since, sort));
        list.sort(Comparator.comparingLong(this::changeSeqOf));
        return list;
    }

    // ── 令牌 CRUD(session 用户调用) ────────────────────────────────────────

    public PlaybackTokenDto createToken(int uid, String name) {
        PlaybackToken t = new PlaybackToken();
        t.setUid(uid);
        t.setName(name);
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        long now = System.currentTimeMillis();
        t.setCreatedTime(now);
        t.setLastUsedAt(now);
        return toDto(tokenRepository.save(t), false);
    }

    public List<PlaybackTokenDto> listTokens(int uid) {
        return tokenRepository.findByUid(uid).stream().map(t -> toDto(t, true)).toList();
    }

    @Transactional
    public void deleteToken(int uid, int id) {
        tokenRepository.deleteByIdAndUid(id, uid);
    }

    // ── 墓碑清理 ────────────────────────────────────────────────────────────

    @Transactional
    @Scheduled(cron = "7 0 6 * * *")
    public void cleanTombstones() {
        long now = System.currentTimeMillis();
        tombstoneRepository.deleteByExpireAtBefore(now);
        log.info("cleaned playback tombstones expired before {}", now);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private boolean isDelete(Map<String, Object> record) {
        String event = record.get("event") == null ? null : String.valueOf(record.get("event")).trim();
        if ("playback.deleted".equals(event)) {
            return true;
        }
        String action = record.get("action") == null ? null : String.valueOf(record.get("action")).trim();
        if ("delete".equalsIgnoreCase(action)) {
            return true;
        }
        return record.containsKey("deletedAt") || record.containsKey("deleted_at");
    }

    /** atv-player 来源名与 TvBox 站点 key 归并到同一个规范身份。 */
    private void normalizeSource(PlaybackSyncInput input) {
        String siteKey = tvBoxSiteKey(input.getSourceKind(), input.getSourceKey());
        if (siteKey != null) {
            input.setSourceKind(KIND_SITE);
            input.setSourceKey(siteKey);
        }
    }

    private void normalizeSource(PlaybackDeleteInput input) {
        String siteKey = tvBoxSiteKey(input.getSourceKind(), input.getSourceKey());
        if (siteKey != null) {
            input.setSourceKind(KIND_SITE);
            input.setSourceKey(siteKey);
        }
    }

    private String tvBoxSiteKey(String sourceKind, String sourceKey) {
        if (sourceKind == null || KIND_SITE.equals(sourceKind) || KIND_SPIDER_PLUGIN.equals(sourceKind)) {
            return null;
        }
        return switch (sourceKind) {
            case "telegram" -> sourceKey != null && TELEGRAM_SITE_KEYS.contains(sourceKey)
                    ? sourceKey : "csp_TgDouBan";
            case "telegram_channel" -> "csp_TgChannel";
            case "browse" -> sourceKey != null && BROWSE_SITE_KEYS.contains(sourceKey)
                    ? sourceKey : "csp_AList";
            case "bilibili" -> "csp_BiliBili";
            case "emby" -> "csp_Emby";
            case "feiniu" -> "csp_FeiNiu";
            case "jellyfin" -> "csp_Jellyfin";
            default -> null;
        };
    }

    private String buildKey(String sourceKind, String sourceKey, String vodId) {
        if (KIND_SITE.equals(sourceKind) && sourceKey != null) {
            return sourceKey + "@@@" + vodId + "@@@0";
        }
        return vodId;
    }

    private String portableHistoryKey(String sourceKind, String sourceKey, String vodId,
                                      String suppliedHistoryKey) {
        if (suppliedHistoryKey != null && !suppliedHistoryKey.isBlank()) {
            return suppliedHistoryKey;
        }
        if ((KIND_SITE.equals(sourceKind) || KIND_SPIDER_PLUGIN.equals(sourceKind))
                && sourceKey != null && !sourceKey.isBlank()) {
            return buildKey(KIND_SITE, sourceKey, vodId);
        }
        return null;
    }

    private List<String> sourceKinds(String header) {
        return csvValues(header);
    }

    private List<String> csvValues(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private boolean selectedForSiteKeys(History history, Set<String> siteKeys) {
        return siteKeys.isEmpty()
                || (!KIND_SITE.equals(history.getSourceKind())
                && !KIND_SPIDER_PLUGIN.equals(history.getSourceKind()))
                || siteKeys.contains(history.getSourceKey());
    }

    private boolean selectedForSiteKeys(PlaybackTombstone tombstone, Set<String> siteKeys) {
        return siteKeys.isEmpty() || tombstone.getSourceKind() == null
                || (!KIND_SITE.equals(tombstone.getSourceKind())
                && !KIND_SPIDER_PLUGIN.equals(tombstone.getSourceKind()))
                || siteKeys.contains(tombstone.getSourceKey());
    }

    /** 记录的有效时钟:新协议写 updatedAt,历史遗留行只有 createTime。 */
    private long timeOf(History h) {
        return h.getUpdatedAt() != null ? h.getUpdatedAt() : h.getCreateTime();
    }

    private History newestHistory(List<History> rows) {
        return rows.stream().max(Comparator.comparingLong(this::timeOf)).orElse(null);
    }

    private PlaybackIdentity identityOf(History history) {
        return new PlaybackIdentity(history.getSourceKind(), history.getSourceKey(), history.getVodId());
    }

    private void deleteDuplicateHistories(List<History> rows, History keep) {
        if (rows.size() > 1) {
            historyRepository.deleteAll(rows.stream().filter(row -> row != keep).toList());
        }
    }

    private PlaybackTombstone newestTombstone(List<PlaybackTombstone> rows) {
        return rows.stream().max(Comparator.comparingLong(PlaybackTombstone::getDeletedAt)).orElse(null);
    }

    // ── 分区查询封装 ─────────────────────────────────────────────────────────
    // syncScope 为空 = uid 级(旧语义,所有订阅互通):走无分区谓词的派生查询,跨所有分区。
    // 非空 = 该分区:history 仅本分区(进度不串台);tombstone 还含 uid 全局墓碑
    //      (管理端/网页删除须下达 scoped 客户端,谓词见 PlaybackTombstoneRepository 的 scoped @Query)。

    private List<History> findByIdentity(int uid, String syncScope, String kind, String key, String vodId) {
        return syncScope == null
                ? historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(uid, kind, key, vodId)
                : historyRepository.findSyncByIdentity(uid, syncScope, kind, key, vodId);
    }

    private List<History> findHistoryCursor(int uid, String syncScope, long since, Sort sort) {
        return syncScope == null
                ? historyRepository.findByUidAndSourceKindIsNotNullAndChangeSeqGreaterThan(uid, since, sort)
                : historyRepository.findSyncByCursor(uid, syncScope, since, sort);
    }

    private List<History> findHistoryCursorKind(int uid, String syncScope, String kind, long since, Sort sort) {
        return syncScope == null
                ? historyRepository.findByUidAndSourceKindAndChangeSeqGreaterThan(uid, kind, since, sort)
                : historyRepository.findSyncByCursorAndKind(uid, syncScope, kind, since, sort);
    }

    private List<History> findHistoryCursorKinds(int uid, String syncScope, List<String> kinds, long since, Sort sort) {
        return syncScope == null
                ? historyRepository.findByUidAndSourceKindInAndChangeSeqGreaterThan(uid, kinds, since, sort)
                : historyRepository.findSyncByCursorAndKinds(uid, syncScope, kinds, since, sort);
    }

    private List<History> findAllSync(int uid, String syncScope, Sort sort) {
        return syncScope == null
                ? historyRepository.findAllByUidAndSourceKindIsNotNull(uid, sort)
                : historyRepository.findSyncAll(uid, syncScope, sort);
    }

    private List<History> findBySite(int uid, String syncScope, String kind, String key) {
        return syncScope == null
                ? historyRepository.findByUidAndSourceKindAndSourceKey(uid, kind, key)
                : historyRepository.findSyncBySite(uid, syncScope, kind, key);
    }

    private List<PlaybackTombstone> findTombsByIdentity(int uid, String syncScope, String kind, String key, String vodId) {
        return syncScope == null
                ? tombstoneRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(uid, kind, key, vodId)
                : tombstoneRepository.findSyncByIdentity(uid, syncScope, kind, key, vodId);
    }

    private PlaybackTombstone findSiteTomb(int uid, String syncScope, String kind, String key) {
        return syncScope == null
                ? tombstoneRepository.findFirstByUidAndScopeAndSourceKindAndSourceKeyOrderByDeletedAtDesc(uid, SCOPE_SITE, kind, key)
                : newestTombstone(tombstoneRepository.findSyncSite(uid, syncScope, SCOPE_SITE, kind, key));
    }

    private PlaybackTombstone findAllTomb(int uid, String syncScope) {
        return syncScope == null
                ? tombstoneRepository.findFirstByUidAndScopeOrderByDeletedAtDesc(uid, SCOPE_ALL)
                : newestTombstone(tombstoneRepository.findSyncAllScope(uid, syncScope, SCOPE_ALL));
    }

    private List<PlaybackTombstone> findTombsCursor(int uid, String syncScope, long since, Sort sort) {
        return syncScope == null
                ? tombstoneRepository.findByUidAndChangeSeqGreaterThan(uid, since, sort)
                : tombstoneRepository.findSyncByCursor(uid, syncScope, since, sort);
    }

    private List<PlaybackTombstone> findTombsCursorKind(int uid, String syncScope, String kind, long since, Sort sort) {
        return syncScope == null
                ? tombstoneRepository.findByUidAndSourceKindAndChangeSeqGreaterThan(uid, kind, since, sort)
                : tombstoneRepository.findSyncByCursorAndKind(uid, syncScope, kind, since, sort);
    }

    private List<PlaybackTombstone> findTombsCursorKinds(int uid, String syncScope, List<String> kinds, long since, Sort sort) {
        return syncScope == null
                ? tombstoneRepository.findByUidAndSourceKindInAndChangeSeqGreaterThan(uid, kinds, since, sort)
                : tombstoneRepository.findSyncByCursorAndKinds(uid, syncScope, kinds, since, sort);
    }

    private List<PlaybackTombstone> findTombsAllBreadth(int uid, String syncScope, long since, Sort sort) {
        return syncScope == null
                ? tombstoneRepository.findByUidAndSourceKindIsNullAndChangeSeqGreaterThan(uid, since, sort)
                : tombstoneRepository.findSyncAllBreadth(uid, syncScope, since, sort);
    }

    private record PlaybackIdentity(String sourceKind, String sourceKey, String vodId) {
    }

    /**
     * 展示类字段按列宽截断:它们只影响显示,截断远好过整条上报以 22001(Value too long)失败丢记录。
     * 身份字段(source_key/vod_id)绝不走这里 —— 截断会把不同条目并成同一条,vod_id 已放宽为 TEXT。
     */
    private String clamp(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private long changeSeqOf(History h) {
        return h.getChangeSeq() != null ? h.getChangeSeq() : timeOf(h);
    }

    private long changeSeqOf(PlaybackTombstone tombstone) {
        return tombstone.getChangeSeq() != null ? tombstone.getChangeSeq() : tombstone.getDeletedAt();
    }

    private long nextChangeSeq() {
        PlaybackChangeSequence sequence = changeSequenceRepository.findByIdForUpdate(1)
                .orElseThrow(() -> new IllegalStateException("playback change sequence is not initialized"));
        long next = sequence.getNextVal() + 1;
        sequence.setNextVal(next);
        return next;
    }

    private void markIdempotentAfterCommit(String cacheKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            idempotency.put(cacheKey, Boolean.TRUE);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                idempotency.put(cacheKey, Boolean.TRUE);
            }
        });
    }

    private PlaybackSyncInput toInput(History h) {
        PlaybackSyncInput in = new PlaybackSyncInput();
        in.setSourceKind(h.getSourceKind() != null ? h.getSourceKind() : KIND_SITE);
        in.setSourceKey(h.getSourceKey());
        in.setSourceName(h.getSourceName());
        in.setVodId(h.getVodId());
        in.setVodName(h.getVodName());
        in.setVodPic(h.getVodPic());
        in.setVodFlag(h.getVodFlag());
        in.setEpisodeName(h.getVodRemarks());
        in.setEpisode(h.getEpisode());
        in.setEpisodeUrl(h.getEpisodeUrl());
        in.setPositionMs(h.getPosition());
        in.setDurationMs(h.getDuration());
        in.setSpeed(h.getSpeed());
        in.setOpeningMs(h.getOpening());
        in.setEndingMs(h.getEnding());
        in.setCompleted(h.getDuration() > 0 && h.getPosition() >= h.getDuration());
        in.setUpdatedAt(timeOf(h));
        in.setClientKey(h.getClientKey());
        in.setPlaylistIndex(h.getPlaylistIndex());
        in.setSourceGroupIndex(h.getSourceGroupIndex());
        in.setSourceIndex(h.getSourceIndex());
        in.setSourceSubgroupIndex(h.getSourceSubgroupIndex());
        in.setSourceSubgroupName(h.getSourceSubgroupName());
        in.setDriveDirId(h.getDriveDirId());
        return in;
    }

    private PlaybackDeleteInput toDelete(PlaybackTombstone t) {
        PlaybackDeleteInput d = new PlaybackDeleteInput();
        d.setScope(t.getScope());
        d.setSourceKind(t.getSourceKind());
        d.setSourceKey(t.getSourceKey());
        d.setVodId(t.getVodId());
        d.setHistoryKey(t.getHistoryKey());
        d.setDeletedAt(t.getDeletedAt());
        return d;
    }

    private PlaybackTokenDto toDto(PlaybackToken t, boolean mask) {
        PlaybackTokenDto dto = new PlaybackTokenDto();
        dto.setId(t.getId());
        dto.setName(t.getName());
        dto.setToken(mask ? maskToken(t.getToken()) : t.getToken());
        dto.setCreatedTime(t.getCreatedTime());
        dto.setLastUsedAt(t.getLastUsedAt());
        return dto;
    }

    private String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return "****";
        }
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}
