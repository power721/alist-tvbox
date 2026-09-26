package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 酷狗繁星直播(pure_live kugoulive 适配器同源契约):
 * 分类=fanxing 首页 HTML 正则(失败回退硬编码);目录/推荐=fx1 service list/list_v4;
 * 搜索=type_all.jsonp;详情=getEnterRoomInfo+streamaddr 多线路(FLV/HLS×rate)。
 */
@Slf4j
@Service
public class KugouLiveService implements LivePlatform {
    private static final String WEB_ORIGIN = "https://fanxing.kugou.com";
    private static final String API_ORIGIN = "https://fx1.service.kugou.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    /** 推荐流分类 id(pure_live 同款,目录接口无 cid 即推荐)。 */
    private static final String RECOMMEND_CATEGORY = "8000";
    private static final Pattern CATEGORY_PATTERN = Pattern.compile(
            "href=[\"'](?:https://fanxing\\.kugou\\.com)?/pcindex/category/(\\d{1,8})[^\"']*[\"'][^>]*title=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    /** 个人空间路由伪装成分类,剔除(pure_live 同款)。 */
    private static final List<String> PERSONAL_ROUTES = List.of("3001", "3009", "3014", "3015");
    /** 分类页 HTML 解析失败时的回退清单(pure_live fallbackCategories 同款)。 */
    private static final String[][] FALLBACK_CATEGORIES = {
            {"8000", "推荐"}, {"100001", "一起玩"}, {"100002", "音乐"}, {"31050", "高清"},
            {"7024", "舞蹈"}, {"1009", "颜值"}, {"1001", "新秀"}, {"3007", "酷次元"},
            {"7041", "搞笑"}, {"31", "国风"}, {"6201", "游戏女神"}, {"6007", "王者荣耀"},
            {"6004", "和平精英"}, {"6003", "网游竞技"}};

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LiveProxyService proxyService;
    /** 搜索 jsonp 端点按 TLS/HTTP 指纹风控:JDK HttpURLConnection 返回合法空结果,okhttp 实测可过。 */
    private static final OkHttpClient OK_HTTP = new OkHttpClient();
    private volatile Map<String, String> categoryNames;

    public KugouLiveService(RestTemplateBuilder builder, ObjectMapper objectMapper, LiveProxyService proxyService) {
        this.restTemplate = builder.defaultHeader("User-Agent", USER_AGENT).build();
        this.objectMapper = objectMapper;
        this.proxyService = proxyService;
    }

    @Override
    public String getType() {
        return "kugoulive";
    }
    /** 流地址经直播代理中转+断流自动重签续流。 */
    @Override
    public boolean isProxied() {
        return true;
    }

    @Override
    public String getName() {
        return "酷狗直播";
    }

    @Override
    public MovieList home() throws IOException {
        return toMovieList(directory(1, null), 1);
    }

    @Override
    public CategoryList category() throws IOException {
        Map<String, String> names = loadCategories();
        // 分类页 HTML 与回退清单均无官方图:推荐流房间封面轮询填充(单次请求,秀场分类视觉索引)
        List<String> covers = new ArrayList<>();
        try {
            for (JsonNode entry : directory(1, null).path("list")) {
                JsonNode raw = "star".equals(entry.path("uiType").asText()) ? entry.path("data") : entry;
                MovieDetail card = parseCard(raw);
                if (card != null && card.getVod_pic() != null && !card.getVod_pic().isEmpty()) {
                    covers.add(card.getVod_pic());
                }
            }
        } catch (Exception e) {
            log.warn("酷狗分类封面获取失败: {}", e.getMessage());
        }
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();
        int index = 0;
        for (var entry : names.entrySet()) {
            Category category = new Category();
            category.setType_id(getType() + "-" + entry.getKey());
            category.setType_name(entry.getValue());
            category.setType_flag(0);
            if (!covers.isEmpty()) {
                category.setCover(covers.get(index % covers.size()));
            }
            index++;
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
        String[] parts = id.split("-");
        if (parts.length < 2) {
            throw new BadRequestException("无效的分类ID: " + id);
        }
        int page = pg == null || pg < 1 ? 1 : pg;
        String cid = RECOMMEND_CATEGORY.equals(parts[1]) ? null : parts[1];
        return toMovieList(directory(page, cid), page);
    }

    @Override
    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        String callback = "pureLive" + System.nanoTime();
        String url = API_ORIGIN + "/pt_search/pcsearch/v1/type_all.jsonp?keywords=" + java.net.URLEncoder.encode(wd, java.nio.charset.StandardCharsets.UTF_8)
                + "&nums=200,0,0,0&callback=" + callback;
        // 该端点对 JDK HttpURLConnection 指纹返回空结果(okhttp 实测同头可过),走 okhttp
        Request request = new Request.Builder().url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", WEB_ORIGIN + "/")
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        String bodyText;
        try (Response response = OK_HTTP.newCall(request).execute()) {
            bodyText = response.body() == null ? "" : response.body().string();
        }
        JsonNode root = objectMapper.readTree(stripJsonp(bodyText, callback));
        for (JsonNode item : root.path("data").path("anchor").path("list")) {
            MovieDetail detail = parseCard(item);
            if (detail != null) {
                list.add(detail);
            }
        }
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        log.debug("search result: {}", result);
        return result;
    }

    @Override
    public MovieList detail(String tid, String client) throws IOException {
        String[] parts = tid.split("\\$");
        if (parts.length < 2) {
            throw new BadRequestException("无效的直播间ID: " + tid);
        }
        String id = parts[1];
        MovieList result = new MovieList();
        JsonNode data = getJson("https://service2.fanxing.kugou.com/roomcen/room/web/cdn/getEnterRoomInfo?roomId=" + id).path("data");
        JsonNode normal = data.path("normalRoomInfo");
        if (!normal.isObject() || normal.path("nickName").asText("").isEmpty() && normal.path("kugouId").asText("").isEmpty()) {
            throw new BadRequestException("房间不存在: " + id);
        }
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);
        String nick = normal.path("nickName").asText();
        detail.setVod_name(firstText(normal.path("publicMesg").asText(), normal.path("privateMesg").asText(), nick));
        detail.setVod_pic(image(normal.path("imgPath").asText()));
        detail.setVod_actor(nick);
        // liveType==-1 下播;liveSessionId 非空在播;limitType>0 受限房
        int liveType = data.path("liveType").asInt(0);
        boolean live = liveType != -1 && !data.path("liveSessionId").asText("").isEmpty();
        detail.setVod_remarks(live ? "直播中" : "未开播");
        if (live) {
            parsePlayUrls(detail, id, client);
        }
        result.getList().add(detail);
        result.setTotal(1);
        result.setLimit(1);
        log.debug("detail: {}", result);
        return result;
    }

    /** 供直播代理续租:按房间重取播放地址(上游断开/签名失效时换新 URL 续流),失败返回 null。 */
    public String renewStreamUrl(String roomId, String protocol) {
        try {
            String url = API_ORIGIN + "/video/pc/live/pull/mutiline/streamaddr?std_rid=" + roomId
                    + "&std_plat=7&std_kid=0&streamType=1-2-4-5-8&ua=fx-flash&targetLiveTypes=1-5-6"
                    + "&version=1000&supportEncryptMode=1&appid=1010&_=" + System.currentTimeMillis();
            JsonNode data = getJson(url).path("data");
            if (!roomId.equals(data.path("roomId").asText()) || data.path("status").asInt(0) != 1) {
                return null;
            }
            String[] sources = "hls".equals(protocol) ? new String[]{"httpsHls", "hls"} : new String[]{"httpsFlv", "flv"};
            for (JsonNode line : data.path("lines")) {
                for (JsonNode profile : line.path("streamProfiles")) {
                    for (JsonNode raw : profile.path(sources[0])) {
                        String uri = validateMediaUrl(raw.asText(), roomId, sources[1]);
                        if (uri != null) {
                            return uri;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("酷狗流地址续租失败: {}", roomId, e);
        }
        return null;
    }

    /** 播放地址:lines[].streamProfiles[] 按 (协议,rate) 分组,rate 降序输出画质条目。 */
    private void parsePlayUrls(MovieDetail detail, String roomId, String client) {
        try {
            String url = API_ORIGIN + "/video/pc/live/pull/mutiline/streamaddr?std_rid=" + roomId
                    + "&std_plat=7&std_kid=0&streamType=1-2-4-5-8&ua=fx-flash&targetLiveTypes=1-5-6"
                    + "&version=1000&supportEncryptMode=1&appid=1010&_=" + System.currentTimeMillis();
            JsonNode data = getJson(url).path("data");
            if (!roomId.equals(data.path("roomId").asText()) || data.path("status").asInt(0) != 1) {
                return;
            }
            Map<String, List<String>> grouped = new LinkedHashMap<>();
            Map<String, Integer> rates = new LinkedHashMap<>();
            for (JsonNode line : data.path("lines")) {
                for (JsonNode profile : line.path("streamProfiles")) {
                    int rate = profile.path("rate").asInt(0);
                    for (String[] source : new String[][]{{"httpsFlv", "flv"}, {"httpsHls", "hls"}}) {
                        String key = source[1] + ":" + rate;
                        rates.putIfAbsent(key, rate);
                        for (JsonNode raw : profile.path(source[0])) {
                            String uri = validateMediaUrl(raw.asText(), roomId, source[1]);
                            if (uri != null) {
                                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(uri);
                            }
                        }
                    }
                }
            }
            List<String> directEntries = new ArrayList<>();
            List<String> proxyEntries = new ArrayList<>();
            grouped.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(rates.get(b.getKey()), rates.get(a.getKey())))
                    // 一档只出一条地址:# 在 TVBox 语法里是分集分隔符,join 多地址会被当连续剧集;
                    // 实测 lines 会返回相同 URL 的重复项,只取首条。
                    // 直连条目=原始地址(签名 txTime 约 12h);代理条目=包代理,上游断连/签名失效时
                    // 代理端自动重签续流(buildProxyUrl 降级或探针无代理实例时不产代理条目);
                    // dual=直连优先+代理双线路(线路1同档直连/代理交错分集,线路2纯代理,网页端恒走代理),直连断流由播放器自动切代理线路
                    .forEach(entry -> {
                        String label = entry.getKey().replace(":", "·");
                        String stream = entry.getValue().get(0);
                        directEntries.add(label + "$" + stream);
                        if (proxyService != null) {
                            String proxyUrl = proxyService.buildProxyUrl(stream);
                            if (!proxyUrl.equals(stream)) {
                                proxyEntries.add(label + "$" + proxyUrl);
                            }
                        }
                    });
            if (!directEntries.isEmpty()) {
                String mode = proxyService != null && proxyService.isDualProxyMode() && !"web".equals(client) ? "dual" : "proxy";
                String[] lines = buildPlayLines(directEntries, proxyEntries, mode);
                detail.setVod_play_from(lines[0]);
                detail.setVod_play_url(lines[1]);
            }
        } catch (Exception e) {
            log.warn("酷狗播放地址获取失败: {}", roomId, e);
        }
    }

    /** 媒体地址防御校验(pure_live 同款精简):CDN host 白名单+token 前缀。 */
    private String validateMediaUrl(String raw, String roomId, String protocol) {
        try {
            java.net.URI uri = java.net.URI.create(raw);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
            String expected = "hls".equals(protocol) ? ".m3u8" : ".flv";
            if (!"https".equals(uri.getScheme()) || !host.endsWith(".liveplay.live.kugou.com") && !"liveplay.live.kugou.com".equals(host)) {
                return null;
            }
            if (!path.startsWith("/live/") || !path.endsWith(expected)) {
                return null;
            }
            String query = uri.getQuery() == null ? "" : uri.getQuery();
            if (!query.contains("txSecret=")) {
                return null;
            }
            for (String param : query.split("&")) {
                if (param.startsWith("token=") && param.substring(6).startsWith("0-" + roomId + "-")) {
                    return raw;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 目录页:推荐分类走 list(无 cid),具体分类走 list_v4(带 cid)。 */
    private JsonNode directory(int page, String cid) throws IOException {
        String url = API_ORIGIN + (cid == null
                ? "/mfanxing-home/h5/cdn/room/index/list?pid=0&kugouId=0&doubleLiveFirst=1&sysVersion=0&platform=7&device=PureLive-Web&channel=0&version=99999&longitude=0&latitude=0&appid=1010&liveTypeFilter=0&isNew=0&entranceType=0&uiMode=0&page=" + page
                : "/mfanxing-home/h5/cdn/room/index/list_v4?pid=0&kugouId=0&doubleLiveFirst=1&sysVersion=0&platform=7&device=PureLive-Web&channel=0&version=99999&longitude=0&latitude=0&appid=1010&liveTypeFilter=0&isNew=0&entranceType=0&uiMode=0&page=" + page + "&cid=" + cid);
        return getJson(url).path("data");
    }

    private MovieList toMovieList(JsonNode data, int page) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        for (JsonNode entry : data.path("list")) {
            // uiType=star 的卡片房间在 wrapper.data 里(pure_live 同款)
            JsonNode raw = "star".equals(entry.path("uiType").asText()) ? entry.path("data") : entry;
            MovieDetail detail = parseCard(raw);
            if (detail != null) {
                list.add(detail);
            }
        }
        result.setList(list);
        result.setPage(page);
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
    }

    private MovieDetail parseCard(JsonNode raw) {
        String roomId = raw.path("roomId").asText("");
        if (roomId.isEmpty() || !roomId.chars().allMatch(Character::isDigit)) {
            return null;
        }
        // 目录卡片带 liveStatus(仅保留在播);搜索卡片无该字段族(结果本身都是直播中),未知态不剔除
        int live = raw.path("liveStatus").asInt(raw.path("status").asInt(raw.path("liveType").asInt(-2)));
        if (live == 0 || live == -1) {
            return null;
        }
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(getType() + "$" + roomId);
        String nick = raw.path("nickName").asText();
        detail.setVod_name(firstText(raw.path("label").asText(), raw.path("topicContent").asText(), raw.path("performContent").asText(), nick));
        detail.setVod_pic(image(firstText0(raw.path("imgPath").asText(), raw.path("imagePath").asText())));
        detail.setVod_remarks(nick);
        return detail;
    }

    private Map<String, String> loadCategories() {
        Map<String, String> cached = categoryNames;
        if (cached != null) {
            return cached;
        }
        Map<String, String> names = new LinkedHashMap<>();
        try {
            String html = getBody(WEB_ORIGIN + "/");
            Matcher matcher = CATEGORY_PATTERN.matcher(html);
            while (matcher.find()) {
                String id = matcher.group(1);
                String name = matcher.group(2).replace("&amp;", "&").replace("&quot;", "\"").trim();
                if (!PERSONAL_ROUTES.contains(id) && !name.isEmpty()) {
                    names.putIfAbsent(id, name);
                }
            }
        } catch (Exception e) {
            log.warn("酷狗分类页解析失败,使用回退清单: {}", e.getMessage());
        }
        if (names.isEmpty()) {
            for (String[] item : FALLBACK_CATEGORIES) {
                names.put(item[0], item[1]);
            }
        }
        categoryNames = names;
        return names;
    }

    private String getBody(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        headers.set(HttpHeaders.ORIGIN, WEB_ORIGIN);
        headers.set(HttpHeaders.REFERER, WEB_ORIGIN + "/");
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        // 分类页是 text/html 无 charset,按 String 收会被 ISO-8859-1 解码出乱码,按字节收统一 UTF-8
        byte[] bytes = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class).getBody();
        return new String(bytes == null ? new byte[0] : bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private JsonNode getJson(String url) throws IOException {
        return objectMapper.readTree(getBody(url));
    }

    /** JSONP 剥壳:callback(...) 取括号内 JSON。 */
    private String stripJsonp(String body, String callback) {
        String text = body == null ? "" : body.trim();
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open <= 0 || close <= open || !text.substring(0, open).trim().equals(callback)) {
            throw new BadRequestException("酷狗搜索响应格式异常");
        }
        return text.substring(open + 1, close);
    }

    /** 图片地址规整(pure_live 同款):协议相对补 https:,相对路径挂 kgimg CDN,限酷狗系 host。 */
    private String image(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "null".equals(value)) {
            return "";
        }
        value = value.replace("/v2/fxuserlogo//v2/fxuserlogo/", "/v2/fxuserlogo/");
        if (value.startsWith("//")) {
            value = "https:" + value;
        } else if (value.startsWith("/")) {
            value = "https://p3.fx.kgimg.com" + value;
        }
        try {
            String host = java.net.URI.create(value).getHost();
            if (host == null || !host.endsWith("kgimg.com") && !host.endsWith("kugou.com")) {
                return "";
            }
            return value.replaceFirst("^http://", "https://");
        } catch (Exception e) {
            return "";
        }
    }

    private String firstText(String... values) {
        String v = firstText0(values);
        return v.isEmpty() ? "酷狗直播" : v;
    }

    private String firstText0(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty() && !"null".equals(value)) {
                return value;
            }
        }
        return "";
    }
}
