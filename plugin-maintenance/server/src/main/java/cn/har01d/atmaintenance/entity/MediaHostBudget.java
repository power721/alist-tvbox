package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "maintenance_media_host_budget")
@Getter
@Setter
public class MediaHostBudget {
    @Id
    @Column(name = "host_sha256", length = 64)
    private String hostSha256;
    @Column(name = "last_run_id", nullable = false, length = 36)
    private String lastRunId;
    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;
}
