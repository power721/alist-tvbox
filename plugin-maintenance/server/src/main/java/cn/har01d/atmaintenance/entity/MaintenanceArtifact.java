package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_artifact", uniqueConstraints =
        @UniqueConstraint(columnNames = {"source_id", "sha256"}))
@Getter
@Setter
public class MaintenanceArtifact {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "source_id", nullable = false, length = 36, updatable = false)
    private String sourceId;
    @Column(nullable = false, length = 64, updatable = false)
    private String sha256;
    @Column(name = "parent_sha256", length = 64, updatable = false)
    private String parentSha256;
    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String body;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
