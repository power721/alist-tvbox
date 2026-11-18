package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "maintenance_upstream")
@Getter
@Setter
public class UpstreamState {
    @Id
    @Column(name = "source_id", length = 36)
    private String sourceId;
    @Column(name = "base_sha256", nullable = false, length = 64)
    private String baseSha256;
    @Column(name = "head_sha256", nullable = false, length = 64)
    private String headSha256;
    @Column(name = "head_observation_json", columnDefinition = "TEXT")
    private String headObservationJson;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
