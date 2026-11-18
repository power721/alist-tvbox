package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.BrowserEvidenceRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in CDP response-stream evidence. Never navigates, copies cookies, or reads a whole-page DOM/body. */
@Service
public class BrowserEvidence {
    interface Peer extends AutoCloseable {
        JsonNode call(String method, JsonNode params) throws IOException, InterruptedException;
        JsonNode event() throws IOException, InterruptedException;
        @Override void close();
    }
    private final ObjectMapper mapper;
    private final String endpoint;

    public BrowserEvidence(ObjectMapper mapper, @Value("${maintenance.browser-evidence-endpoint:}") String endpoint) {
        this.mapper = mapper;
        this.endpoint = endpoint;
    }

    public synchronized ObjectNode capture(BrowserEvidenceRequest input) throws IOException, InterruptedException {
        validate(input);
        require(endpoint != null && endpoint.matches("ws://127\\.0\\.0\\.1:[0-9]{1,5}/devtools/page/[A-Za-z0-9-]{1,100}"),
                "BROWSER_EVIDENCE_DISABLED");
        try (var peer = new CdpPeer(mapper, URI.create(endpoint))) { return capture(input, peer); }
    }

    private void validate(BrowserEvidenceRequest input) throws IOException {
        require(input != null && input.jobId() != null && input.jobId().matches("[a-f0-9-]{36}")
                && hash(input.expectedArtifactSha256()) && hash(input.frameUrlSha256())
                && input.frameId() != null && input.frameId().matches("[A-Za-z0-9-]{1,100}")
                && input.selector() != null && input.selector().matches(
                    "[a-z][a-z0-9-]*(?:\\.[A-Za-z_][A-Za-z0-9_-]{0,63})?")
                && input.maxBodyBytes() >= 1 && input.maxBodyBytes() <= 32768
                && input.responseUrls() != null && input.responseUrls().size() <= 4
                && new HashSet<>(input.responseUrls()).size() == input.responseUrls().size(),
                "BROWSER_EVIDENCE_INPUT_INVALID");
        require(input.responseUrls().isEmpty() || input.allowResponseAbort(), "BROWSER_RESPONSE_ABORT_NOT_APPROVED");
        for (String url : input.responseUrls()) {
            require(url != null && url.length() <= 2048 && url.matches("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?/[^\\s\\\\#]*"),
                    "BROWSER_EVIDENCE_URL_INVALID");
            URI parsed = URI.create(url);
            require(parsed.getUserInfo() == null && parsed.getHost() != null, "BROWSER_EVIDENCE_URL_INVALID");
        }
    }

    ObjectNode capture(BrowserEvidenceRequest input, Peer peer) throws IOException, InterruptedException {
        validate(input);
        JsonNode tree = peer.call("Page.getFrameTree", object()).path("frameTree");
        JsonNode frame = findFrame(tree, input.frameId(), 0);
        var report = object().put("schemaVersion", 1).put("authority", "host_target_browser_observed")
                .put("jobId", input.jobId()).put("artifactSha256", input.expectedArtifactSha256())
                .put("frameIdSha256", Hashes.sha256(input.frameId())).put("frameUrlSha256", input.frameUrlSha256())
                .put("selectorSha256", Hashes.sha256(input.selector())).put("wholePageFallback", false)
                .put("rawOutputStored", false).put("publishEligible", false).put("businessChainVerified", false);
        require(!frame.isMissingNode() && Hashes.sha256(frame.path("url").asText()).equals(input.frameUrlSha256()),
                "BROWSER_TARGET_FRAME_MISSING_OR_CHANGED");
        require(frame.path("loaderId").isTextual() && !frame.path("loaderId").asText().isBlank(),
                "BROWSER_TARGET_LOADER_MISSING");
        String loaderId = frame.path("loaderId").asText();
        report.put("loaderIdSha256", Hashes.sha256(loaderId));
        JsonNode world = peer.call("Page.createIsolatedWorld", object().put("frameId", input.frameId())
                .put("worldName", "maintenance-evidence").put("grantUniveralAccess", false));
        require(world.path("executionContextId").isIntegralNumber(), "BROWSER_TARGET_CONTEXT_MISSING");
        String expression = "(()=>{const targets=document.querySelectorAll(" + mapper.writeValueAsString(input.selector())
                + ");if(targets.length!==1)return {found:false};const e=targets[0];"
                + "let nodes=0,bytes=0,overflow=false;const out=[];"
                + "const walk=n=>{if(++nodes>128){overflow=true;return;}if(n.nodeType===3){"
                + "const t=n.textContent||'';if(t.length>32768-bytes){overflow=true;return;}const b=new TextEncoder().encode(t);"
                + "if(bytes+b.length>32768){overflow=true;return;}bytes+=b.length;out.push(t);}"
                + "for(const c of n.childNodes){if(overflow)break;walk(c);}};walk(e);"
                + "return {found:true,focused:e===document.activeElement||e.contains(document.activeElement),"
                + "nodes,bytes,overflow,text:out.join('')};})()";
        JsonNode evaluated = peer.call("Runtime.evaluate", object().put("expression", expression)
                .put("returnByValue", true).put("timeout", 1000)
                .set("contextId", world.path("executionContextId"))).path("result").path("value");
        require(evaluated.path("found").isBoolean() && evaluated.path("found").booleanValue(),
                "BROWSER_TARGET_DOM_MISSING");
        require(evaluated.path("focused").isBoolean() && (!input.requireFocus() || evaluated.path("focused").booleanValue()),
                "BROWSER_TARGET_FOCUS_MISSING");
        require(evaluated.path("overflow").isBoolean() && !evaluated.path("overflow").booleanValue()
                && evaluated.path("nodes").isInt() && evaluated.path("nodes").intValue() >= 1
                && evaluated.path("nodes").intValue() <= 128
                && evaluated.path("text").isTextual()
                && evaluated.path("text").textValue().getBytes(StandardCharsets.UTF_8).length <= 32768,
                "BROWSER_DOM_BUDGET");
        report.putObject("dom").put("targetFound", true).put("focused", evaluated.path("focused").booleanValue())
                .put("nodes", evaluated.path("nodes").intValue())
                .put("textBytes", evaluated.path("text").textValue().getBytes(StandardCharsets.UTF_8).length)
                .put("textSha256", Hashes.sha256(evaluated.path("text").textValue()));
        var bodies = report.putArray("responses");
        if (input.responseUrls().isEmpty()) {
            verifyFrame(input, peer, loaderId);
            return report;
        }
        var patterns = object().putArray("patterns");
        for (String url : input.responseUrls())
            patterns.addObject().put("urlPattern", url.replace("*", "\\*").replace("?", "\\?"))
                    .put("requestStage", "Response");
        var enabled = object();
        enabled.set("patterns", patterns);
        var handles = new HashSet<String>();
        var paused = new HashSet<String>();
        var covered = new HashSet<String>();
        try {
            peer.call("Fetch.enable", enabled);
            int total = 0;
            var requests = new HashMap<String, Integer>();
            var networks = new HashMap<String, String>();
            for (int eventCount = 0; eventCount < 16 && bodies.size() < input.responseUrls().size(); eventCount++) {
                JsonNode event = peer.event();
                if (!event.path("method").asText().equals("Fetch.requestPaused")) continue;
                JsonNode value = event.path("params");
                String requestId = value.path("requestId").asText();
                require(requestId.matches("[A-Za-z0-9._:-]{1,128}"), "BROWSER_REQUEST_ID_INVALID");
                paused.add(requestId);
                String url = value.path("request").path("url").asText();
                if (!value.path("frameId").asText().equals(input.frameId()) || !input.responseUrls().contains(url)) {
                    peer.call("Fetch.continueResponse", object().put("requestId", requestId));
                    paused.remove(requestId);
                    throw new IOException("BROWSER_RESPONSE_SCOPE_MISMATCH");
                }
                verifyFrame(input, peer, loaderId);
                String redirected = value.path("redirectedRequestId").asText();
                int hop = redirected.isEmpty() ? 0 : requests.getOrDefault(redirected, -2) + 1;
                require(hop >= 0 && !requests.containsKey(requestId), "BROWSER_REDIRECT_CHAIN_INVALID");
                requests.put(requestId, hop);
                require(hop <= 3 && value.path("responseStatusCode").isInt()
                        && value.path("networkId").isTextual()
                        && value.path("networkId").asText().matches("[A-Za-z0-9._:-]{1,128}")
                        && (hop == 0 || value.path("networkId").asText().equals(networks.get(redirected))),
                        "BROWSER_RESPONSE_INVALID");
                networks.put(requestId, value.path("networkId").asText());
                int status = value.path("responseStatusCode").intValue();
                if (status >= 300 && status < 400) {
                    peer.call("Fetch.continueResponse", object().put("requestId", requestId));
                    paused.remove(requestId);
                    continue;
                }
                require(status == 200 || status == 206, "BROWSER_RESPONSE_STATUS_INVALID");
                require(covered.add(url), "BROWSER_DUPLICATE_RESPONSE");
                int remaining = input.maxBodyBytes() - total;
                require(remaining > 0, "BROWSER_BODY_BUDGET");
                JsonNode stream = peer.call("Fetch.takeResponseBodyAsStream", object().put("requestId", requestId));
                String handle = stream.path("stream").asText();
                require(handle.matches("[A-Za-z0-9._:-]{1,128}"), "BROWSER_STREAM_INVALID");
                handles.add(handle);
                var bytes = new java.io.ByteArrayOutputStream();
                boolean eof = false;
                while (!eof && bytes.size() < remaining) {
                        int limit = Math.min(4096, remaining - bytes.size());
                        JsonNode part = peer.call("IO.read", object().put("handle", handle).put("size", limit));
                        require(part.path("data").isTextual() && part.path("data").textValue().length() <= 4 * ((limit + 2) / 3)
                                && part.path("eof").isBoolean() && (part.path("base64Encoded").isMissingNode()
                                    || part.path("base64Encoded").isBoolean()), "BROWSER_BODY_ENCODING_INVALID");
                        byte[] body;
                        try { body = part.path("base64Encoded").asBoolean()
                                ? Base64.getDecoder().decode(part.path("data").textValue())
                                : part.path("data").textValue().getBytes(StandardCharsets.UTF_8); }
                        catch (IllegalArgumentException error) { throw new IOException("BROWSER_BODY_ENCODING_INVALID"); }
                        require(body.length <= limit, "BROWSER_BODY_BUDGET");
                        eof = part.path("eof").booleanValue();
                        require(body.length > 0 || eof, "BROWSER_EMPTY_STREAM_CHUNK");
                        bytes.writeBytes(body);
                }
                total += bytes.size();
                bodies.addObject().put("requestIdSha256", Hashes.sha256(requestId))
                            .put("networkIdSha256", Hashes.sha256(value.path("networkId").asText()))
                            .put("hop", hop).put("urlSha256", Hashes.sha256(url)).put("responseStatus", status)
                            .put("bodySource", "Fetch.takeResponseBodyAsStream/IO.read")
                            .put("downloadLimitBytes", remaining).put("responseBytes", bytes.size())
                            .put("responseSha256", Hashes.sha256(bytes.toByteArray())).put("responseComplete", eof)
                            .put("refetched", false).put("truncatedAfterFullDownload", false)
                            .put("browserRequestAborted", true);
                peer.call("IO.close", object().put("handle", handle));
                handles.remove(handle);
                peer.call("Fetch.failRequest", object().put("requestId", requestId).put("errorReason", "Aborted"));
                paused.remove(requestId);
            }
            require(covered.equals(new HashSet<>(input.responseUrls())), "BROWSER_RESPONSE_COVERAGE_INCOMPLETE");
            verifyFrame(input, peer, loaderId);
        } finally {
            boolean interrupted = Thread.interrupted(), cleanupFailed = false;
            try {
                for (String handle : handles) {
                    try { peer.call("IO.close", object().put("handle", handle)); }
                    catch (IOException | InterruptedException error) {
                        cleanupFailed = true; interrupted |= error instanceof InterruptedException;
                    }
                }
                for (String request : paused) {
                    try { peer.call("Fetch.failRequest", object().put("requestId", request).put("errorReason", "Aborted")); }
                    catch (IOException | InterruptedException error) {
                        cleanupFailed = true; interrupted |= error instanceof InterruptedException;
                    }
                }
                try { peer.call("Fetch.disable", object()); }
                catch (IOException | InterruptedException error) {
                    cleanupFailed = true; interrupted |= error instanceof InterruptedException;
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
            require(!cleanupFailed, "BROWSER_CLEANUP_UNCONFIRMED");
        }
        return report.put("cleanupConfirmed", true);
    }

    private void verifyFrame(BrowserEvidenceRequest input, Peer peer, String loaderId)
            throws IOException, InterruptedException {
        JsonNode frame = findFrame(peer.call("Page.getFrameTree", object()).path("frameTree"), input.frameId(), 0);
        require(!frame.isMissingNode() && frame.path("loaderId").asText().equals(loaderId)
                && Hashes.sha256(frame.path("url").asText()).equals(input.frameUrlSha256()),
                "BROWSER_TARGET_FRAME_MISSING_OR_CHANGED");
    }

    private JsonNode findFrame(JsonNode node, String id, int depth) throws IOException {
        require(depth <= 16, "BROWSER_FRAME_TREE_LIMIT");
        if (node.path("frame").path("id").asText().equals(id)) return node.path("frame");
        require(node.path("childFrames").isMissingNode() || node.path("childFrames").isArray()
                && node.path("childFrames").size() <= 32, "BROWSER_FRAME_TREE_LIMIT");
        for (JsonNode child : node.path("childFrames")) {
            JsonNode frame = findFrame(child, id, depth + 1);
            if (!frame.isMissingNode()) return frame;
        }
        return mapper.missingNode();
    }
    private ObjectNode object() { return mapper.createObjectNode(); }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static void require(boolean condition, String code) throws IOException {
        if (!condition) throw new IOException(code);
    }

    static final class CdpPeer implements Peer, WebSocket.Listener {
        private final ObjectMapper mapper;
        private final HttpClient client;
        private final WebSocket socket;
        private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final ArrayBlockingQueue<JsonNode> events = new ArrayBlockingQueue<>(32);
        private final AtomicInteger sequence = new AtomicInteger();
        private final StringBuilder partial = new StringBuilder();
        private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        private long cleanupDeadline;
        private int cleanupCommands;
        private volatile boolean failed;

        CdpPeer(ObjectMapper mapper, URI endpoint) throws IOException, InterruptedException {
            this.mapper = mapper;
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            try { socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(endpoint, this).get(3, TimeUnit.SECONDS); }
            catch (ExecutionException | TimeoutException error) {
                client.shutdownNow();
                throw new IOException("BROWSER_CONNECTION_FAILED");
            }
        }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            if (partial.length() + text.length() > 65536) { fail(socket); return null; }
            partial.append(text);
            if (last) {
                try {
                    require(partial.toString().getBytes(StandardCharsets.UTF_8).length <= 65536, "BROWSER_MESSAGE_BUDGET");
                    JsonNode value = mapper.readTree(partial.toString());
                    require(value != null && value.isObject(), "BROWSER_PROTOCOL_FAILED");
                    if (value.path("id").isInt()) {
                        var future = pending.get(value.path("id").intValue());
                        if (future == null) fail(socket); else future.complete(value);
                    } else if (!events.offer(value)) fail(socket);
                } catch (IOException error) { fail(socket); }
                partial.setLength(0);
            }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) { fail(socket); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { fail(socket); }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) { fail(socket); return null; }
        private void fail(WebSocket socket) {
            failed = true;
            pending.values().forEach(value -> value.completeExceptionally(new IOException("BROWSER_PROTOCOL_FAILED")));
            socket.abort();
        }
        @Override public JsonNode call(String method, JsonNode params) throws IOException, InterruptedException {
            boolean cleanup = Set.of("IO.close", "Fetch.failRequest", "Fetch.disable", "Fetch.continueResponse").contains(method);
            if (cleanup && cleanupDeadline == 0) cleanupDeadline = deadline + TimeUnit.SECONDS.toNanos(5);
            require(!failed && (cleanup ? ++cleanupCommands <= 40 : sequence.get() < 128), "BROWSER_COMMAND_BUDGET");
            long end = cleanup ? cleanupDeadline : deadline;
            long remaining = Math.min(TimeUnit.SECONDS.toNanos(3), end - System.nanoTime());
            require(remaining > 0, "BROWSER_DEADLINE");
            int id = sequence.incrementAndGet();
            var future = new CompletableFuture<JsonNode>();
            pending.put(id, future);
            try {
                socket.sendText(mapper.createObjectNode().put("id", id).put("method", method)
                        .set("params", params).toString(), true).get(remaining, TimeUnit.NANOSECONDS);
                remaining = Math.min(TimeUnit.SECONDS.toNanos(3), end - System.nanoTime());
                require(remaining > 0, "BROWSER_DEADLINE");
                JsonNode result = future.get(remaining, TimeUnit.NANOSECONDS);
                require(!result.has("error") && result.path("result").isObject(), "BROWSER_COMMAND_FAILED");
                return result.path("result");
            } catch (ExecutionException | TimeoutException error) { throw new IOException("BROWSER_COMMAND_FAILED"); }
            finally { pending.remove(id); }
        }
        @Override public JsonNode event() throws IOException, InterruptedException {
            require(!failed, "BROWSER_PROTOCOL_FAILED");
            long remaining = Math.min(TimeUnit.SECONDS.toNanos(3), deadline - System.nanoTime());
            require(remaining > 0, "BROWSER_DEADLINE");
            JsonNode value = events.poll(remaining, TimeUnit.NANOSECONDS);
            require(value != null, "BROWSER_RESPONSE_NOT_OBSERVED");
            return value;
        }
        @Override public void close() { socket.abort(); client.shutdownNow(); }
    }
}
