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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 六间房直播(pure_live sixroom 适配器同源契约):
 * 目录=v.6.cn 首页 HTML 内嵌 window.__SMARTY_ALL_VARIABLES__.typeList,分类为前端过滤+本地分页;
 * 搜索=search.php?type=use 的 HTML;详情=房间页解出 uid 后 POST coop-mobile-inroom(flag=001);
 * 播放地址=wlive.6rooms.com/httpflv/v{uid}-{liveId}(-many).flv。
 */
@Slf4j
@Service
public class SixRoomService implements LivePlatform {
    private static final String WEB_ORIGIN = "https://v.6.cn";
    private static final String MOBILE_ORIGIN = "https://ios.6.cn";
    private static final String MEDIA_ORIGIN = "https://wlive.6rooms.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    /** inroom 接口必须用官方 App UA,否则拒绝服务(pure_live 同款)。 */
    private static final String MOBILE_USER_AGENT = "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))";
    private static final String SMARTY_MARKER = "window.__SMARTY_ALL_VARIABLES__ = ";
    private static final long DIRECTORY_CACHE_MS = 90_000;
    private static final int PAGE_SIZE = 30;
    /** 房间号 2-12 位、主播 uid 2-13 位纯数字(pure_live 六间房校验)。 */
    private static final Pattern ROOM_ID = Pattern.compile("^[1-9]\\d{1,11}$");
    private static final Pattern USER_ID = Pattern.compile("^[1-9]\\d{1,12}$");
    /** 房间页内嵌脚本:rid:'uid',roomid:'roomId' 成对出现(pure_live 同款)。 */
    private static final Pattern ROOM_UID = Pattern.compile("\\brid\\s*:\\s*['\"]([1-9]\\d{1,12})['\"]\\s*,\\s*roomid\\s*:\\s*['\"]([1-9]\\d{1,11})['\"]");
    /** 2026-09 实测页面 rid 脚本已改 JSON 内嵌形态 "uid":"x","rid":"y"(pure_live 原正则匹配不到,其六间房详情同样失效),备用对。 */
    private static final Pattern UID_RID_PAIR = Pattern.compile("\"uid\"\\s*:\\s*\"([1-9]\\d{1,12})\"\\s*,\\s*\"rid\"\\s*:\\s*\"([1-9]\\d{1,11})\"");
    private static final Pattern LINK_TAG = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern REL_CANONICAL = Pattern.compile("\\brel\\s*=\\s*[\"']canonical[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_ATTR = Pattern.compile("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    /** 搜索结果项:ul.search-user > li[data-uid](pure_live 用 DOM 查询,此处等价正则)。 */
    private static final Pattern SEARCH_ITEM = Pattern.compile("<li\\b[^>]*\\bdata-uid\\s*=\\s*[\"']?([1-9]\\d{1,12})[\"']?[^>]*>([\\s\\S]*?)</li>", Pattern.CASE_INSENSITIVE);
    /** 现行搜索卡片房号在 <span class="rid-num"> 里(href 已改指 /profile/主播id)。 */
    private static final Pattern RID_NUM = Pattern.compile("class=[\"']rid-num[\"'][^>]*>\\s*([1-9]\\d{0,11})\\s*<", Pattern.CASE_INSENSITIVE);
    private static final Pattern USER_BOX_LINK = Pattern.compile("<a\\b[^>]*\\bclass\\s*=\\s*[\"'][^\"']*\\buser-box\\b[^\"']*[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_TAG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_SRC_ATTR = Pattern.compile("\\bdata-src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC_ATTR = Pattern.compile("\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALIAS_TEXT = Pattern.compile("<[^>]*class\\s*=\\s*[\"'][^\"']*\\balias\\b[^\"']*[\"'][^>]*>([\\s\\S]*?)<", Pattern.CASE_INSENSITIVE);
    /** 分类=硬编码(area 非空时按 anchor_area 前端过滤,pure_live 同款)。 */
    private static final Map<String, String[]> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("all", new String[]{"全部", null});
        CATEGORIES.put("song", new String[]{"歌区", "歌区"});
        CATEGORIES.put("dance", new String[]{"舞区", "舞区"});
        CATEGORIES.put("talk", new String[]{"脱口秀", "脱口秀"});
        CATEGORIES.put("face", new String[]{"星颜", "星颜"});
        CATEGORIES.put("party", new String[]{"派对", "派对"});
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    /** 首页大厅文档已含全部分类房间,90 秒内复用,避免每次分页都重拉大页面(pure_live 同款)。 */
    private volatile List<JsonNode> directoryCache;
    private volatile long directoryFetchedAt;
    /** roomId → uid 缓存,省去详情每次都要先抓房间页 HTML。 */
    private final Map<String, String> userIdCache = new ConcurrentHashMap<>();

    public SixRoomService(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder.defaultHeader("User-Agent", USER_AGENT).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getType() {
        return "sixroom";
    }

    @Override
    public String getName() {
        return "六间房";
    }

    @Override
    public MovieList home() throws IOException {
        return toMovieList(directory(), "all", 1);
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();
        CATEGORIES.forEach((id, meta) -> {
            Category category = new Category();
            category.setType_id(getType() + "-" + id);
            category.setType_name(meta[0]);
            category.setType_flag(0);
            list.add(category);
        });
        result.setCategories(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        log.debug("category result: {}", result);
        return result;
    }

    @Override
    public MovieList list(String id, String ac, String sort, Integer pg) throws IOException {
        String[] parts = id.split("-");
        if (parts.length < 2 || !CATEGORIES.containsKey(parts[1])) {
            throw new BadRequestException("无效的分类ID: " + id);
        }
        int page = pg == null || pg < 1 ? 1 : pg;
        return toMovieList(directory(), parts[1], page);
    }

    @Override
    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        String keyword = wd == null ? "" : wd.trim();
        if (!keyword.isEmpty() && keyword.length() <= 80) {
            // 纯房间号/房间链接直达详情(pure_live 同款);房间不存在按无结果处理
            String directRoomId = parseRoomId(keyword);
            if (directRoomId != null) {
                try {
                    list.add(roomDetail(directRoomId));
                } catch (BadRequestException e) {
                    log.debug("六间房房间号直达失败: {}", keyword);
                }
            } else {
                parseSearchHtml(getBody(WEB_ORIGIN + "/search.php?type=use&key="
                        + URLEncoder.encode(keyword, StandardCharsets.UTF_8)), list);
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
        String roomId = parseRoomId(parts[1]);
        if (roomId == null) {
            throw new BadRequestException("无效的直播间ID: " + tid);
        }
        MovieList result = new MovieList();
        result.getList().add(roomDetail(roomId));
        result.setTotal(1);
        result.setLimit(1);
        log.debug("detail: {}", result);
        return result;
    }

    /** 目录分页:area 过滤后本地切片,每页 30 条(pure_live 客户端分页同款)。 */
    private MovieList toMovieList(List<JsonNode> rooms, String categoryId, int page) {
        String area = CATEGORIES.get(categoryId)[1];
        List<JsonNode> filtered = new ArrayList<>();
        for (JsonNode row : rooms) {
            if (area == null || area.equals(row.path("anchor_area").asText().trim())) {
                filtered.add(row);
            }
        }
        List<MovieDetail> list = new ArrayList<>();
        boolean hasMore = false;
        int start = (page - 1) * PAGE_SIZE;
        if (start < filtered.size()) {
            int end = Math.min(start + PAGE_SIZE, filtered.size());
            hasMore = end < filtered.size();
            for (JsonNode row : filtered.subList(start, end)) {
                list.add(parseDirectoryCard(row));
            }
        }
        MovieList result = new MovieList();
        result.setList(list);
        result.setPage(page);
        result.setPagecount(page + (hasMore ? 1 : 0));
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
    }

    /** 大厅卡片:rid/uid/liveid/username/livetitle/userMood/picuser/pospic/pic/anchor_area/count(pure_live 字段同款)。 */
    private MovieDetail parseDirectoryCard(JsonNode row) {
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(getType() + "$" + row.path("rid").asText().trim());
        String nick = text(row.path("username").asText(), "六间房");
        detail.setVod_name(firstText(row.path("livetitle").asText(), row.path("userMood").asText(), nick));
        String cover = firstImage(row, "pospic", "pic", "pospic_sp");
        String avatar = image(row.path("picuser").asText());
        detail.setVod_pic(!cover.isEmpty() ? cover : avatar);
        detail.setVod_actor(nick);
        int popularity = intValue(row.path("count").asText());
        detail.setVod_remarks(popularity >= 0 ? playCount(popularity) : nick);
        return detail;
    }

    /** 搜索页:li[data-uid] 内取 user-box 链接的房间号、alias 昵称、img 头像;无容器按空结果处理。 */
    private void parseSearchHtml(String html, List<MovieDetail> list) {
        // 2026-09 实测页面已无 .page-search-user 容器,卡片直接在 ul.search-user 下(pure_live 同版解析已失效);
        // href 也从房号链接改为 /profile/{主播id},真实房号只在 .rid-num 里
        if (html == null || !html.contains("search-user")) {
            // .remind 提示(无结果/需登录)或页面结构变化:返回空,不炸全局搜索
            log.debug("六间房搜索无结果页");
            return;
        }
        Set<String> seen = new HashSet<>();
        Matcher item = SEARCH_ITEM.matcher(html);
        while (item.find()) {
            String block = item.group(2);
            String roomId = null;
            Matcher rid = RID_NUM.matcher(block);
            if (rid.find()) {
                roomId = parseRoomId(rid.group(1).trim());
            }
            if (roomId == null) {
                Matcher link = USER_BOX_LINK.matcher(block);
                if (link.find()) {
                    Matcher href = HREF_ATTR.matcher(link.group());
                    if (href.find()) {
                        roomId = parseRoomId(href.group(1).trim());
                    }
                }
            }
            if (roomId == null || !seen.add(roomId)) {
                continue;
            }
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + roomId);
            String nick = "六间房";
            Matcher alias = ALIAS_TEXT.matcher(block);
            if (alias.find()) {
                nick = text(alias.group(1).replaceAll("<[^>]+>", ""), "六间房");
            }
            detail.setVod_name(nick);
            detail.setVod_pic(searchImage(block));
            detail.setVod_actor(nick);
            list.add(detail);
        }
    }

    /** 搜索头像:img 的 data-src 优先,src 兜底(pure_live 同款)。 */
    private String searchImage(String block) {
        Matcher img = IMG_TAG.matcher(block);
        if (img.find()) {
            Matcher dataSrc = DATA_SRC_ATTR.matcher(img.group());
            if (dataSrc.find()) {
                return image(dataSrc.group(1));
            }
            Matcher src = SRC_ATTR.matcher(img.group());
            if (src.find()) {
                return image(src.group(1));
            }
        }
        return "";
    }

    /** 房间详情:先从房间页解 uid(canonical 校验),再 POST inroom;flag!=001 即不可访问。 */
    private MovieDetail roomDetail(String roomId) throws IOException {
        String userId = resolveUserId(roomId);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json,text/plain,*/*");
        headers.set(HttpHeaders.REFERER, MOBILE_ORIGIN + "/?ver=8.0.3&build=4");
        headers.set(HttpHeaders.USER_AGENT, MOBILE_USER_AGENT);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        // rid 恒空、ruid 为主播 uid,其余为固定伪装参数(pure_live 同款)
        String form = "av=3.1&encpass=&logiuid=&project=v6iphone&rate=1&rid=&ruid=" + userId;
        String body = restTemplate.exchange(WEB_ORIGIN + "/coop/mobile/index.php?padapi=coop-mobile-inroom.php",
                HttpMethod.POST, new HttpEntity<>(form, headers), String.class).getBody();
        JsonNode root = objectMapper.readTree(body == null ? "" : body);
        if (!"001".equals(root.path("flag").asText().trim())) {
            throw new BadRequestException("房间不可访问: " + roomId);
        }
        JsonNode content = root.path("content");
        JsonNode roomInfo = content.path("roominfo");
        JsonNode liveInfo = content.path("liveinfo");
        JsonNode params = content.path("roomParamInfo");
        if (!roomInfo.isObject() || !liveInfo.isObject()) {
            throw new BadRequestException("房间数据格式异常: " + roomId);
        }
        // 返回的 rid/uid 必须与请求一致(pure_live identity 校验)
        String actualUserId = firstText0(roomInfo.path("id").asText(), params.path("uid").asText());
        if (!roomId.equals(roomInfo.path("rid").asText().trim()) || !userId.equals(actualUserId)) {
            throw new BadRequestException("房间信息不匹配: " + roomId);
        }
        String liveId = liveInfo.path("id").asText().trim();
        String flvTitle = liveInfo.path("flvtitle").asText().trim();
        // isPriveRoom=1 或 blackScreenInfo.msg 非空为受限房(pure_live 同款)
        boolean restricted = truthy(content.path("isPriveRoom"))
                || !text(content.path("blackScreenInfo").path("msg").asText(), "").isEmpty();
        boolean live = !restricted && !liveId.isEmpty() && !flvTitle.isEmpty();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(getType() + "$" + roomId);
        String nick = text(roomInfo.path("alias").asText(), "六间房");
        detail.setVod_name(firstText(liveInfo.path("title").asText(), roomInfo.path("userMood").asText(), nick));
        String cover = firstImage(liveInfo, "spredPic", "pospic", "largepic", "pic");
        String avatar = firstImage(roomInfo, "headPicUrl", "picuser");
        detail.setVod_pic(!cover.isEmpty() ? cover : avatar);
        detail.setVod_actor(nick);
        detail.setVod_area(firstText0(roomInfo.path("anchor_area").asText(), roomInfo.path("rtypename").asText()));
        detail.setVod_remarks(restricted ? "私密房" : live ? "直播中" : "未开播");
        if (live) {
            String media = mediaUrl(userId, liveId, flvTitle);
            if (media != null) {
                JsonNode meta = streamMetadata(liveInfo, flvTitle);
                String resolution = meta.path("resolution").asText().trim();
                int bitrate = intValue(meta.path("videoBitrate").asText());
                if (bitrate < 0) {
                    bitrate = intValue(meta.path("bitrate").asText());
                }
                String label = resolution.isEmpty() ? "原画" : resolution;
                if (bitrate >= 0) {
                    label += "·" + bitrate + "kbps";
                }
                detail.setVod_play_from("线路1");
                detail.setVod_play_url(label + "$" + media);
            }
        }
        return detail;
    }

    /** 房间页解主播 uid:canonical 必须回指本房间(否则视为不存在),再匹配 rid/roomid 成对脚本。 */
    private String resolveUserId(String roomId) {
        String cached = userIdCache.get(roomId);
        if (cached != null) {
            return cached;
        }
        String html = getBody(WEB_ORIGIN + "/" + roomId);
        String canonicalRoomId = null;
        Matcher linkTag = LINK_TAG.matcher(html);
        while (linkTag.find()) {
            String tag = linkTag.group();
            if (REL_CANONICAL.matcher(tag).find()) {
                Matcher href = HREF_ATTR.matcher(tag);
                if (href.find()) {
                    canonicalRoomId = parseRoomId(href.group(1).trim());
                }
                break;
            }
        }
        if (!roomId.equals(canonicalRoomId)) {
            throw new BadRequestException("房间不存在: " + roomId);
        }
        Matcher matcher = ROOM_UID.matcher(html);
        while (matcher.find()) {
            if (matcher.group(2).equals(roomId)) {
                String userId = matcher.group(1);
                userIdCache.put(roomId, userId);
                return userId;
            }
        }
        Matcher pair = UID_RID_PAIR.matcher(html);
        while (pair.find()) {
            if (pair.group(2).equals(roomId)) {
                String userId = pair.group(1);
                userIdCache.put(roomId, userId);
                return userId;
            }
        }
        throw new BadRequestException("无法解析房间用户: " + roomId);
    }

    /** FLV 地址:flvtitle 必须形如 v{uid}-{liveId}(-many),防串流/投毒(pure_live 白名单校验精简版)。 */
    private String mediaUrl(String userId, String liveId, String flvTitle) {
        if (!USER_ID.matcher(userId).matches() || !USER_ID.matcher(liveId).matches()) {
            return null;
        }
        String expected = "v" + userId + "-" + liveId;
        if (!flvTitle.equals(expected) && !flvTitle.equals(expected + "-many")) {
            return null;
        }
        return MEDIA_ORIGIN + "/httpflv/" + flvTitle + ".flv";
    }

    /** 码流元数据:liveinfo.content[*].streamInfo[flvtitle] 藏 resolution/bitrate(pure_live 同款);
     *  实测部分房 content[n] 直接含 flvtitle 无 streamInfo 层,fallback 到 lane 自身。 */
    private JsonNode streamMetadata(JsonNode liveInfo, String flvTitle) {
        for (JsonNode lane : liveInfo.path("content")) {
            JsonNode exact = lane.path("streamInfo").path(flvTitle);
            if (exact.isObject()) {
                return exact;
            }
            if (flvTitle.equals(lane.path("flvtitle").asText()) && lane.isObject()) {
                return lane;
            }
        }
        return objectMapper.createObjectNode();
    }

    /** 大厅房间快照:拉首页 HTML,解内嵌 Smarty JSON,typeList 可能是二次编码字符串(双重坑)。 */
    private List<JsonNode> directory() throws IOException {
        List<JsonNode> cached = directoryCache;
        if (cached != null && System.currentTimeMillis() - directoryFetchedAt < DIRECTORY_CACHE_MS) {
            return cached;
        }
        JsonNode typeList = objectMapper.readTree(extractSmartyJson(getBody(WEB_ORIGIN + "/"))).path("typeList");
        if (typeList.isTextual()) {
            typeList = objectMapper.readTree(typeList.asText());
        }
        if (!typeList.isArray()) {
            throw new BadRequestException("六间房首页数据格式异常");
        }
        List<JsonNode> rooms = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode row : typeList) {
            String rid = row.path("rid").asText().trim();
            String uid = row.path("uid").asText().trim();
            if (ROOM_ID.matcher(rid).matches() && USER_ID.matcher(uid).matches() && seen.add(rid)) {
                rooms.add(row);
            }
        }
        if (rooms.isEmpty()) {
            throw new BadRequestException("六间房首页无房间数据");
        }
        directoryCache = rooms;
        directoryFetchedAt = System.currentTimeMillis();
        return rooms;
    }

    /** 提取 window.__SMARTY_ALL_VARIABLES__ = {...}:花括号配对扫描,需跳过字符串内的引号转义(pure_live 同款)。 */
    private String extractSmartyJson(String html) {
        int markerAt = html == null ? -1 : html.indexOf(SMARTY_MARKER);
        if (markerAt < 0) {
            throw new BadRequestException("六间房首页缺少内嵌数据");
        }
        int start = html.indexOf('{', markerAt + SMARTY_MARKER.length());
        if (start < 0) {
            throw new BadRequestException("六间房内嵌数据不完整");
        }
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return html.substring(start, i + 1);
            }
        }
        throw new BadRequestException("六间房内嵌数据不完整");
    }

    /** 房间号:纯数字,或 v.6.cn/m.6.cn 的 /{id}、/profile/{id} 链接(pure_live 同款)。 */
    private String parseRoomId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (ROOM_ID.matcher(value).matches()) {
            return value;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            int port = uri.getPort();
            if (!"http".equals(scheme) && !"https".equals(scheme)
                    || !"v.6.cn".equals(host) && !"m.6.cn".equals(host)
                    || port != -1 && port != 80 && port != 443) {
                return null;
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            String[] segments = path.split("/");
            List<String> parts = new ArrayList<>();
            for (String part : segments) {
                if (!part.isEmpty()) {
                    parts.add(part);
                }
            }
            if (parts.size() == 1 && ROOM_ID.matcher(parts.get(0)).matches()) {
                return parts.get(0);
            }
            if (parts.size() == 2 && "profile".equalsIgnoreCase(parts.get(0)) && ROOM_ID.matcher(parts.get(1)).matches()) {
                return parts.get(1);
            }
        } catch (Exception ignored) {
            // 非法 URL 一律视为无法解析
        }
        return null;
    }

    /** 图片规整(pure_live 同款)://与 http: 补 https,仅放行 6.cn/6rooms.com/xiu123.cn 系 host。 */
    private String image(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "null".equals(value)) {
            return "";
        }
        if (value.startsWith("//")) {
            value = "https:" + value;
        } else if (value.startsWith("http://")) {
            value = "https://" + value.substring(7);
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            boolean hostOk = host.equals("6.cn") || host.endsWith(".6.cn")
                    || host.equals("6rooms.com") || host.endsWith(".6rooms.com")
                    || host.equals("xiu123.cn") || host.endsWith(".xiu123.cn");
            if (!"https".equals(uri.getScheme()) || !hostOk
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getFragment() != null) {
                return "";
            }
            return uri.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String firstImage(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = image(node.path(field).asText());
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String getBody(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.7");
        headers.set(HttpHeaders.REFERER, WEB_ORIGIN + "/");
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
    }

    private boolean truthy(JsonNode node) {
        return node.asBoolean(false) || "1".equals(node.asText().trim());
    }

    private String text(String raw, String fallback) {
        String value = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        return value.isEmpty() || "null".equals(value) ? fallback : value;
    }

    private String firstText(String... values) {
        String value = firstText0(values);
        return value.isEmpty() ? "六间房" : value;
    }

    private String firstText0(String... values) {
        for (String value : values) {
            if (value != null) {
                String text = value.replaceAll("\\s+", " ").trim();
                if (!text.isEmpty() && !"null".equals(text)) {
                    return text;
                }
            }
        }
        return "";
    }

    private int intValue(String raw) {
        String text = raw == null ? "" : raw.trim().replace(",", "");
        if (text.isEmpty()) {
            return -1;
        }
        try {
            long value = Long.parseLong(text);
            return value >= 0 && value <= Integer.MAX_VALUE ? (int) value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
