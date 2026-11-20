package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Min;

public record StartMaintenanceRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedArtifactSha256,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedConfigurationSha256,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,80}") String requestKey,
        @Min(1) Integer maxRounds) {
    public StartMaintenanceRequest(String expectedArtifactSha256, String expectedConfigurationSha256, String requestKey) {
        this(expectedArtifactSha256, expectedConfigurationSha256, requestKey, null);
    }
}
