package cn.har01d.alist_tvbox.dto;

/** 离线下载配置回显(autoDelete/ttlHours/selfShare 为离线自动清理新增,旧调用点走兼容构造)。 */
public record OfflineDownloadConfigDto(boolean enabled, String driverType, Integer accountId, String accountName,
                                       String folder, Boolean autoDelete, Integer ttlHours, Boolean selfShare) {
    public OfflineDownloadConfigDto(boolean enabled, String driverType, Integer accountId, String accountName,
                                    String folder) {
        this(enabled, driverType, accountId, accountName, folder, null, null, null);
    }
}
