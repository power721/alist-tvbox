package cn.har01d.atmaintenance.dto;

public record AutomaticRepairSummary(String repairId, String status, String configurationSha256, String stopCode,
                                     boolean enhancedDiagnostics) {
    public AutomaticRepairSummary(String repairId, String status, String configurationSha256, String stopCode) {
        this(repairId, status, configurationSha256, stopCode, false);
    }
}
