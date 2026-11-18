package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_model_call", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"repair_id", "request_key"}),
        @UniqueConstraint(columnNames = {"repair_id", "attempt_number"})})
@Getter
@Setter
public class ModelCall {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "repair_id", nullable = false, length = 36, updatable = false)
    private String repairId;
    @Column(name = "request_key", nullable = false, length = 80, updatable = false)
    private String requestKey;
    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;
    @Column(name = "configuration_sha256", nullable = false, length = 64, updatable = false)
    private String configurationSha256;
    @Column(name = "model_name", nullable = false, length = 120, updatable = false)
    private String modelName;
    @Column(name = "request_sha256", nullable = false, length = 64, updatable = false)
    private String requestSha256;
    @Column(name = "request_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String requestJson;
    @Column(name = "request_bytes", nullable = false, updatable = false)
    private int requestBytes;
    @Column(name = "reserved_output_tokens", nullable = false, updatable = false)
    private int reservedOutputTokens;
    @Column(name = "budget_json", columnDefinition = "TEXT", updatable = false)
    private String budgetJson;
    @Column(nullable = false, length = 40)
    private String status = "QUEUED";
    @Column(name = "error_code", length = 100)
    private String errorCode;
    @Column(name = "usage_json", columnDefinition = "TEXT")
    private String usageJson;
    @Column(name = "response_diagnostics_json", columnDefinition = "TEXT")
    private String responseDiagnosticsJson;
    @Column(name = "cost_json", columnDefinition = "TEXT")
    private String costJson;
    @Column(name = "proposal_json", columnDefinition = "TEXT")
    private String proposalJson;
    @Column(name = "auto_submit", nullable = false, updatable = false)
    private boolean autoSubmit;
    @Column(name = "submission_status", nullable = false, length = 40)
    private String submissionStatus = "NOT_REQUESTED";
    @Column(name = "submission_error_code", length = 100)
    private String submissionErrorCode;
    @Column(name = "submission_method", length = 20)
    private String submissionMethod;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "finished_at")
    private Instant finishedAt;
}
