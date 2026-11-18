package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccessPageClassifierTest {
    private final ObjectMapper mapper = new ObjectMapper();
    static final String CHALLENGE = "<html><head><script>var result = 'private-value';"
            + "document.cookie = 'check=' + result; document.location.reload();</script></head>"
            + "<body>Loading...</body></html>";
    static final String CF_CHALLENGE = "<html><head><title>Just a moment...</title></head>"
            + "<body><noscript>Enable JavaScript and cookies to continue</noscript>"
            + "<script>window._cf_chl_opt = {cType:'managed', privateToken:'private-value'};"
            + "var script = document.createElement('script');"
            + "script.src = '/cdn-cgi/challenge-platform/test';</script></body></html>";
    static final String CF_DENIAL = "<html><head><title>Attention Required! | Cloudflare</title></head>"
            + "<body><div id='cf-wrapper'><div id='cf-error-details'>"
            + "<h1>Sorry, you have been blocked</h1><p>This website is using a security service.</p>"
            + "<div class='cf-error-footer'>Cloudflare Ray ID: private-value</div></div></div></body></html>";

    static ObjectNode denied(ObjectMapper mapper, ObjectNode evidence, String sha) {
        var resource = attach(mapper, evidence, sha, CF_DENIAL);
        ((ObjectNode) evidence.path("httpObservations").get(0)).put("responseStatus", 403);
        ((ObjectNode) resource.path("resources").get(0)).put("responseStatus", 403);
        return resource;
    }

    static ObjectNode attach(ObjectMapper mapper, ObjectNode evidence, String sha, String html) {
        int bytes = html.getBytes(StandardCharsets.UTF_8).length;
        String digest = Hashes.sha256(html), url = "1".repeat(64);
        evidence.withArray("httpObservations").addObject().put("method", "GET")
                .put("requestUrlSha256", url).put("responseStatus", 200).put("responseComplete", true)
                .put("responseSha256", digest).put("responseBytes", bytes);
        var resource = evidence.putObject("resourceEvidence").put("schemaVersion", 1)
                .put("authority", "host_public_resource_observed").put("artifactSha256", sha)
                .put("targetNode", "C03").putNull("image").put("textByteLimit", 18000).put("fetchLimit", 8)
                .put("sourceCodeExecuted", false).put("businessVerdictTrusted", false)
                .put("collectionStatus", "observed").put("textBytes", bytes);
        resource.putArray("failures");
        resource.putArray("resources").addObject().put("kind", "page").put("urlSha256", url)
                .putNull("parentUrlSha256").put("responseSha256", digest).put("responseBytes", bytes)
                .putArray("excerpts").addObject().put("offset", 0).put("text", html).put("textSha256", digest);
        return resource;
    }

    static void observeDuring(ObjectMapper mapper, ObjectNode evidence, String method) {
        var call = mapper.createObjectNode().put("id", 3).put("method", method).put("inputSha256", "e".repeat(64));
        evidence.withObject("loaderValidation").withArray("calls").add(call.deepCopy());
        ((ObjectNode) evidence.path("resourceEvidence").path("resources").get(0)).set("observedDuringCall", call);
    }

    @Test
    void completeCloudflareDenialIsDistinctFromAChallengeWithoutPrivateData() throws Exception {
        var evidence = mapper.createObjectNode();
        var resource = denied(mapper, evidence, "a".repeat(64));
        observeDuring(mapper, evidence, "category");
        var projected = new ManagedResourceEvidence(mapper).project(resource, "a".repeat(64),
                evidence.path("httpObservations"), evidence.path("loaderValidation").path("calls"));
        var result = AccessPageClassifier.classify(mapper, projected);
        assertNotNull(result);
        assertEquals("cloudflare_access_denied", result.path("classification").asText());
        assertEquals("C03", result.path("observedNode").asText());
        assertTrue(result.path("denialTemplateObserved").asBoolean());
        assertFalse(result.path("failureCauseEstablished").asBoolean());
        assertFalse(result.has("challengeOptionsObserved"));
        assertFalse(result.toString().contains("private-value"));
        for (String defect : List.of("title", "heading", "wrapper", "status")) {
            String html = switch (defect) {
                case "title" -> CF_DENIAL.replace("Attention Required! | Cloudflare", "Forum");
                case "heading" -> CF_DENIAL.replace("Sorry, you have been blocked", "Forum topics");
                case "wrapper" -> CF_DENIAL.replace("cf-error-details", "article");
                default -> CF_DENIAL;
            };
            var candidate = mapper.createObjectNode();
            var input = attach(mapper, candidate, "a".repeat(64), html);
            ((ObjectNode) input.path("resources").get(0)).put("responseStatus", defect.equals("status") ? 200 : 403);
            assertNull(AccessPageClassifier.classify(mapper, input), defect);
        }
    }

    @Test
    void identifiesCloudflareBodyAtAnyHttpStatusAndKeepsTheObservedCallSeparate() throws Exception {
        for (int status : List.of(200, 403, 503)) {
            var evidence = mapper.createObjectNode();
            var resource = attach(mapper, evidence, "a".repeat(64), CF_CHALLENGE);
            resource.put("targetNode", "C07");
            ((ObjectNode) evidence.path("httpObservations").get(0)).put("responseStatus", status);
            ((ObjectNode) resource.path("resources").get(0)).put("responseStatus", status);
            observeDuring(mapper, evidence, "category");
            var projected = new ManagedResourceEvidence(mapper).project(resource, "a".repeat(64),
                    evidence.path("httpObservations"), evidence.path("loaderValidation").path("calls"));
            var result = AccessPageClassifier.classify(mapper, projected);
            assertNotNull(result);
            assertEquals("cloudflare_challenge", result.path("classification").asText());
            assertEquals("cloudflare", result.path("vendor").asText());
            assertEquals(status, result.path("responseStatus").asInt());
            assertEquals("C07", result.path("targetNode").asText());
            assertEquals("C03", result.path("observedNode").asText());
            assertEquals("category", result.path("observedDuringCall").path("method").asText());
            assertFalse(result.path("failureCauseEstablished").asBoolean());
            assertFalse(result.path("scriptsExecuted").asBoolean());
            assertFalse(result.toString().contains("private-value"));
        }
    }

    @Test
    void identifiesCompleteCookieReloadShellWithoutVendorGuessOrPrivateContent() throws Exception {
        var evidence = mapper.createObjectNode();
        var resource = attach(mapper, evidence, "a".repeat(64), CHALLENGE);
        var projected = new ManagedResourceEvidence(mapper).project(resource, "a".repeat(64),
                evidence.path("httpObservations"), mapper.createArrayNode());
        var result = AccessPageClassifier.classify(mapper, projected);
        assertNotNull(result);
        assertEquals("javascript_cookie_challenge", result.path("classification").asText());
        assertEquals("unattributed", result.path("vendor").asText());
        assertFalse(result.path("scriptsExecuted").asBoolean());
        assertFalse(result.toString().contains("private-value"));
        assertEquals(resource, projected);
    }

    @Test
    void normalCookiePagesScriptDocumentationAndApplicationShellsAreNotChallenges() {
        for (String html : List.of(
                CHALLENGE.replace("Loading...", "Forum topics and magnet downloads"),
                CHALLENGE.replace("Loading...", "<a href='/thread/1'>Topic</a>"),
                CHALLENGE.replace("Loading...", "<pre>Loading...</pre>"),
                CHALLENGE.replace("</head>", "<script src='/app.js'></script></head>"),
                CHALLENGE.replace("document.location.reload();", "console.log('analytics');"),
                CHALLENGE.replace("document.cookie = 'check=' + result;", "var cookieNotice = true;"),
                CF_CHALLENGE.replace("_cf_chl_opt", "appOptions"),
                CF_CHALLENGE.replace("/cdn-cgi/challenge-platform/", "/static/app/"),
                CF_CHALLENGE.replace("Just a moment...", "Forum").replace("Enable JavaScript and cookies to continue", "Topics"),
                "<html><head><title>Just a moment...</title></head><body>Access denied</body></html>",
                "<html><body><form><div class='cf-turnstile'></div></form>"
                        + "<script src='https://challenges.cloudflare.com/turnstile/v0/api.js'></script></body></html>",
                "<html><body><pre>window._cf_chl_opt = {}; /cdn-cgi/challenge-platform/ Just a moment...</pre></body></html>",
                "<html><body>Please enable cookies</body></html>",
                "{\"message\":\"document.cookie=x;location.reload(); Loading\"}")) {
            assertNull(AccessPageClassifier.classify(mapper,
                    attach(mapper, mapper.createObjectNode(), "a".repeat(64), html)), html);
        }
    }

    @Test
    void partialExcerptsAndDifferentFullResponseHashesCannotBlockRepair() {
        for (String html : List.of(CHALLENGE, CF_CHALLENGE, CF_DENIAL)) {
            for (String defect : List.of("offset", "bytes", "hash")) {
                var resource = attach(mapper, mapper.createObjectNode(), "a".repeat(64), html);
                var page = (ObjectNode) resource.path("resources").get(0);
                page.put("responseStatus", 403);
                switch (defect) {
                    case "offset" -> ((ObjectNode) page.path("excerpts").get(0)).put("offset", 1);
                    case "bytes" -> page.put("responseBytes", page.path("responseBytes").asInt() + 100);
                    case "hash" -> page.put("responseSha256", "b".repeat(64));
                }
                assertNull(AccessPageClassifier.classify(mapper, resource), defect);
            }
        }
    }
}
