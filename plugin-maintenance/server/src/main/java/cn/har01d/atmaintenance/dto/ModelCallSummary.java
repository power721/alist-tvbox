package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record ModelCallSummary(String id, String repairId, String requestKey, int attemptNumber,
                               String configurationSha256, String modelName, String requestSha256, int requestBytes,
                               int reservedOutputTokens, String status, String errorCode,
                               Instant createdAt, Instant finishedAt, JsonNode usage, JsonNode proposal,
                               String proposalSha256, boolean autoSubmit, String submissionStatus,
                               String submissionErrorCode, String submissionMethod, String candidateId,
                               JsonNode budgetReservation, JsonNode costEstimate, boolean enhancedDiagnostics,
                               JsonNode responseDiagnostics) {
}
