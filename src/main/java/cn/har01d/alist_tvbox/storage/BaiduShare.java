package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;

public class BaiduShare extends Storage {
    public BaiduShare(Share share) {
        super(share, "BaiduShare");
        addAddition("surl", share.getShareId());
        addAddition("pwd", share.getPassword());
        addAddition("BDUSS", share.getCookie());
        addAddition("root_folder_path", share.getFolderId());
        buildAddition();
    }
}
