package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.dto.SiteCredentialCheckRequest;
import cn.har01d.alist_tvbox.dto.SiteCredentialCheckResult;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Dns;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 观影搜索源(atv-spiders/py/观影.py 的 Java 移植,追剧搜索源之一):观影是需登录的
 * 网盘分享聚合站,多镜像域名互为镜像(教父.com/星际穿越.com/hgeme.com 等,清单以官方
 * 地址发布页「挂了.com」的 check.js 为准——域名会轮换/退役,已知镜像全失败时自动
 * 从发布页自发现最新清单重试)。
 *
 * <p>反爬两道:①PoW 工作量证明——响应出现挑战特征(JSON code=419/"浏览器安全验证"/
 * pow.worker 等,注意页面含 {@code _obj.} 即为正常数据页不算挑战)时,取 {@code /res/pow}
 * 的 {N,x,t} 算 {@code y = x^(2^t) mod N}(BigInteger.modPow,2026-09-20 起 t 随机 20-40 万、
 * 2048-bit 模数,单次求解亚秒级)提交换 {@code browser_verified};另有<b>请求头闸门</b>:
 * 带 Accept-Language(任何值)PoW 收账后仍会被重新挑战、永不放行(2026-09-21 实测 100% 复现),
 * 请求族仅 UA/Accept/Referer/Cookie,勿加回;
 * ②登录态——响应含 nologin/未登录 时用账号密码重登。<b>凭证必须用户自配</b>
 * (Setting {@code guanying_username}/{@code guanying_password} 或直接 {@code guanying_cookie},
 * 站点列表 {@code guanying_host} 可覆盖,逗号/竖线/换行分隔),未配置时本源静默关闭。
 * 登录取得的会话 Cookie(登录表单 cookietime=10506240,约 121 天)剔 PoW 短时效项后
 * 落库 Setting {@code guanying_session}(账号密码形态专属,Cookie 形态由用户配置自管),
 * 重启播种回内存免重登,被站点判 nologin 且重登失败才清除 —— 失效前不重复登录。
 *
 * <p>搜索:HTML 页内嵌 {@code _obj.search={l:{i,title,d,year,info}}} 列表,空则回退
 * {@code /res/search_suggest} JSON;盘链取 {@code /res/downurl/{type}/{rid}} 的
 * {@code panlist.url/name/p} 平行数组,结构化提取码折 {@code ?password=};同一响应的
 * {@code downlist.list.{m,t}} 是磁力种子(btih 哈希+名称平行数组),一并产出 magnet 条目
 * (type=magnet,种子名折进 {@code dn=}),供追剧磁力兜底在 fillPool 的 NON_PAN 收割 ——
 * 与网盘链接同响应零额外请求,兜底未开时由定向集闸门统一剔除。
 */
@Slf4j
@Service
public class GuanYingSearchService {
    public static final String HOST_SETTING = "guanying_host";
    public static final String USERNAME_SETTING = "guanying_username";
    public static final String PASSWORD_SETTING = "guanying_password";
    public static final String COOKIE_SETTING = "guanying_cookie";
    /** 登录会话持久化:登录态 Cookie 头(k=v; k=v,剔 PoW 短时效项),重启播种免重登 */
    public static final String SESSION_SETTING = "guanying_session";

    /** www 形态才是服务域名(裸域所有路径都 302 到 www,救不了);www A 记录 TTL≈1s 轮换反封锁,
     * 任一时刻常有镜像解析到 0.0.0.0 下线中,解析层 resolveAliveRecords 剔死记录。
     * 清单以官方地址发布页(挂了.com)check.js 的 urlData 为准(2026-09-20:楚门的世界/
     * 泰坦尼克号/阿甘正传已退役,新增 ASCII 域 hgeme.com),域名再轮换由 discoverHosts 自愈。 */
    private static final List<String> DEFAULT_HOSTS = List.of(
            "https://www.教父.com", "https://www.星际穿越.com", "https://www.盗梦空间.com",
            "https://www.黑客帝国.com", "https://www.肖申克的救赎.com", "https://www.hgeme.com");
    /** 官方地址发布页:check.js 是静态资源不过 auth,内嵌 urlData 最新镜像清单。 */
    private static final String PUBLISH_HOST = SiteSearchSupport.normalizeHost("https://www.挂了.com", "");
    private static final Pattern CHECK_JS_URLS = Pattern.compile("url:\\s*'(https?://[^']+)'");
    /** 发布页抓取节流:站点全挂时不能每次搜索都打发布页,失败后冷却再抓。 */
    private static final long DISCOVERY_RETRY_MS = 10 * 60_000L;
    /** 风控态原因文案:PoW 已被站点收账(success:true)却仍被要求验证。 */
    private static final String POW_REJECTED_CAUSE = "PoW 通过仍被要求验证(站点反爬未放行)";
    /**
     * PoW 熔断时长:PoW 被收账(success:true)仍遭挑战 = 反爬不认账(2026-09-21 实测根因=请求带
     * Accept-Language 头,任何值都拦、不带则放行,已从请求族移除;此态再现说明反爬规则又变),
     * 所有镜像同一后端,熔断期内不再发起任何求解防连环刺激。
     */
    private static final long POW_BREAK_MS = 60_000L;
    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1";
    private static final String ACCEPT = "text/html,application/xhtml+xml,application/json,text/plain,*/*";
    private static final int TIMEOUT_SECONDS = 15;
    /** 每次搜索最多取多少个条目的盘链 */
    private static final int MAX_DETAIL_ITEMS = 3;
    private static final long LOGIN_COOLDOWN_MS = 5 * 60_000L;
    /** PoW 会话 Cookie(有它们不算登录态) */
    private static final Set<String> TRANSIENT_COOKIES = Set.of("browser_pow", "browser_verified");
    private static final long MAX_POW_ROUNDS = 2_000_000L;
    private static final Pattern SEARCH_OBJ = Pattern.compile("_obj\\.search=(\\{.*?\\});\\s*_obj\\.");
    private static final Pattern CODE_419 = Pattern.compile("\"code\"\\s*:\\s*419");

    private final SettingRepository settingRepository;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient();
    /** 全镜像共享的 Cookie 状态(所有域名同一后端,Python 同策略) */
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private volatile boolean seededConfigCookie;
    private final LoginCooldown loginCooldown = new LoginCooldown();
    private volatile String activeHost = "";
    private volatile boolean warnedNoCredentials;
    /** 最近一次登录失败原因(手动检查账号密码时回显,登录成功清空)。 */
    private volatile String lastLoginError = "";
    /** 发布页发现的最新镜像(缓存,空 = 从未发现成功)。 */
    private volatile List<String> discoveredHosts = List.of();
    private volatile long lastDiscoveryAttempt;
    /** 登录单轮尝试的失败原因(UNREACHABLE/REJECTED 供 loginFailed 回显,仅 synchronized login 内读写)。 */
    private String loginRoundError = "";
    /** PoW 风控熔断至时刻(毫秒):期间 ensurePow 直接拒绝求解。 */
    private volatile long powBreakUntil;

    public GuanYingSearchService(SettingRepository settingRepository, ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.objectMapper = objectMapper;
    }

    private record Config(List<String> hosts, String username, String password, String cookie) implements SiteCredentials {
    }

    /** 登录单轮尝试的三态:成功 / 站点明确拒绝(换镜像也不会变)/ 全镜像不可达。 */
    private enum LoginOutcome { SUCCESS, REJECTED, UNREACHABLE }

    record Item(String dtype, String rid, String title, String remarks) {
    }

    public List<Message> search(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return List.of();
        }
        Config config = loadConfig();
        if (!config.hasCredentials()) {
            if (!warnedNoCredentials) {
                warnedNoCredentials = true;
                log.info("观影搜索源未启用:未配置账号(Setting {}+{} 或 {})", USERNAME_SETTING, PASSWORD_SETTING, COOKIE_SETTING);
            }
            return List.of();
        }
        try {
            if (!ensureAuth(config)) {
                return List.of();
            }
            Map<String, String> searchParams = new LinkedHashMap<>();
            searchParams.put("q", keyword.trim());
            searchParams.put("type", "");
            searchParams.put("mode", "1");
            List<Item> items = parseSearch(requestText(config, "/search", searchParams));
            if (items.isEmpty()) {
                items = parseSearchSuggest(requestJson(config, "/res/search_suggest", Map.of("q", keyword.trim())));
            }

            List<Message> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int details = 0;
            for (Item item : items) {
                if (details >= MAX_DETAIL_ITEMS) {
                    break;
                }
                JsonNode detail = requestJson(config, "/res/downurl/" + item.dtype() + "/" + item.rid(), null);
                details++;
                JsonNode pan = detail.path("panlist");
                JsonNode urls = pan.path("url");
                JsonNode names = pan.path("name");
                JsonNode passwords = pan.path("p");
                for (int i = 0; i < urls.size(); i++) {
                    String url = appendPassword(urls.get(i).asText("").trim(), passwords.path(i).asText("").trim());
                    if (StringUtils.isBlank(url)) {
                        continue;
                    }
                    String type = Message.parseType(url);
                    if (type == null || !SiteSearchSupport.isNumeric(type)) {
                        continue; // 只留可挂载的网盘分享;磁力/未知盘对候选池无意义
                    }
                    String name = names.path(i).asText("").trim();
                    Message message = new Message();
                    message.setType(type);
                    message.setLink(url);
                    message.setName(item.title());
                    message.setChannel("观影");
                    message.setContent((item.title() + " " + StringUtils.defaultString(name) + " "
                            + StringUtils.defaultString(item.remarks())).trim());
                    if (seen.add(message.getLink())) {
                        result.add(message);
                    }
                }
                for (Message message : magnetsFromDetail(detail, item)) {
                    if (seen.add(message.getLink())) {
                        result.add(message);
                    }
                }
            }
            log.info("GuanYing search {} get {} results", keyword, result.size());
            return result;
        } catch (Exception e) {
            // 上抛而非吞掉空表:聚合层 searchAsync 捕获后记 SearchSourceThrottle 退避并返空;
            // 吞掉则死站被 recordSuccess 清零连击,退避闸门对站点源永不生效
            log.warn("guanying search [{}] failed: {}", keyword, e.getMessage());
            throw e instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(e);
        }
    }

    // ---------- 请求管线:多镜像 failover + PoW/登录自动恢复 ----------

    private Resp requestResponse(Config config, String path, Map<String, String> params) {
        Resp resp = tryHosts(orderedHosts(config), config, path, params);
        if (resp == null) {
            // 已知镜像全失败:域名可能又轮换/退役了,从发布页自发现最新清单再试一轮
            List<String> fresh = freshDiscoveredHosts(config);
            if (!fresh.isEmpty()) {
                resp = tryHosts(fresh, config, path, params);
            }
        }
        return resp;
    }

    /** PoW 风控熔断是否生效。 */
    private boolean powBreakActive() {
        return System.currentTimeMillis() < powBreakUntil;
    }

    private void tripPowBreak() {
        powBreakUntil = System.currentTimeMillis() + POW_BREAK_MS;
    }

    private Resp tryHosts(List<String> hosts, Config config, String path, Map<String, String> params) {
        // PoW 已收账仍被挑战 = 反爬不认账(根因曾为 Accept-Language 头,已移除):所有镜像
        // 同一后端,熔断期内不再求解,继续换镜像无意义
        boolean powRejected = false;
        for (String host : hosts) {
            boolean powRetried = false;
            boolean authRetried = false;
            for (int attempt = 0; attempt < 3; attempt++) {
                Resp resp;
                try {
                    resp = http(buildGet(host, path, params));
                } catch (Exception e) {
                    break;
                }
                String body = StringUtils.defaultString(resp.body());
                mergeCookies(resp.setCookies());
                if (detectChallenge(body)) {
                    if (powRetried) {
                        tripPowBreak();
                        powRejected = true;
                        break;
                    }
                    if (powBreakActive() || !ensurePow(config, host, true)) {
                        break;
                    }
                    powRetried = true;
                    continue;
                }
                if (resp.code() == 404 || body.contains("<title>404 Not Found</title>")) {
                    break;
                }
                if (isNotLoggedIn(body)) {
                    if (!authRetried && reauthenticate(config)) {
                        authRetried = true;
                        continue;
                    }
                    return null;
                }
                if (resp.code() >= 500 || body.isEmpty()) {
                    break;
                }
                if (resp.code() >= 400) {
                    break;
                }
                activeHost = host; // 成功镜像粘滞
                return resp;
            }
            if (powRejected) {
                break;
            }
        }
        return null;
    }

    // ---------- 发布页自发现 ----------

    /**
     * 官方地址发布页(挂了.com)自发现:站点会轮换/退役镜像域名(2026-09-20 实测楚门的世界/
     * 泰坦尼克号/阿甘正传被除名、新增 hgeme.com),发布页 check.js 的 urlData 恒为最新清单。
     * 仅在已知镜像全失败后调用,10 分钟节流;解析失败保留上次缓存。
     */
    List<String> discoverHosts() {
        long now = System.currentTimeMillis();
        if (now - lastDiscoveryAttempt < DISCOVERY_RETRY_MS) {
            return discoveredHosts;
        }
        lastDiscoveryAttempt = now;
        try {
            Resp resp = http(buildGet(PUBLISH_HOST, "/check.js", null));
            List<String> hosts = new ArrayList<>();
            Matcher matcher = CHECK_JS_URLS.matcher(StringUtils.defaultString(resp.body()));
            while (matcher.find()) {
                String host = SiteSearchSupport.normalizeHost(matcher.group(1), "");
                if (!host.isEmpty()) {
                    hosts.add(host);
                }
            }
            if (!hosts.isEmpty()) {
                log.info("观影发布页发现 {} 个最新镜像", hosts.size());
                discoveredHosts = List.copyOf(hosts);
            } else {
                log.debug("观影发布页未解析到镜像清单(改版?)");
            }
        } catch (Exception e) {
            log.debug("guanying publish page discovery failed: {}", e.getMessage());
        }
        return discoveredHosts;
    }

    /** 发布页新发现且本轮未试过的镜像(剔除 orderedHosts 含粘滞);自定义站点列表不自动混入;风控熔断中换镜像无意义。 */
    private List<String> freshDiscoveredHosts(Config config) {
        if (powBreakActive() || !usesDefaultHosts(config)) {
            return List.of();
        }
        List<String> known = orderedHosts(config);
        List<String> fresh = new ArrayList<>();
        for (String host : discoverHosts()) {
            if (!known.contains(host)) {
                fresh.add(host);
            }
        }
        return fresh;
    }

    /** 自定义站点列表(可能指向私有部署)不自动混入官方镜像,防登录 Cookie 跨实例泄漏。 */
    private boolean usesDefaultHosts(Config config) {
        return config.hosts().containsAll(normalizeHosts(""));
    }

    private String requestText(Config config, String path, Map<String, String> params) {
        Resp resp = requestResponse(config, path, params);
        return resp == null ? "" : StringUtils.defaultString(resp.body());
    }

    private JsonNode requestJson(Config config, String path, Map<String, String> params) {
        Resp resp = requestResponse(config, path, params);
        if (resp == null) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(StringUtils.defaultString(resp.body()));
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private Request buildGet(String host, String path, Map<String, String> params) {
        StringBuilder url = new StringBuilder(host).append('/').append(StringUtils.stripStart(path, "/"));
        if (params != null && !params.isEmpty()) {
            String sep = url.indexOf("?") >= 0 ? "&" : "?";
            for (Map.Entry<String, String> entry : params.entrySet()) {
                url.append(sep).append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=').append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                sep = "&";
            }
        }
        return new Request.Builder()
                .url(url.toString())
                .header("User-Agent", MOBILE_UA)
                .header("Accept", ACCEPT)
                .header("Referer", host + "/")
                .header("Cookie", cookieHeader())
                .build();
    }

    private List<String> orderedHosts(Config config) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(activeHost)) {
            ordered.add(activeHost);
        }
        ordered.addAll(config.hosts());
        return List.copyOf(ordered);
    }

    // ---------- PoW ----------

    /** 挑战页特征(py _detect_challenge);注意 _obj. 在场即为正常数据页,先于 filejin 判定。 */
    boolean detectChallenge(String text) {
        String body = StringUtils.defaultString(text);
        String stripped = body.strip();
        if (stripped.isEmpty()) {
            return false;
        }
        if (stripped.startsWith("{")) {
            try {
                JsonNode payload = objectMapper.readTree(stripped);
                if (payload.path("code").asInt(0) == 419) {
                    return true;
                }
                if (payload.path("refresh").asInt(0) == 1 && payload.path("msg").asText("").contains("验证")) {
                    return true;
                }
            } catch (Exception ignored) {
                // 非法 JSON 走文本特征
            }
        }
        if (body.contains("浏览器验证已过期") || CODE_419.matcher(body).find()) {
            return true;
        }
        if (body.contains("_obj.")) {
            return false;
        }
        return body.contains("filejin") || body.contains("pow.worker") || body.contains("浏览器安全验证");
    }

    /** y = x^(2^t) mod N(BigInteger.modPow,等价于 Python pow(x, 1<<t, N))。 */
    static String solvePow(String nHex, String xHex, int rounds) {
        BigInteger modulus = new BigInteger(nHex, 16);
        if (modulus.signum() <= 0) {
            throw new IllegalArgumentException("invalid PoW modulus");
        }
        if (rounds < 0 || rounds > MAX_POW_ROUNDS) {
            throw new IllegalArgumentException("invalid PoW round count");
        }
        return new BigInteger(xHex, 16).modPow(BigInteger.ONE.shiftLeft(rounds), modulus).toString(16);
    }

    private synchronized boolean ensurePow(Config config, String host, boolean force) {
        if (System.currentTimeMillis() < powBreakUntil) {
            return false;
        }
        try {
            if (force) {
                dropCookies("browser_pow", "browser_verified");
            }
            Resp home = http(buildGet(host, "/", null));
            mergeCookies(home.setCookies());
            if (!detectChallenge(home.body())) {
                return home.code() < 500;
            }
            Resp challenge = http(buildGet(host, "/res/pow", null));
            mergeCookies(challenge.setCookies());
            JsonNode payload = objectMapper.readTree(StringUtils.defaultString(challenge.body()));
            String y = solvePow(payload.path("N").asText(""), payload.path("x").asText(""),
                    payload.path("t").asInt(-1));
            Resp verified = http(new Request.Builder()
                    .url(host + "/res/pow")
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept", ACCEPT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Origin", host)
                    .header("Referer", host + "/")
                    .header("Cookie", cookieHeader())
                    .post(new FormBody.Builder().add("y", y).build())
                    .build());
            mergeCookies(verified.setCookies());
            if (objectMapper.readTree(StringUtils.defaultString(verified.body())).path("success").asBoolean(false)) {
                dropCookies("browser_pow");
                return true;
            }
        } catch (Exception e) {
            log.debug("guanying pow failed on {}: {}", host, e.getMessage());
        }
        return false;
    }

    // ---------- 登录 ----------

    private boolean isNotLoggedIn(String body) {
        String text = StringUtils.defaultString(body).toLowerCase();
        return text.contains("nologin") || body.contains("未登录");
    }

    private boolean ensureAuth(Config config) {
        if (hasAuthCookie()) {
            return true;
        }
        return login(config);
    }

    private boolean reauthenticate(Config config) {
        return login(config);
    }

    private synchronized boolean login(Config config) {
        lastLoginError = "";
        loginRoundError = "";
        if (loginCooldown.blocked() || !config.canLogin()) {
            return false;
        }
        // 镜像 A 记录秒级轮换,任一镜像可能正解析到 0.0.0.0:逐镜像尝试,坏镜像不阻断登录
        LoginOutcome outcome = loginAcross(orderedHosts(config), config);
        if (outcome == LoginOutcome.UNREACHABLE) {
            // 已知镜像全失败:域名可能又轮换/退役了,从发布页自发现最新清单再试一轮
            List<String> fresh = freshDiscoveredHosts(config);
            if (!fresh.isEmpty()) {
                outcome = loginAcross(fresh, config);
            }
        }
        if (outcome == LoginOutcome.SUCCESS) {
            return true;
        }
        return loginFailed(StringUtils.defaultIfBlank(loginRoundError, "站点不可达"));
    }

    /** 单轮登录尝试:SUCCESS 成功 / REJECTED 站点明确拒绝(验证码/账密被拒,换镜像无意义)/ UNREACHABLE 全不可达。 */
    private LoginOutcome loginAcross(List<String> hosts, Config config) {
        String lastError = "";
        for (String host : hosts) {
            try {
                if (!ensurePow(config, host, false)) {
                    lastError = powBreakActive() ? POW_REJECTED_CAUSE : "PoW 验证未通过";
                    continue;
                }
                // 登录页 GET 下发的会话 Cookie(如 PHPSESSID/csrf)必须并入全局态再发登录 POST,
                // 否则要求登录页会话的站点会永远拒绝账号密码(其余每次 http() 都 mergeCookies,唯独这里漏了)
                Resp page = http(buildGet(host, "/user/login/", null));
                mergeCookies(page.setCookies());
                Resp resp = http(new Request.Builder()
                        .url(host + "/user/login")
                        .header("User-Agent", MOBILE_UA)
                        .header("Accept", ACCEPT)
                        .header("Referer", host + "/user/login/")
                        .header("Cookie", cookieHeader())
                        .post(new FormBody.Builder()
                                .add("code", "")
                                .add("siteid", "1")
                                .add("dosubmit", "1")
                                .add("cookietime", "10506240")
                                .add("username", config.username())
                                .add("password", config.password())
                                .build())
                        .build());
                mergeCookies(resp.setCookies());
                JsonNode payload = objectMapper.readTree(StringUtils.defaultString(resp.body()));
                if (payload.path("code").asInt(0) == 200) {
                    log.info("观影登录成功(username={})", config.username());
                    if (config.cookie().isBlank()) {
                        persistSession();
                    }
                    return LoginOutcome.SUCCESS;
                }
                if (payload.path("captcha").asBoolean(false)) {
                    loginRoundError = "触发点选验证码,请改配 Cookie(" + COOKIE_SETTING + ")";
                    return LoginOutcome.REJECTED;
                }
                loginRoundError = "账号密码被拒绝:" + payload.path("msg").asText(payload.path("message").asText(""));
                return LoginOutcome.REJECTED;
            } catch (Exception e) {
                lastError = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
                log.debug("guanying login failed on {}: {}", host, lastError);
            }
        }
        loginRoundError = StringUtils.defaultIfBlank(lastError, "站点不可达");
        return LoginOutcome.UNREACHABLE;
    }

    private boolean loginFailed(String reason) {
        // 走到登录说明旧会话已被判 nologin 或从未建立,过期会话不得留在库里等重启回灌
        lastLoginError = reason;
        evictSession();
        return loginCooldown.fail("观影", reason, LOGIN_COOLDOWN_MS);
    }

    /** 登录态快照落库(剔 PoW 短时效 Cookie):失败只告警不阻断(大不了下次重启重登)。值含凭证绝不进日志。 */
    private void persistSession() {
        String snapshot;
        synchronized (cookies) {
            Map<String, String> auth = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (!TRANSIENT_COOKIES.contains(entry.getKey().toLowerCase())) {
                    auth.put(entry.getKey(), entry.getValue());
                }
            }
            snapshot = SiteSearchSupport.joinCookies(auth);
        }
        if (snapshot.isBlank()) {
            return;
        }
        try {
            settingRepository.save(new Setting(SESSION_SETTING, snapshot));
        } catch (Exception e) {
            log.warn("观影会话持久化失败(下次重启需重新登录):{}", e.getMessage());
        }
    }

    private void evictSession() {
        try {
            settingRepository.save(new Setting(SESSION_SETTING, ""));
        } catch (Exception e) {
            log.debug("观影过期会话清除失败: {}", e.getMessage());
        }
    }

    // ---------- Cookie 状态 ----------

    private void seedConfigCookie(Config config) {
        if (seededConfigCookie) {
            return;
        }
        synchronized (cookies) {
            if (!seededConfigCookie) {
                for (Map.Entry<String, String> entry : parseCookieHeader(config.cookie()).entrySet()) {
                    // PoW 短时效标记不是凭证:浏览器整串复制来的陈旧值会遮蔽本轮求解换来的新值,不播种
                    if (!TRANSIENT_COOKIES.contains(entry.getKey().toLowerCase())) {
                        cookies.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                }
                // 播种落库会话只在进程生命周期发生一次:被 nologin 换掉/清除后的内存态不回灌旧 Cookie
                if (config.cookie().isBlank()) {
                    String persisted = SiteSearchSupport.setting(settingRepository, SESSION_SETTING);
                    if (!persisted.isBlank()) {
                        for (Map.Entry<String, String> entry : parseCookieHeader(persisted).entrySet()) {
                            cookies.putIfAbsent(entry.getKey(), entry.getValue());
                        }
                        log.info("观影复用持久化登录态(免重登)");
                    }
                }
                seededConfigCookie = true;
            }
        }
    }

    private String cookieHeader() {
        synchronized (cookies) {
            return SiteSearchSupport.joinCookies(cookies);
        }
    }

    /** Set-Cookie 合并:Max-Age<=0 或值 deleted/空 = 删除,其余覆盖(保序)。 */
    private void mergeCookies(List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) {
            return;
        }
        synchronized (cookies) {
            for (String header : setCookies) {
                String pair = StringUtils.substringBefore(header, ";").trim();
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                boolean deleted = value.isEmpty() || "deleted".equalsIgnoreCase(value);
                Matcher maxAge = MAX_AGE_ATTR.matcher(header);
                if (maxAge.find()) {
                    try {
                        deleted = deleted || Integer.parseInt(maxAge.group(1).trim()) <= 0;
                    } catch (NumberFormatException ignored) {
                        // 非法 max-age 忽略
                    }
                }
                if (deleted) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, value);
                }
            }
        }
    }

    private static final Pattern MAX_AGE_ATTR = Pattern.compile("(?i)max-age\\s*=\\s*(-?\\d+)");

    private void dropCookies(String... names) {
        synchronized (cookies) {
            for (String name : names) {
                cookies.remove(name);
            }
        }
    }

    private boolean hasAuthCookie() {
        synchronized (cookies) {
            return cookies.keySet().stream().anyMatch(name -> !TRANSIENT_COOKIES.contains(name.toLowerCase()));
        }
    }

    private static Map<String, String> parseCookieHeader(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String chunk : StringUtils.defaultString(value).split(";")) {
            String pair = chunk.trim();
            int eq = pair.indexOf('=');
            if (eq > 0) {
                result.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return result;
    }

    // ---------- Cookie 有效性检查(网页设置页) ----------

    /**
     * 凭证有效性检查:Cookie 形态过 PoW 后 GET {@code /} —— 匿名/失效会返回
     * 「未登录，访问受限」页(2026-09-20 实测,与搜索链路的 nologin 判定同特征),
     * 200 且无 nologin 即登录态正常;Cookie 未填且带账号密码时实测登录验证(与搜索
     * 自动登录同链路,成功建立并保存会话)。镜像按粘滞线路优先逐个尝试(www 域名
     * TTL≈1s 轮换,任一时刻部分镜像解析到 0.0.0.0 下线中),全失败按原因分组计数回显
     * 并从官方发布页(挂了.com)自发现最新镜像再试一轮,不再只报最后一个镜像的原始
     * 异常(曾让 8 镜像瞬时全死被误读为域名无法识别)。PoW 已被站点收账却仍被要求
     * 验证 = 反爬不认账(2026-09-21 根因=Accept-Language 头触发,已移除请求族;此态再现即
     * 反爬规则又变),立即熔断不再打剩余镜像(同一后端)。
     * 校验请求参数里的表单当前值;Cookie 形态只读探测,不动运行态登录 Cookie
     * (PoW 通过标记是匿名风控标记,随请求补带)。
     */
    public SiteCredentialCheckResult checkCredential(SiteCredentialCheckRequest request) {
        String cookie = StringUtils.trimToEmpty(request.cookie());
        String username = StringUtils.trimToEmpty(request.username());
        String password = StringUtils.trimToEmpty(request.password());
        if (cookie.isEmpty()) {
            if (username.isEmpty() || password.isEmpty()) {
                return new SiteCredentialCheckResult("guanying", false, "未填写 Cookie 或账号密码");
            }
            Config config = new Config(normalizeHosts(request.host()), username, password, "");
            if (loginCooldown.blocked()) {
                return new SiteCredentialCheckResult("guanying", false, "登录冷却中(前次失败),请稍后再试");
            }
            if (!login(config)) {
                return new SiteCredentialCheckResult("guanying", false,
                        "账号密码登录失败:" + StringUtils.defaultIfBlank(lastLoginError, "站点拒绝"));
            }
            return new SiteCredentialCheckResult("guanying", true, "账号密码可用(已登录并保存会话)");
        }
        List<String> hosts = normalizeHosts(request.host());
        Config config = new Config(hosts, "", "", cookie);
        Map<String, Integer> causes = new LinkedHashMap<>();
        SiteCredentialCheckResult result = probeCookieRound(orderedHosts(config), config, cookie, causes);
        if (result == null) {
            // 已知镜像全失败:域名可能又轮换/退役了,从发布页自发现最新清单再试一轮
            List<String> fresh = freshDiscoveredHosts(config);
            if (!fresh.isEmpty()) {
                result = probeCookieRound(fresh, config, cookie, causes);
            }
        }
        if (result != null) {
            return result;
        }
        int attempted = causes.values().stream().mapToInt(Integer::intValue).sum();
        return new SiteCredentialCheckResult("guanying", false,
                "全部 " + attempted + " 个站点地址探测失败:" + summarizeCauses(causes));
    }

    /** Cookie 探测一轮:命中登录态/失效立即返回结果,全失败返回 null(失败原因记入 causes)。 */
    private SiteCredentialCheckResult probeCookieRound(List<String> hosts, Config config, String cookie,
            Map<String, Integer> causes) {
        for (String host : hosts) {
            try {
                Resp resp = http(buildCheckGet(host, "/", checkCookieHeader(cookie)));
                String body = StringUtils.defaultString(resp.body());
                if (detectChallenge(body)) {
                    if (!ensurePow(config, host, false)) {
                        if (powBreakActive()) {
                            causes.merge(POW_REJECTED_CAUSE, 1, Integer::sum);
                            break;
                        }
                        causes.merge("PoW 求解/提交失败", 1, Integer::sum);
                        continue;
                    }
                    resp = http(buildCheckGet(host, "/", checkCookieHeader(cookie)));
                    body = StringUtils.defaultString(resp.body());
                    if (detectChallenge(body)) {
                        // PoW 已被站点收账(success:true)却仍要求验证 = 反爬不认账
                        // (2026-09-21 实测根因=请求带 Accept-Language,任何值都拦、不带则放行,
                        // 已移除;此态再现说明反爬规则又变);所有镜像同一后端,熔断立即停止
                        tripPowBreak();
                        causes.merge(POW_REJECTED_CAUSE, 1, Integer::sum);
                        break;
                    }
                }
                if (resp.code() == 200) {
                    if (isNotLoggedIn(body)) {
                        return new SiteCredentialCheckResult("guanying", false, "Cookie 已失效(站点判定未登录)");
                    }
                    return new SiteCredentialCheckResult("guanying", true, "Cookie 有效(登录态正常)");
                }
                causes.merge("HTTP " + resp.code(), 1, Integer::sum);
            } catch (Exception e) {
                causes.merge(failureCause(e), 1, Integer::sum);
                log.debug("guanying credential check failed on {}: {}", host, e.getMessage());
            }
        }
        return null;
    }

    /** 聚合报错的单条原因:空消息退异常类名,长消息(OkHttp 连接串含 host)截断防刷屏。 */
    private static String failureCause(Exception e) {
        String msg = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
        return StringUtils.abbreviate(msg, 60);
    }

    /** 原因计数汇总:N×原因 顿号连接;空(防御,PoW 失败路径也会记账)给通用文案。 */
    private static String summarizeCauses(Map<String, Integer> causes) {
        if (causes.isEmpty()) {
            return "站点不可达";
        }
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, Integer> entry : causes.entrySet()) {
            if (summary.length() > 0) {
                summary.append('、');
            }
            summary.append(entry.getValue()).append('×').append(entry.getKey());
        }
        return summary.toString();
    }

    /** 校验请求的 Cookie 头:用户提供的 Cookie + 运行态 PoW 通过标记(匿名标记,非登录态)。 */
    private String checkCookieHeader(String provided) {
        Map<String, String> merged = parseCookieHeader(provided);
        // 表单/浏览器整串复制来的 browser_verified/browser_pow 是绑定其浏览器挑战的陈旧短时效值,
        // 会遮蔽本轮 PoW 刚换来的新值导致重访必被挑战(2026-09-21 实测真凶),一律剔除以运行态为准
        merged.keySet().removeIf(name -> TRANSIENT_COOKIES.contains(name.toLowerCase()));
        synchronized (cookies) {
            for (String name : List.of("browser_verified", "browser_pow")) {
                String value = cookies.get(name);
                if (value != null) {
                    merged.put(name, value);
                }
            }
        }
        return SiteSearchSupport.joinCookies(merged);
    }

    /** 校验专用 GET 构造:与搜索管线同头族,但 Cookie 用显式传入值(不带运行态登录 Cookie)。 */
    private Request buildCheckGet(String host, String path, String cookieHeader) {
        return new Request.Builder()
                .url(host + '/' + StringUtils.stripStart(path, "/"))
                .header("User-Agent", MOBILE_UA)
                .header("Accept", ACCEPT)
                .header("Referer", host + "/")
                .header("Cookie", cookieHeader)
                .build();
    }

    // ---------- 解析 ----------

    /** 搜索页内嵌 _obj.search 平行数组(py _parse_search)。 */
    List<Item> parseSearch(String html) {
        Matcher matcher = SEARCH_OBJ.matcher(StringUtils.defaultString(html));
        if (!matcher.find()) {
            return List.of();
        }
        try {
            JsonNode l = objectMapper.readTree(matcher.group(1)).path("l");
            return buildItems(l.path("i"), l.path("title"), l.path("d"), l.path("year"), l.path("info"));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 搜索建议 JSON 回退(py _parse_search_suggest)。 */
    List<Item> parseSearchSuggest(JsonNode payload) {
        if (payload == null || !payload.isArray()) {
            return List.of();
        }
        List<Item> items = new ArrayList<>();
        for (JsonNode entry : payload) {
            String rid = entry.path("id").asText("").trim();
            String title = entry.path("title").asText("").trim();
            String dtype = entry.path("dir").asText("mv").trim();
            if (rid.isEmpty() || title.isEmpty() || !List.of("mv", "tv", "ac").contains(dtype)) {
                continue;
            }
            String year = entry.path("year").asText("").trim();
            items.add(new Item(dtype, rid, title, year));
        }
        return items;
    }

    private List<Item> buildItems(JsonNode ids, JsonNode titles, JsonNode types, JsonNode years, JsonNode infos) {
        List<Item> items = new ArrayList<>();
        for (int idx = 0; idx < ids.size(); idx++) {
            String rid = ids.get(idx).asText("").trim();
            String title = titles.path(idx).asText("").trim();
            if (rid.isEmpty() || title.isEmpty()) {
                continue;
            }
            String dtype = types.path(idx).asText("").trim();
            if (dtype.isEmpty()) {
                dtype = "mv";
            }
            String year = years.path(idx).asText("").trim();
            String remarks = infos.path(idx).asText("").trim();
            items.add(new Item(dtype, rid, title, remarks.isEmpty() ? year : remarks));
        }
        return items;
    }

    /** 结构化提取码折进 ?password=(已有 pwd=/password=/passcode= 不重复折,py _append_password)。 */
    static String appendPassword(String url, String code) {
        return SiteSearchSupport.appendPasswordParam(url, code, "password=");
    }

    private static final Pattern MAGNET_NAME_PREFIX = Pattern.compile("^[^一-龥A-Za-z0-9【\\[]+");

    /**
     * 详情磁力种子产出(py _build_play_fields 磁力段):{@code downlist.list.m} 是 btih 哈希
     * 数组、{@code t} 是种子名平行数组,折成 {@code magnet:?xt=urn:btih:{hash}&dn={种子名}}。
     * dn 是磁力候选的标题口径(集号解析/排除词门禁消费),长度不足 8 的哈希不是有效 btih 跳过。
     */
    static List<Message> magnetsFromDetail(JsonNode detail, Item item) {
        JsonNode list = detail.path("downlist").path("list");
        JsonNode hashes = list.path("m");
        JsonNode names = list.path("t");
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < hashes.size(); i++) {
            String hash = hashes.get(i).asText("").trim().toLowerCase(Locale.ROOT);
            if (hash.length() < 8) {
                continue;
            }
            String name = cleanMagnetName(names.path(i).asText(""));
            StringBuilder link = new StringBuilder("magnet:?xt=urn:btih:").append(hash);
            if (!name.isEmpty()) {
                link.append("&dn=").append(URLEncoder.encode(name, StandardCharsets.UTF_8));
            }
            Message message = new Message();
            message.setType("magnet");
            message.setLink(link.toString());
            message.setName(item.title());
            message.setChannel("观影");
            message.setContent(name.isEmpty() ? "磁力" : name);
            messages.add(message);
        }
        return messages;
    }

    /** 磁力种子名清洗(py _clean_name):剥开头杂符(emoji/引导符),压空白。 */
    static String cleanMagnetName(String value) {
        String text = MAGNET_NAME_PREFIX.matcher(StringUtils.trimToEmpty(value)).replaceAll("");
        return text.replaceAll("\\s+", " ").trim();
    }

    // ---------- 配置 ----------

    private Config loadConfig() {
        Config config = new Config(
                normalizeHosts(SiteSearchSupport.setting(settingRepository, HOST_SETTING)),
                SiteSearchSupport.setting(settingRepository, USERNAME_SETTING).trim(),
                SiteSearchSupport.setting(settingRepository, PASSWORD_SETTING),
                SiteSearchSupport.setting(settingRepository, COOKIE_SETTING).trim());
        seedConfigCookie(config);
        return config;
    }

    /** 站点列表:逗号/竖线/换行分隔,逐个归一化(补 scheme + IDNA)去重;空 = 内置 8 镜像。 */
    static List<String> normalizeHosts(String value) {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        for (String raw : StringUtils.defaultString(value).split("[\\s,，|]+")) {
            String host = SiteSearchSupport.normalizeHost(raw, "");
            if (!host.isEmpty()) {
                hosts.add(host);
            }
        }
        return hosts.isEmpty() ? DEFAULT_HOSTS.stream().map(host -> SiteSearchSupport.normalizeHost(host, "")).toList() : List.copyOf(hosts);
    }

    protected Resp http(Request request) throws IOException {
        OkHttpClient client = httpClient.newBuilder()
                .dns(GuanYingSearchService::resolveAliveRecords)
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            return new Resp(response.code(), response.headers("Set-Cookie"), body);
        }
    }

    /**
     * 站点镜像 A 记录秒级 TTL 轮换:任一时刻常有镜像解析到 0.0.0.0(下线中)。全 0 答案
     * 直接以可读原因失败,避免上层报 "Failed to connect to .../0.0.0.0:443" 被误读为
     * 中文域名无法识别(punycode 转换本身是好的)。
     */
    static List<InetAddress> resolveAliveRecords(String host) throws UnknownHostException {
        List<InetAddress> alive = filterAliveRecords(Dns.SYSTEM.lookup(host));
        if (alive.isEmpty()) {
            throw new UnknownHostException("域名被解析到 0.0.0.0(镜像轮换下线或被 DNS 拦截)");
        }
        return alive;
    }

    /** DNS 答案里剔 0.0.0.0/::(isAnyLocalAddress)死记录;混有真实 IP 的答案只保留真实部分。 */
    static List<InetAddress> filterAliveRecords(List<InetAddress> records) {
        List<InetAddress> alive = new ArrayList<>();
        for (InetAddress record : records) {
            if (!record.isAnyLocalAddress()) {
                alive.add(record);
            }
        }
        return alive;
    }
}
