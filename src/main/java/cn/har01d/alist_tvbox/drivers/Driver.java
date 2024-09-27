package cn.har01d.alist_tvbox.drivers;

import java.util.List;

public interface Driver {
    DriverType getType();

    List<DriverFile> list(String id);

    DriverLink link(String id);

    void delete(String id);
}
