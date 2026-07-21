package cn.har01d.alist_tvbox.model;

public record StoredConfig(boolean enabled, String driverType, Integer accountId, String offlineFolderId) {
}
