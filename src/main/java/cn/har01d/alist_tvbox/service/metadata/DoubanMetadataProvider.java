package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cn.har01d.alist_tvbox.util.Utils;

/**
 * 豆瓣:在线搜索 movie.douban.com/j/subject_suggest(atv-player 已验证的稳定 JSON 接口),
 * 本地 movie 表(豆瓣同步库)兜底与合并;详情尝试 rexxar 条目接口补集数(容错,失败仅本地字段)。
 * 配置 Setting douban_cookie 后追加详情页解析:取"又名"(标题归属匹配用)与 IMDb id,
 * 并经 IMDb 桥接 TMDB(单集播出日程/状态/别名,豆瓣本身无这些字段);详情页抓取全局限速防封。
 */
@Slf4j
@Component
public class DoubanMetadataProvider implements MetadataProvider {
    public static final String NAME = "douban";
    public static final String COOKIE_SETTING = "douban_cookie";
    private static final String SUGGEST_URL = "https://movie.douban.com/j/subject_suggest?q=";
    private static final String SUBJECT_URL = "https://movie.douban.com/subject/";
    private static final Pattern ALIAS_PATTERN = Pattern.compile("又名:.*?</span>\\s*([^<]+)");
    private static final Pattern IMDB_PATTERN = Pattern.compile("IMDb:.*?(tt\\d{7,10})");
    /** 详情页最小请求间隔:cookie 抓取对账号安全敏感,宁可慢不可被封(巡检每日一次,可接受) */
    private static final long PAGE_INTERVAL_MS = 8000;

    private final MovieRepository movieRepository;
    private final RestTemplate restTemplate;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private final Cache<String, MetadataDetails> detailsCache = Caffeine.newBuilder()
            .maximumSize(200).expireAfterWrite(Duration.ofHours(6)).build();
    /** 详情页解析结果(又名/IMDb 基本不变,缓存 24h,避免 rexxar 失败退避重试期间反复抓页) */
    private final Cache<String, DoubanSubjectPage> pageCache = Caffeine.newBuilder()
            .maximumSize(200).expireAfterWrite(Duration.ofHours(24)).build();
    private final Map<String, Instant> failures = new ConcurrentHashMap<>();
    private final PageRateLimiter pageLimiter = new PageRateLimiter(PAGE_INTERVAL_MS);

    private final MetadataHealth health;
    private final SettingRepository settingRepository;
    private final TmdbMetadataProvider tmdbMetadataProvider;

    public DoubanMetadataProvider(MovieRepository movieRepository, MetadataHttp metadataHttp, MetadataHealth health,
                                  SettingRepository settingRepository, TmdbMetadataProvider tmdbMetadataProvider) {
        this.movieRepository = movieRepository;
        this.restTemplate = metadataHttp.create();
        this.health = health;
        this.settingRepository = settingRepository;
        this.tmdbMetadataProvider = tmdbMetadataProvider;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public List<MetadataSearchItem> search(String keyword) {
        List<MetadataSearchItem> result = new ArrayList<>();
        if (StringUtils.isBlank(keyword)) {
            return result;
        }
        // 在线 suggest(优先,覆盖本地库未同步的新剧);失败降级本地表
        Instant lastFailure = failures.get("suggest");
        if (health.isOpen(NAME)) {
            return result.isEmpty() ? localSearch(keyword) : result;
        }
        if (lastFailure == null || Duration.between(lastFailure, Instant.now()).toMinutes() >= 30) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.REFERER, "https://movie.douban.com/");
                headers.set(HttpHeaders.USER_AGENT, Utils.getUserAgent());
                // 用 String 收包再手动解析:与 RestTemplate 消息转换器组合解耦(Jackson2/Jackson3 均可)
                ResponseEntity<String> response = restTemplate.exchange(
                        URI.create(SUGGEST_URL + java.net.URLEncoder.encode(keyword.trim(), java.nio.charset.StandardCharsets.UTF_8)),
                        HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
                JsonNode array = MAPPER.readTree(response.getBody());
                if (array != null && array.isArray()) {
                    for (JsonNode item : array) {
                        String id = item.path("id").asText("");
                        String title = item.path("title").asText("");
                        if (id.isEmpty() || title.isEmpty()) {
                            continue;
                        }
                        MetadataSearchItem entry = new MetadataSearchItem();
                        entry.setProvider(NAME);
                        entry.setId(id);
                        entry.setName(title);
                        entry.setYear(item.path("year").asText(""));
                        entry.setCover(item.path("img").asText(""));
                        entry.setDescription("episode".equalsIgnoreCase(item.path("type").asText()) ? "剧集" : "电影");
                        result.add(entry);
                    }
                }
                failures.remove("suggest");
                health.record(NAME, true);
            } catch (Exception e) {
                health.record(NAME, false);
                log.debug("douban suggest failed: {}", e.getMessage());
                failures.put("suggest", Instant.now());
            }
        }
        // 本地表补充(在线无结果或被ban时仍是完整兜底)
        if (result.isEmpty()) {
            result.addAll(localSearch(keyword));
        }
        return result;
    }

    private List<MetadataSearchItem> localSearch(String keyword) {
        List<MetadataSearchItem> result = new ArrayList<>();
        var page = movieRepository.findByNameContains(keyword.trim(),
                org.springframework.data.domain.PageRequest.of(0, 20));
        for (var movie : page.getContent()) {
            MetadataSearchItem entry = new MetadataSearchItem();
            entry.setProvider(NAME);
            entry.setId(String.valueOf(movie.getId()));
            entry.setName(movie.getName());
            entry.setYear(movie.getYear() == null ? "" : String.valueOf(movie.getYear()));
            entry.setCover(movie.getCover());
            entry.setScore(movie.getDbScore());
            entry.setDescription("本地库");
            result.add(entry);
        }
        return result;
    }

    @Override
    public MetadataDetails details(String id, Integer season) {
        int seasonNumber = season == null || season < 1 ? 1 : season;
        return detailsCache.get(id + ":" + seasonNumber, key -> fetchDetails(id, seasonNumber));
    }

    private MetadataDetails fetchDetails(String id, int season) {
        MetadataDetails details = new MetadataDetails();
        details.setProvider(NAME);
        details.setId(id);
        // rexxar tv 条目接口补集数(在线搜索结果即豆瓣 subject id,直接可用);失败降级本地 movie 表
        Instant lastFailure = failures.get(id);
        if (lastFailure == null || Duration.between(lastFailure, Instant.now()).toMinutes() >= 30) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.REFERER, "https://m.douban.com/");
                headers.set(HttpHeaders.USER_AGENT, Utils.getUserAgent());
                ResponseEntity<String> response = restTemplate.exchange(
                        URI.create("https://m.douban.com/rexxar/api/v2/tv/" + id), HttpMethod.GET,
                        new HttpEntity<>(null, headers), String.class);
                JsonNode body = response.getBody() == null ? null : MAPPER.readTree(response.getBody());
                if (body != null && body.isObject()) {
                    if (body.hasNonNull("episodes_count")) {
                        details.setTotalEpisodes(body.get("episodes_count").asInt());
                        // 豆瓣无"已播"字段:未完结时不动 airedEpisodes,交给官方平台/TMDB 推算
                    }
                    if (StringUtils.isBlank(details.getName()) && body.hasNonNull("title")) {
                        details.setName(body.get("title").asText());
                    }
                    if (StringUtils.isBlank(details.getCover()) && body.hasNonNull("pic")) {
                        details.setCover(body.path("pic").path("large").asText(body.path("pic").path("normal").asText("")));
                    }
                    failures.remove(id);
                }
                health.record(NAME, true);
            } catch (Exception e) {
                health.record(NAME, false);
                log.debug("douban details {} failed: {}", id, e.getMessage());
                failures.put(id, Instant.now());
            }
        }
        // 本地表兜底(数字主键时可用,补名称/封面)
        try {
            Integer movieId = Integer.parseInt(id);
            var movie = movieRepository.findById(movieId).orElse(null);
            if (movie != null) {
                if (StringUtils.isBlank(details.getName())) {
                    details.setName(movie.getName());
                }
                if (StringUtils.isBlank(details.getCover())) {
                    details.setCover(movie.getCover());
                }
                details.setYear(movie.getYear() == null ? "" : String.valueOf(movie.getYear()));
            }
        } catch (NumberFormatException ignored) {
            // 在线 subject id 非数字,本地表兜底不适用
        }
        enrichFromSubjectPage(details, id, season);
        return details;
    }

    /** 详情页增强:又名 + IMDb→TMDB 桥接(分集播出日程/状态/别名)。未配置 cookie 或失败时静默跳过。 */
    private void enrichFromSubjectPage(MetadataDetails details, String id, int season) {
        DoubanSubjectPage page = fetchSubjectPage(id);
        if (page == null) {
            return;
        }
        if (!page.aliases().isEmpty()) {
            details.setAliases(page.aliases());
        }
        if (StringUtils.isNotBlank(page.imdbId()) && tmdbMetadataProvider != null) {
            MetadataDetails tmdb = tmdbMetadataProvider.detailsByImdb(page.imdbId(), season);
            mergeTmdbDetails(details, tmdb);
        }
    }

    private DoubanSubjectPage fetchSubjectPage(String id) {
        String cookie = doubanCookie();
        if (cookie == null) {
            return null; // 未配置 cookie 不抓详情页:匿名抓取极易触发风控,连累同域 suggest
        }
        DoubanSubjectPage cached = pageCache.getIfPresent(id);
        if (cached != null) {
            return cached;
        }
        Instant lastFailure = failures.get("page:" + id);
        if (lastFailure != null && Duration.between(lastFailure, Instant.now()).toMinutes() < 30) {
            return null;
        }
        try {
            pageLimiter.acquire();
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.REFERER, "https://movie.douban.com/");
            headers.set(HttpHeaders.USER_AGENT, Utils.getUserAgent());
            headers.set(HttpHeaders.COOKIE, cookie.trim());
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(SUBJECT_URL + id + "/"), HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
            String html = response.getBody();
            if (html == null || html.contains("有异常请求从你的 IP 发出") || html.contains("sec.douban.com")) {
                throw new IllegalStateException("subject page blocked or empty");
            }
            DoubanSubjectPage page = new DoubanSubjectPage(parseAliases(html), parseImdbId(html));
            pageCache.put(id, page);
            failures.remove("page:" + id);
            return page;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("douban subject page {} failed: {}", id, e.getMessage());
            failures.put("page:" + id, Instant.now());
            return null;
        }
    }

    /** 详情页 #info 块"又名:"字段:"又名:</span> 名字1&nbsp;/&nbsp;名字2<br>" → [名字1, 名字2]。 */
    static List<String> parseAliases(String html) {
        List<String> aliases = new ArrayList<>();
        String infoHtml = infoHtml(html);
        if (infoHtml == null) {
            return aliases;
        }
        Matcher matcher = ALIAS_PATTERN.matcher(infoHtml);
        if (matcher.find()) {
            String raw = matcher.group(1).replace("&nbsp;", " ").replace("\u00A0", " ");
            for (String part : raw.split("[/]")) {
                String alias = part.trim();
                if (alias.length() >= 2 && !alias.contains(":") && !aliases.contains(alias)) {
                    aliases.add(alias);
                }
            }
        }
        return aliases;
    }

    /** 详情页 #info 块"IMDb:"字段的 tt 编号(无链接/带链接两种形态)。 */
    static String parseImdbId(String html) {
        String infoHtml = infoHtml(html);
        if (infoHtml == null) {
            return null;
        }
        Matcher matcher = IMDB_PATTERN.matcher(infoHtml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String infoHtml(String html) {
        if (StringUtils.isBlank(html)) {
            return null;
        }
        var info = org.jsoup.Jsoup.parse(html).selectFirst("#info");
        return info == null ? null : info.html();
    }

    /**
     * 豆瓣详情 ← TMDB 桥接合并:豆瓣保条目身份(名称/封面/总集数,rexxar 的集数更贴国产剧),
     * TMDB 补豆瓣没有的字段:状态/已播集数/分集播出日程/别名(豆瓣又名在前,匹配优先)。
     */
    static void mergeTmdbDetails(MetadataDetails douban, MetadataDetails tmdb) {
        if (douban == null || tmdb == null) {
            return;
        }
        if (!MetadataDetails.STATUS_UNKNOWN.equals(tmdb.getStatus())) {
            douban.setStatus(tmdb.getStatus());
        }
        if (douban.getTotalEpisodes() == null && tmdb.getTotalEpisodes() != null) {
            douban.setTotalEpisodes(tmdb.getTotalEpisodes());
        }
        if (tmdb.getAiredEpisodes() != null) {
            douban.setAiredEpisodes(tmdb.getAiredEpisodes());
        }
        if (tmdb.getNextAirTime() != null) {
            douban.setNextAirTime(tmdb.getNextAirTime());
        }
        if (tmdb.getUpcoming() != null && !tmdb.getUpcoming().isEmpty()) {
            douban.setUpcoming(tmdb.getUpcoming());
        }
        if (tmdb.getTotalSeasons() != null && tmdb.getTotalSeasons() > 0) {
            douban.setTotalSeasons(tmdb.getTotalSeasons());
        }
        if (tmdb.getRuntimeMinutes() != null) {
            douban.setRuntimeMinutes(tmdb.getRuntimeMinutes());
        }
        if (StringUtils.isBlank(douban.getYear()) && StringUtils.isNotBlank(tmdb.getYear())) {
            douban.setYear(tmdb.getYear());
        }
        if (tmdb.getAliases() != null && !tmdb.getAliases().isEmpty()) {
            List<String> merged = new ArrayList<>(douban.getAliases() == null ? List.of() : douban.getAliases());
            for (String alias : tmdb.getAliases()) {
                if (StringUtils.isNotBlank(alias) && !merged.contains(alias)) {
                    merged.add(alias);
                }
            }
            douban.setAliases(merged);
        }
    }

    /** Setting douban_cookie:详情页抓取开关+凭据,空=关闭。 */
    String doubanCookie() {
        if (settingRepository == null) {
            return null;
        }
        return settingRepository.findById(COOKIE_SETTING).map(Setting::getValue)
                .filter(StringUtils::isNotBlank).orElse(null);
    }

    /** 详情页解析结果:又名列表 + IMDb id(可空)。 */
    record DoubanSubjectPage(List<String> aliases, String imdbId) {
    }

    /** 全局串行节流:所有详情页请求共用,最小间隔防封(cookie 抓取频率过高会连累账号)。 */
    static final class PageRateLimiter {
        private final long intervalMs;
        private long last;

        PageRateLimiter(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        synchronized void acquire() throws InterruptedException {
            long wait = last + intervalMs - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            last = System.currentTimeMillis();
        }
    }
}
