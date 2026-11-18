package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_source")
@Getter
@Setter
public class MaintenanceSource {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(nullable = false, length = 200)
    private String name;
    @Column(name = "external_id", unique = true, length = 200)
    private String externalId;
    @Column(nullable = false, length = 200)
    private String filename;
    @Column(name = "origin_url", length = 2000)
    private String originUrl;
    @Column(name = "current_artifact_id", length = 36)
    private String currentArtifactId;
    @Column(name = "current_profile_id", length = 36)
    private String currentProfileId;
    @Column(name = "config_fingerprint_salt", length = 64)
    private String configFingerprintSalt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
