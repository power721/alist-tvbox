package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class Pan139 extends Storage {
    public Pan139(DriverAccount account) {
        super(account, "139Yun");
        addAddition("authorization", account.getToken());
        addAddition("root_folder_id", account.getFolder());
        addAddition("concurrency", account.getConcurrency());
        addAddition("type", "personal_new");
        buildAddition(account);
    }
}
