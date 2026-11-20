package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record PluginInputSummary(String id, String sourceId, String artifactSha256,
                                 String evidenceSha256, JsonNode evidence, Instant createdAt) {
}
