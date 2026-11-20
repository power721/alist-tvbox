package cn.har01d.atmaintenance.dto;

public record ImportedCandidateSummary(
        String importId, String sourceId, String archiveSha256, String originalCandidateId,
        String originalModelCallId, String baselineArtifactSha256, String candidateSha256,
        String authority) {
}
