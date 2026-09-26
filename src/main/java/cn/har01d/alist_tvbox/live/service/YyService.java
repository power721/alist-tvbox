package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.har01d.alist_tvbox.util.Constants.FOLDER;

/**
 * YY直播(pure_live yy 适配器同源契约):
 * 目录=yyweb/module/data/header 顶级频道+getCategory.action 子分区(自带官方分区封面);
 * 房间=more/page.action(子分区页 HTML 的 pageInfo{moduleId,biz,subBiz} 作查询参数,懒加载缓存);
 * 详情=api/liveInfoDetail;播放=stream-manager FLV(gear 档位)或 interface.yy.com 匿名 HLS 兜底。
 * 流地址签名 t 租约仅约 10 分钟,一律包代理:代理端每次连接重取当前地址(LOOK 同款),断流自动续租。
 */
@Slf4j
@Service
public class YyService implements LivePlatform {
    private static final String WEB_ORIGIN = "https://www.yy.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    private static final String MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1";
    private static final String STREAM_SDK_VERSION = "5.23.0-beta.2";
    /** 房间号:1-18 位纯数字(pure_live sid 同形态)。 */
    private static final Pattern ROOM_ID = Pattern.compile("^[1-9][0-9]{1,17}$");
    /** 子分区页 pageInfo(pure_live 同款):块内再取 pageBar 的 moduleId/biz/subBiz。 */
    private static final Pattern PAGE_INFO = Pattern.compile("pageInfo\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*;", Pattern.MULTILINE);
    private static final Pattern MODULE_ID = Pattern.compile("moduleId\\s*:\\s*['\"]?(-?\\d+)");
    private static final Pattern BIZ = Pattern.compile("biz\\s*:\\s*['\"]([^'\"]+)");
    private static final Pattern SUB_BIZ = Pattern.compile("subBiz\\s*:\\s*['\"]([^'\"]+)");
    private static final int PAGE_SIZE = 30;
    private static final String[] HLS_RATES = {"4000", "1200"};

    /** 子分区(官方分区封面+分区页地址);进程内缓存 10 分钟,结构少变。 */
    private final Cache<Integer, List<JsonNode>> subAreas = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(100)
            .build();
    /** 子分区 pageInfo 查询参数(分区页 HTML 解析);官方页极少改版,缓存 30 分钟。 */
    private final Cache<Integer, Map<String, String>> pageParams = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .maximumSize(500)
            .build();
    /** biz→分区名(条目 area 显示用),随 pageInfo 解析填充。 */
    private final Map<String, String> bizNames = new java.util.concurrent.ConcurrentHashMap<>();

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LiveProxyService proxyService;

    public YyService(RestTemplateBuilder builder, ObjectMapper objectMapper, LiveProxyService proxyService) {
        this.restTemplate = builder.defaultHeader("User-Agent", USER_AGENT).build();
        this.objectMapper = objectMapper;
        this.proxyService = proxyService;
    }

    /**
     * 供直播代理续租:重调 stream-manager 拿当前签名 FLV 地址(t 租约仅约 10 分钟,
     * 断流/detail 缓存过期地址都会失效)。gear 非法回落流畅档 1;下播/异常返回 null。
     */
    public String renewStreamUrl(String roomId, String gear) {
        try {
            JsonNode payload = channelStreams(roomId, normalizeGear(gear));
            String url = firstStreamLine(payload);
            return url;
        } catch (Exception e) {
            log.warn("YY流地址续租失败: {} gear={}", roomId, gear, e);
            return null;
        }
    }

    /** 供直播代理续租:重取匿名 HLS 清单地址;下播/异常返回 null。 */
    public String renewHlsUrl(String roomId, String rate) {
        try {
            JsonNode payload = mobileHls(roomId, rate);
            return payload == null ? null : payload.path("hls").asText("").trim();
        } catch (Exception e) {
            log.warn("YY HLS续租失败: {} rate={}", roomId, rate, e);
            return null;
        }
    }

    @Override
    public String getType() {
        return "yy";
    }

    /** 流地址签名租约仅约 10 分钟,经直播代理每次连接重取+断流续租。 */
    @Override
    public boolean isProxied() {
        return true;
    }

    @Override
    public String getName() {
        return "YY直播";
    }

    @Override
    public MovieList home() throws IOException {
        return toMovieList(rooms(1, Map.of("biz", "other", "subBiz", "idx", "moduleId", "-1")));
    }

    @Override
    public CategoryList category() throws IOException {
        JsonNode root = getJson(WEB_ORIGIN + "/yyweb/module/data/header");
        List<Category> list = new ArrayList<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (JsonNode tab : root.path("categoryTabs")) {
            int tabId = tab.path("id").asInt(0);
            String title = tab.path("title").asText("");
            if (tabId <= 0 || title.isEmpty()) {
                continue;
            }
            Category category = new Category();
            category.setType_id(getType() + "-" + tabId);
            category.setType_name(title);
            category.setType_flag(0);
            // 频道无官方图:封面取组内首个子分区的官方分区图;并发预热子分区缓存供 list() 直接命中
            futures.add(CompletableFuture.runAsync(() -> category.setCover(firstAreaCover(tabId))));
            list.add(category);
        }
        for (CompletableFuture<Void> future : futures) {
            future.join();
        }
        CategoryList result = new CategoryList();
        result.setCategories(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        log.debug("category result: {}", result);
        return result;
    }

    @Override
    public MovieList list(String id, String ac, String sort, Integer pg) throws IOException {
        String[] parts = id.split("-");
        if (parts.length < 2 || !parts[1].matches("\\d+")) {
            throw new BadRequestException("无效的分类ID: " + id);
        }
        int page = pg == null || pg < 1 ? 1 : pg;
        int tabId = Integer.parseInt(parts[1]);
        if (parts.length == 2) {
            // 频道级:子分区文件夹
            MovieList result = new MovieList();
            List<MovieDetail> list = new ArrayList<>();
            for (JsonNode area : subAreas(tabId)) {
                MovieDetail detail = new MovieDetail();
                detail.setVod_id(getType() + "-" + tabId + "-" + area.path("id").asInt());
                detail.setVod_name(area.path("title").asText(""));
                detail.setVod_pic(picture(area.path("cover").asText("")));
                detail.setVod_tag(FOLDER);
                list.add(detail);
            }
            if (list.isEmpty()) {
                throw new BadRequestException("频道下没有子分区: " + id);
            }
            result.setList(list);
            result.setPage(page);
            result.setPagecount(1);
            result.setTotal(list.size());
            result.setLimit(list.size());
            return result;
        }
        if (!parts[2].matches("\\d+")) {
            throw new BadRequestException("无效的分类ID: " + id);
        }
        int areaId = Integer.parseInt(parts[2]);
        Map<String, String> params = pageInfo(tabId, areaId);
        if (params == null) {
            throw new BadRequestException("分区数据不可用: " + id);
        }
        List<JsonNode> items = rooms(page, params);
        MovieList result = toMovieList(items);
        result.setPage(page);
        // 满页视为还有下一页
        result.setPagecount(page + (items.size() >= PAGE_SIZE ? 1 : 0));
        return result;
    }

    @Override
    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        String keyword = wd == null ? "" : wd.trim();
        if (!keyword.isEmpty() && keyword.length() <= 100) {
            String directRoomId = parseRoomId(keyword);
            if (directRoomId != null) {
                try {
                    list.add(roomDetail(directRoomId, null));
                } catch (BadRequestException e) {
                    log.debug("YY房间号直达失败: {}", keyword);
                }
            } else {
                JsonNode root = getJson(WEB_ORIGIN + "/apiSearch/doSearch.json?q=" + urlEncode(keyword) + "&t=120&n=1");
                for (JsonNode item : root.path("data").path("searchResult").path("response").path("120").path("docs")) {
                    String roomId = item.path("sid").asText("").trim();
                    if (!ROOM_ID.matcher(roomId).matches()) {
                        continue;
                    }
                    boolean live = isLive(item.path("liveOn"));
                    MovieDetail detail = new MovieDetail();
                    detail.setVod_id(getType() + "$" + roomId);
                    String nick = text(item.path("name").asText());
                    detail.setVod_name(firstText(item.path("channelName").asText(), nick));
                    detail.setVod_pic(picture(firstText(item.path("posterurl").asText(), item.path("headurl").asText())));
                    detail.setVod_actor(nick);
                    detail.setVod_area(bizName(item.path("biz").asText("")));
                    detail.setVod_remarks(live ? playCount(intValue(item.path("users").asText())) : "未开播");
                    list.add(detail);
                }
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
        result.getList().add(roomDetail(roomId, client));
        result.setTotal(1);
        result.setLimit(1);
        log.debug("detail: {}", result);
        return result;
    }

    /** 详情:liveInfoDetail 取元数据与 ssid;流条目=stream-manager 各档 FLV+匿名 HLS 两档,全部包代理续租。 */
    private MovieDetail roomDetail(String roomId, String client) throws IOException {
        JsonNode root = getJson(WEB_ORIGIN + "/api/liveInfoDetail/" + roomId + "/" + roomId + "/0");
        int resultCode = root.path("resultCode").asInt(-1);
        if (resultCode != 0) {
            throw new BadRequestException("房间不存在: " + roomId);
        }
        JsonNode item = root.path("data");
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(getType() + "$" + roomId);
        if (!item.isObject() || item.path("sid").asText("").isEmpty()) {
            detail.setVod_name("未开播");
            detail.setVod_remarks("未开播");
            return detail;
        }
        String nick = text(item.path("name").asText());
        detail.setVod_name(firstText(item.path("desc").asText(), nick));
        detail.setVod_pic(picture(item.path("thumb2").asText("")));

        detail.setVod_actor(nick);
        detail.setVod_area(bizName(item.path("biz").asText("")));

        List<String> directEntries = new ArrayList<>();
        List<String> proxyEntries = new ArrayList<>();
        try {
            // gear=1 一次响应带全档列表(channel_stream_info.streams[].json.gear_info),再逐档取线路地址
            JsonNode first = channelStreams(roomId, "1");
            List<String[]> gears = parseGears(first);
            for (String[] gear : gears) {
                JsonNode payload = "1".equals(gear[0]) ? first : channelStreams(roomId, gear[0]);
                addEntries(directEntries, proxyEntries, gear[1], firstStreamLine(payload), "&yyq=" + gear[0], roomId);
            }
        } catch (Exception e) {
            log.warn("YY stream-manager 失败: {}", roomId, e);
        }
        for (String rate : HLS_RATES) {
            JsonNode payload = mobileHls(roomId, rate);
            if (payload != null) {
                addEntries(directEntries, proxyEntries, rate.equals(HLS_RATES[0]) ? "HLS高清" : "HLS流畅",
                        payload.path("hls").asText("").trim(), "&yyr=" + rate, roomId);
            }
        }
        detail.setVod_remarks(directEntries.isEmpty() ? "未开播" : "直播中 " + playCount(intValue(item.path("users").asText())));
        if (!directEntries.isEmpty()) {
            // 代理条目续租:签名租约仅约10分钟,代理端每次连接重取当前地址;dual=直连优先双线路(网页端恒走代理)
            String mode = proxyService != null && proxyService.isDualProxyMode() && !"web".equals(client) ? "dual" : "proxy";
            String[] lines = buildPlayLines(directEntries, proxyEntries, mode);
            detail.setVod_play_from(lines[0]);
            detail.setVod_play_url(lines[1]);
        }
        return detail;
    }

    /** 档位列表(gear 编号+名称,按码率降序;无 gear_info 的纯音频流跳过)。 */
    private List<String[]> parseGears(JsonNode payload) throws IOException {
        Map<String, String> byGear = new LinkedHashMap<>();
        for (JsonNode stream : payload.path("channel_stream_info").path("streams")) {
            JsonNode gearInfo = objectMapper.readTree(stream.path("json").asText("")).path("gear_info");
            String gear = gearInfo.path("gear").asText("").trim();
            String name = gearInfo.path("name").asText("").trim();
            if (gear.isEmpty() || name.isEmpty() || byGear.containsKey(gear)) {
                continue;
            }
            byGear.put(gear, name);
        }
        return byGear.entrySet().stream()
                .sorted((a, b) -> Integer.parseInt(b.getKey()) - Integer.parseInt(a.getKey()))
                .map(e -> new String[]{e.getKey(), e.getValue()})
                .toList();
    }

    /** stream-manager 请求(pure_live 同款 text/plain JSON 体);流线取首条 cdn_info.url。 */
    private JsonNode channelStreams(String roomId, String gear) throws IOException {
        long sequence = System.currentTimeMillis();
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("seq", sequence);
        head.put("appidstr", "0");
        head.put("bidstr", "121");
        head.put("cidstr", roomId);
        head.put("sidstr", roomId);
        head.put("uid64", 0);
        head.put("client_type", 108);
        head.put("client_ver", STREAM_SDK_VERSION);
        head.put("stream_sys_ver", 1);
        head.put("app", "yylive_web");
        head.put("playersdk_ver", STREAM_SDK_VERSION);
        head.put("thundersdk_ver", "0");
        head.put("streamsdk_ver", STREAM_SDK_VERSION);
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("client", "web");
        client.put("model", "web0");
        client.put("cpu", "");
        client.put("graphics_card", "");
        client.put("os", "chrome");
        client.put("osversion", "128.0.0.0");
        client.put("vsdk_version", "");
        client.put("app_identify", "");
        client.put("app_version", "");
        client.put("business", "");
        client.put("width", "1366");
        client.put("height", "768");
        client.put("scale", "");
        client.put("client_type", 8);
        client.put("h265", 0);
        Map<String, Object> avp = new LinkedHashMap<>();
        avp.put("version", 1);
        avp.put("client_type", 8);
        avp.put("service_type", 0);
        avp.put("imsi", 0);
        avp.put("send_time", sequence / 1000);
        avp.put("line_seq", -1);
        avp.put("gear", Integer.parseInt(gear));
        avp.put("ssl", 1);
        avp.put("stream_format", 0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("head", head);
        body.put("client_attribute", client);
        body.put("avp_parameter", avp);

        HttpHeaders headers = browserHeaders();
        // YY web SDK 以 text/plain 发这团 JSON,部分频道拒收 application/json(pure_live 同注)
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.set(HttpHeaders.REFERER, WEB_ORIGIN + "/" + roomId + "/" + roomId);
        String url = "https://stream-manager.yy.com/v3/channel/streams?uid=0&cid=" + roomId
                + "&sid=" + roomId + "&appid=0&sequence=" + sequence + "&encode=json";
        // 响应 Content-Type 为非法的 "json; charset=utf-8",走转换器会炸 mime 解析,手动读写 body
        String json = objectMapper.writeValueAsString(body);
        String response = restTemplate.execute(url, HttpMethod.POST, request -> {
            request.getHeaders().putAll(headers);
            request.getBody().write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }, clientResponse -> new String(clientResponse.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        return objectMapper.readTree(response == null ? "" : response);
    }

    /** 匿名手机端 HLS(pure_live 同款兜底链路,部分官方频道拒 stream-manager 时仍可用);失败返回 null。 */
    private JsonNode mobileHls(String roomId, String rate) {
        try {
            HttpHeaders headers = browserHeaders();
            headers.set(HttpHeaders.USER_AGENT, MOBILE_UA);
            headers.set(HttpHeaders.REFERER, "https://wap.yy.com/mobileweb/" + roomId + "/" + roomId);
            String body = restTemplate.exchange("https://interface.yy.com/hls/new/get/" + roomId + "/" + roomId
                            + "/" + rate + "?source=wapyy&callback=", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                    .getBody();
            if (body == null) {
                return null;
            }
            // 响应为 JSONP 包装 ({...}),取首 { 到末 } 的 JSON 段(pure_live parseMobileHlsPayload)
            int start = body.indexOf('{');
            int end = body.lastIndexOf('}');
            if (start < 0 || end < start) {
                return null;
            }
            JsonNode payload = objectMapper.readTree(body.substring(start, end + 1));
            return payload.path("code").asInt(-1) == 0 && payload.path("hls").asText("").startsWith("http")
                    ? payload : null;
        } catch (Exception e) {
            log.debug("YY mobile HLS 获取失败: {} rate={}", roomId, rate, e);
            return null;
        }
    }

    /** stream_line_addr 首条线路地址(FLV;校验 *.yy.com 域与 http(s) 协议),无线路/不合法返回 null。 */
    private String firstStreamLine(JsonNode payload) {
        for (JsonNode line : payload.path("avp_info_res").path("stream_line_addr")) {
            String url = line.path("cdn_info").path("url").asText("").trim();
            if (url.startsWith("//")) {
                url = "https:" + url;
            }
            try {
                URI uri = URI.create(url);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
                if (("http".equals(scheme) || "https".equals(scheme)) && host.endsWith(".yy.com")) {
                    return "https".equals(scheme) ? url : "https://" + url.substring(scheme.length() + 3);
                }
            } catch (Exception e) {
                log.debug("YY播放地址校验失败: {}", url);
            }
        }
        return null;
    }

    /**
     * 播放条目双收集:直连条目=原始地址;代理条目=包代理+yy 续租参数(签名租约仅约 10 分钟,
     * 代理端每次连接重取当前地址)。buildProxyUrl 降级(无请求上下文)或探针无代理实例时不产代理条目。
     */
    private void addEntries(List<String> directEntries, List<String> proxyEntries,
                            String label, String raw, String extra, String roomId) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        directEntries.add(label + "$" + raw);
        if (proxyService == null) {
            return;
        }
        String proxyUrl = proxyService.buildProxyUrl(raw);
        if (!proxyUrl.equals(raw)) {
            proxyEntries.add(label + "$" + proxyUrl + "&yy=" + roomId + extra);
        }
    }

    /** 分类房间:page.action 带 pageInfo 参数;page 由调用方传入。 */
    private List<JsonNode> rooms(int page, Map<String, String> params) throws IOException {
        StringBuilder url = new StringBuilder(WEB_ORIGIN + "/more/page.action?page=").append(page)
                .append("&pageSize=").append(PAGE_SIZE);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append("&").append(entry.getKey()).append("=").append(urlEncode(entry.getValue()));
        }
        return parseRooms(getJson(url.toString()));
    }

    private List<JsonNode> parseRooms(JsonNode root) {
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode item : root.path("data").path("data")) {
            if (ROOM_ID.matcher(item.path("sid").asText("").trim()).matches()) {
                list.add(item);
            }
        }
        return list;
    }

    /** 子分区列表(getCategory.action,官方分区封面)。 */
    private List<JsonNode> subAreas(int parentId) throws IOException {
        List<JsonNode> cached = subAreas.getIfPresent(parentId);
        if (cached != null) {
            return cached;
        }
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode item : getJson(WEB_ORIGIN + "/c/yycom/category/getCategory.action?parentId=" + parentId).path("data")) {
            if (item.path("id").asInt(0) > 0 && !item.path("title").asText("").isEmpty()) {
                list.add(item);
            }
        }
        subAreas.put(parentId, list);
        return list;
    }

    private String firstAreaCover(int parentId) {
        try {
            for (JsonNode area : subAreas(parentId)) {
                String cover = picture(area.path("cover").asText(""));
                if (!cover.isEmpty()) {
                    return cover;
                }
            }
        } catch (Exception e) {
            log.debug("YY频道封面获取失败: {}", parentId);
        }
        return null;
    }

    /**
     * 子分区 pageInfo 查询参数:抓分区页 HTML 解析 pageBar 的 moduleId/biz/subBiz(pure_live 同款)。
     * 解析失败返回 null(分区页改版/下线)。
     */
    private Map<String, String> pageInfo(int tabId, int areaId) throws IOException {
        return pageParams.get(areaId, id -> {
            try {
                String url = null;
                for (JsonNode area : subAreas(tabId)) {
                    if (area.path("id").asInt() == id) {
                        url = area.path("url").asText("").trim();
                        break;
                    }
                }
                if (url.isEmpty()) {
                    return null;
                }
                // 分区页 url 官方给 http://,请求会 301 到 https 且重定向体无 pageInfo(pure_live normalizeWebUrl 同款)
                if (url.startsWith("//")) {
                    url = "https:" + url;
                } else if (url.startsWith("http://")) {
                    url = "https://" + url.substring(7);
                }
                if (!url.startsWith("https://")) {
                    return null;
                }
                HttpHeaders headers = browserHeaders();
                headers.set(HttpHeaders.REFERER, WEB_ORIGIN + "/");
                String html = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
                if (html == null) {
                    return null;
                }
                Matcher block = PAGE_INFO.matcher(html);
                if (!block.find()) {
                    return null;
                }
                String source = block.group(1);
                String moduleId = match(MODULE_ID, source);
                String biz = match(BIZ, source);
                String subBiz = match(SUB_BIZ, source);
                if (moduleId == null || biz == null || subBiz == null) {
                    return null;
                }
                Map<String, String> params = new LinkedHashMap<>();
                params.put("moduleId", moduleId);
                params.put("biz", biz);
                params.put("subBiz", subBiz);
                if (!bizNames.containsKey(biz)) {
                    // 条目 area 显示:同分区页的主分区名不可得,用子分区名近似(pure_live bizAreaNameMap 同用途)
                    for (JsonNode area : subAreas(tabId)) {
                        if (area.path("id").asInt() == id) {
                            bizNames.putIfAbsent(biz, area.path("title").asText(""));
                        }
                    }
                }
                return params;
            } catch (Exception e) {
                log.warn("YY分区pageInfo解析失败: tab={} area={}", tabId, id, e);
                return null;
            }
        });
    }

    /** 分类/搜索条目转 MovieList;hasMore 按满页判定。 */
    private MovieList toMovieList(List<JsonNode> items) {
        List<MovieDetail> list = new ArrayList<>();
        for (JsonNode item : items) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + item.path("sid").asText("").trim());
            String nick = text(item.path("name").asText());
            detail.setVod_name(firstText(item.path("desc").asText(), nick));
            detail.setVod_pic(picture(item.path("thumb2").asText("")));
            detail.setVod_actor(nick);
            detail.setVod_area(bizName(item.path("biz").asText("")));
            detail.setVod_remarks(playCount(intValue(item.path("users").asText())));
            list.add(detail);
        }
        MovieList result = new MovieList();
        result.setList(list);
        result.setPage(1);
        result.setPagecount(1);
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
    }

    private String bizName(String biz) {
        if (biz == null || biz.isEmpty()) {
            return "";
        }
        return bizNames.getOrDefault(biz, "");
    }

    private JsonNode getJson(String url) throws IOException {
        HttpHeaders headers = browserHeaders();
        headers.set(HttpHeaders.REFERER, WEB_ORIGIN + "/");
        String body = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
        return objectMapper.readTree(body == null ? "" : body);
    }

    private HttpHeaders browserHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "*/*");
        headers.set(HttpHeaders.ORIGIN, WEB_ORIGIN);
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        return headers;
    }

    /** 房间号:纯数字,或 www.yy.com/{roomId} 直播间链接。 */
    private String parseRoomId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (ROOM_ID.matcher(value).matches()) {
            return value;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (!"http".equals(scheme) && !"https".equals(scheme) || !host.endsWith(".yy.com")) {
                return null;
            }
            String[] segments = (uri.getPath() == null ? "" : uri.getPath()).split("/");
            for (String segment : segments) {
                if (!segment.isEmpty()) {
                    return ROOM_ID.matcher(segment).matches() ? segment : null;
                }
            }
        } catch (Exception ignored) {
            // 非法 URL 一律视为无法解析
        }
        return null;
    }

    private String normalizeGear(String gear) {
        return gear != null && gear.matches("[1-9]\\d{0,2}") ? gear : "1";
    }

    private boolean isLive(JsonNode value) {
        if (value.isBoolean()) {
            return value.asBoolean(false);
        }
        String text = value.asText("").trim().toLowerCase();
        return "1".equals(text) || "true".equals(text) || "live".equals(text);
    }

    private String match(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /** 图片规整(pure_live validImgUrl 同款):强制 https,解析失败丢弃。 */
    private String picture(String raw) {
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
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!"https".equals(scheme) || uri.getHost() == null || uri.getHost().isEmpty()) {
                return "";
            }
            return value;
        } catch (Exception e) {
            return "";
        }
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String text(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            String text = text(value);
            if (!text.isEmpty() && !"null".equals(text)) {
                return text;
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
