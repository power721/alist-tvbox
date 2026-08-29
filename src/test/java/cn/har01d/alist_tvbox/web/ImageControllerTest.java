package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.PlayUrl;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.ProxyService;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImageControllerTest {

    private static final byte[] JPEG = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};

    @Test
    void fallsBackToAlternateCoverWhenPrimaryImageFails() throws Exception {
        TestContext context = context();
        String primary = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        String fallback = "https://img9.doubanio.com/view/photo/m_ratio_poster/public/p1.jpg";
        context.enqueue(primary, 502, new byte[0], MediaType.TEXT_PLAIN_VALUE);
        context.enqueue(fallback, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);

        assertArrayEquals(JPEG, read(context.controller().getImage(primary, fallback, null)));
        assertEquals(List.of(primary, fallback), context.requests());
    }

    @Test
    void fallsBackWhenPrimaryReturnsChallengeHtml() throws Exception {
        TestContext context = context();
        String primary = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        String fallback = "https://img9.doubanio.com/view/photo/m_ratio_poster/public/p1.jpg";
        context.enqueue(primary, 200, "<html>challenge</html>".getBytes(), MediaType.TEXT_HTML_VALUE);
        context.enqueue(fallback, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);

        assertArrayEquals(JPEG, read(context.controller().getImage(primary, fallback, null)));
    }

    @Test
    void fallsBackWhenPrimaryClaimsJpegButReturnsHtml() throws Exception {
        TestContext context = context();
        String primary = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        String fallback = "https://img9.doubanio.com/view/photo/m_ratio_poster/public/p1.jpg";
        context.enqueue(primary, 200, "<html>challenge</html>".getBytes(), MediaType.IMAGE_JPEG_VALUE);
        context.enqueue(fallback, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);

        assertArrayEquals(JPEG, read(context.controller().getImage(primary, fallback, null)));
    }

    @Test
    void fallsBackWhenPrimaryBodyIsEmpty() throws Exception {
        TestContext context = context();
        String primary = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        String fallback = "https://img9.doubanio.com/view/photo/m_ratio_poster/public/p1.jpg";
        context.enqueue(primary, 200, new byte[0], MediaType.IMAGE_JPEG_VALUE);
        context.enqueue(fallback, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);

        assertArrayEquals(JPEG, read(context.controller().getImage(primary, fallback, null)));
    }

    @Test
    void fallsBackWhenPrimaryBodyExceedsLimit() throws Exception {
        TestContext context = context();
        String primary = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        String fallback = "https://img9.doubanio.com/view/photo/m_ratio_poster/public/p1.jpg";
        byte[] oversized = new byte[ImageController.MAX_IMAGE_BYTES + 1];
        oversized[0] = (byte) 0xff;
        oversized[1] = (byte) 0xd8;
        oversized[2] = (byte) 0xff;
        context.enqueue(primary, 200, oversized, MediaType.IMAGE_JPEG_VALUE);
        context.enqueue(fallback, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);

        assertArrayEquals(JPEG, read(context.controller().getImage(primary, fallback, null)));
    }

    @Test
    void acceptsJpegMagicWhenContentTypeIsWrong() throws Exception {
        TestContext context = context();
        String primary = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        context.enqueue(primary, 200, JPEG, MediaType.APPLICATION_OCTET_STREAM_VALUE);

        ResponseEntity<StreamingResponseBody> result = context.controller().getImage(primary, null, null);

        assertArrayEquals(JPEG, read(result));
        assertEquals(MediaType.IMAGE_JPEG, result.getHeaders().getContentType());
    }

    @Test
    void followsValidatedRedirect() throws Exception {
        TestContext context = context();
        String primary = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        String redirected = "https://image.tmdb.org/t/p/w300/poster.jpg";
        context.enqueue(primary, 302, new byte[0], MediaType.TEXT_PLAIN_VALUE,
                Map.of(HttpHeaders.LOCATION, redirected));
        context.enqueue(redirected, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);

        assertArrayEquals(JPEG, read(context.controller().getImage(primary, null, null)));
        assertEquals(List.of(primary, redirected), context.requests());
    }

    @Test
    void unsafeRedirectFallsBackWithoutRequestingRedirectTarget() throws Exception {
        TestContext context = context();
        String primary = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        String fallback = "https://img9.doubanio.com/view/photo/m_ratio_poster/public/p1.jpg";
        context.enqueue(primary, 302, new byte[0], MediaType.TEXT_PLAIN_VALUE,
                Map.of(HttpHeaders.LOCATION, "http://127.0.0.1/internal"));
        context.enqueue(fallback, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);

        assertArrayEquals(JPEG, read(context.controller().getImage(primary, fallback, null)));
        assertEquals(List.of(primary, fallback), context.requests());
    }

    @Test
    void malformedRedirectFallsBack() throws Exception {
        TestContext context = context();
        String primary = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        String fallback = "https://img9.doubanio.com/view/photo/m_ratio_poster/public/p1.jpg";
        context.enqueue(primary, 302, new byte[0], MediaType.TEXT_PLAIN_VALUE,
                Map.of(HttpHeaders.LOCATION, "http://["));
        context.enqueue(fallback, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);

        assertArrayEquals(JPEG, read(context.controller().getImage(primary, fallback, null)));
    }

    @Test
    void malformedContentTypeFallsBackForNonImageBody() throws Exception {
        TestContext context = context();
        String primary = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        String fallback = "https://img9.doubanio.com/view/photo/m_ratio_poster/public/p1.jpg";
        context.enqueue(primary, 200, "not an image".getBytes(), "%%%invalid");
        context.enqueue(fallback, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);

        assertArrayEquals(JPEG, read(context.controller().getImage(primary, fallback, null)));
    }

    @Test
    void blocksPrivateAddressOnAnonymousUrlEndpoint() {
        TestContext context = context();

        assertThrows(BadRequestException.class,
                () -> context.controller().getImage("http://10.0.0.8/poster.jpg", null, null));
        assertTrue(context.requests().isEmpty());
    }

    @Test
    void registeredImageIdCanUsePrivateNasAddress() throws Exception {
        TestContext context = context();
        String url = "http://10.0.0.8/Images/Primary/1";
        when(context.proxyService().getPlayUrl(42))
                .thenReturn(new PlayUrl(ProxyService.IMAGE_PRIVATE_SITE, url,
                        "http://10.0.0.8/", Instant.now().plusSeconds(60)));
        context.enqueue(url, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);

        assertArrayEquals(JPEG, read(context.controller().getImage(42)));
    }

    @Test
    void registeredPublicImageCannotRebindToPrivateAddress() {
        TestContext context = context();
        String url = "http://10.0.0.8/Images/Primary/1";
        when(context.proxyService().getPlayUrl(42))
                .thenReturn(new PlayUrl(ProxyService.IMAGE_PUBLIC_SITE, url,
                        "http://10.0.0.8/", Instant.now().plusSeconds(60)));

        assertThrows(BadRequestException.class, () -> context.controller().getImage(42));
        assertTrue(context.requests().isEmpty());
    }

    @Test
    void regularPlayUrlCannotBeUsedAsRegisteredImage() {
        TestContext context = context();
        String url = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        when(context.proxyService().getPlayUrl(42))
                .thenReturn(new PlayUrl(0, url, Instant.now().plusSeconds(60)));

        assertThrows(BadRequestException.class, () -> context.controller().getImage(42));
        assertTrue(context.requests().isEmpty());
    }

    @Test
    void stripsUntrustedUpstreamResponseHeaders() throws Exception {
        TestContext context = context();
        String primary = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        context.enqueue(primary, 200, JPEG, MediaType.IMAGE_JPEG_VALUE, Map.of(
                HttpHeaders.SET_COOKIE, "session=attacker",
                "Clear-Site-Data", "*",
                "X-Accel-Redirect", "/internal/secret"));

        ResponseEntity<StreamingResponseBody> result = context.controller().getImage(primary, null, null);

        assertFalse(result.getHeaders().headerSet().contains(HttpHeaders.SET_COOKIE));
        assertFalse(result.getHeaders().headerSet().contains("Clear-Site-Data"));
        assertFalse(result.getHeaders().headerSet().contains("X-Accel-Redirect"));
        assertArrayEquals(JPEG, read(result));
    }

    @Test
    void outboundPermitIsHeldUntilStreamingCompletes() throws Exception {
        TestContext context = context();
        String primary = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        for (int i = 0; i < 8; i++) {
            context.enqueue(primary, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);
        }
        List<ResponseEntity<StreamingResponseBody>> held = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                held.add(context.controller().getImage(primary, null, null));
            }
            ResponseStatusException busy = assertThrows(ResponseStatusException.class,
                    () -> context.controller().getImage(primary, null, null));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, busy.getStatusCode());
            assertEquals(8, context.resolverCalls().get(), "busy 请求不得进入 URI/DNS 解析");

            String registered = "http://10.0.0.8/Images/Primary/1";
            when(context.proxyService().getPlayUrl(42))
                    .thenReturn(new PlayUrl(ProxyService.IMAGE_PRIVATE_SITE, registered,
                            "http://10.0.0.8/", Instant.now().plusSeconds(60)));
            context.enqueue(registered, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);
            assertArrayEquals(JPEG, read(context.controller().getImage(42)),
                    "匿名容量耗尽不应阻塞已登记图片");
        } finally {
            for (ResponseEntity<StreamingResponseBody> response : held) {
                read(response);
            }
        }
    }

    @Test
    void resolvesEachHopOnceBeforePinnedConnection() throws Exception {
        TestContext context = context();
        String primary = "https://media.themoviedb.org/t/p/w300/poster.jpg";
        context.enqueue(primary, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);

        assertArrayEquals(JPEG, read(context.controller().getImage(primary, null, null)));
        assertEquals(1, context.resolverCalls().get());
    }

    @Test
    void mockMvcDecodesNestedImageUrlExactlyOnce() throws Exception {
        TestContext context = context();
        String target = "https://media.themoviedb.org/t/p/w300/poster.jpg?x=1&y=a%20b";
        context.enqueue(target, 200, JPEG, MediaType.IMAGE_JPEG_VALUE);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(context.controller()).build();

        mockMvc.perform(get("/images").queryParam("url", target))
                .andExpect(status().isOk());

        assertEquals(List.of(target), context.requests());
    }

    private static byte[] read(ResponseEntity<StreamingResponseBody> response) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        return output.toByteArray();
    }

    private TestContext context() {
        StubInterceptor interceptor = new StubInterceptor();
        ProxyService proxyService = mock(ProxyService.class);
        AtomicInteger resolverCalls = new AtomicInteger();
        ImageController.HostResolver resolver = host -> {
            resolverCalls.incrementAndGet();
            if (host.matches("[0-9.]+") || host.contains(":")) {
                return List.of(InetAddress.getByName(host));
            }
            return List.of(InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34}));
        };
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        ImageController controller = new ImageController(proxyService, client, resolver);
        return new TestContext(controller, proxyService, interceptor, resolverCalls);
    }

    private record TestContext(ImageController controller, ProxyService proxyService,
                               StubInterceptor interceptor, AtomicInteger resolverCalls) {
        void enqueue(String url, int status, byte[] body, String contentType) {
            enqueue(url, status, body, contentType, Map.of());
        }

        void enqueue(String url, int status, byte[] body, String contentType, Map<String, String> headers) {
            interceptor.enqueue(url, new StubResponse(status, body, contentType, headers));
        }

        List<String> requests() {
            return interceptor.requests();
        }
    }

    private record StubResponse(int status, byte[] body, String contentType, Map<String, String> headers) {
    }

    private static final class StubInterceptor implements Interceptor {
        private final Map<String, Deque<StubResponse>> responses = new LinkedHashMap<>();
        private final List<String> requests = new ArrayList<>();

        synchronized void enqueue(String url, StubResponse response) {
            responses.computeIfAbsent(url, ignored -> new ArrayDeque<>()).add(response);
        }

        synchronized List<String> requests() {
            return List.copyOf(requests);
        }

        @Override
        public synchronized Response intercept(Chain chain) throws IOException {
            String url = chain.request().url().toString();
            requests.add(url);
            Deque<StubResponse> queue = responses.get(url);
            if (queue == null || queue.isEmpty()) {
                throw new IOException("No stub response");
            }
            StubResponse stub = queue.removeFirst();
            Response.Builder response = new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(stub.status())
                    .message("stub")
                    .body(ResponseBody.create(stub.body(), okhttp3.MediaType.get("application/octet-stream")));
            if (stub.contentType() != null) {
                response.header(HttpHeaders.CONTENT_TYPE, stub.contentType());
            }
            stub.headers().forEach(response::header);
            return response.build();
        }
    }
}
