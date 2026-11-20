package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "maintenance_model_consent")
@Getter
@Setter
public class ModelSourceConsent {
    @Id
    @Column(name = "source_id", length = 36)
    private String sourceId;
    @Column(name = "artifact_sha256", length = 64)
    private String artifactSha256;
}
