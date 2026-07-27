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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static cn.har01d.alist_tvbox.util.Constants.TMDB_API_KEY;

@Slf4j
@Service
public class PianDanService {
    static final String DOUBAN_PREFIX = "douban:";
    static final String TMDB_PREFIX = "tmdb:";
    private static final String TMDB_API = "https://api.themoviedb.org/3";
    private static final String TMDB_IMAGE = "https://image.tmdb.org/t/p/w500";

    private final TelegramService telegramService;
    private final SettingRepository settingRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PianDanService(TelegramService telegramService,
                          SettingRepository settingRepository,
                          RestTemplateBuilder builder,
                          ObjectMapper objectMapper) {
        this.telegramService = telegramService;
        this.settingRepository = settingRepository;
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
    }

    public CategoryList category() {
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
        addTmdbCategory(result, "tv_popular", "TMDB热门剧集");
        addTmdbCategory(result, "tv_top_rated", "TMDB高分剧集");
        addTmdbCategory(result, "tv_airing_today", "TMDB今日播出");
        addTmdbCategory(result, "discover_movie", "TMDB电影片库");
        addTmdbCategory(result, "discover_tv", "TMDB剧集片库");
        addTmdbFilters(result);

        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());
        return result;
    }

    public MovieList home() {
        List<MovieDetail> items = new ArrayList<>();
        try {
            MovieList douban = telegramService.listDouban("hot_movie", "", null, null, null, null, 1, 10);
            items.addAll(toNavigationList(douban).getList().stream().limit(10).toList());
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

    public MovieList list(String type, String ac, int page, int size, Map<String, String> filters) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        if (StringUtils.startsWith(type, DOUBAN_PREFIX)) {
            return toNavigationList(telegramService.listDouban(
                    type.substring(DOUBAN_PREFIX.length()),
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
        String path = tmdbPath(type, filters);
        if (path == null) {
            return emptyList(page, size);
        }

        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(TMDB_API + path)
                .queryParam("api_key", apiKey())
                .queryParam("language", "zh-CN")
                .queryParam("region", "CN")
                .queryParam("include_adult", false)
                .queryParam("page", page);
        addDiscoverFilters(uri, type, filters);

        try {
            String json = restTemplate.getForObject(uri.build().encode().toUriString(), String.class);
            JsonNode body = StringUtils.isBlank(json) ? null : objectMapper.readTree(json);
            return mapTmdb(body, type, page, size);
        } catch (RestClientException | JsonProcessingException e) {
            log.warn("load TMDB navigation list failed: {}", type, e);
            return emptyList(page, size);
        }
    }

    private String tmdbPath(String type, Map<String, String> filters) {
        return switch (type) {
            case "trending" -> "/trending/" + valueOrDefault(filters, "mediaType", "all") + "/"
                    + valueOrDefault(filters, "time_window", "week");
            case "movie_popular" -> "/movie/popular";
            case "movie_top_rated" -> "/movie/top_rated";
            case "movie_now_playing" -> "/movie/now_playing";
            case "tv_popular" -> "/tv/popular";
            case "tv_top_rated" -> "/tv/top_rated";
            case "tv_airing_today" -> "/tv/airing_today";
            case "discover_movie" -> "/discover/movie";
            case "discover_tv" -> "/discover/tv";
            default -> null;
        };
    }

    private void addDiscoverFilters(UriComponentsBuilder uri, String type, Map<String, String> filters) {
        if (!type.startsWith("discover_")) {
            return;
        }
        addQueryParam(uri, "sort_by", filters.get("sort_by"));
        addQueryParam(uri, "with_genres", filters.get("with_genres"));
        addQueryParam(uri, "with_origin_country", filters.get("with_origin_country"));
        String year = filters.get("year");
        addQueryParam(uri, type.endsWith("movie") ? "primary_release_year" : "first_air_date_year", year);
        if ("vote_average.desc".equals(filters.get("sort_by"))) {
            uri.queryParam("vote_count.gte", 200);
        }
    }

    private void addQueryParam(UriComponentsBuilder uri, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            uri.queryParam(key, value);
        }
    }

    private MovieList mapTmdb(JsonNode body, String categoryType, int page, int size) {
        if (body == null || !body.path("results").isArray()) {
            return emptyList(page, size);
        }
        List<MovieDetail> items = new ArrayList<>();
        for (JsonNode item : body.path("results")) {
            String mediaType = item.path("media_type").asText(defaultMediaType(categoryType));
            if (!"movie".equals(mediaType) && !"tv".equals(mediaType)) {
                continue;
            }
            String title = firstNotBlank(item.path("title").asText(""), item.path("name").asText(""));
            if (StringUtils.isBlank(title)) {
                continue;
            }
            String date = firstNotBlank(item.path("release_date").asText(""), item.path("first_air_date").asText(""));
            String year = date.length() >= 4 ? date.substring(0, 4) : "";
            double score = item.path("vote_average").asDouble(0);

            MovieDetail movie = new MovieDetail();
            movie.setVod_id(TMDB_PREFIX + mediaType + ":" + item.path("id").asText());
            movie.setVod_name(title);
            String poster = item.path("poster_path").asText("");
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
        result.setPagecount(Math.max(1, body.path("total_pages").asInt(1)));
        result.setTotal(body.path("total_results").asInt(items.size()));
        result.setLimit(items.size());
        return result;
    }

    private String defaultMediaType(String type) {
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
        if (StringUtils.isNotBlank(year) && StringUtils.isNotBlank(rating)) {
            return year + " · " + rating;
        }
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
        result.getFilters().put(TMDB_PREFIX + "trending", List.of(
                filter("mediaType", "范围", values("全部", "all", "电影", "movie", "剧集", "tv")),
                filter("time_window", "周期", values("本周", "week", "今日", "day"))
        ));
        List<Filter> movieFilters = discoverFilters(true);
        List<Filter> tvFilters = discoverFilters(false);
        result.getFilters().put(TMDB_PREFIX + "discover_movie", movieFilters);
        result.getFilters().put(TMDB_PREFIX + "discover_tv", tvFilters);
    }

    private List<Filter> discoverFilters(boolean movie) {
        List<FilterValue> sort = movie
                ? values("热度", "popularity.desc", "最新", "primary_release_date.desc", "评分", "vote_average.desc")
                : values("热度", "popularity.desc", "最新", "first_air_date.desc", "评分", "vote_average.desc");
        List<FilterValue> genre = movie
                ? values("全部", "", "剧情", "18", "喜剧", "35", "动作", "28", "科幻", "878", "动画", "16", "悬疑", "9648", "纪录", "99")
                : values("全部", "", "剧情", "18", "喜剧", "35", "动作冒险", "10759", "科幻奇幻", "10765", "动画", "16", "悬疑", "9648", "纪录", "99");
        return List.of(
                filter("sort_by", "排序", sort),
                filter("with_genres", "类型", genre),
                filter("with_origin_country", "地区", values("全部", "", "中国", "CN", "美国", "US", "日本", "JP", "韩国", "KR", "英国", "GB", "法国", "FR")),
                filter("year", "年份", yearValues())
        );
    }

    private List<FilterValue> yearValues() {
        List<FilterValue> values = new ArrayList<>();
        values.add(new FilterValue("全部", ""));
        int year = LocalDate.now().getYear();
        for (int i = 0; i < 20; i++) {
            String value = String.valueOf(year - i);
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
