package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class ThunderShare extends Storage {
    public ThunderShare(Share share) {
        super(share, "ThunderShare");
        addAddition("share_id", share.getShareId());
        addAddition("share_pwd", share.getPassword());
        addAddition("root_folder_id", share.getFolderId());
        buildAddition();
    }
}
