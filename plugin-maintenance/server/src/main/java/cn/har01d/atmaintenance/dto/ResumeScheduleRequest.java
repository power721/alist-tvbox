package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ResumeScheduleRequest(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedSha256) {
}
