package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.*;

public record LocalReleaseRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedFrozenSha256,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedArtifactSha256,
        @NotNull @PositiveOrZero Long expectedSourceRevision) {}
