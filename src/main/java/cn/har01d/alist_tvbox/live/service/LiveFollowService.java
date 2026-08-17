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
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 关注直播间。数据按用户(uid)落库,关注/取关由 TVBox 详情页"关注/取消关注"轨道或 web 管理端触发;
 * 列表展示时并行调各平台 detail 刷新开播状态(短缓存),开播的排在前面。
 */
@Slf4j
@Service
public class LiveFollowService {
    public static final String CATEGORY_ID = "follow";
    private static final long REFRESH_TIMEOUT_SECONDS = 8;

    private final LiveFollowRepository followRepository;
    private final UserService userService;
    private final AppProperties appProperties;
    private final List<LivePlatform> platforms;
    private final Cache<String, Optional<MovieDetail>> statusCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofMinutes(2))
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(6, r -> {
        Thread thread = new Thread(r, "live-follow-refresh");
        thread.setDaemon(true);
        return thread;
    });

    public LiveFollowService(LiveFollowRepository followRepository, UserService userService, AppProperties appProperties, List<LivePlatform> platforms) {
        this.followRepository = followRepository;
        this.userService = userService;
        this.appProperties = appProperties;
        this.platforms = platforms;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 订阅 token → 归属用户:用户名 token → 该用户;共享 token/空 → 首个管理员。
     * 与播放记录同步(playbackTokenForSubscription)的归属规则一致。
     */
    public int resolveUid(String token) {
        var user = StringUtils.isBlank(token) || "-".equals(token) ? null : userService.findByUsername(token);
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

    /** 关注列表(TVBox "关注"分类/首页插入用):并行刷新开播状态,开播在前,其余按关注时间倒序。 */
    public MovieList list(int uid) {
        List<LiveFollow> follows = followRepository.findByUidOrderByCreatedTimeDesc(uid);
        MovieList result = new MovieList();
        if (follows.isEmpty()) {
            return result;
        }

        Map<String, MovieDetail> refreshed = refreshAll(follows);
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
        List<LiveFollow> follows = followRepository.findByUidOrderByCreatedTimeDesc(uid);
        Map<String, MovieDetail> refreshed = refreshAll(follows);

        List<LiveFollowDto> result = new ArrayList<>();
        for (LiveFollow follow : follows) {
            LiveFollowDto dto = new LiveFollowDto();
            dto.setPlatform(follow.getPlatform());
            dto.setRoomId(follow.getRoomId());
            dto.setRoomName(follow.getRoomName());
            dto.setAnchorName(follow.getAnchorName());
            dto.setCover(absoluteCover(follow.getCover()));
            dto.setFollowedTime(follow.getCreatedTime());
            MovieDetail info = refreshed.get(cacheKey(follow.getPlatform(), follow.getRoomId()));
            if (info != null) {
                if (StringUtils.isNotBlank(info.getVod_name())) {
                    dto.setRoomName(info.getVod_name());
                }
                if (StringUtils.isNotBlank(info.getVod_pic())) {
                    dto.setCover(cleanUrl(info.getVod_pic()));
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
        detail.setVod_name(info != null && StringUtils.isNotBlank(info.getVod_name()) ? info.getVod_name() : follow.getRoomName());
        detail.setVod_pic(info != null && StringUtils.isNotBlank(info.getVod_pic()) ? cleanUrl(info.getVod_pic()) : absoluteCover(follow.getCover()));
        String platformName = platformName(follow.getPlatform());
        if (isLive(info)) {
            String remarks = StringUtils.isNotBlank(info.getVod_remarks()) ? info.getVod_remarks() : "直播中";
            detail.setVod_remarks(platformName + " · " + remarks);
        } else {
            detail.setVod_remarks(platformName + " · 未开播");
        }
        String anchor = info != null && StringUtils.isNotBlank(info.getVod_actor()) ? info.getVod_actor() : follow.getAnchorName();
        detail.setVod_actor(anchor);
        if (info != null) {
            // 顺带同步最新房间名/封面到已存元数据
            boolean changed = false;
            if (StringUtils.isNotBlank(info.getVod_name()) && !Objects.equals(info.getVod_name(), follow.getRoomName())) {
                follow.setRoomName(info.getVod_name());
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

    /** 并行刷新各关注房间的实时信息;超时或失败的房间不出现在结果里(降级为已存元数据)。 */
    private Map<String, MovieDetail> refreshAll(List<LiveFollow> follows) {
        // 平台 detail 可能基于当前请求构造代理 URL(如虎牙),把请求上下文带进工作线程
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Map<String, MovieDetail> refreshed = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (LiveFollow follow : follows) {
            if (findPlatform(follow.getPlatform()) == null) {
                continue;
            }
            futures.add(CompletableFuture.runAsync(() -> {
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
            }, executor));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("live follow refresh timeout or interrupted: {}", e.getMessage());
        }
        return refreshed;
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
        String anchor = StringUtils.isNotBlank(info.getVod_actor()) ? info.getVod_actor() : info.getVod_remarks();
        if (StringUtils.isNotBlank(anchor)) {
            follow.setAnchorName(anchor);
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
