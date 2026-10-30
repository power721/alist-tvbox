package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class Pan123 extends Storage {
    public Pan123(DriverAccount account) {
        super(account, "123Pan");
        addAddition("username", account.getUsername());
        addAddition("password", account.getPassword());
        // 持有 token 时注入,Go 侧 Init 直接使用免登录;401 重登的新 token 由 Go 回写本列
        addAddition("accesstoken", account.getToken());
        addAddition("root_folder_id", account.getFolder());
        addAddition("platformType", "android");
        buildAddition();
    }
}
