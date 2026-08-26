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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 蜗牛搜索源(atv-spiders/py/蜗牛.py 的 Java 移植,追剧搜索源之一):蜗牛(wn4k)是
 * MacCMS 衍生的网盘影视站,双线路(wn4k.com/zmi.kdns.fr)并发测速取最快;
 * <b>游客可搜索但网盘链接被打码成 {@code https://******(登录后可见)}</b>,必须登录才能取链。
 *
 * <p><b>凭证必须用户自配</b>(Setting {@code woniu_username}/{@code woniu_password}
 * 或直接 {@code woniu_cookie},站点 {@code woniu_host} 可覆盖双线路测速),未配置时源静默关闭。
 * 登录:POST {@code /user/login.html}(user_name/user_pwd)→ code=="1" → 只保留
 * {@code user_check/user_id/user_name} 最小凭证集(须有 user_check);链接被打码即视为
 * 登录态失效,自动续期一次,失败 10 分钟冷却防凭证错误刷接口。
 *
 * <p>搜索页 {@code /vodsearch/-------------/?wd=}(第 1 页),卡片 {@code a.video-card};
 * 详情 {@code /voddetail/{id}/} 的 {@code .pan-link-item}:链接候选 =
 * {@code a.pan-link-btn@href} + {@code .pan-link-meta} 文本,含 {@code *} 的打码串跳过。
 */
@Slf4j
@Service
public class WoniuSearchService {
    public static final String HOST_SETTING = "woniu_host";
    public static final String USERNAME_SETTING = "woniu_username";
    public static final String PASSWORD_SETTING = "woniu_password";
    public static final String COOKIE_SETTING = "woniu_cookie";

    private static final List<String> DEFAULT_HOSTS = List.of("https://wn4k.com", "https://zmi.kdns.fr");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36";
    private static final int TIMEOUT_SECONDS = 10;
    private static final int PROBE_TIMEOUT_SECONDS = 4;
    /** 每次搜索最多取多少个条目的盘链 */
    private static final int MAX_DETAIL_ITEMS = 3;
    /** 续期失败冷却:防凭证错误时每个详情页都撞登录接口 */
    private static final long RELOGIN_COOLDOWN_MS = 10 * 60_000L;
    /** 登录态最小 Cookie 集合(实测 user_check+user_id+user_name 即可解锁网盘链接) */
    private static final Set<String> AUTH_COOKIE_KEYS = Set.of("user_check", "user_id", "user_name");
    private static final Pattern VOD_ID = Pattern.compile("/voddetail/(\\d+)/?");

    private final SettingRepository settingRepository;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient();
    private volatile String cookie = "";
    private volatile String activeHost = "";
    private volatile boolean seededConfigCookie;
    private final LoginCooldown loginCooldown = new LoginCooldown();
    private volatile boolean warnedNoCredentials;

    public WoniuSearchService(SettingRepository settingRepository, ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.objectMapper = objectMapper;
    }

    private record Config(String host, String username, String password, String cookie) implements SiteCredentials {
        List<String> hosts() {
            return StringUtils.isNotBlank(host) ? List.of(host) : DEFAULT_HOSTS;
        }
    }

    record Card(String vodId, String title, String remarks) {
    }

    public List<Message> search(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return List.of();
        }
        Config config = loadConfig();
        if (!config.hasCredentials()) {
            if (!warnedNoCredentials) {
                warnedNoCredentials = true;
                log.info("蜗牛搜索源未启用:未配置账号(Setting {}+{} 或 {})", USERNAME_SETTING, PASSWORD_SETTING, COOKIE_SETTING);
            }
            return List.of();
        }
        try {
            if (StringUtils.isBlank(cookie) && !login(config)) {
                return List.of();
            }
            List<Card> cards = parseCards(requestHtml(config, searchPath(keyword.trim())));
            List<Message> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int details = 0;
            for (Card card : cards) {
                if (details >= MAX_DETAIL_ITEMS) {
                    break;
                }
                String path = "/voddetail/" + card.vodId() + "/";
                Document doc = Jsoup.parse(requestHtml(config, path));
                details++;
                if (isLocked(doc) && relogin(config)) {
                    doc = Jsoup.parse(requestHtml(config, path));
                }
                for (String[] link : collectPanLinks(doc)) {
                    String type = Message.parseType(link[1]);
                    if (type == null || !SiteSearchSupport.isNumeric(type)) {
                        continue;
                    }
                    Message message = new Message();
                    message.setType(type);
                    message.setLink(link[1]);
                    message.setName(card.title());
                    message.setChannel("蜗牛");
                    message.setContent((card.title() + " " + StringUtils.defaultString(card.remarks()) + " "
                            + StringUtils.defaultString(link[0])).trim());
                    if (seen.add(message.getLink())) {
                        result.add(message);
                    }
                }
            }
            log.info("Woniu search {} get {} results", keyword, result.size());
            return result;
        } catch (Exception e) {
            log.warn("woniu search [{}] failed: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    private static String searchPath(String keyword) {
        // 站点搜索翻页走路径槽位(?pg= 被忽略);搜索源只取第 1 页
        return "/vodsearch/-------------/?wd=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
    }

    // ---------- 登录 ----------

    /** 链接打码即登录态失效:pan-link-meta 含 *。 */
    static boolean isLocked(Document doc) {
        for (Element meta : doc.select(".pan-link-meta")) {
            if (meta.text().contains("*")) {
                return true;
            }
        }
        return false;
    }

    private synchronized boolean relogin(Config config) {
        return login(config);
    }

    /** POST /user/login.html:user_name/user_pwd → code=="1",只保留最小凭证集。 */
    private synchronized boolean login(Config config) {
        if (loginCooldown.blocked() || !config.canLogin()) {
            return false;
        }
        try {
            Request.Builder builder = new Request.Builder()
                    .url(config.hosts().get(0) + "/user/login.html")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", config.hosts().get(0) + "/user/login/")
                    .post(new FormBody.Builder()
                            .add("user_name", config.username())
                            .add("user_pwd", config.password())
                            .build());
            if (StringUtils.isNotBlank(cookie)) {
                builder.header("Cookie", cookie);
            }
            Resp resp = http(builder.build());
            JsonNode payload = objectMapper.readTree(StringUtils.defaultString(resp.body()));
            if (!"1".equals(payload.path("code").asText(""))) {
                return loginFailed(payload.path("msg").asText("登录失败"));
            }
            Map<String, String> auth = new LinkedHashMap<>();
            SiteSearchSupport.parseCookies(resp.setCookies()).forEach((name, value) -> {
                if (AUTH_COOKIE_KEYS.contains(name)) {
                    auth.put(name, value);
                }
            });
            if (!auth.containsKey("user_check")) {
                return loginFailed("登录成功但未取得登录凭证(user_check)");
            }
            cookie = SiteSearchSupport.joinCookies(auth);
            log.info("蜗牛登录成功(username={})", config.username());
            return true;
        } catch (Exception e) {
            return loginFailed(e.getMessage());
        }
    }

    private boolean loginFailed(String reason) {
        return loginCooldown.fail("蜗牛", reason, RELOGIN_COOLDOWN_MS);
    }

    // ---------- 解析 ----------

    /** 搜索卡片:a.video-card,vod_id 取 /voddetail/N/(py _parse_cards)。 */
    List<Card> parseCards(String html) {
        Document doc = Jsoup.parse(html);
        List<Card> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element a : doc.select("a.video-card")) {
            String vodId = extractVodId(a.attr("href"));
            String title = a.attr("title").trim();
            if (title.isEmpty()) {
                Element t = a.selectFirst(".video-title");
                title = t == null ? "" : cleanText(t.text());
            }
            if (title.isEmpty()) {
                Element img = a.selectFirst("img[alt]");
                title = img == null ? "" : img.attr("alt").trim();
            }
            // 卡片角标:评分(video-score)与清晰度(video-episode)合并展示
            String score = textOf(a, ".video-score");
            String episode = textOf(a, ".video-episode");
            String remarks = !score.isEmpty() && !episode.isEmpty() ? score + " · " + episode
                    : StringUtils.defaultString(score.isEmpty() ? episode : score);
            if (vodId.isEmpty() || title.isEmpty() || !seen.add(vodId)) {
                continue;
            }
            cards.add(new Card(vodId, title, remarks));
        }
        return cards;
    }

    static String extractVodId(String href) {
        Matcher matcher = VOD_ID.matcher(StringUtils.defaultString(href));
        return matcher.find() ? matcher.group(1) : "";
    }

    /** pan-link-item 采集 (资源标题, 链接):候选 = pan-link-btn@href + pan-link-meta 文本,打码(*)跳过。 */
    List<String[]> collectPanLinks(Document doc) {
        List<String[]> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        org.jsoup.select.Elements items = doc.select(".pan-link-item");
        if (items.isEmpty()) {
            items = doc.select(".pan-link-list"); // 兜底:整表当一条,标题取首个 pan-link-title
        }
        for (Element item : items) {
            String title = textOf(item, ".pan-link-title");
            List<String> candidates = new ArrayList<>();
            for (Element btn : item.select("a.pan-link-btn")) {
                candidates.add(cleanText(btn.attr("href")));
            }
            for (Element meta : item.select(".pan-link-meta")) {
                candidates.add(cleanText(meta.text()));
            }
            for (String url : candidates) {
                if (url.isEmpty() || url.contains("*") || !seen.add(url)) {
                    continue;
                }
                String type = Message.parseType(url); // 采集即按盘规则过滤(py _pan_info)
                if (type == null || !SiteSearchSupport.isNumeric(type)) {
                    continue;
                }
                links.add(new String[]{title, url});
            }
        }
        return links;
    }

    private static String textOf(Element scope, String selector) {
        Element element = scope.selectFirst(selector);
        return element == null ? "" : cleanText(element.text());
    }

    private static String cleanText(String value) {
        return StringUtils.defaultString(value).replaceAll("\\s+", " ").trim();
    }

    // ---------- 配置与请求 ----------

    private Config loadConfig() {
        Config config = new Config(
                normalizeHost(SiteSearchSupport.setting(settingRepository, HOST_SETTING)),
                SiteSearchSupport.setting(settingRepository, USERNAME_SETTING).trim(),
                SiteSearchSupport.setting(settingRepository, PASSWORD_SETTING).trim(),
                normalizeCookie(SiteSearchSupport.setting(settingRepository, COOKIE_SETTING)));
        if (seededConfigCookie || config.cookie().isEmpty()) {
            return config;
        }
        synchronized (this) {
            if (!seededConfigCookie) {
                cookie = config.cookie();
                seededConfigCookie = true;
            }
        }
        return config;
    }

    /** Cookie 归一化:剥 "Cookie:" 前缀,按 ;/换行 拆分,只留含 = 的段。 */
    static String normalizeCookie(String raw) {
        String text = StringUtils.trimToEmpty(raw);
        if (text.toLowerCase().startsWith("cookie:")) {
            text = text.substring(7);
        }
        StringBuilder sb = new StringBuilder();
        for (String part : text.split("[;\\r\\n]+")) {
            String chunk = part.trim();
            if (chunk.isEmpty() || !chunk.contains("=")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(chunk);
        }
        return sb.toString();
    }

    /** 站点归一化:补 scheme、去尾斜杠(单条自定义地址,py _normalize_host)。 */
    static String normalizeHost(String value) {
        String host = StringUtils.trimToEmpty(value).replaceAll("/+$", "");
        if (host.isEmpty()) {
            return "";
        }
        if (!host.toLowerCase().startsWith("http://") && !host.toLowerCase().startsWith("https://")) {
            host = "https://" + host;
        }
        return host;
    }

    /** 双线路请求:当前粘滞线路优先,失败/空响应换下一条(py 无逐请求 failover,此处加固)。 */
    private String requestHtml(Config config, String path) {
        List<String> hosts = new ArrayList<>(config.hosts());
        if (StringUtils.isNotBlank(activeHost)) {
            hosts.remove(activeHost);
            hosts.add(0, activeHost);
        } else if (config.hosts().size() > 1) {
            probeHosts(config.hosts()); // 全不可达返回 null,下面按默认顺序逐线路尝试
        }
        for (String host : hosts) {
            try {
                Resp resp = http(new Request.Builder()
                        .url(host + path)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", host + "/")
                        .header("Cookie", StringUtils.defaultString(cookie))
                        .build());
                if (resp.code() == 200 && StringUtils.isNotBlank(resp.body())) {
                    activeHost = host;
                    return resp.body();
                }
            } catch (Exception e) {
                log.debug("woniu request {} failed: {}", host, e.getMessage());
            }
        }
        return "";
    }

    /** 并发探测双线路取最快(可覆写供单测打桩),全失败返回 null 保持默认顺序。 */
    String probeHosts(List<String> hosts) {
        if (hosts.size() <= 1) {
            return null;
        }
        // 裸线程并发 put:LinkedHashMap 非线程安全,换并发容器防丢探测结果退回默认线路
        Map<String, Long> timings = new java.util.concurrent.ConcurrentHashMap<>();
        List<Thread> threads = new ArrayList<>();
        for (String host : hosts) {
            Thread thread = new Thread(() -> timings.put(host, probeHost(host)), "woniu-probe");
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            try {
                thread.join((PROBE_TIMEOUT_SECONDS + 2) * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        String best = null;
        long bestTime = Long.MAX_VALUE;
        for (String host : hosts) {
            Long elapsed = timings.get(host);
            if (elapsed != null && elapsed >= 0 && elapsed < bestTime) {
                best = host;
                bestTime = elapsed;
            }
        }
        if (best != null) {
            activeHost = best;
            log.info("蜗牛自动选线: {} ({}ms)", best, bestTime);
        }
        return best;
    }

    protected long probeHost(String host) {
        long start = System.currentTimeMillis();
        try {
            Resp resp = http(new Request.Builder()
                    .url(host + "/")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", host + "/")
                    .build());
            return resp.code() == 200 ? System.currentTimeMillis() - start : -1;
        } catch (Exception e) {
            return -1;
        }
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
