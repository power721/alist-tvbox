package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.ProbeRun;
import cn.har01d.atmaintenance.repository.ProbeRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProbeStore {
    private final ProbeRunRepository runs;
    private final SourceService sources;
    private final ObjectMapper mapper;
    private final ProfileService profiles;
    private final FixtureRuntime runtime;
    private final ContainerRuntime container;
    private final ManagedSourceRuntime managed;
    private final AtBindingService bindings;
    private final AtReadClient atClient;
    private final SourceCredentialStore credentials;
    private static final List<String> MAINTENANCE_ACTIVE = List.of("CHECKING", "REPAIRING");

    @Transactional
    public ProbeRun createMaintenance(String sourceId, String inspectorSha, String knowledgeSha,
                                      StartMaintenanceRequest input) {
        var source = sources.requireForUpdate(sourceId);
        if (!sources.currentArtifact(source).getSha256().equals(input.expectedArtifactSha256()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MAINTENANCE_SOURCE_CHANGED");
        var previous = existingMaintenance(sourceId, input);
        if (previous != null) return previous;
        profiles.prepareLiveDefault(sourceId, managed.forSource(sourceId).manifest().path("runtimeSha256").asText());
        var run = create(sourceId, inspectorSha, knowledgeSha, "managed_source");
        run.setMaintenanceKey(input.requestKey());
        run.setMaintenanceConfigurationSha256(input.expectedConfigurationSha256());
        run.setMaintenanceMaxRounds(input.maxRounds());
        run.setMaintenanceStatus("CHECKING");
        return runs.saveAndFlush(run);
    }

    @Transactional(readOnly = true)
    public ProbeRun existingMaintenance(String sourceId, StartMaintenanceRequest input) {
        var source = sources.requireSource(sourceId);
        if (!sources.currentArtifact(source).getSha256().equals(input.expectedArtifactSha256()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MAINTENANCE_SOURCE_CHANGED");
        var previous = runs.findBySourceIdAndMaintenanceKey(sourceId, input.requestKey());
        if (previous.isEmpty())
            previous = runs.findBySourceIdAndMaintenanceStatusIn(sourceId, MAINTENANCE_ACTIVE).stream().findFirst();
        if (previous.isPresent()) {
            var run = previous.get();
            if (!run.getArtifactSha256().equals(input.expectedArtifactSha256())
                    || !run.getMaintenanceConfigurationSha256().equals(input.expectedConfigurationSha256())
                    || !java.util.Objects.equals(run.getMaintenanceMaxRounds() == null ? 3 : run.getMaintenanceMaxRounds(),
                            input.maxRounds() == null ? 3 : input.maxRounds()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "MAINTENANCE_INPUTS_CHANGED");
            return run;
        }
        return null;
    }

    @Transactional
    public ProbeRun recordPreparationFailure(String sourceId, String inspectorSha, String knowledgeSha,
                                             StartMaintenanceRequest input, String code) {
        sources.requireForUpdate(sourceId);
        if (input != null) {
            var previous = existingMaintenance(sourceId, input);
            if (previous != null) return previous;
        }
        profiles.prepareLiveDefault(sourceId, managed.forSource(sourceId).manifest().path("runtimeSha256").asText());
        var run = input == null ? create(sourceId, inspectorSha, knowledgeSha, "managed_source")
                : createMaintenance(sourceId, inspectorSha, knowledgeSha, input);
        var result = mapper.createObjectNode().put("repairSuccessful", false);
        result.putObject("sourcePreparation").put("status", "FAILED").put("code", code)
                .put("sourceExecuted", false).put("sourceHttpObserved", false);
        var nodes = result.putArray("nodes");
        int blocked = 0;
        for (var required : decode(run.getProfileJson()).path("requiredNodes")) {
            boolean setupNode = "S06".equals(required.asText());
            if (setupNode) blocked++;
            nodes.addObject().put("id", required.asText()).put("status", setupNode ? "blocked" : "not_tested")
                    .put("reason", code).put("evidenceClass", "not_observed");
        }
        result.putObject("requiredCoverage").put("total", nodes.size()).put("pass", 0).put("fail", 0)
                .put("blocked", blocked).put("notTested", nodes.size() - blocked)
                .put("conclusion", "source_preparation_failed")
                .put("boundary", "准备阶段失败，未执行源代码或源站业务请求");
        finish(run.getId(), "BLOCKED", result, null, code);
        if (input != null && !"SOURCE_SETUP_DNS_NO_ADDRESS".equals(code))
            finishMaintenance(run.getId(), "STOPPED", null, code);
        return run;
    }

    @Transactional(readOnly = true)
    public List<ProbeRun> activeMaintenance() {
        return runs.findByMaintenanceStatusIn(MAINTENANCE_ACTIVE);
    }

    @Transactional(readOnly = true)
    public boolean hasActiveMaintenance(String sourceId) {
        return !runs.findBySourceIdAndMaintenanceStatusIn(sourceId, MAINTENANCE_ACTIVE).isEmpty();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceSummary> maintenanceList(String sourceId) {
        sources.requireSource(sourceId);
        return runs.findTop30BySourceIdAndMaintenanceKeyIsNotNullOrderByCreatedAtDesc(sourceId).stream()
                .map(this::maintenanceSummary).toList();
    }

    @Transactional(readOnly = true)
    public MaintenanceSummary maintenance(String id) {
        var run = require(id);
        if (run.getMaintenanceKey() == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MAINTENANCE_NOT_FOUND");
        return maintenanceSummary(run);
    }

    @Transactional
    public MaintenanceSummary finishMaintenance(String id, String status, String repairId, String code) {
        var run = requireForUpdate(id);
        if (MAINTENANCE_ACTIVE.contains(run.getMaintenanceStatus())) {
            run.setMaintenanceStatus(status);
            run.setMaintenanceRepairId(repairId);
            run.setMaintenanceStopCode(code);
        }
        return maintenanceSummary(run);
    }

    @Transactional
    public boolean completeRevalidatedMaintenance(String id, String sourceId, String artifactSha, String repairId) {
        var run = requireForUpdate(id);
        if (!sourceId.equals(run.getSourceId()) || !artifactSha.equals(run.getArtifactSha256()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MAINTENANCE_REPAIR_MISMATCH");
        // Direct automatic repairs have no enclosing maintenance workflow to reconcile.
        if (run.getMaintenanceKey() == null) return true;
        if (!repairId.equals(run.getMaintenanceRepairId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MAINTENANCE_REPAIR_MISMATCH");
        if ("STOPPED".equals(run.getMaintenanceStatus())) {
            run.setMaintenanceStatus("SUCCEEDED");
            run.setMaintenanceStopCode(null);
        }
        // Explicit cancellation is never revived by a late runtime receipt.
        return "SUCCEEDED".equals(run.getMaintenanceStatus());
    }

    @Transactional
    public MaintenanceSummary resumeMaintenance(String id, StartMaintenanceRequest input) {
        return resumeMaintenance(id, input, false);
    }

    @Transactional
    public MaintenanceSummary resumeMaintenance(String id, StartMaintenanceRequest input, boolean networkRecovery) {
        var run = requireForUpdate(id);
        sources.requireMaintenanceAllowed(run.getSourceId());
        if (!input.expectedArtifactSha256().equals(run.getArtifactSha256()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MAINTENANCE_INPUTS_CHANGED");
        if (MAINTENANCE_ACTIVE.contains(run.getMaintenanceStatus())) {
            if (!input.expectedConfigurationSha256().equals(run.getMaintenanceConfigurationSha256()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "MAINTENANCE_INPUTS_CHANGED");
            return maintenanceSummary(run);
        }
        if ("STOPPED".equals(run.getMaintenanceStatus()) && run.getMaintenanceRepairId() == null
                && "SOURCE_SETUP_DNS_NO_ADDRESS".equals(run.getMaintenanceStopCode())
                && RepairReport.dnsPreparationFailure(summary(run))) {
            run.setMaintenanceConfigurationSha256(input.expectedConfigurationSha256());
            run.setMaintenanceStatus("CHECKING");
            run.setMaintenanceStopCode(null);
            return maintenanceSummary(run);
        }
        if (!"STOPPED".equals(run.getMaintenanceStatus()) || run.getMaintenanceRepairId() == null
                || !List.of("MODEL_DAILY_BUDGET", "MODEL_COST_DAILY_BUDGET", "MODEL_SOURCE_DAILY_BUDGET",
                "MODEL_SOURCE_COST_DAILY_BUDGET", "MODEL_NAME_DAILY_BUDGET", "MODEL_NAME_COST_DAILY_BUDGET",
                "MODEL_INPUT_TOO_LARGE")
                .contains(run.getMaintenanceStopCode())
                && !ModelResponsePolicy.explicitlyRecoverable(run.getMaintenanceStopCode()) && !(networkRecovery
                    && List.of("RUNTIME_NETWORK_UNAVAILABLE", "REPEATED_FAILURE", "ROUND_LIMIT", "REVERSE_ENGINE_NOT_READY")
                        .contains(run.getMaintenanceStopCode())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MAINTENANCE_RESUME_NOT_READY");
        // Explicit recovery keeps the original inspection and repair.
        run.setMaintenanceConfigurationSha256(input.expectedConfigurationSha256());
        run.setMaintenanceStatus("REPAIRING");
        run.setMaintenanceStopCode(null);
        return maintenanceSummary(run);
    }

    private MaintenanceSummary maintenanceSummary(ProbeRun run) {
        return new MaintenanceSummary(run.getId(), run.getSourceId(), run.getArtifactSha256(),
                run.getMaintenanceStatus(), run.getMaintenanceRepairId(), run.getMaintenanceStopCode());
    }

    @Transactional
    public ProbeRun create(String sourceId, String inspectorSha, String knowledgeSha) {
        return create(sourceId, inspectorSha, knowledgeSha, "static");
    }

    @Transactional
    public ProbeRun create(String sourceId, String inspectorSha, String knowledgeSha, String mode) {
        sources.requireMaintenanceAllowed(sourceId);
        var source = sources.requireForUpdate(sourceId);
        var artifact = sources.currentArtifact(source);
        ProbeRun run = new ProbeRun();
        run.setSourceId(sourceId);
        run.setSourceRevision(source.getRowVersion());
        run.setArtifactId(artifact.getId());
        run.setArtifactSha256(artifact.getSha256());
        run.setInspectorSha256(inspectorSha);
        run.setKnowledgeSha256(knowledgeSha);
        var profile = profiles.current(source);
        run.setProfileSha256(profile.sha256());
        run.setProfileJson(profile.snapshot().toString());
        run.setExecutionMode(mode);
        var binding = bindings.current(sourceId);
        if (binding != null) {
            run.setBindingSha256(binding.sha256());
            run.setBindingJson(binding.snapshot().toString());
        }
        if (List.of("offline_fixture", "container_fixture").contains(mode)) {
            run.setRuntimeSha256(runtime.sha256());
            run.setRuntimeJson(runtime.manifest().toString());
        }
        if (mode.equals("container_fixture")) {
            run.setExecutionPolicySha256(container.sha256());
            run.setExecutionPolicyJson(container.snapshot().toString());
        }
        if (mode.equals("managed_source")) {
            run.setCredentialRevision(credentials.revision(sourceId));
            var selected = managed.forSource(sourceId);
            run.setRuntimeSha256(selected.sha256());
            run.setRuntimeJson(selected.manifest().toString());
            run.setExecutionPolicySha256(selected.policySha256());
            run.setExecutionPolicyJson(selected.snapshot().toString());
        }
        return runs.saveAndFlush(run);
    }

    @Transactional
    public boolean start(String id) {
        ProbeRun run = requireForUpdate(id);
        sources.requireMaintenanceAllowed(run.getSourceId());
        if (!run.getStatus().equals("QUEUED")) {
            return false;
        }
        run.setStatus("RUNNING");
        return true;
    }

    @Transactional
    public ProbeSummary requestCancel(String id) {
        ProbeRun run = requireForUpdate(id);
        if (run.getStatus().equals("QUEUED")) {
            cancelled(run);
        } else if (run.getStatus().equals("RUNNING")) {
            run.setStatus("CANCELLING");
        }
        return summary(run);
    }

    @Transactional
    public void finish(String id, String status, JsonNode result, JsonNode context, String errorCode) {
        ProbeRun run = requireForUpdate(id);
        if (!List.of("QUEUED", "RUNNING", "CANCELLING").contains(run.getStatus())) {
            return;
        }
        // Completion and cancellation share a row lock. Once accepted, a
        // cancellation cannot be overwritten by a late successful result.
        if (run.getStatus().equals("CANCELLING") && !List.of("INSPECTOR_CLEANUP_FAILED",
                "CONTAINER_CLEANUP_UNCONFIRMED", "MANAGED_CLEANUP_UNCONFIRMED").contains(errorCode == null ? "" : errorCode)) {
            cancelled(run);
            return;
        }
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
        run.setResultJson(encode(result));
        run.setContextJson(encode(context));
        run.setErrorCode(errorCode);
    }

    @Transactional
    public void interruptUnfinished() {
        for (ProbeRun run : runs.findByStatusIn(List.of("QUEUED", "RUNNING", "CANCELLING"))) {
            run.setStatus("INTERRUPTED");
            run.setErrorCode("SERVICE_RESTARTED");
            run.setFinishedAt(Instant.now());
        }
    }

    @Transactional(readOnly = true)
    public List<ProbeRun> unfinishedContainerRuns() {
        return runs.findByStatusIn(List.of("RUNNING", "CANCELLING")).stream()
                .filter(run -> run.getExecutionMode().equals("container_fixture")).toList();
    }

    @Transactional(readOnly = true)
    public List<ProbeRun> unfinishedManagedRuns() {
        return runs.findByStatusIn(List.of("RUNNING", "CANCELLING")).stream()
                .filter(run -> run.getExecutionMode().equals("managed_source")).toList();
    }

    @Transactional(readOnly = true)
    public ProbeSummary get(String id) {
        return summary(require(id));
    }

    @Transactional
    public SourceCredentialSummary observeCredentials(String sourceId, ObserveSourceCredentialsRequest input)
            throws java.io.IOException {
        var source = sources.requireForUpdate(sourceId);
        var run = require(input.probeRunId());
        if (!sources.currentArtifact(source).getSha256().equals(input.expectedArtifactSha256())
                || !run.getSourceId().equals(sourceId)
                || !run.getArtifactSha256().equals(input.expectedArtifactSha256())
                || !"managed_source".equals(run.getExecutionMode()) || !"COMPLETED".equals(run.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SOURCE_CREDENTIAL_EVIDENCE_MISMATCH");
        String revision = run.getCredentialRevision() == null ? "" : run.getCredentialRevision();
        if (!revision.equals(input.expectedRevision()) || !revision.equals(credentials.revision(sourceId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SOURCE_CREDENTIALS_CHANGED");
        JsonNode result = decode(run.getResultJson());
        JsonNode evidence = result == null ? null : result.path("managedSourceEvidence");
        if (evidence == null || !run.getId().equals(evidence.path("jobId").asText())
                || !run.getArtifactSha256().equals(evidence.path("inputSourceSha256").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SOURCE_CREDENTIAL_EVIDENCE_MISMATCH");
        credentials.observe(sourceId, revision, run.getArtifactSha256(), evidence);
        return credentials.summary(sourceId);
    }

    @Transactional(readOnly = true)
    public void requireEvidenceRun(String sourceId, String runId, String expectedSha) {
        evidenceRun(sourceId, require(runId), expectedSha);
    }

    @Transactional
    public ObjectNode attachEvidence(String sourceId, String runId, String expectedSha, String kind, ObjectNode evidence) {
        var run = requireForUpdate(runId);
        evidenceRun(sourceId, run, expectedSha);
        if (!List.of("extractionReplay", "browserEvidence", "pageDiagnostics").contains(kind) || evidence == null
                || !expectedSha.equals(evidence.path("artifactSha256").asText()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AUXILIARY_EVIDENCE_INVALID");
        // Use the persistence parser's numeric representation for idempotent reattachment.
        var bound = ((ObjectNode) decode(evidence.toString())).put("runId", runId).put("sourceId", sourceId);
        if (bound.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 524288)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AUXILIARY_EVIDENCE_LIMIT");
        var context = decode(run.getContextJson());
        var updated = context != null && context.isObject() ? ((ObjectNode) context).deepCopy() : mapper.createObjectNode();
        var auxiliary = updated.withObject("auxiliaryEvidence");
        if (auxiliary.has(kind) && !auxiliary.get(kind).equals(bound))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AUXILIARY_EVIDENCE_ALREADY_FROZEN");
        auxiliary.set(kind, bound);
        run.setContextJson(updated.toString());
        return bound;
    }

    private void evidenceRun(String sourceId, ProbeRun run, String expectedSha) {
        if (!run.getSourceId().equals(sourceId) || !run.getArtifactSha256().equals(expectedSha)
                || !List.of("COMPLETED", "FAILED").contains(run.getStatus())
                || !summary(run).freshness().equals("current"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AUXILIARY_EVIDENCE_RUN_MISMATCH");
    }

    @Transactional(readOnly = true)
    public List<ProbeSummary> list(String sourceId) {
        sources.requireSource(sourceId);
        return runs.findTop30BySourceIdOrderByCreatedAtDesc(sourceId).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public ProbeSummary latest(String sourceId, String sha) {
        return runs.findFirstBySourceIdAndArtifactSha256OrderByCreatedAtDesc(sourceId, sha)
                .map(this::summary).orElse(null);
    }

    @Transactional(readOnly = true)
    public JsonNode runtimeCredentials(String runId) throws java.io.IOException {
        var run = require(runId);
        return credentials.execution(run.getSourceId(), run.getCredentialRevision());
    }

    private ProbeRun require(String id) {
        return runs.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND"));
    }

    private ProbeRun requireForUpdate(String id) {
        return runs.findForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND"));
    }

    private void cancelled(ProbeRun run) {
        run.setStatus("CANCELLED");
        run.setErrorCode("USER_CANCELLED");
        run.setFinishedAt(Instant.now());
    }

    private ProbeSummary summary(ProbeRun run) {
        var current = sources.get(run.getSourceId());
        boolean sourceChanged = !current.artifactSha256().equals(run.getArtifactSha256());
        boolean profileChanged = !current.profileSha256().equals(run.getProfileSha256());
        String freshness = run.getProfileSha256() == null ? "legacy_unbound"
                : sourceChanged && profileChanged ? "source_and_profile_changed"
                : sourceChanged ? "source_changed" : profileChanged ? "profile_changed" : "current";
        var staleInputs = new java.util.ArrayList<String>();
        if (sourceChanged) staleInputs.add("source");
        if (profileChanged) staleInputs.add("profile");
        boolean managedMode = run.getExecutionMode().equals("managed_source");
        if (managedMode && !credentials.revision(run.getSourceId()).equals(
                run.getCredentialRevision() == null ? "" : run.getCredentialRevision())) {
            staleInputs.add("credentials");
            if (freshness.equals("current")) freshness = "credentials_changed";
        }
        var selectedRuntime = managed.forSource(run.getSourceId());
        if (run.getRuntimeSha256() != null && !run.getRuntimeSha256().equals(
                managedMode ? selectedRuntime.sha256() : runtime.sha256())) {
            staleInputs.add("runtime");
            if (freshness.equals("current")) freshness = "runtime_changed";
        }
        if (run.getExecutionPolicySha256() != null && !run.getExecutionPolicySha256().equals(
                managedMode ? selectedRuntime.policySha256() : container.sha256())) {
            staleInputs.add("execution_policy");
            if (freshness.equals("current")) freshness = "execution_policy_changed";
        }
        var binding = bindings.current(run.getSourceId());
        if (!java.util.Objects.equals(run.getBindingSha256(), binding == null ? null : binding.sha256())) {
            staleInputs.add("at_binding");
            if (freshness.equals("current")) freshness = "binding_changed";
        }
        boolean frozenConnectionChanged = run.getBindingJson() != null
                && !decode(run.getBindingJson()).path("connection").path("instanceKey").asText()
                .equals(atClient.connection().instanceKey());
        if (binding != null && !binding.connectionMatches() || frozenConnectionChanged) {
            staleInputs.add("at_connection");
            if (freshness.equals("current")) freshness = "at_connection_changed";
        }
        return new ProbeSummary(run.getId(), run.getSourceId(), run.getArtifactSha256(),
                run.getInspectorSha256(), run.getKnowledgeSha256(), run.getStatus(), run.getCreatedAt(),
                run.getFinishedAt(), decode(run.getResultJson()), decode(run.getContextJson()), run.getErrorCode(),
                run.getProfileSha256(), decode(run.getProfileJson()), freshness,
                run.getExecutionMode(), run.getRuntimeSha256(), decode(run.getRuntimeJson()), List.copyOf(staleInputs),
                run.getBindingSha256(), decode(run.getBindingJson()), run.getTriggerKind(),
                run.getScheduleSha256(), decode(run.getScheduleJson()),
                run.getScheduleBudgetDay(), run.getScheduleOrdinal(),
                run.getExecutionPolicySha256(), decode(run.getExecutionPolicyJson()), run.getSourceRevision());
    }

    private String encode(JsonNode value) {
        return value == null ? null : value.toString();
    }

    private JsonNode decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored probe JSON is invalid", e);
        }
    }
}
