package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 夸父搜索源(atv-spiders/py/夸父.py 的 Java 移植,追剧搜索源之一):kfzy.net,
 * Xiuno BBS「夸父资源社」,<b>夸克为主、混 UC/阿里/天翼/123/115/百度/迅雷</b>。
 * <b>仅订阅候选盘白名单包含夸克时参与搜索</b>(与 123臻藏/123社区同款门控不同盘,
 * 用户定规按主题盘定向:夸克主题社,订阅不定向夸克时产出几乎全被定向集闸门裁掉)。
 *
 * <p>搜索:{@code GET /search-{kw}-1.htm} HTML 页(单页,py 同口径),解析同帖子列表:
 * {@code ul.threadlist > li},置顶帖(i[data-placement~=top])跳过,标题/角标命中
 * <b>屏蔽词表</b>(jar 静态表,内容治理:福利/写真/成人词等)整条丢弃。
 *
 * <p>详情:thread-{id}.htm 链接提取<b>四级回退</b>(jar k()):①正文 alert 块文本
 * (剥「免登流量」提示语,待登录/立即回复/VIP会员等提示语不算链接)→ ②alert 只剩纯
 * 提取码时去 a[href] 配对网盘链接 → ③整页正则兜底(锁贴的真实链接常泄漏在 JSON-LD
 * 的 [ttreply] 里,匿名也能抓到;按 quark/uc/ali/189/115/123 顺序规范重建,123 系域名
 * 镜像众多按 key 回原文匹配,百度/迅雷为 py 移植版补充)→ ④仍空扫 div.message 的
 * a[href] 网盘域链接从父文本补码。提取码折 {@code ?pwd=}(115 特判 password=)。
 *
 * <p>「回复后可见」帖:配置论坛 Cookie(Setting {@code kuafu_host}/{@code kuafu_cookie},
 * 须含 bbs_sid/bbs_token)时自动回复解锁(POST /post-create-{tid}-1.htm,站点发帖间隔
 * 冷却内直接跳过不发 —— 阻塞共享搜索线程不值,下轮巡检自愈;每搜索至多回复一次,
 * 成功后 1s 重取一次)。正文「待登录」= Cookie 失效,该帖跳过。Cookie 不配不关源
 * (第③级正则匿名可抓锁贴泄漏链接)。
 */
@Slf4j
@Service
public class KuafuSearchService {
    public static final String HOST_SETTING = "kuafu_host";
    public static final String COOKIE_SETTING = "kuafu_cookie";
    /** 门控盘 key(夸克主题社):订阅候选盘白名单(drives)包含 quark 才搜本源。 */
    public static final String DRIVE_KEY = "quark";

    private static final String DEFAULT_HOST = "https://www.kfzy.net";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final int REQUEST_TIMEOUT_SECONDS = 10;
    /** 站点发帖间隔(Xiuno 防灌水,冷却内跳过不发)。 */
    private static final long REPLY_MIN_INTERVAL_MS = 21_000L;
    /** 回复成功后重取详情前的等待(py time.sleep(1.0))。 */
    private static final long REFETCH_DELAY_MS = 1000L;
    private static final List<String> REPLY_TEMPLATES = List.of(
            "感谢楼主分享！", "这个资源太棒了！", "已收藏，谢谢！", "不错的资源，支持一下",
            "楼主辛苦了！", "内容很有用，感谢分享");
    /** jar FishKF.r:标题/角标命中即整条丢弃(内容治理)。 */
    private static final List<String> BLOCK_WORDS = List.of(
            "福利", "写真", "私房", "美女图", "性感", "诱惑", "大尺度", "18禁", "18+", "R18",
            "成人", "限制级", "裸", "露点", "无码", "有码", "步兵", "骑兵", "AV", "女优",
            "番号", "情色", "色情", "自拍", "私密", "偷拍", "走光", "约炮", "援交", "陪玩",
            "约会", "黑丝", "白丝", "肉丝", "丝袜诱惑", "制服诱惑", "内衣秀", "比基尼秀",
            "泳装秀", "coser", "COSER", "cos福利", "秀人", "尤果", "推女郎", "嫩模",
            "网红私拍", "onlyfans", "OnlyFans", "ONLYFANS", "P站", "p站", "里番", "本子");
    /** 正文提示语,命中即不算链接(jar k() 第 1 级)。 */
    private static final List<String> ALERT_SKIP = List.of("待登录", "待操作", "立即回复", "查看资源", "VIP会员");
    private static final String NOISE = "请您务必转存保存后再进行下载，以免消耗分享者的免登流量";
    /** 正文首条 message 含「待登录」= Cookie 失效。 */
    private static final String LOGIN_MARKER = "待登录";
    private static final Pattern THREAD_ID = Pattern.compile("thread-(\\d+)");
    private static final Pattern RE_QUARK = Pattern.compile("pan\\.quark\\.cn/s/([a-zA-Z0-9]+)");
    private static final Pattern RE_UC = Pattern.compile("drive\\.uc\\.cn/s/([a-zA-Z0-9]+)");
    private static final Pattern RE_ALI = Pattern.compile("(aliyundrive|alipan)\\.com/s/([a-zA-Z0-9]+)");
    private static final Pattern RE_189 = Pattern.compile("cloud\\.189\\.(cn|com)/(t/|web/share\\?code=)([a-zA-Z0-9]+)");
    private static final Pattern RE_123 = Pattern.compile("123[a-zA-Z0-9]{3}\\.com/s/([a-zA-Z0-9-]+)");
    private static final Pattern RE_115 = Pattern.compile("(?:pan\\.)?(?:115\\.com|115cdn\\.com)/s/([a-zA-Z0-9]+)");
    private static final Pattern RE_BAIDU = Pattern.compile("https?://pan\\.baidu\\.com/s/[\\w-]+(?:\\?pwd=[a-zA-Z0-9]+)?");
    private static final Pattern RE_XUNLEI = Pattern.compile("https?://pan\\.xunlei\\.com/s/[\\w-]+(?:\\?pwd=[a-zA-Z0-9]+)?");
    private static final Pattern RE_PWD = Pattern.compile("(?:提取码|访问码|密码)[：:=\\s]*([a-zA-Z0-9]{4,8})");
    private static final Pattern HTTP_URL = Pattern.compile("https?://\\S+");

    private final SettingRepository settingRepository;
    private final AppProperties appProperties;
    private final OkHttpClient httpClient = new OkHttpClient();
    /** 站点回复限速时间戳(实例级,跨搜索生效)。 */
    private volatile long lastReplyAt;

    public KuafuSearchService(SettingRepository settingRepository, AppProperties appProperties) {
        this.settingRepository = settingRepository;
        this.appProperties = appProperties;
    }

    record Card(String threadId, String title) {
    }

    /** 提取中间产物:干净链接 + 同块提取码(码可为空)。 */
    record Extracted(String link, String code) {
    }

    public List<Message> search(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return List.of();
        }
        String host = SiteSearchSupport.normalizeHost(
                SiteSearchSupport.setting(settingRepository, HOST_SETTING), DEFAULT_HOST);
        String cookie = SiteSearchSupport.setting(settingRepository, COOKIE_SETTING).trim();
        long deadline = System.currentTimeMillis()
                + appProperties.getSubscription().getKuafuTimeoutSeconds() * 1000L;
        try {
            List<Card> cards = parseCards(getHtml(host, host + "/search-"
                    + URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8) + "-1.htm", cookie));
            List<Message> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int details = 0;
            int maxDetails = Math.max(1, appProperties.getSubscription().getKuafuMaxDetailPages());
            boolean replyBudget = true;
            for (Card card : cards) {
                if (details >= maxDetails || System.currentTimeMillis() > deadline) {
                    break;
                }
                details++;
                String detailUrl = host + "/thread-" + card.threadId() + ".htm";
                String html = getHtml(host, detailUrl, cookie);
                List<Extracted> links = extractLinks(html);
                if (links.isEmpty() && StringUtils.isNotBlank(html)
                        && replyBudget && StringUtils.isNotBlank(cookie) && !cookieExpired(html)) {
                    replyBudget = false;
                    if (replyToUnlock(host, cookie, detailUrl, card.threadId())) {
                        sleep(REFETCH_DELAY_MS);
                        html = getHtml(host, detailUrl, cookie);
                        links = extractLinks(html);
                    }
                }
                for (Extracted extracted : links) {
                    String target = foldPassword(extracted.link(), extracted.code());
                    if (!seen.add(target)) {
                        continue;
                    }
                    String type = Message.parseType(target);
                    if (type == null) {
                        continue;
                    }
                    Message message = new Message();
                    message.setType(type);
                    message.setLink(target);
                    message.setName(card.title());
                    message.setChannel("夸父");
                    message.setContent(card.title());
                    result.add(message);
                }
            }
            log.info("Kuafu search {} get {} results", keyword, result.size());
            return result;
        } catch (Exception e) {
            log.warn("kuafu search [{}] failed: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    // ---------- 请求 ----------

    private String getHtml(String host, String url, String cookie) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", host)
                .header("Cookie", cookie);
        Resp resp = http(builder.get().build());
        return resp.code() == 200 ? StringUtils.defaultString(resp.body()) : "";
    }

    /** 服务覆写供单测打桩;跟随重定向。 */
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 帖子列表解析(jar q():ul.threadlist > li,置顶跳过,屏蔽词过滤) ----------

    List<Card> parseCards(String html) {
        if (StringUtils.isBlank(html)) {
            return List.of();
        }
        List<Card> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element li : Jsoup.parse(html).select("ul[class*=threadlist] > li")) {
            // 置顶帖:i[data-placement 含 top] 跳过
            boolean pinned = li.select("i[data-placement]").stream()
                    .anyMatch(i -> i.attr("data-placement").contains("top"));
            if (pinned) {
                continue;
            }
            Element anchor = li.selectFirst("div[class*=subject] > a:not([class*=badge])");
            if (anchor == null) {
                continue;
            }
            String href = anchor.attr("href").trim();
            String name = cleanText(anchor.text());
            String badge = cleanText(String.join(" ",
                    li.select("div[class*=subject] a[class*=badge]").eachText()));
            if (href.isEmpty() || name.isEmpty() || blocked(name) || blocked(badge)) {
                continue;
            }
            String threadId = threadId(href);
            if (threadId.isEmpty() || !seen.add(threadId)) {
                continue;
            }
            cards.add(new Card(threadId, name));
        }
        return cards;
    }

    static String threadId(String href) {
        Matcher matched = THREAD_ID.matcher(StringUtils.defaultString(href));
        return matched.find() ? matched.group(1) : "";
    }

    /** 标题/角标命中屏蔽词表即整条丢弃(jar m())。 */
    static boolean blocked(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return BLOCK_WORDS.stream().anyMatch(text::contains);
    }

    // ---------- 链接提取(jar k():四级回退,逐级只在上一级为空时才启用) ----------

    List<Extracted> extractLinks(String html) {
        if (StringUtils.isBlank(html) || cookieExpired(html)) {
            return List.of();
        }
        Document doc = Jsoup.parse(html);
        List<Extracted> extracted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // ① 正文 alert 块:文本剥提示语后提取干净 URL + 提取码
        List<String> alerts = new ArrayList<>();
        for (Element alert : doc.select("div[class*=message] div[class*=alert]")) {
            String text = cleanText(alert.text().replace(NOISE, ""));
            if (text.isEmpty() || ALERT_SKIP.stream().anyMatch(text::contains)) {
                continue;
            }
            alerts.add(text);
            Matcher url = HTTP_URL.matcher(text);
            String code = passwordOf(text);
            while (url.find()) {
                addExtracted(extracted, seen, url.group(), code);
            }
        }
        if (!extracted.isEmpty()) {
            return extracted;
        }

        // ② alert 只有纯提取码(无 http):去 a[href] 配对网盘链接(码无前缀时剥非字母数字认码)
        if (alerts.size() == 1 && !alerts.get(0).contains("http")) {
            String code = passwordOf(alerts.get(0));
            if (code.isEmpty()) {
                String bare = alerts.get(0).replaceAll("[^a-zA-Z0-9]", "");
                code = bare.length() >= 4 && bare.length() <= 8 ? bare : "";
            }
            for (Element a : doc.select("a[href]")) {
                String href = a.attr("href").trim();
                if (isPanLink(href) && (href.contains("/s/") || href.contains("/t/"))) {
                    addExtracted(extracted, seen, href, code);
                }
            }
            if (!extracted.isEmpty()) {
                return extracted;
            }
        }

        // ③ 整页正则兜底:锁贴真实链接泄漏在 JSON-LD [ttreply],匿名可抓
        regexScan(html, extracted, seen);
        if (!extracted.isEmpty()) {
            return extracted;
        }

        // ④ 仍空:扫 div.message 内 a[href] 网盘域链接,父文本补码
        for (Element a : doc.select("div[class*=message] a[href]")) {
            String href = a.attr("href").trim();
            if (!isPanLink(href)) {
                continue;
            }
            String parentText = a.parent() == null ? "" : a.parent().text();
            addExtracted(extracted, seen, href, passwordOf(parentText));
        }
        return extracted;
    }

    private static void addExtracted(List<Extracted> out, Set<String> seen, String rawLink, String code) {
        String link = fixScheme(StringUtils.stripEnd(StringUtils.defaultString(rawLink).trim(),
                "。，,；;、)】」'\"").trim());
        if (link.isEmpty() || Message.parseType(link) == null || !seen.add(link)) {
            return;
        }
        out.add(new Extracted(link, StringUtils.defaultString(code)));
    }

    /** jar k() 第 3 级:按 quark/uc/ali/189/115/123 顺序规范重建,123 按 key 回原文匹配;百度/迅雷为 py 移植版补充。 */
    private static void regexScan(String html, List<Extracted> out, Set<String> seen) {
        Matcher quark = RE_QUARK.matcher(html);
        while (quark.find()) {
            addExtracted(out, seen, "https://pan.quark.cn/s/" + quark.group(1), "");
        }
        Matcher uc = RE_UC.matcher(html);
        while (uc.find()) {
            addExtracted(out, seen, "https://drive.uc.cn/s/" + uc.group(1), "");
        }
        Matcher ali = RE_ALI.matcher(html);
        while (ali.find()) {
            addExtracted(out, seen, "https://www.alipan.com/s/" + ali.group(2), "");
        }
        Matcher tianyi = RE_189.matcher(html);
        while (tianyi.find()) {
            addExtracted(out, seen, "https://cloud.189.cn/t/" + tianyi.group(3), "");
        }
        Matcher pan115 = RE_115.matcher(html);
        while (pan115.find()) {
            addExtracted(out, seen, "https://115cdn.com/s/" + pan115.group(1), "");
        }
        // 123 系域名镜像众多:拿到 key 后回原文匹配完整 URL
        Matcher pan123 = RE_123.matcher(html);
        while (pan123.find()) {
            Matcher full = Pattern.compile(
                    "https?://[\\w.]*123[a-zA-Z0-9]{3}\\.com/s/" + Pattern.quote(pan123.group(1))).matcher(html);
            if (full.find()) {
                addExtracted(out, seen, full.group(), "");
            }
        }
        Matcher baidu = RE_BAIDU.matcher(html);
        while (baidu.find()) {
            addExtracted(out, seen, baidu.group(), "");
        }
        Matcher xunlei = RE_XUNLEI.matcher(html);
        while (xunlei.find()) {
            addExtracted(out, seen, xunlei.group(), "");
        }
    }

    /** 提取码(py RE_PWD:提取码/访问码/密码 + 4~8 位字母数字)。 */
    static String passwordOf(String text) {
        Matcher matched = RE_PWD.matcher(StringUtils.defaultString(text));
        return matched.find() ? matched.group(1) : "";
    }

    /** jar p():裸域名补 https://(仅 .com/.cn 形态)。 */
    static String fixScheme(String url) {
        String value = StringUtils.defaultString(url);
        if (value.isEmpty() || value.startsWith("http")) {
            return value;
        }
        return (value.contains(".com") || value.contains(".cn")) ? "https://" + value : value;
    }

    /** 已知网盘域名(jar n()/o():ali/quark/uc/189/123 系/baidu/115)。 */
    static boolean isPanLink(String url) {
        String text = StringUtils.defaultString(url).toLowerCase();
        return text.contains("aliyundrive.com") || text.contains("alipan.com")
                || text.contains("pan.quark.cn") || text.contains("drive.uc.cn")
                || text.contains("cloud.189.cn") || text.contains("cloud.189.com")
                || is123(text) || text.contains("pan.baidu.com")
                || text.contains("115.com/s/") || text.contains("115cdn.com/s/");
    }

    private static boolean is123(String url) {
        return url.contains("123pan.com") || url.contains("123pan.cn") || url.contains("share.123pan.cn")
                || url.contains("123684.com") || url.contains("123865.com")
                || url.contains("123912.com") || url.contains("123592.com");
    }

    /** 正文首条楼层含「待登录」= Cookie 失效,该帖跳过。 */
    static boolean cookieExpired(String html) {
        if (StringUtils.isBlank(html)) {
            return false;
        }
        Element first = Jsoup.parse(html).selectFirst("div[class*=message]");
        return first != null && first.text().contains(LOGIN_MARKER);
    }

    /** 提取码折 URL 参数:统一 pwd=(115 特判 password=,已有参数不重复折)。 */
    static String foldPassword(String url, String code) {
        if (StringUtils.isBlank(url) || StringUtils.isBlank(code)) {
            return url;
        }
        String param = "8".equals(Message.parseType(url)) ? "password=" : "pwd=";
        return SiteSearchSupport.appendPasswordParam(url,
                URLEncoder.encode(code, StandardCharsets.UTF_8), param);
    }

    // ---------- 回复解锁(jar j():post-create-{tid}-1.htm) ----------

    /**
     * 自动回复解锁:站点发帖间隔冷却内直接跳过不发(阻塞共享搜索线程不值,下轮巡检自愈);
     * 响应含登录标记视为失败,其余按提交成功论(py 不看响应体,这里稍加判定)。
     */
    boolean replyToUnlock(String host, String cookie, String detailUrl, String threadId) {
        synchronized (this) {
            long cooldown = lastReplyAt > 0
                    ? lastReplyAt + REPLY_MIN_INTERVAL_MS - System.currentTimeMillis() : 0;
            if (cooldown > 0) {
                log.debug("kuafu reply cooldown ({}ms left), skip unlock for {}", cooldown, detailUrl);
                return false;
            }
            lastReplyAt = System.currentTimeMillis();
        }
        Request request = new Request.Builder()
                .url(host + "/post-create-" + threadId + "-1.htm")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", host)
                .header("Cookie", cookie)
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
            log.debug("kuafu reply failed for {}: {}", detailUrl, e.getMessage());
            return false;
        }
        boolean rejected = body.contains("请先登录") || body.contains("请登录") || body.contains("验证码");
        if (rejected) {
            log.debug("kuafu reply rejected for {}: {}", detailUrl, cleanText(body));
        }
        return !rejected;
    }

    /** 剥 HTML 标签/压空白。 */
    static String cleanText(String value) {
        String text = Jsoup.parseBodyFragment(StringUtils.defaultString(value)).text();
        return text.replaceAll("\\s+", " ").trim();
    }
}
