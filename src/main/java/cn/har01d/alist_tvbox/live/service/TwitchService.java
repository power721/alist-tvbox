package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Twitch 直播。走网页端公开 GQL 持久化查询(无需登录),播放地址由 usher HLS 接口获取,
 * 播放地址裸请求可播,无需代理。persisted query 的 sha256Hash 由 Twitch 不定期轮换,失效时需同步更新。
 */
@Slf4j
@Service
public class TwitchService implements LivePlatform {
    private static final String GQL_URL = "https://gql.twitch.tv/gql";
    private static final String USHER_URL = "https://usher.ttvnw.net/api/channel/hls/";
    private static final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    private static final String HASH_DIRECTORIES = "2f67f71ba89f3c0ed26a141ec00da1defecb2303595f5cda4298169549783d9e";
    private static final String HASH_DIRECTORY = "76cb069d835b8a02914c08dc42c421d0dafda8af5b113a3f19141824b901402f";
    private static final String HASH_ACCESS_TOKEN = "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9";
    private static final String HASH_CHANNEL_SHELL = "fea4573a7bf2644f5b3f2cbbdcbee0d17312e48d2e55f080589d053aad353f11";
    private static final String HASH_STREAM_METADATA = "b57f9b910f8cd1a4659d894fe7550ccc81ec9052c01e438b290fd66a040b9b93";
    private static final String HASH_SEARCH = "7f3580f6ac6cd8aa1424cff7c974a07143827d6fa36bba1b54318fe7f0b68dc5";
    private static final String[] PLAY_SESSION_IDS = {"bdd22331a986c7f1073628f2fc5b19da", "064bc3ff1722b6f53b0b5b8c01e46ca5"};

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LiveProxyService liveProxyService;
    // 关注状态刷新会绕过 LiveService 缓存直接调 detail,内部缓存挡住重复的 GQL/usher 请求
    private final Cache<String, MovieList> detailCache = Caffeine.newBuilder()
            .maximumSize(50)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public TwitchService(RestTemplateBuilder builder, ObjectMapper objectMapper, LiveProxyService liveProxyService) {
        this.restTemplate = builder
                .defaultHeader("User-Agent", Constants.USER_AGENT)
                .defaultHeader("Client-Id", CLIENT_ID)
                .build();
        this.objectMapper = objectMapper;
        this.liveProxyService = liveProxyService;
    }

    @Override
    public String getType() {
        return "twitch";
    }

    @Override
    public String getName() {
        return "Twitch";
    }

    @Override
    public MovieList home() throws IOException {
        MovieList result = new MovieList();
        // 全站热门游戏各取头部直播间,交错排列
        JsonNode response = gql(request("BrowsePage_AllDirectories", HASH_DIRECTORIES, variables(
                "limit", 6,
                "options", options())));
        List<String> slugs = new ArrayList<>();
        for (JsonNode edge : response.path("data").path("directoriesWithTags").path("edges")) {
            slugs.add(edge.path("node").path("slug").asText());
        }

        List<Map<String, Object>> queries = new ArrayList<>();
        for (String slug : slugs) {
            queries.add(request("DirectoryPage_Game", HASH_DIRECTORY, directoryVariables(slug, 5)));
        }
        JsonNode responses = gql(queries);

        List<List<MovieDetail>> rooms = new ArrayList<>();
        for (JsonNode item : responses) {
            rooms.add(convertRooms(item.path("data").path("game").path("streams").path("edges")));
        }
        List<MovieDetail> list = interleave(rooms);
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        log.debug("home result: {}", result);
        return result;
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        JsonNode response = gql(request("BrowsePage_AllDirectories", HASH_DIRECTORIES, variables(
                "limit", 100,
                "options", options())));
        for (JsonNode edge : response.path("data").path("directoriesWithTags").path("edges")) {
            JsonNode node = edge.path("node");
            Category category = new Category();
            category.setType_id(getType() + "-" + node.path("slug").asText());
            category.setType_name(node.path("displayName").asText());
            category.setType_flag(0);
            category.setCover(node.path("avatarURL").asText());
            list.add(category);
        }

        result.setCategories(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        log.debug("category result: {}", result);
        return result;
    }

    @Override
    public MovieList list(String id, String ac, String sort, Integer pg) throws IOException {
        String slug = id.split("-", 2)[1];
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        // 匿名 GQL 的 cursor 翻页会返回空(带 token 才可用),单次拉 100 条一页展示,与 CcService 模式一致
        if (pg == null || pg == 1) {
            JsonNode edges = gql(request("DirectoryPage_Game", HASH_DIRECTORY, directoryVariables(slug, 100)))
                    .path("data").path("game").path("streams").path("edges");
            list.addAll(convertRooms(edges));
        }

        result.setList(list);
        result.setPage(pg);
        result.setTotal(list.size());
        result.setLimit(list.size());

        log.debug("list result: {}", result);
        return result;
    }

    @Override
    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        JsonNode response = gql(request("SearchResultsPage_SearchResults", HASH_SEARCH, variables(
                "platform", "web",
                "query", wd,
                "options", variables("targets", null, "shouldSkipDiscoveryControl", false),
                "requestID", "808c9f2e-f52e-431c-8dc7-d2e3c1831d77",
                "includeIsDJ", true)));
        for (JsonNode edge : response.path("data").path("searchFor").path("channels").path("edges")) {
            JsonNode item = edge.path("item");
            JsonNode stream = item.path("stream");
            boolean live = !stream.isMissingNode() && !stream.isNull();
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + item.path("login").asText());
            String title = item.path("broadcastSettings").path("title").asText("");
            detail.setVod_name(title.isEmpty() ? item.path("displayName").asText() : title);
            detail.setVod_pic(stream.path("previewImageURL").asText(item.path("profileImageURL").asText()));
            detail.setVod_actor(item.path("displayName").asText());
            detail.setVod_remarks(live ? playCount(stream.path("viewersCount").asInt(0)) : "未开播");
            list.add(detail);
        }

        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        log.debug("search result: {}", result);
        return result;
    }

    @Override
    public MovieList detail(String tid, String client) throws IOException {
        // 网页端与客户端的播放地址形态不同(代理 vs 直连),缓存键需带 client
        String cacheKey = tid + "@" + (client == null ? "" : client);
        MovieList cached = detailCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        String login = tid.split("\\$")[1];
        MovieList result = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);

        try {
            List<Map<String, Object>> queries = List.of(
                    request("ChannelShell", HASH_CHANNEL_SHELL, variables("login", login)),
                    request("StreamMetadata", HASH_STREAM_METADATA, variables(
                            "channelLogin", login, "includeIsDJ", true)));
            JsonNode responses = gql(queries);
            JsonNode userOrError = responses.get(0).path("data").path("userOrError");
            JsonNode user = responses.get(1).path("data").path("user");

            JsonNode stream = user.path("stream");
            boolean live = "live".equals(stream.path("type").asText(""));
            String game = stream.path("game").path("displayName").asText(stream.path("game").path("name").asText(""));

            String title = user.path("lastBroadcast").path("title").asText("");
            String displayName = userOrError.path("displayName").asText(login);
            detail.setVod_name(title.isEmpty() ? displayName : title);
            detail.setVod_pic(userOrError.path("profileImageURL").asText(""));
            detail.setVod_actor(displayName);
            detail.setType_name(game);
            detail.setVod_remarks(live ? (game.isEmpty() ? "直播中" : game) : "未开播");
            if (live) {
                parsePlayUrls(detail, login, client);
            }
        } catch (Exception e) {
            log.warn("get twitch room detail failed: {}", tid, e);
            detail.setVod_remarks("未开播");
        }

        result.getList().add(detail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        detailCache.put(cacheKey, result);
        return result;
    }

    private void parsePlayUrls(MovieDetail detail, String login, String client) throws IOException {
        JsonNode token = gql(request("PlaybackAccessToken", HASH_ACCESS_TOKEN, variables(
                "isLive", true,
                "login", login,
                "isVod", false,
                "vodID", "",
                "playerType", "site",
                "isClip", false,
                "clipID", "",
                "platform", "site")))
                .path("data").path("streamPlaybackAccessToken");
        String value = token.path("value").asText();
        String signature = token.path("signature").asText();

        StringJoiner query = new StringJoiner("&");
        // 参数集必须与网页播放器一致:缺 play_session_id/player_version 等"无关"参数时,
        // usher 会下发只含初始 15 个分片(约 30 秒)且不再追加的降级清单,表现为播放 30 秒即断
        Map<String, String> params = new LinkedHashMap<>();
        params.put("acmb", "e30=");
        params.put("allow_source", "true");
        params.put("cdm", "wv");
        params.put("fast_bread", "true");
        params.put("p", String.valueOf(System.currentTimeMillis() / 1000));
        params.put("platform", "web");
        params.put("play_session_id", PLAY_SESSION_IDS[ThreadLocalRandom.current().nextInt(PLAY_SESSION_IDS.length)]);
        params.put("player_backend", "mediaplayer");
        params.put("player_version", "1.28.0-rc.1");
        params.put("playlist_include_framerate", "true");
        params.put("reassignments_supported", "true");
        params.put("sig", signature);
        params.put("token", value);
        params.put("transcode_mode", "cbr_v1");
        params.forEach((k, v) -> query.add(k + "=" + encode(v)));

        String url = USHER_URL + login + ".m3u8?" + query;
        String content = restTemplate.getForObject(URI.create(url), String.class);

        // master 清单里清晰度名在 #EXT-X-STREAM-INF 的 VIDEO 属性,下一行是变体地址;
        // 变体顺序不保证按码率排,按 BANDWIDTH 降序保证默认线路是最高清晰度
        record Variant(long bandwidth, String quality, String url) {
        }
        List<Variant> variants = new ArrayList<>();
        String quality = null;
        long bandwidth = 0;
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                int start = line.indexOf("VIDEO=\"");
                quality = start < 0 ? "" : line.substring(start + 7, line.indexOf('"', start + 7));
                start = line.indexOf("BANDWIDTH=");
                bandwidth = start < 0 ? 0 : Long.parseLong(line.substring(start + 10).replaceAll("[^0-9].*", ""));
            } else if (!line.isEmpty() && !line.startsWith("#") && quality != null) {
                if ("chunked".equals(quality)) {
                    quality = "原画";
                }
                if (!"audio_only".equals(quality)) {
                    // 清单域名对带 Origin/Referer 的浏览器请求 403,仅网页端经代理;安卓客户端直连 CDN
                    String variantUrl = "web".equals(client) ? liveProxyService.buildProxyUrl(line) : line;
                    variants.add(new Variant(bandwidth, quality, variantUrl));
                }
                quality = null;
            }
        }
        variants.sort(Comparator.comparingLong(Variant::bandwidth).reversed());
        List<String> urls = variants.stream().map(v -> v.quality() + "$" + v.url()).toList();

        if (!urls.isEmpty()) {
            detail.setVod_play_from("线路1");
            detail.setVod_play_url(String.join("#", urls));
        }
    }

    private List<MovieDetail> convertRooms(JsonNode edges) {
        List<MovieDetail> list = new ArrayList<>();
        for (JsonNode edge : edges) {
            JsonNode node = edge.path("node");
            JsonNode broadcaster = node.path("broadcaster");
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + broadcaster.path("login").asText());
            detail.setVod_name(node.path("title").asText());
            detail.setVod_pic(node.path("previewImageURL").asText(""));
            detail.setVod_actor(broadcaster.path("displayName").asText());
            detail.setVod_remarks(playCount(node.path("viewersCount").asInt(0)));
            list.add(detail);
        }
        return list;
    }

    private List<MovieDetail> interleave(List<List<MovieDetail>> rooms) {
        List<MovieDetail> list = new ArrayList<>();
        int max = rooms.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < max; i++) {
            for (List<MovieDetail> room : rooms) {
                if (i < room.size()) {
                    list.add(room.get(i));
                }
            }
        }
        return list;
    }

    private Map<String, Object> directoryVariables(String slug, int limit) {
        Map<String, Object> variables = variables(
                "imageWidth", 50,
                "slug", slug,
                "options", options(),
                "sortTypeIsRecency", false,
                "limit", limit,
                "includeCostreaming", true);
        return variables;
    }

    private Map<String, Object> options() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("sort", "VIEWER_COUNT");
        options.put("recommendationsContext", Map.of("platform", "web"));
        options.put("requestID", "JIRA-VXP-2397");
        options.put("freeformTags", null);
        options.put("tags", List.of());
        return options;
    }

    private JsonNode gql(Object query) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(query), headers);
        return restTemplate.postForObject(GQL_URL, entity, JsonNode.class);
    }

    private Map<String, Object> request(String operation, String hash, Map<String, Object> variables) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("operationName", operation);
        query.put("extensions", Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", hash)));
        query.put("variables", variables);
        return query;
    }

    private Map<String, Object> variables(Object... keyValue) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            map.put((String) keyValue[i], keyValue[i + 1]);
        }
        return map;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
