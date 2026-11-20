package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record AtBindingSummary(String sourceId, String sha256, JsonNode snapshot, boolean connectionMatches) {
}
