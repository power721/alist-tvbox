package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;

public record ScheduleSummary(String sourceId, String sha256, JsonNode policy, Instant nextDueAt,
                              LocalDate budgetDay, int usedAttempts, int consecutiveFailures,
                              boolean suspended, String suspensionReason, String activeRunId,
                              String lastRunId, String lastOutcome) {
}
