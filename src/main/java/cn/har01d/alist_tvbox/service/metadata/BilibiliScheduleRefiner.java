package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.EpisodeAirDate;
import cn.har01d.alist_tvbox.dto.EpisodeInfo;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.util.Constants;
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
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站排播时刻校正:豆瓣订阅桥接 TMDB 补日程后,air_date 只有日期,时刻统一按当日 20:00 填 ——
 * B站独播国创实际更新时刻各剧不同(凡人修仙传每周六 11:00,线上第 189 集 airTime 被填成
 * 8-29 20:00,与官网 md28223043 的 11:00 差 9 小时),时间轴展示/已播判定/nextAirTime 全部偏晚。
 * 按剧名搜 B站番剧(search.bilibili.com/bangumi SSR 页,游客可用;api 搜索接口游客被 412 风控),
 * 取官方分集(api.bilibili.com/pgc/view/web/season,游客可用)中已上线集(status=13)pub_time
 * 最近 8 集的时刻众数,把日程统一校正到该 HH:mm —— 只换时分,TMDB 日期不动(排播日与官方一致);
 * 未上线集(status=2)pub_time 是占位值(上一集上线后 +15 分钟),不参与规律;早年老集 status
 * 非 13 也不参与。定位/时刻各缓存 6h,标题归一化整词匹配(同名异剧/季标番外拦截),失败静默保留 20:00。
 */
@Slf4j
@Component
public class BilibiliScheduleRefiner {
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);
    private static final String SEARCH_URL = "https://search.bilibili.com/bangumi?keyword=";
    private static final String SEASON_URL = "https://api.bilibili.com/pgc/view/web/season?season_id=";
    /** 搜索结果卡:主链接 ss 号(「立即观看」按钮)+ 封面 img alt 标题(含 &lt;em&gt; 高亮标签) */
    private static final Pattern SS_LINK = Pattern.compile("href=\"https://www\\.bilibili\\.com/bangumi/play/(ss\\d+)\"");
    private static final Pattern CARD_TITLE = Pattern.compile("alt=\"([^\"]+)\"");
    private static final Pattern EM_TAG = Pattern.compile("</?em[^>]*>");
    /** B站 ep status=13 = 已上线正片(2 = 预告/未上线,pub_time 不可信) */
    private static final int EPISODE_STATUS_AIRED = 13;
    /** 规律样本:最近 8 个已上线集(早年时段不同的老集不稀释,加更特别篇被众数压掉) */
    private static final int RECENT_LIMIT = 8;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** season 事实:排播时刻众数 + 已上线最大集号(status=13 的 title 数字)。
     *  已播数只能用最大集号不能用计数 —— 老集转会员/下架后 status 会变(柯南 B站
     *  已上线到 1270,status=13 却只剩 523 条、首条 751),计数只反映"当前可看"。 */
    private record SeasonFacts(LocalTime clock, int maxAiredNumber) {}

    private final RestTemplate restTemplate;
    /** 剧名 → ss 号(Optional.empty 负缓存:搜不到/标题不匹配/请求失败) */
    private final Cache<String, Optional<String>> seasonCache = Caffeine.newBuilder()
            .maximumSize(200).expireAfterWrite(Duration.ofHours(6)).build();
    /** ss 号 → season 事实(Optional.empty 负缓存:无已上线集/请求失败) */
    private final Cache<String, Optional<SeasonFacts>> factsCache = Caffeine.newBuilder()
            .maximumSize(200).expireAfterWrite(Duration.ofHours(6)).build();

    public BilibiliScheduleRefiner(MetadataHttp metadataHttp) {
        this.restTemplate = metadataHttp.create();
    }

    /**
     * 详情里有分集日程(TMDB 桥接产出)时,把播出时刻校正为 B站官方排播 HH:mm;未命中/失败静默跳过。
     * 定位到 ss 号即把 bilibili 条目 id 登记进 externalIds(详情页 links 展开 B站官方链接 —— B站独播
     * 剧此前三源外链都没有它),时刻取不到不影响登记。
     *
     * @return true = 已按 B站官方时刻校正(调用方应让随后的平台排播桥让位:B站独播番剧的
     *         爱优腾协力位常滞后跟进,平台桥此刻会覆盖掉更权威的 B站时刻)
     */
    boolean refine(MetadataDetails details) {
        if (details == null || details.getEpisodes() == null || details.getEpisodes().isEmpty()
                || StringUtils.isBlank(details.getName())) {
            return false;
        }
        try {
            String ss = searchSeason(details.getName());
            if (ss == null) {
                return false;
            }
            recordSeasonId(details, ss);
            LocalTime clock = seasonClock(ss);
            if (clock == null) {
                return false;
            }
            applyScheduleClock(details, clock, System.currentTimeMillis());
            log.info("bili schedule refine: {} air time clock -> {}", details.getName(), clock);
            return true;
        } catch (Exception e) {
            log.debug("bili schedule refine {} failed: {}", details.getName(), e.getMessage());
            return false;
        }
    }

    /** B站条目 id(「ss148433」形态)进 externalIds:MediaSubscriptionService.appendMetaLink 展开成播放页链接。 */
    private static void recordSeasonId(MetadataDetails details, String ss) {
        Map<String, String> ids = details.getExternalIds();
        if (ids == null) {
            ids = new LinkedHashMap<>();
            details.setExternalIds(ids);
        }
        ids.putIfAbsent("bilibili", ss);
    }

    /** 搜索页 SSR 解析:media-card 块的 ss 链接 + 封面标题,与剧名(或剔季缀基名)归一化整词相等才收。 */
    String searchSeason(String title) {
        return seasonCache.get(title, key -> {
            try {
                // byte[] 收包 + UTF-8 解码:StringHttpMessageConverter 对无 charset 的 text/html
                // 默认 ISO-8859-1,中文标题会乱码导致匹配失败,不依赖响应头声明
                ResponseEntity<byte[]> response = restTemplate.exchange(
                        URI.create(SEARCH_URL + java.net.URLEncoder.encode(key.trim(), java.nio.charset.StandardCharsets.UTF_8)),
                        HttpMethod.GET, new HttpEntity<>(null, browserHeaders()), byte[].class);
                String html = response.getBody() == null ? null
                        : new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                if (html == null) {
                    return Optional.empty();
                }
                String base = DoubanMetadataProvider.normalizeTitle(DoubanMetadataProvider.stripSeasonMark(key));
                String full = DoubanMetadataProvider.normalizeTitle(key);
                for (String block : html.split("class=\"media-card")) {
                    Matcher ss = SS_LINK.matcher(block);
                    Matcher alt = CARD_TITLE.matcher(block);
                    if (!ss.find() || !alt.find()) {
                        continue;
                    }
                    String name = EM_TAG.matcher(HtmlUtils.htmlUnescape(alt.group(1))).replaceAll("").trim();
                    String normalized = DoubanMetadataProvider.normalizeTitle(name);
                    if (!normalized.isEmpty() && (normalized.equals(full) || normalized.equals(base))) {
                        return Optional.of(ss.group(1));
                    }
                }
            } catch (Exception e) {
                log.debug("bili search {} failed: {}", key, e.getMessage());
            }
            return Optional.empty();
        }).orElse(null);
    }

    /**
     * 官方已播集数校正:B站已上线最大集号与现值<b>取大</b> —— TMDB/豆瓣对超长连载滞后
     * (柯南 B站已上线 1270,TMDB 停在 1212/1210,官方"已播"落后现实 60 集,巡检的
     * 缺集触发/官方已播提示全跟着滞后);只增不减,不覆盖更快源的更大值;不依赖分集
     * 列表(episodes 为空的详情同样工作),定位到 ss 即顺带登记 B站条目外链。
     */
    boolean refineAiredCount(MetadataDetails details) {
        if (details == null || StringUtils.isBlank(details.getName())) {
            return false;
        }
        try {
            String ss = searchSeason(details.getName());
            if (ss == null) {
                return false;
            }
            recordSeasonId(details, ss);
            Integer maxAired = airedEpisodeNumber(ss);
            if (maxAired != null
                    && (details.getAiredEpisodes() == null || maxAired > details.getAiredEpisodes())) {
                log.info("bili aired count refine: {} aired episodes {} -> {}",
                        details.getName(), details.getAiredEpisodes(), maxAired);
                details.setAiredEpisodes(maxAired);
                return true;
            }
        } catch (Exception e) {
            log.debug("bili aired count refine {} failed: {}", details.getName(), e.getMessage());
        }
        return false;
    }

    /** B站已上线最大集号;无已上线集/请求失败返回 null(缓存 6h)。 */
    Integer airedEpisodeNumber(String ss) {
        SeasonFacts facts = seasonFacts(ss);
        return facts == null || facts.maxAiredNumber() <= 0 ? null : facts.maxAiredNumber();
    }

    /** B站正片 title 是集号字符串("1270");文字条目(特别篇等)解析失败返回 0 不参与。 */
    private static int parseEpisodeTitle(String title) {
        if (StringUtils.isBlank(title)) {
            return 0;
        }
        try {
            return Integer.parseInt(title.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 官方分集已上线集 pub_time(秒)最近 8 集的 HH:mm 众数(平手取更新)。 */
    LocalTime seasonClock(String ss) {
        SeasonFacts facts = seasonFacts(ss);
        return facts == null ? null : facts.clock();
    }

    private SeasonFacts seasonFacts(String ss) {
        return factsCache.get(ss, key -> {
            try {
                ResponseEntity<byte[]> response = restTemplate.exchange(
                        URI.create(SEASON_URL + key.substring(2)),
                        HttpMethod.GET, new HttpEntity<>(null, browserHeaders()), byte[].class);
                List<Long> times = new ArrayList<>();
                int maxAired = 0;
                for (JsonNode episode : MAPPER.readTree(new String(response.getBody(),
                                java.nio.charset.StandardCharsets.UTF_8)).path("result").path("episodes")) {
                    long pubTime = episode.path("pub_time").asLong(0);
                    if (episode.path("status").asInt() == EPISODE_STATUS_AIRED && pubTime > 0) {
                        times.add(pubTime * 1000);
                        maxAired = Math.max(maxAired, parseEpisodeTitle(episode.path("title").asText(null)));
                    }
                }
                LocalTime best = null;
                if (!times.isEmpty()) {
                    times.sort(Comparator.reverseOrder());
                    Map<LocalTime, Integer> counts = new LinkedHashMap<>();
                    for (int i = 0; i < Math.min(times.size(), RECENT_LIMIT); i++) {
                        counts.merge(Instant.ofEpochMilli(times.get(i)).atZone(ZONE).toLocalTime(), 1, Integer::sum);
                    }
                    int bestCount = 0;
                    for (Map.Entry<LocalTime, Integer> entry : counts.entrySet()) {
                        if (entry.getValue() > bestCount) {
                            best = entry.getKey();
                            bestCount = entry.getValue();
                        }
                    }
                }
                if (best == null && maxAired <= 0) {
                    return Optional.empty();
                }
                return Optional.of(new SeasonFacts(best, maxAired));
            } catch (Exception e) {
                log.debug("bili season {} failed: {}", key, e.getMessage());
            }
            return Optional.empty();
        }).orElse(null);
    }

    /**
     * 日程时刻校正:episodes/upcoming 统一换到官方 HH:mm(日期不动),airedEpisodes/nextAirTime
     * 按校正后时刻重数 —— 与 {@link TmdbMetadataProvider#applySeasonEpisodes} 的播出时刻判定同口径。
     */
    static void applyScheduleClock(MetadataDetails details, LocalTime clock, long now) {
        if (details.getEpisodes() != null) {
            int aired = 0;
            Long next = null;
            for (EpisodeInfo info : details.getEpisodes()) {
                info.setAirTime(rewriteClock(info.getAirTime(), clock));
                Long time = info.getAirTime();
                if (time == null) {
                    continue;
                }
                if (time <= now) {
                    aired++;
                } else if (next == null || time < next) {
                    next = time;
                }
            }
            details.setAiredEpisodes(aired);
            details.setNextAirTime(next);
        }
        if (details.getUpcoming() != null) {
            for (EpisodeAirDate date : details.getUpcoming()) {
                date.setAirTime(rewriteClock(date.getAirTime(), clock));
            }
        }
    }

    /** 同日同时刻:epoch ms 在北京时区下替换时分,日期/秒以下不动。 */
    static Long rewriteClock(Long epochMilli, LocalTime clock) {
        if (epochMilli == null) {
            return null;
        }
        return Instant.ofEpochMilli(epochMilli).atZone(ZONE).with(clock).toInstant().toEpochMilli();
    }

    private static HttpHeaders browserHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        headers.set(HttpHeaders.ACCEPT, "text/html,application/json");
        headers.set(HttpHeaders.REFERER, "https://www.bilibili.com/");
        return headers;
    }
}
