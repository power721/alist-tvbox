package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/** 稍后再看条目(web 列表):已追角标服务端算好,前端不再拉订阅表。 */
@Data
public class WatchlistDto {
    private Integer id;
    private String vodId;
    private String title;
    private Integer year;
    private Integer season;
    private String pic;
    private String remarks;
    private String status;
    private long createdTime;
    private boolean subscribed;
}
