package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.WanouDomainStatus;
import cn.har01d.alist_tvbox.dto.WanouSiteStatus;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
import java.util.stream.Collectors;

/**
 * 玩偶聚合搜索源(atv-spiders/py/玩偶聚合.py 的 Java 移植):聚合玩偶系 MacCMS 网盘站
 * (玩偶/多多/木偶/快映/闪电/表哥/花卷/欧歌/虎斑),并行按站搜索 → 卡片标题
 * 与订阅关键词粗匹配 → 抓详情页提取网盘分享链接,产出与 TG 搜索同构的 {@link Message},
 * 供追剧候选池(fillPool/preview)与 TG 结果按 link 去重合并。
 * <p>2026-09-20 与 py 同步(atv-spiders 9880ef2):移除六死站(欧歌/至臻/二小/蜡笔/虎斑/小斑),
 * 新增表哥(punycode 域名)与花卷(海报卡片 + down-card-url 详情形状)。
 * <p>2026-09-26 与 py 对齐:欧歌(woog 新入口)与虎斑(裸 IP 轮换入口,38.76.197.172/xhban.xyz
 * 均 302 到 43.248.128.118)实测复活回归,两站详情形状为 module-row-info 容器文本自身(非其下 p)。
 *
 * <p>站点域名池 = 静态种子 ∪ 监控服务(pan-site-monitor)下发的候选(含其标记失败的域名,
 * 可能复活);本服务定时主动探测各域名可达性与延迟,按延迟升序重排——搜索直接从最优域名
 * 起步,请求时逐域名 failover 与成功粘滞作为探测间隙内的兜底,全域名失败进入冷却期。
 */
@Slf4j
@Service
public class WanouSearchService {
    private static final String DEFAULT_SEARCH_URL = "/index.php/vod/search/page/{page}/wd/{keyword}.html";
    /** 标准搜索卡片选择器(花卷等海报站可覆写) */
    private static final String DEFAULT_SEARCH_CARD_CSS = ".module-search-item";
    /** 标准详情分享链接选择器(花卷走 down-card-url) */
    private static final String DEFAULT_DETAIL_PAN_CSS = ".module-row-info p";
    /** 域名监控刷新周期 */
    private static final long DOMAIN_REFRESH_MS = 6 * 60 * 60_000L;
    /** 监控拉取失败后的重试间隔 */
    private static final long DOMAIN_RETRY_MS = 10 * 60_000L;
    /** 全域名失败的站点冷却期 */
    private static final long SITE_DEAD_COOLDOWN_MS = 30 * 60_000L;
    /** 内置域名探测周期(一小时:域名轮换是小时/天级,再快只是白撞 Cloudflare) */
    private static final long DOMAIN_PROBE_INTERVAL_MS = 60 * 60_000L;
    /** 单域名探测超时(秒) */
    private static final int DOMAIN_PROBE_TIMEOUT_SECONDS = 8;
    /** 探测线程序号(线程名 wanou-probe-N) */
    private static final AtomicInteger PROBE_SEQ = new AtomicInteger();
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\\s\\u3400-\\u4dbf\\u4e00-\\u9fff\\u3000-\\u303f\\uff01-\\uff5e<>\"']+");
    private static final Pattern PASSWORD_IN_TEXT = Pattern.compile("(?:提取码|密码|访问码|pwd)[=:\\s：]*([a-zA-Z0-9]{4,6})");
    private static final String URL_TRAILING = "#，。；：,.;:！？、）)】」』》>\"'";
    /** 标题归一化:画质/站名噪声词 + 分隔符(py 玩偶聚合 _normalize_title) */
    private static final Pattern TITLE_NOISE = Pattern.compile("(?i)4k|hdr|2160p|1080p|720p|玩偶|木偶");
    private static final Pattern TITLE_SEPARATOR = Pattern.compile("[\\s\\-_.·,，。!！?？:：()（）\\[\\]]+");
    /** 关键词侧额外剥掉的集数/季/年份标记(订阅关键词常带"第2季/第12集/2025"后缀,卡片标题通常没有) */
    private static final Pattern KEYWORD_MARKER = Pattern.compile(
            "(?i)(第[0-9一二三四五六七八九十]{1,3}季|season\\d{1,2}|s\\d{1,2}e\\d{1,3}|ep?\\d{1,3}|第\\d{1,3}集|更新?至\\d{1,3}|全\\d{1,3}集|\\d{1,3}集|20\\d{2})");
    /** 站点优先级(py site_priority):同名合并去重时优先保留靠前站点的链接 */
    private static final List<String> SITE_PRIORITY = List.of(
            "wanou", "duoduo", "muou", "kuaiying", "shandian", "biaoge", "huajuan", "ouge", "hban");

    record Site(String id, String name, String monitorKey, List<String> seedDomains,
                String searchUrl, int timeoutSeconds, String searchCardCss, String detailPanCss) {
    }

    record Card(String href, String title, String remarks) {
    }

    /** 单域名探测结果:ok=首页可达(200 且非挑战页),latencyMs=完整请求耗时,error=失败原因。 */
    record DomainProbe(String url, boolean ok, long latencyMs, String error) {
    }

    /** 单站探测结果:domains 已按采用优先级排序(可达按延迟升序在前,不可达垫底),首条即当前采用域名。 */
    record SiteProbe(String siteId, String siteName, List<DomainProbe> domains) {
        String bestUrl() {
            return domains.stream().filter(DomainProbe::ok).findFirst().map(DomainProbe::url).orElse(null);
        }
    }

    private static final List<Site> SITES = List.of(
            new Site("muou", "木偶", "木偶",
                    List.of("https://www.muou.site", "https://www.muou.asia", "https://666.666291.xyz", "https://123.666291.xyz"),
                    null, 10, null, null),
            new Site("duoduo", "多多", "多多",
                    List.of("https://yydsys.de5.net", "https://tv.214521.xyz", "https://tv.yydsys.cc", "https://tv.yydsys.top"),
                    null, 10, null, null),
            new Site("wanou", "玩偶", "玩偶",
                    List.of("https://woggpan.xxooo.cf", "https://wogg.xxooo.cf", "https://woggpan.888484.xyz", "https://www.wogg.net"),
                    "/vodsearch/-------------.html?wd={keyword}&page={page}", 10, null, null),
            new Site("kuaiying", "快映", null,
                    List.of("http://xsayang.fun:12512"),
                    null, 10, null, null),
            new Site("shandian", "闪电", "闪电",
                    List.of("http://sd.sduc.site", "http://shandian.blog"),
                    null, 10, null, null),
            new Site("biaoge", "表哥", null,
                    List.of("http://xn--4yqy17f.xn--yi7aa.vip:3155"),
                    null, 10, null, null),
            new Site("huajuan", "花卷", null,
                    List.of("https://www.hjzhencai.top"),
                    null, 10, ".module-card-item-poster", ".down-card-url"),
            // 欧歌/虎斑:2026-09-26 随 py 复活回归;详情形状是 module-row-info 容器文本自身(标准站是其下 p)
            new Site("ouge", "欧歌", "欧哥",
                    List.of("https://woog.nxog.eu.org", "https://woog.430520.xyz", "https://woog.nxog.fun"),
                    null, 10, null, ".module-row-info"),
            // 虎斑只挂裸 IP 且入口轮流换(38.76.197.172/xhban.xyz 均 302 到 43.248.128.118),探测按可达性自动跟随现行入口
            new Site("hban", "虎斑", "虎斑",
                    List.of("http://43.248.128.118:16969", "http://38.76.197.172:16969", "http://xhban.xyz:20720"),
                    null, 10, null, ".module-row-info"));

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
    /** 最近一轮探测快照(站点 id → 结果),供状态面板与手动触发读取;启动首轮探测前为空表 */
    private volatile List<SiteProbe> lastProbes = List.of();
    private final AtomicBoolean probing = new AtomicBoolean(false);
    /** 站点池线程序号(线程名 wanou-search-N):多站并发时日志可分辨线程 */
    private static final AtomicInteger SEARCH_SEQ = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(SITES.size(), r -> {
        Thread thread = new Thread(r, "wanou-search-" + SEARCH_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    /** 探测池:全站全域名一次性并行(约 30 个首页 GET,各站瞬时并发 ≤ 域名数,无压力) */
    private final ExecutorService probeExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "wanou-probe-" + PROBE_SEQ.incrementAndGet());
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

        long deadline = System.currentTimeMillis() + Math.max(5, appProperties.getSubscription().getWanouTimeoutSeconds()) * 1000L;
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
            List<Card> cards = parseSearchCards(site, requestWithFailover(site, buildSearchPath(site, keyword)));
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

    /** 搜索结果卡片解析(py _parse_search_cards 的 Jsoup 等价实现,卡片选择器按站可覆写:
     *  花卷是海报卡片 module-card-item-poster,无 video-serial,链接在卡片节点自身)。 */
    List<Card> parseSearchCards(Site site, String html) {
        Document doc = Jsoup.parse(html);
        List<Card> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String css = site.searchCardCss() == null ? DEFAULT_SEARCH_CARD_CSS : site.searchCardCss();
        for (Element card : doc.select(css)) {
            Element serial = card.selectFirst(".video-serial");
            String href = serial == null ? "" : StringUtils.trimToEmpty(serial.attr("href"));
            if (href.isEmpty()) {
                Element a = card.selectFirst("a[href]");
                href = a == null ? "" : a.attr("href").trim();
            }
            if (href.isEmpty()) {
                href = card.attr("href").trim();
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
            if (remarks.isEmpty()) {
                Element note = card.selectFirst(".module-item-note");
                remarks = note == null ? "" : note.text().trim();
            }
            if (href.isEmpty() || title.isEmpty() || !seen.add(href)) {
                continue;
            }
            cards.add(new Card(href, title, remarks));
        }
        return cards;
    }

    /**
     * 详情页分享链接提取:标准站取 module-row-info 下 p 文本的第一个 URL,花卷走
     * down-card-url 文本;行内"提取码"折进 ?password= 参数。
     */
    List<String> parseDetailPanUrls(Site site, String html) {
        Document doc = Jsoup.parse(html);
        Set<String> urls = new LinkedHashSet<>();
        String css = site.detailPanCss() == null ? DEFAULT_DETAIL_PAN_CSS : site.detailPanCss();
        for (Element node : doc.select(css)) {
            addShareUrl(urls, node.text());
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
                DomainState state = domainStates.get(site.id());
                synchronized (state) {
                    state.ordered = List.copyOf(merged);
                }
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

    /**
     * 定时域名探测(替代对外部监控有效性判断的依赖):先吸收监控下发的新域名,再并行探测
     * 全部候选域名的首页可达性与延迟,把每站域名按「可达且延迟最低在前、不可达垫底」重排,
     * 搜索从此直接从最优域名起步。探测在线程池里执行,不占调度线程。
     */
    @Scheduled(initialDelay = 90_000L, fixedDelay = DOMAIN_PROBE_INTERVAL_MS)
    public void probeDomainsTask() {
        if (!appProperties.getSubscription().isWanouEnabled()
                || !appProperties.getSubscription().isWanouProbeEnabled()) {
            return;
        }
        if (!probing.compareAndSet(false, true)) {
            return;
        }
        probeExecutor.submit(() -> {
            try {
                probeAllDomains();
            } catch (Exception e) {
                log.warn("wanou domain probe failed: {}", e.getMessage());
            } finally {
                probing.set(false);
            }
        });
    }

    /** 立即探测全站域名并返回结果(状态面板手动触发入口);幂等,定时任务另有 probing 守卫防叠跑。 */
    public List<SiteProbe> probeAllDomains() {
        refreshDomainsIfNeeded();
        List<CompletableFuture<SiteProbe>> futures = new ArrayList<>();
        for (Site site : SITES) {
            DomainState state = domainStates.get(site.id());
            List<String> domains;
            synchronized (state) {
                domains = state.ordered;
            }
            futures.add(CompletableFuture.supplyAsync(() -> probeSite(site, domains), probeExecutor));
        }
        List<SiteProbe> results = futures.stream().map(CompletableFuture::join).toList();
        lastProbes = results;
        log.info("wanou domain probe done: {} alive / {} dead, best={}",
                results.stream().filter(s -> s.bestUrl() != null).count(),
                results.stream().filter(s -> s.bestUrl() == null).count(),
                results.stream().map(s -> s.siteId() + "=" + StringUtils.defaultString(s.bestUrl(), "-"))
                        .collect(Collectors.joining(" ")));
        return results;
    }

    /** 最近一轮探测快照;未探测过返回空表(面板引导手动触发)。 */
    public List<SiteProbe> domainStatuses() {
        return lastProbes;
    }

    /** 状态面板 DTO 形态(内部探测记录 → 公开契约,排序即采用优先级)。 */
    public List<WanouSiteStatus> domainStatusDtos() {
        return lastProbes.stream()
                .map(probe -> new WanouSiteStatus(probe.siteId(), probe.siteName(),
                        probe.bestUrl() != null, probe.bestUrl(),
                        probe.domains().stream()
                                .map(d -> new WanouDomainStatus(d.url(), d.ok(), d.latencyMs(), d.error()))
                                .toList()))
                .toList();
    }

    /** 探测单站:并行测全部域名,按结果重排写回(可达按延迟升序在前,不可达垫底保持原相对顺序)。 */
    private SiteProbe probeSite(Site site, List<String> domains) {
        Map<String, DomainProbe> probes = domains.stream()
                .map(url -> CompletableFuture.supplyAsync(() -> probeDomain(url), probeExecutor))
                .map(CompletableFuture::join)
                .collect(Collectors.toMap(DomainProbe::url, p -> p, (a, b) -> a));
        List<DomainProbe> ordered = new ArrayList<>();
        domains.stream().map(probes::get).filter(DomainProbe::ok)
                .sorted(Comparator.comparingLong(DomainProbe::latencyMs))
                .forEach(ordered::add);
        domains.stream().map(probes::get).filter(p -> !p.ok()).forEach(ordered::add);
        DomainState state = domainStates.get(site.id());
        synchronized (state) {
            state.ordered = ordered.stream().map(DomainProbe::url).toList();
        }
        return new SiteProbe(site.id(), site.name(), List.copyOf(ordered));
    }

    /** 探测单域名:首页 GET,完整耗时即延迟(与搜索场景同口径);由 {@link #probeFetch} 打桩可测。 */
    private DomainProbe probeDomain(String url) {
        long start = System.nanoTime();
        String error = probeFetch(url);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        return new DomainProbe(url, error == null, latencyMs, error);
    }

    /** 探测请求:返回 null=可达(200 且非挑战页),否则返回失败原因(HTTP 码/挑战页/异常摘要)。 */
    protected String probeFetch(String url) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", appProperties.getUserAgent())
                .build();
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(DOMAIN_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DOMAIN_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(DOMAIN_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() != 200) {
                return "HTTP " + response.code();
            }
            String body = response.body() == null ? "" : response.body().string();
            return isChallenge(null, body) ? "challenge" : null;
        } catch (IOException e) {
            return StringUtils.abbreviate(StringUtils.defaultString(e.getMessage(), e.getClass().getSimpleName()), 80);
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
        probeExecutor.shutdownNow();
    }
}
