package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
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
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 玩偶聚合搜索源(atv-spiders/py/玩偶聚合.py 的 Java 移植):聚合玩偶系 MacCMS 网盘站
 * (玩偶/多多/木偶/欧歌/至臻/蜡笔/二小/虎斑/小斑/快映/闪电),并行按站搜索 → 卡片标题
 * 与订阅关键词粗匹配 → 抓详情页提取网盘分享链接,产出与 TG 搜索同构的 {@link Message},
 * 供追剧候选池(fillPool/preview)与 TG 结果按 link 去重合并。
 *
 * <p>站点域名由监控服务(pan-site-monitor)定期下发最新可达地址(按延迟排序),静态域名
 * 表仅作兜底种子;请求时逐域名 failover,成功域名置顶粘住,全域名失败进入冷却期避免反复撞墙。
 */
@Slf4j
@Service
public class WanouSearchService {
    private static final String DEFAULT_SEARCH_URL = "/index.php/vod/search/page/{page}/wd/{keyword}.html";
    /** 域名监控刷新周期 */
    private static final long DOMAIN_REFRESH_MS = 6 * 60 * 60_000L;
    /** 监控拉取失败后的重试间隔 */
    private static final long DOMAIN_RETRY_MS = 10 * 60_000L;
    /** 全域名失败的站点冷却期 */
    private static final long SITE_DEAD_COOLDOWN_MS = 30 * 60_000L;
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\\s\\u3400-\\u4dbf\\u4e00-\\u9fff\\u3000-\\u303f\\uff01-\\uff5e<>\"']+");
    private static final Pattern PASSWORD_IN_TEXT = Pattern.compile("(?:提取码|密码|访问码|pwd)[=:\\s：]*([a-zA-Z0-9]{4,6})");
    private static final String URL_TRAILING = "#，。；：,.;:！？、）)】」』》>\"'";
    /** 标题归一化:画质/站名噪声词 + 分隔符(py 玩偶聚合 _normalize_title) */
    private static final Pattern TITLE_NOISE = Pattern.compile("(?i)4k|hdr|2160p|1080p|720p|玩偶|木偶|蜡笔");
    private static final Pattern TITLE_SEPARATOR = Pattern.compile("[\\s\\-_.·,，。!！?？:：()（）\\[\\]]+");
    /** 关键词侧额外剥掉的集数/季/年份标记(订阅关键词常带"第2季/第12集/2025"后缀,卡片标题通常没有) */
    private static final Pattern KEYWORD_MARKER = Pattern.compile(
            "(?i)(第[0-9一二三四五六七八九十]{1,3}季|season\\d{1,2}|s\\d{1,2}e\\d{1,3}|ep?\\d{1,3}|第\\d{1,3}集|更新?至\\d{1,3}|全\\d{1,3}集|\\d{1,3}集|20\\d{2})");
    /** 站点优先级(py site_priority):同名合并去重时优先保留靠前站点的链接 */
    private static final List<String> SITE_PRIORITY = List.of(
            "wanou", "duoduo", "muou", "ouge", "zhizhen", "labi", "erxiao", "huban", "xiaoban", "kuaiying", "shandian");

    record Site(String id, String name, String monitorKey, List<String> seedDomains,
                String searchUrl, int timeoutSeconds, boolean clipboardDetail) {
    }

    record Card(String href, String title, String remarks) {
    }

    private static final List<Site> SITES = List.of(
            new Site("muou", "木偶", "木偶",
                    List.of("https://www.muou.site", "https://www.muou.asia", "https://666.666291.xyz", "https://123.666291.xyz"),
                    null, 10, false),
            new Site("ouge", "欧歌", "欧哥",
                    List.of("https://woog.nxog.eu.org", "https://woog.430520.xyz", "https://woog.nxog.fun"),
                    null, 10, false),
            new Site("zhizhen", "至臻", "至臻",
                    List.of("https://www.mihdr.top", "https://www.miqk.cc", "https://mihdr.top"),
                    null, 10, false),
            new Site("erxiao", "二小", "二小",
                    List.of("https://www.2xiaopan.top", "https://wexwp.cc", "https://www.wexwp.cc"),
                    null, 10, false),
            new Site("duoduo", "多多", "多多",
                    List.of("https://tv.yydsys.top", "https://tv.yydsys.cc", "https://yydsys.de5.net", "https://tv.214521.xyz"),
                    null, 10, false),
            new Site("labi", "蜡笔", "蜡笔",
                    List.of("http://xiaocgege.shop", "http://feimo.fun", "http://tvpanpan.site"),
                    null, 10, false),
            new Site("huban", "虎斑", "虎斑",
                    List.of("http://121.205.88.174:16969"),
                    null, 10, true),
            new Site("xiaoban", "小斑", "小斑",
                    List.of("http://121.205.88.174:12512"),
                    null, 20, true),
            new Site("wanou", "玩偶", "玩偶",
                    List.of("https://woggpan.xxooo.cf", "https://wogg.xxooo.cf", "https://woggpan.888484.xyz", "https://www.wogg.net"),
                    "/vodsearch/-------------.html?wd={keyword}&page={page}", 10, false),
            new Site("kuaiying", "快映", null,
                    List.of("http://xsayang.fun:12512"),
                    null, 10, false),
            new Site("shandian", "闪电", "闪电",
                    List.of("http://sd.sduc.site", "http://shandian.blog"),
                    null, 10, false));

    private static final class DomainState {
        volatile List<String> ordered;
        volatile long deadUntil;

        DomainState(List<String> seed) {
            this.ordered = seed;
        }
    }

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final Map<String, DomainState> domainStates = new ConcurrentHashMap<>();
    private final AtomicLong monitorRefreshedAt = new AtomicLong(0);
    private final AtomicBoolean monitorRefreshing = new AtomicBoolean(false);
    /** 站点池线程序号(线程名 wanou-search-N):11 站并发时日志可分辨线程 */
    private static final AtomicInteger SEARCH_SEQ = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(SITES.size(), r -> {
        Thread thread = new Thread(r, "wanou-search-" + SEARCH_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    public WanouSearchService(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        for (Site site : SITES) {
            domainStates.put(site.id(), new DomainState(site.seedDomains()));
        }
    }

    /**
     * 并行搜索全部站点:每站搜第 1 页 → 卡片标题粗匹配 → 抓前 N 个详情页提取分享链接。
     * 结果按站点优先级顺序去重合并(同一分享多站收录时保留靠前站点)。
     */
    public List<Message> search(String keyword) {
        if (!appProperties.getSubscription().isWanouEnabled() || StringUtils.isBlank(keyword)) {
            return List.of();
        }
        String kw = keyword.trim();
        refreshDomainsIfNeeded();

        List<Site> sites = SITES.stream()
                .sorted((a, b) -> Integer.compare(siteRank(a.id()), siteRank(b.id())))
                .toList();
        List<Future<List<Message>>> futures = new ArrayList<>();
        for (Site site : sites) {
            futures.add(executor.submit(() -> searchSite(site, kw)));
        }

        long deadline = System.currentTimeMillis() + appProperties.getSubscription().getWanouTimeoutSeconds() * 1000L;
        List<Message> result = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        boolean cancelled = false;
        for (Future<List<Message>> future : futures) {
            if (cancelled) {
                future.cancel(true);
                continue;
            }
            long wait = deadline - System.currentTimeMillis();
            if (wait <= 0) {
                cancelled = true;
                future.cancel(true);
                continue;
            }
            try {
                for (Message message : future.get(wait, TimeUnit.MILLISECONDS)) {
                    if (seenLinks.add(message.getLink())) {
                        result.add(message);
                    }
                }
            } catch (TimeoutException e) {
                cancelled = true;
                future.cancel(true);
                log.warn("wanou search {} partial: overall timeout", kw);
            } catch (ExecutionException e) {
                log.debug("wanou search {} site task failed: {}", kw, String.valueOf(e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled = true;
                future.cancel(true);
            }
        }
        log.info("Wanou aggregate search {} get {} results", kw, result.size());
        return result;
    }

    private List<Message> searchSite(Site site, String keyword) {
        DomainState state = domainStates.get(site.id());
        if (System.currentTimeMillis() < state.deadUntil) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>();
        try {
            List<Card> cards = parseSearchCards(requestWithFailover(site, buildSearchPath(site, keyword)));
            int maxDetails = appProperties.getSubscription().getWanouMaxDetailPages();
            int details = 0;
            Set<String> seenLinks = new HashSet<>();
            for (Card card : cards) {
                if (details >= maxDetails) {
                    break;
                }
                if (StringUtils.isBlank(card.href()) || !matchKeyword(card.title(), keyword)) {
                    continue;
                }
                try {
                    List<String> panUrls = parseDetailPanUrls(site, requestWithFailover(site, card.href()));
                    details++;
                    for (String url : panUrls) {
                        String type = Message.parseType(url);
                        if (type == null || !seenLinks.add(url)) {
                            continue;
                        }
                        messages.add(toMessage(site, card, url, type));
                    }
                } catch (Exception e) {
                    log.debug("wanou site {} detail {} failed: {}", site.id(), card.href(), e.getMessage());
                }
            }
        } catch (Exception e) {
            state.deadUntil = System.currentTimeMillis() + SITE_DEAD_COOLDOWN_MS;
            log.debug("wanou site {} search failed: {}", site.id(), e.getMessage());
        }
        return messages;
    }

    private Message toMessage(Site site, Card card, String url, String type) {
        Message message = new Message();
        message.setType(type);
        message.setLink(url);
        message.setName(card.title());
        message.setChannel(site.name());
        message.setContent((card.title() + " " + StringUtils.defaultString(card.remarks())).trim());
        return message;
    }

    private static int siteRank(String siteId) {
        int index = SITE_PRIORITY.indexOf(siteId);
        return index < 0 ? 999 : index;
    }

    static Site siteById(String siteId) {
        return SITES.stream().filter(site -> site.id().equals(siteId)).findFirst().orElse(null);
    }

    private static String buildSearchPath(Site site, String keyword) {
        String template = site.searchUrl() == null ? DEFAULT_SEARCH_URL : site.searchUrl();
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8).replace("+", "%20");
        return template.replace("{keyword}", encoded).replace("{page}", "1");
    }

    /** 搜索结果卡片解析(py _parse_search_cards 的 Jsoup 等价实现)。 */
    List<Card> parseSearchCards(String html) {
        Document doc = Jsoup.parse(html);
        List<Card> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element card : doc.select(".module-search-item")) {
            Element serial = card.selectFirst(".video-serial");
            String href = serial == null ? "" : StringUtils.trimToEmpty(serial.attr("href"));
            if (href.isEmpty()) {
                Element a = card.selectFirst("a[href]");
                href = a == null ? "" : a.attr("href").trim();
            }
            String title = serial == null ? "" : serial.attr("title").trim();
            if (title.isEmpty()) {
                Element img = card.selectFirst("img[alt]");
                title = img == null ? "" : img.attr("alt").trim();
            }
            if (title.isEmpty()) {
                Element any = card.selectFirst("[title]");
                title = any == null ? "" : any.attr("title").trim();
            }
            Element text = card.selectFirst(".module-item-text");
            String remarks = text == null ? "" : text.text().trim();
            if (href.isEmpty() || title.isEmpty() || !seen.add(href)) {
                continue;
            }
            cards.add(new Card(href, title, remarks));
        }
        return cards;
    }

    /**
     * 详情页分享链接提取:虎斑/小斑走 data-clipboard-text(剪贴板属性里是完整分享链),
     * 其余站点取 module-row-info 下 p 文本的第一个 URL;行内"提取码"折进 ?password= 参数。
     */
    List<String> parseDetailPanUrls(Site site, String html) {
        Document doc = Jsoup.parse(html);
        Set<String> urls = new LinkedHashSet<>();
        if (site.clipboardDetail()) {
            for (Element node : doc.select(".module-row-info .module-row-text")) {
                addShareUrl(urls, node.attr("data-clipboard-text"));
            }
        } else {
            for (Element p : doc.select(".module-row-info p")) {
                addShareUrl(urls, p.text());
            }
        }
        return List.copyOf(urls);
    }

    private void addShareUrl(Set<String> urls, String raw) {
        String text = normalizeShareUrl(StringUtils.trimToEmpty(raw));
        Matcher matcher = URL_IN_TEXT.matcher(text);
        if (!matcher.find()) {
            return;
        }
        String url = matcher.group();
        while (!url.isEmpty() && URL_TRAILING.indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }
        Matcher password = PASSWORD_IN_TEXT.matcher(text);
        if (password.find() && !url.contains("#")) { // 锚点后拼参数无效,不折
            url = SiteSearchSupport.appendPasswordParam(url, password.group(1), "password=");
        }
        urls.add(url);
    }

    /** 修正站点详情页常见的 "hhttps://" 复制瑕疵(py _normalize_share_url)。 */
    private static String normalizeShareUrl(String value) {
        if (value.startsWith("hhttps://")) {
            return "https://" + value.substring("hhttps://".length());
        }
        if (value.startsWith("hhttp://")) {
            return "http://" + value.substring("hhttp://".length());
        }
        return value;
    }

    /**
     * 卡片标题与关键词粗匹配(抓详情前的成本闸门,精确过滤仍由 fillPool 的 matchesTitle 把关):
     * 归一化(去画质/站名噪声与分隔符)后双向包含。
     */
    boolean matchKeyword(String cardTitle, String keyword) {
        String card = normalizeTitle(cardTitle);
        String kw = KEYWORD_MARKER.matcher(normalizeTitle(keyword)).replaceAll("");
        // 归一化后为空(关键词纯"2025"被剥空 / 卡片标题全是噪声词)是无效匹配形态:
        // kw.contains("") 恒真会放行无关卡片白吃详情页预算(空关键词已在 search 入口拦截)
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

    String requestWithFailover(Site site, String pathOrUrl) throws IOException {
        List<String> domains = new ArrayList<>(domainStates.get(site.id()).ordered);
        IOException lastError = null;
        for (int i = 0; i < domains.size(); i++) {
            String domain = domains.get(i);
            String url = pathOrUrl.startsWith("http") ? pathOrUrl : buildAbsoluteUrl(domain, pathOrUrl);
            try {
                String html = fetch(url, site.timeoutSeconds());
                if (StringUtils.isNotBlank(html)) {
                    if (i > 0) {
                        promoteDomain(site.id(), domain);
                    }
                    return html;
                }
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw new IOException("site " + site.id() + " all " + domains.size() + " domains failed"
                + (lastError == null ? "" : ": " + lastError.getMessage()));
    }

    private void promoteDomain(String siteId, String domain) {
        DomainState state = domainStates.get(siteId);
        synchronized (state) {
            List<String> ordered = new ArrayList<>(state.ordered);
            ordered.remove(domain);
            ordered.add(0, domain);
            state.ordered = ordered;
        }
    }

    static String buildAbsoluteUrl(String base, String path) {
        String raw = StringUtils.trimToEmpty(path);
        if (raw.isEmpty()) {
            return "";
        }
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return raw;
        }
        if (raw.startsWith("//")) {
            return "https:" + raw;
        }
        return StringUtils.stripEnd(base, "/") + "/" + StringUtils.stripStart(raw, "/");
    }

    /**
     * 从监控服务拉取各站最新可达域名(已按延迟排序),与静态种子合并:
     * 监控可达域名优先,种子补缺,监控标记失败的域名垫底。失败沿用现有域名,10 分钟后重试。
     */
    void refreshDomainsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - monitorRefreshedAt.get() < DOMAIN_REFRESH_MS) {
            return;
        }
        if (!monitorRefreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            String api = StringUtils.trimToEmpty(appProperties.getSubscription().getWanouMonitorUrl());
            if (api.isEmpty()) {
                monitorRefreshedAt.set(now);
                return;
            }
            JsonNode sites = objectMapper.readTree(fetch(api, 10)).path("sites");
            if (!sites.isObject()) {
                monitorRefreshedAt.set(now - DOMAIN_REFRESH_MS + DOMAIN_RETRY_MS);
                return;
            }
            int updated = 0;
            for (Site site : SITES) {
                if (site.monitorKey() == null) {
                    continue;
                }
                JsonNode node = sites.path(site.monitorKey());
                if (node.isMissingNode()) {
                    continue;
                }
                LinkedHashSet<String> merged = new LinkedHashSet<>();
                List<String> failed = new ArrayList<>();
                for (JsonNode entry : node.path("urls")) {
                    String url = StringUtils.stripEnd(entry.path("url").asText("").trim(), "/");
                    if (url.isEmpty()) {
                        continue;
                    }
                    if (entry.path("has_keyword").asBoolean(false)) {
                        merged.add(url);
                    } else {
                        failed.add(url);
                    }
                }
                merged.addAll(site.seedDomains());
                merged.addAll(failed);
                domainStates.get(site.id()).ordered = List.copyOf(merged);
                updated++;
            }
            monitorRefreshedAt.set(now);
            log.info("wanou domains refreshed from monitor: {} sites updated", updated);
        } catch (Exception e) {
            log.warn("wanou monitor refresh failed, keep current domains: {}", e.getMessage());
            monitorRefreshedAt.set(now - DOMAIN_REFRESH_MS + DOMAIN_RETRY_MS);
        } finally {
            monitorRefreshing.set(false);
        }
    }

    /** 单请求抓取:非 200/空体返回 null(视作该域名失败),异常上抛由 failover 兜住。
     * Cloudflare 挑战页返回 200 + 小体积挑战 HTML,不识别成失败的话 failover 永远轮不到后面的域名。 */
    protected String fetch(String url, int timeoutSeconds) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", appProperties.getUserAgent())
                .header("Referer", rootOf(url))
                .build();
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(Math.min(timeoutSeconds, 10), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String body = response.body().string();
            if (isChallenge(response.header("cf-mitigated"), body)) {
                log.debug("wanou fetch blocked by challenge page: {}", url);
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
        return body == null || body.isBlank()
                || body.contains("challenges.cloudflare.com")
                || body.contains("Just a moment");
    }

    private static String rootOf(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return host == null ? url : uri.getScheme() + "://" + host + "/";
        } catch (Exception e) {
            return url;
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
