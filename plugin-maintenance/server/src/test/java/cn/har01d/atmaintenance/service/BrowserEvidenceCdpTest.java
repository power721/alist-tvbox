package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.BrowserEvidenceRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: only the task-owned offline fixture, never a personal browser page. */
@EnabledIfSystemProperty(named = "maintenance.browser-test-endpoint",
        matches = "ws://127\\.0\\.0\\.1:[0-9]+/devtools/page/[A-Za-z0-9-]+")
class BrowserEvidenceCdpTest {
    final ObjectMapper mapper = new ObjectMapper();

    ObjectNode object() { return mapper.createObjectNode(); }

    @Test void actualFrameDomAndBoundedResponseStreamFromOfflineBrowserFixture() throws Exception {
        URI endpoint = URI.create(System.getProperty("maintenance.browser-test-endpoint"));
        String expectedPage = System.getProperty("maintenance.browser-test-page");
        assertNotNull(expectedPage, "An explicit task-owned fixture URL is required");
        assertTrue(expectedPage.matches("http://127\\.0\\.0\\.1:[0-9]+/tests/evidence-tools\\.html"));
        try (var driver = new BrowserEvidence.CdpPeer(mapper, endpoint)) {
            JsonNode root = driver.call("Page.getFrameTree", object()).path("frameTree").path("frame");
            assertEquals(expectedPage, root.path("url").asText(), "Refuse a different browser page");
            String fixture = "<!doctype html><article class='card' tabindex='0'>fixture <b>text</b></article>";
            driver.call("Runtime.evaluate", object().put("expression",
                    "(()=>{const f=document.createElement('iframe');f.id='maintenance-cdp-fixture';"
                    + "f.srcdoc=" + mapper.writeValueAsString(fixture)
                    + ";document.body.append(f);return true;})()").put("returnByValue", true));
            try {
                JsonNode frame = mapper.missingNode();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < deadline) {
                    var children = driver.call("Page.getFrameTree", object()).path("frameTree").path("childFrames");
                    for (JsonNode child : children)
                        if (child.path("frame").path("url").asText().equals("about:srcdoc"))
                            frame = child.path("frame");
                    if (!frame.isMissingNode()) break;
                    Thread.sleep(25);
                }
                assertFalse(frame.isMissingNode(), "Fixture child frame did not load");
                String frameId = frame.path("id").asText();
                int context = driver.call("Page.createIsolatedWorld", object().put("frameId", frameId)
                        .put("worldName", "maintenance-fixture-driver")).path("executionContextId").asInt();
                driver.call("Runtime.evaluate", object().put("contextId", context)
                        .put("expression", "document.querySelector('article.card').focus()"));
                String address = "https://127.0.0.1/maintenance-cdp-fixture";
                byte[] body = "fixture-payload-".repeat(8192).getBytes(StandardCharsets.UTF_8);
                var patterns = object();
                patterns.putArray("patterns").addObject().put("urlPattern", address).put("requestStage", "Request");
                driver.call("Fetch.enable", patterns);
                try (var connection = new BrowserEvidence.CdpPeer(mapper, endpoint)) {
                    var input = new BrowserEvidenceRequest("00000000-0000-4000-8000-000000000001",
                            "a".repeat(64), frameId, Hashes.sha256("about:srcdoc"), "article.card",
                            true, List.of(address), 32, true);
                    var peer = new BrowserEvidence.Peer() {
                        @Override public JsonNode call(String method, JsonNode params)
                                throws IOException, InterruptedException {
                            JsonNode value = connection.call(method, params);
                            if (method.equals("Fetch.enable")) {
                                driver.call("Runtime.evaluate", object().put("contextId", context)
                                        .put("expression", "fetch(" + mapper.writeValueAsString(address)
                                                + ",{mode:'no-cors'}).catch(()=>{})"));
                                JsonNode paused = driver.event();
                                assertEquals("Fetch.requestPaused", paused.path("method").asText());
                                assertEquals(address, paused.path("params").path("request").path("url").asText());
                                var response = object().put("requestId", paused.path("params").path("requestId").asText())
                                        .put("responseCode", 200)
                                        .put("body", Base64.getEncoder().encodeToString(body));
                                response.putArray("responseHeaders").addObject().put("name", "Content-Type")
                                        .put("value", "application/octet-stream");
                                driver.call("Fetch.fulfillRequest", response);
                            }
                            return value;
                        }
                        @Override public JsonNode event() throws IOException, InterruptedException {
                            return connection.event();
                        }
                        @Override public void close() {}
                    };
                    JsonNode report = new BrowserEvidence(mapper, endpoint.toString()).capture(input, peer);
                    assertEquals(Hashes.sha256("fixture text"), report.path("dom").path("textSha256").asText());
                    assertTrue(report.path("dom").path("focused").asBoolean());
                    JsonNode observed = report.path("responses").get(0);
                    assertEquals(32, observed.path("responseBytes").asInt());
                    assertEquals(Hashes.sha256(Arrays.copyOf(body, 32)), observed.path("responseSha256").asText());
                    assertFalse(observed.path("responseComplete").asBoolean());
                    assertTrue(observed.path("browserRequestAborted").asBoolean());
                    assertTrue(report.path("cleanupConfirmed").asBoolean());
                    assertFalse(report.path("businessChainVerified").asBoolean());
                } finally {
                    driver.call("Fetch.disable", object());
                }
            } finally {
                driver.call("Runtime.evaluate", object().put("expression",
                        "document.getElementById('maintenance-cdp-fixture')?.remove()"));
            }
        }
    }
}
