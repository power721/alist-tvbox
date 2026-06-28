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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RemoteSearchService {
    private static final String CHECK_STATE_BAD = "bad";
    private static final String CHECK_STATE_UNCERTAIN = "uncertain";

    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TelegramChannelRepository telegramChannelRepository;
    private final ShareService shareService;
    private final TvBoxService tvBoxService;
    private final OfflineDownloadService offlineDownloadService;
    private List<String> panSouDefaultChannels;
    private List<String> panSouBuiltinChannels;
    private String panSouToken;
    private String checkedPanSouUrl;
    // carries the search-result title from search() to detail() so the resolved
    // storage folder name (often an obfuscated share token) does not overwrite it.
    private final Cache<String, String> shareTitle = Caffeine.newBuilder().maximumSize(200).expireAfterWrite(Duration.ofHours(2)).build();

    public RemoteSearchService(AppProperties appProperties,
                               RestTemplateBuilder restTemplateBuilder,
                               ObjectMapper objectMapper,
                               TelegramChannelRepository telegramChannelRepository,
                               ShareService shareService,
                               TvBoxService tvBoxService,
                               OfflineDownloadService offlineDownloadService) {
        this.appProperties = appProperties;
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
        this.telegramChannelRepository = telegramChannelRepository;
        this.shareService = shareService;
        this.tvBoxService = tvBoxService;
        this.offlineDownloadService = offlineDownloadService;
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

        var messages = search(keyword, channels);
        for (var message : messages) {
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
            list.add(movieDetail);
        }

        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        long end = System.currentTimeMillis();
        log.info("Search {} get {} results from PanSou elapsed {} ms.", keyword, result.getTotal(), end - start);
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

    public List<Message> search(String keyword, List<String> channels) {
        var request = new SearchRequest(keyword, getSearchChannels(channels), appProperties.getPanSouSource());
        request.setExt(Map.of("referer", "https://dm.xueximeng.com"));
        boolean offlineDownloadEnabled = offlineDownloadService.getConfig().enabled();
        if (StringUtils.isNotBlank(keyword)) {
//            request.setFilter(new SearchRequest.Filter(List.of(keyword), List.of()));
            request.setCloudTypes(getPanSouCloudTypes());
        }
        if (!CollectionUtils.isEmpty(appProperties.getPanSouPlugins())) {
            request.setPlugins(appProperties.getPanSouPlugins());
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

    public List<Message> filterInvalidPanSouLinks(List<Message> messages) {
        if (!appProperties.isPanSouLinkCheckEnabled() || messages.isEmpty()) {
            return messages;
        }
        List<Message> checkable = messages.stream()
                .filter(message -> !isOfflineDownloadType(message.getType()))
                .filter(message -> StringUtils.isNotBlank(getPanSouCloudType(message.getType())))
                .toList();
        log.debug("filterInvalidPanSouLinks totla={} checkable={} threashold={}", messages.size(), checkable.size(), appProperties.getPanSouLinkCheckMaxCount());
        if (checkable.isEmpty() || checkable.size() > appProperties.getPanSouLinkCheckMaxCount()) {
            return messages;
        }

        Map<String, String> states = new java.util.HashMap<>();
        Map<String, String> summaries = new java.util.HashMap<>();
        Map<String, List<Message>> groups = checkable.stream()
                .collect(Collectors.groupingBy(message -> getPanSouCloudType(message.getType())));
        int batchSize = 10;
        List<CompletableFuture<ObjectNode>> futures = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            String diskType = entry.getKey();
            List<Message> all = entry.getValue();
            int batchTotal = (all.size() + batchSize - 1) / batchSize;
            for (int i = 0; i < all.size(); i += batchSize) {
                final List<Message> batch = all.subList(i, Math.min(i + batchSize, all.size()));
                final int batchIndex = i / batchSize;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    long startedAt = System.currentTimeMillis();
                    try {
                        ObjectNode response = checkPanSouLinks(buildPanSouLinkCheckRequest(diskType, batch));
                        logPanSouLinkCheck(diskType + (batchTotal > 1 ? "[" + batchIndex + "]" : ""), batch.size(), response, startedAt);
                        return response;
                    } catch (Exception e) {
                        log.warn("check PanSou search links failed: {} batch {}", diskType, batchIndex, e);
                        return null;
                    }
                }));
            }
        }
        for (CompletableFuture<ObjectNode> future : futures) {
            ObjectNode response = future.join();
            if (response == null || !response.has("results") || !response.get("results").isArray()) {
                continue;
            }
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

    private ObjectNode buildPanSouLinkCheckRequest(String diskType, List<Message> messages) {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode items = request.putArray("items");
        for (Message message : messages) {
            items.addObject()
                    .put("disk_type", diskType)
                    .put("url", message.getLink());
        }
        request.put("view_token", "pansou-search-" + diskType + "-" + System.currentTimeMillis());
        return request;
    }

    private void logPanSouLinkCheck(String diskType, int inputCount, ObjectNode response, long startedAt) {
        long validCount = 0;
        if (response != null && response.has("results") && response.get("results").isArray()) {
            for (var result : response.get("results")) {
                if (result.has("state") && "ok".equals(result.get("state").asText())) {
                    validCount++;
                }
            }
        }
        log.info("检测{}网盘链接{}条，{}条有效，耗时{}ms", diskType, inputCount, validCount, System.currentTimeMillis() - startedAt);
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

    public ObjectNode checkPanSouLinks(ObjectNode request) {
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
