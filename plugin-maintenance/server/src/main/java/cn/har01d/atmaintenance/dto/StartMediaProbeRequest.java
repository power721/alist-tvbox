package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record StartMediaProbeRequest(
        @Pattern(regexp = "[a-f0-9]{64}") @NotNull String expectedArtifactSha256,
        @NotNull JsonNode player) {
}
