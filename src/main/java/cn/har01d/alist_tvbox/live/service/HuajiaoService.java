package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 花椒直播(pure_live huajiao 适配器同源契约):
 * 目录=live.huajiao.com feed/getLives4H5 游标分页(offset 服务端累进非 page*num);
 * 用户=Web/UserInfo/full(living 是场次 id 非布尔);取流=h.huajiao.com api/getFeedInfo
 * 的 pull_m3u8/main/h264_url,host 限 huajiao.com,格式按 URL 后缀判定。
 */
@Slf4j
@Service
public class HuajiaoService implements LivePlatform {
    private static final String API_ORIGIN = "https://live.huajiao.com";
    private static final String H5_ORIGIN = "https://h.huajiao.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    /** 唯一分类:H5 feed 固定 name=live5(pure_live 无更多分类)。 */
    private static final String AREA_ID = "live5";
    /** 目录接口 num 上限 30(pure_live 同款)。 */
    private static final int PAGE_SIZE = 30;
    /** 游标重放页数上限(pure_live getDirectoryPage 同款)。 */
    private static final int MAX_PAGES = 20;
    /** 昵称搜索只过滤推荐流前 3 页(pure_live: 非全站索引,有界)。 */
    private static final int SEARCH_PAGES = 3;
    /** uid 持久 id 格式(pure_live validId 同款)。 */
    private static final Pattern UID_PATTERN = Pattern.compile("[1-9][0-9]{0,15}");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HuajiaoService(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder.defaultHeader("User-Agent", USER_AGENT).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getType() {
        return "huajiao";
    }

    @Override
    public String getName() {
        return "花椒";
    }

    @Override
    public MovieList home() throws IOException {
        try {
            JsonNode data = directoryData(0);
            List<MovieDetail> list = parseFeeds(data);
            MovieList result = new MovieList();
            result.setList(list);
            result.setPage(1);
            result.setPagecount(data.path("more").asBoolean(false) ? 2 : 1);
            result.setTotal(list.size());
            result.setLimit(list.size());
            return result;
        } catch (Exception e) {
            log.warn("花椒首页推荐获取失败", e);
            return empty(1);
        }
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        Category category = new Category();
        category.setType_id(getType() + "-" + AREA_ID);
        category.setType_name("热门");
        category.setType_flag(0);
        try {
            // 单分类无官方图:推荐流首个房间封面兜底(匿名限流下可能取不到,无图不炸)
            List<MovieDetail> feeds = parseFeeds(directoryData(0));
            if (!feeds.isEmpty() && feeds.get(0).getVod_pic() != null) {
                category.setCover(feeds.get(0).getVod_pic());
            }
        } catch (Exception e) {
            log.warn("花椒分类封面获取失败: {}", e.getMessage());
        }
        result.getCategories().add(category);
        result.setTotal(1);
        result.setLimit(1);
        log.debug("category result: {}", result);
        return result;
    }

    @Override
    public MovieList list(String id, String ac, String sort, Integer pg) throws IOException {
        String[] parts = id.split("-", 2);
        if (parts.length < 2 || !AREA_ID.equals(parts[1])) {
            throw new BadRequestException("无效的分类ID: " + id);
        }
        int page = pg == null || pg < 1 ? 1 : pg;
        if (page > MAX_PAGES) {
            return empty(page);
        }
        // 游标分页重放(pure_live replay):next offset 由服务端返回累进,不能自算 page*30
        JsonNode data = null;
        int offset = 0;
        boolean more = false;
        for (int current = 1; current <= page; current++) {
            data = directoryData(offset);
            int next = data.path("offset").asInt(-1);
            more = data.path("more").asBoolean(false);
            if (current == page) {
                break;
            }
            if (!more || next < 0 || next <= offset) {
                // 服务端提前终止,请求页不存在
                return empty(page);
            }
            offset = next;
        }
        List<MovieDetail> list = parseFeeds(data);
        MovieList result = new MovieList();
        result.setList(list);
        result.setPage(page);
        result.setPagecount(more ? page + 1 : page);
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
    }

    @Override
    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        String input = wd == null ? "" : wd.trim();
        try {
            if (UID_PATTERN.matcher(input).matches()) {
                // 纯数字视为 uid 直查用户(pure_live: uid 优先,返回 owner 快照)
                MovieDetail owner = ownerCard(input);
                if (owner != null) {
                    list.add(owner);
                }
            } else if (!input.isEmpty() && !input.toLowerCase().startsWith("http") && !input.matches("[0-9]+")) {
                String query = input.toLowerCase();
                Map<String, MovieDetail> matches = new LinkedHashMap<>();
                int offset = 0;
                for (int index = 0; index < SEARCH_PAGES; index++) {
                    JsonNode data = directoryData(offset);
                    for (MovieDetail card : parseFeeds(data)) {
                        if (card.getVod_name().toLowerCase().contains(query)
                                || card.getVod_actor().toLowerCase().contains(query)) {
                            matches.putIfAbsent(card.getVod_id(), card);
                        }
                    }
                    boolean more = data.path("more").asBoolean(false);
                    int next = data.path("offset").asInt(-1);
                    if (!more || next <= offset) {
                        break;
                    }
                    offset = next;
                }
                list.addAll(matches.values());
            }
        } catch (Exception e) {
            log.warn("花椒搜索失败: {}", input, e);
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
        if (parts.length < 2 || !UID_PATTERN.matcher(parts[1]).matches()) {
            throw new BadRequestException("无效的直播间ID: " + tid);
        }
        String uid = parts[1];
        JsonNode ownerData = getApi(API_ORIGIN + "/Web/UserInfo/full?uid=" + uid + "&with_living=1&with_counter=1");
        JsonNode base = ownerData.path("base");
        if (!base.isObject()) {
            // 未知 uid:匿名接口返回空 base 列表而非错误码(pure_live notFound 哨兵)
            throw new BadRequestException("房间不存在: " + uid);
        }
        String name = base.path("nickname").asText("");
        if (!uid.equals(idText(base.path("uid"))) || name.isEmpty()) {
            throw new BadRequestException("房间信息异常: " + uid);
        }
        // living 是当前场次 id(broadcast id)非布尔;0/非数字=未开播
        String living = idText(ownerData.path("living"));
        String liveId = living.isEmpty() || "0".equals(living) ? "" : living;
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);
        detail.setVod_name(name);
        detail.setVod_pic(image(firstText0(base.path("avatar_l").asText(), base.path("avatar").asText())));
        detail.setVod_actor(name);
        MovieList result = new MovieList();
        result.getList().add(detail);
        result.setTotal(1);
        result.setLimit(1);
        if (liveId.isEmpty()) {
            detail.setVod_remarks("未开播");
            log.debug("detail: {}", result);
            return result;
        }
        detail.setVod_remarks("直播中");
        try {
            parseBroadcast(detail, uid, liveId);
        } catch (Exception e) {
            // 场次缺失/受限/取流失败:保留用户元数据,播放地址留空,不炸详情
            log.warn("花椒播放地址获取失败: uid={} liveId={}", uid, liveId, e);
        }
        log.debug("detail: {}", result);
        return result;
    }

    /** 取流(pure_live broadcast):getFeedInfo 场次详情;多地址按 URL 后缀格式分组,sn 须与 feed 一致。 */
    private void parseBroadcast(MovieDetail detail, String uid, String liveId) throws IOException {
        String url = H5_ORIGIN + "/api/getFeedInfo?liveid=" + liveId + "&_rate=xd&stype=m3u8&sid=" + System.currentTimeMillis();
        JsonNode data = getApi(url);
        JsonNode entry = data.path("feed");
        JsonNode feed = entry.path("feed");
        // 场次缺失哨兵:feed 只剩 {point:""}(pure_live 观察所得,point 是定位数据非权限)
        if (!feed.isObject() || feed.isEmpty() || feed.size() == 1 && feed.path("point").asText("").isEmpty()) {
            detail.setVod_remarks("未开播");
            throw new BadRequestException("直播已结束: " + uid);
        }
        JsonNode live = data.path("live");
        if (!live.isObject() || live.path("errcode").asInt(-1) != 0) {
            detail.setVod_remarks("未开播");
            throw new BadRequestException("直播已结束: " + uid);
        }
        // 身份校验:relateid=场次 id 须匹配,author.uid 须等于持久 uid,live.sn 须等于 feed.sn 且非空
        String sn = feed.path("sn").asText("");
        if (!liveId.equals(idText(feed.path("relateid"))) || !uid.equals(idText(entry.path("author").path("uid")))
                || sn.isEmpty() || !sn.equals(live.path("sn").asText())) {
            throw new BadRequestException("房间信息异常: " + uid);
        }
        if (!isPublicLive(feed)) {
            throw new BadRequestException("直播间受限: " + uid);
        }
        // 元数据以场次为准(标题/封面比用户页新)
        String nick = entry.path("author").path("nickname").asText("").trim();
        String title = feed.path("title").asText("").trim();
        if (!nick.isEmpty()) {
            detail.setVod_actor(nick);
            if (title.isEmpty()) {
                detail.setVod_name(nick);
            }
        }
        if (!title.isEmpty()) {
            detail.setVod_name(title);
        }
        String cover = image(feed.path("image").asText());
        if (!cover.isEmpty()) {
            detail.setVod_pic(cover);
        }
        // 播放地址:pull_m3u8/main/h264_url 去重,host 限 huajiao.com(pure_live _mediaUrl)
        Map<String, List<String>> media = new LinkedHashMap<>();
        for (String key : new String[]{"pull_m3u8", "main", "h264_url"}) {
            String mediaUrl = validateMediaUrl(live.path(key).asText(""));
            if (mediaUrl == null) {
                continue;
            }
            // 格式按 URL 后缀判定,不按字段名(pure_live 注释:编码字段名与实际码流不符)
            String format = formatOf(mediaUrl);
            media.computeIfAbsent(format, k -> new ArrayList<>()).add(mediaUrl);
        }
        if (media.isEmpty()) {
            throw new BadRequestException("无可用播放地址: " + uid);
        }
        List<String> playUrl = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : media.entrySet()) {
            String label = "unknown".equals(group.getKey()) ? "原画" : group.getKey().toUpperCase();
            playUrl.add(label + "$" + String.join("#", group.getValue()));
        }
        detail.setVod_play_from("线路1");
        detail.setVod_play_url(String.join("#", playUrl));
    }

    /** 公开直播判定(pure_live _publicLive):受限/付费房/非视频模式不进目录。 */
    private boolean isPublicLive(JsonNode feed) {
        return feed.path("origin_status").asInt(-1) == 1
                && "N".equals(feed.path("is_privacy").asText())
                && feed.path("special_room").asInt(-1) == 0
                && "video".equals(feed.path("mode").asText());
    }

    /** feed 卡片(pure_live _parseFeed/_card):relateid 是场次 id,author.uid 才是持久 roomId。 */
    private MovieDetail parseCard(JsonNode entry) {
        JsonNode feed = entry.path("feed");
        JsonNode author = entry.path("author");
        String userId = idText(author.path("uid"));
        String name = author.path("nickname").asText("").trim();
        if (entry.path("type").asInt(-1) != 1 || userId.isEmpty() || name.isEmpty() || !isPublicLive(feed)) {
            return null;
        }
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(getType() + "$" + userId);
        String title = feed.path("title").asText("").trim();
        detail.setVod_name(title.isEmpty() ? name : title);
        detail.setVod_pic(image(firstText0(feed.path("image").asText(), author.path("avatar_l").asText(), author.path("avatar").asText())));
        detail.setVod_actor(name);
        int heat = feed.path("current_heat").asInt(-1);
        // current_heat 是热度(pure_live popularity),非观看人数
        detail.setVod_remarks(heat >= 0 ? playCount(heat) : name);
        return detail;
    }

    /** 目录行归一(pure_live directory):sections[].feeds 追加顶层 feeds,按 roomId 去重。 */
    private List<MovieDetail> parseFeeds(JsonNode data) {
        Map<String, MovieDetail> feeds = new LinkedHashMap<>();
        for (JsonNode section : data.path("sections")) {
            for (JsonNode row : section.path("feeds")) {
                MovieDetail card = parseCard(row);
                if (card != null) {
                    feeds.putIfAbsent(card.getVod_id(), card);
                }
            }
        }
        for (JsonNode row : data.path("feeds")) {
            MovieDetail card = parseCard(row);
            if (card != null) {
                feeds.putIfAbsent(card.getVod_id(), card);
            }
        }
        return new ArrayList<>(feeds.values());
    }

    /** 用户快照(pure_live owner):living>0 表示在播;未知 uid 返回 null。 */
    private MovieDetail ownerCard(String uid) throws IOException {
        JsonNode data = getApi(API_ORIGIN + "/Web/UserInfo/full?uid=" + uid + "&with_living=1&with_counter=1");
        JsonNode base = data.path("base");
        if (!base.isObject()) {
            return null;
        }
        String name = base.path("nickname").asText("");
        if (!uid.equals(idText(base.path("uid"))) || name.isEmpty()) {
            return null;
        }
        String living = idText(data.path("living"));
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(getType() + "$" + uid);
        detail.setVod_name(name);
        detail.setVod_pic(image(firstText0(base.path("avatar_l").asText(), base.path("avatar").asText())));
        detail.setVod_actor(name);
        detail.setVod_remarks(living.isEmpty() || "0".equals(living) ? "未开播" : "直播中");
        return detail;
    }

    /** H5 目录(getLives4H5):name 固定 live5,offset 游标由服务端返回。 */
    private JsonNode directoryData(int offset) throws IOException {
        return getApi(API_ORIGIN + "/feed/getLives4H5?name=" + AREA_ID + "&num=" + PAGE_SIZE + "&offset=" + offset);
    }

    /** 信封校验:{errno,data};errno 可为数字字符串;111=匿名风控。 */
    private JsonNode getApi(String url) throws IOException {
        JsonNode root = objectMapper.readTree(getBody(url));
        JsonNode code = root.path("errno");
        int errno = code.isNumber() ? code.asInt(-1) : parseDigits(code.asText(""));
        if (errno != 0) {
            throw new BadRequestException("花椒接口错误: " + errno);
        }
        return root.path("data");
    }

    private int parseDigits(String text) {
        if (text == null || text.isEmpty() || text.length() > 10) {
            return -1;
        }
        for (char c : text.toCharArray()) {
            if (!Character.isDigit(c)) {
                return -1;
            }
        }
        return Integer.parseInt(text);
    }

    /** 纯数字 id 归一:uid/relateid 可能是 int 或 string,超 16 位或非数字返回空。 */
    private String idText(JsonNode node) {
        String text = node.asText("").trim();
        if (text.isEmpty() || text.length() > 16 || "0".equals(text)) {
            return "";
        }
        for (char c : text.toCharArray()) {
            if (!Character.isDigit(c)) {
                return "";
            }
        }
        return text;
    }

    /** 媒体地址校验(pure_live _mediaUrl):仅 http(s) 且 host 为 huajiao.com 或其子域。 */
    private String validateMediaUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                return null;
            }
            if (!"huajiao.com".equals(host) && !host.endsWith(".huajiao.com")) {
                return null;
            }
            return value;
        } catch (Exception e) {
            return null;
        }
    }

    /** 格式按 URL 路径后缀判定(pure_live 同款):.m3u8=hls,.flv=flv,其余 unknown。 */
    private String formatOf(String url) {
        try {
            String path = URI.create(url).getPath().toLowerCase();
            if (path.endsWith(".m3u8")) {
                return "hls";
            }
            if (path.endsWith(".flv")) {
                return "flv";
            }
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 图片规整:仅接受 http(s) 绝对地址,// 开头补 https:。 */
    private String image(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "null".equals(value)) {
            return "";
        }
        if (value.startsWith("//")) {
            value = "https:" + value;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)
                    || uri.getHost() == null || uri.getHost().isEmpty() || uri.getUserInfo() != null) {
                return "";
            }
            return value;
        } catch (Exception e) {
            return "";
        }
    }

    private MovieList empty(int page) {
        MovieList result = new MovieList();
        result.setList(new ArrayList<>());
        result.setPage(page);
        result.setPagecount(page);
        result.setTotal(0);
        result.setLimit(0);
        return result;
    }

    private String getBody(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        headers.set(HttpHeaders.ORIGIN, H5_ORIGIN);
        headers.set(HttpHeaders.REFERER, H5_ORIGIN + "/");
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
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
