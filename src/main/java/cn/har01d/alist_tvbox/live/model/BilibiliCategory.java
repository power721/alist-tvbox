package cn.har01d.alist_tvbox.live.model;

import lombok.Data;

@Data
public class BilibiliCategory {
    private String id;
    private String name;
    private String pic;
    private String parent_id;
    private String parent_name;
}
