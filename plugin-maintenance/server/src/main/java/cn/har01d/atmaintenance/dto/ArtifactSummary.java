package cn.har01d.atmaintenance.dto;

import java.time.Instant;

public record ArtifactSummary(String id, String sha256, String parentSha256, Instant createdAt) {
}
