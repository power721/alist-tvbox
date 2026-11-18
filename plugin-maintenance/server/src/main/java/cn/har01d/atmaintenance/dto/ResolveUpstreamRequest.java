package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ResolveUpstreamRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String requestKey,
        @NotNull @Min(0) Long expectedRevision,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedArtifactSha256,
        @NotBlank @Pattern(regexp = "FAST_FORWARD|KEEP_LOCAL") String decision) {
}
