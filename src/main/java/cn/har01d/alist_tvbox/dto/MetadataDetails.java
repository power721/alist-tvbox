package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.List;

/** 元数据平台条目详情:官方集数/状态/播出日程/别名,用于缺集检测权威触发与日程调度(§4.8)。 */
@Data
public class MetadataDetails {
    public static final String STATUS_RETURNING = "RETURNING";
    public static final String STATUS_ENDED = "ENDED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    private String provider;
    private String id;
    private String name;
    private String cover;
    private String year;
    /** 目标季总集数 */
    private Integer totalEpisodes;
    /** 目标季已播集数(按播出日期推算) */
    private Integer airedEpisodes;
    private String status = STATUS_UNKNOWN;
    /** 下集播出时间(epoch ms),已换算北京时间 */
    private Long nextAirTime;
    private Integer runtimeMinutes;
    private Integer totalSeasons;
    private List<String> aliases;
    /** 未播分集日程(播出时间轴用,尽量只含近期) */
    private List<EpisodeAirDate> upcoming;
}
