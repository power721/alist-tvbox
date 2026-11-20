package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.ProbeRun;
import cn.har01d.atmaintenance.entity.ProbeSchedule;
import cn.har01d.atmaintenance.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ProbeScheduleStore {
    private static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING", "CANCELLING");
    private static final Set<String> SUSPEND = Set.of("TRUSTED_FIXTURE_REQUIRED", "FIXTURE_RUNTIME_UNSUPPORTED",
            "FIXTURE_EXT_UNAVAILABLE", "INSPECTOR_CLEANUP_FAILED", "CONTAINER_CLEANUP_UNCONFIRMED",
            "MANAGED_CLEANUP_UNCONFIRMED", "MANAGED_RUNNER_RECOVERY_REQUIRED",
            "MANAGED_RUNNER_IDENTITY_MISMATCH", "MANAGED_CONTROLS_UNCONFIRMED",
            "MANAGED_RECEIPT_MISMATCH", "MANAGED_INPUT_MISMATCH",
            "MANAGED_SCHEDULE_APPROVAL_CHANGED", "MANAGED_SCHEDULE_INPUTS_CHANGED");
    private final ProbeScheduleRepository schedules;
    private final ScheduleBudgetRepository budgets;
    private final SourceService sources;
    private final ProbeStore runs;
    private final StaticInspector inspector;
    private final KnowledgeService knowledge;
    private final ManagedSourceRuntime managed;
    private final ModelStore models;
    private final ModelClient modelClient;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean enabled;
    private final int dailyLimit;

    public ProbeScheduleStore(ProbeScheduleRepository schedules, ScheduleBudgetRepository budgets,
                              SourceService sources, ProbeStore runs, StaticInspector inspector,
                              KnowledgeService knowledge, ManagedSourceRuntime managed,
                              ModelStore models, ModelClient modelClient, ObjectMapper mapper, Clock clock,
                              @Value("${maintenance.scheduling.enabled:false}") boolean enabled,
                              @Value("${maintenance.scheduling.daily-limit:48}") int dailyLimit) {
        if (dailyLimit < 1 || dailyLimit > 1000) throw new IllegalArgumentException("INVALID_SCHEDULE_DAILY_LIMIT");
        this.schedules = schedules;
        this.budgets = budgets;
        this.sources = sources;
        this.runs = runs;
        this.inspector = inspector;
        this.knowledge = knowledge;
        this.managed = managed;
        this.models = models;
        this.modelClient = modelClient;
        this.mapper = mapper;
        this.clock = clock;
        this.enabled = enabled;
        this.dailyLimit = dailyLimit;
    }

    @Transactional(readOnly = true)
    public SchedulingSummary global() {
        var budget = budgets.findById(1).orElseThrow();
        return new SchedulingSummary(enabled, dailyLimit, budget.getBudgetDay(), budget.getUsedAttempts(),
                "UTC 日巡检投放次数；先持久扣减再投放，不因取消、拒绝、重启或改策略退款。"
                + "仅覆盖自动巡检任务，不是 HTTP 请求、字节或模型预算；不证明源在线。");
    }

    @Transactional(readOnly = true)
    public ScheduleSummary get(String sourceId) {
        sources.requireSource(sourceId);
        return schedules.findById(sourceId).map(this::summary).orElseGet(() ->
                new ScheduleSummary(sourceId, null, policy(false, "static", 21600, 4),
                        null, null, 0, 0, false, null, null, null, null));
    }

    @Transactional
    public ScheduleSummary update(String sourceId, UpdateScheduleRequest input) {
        var saved = schedules.findForUpdate(sourceId).orElse(null);
        sources.requireForUpdate(sourceId);
        if (!Objects.equals(saved == null ? null : saved.getSha256(), input.expectedSha256())) {
            throw error("SCHEDULE_CHANGED");
        }
        boolean firstPolicy = saved == null;
        JsonNode previous = firstPolicy ? mapper.createObjectNode() : decode(saved.getPolicyJson());
        boolean startChanged = input.firstRunAt() != null
                && !input.firstRunAt().toString().equals(previous.path("firstRunAt").asText());
        if (startChanged && input.firstRunAt().isBefore(clock.instant()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SCHEDULE_START_TIME_IN_PAST");
        if (saved == null) {
            saved = new ProbeSchedule();
            saved.setSourceId(sourceId);
            saved.setBudgetDay(day());
            saved.setNextDueAt(clock.instant());
        }
        var policy = policy(input.enabled(), input.mode(), input.intervalSeconds(), input.dailyLimit());
        if (input.firstRunAt() != null) ((ObjectNode) policy).put("firstRunAt", input.firstRunAt().toString());
        else if (previous.has("firstRunAt")) ((ObjectNode) policy).set("firstRunAt", previous.get("firstRunAt"));
        if ("managed_source".equals(input.mode())) {
            sources.requireMaintenanceAllowed(sourceId);
            JsonNode approval;
            if (input.approval() != null) {
                approval = approve(sourceId, input.approval().approvedRunId(),
                        input.approval().expectedSourceRevision());
            } else {
                approval = saved.getPolicyJson() == null ? null : decode(saved.getPolicyJson()).get("approval");
                if (approval == null || !approval.isObject()) throw error("MANAGED_SCHEDULE_APPROVAL_REQUIRED");
            }
            ((ObjectNode) policy).set("approval", approval);
            if (input.automaticRepair() != null) {
                if (input.automaticRepair().maxRounds() != null && input.automaticRepair().maxRounds() < 1)
                    throw error("REPAIR_ROUND_LIMIT_INVALID");
                ((ObjectNode) policy).putObject("automaticRepair").put("configurationSha256",
                        input.automaticRepair().expectedConfigurationSha256())
                        .put("maxRounds", input.automaticRepair().maxRounds() == null ? 3 : input.automaticRepair().maxRounds());
            }
            if (input.enabled()) {
                String blocked = approvalBlockedReason(sourceId, policy);
                if (blocked != null) throw error(blocked);
            }
        } else if (input.approval() != null) {
            throw error("MANAGED_SCHEDULE_APPROVAL_UNEXPECTED");
        } else if (input.automaticRepair() != null) {
            throw error("MANAGED_SCHEDULE_REPAIR_REQUIRES_LIVE_MODE");
        }
        String sha = Hashes.sha256(policy.toString());
        if (!sha.equals(saved.getSha256())) {
            saved.setSha256(sha);
            saved.setPolicyJson(policy.toString());
            saved.setEnabled(input.enabled());
            // Editing or re-enabling never pulls an existing deadline forward or resets budgets/backoff.
            Instant requested = startChanged ? input.firstRunAt() : due(clock.instant(), input.intervalSeconds());
            saved.setNextDueAt(firstPolicy ? requested : later(saved.getNextDueAt(), requested));
        }
        return summary(schedules.saveAndFlush(saved));
    }

    @Transactional
    public ScheduleSummary resume(String sourceId, String expectedSha) {
        var saved = schedules.findForUpdate(sourceId).orElseThrow(() -> error("SCHEDULE_NOT_CONFIGURED"));
        if (!saved.getSha256().equals(expectedSha)) throw error("SCHEDULE_CHANGED");
        String blocked = approvalBlockedReason(sourceId, decode(saved.getPolicyJson()));
        if (blocked != null) throw error(blocked);
        if (Set.of("MANAGED_CLEANUP_UNCONFIRMED", "MANAGED_RUNNER_RECOVERY_REQUIRED")
                .contains(Objects.toString(saved.getSuspensionReason(), ""))
                && saved.getLastRunId() != null) {
            if (!"managed_source".equals(decode(saved.getPolicyJson()).path("mode").asText()))
                throw error("MANAGED_SCHEDULE_FRESH_APPROVAL_REQUIRED");
            var failed = runs.get(saved.getLastRunId());
            var approved = runs.get(decode(saved.getPolicyJson()).path("approval").path("approvedRunId").asText());
            if (failed.finishedAt() == null || !approved.createdAt().isAfter(failed.finishedAt()))
                throw error("MANAGED_SCHEDULE_FRESH_APPROVAL_REQUIRED");
        }
        saved.setSuspended(false);
        saved.setSuspensionReason(null);
        saved.setNextDueAt(later(saved.getNextDueAt(), due(clock.instant(),
                decode(saved.getPolicyJson()).path("intervalSeconds").asInt())));
        return summary(saved);
    }

    @Transactional(readOnly = true)
    public List<String> dueSources() {
        return schedules.findTop20ByEnabledTrueAndSuspendedFalseAndNextDueAtLessThanEqualOrderByNextDueAtAscSourceIdAsc(
                clock.instant()).stream().map(ProbeSchedule::getSourceId).toList();
    }

    @Transactional(readOnly = true)
    public List<String> activeSources() {
        return schedules.findTop100ByActiveRunIdIsNotNullOrderBySourceIdAsc().stream()
                .map(ProbeSchedule::getSourceId).toList();
    }

    /** Budget reservation, immutable run creation and active ownership commit together, before enqueue. */
    @Transactional
    public ProbeRun claim(String sourceId) {
        var global = budgets.lockBudget();
        LocalDate today = day();
        if (today.isBefore(global.getBudgetDay())) return null; // Clock rollback cannot grant a second day.
        if (today.isAfter(global.getBudgetDay())) {
            global.setBudgetDay(today);
            global.setUsedAttempts(0);
        }
        if (global.getUsedAttempts() >= dailyLimit) return null;
        var schedule = schedules.findForUpdate(sourceId).orElse(null);
        Instant now = clock.instant();
        if (schedule == null || !schedule.isEnabled() || schedule.isSuspended()
                || schedule.getActiveRunId() != null || schedule.getNextDueAt().isAfter(now)) return null;
        if (today.isBefore(schedule.getBudgetDay())) return null;
        if (today.isAfter(schedule.getBudgetDay())) {
            schedule.setBudgetDay(today);
            schedule.setUsedAttempts(0);
        }
        var policy = decode(schedule.getPolicyJson());
        if ("managed_source".equals(policy.path("mode").asText())) sources.requireForUpdate(sourceId);
        if (runs.hasActiveMaintenance(sourceId)) return null;
        String blocked = approvalBlockedReason(sourceId, policy);
        if (blocked != null) {
            suspend(schedule, blocked);
            return null;
        }
        if (schedule.getUsedAttempts() >= policy.path("dailyLimit").asInt()) {
            schedule.setNextDueAt(later(schedule.getNextDueAt(),
                    today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            return null;
        }
        schedule.setUsedAttempts(schedule.getUsedAttempts() + 1);
        global.setUsedAttempts(global.getUsedAttempts() + 1);
        var run = runs.create(sourceId, inspector.sha256(), knowledge.sha256(), policy.path("mode").asText());
        run.setTriggerKind("scheduled");
        run.setScheduleSha256(schedule.getSha256());
        run.setScheduleJson(schedule.getPolicyJson());
        run.setScheduleBudgetDay(today);
        run.setScheduleOrdinal(schedule.getUsedAttempts());
        if (policy.has("automaticRepair")) {
            run.setMaintenanceKey("schedule_" + run.getId());
            run.setMaintenanceConfigurationSha256(policy.path("automaticRepair").path("configurationSha256").asText());
            run.setMaintenanceMaxRounds(policy.path("automaticRepair").path("maxRounds").asInt(3));
            run.setMaintenanceStatus("CHECKING");
        }
        schedule.setActiveRunId(run.getId());
        schedule.setNextDueAt(due(now, policy.path("intervalSeconds").asInt()));
        schedules.flush();
        return run;
    }

    /** Recheck the frozen approval after queueing, immediately before any managed execution. */
    @Transactional
    public String executionBlockedReason(ProbeRun run) {
        if (!"scheduled".equals(run.getTriggerKind()) || !"managed_source".equals(run.getExecutionMode())) return null;
        var schedule = schedules.findForUpdate(run.getSourceId()).orElseThrow();
        if (!schedule.isEnabled() || schedule.isSuspended()
                || !Objects.equals(schedule.getActiveRunId(), run.getId())
                || !Objects.equals(schedule.getSha256(), run.getScheduleSha256())) {
            // A stale queued job must not suspend a newly approved policy.
            return "MANAGED_SCHEDULE_APPROVAL_CHANGED";
        }
        sources.requireForUpdate(run.getSourceId());
        var policy = decode(run.getScheduleJson());
        String blocked = approvalBlockedReason(run.getSourceId(), policy);
        if (blocked == null && !identity(runs.get(run.getId())).equals(
                withoutRunId(policy.path("approval")))) blocked = "MANAGED_SCHEDULE_INPUTS_CHANGED";
        if (blocked != null) suspend(schedule, blocked);
        return blocked;
    }

    @Transactional
    public void reconcile(String sourceId) {
        var schedule = schedules.findForUpdate(sourceId).orElseThrow();
        if (schedule.getActiveRunId() == null) return;
        var run = runs.get(schedule.getActiveRunId());
        if (ACTIVE.contains(run.status())) return;
        MaintenanceSummary maintenance = run.schedule().has("automaticRepair")
                ? runs.maintenance(run.id()) : null;
        if (maintenance != null && Set.of("CHECKING", "REPAIRING").contains(maintenance.status())) return;
        schedule.setLastRunId(run.id());
        schedule.setActiveRunId(null);
        var coverage = run.result() == null ? mapper.createObjectNode() : run.result().path("requiredCoverage");
        boolean passed = run.status().equals("COMPLETED") && run.freshness().equals("current")
                && coverage.path("total").asInt() > 0
                && coverage.path("pass").asInt() == coverage.path("total").asInt();
        if (maintenance != null && "SUCCEEDED".equals(maintenance.status())) {
            schedule.setConsecutiveFailures(0);
            schedule.setLastOutcome("repair_candidate_verified");
        } else if (passed && (maintenance == null || "CHECKED".equals(maintenance.status()))) {
            schedule.setConsecutiveFailures(0);
            schedule.setLastOutcome("declared_checks_passed");
        } else {
            int failures = Math.min(1000, schedule.getConsecutiveFailures() + 1);
            schedule.setConsecutiveFailures(failures);
            String outcome = maintenance != null && maintenance.stopCode() != null ? maintenance.stopCode()
                    : maintenance != null && "CANCELLED".equals(maintenance.status()) ? "USER_CANCELLED"
                    : run.errorCode() != null ? run.errorCode()
                    : !run.freshness().equals("current") ? "INPUTS_CHANGED" : "DECLARED_CHECKS_INCOMPLETE";
            schedule.setLastOutcome(outcome);
            // Persistent 30m, 2h, 6h, 24h backoff; the configured interval is also a lower bound.
            int backoff = switch (failures) { case 1 -> 1800; case 2 -> 7200; case 3 -> 21600; default -> 86400; };
            int interval = decode(schedule.getPolicyJson()).path("intervalSeconds").asInt();
            schedule.setNextDueAt(later(schedule.getNextDueAt(), due(clock.instant(), Math.max(interval, backoff))));
            if ((SUSPEND.contains(outcome) || policyRequiresModelRepairPause(outcome))
                    && (!"MANAGED_SCHEDULE_APPROVAL_CHANGED".equals(outcome)
                    || Objects.equals(schedule.getSha256(), run.scheduleSha256()))) {
                schedule.setSuspended(true);
                schedule.setSuspensionReason(outcome);
            }
        }
    }

    private JsonNode policy(boolean enabled, String mode, int seconds, int limit) {
        return mapper.createObjectNode().put("schemaVersion", 1).put("enabled", enabled).put("mode", mode)
                .put("intervalSeconds", seconds).put("dailyLimit", limit).put("jitter", "positive_0_to_20_percent")
                .put("budgetUnit", "scheduled_attempt").put("authority", "declared");
    }

    private ObjectNode approve(String sourceId, String runId, Long revision) {
        ProbeSummary run;
        try { run = runs.get(runId); }
        catch (ResponseStatusException e) { throw error("MANAGED_SCHEDULE_APPROVAL_INVALID"); }
        var source = sources.get(sourceId);
        var selectedRuntime = managed.forSource(sourceId);
        var evidence = run.result() == null ? mapper.createObjectNode()
                : run.result().path("managedSourceEvidence");
        boolean staticPassed = run.result() != null && run.result().path("nodes").valueStream()
                .anyMatch(node -> node.path("id").asText().equals("S03")
                        && node.path("status").asText().equals("pass"));
        if (!sourceId.equals(run.sourceId()) || !"manual".equals(run.triggerKind())
                || !"managed_source".equals(run.executionMode()) || !"COMPLETED".equals(run.status())
                || run.errorCode() != null || !staticPassed
                || !evidence.path("cleanup").path("confirmed").isBoolean()
                || !evidence.path("cleanup").path("confirmed").booleanValue()
                || !evidence.path("cleanup").path("containerAbsent").isBoolean()
                || !evidence.path("cleanup").path("containerAbsent").booleanValue())
            throw error("MANAGED_SCHEDULE_APPROVAL_INVALID");
        if (revision == null || run.sourceRevision() == null || !revision.equals(run.sourceRevision())
                || revision.longValue() != source.revision() || !"current".equals(run.freshness())
                || !selectedRuntime.sha256().equals(run.runtimeSha256())
                || !selectedRuntime.policySha256().equals(run.executionPolicySha256())
                || !inspector.sha256().equals(run.inspectorSha256())
                || !knowledge.sha256().equals(run.knowledgeSha256()))
            throw error("MANAGED_SCHEDULE_INPUTS_CHANGED");
        return identity(run).put("approvedRunId", run.id());
    }

    private String approvalBlockedReason(String sourceId, JsonNode policy) {
        if (!"managed_source".equals(policy.path("mode").asText())) return null;
        var approval = policy.path("approval");
        try {
            var expected = approve(sourceId, approval.path("approvedRunId").asText(),
                    approval.path("sourceRevision").isIntegralNumber()
                            ? approval.path("sourceRevision").longValue() : null);
            if (!expected.equals(approval)) return "MANAGED_SCHEDULE_INPUTS_CHANGED";
            if (policy.has("automaticRepair")) {
                modelClient.requireEnabled();
                var connection = modelClient.connection();
                if (!connection.configurationSha256().equals(
                        policy.path("automaticRepair").path("configurationSha256").asText()))
                    return "MODEL_CONFIGURATION_CHANGED";
                if (!connection.autoSubmitEnabled()) return "MODEL_AUTO_SUBMIT_DISABLED";
                if (!approval.path("artifactSha256").asText().equals(models.consent(sourceId).artifactSha256()))
                    return "MODEL_SOURCE_NOT_AUTHORIZED";
            }
            return managed.forSource(sourceId).blockedReason(runs.get(approval.path("approvedRunId").asText()).profile());
        } catch (ResponseStatusException e) {
            return e.getReason();
        }
    }

    private boolean policyRequiresModelRepairPause(String code) {
        return Set.of("MODEL_CONFIGURATION_CHANGED", "MODEL_AUTO_SUBMIT_DISABLED",
                "MODEL_SOURCE_NOT_AUTHORIZED", "MODEL_DISABLED").contains(code);
    }

    private ObjectNode identity(ProbeSummary run) {
        var identity = mapper.createObjectNode().put("sourceRevision", run.sourceRevision())
                .put("artifactSha256", run.artifactSha256()).put("profileSha256", run.profileSha256())
                .put("runtimeSha256", run.runtimeSha256()).put("executionPolicySha256", run.executionPolicySha256())
                .put("inspectorSha256", run.inspectorSha256()).put("knowledgeSha256", run.knowledgeSha256())
                .put("bindingSha256", run.bindingSha256());
        return (ObjectNode) decode(identity.toString());
    }

    private ObjectNode withoutRunId(JsonNode approval) {
        var result = ((ObjectNode) approval).deepCopy();
        result.remove("approvedRunId");
        return result;
    }

    private void suspend(ProbeSchedule schedule, String reason) {
        schedule.setSuspended(true);
        schedule.setSuspensionReason(reason);
        schedule.setLastOutcome(reason);
    }

    private ScheduleSummary summary(ProbeSchedule s) {
        return new ScheduleSummary(s.getSourceId(), s.getSha256(), decode(s.getPolicyJson()), s.getNextDueAt(),
                s.getBudgetDay(), s.getUsedAttempts(), s.getConsecutiveFailures(), s.isSuspended(),
                s.getSuspensionReason(), s.getActiveRunId(), s.getLastRunId(), s.getLastOutcome());
    }

    private JsonNode decode(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored schedule is invalid", e); }
    }

    private LocalDate day() { return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private Instant due(Instant now, int seconds) {
        return now.plusSeconds(seconds + ThreadLocalRandom.current().nextInt(seconds / 5 + 1));
    }
    private Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private ResponseStatusException error(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
}
