package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class Versions {
    private String app;
    private String alist;
    private String docker;
    private String index;
    private String movie;
    private String cachedMovie;
    private String changelog;
}
