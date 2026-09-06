package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.domain.DriveId;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionDto;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionEventDto;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionFilter;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionResourceDto;
import cn.har01d.alist_tvbox.dto.CastMember;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisode;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEventRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.entity.UserPreference;
import cn.har01d.alist_tvbox.entity.UserPreferenceRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.metadata.DoubanMetadataProvider;
import cn.har01d.alist_tvbox.service.metadata.MetadataService;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.TextUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 追剧订阅(自动追更)CRUD 与内容接口。巡检/换源/候选池逻辑见 {@link MediaSubscriptionCheckService}。
 * 固定挂载路径(/追剧/{id}-{名称})跨换源不变,保证播放地址与观看进度不中断。
 */
@Slf4j
@Service
public class MediaSubscriptionService {
    public static final String CATEGORY_ID = "msub";
    public static final String VOD_ID_PREFIX = "msub:";
    /** 片单条目「加入追剧」伪播放 id 前缀:msubadd-{vodId}(vodId 为片单形态 tmdb:tv:123 / s:{标题}),
     *  PlayController 截前缀后按片单条目建订阅,播放器经 msg 通道收到回执。 */
    public static final String SUBSCRIBE_PLAY_PREFIX = "msubadd-";
    /** 片单条目「取消追剧」伪播放 id 前缀:msubdel-{vodId},取消与该条目同名的全部订阅(含多季)。 */
    public static final String UNSUBSCRIBE_PLAY_PREFIX = "msubdel-";
    /** 订阅操作线路伪播放 id 前缀:msubstat-{subId}(订阅信息)/ msubcheck-{subId}(轻量检查更新)/
     *  msubinspect-{subId}(完整巡检,异步)/ msubunsub-{subId}(取消追剧),均 msg 回执。 */
    public static final String STAT_PLAY_PREFIX = "msubstat-";

    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 播出时间文案「MM-dd 周X HH:mm」(北京时间):欧美剧/追番多为周播,周几才是更新心智锚点,裸日期难对应。 */
    static String airTimeText(long epochMilli) {
        java.time.ZonedDateTime time = java.time.Instant.ofEpochMilli(epochMilli)
                .atZone(java.time.ZoneId.of(Constants.ZONE_ID));
        return String.format("%02d-%02d %s %02d:%02d", time.getMonthValue(), time.getDayOfMonth(),
                WEEKDAYS[time.getDayOfWeek().getValue() - 1], time.getHour(), time.getMinute());
    }
    public static final String CHECK_PLAY_PREFIX = "msubcheck-";
    public static final String INSPECT_PLAY_PREFIX = "msubinspect-";
    public static final String UNSUBSCRIBE_SUB_PLAY_PREFIX = "msubunsub-";
    /** 片单条目「媒体信息」伪播放 id 前缀:msubinfo-{vodId},msg 通道返回条目元数据,无任何副作用。
     *  排在选集第一位:部分播放器内核进详情会自动触发第一集播放,第一条目不能是订阅动作。 */
    public static final String INFO_PLAY_PREFIX = "msubinfo-";
    /** 片单条目「全局搜索」伪播放 id 前缀:msubsearch-{剧名}。spider 端本地拦截跳播放器搜索页
     *  (FongMi 系 SearchActivity 带 keyword extra 启动即对全部站点自动搜索),不请求后端。 */
    public static final String SEARCH_PLAY_PREFIX = "msubsearch-";
    /** TVBox 分集标题美化开关(Setting,默认关):剧集列表显示「集数. 分集标题(大小)」替代文件名 */
    public static final String SETTING_EPISODE_TITLES = "msub_episode_titles";
    /** 资源侧"可播集"状态口径:列目录见过(LISTED)或取链成功过(VERIFIED)的集源行 —— 详情装配与角标同源。 */
    private static final Set<String> LIVE_EPISODE_STATES = Set.of(
            MediaSubscriptionEpisodeSource.STATE_LISTED, MediaSubscriptionEpisodeSource.STATE_VERIFIED);
    /** 网页集数清单/详情分集单次返回上限:防预期集数等脏数据撑爆响应;超长番(1200+ 集)不受影响。
     *  巡检缺集检测(computeMissing)同用此上限 —— 集号超过它的长番与展示层口径一致。 */
    static final int MAX_EPISODE_ROWS = 5000;

    private final MediaSubscriptionRepository subscriptionRepository;
    private final MediaSubscriptionResourceRepository resourceRepository;
    private final MediaSubscriptionEventRepository eventRepository;
    private final cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository episodeRepository;
    private final cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository episodeSourceRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final MovieRepository movieRepository;
    private final UserService userService;
    private final TvBoxService tvBoxService;
    private final ShareService shareService;
    private final MetadataService metadataService;
    private final MediaSubscriptionCheckService checkService;
    private final MediaSubscriptionTransferService transferService;
    private final cn.har01d.alist_tvbox.entity.SettingRepository settingRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final ProxyService proxyService;
    private final cn.har01d.alist_tvbox.entity.SiteRepository siteRepository;
    private final cn.har01d.alist_tvbox.service.sitesearch.EpisodeFallbackService episodeFallbackService;

    public MediaSubscriptionService(MediaSubscriptionRepository subscriptionRepository,
                                    MediaSubscriptionResourceRepository resourceRepository,
                                    MediaSubscriptionEventRepository eventRepository,
                                    cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository episodeRepository,
                                    cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository episodeSourceRepository,
                                    UserPreferenceRepository preferenceRepository,
                                    MovieRepository movieRepository,
                                    UserService userService,
                                    TvBoxService tvBoxService,
                                    ShareService shareService,
                                    MetadataService metadataService,
                                    MediaSubscriptionCheckService checkService,
                                    MediaSubscriptionTransferService transferService,
                                    cn.har01d.alist_tvbox.entity.SettingRepository settingRepository,
                                    AppProperties appProperties,
                                    ObjectMapper objectMapper,
                                    ProxyService proxyService,
                                    cn.har01d.alist_tvbox.entity.SiteRepository siteRepository,
                                    cn.har01d.alist_tvbox.service.sitesearch.EpisodeFallbackService episodeFallbackService) {
        this.subscriptionRepository = subscriptionRepository;
        this.resourceRepository = resourceRepository;
        this.eventRepository = eventRepository;
        this.episodeRepository = episodeRepository;
        this.episodeSourceRepository = episodeSourceRepository;
        this.preferenceRepository = preferenceRepository;
        this.movieRepository = movieRepository;
        this.userService = userService;
        this.tvBoxService = tvBoxService;
        this.shareService = shareService;
        this.metadataService = metadataService;
        this.checkService = checkService;
        this.transferService = transferService;
        this.settingRepository = settingRepository;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.proxyService = proxyService;
        this.siteRepository = siteRepository;
        this.episodeFallbackService = episodeFallbackService;
    }

    /** 订阅 token → 归属用户:凭证形态(u-{username}-{secret})验真或裸 u-{username} → 该用户;
     * 共享 token/空 → 首个管理员(全局 tokens 无 u- 前缀,不撞车)。与 live-follow/播放同步一致。
     * 必须先按凭证形态解析:带密钥 token 整段(含 '-' 的用户名拼接)不是合法用户名,裸形态查不到会误回落管理员。 */
    /** token 归属用户(凭证形态优先、裸 u- 形态回退);全局/共享/无效 token 返回 null(管理级口径)。
     * 供 /p 代理等只需「是否用户级 token」的调用方使用,不带 resolveUid 的首个 ADMIN 兜底。 */
    public cn.har01d.alist_tvbox.entity.User resolveTokenUser(String token) {
        if (StringUtils.isBlank(token) || "-".equals(token)) {
            return null;
        }
        var user = userService.findUserByCredentialToken(token);
        if (user == null) {
            user = userService.findByUserVodToken(token);
        }
        return user;
    }

    public int resolveUid(String token) {
        var user = resolveTokenUser(token);
        if (user == null) {
            user = userService.list().stream()
                    .filter(candidate -> candidate.getRole() == Role.ADMIN)
                    .min(Comparator.comparingInt(candidate -> candidate.getId() == null ? Integer.MAX_VALUE : candidate.getId()))
                    .orElse(null);
        }
        return user == null || user.getId() == null ? 1 : user.getId();
    }

    public List<MediaSubscriptionDto> list(int uid) {
        List<MediaSubscription> subscriptions = subscriptionRepository.findByUidOrderByCreatedTimeDesc(uid);
        // 封面快照缺失(新订阅/升级存量)时后台预热:本轮先出占位/豆瓣库封面,回填后下次刷新可见。列表自身不等待任何外部调用
        for (MediaSubscription subscription : subscriptions) {
            if (StringUtils.isBlank(subscription.getCoverUrl())) {
                checkService.prewarmCoverAsync(subscription);
            }
        }
        return subscriptions.stream().map(this::toDto).toList();
    }

    @Transactional
    public MediaSubscriptionDto create(int uid, MediaSubscriptionRequest request) {
        if (request == null || StringUtils.isBlank(request.getName())) {
            throw new BadRequestException("订阅名称不能为空");
        }
        MediaSubscription subscription = new MediaSubscription();
        subscription.setUid(uid);
        // 标题列 VARCHAR(255):TG 消息名可超长,统一截断防 22001
        subscription.setName(StringUtils.abbreviate(request.getName().trim(), 250));
        subscription.setKeyword(StringUtils.abbreviate(
                StringUtils.defaultIfBlank(request.getKeyword(), request.getName()).trim(), 250));
        subscription.setCustomKeywords(normalizeCustomKeywords(request.getCustomKeywords()));
        // 季号兜底:片单/链接直订等入口不解析季号(片单曾硬编码 season=1),而条目名常写着"第四季"。
        // 季号错会同时击穿候选季过滤、SxxEyy 集号识别、播放列表集号解析三条链路,且都表现为"什么都没搜到"。
        subscription.setSeason(TextUtils.resolveSeason(request.getSeason(), subscription.getName()));
        // 同剧幂等(语义匹配,非精确名):剥季号后裸名相等且季号相容(任一方未标季即视为相容)——
        // web TMDB 搜「末日地堡」订的 S3 与豆瓣片单「末日地堡 第三季」是同一部订阅,
        // 精确名匹配会开出第二条重复条目;已存在则直接复用(删除后重订不受影响)
        Integer season = subscription.getSeason();
        String bareName = TextUtils.stripSeasonSuffix(subscription.getName());
        MediaSubscription existing = subscriptionRepository.findByUidOrderByCreatedTimeDesc(uid).stream()
                .filter(s -> matchesTitle(s, bareName, season))
                .findFirst().orElse(null);
        if (existing != null) {
            log.info("media subscription already exists: uid={} {} season={}, reuse id {}", uid,
                    subscription.getName(), season, existing.getId());
            return toDto(existing);
        }
        subscription.setDoubanId(request.getDoubanId());
        subscription.setMetaProvider(StringUtils.defaultIfBlank(request.getMetaProvider(), null));
        // meta_id 列 VARCHAR(64):official 源的 id 是剧名(外部长字符串),链接直订回落时无界
        subscription.setMetaId(abbreviateMetaId(request.getMetaId()));
        if (subscription.getMetaId() == null && subscription.getDoubanId() != null) {
            subscription.setMetaProvider(DoubanMetadataProvider.NAME);
            subscription.setMetaId(String.valueOf(subscription.getDoubanId()));
        }
        subscription.setExpectedEpisodes(request.getExpectedEpisodes());
        subscription.setManualTotalEpisodes(request.getManualTotalEpisodes() != null && request.getManualTotalEpisodes() > 0
                ? request.getManualTotalEpisodes() : null);
        subscription.setSeasonStartEpisode(request.getSeasonStartEpisode() != null && request.getSeasonStartEpisode() > 1
                ? request.getSeasonStartEpisode() : null);
        subscription.setMode(StringUtils.isBlank(request.getMode()) ? MediaSubscription.MODE_FOLLOW : request.getMode());
        subscription.setAccountId(request.getAccountId());
        subscription.setAccountIds(serializeAccountIds(request.getAccountIds(), request.getAccountId()));
        subscription.setCrossDrive(request.getCrossDrive() != null && request.getCrossDrive());
        // 磁力兜底仅转存模式可用:离线产物落全局离线配置账号,挂载模式无资源沉淀语义
        subscription.setMagnetOffline(MediaSubscription.MODE_TRANSFER.equals(subscription.getMode())
                && request.getMagnetOffline() != null && request.getMagnetOffline());
        subscription.setCheckIntervalHours(request.getCheckIntervalHours() != null && request.getCheckIntervalHours() > 0
                ? request.getCheckIntervalHours() : appProperties.getSubscription().getCheckIntervalHours());
        subscription.setCustomAirClock(requireAirClock(request.getCustomAirClock()));
        subscription.setAirWeekdays(serializeAirWeekdays(request.getAirWeekdays()));
        subscription.setFilterConfig(serializeFilter(resolveFilter(uid, request.getFilter())));
        subscription.setMainDrives(serializeMainDrives(request.getMainDrives()));
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        long now = System.currentTimeMillis();
        subscription.setCreatedTime(now);
        subscription.setUpdatedTime(now);
        subscription.setNextCheckTime(now); // 创建即到期,首轮巡检立即搜索挂载
        subscriptionRepository.saveAndFlush(subscription);

        subscription.setMountPath(buildMountPath(subscription));
        subscriptionRepository.save(subscription);
        log.info("media subscription created: uid={} {} {} mode={}", uid, subscription.getId(), subscription.getName(), subscription.getMode());
        return toDto(subscription);
    }

    @Transactional
    public MediaSubscriptionDto update(int uid, int id, MediaSubscriptionRequest request) {
        MediaSubscription subscription = getOwned(uid, id);
        if (request == null) {
            return toDto(subscription);
        }
        boolean searchRelevant = false;
        Integer previousSeason = subscription.getSeason();
        Integer previousSeasonStart = subscription.getSeasonStartEpisode();
        String previousMode = subscription.getMode();
        String previousAccountIds = subscription.getAccountIds();
        if (StringUtils.isNotBlank(request.getName())) {
            subscription.setName(StringUtils.abbreviate(request.getName().trim(), 250));
        }
        if (request.getKeyword() != null) {
            subscription.setKeyword(StringUtils.abbreviate(request.getKeyword().trim(), 250));
            searchRelevant = true;
        }
        if (request.getCustomKeywords() != null) {
            // 空串 = 清除(回归单主词搜索);变更即触发下一轮巡检立即生效
            subscription.setCustomKeywords(normalizeCustomKeywords(request.getCustomKeywords()));
            searchRelevant = true;
        }
        if (request.getSeason() != null) {
            subscription.setSeason(request.getSeason());
            searchRelevant = true;
        }
        if (request.getSeasonStartEpisode() != null) {
            // ≤0 = 清除(回归季内编号即官方编号)
            Integer start = request.getSeasonStartEpisode() > 1 ? request.getSeasonStartEpisode() : null;
            if (!Objects.equals(start, previousSeasonStart)) {
                // 集号语义变化:存量集源行全按旧编号落库,偏移后整体错位 —— 清池重扫。
                // 挂载/分享保留(同一批文件只是换编号),首轮巡检按新偏移重建集源行。
                subscription.setSeasonStartEpisode(start);
                checkService.resetInventoryForSeason(subscription,
                        subscription.getSeason() == null ? 1 : subscription.getSeason());
                searchRelevant = true;
            } else {
                subscription.setSeasonStartEpisode(start);
            }
        }
        if (request.getDoubanId() != null) {
            subscription.setDoubanId(request.getDoubanId());
        }
        if (request.getMetaProvider() != null) {
            subscription.setMetaProvider(StringUtils.defaultIfBlank(request.getMetaProvider(), null));
        }
        if (request.getMetaId() != null) {
            subscription.setMetaId(abbreviateMetaId(request.getMetaId())); // 列 VARCHAR(64),official 源 id 是剧名
            subscription.setMetaSyncTime(null); // 换条目立即重拉元数据
            subscription.setCoverUrl(null); // 封面快照随条目走,不留旧剧封面
        }
        if (request.getExpectedEpisodes() != null) {
            subscription.setExpectedEpisodes(request.getExpectedEpisodes());
        }
        if (request.getManualTotalEpisodes() != null) {
            // ≤0 = 清除(回退官方总集数口径)
            subscription.setManualTotalEpisodes(request.getManualTotalEpisodes() > 0
                    ? request.getManualTotalEpisodes() : null);
        }
        if (request.getMode() != null) {
            subscription.setMode(request.getMode());
            // 磁力兜底仅转存模式:切出 TRANSFER 时同步关闭,防孤儿开关(magnetOffline 未随请求携带时)
            if (!MediaSubscription.MODE_TRANSFER.equals(subscription.getMode())) {
                subscription.setMagnetOffline(false);
            }
        }
        if (request.getAccountId() != null) {
            subscription.setAccountId(request.getAccountId());
        }
        if (request.getAccountIds() != null || request.getAccountId() != null) {
            subscription.setAccountIds(serializeAccountIds(request.getAccountIds(), request.getAccountId()));
        }
        if (request.getCrossDrive() != null) {
            subscription.setCrossDrive(request.getCrossDrive());
        }
        if (request.getMagnetOffline() != null) {
            // 模式可能同请求内切换:以更新后的 mode 为准,非转存模式静默回落 false(顺滑降级不报错)
            subscription.setMagnetOffline(MediaSubscription.MODE_TRANSFER.equals(subscription.getMode())
                    && request.getMagnetOffline());
        }
        if (request.getCheckIntervalHours() != null && request.getCheckIntervalHours() > 0) {
            subscription.setCheckIntervalHours(request.getCheckIntervalHours());
        }
        if (request.getCustomAirClock() != null) {
            subscription.setCustomAirClock(requireAirClock(request.getCustomAirClock()));
            // 立即重放改写 schedule/nextAirTime(元数据刷新 24h 节流,等下轮太迟)并按新时刻重排
            checkService.applyCustomAirClock(subscription);
            checkService.scheduleNext(subscription);
        }
        if (request.getAirWeekdays() != null) {
            // 更新日变更(空列表 = 清除,回归官方日程/常规调度):立即重排,下一个更新日 20:15 起查
            subscription.setAirWeekdays(serializeAirWeekdays(request.getAirWeekdays()));
            checkService.scheduleNext(subscription);
        }
        if (request.getFilter() != null) {
            subscription.setFilterConfig(serializeFilter(request.getFilter()));
            searchRelevant = true;
        }
        if (request.getMainDrives() != null) {
            subscription.setMainDrives(serializeMainDrives(request.getMainDrives())); // 空列表 = 清除覆盖,回归全局
        }
        if (searchRelevant) {
            subscription.setNextCheckTime(System.currentTimeMillis());
        }
        subscription.setUpdatedTime(System.currentTimeMillis());
        int oldSeason = previousSeason == null || previousSeason <= 0 ? 1 : previousSeason;
        if (request.getSeason() != null && request.getSeason() > 0 && request.getSeason() != oldSeason) {
            resetForSeasonChange(uid, subscription, oldSeason, request.getSeason());
        }
        subscriptionRepository.save(subscription);
        // 编辑切入 TRANSFER(如挂载模式改转存)或转存目标账号变化:立即排队增量转存,
        // 不再等下一轮巡检/每小时 :40 sweep。afterCommit 再提交:transfer() 入口 findById
        // 要读已提交的新 mode,事务提交前抢先执行会读到旧 FOLLOW 静默跳过(单测直调无事务,兜底同步执行)
        if (MediaSubscription.MODE_TRANSFER.equals(subscription.getMode())
                && (!MediaSubscription.MODE_TRANSFER.equals(previousMode)
                        || !Objects.equals(previousAccountIds, subscription.getAccountIds()))) {
            int subscriptionId = subscription.getId();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        transferService.transferAsync(uid, subscriptionId);
                    }
                });
            } else {
                transferService.transferAsync(uid, subscriptionId);
            }
        }
        return toDto(subscription);
    }

    /**
     * 换季重置:旧季的资源池/挂载/集源行对新季全是误导 —— 明标旧季的候选继续躺在列表里,
     * 裸标题旧资源的集源行继续冒领集号(computeMissing 判"已齐"→永不搜索新季),主源顶着的
     * 也是旧季内容。季号一变即整体清空,首轮巡检按新季重搜重挂。
     * <p>
     * DB 清理在本事务内原子完成;远程卸载是 N 次 HTTP 往返,提交后异步执行(行锁不横跨远程调用,
     * 与 delete 同规),卸载失败仅记日志 —— 挂载 share 已无行引用,由既有清理兜底。
     */
    private void resetForSeasonChange(int uid, MediaSubscription subscription, int oldSeason, int newSeason) {
        int id = subscription.getId();
        List<Integer> shareIds = new ArrayList<>();
        for (MediaSubscriptionResource resource : resourceRepository.findBySubscriptionIdOrderByScoreDesc(id)) {
            if (resource.getShareId() != null) {
                shareIds.add(resource.getShareId());
            }
        }
        if (subscription.getShareId() != null) {
            shareIds.add(subscription.getShareId());
        }
        checkService.resetInventoryForSeason(subscription, newSeason);
        List<Integer> sharesToUnmount = List.copyOf(new java.util.LinkedHashSet<>(shareIds));
        Runnable cleanup = () -> {
            for (Integer shareId : sharesToUnmount) {
                // 共享挂载:share 仍被其它订阅引用时不卸载(换季只是本订阅重置,别人的挂载不动)
                if (isShareReferencedByOthers(shareId, id)) {
                    log.debug("share {} still referenced by another subscription, skip unmount on season reset", shareId);
                    continue;
                }
                try {
                    shareService.deleteShare(shareId);
                } catch (Exception e) {
                    log.warn("unmount share after season change failed: {} {}", shareId, e.getMessage());
                }
            }
            checkService.checkAsync(uid, id);
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            cleanup.run();
                        }
                    });
        } else {
            cleanup.run();
        }
        log.info("media subscription {} season changed {} -> {}: pool reset",
                id, oldSeason, newSeason);
    }

    public void delete(int uid, int id) {
        MediaSubscription subscription = getOwned(uid, id);
        // 取消标记先行:创建即触发首轮巡检,进行中的搜索/挂载(可达数分钟)在下一道阶段检查点收工,
        // 否则巡检会把已删剧的挂载重新建回 AList、尾部 save 把无 @Version 的订阅行 INSERT 复活
        checkService.onDeleted(id);
        // 主源 + 所有补缺挂载都要删,否则 temp=false 且清理豁免的挂载会永久泄漏。
        // 不挂 @Transactional:远程卸载是 N 次 HTTP 往返,坐在事务里行锁横跨整个远程调用,
        // /batch 删除循环放大 —— 先逐个卸载(失败只记日志),行删除交给各 repository 自带事务
        List<MediaSubscriptionResource> resources = resourceRepository.findBySubscriptionIdOrderByScoreDesc(id);
        for (MediaSubscriptionResource resource : resources) {
            if (resource.getShareId() != null) {
                if (isShareReferencedByOthers(resource.getShareId(), id)) {
                    log.debug("share {} still referenced by another subscription, skip unmount", resource.getShareId());
                    continue;
                }
                try {
                    shareService.deleteShare(resource.getShareId());
                } catch (Exception e) {
                    log.warn("delete resource share failed: {} {}", resource.getShareId(), e.getMessage());
                }
            }
        }
        if (subscription.getShareId() != null && !isShareReferencedByOthers(subscription.getShareId(), id)) {
            try {
                shareService.deleteShare(subscription.getShareId());
            } catch (Exception e) {
                log.warn("delete subscription share failed: {} {}", subscription.getShareId(), e.getMessage());
            }
        }
        List<Integer> resourceIds = resources.stream().map(MediaSubscriptionResource::getId).toList();
        episodeSourceRepository.deleteByResourceIdIn(resourceIds);
        episodeRepository.deleteBySubscriptionId(id);
        episodeFallbackService.deleteForSubscription(id); // 兜底覆盖层行 + 负缓存等内存态随行清理
        resourceRepository.deleteBySubscriptionId(id);
        eventRepository.deleteBySubscriptionId(id);
        subscriptionRepository.deleteById(id);
        checkService.forget(id, resourceIds); // 内存态(限频/轮次/冷却 Map)随行清理,防无界泄漏
        log.info("media subscription deleted: uid={} {} {}", uid, id, subscription.getName());
    }

    @Transactional
    public MediaSubscriptionDto pause(int uid, int id) {
        MediaSubscription subscription = getOwned(uid, id);
        subscription.setStatus(MediaSubscription.STATUS_PAUSED);
        subscription.setUpdatedTime(System.currentTimeMillis());
        subscriptionRepository.save(subscription);
        return toDto(subscription);
    }

    @Transactional
    public MediaSubscriptionDto resume(int uid, int id) {
        MediaSubscription subscription = getOwned(uid, id);
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setNextCheckTime(System.currentTimeMillis());
        subscription.setUpdatedTime(System.currentTimeMillis());
        subscriptionRepository.save(subscription);
        return toDto(subscription);
    }

    public List<MediaSubscriptionEventDto> events(int uid, int id) {
        getOwned(uid, id);
        return eventRepository.findTop100BySubscriptionIdOrderByCreatedTimeDesc(id).stream().map(e -> {
            MediaSubscriptionEventDto dto = new MediaSubscriptionEventDto();
            dto.setId(e.getId());
            dto.setType(e.getType());
            dto.setDetail(e.getDetail());
            dto.setCreatedTime(e.getCreatedTime());
            return dto;
        }).toList();
    }

    /**
     * 资源级起始集号(手动):该资源第 1 集对应全剧第 N 集,≤0/null = 清除。
     * 元数据全剧连续集号而该资源按季内/局部编号时(完结季季包)声明;改动后该资源集源行
     * 全按旧编号落库、平移后整体错位 —— 删行重扫,其它资源与挂载不动。
     */
    @Transactional
    public void setResourceEpisodeStart(int uid, int id, int resourceId, Integer startEpisode) {
        getOwned(uid, id);
        MediaSubscriptionResource resource = resourceRepository.findById(resourceId)
                .filter(r -> r.getSubscriptionId() == id)
                .orElseThrow(() -> new cn.har01d.alist_tvbox.exception.NotFoundException("资源不存在"));
        Integer start = startEpisode != null && startEpisode > 1 ? startEpisode : null;
        if (Objects.equals(start, resource.getStartEpisode()) && resource.getSeasonStarts() == null) {
            return;
        }
        resource.setStartEpisode(start);
        resource.setSeasonStarts(null); // 手动声明优先:清自动季包映射,列举回到裸编号+平移口径
        resource.setEpisodesFound(null);
        resourceRepository.save(resource);
        episodeSourceRepository.deleteByResourceId(resourceId);
        log.info("media subscription {} resource {} episode start set to {}: inventory reset", id, resourceId, start);
    }

    /**
     * 手动添加候选资源:用户自己找到的分享链接直接入候选池 —— 不挂载、不动当前主源
     * (与「启用/钉选」的转主源语义分开,回应"一启用就变主资源"),下轮巡检按需探测,
     * 可参与补缺/换源;手动源豁免自动门禁(盘白名单/年份/标题/排除词),候选序置顶(同 follow 的 1000 档)。
     * <p>
     * 同链幂等:候选/已挂载只更新提取码;REMOVED/RETIRED/REJECTED 复活回候选
     * (手动事实优先于既有判定,restoreResource 同款语义)。
     */
    public Map<String, Object> addResource(int uid, int id, String link, String password) {
        getOwned(uid, id);
        String normalized = StringUtils.trimToEmpty(link);
        if (StringUtils.isBlank(normalized)) {
            throw new BadRequestException("缺少分享链接");
        }
        normalized = StringUtils.abbreviate(normalized, 1000); // 列 VARCHAR(1024)
        cn.har01d.alist_tvbox.entity.Share probe = shareService.parseShareLink(normalized);
        if (probe == null || probe.getType() == null || !MediaSubscriptionCheckService.supportedDriveType(probe.getType())) {
            throw new BadRequestException("无法识别的网盘分享链接(支持夸克/UC/阿里/百度/115/天翼/移动/123/迅雷/光鸭)");
        }
        MediaSubscriptionResource resource =
                resourceRepository.findBySubscriptionIdAndLink(id, normalized).orElse(null);
        boolean existed = resource != null;
        boolean revived = false;
        if (resource == null) {
            resource = new MediaSubscriptionResource();
            resource.setSubscriptionId(id);
            resource.setLink(normalized);
            resource.setCreatedTime(System.currentTimeMillis());
        } else if (MediaSubscriptionResource.STATE_CANDIDATE.equals(resource.getState())
                || MediaSubscriptionResource.STATE_MOUNTED.equals(resource.getState())) {
            // 幂等:已在池/已挂载,只补提取码(挂载/状态一概不动)
        } else {
            // REMOVED/RETIRED/REJECTED:用户手动加 = 明确要它,复活回候选并允许下轮重探
            resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
            resource.setCheckedTime(null);
            resource.setMountPath(null);
            resource.setShareId(null);
            revived = true;
        }
        resource.setType(probe.getType());
        resource.setSource(MediaSubscriptionResource.SOURCE_MANUAL);
        if (StringUtils.isNotBlank(password)) {
            resource.setPassword(StringUtils.abbreviate(password.trim(), 128)); // 列 VARCHAR(128)
        }
        if (!existed) {
            resource.setTitle(null); // 探测后按目录内容呈现,展示时回落链接
            resource.setScore(1000); // 手动提供的源:换源/探测候选序置顶(与 follow「订阅即所见」同档)
            resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
        }
        resourceRepository.save(resource);
        addPoolEvent(id, existed
                ? "手动添加:链接已在资源池" + (StringUtils.isNotBlank(password) ? ",已更新提取码" : "")
                : "已手动添加候选:" + normalized + "(不挂载不动主源,巡检/补缺时自动探测)");
        log.info("media subscription {} manually added resource candidate {} (existed={}, revived={})",
                id, resource.getId(), existed, revived);
        return Map.of("success", true, "resourceId", resource.getId(), "existed", existed, "revived", revived);
    }

    /** 资源池事件(不发通知):手动添加/恢复这类管理动作只进页面时间线。 */
    private void addPoolEvent(int subscriptionId, String detail) {
        try {
            MediaSubscriptionEvent event = new MediaSubscriptionEvent();
            event.setSubscriptionId(subscriptionId);
            event.setType(MediaSubscriptionEvent.TYPE_POOL_FILLED);
            event.setDetail(detail);
            event.setCreatedTime(System.currentTimeMillis());
            eventRepository.save(event);
        } catch (Exception e) {
            log.warn("add pool event failed: {}", e.getMessage());
        }
    }

    public List<MediaSubscriptionResourceDto> resources(int uid, int id) {
        MediaSubscription subscription = getOwned(uid, id);
        Set<String> allowedDrives = checkService.allowedCandidateDrives(subscription);
        List<MediaSubscriptionResource> all = resourceRepository.findBySubscriptionIdOrderByScoreDesc(id);
        Map<Integer, Long> avgSizes = new HashMap<>();
        for (Object[] row : episodeSourceRepository.findAvgFileSizeGroupByResourceId(
                all.stream().map(MediaSubscriptionResource::getId).toList())) {
            avgSizes.put((Integer) row[0], ((Number) row[1]).longValue());
        }
        return all.stream()
                // 已挂载的照常展示(供流中,用户需要可见/可停用);其余行按候选盘白名单收敛,
                // 白名单外的存量候选不再被探测/换源,展示出来只会误导"有个源躺着没用";
                // 手动添加的豁免(用户明确要的源,加进来却看不见等于白加)
                .filter(r -> MediaSubscriptionResource.STATE_MOUNTED.equals(r.getState())
                        || MediaSubscriptionCheckService.manuallyAdded(r)
                        || MediaSubscriptionCheckService.driveAllowed(allowedDrives,
                        r.getType() == null ? null : DriveId.toDrive(r.getType())))
                .map(r -> {
            MediaSubscriptionResourceDto dto = new MediaSubscriptionResourceDto();
            dto.setId(r.getId());
            dto.setLink(r.getLink());
            dto.setPassword(r.getPassword());
            dto.setType(r.getType());
            dto.setDriveName(r.getType() == null ? null : DriveId.toDrive(r.getType()));
            dto.setSource(r.getSource());
            dto.setTitle(r.getTitle());
            dto.setEpisodesFound(r.getEpisodesFound());
            dto.setAvgFileSize(avgSizes.get(r.getId()));
            dto.setScore(r.getScore());
            dto.setState(r.getState());
            dto.setPrimary(MediaSubscriptionResource.STATE_MOUNTED.equals(r.getState())
                    && subscription.getMountPath() != null && subscription.getMountPath().equals(r.getMountPath()));
            dto.setPinned(Boolean.TRUE.equals(r.getPinned()));
            dto.setStartEpisode(r.getStartEpisode());
            dto.setCheckedTime(r.getCheckedTime());
            dto.setCreatedTime(r.getCreatedTime());
            return dto;
        }).toList();
    }

    public String getPreference(int uid) {
        return preferenceRepository.findByUid(uid).map(UserPreference::getConfig).orElse(null);
    }

    @Transactional
    public String savePreference(int uid, String configJson) {
        UserPreference preference = preferenceRepository.findByUid(uid).orElseGet(() -> {
            UserPreference created = new UserPreference();
            created.setUid(uid);
            return created;
        });
        preference.setConfig(configJson);
        preference.setUpdatedTime(System.currentTimeMillis());
        preferenceRepository.save(preference);
        return configJson;
    }

    /** TVBox/web"我的追更"列表(t=msub)。 */
    public MovieList contentList(int uid) {
        return contentList(uid, null, null);
    }

    /** 媒体库列表(csp_Media 源):status 过滤(active/ended/null=全部),keyword 搜索(名称包含)。
     * 封面走绝对地址的后端 /images 代理(安卓端直连豆瓣/TMDB 图床可能被墙/防盗链)。 */
    public MovieList contentList(int uid, String status, String keyword) {
        List<MediaSubscription> subscriptions = subscriptionRepository.findByUidOrderByCreatedTimeDesc(uid).stream()
                .filter(s -> filterByStatus(s, status))
                .filter(s -> StringUtils.isBlank(keyword) || StringUtils.contains(s.getName(), keyword.trim())
                        || StringUtils.contains(StringUtils.defaultString(s.getKeyword()), keyword.trim()))
                .sorted(Comparator.comparing((MediaSubscription s) -> s.getUpdatedTime() == null ? 0 : s.getUpdatedTime(),
                        Comparator.reverseOrder()))
                .toList();
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        for (MediaSubscription subscription : subscriptions) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(VOD_ID_PREFIX + subscription.getId());
            detail.setVod_name(displayName(subscription));
            detail.setVod_pic(absoluteCover(coverOf(subscription)));
            String rating = compactRatingSuffix(subscription);
            detail.setVod_remarks(buildRemarks(subscription)
                    + (rating.isEmpty() ? "" : " · " + rating));
            list.add(detail);
        }
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        log.debug("list: {}", result);
        return result;
    }

    /** 片单/订阅标题语义匹配:裸名(剥季号)相等 + 季号相容(任一方未标季即相容,双方都标则须相等)。 */
    private static boolean matchesTitle(MediaSubscription subscription, String bareName, Integer season) {
        if (!TextUtils.stripSeasonSuffix(subscription.getName()).equals(bareName)) {
            return false;
        }
        return season == null || subscription.getSeason() == null || season.equals(subscription.getSeason());
    }

    /** 豆瓣片单条目绑 id 优先路:本地豆瓣库精确名匹配(Movie.id 即豆瓣 subject id,零网络)。
     *  同名多部(翻拍)本地无年份无法消歧,返回 null 由调用方回落 suggest 严格匹配。 */
    public Integer localDoubanId(String name) {
        return localDoubanId(name, null);
    }

    /** 年份版:条目带年份(rexxar item / 本地 Movie.year)时只认年份命中的唯一候选消歧翻拍;
     *  年份对不上即 null(不同部,不退纯名防冒领),调用方回落 suggest 严格匹配。 */
    public Integer localDoubanId(String name, Integer year) {
        Movie movie = uniqueLocalMovie(name, year);
        return movie != null ? movie.getId() : null;
    }

    /** 豆瓣片单条目(s:)详情富化:本地豆瓣库精确名匹配唯一才返回,否则 null 由调用方回落仅标题详情。
     *  匹配口径与 localDoubanId 一致(年份过滤优先、纯名唯一兜底)。 */
    public MovieDetail localDoubanDetail(String name, Integer year) {
        Movie movie = uniqueLocalMovie(name, year);
        if (movie == null) {
            return null;
        }
        MovieDetail detail = new MovieDetail();
        detail.setVod_name(movie.getName());
        detail.setVod_pic(movie.getCover());
        if (movie.getYear() != null) {
            detail.setVod_year(String.valueOf(movie.getYear()));
        }
        detail.setVod_remarks(movie.getDbScore());
        detail.setType_name(movie.getGenre());
        detail.setVod_area(movie.getCountry());
        detail.setVod_lang(movie.getLanguage());
        detail.setVod_director(movie.getDirectors());
        detail.setVod_actor(movie.getActors());
        if (StringUtils.isNotBlank(movie.getDescription())) {
            detail.setVod_content(movie.getDescription());
        }
        return detail;
    }

    /** 同名候选严格唯一:带年份只认年份命中的唯一一条(年份对不上=不是同一部,不退纯名防冒领);
     *  无年份才用纯名唯一。 */
    private Movie uniqueLocalMovie(String name, Integer year) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        List<Movie> candidates = movieRepository.getByName(name.trim()).stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (candidates.isEmpty()) {
            log.debug("local douban match: name='{}' year={} no same-name row in local db", name, year);
            return null;
        }
        String candidateYears = candidates.stream()
                .map(m -> m.getId() + ":" + m.getYear())
                .collect(Collectors.joining(","));
        if (year != null) {
            List<Movie> byYear = candidates.stream()
                    .filter(m -> year.equals(m.getYear()))
                    .toList();
            if (byYear.size() == 1) {
                log.debug("local douban match: name='{}' year={} hit unique id={} (candidates {})",
                        name, year, byYear.get(0).getId(), candidateYears);
                return byYear.get(0);
            }
            log.debug("local douban match: name='{}' year={} rejected: year-filter matches={} (candidates {}) — mismatch means different title, no name-only fallback",
                    name, year, byYear.size(), candidateYears);
            return null;
        }
        if (candidates.size() == 1) {
            log.debug("local douban match: name='{}' year=null hit unique id={}", name, candidates.get(0).getId());
            return candidates.get(0);
        }
        log.debug("local douban match: name='{}' year=null rejected: {} same-name rows (candidates {}) cannot disambiguate",
                name, candidates.size(), candidateYears);
        return null;
    }

    /** 片单条目是否已在追:标题语义匹配(「末日地堡 第三季」命中 web 订的「末日地堡」S3)。 */
    public boolean isSubscribedTitle(int uid, String title) {
        String bareName = TextUtils.stripSeasonSuffix(title);
        return subscriptionRepository.findByUidOrderByCreatedTimeDesc(uid).stream()
                .anyMatch(s -> matchesTitle(s, bareName, TextUtils.parseTitleSeason(title)));
    }

    /** 与片单条目同剧的订阅 id 列表:裸名相等;季号(显式或条目名解析)已知则只取该季,
     *  完全未标季则同名各季一并撤销(回执报部数)。 */
    public List<Integer> subscriptionIdsByTitle(int uid, String title, Integer explicitSeason) {
        String bareName = TextUtils.stripSeasonSuffix(title);
        Integer season = explicitSeason != null ? explicitSeason : TextUtils.parseTitleSeason(title);
        return subscriptionRepository.findByUidOrderByCreatedTimeDesc(uid).stream()
                .filter(s -> matchesTitle(s, bareName, season))
                .map(MediaSubscription::getId)
                .toList();
    }

    /** 片单条目封面 → 客户端可用地址:直链图床包 /images 代理(防盗链/被墙),再按当前请求 host 重建绝对地址。 */
    public String absoluteClientCover(String cover) {
        return absoluteCover(cover);
    }

    /** 「最近更新」口径:updatedTime(集数变化/编辑时刷新)近 7 天。 */
    private static final long RECENT_WINDOW_MS = 7L * 24 * 3600 * 1000;

    private boolean filterByStatus(MediaSubscription subscription, String status) {
        if (StringUtils.isBlank(status) || "all".equals(status)) {
            return true;
        }
        return switch (status) {
            case "recent" -> subscription.getUpdatedTime() != null
                    && System.currentTimeMillis() - subscription.getUpdatedTime() <= RECENT_WINDOW_MS;
            case "active" -> !MediaSubscription.STATUS_ENDED.equals(subscription.getStatus())
                    && !MediaSubscription.STATUS_PAUSED.equals(subscription.getStatus());
            case "ended" -> MediaSubscription.STATUS_ENDED.equals(subscription.getStatus());
            default -> true;
        };
    }

    /** 安卓端封面:直链图(TMDB/豆瓣图床,客户端直连可能被墙/防盗链)先包进后端 /images 代理,
     * 再按当前请求 host 重建绝对地址。 */
    private String absoluteCover(String stored) {
        if (StringUtils.isBlank(stored)) {
            return stored;
        }
        if (stored.startsWith("http")) {
            stored = proxiedCover(stored);
        }
        if (!stored.startsWith("/images")) {
            return stored;
        }
        try {
            int queryAt = stored.indexOf('?');
            if (queryAt < 0) {
                return stored;
            }
            String query = stored.substring(queryAt + 1);
            boolean https = appProperties.isEnableHttps() && !cn.har01d.alist_tvbox.util.Utils.isLocalAddress();
            return org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentRequest()
                    .scheme(https ? "https" : "http")
                    .replacePath("/images")
                    .replaceQuery(query)
                    .build()
                    .toUriString();
        } catch (Exception e) {
            return stored;
        }
    }

    /** TVBox/web 详情(msub:{id}):元数据(名称/封面/年份/演员/简介…)直用订阅快照,与列表侧 contentList、
     * 媒体详情页 detail() 同源。vod_id 重写为稳定键。
     * TVBox 请求(空 ac)走集源行索引直装配(见 {@link #fastDetail});web/TG(非空 ac)与快路径兜底走旧实时列举。 */
    public MovieList contentDetail(int uid, int id, String ac, String title) {
        MediaSubscription subscription = getOwned(uid, id);
        if (StringUtils.isBlank(subscription.getMountPath()) || subscription.getShareId() == null) {
            MovieList result = new MovieList();
            MovieDetail detail = new MovieDetail();
            applySubscriptionMetadata(detail, subscription);
            detail.setVod_remarks("尚未找到可用资源");
            result.getList().add(detail);
            result.setTotal(1);
            result.setLimit(1);
            return result;
        }
        if (StringUtils.isBlank(ac)) {
            try {
                MovieList fast = fastDetail(subscription);
                if (fast != null) {
                    return fast;
                }
            } catch (Exception e) {
                log.debug("fast detail for subscription {} failed: {}", id, e.getMessage());
            }
        }
        return legacyDetail(subscription, ac, title);
    }

    /** 快路径详情:集源行索引直装配,零目录列举 —— 旧路径要对 主挂载/每转存目标/每补缺挂载(上限 6)各做
     * depth-3 递归列举(最坏 ~8 挂载点串行 HTTP),这里全部换成 DB 行 + PlayUrl 注册(纯 DB)。
     * 「我的追剧」逻辑线路 msubep-{id}-{集}(播放期实时选源降级,与旧路径同格式);盘线路条目 `1@{pid}`,
     * 点击时经 /play 才取真链。集清单空(首轮巡检前/全源失效)返回 null,由调用方回落旧路径。 */
    private MovieList fastDetail(MediaSubscription subscription) {
        int id = subscription.getId();
        // 盘线路装配序(同盘同集先到先得):转存 → 主源 → 其余 MOUNTED 按分降序 —— 与旧路径 主源>补缺 语义一致
        Map<String, TreeMap<Integer, String>> driveLines = new LinkedHashMap<>();
        Map<Integer, Long> sizeByEpisode = new TreeMap<>();
        cn.har01d.alist_tvbox.entity.Site site = siteRepository.findById(1).orElseThrow();
        if (MediaSubscription.MODE_TRANSFER.equals(subscription.getMode()) && !parseAccountIds(subscription).isEmpty()) {
            try {
                for (var target : transferService.transferredTargets(subscription.getUid(), id)) {
                    for (var entry : checkService.episodeFilesAt(target.path(), subscription).entrySet()) {
                        MediaSubscriptionCheckService.EpisodeFile file = entry.getValue();
                        sizeByEpisode.merge(entry.getKey(), file.size(), Math::max);
                        String path = file.dir() + "/" + file.name();
                        driveLine(driveLines, target.drive()).putIfAbsent(entry.getKey(),
                                fileEntry(site, path, file.name(), file.size(), subscription.getUid()));
                    }
                }
            } catch (Exception e) {
                // 转存列举失败只丢转存线路,资源行照常装配(episodeFilesAt 自身吞错,这里防账号/目标查询失败)
                log.debug("load transfer targets for subscription {} failed: {}", id, e.getMessage());
            }
        }
        Set<String> live = LIVE_EPISODE_STATES;
        Map<Integer, MediaSubscriptionResource> mounted = new LinkedHashMap<>();
        for (MediaSubscriptionResource resource : resourceRepository.findBySubscriptionIdOrderByScoreDesc(id)) {
            if (MediaSubscriptionResource.STATE_MOUNTED.equals(resource.getState())
                    && StringUtils.isNotBlank(resource.getMountPath())) {
                mounted.put(resource.getId(), resource);
            }
        }
        String mainMount = subscription.getMountPath();
        List<MediaSubscriptionResource> ordered = new ArrayList<>(mounted.values());
        ordered.sort(Comparator
                .comparing((MediaSubscriptionResource r) -> mainMount != null && mainMount.equals(r.getMountPath()) ? 0 : 1)
                .thenComparing(r -> -(r.getScore() == null ? 0 : r.getScore())));
        record LiveRow(int episode, MediaSubscriptionEpisodeSource source) {
        }
        Map<Integer, List<LiveRow>> rowsByResource = new java.util.HashMap<>();
        for (Object[] pair : episodeSourceRepository.findNumberAndSource(id)) {
            Integer number = (Integer) pair[0];
            MediaSubscriptionEpisodeSource row = (MediaSubscriptionEpisodeSource) pair[1];
            if (number != null && number > 0 && live.contains(row.getState())) {
                rowsByResource.computeIfAbsent(row.getResourceId(), key -> new ArrayList<>()).add(new LiveRow(number, row));
            }
        }
        for (MediaSubscriptionResource resource : ordered) {
            String drive = resource.getType() == null ? null : DriveId.toDrive(resource.getType());
            for (LiveRow liveRow : rowsByResource.getOrDefault(resource.getId(), List.of())) {
                int episode = liveRow.episode();
                MediaSubscriptionEpisodeSource row = liveRow.source();
                sizeByEpisode.merge(episode, row.getFileSize() == null ? 0L : row.getFileSize(), Math::max);
                if (drive != null) {
                    String path = resource.getMountPath() + "/" + row.getRelPath();
                    driveLine(driveLines, drive).putIfAbsent(episode,
                            fileEntry(site, path, row.getRelPath(), row.getFileSize() == null ? 0L : row.getFileSize(), subscription.getUid()));
                }
            }
        }
        // 逻辑线路「我的追剧」:集号并集(资源行 ∪ 转存),标题元数据优先
        Map<Integer, String> titles = episodeTitles(subscription);
        TreeMap<Integer, String> merged = new TreeMap<>();
        for (Integer episode : sizeByEpisode.keySet()) {
            merged.put(episode, logicalEpisodeTitle(episode, titles.get(episode), sizeByEpisode.get(episode))
                    + "$msubep-" + id + '-' + episode);
        }
        // 采集源兜底覆盖层并入:已垫底的缺集进逻辑线路可点可播(msubep 播放期走覆盖层快路径),
        // 真源恢复后 putIfAbsent 让位 —— 覆盖层只补洞,不覆盖真源条目(裸实例测试可注入 null,同 CheckService 守卫)
        List<cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallback> overlayRows =
                episodeFallbackService == null ? List.of() : episodeFallbackService.activeRows(id);
        for (cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallback row : overlayRows) {
            if (row.getEpisode() <= 0) {
                continue;
            }
            merged.putIfAbsent(row.getEpisode(), logicalEpisodeTitle(row.getEpisode(), titles.get(row.getEpisode()), 0)
                    + "$msubep-" + id + '-' + row.getEpisode());
        }
        if (merged.isEmpty()) {
            return null;
        }
        rewriteEpisodeTitles(subscription, merged, driveLines);
        // 缺集占位(与巡检 computeMissing/网页缺集同口径,不受采集兜底开关影响):缺失集在逻辑线路
        // 可见可点 —— 兜底开着则播放期同步救活,关着则如实报「暂无可用播放源」,不再整集隐身。
        // 放在 rewriteEpisodeTitles 之后:标题改写器会重建条目文本,(缺源)标记不能被改掉;
        // putIfAbsent 让真源/覆盖层条目优先,占位只补洞。
        for (Integer episode : missingEpisodes(subscription)) {
            merged.putIfAbsent(episode,
                    logicalEpisodeTitle(episode, titles.get(episode), 0) + "(缺源)$msubep-" + id + '-' + episode);
        }
        String[] lines = buildTvBoxPlayLines(id, merged, driveLines, Set.copyOf(checkService.mainDrives(subscription)));
        appendActionLine(lines, id);
        MovieDetail detail = new MovieDetail();
        applySubscriptionMetadata(detail, subscription);
        detail.setVod_play_from(lines[0]);
        detail.setVod_play_url(lines[1]);
        MovieList result = new MovieList();
        result.getList().add(detail);
        result.setTotal(1);
        result.setLimit(1);
        kickDriveLines(subscription, driveLines.keySet());
        log.debug("fast media subscription result: {}", result);
        return result;
    }

    /** 盘线路 pid 的长效注册窗口:播放历史/跨端同步绑定的 `1@pid` 地址一年内可播,每次打开详情自动续期;
     * 剧完结停止回放一年后由 clean 自然回收(默认 7 天有效期会把历史里的物理地址变成死链)。 */
    private static final java.time.Duration DRIVE_LINE_PID_TTL = java.time.Duration.ofDays(365);

    /** 盘线路条目:`文件名(大小)$1@{pid}` —— pid 经 PlayUrl 长效注册(纯 DB,带订阅归属),点击时才解析真链。 */
    private String fileEntry(cn.har01d.alist_tvbox.entity.Site site, String path, String relPath, long size, int ownerUid) {
        String name = relPath.contains("/") ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
        String sizeText = size > 0 ? "(" + cn.har01d.alist_tvbox.util.Utils.byte2size(size) + ")" : "";
        return name + sizeText + "$1@" + proxyService.generateProxyUrl(site, path, DRIVE_LINE_PID_TTL, ownerUid);
    }

    /** 逻辑线路分集标题:`NN. 分集标题(大小)`(分集标题读元数据快照零网络;无标题兜底"第N集",无大小省略括号)。 */
    static String logicalEpisodeTitle(int episode, String metaTitle, long sizeBytes) {
        String number = episode > 0 && episode < 100 ? String.format("%02d", episode) : String.valueOf(episode);
        String title = StringUtils.defaultIfBlank(sanitizeTitle(metaTitle), "第" + episode + "集");
        String size = sizeBytes > 0 ? "(" + cn.har01d.alist_tvbox.util.Utils.byte2size(sizeBytes) + ")" : "";
        return number + ". " + title + size;
    }

    /** 旧路径详情:复用 TvBoxService 播放列表(代理 URL/排序,逐挂载点列目录)+ 多源合并 —— 快路径的兜底。 */
    private MovieList legacyDetail(MediaSubscription subscription, String ac, String title) {
        MovieList result;
        try {
            result = tvBoxService.getDetail(ac, "1$" + subscription.getMountPath() + Constants.PLAYLIST,
                    StringUtils.defaultIfBlank(title, displayName(subscription)), null, null, true);
            if (!result.getList().isEmpty()) {
                applySubscriptionMetadata(result.getList().get(0), subscription);
            }
            mergeGapPlaylists(subscription, result, ac);
        } catch (Exception e) {
            log.warn("subscription detail failed: {} {}", subscription.getId(), e.getMessage());
            MovieDetail detail = new MovieDetail();
            applySubscriptionMetadata(detail, subscription);
            detail.setVod_remarks("资源暂时不可用:" + e.getMessage());
            result = new MovieList();
            result.getList().add(detail);
            result.setTotal(1);
            result.setLimit(1);
        }
        log.debug("media subscription result: {}", result);
        return result;
    }

    /** 订阅元数据覆写(vod_id/名称/封面/状态 + 快照详情字段)。快照读 media_metadata 持久层零网络,
     * 快照缺失的字段再用 douban_id 查本地豆瓣库(Movie 行)补空;无快照无豆瓣行时仅基础字段 ——
     * 替代旧版按挂载路径名匹配豆瓣/TMDB(路径名带资源后缀既慢又易错)。 */
    private void applySubscriptionMetadata(MovieDetail detail, MediaSubscription subscription) {
        detail.setVod_id(VOD_ID_PREFIX + subscription.getId());
        detail.setVod_name(displayName(subscription));
        detail.setVod_pic(absoluteCover(coverOf(subscription)));
        if (subscription.getDoubanId() != null) {
            detail.setDbid(subscription.getDoubanId());
        }
        String remarks = "";
        MetadataDetails meta = null;
        Movie douban = subscription.getDoubanId() == null ? null
                : movieRepository.findById(subscription.getDoubanId()).orElse(null);
        if (StringUtils.isNotBlank(subscription.getMetaProvider()) && StringUtils.isNotBlank(subscription.getMetaId())) {
            try {
                meta = subscriptionMetadata(subscription);
                if (meta != null) {
                    String year = meta.getYear();
                    if (StringUtils.isBlank(year) && StringUtils.length(meta.getFirstAirDate()) >= 4) {
                        year = meta.getFirstAirDate().substring(0, 4);
                    }
                    if (StringUtils.isNotBlank(year)) {
                        detail.setVod_year(year);
                    }
                    if (meta.getGenres() != null && !meta.getGenres().isEmpty()) {
                        detail.setType_name(String.join(",", meta.getGenres()));
                    }
                    if (meta.getCountries() != null && !meta.getCountries().isEmpty()) {
                        detail.setVod_area(localizeArea(String.join(",", meta.getCountries())));
                    }
                    if (meta.getLanguages() != null && !meta.getLanguages().isEmpty()) {
                        detail.setVod_lang(localizeLang(String.join(",", meta.getLanguages())));
                    }
                    if (meta.getDirectors() != null && !meta.getDirectors().isEmpty()) {
                        detail.setVod_director(String.join(",", meta.getDirectors()));
                    }
                    if (meta.getCast() != null && !meta.getCast().isEmpty()) {
                        detail.setVod_actor(meta.getCast().stream()
                                .map(CastMember::getName).filter(StringUtils::isNotBlank)
                                .collect(Collectors.joining(",")));
                    }
                    // 评分不进 remarks(手机卡片/部分播放器详情 remarks 显示不全),
                    // 紧凑单评分统一由 compactRatingSuffix 收口,全量多源评分挪进正文顶部
                }
            } catch (Exception e) {
                log.debug("load metadata snapshot for subscription {} failed: {}", subscription.getId(), e.getMessage());
            }
        }
        // 简介整体替换为快照 overview(无快照则清空)—— getPlaylist 对非 web 请求预填的"站点:挂载路径"不外泄
        detail.setVod_content(meta == null ? null : meta.getOverview());
        // 本地豆瓣库兜底:快照缺失的字段用 douban_id 对应的 Movie 行补齐(只补空,不覆盖快照值)
        if (douban != null) {
            if (detail.getVod_year() == null && douban.getYear() != null) {
                detail.setVod_year(String.valueOf(douban.getYear()));
            }
            if (StringUtils.isBlank(detail.getType_name()) && StringUtils.isNotBlank(douban.getGenre())) {
                detail.setType_name(douban.getGenre());
            }
            if (StringUtils.isBlank(detail.getVod_area()) && StringUtils.isNotBlank(douban.getCountry())) {
                detail.setVod_area(douban.getCountry());
            }
            if (StringUtils.isBlank(detail.getVod_lang()) && StringUtils.isNotBlank(douban.getLanguage())) {
                detail.setVod_lang(douban.getLanguage());
            }
            if (StringUtils.isBlank(detail.getVod_director()) && StringUtils.isNotBlank(douban.getDirectors())) {
                detail.setVod_director(douban.getDirectors());
            }
            if (StringUtils.isBlank(detail.getVod_actor()) && StringUtils.isNotBlank(douban.getActors())) {
                detail.setVod_actor(douban.getActors());
            }
            if (StringUtils.isBlank(detail.getVod_pic()) && StringUtils.isNotBlank(douban.getCover())) {
                detail.setVod_pic(absoluteCover(douban.getCover()));
            }
            if (StringUtils.isBlank(detail.getVod_content()) && StringUtils.isNotBlank(douban.getDescription())) {
                detail.setVod_content(douban.getDescription());
            }
        }
        remarks += compactRatingSuffix(subscription);
        // 详情 remarks 只放评分(集数进度列表页已有,详情页正文顶部还有全量多源评分行)
        if (meta != null && meta.getRatings() != null && !meta.getRatings().isEmpty()) {
            StringBuilder line = new StringBuilder("评分:");
            for (String source : List.of("douban", "tmdb", "bangumi")) {
                String score = meta.getRatings().get(source);
                if (StringUtils.isNotBlank(score)) {
                    if (line.charAt(line.length() - 1) != ':') {
                        line.append(" / ");
                    }
                    line.append(ratingSourceLabel(source)).append(' ').append(score);
                }
            }
            if (line.length() > 3) {
                detail.setVod_content(line
                        + (StringUtils.isBlank(detail.getVod_content()) ? "" : "\n" + detail.getVod_content()));
            }
        }
        // 下集播出置顶(评分行之上,完结剧无此行评分自然回顶):欧美剧/追番周播,「周六 04:00」一眼对上更新日
        if (hasNextAir(subscription)) {
            String line = "📅 下集播出:" + airTimeText(subscription.getNextAirTime());
            detail.setVod_content(StringUtils.isBlank(detail.getVod_content())
                    ? line : line + "\n" + detail.getVod_content());
        }
        detail.setVod_remarks(remarks);
    }

    /** 有下集播出可展示:nextAirTime 非空且订阅未完结(暂停剧仍在播,播出信息照示)。 */
    private static boolean hasNextAir(MediaSubscription subscription) {
        return subscription.getNextAirTime() != null
                && !MediaSubscription.STATUS_ENDED.equals(subscription.getStatus());
    }

    /** 订阅元数据快照(持久层零网络);无 provider/metaId 或读取失败返回 null。 */
    private MetadataDetails loadSubscriptionSnapshot(MediaSubscription subscription) {
        return subscriptionMetadata(subscription);
    }

    /**
     * 订阅元数据读取(持久层零网络,视图/快照统一口径):TMDB 单季装全剧(totalSeasons==1)
     * 的剧订阅第 N&gt;1 季时回落读第 1 季行 —— 第 N 季行只有剧集级字段,分集标题/播出日程
     * 只在全剧口径的第 1 季行(绝对集号空间)。形态未知(快照缺失/多季)原样返回,与巡检侧
     * effectiveMetaSeason 同语义但不打外网:第 1 季快照由巡检 refreshMetadata 落库。
     */
    private MetadataDetails subscriptionMetadata(MediaSubscription subscription) {
        if (StringUtils.isBlank(subscription.getMetaProvider()) || StringUtils.isBlank(subscription.getMetaId())) {
            return null;
        }
        try {
            MetadataDetails details = metadataService.cachedDetails(
                    subscription.getMetaProvider(), subscription.getMetaId(), subscription.getSeason());
            if (details != null && details.getTotalSeasons() != null && details.getTotalSeasons() == 1
                    && (subscription.getSeason() == null || subscription.getSeason() > 1)) {
                MetadataDetails whole = metadataService.cachedDetails(
                        subscription.getMetaProvider(), subscription.getMetaId(), 1);
                if (whole != null) {
                    return whole;
                }
            }
            return details;
        } catch (Exception e) {
            log.debug("load metadata snapshot for subscription {} failed: {}",
                    subscription.getId(), e.getMessage());
            return null;
        }
    }

    /** 紧凑单评分(裸文本,无分隔符;列表拼在进度后,详情单独作 remarks):
     * 豆瓣(快照 Map → 本地豆瓣库)优先,回落 TMDB → Bangumi → 旧快照单值 rating。 */
    private String compactRatingSuffix(MediaSubscription subscription) {
        MetadataDetails meta = loadSubscriptionSnapshot(subscription);
        Map<String, String> ratings = meta == null ? null : meta.getRatings();
        String doubanScore = ratings == null ? null : ratings.get("douban");
        if (StringUtils.isBlank(doubanScore) && subscription.getDoubanId() != null) {
            Movie douban = movieRepository.findById(subscription.getDoubanId()).orElse(null);
            doubanScore = douban == null ? null : douban.getDbScore();
        }
        if (StringUtils.isNotBlank(doubanScore)) {
            return "豆瓣 " + doubanScore;
        }
        if (ratings != null) {
            for (String source : List.of("tmdb", "bangumi")) {
                String score = ratings.get(source);
                if (StringUtils.isNotBlank(score)) {
                    return ratingSourceLabel(source) + " " + score;
                }
            }
        }
        if (meta != null && StringUtils.isNotBlank(meta.getRating())) {
            return ratingSourceLabel(subscription.getMetaProvider()) + " " + meta.getRating();
        }
        return "";
    }

    /** 评分来源标签:tmdb→TMDB,bangumi→Bangumi(首字母大写),douban→豆瓣。 */
    private static String ratingSourceLabel(String metaProvider) {
        if ("douban".equalsIgnoreCase(metaProvider)) {
            return "豆瓣";
        }
        if ("tmdb".equalsIgnoreCase(metaProvider)) {
            return "TMDB";
        }
        return metaProvider.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                + metaProvider.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 快照里的国家/语言代码转中文展示(TMDB 存 ISO 码:CN/zh 等)。 */
    private static String localizeArea(String code) {
        return "CN".equalsIgnoreCase(code) ? "中国" : code;
    }

    private static String localizeLang(String code) {
        return "zh".equalsIgnoreCase(code) || "zh-CN".equalsIgnoreCase(code) ? "中文" : code;
    }

    /** 链接解析专用客户端(跟随重定向;String 收包 + UTF-8 默认字符集防中文乱码) */
    private final org.springframework.web.client.RestTemplate linkRestTemplate = buildLinkRestTemplate();

    private static org.springframework.web.client.RestTemplate buildLinkRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(15_000);
        org.springframework.web.client.RestTemplate template =
                new org.springframework.web.client.RestTemplate(factory);
        template.getMessageConverters().forEach(converter -> {
            if (converter instanceof org.springframework.http.converter.StringHttpMessageConverter stringConverter) {
                stringConverter.setDefaultCharset(java.nio.charset.StandardCharsets.UTF_8);
            }
        });
        return template;
    }

    /** 元数据链接解析允许服务端发起请求的平台官方域:白名单域外/非 https 一律不发请求,防借道探测内网(SSRF)。 */
    private static final java.util.Set<String> META_LINK_DOMAINS = java.util.Set.of(
            "b23.tv", "bilibili.com", "youku.com", "iqiyi.com");

    /** https 且 host 为白名单域或其子域;解析失败按不通过处理(调用方不会发起请求)。 */
    static boolean isAllowedMetaLinkUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            String lower = host.toLowerCase(java.util.Locale.ROOT);
            return META_LINK_DOMAINS.stream().anyMatch(d -> lower.equals(d) || lower.endsWith("." + d));
        } catch (Exception e) {
            return false;
        }
    }

    /** b23.tv 短链精确判定:host 精确等于 b23.tv/www.b23.tv。旧 contains("b23.tv") 会被
     *  http://127.0.0.1/b23.tv 之类借道,让短链展开先打到内网地址。 */
    static boolean isShortLink(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            return host != null && ("b23.tv".equalsIgnoreCase(host) || "www.b23.tv".equalsIgnoreCase(host));
        } catch (Exception e) {
            return false;
        }
    }

    /** b23.tv 等短链展开:JDK 连接手动读 Location 逐跳跟随(≤5 跳);入口与每一跳都过白名单,跳向其它域不再请求。 */
    private String expandShortLink(String url) {
        if (!isShortLink(url)) {
            return url;
        }
        String current = url;
        for (int i = 0; i < 5; i++) {
            if (!isAllowedMetaLinkUrl(current)) {
                return current;
            }
            try {
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) java.net.URI.create(current).toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.setRequestProperty(org.springframework.http.HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0 Safari/537.36");
                int code = connection.getResponseCode();
                String location = connection.getHeaderField(org.springframework.http.HttpHeaders.LOCATION);
                connection.disconnect();
                if (code >= 300 && code < 400 && StringUtils.isNotBlank(location)) {
                    current = location;
                    continue;
                }
                return current;
            } catch (Exception e) {
                log.debug("expand short link failed: {} {}", current, e.getMessage());
                return current;
            }
        }
        return current;
    }

    private static org.springframework.http.HttpHeaders browserHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set(org.springframework.http.HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        headers.set(org.springframework.http.HttpHeaders.ACCEPT, "text/html,application/json");
        return headers;
    }

    /** 页面服务端 <title> 提取与清洗:取首段、去"第N集…"尾巴、压缩空白。B站页面带用户 cookie 提升成功率。
     *  链接来自用户输入而平台正则不锚定 host(https://内网地址/#youku.com/... 也能命中),请求前必须过白名单。 */
    private String fetchPageTitle(String url) {
        if (!isAllowedMetaLinkUrl(url)) {
            throw new BadRequestException("页面解析仅支持 B站/优酷/爱奇艺官方 https 链接");
        }
        try {
            org.springframework.http.HttpHeaders headers = browserHeaders();
            if (url.contains("bilibili.com")) {
                String cookie = settingRepository.findById(cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE)
                        .map(s -> s.getValue()).orElse("");
                if (StringUtils.isNotBlank(cookie)) {
                    headers.set(org.springframework.http.HttpHeaders.COOKIE, cookie);
                }
            }
            org.springframework.http.ResponseEntity<String> response = linkRestTemplate.exchange(
                    java.net.URI.create(url), org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(null, headers), String.class);
            String body = response.getBody();
            if (StringUtils.isBlank(body)) {
                return null;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("<title>(.*?)</title>", java.util.regex.Pattern.DOTALL).matcher(body);
            if (!matcher.find()) {
                return null;
            }
            String title = matcher.group(1).trim()
                    .replace("&amp;", "&").replace("&nbsp;", " ").replace("&quot;", "\"");
            title = title.split("[_|·]")[0];
            title = title.split("-")[0].trim();
            title = title.replaceAll("(?:第?\\s*\\d+\\s*[集话話].*)$", "").trim();
            title = title.replaceAll("\\s+", " ");
            return StringUtils.isBlank(title) ? null : title;
        } catch (Exception e) {
            log.debug("fetch page title failed: {} {}", url, e.getMessage());
            return null;
        }
    }

    /** 页面取剧名后绑定:优先 Bangumi(番剧)或豆瓣(剧集),都搜不到回落官方平台按名。 */
    private Map<String, Object> resolveByPageTitle(String url, boolean preferBangumi) {
        String title = fetchPageTitle(url);
        if (StringUtils.isBlank(title)) {
            throw new BadRequestException("无法从链接解析剧名(页面为前端渲染),请改用关键词搜索或元数据链接绑定");
        }
        Map<String, Object> result = bindByTitle(title, preferBangumi);
        result.put("name", title);
        return result;
    }

    /** 按剧名绑定元数据条目。 */
    private Map<String, Object> bindByTitle(String title, boolean preferBangumi) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        String first = preferBangumi ? "bangumi" : "douban";
        String second = preferBangumi ? "douban" : "bangumi";
        for (String provider : List.of(first, second)) {
            try {
                List<MetadataSearchItem> items = metadataService.searchReport(provider, title).items();
                if (!items.isEmpty()) {
                    MetadataSearchItem hit = items.get(0);
                    result.put("provider", hit.getProvider());
                    result.put("id", hit.getId());
                    if ("douban".equals(hit.getProvider())) {
                        result.put("doubanId", Integer.parseInt(hit.getId()));
                    }
                    return result;
                }
            } catch (Exception e) {
                log.debug("bind by title via {} failed: {}", provider, e.getMessage());
            }
        }
        // 未匹配条目:回落官方平台按名(腾讯/优酷/爱奇艺集数兜底)
        result.put("provider", "official");
        result.put("id", StringUtils.abbreviate(title, 64)); // meta_id 列 VARCHAR(64),剧名无界
        return result;
    }

    /** meta_id 列 VARCHAR(64):douban/tmdb/bangumi 均为短数字 id,official 源的 id 是剧名(外部字符串无界)。 */
    private static String abbreviateMetaId(String metaId) {
        return StringUtils.abbreviate(StringUtils.defaultIfBlank(metaId, null), 64);
    }

    /** B站 PGC season API(存在 bilibili_cookie 时优先携带,游客直连会被风控 -404)。 */
    private Map<String, Object> fetchBilibiliSeason(String queryParam) {
        String cookie = settingRepository.findById(cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE)
                .map(s -> s.getValue()).orElse("");
        try {
            org.springframework.http.HttpHeaders headers = browserHeaders();
            headers.set(org.springframework.http.HttpHeaders.REFERER, "https://www.bilibili.com/");
            if (StringUtils.isNotBlank(cookie)) {
                headers.set(org.springframework.http.HttpHeaders.COOKIE, cookie);
            }
            org.springframework.http.ResponseEntity<String> response = linkRestTemplate.exchange(
                    java.net.URI.create("https://api.bilibili.com/pgc/view/web/season?" + queryParam),
                    org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(null, headers), String.class);
            JsonNode root = StringUtils.isBlank(response.getBody()) ? null : objectMapper.readTree(response.getBody());
            if (root == null || root.path("code").asInt(-1) != 0) {
                return null;
            }
            JsonNode result = root.path("result");
            String title = result.path("title").asText("");
            if (StringUtils.isBlank(title)) {
                return null;
            }
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("title", title);
            int total = result.path("total").asInt(0);
            if (total > 0) {
                data.put("total", total);
            }
            return data;
        } catch (Exception e) {
            log.debug("bilibili season api failed: {}", e.getMessage());
            return null;
        }
    }

    /** 播出时间轴:昨天 → 未来 8 天,每天更新的订阅与媒体播出时间。日程来自 provider 分集日期快照,窗口外退化为 nextAirTime。 */
    public List<Map<String, Object>> schedule(int uid) {
        java.time.ZoneId zone = java.time.ZoneId.of(Constants.ZONE_ID);
        java.time.LocalDate startDate = java.time.LocalDate.now(zone).minusDays(1);
        List<Map<String, Object>> days = new ArrayList<>();
        List<List<Map<String, Object>>> dayItems = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            java.time.LocalDate date = startDate.plusDays(i);
            String label = switch (i) {
                case 0 -> "昨天";
                case 1 -> "今天";
                case 2 -> "明天";
                default -> WEEKDAYS[date.getDayOfWeek().getValue() - 1];
            };
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("label", label);
            day.put("date", date.getMonthValue() + "/" + date.getDayOfMonth());
            day.put("today", i == 1);
            days.add(day);
            dayItems.add(new ArrayList<>());
        }
        long windowStart = startDate.atStartOfDay(zone).toInstant().toEpochMilli();
        long windowEnd = startDate.plusDays(9).atStartOfDay(zone).toInstant().toEpochMilli();
        for (MediaSubscription subscription : subscriptionRepository.findByUidOrderByCreatedTimeDesc(uid)) {
            if (MediaSubscription.STATUS_ENDED.equals(subscription.getStatus())) {
                continue;
            }
            List<cn.har01d.alist_tvbox.dto.EpisodeAirDate> entries = new ArrayList<>();
            try {
                if (StringUtils.isNotBlank(subscription.getSchedule())) {
                    entries = objectMapper.readValue(subscription.getSchedule(),
                            new TypeReference<List<cn.har01d.alist_tvbox.dto.EpisodeAirDate>>() {
                            });
                }
            } catch (Exception e) {
                log.debug("parse schedule failed: {}", e.getMessage());
            }
            if (entries.isEmpty() && subscription.getNextAirTime() != null
                    && subscription.getNextAirTime() >= windowStart && subscription.getNextAirTime() < windowEnd) {
                entries = List.of(new cn.har01d.alist_tvbox.dto.EpisodeAirDate(0, subscription.getNextAirTime()));
            }
            boolean paused = MediaSubscription.STATUS_PAUSED.equals(subscription.getStatus());
            for (var entry : entries) {
                if (entry.getAirTime() < windowStart || entry.getAirTime() >= windowEnd) {
                    continue;
                }
                int index = (int) java.time.Duration.between(
                        java.time.Instant.ofEpochMilli(windowStart),
                        java.time.Instant.ofEpochMilli(entry.getAirTime())).toDays();
                if (index < 0 || index >= dayItems.size()) {
                    continue;
                }
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("subscriptionId", subscription.getId());
                item.put("name", displayName(subscription));
                item.put("episode", entry.getEpisode());
                item.put("airTime", entry.getAirTime());
                item.put("paused", paused);
                dayItems.get(index).add(item);
            }
        }
        for (int i = 0; i < days.size(); i++) {
            days.get(i).put("items", mergeDayItems(dayItems.get(i)));
        }
        return days;
    }

    /**
     * 同订阅同日同时段的多集合并为一行(20:00 重器 第29-33集):囤剧平台常一天放出整周排播,
     * 逐集一行会把时间轴挤爆。集数压缩为区间(连续 29-33)/逗号(离散 10,12);单集/无集数保持原样。
     */
    static List<Map<String, Object>> mergeDayItems(List<Map<String, Object>> items) {
        java.time.ZoneId zone = java.time.ZoneId.of(Constants.ZONE_ID);
        Map<String, Map<String, Object>> groups = new java.util.LinkedHashMap<>();
        Map<String, List<Integer>> numbers = new java.util.LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String clock = java.time.Instant.ofEpochMilli((long) item.get("airTime"))
                    .atZone(zone).toLocalTime().withSecond(0).toString();
            String key = item.get("subscriptionId") + "@" + clock;
            int episode = (int) item.getOrDefault("episode", 0);
            if (episode > 0) {
                numbers.computeIfAbsent(key, k -> new ArrayList<>()).add(episode);
            }
            Map<String, Object> group = groups.get(key);
            if (group == null) {
                groups.put(key, new java.util.LinkedHashMap<>(item));
            } else if (group.get("airTime") instanceof Long first && (long) item.get("airTime") < first) {
                group.put("airTime", item.get("airTime")); // 同段取最早时间
            }
        }
        List<Map<String, Object>> merged = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            Map<String, Object> item = entry.getValue();
            List<Integer> list = numbers.getOrDefault(entry.getKey(), List.of());
            item.put("episodes", list.isEmpty() ? null
                    : list.size() == 1 ? String.valueOf(list.get(0)) : compactEpisodes(list));
            merged.add(item);
        }
        return merged;
    }

    /** 排序后按连续段压缩:29..33 → "29-33";混杂 → "10,12-14,20"。 */
    static String compactEpisodes(List<Integer> numbers) {
        List<Integer> sorted = new ArrayList<>(numbers);
        java.util.Collections.sort(sorted);
        List<String> parts = new ArrayList<>();
        int start = sorted.get(0);
        int prev = start;
        for (int i = 1; i <= sorted.size(); i++) {
            int current = i < sorted.size() ? sorted.get(i) : Integer.MIN_VALUE;
            if (current != prev + 1) {
                parts.add(start == prev ? String.valueOf(start) : start + "-" + prev);
                start = current;
            }
            prev = current;
        }
        return String.join(",", parts);
    }

    /** 粘贴链接解析:豆瓣 subject / TMDB tv(含 season) / Bangumi subject / 腾讯 cover /
     *  B站番剧播放页(ss/ep)/ 优酷 / 爱奇艺剧集页 → 元数据绑定信息。 */
    public Map<String, Object> resolveMetaLink(String url) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (StringUtils.isBlank(url)) {
            throw new BadRequestException("链接不能为空");
        }
        String link = expandShortLink(url.trim());
        java.util.regex.Matcher matcher;
        if ((matcher = java.util.regex.Pattern.compile("douban\\.com/subject/(\\d+)").matcher(link)).find()) {
            result.put("provider", "douban");
            result.put("id", matcher.group(1));
            result.put("doubanId", Integer.parseInt(matcher.group(1)));
        } else if ((matcher = java.util.regex.Pattern.compile("themoviedb\\.org/(?:tv|movie)/(\\d+)").matcher(link)).find()) {
            result.put("provider", "tmdb");
            result.put("id", matcher.group(1));
            java.util.regex.Matcher season = java.util.regex.Pattern.compile("/season/(\\d+)").matcher(link);
            if (season.find()) {
                result.put("season", Integer.parseInt(season.group(1)));
            }
        } else if ((matcher = java.util.regex.Pattern.compile("(?:bgm\\.tv|bangumi\\.tv|chii\\.in)/subject/(\\d+)").matcher(link)).find()) {
            result.put("provider", "bangumi");
            result.put("id", matcher.group(1));
        } else if ((matcher = java.util.regex.Pattern.compile("v\\.qq\\.com/x/cover/([A-Za-z0-9]+)").matcher(link)).find()) {
            result.put("provider", "official");
            // 规范化 canonical 形式:原链接可能带 ?vid= 等 query,超出 meta_id 列宽(VARCHAR 64)
            result.put("id", "https://v.qq.com/x/cover/" + matcher.group(1) + ".html");
        } else if ((matcher = java.util.regex.Pattern.compile("bilibili\\.com/bangumi/play/(ss|ep)(\\d+)").matcher(link)).find()
                || (matcher = java.util.regex.Pattern.compile("bilibili\\.com/bangumi/media/md(\\d+)").matcher(link)).find()) {
            // B站链接:优先 PGC season API(带用户 cookie 过风控,拿准确剧名+总集数,md 链接也支持);
            // 失败回落播放页服务端 title(media 页为前端渲染,会明确报错)
            String queryParam = matcher.groupCount() == 2 && matcher.group(1) != null && !matcher.group(1).equals("md")
                    ? ("ss".equals(matcher.group(1)) ? "season_id=" : "ep_id=") + matcher.group(2)
                    : "media_id=" + matcher.group(1);
            Map<String, Object> season = fetchBilibiliSeason(queryParam);
            if (season != null) {
                String title = (String) season.get("title");
                Map<String, Object> bound = bindByTitle(title, true);
                bound.put("name", title);
                if (season.get("total") instanceof Number number && number.intValue() > 0) {
                    bound.put("totalEpisodes", number.intValue());
                }
                return bound;
            }
            if (link.contains("/bangumi/play/")) {
                return resolveByPageTitle(link, true);
            }
            throw new BadRequestException("B 站 media 页为前端渲染且 season API 不可用(检查 bilibili_cookie),请改用播放页链接或关键词绑定");
        } else if ((matcher = java.util.regex.Pattern.compile("(?:v\\.youku\\.com/v_show/id_|youku\\.com/show/id_|youku\\.com/.*/id_)[A-Za-z0-9=]+").matcher(link)).find()) {
            // 优酷页面有服务端 title;国产剧优先豆瓣绑定
            return resolveByPageTitle(link, false);
        } else if ((matcher = java.util.regex.Pattern.compile("iqiyi\\.com/[av]_[A-Za-z0-9]+\\.html").matcher(link)).find()) {
            // 爱奇艺:剧集页(a_)通常有服务端 title,单集页(v_)不一定,失败会给明确提示
            return resolveByPageTitle(link, false);
        } else if (link.contains("bilibili.com/bangumi/media/")) {
            throw new BadRequestException("B 站 media 页为前端渲染无法解析,请改用播放页链接(含 ss/ep 的)");
        } else {            throw new BadRequestException("无法识别的链接,支持:豆瓣 subject / TMDB tv / Bangumi subject / 腾讯视频 cover 链接");
        }
        // 尽力解析剧名(失败不阻断,用户可手填)
        try {
            MetadataDetails details = metadataService.details((String) result.get("provider"), (String) result.get("id"),
                    result.get("season") instanceof Number number ? number.intValue() : null);
            if (details != null && StringUtils.isNotBlank(details.getName())) {
                result.put("name", details.getName());
            }
            if (details != null && details.getTotalEpisodes() != null && details.getTotalEpisodes() > 0) {
                result.put("totalEpisodes", details.getTotalEpisodes());
            }
        } catch (Exception e) {
            log.debug("resolve link name failed: {}", e.getMessage());
        }
        return result;
    }

    /** 元数据条目搜索(web 端):封面经后端 /images 代理(TMDB/Bangumi 图床直连可能被墙/防盗链),并附带各源失败原因。 */
    public Map<String, Object> metaSearch(String provider, String keyword) {
        var result = metadataService.searchReport(provider, keyword);
        result.items().forEach(item -> item.setCover(proxiedCover(item.getCover())));
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("items", result.items());
        response.put("errors", result.errors());
        return response;
    }

    /** web 端展示用封面走后端代理;/images 为 GET permitAll,浏览器直链可用。TVBox 端保持绝对地址。 */
    public String proxiedCover(String cover) {
        if (StringUtils.isBlank(cover) || !cover.startsWith("http")) {
            return cover;
        }
        return "/images?url=" + java.net.URLEncoder.encode(cover, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 存量元数据快照的 TMDB 背景图(或上一版已升的 original)统一改写 w1280:预生成尺寸文件小、加载快,零网络免刷新。 */
    static String upgradeBackdropUrl(String url) {
        if (url == null) {
            return null;
        }
        return url.replace("/t/p/w780/", "/t/p/w1280/").replace("/t/p/original/", "/t/p/w1280/");
    }

    /** 背景图轮播候选(去重、升级高清、走代理):backdrops 为主,主图兜底,单图订阅也得到单元素列表。 */
    private List<String> proxiedBackdrops(MetadataDetails details) {
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        if (details.getBackdrops() != null) {
            details.getBackdrops().stream().filter(StringUtils::isNotBlank).forEach(urls::add);
        }
        if (StringUtils.isNotBlank(details.getBackdrop())) {
            urls.add(details.getBackdrop());
        }
        return urls.stream().map(MediaSubscriptionService::upgradeBackdropUrl)
                .filter(StringUtils::isNotBlank).map(this::proxiedCover).toList();
    }

    /**
     * 播放逻辑集 msubep-{订阅}-{集}:实时选源并回退(转存>VERIFIED>LISTED),用户无感知。
     * 资源侧候选走集源行索引直查(rel_path + mount_path 一次取链),不再逐挂载点递归列目录 ——
     * 旧实现每次播放要对每个挂载点做最多 maxListDepth 层列举;索引后是 1 次库查 + 1 次取链。
     */
    public Map<String, Object> playEpisode(int uid, int subscriptionId, int episode, String client, String type) {
        MediaSubscription subscription = getOwned(uid, subscriptionId);
        boolean getSub = wantsSubtitles(subscription);
        // 候选清单:转存各账号盘(实时列目录,自有盘无集源行) → 集源行索引(VERIFIED 优先、资源分序)
        List<MediaSubscriptionCheckService.EpisodeFile> transferFiles = new ArrayList<>();
        if (MediaSubscription.MODE_TRANSFER.equals(subscription.getMode()) && !parseAccountIds(subscription).isEmpty()) {
            for (var target : transferService.transferredTargets(uid, subscriptionId)) {
                var file = checkService.episodeFilesAt(target.path(), subscription).get(episode);
                if (file != null) {
                    transferFiles.add(file);
                }
            }
        }
        List<MediaSubscriptionCheckService.PlayCandidate> rows = checkService.playCandidates(subscription, episode);
        List<String> errors = new ArrayList<>();
        int attempted = 0;
        for (MediaSubscriptionCheckService.EpisodeFile file : transferFiles) {
            attempted++;
            try {
                Map<String, Object> result = tvBoxService.getPlayUrl(1, file.dir() + "/" + file.name(), getSub, client, type);
                kickPreheatAhead(uid, subscriptionId, episode);
                kickCollectionFallback(uid, subscriptionId, episode);
                return result;
            } catch (Exception e) {
                log.info("subscription {} episode {} via {} failed: {}", subscriptionId, episode, file.dir(), e.getMessage());
                errors.add(file.dir() + ": " + e.getMessage());
            }
        }
        for (MediaSubscriptionCheckService.PlayCandidate candidate : rows) {
            if (!MediaSubscriptionResource.STATE_MOUNTED.equals(candidate.resource().getState())) {
                continue; // 前一个候选的失败传染已把整源判死(同对象就地变更)
            }
            attempted++;
            String path = candidate.resource().getMountPath() + "/" + candidate.source().getRelPath();
            try {
                Map<String, Object> result = tvBoxService.getPlayUrl(1, path, getSub, client, type);
                checkService.recordPlaySuccess(candidate.source());
                kickPreheatAhead(uid, subscriptionId, episode);
                kickCollectionFallback(uid, subscriptionId, episode);
                return result;
            } catch (Exception e) {
                log.info("subscription {} episode {} via {} failed: {}", subscriptionId, episode, path, e.getMessage());
                errors.add(path + ": " + e.getMessage());
                checkService.recordPlayFailure(subscription, candidate);
            }
        }
        // 采集源兜底(播放链路最后一级):候选全灭(含零候选缺集 —— attempted 不涨)才介入,
        // 开关关闭时零调用;补集只落覆盖层不改写本订阅数据,失败继续走既有巡检补救路径
        try {
            Map<String, Object> rescued = episodeFallbackService.resolveEpisodeFallback(subscription, episode, client, type);
            if (rescued != null) {
                kickCollectionFallback(uid, subscriptionId, episode);
                kickPreheatAhead(uid, subscriptionId, episode);
                return rescued;
            }
        } catch (Exception e) {
            log.debug("collection fallback for subscription {} episode {} failed: {}",
                    subscriptionId, episode, e.getMessage());
        }
        // 全部候选都播不了:播放期是信噪比最高的失效信号,不能只记个失败就完事——
        // 立刻异步补救(先查池换源,池空才搜索),否则用户重试多少次都是同一个死源。
        if (attempted > 0) {
            try {
                // ENDED 订阅的 check() 会轻查短路(不换源不搜索),先打播放失败标越过短路跑完整巡检,
                // 否则完结剧没看完、分享失效后用户重试多少次都是同一个死源
                checkService.markPlaybackFailure(subscriptionId);
                checkService.checkAsync(uid, subscriptionId);
            } catch (Exception e) {
                log.warn("trigger recovery for subscription {} failed: {}", subscriptionId, e.getMessage());
            }
        }
        throw new BadRequestException("第 " + episode + " 集暂无可用播放源(已尝试 " + attempted + " 个源"
                + (errors.isEmpty() ? "" : ";" + String.join("; ", errors)) + ")");
    }

    /** 播放成功后顺带触发前瞻验证(后台探测接下来几集的最优源,提前发现死集):fire-and-forget,绝不能影响播放返回。 */
    private void kickPreheatAhead(int uid, int subscriptionId, int episode) {
        try {
            checkService.preheatAheadAsync(uid, subscriptionId, episode);
        } catch (Exception e) {
            log.debug("trigger preheat ahead for subscription {} failed: {}", subscriptionId, e.getMessage());
        }
    }

    /** 播放成功后后台补齐「当前集+后3集」缺口的采集源覆盖层(类注释口径:当前集正常起播时补齐不阻塞)。
     *  窗口无缺口时零开销(一次集号查询),开关/负缓存/10min 限频在 EpisodeFallbackService 内部自查。 */
    private void kickCollectionFallback(int uid, int subscriptionId, int episode) {
        try {
            episodeFallbackService.fillWindowAsync(uid, subscriptionId, episode);
        } catch (Exception e) {
            log.debug("trigger collection fallback fill for subscription {} failed: {}", subscriptionId, e.getMessage());
        }
    }

    /** 播放期是否查找外挂字幕:字幕查找(getSubtitle)要列一次文件所在目录,而网盘分享的外挂字幕
     * 几乎只出现在非华语资源(国产剧内嵌)——元数据地区明确非中国(番剧/欧美剧)才查,
     * 中国/港台/未绑元数据/无地区数据都不查,省掉播放链路里唯一一次目录列举。 */
    boolean wantsSubtitles(MediaSubscription subscription) {
        if (StringUtils.isBlank(subscription.getMetaProvider()) || StringUtils.isBlank(subscription.getMetaId())) {
            return false;
        }
        try {
            MetadataDetails details = subscriptionMetadata(subscription);
            List<String> countries = details == null ? null : details.getCountries();
            if (countries == null || countries.isEmpty()) {
                return false;
            }
            return countries.stream().noneMatch(MediaSubscriptionService::isChineseMarket);
        } catch (Exception e) {
            log.debug("load countries for subtitle gate of subscription {} failed: {}",
                    subscription.getId(), e.getMessage());
            return false;
        }
    }

    /** 地区是否华语市场:TMDB 用 ISO 码(CN/TW/HK),豆瓣用中文(中国大陆/中国香港…),港台分享同样极少外挂字幕。 */
    static boolean isChineseMarket(String country) {
        if (StringUtils.isBlank(country)) {
            return false;
        }
        String normalized = country.trim();
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        return "cn".equals(lower) || "tw".equals(lower) || "hk".equals(lower)
                || normalized.contains("中国") || "china".equals(lower)
                || lower.startsWith("hong kong") || lower.startsWith("taiwan");
    }

    /** 多源合并播放(§4.5,需求 1):按集号合并,优先级 转存副本(自有盘)> 主源 > 补缺源,排序成单一播放列表。
     * 支持逐集异源:已转存的集走自有盘(如夸克盘),未转存的集继续走原分享(如百度分享)。 */
    private void mergeGapPlaylists(MediaSubscription subscription, MovieList result, String ac) {
        if (result == null || result.getList().isEmpty()) {
            return;
        }
        MovieDetail detail = result.getList().get(0);
        // 主源条目(编号归一口径与巡检一致:资源级起始集号 > 订阅级季起始集号)
        MediaSubscriptionResource primaryResource = checkService.primaryResource(subscription);
        TreeMap<Integer, String> primary = new TreeMap<>();
        Integer primaryStart = effectiveStartEpisode(primaryResource, subscription);
        Integer primaryCollectSeason = checkService.collectSeason(subscription, primaryResource);
        if (!parsePlayEntries(detail.getVod_play_url(), primaryCollectSeason, primaryStart, primary)) {
            return; // 主源列表解析失败不动原始输出
        }
        List<MediaSubscriptionResource> gaps = checkService.auxMounts(subscription);
        boolean transferMode = MediaSubscription.MODE_TRANSFER.equals(subscription.getMode())
                && !parseAccountIds(subscription).isEmpty();
        boolean tvboxRequest = StringUtils.isBlank(ac);
        if (!tvboxRequest && gaps.isEmpty() && !transferMode) {
            return;
        }
        TreeMap<Integer, String> merged = new TreeMap<>();
        // 按网盘分线的各盘集清单(TVBox 备用线路:同盘内 转存>主源>补缺 按集合并),插入序即线路顺序
        Map<String, TreeMap<Integer, String>> driveLines = new LinkedHashMap<>();
        // 1) 转存副本(自有盘,按目标顺序):已转存的集优先从自有盘播
        if (transferMode) {
            for (var target : transferService.transferredTargets(subscription.getUid(), subscription.getId())) {
                mergePlaylistFrom(subscription, target.path(), ac, subscription.getSeason(),
                        subscription.getSeasonStartEpisode(), merged, driveLine(driveLines, target.drive()));
            }
        }
        // 2) 主源
        String primaryDrive = primaryResource != null && primaryResource.getType() != null
                ? DriveId.toDrive(primaryResource.getType()) : null;
        TreeMap<Integer, String> primaryLine = driveLine(driveLines, primaryDrive);
        primary.forEach((episode, entry) -> {
            merged.putIfAbsent(episode, entry);
            primaryLine.putIfAbsent(episode, entry);
        });
        // 3) 补缺源
        for (MediaSubscriptionResource gap : gaps) {
            mergePlaylistFrom(subscription, gap.getMountPath(), ac, checkService.collectSeason(subscription, gap),
                    effectiveStartEpisode(gap, subscription), merged,
                    driveLine(driveLines, gap.getType() == null ? null : DriveId.toDrive(gap.getType())));
        }
        if (tvboxRequest) {
            // TVBox/spider 请求:首条线路每集重写为逻辑链接 msubep-{subId}-{集},播放时实时选源并逐源回退,
            // 换源/补缺/转存切换不影响续看进度(历史绑定逻辑 id 而非物理地址);
            // 其余线路按网盘分线(百度/夸克/115…,同盘聚合所有源)——主网盘线路固定居前(完整覆盖由巡检保障),
            // 其它盘线路非空即上(挂载由 ensureDriveLines 保障,集数可不全:115 每集一链的线路就是该盘可用集清单)。
            // 逻辑线路失败或想固定某个盘时手动切换。
            rewriteEpisodeTitles(subscription, merged, driveLines);
            String[] lines = buildTvBoxPlayLines(subscription.getId(), merged, driveLines,
                    Set.copyOf(checkService.mainDrives(subscription)));
            appendActionLine(lines, subscription.getId());
            detail.setVod_play_from(lines[0]);
            detail.setVod_play_url(lines[1]);
            kickDriveLines(subscription, driveLines.keySet());
        } else if (transferMode || merged.size() != primary.size()) {
            // web 请求保留真实地址,与挂载目录播放一致
            detail.setVod_play_from("追更");
            detail.setVod_play_url(String.join("#", merged.values()));
        }
    }

    /** 线路未齐(池里还有未出线网盘的候选)时异步补挂,限频在 CheckService;下次详情刷新即可见新线路。 */
    private void kickDriveLines(MediaSubscription subscription, Set<String> linedDrives) {
        try {
            if (checkService.hasUnlinedDriveCandidates(subscription, linedDrives)) {
                checkService.ensureDriveLinesAsync(subscription.getUid(), subscription.getId());
            }
        } catch (Exception e) {
            log.debug("kick drive lines failed: {}", e.getMessage());
        }
    }

    /** 盘线路懒建;盘类型未知(旧数据/未识别分享)返回丢弃容器——集仍并入合并线路,只是不单独出线。 */
    private TreeMap<Integer, String> driveLine(Map<String, TreeMap<Integer, String>> driveLines, String drive) {
        if (StringUtils.isBlank(drive)) {
            return new TreeMap<>();
        }
        return driveLines.computeIfAbsent(drive, key -> new TreeMap<>());
    }

    /** TVBox 逻辑播放列表:集号 → `title$msubep-{subId}-{集}`(title 取自原条目,无 '$' 时退化为"第N集")。
     * 分隔符用 '-':冒号在部分播放器/代理链路会被当 URL scheme 截断。 */
    static String buildMsubepPlaylist(int subscriptionId, TreeMap<Integer, String> merged) {
        StringBuilder playUrl = new StringBuilder();
        for (var entry : merged.entrySet()) {
            if (!playUrl.isEmpty()) {
                playUrl.append('#');
            }
            String source = entry.getValue();
            int index = source.lastIndexOf('$');
            String episodeTitle = index > 0 ? source.substring(0, index) : ("第" + entry.getKey() + "集");
            playUrl.append(episodeTitle).append("$msubep-").append(subscriptionId).append('-').append(entry.getKey());
        }
        return playUrl.toString();
    }

    /** TVBox 多线路装配:首条「我的追剧」为 msubep 逻辑线路(默认,续看绑定逻辑 id),
     * 其余每个网盘一条线路(同盘聚合 转存>主源>补缺 的全部集),主网盘线路固定居前,
     * 其它盘线路非空即上、按集数降序 —— 单集源盘(115 每集一链)线路即该盘可用集清单,
     * 合并线路仍是完整权威清单。返回 [vod_play_from, vod_play_url]。 */
    static String[] buildTvBoxPlayLines(int subscriptionId, TreeMap<Integer, String> merged,
                                        Map<String, TreeMap<Integer, String>> driveLines, Set<String> mainDrives) {
        List<Map.Entry<String, TreeMap<Integer, String>>> ordered = new ArrayList<>();
        for (var line : driveLines.entrySet()) {
            if (!line.getValue().isEmpty()) {
                ordered.add(line);
            }
        }
        ordered.sort(Comparator
                .comparing((Map.Entry<String, TreeMap<Integer, String>> line) -> mainDrives.contains(line.getKey()) ? 0 : 1)
                .thenComparing(line -> -line.getValue().size()));
        List<String> from = new ArrayList<>();
        from.add("我的追剧");
        List<String> urls = new ArrayList<>();
        urls.add(buildMsubepPlaylist(subscriptionId, merged));
        for (var line : ordered) {
            from.add(DriveId.displayName(line.getKey()));
            urls.add(String.join("#", line.getValue().values()));
        }
        return new String[]{String.join("$$$", from), String.join("$$$", urls)};
    }

    /** 详情末尾追加「操作」线路:首条「订阅信息」纯占位零副作用 —— 部分内核切线路会自动触发第 1 条,
     * 动作必须让位(与片单首条「媒体信息」同款防御);「检查更新」居次,点按同步轻量检查后 msg 回执;
     * 「立即巡检」居三,点按异步触发完整巡检(搜索/挂载/缺集补全,分钟级),msg 只回执已开始;
     * 「取消追剧」居末,删除订阅与全部挂载记录 —— 播放器端先弹窗确认,确认后才发请求。 */
    private static void appendActionLine(String[] lines, int subscriptionId) {
        lines[0] = lines[0] + "$$$操作";
        lines[1] = lines[1] + "$$$订阅信息$msubstat-" + subscriptionId
                + "#检查更新$msubcheck-" + subscriptionId
                + "#立即巡检$msubinspect-" + subscriptionId
                + "#取消追剧$msubunsub-" + subscriptionId;
    }

    /** TVBox 操作线路「订阅信息」:当前状态/进度文本,零网络纯读库。 */
    public String subscriptionStatusText(int uid, int subId) {
        MediaSubscription subscription = getOwned(uid, subId);
        String text = displayName(subscription) + " · " + buildRemarks(subscription);
        List<Integer> weekdays = List.copyOf(MediaSubscriptionCheckService.parseAirWeekdays(subscription.getAirWeekdays()));
        if (!weekdays.isEmpty()) {
            String joined = weekdays.stream().map(day -> WEEKDAYS[day - 1])
                    .collect(java.util.stream.Collectors.joining("、"));
            text += "\n更新日:" + joined;
        }
        if (hasNextAir(subscription)) {
            text += "\n下集播出:" + airTimeText(subscription.getNextAirTime());
        }
        String missing = missingEpisodesSummary(subscription);
        if (missing != null) {
            text += "\n" + missing;
        }
        return text;
    }

    /** 订阅信息缺集行(纯读库,不刷元数据):官方已播快照 vs 本地可播集(LISTED/VERIFIED)。
     *  官方快照缺失返回 null(不臆测);全部同步返回「已全部同步」;缺集列号,超 8 个收敛为区间。 */
    private String missingEpisodesSummary(MediaSubscription subscription) {
        int official = subscription.getOfficialEpisodes() == null ? 0 : subscription.getOfficialEpisodes();
        int totalCap = subscription.effectiveTotalEpisodes();
        if (totalCap > 0 && official > totalCap) {
            // 已播数不可能超过总集数(手动锁定口径),超出是上游污染 —— 与 computeMissing 夹紧同规
            official = totalCap;
        }
        if (official <= 0) {
            return null;
        }
        Set<Integer> local = new java.util.HashSet<>(episodeSourceRepository
                .findNumbersBySubscriptionAndStatesIn(subscription.getId(), LIVE_EPISODE_STATES));
        List<Integer> missing = new ArrayList<>();
        // 季起始集号下界:分季订阅对齐后季前旧集不在缺口口径(与 computeMissing 同规)
        int lower = subscription.getSeasonStartEpisode() != null && subscription.getSeasonStartEpisode() > 1
                ? subscription.getSeasonStartEpisode() : 1;
        for (int i = lower; i <= Math.min(official, MAX_EPISODE_ROWS); i++) {
            if (!local.contains(i)) {
                missing.add(i);
            }
        }
        if (missing.isEmpty()) {
            return "官方已播至第 " + official + " 集,本地已全部同步";
        }
        String summary = missing.size() <= 8
                ? missing.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("")
                : missing.get(0) + "-" + missing.get(missing.size() - 1) + " 等 " + missing.size() + " 集";
        return "官方已播至第 " + official + " 集,缺第 " + summary + " 集";
    }

    /** TVBox 操作线路「检查更新」:同步轻量检查(刷新元数据→官方已播 vs 本地已有),返回结论文本。 */
    public String checkUpdateText(int uid, int subId) {
        getOwned(uid, subId);
        return checkService.checkUpdateNow(uid, subId);
    }

    /** TVBox 操作线路「立即巡检」:异步触发完整巡检(搜索/挂载/缺集补全,分钟级),回执只报已开始。 */
    public void inspectAsync(int uid, int subId) {
        getOwned(uid, subId);
        checkService.checkAsync(uid, subId);
    }

    /** TVBox 操作线路「取消追剧」:删除订阅及全部挂载(与网页删除同链路),返回回执文本。 */
    public String unsubscribeText(int uid, int subId) {
        MediaSubscription subscription = getOwned(uid, subId);
        String name = displayName(subscription);
        delete(uid, subId);
        return "已取消追剧:" + name;
    }

    /** TVBox 分集标题美化(Setting {@link #SETTING_EPISODE_TITLES},默认关):`NN. 分集标题(大小)` 替换文件名。
     * 逻辑线路与盘线路一并改写;分集标题来自元数据快照(TMDB 桥接产分集,豆瓣纯源无),无标题保留原文件名。 */
    private void rewriteEpisodeTitles(MediaSubscription subscription, TreeMap<Integer, String> merged,
                                      Map<String, TreeMap<Integer, String>> driveLines) {
        if (!episodeTitlesEnabled()) {
            return;
        }
        Map<Integer, String> titles = episodeTitles(subscription);
        rewriteTitles(merged, titles);
        for (TreeMap<Integer, String> line : driveLines.values()) {
            rewriteTitles(line, titles);
        }
    }

    private boolean episodeTitlesEnabled() {
        return settingRepository.findById(SETTING_EPISODE_TITLES)
                .map(setting -> setting.getValue())
                .map(value -> "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()))
                .orElse(false);
    }

    /** 元数据分集标题(集号→标题),读 media_metadata 持久层快照零网络;未绑元数据/无分集返回空表。 */
    private Map<Integer, String> episodeTitles(MediaSubscription subscription) {
        if (StringUtils.isBlank(subscription.getMetaProvider()) || StringUtils.isBlank(subscription.getMetaId())) {
            return Map.of();
        }
        try {
            MetadataDetails details = subscriptionMetadata(subscription);
            if (details == null || details.getEpisodes() == null) {
                return Map.of();
            }
            Map<Integer, String> titles = new LinkedHashMap<>();
            for (cn.har01d.alist_tvbox.dto.EpisodeInfo info : details.getEpisodes()) {
                if (info.getNumber() > 0 && StringUtils.isNotBlank(info.getTitle())) {
                    titles.put(info.getNumber(), info.getTitle());
                }
            }
            return titles;
        } catch (Exception e) {
            log.debug("load episode titles failed: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 每集文件大小不另查:播放列表条目标题尾部本就带 `(796.08 MB)`(TvBoxService 装配 `fixName+"("+byte2size+")"`),
     * 直接从原标题提取,顺便剥掉避免与改写后的标题重复。 */
    private static final java.util.regex.Pattern TRAILING_SIZE =
            java.util.regex.Pattern.compile("\\(([\\d.]+)\\s*(B|KB|MB|GB|TB)\\)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 就地改写条目标题(`title$id` → `新title$id`),URL 部分不动。 */
    static void rewriteTitles(TreeMap<Integer, String> entries, Map<Integer, String> titles) {
        entries.replaceAll((episode, entry) -> {
            int index = entry.lastIndexOf('$');
            if (index <= 0) {
                return entry;
            }
            return episodeDisplayTitle(episode, titles.get(episode), entry.substring(0, index))
                    + entry.substring(index);
        });
    }

    /** 分集显示标题:`NN. 标题(大小)`(两位补零,百集以上不补);大小从原条目标题尾部的 `(796.08 MB)` 提取,
     * 无大小省略括号;元数据无标题用剥掉大小后的原文件名,再无则"第N集"。
     * 标题先洗掉 $/#/$$$ —— 它们是播放列表分隔符,残留会把条目截断。 */
    static String episodeDisplayTitle(int episode, String metaTitle, String originalTitle) {
        String number = episode > 0 && episode < 100 ? String.format("%02d", episode) : String.valueOf(episode);
        String size = null;
        String fallback = StringUtils.defaultString(originalTitle).trim();
        if (fallback.endsWith("()")) { // byte2size(size<=0) 为空串,装配残留空括号
            fallback = fallback.substring(0, fallback.length() - 2).trim();
        } else {
            java.util.regex.Matcher matcher = TRAILING_SIZE.matcher(fallback);
            if (matcher.find()) {
                size = matcher.group(1) + " " + matcher.group(2).toUpperCase(java.util.Locale.ROOT);
                fallback = fallback.substring(0, matcher.start()).trim();
            }
        }
        String title = StringUtils.defaultIfBlank(sanitizeTitle(metaTitle), fallback);
        if (StringUtils.isBlank(title)) {
            title = "第" + episode + "集";
        }
        return size == null ? number + ". " + title : number + ". " + title + "(" + size + ")";
    }

    private static String sanitizeTitle(String title) {
        if (StringUtils.isBlank(title)) {
            return null;
        }
        return title.replace('$', ' ').replace('#', ' ').replace("  ", " ").trim();
    }

    /** 拉取挂载路径播放列表并按集合并进合并线路与盘线路。
     * 显式 depth=3:getPlaylist 对 detail/web 默认 depth=1,嵌套目录结构的补缺/转存挂载会列空;
     * ac 透传——TVBox 请求(空 ac)产出紧凑播放 id(备用线路可直连 /play),web 产出代理地址。 */
    /** 编号归一的播放侧口径:资源级起始集号 &gt; 订阅级季起始集号(与巡检 applyNumbering 同序)。 */
    private static Integer effectiveStartEpisode(MediaSubscriptionResource resource, MediaSubscription subscription) {
        return resource != null && resource.getStartEpisode() != null
                ? resource.getStartEpisode() : subscription.getSeasonStartEpisode();
    }

    private void mergePlaylistFrom(MediaSubscription subscription, String path, String ac, Integer collectSeason,
                                   Integer startEpisode, TreeMap<Integer, String> merged, TreeMap<Integer, String> driveLine) {
        try {
            MovieList playlist = tvBoxService.getDetail(StringUtils.defaultString(ac), "1$" + path + Constants.PLAYLIST,
                    subscription.getName(), null, 3, true);
            if (playlist == null || playlist.getList().isEmpty()) {
                return;
            }
            TreeMap<Integer, String> entries = new TreeMap<>();
            parsePlayEntries(playlist.getList().get(0).getVod_play_url(), collectSeason,
                    startEpisode, entries);
            entries.forEach((episode, entry) -> {
                merged.putIfAbsent(episode, entry);
                driveLine.putIfAbsent(episode, entry);
            });
        } catch (Exception e) {
            log.debug("load playlist from {} failed: {}", path, e.getMessage());
        }
    }

    /** 解析 vod_play_url(组$$$集#条目 title$url)为 集→条目;至少一条解析成功才算有效。
     * 注意:选集分隔符是 '#',但 URL 可能内嵌 "#storageId=..." 片段 —— 不含 '$' 的片段拼回上一条,避免截断。 */
    boolean parsePlayEntries(String playUrl, Integer season, TreeMap<Integer, String> out) {
        return parsePlayEntries(playUrl, season, null, out);
    }

    /** 带<b>季起始集号</b>偏移的播放列表解析:资源季内编号而官方连续编号时(一念永恒形态),
     * 解析出的季内集号 +N-1 映射到全剧连续集号,与巡检/集源行同一套编号。 */
    boolean parsePlayEntries(String playUrl, Integer season, Integer seasonStartEpisode, TreeMap<Integer, String> out) {
        if (StringUtils.isBlank(playUrl)) {
            return false;
        }
        int offset = seasonStartEpisode != null && seasonStartEpisode > 1 ? seasonStartEpisode - 1 : 0;
        boolean any = false;
        for (String group : playUrl.split("\\$\\$\\$")) {
            List<String> entries = new ArrayList<>();
            for (String part : group.split("#")) {
                if (!entries.isEmpty() && !part.contains("$")) {
                    entries.set(entries.size() - 1, entries.get(entries.size() - 1) + "#" + part);
                } else {
                    entries.add(part);
                }
            }
            for (String entry : entries) {
                int index = entry.lastIndexOf('$');
                if (index <= 0) {
                    continue;
                }
                String episodeTitle = entry.substring(0, index).replaceAll("\\([^)]*\\)$", ""); // 去掉体积后缀 (1.2G)
                int episode = checkService.parseEpisodeFromTitle(episodeTitle, season);
                if (offset > 0 && episode > 0) {
                    episode += offset;
                }
                if (episode > 0) {
                    // 同集重复(平铺混排包 01.DV/01.SDR 同组并列):画质兼容性差的让位,防 DV 版绿屏
                    String existing = out.get(episode);
                    if (existing == null || TextUtils.picturePenalty(entry) < TextUtils.picturePenalty(existing)) {
                        out.put(episode, entry);
                    }
                    any = true;
                }
            }
        }
        return any;
    }

    /**
     * 集数清单(详情页集数页签):每集是否已有、来源摘要(转存>主源>补缺),全部来自集源行聚合 —— 不再列目录。
     * <p>
     * {@code sources} 是逐集资源矩阵:该集在每个资源里的行状态(VERIFIED/LISTED/FAILED/MISSING)、
     * 成功/失败取链次数、最后验证时间;转存副本以 state=TRANSFER 伪行呈现(自有文件,无集源行)。
     * 这是 episode_source 落库后唯一新增的、有信息量的视图 —— "系统自以为健康"在这里一眼可见。
     */
    public List<Map<String, Object>> episodes(int uid, int id) {
        MediaSubscription subscription = getOwned(uid, id);
        Map<Integer, String> sources = new TreeMap<>();
        Map<Integer, List<Map<String, Object>>> matrix = new TreeMap<>();
        // 1) 转存副本(自有盘):来源摘要 + 矩阵伪行
        if (MediaSubscription.MODE_TRANSFER.equals(subscription.getMode()) && !parseAccountIds(subscription).isEmpty()) {
            for (var target : transferService.transferredTargets(uid, id)) {
                Set<Integer> covered = checkService.walkEpisodesAt(target.path(), subscription.getSeason(),
                        checkService.episodeSizePolicy(subscription));
                covered.forEach(e -> {
                    sources.putIfAbsent(e, "转存:" + target.account());
                    matrix.computeIfAbsent(e, k -> new ArrayList<>()).add(new java.util.LinkedHashMap<>(Map.of(
                            "title", "转存:" + target.account(),
                            "drive", StringUtils.defaultString(target.drive()),
                            "state", "TRANSFER",
                            "successCount", 0, "failCount", 0)));
                });
            }
        }
        // 2) 集源行聚合:主源行 → "主源",补缺挂载行 → "补缺:{标题}"
        Map<Integer, MediaSubscriptionResource> resources = new java.util.HashMap<>();
        for (MediaSubscriptionResource resource : resourceRepository.findBySubscriptionIdOrderByScoreDesc(id)) {
            resources.put(resource.getId(), resource);
        }
        boolean rowsSeen = false;
        Map<Integer, List<Integer>> deadByEpisode = new java.util.TreeMap<>();
        Set<String> live = LIVE_EPISODE_STATES;
        for (Object[] pair : episodeSourceRepository.findNumberAndSource(id)) {
            rowsSeen = true;
            Integer number = (Integer) pair[0];
            MediaSubscriptionEpisodeSource row = (MediaSubscriptionEpisodeSource) pair[1];
            MediaSubscriptionResource resource = resources.get(row.getResourceId());
            if (resource == null) {
                continue;
            }
            boolean mounted = MediaSubscriptionResource.STATE_MOUNTED.equals(resource.getState())
                    && StringUtils.isNotBlank(resource.getMountPath());
            boolean primary = mounted && subscription.getMountPath() != null
                    && subscription.getMountPath().equals(resource.getMountPath());
            // 只有 LIVE 行算"已有":FAILED(取不了链)的集不能再顶着"主源"的名头显示为已有
            if (live.contains(row.getState())) {
                if (primary) {
                    sources.putIfAbsent(number, "主源");
                } else if (mounted) {
                    sources.putIfAbsent(number, "补缺:" + StringUtils.defaultIfBlank(resource.getTitle(), "候选源"));
                }
            }
            if (MediaSubscriptionEpisodeSource.STATE_FAILED.equals(row.getState())) {
                deadByEpisode.computeIfAbsent(number, k -> new ArrayList<>()).add(resource.getId());
            }
            if (mounted) { // 矩阵只展示挂载中的资源行(候选探测行对用户没有播放意义)
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("title", StringUtils.defaultIfBlank(resource.getTitle(), "候选源"));
                item.put("drive", resource.getType() == null ? "" : StringUtils.defaultString(DriveId.toDrive(resource.getType())));
                item.put("primary", primary);
                item.put("state", row.getState());
                item.put("successCount", row.getSuccessCount());
                item.put("failCount", row.getFailCount());
                item.put("lastVerifiedTime", row.getLastVerifiedTime());
                matrix.computeIfAbsent(number, k -> new ArrayList<>()).add(item);
            }
        }
        // 3) 采集源兜底覆盖层:已垫底的缺集呈现为「采集兜底」来源(ACTIVE 未过期;72h TTL 到期自然出局回缺失)
        if (episodeFallbackService != null) {
            for (cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallback row : episodeFallbackService.activeRows(id)) {
                int number = row.getEpisode();
                if (number <= 0) {
                    continue;
                }
                String label = "采集兜底:" + StringUtils.defaultIfBlank(row.getLine(), row.getSiteId());
                sources.putIfAbsent(number, label);
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("title", label);
                item.put("drive", "");
                item.put("state", "FALLBACK");
                item.put("successCount", row.getValidatedAt() == null ? 0 : 1);
                item.put("failCount", 0);
                item.put("lastVerifiedTime", row.getValidatedAt());
                matrix.computeIfAbsent(number, k -> new ArrayList<>()).add(item);
            }
        }
        if (!rowsSeen) {
            // 集源行完全未同步(首轮巡检前):退回 currentEpisodes 显示,避免页签全灰。
            // 行一旦存在就不再兜底 —— 主源失效/换源后 currentEpisodes 是旧值,
            // 再兜底会把已不可播的集全部伪造为「主源已有」(线上:主源目录清空后
            // 1..1243 兜底 + 补缺源真实行 1244..1270,集数清单 1270 行全 present、缺失 0)
            for (int i = 1; i <= (subscription.getCurrentEpisodes() == null ? 0 : subscription.getCurrentEpisodes()); i++) {
                sources.putIfAbsent(i, "主源");
            }
        }
        int base = sources.isEmpty() ? 0 : sources.keySet().stream().max(Integer::compareTo).orElse(0);
        if (!deadByEpisode.isEmpty()) {
            base = Math.max(base, deadByEpisode.keySet().stream().max(Integer::compareTo).orElse(0));
        }
        int observedBase = base;
        if (subscription.getOfficialEpisodes() != null) {
            base = Math.max(base, subscription.getOfficialEpisodes());
        }
        if (subscription.getExpectedEpisodes() != null) {
            base = Math.max(base, subscription.getExpectedEpisodes());
        }
        int totalCap = subscription.effectiveTotalEpisodes();
        if (totalCap > 0 && base > totalCap) {
            // 手动锁定总集数是未播占位的上界;观测真实文件不参与夹紧(与 computeMissing 同规)
            base = Math.max(totalCap, observedBase);
        }
        Integer seasonWindowEnd = checkService == null ? null : checkService.seasonWindowEnd(subscription);
        if (seasonWindowEnd != null && seasonWindowEnd > 0) {
            base = Math.min(base, seasonWindowEnd);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        // 季起始集号下界:分季订阅对齐后本季从全剧第 N 集开始,季前旧集不属于本订阅(与 computeMissing 同规)
        int lower = subscription.getSeasonStartEpisode() != null && subscription.getSeasonStartEpisode() > 1
                ? subscription.getSeasonStartEpisode() : 1;
        for (int i = lower; i <= Math.min(base, MAX_EPISODE_ROWS); i++) {
            String source = sources.get(i);
            boolean present = source != null; // 可用性 = LIVE 行 ∪ 采集兜底覆盖层;"源损坏"是展示文案,不是已有
            if (source == null && deadByEpisode.containsKey(i)) {
                source = "源损坏(待补源)";
            }
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("episode", i);
            item.put("present", present);
            item.put("source", source == null ? "" : source);
            item.put("sources", matrix.getOrDefault(i, List.of()));
            result.add(item);
        }
        return result;
    }

    /**
     * 媒体详情(订阅点击):元数据快照(名称/年份/状态/简介/总集数/下集播出)+ 分集列表
     * (标题/播出时间/剧照来自 provider,是否已有/来源来自本地集源行)。全程零网络 ——
     * 元数据读 media_metadata 表,无快照时后台预热(prewarmCoverAsync 一并落库),下次打开即有。
     */
    public Map<String, Object> detail(int uid, int id) {
        MediaSubscription subscription = getOwned(uid, id);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("subscription", toDto(subscription));

        MetadataDetails details = null;
        if (StringUtils.isNotBlank(subscription.getMetaProvider()) && StringUtils.isNotBlank(subscription.getMetaId())) {
            details = subscriptionMetadata(subscription);
            if (details == null) {
                checkService.prewarmCoverAsync(subscription); // 后台拉首轮元数据落库,不打断本次响应
            }
        }
        Map<String, Object> media = new java.util.LinkedHashMap<>();
        media.put("provider", subscription.getMetaProvider());
        media.put("season", subscription.getSeason() == null ? 1 : subscription.getSeason());
        if (details != null) {
            media.put("name", details.getName());
            media.put("originalName", details.getOriginalName());
            media.put("year", details.getYear());
            media.put("cover", proxiedCover(details.getCover()));
            media.put("backdrop", proxiedCover(upgradeBackdropUrl(details.getBackdrop())));
            media.put("backdrops", proxiedBackdrops(details));
            media.put("status", details.getStatus());
            media.put("totalSeasons", details.getTotalSeasons());
            media.put("runtimeMinutes", details.getRuntimeMinutes());
            media.put("overview", details.getOverview());
            media.put("aliases", details.getAliases() == null ? List.of() : details.getAliases());
            media.put("genres", details.getGenres() == null ? List.of() : details.getGenres());
            media.put("countries", details.getCountries() == null ? List.of() : details.getCountries());
            media.put("languages", details.getLanguages() == null ? List.of() : details.getLanguages());
            media.put("firstAirDate", details.getFirstAirDate());
            media.put("rating", details.getRating());
            media.put("ratings", details.getRatings() == null ? Map.of() : details.getRatings());
            media.put("directors", details.getDirectors() == null ? List.of() : details.getDirectors());
            media.put("writers", details.getWriters() == null ? List.of() : details.getWriters());
            media.put("cast", details.getCast() == null ? List.of() : details.getCast());
        }
        if (subscription.isSeasonAiredOut()) {
            // 季口径覆盖剧级 status:多季剧本季播完时整部剧仍是 RETURNING,详情页不能继续显示"在播"
            media.put("status", MetadataDetails.STATUS_ENDED);
        }
        // 条目外链(豆瓣/TMDB/Bangumi 页面,新窗跳转):订阅绑定源 + 桥接拿到的跨源 id
        Map<String, Object> links = new java.util.LinkedHashMap<>();
        appendMetaLink(links, subscription.getMetaProvider(), subscription.getMetaId());
        if (details != null && details.getExternalIds() != null) {
            details.getExternalIds().forEach((provider, metaId) -> appendMetaLink(links, provider, metaId));
        }
        if (details != null && details.getPlayLinks() != null) {
            details.getPlayLinks().forEach(links::putIfAbsent); // 官方播放地址(爱奇艺/优酷/腾讯视频)
        }
        media.put("links", links);
        // 订阅侧快照兜底:元数据未拉到/字段缺时详情页仍有官方集数与下集播出时间
        media.put("officialEpisodes", subscription.getOfficialEpisodes());
        media.put("officialTotal", subscription.getOfficialTotal());
        media.put("officialStatus", subscription.getOfficialStatus());
        media.put("nextAirTime", subscription.getNextAirTime());
        // 观测最大集号一并参与取大:官方统计滞后于资源现实时(柯南官方 1212/资源已到 1270),
        // 详情页"已播/共"不能倒挂在本地集数之下;已播再被总数夹住 —— 官方已播超过总集数是
        // 上游污染(瑞克 S9 总 10/已播 11),"已播 11 / 共 10"同样是倒挂
        int observedMax = subscription.getMaxEpisode() == null ? 0 : subscription.getMaxEpisode();
        int metaTotal = details == null || details.getTotalEpisodes() == null ? 0 : details.getTotalEpisodes();
        // 生效总集数(手动锁定优先)夹住官方侧;观测不夹(与巡检 computeMissing 同规)
        int officialish = Math.max(metaTotal, subscription.getOfficialTotal() == null ? 0 : subscription.getOfficialTotal());
        int totalCap = subscription.effectiveTotalEpisodes();
        if (totalCap > 0 && officialish > totalCap) {
            officialish = totalCap;
        }
        int total = Math.max(officialish, observedMax);
        media.put("totalEpisodes", total);
        int metaAired = details == null || details.getAiredEpisodes() == null ? 0 : details.getAiredEpisodes();
        media.put("airedEpisodes", Math.min(Math.max(Math.max(metaAired,
                subscription.getOfficialEpisodes() == null ? 0 : subscription.getOfficialEpisodes()), observedMax), total));
        result.put("media", media);

        // 分集合并:本地清单(已有/来源,含转存/主源/补缺)+ 元数据分集(标题/播出时间/剧照/简介)+ 分集行 + 日程快照
        Map<Integer, Map<String, Object>> localByEpisode = new java.util.HashMap<>();
        for (Map<String, Object> item : episodes(uid, id)) {
            localByEpisode.put((int) item.get("episode"), item);
        }
        Map<Integer, cn.har01d.alist_tvbox.dto.EpisodeInfo> metaEpisodes = new java.util.HashMap<>();
        if (details != null && details.getEpisodes() != null) {
            for (cn.har01d.alist_tvbox.dto.EpisodeInfo info : details.getEpisodes()) {
                metaEpisodes.put(info.getNumber(), info);
            }
        }
        Map<Integer, Long> scheduleAir = new java.util.HashMap<>();
        if (StringUtils.isNotBlank(subscription.getSchedule())) {
            try {
                for (cn.har01d.alist_tvbox.dto.EpisodeAirDate entry : objectMapper.readValue(
                        subscription.getSchedule(), cn.har01d.alist_tvbox.dto.EpisodeAirDate[].class)) {
                    scheduleAir.putIfAbsent(entry.getEpisode(), entry.getAirTime());
                }
            } catch (Exception e) {
                log.debug("parse schedule failed: {}", e.getMessage());
            }
        }
        Map<Integer, MediaSubscriptionEpisode> rows = new java.util.HashMap<>();
        for (MediaSubscriptionEpisode episode : episodeRepository.findBySubscriptionIdOrderByNumber(id)) {
            rows.put(episode.getNumber(), episode);
        }
        int base = Math.max(
                Math.max((int) media.get("totalEpisodes"), (int) media.get("airedEpisodes")),
                Math.max(localByEpisode.isEmpty() ? 0 : localByEpisode.keySet().stream().max(Integer::compareTo).orElse(0),
                        Math.max(rows.isEmpty() ? 0 : rows.keySet().stream().max(Integer::compareTo).orElse(0),
                                subscription.getExpectedEpisodes() == null ? 0 : subscription.getExpectedEpisodes())));
        long now = System.currentTimeMillis();
        List<Map<String, Object>> episodeItems = new ArrayList<>();
        // 季起始集号下界:分季订阅对齐后本季从全剧第 N 集开始,季前旧集不属于本订阅(与 computeMissing 同规)
        int lower = subscription.getSeasonStartEpisode() != null && subscription.getSeasonStartEpisode() > 1
                ? subscription.getSeasonStartEpisode() : 1;
        for (int i = lower; i <= Math.min(base, MAX_EPISODE_ROWS); i++) {
            Map<String, Object> local = localByEpisode.get(i);
            cn.har01d.alist_tvbox.dto.EpisodeInfo info = metaEpisodes.get(i);
            MediaSubscriptionEpisode row = rows.get(i);
            Long airTime = info != null && info.getAirTime() != null ? info.getAirTime()
                    : row != null && row.getAirTime() != null ? row.getAirTime() : scheduleAir.get(i);
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("episode", i);
            item.put("title", info != null && StringUtils.isNotBlank(info.getTitle()) ? info.getTitle()
                    : row != null ? row.getTitle() : null);
            item.put("airTime", airTime);
            item.put("aired", airTime != null && airTime <= now
                    || row != null && Boolean.TRUE.equals(row.getAired()));
            item.put("runtime", info == null ? null : info.getRuntime());
            item.put("present", local != null && Boolean.TRUE.equals(local.get("present")));
            item.put("source", local == null ? "" : StringUtils.defaultString((String) local.get("source")));
            if (info != null) {
                item.put("overview", info.getOverview());
                item.put("still", proxiedCover(info.getStill()));
            }
            episodeItems.add(item);
        }
        result.put("episodes", episodeItems);
        log.debug("details: {}", result);
        return result;
    }

    /** 更新收件箱(§10.3):近 3 天全部订阅的新集/换源/补缺事件,按时间倒序。 */
    public List<Map<String, Object>> inbox(int uid) {
        long since = System.currentTimeMillis() - 3L * 24 * 3600_000;
        List<Map<String, Object>> result = new ArrayList<>();
        for (MediaSubscription subscription : subscriptionRepository.findByUidOrderByCreatedTimeDesc(uid)) {
            for (MediaSubscriptionEvent event : eventRepository.findTop100BySubscriptionIdOrderByCreatedTimeDesc(subscription.getId())) {
                if (event.getCreatedTime() < since) {
                    break;
                }
                if (MediaSubscriptionEvent.TYPE_NEW_EPISODE.equals(event.getType())
                        || MediaSubscriptionEvent.TYPE_SOURCE_REPLACED.equals(event.getType())
                        || MediaSubscriptionEvent.TYPE_GAP_FILLED.equals(event.getType())) {
                    result.add(Map.of(
                            "subscriptionId", subscription.getId(),
                            "name", displayName(subscription),
                            "cover", proxiedCover(coverOf(subscription)),
                            "type", event.getType(),
                            "detail", StringUtils.defaultString(event.getDetail()),
                            "createdTime", event.getCreatedTime()));
                }
            }
        }
        result.sort((a, b) -> Long.compare((long) b.get("createdTime"), (long) a.get("createdTime")));
        return result.size() > 100 ? result.subList(0, 100) : result;
    }

    /** 多季联动(§10.7):检查元数据平台是否有下一季可订阅。 */
    public Map<String, Object> nextSeason(int uid, int id) {
        MediaSubscription subscription = getOwned(uid, id);
        int nextSeason = (subscription.getSeason() == null ? 1 : subscription.getSeason()) + 1;
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("season", nextSeason);
        result.put("name", subscription.getName());
        if (StringUtils.isBlank(subscription.getMetaProvider()) || StringUtils.isBlank(subscription.getMetaId())) {
            result.put("available", false);
            result.put("reason", "未绑定元数据平台");
            return result;
        }
        MetadataDetails details = metadataService.details(subscription.getMetaProvider(), subscription.getMetaId(), nextSeason);
        boolean available = details != null
                && ((details.getTotalEpisodes() != null && details.getTotalEpisodes() > 0)
                || (details.getTotalSeasons() != null && details.getTotalSeasons() >= nextSeason));
        result.put("available", available);
        if (available && details != null) {
            result.put("totalEpisodes", details.getTotalEpisodes());
        }
        return result;
    }

    /** 健康面板统计:各状态数量 + 今日新集事件数。 */
    public Map<String, Object> stats(int uid) {
        List<MediaSubscription> subscriptions = subscriptionRepository.findByUidOrderByCreatedTimeDesc(uid);
        long active = subscriptions.stream().filter(s -> MediaSubscription.STATUS_ACTIVE.equals(s.getStatus())).count();
        long paused = subscriptions.stream().filter(s -> MediaSubscription.STATUS_PAUSED.equals(s.getStatus())).count();
        long ended = subscriptions.stream().filter(s -> MediaSubscription.STATUS_ENDED.equals(s.getStatus())).count();
        long error = subscriptions.stream().filter(s -> MediaSubscription.STATUS_ERROR.equals(s.getStatus())).count();
        long todayStart = java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.of(cn.har01d.alist_tvbox.util.Constants.ZONE_ID)).toInstant().toEpochMilli();
        long weekStart = System.currentTimeMillis() - 7L * 24 * 3600_000;
        long todayNew = 0;
        long searchOk = 0;
        long searchFail = 0;
        long resourcesOk = 0;
        long resourcesBad = 0;
        for (MediaSubscription subscription : subscriptions) {
            for (var resource : resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId())) {
                if (MediaSubscriptionResource.STATE_MOUNTED.equals(resource.getState())) {
                    resourcesOk++;
                } else if (MediaSubscriptionResource.STATE_RETIRED.equals(resource.getState())
                        || MediaSubscriptionResource.STATE_REJECTED.equals(resource.getState())) {
                    resourcesBad++;
                }
            }
            for (MediaSubscriptionEvent event : eventRepository.findTop100BySubscriptionIdOrderByCreatedTimeDesc(subscription.getId())) {
                if (event.getCreatedTime() < weekStart) {
                    break;
                }
                if (event.getCreatedTime() >= todayStart && MediaSubscriptionEvent.TYPE_NEW_EPISODE.equals(event.getType())) {
                    todayNew++;
                }
                if (MediaSubscriptionEvent.TYPE_POOL_FILLED.equals(event.getType())
                        && StringUtils.contains(event.getDetail(), "新增")) {
                    searchOk++;
                } else if (MediaSubscriptionEvent.TYPE_ERROR.equals(event.getType())
                        && StringUtils.contains(event.getDetail(), "搜索失败")) {
                    searchFail++;
                }
            }
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", subscriptions.size());
        result.put("active", active);
        result.put("paused", paused);
        result.put("ended", ended);
        result.put("error", error);
        result.put("todayNewEpisodes", todayNew);
        result.put("searchSuccessRate", searchOk + searchFail == 0 ? null
                : Math.round(searchOk * 100.0 / (searchOk + searchFail)));
        result.put("resourceSurvivalRate", resourcesOk + resourcesBad == 0 ? null
                : Math.round(resourcesOk * 100.0 / (resourcesOk + resourcesBad)));
        return result;
    }

    /** 订阅导出(JSON,重装迁移/设备间复制)。 */
    public List<Map<String, Object>> export(int uid) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MediaSubscription subscription : subscriptionRepository.findByUidOrderByCreatedTimeDesc(uid)) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("name", subscription.getName());
            item.put("keyword", subscription.getKeyword());
            item.put("season", subscription.getSeason());
            item.put("doubanId", subscription.getDoubanId());
            item.put("metaProvider", subscription.getMetaProvider());
            item.put("metaId", subscription.getMetaId());
            item.put("mode", subscription.getMode());
            item.put("accountId", subscription.getAccountId());
            item.put("accountIds", parseAccountIds(subscription));
            item.put("checkIntervalHours", subscription.getCheckIntervalHours());
            item.put("expectedEpisodes", subscription.getExpectedEpisodes());
            item.put("filter", parseFilterJson(subscription.getFilterConfig()));
            result.add(item);
        }
        return result;
    }

    /** 订阅导入:同名同季跳过(含批次内重复);新建的逐个异步触发首轮检查。 */
    @Transactional
    public Map<String, Object> importSubscriptions(int uid, List<MediaSubscriptionRequest> requests) {
        int created = 0;
        int skipped = 0;
        List<Integer> createdIds = new ArrayList<>();
        var existing = subscriptionRepository.findByUidOrderByCreatedTimeDesc(uid);
        record NameSeason(String name, Integer season) {
        }
        java.util.Set<NameSeason> seen = new java.util.HashSet<>();
        existing.forEach(s -> seen.add(new NameSeason(s.getName(), s.getSeason())));
        for (MediaSubscriptionRequest request : requests == null ? List.<MediaSubscriptionRequest>of() : requests) {
            if (request == null || StringUtils.isBlank(request.getName())) {
                continue;
            }
            if (!seen.add(new NameSeason(request.getName().trim(), request.getSeason()))) {
                skipped++;
                continue;
            }
            MediaSubscriptionDto dto = create(uid, request);
            createdIds.add(dto.getId());
            created++;
        }
        // 提交后再触发首轮巡检:checkAsync 的异步线程开新事务,读不到本事务未提交的行,
        // 在事务内触发等于首轮静默丢失,只能等每小时 sweep 补救
        if (!createdIds.isEmpty()) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                createdIds.forEach(id -> checkService.checkAsync(uid, id));
                            }
                        });
            } else {
                createdIds.forEach(id -> checkService.checkAsync(uid, id));
            }
        }
        return Map.of("created", created, "skipped", skipped);
    }

    private Map<String, Object> parseFilterJson(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** TVBox 操作组/搜索页追更按钮的后端动作(§10.1)。follow 带 link 时"订阅即所见":当前源直接成为主源。 */
    @Transactional
    public Map<String, Object> handleAction(int uid, String action, Map<String, Object> body) {
        if (body == null) {
            body = Map.of();
        }
        switch (action) {
            case "follow" -> {
                String name = valueOf(body.get("name"));
                String link = valueOf(body.get("link"));
                if (StringUtils.isBlank(name)) {
                    throw new BadRequestException("缺少剧名");
                }
                MediaSubscriptionRequest request = new MediaSubscriptionRequest();
                request.setName(name.trim());
                request.setKeyword(valueOf(body.get("keyword")));
                Object season = body.get("season");
                if (season instanceof Number number) {
                    request.setSeason(number.intValue());
                }
                MediaSubscriptionDto dto = create(uid, request);
                if (StringUtils.isNotBlank(link)) {
                    String normalized = StringUtils.abbreviate(link.trim(), 1000); // 列 VARCHAR(1024),站点链接无界
                    // create 对同名订阅幂等复用,资源同样要幂等:重试/另一入口重复 follow 时直接复用已有行,
                    // 再插一行会撞 (subscription_id, link) 唯一索引把整个事务打挂 —— 已存在则视为 no-op
                    MediaSubscriptionResource resource =
                            resourceRepository.findBySubscriptionIdAndLink(dto.getId(), normalized).orElse(null);
                    if (resource == null) {
                        resource = new MediaSubscriptionResource();
                        resource.setSubscriptionId(dto.getId());
                        resource.setLink(normalized);
                        resource.setTitle(StringUtils.abbreviate(name.trim(), 250));
                        resource.setScore(1000); // 订阅即所见:当前源优先
                        resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
                        resource.setCreatedTime(System.currentTimeMillis());
                        resourceRepository.save(resource);
                        checkService.activateAsync(uid, dto.getId(), resource.getId());
                    }
                } else {
                    checkService.checkAsync(uid, dto.getId());
                }
                return Map.of("success", true, "id", dto.getId(), "subscribed", true);
            }
            case "unfollow" -> {
                Integer id = body.get("id") instanceof Number number ? number.intValue() : null;
                if (id == null) {
                    // TVBox 轨道只带链接:按链接定位订阅
                    MediaSubscription byLink = findByLink(uid, valueOf(body.get("link")));
                    if (byLink == null) {
                        return Map.of("success", true, "subscribed", false);
                    }
                    id = byLink.getId();
                }
                delete(uid, id);
                return Map.of("success", true, "subscribed", false);
            }
            case "next" -> {
                int id = intValue(body.get("id"));
                getOwned(uid, id);
                List<MediaSubscriptionResource> candidates = resourceRepository.findBySubscriptionIdOrderByScoreDesc(id).stream()
                        .filter(r -> MediaSubscriptionResource.STATE_CANDIDATE.equals(r.getState()))
                        .toList();
                if (candidates.isEmpty()) {
                    throw new BadRequestException("无可用候选源");
                }
                checkService.activateAsync(uid, id, candidates.get(0).getId());
                return Map.of("success", true);
            }
            default -> throw new BadRequestException("未知操作: " + action);
        }
    }

    /** TG 搜索详情页追加"追更"操作组(live-follow 同款播放轨道约定,spider 拦截 $msub$/$munsub$ 前缀)。
     * 轨道 id 携带 enc(链接)$enc(剧名),后端 follow 用其"订阅即所见",unfollow 按链接定位订阅。 */
    public void appendFollowTrack(MovieDetail detail, int uid, String link, String title) {
        if (detail == null || StringUtils.isBlank(detail.getVod_play_url()) || StringUtils.isBlank(link)) {
            return;
        }
        boolean followed = isFollowed(uid, link);
        String encodedLink = java.net.URLEncoder.encode(link, java.nio.charset.StandardCharsets.UTF_8);
        String name = StringUtils.defaultIfBlank(title, detail.getVod_name());
        String encodedName = java.net.URLEncoder.encode(StringUtils.defaultString(name), java.nio.charset.StandardCharsets.UTF_8);
        String label = followed ? "已订阅追更" : "追更";
        detail.setVod_play_from(StringUtils.defaultString(detail.getVod_play_from()) + "$$$" + label);
        detail.setVod_play_url(detail.getVod_play_url() + "$$$"
                + (followed ? "取消追更$munsub$" : "订阅追更$msub$") + encodedLink + "$" + encodedName);
    }

    private boolean isFollowed(int uid, String link) {
        return findByLink(uid, link) != null;
    }

    /** 按分享链接定位当前用户的订阅(unfollow 轨道只带链接)。 */
    private MediaSubscription findByLink(int uid, String link) {
        if (StringUtils.isBlank(link)) {
            return null;
        }
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(link, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            decoded = link;
        }
        for (MediaSubscription subscription : subscriptionRepository.findByUidOrderByCreatedTimeDesc(uid)) {
            for (MediaSubscriptionResource resource : resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId())) {
                if (link.equals(resource.getLink()) || decoded.equals(resource.getLink())) {
                    return subscription;
                }
            }
        }
        return null;
    }

    private static String valueOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            throw new BadRequestException("参数不合法: " + value);
        }
    }

    /** 转存目标账号列表("pan:{id}"/"ali:{id}"):优先 account_ids(JSON),兼容旧整数(默认 pan)与旧 accountId 单值。 */
    List<String> parseAccountIds(MediaSubscription subscription) {
        if (StringUtils.isNotBlank(subscription.getAccountIds())) {
            try {
                List<Object> raw = objectMapper.readValue(subscription.getAccountIds(), new TypeReference<List<Object>>() {
                });
                if (!raw.isEmpty()) {
                    List<String> ids = raw.stream()
                            .filter(java.util.Objects::nonNull)
                            .map(value -> value instanceof Number number ? "pan:" + number.intValue() : String.valueOf(value).trim())
                            .filter(StringUtils::isNotBlank)
                            .collect(Collectors.toList());
                    if (!ids.isEmpty()) {
                        return ids;
                    }
                }
            } catch (Exception e) {
                log.debug("parse accountIds failed: {}", e.getMessage());
            }
        }
        return subscription.getAccountId() == null ? List.of() : List.of("pan:" + subscription.getAccountId());
    }

    /** 序列化转存目标 id:裸数字视为 pan 账号补前缀,空列表回退单值 accountId。 */
    String serializeAccountIds(List<String> accountIds, Integer fallbackAccountId) {
        List<String> ids = accountIds == null || accountIds.isEmpty()
                ? (fallbackAccountId == null ? List.of() : List.of("pan:" + fallbackAccountId))
                : accountIds.stream()
                .filter(StringUtils::isNotBlank)
                .map(value -> value.trim().matches("\\d+") ? "pan:" + value.trim() : value.trim())
                .collect(Collectors.toList());
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 主网盘覆盖存储:去重取前 2 个分享类型码,逗号分隔;空 → null(跟随全局 msub_main_drives)。 */
    /** 更新日序列化(ISO 周几 List → 升序 CSV);null/空/全非法 → null(未配置)。 */
    static String serializeAirWeekdays(List<Integer> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        String csv = days.stream().filter(day -> day != null && day >= 1 && day <= 7)
                .distinct().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return csv.isEmpty() ? null : csv;
    }

    static String serializeMainDrives(List<Integer> mainDrives) {
        if (mainDrives == null || mainDrives.isEmpty()) {
            return null;
        }
        return mainDrives.stream().filter(java.util.Objects::nonNull).distinct().limit(2)
                .map(String::valueOf).collect(Collectors.joining(","));
    }

    static List<Integer> parseMainDrives(String raw) {
        if (StringUtils.isBlank(raw)) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim).filter(StringUtils::isNotBlank)
                .map(value -> {
                    try {
                        return Integer.valueOf(value);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private MediaSubscription getOwned(int uid, int id) {
        MediaSubscription subscription = subscriptionRepository.findById(id)
                .orElseThrow(() -> new BadRequestException("订阅不存在: " + id));
        if (subscription.getUid() != uid) {
            throw new BadRequestException("无权访问该订阅");
        }
        return subscription;
    }

    /** 共享挂载守卫:该 share 仍被其它订阅(主源或资源行)引用时不卸载 —— 内容继续有效,别人还在看。 */
    private boolean isShareReferencedByOthers(Integer shareId, int subscriptionId) {
        if (shareId == null) {
            return false;
        }
        return subscriptionRepository.existsByShareIdAndIdNot(shareId, subscriptionId)
                || resourceRepository.existsByShareIdAndSubscriptionIdNot(shareId, subscriptionId);
    }

    private String buildMountPath(MediaSubscription subscription) {
        String slug = subscription.getName().replaceAll("[\\s/\\\\:*?\"<>|#@$%\\.、,]+", "-");
        slug = StringUtils.strip(slug, "-");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        if (slug.isEmpty()) {
            slug = "sub";
        }
        // 目录名带元数据 id:全局唯一防同名剧冲突,刮削器(Emby/Jellyfin/TMM)可精准匹配;
        // 未绑定元数据时兜底内部 id。仅在创建时定名,存量订阅不改(固定路径不动)
        String tag = metaIdTag(subscription);
        // 季后缀(第 2 季起):同一 TMDB tv id 跨季共用,不带季号时并行多季订阅(含「多季联动」入口)
        // 会生成同一路径互相覆盖挂载;仅创建时定名,存量订阅路径不动
        String seasonSuffix = seasonSuffix(subscription);
        String base;
        if (tag != null) {
            base = Constants.SUBSCRIPTION_MOUNT_ROOT + slug + " " + tag + seasonSuffix;
        } else {
            base = Constants.SUBSCRIPTION_MOUNT_ROOT + subscription.getId() + "-" + slug + seasonSuffix;
        }
        // 挂载(FOLLOW)模式共享挂载路径:多个用户追同一部剧共用同一路径背后的分享挂载,
        // 播放历史按 uid 隔离互不影响;仅转存(TRANSFER)模式挂的是个人网盘,必须独占路径。
        // 转存路径构造级带 uid 段(uid>0):跨用户天然不撞,从根上堵并发同路径劫持 —— 不能用库级
        // 全局唯一索引,FOLLOW 的共享挂载收编(同路径复用主源)要求允许重复 mount_path
        if (MediaSubscription.MODE_TRANSFER.equals(subscription.getMode())) {
            String path = subscription.getUid() > 0 ? base + " u" + subscription.getUid() : base;
            return ensureUniqueMountPath(path, subscription.getUid());
        }
        return base;
    }

    /**
     * mountPath 唯一化(仅转存模式):转存挂的是个人网盘必须独占路径,被占用时追加 uid 段消歧。
     * 挂载模式共享路径不经过这里;仅影响新建订阅,存量路径不动。
     */
    private String ensureUniqueMountPath(String path, int uid) {
        if (!subscriptionRepository.existsByMountPath(path)) {
            return path;
        }
        String candidate = path + " u" + uid;
        int n = 2;
        while (subscriptionRepository.existsByMountPath(candidate) && n < 100) {
            candidate = path + " u" + uid + "-" + n++;
        }
        return candidate;
    }

    /** 挂载目录季后缀:第 2 季起追加 " Sxx",首季/未标注不加(保持既有首季路径形态)。 */
    private static String seasonSuffix(MediaSubscription subscription) {
        Integer season = subscription.getSeason();
        return season != null && season > 1 ? String.format(" S%02d", season) : "";
    }

    /** 元数据 id 目录标签:豆瓣 [dbid-x] / TMDB [tmdbid-x] / Bangumi [bgmid-x];未绑定返回 null。 */
    public static String metaIdTag(MediaSubscription subscription) {
        if (subscription.getDoubanId() != null) {
            return "[dbid-" + subscription.getDoubanId() + "]";
        }
        if (StringUtils.isBlank(subscription.getMetaProvider()) || StringUtils.isBlank(subscription.getMetaId())) {
            return null;
        }
        return switch (subscription.getMetaProvider().toLowerCase()) {
            case "tmdb" -> "[tmdbid-" + subscription.getMetaId() + "]";
            case "bangumi" -> "[bgmid-" + subscription.getMetaId() + "]";
            default -> null;
        };
    }

    private String displayName(MediaSubscription subscription) {
        String name = subscription.getName();
        if (subscription.getSeason() == null || subscription.getSeason() <= 1) {
            return name;
        }
        Integer titleSeason = TextUtils.parseTitleSeason(name);
        if (titleSeason != null && titleSeason.equals(subscription.getSeason())) {
            return name; // 条目名自带季标(豆瓣「瑞克和莫蒂 第九季」),不重复追加
        }
        return name + " 第" + subscription.getSeason() + "季";
    }

    /** 条目页外链:豆瓣 subject / TMDB tv / Bangumi subject / B站番剧播放页(bilibili id 为「ss123」形态)。 */
    private static void appendMetaLink(Map<String, Object> links, String provider, String id) {
        if (StringUtils.isBlank(provider) || StringUtils.isBlank(id)) {
            return;
        }
        switch (provider) {
            case "douban" -> links.putIfAbsent("豆瓣", "https://movie.douban.com/subject/" + id + "/");
            case "tmdb" -> links.putIfAbsent("TMDB", "https://www.themoviedb.org/tv/" + id);
            case "bangumi" -> links.putIfAbsent("Bangumi", "https://bgm.tv/subject/" + id);
            case "bilibili" -> links.putIfAbsent("B站", "https://www.bilibili.com/bangumi/play/" + id + "/");
            default -> {
            }
        }
    }

    /**
     * 封面只读本地(订阅行快照 → 豆瓣库),绝不在此发起外部请求:
     * 列表页 N 个订阅 × provider 冷缓存(重启后)曾是 N×3 次串行外部调用,把接口拖到几十秒。
     * 快照由巡检 refreshMetadata / 异步预热(prewarmCoverAsync)回填。
     */
    private String coverOf(MediaSubscription subscription) {
        if (StringUtils.isNotBlank(subscription.getCoverUrl())) {
            return subscription.getCoverUrl();
        }
        if (subscription.getDoubanId() != null) {
            var movie = movieRepository.findById(subscription.getDoubanId()).orElse(null);
            if (movie != null && StringUtils.isNotBlank(movie.getCover())) {
                return movie.getCover();
            }
        }
        return Constants.ALIST_PIC;
    }

    private String buildRemarks(MediaSubscription subscription) {
        int current = subscription.getCurrentEpisodes() == null ? 0 : subscription.getCurrentEpisodes();
        int expected = subscription.getExpectedEpisodes() == null ? 0 : subscription.getExpectedEpisodes();
        // 总数口径与 web 列表一致:手填期望(expected=0 表示跟随官方) > 官方总集数;均无才退「已更新至 N 集」。
        // 分季订阅对齐(seasonStartEpisode)的在播季<b>不显示自动分母</b>:官方总集数是全剧连续空间且
        // 登记滞后(一念永恒 TMDB 200 vs 腾讯 181 都不可信),腾讯分季登记数还会随播出继续长 ——
        // 推出来的本季体量都是假精度(35/16 两版都被否),完结(status=ENDED)后直接「N集完结」
        Integer start = subscription.getSeasonStartEpisode();
        boolean windowed = start != null && start > 1;
        int manual = subscription.getManualTotalEpisodes() == null ? 0 : subscription.getManualTotalEpisodes();
        int officialTotal = subscription.getOfficialTotal() == null ? 0 : subscription.getOfficialTotal();
        // 总数口径:手填期望(主观目标)> 手动锁定(官方总数的纠正)> 官方总集数;分季在播季
        // 无手动值时不显示自动分母(官方全剧连续空间+登记滞后,推本季体量是假精度,见下);
        // 手动锁定的分母是用户明确给的数,不受分季抑制
        int total = expected > 0 ? expected : (manual > 0 ? manual : (windowed ? 0 : officialTotal));
        boolean ended = MediaSubscription.STATUS_ENDED.equals(subscription.getStatus())
                || (expected > 0 && current >= expected)
                || (total > 0 && subscription.isSeasonAiredOut() && current >= total);
        String base;
        if (ended) {
            base = Math.max(current, total) + "集完结";
        } else if (total > 0) {
            base = current + "/" + total + "集";
        } else {
            base = "已更新至 " + current + " 集";
        }
        if (MediaSubscription.STATUS_PAUSED.equals(subscription.getStatus())) {
            return "已暂停 · " + base;
        }
        if (MediaSubscription.STATUS_ERROR.equals(subscription.getStatus())) {
            return "检查失败 · " + base;
        }
        String badge = newEpisodeBadge(subscription);
        return badge == null ? base : badge + " · " + base;
    }

    /**
     * TVBox 新集角标(Setting {@code msub_tvbox_badge},默认开;配 false 关闭):
     * 只对<b>追平过</b>的订阅显示 —— 观看进度追上资源侧最新集(看到最新播出集)时,把当时的
     * 最新集号登记为追平标记({@code caught_up_episode},读路径惰性维护,只升不降);此后
     * 新播出且未看的集计 "🆕N"(LISTED/VERIFIED 都算已播出;按集号去重,同集多资源行只计一次)。
     * 落后补看途中不亮灯 —— 还差几集没看的人不需要为最新一集亮一次(与通知门槛同哲学);
     * 回看不回算 —— History 只存当前进度,追平后跳回前面集会把 watched 拉低,追平线以内的
     * 集都是看过的旧集,须同时越过当前进度与追平线才算"新播出且未看";
     * 该集被播放后 watchedEpisode 追上,角标自动消除 —— vod_remarks 是 TVBox 协议里唯一
     * 保证被渲染的文本位。
     */
    private String newEpisodeBadge(MediaSubscription subscription) {
        if ("false".equals(settingRepository.findById("msub_tvbox_badge").map(s -> s.getValue()).orElse(""))) {
            return null;
        }
        try {
            int watched = checkService.watchedEpisode(subscription);
            if (watched <= 0) {
                return null; // 还没开始看:整部剧都是"新",角标没有信息量
            }
            List<Integer> live = episodeSourceRepository
                    .findNumbersBySubscriptionAndStatesIn(subscription.getId(), LIVE_EPISODE_STATES);
            int latest = live.stream().max(Integer::compareTo).orElse(0);
            if (latest <= 0) {
                return null; // 还没有任何可播集
            }
            if (watched >= latest) {
                if (subscription.getCaughtUpEpisode() == null || subscription.getCaughtUpEpisode() < latest) {
                    subscriptionRepository.markCaughtUp(subscription.getId(), latest);
                    subscription.setCaughtUpEpisode(latest); // 就地同步,同请求内不重复写库
                }
                return null; // 追平(或领先):没有比已看更新的集
            }
            if (subscription.getCaughtUpEpisode() == null) {
                return null; // 从未追平:落后补看途中不出角标,追平后新播出的集才算"新"
            }
            // 回看防护:History 当前进度在回跳后低于追平线(33集看完跳回11集),追平线以内的
            // 集都是看过的旧集 —— "新播出且未看"必须同时越过当前进度与追平线,否则误报 🆕22。
            int floor = Math.max(watched, subscription.getCaughtUpEpisode());
            long unwatchedNew = live.stream().filter(number -> number > floor).distinct().count();
            return unwatchedNew > 0 ? "🆕" + unwatchedNew : null;
        } catch (Exception e) {
            log.debug("new episode badge failed: {}", e.getMessage());
            return null;
        }
    }

    private MediaSubscriptionFilter resolveFilter(int uid, MediaSubscriptionFilter filter) {
        if (filter != null) {
            return filter;
        }
        String config = getPreference(uid);
        if (StringUtils.isNotBlank(config)) {
            try {
                return objectMapper.readValue(config, MediaSubscriptionFilter.class);
            } catch (Exception e) {
                log.warn("parse user preference failed: {}", e.getMessage());
            }
        }
        return new MediaSubscriptionFilter();
    }

    private String serializeFilter(MediaSubscriptionFilter filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (Exception e) {
            log.warn("serialize filter failed: {}", e.getMessage());
            return "{}";
        }
    }

    /** 校验手动播出时刻:空=清除(null),"H:mm"/"HH:mm" 归一为 "HH:mm",非法格式拒绝。 */
    private static String requireAirClock(String clock) {
        if (StringUtils.isBlank(clock)) {
            return null;
        }
        String normalized = MediaSubscriptionCheckService.normalizeAirClock(clock);
        if (normalized == null) {
            throw new BadRequestException("播出时刻格式应为 HH:mm");
        }
        return normalized;
    }

    /** 自定义搜索词存储规范化:与读取解析同口径拆分(trim/去空/去重/≤5),换行 join;空 = null(不启用) */
    private String normalizeCustomKeywords(String raw) {
        List<String> split = MediaSubscriptionCheckService.splitCustomKeywords(raw);
        return split.isEmpty() ? null : String.join("\n", split);
    }

    MediaSubscriptionDto toDto(MediaSubscription subscription) {
        MediaSubscriptionDto dto = new MediaSubscriptionDto();
        dto.setId(subscription.getId());
        dto.setName(subscription.getName());
        dto.setKeyword(subscription.getKeyword());
        dto.setCustomKeywords(subscription.getCustomKeywords());
        dto.setSeason(subscription.getSeason());
        dto.setSeasonStartEpisode(subscription.getSeasonStartEpisode());
        dto.setDoubanId(subscription.getDoubanId());
        dto.setMetaProvider(subscription.getMetaProvider());
        dto.setMetaId(subscription.getMetaId());
        dto.setOfficialEpisodes(subscription.getOfficialEpisodes());
        dto.setOfficialTotal(subscription.getOfficialTotal());
        dto.setOfficialStatus(subscription.getOfficialStatus());
        dto.setNextAirTime(subscription.getNextAirTime());
        String cover = coverOf(subscription);
        dto.setCover(Constants.ALIST_PIC.equals(cover) ? null : proxiedCover(cover));
        dto.setMode(subscription.getMode());
        dto.setAccountId(subscription.getAccountId());
        dto.setAccountIds(parseAccountIds(subscription));
        dto.setMountPath(subscription.getMountPath());
        dto.setCrossDrive(subscription.isCrossDrive());
        dto.setMagnetOffline(subscription.isMagnetOffline());
        dto.setMainDrives(parseMainDrives(subscription.getMainDrives()));
        dto.setStatus(subscription.getStatus());
        dto.setExpectedEpisodes(subscription.getExpectedEpisodes());
        dto.setManualTotalEpisodes(subscription.getManualTotalEpisodes());
        dto.setCurrentEpisodes(subscription.getCurrentEpisodes());
        dto.setMaxEpisode(subscription.getMaxEpisode());
        dto.setMissingEpisodes(missingEpisodes(subscription));
        dto.setStallCount(subscription.getStallCount());
        dto.setCheckIntervalHours(subscription.getCheckIntervalHours());
        dto.setCustomAirClock(subscription.getCustomAirClock());
        dto.setAirWeekdays(List.copyOf(MediaSubscriptionCheckService.parseAirWeekdays(subscription.getAirWeekdays())));
        dto.setNextCheckTime(subscription.getNextCheckTime());
        dto.setLastCheckTime(subscription.getLastCheckTime());
        dto.setCreatedTime(subscription.getCreatedTime());
        List<MediaSubscriptionResource> resources = resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId());
        Set<String> allowedDrives = checkService.allowedCandidateDrives(subscription); // 与候选源抽屉同口径
        dto.setResourceCount((int) resources.stream()
                .filter(r -> MediaSubscriptionResource.STATE_MOUNTED.equals(r.getState())
                        || MediaSubscriptionCheckService.driveAllowed(allowedDrives,
                        r.getType() == null ? null : DriveId.toDrive(r.getType())))
                .count());
        dto.setGapCount((int) resources.stream()
                .filter(r -> MediaSubscriptionResource.STATE_MOUNTED.equals(r.getState())
                        && StringUtils.isNotBlank(r.getMountPath())
                        && !r.getMountPath().equals(subscription.getMountPath()))
                .count());
        dto.setActiveResourceTitle(resources.stream()
                .filter(r -> MediaSubscriptionResource.STATE_MOUNTED.equals(r.getState())
                        && subscription.getMountPath() != null && subscription.getMountPath().equals(r.getMountPath()))
                .findFirst().map(MediaSubscriptionResource::getTitle).orElse(null));
        if (StringUtils.isNotBlank(subscription.getFilterConfig())) {
            try {
                dto.setFilter(objectMapper.readValue(subscription.getFilterConfig(), MediaSubscriptionFilter.class));
            } catch (Exception e) {
                log.debug("parse filter failed: {}", e.getMessage());
            }
        }
        return dto;
    }

    private List<Integer> missingEpisodes(MediaSubscription subscription) {
        // 与巡检 computeMissing 同口径:官方已播/期望/观测最大集号取大为范围 ——
        // 只认 expectedEpisodes 时,未配期望的长番(柯南 expected=null)永远拿不到缺口提示
        Set<Integer> present = episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(subscription.getId(),
                LIVE_EPISODE_STATES)
                .stream().collect(java.util.stream.Collectors.toSet());
        if (present.isEmpty() && subscription.getCurrentEpisodes() != null) {
            for (int i = 1; i <= subscription.getCurrentEpisodes(); i++) {
                present.add(i);
            }
        }
        int base = present.stream().max(Integer::compareTo).orElse(0);
        // 官方已播/期望先互选取大,再被官方总集数夹住 —— 已播数逻辑上不可能超过总集数,
        // 不夹会被上游污染数据凭空造缺口(瑞克 S9:官方总 10 完结,官方已播 11 系 S1 分集
        // 桥接污染,不夹则"10/10 缺第 11 集"且巡检空转搜不存在的集);观测最大集号不参与夹紧
        int projected = Math.max(
                subscription.getOfficialEpisodes() == null ? 0 : subscription.getOfficialEpisodes(),
                subscription.getExpectedEpisodes() == null ? 0 : subscription.getExpectedEpisodes());
        int total = subscription.effectiveTotalEpisodes();
        if (total > 0) {
            projected = Math.min(projected, total);
        }
        base = Math.max(base, projected);
        Integer seasonWindowEnd = checkService == null ? null : checkService.seasonWindowEnd(subscription);
        if (seasonWindowEnd != null && seasonWindowEnd > 0) {
            base = Math.min(base, seasonWindowEnd);
        }
        if (base <= 0 || base > MAX_EPISODE_ROWS) {
            return List.of();
        }
        List<Integer> missing = new ArrayList<>();
        // 季起始集号下界:本季从全剧第 N 集开始,季前旧集不在本订阅缺口口径内(与巡检 computeMissing 同规)
        int lower = subscription.getSeasonStartEpisode() != null && subscription.getSeasonStartEpisode() > 1
                ? subscription.getSeasonStartEpisode() : 1;
        for (int i = lower; i <= base; i++) {
            if (!present.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }
}
