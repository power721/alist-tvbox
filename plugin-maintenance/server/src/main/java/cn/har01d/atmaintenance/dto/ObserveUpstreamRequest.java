package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ObserveUpstreamRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String requestKey,
        @NotNull @Min(0) Long expectedRevision,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedArtifactSha256,
        @NotBlank @Size(max = 1_048_576) String content) {
}
