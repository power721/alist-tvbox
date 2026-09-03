package cn.har01d.alist_tvbox.service.offline;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;

public interface OfflineDownloadHandler {
    DriverType getDriverType();

    String ensureOfflineFolder(DriverAccount account);

    TaskResult submitAndWait(DriverAccount account, String url, String folderId);

    /** 定向等待版(追剧磁力提交用):waitSeconds 秒内轮询完成,超时抛「未在N秒内完成」;
     *  手动离线下载走三参默认等待(115=10 秒/迅雷、光鸭=30 秒),行为不变。 */
    default TaskResult submitAndWait(DriverAccount account, String url, String folderId, int waitSeconds) {
        return submitAndWait(account, url, folderId);
    }

    QuotaResult getQuota(DriverAccount account);

    record TaskResult(String taskName, String infoHash, boolean folder) {
    }

    record QuotaResult(boolean supported, String displayText) {
        public static QuotaResult unsupported() {
            return new QuotaResult(false, "");
        }
    }
}
