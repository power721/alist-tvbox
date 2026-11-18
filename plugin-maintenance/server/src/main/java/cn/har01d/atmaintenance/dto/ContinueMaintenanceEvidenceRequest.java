package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ContinueMaintenanceEvidenceRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedArtifactSha256,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedConfigurationSha256,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedFaultSha256,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedCandidateSha256,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedEvidenceSha256,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,80}") String requestKey) {
}
