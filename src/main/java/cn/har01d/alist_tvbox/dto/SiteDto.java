package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class SiteDto {
    private String name;
    private String url;
    private String searchApi;
    private String indexFile;
    private boolean searchable;
    private boolean disabled;
    private int order;
}
