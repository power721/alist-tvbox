package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/** web 管理端的直播平台 cookie 配置项。 */
@Data
public class PlatformCookieDto {
    private String platform;
    private String name;
    private String cookie;
    /** 提示配置后带来的能力(如 SOOP 登录可看受限房间)。 */
    private String hint;
}
