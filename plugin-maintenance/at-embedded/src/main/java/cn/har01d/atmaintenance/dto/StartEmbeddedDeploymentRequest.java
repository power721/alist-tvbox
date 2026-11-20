package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StartEmbeddedDeploymentRequest(
        @NotBlank @Size(max = 36) String planId,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedFrozenSha256,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedPluginSha256,
        @Pattern(regexp = "[a-f0-9]{32}") String expectedWorkerGeneration,
        boolean interruptIssuedPlayback,
        @NotBlank @Size(max = 80) String requestKey) {}
