package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 盘聚搜索源(atv-spiders/py/盘聚.py 的 Java 移植):seedhub 系网盘聚合站,官方域名轮换
 * 频繁(内置 6 域名种子 + /worker-json-hosts/ 动态下发最新列表)。Cloudflare 指纹门禁:
 * OpenSSL 系指纹(curl / urllib3 1.x)被 403,JDK/JSSE 指纹(OkHttp)实测可过 —— Java 侧
 * 无需 py 版的 TLS 伪装适配器;挑战页按标记识别,命中视为该域名失败走 failover,
 * 全域名失败静默返回空不拖垮其它源。
 *
 * <p>搜索页 .cover 卡片 → 标题粗匹配 → 详情页 .pan-links 行(data-link 属性直接标盘型
 * 域名,免请求预判)→ 按候选池价值排序盘型、上限截断后解析 link_start 站内中转页
 * (var panLink = "真实分享链")产出与 TG 搜索同构的 {@link Message}。中转页是必要开销:
 * 详情页只有站内跳转链,真实分享链(含百度 pwd 提取码内嵌)在中转页脚本里。
 */
@Slf4j
@Service
public class PanjuSearchService {
    private static final String NAME = "盘聚";
    private static final String HOSTS_API_PATH = "/worker-json-hosts/";
    /** py 版同款 iPhone UA:与实测过盾的请求形态保持一致,不随全局 UA 漂移 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) AppleWebKit/604.1.14 (KHTML, like Gecko)";
    private static final List<String> SEED_HOSTS = List.of(
            "https://sidhub.cc", "https://seedog.cc", "https://seeduck.cc",
            "https://hubdog.cc", "https://哈巴狗.com", "https://www.seedhub.cc");
    /** 官方域名列表刷新周期 / 拉取失败重试间隔(同玩偶监控口径) */
    private static final long HOSTS_REFRESH_MS = 6 * 60 * 60_000L;
    private static final long HOSTS_RETRY_MS = 10 * 60_000L;
    private static final int REQUEST_TIMEOUT_SECONDS = 10;

    /**
     * 盘型解析优先级:候选池价值排序(夸克/UC/阿里转存主力盘在前),与 py 版 disk_priority
     * (百度最前)不同 —— 解析有上限,排序决定配额花在哪些盘上。
     */
    private static final Map<String, Integer> DISK_PRIORITY = Map.ofEntries(
            Map.entry("5", 1), Map.entry("7", 2), Map.entry("0", 3), Map.entry("10", 4),
            Map.entry("8", 5), Map.entry("9", 6), Map.entry("3", 7), Map.entry("2", 8),
            Map.entry("6", 9), Map.entry("1", 10));

    private static final Pattern MOVIE_ID = Pattern.compile("/movies/(\\d+)/?");
    /** 中转页真实分享链:var panLink = "..." 为主,window.open / location.href 与裸盘链兜底(py _extract_pan_link) */
    private static final Pattern PAN_LINK_VAR = Pattern.compile("panLink\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern PAN_LINK_OPEN = Pattern.compile("window\\.open\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");
    private static final Pattern PAN_LINK_LOCATION = Pattern.compile("location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern PAN_LINK_URL = Pattern.compile(
            "https?://(?:pan\\.baidu\\.com|pan\\.quark\\.cn|drive\\.uc\\.cn|cloud\\.189\\.cn|(?:www\\.)?alipan\\.com|"
                    + "pan\\.aliyun\\.com|(?:www\\.)?aliyundrive\\.com|pan\\.xunlei\\.com|(?:www\\.)?(?:123pan\\.com|123pan\\.cn|123684\\.com|123865\\.com|123912\\.com|123592\\.com)|"
                    + "(?:www\\.)?(?:115\\.com|anxia\\.com)|yun\\.139\\.com|mcloud\\.139\\.com|mypikpak\\.com)/[^\\s\"'<>]+",
            Pattern.CASE_INSENSITIVE);
    /** Cloudflare 挑战页标记(py _is_cloudflare_blocked + 玩偶源 cf-mitigated 头口径) */
    private static final List<String> CHALLENGE_MARKERS = List.of(
            "Just a moment", "Enable JavaScript and cookies to continue", "challenge-platform",
            "cf-turnstile", "Attention Required! | Cloudflare", "challenges.cloudflare.com");
    /** 标题归一化:画质噪声词 + 分隔符(玩偶源同口径) */
    private static final Pattern TITLE_NOISE = Pattern.compile("(?i)4k|hdr|2160p|1080p|720p|蓝光|原盘");
    private static final Pattern TITLE_SEPARATOR = Pattern.compile("[\\s\\-_.·,，。!！?？:：()（）\\[\\]]+");
    /** 关键词侧剥掉的集数/季/年份标记(订阅关键词常带后缀,卡片标题通常没有) */
    private static final Pattern KEYWORD_MARKER = Pattern.compile(
            "(?i)(第[0-9一二三四五六七八九十]{1,3}季|season\\d{1,2}|s\\d{1,2}e\\d{1,3}|ep?\\d{1,3}|第\\d{1,3}集|更新?至\\d{1,3}|全\\d{1,3}集|\\d{1,3}集|20\\d{2})");

    record Card(String id, String title) {
    }

    /** 详情页网盘行:href=站内中转链,disk=TVBox 盘型代码,label=资源标题(带集数进度) */
    record PanRow(String disk, String href, String label) {
    }

    /** failover 成功的一次抓取:url 用于把详情页里的相对中转链拼成绝对地址 */
    record Fetched(String url, String html) {
    }

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient();
    private volatile List<String> hosts = normalizeSeeds();
    private final AtomicLong hostsRefreshedAt = new AtomicLong(0);
    private final AtomicBoolean hostsRefreshing = new AtomicBoolean(false);
    private final Object hostLock = new Object();

    public PanjuSearchService(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    private static List<String> normalizeSeeds() {
        List<String> normalized = new ArrayList<>();
        for (String seed : SEED_HOSTS) {
            String host = SiteSearchSupport.normalizeHost(seed, "");
            if (!host.isEmpty() && !normalized.contains(host)) {
                normalized.add(host);
            }
        }
        return List.copyOf(normalized);
    }

    /**
     * 搜索第 1 页 → 卡片标题粗匹配 → 抓前 N 个详情页 → 按盘型优先级解析中转链。
     * 任一环节失败/超时静默返回已收集的部分,不抛出(多源聚合里单源失败不影响其它源)。
     */
    public List<Message> search(String keyword) {
        if (!appProperties.getSubscription().isPanjuEnabled() || StringUtils.isBlank(keyword)) {
            return List.of();
        }
        String kw = keyword.trim();
        refreshHostsIfNeeded();
        long deadline = System.currentTimeMillis()
                + Math.max(5, appProperties.getSubscription().getPanjuTimeoutSeconds()) * 1000L;

        List<Message> result = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        try {
            Fetched search = requestWithFailover("/s/" + URLEncoder.encode(kw, StandardCharsets.UTF_8).replace("+", "%20") + "/?page=1");
            int maxDetails = Math.max(1, appProperties.getSubscription().getPanjuMaxDetailPages());
            int maxResolves = Math.max(1, appProperties.getSubscription().getPanjuMaxResolves());
            int details = 0;
            for (Card card : parseSearchCards(search.html())) {
                if (details >= maxDetails || System.currentTimeMillis() > deadline) {
                    break;
                }
                if (card.id().isEmpty() || !matchKeyword(card.title(), kw)) {
                    continue;
                }
                try {
                    Fetched detail = requestWithFailover("/movies/" + card.id() + "/");
                    details++;
                    List<PanRow> rows = parsePanRows(detail.html());
                    int resolved = 0;
                    for (PanRow row : rows) {
                        if (resolved >= maxResolves || System.currentTimeMillis() > deadline) {
                            break;
                        }
                        String link = resolvePanLink(WanouSearchService.buildAbsoluteUrl(rootOf(detail.url()), row.href()));
                        resolved++;
                        String type = link.isEmpty() ? null : Message.parseType(link);
                        if (type == null || !SiteSearchSupport.isNumeric(type) || !seenLinks.add(link)) {
                            continue; // 中转解析失败 / 磁力或未知盘:对候选池无意义
                        }
                        result.add(toMessage(card, link, type, row.label()));
                    }
                } catch (Exception e) {
                    log.debug("panju detail {} failed: {}", card.id(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("panju search {} failed: {}", kw, e.getMessage());
        }
        log.info("Panju search {} get {} results", kw, result.size());
        return result;
    }

    private Message toMessage(Card card, String link, String type, String label) {
        Message message = new Message();
        message.setType(type);
        message.setLink(link);
        message.setName(card.title());
        message.setChannel(NAME);
        // 资源标题自带集数进度(更新至08集/附1-2季),是候选打分比片名更有信息量的文本
        message.setContent(StringUtils.defaultString(label));
        return message;
    }

    /** 搜索结果卡片解析(py _parse_cards 的 Jsoup 等价实现):.cover 容器,img@alt 为标题。 */
    List<Card> parseSearchCards(String html) {
        Document doc = Jsoup.parse(html);
        List<Card> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element cover : doc.select(".cover")) {
            Element anchor = cover.selectFirst("a[href]");
            String href = anchor == null ? "" : anchor.attr("href").trim();
            String id = "";
            Matcher matcher = MOVIE_ID.matcher(href);
            if (matcher.find()) {
                id = matcher.group(1);
            }
            String title = "";
            Element img = cover.selectFirst("img[alt]");
            if (img != null) {
                title = img.attr("alt").trim();
            }
            if (title.isEmpty() && anchor != null) {
                title = anchor.attr("title").trim();
            }
            if (id.isEmpty() || title.isEmpty() || !seen.add(id)) {
                continue;
            }
            cards.add(new Card(id, title));
        }
        return cards;
    }

    private static final Pattern REDIRECT_TARGET = Pattern.compile("redirect_to=([a-zA-Z0-9_]+)");

    /**
     * 详情页网盘行解析:仅收 .pan-links 下的行(seed_list 磁力行不在此列),盘型三级判定
     * (data-link 域名提示 → href 域名 → 标题文本),按候选池价值排序输出。
     * 去重按 redirect_to 目标(同一分享多行收录时 movie_title 参数不同,href 全串去重不掉,
     * 会白占中转解析配额)。
     */
    List<PanRow> parsePanRows(String html) {
        Document doc = Jsoup.parse(html);
        List<PanRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 1;
        for (Element anchor : doc.select(".pan-links li a[href]")) {
            String href = anchor.attr("href").trim();
            if (href.isEmpty() || href.contains("seed_id=")) {
                continue;
            }
            Matcher target = REDIRECT_TARGET.matcher(href);
            if (!seen.add(target.find() ? target.group(1) : href)) {
                continue;
            }
            String disk = diskType(anchor.attr("data-link"));
            if (disk == null) {
                disk = diskType(href);
            }
            if (disk == null) {
                disk = diskTypeFromText(anchor.attr("title") + " " + anchor.text());
            }
            if (disk == null) {
                continue; // 未知盘/磁力:不占中转解析配额
            }
            String label = firstNonBlank(anchor.attr("title"), movieTitleFromHref(href), anchor.text(), "网盘" + index);
            rows.add(new PanRow(disk, stripMovieTitle(href), label));
            index++;
        }
        rows.sort(Comparator.comparingInt(row -> DISK_PRIORITY.getOrDefault(row.disk(), 99)));
        return rows;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    /** href 里 movie_title 参数兜底资源标题(py _extract_movie_title_from_href)。 */
    private static String movieTitleFromHref(String href) {
        int start = href.indexOf("movie_title=");
        if (start < 0) {
            return "";
        }
        start += "movie_title=".length();
        int end = href.indexOf('&', start);
        return end < 0 ? href.substring(start) : href.substring(start, end);
    }

    /**
     * 中转链只保留 redirect_to 参数:movie_title 值是裸 Unicode(▶️/中文),
     * 带着它发请求会让 Referer 头构造炸掉(OkHttp 拒绝非 ASCII 头),解析也用不到它。
     */
    private static String stripMovieTitle(String href) {
        Matcher target = REDIRECT_TARGET.matcher(href);
        if (!target.find()) {
            return href;
        }
        int query = href.indexOf('?');
        return query < 0 ? href : href.substring(0, query + 1) + "redirect_to=" + target.group(1);
    }

    /** 盘型域名判定 → TVBox 盘型代码(与 Message.parseType 同口径,域名单独给时也认)。 */
    static String diskType(String value) {
        String text = StringUtils.trimToEmpty(value).toLowerCase();
        if (text.isEmpty()) {
            return null;
        }
        if (text.contains("pan.baidu.com")) {
            return "10";
        }
        if (text.contains("quark.cn")) {
            return "5";
        }
        if (text.contains("drive.uc.cn") || text.contains("uc.cn")) {
            return "7";
        }
        if (text.contains("alipan.com") || text.contains("aliyundrive.com") || text.contains("pan.aliyun.com")) {
            return "0";
        }
        if (text.contains("pan.xunlei.com") || text.contains("xunlei.com")) {
            return "2";
        }
        if (text.contains("123pan.") || text.contains("123684.") || text.contains("123685.")
                || text.contains("123912.") || text.contains("123592.") || text.contains("123865.")) {
            return "3";
        }
        if (text.contains("115.com") || text.contains("115cdn") || text.contains("anxia.com")) {
            return "8";
        }
        if (text.contains("189.cn") || text.contains("21cn.com")) {
            return "9";
        }
        if (text.contains("139.com")) {
            return "6";
        }
        if (text.contains("mypikpak.com")) {
            return "1";
        }
        return null;
    }

    /** 标题文本盘型兜底(py _detect_disk_type_from_text,中文站习惯直接标盘名)。 */
    static String diskTypeFromText(String text) {
        String value = StringUtils.trimToEmpty(text).toLowerCase();
        if (value.isEmpty()) {
            return null;
        }
        if (value.contains("夸克")) {
            return "5";
        }
        if (value.contains("百度")) {
            return "10";
        }
        if (value.contains("阿里")) {
            return "0";
        }
        if (value.contains("迅雷")) {
            return "2";
        }
        if (value.contains("天翼") || value.contains("189")) {
            return "9";
        }
        if (value.contains("115")) {
            return "8";
        }
        if (value.contains("123")) {
            return "3";
        }
        if (value.contains("移动") || value.contains("139")) {
            return "6";
        }
        if (value.contains("uc")) {
            return "7";
        }
        return null;
    }

    /**
     * 中转页解析:var panLink = "真实分享链" 为主,window.open / location.href 次之,
     * 页面文本里的裸盘链兜底;解析结果须是可挂载盘链(Message.parseType 数字)。
     */
    String resolvePanLink(String url) {
        if (StringUtils.isBlank(url)) {
            return "";
        }
        String html;
        try {
            html = fetch(url);
        } catch (Exception e) { // 单条坏链(网络/头构造异常)只作废该行,不中断同卡剩余行
            log.debug("panju resolve {} failed: {}", url, e.getMessage());
            return "";
        }
        if (StringUtils.isBlank(html)) {
            return "";
        }
        for (Pattern pattern : List.of(PAN_LINK_VAR, PAN_LINK_OPEN, PAN_LINK_LOCATION)) {
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                String link = matcher.group(1).trim();
                if (isMountablePanLink(link)) {
                    return link;
                }
            }
        }
        Matcher matcher = PAN_LINK_URL.matcher(html);
        return matcher.find() ? matcher.group().trim() : "";
    }

    private static boolean isMountablePanLink(String link) {
        String type = Message.parseType(link);
        return type != null && SiteSearchSupport.isNumeric(type);
    }

    /**
     * 卡片标题与关键词粗匹配(抓详情前的成本闸门,精确过滤仍由 fillPool 的 matchesTitle 把关):
     * 归一化(去画质噪声与分隔符)后双向包含。卡片常带英文剧名后缀,包含判定天然兼容。
     */
    boolean matchKeyword(String cardTitle, String keyword) {
        String card = normalizeTitle(cardTitle);
        String kw = KEYWORD_MARKER.matcher(normalizeTitle(keyword)).replaceAll("");
        if (kw.isEmpty() || card.isEmpty()) {
            return false;
        }
        return card.contains(kw) || kw.contains(card);
    }

    static String normalizeTitle(String value) {
        String text = StringUtils.lowerCase(StringUtils.defaultString(value));
        text = TITLE_NOISE.matcher(text).replaceAll("");
        return TITLE_SEPARATOR.matcher(text).replaceAll("");
    }

    /** 域名 failover:逐域名尝试,首个成功域名粘到队首(py _request_html 的 promote 行为)。 */
    Fetched requestWithFailover(String path) throws IOException {
        List<String> candidates = hosts;
        IOException lastError = null;
        for (int i = 0; i < candidates.size(); i++) {
            String host = candidates.get(i);
            String url = WanouSearchService.buildAbsoluteUrl(host, path);
            try {
                String html = fetch(url);
                if (StringUtils.isNotBlank(html)) {
                    if (i > 0) {
                        promoteHost(host);
                    }
                    return new Fetched(url, html);
                }
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw new IOException("panju all " + candidates.size() + " hosts failed"
                + (lastError == null ? "" : ": " + lastError.getMessage()));
    }

    private void promoteHost(String host) {
        synchronized (hostLock) {
            List<String> ordered = new ArrayList<>(hosts);
            ordered.remove(host);
            ordered.add(0, host);
            hosts = List.copyOf(ordered);
        }
    }

    /** 从任一可达域名拉取官方最新域名列表(JSON 数组),失败沿用现有域名 10 分钟后重试。 */
    void refreshHostsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - hostsRefreshedAt.get() < HOSTS_REFRESH_MS) {
            return;
        }
        if (!hostsRefreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            // 只试队首 2 个域名:全队轮询在最坏情况(域名全死,每个吃满超时)会把首次搜索拖住
            for (String host : hosts.subList(0, Math.min(2, hosts.size()))) {
                String body = fetch(WanouSearchService.buildAbsoluteUrl(host, HOSTS_API_PATH));
                if (StringUtils.isBlank(body)) {
                    continue;
                }
                JsonNode domains = objectMapper.readTree(body);
                if (!domains.isArray()) {
                    continue;
                }
                LinkedHashSet<String> merged = new LinkedHashSet<>();
                for (JsonNode entry : domains) {
                    String normalized = SiteSearchSupport.normalizeHost(entry.asText(""), "");
                    if (!normalized.isEmpty()) {
                        merged.add(normalized);
                    }
                }
                if (merged.isEmpty()) {
                    continue;
                }
                merged.addFirst(host); // 拉取成功的域名置顶(可达性已验证)
                merged.addAll(normalizeSeeds());
                hosts = List.copyOf(merged);
                hostsRefreshedAt.set(now);
                log.info("panju hosts refreshed: {} domains, first={}", hosts.size(), hosts.get(0));
                return;
            }
            hostsRefreshedAt.set(now - HOSTS_REFRESH_MS + HOSTS_RETRY_MS);
        } catch (Exception e) {
            log.debug("panju hosts refresh failed, keep current: {}", e.getMessage());
            hostsRefreshedAt.set(now - HOSTS_REFRESH_MS + HOSTS_RETRY_MS);
        } finally {
            hostsRefreshing.set(false);
        }
    }

    /**
     * 单请求抓取:非 200/空体返回 null(视作该域名失败),挑战页返回 200 + 挑战 HTML,
     * 不识别成失败的话 failover 永远轮不到后面的域名。
     */
    protected String fetch(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", rootOf(url))
                .build();
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String body = response.body().string();
            if (isChallenge(response.header("cf-mitigated"), body)) {
                log.debug("panju fetch blocked by challenge page: {}", url);
                return null;
            }
            return body;
        }
    }

    /** Cloudflare 挑战判定:cf-mitigated 响应头、挑战页标记、空体。 */
    static boolean isChallenge(String cfMitigated, String body) {
        if ("challenge".equalsIgnoreCase(cfMitigated)) {
            return true;
        }
        if (body == null || body.isBlank()) {
            return true;
        }
        for (String marker : CHALLENGE_MARKERS) {
            if (body.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 手工切 host:URI.create 遇到路径里的裸 Unicode 会抛异常(中转链 movie_title 参数实测如此)。 */
    private static String rootOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int end = url.indexOf('/', scheme + 3);
        return end < 0 ? url : url.substring(0, end) + "/";
    }

}
