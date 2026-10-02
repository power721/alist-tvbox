package cn.har01d.alist_tvbox.live.service;

import org.apache.commons.lang3.StringUtils;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.live.model.DouyuCategoryResponse;
import cn.har01d.alist_tvbox.live.model.DouyuRoomResponse;
import cn.har01d.alist_tvbox.live.model.DouyuRoomsResponse;
import cn.har01d.alist_tvbox.live.model.DouyuStreamResponse;
import cn.har01d.alist_tvbox.live.util.FlvSpliceSession;
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
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.stream.Collectors;

@Slf4j
@Service
public class DouyuService implements LivePlatform {
    /** web 管理端配置的用户 cookie 存储键:登录态解锁原画等高画质,匿名流不仅限档还会 5-30 分钟中断。 */
    public static final String COOKIE_SETTING = "douyu_cookie";
    /** Cookie 保存时间(秒)存储键:网页版 dy_auth 是不透明 token 读不到 exp,7 天时效从这里起算。 */
    static final String COOKIE_SAVED_AT_SETTING = "douyu_cookie_saved_at";
    private static final String GET_ENCRYPTION_URL = "https://www.douyu.com/wgapi/livenc/liveweb/websec/getEncryption";
    private static final String PLAY_API = "https://www.douyu.com/lapi/live/getH5PlayV1/";
    private static final String LEGACY_SIGN_URL = "http://dy.har01d.cn/sign";
    /** passport 端点:LTP0+dy_did 换新会话 Cookie(pure_live DouyuUtils.safeAuth 同款)。 */
    private static final String PASSPORT_SAFE_AUTH_URL = "https://passport.douyu.com/lapi/passport/iframe/safeAuth";
    /** getEncryption 加密描述符缓存窗口(pure_live 同款 5 分钟),描述符与房间无关,全房间共享。 */
    private static final long ENC_KEY_CACHE_SECONDS = 5 * 60;
    /** 网页版 dy_auth 的 7 天时效(pure_live webCookieLifetime),到期前 1 天进入续期窗口。 */
    private static final long WEB_COOKIE_LIFETIME_SECONDS = 7L * 24 * 3600;
    private static final long REFRESH_MARGIN_SECONDS = 24L * 3600;
    /** Set-Cookie 属性名:不是 Cookie 字段,合并时不得写回请求头(pure_live _setCookieAttributes)。 */
    private static final java.util.Set<String> SET_COOKIE_ATTRIBUTES =
            java.util.Set.of("path", "domain", "expires", "max-age", "samesite", "secure", "httponly");
    /** 合法 Cookie 字段名形态:拦住 "Path=/" 之类的属性碎片(pure_live cookieHeader 同款正则)。 */
    private static final java.util.regex.Pattern COOKIE_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_!#$%&'*+.^`|~-]+");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, String> categoryMap = new HashMap<>();
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SettingRepository settingRepository;
    /** 与 LiveProxyService 互为依赖,以 ObjectProvider 延迟化解(native 下 @Lazy CGLIB 不可用)。 */
    private final ObjectProvider<LiveProxyService> liveProxyService;
    /** 进程级设备 DID:无账号 Cookie dy_did 时的回退值。签名表单、请求 Cookie、getEncryption 三处恒用同一 DID。 */
    private final String deviceId = randomDeviceId();
    private volatile JsonNode encryptionKey;
    private volatile long encryptionKeyFetchedAt;
    /** 缓存的加密描述符签发给哪个 DID:描述符发给某台设备并校验签名来自同一台,换账号(=换 dy_did)必须作废。 */
    private volatile String encryptionKeyDeviceId;

    public DouyuService(RestTemplateBuilder builder, ObjectMapper objectMapper, SettingRepository settingRepository,
                        ObjectProvider<LiveProxyService> liveProxyService) {
        this.restTemplate = builder
                .defaultHeader("User-Agent", Constants.MOBILE_USER_AGENT)
                .build();
        this.objectMapper = objectMapper;
        this.settingRepository = settingRepository;
        this.liveProxyService = liveProxyService;
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
        // 播放是需要登录的路径:粘贴的 Cookie 只值 7 天,先按窗口保鲜(过期边缘自动续)
        ensureFreshSession(false);
        try {
            doParseUrl(movieDetail, id);
        } catch (Exception e) {
            // 第一次失败也是最便宜的凭证过期信号:有会话且有 LTP0 时强续一次再试,纯游客照旧上抛
            String cookie = userCookie();
            if (sessionToken(cookie) != null && cookieField(cookie, "LTP0") != null) {
                log.warn("斗鱼播放请求失败,强制续期后重试: room={} {}", id, e.getMessage());
                ensureFreshSession(true);
                doParseUrl(movieDetail, id);
            } else {
                throw e;
            }
        }
    }

    private void doParseUrl(MovieDetail movieDetail, String id) throws IOException {
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

        // 目录请求(不定档)可能回错误 JSON(无 data,如匿名被限流/房间异常):模型没有 error 字段,
        // 先读树判形再转换;转换用宽松 reader(Spring mapper 默认忽略未知字段,裸 mapper 会炸)
        JsonNode catalogRoot = objectMapper.readTree(response.getBody());
        if (!catalogRoot.path("data").isObject()) {
            log.warn("斗鱼房间 {} 播放目录请求无数据: error={} msg={}", id,
                    catalogRoot.path("error").asInt(-1), catalogRoot.path("msg").asText(""));
            return;
        }
        DouyuStreamResponse douyuStreamResponse = objectMapper
                .readerFor(DouyuStreamResponse.class)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(catalogRoot);
        var stream = douyuStreamResponse.getData();
        // 未开播/签名失败返回错误 JSON 无 data:无流即不出线路(此前 getCdnsWithName 直接 NPE,detail 500)
        if (stream == null || stream.getCdnsWithName() == null || stream.getCdnsWithName().isEmpty()) {
            log.debug("douyu room {} has no stream data (offline or sign failed)", id);
            return;
        }
        var cdns = stream.getCdnsWithName();
        List<cn.har01d.alist_tvbox.live.model.DouyuLiveStream.BitRate> rates =
                stream.getMultirates() != null ? stream.getMultirates() : java.util.Collections.emptyList();
        java.util.Set<Integer> rateIds = new java.util.HashSet<>();
        for (var bitRate : rates) {
            rateIds.add(bitRate.getRate());
        }
        // 每条线路取全清晰度:实测(2026-09-10)斗鱼已把每房 CDN 收敛到 1~2 条(hw-h5/hs-h5),
        // 全档成本回到 4~10 次请求可接受;曾按「默认线路全档、其余单档」砍请求,CDN 收敛后
        // 第二条线路的清晰度菜单价值 > 省下的几次请求,恢复全档
        for (var cdn : cdns) {
            List<String> urls = new ArrayList<>();
            for (var bitRate : rates) {
                PlayResult result = getPlayUrl(id, args, bitRate.getRate(), cdn.getCdn());
                if (StringUtils.isBlank(result.url())) {
                    continue;
                }
                // 服务端回落(pure_live 03234c7d acknowledged quality 同款信源):请求档未被确认且
                // 确认档在本房目录内 → 跳过该条目,防「原画」条目实际给 4M 流的名实不符与重复;
                // 确认档不在目录(如单档房被回落)时保留,宁可名实不符不丢流
                if (result.ackRate() >= 0 && result.ackRate() != bitRate.getRate() && rateIds.contains(result.ackRate())) {
                    log.debug("douyu room {} rate {} fell back to {}", id, bitRate.getRate(), result.ackRate());
                    continue;
                }
                urls.add(bitRate.getName() + "$" + relayUrlIfLeased(result.url(), id, bitRate.getRate(), cdn.getCdn()));
            }
            if (!urls.isEmpty()) {
                playFrom.add(cdn.getName());
                playUrl.add(String.join("#", urls));
            }
        }

        movieDetail.setVod_play_from(String.join("$$$", playFrom));
        movieDetail.setVod_play_url(String.join("$$$", playUrl));
    }

    /** 播放条目与其服务端确认档位(getH5PlayV1 响应 data.rate):确认档≠请求档即发生了回落。 */
    record PlayResult(String url, int ackRate) {
    }

    /** 带 expire 租约的 FLV 地址(匿名原画 expire=300,CDN 到点断流且客户端无从重签)改发服务端
     *  拼接中继地址,换链在关键帧上无缝接续(pure_live 3.2.11 #35);无租约/无请求上下文回退直连。 */
    private String relayUrlIfLeased(String url, String roomId, int rate, String cdn) {
        if (!FlvSpliceSession.appliesTo(url)) {
            return url;
        }
        try {
            return liveProxyService.getObject().buildDouyuProxyUrl(roomId, rate, cdn, url);
        } catch (Exception e) {
            log.debug("douyu relay url build failed, fallback direct: {}", url, e);
            return url;
        }
    }

    /** 中继续租:同房同线同档重签取流地址(含会话保鲜);空串=无流(下播/签名失败),调用方据此终止或换源。 */
    String getPlayUrlForRelay(String roomId, int rate, String cdn) {
        try {
            ensureFreshSession(false);
            PlayArgs args = getPlayArgs(roomId);
            if (args == null) {
                return "";
            }
            String url = getPlayUrl(roomId, args, rate, cdn).url();
            return url == null ? "" : url;
        } catch (Exception e) {
            log.warn("斗鱼中继重签失败: room={} rate={} cdn={} {}", roomId, rate, cdn, e.getMessage());
            return "";
        }
    }

    private PlayResult getPlayUrl(String id, PlayArgs args, int rate, String cdn) {
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
            return new PlayResult("", -1);
        }
        String rtmpUrl = data.get("rtmp_url").asText();
        String rtmpLive = data.get("rtmp_live").asText();
        return new PlayResult(combinePlayUrl(rtmpUrl, rtmpLive), data.path("rate").asInt(-1));
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
            headers.set(HttpHeaders.COOKIE, buildCookieHeader(effectiveDeviceId(), userCookie()));
        }
        return headers;
    }

    /** 用户在 web 管理端配置的 cookie,未配置返回空串。 */
    private String userCookie() {
        return settingRepository.findById(COOKIE_SETTING).map(Setting::getValue).orElse("");
    }

    /** Cookie 保存时间(秒);未记录返回 null(网页版 dy_auth 的 7 天时效无从起算,不猜)。 */
    private Long cookieSavedAt() {
        return settingRepository.findById(COOKIE_SAVED_AT_SETTING)
                .map(Setting::getValue)
                .map(value -> {
                    try {
                        long parsed = Long.parseLong(value.trim());
                        return parsed > 0 ? parsed : null;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    // ---------------------------------------------------------------------------
    // 会话判定与续期(pure_live 3.1.6 DouyuUtils 对应物)
    // ---------------------------------------------------------------------------

    /** Cookie 字段值(名字大小写不敏感),无该字段返回 null。 */
    static String cookieField(String cookie, String name) {
        if (cookie == null || cookie.isEmpty()) {
            return null;
        }
        String wanted = name.toLowerCase();
        for (String piece : cookie.split(";")) {
            int sep = piece.indexOf('=');
            if (sep <= 0) {
                continue;
            }
            if (piece.substring(0, sep).trim().toLowerCase().equals(wanted)) {
                return piece.substring(sep + 1).trim();
            }
        }
        return null;
    }

    /** 登录态 token:H5/app 为 acf_jwt_token/acf_auth(JWT 可读 exp),网页版为 dy_auth(不透明 token)——
     *  只认 H5 那套会把网页登录 Cookie 判成游客(pure_live 7d5187ca)。 */
    static String sessionToken(String cookie) {
        String token = cookieField(cookie, "acf_jwt_token");
        if (token == null || token.isEmpty()) {
            token = cookieField(cookie, "acf_auth");
        }
        if (token == null || token.isEmpty()) {
            token = cookieField(cookie, "dy_auth");
        }
        return token == null || token.isEmpty() ? null : token;
    }

    /** JWT payload 的 exp(秒);非 JWT 或解析失败返回 null——手贴 Cookie 的畸形 token 不该炸请求链路。 */
    static Long jwtExpirySeconds(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            String payload = parts[1].replace('-', '+').replace('_', '/');
            switch (payload.length() % 4) {
                case 2 -> payload += "==";
                case 3 -> payload += "=";
            }
            long exp = JSON.readTree(java.util.Base64.getDecoder().decode(payload)).path("exp").asLong(0);
            return exp > 0 ? exp : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 会话到期(秒):优先 JWT exp;网页版不透明 token 按保存时间 + 7 天折算;皆无返回 null(未知不猜,
     *  猜过期会否掉一个仍然可用的登录)。 */
    static Long sessionExpirySeconds(String cookie, Long savedAtSeconds) {
        String token = sessionToken(cookie);
        if (token == null) {
            return null;
        }
        Long exp = jwtExpirySeconds(token);
        if (exp != null) {
            return exp;
        }
        return savedAtSeconds == null ? null : savedAtSeconds + WEB_COOKIE_LIFETIME_SECONDS;
    }

    /** 是否应续期:无 token 恒应(仅含 LTP0/dy_did 的 passport 凭证串可借此续出完整会话);到期时间未知不续
     *  (猜过期会否掉仍可用的登录,交给播放失败强续路径);到期前 1 天进入续期窗口。 */
    static boolean shouldRefreshSession(String cookie, Long savedAtSeconds, long nowSeconds) {
        if (sessionToken(cookie) == null) {
            return true;
        }
        Long expiry = sessionExpirySeconds(cookie, savedAtSeconds);
        if (expiry == null) {
            return false;
        }
        return nowSeconds >= expiry - REFRESH_MARGIN_SECONDS;
    }

    /** 签名/加密/Cookie 共用 DID:账号 Cookie 自带 dy_did(登录所属设备)时以它为准,缺失才回退进程 DID。
     *  用另一个 DID 签名正是有效登录被按游客作答、边缘 403 的常见原因(pure_live 2e2cb0d4/c29a3b85)。 */
    static String effectiveDeviceId(String userCookie, String processDid) {
        String did = cookieField(userCookie, "dy_did");
        return did == null || did.isEmpty() ? processDid : did;
    }

    private String effectiveDeviceId() {
        return effectiveDeviceId(userCookie(), deviceId);
    }

    /** Set-Cookie 行合并进 Cookie:响应未提及的字段保留(整串替换会丢 LTP0,下次续期就没凭据了),
     *  属性名(Path/Expires 等)不是字段,空值=服务端清空该字段,须删除而非复活。 */
    static String mergeSetCookieLines(String cookie, List<String> setCookieLines) {
        Map<String, String> fields = new HashMap<>();
        for (String piece : cookie.split(";")) {
            int sep = piece.indexOf('=');
            if (sep <= 0) {
                continue;
            }
            fields.put(piece.substring(0, sep).trim(), piece.substring(sep + 1).trim());
        }
        for (String line : setCookieLines) {
            String pair = line.split(";", 2)[0].trim();
            int sep = pair.indexOf('=');
            if (sep <= 0) {
                continue;
            }
            String name = pair.substring(0, sep).trim();
            if (name.isEmpty() || SET_COOKIE_ATTRIBUTES.contains(name.toLowerCase())) {
                continue;
            }
            String value = pair.substring(sep + 1).trim();
            if (value.isEmpty()) {
                fields.remove(name);
            } else {
                fields.put(name, value);
            }
        }
        return fields.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; "));
    }

    /** 粘贴保护:新串只含 passport 凭证(无会话 token)而旧 Cookie 仍是登录态时,只取走 LTP0/dy_did 合并进
     *  旧值——passport 那串整串覆盖等于登出(pure_live c29a3b85 形态 2)。 */
    static String normalizePastedCookie(String oldCookie, String paste) {
        if (oldCookie == null || oldCookie.isBlank() || paste == null || paste.isBlank()) {
            return paste;
        }
        if (sessionToken(oldCookie) == null || sessionToken(paste) != null) {
            return paste;
        }
        List<String> carry = new ArrayList<>();
        String ltp0 = cookieField(paste, "LTP0");
        if (ltp0 != null && !ltp0.isEmpty()) {
            carry.add("LTP0=" + ltp0);
        }
        String did = cookieField(paste, "dy_did");
        if (did != null && !did.isEmpty()) {
            carry.add("dy_did=" + did);
        }
        return carry.isEmpty() ? paste : mergeSetCookieLines(oldCookie, carry);
    }

    /** passport safeAuth 续期:LTP0+dy_did 换新会话 Cookie 并落库;无凭证/不到窗口/没续到一律返回 null 不动存量。 */
    synchronized String refreshSession(boolean force) {
        String stored = userCookie();
        if (stored.isBlank()) {
            return null;
        }
        String ltp0 = cookieField(stored, "LTP0");
        String did = cookieField(stored, "dy_did");
        // 续期必须用登录所属设备,编造 DID 只会被 passport 拒绝(pure_live 7b917467)
        if (ltp0 == null || ltp0.isBlank() || did == null || did.isBlank()) {
            return null;
        }
        if (!force && !shouldRefreshSession(stored, cookieSavedAt(), System.currentTimeMillis() / 1000)) {
            return null;
        }
        try {
            String ms = String.valueOf(System.currentTimeMillis());
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
            headers.set(HttpHeaders.REFERER, "https://www.douyu.com/");
            headers.set(HttpHeaders.COOKIE, "dy_did=" + did + ";LTP0=" + ltp0);
            ResponseEntity<String> response = restTemplate.exchange(
                    PASSPORT_SAFE_AUTH_URL + "?client_id=1&t=" + ms + "&_=" + ms + "&callback=axiosJsonpCallback",
                    HttpMethod.GET, new HttpEntity<>(headers), String.class);
            List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
            // 没有任何 Set-Cookie = 没续到:不落库不推进时间戳,否则把将死的 Cookie 再装成 7 天新鲜
            if (setCookies == null || setCookies.isEmpty()) {
                log.info("斗鱼续期响应未带回 Set-Cookie,保留原 Cookie");
                return null;
            }
            String renewed = mergeSetCookieLines(stored, setCookies);
            // 续期结果丢了会话字段 = 降级为游客,宁可保留原 Cookie
            if (renewed.isBlank() || sessionToken(renewed) == null) {
                log.info("斗鱼续期结果缺少会话字段,保留原 Cookie");
                return null;
            }
            settingRepository.save(new Setting(COOKIE_SETTING, renewed));
            settingRepository.save(new Setting(COOKIE_SAVED_AT_SETTING, String.valueOf(System.currentTimeMillis() / 1000)));
            log.info("斗鱼 Cookie 已自动续期");
            return renewed;
        } catch (Exception e) {
            log.warn("斗鱼 Cookie 续期失败: {}", e.getMessage());
            return null;
        }
    }

    /** 播放前会话保鲜,永不抛:续期失败按原身份继续,不拖垮播放。force=播放失败路径强续一次。 */
    void ensureFreshSession(boolean force) {
        try {
            refreshSession(force);
        } catch (Exception e) {
            log.warn("斗鱼会话续期异常: {}", e.getMessage());
        }
    }

    /** 请求 Cookie:dy_did/acf_did 恒为签名 DID;LTP0 是 passport 续期密钥不进播放请求头(发了会被边缘
     *  风控 403,pure_live c29a3b85 形态 3);属性碎片与非字段名剔除。 */
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
            if (lower.equals("dy_did") || lower.equals("acf_did") || lower.equals("ltp0")) {
                continue;
            }
            // 误贴 Set-Cookie 行时带进的属性名(Path=/ 等)不是字段,一并滤掉
            if (SET_COOKIE_ATTRIBUTES.contains(lower) || !COOKIE_NAME.matcher(name).matches()) {
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
            return new PlayArgs(buildSignedForm(roomId, key, effectiveDeviceId(), System.currentTimeMillis() / 1000),
                    "&hevc=0&fa=0&ive=0&ver=Douyu_new&iar=0", true);
        } catch (Exception e) {
            log.warn("斗鱼本地签名失败,回退外部签名服务: {}", e.getMessage());
            return legacySign(roomId);
        }
    }

    private synchronized JsonNode fetchEncryptionKey() throws IOException {
        long now = System.currentTimeMillis() / 1000;
        // 描述符签发给某台设备并校验签名来自同一台:登录 dy_did 变化 = 换设备,旧描述符必须作废
        String did = effectiveDeviceId();
        if (encryptionKey != null && did.equals(encryptionKeyDeviceId)
                && now - encryptionKeyFetchedAt < ENC_KEY_CACHE_SECONDS
                && isEncryptionKeyUsable(encryptionKey, now)) {
            return encryptionKey;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
        headers.set(HttpHeaders.REFERER, "https://www.douyu.com/");
        headers.set(HttpHeaders.ORIGIN, "https://www.douyu.com");
        headers.set(HttpHeaders.COOKIE, buildCookieHeader(did, userCookie()));
        String body = restTemplate.exchange(GET_ENCRYPTION_URL + "?did=" + did, HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody();
        JsonNode data = objectMapper.readTree(body).path("data");
        if (!isEncryptionKeyUsable(data, now)) {
            throw new IllegalStateException("encryption descriptor incomplete or expired");
        }
        encryptionKey = data;
        encryptionKeyFetchedAt = now;
        encryptionKeyDeviceId = did;
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
