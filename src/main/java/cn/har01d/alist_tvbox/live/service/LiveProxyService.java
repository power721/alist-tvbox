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
    private static final String KUGOU_MEDIA_HOST = ".liveplay.live.kugou.com";
    private static final String INKE_MEDIA_HOST = ".ikstatic.cn";
    private final OkHttpClient okHttpClient;
    private final SubscriptionService subscriptionService;
    private final AppProperties appProperties;
    private final KugouLiveService kugouLiveService;
    private final InkeService inkeService;

    public LiveProxyService(SubscriptionService subscriptionService, AppProperties appProperties,
                            @org.springframework.context.annotation.Lazy KugouLiveService kugouLiveService,
                            @org.springframework.context.annotation.Lazy InkeService inkeService) {
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.subscriptionService = subscriptionService;
        this.appProperties = appProperties;
        this.kugouLiveService = kugouLiveService;
        this.inkeService = inkeService;
    }

    /**
     * 把目标流地址包装为本服务的代理地址。
     * 无请求上下文(关注状态后台刷新)等异常场景下原样返回目标地址。
     */
    public String buildProxyUrl(String targetUrl) {
        try {
            String token = subscriptionService.getCurrentToken();
            return ServletUriComponentsBuilder.fromCurrentRequest()
                    .scheme(Utils.publicScheme(appProperties.isEnableHttps())) // nginx https
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

        if (isKugouStream(target)) {
            proxyWithRenew(target, response, "https://fanxing.kugou.com/",
                    () -> kugouLiveService.renewStreamUrl(kugouRoomId(target), kugouProtocol(target)));
            return;
        }
        if (isInkeStream(target)) {
            // 映客流 URL 无法反解主播身份,uid 由条目生成时追加在代理 URL 的 ink 参数里
            String uid = request.getParameter("ink");
            proxyWithRenew(target, response, "https://www.inke.cn/",
                    () -> uid == null ? null : inkeService.renewStreamUrl(uid));
            return;
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

    static boolean isKugouStream(String target) {
        return hostMatches(target, KUGOU_MEDIA_HOST);
    }

    static boolean isInkeStream(String target) {
        return hostMatches(target, INKE_MEDIA_HOST);
    }

    private static boolean hostMatches(String target, String suffix) {
        try {
            String host = URI.create(target).getHost();
            // 与各平台流地址校验同口径:裸域与子域都认
            return host != null && (host.endsWith(suffix) || host.equals(suffix.substring(1)));
        } catch (Exception e) {
            return false;
        }
    }

    private static String kugouProtocol(String target) {
        return target.contains(".m3u8") ? "hls" : "flv";
    }

    /**
     * 断流续租代理(酷狗/映客同症状同药方):直播直连断流即停(播放器对直播 progressive 流不重连),
     * 上游断开时经 renewer 重取流地址续写,与播放器的连接由本服务维持。FLV 重连会重发 9+4 字节头,
     * 续写前剥掉防双重 header;renewer 返回 null(下播/签名失败)或客户端断开时结束。
     * 实测部分平台房间单连接寿命随机(几十秒到几分钟断,房间仍在播),重签可立即续上——重试上限
     * 100 次(间隔 1s)覆盖数小时观看。
     */
    private void proxyWithRenew(String target, HttpServletResponse response, String referer,
                                java.util.function.Supplier<String> renewer) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(kugouProtocol(target).equals("hls") ? "application/vnd.apple.mpegurl" : "video/x-flv");
        response.setHeader("Cache-Control", "no-store");
        String url = target;
        boolean first = true;
        int renewals = 0;
        while (true) {
            Request request = new Request.Builder().url(url)
                    .header("User-Agent", Constants.USER_AGENT)
                    .header("Referer", referer)
                    .build();
            try (Response upstream = okHttpClient.newCall(request).execute()) {
                if (!upstream.isSuccessful() || upstream.body() == null) {
                    throw new IOException("upstream HTTP " + upstream.code());
                }
                if (!first && kugouProtocol(url).equals("flv")) {
                    skipFlvHeader(upstream.body().byteStream());
                }
                upstream.body().byteStream().transferTo(response.getOutputStream());
                // 上游正常 EOF = 直播结束
                return;
            } catch (IOException e) {
                boolean downstream = e.toString().contains("ClientAbortException") || e.getCause() instanceof IOException
                        && e.getCause().toString().contains("ClientAbortException");
                if (downstream || response.isCommitted() && e.toString().toLowerCase().contains("broken pipe")) {
                    // 播放器断开,无需续流
                    return;
                }
                String renewed = null;
                if (++renewals <= 100) {
                    renewed = renewer.get();
                }
                if (renewed == null) {
                    log.warn("live stream renew exhausted or offline: {} renewals={}", target, renewals);
                    return;
                }
                log.debug("live stream interrupted, renewing #{}: {}", renewals, e.toString());
                url = renewed;
                first = false;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 从流地址 token=0-{roomId}- 前缀反解房间号(续租重签用)。 */
    static String kugouRoomId(String target) {
        try {
            String query = URI.create(target).getQuery();
            if (query == null) {
                return null;
            }
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    String value = param.substring(6);
                    int start = value.indexOf('-') + 1;
                    int end = value.indexOf('-', start);
                    if (start > 0 && end > start) {
                        return value.substring(start, end);
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败按无续租处理
        }
        return null;
    }

    /** FLV 重连续写前剥掉重发的 9 字节签名头+4 字节 PreviousTagSize0,防双重 header。 */
    private void skipFlvHeader(InputStream in) throws IOException {
        byte[] header = new byte[13];
        int read = 0;
        while (read < header.length) {
            int n = in.read(header, read, header.length - read);
            if (n < 0) {
                throw new IOException("short flv header on renew");
            }
            read += n;
        }
    }

    /** 把 m3u8 里出现的清单/分片地址(相对或绝对)解析后替换为代理地址 */
    private String rewrite(String text, String baseUrl) {        URI base = URI.create(baseUrl);
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
