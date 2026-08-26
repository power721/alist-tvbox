package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.model.Filter;
import cn.har01d.alist_tvbox.model.FilterValue;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static cn.har01d.alist_tvbox.util.Constants.TMDB_API_KEY;

@Slf4j
@Service
public class PianDanService {
    static final String DOUBAN_PREFIX = "douban:";
    static final String TMDB_PREFIX = "tmdb:";
    private static final String TMDB_API = "https://api.themoviedb.org/3";
    private static final String TMDB_IMAGE = "https://image.tmdb.org/t/p/w500";
    private static final Set<String> TRENDING_MEDIA = Set.of("all", "movie", "tv");
    private static final Set<String> TRENDING_WINDOWS = Set.of("day", "week");
    private static final Set<String> ANIME_MEDIA = Set.of("movie", "tv");
    private static final Set<String> VARIETY_LISTS = Set.of("hot", "today", "tomorrow", "trend", "top");
    private static final Set<String> TV_PLATFORM_CONTENT = Set.of("drama", "variety", "anime");
    private static final Set<String> TV_PLATFORM_SORTS = Set.of("popularity.desc", "first_air_date.desc", "vote_average.desc");
    private static final Set<String> MOVIE_PLATFORM_SORTS = Set.of("popularity.desc", "primary_release_date.desc", "vote_average.desc", "revenue.desc");
    private static final Set<String> TV_NETWORKS = Set.of("2007", "1330", "1419", "1631", "1605", "213", "2739", "49", "2552");
    private static final Set<String> MOVIE_PROVIDERS = Set.of("8", "337", "1899", "350", "9");
    private static final Set<String> WATCH_REGIONS = Set.of("US", "CN", "HK", "TW", "JP", "KR", "GB", "CA", "AU");
    private static final int TMDB_MAX_PAGE = 500;
    private static final String HOME_DEFAULT_CATEGORY = "hot_movie";
    private static final Set<String> HOME_DOUBAN_CATEGORIES = Set.of(
            "hot_movie", "hot_tv", "tv_domestic", "tv_american", "tv_animation", "tv_variety_show",
            "tv_korean", "tv_japanese", "suggestion_movie", "suggestion_tv", "movie_top250",
            "movie_real_time_hotest", "movie_weekly_best", "tv_real_time_hotest",
            "tv_chinese_best_weekly", "tv_global_best_weekly", "show_chinese_best_weekly"
    );
    private static final Set<String> TMDB_MOVIE_LISTS = Set.of("popular", "top_rated", "now_playing", "upcoming", "discover", "platform");
    private static final Set<String> TMDB_TV_LISTS = Set.of("popular", "top_rated", "airing_today", "on_the_air", "discover", "platform");
    private static final Set<String> DOUBAN_CATEGORY_VALUES = Set.of("tv_domestic", "tv_american", "tv_korean", "tv_japanese", "tv_animation", "tv_variety_show");
    private static final Set<String> DOUBAN_BILLBOARD_VALUES = Set.of(
            "movie_top250", "movie_real_time_hotest", "movie_weekly_best", "tv_real_time_hotest",
            "tv_chinese_best_weekly", "tv_global_best_weekly", "show_chinese_best_weekly",
            "show_global_best_weekly", "suggestion_movie", "suggestion_tv"
    );

    private final TelegramService telegramService;
    private final SettingRepository settingRepository;
    private final SubscriptionSourceService subscriptionSourceService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Cache<String, MovieList> listCache = Caffeine.newBuilder()
            .maximumSize(256)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();
    private final Cache<String, MovieList> staleListCache = Caffeine.newBuilder()
            .maximumSize(256)
            .expireAfterWrite(Duration.ofHours(24))
            .build();

    public PianDanService(TelegramService telegramService,
                          SettingRepository settingRepository,
                          SubscriptionSourceService subscriptionSourceService,
                          RestTemplateBuilder builder,
                          ObjectMapper objectMapper) {
        this.telegramService = telegramService;
        this.settingRepository = settingRepository;
        this.subscriptionSourceService = subscriptionSourceService;
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
    }

    public CategoryList category() {
        return "lite".equals(categoryMode()) ? buildLiteCategory() : buildFullCategory();
    }

    /** 片单追更分类:排除电影类目(追更只对剧集/综艺的集数有意义);lite 榜单、趋势、动漫筛选里的电影选项一并隐藏。
     * 电视端 csp_PianDan 导航仍走 category() 全量,电影分类在电视浏览场景是正当用途。 */
    public CategoryList subscriptionCategory() {
        CategoryList result = category();
        result.getCategories().removeIf(category -> movieCategoryId(category.getType_id()));
        removeMovieFilterValues(result.getFilters().get(DOUBAN_PREFIX + "billboard"), "billboard");
        removeMovieFilterValues(result.getFilters().get(TMDB_PREFIX + "trending"), "mediaType");
        removeMovieFilterValues(result.getFilters().get(TMDB_PREFIX + "anime"), "kind");
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());
        return result;
    }

    /** 纯电影分类(豆瓣 hot_movie/suggestion_movie/movie_* 榜单与 TMDB movie/discover_movie/platform_movie);trending/anime 等混合类目不算。 */
    private static boolean movieCategoryId(String typeId) {
        String id = StringUtils.removeStart(StringUtils.removeStart(typeId, DOUBAN_PREFIX), TMDB_PREFIX);
        return id.startsWith("movie_") || id.equals("movie") || id.equals("hot_movie")
                || id.equals("suggestion_movie") || id.equals("discover_movie") || id.equals("platform_movie");
    }

    private static void removeMovieFilterValues(List<Filter> filters, String key) {
        if (filters == null) {
            return;
        }
        for (Filter filter : filters) {
            if (key.equals(filter.getKey())) {
                filter.getValue().removeIf(value -> movieCategoryId(value.getV()));
            }
        }
    }

    private CategoryList buildFullCategory() {
        CategoryList result = new CategoryList();
        CategoryList douban = telegramService.categoryDouban();
        for (Category source : douban.getCategories()) {
            Category category = copyCategory(source);
            category.setType_id(DOUBAN_PREFIX + source.getType_id());
            category.setType_name("豆瓣·" + source.getType_name());
            result.getCategories().add(category);
        }
        douban.getFilters().forEach((key, value) -> result.getFilters().put(DOUBAN_PREFIX + key, value));

        addTmdbCategory(result, "trending", "TMDB趋势");
        addTmdbCategory(result, "movie_popular", "TMDB热门电影");
        addTmdbCategory(result, "movie_top_rated", "TMDB高分电影");
        addTmdbCategory(result, "movie_now_playing", "TMDB院线热映");
        addTmdbCategory(result, "movie_upcoming", "TMDB即将上映");
        addTmdbCategory(result, "tv_popular", "TMDB热门剧集");
        addTmdbCategory(result, "tv_top_rated", "TMDB高分剧集");
        addTmdbCategory(result, "tv_airing_today", "TMDB今日播出");
        addTmdbCategory(result, "tv_on_the_air", "TMDB正在播出");
        addTmdbCategory(result, "anime", "TMDB动漫片库");
        addTmdbCategory(result, "variety", "TMDB综艺片库");
        addTmdbCategory(result, "platform_tv", "TMDB平台剧集");
        addTmdbCategory(result, "platform_movie", "TMDB流媒体电影");
        addTmdbCategory(result, "discover_movie", "TMDB电影片库");
        addTmdbCategory(result, "discover_tv", "TMDB剧集片库");
        addTmdbFilters(result);

        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());
        return result;
    }

    private CategoryList buildLiteCategory() {
        CategoryList result = new CategoryList();
        CategoryList douban = telegramService.categoryDouban();
        keepDoubanCategories(result, douban, Set.of("hot_movie", "hot_tv"));
        addDoubanCategory(result, "category", "分类");
        addDoubanCategory(result, "billboard", "榜单");
        keepDoubanCategories(result, douban, Set.of("local"));
        result.getFilters().put(DOUBAN_PREFIX + "category", List.of(
                filter("category", "分类", values(
                        "国产剧", "tv_domestic", "欧美剧", "tv_american", "韩剧", "tv_korean",
                        "日剧", "tv_japanese", "动漫", "tv_animation", "综艺", "tv_variety_show"))));
        result.getFilters().put(DOUBAN_PREFIX + "billboard", List.of(
                filter("billboard", "榜单", values(
                        "电影Top250", "movie_top250", "实时热门电影", "movie_real_time_hotest",
                        "一周口碑电影榜", "movie_weekly_best", "实时热门电视", "tv_real_time_hotest",
                        "华语口碑剧集榜", "tv_chinese_best_weekly", "全球口碑剧集榜", "tv_global_best_weekly",
                        "国内口碑综艺榜", "show_chinese_best_weekly", "国外口碑综艺榜", "show_global_best_weekly",
                        "电影推荐", "suggestion_movie", "电视剧推荐", "suggestion_tv"))));

        addTmdbCategory(result, "trending", "TMDB趋势");
        addTmdbCategory(result, "movie", "TMDB电影");
        addTmdbCategory(result, "tv", "TMDB剧集");
        addTmdbCategory(result, "discover_movie", "TMDB电影片库");
        addTmdbCategory(result, "discover_tv", "TMDB剧集片库");
        addTmdbCategory(result, "anime", "TMDB动漫片库");
        addTmdbCategory(result, "variety", "TMDB综艺片库");
        result.getFilters().put(TMDB_PREFIX + "trending", trendingFilters());
        result.getFilters().put(TMDB_PREFIX + "movie", List.of(
                filter("list", "分类", values(
                        "热门", "popular", "高分", "top_rated", "院线热映", "now_playing",
                        "即将上映", "upcoming", "流媒体", "platform"))));
        result.getFilters().put(TMDB_PREFIX + "tv", List.of(
                filter("list", "分类", values(
                        "热门", "popular", "高分", "top_rated", "今日播出", "airing_today",
                        "正在播出", "on_the_air", "平台", "platform"))));
        result.getFilters().put(TMDB_PREFIX + "discover_movie", discoverFilters(true));
        result.getFilters().put(TMDB_PREFIX + "discover_tv", discoverFilters(false));
        result.getFilters().put(TMDB_PREFIX + "anime", animeFilters());
        result.getFilters().put(TMDB_PREFIX + "variety", varietyFilters());

        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());
        return result;
    }

    private void keepDoubanCategories(CategoryList result, CategoryList douban, Set<String> ids) {
        for (Category source : douban.getCategories()) {
            if (ids.contains(source.getType_id())) {
                Category category = copyCategory(source);
                category.setType_id(DOUBAN_PREFIX + source.getType_id());
                category.setType_name("豆瓣·" + source.getType_name());
                result.getCategories().add(category);
            }
        }
        for (String id : ids) {
            List<Filter> filters = douban.getFilters().get(id);
            if (filters != null) {
                result.getFilters().put(DOUBAN_PREFIX + id, filters);
            }
        }
    }

    private void addDoubanCategory(CategoryList result, String id, String name) {
        Category category = new Category();
        category.setType_id(DOUBAN_PREFIX + id);
        category.setType_name("豆瓣·" + name);
        category.setType_flag(0);
        result.getCategories().add(category);
    }

    public MovieList home() {
        List<MovieDetail> items = new ArrayList<>();
        try {
            MovieList douban = telegramService.listDouban(homeDoubanCategory(), "", null, null, null, null, 1, 20);
            items.addAll(toNavigationList(douban).getList().stream().limit(20).toList());
        } catch (RuntimeException e) {
            log.warn("load Douban navigation home failed", e);
        }
        items.addAll(listTmdb("trending", 1, 10, Map.of()).getList());

        MovieList result = new MovieList();
        result.setList(items);
        result.setPage(1);
        result.setPagecount(1);
        result.setLimit(items.size());
        result.setTotal(items.size());
        return result;
    }

    private JsonNode pianDanConfig() {
        String extend = subscriptionSourceService.getBuiltinExtend("csp_PianDan");
        if (StringUtils.isBlank(extend)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(extend);
            return node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String homeDoubanCategory() {
        JsonNode config = pianDanConfig();
        String category = config == null ? "" : config.path("home").asText("");
        return HOME_DOUBAN_CATEGORIES.contains(category) ? category : HOME_DEFAULT_CATEGORY;
    }

    private String categoryMode() {
        JsonNode config = pianDanConfig();
        String mode = config == null ? "" : config.path("mode").asText("");
        return "lite".equalsIgnoreCase(mode) ? "lite" : "all";
    }

    public MovieList list(String type, String ac, int page, int size, Map<String, String> filters) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        if (StringUtils.startsWith(type, DOUBAN_PREFIX)) {
            return toNavigationList(telegramService.listDouban(
                    resolveDoubanType(type.substring(DOUBAN_PREFIX.length()), filters),
                    ac,
                    filters.get("sort"),
                    parseYear(filters.get("year")),
                    filters.get("genre"),
                    filters.get("region"),
                    safePage,
                    safeSize
            ));
        }
        if (StringUtils.startsWith(type, TMDB_PREFIX)) {
            return listTmdb(type.substring(TMDB_PREFIX.length()), safePage, safeSize, filters);
        }
        return emptyList(safePage, safeSize);
    }

    private MovieList listTmdb(String type, int page, int size, Map<String, String> filters) {
        int safePage = Math.min(TMDB_MAX_PAGE, Math.max(1, page));
        String effectiveType = resolveTmdbType(type, filters);
        String cacheKey = cacheKey(effectiveType, safePage, size, filters);
        MovieList cached = listCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        String path = tmdbPath(effectiveType, filters);
        if (path == null) {
            return emptyList(safePage, size);
        }

        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(TMDB_API + path)
                .queryParam("api_key", apiKey())
                .queryParam("language", "zh-CN")
                .queryParam("include_adult", false)
                .queryParam("page", safePage);
        addTmdbQueryFilters(uri, effectiveType, filters);

        try {
            String json = fetchTmdb(uri.build().encode().toUriString());
            JsonNode body = StringUtils.isBlank(json) ? null : objectMapper.readTree(json);
            MovieList result = mapTmdb(body, effectiveType, filters, safePage, size);
            listCache.put(cacheKey, result);
            staleListCache.put(cacheKey, result);
            return result;
        } catch (RestClientException | JsonProcessingException e) {
            log.warn("load TMDB navigation list failed: {}", type, e);
            MovieList stale = staleListCache.getIfPresent(cacheKey);
            return stale == null ? emptyList(safePage, size) : stale;
        }
    }

    private String resolveTmdbType(String type, Map<String, String> filters) {
        if ("movie".equals(type)) {
            return switch (allowedValue(filters, "list", "popular", TMDB_MOVIE_LISTS)) {
                case "top_rated" -> "movie_top_rated";
                case "now_playing" -> "movie_now_playing";
                case "upcoming" -> "movie_upcoming";
                case "discover" -> "discover_movie";
                case "platform" -> "platform_movie";
                default -> "movie_popular";
            };
        }
        if ("tv".equals(type)) {
            return switch (allowedValue(filters, "list", "popular", TMDB_TV_LISTS)) {
                case "top_rated" -> "tv_top_rated";
                case "airing_today" -> "tv_airing_today";
                case "on_the_air" -> "tv_on_the_air";
                case "discover" -> "discover_tv";
                case "platform" -> "platform_tv";
                default -> "tv_popular";
            };
        }
        return type;
    }

    private String resolveDoubanType(String type, Map<String, String> filters) {
        if ("category".equals(type)) {
            return allowedValue(filters, "category", "tv_domestic", DOUBAN_CATEGORY_VALUES);
        }
        if ("billboard".equals(type)) {
            return allowedValue(filters, "billboard", "movie_top250", DOUBAN_BILLBOARD_VALUES);
        }
        return type;
    }

    private String tmdbPath(String type, Map<String, String> filters) {
        return switch (type) {
            case "trending" -> "/trending/" + allowedValue(filters, "mediaType", "all", TRENDING_MEDIA) + "/"
                    + allowedValue(filters, "time_window", "week", TRENDING_WINDOWS);
            case "movie_popular" -> "/movie/popular";
            case "movie_top_rated" -> "/movie/top_rated";
            case "movie_now_playing" -> "/movie/now_playing";
            case "movie_upcoming" -> "/movie/upcoming";
            case "tv_popular" -> "/tv/popular";
            case "tv_top_rated" -> "/tv/top_rated";
            case "tv_airing_today" -> "/tv/airing_today";
            case "tv_on_the_air" -> "/tv/on_the_air";
            case "discover_movie" -> "/discover/movie";
            case "discover_tv" -> "/discover/tv";
            case "anime" -> "/discover/" + allowedValue(filters, "kind", "tv", ANIME_MEDIA);
            case "variety", "platform_tv" -> "/discover/tv";
            case "platform_movie" -> "/discover/movie";
            default -> null;
        };
    }

    private void addTmdbQueryFilters(UriComponentsBuilder uri, String type, Map<String, String> filters) {
        if (type.startsWith("movie_") && !type.startsWith("discover_")) {
            uri.queryParam("region", valueOrDefault(filters, "region", "CN"));
            return;
        }
        if ("variety".equals(type)) {
            addVarietyFilters(uri, filters);
            return;
        }
        if ("platform_tv".equals(type)) {
            addTvPlatformFilters(uri, filters);
            return;
        }
        if ("platform_movie".equals(type)) {
            addMoviePlatformFilters(uri, filters);
            return;
        }
        if (!type.startsWith("discover_") && !"anime".equals(type)) {
            return;
        }

        boolean anime = "anime".equals(type);
        boolean movie = type.endsWith("movie") || (anime && "movie".equals(allowedValue(filters, "kind", "tv", ANIME_MEDIA)));
        String sort = valueOrDefault(filters, "sort_by", "popularity.desc");
        String country = filters.get("with_origin_country");
        String origin = StringUtils.defaultIfBlank(country, filters.get("origin_group"));

        addQueryParam(uri, "sort_by", sort);
        addQueryParam(uri, "with_genres", anime ? "16" : filters.get("with_genres"));
        addQueryParam(uri, "with_origin_country", anime ? valueOrDefault(filters, "anime_region", "JP") : origin);
        addQueryParam(uri, "with_original_language", filters.get("with_original_language"));
        addQueryParam(uri, "vote_average.gte", filters.get("vote_average.gte"));
        String year = filters.get("year");
        addQueryParam(uri, movie ? "primary_release_year" : "first_air_date_year", year);
        if (movie && StringUtils.isNotBlank(country) && !country.contains("|")) {
            uri.queryParam("region", country);
        }
        String votes = filters.get("vote_count.gte");
        if (StringUtils.isBlank(votes) && "vote_average.desc".equals(sort)) {
            votes = anime ? "20" : "50";
        }
        addQueryParam(uri, "vote_count.gte", votes);
        addRuntimeFilters(uri, filters.get("runtime"));
        if (movie) {
            uri.queryParam("include_video", false);
        }
        addReleaseDateLimit(uri, movie, sort);
    }

    private void addVarietyFilters(UriComponentsBuilder uri, Map<String, String> filters) {
        String listType = allowedValue(filters, "list_type", "hot", VARIETY_LISTS);
        String sort = "top".equals(listType) ? "vote_average.desc" : "popularity.desc";
        LocalDate date = LocalDate.now();

        uri.queryParam("sort_by", sort)
                .queryParam("with_genres", "10764|10767");
        addQueryParam(uri, "with_origin_country", filters.get("region"));
        if ("today".equals(listType) || "tomorrow".equals(listType)) {
            String airDate = date.plusDays("tomorrow".equals(listType) ? 1 : 0).toString();
            uri.queryParam("air_date.gte", airDate).queryParam("air_date.lte", airDate);
        } else if ("trend".equals(listType)) {
            uri.queryParam("first_air_date.gte", date.minusYears(5));
        } else if ("top".equals(listType)) {
            uri.queryParam("vote_count.gte", 15);
        }
    }

    private void addTvPlatformFilters(UriComponentsBuilder uri, Map<String, String> filters) {
        String content = allowedValue(filters, "content", "drama", TV_PLATFORM_CONTENT);
        String sort = allowedValue(filters, "sort_by", "popularity.desc", TV_PLATFORM_SORTS);
        uri.queryParam("sort_by", sort)
                .queryParam("with_networks", allowedValue(filters, "network", "2007", TV_NETWORKS))
                .queryParam("include_null_first_air_dates", false);
        if ("anime".equals(content)) {
            uri.queryParam("with_genres", "16");
        } else if ("variety".equals(content)) {
            uri.queryParam("with_genres", "10764|10767");
        } else {
            uri.queryParam("without_genres", "16,10764,10767");
        }
        if ("vote_average.desc".equals(sort)) {
            uri.queryParam("vote_count.gte", 50);
        }
        addReleaseDateLimit(uri, false, sort);
    }

    private void addMoviePlatformFilters(UriComponentsBuilder uri, Map<String, String> filters) {
        String sort = allowedValue(filters, "sort_by", "popularity.desc", MOVIE_PLATFORM_SORTS);
        uri.queryParam("sort_by", sort)
                .queryParam("watch_region", allowedValue(filters, "watch_region", "US", WATCH_REGIONS))
                .queryParam("with_watch_providers", allowedValue(filters, "provider", "8", MOVIE_PROVIDERS))
                .queryParam("include_video", false);
        if ("vote_average.desc".equals(sort)) {
            uri.queryParam("vote_count.gte", 50);
        }
        addReleaseDateLimit(uri, true, sort);
    }

    private void addReleaseDateLimit(UriComponentsBuilder uri, boolean movie, String sort) {
        String latestSort = movie ? "primary_release_date.desc" : "first_air_date.desc";
        if (latestSort.equals(sort)) {
            uri.queryParam(movie ? "primary_release_date.lte" : "first_air_date.lte", LocalDate.now().plusDays(31));
        }
    }

    private void addRuntimeFilters(UriComponentsBuilder uri, String runtime) {
        if ("short".equals(runtime)) {
            uri.queryParam("with_runtime.lte", 90);
        } else if ("medium".equals(runtime)) {
            uri.queryParam("with_runtime.gte", 90);
            uri.queryParam("with_runtime.lte", 120);
        } else if ("long".equals(runtime)) {
            uri.queryParam("with_runtime.gte", 120);
        }
    }

    private void addQueryParam(UriComponentsBuilder uri, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            uri.queryParam(key, value);
        }
    }

    private String fetchTmdb(String url) {
        RestClientException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return restTemplate.getForObject(url, String.class);
            } catch (RestClientException e) {
                lastError = e;
                if (attempt == 2 || !isRetryable(e)) {
                    throw e;
                }
                log.debug("retry TMDB navigation request: attempt={}", attempt + 2);
            }
        }
        throw lastError;
    }

    private boolean isRetryable(RestClientException error) {
        if (error instanceof RestClientResponseException responseError) {
            int status = responseError.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return true;
    }

    private MovieList mapTmdb(JsonNode body, String categoryType, Map<String, String> filters, int page, int size) {
        if (body == null || !body.path("results").isArray()) {
            return emptyList(page, size);
        }
        List<MovieDetail> items = new ArrayList<>();
        for (JsonNode item : body.path("results")) {
            String mediaType = item.path("media_type").asText(defaultMediaType(categoryType, filters));
            if (!"movie".equals(mediaType) && !"tv".equals(mediaType)) {
                continue;
            }
            String title = firstNotBlank(item.path("title").asText(""), item.path("name").asText(""));
            int tmdbId = item.path("id").asInt(0);
            if (StringUtils.isBlank(title) || tmdbId < 1) {
                continue;
            }
            String date = firstNotBlank(item.path("release_date").asText(""), item.path("first_air_date").asText(""));
            String year = date.length() >= 4 ? date.substring(0, 4) : "";
            double score = item.path("vote_average").asDouble(0);

            MovieDetail movie = new MovieDetail();
            movie.setVod_id(TMDB_PREFIX + mediaType + ":" + tmdbId);
            movie.setVod_name(title);
            String poster = firstNotBlank(item.path("poster_path").asText(""), item.path("backdrop_path").asText(""));
            if (StringUtils.isNotBlank(poster)) {
                movie.setVod_pic(TMDB_IMAGE + poster);
            }
            movie.setVod_year(year);
            movie.setVod_content(item.path("overview").asText(""));
            movie.setType_name("movie".equals(mediaType) ? "电影" : "剧集");
            movie.setVod_remarks(remarks(year, score));
            items.add(movie);
            if (items.size() >= size) {
                break;
            }
        }

        MovieList result = new MovieList();
        result.setList(items);
        result.setPage(body.path("page").asInt(page));
        result.setPagecount(Math.min(TMDB_MAX_PAGE, Math.max(1, body.path("total_pages").asInt(1))));
        result.setTotal(body.path("total_results").asInt(items.size()));
        result.setLimit(items.size());
        return result;
    }

    private String defaultMediaType(String type, Map<String, String> filters) {
        if ("anime".equals(type)) {
            return allowedValue(filters, "kind", "tv", ANIME_MEDIA);
        }
        if ("variety".equals(type)) {
            return "tv";
        }
        if (type.startsWith("movie_") || type.endsWith("movie")) {
            return "movie";
        }
        if (type.startsWith("tv_") || type.endsWith("tv")) {
            return "tv";
        }
        return "";
    }

    private String remarks(String year, double score) {
        String rating = score > 0 ? String.format(Locale.ROOT, "%.1f", score) : "";
//        if (StringUtils.isNotBlank(year) && StringUtils.isNotBlank(rating)) {
//            return year + " · " + rating;
//        }
        return StringUtils.defaultIfBlank(rating, year);
    }

    private String apiKey() {
        return settingRepository.findById("tmdb_api_key")
                .map(Setting::getValue)
                .filter(StringUtils::isNotBlank)
                .orElse(TMDB_API_KEY);
    }

    private void addTmdbCategory(CategoryList result, String id, String name) {
        Category category = new Category();
        category.setType_id(TMDB_PREFIX + id);
        category.setType_name(name);
        category.setType_flag(0);
        result.getCategories().add(category);
    }

    private void addTmdbFilters(CategoryList result) {
        result.getFilters().put(TMDB_PREFIX + "trending", trendingFilters());
        List<Filter> movieRegion = List.of(filter("region", "地区", regionValues()));
        result.getFilters().put(TMDB_PREFIX + "movie_popular", movieRegion);
        result.getFilters().put(TMDB_PREFIX + "movie_top_rated", movieRegion);
        result.getFilters().put(TMDB_PREFIX + "movie_now_playing", movieRegion);
        result.getFilters().put(TMDB_PREFIX + "movie_upcoming", movieRegion);
        List<Filter> movieFilters = discoverFilters(true);
        List<Filter> tvFilters = discoverFilters(false);
        result.getFilters().put(TMDB_PREFIX + "discover_movie", movieFilters);
        result.getFilters().put(TMDB_PREFIX + "discover_tv", tvFilters);
        result.getFilters().put(TMDB_PREFIX + "anime", animeFilters());
        result.getFilters().put(TMDB_PREFIX + "variety", varietyFilters());
        result.getFilters().put(TMDB_PREFIX + "platform_tv", List.of(
                filter("content", "内容", values("电视剧", "drama", "综艺", "variety", "动漫", "anime")),
                filter("network", "播出平台", tvNetworkValues()),
                filter("sort_by", "排序", tvSortValues())
        ));
        result.getFilters().put(TMDB_PREFIX + "platform_movie", List.of(
                filter("provider", "流媒体", movieProviderValues()),
                filter("watch_region", "观看地区", watchRegionValues()),
                filter("sort_by", "排序", movieSortValues())
        ));
    }

    private List<Filter> trendingFilters() {
        return List.of(
                filter("mediaType", "范围", values("全部", "all", "电影", "movie", "剧集", "tv")),
                filter("time_window", "周期", values("本周", "week", "今日", "day"))
        );
    }

    private List<Filter> animeFilters() {
        return List.of(
                filter("sort_by", "排序", tvSortValues()),
                filter("anime_region", "地区", values("国漫", "CN", "日漫", "JP", "韩漫", "KR", "美漫", "US")),
                filter("kind", "内容", values("动画剧集", "tv", "动画电影", "movie")),
                filter("year", "年代", yearValues())
        );
    }

    private List<Filter> varietyFilters() {
        return List.of(
                filter("region", "地区", varietyRegionValues()),
                filter("list_type", "榜单", values(
                        "近期热播", "hot", "今日更新", "today", "明日预告", "tomorrow",
                        "五年热榜", "trend", "高分综艺", "top"
                ))
        );
    }

    private List<Filter> discoverFilters(boolean movie) {
        return List.of(
                filter("origin_group", "大区", originGroupValues()),
                filter("with_origin_country", "国家/地区", regionValues()),
                filter("sort_by", "排序", movie ? movieSortValues() : tvSortValues()),
                filter("with_genres", "类型", movie ? movieGenreValues() : tvGenreValues()),
                filter("year", "年代", yearValues()),
                filter("with_original_language", "原始语言", languageValues()),
                filter("vote_average.gte", "最低评分", values("不限评分", "", "6分以上", "6", "7分以上", "7", "8分以上", "8", "9分以上", "9")),
                filter("vote_count.gte", "评分人数", values("不限人数", "", "20票以上", "20", "50票以上", "50", "100票以上", "100", "500票以上", "500")),
                filter("runtime", movie ? "片长" : "单集片长", values("不限片长", "", "90分钟内", "short", "90-120分钟", "medium", "120分钟以上", "long"))
        );
    }

    private List<FilterValue> originGroupValues() {
        return values(
                "全部地区", "", "日韩", "JP|KR", "欧美", "US|GB|FR|DE|ES|IT|CA|AU",
                "国产", "CN|HK|TW", "港台", "HK|TW", "东南亚", "TH|SG|MY|PH|ID|VN",
                "亚太", "CN|HK|TW|JP|KR|TH|SG|MY|IN", "欧洲", "GB|DE|FR|IT|ES|SE|NO|DK|FI|NL|BE|CH|AT|IE",
                "拉美", "MX|AR|CO|CL|PE|VE"
        );
    }

    private List<FilterValue> regionValues() {
        return values(
                "全部地区", "", "中国大陆", "CN", "中国香港", "HK", "中国台湾", "TW",
                "日本", "JP", "韩国", "KR", "美国", "US", "英国", "GB", "法国", "FR",
                "德国", "DE", "西班牙", "ES", "意大利", "IT", "加拿大", "CA", "澳大利亚", "AU",
                "泰国", "TH", "新加坡", "SG", "马来西亚", "MY", "菲律宾", "PH",
                "印度尼西亚", "ID", "越南", "VN", "印度", "IN", "俄罗斯", "RU",
                "巴西", "BR", "墨西哥", "MX", "土耳其", "TR", "瑞典", "SE", "挪威", "NO",
                "丹麦", "DK", "芬兰", "FI", "荷兰", "NL", "比利时", "BE", "瑞士", "CH",
                "奥地利", "AT", "爱尔兰", "IE", "阿根廷", "AR", "哥伦比亚", "CO",
                "智利", "CL", "秘鲁", "PE", "委内瑞拉", "VE"
        );
    }

    private List<FilterValue> varietyRegionValues() {
        return values(
                "全球", "", "中国大陆", "CN", "韩国", "KR", "日本", "JP", "中国台湾", "TW",
                "中国香港", "HK", "欧美", "US|GB|DE|FR|IT|ES|CA|AU"
        );
    }

    private List<FilterValue> tvNetworkValues() {
        return values(
                "腾讯视频", "2007", "爱奇艺", "1330", "优酷", "1419", "芒果TV", "1631",
                "Bilibili", "1605", "Netflix", "213", "Disney+", "2739", "HBO", "49", "Apple TV+", "2552"
        );
    }

    private List<FilterValue> movieProviderValues() {
        return values(
                "Netflix", "8", "Disney+", "337", "Max", "1899",
                "Apple TV+", "350", "Amazon Prime", "9"
        );
    }

    private List<FilterValue> watchRegionValues() {
        return values(
                "美国", "US", "中国大陆", "CN", "中国香港", "HK", "中国台湾", "TW",
                "日本", "JP", "韩国", "KR", "英国", "GB", "加拿大", "CA", "澳大利亚", "AU"
        );
    }

    private List<FilterValue> movieGenreValues() {
        return values(
                "全部类型", "", "动作", "28", "冒险", "12", "动画", "16", "喜剧", "35",
                "犯罪", "80", "纪录片", "99", "剧情", "18", "家庭", "10751", "奇幻", "14",
                "历史", "36", "恐怖", "27", "音乐", "10402", "悬疑", "9648", "爱情", "10749",
                "科幻", "878", "惊悚", "53", "战争", "10752", "西部", "37"
        );
    }

    private List<FilterValue> tvGenreValues() {
        return values(
                "全部类型", "", "动作冒险", "10759", "动画", "16", "喜剧", "35", "犯罪", "80",
                "纪录片", "99", "剧情", "18", "家庭", "10751", "儿童", "10762", "悬疑", "9648",
                "真人秀", "10764", "科幻奇幻", "10765", "肥皂剧", "10766", "脱口秀", "10767",
                "战争政治", "10768", "西部", "37"
        );
    }

    private List<FilterValue> movieSortValues() {
        return values(
                "热度", "popularity.desc", "更新时间", "primary_release_date.desc",
                "评分", "vote_average.desc", "票房", "revenue.desc"
        );
    }

    private List<FilterValue> tvSortValues() {
        return values("热度", "popularity.desc", "更新时间", "first_air_date.desc", "评分", "vote_average.desc");
    }

    private List<FilterValue> languageValues() {
        return values(
                "全部语言", "", "中文", "zh", "日语", "ja", "韩语", "ko", "英语", "en",
                "法语", "fr", "德语", "de", "西班牙语", "es", "印地语", "hi", "泰语", "th"
        );
    }

    private List<FilterValue> yearValues() {
        List<FilterValue> values = new ArrayList<>();
        values.add(new FilterValue("全部年代", ""));
        for (int year = LocalDate.now().getYear() + 1; year >= 1980; year--) {
            String value = String.valueOf(year);
            values.add(new FilterValue(value, value));
        }
        return values;
    }

    private MovieList toNavigationList(MovieList source) {
        MovieList result = new MovieList();
        result.setPage(source.getPage());
        result.setPagecount(source.getPagecount());
        result.setLimit(source.getLimit());
        result.setTotal(source.getTotal());
        result.setHeader(source.getHeader());
        result.setList(source.getList().stream().map(this::toNavigationMovie).toList());
        return result;
    }

    private MovieDetail toNavigationMovie(MovieDetail source) {
        MovieDetail result = new MovieDetail();
        result.setVod_id(source.getVod_id());
        result.setVod_name(source.getVod_name());
        result.setVod_pic(source.getVod_pic());
        result.setVod_time(source.getVod_time());
        result.setVod_remarks(source.getVod_remarks());
        result.setType_name(source.getType_name());
        result.setVod_actor(source.getVod_actor());
        result.setVod_area(source.getVod_area());
        result.setVod_content(source.getVod_content());
        result.setVod_director(source.getVod_director());
        result.setVod_lang(source.getVod_lang());
        result.setVod_year(source.getVod_year());
        result.setValidity_state(source.getValidity_state());
        result.setValidity_summary(source.getValidity_summary());
        result.setDbid(source.getDbid());
        result.setType(source.getType());
        result.setSize(source.getSize());
        result.setExt(source.getExt());
        // TvBox checks folder state before indexs; navigation items must reach the index branch.
        result.setVod_tag(null);
        result.setCate(null);
        return result;
    }

    private Filter filter(String key, String name, List<FilterValue> values) {
        return new Filter(key, name, values);
    }

    private List<FilterValue> values(String... pairs) {
        List<FilterValue> values = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.add(new FilterValue(pairs[i], pairs[i + 1]));
        }
        return values;
    }

    private Category copyCategory(Category source) {
        Category category = new Category();
        category.setCover(source.getCover());
        category.setLand(source.getLand());
        category.setRatio(source.getRatio());
        category.setType_flag(source.getType_flag());
        return category;
    }

    private Integer parseYear(String year) {
        try {
            return StringUtils.isBlank(year) ? null : Integer.valueOf(year);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String valueOrDefault(Map<String, String> values, String key, String defaultValue) {
        return StringUtils.defaultIfBlank(values.get(key), defaultValue);
    }

    private String allowedValue(Map<String, String> values, String key, String defaultValue, Set<String> allowed) {
        String value = valueOrDefault(values, key, defaultValue);
        return allowed.contains(value) ? value : defaultValue;
    }

    private String cacheKey(String type, int page, int size, Map<String, String> filters) {
        String listType = filters.get("list_type");
        String date = "variety".equals(type) && ("today".equals(listType) || "tomorrow".equals(listType))
                ? "|" + LocalDate.now()
                : "";
        return type + '|' + page + '|' + size + '|' + new TreeMap<>(filters) + date;
    }

    private String firstNotBlank(String first, String second) {
        return StringUtils.defaultIfBlank(first, second);
    }

    private MovieList emptyList(int page, int size) {
        MovieList result = new MovieList();
        result.setPage(page);
        result.setLimit(size);
        result.setTotal(0);
        result.setPagecount(1);
        return result;
    }
}
