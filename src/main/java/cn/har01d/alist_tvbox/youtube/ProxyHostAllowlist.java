package cn.har01d.alist_tvbox.youtube;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * 视频代理动态域名白名单:yt-dlp 通用解析(1800+ 站点)的流/清单 CDN 域无法静态枚举,
 * 解析成功后把出流域名登记进来,/{@code youtube-proxy} 放行。
 *
 * <p>SSRF 收敛:登记来源仅限 yt-dlp 提取器输出(用户推送 URL 的解析结果),不是任意
 * 请求参数;条目 15 分钟过期(签名 URL 本身约 6 小时时效,白名单更短)。</p>
 */
@Component
public class ProxyHostAllowlist {
    private final Cache<String, Boolean> hosts = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofMinutes(15))
            .build();

    /** host → 请求头 JSON(yt-dlp format.http_headers,站点防盗链头如 B站 Referer/UA);中继回源时带上。 */
    private final Cache<String, String> hostHeaders = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofMinutes(15))
            .build();

    public void register(String host) {
        if (host != null && !host.isBlank()) {
            hosts.put(host.toLowerCase(Locale.ROOT), Boolean.TRUE);
        }
    }

    public void register(String host, String headersJson) {
        register(host);
        if (host != null && !host.isBlank() && headersJson != null && !headersJson.isBlank()) {
            hostHeaders.put(host.toLowerCase(Locale.ROOT), headersJson);
        }
    }

    /** 精确命中或登记域的后缀匹配(x.y-CDN 常见子域分片)。 */
    public boolean allows(String host) {
        if (host == null) {
            return false;
        }
        String current = host.toLowerCase(Locale.ROOT);
        while (!current.isEmpty()) {
            if (hosts.getIfPresent(current) != null) {
                return true;
            }
            int dot = current.indexOf('.');
            current = dot < 0 ? "" : current.substring(dot + 1);
        }
        return false;
    }

    /** 该 host 登记的防盗链请求头 JSON(子域上溯);无登记返回 null。 */
    public String headersFor(String host) {
        if (host == null) {
            return null;
        }
        String current = host.toLowerCase(Locale.ROOT);
        while (!current.isEmpty()) {
            String headers = hostHeaders.getIfPresent(current);
            if (headers != null) {
                return headers;
            }
            int dot = current.indexOf('.');
            current = dot < 0 ? "" : current.substring(dot + 1);
        }
        return null;
    }
}
