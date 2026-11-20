package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StartAtRuntimeActivationRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedPluginSha256,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedConfigurationSha256,
        @Pattern(regexp = "[a-f0-9]{32}") String expectedWorkerGeneration,
        @NotBlank @Size(max = 80) String requestKey,
        boolean interruptIssuedPlayback) {}
