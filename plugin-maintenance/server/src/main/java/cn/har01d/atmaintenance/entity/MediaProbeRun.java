package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_media_probe")
@Getter
@Setter
public class MediaProbeRun {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "source_id", nullable = false, length = 36, updatable = false)
    private String sourceId;
    @Column(name = "artifact_sha256", nullable = false, length = 64, updatable = false)
    private String artifactSha256;
    @Column(name = "profile_sha256", nullable = false, length = 64, updatable = false)
    private String profileSha256;
    @Column(name = "policy_sha256", nullable = false, length = 64, updatable = false)
    private String policySha256;
    @Column(name = "policy_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String policyJson;
    @Column(nullable = false, length = 30)
    private String status = "QUEUED";
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(name = "error_code", length = 100)
    private String errorCode;
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;
    @Column(name = "budget_json", columnDefinition = "TEXT", updatable = false)
    private String budgetJson;
    @Column(name = "issued_requests", nullable = false)
    private int issuedRequests;
}
