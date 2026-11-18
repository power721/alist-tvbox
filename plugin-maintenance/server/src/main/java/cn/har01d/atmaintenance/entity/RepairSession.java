package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_repair", uniqueConstraints =
        @UniqueConstraint(columnNames = {"source_id", "fault_sha256"}))
@Getter
@Setter
public class RepairSession {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "source_id", nullable = false, length = 36, updatable = false)
    private String sourceId;
    @Column(name = "baseline_run_id", nullable = false, length = 36, updatable = false)
    private String baselineRunId;
    @Column(name = "baseline_artifact_sha256", nullable = false, length = 64, updatable = false)
    private String baselineArtifactSha256;
    @Column(name = "fault_sha256", nullable = false, length = 64, updatable = false)
    private String faultSha256;
    @Column(name = "frozen_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String frozenJson;
    @Column(name = "reverse_analysis_json", columnDefinition = "TEXT")
    private String reverseAnalysisJson;
    @Column(name = "runtime_context_json", columnDefinition = "TEXT")
    private String runtimeContextJson;
    @Column(name = "evidence_continuations_json", columnDefinition = "TEXT")
    private String evidenceContinuationsJson;
    @Column(nullable = false, length = 40)
    private String status = "AWAITING_PROPOSAL";
    @Column(name = "stop_code", length = 100)
    private String stopCode;
    @Column(name = "automatic_status", nullable = false, length = 40)
    private String automaticStatus = "DISABLED";
    @Column(name = "automatic_configuration_sha256", length = 64)
    private String automaticConfigurationSha256;
    @Column(name = "automatic_stop_code", length = 100)
    private String automaticStopCode;
    @Column(name = "automatic_enhanced_diagnostics", nullable = false)
    private boolean automaticEnhancedDiagnostics;
    @Column(name = "used_rounds", nullable = false)
    private int usedRounds;
    @Column(name = "active_candidate_id", length = 36)
    private String activeCandidateId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
