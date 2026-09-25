package cn.har01d.alist_tvbox.live.service;

import org.apache.commons.lang3.StringUtils;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.live.model.DouyuCategoryResponse;
import cn.har01d.alist_tvbox.live.model.DouyuRoomResponse;
import cn.har01d.alist_tvbox.live.model.DouyuRoomsResponse;
import cn.har01d.alist_tvbox.live.model.DouyuStreamResponse;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DouyuService implements LivePlatform {
    /** web 管理端配置的用户 cookie 存储键:登录态解锁原画等高画质,匿名流不仅限档还会 5-30 分钟中断。 */
    public static final String COOKIE_SETTING = "douyu_cookie";
    private static final String GET_ENCRYPTION_URL = "https://www.douyu.com/wgapi/livenc/liveweb/websec/getEncryption";
    private static final String PLAY_API = "https://www.douyu.com/lapi/live/getH5PlayV1/";
    private static final String LEGACY_SIGN_URL = "http://dy.har01d.cn/sign";
    /** getEncryption 加密描述符缓存窗口(pure_live 同款 5 分钟),描述符与房间无关,全房间共享。 */
    private static final long ENC_KEY_CACHE_SECONDS = 5 * 60;

    private final Map<String, String> categoryMap = new HashMap<>();
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SettingRepository settingRepository;
    /** 进程级设备 DID:签名表单与请求 Cookie 恒用同一值。用户粘贴的 Cookie 里 dy_did/acf_did 会被它取代,
     *  防止表单 did 与请求头 Cookie 中的 did 冲突(pure_live issue #873 审计结论)。 */
    private final String deviceId = randomDeviceId();
    private volatile JsonNode encryptionKey;
    private volatile long encryptionKeyFetchedAt;

    public DouyuService(RestTemplateBuilder builder, ObjectMapper objectMapper, SettingRepository settingRepository) {
        this.restTemplate = builder
                .defaultHeader("User-Agent", Constants.MOBILE_USER_AGENT)
                .build();
        this.objectMapper = objectMapper;
        this.settingRepository = settingRepository;
    }

    @Override
    public String getType() {
        return "douyu";
    }

    @Override
    public String getName() {
        return "斗鱼";
    }

    @Override
    public MovieList home() throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            MovieList temp = list("", i);
            list.addAll(temp.getList());
            if (temp.getList().size() < 8) {
                break;
            }
        }

        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("home result: {}", result);
        return result;
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        String url = "https://m.douyu.com/api/cate/list";
        var response = restTemplate.getForObject(url, DouyuCategoryResponse.class);

        for (var item : response.getData().getCate2Info()) {
            Category category = new Category();
            category.setType_id(getType() + "-" + item.getCate2Id());
            category.setType_name(item.getCate2Name());
            category.setType_flag(0);
            category.setCover(item.getPic());
            categoryMap.put(category.getType_id(), item.getShortName());
            list.add(category);
        }

        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("category result: {}", result);
        return result;
    }

    @Override
    public MovieList list(String id, String ac, String sort, Integer pg) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        if (categoryMap.isEmpty()) {
            category();
        }

        int size = 6;
        int start = (pg - 1) * size + 1;
        int end = start + size;
        for (int i = start; i < end; i++) {
            MovieList temp = list(categoryMap.get(id), i);
            list.addAll(temp.getList());
            if (temp.getList().size() < 8) {
                break;
            }
            result.setPagecount((temp.getPagecount() + size - 1) / size);
        }

        result.setList(list);
        result.setPage(pg);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("list result: {}", result);
        return result;
    }

    private MovieList list(String type, int pg) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        String url = "https://m.douyu.com/api/room/list?page=" + pg + "&type=" + type;
        var response = restTemplate.getForObject(url, DouyuRoomsResponse.class);
        for (var room : response.getData().getList()) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + room.getRid());
            detail.setVod_name(room.getRoomName());
            detail.setVod_pic(room.getRoomSrc());
            detail.setVod_remarks(room.getNickname());
            list.add(detail);
        }
        result.setList(list);
        result.setPagecount(response.getData().getPageCount());
        return result;
    }

    @Override
    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        var response = restTemplate.postForObject("https://m.douyu.com/api/search/anchor?offset=0&limit=30&sk=" + wd, null, DouyuRoomsResponse.class);
        for (var room : response.getData().getList()) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + room.getRoomId());
            detail.setVod_name(room.getRoomName());
            detail.setVod_pic(room.getRoomSrc());
            detail.setVod_remarks(room.getNickname());
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
        if (parts.length < 2) {
            throw new BadRequestException("无效的直播间ID: " + tid);
        }
        String id = parts[1];
        MovieList result = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);
        if (getRoomDetailByBetard(detail, id)) {
            // betard 官方接口:room.videoLoop==1 是轮播录播间,能取到流但不是真直播,须显式标注
            parseUrl(detail, id);
        } else {
            getRoomDetailByOpenApi(detail, id);
            parseUrl(detail, id);
        }
        result.getList().add(detail);

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    /** betard 元数据解析失败时回退的第三方开放接口(open.douyucdn.cn),无录播标记。 */
    private void getRoomDetailByOpenApi(MovieDetail detail, String id) {
        String url = "http://open.douyucdn.cn/api/RoomApi/room/" + id;
        var response = restTemplate.getForObject(url, DouyuRoomResponse.class);
        var room = response.getData();
        detail.setVod_name(room.getRoom_name());
        detail.setVod_pic(room.getRoom_thumb());
        detail.setVod_actor(room.getOwner_name());
        detail.setType_name(room.getCate_name());
        detail.setVod_remarks(playCount(room.getOnline()));
    }

    /** 官方 web 端 betard 接口,唯一带 videoLoop 轮播标记的元数据源;解析不到有效房间返回 false。 */
    private boolean getRoomDetailByBetard(MovieDetail detail, String id) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
            headers.set(HttpHeaders.REFERER, "https://www.douyu.com/" + id);
            String body = restTemplate.exchange("https://www.douyu.com/betard/" + id, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class).getBody();
            JsonNode room = objectMapper.readTree(body).path("room");
            if (!room.isObject() || room.path("room_id").asLong(0) == 0) {
                return false;
            }
            detail.setVod_name(room.path("room_name").asText());
            detail.setVod_pic(room.path("room_pic").asText(room.path("room_src").asText()));
            detail.setVod_actor(room.path("nickname").asText());
            detail.setType_name(room.path("second_lvl_name").asText());
            boolean replay = room.path("videoLoop").asInt(0) == 1;
            detail.setVod_remarks(replay ? "录播中" : "直播中");
            return true;
        } catch (Exception e) {
            log.warn("斗鱼betard获取失败,回退开放接口: {}", id, e);
            return false;
        }
    }

    private void parseUrl(MovieDetail movieDetail, String id) throws IOException {
        PlayArgs args = getPlayArgs(id);
        if (args == null) {
            return;
        }
        String dataUse = args.form() + args.extraParams() + "&cdn=&rate=-1";
        log.debug("dataUse: {}", dataUse);

        HttpHeaders headers = playHeaders(id, args);
        HttpEntity<String> request = new HttpEntity<>(dataUse, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                PLAY_API + id,
                HttpMethod.POST,
                request,
                String.class
        );
        log.debug("{}", response.getBody());

        List<String> playFrom = new ArrayList<>();
        List<String> playUrl = new ArrayList<>();

        DouyuStreamResponse douyuStreamResponse = objectMapper.readValue(response.getBody(), DouyuStreamResponse.class);
        var stream = douyuStreamResponse.getData();
        // 未开播/签名失败返回错误 JSON 无 data:无流即不出线路(此前 getCdnsWithName 直接 NPE,detail 500)
        if (stream == null || stream.getCdnsWithName() == null || stream.getCdnsWithName().isEmpty()) {
            log.debug("douyu room {} has no stream data (offline or sign failed)", id);
            return;
        }
        var cdns = stream.getCdnsWithName();
        List<cn.har01d.alist_tvbox.live.model.DouyuLiveStream.BitRate> rates =
                stream.getMultirates() != null ? stream.getMultirates() : java.util.Collections.emptyList();
        // 每条线路取全清晰度:实测(2026-09-10)斗鱼已把每房 CDN 收敛到 1~2 条(hw-h5/hs-h5),
        // 全档成本回到 4~10 次请求可接受;曾按「默认线路全档、其余单档」砍请求,CDN 收敛后
        // 第二条线路的清晰度菜单价值 > 省下的几次请求,恢复全档
        for (var cdn : cdns) {
            List<String> urls = new ArrayList<>();
            for (var bitRate : rates) {
                String playUrlItem = getPlayUrl(id, args, bitRate.getRate(), cdn.getCdn());
                if (StringUtils.isNotBlank(playUrlItem)) {
                    urls.add(bitRate.getName() + "$" + playUrlItem);
                }
            }
            if (!urls.isEmpty()) {
                playFrom.add(cdn.getName());
                playUrl.add(String.join("#", urls));
            }
        }

        movieDetail.setVod_play_from(String.join("$$$", playFrom));
        movieDetail.setVod_play_url(String.join("$$$", playUrl));
    }

    private String getPlayUrl(String id, PlayArgs args, int rate, String cdn) {
        String dataUse = args.form() + args.extraParams() + "&cdn=" + cdn + "&rate=" + rate;
        HttpHeaders headers = playHeaders(id, args);
        HttpEntity<String> request = new HttpEntity<>(dataUse, headers);
        ResponseEntity<ObjectNode> response = restTemplate.exchange(
                PLAY_API + id,
                HttpMethod.POST,
                request,
                ObjectNode.class
        );

        ObjectNode data = (ObjectNode) response.getBody().get("data");
        if (data == null || data.path("rtmp_url").isMissingNode() || data.path("rtmp_live").isMissingNode()) {
            // 该清晰度无流(错误响应无 data):返回空串让调用方跳过此条目,不让整次 detail 崩掉
            return "";
        }
        String rtmpUrl = data.get("rtmp_url").asText();
        String rtmpLive = data.get("rtmp_live").asText();
        return combinePlayUrl(rtmpUrl, rtmpLive);
    }

    /** rtmp_live 偶为完整签名 URL 必须直接用;再拼 rtmp_url 会得到语法合法但不可播的双 URL(pure_live 实证坑)。 */
    static String combinePlayUrl(String rtmpUrl, String rtmpLive) {
        String live = StringEscapeUtils.unescapeHtml4(rtmpLive).trim();
        if (live.startsWith("http://") || live.startsWith("https://") || live.startsWith("rtmp://")) {
            return live;
        }
        return rtmpUrl.replaceAll("/+$", "") + "/" + live.replaceFirst("^/+", "");
    }

    /** getH5PlayV1 请求头:签名表单与 Cookie 的 did 必须同源;回退链路 did 由外部签名服务随机生成,
     *  与用户 Cookie 无从对齐,按匿名请求(不带 Cookie)保持一致性。 */
    private HttpHeaders playHeaders(String id, PlayArgs args) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
        headers.set(HttpHeaders.REFERER, "https://www.douyu.com/" + id);
        headers.set(HttpHeaders.ORIGIN, "https://www.douyu.com");
        if (args.localDid()) {
            headers.set(HttpHeaders.COOKIE, buildCookieHeader(deviceId, userCookie()));
        }
        return headers;
    }

    /** 用户在 web 管理端配置的 cookie,未配置返回空串。 */
    private String userCookie() {
        return settingRepository.findById(COOKIE_SETTING).map(Setting::getValue).orElse("");
    }

    /** 请求 Cookie:dy_did/acf_did 恒为签名 DID,用户粘贴值里的同名项剔除防冲突,其余登录字段(dy_auth 等)原样附加。 */
    static String buildCookieHeader(String did, String userCookie) {
        StringBuilder sb = new StringBuilder("dy_did=").append(did).append("; acf_did=").append(did);
        String normalized = userCookie == null ? "" : userCookie.trim().replaceFirst("(?i)^Cookie:\\s*", "");
        for (String piece : normalized.split(";")) {
            int sep = piece.indexOf('=');
            if (sep <= 0) {
                continue;
            }
            String name = piece.substring(0, sep).trim();
            String lower = name.toLowerCase();
            if (lower.equals("dy_did") || lower.equals("acf_did")) {
                continue;
            }
            sb.append("; ").append(name).append("=").append(piece.substring(sep + 1).trim());
        }
        return sb.toString();
    }

    /** 签名产物:form 为公共参数串(不含 cdn/rate,由调用方按档位/线路追加,auth 不依赖它们)。 */
    record PlayArgs(String form, String extraParams, boolean localDid) {
    }

    /** 签名:现行 getEncryption 描述符 + 本地 MD5(pure_live 同款);失败回退外部 js 签名服务,两者皆败返回 null。 */
    private PlayArgs getPlayArgs(String roomId) {
        try {
            JsonNode key = fetchEncryptionKey();
            return new PlayArgs(buildSignedForm(roomId, key, deviceId, System.currentTimeMillis() / 1000),
                    "&hevc=0&fa=0&ive=0&ver=Douyu_new&iar=0", true);
        } catch (Exception e) {
            log.warn("斗鱼本地签名失败,回退外部签名服务: {}", e.getMessage());
            return legacySign(roomId);
        }
    }

    private synchronized JsonNode fetchEncryptionKey() throws IOException {
        long now = System.currentTimeMillis() / 1000;
        if (encryptionKey != null && now - encryptionKeyFetchedAt < ENC_KEY_CACHE_SECONDS
                && isEncryptionKeyUsable(encryptionKey, now)) {
            return encryptionKey;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
        headers.set(HttpHeaders.REFERER, "https://www.douyu.com/");
        headers.set(HttpHeaders.ORIGIN, "https://www.douyu.com");
        headers.set(HttpHeaders.COOKIE, buildCookieHeader(deviceId, userCookie()));
        String body = restTemplate.exchange(GET_ENCRYPTION_URL + "?did=" + deviceId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody();
        JsonNode data = objectMapper.readTree(body).path("data");
        if (!isEncryptionKeyUsable(data, now)) {
            throw new IllegalStateException("encryption descriptor incomplete or expired");
        }
        encryptionKey = data;
        encryptionKeyFetchedAt = now;
        return encryptionKey;
    }

    /** 描述符完整性校验(pure_live 同款):expire_at 留 30 秒边际,enc_time 1..16,关键字段非空。 */
    static boolean isEncryptionKeyUsable(JsonNode key, long nowSeconds) {
        return key.path("expire_at").asLong(0) > nowSeconds + 30
                && key.path("enc_time").asInt(0) > 0 && key.path("enc_time").asInt(0) <= 16
                && !key.path("key").asText("").isEmpty()
                && !key.path("rand_str").asText("").isEmpty()
                && !key.path("enc_data").asText("").isEmpty();
    }

    /** 纯 MD5 签名:secret = enc_time 次 md5(rand_str+key),auth = md5(secret+key+roomId+tt);
     *  is_special=1 时盐为空。enc_data 需 form 编码(base64 体质含 +/=)。 */
    static String buildSignedForm(String roomId, JsonNode key, String did, long ttSeconds) {
        String keyStr = key.path("key").asText();
        int encTime = key.path("enc_time").asInt();
        String secret = key.path("rand_str").asText();
        for (int i = 0; i < encTime; i++) {
            secret = DigestUtils.md5DigestAsHex((secret + keyStr).getBytes(StandardCharsets.UTF_8));
        }
        String salt = key.path("is_special").asInt(0) == 1 ? "" : roomId + ttSeconds;
        String auth = DigestUtils.md5DigestAsHex((secret + keyStr + salt).getBytes(StandardCharsets.UTF_8));
        return "enc_data=" + URLEncoder.encode(key.path("enc_data").asText(), StandardCharsets.UTF_8)
                + "&tt=" + ttSeconds + "&did=" + did + "&auth=" + auth;
    }

    private static String randomDeviceId() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(Integer.toHexString(random.nextInt(16)));
        }
        return sb.toString();
    }

    /** 旧链路回退:homeH5Enc 描述符交外部 js 签名服务执行(did 由服务端随机生成,无法与用户 Cookie 对齐,匿名请求)。 */
    private PlayArgs legacySign(String roomId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.REFERER, "https://www.douyu.com/" + roomId);
            headers.set(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
            String html = restTemplate.exchange("https://www.douyu.com/swf_api/homeH5Enc?rids=" + roomId,
                    HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
            ObjectNode response = restTemplate.postForObject(LEGACY_SIGN_URL, objectMapper.readTree(html), ObjectNode.class);
            String result = response.get("result").asText();
            if (result.isBlank()) {
                throw new IllegalStateException("empty sign result");
            }
            return new PlayArgs(result, "&ver=Douyu_223061205&iar=1&ive=1&hevc=0&fa=0", false);
        } catch (Exception e) {
            log.error("斗鱼外部签名服务失败: {}", roomId, e);
            return null;
        }
    }

}
