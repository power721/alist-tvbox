package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 直播流通用代理。Twitch 的清单域名对带 Origin/Referer 的请求返回 403,
 * SOOP 的 CDN 不下发 CORS 头,浏览器/部分客户端直连不可用;
 * 同时部分客户端到海外 CDN 的直连路径差,统一经服务端中转。
 * m3u8 响应会改写其中的清单/分片地址为本代理地址,使分片流量也走代理。
 */
@Slf4j
@Service
public class LiveProxyService {
    private final OkHttpClient okHttpClient;
    private final SubscriptionService subscriptionService;
    private final AppProperties appProperties;

    public LiveProxyService(SubscriptionService subscriptionService, AppProperties appProperties) {
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.subscriptionService = subscriptionService;
        this.appProperties = appProperties;
    }

    /**
     * 把目标流地址包装为本服务的代理地址。
     * 无请求上下文(关注状态后台刷新)等异常场景下原样返回目标地址。
     */
    public String buildProxyUrl(String targetUrl) {
        try {
            String token = subscriptionService.getCurrentToken();
            return ServletUriComponentsBuilder.fromCurrentRequest()
                    .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                    .replacePath("/live-proxy/" + token)
                    .replaceQuery("u=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8))
                    .build()
                    .toUriString();
        } catch (Exception e) {
            log.debug("build live proxy url failed: {}", targetUrl, e);
            return targetUrl;
        }
    }

    public void proxy(String target, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (target == null || target.isEmpty() || !Utils.isSafeExternalUrl(target)) {
            throw new BadRequestException("不安全的地址");
        }

        Request.Builder builder = new Request.Builder().url(target)
                .header("User-Agent", Constants.USER_AGENT);
        // 分片可能带 Range 请求,透传
        String range = request.getHeader("Range");
        if (range != null && !range.isEmpty()) {
            builder.header("Range", range);
        }

        try (Response upstream = okHttpClient.newCall(builder.build()).execute()) {
            response.setStatus(upstream.code());
            response.setHeader("Cache-Control", "no-store");
            String contentType = upstream.header("Content-Type", "");
            if (upstream.body() == null) {
                return;
            }
            if (contentType.contains("mpegurl") || target.contains(".m3u8")) {
                byte[] body = upstream.body().bytes();
                String text = new String(body, StandardCharsets.UTF_8);
                if (text.startsWith("#EXTM3U")) {
                    byte[] rewritten = rewrite(text, target).getBytes(StandardCharsets.UTF_8);
                    response.setContentType("application/vnd.apple.mpegurl");
                    response.setContentLength(rewritten.length);
                    response.getOutputStream().write(rewritten);
                    return;
                }
                // 不是 m3u8 内容则按普通响应写出
                response.setContentType(contentType.isEmpty() ? "application/octet-stream" : contentType);
                response.setContentLength(body.length);
                response.getOutputStream().write(body);
                return;
            }

            response.setContentType(contentType.isEmpty() ? "application/octet-stream" : contentType);
            long length = upstream.body().contentLength();
            if (length > 0 && length <= Integer.MAX_VALUE) {
                response.setContentLength((int) length);
            }
            try (InputStream in = upstream.body().byteStream()) {
                in.transferTo(response.getOutputStream());
            }
        } catch (IOException e) {
            log.warn("live proxy failed: {} {}", e.toString(), target);
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            }
        }
    }

    /** 把 m3u8 里出现的清单/分片地址(相对或绝对)解析后替换为代理地址 */
    private String rewrite(String text, String baseUrl) {
        URI base = URI.create(baseUrl);
        StringBuilder result = new StringBuilder(text.length() + 512);
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                result.append(trimmed);
            } else {
                try {
                    result.append(buildProxyUrl(base.resolve(trimmed).toString()));
                } catch (Exception e) {
                    log.debug("resolve m3u8 line failed: {}", trimmed);
                    result.append(trimmed);
                }
            }
            result.append('\n');
        }
        return result.toString();
    }
}
