package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.ProbeSummary;
import cn.har01d.atmaintenance.entity.ProbeRun;
import cn.har01d.atmaintenance.entity.RepairCandidate;
import cn.har01d.atmaintenance.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WorkbenchService {
    private final SourceService sources;
    private final ProbeStore probes;
    private final ProbeRunRepository runs;
    private final RepairRepository repairs;
    private final RepairCandidateRepository candidates;
    private final ModelCallRepository calls;
    private final ObjectMapper mapper;
    @Value("${maintenance.log-import-dir:${MAINTENANCE_DATA_DIR:./data}/log-imports}") private String importDirectory;

    @Transactional(readOnly = true)
    public ArrayNode overview() {
        var result = mapper.createArrayNode();
        for (var run : runs.latestLiveRuns()) {
            ProbeSummary summary = probes.get(run.getId());
            var row = result.addObject().put("sourceId", run.getSourceId()).put("runId", run.getId())
                    .put("checkedAt", run.getCreatedAt().toString()).put("artifactSha256", run.getArtifactSha256());
            row.put("state", health(summary)).put("executionMode", "managed_source");
            row.put("maintenanceStatus", run.getMaintenanceStatus());
            row.put("stopCode", run.getMaintenanceStopCode());
            addAttention(row, run, summary);
        }
        return result;
    }

    private void addAttention(ObjectNode row, ProbeRun run, ProbeSummary summary) {
        var regressed = row.putArray("regressedNodes");
        row.put("failedRepairRounds", 0).put("repairExhausted", false);
        if (!"current".equals(summary.freshness())) return;
        if ("needs_repair".equals(row.path("state").asText())) {
            for (var previous : runs.findTop30BySourceIdOrderByCreatedAtDesc(run.getSourceId())) {
                if (!previous.getCreatedAt().isBefore(run.getCreatedAt())
                        || !"managed_source".equals(previous.getExecutionMode())
                        || !sameProbeInputs(run, previous)) continue;
                var before = probes.get(previous.getId());
                if (!Set.of("verified", "needs_repair", "incomplete").contains(health(before))) continue;
                for (var node : summary.result().path("nodes")) {
                    if ("fail".equals(node.path("status").asText()) && before.result().path("nodes").valueStream()
                            .anyMatch(old -> old.path("id").equals(node.path("id"))
                                    && "pass".equals(old.path("status").asText()))) regressed.add(node.path("id"));
                }
                break;
            }
        }
        row.put("degraded", !regressed.isEmpty());
        if ("verified".equals(row.path("state").asText())) return;
        var repair = repairs.findTop20BySourceIdOrderByCreatedAtDesc(run.getSourceId()).stream()
                .filter(item -> run.getArtifactSha256().equals(item.getBaselineArtifactSha256()))
                .findFirst().orElse(null);
        if (repair == null || !"current".equals(probes.get(repair.getBaselineRunId()).freshness())) return;
        var attempts = candidates.findByRepairIdOrderByRoundNumberAsc(repair.getId());
        // Count persisted candidate validation, never probe failures or model transport attempts.
        int failed = (int) attempts.stream().filter(this::failedRepairRound).count();
        boolean succeeded = attempts.stream().filter(item -> item.getResultJson() != null)
                .anyMatch(item -> json(item.getResultJson()).path("repairSuccessful").asBoolean());
        row.put("failedRepairRounds", failed).put("repairExhausted", failed >= 3 && !succeeded);
        if (!succeeded && !attempts.isEmpty()) {
            var latest = attempts.getLast();
            if (latest.getResultJson() != null && failedRepairRound(latest)) {
                var regressions = json(latest.getResultJson()).path("observedRegressions");
                if (!regressions.isEmpty()) row.put("candidateRegressed", true);
            }
        }
    }

    private boolean sameProbeInputs(ProbeRun a, ProbeRun b) {
        return Objects.equals(a.getArtifactSha256(), b.getArtifactSha256())
                && Objects.equals(a.getSourceRevision(), b.getSourceRevision())
                && Objects.equals(a.getCredentialRevision(), b.getCredentialRevision())
                && Objects.equals(a.getProfileSha256(), b.getProfileSha256())
                && Objects.equals(a.getRuntimeSha256(), b.getRuntimeSha256())
                && Objects.equals(a.getExecutionPolicySha256(), b.getExecutionPolicySha256())
                && Objects.equals(a.getBindingSha256(), b.getBindingSha256())
                && Objects.equals(a.getKnowledgeSha256(), b.getKnowledgeSha256())
                && Objects.equals(a.getInspectorSha256(), b.getInspectorSha256());
    }

    private boolean failedRepairRound(RepairCandidate candidate) {
        if (!Set.of("REJECTED", "RUNTIME_OBSERVED").contains(candidate.getStatus())
                || candidate.getResultJson() == null) return false;
        JsonNode result = json(candidate.getResultJson());
        if (result.path("repairSuccessful").asBoolean() || result.path("runtimeValidationPaused").asBoolean()) return false;
        if (result.path("staticValidationPassed").isBoolean() && !result.path("staticValidationPassed").asBoolean()
                && result.path("inspection").isObject()) return true;
        JsonNode evidence = result.path("runtimeEvidence");
        return result.path("runtimeAcceptance").isObject() && trustedResponse(evidence);
    }

    private static boolean trustedResponse(JsonNode evidence) {
        if (!evidence.path("cleanup").path("confirmed").asBoolean()
                || !evidence.path("cleanup").path("containerAbsent").asBoolean()
                || !evidence.path("deniedCodes").isEmpty()) return false;
        return evidence.path("httpObservations").valueStream().anyMatch(observation -> {
            int status = observation.path("responseStatus").asInt();
            return observation.path("responseComplete").asBoolean() && status >= 100 && status <= 599
                    && observation.path("responseBytes").isIntegralNumber() && observation.path("responseBytes").asLong() >= 0;
        });
    }

    static String health(ProbeSummary run) {
        if (!"current".equals(run.freshness())) return "stale";
        if (Set.of("QUEUED", "RUNNING", "CANCELLING").contains(run.status())) return "running";
        if (!"COMPLETED".equals(run.status()) || run.errorCode() != null || run.result() == null) return "blocked";
        JsonNode result = run.result(), evidence = result.path("managedSourceEvidence");
        if (!evidence.path("cleanup").path("confirmed").asBoolean()
                || !evidence.path("cleanup").path("containerAbsent").asBoolean()
                || !evidence.path("deniedCodes").isEmpty()) return "blocked";
        boolean response = false, business = false, failed = false;
        for (var observation : evidence.path("httpObservations")) {
            int status = observation.path("responseStatus").asInt();
            response |= observation.path("responseComplete").asBoolean() && status >= 100 && status <= 599
                    && observation.path("responseBytes").isIntegralNumber() && observation.path("responseBytes").asLong() >= 0;
        }
        if (!response) return "blocked";
        for (var node : result.path("nodes")) {
            failed |= "fail".equals(node.path("status").asText());
            business |= "pass".equals(node.path("status").asText())
                    && Set.of("C03", "C05", "C07", "C09", "C10", "R01", "M01").contains(node.path("id").asText());
        }
        if (failed) return "needs_repair";
        JsonNode coverage = result.path("requiredCoverage");
        return business && coverage.path("total").asInt() > 0
                && coverage.path("total").asInt() == coverage.path("pass").asInt() ? "verified" : "incomplete";
    }

    @Transactional(readOnly = true)
    public ObjectNode log(String sourceId, String runId) {
        var source = sources.get(sourceId);
        var root = mapper.createObjectNode().put("format", "at-maintenance-log").put("schemaVersion", 1)
                .put("sourceId", sourceId).put("sourceName", source.name()).put("exportedAt", Instant.now().toString())
                .put("authority", "persisted_task_records");
        var history = runs.findTop30BySourceIdOrderByCreatedAtDesc(sourceId);
        var sessions = root.putArray("sessions");
        for (var item : history) sessions.addObject().put("runId", item.getId())
                .put("createdAt", item.getCreatedAt().toString()).put("mode", item.getExecutionMode())
                .put("status", item.getMaintenanceStatus() == null ? item.getStatus() : item.getMaintenanceStatus());
        ProbeRun run = runId == null || runId.isBlank() ? history.stream().findFirst().orElse(null)
                : runs.findById(runId).filter(item -> item.getSourceId().equals(sourceId)).orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "MAINTENANCE_LOG_RUN_NOT_FOUND"));
        ArrayNode events = root.putArray("events");
        if (run == null) return root.put("active", false);
        root.put("runId", run.getId()).put("artifactSha256", run.getArtifactSha256());
        root.put("active", Set.of("QUEUED", "RUNNING", "CANCELLING").contains(run.getStatus())
                || Set.of("CHECKING", "REPAIRING").contains(Objects.toString(run.getMaintenanceStatus(), "")));
        root.put("status", run.getMaintenanceStatus() == null ? run.getStatus() : run.getMaintenanceStatus());
        event(events, "probe:" + run.getId(), run.getCreatedAt(), "inspection", run.getStatus(),
                run.getErrorCode(), "managed_source".equals(run.getExecutionMode()) ? "联网巡检任务" : "非联网检查任务");
        if (run.getFinishedAt() != null) event(events, "probe-end:" + run.getId(), run.getFinishedAt(), "inspection",
                run.getStatus(), run.getErrorCode(), "巡检执行结束");
        if (run.getResultJson() != null) {
            var result = json(run.getResultJson());
            addNodes(events, "probe-node:" + run.getId(), run.getFinishedAt(), "inspection", result.path("nodes"));
            var summary = root.putObject("network");
            JsonNode evidence = result.path("managedSourceEvidence");
            summary.put("observations", evidence.path("httpObservations").size());
            summary.put("cleanupConfirmed", evidence.path("cleanup").path("confirmed").asBoolean());
        }
        var repair = run.getMaintenanceRepairId() == null ? repairs.findTop20BySourceIdOrderByCreatedAtDesc(sourceId)
                .stream().filter(item -> item.getBaselineRunId().equals(run.getId())).findFirst().orElse(null)
                : repairs.findById(run.getMaintenanceRepairId()).orElse(null);
        if (repair != null) {
            if (Set.of("VALIDATING", "VALIDATING_RUNTIME", "CANCELLING").contains(repair.getStatus())
                    || Set.of("QUEUED", "RUNNING", "CANCELLING").contains(repair.getAutomaticStatus())) root.put("active", true);
            root.put("repairId", repair.getId()).put("rounds", repair.getUsedRounds());
            event(events, "repair:" + repair.getId(), repair.getCreatedAt(), "repair", repair.getStatus(),
                    repair.getStopCode(), "修复会话");
            for (var call : calls.findByRepairIdOrderByAttemptNumberAsc(repair.getId())) {
                event(events, "model:" + call.getId(), call.getCreatedAt(), "model", "SUBMITTED", null,
                        "第 " + call.getAttemptNumber() + " 次模型请求");
                event(events, "model-result:" + call.getId(), call.getFinishedAt() == null ? call.getCreatedAt() : call.getFinishedAt(),
                        "model", call.getStatus(), call.getErrorCode(), "模型请求结果 · " + call.getSubmissionStatus());
            }
            for (var candidate : candidates.findByRepairIdOrderByRoundNumberAsc(repair.getId())) {
                event(events, "candidate:" + candidate.getId(), candidate.getCreatedAt(), "validation", "CREATED", null,
                        "第 " + candidate.getRoundNumber() + " 轮候选已保存");
                event(events, "candidate-result:" + candidate.getId(), candidate.getFinishedAt() == null
                                ? candidate.getCreatedAt() : candidate.getFinishedAt(), "validation", candidate.getStatus(),
                        candidate.getErrorCode(), "候选验证结果");
                if (candidate.getResultJson() != null) addNodes(events, "candidate-node:" + candidate.getId(),
                        candidate.getFinishedAt(), "validation", json(candidate.getResultJson()).path("runtimeAcceptance").path("nodes"));
            }
        }
        if (run.getMaintenanceStopCode() != null) event(events, "stop:" + run.getId(), run.getFinishedAt() == null
                ? run.getCreatedAt() : run.getFinishedAt(), "workflow", "STOPPED", run.getMaintenanceStopCode(), "维护任务暂停");
        var ordered = new ArrayList<JsonNode>();
        events.forEach(ordered::add);
        ordered.sort(Comparator.comparing(item -> item.path("time").asText()));
        events.removeAll(); ordered.forEach(events::add);
        root.set("analysis", analyze(events));
        return root;
    }

    private void addNodes(ArrayNode events, String prefix, Instant time, String stage, JsonNode nodes) {
        if (time == null) return;
        for (var node : nodes) event(events, prefix + ":" + node.path("id").asText(), time, stage,
                node.path("status").asText(), node.path("id").asText(), node.path("title").asText());
    }

    private void event(ArrayNode events, String id, Instant time, String stage, String status, String code, String message) {
        events.addObject().put("id", id).put("time", time.toString()).put("stage", stage)
                .put("status", status).put("code", code).put("message", message);
    }

    public ObjectNode importLog(JsonNode input) throws IOException {
        if (input == null || !"at-maintenance-log".equals(input.path("format").asText())
                || input.path("schemaVersion").asInt() != 1 || !input.path("events").isArray()
                || input.path("events").size() > 2000 || mapper.writeValueAsBytes(input).length > 2_097_152)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MAINTENANCE_LOG_INVALID");
        var accepted = mapper.createObjectNode().put("format", "at-maintenance-log").put("schemaVersion", 1)
                .put("authority", "imported_untrusted_log").put("active", false);
        for (String field : List.of("sourceId", "sourceName", "runId", "repairId", "artifactSha256", "exportedAt", "status"))
            accepted.put(field, text(input, field, 200));
        var events = accepted.putArray("events");
        for (var entry : input.path("events")) {
            if (!entry.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MAINTENANCE_LOG_INVALID");
            var clean = events.addObject();
            for (String field : List.of("id", "time", "stage", "status", "code", "message"))
                clean.put(field, text(entry, field, field.equals("message") ? 300 : 160));
            try { Instant.parse(clean.path("time").asText()); }
            catch (RuntimeException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MAINTENANCE_LOG_INVALID"); }
        }
        accepted.set("analysis", analyze(events));
        String id = UUID.randomUUID().toString();
        accepted.put("importId", id);
        Path directory = Path.of(importDirectory).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Files.write(directory.resolve(id + ".json"), mapper.writeValueAsBytes(accepted), StandardOpenOption.CREATE_NEW);
        return accepted;
    }

    public JsonNode importedLog(String id) throws IOException {
        if (!id.matches("[a-f0-9-]{36}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MAINTENANCE_LOG_NOT_FOUND");
        Path file = Path.of(importDirectory).resolve(id + ".json");
        if (!Files.isRegularFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MAINTENANCE_LOG_NOT_FOUND");
        return mapper.readTree(Files.readAllBytes(file));
    }

    private String text(JsonNode node, String name, int max) {
        var value = node.path(name);
        if (value.isMissingNode() || value.isNull()) return "";
        if (!value.isTextual() || value.asText().length() > max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MAINTENANCE_LOG_INVALID");
        return value.asText();
    }

    private ObjectNode analyze(ArrayNode events) {
        var result = mapper.createObjectNode().put("eventCount", events.size());
        var problems = result.putArray("problems");
        for (var event : events) if (Set.of("FAILED", "STOPPED", "BLOCKED", "REJECTED", "fail", "blocked")
                .contains(event.path("status").asText())) problems.add(event.deepCopy());
        result.put("problemCount", problems.size());
        return result;
    }

    private JsonNode json(String value) {
        try { return mapper.readTree(value); }
        catch (IOException error) { throw new IllegalStateException("MAINTENANCE_LOG_RECORD_INVALID", error); }
    }
}
