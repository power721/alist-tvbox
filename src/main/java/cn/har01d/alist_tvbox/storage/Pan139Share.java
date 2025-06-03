package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class Pan139Share extends Storage {
    public Pan139Share(Share share) {
        super(share, "Yun139Share");
        addAddition("share_id", share.getShareId());
        addAddition("share_pwd", share.getPassword());
        addAddition("root_folder_id", share.getFolderId());
        buildAddition();
    }
}
