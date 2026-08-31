package cn.har01d.alist_tvbox.service.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import cn.har01d.alist_tvbox.util.TextUtils;
import cn.har01d.alist_tvbox.util.Utils;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 豆瓣分季集数 → 全剧起始集号推断(资源级 startEpisode 的自动来源)。
 * <p>
 * 场景:元数据是全剧连续集号(TMDB 单季装全剧,线上:一念永恒 173/200 连续),而网盘资源
 * 按季打包(「完结季 4K 更新至08集」= 全剧第 153-160 集)—— 季内裸编号会冒领全剧第 1-8 集。
 * 豆瓣每季是独立条目且各带 episodes_count,按季号累加即得各季起始集号(S1 52 + S2 52 + S3 48
 * → S4 起始 153),正好补上 TMDB 单季条目缺的「季边界」信息。
 * <p>
 * 只推断<b>起始集号</b>,不落库:写入与观测门禁(平移后不超官方口径、裸编号确属季内形态)
 * 由调用方(MediaSubscriptionCheckService)把关 —— 门禁不过宁可不推,错位代价是静默错标全部集号。
 * <p>
 * 检索/匹配与 RatingBridge 的豆瓣桥同规:游客 suggest(滤非影视条目)+ 归一化整词同名
 * (候选剥季缀后与裸剧名相等)+ 第 1 季年份门禁 ±1(分季条目的年份是该季年份,前置季豁免);
 * 季号从候选标题「第N季/Sxx」解析,季标在 sub_title 时认 sub_title。命中与未命中各缓存 24h
 * (负缓存防巡检反复打外网),失败静默返 null 不炸巡检主链。
 */
@Slf4j
@Component
public class DoubanSeasonAligner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUGGEST_URL = "https://movie.douban.com/j/subject_suggest?q=";
    private static final String REXXAR_URL = "https://m.douban.com/rexxar/api/v2/tv/";

    /** 完结季/最终季类无季号标记:目标季 = 豆瓣分季条目里的最后一季。 */
    private static final Pattern FINALE_MARK = Pattern.compile("完结季|最终季|完结篇|大结局");

    /** 剧级完结标记(完结季/最终季):季包整体就是最终季,区别于篇/弧级的完结篇/大结局。 */
    private static final Pattern SERIES_FINALE_MARK = Pattern.compile("完结季|最终季");

    /** 标题是否带完结季类标记(无季号,季归属靠豆瓣分季条目推断)。 */
    public static boolean finaleMarked(String title) {
        return title != null && FINALE_MARK.matcher(title).find();
    }

    /** 标题是否带剧级完结标记 —— 季包整体就是最终季,包内 S01Exx 只是季内编号,可安全归位。 */
    public static boolean seriesFinaleMarked(String title) {
        return title != null && SERIES_FINALE_MARK.matcher(title).find();
    }

    private final RestTemplate restTemplate;
    /** 裸剧名 → 分季集数表(Optional.empty 负缓存 24h:搜不到/豆瓣无分季条目/失败)。 */
    private final Cache<String, Optional<Map<Integer, Integer>>> seasonsCache = Caffeine.newBuilder()
            .maximumSize(200).expireAfterWrite(Duration.ofHours(24)).build();
    /** 豆瓣条目 id → episodes_count(负缓存 24h)。 */
    private final Cache<String, Optional<Integer>> episodeCountCache = Caffeine.newBuilder()
            .maximumSize(500).expireAfterWrite(Duration.ofHours(24)).build();

    public DoubanSeasonAligner(MetadataHttp metadataHttp) {
        this.restTemplate = metadataHttp == null ? null : metadataHttp.create(); // null:单测桩不打网
    }

    /**
     * 推断资源标题对应季的全剧起始集号。
     *
     * @param seriesName    剧名(裸名,剥季缀)
     * @param firstYear     首播年份(S1 年份门禁用,可 null 放行)
     * @param resourceTitle 资源标题(声明目标季:第N季/Sxx/完结季)
     * @param officialAired 官方已播集数(完结季无豆瓣条目时判定目标季 = 已登记最后一季 + 1)
     * @return 起始集号(≥2);null = 无法推断(标题不声明季 / 豆瓣分季数据不全)
     */
    public Integer inferSeasonStart(String seriesName, Integer firstYear, String resourceTitle, Integer officialAired) {
        if (StringUtils.isBlank(seriesName) || StringUtils.isBlank(resourceTitle)) {
            return null;
        }
        Integer declared = TextUtils.parseTitleSeason(resourceTitle);
        boolean finale = FINALE_MARK.matcher(resourceTitle).find();
        if ((declared == null || declared <= 1) && !finale) {
            return null; // 标题不声明季(>1):无推断锚点
        }
        String bare = StringUtils.defaultIfBlank(TextUtils.stripSeasonSuffix(seriesName), seriesName).trim();
        Map<Integer, Integer> seasons = seasonsCache.get(bare, key -> fetchSeasonCounts(bare, firstYear))
                .orElse(null);
        if (seasons == null || seasons.isEmpty()) {
            return null;
        }
        int sum = sumSeasons(seasons, declared != null && declared > 1 ? declared : finaleTarget(seasons, officialAired));
        return sum >= 0 ? sum + 1 : null;
    }

    /**
     * 各季 → 全剧起始集号表(S1→1,S2→S1 集数+1,…)。多季合一包(S04E01 还带前 3 季)
     * 的文件级映射用:按文件各自 SxxEyy 的季逐个平移。搜不到/数据不全/失败返回 null。
     */
    public Map<Integer, Integer> seasonStarts(String seriesName, Integer firstYear) {
        if (StringUtils.isBlank(seriesName)) {
            return null;
        }
        String bare = StringUtils.defaultIfBlank(TextUtils.stripSeasonSuffix(seriesName), seriesName).trim();
        Map<Integer, Integer> counts = seasonsCache.get(bare, key -> fetchSeasonCounts(bare, firstYear))
                .orElse(null);
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

    /** 完结季目标季(公开给调用方定 SINGLE 季包的包季):豆瓣分季条目通常滞后,完结季资源
     常常还没条目 —— 已播数超出已登记各季之和说明最后一季未登记,目标 = 最后一季 + 1。
     与 seasonStarts 同缓存命中,不额外打外网。 */
    public Integer finaleSeason(String seriesName, Integer firstYear, Integer officialAired) {
        if (StringUtils.isBlank(seriesName)) {
            return null;
        }
        String bare = StringUtils.defaultIfBlank(TextUtils.stripSeasonSuffix(seriesName), seriesName).trim();
        Map<Integer, Integer> seasons = seasonsCache.get(bare, key -> fetchSeasonCounts(bare, firstYear))
                .orElse(null);
        if (seasons == null || seasons.isEmpty()) {
            return null;
        }
        return finaleTarget(seasons, officialAired);
    }

    /** 完结季目标季:豆瓣分季条目通常滞后,完结季资源常常还没条目 —— 已播数超出已登记各季之和
     说明最后一季未登记,目标 = 已登记最后一季 + 1;否则完结季 = 已登记最后一季。 */
    private static int finaleTarget(Map<Integer, Integer> seasons, Integer officialAired) {
        int last = java.util.Collections.max(seasons.keySet());
        int registered = seasons.values().stream().mapToInt(Integer::intValue).sum();
        if (officialAired != null && officialAired > registered) {
            return last + 1;
        }
        return last;
    }

    /** 前置季(1..target-1)集数累加;任一缺失返回 -1(宁可不推不错位)。 */
    private static int sumSeasons(Map<Integer, Integer> seasons, int target) {
        if (target <= 1) {
            return -1;
        }
        int sum = 0;
        for (int s = 1; s < target; s++) {
            Integer count = seasons.get(s);
            if (count == null || count <= 0) {
                return -1;
            }
            sum += count;
        }
        return sum;
    }

    /** 裸剧名 → {季号 → episodes_count}。suggest 候选剥季缀后须与裸剧名归一化整词相等。 */
    private Optional<Map<Integer, Integer>> fetchSeasonCounts(String bare, Integer firstYear) {
        try {
            List<DoubanCandidate> candidates = suggest(bare);
            if (candidates == null || candidates.isEmpty()) {
                return Optional.empty();
            }
            Map<Integer, Integer> seasons = new TreeMap<>();
            for (DoubanCandidate candidate : candidates) {
                Integer season = seasonOf(candidate, bare, firstYear);
                if (season == null) {
                    continue;
                }
                Integer count = episodeCountCache.get(candidate.id(), id -> fetchEpisodeCount(candidate.id()))
                        .orElse(null);
                if (count != null && count > 0) {
                    seasons.putIfAbsent(season, count);
                }
            }
            return seasons.isEmpty() ? Optional.empty() : Optional.of(seasons);
        } catch (Exception e) {
            log.debug("season aligner fetch failed: {} {}", bare, e.getMessage());
            return Optional.empty();
        }
    }

    /** 候选归本剧某季:剥季缀整词同名 + 季号(标题优先,季标在 sub_title 时认 sub_title);否则 null。 */
    private static Integer seasonOf(DoubanCandidate candidate, String bare, Integer firstYear) {
        Integer season = TextUtils.parseTitleSeason(candidate.title());
        if (season == null && StringUtils.isNotBlank(candidate.subTitle())) {
            season = TextUtils.parseTitleSeason(candidate.subTitle());
        }
        String stripped = TextUtils.stripSeasonSuffix(StringUtils.defaultString(candidate.title()));
        boolean sameShow = sameTitle(stripped, bare) || sameTitle(candidate.title(), bare);
        if (!sameShow) {
            return null;
        }
        if (season != null && season > 1) {
            return season; // 分季条目年份是该季年份,不做年份门禁
        }
        // 裸名条目 = S1:年份门禁 ±1 拦同名异剧(候选缺年份放行)
        if (firstYear != null) {
            Integer year = DoubanMetadataProvider.parseYear(candidate.year());
            if (year != null && Math.abs(year - firstYear) > 1) {
                return null;
            }
        }
        return season != null ? season : 1;
    }

    private static boolean sameTitle(String a, String b) {
        String na = DoubanMetadataProvider.normalizeTitle(a);
        return !na.isEmpty() && na.equals(DoubanMetadataProvider.normalizeTitle(b));
    }

    /** 豆瓣 suggest(游客可用,滤非影视条目);失败 null。单测可覆写。 */
    public List<DoubanCandidate> suggest(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return null;
        }
        JsonNode array = httpGetJson(SUGGEST_URL
                + java.net.URLEncoder.encode(keyword.trim(), java.nio.charset.StandardCharsets.UTF_8),
                "https://movie.douban.com/");
        if (array == null || !array.isArray()) {
            return null;
        }
        List<DoubanCandidate> result = new ArrayList<>();
        for (JsonNode item : array) {
            String type = item.path("type").asText("");
            if (!"movie".equalsIgnoreCase(type) && !"tv".equalsIgnoreCase(type)
                    && !"episode".equalsIgnoreCase(type)) {
                continue;
            }
            result.add(new DoubanCandidate(item.path("id").asText(""), item.path("title").asText(""),
                    item.path("sub_title").asText(""), item.path("year").asText("")));
        }
        return result;
    }

    /** rexxar tv 条目的 episodes_count;失败/非剧集条目返 Optional.empty。单测可覆写。 */
    public Optional<Integer> fetchEpisodeCount(String doubanId) {
        if (StringUtils.isBlank(doubanId)) {
            return Optional.empty();
        }
        JsonNode body = httpGetJson(REXXAR_URL + doubanId, "https://m.douban.com/");
        if (body == null || body.isMissingNode()) {
            return Optional.empty();
        }
        int count = body.path("episodes_count").asInt(0);
        return count > 0 ? Optional.of(count) : Optional.empty();
    }

    private JsonNode httpGetJson(String url, String referer) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.REFERER, referer);
            headers.set(HttpHeaders.USER_AGENT, Utils.getUserAgent());
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
            return StringUtils.isBlank(response.getBody()) ? null : MAPPER.readTree(response.getBody());
        } catch (Exception e) {
            log.debug("season aligner request failed: {} {}", url, e.getMessage());
            return null;
        }
    }

    public record DoubanCandidate(String id, String title, String subTitle, String year) {
    }
}
