package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.service.TmdbEndpoint;
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
    /** 电影条目 metaId 前缀:TMDB tv/movie id 是两个独立命名空间,订阅绑电影必须带前缀区分
     *  (裸 id 会被当剧集查 /3/tv/{id} 绑到无关条目)。 */
    public static final String MOVIE_ID_PREFIX = "movie:";
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);

    private final TmdbEndpoint tmdbEndpoint;
    private final RestTemplate restTemplate;
    private final Cache<String, MetadataDetails> detailsCache = Caffeine.newBuilder()
            .maximumSize(200).expireAfterWrite(Duration.ofHours(6)).build();

    private final MetadataHealth health;
    private final RatingBridge ratingBridge;
    private final PlayScheduleBridge playScheduleBridge;
    private final BilibiliScheduleRefiner biliScheduleRefiner;
    private final BangumiEpisodeBridge bangumiEpisodeBridge;

    public TmdbMetadataProvider(TmdbEndpoint tmdbEndpoint, MetadataHttp metadataHttp, MetadataHealth health,
                                RatingBridge ratingBridge, PlayScheduleBridge playScheduleBridge,
                                BilibiliScheduleRefiner biliScheduleRefiner, BangumiEpisodeBridge bangumiEpisodeBridge) {
        this.tmdbEndpoint = tmdbEndpoint;
        this.health = health;
        this.restTemplate = metadataHttp.create();
        this.ratingBridge = ratingBridge;
        this.playScheduleBridge = playScheduleBridge;
        this.biliScheduleRefiner = biliScheduleRefiner;
        this.bangumiEpisodeBridge = bangumiEpisodeBridge;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public List<MetadataSearchItem> search(String keyword) {
        List<MetadataSearchItem> result = new ArrayList<>();
        if (StringUtils.isBlank(keyword) || health.isOpen(NAME)) {
            return result;
        }
        try {
            String url = UriComponentsBuilder.fromUriString(tmdbEndpoint.apiHost() + "/3/search/tv")
                    .queryParam("query", keyword.trim())
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
                    entry.setScore(ratingOf(item.path("vote_average").asDouble(0))); // 未开分不显示 0.0
                    result.add(entry);
                }
            }
            health.record(NAME, true);
        } catch (Exception e) {
            health.record(NAME, false);
            log.warn("tmdb search failed: {}", e.getMessage());
            // 上抛给 MetadataService.searchReport 的 errors 映射(前端"为什么没有 TMDB 结果"的依据),
            // 不再吞掉返回空表 —— 空表与失败在调用方无从区分
            throw e instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(e);
        }
        return result;
    }

    @Override
    public MetadataDetails details(String id, Integer season) {
        if (health.isOpen(NAME)) {
            return null; // 熔断打开期间短路:别让每次调用都付满读超时(外网挂起时可拖 15s×4 请求)
        }
        int seasonNumber = season == null || season < 1 ? 1 : season;
        MetadataDetails details = detailsCache.get(id + ":" + seasonNumber, key -> fetchDetails(id, seasonNumber));
        return details != null && StringUtils.isNotBlank(details.getName()) ? details : null;
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
            JsonNode find = get(tmdbEndpoint.apiHost() + "/3/find/" + imdbId
                    + "?external_source=imdb_id&language=zh-CN");
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
        if (id.startsWith(MOVIE_ID_PREFIX)) {
            return fetchMovieDetails(id);
        }
        MetadataDetails details = new MetadataDetails();
        details.setProvider(NAME);
        details.setId(id);
        try {
            JsonNode tv = get(tmdbEndpoint.apiHost() + "/3/tv/" + id
                    + "?language=zh-CN&append_to_response=images");
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
                details.setBackdrop(BACKDROP_BASE + backdrop);
            }
            // 高清背景图候选(详情页轮播):append_to_response=images 随详情一次带回,零额外请求
            List<String> backdrops = bestBackdropUrls(tv);
            if (!backdrops.isEmpty()) {
                details.setBackdrops(backdrops);
                if (StringUtils.isBlank(details.getBackdrop())) {
                    details.setBackdrop(backdrops.get(0)); // 官方主图缺失时取最佳候选兜底
                }
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
            JsonNode alt = get(tmdbEndpoint.apiHost() + "/3/tv/" + id + "/alternative_titles");
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
            JsonNode credits = get(tmdbEndpoint.apiHost() + "/3/tv/" + id + "/credits"
                    + "?language=zh-CN");
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
            JsonNode seasonNode = get(tmdbEndpoint.apiHost() + "/3/tv/" + id + "/season/" + season
                    + "?language=zh-CN");
            if (seasonNode != null && seasonNode.has("episodes")) {
                applySeasonEpisodes(details, seasonNode, System.currentTimeMillis());
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
            if (ratingBridge != null) {
                ratingBridge.enrich(details, season); // 补豆瓣/Bangumi 评分与外链,失败自静默
            }
            if (bangumiEpisodeBridge != null) {
                // Bangumi 分集标题回填/补行(externalIds 已带 bangumi id):TMDB 中文标题「第 N 集」
                // 占位或滞后缺失,补入行落在时刻校正之前同享 HH:mm 校正
                bangumiEpisodeBridge.merge(details);
            }
            boolean biliClocked = false;
            if (biliScheduleRefiner != null) {
                // B站独播番剧实际更新时刻(如盗妖行 周二/四 9:00)校正默认 20:00,并登记 B站条目外链
                biliClocked = biliScheduleRefiner.refine(details);
                // 官方已播集数:B站已上线最大集号取大 —— TMDB 对超长连载滞后(柯南停在 1212/1210,B站已到 1270)
                biliScheduleRefiner.refineAiredCount(details);
            }
            if (playScheduleBridge != null && !biliClocked) {
                playScheduleBridge.refine(details); // 豆瓣桥接带出播放源后校正爱优腾实际排播时刻;B站已校正则让位(与豆瓣链同规)
            }
            // 全剧绝对集号形态的剧级信号采纳:必须在 bangumi/B站桥接之后(见方法注释的翻倍坑)
            adoptWholeShowSignals(details, tv, season);
        } catch (Exception e) {
            health.record(NAME, false);
            log.warn("tmdb details {} failed: {}", id, e.getMessage());
            return StringUtils.isBlank(details.getName()) ? null : details; // 空壳不缓存(detailsCache 对 null 不落位)
        }
        return details;
    }

    /**
     * TMDB 电影详情(metaId 形态 {@code movie:{id}}):电影按 1 个资源位建模 —— 总集数恒 1,
     * 已映(Released)=已播 1,收齐 1 个文件即与剧集 endedByCollectedAll 同路完结;
     * 未映=已播 0 且上映日进 nextAirTime/upcoming(待上映电影进播放时间轴)。
     * 分集导向的桥接(Bangumi/B站/平台排播)不参与,评分桥接照常补豆瓣互补分。
     */
    private MetadataDetails fetchMovieDetails(String metaId) {
        String id = metaId.substring(MOVIE_ID_PREFIX.length());
        MetadataDetails details = new MetadataDetails();
        details.setProvider(NAME);
        details.setId(metaId);
        try {
            JsonNode movie = get(tmdbEndpoint.apiHost() + "/3/movie/" + id
                    + "?language=zh-CN&append_to_response=images");
            if (movie == null) {
                return details;
            }
            details.setName(movie.path("title").asText());
            details.setOriginalName(movie.path("original_title").asText());
            String releaseDate = movie.path("release_date").asText("");
            details.setYear(yearOf(releaseDate));
            details.setFirstAirDate(releaseDate);
            details.setRating(ratingOf(movie.path("vote_average").asDouble(0)));
            if (details.getRating() != null) {
                details.setRatings(new java.util.LinkedHashMap<>(java.util.Map.of("tmdb", details.getRating())));
            }
            details.setExternalIds(new java.util.LinkedHashMap<>(java.util.Map.of("tmdb", id)));
            List<String> genres = new ArrayList<>();
            for (JsonNode genre : movie.path("genres")) {
                String name = genre.path("name").asText();
                if (StringUtils.isNotBlank(name)) {
                    genres.add(name);
                }
            }
            details.setGenres(genres);
            String language = movie.path("original_language").asText("");
            if (StringUtils.isNotBlank(language)) {
                details.setLanguages(List.of(language));
            }
            String backdrop = movie.path("backdrop_path").asText("");
            if (StringUtils.isNotBlank(backdrop)) {
                details.setBackdrop(BACKDROP_BASE + backdrop);
            }
            List<String> backdrops = bestBackdropUrls(movie);
            if (!backdrops.isEmpty()) {
                details.setBackdrops(backdrops);
                if (StringUtils.isBlank(details.getBackdrop())) {
                    details.setBackdrop(backdrops.get(0));
                }
            }
            String poster = movie.path("poster_path").asText("");
            if (StringUtils.isNotBlank(poster)) {
                details.setCover("https://media.themoviedb.org/t/p/w300_and_h450_bestv2" + poster);
            }
            details.setOverview(movie.path("overview").asText(""));
            int runtime = movie.path("runtime").asInt(0);
            if (runtime > 0) {
                details.setRuntimeMinutes(runtime);
            }
            boolean released = "Released".equals(movie.path("status").asText());
            details.setStatus(released ? MetadataDetails.STATUS_ENDED : MetadataDetails.STATUS_UNKNOWN);
            details.setTotalEpisodes(1);
            details.setAiredEpisodes(released ? 1 : 0);
            LocalDate airDate = localDate(releaseDate);
            if (!released && airDate != null) {
                long airMoment = airDate.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli();
                details.setNextAirTime(airMoment);
                details.setUpcoming(List.of(new cn.har01d.alist_tvbox.dto.EpisodeAirDate(1, airMoment)));
            }
            // 别名(标题归属匹配素材:海外电影分享常用英文原名/其他译名)
            JsonNode alt = get(tmdbEndpoint.apiHost() + "/3/movie/" + id + "/alternative_titles");
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
            // 演职人员:cast=主演,crew=导演/编剧
            JsonNode credits = get(tmdbEndpoint.apiHost() + "/3/movie/" + id + "/credits"
                    + "?language=zh-CN");
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
                List<String> writers = new ArrayList<>();
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
            health.record(NAME, true);
            if (ratingBridge != null) {
                ratingBridge.enrich(details, 1); // 补豆瓣评分与外链,失败自静默(电影无季,季参不参与)
            }
        } catch (Exception e) {
            health.record(NAME, false);
            log.warn("tmdb movie details {} failed: {}", id, e.getMessage());
            return StringUtils.isBlank(details.getName()) ? null : details;
        }
        return details;
    }

    /**
     * 季分集 → 总集数/已播/日程/分集详情。已播按播出时刻(air_date 当日 20:00,与 airTime 展示同口径)
     * 判定而非日期粒度:播出日当天 20:00 前刷新即把当日集算已播,点映礼 N 集同日上架的剧已播虚高
     * (28 被记成 33),连带把未上架集报成缺集徒劳补搜;已播集仍按昨日窗口进日程,时间轴
     * 「昨天/今天」分组不受影响。nextAirTime 严格取 20:00 未过的集。
     */
    static void applySeasonEpisodes(MetadataDetails details, JsonNode seasonNode, long now) {
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
                // air_date 未登记(TMDB 滞后常态):不参与已播/日程统计,但分集详情照收 ——
                // 否则 total 计了它而 episodes 缺它,详情页分集行数与总数不齐
                cn.har01d.alist_tvbox.dto.EpisodeInfo pending = new cn.har01d.alist_tvbox.dto.EpisodeInfo(
                        episode.path("episode_number").asInt(0),
                        firstNonBlank(episode.path("name").asText(""), ""),
                        null);
                pending.setOverview(episode.path("overview").asText(""));
                int pendingRuntime = episode.path("runtime").asInt(0);
                if (pendingRuntime > 0) {
                    pending.setRuntime(pendingRuntime);
                }
                String pendingStill = episode.path("still_path").asText("");
                if (StringUtils.isNotBlank(pendingStill)) {
                    pending.setStill("https://media.themoviedb.org/t/p/w300_and_h450_bestv2" + pendingStill);
                }
                episodeInfos.add(pending);
                continue;
            }
            long airMoment = airDate.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli();
            if (airMoment <= now) {
                aired++;
                if (!airDate.isBefore(windowFrom) && upcoming.size() < 60) {
                    upcoming.add(new cn.har01d.alist_tvbox.dto.EpisodeAirDate(
                            episode.path("episode_number").asInt(0), airMoment));
                }
            } else {
                if (nextAir == null || airDate.isBefore(nextAir)) {
                    nextAir = airDate;
                }
                if (upcoming.size() < 60) {
                    upcoming.add(new cn.har01d.alist_tvbox.dto.EpisodeAirDate(
                            episode.path("episode_number").asInt(0), airMoment));
                }
            }
            // 分集详情(媒体详情页):标题/播出日期/简介/剧照,与日程统计同源零额外请求
            cn.har01d.alist_tvbox.dto.EpisodeInfo info = new cn.har01d.alist_tvbox.dto.EpisodeInfo(
                    episode.path("episode_number").asInt(0),
                    firstNonBlank(episode.path("name").asText(""), ""),
                    airMoment);
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

    /**
     * 全剧绝对集号形态(海贼王/柯南):季只是篇章,episode_number 跨季连续,season 1 订阅=全剧订阅。
     * 判定:最近已播集的集号超出其所在季的注册集数 —— 分季编号剧的集号恒 ≤ 本季注册数
     * (末日地堡 S3E8 ≤ 8 不触发),绝对编号剧的集号是全剧位置必然越过(海贼王 S23 注册 26 集,
     * 已播 E1176)。命中后采纳剧级权威信号,只升不降:已播=最近已播集号、总数≥number_of_episodes、
     * 下集排播不再按季过滤(下集在任何季都与 S1 同处一个连续集号空间)。
     * <p>
     * 不修的后果(线上 海贼王 sub48 卡 ENDED):next_episode_to_air 按季过滤丢弃 → nextAirTime 恒空,
     * 已播/总数停在 bangumi 桥接的滞后登记值(1151/1155),本地集齐 1155 即被 endedByCollectedAll
     * 误判完结,且重开条件「官方已播 > 本地」被滞后口径反向堵死(本地 1174 > 官方 1151)永不重开。
     * <p>
     * 必须在 bangumi/B站桥接之后执行:bangumi 桥接对已播是逐行累加,先抬已播会被翻倍。
     */
    static void adoptWholeShowSignals(MetadataDetails details, JsonNode tv, int season) {
        if (season != 1) {
            return; // 分季订阅走季口径 + seasonStartEpisode 对齐,剧级信号混入会冒领别季集号
        }
        JsonNode last = tv.path("last_episode_to_air");
        int lastNumber = last.path("episode_number").asInt(0);
        int ownCount = seasonEpisodeCount(tv, last.path("season_number").asInt(-1));
        if (lastNumber <= 0 || ownCount <= 0 || lastNumber <= ownCount) {
            return; // 分季编号剧(集号不出本季 1..K 空间)或季注册缺失:维持季口径
        }
        if (lastNumber > (details.getAiredEpisodes() == null ? 0 : details.getAiredEpisodes())) {
            details.setAiredEpisodes(lastNumber);
        }
        int showTotal = tv.path("number_of_episodes").asInt(0);
        if (showTotal > (details.getTotalEpisodes() == null ? 0 : details.getTotalEpisodes())) {
            details.setTotalEpisodes(showTotal);
        }
        JsonNode next = tv.path("next_episode_to_air");
        LocalDate airDate = next.isObject() ? localDate(next.path("air_date").asText()) : null;
        if (airDate == null) {
            return;
        }
        long airMoment = airDate.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli();
        Long currentNext = details.getNextAirTime();
        if (currentNext == null || airMoment < currentNext) {
            details.setNextAirTime(airMoment);
        }
        int nextNumber = next.path("episode_number").asInt(0);
        if (nextNumber > 0 && details.getUpcoming() != null && details.getUpcoming().size() < 60
                && details.getUpcoming().stream().noneMatch(e -> e.getEpisode() == nextNumber)) {
            List<cn.har01d.alist_tvbox.dto.EpisodeAirDate> upcoming = new ArrayList<>(details.getUpcoming());
            upcoming.add(new cn.har01d.alist_tvbox.dto.EpisodeAirDate(nextNumber, airMoment));
            details.setUpcoming(upcoming); // 排播行进时间轴;去重防与季内路径重复
        }
    }

    private static int seasonEpisodeCount(JsonNode tv, int seasonNumber) {
        if (seasonNumber < 0) {
            return 0;
        }
        for (JsonNode s : tv.path("seasons")) {
            if (s.path("season_number").asInt(-1) == seasonNumber) {
                return s.path("episode_count").asInt(0);
            }
        }
        return 0;
    }

    /** 外网 GET:失败上抛(调用方 catch 记 health/降级)—— 吞掉返回 null 会让熔断器永远打不开,
     * 还会把网络故障当"无结果"记成 health 成功、清零失败计数。认证收口在 tmdbEndpoint(api key 拼 query / Bearer 走头)。 */
    private JsonNode get(String url) {
        try {
            // String 收包再手动解析:与消息转换器组合解耦(Jackson2/Jackson3 均可)
            HttpHeaders headers = tmdbEndpoint.applyAuth(jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(URI.create(tmdbEndpoint.appendApiKey(url)), HttpMethod.GET,
                    new HttpEntity<>(null, headers), String.class);
            return response.getBody() == null ? null : MAPPER.readTree(response.getBody());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return null; // 条目不存在是正常业务结果(如 IMDb 桥接 id 无对应剧集),不计健康失败
        } catch (Exception e) {
            throw new IllegalStateException("tmdb request failed: " + e.getMessage(), e);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json");
        return headers;
    }

    /**
     * TMDB 背景图床:w1280(预生成最大尺寸,1280×16:9)。original(常见 1920/3840 宽)动辄数 MB
     * 且走 web 端 /images 代理转发,详情横幅(抽屉 ~58% 视口宽)首屏加载明显拖慢;w1280 已超采样无清晰度损失。
     */
    static final String BACKDROP_BASE = "https://media.themoviedb.org/t/p/w1280";

    /**
     * 背景图候选(≤8 张,original 尺寸):官方主图恒置顶,其余按投票/票数/分辨率加成、
     * 16:9 偏差惩罚打分排序(atv-player 同款公式)。images 未随详情带回或为空时只含主图。
     */
    static List<String> bestBackdropUrls(JsonNode tv) {
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        String primary = tv.path("backdrop_path").asText("").trim();
        if (StringUtils.isNotBlank(primary)) {
            paths.add(primary);
        }
        List<JsonNode> candidates = new ArrayList<>();
        JsonNode backdrops = tv.path("images").path("backdrops");
        if (backdrops.isArray()) {
            for (JsonNode img : backdrops) {
                if (StringUtils.isNotBlank(img.path("file_path").asText(""))) {
                    candidates.add(img);
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(TmdbMetadataProvider::backdropScore).reversed());
        for (JsonNode img : candidates) {
            if (paths.size() >= 8) {
                break;
            }
            paths.add(img.path("file_path").asText("").trim());
        }
        return paths.stream().map(path -> BACKDROP_BASE + path).toList();
    }

    /** 投票优先,票数(封顶 1000)与宽度(封顶 4K)小幅加成,宽高比偏离 16:9 重罚。 */
    private static double backdropScore(JsonNode img) {
        double vote = img.path("vote_average").asDouble(0);
        double count = Math.min(img.path("vote_count").asDouble(0), 1000);
        double width = Math.min(img.path("width").asDouble(0), 3840);
        double height = img.path("height").asDouble(0);
        double ratio = width > 0 && height > 0 ? width / height : 16.0 / 9;
        return vote * 1000 + count * 2 + width / 20 - Math.abs(ratio - 16.0 / 9) * 140;
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
