package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.RepairSummary;
import cn.har01d.atmaintenance.dto.RepairCandidateSummary;
import cn.har01d.atmaintenance.dto.ReverseAnalysisRequest;
import cn.har01d.atmaintenance.dto.OriginInspectionRequest;
import cn.har01d.atmaintenance.dto.PageDiagnosticsRequest;
import cn.har01d.atmaintenance.dto.ProbeSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Task-owned browser analysis; never executes Spider code or changes acceptance. */
@Service
public class ReverseAnalysis {
    private final ObjectMapper mapper;
    private final RepairStore repairs;
    private final SourceService sources;
    private final String directory, node, browser, python, tunnelsFile, tunnelScript;

    public ReverseAnalysis(ObjectMapper mapper, RepairStore repairs, SourceService sources,
            @Value("${maintenance.reverse-engine.directory:}") String directory,
            @Value("${maintenance.reverse-engine.node:node}") String node,
            @Value("${maintenance.reverse-engine.browser:}") String browser,
            @Value("${maintenance.python:python3}") String python,
            @Value("${maintenance.reverse-engine.tunnels-file:}") String tunnelsFile,
            @Value("${maintenance.reverse-engine.tunnel-script:}") String tunnelScript) {
        this.mapper = mapper;
        this.repairs = repairs;
        this.sources = sources;
        this.directory = directory;
        this.node = node;
        this.browser = browser;
        this.python = python;
        this.tunnelsFile = tunnelsFile;
        this.tunnelScript = tunnelScript;
    }

    public ObjectNode readiness() {
        boolean configured = !directory.isBlank() && !browser.isBlank();
        boolean installed = configured && Files.isRegularFile(Path.of(directory, "runner.mjs"))
                && Files.isDirectory(Path.of(directory, "node_modules", "playwright-core"))
                && Files.isDirectory(Path.of(directory, "node_modules", "webcrack"))
                && Files.isRegularFile(Path.of(browser))
                && (tunnelsFile.isBlank() || Files.isRegularFile(Path.of(tunnelsFile))
                    && Files.isRegularFile(Path.of(tunnelScript))
                    && Files.isRegularFile(Path.of(directory, "remote_broker.py")));
        return mapper.createObjectNode().put("configured", configured).put("ready", installed)
                .put("scriptInspectionReady", lightweightOrigins())
                .put("mode", "reverse_analysis_v1").put("sourceCodeExecuted", false)
                .put("credentialsImported", false).put("productionChanged", false)
                .put("networkLocation", tunnelsFile.isBlank() ? "controller_host" : "source_runner_host");
    }

    boolean lightweightOrigins() {
        return tunnelsFile.isBlank() && !python.isBlank() && new ClassPathResource("worker/broker.py").exists();
    }

    boolean originReady() {
        return lightweightOrigins() || readiness().path("ready").asBoolean();
    }

    public void prepare(RepairSummary repair) {
        if (!eligible(repair)) return;
        if (!readiness().path("ready").asBoolean()) {
            if (!lightweightOrigins()) return;
            var candidates = repairs.candidates(repair.id());
            var request = automaticRequest(repair, candidates,
                    sources.artifact(repair.sourceId(), repair.baselineArtifactSha256()).getBody());
            if (!requestedPageSha(repair, candidates, request.pageUrl()).isEmpty()) inspectScripts(repair.id(), request);
            return;
        }
        JsonNode previous = repair.frozen().path("reverseAnalysis");
        if ("source_origin_inspection".equals(previous.path("operation").asText()) && previous.has("originPool")) {
            if (requiresPublishedOriginFollowup(previous))
                inspectOrigins(repair.id(), new OriginInspectionRequest(repair.faultSha256()));
            return;
        }
        if (previous.path("targetPage").path("challengeObserved").asBoolean()
                && !"content_observed".equals(previous.path("targetPage").path("status").asText())
                && !previous.has("originPool")) {
            inspectOrigins(repair.id(), new OriginInspectionRequest(repair.faultSha256()));
            return;
        }
        var candidates = repairs.candidates(repair.id());
        var request = automaticRequest(repair, candidates, candidates.isEmpty() ? ""
                : sources.artifact(repair.sourceId(), repair.baselineArtifactSha256()).getBody());
        if (request.pageUrl().isEmpty() && repair.frozen().has("reverseAnalysis") && replayTargets(repair, candidates).isEmpty()) return;
        capture(repair.id(), request);
    }

    static ReverseAnalysisRequest automaticRequest(RepairSummary repair, List<RepairCandidateSummary> candidates) {
        return automaticRequest(repair, candidates, "");
    }

    static ReverseAnalysisRequest automaticRequest(RepairSummary repair, List<RepairCandidateSummary> candidates,
                                                   String sourceContent) {
        var evidence = replayEvidence(repair, candidates);
        var hints = new LinkedHashSet<String>();
        addAbsoluteHints(hints, sourceContent);
        for (JsonNode observed : evidence) for (JsonNode resource : observed.path("resourceEvidence").path("resources"))
            for (JsonNode excerpt : resource.path("excerpts")) addAbsoluteHints(hints, excerpt.path("text").asText());
        var bases = new LinkedHashSet<>(hints);
        for (String hint : List.copyOf(bases)) {
            URI url = URI.create(hint);
            bases.add(url.getScheme() + "://" + url.getRawAuthority() + "/");
        }
        // A relative link is only a hint. Its exact resolved URL still needs a host-observed GET.
        for (JsonNode observed : evidence) for (JsonNode resource : observed.path("resourceEvidence").path("resources"))
            for (JsonNode excerpt : resource.path("excerpts")) {
                var document = org.jsoup.Jsoup.parse(excerpt.path("text").asText());
                for (var link : document.select("[href], [src]").stream().limit(256).toList())
                    for (String base : bases) {
                        if (hints.size() >= 4096) break;
                        try {
                            String address = link.hasAttr("href") ? link.attr("href") : link.attr("src");
                            if (!address.isBlank()) addPageHint(hints, URI.create(base).resolve(address).toString());
                        } catch (IllegalArgumentException ignored) { }
                    }
            }
        var bySha = new LinkedHashMap<String, String>();
        hints.forEach(hint -> bySha.put(Hashes.sha256(hint), hint));
        for (JsonNode observed : evidence) {
            var pages = new ArrayList<JsonNode>();
            observed.path("resourceEvidence").path("resources").forEach(resource -> {
                if ("page".equals(resource.path("kind").asText())) pages.add(resource);
            });
            pages.sort(java.util.Comparator.comparingInt(resource -> pagePriority(repair, resource)));
            for (JsonNode resource : pages) {
                String page = bySha.get(resource.path("urlSha256").asText());
                if (page != null && (!requestedPageSha(observed, page).isEmpty() || !observedPageSha(repair, page).isEmpty()))
                    return new ReverseAnalysisRequest(repair.faultSha256(), page, "", true);
            }
        }
        return new ReverseAnalysisRequest(repair.faultSha256(), "", "", true);
    }

    private static List<JsonNode> replayEvidence(RepairSummary repair, List<RepairCandidateSummary> candidates) {
        var evidence = new ArrayList<JsonNode>();
        for (var candidate : candidates.reversed())
            if (boundCandidate(repair, candidate)) evidence.add(candidate.result().path("runtimeEvidence"));
        JsonNode baseline = repair.frozen().path("baseline").path("result").path("managedSourceEvidence");
        if (boundEvidence(baseline, repair.baselineRunId(), repair.baselineArtifactSha256())) evidence.add(baseline);
        return evidence;
    }

    static List<String> replayTargets(RepairSummary repair, List<RepairCandidateSummary> candidates) {
        var targets = new LinkedHashSet<String>();
        for (JsonNode evidence : replayEvidence(repair, candidates)) {
            var pages = evidence.path("resourceEvidence").path("resources").valueStream()
                    .filter(resource -> "page".equals(resource.path("kind").asText()))
                    .sorted(java.util.Comparator.comparingInt(resource -> pagePriority(repair, resource))).toList();
            for (JsonNode resource : pages) for (JsonNode http : evidence.path("httpObservations"))
                if (resource.path("urlSha256").equals(http.path("requestUrlSha256"))
                        && "GET".equals(http.path("method").asText()) && http.path("responseComplete").asBoolean()
                        && http.path("responseStatus").asInt() >= 200 && http.path("responseStatus").asInt() <= 599)
                    targets.add(resource.path("urlSha256").asText());
        }
        return List.copyOf(targets);
    }

    static List<String> replayHints(RepairSummary repair, List<RepairCandidateSummary> candidates) {
        var hints = new LinkedHashSet<String>();
        for (JsonNode evidence : replayEvidence(repair, candidates))
            for (JsonNode resource : evidence.path("resourceEvidence").path("resources"))
                for (JsonNode excerpt : resource.path("excerpts")) addAbsoluteHints(hints, excerpt.path("text").asText());
        return List.copyOf(hints);
    }

    private static int pagePriority(RepairSummary repair, JsonNode resource) {
        String method = resource.path("observedDuringCall").path("method").asText();
        for (JsonNode node : repair.frozen().path("policy").path("targetNodes")) {
            String expected = switch (node.asText()) {
                case "R01" -> "play";
                case "C07", "C10" -> "detail";
                case "C03" -> "category";
                case "C01" -> "home";
                default -> "";
            };
            if (!expected.isEmpty() && expected.equals(method)) return 0;
        }
        return method.equals("play") ? 1 : method.equals("detail") ? 2 : 3;
    }

    private static void addAbsoluteHints(Set<String> hints, String text) {
        if (text == null) return;
        var matches = Pattern.compile("https?://[^\\s<>\"']+").matcher(Parser.unescapeEntities(text, false));
        while (matches.find() && hints.size() < 512) addPageHint(hints, matches.group());
    }

    private static void addPageHint(Set<String> hints, String address) {
        String page = address.split("#", 2)[0];
        if (publicUrl(page)) hints.add(page);
    }

    private static boolean boundEvidence(JsonNode evidence, String job, String artifact) {
        return "managed_source_host_observed".equals(evidence.path("authority").asText())
                && job.equals(evidence.path("jobId").asText())
                && artifact.equals(evidence.path("inputSourceSha256").asText())
                && artifact.equals(evidence.path("resourceEvidence").path("artifactSha256").asText());
    }

    private static boolean boundCandidate(RepairSummary repair, RepairCandidateSummary candidate) {
        if (!repair.id().equals(candidate.repairId()) || candidate.result() == null) return false;
        JsonNode result = candidate.result();
        return candidate.sha256().equals(result.path("artifactSha256").asText())
                && repair.baselineArtifactSha256().equals(result.path("baselineArtifactSha256").asText())
                && boundEvidence(result.path("runtimeEvidence"), result.path("runtimeJobId").asText(candidate.id()), candidate.sha256());
    }

    private static String requestedPageSha(JsonNode evidence, String page) {
        if (!publicUrl(page)) return "";
        String sha = Hashes.sha256(page);
        boolean observed = evidence.path("resourceEvidence").path("resources").valueStream().anyMatch(resource ->
                "page".equals(resource.path("kind").asText()) && sha.equals(resource.path("urlSha256").asText()));
        if (observed) for (JsonNode http : evidence.path("httpObservations"))
            if (sha.equals(http.path("requestUrlSha256").asText()) && "GET".equals(http.path("method").asText())
                    && http.path("responseComplete").asBoolean()
                    && http.path("responseStatus").asInt() >= 200 && http.path("responseStatus").asInt() <= 599) return sha;
        return "";
    }

    static String requestedPageSha(RepairSummary repair, List<RepairCandidateSummary> candidates, String page) {
        for (var candidate : candidates.reversed()) if (boundCandidate(repair, candidate)) {
            String sha = requestedPageSha(candidate.result().path("runtimeEvidence"), page);
            if (!sha.isEmpty()) return sha;
        }
        JsonNode baseline = repair.frozen().path("baseline").path("result").path("managedSourceEvidence");
        return boundEvidence(baseline, repair.baselineRunId(), repair.baselineArtifactSha256())
                ? requestedPageSha(baseline, page) : "";
    }

    static boolean eligible(RepairSummary repair) {
        for (JsonNode node : repair.frozen().path("policy").path("targetNodes"))
            if (Set.of("C01", "C03", "C07", "C10", "R01").contains(node.asText())) return true;
        return false;
    }

    static JsonNode modelReport(ObjectMapper mapper, String request) {
        if (request != null) try {
            for (JsonNode message : mapper.readTree(request).path("messages"))
                if ("user".equals(message.path("role").asText()))
                    return mapper.readTree(message.path("content").asText()).path("reverseAnalysis");
        } catch (IOException ignored) {
            // Missing or unreadable historical input is not evidence for an extra attempt.
        }
        return mapper.nullNode();
    }

    static boolean newMaterial(RepairSummary repair, JsonNode previous) {
        JsonNode current = repair.frozen().path("reverseAnalysis");
        var mapper = new ObjectMapper();
        var origins = RepairEvidenceMaterial.originSignatures(mapper, repair, current);
        if (!origins.isEmpty() && !RepairEvidenceMaterial.originSignatures(mapper, repair, previous).containsAll(origins))
            return true;
        if (previous.isNull() || !"reverse_analysis_v1".equals(current.path("mode").asText())
                || !"host_reverse_browser_observed".equals(current.path("authority").asText())
                || !repair.id().equals(current.path("repairId").asText())
                || !repair.baselineRunId().equals(current.path("runId").asText())
                || !repair.baselineArtifactSha256().equals(current.path("artifactSha256").asText())
                || !current.path("cleanupConfirmed").asBoolean()
                || !current.path("taskId").asText().matches("[a-f0-9-]{36}")
                || current.path("taskId").equals(previous.path("taskId"))) return false;
        if (current.path("targetPage").path("challengeObserved").asBoolean()
                && !"content_observed".equals(current.path("targetPage").path("status").asText())) return false;
        var known = new java.util.HashSet<String>();
        previous.path("scripts").forEach(script -> known.add(script.path("sourceSha256").asText()));
        // A new task ID alone is insufficient: require newly available, complete observed source.
        for (JsonNode script : current.path("scripts")) {
            String sourceSha = script.path("sourceSha256").asText();
            JsonNode excerpt = script.path("excerpt");
            if (sourceSha.matches("[a-f0-9]{64}") && !known.contains(sourceSha)
                    && excerpt.path("complete").asBoolean() && excerpt.path("text").isTextual()
                    && !excerpt.path("text").asText().isBlank()
                    && sourceSha.equals(Hashes.sha256(excerpt.path("text").asText()))) return true;
        }
        return false;
    }

    public synchronized ObjectNode capture(String id, ReverseAnalysisRequest input) {
        if (input != null && input.recoverScripts() && (input.requestPattern() == null || input.requestPattern().isBlank())
                && !readiness().path("ready").asBoolean() && lightweightOrigins()) return inspectScripts(id, input);
        return capture(id, input, false);
    }

    public synchronized ObjectNode inspectScripts(String id, ReverseAnalysisRequest input) {
        return inspectScripts(id, input, List.of());
    }

    public synchronized ObjectNode inspectScripts(String id, ReverseAnalysisRequest input, List<String> scriptUrls) {
        RepairSummary repair = repairs.get(id);
        if (input == null || !repair.faultSha256().equals(input.expectedFaultSha256())) throw error("REPAIR_FAULT_CHANGED");
        requireCurrent(repair);
        if (!lightweightOrigins())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "REVERSE_SCRIPT_INSPECTION_NOT_READY");
        var candidates = repairs.candidates(id);
        String source = sources.artifact(repair.sourceId(), repair.baselineArtifactSha256()).getBody();
        String page = input.pageUrl() == null || input.pageUrl().isBlank()
                ? automaticRequest(repair, candidates, source).pageUrl() : input.pageUrl().trim();
        String observed = requestedPageSha(repair, candidates, page);
        if (observed.isEmpty()) throw error("REVERSE_OBSERVED_PAGE_REQUIRED");
        var payload = mapper.createObjectNode().put("repairId", id).put("runId", repair.baselineRunId())
                .put("artifactSha256", repair.baselineArtifactSha256()).put("pageUrl", page)
                .put("observedPageUrlSha256", observed).put("scriptInspectionOnly", true);
        var targets = payload.putArray("scriptTargets");
        var fields = new LinkedHashSet<String>();
        var hashes = new LinkedHashSet<String>();
        for (JsonNode evidence : replayEvidence(repair, candidates))
            for (JsonNode resource : evidence.path("resourceEvidence").path("resources")) {
                if ("script".equals(resource.path("kind").asText())
                        && observed.equals(resource.path("parentUrlSha256").asText())
                        && resource.path("urlSha256").asText().matches("[a-f0-9]{64}"))
                    hashes.add(resource.path("urlSha256").asText());
                for (JsonNode path : resource.path("jsonStructure").findValues("path"))
                    for (JsonNode field : path) if (field.isTextual()
                            && field.asText().matches("[A-Za-z_][A-Za-z0-9_]{2,47}")) fields.add(field.asText());
            }
        hashes.stream().limit(8).forEach(targets::add);
        if (scriptUrls == null || scriptUrls.size() > 7 || new LinkedHashSet<>(scriptUrls).size() != scriptUrls.size())
            throw error("REVERSE_INPUT_INVALID");
        var declared = payload.putArray("observedScriptUrls");
        for (String url : scriptUrls) {
            if (!publicUrl(url) || !hashes.contains(Hashes.sha256(url))) throw error("REVERSE_OBSERVED_SCRIPT_REQUIRED");
            declared.add(url);
        }
        var consumerFields = payload.putArray("consumerFields");
        fields.stream().limit(16).forEach(consumerFields::add);
        String worker;
        try { worker = originWorkerSha(); }
        catch (IOException failure) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "REVERSE_SCRIPT_INSPECTION_NOT_READY"); }
        String requestSha = Hashes.sha256(payload.toString() + worker);
        ObjectNode cached = cachedReport(repair.frozen().path("reverseAnalysis"), requestSha, false, worker);
        if (cached != null) return cached;
        ObjectNode report = observe(payload).put("analysisRequestSha256", requestSha).put("workerSha256", worker)
                .put("pageReplay", "independent_guest_http").put("failureCauseEstablished", false);
        return repairs.attachReverseAnalysis(id, repair.faultSha256(), report);
    }

    public synchronized ObjectNode captureRun(ProbeSummary run, PageDiagnosticsRequest input) {
        if (input == null || !run.artifactSha256().equals(input.expectedArtifactSha256())
                || !"current".equals(run.freshness()) || !Set.of("COMPLETED", "FAILED").contains(run.status()))
            throw error("AUXILIARY_EVIDENCE_RUN_MISMATCH");
        String source = sources.artifact(run.sourceId(), run.artifactSha256()).getBody();
        String page = runPage(run, source, input.pageUrl());
        String workerSha;
        try { workerSha = workerSha(); }
        catch (IOException failure) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "REVERSE_ENGINE_NOT_READY"); }
        JsonNode saved = run.context() == null ? mapper.nullNode()
                : run.context().path("auxiliaryEvidence").path("pageDiagnostics");
        if (saved.isObject()) {
            if (Hashes.sha256(page).equals(saved.path("pageUrlSha256").asText())) return (ObjectNode) saved;
            throw error("AUXILIARY_EVIDENCE_ALREADY_FROZEN");
        }
        var payload = mapper.createObjectNode().put("repairId", "").put("runId", run.id())
                .put("artifactSha256", run.artifactSha256()).put("pageUrl", page)
                .put("observedPageUrlSha256", Hashes.sha256(page)).put("sourceContent", source)
                .put("requestPattern", "").put("recoverScripts", false).put("originInspectionOnly", false);
        return observe(payload).put("workerSha256", workerSha).put("sourceId", run.sourceId())
                .put("profileSha256", run.profileSha256()).put("baselineFreshness", run.freshness())
                .put("pageReplay", "independent_guest_session").put("sourceCodeExecuted", false)
                .put("acceptanceChanged", false).put("failureCauseEstablished", false);
    }

    static String runPage(ProbeSummary run, String source, String requested) {
        JsonNode evidence = run.result() == null ? null : run.result().path("managedSourceEvidence");
        if (evidence == null || !boundEvidence(evidence, run.id(), run.artifactSha256()))
            throw error("PAGE_DIAGNOSTICS_HOST_EVIDENCE_REQUIRED");
        var hints = new LinkedHashSet<String>();
        if (requested != null && !requested.isBlank()) {
            if (!publicUrl(requested.trim())) throw error("REVERSE_INPUT_INVALID");
            hints.add(requested.trim());
        } else {
            addAbsoluteHints(hints, source);
            for (String hint : List.copyOf(hints)) {
                URI url = URI.create(hint);
                hints.add(url.getScheme() + "://" + url.getRawAuthority() + "/");
            }
        }
        for (String hint : hints) for (JsonNode http : evidence.path("httpObservations"))
            if (Hashes.sha256(hint).equals(http.path("requestUrlSha256").asText())
                    && "GET".equals(http.path("method").asText()) && http.path("responseComplete").asBoolean()) return hint;
        throw error("PAGE_DIAGNOSTICS_OBSERVED_URL_REQUIRED");
    }

    public synchronized ObjectNode inspectOrigins(String id, OriginInspectionRequest input) {
        var request = new ReverseAnalysisRequest(input == null ? null : input.expectedFaultSha256(), "", "", false);
        ObjectNode report = capture(id, request, true);
        return requiresPublishedOriginFollowup(report) ? capture(id, request, true) : report;
    }

    public synchronized ObjectNode discoverOrigins(String id, JsonNode modelOrigins) {
        var repair = repairs.get(id);
        requireCurrent(repair);
        String source = sources.artifact(repair.sourceId(), repair.baselineArtifactSha256()).getBody();
        var candidates = repairs.candidates(id);
        var payload = mapper.createObjectNode().put("repairId", id).put("runId", repair.baselineRunId())
                .put("artifactSha256", repair.baselineArtifactSha256()).put("originInspectionOnly", true)
                .put("searchForOrigins", modelOrigins == null).put("sourceContent", source)
                .put("pageUrl", automaticRequest(repair, candidates, source).pageUrl())
                .put("requestPattern", "").put("recoverScripts", false);
        payload.set("replayTargets", mapper.valueToTree(replayTargets(repair, candidates)));
        payload.set("replayHints", mapper.valueToTree(replayHints(repair, candidates)));
        if (modelOrigins != null) payload.set("modelOrigins", modelOrigins.deepCopy());
        String worker;
        try { worker = originWorkerSha(); }
        catch (IOException failure) { throw error("REVERSE_ENGINE_NOT_READY"); }
        String sha = Hashes.sha256(payload.toString() + worker);
        if (modelOrigins != null) {
            ObjectNode cached = cachedReport(repair.frozen().path("reverseAnalysis"), sha, false, worker);
            if (cached != null) return cached;
        }
        ObjectNode report = observe(payload).put("analysisRequestSha256", sha).put("workerSha256", worker)
                .put("pageReplay", "credential_free_same_path_http").put("sourceCodeExecuted", false);
        return modelOrigins == null ? report : repairs.attachReverseAnalysis(id, repair.faultSha256(), report);
    }

    static boolean requiresPublishedOriginFollowup(JsonNode report) {
        JsonNode pool = report.path("originPool");
        return !"published_redirects".equals(pool.path("inspectionStage").asText())
                && pool.path("observations").valueStream().anyMatch(row -> "redirect".equals(row.path("status").asText())
                    && Set.of(301, 308).contains(row.path("httpStatus").asInt()));
    }

    private ObjectNode capture(String id, ReverseAnalysisRequest input, boolean originInspectionOnly) {
        RepairSummary repair = repairs.get(id);
        if (input == null || !repair.faultSha256().equals(input.expectedFaultSha256())) throw error("REPAIR_FAULT_CHANGED");
        requireCurrent(repair);
        if (!(originInspectionOnly ? originReady() : readiness().path("ready").asBoolean()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "REVERSE_ENGINE_NOT_READY");
        String page = input.pageUrl() == null ? "" : input.pageUrl().trim();
        if (!page.isEmpty() && !publicUrl(page) || input.requestPattern() != null && (input.requestPattern().length() > 512
                || input.requestPattern().chars().anyMatch(value -> value < 32)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "REVERSE_INPUT_INVALID");
        String pattern = input.requestPattern() == null ? "" : input.requestPattern();
        String sourceContent = sources.artifact(repair.sourceId(), repair.baselineArtifactSha256()).getBody();
        var candidates = repairs.candidates(id);
        if (page.isEmpty() && pattern.isEmpty()) page = automaticRequest(repair, candidates, sourceContent).pageUrl();
        var replayTargets = page.isEmpty() && pattern.isEmpty() ? replayTargets(repair, candidates) : List.<String>of();
        var replayHints = replayTargets.isEmpty() ? List.<String>of() : replayHints(repair, candidates);
        String workerSha;
        try { workerSha = originInspectionOnly ? originWorkerSha() : workerSha(); }
        catch (IOException failure) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "REVERSE_ENGINE_NOT_READY"); }
        var requestIdentity = mapper.createObjectNode().put("pageUrl", page)
                .put("requestPattern", pattern).put("recoverScripts", input.recoverScripts())
                .put("originInspectionOnly", originInspectionOnly)
                .put("workerSha256", workerSha);
        requestIdentity.set("replayTargets", mapper.valueToTree(replayTargets));
        requestIdentity.set("replayHints", mapper.valueToTree(replayHints));
        JsonNode publishedRedirects = originInspectionOnly ? publishedRedirects(repair) : mapper.createArrayNode();
        requestIdentity.set("publishedRedirects", publishedRedirects);
        String requestSha = Hashes.sha256(requestIdentity.toString());
        ObjectNode cached = cachedReport(repair.frozen().path("reverseAnalysis"), requestSha,
                originInspectionOnly && "published_redirects".equals(repair.frozen().path("reverseAnalysis")
                    .path("originPool").path("inspectionStage").asText())
                    || !originInspectionOnly && page.isEmpty() && pattern.isEmpty() && replayTargets.isEmpty(), workerSha);
        if (cached != null && originInspectionOnly != "source_origin_inspection".equals(cached.path("operation").asText()))
            cached = null;
        if (cached != null) return cached;
        var payload = mapper.createObjectNode().put("repairId", id)
                .put("runId", repair.baselineRunId()).put("artifactSha256", repair.baselineArtifactSha256())
                .put("pageUrl", page).put("requestPattern", pattern).put("originInspectionOnly", originInspectionOnly)
                .put("observedPageUrlSha256", requestedPageSha(repair, candidates, page))
                .put("sourceContent", sourceContent).put("recoverScripts", input.recoverScripts());
        payload.set("replayTargets", mapper.valueToTree(replayTargets));
        payload.set("replayHints", mapper.valueToTree(replayHints));
        payload.set("publishedRedirects", publishedRedirects);
        ObjectNode report = observe(payload);
        report.put("analysisRequestSha256", requestSha).put("workerSha256", workerSha)
                .put("pageReplay", originInspectionOnly ? "credential_free_same_path_http" : "independent_guest_session")
                .put("sourceCodeExecuted", false).put("profileSha256",
                        repair.frozen().path("baseline").path("profileSha256").asText())
                .put("baselineFreshness", repair.freshness());
        report.set("baselineStaleInputs", mapper.valueToTree(repair.staleInputs()));
        return repairs.attachReverseAnalysis(id, input.expectedFaultSha256(), report);
    }

    ObjectNode observe(ObjectNode payload) {
        boolean scripts = payload.path("scriptInspectionOnly").asBoolean();
        boolean directOrigin = (payload.path("originInspectionOnly").asBoolean() || scripts) && lightweightOrigins();
        if (!directOrigin && !readiness().path("ready").asBoolean())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "REVERSE_ENGINE_NOT_READY");
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory("at-maintenance-reverse-");
            Path egress = temporary.resolve("source_egress.py");
            Files.write(egress, new ClassPathResource("worker/source_egress.py").getContentAsByteArray());
            Files.write(temporary.resolve("source_origin_discovery.py"),
                    new ClassPathResource("worker/source_origin_discovery.py").getContentAsByteArray());
            String taskId = UUID.randomUUID().toString();
            payload.put("taskId", taskId).put("browserExecutable", browser)
                    .put("python", python).put("egressScript", egress.toString())
                    .put("tunnelsFile", tunnelsFile).put("tunnelScript", tunnelScript);
            List<String> command;
            if (directOrigin) {
                Path broker = temporary.resolve("broker.py");
                Files.write(broker, new ClassPathResource("worker/broker.py").getContentAsByteArray());
                command = List.of(python, "-I", "-X", "utf8", broker.toString(), egress.toString(),
                        scripts ? "--inspect-scripts" : "--inspect-origin");
            } else command = List.of(node, "--max-old-space-size=384", Path.of(directory, "runner.mjs").toAbsolutePath().toString());
            byte[] bytes = PythonProcess.runCommand(command, temporary,
                    mapper.writeValueAsBytes(payload), 80, tunnelsFile.isBlank() ? Map.of()
                            : transportEnvironment(mapper.readTree(Files.readAllBytes(Path.of(tunnelsFile))).path("ssh"),
                                System::getenv));
            JsonNode value = mapper.readTree(bytes);
            requireSuccessfulWorker(value);
            if (scripts) requireScriptOperation(value);
            else requireOperation(value, payload.path("originInspectionOnly").asBoolean());
            if (!(value instanceof ObjectNode report) || !taskId.equals(value.path("taskId").asText())
                    || !payload.path("repairId").equals(value.path("repairId"))
                    || !payload.path("runId").equals(value.path("runId"))
                    || !payload.path("artifactSha256").equals(value.path("artifactSha256"))
                    || (!payload.path("pageUrl").asText().isEmpty()
                        && !Hashes.sha256(payload.path("pageUrl").asText()).equals(value.path("pageUrlSha256").asText()))
                    || !value.path("pageUrlSha256").asText().matches("[a-f0-9]{64}")
                    || !value.path("cleanupConfirmed").asBoolean()
                    || value.path("businessChainVerified").asBoolean() || value.path("publishEligible").asBoolean()
                    || value.path("credentialsImported").asBoolean()) throw new IOException("REVERSE_RECEIPT_INVALID");
            return report;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "REVERSE_INTERRUPTED");
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "REVERSE_ANALYSIS_FAILED");
        } finally {
            if (temporary != null) try { PythonProcess.deleteDirectory(temporary); }
            catch (IOException failure) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "REVERSE_CLEANUP_FAILED"); }
        }
    }

    static ObjectNode cachedReport(JsonNode report, String requestSha, boolean defaultTarget) {
        if (!(report instanceof ObjectNode object)) return null;
        if (defaultTarget || requestSha.equals(report.path("analysisRequestSha256").asText())) return object;
        for (JsonNode previous : report.path("previousReports"))
            if (requestSha.equals(previous.path("analysisRequestSha256").asText())) return (ObjectNode) previous;
        return null;
    }

    static ObjectNode cachedReport(JsonNode report, String requestSha, boolean defaultTarget, String workerSha) {
        ObjectNode cached = cachedReport(report, requestSha, defaultTarget);
        return cached != null && workerSha.equals(cached.path("workerSha256").asText()) ? cached : null;
    }

    static void requireSuccessfulWorker(JsonNode value) {
        if (value == null || !value.has("errorCode")) return;
        String code = value.path("errorCode").asText();
        if (!Set.of("REVERSE_TRANSPORT_START_FAILED", "REVERSE_ORIGIN_DISCOVERY_FAILED",
                "REVERSE_BROWSER_START_FAILED", "REVERSE_BROWSER_SETUP_FAILED", "REVERSE_CAPTURE_FAILED",
                "REVERSE_SCRIPT_COLLECTION_FAILED", "REVERSE_ORIGIN_INSPECTION_FAILED", "REVERSE_CLEANUP_FAILED", "REVERSE_INPUT_INVALID").contains(code))
            code = "REVERSE_ANALYSIS_FAILED";
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, code);
    }

    static void requireOperation(JsonNode report, boolean originInspectionOnly) throws IOException {
        String authority = originInspectionOnly ? "host_source_origin_observed" : "host_reverse_browser_observed";
        String operation = originInspectionOnly ? "source_origin_inspection" : "browser_observation";
        if (report == null || !authority.equals(report.path("authority").asText())
                || !operation.equals(report.path("operation").asText())
                || originInspectionOnly && (!report.path("originPool").isObject()
                    || !report.path("browserStarted").isBoolean() || report.path("browserStarted").asBoolean()
                    || report.path("originPool").path("selectionApplied").asBoolean()
                    || report.path("originPool").path("businessChainVerified").asBoolean()))
            throw new IOException("REVERSE_RECEIPT_INVALID");
    }

    static void requireScriptOperation(JsonNode report) throws IOException {
        if (report == null || !"host_public_scripts_observed".equals(report.path("authority").asText())
                || !"script_resource_inspection".equals(report.path("operation").asText())
                || !report.path("browserStarted").isBoolean() || report.path("browserStarted").asBoolean()
                || report.path("sourceCodeExecuted").asBoolean()
                || report.path("consumerExecutionVerified").asBoolean()
                || report.path("transformationVerified").asBoolean()
                || !report.path("scripts").isArray() || !report.path("requests").isArray())
            throw new IOException("REVERSE_RECEIPT_INVALID");
    }

    static JsonNode publishedRedirects(RepairSummary repair) {
        var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        JsonNode previous = repair.frozen().path("reverseAnalysis");
        if (!"host_source_origin_observed".equals(previous.path("authority").asText())
                || !repair.id().equals(previous.path("repairId").asText())
                || !repair.baselineRunId().equals(previous.path("runId").asText())
                || !repair.baselineArtifactSha256().equals(previous.path("artifactSha256").asText())
                || !previous.path("cleanupConfirmed").asBoolean()
                || "published_redirects".equals(previous.path("originPool").path("inspectionStage").asText())) return result;
        for (JsonNode row : previous.path("originPool").path("observations"))
            if ("redirect".equals(row.path("status").asText()) && result.size() < 4) {
                var hint = result.addObject();
                for (String field : List.of("origin", "requestUrlSha256", "status", "httpStatus", "redirectOrigin", "redirectUrlSha256"))
                    if (row.has(field)) hint.set(field, row.path(field).deepCopy());
            }
        return result;
    }

    static String observedPageSha(RepairSummary repair, String page) {
        if (!publicUrl(page)) return "";
        JsonNode evidence = repair.frozen().path("baseline").path("result").path("managedSourceEvidence");
        if (!"managed_source_host_observed".equals(evidence.path("authority").asText())
                || !repair.baselineRunId().equals(evidence.path("jobId").asText())
                || !repair.baselineArtifactSha256().equals(evidence.path("inputSourceSha256").asText())) return "";
        JsonNode resources = evidence.path("resourceEvidence");
        if (!repair.baselineArtifactSha256().equals(resources.path("artifactSha256").asText())) return "";
        String sha = Hashes.sha256(page);
        boolean pageObserved = false;
        for (JsonNode resource : resources.path("resources"))
            if ("page".equals(resource.path("kind").asText()) && sha.equals(resource.path("urlSha256").asText()))
                pageObserved = true;
        if (!pageObserved) return "";
        for (JsonNode http : evidence.path("httpObservations"))
            if (sha.equals(http.path("requestUrlSha256").asText()) && "GET".equals(http.path("method").asText())
                    && http.path("responseStatus").asInt() == 200 && http.path("responseComplete").asBoolean()) return sha;
        return "";
    }

    String workerSha() throws IOException {
        var identity = mapper.createObjectNode();
        for (String name : List.of("runner.mjs", "analysis.mjs", "broker.py", "remote_broker.py", "package-lock.json"))
            identity.put(name, Hashes.sha256(Files.readAllBytes(Path.of(directory, name))));
        identity.put("source_egress.py", Hashes.sha256(new ClassPathResource("worker/source_egress.py").getContentAsByteArray()));
        identity.put("source_origin_discovery.py", Hashes.sha256(new ClassPathResource("worker/source_origin_discovery.py").getContentAsByteArray()));
        if (!tunnelsFile.isBlank()) {
            identity.put("tunnels", Hashes.sha256(Files.readAllBytes(Path.of(tunnelsFile))));
            identity.put("tunnelScript", Hashes.sha256(Files.readAllBytes(Path.of(tunnelScript))));
        }
        return Hashes.sha256(identity.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String originWorkerSha() throws IOException {
        if (!lightweightOrigins()) return workerSha();
        var identity = mapper.createObjectNode();
        for (String name : List.of("broker.py", "source_egress.py", "source_origin_discovery.py"))
            identity.put(name, Hashes.sha256(new ClassPathResource("worker/" + name).getContentAsByteArray()));
        return Hashes.sha256(identity.toString());
    }

    static Map<String, String> transportEnvironment(JsonNode ssh, Function<String, String> lookup) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String name : List.of("HOME", "USERPROFILE")) {
            String value = lookup.apply(name);
            if (value != null) values.put(name, value);
        }
        for (String key : List.of("hostEnv", "portEnv", "userEnv", "passwordEnv")) {
            if (!ssh.has(key)) continue;
            String name = ssh.path(key).asText();
            if (!name.matches("[A-Z][A-Z0-9_]{0,99}")) throw new IOException("REVERSE_TRANSPORT_CONFIGURATION_INVALID");
            String value = lookup.apply(name);
            if (value != null) values.put(name, value);
        }
        return values;
    }

    static void requireCurrent(RepairSummary repair) {
        // Guest-browser diagnostics do not revalidate or execute the historical Spider runtime.
        boolean boundInputs = repair.freshness().equals("current") && repair.staleInputs().isEmpty()
                || repair.freshness().equals("runtime_changed") && !repair.staleInputs().isEmpty()
                    && Set.of("runtime", "execution_policy", "inspector").containsAll(repair.staleInputs());
        if (!boundInputs || !(repair.status().equals("AWAITING_PROPOSAL")
                || repair.status().equals("STOPPED") && ("REPEATED_FAILURE".equals(repair.stopCode())
                    || "ROUND_LIMIT".equals(repair.stopCode()))))
            throw error("REVERSE_REPAIR_NOT_READY");
    }

    static boolean publicUrl(String value) {
        if (value == null || value.length() > 2048 || value.chars().anyMatch(c -> c < 33 || c > 126) || value.contains("\\")) return false;
        try {
            URI url = URI.create(value);
            return ("http".equals(url.getScheme()) || "https".equals(url.getScheme())) && url.getHost() != null
                    && url.getUserInfo() == null && url.getFragment() == null;
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private static ResponseStatusException error(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
}
