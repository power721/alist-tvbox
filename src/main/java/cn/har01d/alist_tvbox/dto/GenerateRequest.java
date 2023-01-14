package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class GenerateRequest {
    private String site;
    private String path;
    private boolean includeSub;
}
