package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record BindAtRequest(@Pattern(regexp = "[a-f0-9]{64}") String expectedSha256,
                           @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String observationSha256,
                           @Min(1) int pluginId) {
}
