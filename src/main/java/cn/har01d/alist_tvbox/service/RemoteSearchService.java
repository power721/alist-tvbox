package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriveId;
import cn.har01d.alist_tvbox.domain.SearchTargets;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.dto.pansou.MergedLink;
import cn.har01d.alist_tvbox.dto.pansou.PanSouSearchResponse;
import cn.har01d.alist_tvbox.dto.pansou.SearchRequest;
import cn.har01d.alist_tvbox.dto.pansou.SearchResult;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.TelegramChannel;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RemoteSearchService {
    private static final String CHECK_STATE_OK = "ok";
    private static final String CHECK_STATE_BAD = "bad";
    private static final String CHECK_STATE_UNCERTAIN = "uncertain";
    private static final Set<String> PAN_SOU_CHECK_TYPES = Set.of(
            "baidu", "aliyun", "quark", "tianyi", "uc", "mobile", "115", "xunlei", "123");

    // Host fragment -> cloud name, mirrors the ShareService.isValidShareLink domain whitelist.
    // Used to infer disk_type for plugin requests that only carry a raw share URL.
    private static final List<String[]> DISK_HOST_MAP = List.of(
            new String[]{"alipan.com", "aliyun"}, new String[]{"aliyundrive.com", "aliyun"},
            new String[]{"123pan.com", "123"}, new String[]{"123pan.cn", "123"},
            new String[]{"123684.com", "123"}, new String[]{"123685.com", "123"}, new String[]{"123865.com", "123"},
            new String[]{"123912.com", "123"}, new String[]{"123592.com", "123"},
            new String[]{"123684.cn", "123"}, new String[]{"123685.cn", "123"}, new String[]{"123865.cn", "123"},
            new String[]{"123912.cn", "123"}, new String[]{"123592.cn", "123"},
            new String[]{"guangyapan.com", "guangya"},
            new String[]{"mypikpak.com", "pikpak"},
            new String[]{"xunlei.com", "xunlei"},
            new String[]{"quark.cn", "quark"},
            new String[]{"139.com", "mobile"},
            new String[]{"uc.cn", "uc"},
            new String[]{"115.com", "115"}, new String[]{"115cdn.com", "115"}, new String[]{"anxia.com", "115"},
            new String[]{"189.cn", "tianyi"},
            new String[]{"baidu.com", "baidu"});

    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TelegramChannelRepository telegramChannelRepository;
    private final ShareService shareService;
    private final TvBoxService tvBoxService;
    private final OfflineDownloadService offlineDownloadService;
    private final SubscriptionSourceService subscriptionSourceService;
    private final PanSouClient panSouClient;
    private final PanLinkCheckService panLinkCheckService;
    private List<String> panSouDefaultChannels;
    private List<String> panSouBuiltinChannels;
    // holds one grouped search result set per short cache id so the folder
    // drill-down can page through it without hitting PanSou again.
    private final Cache<String, List<Message>> groupCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15))
            .maximumSize(20)
            .build();

    public RemoteSearchService(AppProperties appProperties,
                               RestTemplateBuilder restTemplateBuilder,
                               ObjectMapper objectMapper,
                               TelegramChannelRepository telegramChannelRepository,
                               ShareService shareService,
                               TvBoxService tvBoxService,
                               OfflineDownloadService offlineDownloadService,
                               SubscriptionSourceService subscriptionSourceService,
                               PanSouClient panSouClient,
                               PanLinkCheckService panLinkCheckService) {
        this.appProperties = appProperties;
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
        this.telegramChannelRepository = telegramChannelRepository;
        this.shareService = shareService;
        this.tvBoxService = tvBoxService;
        this.offlineDownloadService = offlineDownloadService;
        this.subscriptionSourceService = subscriptionSourceService;
        this.panSouClient = panSouClient;
        this.panLinkCheckService = panLinkCheckService;
    }

    /** PanSou 健康信息(供 /api/pansou 展示):健康/auth/频道来自 {@link PanSouClient},补充项目频道数。 */
    public ObjectNode getPanSouInfo() {
        ObjectNode info = panSouClient.getPanSouInfo();
        if (info != null) {
            info.put("project_channels_count", getProjectChannels().size());
        }
        return info;
    }

    public MovieList pansou(String keyword) {
        long start = System.currentTimeMillis();
        var result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        List<String> channels = telegramChannelRepository.findByEnabledTrue(Sort.by("sortOrder")).stream()
                .filter(TelegramChannel::isValid)
                .map(TelegramChannel::getUsername)
                .toList();

        var messages = search(keyword, channels, "csp_FishPanSou");
        for (var message : messages) {
            list.add(toMovieDetail(message));
        }

        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        long end = System.currentTimeMillis();
        log.info("Search {} get {} results from PanSou elapsed {} ms.", keyword, result.getTotal(), end - start);
        return result;
    }

    private MovieDetail toMovieDetail(Message message) {
        var movieDetail = new MovieDetail();
        movieDetail.setVod_id(encodeUrl(message.getLink()));
        movieDetail.setVod_name(message.getName());
        if (StringUtils.isNotBlank(message.getLink()) && StringUtils.isNotBlank(movieDetail.getVod_name())) {
            shareService.cacheShareTitle(message.getLink(), movieDetail.getVod_name());
        }
        if (StringUtils.isBlank(message.getCover())) {
            movieDetail.setVod_pic(getPic(message.getType()));
        } else {
            movieDetail.setVod_pic(message.getCover());
        }
        movieDetail.setVod_remarks(getTypeName(message.getType()));
        movieDetail.setVod_play_from(message.getChannel());
        if (message.getTime() != null) {
            movieDetail.setVod_time(message.getTime().toString());
        }
        movieDetail.setValidity_state(message.getValidityState());
        movieDetail.setValidity_summary(message.getValiditySummary());
        return movieDetail;
    }

    public MovieList pansouGroup(String keyword) {
        long start = System.currentTimeMillis();
        List<String> channels = telegramChannelRepository.findByEnabledTrue(Sort.by("sortOrder")).stream()
                .filter(TelegramChannel::isValid)
                .map(TelegramChannel::getUsername)
                .toList();
        List<Message> messages = search(keyword, channels, "csp_FishPanSouGroup");
        String cacheId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        groupCache.put(cacheId, messages);

        // seed with the configured driver order so folders follow the user's preferred order
        Map<String, List<Message>> byType = new LinkedHashMap<>();
        for (String type : appProperties.getTgDriverOrder()) {
            byType.put(type, new ArrayList<>());
        }
        for (Message message : messages) {
            byType.computeIfAbsent(message.getType(), key -> new ArrayList<>()).add(message);
        }

        List<MovieDetail> folders = new ArrayList<>();
        for (var entry : byType.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            String type = entry.getKey();
            String typeName = getTypeName(type);
            var folder = new MovieDetail();
            folder.setVod_id("pgroup:" + cacheId + ":" + type);
            folder.setVod_name((typeName == null ? type : typeName) + "网盘");
            folder.setVod_pic(getPic(type));
            folder.setVod_remarks(entry.getValue().size() + "条结果");
            folder.setVod_tag("folder");
            folders.add(folder);
        }

        var result = new MovieList();
        result.setList(folders);
        result.setTotal(folders.size());
        result.setLimit(folders.size());
        log.info("Grouped search {} get {} disk types from PanSou elapsed {} ms.", keyword, folders.size(), System.currentTimeMillis() - start);
        return result;
    }

    public MovieList pansouGroupList(String tid, int pg) {
        int page = Math.max(1, pg);
        String rest = tid.startsWith("pgroup:") ? tid.substring("pgroup:".length()) : "";
        int sep = rest.indexOf(':');
        if (sep < 0) {
            return emptyGroupList(page);
        }
        String cacheId = rest.substring(0, sep);
        String type = rest.substring(sep + 1);
        List<Message> messages = groupCache.getIfPresent(cacheId);
        if (messages == null) {
            log.info("grouped search cache {} expired", cacheId);
            return emptyGroupList(page);
        }
        List<MovieDetail> all = messages.stream()
                .filter(message -> type.equals(message.getType()))
                .map(this::toMovieDetail)
                .toList();
        int size = 20;
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        List<MovieDetail> pageItems = new ArrayList<>(all.subList(from, to));

        var result = new MovieList();
        result.setList(pageItems);
        result.setPage(page);
        result.setPagecount(Math.max(1, (int) Math.ceil(all.size() / (double) size)));
        result.setLimit(pageItems.size());
        result.setTotal(all.size());
        return result;
    }

    private MovieList emptyGroupList(int page) {
        var result = new MovieList();
        result.setList(new ArrayList<>());
        result.setPage(page);
        result.setPagecount(1);
        result.setLimit(0);
        result.setTotal(0);
        return result;
    }

    public MovieList detail(String tid) {
        return detail(tid, null, null);
    }

    public MovieList detail(String tid, String title, String keyword) {
        var share = new ShareLink();
        share.setLink(tid);
        String path = shareService.add(share);

        // Recover the real title so getPlaylist does not fall back to the obfuscated
        // storage folder name (which also breaks metadata scraping). resolveShareTitle
        // checks caller param -> shared in-memory search cache -> persisted Share.title.
        String resolved = shareService.resolveShareTitle(tid, title);
        return tvBoxService.getDetail("", "1$" + path + "/~playlist", resolved, keyword, 0);
    }

    // Per-built-in-source override parsed from the builtin extend JSON
    // ({"source":..,"filter_include":..,"filter_exclude":..}); null when no siteKey
    // or no extend configured, so callers fall back to global AppProperties values.
    private JsonNode pansouSourceConfig(String siteKey) {
        if (siteKey == null) {
            return null;
        }
        String extend = subscriptionSourceService.getBuiltinExtend(siteKey);
        if (StringUtils.isBlank(extend)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(extend);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            log.debug("invalid pansou source extend for {}: {}", siteKey, extend);
            return null;
        }
    }

    private String resolvePanSouSource(JsonNode config) {
        String override = config == null ? "" : config.path("source").asText("");
        return StringUtils.isNotBlank(override) ? override : appProperties.getPanSouSource();
    }

    private List<String> resolvePanSouFilterInclude(JsonNode config) {
        return resolvePanSouFilter(config, "filter_include", appProperties.getPanSouFilterInclude());
    }

    private List<String> resolvePanSouFilterExclude(JsonNode config) {
        return resolvePanSouFilter(config, "filter_exclude", appProperties.getPanSouFilterExclude());
    }

    // per-field inherit: a non-blank override wins, otherwise fall back to the global value
    private List<String> resolvePanSouFilter(JsonNode config, String field, List<String> globalValue) {
        String csv = config == null ? "" : config.path(field).asText("");
        if (StringUtils.isBlank(csv)) {
            return globalValue;
        }
        return Arrays.stream(csv.split(",")).map(String::trim)
                .filter(StringUtils::isNotBlank).toList();
    }

    public List<Message> search(String keyword, List<String> channels) {
        return doSearch(keyword, channels, null, null);
    }

    /** 追剧口径:盘搜按订阅定向集({@link SearchTargets})服务端定向 + 结果本地门禁,null = 观影全局口径。 */
    public List<Message> search(String keyword, List<String> channels, SearchTargets targets) {
        return doSearch(keyword, channels, null, targets);
    }

    public List<Message> search(String keyword, List<String> channels, String siteKey) {
        return doSearch(keyword, channels, siteKey, null);
    }

    private List<Message> doSearch(String keyword, List<String> channels, String siteKey, SearchTargets targets) {
        JsonNode sourceConfig = pansouSourceConfig(siteKey);
        var request = new SearchRequest(keyword, getSearchChannels(channels), resolvePanSouSource(sourceConfig));
        request.setExt(Map.of("referer", "https://dm.xueximeng.com"));
        boolean offlineDownloadEnabled = offlineDownloadService.getConfig().enabled();
        if (StringUtils.isNotBlank(keyword)) {
            List<String> cloudTypes = resolveCloudTypes(targets);
            if (!cloudTypes.isEmpty()) {
                request.setCloudTypes(cloudTypes);
            }
        }
        if (!CollectionUtils.isEmpty(appProperties.getPanSouPlugins())) {
            request.setPlugins(appProperties.getPanSouPlugins());
        }
        if (appProperties.getPanSouConc() != null && appProperties.getPanSouConc() > 0) {
            request.setConc(appProperties.getPanSouConc());
        }
        if (Boolean.TRUE.equals(appProperties.getPanSouRefresh())) {
            request.setRefresh(true);
        }
        //request.setRes(StringUtils.defaultIfBlank(appProperties.getPanSouRes(), "merge"));
        List<String> filterInclude = resolvePanSouFilterInclude(sourceConfig);
        List<String> filterExclude = resolvePanSouFilterExclude(sourceConfig);
        if (!CollectionUtils.isEmpty(filterInclude) || !CollectionUtils.isEmpty(filterExclude)) {
            request.setFilter(new SearchRequest.Filter(
                    CollectionUtils.isEmpty(filterInclude) ? List.of() : filterInclude,
                    CollectionUtils.isEmpty(filterExclude) ? List.of() : filterExclude));
        }
        String url = appProperties.getPanSouUrl() + "/api/search";
        log.debug("search request: {} {}", url, request);
        try {
            var json = searchPanSou(url, request);
            var response = objectMapper.readValue(json, PanSouSearchResponse.class);
            List<Message> messages = new ArrayList<>();
            addMergedMessages(response.getSearchResponse().getMergedByType(), keyword, offlineDownloadEnabled, messages, targets);
            if (!messages.isEmpty()) {
                return filterInvalidPanSouLinks(messages.stream().sorted(comparator()).distinct().toList());
            }

            List<SearchResult> results = response.getSearchResponse().getResults();
            if (results == null) {
                return messages;
            }
            for (var result : results) {
                if (!isMatched(result, keyword)) {
                    log.debug("ignore PanSou result '{}' because it does not match keyword '{}'", result.getTitle(), keyword);
                    continue;
                }
                if (result.getLinks() == null) {
                    continue;
                }
                for (var link : result.getLinks()) {
                    String type = getTypeName(link.getType());
                    if (type == null) {
                        continue;
                    }
                    var message = new Message(result, link);
                    if (resultTypeAllowed(message.getType(), targets)) {
                        messages.add(message);
                    }
                }
            }
            return filterInvalidPanSouLinks(messages.stream().sorted(comparator()).distinct().toList());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 盘搜 cloud_types 定向:盘白名单非空按白名单映射,否则全局 tg.drivers(现状);磁力兜底生效
     * 追加 magnet/ed2k。pan 部分为空返回空表(不发送 —— 不限模式服务端本就返回离线类型,
     * 单发离线列表会把网盘结果裁光)。
     */
    private List<String> resolveCloudTypes(SearchTargets targets) {
        List<String> base;
        if (targets != null && !targets.drives().isEmpty()) {
            base = targets.drives().stream()
                    .map(DriveId::toTypeLeniently)
                    .filter(Objects::nonNull)
                    .map(type -> PanSouClient.cloudType(String.valueOf(type)))
                    .filter(StringUtils::isNotBlank)
                    .distinct()
                    .toList();
        } else {
            base = getPanSouCloudTypes();
        }
        if (base.isEmpty() || targets == null || !targets.offlineIncluded()) {
            return base;
        }
        List<String> withOffline = new ArrayList<>(base);
        if (!withOffline.contains("magnet")) {
            withOffline.add("magnet");
        }
        if (!withOffline.contains("ed2k")) {
            withOffline.add("ed2k");
        }
        return withOffline;
    }

    /**
     * 结果本地盘门禁:定向集(null = 观影全局口径)——盘白名单非空时替换全局 tg.drivers
     * (订阅生效盘优先);白名单空时网盘维持现状;离线类型不在此收口(telegram 聚合出口的
     * 定向门禁统一裁决)。targets==null 逐字保留两条路径的存量差异(merged 恒放行离线,
     * results 按 tg.drivers)。
     */
    private boolean mergedTypeAllowed(String messageType, SearchTargets targets) {
        if (targets == null) {
            List<String> tgDrivers = appProperties.getTgDrivers();
            return isOfflineDownloadType(messageType) || tgDrivers.isEmpty() || tgDrivers.contains(messageType);
        }
        if (SearchTargets.isOfflineType(messageType)) {
            return true;
        }
        if (targets.drives().isEmpty()) {
            List<String> tgDrivers = appProperties.getTgDrivers();
            return tgDrivers.isEmpty() || tgDrivers.contains(messageType);
        }
        return targets.allowsDrive(messageType);
    }

    private boolean resultTypeAllowed(String messageType, SearchTargets targets) {
        if (targets == null) {
            List<String> tgDrivers = appProperties.getTgDrivers();
            return tgDrivers.isEmpty() || tgDrivers.contains(messageType);
        }
        return mergedTypeAllowed(messageType, targets);
    }

    /** 盘检过滤(搜索即过滤):bad/uncertain 剔除、ok/locked 盖 validityState —— 实现在 {@link PanLinkCheckService}。 */
    public List<Message> filterInvalidPanSouLinks(List<Message> messages) {
        return panLinkCheckService.filterInvalidPanSouLinks(messages);
    }

    private String searchPanSou(String url, SearchRequest request) {
        return panSouClient.post(url, request, String.class);
    }

    List<String> getSearchChannels(List<String> channels) {
        return switch (appProperties.getPanSouChannels()) {
            case "project" -> getProjectChannels();
            case "pansou" -> getPanSouBuiltinChannels();
            default -> channels;
        };
    }

    private List<String> getProjectChannels() {
        if (panSouDefaultChannels == null) {
            panSouDefaultChannels = loadPanSouDefaultChannels();
        }
        return panSouDefaultChannels;
    }

    private List<String> getPanSouBuiltinChannels() {
        if (panSouBuiltinChannels == null) {
            ObjectNode info = panSouClient.getPanSouInfo();
            if (info == null || !info.has("channels") || !info.get("channels").isArray()) {
                return List.of();
            }
            panSouBuiltinChannels = parseChannels((ArrayNode) info.get("channels"));
        }
        return panSouBuiltinChannels;
    }

    private List<String> parseChannels(ArrayNode channels) {
        List<String> list = new ArrayList<>();
        channels.forEach(channel -> {
            if (channel.isTextual() && StringUtils.isNotBlank(channel.asText())) {
                list.add(channel.asText().trim());
            }
        });
        return list.stream().distinct().toList();
    }

    private List<String> loadPanSouDefaultChannels() {
        try {
            var resource = new ClassPathResource("channels.txt");
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            return Arrays.stream(content.split("[,\\r\\n]+"))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .distinct()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("load channels.txt failed", e);
        }
    }

    private void addMergedMessages(Map<String, List<MergedLink>> mergedByType, String keyword, boolean offlineDownloadEnabled,
                                   List<Message> messages, SearchTargets targets) {
        if (CollectionUtils.isEmpty(mergedByType)) {
            return;
        }
        for (var entry : mergedByType.entrySet()) {
//            if (!offlineDownloadEnabled && isOfflineDownloadType(entry.getKey())) {
//                continue;
//            }
            String messageType = getMessageType(entry.getKey());
            if (messageType == null || !mergedTypeAllowed(messageType, targets)) {
                continue;
            }
            for (var link : entry.getValue()) {
                if (!isMatched(link, keyword)) {
                    log.debug("ignore PanSou merged link '{}' because it does not match keyword '{}'", link.getNote(), keyword);
                    continue;
                }
                messages.add(new Message(messageType, link));
            }
        }
    }

    private boolean isOfflineDownloadType(String type) {
        return "magnet".equals(type) || "ed2k".equals(type);
    }

    private boolean isMatched(SearchResult result, String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return true;
        }
        for (String token : keywordTokens(keyword)) {
            if (containsIgnoreCase(result.getTitle(), token)
                    || containsIgnoreCase(result.getContent(), token)
                    || result.getTags() != null && result.getTags().stream().anyMatch(tag -> containsIgnoreCase(tag, token))
                    || result.getLinks() != null && result.getLinks().stream().anyMatch(link -> containsIgnoreCase(link.getWorkTitle(), token))) {
                return true;
            }
        }
        return false;
    }

    private boolean isMatched(MergedLink link, String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return true;
        }
        for (String token : keywordTokens(keyword)) {
            if (containsIgnoreCase(link.getNote(), token) || containsIgnoreCase(link.getUrl(), token)) {
                return true;
            }
        }
        return false;
    }

    private List<String> keywordTokens(String keyword) {
        String normalized = keyword.trim();
        List<String> tokens = Arrays.stream(normalized.split("[\\s,，、]+"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .toList();
        return tokens.isEmpty() ? List.of(normalized) : tokens;
    }

    private boolean containsIgnoreCase(String text, String token) {
        return StringUtils.isNotBlank(text)
                && StringUtils.isNotBlank(token)
                && text.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
    }

    private List<String> getPanSouCloudTypes() {
        return new ArrayList<>(appProperties.getTgDrivers().stream()
                .map(PanSouClient::cloudType)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList());
    }

    private List<String> getPanSouCloudTypes(boolean offlineDownloadEnabled) {
        List<String> types = new ArrayList<>(appProperties.getTgDrivers().stream()
                .map(PanSouClient::cloudType)
                .filter(type -> offlineDownloadEnabled || !isOfflineDownloadType(type))
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList());
        if (offlineDownloadEnabled) {
            if (!types.contains("magnet")) {
                types.add("magnet");
            }
            if (!types.contains("ed2k")) {
                types.add("ed2k");
            }
        }
        return types;
    }

    private String getMessageType(String type) {
        return switch (type) {
            case "aliyun" -> "0";
            case "pikpak" -> "1";
            case "xunlei" -> "2";
            case "123" -> "3";
            case "quark" -> "5";
            case "mobile" -> "6";
            case "uc" -> "7";
            case "115" -> "8";
            case "tianyi" -> "9";
            case "baidu" -> "10";
            case "guangya" -> "12";
            case "magnet" -> "magnet";
            case "ed2k" -> "ed2k";
            default -> null;
        };
    }

    private Comparator<Message> comparator() {
        Comparator<Message> type = Comparator.comparing(a -> appProperties.getTgDriverOrder().indexOf(a.getType()));
        return switch (appProperties.getTgSortField()) {
            case "type" -> type.thenComparing(Comparator.comparing(Message::getTime).reversed());
            case "name" -> Comparator.comparing(Message::getName);
            case "channel" ->
                    Comparator.comparing(Message::getChannel).thenComparing(Comparator.comparing(Message::getTime).reversed());
            default -> Comparator.comparing(Message::getTime).reversed();
        };
    }

    public String searchPg(String keyword, String username, String encode) {
        List<String> channels = Arrays.stream(username.split(",")).map(e -> e.split("\\|")[0]).toList();
        return searchPg(keyword, channels, encode);
    }

    public String searchPg(String keyword, List<String> channels, String encode) {
        log.info("[PanSou] search {} from channels {}", keyword, channels);

        var result = search(keyword, channels);

        log.info("[PanSou] get {} results", result.size());
        return result.stream()
                .map(Message::toPgString)
                .map(e -> {
                    if ("1".equals(encode)) {
                        return Base64.getEncoder().encodeToString(e.getBytes());
                    }
                    return e;
                })
                .collect(Collectors.joining("\n"));
    }

    private String encodeUrl(String url) {
        return URLEncoder.encode(url, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String getTypeName(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "0" -> "阿里";
            case "1" -> "PikPak";
            case "2" -> "迅雷";
            case "3" -> "123";
            case "5" -> "夸克";
            case "6" -> "移动";
            case "7" -> "UC";
            case "8" -> "115";
            case "9" -> "天翼";
            case "10" -> "百度";
            case "12" -> "光鸭";
            case "aliyun" -> "阿里";
            case "pikpak" -> "PikPak";
            case "xunlei" -> "迅雷";
            case "123" -> "123";
            case "quark" -> "夸克";
            case "mobile" -> "移动";
            case "uc" -> "UC";
            case "115" -> "115";
            case "tianyi" -> "天翼";
            case "baidu" -> "百度";
            case "guangya" -> "光鸭";
            case "magnet" -> "磁力";
            case "ed2k" -> "ED2K";
            default -> null;
        };
    }

    private String getPic(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "0" -> getUrl("/ali.jpg");
            case "1" -> getUrl("/pikpak.jpg");
            case "2" -> getUrl("/thunder.png");
            case "3" -> getUrl("/123.png");
            case "5" -> getUrl("/quark.png");
            case "7" -> getUrl("/uc.png");
            case "8" -> getUrl("/115.jpg");
            case "9" -> getUrl("/189.png");
            case "6" -> getUrl("/139.jpg");
            case "10" -> getUrl("/baidu.jpg");
            case "12" -> getUrl("/guangya.webp");
            case "magnet" -> getUrl("/magnet.png");
            case "ed2k" -> getUrl("/ed2k.jpg");
            default -> null;
        };
    }

    private String getUrl(String path) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath(path)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

}
