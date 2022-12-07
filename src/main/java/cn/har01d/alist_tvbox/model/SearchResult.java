package cn.har01d.alist_tvbox.model;

import lombok.Data;

@Data
public class SearchResult {
    private boolean is_dir;
    private String name;
    private String parent = "";
    private long size;
    private int type;
}
