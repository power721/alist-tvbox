package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class Video {
    private int id;
    private String name;
    private String title;
    private String path;
    private String time;
    private String url;
    private Long size;
    private Integer rating;
}
