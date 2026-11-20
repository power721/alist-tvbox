package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record SourceImportPreview(
        String archiveSha256, String originalSourceId, String originalName, String artifactSha256,
        String profileSha256, JsonNode profile, int fileCount, int archiveBytes, int expandedBytes,
        boolean configRebindRequired, boolean reportPresent, String authority, String boundary,
        String archiveKind, String candidateSha256) {
}
