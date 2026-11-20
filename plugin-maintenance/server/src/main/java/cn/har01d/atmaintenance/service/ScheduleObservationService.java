package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.entity.ProbeRun;
import cn.har01d.atmaintenance.repository.ProbeRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ScheduleObservationService {
    static final int LIMIT = 512;
    private static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING", "CANCELLING");
    private static final Set<String> NODE_STATES = Set.of("pass", "fail", "blocked", "not_tested");
    private final ProbeRunRepository runs;
    private final SourceService sources;
    private final ProbeScheduleStore schedules;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ObjectNode get(String sourceId) {
        sources.requireSource(sourceId);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant start = today.minusDays(13).atStartOfDay(ZoneOffset.UTC).toInstant();
        String currentPolicy = schedules.get(sourceId).sha256();
        var found = runs.scheduledWindow(sourceId, start, now, PageRequest.of(0, LIMIT + 1));
        boolean complete = found.size() <= LIMIT;
        var window = new ArrayList<>(found.subList(0, Math.min(found.size(), LIMIT)));
        Collections.reverse(window);
        var result = mapper.createObjectNode().put("schemaVersion", 1)
                .put("authority", "persisted_scheduled_probe_history").put("sourceId", sourceId)
                .put("generatedAt", now.toString()).put("windowStart", start.toString())
                .put("windowEnd", now.toString()).put("windowDays", 14)
                .put("currentPolicySha256", currentPolicy).put("historyComplete", complete)
                .put("limit", LIMIT).put("publishEligible", false).put("trialAccepted", false);
        var totals = counts();
        var current = counts();
        Map<LocalDate, ObjectNode> days = new LinkedHashMap<>();
        for (int i = 13; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            days.put(date, counts().put("date", date.toString()));
        }
        var rows = mapper.createArrayNode();
        Map<String, ObjectNode> previous = new HashMap<>();
        Set<LocalDate> observedDays = new HashSet<>();
        Instant first = null, last = null;
        for (var run : window) {
            JsonNode report = decode(run.getResultJson());
            var row = mapper.createObjectNode().put("id", run.getId()).put("status", run.getStatus())
                    .put("createdAt", run.getCreatedAt().toString())
                    .put("finishedAt", run.getFinishedAt() == null ? null : run.getFinishedAt().toString())
                    .put("errorCode", run.getErrorCode()).put("executionMode", run.getExecutionMode())
                    .put("scheduleSha256", run.getScheduleSha256())
                    .put("scheduleBudgetDay", Objects.toString(run.getScheduleBudgetDay(), null));
            if (run.getMaintenanceKey() != null)
                row.putObject("maintenance").put("status", run.getMaintenanceStatus())
                        .put("repairId", run.getMaintenanceRepairId()).put("stopCode", run.getMaintenanceStopCode());
            if (run.getScheduleOrdinal() == null) row.putNull("scheduleOrdinal");
            else row.put("scheduleOrdinal", run.getScheduleOrdinal());
            var identity = identity(run);
            row.set("inputs", identity);
            row.put("currentPolicy", currentPolicy != null && currentPolicy.equals(run.getScheduleSha256()));
            String cleanup = cleanup(run, report);
            row.put("cleanup", cleanup);
            var states = requiredStates(run, report);
            var coverage = row.putObject("requiredCoverage").put("total", states.size());
            for (String state : NODE_STATES)
                coverage.put(state, (int) states.values().stream().filter(state::equals).count());
            String outcome = ACTIVE.contains(run.getStatus()) ? "pending"
                    : cleanup.equals("unconfirmed") ? "cleanup_unconfirmed"
                    : run.getStatus().equals("CANCELLED") ? "cancelled"
                    : !run.getStatus().equals("COMPLETED") || run.getErrorCode() != null ? "execution_failed"
                    : !cleanup.equals("not_applicable") && !cleanup.equals("confirmed") ? "cleanup_unconfirmed"
                    : !states.isEmpty() && states.values().stream().allMatch("pass"::equals)
                        ? "declared_checks_passed" : "declared_checks_incomplete";
            row.put("outcome", outcome);
            var regressed = row.putArray("regressedNodes");
            boolean comparable = comparable(run) && run.getStatus().equals("COMPLETED")
                    && run.getErrorCode() == null && !states.isEmpty()
                    && Set.of("confirmed", "not_applicable").contains(cleanup);
            if (comparable) {
                String key = identity.toString() + ":" + run.getScheduleSha256();
                var before = previous.get(key);
                if (before != null && requiredIds(before).equals(states.keySet())) {
                    row.put("comparedRunId", before.path("id").asText());
                    states.forEach((id, state) -> {
                        if (before.path("nodeStates").path(id).asText().equals("pass") && !state.equals("pass"))
                            regressed.add(id);
                    });
                }
                var observation = row.deepCopy();
                observation.set("nodeStates", mapper.valueToTree(states));
                previous.put(key, observation);
            }
            count(totals, row);
            if (row.path("currentPolicy").booleanValue()) count(current, row);
            count(days.get(LocalDate.ofInstant(run.getCreatedAt(), ZoneOffset.UTC)), row);
            if (comparable && run.getFinishedAt() != null && !run.getFinishedAt().isAfter(now)
                    && !run.getFinishedAt().isBefore(run.getCreatedAt())) {
                observedDays.add(LocalDate.ofInstant(run.getFinishedAt(), ZoneOffset.UTC));
                if (first == null || run.getFinishedAt().isBefore(first)) first = run.getFinishedAt();
                if (last == null || run.getFinishedAt().isAfter(last)) last = run.getFinishedAt();
            }
            rows.add(row);
        }
        result.set("totals", totals);
        result.set("currentPolicyTotals", current);
        result.put("observedUtcDays", observedDays.size())
                .put("firstObservedAt", first == null ? null : first.toString())
                .put("lastObservedAt", last == null ? null : last.toString());
        result.set("days", mapper.valueToTree(days.values()));
        // Newest first in the workbench and export; comparison was computed chronologically.
        var newest = result.putArray("runs");
        for (int i = rows.size() - 1; i >= 0; i--) newest.add(rows.get(i));
        result.put("boundary", "最近14个UTC日期内已持久投放的巡检任务；今天尚未结束。"
                + "汇总区分历史策略与当前策略，节点退化仅比较相同冻结输入和策略。"
                + "观察天数只计有完成回执的日期，不证明连续在线、播放成功或试运行验收；"
                + "截断时所有计数仅覆盖返回记录。导入只保留历史数据，不恢复投放、预算或发布权限。");
        return result;
    }

    private ObjectNode counts() {
        var node = mapper.createObjectNode();
        for (String key : List.of("attempts", "pending", "cancelled", "execution_failed", "cleanup_unconfirmed",
                "declared_checks_passed", "declared_checks_incomplete", "regressions")) node.put(key, 0);
        return node;
    }

    private void count(ObjectNode counts, JsonNode row) {
        counts.put("attempts", counts.path("attempts").intValue() + 1);
        String outcome = row.path("outcome").asText();
        counts.put(outcome, counts.path(outcome).intValue() + 1);
        if (!row.path("regressedNodes").isEmpty())
            counts.put("regressions", counts.path("regressions").intValue() + 1);
    }

    private Map<String, String> requiredStates(ProbeRun run, JsonNode report) {
        JsonNode required = decode(run.getProfileJson()).path("requiredNodes");
        Map<String, String> states = new TreeMap<>();
        if (!required.isArray() || required.isEmpty() || required.size() > 44) return states;
        for (JsonNode id : required) {
            if (!id.isTextual() || !id.textValue().matches("[SCRMP][0-9]{2}")
                    || states.put(id.textValue(), "not_tested") != null) return Map.of();
        }
        for (String id : states.keySet()) {
            var matches = report.path("nodes").valueStream().filter(n -> n.path("id").asText().equals(id)).toList();
            if (matches.size() == 1 && NODE_STATES.contains(matches.getFirst().path("status").asText()))
                states.put(id, matches.getFirst().path("status").asText());
        }
        return states;
    }

    private Set<String> requiredIds(ObjectNode row) {
        var ids = new HashSet<String>();
        row.path("nodeStates").fieldNames().forEachRemaining(ids::add);
        return ids;
    }

    private String cleanup(ProbeRun run, JsonNode report) {
        if (Objects.toString(run.getErrorCode(), "").contains("CLEANUP")) return "unconfirmed";
        if (!Set.of("managed_source", "container_fixture").contains(run.getExecutionMode())) return "not_applicable";
        JsonNode evidence = report.path(run.getExecutionMode().equals("managed_source")
                ? "managedSourceEvidence" : "runtimeEvidence");
        JsonNode cleanup = evidence.path("cleanup");
        if (!cleanup.isObject()) return "not_observed";
        return cleanup.path("confirmed").isBoolean() && cleanup.path("confirmed").booleanValue()
                && cleanup.path("containerAbsent").isBoolean() && cleanup.path("containerAbsent").booleanValue()
                ? "confirmed" : "unconfirmed";
    }

    private ObjectNode identity(ProbeRun run) {
        var node = mapper.createObjectNode().put("artifactSha256", run.getArtifactSha256())
                .put("profileSha256", run.getProfileSha256()).put("runtimeSha256", run.getRuntimeSha256())
                .put("executionPolicySha256", run.getExecutionPolicySha256())
                .put("inspectorSha256", run.getInspectorSha256()).put("knowledgeSha256", run.getKnowledgeSha256())
                .put("bindingSha256", run.getBindingSha256()).put("executionMode", run.getExecutionMode());
        if (run.getSourceRevision() == null) node.putNull("sourceRevision");
        else node.put("sourceRevision", run.getSourceRevision());
        return node;
    }

    private boolean comparable(ProbeRun run) {
        if (run.getSourceRevision() == null || run.getProfileSha256() == null
                || run.getScheduleSha256() == null || run.getInspectorSha256() == null
                || run.getKnowledgeSha256() == null) return false;
        return run.getExecutionMode().equals("static") || run.getRuntimeSha256() != null
                && (!Set.of("managed_source", "container_fixture").contains(run.getExecutionMode())
                    || run.getExecutionPolicySha256() != null);
    }

    private JsonNode decode(String json) {
        if (json == null) return mapper.missingNode();
        try { return mapper.readTree(json); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored schedule observation is invalid", e); }
    }
}
