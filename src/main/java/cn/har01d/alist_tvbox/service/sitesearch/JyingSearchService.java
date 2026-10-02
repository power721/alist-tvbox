package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.dto.SiteCredentialCheckRequest;
import cn.har01d.alist_tvbox.dto.SiteCredentialCheckResult;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 聚影搜索源(atv-spiders/py/聚影.py 的 Java 移植,追剧搜索源之一):聚影
 * (www.jying.top)是 Vue SPA + Django JSON API 的网盘资源收集站,TMDB 规范化片库。
 * 搜索 {@code GET /api/app/movies/?q=} 与详情匿名可用;资源列表
 * {@code GET /api/app/movie/{id}/resources/} 与解锁 {@code POST /api/app/resource/{rid}/access/}
 * (access_ticket 换真链 target + 提取码 access_code,提取码折进 URL 参数)需登录 ——
 * 认证是 {@code X-App-User-Token} 请求头(约 7 天有效,服务端轮换时经
 * {@code x-refreshed-token} 响应头下发,顺手捕获更新)。登录 {@code POST /api/app/login/}
 * JSON 账号密码,无验证码无 CSRF。
 *
 * <p><b>凭证必须用户自配</b>(Setting {@code jying_username}/{@code jying_password} 账号密码
 * 形态 —— token 过期自动重登;或 {@code jying_token} 直接贴浏览器复制的
 * x-app-user-token;站点 {@code jying_host} 可覆盖),未配置时本源静默关闭。账号密码形态
 * 登录所得 token 落库 Setting {@code jying_session},重启播种免重登。
 *
 * <p>站方对逐条解锁限流(连续 POST access 超过 12 条触发「资源访问过于频繁」),单次
 * 搜索解锁预算 12 条 + 解锁结果 10 分钟短缓存(同检查周期重复搜索不重扣);磁力/ed2k
 * 资源仅订阅磁力兜底生效时解锁产出离线候选条目,供追剧磁力兜底在 fillPool 的
 * NON_PAN 收割 —— 兜底未开时由定向集闸门统一剔除。
 *
 * <p><b>每日签到</b>(2026-10-02 实测契约):{@code POST /api/app/checkin/do/} +5 积分,
 * 签到后可在「签到幸运奖池」每日免费领一次({@code POST /checkin/bonus-pool/draw/}
 * {@code {"pool_id":N}},先 {@code GET /checkin/bonus-pool/} 看 can_draw)。两接口均
 * 只认 x-app-user-token,<b>不强制 CSRF</b>(浏览器抓包带的 csrftoken 头/Cookie 可省,
 * 实测不带放行);签到与抽奖均幂等(重复报「今日已签到/已恢复今日抽奖结果」,不发分),
 * 每日 06:13 定时执行(与盘链/蜗牛等四站签到错峰),搜索路径不签保持轻量。
 */
@Slf4j
@Service
public class JyingSearchService {
    public static final String HOST_SETTING = "jying_host";
    public static final String USERNAME_SETTING = "jying_username";
    public static final String PASSWORD_SETTING = "jying_password";
    /** Token 形态:浏览器登录后复制的 x-app-user-token(约 7 天,过期需手动更新)。 */
    public static final String TOKEN_SETTING = "jying_token";
    /** 账号密码形态登录所得 token 落库,重启播种免重登。 */
    public static final String SESSION_SETTING = "jying_session";

    private static final String DEFAULT_HOST = "https://www.jying.top";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36";
    private static final int TIMEOUT_SECONDS = 15;
    /** 每次搜索最多取多少个条目的资源列表(控制站点压力) */
    private static final int MAX_DETAIL_ITEMS = 3;
    /**
     * 单次搜索解锁预算上限:站方对逐条解锁限流(连续 POST access 超过 12 条触发
     * 「资源访问过于频繁」),一次搜索烧穿会被整段拒绝。
     */
    private static final int MAX_UNLOCKS_PER_SEARCH = 12;
    /** 解锁结果短缓存:同一检查周期内重复搜索同一剧不重复解锁(省配额)。 */
    private static final long UNLOCK_CACHE_TTL_MS = 10 * 60_000L;
    private static final int UNLOCK_CACHE_MAX = 256;
    /** 登录失败冷却:防凭证错误时每轮巡检都撞登录接口(py 600s)。 */
    private static final long LOGIN_COOLDOWN_MS = 10 * 60_000L;
    private static final int LIST_PAGE_SIZE = 30;
    private static final int RESOURCE_PAGE_SIZE = 100;
    private static final int MAX_RESOURCE_PAGES = 3;
    /** 选集/资源标题里的播放串分隔符与换行,必须替换(蜗牛/观影同款处理)。 */
    private static final Pattern LABEL_NOISE = Pattern.compile("[#$\\r\\n]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final SettingRepository settingRepository;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final LoginCooldown loginCooldown = new LoginCooldown();
    /** 每日签到日记账(内存态,重启当日重撞一次由站点幂等拒绝,无副作用)。 */
    private volatile String lastCheckinDay = "";
    private volatile boolean warnedNoCredentials;
    /** 最近一次登录失败原因(手动检查账号密码时回显,登录成功清空)。 */
    private volatile String lastLoginError = "";
    /** 内存态 token;播种只在进程生命周期发生一次,被站点拒收后清空的不回灌旧值。 */
    private volatile String token = "";
    private volatile boolean seededToken;
    /** 解锁结果缓存:rid → 折好提取码的最终链接。 */
    private final ConcurrentHashMap<String, CachedUnlock> unlockCache = new ConcurrentHashMap<>();

    private record CachedUnlock(String url, long expiresAt) {
    }

    private record Config(String host, String username, String password, String token) implements SiteCredentials {
        @Override
        public String cookie() {
            return token;
        }
    }

    /** 解锁结果:url 空=失败;authFailed=登录失效(可重登续链);throttled=站点限流停止后续解锁。 */
    private record UnlockResult(String url, boolean authFailed, boolean throttled) {
        static final UnlockResult FAILED = new UnlockResult("", false, false);

        static UnlockResult ok(String url) {
            return new UnlockResult(url, false, false);
        }
    }

    public JyingSearchService(SettingRepository settingRepository, ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.objectMapper = objectMapper;
    }

    public List<Message> search(String keyword, boolean offlineIncluded) {
        if (StringUtils.isBlank(keyword)) {
            return List.of();
        }
        Config config = loadConfig();
        if (!config.hasCredentials()) {
            if (!warnedNoCredentials) {
                warnedNoCredentials = true;
                log.info("聚影搜索源未启用:未配置账号(Setting {}+{} 或 {})", USERNAME_SETTING, PASSWORD_SETTING, TOKEN_SETTING);
            }
            return List.of();
        }
        try {
            if (!ensureToken(config)) {
                return List.of();
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("page", "1");
            params.put("page_size", String.valueOf(LIST_PAGE_SIZE));
            params.put("count", "1");
            params.put("q", keyword.trim());
            JsonNode payload = getJson(config, "/api/app/movies/", params, "");
            List<Message> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int details = 0;
            int budget = MAX_UNLOCKS_PER_SEARCH;
            outer:
            for (JsonNode item : payload.path("results")) {
                if (details >= MAX_DETAIL_ITEMS) {
                    break;
                }
                if (item.path("is_private").asBoolean(false) || item.path("is_adult").asBoolean(false)
                        || item.path("resource_count").asInt(0) <= 0) {
                    continue;
                }
                String movieId = item.path("id").asText("").trim();
                String vodName = item.path("title").asText("").trim();
                if (movieId.isEmpty() || !DIGITS.matcher(movieId).matches() || vodName.isEmpty()) {
                    continue;
                }
                details++;
                List<JsonNode> resources = fetchResources(config, movieId);
                for (JsonNode resource : resources) {
                    if (budget <= 0) {
                        break outer;
                    }
                    String rid = resource.path("id").asText("").trim();
                    String ticket = resource.path("access_ticket").asText("").trim();
                    if (rid.isEmpty() || !resource.path("link_exposed").asBoolean(false) || ticket.isEmpty()) {
                        continue;
                    }
                    String resourceType = resource.path("resource_type").asText("").trim();
                    // 磁力/ed2k 资源仅磁力兜底生效时解锁:未开时产出会被定向集闸门裁掉,不值花解锁配额
                    if (("MagnetLink".equals(resourceType) || "ed2k".equals(resourceType)) && !offlineIncluded) {
                        continue;
                    }
                    UnlockResult outcome = unlockResource(config, rid, ticket);
                    if (outcome.authFailed()) {
                        // 登录失效:重登后重读资源列表换新 ticket 再试一次(凭证过期≠账号故障)
                        if (!relogin(config)) {
                            break outer;
                        }
                        String freshTicket = findTicket(fetchResources(config, movieId), rid);
                        if (!freshTicket.isEmpty()) {
                            outcome = unlockResource(config, rid, freshTicket);
                        }
                    }
                    if (outcome.throttled()) {
                        log.info("聚影解锁被限流(资源访问过于频繁),本轮停止解锁:{}", keyword);
                        break outer;
                    }
                    if (outcome.url().isEmpty()) {
                        continue;
                    }
                    budget--;
                    addMessage(result, seen, vodName, resourceLabel(resource), outcome.url());
                }
            }
            log.info("Jying search {} get {} results", keyword, result.size());
            return result;
        } catch (Exception e) {
            // 上抛而非吞掉空表:聚合层 searchAsync 捕获后记 SearchSourceThrottle 退避并返空;
            // 吞掉则死站被 recordSuccess 清零连击,退避闸门对站点源永不生效
            log.warn("jying search [{}] failed: {}", keyword, e.getMessage());
            throw e instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(e);
        }
    }

    /** 磁力/ed2k 资源类型 slug(站方 DISK_META 口径)。 */
    static boolean isOfflineResourceType(String resourceType) {
        return "MagnetLink".equals(resourceType) || "ed2k".equals(resourceType);
    }

    /** 资源标题(py _episode_label):title/description 兜底 + 体积,剥播放串分隔符,截 70。 */
    static String resourceLabel(JsonNode resource) {
        String title = StringUtils.trimToEmpty(resource.path("title").asText(""));
        if (title.isEmpty()) {
            title = StringUtils.trimToEmpty(resource.path("resource_description").asText(""));
        }
        if (title.isEmpty()) {
            title = StringUtils.trimToEmpty(resource.path("description").asText(""));
        }
        String size = StringUtils.trimToEmpty(resource.path("file_size").asText(""));
        String label = title;
        if (!size.isEmpty() && !title.contains(size)) {
            label = title.isEmpty() ? size : title + "·" + size;
        }
        label = LABEL_NOISE.matcher(label).replaceAll(" ").trim();
        return StringUtils.abbreviate(label, 70);
    }

    private static void addMessage(List<Message> result, Set<String> seen, String vodName, String label, String url) {
        if (StringUtils.isBlank(url)) {
            return;
        }
        String type = Message.parseType(url);
        if (type == null || !SiteSearchSupport.isNumeric(type)) {
            // 网盘只留可挂载分享;magnet/ed2k(解锁后直出到磁力)作离线候选产出,
            // 由追剧定向集闸门裁决 —— 兜底未开时在 searchAllSources 统一剔除
            if (StringUtils.startsWithIgnoreCase(url, "magnet:")
                    || StringUtils.startsWithIgnoreCase(url, "ed2k:")) {
                Message message = new Message();
                message.setType(StringUtils.startsWithIgnoreCase(url, "ed2k:") ? "ed2k" : "magnet");
                message.setLink(url);
                message.setName(vodName);
                message.setChannel("聚影");
                message.setContent(label.isEmpty() ? vodName : label);
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
        message.setChannel("聚影");
        message.setContent((vodName + " " + StringUtils.defaultString(label)).trim());
        if (seen.add(message.getLink())) {
            result.add(message);
        }
    }

    /** 结构化提取码折进 URL 参数:115 用 password=(AList 驱动口径),其余 pwd=(py 一律 pwd=)。 */
    static String foldPassword(String url, String password) {
        if (StringUtils.isBlank(url) || StringUtils.isBlank(password)) {
            return url;
        }
        String param = "8".equals(Message.parseType(url)) ? "password=" : "pwd=";
        return SiteSearchSupport.appendPasswordParam(url,
                URLEncoder.encode(password, StandardCharsets.UTF_8), param);
    }

    // ---------- 资源列表与解锁 ----------

    /**
     * 翻页拉全资源列表(py _fetch_resources,最多 {@value #MAX_RESOURCE_PAGES} 页);
     * 登录失效自动重登后重试一次。
     */
    private List<JsonNode> fetchResources(Config config, String movieId) throws IOException {
        List<JsonNode> resources = new ArrayList<>();
        for (int page = 1; page <= MAX_RESOURCE_PAGES; page++) {
            JsonNode payload = authedGet(config, "/api/app/movie/" + movieId + "/resources/",
                    Map.of("page", String.valueOf(page), "page_size", String.valueOf(RESOURCE_PAGE_SIZE)));
            JsonNode items = payload.path("resources");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            items.forEach(resources::add);
            if (!payload.path("has_more").asBoolean(false)) {
                break;
            }
        }
        return resources;
    }

    private static String findTicket(List<JsonNode> resources, String rid) {
        for (JsonNode resource : resources) {
            if (rid.equals(resource.path("id").asText("").trim())) {
                return resource.path("access_ticket").asText("").trim();
            }
        }
        return "";
    }

    /**
     * 解锁(py _unlock):POST access 用 ticket 换真链,提取码折进 URL 参数;结果短缓存,
     * 命中不消耗预算。限流报文(「资源访问过于频繁」)整段停止后续解锁。
     */
    private UnlockResult unlockResource(Config config, String rid, String ticket) throws IOException {
        if (!DIGITS.matcher(rid).matches() || StringUtils.isBlank(ticket)) {
            return UnlockResult.FAILED;
        }
        CachedUnlock cached = unlockCache.get(rid);
        if (cached != null) {
            if (System.currentTimeMillis() < cached.expiresAt()) {
                return UnlockResult.ok(cached.url());
            }
            unlockCache.remove(rid, cached);
        }
        JsonNode payload = postJson(config, "/api/app/resource/" + rid + "/access/",
                "{\"access_ticket\":\"" + ticket + "\"}", token);
        if (isAuthError(payload)) {
            return new UnlockResult("", true, false);
        }
        String message = payload.path("message").asText("");
        if (message.contains("频繁")) {
            return new UnlockResult("", false, true);
        }
        String target = payload.path("target").asText("").trim();
        if (target.isEmpty()) {
            return UnlockResult.FAILED;
        }
        String url = foldPassword(target, payload.path("access_code").asText("").trim());
        if (url.isEmpty()) {
            return UnlockResult.FAILED;
        }
        cacheUnlock(rid, url);
        return UnlockResult.ok(url);
    }

    private void cacheUnlock(String rid, String url) {
        if (unlockCache.size() >= UNLOCK_CACHE_MAX) {
            long now = System.currentTimeMillis();
            unlockCache.values().removeIf(entry -> now >= entry.expiresAt());
            if (unlockCache.size() >= UNLOCK_CACHE_MAX) {
                unlockCache.clear();
            }
        }
        unlockCache.put(rid, new CachedUnlock(url, System.currentTimeMillis() + UNLOCK_CACHE_TTL_MS));
    }

    /** 需登录的 GET:遇登录失效自动重登一次后重试(py _authed_get)。 */
    private JsonNode authedGet(Config config, String path, Map<String, String> params) throws IOException {
        JsonNode payload = getJson(config, path, params, token);
        if (isAuthError(payload) && relogin(config)) {
            payload = getJson(config, path, params, token);
        }
        return payload;
    }

    /** 需登录的 POST(签到/抽奖):遇登录失效自动重登一次后重试。 */
    private JsonNode authedPost(Config config, String path, String jsonBody) throws IOException {
        JsonNode payload = postJson(config, path, jsonBody, token);
        if (isAuthError(payload) && relogin(config)) {
            payload = postJson(config, path, jsonBody, token);
        }
        return payload;
    }

    /** 登录失效判定(py AUTH_ERROR_MARKS):status=error 且报文命中未登录/登录已失效等文案。 */
    static boolean isAuthError(JsonNode payload) {
        if (payload == null || !payload.isObject() || !"error".equals(payload.path("status").asText(""))) {
            return false;
        }
        String message = payload.path("message").asText("");
        return message.contains("未登录") || message.contains("登录已失效")
                || message.contains("请先登录") || message.contains("重新登录");
    }

    // ---------- 登录与 token 状态 ----------

    private boolean ensureToken(Config config) {
        seedToken(config);
        return !token.isEmpty() || login(config);
    }

    private void seedToken(Config config) {
        if (seededToken) {
            return;
        }
        synchronized (this) {
            if (seededToken) {
                return;
            }
            if (!config.token().isBlank()) {
                token = config.token().trim();
            } else {
                String persisted = SiteSearchSupport.setting(settingRepository, SESSION_SETTING);
                if (!persisted.isBlank()) {
                    token = persisted.trim();
                    log.info("聚影复用持久化登录态(免重登)");
                }
            }
            seededToken = true;
        }
    }

    /** token 失效后用账号密码无头续期:清内存与落库旧值再登录(过期 token 不得等重启回灌)。 */
    private boolean relogin(Config config) {
        if (!config.canLogin()) {
            return false;
        }
        token = "";
        evictSession();
        return login(config);
    }

    private synchronized boolean login(Config config) {
        if (loginCooldown.blocked() || !config.canLogin()) {
            return false;
        }
        if (!token.isEmpty()) {
            return true;
        }
        try {
            JsonNode payload = parseBody(http(apiBuilder(config, config.host() + "/api/app/login/", "")
                    .post(RequestBody.create(
                            "{\"username\":\"" + jsonEscape(config.username()) + "\",\"password\":\""
                                    + jsonEscape(config.password()) + "\"}",
                            MediaType.parse("application/json")))
                    .build()));
            String newToken = payload.path("token").asText("").trim();
            if ("success".equals(payload.path("status").asText("")) && !newToken.isEmpty()) {
                applyToken(config, newToken);
                lastLoginError = "";
                log.info("聚影账号 {} 登录成功", config.username());
                return true;
            }
            lastLoginError = "登录被拒:" + payload.path("message").asText("站点未返回原因");
            return loginCooldown.fail("聚影", lastLoginError, LOGIN_COOLDOWN_MS);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            lastLoginError = "登录请求失败:" + reason;
            return loginCooldown.fail("聚影", lastLoginError, LOGIN_COOLDOWN_MS);
        }
    }

    /** token 就位:内存态 + 账号密码形态落库(手配 Token 形态由用户配置自管,不覆盖)。 */
    private void applyToken(Config config, String newToken) {
        token = newToken;
        loginCooldown.reset();
        if (config.token().isBlank()) {
            persistSession(newToken);
        }
    }

    /** 服务端轮换 token 时经该响应头下发:顺手捕获更新内存态(账号密码形态顺带落库)。 */
    private void captureRefreshedToken(Config config, Resp resp) {
        String refreshed = resp.firstHeader("x-refreshed-token");
        if (refreshed.isBlank() || refreshed.equals(token)) {
            return;
        }
        token = refreshed;
        if (config.token().isBlank()) {
            persistSession(refreshed);
        }
    }

    /** 会话落库:token 空 = 清除;失败只告警不阻断(大不了下次重启重登)。 */
    private void persistSession(String value) {
        try {
            settingRepository.save(new Setting(SESSION_SETTING, StringUtils.defaultString(value)));
        } catch (Exception e) {
            log.warn("聚影会话持久化失败(下次重启需重新登录):{}", e.getMessage());
        }
    }

    private void evictSession() {
        persistSession("");
    }

    // ---------- 每日签到与奖池领取 ----------

    /**
     * 每日定时签到(+5 积分)+ 签到幸运奖池免费领取(每日一次,签到前置)。四站点源
     * 签到 06:05-06:11 错峰,本源排 06:13;无凭证静默返回,失败不记日明日重试。
     */
    @Scheduled(cron = "0 13 6 * * *")
    public void dailyCheckin() {
        Config config = loadConfig();
        if (!config.hasCredentials()) {
            return;
        }
        try {
            if (ensureToken(config)) {
                checkinOncePerDay(config);
            }
        } catch (Exception e) {
            log.warn("聚影每日签到失败(明日重试):{}", e.getMessage());
        }
    }

    /**
     * 签到一次(幂等):成功或站点确认「今日已签到」都记账并顺路领取签到奖池;
     * 其它失败不记日,下次调用重试。记账是内存态,重启当日会再撞一次 ——
     * 站点幂等拒绝无副作用。
     */
    void checkinOncePerDay(Config config) throws IOException {
        String today = LocalDate.now().toString();
        if (today.equals(lastCheckinDay)) {
            return;
        }
        JsonNode payload = authedPost(config, "/api/app/checkin/do/", "{}");
        String message = payload.path("message").asText("");
        if ("success".equals(payload.path("status").asText(""))) {
            lastCheckinDay = today;
            log.info("聚影每日签到完成:{}", message);
            drawDailyBonus(config);
        } else if (SiteSearchSupport.alreadyCheckedIn(message)) {
            lastCheckinDay = today;
            log.info("聚影今日已签到(站点确认)");
            drawDailyBonus(config);
        } else {
            log.info("聚影签到未成功(下次重试):{}", message);
        }
    }

    /**
     * 签到幸运奖池领取:can_draw 才抽(pool_id 从奖池接口取,不硬编码);重复请求
     * 站点幂等重放(replayed:true 不多发分),领完 can_draw 翻 false。奖池关闭/已领
     * 静默跳过。
     */
    private void drawDailyBonus(Config config) throws IOException {
        JsonNode pool = authedGet(config, "/api/app/checkin/bonus-pool/", Map.of()).path("pool");
        int poolId = pool.path("id").asInt(0);
        if (poolId <= 0 || !pool.path("can_draw").asBoolean(false)) {
            return;
        }
        JsonNode draw = authedPost(config, "/api/app/checkin/bonus-pool/draw/", "{\"pool_id\":" + poolId + "}");
        if ("success".equals(draw.path("status").asText(""))) {
            log.info("聚影签到奖池已领取:{}", draw.path("message").asText(""));
        } else {
            log.debug("jying bonus draw skipped: {}", draw.path("message").asText(""));
        }
    }

    // ---------- 凭证有效性检查(网页设置页) ----------

    /**
     * 凭证有效性检查:Token 形态用表单值 GET {@code /api/app/profile/} 只读验证
     * (回显站点用户名);Token 未填且带账号密码时实测登录(与搜索自动登录同链路,
     * 成功保存会话)。校验表单当前值,不读 Setting。
     */
    public SiteCredentialCheckResult checkCredential(SiteCredentialCheckRequest request) {
        String formToken = StringUtils.trimToEmpty(request.cookie());
        String username = StringUtils.trimToEmpty(request.username());
        String password = StringUtils.trimToEmpty(request.password());
        Config config = new Config(SiteSearchSupport.normalizeHost(request.host(), DEFAULT_HOST),
                username, password, formToken);
        if (!formToken.isEmpty()) {
            try {
                JsonNode payload = getJson(config, "/api/app/profile/", Map.of(), formToken);
                if (isAuthError(payload)) {
                    return new SiteCredentialCheckResult("jying", false, "Token 已失效(站点判定未登录)");
                }
                String siteUser = payload.path("user").path("username").asText("").trim();
                if (siteUser.isEmpty() && payload.path("user").isMissingNode()) {
                    return new SiteCredentialCheckResult("jying", false, "站点响应异常(未取到用户信息)");
                }
                return new SiteCredentialCheckResult("jying", true,
                        "Token 有效" + (siteUser.isEmpty() ? "" : "(用户 " + siteUser + ")"));
            } catch (Exception e) {
                return new SiteCredentialCheckResult("jying", false, "站点不可达:" + e.getMessage());
            }
        }
        if (username.isEmpty() || password.isEmpty()) {
            return new SiteCredentialCheckResult("jying", false, "未填写 Token 或账号密码");
        }
        if (loginCooldown.blocked()) {
            return new SiteCredentialCheckResult("jying", false, "登录冷却中(前次失败),请稍后再试");
        }
        if (!login(config)) {
            return new SiteCredentialCheckResult("jying", false,
                    "账号密码登录失败:" + StringUtils.defaultIfBlank(lastLoginError, "站点拒绝"));
        }
        return new SiteCredentialCheckResult("jying", true, "账号密码可用(已登录并保存会话)");
    }

    // ---------- HTTP ----------

    private JsonNode getJson(Config config, String path, Map<String, String> params, String tokenHeader)
            throws IOException {
        StringBuilder url = new StringBuilder(config.host()).append(path);
        String sep = "?";
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append(sep).append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=').append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            sep = "&";
        }
        Resp resp = http(apiBuilder(config, url.toString(), tokenHeader).get().build());
        captureRefreshedToken(config, resp);
        return parseBody(resp);
    }

    private JsonNode postJson(Config config, String path, String jsonBody, String tokenHeader) throws IOException {
        Resp resp = http(apiBuilder(config, config.host() + path, tokenHeader)
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build());
        captureRefreshedToken(config, resp);
        return parseBody(resp);
    }

    /** API 公共头(py _headers):认证头仅在持有 token 时携带。 */
    private Request.Builder apiBuilder(Config config, String url, String tokenHeader) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", config.host())
                .header("Referer", config.host() + "/");
        if (StringUtils.isNotBlank(tokenHeader)) {
            builder.header("X-App-User-Token", tokenHeader);
        }
        return builder;
    }

    /** 响应体容错解析:聚影契约与 HTTP 状态码解耦(Django API 用 body 的 status 字段报错),body 总是尝试解析。 */
    private JsonNode parseBody(Resp resp) {
        try {
            JsonNode node = objectMapper.readTree(StringUtils.defaultString(resp.body()));
            return node == null || !node.isObject() ? objectMapper.createObjectNode() : node;
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : StringUtils.defaultString(value).toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private Config loadConfig() {
        return new Config(
                SiteSearchSupport.normalizeHost(SiteSearchSupport.setting(settingRepository, HOST_SETTING), DEFAULT_HOST),
                SiteSearchSupport.setting(settingRepository, USERNAME_SETTING).trim(),
                SiteSearchSupport.setting(settingRepository, PASSWORD_SETTING),
                SiteSearchSupport.setting(settingRepository, TOKEN_SETTING).trim());
    }

    protected Resp http(Request request) throws IOException {
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            return new Resp(response.code(), response.headers("Set-Cookie"), body, response.headers().toMultimap());
        }
    }
}
