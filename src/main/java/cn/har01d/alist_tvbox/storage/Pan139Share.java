package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class Pan139Share extends Storage {
    public Pan139Share(Share share) {
        super(share.getId(), "Yun139Share", getMountPath(share), share.getTime());
        addAddition("share_id", share.getShareId());
        addAddition("receive_code", share.getPassword());
        addAddition("share_pwd", share.getFolderId());
        buildAddition();
    }
}
