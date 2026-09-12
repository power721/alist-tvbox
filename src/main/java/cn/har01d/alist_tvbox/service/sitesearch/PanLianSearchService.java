package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 盘链搜索源(atv-spiders/py/盘链.py 的 Java 移植,追剧搜索源之一):盘链是需登录的
 * 网盘分享聚合站,2026-09 改版后为纯 JSON API —— 搜索 {@code /api/videos}、详情
 * {@code /api/videos/{id}}(links 只含 link_id/pan_type/title,无真实链)、两步解锁
 * {@code POST /api/videos/link-ticket} + {@code GET /api/videos/link-open/{id}?t=}
 * 才拿到分享 URL 与提取码(token/go.php 302 老链路已废弃)。
 * links 解出的 magnet/ed2k 一并产出离线候选条目,供追剧磁力兜底在 fillPool 的
 * NON_PAN 收割 —— 兜底未开时由定向集闸门统一剔除。
 *
 * <p><b>配额</b>:站点按天计解锁配额(基础 30 + 每日签到 +20),本源做三层防御:
 * 单次搜索解锁预算上限、解锁结果短缓存(同一检查周期重复搜索不重扣)、配额用尽
 * 即停。每日签到幂等,会话建立后每天首个搜索自动触发一次。
 *
 * <p><b>凭证必须用户自配</b>(Setting {@code panlian_username}/{@code panlian_password}
 * 或直接 {@code panlian_cookie},站点可 {@code panlian_host} 覆盖)——不内置任何共享账号;
 * 未配置时本源静默关闭。账号密码登录:表单 POST {@code /api/auth/login},Cookie 内存缓存,
 * 失效自动重登;连续失败 5 分钟冷却防撞墙。
 */
@Slf4j
@Service
public class PanLianSearchService {
    public static final String HOST_SETTING = "panlian_host";
    public static final String USERNAME_SETTING = "panlian_username";
    public static final String PASSWORD_SETTING = "panlian_password";
    public static final String COOKIE_SETTING = "panlian_cookie";

    private static final String DEFAULT_HOST = "https://www.xn--vzy265d.cc";
    private static final int TIMEOUT_SECONDS = 10;
    /** 每次搜索最多取多少个条目的详情(控制站点压力) */
    private static final int MAX_DETAIL_ITEMS = 3;
    /**
     * 单次搜索解锁预算上限:实测解锁按视频/天计且宽松,但 py 参考实现明确按次扣配额,
     * 防计费口径收紧后一次搜索烧穿全天额度(30+签到 20)。
     */
    private static final int MAX_UNLOCKS_PER_SEARCH = 12;
    /** 解锁结果短缓存:同一检查周期内重复搜索同一剧不重复走两步解锁(省配额) */
    private static final long UNLOCK_CACHE_TTL_MS = 10 * 60_000L;
    private static final int UNLOCK_CACHE_MAX = 256;
    /** 登录失败冷却:防止账号错误/站点故障时每轮巡检都撞登录接口 */
    private static final long LOGIN_COOLDOWN_MS = 5 * 60_000L;
    /** 分享 URL 尾部内联提取码噪声(py _normalize_pan_url) */
    private static final Pattern EXTRACT_CODE_NOISE = Pattern.compile("(?i)([?？]?\\s*(提取码|访问码|密码)[:：]\\s*[a-z0-9]{4,8})+$");
    /** link_id 进 JSON 体前校验(防注入,对齐 py int(link_id)) */
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern LINK_TITLE_INTRO = Pattern.compile("\\s*[·•]\\s*介绍[:：].*$");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private final SettingRepository settingRepository;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient();
    /** 登录态 Cookie:配置 Cookie 直接用,账号密码登录后内存缓存 */
    private volatile String sessionCookie = "";
    private final LoginCooldown loginCooldown = new LoginCooldown();
    private volatile boolean warnedNoCredentials;
    /** 解锁结果缓存:link_id → 折好提取码的最终分享链 */
    private final ConcurrentHashMap<String, CachedUnlock> unlockCache = new ConcurrentHashMap<>();
    /** 每日签到防重(站点幂等,重复调用不加倍;本地再挡一层省请求) */
    private volatile String lastCheckinDay = "";

    private record CachedUnlock(String url, long expiresAt) {
    }

    public PanLianSearchService(SettingRepository settingRepository, ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.objectMapper = objectMapper;
    }

    private record Config(String host, String username, String password, String cookie) implements SiteCredentials {
    }

    /** 解锁结果:url 空=失败;charged=真实走了网络(扣搜索预算);quotaExhausted=配额用尽停止后续解锁。 */
    private record UnlockOutcome(String url, boolean charged, boolean quotaExhausted) {
        static final UnlockOutcome FAILED = new UnlockOutcome("", false, false);

        static UnlockOutcome ok(String url) {
            return new UnlockOutcome(url, true, false);
        }

        static UnlockOutcome quota() {
            return new UnlockOutcome("", false, true);
        }
    }

    public List<Message> search(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return List.of();
        }
        Config config = loadConfig();
        if (!config.hasCredentials()) {
            if (!warnedNoCredentials) {
                warnedNoCredentials = true;
                log.info("盘链搜索源未启用:未配置账号(Setting {}+{} 或 {})", USERNAME_SETTING, PASSWORD_SETTING, COOKIE_SETTING);
            }
            return List.of();
        }
        try {
            if (!ensureSession(config)) {
                return List.of();
            }
            checkinOncePerDay(config);
            JsonNode payload = getJson(config, "/api/videos", Map.of(
                    "search", keyword.trim(),
                    "page", "1",
                    "page_size", "30"));
            if (isLoginRequired(payload)) {
                sessionCookie = "";
                if (!ensureSession(config)) {
                    return List.of();
                }
                payload = getJson(config, "/api/videos", Map.of(
                        "search", keyword.trim(),
                        "page", "1",
                        "page_size", "30"));
                if (isLoginRequired(payload)) {
                    return List.of();
                }
            }
            if (!payload.path("success").asBoolean(false)) {
                return List.of();
            }

            List<Message> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int details = 0;
            int unlockBudget = MAX_UNLOCKS_PER_SEARCH;
            boolean quotaExhausted = false;
            for (JsonNode item : payload.path("data").path("list")) {
                if (details >= MAX_DETAIL_ITEMS || quotaExhausted) {
                    break;
                }
                String vodId = item.path("vod_id").asText(item.path("id").asText("")).trim();
                String vodName = item.path("vod_name").asText(item.path("title").asText("")).trim();
                if (vodId.isEmpty() || vodName.isEmpty()) {
                    continue;
                }
                String remarks = item.path("vod_remarks").asText(item.path("remarks").asText("")).trim();
                JsonNode detail = getJson(config, "/api/videos/" + URLEncoder.encode(vodId, StandardCharsets.UTF_8), Map.of());
                details++;
                if (isLoginRequired(detail)) {
                    sessionCookie = "";
                    if (ensureSession(config)) {
                        detail = getJson(config, "/api/videos/" + URLEncoder.encode(vodId, StandardCharsets.UTF_8), Map.of());
                    }
                }
                if (!detail.path("success").asBoolean(false)) {
                    continue;
                }
                for (JsonNode link : detail.path("data").path("links")) {
                    if (quotaExhausted || unlockBudget <= 0) {
                        break;
                    }
                    String linkId = link.path("id").asText("").trim();
                    if (linkId.isEmpty() || skipPanType(link)) {
                        continue;
                    }
                    UnlockOutcome outcome = unlockLink(config, linkId);
                    if (outcome.quotaExhausted()) {
                        quotaExhausted = true;
                        log.info("panlian 解锁配额已用尽,本次搜索停止后续解锁");
                        break;
                    }
                    if (outcome.charged()) {
                        unlockBudget--;
                    }
                    addMessage(result, seen, vodName, remarks,
                            cleanLinkTitle(link.path("title").asText("")), outcome.url());
                }
            }
            log.info("PanLian search {} get {} results", keyword, result.size());
            return result;
        } catch (Exception e) {
            // 上抛而非吞掉空表:聚合层 searchAsync 捕获后记 SearchSourceThrottle 退避并返空;
            // 吞掉则死站被 recordSuccess 清零连击,退避闸门对站点源永不生效
            log.warn("panlian search [{}] failed: {}", keyword, e.getMessage());
            throw e instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(e);
        }
    }

    /** pan_type 预过滤:挂载口径外的已知盘(微云/蓝奏/其他)不值得花解锁配额;未知 slug 放行走 URL 判定。 */
    private static boolean skipPanType(JsonNode link) {
        String panType = link.path("pan_type").asText("").trim().toLowerCase();
        if (link.path("is_magnet").asBoolean(false) && !"magnet".equals(panType) && !"ed2k".equals(panType)) {
            return false;
        }
        return "weiyun".equals(panType) || "lanzou".equals(panType) || "others".equals(panType);
    }

    private static void addMessage(List<Message> result, Set<String> seen, String vodName, String remarks, String title, String url) {
        if (StringUtils.isBlank(url)) {
            return;
        }
        String type = Message.parseType(url);
        if (type == null || !SiteSearchSupport.isNumeric(type)) {
            // 网盘只留可挂载分享;magnet/ed2k(解锁 302/直出到磁力)作离线候选产出,
            // 由追剧定向集闸门裁决 —— 兜底未开时在 searchAllSources 统一剔除
            if (StringUtils.startsWithIgnoreCase(url, "magnet:")
                    || StringUtils.startsWithIgnoreCase(url, "ed2k:")) {
                Message message = offlineMessage(vodName, url, title);
                if (seen.add(message.getLink())) {
                    result.add(message);
                }
            }
            return;
        }
        Message message = new Message();
        message.setType(type);
        message.setLink(url);
        message.setName(vodName);
        message.setChannel("盘链");
        message.setContent((vodName + " " + StringUtils.defaultString(remarks)).trim());
        if (seen.add(message.getLink())) {
            result.add(message);
        }
    }

    /** 离线候选条目(magnet/ed2k):content 放清洗后的资源标题(常带集数),磁力兜底的标题门禁消费。 */
    private static Message offlineMessage(String vodName, String url, String title) {
        Message message = new Message();
        message.setType(StringUtils.startsWithIgnoreCase(url, "ed2k:") ? "ed2k" : "magnet");
        message.setLink(url);
        message.setName(vodName);
        message.setChannel("盘链");
        message.setContent(title.isEmpty() ? vodName : title);
        return message;
    }

    /** 资源标题清洗(py _clean_link_title):剥「介绍:」尾巴与 HTML 标签。 */
    static String cleanLinkTitle(String value) {
        String text = HTML_TAG.matcher(LINK_TITLE_INTRO.matcher(StringUtils.trimToEmpty(value)).replaceAll("")).replaceAll("");
        return text.trim();
    }

    /** 分享 URL 清洗:去尾部 # 与内联提取码噪声(py _normalize_pan_url)。 */
    static String cleanShareUrl(String raw) {
        String clean = StringUtils.stripEnd(StringUtils.trimToEmpty(raw), "#").trim();
        return EXTRACT_CODE_NOISE.matcher(clean).replaceAll("").trim();
    }

    /** 结构化提取码折进 URL 参数:百度/迅雷/123 用 pwd=,115 用 password=(已有参数不重复折)。 */
    static String foldPassword(String url, String password) {
        if (StringUtils.isBlank(url) || StringUtils.isBlank(password)) {
            return url;
        }
        String type = Message.parseType(url);
        String param = switch (type == null ? "" : type) {
            case "10", "2", "3" -> "pwd="; // baidu / xunlei / 123
            case "8" -> "password="; // 115
            default -> null;
        };
        return param == null ? url
                : SiteSearchSupport.appendPasswordParam(url, URLEncoder.encode(password, StandardCharsets.UTF_8), param);
    }

    /**
     * 两步解锁(py _unlock_link):POST link-ticket 换 90 秒 ticket,GET link-open 换真实
     * 分享链 + 提取码;登录失效重登后整链路重试一次。结果短缓存,命中不消耗预算。
     */
    private UnlockOutcome unlockLink(Config config, String linkId) throws IOException {
        if (!DIGITS.matcher(linkId).matches()) {
            return UnlockOutcome.FAILED;
        }
        CachedUnlock cached = unlockCache.get(linkId);
        if (cached != null) {
            if (System.currentTimeMillis() < cached.expiresAt()) {
                return new UnlockOutcome(cached.url(), false, false);
            }
            unlockCache.remove(linkId, cached);
        }
        String body = "{\"link_id\":" + linkId + "}";
        JsonNode ticketPayload = postJson(config, "/api/videos/link-ticket", body);
        if (isLoginRequired(ticketPayload)) {
            sessionCookie = "";
            if (ensureSession(config)) {
                ticketPayload = postJson(config, "/api/videos/link-ticket", body);
            }
        }
        String ticket = ticketPayload.path("data").path("ticket").asText("").trim();
        if (!ticketPayload.path("success").asBoolean(false) || ticket.isEmpty()) {
            return quotaOrFailed(ticketPayload);
        }
        JsonNode openPayload = getJson(config, "/api/videos/link-open/" + linkId, Map.of("t", ticket));
        if (isLoginRequired(openPayload)) {
            sessionCookie = "";
            if (ensureSession(config)) {
                ticketPayload = postJson(config, "/api/videos/link-ticket", body);
                ticket = ticketPayload.path("data").path("ticket").asText("").trim();
                if (!ticketPayload.path("success").asBoolean(false) || ticket.isEmpty()) {
                    return quotaOrFailed(ticketPayload);
                }
                openPayload = getJson(config, "/api/videos/link-open/" + linkId, Map.of("t", ticket));
            }
        }
        if (!openPayload.path("success").asBoolean(false)) {
            return quotaOrFailed(openPayload);
        }
        JsonNode data = openPayload.path("data");
        String rawUrl = data.path("url").asText("").trim();
        if (rawUrl.isEmpty()) {
            return UnlockOutcome.FAILED;
        }
        String code = data.path("code").asText(data.path("password").asText("")).trim();
        String url = foldPassword(cleanShareUrl(rawUrl), code);
        if (url.isEmpty()) {
            return UnlockOutcome.FAILED;
        }
        cacheUnlock(linkId, url);
        return UnlockOutcome.ok(url);
    }

    private void cacheUnlock(String linkId, String url) {
        if (unlockCache.size() >= UNLOCK_CACHE_MAX) {
            long now = System.currentTimeMillis();
            unlockCache.values().removeIf(entry -> now >= entry.expiresAt());
            if (unlockCache.size() >= UNLOCK_CACHE_MAX) {
                unlockCache.clear();
            }
        }
        unlockCache.put(linkId, new CachedUnlock(url, System.currentTimeMillis() + UNLOCK_CACHE_TTL_MS));
    }

    private static UnlockOutcome quotaOrFailed(JsonNode payload) {
        String text = payload.path("message").asText(payload.path("msg").asText(""));
        if (text.contains("配额") || text.contains("次数") || text.contains("额度")
                || StringUtils.containsIgnoreCase(text, "quota")) {
            return UnlockOutcome.quota();
        }
        return UnlockOutcome.FAILED;
    }

    /**
     * 每日签到:基础配额 30 之外 +20(2026-09-12 实测幂等,重复调用不加倍)。
     * 每天首个搜索触发一次,失败不记日次日重试;签到不成分不影响搜索主链路。
     */
    private void checkinOncePerDay(Config config) {
        String today = LocalDate.now().toString();
        if (today.equals(lastCheckinDay)) {
            return;
        }
        try {
            JsonNode payload = postJson(config, "/api/tasks/checkin", "{}");
            if (payload.path("success").asBoolean(false)) {
                lastCheckinDay = today;
                JsonNode quota = payload.path("data").path("quota");
                if (!quota.isMissingNode()) {
                    log.info("盘链每日签到完成:解锁配额 remaining={}/{}",
                            quota.path("remaining").asText("?"), quota.path("limit").asText("?"));
                } else {
                    log.info("盘链每日签到完成:{}", payload.path("data").path("message").asText(""));
                }
            } else {
                log.debug("panlian checkin skipped: {}", payload.path("message").asText(payload.path("msg").asText("")));
            }
        } catch (Exception e) {
            log.debug("panlian checkin failed: {}", e.getMessage());
        }
    }

    /** error_type=ADMIN_AUTH_REQUIRED,或 success=false 且报文含"登录/登陆/未授权"(py _is_login_required)。 */
    static boolean isLoginRequired(JsonNode payload) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return false;
        }
        if ("ADMIN_AUTH_REQUIRED".equals(payload.path("error_type").asText(""))) {
            return true;
        }
        if (payload.has("success") && !payload.path("success").asBoolean(true)) {
            String text = payload.path("message").asText("") + payload.path("msg").asText("");
            return text.contains("登录") || text.contains("登陆") || text.contains("未授权");
        }
        return false;
    }

    private boolean ensureSession(Config config) {
        if (StringUtils.isNotBlank(sessionCookie)) {
            return true;
        }
        if (StringUtils.isNotBlank(config.cookie())) {
            sessionCookie = config.cookie().trim();
            return true;
        }
        return login(config);
    }

    private synchronized boolean login(Config config) {
        if (loginCooldown.blocked() || !config.canLogin()) {
            return false;
        }
        try {
            RequestBody body = new FormBody.Builder()
                    .add("username", config.username())
                    .add("password", config.password())
                    .add("remember", "1")
                    .build();
            Resp resp = http(new Request.Builder()
                    .url(config.host() + "/api/auth/login")
                    .header("User-Agent", userAgent())
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-TW,zh;q=0.9,zh-CN;q=0.8,en;q=0.7")
                    .header("Origin", config.host())
                    .header("Referer", config.host() + "/login")
                    .post(body)
                    .build());
            if (resp.code() != 200) {
                return loginFailed("login api http " + resp.code());
            }
            JsonNode payload = objectMapper.readTree(StringUtils.defaultString(resp.body()));
            if (!payload.path("success").asBoolean(false)) {
                String reason = payload.path("message").asText(payload.path("msg").asText(""));
                return loginFailed("账号密码被拒绝:" + reason);
            }
            String cookie = SiteSearchSupport.joinCookies(SiteSearchSupport.parseCookies(resp.setCookies()));
            if (cookie.isBlank()) {
                return loginFailed("登录成功但未取到 Cookie");
            }
            sessionCookie = cookie;
            log.info("盘链登录成功(username={})", config.username());
            return true;
        } catch (Exception e) {
            return loginFailed(e.getMessage());
        }
    }

    private boolean loginFailed(String reason) {
        sessionCookie = "";
        return loginCooldown.fail("盘链", reason, LOGIN_COOLDOWN_MS);
    }

    private JsonNode getJson(Config config, String path, Map<String, String> params) throws IOException {
        StringBuilder url = new StringBuilder(config.host()).append(path);
        String sep = "?";
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append(sep).append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=').append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            sep = "&";
        }
        return parseJson(http(apiBuilder(config, url.toString()).get().build()));
    }

    private JsonNode postJson(Config config, String path, String jsonBody) throws IOException {
        Request request = apiBuilder(config, config.host() + path)
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();
        return parseJson(http(request));
    }

    /** API 公共头:Origin/XHR/Referer 缺一会被站点 CSRF 口径拒成 ADMIN_AUTH_REQUIRED(2026-09-12 实测)。 */
    private Request.Builder apiBuilder(Config config, String url) {
        return new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent())
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-TW,zh;q=0.9,zh-CN;q=0.8,en;q=0.7")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", config.host())
                .header("Referer", config.host() + "/")
                .header("Cookie", StringUtils.defaultString(sessionCookie));
    }

    private JsonNode parseJson(Resp resp) {
        if (resp.code() != 200 || StringUtils.isBlank(resp.body())) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(resp.body());
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private Config loadConfig() {
        return new Config(
                normalizeHost(SiteSearchSupport.setting(settingRepository, HOST_SETTING)),
                SiteSearchSupport.setting(settingRepository, USERNAME_SETTING).trim(),
                SiteSearchSupport.setting(settingRepository, PASSWORD_SETTING),
                SiteSearchSupport.setting(settingRepository, COOKIE_SETTING).trim());
    }

    /** 站点地址归一化:空/非法回落内置域名(内核见 {@link SiteSearchSupport#normalizeHost})。 */
    static String normalizeHost(String value) {
        return SiteSearchSupport.normalizeHost(value, DEFAULT_HOST);
    }

    private String userAgent() {
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36";
    }

    protected Resp http(Request request) throws IOException {
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            return new Resp(response.code(), response.headers("Set-Cookie"), body);
        }
    }
}
