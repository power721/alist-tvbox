package cn.har01d.atmaintenance.dto;

import java.time.LocalDate;

public record SchedulingSummary(boolean enabled, int dailyLimit, LocalDate budgetDay, int usedAttempts,
                                String boundary) {
}
