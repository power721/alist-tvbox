package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;
import org.apache.commons.lang3.StringUtils;

public class GuangYaPanShare extends Storage {
    public GuangYaPanShare(Share share) {
        super(share, "GuangYaPanShare");
        addAddition("share_id", share.getShareId());
        if (StringUtils.isNotBlank(share.getCookie())) {
            addAddition("device_id", share.getCookie().trim());
        }
        addAddition("page_size", 200);
        addAddition("order_by", 0);
        addAddition("sort_type", 0);
        buildAddition();
    }
}
