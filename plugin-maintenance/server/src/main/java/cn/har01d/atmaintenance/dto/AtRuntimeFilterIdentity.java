package cn.har01d.atmaintenance.dto;

public record AtRuntimeFilterIdentity(int index, String configurationSha256,
                                      String loadState, String sourceSha256) {
}
