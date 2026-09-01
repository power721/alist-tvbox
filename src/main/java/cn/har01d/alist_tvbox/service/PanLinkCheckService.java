package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 盘检服务:网盘分享链接有效性校验。后端三选一 —— 专用盘检地址(PanCheck)&gt; TG-Search &gt; PanSou,
 * 统一归一化成 {results:[{url,state,summary}]} 契约(state: ok/bad/locked/uncertain)。
 * 消费方:TG 聚合/PanSou 搜索结果过滤(RemoteSearchService)、追剧候选池站点源统一送检
 * (MediaSubscriptionCheckService.searchAllSources)、插件/爬虫 HTTP 入口
 * (RemoteSearchController /check-links)。{@link #filterInvalidPanSouLinks} 负责选样与
 * 结果改写:bad/uncertain 剔除、ok/locked 盖 validityState/validitySummary 供候选池准入消费;
 * 可检链接超过全局阈值时按盘类型各取排序在前的前 N 条送检(主网盘头部分享照常被预检),
 * 未选中的原样保留。
 */
@Slf4j
@Service
public class PanLinkCheckService {
    private static final String CHECK_STATE_OK = "ok";
    private static final String CHECK_STATE_BAD = "bad";
    private static final String CHECK_STATE_UNCERTAIN = "uncertain";
    private static final String CHECK_STATE_RATE_LIMITED = "rate_limited";
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
    private final PanSouClient panSouClient;

    public PanLinkCheckService(AppProperties appProperties, RestTemplateBuilder restTemplateBuilder,
                               ObjectMapper objectMapper, PanSouClient panSouClient) {
        this.appProperties = appProperties;
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
        this.panSouClient = panSouClient;
    }

    public ObjectNode checkPanSouLinks(ObjectNode request) {        // Priority: dedicated 盘检地址 (PanCheck) > TG-Search > PanSou
        ObjectNode response;
        if (StringUtils.isNotBlank(appProperties.getPanCheckUrl())) {
            response = checkViaPanCheck(request);
        } else if (StringUtils.isNotBlank(appProperties.getTgSearch())) {
            response = checkViaTgSearch(request);
        } else {
            response = checkViaPanSou(request);
        }
        return demoteSuspectedBaiduRateLimit(request, response);
    }

    /**
     * 百度批量异常降级:后端(PanCheck/tg-search/pansou 均为第三方)把限流链接混进
     * invalid/uncertain 且无法逐条区分。一批送检里百度 bad/uncertain 占比 >=80% 且 >=5 条,
     * 与 IP 级风控(-62/-65)形态一致而与真死链分布不符,按疑似限流降级为 rate_limited:
     * 只标注不剔除,防止限流期间追剧候选池的百度源被整体误杀。
     */
    private ObjectNode demoteSuspectedBaiduRateLimit(ObjectNode request, ObjectNode response) {
        if (response == null || !response.has("results") || !response.get("results").isArray()) {
            return response;
        }
        Set<String> baiduUrls = new LinkedHashSet<>();
        if (request != null && request.has("items") && request.get("items").isArray()) {
            for (JsonNode item : request.get("items")) {
                if ("baidu".equals(item.path("disk_type").asText("")) && item.has("url")) {
                    baiduUrls.add(item.get("url").asText());
                }
            }
        }
        List<ObjectNode> demotable = new ArrayList<>();
        for (JsonNode result : response.get("results")) {
            if (result.isObject() && baiduUrls.contains(result.path("url").asText())) {
                String state = result.path("state").asText();
                if (CHECK_STATE_BAD.equals(state) || CHECK_STATE_UNCERTAIN.equals(state)) {
                    demotable.add((ObjectNode) result);
                }
            }
        }
        if (baiduUrls.size() >= 5 && !demotable.isEmpty()
                && demotable.size() * 5 >= baiduUrls.size() * 4) {
            log.warn("百度链接批量异常({}/{} bad/uncertain),疑似网盘限流,降级为保留待复查", demotable.size(), baiduUrls.size());
            for (JsonNode result : demotable) {
                ((ObjectNode) result).put("state", CHECK_STATE_RATE_LIMITED);
                ((ObjectNode) result).put("summary", "检测受限(疑似网盘限流),保留待复查");
            }
        }
        return response;
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

    public List<Message> filterInvalidPanSouLinks(List<Message> messages) {
        if (!appProperties.isPanSouLinkCheckEnabled() || messages.isEmpty()) {
            return messages;
        }
        List<Message> checkable = selectCheckable(messages);
        log.debug("filterInvalidPanSouLinks totla={} checkable={} threashold={}", messages.size(), checkable.size(), appProperties.getPanSouLinkCheckMaxCount());
        if (checkable.isEmpty()) {
            return messages;
        }
        List<Message> toCheck = checkable.size() > appProperties.getPanSouLinkCheckMaxCount()
                ? selectPanSouCheckCandidates(checkable)
                : checkable;
        if (toCheck.isEmpty()) {
            return messages;
        }

        Map<String, String> states = new java.util.HashMap<>();
        Map<String, String> summaries = new java.util.HashMap<>();
        long startedAt = System.currentTimeMillis();
        ObjectNode response = null;
        try {
            response = checkPanSouLinks(buildPanSouLinkCheckRequest(toCheck));
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
        logPanSouLinkCheck(toCheck, states, startedAt);
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

    List<Message> selectCheckable(List<Message> messages) {
        Set<String> enabledLinkCheckTypes = getEnabledLinkCheckTypes();
        return messages.stream()
                .filter(message -> !isOfflineDownloadType(message.getType()))
                .filter(message -> StringUtils.isNotBlank(PanSouClient.cloudType(message.getType())))
                .filter(message -> enabledLinkCheckTypes.contains(PanSouClient.cloudType(message.getType())))
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

    /**
     * 可检链接总数超过全局阈值时的降级选样:按盘类型各取排序在前的前 N 条送检,
     * 总量仍受全局阈值约束,保证各盘(尤其主网盘如百度/夸克)的头部分享照常被预检,
     * 未选中的链接不参与检测、原样保留在结果里。
     */
    private List<Message> selectPanSouCheckCandidates(List<Message> checkable) {
        int budget = appProperties.getPanSouLinkCheckMaxCount();
        int perTypeLimit = appProperties.getPanSouLinkCheckMaxPerTypeCount();
        Map<String, Integer> takenByType = new java.util.HashMap<>();
        List<Message> selected = new ArrayList<>();
        for (Message message : checkable) {
            if (selected.size() >= budget) {
                break;
            }
            String type = PanSouClient.cloudType(message.getType());
            int taken = takenByType.getOrDefault(type, 0);
            if (taken >= perTypeLimit) {
                continue;
            }
            takenByType.put(type, taken + 1);
            selected.add(message);
        }
        log.debug("filterInvalidPanSouLinks over threashold, per-type sample {} of {} (perTypeLimit={})",
                selected.size(), checkable.size(), perTypeLimit);
        return selected;
    }

    private boolean isInvalidPanSouCheckState(String state) {
        return CHECK_STATE_BAD.equals(state) || CHECK_STATE_UNCERTAIN.equals(state);
    }

    private String getPanSouLinkStateSummary(String state) {
        if ("locked".equals(state)) {
            return "链接受限";
        }
        if (CHECK_STATE_RATE_LIMITED.equals(state)) {
            return "检测受限(网盘限流)";
        }
        return "链接有效";
    }

    private ObjectNode buildPanSouLinkCheckRequest(List<Message> messages) {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode items = request.putArray("items");
        for (Message message : messages) {
            items.addObject()
                    .put("disk_type", PanSouClient.cloudType(message.getType()))
                    .put("url", message.getLink());
        }
        request.put("view_token", "pansou-search-" + System.currentTimeMillis());
        return request;
    }

    private void logPanSouLinkCheck(List<Message> checkable, Map<String, String> states, long startedAt) {
        Map<String, int[]> stats = new LinkedHashMap<>();
        int totalValid = 0;
        for (Message message : checkable) {
            String type = PanSouClient.cloudType(message.getType());
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
        // 限流分桶:被网盘风控拦下的链接状态未知,只标注不剔除,防止限流期间整源被误杀
        addPanCheckResults(results, response, "rate_limited_links", CHECK_STATE_RATE_LIMITED);
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
            case CHECK_STATE_RATE_LIMITED -> "检测受限(网盘限流),保留待复查";
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
        return panSouClient.post(appProperties.getPanSouUrl() + "/api/check/links", request, ObjectNode.class);
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

    private static boolean isOfflineDownloadType(String type) {
        return "magnet".equals(type) || "ed2k".equals(type);
    }
}
