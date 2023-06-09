package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class DoubanData {
    private String name;
    private String genre;
    private String description;
    private String language;
    private String country;
    private String year;
    private String poster;
    private Integer episodes;
}
