package cn.har01d.alist_tvbox.dto;

/**
 * 离线下载配置保存请求。autoDelete/ttlHours/selfShare 为离线自动清理新增,
 * 旧前端/旧测试不传按 null(关闭/默认)处理。
 */
public record OfflineDownloadConfigRequest(boolean enabled, String driverType, Integer accountId,
                                           Boolean autoDelete, Integer ttlHours, Boolean selfShare) {
    public OfflineDownloadConfigRequest(boolean enabled, String driverType, Integer accountId) {
        this(enabled, driverType, accountId, null, null, null);
    }
}
