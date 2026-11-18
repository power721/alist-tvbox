package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.Pattern;

public record RollbackEmbeddedDeploymentRequest(
        @Pattern(regexp = "[a-f0-9]{32}") String expectedWorkerGeneration,
        boolean interruptIssuedPlayback) {}
