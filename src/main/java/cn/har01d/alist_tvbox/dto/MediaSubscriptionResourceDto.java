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
    private String validity;
    private boolean active;
    private Long checkedTime;
    private long createdTime;
}
