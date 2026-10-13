package cn.har01d.alist_tvbox.dto.playback;

import lombok.Data;

/**
 * 播放记录的同步分区选项(按安全 Token 筛选)。
 * scope 为 null 表示 uid 级(全局互通);筛选参数沿用订阅 token 的 "-" 占位惯例表示该分区。
 */
@Data
public class PlaybackScopeDto {
    private String scope;
    /** 服务端算好的展示名:用户 xxx / 订阅 xxx · 订阅 #id / 脱敏 token;uid 级为「全局互通」。 */
    private String label;
}
