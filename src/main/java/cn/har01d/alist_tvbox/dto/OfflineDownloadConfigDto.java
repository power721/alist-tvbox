package cn.har01d.alist_tvbox.dto;

public record OfflineDownloadConfigDto(boolean enabled, String driverType, Integer accountId, String folder) {
}
