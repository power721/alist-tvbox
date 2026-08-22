package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/**
 * 关注直播间。入参只需 platform + roomId(后端自动补全房间信息),出参含开播状态。
 */
@Data
public class LiveFollowDto {
    private String platform;
    private String roomId;
    private String roomName;
    private String anchorName;
    private String cover;
    /** 官方直播间页地址,平台无法识别时为 null */
    private String roomUrl;
    /** null=未知(刷新失败),true=开播中,false=未开播 */
    private Boolean live;
    private Long followedTime;
}
