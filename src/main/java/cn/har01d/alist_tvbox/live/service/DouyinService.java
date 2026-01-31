package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DouyinService implements LivePlatform {
    private final Map<String, String> categoryMap = new HashMap<>();
    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private String cookie = "ttwid=1%7CB1qls3GdnZhUov9o2NxOMxxYS2ff6OSvEWbv0ytbES4%7C1680522049%7C280d802d6d478e3e78d0c807f7c487e7ffec0ae4e5fdd6a0fe74c3c6af149511";

    private static final String BASE_URL = "https://live.douyin.com";
    private static final String PARTITION_ROOM_API = "https://live.douyin.com/webcast/web/partition/detail/room/v2/";
    private static final String ROOM_ENTER_API = "https://live.douyin.com/webcast/room/web/enter/";
    private static final String SEARCH_API = "https://www.douyin.com/aweme/v1/web/live/search/";
    private static final String ROOM_REFLOW_API = "https://webcast.amemv.com/webcast/room/reflow/info/";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0";

    // 抖音签名API地址，可通过环境变量配置
    private final String signApiUrl;

    public DouyinService(AppProperties appProperties, RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.signApiUrl = System.getenv().getOrDefault("DOUYIN_SIGN_API", "http://dy.har01d.cn/abogus");
        this.restTemplate = builder
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
        this.objectMapper = objectMapper;
        log.info("抖音签名API地址: {}", signApiUrl);
    }

    @Override
    public String getType() {
        return "douyin";
    }

    @Override
    public String getName() {
        return "抖音";
    }

    @Override
    public MovieList home() throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        try {
            String url = buildPartitionUrl("720", "1", 1, 15);
            url = signUrl(url); // 签名
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders()),
                    String.class
            );

            String body = response.getBody();
            if (body == null || body.isEmpty()) {
                log.error("抖音首页API返回空响应");
                return result;
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode dataList = root.path("data").path("data");

            for (JsonNode item : dataList) {
                JsonNode room = item.path("room");
                JsonNode owner = room.path("owner");

                MovieDetail detail = new MovieDetail();
                detail.setVod_id(getType() + "$" + item.path("web_rid").asText());
                detail.setVod_name(room.path("title").asText());
                detail.setVod_pic(getFirstUrl(room.path("cover").path("url_list")));
                detail.setVod_remarks(owner.path("nickname").asText() + " - " +
                        room.path("room_view_stats").path("display_value").asText());
                list.add(detail);

                if (list.size() >= 30) {
                    break;
                }
            }
        } catch (Exception e) {
            log.error("抖音首页获取失败", e);
        }

        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("抖音home result: {}", result);
        return result;
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        try {
            ensureCookie();

            String html = restTemplate.getForObject(BASE_URL + "/", String.class);
            Pattern pattern = Pattern.compile("\\{\\\\\"pathname\\\\\":\\\\\"\\/\\\\\",\\\\\"categoryData.*?\\]\\\\n");
            Matcher matcher = pattern.matcher(html);

            if (matcher.find()) {
                String jsonText = matcher.group(0)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("]\\n", "");

                JsonNode categoryDataNode = objectMapper.readTree(jsonText);
                JsonNode categories = categoryDataNode.path("categoryData");
                String cover = getCover();

                for (JsonNode item : categories) {
                    JsonNode partition = item.path("partition");
                    String catId = partition.path("id_str").asText() + "," + partition.path("type").asInt();
                    String catName = partition.path("title").asText();

                    Category category = new Category();
                    category.setType_id(getType() + "-" + catId);
                    category.setType_name(catName);
                    category.setType_flag(0);
                    category.setCover(cover);
                    categoryMap.put(category.getType_id(), catId);
                    list.add(category);

                    // 添加子分类
                    JsonNode subPartitions = item.path("sub_partition");
                    for (JsonNode subItem : subPartitions) {
                        JsonNode subPartition = subItem.path("partition");
                        String subId = subPartition.path("id_str").asText() + "," + subPartition.path("type").asInt();
                        String subName = subPartition.path("title").asText();

                        Category subCategory = new Category();
                        subCategory.setType_id(getType() + "-" + subId);
                        subCategory.setType_name(catName + " - " + subName);
                        subCategory.setType_flag(0);
                        subCategory.setCover(cover);
                        categoryMap.put(subCategory.getType_id(), subId);
                        list.add(subCategory);
                    }
                }
            }
        } catch (Exception e) {
            log.error("抖音分类获取失败", e);
        }

        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("抖音category result: {}", result);
        return result;
    }

    private String getCover() {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath("/douyin.png")
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    @Override
    public MovieList list(String id, String sort, Integer pg) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        if (categoryMap.isEmpty()) {
            category();
        }

        String partitionId = categoryMap.get(id);
        if (partitionId == null) {
            return result;
        }

        String[] parts = partitionId.split(",");
        if (parts.length != 2) {
            return result;
        }

        try {
            String url = buildPartitionUrl(parts[0], parts[1], pg, 15);
            url = signUrl(url); // 签名
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders()),
                    String.class
            );

            String body = response.getBody();
            if (body == null || body.isEmpty()) {
                log.error("抖音房间列表API返回空响应: {}", url);
                return result;
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode dataList = root.path("data").path("data");

            for (JsonNode item : dataList) {
                JsonNode room = item.path("room");
                JsonNode owner = room.path("owner");

                MovieDetail detail = new MovieDetail();
                detail.setVod_id(getType() + "$" + item.path("web_rid").asText());
                detail.setVod_name(room.path("title").asText());
                detail.setVod_pic(getFirstUrl(room.path("cover").path("url_list")));
                detail.setVod_remarks(owner.path("nickname").asText());
                list.add(detail);
            }
        } catch (Exception e) {
            log.error("抖音房间列表获取失败: {}", id, e);
        }

        result.setList(list);
        result.setPage(pg);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        result.setPagecount(list.size() >= 15 ? pg + 1 : pg);

        log.debug("抖音list result: {}", result);
        return result;
    }

    @Override
    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        try {
            ensureCookie();

            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(SEARCH_API)
                    .queryParam("device_platform", "webapp")
                    .queryParam("aid", "6383")
                    .queryParam("channel", "channel_pc_web")
                    .queryParam("search_channel", "aweme_live")
                    .queryParam("keyword", wd)
                    .queryParam("search_source", "switch_tab")
                    .queryParam("query_correct_type", "1")
                    .queryParam("is_filter_search", "0")
                    .queryParam("from_group_id", "")
                    .queryParam("offset", "0")
                    .queryParam("count", "10")
                    .queryParam("pc_client_type", "1")
                    .queryParam("version_code", "170400")
                    .queryParam("version_name", "17.4.0")
                    .queryParam("cookie_enabled", "true")
                    .queryParam("screen_width", "1980")
                    .queryParam("screen_height", "1080")
                    .queryParam("browser_language", "zh-CN")
                    .queryParam("browser_platform", "Win32")
                    .queryParam("browser_name", "Edge")
                    .queryParam("browser_version", "125.0.0.0")
                    .queryParam("browser_online", "true")
                    .queryParam("engine_name", "Blink")
                    .queryParam("engine_version", "125.0.0.0")
                    .queryParam("os_name", "Windows")
                    .queryParam("os_version", "10")
                    .queryParam("cpu_core_num", "12")
                    .queryParam("device_memory", "8")
                    .queryParam("platform", "PC")
                    .queryParam("downlink", "10")
                    .queryParam("effective_type", "4g")
                    .queryParam("round_trip_time", "100")
                    .queryParam("webid", "7382872326016435738");

            HttpHeaders headers = createHeaders();
            headers.set("Authority", "www.douyin.com");
            headers.set("accept", "application/json, text/plain, */*");
            headers.set("accept-language", "zh-CN,zh;q=0.9,en;q=0.8");
            headers.set("priority", "u=1, i");
            headers.set("referer", "https://www.douyin.com/search/" + wd + "?type=live");
            headers.set("sec-ch-ua", "\"Microsoft Edge\";v=\"125\", \"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"");
            headers.set("sec-ch-ua-mobile", "?0");
            headers.set("sec-ch-ua-platform", "\"Windows\"");
            headers.set("sec-fetch-dest", "empty");
            headers.set("sec-fetch-mode", "cors");
            headers.set("sec-fetch-site", "same-origin");

            String url = signUrl(builder.toUriString()); // 签名
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            String body = response.getBody();
            if (body == null || body.isEmpty()) {
                log.error("抖音搜索API返回空响应: {}", wd);
                return result;
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode dataList = root.path("data");

            for (JsonNode item : dataList) {
                String rawdata = item.path("lives").path("rawdata").asText();
                JsonNode roomData = objectMapper.readTree(rawdata);

                int status = roomData.path("status").asInt();
                boolean isLive = status == 2;

                JsonNode owner = roomData.path("owner");

                MovieDetail detail = new MovieDetail();
                detail.setVod_id(getType() + "$" + owner.path("web_rid").asText());
                detail.setVod_name(roomData.path("title").asText());
                detail.setVod_pic(getFirstUrl(roomData.path("cover").path("url_list")));
                detail.setVod_remarks(owner.path("nickname").asText() + (isLive ? " 直播中" : " 未开播"));
                list.add(detail);
            }
        } catch (Exception e) {
            log.error("抖音搜索失败: {}", wd, e);
        }

        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("抖音search result: {}", result);
        return result;
    }

    @Override
    public MovieList detail(String tid, String client) throws IOException {
        String[] parts = tid.split("\\$");
        String roomId = parts[1];

        MovieList result = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);

        try {
            ensureCookie();

            // 先尝试通过API获取
            JsonNode roomData = getRoomDataByApi(roomId);
            if (roomData != null) {
                parseRoomDetail(detail, roomData, roomId, true);
            } else {
                // API失败则通过HTML解析
                JsonNode pageData = getRoomDataByHtml(roomId);
                if (pageData != null) {
                    parseRoomDetailFromHtml(detail, pageData, roomId);
                }
            }
        } catch (Exception e) {
            log.error("抖音房间详情获取失败: {}", roomId, e);
        }

        result.getList().add(detail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("抖音detail: {}", result);
        return result;
    }

    private JsonNode getRoomDataByApi(String webRid) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(ROOM_ENTER_API)
                    .queryParam("aid", "6383")
                    .queryParam("app_name", "douyin_web")
                    .queryParam("live_id", "1")
                    .queryParam("device_platform", "web")
                    .queryParam("enter_from", "web_live")
                    .queryParam("web_rid", webRid)
                    .queryParam("room_id_str", "")
                    .queryParam("enter_source", "")
                    .queryParam("Room-Enter-User-Login-Ab", "0")
                    .queryParam("is_need_double_stream", "false")
                    .queryParam("cookie_enabled", "true")
                    .queryParam("screen_width", "1980")
                    .queryParam("screen_height", "1080")
                    .queryParam("browser_language", "zh-CN")
                    .queryParam("browser_platform", "Win32")
                    .queryParam("browser_name", "Edge")
                    .queryParam("browser_version", "125.0.0.0");

            String url = signUrl(builder.toUriString()); // 签名
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders()),
                    String.class
            );

            String body = response.getBody();
            if (body == null || body.isEmpty()) {
                log.debug("抖音房间API返回空响应: {}", webRid);
                return null;
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode dataList = root.path("data");
            if (dataList.isArray() && !dataList.isEmpty()) {
                var result = objectMapper.createObjectNode();
                result.set("room", dataList.get(0));
                result.set("user", root.path("data").path("user"));
                return result;
            }
        } catch (Exception e) {
            log.debug("通过API获取抖音房间数据失败: {}", webRid, e);
        }
        return null;
    }

    private JsonNode getRoomDataByHtml(String webRid) {
        try {
            String url = BASE_URL + "/" + webRid;
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders()),
                    String.class
            );

            String html = response.getBody();
            if (html == null || html.isEmpty()) {
                log.debug("抖音房间页面返回空响应: {}", webRid);
                return null;
            }
            Pattern pattern = Pattern.compile("\\{\\\\\"state\\\\\":\\{\\\\\"appStore.*?\\]\\\\n");
            Matcher matcher = pattern.matcher(html);

            if (matcher.find()) {
                String jsonText = matcher.group(0)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("]\\n", "");

                JsonNode stateNode = objectMapper.readTree(jsonText);
                return stateNode.path("state");
            }
        } catch (Exception e) {
            log.debug("通过HTML获取抖音房间数据失败: {}", webRid, e);
        }
        return null;
    }

    private void parseRoomDetail(MovieDetail detail, JsonNode data, String webRid, boolean fromApi) {
        JsonNode room = data.path("room");
        JsonNode owner = room.path("owner");
        JsonNode user = data.path("user");
        int status = room.path("status").asInt();
        boolean isLive = status == 2;

        detail.setVod_name(room.path("title").asText());
        detail.setVod_pic(getFirstUrl(room.path("cover").path("url_list")));
        detail.setVod_actor(isLive ? owner.path("nickname").asText() : user.path("nickname").asText());
        detail.setVod_remarks(room.path("room_view_stats").path("display_value").asText());
        detail.setVod_content(owner.path("signature").asText());

        if (isLive) {
            JsonNode streamUrl = room.path("stream_url");
            parsePlayUrls(detail, streamUrl);
        }
    }

    private void parseRoomDetailFromHtml(MovieDetail detail, JsonNode state, String webRid) {
        JsonNode room = state.path("roomStore").path("roomInfo").path("room");
        JsonNode owner = room.path("owner");
        JsonNode anchor = state.path("roomStore").path("roomInfo").path("anchor");
        int status = room.path("status").asInt();
        boolean isLive = status == 2;

        detail.setVod_name(room.path("title").asText());
        detail.setVod_pic(getFirstUrl(room.path("cover").path("url_list")));
        detail.setVod_actor(isLive ? owner.path("nickname").asText() : anchor.path("nickname").asText());
        detail.setVod_remarks(room.path("room_view_stats").path("display_value").asText());
        detail.setVod_content(room.path("title").asText());

        if (isLive) {
            JsonNode streamUrl = room.path("stream_url");
            parsePlayUrls(detail, streamUrl);
        }
    }

    private void parsePlayUrls(MovieDetail movieDetail, JsonNode streamUrl) {
        try {
            List<String> playFrom = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();

            JsonNode liveCoreSdkData = streamUrl.path("live_core_sdk_data");
            if (liveCoreSdkData.isMissingNode() || liveCoreSdkData.isNull()) {
                // 使用旧的URL格式
                JsonNode flvPullUrl = streamUrl.path("flv_pull_url");
                JsonNode hlsPullUrlMap = streamUrl.path("hls_pull_url_map");

                if (flvPullUrl.isObject()) {
                    Iterator<String> flvKeys = flvPullUrl.fieldNames();
                    while (flvKeys.hasNext()) {
                        String key = flvKeys.next();
                        String url = flvPullUrl.path(key).asText();
                        playUrlList.add("FLV-" + key + "$" + url);
                    }
                }

                if (hlsPullUrlMap.isObject()) {
                    Iterator<String> hlsKeys = hlsPullUrlMap.fieldNames();
                    while (hlsKeys.hasNext()) {
                        String key = hlsKeys.next();
                        String url = hlsPullUrlMap.path(key).asText();
                        playUrlList.add("HLS-" + key + "$" + url);
                    }
                }
            } else {
                // 使用新的SDK格式
                JsonNode qualities = liveCoreSdkData
                        .path("pull_data")
                        .path("options")
                        .path("qualities");

                JsonNode streamData = liveCoreSdkData
                        .path("pull_data")
                        .path("stream_data");

                if (streamData.isTextual()) {
                    // stream_data是字符串，需要解析
                    String streamDataStr = streamData.asText();
                    if (!streamDataStr.startsWith("{")) {
                        // 使用旧格式
                        parseOldQualityFormat(playUrlList, streamUrl, qualities);
                    } else {
                        // 使用新格式
                        JsonNode streamDataJson = objectMapper.readTree(streamDataStr);
                        parseNewQualityFormat(playUrlList, streamDataJson, qualities);
                    }
                } else if (streamData.isObject()) {
                    parseNewQualityFormat(playUrlList, streamData, qualities);
                }
            }

            if (!playUrlList.isEmpty()) {
                playFrom.add("FLV");
                if (playUrlList.size() > 1) {
                    playFrom.add("HLS");
                }
                movieDetail.setVod_play_from(String.join("$$$", playFrom));
                movieDetail.setVod_play_url(String.join("$$$", playUrlList));
            }
        } catch (Exception e) {
            log.error("抖音播放URL解析失败", e);
        }
    }

    private void parseOldQualityFormat(List<String> playUrlList, JsonNode streamUrl, JsonNode qualities) {
        JsonNode flvPullUrl = streamUrl.path("flv_pull_url");
        JsonNode hlsPullUrlMap = streamUrl.path("hls_pull_url_map");

        List<String> flvList = new ArrayList<>();
        List<String> hlsList = new ArrayList<>();

        if (flvPullUrl.isObject()) {
            Iterator<String> flvKeys = flvPullUrl.fieldNames();
            while (flvKeys.hasNext()) {
                String key = flvKeys.next();
                flvList.add(flvPullUrl.path(key).asText());
            }
        }

        if (hlsPullUrlMap.isObject()) {
            Iterator<String> hlsKeys = hlsPullUrlMap.fieldNames();
            while (hlsKeys.hasNext()) {
                String key = hlsKeys.next();
                hlsList.add(hlsPullUrlMap.path(key).asText());
            }
        }

        List<String> flv = new ArrayList<>();
        List<String> hls = new ArrayList<>();

        for (JsonNode quality : qualities) {
            int level = quality.path("level").asInt();
            String name = quality.path("name").asText();

            int index = flvList.size() - level;
            if (index >= 0 && index < flvList.size()) {
                flv.add(name + "$" + flvList.get(index));
            }

            index = hlsList.size() - level;
            if (index >= 0 && index < hlsList.size()) {
                hls.add(name + "$" + hlsList.get(index));
            }
        }

        flv = flv.reversed();
        hls = hls.reversed();
        if (!flv.isEmpty()) {
            playUrlList.add(String.join("#", flv));
        }
        if (!hls.isEmpty()) {
            playUrlList.add(String.join("#", hls));
        }
    }

    private void parseNewQualityFormat(List<String> playUrlList, JsonNode streamData, JsonNode qualities) {
        JsonNode data = streamData.path("data");

        for (JsonNode quality : qualities) {
            String sdkKey = quality.path("sdk_key").asText();
            String name = quality.path("name").asText();

            JsonNode qualityData = data.path(sdkKey);
            String flvUrl = qualityData.path("main").path("flv").asText();
            String hlsUrl = qualityData.path("main").path("hls").asText();

            List<String> urls = new ArrayList<>();
            if (!flvUrl.isEmpty()) {
                urls.add(flvUrl);
            }
            if (!hlsUrl.isEmpty()) {
                urls.add(hlsUrl);
            }

            if (!urls.isEmpty()) {
                playUrlList.add(name + "$" + String.join("#", urls));
            }
        }
    }

    private String buildPartitionUrl(String partitionId, String partitionType, int page, int count) {
        return UriComponentsBuilder.fromHttpUrl(PARTITION_ROOM_API)
                .queryParam("aid", "6383")
                .queryParam("app_name", "douyin_web")
                .queryParam("live_id", "1")
                .queryParam("device_platform", "web")
                .queryParam("language", "zh-CN")
                .queryParam("enter_from", "link_share")
                .queryParam("cookie_enabled", "true")
                .queryParam("screen_width", "1980")
                .queryParam("screen_height", "1080")
                .queryParam("browser_language", "zh-CN")
                .queryParam("browser_platform", "Win32")
                .queryParam("browser_name", "Edge")
                .queryParam("browser_version", "125.0.0.0")
                .queryParam("browser_online", "true")
                .queryParam("count", count)
                .queryParam("offset", (page - 1) * count)
                .queryParam("partition", partitionId)
                .queryParam("partition_type", partitionType)
                .queryParam("req_from", "2")
                .toUriString();
    }

    private synchronized void ensureCookie() {
        if (cookie != null && !cookie.isEmpty()) {
            return;
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    BASE_URL,
                    HttpMethod.GET,
                    new HttpEntity<>(createBasicHeaders()),
                    String.class
            );

            HttpHeaders headers = response.getHeaders();
            List<String> setCookies = headers.get("set-cookie");
            if (setCookies != null) {
                for (String c : setCookies) {
                    String cookieValue = c.split(";")[0];
                    if (cookieValue.contains("ttwid")) {
                        cookie += cookieValue + "; ";
                    }
                }
            }
        } catch (Exception e) {
            log.error("获取抖音Cookie失败", e);
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = createBasicHeaders();
        if (cookie != null && !cookie.isEmpty()) {
            headers.set("Cookie", cookie);
        }
        return headers;
    }

    private HttpHeaders createBasicHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);
        headers.set("Authority", "live.douyin.com");
        headers.set("Referer", "https://live.douyin.com");
        headers.set("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3");
        headers.set("connection", "keep-alive");
        return headers;
    }

    private String getFirstUrl(JsonNode urlListNode) {
        if (urlListNode.isArray() && !urlListNode.isEmpty()) {
            return urlListNode.get(0).asText();
        }
        return "";
    }

    private String signUrl(String url) {
        try {
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("url", url);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                    signApiUrl,
                    request,
                    JsonNode.class
            );

            JsonNode body = response.getBody();
            if (body != null && body.has("signed_url")) {
                String signedUrl = body.get("signed_url").asText();
                log.debug("签名成功: {} -> {}", url, signedUrl);
                return signedUrl;
            } else {
                log.warn("签名API返回无效响应: {}", body);
                return url;
            }
        } catch (Exception e) {
            log.error("调用签名API失败，使用原始URL: {}", url, e);
            return url;
        }
    }
}
