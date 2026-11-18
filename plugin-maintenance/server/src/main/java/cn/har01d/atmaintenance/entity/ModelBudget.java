package cn.har01d.atmaintenance.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Entity
@Table(name = "maintenance_model_budget")
@Getter
@Setter
public class ModelBudget {
    @Id
    private Integer id;
    @Column(name = "budget_day")
    private LocalDate budgetDay;
    @Column(name = "used_calls", nullable = false)
    private int usedCalls;
    @Column(name = "reserved_input_bytes", nullable = false)
    private long reservedInputBytes;
    @Column(name = "reserved_output_tokens", nullable = false)
    private long reservedOutputTokens;
    @Column(name = "reserved_cost_usd", precision = 38, scale = 18)
    private BigDecimal reservedCostUsd;
    @Column(name = "cost_breach_day")
    private LocalDate costBreachDay;
    @Column(name = "active_call_id", length = 36)
    private String activeCallId;
}
