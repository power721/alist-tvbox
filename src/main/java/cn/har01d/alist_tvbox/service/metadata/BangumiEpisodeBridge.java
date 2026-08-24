package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.EpisodeAirDate;
import cn.har01d.alist_tvbox.dto.EpisodeInfo;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Bangumi 分集标题桥接:provider 详情尾部(ratingBridge 之后,externalIds 已带 bangumi id)补分集标题。
 * TMDB 中文分集标题常为「第 N 集」占位或滞后缺失:盗妖行(tmdb 315088)60 集播出日程齐全、标题全是
 * 占位,Bangumi 同条目(subject 608049)首播日起就有全量真实标题 + 全季播出日期。桥接只做两件事:
 * <ul>
 * <li>占位/空标题回填:非占位标题不覆盖 —— TMDB 有真实标题时它仍是更权威的源;</li>
 * <li>集号超出源列表上界的分集整行补齐:TMDB 滞后未建行而 Bangumi 已排播到收官,播出日期按当日
 * 20:00 约定落位,totalEpisodes/airedEpisodes/upcoming/nextAirTime 随之延展。播出时刻的 HH:mm
 * 校正(B站 refiner/平台排播桥)挂在本桥之后,补入行同享校正。</li>
 * </ul>
 * bangumi 自源详情跳过(自带全量标题);无 externalId/失败静默,负缓存 6h 防反复打外网。
 * 直连 api.bgm.tv(章节拉取复用 {@link BangumiMetadataProvider#fetchEpisodePages})——
 * 不注入任何 MetadataProvider,防构造环(与 RatingBridge 同规)。
 */
@Slf4j
@Component
public class BangumiEpisodeBridge {
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);
    /** TMDB 中文占位标题形态:「第 57 集」「第57话」(真实标题不会长这样,可安全回填)。 */
    private static final Pattern PLACEHOLDER_TITLE = Pattern.compile("^第\\s*\\d{1,4}\\s*[集话話]$");
    /** 补入行的日程上限与 provider 同口径。 */
    private static final int UPCOMING_LIMIT = 60;

    private final RestTemplate restTemplate;
    /** bangumi subject id → 章节列表(Optional.empty 负缓存:无正片章节/请求失败,6h 后重试)。 */
    private final Cache<String, Optional<List<JsonNode>>> episodesCache = Caffeine.newBuilder()
            .maximumSize(300).expireAfterWrite(Duration.ofHours(6)).build();

    public BangumiEpisodeBridge(MetadataHttp metadataHttp) {
        this.restTemplate = metadataHttp.create();
    }

    /** provider 详情尾部接入:占位/空标题回填 + 超上界分集补齐;未桥接到 bangumi/失败静默跳过。 */
    void merge(MetadataDetails details) {
        if (details == null || BangumiMetadataProvider.NAME.equals(details.getProvider())
                || details.getEpisodes() == null || details.getEpisodes().isEmpty()) {
            return; // bangumi 自源详情自带全量标题;无分集的详情(豆瓣纯源)无回填对象
        }
        String subjectId = details.getExternalIds() == null ? null
                : details.getExternalIds().get(BangumiMetadataProvider.NAME);
        if (StringUtils.isBlank(subjectId)) {
            return;
        }
        List<JsonNode> episodes = episodesCache.get(subjectId, key -> {
            try {
                return Optional.of(BangumiMetadataProvider.fetchEpisodePages(restTemplate, key));
            } catch (Exception e) {
                log.debug("bangumi episode bridge {} failed: {}", key, e.getMessage());
                return Optional.empty();
            }
        }).orElse(null);
        if (episodes == null || episodes.isEmpty()) {
            return;
        }
        try {
            apply(details, episodes, System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("bangumi episode bridge merge {} failed: {}", details.getName(), e.getMessage());
        }
    }

    /** 章节正片(type=0)按集号回填标题/补行; aired/日程/总数随之延展。 */
    private static void apply(MetadataDetails details, List<JsonNode> episodes, long now) {
        Map<Integer, JsonNode> byNumber = new TreeMap<>();
        for (JsonNode episode : episodes) {
            if (episode.path("type").asInt(-1) != 0) {
                continue;
            }
            int number = episode.path("ep").asInt(0);
            if (number <= 0) {
                number = episode.path("sort").asInt(0);
            }
            if (number > 0) {
                byNumber.putIfAbsent(number, episode);
            }
        }
        if (byNumber.isEmpty()) {
            return;
        }
        int maxNumber = 0;
        for (EpisodeInfo info : details.getEpisodes()) {
            maxNumber = Math.max(maxNumber, info.getNumber());
            JsonNode episode = byNumber.get(info.getNumber());
            if (episode == null) {
                continue;
            }
            String title = titleOf(episode);
            if (StringUtils.isNotBlank(title) && isPlaceholder(info.getTitle())) {
                info.setTitle(title);
            }
        }
        List<EpisodeInfo> merged = new ArrayList<>(details.getEpisodes());
        List<EpisodeAirDate> upcoming = details.getUpcoming() == null
                ? new ArrayList<>() : new ArrayList<>(details.getUpcoming());
        int aired = details.getAiredEpisodes() == null ? 0 : details.getAiredEpisodes();
        LocalDate windowFrom = LocalDate.now(ZONE).minusDays(1); // 昨日/今日窗口与 provider 同口径
        Long nextAir = details.getNextAirTime();
        boolean appended = false;
        for (Map.Entry<Integer, JsonNode> entry : byNumber.entrySet()) {
            if (entry.getKey() <= maxNumber) {
                continue; // 集号在上界内只回填标题(上面已做),不整行替换
            }
            LocalDate airDate = localDate(entry.getValue().path("airdate").asText());
            Long airMoment = airDate == null ? null
                    : airDate.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli();
            merged.add(new EpisodeInfo(entry.getKey(), titleOf(entry.getValue()), airMoment));
            appended = true;
            if (airMoment == null) {
                continue;
            }
            if (airMoment <= now) {
                aired++;
                if (!airDate.isBefore(windowFrom) && upcoming.size() < UPCOMING_LIMIT) {
                    upcoming.add(new EpisodeAirDate(entry.getKey(), airMoment));
                }
            } else {
                if (nextAir == null || airMoment < nextAir) {
                    nextAir = airMoment;
                }
                if (upcoming.size() < UPCOMING_LIMIT) {
                    upcoming.add(new EpisodeAirDate(entry.getKey(), airMoment));
                }
            }
        }
        if (!appended) {
            return;
        }
        details.setEpisodes(merged);
        details.setUpcoming(upcoming);
        details.setAiredEpisodes(aired);
        details.setTotalEpisodes(Math.max(
                details.getTotalEpisodes() == null ? 0 : details.getTotalEpisodes(),
                byNumber.keySet().stream().max(Integer::compareTo).orElse(maxNumber)));
        if (details.getNextAirTime() == null && nextAir != null) {
            details.setNextAirTime(nextAir); // 源侧无日程(滞后未建行)时,补入行给出下集播出
            details.setStatus(MetadataDetails.STATUS_RETURNING);
        }
    }

    private static String titleOf(JsonNode episode) {
        String title = StringUtils.defaultIfBlank(episode.path("name_cn").asText(), episode.path("name").asText());
        return StringUtils.trimToNull(title); // name_cn 常为空串(盗妖行形态),原名字段才是中文
    }

    private static boolean isPlaceholder(String title) {
        return StringUtils.isBlank(title)
                || PLACEHOLDER_TITLE.matcher(StringUtils.trim(title)).matches();
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
