package cn.har01d.alist_tvbox.model;

import lombok.Data;

@Data
public class FsInfoV2 {
    private String name;
    private int type;
    private long size;
    private String thumbnail;
    private String driver;
    private String url;
}
