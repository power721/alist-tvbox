package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 盘链搜索源(atv-spiders/py/盘链.py 的 Java 移植,追剧搜索源之一):盘链是需登录的
 * 网盘分享聚合站,JSON API 搜索({@code /api/get_videos.php})与取盘链
 * ({@code /api/search_pan_links.php});链接可能是直链(结构化 password 折进
 * {@code pwd=/password=} 参数)或 token(经 {@code /api/go.php} 302 跳转解析出真实分享链)。
 * links 里的 magnet/ed2k(直连或 token 302 解出)一并产出离线候选条目,供追剧磁力兜底
 * 在 fillPool 的 NON_PAN 收割 —— 兜底未开时由定向集闸门统一剔除。
 *
 * <p><b>凭证必须用户自配</b>(Setting {@code panlian_username}/{@code panlian_password}
 * 或直接 {@code panlian_cookie},站点可 {@code panlian_host} 覆盖)——不内置任何共享账号;
 * 未配置时本源静默关闭。账号密码登录:multipart POST {@code /api/login.php}(先取登录页
 * 会话 Cookie),Cookie 内存缓存,失效自动重登;连续失败 5 分钟冷却防撞墙。
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
    /** 每次搜索最多取多少个条目的盘链(控制站点压力) */
    private static final int MAX_DETAIL_ITEMS = 3;
    /** 登录失败冷却:防止账号错误/站点故障时每轮巡检都撞登录接口 */
    private static final long LOGIN_COOLDOWN_MS = 5 * 60_000L;
    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    /** 详情 keyword 去掉片名尾部语言标注(py _build_keyword) */
    private static final Pattern KEYWORD_LANG_SUFFIX = Pattern.compile("[\\s\\u3000]*(国语|粤语)$");
    /** 分享 URL 尾部内联提取码噪声(py EXTRACT_CODE_NOISE_RE) */
    private static final Pattern EXTRACT_CODE_NOISE = Pattern.compile("(?i)([?？]?\\s*(提取码|访问码|密码)[:：]\\s*[a-z0-9]{4,8})+$");

    private final SettingRepository settingRepository;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient();
    /** 登录态 Cookie:配置 Cookie 直接用,账号密码登录后内存缓存 */
    private volatile String sessionCookie = "";
    private final LoginCooldown loginCooldown = new LoginCooldown();
    private volatile boolean warnedNoCredentials;

    public PanLianSearchService(SettingRepository settingRepository, ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.objectMapper = objectMapper;
    }

    private record Config(String host, String username, String password, String cookie) implements SiteCredentials {
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
            JsonNode payload = getJson(config, "/api/get_videos.php", Map.of(
                    "wd", keyword.trim(),
                    "pg", "1"));
            if (isLoginRequired(payload)) {
                sessionCookie = "";
                if (!ensureSession(config)) {
                    return List.of();
                }
                payload = getJson(config, "/api/get_videos.php", Map.of(
                        "wd", keyword.trim(),
                        "pg", "1"));
                if (isLoginRequired(payload)) {
                    return List.of();
                }
            }
            if (payload.path("code").asInt(0) != 1) {
                return List.of();
            }

            List<Message> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int details = 0;
            for (JsonNode item : payload.path("list")) {
                if (details >= MAX_DETAIL_ITEMS) {
                    break;
                }
                String vodId = item.path("vod_id").asText(item.path("id").asText("")).trim();
                String vodName = item.path("vod_name").asText(item.path("name").asText("")).trim();
                if (vodId.isEmpty() || vodName.isEmpty()) {
                    continue;
                }
                String remarks = item.path("vod_remarks").asText(item.path("remarks").asText("")).trim();
                Map<String, String> params = new LinkedHashMap<>();
                params.put("vod_id", vodId);
                params.put("_t", String.valueOf(System.currentTimeMillis()));
                String detailKeyword = buildKeyword(vodName);
                if (!detailKeyword.isEmpty()) {
                    params.put("keyword", detailKeyword);
                }
                JsonNode linksPayload = getJson(config, "/api/search_pan_links.php", params);
                details++;
                if (isLoginRequired(linksPayload)) {
                    sessionCookie = "";
                    if (ensureSession(config)) {
                        linksPayload = getJson(config, "/api/search_pan_links.php", params);
                    }
                }
                if (!linksPayload.path("success").asBoolean(false)) {
                    continue;
                }
                for (Message message : messagesFromGroups(config, linksPayload.path("data"), vodName, remarks)) {
                    if (seen.add(message.getLink())) {
                        result.add(message);
                    }
                }
            }
            log.info("PanLian search {} get {} results", keyword, result.size());
            return result;
        } catch (Exception e) {
            log.warn("panlian search [{}] failed: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    private List<Message> messagesFromGroups(Config config, JsonNode data, String vodName, String remarks) {
        List<Message> messages = new ArrayList<>();
        if (!data.isObject()) {
            return messages;
        }
        data.fieldNames().forEachRemaining(groupKey -> {
            for (JsonNode link : data.path(groupKey).path("links")) {
                String directUrl = link.path("url").asText("").trim();
                String token = link.path("token").asText("").trim();
                String raw = !directUrl.isEmpty() ? directUrl : token;
                if (raw.isEmpty()) {
                    continue;
                }
                String password = link.path("password").asText("").trim();
                String url = directUrl.isEmpty()
                        ? resolveTokenUrl(config, raw, password)
                        : foldPassword(cleanShareUrl(raw), password);
                if (StringUtils.isBlank(url)) {
                    continue;
                }
                String type = Message.parseType(url);
                if (type == null || !SiteSearchSupport.isNumeric(type)) {
                    // 网盘只留可挂载分享;magnet/ed2k(token 也可能 302 到磁力)作离线候选产出,
                    // 由追剧定向集闸门裁决 —— 兜底未开时在 searchAllSources 统一剔除
                    if (StringUtils.startsWithIgnoreCase(url, "magnet:")
                            || StringUtils.startsWithIgnoreCase(url, "ed2k:")) {
                        messages.add(offlineMessage(vodName, url,
                                cleanLinkTitle(link.path("title").asText(""))));
                    }
                    continue;
                }
                Message message = new Message();
                message.setType(type);
                message.setLink(url);
                message.setName(vodName);
                message.setChannel("盘链");
                message.setContent((vodName + " " + StringUtils.defaultString(remarks)).trim());
                messages.add(message);
            }
        });
        return messages;
    }

    private static final Pattern LINK_TITLE_INTRO = Pattern.compile("\\s*[·•]\\s*介绍[:：].*$");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    /** 资源标题清洗(py _clean_link_title):剥「介绍:」尾巴与 HTML 标签。 */
    static String cleanLinkTitle(String value) {
        String text = HTML_TAG.matcher(LINK_TITLE_INTRO.matcher(StringUtils.trimToEmpty(value)).replaceAll("")).replaceAll("");
        return text.trim();
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

    /** token 链接经 /api/go.php 302 解析真实分享链(手机 UA + skip_go_warning 免确认页)。 */    private String resolveTokenUrl(Config config, String token, String password) {
        String goUrl = config.host() + "/api/go.php?t=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", MOBILE_UA);
        headers.put("Referer", config.host() + "/");
        headers.put("Cookie", goCookie());
        String resolved = resolveRedirect(goUrl, headers);
        if (StringUtils.isBlank(resolved) || resolved.equals(goUrl) || resolved.startsWith(config.host())) {
            log.debug("panlian go.php resolve failed for token {}", token);
            return "";
        }
        return foldPassword(cleanShareUrl(resolved), password);
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

    static String buildKeyword(String vodName) {
        return KEYWORD_LANG_SUFFIX.matcher(StringUtils.trimToEmpty(vodName)).replaceAll("").trim();
    }

    /** code==-1 或 success=false 且报文含"登录"(py _is_login_required)。 */
    static boolean isLoginRequired(JsonNode payload) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return false;
        }
        String text = payload.toString();
        boolean rejected = (payload.has("code") && payload.path("code").asInt(0) == -1)
                || (payload.has("success") && !payload.path("success").asBoolean(true));
        return rejected && text.contains("登录");
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
            Resp page = http(new Request.Builder()
                    .url(config.host() + "/pages/login.php")
                    .header("User-Agent", userAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-TW,zh;q=0.9,zh-CN;q=0.8,en;q=0.7")
                    .build());
            if (page.code() != 200) {
                return loginFailed("login page http " + page.code());
            }
            Map<String, String> cookies = SiteSearchSupport.parseCookies(page.setCookies());
            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("username", config.username())
                    .addFormDataPart("password", config.password())
                    .addFormDataPart("remember", "on")
                    .build();
            Resp resp = http(new Request.Builder()
                    .url(config.host() + "/api/login.php")
                    .header("User-Agent", userAgent())
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-TW,zh;q=0.9,zh-CN;q=0.8,en;q=0.7")
                    .header("Origin", config.host())
                    .header("Referer", config.host() + "/pages/login.php")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Cookie", SiteSearchSupport.joinCookies(cookies))
                    .post(body)
                    .build());
            if (resp.code() != 200) {
                return loginFailed("login api http " + resp.code());
            }
            JsonNode payload = objectMapper.readTree(StringUtils.defaultString(resp.body()));
            if (!payload.path("success").asBoolean(false)) {
                String reason = payload.path("msg").asText(payload.path("message").asText(""));
                return loginFailed("账号密码被拒绝:" + reason);
            }
            cookies.putAll(SiteSearchSupport.parseCookies(resp.setCookies()));
            String cookie = SiteSearchSupport.joinCookies(cookies);
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

    private String goCookie() {
        String raw = StringUtils.defaultString(sessionCookie);
        return raw.contains("skip_go_warning") ? raw : (raw.isBlank() ? "" : raw + "; ") + "skip_go_warning=1";
    }

    private JsonNode getJson(Config config, String path, Map<String, String> params) throws IOException {
        StringBuilder url = new StringBuilder(config.host()).append(path);
        String sep = "?";
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append(sep).append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=').append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            sep = "&";
        }
        Resp resp = http(new Request.Builder()
                .url(url.toString())
                .header("User-Agent", userAgent())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-TW,zh;q=0.9,zh-CN;q=0.8,en;q=0.7")
                .header("Origin", config.host())
                .header("Referer", config.host() + "/all-videos.php")
                .header("Cookie", StringUtils.defaultString(sessionCookie))
                .build());
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

    /** 单跳读 Location(go.php 解析用,可覆写供单测打桩):不自动跟随 —— OkHttp 跟随跨域重定向
     * 会把手工设置的盘链会话 Cookie 头原样带到目标网盘域(pan.baidu.com 等),会话 token 泄给第三方。 */
    protected String resolveRedirect(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url);
        headers.forEach(builder::header);
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        try (Response response = client.newCall(builder.build()).execute()) {
            String location = response.header("Location");
            return StringUtils.isNotBlank(location) ? location : response.request().url().toString();
        } catch (Exception e) {
            return "";
        }
    }
}
