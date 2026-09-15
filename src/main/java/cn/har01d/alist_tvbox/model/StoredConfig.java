package cn.har01d.alist_tvbox.model;

/**
 * 离线下载配置(Setting {@code offline_download_config} 的 JSON 形态)。
 * <p>
 * autoDelete/ttlHours/selfShare 为 V54 离线清理新增(docs/pan115-offline-auto-delete-design.md):
 * 旧 JSON 无这三个字段,反序列化为 null 后按关闭/默认处理,天然向后兼容。
 */
public record StoredConfig(boolean enabled, String driverType, Integer accountId, String offlineFolderId,
                           Boolean autoDelete, Integer ttlHours, Boolean selfShare) {

    /** 旧调用点兼容构造(清理三项取默认关闭)。 */
    public StoredConfig(boolean enabled, String driverType, Integer accountId, String offlineFolderId) {
        this(enabled, driverType, accountId, offlineFolderId, null, null, null);
    }

    public boolean autoDeleteEnabled() {
        return Boolean.TRUE.equals(autoDelete);
    }

    public boolean selfShareEnabled() {
        return Boolean.TRUE.equals(selfShare);
    }

    /** 通用入口 TTL(小时):未配置或非法回落 24。 */
    public int ttlHoursOrDefault() {
        return ttlHours != null && ttlHours > 0 ? ttlHours : 24;
    }
}
