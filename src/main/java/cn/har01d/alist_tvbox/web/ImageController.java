package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.PlayUrl;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.ProxyService;
import cn.har01d.alist_tvbox.util.Constants;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/images")
public class ImageController {

    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final int COPY_BUFFER_BYTES = 16 * 1024;
    private static final int IMAGE_PREFIX_BYTES = 1024;
    private static final long TOTAL_BUDGET_MS = 15_000;
    private static final long DNS_TIMEOUT_MS = 2_000;
    private static final Semaphore REGISTERED_LIMIT = new Semaphore(16);
    private static final Semaphore ANONYMOUS_LIMIT = new Semaphore(8);
    private static final ThreadPoolExecutor DNS_EXECUTOR = new ThreadPoolExecutor(
            16, 16, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
        Thread thread = new Thread(runnable, "image-dns");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledExecutorService CLEANUP_EXECUTOR = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "image-cleanup");
                thread.setDaemon(true);
                return thread;
            });
    private static final MediaType IMAGE_SVG_XML = MediaType.parseMediaType("image/svg+xml");

    private final OkHttpClient httpClient;
    private final ProxyService proxyService;
    private final HostResolver hostResolver;

    public ImageController(ProxyService proxyService) {
        this(proxyService, new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(7))
                .callTimeout(Duration.ofSeconds(12))
                .build(), host -> Arrays.asList(InetAddress.getAllByName(host)));
    }

    ImageController(ProxyService proxyService, OkHttpClient httpClient, HostResolver hostResolver) {
        this.proxyService = proxyService;
        this.httpClient = httpClient;
        this.hostResolver = hostResolver;
    }

    @GetMapping(value = "", produces = "image/webp")
    public ResponseEntity<StreamingResponseBody> getImage(String url, String fallback, String referer) {
        return proxyImage(url, fallback, referer, false, false);
    }

    private ResponseEntity<StreamingResponseBody> proxyImage(
            String primaryUrl, String fallback, String referer,
            boolean registeredTarget, boolean allowPrivate) {
        Semaphore limit = registeredTarget ? REGISTERED_LIMIT : ANONYMOUS_LIMIT;
        if (!limit.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image proxy is busy");
        }
        boolean streamingOwnsPermit = false;
        RequestBudget budget = RequestBudget.start(TOTAL_BUDGET_MS);
        try {
            ValidatedTarget primary;
            try {
                primary = validateTarget(primaryUrl, allowPrivate, budget);
            } catch (ImageFetchException e) {
                throw new BadRequestException("Invalid image URL");
            }
            ImagePayload payload;
            try {
                payload = fetchImage(primary, referer, registeredTarget, budget);
            } catch (ImageFetchException primaryFailure) {
                ValidatedTarget alternate = validateFallback(fallback, primary.uri(), budget);
                if (alternate == null) {
                    throw primaryFailure;
                }
                log.debug("image fetch failed, trying alternate cover");
                payload = fetchImage(alternate, referer, false, budget);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(payload.contentType());
            headers.setContentLength(payload.length());
            headers.set("X-Content-Type-Options", "nosniff");
            if (IMAGE_SVG_XML.equals(payload.contentType())) {
                headers.set("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'");
            }
            RequestLease lease = new RequestLease(payload, limit);
            streamingOwnsPermit = true;
            ScheduledFuture<?> cleanup;
            try {
                cleanup = CLEANUP_EXECUTOR.schedule(
                        lease::close, budget.remainingMillis(), TimeUnit.MILLISECONDS);
            } catch (RuntimeException e) {
                lease.close();
                throw e;
            }
            StreamingResponseBody body = output -> {
                try (InputStream input = lease.open()) {
                    copyToClient(input, output, budget);
                } finally {
                    cleanup.cancel(false);
                    lease.close();
                }
            };
            return ResponseEntity.ok().headers(headers).body(body);
        } catch (ImageFetchException e) {
            log.debug("image proxy request failed");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Image upstream unavailable");
        } finally {
            if (!streamingOwnsPermit) {
                limit.release();
            }
        }
    }

    private ValidatedTarget validateFallback(String fallback, URI primary, RequestBudget budget) {
        if (StringUtils.isBlank(fallback)) {
            return null;
        }
        try {
            ValidatedTarget alternate = validateTarget(fallback, false, budget);
            return alternate.uri().equals(primary) ? null : alternate;
        } catch (ImageFetchException e) {
            return null;
        }
    }

    private ImagePayload fetchImage(ValidatedTarget initial, String referer,
                                    boolean registeredTarget, RequestBudget budget) {
        ValidatedTarget current = initial;
        boolean privateOrigin = current.hasPrivateAddress();
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            budget.requireRemaining();
            try (Response response = execute(current, referer, budget)) {
                if (response.isRedirect()) {
                    if (redirect == MAX_REDIRECTS) {
                        throw new ImageFetchException();
                    }
                    String location = response.header(HttpHeaders.LOCATION);
                    if (StringUtils.isBlank(location)) {
                        throw new ImageFetchException();
                    }
                    URI next;
                    try {
                        next = current.uri().resolve(new URI(location));
                    } catch (IllegalArgumentException | URISyntaxException e) {
                        throw new ImageFetchException();
                    }
                    boolean allowPrivate = registeredTarget && privateOrigin
                            && sameAuthority(initial.uri(), next);
                    current = validateTarget(next.toString(), allowPrivate, budget);
                    continue;
                }
                if (!response.isSuccessful()) {
                    throw new ImageFetchException();
                }
                return spoolImage(response, budget);
            } catch (IOException | IllegalArgumentException e) {
                throw new ImageFetchException();
            }
        }
        throw new ImageFetchException();
    }

    private Response execute(ValidatedTarget target, String referer, RequestBudget budget) throws IOException {
        Dns pinnedDns = hostname -> {
            if (!target.host().equalsIgnoreCase(hostname)) {
                throw new UnknownHostException();
            }
            return target.addresses();
        };
        OkHttpClient client = httpClient.newBuilder()
                .dns(pinnedDns)
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(Duration.ofMillis(budget.remainingMillis()))
                .build();
        Request.Builder request = new Request.Builder()
                .url(target.uri().toString())
                .header(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
        String host = target.host().toLowerCase(Locale.ROOT);
        String path = StringUtils.defaultString(target.uri().getPath());
        if (hostMatches(host, "douban.com") || hostMatches(host, "doubanio.com")) {
            request.header(HttpHeaders.REFERER, "https://movie.douban.com/");
        } else if (hostMatches(host, "ytimg.com")) {
            request.header(HttpHeaders.REFERER, "https://www.youtube.com/");
        } else if (hostMatches(host, "netease.com")) {
            request.header(HttpHeaders.REFERER, "https://cc.163.com/");
        } else if (hostMatches(host, "hdslb.com")) {
            request.header(HttpHeaders.REFERER, "https://live.bilibili.com/");
        } else if (path.contains("/Images/Primary")) {
            if (StringUtils.isNotBlank(referer)) {
                request.header(HttpHeaders.REFERER, referer);
            }
            request.header(HttpHeaders.USER_AGENT, Constants.EMBY_USER_AGENT);
        }
        return client.newCall(request.build()).execute();
    }

    private ImagePayload spoolImage(Response response, RequestBudget budget) {
        ResponseBody responseBody = response.body();
        if (responseBody == null || responseBody.contentLength() > MAX_IMAGE_BYTES) {
            throw new ImageFetchException();
        }
        Path temp = null;
        boolean complete = false;
        try {
            temp = Files.createTempFile("alist-tvbox-image-", ".tmp");
            byte[] prefix = new byte[IMAGE_PREFIX_BYTES];
            int prefixLength = 0;
            long total = 0;
            try (InputStream input = responseBody.byteStream();
                 OutputStream output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    budget.requireRemaining();
                    total += read;
                    if (total > MAX_IMAGE_BYTES) {
                        throw new ImageFetchException();
                    }
                    if (prefixLength < prefix.length) {
                        int copy = Math.min(read, prefix.length - prefixLength);
                        System.arraycopy(buffer, 0, prefix, prefixLength, copy);
                        prefixLength += copy;
                    }
                    output.write(buffer, 0, read);
                }
            }
            if (total == 0) {
                throw new ImageFetchException();
            }
            MediaType declaredType = parseContentType(response.header(HttpHeaders.CONTENT_TYPE));
            MediaType detectedType = detectImageType(Arrays.copyOf(prefix, prefixLength), declaredType);
            complete = true;
            return new ImagePayload(temp, total, detectedType);
        } catch (IOException | IllegalArgumentException e) {
            throw new ImageFetchException();
        } finally {
            if (!complete && temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private ValidatedTarget validateTarget(String url, boolean allowPrivate, RequestBudget budget) {
        if (StringUtils.isBlank(url)) {
            throw new ImageFetchException();
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || StringUtils.isBlank(host) || uri.getUserInfo() != null) {
                throw new ImageFetchException();
            }
            if ("metadata.google.internal".equalsIgnoreCase(host)) {
                throw new ImageFetchException();
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            List<InetAddress> addresses = resolveHost(host, budget);
            if (addresses == null || addresses.isEmpty()) {
                throw new ImageFetchException();
            }
            boolean hasPrivateAddress = false;
            for (InetAddress address : addresses) {
                if (address == null || isAlwaysBlocked(address)) {
                    throw new ImageFetchException();
                }
                boolean publicAddress = isPublicAddress(address);
                if (!publicAddress && (!allowPrivate || !isLanAddress(address))) {
                    throw new ImageFetchException();
                }
                hasPrivateAddress |= !publicAddress;
            }
            return new ValidatedTarget(uri, host, List.copyOf(addresses), hasPrivateAddress);
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new ImageFetchException();
        }
    }

    private List<InetAddress> resolveHost(String host, RequestBudget budget) {
        Future<List<InetAddress>> future;
        try {
            future = DNS_EXECUTOR.submit(() -> hostResolver.resolve(host));
        } catch (RejectedExecutionException e) {
            throw new ImageFetchException();
        }
        try {
            long timeout = Math.min(DNS_TIMEOUT_MS, budget.remainingMillis());
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ImageFetchException();
        } catch (Exception e) {
            throw new ImageFetchException();
        } finally {
            future.cancel(true);
        }
    }

    private static boolean isAlwaysBlocked(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress();
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (isAlwaysBlocked(address) || address.isSiteLocalAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if (first == 0 || first == 10 || first == 127 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && (second == 0 || second == 168))
                    || (first == 198 && (second == 18 || second == 19))) {
                return false;
            }
            return true;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            return (bytes[0] & 0xe0) == 0x20;
        }
        return false;
    }

    private static boolean isLanAddress(InetAddress address) {
        if (address instanceof Inet4Address) {
            return address.isSiteLocalAddress();
        }
        byte[] bytes = address.getAddress();
        return address instanceof Inet6Address && bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static boolean sameAuthority(URI first, URI second) {
        return first.getHost() != null && second.getHost() != null
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean hostMatches(String host, String suffix) {
        return host.equals(suffix) || host.endsWith("." + suffix);
    }

    private static MediaType parseContentType(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static MediaType detectImageType(byte[] body, MediaType declaredType) {
        if (body.length >= 3 && (body[0] & 0xff) == 0xff && (body[1] & 0xff) == 0xd8
                && (body[2] & 0xff) == 0xff) {
            return MediaType.IMAGE_JPEG;
        }
        if (body.length >= 8 && (body[0] & 0xff) == 0x89 && body[1] == 0x50 && body[2] == 0x4e
                && body[3] == 0x47 && body[4] == 0x0d && body[5] == 0x0a && body[6] == 0x1a && body[7] == 0x0a) {
            return MediaType.IMAGE_PNG;
        }
        if (body.length >= 6 && body[0] == 'G' && body[1] == 'I' && body[2] == 'F'
                && body[3] == '8' && (body[4] == '7' || body[4] == '9') && body[5] == 'a') {
            return MediaType.IMAGE_GIF;
        }
        if (body.length >= 12 && body[0] == 'R' && body[1] == 'I' && body[2] == 'F'
                && body[3] == 'F' && body[8] == 'W' && body[9] == 'E' && body[10] == 'B' && body[11] == 'P') {
            return MediaType.parseMediaType("image/webp");
        }
        if (declaredType != null && IMAGE_SVG_XML.isCompatibleWith(declaredType) && looksLikeSvg(body)) {
            return IMAGE_SVG_XML;
        }
        throw new ImageFetchException();
    }

    private static boolean looksLikeSvg(byte[] body) {
        int offset = body.length >= 3 && (body[0] & 0xff) == 0xef && (body[1] & 0xff) == 0xbb
                && (body[2] & 0xff) == 0xbf ? 3 : 0;
        while (offset < body.length && Character.isWhitespace(body[offset])) {
            offset++;
        }
        int length = Math.min(body.length - offset, IMAGE_PREFIX_BYTES);
        if (length <= 0) {
            return false;
        }
        String prefix = new String(body, offset, length, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return prefix.startsWith("<svg") || (prefix.startsWith("<?xml") && prefix.contains("<svg"));
    }

    private static void copyToClient(InputStream input, OutputStream output, RequestBudget budget) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        int read;
        while ((read = input.read(buffer)) != -1) {
            budget.requireRemaining();
            output.write(buffer, 0, read);
        }
    }

    @GetMapping(value = "/{id}", produces = "image/webp")
    public ResponseEntity<StreamingResponseBody> getImage(@PathVariable int id) {
        PlayUrl url = proxyService.getPlayUrl(id);
        boolean registeredImage = url.getSite() == ProxyService.IMAGE_PUBLIC_SITE
                || url.getSite() == ProxyService.IMAGE_PRIVATE_SITE;
        if (!registeredImage || url.getTime() == null || url.getTime().isBefore(java.time.Instant.now())) {
            throw new BadRequestException("Invalid image URL");
        }
        return proxyImage(url.getPath(), null, url.getReferer(), true,
                url.getSite() == ProxyService.IMAGE_PRIVATE_SITE);
    }

    @FunctionalInterface
    interface HostResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    private record ValidatedTarget(URI uri, String host, List<InetAddress> addresses, boolean hasPrivateAddress) {
    }

    private record ImagePayload(Path path, long length, MediaType contentType) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }

    private record RequestBudget(long deadlineNanos) {
        static RequestBudget start(long durationMillis) {
            return new RequestBudget(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis));
        }

        long remainingMillis() {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new ImageFetchException();
            }
            return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        }

        void requireRemaining() {
            remainingMillis();
        }
    }

    private static final class RequestLease {
        private final ImagePayload payload;
        private final Semaphore limit;
        private final AtomicBoolean closed = new AtomicBoolean();
        private InputStream input;

        private RequestLease(ImagePayload payload, Semaphore limit) {
            this.payload = payload;
            this.limit = limit;
        }

        synchronized InputStream open() throws IOException {
            if (closed.get()) {
                throw new IOException("image request expired");
            }
            input = Files.newInputStream(payload.path());
            return input;
        }

        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (this) {
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            try {
                payload.close();
            } catch (IOException ignored) {
            } finally {
                limit.release();
            }
        }
    }

    private static final class ImageFetchException extends RuntimeException {
    }
}
