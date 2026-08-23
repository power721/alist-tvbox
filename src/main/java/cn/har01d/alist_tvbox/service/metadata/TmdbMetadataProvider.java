package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * TMDB:海外剧权威。季集数/单集播出日期/续订状态/多语言别名;播出日程最全(§4.8)。
 * api key 复用 Setting tmdb_api_key(缺省内置公共 key)。
 */
@Slf4j
@Component
public class TmdbMetadataProvider implements MetadataProvider {
    public static final String NAME = "tmdb";
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);

    private final SettingRepository settingRepository;
    private final RestTemplate restTemplate;
    private final Cache<String, MetadataDetails> detailsCache = Caffeine.newBuilder()
            .maximumSize(200).expireAfterWrite(Duration.ofHours(6)).build();

    private final MetadataHealth health;

    public TmdbMetadataProvider(SettingRepository settingRepository, MetadataHttp metadataHttp, MetadataHealth health) {
        this.settingRepository = settingRepository;
        this.health = health;
        this.restTemplate = metadataHttp.create();
    }

    @Override
    public String getName() {
        return NAME;
    }

    private String apiKey() {
        return settingRepository.findById("tmdb_api_key").map(s -> s.getValue())
                .filter(StringUtils::isNotBlank).orElse(Constants.TMDB_API_KEY);
    }

    @Override
    public List<MetadataSearchItem> search(String keyword) {
        List<MetadataSearchItem> result = new ArrayList<>();
        if (StringUtils.isBlank(keyword) || health.isOpen(NAME)) {
            return result;
        }
        try {
            String url = UriComponentsBuilder.fromUriString("https://api.themoviedb.org/3/search/tv")
                    .queryParam("query", keyword.trim())
                    .queryParam("api_key", apiKey())
                    .queryParam("language", "zh-CN")
                    .build().encode().toUriString();
            JsonNode body = get(url);
            if (body != null && body.has("results")) {
                for (JsonNode item : body.get("results")) {
                    MetadataSearchItem entry = new MetadataSearchItem();
                    entry.setProvider(NAME);
                    entry.setId(item.path("id").asText());
                    entry.setName(firstNonBlank(item.path("name").asText(), item.path("original_name").asText()));
                    entry.setYear(yearOf(item.path("first_air_date").asText()));
                    String poster = item.path("poster_path").asText("");
                    if (StringUtils.isNotBlank(poster)) {
                        entry.setCover("https://media.themoviedb.org/t/p/w300_and_h450_bestv2" + poster);
                    }
                    entry.setScore(item.path("vote_average").asText(""));
                    result.add(entry);
                }
            }
            health.record(NAME, true);
        } catch (Exception e) {
            health.record(NAME, false);
            log.warn("tmdb search failed: {}", e.getMessage());
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
            detailsCache.put(id + ":" + seasonNumber, details); // 新值占位,后续 details() 不再吃旧缓存
        }
        return details;
    }

    /** IMDb id 定位 TMDB 剧集并复用全量详情(豆瓣详情页 IMDb 桥接:分集播出日程/状态/别名);未命中返回 null。 */
    public MetadataDetails detailsByImdb(String imdbId, Integer season) {
        if (StringUtils.isBlank(imdbId) || health.isOpen(NAME)) {
            return null;
        }
        try {
            JsonNode find = get("https://api.themoviedb.org/3/find/" + imdbId
                    + "?api_key=" + apiKey() + "&external_source=imdb_id&language=zh-CN");
            JsonNode tv = find == null ? null : find.path("tv_results").path(0);
            if (!tv.isObject() || !tv.hasNonNull("id")) {
                return null;
            }
            health.record(NAME, true);
            return details(tv.get("id").asText(), season);
        } catch (Exception e) {
            health.record(NAME, false);
            log.debug("tmdb find by imdb {} failed: {}", imdbId, e.getMessage());
            return null;
        }
    }

    private MetadataDetails fetchDetails(String id, int season) {
        MetadataDetails details = new MetadataDetails();
        details.setProvider(NAME);
        details.setId(id);
        try {
            JsonNode tv = get("https://api.themoviedb.org/3/tv/" + id
                    + "?api_key=" + apiKey() + "&language=zh-CN");
            if (tv == null) {
                return details;
            }
            details.setName(tv.path("name").asText());
            details.setOriginalName(tv.path("original_name").asText());
            details.setYear(yearOf(tv.path("first_air_date").asText()));
            details.setFirstAirDate(tv.path("first_air_date").asText(""));
            details.setRating(ratingOf(tv.path("vote_average").asDouble(0)));
            if (details.getRating() != null) {
                details.setRatings(new java.util.LinkedHashMap<>(java.util.Map.of("tmdb", details.getRating())));
            }
            details.setExternalIds(new java.util.LinkedHashMap<>(java.util.Map.of("tmdb", id)));
            List<String> genres = new ArrayList<>();
            if (tv.has("genres") && tv.get("genres").isArray()) {
                for (JsonNode genre : tv.get("genres")) {
                    String name = genre.path("name").asText();
                    if (StringUtils.isNotBlank(name)) {
                        genres.add(name);
                    }
                }
            }
            details.setGenres(genres);
            List<String> countries = new ArrayList<>();
            if (tv.has("origin_country") && tv.get("origin_country").isArray()) {
                for (JsonNode country : tv.get("origin_country")) {
                    countries.add(country.asText());
                }
            }
            details.setCountries(countries);
            String language = tv.path("original_language").asText("");
            if (StringUtils.isNotBlank(language)) {
                details.setLanguages(List.of(language));
            }
            // 主创(created_by =剧集创作者,编剧/导演混列;细分职务在 credits)
            if (tv.has("created_by") && tv.get("created_by").isArray()) {
                List<String> creators = new ArrayList<>();
                for (JsonNode person : tv.get("created_by")) {
                    String name = person.path("name").asText();
                    if (StringUtils.isNotBlank(name)) {
                        creators.add(name);
                    }
                }
                if (!creators.isEmpty()) {
                    details.setWriters(creators);
                }
            }
            String backdrop = tv.path("backdrop_path").asText("");
            if (StringUtils.isNotBlank(backdrop)) {
                details.setBackdrop("https://media.themoviedb.org/t/p/w780" + backdrop);
            }
            String poster = tv.path("poster_path").asText("");
            if (StringUtils.isNotBlank(poster)) {
                details.setCover("https://media.themoviedb.org/t/p/w300_and_h450_bestv2" + poster);
            }
            String status = tv.path("status").asText();
            if ("Ended".equals(status) || "Canceled".equals(status)) {
                details.setStatus(MetadataDetails.STATUS_ENDED);
            } else if ("Returning Series".equals(status)) {
                details.setStatus(MetadataDetails.STATUS_RETURNING);
            } else {
                details.setStatus(MetadataDetails.STATUS_UNKNOWN);
            }
            details.setTotalSeasons(tv.path("number_of_seasons").asInt(0));
            details.setOverview(tv.path("overview").asText(""));
            if (tv.has("episode_run_time") && tv.get("episode_run_time").isArray()
                    && !tv.get("episode_run_time").isEmpty()) {
                details.setRuntimeMinutes(tv.get("episode_run_time").get(0).asInt());
            }
            // 别名(搜索关键词扩展用)
            JsonNode alt = get("https://api.themoviedb.org/3/tv/" + id + "/alternative_titles?api_key=" + apiKey());
            List<String> aliases = new ArrayList<>();
            if (alt != null && alt.has("results")) {
                for (JsonNode item : alt.get("results")) {
                    String title = item.path("title").asText();
                    if (StringUtils.isNotBlank(title)) {
                        aliases.add(title);
                    }
                }
            }
            details.setAliases(aliases);

            // 演职人员(详情页演员卡):cast=主演饰演角色,crew=导演/编剧职务
            JsonNode credits = get("https://api.themoviedb.org/3/tv/" + id + "/credits?api_key=" + apiKey()
                    + "&language=zh-CN");
            if (credits != null) {
                List<cn.har01d.alist_tvbox.dto.CastMember> cast = new ArrayList<>();
                if (credits.has("cast") && credits.get("cast").isArray()) {
                    for (JsonNode person : credits.get("cast")) {
                        if (cast.size() >= 15) {
                            break;
                        }
                        String name = person.path("name").asText();
                        if (StringUtils.isBlank(name)) {
                            continue;
                        }
                        String profile = person.path("profile_path").asText("");
                        cast.add(new cn.har01d.alist_tvbox.dto.CastMember(name,
                                person.path("character").asText(""),
                                StringUtils.isNotBlank(profile)
                                        ? "https://media.themoviedb.org/t/p/w185" + profile : null));
                    }
                }
                details.setCast(cast);
                List<String> directors = new ArrayList<>();
                List<String> writers = new ArrayList<>(details.getWriters() == null ? List.of() : details.getWriters());
                if (credits.has("crew") && credits.get("crew").isArray()) {
                    for (JsonNode person : credits.get("crew")) {
                        String name = person.path("name").asText();
                        if (StringUtils.isBlank(name)) {
                            continue;
                        }
                        String job = person.path("job").asText("");
                        if ("Director".equals(job) && directors.size() < 5) {
                            directors.add(name);
                        } else if ("Writer".equals(job) && writers.size() < 8) {
                            writers.add(name);
                        }
                    }
                }
                details.setDirectors(directors.isEmpty() ? null : directors);
                if (!writers.isEmpty()) {
                    details.setWriters(writers);
                }
            }

            // 目标季集数与播出日程
            JsonNode seasonNode = get("https://api.themoviedb.org/3/tv/" + id + "/season/" + season
                    + "?api_key=" + apiKey() + "&language=zh-CN");
            if (seasonNode != null && seasonNode.has("episodes")) {
                LocalDate today = LocalDate.now(ZONE);
                LocalDate windowFrom = today.minusDays(1); // 昨日/今日已播仍进日程:时间轴「昨天/今天」分组靠它,只收严格未来会把刚播出的集洗掉
                int total = 0;
                int aired = 0;
                LocalDate nextAir = null;
                List<cn.har01d.alist_tvbox.dto.EpisodeAirDate> upcoming = new ArrayList<>();
                List<cn.har01d.alist_tvbox.dto.EpisodeInfo> episodeInfos = new ArrayList<>();
                for (JsonNode episode : seasonNode.get("episodes")) {
                    total++;
                    LocalDate airDate = localDate(episode.path("air_date").asText());
                    if (airDate == null) {
                        continue;
                    }
                    if (!airDate.isAfter(today)) {
                        aired++;
                        if (!airDate.isBefore(windowFrom) && upcoming.size() < 60) {
                            upcoming.add(new cn.har01d.alist_tvbox.dto.EpisodeAirDate(
                                    episode.path("episode_number").asInt(0),
                                    airDate.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli()));
                        }
                    } else {
                        if (nextAir == null || airDate.isBefore(nextAir)) {
                            nextAir = airDate;
                        }
                        if (upcoming.size() < 60) {
                            upcoming.add(new cn.har01d.alist_tvbox.dto.EpisodeAirDate(
                                    episode.path("episode_number").asInt(0),
                                    airDate.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli()));
                        }
                    }
                    // 分集详情(媒体详情页):标题/播出日期/简介/剧照,与日程统计同源零额外请求
                    cn.har01d.alist_tvbox.dto.EpisodeInfo info = new cn.har01d.alist_tvbox.dto.EpisodeInfo(
                            episode.path("episode_number").asInt(0),
                            firstNonBlank(episode.path("name").asText(""), ""),
                            airDate.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli());
                    info.setOverview(episode.path("overview").asText(""));
                    int runtime = episode.path("runtime").asInt(0);
                    if (runtime > 0) {
                        info.setRuntime(runtime);
                    }
                    String still = episode.path("still_path").asText("");
                    if (StringUtils.isNotBlank(still)) {
                        info.setStill("https://media.themoviedb.org/t/p/w300_and_h450_bestv2" + still);
                    }
                    episodeInfos.add(info);
                }
                details.setTotalEpisodes(total);
                details.setAiredEpisodes(aired);
                details.setUpcoming(upcoming);
                details.setEpisodes(episodeInfos);
                if (nextAir != null) {
                    details.setNextAirTime(nextAir.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli());
                }
            }
            // next_episode_to_air 更精确(含具体集与时间),仅当属于目标季
            JsonNode next = tv.path("next_episode_to_air");
            if (next.isObject() && next.path("season_number").asInt(-1) == season
                    && StringUtils.isNotBlank(next.path("air_date").asText())) {
                LocalDate airDate = localDate(next.get("air_date").asText());
                if (airDate != null) {
                    details.setNextAirTime(airDate.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli());
                }
            }
            health.record(NAME, true);
        } catch (Exception e) {
            health.record(NAME, false);
            log.warn("tmdb details {} failed: {}", id, e.getMessage());
        }
        return details;
    }

    private JsonNode get(String url) {
        try {
            // String 收包再手动解析:与消息转换器组合解耦(Jackson2/Jackson3 均可)
            ResponseEntity<String> response = restTemplate.exchange(URI.create(url), HttpMethod.GET,
                    new HttpEntity<>(null, jsonHeaders()), String.class);
            return response.getBody() == null ? null : MAPPER.readTree(response.getBody());
        } catch (Exception e) {
            log.debug("tmdb request failed: {} {}", url, e.getMessage());
            return null;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json");
        return headers;
    }

    private static String firstNonBlank(String a, String b) {
        return StringUtils.isNotBlank(a) ? a : b;
    }

    private static String yearOf(String date) {
        return StringUtils.isBlank(date) ? "" : date.substring(0, Math.min(4, date.length()));
    }

    /** TMDB 评分为 10 分制小数,一位小数展示。 */
    private static String ratingOf(double vote) {
        return vote > 0 ? String.format("%.1f", vote) : null;
    }

    private static LocalDate localDate(String date) {
        if (StringUtils.isBlank(date)) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (Exception e) {
            return null;
        }
    }
}
