package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ResumeOriginSearchRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedFaultSha256,
        @NotBlank @Pattern(regexp = "[a-f0-9-]{36}") String expectedSearchTaskId) {
}
