package cn.har01d.atmaintenance.dto;

import java.time.*;

public record MediaBudgetSummary(String scope, LocalDate budgetDay, int usedProbes,
                                 long reservedRequests, long reservedBodyBytes,
                                 Instant nextDueAt, Instant lastObservedAt) {
}
