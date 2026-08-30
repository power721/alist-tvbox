package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SOOP 直播(原 AfreecaTV)。
 * 海外必须用 .com 全球域名:.co.kr 的流分配域名对海外 DNS 不解析(GTM 空应答)。
 * roomId 为主播用户名(bj_id),每次开播的场次号 broad_no 由 watch 接口换取;
 * 播放地址 = 流分配接口 view_url + player_live_api 的 aid 拼接,裸请求可播无需代理。
 * aid 凭证有时效,本服务不缓存 detail 结果。
 */
@Slf4j
@Service
public class SoopService implements LivePlatform {
    private static final String SCH_URL = "https://sch.sooplive.com/api.php";
    private static final String LIVE_URL = "https://live.sooplive.com";
    private static final String WATCH_URL = "https://api.m.sooplive.com/broad/a/watch";
    private static final String ASSIGN_URL = "https://livestream-manager.sooplive.com/broad_stream_assign.html";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LiveProxyService liveProxyService;
    private final SettingRepository settingRepository;

    /** web 管理端配置的用户 cookie 存储键:部分房间必须登录态才能取流。 */
    public static final String COOKIE_SETTING = "soop_cookie";

    public SoopService(RestTemplateBuilder builder, ObjectMapper objectMapper, LiveProxyService liveProxyService,
                       SettingRepository settingRepository) {
        this.restTemplate = builder
                .defaultHeader("User-Agent", Constants.USER_AGENT)
                .defaultHeader("Origin", "https://play.sooplive.com")
                .defaultHeader("Referer", "https://play.sooplive.com/")
                .build();
        this.objectMapper = objectMapper;
        this.liveProxyService = liveProxyService;
        this.settingRepository = settingRepository;
    }

    /** 用户在 web 管理端配置的 cookie,未配置返回 null。 */
    public String userCookie() {
        return settingRepository.findById(COOKIE_SETTING).map(Setting::getValue).filter(v -> !v.isBlank()).orElse(null);
    }

    @Override
    public String getType() {
        return "soop";
    }

    @Override
    public String getName() {
        return "SOOP";
    }

    @Override
    public MovieList home() throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        JsonNode broad = getJson(LIVE_URL + "/api/main_broad_list_api.php", Map.of(
                        "selectType", "action", "selectValue", "all", "orderType", "view_cnt",
                        "pageNo", "1", "lang", "ko_KR"))
                .path("broad");
        for (JsonNode item : broad) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + item.path("user_id").asText());
            detail.setVod_name(item.path("broad_title").asText());
            detail.setVod_pic(fixUrl(item.path("broad_thumb").asText()));
            detail.setVod_actor(item.path("user_nick").asText());
            detail.setVod_remarks(playCount(item.path("current_view_cnt")));
            list.add(detail);
        }

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

        JsonNode items = getJson(SCH_URL, Map.of(
                        "m", "categoryList", "szKeyword", "", "szOrder", "view_cnt",
                        "nPageNo", "1", "nListCnt", "100", "nOffset", "0", "szPlatform", "pc"))
                .path("data").path("list");
        for (JsonNode item : items) {
            Category category = new Category();
            category.setType_id(getType() + "-" + item.path("category_no").asText());
            category.setType_name(item.path("category_name").asText());
            category.setType_flag(0);
            category.setCover(fixUrl(item.path("cate_img").asText()));
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
        String cate = id.split("-", 2)[1];
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        JsonNode items = getJson(SCH_URL, new LinkedHashMap<>(Map.of(
                        "m", "categoryContentsList", "szType", "live", "nPageNo", String.valueOf(pg == null ? 1 : pg),
                        "nListCnt", "60", "szPlatform", "pc", "szOrder", "view_cnt_desc", "szCateNo", cate)))
                .path("data").path("list");
        for (JsonNode item : items) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + item.path("user_id").asText());
            detail.setVod_name(item.path("broad_title").asText());
            detail.setVod_pic(fixUrl(item.path("thumbnail").asText()));
            detail.setVod_actor(item.path("user_nick").asText());
            detail.setVod_remarks(playCount(item.path("view_cnt")));
            list.add(detail);
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

        Map<String, String> params = new LinkedHashMap<>();
        params.put("l", "DF");
        params.put("m", "liveSearch");
        params.put("c", "UTF-8");
        params.put("w", "webk");
        params.put("isMobile", "0");
        params.put("onlyParent", "1");
        params.put("szType", "json");
        params.put("szOrder", "score");
        params.put("szKeyword", wd);
        params.put("nPageNo", "1");
        params.put("nListCnt", "40");
        params.put("tab", "live");
        params.put("location", "total_search");
        params.put("isHashSearch", "0");
        params.put("v", "2.0");
        for (JsonNode item : getJson(SCH_URL, params).path("REAL_BROAD")) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + item.path("user_id").asText());
            detail.setVod_name(item.path("broad_title").asText());
            detail.setVod_pic(fixUrl(item.path("broad_img").asText()));
            detail.setVod_actor(item.path("user_nick").asText());
            detail.setVod_remarks(playCount(item.path("current_view_cnt")));
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
        String bjId = tid.split("\\$")[1];
        MovieList result = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);

        JsonNode data;
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("bj_id", bjId);
            form.add("bid", bjId);
            form.add("broad_no", "");
            form.add("agent", "web");
            form.add("confirm_adult", "true");
            form.add("player_type", "webm");
            form.add("mode", "live");
            data = postForm(WATCH_URL, form).path("data");
        } catch (Exception e) {
            log.warn("get soop room detail failed: {}", tid, e);
            data = null;
        }

        // viewpreset 只在开播时返回
        JsonNode viewpreset = data == null ? null : data.path("viewpreset");
        boolean live = viewpreset != null && viewpreset.isArray() && !viewpreset.isEmpty();
        if (data != null) {
            detail.setVod_name(data.path("broad_title").asText(bjId));
            detail.setVod_pic(fixUrl(data.path("thumbnail").asText()));
            detail.setVod_actor(data.path("user_nick").asText());
            JsonNode tags = data.path("category_tags");
            detail.setType_name(tags.isArray() && !tags.isEmpty() ? tags.get(0).asText() : "");
            detail.setVod_remarks(live ? playCount(data.path("view_cnt")) : "未开播");
        } else {
            detail.setVod_name(bjId);
            detail.setVod_remarks("未开播");
        }

        if (live) {
            parsePlayUrls(detail, bjId, data, client);
        }

        result.getList().add(detail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    /** 逐清晰度取流分配 view_url + aid 拼成媒体清单地址,按码率降序保证默认线路最高清 */
    private void parsePlayUrls(MovieDetail detail, String bjId, JsonNode data, String client) {
        String bno = data.path("broad_no").asText();
        List<JsonNode> presets = new ArrayList<>();
        data.path("viewpreset").forEach(presets::add);
        presets.sort(Comparator.comparingInt(p -> -p.path("bps").asInt(0)));

        List<String> urls = new ArrayList<>();
        for (JsonNode preset : presets) {
            String name = preset.path("name").asText();
            if (name.isEmpty() || "auto".equals(name)) {
                continue;
            }
            try {
                String viewUrl = getJson(ASSIGN_URL, Map.of(
                                "return_type", "gcp_cdn", "use_cors", "false", "cors_origin_url", "play.sooplive.com",
                                "broad_key", bno + "-common-" + name + "-hls", "time", "8361.086329376785"))
                        .path("view_url").asText();
                String aid;
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("bid", bjId);
                form.add("bno", bno);
                form.add("type", "aid");
                form.add("pwd", "");
                form.add("player_type", "html5");
                form.add("stream_type", "common");
                form.add("quality", name);
                form.add("mode", "landing");
                form.add("from_api", "0");
                form.add("is_revive", "false");
                aid = postForm(LIVE_URL + "/afreeca/player_live_api.php?bjid=" + bjId, form)
                        .path("CHANNEL").path("AID").asText();
                if (!viewUrl.isEmpty() && !aid.isEmpty()) {
                    String label = preset.path("label").asText(name);
                    // SOOP CDN 不下发 CORS 头,浏览器无法直连,仅网页端经代理(清单改写后分片同走代理);安卓客户端直连
                    String url = viewUrl + "?aid=" + aid;
                    urls.add(label + "$" + ("web".equals(client) ? liveProxyService.buildProxyUrl(url) : url));
                }
            } catch (Exception e) {
                log.debug("get soop {} stream failed: {} {}", name, bjId, e.toString());
            }
        }

        if (!urls.isEmpty()) {
            detail.setVod_play_from("线路1");
            detail.setVod_play_url(String.join("#", urls));
        }
    }

    // SOOP 部分接口(watch/player_live_api)的 body 是 JSON 却标 Content-Type: text/html,
    // 不能让 RestTemplate 按 JsonNode 反序列化,统一按 String 拉取后手动解析;用户 cookie 存在时随行带上
    private JsonNode getJson(String url, Map<String, String> params) throws IOException {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        params.forEach(builder::queryParam);
        return objectMapper.readTree(restTemplate.exchange(builder.encode().build().toUri(), HttpMethod.GET,
                new HttpEntity<>(cookieHeaders(null)), String.class).getBody());
    }

    private JsonNode postForm(String url, MultiValueMap<String, String> form) throws IOException {
        HttpHeaders headers = cookieHeaders(MediaType.APPLICATION_FORM_URLENCODED);
        return objectMapper.readTree(restTemplate.postForObject(url, new HttpEntity<>(form, headers), String.class));
    }

    /** 带 web 管理端配置的用户 cookie(如有);content type 为 null 时不设置。 */
    private HttpHeaders cookieHeaders(MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        String cookie = userCookie();
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        return headers;
    }

    /** view_cnt 可能是数字或数字串,解析失败原样返回 */
    private String playCount(JsonNode count) {
        String text = count.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return playCount(Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return text;
        }
    }

    private String fixUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        return url;
    }
}
