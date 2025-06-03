package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class UCShare extends Storage {
    public UCShare(Share share) {
        super(share.getId(), "UCShare", getMountPath(share), share.getTime());
        setWebdavPolicy("native_proxy");
        addAddition("share_id", share.getShareId());
        addAddition("share_pwd", share.getPassword());
        addAddition("root_folder_id", share.getFolderId());
        addAddition("order_by", "file_name");
        addAddition("order_direction", "asc");
        buildAddition();
    }
}
