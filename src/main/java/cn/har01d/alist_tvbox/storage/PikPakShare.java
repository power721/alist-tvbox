package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class PikPakShare extends Storage {
    public PikPakShare(Share share) {
        super(share.getId(), "PikPakShare", getMountPath(share), share.getTime());
        addAddition("share_id", share.getShareId());
        addAddition("share_pwd", share.getPassword());
        addAddition("root_folder_id", share.getFolderId());
        addAddition("platform", "pc");
        buildAddition();
    }
}
