package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_release_plan", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"source_id", "request_key"}),
        @UniqueConstraint(columnNames = "active_source_id")})
@Getter
@Setter
public class ReleasePlan {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "source_id", nullable = false, length = 36, updatable = false)
    private String sourceId;
    @Column(name = "candidate_id", nullable = false, length = 36, updatable = false)
    private String candidateId;
    @Column(name = "request_key", nullable = false, length = 80, updatable = false)
    private String requestKey;
    @Column(name = "request_sha256", nullable = false, length = 64, updatable = false)
    private String requestSha256;
    @Column(name = "baseline_artifact_sha256", nullable = false, length = 64, updatable = false)
    private String baselineArtifactSha256;
    @Column(name = "candidate_sha256", nullable = false, length = 64, updatable = false)
    private String candidateSha256;
    @Column(name = "frozen_sha256", nullable = false, length = 64, updatable = false)
    private String frozenSha256;
    @Column(name = "frozen_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String frozenJson;
    @Column(nullable = false, length = 40)
    private String status = "AWAITING_RUNTIME_VALIDATION";
    @Column(name = "active_source_id", length = 36)
    private String activeSourceId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "local_receipt_json", columnDefinition = "TEXT")
    private String localReceiptJson;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
