package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record RepairCandidateSummary(String id, String repairId, int roundNumber, String sha256,
                                     String proposalSha256, String status, String errorCode,
                                     Instant createdAt, Instant finishedAt, JsonNode proposal, JsonNode result,
                                     String modelCallId, JsonNode selectorEvidence, String sourceImportId) {
}
