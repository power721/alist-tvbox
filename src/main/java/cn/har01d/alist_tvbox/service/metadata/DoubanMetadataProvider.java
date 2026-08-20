package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.entity.MovieRepository;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cn.har01d.alist_tvbox.util.Utils;

/**
 * 豆瓣:在线搜索 movie.douban.com/j/subject_suggest(atv-player 已验证的稳定 JSON 接口),
 * 本地 movie 表(豆瓣同步库)兜底与合并;详情尝试 rexxar 条目接口补集数(容错,失败仅本地字段)。
 */
@Slf4j
@Component
public class DoubanMetadataProvider implements MetadataProvider {
    public static final String NAME = "douban";
    private static final String SUGGEST_URL = "https://movie.douban.com/j/subject_suggest?q=";

    private final MovieRepository movieRepository;
    private final RestTemplate restTemplate;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private final Cache<String, MetadataDetails> detailsCache = Caffeine.newBuilder()
            .maximumSize(200).expireAfterWrite(Duration.ofHours(6)).build();
    private final Map<String, Instant> failures = new ConcurrentHashMap<>();

    private final MetadataHealth health;

    public DoubanMetadataProvider(MovieRepository movieRepository, MetadataHttp metadataHttp, MetadataHealth health) {
        this.movieRepository = movieRepository;
        this.restTemplate = metadataHttp.create();
        this.health = health;
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
        return detailsCache.get(id, key -> fetchDetails(key));
    }

    private MetadataDetails fetchDetails(String id) {
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
        return details;
    }
}
