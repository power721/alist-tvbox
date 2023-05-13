package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.List;

@Data
public class DoubanDto {
    private String year;
    private String originalName;
    private String alias;
    private String type;
    private Integer episodes;
    List<DoubanData> data;
}
