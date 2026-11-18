package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.*;

/** Global and source reservations are serialized by the global row. */
@Entity
@Table(name = "maintenance_media_budget")
@Getter
@Setter
public class MediaBudget {
    @Id
    @Column(length = 50)
    private String id;
    @Column(name = "budget_day")
    private LocalDate budgetDay;
    @Column(name = "used_probes", nullable = false)
    private int usedProbes;
    @Column(name = "reserved_requests", nullable = false)
    private long reservedRequests;
    @Column(name = "reserved_body_bytes", nullable = false)
    private long reservedBodyBytes;
    @Column(name = "next_due_at")
    private Instant nextDueAt;
    @Column(name = "last_observed_at")
    private Instant lastObservedAt;
}
