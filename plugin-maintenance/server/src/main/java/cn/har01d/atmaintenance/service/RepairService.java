package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.RepairCandidate;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class RepairService {
    private final RepairStore store;
    private final StaticInspector inspector;
    private final RepairReport reports;
    private final ManagedSourceRuntime managed;
    private final SourceCredentialStore credentials;
    private final ProbeStore probes;
    private final ExtractionReplay extraction;
    private final SourceService sources;
    private final ManagedSourceSetup setup;
    private final ConcurrentMap<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().name("maintenance-repair").factory(),
            new ThreadPoolExecutor.AbortPolicy());

    @PostConstruct
    void recover() {
        for (var candidate : store.unfinishedRuntimeCandidates()) {
            var repair = store.get(candidate.repairId());
            try {
                managed.cancelAcceptance(runtimeJobId(candidate), RepairReport.runtimeInputs(repair.frozen()).path("executionPolicy"));
            } catch (IOException e) {
                store.finishRuntime(repair.id(), candidate.id(), null, "MANAGED_CLEANUP_UNCONFIRMED");
            }
        }
        store.interruptUnfinished();
    }

    public synchronized RepairCandidateSummary validateRuntime(String candidateId, String expectedSha) {
        var candidate = store.candidate(candidateId);
        var repair = store.get(candidate.repairId());
        requireCredentials(repair.sourceId());
        if (setup.hasVerifiedOrigin(repair))
            return revalidateRuntime(candidateId, expectedSha, repair.faultSha256());
        return enqueue(store.reserveRuntime(candidateId, expectedSha));
    }

    public synchronized RepairCandidateSummary retryAutomaticRuntime(String repairId) {
        requireCredentials(store.get(repairId).sourceId());
        var candidate = store.reserveAutomaticRuntimeRetry(repairId);
        return candidate == null ? null : enqueue(candidate);
    }

    public synchronized RepairCandidateSummary revalidateRuntime(String candidateId, String expectedSha, String expectedFaultSha) {
        var candidate = store.candidate(candidateId);
        var repair = store.get(candidate.repairId());
        if (!candidate.sha256().equals(expectedSha))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REPAIR_CANDIDATE_CHANGED");
        if (!repair.faultSha256().equals(expectedFaultSha))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REPAIR_FAULT_CHANGED");
        requireCredentials(repair.sourceId());
        try {
            var original = inspector.inspect(sources.artifact(repair.sourceId(), repair.baselineArtifactSha256()).getBody());
            var inspection = inspector.inspect(store.content(candidateId));
            if (setup.hasVerifiedOrigin(repair) && !store.candidates(repair.id()).getLast().id().equals(candidateId))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "REPAIR_REVALIDATION_NOT_READY");
            if (!setup.ensureVerifiedOrigin(repair))
                setup.ensure(repair.sourceId(), repair.baselineArtifactSha256());
            return enqueue(store.reserveRevalidation(candidateId, expectedSha, expectedFaultSha, original, inspection));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVALIDATION_INSPECTION_FAILED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVALIDATION_INTERRUPTED");
        }
    }

    private void requireCredentials(String sourceId) {
        String blocked = credentials.blockedReason(sourceId);
        if (blocked != null) throw new ResponseStatusException(HttpStatus.CONFLICT, blocked);
    }

    public synchronized RepairCandidateSummary propose(String repairId, RepairProposal proposal) {
        // The store commits budget and immutable bytes before the executor can inspect them.
        RepairCandidate candidate = store.reserve(repairId, proposal);
        return enqueue(candidate);
    }

    public synchronized RepairCandidateSummary proposeImported(String repairId, ImportedCandidateRequest input) {
        return enqueue(store.reserveImported(repairId, input));
    }

    public synchronized RepairCandidateSummary proposeSelector(String repairId, SelectorLearningRequest input) {
        var repair = store.get(repairId);
        if (input == null || input.replay() == null || !repair.baselineArtifactSha256()
                .equals(input.replay().expectedArtifactSha256()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SELECTOR_BASELINE_MISMATCH");
        var frozenReplay = repair.frozen().path("baseline").path("context")
                .path("auxiliaryEvidence").path("extractionReplay");
        var originalReplay = extraction.replay(input.replay());
        if (!frozenReplay.isObject() || !originalReplay.toString().equals(
                ((com.fasterxml.jackson.databind.node.ObjectNode) frozenReplay).deepCopy()
                        .without(java.util.List.of("runId", "sourceId")).toString()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SELECTOR_REPLAY_NOT_FROZEN");
        var rule = extraction.learn(input).getFirst();
        String previous = ExtractionReplay.expression(input.oldSelector());
        String next = ExtractionReplay.expression(rule.fields().get(input.field()).selector());
        String selectorType = ExtractionReplay.xpath(input.oldSelector()) ? "xpath" : "css";
        String source = sources.artifact(repair.sourceId(), repair.baselineArtifactSha256()).getBody();
        var edits = new java.util.ArrayList<RepairEdit>();
        for (String quote : java.util.List.of("\"", "'")) {
            String old = quote + previous + quote;
            int at = source.indexOf(old);
            if (at >= 0 && source.indexOf(old, at + 1) < 0)
                edits.add(new RepairEdit(old, quote + next.replace("\\", "\\\\").replace(quote, "\\" + quote) + quote));
        }
        if (edits.size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "SELECTOR_LITERAL_NOT_UNIQUE");
        var inspection = reports.staticEvidence(repair.frozen().path("baseline"));
        var boundCalls = inspection.path("selectorCalls").valueStream()
                .filter(call -> call.path("selectorType").asText().equals(selectorType)
                        && call.path("selectorSha256").asText().equals(Hashes.sha256(previous))
                        && call.path("literalSha256").asText().equals(Hashes.sha256(edits.getFirst().oldText())))
                .toList();
        if (inspection.path("selectorCallsTruncated").asBoolean(true) || boundCalls.size() != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SELECTOR_SOURCE_CALL_UNVERIFIED");
        var evidence = extraction.replay(new ExtractionReplayRequest(
                input.replay().expectedArtifactSha256(), rule, input.replay().pages(),
                input.replay().dependencySha256()));
        String replaySha = Hashes.sha256(evidence.toString());
        evidence.put("field", input.field()).put("selectorType", selectorType)
                .put("oldSelectorSha256", Hashes.sha256(previous))
                .put("newSelectorSha256", Hashes.sha256(next));
        evidence.set("sourceSelectorBinding", boundCalls.getFirst().deepCopy());
        evidence.put("sourceSelectorCallVerified", true)
                .put("newLiteralSha256", Hashes.sha256(edits.getFirst().newText()));
        var targets = new java.util.ArrayList<String>();
        repair.frozen().path("policy").path("targetNodes").forEach(node -> targets.add(node.asText()));
        return enqueue(store.reserveSelector(repairId, new RepairProposal(1, repair.baselineArtifactSha256(), "source.py",
                "Bounded selector candidate; original and independent holdout verified: " + replaySha,
                "Exact per-ID fields preserved; isolated source runtime validation still required",
                targets, edits, null), evidence));
    }

    /** Accepts only candidates already reserved by a transactional store. */
    public synchronized RepairCandidateSummary enqueue(RepairCandidate candidate) {
        String repairId = candidate.getRepairId();
        if (!Set.of("QUEUED", "RUNTIME_QUEUED").contains(candidate.getStatus())) return store.candidate(candidate.getId());
        boolean runtime = candidate.getStatus().equals("RUNTIME_QUEUED");
        if (tasks.containsKey(candidate.getId())) return store.candidate(candidate.getId());
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (runtime) executeRuntime(candidate);
            else execute(candidate);
            return null;
        }) {
            @Override protected void done() { tasks.remove(candidate.getId(), this); }
        };
        tasks.put(candidate.getId(), task);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            tasks.remove(candidate.getId(), task);
            if (runtime) store.finishRuntime(repairId, candidate.getId(), null, "QUEUE_FULL");
            else store.finish(repairId, candidate.getId(), null, "QUEUE_FULL");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "QUEUE_FULL");
        }
        return store.candidate(candidate.getId());
    }

    public synchronized RepairSummary cancel(String id) {
        String candidateId = store.get(id).activeCandidateId();
        var result = store.cancel(id);
        FutureTask<Void> task = candidateId == null ? null : tasks.get(candidateId);
        if (task != null && Set.of("CANCELLING", "CANCELLED").contains(result.status())) {
            task.cancel(true);
            executor.remove(task);
        }
        return store.get(id);
    }

    private void execute(RepairCandidate candidate) {
        String repairId = candidate.getRepairId();
        if (!store.start(repairId, candidate.getId())) return;
        try {
            var frozen = store.get(repairId).frozen();
            var inspection = inspector.inspect(candidate.getBody());
            var result = reports.build(frozen, candidate.getSha256(), inspection);
            reports.applySelectorBinding(result, store.candidate(candidate.getId()).selectorEvidence());
            store.finish(repairId, candidate.getId(), result, null);
        } catch (IOException e) {
            store.finish(repairId, candidate.getId(), null,
                    "INSPECTOR_CLEANUP_FAILED".equals(e.getMessage()) ? "INSPECTOR_CLEANUP_FAILED" : "INSPECTOR_IO_ERROR");
        } catch (InterruptedException e) {
            store.finish(repairId, candidate.getId(), null, "EXECUTION_INTERRUPTED");
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            store.finish(repairId, candidate.getId(), null, "INTERNAL_ERROR");
            throw e;
        }
    }

    private void executeRuntime(RepairCandidate candidate) {
        String repairId = candidate.getRepairId();
        if (!store.start(repairId, candidate.getId())) return;
        try {
            var repair = store.get(repairId);
            if (!repair.freshness().equals("current")) {
                store.finishRuntime(repairId, candidate.getId(), null, "REPAIR_INPUTS_CHANGED");
                return;
            }
            var baseline = repair.frozen().path("baseline");
            var inputs = RepairReport.runtimeInputs(repair.frozen());
            String credentialRevision = credentials.revision(repair.sourceId());
            var evidence = managed.inspect(runtimeJobId(store.candidate(candidate.getId())),
                    candidate.getBody(), baseline.path("profile"),
                    inputs.path("runtimeSha256").asText(), inputs.path("executionPolicySha256").asText(),
                    probes.runtimeCredentials(repair.baselineRunId()));
            credentials.observe(repair.sourceId(), credentialRevision, candidate.getSha256(), evidence);
            store.finishRuntime(repairId, candidate.getId(), evidence, null);
            var observed = store.candidate(candidate.getId());
            if ("RUNTIME_OBSERVED".equals(observed.status()) && !observed.result().path("runtimeAttempts").isEmpty()
                    && store.candidates(repairId).getLast().id().equals(observed.id()))
                store.reconcileRuntimeSuccess(observed.id(), observed.sha256(), repair.faultSha256());
        } catch (IOException e) {
            store.finishRuntime(repairId, candidate.getId(), null, managed.errorCode(e.getMessage()),
                    e instanceof ManagedExecutionFailure failure ? failure.diagnostic() : null);
        } catch (InterruptedException e) {
            store.finishRuntime(repairId, candidate.getId(), null, "EXECUTION_INTERRUPTED");
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            store.finishRuntime(repairId, candidate.getId(), null, "INTERNAL_ERROR");
            throw e;
        }
    }

    private String runtimeJobId(RepairCandidateSummary candidate) {
        return candidate.result() == null ? candidate.id()
                : candidate.result().path("runtimeJobId").asText(candidate.id());
    }

    @PreDestroy
    synchronized void close() throws InterruptedException {
        var queued = executor.shutdownNow();
        for (var entry : tasks.entrySet()) {
            if (queued.contains(entry.getValue())) {
                var candidate = store.candidate(entry.getKey());
                if (candidate.status().equals("RUNTIME_QUEUED"))
                    store.finishRuntime(candidate.repairId(), candidate.id(), null, "SERVICE_STOPPED");
                else store.finish(candidate.repairId(), candidate.id(), null, "SERVICE_STOPPED");
                entry.getValue().cancel(false);
            }
        }
        executor.awaitTermination(15, TimeUnit.SECONDS);
    }
}
