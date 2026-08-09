package cn.har01d.alist_tvbox.dto.playback;

import lombok.Data;

@Data
public class PlaybackTokenDto {
    private Integer id;
    private String name;
    /** 创建时返回完整令牌;列表回显时由服务端脱敏为前后缀。 */
    private String token;
    private long createdTime;
    private long lastUsedAt;
}
