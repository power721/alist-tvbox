package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.qqmusic.QqMusicLoginStatus;
import cn.har01d.alist_tvbox.dto.qqmusic.QqMusicQrCode;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ音乐扫码登录与凭据保活。
 *
 * <p>扫码：QQ 链路 ptqrshow 取二维码与 qrsig → ptqrlogin 轮询（ptqrtoken=hash33(qrsig)）→
 * check_sig 换 p_skey → graph.qq.com OAuth 授权取 code → musicu.fcg QQConnectLogin 换凭据；
 * 微信链路 qrconnect 取 uuid → 长轮询取 wx_code → musicu.fcg music.login 换凭据。
 * 凭据以 JSON 返回给前端，由前端写入订阅源 extend（即爬虫 init 的 data），
 * 与 py/QQ音乐.py 内置的 PySide6 扫码流程写入的格式一致。</p>
 *
 * <p>保活：凭据（musickey 等）约3天过期，每天定时刷新所有 QQ音乐插件凭据并写回 extend，
 * 爬虫随后任何一次 init 都会拿到最新凭据，无需自身维护刷新状态。</p>
 */
@Slf4j
@Service
public class QqMusicService {
    private static final String QQ_QR_URL = "https://ssl.ptlogin2.qq.com/ptqrshow";
    private static final String QQ_CHECK_URL = "https://ssl.ptlogin2.qq.com/ptqrlogin";
    private static final String QQ_AUTHORIZE_URL = "https://ssl.ptlogin2.graph.qq.com/check_sig";
    private static final String QQ_OAUTH_URL = "https://graph.qq.com/oauth2.0/authorize";
    private static final String WX_QR_URL = "https://open.weixin.qq.com/connect/qrconnect";
    private static final String WX_CHECK_URL = "https://lp.open.weixin.qq.com/connect/l/qrconnect";
    private static final String WX_QR_IMAGE_URL = "https://open.weixin.qq.com/connect/qrcode/";
    private static final String MUSIC_API_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String WX_APP_ID = "wx48db31d50e334801";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";

    private static final Pattern PTUI_CB = Pattern.compile("ptuiCB\\((.*?)\\)");
    private static final Pattern WX_CODE = Pattern.compile("window\\.wx_errcode=(\\d+);window\\.wx_code='([^']*)'");
    private static final Pattern WX_UUID = Pattern.compile("uuid=(.+?)\"");

    private static final long SESSION_TTL_MS = 5 * 60 * 1000;
    private static final int MAX_SESSIONS = 50;

    private final ObjectMapper objectMapper;
    private final PluginRepository pluginRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Random random = new Random();
    private final Map<String, QrSession> sessions = new ConcurrentHashMap<>();

    public QqMusicService(ObjectMapper objectMapper, PluginRepository pluginRepository) {
        this.objectMapper = objectMapper;
        this.pluginRepository = pluginRepository;
    }

    // ptlogin 的 ptqrtoken / g_tk 算法：与 py/QQ音乐.py 的 hash33 保持一致
    static int hash33(String value, int seed) {
        int result = seed;
        for (int i = 0; i < value.length(); i++) {
            result = (result << 5) + result + value.charAt(i);
        }
        return result & 2147483647;
    }

    public QqMusicQrCode createQrLogin(String type) throws IOException, InterruptedException {
        boolean wx = "wx".equalsIgnoreCase(type);
        QrSession session = wx ? createWxSession() : createQqSession();
        cleanupSessions();
        String key = UUID.randomUUID().toString().replace("-", "");
        sessions.put(key, session);
        return new QqMusicQrCode(key, wx ? "wx" : "qq", session.imageBase64);
    }

    public QqMusicLoginStatus checkLogin(String key) {
        QrSession session = sessions.get(key);
        if (session == null || session.isExpired()) {
            sessions.remove(key);
            return new QqMusicLoginStatus("expired", "二维码已失效，请刷新后重试", null);
        }
        if (session.credentialExtend != null) {
            return new QqMusicLoginStatus("success", "登录成功", session.credentialExtend);
        }
        if ("wx".equals(session.type)) {
            return checkWx(key, session);
        }
        return checkQq(key, session);
    }

    private QrSession createQqSession() throws IOException, InterruptedException {
        String url = QQ_QR_URL + "?appid=716027609&e=2&l=M&s=3&d=72&v=4&t=" + random.nextDouble()
                + "&daid=383&pt_3rd_aid=100497308";
        HttpResponse<byte[]> response = httpGet(url, "https://xui.ptlogin2.qq.com/", null);
        String qrsig = extractCookie(response, "qrsig");
        if (StringUtils.isBlank(qrsig) || response.body() == null || response.body().length == 0) {
            throw new IOException("获取QQ二维码失败");
        }
        QrSession session = new QrSession("qq");
        session.identifier = qrsig;
        session.imageBase64 = Base64.getEncoder().encodeToString(response.body());
        return session;
    }

    private QrSession createWxSession() throws IOException, InterruptedException {
        String url = WX_QR_URL + "?appid=" + WX_APP_ID
                + "&redirect_uri=" + URLEncoder.encode(
                "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/", StandardCharsets.UTF_8)
                + "&response_type=code&scope=snsapi_login&state=STATE"
                + "&href=https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css%23wechat_redirect";
        HttpResponse<String> response = httpGetString(url, "https://y.qq.com/", null);
        Matcher matcher = WX_UUID.matcher(response.body());
        if (!matcher.find()) {
            throw new IOException("获取微信二维码失败");
        }
        String uuid = matcher.group(1);
        HttpResponse<byte[]> image = httpGet(WX_QR_IMAGE_URL + uuid,
                "https://open.weixin.qq.com/connect/qrconnect", null);
        if (image.body() == null || image.body().length == 0) {
            throw new IOException("获取微信二维码图片失败");
        }
        QrSession session = new QrSession("wx");
        session.identifier = uuid;
        session.imageBase64 = Base64.getEncoder().encodeToString(image.body());
        return session;
    }

    private QqMusicLoginStatus checkQq(String key, QrSession session) {
        String url = QQ_CHECK_URL + "?u1=" + URLEncoder.encode("https://graph.qq.com/oauth2.0/login_jump",
                StandardCharsets.UTF_8)
                + "&ptqrtoken=" + hash33(session.identifier, 0)
                + "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052"
                + "&action=0-0-" + System.currentTimeMillis()
                + "&js_ver=20102616&js_type=1&pt_uistyle=40"
                + "&aid=716027609&daid=383&pt_3rd_aid=100497308&has_onekey=1";
        String body;
        try {
            HttpResponse<String> response = httpGetString(url, "https://xui.ptlogin2.qq.com/",
                    "qrsig=" + session.identifier);
            body = response.body();
        } catch (Exception e) {
            log.warn("QQ 音乐二维码轮询失败", e);
            return new QqMusicLoginStatus("waiting", "等待扫码", null);
        }
        String[] data = parsePtuiCallback(body);
        if (data == null || !StringUtils.isNumeric(data[0])) {
            return new QqMusicLoginStatus("failed", "登录状态异常，请重试", null);
        }
        switch (data[0]) {
            case "66":
                return new QqMusicLoginStatus("waiting", "等待扫码", null);
            case "67":
                return new QqMusicLoginStatus("scanned", "已扫码，请在手机上确认", null);
            case "65":
                sessions.remove(key);
                return new QqMusicLoginStatus("expired", "二维码已失效，请刷新后重试", null);
            case "68":
                sessions.remove(key);
                return new QqMusicLoginStatus("failed", "已拒绝登录", null);
            case "0":
                break;
            default:
                return new QqMusicLoginStatus("failed", "登录状态异常，请重试", null);
        }
        try {
            String sigx = extractParam(data[2], "ptsigx=", "&s_url");
            String uin = extractParam(data[2], "&uin=", "&service");
            if (StringUtils.isBlank(sigx) || StringUtils.isBlank(uin)) {
                throw new IOException("解析登录回调参数失败");
            }
            String code = authorizeQq(session, uin, sigx);
            session.credentialExtend = exchangeCredential("qq", code, null);
            return new QqMusicLoginStatus("success", "登录成功", session.credentialExtend);
        } catch (Exception e) {
            log.warn("QQ 音乐扫码授权失败", e);
            sessions.remove(key);
            return new QqMusicLoginStatus("failed", "登录失败，请重试", null);
        }
    }

    private QqMusicLoginStatus checkWx(String key, QrSession session) {
        String url = WX_CHECK_URL + "?uuid=" + session.identifier + "&_=" + System.currentTimeMillis();
        String body;
        try {
            HttpResponse<String> response = httpGetString(url, "https://open.weixin.qq.com/", null);
            body = response.body();
        } catch (Exception e) {
            log.warn("QQ 音乐微信二维码轮询失败", e);
            return new QqMusicLoginStatus("waiting", "等待扫码", null);
        }
        Matcher matcher = WX_CODE.matcher(body);
        if (!matcher.find()) {
            return new QqMusicLoginStatus("failed", "登录状态异常，请重试", null);
        }
        switch (matcher.group(1)) {
            case "408":
                return new QqMusicLoginStatus("waiting", "等待扫码", null);
            case "404":
                return new QqMusicLoginStatus("scanned", "已扫码，请在手机上确认", null);
            case "405":
                break;
            case "403":
                sessions.remove(key);
                return new QqMusicLoginStatus("failed", "已拒绝登录", null);
            default:
                return new QqMusicLoginStatus("failed", "登录状态异常，请重试", null);
        }
        String code = matcher.group(2);
        if (StringUtils.isBlank(code)) {
            return new QqMusicLoginStatus("failed", "登录状态异常，请重试", null);
        }
        try {
            session.credentialExtend = exchangeCredential("wx", code, WX_APP_ID);
            return new QqMusicLoginStatus("success", "登录成功", session.credentialExtend);
        } catch (Exception e) {
            log.warn("QQ 音乐微信授权失败", e);
            sessions.remove(key);
            return new QqMusicLoginStatus("failed", "登录失败，请重试", null);
        }
    }

    private String authorizeQq(QrSession session, String uin, String sigx) throws IOException, InterruptedException {
        String url = QQ_AUTHORIZE_URL + "?uin=" + uin + "&pttype=1&service=ptqrlogin&nodirect=0"
                + "&ptsigx=" + sigx
                + "&s_url=" + URLEncoder.encode("https://graph.qq.com/oauth2.0/login_jump", StandardCharsets.UTF_8)
                + "&ptlang=2052&ptredirect=100&aid=716027609&daid=383"
                + "&j_later=0&low_login_hour=0&regmaster=0&pt_login_type=3&pt_aid=0&pt_aaid=16&pt_light=0"
                + "&pt_3rd_aid=100497308";
        HttpResponse<String> response = followRedirects(httpGetString(url, "https://xui.ptlogin2.qq.com/", null), session);
        collectCookies(response, session);
        String pSkey = session.cookies.get("p_skey");
        if (StringUtils.isBlank(pSkey)) {
            throw new IOException("未获取到 p_skey");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("response_type", "code");
        form.put("client_id", "100497308");
        form.put("redirect_uri", "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/");
        form.put("scope", "get_user_info,get_app_friends");
        form.put("state", "state");
        form.put("switch", "");
        form.put("from_ptlogin", "1");
        form.put("src", "1");
        form.put("update_auth", "1");
        form.put("openapi", "1010_1030");
        form.put("g_tk", String.valueOf(hash33(pSkey, 5381)));
        form.put("auth_time", String.valueOf(System.currentTimeMillis()));
        form.put("ui", String.valueOf(100000 + random.nextInt(900000)));
        HttpResponse<String> oauth = httpPostForm(QQ_OAUTH_URL, form,
                "https://graph.qq.com/oauth2.0/authorize", session.cookieHeader());
        collectCookies(oauth, session);
        String location = oauth.headers().firstValue("location").orElse("");
        String code = extractParam(location, "code=", "&");
        if (StringUtils.isBlank(code) && location.contains("code=")) {
            code = location.substring(location.indexOf("code=") + 5);
        }
        if (StringUtils.isBlank(code)) {
            throw new IOException("未获取到 OAuth code");
        }
        return code;
    }

    private String exchangeCredential(String type, String code, String wxAppId) throws IOException, InterruptedException {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode comm = payload.putObject("comm");
        comm.put("ct", "11");
        comm.put("cv", "13020508");
        comm.put("v", "13020508");
        comm.put("tmeAppID", "qqmusic");
        comm.put("format", "json");
        comm.put("inCharset", "utf-8");
        comm.put("outCharset", "utf-8");
        comm.put("uid", "3931641530");
        comm.put("tmeLoginType", "wx".equals(type) ? "1" : "2");
        String requestKey;
        if ("wx".equals(type)) {
            requestKey = "music.login.LoginServer.Login";
            ObjectNode req = payload.putObject(requestKey);
            req.put("module", "music.login.LoginServer");
            req.put("method", "Login");
            ObjectNode param = req.putObject("param");
            param.put("code", code);
            param.put("strAppid", wxAppId);
        } else {
            requestKey = "QQConnectLogin.LoginServer.QQLogin";
            ObjectNode req = payload.putObject(requestKey);
            req.put("module", "QQConnectLogin.LoginServer");
            req.put("method", "QQLogin");
            req.putObject("param").put("code", code);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(MUSIC_API_URL))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode result = root.path(requestKey);
        if (result.path("code").asInt(-1) != 0) {
            throw new IOException("QQ音乐登录接口返回异常: " + result.path("code").asInt());
        }
        return buildCredentialExtend(result.path("data"), "wx".equals(type) ? 1 : 2);
    }

    // 与 py/QQ音乐.py QQMusicCredential.as_dict 的键保持一致，musicid 统一为字符串
    static String buildCredentialExtend(JsonNode data, int loginType) throws IOException {
        if (data == null || !data.isObject() || data.isEmpty()) {
            throw new IOException("QQ音乐登录凭据为空");
        }
        ObjectNode credential = (ObjectNode) data.deepCopy();
        if (credential.hasNonNull("musicid")) {
            credential.put("musicid", credential.path("musicid").asText());
        }
        credential.put("loginType", loginType);
        credential.put("login_type", loginType);
        return credential.toString();
    }

    // 与 py/QQ音乐.py _is_credential_refreshable 一致：四个键齐全才可刷新
    static boolean isCredentialRefreshable(JsonNode config) {
        if (config == null || !config.isObject()) {
            return false;
        }
        return StringUtils.isNotBlank(config.path("refresh_key").asText())
                && StringUtils.isNotBlank(config.path("refresh_token").asText())
                && StringUtils.isNotBlank(config.path("musickey").asText())
                && StringUtils.isNotBlank(config.path("musicid").asText());
    }

    // 刷新返回的新字段覆盖旧值，musicid 统一为字符串，str_musicid 兜底；data 为空表示无更新
    static String mergeRefreshedCredential(ObjectNode current, JsonNode data) {
        if (data == null || !data.isObject() || data.isEmpty()) {
            return null;
        }
        current.setAll((ObjectNode) data);
        if (current.hasNonNull("musicid")) {
            current.put("musicid", current.path("musicid").asText());
        }
        if (StringUtils.isBlank(current.path("str_musicid").asText())) {
            current.put("str_musicid", current.path("musicid").asText(""));
        }
        return current.toString();
    }

    /**
     * 用 extend 中的 refresh_key/refresh_token 调 musicu.fcg 刷新凭据，
     * 协议与 py/QQ音乐.py _refresh_credential 一致。返回新的 extend JSON，失败返回 null。
     */
    public String refreshCredential(JsonNode config) {
        if (!isCredentialRefreshable(config) || !(config instanceof ObjectNode)) {
            return null;
        }
        String musicid = config.path("musicid").asText().trim();
        long musicidInt;
        try {
            musicidInt = Long.parseLong(musicid);
        } catch (NumberFormatException e) {
            musicidInt = 0;
        }
        String musickey = config.path("musickey").asText();
        int loginType = config.path("loginType").asInt(config.path("login_type").asInt(2));
        int gtk = hash33(musickey, 5381);

        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode comm = payload.putObject("comm");
        comm.put("cv", "13020508");
        comm.put("v", "13020508");
        comm.put("ct", "11");
        comm.put("tmeAppID", "qqmusic");
        comm.put("format", "json");
        comm.put("inCharset", "utf-8");
        comm.put("outCharset", "utf-8");
        comm.put("uid", "3931641530");
        comm.put("QIMEI36", "8888888888888888");
        comm.put("qq", musicid);
        comm.put("authst", musickey);
        comm.put("tmeLoginType", String.valueOf(loginType));
        comm.put("uin", musicidInt);
        comm.put("g_tk", gtk);
        comm.put("g_tk_new_20200303", gtk);
        String requestKey = "music.login.LoginServer.Login";
        ObjectNode req = payload.putObject(requestKey);
        req.put("module", "music.login.LoginServer");
        req.put("method", "Login");
        ObjectNode param = req.putObject("param");
        param.put("refresh_key", config.path("refresh_key").asText());
        param.put("refresh_token", config.path("refresh_token").asText());
        param.put("musickey", musickey);
        param.put("musicid", musicidInt);

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(MUSIC_API_URL))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://y.qq.com/")
                    .header("Origin", "https://y.qq.com")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(response.body()).path(requestKey);
            if (result.path("code").asInt(-1) != 0) {
                log.warn("QQ音乐凭据刷新失败, code={}", result.path("code").asInt());
                return null;
            }
            String merged = mergeRefreshedCredential((ObjectNode) config.deepCopy(), result.path("data"));
            if (merged == null) {
                log.warn("QQ音乐凭据刷新返回空数据");
            }
            return merged;
        } catch (Exception e) {
            log.warn("QQ音乐凭据刷新请求失败", e);
            return null;
        }
    }

    // 凭据约3天过期，每天刷新一次保活
    @Scheduled(cron = "0 40 4 * * *")
    public void scheduledRefresh() {
        refreshAll();
    }

    public int refreshAll() {
        int refreshed = 0;
        for (Plugin plugin : pluginRepository.findByEnabledTrueOrderBySortOrderAscIdAsc()) {
            if (!isQqMusicPlugin(plugin)) {
                continue;
            }
            try {
                if (refreshPlugin(plugin)) {
                    refreshed++;
                }
            } catch (Exception e) {
                log.warn("QQ音乐插件凭据刷新异常: {}", plugin.getName(), e);
            }
        }
        return refreshed;
    }

    boolean refreshPlugin(Plugin plugin) throws IOException {
        String extend = plugin.getExtend();
        if (StringUtils.isBlank(extend)) {
            return false;
        }
        JsonNode config = objectMapper.readTree(extend);
        if (!config.isObject() || config.isEmpty()) {
            return false;
        }
        if (!isCredentialRefreshable(config)) {
            log.debug("QQ音乐插件缺少可刷新凭据: {}", plugin.getName());
            return false;
        }
        String next = refreshCredential(config);
        if (StringUtils.isBlank(next)) {
            log.warn("QQ音乐插件凭据刷新失败: {}", plugin.getName());
            return false;
        }
        plugin.setExtend(next);
        pluginRepository.save(plugin);
        log.info("QQ音乐插件凭据刷新成功: {}", plugin.getName());
        return true;
    }

    static boolean isQqMusicPlugin(Plugin plugin) {
        if (plugin == null) {
            return false;
        }
        return StringUtils.contains(plugin.getName(), "QQ音乐")
                || StringUtils.contains(plugin.getSourceName(), "QQ音乐");
    }

    static String[] parsePtuiCallback(String body) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        Matcher matcher = PTUI_CB.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        String[] parts = matcher.group(1).split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            String value = parts[i].trim();
            if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1);
            }
            parts[i] = value;
        }
        return parts;
    }

    static String extractParam(String text, String prefix, String suffix) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        int start = text.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        start += prefix.length();
        int end = suffix == null ? text.length() : text.indexOf(suffix, start);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, end);
    }

    private HttpResponse<String> followRedirects(HttpResponse<String> response, QrSession session)
            throws IOException, InterruptedException {
        for (int i = 0; i < 5 && isRedirect(response.statusCode()); i++) {
            collectCookies(response, session);
            String location = response.headers().firstValue("location").orElse("");
            if (StringUtils.isBlank(location)) {
                break;
            }
            URI next = response.uri().resolve(location);
            response = httpGetString(next.toString(), response.uri().toString(), session.cookieHeader());
        }
        return response;
    }

    private static boolean isRedirect(int status) {
        return status >= 300 && status < 400;
    }

    private static String extractCookie(HttpResponse<?> response, String name) {
        for (String header : response.headers().allValues("set-cookie")) {
            String first = header.split(";", 2)[0].trim();
            int eq = first.indexOf('=');
            if (eq > 0 && name.equals(first.substring(0, eq))) {
                return first.substring(eq + 1);
            }
        }
        return "";
    }

    private static void collectCookies(HttpResponse<?> response, QrSession session) {
        for (String header : response.headers().allValues("set-cookie")) {
            String first = header.split(";", 2)[0].trim();
            int eq = first.indexOf('=');
            if (eq > 0 && StringUtils.isNotBlank(first.substring(eq + 1))) {
                session.cookies.put(first.substring(0, eq), first.substring(eq + 1));
            }
        }
    }

    private HttpResponse<byte[]> httpGet(String url, String referer, String cookie)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET();
        if (referer != null) {
            builder.header("Referer", referer);
        }
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> httpGetString(String url, String referer, String cookie)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET();
        if (referer != null) {
            builder.header("Referer", referer);
        }
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpPostForm(String url, Map<String, String> form, String referer, String cookie)
            throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (referer != null) {
            builder.header("Referer", referer);
        }
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void cleanupSessions() {
        Iterator<Map.Entry<String, QrSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isExpired()) {
                iterator.remove();
            }
        }
        while (sessions.size() > MAX_SESSIONS) {
            String oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, QrSession> entry : sessions.entrySet()) {
                if (entry.getValue().createdAt < oldestTime) {
                    oldestTime = entry.getValue().createdAt;
                    oldest = entry.getKey();
                }
            }
            if (oldest == null || sessions.remove(oldest) == null) {
                break;
            }
        }
    }

    private static class QrSession {
        final String type;
        final long createdAt = System.currentTimeMillis();
        final Map<String, String> cookies = new LinkedHashMap<>();
        String identifier;
        String imageBase64;
        volatile String credentialExtend;

        QrSession(String type) {
            this.type = type;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > SESSION_TTL_MS;
        }

        String cookieHeader() {
            if (cookies.isEmpty()) {
                return null;
            }
            StringBuilder header = new StringBuilder();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (header.length() > 0) {
                    header.append("; ");
                }
                header.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return header.toString();
        }
    }
}
