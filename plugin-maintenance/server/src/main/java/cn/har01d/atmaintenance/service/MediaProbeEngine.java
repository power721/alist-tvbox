package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Fetches bounded media samples. It does not infer source or client health. */
@Service
public class MediaProbeEngine {
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    private static final Pattern HTML_XML_PREFIX = Pattern.compile(
            "^<(?:!doctype(?:\\s|>)|\\?xml(?:\\s|\\?)|/?(?:html|head|body|script|error)(?:\\s|>|/))");
    private final MediaNetworkPolicy policy;
    private final ObjectMapper mapper;
    private final MediaBudgetStore budgets;
    private final HttpClient client;

    public MediaProbeEngine(MediaNetworkPolicy policy, ObjectMapper mapper, MediaBudgetStore budgets) {
        this.policy = policy;
        this.mapper = mapper;
        this.budgets = budgets;
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2)).proxy(new ProxySelector() {
                    public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, SocketAddress address, IOException error) { }
                }).build();
    }

    public ObjectNode probe(MediaTarget target, String runId) throws InterruptedException {
        Session session = new Session(target, runId);
        String blocked = policy.blockedReason();
        if (blocked != null) {
            session.node("M01", "blocked", blocked);
            return session.finish();
        }
        String activeNode = "M01";
        try {
            MediaHttpResult first = session.fetch(target.uri(), "player", null, 0L);
            if (!session.success(first)) return session.finish();
            byte[] body = first.body();
            if (htmlOrJson(body)) {
                session.node("M01", "fail", "MEDIA_ERROR_DOCUMENT");
            } else if (hls(body)) {
                if (first.truncated()) throw new IOException("MEDIA_MANIFEST_LIMIT");
                session.node("M01", "pass", "HLS_MAGIC_OBSERVED");
                activeNode = "M03";
                HlsPlaylist playlist = HlsInspector.parse(text(body));
                MediaHttpResult current = first;
                if (!playlist.variants().isEmpty()) {
                    activeNode = "M02";
                    session.node("M02", "pass", "HLS_MASTER_PARSED")
                            .put("publishedVariants", playlist.variants().size()).put("sampledVariants", 1);
                    current = session.fetch(resolve(current.uri(), playlist.variants().getFirst()),
                            "variant", current.resourceId(), null);
                    session.requireSuccess(current);
                    if (current.truncated()) throw new IOException("MEDIA_MANIFEST_LIMIT");
                    playlist = HlsInspector.parse(text(current.body()));
                    if (!playlist.variants().isEmpty()) throw new IOException("MEDIA_NESTED_MASTER_UNSUPPORTED");
                } else session.node("M02", "not_applicable", "DIRECT_MEDIA_PLAYLIST");
                activeNode = "M03";
                session.node("M03", "pass", "HLS_MEDIA_PLAYLIST_PARSED")
                        .put("publishedSegments", playlist.segments().size()).put("endList", playlist.endList());
                if (playlist.unsupportedEncryption() || playlist.initializationMap() || playlist.byteRanges()
                        || playlist.iFramesOnly()) {
                    session.node("M04", "blocked", "HLS_KEY_MAP_OR_BYTERANGE_UNSUPPORTED");
                    session.node("M05", "blocked", "HLS_RESOURCE_PREREQUISITE_UNSUPPORTED");
                } else {
                    Set<Integer> indices = new LinkedHashSet<>(List.of(0, playlist.segments().size() / 2,
                            playlist.segments().size() - 1));
                    // A new declaration can rotate bytes at the same URI. Cache by declaration, never URI.
                    Map<Integer, MediaHttpResult> keys = new LinkedHashMap<>();
                    try {
                        activeNode = "M04";
                        for (int index : indices) {
                            HlsKey key = playlist.segmentKeys().get(index);
                            if (!key.encrypted() || keys.containsKey(key.declaration())) continue;
                            MediaHttpResult resource = session.fetch(resolve(current.uri(), key.uri()),
                                    "key", current.resourceId(), null);
                            keys.put(key.declaration(), resource);
                            if (resource.status() != 200 || resource.truncated() || resource.body().length != 16)
                                throw new IOException("MEDIA_HLS_KEY_RESPONSE_INVALID");
                        }
                        if (keys.isEmpty()) session.node("M04", "not_applicable", "NO_SAMPLED_KEY_MAP_OR_BYTERANGE");
                        else session.node("M04", "pass", "HLS_AES_128_KEYS_OBSERVED")
                                .put("sampledKeyDeclarations", keys.size()).put("keyBytesPersisted", false);
                        activeNode = "M05";
                        ArrayNode samples = mapper.createArrayNode();
                        for (int index : indices) {
                            HlsKey key = playlist.segmentKeys().get(index);
                            MediaHttpResult segment = session.fetch(resolve(current.uri(), playlist.segments().get(index)),
                                    "segment", current.resourceId(), 0L);
                            session.requireSuccess(segment);
                            boolean prefixOnly = segment.truncated() || segment.status() == 206
                                    && range(segment, 0, null) > segment.body().length;
                            byte[] clear = key.encrypted() ? HlsAes128.decrypt(segment.body(),
                                    keys.get(key.declaration()).body(), HlsAes128.iv(key.iv(),
                                            playlist.mediaSequence().add(BigInteger.valueOf(index))), prefixOnly) : segment.body();
                            try {
                                ObjectNode transport = TsInspector.inspect(clear, prefixOnly, mapper);
                                ObjectNode sample = samples.addObject().put("index", index)
                                        .put("resourceId", segment.resourceId())
                                        .put("declaredDurationSeconds", playlist.durations().get(index));
                                sample.set("transport", transport);
                                if (key.encrypted()) sample.putObject("encryption").put("method", "AES-128")
                                        .put("keyResourceId", keys.get(key.declaration()).resourceId())
                                        .put("ivSource", key.iv() == null ? "media_sequence" : "explicit")
                                        .put("prefixOnly", prefixOnly).put("paddingChecked", !prefixOnly)
                                        .put("authenticated", false);
                            } finally {
                                if (key.encrypted()) Arrays.fill(clear, (byte) 0);
                            }
                        }
                        session.node("M05", "pass", "TS_PSI_PES_SAMPLES_OBSERVED").set("samples", samples);
                    } finally {
                        keys.values().forEach(key -> Arrays.fill(key.body(), (byte) 0));
                    }
                }
            } else if (mp4(body)) {
                session.node("M01", "pass", "MP4_FTYP_BOX_OBSERVED");
                activeNode = "M06";
                long total = range(first, 0, null);
                String validator = first.headers().firstValue("ETag").orElse(null);
                if (validator != null && validator.startsWith("W/")) validator = null;
                ArrayNode samples = mapper.createArrayNode();
                samples.addObject().put("offset", 0).put("resourceId", first.resourceId());
                // Do not pretend that a tiny file has three independent positions.
                if (total <= MediaNetworkPolicy.MAX_RESPONSE_BYTES * 2L)
                    throw new IOException("MEDIA_MP4_TOO_SMALL_FOR_THREE_RANGES");
                for (long offset : List.of(total / 2, total - Math.min(4096, total))) {
                    MediaHttpResult sample = session.fetch(first.uri(), "mp4_range", first.resourceId(), offset);
                    session.requireSuccess(sample);
                    range(sample, offset, total);
                    if (htmlOrJson(sample.body())) throw new IOException("MEDIA_ERROR_DOCUMENT");
                    if (validator != null && !validator.equals(sample.headers().firstValue("ETag").orElse(null)))
                        throw new IOException("MEDIA_REPRESENTATION_CHANGED");
                    samples.addObject().put("offset", offset).put("resourceId", sample.resourceId());
                }
                ObjectNode node = session.node("M06", "pass", "MP4_THREE_COHERENT_RANGES");
                node.put("totalBytes", total).put("representationValidator", validator == null ? "unavailable" : "strong_etag");
                node.set("samples", samples);
            } else {
                session.node("M01", "fail", "MEDIA_FORMAT_UNSUPPORTED");
            }
        } catch (IOException error) {
            String code = error.getMessage();
            session.node(activeNode, Set.of("MEDIA_BUDGET_EXHAUSTED", "MEDIA_PERSISTENT_REQUEST_LIMIT",
                    "MEDIA_TS_PREFIX_INCOMPLETE", "MEDIA_HLS_CIPHER_PREFIX_INCOMPLETE").contains(code)
                    ? "not_tested" : Set.of("MEDIA_HOST_COOLDOWN", "MEDIA_CLOCK_ROLLBACK", "MEDIA_RUN_NOT_ACTIVE",
                    "MEDIA_RESERVATION_REQUIRED", "MEDIA_RESERVATION_DAY_EXPIRED").contains(code)
                    ? "blocked" : "fail", code);
        } catch (IllegalArgumentException error) {
            // All messages below come from our URI/playlist validators, never arbitrary transport text.
            String code = error.getMessage();
            if (code == null || !code.matches("MEDIA_[A-Z_]+|INVALID_MEDIA_URI")) code = "INVALID_MEDIA_URI";
            session.node(activeNode, code.equals("MEDIA_HLS_INVALID") ? "fail" : "blocked", code);
        }
        return session.finish();
    }

    private final class Session {
        private final MediaTarget target;
        private final String runId;
        private final ObjectNode report = mapper.createObjectNode();
        private final ArrayNode nodes = report.putArray("nodes");
        private final ArrayNode resources = report.putArray("resources");
        private final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MediaNetworkPolicy.TOTAL_MILLIS);
        private int requests, retained;

        Session(MediaTarget target, String runId) {
            this.target = target;
            this.runId = runId;
            report.put("schemaVersion", 1).put("authority", "operator_supplied_player")
                    .put("sourceHealthEligible", false).put("publishEligible", false)
                    .put("observedAt", Instant.now().toString())
                    .put("boundary", "人工提供的 player 输出；地址未由受管源调用证明。仅检查有限响应与媒体取样，"
                            + "未证明完整解码、连续播放、所有线路、真实源或客户端可用；不参与修复晋级或发布。");
            for (int index = 1; index <= 10; index++)
                node(String.format("M%02d", index), "not_tested", "OUTSIDE_CURRENT_SAMPLE");
        }

        ObjectNode node(String id, String state, String reason) {
            for (var item : nodes) {
                if (item.path("id").asText().equals(id)) {
                    var node = (ObjectNode) item;
                    node.removeAll();
                    return node.put("id", id).put("status", state).put("reason", reason);
                }
            }
            return nodes.addObject().put("id", id).put("status", state).put("reason", reason);
        }

        ObjectNode finish() {
            report.putObject("usage").put("requests", requests).put("retainedBodyBytes", retained)
                    .put("elapsedMillis", MediaNetworkPolicy.TOTAL_MILLIS
                            - Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))
                    .put("byteAccounting", "retained_response_body_only_not_wire_bytes");
            boolean failed = false, blocked = false, incomplete = false;
            for (var item : nodes) {
                failed |= item.path("status").asText().equals("fail");
                blocked |= item.path("status").asText().equals("blocked");
                incomplete |= item.path("status").asText().equals("not_tested")
                        && !item.path("reason").asText().equals("OUTSIDE_CURRENT_SAMPLE");
            }
            report.put("verdict", failed ? "sample_failed" : blocked ? "sample_blocked"
                    : incomplete ? "sample_incomplete" : "sample_checked");
            return report;
        }

        boolean success(MediaHttpResult response) {
            if (response.status() == 200 || response.status() == 206) return true;
            node("M01", "fail", "MEDIA_HTTP_" + response.status());
            return false;
        }

        void requireSuccess(MediaHttpResult response) throws IOException {
            if (response.status() != 200 && response.status() != 206) throw new IOException("MEDIA_HTTP_" + response.status());
        }

        MediaHttpResult fetch(URI initial, String role, String parent, Long offset)
                throws IOException, InterruptedException {
            URI uri = initial;
            policy.check(uri);
            Set<URI> visited = new HashSet<>();
            // Once an origin boundary is crossed, never reattach credentials on a later hop back.
            boolean forwardHeaders = MediaNetworkPolicy.origin(uri).equals(MediaNetworkPolicy.origin(target.uri()));
            for (int redirects = 0; ; redirects++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                policy.check(uri);
                if (!visited.add(uri)) throw new IOException("MEDIA_REDIRECT_LOOP");
                if (requests >= MediaNetworkPolicy.MAX_REQUESTS
                        || retained >= MediaNetworkPolicy.MAX_TOTAL_BYTES) throw new IOException("MEDIA_BUDGET_EXHAUSTED");
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) throw new IOException("MEDIA_TOTAL_TIMEOUT");
                budgets.authorize(runId, uri);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) throw new IOException("MEDIA_TOTAL_TIMEOUT");
                HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET()
                        .timeout(Duration.ofMillis(Math.min(remaining, MediaNetworkPolicy.REQUEST_MILLIS)))
                        .header("Accept-Encoding", "identity");
                if (forwardHeaders) target.headers().forEach(request::header);
                if (offset != null) request.header("Range", "bytes=" + offset + "-"
                        + (offset + MediaNetworkPolicy.MAX_RESPONSE_BYTES - 1L));
                // Read one byte beyond a valid key to distinguish exact 16-byte completion from truncation.
                int cap = Math.min(role.equals("key") ? 17 : MediaNetworkPolicy.MAX_RESPONSE_BYTES,
                        MediaNetworkPolicy.MAX_TOTAL_BYTES - retained);
                MediaPrefixBody body = new MediaPrefixBody(cap);
                String id = "resource-" + (++requests);
                ObjectNode evidence = resources.addObject().put("id", id).put("role", role);
                if (parent != null) evidence.put("inputFrom", parent);
                else evidence.put("inputFrom", "operator_supplied_player.url");
                evidence.put("suppliedHeadersForwarded", forwardHeaders && !target.headers().isEmpty());
                long started = System.nanoTime();
                var pending = client.sendAsync(request.build(), ignored -> body);
                HttpResponse<byte[]> response;
                try {
                    response = pending.get(Math.min(remaining, MediaNetworkPolicy.REQUEST_MILLIS), TimeUnit.MILLISECONDS);
                } catch (ExecutionException error) {
                    throw new IOException("MEDIA_TRANSPORT_ERROR");
                } catch (TimeoutException error) {
                    throw new IOException("MEDIA_REQUEST_TIMEOUT");
                } finally {
                    pending.cancel(true);
                    evidence.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                }
                retained += response.body().length;
                evidence.put("httpStatus", response.statusCode()).put("retainedBytes", response.body().length)
                        .put("prefixTruncated", body.truncated());
                if (role.equals("key")) evidence.put("bodyIdentity", "omitted_sensitive_key");
                else evidence.put("bodySha256", Hashes.sha256(response.body()));
                if (!response.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity"))
                    throw new IOException("MEDIA_CONTENT_ENCODING_UNSUPPORTED");
                if (Set.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
                    if (redirects >= MediaNetworkPolicy.MAX_REDIRECTS) throw new IOException("MEDIA_REDIRECT_LIMIT");
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location == null) throw new IOException("MEDIA_REDIRECT_LOCATION_MISSING");
                    URI next = resolve(uri, location);
                    policy.check(next);
                    forwardHeaders &= MediaNetworkPolicy.origin(next).equals(MediaNetworkPolicy.origin(uri));
                    parent = id;
                    uri = next;
                    continue;
                }
                MediaHttpResult result = new MediaHttpResult(uri, response.statusCode(), response.headers(),
                        response.body(), body.truncated(), id);
                if (response.statusCode() == 206 && offset != null) range(result, offset, null);
                return result;
            }
        }
    }

    private static URI resolve(URI base, String value) {
        try { return base.resolve(value); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("INVALID_MEDIA_URI"); }
    }

    private static long range(MediaHttpResult response, long offset, Long total) throws IOException {
        Matcher match = CONTENT_RANGE.matcher(response.headers().firstValue("Content-Range").orElse(""));
        try {
            if (response.status() != 206 || !match.matches()) throw new IOException("MEDIA_RANGE_NOT_HONORED");
            long start = Long.parseLong(match.group(1)), end = Long.parseLong(match.group(2)),
                    length = Long.parseLong(match.group(3));
            if (start != offset || end < start || end >= length || length <= 0
                    || total != null && length != total
                    || end != Math.min(length - 1, offset + MediaNetworkPolicy.MAX_RESPONSE_BYTES - 1L)
                    || response.body().length != end - start + 1L)
                throw new IOException("MEDIA_CONTENT_RANGE_INVALID");
            return length;
        } catch (NumberFormatException error) { throw new IOException("MEDIA_CONTENT_RANGE_INVALID"); }
    }

    private static String text(byte[] bytes) throws IOException {
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return value.startsWith("\ufeff") ? value.substring(1) : value;
        } catch (CharacterCodingException error) { throw new IOException("MEDIA_MANIFEST_ENCODING"); }
    }

    private static boolean hls(byte[] bytes) {
        return new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.UTF_8)
                .replace("\ufeff", "").startsWith("#EXTM3U");
    }

    private boolean htmlOrJson(byte[] bytes) {
        String prefix = new String(bytes, 0, Math.min(bytes.length, 128), StandardCharsets.UTF_8)
                .stripLeading().toLowerCase(Locale.ROOT);
        if (HTML_XML_PREFIX.matcher(prefix).find()) return true;
        if (!prefix.startsWith("{") && !prefix.startsWith("[")) return false;
        // A binary Range can start with a brace; require a complete JSON document.
        try {
            mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private static boolean mp4(byte[] bytes) {
        if (bytes.length < 16 || !new String(bytes, 4, 4, StandardCharsets.US_ASCII).equals("ftyp")) return false;
        long size = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, 0, 4).getInt());
        return size >= 16 && size <= bytes.length && size % 4 == 0;
    }

    @PreDestroy
    void close() { client.shutdownNow(); }
}
