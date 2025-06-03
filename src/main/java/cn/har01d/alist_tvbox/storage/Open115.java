package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class Open115 extends Storage {
    public Open115(DriverAccount account) {
        super(account.getId(), "115 Open", getMountPath(account));
        addAddition("refresh_token", account.getToken());
        addAddition("root_folder_id", account.getFolder());
        addAddition("order_by", "file_name");
        addAddition("order_direction", "asc");
        buildAddition();
    }
}
