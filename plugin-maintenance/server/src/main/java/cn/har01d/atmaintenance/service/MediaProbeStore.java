package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.MediaProbeRun;
import cn.har01d.atmaintenance.repository.MediaProbeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MediaProbeStore {
    private final MediaProbeRepository repository;
    private final SourceService sources;
    private final MediaNetworkPolicy policy;
    private final MediaBudgetStore budgets;
    private final ObjectMapper mapper;

    @Transactional
    public MediaProbeRun create(String sourceId, String expectedSha) {
        var source = sources.get(sourceId);
        if (!source.artifactSha256().equals(expectedSha))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SOURCE_CHANGED");
        MediaProbeRun run = new MediaProbeRun();
        run.setSourceId(sourceId);
        run.setArtifactSha256(source.artifactSha256());
        run.setProfileSha256(source.profileSha256());
        run.setPolicySha256(policy.sha256());
        run.setPolicyJson(policy.snapshot().toString());
        run.setBudgetJson(policy.blockedReason() == null ? budgets.reserve(sourceId).toString()
                : mapper.createObjectNode().put("reserved", false).put("reason", policy.blockedReason()).toString());
        return repository.saveAndFlush(run);
    }

    @Transactional
    public boolean start(String id) {
        var run = locked(id);
        if (!run.getStatus().equals("QUEUED")) return false;
        run.setStatus("RUNNING");
        return true;
    }

    @Transactional
    public MediaProbeSummary cancel(String id) {
        var run = locked(id);
        if (run.getStatus().equals("QUEUED")) terminal(run, "CANCELLED", "USER_CANCELLED");
        else if (run.getStatus().equals("RUNNING")) run.setStatus("CANCELLING");
        return summary(run);
    }

    @Transactional
    public void finish(String id, String status, JsonNode result, String code) {
        var run = locked(id);
        if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(run.getStatus())) return;
        if (run.getStatus().equals("CANCELLING")) {
            terminal(run, "CANCELLED", "USER_CANCELLED");
            return;
        }
        terminal(run, status, code);
        run.setResultJson(result == null ? null : result.toString());
    }

    @Transactional
    public void recover() {
        for (var run : repository.findByStatusIn(List.of("QUEUED", "RUNNING", "CANCELLING")))
            terminal(run, "INTERRUPTED", "SERVICE_RESTARTED_INPUT_NOT_RETAINED");
    }

    @Transactional(readOnly = true)
    public MediaProbeSummary get(String id) {
        return summary(repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MEDIA_RUN_NOT_FOUND")));
    }

    @Transactional(readOnly = true)
    public List<MediaProbeSummary> list(String sourceId) {
        sources.requireSource(sourceId);
        return repository.findTop20BySourceIdOrderByCreatedAtDesc(sourceId).stream().map(this::summary).toList();
    }

    private MediaProbeRun locked(String id) {
        return repository.findForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MEDIA_RUN_NOT_FOUND"));
    }

    private void terminal(MediaProbeRun run, String status, String code) {
        run.setStatus(status);
        run.setErrorCode(code);
        run.setFinishedAt(Instant.now());
    }

    private MediaProbeSummary summary(MediaProbeRun run) {
        var source = sources.get(run.getSourceId());
        List<String> stale = new ArrayList<>();
        if (!source.artifactSha256().equals(run.getArtifactSha256())) stale.add("source");
        if (!source.profileSha256().equals(run.getProfileSha256())) stale.add("profile");
        if (!policy.sha256().equals(run.getPolicySha256())) stale.add("media_policy");
        JsonNode budget = decode(run.getBudgetJson());
        if (budgets.stale(budget)) stale.add("media_budget_policy");
        return new MediaProbeSummary(run.getId(), run.getSourceId(), run.getArtifactSha256(),
                run.getProfileSha256(), run.getPolicySha256(), run.getStatus(), run.getCreatedAt(),
                run.getFinishedAt(), run.getErrorCode(), stale.isEmpty() ? "current" : "inputs_changed",
                List.copyOf(stale), "operator_supplied_player", false, decode(run.getPolicyJson()), decode(run.getResultJson()),
                budget, run.getIssuedRequests());
    }

    private JsonNode decode(String value) {
        if (value == null) return null;
        try { return mapper.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Stored media evidence is invalid", error); }
    }
}
