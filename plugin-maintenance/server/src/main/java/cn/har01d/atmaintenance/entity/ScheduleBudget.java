package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Entity
@Table(name = "maintenance_schedule_budget")
@Getter
@Setter
public class ScheduleBudget {
    @Id
    private int id;
    @Column(name = "budget_day", nullable = false)
    private LocalDate budgetDay;
    @Column(name = "used_attempts", nullable = false)
    private int usedAttempts;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
