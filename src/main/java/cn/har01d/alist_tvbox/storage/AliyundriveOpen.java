package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Account;

public class AliyundriveOpen extends Storage {
    public AliyundriveOpen(Account account, String type) {
        super(account, type);
        addAddition("refresh_token", account.getOpenToken());
        addAddition("refresh_token2", account.getRefreshToken());
        addAddition("concurrency", account.getConcurrency());
        addAddition("chunk_size", account.getChunkSize());
        addAddition("root_folder_id", "root");
        addAddition("order_by", "name");
        addAddition("order_direction", "ASC");
        addAddition("drive_type", type);
        addAddition("account_id", account.getId());
        buildAddition();
    }
}
