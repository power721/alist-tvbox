package cn.har01d.atmaintenance.dto;

import java.time.Instant;

public record UpstreamEventSummary(String id, String sourceId, String operation, String requestSha256,
                                   UpstreamSummary before, UpstreamSummary after, Instant createdAt) {
}
