package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record ProbeSummary(String id, String sourceId, String artifactSha256, String inspectorSha256,
                           String knowledgeSha256, String status, Instant createdAt, Instant finishedAt,
                           JsonNode result, JsonNode context, String errorCode,
                           String profileSha256, JsonNode profile, String freshness,
                           String executionMode, String runtimeSha256, JsonNode runtime,
                           java.util.List<String> staleInputs, String bindingSha256, JsonNode binding,
                           String triggerKind, String scheduleSha256, JsonNode schedule,
                           java.time.LocalDate scheduleBudgetDay, Integer scheduleOrdinal,
                           String executionPolicySha256, JsonNode executionPolicy, Long sourceRevision) {
}
