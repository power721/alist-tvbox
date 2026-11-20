package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "maintenance_probe_schedule")
@Getter
@Setter
public class ProbeSchedule {
    @Id
    @Column(name = "source_id", length = 36)
    private String sourceId;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(name = "policy_json", nullable = false, columnDefinition = "TEXT")
    private String policyJson;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;
    @Column(name = "budget_day", nullable = false)
    private LocalDate budgetDay;
    @Column(name = "used_attempts", nullable = false)
    private int usedAttempts;
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;
    @Column(nullable = false)
    private boolean suspended;
    @Column(name = "suspension_reason", length = 100)
    private String suspensionReason;
    @Column(name = "active_run_id", length = 36)
    private String activeRunId;
    @Column(name = "last_run_id", length = 36)
    private String lastRunId;
    @Column(name = "last_outcome", length = 100)
    private String lastOutcome;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
