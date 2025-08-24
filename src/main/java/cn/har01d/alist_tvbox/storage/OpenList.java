package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Site;
import org.apache.commons.lang3.StringUtils;

public class OpenList extends Storage {
    public OpenList(Site site) {
        super(site);
        addAddition("root_folder_path", StringUtils.isBlank(site.getFolder()) ? "/" : site.getFolder());
        addAddition("url", site.getUrl());
        addAddition("meta_password", site.getPassword());
        addAddition("token", site.getToken());
        buildAddition();
    }
}
