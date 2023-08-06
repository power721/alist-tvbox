package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class Storage<T> {
    private int id;
    private String mount_path;
    private String driver;
    private String status;
    private String remark;
    private int order;
    private int cache_expiration;
    private boolean disabled;
    private boolean enable_sign;
    private boolean web_proxy;
    private Instant modified;
    private T addition;
    private String order_by;
    private String order_direction;
    private String extract_folder;
    private String webdav_policy;
    private String down_proxy_url;
}
