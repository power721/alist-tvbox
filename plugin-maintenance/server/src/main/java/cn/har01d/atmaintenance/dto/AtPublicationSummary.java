package cn.har01d.atmaintenance.dto;

import java.time.Instant;

public record AtPublicationSummary(String id, String candidateId, String mode, String state,
        Integer originalPluginId, Integer publishedPluginId, String pluginName, String version,
        String candidateSha256, String baselineSha256, String errorCode, Instant createdAt) {
}
