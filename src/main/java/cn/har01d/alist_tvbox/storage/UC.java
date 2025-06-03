package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class UC extends Storage {
    public UC(DriverAccount account) {
        super(account, "UC");
        setWebProxy(account.isUseProxy());
        setWebdavPolicy("native_proxy");
        addAddition("cookie", account.getCookie());
        addAddition("token", "Nk27FcCv6q1eo6rXz8QHR/nIG6qLA3jh7KdL+agFgcOvww==");
        addAddition("root_folder_id", account.getFolder());
        addAddition("order_by", "file_name");
        addAddition("order_direction", "asc");
        buildAddition();
    }
}
