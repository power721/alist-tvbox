package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.BrowserEvidenceRequest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BrowserEvidenceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final BrowserEvidence service = new BrowserEvidence(mapper, "");
    final String frame = "frame-1";
    final String frameUrl = "https://example.invalid/page";
    final String url = "https://example.invalid/api/data";

    final class Peer implements BrowserEvidence.Peer {
        final ObjectMapper mapper = new ObjectMapper();
        final List<String> calls = new ArrayList<>();
        final List<JsonNode> parameters = new ArrayList<>();
        final ArrayDeque<JsonNode> events = new ArrayDeque<>();
        boolean bodyRead, focused = true, overflow, closeFails, oversized;
        int frameReads, navigateAt = Integer.MAX_VALUE, offset;
        byte[] body = {0x47, 0, 0};
        String scopeFrame = frame;
        @Override public JsonNode call(String method, JsonNode params) throws IOException {
            calls.add(method);
            parameters.add(params.deepCopy());
            switch (method) {
                case "Page.getFrameTree" -> {
                    var root = mapper.createObjectNode();
                    root.putObject("frame").put("id", "root").put("url", "https://example.invalid/");
                    root.putArray("childFrames").addObject().putObject("frame").put("id", frame)
                            .put("url", frameUrl).put("loaderId", ++frameReads >= navigateAt ? "new-loader" : "loader");
                    return mapper.createObjectNode().set("frameTree", root);
                }
                case "Page.createIsolatedWorld" -> { return mapper.createObjectNode().put("executionContextId", 7); }
                case "Runtime.evaluate" -> {
                    var root = mapper.createObjectNode();
                    root.putObject("result").putObject("value")
                            .put("found", true).put("focused", focused).put("nodes", 2).put("bytes", 4)
                            .put("overflow", overflow).put("text", "body");
                    return root;
                }
                case "Fetch.takeResponseBodyAsStream" -> { bodyRead = true; return mapper.createObjectNode().put("stream", "s1"); }
                case "IO.read" -> {
                    int length = oversized ? params.path("size").asInt() + 1
                            : Math.min(params.path("size").asInt(), body.length - offset);
                    byte[] part = oversized ? new byte[length] : Arrays.copyOfRange(body, offset, offset + length);
                    offset += length;
                    return mapper.createObjectNode().put("data", Base64.getEncoder().encodeToString(part))
                            .put("base64Encoded", true).put("eof", offset == body.length);
                }
                case "IO.close" -> { if (closeFails) throw new IOException("fixture-close-failure"); }
                default -> { return mapper.createObjectNode(); }
            }
            return mapper.createObjectNode();
        }
        @Override public JsonNode event() {
            if (!events.isEmpty()) return events.remove();
            return event("req-1", url, 200, "");
        }
        JsonNode event(String id, String address, int status, String redirected) {
            var params = mapper.createObjectNode();
            params.putObject("request").put("url", address);
            params.put("requestId", id).put("frameId", scopeFrame).put("responseStatusCode", status)
                    .put("networkId", "network-1").put("redirectedRequestId", redirected);
            return mapper.createObjectNode().put("method", "Fetch.requestPaused").set("params", params);
        }
        @Override public void close() {}
    }

    BrowserEvidenceRequest request(String selector, List<String> urls) {
        return new BrowserEvidenceRequest("00000000-0000-4000-8000-000000000001",
                "a".repeat(64), frame, Hashes.sha256(frameUrl), selector, true, urls, 32, true);
    }
    @Test void responseAbortRequiresExplicitApprovalBeforeBrowserCommands() {
        var input = request("article.card", List.of(url));
        var peer = new Peer();
        var denied = new BrowserEvidenceRequest(input.jobId(), input.expectedArtifactSha256(), input.frameId(),
                input.frameUrlSha256(), input.selector(), true, input.responseUrls(), 32, false);
        assertEquals("BROWSER_RESPONSE_ABORT_NOT_APPROVED",
                assertThrows(IOException.class, () -> service.capture(denied, peer)).getMessage());
        assertTrue(peer.calls.isEmpty());
    }
    @Test void targetFrameDomFocusAndStreamBodyAreBoundAndHashed() throws Exception {
        var peer = new Peer();
        var value = service.capture(request("article.card", List.of(url)), peer);
        assertTrue(value.path("dom").path("targetFound").asBoolean());
        assertEquals("Fetch.takeResponseBodyAsStream/IO.read",
                value.path("responses").get(0).path("bodySource").asText());
        assertEquals(3, value.path("responses").get(0).path("responseBytes").asInt());
        assertTrue(peer.bodyRead);
        assertFalse(value.toString().contains(url));
        assertFalse(value.path("wholePageFallback").asBoolean());
        assertTrue(value.path("cleanupConfirmed").asBoolean());
        assertTrue(value.path("responses").get(0).path("browserRequestAborted").asBoolean());
        assertFalse(peer.calls.contains("Network.getResponseBody"));
        assertTrue(peer.calls.containsAll(List.of("IO.close", "Fetch.failRequest", "Fetch.disable")));
    }
    @Test void missingFrameWrongOriginRedirectOverflowOrWholePageFallbackFailClosed() {
        assertThrows(Exception.class, () -> service.capture(request("article.card", List.of(url + "/other")), new Peer()));
        assertThrows(Exception.class, () -> service.capture(request("article:contains(secret)", List.of()), new Peer()));
    }
    @Test void disabledEndpointDuplicateUrlsAndWrongFrameFailBeforeAnyBody() {
        assertThrows(IOException.class, () -> service.capture(request("article.card", List.of())));
        var peer = new Peer();
        assertThrows(IOException.class, () -> service.capture(request("article.card", List.of(url, url)), peer));
        assertTrue(peer.calls.isEmpty());
        peer.scopeFrame = "other-frame";
        assertThrows(IOException.class, () -> service.capture(request("article.card", List.of(url)), peer));
        assertFalse(peer.bodyRead);
        assertTrue(peer.calls.contains("Fetch.disable"));
    }
    @Test void focusOverflowAndSameUrlNavigationInvalidateDomEvidence() {
        var peer = new Peer();
        peer.focused = false;
        assertThrows(IOException.class, () -> service.capture(request("article.card", List.of()), peer));
        peer.focused = true;
        peer.overflow = true;
        assertThrows(IOException.class, () -> service.capture(request("article.card", List.of()), peer));
        peer.overflow = false;
        peer.navigateAt = peer.frameReads + 2;
        assertThrows(IOException.class, () -> service.capture(request("article.card", List.of()), peer));
    }
    @Test void streamHasPreDownloadBudgetAndExplicitIncompleteFlag() throws Exception {
        var peer = new Peer();
        peer.body = new byte[100000];
        var value = service.capture(request("article.card", List.of(url)), peer);
        assertEquals(32, peer.offset);
        assertEquals(32, value.path("responses").get(0).path("responseBytes").asInt());
        assertFalse(value.path("responses").get(0).path("responseComplete").asBoolean());
        assertFalse(value.path("responses").get(0).path("truncatedAfterFullDownload").asBoolean());
    }
    @Test void oversizedChunkStillClosesStreamAbortsRequestAndDisablesInterception() {
        var peer = new Peer();
        peer.oversized = true;
        assertThrows(IOException.class, () -> service.capture(request("article.card", List.of(url)), peer));
        assertTrue(peer.calls.containsAll(List.of("IO.close", "Fetch.failRequest", "Fetch.disable")));
    }
    @Test void closeFailureDoesNotSkipAbortOrDisable() {
        var peer = new Peer();
        peer.closeFails = true;
        var error = assertThrows(IOException.class, () -> service.capture(request("article.card", List.of(url)), peer));
        assertEquals("BROWSER_CLEANUP_UNCONFIRMED", error.getMessage());
        assertTrue(peer.calls.containsAll(List.of("Fetch.failRequest", "Fetch.disable")));
    }
    @Test void repeatedUrlCannotSubstituteForMissingCoverageAndRedirectsKeepNetworkIdentity() {
        var peer = new Peer();
        peer.events.add(peer.event("req-1", url, 200, ""));
        peer.events.add(peer.event("req-2", url, 200, ""));
        assertEquals("BROWSER_DUPLICATE_RESPONSE", assertThrows(IOException.class, () ->
                service.capture(request("article.card", List.of(url, url + "/second")), peer)).getMessage());
        var redirected = new Peer();
        redirected.events.add(redirected.event("req-a", url, 302, ""));
        redirected.events.add(redirected.event("req-b", url, 200, "missing"));
        assertEquals("BROWSER_REDIRECT_CHAIN_INVALID", assertThrows(IOException.class, () ->
                service.capture(request("article.card", List.of(url)), redirected)).getMessage());
    }
}
