package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "maintenance_automation_task")
@Getter
@Setter
public class MaintenanceAutomationTask {
    @Id @Column(length = 80)
    private String id;
    @Column(nullable = false, length = 30)
    private String kind;
    @Column(nullable = false, length = 36)
    private String ownerId;
    @Column(nullable = false, length = 36)
    private String sourceId;
    @Column(nullable = false, length = 40)
    private String state = "QUEUED";
    @Column(nullable = false, columnDefinition = "TEXT")
    private String contextJson = "{}";
    private String errorCode;
    @Column(nullable = false)
    private int attempts;
    @Column(nullable = false)
    private Instant nextAttemptAt = Instant.now();
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
