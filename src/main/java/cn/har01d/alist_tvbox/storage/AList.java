package cn.har01d.alist_tvbox.storage;

import org.apache.commons.lang3.StringUtils;

import cn.har01d.alist_tvbox.entity.Site;

public class AList extends Storage {
    public AList(Site site) {
        super(site);
        addAddition("root_folder_path", StringUtils.isBlank(site.getFolder()) ? "/" : site.getFolder());
        addAddition("url", site.getUrl());
        if (site.getVersion() == 3) {
            addAddition("meta_password", site.getPassword());
            addAddition("token", site.getToken());
        } else {
            addAddition("access_token", site.getToken());
        }
        buildAddition();
    }
}
