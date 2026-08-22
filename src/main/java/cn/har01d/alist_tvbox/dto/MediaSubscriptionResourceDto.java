package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/** 追剧订阅候选资源(候选池展示)。 */
@Data
public class MediaSubscriptionResourceDto {
    private Integer id;
    private String link;
    private Integer type;
    private String driveName;
    private String source;
    private String title;
    private Integer episodesFound;
    private Integer score;
    /** 挂载生命周期:CANDIDATE(池内)/MOUNTED(已挂载)/RETIRED(已退役)/REJECTED(盘检判死) */
    private String state;
    /** 是否主源(挂在订阅固定路径上的那个 MOUNTED 资源) */
    private boolean primary;
    private Long checkedTime;
    private long createdTime;
}
