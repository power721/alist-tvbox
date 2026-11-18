package cn.har01d.atmaintenance.dto;

import java.time.Instant;

public record EmbeddedDeploymentSummary(String id, int pluginId, String siteKey, String planId,
        String baselineSha256, String candidateSha256, String state, String loadedGeneration,
        String errorCode, Instant createdAt, Instant updatedAt) {}
