package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.AListAlias;
import cn.har01d.alist_tvbox.util.Utils;

public class Alias extends Storage {
    public Alias(AListAlias alias) {
        super(alias.getId(), "Alias", alias.getPath());
        addAddition("paths", Utils.getAliasPaths(alias.getContent()));
        buildAddition();
    }
}
