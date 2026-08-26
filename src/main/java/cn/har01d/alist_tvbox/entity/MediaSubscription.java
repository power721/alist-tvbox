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

    /** 封面快照(元数据详情页图床直链)。列表接口只读此列 —— 实时查 provider 在缓存冷(重启后)时是 N×3 次串行外部请求,曾把列表页拖到 50s。 */
    @Column(name = "cover_url", length = 512)
    private String coverUrl;

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

    /** 挂载目录里观测到的最大集号(资源侧指标)。**不是观看进度** —— 进度在 History,见 watchedEpisode。 */
    @Column(name = "max_episode")
    private Integer maxEpisode;

    /**
     * 追平标记:用户<b>看到过最新播出集</b>(观看进度 >= 当时资源侧最新集)那一刻的最新集号,
     * null=从未追平。TVBox 🆕 角标只对追平过的订阅显示 —— 追平后新播出的集才算"新",
     * 落后补看途中不亮灯(与通知「差十集的人不为最新一集响铃」同哲学)。由角标读路径惰性维护。
     */
    @Column(name = "caught_up_episode")
    private Integer caughtUpEpisode;

    /**
     * 播出日程快照(JSON [{episode,airTime}]),provider 分集播出日期,昨日~+14 天窗口。
     * 分集实体(msub_episode)建行时从这里取播出时间;时间轴 UI 也直读此快照。
     */
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

    /**
     * Telegram 消息绑定:该订阅的通知卡片绑定的 bot 消息 id(首条 sendMessage 返回,
     * 后续事件 editMessageText 编辑同一条消息,不刷屏)。null=尚未发过。
     * 绑定在 tg_chat_id 对应的 chat 内有效 —— 配置换 chat 后旧 id 失效,自动重发换绑。
     */
    @Column(name = "tg_message_id")
    private Long tgMessageId;

    /** 绑定消息所在的 chat_id(设置项 msub_telegram_chat_id 的快照),用于检测 chat 变更 */
    @Column(name = "tg_chat_id", length = 64)
    private String tgChatId;

    /**
     * 本季官方集数已全部播完:已播 ≥ 总集数(>0)且无下集播出时间。
     * officialStatus 是剧级的(多季剧本季播完时整部剧仍 RETURNING),完结判定与展示要走这个季口径。
     */
    public boolean isSeasonAiredOut() {
        return officialTotal != null && officialTotal > 0
                && officialEpisodes != null && officialEpisodes >= officialTotal
                && nextAirTime == null;
    }
}
