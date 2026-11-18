package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddArtifactRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedSha256,
        @NotBlank @Size(max = 1_048_576) String content) {
}
