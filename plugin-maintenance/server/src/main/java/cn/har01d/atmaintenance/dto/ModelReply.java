package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record ModelReply(RepairProposal proposal, JsonNode usage, String errorCode, JsonNode responseDiagnostics) {
}
