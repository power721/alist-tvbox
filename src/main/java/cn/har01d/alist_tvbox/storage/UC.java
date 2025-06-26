package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class UC extends Storage {
    public UC(DriverAccount account) {
        super(account, "UC");
        setWebProxy(account.isUseProxy());
        setWebdavPolicy("native_proxy");
        addAddition("cookie", account.getCookie());
        addAddition("root_folder_id", account.getFolder());
        addAddition("concurrency", account.getConcurrency());
        addAddition("order_by", "file_name");
        addAddition("order_direction", "asc");
        buildAddition(account);
    }
}
