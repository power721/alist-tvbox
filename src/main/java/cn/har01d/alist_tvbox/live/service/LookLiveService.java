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

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LOOK直播(网易云 look.163.com,pure_live looklive 适配器同源契约):
 * 全接口 POST 网易 weapi 加密(双层 AES-CBC + 无填充 RSA);目录=homepage/recommend(视频/语音两路,
 * offset=(page-1)*20 服务端分页);详情=livestream/room/get/v3;播放=liveUrl 的 hlsPullUrl/httpPullUrl。
 */
@Slf4j
@Service
public class LookLiveService implements LivePlatform {
    private static final String API_ORIGIN = "https://api.look.163.com";
    private static final String WEB_ORIGIN = "https://look.163.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    // weapi 公开浏览器加密参数(pure_live 同款):先 nonce 后 secretKey 双层 AES,encSecKey 为 secretKey 反转字节的 RSA
    private static final String NONCE = "0CoJUm6Qyw8W8jud";
    private static final String SECRET_KEY = "0123456789abcdef";
    private static final String AES_IV = "0102030405060708";
    private static final String RSA_EXPONENT = "010001";
    private static final String RSA_MODULUS = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec"
            + "4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813"
            + "cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
    /** 房间号 2-18 位纯数字(pure_live 校验)。 */
    private static final Pattern ROOM_ID = Pattern.compile("^[1-9][0-9]{1,17}$");
    /** 流媒体 host 白名单:仅 *.live.126.net(pure_live 同款)。 */
    private static final Pattern MEDIA_HOST = Pattern.compile("^[a-z0-9-]+\\.live\\.126\\.net$");
    private static final Pattern HLS_PATH = Pattern.compile("^/live/[a-f0-9]{32}/playlist\\.m3u8$");
    private static final Pattern FLV_PATH = Pattern.compile("^/live/[a-f0-9]{32}\\.flv$");
    private static final int PAGE_SIZE = 20;
    /** 分类:全部=视频+语音合并,video/audio 对应两路推荐流(pure_live kind 映射)。 */
    private static final Map<String, String> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("all", "全部");
        CATEGORIES.put("video", "视频直播");
        CATEGORIES.put("audio", "语音直播");
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public LookLiveService(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder.defaultHeader("User-Agent", USER_AGENT).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getType() {
        return "look";
    }

    @Override
    public String getName() {
        return "LOOK直播";
    }

    @Override
    public MovieList home() throws IOException {
        return merged(1);
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();
        CATEGORIES.forEach((id, name) -> {
            Category category = new Category();
            category.setType_id(getType() + "-" + id);
            category.setType_name(name);
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
        if ("all".equals(parts[1])) {
            return merged(page);
        }
        boolean audio = "audio".equals(parts[1]);
        JsonNode data = recommend(audio, page);
        return buildResult(parseFeed(data, audio, new LinkedHashSet<>()), page, data.path("hasMore").asBoolean(false));
    }

    @Override
    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        String keyword = wd == null ? "" : wd.trim();
        if (!keyword.isEmpty() && keyword.length() <= 100) {
            // 房间号/直播间链接直达(pure_live 同款);不存在按无结果处理
            String directRoomId = parseRoomId(keyword);
            if (directRoomId != null) {
                try {
                    list.add(roomDetail(directRoomId));
                } catch (BadRequestException e) {
                    log.debug("LOOK房间号直达失败: {}", keyword);
                }
            } else {
                // 平台无搜索接口:拉首页推荐本地过滤 roomId/昵称/标题(pure_live 同款降级)
                String query = keyword.toLowerCase(Locale.ROOT);
                for (MovieDetail detail : merged(1).getList()) {
                    String roomId = detail.getVod_id().substring(getType().length() + 1);
                    String nick = detail.getVod_actor() == null ? "" : detail.getVod_actor().toLowerCase(Locale.ROOT);
                    String title = detail.getVod_name() == null ? "" : detail.getVod_name().toLowerCase(Locale.ROOT);
                    if (roomId.contains(query) || nick.contains(query) || title.contains(query)) {
                        list.add(detail);
                    }
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
        result.getList().add(roomDetail(roomId));
        result.setTotal(1);
        result.setLimit(1);
        log.debug("detail: {}", result);
        return result;
    }

    /** 无分类目录行为:video+audio 两路各拉一页合并去重,hasMore 任一路为真(pure_live 同款)。 */
    private MovieList merged(int page) throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        List<MovieDetail> list = new ArrayList<>();
        boolean hasMore = false;
        for (boolean audio : new boolean[]{false, true}) {
            JsonNode data = recommend(audio, page);
            list.addAll(parseFeed(data, audio, seen));
            hasMore = hasMore || data.path("hasMore").asBoolean(false);
        }
        return buildResult(list, page, hasMore);
    }

    /** 推荐流:video=homepage/recommend,audio=listen/homepage/recommend/list,offset 从 0 起。 */
    private JsonNode recommend(boolean audio, int page) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offset", (page - 1) * PAGE_SIZE);
        payload.put("limit", PAGE_SIZE);
        return post(audio ? "/weapi/livestream/listen/homepage/recommend/list" : "/weapi/livestream/homepage/recommend", payload);
    }

    /** 推荐流条目:itemList[].type==1 且带 liveData;语音流会混入视频卡,按 liveType 过滤(pure_live 同款)。 */
    private List<MovieDetail> parseFeed(JsonNode data, boolean audio, Set<String> seen) {
        List<MovieDetail> list = new ArrayList<>();
        for (JsonNode item : data.path("itemList")) {
            if (!"1".equals(item.path("type").asText())) {
                continue;
            }
            JsonNode live = item.path("liveData");
            if (!live.isObject()) {
                continue;
            }
            int liveType = live.path("liveType").asInt(0);
            if (liveType != 1 && liveType != 2 || (liveType == 2) != audio) {
                continue;
            }
            MovieDetail detail = parseCard(live);
            if (detail != null && seen.add(detail.getVod_id())) {
                list.add(detail);
            }
        }
        return list;
    }

    /** 卡片字段:userInfo.{liveRoomNo,userId,nickname,avatarUrl},liveTitle/liveCoverUrl/onlineNumber/popularity。 */
    private MovieDetail parseCard(JsonNode live) {
        JsonNode user = live.path("userInfo");
        String roomId = user.path("liveRoomNo").asText().trim();
        if (!ROOM_ID.matcher(roomId).matches()) {
            return null;
        }
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(getType() + "$" + roomId);
        String nick = text(user.path("nickname").asText());
        detail.setVod_name(firstText(live.path("liveTitle").asText(), nick));
        detail.setVod_pic(picture(firstText0(live.path("liveCoverUrl").asText(), user.path("avatarUrl").asText())));
        detail.setVod_actor(nick);
        int online = intValue(live.path("onlineNumber").asText());
        int popularity = intValue(live.path("popularity").asText());
        detail.setVod_remarks(online >= 0 ? playCount(online) : popularity >= 0 ? playCount(popularity) : "");
        return detail;
    }

    /** 详情:anchor.liveRoomNo 必须回显一致(pure_live identity 校验);liveStatus 1/0/-1/-10。 */
    private MovieDetail roomDetail(String roomId) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("liveRoomNo", roomId);
        JsonNode data = post("/weapi/livestream/room/get/v3", payload);
        JsonNode anchor = data.path("anchor");
        if (!roomId.equals(anchor.path("liveRoomNo").asText().trim())) {
            throw new BadRequestException("房间不存在: " + roomId);
        }
        JsonNode info = data.path("roomInfo");
        if (!info.isObject()) {
            throw new BadRequestException("房间数据格式异常: " + roomId);
        }
        int liveStatus = data.path("liveStatus").asInt(-99);
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(getType() + "$" + roomId);
        String nick = text(anchor.path("nickName").asText());
        detail.setVod_name(firstText(info.path("title").asText(), nick));
        detail.setVod_pic(picture(firstText0(info.path("liveCoverUrl").asText(), anchor.path("avatarUrl").asText())));
        detail.setVod_actor(nick);
        detail.setVod_area(info.path("liveType").asInt(1) == 2 ? "语音直播" : "视频直播");
        List<String> entries = new ArrayList<>();
        if (liveStatus == 1) {
            addVariant(entries, "HLS", info.path("liveUrl").path("hlsPullUrl").asText().trim(), true);
            addVariant(entries, "FLV", info.path("liveUrl").path("httpPullUrl").asText().trim(), false);
        }
        // liveStreamType==50 且无流地址=仅 App 端可看(pure_live isAppOnly)
        boolean appOnly = liveStatus == 1 && info.path("liveStreamType").asInt(0) == 50 && entries.isEmpty();
        detail.setVod_remarks(appOnly ? "仅App观看"
                : liveStatus == 1 ? "直播中"
                : liveStatus == 0 || liveStatus == -1 ? "未开播"
                : liveStatus == -10 ? "受限房" : "未知状态");
        if (!entries.isEmpty()) {
            detail.setVod_play_from("线路1");
            detail.setVod_play_url(String.join("#", entries));
        }
        return detail;
    }

    /** 流地址白名单校验(pure_live 精简):仅 *.live.126.net、无端口/fragment、路径指纹匹配、强制 https。 */
    private void addVariant(List<String> entries, String label, String raw, boolean hls) {
        if (raw.isEmpty()) {
            return;
        }
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
            if (!"http".equals(scheme) && !"https".equals(scheme)
                    || !MEDIA_HOST.matcher(host).matches()
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || query.length() > 2048) {
                return;
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (!(hls ? HLS_PATH : FLV_PATH).matcher(path).matches()) {
                return;
            }
            entries.add(label + "$" + ("https".equals(scheme) ? raw : "https://" + raw.substring(scheme.length() + 3)));
        } catch (Exception e) {
            log.debug("LOOK播放地址校验失败: {}", raw);
        }
    }

    /** weapi 请求:表单 params+encSecKey;code 404=不存在,424/520/522/555=受限,200 才有效。 */
    private JsonNode post(String path, Map<String, Object> payload) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        headers.set(HttpHeaders.ORIGIN, WEB_ORIGIN);
        headers.set(HttpHeaders.REFERER, WEB_ORIGIN + "/");
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String body = restTemplate.exchange(API_ORIGIN + path, HttpMethod.POST,
                new HttpEntity<>(encryptForm(payload), headers), String.class).getBody();
        JsonNode root = objectMapper.readTree(body == null ? "" : body);
        int code = root.path("code").asInt(-1);
        if (code == 404) {
            throw new BadRequestException("房间不存在");
        }
        if (code == 424 || code == 520 || code == 522 || code == 555) {
            throw new BadRequestException("LOOK接口访问受限: " + code);
        }
        if (code != 200) {
            throw new IOException("LOOK接口返回码异常: " + code);
        }
        return root.path("data");
    }

    /** 加密表单:json 双层 AES-CBC(PKCS7)得 params,secretKey 反转字节 hex 后 RSA 得 encSecKey(256 位补零)。 */
    private String encryptForm(Map<String, Object> payload) throws IOException {
        try {
            // LinkedHashMap 保证 json 字段顺序与官方 web 包一致
            String params = aes(objectMapper.writeValueAsString(payload), NONCE);
            params = aes(params, SECRET_KEY);
            byte[] key = SECRET_KEY.getBytes(StandardCharsets.UTF_8);
            StringBuilder hex = new StringBuilder(key.length * 2);
            for (int i = key.length - 1; i >= 0; i--) {
                hex.append(String.format("%02x", key[i]));
            }
            BigInteger encrypted = new BigInteger(hex.toString(), 16)
                    .modPow(new BigInteger(RSA_EXPONENT, 16), new BigInteger(RSA_MODULUS, 16));
            String encSecKey = encrypted.toString(16);
            encSecKey = "0".repeat(Math.max(0, 256 - encSecKey.length())) + encSecKey;
            return "params=" + URLEncoder.encode(params, StandardCharsets.UTF_8)
                    + "&encSecKey=" + URLEncoder.encode(encSecKey, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IOException("LOOK接口加密失败", e);
        }
    }

    private String aes(String value, String key) throws GeneralSecurityException {
        // AES/CBC/PKCS5Padding 在 16 字节块上等价 dart pointycastle 的 PKCS7
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(AES_IV.getBytes(StandardCharsets.UTF_8)));
        return java.util.Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** 房间号:纯数字,或 look.163.com/live?id=xxx 链接(pure_live 同款)。 */
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
            if (!"http".equals(scheme) && !"https".equals(scheme) || !"look.163.com".equals(host)
                    || port != -1 && port != 80 && port != 443
                    || !"/live".equalsIgnoreCase(uri.getPath() == null ? "" : uri.getPath())) {
                return null;
            }
            String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
            for (String pair : query.split("&")) {
                if (pair.startsWith("id=")) {
                    String id = URLDecoder.decode(pair.substring(3), StandardCharsets.UTF_8);
                    return ROOM_ID.matcher(id).matches() ? id : null;
                }
            }
        } catch (Exception ignored) {
            // 非法 URL 一律视为无法解析
        }
        return null;
    }

    /** 图片规整(pure_live 同款):绝对 http(s) 地址强制 https,带端口/userinfo/fragment 的丢弃。 */
    private String picture(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "null".equals(value)) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost();
            if (!"http".equals(scheme) && !"https".equals(scheme) || host == null || host.isEmpty()
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getFragment() != null) {
                return "";
            }
            return "https".equals(scheme) ? value : "https://" + value.substring(scheme.length() + 3);
        } catch (Exception e) {
            return "";
        }
    }

    private MovieList buildResult(List<MovieDetail> list, int page, boolean hasMore) {
        MovieList result = new MovieList();
        result.setList(list);
        result.setPage(page);
        result.setPagecount(page + (hasMore ? 1 : 0));
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
    }

    private String text(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
    }

    private String firstText(String... values) {
        String value = firstText0(values);
        return value.isEmpty() ? "LOOK直播" : value;
    }

    private String firstText0(String... values) {
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
