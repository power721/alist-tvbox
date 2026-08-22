package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import cn.har01d.alist_tvbox.util.Constants;

/**
 * Bangumi(api.bgm.tv 公开接口,免 key):番剧/动画集数与播出日程最准(§4.8)。
 * 章节 API 区分正片(type=0)与已播(status=0),下集播出时间取最近未播章节日期。
 */
@Slf4j
@Component
public class BangumiMetadataProvider implements MetadataProvider {
    public static final String NAME = "bangumi";
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);

    private final RestTemplate restTemplate;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final MetadataHealth health;

    public BangumiMetadataProvider(MetadataHttp metadataHttp, MetadataHealth health) {
        this.restTemplate = metadataHttp.create();
        this.health = health;
    }
    private final Cache<String, MetadataDetails> detailsCache = Caffeine.newBuilder()
            .maximumSize(200).expireAfterWrite(Duration.ofHours(6)).build();

    @Override
    public String getName() {
        return NAME;
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "alist-tvbox (https://github.com/har01d/alist-tvbox)");
        headers.set(HttpHeaders.ACCEPT, "application/json");
        return headers;
    }

    @Override
    public List<MetadataSearchItem> search(String keyword) {
        List<MetadataSearchItem> result = new ArrayList<>();
        if (StringUtils.isBlank(keyword) || health.isOpen(NAME)) {
            return result;
        }
        try {
            HttpHeaders headers = headers();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ObjectNode body = MAPPER.createObjectNode();
            body.put("keyword", keyword.trim());
            body.put("limit", 10);
            // 只取动画(2)/三次元真人(6):否则小说等类型会占首位(如"凡人修仙传"首条是原著小说)
            body.putObject("filter").putArray("type").add(2).add(6);
            // String 收发:与消息转换器组合解耦,避免 Jackson2 ObjectNode 撞上 Jackson3 转换器
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create("https://api.bgm.tv/v0/search/subjects"), HttpMethod.POST,
                    new HttpEntity<>(MAPPER.writeValueAsString(body), headers), String.class);
            JsonNode root = response.getBody() == null ? null : MAPPER.readTree(response.getBody());
            if (root != null && root.has("data")) {
                for (JsonNode item : root.get("data")) {
                    MetadataSearchItem entry = new MetadataSearchItem();
                    entry.setProvider(NAME);
                    entry.setId(item.path("id").asText());
                    entry.setName(firstNonBlank(item.path("name_cn").asText(), item.path("name").asText()));
                    entry.setYear(item.path("date").asText(""));
                    if (entry.getYear() != null && entry.getYear().length() > 4) {
                        entry.setYear(entry.getYear().substring(0, 4));
                    }
                    entry.setScore(item.path("rating").path("score").asText(""));
                    result.add(entry);
                }
            }
            health.record(NAME, true);
        } catch (Exception e) {
            health.record(NAME, false);
            log.warn("bangumi search failed: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public MetadataDetails details(String id, Integer season) {
        return detailsCache.get(id, key -> fetchDetails(key));
    }

    @Override
    public MetadataDetails refreshDetails(String id, Integer season) {
        MetadataDetails details = fetchDetails(id);
        if (details != null) {
            detailsCache.put(id, details);
        }
        return details;
    }

    private MetadataDetails fetchDetails(String id) {
        MetadataDetails details = new MetadataDetails();
        details.setProvider(NAME);
        details.setId(id);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    URI.create("https://api.bgm.tv/v0/subjects/" + id), HttpMethod.GET,
                    new HttpEntity<>(null, headers()), JsonNode.class);
            JsonNode subject = response.getBody();
            if (subject == null) {
                return details;
            }
            details.setName(firstNonBlank(subject.path("name_cn").asText(), subject.path("name").asText()));
            details.setOverview(subject.path("summary").asText(""));
            double score = subject.path("rating").path("score").asDouble(0);
            if (score > 0) {
                details.setRating(String.valueOf(score));
            }
            details.setYear(subject.path("date").asText(""));
            if (details.getYear() != null && details.getYear().length() > 4) {
                details.setYear(details.getYear().substring(0, 4));
            }
            JsonNode image = subject.path("images");
            if (image.isObject()) {
                details.setCover(firstNonBlank(image.path("large").asText(), image.path("common").asText()));
            }
            List<String> aliases = new ArrayList<>();
            if (StringUtils.isNotBlank(subject.path("name").asText()) && !subject.path("name").asText().equals(details.getName())) {
                aliases.add(subject.path("name").asText());
            }
            details.setAliases(aliases);

            JsonNode episodes = MAPPER.readTree(restTemplate.exchange(
                    URI.create("https://api.bgm.tv/v0/subjects/" + id + "/episodes"), HttpMethod.GET,
                    new HttpEntity<>(null, headers()), String.class).getBody());
            if (episodes != null && episodes.isArray()) {
                LocalDate today = LocalDate.now(ZONE);
                int total = 0;
                int aired = 0;
                LocalDate nextAir = null;
                List<cn.har01d.alist_tvbox.dto.EpisodeAirDate> upcoming = new ArrayList<>();
                List<cn.har01d.alist_tvbox.dto.EpisodeInfo> episodeInfos = new ArrayList<>();
                for (JsonNode episode : episodes) {
                    if (episode.path("type").asInt(-1) != 0) {
                        continue; // 非正片(SP/OP/ED/trailer)不计
                    }
                    total++;
                    LocalDate airDate = localDate(episode.path("air_date").asText());
                    // 分集详情(媒体详情页):标题/播出日期,bangumi 分集无简介/剧照
                    cn.har01d.alist_tvbox.dto.EpisodeInfo info = new cn.har01d.alist_tvbox.dto.EpisodeInfo(
                            episode.path("ep").asInt(0),
                            firstNonBlank(episode.path("name_cn").asText(), episode.path("name").asText()),
                            airDate == null ? null : airDate.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli());
                    episodeInfos.add(info);
                    if (episode.path("status").asInt(-1) == 0) {
                        aired++;
                        continue;
                    }
                    if (airDate != null && airDate.isAfter(today)) {
                        if (nextAir == null || airDate.isBefore(nextAir)) {
                            nextAir = airDate;
                        }
                        if (upcoming.size() < 60) {
                            upcoming.add(new cn.har01d.alist_tvbox.dto.EpisodeAirDate(
                                    episode.path("ep").asInt(0),
                                    airDate.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli()));
                        }
                    }
                }
                details.setTotalEpisodes(total);
                details.setAiredEpisodes(aired);
                details.setUpcoming(upcoming);
                details.setEpisodes(episodeInfos);
                if (nextAir != null) {
                    details.setNextAirTime(nextAir.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli());
                    details.setStatus(MetadataDetails.STATUS_RETURNING);
                } else {
                    details.setStatus(total > 0 && aired >= total
                            ? MetadataDetails.STATUS_ENDED : MetadataDetails.STATUS_UNKNOWN);
                }
            }
            health.record(NAME, true);
        } catch (Exception e) {
            health.record(NAME, false);
            log.warn("bangumi details {} failed: {}", id, e.getMessage());
        }
        return details;
    }

    private static String firstNonBlank(String a, String b) {
        return StringUtils.isNotBlank(a) ? a : b;
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
