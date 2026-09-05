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

    /**
     * 自定义搜索词(手动,换行分隔,至多 5 个):主搜索词以外的额外召回词 —— 英文名/别名/简繁写法等
     * 资源命名差异大的场景。巡检填池时各词独立成一路全源搜索;补搜轮次里插在单集降级之前;
     * 标题归属匹配(matchNames)同时并入,自定义词搜回的召回才能过剧名门禁入池。空 = 不启用。
     */
    @Column(columnDefinition = "TEXT", name = "custom_keywords")
    private String customKeywords;

    private Integer season;

    /**
     * 季起始集号(手动):资源按<b>季内编号</b>组织而官方元数据是<b>全剧连续集号</b>时
     * (线上:一念永恒 —— TMDB 单季连续总集数,网盘按「第二季/第01集」季内编号),
     * 声明本季第 1 集对应全剧第 N 集:解析出的季内集号统一 +N-1 映射进官方连续集号空间,
     * 缺集检测按下界 N 运行(季前旧集不算缺)。null = 季内编号即官方编号(默认)。
     * 与 {@link #remapAbsoluteNumbering 自动重映射}互斥:手动声明优先,自动推断跳过。
     */
    @Column(name = "season_start_episode")
    private Integer seasonStartEpisode;

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

    /**
     * 手动锁定总集数(>0 生效):官方总集数不可信时的用户逃生舱(上游桥接污染/反复横跳)。
     * 生效后缺集计算、自动完结、展示分母全部以此为准,官方快照照常刷新但不参与上述口径。
     * 与 {@link #expectedEpisodes}(期望完结线,主观目标)语义不同:这是对客观总数的纠正。
     */
    @Column(name = "manual_total_episodes")
    private Integer manualTotalEpisodes;

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

    /** 手动播出时刻校正("HH:mm",空=自动):无官方时刻的剧 nextAirTime 按当日 20:00 硬编码,
     *  用户可按实际排播校正;每次元数据刷新后重放改写 schedule/nextAirTime 的时分(日期不动),
     *  优先级 手动 > PlayScheduleBridge 平台桥 > 默认 20:00。 */
    @Column(name = "custom_air_clock", length = 5)
    private String customAirClock;

    /**
     * 手动更新日(ISO 周一=1..周日=7 的 CSV,如 "2,4" = 周二/周四;空=不限制):欧美周播剧/追番
     * 固定周几更新,官方日程缺失/不可信时用户显式指定 —— 巡检只落在配置周几的生效播出时刻
     * (customAirClock 优先,默认 20:00)+15min,其余时间休眠;nextAirTime 同步接管为下一更新日
     * (详情页「下集播出」/时间轴/播出前休眠同口径)。已播集有缺口时不死等(与官方日程路同语义);
     * 完结剧不接管(完结周轻查继续)。
     */
    @Column(name = "air_weekdays", length = 13)
    private String airWeekdays;

    /** 显式允许跨网盘转存(默认仅同盘:AList 秒传配置允许的方向除外) */
    @Column(name = "cross_drive")
    private boolean crossDrive;

    /** 磁力兜底(仅转存模式生效):补缺穷尽后用磁力链接经全局离线下载配置账号离线补集,
     *  产物落离线账号挂载根/alist-tvbox-offline/,资源行 shareId=null 按挂载路径直接供播 */
    @Column(name = "magnet_offline")
    private boolean magnetOffline;

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

    /** 生效总集数:手动锁定(>0)优先,否则官方快照;0 = 未知。缺集/完结/展示统一走这个口径。 */
    public int effectiveTotalEpisodes() {
        if (manualTotalEpisodes != null && manualTotalEpisodes > 0) {
            return manualTotalEpisodes;
        }
        return officialTotal == null ? 0 : officialTotal;
    }

    /**
     * 本季官方集数已全部播完:已播 ≥ 总集数(>0)且无下集播出时间。
     * officialStatus 是剧级的(多季剧本季播完时整部剧仍 RETURNING),完结判定与展示要走这个季口径。
     */
    public boolean isSeasonAiredOut() {
        int total = effectiveTotalEpisodes();
        return total > 0
                && officialEpisodes != null && officialEpisodes >= total
                && nextAirTime == null;
    }
}
