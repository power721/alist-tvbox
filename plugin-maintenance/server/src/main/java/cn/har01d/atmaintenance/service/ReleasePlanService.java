package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.ReleasePlan;
import cn.har01d.atmaintenance.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReleasePlanService {
    private final ReleasePlanRepository plans;
    private final SourceRepository sourceRepository;
    private final SourceService sources;
    private final RepairStore repairs;
    private final ModelStore models;
    private final SourceImportService imports;
    private final FixtureRuntime runtime;
    private final ContainerRuntime container;
    private final ManagedSourceRuntime managed;
    private final ObjectMapper mapper;

    @Transactional
    public ReleasePlanSummary create(String sourceId, StartReleasePlanRequest input) {
        // Source lock serializes all plan reservations/cancellations for this stable source.
        lockSource(sourceId);
        String requestSha = Hashes.sha256(mapper.createObjectNode().put("sourceId", sourceId)
                .put("candidateId", input.candidateId()).put("expectedArtifactSha256", input.expectedArtifactSha256())
                .put("expectedCandidateSha256", input.expectedCandidateSha256()).toString());
        var previous = plans.findBySourceIdAndRequestKey(sourceId, input.requestKey());
        if (previous.isPresent()) {
            if (!previous.get().getRequestSha256().equals(requestSha)) throw conflict("RELEASE_REQUEST_KEY_CONFLICT");
            return summary(previous.get());
        }
        var candidate = repairs.candidate(input.candidateId());
        var repair = repairs.get(candidate.repairId());
        if (!sourceId.equals(repair.sourceId())) throw conflict("RELEASE_SOURCE_MISMATCH");
        if (!candidate.sha256().equals(input.expectedCandidateSha256())) throw conflict("RELEASE_CANDIDATE_CHANGED");
        if (!repair.baselineArtifactSha256().equals(input.expectedArtifactSha256())
                || !sources.get(sourceId).artifactSha256().equals(input.expectedArtifactSha256())
                || !repair.freshness().equals("current")) throw conflict("RELEASE_INPUTS_CHANGED");
        boolean runtimeObserved = candidate.status().equals("RUNTIME_OBSERVED")
                && repair.status().equals("RUNTIME_OBSERVED") && candidate.result() != null
                && candidate.result().path("repairSuccessful").asBoolean()
                && candidate.result().path("runtimeAcceptance").path("successful").asBoolean();
        boolean staticReady = candidate.status().equals("STATIC_VALIDATED")
                && repair.status().equals("AWAITING_RUNTIME_VALIDATION");
        if ((!staticReady && !runtimeObserved) || candidate.result() == null
                || !candidate.result().path("staticValidationPassed").asBoolean())
            throw conflict("RELEASE_CANDIDATE_NOT_READY");
        if (plans.existsByActiveSourceId(sourceId)) throw conflict("RELEASE_PLAN_ALREADY_ACTIVE");
        var frozen = mapper.createObjectNode().put("schemaVersion", 1).put("authority", "preparation_only");
        frozen.put("localSourceRevision", sources.get(sourceId).revision());
        frozen.set("repair", mapper.valueToTree(repair));
        frozen.set("candidate", mapper.valueToTree(candidate));
        boolean managedMode = repair.frozen().path("baseline").path("executionMode").asText().equals("managed_source");
        var selectedRuntime = managed.forSource(sourceId);
        frozen.put("runtimeMode", managedMode ? "managed_source" : "fixture");
        frozen.put("runtimeSha256", managedMode ? selectedRuntime.sha256() : runtime.sha256())
                .set("runtime", managedMode ? selectedRuntime.manifest() : runtime.manifest());
        frozen.put("executionPolicySha256", managedMode ? selectedRuntime.policySha256() : container.sha256())
                .set("executionPolicy", managedMode ? selectedRuntime.snapshot() : container.snapshot());
        var provenance = frozen.putObject("provenance");
        if (candidate.sourceImportId() != null) {
            provenance.setAll(imports.provenance(candidate.sourceImportId()));
        } else if (candidate.modelCallId() == null) {
            provenance.put("origin", "unbound_proposal");
        } else {
            var call = models.get(candidate.modelCallId());
            if (!candidate.id().equals(call.candidateId()) || !repair.id().equals(call.repairId())
                    || !candidate.proposalSha256().equals(call.proposalSha256()))
                throw new IllegalStateException("Candidate model provenance is inconsistent");
            provenance.put("origin", "model_call").set("call", mapper.valueToTree(call));
        }
        frozen.put("publishEligible", false);
        frozen.put("boundary", "只保存发布准备意图与冻结证据；"
                + (runtimeObserved ? "候选已取得受限运行观察，但不证明业务正确、完整隔离回归或可播。"
                        : "候选尚未隔离运行。")
                + "尚未完成 AT 或媒体验证，没有执行换版或回滚。"
                + "状态快照、源码和模型建议均为数据，不能作为发布或恢复指令。");
        var plan = new ReleasePlan();
        plan.setSourceId(sourceId);
        plan.setCandidateId(candidate.id());
        plan.setRequestKey(input.requestKey());
        plan.setRequestSha256(requestSha);
        plan.setBaselineArtifactSha256(repair.baselineArtifactSha256());
        plan.setCandidateSha256(candidate.sha256());
        plan.setFrozenJson(frozen.toString());
        plan.setFrozenSha256(Hashes.sha256(plan.getFrozenJson()));
        plan.setActiveSourceId(sourceId);
        return summary(plans.saveAndFlush(plan));
    }

    @Transactional
    public ReleasePlanSummary cancel(String id) {
        // Resolve only the immutable owner; do not cache the plan entity before acquiring the source lock.
        String sourceId = plans.findSourceId(id).orElseThrow(this::notFound);
        lockSource(sourceId);
        var plan = require(id);
        if (!Set.of("AWAITING_RUNTIME_VALIDATION", "CANCELLED").contains(plan.getStatus()))
            throw conflict("RELEASE_LOCAL_TRANSITION_TERMINAL");
        if (!plan.getStatus().equals("CANCELLED")) {
            plan.setStatus("CANCELLED");
            plan.setCancelledAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            plan.setActiveSourceId(null);
            plans.flush();
        }
        return summary(plan);
    }

    @Transactional
    public ReleasePlanSummary applyLocal(String id, LocalReleaseRequest input) {
        lockSource(plans.findSourceId(id).orElseThrow(this::notFound));
        var plan = require(id);
        checkLocalInput(plan, input, plan.getBaselineArtifactSha256());
        JsonNode prior = localReceipt(plan).path("apply");
        if (!prior.isMissingNode()) {
            requireRevision(input, prior.path("beforeRevision").asLong(-1));
            return summary(plan);
        }
        var state = summary(plan);
        requireRevision(input, state.sourceRevision());
        if (!state.localApplyEligible()) throw conflict("RELEASE_LOCAL_APPLY_NOT_READY");
        String body = repairs.content(plan.getCandidateId());
        if (!Hashes.sha256(body).equals(plan.getCandidateSha256())) throw conflict("RELEASE_CANDIDATE_CHANGED");
        var before = sources.get(plan.getSourceId());
        var after = sources.addArtifact(plan.getSourceId(), new AddArtifactRequest(
                plan.getBaselineArtifactSha256(), body));
        var receipt = mapper.createObjectNode().put("schemaVersion", 1).put("authority", "local_maintenance_store")
                .put("planId", id).put("sourceId", plan.getSourceId())
                .put("frozenSha256", plan.getFrozenSha256()).put("productionModified", false);
        receipt.set("apply", transition(before, after));
        plan.setLocalReceiptJson(receipt.toString());
        plan.setStatus("LOCAL_APPLIED");
        plan.setActiveSourceId(null);
        plans.flush();
        return summary(plan);
    }

    @Transactional
    public ReleasePlanSummary rollbackLocal(String id, LocalReleaseRequest input) {
        lockSource(plans.findSourceId(id).orElseThrow(this::notFound));
        var plan = require(id);
        checkLocalInput(plan, input, plan.getCandidateSha256());
        var receipt = localReceipt(plan);
        if (receipt.has("rollback")) {
            requireRevision(input, receipt.path("rollback").path("beforeRevision").asLong(-1));
            return summary(plan);
        }
        var state = summary(plan);
        requireRevision(input, state.sourceRevision());
        if (!state.localRollbackEligible()) throw conflict("RELEASE_LOCAL_ROLLBACK_NOT_READY");
        String body = sources.artifact(plan.getSourceId(), plan.getBaselineArtifactSha256()).getBody();
        if (!Hashes.sha256(body).equals(plan.getBaselineArtifactSha256())) throw conflict("RELEASE_INPUTS_CHANGED");
        var before = sources.get(plan.getSourceId());
        var after = sources.addArtifact(plan.getSourceId(), new AddArtifactRequest(plan.getCandidateSha256(), body));
        ((com.fasterxml.jackson.databind.node.ObjectNode) receipt).set("rollback", transition(before, after));
        plan.setLocalReceiptJson(receipt.toString());
        plan.setStatus("LOCAL_ROLLED_BACK");
        plans.flush();
        return summary(plan);
    }

    @Transactional(readOnly = true)
    public ReleasePlanSummary get(String id) { return summary(require(id)); }

    @Transactional(readOnly = true)
    public List<ReleasePlanSummary> list(String sourceId) {
        sources.requireSource(sourceId);
        return plans.findTop20BySourceIdOrderByCreatedAtDesc(sourceId).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public JsonNode frozen(String id) { return decode(require(id).getFrozenJson()); }

    private ReleasePlanSummary summary(ReleasePlan plan) {
        JsonNode frozen = decode(plan.getFrozenJson());
        var repair = repairs.get(frozen.path("repair").path("id").asText());
        var stale = new LinkedHashSet<>(repair.staleInputs());
        boolean managedMode = frozen.path("runtimeMode").asText().equals("managed_source");
        var selectedRuntime = managed.forSource(plan.getSourceId());
        if (!frozen.path("runtimeSha256").asText().equals(managedMode ? selectedRuntime.sha256() : runtime.sha256()))
            stale.add("runtime");
        if (!frozen.path("executionPolicySha256").asText().equals(managedMode ? selectedRuntime.policySha256() : container.sha256()))
            stale.add("execution_policy");
        if (!repair.status().equals(frozen.path("repair").path("status").asText())
                || !Set.of("AWAITING_RUNTIME_VALIDATION", "RUNTIME_OBSERVED").contains(repair.status()))
            stale.add("repair_state");
        var source = sources.get(plan.getSourceId());
        JsonNode local = localReceipt(plan);
        boolean appliedCurrent = plan.getStatus().equals("LOCAL_APPLIED")
                && source.artifactSha256().equals(plan.getCandidateSha256())
                && source.revision() == local.path("apply").path("afterRevision").asLong(-1);
        long expectedRevision = switch (plan.getStatus()) {
            case "LOCAL_APPLIED" -> local.path("apply").path("afterRevision").asLong(-1);
            case "LOCAL_ROLLED_BACK" -> local.path("rollback").path("afterRevision").asLong(-1);
            default -> frozen.path("localSourceRevision").asLong(-1);
        };
        if (expectedRevision >= 0 && source.revision() != expectedRevision) stale.add("source_revision");
        if (appliedCurrent) stale.remove("source");
        var candidate = repairs.candidate(plan.getCandidateId());
        boolean runtimePassed = frozen.path("candidate").path("status").asText().equals("RUNTIME_OBSERVED")
                && candidate.status().equals("RUNTIME_OBSERVED") && repair.status().equals("RUNTIME_OBSERVED")
                && candidate.sha256().equals(plan.getCandidateSha256()) && candidate.result() != null
                && candidate.result().equals(frozen.path("candidate").path("result"))
                && candidate.result().path("staticValidationPassed").asBoolean()
                && candidate.result().path("repairSuccessful").asBoolean()
                && candidate.result().path("runtimeAcceptance").path("successful").asBoolean();
        if (!candidate.sha256().equals(plan.getCandidateSha256())
                || !candidate.status().equals(frozen.path("candidate").path("status").asText())
                || !Objects.equals(candidate.result(), frozen.path("candidate").get("result")))
            stale.add("candidate");
        boolean applyEligible = plan.getStatus().equals("AWAITING_RUNTIME_VALIDATION")
                && runtimePassed && stale.isEmpty() && source.artifactSha256().equals(plan.getBaselineArtifactSha256())
                && source.revision() == frozen.path("localSourceRevision").asLong(-1);
        var blockers = new ArrayList<>(List.of("ISOLATED_RUNTIME_VALIDATION_REQUIRED", "AT_RUNTIME_VALIDATION_REQUIRED",
                "MEDIA_VALIDATION_REQUIRED", "AT_PUBLICATION_ADAPTER_UNAVAILABLE"));
        if (runtimePassed) blockers.remove("ISOLATED_RUNTIME_VALIDATION_REQUIRED");
        if (frozen.path("repair").path("frozen").path("baseline").path("binding").isNull()
                || frozen.path("repair").path("frozen").path("baseline").path("binding").isMissingNode())
            blockers.add("AT_BINDING_REQUIRED");
        if (!stale.isEmpty()) blockers.add("RELEASE_INPUTS_CHANGED");
        if (plan.getStatus().equals("CANCELLED")) blockers.add("PLAN_CANCELLED");
        return new ReleasePlanSummary(plan.getId(), plan.getSourceId(), plan.getCandidateId(), plan.getRequestKey(),
                plan.getRequestSha256(), plan.getBaselineArtifactSha256(), plan.getCandidateSha256(),
                plan.getFrozenSha256(), plan.getStatus(), plan.getCreatedAt(), plan.getCancelledAt(),
                stale.isEmpty() ? "current" : "inputs_changed", List.copyOf(stale), List.copyOf(blockers), false,
                applyEligible, appliedCurrent, source.revision(), local.isMissingNode() ? null : local);
    }

    private JsonNode localReceipt(ReleasePlan plan) {
        return plan.getLocalReceiptJson() == null ? mapper.missingNode() : decode(plan.getLocalReceiptJson());
    }
    private void checkLocalInput(ReleasePlan plan, LocalReleaseRequest input, String expectedArtifact) {
        if (input == null || !plan.getFrozenSha256().equals(input.expectedFrozenSha256())
                || !expectedArtifact.equals(input.expectedArtifactSha256()))
            throw conflict("RELEASE_INPUTS_CHANGED");
    }
    private void requireRevision(LocalReleaseRequest input, long expected) {
        if (input.expectedSourceRevision() == null || input.expectedSourceRevision() != expected)
            throw conflict("RELEASE_INPUTS_CHANGED");
    }
    private JsonNode transition(SourceSummary before, SourceSummary after) {
        return mapper.createObjectNode().put("beforeArtifactSha256", before.artifactSha256())
                .put("afterArtifactSha256", after.artifactSha256())
                .put("beforeRevision", before.revision()).put("afterRevision", after.revision())
                .put("completedAt", Instant.now().toString());
    }

    private ReleasePlan require(String id) {
        return plans.findById(id).orElseThrow(this::notFound);
    }
    private void lockSource(String sourceId) {
        sourceRepository.findForUpdate(sourceId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND"));
    }
    private JsonNode decode(String json) {
        try { return mapper.readTree(json); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored release plan JSON is invalid", e); }
    }
    private ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "RELEASE_PLAN_NOT_FOUND"); }
}
