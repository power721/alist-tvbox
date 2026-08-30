package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/** 追剧订阅候选资源(候选池展示)。 */
@Data
public class MediaSubscriptionResourceDto {
    private Integer id;
    private String link;
    /** 分享提取码(网盘分享页需要时随名称一并展示) */
    private String password;
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
    /** 手动钉选:换源候选序置顶、归属复核豁免(用户否决自动换源) */
    private boolean pinned;
    private Long checkedTime;
    private long createdTime;
}
