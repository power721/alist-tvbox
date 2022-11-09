package cn.har01d.alist_tvbox.tvbox;

import lombok.Data;

@Data
public class GenerateRequest {
    private String site;
    private String path;
    private boolean includeSub;
}
