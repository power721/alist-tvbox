package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class Pan115Share extends Storage {
    public Pan115Share(Share share) {
        super(share, "115 Share");
        addAddition("share_id", share.getShareId());
        addAddition("share_pwd", share.getPassword());
        addAddition("root_folder_id", share.getFolderId());
        addAddition("page_size", 1500);
        addAddition("limit_rate", 2);
        buildAddition();
    }
}
