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
    /** 剧集简介(媒体详情页展示) */
    private String overview;
    /** 分集详情(标题/播出时间/简介/剧照),provider 未提供时为空 */
    private List<EpisodeInfo> episodes;
    /** 原始标题(与译名不同时展示,如英剧原名) */
    private String originalName;
    /** 类型(剧情/科幻/动画…) */
    private List<String> genres;
    /** 地区(美国/中国大陆/日本…) */
    private List<String> countries;
    /** 语言 */
    private List<String> languages;
    /** 首播/上映日期(yyyy-MM-dd) */
    private String firstAirDate;
    /** 评分(展示形态如 "8.5";来源 = provider 名) */
    private String rating;
    /** 导演 */
    private List<String> directors;
    /** 编剧 */
    private List<String> writers;
    /** 演员(头像+饰演角色,前 N 位主演) */
    private List<CastMember> cast;
    /** 背景图(详情页头部横幅) */
    private String backdrop;
}
