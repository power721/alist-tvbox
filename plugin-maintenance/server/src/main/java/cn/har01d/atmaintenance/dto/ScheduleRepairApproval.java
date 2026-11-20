package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Min;

public record ScheduleRepairApproval(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedConfigurationSha256,
        @Min(1) Integer maxRounds) {
    public ScheduleRepairApproval(String expectedConfigurationSha256) {
        this(expectedConfigurationSha256, null);
    }
}
