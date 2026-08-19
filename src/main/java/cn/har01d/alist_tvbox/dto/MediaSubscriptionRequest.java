package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/** 创建/更新追剧订阅的请求体。 */
@Data
public class MediaSubscriptionRequest {
    private String name;
    private String keyword;
    private Integer season;
    private Integer doubanId;
    /** 元数据平台与条目 id(douban/tmdb/bangumi,§4.8) */
    private String metaProvider;
    private String metaId;
    private Integer expectedEpisodes;
    /** P0 仅实现 FOLLOW(挂载+自动换源);TRANSFER(自动转存)为 P2 */
    private String mode;
    private Integer accountId;
    /** 多网盘转存目标(TRANSFER 模式,可多选) */
    private java.util.List<Integer> accountIds;
    private Integer checkIntervalHours;
    private MediaSubscriptionFilter filter;
}
