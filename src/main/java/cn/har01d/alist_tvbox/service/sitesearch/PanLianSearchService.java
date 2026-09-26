package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.dto.PanLianAccountStatus;
import cn.har01d.alist_tvbox.dto.PanLianCaptcha;
import cn.har01d.alist_tvbox.dto.PanLianCaptchaLoginResult;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p><b>账号池</b>:支持多账号配额叠加 —— Setting {@code panlian_accounts} 存 JSON 数组,
 * 元素为 {@code {"username","password"}}(账号密码)或 {@code {"cookie"}}(Cookie 凭证),
 * 存量 {@code panlian_username}/{@code panlian_password} 与 {@code panlian_cookie}
 * 自动并入池(按身份去重)。池内各账号独立登录态、独立每日签到(+20)、独立配额记账;
 * 搜索轮换起步,某号解锁配额用尽当场切下一号续链,全池用尽才停。凭证必须用户自配,
 * 不内置任何共享账号;全空时本源静默关闭。<b>2026-09-26 起站点登录强制图形验证码
 * ({@code GET /api/auth/captcha} 取图,登录表单加 captcha_id/captcha_code)</b>,账号
 * 密码形态无法再无头自动重登:自动链路遇验证码报错进冷却并在账号状态里引导,由用户
 * 在网页设置页发起 {@link #loginWithCaptcha} 人工输码完成登录;会话 Cookie 仍实测
 * 30 天有效,登录一次落库 Setting {@code panlian_sessions} 后重启免重登,被站点拒收
 * 且无法自动重登(验证码/凭证错)才依赖再次人工登录。
 *
 * <p><b>配额</b>:站点按天计解锁配额(基础 30 + 每日签到 +20,按号叠加),本源另做
 * 三层防御:单次搜索解锁预算上限、解锁结果短缓存(同一检查周期重复搜索不重扣,
 * 全池共享)、配额用尽即停。每日签到幂等,账号被用到时自动触发一次,另有每日定时
 * 兜底({@link #dailyCheckin()})保证无搜索的日子不漏签。
 */
@Slf4j
@Service
public class PanLianSearchService {
    public static final String HOST_SETTING = "panlian_host";
    public static final String USERNAME_SETTING = "panlian_username";
    public static final String PASSWORD_SETTING = "panlian_password";
    public static final String COOKIE_SETTING = "panlian_cookie";
    /** 账号池:JSON 数组,元素 {"username","password"} 或 {"cookie"} */
    public static final String ACCOUNTS_SETTING = "panlian_accounts";
    /** 登录会话持久化:JSON 对象 {"u:username":{"cookie","userId"}},cookie 实测 30 天有效,重启免重登 */
    public static final String SESSIONS_SETTING = "panlian_sessions";

    private static final String DEFAULT_HOST = "https://www.xn--vzy265d.cc";
    private static final int TIMEOUT_SECONDS = 10;
    /** 每次搜索最多取多少个条目的详情(控制站点压力) */
    private static final int MAX_DETAIL_ITEMS = 3;
    /**
     * 单次搜索解锁预算上限(全池合计,不随切号重置):实测解锁按视频/天计且宽松,
     * 但 py 参考实现明确按次扣配额,防计费口径收紧后一次搜索烧穿全天额度。
     */
    private static final int MAX_UNLOCKS_PER_SEARCH = 12;
    /** 解锁结果短缓存:同一检查周期内重复搜索同一剧不重复走两步解锁(省配额,全池共享) */
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
    /** 账号运行态(会话/冷却/签到/配额)按账号身份键持久于内存,池每次搜索从 Setting 重建 */
    private final ConcurrentHashMap<String, AccountState> accountStates = new ConcurrentHashMap<>();
    /** 会话落库的读改写串行锁(JSON 整体读写,多账号并发登录互斥) */
    private final Object sessionStoreLock = new Object();
    private final AtomicInteger rotationCursor = new AtomicInteger();
    private volatile boolean warnedNoCredentials;
    /** 解锁结果缓存:link_id → 折好提取码的最终分享链(账号无关,真实链同源) */
    private final ConcurrentHashMap<String, CachedUnlock> unlockCache = new ConcurrentHashMap<>();

    private record Config(String host) {
    }

    private record CachedUnlock(String url, long expiresAt) {
    }

    /** 落库会话:登录 Cookie + 站点 user_id(重启后播种回内存,保住跨形态去重)。 */
    private record PersistedSession(String cookie, String userId) {
    }

    /** 账号运行态:会话 Cookie、登录冷却、签到/配额按天记账 —— 池内各账号独立配额与签到。 */
    private static final class AccountState {
        final LoginCooldown cooldown = new LoginCooldown();
        volatile String sessionCookie = "";
        volatile String lastCheckinDay = "";
        volatile String quotaExhaustedDay = "";
        /** 站点侧真实身份(/api/auth/login 响应或 /api/me/profile 解析缓存):用户名/邮箱/Cookie 三形态同账号靠它去重 */
        volatile String userId = "";
        /** 最近一次登录失败的归因(captcha/rate_limited/email/credentials),账号状态页据此给出精准引导 */
        volatile String loginBlocker = "";
    }

    private record Account(String key, String username, String password, String cookie, AccountState state)
            implements SiteCredentials {
        boolean cookieBased() {
            return StringUtils.isNotBlank(cookie);
        }

        String display() {
            return cookieBased() ? "cookie" : username;
        }
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

    /** 登录结果:成功带新会话 Cookie 与 user_id;失败带归因(blocker)与用户可读原因。 */
    private record LoginResult(boolean success, String cookie, String userId, LoginReject reject) {
        static LoginResult ok(String cookie, String userId) {
            return new LoginResult(true, cookie, userId, null);
        }

        static LoginResult fail(LoginReject reject) {
            return new LoginResult(false, "", "", reject);
        }
    }

    /** 登录被拒归因:blocker ∈ captcha / rate_limited / email / credentials / error。 */
    record LoginReject(String blocker, String reason) {
    }

    public PanLianSearchService(SettingRepository settingRepository, ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.objectMapper = objectMapper;
    }

    public List<Message> search(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return List.of();
        }
        Config config = loadConfig();
        List<Account> pool = buildPool();
        if (pool.isEmpty()) {
            if (!warnedNoCredentials) {
                warnedNoCredentials = true;
                log.info("盘链搜索源未启用:未配置账号(Setting {} 或 {}+{} 或 {})",
                        ACCOUNTS_SETTING, USERNAME_SETTING, PASSWORD_SETTING, COOKIE_SETTING);
            }
            return List.of();
        }
        try {
            Account account = pickStart(config, pool);
            if (account == null) {
                return List.of();
            }
            checkinOncePerDay(config, account);
            Map<String, String> params = Map.of(
                    "search", keyword.trim(),
                    "page", "1",
                    "page_size", "30");
            JsonNode payload = getJson(config, account, "/api/videos", params);
            if (isLoginRequired(payload)) {
                account.state().sessionCookie = "";
                if (!ensureSession(config, account)) {
                    return List.of();
                }
                payload = getJson(config, account, "/api/videos", params);
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
            Account current = account;
            outer:
            for (JsonNode item : payload.path("data").path("list")) {
                if (details >= MAX_DETAIL_ITEMS) {
                    break;
                }
                String vodId = item.path("vod_id").asText(item.path("id").asText("")).trim();
                String vodName = item.path("vod_name").asText(item.path("title").asText("")).trim();
                if (vodId.isEmpty() || vodName.isEmpty()) {
                    continue;
                }
                String remarks = item.path("vod_remarks").asText(item.path("remarks").asText("")).trim();
                String detailPath = "/api/videos/" + URLEncoder.encode(vodId, StandardCharsets.UTF_8);
                JsonNode detail = getJson(config, current, detailPath, Map.of());
                details++;
                if (isLoginRequired(detail)) {
                    current.state().sessionCookie = "";
                    if (ensureSession(config, current)) {
                        detail = getJson(config, current, detailPath, Map.of());
                    }
                }
                if (!detail.path("success").asBoolean(false)) {
                    continue;
                }
                for (JsonNode link : detail.path("data").path("links")) {
                    if (unlockBudget <= 0) {
                        break outer;
                    }
                    String linkId = link.path("id").asText("").trim();
                    if (linkId.isEmpty() || skipPanType(link)) {
                        continue;
                    }
                    UnlockOutcome outcome = unlockLink(config, current, linkId);
                    if (outcome.quotaExhausted()) {
                        // 当前号配额用尽:当场切下一可用号,同一链接换号重试;全池用尽才放弃
                        current.state().quotaExhaustedDay = LocalDate.now().toString();
                        log.info("panlian 账号 {} 解锁配额已用尽,尝试切换下一账号", current.display());
                        Account next = rotateForQuota(config, pool, current);
                        if (next == null) {
                            break outer;
                        }
                        current = next;
                        outcome = unlockLink(config, current, linkId);
                        if (outcome.quotaExhausted()) {
                            current.state().quotaExhaustedDay = LocalDate.now().toString();
                            continue;
                        }
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
     * 分享链 + 提取码;登录失效重登后整链路重试一次(同号重登,会话过期≠账号故障)。
     * 结果短缓存,命中不消耗预算。
     */
    private UnlockOutcome unlockLink(Config config, Account account, String linkId) throws IOException {
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
        JsonNode ticketPayload = postJson(config, account, "/api/videos/link-ticket", body);
        if (isLoginRequired(ticketPayload)) {
            account.state().sessionCookie = "";
            if (ensureSession(config, account)) {
                ticketPayload = postJson(config, account, "/api/videos/link-ticket", body);
            }
        }
        String ticket = ticketPayload.path("data").path("ticket").asText("").trim();
        if (!ticketPayload.path("success").asBoolean(false) || ticket.isEmpty()) {
            return quotaOrFailed(ticketPayload);
        }
        JsonNode openPayload = getJson(config, account, "/api/videos/link-open/" + linkId, Map.of("t", ticket));
        if (isLoginRequired(openPayload)) {
            account.state().sessionCookie = "";
            if (ensureSession(config, account)) {
                ticketPayload = postJson(config, account, "/api/videos/link-ticket", body);
                ticket = ticketPayload.path("data").path("ticket").asText("").trim();
                if (!ticketPayload.path("success").asBoolean(false) || ticket.isEmpty()) {
                    return quotaOrFailed(ticketPayload);
                }
                openPayload = getJson(config, account, "/api/videos/link-open/" + linkId, Map.of("t", ticket));
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
     * 每账号每天首次被用到时触发一次,失败不记日下次重试;签到不成分不影响搜索主链路。
     */
    private void checkinOncePerDay(Config config, Account account) {
        String today = LocalDate.now().toString();
        if (today.equals(account.state().lastCheckinDay)) {
            return;
        }
        try {
            JsonNode payload = postJson(config, account, "/api/tasks/checkin", "{}");
            if (payload.path("success").asBoolean(false)) {
                account.state().lastCheckinDay = today;
                JsonNode quota = payload.path("data").path("quota");
                if (!quota.isMissingNode()) {
                    log.info("盘链账号 {} 每日签到完成:解锁配额 remaining={}/{}", account.display(),
                            quota.path("remaining").asText("?"), quota.path("limit").asText("?"));
                } else {
                    log.info("盘链账号 {} 每日签到完成:{}", account.display(), payload.path("data").path("message").asText(""));
                }
            } else {
                log.debug("panlian[{}] checkin skipped: {}", account.display(),
                        payload.path("message").asText(payload.path("msg").asText("")));
            }
        } catch (Exception e) {
            log.debug("panlian[{}] checkin failed: {}", account.display(), e.getMessage());
        }
    }

    /**
     * 每日定时签到:搜索是"账号被用到才签",当天没有搜索的账号会漏签(+20 配额白丢),
     * 定时逐号补齐。与 {@link #checkinOncePerDay} 共享同日记账,先签后搜/先搜后签都只签
     * 一次;登录失败冷却中的账号跳过(次日重试),无账号时静默返回。
     */
    @Scheduled(cron = "0 5 6 * * *")
    public void dailyCheckin() {
        Config config = loadConfig();
        List<Account> pool = buildPool();
        if (pool.isEmpty()) {
            return;
        }
        int done = 0;
        for (Account account : pool) {
            if (account.state().cooldown.blocked()) {
                continue;
            }
            try {
                if (ensureSession(config, account)) {
                    checkinOncePerDay(config, account);
                    done++;
                }
            } catch (Exception e) {
                log.debug("panlian[{}] scheduled checkin failed: {}", account.display(), e.getMessage());
            }
        }
        log.info("盘链每日定时签到完成:{}/{} 个账号", done, pool.size());
    }

    /**
     * 账号池状态(只读,网页设置页展示):逐号确保会话后拉 {@code /api/tasks}(配额+签到)
     * 与 {@code /api/me/profile}(账号信息);不触发签到、不改任何运行态记账。
     * 顺带解析各成员 user_id 缓存,展示列表按 user_id 去重并按形态优先级择代表
     * (账号用户名 &gt; 账号邮箱 &gt; Cookie)。
     */
    public List<PanLianAccountStatus> accountStatuses() {
        Config config = loadConfig();
        List<Account> described = new ArrayList<>();
        Map<Account, PanLianAccountStatus> statusByAccount = new LinkedHashMap<>();
        for (Account account : buildPool()) {
            statusByAccount.put(account, describeAccount(config, account));
            described.add(account);
        }
        return dedupeByUserId(described).stream().map(statusByAccount::get).toList();
    }

    private PanLianAccountStatus describeAccount(Config config, Account account) {
        String identity = account.display();
        boolean exhaustedToday = LocalDate.now().toString().equals(account.state().quotaExhaustedDay);
        if (!ensureSession(config, account)) {
            return new PanLianAccountStatus(identity, null, null, account.cookieBased(),
                    "login_failed", loginFailureMessage(account), exhaustedToday,
                    false, 0, 0, 0, 0);
        }
        try {
            JsonNode tasks = getJson(config, account, "/api/tasks", Map.of());
            if (isLoginRequired(tasks)) {
                account.state().sessionCookie = "";
                if (ensureSession(config, account)) {
                    tasks = getJson(config, account, "/api/tasks", Map.of());
                }
            }
            JsonNode profile = getJson(config, account, "/api/me/profile", Map.of());
            String siteUserId = profile.path("data").path("user_id").asText("").trim();
            if (!siteUserId.isEmpty()) {
                account.state().userId = siteUserId;
            }
            String username = profile.path("data").path("username").asText("").trim();
            String email = profile.path("data").path("email").asText("").trim();
            if (!tasks.path("success").asBoolean(false)) {
                return new PanLianAccountStatus(identity,
                        username.isEmpty() ? null : username, email.isEmpty() ? null : email, account.cookieBased(),
                        "error", "站点状态查询失败:" + tasks.path("message").asText(""), exhaustedToday,
                        false, 0, 0, 0, 0);
            }
            JsonNode data = tasks.path("data");
            JsonNode quota = data.path("quota");
            JsonNode checkin = data.path("checkin");
            return new PanLianAccountStatus(identity,
                    username.isEmpty() ? null : username, email.isEmpty() ? null : email, account.cookieBased(),
                    "ok", null, exhaustedToday,
                    checkin.path("done").asBoolean(false), checkin.path("bonus").asInt(0),
                    quota.path("remaining").asInt(0), quota.path("limit").asInt(0), quota.path("used").asInt(0));
        } catch (Exception e) {
            return new PanLianAccountStatus(identity, null, null, account.cookieBased(),
                    "error", e.getMessage(), exhaustedToday, false, 0, 0, 0, 0);
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

    /** 登录失败状态的用户可读提示:按最近一次失败归因引导(验证码→本页人工登录,邮箱→改 Cookie)。 */
    private static String loginFailureMessage(Account account) {
        return switch (StringUtils.defaultString(account.state().loginBlocker)) {
            case "captcha" -> "站点登录已启用图形验证码,请在下方完成验证码登录";
            case "rate_limited" -> "密码错误次数过多被站点限流,请稍后再试";
            case "email" -> "账号需邮箱确认/二次验证,请改用 Cookie 形态";
            default -> "登录失败(账号密码被拒或站点故障,冷却中重试)";
        };
    }

    /**
     * 账号池:{@code panlian_accounts} 存 JSON 数组(元素 {"username","password"} 或
     * {"cookie"}),存量单账号/Cookie 配置并入(按身份去重);池为空=未启用。
     * 每次搜索从 Setting 重建,运行态按身份键挂回。同一站点账号可能以用户名/邮箱/Cookie
     * 多形态入池 —— 登录或状态查询解析出 user_id 后按它跨形态去重(保留首个),未解析前
     * 各自保留,随使用收敛。
     */
    private List<Account> buildPool() {
        Map<String, Account> pool = new LinkedHashMap<>();
        String bulk = SiteSearchSupport.setting(settingRepository, ACCOUNTS_SETTING);
        if (StringUtils.isNotBlank(bulk)) {
            try {
                JsonNode entries = objectMapper.readTree(bulk);
                if (entries.isArray()) {
                    for (JsonNode entry : entries) {
                        String username = entry.path("username").asText("").trim();
                        String password = entry.path("password").asText("");
                        String cookie = entry.path("cookie").asText("").trim();
                        if (!username.isEmpty() && !password.isEmpty()) {
                            pool.computeIfAbsent("u:" + username, key -> newAccount(key, username, password, ""));
                        } else if (!cookie.isEmpty()) {
                            pool.computeIfAbsent("c:" + cookie, key -> newAccount(key, "", "", cookie));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("panlian 账号池配置解析失败(须为 JSON 数组):{}", e.getMessage());
            }
        }
        String username = SiteSearchSupport.setting(settingRepository, USERNAME_SETTING).trim();
        String password = SiteSearchSupport.setting(settingRepository, PASSWORD_SETTING);
        if (!username.isEmpty() && !password.isEmpty()) {
            pool.computeIfAbsent("u:" + username, key -> newAccount(key, username, password, ""));
        }
        String cookie = SiteSearchSupport.setting(settingRepository, COOKIE_SETTING).trim();
        if (!cookie.isEmpty()) {
            pool.computeIfAbsent("c:" + cookie, key -> newAccount(key, "", "", cookie));
        }
        return dedupeByUserId(List.copyOf(pool.values()));
    }

    /**
     * 同站点账号跨形态去重:user_id 相同的成员按形态优先级择一保留 —— 账号用户名 &gt;
     * 账号邮箱 &gt; Cookie(账号密码形态会话过期可自动重登自愈,Cookie 过期即死板),
     * 槽位稳定在首次出现处;user_id 未解析的成员保留待收敛。
     */
    private static List<Account> dedupeByUserId(List<Account> pool) {
        if (pool.size() < 2) {
            return pool;
        }
        List<Account> result = new ArrayList<>();
        Map<String, Integer> slots = new HashMap<>();
        for (Account account : pool) {
            String userId = account.state().userId;
            if (userId.isEmpty()) {
                result.add(account);
                continue;
            }
            Integer index = slots.get(userId);
            if (index == null) {
                slots.put(userId, result.size());
                result.add(account);
            } else if (formPriority(account) > formPriority(result.get(index))) {
                log.info("盘链账号池去重:user_id={} 保留 {} 形态(替换 {} 形态)", userId, account.display(), result.get(index).display());
                result.set(index, account);
            } else {
                log.info("盘链账号池去重:{} 与保留成员是同一站点账号(user_id={}),丢弃", account.display(), userId);
            }
        }
        return result;
    }

    /** 形态优先级:账号用户名(标识不含 @)2 &gt; 账号邮箱(含 @)1 &gt; Cookie 0。 */
    private static int formPriority(Account account) {
        if (account.cookieBased()) {
            return 0;
        }
        return account.username().contains("@") ? 1 : 2;
    }

    private Account newAccount(String key, String username, String password, String cookie) {
        boolean cookieBased = StringUtils.isNotBlank(cookie);
        // 播种只在账号态创建时发生一次:被站点拒收后清空的内存会话不能回灌同一张过期 Cookie
        AccountState state = accountStates.computeIfAbsent(key, k -> cookieBased ? new AccountState() : seedState(key));
        return new Account(key, username, password, cookie, state);
    }

    /** 从落库会话播种内存态(重启免重登);Cookie 形态凭据即会话,不播种。 */
    private AccountState seedState(String key) {
        AccountState state = new AccountState();
        PersistedSession session = loadSessions().get(key);
        if (session != null) {
            state.sessionCookie = session.cookie();
            state.userId = session.userId();
            log.info("盘链账号 {} 复用持久化登录态(免重登)", key.substring(Math.min(2, key.length())));
        }
        return state;
    }

    /** 读落库会话:非法 JSON 容错为空(下次登录重建),值绝不进日志。 */
    private Map<String, PersistedSession> loadSessions() {
        Map<String, PersistedSession> sessions = new LinkedHashMap<>();
        String raw = SiteSearchSupport.setting(settingRepository, SESSIONS_SETTING);
        if (StringUtils.isBlank(raw)) {
            return sessions;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isObject()) {
                node.fields().forEachRemaining(field -> {
                    String cookie = field.getValue().path("cookie").asText("").trim();
                    if (!cookie.isEmpty()) {
                        sessions.put(field.getKey(), new PersistedSession(cookie, field.getValue().path("userId").asText("").trim()));
                    }
                });
            }
        } catch (Exception e) {
            log.warn("盘链持久会话解析失败(将重新登录):{}", e.getMessage());
        }
        return sessions;
    }

    /** 搜索起步账号:轮换取一个可用号(未配额尽/未冷却、登录成功),全不可用返回 null。 */
    private Account pickStart(Config config, List<Account> pool) {
        int start = Math.floorMod(rotationCursor.getAndIncrement(), pool.size());
        for (int i = 0; i < pool.size(); i++) {
            Account candidate = pool.get(Math.floorMod(start + i, pool.size()));
            if (!available(candidate)) {
                continue;
            }
            if (ensureSession(config, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 配额用尽后切号:从当前号下一个开始找可用号,登录+签到后交还;找不到返回 null。 */
    private Account rotateForQuota(Config config, List<Account> pool, Account exhausted) {
        int from = pool.indexOf(exhausted);
        for (int i = 1; i <= pool.size(); i++) {
            Account candidate = pool.get(Math.floorMod(from + i, pool.size()));
            if (!available(candidate)) {
                continue;
            }
            if (!ensureSession(config, candidate)) {
                continue;
            }
            checkinOncePerDay(config, candidate);
            log.info("panlian 切换账号 {} → {}(解锁配额续链)", exhausted.display(), candidate.display());
            return candidate;
        }
        return null;
    }

    private static boolean available(Account account) {
        return !LocalDate.now().toString().equals(account.state().quotaExhaustedDay)
                && !account.state().cooldown.blocked();
    }

    private boolean ensureSession(Config config, Account account) {
        if (StringUtils.isNotBlank(account.state().sessionCookie)) {
            return true;
        }
        if (account.cookieBased()) {
            account.state().sessionCookie = account.cookie();
            return true;
        }
        return login(config, account);
    }

    private boolean login(Config config, Account account) {
        AccountState state = account.state();
        synchronized (state) {
            if (state.cooldown.blocked() || !account.canLogin()) {
                return false;
            }
            if (StringUtils.isNotBlank(state.sessionCookie)) {
                return true;
            }
            LoginResult result = doLogin(config, account, "", "");
            if (result.success()) {
                applyLoginSuccess(account, result);
                return true;
            }
            return loginFailed(account, result.reject());
        }
    }

    /**
     * 图形验证码获取(匿名可用):{@code GET /api/auth/captcha} → data.id + base64 图,
     * id 单次有效,配套 {@link #loginWithCaptcha} 提交。
     */
    public PanLianCaptcha fetchCaptcha() throws IOException {
        Config config = loadConfig();
        Account anonymous = new Account("anon", "", "", "", new AccountState());
        JsonNode payload = getJson(config, anonymous, "/api/auth/captcha", Map.of());
        String id = payload.path("data").path("id").asText("").trim();
        String image = payload.path("data").path("image").asText("").trim();
        if (!payload.path("success").asBoolean(false) || id.isEmpty() || image.isEmpty()) {
            throw new IllegalStateException("验证码获取失败:" + payload.path("message").asText("站点无响应"));
        }
        return new PanLianCaptcha(id, image, payload.path("data").path("ttl").asInt(300));
    }

    /**
     * 人工验证码登录(2026-09-26 站点登录强制图形验证码后的补救通道):用户在网页端
     * 看图输码,后端带 captcha_id/captcha_code 完成登录。与池内同账号键({@code u:username})
     * 复用运行态,成功后会话落库、清冷却,搜索链路立即可用;失败只回原因 —— 验证码错
     * 刷新图片即可重试,不进入自动链路的冷却/清会话口径(旧会话未必已死)。
     */
    public PanLianCaptchaLoginResult loginWithCaptcha(String username, String password, String captchaId, String captchaCode) {
        if (StringUtils.isAnyBlank(username, password, captchaId, captchaCode)) {
            return PanLianCaptchaLoginResult.fail("请填写账号、密码与图形验证码");
        }
        Config config = loadConfig();
        String key = "u:" + username.trim();
        AccountState state = accountStates.computeIfAbsent(key, this::seedState);
        Account account = new Account(key, username.trim(), password, "", state);
        synchronized (state) {
            LoginResult result = doLogin(config, account, captchaId.trim(), captchaCode.trim());
            if (result.success()) {
                applyLoginSuccess(account, result);
                return PanLianCaptchaLoginResult.ok(state.userId);
            }
            return PanLianCaptchaLoginResult.fail(result.reject().reason());
        }
    }

    /**
     * 登录核心(表单 POST {@code /api/auth/login}):captchaId/captchaCode 非空随表单提交
     * (2026-09-26 起站点强制,字段名 captcha_id/captcha_code,实测缺码即 400
     * {@code error_type=VALIDATION, details.captcha_error=true})。
     */
    private LoginResult doLogin(Config config, Account account, String captchaId, String captchaCode) {
        try {
            FormBody.Builder form = new FormBody.Builder()
                    .add("username", account.username())
                    .add("password", account.password())
                    .add("remember", "1");
            if (StringUtils.isNotBlank(captchaId)) {
                form.add("captcha_id", captchaId);
            }
            if (StringUtils.isNotBlank(captchaCode)) {
                form.add("captcha_code", captchaCode);
            }
            Resp resp = http(new Request.Builder()
                    .url(config.host() + "/api/auth/login")
                    .header("User-Agent", userAgent())
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-TW,zh;q=0.9,zh-CN;q=0.8,en;q=0.7")
                    .header("Origin", config.host())
                    .header("Referer", config.host() + "/login")
                    .post(form.build())
                    .build());
            if (resp.code() != 200) {
                // 站点以 400+结构化 JSON 拒绝(实测缺验证码即 400):body 仍是可归因错误,不浪费
                LoginResult parsed = parseLoginResponse(resp, captchaCode);
                if (parsed != null) {
                    return parsed;
                }
                return LoginResult.fail(new LoginReject("error", "login api http " + resp.code()));
            }
            LoginResult parsed = parseLoginResponse(resp, captchaCode);
            if (parsed != null) {
                return parsed;
            }
            return LoginResult.fail(new LoginReject("error", "登录响应不是有效 JSON"));
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return LoginResult.fail(new LoginReject("error", reason));
        }
    }

    /** 登录响应解析:body 是含 success 字段的结构化 JSON 才给结论,否则返回 null 交上游兜底。 */
    private LoginResult parseLoginResponse(Resp resp, String captchaCode) {
        if (StringUtils.isBlank(resp.body())) {
            return null;
        }
        try {
            JsonNode payload = objectMapper.readTree(resp.body());
            if (!payload.isObject() || !payload.has("success")) {
                return null;
            }
            if (!payload.path("success").asBoolean(false)) {
                return LoginResult.fail(parseLoginReject(payload, StringUtils.isNotBlank(captchaCode)));
            }
            String cookie = SiteSearchSupport.joinCookies(SiteSearchSupport.parseCookies(resp.setCookies()));
            if (cookie.isBlank()) {
                return LoginResult.fail(new LoginReject("error", "登录成功但未取到 Cookie"));
            }
            return LoginResult.ok(cookie, payload.path("data").path("user_id").asText("").trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 登录被拒归因(实测/前端 bundle 契约):captcha_error(缺码或码错)、RATE_LIMITED
     * (密码错太多次,details.retry_after_seconds)、email_verify_required(新设备邮箱
     * 确认,confirm_id)、email_code_required(账号邮箱二次验证),其余按账号密码被拒。
     * 结构化标志优先于验证码文案判定:邮箱验证码的报文同样含「验证码」字样,只看文案会误判。
     */
    static LoginReject parseLoginReject(JsonNode payload, boolean captchaSent) {
        String message = payload.path("message").asText(payload.path("msg").asText(""));
        JsonNode details = payload.path("details");
        if (details.path("email_verify_required").asBoolean(false)) {
            return new LoginReject("email",
                    "站点要求邮箱确认登录(新设备保护),请在浏览器完成一次后改用 Cookie 形态:" + message);
        }
        if (details.path("email_code_required").asBoolean(false)) {
            return new LoginReject("email",
                    "账号开启了邮箱验证码二次验证,自动登录不支持,请改用 Cookie 形态:" + message);
        }
        if ("RATE_LIMITED".equals(payload.path("error_type").asText("")) || details.path("retry_after_seconds").isNumber()) {
            long retry = details.path("retry_after_seconds").asLong(0);
            return new LoginReject("rate_limited",
                    "密码错误次数过多被限流" + (retry > 0 ? "," + retry + " 秒后再试" : "") + ":" + message);
        }
        if (details.path("captcha_error").asBoolean(false) || message.contains("验证码")) {
            String prefix = captchaSent ? "图形验证码错误" : "站点登录需图形验证码";
            return new LoginReject("captcha", prefix + ":" + message);
        }
        return new LoginReject("credentials", "账号密码被拒绝:" + message);
    }

    /** 登录成功落运行态+落库:会话 Cookie/user_id 就位,清失败归因与冷却。 */
    private void applyLoginSuccess(Account account, LoginResult result) {
        AccountState state = account.state();
        state.sessionCookie = result.cookie();
        if (StringUtils.isNotBlank(result.userId())) {
            state.userId = result.userId();
        }
        state.loginBlocker = "";
        state.cooldown.reset();
        log.info("盘链账号 {} 登录成功", account.username());
        persistSession(account, result.cookie());
    }

    private boolean loginFailed(Account account, LoginReject reject) {
        AccountState state = account.state();
        state.sessionCookie = "";
        state.loginBlocker = reject.blocker();
        // 走到登录说明旧会话已被站点拒收或从未建立,过期 Cookie 不得留在库里等重启回灌
        persistSession(account, "");
        return state.cooldown.fail("盘链[" + account.display() + "]", reject.reason(), LOGIN_COOLDOWN_MS);
    }

    /**
     * 会话落库:cookie 空 = 清除该账号的持久会话;失败只告警不阻断(大不了下次重启重登)。
     * Cookie 形态账号的凭据即用户手配,不属于本方法管辖。
     */
    private void persistSession(Account account, String cookie) {
        if (account.cookieBased()) {
            return;
        }
        synchronized (sessionStoreLock) {
            Map<String, PersistedSession> sessions = loadSessions();
            if (StringUtils.isBlank(cookie)) {
                if (sessions.remove(account.key()) != null) {
                    saveSessions(sessions);
                }
                return;
            }
            sessions.put(account.key(), new PersistedSession(cookie, account.state().userId));
            saveSessions(sessions);
        }
    }

    private void saveSessions(Map<String, PersistedSession> sessions) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            sessions.forEach((key, session) -> node.putObject(key)
                    .put("cookie", session.cookie())
                    .put("userId", session.userId()));
            settingRepository.save(new Setting(SESSIONS_SETTING, objectMapper.writeValueAsString(node)));
        } catch (Exception e) {
            log.warn("盘链会话持久化失败(下次重启需重新登录):{}", e.getMessage());
        }
    }

    private JsonNode getJson(Config config, Account account, String path, Map<String, String> params) throws IOException {
        StringBuilder url = new StringBuilder(config.host()).append(path);
        String sep = "?";
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append(sep).append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=').append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            sep = "&";
        }
        return parseJson(http(apiBuilder(config, account, url.toString()).get().build()));
    }

    private JsonNode postJson(Config config, Account account, String path, String jsonBody) throws IOException {
        Request request = apiBuilder(config, account, config.host() + path)
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();
        return parseJson(http(request));
    }

    /** API 公共头:Origin/XHR/Referer 缺一会被站点 CSRF 口径拒成 ADMIN_AUTH_REQUIRED(2026-09-12 实测)。 */
    private Request.Builder apiBuilder(Config config, Account account, String url) {
        return new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent())
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-TW,zh;q=0.9,zh-CN;q=0.8,en;q=0.7")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", config.host())
                .header("Referer", config.host() + "/")
                .header("Cookie", StringUtils.defaultString(account.state().sessionCookie));
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
        return new Config(normalizeHost(SiteSearchSupport.setting(settingRepository, HOST_SETTING)));
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
