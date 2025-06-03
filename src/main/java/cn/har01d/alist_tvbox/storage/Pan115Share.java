package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class Pan115Share extends Storage {
    public Pan115Share(Share share) {
        super(share.getId(), "115 Share", getMountPath(share), share.getTime());
        addAddition("share_code", share.getShareId());
        addAddition("receive_code", share.getPassword());
        addAddition("root_folder_id", share.getFolderId());
        addAddition("page_size", 1500);
        addAddition("limit_rate", 1);
        buildAddition();
    }
}
