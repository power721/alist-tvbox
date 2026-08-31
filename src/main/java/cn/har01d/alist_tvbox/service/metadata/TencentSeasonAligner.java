package cn.har01d.alist_tvbox.service.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import cn.har01d.alist_tvbox.util.TextUtils;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 腾讯视频分季集数 → 全剧起始集号推断(资源级 startEpisode / 季包映射的<b>首选</b>来源,
 * {@link DoubanSeasonAligner} 作兜底)。
 * <p>
 * 场景:元数据是全剧连续集号(TMDB 单季装全剧,线上:一念永恒),网盘资源按季打包 —— 腾讯
 * 官网把每季做成独立条目,且分季集数与绝对集号严格对齐(线上实测:S1=52 / S2=54(53-106)/
 * S3=59(107-165)/ 完结季起点 166,与 Bangumi 一致),比豆瓣的分季集数(完结季累推 153,
 * 漏登/错登常见)更准。同一页面就包含所有季和集数,无需逐季查条目。
 * <p>
 * 数据源:MbSearch 搜索(OfficialSiteMetadataProvider 同接口同头),取 dataType==2 且
 * 标题剥季缀后与裸剧名归一化相等的条目,season = parseTitleSeason(完结季 = 最大季 + 1),
 * 集数 = playSites[].totalEpisode。同名异剧防护:S1 条目年份与首播年差 &gt;2 拒;
 * 「合集篇/小剧场/预告」类衍生条目剥季缀后不等于裸剧名,自然出局。
 * <p>
 * 命中与未命中各缓存 24h(负缓存防巡检反复打外网),失败静默返 null 不炸巡检主链。
 */
@Slf4j
@Component
public class TencentSeasonAligner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEARCH_URL =
            "https://pbaccess.video.qq.com/trpc.videosearch.mobile_search.MultiTerminalSearch/MbSearch?vversion_platform=2";

    private final RestTemplate restTemplate;
    /** 裸剧名 → {季号 → 集数}(Optional.empty 负缓存 24h:搜不到/无本站条目/失败)。 */
    private final Cache<String, Optional<Map<Integer, Integer>>> seasonsCache = Caffeine.newBuilder()
            .maximumSize(200).expireAfterWrite(Duration.ofHours(24)).build();

    public TencentSeasonAligner(MetadataHttp metadataHttp) {
        // pbaccess 网关歧视 HttpURLConnection 连接层(20607),必须走 JDK HttpClient(HTTP/2)
        this.restTemplate = metadataHttp == null ? null : metadataHttp.createJdk(); // null:单测桩不打网
    }

    /**
     * 推断资源标题对应季的全剧起始集号(与 DoubanSeasonAligner.inferSeasonStart 同契约)。
     *
     * @return 起始集号(≥2);null = 无法推断(标题不声明季 / 腾讯无分季数据)
     */
    public Integer inferSeasonStart(String seriesName, Integer firstYear, String resourceTitle, Integer officialAired) {
        if (StringUtils.isBlank(seriesName) || StringUtils.isBlank(resourceTitle)) {
            return null;
        }
        Integer declared = TextUtils.parseTitleSeason(resourceTitle);
        boolean finale = DoubanSeasonAligner.finaleMarked(resourceTitle);
        if ((declared == null || declared <= 1) && !finale) {
            return null;
        }
        Map<Integer, Integer> starts = seasonStarts(seriesName, firstYear);
        if (starts == null) {
            return null;
        }
        Integer target = declared != null && declared > 1 ? declared : finaleSeason(seriesName, firstYear, officialAired);
        return target == null ? null : starts.get(target);
    }

    /** 各季 → 全剧起始集号表(S1→1,S2→S1 集数+1,…;完结季条目按最大季+1 归位)。 */
    public Map<Integer, Integer> seasonStarts(String seriesName, Integer firstYear) {
        Map<Integer, Integer> counts = seasonCounts(seriesName, firstYear);
        if (counts == null || counts.isEmpty()) {
            return null;
        }
        Map<Integer, Integer> starts = new TreeMap<>();
        int sum = 0;
        for (Map.Entry<Integer, Integer> entry : new TreeMap<>(counts).entrySet()) {
            starts.put(entry.getKey(), sum + 1);
            sum += entry.getValue();
        }
        return starts;
    }

    /** 完结季目标季:腾讯分季条目在线(完结季正在播,通常有条目),目标 = 已登记最大季;
     但腾讯也可能缺完结季条目 —— 已播数超出已登记各季之和时目标 = 最大季 + 1(与豆瓣兜底同口径,
     此时 seasonStarts 无该季起点,inferSeasonStart 返 null 自然回落豆瓣)。 */
    public Integer finaleSeason(String seriesName, Integer firstYear, Integer officialAired) {
        Map<Integer, Integer> counts = seasonCounts(seriesName, firstYear);
        if (counts == null || counts.isEmpty()) {
            return null;
        }
        int last = java.util.Collections.max(counts.keySet());
        if (officialAired != null) {
            int registered = counts.values().stream().mapToInt(Integer::intValue).sum();
            if (officialAired > registered) {
                return last + 1;
            }
        }
        return last;
    }

    /** 裸剧名 → {季号 → 集数}(腾讯官方分季集数,CheckService 覆盖 TMDB 已播/总数用)。
     * 搜索候选剥季缀后须与裸剧名归一化相等。 */
    public Map<Integer, Integer> seasonCounts(String seriesName, Integer firstYear) {
        if (StringUtils.isBlank(seriesName)) {
            return null; // restTemplate null(单测桩)不拦:search 已被覆写,异常路径自带兜底
        }
        String bare = StringUtils.defaultIfBlank(TextUtils.stripSeasonSuffix(seriesName), seriesName).trim();
        return seasonsCache.get(bare, key -> Optional.ofNullable(fetchSeasonCounts(bare, firstYear)))
                .orElse(null);
    }

    private Map<Integer, Integer> fetchSeasonCounts(String bare, Integer firstYear) {
        try {
            JsonNode data = search(bare);
            if (data == null || !data.isObject()) {
                return null;
            }
            List<JsonNode> items = new ArrayList<>();
            collectItems(data.path("normalList").path("itemList"), items);
            for (JsonNode box : data.path("areaBoxList")) {
                collectItems(box.path("itemList"), items);
            }
            Map<Integer, Integer> seasons = new TreeMap<>();
            for (JsonNode item : items) {
                if (item.path("doc").path("dataType").asInt(0) != 2) {
                    continue;
                }
                JsonNode videoInfo = item.path("videoInfo");
                String title = videoInfo.path("title").asText("").replaceAll("<[^>]+>", "");
                Integer season = seasonOf(title, bare, firstYear,
                        DoubanMetadataProvider.parseYear(String.valueOf(videoInfo.path("year").asInt(0))));
                if (season == null) {
                    continue;
                }
                int count = maxTotalEpisodes(videoInfo);
                if (count > 0) {
                    seasons.merge(season, count, Math::max);
                }
            }
            return seasons.isEmpty() ? null : placeFinale(seasons);
        } catch (Exception e) {
            log.debug("tencent season aligner fetch failed: {} {}", bare, e.getMessage());
            return null;
        }
    }

    /** 候选归本剧某季:剥季缀归一化同名 + 季号(第N季;裸名 = S1 且过首播年门禁 ±2;完结季 = -1 由调用方归位)。 */
    private static Integer seasonOf(String title, String bare, Integer firstYear, Integer itemYear) {
        String stripped = StringUtils.defaultIfBlank(TextUtils.stripSeasonSuffix(title), title);
        boolean finale = DoubanSeasonAligner.finaleMarked(title);
        // 「完结季」不在 stripSeasonSuffix 的季缀清单里,比较前先剥完结类标记
        String strippedFurther = finale ? stripped.replaceAll("完结季|最终季|完结篇|大结局", "").trim() : stripped;
        if (!sameTitle(stripped, bare) && !(finale && sameTitle(strippedFurther, bare))) {
            return null; // 「合集篇/小剧场/预告」等衍生条目剥季缀后不等名,自然出局
        }
        Integer season = TextUtils.parseTitleSeason(title);
        if (season != null && season > 1) {
            return season;
        }
        if (finale) {
            return -1; // 完结季:无季号,先登记,归位 = 最大季 + 1(fetchSeasonCounts 尾部统一处理)
        }
        if (season != null) {
            return season; // 标了第 1 季
        }
        if (firstYear != null && itemYear != null && Math.abs(itemYear - firstYear) > 2) {
            return null; // 裸名条目按首播年拦同名异剧(分季条目年份是该季年份,不做门禁)
        }
        return 1;
    }

    /** 完结季条目(season=-1 登记的)归位到最大季 + 1;腾讯分季条目在线,完结季正在播。 */
    private static Map<Integer, Integer> placeFinale(Map<Integer, Integer> seasons) {
        Integer finaleCount = seasons.remove(-1);
        if (finaleCount == null || seasons.isEmpty()) {
            return seasons;
        }
        int target = java.util.Collections.max(seasons.keySet()) + 1;
        seasons.merge(target, finaleCount, Math::max);
        return seasons;
    }

    /** 条目集数:各播放源 totalEpisode 取最大(以腾讯本站为准,其余源可能缺集)。 */
    private static int maxTotalEpisodes(JsonNode videoInfo) {
        int max = 0;
        for (String siteKey : List.of("playSites", "episodeSites")) {
            for (JsonNode site : videoInfo.path(siteKey)) {
                int total = site.path("totalEpisode").asInt(0);
                max = Math.max(max, total);
            }
        }
        return max;
    }

    private static boolean sameTitle(String a, String b) {
        String na = DoubanMetadataProvider.normalizeTitle(a);
        return !na.isEmpty() && na.equals(DoubanMetadataProvider.normalizeTitle(b));
    }

    private static void collectItems(JsonNode list, List<JsonNode> out) {
        if (list.isArray()) {
            list.forEach(out::add);
        }
    }

    /** MbSearch(OfficialSiteMetadataProvider 同接口同头);失败 null。单测可覆写。 */
    public JsonNode search(String keyword) {
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("version", "26022601");
            payload.put("clientType", 1);
            payload.put("query", keyword);
            payload.put("pagenum", 0);
            payload.put("pagesize", 30);
            payload.put("uuid", java.util.UUID.randomUUID().toString().toUpperCase());
            payload.put("retry", 0);
            payload.put("isPrefetch", true);
            payload.put("queryFrom", 0);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.ACCEPT, "application/json");
            headers.set(HttpHeaders.ORIGIN, "https://v.qq.com");
            headers.set(HttpHeaders.REFERER, "https://v.qq.com/");
            headers.set("trpc-trans-info", "{\"trpc-env\":\"\"}");
            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0");
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(SEARCH_URL), HttpMethod.POST, new HttpEntity<>(MAPPER.writeValueAsString(payload), headers),
                    String.class);
            return StringUtils.isBlank(response.getBody()) ? null : MAPPER.readTree(response.getBody()).path("data");
        } catch (Exception e) {
            log.debug("tencent season aligner search failed: {}", e.getMessage());
            return null;
        }
    }
}
