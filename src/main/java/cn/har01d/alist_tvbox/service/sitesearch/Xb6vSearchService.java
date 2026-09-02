package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Cookie;
import okhttp3.CookieJar;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 6V磁力搜索源(atv-spiders/py/6V磁力.py 的 Java 移植,追剧搜索源之一):xb6v.com,
 * 帝国 CMS 站点,免登录。<b>磁力为主、少量网盘资源</b> —— 详情页下载表格按资源组分组
 * (组头 strong 行),磁力行与网盘行(夸克/迅雷/百度等,提取码多内嵌在 {@code ?pwd=})
 * 交错混排,两类产出同一响应零额外请求。
 *
 * <p>搜索:POST {@code /e/search/11index.php}(首页表单当前 action;py 版的
 * {@code 1index.php} 已随站点改版 404)后 302 跳 {@code /e/search/result/?searchid=N},
 * OkHttp 自动带会话 Cookie 跟随;结果页 {@code #post_container .post_hover} 卡片的
 * {@code a.zoom} 链接即详情入口,标题取 title 属性(搜索命中词带 {@code <font>} 高亮,
 * 须剥标签)。单页结果,不翻页(py 同口径)。
 *
 * <p>产出:网盘链接按 {@link Message#parseType} 识别(只留可挂载数字盘型)入候选池;
 * 磁力 {@code magnet:} 链接(type=magnet,种子名取 URI 的 dn= 解码,回落行文本,所在
 * 资源组的组头并入 content 供集数分组打分)供追剧磁力兜底在 fillPool 的 NON_PAN 收割。
 * <b>本源仅订阅磁力兜底生效时参与搜索</b>(searchAllSources 按 offlineIncluded 门控,
 * 磁力为主、网盘少量,未开兜底不值一路请求);站点地址 Setting {@code xb6v_host} 可覆盖。
 */
@Slf4j
@Service
public class Xb6vSearchService {
    public static final String HOST_SETTING = "xb6v_host";
    private static final String DEFAULT_HOST = "https://www.xb6v.com";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/136.0.0.0 Safari/537.36";
    private static final int REQUEST_TIMEOUT_SECONDS = 10;

    private final SettingRepository settingRepository;
    private final AppProperties appProperties;
    private final OkHttpClient httpClient = new OkHttpClient();

    public Xb6vSearchService(SettingRepository settingRepository, AppProperties appProperties) {
        this.settingRepository = settingRepository;
        this.appProperties = appProperties;
    }

    record Card(String href, String title) {
    }

    public List<Message> search(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return List.of();
        }
        String host = SiteSearchSupport.normalizeHost(
                SiteSearchSupport.setting(settingRepository, HOST_SETTING), DEFAULT_HOST);
        long deadline = System.currentTimeMillis()
                + appProperties.getSubscription().getXb6vTimeoutSeconds() * 1000L;
        Map<String, String> jar = new LinkedHashMap<>();
        try {
            List<Card> cards = parseCards(searchHtml(host, keyword.trim(), jar));
            List<Message> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int details = 0;
            int maxDetails = Math.max(1, appProperties.getSubscription().getXb6vMaxDetailPages());
            for (Card card : cards) {
                if (details >= maxDetails || System.currentTimeMillis() > deadline) {
                    break;
                }
                details++;
                parseDetail(get(host, absoluteUrl(host, card.href()), jar), card, result, seen);
            }
            log.info("Xb6v search {} get {} results", keyword, result.size());
            return result;
        } catch (Exception e) {
            log.warn("xb6v search [{}] failed: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    // ---------- 请求(每次搜索独立会话 Cookie,POST→302→GET 由 OkHttp 自动完成) ----------

    private String searchHtml(String host, String keyword, Map<String, String> jar) throws IOException {
        Request request = new Request.Builder()
                .url(host + "/e/search/11index.php")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", host + "/")
                .header("Origin", host)
                .post(new FormBody.Builder()
                        .add("show", "title")
                        .add("tempid", "1")
                        .add("tbname", "article")
                        .add("mid", "1")
                        .add("dopost", "search")
                        .add("submit", "")
                        .add("keyboard", keyword)
                        .build())
                .build();
        Resp resp = http(request, jar);
        return resp.code() == 200 ? StringUtils.defaultString(resp.body()) : "";
    }

    private String get(String host, String url, Map<String, String> jar) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", host + "/")
                .get()
                .build();
        Resp resp = http(request, jar);
        return resp.code() == 200 ? StringUtils.defaultString(resp.body()) : "";
    }

    protected Resp http(Request request, Map<String, String> jar) throws IOException {
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .cookieJar(new CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                        for (Cookie cookie : cookies) {
                            jar.put(cookie.name(), cookie.value());
                        }
                    }

                    @Override
                    public List<Cookie> loadForRequest(HttpUrl url) {
                        List<Cookie> result = new ArrayList<>();
                        for (Map.Entry<String, String> entry : jar.entrySet()) {
                            try {
                                result.add(new Cookie.Builder()
                                        .name(entry.getKey()).value(entry.getValue())
                                        .domain(url.host()).build());
                            } catch (IllegalArgumentException ignored) {
                                // 值含非法字符的 Cookie 跳过,不炸请求
                            }
                        }
                        return result;
                    }
                })
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            return new Resp(response.code(), response.headers("Set-Cookie"), body);
        }
    }

    // ---------- 解析 ----------

    /** 搜索结果卡片(py _extract_cards 的 Jsoup 等价实现):.post_hover 块内 a.zoom 的 href+title。 */
    List<Card> parseCards(String html) {
        List<Card> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element node : Jsoup.parse(StringUtils.defaultString(html))
                .select("#post_container .post_hover")) {
            Element link = node.selectFirst("a.zoom");
            if (link == null) {
                continue;
            }
            String href = link.attr("href").trim();
            String title = cleanText(link.attr("title"));
            if (title.isEmpty()) {
                Element h2 = node.selectFirst("h2 a");
                if (h2 != null) {
                    title = cleanText(h2.text());
                }
            }
            if (href.isEmpty() || title.isEmpty() || !seen.add(href)) {
                continue;
            }
            cards.add(new Card(href, title));
        }
        return cards;
    }

    /**
     * 详情页下载表格产出:磁力行(a[href^=magnet:],种子名 dn= 解码优先)+ 网盘行
     * (parseType 数字盘型);两类的 content 都并入所在资源组的组头(前向最近 strong 行,
     * 如「幕兰之战 年番4」),集数分组信息供进度/季匹配打分消费。
     */
    void parseDetail(String html, Card card, List<Message> out, Set<String> seen) {
        Document doc = Jsoup.parse(StringUtils.defaultString(html));
        Element content = doc.selectFirst("#post_content");
        if (content == null) {
            content = doc.body();
        }
        if (content == null) {
            return;
        }
        int magnets = 0;
        int maxMagnets = Math.max(1, appProperties.getSubscription().getXb6vMaxMagnets());
        for (Element a : content.select("a[href^=magnet:]")) {
            if (magnets >= maxMagnets) {
                break;
            }
            String href = a.attr("href").trim();
            if (href.isEmpty() || !seen.add(href)) {
                continue;
            }
            String dn = magnetDn(href);
            String label = dn.isEmpty() ? cleanText(a.text()) : dn;
            Message message = new Message();
            message.setType("magnet");
            message.setLink(href);
            message.setName(card.title());
            message.setChannel("6V");
            message.setContent(joinContent(groupOf(a), label));
            out.add(message);
            magnets++;
        }
        for (Element a : content.select("a[href]")) {
            String href = a.attr("href").trim();
            if (!href.startsWith("http")) {
                continue;
            }
            String type = Message.parseType(href);
            if (type == null || !SiteSearchSupport.isNumeric(type) || !seen.add(href)) {
                continue;
            }
            String rowLabel = cleanText(a.parent() != null && "td".equals(a.parent().tagName())
                    ? a.parent().text().replace(href, "") : a.ownText());
            Message message = new Message();
            message.setType(type);
            message.setLink(href);
            message.setName(card.title());
            message.setChannel("6V");
            message.setContent(joinContent(groupOf(a), rowLabel));
            out.add(message);
        }
    }

    /** 行所在资源组的组头:同一 table 内前向最近的 strong 行文本(无则空)。 */
    private static String groupOf(Element anchor) {
        Element row = anchor.closest("tr");
        if (row == null) {
            return "";
        }
        for (Element prev = row.previousElementSibling(); prev != null; prev = prev.previousElementSibling()) {
            Element strong = prev.selectFirst("strong");
            if (strong != null) {
                return cleanText(prev.text());
            }
        }
        return "";
    }

    private static String joinContent(String group, String label) {
        return (group + " " + StringUtils.defaultString(label)).trim();
    }

    /** 磁力 URI 的 dn= 参数解码(截取 ? 后按 & 分段;非法编码回落原值)。 */
    static String magnetDn(String link) {
        String query = StringUtils.substringAfter(StringUtils.trimToEmpty(link), "?");
        if (query.isEmpty()) {
            return "";
        }
        for (String param : query.split("&")) {
            if (param.startsWith("dn=")) {
                String raw = param.substring(3);
                try {
                    String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
                    return cleanText(decoded);
                } catch (IllegalArgumentException e) {
                    return cleanText(raw);
                }
            }
        }
        return "";
    }

    /** 剥 HTML 标签/解实体/压空白(title 属性里的搜索命中词 <font> 高亮等)。 */
    static String cleanText(String value) {
        String text = Jsoup.parseBodyFragment(StringUtils.defaultString(value)).text();
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String absoluteUrl(String host, String href) {
        String raw = StringUtils.trimToEmpty(href);
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return raw;
        }
        return host + "/" + StringUtils.stripStart(raw, "/");
    }
}
