package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class Open115 extends Storage {
    public Open115(DriverAccount account) {
        super(account, "115 Open");
        addAddition("refresh_token", account.getToken());
        addAddition("root_folder_id", account.getFolder());
        addAddition("concurrency", account.getConcurrency());
        addAddition("order_by", "file_name");
        addAddition("order_direction", "asc");
        buildAddition();
    }
}
