package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class Local extends Storage {
    public Local(Share share) {
        super(share, "Local");
        setCacheExpiration(0);
        setWebdavPolicy("native_proxy");
        addAddition("root_folder_path", share.getFolderId());
        addAddition("thumbnail", false);
        addAddition("show_hidden", true);
        addAddition("mkdir_perm", "777");
        addAddition("thumb_cache_folder", "");
        buildAddition();
    }
}
