package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class Local extends Storage {
    public Local(Share share) {
        super(share, "Local");
        setWebdavPolicy("native_proxy");
        addAddition("root_folder_id", share.getFolderId());
        addAddition("thumbnail", false);
        buildAddition();
    }
}
