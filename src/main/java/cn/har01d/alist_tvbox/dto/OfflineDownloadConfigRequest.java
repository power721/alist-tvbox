package cn.har01d.alist_tvbox.dto;

public record OfflineDownloadConfigRequest(boolean enabled, String driverType, Integer accountId) {
}
