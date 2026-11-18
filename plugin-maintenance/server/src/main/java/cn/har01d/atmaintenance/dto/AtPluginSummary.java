package cn.har01d.atmaintenance.dto;

public record AtPluginSummary(int id, String name, String externalId, boolean enabled,
                              boolean t4Visible, Integer version, String kind, String derivedSiteKey,
                              String sourceLocatorSha256, boolean hasLastError) {
}
