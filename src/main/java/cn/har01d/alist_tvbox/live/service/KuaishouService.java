package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.live.model.*;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KuaishouService implements LivePlatform {
    private final Map<String, String> categoryMap = new HashMap<>();
    // 快手房间页对无 cookie 请求限流("请求过快"), 需携带 did 等 cookie 并注册设备
    private final Map<String, String> cookieStore = new ConcurrentHashMap<>();
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final String CATEGORY_API = "https://live.kuaishou.com/live_api/category/data";
    private static final String GAME_BOARD_API = "https://live.kuaishou.com/live_api/gameboard/list";
    private static final String NON_GAME_BOARD_API = "https://live.kuaishou.com/live_api/non-gameboard/list";
    private static final String HOME_LIST_API = "https://live.kuaishou.com/live_api/home/list";
    private static final String ROOM_PAGE_API = "https://live.kuaishou.com/u/";
    private static final String SITE_URL = "https://live.kuaishou.com/";
    private static final String DID_REGISTER_API = "https://log-sdk.ksapisrv.com/rest/wd/common/log/collect/misc2?v=3.9.49&kpn=KS_GAME_LIVE_PC";
    private static final Pattern INITIAL_STATE_PATTERN = Pattern.compile("window\\.__INITIAL_STATE__=(.*?);", Pattern.DOTALL);

    private static final List<String> IMAGE_EXTENSIONS = Arrays.asList(
            "svgz", "pjp", "png", "ico", "avif", "tiff", "tif", "jfif",
            "svg", "xbm", "pjpeg", "webp", "jpg", "jpeg", "bmp", "gif"
    );

    public KuaishouService(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder
                .defaultHeader("User-Agent", Constants.USER_AGENT)
                .defaultHeader("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3")
                .defaultHeader("connection", "keep-alive")
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getType() {
        return "ks";
    }

    @Override
    public String getName() {
        return "快手";
    }

    @Override
    public MovieList home() throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        try {
            String url = HOME_LIST_API;
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders()),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode dataList = root.path("data").path("list");

            for (JsonNode item : dataList) {
                JsonNode gameLiveInfoList = item.path("gameLiveInfo");
                for (JsonNode sitem : gameLiveInfoList) {
                    JsonNode liveInfoList = sitem.path("liveInfo");
                    for (JsonNode titem : liveInfoList) {
                        MovieDetail detail = new MovieDetail();
                        JsonNode author = titem.path("author");
                        JsonNode gameInfo = titem.path("gameInfo");

                        detail.setVod_id(getType() + "$" + author.path("id").asText());
                        detail.setVod_name(author.path("name").asText());
                        detail.setVod_pic(gameInfo.path("poster").asText());
                        detail.setVod_remarks(playCount(parseWatchingCount(titem.path("watchingCount"))));
                        list.add(detail);

                        if (list.size() >= 30) {
                            break;
                        }
                    }
                    if (list.size() >= 30) {
                        break;
                    }
                }
                if (list.size() >= 30) {
                    break;
                }
            }
        } catch (Exception e) {
            log.error("快手首页获取失败", e);
        }

        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("快手home result: {}", result);
        return result;
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        String[][] categories = {
                {"1", "热门"},
                {"2", "网游"},
                {"3", "单机"},
                {"4", "手游"},
                {"5", "棋牌"},
                {"6", "娱乐"},
                {"7", "综合"},
                {"8", "文化"}
        };

        for (String[] cat : categories) {
            String catId = cat[0];
            String catName = cat[1];

            try {
                int page = 1;
                int pageSize = 30;
                String url = CATEGORY_API + "?type=" + catId + "&page=" + page + "&size=" + pageSize;

                ResponseEntity<String> response = restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        new HttpEntity<>(createHeaders()),
                        String.class
                );

                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode subList = root.path("data").path("list");

                for (JsonNode item : subList) {
                    String subId = item.path("id").asText();
                    String subName = item.path("name").asText();
                    String poster = item.path("poster").asText();

                    Category category = new Category();
                    category.setType_id(getType() + "-" + subId);
                    category.setType_name(catName + " - " + subName);
                    category.setType_flag(0);
                    category.setCover(poster);
                    categoryMap.put(category.getType_id(), subId);
                    list.add(category);
                }
            } catch (Exception e) {
                log.error("快手分类获取失败: {}", catName, e);
            }
        }

        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("快手category result: {}", result);
        return result;
    }

    @Override
    public MovieList list(String id, String ac, String sort, Integer pg) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        if (categoryMap.isEmpty()) {
            category();
        }

        String gameId = categoryMap.get(id);
        if (gameId == null) {
            return result;
        }

        try {
            boolean isGameBoard = gameId.length() < 7;
            String apiUrl = isGameBoard ? GAME_BOARD_API : NON_GAME_BOARD_API;
            String url = apiUrl + "?filterType=0&pageSize=30&gameId=" + gameId + "&page=" + pg;

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders()),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode roomList = root.path("data").path("list");

            for (JsonNode item : roomList) {
                JsonNode author = item.path("author");
                JsonNode gameInfo = item.path("gameInfo");

                MovieDetail detail = new MovieDetail();
                detail.setVod_id(getType() + "$" + author.path("id").asText());
                detail.setVod_name(item.path("caption").asText());
                String poster = item.path("poster").asText();
                detail.setVod_pic(isImage(poster) ? poster : poster + ".jpg");
                detail.setVod_remarks(author.path("name").asText() + " - " + playCount(parseWatchingCount(item.path("watchingCount"))));
                list.add(detail);
            }
        } catch (Exception e) {
            log.error("快手房间列表获取失败: {}", id, e);
        }

        result.setList(list);
        result.setPage(pg);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        result.setPagecount(list.size() >= 20 ? pg + 1 : pg);

        log.debug("快手list result: {}", result);
        return result;
    }

    @Override
    public MovieList search(String wd) throws IOException {
        // 快手无法搜索主播，只能搜索游戏分类
        return new MovieList();
    }

    @Override
    public MovieList detail(String tid, String client) throws IOException {
        String[] parts = tid.split("\\$");
        String roomId = parts[1];

        MovieList result = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);

        try {
            String url = ROOM_PAGE_API + roomId;
            if (cookieStore.isEmpty()) {
                refreshSession();
            }

            JsonNode firstPlay = fetchFirstPlay(url);
            if (firstPlay != null && firstPlay.has("errorType")) {
                log.warn("快手房间详情被限流(请求过快), 刷新会话重试: {}", roomId);
                refreshSession();
                firstPlay = fetchFirstPlay(url);
            }

            if (firstPlay != null && !firstPlay.has("errorType")) {
                JsonNode liveStream = firstPlay.path("liveStream");
                JsonNode author = firstPlay.path("author");
                JsonNode gameInfo = firstPlay.path("gameInfo");
                boolean isLiving = firstPlay.path("isLiving").asBoolean();

                detail.setVod_name(author.path("name").asText());
                detail.setVod_pic(isImage(liveStream.path("poster").asText()) ?
                        liveStream.path("poster").asText() :
                        liveStream.path("poster").asText() + ".jpg");
                detail.setVod_actor(author.path("name").asText());
                detail.setType_name(gameInfo.path("name").asText());
                detail.setVod_remarks(playCount(isLiving ? parseWatchingCount(gameInfo.path("watchingCount")) : 0));
                detail.setVod_content(author.path("description").asText());

                if (isLiving) {
                    JsonNode playUrls = liveStream.path("playUrls");
                    parsePlayUrls(detail, playUrls);
                }
            }
        } catch (Exception e) {
            log.error("快手房间详情获取失败: {}", roomId, e);
        }

        result.getList().add(detail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("快手detail: {}", result);
        return result;
    }

    private void parsePlayUrls(MovieDetail movieDetail, JsonNode playUrls) {
        try {
            List<String> playFrom = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();

            JsonNode adaptationSet = playUrls.path("h264").path("adaptationSet");
            JsonNode representations = adaptationSet.path("representation");

            if (representations.isArray()) {
                List<JsonNode> qualityList = new ArrayList<>();
                representations.forEach(qualityList::add);
                // 按level降序排序
                qualityList.sort((a, b) -> {
                    int levelA = a.path("level").asInt();
                    int levelB = b.path("level").asInt();
                    return Integer.compare(levelB, levelA);
                });

                List<String> urls = new ArrayList<>();
                for (JsonNode quality : qualityList) {
                    String name = quality.path("name").asText();
                    String url = quality.path("url").asText();
                    urls.add(name + "$" + url);
                }

                playFrom.add("原画");
                playUrlList.add(String.join("#", urls));
            }

            movieDetail.setVod_play_from(String.join("$$$", playFrom));
            movieDetail.setVod_play_url(String.join("$$$", playUrlList));
        } catch (Exception e) {
            log.error("快手播放URL解析失败", e);
        }
    }

    private JsonNode fetchFirstPlay(String url) throws IOException {
        HttpHeaders headers = createHeaders();
        headers.set("User-Agent", getRandomUserAgent());
        String cookie = buildCookieHeader();
        if (!cookie.isEmpty()) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        captureCookies(response);

        String html = response.getBody();
        if (html == null) {
            return null;
        }
        Matcher matcher = INITIAL_STATE_PATTERN.matcher(html);
        if (!matcher.find()) {
            log.warn("快手房间页缺少 __INITIAL_STATE__");
            return null;
        }
        String jsonText = matcher.group(1).replace("undefined", "null");
        JsonNode playList = objectMapper.readTree(jsonText).path("liveroom").path("playList");
        if (playList.isArray() && playList.size() > 0) {
            return playList.get(0);
        }
        return null;
    }

    private synchronized void refreshSession() {
        try {
            HttpHeaders headers = createHeaders();
            ResponseEntity<String> response = restTemplate.exchange(
                    SITE_URL,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );
            captureCookies(response);
            registerDid();
            log.info("快手会话已刷新: {}", cookieStore.keySet());
        } catch (Exception e) {
            log.warn("快手会话刷新失败", e);
        }
    }

    private void registerDid() {
        String did = cookieStore.get("did");
        if (did == null) {
            return;
        }
        try {
            Map<String, Object> h5Attrs = new LinkedHashMap<>();
            h5Attrs.put("sdk_name", "webLogger");
            h5Attrs.put("sdk_version", "3.9.49");
            h5Attrs.put("sdk_bundle", "log.common.js");
            h5Attrs.put("app_version_name", "");
            h5Attrs.put("host_product", "");
            h5Attrs.put("resolution", "1600x900");
            h5Attrs.put("screen_with", 1600);
            h5Attrs.put("screen_height", 900);
            h5Attrs.put("device_pixel_ratio", 1);
            h5Attrs.put("domain", SITE_URL);

            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("device_id", did);
            identity.put("global_id", "");
            Map<String, Object> app = new LinkedHashMap<>();
            app.put("language", "zh-CN");
            app.put("platform", 10);
            app.put("container", "WEB");
            app.put("product_name", "KS_GAME_LIVE_PC");
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("os_version", "NT 6.1");
            device.put("model", "Windows");
            device.put("ua", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36");
            Map<String, Object> network = new LinkedHashMap<>();
            network.put("type", 3);

            Map<String, Object> common = new LinkedHashMap<>();
            common.put("identity_package", identity);
            common.put("app_package", app);
            common.put("device_package", device);
            common.put("need_encrypt", "false");
            common.put("network_package", network);
            common.put("h5_extra_attr", objectMapper.writeValueAsString(h5Attrs));
            common.put("global_attr", "{}");

            Map<String, Object> urlPackage = new LinkedHashMap<>();
            urlPackage.put("page", "GAME_DETAL_PAGE");
            urlPackage.put("identity", "5316c78e-f0b6-4be2-a076-c8f9d11ebc0f");
            urlPackage.put("page_type", 2);
            urlPackage.put("params", "{\"game_id\":1001,\"game_name\":\"王者荣耀\"}");
            Map<String, Object> taskEvent = new LinkedHashMap<>();
            taskEvent.put("type", 1);
            taskEvent.put("status", 0);
            taskEvent.put("operation_type", 1);
            taskEvent.put("operation_direction", 0);
            taskEvent.put("session_id", "1eb20f88-51ac-4ecf-8dc3-ace5aefcae4f");
            taskEvent.put("url_package", urlPackage);
            taskEvent.put("element_package", new LinkedHashMap<>());
            Map<String, Object> eventPackage = new LinkedHashMap<>();
            eventPackage.put("task_event", taskEvent);

            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("client_timestamp", System.currentTimeMillis());
            logEntry.put("client_increment_id", new Random().nextInt(8999) + 1000);
            logEntry.put("session_id", "1eb20f88-51ac-4ecf-8dc3-ace5aefcae4f");
            logEntry.put("time_zone", "GMT+08:00");
            logEntry.put("event_package", eventPackage);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("common", common);
            body.put("logs", List.of(logEntry));

            HttpHeaders headers = createHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Origin", SITE_URL);
            headers.set("Referer", SITE_URL);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    DID_REGISTER_API,
                    new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                    String.class
            );
            log.info("快手 did 注册完成: {}", response.getBody());
        } catch (Exception e) {
            log.warn("快手 did 注册失败", e);
        }
    }

    private void captureCookies(ResponseEntity<String> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null) {
            return;
        }
        for (String cookie : cookies) {
            String[] pair = cookie.split(";", 2)[0].split("=", 2);
            if (pair.length == 2) {
                cookieStore.put(pair[0].trim(), pair[1].trim());
            }
        }
    }

    private String buildCookieHeader() {
        return cookieStore.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
    }

    private static final Pattern WAN_PATTERN = Pattern.compile("([\\d.]+)万");

    // 快手人气值可能是数字、纯数字字符串("6486")或已格式化字符串("1万+"), asInt() 无法解析后者
    private int parseWatchingCount(JsonNode node) {
        if (node.isNumber()) {
            return node.asInt();
        }
        String text = node.asText();
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            Matcher matcher = WAN_PATTERN.matcher(text);
            if (matcher.find()) {
                return (int) (Double.parseDouble(matcher.group(1)) * 10000);
            }
            return 0;
        }
    }

    private boolean isImage(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String ext = url.substring(url.lastIndexOf('.') + 1).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext);
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", Constants.USER_AGENT);
        headers.set("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3");
        headers.set("connection", "keep-alive");
        headers.set("sec-ch-ua", "Google Chrome;v=107, Chromium;v=107, Not=A?Brand;v=24");
        headers.set("sec-ch-ua-platform", "macOS");
        headers.set("Sec-Fetch-Dest", "document");
        headers.set("Sec-Fetch-Mode", "navigate");
        headers.set("Sec-Fetch-Site", "same-origin");
        headers.set("Sec-Fetch-User", "?1");
        return headers;
    }

    private String getRandomUserAgent() {
        String[] userAgents = {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Safari/537.36"
        };
        return userAgents[new Random().nextInt(userAgents.length)];
    }
}
