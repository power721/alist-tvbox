package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaProbeEngineTest {
    ObjectMapper mapper = new ObjectMapper();
    HttpServer server;
    ExecutorService workers;
    String origin;
    MediaProbeEngine engine;
    MediaNetworkPolicy policy;
    MediaBudgetStore budgets;
    AtomicInteger requests = new AtomicInteger();
    HttpHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try { handler.handle(exchange); } finally { exchange.close(); }
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        policy = new MediaNetworkPolicy(mapper, true, origin, true);
        budgets = mock(MediaBudgetStore.class);
        engine = new MediaProbeEngine(policy, mapper, budgets);
    }

    @AfterEach
    void close() {
        engine.close();
        server.stop(0);
        workers.shutdownNow();
    }

    @Test
    void hlsUsesSelectedVariantAndSamplesFirstMiddleLastSegmentsWithProvenance() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        handler = exchange -> {
            seen.add(exchange.getRequestURI().getPath());
            assertEquals("same-origin-secret", exchange.getRequestHeaders().getFirst("Cookie"));
            switch (exchange.getRequestURI().getPath()) {
                case "/master" -> send(exchange, 200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nnested/list\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=2000\nunused\n");
                case "/nested/list" -> send(exchange, 200, playlist(9));
                default -> send(exchange, 200, ts());
            }
        };
        var report = run("/master", Map.of("Cookie", "same-origin-secret"));
        assertEquals("sample_checked", report.path("verdict").asText());
        for (String id : List.of("M01", "M02", "M03", "M05")) assertNode(report, id, "pass");
        assertEquals(List.of("/master", "/nested/list", "/nested/s0", "/nested/s4", "/nested/s8"), seen);
        assertEquals(2, node(report, "M02").path("publishedVariants").asInt());
        assertEquals(3, node(report, "M05").path("samples").size());
        assertTrue(node(report, "M05").path("samples").get(0).path("transport").path("psiCrcChecked").asBoolean());
        assertFalse(node(report, "M05").path("samples").get(0).path("transport").path("codecDecoded").asBoolean());
        assertEquals("resource-2", report.path("resources").get(2).path("inputFrom").asText());
        assertNode(report, "M07", "not_tested");
        assertFalse(report.path("sourceHealthEligible").asBoolean());
        assertFalse(report.toString().contains("same-origin-secret"));
        assertFalse(report.toString().contains(origin));
    }

    @Test
    void htmlAndJsonWithSuccessfulHttpNeverBecomeMedia() throws Exception {
        for (String document : List.of("<html>login</html>", "<!DOCTYPE html><html>login</html>",
                "<?xml version=\"1.0\"?><Error>expired</Error>", "{\"error\":\"expired\"}", "[\"expired\"]")) {
            handler = exchange -> send(exchange, 200, document);
            var report = run("/not-video", Map.of());
            assertNode(report, "M01", "fail");
            assertEquals("MEDIA_ERROR_DOCUMENT", node(report, "M01").path("reason").asText());
        }
    }

    @Test
    void mp4RequiresThreeExactRangesAndStableRepresentation() throws Exception {
        List<String> ranges = new CopyOnWriteArrayList<>();
        byte[] file = mp4();
        handler = exchange -> {
            ranges.add(exchange.getRequestHeaders().getFirst("Range"));
            ranged(exchange, file, "stable", false);
        };
        var report = run("/video", Map.of());
        assertNode(report, "M06", "pass");
        assertEquals(List.of("bytes=0-65535", "bytes=150000-215535", "bytes=295904-361439"), ranges);
        assertEquals("strong_etag", node(report, "M06").path("representationValidator").asText());
        assertEquals(3, report.path("usage").path("requests").asInt());
    }

    @Test
    void mp4BinaryRangesAreNotMistakenForDocumentsButActualErrorDocumentsStillFail() throws Exception {
        for (String prefix : List.of("{", "[", "<binary", "{}\u0001", "[] {}")) {
            byte[] file = mp4();
            byte[] binary = prefix.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(binary, 0, file, file.length / 2, binary.length);
            handler = exchange -> ranged(exchange, file, "stable", false);
            assertNode(run("/video", Map.of()), "M06", "pass");
        }
        for (String document : List.of("<HTML>expired</HTML>", "<?xml version=\"1.0\"?><Error/>",
                "{\"error\":\"expired\"}", "[\"expired\"]")) {
            byte[] file = mp4();
            int middle = file.length / 2;
            Arrays.fill(file, middle, middle + MediaNetworkPolicy.MAX_RESPONSE_BYTES, (byte) ' ');
            byte[] error = document.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(error, 0, file, middle, error.length);
            handler = exchange -> ranged(exchange, file, "stable", false);
            var report = run("/video", Map.of());
            assertNode(report, "M01", "pass");
            assertNode(report, "M06", "fail");
            assertEquals("MEDIA_ERROR_DOCUMENT", node(report, "M06").path("reason").asText());
        }
    }

    @Test
    void ignoredRangeIsCappedAndFailsMp4RangeContract() throws Exception {
        handler = exchange -> send(exchange, 200, mp4());
        var report = run("/video", Map.of());
        assertNode(report, "M01", "pass");
        assertNode(report, "M06", "fail");
        assertEquals("MEDIA_RANGE_NOT_HONORED", node(report, "M06").path("reason").asText());
        assertEquals(65_536, report.path("usage").path("retainedBodyBytes").asInt());
        assertTrue(report.path("resources").get(0).path("prefixTruncated").asBoolean());
    }

    @Test
    void malformedContentRangeAndChangingRepresentationAreRejected() throws Exception {
        byte[] file = mp4();
        handler = exchange -> ranged(exchange, file, "same", true);
        assertEquals("MEDIA_CONTENT_RANGE_INVALID", node(run("/video", Map.of()), "M01").path("reason").asText());
        AtomicInteger sequence = new AtomicInteger();
        handler = exchange -> ranged(exchange, file, sequence.incrementAndGet() == 1 ? "before" : "after", false);
        var report = run("/video", Map.of());
        assertNode(report, "M06", "fail");
        assertEquals("MEDIA_REPRESENTATION_CHANGED", node(report, "M06").path("reason").asText());
    }

    @Test
    void laterBadSegmentFailsWithoutLosingPlaylistEvidence() throws Exception {
        handler = exchange -> {
            if (exchange.getRequestURI().getPath().equals("/list")) send(exchange, 200, playlist(3));
            else send(exchange, 200, exchange.getRequestURI().getPath().equals("/s1")
                    ? "<html>expired</html>".getBytes(StandardCharsets.UTF_8) : ts());
        };
        var report = run("/list", Map.of());
        assertNode(report, "M01", "pass");
        assertNode(report, "M03", "pass");
        assertNode(report, "M05", "fail");
        assertEquals("MEDIA_TS_PACKET_SIGNATURE_INVALID", node(report, "M05").path("reason").asText());
    }

    @Test
    void unsupportedEncryptedMappedAndByteRangePlaylistsStayBlockedWithoutFetchingAuxiliaryResources() throws Exception {
        for (String tag : List.of("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"secret-key\"\n",
                "#EXT-X-MAP:URI=\"init.mp4\"\n", "#EXT-X-BYTERANGE:100@0\n",
                "#EXT-X-I-FRAMES-ONLY\n",
                "#EXT-X-KEY:METHOD=AES-128,URI=\"secret-key\"\n#EXT-X-MAP:URI=\"init.mp4\"\n")) {
            requests.set(0);
            handler = exchange -> send(exchange, 200, "#EXTM3U\n#EXT-X-TARGETDURATION:6\n" + tag + "#EXTINF:6,\ns0\n");
            var report = run("/list", Map.of());
            assertNode(report, "M03", "pass");
            assertNode(report, "M04", "blocked");
            assertNode(report, "M05", "blocked");
            assertEquals(1, requests.get());
        }
    }

    @Test
    void malformedAndTruncatedPlaylistsCannotPass() throws Exception {
        for (String text : List.of("#EXTM3U\ns0\n", "#EXTM3U\n#EXTINF:NaN,\ns0\n",
                "#EXTM3U\n#EXTINF:6,\ns0\n", "#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:6,\ns0\n",
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n", "#EXTM3U\n" + "# comment\n".repeat(9000))) {
            handler = exchange -> send(exchange, 200, text);
            var report = run("/list", Map.of());
            assertEquals("sample_failed", report.path("verdict").asText());
            assertNode(report, "M05", "not_tested");
        }
    }

    @Test
    void syncBytesWithoutProgramTablesCannotProduceAMediaSuccess() throws Exception {
        byte[] fake = new byte[188 * 20];
        for (int offset = 0; offset < fake.length; offset += 188) {
            fake[offset] = 0x47; fake[offset + 3] = 0x10;
        }
        handler = exchange -> {
            if (exchange.getRequestURI().getPath().equals("/list")) send(exchange, 200, playlist(1));
            else send(exchange, 200, fake);
        };
        var report = run("/list", Map.of());
        assertNode(report, "M03", "pass");
        assertNode(report, "M05", "fail");
        assertEquals("sample_failed", report.path("verdict").asText());
    }

    @Test
    void bounded206WithoutProgramTablesRemainsIncompleteRatherThanGreen() throws Exception {
        byte[] nullPackets = new byte[188 * 400];
        for (int offset = 0; offset < nullPackets.length; offset += 188) {
            nullPackets[offset] = 0x47; nullPackets[offset + 1] = 0x1f;
            nullPackets[offset + 2] = (byte) 0xff; nullPackets[offset + 3] = 0x10;
        }
        handler = exchange -> {
            if (exchange.getRequestURI().getPath().equals("/list")) send(exchange, 200, playlist(1));
            else ranged(exchange, nullPackets, "null-packet-fixture", false);
        };
        var report = run("/list", Map.of());
        assertNode(report, "M05", "not_tested");
        assertEquals("MEDIA_TS_PREFIX_INCOMPLETE", node(report, "M05").path("reason").asText());
        assertEquals("sample_incomplete", report.path("verdict").asText());
    }

    @Test
    void refusesInitialRedirectAndPlaylistOriginsBeforeMakingARequestThere() throws Exception {
        var initial = engine.probe(policy.target(mapper.readTree("{\"url\":\"http://127.0.0.1:1/private\"}")), "engine-fixture");
        assertNode(initial, "M01", "blocked");
        assertEquals(0, requests.get());
        handler = exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/private?secret=not-exported");
            exchange.sendResponseHeaders(302, -1);
        };
        assertNode(run("/redirect", Map.of()), "M01", "blocked");
        handler = exchange -> send(exchange, 200, "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nhttp://127.0.0.1:1/private\n");
        var report = run("/list", Map.of());
        assertNode(report, "M05", "blocked");
        assertEquals(2, requests.get());
        assertFalse(report.toString().contains("private"));
    }

    @Test
    void crossOriginRedirectAndSegmentsDoNotReceiveSuppliedCredentials() throws Exception {
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> cookie = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        other.createContext("/", exchange -> {
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, 200, ts());
            exchange.close();
        });
        other.start();
        String second = "http://127.0.0.1:" + other.getAddress().getPort();
        try {
            engine.close();
            policy = new MediaNetworkPolicy(mapper, true, origin + "," + second, true);
            engine = new MediaProbeEngine(policy, mapper, budgets);
            handler = exchange -> send(exchange, 200, "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n" + second + "/s0\n");
            var report = run("/list", Map.of("Cookie", "never-forward", "Authorization", "Bearer never-forward"));
            assertNode(report, "M05", "pass");
            assertNull(cookie.get());
            assertNull(authorization.get());
            assertFalse(report.path("resources").get(1).path("suppliedHeadersForwarded").asBoolean());
            handler = exchange -> {
                exchange.getResponseHeaders().set("Location", second + "/redirected");
                exchange.sendResponseHeaders(302, -1);
            };
            var redirected = run("/redirect", Map.of("Cookie", "never-forward", "Authorization", "Bearer never-forward"));
            assertNull(cookie.get());
            assertNull(authorization.get());
            assertFalse(redirected.path("resources").get(1).path("suppliedHeadersForwarded").asBoolean());
        } finally { other.stop(0); }
    }

    @Test
    void redirectLoopAndHopLimitStopWithBoundedRequests() throws Exception {
        handler = exchange -> {
            exchange.getResponseHeaders().set("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
        };
        assertEquals("MEDIA_REDIRECT_LOOP", node(run("/loop", Map.of()), "M01").path("reason").asText());
        requests.set(0);
        handler = exchange -> {
            exchange.getResponseHeaders().set("Location", "/hop" + requests.get());
            exchange.sendResponseHeaders(302, -1);
        };
        assertEquals("MEDIA_REDIRECT_LIMIT", node(run("/start", Map.of()), "M01").path("reason").asText());
        assertEquals(4, requests.get());
    }

    @Test
    void disabledPolicyDoesNotTouchNetworkAndInputCannotInjectTransportHeaders() throws Exception {
        engine.close();
        policy = new MediaNetworkPolicy(mapper, false, "", false);
        engine = new MediaProbeEngine(policy, mapper, budgets);
        var report = engine.probe(policy.target(mapper.readTree("{\"url\":\"https://example.invalid/video\"}")), "engine-fixture");
        assertNode(report, "M01", "blocked");
        assertEquals(0, requests.get());
        for (String input : List.of("{\"url\":\"https://user:secret@example.invalid/a\"}",
                "{\"url\":\"file:///secret\"}", "{\"url\":\"https://example.invalid/a\",\"parse\":1}",
                "{\"url\":\"https://example.invalid/a\",\"header\":{\"Range\":\"bytes=0-1\"}}",
                "{\"url\":\"https://example.invalid/a\",\"header\":{\"Cookie\":\"bad\\r\\nX: inject\"}}",
                "{\"url\":\"https://example.invalid/a\",\"header\":{\"Cookie\":\"a\",\"cookie\":\"b\"}}",
                "{\"url\":\"https://example.invalid/a\",\"header\":\"\"}",
                "{\"url\":\"https://example.invalid/a\",\"header\":\"null\"}",
                "{\"url\":\"https://example.invalid/a\",\"playUrl\":{}}",
                "{\"url\":\"https://example.invalid/a\",\"jx\":4294967296}")) {
            assertThrows(IllegalArgumentException.class, () -> policy.target(mapper.readTree(input)), input);
        }
    }

    @Test
    void cancellationInterruptsAnInFlightHttpRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        handler = exchange -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> task = executor.submit(() -> {
                try { run("/stall", Map.of()); fail("Must be interrupted"); }
                catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(task.cancel(true));
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @Test
    void requestBudgetExhaustionRemainsUntestedAndCannotProduceASuccessVerdict() throws Exception {
        handler = exchange -> {
            if (exchange.getRequestURI().getPath().equals("/list")) {
                send(exchange, 200, playlist(3));
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            int hop = query == null ? 0 : Integer.parseInt(query.substring(2));
            if (hop < 3) {
                exchange.getResponseHeaders().set("Location", exchange.getRequestURI().getPath() + "?h=" + (hop + 1));
                exchange.sendResponseHeaders(302, -1);
            } else send(exchange, 200, ts());
        };
        var report = run("/list", Map.of());
        assertNode(report, "M05", "not_tested");
        assertEquals("MEDIA_BUDGET_EXHAUSTED", node(report, "M05").path("reason").asText());
        assertEquals("sample_incomplete", report.path("verdict").asText());
        assertEquals(12, requests.get());
    }

    @Test
    void stalledResponseStopsAtTheRequestDeadline() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        handler = exchange -> {
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        };
        long started = System.nanoTime();
        try {
            var report = run("/stall", Map.of());
            assertEquals("sample_failed", report.path("verdict").asText());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000);
            assertEquals(1, requests.get());
        } finally { release.countDown(); }
    }

    @Test
    void emptyVariantBecomesAnExplicitMediaFailureAndEngineIdentityIsFrozen() throws Exception {
        handler = exchange -> send(exchange, 200, exchange.getRequestURI().getPath().equals("/master")
                ? "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvariant\n" : "\n");
        var report = run("/master", Map.of());
        assertNode(report, "M02", "fail");
        assertEquals("MEDIA_HLS_INVALID", node(report, "M02").path("reason").asText());
        var classes = policy.snapshot().path("engineClasses");
        assertEquals(22, classes.size());
        assertTrue(classes.has("HlsAes128.class"));
        assertTrue(classes.has("HlsKey.class"));
        assertTrue(classes.has("HlsPlaylist.class"));
        assertTrue(classes.has("TsInspector.class"));
        assertTrue(classes.has("TsInspector$Sections.class"));
        assertTrue(classes.has("TsInspector$Packet.class"));
        byte[] body = new org.springframework.core.io.ClassPathResource(
                "cn/har01d/atmaintenance/service/MediaProbeEngine.class").getContentAsByteArray();
        assertEquals(Hashes.sha256(body), classes.path("MediaProbeEngine.class").asText());
    }

    JsonNode run(String path, Map<String, String> headers) throws Exception {
        var player = mapper.createObjectNode().put("parse", 0).put("jx", 0).put("playUrl", "").put("url", origin + path);
        player.set("header", mapper.valueToTree(headers));
        return engine.probe(policy.target(player), "engine-fixture");
    }

    @Test
    void aesKeysAreBudgetedOncePerDeclarationAndNeverPersisted() throws Exception {
        byte[] key = "key-do-not-save!".getBytes(StandardCharsets.US_ASCII);
        Map<String, byte[]> ciphertext = new HashMap<>();
        for (int index = 0; index < 3; index++) ciphertext.put("/s" + index,
                HlsAes128Test.encrypt(ts(), key, HlsAes128.iv(null, java.math.BigInteger.valueOf(9 + index))));
        handler = exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/list")) send(exchange, 200, playlist(3).replace("#EXT-X-TARGETDURATION:6",
                    "#EXT-X-MEDIA-SEQUENCE:9\n#EXT-X-TARGETDURATION:6\n#EXT-X-KEY:METHOD=AES-128,URI=\"key,private\""));
            else if (path.equals("/key,private")) {
                assertNull(exchange.getRequestHeaders().getFirst("Range"));
                send(exchange, 200, key);
            } else send(exchange, 200, ciphertext.get(path));
        };
        var report = run("/list", Map.of());
        assertNode(report, "M04", "pass");
        assertNode(report, "M05", "pass");
        assertEquals(5, requests.get());
        verify(budgets, times(5)).authorize(eq("engine-fixture"), any(URI.class));
        var keyEvidence = report.path("resources").get(1);
        assertEquals(16, keyEvidence.path("retainedBytes").asInt());
        assertFalse(keyEvidence.has("bodySha256"));
        assertEquals("omitted_sensitive_key", keyEvidence.path("bodyIdentity").asText());
        assertFalse(report.toString().contains(Hashes.sha256(key)));
        assertFalse(report.toString().contains("key-do-not-save"));
        assertFalse(report.toString().contains("key,private"));
        for (var sample : node(report, "M05").path("samples")) {
            assertTrue(sample.path("encryption").path("paddingChecked").asBoolean());
            assertFalse(sample.path("encryption").path("authenticated").asBoolean());
            assertEquals("media_sequence", sample.path("encryption").path("ivSource").asText());
        }
    }

    @Test
    void repeatedKeyUriRotatesByDeclarationAndNoneReturnsToClearTs() throws Exception {
        byte[] first = new byte[16], second = new byte[16];
        second[0] = 7;
        byte[] firstCipher = HlsAes128Test.encrypt(ts(), first, HlsAes128.iv("0x3", java.math.BigInteger.ZERO));
        byte[] secondCipher = HlsAes128Test.encrypt(ts(), second, HlsAes128.iv("0x4", java.math.BigInteger.ZERO));
        AtomicInteger keys = new AtomicInteger();
        String manifest = "#EXTM3U\n#EXT-X-VERSION:2\n#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x3\n#EXTINF:6,\ns0\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x4\n#EXTINF:6,\ns1\n"
                + "#EXT-X-KEY:METHOD=NONE\n#EXTINF:6,\ns2\n";
        handler = exchange -> {
            switch (exchange.getRequestURI().getPath()) {
                case "/list" -> send(exchange, 200, manifest);
                case "/key" -> send(exchange, 200, keys.incrementAndGet() == 1 ? first : second);
                case "/s0" -> send(exchange, 200, firstCipher);
                case "/s1" -> send(exchange, 200, secondCipher);
                default -> send(exchange, 200, ts());
            }
        };
        var report = run("/list", Map.of());
        assertNode(report, "M05", "pass");
        assertEquals(2, keys.get());
        assertEquals(6, requests.get());
        assertEquals("explicit", node(report, "M05").path("samples").get(0).path("encryption").path("ivSource").asText());
        assertFalse(node(report, "M05").path("samples").get(2).has("encryption"));
    }

    @Test
    void wrongLengthTruncatedOrPartialKeysFailBeforeFetchingSegments() throws Exception {
        for (int status : List.of(200, 206)) for (int size : List.of(0, 15, 16, 17, 50000)) {
            if (status == 200 && size == 16) continue;
            requests.set(0);
            handler = exchange -> {
                if (exchange.getRequestURI().getPath().equals("/list"))
                    send(exchange, 200, playlist(1).replace("#EXTINF", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF"));
                else send(exchange, status, new byte[size]);
            };
            var report = run("/list", Map.of());
            assertNode(report, "M04", "fail");
            assertEquals("MEDIA_HLS_KEY_RESPONSE_INVALID", node(report, "M04").path("reason").asText());
            assertNode(report, "M05", "not_tested");
            assertEquals(2, requests.get());
            assertTrue(report.path("resources").get(1).path("retainedBytes").asInt() <= 17);
            assertFalse(report.path("resources").get(1).has("bodySha256"));
        }
    }

    @Test
    void encryptedRangePrefixInspectsTsWithoutClaimingPaddingOrFullPlayback() throws Exception {
        byte[] key = new byte[16];
        byte[] plain = Arrays.copyOf(ts(), 188 * 500);
        for (int i = 3; i < 500; i++) System.arraycopy(TsFixtures.nullPacket(i % 16), 0, plain, i * 188, 188);
        byte[] encrypted = HlsAes128Test.encrypt(plain, key, new byte[16]);
        handler = exchange -> {
            switch (exchange.getRequestURI().getPath()) {
                case "/list" -> send(exchange, 200, playlist(1).replace("#EXTINF",
                        "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF"));
                case "/key" -> send(exchange, 200, key);
                default -> ranged(exchange, encrypted, "fixture-cipher", false);
            }
        };
        var report = run("/list", Map.of());
        assertNode(report, "M05", "pass");
        var sample = node(report, "M05").path("samples").get(0);
        assertTrue(sample.path("encryption").path("prefixOnly").asBoolean());
        assertFalse(sample.path("encryption").path("paddingChecked").asBoolean());
        assertFalse(sample.path("transport").path("codecDecoded").asBoolean());
    }

    @Test
    void wrongKeyAndCorruptCipherCannotPassTransportEvidence() throws Exception {
        byte[] ciphertext = HlsAes128Test.encrypt(ts(), new byte[16], new byte[16]);
        byte[] wrongKey = new byte[16];
        Arrays.fill(wrongKey, (byte) 3);
        handler = exchange -> {
            switch (exchange.getRequestURI().getPath()) {
                case "/list" -> send(exchange, 200, playlist(1).replace("#EXTINF",
                        "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF"));
                case "/key" -> send(exchange, 200, wrongKey);
                default -> send(exchange, 200, ciphertext);
            }
        };
        var report = run("/list", Map.of());
        assertNode(report, "M04", "pass");
        assertNode(report, "M05", "fail");
        assertEquals("sample_failed", report.path("verdict").asText());
    }

    @Test
    void keyRedirectOriginsAndPersistentBudgetAreCheckedBeforeNetwork() throws Exception {
        for (boolean budgetBlocked : List.of(false, true)) {
            requests.set(0);
            if (budgetBlocked) doThrow(new IOException("MEDIA_PERSISTENT_REQUEST_LIMIT"))
                    .when(budgets).authorize(eq("engine-fixture"), argThat(uri -> uri.getPath().equals("/key")));
            handler = exchange -> {
                if (exchange.getRequestURI().getPath().equals("/list"))
                    send(exchange, 200, playlist(1).replace("#EXTINF", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF"));
                else {
                    exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/private-key");
                    send(exchange, 302, "redirect-secret");
                }
            };
            var report = run("/list", Map.of("Cookie", "private-cookie"));
            assertNode(report, "M04", budgetBlocked ? "not_tested" : "blocked");
            assertEquals(budgetBlocked ? 1 : 2, requests.get());
            assertFalse(report.toString().contains("redirect-secret"));
            assertFalse(report.toString().contains(Hashes.sha256("redirect-secret")));
            assertFalse(report.toString().contains("private-key"));
        }
    }

    static JsonNode node(JsonNode report, String id) {
        for (JsonNode node : report.path("nodes")) if (node.path("id").asText().equals(id)) return node;
        throw new AssertionError(id);
    }
    static void assertNode(JsonNode report, String id, String state) {
        assertEquals(state, node(report, id).path("status").asText(), report.toPrettyString());
    }
    static String playlist(int count) {
        StringBuilder value = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:6\n");
        for (int index = 0; index < count; index++) value.append("#EXTINF:6,\ns").append(index).append("\n");
        return value.append("#EXT-X-ENDLIST\n").toString();
    }
    static byte[] ts() {
        return TsFixtures.stream();
    }
    static byte[] mp4() {
        byte[] bytes = new byte[300_000];
        ByteBuffer.wrap(bytes).putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII));
        return bytes;
    }
    static void ranged(HttpExchange exchange, byte[] file, String etag, boolean malformed) throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        String[] parts = range.substring(6).split("-");
        int start = Integer.parseInt(parts[0]), end = Math.min(file.length - 1, Integer.parseInt(parts[1]));
        exchange.getResponseHeaders().set("Content-Range", "bytes " + (malformed ? start + 1 : start) + "-" + end + "/" + file.length);
        exchange.getResponseHeaders().set("ETag", "\"" + etag + "\"");
        send(exchange, 206, Arrays.copyOfRange(file, start, end + 1));
    }
    static void send(HttpExchange exchange, int status, String text) throws IOException {
        send(exchange, status, text.getBytes(StandardCharsets.UTF_8));
    }
    static void send(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
