package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record StartRepairRequest(@NotBlank String baselineRunId,
                                 @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedArtifactSha256) {
}
