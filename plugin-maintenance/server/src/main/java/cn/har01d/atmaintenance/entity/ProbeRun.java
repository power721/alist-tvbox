package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_probe_run")
@Getter
@Setter
public class ProbeRun {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "source_id", nullable = false, length = 36)
    private String sourceId;
    @Column(name = "source_revision")
    private Long sourceRevision;
    @Column(name = "credential_revision", length = 36)
    private String credentialRevision;
    @Column(name = "artifact_id", nullable = false, length = 36)
    private String artifactId;
    @Column(name = "artifact_sha256", nullable = false, length = 64)
    private String artifactSha256;
    @Column(name = "inspector_sha256", nullable = false, length = 64)
    private String inspectorSha256;
    @Column(name = "knowledge_sha256", nullable = false, length = 64)
    private String knowledgeSha256;
    @Column(name = "profile_sha256", length = 64)
    private String profileSha256;
    @Column(name = "profile_json", columnDefinition = "TEXT")
    private String profileJson;
    @Column(name = "execution_mode", nullable = false, length = 30)
    private String executionMode = "static";
    @Column(name = "runtime_sha256", length = 64)
    private String runtimeSha256;
    @Column(name = "runtime_json", columnDefinition = "TEXT")
    private String runtimeJson;
    @Column(name = "execution_policy_sha256", length = 64)
    private String executionPolicySha256;
    @Column(name = "execution_policy_json", columnDefinition = "TEXT")
    private String executionPolicyJson;
    @Column(name = "binding_sha256", length = 64)
    private String bindingSha256;
    @Column(name = "binding_json", columnDefinition = "TEXT")
    private String bindingJson;
    @Column(name = "trigger_kind", nullable = false, length = 20)
    private String triggerKind = "manual";
    @Column(name = "schedule_sha256", length = 64)
    private String scheduleSha256;
    @Column(name = "schedule_json", columnDefinition = "TEXT")
    private String scheduleJson;
    @Column(name = "schedule_budget_day")
    private java.time.LocalDate scheduleBudgetDay;
    @Column(name = "schedule_ordinal")
    private Integer scheduleOrdinal;
    @Column(nullable = false, length = 30)
    private String status = "QUEUED";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;
    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;
    @Column(name = "error_code", length = 100)
    private String errorCode;
    @Column(name = "maintenance_key", length = 80)
    private String maintenanceKey;
    @Column(name = "maintenance_configuration_sha256", length = 64)
    private String maintenanceConfigurationSha256;
    @Column(name = "maintenance_status", length = 30)
    private String maintenanceStatus;
    @Column(name = "maintenance_repair_id", length = 36)
    private String maintenanceRepairId;
    @Column(name = "maintenance_stop_code", length = 100)
    private String maintenanceStopCode;
}
