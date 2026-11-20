package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_upstream_event", uniqueConstraints =
        @UniqueConstraint(columnNames = {"source_id", "request_key"}))
@Getter
@Setter
public class UpstreamEvent {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "source_id", nullable = false, length = 36, updatable = false)
    private String sourceId;
    @Column(name = "request_key", nullable = false, length = 80, updatable = false)
    private String requestKey;
    @Column(name = "request_sha256", nullable = false, length = 64, updatable = false)
    private String requestSha256;
    @Column(name = "receipt_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String receiptJson;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
