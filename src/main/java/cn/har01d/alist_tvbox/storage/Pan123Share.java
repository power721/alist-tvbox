package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class Pan123Share extends Storage {
    public Pan123Share(Share share) {
        super(share, "123PanShare");
        addAddition("share_id", share.getShareId());
        addAddition("receive_code", share.getPassword());
        addAddition("share_pwd", share.getFolderId());
        buildAddition();
    }
}
