package cn.har01d.alist_tvbox.service.offline;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;

public interface OfflineDownloadHandler {
    DriverType getDriverType();

    String ensureOfflineFolder(DriverAccount account);

    TaskResult submitAndWait(DriverAccount account, String url, String folderId);

    QuotaResult getQuota(DriverAccount account);

    record TaskResult(String taskName, String infoHash, boolean folder) {
    }

    record QuotaResult(boolean supported, String displayText) {
        public static QuotaResult unsupported() {
            return new QuotaResult(false, "");
        }
    }
}
