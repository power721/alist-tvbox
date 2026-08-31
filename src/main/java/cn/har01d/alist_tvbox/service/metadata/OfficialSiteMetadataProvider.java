package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 官方视频平台集数兜底(§4.8):腾讯/优酷/爱奇艺,接口与解析参考 atv-player
 * (src/atv_player/metadata/providers/{tencent,youku,iqiyi}.py)的已验证实现:
 * - 腾讯:pbaccess trpc MbSearch 搜索 + GetPageData 官方分集列表(带 publish_date,可推算已播/下集播出时间,最准);
 * - 优酷:search.youku.com/api/search,commonData.updateNotice 文案"更新至/全 N 集";
 * - 爱奇艺:mesh.if.iqiyi.com homePageV3,albumInfo.updateTime.value 文案。
 * metaId = 剧名(details 时重新搜索定位);仅非腾讯站结果降权跳过(与 atv-player 的 native 站判定一致)。
 * 全部失败时回落可配置 URL 模板(msub_official_url_template,占位符 {name})。
 */
@Slf4j
@Component
public class OfficialSiteMetadataProvider implements MetadataProvider {
    public static final String NAME = "official";
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);
    private static final Pattern UPDATE_TEXT = Pattern.compile("(?:更新至|已更新到|更新到|共|全)\\s*第?\\s*(\\d{1,3})\\s*集");
    /** "更新至22/22集"形式:已播/总数,相等即完结 */
    private static final Pattern PROGRESS_TEXT = Pattern.compile("(\\d{1,3})\\s*/\\s*(\\d{1,3})\\s*集");
    private static final Pattern ENDED_TEXT = Pattern.compile("(?:全集|已完结|全\\s*\\d{1,3}\\s*集)");

    private static final String TENCENT_SEARCH_URL = "https://pbaccess.video.qq.com/trpc.videosearch.mobile_search.MultiTerminalSearch/MbSearch?vversion_platform=2";
    private static final String TENCENT_EPISODE_URL = "https://pbaccess.video.qq.com/trpc.universal_backend_service.page_server_rpc.PageServer/GetPageData?video_appid=3000010&vplatform=2&vversion_name=8.2.96";
    private static final Pattern TENCENT_COVER_ID = Pattern.compile("/cover/([A-Za-z0-9]+)");
    private static final Pattern TENCENT_LINK = Pattern.compile("v\\.qq\\.com/x/cover/([A-Za-z0-9]+)");
    private static final String YOUKU_SEARCH_URL = "https://search.youku.com/api/search";
    private static final String IQIYI_SEARCH_URL = "https://mesh.if.iqiyi.com/portal/lw/search/homePageV3";

    private final SettingRepository settingRepository;
    private final RestTemplate restTemplate;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    /** details 会触发 1-3 个平台 HTTP 请求,必须缓存(与巡检/封面渲染频率解耦) */
    private final com.github.benmanes.caffeine.cache.Cache<String, MetadataDetails> detailsCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(200).expireAfterWrite(java.time.Duration.ofHours(6)).build();

    private final MetadataHealth health;

    public OfficialSiteMetadataProvider(SettingRepository settingRepository, MetadataHttp metadataHttp, MetadataHealth health) {
        this.settingRepository = settingRepository;
        // pbaccess 网关歧视 HttpURLConnection 连接层(20607),腾讯系接口必须走 JDK HttpClient(HTTP/2)
        this.restTemplate = metadataHttp.createJdk();
        this.health = health;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public List<MetadataSearchItem> search(String keyword) {
        List<MetadataSearchItem> result = new ArrayList<>();
        if (StringUtils.isBlank(keyword) || health.isOpen(NAME)) {
            return result;
        }
        // 搜索条目只出腾讯本站结果(其他平台仅用于集数兜底,条目绑定优先豆瓣/TMDB/Bangumi)
        try {
            JsonNode item = tencentFirstNative(keyword.trim());
            if (item != null) {
                JsonNode videoInfo = item.path("videoInfo");
                MetadataSearchItem entry = new MetadataSearchItem();
                entry.setProvider(NAME);
                entry.setId(keyword.trim()); // metaId=剧名,details 时重搜定位
                entry.setName(videoInfo.path("title").asText(""));
                entry.setYear(videoInfo.path("year").asText(""));
                entry.setDescription("腾讯视频");
                result.add(entry);
            }
            health.record(NAME, true);
        } catch (Exception e) {
            health.record(NAME, false);
            log.debug("official search failed: {}", e.getMessage());
            // 上抛给 MetadataService.searchReport 的 errors 映射(与 TMDB 同规):空表与失败调用方无从区分
            throw e instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(e);
        }
        return result;
    }

    @Override
    public MetadataDetails details(String id, Integer season) {
        return detailsCache.get(StringUtils.defaultString(id), key -> fetchDetails(key));
    }

    @Override
    public MetadataDetails refreshDetails(String id, Integer season) {
        MetadataDetails details = fetchDetails(StringUtils.defaultString(id));
        if (details != null) {
            detailsCache.put(StringUtils.defaultString(id), details);
        }
        return details;
    }

    private MetadataDetails fetchDetails(String id) {
        MetadataDetails details = new MetadataDetails();
        if (health.isOpen(NAME)) {
            details.setProvider(NAME);
            details.setId(id);
            return details;
        }
        details.setProvider(NAME);
        details.setId(id);
        details.setName(id);
        details.setStatus(MetadataDetails.STATUS_UNKNOWN);
        if (StringUtils.isBlank(id)) {
            return details;
        }
        // 腾讯 cover 链接(metaId 直接存 URL):按 cid 直取官方分集列表,无需按名重搜
        Matcher linkMatcher = TENCENT_LINK.matcher(id);
        if (linkMatcher.find()) {
            try {
                applyEpisodeDates(details, tencentCoverEpisodeDates(linkMatcher.group(1)));
            } catch (Exception e) {
                log.debug("tencent link episodes failed: {}", e.getMessage());
            }
            details.setName(null); // 链接本身不含剧名,展示名由订阅名称承担
            return details;
        }
        // 1) 腾讯官方分集列表:publish_date 可推算已播/下集播出(综艺/独播剧最准)
        try {
            JsonNode item = tencentFirstNative(id);
            String providerId = item == null ? "" : tencentProviderId(item);
            Matcher cover = TENCENT_COVER_ID.matcher(providerId);
            if (cover.find()) {
                List<LocalDate> dates = tencentCoverEpisodeDates(cover.group(1));
                if (!dates.isEmpty()) {
                    applyEpisodeDates(details, dates);
                    if (item.path("videoInfo").path("title").asText("").length() > 0) {
                        details.setName(item.path("videoInfo").path("title").asText());
                    }
                    return details;
                }
            }
        } catch (Exception e) {
            log.debug("tencent episodes failed: {}", e.getMessage());
        }
        // 2) 优酷 updateNotice / 爱奇艺 updateTime 文案:"更新至N集 / 全N集"
        String updateText = firstUpdateText(id);
        if (StringUtils.isNotBlank(updateText)) {
            applyUpdateText(details, updateText);
            if (details.getAiredEpisodes() != null) {
                return details;
            }
        }
        // 3) 可配置模板兜底(自建代理/渲染端点)
        applyTemplate(details, id);
        // 「没搜到」是常态(官源只覆盖腾讯/优酷/爱奇艺在播剧),不能计入熔断失败 ——
        // 否则连续查 3 部不在官源的剧就误开 60s 熔断,殃及同期其他剧的兜底查询;
        // 真失败(HTTP 异常)在各路径的 catch 里已被吞为降级,熔断交给 search 的 record
        return details;
    }

    static void applyEpisodeDates(MetadataDetails details, List<LocalDate> dates) {
        applyEpisodeDates(details, dates, System.currentTimeMillis());
    }

    /**
     * 已播按播出时刻(当日 20:00,与 airTime 展示同口径)判定而非日期粒度:播出日当天 20:00 前
     * 刷新即把当日集算已播,会把当日待播集虚报成缺集;已播集仍按昨日窗口进日程(时间轴
     * 「昨天/今天」用),nextAirTime 严格取 20:00 未过的集。
     */
    static void applyEpisodeDates(MetadataDetails details, List<LocalDate> dates, long now) {
        if (dates.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now(ZONE);
        int aired = 0;
        LocalDate nextAir = null;
        List<cn.har01d.alist_tvbox.dto.EpisodeAirDate> upcoming = new ArrayList<>();
        for (LocalDate date : dates) {
            long airMoment = date.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli();
            if (airMoment <= now) {
                aired++;
                // 昨日/今日已播仍进日程(时间轴「昨天/今天」用),只收严格未来会把刚播出的集洗掉
                if (!date.isBefore(today.minusDays(1)) && upcoming.size() < 60) {
                    upcoming.add(new cn.har01d.alist_tvbox.dto.EpisodeAirDate(0, airMoment));
                }
            } else {
                if (nextAir == null || date.isBefore(nextAir)) {
                    nextAir = date;
                }
                if (upcoming.size() < 60) {
                    upcoming.add(new cn.har01d.alist_tvbox.dto.EpisodeAirDate(0, airMoment));
                }
            }
        }
        details.setAiredEpisodes(aired);
        details.setTotalEpisodes(null); // 官方分集列表只含已上架集,总数交给豆瓣/TMDB 或期望值
        details.setNextAirTime(nextAir == null ? null
                : nextAir.atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli());
        details.setStatus(nextAir == null ? MetadataDetails.STATUS_UNKNOWN : MetadataDetails.STATUS_RETURNING);
        details.setUpcoming(upcoming);
    }

    static void applyUpdateText(MetadataDetails details, String text) {
        // 优先 "N/M集" 形式(优酷"更新至22/22集"):已播 + 总数,相等即完结
        Matcher progress = PROGRESS_TEXT.matcher(text);
        if (progress.find()) {
            int aired = Integer.parseInt(progress.group(1));
            int total = Integer.parseInt(progress.group(2));
            details.setAiredEpisodes(aired);
            details.setTotalEpisodes(total);
            details.setStatus(aired >= total ? MetadataDetails.STATUS_ENDED : MetadataDetails.STATUS_RETURNING);
            return;
        }
        Matcher matcher = UPDATE_TEXT.matcher(text);
        int max = 0;
        while (matcher.find()) {
            max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        if (max > 0) {
            details.setAiredEpisodes(max);
            boolean ended = ENDED_TEXT.matcher(text).find();
            details.setTotalEpisodes(ended ? max : null);
            details.setStatus(ended ? MetadataDetails.STATUS_ENDED : MetadataDetails.STATUS_RETURNING);
        }
    }

    private void applyTemplate(MetadataDetails details, String id) {
        String template = settingRepository.findById("msub_official_url_template")
                .map(s -> s.getValue()).orElse("");
        if (StringUtils.isBlank(template)) {
            return;
        }
        try {
            String url = template.replace("{name}", java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8));
            ResponseEntity<String> response = restTemplate.exchange(URI.create(url), HttpMethod.GET,
                    new HttpEntity<>(null, browserHeaders()), String.class);
            if (StringUtils.isNotBlank(response.getBody())) {
                applyUpdateText(details, response.getBody());
            }
        } catch (Exception e) {
            log.debug("official template failed: {}", e.getMessage());
        }
    }

    /** 腾讯搜索:返回第一个本站(dataType=2 且含 videoInfo)条目。 */
    private JsonNode tencentFirstNative(String keyword) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("version", "26022601");
        payload.put("clientType", 1);
        payload.put("query", keyword);
        payload.put("pagenum", 0);
        payload.put("pagesize", 30);
        payload.put("uuid", UUID.randomUUID().toString().toUpperCase());
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
        // String 收发:与消息转换器组合解耦,避免 Jackson2 ObjectNode 撞上 Jackson3 转换器
        ResponseEntity<String> response = restTemplate.exchange(URI.create(TENCENT_SEARCH_URL), HttpMethod.POST,
                new HttpEntity<>(MAPPER.writeValueAsString(payload), headers), String.class);
        JsonNode data = StringUtils.isBlank(response.getBody()) ? null : MAPPER.readTree(response.getBody()).path("data");
        if (!data.isObject()) {
            return null;
        }
        List<JsonNode> items = new ArrayList<>();
        collectItems(data.path("normalList").path("itemList"), items);
        for (JsonNode box : data.path("areaBoxList")) {
            collectItems(box.path("itemList"), items);
        }
        for (JsonNode item : items) {
            if (item.path("doc").path("dataType").asInt(0) == 2 && item.path("videoInfo").isObject()) {
                return item;
            }
        }
        return null;
    }

    private static void collectItems(JsonNode list, List<JsonNode> out) {
        if (list.isArray()) {
            list.forEach(out::add);
        }
    }

    private static String tencentProviderId(JsonNode item) {
        JsonNode videoInfo = item.path("videoInfo");
        for (String siteKey : List.of("playSites", "episodeSites")) {
            for (JsonNode site : videoInfo.path(siteKey)) {
                for (JsonNode episode : site.path("episodeInfoList")) {
                    String url = episode.path("url").asText("");
                    if (StringUtils.isNotBlank(url)) {
                        return url;
                    }
                }
            }
        }
        String coverId = item.path("doc").path("id").asText("");
        return coverId.isEmpty() ? "" : "https://v.qq.com/x/cover/" + coverId + ".html";
    }

    /** 腾讯官方分集列表(publish_date);跳过无日期的分区标签(第N季/花絮等)。 */
    private List<LocalDate> tencentCoverEpisodeDates(String coverId) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        ObjectNode pageParams = payload.putObject("page_params");
        pageParams.put("req_from", "web_vsite");
        pageParams.put("page_id", "vsite_episode_list");
        pageParams.put("page_type", "detail_operation");
        pageParams.put("id_type", "1");
        pageParams.put("page_size", "100");
        pageParams.put("cid", coverId);
        pageParams.put("req_from_platform_id", "2");
        pageParams.put("is_skp_style", "false");
        payload.put("has_cache", 1);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ORIGIN, "https://v.qq.com");
        headers.set(HttpHeaders.REFERER, "https://v.qq.com/");
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0");
        ResponseEntity<String> response = restTemplate.exchange(URI.create(TENCENT_EPISODE_URL), HttpMethod.POST,
                new HttpEntity<>(MAPPER.writeValueAsString(payload), headers), String.class);
        JsonNode responseBody = StringUtils.isBlank(response.getBody()) ? null : MAPPER.readTree(response.getBody());
        List<LocalDate> dates = new ArrayList<>();
        for (JsonNode module : responseBody.path("data").path("module_list_datas")) {
            for (JsonNode moduleData : module.path("module_datas")) {
                for (JsonNode item : moduleData.path("item_data_lists").path("item_datas")) {
                    JsonNode params = item.path("item_params");
                    String date = params.path("publish_date").asText("");
                    if (date.length() >= 10) {
                        try {
                            dates.add(LocalDate.parse(date.substring(0, 10)));
                        } catch (Exception ignored) {
                            // 非标准日期格式跳过
                        }
                    }
                }
            }
        }
        return dates;
    }

    /** 优酷 updateNotice / 爱奇艺 updateTime:取第一个含"更新至/全N集"的文案。 */
    private String firstUpdateText(String keyword) {
        try {
            // 优酷
            ResponseEntity<String> youku = restTemplate.exchange(
                    URI.create(YOUKU_SEARCH_URL + "?keyword=" + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8)
                            + "&userAgent=Mozilla%2F5.0&site=1&categories=0&ftype=0&ob=0&pg=1"),
                    HttpMethod.GET, new HttpEntity<>(null, youkuHeaders()), String.class);
            for (JsonNode component : MAPPER.readTree(youku.getBody()).path("pageComponentList")) {
                JsonNode common = component.path("commonData");
                for (String key : List.of("updateNotice", "updateNotification")) {
                    String text = common.path(key).asText("");
                    if (StringUtils.isNotBlank(text)) {
                        return text;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("youku search failed: {}", e.getMessage());
        }
        try {
            // 爱奇艺
            ResponseEntity<String> iqiyi = restTemplate.exchange(
                    URI.create(IQIYI_SEARCH_URL + "?key=" + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8)
                            + "&pageNum=1&pageSize=25&mode=1&current_page=1"),
                    HttpMethod.GET, new HttpEntity<>(null, iqiyiHeaders()), String.class);
            for (JsonNode template : MAPPER.readTree(iqiyi.getBody()).path("data").path("templates")) {
                int templateId = template.path("template").asInt(0);
                if (templateId != 101 && templateId != 102 && templateId != 103 && templateId != 112) {
                    continue;
                }
                String text = template.path("albumInfo").path("updateTime").path("value").asText("");
                if (StringUtils.isNotBlank(text)) {
                    return text;
                }
            }
        } catch (Exception e) {
            log.debug("iqiyi search failed: {}", e.getMessage());
        }
        return null;
    }

    private static HttpHeaders youkuHeaders() {
        HttpHeaders headers = browserHeaders();
        headers.set(HttpHeaders.REFERER, "https://www.youku.com/");
        return headers;
    }

    private static HttpHeaders iqiyiHeaders() {
        HttpHeaders headers = browserHeaders();
        headers.set(HttpHeaders.REFERER, "https://www.iqiyi.com/");
        return headers;
    }

    private static HttpHeaders browserHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        headers.set(HttpHeaders.ACCEPT, "application/json");
        return headers;
    }
}
