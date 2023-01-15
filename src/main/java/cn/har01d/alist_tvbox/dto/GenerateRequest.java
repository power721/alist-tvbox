package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class GenerateRequest {
    private Integer siteId;
    private String path;
    private boolean includeSub;
}
