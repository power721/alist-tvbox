package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.List;

/** 追剧订阅列表项(web 管理端)。 */
@Data
public class MediaSubscriptionDto {
    private Integer id;
    private String name;
    private String keyword;
    private Integer season;
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
    private String status;
    private Integer expectedEpisodes;
    private Integer currentEpisodes;
    private Integer maxEpisode;
    private List<Integer> missingEpisodes;
    private int stallCount;
    private Integer checkIntervalHours;
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
