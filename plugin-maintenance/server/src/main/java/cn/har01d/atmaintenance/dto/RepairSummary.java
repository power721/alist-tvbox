package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public record RepairSummary(String id, String sourceId, String baselineRunId, String baselineArtifactSha256, String faultSha256,
                            String status, String stopCode, int usedRounds, int maxRounds,
                            String activeCandidateId, Instant createdAt, JsonNode frozen,
                            String freshness, List<String> staleInputs) {
}
