package cn.har01d.alist_tvbox.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户级设置项(白名单键经 /api/user-settings 读写):value 为回退后的生效值,
 * userLevel 标识是「自己的覆盖值」(true)还是「继承的全局值」(false)。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSettingDto {
    private String name;
    private String value;
    private boolean userLevel;
}
