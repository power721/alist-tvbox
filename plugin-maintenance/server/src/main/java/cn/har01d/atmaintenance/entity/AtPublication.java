package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_at_publication")
@Getter
@Setter
public class AtPublication {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(nullable = false, unique = true, length = 80)
    private String requestKey;
    @Column(nullable = false, length = 64)
    private String requestSha256;
    @Column(nullable = false, length = 36)
    private String candidateId;
    @Column(nullable = false, length = 64)
    private String instanceKey;
    @Column(nullable = false, length = 20)
    private String mode;
    private Integer originalPluginId;
    private Integer publishedPluginId;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String targetPath;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String beforePluginJson;
    @Column(nullable = false, length = 200)
    private String pluginName;
    @Column(nullable = false, length = 50)
    private String deliveryVersion;
    @Column(nullable = false, length = 64)
    private String candidateSha256;
    @Column(nullable = false, length = 64)
    private String baselineSha256;
    @Column(nullable = false, length = 30)
    private String state = "PREPARED";
    @Column(length = 100)
    private String errorCode;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
