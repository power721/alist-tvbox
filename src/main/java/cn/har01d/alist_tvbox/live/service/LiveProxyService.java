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
import org.springframework.beans.factory.ObjectProvider;
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
    private static final String LOOK_MEDIA_HOST = ".live.126.net";
    private static final String YY_MEDIA_HOST = ".yy.com";
    private final OkHttpClient okHttpClient;
    private final SubscriptionService subscriptionService;
    private final AppProperties appProperties;
    // 四平台服务反向依赖本服务成环,以 ObjectProvider 延迟化解:
    // @Lazy 类代理需运行期生成 CGLIB 类,native image 下无反射注册直接启动失败
    private final ObjectProvider<KugouLiveService> kugouLiveService;
    private final ObjectProvider<InkeService> inkeService;
    private final ObjectProvider<LookLiveService> lookService;
    private final ObjectProvider<YyService> yyService;

    public LiveProxyService(SubscriptionService subscriptionService, AppProperties appProperties,
                            ObjectProvider<KugouLiveService> kugouLiveService,
                            ObjectProvider<InkeService> inkeService,
                            ObjectProvider<LookLiveService> lookService,
                            ObjectProvider<YyService> yyService) {
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.subscriptionService = subscriptionService;
        this.appProperties = appProperties;
        this.kugouLiveService = kugouLiveService;
        this.inkeService = inkeService;
        this.lookService = lookService;
        this.yyService = yyService;
    }

    /** dual 代理模式(直连优先双线路):各平台 detail 据此产出「直连+代理」两条线路。 */
    public boolean isDualProxyMode() {
        return appProperties != null && "dual".equals(appProperties.getLiveProxyMode());
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
                    () -> kugouLiveService.getObject().renewStreamUrl(kugouRoomId(target), kugouProtocol(target)));
            return;
        }
        if (isInkeStream(target)) {
            // 映客流 URL 无法反解主播身份,uid 由条目生成时追加在代理 URL 的 ink 参数里
            String uid = request.getParameter("ink");
            proxyWithRenew(target, response, "https://www.inke.cn/",
                    () -> uid == null ? null : inkeService.getObject().renewStreamUrl(uid));
            return;
        }
        if (isLookStream(target) && request.getParameter("look") != null) {
            // LOOK 地址含场次 hash,换场次/重推后旧地址 404(detail 15 分钟缓存放大):
            // 清单请求每次重取房间当前地址(HLS 天然续租);分片经 rewrite 生成的代理地址不带
            // look 参数,落到下方通用转发,不产生多余重签
            String roomId = request.getParameter("look");
            boolean hls = target.contains(".m3u8");
            String fresh = lookService.getObject().renewStreamUrl(roomId, hls);
            String url = fresh == null ? target : fresh;
            if (hls) {
                proxyManifest(url, response, "https://look.163.com/");
            } else {
                proxyWithRenew(url, response, "https://look.163.com/",
                        () -> lookService.getObject().renewStreamUrl(roomId, false));
            }
            return;
        }
        if (isYyStream(target) && request.getParameter("yy") != null) {
            // YY 流地址签名 t 租约仅约 10 分钟(detail 15 分钟缓存内必然过期):
            // 每次连接先重取当前地址,断流再续租;HLS 清单每次重取,分片独立签名即刻有效,
            // 分片经 rewrite 生成的代理地址不带 yy 参数,落到下方通用转发
            String roomId = request.getParameter("yy");
            if (target.contains(".m3u8")) {
                String rate = request.getParameter("yyr");
                String fresh = rate == null ? null : yyService.getObject().renewHlsUrl(roomId, rate);
                proxyManifest(fresh == null ? target : fresh, response, "https://wap.yy.com/");
            } else {
                String gear = request.getParameter("yyq");
                String fresh = yyService.getObject().renewStreamUrl(roomId, gear);
                proxyWithRenew(fresh == null ? target : fresh, response, "https://www.yy.com/",
                        () -> yyService.getObject().renewStreamUrl(roomId, gear));
            }
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
                writeManifest(upstream, response, target);
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

    /** 拉取 m3u8 清单并写出(分片地址重写为本代理地址),供通用转发与 LOOK 重取清单两条路径复用。 */
    private void proxyManifest(String url, HttpServletResponse response, String referer) throws IOException {
        Request.Builder builder = new Request.Builder().url(url)
                .header("User-Agent", Constants.USER_AGENT);
        if (referer != null) {
            builder.header("Referer", referer);
        }
        try (Response upstream = okHttpClient.newCall(builder.build()).execute()) {
            response.setStatus(upstream.code());
            response.setHeader("Cache-Control", "no-store");
            if (upstream.body() == null) {
                return;
            }
            writeManifest(upstream, response, url);
        } catch (IOException e) {
            log.warn("live manifest proxy failed: {} {}", e.toString(), url);
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            }
        }
    }

    private void writeManifest(Response upstream, HttpServletResponse response, String baseUrl) throws IOException {
        String contentType = upstream.header("Content-Type", "");
        byte[] body = upstream.body().bytes();
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.startsWith("#EXTM3U")) {
            byte[] rewritten = rewrite(text, baseUrl).getBytes(StandardCharsets.UTF_8);
            response.setContentType("application/vnd.apple.mpegurl");
            response.setContentLength(rewritten.length);
            response.getOutputStream().write(rewritten);
            return;
        }
        // 不是 m3u8 内容则按普通响应写出
        response.setContentType(contentType.isEmpty() ? "application/octet-stream" : contentType);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    static boolean isKugouStream(String target) {
        return hostMatches(target, KUGOU_MEDIA_HOST);
    }

    static boolean isInkeStream(String target) {
        return hostMatches(target, INKE_MEDIA_HOST);
    }

    static boolean isLookStream(String target) {
        return hostMatches(target, LOOK_MEDIA_HOST);
    }

    static boolean isYyStream(String target) {
        return hostMatches(target, YY_MEDIA_HOST);
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
     * 上游断开两种形态都要续:RST/超时抛 IOException 走 catch;CDN 优雅关闭(FIN)则 transferTo
     * 正常返回——直播 FLV 不存在"正常结束",EOF 同样必须经 renewer 复核(实测单连接 23 分钟被
     * FIN 截断,无任何 warn 即旧版误判下播直接结束);只有 HLS 清单是短响应,EOF 即完成。
     */
    void proxyWithRenew(String target, HttpServletResponse response, String referer,
                        java.util.function.Supplier<String> renewer) throws IOException {
        boolean hls = kugouProtocol(target).equals("hls");
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(hls ? "application/vnd.apple.mpegurl" : "video/x-flv");
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
                if (!first && !hls) {
                    skipFlvHeader(upstream.body().byteStream());
                }
                upstream.body().byteStream().transferTo(response.getOutputStream());
                if (hls) {
                    // 清单为短响应,EOF 即完成
                    return;
                }
                // FLV 流"正常 EOF"=上游掐连接(房间多半还在播)或真下播,一律经 renewer 复核
                String renewed = renewOnce(target, renewer, ++renewals);
                if (renewed == null) {
                    return;
                }
                url = renewed;
                first = false;
                if (!sleepInterruptibly()) {
                    return;
                }
            } catch (IOException e) {
                boolean downstream = e.toString().contains("ClientAbortException") || e.getCause() instanceof IOException
                        && e.getCause().toString().contains("ClientAbortException");
                if (downstream || response.isCommitted() && e.toString().toLowerCase().contains("broken pipe")) {
                    // 播放器断开,无需续流
                    return;
                }
                String renewed = renewOnce(target, renewer, ++renewals);
                if (renewed == null) {
                    return;
                }
                log.debug("live stream interrupted, renewing #{}: {}", renewals, e.toString());
                url = renewed;
                first = false;
                if (!sleepInterruptibly()) {
                    return;
                }
            }
        }
    }

    /** 续租一轮:超限或 renewer 返回 null(下播/重签失败)返回 null,调用方终止。 */
    private String renewOnce(String target, java.util.function.Supplier<String> renewer, int renewal) {
        String renewed = renewal <= 100 ? renewer.get() : null;
        if (renewed == null) {
            log.warn("live stream ended or renew exhausted: {} renewals={}", target, renewal);
        } else {
            log.debug("live stream renewing #{}: {}", renewal, target);
        }
        return renewed;
    }

    /** 续租间隔 1s;线程被中断返回 false(调用方终止)。 */
    private boolean sleepInterruptibly() {
        try {
            Thread.sleep(1000);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
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
