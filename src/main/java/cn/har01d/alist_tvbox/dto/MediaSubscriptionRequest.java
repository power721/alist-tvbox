package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/** 创建/更新追剧订阅的请求体。 */
@Data
public class MediaSubscriptionRequest {
    private String name;
    private String keyword;
    /** 自定义搜索词(换行/逗号分隔,至多 5 个,空 = 清除):主搜索词以外的额外召回词(英文名/别名/写法差异) */
    private String customKeywords;
    private Integer season;
    /** 季起始集号(≤0 = 清除):本季第 1 集对应全剧第 N 集;资源季内编号而官方连续编号时用 */
    private Integer seasonStartEpisode;
    private Integer doubanId;
    /** 元数据平台与条目 id(douban/tmdb/bangumi,§4.8) */
    private String metaProvider;
    private String metaId;
    private Integer expectedEpisodes;
    /** 手动锁定总集数(≤0 = 清除,跟随官方):官方总集数不可信时(桥接污染/反复横跳)的逃生舱,
     *  生效后缺集/完结/展示分母以此为准 */
    private Integer manualTotalEpisodes;
    /** P0 仅实现 FOLLOW(挂载+自动换源);TRANSFER(自动转存)为 P2 */
    private String mode;
    private Integer accountId;
    /** 多网盘转存目标(TRANSFER 模式,可多选) */
    /** 转存目标 id:"pan:{id}"(网盘账号)/"ali:{id}"(阿里独立账号表);裸数字兼容为 pan */
    private java.util.List<String> accountIds;
    /** 显式允许跨网盘转存(默认仅同盘,AList 秒传配置允许的方向除外) */
    private Boolean crossDrive;
    /** 磁力兜底(仅转存模式生效):补缺穷尽后用磁力经离线下载补集,前置=网盘账号配置已开启离线下载 */
    private Boolean magnetOffline;
    private Integer checkIntervalHours;
    /** 手动播出时刻校正("HH:mm",空=自动;仅日期无时刻的剧按 20:00 兜底,可按实际排播改写) */
    private String customAirClock;
    /** 手动更新日(ISO 周一=1..周日=7,空 = 清除):欧美周播剧/追番固定周几更新,官方日程缺失/
     *  不可信时指定 —— 巡检只落配置周几的播出时刻 */
    private java.util.List<Integer> airWeekdays;
    /** 主网盘覆盖(分享类型码,空 = 跟随全局 msub_main_drives) */
    private java.util.List<Integer> mainDrives;
    private MediaSubscriptionFilter filter;
}
