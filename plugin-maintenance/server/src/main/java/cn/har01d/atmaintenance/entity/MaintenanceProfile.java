package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_profile", uniqueConstraints =
        @UniqueConstraint(columnNames = {"source_id", "sha256"}))
@Getter
@Setter
public class MaintenanceProfile {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "source_id", nullable = false, length = 36, updatable = false)
    private String sourceId;
    @Column(nullable = false, length = 64, updatable = false)
    private String sha256;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String snapshotJson;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
