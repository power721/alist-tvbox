package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class Pan123 extends Storage {
    public Pan123(DriverAccount account) {
        super(account.getId(), "123Pan", getMountPath(account));
        addAddition("username", account.getUsername());
        addAddition("password", account.getPassword());
        addAddition("root_folder_id", account.getFolder());
        addAddition("platformType", "android");
        buildAddition();
    }
}
