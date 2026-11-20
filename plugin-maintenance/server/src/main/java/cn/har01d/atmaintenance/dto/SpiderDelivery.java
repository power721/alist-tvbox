package cn.har01d.atmaintenance.dto;

public record SpiderDelivery(String pluginName, String version, String filename, String archiveFilename,
                             String entryPath, String candidateSha256, int iteration, boolean verified) {
}
