package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 追剧订阅。固定挂载路径(mount_path)跨换源不变,保证播放地址与观看进度不中断;
 * 失效清理豁免见 ShareService(路径前缀 /追剧/)。
 */
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@jakarta.persistence.Table(name = "media_subscription", indexes = {
        @Index(name = "idx_msub_uid", columnList = "uid"),
        @Index(name = "idx_msub_schedule", columnList = "status, next_check_time")
})
public class MediaSubscription {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_ENDED = "ENDED";
    public static final String STATUS_ERROR = "ERROR";
    public static final String MODE_FOLLOW = "FOLLOW";
    public static final String MODE_TRANSFER = "TRANSFER";

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(nullable = false)
    private int uid;

    @Column(nullable = false)
    private String name;

    private String keyword;

    private Integer season;

    @Column(name = "douban_id")
    private Integer doubanId;

    /** 元数据平台:douban/tmdb/bangumi(§4.8) */
    @Column(name = "meta_provider", length = 16)
    private String metaProvider;

    @Column(name = "meta_id", length = 64)
    private String metaId;

    /** 官方元数据快照:已播集数/总集数/状态(RETURNING/ENDED/UNKNOWN)/下集播出时间/刷新时间 */
    @Column(name = "official_episodes")
    private Integer officialEpisodes;

    @Column(name = "official_total")
    private Integer officialTotal;

    @Column(name = "official_status", length = 16)
    private String officialStatus;

    @Column(name = "next_air_time")
    private Long nextAirTime;

    @Column(name = "meta_sync_time")
    private Long metaSyncTime;

    /** 元数据别名快照(换行分隔),搜索结果标题归属匹配用(§4.7) */
    @Column(columnDefinition = "TEXT")
    private String aliases;

    /** 主网盘覆盖(逗号分隔分享类型码,如 "10,5" = 百度/夸克;空 = 跟随全局 Setting msub_main_drives) */
    @Column(name = "main_drives", length = 64)
    private String mainDrives;

    @Column(columnDefinition = "TEXT", name = "filter_config")
    private String filterConfig;

    @Column(length = 16)
    private String mode = MODE_FOLLOW;

    @Column(name = "account_id")
    private Integer accountId;

    /** 多网盘转存目标(JSON 数组,兼容旧 accountId 单值) */
    @Column(columnDefinition = "TEXT", name = "account_ids")
    private String accountIds;

    @Column(name = "mount_path", length = 512)
    private String mountPath;

    @Column(name = "share_id")
    private Integer shareId;

    @Column(name = "expected_episodes")
    private Integer expectedEpisodes;

    @Column(name = "current_episodes")
    private Integer currentEpisodes;

    @Column(name = "last_episode")
    private Integer lastEpisode;

    @Column(columnDefinition = "TEXT", name = "episode_list")
    private String episodeList;

    /** 损坏集登记(JSON {集号: "源目录|时间戳"}):分享有效但某集被和谐,转存校验发现后登记,7 天过期 */
    @Column(columnDefinition = "TEXT", name = "broken_episodes")
    private String brokenEpisodes;

    /** 播出日程快照(JSON [{episode,airTime}]),provider 分集播出日期,昨日~+14 天窗口 */
    @Column(columnDefinition = "TEXT", name = "schedule")
    private String schedule;

    /** 显式允许跨网盘转存(默认仅同盘:AList 秒传配置允许的方向除外) */
    @Column(name = "cross_drive")
    private boolean crossDrive;

    @Column(nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    @Column(name = "stall_count")
    private int stallCount;

    @Column(name = "check_interval_hours")
    private Integer checkIntervalHours;

    @Column(name = "next_check_time")
    private Long nextCheckTime;

    @Column(name = "last_check_time")
    private Long lastCheckTime;

    @Column(name = "created_time", nullable = false)
    private long createdTime;

    @Column(name = "updated_time")
    private Long updatedTime;
}
