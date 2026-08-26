package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.util.Utils;
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

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 跨源评分桥接:各 provider 拉完详情后按剧名定位同剧条目,只补 ratings/externalIds
 * (详情页多源评分 + 条目外链,links 由 {@code MediaSubscriptionService.appendMetaLink} 展开),
 * 条目身份(名称/封面/日程/集数)仍以源 provider 为准。任一源订阅经桥接即得三源评分/外链:
 * <ul>
 * <li>TMDB 订阅:TMDB 对国产剧/国创动画投票覆盖差,此前只有孤零零一个 TMDB 分;</li>
 * <li>豆瓣订阅:名称桥接已带 TMDB,缺 Bangumi(国创动画同样有分);</li>
 * <li>Bangumi 订阅:缺豆瓣评分。</li>
 * </ul>
 * 两条评分路均免 cookie 免 key:豆瓣 suggest(游客可用)定位 subject id(只认 {@code type=episode},
 * id 要喂 rexxar tv 接口)→ rexxar rating.value(rexxar 无 cookie 可用,未开分 value=0 视为无分);
 * Bangumi api.bgm.tv 搜索结果自带 rating.score,无需二跳。匹配与「豆瓣名称桥接 TMDB」同规:
 * 归一化整词同名(匹配集=源中文名/原名/别名+剔季缀基名,候选含 sub_title/剔季缀;同名异剧/
 * 子串模仿者拦)+ 年份门禁(±1,候选缺年份放行;多季合一 TMDB 条目 TMDB 年份是 S1 首播年,
 * season≥2 放行)。原名搜不到再按剔季缀基名补搜一轮。命中与未命中各缓存 6h(负缓存防完播剧
 * 反复 refresh 打爆外网),失败静默不炸详情主链。
 *
 * <p>不依赖任何 MetadataProvider(直连接口,内联 Bangumi 搜索)—— 挂豆瓣/Bangumi 侧时
 * 若注入 provider 会与既有注入方向(Douban→Tmdb)成构造环。
 */
@Slf4j
@Component
public class RatingBridge {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUGGEST_URL = "https://movie.douban.com/j/subject_suggest?q=";
    private static final String REXXAR_URL = "https://m.douban.com/rexxar/api/v2/tv/";
    private static final String BANGUMI_SEARCH_URL = "https://api.bgm.tv/v0/search/subjects";

    /** 桥接产物:外部条目 id + 评分(未开分为 null —— 链接可给,分数不造;字符串形态与各 provider 的 ratings 一致)。 */
    record Rating(String id, String score) {
    }

    private record DoubanCandidate(String id, String title, String subTitle, String year) {
    }

    private record BangumiCandidate(String id, String name, String year, String score) {
    }

    private final RestTemplate restTemplate;
    /** 源条目(provider:id)→ 豆瓣评分(Optional.empty 负缓存:未命中/未开分/失败,6h 后重试)。 */
    private final Cache<String, Optional<Rating>> doubanCache = Caffeine.newBuilder()
            .maximumSize(300).expireAfterWrite(Duration.ofHours(6)).build();
    /** 源条目(provider:id)→ Bangumi 评分(同上负缓存)。 */
    private final Cache<String, Optional<Rating>> bangumiCache = Caffeine.newBuilder()
            .maximumSize(300).expireAfterWrite(Duration.ofHours(6)).build();

    public RatingBridge(MetadataHttp metadataHttp) {
        this.restTemplate = metadataHttp.create();
    }

    /** provider 详情尾部接入:补缺源评分与外链;源自身/已带该源外链时跳过对应搜索。 */
    void enrich(MetadataDetails details, int season) {
        if (details == null || StringUtils.isBlank(details.getName()) || StringUtils.isBlank(details.getId())) {
            return;
        }
        try {
            List<String> names = matchNames(details);
            Integer year = DoubanMetadataProvider.parseYear(details.getYear());
            // 缓存键带 season:年份门禁只在 season<2 生效 —— 同剧 S1/S2 订阅共享结论会把
            // S2(无门禁)命中的同名异剧 id 串给 S1(本应门禁拦截),反之亦然
            String cacheKey = details.getProvider() + ":" + details.getId() + ":" + season;
            if (!hasExternal(details, DoubanMetadataProvider.NAME)) {
                apply(details, DoubanMetadataProvider.NAME, doubanCache.get(cacheKey,
                        key -> searchDouban(details, season, names, year)));
            }
            if (!hasExternal(details, BangumiMetadataProvider.NAME)) {
                apply(details, BangumiMetadataProvider.NAME, bangumiCache.get(cacheKey,
                        key -> searchBangumi(details, season, names, year)));
            }
        } catch (Exception e) {
            log.debug("rating bridge {} failed: {}", details.getName(), e.getMessage());
        }
    }

    private static boolean hasExternal(MetadataDetails details, String provider) {
        return details.getExternalIds() != null && StringUtils.isNotBlank(details.getExternalIds().get(provider));
    }

    /** 匹配名集合:中文名/原名/别名(TMDB alternative_titles / 豆瓣又名),每项再含剔季缀基名。 */
    private static List<String> matchNames(MetadataDetails details) {
        List<String> names = new ArrayList<>();
        for (String raw : new String[]{details.getName(), details.getOriginalName()}) {
            if (StringUtils.isNotBlank(raw)) {
                names.add(raw);
                String base = DoubanMetadataProvider.stripSeasonMark(raw);
                if (StringUtils.isNotBlank(base) && !base.equals(raw)) {
                    names.add(base);
                }
            }
        }
        if (details.getAliases() != null) {
            names.addAll(details.getAliases());
        }
        return names;
    }

    private Optional<Rating> searchDouban(MetadataDetails details, int season, List<String> names, Integer year) {
        List<DoubanCandidate> matched = matchDouban(suggest(details.getName()), names);
        String base = baseQuery(details.getName());
        if (matched.isEmpty() && base != null) {
            matched = matchDouban(suggest(base), names); // 「诛仙 第四季」条目名 → 基名「诛仙」补搜
        }
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        if (year != null && season < 2) {
            // 单季条目年份必须对上(候选缺年份视为通过);全不沾 = 同名异剧,放弃
            List<DoubanCandidate> yearMatched = new ArrayList<>();
            for (DoubanCandidate item : matched) {
                Integer candidateYear = DoubanMetadataProvider.parseYear(item.year());
                if (candidateYear == null || Math.abs(candidateYear - year) <= 1) {
                    yearMatched.add(item);
                }
            }
            if (yearMatched.isEmpty()) {
                log.info("rating bridge skip douban {} ({}): same-name candidates share no year",
                        details.getName(), year);
                return Optional.empty();
            }
            matched = yearMatched;
        }
        DoubanCandidate best = matched.get(0); // suggest 相关性序,门禁后取首位
        JsonNode body = httpGetJson(REXXAR_URL + best.id(), "https://m.douban.com/");
        if (body == null || StringUtils.isBlank(body.path("title").asText())) {
            return Optional.empty(); // 接口失败/无此条目:负缓存,6h 后重试
        }
        double rating = body.path("rating").path("value").asDouble(0);
        log.info("rating bridge: {} ({} {}) -> douban {} [{}]", details.getName(),
                details.getProvider(), details.getId(), best.id(), rating > 0 ? rating : "unrated");
        return Optional.of(new Rating(best.id(), rating > 0 ? String.valueOf(rating) : null));
    }

    private Optional<Rating> searchBangumi(MetadataDetails details, int season, List<String> names, Integer year) {
        List<BangumiCandidate> matched = matchBangumi(bangumiSearch(details.getName()), names);
        String base = baseQuery(details.getName());
        if (matched.isEmpty() && base != null) {
            matched = matchBangumi(bangumiSearch(base), names);
        }
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        if (year != null && season < 2) {
            List<BangumiCandidate> yearMatched = new ArrayList<>();
            for (BangumiCandidate item : matched) {
                Integer candidateYear = DoubanMetadataProvider.parseYear(item.year());
                if (candidateYear == null || Math.abs(candidateYear - year) <= 1) {
                    yearMatched.add(item);
                }
            }
            if (yearMatched.isEmpty()) {
                return Optional.empty();
            }
            matched = yearMatched;
        }
        BangumiCandidate best = matched.get(0);
        double score = 0;
        try {
            score = Double.parseDouble(StringUtils.defaultIfBlank(best.score(), "0"));
        } catch (NumberFormatException ignored) {
            // 形态异常按无分处理
        }
        log.info("rating bridge: {} ({} {}) -> bangumi {} [{}]", details.getName(),
                details.getProvider(), details.getId(), best.id(), score > 0 ? best.score() : "unrated");
        return Optional.of(new Rating(best.id(), score > 0 ? best.score() : null));
    }

    /** 剔季缀基名作第二轮搜索词(与第一轮原名不同才值得补搜);无季标返回 null。 */
    private static String baseQuery(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        String base = DoubanMetadataProvider.stripSeasonMark(name.trim());
        return StringUtils.isNotBlank(base) && !base.equals(name.trim()) ? base : null;
    }

    private static List<DoubanCandidate> matchDouban(List<DoubanCandidate> candidates, List<String> names) {
        List<DoubanCandidate> matched = new ArrayList<>();
        if (candidates == null) {
            return matched;
        }
        for (DoubanCandidate item : candidates) {
            // 只认剧集条目:豆瓣 id 要喂给 rexxar tv 接口,movie 条目对不上路
            if (StringUtils.isBlank(item.id()) || !titleMatches(names, item.title(), item.subTitle())) {
                continue;
            }
            matched.add(item);
        }
        return matched;
    }

    private static List<BangumiCandidate> matchBangumi(List<BangumiCandidate> candidates, List<String> names) {
        List<BangumiCandidate> matched = new ArrayList<>();
        if (candidates == null) {
            return matched;
        }
        for (BangumiCandidate item : candidates) {
            if (StringUtils.isNotBlank(item.id()) && titleMatches(names, item.name(), null)) {
                matched.add(item);
            }
        }
        return matched;
    }

    /** 整词同名:候选(含副标题/剔季缀基名)与匹配名集合归一化后任一相等。 */
    private static boolean titleMatches(List<String> names, String candidate, String subTitle) {
        if (isSameTitle(names, candidate) || isSameTitle(names, subTitle)) {
            return true;
        }
        String base = StringUtils.defaultString(candidate);
        String stripped = DoubanMetadataProvider.stripSeasonMark(base);
        return StringUtils.isNotBlank(stripped) && !stripped.equals(base) && isSameTitle(names, stripped);
    }

    private static boolean isSameTitle(List<String> names, String candidate) {
        if (StringUtils.isBlank(candidate)) {
            return false;
        }
        String normalized = DoubanMetadataProvider.normalizeTitle(candidate);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String name : names) {
            if (normalized.equals(DoubanMetadataProvider.normalizeTitle(name))) {
                return true;
            }
        }
        return false;
    }

    private static void apply(MetadataDetails details, String source, Optional<Rating> hit) {
        if (hit.isEmpty()) {
            return;
        }
        if (StringUtils.isNotBlank(hit.get().score())) {
            Map<String, String> ratings = details.getRatings() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(details.getRatings());
            ratings.putIfAbsent(source, hit.get().score());
            details.setRatings(ratings);
        }
        Map<String, String> ids = details.getExternalIds() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(details.getExternalIds());
        ids.putIfAbsent(source, hit.get().id());
        details.setExternalIds(ids);
    }

    /** 豆瓣 suggest(游客可用,与 DoubanMetadataProvider.search 同接口):失败返回 null。 */
    private List<DoubanCandidate> suggest(String keyword) {
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
            // 豆瓣 suggest 把剧集/番剧统一归 movie 大类(实测凡人修仙传各季/盗妖行全 movie),
            // episode 类型极少见 —— 只滤 book/music 等非影视条目(同名原著小说常占搜索结果);
            // 电影条目误入由 rexxar tv 接口无此条目兜底丢弃
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

    /** Bangumi 搜索(游客可用,与 BangumiMetadataProvider.search 同接口):只取动画(2)/三次元真人(6),失败返回 null。 */
    private List<BangumiCandidate> bangumiSearch(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return null;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, "alist-tvbox (https://github.com/har01d/alist-tvbox)");
            headers.set(HttpHeaders.ACCEPT, "application/json");
            headers.setContentType(MediaType.APPLICATION_JSON);
            ObjectNode body = MAPPER.createObjectNode();
            body.put("keyword", keyword.trim());
            body.put("limit", 10);
            body.putObject("filter").putArray("type").add(2).add(6);
            ResponseEntity<String> response = restTemplate.exchange(URI.create(BANGUMI_SEARCH_URL), HttpMethod.POST,
                    new HttpEntity<>(MAPPER.writeValueAsString(body), headers), String.class);
            JsonNode root = StringUtils.isBlank(response.getBody()) ? null : MAPPER.readTree(response.getBody());
            if (root == null || !root.has("data")) {
                return null;
            }
            List<BangumiCandidate> result = new ArrayList<>();
            for (JsonNode item : root.get("data")) {
                // name_cn 常为空串(盗妖行形态,原名字段才是中文) —— asText(default) 节点存在但值为空不走 default,须显式回落
                String name = item.path("name_cn").asText("");
                if (StringUtils.isBlank(name)) {
                    name = item.path("name").asText("");
                }
                result.add(new BangumiCandidate(item.path("id").asText(), name,
                        item.path("date").asText(""), item.path("rating").path("score").asText("")));
            }
            return result;
        } catch (Exception e) {
            log.debug("rating bridge bangumi search failed: {}", e.getMessage());
            return null;
        }
    }

    /** String 收包再手动解析:与 RestTemplate 消息转换器组合解耦(Jackson2/Jackson3 均可)。 */
    private JsonNode httpGetJson(String url, String referer) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.REFERER, referer);
            headers.set(HttpHeaders.USER_AGENT, Utils.getUserAgent());
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
            return StringUtils.isBlank(response.getBody()) ? null : MAPPER.readTree(response.getBody());
        } catch (Exception e) {
            log.debug("rating bridge request failed: {} {}", url, e.getMessage());
            return null;
        }
    }
}
