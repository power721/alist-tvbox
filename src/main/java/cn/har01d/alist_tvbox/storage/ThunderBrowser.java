package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class ThunderBrowser extends Storage {
    public ThunderBrowser(DriverAccount account) {
        super(account, "ThunderBrowser");
        addAddition("username", account.getUsername());
        addAddition("password", account.getPassword());
        addAddition("safe_password", account.getSafePassword());
        addAddition("root_folder_id", account.getFolder());
        addAddition("concurrency", account.getConcurrency());
        addAddition("remove_way", "delete");
        buildAddition();
    }
}
