package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class BaiduNetdisk extends Storage {
    public BaiduNetdisk(DriverAccount account) {
        super(account, "BaiduNetdisk");
        addAddition("refresh_token", account.getToken());
        addAddition("root_folder_path", account.getFolder());
        buildAddition();
    }
}
