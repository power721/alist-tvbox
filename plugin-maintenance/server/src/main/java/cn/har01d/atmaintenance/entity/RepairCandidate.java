package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_repair_candidate", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"repair_id", "round_number"}),
        @UniqueConstraint(columnNames = "model_call_id")})
@Getter
@Setter
public class RepairCandidate {
    @Column(name = "source_import_id", length = 36, updatable = false)
    private String sourceImportId;
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "repair_id", nullable = false, length = 36, updatable = false)
    private String repairId;
    @Column(name = "model_call_id", length = 36, updatable = false)
    private String modelCallId;
    @Column(name = "round_number", nullable = false, updatable = false)
    private int roundNumber;
    @Column(nullable = false, length = 64, updatable = false)
    private String sha256;
    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String body;
    @Column(name = "proposal_sha256", nullable = false, length = 64, updatable = false)
    private String proposalSha256;
    @Column(name = "proposal_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String proposalJson;
    @Column(name = "selector_evidence_json", columnDefinition = "TEXT", updatable = false)
    private String selectorEvidenceJson;
    @Column(nullable = false, length = 40)
    private String status = "QUEUED";
    @Column(name = "error_code", length = 100)
    private String errorCode;
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;
    @Column(name = "failure_sha256", length = 64)
    private String failureSha256;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "finished_at")
    private Instant finishedAt;
}
