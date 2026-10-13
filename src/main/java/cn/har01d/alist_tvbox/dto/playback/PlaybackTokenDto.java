package cn.har01d.alist_tvbox.dto.playback;

import lombok.Data;

@Data
public class PlaybackTokenDto {
    private Integer id;
    private String name;
    /** 创建时返回完整令牌;列表回显时由服务端脱敏为前后缀。 */
    private String token;
    /** 关联的安全 Token 分区:null=uid 级(全局互通);非空=vod token 或 token/订阅id。 */
    private String syncScope;
    /** 分区展示名(用户名/订阅名/脱敏 token);null 时前端显示「全局互通」。 */
    private String syncScopeLabel;
    private long createdTime;
    private long lastUsedAt;
}
