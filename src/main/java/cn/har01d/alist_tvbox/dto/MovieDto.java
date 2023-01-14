package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class MovieDto {
    private Integer siteId;
    private String path;
    private String name;
    private String cover;
    private String category;
    private String actor;
    private String director;
    private String lang;
    private String area;
    private Integer year;
    private String content;
}
