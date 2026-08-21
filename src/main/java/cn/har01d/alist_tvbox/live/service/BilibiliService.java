package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.live.danmaku.BilibiliDanmakuClient;
import cn.har01d.alist_tvbox.live.model.BilibiliCategoriesResponse;
import cn.har01d.alist_tvbox.live.model.BilibiliCategory;
import cn.har01d.alist_tvbox.live.model.BilibiliRoomInfo;
import cn.har01d.alist_tvbox.live.model.BilibiliRoomPlayInfo;
import cn.har01d.alist_tvbox.live.model.BilibiliRoomPlayResponse;
import cn.har01d.alist_tvbox.live.model.BilibiliRoomResponse;
import cn.har01d.alist_tvbox.live.model.BilibiliRoomsResponse;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE;
import static cn.har01d.alist_tvbox.util.Constants.FOLDER;

@Slf4j
@Service
public class BilibiliService implements LivePlatform {
    /** 推荐流正常至少返回 10+ 条,低于该阈值视为源质量不足,继续尝试兜底链。 */
    private static final int MIN_RECOMMEND_ROOMS = 10;
    private final Map<String, String> userMap = new HashMap<>();
    private final Map<String, List<BilibiliCategory>> categoryMap = new HashMap<>();
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final SettingRepository settingRepository;
    private String imgKey;
    private String subKey;
    private LocalDate keyTime;

    public BilibiliService(RestTemplateBuilder builder, AppProperties appProperties, SettingRepository settingRepository) {
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.USER_AGENT, appProperties.getUserAgent())
                .build();
        this.appProperties = appProperties;
        this.settingRepository = settingRepository;
    }

    @Override
    public String getType() {
        return "bili";
    }

    @Override
    public String getName() {
        return "B站";
    }

    @Override
    public MovieList home() throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        List<BilibiliRoomInfo> rooms = recommendRooms();

        for (var room : rooms) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + room.getRoomid());
            detail.setVod_name(room.getTitle());
            detail.setVod_pic(fixCover(room.getCover()));
            detail.setVod_remarks(room.getUname());
            userMap.put(String.valueOf(room.getRoomid()), room.getUname());
            list.add(detail);
        }

        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("home result: {}", result);
        return result;
    }

    /**
     * 热门推荐三级链路(pure_live 验证的同款顺序):
     * ① webMain/getMoreRecList 官方 web 首页推荐流,免 WBI 签名,重试 2 次(间隔 180ms);
     * ② room/v1/Area/getListByAreaID 匿名分区接口兜底;
     * ③ 原 index/getList 首页流最终保底。
     * 登录态 cookie 下主源只返回少量个性化推荐:与匿名兜底结果合并(个性化排前、按房间号去重),
     * 而不是二选一丢弃。
     */
    private List<BilibiliRoomInfo> recommendRooms() {
        List<BilibiliRoomInfo> merged = new ArrayList<>(moreRecRooms());
        if (merged.size() >= MIN_RECOMMEND_ROOMS) {
            return merged;
        }
        for (BilibiliRoomInfo room : areaRooms()) {
            if (merged.stream().noneMatch(item -> item.getRoomid() == room.getRoomid())) {
                merged.add(room);
            }
        }
        if (merged.size() < MIN_RECOMMEND_ROOMS) {
            for (BilibiliRoomInfo room : indexRooms()) {
                if (merged.stream().noneMatch(item -> item.getRoomid() == room.getRoomid())) {
                    merged.add(room);
                }
            }
        }
        return merged;
    }

    private List<BilibiliRoomInfo> moreRecRooms() {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (attempt > 0) {
                    Thread.sleep(180);
                }
                String url = "https://api.live.bilibili.com/xlive/web-interface/v1/webMain/getMoreRecList?platform=web&page=1";
                var response = restTemplate.exchange(url, HttpMethod.GET, buildHttpEntity(null), BilibiliRoomsResponse.class).getBody();
                if (response != null && response.getCode() == 0 && response.getData() != null
                        && response.getData().getRecommend_room_list() != null) {
                    return response.getData().getRecommend_room_list();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("getMoreRecList attempt {} failed", attempt, e);
            }
        }
        return List.of();
    }

    private List<BilibiliRoomInfo> areaRooms() {
        try {
            String url = "https://api.live.bilibili.com/room/v1/Area/getListByAreaID?areaId=0&parent_area_id=0&sort=online&pageSize=30&page=1";
            ObjectNode response = restTemplate.exchange(url, HttpMethod.GET, buildHttpEntity(null), ObjectNode.class).getBody();
            if (response != null && response.path("code").asInt(-1) == 0 && response.path("data").isArray()) {
                List<BilibiliRoomInfo> rooms = new ArrayList<>();
                for (JsonNode item : response.path("data")) {
                    BilibiliRoomInfo room = new BilibiliRoomInfo();
                    room.setRoomid(item.path("roomid").asInt());
                    room.setTitle(item.path("title").asText());
                    room.setCover(item.path("user_cover").asText(item.path("cover").asText()));
                    room.setUname(item.path("uname").asText());
                    if (room.getRoomid() > 0) {
                        rooms.add(room);
                    }
                }
                return rooms;
            }
        } catch (Exception e) {
            log.debug("getListByAreaID failed", e);
        }
        return List.of();
    }

    /** 原 index/getList 首页推荐流(需 buvid3+Referer),作为最终保底。 */
    private List<BilibiliRoomInfo> indexRooms() {
        List<BilibiliRoomInfo> rooms = new ArrayList<>();
        String url = "https://api.live.bilibili.com/xlive/web-interface/v1/index/getList?platform=web&page=1";
        var response = restTemplate.exchange(url, HttpMethod.GET, buildHttpEntity(null), BilibiliRoomsResponse.class).getBody();
        if (response != null && response.getCode() == 0 && response.getData() != null) {
            if (response.getData().getRecommend_room_list() != null) {
                rooms.addAll(response.getData().getRecommend_room_list());
            }
            if (response.getData().getRoom_list() != null) {
                for (var room : response.getData().getRoom_list()) {
                    if (rooms.stream().noneMatch(item -> item.getRoomid() == room.getRoomid())) {
                        rooms.add(room);
                    }
                }
            }
        }
        return rooms;
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        String url = "https://api.live.bilibili.com/xlive/web-interface/v1/index/getWebAreaList?source_id=2";
        var response = restTemplate.getForObject(url, BilibiliCategoriesResponse.class);

        String cover = getCover();
        for (var data : response.getData().getData()) {
            categoryMap.put(String.valueOf(data.getId()), data.getList());
            Category category = new Category();
            category.setType_id(getType() + "-" + data.getId());
            category.setType_name(data.getName());
            category.setType_flag(0);
            category.setCover(cover);
            list.add(category);
        }

        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("category result: {}", result);
        return result;
    }

    private String getCover() {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath("/bilibili.jpg")
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    private final Pattern pattern = Pattern.compile("\"access_id\"\\s*:\\s*\"([^\"]+)\"");

    @Override
    public MovieList list(String tid, String ac, String sort, Integer pg) throws IOException {
        String[] parts = tid.split("-");

        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        if (parts.length == 2) {
            if (categoryMap.isEmpty()) {
                category();
            }

            String id = parts[1];
            for (var item : categoryMap.get(id)) {
                MovieDetail detail = new MovieDetail();
                detail.setVod_id(tid + "-" + item.getId());
                detail.setVod_name(item.getName());
                if ("gui".equals(ac)) {
                    detail.setVod_pic(item.getPic());
                } else {
                    detail.setVod_pic(fixCover(item.getPic()));
                }
                detail.setVod_remarks(item.getParent_name());
                detail.setVod_tag(FOLDER);
                list.add(detail);
            }
        } else {
            String pid = parts[1];
            String id = parts[2];

            Map<String, Object> map = new HashMap<>();
            map.put("platform", "web");
            map.put("sort_type", "");
            map.put("vajra_business_key", "");
            map.put("web_location", "444.253");
            map.put("wts", System.currentTimeMillis() / 1000);
            map.put("parent_area_id", pid);
            map.put("area_id", id);
            map.put("page", pg);

            getKeys();
            var entity = buildHttpEntity(null);
            String url = "https://live.bilibili.com/p/eden/area-tags?parentAreaId=" + pid + "&areaId=" + id;
            log.debug("area page url: {}", url);
            var html = restTemplate.exchange(url, HttpMethod.GET, entity, String.class, pid, id);
            Matcher matcher = pattern.matcher(html.getBody());
            if (matcher.find()) {
                String accessId = matcher.group(1);
                map.put("w_webid", accessId);
            }

            url = "https://api.live.bilibili.com/xlive/web-interface/v1/second/getList?" + Utils.encryptWbi(map, imgKey, subKey);
            log.debug("list url: {} {}", url, entity);
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, BilibiliRoomsResponse.class);
            log.debug("list response: {} {}", response.getBody().getCode(), response.getBody().getMessage());
            for (var room : response.getBody().getData().getList()) {
                MovieDetail detail = new MovieDetail();
                detail.setVod_id(getType() + "$" + room.getRoomid());
                detail.setVod_name(room.getTitle());
                if ("gui".equals(ac)) {
                    detail.setVod_pic(room.getCover());
                } else {
                    detail.setVod_pic(fixCover(room.getCover()));
                }
                detail.setVod_remarks(room.getUname());
                userMap.put(String.valueOf(room.getRoomid()), room.getUname());
                list.add(detail);
            }
            if (list.size() < 40) {
                result.setPagecount(pg);
            } else {
                result.setPagecount(pg + 1);
            }
        }

        result.setList(list);
        result.setPage(pg);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("list result: {}", result);
        return result;
    }

    private <T> HttpEntity<T> buildHttpEntity(T data) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.REFERER, "https://live.bilibili.com/");
        headers.set(HttpHeaders.USER_AGENT, appProperties.getUserAgent());
        String cookie = settingRepository.findById(BILIBILI_COOKIE).map(Setting::getValue).orElse("");
        if (!cookie.contains("buvid3=")) {
            cookie += "; buvid3=" + UUID.randomUUID() + ThreadLocalRandom.current().nextInt(10000, 99999) + "infoc";
            settingRepository.save(new Setting(BILIBILI_COOKIE, cookie));
        }
        headers.set(HttpHeaders.COOKIE, cookie);
        return new HttpEntity<>(data, headers);
    }

    private void getKeys() {
        LocalDate now = LocalDate.now();
        if (keyTime == null || now.getDayOfYear() != keyTime.getDayOfYear()) {
            Map<String, Object> json = restTemplate.getForObject("https://api.bilibili.com/x/web-interface/nav", Map.class);
            Map<String, Object> data = (Map<String, Object>) json.get("data");
            Map<String, Object> wbi = (Map<String, Object>) data.get("wbi_img");
            imgKey = getKey((String) wbi.get("img_url"));
            subKey = getKey((String) wbi.get("sub_url"));
            keyTime = LocalDate.now();
            log.info("get WBI key: {} {}", imgKey, subKey);
        }
    }

    private String getKey(String url) {
        int start = url.lastIndexOf('/') + 1;
        int end = url.lastIndexOf('.');
        return url.substring(start, end);
    }

    @Override
    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        String url = "https://api.bilibili.com/x/web-interface/search/type?search_type=live_user&keyword=" + wd;
        var response = restTemplate.getForObject(url, ObjectNode.class);
        ArrayNode array = (ArrayNode) response.get("data").get("result");
        for (int i = 0; i < array.size(); i++) {
            var item = array.get(i);
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + item.get("roomid").asText());
            detail.setVod_name(item.get("uname").asText());
            detail.setVod_pic(item.get("uface").asText());
            //detail.setVod_remarks(item.get("uname").asText());
            list.add(detail);
        }

        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("search result: {}", result);
        return result;
    }

    @Override
    public MovieList detail(String tid, String client) throws IOException {
        String[] parts = tid.split("\\$");
        String id = parts[1];
        MovieList result = new MovieList();
        // 关注刷新会对这两个接口产生持续请求,带上 buvid3/Referer(与 home 一致),避免裸请求被游客风控(-352)
        String url = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + id;
        var response = restTemplate.exchange(url, HttpMethod.GET, buildHttpEntity(null), BilibiliRoomResponse.class).getBody();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            log.warn("B站房间信息获取失败: {} code={}", id, response == null ? "null" : response.getCode());
            result.getList().add(detail);
            result.setTotal(1);
            result.setLimit(1);
            return result;
        }
        var room = response.getData();
        detail.setVod_name(room.getTitle());
        String cover = room.getUser_cover();
        detail.setVod_pic(fixCover(cover == null || cover.isBlank() ? room.getCover() : cover));
        detail.setVod_actor(userMap.get(id));
        detail.setType_name(room.getArea_name());
        detail.setVod_remarks(playCount(room.getOnline()));
        parseUrl(detail, id);
        result.getList().add(detail);

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    private void parseUrl(MovieDetail movieDetail, String id) throws IOException {
        //id = getRealRoomId(id);
        List<String> playFrom = new ArrayList<>();
        List<String> playUrl = new ArrayList<>();

        String url = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?protocol=0,1&format=0,1,2&codec=0,1&platform=web&room_id=" + id;
        var response = restTemplate.exchange(url, HttpMethod.GET, buildHttpEntity(null), BilibiliRoomPlayResponse.class).getBody();

        // 房间未开播时接口不返回 playurl_info,无流可播;返回后 vod_play_url 为空即"未开播"标记
        if (response == null || response.getData() == null || response.getData().getPlayurl_info() == null) {
            return;
        }

        int count = 1;
        var streams = response.getData().getPlayurl_info().getPlayurl().getStream();
        Map<Integer, String> map = new HashMap<>();
        for (var qn : response.getData().getPlayurl_info().getPlayurl().getG_qn_desc()) {
            map.put(qn.getQn(), qn.getDesc());
        }

        for (var stream : streams) {
            playFrom.add("线路" + count++);
            List<String> urls = new ArrayList<>();
            for (var format : stream.getFormat()) {
                for (var codec : format.getCodec()) {
                    String baseUrl = codec.getBase_url();
                    int i = 1;
                    List<BilibiliRoomPlayInfo.UrlInfo> list = new ArrayList<>(codec.getUrl_info());
                    Collections.reverse(list);
                    for (var urlInfo : list) {
                        url = urlInfo.getHost() + baseUrl + urlInfo.getExtra();
                        urls.add(map.get(codec.getCurrent_qn()) + "-" + format.getFormat_name() + "-" + codec.getCodec_name() + "-" + i + "$" + url);
                        i++;
                    }
                }
            }
            playUrl.add(String.join("#", urls));
        }

        movieDetail.setVod_play_from(String.join("$$$", playFrom));
        movieDetail.setVod_play_url(String.join("$$$", playUrl));
    }

    private String getRealRoomId(String roomId) {
        ObjectNode response = restTemplate.getForObject("https://api.live.bilibili.com/xlive/web-room/v1/index/getH5InfoByRoom?room_id=" + roomId, ObjectNode.class);
        if (response.get("code").asInt() == 0) {
            return response.get("data").get("room_info").get("room_id").asText();
        }
        return roomId;
    }

    private static final Pattern BUVID = Pattern.compile("buvid3=([^;]+)");
    private static final Pattern DEDE_USER_ID = Pattern.compile("DedeUserID=(\\d+)");

    /**
     * 获取 B站弹幕连接参数:真实房间号 + getDanmuInfo(WBI 签名)返回的 token 与弹幕服务器。
     */
    public BilibiliDanmakuClient.BiliDanmakuArgs getDanmakuArgs(String roomId) {
        try {
            String realRoomId = getRealRoomId(roomId);
            getKeys();
            Map<String, Object> params = new HashMap<>();
            params.put("id", realRoomId);
            String url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?" + Utils.encryptWbi(params, imgKey, subKey);
            var response = restTemplate.exchange(url, HttpMethod.GET, buildHttpEntity(null), ObjectNode.class);
            var data = response.getBody().path("data");
            String token = data.path("token").asText("");
            String host = data.path("host_list").path(0).path("host").asText("");
            if (token.isEmpty() || host.isEmpty()) {
                log.warn("B站弹幕参数获取失败: code={} {}", response.getBody().path("code").asInt(), roomId);
                return null;
            }
            String cookie = settingRepository.findById(BILIBILI_COOKIE).map(Setting::getValue).orElse("");
            Matcher matcher = BUVID.matcher(cookie);
            String buvid = matcher.find() ? matcher.group(1) : "";
            // 进房包的 uid 必须与 Cookie 的登录身份一致,否则服务端在握手后立刻断开
            Matcher uidMatcher = DEDE_USER_ID.matcher(cookie);
            long uid = 0;
            if (uidMatcher.find()) {
                try {
                    uid = Long.parseLong(uidMatcher.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            return new BilibiliDanmakuClient.BiliDanmakuArgs(Long.parseLong(realRoomId), uid, token, buvid, host, cookie);
        } catch (Exception e) {
            log.warn("B站弹幕参数获取失败: {}", roomId, e);
            return null;
        }
    }

    private String fixCover(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        // nginx https
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath("/images")
                .replaceQuery("url=" + url)
                .build()
                .toUriString();
    }
}
