package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.OriginInspectionRequest;
import cn.har01d.atmaintenance.dto.ResumeOriginSearchRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.time.Instant;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class OriginDiscoveryService {
    private final MaintenanceAutomationStore tasks;
    private final RepairStore repairs;
    private final ReverseAnalysis reverse;
    private final ModelStore models;
    private final ModelService generation;
    private final ModelClient client;
    private final AutomaticRepairStore automatic;
    private final SourceCredentialStore credentials;
    private final ObjectMapper mapper;

    public synchronized ObjectNode start(String id, String faultSha) {
        var repair = repairs.get(id);
        if (!repair.faultSha256().equals(faultSha)) throw conflict("REPAIR_FAULT_CHANGED");
        var previous = tasks.get("origin_discovery", id);
        boolean infrastructureRecovery = previous != null && "BLOCKED".equals(previous.getState())
                && "REVERSE_ENGINE_NOT_READY".equals(previous.getErrorCode()) && previous.getAttempts() == 0
                && !tasks.context(previous).hasNonNull("modelCallId") && reverse.originReady();
        if (previous != null && !infrastructureRecovery) return tasks.summary(previous);
        ReverseAnalysis.requireCurrent(repair);
        client.requireEnabled();
        if (!repair.baselineArtifactSha256().equals(models.consent(repair.sourceId()).artifactSha256()))
            throw conflict("MODEL_SOURCE_NOT_AUTHORIZED");
        if (!"current".equals(repair.freshness())) throw conflict("REPAIR_INPUTS_CHANGED");
        if (infrastructureRecovery) {
            if ("CANCELLED".equals(automatic.get(id).status())) throw conflict("USER_CANCELLED");
            var context = tasks.context(previous);
            if (!client.connection().configurationSha256().equals(context.path("configurationSha256").asText()))
                throw conflict("MODEL_CONFIGURATION_CHANGED");
            context.withArray("infrastructureFailures").addObject().put("code", previous.getErrorCode())
                    .put("updatedAt", previous.getUpdatedAt().toString());
            return tasks.summary(tasks.save(previous, "QUEUED", context, null));
        }
        var context = mapper.createObjectNode().put("expectedFaultSha256", faultSha)
                .put("configurationSha256", client.connection().configurationSha256())
                .put("maxDiscoveryCalls", 1).put("autoResume", true);
        return tasks.summary(tasks.enqueue("origin_discovery", id, repair.sourceId(), context));
    }

    public ObjectNode status(String id) { repairs.get(id); return tasks.summary(tasks.get("origin_discovery", id)); }

    boolean hasVerifiedRoute(cn.har01d.atmaintenance.dto.RepairSummary repair) {
        return !RepairEvidenceMaterial.originRoute(mapper, repair, repair.frozen().path("reverseAnalysis")).isMissingNode();
    }

    public synchronized ObjectNode resumeSearch(String id, ResumeOriginSearchRequest input) {
        var repair = repairs.get(id);
        if (!repair.faultSha256().equals(input.expectedFaultSha256())) throw conflict("REPAIR_FAULT_CHANGED");
        var task = tasks.get("origin_discovery", id);
        if (task == null) throw conflict("ORIGIN_DISCOVERY_NOT_REQUESTED");
        var context = tasks.context(task);
        if (input.expectedSearchTaskId().equals(context.path("recoveredSearchTaskId").asText()))
            return tasks.summary(task);
        if (!"BLOCKED".equals(task.getState()) || !"ORIGIN_SEARCH_RESULTS_UNAVAILABLE".equals(task.getErrorCode())
                || context.hasNonNull("modelCallId") || task.getAttempts() != 0
                || context.path("searchRecoveries").asInt() >= 1
                || !"unavailable".equals(context.path("searchEvidence").path("status").asText())
                || !input.expectedSearchTaskId().equals(context.path("searchTaskId").asText()))
            throw conflict("ORIGIN_SEARCH_RECOVERY_NOT_AVAILABLE");
        ReverseAnalysis.requireCurrent(repair);
        client.requireEnabled();
        if (!client.connection().configurationSha256().equals(context.path("configurationSha256").asText()))
            throw conflict("MODEL_CONFIGURATION_CHANGED");
        if (!repair.baselineArtifactSha256().equals(models.consent(repair.sourceId()).artifactSha256()))
            throw conflict("MODEL_SOURCE_NOT_AUTHORIZED");
        if ("CANCELLED".equals(automatic.get(id).status())) throw conflict("USER_CANCELLED");
        var previous = context.withArray("searchHistory").addObject()
                .put("taskId", input.expectedSearchTaskId()).put("state", task.getState())
                .put("errorCode", task.getErrorCode()).put("updatedAt", task.getUpdatedAt().toString());
        previous.set("searchEvidence", context.path("searchEvidence").deepCopy());
        context.put("recoveredSearchTaskId", input.expectedSearchTaskId()).put("searchRecoveries", 1);
        context.remove(Set.of("searchEvidence", "searchTaskId"));
        task.setNextAttemptAt(Instant.now());
        return tasks.summary(tasks.save(task, "QUEUED", context, null));
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public synchronized void poll() {
        for (var task : tasks.due("origin_discovery")) {
            var context = tasks.context(task);
            try {
                var repair = repairs.get(task.getOwnerId());
                ReverseAnalysis.requireCurrent(repair);
                if (!repair.faultSha256().equals(context.path("expectedFaultSha256").asText()))
                    throw conflict("REPAIR_FAULT_CHANGED");
                if (!client.connection().configurationSha256().equals(context.path("configurationSha256").asText()))
                    throw conflict("MODEL_CONFIGURATION_CHANGED");
                if ("CANCELLED".equals(automatic.get(repair.id()).status())) throw conflict("USER_CANCELLED");
                if ("QUEUED".equals(task.getState())) {
                    JsonNode declared = repair.frozen().path("reverseAnalysis");
                    if (!"source_origin_inspection".equals(declared.path("operation").asText()))
                        declared = reverse.inspectOrigins(repair.id(), new OriginInspectionRequest(repair.faultSha256()));
                    JsonNode declaredRoute = RepairEvidenceMaterial.originRoute(mapper, repair, declared);
                    if (!declaredRoute.isMissingNode()) {
                        resumeVerified(task, context, repairs.get(repair.id()), declaredRoute);
                        continue;
                    }
                    // Search once, then retain the exact results across provider waits and restarts.
                    ObjectNode search = reverse.discoverOrigins(repair.id(), null);
                    context.set("searchEvidence", search.path("originSearch"));
                    context.put("searchTaskId", search.path("taskId").asText());
                    tasks.save(task, "SEARCHED", context, null);
                }
                JsonNode search = context.path("searchEvidence");
                if (!"results_observed".equals(search.path("status").asText())) {
                    tasks.save(task, "BLOCKED", context, "ORIGIN_SEARCH_RESULTS_UNAVAILABLE");
                    continue;
                }
                if (!context.hasNonNull("modelCallId")) {
                    var call = generation.discoverOrigins(repair.id(), repair.faultSha256(), search);
                    context.put("modelCallId", call.id());
                    task.setAttempts(1);
                    tasks.save(task, "WAITING_MODEL", context, null);
                }
                var call = models.get(context.path("modelCallId").asText());
                if (Set.of("QUEUED", "RUNNING", "CANCELLING").contains(call.status())) continue;
                if (!"DISCOVERED".equals(call.status())) {
                    tasks.save(task, "BLOCKED", context, call.errorCode() == null ? "ORIGIN_MODEL_FAILED" : call.errorCode());
                    continue;
                }
                var proposed = mapper.createArrayNode();
                var seen = new java.util.HashSet<String>();
                for (JsonNode row : call.responseDiagnostics().path("originCandidates")) {
                    String origin = row.path("origin").asText(), url = row.path("evidenceUrl").asText();
                    if (!ReverseAnalysis.publicUrl(origin) || !origin.startsWith("https://")
                            || !origin.equals(URI.create(origin).getScheme() + "://" + URI.create(origin).getRawAuthority())
                            || origin.equals(search.path("originalOrigin").asText()) || seen.contains(origin)) continue;
                    if (search.path("results").valueStream().anyMatch(result -> url.equals(result.path("url").asText())
                            && origin.equals(result.path("origin").asText()))) {
                        proposed.add(row.deepCopy());
                        seen.add(origin);
                    }
                }
                context.set("matchedSearchCandidates", proposed);
                if (proposed.isEmpty()) {
                    tasks.save(task, "BLOCKED", context, "ORIGIN_MODEL_NO_MATCHING_CANDIDATES");
                    continue;
                }
                tasks.save(task, "VERIFYING", context, null);
                ObjectNode report = reverse.discoverOrigins(repair.id(), proposed);
                context.set("verification", report);
                repair = repairs.get(repair.id());
                JsonNode route = RepairEvidenceMaterial.originRoute(mapper, repair, report);
                if (route.isMissingNode()) {
                    tasks.save(task, "BLOCKED", context, "ORIGIN_SITE_IDENTITY_OR_PATH_UNCONFIRMED");
                    continue;
                }
                resumeVerified(task, context, repair, route);
            } catch (ResponseStatusException error) {
                if (!"MODEL_BUSY".equals(error.getReason())) tasks.save(task, "BLOCKED", context, error.getReason());
            } catch (RuntimeException error) {
                tasks.save(task, "BLOCKED", context, "ORIGIN_DISCOVERY_INTERNAL_ERROR");
                log.error("Origin discovery stopped: repair={}, exception={}", task.getOwnerId(), error.getClass().getName());
            }
        }
    }

    private void resumeVerified(cn.har01d.atmaintenance.entity.MaintenanceAutomationTask task, ObjectNode context,
                                cn.har01d.atmaintenance.dto.RepairSummary repair, JsonNode route) {
        context.set("verifiedRoute", route);
        // Discovery never grants an extra repair round or revives a user cancellation.
        if (repair.usedRounds() >= repair.maxRounds()) {
            tasks.save(task, "VERIFIED", context, "ROUND_LIMIT");
            return;
        }
        String blocked = credentials.blockedReason(repair.sourceId());
        if (blocked != null) throw conflict(blocked);
        if (!client.connection().autoSubmitEnabled()) throw conflict("MODEL_AUTO_SUBMIT_DISABLED");
        if ("STOPPED".equals(repair.status())) repair = repairs.resume(repair.id(), repair.faultSha256());
        automatic.start(repair.id(), client.connection().configurationSha256(), true);
        tasks.save(task, "RESUMED", context, null);
    }

    private ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
}
