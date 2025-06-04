package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class BaiduShare2 extends Storage {
    public BaiduShare2(Share share) {
        super(share, "BaiduShare2");
        addAddition("share_id", share.getShareId());
        addAddition("share_pwd", share.getPassword());
        addAddition("root_folder_path", share.getFolderId());
        buildAddition();
    }
}
