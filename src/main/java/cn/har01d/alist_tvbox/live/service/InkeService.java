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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 映客直播(pure_live inke 适配器同源契约):
 * 分类=Live_channel_pc 官网精选 tab_key 分组(有限集合非全站索引);推荐=Live_top_pc;
 * 详情=live_share_pc(uid 持久,liveid 场次每次重取);播放=目录接口反查 stream_addr 签名 FLV。
 */
@Slf4j
@Service
public class InkeService implements LivePlatform {
    private static final String WEB_ORIGIN = "https://www.inke.cn";
    private static final String API_ORIGIN = "https://webapi.busi.inke.cn/web";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    /** 推荐流伪分类 id(pure_live 无分类概念,Live_top_pc 即推荐页)。 */
    private static final String TOP_CATEGORY = "top";
    /** 下播哨兵错误码(pure_live: 仅 live_share_pc 端点的该码视为未开播,其余错误码不当作下播)。 */
    private static final int OFFLINE_CODE = 1099999920;
    /** uid 持久 id 格式(pure_live roomId 同款校验)。 */
    private static final Pattern UID_PATTERN = Pattern.compile("[1-9][0-9]{0,17}");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LiveProxyService proxyService;

    public InkeService(RestTemplateBuilder builder, ObjectMapper objectMapper, LiveProxyService proxyService) {
        this.restTemplate = builder.defaultHeader("User-Agent", USER_AGENT).build();
        this.objectMapper = objectMapper;
        this.proxyService = proxyService;
    }

    /**
     * 供直播代理续租:重查该主播当前场次的流地址(上游断连时换新签名 URL 续流)。
     * 重新走 live_share_pc(场次 liveid 每场变化,不能复用旧 bid)→ 目录三源反查 stream_addr。
     */
    public String renewStreamUrl(String uid) {
        try {
            JsonNode root = objectMapper.readTree(getBody(API_ORIGIN + "/live_share_pc?uid=" + uid));
            int code = errorCode(root);
            if (code != 0) {
                // 下播(1099999920)或其他错误码:无流可续
                return null;
            }
            JsonNode info = root.path("data");
            boolean live = "1".equals(info.path("status").asText()) || info.path("status").asBoolean(false);
            if (!live) {
                return null;
            }
            List<String> urls = showcaseMedia(uid, idText(info.path("liveid")));
            return urls.isEmpty() ? null : urls.get(0);
        } catch (Exception e) {
            log.warn("映客流地址续租失败: {}", uid, e);
            return null;
        }
    }

    @Override
    public String getType() {
        return "inke";
    }
    /** 流地址经直播代理中转+断流自动续租。 */
    @Override
    public boolean isProxied() {
        return true;
    }

    @Override
    public String getName() {
        return "映客";
    }

    @Override
    public MovieList home() throws IOException {
        try {
            Map<String, MovieDetail> rooms = new LinkedHashMap<>();
            collectCards(getApi("Live_top_pc").path("list"), rooms);
            return toMovieList(new ArrayList<>(rooms.values()), 1, 1);
        } catch (Exception e) {
            log.warn("映客首页推荐获取失败", e);
            return empty(1);
        }
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();
        Category top = new Category();
        top.setType_id(getType() + "-" + TOP_CATEGORY);
        top.setType_name("热门推荐");
        top.setType_flag(0);
        list.add(top);
        try {
            top.setCover(firstCover(getApi("Live_top_pc").path("list")));
            for (JsonNode group : getApi("Live_channel_pc").path("list")) {
                String key = group.path("tab_key").asText("");
                String name = group.path("channel_name").asText("");
                if (key.isEmpty() || name.isEmpty()) {
                    continue;
                }
                Category category = new Category();
                category.setType_id(getType() + "-" + key);
                category.setType_name(name);
                category.setType_flag(0);
                // 分组无自带分类图,用组内首个在播主播头像当封面(秀场分类视觉索引)
                category.setCover(firstCover(group.path("list")));
                list.add(category);
            }
        } catch (Exception e) {
            // 分类接口失败时保留推荐伪分类,保证平台首页仍可导航
            log.warn("映客分类获取失败,仅保留推荐分类", e);
        }
        result.setCategories(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        log.debug("category result: {}", result);
        return result;
    }

    /** 行数组首个房间的 portrait 头像(image() 归一),无可用行返回 null。 */
    private String firstCover(JsonNode rooms) {
        if (rooms.isArray()) {
            for (JsonNode room : rooms) {
                String portrait = image(room.path("portrait").asText(""));
                if (!portrait.isEmpty()) {
                    return portrait;
                }
            }
        }
        return null;
    }

    @Override
    public MovieList list(String id, String ac, String sort, Integer pg) throws IOException {
        String[] parts = id.split("-", 2);
        if (parts.length < 2 || parts[1].isEmpty()) {
            throw new BadRequestException("无效的分类ID: " + id);
        }
        int page = pg == null || pg < 1 ? 1 : pg;
        if (page > 1) {
            // 官网精选是有限集合无服务端分页(pure_live: page>1 返回空且 hasMore=false)
            return empty(page);
        }
        String tabKey = parts[1];
        Map<String, MovieDetail> rooms = new LinkedHashMap<>();
        boolean found = false;
        for (JsonNode group : getApi("Live_channel_pc").path("list")) {
            if (!tabKey.equals(group.path("tab_key").asText())) {
                continue;
            }
            found = true;
            collectCards(group.path("list"), rooms);
        }
        if (!found && TOP_CATEGORY.equals(tabKey)) {
            collectCards(getApi("Live_top_pc").path("list"), rooms);
            found = true;
        }
        if (!found) {
            throw new BadRequestException("分类不存在: " + id);
        }
        return toMovieList(new ArrayList<>(rooms.values()), 1, 1);
    }

    @Override
    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        String input = wd == null ? "" : wd.trim();
        try {
            if (UID_PATTERN.matcher(input).matches()) {
                // 纯数字视为 uid 直查(pure_live: uid 优先于昵称过滤)
                MovieDetail detail = searchByUid(input);
                if (detail != null) {
                    list.add(detail);
                }
            } else if (!input.isEmpty() && !input.toLowerCase().startsWith("http")) {
                // 昵称本地过滤官网精选目录(非全站索引,pure_live searchShowcases 同款)
                String query = input.toLowerCase();
                Map<String, MovieDetail> rooms = new LinkedHashMap<>();
                collectCards(getApi("Live_top_pc").path("list"), rooms);
                for (JsonNode group : getApi("Live_channel_pc").path("list")) {
                    collectCards(group.path("list"), rooms);
                }
                for (MovieDetail detail : rooms.values()) {
                    if (detail.getVod_name().toLowerCase().contains(query) && list.size() < 50) {
                        list.add(detail);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("映客搜索失败: {}", input, e);
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
        JsonNode root = objectMapper.readTree(getBody(API_ORIGIN + "/live_share_pc?uid=" + uid));
        int code = errorCode(root);
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);
        MovieList result = new MovieList();
        result.getList().add(detail);
        result.setTotal(1);
        result.setLimit(1);
        if (code == OFFLINE_CODE) {
            // 1099999920=当前无直播;公开下播响应无主播资料,仅保留可识别卡片
            detail.setVod_name("映客" + uid);
            detail.setVod_remarks("未开播");
            log.debug("detail: {}", result);
            return result;
        }
        if (code != 0) {
            throw new BadRequestException("房间不存在: " + uid);
        }
        JsonNode info = root.path("data");
        JsonNode owner = info.path("media_info");
        String nick = owner.path("nick").asText("");
        // 身份校验:live_uid/inke_id 必须与 uid 一致,status∈{1,"1",true} 才是在播
        boolean live = "1".equals(info.path("status").asText()) || info.path("status").asBoolean(false);
        if (!live || !uid.equals(idText(info.path("live_uid"))) || nick.isEmpty()
                || !uid.equals(idText(owner.path("inke_id")))) {
            throw new BadRequestException("房间信息异常: " + uid);
        }
        String broadcastId = idText(info.path("liveid"));
        detail.setVod_name(firstText(info.path("live_name").asText(), nick));
        detail.setVod_pic(image(firstText0(info.path("portrait").asText(), owner.path("portrait").asText())));
        detail.setVod_actor(nick);
        detail.setVod_remarks(nick);
        try {
            List<String> urls = showcaseMedia(uid, broadcastId);
            if (!urls.isEmpty()) {
                // 只取首条(# 在 TVBox 语法是分集分隔符);代理条目包代理+ink=uid:
                // 上游断连/换场次时代理端经 uid 重查 stream_addr 续流(映客流 URL 本身无主播身份);
                // dual=直连优先+代理双线路(线路1同档直连/代理交错分集,线路2纯代理,网页端恒走代理)
                String stream = urls.get(0);
                List<String> proxyEntries = new ArrayList<>();
                if (proxyService != null) {
                    String proxyUrl = proxyService.buildProxyUrl(stream);
                    if (!proxyUrl.equals(stream)) {
                        proxyEntries.add("FLV$" + proxyUrl + "&ink=" + uid);
                    }
                }
                String mode = proxyService != null && proxyService.isDualProxyMode() && !"web".equals(client) ? "dual" : "proxy";
                String[] lines = buildPlayLines(List.of("FLV$" + stream), proxyEntries, mode);
                detail.setVod_play_from(lines[0]);
                detail.setVod_play_url(lines[1]);
            } else {
                log.warn("映客目录反查无可用流地址: uid={} bid={}", uid, broadcastId);
            }
        } catch (Exception e) {
            // 播放地址反查失败不炸详情,保留元数据
            log.warn("映客播放地址获取失败: uid={}", uid, e);
        }
        log.debug("detail: {}", result);
        return result;
    }

    /** 播放地址反查(pure_live _showcaseMedia):依次扫 top/hot/channel 目录里 uid+live_id 匹配行的 stream_addr。 */
    private List<String> showcaseMedia(String uid, String broadcastId) throws IOException {
        for (String path : new String[]{"Live_top_pc", "Live_hot_pc", "Live_channel_pc"}) {
            LinkedHashSet<String> urls = new LinkedHashSet<>();
            JsonNode list = getApi(path).path("list");
            if ("Live_channel_pc".equals(path)) {
                for (JsonNode group : list) {
                    collectStream(group.path("list"), uid, broadcastId, urls);
                }
            } else if (list.isArray()) {
                collectStream(list, uid, broadcastId, urls);
            } else if (list.isObject()) {
                // Live_hot_pc 的 list 是对象,值才是行数组
                for (JsonNode group : list) {
                    collectStream(group, uid, broadcastId, urls);
                }
            }
            if (!urls.isEmpty()) {
                return new ArrayList<>(urls);
            }
        }
        return List.of();
    }

    private void collectStream(JsonNode rows, String uid, String broadcastId, LinkedHashSet<String> urls) {
        if (!rows.isArray()) {
            return;
        }
        for (JsonNode row : rows) {
            if (!uid.equals(idText(row.path("uid"))) || !broadcastId.equals(idText(row.path("live_id")))) {
                continue;
            }
            String url = validateFlvUrl(row.path("stream_addr").asText(""), broadcastId);
            if (url != null) {
                urls.add(url);
            }
        }
    }

    /** 流地址校验(pure_live plainFlv 精简):ikstatic CDN + /live/{场次id}_t.flv,保留原签名 query 不做合成。 */
    private String validateFlvUrl(String raw, String broadcastId) {
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
            if (!"live-pull-ws.ikstatic.cn".equals(host) && !host.endsWith(".ikstatic.cn")) {
                return null;
            }
            if (!("/live/" + broadcastId + "_t.flv").equals(uri.getPath())) {
                return null;
            }
            return value;
        } catch (Exception e) {
            return null;
        }
    }

    /** uid 直查(pure_live searchRoomsCancellable):下播返回可识别卡片,其余错误视为无结果。 */
    private MovieDetail searchByUid(String uid) throws IOException {
        JsonNode root = objectMapper.readTree(getBody(API_ORIGIN + "/live_share_pc?uid=" + uid));
        int code = errorCode(root);
        if (code == OFFLINE_CODE) {
            MovieDetail offline = card(uid, "映客" + uid, "");
            offline.setVod_remarks("未开播");
            return offline;
        }
        if (code != 0) {
            return null;
        }
        JsonNode info = root.path("data");
        JsonNode owner = info.path("media_info");
        String nick = owner.path("nick").asText("");
        if (!uid.equals(idText(info.path("live_uid"))) || nick.isEmpty()) {
            return null;
        }
        MovieDetail detail = card(uid, firstText(info.path("live_name").asText(), nick),
                image(firstText0(info.path("portrait").asText(), owner.path("portrait").asText())));
        detail.setVod_actor(nick);
        return detail;
    }

    /** 房间卡片(pure_live _card):roomId 取持久 uid,nick 即标题,portrait 兼作封面/头像。 */
    private void collectCards(JsonNode rows, Map<String, MovieDetail> rooms) {
        if (!rows.isArray()) {
            return;
        }
        for (JsonNode row : rows) {
            String uid = idText(row.path("uid"));
            String nick = row.path("nick").asText("").trim();
            if (uid.isEmpty() || nick.isEmpty()) {
                continue;
            }
            rooms.putIfAbsent(uid, card(uid, nick, image(row.path("portrait").asText(""))));
        }
    }

    private MovieDetail card(String uid, String nick, String pic) {
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(getType() + "$" + uid);
        detail.setVod_name(nick);
        detail.setVod_pic(pic);
        detail.setVod_remarks(nick);
        return detail;
    }

    /** webapi 信封校验:{error_code,data};error_code 可为数字字符串,非 0 视为服务错误。 */
    private JsonNode getApi(String pathAndQuery) throws IOException {
        JsonNode root = objectMapper.readTree(getBody(API_ORIGIN + "/" + pathAndQuery));
        if (errorCode(root) != 0) {
            throw new BadRequestException("映客接口错误: " + errorCode(root));
        }
        return root.path("data");
    }

    private int errorCode(JsonNode root) {
        JsonNode code = root.path("error_code");
        if (code.isNumber()) {
            return code.asInt(-1);
        }
        String text = code.asText("");
        if (text.isEmpty() || text.length() > 10) {
            return -1;
        }
        for (char c : text.toCharArray()) {
            if (!Character.isDigit(c)) {
                return -1;
            }
        }
        return Integer.parseInt(text);
    }

    /** 纯数字 id 归一:uid/live_id 可能是 int 或 string,非纯数字返回空。 */
    private String idText(JsonNode node) {
        String text = node.asText("").trim();
        if (text.isEmpty()) {
            return "";
        }
        for (char c : text.toCharArray()) {
            if (!Character.isDigit(c)) {
                return "";
            }
        }
        return text;
    }

    /** 图片规整(pure_live _picture):仅接受 http(s) 绝对地址,// 开头补 https:。 */
    private String image(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "null".equals(value)) {
            return "";
        }
        if (value.startsWith("//")) {
            value = "https:" + value;
        } else if (value.startsWith("http://")) {
            // ikstatic 双协议均可用,https 防网页端混合内容拦截
            value = "https://" + value.substring(7);
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

    private MovieList toMovieList(List<MovieDetail> list, int page, int pagecount) {
        MovieList result = new MovieList();
        result.setList(list);
        result.setPage(page);
        result.setPagecount(pagecount);
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
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
        headers.set(HttpHeaders.ORIGIN, WEB_ORIGIN);
        headers.set(HttpHeaders.REFERER, WEB_ORIGIN + "/");
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
    }

    private String firstText(String... values) {
        String value = firstText0(values);
        return value.isEmpty() ? "映客直播" : value;
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
