package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.Tmdb;
import cn.har01d.alist_tvbox.util.TextUtils;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4 第三方插件源详情增强(T4Controller.pluginVod 的 detail 后处理)。
 * <p>
 * 数据源双链:本地豆瓣库(Movie/Alias,零网络零配额)+ TMDB(TmdbService 镜像池),
 * 优先级 Setting 键 t4_enhance_priority(douban=豆瓣优先,默认/tmdb=TMDB 优先),
 * 首选 miss 自动回退另一源,命中方全套字段不混拼。
 * <p>
 * 短剧/音乐源跳过(检测在源级而非数据源级):Plugin.detailEnhance 三态
 * (FALSE=永不/TRUE=强制/NULL=跟随全局+自动检测),自动检测=名称关键词 + detail 运行时信号。
 * 主因是短剧极易与同名正剧误匹配,宁可跳过。
 * <p>
 * 安全边界:任何异常/超时(总预算 6s)返回原始 JsonNode;vod_name(历史/搜索连续性)与
 * vod_remarks(承载更新进度)永不覆写;选集段名仅清洗混淆名(乱码/域名水印→第N集),ID 永不动。
 */
@Slf4j
@Service
public class T4DetailEnhancer {

    /** 增强总预算:超时返回原样且不缓存负结果(下次详情再试)。 */
    private static final long ENHANCE_BUDGET_SECONDS = 6;

    /** 短剧源名称关键词(Plugin.name/sourceName 命中即跳过,红果=红果短剧平台)。 */
    private static final Pattern SHORT_DRAMA_NAME = Pattern.compile("短剧|微短剧|爽剧|红果");
    /** 音乐/音频类源名称关键词:TMDB/豆瓣均无对应条目,增强必然 miss 或误配。 */
    private static final Pattern MUSIC_NAME = Pattern.compile("音乐|DJ|舞曲|演唱会|MTV|无损|发烧|戏曲|评书|相声|有声|广播剧|儿歌");
    /** detail type_name 运行时信号。 */
    private static final Pattern RUNTIME_KIND = Pattern.compile("短剧|音乐|戏曲|演唱");
    /** 「更新至3位数集」:短剧典型形态(正剧极少三位数在更)。 */
    private static final Pattern UPDATE_TO_MANY = Pattern.compile("更新至\\s*\\d{3,}\\s*[集期]");
    /** 选集段名混淆信号:URL/域名水印(括号外裸出现的)。 */
    private static final Pattern WATERMARK = Pattern.compile("(?i)https?://|www\\.|[a-z0-9-]+\\.(?:com|net|org|cc|cn|xyz|top|vip|site|shop|club|online|icu|fun|live|pro|me|io)\\b");
    /** 无 CJK 且足够长的压缩串(点分隔/乱码类,如 ychs2024webdl)。 */
    private static final Pattern GIBBERISH = Pattern.compile("[\\p{IsAlphabetic}\\d.\\-_]{16,}");
    /** 检索词有效性:须含 CJK 或至少两个连续字母——纯符号/单字符乱码无从匹配,宁缺勿错。 */
    private static final Pattern SEARCHABLE = Pattern.compile("[\\u4e00-\\u9fa5]|[A-Za-z]{2}");
    private static final Pattern YEAR = Pattern.compile("(19[7-9]\\d|20[0-4]\\d)");

    private final AppProperties appProperties;
    private final DoubanService doubanService;
    private final TmdbService tmdbService;
    private final MediaSubscriptionCheckService checkService;

    /** 源级运行时跳过裁决(pluginId → skip):detail 信号按源稳定,缓存 24h;强制 TRUE 不走此缓存。 */
    private final Cache<Integer, Boolean> skipCache = Caffeine.newBuilder()
            .maximumSize(500).expireAfterWrite(Duration.ofHours(24)).build();
    /** 条目级正向结果(site|ids → 字段),6h。 */
    private final Cache<String, Enhancement> hitCache = Caffeine.newBuilder()
            .maximumSize(2000).expireAfterWrite(Duration.ofHours(6)).build();
    /** 条目级负结果(双源 miss,不含超时),1h:防同一条目反复打 TMDB 配额。 */
    private final Cache<String, Boolean> missCache = Caffeine.newBuilder()
            .maximumSize(2000).expireAfterWrite(Duration.ofHours(1)).build();
    /** TMDB 标题匹配结果(title|year → Optional,empty=MISS 哨兵;Caffeine 不存 null),24h。 */
    private final Cache<String, java.util.Optional<Tmdb>> tmdbMatchCache = Caffeine.newBuilder()
            .maximumSize(1000).expireAfterWrite(Duration.ofHours(24)).build();

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "t4-detail-enhance");
        thread.setDaemon(true);
        return thread;
    });

    public T4DetailEnhancer(AppProperties appProperties, DoubanService doubanService,
                            TmdbService tmdbService, MediaSubscriptionCheckService checkService) {
        this.appProperties = appProperties;
        this.doubanService = doubanService;
        this.tmdbService = tmdbService;
        this.checkService = checkService;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** detail 响应增强入口:T4Controller.pluginVod 的 detail 分支调用(仍在请求线程,可取请求地址)。 */
    public JsonNode enhance(Plugin plugin, JsonNode result) {
        try {
            if (result == null || !result.isObject()) {
                return result;
            }
            JsonNode list = result.get("list");
            if (list == null || !list.isArray() || list.isEmpty()) {
                return result;
            }
            for (JsonNode item : list) {
                enhanceItem(plugin, item);
            }
            return result;
        } catch (Exception e) {
            log.warn("t4 detail enhance failed: plugin={} error={}", plugin.getId(), e.getMessage());
            return result;
        }
    }

    private void enhanceItem(Plugin plugin, JsonNode item) {
        if (!(item instanceof ObjectNode node)) {
            return;
        }
        boolean skip = shouldSkip(plugin, node);
        // 选集名清洗:与元数据命中无关,只要该源允许增强就执行(廉价确定性操作)
        if (!skip) {
            cleanEpisodeNames(node);
        }
        if (skip) {
            return;
        }
        String rawName = node.path("vod_name").asText("");
        String site = "plugin-" + plugin.getId();
        String ids = node.path("vod_id").asText("");
        String entryKey = site + "|" + ids;

        Enhancement cached = hitCache.getIfPresent(entryKey);
        if (cached == null && missCache.getIfPresent(entryKey) != null) {
            return;
        }
        if (cached == null) {
            Title title = cleanTitle(rawName, node.path("vod_year").asText(null));
            if (title == null) {
                return;
            }
            try {
                cached = CompletableFuture.supplyAsync(() -> lookup(title), executor)
                        .get(ENHANCE_BUDGET_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.debug("t4 detail enhance timeout: {} {}", site, title.keyword());
                return; // 超时不缓存负结果,下次再试
            } catch (Exception e) {
                log.debug("t4 detail enhance error: {} {}", site, e.getMessage());
                return;
            }
            if (cached == null) {
                missCache.put(entryKey, Boolean.TRUE);
                return;
            }
            hitCache.put(entryKey, cached);
        }
        apply(node, cached);
        log.debug("t4 detail enhanced: {} {} <- {} {}", site, rawName, cached.source(), cached.name());
    }

    // ==================== 源级策略(短剧/音乐忽略 + 三态) ====================

    /** true = 该源不做任何增强(也不做选集清洗)。FALSE 三态优先跳过,TRUE 强制跳过自动检测。 */
    private boolean shouldSkip(Plugin plugin, ObjectNode detailItem) {
        Boolean override = plugin.getDetailEnhance();
        if (Boolean.FALSE.equals(override)) {
            return true;
        }
        if (Boolean.TRUE.equals(override)) {
            return false;
        }
        String name = StringUtils.defaultString(plugin.getName()) + " " + StringUtils.defaultString(plugin.getSourceName());
        if (SHORT_DRAMA_NAME.matcher(name).find() || MUSIC_NAME.matcher(name).find()) {
            return true;
        }
        Boolean verdict = skipCache.getIfPresent(plugin.getId());
        if (verdict != null) {
            return verdict;
        }
        boolean skip = runtimeKindSignal(detailItem);
        if (skip) {
            skipCache.put(plugin.getId(), Boolean.TRUE);
            log.info("t4 detail enhance skip by runtime signal: plugin={} name={}", plugin.getId(), plugin.getName());
        }
        return skip;
    }

    /** detail 运行时信号:type_name 关键词/更新至三位数集/单线路段数>200/无年份无演员且段数>60。 */
    boolean runtimeKindSignal(ObjectNode detailItem) {
        String typeName = detailItem.path("type_name").asText("");
        if (RUNTIME_KIND.matcher(typeName).find()) {
            return true;
        }
        if (UPDATE_TO_MANY.matcher(detailItem.path("vod_remarks").asText("")).find()) {
            return true;
        }
        int segments = countSegments(detailItem.path("vod_play_url").asText(""));
        if (segments > 200) {
            return true;
        }
        boolean noMeta = detailItem.path("vod_year").asText("").isBlank()
                && detailItem.path("vod_actor").asText("").isBlank();
        return noMeta && segments > 60;
    }

    private int countSegments(String playUrl) {
        if (StringUtils.isBlank(playUrl)) {
            return 0;
        }
        int max = 0;
        for (String route : playUrl.split("\\$\\$\\$")) {
            max = Math.max(max, route.split("#").length);
        }
        return max;
    }

    // ==================== 标题清洗(混淆文件名 → 检索词) ====================

    /** 清洗 vod_name → 检索词;清洗后为空/单字符返回 null(乱码串无从匹配,宁缺勿错)。 */
    Title cleanTitle(String rawName, String yearText) {
        if (StringUtils.isBlank(rawName)) {
            return null;
        }
        Integer year = sanitizeYear(yearText);
        // 元数据 id 标记快路径由 provider 消费(豆瓣 getByName 认 dbid;tmdbid 此处解析)
        String dbid = TextUtils.parseMetaIdTag(rawName, "dbid");
        String tmdbid = TextUtils.parseMetaIdTag(rawName, "tmdbid");
        // 水印/技术段先剥(BRACKET_TECH_EXTRA 管线,广告水印毒化修复同源),再走通用清洗
        String name = MediaSubscriptionCheckService.stripTechBrackets(TextUtils.stripMetaIdTags(rawName));
        name = TextUtils.collapseCjkSpaces(TextUtils.fixName(name));
        if (year == null) {
            Matcher matcher = YEAR.matcher(rawName);
            if (matcher.find()) {
                year = Integer.valueOf(matcher.group(1));
            }
        }
        Integer season = TextUtils.parseTitleSeason(name);
        String keyword = TextUtils.stripSeasonSuffix(name).trim();
        if (keyword.length() < 2 || !SEARCHABLE.matcher(keyword).find()) {
            return null;
        }
        return new Title(rawName, keyword, season, year, dbid, tmdbid);
    }

    private Integer sanitizeYear(String yearText) {
        if (StringUtils.isBlank(yearText)) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\d{4}").matcher(yearText);
        return matcher.find() ? Integer.valueOf(matcher.group()) : null;
    }

    // ==================== 双源链(优先级可配,单一来源原则) ====================

    /** 豆瓣优先(默认)或 TMDB 优先,首选 miss 回退另一源;双 miss 返回 null。 */
    Enhancement lookup(Title title) {
        boolean tmdbFirst = "tmdb".equals(appProperties.getT4EnhancePriority());
        Enhancement first = tmdbFirst ? fromTmdb(title) : fromDouban(title);
        return first != null ? first : (tmdbFirst ? fromDouban(title) : fromTmdb(title));
    }

    /** 本地豆瓣库(零网络):getByName 内置 dbid 标签→Alias→清洗全链;增强器侧再做严格校验防松配。 */
    Enhancement fromDouban(Title title) {
        try {
            // 保留 dbid 标签的原始名直传(getByName 的标签快路径);无标签传清洗词
            String query = title.dbid() != null ? title.rawName() : title.keyword();
            Movie movie = doubanService.getByName(query, title.year());
            if (movie == null || !accepts(title, movie.getName(), movie.getYear())) {
                return null;
            }
            return new Enhancement("douban", movie.getName(), movie.getCover(), movie.getDescription(),
                    movie.getYear(), movie.getActors(), movie.getDirectors(), movie.getCountry(),
                    movie.getLanguage(), movie.getGenre());
        } catch (Exception e) {
            log.debug("douban enhance miss: {} {}", title.keyword(), e.getMessage());
            return null;
        }
    }

    Enhancement fromTmdb(Title title) {
        try {
            Tmdb tmdb = null;
            if (title.tmdbid() != null) {
                tmdb = tmdbService.getById("tv", Integer.valueOf(title.tmdbid()));
                if (tmdb == null) {
                    tmdb = tmdbService.getById("movie", Integer.valueOf(title.tmdbid()));
                }
            }
            if (tmdb == null) {
                tmdb = searchTmdb(title);
            }
            if (tmdb == null || !accepts(title, tmdb.getName(), tmdb.getYear())) {
                return null;
            }
            return new Enhancement("tmdb", tmdb.getName(), tmdb.getCover(), tmdb.getDescription(),
                    tmdb.getYear(), tmdb.getActors(), tmdb.getDirectors(), tmdb.getCountry(),
                    tmdb.getLanguage(), tmdb.getGenre());
        } catch (Exception e) {
            log.debug("tmdb enhance miss: {} {}", title.keyword(), e.getMessage());
            return null;
        }
    }

    /** tv→movie 两段搜索(宽松搜索+自校验,match=true 精确快路径对 tv/movie 各打一次太费配额)。 */
    private Tmdb searchTmdb(Title title) {
        String cacheKey = title.keyword() + "|" + title.year();
        java.util.Optional<Tmdb> cached = tmdbMatchCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached.orElse(null);
        }
        Tmdb result = null;
        for (String type : List.of("tv", "movie")) {
            Tmdb candidate = tmdbService.search(type, title.keyword(), title.year(), false);
            if (candidate != null) {
                result = candidate; // tv 优先;首结果由 accepts 统一校验
                break;
            }
        }
        tmdbMatchCache.put(cacheKey, java.util.Optional.ofNullable(result));
        return result;
    }

    /** 严格匹配:两侧都过 stripSeasonSuffix 后精确等 or Levenshtein ≤ max(1, len/8);年份都有时 |Δ|≤1。 */
    boolean accepts(Title title, String candidateName, Integer candidateYear) {
        if (StringUtils.isBlank(candidateName)) {
            return false;
        }
        String left = TextUtils.stripSeasonSuffix(title.keyword()).trim();
        String right = TextUtils.stripSeasonSuffix(candidateName).trim();
        boolean nameOk = left.equals(right)
                || TextUtils.minDistance(left, right) <= Math.max(1, left.length() / 8);
        if (!nameOk) {
            return false;
        }
        return title.year() == null || candidateYear == null || Math.abs(title.year() - candidateYear) <= 1;
    }

    // ==================== 字段应用(不动 vod_name/vod_remarks/ID) ====================

    private void apply(ObjectNode node, Enhancement enhancement) {
        putIfNotBlank(node, "vod_pic", proxyCover(enhancement.cover()));
        putIfNotBlank(node, "vod_content", enhancement.description());
        if (enhancement.year() != null) {
            node.put("vod_year", String.valueOf(enhancement.year()));
        }
        putIfNotBlank(node, "vod_actor", enhancement.actors());
        putIfNotBlank(node, "vod_director", enhancement.directors());
        putIfNotBlank(node, "vod_area", enhancement.country());
        putIfNotBlank(node, "vod_lang", enhancement.language());
        putIfNotBlank(node, "type_name", enhancement.genre());
    }

    private void putIfNotBlank(ObjectNode node, String field, String value) {
        if (StringUtils.isNotBlank(value)) {
            node.put(field, value);
        }
    }

    /** 海报统一经 /images 反代绝对化:豆瓣图床 Referer 风险、TMDB 墙内不可达,一视同仁(fixCover 同构)。
     *  基地址取不到(非请求上下文)时原样返回,不下发相对路径(T4 相对路径会解析到五动作前缀下)。 */
    private String proxyCover(String cover) {
        if (StringUtils.isBlank(cover)) {
            return cover;
        }
        String base = buildImageBaseUrl();
        if (base == null) {
            return cover;
        }
        String normalized = cover.startsWith("//") ? "https:" + cover : cover;
        return base + "?url=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8);
    }

    /** /images 反代基地址:apply 在请求线程执行,可取当前请求 host(反代默认带豆瓣 Referer,兼走 TMDB 端点)。 */
    private String buildImageBaseUrl() {
        try {
            return ServletUriComponentsBuilder.fromCurrentRequest()
                    .scheme(Utils.publicScheme(appProperties.isEnableHttps()))
                    .replacePath("/images")
                    .replaceQuery(null)
                    .build()
                    .toUriString();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 选集名清洗(仅混淆名→第N集,ID 永不动) ====================

    /** vod_play_url 各线路:混淆段名(乱码/域名水印/超长无 CJK)→「第N集」;正常名不动;可解析率<50% 整线跳过。 */
    void cleanEpisodeNames(ObjectNode node) {
        String playUrl = node.path("vod_play_url").asText("");
        if (StringUtils.isBlank(playUrl) || !playUrl.contains("$")) {
            return;
        }
        Integer season = TextUtils.parseTitleSeason(node.path("vod_name").asText(""));
        String[] routes = playUrl.split("\\$\\$\\$");
        List<String> cleaned = new ArrayList<>(routes.length);
        boolean changed = false;
        for (String route : routes) {
            String result = cleanRoute(route, season);
            changed |= !result.equals(route);
            cleaned.add(result);
        }
        if (changed) {
            node.put("vod_play_url", String.join("$$$", cleaned));
        }
    }

    private String cleanRoute(String route, Integer season) {
        String[] items = route.split("#");
        if (items.length < 2) {
            return route; // 单段线路(电影正片/整集):没有集序语义,重编号反而有害
        }
        boolean[] junk = new boolean[items.length];
        int[] episodes = new int[items.length];
        int parseable = 0;
        for (int i = 0; i < items.length; i++) {
            int dollar = items[i].lastIndexOf('$');
            String name = dollar >= 0 ? items[i].substring(0, dollar) : items[i];
            junk[i] = isJunkName(name);
            episodes[i] = junk[i] ? parseJunkEpisode(name, season) : -1;
            if (!junk[i] || episodes[i] > 0) {
                parseable++;
            }
        }
        if (parseable * 2 < items.length) {
            return route; // 过半不可解析:疑似音乐/非剧集内容,整线不动
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                result.append('#');
            }
            int dollar = items[i].lastIndexOf('$');
            if (junk[i] && episodes[i] > 0) {
                // 只重写显示名,ID 段($ 及其后)原样保留——丢了 ID 该集就播不了
                result.append("第").append(episodes[i]).append("集");
                if (dollar >= 0) {
                    result.append(items[i], dollar, items[i].length());
                }
            } else {
                result.append(items[i]);
            }
        }
        return result.toString();
    }

    /**
     * 混淆段名的集号解析:先剥域名水印再走 parseEpisode——括号外裸水印(「影片www.dyg7.com.mp4」的 7)
     * 正是八期修复过的集号毒化形态在括号外的变体,直接喂 parseEpisode 会把域名数字当集号。
     * 解析不出/超出 1..2000 合理域 → 保留原名(宁可不洗,不可洗错)。
     */
    private int parseJunkEpisode(String name, Integer season) {
        String cleaned = WATERMARK.matcher(MediaSubscriptionCheckService.stripTechBrackets(name)).replaceAll(" ");
        int episode = checkService.parseEpisode(cleaned, season);
        return episode > 0 && episode <= 2000 ? episode : -1;
    }

    /** 混淆段名判定:剥技术段后为空/域名水印/超长无 CJK 压缩串。纯数字(01/1)是正常集号形态,不算混淆。 */
    boolean isJunkName(String name) {
        if (StringUtils.isBlank(name)) {
            return true;
        }
        String cleaned = MediaSubscriptionCheckService.stripTechBrackets(name).trim();
        if (cleaned.isEmpty()) {
            return true;
        }
        if (WATERMARK.matcher(cleaned).find()) {
            return true;
        }
        return GIBBERISH.matcher(cleaned).matches();
    }

    // ==================== 值对象 ====================

    record Title(String rawName, String keyword, Integer season, Integer year, String dbid, String tmdbid) {
    }

    /** 增强字段集(单一来源,不跨源混拼)。 */
    record Enhancement(String source, String name, String cover, String description, Integer year,
                       String actors, String directors, String country, String language, String genre) {
    }
}
