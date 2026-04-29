package cn.har01d.alist_tvbox.model;

import lombok.Data;

@Data
public class FsInfo {
    private String name;
    private int type;
    private int duration;
    private long size;
    private String thumb;
    private String thumbnail;
    private String modified;
}
