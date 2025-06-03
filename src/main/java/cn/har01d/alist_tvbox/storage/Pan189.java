package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class Pan189 extends Storage {
    public Pan189(DriverAccount account) {
        super(account, "189CloudPC");
        addAddition("username", account.getUsername());
        addAddition("password", account.getPassword());
        addAddition("validate_code", account.getToken());
        addAddition("root_folder_id", account.getFolder());
        addAddition("order_by", "filename");
        addAddition("order_direction", "asc");
        addAddition("type", "personal");
        addAddition("upload_method", "stream");
        addAddition("upload_thread", "3");
        addAddition("rapid_upload", true);
        buildAddition();
    }
}
