package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateRepairRoundLimitRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedFaultSha256,
        @Min(1) int expectedMaxRounds,
        @Min(1) int maxRounds) {
}
