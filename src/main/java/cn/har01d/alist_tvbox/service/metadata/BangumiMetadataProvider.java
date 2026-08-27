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
 * 章节 API(/v0/episodes,注意不是 /v0/subjects/{id}/episodes —— 该路径 404,曾致线上分集全空
 * 且误触熔断)区分正片(type=0),已播/日程按播出时刻(airdate 当日 20:00)判定(与 TMDB 同口径,
 * v0 章节无 status 字段)。
 */
@Slf4j
@Component
public class BangumiMetadataProvider implements MetadataProvider {
    public static final String NAME = "bangumi";
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);

    private final RestTemplate restTemplate;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final MetadataHealth health;
    private final RatingBridge ratingBridge;
    private final PlayScheduleBridge playScheduleBridge;
    private final BilibiliScheduleRefiner biliScheduleRefiner;

    public BangumiMetadataProvider(MetadataHttp metadataHttp, MetadataHealth health, RatingBridge ratingBridge,
                                   PlayScheduleBridge playScheduleBridge, BilibiliScheduleRefiner biliScheduleRefiner) {
        this.restTemplate = metadataHttp.create();
        this.health = health;
        this.ratingBridge = ratingBridge;
        this.playScheduleBridge = playScheduleBridge;
        this.biliScheduleRefiner = biliScheduleRefiner;
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
                    double bgmScore = item.path("rating").path("score").asDouble(0);
                    entry.setScore(bgmScore > 0 ? String.valueOf(bgmScore) : null); // 未开分不显示 0.0
                    result.add(entry);
                }
            }
            health.record(NAME, true);
        } catch (Exception e) {
            health.record(NAME, false);
            log.warn("bangumi search failed: {}", e.getMessage());
            // 上抛给 MetadataService.searchReport 的 errors 映射(与 TMDB 同规):空表与失败调用方无从区分
            throw e instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(e);
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
                details.setRatings(new java.util.LinkedHashMap<>(java.util.Map.of(NAME, String.valueOf(score))));
            }
            details.setExternalIds(new java.util.LinkedHashMap<>(java.util.Map.of(NAME, id)));
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

            List<JsonNode> episodes = fetchEpisodePages(restTemplate, id);
            if (!episodes.isEmpty()) {
                applyEpisodes(details, episodes, System.currentTimeMillis());
            }
            health.record(NAME, true);
            if (ratingBridge != null) {
                ratingBridge.enrich(details, 1); // 补豆瓣评分/外链(bangumi 无季概念,按单季过年份门禁)
            }
            boolean biliClocked = false;
            if (biliScheduleRefiner != null) {
                // B站独播番剧实际更新时刻(如盗妖行 周二/四 9:00)校正默认 20:00,并登记 B站条目外链
                biliClocked = biliScheduleRefiner.refine(details);
                biliScheduleRefiner.refineAiredCount(details); // 官方已播集数:B站已上线最大集号取大(bgm 集数也常滞后)
            }
            if (playScheduleBridge != null && !biliClocked) {
                playScheduleBridge.refine(details); // 豆瓣桥接带出播放源后校正爱优腾实际排播时刻;B站已校正则让位
            }
        } catch (Exception e) {
            health.record(NAME, false);
            log.warn("bangumi details {} failed: {}", id, e.getMessage());
        }
        return details;
    }

    /** 页数上限:50 页 × 100 = 5000 行,与追剧分集展示 MAX_EPISODE_ROWS 同口径(超长连载兜底,不至拉无界)。 */
    private static final int EPISODE_PAGE_LIMIT = 50;

    /**
     * 章节 API 分页拉全(https://api.bgm.tv/v0/episodes,每页上限 100,翻到不满页即止):
     * 供本类与 {@link BangumiEpisodeBridge}(分集标题桥)共用。失败上抛,由调用方决定健康记录/静默。
     * 页数上限必须覆盖千集级连载 —— 旧上限 5 页把航海王(bgm 1191 集)截到 500,桥接据此把官方
     * 总/已播集数双双钉死 500,千集级真资源反被集号门禁当同名异剧拦截(线上订阅 48)。
     */
    static List<JsonNode> fetchEpisodePages(RestTemplate restTemplate, String subjectId) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (int page = 0; page < EPISODE_PAGE_LIMIT; page++) {
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create("https://api.bgm.tv/v0/episodes?subject_id=" + subjectId
                            + "&limit=100&offset=" + (page * 100)),
                    HttpMethod.GET, new HttpEntity<>(null, headers()), String.class);
            JsonNode data = StringUtils.isBlank(response.getBody()) ? null
                    : MAPPER.readTree(response.getBody()).path("data");
            if (!data.isArray()) {
                break;
            }
            data.forEach(result::add);
            if (data.size() < 100) {
                break;
            }
        }
        return result;
    }

    /**
     * 章节 → 总集数/已播/日程/分集详情。v0 章节无 status 字段,口径与
     * {@link TmdbMetadataProvider#applySeasonEpisodes} 对齐:已播按播出时刻(airdate 当日 20:00)
     * 判定 —— 当日待播集参与 nextAir(20:00 前刷新不漏当日集),昨日/今日已播仍进日程
     * (时间轴「昨天/今天」分组);airdate 未登记的章节只进分集详情不进统计(total 计数保持与列表行数一致)。
     */
    static void applyEpisodes(MetadataDetails details, List<JsonNode> episodes, long now) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate windowFrom = today.minusDays(1);
        int total = 0;
        int aired = 0;
        Long nextAir = null;
        List<cn.har01d.alist_tvbox.dto.EpisodeAirDate> upcoming = new ArrayList<>();
        List<cn.har01d.alist_tvbox.dto.EpisodeInfo> episodeInfos = new ArrayList<>();
        for (JsonNode episode : episodes) {
            if (episode.path("type").asInt(-1) != 0) {
                continue; // 非正片(SP/OP/ED/trailer)不计
            }
            int number = episode.path("ep").asInt(0);
            if (number <= 0) {
                number = episode.path("sort").asInt(0); // 未编号章节以 sort 计
            }
            total++;
            LocalDate airDate = localDate(episode.path("airdate").asText());
            Long airMoment = airDate == null ? null
                    : airDate.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli();
            // 分集详情(媒体详情页):标题/播出日期,bangumi 分集无简介/剧照
            episodeInfos.add(new cn.har01d.alist_tvbox.dto.EpisodeInfo(
                    number,
                    firstNonBlank(episode.path("name_cn").asText(), episode.path("name").asText()),
                    airMoment));
            if (airMoment == null) {
                continue;
            }
            if (airMoment <= now) {
                aired++;
                // 昨日/今日档期仍进日程(时间轴「昨天/今天」用),状态已翻转的已播集保留
                if (!airDate.isBefore(windowFrom) && upcoming.size() < 60) {
                    upcoming.add(new cn.har01d.alist_tvbox.dto.EpisodeAirDate(number, airMoment));
                }
            } else {
                if (nextAir == null || airMoment < nextAir) {
                    nextAir = airMoment;
                }
                if (upcoming.size() < 60) {
                    upcoming.add(new cn.har01d.alist_tvbox.dto.EpisodeAirDate(number, airMoment));
                }
            }
        }
        details.setTotalEpisodes(total);
        details.setAiredEpisodes(aired);
        details.setUpcoming(upcoming);
        details.setEpisodes(episodeInfos);
        details.setNextAirTime(nextAir);
        details.setStatus(nextAir != null ? MetadataDetails.STATUS_RETURNING
                : (total > 0 && aired >= total ? MetadataDetails.STATUS_ENDED : MetadataDetails.STATUS_UNKNOWN));
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
