package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AcFun 直播(pure_live acfun 适配器同源契约):
 * 目录=live.acfun.cn/api/channel/list(pcursor 即页码,翻页实测可行);分类=filters 参数
 * ([{filterType,filterId}] JSON);搜索=www.acfun.cn/search BigPipe(ajaxpipe=1);
 * 播放=游客登录(id.app.acfun.cn visitor/login 5 分钟会话)+快手系 startPlay(必须带
 * Referer 与 _did Cookie,否则 result=52)→liveAdaptiveManifest 多画质。
 */
@Slf4j
@Service
public class AcfunService implements LivePlatform {
    private static final String ORIGIN = "https://live.acfun.cn";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private static final long VISITOR_TTL_MS = 5 * 60 * 1000L;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    /** 游客会话(userId+token)内存缓存,5 分钟过期(pure_live 同款短会话)。 */
    private volatile VisitorSession visitor;
    private volatile long visitorExpiresAt;
    /** 分类元数据缓存:key=filterType_filterId,value=[名称, 封面]。 */
    private volatile Map<String, String[]> categoryMeta;

    public AcfunService(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder.defaultHeader("User-Agent", USER_AGENT).build();
        this.objectMapper = objectMapper;
    }

    private record VisitorSession(String did, String userId, String token) {
    }

    @Override
    public String getType() {
        return "acfun";
    }

    @Override
    public String getName() {
        return "AcFun";
    }

    @Override
    public MovieList home() throws IOException {
        return toMovieList(directory(1, null));
    }

    @Override
    public CategoryList category() throws IOException {
        Map<String, String[]> names = loadCategories();
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();
        names.forEach((id, meta) -> {
            Category category = new Category();
            category.setType_id(getType() + "-" + id);
            category.setType_name(meta[0]);
            category.setType_flag(0);
            category.setCover(meta[1]);
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
        if (parts.length < 2) {
            throw new BadRequestException("无效的分类ID: " + id);
        }
        int page = pg == null || pg < 1 ? 1 : pg;
        return toMovieList(directory(page, encodeFilter(parts[1])));
    }

    @Override
    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        // 官方匿名搜索是 BigPipe JSON+HTML(ajaxpipe=1),只解析 content 不执行脚本(pure_live 同款)
        String url = "https://www.acfun.cn/search?keyword=" + URLEncoder.encode(wd, StandardCharsets.UTF_8)
                + "&type=user&pCursor=1&quickViewId=up-list&reqID=1&ajaxpipe=1";
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.REFERER, "https://www.acfun.cn/search");
        String body = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
        String json = body == null ? "" : body;
        int sep = json.indexOf("/*<!-- fetch-stream -->*/");
        JsonNode data = objectMapper.readTree(sep < 0 ? json : json.substring(0, sep));
        if (data.path("html").isTextual()) {
            Document fragment = Jsoup.parseBodyFragment(data.path("html").asText());
            for (Element card : fragment.select(".search-up")) {
                String id = null;
                String live = null;
                try {
                    JsonNode meta = objectMapper.readTree(card.attr("data-up-exposure-log"));
                    id = meta.path("up_id").asText("");
                    live = meta.path("is_on_live").asText("");
                } catch (Exception ignored) {
                    // 畸形卡片跳过,不炸整个搜索
                }
                Element anchor = card.selectFirst(".up__main__name a");
                if (id.isEmpty() || anchor == null) {
                    continue;
                }
                MovieDetail detail = new MovieDetail();
                detail.setVod_id(getType() + "$" + id);
                detail.setVod_name(anchor.text().trim());
                Element img = card.selectFirst("img.up__avatar");
                if (img != null) {
                    String src = img.attr("src");
                    if (src.startsWith("//")) {
                        src = "https:" + src;
                    }
                    detail.setVod_pic(src);
                }
                detail.setVod_remarks(live.isEmpty() ? "未知" : ("1".equals(live) || "true".equals(live) ? "直播中" : "未开播"));
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
        JsonNode data = getJson(ORIGIN + "/api/live/info?authorId=" + id);
        if (data.path("result").asInt(-1) != 0) {
            throw new BadRequestException("房间不存在: " + id);
        }
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);
        detail.setVod_name(data.path("title").asText(data.path("user").path("name").asText()));
        JsonNode covers = data.path("coverUrls");
        if (covers.isArray() && !covers.isEmpty()) {
            String cover = covers.get(0).asText("");
            detail.setVod_pic(cover.startsWith("//") ? "https:" + cover : cover);
        }
        detail.setVod_actor(data.path("user").path("name").asText());
        // liveId/streamName 同空=官方下播形态(pure_live 同款判定)
        boolean live = !data.path("liveId").asText("").isEmpty();
        detail.setVod_remarks(live ? "直播中" : "未开播");
        if (live) {
            parsePlayUrls(detail, id);
        }
        MovieList result = new MovieList();
        result.getList().add(detail);
        result.setTotal(1);
        result.setLimit(1);
        log.debug("detail: {}", result);
        return result;
    }

    /** startPlay(快手系接口):游客登录凭证必须带 Referer+_did Cookie,缺一返回 result=52(实测)。 */
    private void parsePlayUrls(MovieDetail detail, String authorId) {
        try {
            VisitorSession session = visitorSession();
            String url = "https://api.kuaishouzt.com/rest/zt/live/web/startPlay?subBiz=mainApp&kpn=ACFUN_APP&kpf=PC_WEB"
                    + "&userId=" + session.userId() + "&did=" + session.did() + "&acfun.api.visitor_st=" + session.token();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set(HttpHeaders.REFERER, ORIGIN + "/");
            headers.set(HttpHeaders.COOKIE, "_did=" + session.did() + ";");
            String body = "authorId=" + authorId + "&pullStreamType=FLV";
            JsonNode root = objectMapper.readTree(restTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class).getBody());
            if (root.path("result").asInt(0) != 1) {
                log.debug("acfun startPlay failed: result={}", root.path("result").asInt());
                return;
            }
            JsonNode playRes = objectMapper.readTree(root.path("data").path("videoPlayRes").asText());
            // 画质按 level 降序(服务器档位与 bitrate 不同量纲,pure_live 同款:已知 level 优先,bitrate 只排未知组)
            Map<String, Integer> ranks = new LinkedHashMap<>();
            Map<String, String> labels = new LinkedHashMap<>();
            Map<String, List<String>> urls = new LinkedHashMap<>();
            for (JsonNode manifest : playRes.path("liveAdaptiveManifest")) {
                for (JsonNode item : manifest.path("adaptationSet").path("representation")) {
                    if (item.path("hidden").asBoolean(false)) {
                        continue;
                    }
                    String streamUrl = item.path("url").asText("");
                    if (streamUrl.isEmpty()) {
                        continue;
                    }
                    String type = item.path("qualityType").asText("");
                    String key = type.isEmpty() ? "id:" + item.path("id").asText() : type;
                    int level = item.path("level").asInt(-1);
                    int rank = level >= 0 ? 1000000 + level : item.path("bitrate").asInt(0);
                    ranks.merge(key, rank, Math::max);
                    labels.putIfAbsent(key, qualityLabel(type, item.path("name").asText(""), key));
                    urls.computeIfAbsent(key, k -> new ArrayList<>()).add(streamUrl);
                }
            }
            List<String> playUrl = new ArrayList<>();
            urls.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(ranks.get(b.getKey()), ranks.get(a.getKey())))
                    .forEach(entry -> playUrl.add(labels.get(entry.getKey()) + "$" + String.join("#", entry.getValue())));
            if (!playUrl.isEmpty()) {
                detail.setVod_play_from("线路1");
                detail.setVod_play_url(String.join("#", playUrl));
            }
        } catch (Exception e) {
            log.warn("AcFun播放地址获取失败: {}", authorId, e);
        }
    }

    private String qualityLabel(String type, String name, String fallback) {
        if (!name.isEmpty()) {
            return name;
        }
        return switch (type) {
            case "STANDARD" -> "高清";
            case "HIGH" -> "超清";
            case "SUPER" -> "蓝光";
            case "BLUE_RAY" -> "高码率";
            default -> "画质 " + fallback;
        };
    }

    private synchronized VisitorSession visitorSession() throws IOException {
        VisitorSession cached = visitor;
        if (cached != null && System.currentTimeMillis() < visitorExpiresAt) {
            return cached;
        }
        String did = "web_" + randomString(16);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set(HttpHeaders.REFERER, ORIGIN + "/");
        headers.set(HttpHeaders.COOKIE, "_did=" + did + ";");
        JsonNode data = objectMapper.readTree(restTemplate.exchange("https://id.app.acfun.cn/rest/app/visitor/login",
                HttpMethod.POST, new HttpEntity<>("sid=acfun.api.visitor", headers), String.class).getBody());
        if (data.path("result").asInt(-1) != 0) {
            throw new BadRequestException("AcFun游客登录失败: result=" + data.path("result").asInt());
        }
        VisitorSession session = new VisitorSession(did, data.path("userId").asText(), data.path("acfun.api.visitor_st").asText());
        visitor = session;
        visitorExpiresAt = System.currentTimeMillis() + VISITOR_TTL_MS;
        return session;
    }

    /** 目录:pcursor 即页码(实测数字翻页可行,响应 pcursor=下一页码);filters=分类 JSON。 */
    private JsonNode directory(int page, String filters) throws IOException {
        StringBuilder url = new StringBuilder(ORIGIN + "/api/channel/list?count=30&pcursor=").append(page == 1 ? "" : page);
        if (filters != null) {
            url.append("&filters=").append(URLEncoder.encode(filters, StandardCharsets.UTF_8));
        }
        return getJson(url.toString()).path("channelListData");
    }

    private MovieList toMovieList(JsonNode data) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        for (JsonNode item : data.path("liveList")) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + item.path("authorId").asText());
            detail.setVod_name(item.path("title").asText(item.path("user").path("name").asText()));
            JsonNode covers = item.path("coverUrls");
            if (covers.isArray() && !covers.isEmpty()) {
                String cover = covers.get(0).asText("");
                detail.setVod_pic(cover.startsWith("//") ? "https:" + cover : cover);
            }
            detail.setVod_remarks(item.path("user").path("name").asText(""));
            list.add(detail);
        }
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
    }

    /** 分类来自目录首响应的 channelFilters(liveChannelDisplayFilters[].displayFilters[]),自带分类封面。 */
    private Map<String, String[]> loadCategories() throws IOException {
        var cached = categoryMeta;
        if (cached != null) {
            return cached;
        }
        Map<String, String[]> names = new LinkedHashMap<>();
        JsonNode root = getJson(ORIGIN + "/api/channel/list?count=1&pcursor=&filters=");
        for (JsonNode group : root.path("channelFilters").path("liveChannelDisplayFilters")) {
            for (JsonNode filter : group.path("displayFilters")) {
                String key = filter.path("filterType").asInt() + "_" + filter.path("filterId").asInt();
                String name = filter.path("name").asText("");
                if (!name.isEmpty()) {
                    String cover = filter.path("cover").asText("");
                    names.putIfAbsent(key, new String[]{name, cover.startsWith("//") ? "https:" + cover : cover});
                }
            }
        }
        categoryMeta = names;
        return names;
    }

    /** 分类 id(filterType_filterId)还原成 filters 参数 JSON。 */
    private String encodeFilter(String raw) throws IOException {
        String[] parts = raw.split("_");
        if (parts.length != 2) {
            return null;
        }
        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("filterType", Integer.parseInt(parts[0]));
        filter.put("filterId", Integer.parseInt(parts[1]));
        ArrayNode array = objectMapper.createArrayNode();
        array.add(filter);
        return objectMapper.writeValueAsString(array);
    }

    private JsonNode getJson(String url) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.REFERER, ORIGIN + "/");
        return objectMapper.readTree(restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody());
    }

    private static String randomString(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
