package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomaticRepairService {
    private final AutomaticRepairStore store;
    private final RepairStore repairs;
    private final RepairService validation;
    private final ModelStore models;
    private final ModelService generation;
    private final ModelSubmissionService submissions;
    private final ModelClient client;
    private final SourceCredentialStore credentials;
    private final RepairDiagnostics diagnostics;
    private final OriginDiscoveryService originDiscovery;
    private final AutomaticPublicationService publications;

    public synchronized AutomaticRepairSummary start(String id, StartModelCallRequest input) {
        var repair = repairs.get(id);
        if (!repair.faultSha256().equals(input.expectedFaultSha256()))
            throw conflict("REPAIR_FAULT_CHANGED");
        requireInputs(repair, input.expectedConfigurationSha256());
        if (!Set.of("AWAITING_PROPOSAL", "AWAITING_RUNTIME_VALIDATION", "VALIDATING",
                "VALIDATING_RUNTIME").contains(repair.status()))
            throw conflict("AUTOMATIC_REPAIR_NOT_READY");
        var calls = models.repairCalls(id);
        boolean enhanced = input.enhancedDiagnostics();
        if (!"ACTIVE".equals(store.get(id).status()) && "AWAITING_PROPOSAL".equals(repair.status())
                && !calls.isEmpty() && "FAILED".equals(calls.getLast().status())
                && ModelResponsePolicy.explicitlyRecoverable(calls.getLast().errorCode())) {
            // Explicit recovery reserves a new call before enabling polling; the old receipt stays immutable.
            enhanced |= calls.getLast().enhancedDiagnostics();
            generation.propose(id, new StartModelCallRequest(repair.faultSha256(),
                    input.expectedConfigurationSha256(), input.requestKey(), true, enhanced));
        }
        return store.start(id, input.expectedConfigurationSha256(), enhanced);
    }

    public synchronized AutomaticRepairSummary cancel(String id) {
        var result = store.finish(id, "CANCELLED", "USER_CANCELLED");
        for (var call : models.list(id)) {
            if (Set.of("QUEUED", "RUNNING", "CANCELLING").contains(call.status())
                    || "PENDING".equals(call.submissionStatus())) generation.cancel(call.id());
        }
        validation.cancel(id);
        return result;
    }

    @Scheduled(fixedDelayString = "${maintenance.automatic-repair.poll-millis:1000}", initialDelay = 1000)
    public synchronized void poll() {
        for (String id : store.active()) {
            try { advance(id); }
            catch (ResponseStatusException error) {
                if (Set.of("MODEL_SOURCE_ACCESS_CHALLENGE", "MODEL_SOURCE_ACCESS_DENIED").contains(
                        error.getReason() == null ? "" : error.getReason())) {
                    try {
                        originDiscovery.start(id, repairs.get(id).faultSha256());
                        store.finish(id, "STOPPED", "ORIGIN_DISCOVERY_QUEUED");
                    } catch (ResponseStatusException blocked) { store.finish(id, "STOPPED", blocked.getReason()); }
                    continue;
                }
                // A committed model submission can advance the repair after our snapshot was read.
                if (!Set.of("MODEL_BUSY", "REPAIR_NOT_ACCEPTING_PROPOSALS", "REPAIR_RUNTIME_NOT_READY")
                        .contains(error.getReason() == null ? "" : error.getReason()))
                    store.finish(id, "STOPPED", error.getReason());
            } catch (RuntimeException error) {
                var trace = error.getStackTrace();
                // Exception messages may contain provider data or credentials.
                log.error("Automatic repair failed: repairId={}, exceptionType={}, location={}",
                        id, error.getClass().getName(), trace.length == 0 ? "unknown" : trace[0]);
                store.finish(id, "STOPPED", "AUTOMATIC_REPAIR_INTERNAL_ERROR");
            }
        }
    }

    private void advance(String id) {
        var automatic = store.get(id);
        var repair = repairs.get(id);
        if ("RUNTIME_OBSERVED".equals(repair.status())) {
            boolean successful = repairs.candidates(id).stream()
                    .anyMatch(candidate -> candidate.result() != null
                            && candidate.result().path("repairSuccessful").asBoolean());
            if (!successful && discoverBlockedOrigin(repair)) return;
            if (successful) publications.repairSucceeded(id);
            store.finish(id, successful ? "SUCCEEDED" : "STOPPED",
                    successful ? null : "REQUIRED_RUNTIME_NODES_INCOMPLETE");
            return;
        }
        if ("BLOCKED".equals(repair.status()) && "RUNTIME_NETWORK_UNAVAILABLE".equals(repair.stopCode())) {
            requireInputs(repair, automatic.configurationSha256());
            validation.retryAutomaticRuntime(id);
            return;
        }
        if (Set.of("STOPPED", "BLOCKED", "INTERRUPTED", "CANCELLED", "CANCELLING").contains(repair.status())) {
            if (Set.of("STOPPED", "BLOCKED").contains(repair.status()) && discoverBlockedOrigin(repair)) return;
            store.finish(id, "STOPPED", repair.stopCode());
            return;
        }
        requireInputs(repair, automatic.configurationSha256());
        if ("managed_origin_candidate".equals(repair.frozen().path("policy").path("scope").asText())
                && !originDiscovery.hasVerifiedRoute(repair)) {
            var discovery = originDiscovery.start(id, repair.faultSha256());
            if (Set.of("BLOCKED", "VERIFIED", "NOT_NEEDED").contains(discovery.path("state").asText()))
                store.finish(id, "STOPPED", discovery.path("errorCode").asText("VERIFIED_ORIGIN_ROUTE_REQUIRED"));
            return;
        }
        var calls = models.repairCalls(id);
        if (calls.stream().anyMatch(call -> Set.of("QUEUED", "RUNNING", "CANCELLING").contains(call.status()))) return;
        if (!calls.isEmpty()) {
            var last = calls.getLast();
            if (!"PROPOSED".equals(last.status())) {
                store.finish(id, "STOPPED", last.errorCode());
                return;
            }
            if (last.candidateId() == null) {
                if ("PENDING".equals(last.submissionStatus())) submissions.autoSubmit(last.id());
                else store.finish(id, "STOPPED", last.submissionErrorCode() == null
                        ? "MODEL_SUBMISSION_REQUIRED" : last.submissionErrorCode());
                return;
            }
        }
        if ("AWAITING_RUNTIME_VALIDATION".equals(repair.status())) {
            if (!Set.of("managed_home_candidate", "managed_loader_candidate", "managed_origin_candidate")
                    .contains(repair.frozen().path("policy").path("scope").asText())) {
                store.finish(id, "STATIC_ONLY", "REPAIR_RUNTIME_UNSUPPORTED");
                return;
            }
            var candidate = repairs.candidates(id).getLast();
            validation.validateRuntime(candidate.id(), candidate.sha256());
        } else if ("AWAITING_PROPOSAL".equals(repair.status())) {
            // Stable across page closure/restart. Existing reservations are never charged twice.
            generation.propose(id, new StartModelCallRequest(repair.faultSha256(),
                    automatic.configurationSha256(), "automatic_" + calls.size() + "_" + repair.usedRounds(),
                    true, automatic.enhancedDiagnostics()
                            || diagnostics.automaticEnhancement(repair, repairs.candidates(id))));
        }
    }

    private boolean discoverBlockedOrigin(RepairSummary repair) {
        var triage = diagnostics.forRepair(repair, repairs.candidates(repair.id()));
        if (triage == null || !triage.path("accessPage").path("repairBlocked").asBoolean()) return false;
        originDiscovery.start(repair.id(), repair.faultSha256());
        store.finish(repair.id(), "STOPPED", "ORIGIN_DISCOVERY_QUEUED");
        return true;
    }

    private void requireInputs(RepairSummary repair, String configurationSha) {
        String blocked = credentials.blockedReason(repair.sourceId());
        if (blocked != null) throw conflict(blocked);
        client.requireEnabled();
        if (!client.connection().configurationSha256().equals(configurationSha))
            throw conflict("MODEL_CONFIGURATION_CHANGED");
        if (!client.connection().autoSubmitEnabled()) throw conflict("MODEL_AUTO_SUBMIT_DISABLED");
        if (!"current".equals(repair.freshness())) throw conflict("REPAIR_INPUTS_CHANGED");
        if (!repair.baselineArtifactSha256().equals(models.consent(repair.sourceId()).artifactSha256()))
            throw conflict("MODEL_SOURCE_NOT_AUTHORIZED");
    }

    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}
