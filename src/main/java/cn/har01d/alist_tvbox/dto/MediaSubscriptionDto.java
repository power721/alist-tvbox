package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.List;

/** 追剧订阅列表项(web 管理端)。 */
@Data
public class MediaSubscriptionDto {
    private Integer id;
    private String name;
    private String keyword;
    /** 自定义搜索词(换行分隔,至多 5 个):主搜索词以外的额外召回词,空 = 不启用 */
    private String customKeywords;
    private Integer season;
    /** 季起始集号(null = 季内编号即官方编号) */
    private Integer seasonStartEpisode;
    private Integer doubanId;
    private String metaProvider;
    private String metaId;
    private Integer officialEpisodes;
    private Integer officialTotal;
    private String officialStatus;
    private Long nextAirTime;
    private String cover;
    private String mode;
    private Integer accountId;
    private List<String> accountIds;
    private String mountPath;
    private boolean crossDrive;
    /** 磁力兜底(仅转存模式生效) */
    private boolean magnetOffline;
    private String status;
    private Integer expectedEpisodes;
    /** 手动锁定总集数(null = 跟随官方) */
    private Integer manualTotalEpisodes;
    private Integer currentEpisodes;
    private Integer maxEpisode;
    private List<Integer> missingEpisodes;
    private int stallCount;
    private Integer checkIntervalHours;
    /** 手动播出时刻校正("HH:mm",空=自动) */
    private String customAirClock;
    /** 手动更新日(ISO 周一=1..周日=7,null/空 = 不限制:巡检只落配置周几的播出时刻) */
    private List<Integer> airWeekdays;
    private Long nextCheckTime;
    private Long lastCheckTime;
    private int resourceCount;
    private int gapCount;
    private String activeResourceTitle;
    /** 主网盘覆盖(分享类型码,null/空 = 跟随全局) */
    private List<Integer> mainDrives;
    private MediaSubscriptionFilter filter;
    private long createdTime;
}
