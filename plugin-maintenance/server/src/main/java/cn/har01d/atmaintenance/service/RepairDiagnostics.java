package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.ProbeSummary;
import cn.har01d.atmaintenance.dto.RepairCandidateSummary;
import cn.har01d.atmaintenance.dto.RepairSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Read-only triage of stored evidence. Does not execute sources, tools or model calls. */
@Service
@RequiredArgsConstructor
public class RepairDiagnostics {
    private final ObjectMapper mapper;

    public ObjectNode forRun(ProbeSummary run) {
        JsonNode result = run.result() == null ? mapper.missingNode() : run.result();
        var diagnostic = build(run.id(), run.artifactSha256(), run.profileSha256(), run.runtimeSha256(),
                result.path("managedSourceEvidence"), run.freshness(), null);
        if ("managed_source".equals(run.executionMode())) {
            JsonNode failure = storedRunFailure(result, run.artifactSha256(), run.profileSha256(),
                    run.runtimeSha256(), run.executionPolicySha256(), run.errorCode());
            if (failure != null) failureDiagnostic(diagnostic, failure);
        }
        return diagnostic;
    }

    private ObjectNode storedRunFailure(JsonNode report, String sha, String profile, String runtime,
                                        String policy, String code) {
        if (report.hasNonNull("managedSourceEvidence")
                || !"managed_source_blocked".equals(report.path("verdict").asText())) return null;
        String[] values = {sha, profile, runtime, policy};
        String[] fields = {"artifactSha256", "profileSha256", "runtimeSha256", "executionPolicySha256"};
        for (int i = 0; i < values.length; i++)
            if (values[i] == null || !values[i].matches("[a-f0-9]{64}")
                    || !values[i].equals(report.path(fields[i]).asText())) return null;
        JsonNode diagnostic = report.path("managedExecutionDiagnostic");
        if (!boundedInteger(diagnostic.path("schemaVersion"), 1, 1)
                || !"managed_source_broker_observed".equals(diagnostic.path("authority").asText())) return null;
        var failure = mapper.createObjectNode().put("code", code);
        failure.set("diagnostic", diagnostic);
        return failure;
    }

    public boolean automaticEnhancement(RepairSummary repair, List<RepairCandidateSummary> candidates) {
        return forRepair(repair, candidates).path("automaticEnhancementRecommended").asBoolean();
    }

    private boolean automaticEnhancement(RepairSummary repair, JsonNode diagnostic) {
        return "current".equals(repair.freshness()) && diagnostic.has("candidateId")
                && diagnostic.path("loaderReceiptMatched").asBoolean()
                && !diagnostic.path("latestCandidateEvidenceUnavailable").asBoolean()
                && diagnostic.path("routes").valueStream().allMatch(route ->
                    Set.of("source_contract", "player_resource").contains(route.asText())
                        || "access_evidence".equals(route.asText())
                            && "pass".equals(diagnostic.path("accessPage").path("observedNodeStatus").asText())
                            && diagnostic.path("http").path("statusCounts").properties().stream()
                                .anyMatch(row -> row.getKey().matches("2[0-9]{2}") && row.getValue().asInt() > 0))
                && diagnostic.path("findings").valueStream().anyMatch(finding ->
                    Set.of("C01", "C03", "C07", "C10", "R01").contains(finding.path("node").asText())
                        && Set.of("source_contract", "player_resource").contains(finding.path("route").asText()));
    }

    public ObjectNode forRepair(RepairSummary repair, List<RepairCandidateSummary> candidates) {
        JsonNode baseline = repair.frozen().path("baseline");
        String sha = repair.baselineArtifactSha256();
        JsonNode evidence = baseline.path("result").path("managedSourceEvidence");
        String evidenceRuntime = baseline.path("runtimeSha256").asText();
        String candidateId = null;
        JsonNode failure = mapper.missingNode();
        JsonNode inputs = RepairReport.runtimeInputs(repair.frozen());
        // Only the latest observed candidate may supersede the original failure.
        var latest = candidates.stream().filter(candidate -> repair.id().equals(candidate.repairId())
                        && candidate.roundNumber() >= 1 && candidate.roundNumber() <= repair.maxRounds()
                        && candidate.result() != null && candidate.result().path("runtimeRequested").asBoolean())
                .max(Comparator.comparingInt(RepairCandidateSummary::roundNumber));
        if (latest.isPresent()) {
            var candidate = latest.get();
            JsonNode runtime = candidate.result().path("runtimeEvidence");
            if (candidate.sha256().equals(candidate.result().path("artifactSha256").asText())
                    && bound(runtime, candidate.sha256(), baseline.path("profileSha256").asText(),
                        inputs.path("runtimeSha256").asText())) {
                sha = candidate.sha256();
                evidence = runtime;
                evidenceRuntime = inputs.path("runtimeSha256").asText();
                candidateId = candidate.id();
            } else {
                JsonNode failed = candidate.result().path("managedExecutionFailure");
                if (candidate.sha256().equals(candidate.result().path("artifactSha256").asText())
                        && candidate.id().equals(failed.path("candidateId").asText())
                        && candidate.result().path("runtimeJobId").asText(candidate.id())
                            .equals(failed.path("jobId").asText())
                        && candidate.sha256().equals(failed.path("inputSourceSha256").asText())
                        && baseline.path("profileSha256").equals(failed.path("inputProfileSha256"))
                        && inputs.path("runtimeSha256").equals(failed.path("bundleSha256"))
                        && inputs.path("executionPolicySha256").isTextual()
                        && inputs.path("executionPolicySha256").equals(failed.path("executionPolicySha256"))
                        && failed.path("diagnostic").path("authority").asText()
                            .equals("managed_source_broker_observed")) {
                    sha = candidate.sha256();
                    evidence = mapper.missingNode();
                    evidenceRuntime = inputs.path("runtimeSha256").asText();
                    candidateId = candidate.id();
                    failure = failed;
                }
            }
        }
        var result = build(repair.baselineRunId(), sha, baseline.path("profileSha256").asText(),
                evidenceRuntime, evidence, repair.freshness(), candidateId);
        result.put("repairId", repair.id()).put("baselineArtifactSha256", repair.baselineArtifactSha256())
                .put("usedRounds", repair.usedRounds()).put("maxRounds", repair.maxRounds());
        result.put("latestCandidateEvidenceUnavailable", latest.isPresent() && candidateId == null);
        if (failure.isMissingNode() && latest.isEmpty()) {
            JsonNode originalFailure = storedRunFailure(baseline.path("result"), repair.baselineArtifactSha256(),
                    baseline.path("profileSha256").asText(), baseline.path("runtimeSha256").asText(),
                    baseline.path("executionPolicySha256").asText(), baseline.path("errorCode").asText());
            if (originalFailure != null) failure = originalFailure;
        }
        if (!failure.isMissingNode()) failureDiagnostic(result, failure);
        else if (candidateId != null) regressionDiagnostics(result, repair, latest.orElseThrow());
        result.put("automaticEnhancementRecommended", automaticEnhancement(repair, result));
        return result;
    }

    private void regressionDiagnostics(ObjectNode diagnostic, RepairSummary repair, RepairCandidateSummary candidate) {
        JsonNode baseline = repair.frozen().path("baseline");
        JsonNode original = baseline.path("result").path("managedSourceEvidence");
        JsonNode result = candidate.result();
        if (!bound(original, repair.baselineArtifactSha256(), baseline.path("profileSha256").asText(),
                    baseline.path("runtimeSha256").asText())) return;
        JsonNode before = original.path("untrustedRuntimeSummary");
        JsonNode after = result.path("runtimeEvidence").path("untrustedRuntimeSummary");
        for (String field : RepairReport.OUTPUT_FIELDS) {
            boolean network = RepairReport.networkAffectedOutput(before, result.path("runtimeEvidence"), field);
            boolean compared = result.path("runtimeComparison").valueStream().anyMatch(row ->
                    field.equals(row.path("field").asText()) && row.path("comparable").asBoolean());
            if (!network && (result.path("runtimeValidationPaused").asBoolean() && !compared
                    || !result.path("observedRegressions").valueStream()
                        .anyMatch(value -> value.asText().equals(field)))) continue;
            JsonNode previous = before.path(field), current = after.path(field);
            if (!previous.isIntegralNumber() || !previous.canConvertToInt() || previous.asInt() <= 0
                    || previous.asInt() > 10000 || !current.isIntegralNumber()
                    || !current.canConvertToInt() || current.asInt() != 0) continue;
            // A loss observed by the existing gate is not a failed loader node or an upstream root cause.
            String selectedRoute = network ? "network" : "source_regression";
            var finding = diagnostic.withArray("findings").addObject().put("node", "").put("status", "blocked")
                    .put("reason", network ? "RUNTIME_NETWORK_UNAVAILABLE" : "RUNTIME_REGRESSION")
                    .put("route", selectedRoute)
                    .put("basis", "matched_runtime_comparison").put("failureCauseEstablished", false);
            if (network) finding.put("networkFailureObserved", true)
                    .put("networkAssociation", "source_method_observation");
            finding.putObject("regression").put("field", field).put("before", previous.asInt()).put("after", 0)
                    .put("basis", "untrusted_source_output_count");
            if (!diagnostic.withArray("routes").valueStream().anyMatch(value -> value.asText().equals(selectedRoute))) {
                diagnostic.withArray("routes").add(selectedRoute);
                diagnostic.withArray("nextActions").addObject().put("route", selectedRoute)
                        .put("action", action(selectedRoute));
            }
            diagnostic.put("conclusion", "triage_only");
        }
    }

    private void failureDiagnostic(ObjectNode result, JsonNode failure) {
        JsonNode diagnostic = failure.path("diagnostic");
        String code = ManagedLoaderReceipt.safeReason(failure.path("code").asText());
        String selectedRoute = route("", code, null);
        result.put("hostFailureDiagnosticMatched", true);
        // Failure observations are not a loader receipt and cannot promote any acceptance node.
        var finding = result.withArray("findings").addObject().put("node", "").put("status", "blocked")
                .put("reason", code).put("route", selectedRoute).put("basis", "matched_host_failure_diagnostic")
                .put("failureCauseEstablished", false);
        JsonNode context = diagnostic.path("exchangeContext");
        if (Set.of("ready", "source", "frame", "loader", "fetch", "exit").contains(context.path("phase").asText()))
            finding.put("executionPhase", context.path("phase").asText());
        // The tail observation can predate the terminal error; never call it the current failure cause.
        var observations = mapper.createObjectNode();
        observations.putArray("networkObservations").add(context.path("networkObservation"));
        observations.putArray("rateLimitObservations").add(context.path("rateLimit"));
        transportDiagnostics(result, observations);
        cooldownDiagnostics(result, observations);
        for (String field : List.of("transportEvidence", "cooldownEvidence")) {
            if (result.path(field).isObject()) ((ObjectNode) result.path(field))
                    .put("basis", "matched_host_failure_diagnostic").put("scope", "last_recorded_observation");
        }
        if (context.path("dnsObservations").isArray() && !context.path("dnsObservations").isEmpty()) {
            dnsDiagnostics(result, context.path("dnsObservations"));
            if (result.path("dnsEvidence").isObject()) {
                ((ObjectNode) result.path("dnsEvidence")).put("basis", "matched_host_failure_diagnostic")
                        .put("scope", "recorded_resolution_stages_before_interruption");
                if (boundedInteger(context.path("dnsObservationsDropped"), 0, 2048))
                    ((ObjectNode) result.path("dnsEvidence")).set("observationsDropped", context.path("dnsObservationsDropped"));
            }
        } else dnsDiagnostics(result, context.path("dnsObservation"));
        JsonNode request = context.path("requestDiagnostic");
        if (code.equals("EGRESS_HEADERS_DENIED") && request.path("kind").asText().equals("header")
                && request.path("reason").asText().equals("header_not_allowed")
                && request.path("headerName").asText().matches("[a-z][a-z0-9-]{0,63}"))
            finding.putObject("requestDiagnostic").put("kind", "header").put("reason", "header_not_allowed")
                    .put("headerName", request.path("headerName").asText());
        if (!result.withArray("routes").valueStream().anyMatch(item -> item.asText().equals(selectedRoute))) {
            result.withArray("routes").add(selectedRoute);
            result.withArray("nextActions").addObject().put("route", selectedRoute).put("action", action(selectedRoute));
        }
        int complete = 0;
        long bytes = 0;
        var statuses = new java.util.TreeMap<Integer, Integer>();
        for (JsonNode response : diagnostic.path("exchangeContext").path("completedHttpObservations")
                .valueStream().limit(64).toList()) {
            int status = response.path("responseStatus").asInt();
            JsonNode size = response.path("responseBytes");
            if (response.path("responseComplete").asBoolean() && status >= 100 && status <= 599
                    && size.isIntegralNumber() && size.canConvertToLong()
                    && size.asLong() >= 0 && size.asLong() <= Long.MAX_VALUE / 64) {
                complete++;
                bytes += size.asLong();
                statuses.merge(status, 1, Integer::sum);
            }
        }
        var http = result.putObject("http").put("completedResponses", complete).put("completedResponseBytes", bytes);
        var counts = http.putObject("statusCounts");
        statuses.forEach((status, count) -> counts.put(status.toString(), count));
        if (complete > 0) {
            var missing = result.putArray("missingMaterials");
            // The missing loader receipt remains explicit even when HTTP responses were completed.
            missing.add("matched_loader_receipt");
            if (!result.path("freshness").asText().equals("current")) missing.add("current_input_receipt");
        }
        result.put("conclusion", "triage_only");
    }

    private void dnsDiagnostics(ObjectNode result, JsonNode input) {
        dnsDiagnostics(result, input, mapper.createObjectNode());
    }

    private void dnsDiagnostics(ObjectNode result, JsonNode input, JsonNode evidence) {
        if (input.isArray()) {
            for (JsonNode row : input.valueStream().limit(64).toList()) dnsDiagnostics(result, row, evidence);
            if (result.path("dnsEvidence").isObject()) ((ObjectNode) result.path("dnsEvidence"))
                    .put("basis", "matched_runtime_dns_observations").put("scope", "recorded_resolution_stages");
            return;
        }
        JsonNode row = input;
        if (!(row.path("dnsStatus").isNull() || boundedInteger(row.path("dnsStatus"), 0, 65535))
                || !boundedInteger(row.path("answerCount"), 0, 65535)
                || !boundedInteger(row.path("publicIpv4Count"), 0, 16)
                || !Set.of("success", "no_address", "resolver_error", "nonpublic_address", "invalid_response", "transport_error")
                    .contains(row.path("kind").asText())) return;
        var dns = result.withObject("dnsEvidence").put("basis", "matched_host_failure_diagnostic")
                .put("scope", "last_recorded_observation").put("currentAvailabilityEstablished", false)
                .put("failureCauseEstablished", false);
        var safe = dns.withArray("observations").addObject();
        for (String field : List.of("dnsStatus", "answerCount", "publicIpv4Count", "kind"))
            safe.set(field, row.path(field).deepCopy());
        if ("AAAA".equals(row.path("recordType").asText()) && boundedInteger(row.path("publicIpv6Count"), 0, 16))
            safe.put("recordType", "AAAA").set("publicIpv6Count", row.path("publicIpv6Count").deepCopy());
        if ("A".equals(row.path("recordType").asText())) safe.put("recordType", "A");
        for (String field : List.of("resolver", "fallbackFrom"))
            if (Set.of("alidns", "cloudflare").contains(row.path(field).asText())) safe.set(field, row.path(field));
        if (row.path("cacheHit").isBoolean()) safe.set("cacheHit", row.path("cacheHit"));
        if (!row.path("cacheHit").asBoolean() && boundedInteger(row.path("elapsedMilliseconds"), 0, 120000))
            safe.set("elapsedMilliseconds", row.path("elapsedMilliseconds"));
        copyObservedCall(safe, row, evidence);
    }

    private ObjectNode build(String runId, String sha, String profileSha, String runtimeSha,
                             JsonNode evidence, String freshness, String candidateId) {
        var result = mapper.createObjectNode().put("schemaVersion", 1)
                .put("mode", "enhanced_diagnostics_v1").put("authority", "stored_evidence_triage")
                .put("runId", runId).put("artifactSha256", sha).put("freshness", freshness)
                .put("readOnly", true).put("newNetworkRequests", 0).put("newModelCalls", 0)
                .put("bypassImplemented", false).put("acceptanceChanged", false);
        if (candidateId != null) result.put("candidateId", candidateId);
        if (profileSha != null && profileSha.matches("[a-f0-9]{64}")) result.put("profileSha256", profileSha);
        if (runtimeSha != null && runtimeSha.matches("[a-f0-9]{64}")) result.put("runtimeSha256", runtimeSha);
        var findings = result.putArray("findings");
        var missing = new LinkedHashSet<String>();
        var routes = new LinkedHashSet<String>();
        boolean verified = bound(evidence, sha, profileSha, runtimeSha);
        result.put("loaderReceiptMatched", verified);
        boolean structure = false;
        JsonNode resources = mapper.createArrayNode();
        JsonNode playerContext = mapper.missingNode();
        ObjectNode access = null;
        if (verified && evidence.has("resourceEvidence")) {
            try {
                var projected = new ManagedResourceEvidence(mapper).project(
                        evidence.path("resourceEvidence"), sha, evidence.path("httpObservations"),
                        evidence.path("loaderValidation").path("calls"));
                structure = projected.path("resources").valueStream().anyMatch(row -> !row.path("excerpts").isEmpty());
                resources = projected.path("resources");
                playerContext = projected.path("playerContext");
                structure |= resources.valueStream().anyMatch(row -> row.path("kind").asText().equals("api_json"));
                access = AccessPageClassifier.classify(mapper, projected);
                if (access != null) result.set("accessPage", access);
            } catch (IOException ignored) {
                missing.add("bound_resource_evidence");
            }
        }
        // Call intervals constrain association, but do not establish request or failure causality.
        String accessTarget = access == null ? "" : access.has("observedDuringCall")
                ? access.path("observedNode").asText() : access.path("targetNode").asText();
        String accessClassification = access == null ? "" : access.path("classification").asText();
        String accessAssociation = access != null && access.has("observedDuringCall")
                ? "observed_during_call" : "diagnostic_target_only";
        if (access != null && access.has("observedDuringCall")) {
            for (JsonNode node : evidence.path("loaderValidation").path("nodes")) {
                if (accessTarget.equals(node.path("id").asText())
                        && Set.of("pass", "fail", "blocked", "not_tested").contains(node.path("status").asText())) {
                    access.put("observedNodeStatus", node.path("status").asText());
                    break;
                }
            }
        }
        if (access != null) routes.add("access_evidence");
        ObjectNode authentication = verified ? SourceAuthenticationEvidence.detect(mapper, sha, evidence) : null;
        if (authentication != null) result.set("authenticationEvidence", authentication);
        if (verified) {
            for (JsonNode node : evidence.path("loaderValidation").path("nodes").valueStream().limit(44).toList()) {
                String id = node.path("id").asText();
                String status = node.path("status").asText();
                if (!id.matches("[SCRMP][0-9]{2}") || !Set.of("fail", "blocked").contains(status)) continue;
                String reason = ManagedLoaderReceipt.safeReason(node.path("reason").asText());
                String type = ManagedLoaderReceipt.safeFailureType(node.path("failureType").asText());
                String route = route(id, reason, type);
                if (authentication != null && id.equals(authentication.path("targetNode").asText())
                        && route.equals("source_contract")) route = "credentials";
                boolean challenge = id.equals(accessTarget) && route.equals("source_contract");
                if (challenge) route = "access_evidence";
                boolean network = route.equals("source_contract") && networkFailureForNode(evidence, id);
                if (network) route = "network";
                ObjectNode actionHttp = actionHttpEvidence(evidence, resources, id);
                if (route.equals("source_contract") && actionHttp != null
                        && actionHttp.has("unavailableRoute")) route = actionHttp.path("unavailableRoute").asText();
                var finding = findings.addObject().put("node", id).put("status", status)
                        .put("reason", reason).put("route", route).put("basis", "matched_host_loader");
                if (actionHttp != null) finding.set("httpEvidence", actionHttp);
                if (challenge) finding.put("accessClassification", accessClassification)
                        .put("accessAssociation", accessAssociation)
                        .put("failureCauseEstablished", false);
                if (network) finding.put("networkFailureObserved", true)
                        .put("networkAssociation", "source_method_observation")
                        .put("failureCauseEstablished", false);
                var denials = networkDenialsForNode(evidence, id);
                if (!denials.isEmpty()) finding.set("networkDenials", denials);
                if (type != null) finding.put("failureType", type);
                var observations = ManagedLoaderReceipt.contractObservations(mapper, node);
                if (!observations.isEmpty()) finding.set("contractObservations", observations);
                if ("R01".equals(id) && !playerContext.isMissingNode()
                        && playerContext.path("detailCallId").equals(observations.at("/playerContract/detailCallId"))
                        && playerContext.path("playCallId").equals(observations.at("/playerContract/playCallId"))) {
                    var values = playerValueEvidence(resources, playerContext);
                    finding.set("playerValueEvidence", values);
                    if (values.path("matches").isEmpty()) missing.add("player_value_correspondence");
                }
                routes.add(route);
            }
        } else {
            missing.add("matched_loader_receipt");
            routes.add("runtime_evidence");
        }
        var http = result.putObject("http");
        int complete = 0;
        var statuses = new java.util.TreeMap<Integer, Integer>();
        // These observations already passed ManagedSourceRuntime projection; do not copy raw fields.
        if (sha != null && sha.equals(evidence.path("inputSourceSha256").asText())) {
            for (JsonNode row : evidence.path("httpObservations")) {
                int status = row.path("responseStatus").asInt();
                if (row.path("responseComplete").asBoolean() && status >= 100 && status <= 599) {
                    complete++;
                    statuses.merge(status, 1, Integer::sum);
                }
            }
            var codes = result.putArray("unresolvedDenials");
            RepairReport.unresolvedEgressCodes(evidence).stream().limit(64).forEach(code -> {
                String safe = ManagedLoaderReceipt.safeReason(code.asText());
                if (safe.equals("LOADER_NODE_UNRESOLVED")) safe = "EGRESS_POLICY_REJECTED";
                codes.add(safe);
                routes.add(route("", safe, null));
            });
        }
        http.put("completedResponses", complete);
        var statusCounts = http.putObject("statusCounts");
        statuses.forEach((status, count) -> statusCounts.put(status.toString(), count));
        var terminalStatuses = new java.util.TreeMap<>(statuses);
        Set.of(301, 302, 303, 307, 308).forEach(terminalStatuses::remove);
        var terminalCounts = http.putObject("terminalStatusCounts");
        terminalStatuses.forEach((status, count) -> terminalCounts.put(status.toString(), count));
        if (complete == 0) missing.add("completed_http_response");
        if (terminalStatuses.size() == 1) {
            int only = terminalStatuses.firstKey();
            if (only == 401) routes.add("credentials");
            else if (only == 403 || only == 444) routes.add("access_evidence");
            else if (only == 429) routes.add("cooldown");
            else if (only >= 500) routes.add("network");
        }
        if (verified) {
            transportDiagnostics(result, evidence);
            cooldownDiagnostics(result, evidence);
            dnsDiagnostics(result, evidence.path("dnsObservations"), evidence);
            if (result.path("dnsEvidence").isObject()) {
                if (boundedInteger(evidence.path("dnsObservationsDropped"), 0, 2048))
                    ((ObjectNode) result.path("dnsEvidence")).set("observationsDropped", evidence.path("dnsObservationsDropped"));
            }
        }
        if (statuses.keySet().stream().anyMatch(status -> status >= 400)
                && findings.valueStream().anyMatch(finding -> "source_contract".equals(finding.path("route").asText())
                    && !finding.has("httpEvidence"))) missing.add("action_http_association");
        result.put("resourceEvidenceAvailable", structure);
        int jsonResponses = 0, missingJsonStructures = 0;
        if (verified) for (JsonNode event : evidence.path("httpObservations")) {
            String mime = event.path("responseContentType").asText();
            if (!event.path("responseComplete").asBoolean()
                    || !Set.of("GET", "POST").contains(event.path("method").asText())
                    || !(mime.equals("application/json") || mime.endsWith("+json"))) continue;
            jsonResponses++;
            boolean present = resources.valueStream().anyMatch(row -> row.path("kind").asText().equals("api_json")
                    && row.path("requestMethod").equals(event.path("method"))
                    && row.path("urlSha256").equals(event.path("requestUrlSha256"))
                    && row.path("responseSha256").equals(event.path("responseSha256")));
            if (!present) missingJsonStructures++;
        }
        long structuredJson = resources.valueStream().filter(row -> row.path("kind").asText().equals("api_json")).count();
        long playJson = resources.valueStream().filter(row -> row.path("kind").asText().equals("api_json")
                && row.path("observedDuringCall").path("method").asText().equals("play")).count();
        var jsonEvidence = result.putObject("jsonResponseEvidence").put("completedJsonResponses", jsonResponses)
                .put("structuredResponses", structuredJson).put("responsesWithoutStructure", missingJsonStructures)
                .put("playCallAssociatedResponses", playJson).put("failureCauseEstablished", false);
        var addresses = mapper.createArrayNode();
        for (JsonNode resource : resources) {
            if (!"api_json".equals(resource.path("kind").asText())) continue;
            for (JsonNode node : resource.path("jsonStructure").path("nodes")) {
                if (!"string".equals(node.path("type").asText()) || !"url_like".equals(node.path("shape").asText())) continue;
                var field = addresses.addObject().put("responseSha256", resource.path("responseSha256").asText())
                        .put("requestMethod", resource.path("requestMethod").asText())
                        .put("byteLength", node.path("byteLength").asInt())
                        .put("completeStructure", !resource.path("jsonStructure").path("truncated").asBoolean());
                field.set("path", node.path("path"));
                if (resource.has("observedDuringCall")) field.set("observedDuringCall", resource.path("observedDuringCall"));
            }
        }
        if (!addresses.isEmpty()) jsonEvidence.set("addressFields", addresses);
        // JSON APIs are not required for every source. Flag only a gap in responses actually observed.
        if (missingJsonStructures > 0 && findings.valueStream().anyMatch(finding ->
                finding.path("node").asText().equals("R01")
                && Set.of("source_contract", "player_resource").contains(finding.path("route").asText())))
            missing.add("json_response_structure");
        if (!structure && (routes.contains("source_contract") || routes.contains("player_resource")))
            missing.add("public_response_structure");
        if (!"current".equals(freshness)) missing.add("current_input_receipt");
        if (access != null) {
            boolean blocked = findings.valueStream().anyMatch(finding ->
                    AccessPageClassifier.blocksRepair(finding.path("accessClassification").asText()));
            access.put("repairBlocked", blocked).put("automaticChallengeSolving", false)
                    .put("nextAction", blocked ? "cloudflare_access_denied".equals(accessClassification)
                            ? "authorized_access_or_verified_origin" : "authorized_access_or_manual_verification"
                            : "continue_independent_repair");
        }
        result.set("missingMaterials", mapper.valueToTree(missing));
        result.set("routes", mapper.valueToTree(routes));
        var actions = result.putArray("nextActions");
        for (String route : routes) actions.addObject().put("route", route).put("action", action(route));
        result.put("conclusion", findings.isEmpty() && routes.isEmpty() ? "no_observed_blocker" : "triage_only");
        return result;
    }

    private ObjectNode playerValueEvidence(JsonNode resources, JsonNode context) {
        var result = mapper.createObjectNode().put("basis", "observed_response_value_comparison")
                .put("failureCauseEstablished", false).put("truncated", false);
        result.set("playerContext", context);
        var matches = result.putArray("matches");
        for (JsonNode resource : resources) {
            JsonNode call = resource.path("observedDuringCall");
            if (!"api_json".equals(resource.path("kind").asText())
                    || !Set.of("detail", "play").contains(call.path("method").asText())
                    || !call.path("id").equals(context.path(call.path("method").asText() + "CallId"))) continue;
            result.put("truncated", result.path("truncated").asBoolean()
                    || resource.path("jsonStructure").path("truncated").asBoolean());
            for (JsonNode node : resource.path("jsonStructure").path("nodes")) {
                appendPlayerValueMatch(matches, resource, node, null);
                result.put("truncated", result.path("truncated").asBoolean()
                        || node.path("embeddedJson").path("truncated").asBoolean());
                for (JsonNode embedded : node.path("embeddedJson").path("nodes"))
                    appendPlayerValueMatch(matches, resource, embedded, node.path("path"));
            }
        }
        if (matches.size() > 24) {
            result.put("truncated", true);
            while (matches.size() > 24) matches.remove(matches.size() - 1);
        }
        return result;
    }

    private void appendPlayerValueMatch(com.fasterxml.jackson.databind.node.ArrayNode matches,
                                       JsonNode resource, JsonNode node, JsonNode enclosingPath) {
        if (!node.has("playerValueMatches")) return;
        var match = matches.addObject().put("requestMethod", resource.path("requestMethod").asText())
                .put("responseSha256", resource.path("responseSha256").asText());
        match.set("observedDuringCall", resource.path("observedDuringCall"));
        match.set("path", enclosingPath == null ? node.path("path") : enclosingPath);
        if (enclosingPath != null) match.set("embeddedPath", node.path("path"));
        match.set("playerValueMatches", node.path("playerValueMatches"));
    }

    private void transportDiagnostics(ObjectNode result, JsonNode evidence) {
        var rows = mapper.createArrayNode();
        for (JsonNode row : evidence.path("networkObservations").valueStream().limit(32).toList()) {
            if (!boundedInteger(row.path("requestNumber"), 1, 64)
                    || !boundedInteger(row.path("addressIndex"), 0, 15)
                    || !boundedInteger(row.path("elapsedMilliseconds"), 0, 120000)
                    || !Set.of("tcp_connect", "tls_handshake", "request_send", "response_headers", "response_body")
                        .contains(row.path("stage").asText())
                    || !Set.of("certificate", "timeout", "tls", "connection_refused", "connection_reset",
                        "http_protocol", "invalid_response", "io").contains(row.path("kind").asText())) continue;
            var safe = rows.addObject();
            for (String field : List.of("requestNumber", "addressIndex", "stage", "kind", "elapsedMilliseconds"))
                safe.set(field, row.path(field).deepCopy());
            if (row.path("errno").isNull() || boundedInteger(row.path("errno"), -1, 65535))
                safe.set("errno", row.path("errno").deepCopy());
            copyObservedCall(safe, row, evidence);
        }
        JsonNode dropped = evidence.path("networkObservationsDropped");
        int omitted = boundedInteger(dropped, 0, 2048) ? dropped.intValue() : 0;
        if (rows.isEmpty() && omitted == 0) return;
        var transport = result.putObject("transportEvidence").put("basis", "matched_runtime_network_observations")
                .put("observationsDropped", omitted).put("actionAssociation",
                    rows.valueStream().anyMatch(row -> row.has("observedDuringCall")) ? "host_call_interval" : "not_established")
                .put("currentAvailabilityEstablished", false).put("failureCauseEstablished", false);
        transport.set("observations", rows);
    }

    private void cooldownDiagnostics(ObjectNode result, JsonNode evidence) {
        var rows = mapper.createArrayNode();
        for (JsonNode row : evidence.path("rateLimitObservations").valueStream().limit(64).toList()) {
            JsonNode timing = row.path("retryAfter"), seconds = timing.path("seconds");
            String kind = timing.path("kind").asText();
            if (!boundedInteger(row.path("responseStatus"), 429, 429)
                    || !row.path("automaticRetry").isBoolean() || row.path("automaticRetry").booleanValue()
                    || !Set.of("absent", "invalid", "delay", "date").contains(kind)
                    || !(Set.of("absent", "invalid").contains(kind) ? seconds.isNull()
                        : seconds.isIntegralNumber() && seconds.bigIntegerValue().signum() >= 0
                            && seconds.bigIntegerValue().toString().length() <= 128)) continue;
            var observation = rows.addObject().put("responseStatus", 429);
            var safe = observation.putObject("retryAfter").put("kind", kind);
            if (seconds.isNull()) safe.putNull("seconds");
            else safe.put("seconds", seconds.bigIntegerValue().toString());
            copyObservedCall(observation, row, evidence);
            JsonNode context = row.path("cooldownContext");
            // These are execution-time snapshots, never a countdown restarted by a diagnostic GET.
            if (context.isObject() && row.path("observedAtEpochMilliseconds").isIntegralNumber()
                    && context.path("recordedAtEpochMilliseconds").isIntegralNumber()) {
                observation.set("observedAtEpochMilliseconds", row.path("observedAtEpochMilliseconds"));
                var snapshot = observation.putObject("cooldownContext");
                for (String field : List.of("phase", "recordedAtEpochMilliseconds", "taskBudgetRemainingMilliseconds", "fitsTaskBudget"))
                    snapshot.set(field, context.path(field).deepCopy());
                JsonNode wait = context.path("remainingWaitMilliseconds");
                if (wait.isNull()) snapshot.putNull("remainingWaitMilliseconds");
                else if (wait.isIntegralNumber() && wait.bigIntegerValue().signum() >= 0
                        && wait.bigIntegerValue().toString().length() <= 131)
                    snapshot.put("remainingWaitMilliseconds", wait.bigIntegerValue().toString());
            }
        }
        if (rows.isEmpty()) return;
        var cooldown = result.putObject("cooldownEvidence").put("basis", "matched_runtime_rate_limit_observations")
                .put("timing", "at_response").put("currentRemainingWaitKnown", false).put("automaticRetry", false);
        cooldown.set("observations", rows);
    }

    private void copyObservedCall(ObjectNode safe, JsonNode row, JsonNode evidence) {
        JsonNode observed = row.path("observedDuringCall");
        if (!observed.isObject() || observed.size() != 3
                || !boundedInteger(observed.path("id"), 1, 32)
                || !observed.path("inputSha256").asText().matches("[a-f0-9]{64}")) return;
        for (JsonNode call : evidence.path("loaderValidation").path("calls"))
            if (observed.path("id").equals(call.path("id"))
                    && observed.path("method").equals(call.path("method"))
                    && observed.path("inputSha256").equals(call.path("inputSha256"))) {
                safe.set("observedDuringCall", observed.deepCopy());
                return;
            }
    }

    private static boolean boundedInteger(JsonNode value, int minimum, int maximum) {
        return value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= minimum && value.intValue() <= maximum;
    }

    static boolean bound(JsonNode evidence, String sha, String profileSha, String runtimeSha) {
        JsonNode loader = evidence.path("loaderValidation");
        return sha != null && sha.matches("[a-f0-9]{64}") && profileSha != null
                && profileSha.matches("[a-f0-9]{64}") && runtimeSha != null && runtimeSha.matches("[a-f0-9]{64}")
                && sha.equals(evidence.path("inputSourceSha256").asText())
                && runtimeSha.equals(evidence.path("bundleSha256").asText())
                && loader.path("projection").asText().equals(ManagedLoaderReceipt.PROJECTION)
                && sha.equals(loader.path("inputSourceSha256").asText())
                && profileSha.equals(loader.path("inputProfileSha256").asText());
    }

    private String route(String id, String reason, String type) {
        if (reason.equals("LOADER_NODE_UNRESOLVED")) return "runtime_evidence";
        if (reason.equals("EGRESS_REQUEST_BUDGET") || reason.equals("EGRESS_BYTE_BUDGET")) return "request_budget";
        if (reason.equals("EGRESS_RATE_LIMITED")) return "cooldown";
        if (Set.of("EGRESS_HTTP_FAILED", "EGRESS_HTTP_DEADLINE", "EGRESS_DNS_FAILED",
                "EGRESS_DNS_NO_ADDRESS", "EGRESS_NONPUBLIC_DNS").contains(reason)) return "network";
        if (reason.startsWith("EGRESS_")) return "request_policy";
        if ("ModuleNotFoundError".equals(type) || "ImportError".equals(type)
                || id.equals("S04") || reason.equals("DEPENDENCY_COVERAGE_INCOMPLETE")) return "dependency";
        if (id.equals("S06") || reason.equals("LOADER_INIT_FAILED")) return "initialization";
        if (reason.equals("PLAYER_EXTERNAL_PARSER_REQUIRED")) return "player_resource";
        if (reason.equals("PLAYER_OFFLINE_RESOLVER_REQUIRED")
                || reason.equals("AT_OFFLINE_DOWNLOAD_NOT_VALIDATED")) return "at_offline";
        if (id.startsWith("M") || id.equals("R06")) return "media_resource";
        return "source_contract";
    }

    private static String sourceMethod(String id) {
        return switch (id) {
            case "C01" -> "home";
            case "C03" -> "category";
            case "C05" -> "search";
            case "C07", "C10" -> "detail";
            case "R01" -> "play";
            default -> "";
        };
    }

    private ObjectNode actionHttpEvidence(JsonNode evidence, JsonNode resources, String id) {
        String method = sourceMethod(id);
        if (method.isEmpty()) return null;
        var calls = mapper.createArrayNode();
        boolean httpIntervals = false, resourceIntervals = false;
        for (JsonNode call : evidence.path("loaderValidation").path("calls")) {
            if (!method.equals(call.path("method").asText())) continue;
            var statuses = new java.util.TreeMap<Integer, Integer>();
            // Count each host HTTP event once, even when several resources sample that response.
            for (JsonNode response : evidence.path("httpObservations")) {
                JsonNode status = response.path("responseStatus");
                if (!response.path("responseComplete").asBoolean() || !boundedInteger(status, 200, 599)
                        || Set.of(301, 302, 303, 307, 308).contains(status.asInt())) continue;
                if (response.has("observedDuringCall")) {
                    if (!sameCall(response.path("observedDuringCall"), call)) continue;
                    httpIntervals = true;
                } else {
                    boolean matched = false;
                    for (JsonNode resource : resources) {
                        if (!Set.of("page", "api_json").contains(resource.path("kind").asText())
                            || !sameCall(resource.path("observedDuringCall"), call)
                            || !resource.path("urlSha256").equals(response.path("requestUrlSha256"))
                            || !resource.path("responseSha256").equals(response.path("responseSha256"))
                            || !resource.path("responseBytes").equals(response.path("responseBytes"))
                            || resource.has("responseStatus") && !resource.path("responseStatus").equals(status)
                            || resource.has("requestMethod")
                                && !resource.path("requestMethod").equals(response.path("method"))) continue;
                        matched = true;
                        break;
                    }
                    if (!matched) continue;
                    resourceIntervals = true;
                }
                statuses.merge(status.asInt(), 1, Integer::sum);
            }
            if (statuses.isEmpty()) continue;
            var observed = calls.addObject().put("id", call.path("id").asInt()).put("method", method)
                    .put("inputSha256", call.path("inputSha256").asText())
                    .put("successfulResponseObserved", statuses.keySet().stream().anyMatch(status -> status < 300));
            var counts = observed.putObject("statusCounts");
            statuses.forEach((status, count) -> counts.put(status.toString(), count));
            // Call intervals do not prove causality or coverage of every request made by the source.
            if (!observed.path("successfulResponseObserved").asBoolean()) {
                if (statuses.containsKey(401)) observed.put("unavailableRoute", "credentials");
                else if (statuses.containsKey(429)) observed.put("unavailableRoute", "cooldown");
                else if (statuses.containsKey(403) || statuses.containsKey(444)) observed.put("unavailableRoute", "access_evidence");
                else if (statuses.keySet().stream().allMatch(status -> status >= 500)) observed.put("unavailableRoute", "network");
            }
        }
        if (calls.isEmpty()) return null;
        String basis = httpIntervals ? resourceIntervals ? "matched_http_and_resource_call_intervals"
                : "matched_http_call_interval" : "matched_resource_call_interval";
        var result = mapper.createObjectNode().put("basis", basis)
                .put("completeCallCoverage", false).put("failureCauseEstablished", false);
        result.set("calls", calls);
        for (JsonNode call : calls) {
            if (call.has("unavailableRoute")) {
                result.put("unavailableRoute", call.path("unavailableRoute").asText());
                break;
            }
        }
        return result;
    }

    private boolean sameCall(JsonNode association, JsonNode call) {
        return association.isObject() && association.size() == 3
                && boundedInteger(association.path("id"), 1, 32)
                && association.path("inputSha256").asText().matches("[a-f0-9]{64}")
                && association.path("id").equals(call.path("id"))
                && association.path("method").equals(call.path("method"))
                && association.path("inputSha256").equals(call.path("inputSha256"));
    }

    private boolean networkFailureForNode(JsonNode evidence, String id) {
        return !networkDenialsForNode(evidence, id).isEmpty();
    }

    private com.fasterxml.jackson.databind.node.ArrayNode networkDenialsForNode(JsonNode evidence, String id) {
        var result = mapper.createArrayNode();
        String method = sourceMethod(id);
        if (method.isEmpty()) return result;
        JsonNode codes = evidence.path("deniedCodes");
        var seen = new java.util.HashSet<Integer>();
        for (JsonNode call : evidence.path("networkFailureCalls").valueStream().limit(64).toList()) {
            JsonNode index = call.path("denialIndex");
            if (method.equals(call.path("method").asText()) && boundedInteger(index, 0, 63)
                    && index.intValue() < codes.size() && seen.add(index.intValue())
                    && route("", codes.path(index.intValue()).asText(), null).equals("network"))
                result.addObject().put("denialIndex", index.intValue()).put("method", method)
                        .put("code", codes.path(index.intValue()).asText()).put("connectionAssociationEstablished", false);
        }
        return result;
    }

    private String action(String route) {
        return switch (route) {
            case "request_budget" -> "Compare repeated requests, redirects and pagination before changing source calls.";
            case "cooldown" -> "Inspect Retry-After and the remaining task budget; do not repeat immediately.";
            case "network" -> "Compare stored DNS/TCP/TLS and upstream HTTP failures; prefer revalidating the same candidate before source edits or another model call. Action association does not establish failure causality.";
            case "request_policy" -> "Trace the denied request to its source call and observed origin; do not weaken egress policy.";
            case "dependency" -> "Identify the import and pinned runtime dependency; source edits alone do not install packages.";
            case "initialization" -> "Trace init and the first failed host action; use the exception type, not guessed error text.";
            case "player_resource" -> "Use the actual player page and script evidence before changing media extraction.";
            case "at_offline" -> "Preserve the magnet or cloud handoff; AT resolution is separate from HTTP playback.";
            case "media_resource" -> "Compare the observed callback, MIME and bytes on the same source session.";
            case "credentials" -> "Configure source credentials through the private source-credentials panel.";
            case "access_evidence" -> "Use an authorized API or visible original-page verification for a confirmed challenge; do not repair selectors or export clearance cookies. HTTP 403 or 444 alone does not prove login is required.";
            case "runtime_evidence" -> "Obtain a current platform runtime receipt before assigning a source-code cause.";
            case "source_regression" -> "Restore the previously non-empty output while retaining recovered nodes; compare the actual home/list/detail paths and prior edits. Counts are observations, not fixed expected totals or proof of cause.";
            default -> "Trace input, extraction, transformation and returned contract; reuse bounded page evidence and replay.";
        };
    }
}
