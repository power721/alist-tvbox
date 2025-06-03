package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class QuarkTV extends Storage {
    public QuarkTV(DriverAccount account, String deviceId) {
        super(account.getId(), "QuarkTV", getMountPath(account));
        addAddition("refresh_token", account.getToken());
        addAddition("root_folder_id", account.getFolder());
        addAddition("device_id", deviceId);
        buildAddition();
    }
}
