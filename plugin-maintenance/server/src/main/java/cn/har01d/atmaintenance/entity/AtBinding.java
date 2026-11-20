package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "maintenance_at_binding", uniqueConstraints =
        @UniqueConstraint(columnNames = {"instance_key", "plugin_id"}))
@Getter
@Setter
public class AtBinding {
    @Id
    @Column(name = "source_id", length = 36)
    private String sourceId;
    @Column(name = "instance_key", nullable = false, length = 64)
    private String instanceKey;
    @Column(name = "plugin_id", nullable = false)
    private int pluginId;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
