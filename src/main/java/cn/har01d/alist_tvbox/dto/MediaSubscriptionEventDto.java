package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/** 追剧订阅事件(时间线展示)。 */
@Data
public class MediaSubscriptionEventDto {
    private Integer id;
    private String type;
    private String detail;
    private long createdTime;
}
