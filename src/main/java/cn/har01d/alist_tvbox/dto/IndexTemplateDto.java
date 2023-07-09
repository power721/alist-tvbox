package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class IndexTemplateDto {
    private Integer siteId;
    private String name;
    private String data;
    private boolean includeFiles;
    private int sleep = 2000;
}
