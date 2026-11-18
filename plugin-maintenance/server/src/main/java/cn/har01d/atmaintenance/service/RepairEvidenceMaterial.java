package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.RepairCandidateSummary;
import cn.har01d.atmaintenance.dto.RepairSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Compare semantic response or routing evidence, not rotating request or response identities. */
final class RepairEvidenceMaterial {
    private RepairEvidenceMaterial() {}

    static ObjectNode current(ObjectMapper mapper, RepairSummary repair, List<RepairCandidateSummary> history) {
        var result = mapper.createObjectNode().put("available", false);
        if (!"current".equals(repair.freshness()) || history.isEmpty()
                || !"managed_loader_candidate".equals(repair.frozen().path("policy").path("scope").asText())) return result;
        var candidate = history.getLast();
        if (!repair.id().equals(candidate.repairId()) || candidate.result() == null
                || !"REJECTED".equals(candidate.status())
                || !"RUNTIME_TARGET_UNRESOLVED".equals(candidate.errorCode())) return result;
        JsonNode runtime = candidate.result().path("runtimeEvidence");
        String profile = repair.frozen().path("baseline").path("profileSha256").asText();
        String bundle = RepairReport.runtimeInputs(repair.frozen()).path("runtimeSha256").asText();
        if (!candidate.sha256().equals(candidate.result().path("artifactSha256").asText())
                || !repair.baselineArtifactSha256().equals(candidate.result().path("baselineArtifactSha256").asText())
                || !"managed_source_host_observed".equals(runtime.path("authority").asText())
                || !candidate.result().path("runtimeJobId").asText(candidate.id()).equals(runtime.path("jobId").asText())
                || !RepairDiagnostics.bound(runtime, candidate.sha256(), profile, bundle)
                || !runtime.path("cleanup").path("confirmed").asBoolean()
                || !runtime.path("cleanup").path("containerAbsent").asBoolean()
                || RepairReport.networkUnavailable(runtime) || RepairReport.httpAccessDenied(runtime)
                || RepairReport.egressUnresolved(runtime)) return result;
        JsonNode route = originRoute(mapper, repair, repair.frozen().path("reverseAnalysis"));
        if (!route.isMissingNode()) {
            String target = repair.frozen().path("policy").path("targetNodes").valueStream()
                    .map(JsonNode::asText).filter(id -> runtime.path("loaderValidation").path("nodes")
                        .valueStream().anyMatch(node -> id.equals(node.path("id").asText())
                            && RepairReport.repairableLoaderNode(node))).findFirst().orElse("");
            if (!target.isEmpty()) {
                Set<String> signatures = originSignatures(mapper, repair, repair.frozen().path("reverseAnalysis"));
                result.put("available", true).put("candidateId", candidate.id()).put("candidateSha256", candidate.sha256())
                        .put("targetNode", target).put("materialKind", "verified_alternate_origin_route")
                        .put("evidenceSha256", Hashes.sha256(String.join("\n", signatures)));
                result.set("structureSignatures", mapper.valueToTree(signatures));
                result.set("verifiedAlternateOriginRoute", route);
                return result;
            }
        }
        try {
            var resources = new ManagedResourceEvidence(mapper).project(runtime.path("resourceEvidence"),
                    candidate.sha256(), runtime.path("httpObservations"), runtime.path("loaderValidation").path("calls"));
            String target = resources.path("targetNode").asText();
            if (!repair.frozen().path("policy").path("targetNodes").valueStream().anyMatch(n -> target.equals(n.asText()))
                    || !runtime.path("loaderValidation").path("nodes").valueStream().anyMatch(n ->
                        target.equals(n.path("id").asText()) && RepairReport.repairableLoaderNode(n))) return result;
            Set<String> signatures = signatures(mapper, resources);
            if (signatures.isEmpty()) return result;
            result.put("available", true).put("candidateId", candidate.id()).put("candidateSha256", candidate.sha256())
                    .put("targetNode", target).put("evidenceSha256", Hashes.sha256(String.join("\n", signatures)));
            result.set("structureSignatures", mapper.valueToTree(signatures));
        } catch (IOException ignored) {
            // Unbound or incomplete evidence cannot authorize another repair round.
        }
        return result;
    }

    static JsonNode originRoute(ObjectMapper mapper, RepairSummary repair, JsonNode report) {
        return originRoute(mapper, repair.id(), repair.baselineRunId(), repair.baselineArtifactSha256(), report);
    }

    static JsonNode originRoute(ObjectMapper mapper, String repairId, String runId, String artifactSha, JsonNode report) {
        var reports = new java.util.ArrayList<JsonNode>();
        reports.add(report);
        report.path("previousReports").forEach(reports::add);
        for (JsonNode observed : reports) {
            if (!"host_source_origin_observed".equals(observed.path("authority").asText())
                    || !"source_origin_inspection".equals(observed.path("operation").asText())
                    || !repairId.equals(observed.path("repairId").asText())
                    || !runId.equals(observed.path("runId").asText())
                    || !artifactSha.equals(observed.path("artifactSha256").asText())
                    || !observed.path("cleanupConfirmed").asBoolean()) continue;
            JsonNode pool = observed.path("originPool");
            String origin = pool.path("preferredCandidateOrigin").asText();
            if (!"maintenance_pinned_http_origin_observed".equals(pool.path("authority").asText())
                    || !pool.path("credentialsForwarded").isBoolean() || pool.path("credentialsForwarded").asBoolean()
                    || !pool.path("requestedPageUrlSha256").asText().matches("[a-f0-9]{64}")
                    || !pool.path("requestedPageUrlSha256").equals(observed.path("pageUrlSha256"))
                    || !ReverseAnalysis.publicUrl(origin)
                    || !pool.path("candidates").valueStream().anyMatch(row -> origin.equals(row.path("origin").asText())
                        && Set.of("source_declared_page_host", "source_published_same_path_redirect", "model_search_result_origin")
                            .contains(row.path("basis").asText()))) continue;
            for (JsonNode row : pool.path("observations"))
                if (origin.equals(row.path("origin").asText())
                        && "business_content_candidate".equals(row.path("status").asText())
                        && row.path("httpStatus").asInt() == 200 && row.path("responseComplete").asBoolean()
                        && row.path("sameActionPath").asBoolean()
                        && (!row.path("matchedMarkerIndexes").isEmpty()
                            || "model_search_results".equals(pool.path("inspectionStage").asText())
                                && "consistent_site_identity".equals(row.path("siteIdentity").path("status").asText()))
                        && row.path("responseContentType").asText().contains("html") && row.path("responseBytes").asLong() > 0
                        && row.path("requestUrlSha256").asText().matches("[a-f0-9]{64}")
                        && row.path("responseSha256").asText().matches("[a-f0-9]{64}"))
                    return mapper.createObjectNode().put("candidateOrigin", origin)
                            .put("requestedPageUrlSha256", pool.path("requestedPageUrlSha256").asText())
                            .put("candidatePageUrlSha256", row.path("requestUrlSha256").asText())
                            .put("businessChainVerified", false).put("failureCauseEstablished", false);
        }
        return mapper.missingNode();
    }

    static Set<String> originSignatures(ObjectMapper mapper, RepairSummary repair, JsonNode report) {
        JsonNode route = originRoute(mapper, repair, report);
        return route.isMissingNode() ? Set.of() : Set.of(Hashes.sha256("verified_alternate_origin_route\n" + route.toString()));
    }

    static Set<String> signatures(ObjectMapper mapper, JsonNode resources) {
        var result = new TreeSet<String>();
        String method = switch (resources.path("targetNode").asText()) {
            case "C01" -> "home";
            case "C03" -> "category";
            case "C07", "C10" -> "detail";
            case "R01" -> "play";
            default -> "";
        };
        if (method.isEmpty()) return result;
        for (JsonNode row : resources.path("resources")) {
            JsonNode structure = row.path("jsonStructure");
            if (!"api_json".equals(row.path("kind").asText()) || !structure.path("truncated").isBoolean()
                    || structure.path("truncated").asBoolean() || structure.path("nodes").isEmpty()
                    || !method.equals(row.path("observedDuringCall").path("method").asText())) continue;
            String semantic = semanticStructure(mapper, structure, true);
            if (semantic == null) continue;
            result.add(Hashes.sha256(method + "\n" + row.path("requestMethod").asText()
                    + "\n" + semantic));
        }
        return result;
    }

    private static String semanticStructure(ObjectMapper mapper, JsonNode structure, boolean embedded) {
        if (!structure.path("truncated").isBoolean() || structure.path("truncated").asBoolean()
                || !structure.path("nodes").isArray() || structure.path("nodes").isEmpty()) return null;
        var nodes = new TreeMap<String, String>();
        for (JsonNode node : structure.path("nodes")) {
            var semantic = mapper.createObjectNode();
            semantic.set("path", node.path("path"));
            semantic.set("type", node.path("type"));
            if (node.has("shape")) semantic.set("shape", node.path("shape"));
            if (node.has("embeddedJson")) {
                if (!embedded) return null;
                String decoded = semanticStructure(mapper, node.path("embeddedJson"), false);
                if (decoded == null) return null;
                semantic.put("embeddedStructure", decoded);
            }
            nodes.put(node.path("path").toString(), semantic.toString());
        }
        return String.join("\n", nodes.values());
    }

    static JsonNode requestData(ObjectMapper mapper, String request) {
        if (request != null) try {
            for (JsonNode message : mapper.readTree(request).path("messages"))
                if ("user".equals(message.path("role").asText())) return mapper.readTree(message.path("content").asText());
        } catch (IOException ignored) {
            // Missing historical requests are unknown, never evidence that material is new.
        }
        return mapper.missingNode();
    }

    static JsonNode activeGrant(RepairSummary repair) {
        JsonNode grants = repair.frozen().path("evidenceContinuations");
        if (!grants.isArray() || grants.isEmpty()) return grants.path(0);
        JsonNode grant = grants.get(grants.size() - 1);
        return grant.path("afterUsedRounds").asInt(-1) == repair.usedRounds()
                && repair.usedRounds() < repair.maxRounds() ? grant : grants.path(grants.size());
    }
}
