package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class AliyunShare extends Storage {
    public AliyunShare(Share share) {
        super(share.getId(), "AliyunShare", getMountPath(share), share.getTime());
        addAddition("share_id", share.getShareId());
        addAddition("share_pwd", share.getPassword());
        addAddition("root_folder_id", share.getFolderId());
        addAddition("order_by", "name");
        addAddition("order_direction", "ASC");
        buildAddition();
    }
}
