package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public record MediaProbeSummary(String id, String sourceId, String artifactSha256, String profileSha256,
                                String policySha256, String status, Instant createdAt, Instant finishedAt,
                                String errorCode, String freshness, List<String> staleInputs,
                                String authority, boolean sourceHealthEligible, JsonNode policy, JsonNode result,
                                JsonNode budget, int issuedRequests) {
}
