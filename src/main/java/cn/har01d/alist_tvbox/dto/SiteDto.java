package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;

@Data
public class SiteDto {
    @NotEmpty
    private String name;
    @NotEmpty
    private String url;
    private String searchApi;
    private String indexFile;
    private boolean searchable;
    private int order;
}
