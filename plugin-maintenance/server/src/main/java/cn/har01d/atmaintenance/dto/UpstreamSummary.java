package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record UpstreamSummary(String sourceId, long revision, String baseSha256, String headSha256,
                              String effectiveSha256, String status, Instant updatedAt,
                              String authority, boolean runtimeApplied, boolean publishEligible,
                              JsonNode headObservation) {
    public UpstreamSummary {
        if (headObservation != null && headObservation.isNull()) headObservation = null;
    }
}
