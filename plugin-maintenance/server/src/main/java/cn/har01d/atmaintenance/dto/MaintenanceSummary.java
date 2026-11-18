package cn.har01d.atmaintenance.dto;

public record MaintenanceSummary(String runId, String sourceId, String artifactSha256,
                                 String status, String repairId, String stopCode) {
}
