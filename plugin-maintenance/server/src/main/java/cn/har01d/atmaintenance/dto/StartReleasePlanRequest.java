package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.*;

public record StartReleasePlanRequest(@NotBlank @Size(max = 36) String candidateId,
                                      @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedArtifactSha256,
                                      @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedCandidateSha256,
                                      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,80}") String requestKey) {
}
