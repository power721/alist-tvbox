package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ObserveSourceCredentialsRequest(
        @NotNull @Pattern(regexp = "[a-f0-9]{64}") String expectedArtifactSha256,
        @NotNull @Size(min = 1, max = 64) String probeRunId,
        @NotNull @Size(max = 64) String expectedRevision) {}
