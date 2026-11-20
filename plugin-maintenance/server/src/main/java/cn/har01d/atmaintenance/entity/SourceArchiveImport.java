package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_source_import", uniqueConstraints = @UniqueConstraint(columnNames = "request_key"))
@Getter
@Setter
public class SourceArchiveImport {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "source_id", nullable = false, length = 36, updatable = false)
    private String sourceId;
    @Column(name = "request_key", nullable = false, length = 80, updatable = false)
    private String requestKey;
    @Column(name = "request_sha256", nullable = false, length = 64, updatable = false)
    private String requestSha256;
    @Column(name = "archive_sha256", nullable = false, length = 64, updatable = false)
    private String archiveSha256;
    @Column(name = "archive_base64", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String archiveBase64;
    @Column(name = "original_source_id", nullable = false, length = 36, updatable = false)
    private String originalSourceId;
    @Column(name = "artifact_sha256", nullable = false, length = 64, updatable = false)
    private String artifactSha256;
    @Column(name = "original_profile_sha256", nullable = false, length = 64, updatable = false)
    private String originalProfileSha256;
    @Column(name = "restored_profile_sha256", nullable = false, length = 64, updatable = false)
    private String restoredProfileSha256;
    @Column(name = "config_rebind_required", nullable = false, updatable = false)
    private boolean configRebindRequired;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
}
