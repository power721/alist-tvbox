package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URLEncoder;
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
 * 网盘分享聚合站,多个电影名中文域名互为镜像(教父.com/星际穿越.com 等 8 个)。
 *
 * <p>反爬两道:①PoW 工作量证明——响应出现挑战特征(JSON code=419/"浏览器安全验证"/
 * pow.worker 等,注意页面含 {@code _obj.} 即为正常数据页不算挑战)时,取 {@code /res/pow}
 * 的 {N,x,t} 算 {@code y = x^(2^t) mod N}(BigInteger.modPow)提交换 {@code browser_verified};
 * ②登录态——响应含 nologin/未登录 时用账号密码重登。<b>凭证必须用户自配</b>
 * (Setting {@code guanying_username}/{@code guanying_password} 或直接 {@code guanying_cookie},
 * 站点列表 {@code guanying_host} 可覆盖,逗号/竖线/换行分隔),未配置时本源静默关闭。
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

    private static final List<String> DEFAULT_HOSTS = List.of(
            "https://www.教父.com", "https://www.星际穿越.com", "https://www.楚门的世界.com",
            "https://www.泰坦尼克号.com", "https://www.盗梦空间.com", "https://www.肖申克的救赎.com",
            "https://www.阿甘正传.com", "https://www.黑客帝国.com");
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

    public GuanYingSearchService(SettingRepository settingRepository, ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.objectMapper = objectMapper;
    }

    private record Config(List<String> hosts, String username, String password, String cookie) implements SiteCredentials {
    }

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
            log.warn("guanying search [{}] failed: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    // ---------- 请求管线:多镜像 failover + PoW/登录自动恢复 ----------

    private Resp requestResponse(Config config, String path, Map<String, String> params) {
        for (String host : orderedHosts(config)) {
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
                    if (powRetried || !ensurePow(config, host, true)) {
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
        }
        return null;
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
                .header("Accept-Language", "zh-CN,zh;q=0.9")
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
        if (loginCooldown.blocked() || !config.canLogin()) {
            return false;
        }
        try {
            if (!ensurePow(config, config.hosts().get(0), false)) {
                return loginFailed("PoW 验证未通过");
            }
            // 登录页 GET 下发的会话 Cookie(如 PHPSESSID/csrf)必须并入全局态再发登录 POST,
            // 否则要求登录页会话的站点会永远拒绝账号密码(其余每次 http() 都 mergeCookies,唯独这里漏了)
            Resp page = http(buildGet(config.hosts().get(0), "/user/login/", null));
            mergeCookies(page.setCookies());
            Resp resp = http(new Request.Builder()
                    .url(config.hosts().get(0) + "/user/login")
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept", ACCEPT)
                    .header("Referer", config.hosts().get(0) + "/user/login/")
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
                return true;
            }
            if (payload.path("captcha").asBoolean(false)) {
                return loginFailed("触发点选验证码,请改配 Cookie(" + COOKIE_SETTING + ")");
            }
            return loginFailed("账号密码被拒绝:" + payload.path("msg").asText(payload.path("message").asText("")));
        } catch (Exception e) {
            return loginFailed(e.getMessage());
        }
    }

    private boolean loginFailed(String reason) {
        return loginCooldown.fail("观影", reason, LOGIN_COOLDOWN_MS);
    }

    // ---------- Cookie 状态 ----------

    private void seedConfigCookie(Config config) {
        if (seededConfigCookie) {
            return;
        }
        synchronized (cookies) {
            if (!seededConfigCookie) {
                for (Map.Entry<String, String> entry : parseCookieHeader(config.cookie()).entrySet()) {
                    cookies.putIfAbsent(entry.getKey(), entry.getValue());
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
