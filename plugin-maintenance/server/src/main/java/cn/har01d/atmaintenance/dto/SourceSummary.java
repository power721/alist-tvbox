package cn.har01d.atmaintenance.dto;

import java.time.Instant;

public record SourceSummary(String id, String name, String externalId, String filename,
                            String originUrl, String artifactSha256, long revision, Instant createdAt,
                            String profileSha256, SourceCredentialSummary credentials) {
}
