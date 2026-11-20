package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Min;

public record StartRepairRequest(@NotBlank String baselineRunId,
                                 @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedArtifactSha256,
                                 @Min(1) Integer maxRounds) {
    public StartRepairRequest(String baselineRunId, String expectedArtifactSha256) {
        this(baselineRunId, expectedArtifactSha256, null);
    }
}
