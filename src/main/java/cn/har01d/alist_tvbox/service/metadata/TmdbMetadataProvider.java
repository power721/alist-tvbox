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

    public TmdbMetadataProvider(SettingRepository settingRepository, MetadataHttp metadataHttp) {
        this.settingRepository = settingRepository;
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
        if (StringUtils.isBlank(keyword)) {
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
        } catch (Exception e) {
            log.warn("tmdb search failed: {}", e.getMessage());
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
        try {
            JsonNode tv = get("https://api.themoviedb.org/3/tv/" + id
                    + "?api_key=" + apiKey() + "&language=zh-CN");
            if (tv == null) {
                return details;
            }
            details.setName(tv.path("name").asText());
            details.setYear(yearOf(tv.path("first_air_date").asText()));
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

            // 目标季集数与播出日程
            JsonNode seasonNode = get("https://api.themoviedb.org/3/tv/" + id + "/season/" + season
                    + "?api_key=" + apiKey() + "&language=zh-CN");
            if (seasonNode != null && seasonNode.has("episodes")) {
                LocalDate today = LocalDate.now(ZONE);
                int total = 0;
                int aired = 0;
                LocalDate nextAir = null;
                for (JsonNode episode : seasonNode.get("episodes")) {
                    total++;
                    LocalDate airDate = localDate(episode.path("air_date").asText());
                    if (airDate == null) {
                        continue;
                    }
                    if (!airDate.isAfter(today)) {
                        aired++;
                    } else if (nextAir == null || airDate.isBefore(nextAir)) {
                        nextAir = airDate;
                    }
                }
                details.setTotalEpisodes(total);
                details.setAiredEpisodes(aired);
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
        } catch (Exception e) {
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
