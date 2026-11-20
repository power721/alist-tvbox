package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public record ReleasePlanSummary(String id, String sourceId, String candidateId, String requestKey,
                                 String requestSha256, String baselineArtifactSha256, String candidateSha256,
                                 String frozenSha256, String status, Instant createdAt, Instant cancelledAt,
                                 String freshness, List<String> staleInputs, List<String> blockers,
                                 boolean publishEligible, boolean localApplyEligible, boolean localRollbackEligible,
                                 long sourceRevision, JsonNode localReceipt) {
}
