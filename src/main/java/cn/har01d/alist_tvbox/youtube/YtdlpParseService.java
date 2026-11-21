package cn.har01d.alist_tvbox.youtube;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * yt-dlp 通用 URL 解析:推送场景覆盖 YouTube/网盘/磁力之外的视频站(TikTok/X/Twitch 等
 * yt-dlp 支持的 1800+ 站点)。解析出的流/清单 CDN 域登记进 {@link ProxyHostAllowlist},
 * 播放地址经 /youtube-proxy 中继(设备端多无直连能力)。
 *
 * <p>详情即播放:解析成功直接在 vod_play_url 内嵌代理地址(签名 URL 约 6h 时效,
 * 推送单集场景免二次解析)。须在请求线程内调用(取当前请求拼代理基址)。</p>
 */
@Slf4j
@Service
public class YtdlpParseService {
    private final YoutubeDlpRuntime runtime;
    private final ProxyHostAllowlist allowlist;
    private final AppProperties appProperties;

    public YtdlpParseService(YoutubeDlpRuntime runtime, ProxyHostAllowlist allowlist, AppProperties appProperties) {
        this.runtime = runtime;
        this.allowlist = allowlist;
        this.appProperties = appProperties;
    }

    private static final java.util.regex.Pattern MEDIA_FILE = java.util.regex.Pattern.compile(
            "\\.(mp4|mkv|avi|mov|flv|ts|m3u8|mp3|flac|webm|aac)([?#]|$)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** yt-dlp 提取;URL 先过 SSRF 校验(拒私网/非 http),媒体文件直链跳过(generic 解析无增益)。失败返回 null 由调用方回落。 */
    public JsonNode extract(String url) {
        if (url == null || !Utils.isSafeExternalUrl(url)) {
            return null;
        }
        if (MEDIA_FILE.matcher(url).find()) {
            return null;
        }
        try {
            JsonNode info = runtime.execJson(List.of("-J", "--no-playlist", url), Duration.ofSeconds(45));
            return info.has("formats") ? info : null;
        } catch (Exception e) {
            log.debug("ytdlp parse failed: {} {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 播放结果:{parse:0, url}。
     * 直播/有 master 清单 → master(自适应);否则最高质量 muxed;再退 videoOnly 最佳。
     * 直链登记白名单后经代理中继。
     */
    public Map<String, Object> play(JsonNode info, String proxyBase) {
        String master = YoutubeService.findMasterManifest(info);
        List<JsonNode> muxed = YoutubeService.muxedFormats(info);
        String url;
        if (master != null) {
            url = master;
        } else if (!muxed.isEmpty()) {
            url = muxed.get(0).path("url").asText();
        } else {
            List<JsonNode> videos = YoutubeService.videoOnlyFormats(info, 0);
            if (videos.isEmpty()) {
                return null;
            }
            url = videos.get(0).path("url").asText();
        }
        registerHosts(info, url);
        Map<String, Object> result = new HashMap<>();
        result.put("parse", "0");
        result.put("url", proxyBase + "?u=" + YoutubeService.encodeProxyUrl(url));
        return result;
    }

    /** 解析并组装推送详情(单集,线路「播放」,vod_play_url 内嵌代理直链);失败返回 null。 */
    public Map<String, Object> detail(String url, String token) {
        JsonNode info = extract(url);
        if (info == null) {
            return null;
        }
        Map<String, Object> play = play(info, proxyBase(token));
        if (play == null) {
            return null;
        }
        String title = info.path("title").asText(url);
        Map<String, Object> vod = new HashMap<>();
        vod.put("vod_id", url);
        vod.put("vod_name", title);
        vod.put("vod_pic", info.path("thumbnail").asText(""));
        vod.put("vod_play_from", "播放");
        vod.put("vod_play_url", "播放$" + play.get("url"));
        return Map.of("list", List.of(vod));
    }

    /** 代理基址:/youtube-proxy[/{token}],口径同 YoutubeService.buildProxyUrl。 */
    private String proxyBase(String token) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http")
                .replacePath("/youtube-proxy" + (StringUtils.isBlank(token) ? "" : "/" + token))
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    /** 把流/清单/thumbnail 域登记进动态白名单(子域分片 CDN 由后缀匹配覆盖),连带站点防盗链头。 */
    private void registerHosts(JsonNode info, String playUrl) {
        registerHost(playUrl, null);
        for (var format : info.path("formats")) {
            String headers = format.has("http_headers") ? format.path("http_headers").toString() : null;
            registerHost(format.path("url").asText(""), headers);
            registerHost(format.path("manifest_url").asText(""), headers);
        }
    }

    private void registerHost(String url, String headersJson) {
        try {
            String host = URI.create(url).getHost();
            if (host != null) {
                allowlist.register(host, headersJson);
            }
        } catch (Exception ignored) {
        }
    }
}
