package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.PikPakAccount;

public class PikPak extends Storage {
    public PikPak(PikPakAccount account) {
        super(account);
        addAddition("root_folder_id", "");
        addAddition("platform", account.getPlatform());
        addAddition("refresh_token_method", account.getRefreshTokenMethod());
        addAddition("username", account.getUsername());
        addAddition("password", account.getPassword());
        buildAddition();
    }
}
