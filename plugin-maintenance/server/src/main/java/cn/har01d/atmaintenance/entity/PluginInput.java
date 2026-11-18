package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "maintenance_plugin_input")
@Getter
@Setter
public class PluginInput {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "source_id", nullable = false, length = 36, updatable = false)
    private String sourceId;
    @Column(name = "artifact_sha256", nullable = false, length = 64, updatable = false)
    private String artifactSha256;
    @Column(name = "request_key", nullable = false, unique = true, length = 80, updatable = false)
    private String requestKey;
    @Column(name = "request_sha256", nullable = false, length = 64, updatable = false)
    private String requestSha256;
    @Column(name = "input_sha256", nullable = false, length = 64, updatable = false)
    private String inputSha256;
    @Column(name = "input_base64", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String inputBase64;
    @Column(name = "evidence_sha256", nullable = false, length = 64, updatable = false)
    private String evidenceSha256;
    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String evidence;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
}
