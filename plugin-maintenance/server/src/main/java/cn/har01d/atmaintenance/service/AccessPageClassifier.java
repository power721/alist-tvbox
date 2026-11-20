package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Classifies already-projected response data without executing page scripts. */
final class AccessPageClassifier {
    private static final Pattern COOKIE_WRITE = Pattern.compile("\\bdocument\\s*\\.\\s*cookie\\s*=(?!=)");
    private static final Pattern RELOAD = Pattern.compile(
            "\\b(?:document\\s*\\.\\s*|window\\s*\\.\\s*)?location\\s*\\.\\s*reload\\s*\\(");
    private static final Pattern WAITING = Pattern.compile(
            "(?i)(?:loading|please wait|checking your browser|verifying your browser)[\\s.!]*");
    private static final Pattern CF_OPTIONS = Pattern.compile("\\b_cf_chl_opt\\s*=(?!=)");
    private static final Pattern CF_TITLE = Pattern.compile("(?i)just a moment[\\s.!\\u2026]*");
    private static final Pattern CF_DENIAL_TITLE = Pattern.compile("(?i)attention required[\\s!]*\\|\\s*cloudflare");

    private AccessPageClassifier() { }

    static ObjectNode classify(ObjectMapper mapper, JsonNode projected) {
        for (JsonNode page : projected.path("resources")) {
            JsonNode excerpts = page.path("excerpts");
            if (!page.path("kind").asText().equals("page") || excerpts.size() != 1) continue;
            JsonNode excerpt = excerpts.get(0);
            String text = excerpt.path("text").asText();
            // A partial excerpt cannot establish that the response has no business content.
            if (excerpt.path("offset").asInt(-1) != 0 || text.isEmpty()
                    || text.getBytes(StandardCharsets.UTF_8).length != page.path("responseBytes").asInt(-1)
                    || !Hashes.sha256(text).equals(page.path("responseSha256").asText())) continue;
            var document = Jsoup.parse(text);
            var denied = document.selectFirst("body > #cf-wrapper #cf-error-details");
            if (page.path("responseStatus").asInt() == 403 && denied != null
                    && CF_DENIAL_TITLE.matcher(document.title().strip()).matches()
                    && denied.select("h1").text().strip().equalsIgnoreCase("Sorry, you have been blocked")) {
                return observation(mapper, projected, page, "cloudflare_access_denied", "cloudflare")
                        .put("denialTemplateObserved", true);
            }
            String visible = document.body().text().strip();
            String script = document.select("script:not([src])").stream()
                    .map(element -> element.data()).collect(java.util.stream.Collectors.joining("\n"));
            if (CF_OPTIONS.matcher(script).find() && script.contains("/cdn-cgi/challenge-platform/")
                    && (CF_TITLE.matcher(document.title().strip()).matches()
                        || visible.toLowerCase(java.util.Locale.ROOT)
                            .contains("enable javascript and cookies to continue"))) {
                return observation(mapper, projected, page, "cloudflare_challenge", "cloudflare")
                        .put("challengeOptionsObserved", true).put("challengePlatformObserved", true);
            }
            if (!visible.isEmpty() && !WAITING.matcher(visible).matches()) continue;
            if (!document.select("a[href], img[src], video, audio, table, article, pre, code, form, input, script[src]")
                    .isEmpty()) continue;
            if (!COOKIE_WRITE.matcher(script).find() || !RELOAD.matcher(script).find()) continue;
            return observation(mapper, projected, page, "javascript_cookie_challenge", "unattributed")
                    .put("cookieWriteObserved", true).put("reloadObserved", true)
                    .put("waitingShellObserved", true);
        }
        return null;
    }

    static boolean blocksRepair(String classification) {
        return "javascript_cookie_challenge".equals(classification) || "cloudflare_challenge".equals(classification)
                || "cloudflare_access_denied".equals(classification);
    }

    private static ObjectNode observation(ObjectMapper mapper, JsonNode projected, JsonNode page,
                                          String classification, String vendor) {
        var result = mapper.createObjectNode().put("classification", classification)
                .put("targetNode", projected.path("targetNode").asText())
                .put("basis", "complete_http_page_body").put("vendor", vendor)
                .put("urlSha256", page.path("urlSha256").asText())
                .put("responseSha256", page.path("responseSha256").asText())
                .put("responseBytes", page.path("responseBytes").asInt())
                .put("scriptsExecuted", false).put("failureCauseEstablished", false);
        if (page.has("responseStatus")) result.set("responseStatus", page.get("responseStatus"));
        JsonNode call = page.path("observedDuringCall");
        if (call.isObject()) {
            result.set("observedDuringCall", call.deepCopy());
            result.put("observedNode", RepairReport.NETWORK_METHOD_NODES.getOrDefault(call.path("method").asText(), ""));
        }
        return result;
    }
}
