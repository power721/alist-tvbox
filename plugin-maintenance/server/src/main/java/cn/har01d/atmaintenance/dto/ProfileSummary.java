package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record ProfileSummary(String id, String sourceId, String sha256, JsonNode snapshot, Instant createdAt) {
}
