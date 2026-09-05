package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService;
import cn.har01d.alist_tvbox.util.TextUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 采集源兜底网关(资源聚合.py 资源聚合的 Java 精简版):播放链路最后一级,
 * 候选源(转存/主源/补缺)全灭时才被 EpisodeFallbackService 调用,常规巡检/换源不碰。
 * <p>
 * 站点 = 资源聚合.py SITES 前 8(非凡/卧龙/最大/百度云/暴风/极速/天涯/无尽),全部 MacCMS
 * JSON API({@code api.php/provide/vod}),免费免登录,无域监控需求(失败即放弃,负缓存兜住)。
 * 与玩偶/盘聚等追剧搜索源不同:这里产出的是<b>直链</b>(m3u8/mp4),不经 AList 挂载、
 * 不进候选池、不写集源行 —— 结果只落 msub_episode_fallback 覆盖层。
 * <p>
 * 匹配硬过滤复用 CheckService 现成口径(matchesTitle/titleYearMatches/episodeNumbersForeign),
 * 宁可不补也不绑定异剧;播放条目集号解析复用 parseEpisodeFromTitle(与主链路同口径,
 * 第10集/EP10/S01E10 统一)。HTTP 抽象为可覆写 {@link #http(String)}(WanouSearchService 桩模式),
 * 单测零网络。
 */
@Service
public class CollectionGateway {
    private static final Logger log = LoggerFactory.getLogger(CollectionGateway.class);

    /** 采集站清单(资源聚合.py priority 1-8;id 与 py 一致,便于对日志)。 */
    record Site(String id, String name, String api) {
    }

    private static final List<Site> SITES = List.of(
            new Site("feifan", "非凡资源", "http://ffzy5.tv/api.php/provide/vod"),
            new Site("wolong", "卧龙资源", "https://wolongzyw.com/api.php/provide/vod"),
            new Site("zuida", "最大资源", "https://api.zuidapi.com/api.php/provide/vod"),
            new Site("baiduyun", "百度云资源", "https://api.apibdzy.com/api.php/provide/vod"),
            new Site("baofeng", "暴风资源", "https://bfzyapi.com/api.php/provide/vod"),
            new Site("jisu", "极速资源", "https://jszyapi.com/api.php/provide/vod"),
            new Site("tianya", "天涯资源", "https://tyyszy.com/api.php/provide/vod"),
            new Site("wujin", "无尽资源", "https://api.wujinapi.com/api.php/provide/vod"));

    /** 直接媒体后缀(py _is_direct_media_url):判断该组是否直链组。 */
    private static final List<String> DIRECT_SUFFIXES = List.of(".m3u8", ".mp4", ".flv", ".avi", ".mkv", ".ts");
    /** 标题里的年份(YEAR_MARK 同口径)。 */
    private static final Pattern YEAR_MARK = Pattern.compile("(?<!\\d)(19[89]\\d|20[0-2]\\d)(?!\\d)");
    /** 标题宣称集数进度(TITLE_PROGRESS 同口径取最大值),集号门禁辅助。 */
    private static final Pattern TITLE_PROGRESS = Pattern.compile(
            "(?i)更新?至\\s*(\\d{1,4})|全\\s*(\\d{1,4})\\s*集|第\\s*(\\d{1,4})\\s*[-~至]\\s*(\\d{1,4})\\s*集|第\\s*(\\d{1,4})\\s*集|(?:^|[^a-z])e(?:p)?\\s*(\\d{1,4})(?!\\d)");

    /** 采集搜索条目:站点 + vod_id + 搜索侧元数据(标题/年份/备注)。 */
    public record CollectionItem(String siteId, String siteName, String vodId,
                                  String title, String year, String remarks, int rank) {
    }

    /** 采集播放列表:集号 → 直链(条目 name$url 已折开;同集多版本取非 DV 版)。 */
    public record CollectionPlaylist(String siteId, String siteName, String vodId, String line,
                                      TreeMap<Integer, String> episodes) {
    }

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final MediaSubscriptionCheckService checkService;
    private final OkHttpClient httpClient = new OkHttpClient();
    private static final AtomicInteger SEARCH_SEQ = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(SITES.size(), r -> {
        Thread thread = new Thread(r, "collection-fallback-" + SEARCH_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    public CollectionGateway(AppProperties appProperties, ObjectMapper objectMapper,
                             MediaSubscriptionCheckService checkService) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.checkService = checkService;
    }

    /**
     * 8 站并行搜索 + 硬过滤 + 排序:标题归属(matchesTitle,含剧名/别名/自定义词)、
     * 季号、年份门禁全过才收;集号范围门禁(episodeNumbersForeign)按详情侧集号覆盖复核。
     * 返回按「同名精确 > 季年匹配 > 覆盖缺口数」排序的条目,空 = 无可用采集资源(调用方记负缓存)。
     */
    public List<CollectionItem> search(MediaSubscription subscription, int currentEpisode) {
        String keyword = StringUtils.defaultIfBlank(subscription.getKeyword(), subscription.getName());
        var config = appProperties.getSubscription();
        int timeout = config.getCollectionFallbackTimeoutSeconds();
        List<Future<List<JsonNode>>> futures = new ArrayList<>();
        for (Site site : SITES) {
            String url = site.api() + "?ac=list&wd=" + java.net.URLEncoder.encode(keyword,
                    java.nio.charset.StandardCharsets.UTF_8) + "&pg=1&pagesize=30";
            futures.add(executor.submit(() -> requestList(site, url, timeout)));
        }
        List<String> names = checkService.matchNames(subscription);
        Integer metaYear = checkService.metaYear(subscription);
        Integer expectedSeason = subscription.getSeason();
        List<CollectionItem> result = new ArrayList<>();
        for (int i = 0; i < SITES.size(); i++) {
            List<JsonNode> items = join(futures.get(i));
            if (items == null) {
                continue;
            }
            Site site = SITES.get(i);
            for (JsonNode item : items) {
                CollectionItem matched = filterItem(site, item, names, metaYear, expectedSeason);
                if (matched != null) {
                    result.add(matched);
                }
            }
        }
        result.sort(Comparator.comparingInt(CollectionItem::rank));
        log.info("collection fallback search {} (sub {} ep {}): {} candidates from {} sites",
                keyword, subscription.getId(), currentEpisode, result.size(),
                futures.stream().filter(f -> join(f) != null).count());
        return result;
    }

    /** 单条目硬过滤:归属/季/年三道门禁;rank 越小越优(0=精确同名同季)。 */
    private CollectionItem filterItem(Site site, JsonNode item, List<String> names,
                                      Integer metaYear, Integer expectedSeason) {
        String vodId = item.path("vod_id").asText("");
        String title = item.path("vod_name").asText("");
        String year = item.path("vod_year").asText("");
        String remarks = item.path("vod_remarks").asText("");
        if (StringUtils.isBlank(vodId) || "0".equals(vodId) || StringUtils.isBlank(title)) {
            return null;
        }
        if (!MediaSubscriptionCheckService.matchesTitle(names, title)) {
            return null; // 归属不符:同名异剧/无关条目,宁可不补
        }
        if (!titleTailAcceptable(names, title)) {
            return null; // 剧名后续接未知词(「凡人修仙外传」):前缀异剧,拒绝
        }
        Integer titleSeason = TextUtils.parseTitleSeason(title);
        int rank = 1;
        if (titleSeason != null) {
            if (expectedSeason != null && expectedSeason > 0 && !titleSeason.equals(expectedSeason)) {
                return null; // 明确标注其它季:对本订阅是异剧
            }
            rank = titleSeason.equals(expectedSeason) ? 0 : 1; // 无期望季号时降可信度不拒
        }
        if (metaYear != null && StringUtils.isNotBlank(year)) {
            String digits = firstYear(year);
            if (digits != null && Integer.parseInt(digits) != metaYear) {
                return null; // 双方都有年份且不等:拒绝
            }
        }
        return new CollectionItem(site.id(), site.name(), vodId, title, year, remarks, rank);
    }

    /**
     * 前缀异剧门禁:matchesTitle 的子串/模糊语义会放行「凡人修仙外传」(剧名内插字)。
     * 标题紧凑形态剥掉全部已知标记(季/集/画质/载体/进度)后必须与某剧名紧凑形态<b>全等</b>
     * —— 剩一个实义字(外/后/前传)即异剧。名字直接完整出现且尾部全噪声的同样通过。
     */
    static boolean titleTailAcceptable(List<String> names, String title) {
        if (names == null || names.isEmpty() || StringUtils.isBlank(title)) {
            return true;
        }
        String compactTitle = MediaSubscriptionCheckService.normalizeForMatch(title).replace(" ", "");
        String stripped = TAIL_NOISE.matcher(compactTitle).replaceAll("");
        for (String name : names) {
            String compactName = MediaSubscriptionCheckService.normalizeForMatch(name).replace(" ", "");
            if (compactName.isBlank()) {
                continue;
            }
            if (stripped.equals(compactName) || compactTitle.equals(compactName)) {
                return true;
            }
        }
        // 剧名全等失败但标题仍含剧名且尾部全噪声:normalize 差异(全半角/大小写)兜底
        for (String name : names) {
            String n = MediaSubscriptionCheckService.normalizeForMatch(name);
            if (n.isBlank() || !compactTitle.contains(n.replace(" ", ""))) {
                continue;
            }
            int idx = compactTitle.indexOf(n.replace(" ", ""));
            String tail = compactTitle.substring(idx + n.replace(" ", "").length());
            if (StringUtils.isBlank(TAIL_NOISE.matcher(tail).replaceAll(""))) {
                return true;
            }
        }
        return false;
    }

    /** 剧名尾部已知标记(同名后缀噪声):季/集序数、画质、载体、音轨字幕、进度词、数字年份。 */
    private static final Pattern TAIL_NOISE = Pattern.compile(
            "第[0-9一二三四五六七八九十百]+[季部集]|第?[0-9一二三四五六七八九十百]+[季部]|season\\s*\\d+|s\\d{1,2}"
                    + "|更新?至?\\d{1,4}集?|全\\d{1,4}集|\\d{1,4}集|\\d{1,4}|20\\d{2}"
                    + "|完结|大结局|全集|合集|正片|抢先|首发|独家|修复|高清|国语|粤语|中字|双字|双语|字幕"
                    + "|电视剧|电影|动画|动漫|剧场版|4k|hdr|2160p|1080p|720p|蓝光|web.?dl|[a-zA-Z]+");

    /**
     * 拉取条目详情并解析播放列表(vod_play_url 组$$$集#条目 name$url):
     * 只认含直接媒体后缀的组(py _parse_play_groups 语义收紧),集号走 parseEpisodeFromTitle。
     * 纯网页/网盘组(HTML、pan 链接)整体拒收 —— 覆盖层 URL 以 parse:0 直链形态返回 TVBox,
     * 存进去的就是把网页地址当视频流;无可解析直链集返回 null。
     */
    public CollectionPlaylist loadPlaylist(MediaSubscription subscription, CollectionItem item) {
        var config = appProperties.getSubscription();
        Site site = siteOf(item.siteId());
        if (site == null) {
            return null;
        }
        JsonNode detail;
        try {
            String url = site.api() + "?ac=videolist&ids=" + java.net.URLEncoder.encode(item.vodId(),
                    java.nio.charset.StandardCharsets.UTF_8);
            JsonNode body = parseJson(http(url, config.getCollectionFallbackTimeoutSeconds()));
            detail = body == null ? null : body.path("list").path(0);
        } catch (Exception e) {
            log.info("collection fallback detail {} {} failed: {}", site.id(), item.vodId(), e.getMessage());
            return null;
        }
        if (detail == null || !detail.isObject() || detail.isEmpty()) {
            return null;
        }
        String playFrom = detail.path("vod_play_from").asText("");
        String playUrl = detail.path("vod_play_url").asText("");
        String[] fromGroups = playFrom.split("\\$\\$\\$");
        String[] urlGroups = playUrl.split("\\$\\$\\$");
        TreeMap<Integer, String> episodes = new TreeMap<>();
        String line = null;
        for (int i = 0; i < urlGroups.length; i++) {
            if (!groupHasDirectUrl(urlGroups[i])) {
                continue; // 非直链组(网页/网盘链接)对兜底无意义,宁可不补也不存伪直链
            }
            TreeMap<Integer, String> parsed = parseGroup(urlGroups[i], subscription.getSeason());
            if (parsed.isEmpty()) {
                continue;
            }
            if (episodes.isEmpty()) { // 首个可解析的直链组定版:后续组不再覆盖(同前多版本混挂常态)
                episodes.putAll(parsed);
                String groupName = i < fromGroups.length ? fromGroups[i].trim() : "";
                line = StringUtils.defaultIfBlank(groupName, site.name());
            }
        }
        if (episodes.isEmpty()) {
            return null;
        }
        return new CollectionPlaylist(site.id(), site.name(), item.vodId(), line, episodes);
    }

    /** 单组播放条目解析:集号(parseEpisodeFromTitle 同主链路口径)→ 直链 URL。 */
    private TreeMap<Integer, String> parseGroup(String group, Integer season) {
        TreeMap<Integer, String> out = new TreeMap<>();
        if (StringUtils.isBlank(group)) {
            return out;
        }
        for (String entry : group.split("#")) {
            if (StringUtils.isBlank(entry)) {
                continue;
            }
            int index = entry.lastIndexOf('$');
            if (index <= 0) {
                continue;
            }
            String name = entry.substring(0, index).trim();
            String url = entry.substring(index + 1).trim();
            if (StringUtils.isBlank(url)) {
                continue;
            }
            int episode = checkService.parseEpisodeFromTitle(name, season);
            if (episode > 0) {
                out.putIfAbsent(episode, url); // 同集多版本取首个(采集站组内重复罕见)
            }
            // 集号解析失败(番外/预告):不补 —— 宁缺毋错
        }
        return out;
    }

    /** 该组是否含直接媒体后缀的条目(py _is_direct_media_url)。 */
    static boolean groupHasDirectUrl(String group) {
        String lowered = group.toLowerCase(java.util.Locale.ROOT);
        return DIRECT_SUFFIXES.stream().anyMatch(lowered::contains);
    }

    /** 列表请求(搜索):单站失败返回 null(视作该站本轮无产出)。 */
    private List<JsonNode> requestList(Site site, String url, int timeoutSeconds) {
        try {
            JsonNode body = parseJson(http(url, timeoutSeconds));
            if (body == null) {
                return null;
            }
            List<JsonNode> items = new ArrayList<>();
            body.path("list").forEach(items::add);
            return items;
        } catch (Exception e) {
            log.debug("collection fallback search {} failed: {}", site.id(), e.getMessage());
            return null;
        }
    }

    /** HTTP 抓取:非 200/空体返回 null;可覆写打桩(单测零网络)。 */
    protected String http(String url, int timeoutSeconds) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", appProperties.getUserAgent())
                .build();
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(Math.min(timeoutSeconds, 10), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            return response.body().string();
        }
    }

    private JsonNode parseJson(String body) throws IOException {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        return objectMapper.readTree(body);
    }

    private static String firstYear(String text) {
        Matcher matcher = YEAR_MARK.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Site siteOf(String siteId) {
        return SITES.stream().filter(s -> s.id().equals(siteId)).findFirst().orElse(null);
    }

    /** Future 汇合:取消/异常一律 null(该站无产出),不拖垮整轮。 */
    private static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
