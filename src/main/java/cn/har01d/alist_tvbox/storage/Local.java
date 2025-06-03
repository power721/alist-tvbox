package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class Local extends Storage {
    public Local(Share share) {
        super(share.getId(), "Local", getMountPath(share), share.getTime());
        setWebdavPolicy("native_proxy");
        addAddition("root_folder_id", share.getFolderId());
        addAddition("thumbnail", false);
        addAddition("show_hidden", true);
        addAddition("mkdir_perm", "777");
        addAddition("thumb_cache_folder", "");
        buildAddition();
    }
}
