package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_at_deployment", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"plugin_id", "request_key"}),
        @UniqueConstraint(columnNames = "active_plugin_id")})
@Getter
@Setter
public class EmbeddedDeployment {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "plugin_id", nullable = false)
    private int pluginId;
    @Column(name = "active_plugin_id")
    private Integer activePluginId;
    @Column(name = "site_key", nullable = false, length = 200)
    private String siteKey;
    @Column(name = "plan_id", length = 36)
    private String planId;
    @Column(name = "request_key", nullable = false, length = 80)
    private String requestKey;
    @Column(name = "request_sha256", nullable = false, length = 64)
    private String requestSha256;
    @Column(name = "baseline_sha256", nullable = false, length = 64)
    private String baselineSha256;
    @Column(name = "candidate_sha256", nullable = false, length = 64)
    private String candidateSha256;
    @Column(name = "baseline_content", nullable = false, columnDefinition = "TEXT")
    private String baselineContent;
    @Column(name = "candidate_content", nullable = false, columnDefinition = "TEXT")
    private String candidateContent;
    @Column(name = "configuration_sha256", nullable = false, length = 64)
    private String configurationSha256;
    @Column(name = "expected_generation", length = 32)
    private String expectedGeneration;
    @Column(name = "loaded_generation", length = 32)
    private String loadedGeneration;
    @Column(name = "interrupt_playback", nullable = false)
    private boolean interruptPlayback;
    @Column(nullable = false, length = 32)
    private String state = "PREPARED";
    @Column(name = "error_code", length = 80)
    private String errorCode;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = createdAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
