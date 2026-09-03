package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 123社区搜索源(atv-spiders/py/123社区.py 的 Java 移植,追剧搜索源之一):123panfx.com /
 * pan1.me 双站探活(Xiuno BBS「123分享社区」,Setting {@code pan123community_host} 可覆盖),
 * 论坛形态的 <b>纯 123 云盘资源社区 —— 所有链接规范化收敛到
 * {@code https://123pan.cn/{s|123pan}/{key}?pwd=}</b>(镜像域名统一便于识别与去重,
 * 路径模式按原样保留),<b>仅订阅候选盘白名单包含 123 时参与搜索</b>(与 123臻藏同门控,
 * 用户定规「订阅包含123网盘才搜索」)。
 *
 * <p>搜索:GET {@code /search.htm?keyword=} AJAX JSON(手机 UA + X-Requested-With,
 * 匿名可用),{@code message[]} 的 subject/url/tid 即帖子入口,单页不翻页(py 同口径)。
 *
 * <p>详情:{@code GET /thread-{id}.htm}(桌面 UA),楼层 div.message 前 3 层提取 123
 * 分享链接(share123 正则直抓 + 通用 URL 过滤 123 族域名/路径);提取码 URL 自带
 * {@code pwd=} 优先,否则 key 之后 60 字符窗口找「提取码/访问码/密码/码」。<b>「请回复后再
 * 查看」帖:配置社区 Cookie(Setting {@code pan123community_cookie},须含 bbs_sid/bbs_token)
 * 时自动回复解锁</b>(POST /post-create-{tid}-1.htm,站点发帖间隔冷却内跳过不发;每次搜索
 * 至多回复一次,后续隐藏帖跳过;回复成功后 1.2s 重取详情一次,py 同款)。登录墙帖子
 * (无权访问文案)静默跳过。Cookie 不配不关源 —— 匿名可搜可提取非隐藏帖,只是隐藏帖
 * 跳过(与 123臻藏的「正文默认全隐藏」不同)。
 */
@Slf4j
@Service
public class Pan123CommunitySearchService {
    public static final String HOST_SETTING = "pan123community_host";
    public static final String COOKIE_SETTING = "pan123community_cookie";
    /** 门控盘 key(与 123臻藏同源):订阅候选盘白名单(drives)包含 123 才搜本源。 */
    public static final String DRIVE_KEY = "123";

    private static final List<String> DEFAULT_SITES = List.of("https://123panfx.com", "https://pan1.me");
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/77.0.3865.90 Safari/537.36";
    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1";
    private static final int REQUEST_TIMEOUT_SECONDS = 10;
    /** 站点回复限速(py 同款 21s)。 */
    private static final long REPLY_MIN_INTERVAL_MS = 21_000L;
    /** 回复成功后重取详情前的等待(py time.sleep(1.2),防站点缓存旧页)。 */
    private static final long REFETCH_DELAY_MS = 1200L;
    private static final List<String> REPLY_TEMPLATES = List.of(
            "感谢楼主分享！", "这个资源太棒了！", "已收藏，谢谢！", "不错的资源，支持一下",
            "楼主辛苦了！", "内容很有用，感谢分享");
    private static final List<String> PAN_DOMAINS = List.of(
            "123pan.com", "123pan.cn", "share.123pan.cn", "123684.com", "123865.com",
            "123912.com", "123592.com", "pan.quark.cn", "drive.uc.cn", "alipan.com",
            "aliyundrive.com", "pan.aliyun.com", "pan.baidu.com", "115.com", "anxia.com",
            "cloud.189.cn", "pan.xunlei.com", "yun.139.com", "mcloud.139.com", "mypikpak.com");
    private static final Pattern THREAD_ID = Pattern.compile("thread-(\\d+)");
    private static final Pattern SHARE_123 = Pattern.compile(
            "(?:https?://)?(?:www\\.)?(?:(?:[\\w-]+\\.)?share\\.123pan\\.cn|123\\w{3}\\.com|123pan\\.(?:com|cn))/(?:s|123pan)/[^\\s\"'<>]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_URL = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARE_KEY = Pattern.compile(
            "https?://(?:[\\w-]+\\.)?(?:123\\w{3}\\.com|123pan\\.(?:com|cn)|share\\.123pan\\.cn)/(s|123pan)/([A-Za-z0-9_\\-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARE_PATH = Pattern.compile("/(?:s|123pan)/", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_PWD = Pattern.compile("[?&]pwd=([A-Za-z0-9]+)");
    private static final Pattern EXTRACT_CODE = Pattern.compile("(?:提取码|访问码|密码|码)\\s*[:：=]?\\s*([A-Za-z0-9]{4,8})");
    private static final String LOCK_MARKER = "请回复后再查看";
    private static final List<String> LOGIN_MARKERS = List.of(
            "用户组无权访问", "无权访问该板块", "需要登录才能访问", "您没有权限访问", "您需要先登录");
    private static final List<String> REPLY_FAIL_MARKERS = List.of(
            "请先登录", "请登录", "登录后", "失败", "错误", "无权", "权限", "太快", "频繁", "灌水", "验证码", "审核");

    private final SettingRepository settingRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient();
    private volatile String activeHost;
    /** 站点回复限速时间戳(实例级,跨搜索生效)。 */
    private volatile long lastReplyAt;

    public Pan123CommunitySearchService(SettingRepository settingRepository, AppProperties appProperties,
                                        ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    record Card(String threadId, String title) {
    }

    public List<Message> search(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return List.of();
        }
        String host = activeHost();
        String cookie = SiteSearchSupport.setting(settingRepository, COOKIE_SETTING).trim();
        long deadline = System.currentTimeMillis()
                + appProperties.getSubscription().getPan123communityTimeoutSeconds() * 1000L;
        try {
            List<Card> cards = parseSearchResults(getJson(host, searchUrl(host, keyword.trim()), cookie));
            List<Message> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int details = 0;
            int maxDetails = Math.max(1, appProperties.getSubscription().getPan123communityMaxDetailPages());
            boolean replyBudget = true;
            for (Card card : cards) {
                if (details >= maxDetails || System.currentTimeMillis() > deadline) {
                    break;
                }
                details++;
                String detailUrl = host + "/thread-" + card.threadId() + ".htm";
                String html = getHtml(host, detailUrl, cookie);
                List<String> links = extractLinks(html);
                if (links.isEmpty() && replyLocked(html) && replyBudget && StringUtils.isNotBlank(cookie)) {
                    // 回复解锁:每搜索至多一次(21s 限速下第二次至少再等 21s,预算内不值得)
                    replyBudget = false;
                    if (replyToUnlock(host, cookie, detailUrl, card.threadId())) {
                        sleep(REFETCH_DELAY_MS);
                        html = getHtml(host, detailUrl, cookie);
                        links = extractLinks(html);
                    }
                }
                for (String link : links) {
                    if (!seen.add(link)) {
                        continue;
                    }
                    Message message = new Message();
                    message.setType("3");
                    message.setLink(link);
                    message.setName(card.title());
                    message.setChannel("123社区");
                    message.setContent(card.title());
                    result.add(message);
                }
            }
            log.info("Pan123Community search {} get {} results", keyword, result.size());
            return result;
        } catch (Exception e) {
            log.warn("pan123community search [{}] failed: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    // ---------- 站点与请求 ----------

    /** 双站探活选站(2xx~3xx 即通,py _pick_site);Setting 覆盖则直接用;结果进程内缓存。 */
    String activeHost() {
        String cached = activeHost;
        if (cached != null) {
            return cached;
        }
        String override = SiteSearchSupport.normalizeHost(
                SiteSearchSupport.setting(settingRepository, HOST_SETTING), "");
        if (!override.isEmpty()) {
            activeHost = override;
            return override;
        }
        for (String site : DEFAULT_SITES) {
            Request request = new Request.Builder().url(site + "/")
                    .header("User-Agent", DESKTOP_UA).get().build();
            try {
                Resp resp = http(request);
                if (resp.code() >= 200 && resp.code() < 400) {
                    activeHost = site;
                    return site;
                }
            } catch (Exception ignored) {
                // 站点不可达,试下一个
            }
        }
        activeHost = DEFAULT_SITES.get(0);
        return activeHost;
    }

    /** 服务覆写供单测打桩;全部跟随重定向。 */
    protected Resp http(Request request) throws IOException {
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            return new Resp(response.code(), response.headers("Set-Cookie"), body);
        }
    }

    private static String searchUrl(String host, String keyword) {
        HttpUrl url = HttpUrl.parse(host + "/search.htm");
        if (url == null) {
            return "";
        }
        return url.newBuilder().addQueryParameter("keyword", keyword).build().toString();
    }

    private String getHtml(String host, String url, String cookie) throws IOException {
        Request request = baseRequest(host, cookie, url)
                .header("User-Agent", DESKTOP_UA)
                .get().build();
        Resp resp = http(request);
        return resp.code() == 200 ? StringUtils.defaultString(resp.body()) : "";
    }

    private String getJson(String host, String url, String cookie) throws IOException {
        Request request = baseRequest(host, cookie, url)
                .header("User-Agent", MOBILE_UA)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/plain, */*")
                .get().build();
        Resp resp = http(request);
        return resp.code() == 200 ? StringUtils.defaultString(resp.body()) : "";
    }

    private static Request.Builder baseRequest(String host, String cookie, String url) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Referer", host + "/")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (StringUtils.isNotBlank(cookie)) {
            builder.header("Cookie", cookie);
        }
        return builder;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 搜索结果解析 ----------

    /** AJAX JSON 的 message[] → 帖子卡片(py searchContent;url 缺失回落 tid 拼线程)。 */
    List<Card> parseSearchResults(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (IOException e) {
            return List.of();
        }
        List<Card> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : root.path("message")) {
            if (!item.isObject()) {
                continue;
            }
            String href = item.path("url").asText("");
            String threadId = threadId(href);
            if (threadId.isEmpty() && item.hasNonNull("tid")) {
                threadId = item.path("tid").asText("");
            }
            String title = cleanText(item.path("subject").asText(""));
            if (threadId.isEmpty() || title.isEmpty() || !seen.add(threadId)) {
                continue;
            }
            cards.add(new Card(threadId, title));
        }
        return cards;
    }

    static String threadId(String href) {
        Matcher matched = THREAD_ID.matcher(StringUtils.defaultString(href));
        return matched.find() ? matched.group(1) : "";
    }

    // ---------- 详情链接提取 ----------

    /**
     * 帖子楼层(div.message 前 3 层)提取 123 分享链接并规范化(py _extract_links +
     * _normalize_share):share123 正则直抓 + 通用 URL 过滤 123 族域名/路径;全部收敛到
     * https://123pan.cn/{s|123pan}/{key}(镜像域名统一),提取码折 ?pwd=。
     */
    List<String> extractLinks(String html) {
        if (StringUtils.isBlank(html) || loginRequired(html)) {
            return List.of();
        }
        Document doc = Jsoup.parse(html);
        List<Element> messages = doc.select("div[class*=message]");
        if (messages.isEmpty()) {
            return List.of();
        }
        List<Element> floors = messages.subList(0, Math.min(3, messages.size()));
        StringBuilder inner = new StringBuilder();
        StringBuilder textAll = new StringBuilder();
        for (Element floor : floors) {
            inner.append(floor.outerHtml()).append('\n');
            textAll.append(floor.text()).append('\n');
        }
        List<String> found = new ArrayList<>();
        Matcher share = SHARE_123.matcher(inner);
        while (share.find()) {
            found.add(share.group());
        }
        String innerText = inner.toString();
        Matcher url = ANY_URL.matcher(innerText);
        while (url.find()) {
            String candidate = url.group();
            String lowered = candidate.toLowerCase();
            if (SHARE_PATH.matcher(lowered).find() || PAN_DOMAINS.stream().anyMatch(lowered::contains)) {
                found.add(candidate);
            }
        }
        String allText = textAll.toString();
        List<String> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String candidate : found) {
            String normalized = normalizeShare(candidate, allText);
            if (!normalized.isEmpty() && seen.add(normalized)) {
                links.add(normalized);
            }
        }
        return links;
    }

    /** 123 分享链接规范化:剥锚点、mode/key 提取、域名统一 123pan.cn、提取码折 ?pwd=(URL 自带优先,否则 key 后 60 字符窗口)。 */
    static String normalizeShare(String raw, String textAll) {
        String value = StringUtils.strip(StringUtils.defaultString(raw).trim(), ".,;。，；)");
        if (value.isEmpty()) {
            return "";
        }
        value = value.replace("&amp;", "&");
        if (!StringUtils.startsWithIgnoreCase(value, "http")) {
            value = "https://" + StringUtils.stripStart(value, "/");
        }
        value = StringUtils.substringBefore(value, "#");
        Matcher matched = SHARE_KEY.matcher(value);
        if (!matched.find()) {
            return "";
        }
        String mode = matched.group(1);
        String key = matched.group(2);
        String pwd = "";
        Matcher urlPwd = URL_PWD.matcher(value);
        if (urlPwd.find()) {
            pwd = urlPwd.group(1);
        } else {
            int pos = textAll.indexOf(key);
            String window = pos >= 0 ? textAll.substring(pos, Math.min(textAll.length(), pos + 60)) : textAll;
            Matcher code = EXTRACT_CODE.matcher(window);
            if (code.find()) {
                pwd = code.group(1);
            }
        }
        String canon = "https://123pan.cn/" + mode + "/" + key;
        return pwd.isEmpty() ? canon : canon + "?pwd=" + pwd;
    }

    /** 页面命中登录墙文案(py login_markers):该帖对当前会话不可见,静默跳过。 */
    static boolean loginRequired(String html) {
        String text = StringUtils.defaultString(html);
        for (String marker : LOGIN_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 「请回复后再查看」锁判定:第一条楼层的文本(py _first_message_text)。 */
    static boolean replyLocked(String html) {
        if (StringUtils.isBlank(html)) {
            return false;
        }
        Document doc = Jsoup.parse(html);
        Element first = doc.selectFirst("div[class*=message]");
        return first != null && first.text().contains(LOCK_MARKER);
    }

    // ---------- 回复解锁 ----------

    /**
     * 自动回复解锁(py _reply_to_unlock):POST /post-create-{tid}-1.htm。站点有发帖间隔
     * (防灌水,约 21s):距上次回复不足间隔时<b>直接跳过不发</b>(py 的 sleep 等够不搬 ——
     * 阻塞的是 msub-search 共享线程池的一路线程,还会让后续搜索排队;当轮少解锁一帖由
     * 下轮巡检自愈,不值;硬发则大概率被拒还累积灌水风控信号,Cookie 是用户自己的账号)。
     * 成功判定 JSON code==0/文案含「成功」,无失败标记的非 JSON 响应按成功论(py 宽松口径)。
     */
    boolean replyToUnlock(String host, String cookie, String detailUrl, String threadId) {
        synchronized (this) {
            long cooldown = lastReplyAt > 0
                    ? lastReplyAt + REPLY_MIN_INTERVAL_MS - System.currentTimeMillis() : 0;
            if (cooldown > 0) {
                log.debug("pan123community reply cooldown ({}ms left), skip unlock for {}", cooldown, detailUrl);
                return false;
            }
            lastReplyAt = System.currentTimeMillis();
        }
        Request request = baseRequest(host, cookie, host + "/post-create-" + threadId + "-1.htm")
                .header("User-Agent", DESKTOP_UA)
                .header("Origin", host)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(new FormBody.Builder()
                        .add("doctype", "1")
                        .add("return_html", "0")
                        .add("quotepid", "")
                        .add("message", REPLY_TEMPLATES.get(ThreadLocalRandom.current().nextInt(REPLY_TEMPLATES.size())))
                        .add("quick_reply_message", "4")
                        .build())
                .build();
        String body;
        try {
            Resp resp = http(request);
            body = resp.code() == 200 ? StringUtils.defaultString(resp.body()) : "";
        } catch (Exception e) {
            log.debug("pan123community reply failed for {}: {}", detailUrl, e.getMessage());
            return false;
        }
        boolean ok = replySucceeded(body);
        if (!ok) {
            log.debug("pan123community reply rejected for {}: {}", detailUrl, cleanText(body));
        }
        return ok;
    }

    /** 回复响应成功判定:JSON code==0 或 message 含「成功」;非 JSON 含失败标记才拒(py 同款)。 */
    boolean replySucceeded(String body) {
        String text = StringUtils.defaultString(body);
        try {
            JsonNode root = objectMapper.readTree(text);
            if (root.isObject()) {
                if (root.path("code").asInt(-1) == 0) {
                    return true;
                }
                return root.path("message").asText("").contains("成功")
                        || root.path("msg").asText("").contains("成功");
            }
        } catch (IOException ignored) {
            // 非 JSON 走文案判定
        }
        if (text.contains("成功")) {
            return true;
        }
        return REPLY_FAIL_MARKERS.stream().noneMatch(text::contains);
    }

    /** 剥 HTML 标签/压空白。 */
    static String cleanText(String value) {
        String text = Jsoup.parseBodyFragment(StringUtils.defaultString(value)).text();
        return text.replaceAll("\\s+", " ").trim();
    }
}
