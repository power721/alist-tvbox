package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.*;
import cn.har01d.atmaintenance.repository.*;
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
import java.util.*;

@Service
@RequiredArgsConstructor
public class RepairStore {
    private static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING", "CANCELLING",
            "RUNTIME_QUEUED", "RUNTIME_RUNNING");
    private final RepairRepository repairs;
    private final RepairCandidateRepository candidates;
    private final SourceService sources;
    private final ProbeStore probes;
    private final StaticInspector inspector;
    private final KnowledgeService knowledge;
    private final RepairReport reports;
    private final RepairPatch patches;
    private final ObjectMapper mapper;
    private final SourceImportService imports;
    private final ManagedSourceRuntime managed;
    private final ModelCallRepository modelCalls;
    private final ModelBudgetRepository modelBudgets;

    @Transactional(readOnly = true)
    public int deliveryIteration(String candidateId) {
        var candidate = requireCandidate(candidateId);
        var repair = repairs.findById(candidate.getRepairId()).orElseThrow();
        var versions = new LinkedHashMap<String, Integer>();
        for (var row : candidates.findChangedCandidates(repair.getSourceId())) {
            versions.computeIfAbsent(row.getSha256(), ignored -> versions.size() + 1);
            if (row.getId().equals(candidateId)) return versions.get(row.getSha256());
        }
        return 0;
    }

    @Transactional
    public RepairSummary create(String sourceId, StartRepairRequest input) {
        return create(sourceId, input, false);
    }

    @Transactional
    public RepairSummary createOriginRecovery(String sourceId, StartRepairRequest input) {
        return create(sourceId, input, true);
    }

    private RepairSummary create(String sourceId, StartRepairRequest input, boolean originRecovery) {
        if (input.maxRounds() != null && input.maxRounds() < 1) throw conflict("REPAIR_ROUND_LIMIT_INVALID");
        sources.requireMaintenanceAllowed(sourceId);
        var source = sources.get(sourceId);
        var baseline = probes.get(input.baselineRunId());
        if (!baseline.sourceId().equals(sourceId)) throw conflict("REPAIR_SOURCE_MISMATCH");
        if (!baseline.artifactSha256().equals(input.expectedArtifactSha256())
                || !source.artifactSha256().equals(input.expectedArtifactSha256())
                || !baseline.freshness().equals("current") && !(originRecovery
                    && Set.of("runtime", "execution_policy").containsAll(baseline.staleInputs())))
            throw conflict("REPAIR_BASELINE_STALE");
        if (!baseline.inspectorSha256().equals(inspector.sha256())
                || !baseline.knowledgeSha256().equals(knowledge.sha256())) throw conflict("REPAIR_TOOLS_CHANGED");
        if (originRecovery ? !RepairReport.dnsPreparationFailure(baseline) || baseline.profile() == null
                : !baseline.status().equals("COMPLETED") || baseline.result() == null || baseline.profile() == null)
            throw conflict("REPAIR_BASELINE_NOT_READY");
        JsonNode frozenBaseline = mapper.valueToTree(baseline);
        if (RepairDiagnostics.bound(frozenBaseline.path("result").path("managedSourceEvidence"),
                baseline.artifactSha256(), baseline.profileSha256(), baseline.runtimeSha256())
                && RepairReport.hostConfigurationUnavailable(frozenBaseline.path("result").path("managedSourceEvidence"),
                    frozenBaseline.path("profile").path("requiredNodes")))
            throw conflict("LIVE_INSPECTION_HOST_CONFIGURATION_REQUIRED");
        if (RepairReport.networkUnavailable(frozenBaseline.path("result").path("managedSourceEvidence")))
            throw conflict("LIVE_INSPECTION_NETWORK_UNAVAILABLE");
        if (RepairReport.httpAccessDenied(frozenBaseline.path("result").path("managedSourceEvidence")))
            throw conflict("LIVE_INSPECTION_HTTP_ACCESS_DENIED");
        if (RepairReport.playbackNotProvided(frozenBaseline.path("result")))
            throw conflict("LIVE_INSPECTION_METADATA_ONLY");
        JsonNode evidence;
        try {
            evidence = originRecovery ? inspector.inspect(sources.artifact(sourceId, baseline.artifactSha256()).getBody())
                    : reports.staticEvidence(frozenBaseline);
        } catch (java.io.IOException error) {
            throw conflict("REPAIR_PREPARATION_INSPECTION_FAILED");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw conflict("REPAIR_PREPARATION_INSPECTION_INTERRUPTED");
        }
        boolean runtimeFault = runtimeFault(frozenBaseline);
        List<String> loaderFaults = loaderFaultNodes(frozenBaseline);
        JsonNode replay = frozenBaseline.path("context").path("auxiliaryEvidence").path("extractionReplay");
        boolean selectorFault = replay.path("authority").asText().equals("host_bounded_rule_extraction")
                && replay.path("passed").isBoolean() && !replay.path("passed").booleanValue()
                && replay.path("artifactSha256").asText().equals(baseline.artifactSha256())
                && replay.path("runId").asText().equals(baseline.id())
                && replay.path("sourceId").asText().equals(sourceId);
        if (!evidence.path("parseOk").asBoolean()
                || evidence.path("issues").isEmpty() && !runtimeFault && loaderFaults.isEmpty() && !selectorFault && !originRecovery)
            throw conflict("REPAIR_FAILURE_UNSUPPORTED");
        if (originRecovery && !baseline.artifactSha256().equals(evidence.path("sha256").asText()))
            throw conflict("RUNTIME_INPUT_MISMATCH");
        for (JsonNode issue : evidence.path("issues")) {
            if (!issue.path("code").asText().equals("duplicate_method")) throw conflict("REPAIR_FAILURE_UNSUPPORTED");
        }
        if (!evidence.path("methodContracts").isObject() || evidence.path("methodContracts").isEmpty()
                || evidence.path("methodsTruncated").asBoolean() || evidence.path("importsTruncated").asBoolean()
                || !evidence.path("classContractSha256").isTextual()
                || !evidence.path("importContractSha256").isTextual())
            throw conflict("REPAIR_CONTRACT_UNAVAILABLE");
        var identity = mapper.createObjectNode();
        identity.put("artifact", baseline.artifactSha256()).put("profile", baseline.profileSha256())
                .put("inspector", baseline.inspectorSha256()).put("knowledge", baseline.knowledgeSha256())
                .put("inspectorPython", evidence.path("pythonVersion").asText())
                .put("binding", baseline.bindingSha256()).put("runtime", baseline.runtimeSha256())
                .put("nodeCatalog", knowledge.nodeCatalogSha256());
        identity.set("issues", evidence.path("issues"));
        if (runtimeFault) identity.put("runtimeFault", "T4_HOME_EMPTY");
        if (!loaderFaults.isEmpty()) identity.set("loaderFaultNodes", mapper.valueToTree(loaderFaults));
        if (selectorFault) identity.put("extractionReplaySha256", Hashes.sha256(replay.toString()));
        if (originRecovery) identity.put("preparationFailure", baseline.errorCode());
        if (baseline.executionPolicySha256() != null) identity.put("executionPolicy", baseline.executionPolicySha256());
        String faultSha = Hashes.sha256(identity.toString());
        var previous = repairs.findBySourceIdAndFaultSha256(sourceId, faultSha);
        if (previous.isPresent()) return summary(previous.get());
        var frozen = mapper.createObjectNode().put("schemaVersion", 1).put("authority", "data_only")
                .put("nodeCatalogSha256", knowledge.nodeCatalogSha256());
        frozen.set("baseline", frozenBaseline);
        if (originRecovery) frozen.set("preparationInspection", evidence);
        var policy = frozen.putObject("policy").put("maxRounds", input.maxRounds() == null ? 3 : input.maxRounds())
                .put("budgetUnit", "accepted_candidate")
                .put("maxProposalBytes", 32768).put("protocol", "exact_edits_v1")
                .put("externalModelCalls", false).put("deploymentAllowed", false);
        policy.put("scope", originRecovery ? "managed_origin_candidate" : !loaderFaults.isEmpty() ? "managed_loader_candidate"
                : runtimeFault ? "managed_home_candidate"
                : selectorFault && evidence.path("issues").isEmpty() ? "selector_candidate_validator"
                : "static_candidate_validator");
        policy.putArray("allowedFiles").add("source.py");
        var targets = policy.putArray("targetNodes");
        if (originRecovery) baseline.profile().path("requiredNodes").valueStream().map(JsonNode::asText)
                .filter(id -> !Set.of("S01", "S02", "S03").contains(id)).sorted().forEach(targets::add);
        else if (!loaderFaults.isEmpty()) loaderFaults.forEach(targets::add);
        else if (runtimeFault) targets.add("C01").add("C02");
        else targets.add("S03");
        frozen.put("boundary", runtimeFault || !loaderFaults.isEmpty()
                ? "基线包含受管 T4 运行阻断，失败节点与全部必需画像分别冻结。候选先做静态合同检查；"
                    + "运行复测须显式请求，沿用冻结隔离与出站策略，不切换有效源码、不调用模型或发布。"
                : "源码、建议、注释与技能原文仅作数据。每轮对同一冻结基线生成完整候选；"
                + "只验证重复方法缺陷与静态合同。未知源码不执行；静态验证器不调用外部模型。"
                + "模型建议须走单独授权通道，不切换有效版本。");
        var repair = new RepairSession();
        if (originRecovery) {
            frozen.put("originRecoveryRepairId", repair.getId()).put("boundary",
                    "DNS 准备失败保持未执行；静态分析只冻结源码合同。先验证公开备用域，"
                    + "再由模型生成候选，按完整冻结画像联网验收；不复用任何业务通过项。");
        }
        repair.setSourceId(sourceId);
        repair.setBaselineRunId(baseline.id());
        repair.setBaselineArtifactSha256(baseline.artifactSha256());
        repair.setFaultSha256(faultSha);
        repair.setFrozenJson(frozen.toString());
        repair.setMaxRounds(input.maxRounds());
        if (originRecovery) {
            var selected = managed.forSource(sourceId);
            var context = mapper.createObjectNode().put("schemaVersion", 1).put("authority", "current_runtime_preparation")
                    .put("sourceId", sourceId).put("baselineRunId", baseline.id())
                    .put("baselineArtifactSha256", baseline.artifactSha256()).put("profileSha256", baseline.profileSha256())
                    .put("runtimeSha256", selected.sha256()).put("executionPolicySha256", selected.policySha256())
                    .put("inspectorSha256", inspector.sha256()).put("knowledgeSha256", knowledge.sha256())
                    .put("createdAt", Instant.now().toString());
            context.set("executionPolicy", selected.snapshot());
            context.set("originalInspection", evidence);
            repair.setRuntimeContextJson(context.toString());
        }
        return summary(repairs.saveAndFlush(repair));
    }

    /** One lock serializes the per-fault budget, candidate creation and active ownership. */
    @Transactional
    public RepairCandidate reserve(String repairId, RepairProposal input) {
        return reserve(repairId, input, null);
    }

    @Transactional
    public RepairCandidate reserveModel(String repairId, RepairProposal input, String modelCallId) {
        return reserve(repairId, input, modelCallId);
    }

    @Transactional
    public RepairCandidate reserveSelector(String repairId, RepairProposal input, ObjectNode evidence) {
        if (evidence == null || !evidence.path("passed").asBoolean()
                || !input.expectedBaselineSha256().equals(evidence.path("artifactSha256").asText())
                || evidence.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 524288)
            throw conflict("SELECTOR_EVIDENCE_INVALID");
        return reserve(repairId, input, null, evidence);
    }

    @Transactional
    public RepairCandidate reserveImported(String repairId, ImportedCandidateRequest input) {
        var repair = lock(repairId);
        sources.requireMaintenanceAllowed(repair.getSourceId());
        var archived = imports.candidate(input.importId());
        if (!archived.archiveSha256().equals(input.expectedArchiveSha256())
                || !archived.sourceId().equals(repair.getSourceId())
                || !archived.baselineArtifactSha256().equals(repair.getBaselineArtifactSha256()))
            throw conflict("IMPORTED_CANDIDATE_INPUTS_CHANGED");
        for (var previous : candidates.findByRepairIdOrderByRoundNumberAsc(repairId)) {
            if (input.importId().equals(previous.getSourceImportId())) return previous;
        }
        var candidate = reserve(repairId, imports.proposal(input.importId()), null, null, input.importId());
        if (!candidate.getSha256().equals(archived.candidateSha256()))
            throw conflict("IMPORTED_CANDIDATE_BYTES_CHANGED");
        return candidate;
    }

    private RepairCandidate reserve(String repairId, RepairProposal input, String modelCallId) {
        return reserve(repairId, input, modelCallId, null);
    }

    private RepairCandidate reserve(String repairId, RepairProposal input, String modelCallId, ObjectNode selectorEvidence) {
        return reserve(repairId, input, modelCallId, selectorEvidence, null);
    }

    private RepairCandidate reserve(String repairId, RepairProposal input, String modelCallId,
                                    ObjectNode selectorEvidence, String sourceImportId) {
        var repair = lock(repairId);
        sources.requireMaintenanceAllowed(repair.getSourceId());
        if (!repair.getStatus().equals("AWAITING_PROPOSAL") || repair.getUsedRounds() >= roundLimit(repair))
            throw conflict("REPAIR_NOT_ACCEPTING_PROPOSALS");
        if (!input.expectedBaselineSha256().equals(repair.getBaselineArtifactSha256()))
            throw conflict("REPAIR_BASELINE_MISMATCH");
        if (!summary(repair).freshness().equals("current")) throw conflict("REPAIR_INPUTS_CHANGED");
        var targets = new HashSet<String>();
        decode(repair.getFrozenJson()).path("policy").path("targetNodes").forEach(node -> targets.add(node.asText()));
        if (input.validationTargets() == null || input.validationTargets().size() != targets.size()
                || !targets.equals(new HashSet<>(input.validationTargets()))) throw conflict("PATCH_TARGET_MISMATCH");
        String proposalJson = patches.encode(input);
        String body = patches.apply(sources.artifact(repair.getSourceId(), repair.getBaselineArtifactSha256()).getBody(), input);
        var candidate = new RepairCandidate();
        candidate.setRepairId(repairId);
        candidate.setModelCallId(modelCallId);
        candidate.setSourceImportId(sourceImportId);
        candidate.setRoundNumber(repair.getUsedRounds() + 1);
        candidate.setBody(body);
        candidate.setSha256(Hashes.sha256(body));
        candidate.setProposalJson(proposalJson);
        candidate.setProposalSha256(Hashes.sha256(proposalJson));
        if (selectorEvidence != null)
            candidate.setSelectorEvidenceJson(selectorEvidence.deepCopy().put("candidateSha256", candidate.getSha256())
                    .put("baselineRunId", repair.getBaselineRunId()).toString());
        String rejection = candidate.getSha256().equals(repair.getBaselineArtifactSha256()) ? "PATCH_NO_CHANGE" : null;
        for (var previous : candidates.findByRepairIdOrderByRoundNumberAsc(repairId)) {
            // An explicitly resumed execution that never produced validation evidence may retry its bytes.
            if (previous.getResultJson() == null) continue;
            if (previous.getProposalSha256().equals(candidate.getProposalSha256())) rejection = "REPEATED_PATCH";
            else if (previous.getSha256().equals(candidate.getSha256()) && rejection == null) rejection = "REPEATED_CANDIDATE";
        }
        repair.setUsedRounds(candidate.getRoundNumber());
        if (rejection != null) {
            terminal(candidate, "REJECTED", rejection);
            repair.setStatus("STOPPED");
            repair.setStopCode(rejection);
        } else {
            repair.setStatus("VALIDATING");
            repair.setStopCode(null);
            repair.setActiveCandidateId(candidate.getId());
        }
        candidates.saveAndFlush(candidate);
        return candidate;
    }

    @Transactional
    public boolean start(String repairId, String candidateId) {
        var repair = lock(repairId);
        sources.requireMaintenanceAllowed(repair.getSourceId());
        var candidate = requireCandidate(candidateId);
        if (!candidateId.equals(repair.getActiveCandidateId())
                || !Set.of("QUEUED", "RUNTIME_QUEUED").contains(candidate.getStatus())) return false;
        candidate.setStatus(candidate.getStatus().equals("RUNTIME_QUEUED") ? "RUNTIME_RUNNING" : "RUNNING");
        return true;
    }

    @Transactional
    public RepairCandidate reserveAutomaticRuntimeRetry(String repairId) {
        var repair = lock(repairId);
        if (!"ACTIVE".equals(repair.getAutomaticStatus()) || !"BLOCKED".equals(repair.getStatus())
                || !"RUNTIME_NETWORK_UNAVAILABLE".equals(repair.getStopCode()))
            throw conflict("REPAIR_RUNTIME_NOT_READY");
        var history = candidates.findByRepairIdOrderByRoundNumberAsc(repairId);
        if (history.isEmpty()) throw conflict("RUNTIME_NETWORK_UNAVAILABLE");
        var candidate = history.getLast();
        var result = decode(candidate.getResultJson());
        if (!reports.networkRetryable(summary(repair).frozen(), result)
                || !result.path("runtimeAttempts").isEmpty() || candidate.getFinishedAt() == null)
            throw conflict("RUNTIME_NETWORK_UNAVAILABLE");
        if (candidate.getFinishedAt().plusSeconds(30).isAfter(Instant.now())) return null;
        // Reserve under the same repair lock: cancellation cannot be revived by a generic resume.
        repair.setStatus("AWAITING_RUNTIME_VALIDATION");
        repair.setStopCode(null);
        return reserveRuntime(candidate.getId(), candidate.getSha256());
    }

    @Transactional
    public RepairCandidate reserveRuntime(String candidateId, String expectedSha) {
        var candidate = requireCandidate(candidateId);
        var repair = lock(candidate.getRepairId());
        sources.requireMaintenanceAllowed(repair.getSourceId());
        if (!candidate.getSha256().equals(expectedSha)) throw conflict("REPAIR_CANDIDATE_CHANGED");
        JsonNode frozen = summary(repair).frozen();
        if (!Set.of("managed_home_candidate", "managed_loader_candidate", "managed_origin_candidate")
                .contains(frozen.path("policy").path("scope").asText()))
            throw conflict("REPAIR_RUNTIME_UNSUPPORTED");
        boolean retry = reports.networkRetryable(frozen, decode(candidate.getResultJson()))
                && candidates.findByRepairIdOrderByRoundNumberAsc(repair.getId()).getLast().getId().equals(candidateId);
        if (!repair.getStatus().equals("AWAITING_RUNTIME_VALIDATION")
                || !candidate.getStatus().equals("STATIC_VALIDATED") && !retry)
            throw conflict("REPAIR_RUNTIME_NOT_READY");
        if (!summary(repair).freshness().equals("current")) throw conflict("REPAIR_INPUTS_CHANGED");
        ObjectNode result = (ObjectNode) decode(candidate.getResultJson());
        if (retry) {
            var history = result.withArray("runtimeAttempts").deepCopy();
            ObjectNode previous = result.deepCopy();
            previous.remove("runtimeAttempts");
            previous.put("candidateStatus", candidate.getStatus()).put("candidateErrorCode", candidate.getErrorCode());
            history.add(previous);
            result = reports.build(frozen, candidate.getSha256(), result.path("inspection"));
            reports.applySelectorBinding(result, decode(candidate.getSelectorEvidenceJson()));
            result.set("runtimeAttempts", history);
            result.put("runtimeJobId", UUID.randomUUID().toString());
            candidate.setFailureSha256(null);
            candidate.setErrorCode(null);
        }
        result.put("runtimeRequested", true);
        candidate.setResultJson(result.toString());
        candidate.setStatus("RUNTIME_QUEUED");
        candidate.setFinishedAt(null);
        repair.setStatus("VALIDATING_RUNTIME");
        repair.setActiveCandidateId(candidateId);
        return candidate;
    }

    @Transactional
    public RepairCandidate reserveRevalidation(String candidateId, String expectedSha, String expectedFaultSha,
                                                JsonNode originalInspection, JsonNode candidateInspection) {
        var candidate = requireCandidate(candidateId);
        var repair = lock(candidate.getRepairId());
        sources.requireMaintenanceAllowed(repair.getSourceId());
        if (!candidate.getSha256().equals(expectedSha)) throw conflict("REPAIR_CANDIDATE_CHANGED");
        if (!repair.getFaultSha256().equals(expectedFaultSha)) throw conflict("REPAIR_FAULT_CHANGED");
        var current = summary(repair);
        if (!Set.of("managed_home_candidate", "managed_loader_candidate", "managed_origin_candidate")
                .contains(current.frozen().path("policy").path("scope").asText()))
            throw conflict("REPAIR_RUNTIME_UNSUPPORTED");
        if (current.staleInputs().stream().anyMatch(value ->
                !Set.of("runtime", "execution_policy", "inspector").contains(value)))
            throw conflict("REPAIR_INPUTS_CHANGED");
        var selected = managed.forSource(repair.getSourceId());
        boolean revalidationToolsChanged = Set.of("STOPPED", "RUNTIME_OBSERVED", "AWAITING_PROPOSAL").contains(repair.getStatus())
                && current.staleInputs().stream().anyMatch(Set.of("runtime", "inspector")::contains);
        var history = candidates.findByRepairIdOrderByRoundNumberAsc(repair.getId());
        JsonNode priorResult = decode(candidate.getResultJson());
        boolean assessmentRecovered = false;
        boolean compatibleContentBaseline = false;
        boolean contentBaselineMissing = !current.frozen().path("baseline").path("result")
                .path("managedSourceEvidence").path("loaderValidation").path("contentSnapshot").isObject();
        if (contentBaselineMissing) {
            for (var observed : probes.list(repair.getSourceId())) {
                if (reports.contentBaseline(observed, current.frozen(), repair.getSourceId(),
                        repair.getBaselineArtifactSha256(), selected.sha256(), selected.policySha256()) != null) {
                    compatibleContentBaseline = true;
                    break;
                }
            }
        }
        if (repair.getStatus().equals("STOPPED")
                && "RUNTIME_TARGET_UNRESOLVED".equals(candidate.getErrorCode())
                && priorResult != null && priorResult.path("runtimeEvidence").isObject()
                && !priorResult.path("repairSuccessful").asBoolean()) {
            JsonNode reassessed = reports.runtime(current.frozen(), priorResult, priorResult.path("runtimeEvidence"));
            assessmentRecovered = reassessed.path("repairSuccessful").asBoolean()
                    || reassessed.path("runtimeValidationPaused").asBoolean()
                        && reassessed.path("observedRegressions").isEmpty();
        }
        // A compatible original content probe is enough to reopen an existing
        // runtime candidate so the platform can bind the missing content gate.
        assessmentRecovered = assessmentRecovered || compatibleContentBaseline;
        boolean latestEquivalent = history.getLast().getId().equals(candidateId)
                || assessmentRecovered && history.stream()
                    .filter(row -> row.getRoundNumber() > candidate.getRoundNumber())
                    .allMatch(row -> row.getSha256().equals(candidate.getSha256())
                            && "REPEATED_CANDIDATE".equals(row.getErrorCode()));
        if (repair.getActiveCandidateId() != null || modelCalls.findByRepairIdOrderByAttemptNumberAsc(repair.getId()).stream()
                .anyMatch(call -> Set.of("QUEUED", "RUNNING", "CANCELLING", "RETRY_WAIT").contains(call.getStatus()))
                || !revalidationToolsChanged && !assessmentRecovered
                    && !Set.of("BLOCKED", "INTERRUPTED", "AWAITING_RUNTIME_VALIDATION").contains(repair.getStatus())
                || !latestEquivalent)
            throw conflict("REPAIR_REVALIDATION_NOT_READY");
        if (!originalInspection.path("sha256").asText().equals(repair.getBaselineArtifactSha256())
                || !candidateInspection.path("sha256").asText().equals(candidate.getSha256()))
            throw conflict("RUNTIME_INPUT_MISMATCH");
        if (!originalInspection.path("parseOk").asBoolean()
                || originalInspection.path("methodsTruncated").asBoolean()
                || originalInspection.path("importsTruncated").asBoolean()
                || !originalInspection.path("methodContracts").isObject()
                || originalInspection.path("methodContracts").isEmpty())
            throw conflict("REPAIR_CONTRACT_UNAVAILABLE");
        var baseline = current.frozen().path("baseline");
        var context = mapper.createObjectNode().put("schemaVersion", 1)
                .put("authority", "current_runtime_revalidation")
                .put("sourceId", repair.getSourceId()).put("baselineRunId", repair.getBaselineRunId())
                .put("baselineArtifactSha256", repair.getBaselineArtifactSha256())
                .put("profileSha256", baseline.path("profileSha256").asText())
                .put("runtimeSha256", selected.sha256()).put("executionPolicySha256", selected.policySha256())
                .put("inspectorSha256", inspector.sha256()).put("knowledgeSha256", knowledge.sha256())
                .put("createdAt", Instant.now().toString());
        context.set("executionPolicy", selected.snapshot());
        context.set("originalInspection", originalInspection.deepCopy());
        JsonNode previousContext = decode(repair.getRuntimeContextJson());
        if (previousContext != null) {
            var contexts = context.putArray("previousContexts");
            previousContext.path("previousContexts").forEach(value -> contexts.add(value.deepCopy()));
            ObjectNode previous = previousContext.deepCopy();
            previous.remove("previousContexts");
            contexts.add(previous);
        }
        if (!baseline.path("result").path("managedSourceEvidence").path("loaderValidation")
                .path("contentSnapshot").isObject()) {
            for (var observed : probes.list(repair.getSourceId())) {
                ObjectNode supplement = reports.contentBaseline(observed, current.frozen(), repair.getSourceId(),
                        repair.getBaselineArtifactSha256(), selected.sha256(), selected.policySha256());
                if (supplement != null) {
                    context.set("contentBaseline", supplement);
                    break;
                }
            }
        }
        repair.setRuntimeContextJson(context.toString());
        var previousResult = (ObjectNode) decode(candidate.getResultJson());
        var attempts = mapper.createArrayNode();
        if (previousResult != null) {
            previousResult.path("runtimeAttempts").forEach(value -> attempts.add(value.deepCopy()));
            previousResult.remove("runtimeAttempts");
            previousResult.put("candidateStatus", candidate.getStatus()).put("candidateErrorCode", candidate.getErrorCode())
                    .put("candidateFinishedAt", candidate.getFinishedAt() == null ? null : candidate.getFinishedAt().toString())
                    .put("candidateFailureSha256", candidate.getFailureSha256());
            attempts.add(previousResult);
        }
        var result = reports.build(summary(repair).frozen(), candidate.getSha256(), candidateInspection);
        reports.applySelectorBinding(result, decode(candidate.getSelectorEvidenceJson()));
        result.set("runtimeAttempts", attempts);
        result.put("runtimeJobId", UUID.randomUUID().toString())
                .put("runtimeRequested", result.path("staticValidationPassed").asBoolean());
        candidate.setResultJson(result.toString());
        candidate.setFailureSha256(null);
        candidate.setErrorCode(null);
        candidate.setFinishedAt(null);
        if (!result.path("staticValidationPassed").asBoolean()) {
            terminal(candidate, "REJECTED", "REVALIDATION_STATIC_CHECK_FAILED");
            repair.setStatus("BLOCKED");
            repair.setStopCode("REVALIDATION_STATIC_CHECK_FAILED");
        } else {
            candidate.setStatus("RUNTIME_QUEUED");
            repair.setStatus("VALIDATING_RUNTIME");
            repair.setStopCode(null);
            repair.setActiveCandidateId(candidateId);
        }
        return candidate;
    }

    @Transactional
    public void finishRuntime(String repairId, String candidateId, JsonNode evidence, String errorCode) {
        finishRuntime(repairId, candidateId, evidence, errorCode, null);
    }

    @Transactional
    public void finishRuntime(String repairId, String candidateId, JsonNode evidence, String errorCode,
                              JsonNode diagnostic) {
        var repair = lock(repairId);
        var candidate = requireCandidate(candidateId);
        if (!candidateId.equals(repair.getActiveCandidateId()) || !ACTIVE.contains(candidate.getStatus())) return;
        if (errorCode != null && diagnostic != null) {
            var result = (ObjectNode) decode(candidate.getResultJson());
            var baseline = RepairReport.runtimeInputs(summary(repair).frozen());
            result.putObject("managedExecutionFailure").put("candidateId", candidateId)
                    .put("jobId", result.path("runtimeJobId").asText(candidateId))
                    .put("inputSourceSha256", candidate.getSha256())
                    .put("inputProfileSha256", baseline.path("profileSha256").asText())
                    .put("bundleSha256", baseline.path("runtimeSha256").asText())
                    .put("executionPolicySha256", baseline.path("executionPolicySha256").asText())
                    .put("code", errorCode).set("diagnostic", diagnostic.deepCopy());
            candidate.setResultJson(result.toString());
        }
        if (candidate.getStatus().equals("CANCELLING") && !"MANAGED_CLEANUP_UNCONFIRMED".equals(errorCode)) {
            terminal(candidate, "CANCELLED", "USER_CANCELLED");
            repair.setStatus("CANCELLED");
            repair.setStopCode("USER_CANCELLED");
        } else if (errorCode != null) {
            boolean interrupted = Set.of("EXECUTION_INTERRUPTED", "SERVICE_STOPPED").contains(errorCode);
            terminal(candidate, interrupted ? "INTERRUPTED" : "FAILED", errorCode);
            repair.setStatus(interrupted ? "INTERRUPTED" : "BLOCKED");
            repair.setStopCode(errorCode);
        } else if (evidence == null
                || !evidence.path("inputSourceSha256").asText().equals(candidate.getSha256())
                || !evidence.path("bundleSha256").asText().equals(
                        RepairReport.runtimeInputs(summary(repair).frozen()).path("runtimeSha256").asText())
                || !decode(candidate.getResultJson()).path("artifactSha256").asText().equals(candidate.getSha256())) {
            terminal(candidate, "FAILED", "RUNTIME_INPUT_MISMATCH");
            repair.setStatus("BLOCKED");
            repair.setStopCode("RUNTIME_INPUT_MISMATCH");
        } else {
            ObjectNode result = reports.runtime(summary(repair).frozen(), decode(candidate.getResultJson()), evidence);
            candidate.setResultJson(result.toString());
            boolean loaderMode = Set.of("managed_loader_candidate", "managed_origin_candidate")
                    .contains(decode(repair.getFrozenJson()).path("policy").path("scope").asText());
            boolean successful = result.path("runtimeAcceptance").path("successful").asBoolean()
                    && result.path("repairSuccessful").asBoolean();
            boolean repeated = false;
            boolean validationPaused = result.path("runtimeValidationPaused").asBoolean();
            if (!successful && !validationPaused) {
                candidate.setFailureSha256(Hashes.sha256(result.path(loaderMode
                        ? "runtimeFailureSignature" : "runtimeObservation").toString()));
                repeated = candidates.findByRepairIdOrderByRoundNumberAsc(repairId).stream()
                        .filter(previous -> !previous.getId().equals(candidateId)
                                && candidate.getFailureSha256().equals(previous.getFailureSha256()))
                        .map(previous -> decode(previous.getResultJson()))
                        .anyMatch(previous -> result.path("inspectorSha256").equals(previous.path("inspectorSha256"))
                                && result.path("runtimeEvidence").path("bundleSha256")
                                    .equals(previous.path("runtimeEvidence").path("bundleSha256")));
            }
            if (!summary(repair).freshness().equals("current")) {
                terminal(candidate, "BLOCKED", "REPAIR_INPUTS_CHANGED");
                repair.setStatus("STOPPED");
                repair.setStopCode("REPAIR_INPUTS_CHANGED");
            } else if (validationPaused) {
                String pauseReason = "RUNTIME_HOST_CONFIGURATION_REQUIRED".equals(
                        result.path("runtimeValidationPauseReason").asText())
                        ? "RUNTIME_HOST_CONFIGURATION_REQUIRED" : "RUNTIME_NETWORK_UNAVAILABLE";
                candidate.setFailureSha256(null);
                terminal(candidate, "BLOCKED", pauseReason);
                repair.setStatus("BLOCKED");
                repair.setStopCode(pauseReason);
            } else if (successful) {
                terminal(candidate, "RUNTIME_OBSERVED", null);
                repair.setStatus("RUNTIME_OBSERVED");
                repair.setStopCode(null);
            } else if (result.path(loaderMode ? "runtimeProgressObserved" : "targetObservedRecovered").asBoolean()
                    && result.path("observedRegressions").isEmpty()) {
                terminal(candidate, "RUNTIME_OBSERVED", null);
                repair.setStatus(loaderMode && repeated || repair.getUsedRounds() >= roundLimit(repair) ? "STOPPED" : "AWAITING_PROPOSAL");
                repair.setStopCode(loaderMode && repeated ? "REPEATED_FAILURE"
                        : repair.getUsedRounds() >= roundLimit(repair) ? "ROUND_LIMIT" : "REQUIRED_RUNTIME_NODES_INCOMPLETE");
            } else {
                terminal(candidate, "REJECTED", "RUNTIME_TARGET_UNRESOLVED");
                repair.setStatus(repeated || repair.getUsedRounds() >= roundLimit(repair) ? "STOPPED" : "AWAITING_PROPOSAL");
                repair.setStopCode(repeated ? "REPEATED_FAILURE" : repair.getUsedRounds() >= roundLimit(repair) ? "ROUND_LIMIT" : null);
            }
        }
        repair.setActiveCandidateId(null);
    }

    @Transactional
    public RepairSummary reconcileRuntimeSuccess(String candidateId, String expectedSha, String expectedFaultSha) {
        var candidate = requireCandidate(candidateId);
        var repair = lock(candidate.getRepairId());
        sources.requireMaintenanceAllowed(repair.getSourceId());
        if (!candidate.getSha256().equals(expectedSha)) throw conflict("REPAIR_CANDIDATE_CHANGED");
        if (!repair.getFaultSha256().equals(expectedFaultSha)) throw conflict("REPAIR_FAULT_CHANGED");
        var current = summary(repair);
        JsonNode result = decode(candidate.getResultJson());
        if (!"current".equals(current.freshness()) || repair.getActiveCandidateId() != null
                || !"RUNTIME_OBSERVED".equals(repair.getStatus()) || !"RUNTIME_OBSERVED".equals(candidate.getStatus())
                || !candidates.findByRepairIdOrderByRoundNumberAsc(repair.getId()).getLast().getId().equals(candidateId)
                || result == null || result.path("runtimeAttempts").isEmpty()
                || !result.path("repairSuccessful").asBoolean()
                || !result.path("runtimeAcceptance").path("successful").asBoolean()
                || !result.path("runtimeEvidence").path("cleanup").path("confirmed").asBoolean()
                || !result.path("runtimeEvidence").path("cleanup").path("containerAbsent").asBoolean())
            throw conflict("REPAIR_RUNTIME_SUCCESS_NOT_READY");
        if (probes.completeRevalidatedMaintenance(repair.getBaselineRunId(), repair.getSourceId(),
                repair.getBaselineArtifactSha256(), repair.getId())
                && "STOPPED".equals(repair.getAutomaticStatus())) {
            repair.setAutomaticStatus("SUCCEEDED");
            repair.setAutomaticStopCode(null);
        }
        return summary(repair);
    }

    @Transactional(readOnly = true)
    public List<RepairCandidateSummary> unfinishedRuntimeCandidates() {
        return repairs.findByStatusIn(Set.of("VALIDATING_RUNTIME", "CANCELLING")).stream()
                .filter(repair -> repair.getActiveCandidateId() != null)
                .map(repair -> requireCandidate(repair.getActiveCandidateId()))
                .filter(candidate -> decode(candidate.getResultJson()) != null
                        && decode(candidate.getResultJson()).path("runtimeRequested").asBoolean())
                .map(this::candidateSummary).toList();
    }

    @Transactional
    public void finish(String repairId, String candidateId, ObjectNode result, String errorCode) {
        var repair = lock(repairId);
        var candidate = requireCandidate(candidateId);
        if (!candidateId.equals(repair.getActiveCandidateId()) || !ACTIVE.contains(candidate.getStatus())) return;
        if (candidate.getStatus().equals("CANCELLING") && !"INSPECTOR_CLEANUP_FAILED".equals(errorCode)) {
            terminal(candidate, "CANCELLED", "USER_CANCELLED");
            repair.setStatus("CANCELLED");
            repair.setStopCode("USER_CANCELLED");
        } else if (errorCode != null) {
            boolean interrupted = Set.of("EXECUTION_INTERRUPTED", "SERVICE_STOPPED").contains(errorCode);
            terminal(candidate, interrupted ? "INTERRUPTED" : errorCode.equals("QUEUE_FULL") ? "REJECTED" : "FAILED", errorCode);
            repair.setStatus(interrupted ? "INTERRUPTED" : "BLOCKED");
            repair.setStopCode(errorCode);
        } else {
            candidate.setResultJson(result.toString());
            if (!summary(repair).freshness().equals("current")) {
                terminal(candidate, "BLOCKED", "REPAIR_INPUTS_CHANGED");
                repair.setStatus("STOPPED");
                repair.setStopCode("REPAIR_INPUTS_CHANGED");
            } else if (result.path("staticValidationPassed").asBoolean()) {
                terminal(candidate, "STATIC_VALIDATED", null);
                repair.setStatus("AWAITING_RUNTIME_VALIDATION");
            } else {
                String code = result.path("contractViolations").isEmpty() ? "STATIC_CHECK_FAILED" : "CONTRACT_REGRESSION";
                terminal(candidate, "REJECTED", code);
                String failure = mapper.createObjectNode().set("issues", result.path("inspection").path("issues")).toString()
                        + result.path("contractViolations");
                candidate.setFailureSha256(Hashes.sha256(failure));
                boolean repeated = candidates.findByRepairIdOrderByRoundNumberAsc(repairId).stream()
                        .anyMatch(previous -> !previous.getId().equals(candidateId)
                                && candidate.getFailureSha256().equals(previous.getFailureSha256()));
                repair.setStatus(repeated || repair.getUsedRounds() >= roundLimit(repair) ? "STOPPED" : "AWAITING_PROPOSAL");
                repair.setStopCode(repeated ? "REPEATED_FAILURE" : repair.getUsedRounds() >= roundLimit(repair) ? "ROUND_LIMIT" : null);
            }
        }
        repair.setActiveCandidateId(null);
    }

    @Transactional
    public RepairSummary cancel(String id) {
        var repair = lock(id);
        if (Set.of("STOPPED", "CANCELLED", "INTERRUPTED", "BLOCKED").contains(repair.getStatus())) return summary(repair);
        if (repair.getActiveCandidateId() != null) {
            var candidate = requireCandidate(repair.getActiveCandidateId());
            if (Set.of("QUEUED", "RUNTIME_QUEUED").contains(candidate.getStatus())) {
                terminal(candidate, "CANCELLED", "USER_CANCELLED");
                repair.setActiveCandidateId(null);
            } else if (Set.of("RUNNING", "RUNTIME_RUNNING", "CANCELLING").contains(candidate.getStatus())) {
                candidate.setStatus("CANCELLING");
                repair.setStatus("CANCELLING");
                return summary(repair);
            }
        }
        repair.setStatus("CANCELLED");
        repair.setStopCode("USER_CANCELLED");
        return summary(repair);
    }

    @Transactional
    public RepairSummary updateRoundLimit(String id, UpdateRepairRoundLimitRequest input) {
        modelBudgets.lock().orElseThrow();
        var repair = lock(id);
        sources.requireMaintenanceAllowed(repair.getSourceId());
        if (!repair.getFaultSha256().equals(input.expectedFaultSha256())) throw conflict("REPAIR_FAULT_CHANGED");
        int previous = roundLimit(repair);
        if (previous != input.expectedMaxRounds()) throw conflict("REPAIR_ROUND_LIMIT_CHANGED");
        if (input.maxRounds() < 1 || input.maxRounds() < repair.getUsedRounds())
            throw conflict("REPAIR_ROUND_LIMIT_BELOW_USED");
        if (repair.getActiveCandidateId() != null || modelCalls.findByRepairIdOrderByAttemptNumberAsc(id).stream()
                .anyMatch(call -> Set.of("QUEUED", "RUNNING", "CANCELLING", "RETRY_WAIT").contains(call.getStatus())))
            throw conflict("REPAIR_ROUND_LIMIT_BUSY");
        repair.setMaxRounds(input.maxRounds());
        if ("AWAITING_PROPOSAL".equals(repair.getStatus()) && repair.getUsedRounds() >= input.maxRounds()) {
            repair.setStatus("STOPPED");
            repair.setStopCode("ROUND_LIMIT");
        }
        return summary(repair);
    }

    @Transactional
    public RepairSummary resume(String id, String expectedFaultSha) {
        var repair = lock(id);
        if (!repair.getFaultSha256().equals(expectedFaultSha)) throw conflict("REPAIR_FAULT_CHANGED");
        var history = candidates.findByRepairIdOrderByRoundNumberAsc(id);
        boolean networkPending = !history.isEmpty()
                && reports.networkRetryable(summary(repair).frozen(), decode(history.getLast().getResultJson()));
        boolean evidencePending = "STOPPED".equals(repair.getStatus())
                && "REPEATED_FAILURE".equals(repair.getStopCode()) && hasNewReverseEvidence(id);
        boolean roundsAvailable = "STOPPED".equals(repair.getStatus()) && repair.getMaxRounds() != null
                && repair.getUsedRounds() < roundLimit(repair)
                && Set.of("ROUND_LIMIT", "REPEATED_FAILURE").contains(repair.getStopCode() == null ? "" : repair.getStopCode());
        if (!Set.of("BLOCKED", "INTERRUPTED", "CANCELLED").contains(repair.getStatus())
                && !evidencePending && !roundsAvailable
                && !(repair.getStatus().equals("STOPPED") && networkPending
                    && Set.of("REPEATED_FAILURE", "ROUND_LIMIT").contains(repair.getStopCode())))
            throw conflict("REPAIR_NOT_RESUMABLE");
        if (!summary(repair).freshness().equals("current")) throw conflict("REPAIR_INPUTS_CHANGED");
        boolean runtimePending = networkPending || "RUNTIME_HOST_CONFIGURATION_REQUIRED".equals(repair.getStopCode())
                || !history.isEmpty() && history.getLast().getStatus().equals("STATIC_VALIDATED");
        boolean exhausted = repair.getUsedRounds() >= roundLimit(repair) && !runtimePending;
        repair.setStatus(runtimePending ? "AWAITING_RUNTIME_VALIDATION" : exhausted ? "STOPPED" : "AWAITING_PROPOSAL");
        repair.setStopCode(exhausted ? "ROUND_LIMIT" : null);
        return summary(repair);
    }

    @Transactional(readOnly = true)
    public boolean hasNewReverseEvidence(String id) {
        var repair = get(id);
        var history = candidates.findByRepairIdOrderByRoundNumberAsc(id);
        if (!"current".equals(repair.freshness()) || repair.usedRounds() >= repair.maxRounds()
                || history.isEmpty() || history.getLast().getModelCallId() == null) return false;
        var candidate = history.getLast();
        var call = modelCalls.findById(candidate.getModelCallId()).orElse(null);
        return call != null && id.equals(call.getRepairId()) && "PROPOSED".equals(call.getStatus())
                && "SUBMITTED".equals(call.getSubmissionStatus())
                && ReverseAnalysis.newMaterial(repair, ReverseAnalysis.modelReport(mapper, call.getRequestJson()));
    }

    @Transactional(readOnly = true)
    public ObjectNode evidenceContinuation(String id) {
        var repair = get(id);
        var material = RepairEvidenceMaterial.current(mapper, repair, candidates(id))
                .put("usedRounds", repair.usedRounds()).put("maxRounds", repair.maxRounds());
        if (!material.path("available").asBoolean()) return material.put("reason", "NEW_RESPONSE_STRUCTURE_UNAVAILABLE");
        if (!"STOPPED".equals(repair.status()) || repair.usedRounds() < repair.maxRounds()
                || !Set.of("REPEATED_FAILURE", "ROUND_LIMIT").contains(repair.stopCode() == null ? "" : repair.stopCode()))
            return material.put("available", false).put("reason", "REPAIR_CONTINUATION_NOT_READY");
        var seen = new HashSet<String>();
        var calls = modelCalls.findByRepairIdOrderByAttemptNumberAsc(id);
        if (calls.isEmpty()) return material.put("available", false).put("reason", "MODEL_REQUEST_EVIDENCE_UNAVAILABLE");
        for (var call : calls) {
            JsonNode data = RepairEvidenceMaterial.requestData(mapper, call.getRequestJson());
            if (data.isMissingNode()) return material.put("available", false).put("reason", "MODEL_REQUEST_EVIDENCE_UNAVAILABLE");
            seen.addAll(RepairEvidenceMaterial.signatures(mapper, data.path("resourceEvidence")));
            seen.addAll(RepairEvidenceMaterial.originSignatures(mapper, repair, data.path("reverseAnalysis")));
            seen.addAll(RepairEvidenceMaterial.playerSignatures(mapper, data.path("loaderFailures")));
            for (JsonNode previous : data.path("previousCandidates")) {
                seen.addAll(RepairEvidenceMaterial.signatures(mapper, previous.path("resourceEvidence")));
                seen.addAll(RepairEvidenceMaterial.playerSignatures(mapper, previous.path("unresolvedNodes")));
            }
        }
        boolean novel = material.path("structureSignatures").valueStream().anyMatch(value -> !seen.contains(value.asText()));
        boolean granted = repair.frozen().path("evidenceContinuations").valueStream().anyMatch(grant ->
                grant.path("evidenceSha256").equals(material.path("evidenceSha256")));
        if (!novel || granted) return material.put("available", false).put("reason", "RESPONSE_STRUCTURE_ALREADY_USED");
        return material.put("additionalRounds", 1).put("expectedFaultSha256", repair.faultSha256());
    }

    @Transactional
    public RepairSummary continueWithEvidence(String id, ContinueMaintenanceEvidenceRequest input) {
        var row = lock(id);
        sources.requireMaintenanceAllowed(row.getSourceId());
        if (!row.getFaultSha256().equals(input.expectedFaultSha256())) throw conflict("REPAIR_FAULT_CHANGED");
        var grants = row.getEvidenceContinuationsJson() == null ? mapper.createArrayNode()
                : (com.fasterxml.jackson.databind.node.ArrayNode) decode(row.getEvidenceContinuationsJson());
        for (JsonNode grant : grants) if (input.requestKey().equals(grant.path("requestKey").asText())) {
            if (!input.expectedEvidenceSha256().equals(grant.path("evidenceSha256").asText())
                    || !input.expectedCandidateSha256().equals(grant.path("candidateSha256").asText()))
                throw conflict("EVIDENCE_CONTINUATION_KEY_CONFLICT");
            return summary(row);
        }
        var offer = evidenceContinuation(id);
        if (!offer.path("available").asBoolean()) throw conflict(offer.path("reason").asText());
        if (!input.expectedCandidateSha256().equals(offer.path("candidateSha256").asText())
                || !input.expectedEvidenceSha256().equals(offer.path("evidenceSha256").asText()))
            throw conflict("REPAIR_EVIDENCE_CHANGED");
        int nextLimit;
        try { nextLimit = Math.addExact(roundLimit(row), 1); }
        catch (ArithmeticException error) { throw conflict("REPAIR_ROUND_LIMIT_OVERFLOW"); }
        var grant = grants.addObject().put("requestKey", input.requestKey()).put("authority", "explicit_new_evidence_round")
                .put("candidateId", offer.path("candidateId").asText())
                .put("candidateSha256", input.expectedCandidateSha256()).put("evidenceSha256", input.expectedEvidenceSha256())
                .put("afterUsedRounds", row.getUsedRounds()).put("additionalRounds", 1).put("grantedAt", Instant.now().toString());
        grant.set("structureSignatures", offer.path("structureSignatures"));
        if ("observed_player_output_contract".equals(offer.path("materialKind").asText())) {
            grant.put("materialKind", "observed_player_output_contract").put("targetNode", offer.path("targetNode").asText());
            grant.set("playerContract", offer.path("playerContract"));
        }
        row.setEvidenceContinuationsJson(grants.toString());
        row.setMaxRounds(nextLimit);
        row.setStatus("AWAITING_PROPOSAL");
        row.setStopCode(null);
        return summary(row);
    }

    @Transactional
    public void interruptUnfinished() {
        for (var found : repairs.findByStatusIn(Set.of("VALIDATING", "VALIDATING_RUNTIME", "CANCELLING"))) {
            var repair = lock(found.getId());
            if (repair.getActiveCandidateId() != null) terminal(requireCandidate(repair.getActiveCandidateId()),
                    "INTERRUPTED", "SERVICE_RESTARTED");
            repair.setActiveCandidateId(null);
            repair.setStatus("INTERRUPTED");
            repair.setStopCode("SERVICE_RESTARTED");
        }
    }

    @Transactional(readOnly = true)
    public RepairSummary get(String id) { return summary(require(id)); }

    @Transactional(readOnly = true)
    public List<RepairSummary> list(String sourceId) {
        sources.requireSource(sourceId);
        return repairs.findTop20BySourceIdOrderByCreatedAtDesc(sourceId).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public List<RepairCandidateSummary> candidates(String repairId) {
        require(repairId);
        return candidates.findByRepairIdOrderByRoundNumberAsc(repairId).stream().map(this::candidateSummary).toList();
    }

    @Transactional(readOnly = true)
    public RepairCandidateSummary candidate(String id) { return candidateSummary(requireCandidate(id)); }

    @Transactional(readOnly = true)
    public String content(String id) { return requireCandidate(id).getBody(); }

    @Transactional
    public ObjectNode attachReverseAnalysis(String id, String faultSha, ObjectNode report) {
        var repair = lock(id);
        var current = summary(repair);
        ReverseAnalysis.requireCurrent(current);
        if (!faultSha.equals(current.faultSha256()) || report == null
                || !id.equals(report.path("repairId").asText())
                || !current.baselineRunId().equals(report.path("runId").asText())
                || !current.baselineArtifactSha256().equals(report.path("artifactSha256").asText())
                || !report.path("cleanupConfirmed").asBoolean()
                || report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144)
            throw conflict("REVERSE_RECEIPT_INVALID");
        ObjectNode stored = report.deepCopy();
        stored.remove("previousReports");
        if (current.frozen().path("reverseAnalysis") instanceof ObjectNode previous) {
            if (previous.equals(report)) return previous;
            String key = report.path("analysisRequestSha256").asText();
            ObjectNode cached = key.isEmpty() ? null : ReverseAnalysis.cachedReport(previous, key, false);
            if (cached != null) return cached;
            var history = stored.putArray("previousReports");
            previous.path("previousReports").forEach(item -> history.add(item.deepCopy()));
            ObjectNode prior = previous.deepCopy();
            prior.remove("previousReports");
            history.add(prior);
        }
        repair.setReverseAnalysisJson(stored.toString());
        return stored;
    }

    private boolean runtimeFault(JsonNode baseline) {
        JsonNode observed = baseline.path("result").path("managedSourceEvidence");
        return baseline.path("executionMode").asText().equals("managed_source")
                && observed.path("authority").asText().equals("managed_source_host_observed")
                && observed.path("executionKind").asText().equals("t4_business_sample")
                && observed.path("inputSourceSha256").equals(baseline.path("artifactSha256"))
                && observed.path("bundleSha256").equals(baseline.path("runtimeSha256"))
                && observed.path("untrustedRuntimeSummary").path("status").asText().equals("home_empty")
                && observed.path("untrustedRuntimeSummary").path("homeItems").isIntegralNumber()
                && observed.path("untrustedRuntimeSummary").path("homeItems").asLong() == 0
                && !RepairReport.egressUnresolved(observed)
                && observed.path("cleanup").path("confirmed").asBoolean()
                && observed.path("cleanup").path("containerAbsent").asBoolean();
    }

    private List<String> loaderFaultNodes(JsonNode baseline) {
        JsonNode evidence = baseline.path("result").path("managedSourceEvidence");
        JsonNode loader = evidence.path("loaderValidation");
        if (!baseline.path("executionMode").asText().equals("managed_source")
                || !loader.path("projection").asText().equals(ManagedLoaderReceipt.PROJECTION)
                || !loader.path("inputSourceSha256").equals(baseline.path("artifactSha256"))
                || !loader.path("inputProfileSha256").equals(baseline.path("profileSha256"))
                || !evidence.path("cleanup").path("confirmed").asBoolean()
                || !evidence.path("cleanup").path("containerAbsent").asBoolean()) return List.of();
        Set<String> required = new HashSet<>();
        baseline.path("profile").path("requiredNodes").forEach(node -> required.add(node.asText()));
        return loader.path("nodes").valueStream()
                .filter(node -> required.contains(node.path("id").asText())
                        && RepairReport.repairableLoaderNode(node))
                .map(node -> node.path("id").asText()).sorted().toList();
    }

    private RepairSummary summary(RepairSession repair) {
        var baseline = probes.get(repair.getBaselineRunId());
        var stale = new ArrayList<>(baseline.staleInputs());
        JsonNode runtimeContext = decode(repair.getRuntimeContextJson());
        if (runtimeContext != null) {
            var selected = managed.forSource(repair.getSourceId());
            stale.removeAll(Set.of("runtime", "execution_policy"));
            if (!runtimeContext.path("runtimeSha256").asText().equals(selected.sha256())) stale.add("runtime");
            if (!runtimeContext.path("executionPolicySha256").asText().equals(selected.policySha256())) stale.add("execution_policy");
        }
        String inspectedBy = runtimeContext == null ? baseline.inspectorSha256()
                : runtimeContext.path("inspectorSha256").asText();
        if (!inspectedBy.equals(inspector.sha256())) stale.add("inspector");
        if (!baseline.knowledgeSha256().equals(knowledge.sha256())) stale.add("knowledge");
        JsonNode frozen = decode(repair.getFrozenJson());
        if (runtimeContext != null) ((ObjectNode) frozen).set("runtimeValidationContext", runtimeContext);
        if (repair.getReverseAnalysisJson() != null)
            ((ObjectNode) frozen).set("reverseAnalysis", decode(repair.getReverseAnalysisJson()));
        if (repair.getEvidenceContinuationsJson() != null)
            ((ObjectNode) frozen).set("evidenceContinuations", decode(repair.getEvidenceContinuationsJson()));
        if (repair.getMaxRounds() != null) ((ObjectNode) frozen).putObject("roundLimit")
                .put("authority", "administrator_configured").put("maxRounds", repair.getMaxRounds());
        if (!frozen.path("nodeCatalogSha256").asText().equals(knowledge.nodeCatalogSha256())) stale.add("node_catalog");
        String freshness = stale.isEmpty() ? "current"
                : baseline.freshness().equals("current") ? "repair_tools_changed" : baseline.freshness();
        return new RepairSummary(repair.getId(), repair.getSourceId(), repair.getBaselineRunId(),
                repair.getBaselineArtifactSha256(), repair.getFaultSha256(), repair.getStatus(), repair.getStopCode(),
                repair.getUsedRounds(), roundLimit(repair), repair.getActiveCandidateId(), repair.getCreatedAt(),
                frozen, freshness, List.copyOf(stale));
    }

    private int roundLimit(RepairSession repair) {
        if (repair.getMaxRounds() != null) return repair.getMaxRounds();
        int initial = decode(repair.getFrozenJson()).path("policy").path("maxRounds").asInt(3);
        return initial + (repair.getEvidenceContinuationsJson() == null ? 0 : decode(repair.getEvidenceContinuationsJson()).size());
    }

    private RepairCandidateSummary candidateSummary(RepairCandidate candidate) {
        return new RepairCandidateSummary(candidate.getId(), candidate.getRepairId(), candidate.getRoundNumber(),
                candidate.getSha256(), candidate.getProposalSha256(), candidate.getStatus(), candidate.getErrorCode(),
                candidate.getCreatedAt(), candidate.getFinishedAt(), decode(candidate.getProposalJson()), decode(candidate.getResultJson()),
                candidate.getModelCallId(), decode(candidate.getSelectorEvidenceJson()), candidate.getSourceImportId());
    }
    private void terminal(RepairCandidate candidate, String state, String code) {
        candidate.setStatus(state);
        candidate.setErrorCode(code);
        candidate.setFinishedAt(Instant.now());
    }
    private RepairSession require(String id) {
        return repairs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REPAIR_NOT_FOUND"));
    }
    private RepairSession lock(String id) {
        return repairs.findForUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REPAIR_NOT_FOUND"));
    }
    private RepairCandidate requireCandidate(String id) {
        return candidates.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "REPAIR_CANDIDATE_NOT_FOUND"));
    }
    private JsonNode decode(String value) {
        if (value == null) return null;
        try { return mapper.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored repair JSON is invalid", e); }
    }
    private ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
}
