package cn.har01d.atmaintenance.dto;

import java.time.Instant;

public record SourceImportSummary(
        String id, String sourceId, String archiveSha256, String requestSha256, String originalSourceId,
        String artifactSha256, String originalProfileSha256, String restoredProfileSha256,
        boolean configRebindRequired, Instant createdAt, String authority) {
}
