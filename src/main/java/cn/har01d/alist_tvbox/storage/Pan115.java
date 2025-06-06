package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class Pan115 extends Storage {
    public Pan115(DriverAccount account) {
        super(account, "115 Cloud");
        setWebProxy(account.isUseProxy());
        setWebdavPolicy("native_proxy");
        addAddition("cookie", account.getCookie());
        addAddition("qrcode_token", account.getToken());
        addAddition("root_folder_id", account.getFolder());
        addAddition("concurrency", account.getConcurrency());
        addAddition("page_size", 1000);
        addAddition("limit_rate", 2);
        buildAddition();
    }
}
