package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.SettingRepository;
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
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 123臻藏搜索源(atv-spiders/py/123臻藏.py 的 Java 移植,追剧搜索源之一):123.qsxy.top,
 * WordPress + Zibll 主题的 123 云盘资源站,<b>123 盘为主、混少量其它盘/磁力</b>。
 * <b>仅订阅候选盘白名单包含 123 时参与搜索</b>(searchAllSources 门控 —— 123 主题站,
 * 订阅不定向 123 时产出几乎全被定向集闸门裁掉,不值一路搜索 + N 个详情页请求)。
 *
 * <p>搜索页匿名可用:GET {@code /?s={kw}&type=post},卡片 {@code .posts-item} 的
 * {@code h2.item-heading a}(href={@code /{id}.html},标题剥「- 123云盘…」/「- 臻藏阁…」
 * 站名后缀),单页不翻页(py 同口径)。WP REST 兜底不搬 —— REST 只有标题没有链接,
 * 对候选池无产出。
 *
 * <p>详情:{@code GET /{id}.html},正文 div.wp-posts-content(回落 article-content)
 * 默认「内容已隐藏,请登录后查看」,<b>必须配 Cookie</b>(Setting {@code zencang_host}
 * 可覆盖站点;{@code zencang_cookie} 需含 wordpress_logged_in,未配置时本源静默关闭)。
 * 「评论后可见」的文章自动发一条评论解锁(POST /wp-comments-post.php 后重取正文,
 * 文案与 py 一致)。链接从正文属性(href/data-clipboard-text/data-url/data-link)与
 * 裸 URL 正则提取,{@code golink=} 中转 base64 解码,站点付费/推广地址
 * (outsidePay/login/wp-content/wp-admin)剔除;盘型走 {@link Message#parseType},
 * 提取码(提取码/访问码/密码 4~8 位)折 {@code ?pwd=}(115 用 {@code password=})。
 * py 的每日签到不搬 —— 服务端不替用户做站点任务,签到与取资源无直接关系。
 */
@Slf4j
@Service
public class ZhenCangSearchService {
    public static final String HOST_SETTING = "zencang_host";
    public static final String COOKIE_SETTING = "zencang_cookie";
    /** 门控盘 key:订阅候选盘白名单(drives)包含 123 才搜本源。 */
    public static final String DRIVE_KEY = "123";

    private static final String DEFAULT_HOST = "https://123.qsxy.top";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/147.0.0.0 Safari/537.36";
    private static final int REQUEST_TIMEOUT_SECONDS = 10;
    /** 评论解锁文案(py 同款)。 */
    private static final String COMMENT_TEXT = "感谢分享资源";
    private static final Pattern SITE_SUFFIX = Pattern.compile("\\s*[-—–]\\s*(?:123云盘|臻藏阁).*$");
    private static final Pattern POST_ID = Pattern.compile("/(\\d+)\\.html");
    private static final Pattern GOLINK = Pattern.compile("golink=([A-Za-z0-9_\\-=%]+)");
    private static final Pattern COMMENT_LOCK_BOX = Pattern.compile("hidden-box[^>]*reply-show");
    private static final List<Pattern> ACCESS_CODE_PATTERNS = List.of(
            Pattern.compile("提取码[：:\\s]*([A-Za-z0-9]{4,8})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("访问码[：:\\s]*([A-Za-z0-9]{4,8})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("密码[：:\\s]*([A-Za-z0-9]{4,8})", Pattern.CASE_INSENSITIVE));
    private static final Pattern BARE_HTTP = Pattern.compile("https?://[^\\s\"'<>()\\[\\]]+");
    private static final Pattern BARE_MAGNET = Pattern.compile("magnet:\\?[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_ED2K = Pattern.compile("ed2k://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    /** 站点公共的付费/推广/资源地址,不是分享链接(py LINK_BLOCKLIST)。 */
    private static final List<String> LINK_BLOCKLIST =
            List.of("123pan.com/outsidepay", "123pan.com/login", "/wp-content/", "/wp-admin/");
    private static final List<String> COMMENT_LOCK_MARKERS = List.of("请评论后刷新页面查看", "评论后可见");

    private final SettingRepository settingRepository;
    private final AppProperties appProperties;
    private final OkHttpClient httpClient = new OkHttpClient();
    private volatile boolean warnedNoCookie;

    public ZhenCangSearchService(SettingRepository settingRepository, AppProperties appProperties) {
        this.settingRepository = settingRepository;
        this.appProperties = appProperties;
    }

    record Card(String postId, String title) {
    }

    record Candidate(String link, String context) {
    }

    public List<Message> search(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return List.of();
        }
        String host = SiteSearchSupport.normalizeHost(
                SiteSearchSupport.setting(settingRepository, HOST_SETTING), DEFAULT_HOST);
        String cookie = normalizeCookie(SiteSearchSupport.setting(settingRepository, COOKIE_SETTING));
        if (cookie.isEmpty()) {
            if (!warnedNoCookie) {
                warnedNoCookie = true;
                log.info("123臻藏搜索源未启用:未配置 Cookie(Setting {},需含 wordpress_logged_in,正文默认隐藏)", COOKIE_SETTING);
            }
            return List.of();
        }
        long deadline = System.currentTimeMillis()
                + appProperties.getSubscription().getZencangTimeoutSeconds() * 1000L;
        try {
            List<Card> cards = parseCards(getHtml(host, searchUrl(host, keyword.trim()), cookie));
            List<Message> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int details = 0;
            int maxDetails = Math.max(1, appProperties.getSubscription().getZencangMaxDetailPages());
            for (Card card : cards) {
                if (details >= maxDetails || System.currentTimeMillis() > deadline) {
                    break;
                }
                details++;
                String detailUrl = host + "/" + card.postId() + ".html";
                String html = getHtml(host, detailUrl, cookie);
                html = maybeUnlockByComment(host, cookie, detailUrl, html);
                parseDetail(html, card, result, seen);
            }
            log.info("ZhenCang search {} get {} results", keyword, result.size());
            return result;
        } catch (Exception e) {
            log.warn("zencang search [{}] failed: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    // ---------- 请求 ----------

    private String getHtml(String host, String url, String cookie) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", host + "/")
                .header("Cookie", cookie)
                .get()
                .build();
        Resp resp = http(request, true);
        return resp.code() == 200 ? StringUtils.defaultString(resp.body()) : "";
    }

    /** 服务覆写供单测打桩;评论 POST 不跟重定向(判定 302 即成功),其余全跟。 */
    protected Resp http(Request request, boolean followRedirects) throws IOException {
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(followRedirects)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            return new Resp(response.code(), response.headers("Set-Cookie"), body);
        }
    }

    private static String searchUrl(String host, String keyword) {
        HttpUrl url = HttpUrl.parse(host + "/");
        if (url == null) {
            return "";
        }
        return url.newBuilder()
                .addQueryParameter("s", keyword)
                .addQueryParameter("type", "post")
                .build().toString();
    }

    // ---------- 评论解锁(「评论后可见」的文章:发一条评论后重取正文,py 同款) ----------

    String maybeUnlockByComment(String host, String cookie, String detailUrl, String html) {
        if (!isCommentLocked(html)) {
            return html;
        }
        Document doc = Jsoup.parse(StringUtils.defaultString(html));
        String postId = inputValue(doc, "input[name=comment_post_ID]");
        String parent = StringUtils.defaultIfEmpty(inputValue(doc, "input[name=comment_parent]"), "0");
        String nonce = inputValue(doc, "input[name=_wpnonce]");
        if (postId.isEmpty() || nonce.isEmpty()) {
            return html;
        }
        Request request = new Request.Builder()
                .url(host + "/wp-comments-post.php")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", detailUrl)
                .header("Origin", host)
                .header("Cookie", cookie)
                .post(new FormBody.Builder()
                        .add("comment", COMMENT_TEXT)
                        .add("comment_post_ID", postId)
                        .add("comment_parent", StringUtils.defaultIfEmpty(parent, "0"))
                        .add("_wpnonce", nonce)
                        .build())
                .build();
        try {
            Resp resp = http(request, false);
            // WP 评论成功必 302 跳 #comment-N(py 另查 Location;失败渲染 200 错误页)
            if (resp.code() != 301 && resp.code() != 302) {
                return html;
            }
            String fresh = getHtml(host, detailUrl, cookie);
            return StringUtils.defaultIfEmpty(fresh, html);
        } catch (Exception e) {
            log.debug("zencang comment unlock failed for {}: {}", detailUrl, e.getMessage());
            return html;
        }
    }

    static boolean isCommentLocked(String html) {
        String text = StringUtils.defaultString(html);
        for (String marker : COMMENT_LOCK_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return COMMENT_LOCK_BOX.matcher(text).find();
    }

    private static String inputValue(Document doc, String selector) {
        Element input = doc.selectFirst(selector);
        return input == null ? "" : StringUtils.defaultString(input.attr("value")).trim();
    }

    // ---------- 解析 ----------

    /** 搜索结果卡片(py _extract_cards 的 Jsoup 等价实现):.posts-item 内 h2.item-heading 的文章链接。 */
    List<Card> parseCards(String html) {
        List<Card> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element node : Jsoup.parse(StringUtils.defaultString(html)).select("[class*=posts-item]")) {
            Element anchor = node.selectFirst("h2[class*=item-heading] a[href]");
            if (anchor == null) {
                continue;
            }
            String postId = postId(anchor.attr("href"));
            String name = stripSiteSuffix(anchor.text());
            if (postId.isEmpty() || name.isEmpty() || !seen.add(postId)) {
                continue;
            }
            cards.add(new Card(postId, name));
        }
        return cards;
    }

    /** 文章链接里的数字 id(/12345.html);无匹配回落纯数字串(py _detail_path 同款)。 */
    static String postId(String href) {
        Matcher matched = POST_ID.matcher(StringUtils.defaultString(href));
        if (matched.find()) {
            return matched.group(1);
        }
        return StringUtils.defaultString(href).replaceAll("\\D", "");
    }

    /** 标题剥「- 123云盘…」/「- 臻藏阁…」站名后缀(py _strip_site_suffix)。 */
    static String stripSiteSuffix(String title) {
        return SITE_SUFFIX.matcher(cleanText(title)).replaceAll("").trim();
    }

    /**
     * 详情页正文产出:属性链接 + 裸 URL,盘型识别后按需折提取码;磁力/ed2k 原样
     * (离线类型由定向集闸门统一裁决)。
     */
    void parseDetail(String html, Card card, List<Message> out, Set<String> seen) {
        Document doc = Jsoup.parse(StringUtils.defaultString(html));
        Element content = doc.selectFirst("div[class*=wp-posts-content]");
        if (content == null) {
            content = doc.selectFirst("[class*=article-content]");
        }
        if (content == null) {
            // py 找不到内容节点时用整页兜底,这里宁缺勿滥:整页会引入侧栏/评论区噪声链接
            return;
        }
        String code = extractAccessCode(content.text());
        for (Candidate candidate : collectLinks(content)) {
            String link = normalizeLink(candidate.link());
            if (link.isEmpty()) {
                continue;
            }
            String type = Message.parseType(link);
            if (type == null) {
                continue;
            }
            String target = isOfflineType(type) ? link : foldPassword(link, code);
            if (!seen.add(target)) {
                continue;
            }
            Message message = new Message();
            message.setType(type);
            message.setLink(target);
            message.setName(card.title());
            message.setChannel("123臻藏");
            message.setContent(joinContent(card.title(), candidate.context()));
            out.add(message);
        }
    }

    private static boolean isOfflineType(String type) {
        return "magnet".equals(type) || "ed2k".equals(type);
    }

    /** 候选链接:正文里 a[href] 与 data-clipboard-text/data-url/data-link 属性 + 裸 URL 正则(带所在块文本)。 */
    static List<Candidate> collectLinks(Element content) {
        List<Candidate> candidates = new ArrayList<>();
        for (Element anchor : content.select("a[href]")) {
            candidates.add(new Candidate(anchor.attr("href"), contextOf(anchor)));
        }
        for (String attr : List.of("data-clipboard-text", "data-url", "data-link")) {
            for (Element element : content.select("[" + attr + "]")) {
                candidates.add(new Candidate(element.attr(attr), contextOf(element)));
            }
        }
        String text = Parser.unescapeEntities(content.outerHtml(), false);
        collectBare(text, BARE_HTTP, candidates);
        collectBare(text, BARE_MAGNET, candidates);
        collectBare(text, BARE_ED2K, candidates);
        return candidates;
    }

    private static void collectBare(String text, Pattern pattern, List<Candidate> out) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            out.add(new Candidate(matcher.group(), ""));
        }
    }

    /** 链接所在块(p/li/td)文本剥链接本身,留集数/规格描述供打分;裸 URL 无上下文返回空。 */
    private static String contextOf(Element anchor) {
        Element block = anchor.closest("p, li, td");
        String text = block != null ? block.text() : anchor.ownText();
        return cleanText(StringUtils.defaultString(text)
                .replace(StringUtils.defaultString(anchor.attr("href")), ""));
    }

    private static String joinContent(String name, String context) {
        return context.isEmpty() ? name : (name + " " + context).trim();
    }

    /** 链接归一化:解实体、剥首尾标点、golink 中转解码、站点付费/推广地址剔除、scheme 校验。 */
    static String normalizeLink(String value) {
        String link = Parser.unescapeEntities(StringUtils.defaultString(value), false).trim();
        link = StringUtils.strip(link, "。，,；;、");
        if (link.isEmpty()) {
            return "";
        }
        if (link.contains("golink=")) {
            String decoded = decodeGolink(link);
            if (!decoded.isEmpty()) {
                link = decoded;
            }
        }
        String lowered = link.toLowerCase();
        for (String bad : LINK_BLOCKLIST) {
            if (lowered.contains(bad)) {
                return "";
            }
        }
        if (lowered.startsWith("magnet:") || lowered.startsWith("ed2k://")) {
            return link;
        }
        return lowered.startsWith("http://") || lowered.startsWith("https://") ? link : "";
    }

    /** golink= 的 URL-safe base64 中转解码(-/_ 还原标准字母表,%3D 还原 = 后补齐 padding)。 */
    static String decodeGolink(String url) {
        Matcher matched = GOLINK.matcher(StringUtils.defaultString(url));
        if (!matched.find()) {
            return "";
        }
        String raw = matched.group(1).replace("%3D", "=").replace("-", "+").replace("_", "/");
        raw += "=".repeat((4 - raw.length() % 4) % 4);
        try {
            String decoded = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8).trim();
            String lowered = decoded.toLowerCase();
            return lowered.startsWith("http://") || lowered.startsWith("https://") ? decoded : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** 正文里的提取码/访问码/密码(4~8 位字母数字,py _extract_access_code)。 */
    static String extractAccessCode(String text) {
        for (Pattern pattern : ACCESS_CODE_PATTERNS) {
            Matcher matched = pattern.matcher(StringUtils.defaultString(text));
            if (matched.find()) {
                return matched.group(1);
            }
        }
        return "";
    }

    /** 提取码折 URL 参数:py 统一 pwd=,115 的标准参数是 password=(盘链同款特判)。 */
    static String foldPassword(String url, String code) {
        if (StringUtils.isBlank(url) || StringUtils.isBlank(code)) {
            return url;
        }
        String param = "8".equals(Message.parseType(url)) ? "password=" : "pwd=";
        return SiteSearchSupport.appendPasswordParam(url,
                URLEncoder.encode(code, StandardCharsets.UTF_8), param);
    }

    /** Cookie 归一化:剥 cookie: 前缀,按分号/换行分段留含 = 的段(py _normalize_cookie)。 */
    static String normalizeCookie(String raw) {
        String text = StringUtils.trimToEmpty(raw);
        if (StringUtils.startsWithIgnoreCase(text, "cookie:")) {
            text = text.substring(7);
        }
        StringBuilder sb = new StringBuilder();
        for (String part : text.split("[;\\r\\n]+")) {
            String segment = part.trim();
            if (segment.isEmpty() || !segment.contains("=")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(segment);
        }
        return sb.toString();
    }

    /** 剥 HTML 标签/解实体/压空白。 */
    static String cleanText(String value) {
        String text = Jsoup.parseBodyFragment(StringUtils.defaultString(value)).text();
        return text.replaceAll("\\s+", " ").trim();
    }
}
