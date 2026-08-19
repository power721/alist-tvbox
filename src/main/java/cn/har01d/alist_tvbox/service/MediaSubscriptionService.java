package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.domain.DriveId;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionDto;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionEventDto;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionFilter;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionResourceDto;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEventRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.entity.UserPreference;
import cn.har01d.alist_tvbox.entity.UserPreferenceRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.metadata.DoubanMetadataProvider;
import cn.har01d.alist_tvbox.service.metadata.MetadataService;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 追剧订阅(自动追更)CRUD 与内容接口。巡检/换源/候选池逻辑见 {@link MediaSubscriptionCheckService}。
 * 固定挂载路径(/追剧/{id}-{名称})跨换源不变,保证播放地址与观看进度不中断。
 */
@Slf4j
@Service
public class MediaSubscriptionService {
    public static final String CATEGORY_ID = "msub";
    public static final String VOD_ID_PREFIX = "msub:";

    private final MediaSubscriptionRepository subscriptionRepository;
    private final MediaSubscriptionResourceRepository resourceRepository;
    private final MediaSubscriptionEventRepository eventRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final MovieRepository movieRepository;
    private final UserService userService;
    private final TvBoxService tvBoxService;
    private final ShareService shareService;
    private final MetadataService metadataService;
    private final MediaSubscriptionCheckService checkService;
    private final MediaSubscriptionTransferService transferService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public MediaSubscriptionService(MediaSubscriptionRepository subscriptionRepository,
                                    MediaSubscriptionResourceRepository resourceRepository,
                                    MediaSubscriptionEventRepository eventRepository,
                                    UserPreferenceRepository preferenceRepository,
                                    MovieRepository movieRepository,
                                    UserService userService,
                                    TvBoxService tvBoxService,
                                    ShareService shareService,
                                    MetadataService metadataService,
                                    MediaSubscriptionCheckService checkService,
                                    MediaSubscriptionTransferService transferService,
                                    AppProperties appProperties,
                                    ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.resourceRepository = resourceRepository;
        this.eventRepository = eventRepository;
        this.preferenceRepository = preferenceRepository;
        this.movieRepository = movieRepository;
        this.userService = userService;
        this.tvBoxService = tvBoxService;
        this.shareService = shareService;
        this.metadataService = metadataService;
        this.checkService = checkService;
        this.transferService = transferService;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    /** 订阅 token → 归属用户:用户名 token → 该用户;共享 token/空 → 首个管理员。与 live-follow/播放同步一致。 */
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

    public List<MediaSubscriptionDto> list(int uid) {
        return subscriptionRepository.findByUidOrderByCreatedTimeDesc(uid).stream().map(this::toDto).toList();
    }

    @Transactional
    public MediaSubscriptionDto create(int uid, MediaSubscriptionRequest request) {
        if (request == null || StringUtils.isBlank(request.getName())) {
            throw new BadRequestException("订阅名称不能为空");
        }
        MediaSubscription subscription = new MediaSubscription();
        subscription.setUid(uid);
        subscription.setName(request.getName().trim());
        subscription.setKeyword(StringUtils.defaultIfBlank(request.getKeyword(), request.getName()).trim());
        subscription.setSeason(request.getSeason());
        subscription.setDoubanId(request.getDoubanId());
        subscription.setMetaProvider(StringUtils.defaultIfBlank(request.getMetaProvider(), null));
        subscription.setMetaId(StringUtils.defaultIfBlank(request.getMetaId(), null));
        if (subscription.getMetaId() == null && subscription.getDoubanId() != null) {
            subscription.setMetaProvider(DoubanMetadataProvider.NAME);
            subscription.setMetaId(String.valueOf(subscription.getDoubanId()));
        }
        subscription.setExpectedEpisodes(request.getExpectedEpisodes());
        subscription.setMode(StringUtils.isBlank(request.getMode()) ? MediaSubscription.MODE_FOLLOW : request.getMode());
        subscription.setAccountId(request.getAccountId());
        subscription.setAccountIds(serializeAccountIds(request.getAccountIds(), request.getAccountId()));
        subscription.setCheckIntervalHours(request.getCheckIntervalHours() != null && request.getCheckIntervalHours() > 0
                ? request.getCheckIntervalHours() : appProperties.getSubscription().getCheckIntervalHours());
        subscription.setFilterConfig(serializeFilter(resolveFilter(uid, request.getFilter())));
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
        if (StringUtils.isNotBlank(request.getName())) {
            subscription.setName(request.getName().trim());
        }
        if (request.getKeyword() != null) {
            subscription.setKeyword(request.getKeyword().trim());
            searchRelevant = true;
        }
        if (request.getSeason() != null) {
            subscription.setSeason(request.getSeason());
            searchRelevant = true;
        }
        if (request.getDoubanId() != null) {
            subscription.setDoubanId(request.getDoubanId());
        }
        if (request.getMetaProvider() != null) {
            subscription.setMetaProvider(StringUtils.defaultIfBlank(request.getMetaProvider(), null));
        }
        if (request.getMetaId() != null) {
            subscription.setMetaId(StringUtils.defaultIfBlank(request.getMetaId(), null));
            subscription.setMetaSyncTime(null); // 换条目立即重拉元数据
        }
        if (request.getExpectedEpisodes() != null) {
            subscription.setExpectedEpisodes(request.getExpectedEpisodes());
        }
        if (request.getMode() != null) {
            subscription.setMode(request.getMode());
        }
        if (request.getAccountId() != null) {
            subscription.setAccountId(request.getAccountId());
        }
        if (request.getAccountIds() != null || request.getAccountId() != null) {
            subscription.setAccountIds(serializeAccountIds(request.getAccountIds(), request.getAccountId()));
        }
        if (request.getCheckIntervalHours() != null && request.getCheckIntervalHours() > 0) {
            subscription.setCheckIntervalHours(request.getCheckIntervalHours());
        }
        if (request.getFilter() != null) {
            subscription.setFilterConfig(serializeFilter(request.getFilter()));
            searchRelevant = true;
        }
        if (searchRelevant) {
            subscription.setNextCheckTime(System.currentTimeMillis());
        }
        subscription.setUpdatedTime(System.currentTimeMillis());
        subscriptionRepository.save(subscription);
        return toDto(subscription);
    }

    @Transactional
    public void delete(int uid, int id) {
        MediaSubscription subscription = getOwned(uid, id);
        // 主源 + 所有补缺挂载(gap mounts,-补N 路径)都要删,否则 temp=false 且清理豁免的挂载会永久泄漏
        for (MediaSubscriptionResource resource : resourceRepository.findBySubscriptionIdOrderByScoreDesc(id)) {
            if (resource.getShareId() != null) {
                try {
                    shareService.deleteShare(resource.getShareId());
                } catch (Exception e) {
                    log.warn("delete resource share failed: {} {}", resource.getShareId(), e.getMessage());
                }
            }
        }
        if (subscription.getShareId() != null) {
            try {
                shareService.deleteShare(subscription.getShareId());
            } catch (Exception e) {
                log.warn("delete subscription share failed: {} {}", subscription.getShareId(), e.getMessage());
            }
        }
        resourceRepository.deleteBySubscriptionId(id);
        eventRepository.deleteBySubscriptionId(id);
        subscriptionRepository.delete(subscription);
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

    public List<MediaSubscriptionResourceDto> resources(int uid, int id) {
        getOwned(uid, id);
        return resourceRepository.findBySubscriptionIdOrderByScoreDesc(id).stream().map(r -> {
            MediaSubscriptionResourceDto dto = new MediaSubscriptionResourceDto();
            dto.setId(r.getId());
            dto.setLink(r.getLink());
            dto.setType(r.getType());
            dto.setDriveName(r.getType() == null ? null : DriveId.toDrive(r.getType()));
            dto.setSource(r.getSource());
            dto.setTitle(r.getTitle());
            dto.setEpisodesFound(r.getEpisodesFound());
            dto.setScore(r.getScore());
            dto.setValidity(r.getValidity());
            dto.setActive(r.isActive());
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
        List<MediaSubscription> subscriptions = subscriptionRepository.findByUidOrderByCreatedTimeDesc(uid).stream()
                .sorted(Comparator.comparing((MediaSubscription s) -> s.getUpdatedTime() == null ? 0 : s.getUpdatedTime(),
                        Comparator.reverseOrder()))
                .toList();
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        for (MediaSubscription subscription : subscriptions) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(VOD_ID_PREFIX + subscription.getId());
            detail.setVod_name(displayName(subscription));
            detail.setVod_pic(coverOf(subscription));
            detail.setVod_remarks(buildRemarks(subscription));
            list.add(detail);
        }
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
    }

    /** TVBox/web 详情(msub:{id}):复用 TvBoxService 播放列表(代理 URL/排序/豆瓣匹配),vod_id 重写为稳定键。 */
    public MovieList contentDetail(int uid, int id, String ac, String title) {
        MediaSubscription subscription = getOwned(uid, id);
        MovieList result = new MovieList();
        if (StringUtils.isBlank(subscription.getMountPath()) || subscription.getShareId() == null) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(VOD_ID_PREFIX + id);
            detail.setVod_name(displayName(subscription));
            detail.setVod_pic(coverOf(subscription));
            detail.setVod_remarks("尚未找到可用资源");
            result.getList().add(detail);
            result.setTotal(1);
            result.setLimit(1);
            return result;
        }
        try {
            result = tvBoxService.getDetail(ac, "1$" + subscription.getMountPath() + Constants.PLAYLIST,
                    StringUtils.defaultIfBlank(title, displayName(subscription)), null, null);
            if (!result.getList().isEmpty()) {
                result.getList().get(0).setVod_id(VOD_ID_PREFIX + id);
                result.getList().get(0).setVod_name(displayName(subscription));
            }
            mergeGapPlaylists(subscription, result);
        } catch (Exception e) {
            log.warn("subscription detail failed: {} {}", id, e.getMessage());
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(VOD_ID_PREFIX + id);
            detail.setVod_name(displayName(subscription));
            detail.setVod_pic(coverOf(subscription));
            detail.setVod_remarks("资源暂时不可用:" + e.getMessage());
            result = new MovieList();
            result.getList().add(detail);
            result.setTotal(1);
            result.setLimit(1);
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
    private String proxiedCover(String cover) {
        if (StringUtils.isBlank(cover) || !cover.startsWith("http")) {
            return cover;
        }
        return "/images?url=" + java.net.URLEncoder.encode(cover, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 多源合并播放(§4.5,需求 1):按集号合并,优先级 转存副本(自有盘)> 主源 > 补缺源,排序成单一播放列表。
     * 支持逐集异源:已转存的集走自有盘(如夸克盘),未转存的集继续走原分享(如百度分享)。 */
    private void mergeGapPlaylists(MediaSubscription subscription, MovieList result) {
        if (result == null || result.getList().isEmpty()) {
            return;
        }
        MovieDetail detail = result.getList().get(0);
        // 主源条目
        TreeMap<Integer, String> primary = new TreeMap<>();
        if (!parsePlayEntries(detail.getVod_play_url(), subscription.getSeason(), primary)) {
            return; // 主源列表解析失败不动原始输出
        }
        List<MediaSubscriptionResource> gaps = resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId()).stream()
                .filter(r -> r.isGap() && r.getShareId() != null && StringUtils.isNotBlank(r.getMountPath()))
                .toList();
        boolean transferMode = MediaSubscription.MODE_TRANSFER.equals(subscription.getMode())
                && !parseAccountIds(subscription).isEmpty();
        if (gaps.isEmpty() && !transferMode) {
            return;
        }
        TreeMap<Integer, String> merged = new TreeMap<>();
        // 1) 转存副本(自有盘,按目标顺序):已转存的集优先从自有盘播
        if (transferMode) {
            for (var target : transferService.transferredTargets(subscription.getUid(), subscription.getId())) {
                mergePlaylistFrom(subscription, target.path(), merged);
            }
        }
        // 2) 主源
        primary.forEach(merged::putIfAbsent);
        // 3) 补缺源
        for (MediaSubscriptionResource gap : gaps) {
            mergePlaylistFrom(subscription, gap.getMountPath(), merged);
        }
        if (transferMode || merged.size() != primary.size()) {
            detail.setVod_play_from("追更");
            detail.setVod_play_url(String.join("#", merged.values()));
        }
    }

    private void mergePlaylistFrom(MediaSubscription subscription, String path, TreeMap<Integer, String> merged) {
        try {
            MovieList playlist = tvBoxService.getDetail("detail", "1$" + path + Constants.PLAYLIST,
                    subscription.getName(), null, null);
            if (playlist == null || playlist.getList().isEmpty()) {
                return;
            }
            parsePlayEntries(playlist.getList().get(0).getVod_play_url(), subscription.getSeason(), merged);
        } catch (Exception e) {
            log.debug("load playlist from {} failed: {}", path, e.getMessage());
        }
    }

    /** 解析 vod_play_url(组$$$集#条目 title$url)为 集→条目;至少一条解析成功才算有效。
     * 注意:选集分隔符是 '#',但 URL 可能内嵌 "#storageId=..." 片段 —— 不含 '$' 的片段拼回上一条,避免截断。 */
    boolean parsePlayEntries(String playUrl, Integer season, TreeMap<Integer, String> out) {
        if (StringUtils.isBlank(playUrl)) {
            return false;
        }
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
                int episode = checkService.parseEpisode(episodeTitle, season);
                if (episode > 0) {
                    out.putIfAbsent(episode, entry);
                    any = true;
                }
            }
        }
        return any;
    }

    /** 集数清单(详情页集数页签):每集是否已有、来源(转存>主源>补缺)。 */
    public List<Map<String, Object>> episodes(int uid, int id) {
        MediaSubscription subscription = getOwned(uid, id);
        TreeMap<Integer, String> sources = new TreeMap<>();
        // 1) 转存副本(自有盘)
        if (MediaSubscription.MODE_TRANSFER.equals(subscription.getMode()) && !parseAccountIds(subscription).isEmpty()) {
            for (var target : transferService.transferredTargets(uid, id)) {
                checkService.walkEpisodesAt(target.path(), subscription.getSeason(), checkService.maxEpisodeBytes(subscription))
                        .forEach(e -> sources.putIfAbsent(e, "转存:" + target.account()));
            }
        }
        // 2) 主源
        checkService.parseEpisodeList(subscription.getEpisodeList()).forEach(e -> sources.putIfAbsent(e, "主源"));
        // 3) 补缺
        for (MediaSubscriptionResource resource : resourceRepository.findBySubscriptionIdOrderByScoreDesc(id)) {
            if (resource.isGap() || resource.isActive()) {
                checkService.parseEpisodeList(resource.getEpisodeList())
                        .forEach(e -> sources.putIfAbsent(e, "补缺:" + StringUtils.defaultIfBlank(resource.getTitle(), "候选源")));
            }
        }
        int base = sources.isEmpty() ? 0 : sources.lastKey();
        if (subscription.getOfficialEpisodes() != null) {
            base = Math.max(base, subscription.getOfficialEpisodes());
        }
        if (subscription.getExpectedEpisodes() != null) {
            base = Math.max(base, subscription.getExpectedEpisodes());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 1; i <= Math.min(base, 500); i++) {
            String source = sources.get(i);
            result.add(Map.of("episode", i, "present", source != null, "source", source == null ? "" : source));
        }
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
                if (MediaSubscriptionResource.VALIDITY_OK.equals(resource.getValidity())) {
                    resourcesOk++;
                } else if (MediaSubscriptionResource.VALIDITY_BAD.equals(resource.getValidity())) {
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
            checkService.checkAsync(uid, dto.getId());
            created++;
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
                    MediaSubscriptionResource resource = new MediaSubscriptionResource();
                    resource.setSubscriptionId(dto.getId());
                    resource.setLink(link.trim());
                    resource.setTitle(name.trim());
                    resource.setScore(1000); // 订阅即所见:当前源优先
                    resource.setValidity(MediaSubscriptionResource.VALIDITY_UNKNOWN);
                    resource.setCreatedTime(System.currentTimeMillis());
                    resourceRepository.save(resource);
                    checkService.activateAsync(uid, dto.getId(), resource.getId());
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
                        .filter(r -> !r.isActive() && !MediaSubscriptionResource.VALIDITY_BAD.equals(r.getValidity()))
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

    /** 转存目标账号列表:优先 account_ids(JSON),回退旧 accountId 单值。 */
    List<Integer> parseAccountIds(MediaSubscription subscription) {
        if (StringUtils.isNotBlank(subscription.getAccountIds())) {
            try {
                List<Integer> ids = objectMapper.readValue(subscription.getAccountIds(), new TypeReference<List<Integer>>() {
                });
                if (!ids.isEmpty()) {
                    return ids;
                }
            } catch (Exception e) {
                log.debug("parse accountIds failed: {}", e.getMessage());
            }
        }
        return subscription.getAccountId() == null ? List.of() : List.of(subscription.getAccountId());
    }

    private String serializeAccountIds(List<Integer> accountIds, Integer fallbackAccountId) {
        List<Integer> ids = accountIds == null || accountIds.isEmpty()
                ? (fallbackAccountId == null ? List.of() : List.of(fallbackAccountId))
                : accountIds;
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    private MediaSubscription getOwned(int uid, int id) {
        MediaSubscription subscription = subscriptionRepository.findById(id)
                .orElseThrow(() -> new BadRequestException("订阅不存在: " + id));
        if (subscription.getUid() != uid) {
            throw new BadRequestException("无权访问该订阅");
        }
        return subscription;
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
        return Constants.SUBSCRIPTION_MOUNT_ROOT + subscription.getId() + "-" + slug;
    }

    private String displayName(MediaSubscription subscription) {
        return subscription.getSeason() != null && subscription.getSeason() > 1
                ? subscription.getName() + " 第" + subscription.getSeason() + "季"
                : subscription.getName();
    }

    private String coverOf(MediaSubscription subscription) {
        if (StringUtils.isNotBlank(subscription.getMetaProvider()) && StringUtils.isNotBlank(subscription.getMetaId())) {
            MetadataDetails details = metadataService.details(subscription.getMetaProvider(), subscription.getMetaId(), subscription.getSeason());
            if (details != null && StringUtils.isNotBlank(details.getCover())) {
                return details.getCover();
            }
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
        Integer expected = subscription.getExpectedEpisodes();
        String base = "已更新至 " + current + " 集";
        if (expected != null && expected > 0) {
            if (current >= expected) {
                base = "全" + expected + "集 · 已完结";
            } else {
                base += " · 缺 " + (expected - current) + " 集";
            }
        }
        if (MediaSubscription.STATUS_PAUSED.equals(subscription.getStatus())) {
            return "已暂停 · " + base;
        }
        if (MediaSubscription.STATUS_ERROR.equals(subscription.getStatus())) {
            return "检查失败 · " + base;
        }
        return base;
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

    MediaSubscriptionDto toDto(MediaSubscription subscription) {
        MediaSubscriptionDto dto = new MediaSubscriptionDto();
        dto.setId(subscription.getId());
        dto.setName(subscription.getName());
        dto.setKeyword(subscription.getKeyword());
        dto.setSeason(subscription.getSeason());
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
        dto.setStatus(subscription.getStatus());
        dto.setExpectedEpisodes(subscription.getExpectedEpisodes());
        dto.setCurrentEpisodes(subscription.getCurrentEpisodes());
        dto.setLastEpisode(subscription.getLastEpisode());
        dto.setMissingEpisodes(missingEpisodes(subscription));
        dto.setStallCount(subscription.getStallCount());
        dto.setCheckIntervalHours(subscription.getCheckIntervalHours());
        dto.setNextCheckTime(subscription.getNextCheckTime());
        dto.setLastCheckTime(subscription.getLastCheckTime());
        dto.setCreatedTime(subscription.getCreatedTime());
        List<MediaSubscriptionResource> resources = resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId());
        dto.setResourceCount(resources.size());
        dto.setGapCount((int) resources.stream().filter(MediaSubscriptionResource::isGap).count());
        dto.setActiveResourceTitle(resources.stream().filter(MediaSubscriptionResource::isActive).findFirst()
                .map(MediaSubscriptionResource::getTitle).orElse(null));
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
        Integer expected = subscription.getExpectedEpisodes();
        if (expected == null || expected <= 0) {
            return List.of();
        }
        List<Integer> present = parseEpisodeList(subscription.getEpisodeList());
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= expected; i++) {
            if (!present.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    private List<Integer> parseEpisodeList(String json) {
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
}
