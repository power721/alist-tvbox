package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.dto.LiveFollowDto;
import cn.har01d.alist_tvbox.entity.LiveFollow;
import cn.har01d.alist_tvbox.entity.LiveFollowRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.UserService;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Utils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 关注直播间。数据按用户(uid)落库,关注/取关由 TVBox 详情页"关注/取消关注"轨道或 web 管理端触发;
 * 列表展示时并行调各平台 detail 刷新开播状态(短缓存),开播的排在前面。
 */
@Slf4j
@Service
public class LiveFollowService {
    public static final String CATEGORY_ID = "follow";
    private static final long REFRESH_TIMEOUT_SECONDS = 8;
    /**
     * 风控敏感平台:并发轰炸会按设备/IP 维度封禁,整组关注串行刷新并加间隔。
     * 抖音按 ttwid 维度风控;虎牙 detail 是 m.huya.com 页面解析(比 API 重得多),
     * 6 并发批量抓取会触发验证页导致正则全失配,同样需要串行。
     */
    private static final Set<String> THROTTLED_PLATFORMS = Set.of("douyin", "huya");
    private static final long THROTTLE_INTERVAL_MS = 300;
    /**
     * 关注状态后台定时预热(pure_live 收藏自动刷新的服务端对应物):TVBox 打开关注列表时
     * 大概率直接命中缓存,不再打开瞬间集中放量打平台接口。缓存有效期略大于预热周期,
     * 保证任意时刻打开都有预热结果可用;miss 时仍会现刷兜底(冷启动场景)。
     * 服务端与客户端的本质差异:客户端跟随 App 生命周期,服务端 7×24 运行——
     * 无人观看时持续刷平台接口纯属浪费且徒增风控暴露,故空闲超时后暂停预热,
     * 直到有人再次消费关注列表;预热任务异步执行,不占用共享的 @Scheduled 线程。
     */
    private static final long PREWARM_INTERVAL_MS = 15 * 60 * 1000L;
    private static final long PREWARM_IDLE_TIMEOUT_MS = 2 * 60 * 60 * 1000L;

    private final LiveFollowRepository followRepository;
    private final UserService userService;
    private final AppProperties appProperties;
    private final List<LivePlatform> platforms;
    private final LiveShortLinkResolver shortLinkResolver;
    /** 成功状态缓存到下一轮预热,失败结果(状态未知)只短缓存:不能把临时故障放大成持续"未知"。 */
    private final Cache<String, Optional<MovieDetail>> statusCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfter(new Expiry<String, Optional<MovieDetail>>() {
                @Override
                public long expireAfterCreate(String key, Optional<MovieDetail> value, long currentTime) {
                    return ttlFor(value);
                }

                @Override
                public long expireAfterUpdate(String key, Optional<MovieDetail> value, long currentTime, long currentDuration) {
                    return ttlFor(value);
                }

                @Override
                public long expireAfterRead(String key, Optional<MovieDetail> value, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .build();

    private static long ttlFor(Optional<MovieDetail> value) {
        return (value.isPresent() ? Duration.ofMillis(PREWARM_INTERVAL_MS + 5 * 60 * 1000L) : Duration.ofMinutes(3)).toNanos();
    }
    private final ExecutorService executor = Executors.newFixedThreadPool(6, r -> {
        Thread thread = new Thread(r, "live-follow-refresh");
        thread.setDaemon(true);
        return thread;
    });
    /** 最近一次有人消费关注列表(list/listDto)的时间,空闲超时后预热自动暂停。 */
    private volatile long lastConsumedAt = System.currentTimeMillis();
    private final AtomicBoolean prewarming = new AtomicBoolean();

    public LiveFollowService(LiveFollowRepository followRepository, UserService userService, AppProperties appProperties,
                             List<LivePlatform> platforms, LiveShortLinkResolver shortLinkResolver) {
        this.followRepository = followRepository;
        this.userService = userService;
        this.appProperties = appProperties;
        this.platforms = platforms;
        this.shortLinkResolver = shortLinkResolver;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 订阅 token → 归属用户:u-{username} → 该用户;共享 token/空 → 首个管理员(全局 tokens 无 u- 前缀,不撞车)。
     * 与播放记录同步(playbackTokenForSubscription)的归属规则一致。
     */
    public int resolveUid(String token) {
        var user = StringUtils.isBlank(token) || "-".equals(token) ? null : userService.findUserByCredentialToken(token);
        if (user == null) {
            user = StringUtils.isBlank(token) || "-".equals(token) ? null : userService.findByUserVodToken(token);
        }
        if (user == null) {
            user = userService.list().stream()
                    .filter(candidate -> candidate.getRole() == Role.ADMIN)
                    .min(Comparator.comparingInt(candidate -> candidate.getId() == null ? Integer.MAX_VALUE : candidate.getId()))
                    .orElse(null);
        }
        return user == null || user.getId() == null ? 1 : user.getId();
    }

    @Transactional
    public boolean follow(int uid, String platform, String roomId) {
        if (findPlatform(platform) == null) {
            throw new BadRequestException("unknown platform: " + platform);
        }
        if (followRepository.findByUidAndPlatformAndRoomId(uid, platform, roomId).isPresent()) {
            return false;
        }
        LiveFollow follow = new LiveFollow();
        follow.setUid(uid);
        follow.setPlatform(platform);
        follow.setRoomId(roomId);
        follow.setCreatedTime(System.currentTimeMillis());
        applyRoomInfo(follow, fetchRoomInfo(platform, roomId).orElse(null));
        followRepository.save(follow);
        statusCache.invalidate(cacheKey(platform, roomId));
        log.info("live follow: uid={}, {}${}", uid, platform, roomId);
        return true;
    }

    /**
     * 通过官方直播间地址关注:解析平台与房间号,并实时校验房间存在后才落库。
     * 与 follow(uid, platform, roomId) 的静默降级不同——URL 是用户手输的,
     * 解析失败或拉取不到房间信息必须报错,不能存进无效关注。
     * 支持带文字包装的分享文案与 b23.tv/v.douyin.com 等分享短链(网络展开)。
     */
    @Transactional
    public void followByUrl(int uid, String input) {
        String url = LiveUrlParser.extractUrl(input);
        String[] parsed = url == null ? null : LiveUrlParser.parse(url);
        if (parsed == null && url != null && LiveUrlParser.isShareLink(url)) {
            parsed = shortLinkResolver.resolve(url);
        }
        if (parsed == null) {
            throw new BadRequestException("无法识别的直播间地址,支持虎牙/斗鱼/B站/网易CC/快手/抖音/Twitch/SOOP 直播间链接或 b23.tv/v.douyin.com 分享短链");
        }
        String platform = parsed[0];
        String roomId = parsed[1];
        if (followRepository.findByUidAndPlatformAndRoomId(uid, platform, roomId).isPresent()) {
            throw new BadRequestException("已关注该直播间");
        }
        MovieDetail info = fetchRoomInfo(platform, roomId)
                .orElseThrow(() -> new BadRequestException("未找到直播间,请检查地址"));
        LiveFollow follow = new LiveFollow();
        follow.setUid(uid);
        follow.setPlatform(platform);
        follow.setRoomId(roomId);
        follow.setCreatedTime(System.currentTimeMillis());
        applyRoomInfo(follow, info);
        followRepository.save(follow);
        statusCache.invalidate(cacheKey(platform, roomId));
        log.info("live follow by url: uid={}, {}${}", uid, platform, roomId);
    }

    @Transactional
    public boolean unfollow(int uid, String platform, String roomId) {
        boolean deleted = followRepository.deleteByUidAndPlatformAndRoomId(uid, platform, roomId) > 0;
        if (deleted) {
            log.info("live unfollow: uid={}, {}${}", uid, platform, roomId);
        }
        return deleted;
    }

    public boolean isFollowed(int uid, String platform, String roomId) {
        return followRepository.findByUidAndPlatformAndRoomId(uid, platform, roomId).isPresent();
    }

    public long count(int uid) {
        return followRepository.countByUid(uid);
    }

    /** 关注列表(TVBox "关注"分类用):并行刷新开播状态,开播在前,其余按关注时间倒序。 */
    public MovieList list(int uid) {
        lastConsumedAt = System.currentTimeMillis();
        List<LiveFollow> follows = followRepository.findByUidOrderByCreatedTimeDesc(uid);
        MovieList result = new MovieList();
        if (follows.isEmpty()) {
            return result;
        }

        Map<String, MovieDetail> refreshed = refreshAll(follows, RequestContextHolder.getRequestAttributes());
        // 稳定排序:开播在前,同组保持关注时间倒序
        List<LiveFollow> sorted = follows.stream()
                .sorted(Comparator.comparing(follow -> isLive(refreshed.get(cacheKey(follow.getPlatform(), follow.getRoomId()))) ? 0 : 1))
                .toList();
        List<MovieDetail> list = new ArrayList<>();
        for (LiveFollow follow : sorted) {
            list.add(toMovieDetail(follow, refreshed.get(cacheKey(follow.getPlatform(), follow.getRoomId()))));
        }
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
    }

    /** 关注列表(web 管理端用):含开播状态。 */
    public List<LiveFollowDto> listDto(int uid) {
        lastConsumedAt = System.currentTimeMillis();
        List<LiveFollow> follows = followRepository.findByUidOrderByCreatedTimeDesc(uid);
        Map<String, MovieDetail> refreshed = refreshAll(follows, RequestContextHolder.getRequestAttributes());

        List<LiveFollowDto> result = new ArrayList<>();
        for (LiveFollow follow : follows) {
            LiveFollowDto dto = new LiveFollowDto();
            dto.setPlatform(follow.getPlatform());
            dto.setRoomId(follow.getRoomId());
            dto.setRoomName(follow.getRoomName());
            dto.setAnchorName(follow.getAnchorName());
            dto.setCover(absoluteCover(follow.getCover()));
            dto.setRoomUrl(LiveUrlParser.buildRoomUrl(follow.getPlatform(), follow.getRoomId()));
            dto.setFollowedTime(follow.getCreatedTime());
            MovieDetail info = refreshed.get(cacheKey(follow.getPlatform(), follow.getRoomId()));
            if (info != null) {
                if (StringUtils.isNotBlank(info.getVod_name())) {
                    dto.setRoomName(info.getVod_name());
                }
                if (StringUtils.isNotBlank(info.getVod_pic())) {
                    // 预热详情的封面可能带 mock host,先归一再按当前请求重建
                    dto.setCover(absoluteCover(normalizeCover(info.getVod_pic())));
                }
                dto.setLive(isLive(info));
            }
            result.add(dto);
        }
        return result;
    }

    /**
     * 详情页追加"关注"轨道组:固定提供"关注"和"取消关注"两个选集(后端幂等),
     * 组名按当前状态显示"关注"/"已关注"——播放器详情页打开后不会重新拉取,
     * 状态文字会过期,必须两个操作都常驻才能立即取关。spider 拦截 follow$/unfollow$ 前缀。
     * 未开播时平台没有可播线路,需先补"未开播"占位线路,否则"关注主播"成为第一集
     * 被播放器自动选中起播,导致误关注(占位选集 id=offline,spider 提示后拦截)。
     */
    public void appendFollowTrack(MovieDetail detail, int uid) {
        String[] parts = detail.getVod_id().split("\\$");
        if (parts.length < 2) {
            return;
        }
        String platform = parts[0];
        String roomId = String.join("$", Arrays.copyOfRange(parts, 1, parts.length));
        String label = isFollowed(uid, platform, roomId) ? "已关注" : "关注";
        String from = StringUtils.defaultString(detail.getVod_play_from());
        String url = detail.getVod_play_url();
        if (StringUtils.isEmpty(url)) {
            from = "未开播";
            url = "未开播$offline";
        }
        detail.setVod_play_from(from + "$$$" + label);
        detail.setVod_play_url(url + "$$$" + "关注主播$follow$" + platform + "$" + roomId + "#取消关注$unfollow$" + platform + "$" + roomId);
    }

    private MovieDetail toMovieDetail(LiveFollow follow, MovieDetail info) {
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(follow.getPlatform() + "$" + follow.getRoomId());
        String anchor = info != null && StringUtils.isNotBlank(info.getVod_actor()) ? info.getVod_actor() : follow.getAnchorName();
        // 列表标题显示主播名,播放器列表不展示 vod_actor,拿不到主播名时才回退房间名
        String roomName = info != null && StringUtils.isNotBlank(info.getVod_name()) ? info.getVod_name() : follow.getRoomName();
        detail.setVod_name(StringUtils.isNotBlank(anchor) ? anchor : roomName);
        // 预热写入的详情可能带 mock host 的代理封面,统一先归一为相对路径再按当前请求重建
        detail.setVod_pic(info != null && StringUtils.isNotBlank(info.getVod_pic()) ? absoluteCover(normalizeCover(info.getVod_pic())) : absoluteCover(follow.getCover()));
        String platformName = platformName(follow.getPlatform());
        if (isLive(info)) {
            String remarks = StringUtils.isNotBlank(info.getVod_remarks()) ? info.getVod_remarks() : "直播中";
            detail.setVod_remarks(platformName + " · " + remarks);
        } else {
            detail.setVod_remarks(platformName + " · 未开播");
        }
        detail.setVod_actor(anchor);
        if (info != null) {
            // 顺带同步最新房间名/封面/主播名到已存元数据(主播名修正早期由 remarks 误存的脏数据)
            boolean changed = false;
            if (StringUtils.isNotBlank(info.getVod_name()) && !Objects.equals(info.getVod_name(), follow.getRoomName())) {
                follow.setRoomName(info.getVod_name());
                changed = true;
            }
            if (StringUtils.isNotBlank(info.getVod_actor()) && !Objects.equals(info.getVod_actor(), follow.getAnchorName())) {
                follow.setAnchorName(info.getVod_actor());
                changed = true;
            }
            if (StringUtils.isNotBlank(info.getVod_pic()) && !Objects.equals(normalizeCover(info.getVod_pic()), follow.getCover())) {
                follow.setCover(normalizeCover(info.getVod_pic()));
                changed = true;
            }
            if (changed) {
                followRepository.save(follow);
            }
        }
        return detail;
    }

    /**
     * 并行刷新各关注房间的实时信息;超时或失败的房间不出现在结果里(降级为已存元数据)。
     * 抖音等风控敏感平台按平台分组串行刷新,避免同平台瞬时并发触发封控。
     */
    private Map<String, MovieDetail> refreshAll(List<LiveFollow> follows, RequestAttributes attributes) {
        Map<String, MovieDetail> refreshed = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        Map<String, List<LiveFollow>> byPlatform = new LinkedHashMap<>();
        for (LiveFollow follow : follows) {
            if (findPlatform(follow.getPlatform()) == null) {
                continue;
            }
            byPlatform.computeIfAbsent(follow.getPlatform(), key -> new ArrayList<>()).add(follow);
        }
        for (List<LiveFollow> group : byPlatform.values()) {
            if (THROTTLED_PLATFORMS.contains(group.get(0).getPlatform())) {
                futures.add(CompletableFuture.runAsync(() -> refreshGroupSerially(group, attributes, refreshed), executor));
            } else {
                for (LiveFollow follow : group) {
                    futures.add(CompletableFuture.runAsync(() -> refreshOne(follow, attributes, refreshed), executor));
                }
            }
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("live follow refresh timeout or interrupted: {}", e.getMessage());
        }
        return refreshed;
    }

    /** 敏感平台整组一个任务排队刷新:单平台并发降为 1,房间之间留间隔;命中缓存(无网络请求)不占间隔预算。 */
    private void refreshGroupSerially(List<LiveFollow> group, RequestAttributes attributes, Map<String, MovieDetail> refreshed) {
        for (int i = 0; i < group.size(); i++) {
            LiveFollow follow = group.get(i);
            if (i > 0 && statusCache.getIfPresent(cacheKey(follow.getPlatform(), follow.getRoomId())) == null) {
                try {
                    Thread.sleep(THROTTLE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            refreshOne(follow, attributes, refreshed);
        }
    }

    private void refreshOne(LiveFollow follow, RequestAttributes attributes, Map<String, MovieDetail> refreshed) {
        if (attributes != null) {
            RequestContextHolder.setRequestAttributes(attributes);
        }
        try {
            MovieDetail info = fetchRoomInfoCached(follow.getPlatform(), follow.getRoomId());
            if (info != null) {
                refreshed.put(cacheKey(follow.getPlatform(), follow.getRoomId()), info);
            }
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /**
     * 关注状态定时预热:遍历全部用户的关注房间刷新开播状态写入缓存。
     * 服务端长期运行的代价控制:①空闲(无人消费关注列表)超过 2 小时即跳过,
     * 避免无人观看时持续请求平台接口;②整体提交到自有线程池异步执行,
     * 不阻塞共享的 @Scheduled 单线程调度器(其他定时任务不受影响);
     * ③防重入:上一轮未完成时跳过本轮。调度线程无请求上下文,传入最小 mock
     * 支撑平台 detail 内部的封面代理 URL 构造(fromCurrentRequest),缓存中的封面
     * 已归一为相对路径,展示时由真实请求重建 host。
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = PREWARM_INTERVAL_MS)
    public void prewarmFollowStatus() {
        long idle = System.currentTimeMillis() - lastConsumedAt;
        if (idle > PREWARM_IDLE_TIMEOUT_MS) {
            log.debug("skip follow prewarm: idle for {}ms", idle);
            return;
        }
        if (!prewarming.compareAndSet(false, true)) {
            log.debug("skip follow prewarm: previous round still running");
            return;
        }
        CompletableFuture.runAsync(this::doPrewarmFollowStatus, executor);
    }

    private void doPrewarmFollowStatus() {
        try {
            List<Integer> uids = followRepository.findDistinctUids();
            if (uids.isEmpty()) {
                return;
            }
            RequestAttributes attributes = mockRequestAttributes();
            int rooms = 0;
            int failed = 0;
            for (int uid : uids) {
                List<LiveFollow> follows = followRepository.findByUidOrderByCreatedTimeDesc(uid);
                if (follows.isEmpty()) {
                    continue;
                }
                rooms += follows.size();
                Map<String, MovieDetail> refreshed = refreshAll(follows, attributes);
                // 平台限流后逐房间可能超时,但整平台全失败(一个都没刷出来)通常意味着接口异常,显式告警
                for (LiveFollow follow : follows) {
                    if (findPlatform(follow.getPlatform()) != null && !refreshed.containsKey(cacheKey(follow.getPlatform(), follow.getRoomId()))) {
                        failed++;
                    }
                }
            }
            if (failed > 0) {
                log.warn("prewarmed {} followed rooms for {} users, {} failed to refresh (platform API error or timeout)", rooms, uids.size(), failed);
            } else {
                log.info("prewarmed {} followed rooms for {} users", rooms, uids.size());
            }
        } catch (Exception e) {
            log.warn("prewarm follow status failed", e);
        } finally {
            prewarming.set(false);
        }
    }

    private static RequestAttributes mockRequestAttributes() {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRequestURL" -> new StringBuffer("http://127.0.0.1");
                    case "getRequestURI" -> "/live";
                    case "getScheme" -> "http";
                    case "getServerName" -> "127.0.0.1";
                    case "getServerPort" -> 80;
                    case "getQueryString" -> "";
                    case "isSecure" -> false;
                    default -> defaultValue(method.getReturnType());
                });
        return new ServletRequestAttributes(request);
    }

    /** fromCurrentRequest 等还会调其他方法:原生类型返回 null 会拆箱 NPE,必须按类型给默认值。 */
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private boolean isLive(MovieDetail info) {
        return info != null && StringUtils.isNotBlank(info.getVod_play_url());
    }

    private String platformName(String type) {
        LivePlatform platform = findPlatform(type);
        return platform == null ? type : platform.getName();
    }

    private LivePlatform findPlatform(String type) {
        for (LivePlatform platform : platforms) {
            if (platform.getType().equals(type)) {
                return platform;
            }
        }
        return null;
    }

    private String cacheKey(String platform, String roomId) {
        return platform + "$" + roomId;
    }

    private MovieDetail fetchRoomInfoCached(String platform, String roomId) {
        return statusCache.get(cacheKey(platform, roomId), key -> fetchRoomInfo(platform, roomId)).orElse(null);
    }

    /** 调平台 detail 取房间实时信息;失败返回 null(视为状态未知,降级为已存元数据)。 */
    private Optional<MovieDetail> fetchRoomInfo(String platform, String roomId) {
        LivePlatform livePlatform = findPlatform(platform);
        if (livePlatform == null) {
            return Optional.empty();
        }
        try {
            MovieList result = livePlatform.detail(platform + "$" + roomId, null);
            if (result != null && !result.getList().isEmpty()) {
                return Optional.of(result.getList().get(0));
            }
        } catch (Exception e) {
            log.debug("fetch live room info failed: {}${}: {}", platform, roomId, e.getMessage());
        }
        return Optional.empty();
    }

    private void applyRoomInfo(LiveFollow follow, MovieDetail info) {
        if (info == null) {
            return;
        }
        if (StringUtils.isNotBlank(info.getVod_name())) {
            follow.setRoomName(info.getVod_name());
        }
        if (StringUtils.isNotBlank(info.getVod_pic())) {
            follow.setCover(normalizeCover(info.getVod_pic()));
        }
        // detail 接口的 remarks 是人气/开播状态文案,不是主播名,不能当 anchor 存
        if (StringUtils.isNotBlank(info.getVod_actor())) {
            follow.setAnchorName(info.getVod_actor());
        }
    }

    /** 代理封面(/images?url=...)依赖请求 host,入库只存相对路径,避免固化关注时的访问地址。 */
    private String normalizeCover(String url) {
        String cleaned = cleanUrl(url);
        if (cleaned == null) {
            return null;
        }
        int idx = cleaned.indexOf("/images?url=");
        if (idx > 0 && cleaned.startsWith("http")) {
            return cleaned.substring(idx);
        }
        return cleaned;
    }

    /** 展示时把相对代理封面按当前请求 host 重建绝对地址。 */
    private String absoluteCover(String stored) {
        if (stored == null || !stored.startsWith("/")) {
            return stored;
        }
        String query = stored.contains("?") ? stored.substring(stored.indexOf('?') + 1) : "";
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath("/images")
                .replaceQuery(query)
                .build()
                .toUriString();
    }

    /** 虎牙等平台的封面 URL 内嵌 JSON 转义(\u002F),入库/展示前归一为可直接访问的 URL。 */
    private String cleanUrl(String url) {
        return StringUtils.isBlank(url) ? url : url.replace("\\u002F", "/");
    }
}
