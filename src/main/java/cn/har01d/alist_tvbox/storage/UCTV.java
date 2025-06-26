package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class UCTV extends Storage {
    public UCTV(DriverAccount account, String deviceId) {
        super(account, "UCTV");
        addAddition("refresh_token", account.getToken());
        addAddition("root_folder_id", account.getFolder());
        addAddition("concurrency", account.getConcurrency());
        addAddition("device_id", deviceId);
        buildAddition(account);
    }
}
