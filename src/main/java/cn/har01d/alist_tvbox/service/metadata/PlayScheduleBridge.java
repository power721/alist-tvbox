package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
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
 * 平台播放源桥接:①排播时刻 —— TMDB/豆瓣/Bangumi 的日程只有日期,时刻统一按当日 20:00 填,而各剧
 * 实际排播时刻不同(师兄太稳健 12:00),时间轴展示/已播判定/播出前休眠/播后短轮全部偏晚;②官方
 * 播放地址 —— 豆瓣条目「在哪儿看」的 vendors(rexxar tv 接口,游客可用)给出各平台入口,构造为
 * 播放页 URL 写入 {@code MetadataDetails.playLinks}(详情页 links 展开;完结剧/无日程剧同样适用,
 * 不依赖排播时刻命中)。两路同一份 vendors 数据零额外请求:
 * <ul>
 * <li>爱奇艺:vendors url 是 www 域名播放页,但 www 对无 JS 客户端只回空壳页 —— 换 m 域名同路径
 * 即完整 SSR,且 m 站按 UA 分流:桌面 UA 302 回 www 空壳(醒来 36126289 实测,静默拿不到任何
 * issueTime),必须带手机 UA;分集条目 type=1 正片(3=预告/花絮)的 issueTime 取最近 8 集
 * 时刻众数。免费转免线更新时刻会略早(次日 11:50 vs VIP 12:00)且数量少,众数天然压掉;
 * 播放链接剥豆瓣引流参数(vfm/fv)并升级 https;</li>
 * <li>优酷:vendors url 是豆瓣小程序 scheme(showId 以明文/URL 编码形态嵌在 path 里),抠出 showId
 * 构造 show 页链接(浏览器访问 302 落到播放页);排播时刻请求同一 show 页(游客直出
 * __INITIAL_DATA__)取 videoPublishTime(本集上线时刻)。</li>
 * <li>腾讯视频(花开锦绣 36810153 实测):vendors url 小程序 scheme 抠 cid 构造 cover 页链接;
 * 排播时刻走 pbaccess GetPageData(游客 POST)分集列表 module_params.sub_title 更新文案
 * (「会员周一至周三18点更新1集,周四至周日18点更新2集,SVIP抢先看1集…」)抽 HH:mm —— 分集条目的
 * publish_date 已普遍不回填(花开锦绣 0/56、庆余年S2 0/34 实测)不可依赖,完结剧文案无时刻词
 * (「会员看全集」)自然跳过,时刻只在文案字段内找不扫全文(duration 等数字形态会误命中)。</li>
 * <li>咪咕视频(悬案 36624136 实测):vendors url 本就是 https 播放页(m 站 detail 页)直接入
 * links;分集数据是低代码平台 XHR 异步拉(壳页零数据、网关接口未逆向),时刻路不接 —— 咪咕
 * 同播剧爱优腾路已覆盖。</li>
 * </ul>
 * 时刻校正复用 {@link BilibiliScheduleRefiner#applyScheduleClock}:只换时分、日期不动(TMDB 排播日
 * 与官方一致),airedEpisodes/nextAirTime 按校正后时刻重数。豆瓣 subject id 取自 externalIds
 * (豆瓣源自带;TMDB/Bangumi 订阅经 {@link RatingBridge} 桥接后带上),因此必须挂在
 * ratingBridge.enrich 之后;桥接不到豆瓣则跳过。命中与未命中各缓存 6h(负缓存防完播剧反复
 * refresh 打爆外网),失败静默不炸详情主链。与 B站 refiner 的顺序:平台桥挂后(国产剧爱优腾为
 * 正源,两路时刻同剧一致时后跑覆盖无碍)。芒果等平台页面结构未验证,暂不接。
 * <p>不依赖任何 MetadataProvider(直连接口)—— 与 RatingBridge 同规,防构造环。
 */
@Slf4j
@Component
public class PlayScheduleBridge {
    /** 桥接产物:排播时刻(可 null —— 完结剧/无排播文案只有链接)+ 官方播放地址(平台名→播放页)。 */
    record PlaySources(LocalTime clock, Map<String, String> playLinks) {
    }
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of(Constants.ZONE_ID);
    private static final String REXXAR_URL = "https://m.douban.com/rexxar/api/v2/tv/";
    /** 爱奇艺 m 页分集条目:同对象内 type 与 issueTime 之间全为标量字段,无嵌套即可跨到。 */
    private static final Pattern IQ_ENTRY = Pattern.compile("\"type\":(\\d),[^{}]*?\"issueTime\":(\\d+)");
    /** 优酷播放页 __INITIAL_DATA__:本集上线时刻(页面是第几集入口不影响,同剧每集同点更新)。 */
    private static final Pattern YK_PUBLISH_TIME =
            Pattern.compile("\"videoPublishTime\"\\s*:\\s*\"\\d{4}-\\d{2}-\\d{2} (\\d{2}:\\d{2})");
    /** 豆瓣小程序 scheme 里的 showId(明文 = / 编码 %3D、%3d 两种形态;id 是十六进制串)。 */
    private static final Pattern YK_SHOW_ID = Pattern.compile("(?i)show[Ii]d(?:=|%3d)([0-9a-f]{16,})");
    private static final String TENCENT_EPISODE_URL =
            "https://pbaccess.video.qq.com/trpc.universal_backend_service.page_server_rpc.PageServer/GetPageData"
                    + "?video_appid=3000010&vplatform=2&vversion_name=8.2.96";
    private static final Pattern TX_CID = Pattern.compile("cid=([A-Za-z0-9]{10,})");
    /** 更新文案中的排播时刻:「18点」「12:30」两种形态。 */
    private static final Pattern CLOCK_TEXT = Pattern.compile("(\\d{1,2}):([0-5]\\d)|(\\d{1,2})点");
    /** 与 BilibiliScheduleRefiner 同口径:最近 8 个已上线集的 HH:mm 众数(平手取更新的集)。 */
    private static final int RECENT_LIMIT = 8;
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    /** m.iqiyi.com 按 UA 分流:桌面 UA 302 回 www JS 空壳(零分集数据),手机 UA 才直出完整 SSR 页。 */
    private static final String MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    private final RestTemplate restTemplate;
    /** 豆瓣 subject id → 播放源(Optional.empty 负缓存:无播放源/页面失败,6h 后重试)。 */
    private final Cache<String, Optional<PlaySources>> sourceCache = Caffeine.newBuilder()
            .maximumSize(300).expireAfterWrite(Duration.ofHours(6)).build();

    public PlayScheduleBridge(MetadataHttp metadataHttp) {
        this.restTemplate = metadataHttp.create();
    }

    /**
     * provider 详情尾部接入(ratingBridge 之后):官方播放地址写入 playLinks(不依赖日程与时刻,
     * 完结剧同样带出),有分集日程且拿到排播时刻时把播出时刻校正为平台真实 HH:mm;未命中/失败静默。
     */
    void refine(MetadataDetails details) {
        if (details == null || StringUtils.isBlank(details.getName())) {
            return;
        }
        String doubanId = details.getExternalIds() == null ? null
                : details.getExternalIds().get(DoubanMetadataProvider.NAME);
        if (StringUtils.isBlank(doubanId)) {
            return; // 未经豆瓣桥接:拿不到「在哪儿看」入口
        }
        try {
            PlaySources sources = sourceCache.get(doubanId, this::fetchPlaySources).orElse(null);
            if (sources == null) {
                return;
            }
            if (!sources.playLinks().isEmpty()) {
                details.setPlayLinks(sources.playLinks());
            }
            LocalTime clock = sources.clock();
            if (clock == null || details.getEpisodes() == null || details.getEpisodes().isEmpty()) {
                return; // 只有链接:无排播文案或无日程可校正
            }
            BilibiliScheduleRefiner.applyScheduleClock(details, clock, System.currentTimeMillis());
            log.info("play schedule refine: {} air time clock -> {}", details.getName(), clock);
        } catch (Exception e) {
            log.debug("play schedule refine {} failed: {}", details.getName(), e.getMessage());
        }
    }

    /** 豆瓣「在哪儿看」vendors:逐平台收集播放链接,clock 取首个命中(平台页失败自然落到下一家)。 */
    private Optional<PlaySources> fetchPlaySources(String doubanId) {
        JsonNode body = httpGetJson(REXXAR_URL + doubanId);
        JsonNode vendors = body == null ? null : body.path("vendors");
        if (vendors == null || !vendors.isArray()) {
            return Optional.empty();
        }
        LocalTime clock = null;
        Map<String, String> playLinks = new LinkedHashMap<>();
        for (JsonNode vendor : vendors) {
            String platform = platformName(vendor.path("title").asText(""));
            if (platform == null) {
                continue;
            }
            String url = vendor.path("url").asText("");
            String playUrl = switch (platform) {
                case "爱奇艺" -> iqiyiPlayUrl(url);
                case "优酷" -> youkuPlayUrl(url);
                case "腾讯视频" -> tencentPlayUrl(url);
                case "咪咕视频" -> url.contains("miguvideo.com") ? url : null;
                default -> null;
            };
            if (playUrl != null) {
                playLinks.putIfAbsent(platform, playUrl);
            }
            if (clock == null) {
                clock = switch (platform) {
                    case "爱奇艺" -> iqiyiClock(url);
                    case "优酷" -> youkuClock(url);
                    case "腾讯视频" -> tencentClock(url);
                    default -> null; // 咪咕等未逆向时刻路的平台不尝试
                };
                if (clock != null) {
                    log.info("play schedule bridge: douban {} vendor {} clock {}", doubanId, platform, clock);
                }
            }
        }
        if (clock == null && playLinks.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PlaySources(clock, playLinks));
    }

    /** 平台标准名(links 标签),未接入的平台返回 null。 */
    private static String platformName(String title) {
        if (title.contains("爱奇艺")) {
            return "爱奇艺";
        }
        if (title.contains("优酷")) {
            return "优酷";
        }
        if (title.contains("腾讯")) {
            return "腾讯视频";
        }
        if (title.contains("咪咕")) {
            return "咪咕视频";
        }
        return null;
    }

    /** 爱奇艺播放页:剥豆瓣引流参数(vfm/fv)升级 https。 */
    private static String iqiyiPlayUrl(String url) {
        if (!url.contains("iqiyi.com/v_")) {
            return null;
        }
        int query = url.indexOf('?');
        String clean = query > 0 ? url.substring(0, query) : url;
        return clean.startsWith("http://") ? "https://" + clean.substring("http://".length()) : clean;
    }

    /** 优酷 show 页:浏览器访问 302 落到播放页。 */
    private static String youkuPlayUrl(String url) {
        Matcher matcher = YK_SHOW_ID.matcher(url);
        return matcher.find() ? "https://www.youku.com/show/id_" + matcher.group(1) + ".html" : null;
    }

    /** 腾讯 cover 页。 */
    private static String tencentPlayUrl(String url) {
        Matcher matcher = TX_CID.matcher(url);
        return matcher.find() ? "https://v.qq.com/x/cover/" + matcher.group(1) + ".html" : null;
    }

    private LocalTime iqiyiClock(String url) {
        if (!url.contains("iqiyi.com/v_")) {
            return null;
        }
        String html = httpGet(url.replaceFirst("^https?://(www\\.)?iqiyi\\.com", "https://m.iqiyi.com"), MOBILE_UA);
        if (html == null) {
            return null;
        }
        List<Long> times = new ArrayList<>();
        Matcher matcher = IQ_ENTRY.matcher(html);
        while (matcher.find() && times.size() < 60) {
            if ("1".equals(matcher.group(1))) {
                times.add(Long.parseLong(matcher.group(2)));
            }
        }
        return modeClock(times);
    }

    private LocalTime youkuClock(String url) {
        Matcher matcher = YK_SHOW_ID.matcher(url);
        if (!matcher.find()) {
            return null;
        }
        String html = httpGet("https://www.youku.com/show/id_" + matcher.group(1) + ".html");
        if (html == null) {
            return null;
        }
        Matcher time = YK_PUBLISH_TIME.matcher(html);
        if (!time.find()) {
            return null;
        }
        try {
            return LocalTime.parse(time.group(1));
        } catch (Exception e) {
            // 24:00 一类越界形态:返回 null 落到 vendors 里下一家平台,
            // 上抛会把整条 fetchClock 打断(排在后面的腾讯源就永远轮不到)
            log.debug("youku videoPublishTime unparsable: {}", time.group(1));
            return null;
        }
    }

    /**
     * 腾讯:vendors url 小程序 scheme 抠 cid → GetPageData 分集列表,更新文案(module_params.sub_title)
     * 抽排播时刻。只在文案字段内找,不扫整个响应(分集时长等数字形态会误命中);完结剧文案无时刻
     * (「会员看全集」)自然跳过。
     */
    private LocalTime tencentClock(String url) {
        Matcher matcher = TX_CID.matcher(url);
        if (!matcher.find()) {
            return null;
        }
        String response = httpPost(TENCENT_EPISODE_URL, "{\"page_params\":{\"req_from\":\"web_vsite\","
                + "\"page_id\":\"vsite_episode_list\",\"page_type\":\"detail_operation\",\"id_type\":\"1\","
                + "\"page_size\":\"100\",\"cid\":\"" + matcher.group(1)
                + "\",\"req_from_platform_id\":\"2\",\"is_skp_style\":\"false\"},\"has_cache\":1}");
        if (response == null) {
            return null;
        }
        try {
            for (JsonNode module : MAPPER.readTree(response).path("data").path("module_list_datas")) {
                for (JsonNode moduleData : module.path("module_datas")) {
                    LocalTime clock = parseClock(moduleData.path("module_params").path("sub_title").asText(""));
                    if (clock != null) {
                        return clock;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("tencent episode list parse failed: {}", e.getMessage());
        }
        return null;
    }

    /** 文案抽时刻:「18点」/「12:30」,越界(如 25点)继续找下一个候选。 */
    private static LocalTime parseClock(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        Matcher matcher = CLOCK_TEXT.matcher(text);
        while (matcher.find()) {
            try {
                return matcher.group(3) != null
                        ? LocalTime.of(Integer.parseInt(matcher.group(3)), 0)
                        : LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            } catch (Exception ignored) {
                // 越界时刻继续
            }
        }
        return null;
    }

    private static LocalTime modeClock(List<Long> times) {
        if (times.isEmpty()) {
            return null;
        }
        times.sort(Comparator.reverseOrder());
        Map<LocalTime, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(times.size(), RECENT_LIMIT); i++) {
            counts.merge(Instant.ofEpochMilli(times.get(i)).atZone(ZONE).toLocalTime(), 1, Integer::sum);
        }
        LocalTime best = null;
        int bestCount = 0;
        for (Map.Entry<LocalTime, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    /** byte[] 收包 + UTF-8 解码:平台页面响应头多无 charset,String 收包按 ISO-8859-1 解码会乱码。 */
    private String httpGet(String url) {
        return httpGet(url, DESKTOP_UA);
    }

    private String httpGet(String url, String userAgent) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, userAgent);
            headers.set(HttpHeaders.ACCEPT, "text/html");
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(null, headers), byte[].class);
            return response.getBody() == null ? null : new String(response.getBody(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("play schedule request failed: {} {}", url, e.getMessage());
            return null;
        }
    }

    private String httpPost(String url, String jsonBody) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.ACCEPT, "application/json");
            headers.set(HttpHeaders.ORIGIN, "https://v.qq.com");
            headers.set(HttpHeaders.REFERER, "https://v.qq.com/");
            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0");
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.POST, new HttpEntity<>(jsonBody, headers), String.class);
            return response.getBody();
        } catch (Exception e) {
            log.debug("play schedule request failed: {} {}", url, e.getMessage());
            return null;
        }
    }

    private JsonNode httpGetJson(String url) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.REFERER, "https://m.douban.com/");
            headers.set(HttpHeaders.USER_AGENT, Utils.getUserAgent());
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
            return StringUtils.isBlank(response.getBody()) ? null : MAPPER.readTree(response.getBody());
        } catch (Exception e) {
            log.debug("play schedule request failed: {} {}", url, e.getMessage());
            return null;
        }
    }
}
