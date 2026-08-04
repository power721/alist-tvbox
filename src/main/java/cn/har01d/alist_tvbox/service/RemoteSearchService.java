package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
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
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private List<String> panSouDefaultChannels;
    private List<String> panSouBuiltinChannels;
    private String panSouToken;
    private String checkedPanSouUrl;
    // carries the search-result title from search() to detail() so the resolved
    // storage folder name (often an obfuscated share token) does not overwrite it.
    private final Cache<String, String> shareTitle = Caffeine.newBuilder().maximumSize(200).expireAfterWrite(Duration.ofHours(2)).build();
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
                               SubscriptionSourceService subscriptionSourceService) {
        this.appProperties = appProperties;
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
        this.telegramChannelRepository = telegramChannelRepository;
        this.shareService = shareService;
        this.tvBoxService = tvBoxService;
        this.offlineDownloadService = offlineDownloadService;
        this.subscriptionSourceService = subscriptionSourceService;
    }

    @PostConstruct
    public void setup() {
        refreshPanSouInfoAsync();
    }

    public ObjectNode getPanSouInfo() {
        String url = appProperties.getPanSouUrl();
        ObjectNode info = restTemplate.getForObject(url + "/api/health", ObjectNode.class);
        if (info != null) {
            checkedPanSouUrl = StringUtils.defaultString(url);
            updatePanSouAuthEnabled(info);
            info.put("project_channels_count", getProjectChannels().size());
        }
        return info;
    }

    public void refreshPanSouInfoAsync() {
        String url = appProperties.getPanSouUrl();
        checkedPanSouUrl = StringUtils.defaultString(url);
        if (StringUtils.isBlank(url)) {
            appProperties.setPanSouAuthEnabled(null);
            return;
        }
        appProperties.setPanSouAuthEnabled(null);
        CompletableFuture.runAsync(() -> {
            try {
                getPanSouInfo();
            } catch (Exception e) {
                log.warn("check PanSou health failed: {}", url, e);
                appProperties.setPanSouAuthEnabled(null);
            }
        });
    }

    private void refreshPanSouInfoIfUrlChanged() {
        String url = appProperties.getPanSouUrl();
        if (checkedPanSouUrl != null && !StringUtils.equals(StringUtils.defaultString(url), checkedPanSouUrl)) {
            refreshPanSouInfoAsync();
        }
    }

    private void updatePanSouAuthEnabled(ObjectNode info) {
        if (info.has("auth_enabled")) {
            appProperties.setPanSouAuthEnabled(info.get("auth_enabled").asBoolean(false));
        }
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
            shareTitle.put(message.getLink(), movieDetail.getVod_name());
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
        var share = new ShareLink();
        share.setLink(tid);
        String path = shareService.add(share);

        // backfill the title captured during search; without it getPlaylist falls
        // back to the obfuscated storage folder name and metadata scraping fails.
        String title = shareTitle.getIfPresent(tid);
        return tvBoxService.getDetail("", "1$" + path + "/~playlist", title, 0);
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
        return search(keyword, channels, null);
    }

    public List<Message> search(String keyword, List<String> channels, String siteKey) {
        JsonNode sourceConfig = pansouSourceConfig(siteKey);
        var request = new SearchRequest(keyword, getSearchChannels(channels), resolvePanSouSource(sourceConfig));
        request.setExt(Map.of("referer", "https://dm.xueximeng.com"));
        boolean offlineDownloadEnabled = offlineDownloadService.getConfig().enabled();
        if (StringUtils.isNotBlank(keyword)) {
            request.setCloudTypes(getPanSouCloudTypes());
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
            addMergedMessages(response.getSearchResponse().getMergedByType(), keyword, offlineDownloadEnabled, messages);
            if (!messages.isEmpty()) {
                return filterInvalidPanSouLinks(messages.stream().sorted(comparator()).distinct().toList());
            }

            List<SearchResult> results = response.getSearchResponse().getResults();
            if (results == null) {
                return messages;
            }
            List<String> tgDrivers = appProperties.getTgDrivers();
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
                    if (tgDrivers.isEmpty() || tgDrivers.contains(message.getType())) {
                        messages.add(message);
                    }
                }
            }
            return filterInvalidPanSouLinks(messages.stream().sorted(comparator()).distinct().toList());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    List<Message> selectCheckable(List<Message> messages) {
        Set<String> enabledLinkCheckTypes = getEnabledLinkCheckTypes();
        return messages.stream()
                .filter(message -> !isOfflineDownloadType(message.getType()))
                .filter(message -> StringUtils.isNotBlank(getPanSouCloudType(message.getType())))
                .filter(message -> enabledLinkCheckTypes.contains(getPanSouCloudType(message.getType())))
                .toList();
    }

    private Set<String> getEnabledLinkCheckTypes() {
        List<String> configured = appProperties.getPanSouLinkCheckTypes();
        if (CollectionUtils.isEmpty(configured)) {
            return PAN_SOU_CHECK_TYPES;
        }
        return configured.stream()
                .filter(PAN_SOU_CHECK_TYPES::contains)
                .collect(Collectors.toSet());
    }

    public List<Message> filterInvalidPanSouLinks(List<Message> messages) {
        if (!appProperties.isPanSouLinkCheckEnabled() || messages.isEmpty()) {
            return messages;
        }
        List<Message> checkable = selectCheckable(messages);
        log.debug("filterInvalidPanSouLinks totla={} checkable={} threashold={}", messages.size(), checkable.size(), appProperties.getPanSouLinkCheckMaxCount());
        if (checkable.isEmpty() || checkable.size() > appProperties.getPanSouLinkCheckMaxCount()) {
            return messages;
        }

        Map<String, String> states = new java.util.HashMap<>();
        Map<String, String> summaries = new java.util.HashMap<>();
        long startedAt = System.currentTimeMillis();
        ObjectNode response = null;
        try {
            response = checkPanSouLinks(buildPanSouLinkCheckRequest(checkable));
        } catch (Exception e) {
            log.warn("check PanSou search links failed", e);
        }
        if (response != null && response.has("results") && response.get("results").isArray()) {
            response.get("results").forEach(result -> {
                if (result.has("url") && result.has("state")) {
                    String url = result.get("url").asText();
                    states.put(url, result.get("state").asText());
                    if (result.has("summary")) {
                        summaries.put(url, result.get("summary").asText());
                    }
                }
            });
        }
        logPanSouLinkCheck(checkable, states, startedAt);
        if (states.isEmpty()) {
            return messages;
        }
        return messages.stream()
                .filter(message -> !isInvalidPanSouCheckState(states.get(message.getLink())))
                .peek(message -> {
                    if (states.containsKey(message.getLink())) {
                        String state = states.get(message.getLink());
                        message.setValidityState(state);
                        message.setValiditySummary(StringUtils.defaultIfBlank(summaries.get(message.getLink()), getPanSouLinkStateSummary(state)));
                    }
                })
                .toList();
    }

    private boolean isInvalidPanSouCheckState(String state) {
        return CHECK_STATE_BAD.equals(state) || CHECK_STATE_UNCERTAIN.equals(state);
    }

    private String getPanSouLinkStateSummary(String state) {
        if ("locked".equals(state)) {
            return "链接受限";
        }
        return "链接有效";
    }

    private ObjectNode buildPanSouLinkCheckRequest(List<Message> messages) {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode items = request.putArray("items");
        for (Message message : messages) {
            items.addObject()
                    .put("disk_type", getPanSouCloudType(message.getType()))
                    .put("url", message.getLink());
        }
        request.put("view_token", "pansou-search-" + System.currentTimeMillis());
        return request;
    }

    private void logPanSouLinkCheck(List<Message> checkable, Map<String, String> states, long startedAt) {
        Map<String, int[]> stats = new LinkedHashMap<>();
        int totalValid = 0;
        for (Message message : checkable) {
            String type = getPanSouCloudType(message.getType());
            int[] counts = stats.computeIfAbsent(type, k -> new int[2]);
            counts[0]++;
            if (CHECK_STATE_OK.equals(states.get(message.getLink()))) {
                counts[1]++;
                totalValid++;
            }
        }
        StringBuilder detail = new StringBuilder();
        for (var entry : stats.entrySet()) {
            if (detail.length() > 0) {
                detail.append(", ");
            }
            detail.append(entry.getKey()).append(' ').append(entry.getValue()[1]).append('/').append(entry.getValue()[0]);
        }
        log.info("检测网盘链接{}条，{}条有效 [{}]，耗时{}ms", checkable.size(), totalValid, detail, System.currentTimeMillis() - startedAt);
    }

    private String searchPanSou(String url, SearchRequest request) {
        if (!shouldUsePanSouAuth()) {
            return restTemplate.postForObject(url, request, String.class);
        }
        String token = getPanSouToken();
        if (StringUtils.isBlank(token)) {
            return restTemplate.postForObject(url, request, String.class);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, headers), String.class).getBody();
    }

    // Plugin-facing entry: fill in disk_type from the share URL when the caller omits it,
    // then delegate to checkPanSouLinks. Lets filter/spider plugins send just URLs.
    public ObjectNode checkLinks(ObjectNode request) {
        if (request != null && request.has("items") && request.get("items").isArray()) {
            for (JsonNode item : request.get("items")) {
                if (!item.isObject()) {
                    continue;
                }
                ObjectNode obj = (ObjectNode) item;
                if (StringUtils.isBlank(obj.path("disk_type").asText(""))) {
                    String inferred = inferDiskType(obj.path("url").asText(""));
                    if (StringUtils.isNotBlank(inferred)) {
                        obj.put("disk_type", inferred);
                    }
                }
            }
        }
        return checkPanSouLinks(request);
    }

    private String inferDiskType(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("magnet:")) {
            return "magnet";
        }
        if (lower.startsWith("ed2k:")) {
            return "ed2k";
        }
        String host;
        try {
            host = new URI(url).getHost();
        } catch (Exception e) {
            return null;
        }
        if (host == null) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        for (String[] entry : DISK_HOST_MAP) {
            if (host.contains(entry[0])) {
                return entry[1];
            }
        }
        return null;
    }

    public ObjectNode checkPanSouLinks(ObjectNode request) {        // Priority: dedicated 盘检地址 (PanCheck) > TG-Search > PanSou
        if (StringUtils.isNotBlank(appProperties.getPanCheckUrl())) {
            return checkViaPanCheck(request);
        }
        if (StringUtils.isNotBlank(appProperties.getTgSearch())) {
            return checkViaTgSearch(request);
        }
        return checkViaPanSou(request);
    }

    // PanCheck backend (see /home/harold/workspace/PanCheck): different contract —
    // req {links:[url...], selected_platforms:[...]}, resp bucketed by validity.
    private ObjectNode checkViaPanCheck(ObjectNode request) {
        ObjectNode panCheckReq = objectMapper.createObjectNode();
        ArrayNode links = panCheckReq.putArray("links");
        Set<String> platforms = new LinkedHashSet<>();
        if (request.has("items") && request.get("items").isArray()) {
            for (JsonNode item : request.get("items")) {
                if (item.has("url")) {
                    links.add(item.get("url").asText());
                }
                if (item.has("disk_type")) {
                    platforms.add(mapPanCheckPlatform(item.get("disk_type").asText()));
                }
            }
        }
        // send selected_platforms so PanCheck runs the checkers synchronously (realtime)
        ArrayNode selectedPlatforms = panCheckReq.putArray("selected_platforms");
        platforms.forEach(selectedPlatforms::add);
        String url = appProperties.getPanCheckUrl() + "/api/v1/links/check";
        ObjectNode response = restTemplate.postForObject(url, panCheckReq, ObjectNode.class);
        return normalizePanCheckResponse(response);
    }

    private String mapPanCheckPlatform(String diskType) {
        return switch (diskType) {
            case "123" -> "pan123";
            case "115" -> "pan115";
            case "mobile" -> "cmcc";
            default -> diskType;
        };
    }

    private ObjectNode normalizePanCheckResponse(ObjectNode response) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode results = result.putArray("results");
        if (response == null) {
            return result;
        }
        addPanCheckResults(results, response, "valid_links", "ok");
        addPanCheckResults(results, response, "invalid_links", "bad");
        addPanCheckResults(results, response, "locked_links", "locked");
        addPanCheckResults(results, response, "pending_links", "uncertain");
        return result;
    }

    private void addPanCheckResults(ArrayNode results, ObjectNode response, String field, String state) {
        if (response.has(field) && response.get(field).isArray()) {
            for (JsonNode link : response.get(field)) {
                results.addObject()
                        .put("url", link.asText())
                        .put("state", state)
                        .put("summary", getPanCheckSummary(state));
            }
        }
    }

    private String getPanCheckSummary(String state) {
        return switch (state) {
            case "ok" -> "链接有效";
            case "bad" -> "链接失效";
            case "locked" -> "链接受限";
            case "uncertain" -> "状态不确定";
            default -> state;
        };
    }

    // TG-Search exposes the same /api/check/links contract but wraps results under "data"
    // and authenticates via X-API-Key. Unwrap so downstream sees the canonical {results} shape.
    private ObjectNode checkViaTgSearch(ObjectNode request) {
        String url = appProperties.getTgSearch() + "/api/check/links";
        // Only TG-Search exposes a server-side check timeout; honor it when configured.
        Integer timeoutMs = appProperties.getPanCheckTimeoutMs();
        if (timeoutMs != null && timeoutMs > 0) {
            request.put("timeout_ms", timeoutMs);
        }
        HttpHeaders headers = new HttpHeaders();
        if (StringUtils.isNotBlank(appProperties.getTgSearchApiKey())) {
            headers.set("X-API-Key", appProperties.getTgSearchApiKey());
        }
        ObjectNode response = restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(request, headers), ObjectNode.class).getBody();
        if (response != null && response.has("data") && response.get("data").isObject()) {
            JsonNode data = response.get("data");
            if (data.has("results")) {
                ObjectNode normalized = objectMapper.createObjectNode();
                normalized.set("results", data.get("results"));
                return normalized;
            }
        }
        return response == null ? objectMapper.createObjectNode() : response;
    }

    private ObjectNode checkViaPanSou(ObjectNode request) {
        String url = appProperties.getPanSouUrl() + "/api/check/links";
        if (!shouldUsePanSouAuth()) {
            return restTemplate.postForObject(url, request, ObjectNode.class);
        }
        String token = getPanSouToken();
        if (StringUtils.isBlank(token)) {
            return restTemplate.postForObject(url, request, ObjectNode.class);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, headers), ObjectNode.class).getBody();
    }

    private boolean hasPanSouCredentials() {
        return StringUtils.isNoneBlank(appProperties.getPanSouUsername(), appProperties.getPanSouPassword());
    }

    private boolean shouldUsePanSouAuth() {
        refreshPanSouInfoIfUrlChanged();
        return hasPanSouCredentials() && Boolean.TRUE.equals(appProperties.getPanSouAuthEnabled());
    }

    private String getPanSouToken() {
        if (StringUtils.isNotBlank(panSouToken)) {
            return panSouToken;
        }
        Map<String, String> body = Map.of(
                "username", appProperties.getPanSouUsername(),
                "password", appProperties.getPanSouPassword());
        Map<?, ?> response;
        try {
            response = restTemplate.postForObject(appProperties.getPanSouUrl() + "/api/auth/login", body, Map.class);
        } catch (HttpClientErrorException.Forbidden e) {
            if (e.getResponseBodyAsString().contains("认证功能未启用")) {
                log.info("PanSou auth is disabled, use unauthenticated requests");
                appProperties.setPanSouAuthEnabled(false);
                return "";
            }
            throw e;
        }
        if (response == null || response.get("token") == null) {
            throw new IllegalStateException("PanSou login failed");
        }
        panSouToken = response.get("token").toString();
        return panSouToken;
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
            ObjectNode info = getPanSouInfo();
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

    private void addMergedMessages(Map<String, List<MergedLink>> mergedByType, String keyword, boolean offlineDownloadEnabled, List<Message> messages) {
        if (CollectionUtils.isEmpty(mergedByType)) {
            return;
        }
        List<String> tgDrivers = appProperties.getTgDrivers();
        for (var entry : mergedByType.entrySet()) {
//            if (!offlineDownloadEnabled && isOfflineDownloadType(entry.getKey())) {
//                continue;
//            }
            String messageType = getMessageType(entry.getKey());
            if (messageType == null || !isEnabledDriver(messageType, tgDrivers)) {
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

    private boolean isEnabledDriver(String messageType, List<String> tgDrivers) {
        return isOfflineDownloadType(messageType) || tgDrivers.isEmpty() || tgDrivers.contains(messageType);
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

    private String getPanSouCloudType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "0" -> "aliyun";
            case "1" -> "pikpak";
            case "2" -> "xunlei";
            case "3" -> "123";
            case "5" -> "quark";
            case "6" -> "mobile";
            case "7" -> "uc";
            case "8" -> "115";
            case "9" -> "tianyi";
            case "10" -> "baidu";
            case "12" -> "guangya";
            case "magnet" -> "magnet";
            case "ed2k" -> "ed2k";
            default -> null;
        };
    }

    private List<String> getPanSouCloudTypes() {
        return new ArrayList<>(appProperties.getTgDrivers().stream()
                .map(this::getPanSouCloudType)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList());
    }

    private List<String> getPanSouCloudTypes(boolean offlineDownloadEnabled) {
        List<String> types = new ArrayList<>(appProperties.getTgDrivers().stream()
                .map(this::getPanSouCloudType)
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
