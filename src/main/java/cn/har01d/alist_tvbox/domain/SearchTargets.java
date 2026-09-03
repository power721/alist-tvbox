package cn.har01d.alist_tvbox.domain;

import java.util.Set;

/**
 * 追剧搜索定向集:候选盘白名单(盘 key,空 = 不限盘,兼容未配置部署)+ 磁力兜底生效时的
 * 离线链接类型({@code magnet}/{@code ed2k})。仅追剧搜索侧使用;入池/探测/换源仍走
 * {@code allowedCandidateDrives}(不含离线类型,离线链接不入池只作兜底候选收割)。
 * <p>
 * 消息 type 口径:网盘为数字串("5")或盘 key("quark"),离线为字面 {@code magnet}/{@code ed2k}。
 */
public record SearchTargets(Set<String> drives, boolean offlineIncluded) {

    public static final SearchTargets UNRESTRICTED = new SearchTargets(Set.of(), false);

    public static SearchTargets of(Set<String> drives, boolean offlineIncluded) {
        return new SearchTargets(Set.copyOf(drives), offlineIncluded);
    }

    public static boolean isOfflineType(String type) {
        return "magnet".equals(type) || "ed2k".equals(type);
    }

    /**
     * 严格门禁(无全局口径的搜索源用,如站点源):网盘按盘白名单(空 = 不限);
     * magnet/ed2k 只看并入开关。
     */
    public boolean allowsType(String type) {
        if (isOfflineType(type)) {
            return offlineIncluded;
        }
        return allowsDrive(type);
    }

    /** 网盘消息门禁:type 可为数字串或盘 key;白名单空 = 不限。 */
    public boolean allowsDrive(String type) {
        if (drives.isEmpty()) {
            return true;
        }
        Integer number = DriveId.toTypeLeniently(type);
        return number != null && drives.contains(DriveId.toDrive(number));
    }

    /**
     * 全局口径门禁(TG 聚合出口用):{@code globalAllowed} 为现状全局 tg.drivers 判定。
     * 盘白名单非空时替换全局口径(防全局配置误杀订阅扩展盘),magnet/ed2k 以订阅开关为准;
     * 白名单空时网盘维持现状,离线类型保留全局既有放行(不收窄现状)。
     */
    public boolean allowsType(String type, boolean globalAllowed) {
        if (isOfflineType(type)) {
            if (!drives.isEmpty()) {
                return offlineIncluded;
            }
            return offlineIncluded || globalAllowed;
        }
        if (!drives.isEmpty()) {
            return allowsDrive(type);
        }
        return globalAllowed;
    }
}
