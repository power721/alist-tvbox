package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class SiteDto {
    private String name;
    private String url;
    private String password;
    private String token;
    private String indexFile;
    private String folder;
    private boolean searchable;
    private boolean disabled;
    private boolean xiaoya;
    private int order;
    private Integer version;
}
