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
 * IMDb 桥接未命中(未配 cookie/页面被风控/TMDB 无该 IMDb)时,退回<b>名称桥接</b>:
 * 豆瓣名剔季缀搜 TMDB,精确同名 + 年份门禁(多季长篇放行)后按有效季合并 —— 播出时间轴不再只剩 TMDB 源订阅。
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
    /** 季标:第N季/第N部(N 为阿拉伯或中文数字,「瑞克和莫蒂 第九季」「庆余年 第二部」)。 */
    static final Pattern SEASON_MARK = Pattern.compile("第\\s*([0-9一二三四五六七八九十]{1,3})\\s*[季部]");
    /** 尾缀季数:CJK 后跟单数字 2-9 结尾(「杀人者的购物中心2」= S2);1 是剧名本身不算。 */
    static final Pattern TRAILING_SEASON_MARK = Pattern.compile("\\p{IsHan}([2-9])$");
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

    @Override
    public MetadataDetails refreshDetails(String id, Integer season) {
        int seasonNumber = season == null || season < 1 ? 1 : season;
        MetadataDetails details = fetchDetails(id, seasonNumber);
        if (details != null) {
            detailsCache.put(id + ":" + seasonNumber, details);
        }
        return details;
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
                    if (StringUtils.isBlank(details.getYear()) && body.hasNonNull("year")) {
                        details.setYear(String.valueOf(body.get("year").asInt()));
                    }
                    if (StringUtils.isBlank(details.getRating())) {
                        double rating = body.path("rating").path("value").asDouble(0);
                        if (rating > 0) {
                            details.setRating(String.valueOf(rating));
                        }
                    }
                    // rexxar aka:名称桥接的匹配素材(也是标题归属别名,详情页又名缺失时补上)
                    if (body.hasNonNull("aka") && body.get("aka").isArray()) {
                        List<String> akas = new ArrayList<>();
                        for (JsonNode aka : body.get("aka")) {
                            String alias = aka.asText("");
                            if (StringUtils.isNotBlank(alias) && alias.length() <= 100) {
                                akas.add(alias);
                            }
                        }
                        if (!akas.isEmpty()) {
                            details.setAliases(akas);
                        }
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
        // 本地表兜底(数字主键时可用,补名称/封面/类型/地区/演职/评分 —— 豆瓣同步库字段全)
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
                if (StringUtils.isBlank(details.getRating()) && StringUtils.isNotBlank(movie.getDbScore())) {
                    details.setRating(movie.getDbScore());
                }
                if (details.getGenres() == null) {
                    details.setGenres(splitNames(movie.getGenre(), 8));
                }
                if (details.getCountries() == null) {
                    details.setCountries(splitNames(movie.getCountry(), 4));
                }
                if (details.getLanguages() == null) {
                    details.setLanguages(splitNames(movie.getLanguage(), 4));
                }
                if (details.getDirectors() == null) {
                    details.setDirectors(splitNames(movie.getDirectors(), 5));
                }
                if (details.getWriters() == null) {
                    details.setWriters(splitNames(movie.getEditors(), 5));
                }
                if (details.getCast() == null) {
                    List<String> actors = splitNames(movie.getActors(), 15);
                    if (!actors.isEmpty()) {
                        details.setCast(actors.stream()
                                .map(name -> new cn.har01d.alist_tvbox.dto.CastMember(name, null, null))
                                .toList());
                    }
                }
            }
        } catch (NumberFormatException ignored) {
            // 在线 subject id 非数字,本地表兜底不适用
        }
        enrichFromSubjectPage(details, id, season);
        bridgeTmdbByName(details, season);
        return details;
    }

    /** 详情页增强:又名 + IMDb→TMDB 桥接(分集播出日程/状态/别名)。未配置 cookie 或失败时静默跳过。 */
    private void enrichFromSubjectPage(MetadataDetails details, String id, int season) {
        DoubanSubjectPage page = fetchSubjectPage(id);
        if (page == null) {
            return;
        }
        if (!page.aliases().isEmpty()) {
            // 详情页又名优先(rexxar aka 排后):归属匹配以豆瓣页面口径为准
            List<String> merged = new ArrayList<>(page.aliases());
            if (details.getAliases() != null) {
                for (String alias : details.getAliases()) {
                    if (StringUtils.isNotBlank(alias) && !merged.contains(alias)) {
                        merged.add(alias);
                    }
                }
            }
            details.setAliases(merged);
        }
        if (StringUtils.isNotBlank(page.imdbId()) && tmdbMetadataProvider != null) {
            MetadataDetails tmdb = tmdbMetadataProvider.detailsByImdb(page.imdbId(), season);
            mergeTmdbDetails(details, tmdb);
        }
    }

    /**
     * 名称桥接(IMDb 桥接的兜底):IMDb 取不到或未命中时,用豆瓣名(剔季缀)搜 TMDB,
     * <b>精确同名</b>(剧名/别名/剔季缀基名,归一化后整词相等)且<b>年份门禁</b>通过才合并 ——
     * 同名异剧(「悬案」2026 vs 2018 vs 「悬案解码」)靠年份拦,子串嵌套(悬案⊂悬案解码)靠整词拦。
     * 多季长篇(有效季 &gt; 1,「诛仙 第四季」TMDB 首播 2022)年份必然对不上,放行年份门禁。
     * 有效季 = 订阅季(用户显式选的优先)或标题季标(瑞克和莫蒂 第九季 → S9)。
     */
    void bridgeTmdbByName(MetadataDetails details, int season) {
        if (tmdbMetadataProvider == null || details.getNextAirTime() != null
                || (details.getUpcoming() != null && !details.getUpcoming().isEmpty())) {
            return; // IMDb 桥接已带出日程,不重复
        }
        String title = details.getName();
        if (StringUtils.isBlank(title)) {
            return;
        }
        String query = stripSeasonMark(title);
        if (StringUtils.isBlank(query)) {
            return;
        }
        int effectiveSeason = Math.max(season, seasonHintOf(title));
        List<String> names = new ArrayList<>();
        names.add(title);
        names.add(query); // 剔季缀基名:「杀人者的购物中心2」要匹配 TMDB 的「杀人者的购物中心」
        if (details.getAliases() != null) {
            names.addAll(details.getAliases());
        }
        List<MetadataSearchItem> matched = new ArrayList<>();
        for (MetadataSearchItem item : tmdbMetadataProvider.search(query)) {
            String candidate = normalizeTitle(item.getName());
            if (candidate.isEmpty()) {
                continue;
            }
            for (String name : names) {
                if (candidate.equals(normalizeTitle(name))) {
                    matched.add(item);
                    break;
                }
            }
        }
        if (matched.isEmpty()) {
            return;
        }
        Integer year = parseYear(details.getYear());
        if (year != null && effectiveSeason < 2) {
            // 单季条目年份必须对上(候选缺年份视为通过);全不沾 = 同名异剧,放弃
            List<MetadataSearchItem> yearMatched = new ArrayList<>();
            for (MetadataSearchItem item : matched) {
                Integer candidateYear = parseYear(item.getYear());
                if (candidateYear == null || Math.abs(candidateYear - year) <= 1) {
                    yearMatched.add(item);
                }
            }
            if (yearMatched.isEmpty()) {
                log.info("douban name bridge skip {} ({}): same-name candidates {} share no year",
                        title, year, matched.stream().map(i -> i.getName() + "/" + i.getYear()).toList());
                return;
            }
            matched = yearMatched;
        }
        MetadataSearchItem best = matched.get(0); // 搜索相关性序,门禁后取首位
        MetadataDetails tmdb = tmdbMetadataProvider.details(best.getId(), effectiveSeason);
        if (tmdb == null || tmdb.getTotalEpisodes() == null || tmdb.getTotalEpisodes() <= 0) {
            return; // TMDB 无该季(季标误判/未收录),宁缺毋滥
        }
        log.info("douban name bridge: {} → tmdb {} ({}) season {}", title, best.getId(), best.getName(), effectiveSeason);
        mergeTmdbDetails(details, tmdb);
    }

    /** 标题季数提示:第N季/第N部(中文数字可)或尾缀数字(剧名2);无 → 0。 */
    static int seasonHintOf(String title) {
        if (StringUtils.isBlank(title)) {
            return 0;
        }
        Matcher matcher = SEASON_MARK.matcher(title);
        if (matcher.find()) {
            return parseNumber(matcher.group(1));
        }
        matcher = TRAILING_SEASON_MARK.matcher(title);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** 剔除季标(含尾缀数字)后的基名,作搜索词与匹配基名:「诛仙 第四季」→「诛仙」。 */
    static String stripSeasonMark(String title) {
        if (title == null) {
            return null;
        }
        String stripped = SEASON_MARK.matcher(title).replaceAll("");
        Matcher trailing = TRAILING_SEASON_MARK.matcher(stripped);
        if (trailing.find()) {
            stripped = stripped.substring(0, trailing.start(1));
        }
        return stripped.trim();
    }

    static int parseNumber(String raw) {
        if (StringUtils.isBlank(raw)) {
            return 0;
        }
        if (raw.chars().allMatch(c -> c >= '0' && c <= '9')) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return parseChineseNumeral(raw);
    }

    /** 中文数字(≤ 两位,季号足够):十→10、十九→19、二十三→23;解析不了返回 0。 */
    static int parseChineseNumeral(String raw) {
        int p = raw.indexOf('十');
        if (p < 0) {
            return raw.length() == 1 ? Math.max(chineseDigit(raw.charAt(0)), 0) : 0;
        }
        if (raw.length() > 3) {
            return 0;
        }
        int tens = p == 0 ? 1 : chineseDigit(raw.charAt(0));
        int ones = p == raw.length() - 1 ? 0 : chineseDigit(raw.charAt(p + 1));
        return tens > 0 && ones >= 0 ? tens * 10 + ones : 0;
    }

    private static int chineseDigit(char c) {
        return "零一二三四五六七八九".indexOf(c);
    }

    static String normalizeTitle(String s) {
        return s == null ? "" : s.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
    }

    static Integer parseYear(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        Matcher matcher = Pattern.compile("(19[89]\\d|20[0-2]\\d)").matcher(raw);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
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
        if (StringUtils.isBlank(douban.getFirstAirDate()) && StringUtils.isNotBlank(tmdb.getFirstAirDate())) {
            douban.setFirstAirDate(tmdb.getFirstAirDate());
        }
        if (douban.getGenres() == null && tmdb.getGenres() != null) {
            douban.setGenres(tmdb.getGenres());
        }
        if (douban.getCountries() == null && tmdb.getCountries() != null) {
            douban.setCountries(tmdb.getCountries());
        }
        if (douban.getLanguages() == null && tmdb.getLanguages() != null) {
            douban.setLanguages(tmdb.getLanguages());
        }
        if (StringUtils.isBlank(douban.getRating()) && StringUtils.isNotBlank(tmdb.getRating())) {
            douban.setRating(tmdb.getRating()); // 豆瓣评分优先,缺失时 TMDB 评分兜底
        }
        if (douban.getDirectors() == null && tmdb.getDirectors() != null) {
            douban.setDirectors(tmdb.getDirectors());
        }
        if (douban.getWriters() == null && tmdb.getWriters() != null) {
            douban.setWriters(tmdb.getWriters());
        }
        if (douban.getCast() == null && tmdb.getCast() != null) {
            douban.setCast(tmdb.getCast());
        }
        if (StringUtils.isBlank(douban.getBackdrop()) && StringUtils.isNotBlank(tmdb.getBackdrop())) {
            douban.setBackdrop(tmdb.getBackdrop());
        }
        if (douban.getEpisodes() == null && tmdb.getEpisodes() != null) {
            douban.setEpisodes(tmdb.getEpisodes()); // 分集标题/播出时间/剧照/简介(豆瓣无分集数据)
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

    /** 豆瓣库字段以 " / " 分隔(演员/类型/地区等),拆为列表并限长。 */
    private static List<String> splitNames(String raw, int limit) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String name : raw.split("\\s*/\\s*")) {
            if (StringUtils.isNotBlank(name) && names.size() < limit) {
                names.add(name.trim());
            }
        }
        return names.isEmpty() ? null : names;
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
