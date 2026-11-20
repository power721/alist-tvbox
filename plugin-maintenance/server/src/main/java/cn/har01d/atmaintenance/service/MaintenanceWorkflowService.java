package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.ProbeRun;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class MaintenanceWorkflowService {
    private final ProbeStore store;
    private final ProbeService probes;
    private final SourceService sources;
    private final ModelStore models;
    private final ModelClient client;
    private final RepairStore repairs;
    private final AutomaticRepairStore automatic;
    private final AutomaticRepairService automation;
    private final ProbeScheduleStore schedules;
    private final SourceCredentialStore credentials;
    private final RepairDiagnostics diagnostics;

    public synchronized MaintenanceSummary start(String sourceId, StartMaintenanceRequest input) {
        sources.requireMaintenanceAllowed(sourceId);
        String blocked = credentials.blockedReason(sourceId);
        if (blocked != null) throw conflict(blocked);
        if (!sources.get(sourceId).artifactSha256().equals(input.expectedArtifactSha256()))
            throw conflict("MAINTENANCE_SOURCE_CHANGED");
        requireModel(sourceId, input.expectedArtifactSha256(), input.expectedConfigurationSha256());
        return store.maintenance(probes.startMaintenance(sourceId, input).id());
    }

    public synchronized MaintenanceSummary cancel(String runId) {
        var task = store.maintenance(runId);
        if (!Set.of("CHECKING", "REPAIRING").contains(task.status())) return task;
        var stopped = store.finishMaintenance(runId, "CANCELLED", task.repairId(), "USER_CANCELLED");
        probes.cancel(runId);
        if (task.repairId() != null) automation.cancel(task.repairId());
        return stopped;
    }

    public synchronized MaintenanceSummary resume(String runId, StartMaintenanceRequest input) {
        var task = store.maintenance(runId);
        sources.requireMaintenanceAllowed(task.sourceId());
        String blocked = credentials.blockedReason(task.sourceId());
        if (blocked != null) throw conflict(blocked);
        boolean networkRecovery = "STOPPED".equals(task.status()) && task.repairId() != null
                && Set.of("RUNTIME_NETWORK_UNAVAILABLE", "REPEATED_FAILURE", "ROUND_LIMIT", "REVERSE_ENGINE_NOT_READY")
                    .contains(task.stopCode() == null ? "" : task.stopCode());
        var originalRun = store.get(runId);
        boolean dnsRecovery = "STOPPED".equals(task.status()) && task.repairId() == null
                && "SOURCE_SETUP_DNS_NO_ADDRESS".equals(task.stopCode()) && RepairReport.dnsPreparationFailure(originalRun)
                && Set.of("runtime", "execution_policy").containsAll(originalRun.staleInputs());
        // Network recovery validates the repair's current runtime context below, not the historical probe runtime.
        if (!sources.get(task.sourceId()).artifactSha256().equals(input.expectedArtifactSha256())
                || !networkRecovery && !dnsRecovery && !"current".equals(originalRun.freshness()))
            throw conflict("MAINTENANCE_INPUTS_CHANGED");
        requireModel(task.sourceId(), input.expectedArtifactSha256(), input.expectedConfigurationSha256());
        if (networkRecovery) {
            var repair = repairs.get(task.repairId());
            if (!"REVERSE_ENGINE_NOT_READY".equals(task.stopCode())) repairs.resume(repair.id(), repair.faultSha256());
        }
        var resumed = networkRecovery ? store.resumeMaintenance(runId, input, true)
                : store.resumeMaintenance(runId, input);
        if ("REPAIRING".equals(resumed.status())) {
            var repair = repairs.get(resumed.repairId());
            automation.start(repair.id(), new StartModelCallRequest(repair.faultSha256(),
                    input.expectedConfigurationSha256(), input.requestKey(), true,
                    automatic.get(repair.id()).enhancedDiagnostics()));
        }
        return resumed;
    }

    public synchronized MaintenanceSummary continueWithEvidence(String runId, ContinueMaintenanceEvidenceRequest input) {
        var task = store.maintenance(runId);
        sources.requireMaintenanceAllowed(task.sourceId());
        if (task.repairId() == null) throw conflict("MAINTENANCE_RESUME_NOT_READY");
        String blocked = credentials.blockedReason(task.sourceId());
        if (blocked != null) throw conflict(blocked);
        var repair = repairs.get(task.repairId());
        if (!sources.get(task.sourceId()).artifactSha256().equals(input.expectedArtifactSha256())
                || !repair.baselineArtifactSha256().equals(input.expectedArtifactSha256())
                || !"current".equals(repair.freshness())) throw conflict("MAINTENANCE_INPUTS_CHANGED");
        requireModel(task.sourceId(), input.expectedArtifactSha256(), input.expectedConfigurationSha256());
        repair = repairs.continueWithEvidence(repair.id(), input);
        // Repeating the same explicit request must not revive a finished or cancelled follow-up.
        if (!"AWAITING_PROPOSAL".equals(repair.status())
                || RepairEvidenceMaterial.activeGrant(repair).isMissingNode()) return store.maintenance(runId);
        var resumed = store.resumeMaintenance(runId, new StartMaintenanceRequest(input.expectedArtifactSha256(),
                input.expectedConfigurationSha256(), input.requestKey()), true);
        automation.start(repair.id(), new StartModelCallRequest(repair.faultSha256(),
                input.expectedConfigurationSha256(), input.requestKey(), true, true));
        return resumed;
    }

    @Scheduled(fixedDelayString = "${maintenance.workflow.poll-millis:1000}", initialDelay = 1000)
    public synchronized void poll() {
        for (var row : store.activeMaintenance()) {
            try { advance(row); }
            catch (ResponseStatusException error) {
                stop(row.getId(), error.getReason());
            } catch (RuntimeException error) {
                stop(row.getId(), "MAINTENANCE_INTERNAL_ERROR");
            }
        }
    }

    private void stop(String id, String reason) {
        var stopped = store.finishMaintenance(id, "STOPPED", store.maintenance(id).repairId(), reason);
        if ("scheduled".equals(store.get(id).triggerKind())) {
            probes.cancel(id);
            if (stopped.repairId() != null) automation.cancel(stopped.repairId());
        }
    }

    private void advance(ProbeRun row) {
        String id = row.getId(), repairId = row.getMaintenanceRepairId();
        String blocked = schedules.executionBlockedReason(row);
        if (blocked != null) throw conflict(blocked);
        if ("CHECKING".equals(row.getMaintenanceStatus())) {
            var run = store.get(id);
            if (Set.of("QUEUED", "RUNNING", "CANCELLING").contains(run.status())) return;
            if (RepairReport.dnsPreparationFailure(run)) {
                requireModel(row.getSourceId(), row.getArtifactSha256(), row.getMaintenanceConfigurationSha256());
                repairId = repairs.createOriginRecovery(row.getSourceId(),
                        new StartRepairRequest(id, row.getArtifactSha256(), row.getMaintenanceMaxRounds())).id();
                store.finishMaintenance(id, "REPAIRING", repairId, null);
                var repair = repairs.get(repairId);
                automation.start(repairId, new StartModelCallRequest(repair.faultSha256(),
                        row.getMaintenanceConfigurationSha256(), "maintenance_" + id, true, true));
                return;
            }
            if (!"COMPLETED".equals(run.status()) || run.errorCode() != null)
                throw conflict(run.errorCode() == null ? "LIVE_INSPECTION_NOT_COMPLETED" : run.errorCode());
            if (!"current".equals(run.freshness())) throw conflict("MAINTENANCE_INPUTS_CHANGED");
            String credentialBlock = credentials.blockedReason(row.getSourceId());
            if (credentialBlock != null) throw conflict(credentialBlock);
            requireLive(run.result());
            if (diagnostics.forRun(run).path("hostConfigurationRequired").asBoolean())
                throw conflict("LIVE_INSPECTION_HOST_CONFIGURATION_REQUIRED");
            boolean fault = run.result().path("nodes").valueStream().anyMatch(node ->
                    RepairReport.repairableLoaderNode(node)
                            && "host_loader_contract_observed".equals(node.path("evidenceClass").asText()));
            if (!fault) {
                if (run.result().path("nodes").valueStream().anyMatch(node ->
                        "R01".equals(node.path("id").asText())
                                && "blocked".equals(node.path("status").asText())
                                && "host_loader_contract_observed".equals(node.path("evidenceClass").asText())
                                && "PLAYER_OFFLINE_RESOLVER_REQUIRED".equals(node.path("reason").asText())))
                    throw conflict("LIVE_INSPECTION_OFFLINE_RESOLVER_REQUIRED");
                var coverage = run.result().path("requiredCoverage");
                boolean business = run.result().path("nodes").valueStream().anyMatch(node ->
                        "pass".equals(node.path("status").asText())
                                && Set.of("C03", "C05", "C07", "C09", "C10", "R01", "M01")
                                .contains(node.path("id").asText()));
                if (!business || coverage.path("total").asInt() < 1
                        || coverage.path("total").asInt() != coverage.path("pass").asInt())
                    throw conflict("LIVE_INSPECTION_INCOMPLETE");
                store.finishMaintenance(id, "CHECKED", null, null);
                return;
            }
            var access = diagnostics.forRun(run).path("accessPage");
            if (access.path("repairBlocked").asBoolean())
                throw conflict("cloudflare_access_denied".equals(access.path("classification").asText())
                        ? "LIVE_INSPECTION_SOURCE_ACCESS_DENIED" : "LIVE_INSPECTION_SOURCE_ACCESS_CHALLENGE");
            requireModel(row.getSourceId(), row.getArtifactSha256(), row.getMaintenanceConfigurationSha256());
            repairId = repairs.create(row.getSourceId(), new StartRepairRequest(id, row.getArtifactSha256(), row.getMaintenanceMaxRounds())).id();
            // Persist the link before starting automation so a restart reuses this exact repair.
            store.finishMaintenance(id, "REPAIRING", repairId, null);
        }
        var task = automatic.get(repairId);
        if ("DISABLED".equals(task.status())) {
            requireModel(row.getSourceId(), row.getArtifactSha256(), row.getMaintenanceConfigurationSha256());
            var repair = repairs.get(repairId);
            automation.start(repairId, new StartModelCallRequest(repair.faultSha256(),
                    row.getMaintenanceConfigurationSha256(), "maintenance_" + id, true));
        } else if (!"ACTIVE".equals(task.status())) {
            store.finishMaintenance(id, "SUCCEEDED".equals(task.status()) ? "SUCCEEDED" : "STOPPED",
                    repairId, task.stopCode());
        }
    }

    private void requireModel(String sourceId, String artifactSha, String configurationSha) {
        client.requireEnabled();
        var connection = client.connection();
        if (!configurationSha.equals(connection.configurationSha256())) throw conflict("MODEL_CONFIGURATION_CHANGED");
        if (!connection.autoSubmitEnabled()) throw conflict("MODEL_AUTO_SUBMIT_DISABLED");
        if (!artifactSha.equals(models.consent(sourceId).artifactSha256())) throw conflict("MODEL_SOURCE_NOT_AUTHORIZED");
    }

    static void requireLive(JsonNode result) {
        var evidence = result == null ? null : result.path("managedSourceEvidence");
        if (evidence == null || !evidence.isObject()) throw conflict("LIVE_INSPECTION_EVIDENCE_MISSING");
        if (!evidence.path("cleanup").path("confirmed").asBoolean()
                || !evidence.path("cleanup").path("containerAbsent").asBoolean())
            throw conflict("MANAGED_CLEANUP_UNCONFIRMED");
        if (RepairReport.networkUnavailable(evidence)) throw conflict("LIVE_INSPECTION_NETWORK_UNAVAILABLE");
        if (RepairReport.httpAccessDenied(evidence)) throw conflict("LIVE_INSPECTION_HTTP_ACCESS_DENIED");
        if (RepairReport.egressUnresolved(evidence))
            throw conflict("LIVE_INSPECTION_POLICY_LIMITED");
        boolean observed = evidence.path("httpObservations").valueStream().anyMatch(response ->
                response.path("responseComplete").asBoolean()
                        && response.path("responseStatus").isIntegralNumber()
                        && response.path("responseStatus").asInt() >= 100
                        && response.path("responseStatus").asInt() <= 599
                        && response.path("responseBytes").isIntegralNumber()
                        && response.path("responseBytes").asLong() >= 0);
        if (!observed) throw conflict("LIVE_INSPECTION_RESPONSE_MISSING");
        if (RepairReport.playbackNotProvided(result)) throw conflict("LIVE_INSPECTION_METADATA_ONLY");
    }

    private static ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}
