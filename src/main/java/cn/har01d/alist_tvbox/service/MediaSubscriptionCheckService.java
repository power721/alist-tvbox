package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriveId;
import cn.har01d.alist_tvbox.dto.EpisodeAirDate;
import cn.har01d.alist_tvbox.dto.IndexRequest;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionFilter;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionPoolFilter;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.DeadLink;
import cn.har01d.alist_tvbox.entity.DeadLinkRepository;
import cn.har01d.alist_tvbox.entity.History;
import cn.har01d.alist_tvbox.entity.HistoryRepository;
import cn.har01d.alist_tvbox.entity.IndexTemplate;
import cn.har01d.alist_tvbox.entity.IndexTemplateRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisode;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEventRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.service.metadata.MetadataService;
import cn.har01d.alist_tvbox.service.sitesearch.GuanYingSearchService;
import cn.har01d.alist_tvbox.service.sitesearch.PanLianSearchService;
import cn.har01d.alist_tvbox.service.sitesearch.PanjuSearchService;
import cn.har01d.alist_tvbox.service.sitesearch.WanouSearchService;
import cn.har01d.alist_tvbox.service.sitesearch.WoniuSearchService;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.TextUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 追剧订阅巡检:三级递进(重列主源 → 失效换源 → 搜索补源)把搜索开销压到最低;
 * 官方元数据(§4.8)提供缺集检测权威触发与播出日程调度;缺集时探测候选并挂"补缺"源合并播放。
 * 换源 = 删旧挂载后在同一固定路径重挂新分享(mount_path 不变,播放历史不断链)。
 * <p>
 * 可用性模型(v2):资源只表达挂载生命周期(CANDIDATE/MOUNTED/RETIRED/REJECTED),
 * <b>取链事实只落在 {@link MediaSubscriptionEpisodeSource}</b> —— 某集可播 = 存在 LISTED/VERIFIED 行,
 * 整源死 = 该资源全部行 FAILED/MISSING。列得出目录 ≠ 取得到链,取得到链 ≠ 拉得出流,所以弱信号(LISTED)只作兜底,
 * 每轮巡检对每个挂载源抽 1 集做字节级流探测(verifyStream)、播放失败做二次探测传染判定,让 VERIFIED/FAILED 持续逼近事实。
 */
@Slf4j
@Service
public class MediaSubscriptionCheckService {
    /** 分享类型码(Message.type):网盘类;magnet/ed2k/video 候选直接丢弃 */
    private static final Set<String> PAN_TYPES = Set.of("0", "1", "2", "3", "5", "6", "7", "8", "9", "10", "12");
    private static final Pattern SEASON_EPISODE = Pattern.compile("[Ss](\\d{1,2})[Ee](\\d{1,4})");
    private static final Pattern NUMBER = Pattern.compile("(\\d{1,4})");
    /** 全局主网盘 Setting key(逗号分隔分享类型码;订阅级 main_drives 覆盖) */
    public static final String MSUB_MAIN_DRIVES = "msub_main_drives";
    /** 全局扩展网盘 Setting key(逗号分隔分享类型码):主网盘以外允许入候选池的盘,未配置时候选仅收主网盘 */
    public static final String MSUB_EXTENDED_DRIVES = "msub_extended_drives";
    /** 全局资源筛选 Setting key(单行 JSON → {@link MediaSubscriptionPoolFilter}):包含/排除词、
     * 清晰度门槛、单集体积上下限,入池/候选复筛/集文件体积策略三处消费,订阅级显式配置优先 */
    public static final String MSUB_POOL_FILTER = "msub_pool_filter";
    /** 预告/花絮等非正片(片头/片尾:年番分享常带「片头尾/」目录装 OP/ED 片段,线上被当成第 2、3 集) */
    private static final Pattern EXTRA = Pattern.compile("(?i)(pv|ncop|nced|sample|trailer|menu|预告|花絮|彩蛋|ost|片头|片尾)");
    /** 衍生篇目目录词(番外/前传/外传):自成条目、集号并入全剧连续计数,主季订阅整棵跳过 */
    private static final Pattern SPIN_OFF_DIR = Pattern.compile("番外|前传|外传");
    /** 季目录声明的本季起始集号(全剧连续编号形态):「067-更新中 4K 第三季」「070-092」的行首区间起点 */
    private static final Pattern DIR_RANGE_START = Pattern.compile("(?:^|\\D)0*(\\d{1,4})\\s*[-~—–至]");
    /** 完结资源包形态:追更中的订阅不会持续更新 */
    private static final Pattern COMPLETE_PACK = Pattern.compile("全\\s*\\d{1,4}\\s*集|全集|完整版|已?完结");
    /** 手动播出时刻("H:mm" 或 "HH:mm") */
    private static final Pattern AIR_CLOCK = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    /** 扫集号前先剥掉的技术标签(避免 1080/2160/4K 被当成集数)。声道位/版本号必须一并剥:
     * 剧场版电影常以 {@code 2025.V2.1080p.BluRay.Remux.AVC.TrueHD.5.1} 命名,不剥的话末号规则把
     * {@code 5.1} 的 1 当集号,109 分钟的电影混进剧集清单冒充「第1集」(线上:柯南订阅唯一
     * "识别"出的 1 集就是剧场版)。声道位带数字边界(前后都不是数字),日期戳 {@code 2026.08.21}
     * 里的 {@code 6.0}/{@code 8.2} 不在剥离范围,日期戳剥除(缺陷 10)不受影响。 */
    private static final Pattern TECH_TAGS = Pattern.compile(
            "(?i)(2160p|1080p|720p|480p|4k|8k|h\\.?26[45]|x\\.?26[45]|hevc|avc|aac|dts|flac|ac3|truehd|10bit|8bit|sdr|hdr10?|dolby|dv|web-?dl|bdrip|blu-?ray|remux|[.\\-_ ]v\\d{1,2}(?![a-z0-9])|(?<!\\d)\\d\\.\\d(?!\\d)|国语|粤语|中字|简体|繁体|双语|字幕)");
    /** 网盘限流/风控(非资源失效):百度 errno -62 = 验证次数过多;其余为通用限流措辞 */
    private static final Pattern THROTTLE_ERROR = Pattern.compile(
            "(?i)errno\"?\\s*:\\s*-62|验证次数过多|请稍[后候]|访问频繁|操作频繁|too many (requests|attempts)|rate.?limit|\\b429\\b");
    /** 方括号段里的技术信号补充集(TECH_TAGS 之外):帧率/夸克转码模板名/体积标注/长数字 id。
     * 线上形态 {@code [322155_maxplus_50fps_tv_6.45GB]}(夸克 4K 转码命名):模板 id 被拆成 3221+55,
     * 体积 6.45 的 45 会被末号规则当集号,三集各解析成 45/60/72。 */
    private static final Pattern BRACKET_TECH_EXTRA = Pattern.compile("(?i)fps|maxplus|\\d+(?:\\.\\d+)?\\s*[gmtk]b?\\b|\\d{5,}");
    /** 方括号段里的显式集号标记:段内虽混有技术词但集号是明确写出的,不剔(如 {@code [第05集 1080P]}) */
    private static final Pattern BRACKET_EPISODE_MARK = Pattern.compile("(?i)第\\s*\\d{1,4}\\s*集|[Ss]\\d{1,2}[Ee]\\d{1,4}|\\bep\\s*\\d{1,4}");
    /** 上/中/下章节标记(集/篇/部):无数字集号时的集序推定,上=1 中=2 下=3。
     * 三集迷你剧常按「上集/中集/下集」命名且与 TMDB 的 S1E1-3 标题一一对应。 */
    private static final Pattern CHAPTER_MARK = Pattern.compile("([上中下])[集篇部]");
    /** 明确失效(判死即拉黑):分享/提取码/过期类措辞(AList 报错原文,如 "failed get link: 参数错误")。
     * 其余未识别错误一律按瞬时处理(见 {@link #classifyProbeFailure}) */
    private static final Pattern GONE_ERROR = Pattern.compile(
            "(?i)分享已?失效|链接错误|链接已?过期|提取码(错误|不正确)|密码(错误|不正确)|已取消|不存在|参数错误|"
                    + "object not found|not exist|expired|cancel|invalid");
    /** 搜索源判定失效的原始状态词(各源大小写/措辞不一,入池时统一归一化) */
    private static final Set<String> INVALID_STATES = Set.of("BAD", "INVALID", "FAILED", "EXPIRED", "DEAD", "ERROR");
    /** 集号范围门禁拒绝消息的识别标记:调用方据此退役候选但不进跨订阅失效黑名单(链接没死,只是不属于本剧) */
    private static final String FOREIGN_SHOW_MARK = "疑似同名异剧";
    /** 池枯竭释放 BAD 冷却的最小年龄:本轮刚判死的不参与释放 */
    private static final long BAD_RELEASE_MIN_AGE_MS = 30 * 60_000L;
    /** 播放历史里的逻辑链接 msubep-{订阅}-{集},集号即观看进度 */
    private static final Pattern MSUBEP_EPISODE = Pattern.compile("msubep-\\d+-(\\d{1,4})");
    /** 播放取链失败对资源的降分幅度:够把它挤到同类候选之后,又不至于一次失败就永久出局 */
    static final int PLAY_FAILURE_PENALTY = 20;
    /** 搜索池线程序号(线程名 msub-search-N):五路共用名字时日志看不出并发交错,排查误判单线程 */
    private static final AtomicInteger SEARCH_SEQ = new AtomicInteger();
    /** 集源行"可播"状态集(可用性派生口径) */
    private static final Set<String> LIVE_STATES = Set.of(MediaSubscriptionEpisodeSource.STATE_LISTED, MediaSubscriptionEpisodeSource.STATE_VERIFIED);
    /**
     * 文件名里的发布日期戳。必须在扫集号之前剥掉 —— 末号规则取"最后一个 1~999 的数字",
     * 而 {@code 01 [4K][HEVC.AAC][2026.08.21].mp4} 里的月(08)和日(21)都在这个区间且排在集号之后,
     * 会把真正的集号 01 覆盖成 21。线上后果:同一目录三集全部解析成第 21 集(集数清单塌成 1 集),
     * 播放请求第 1 集时清单里根本没有这个 key,报"已尝试 0 个源"。
     */
    private static final Pattern DATE_STAMP = Pattern.compile(
            "(?:19|20)\\d{2}\\s*[.\\-/年]\\s*\\d{1,2}\\s*[.\\-/月]\\s*\\d{1,2}\\s*日?|\\b(?:19|20)\\d{6}\\b");
    /** 目录名声明的季区间 第A-B季 / 第A~B季(单季由 TextUtils.parseTitleSeason 处理) */
    private static final Pattern SEASON_RANGE = Pattern.compile("第\\s*(\\d{1,2})\\s*[-~至]\\s*(\\d{1,2})\\s*季");
    /** 标题宣称的集数进度:更新至N / 全N集 / 第A-B集 / 第N集 / EPn(取最大值) */
    private static final Pattern TITLE_PROGRESS = Pattern.compile(
            "(?i)更新?至\\s*(\\d{1,4})|全\\s*(\\d{1,4})\\s*集|第\\s*(\\d{1,4})\\s*[-~至]\\s*(\\d{1,4})\\s*集|第\\s*(\\d{1,4})\\s*集|(?:^|[^a-z])e(?:p)?\\s*(\\d{1,4})(?!\\d)");
    /** 标题/元数据里的年份(前后无数字边界,防 1080p/60fps/长数字段误配) */
    private static final Pattern YEAR_MARK = Pattern.compile("(?<!\\d)(19[89]\\d|20[0-2]\\d)(?!\\d)");
    private static final String INDEX_TEMPLATE_NAME = "追剧";
    /** 补缺源内部目录(藏于 /追剧/ 下的点目录,用户视角每部剧只有一个文件夹入口) */
    private static final String GAP_SOURCES_ROOT = cn.har01d.alist_tvbox.util.Constants.SUBSCRIPTION_MOUNT_ROOT + ".sources/";
    /** AList 整体不可用时本轮跳过后的短间隔重试(15min,下个每小时 sweep 即可捞到) */
    private static final long INVALID_RETRY_DELAY_MS = 15 * 60_000L;
    /** 播放选源排序:VERIFIED > LISTED,再按资源分/成功率 —— 转存副本优先级由调用方排在前 */
    private static final Comparator<MediaSubscriptionEpisodeSource> SOURCE_ORDER =
            Comparator.comparing((MediaSubscriptionEpisodeSource s) -> MediaSubscriptionEpisodeSource.STATE_VERIFIED.equals(s.getState()) ? 0 : 1)
                    .thenComparing(s -> -s.getSuccessCount())
                    .thenComparing(MediaSubscriptionEpisodeSource::getFailCount);

    private final MediaSubscriptionRepository subscriptionRepository;
    private final MediaSubscriptionResourceRepository resourceRepository;
    private final MediaSubscriptionEventRepository eventRepository;
    private final MediaSubscriptionEpisodeRepository episodeRepository;
    private final MediaSubscriptionEpisodeSourceRepository episodeSourceRepository;
    private final DeadLinkRepository deadLinkRepository;
    private final ShareRepository shareRepository;
    private final SiteRepository siteRepository;
    private final cn.har01d.alist_tvbox.entity.DriverAccountRepository driverAccountRepository;
    private final IndexTemplateRepository indexTemplateRepository;
    private final SettingRepository settingRepository;
    private final ShareService shareService;
    private final AListService aListService;
    private final TelegramService telegramService;
    private final WanouSearchService wanouSearchService;
    private final PanLianSearchService panLianSearchService;
    private final GuanYingSearchService guanYingSearchService;
    private final WoniuSearchService woniuSearchService;
    private final PanjuSearchService panjuSearchService;
    private final MetadataService metadataService;
    private final AutoUpdateExecutor autoUpdateExecutor;
    /** 观看进度只读来源:追更系统不自行存储进度,多端合并由播放记录同步负责 */
    private final HistoryRepository historyRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    /** 巡检/换源后联动增量转存;延迟取用以破与 TransferService 的构造循环。测试裸实例为 null。 */
    private final ObjectProvider<MediaSubscriptionTransferService> transferServiceProvider;
    /** Telegram 通知(同剧编辑同一条消息+outbox 重试);裸实例测试为 null(事件仍落站内时间线,只是不外发)。 */
    private final MediaSubscriptionNotificationService notificationService;

    private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();
    /** 已删除订阅的取消标记(delete() 卸载/删行前第一时间打上):订阅创建即触发首轮巡检,
     * 搜索+挂载可达数分钟,期间删除若巡检不感知,会继续搜索、把已删剧的挂载重新建回 AList,
     * 尾部对 detached 实体的 save 更会把无 @Version 的整行 INSERT 复活(线上 #40)。 */
    private final Set<Integer> deleted = ConcurrentHashMap.newKeySet();
    /** 播放全源失败标记(订阅 id):ENDED 订阅不再跑完整巡检,分享失效后系统无感知、用户每次播放都撞死源 ——
     * playEpisode 全候选失败时打标,check() 的 ENDED 轻查短路据此放行一次完整巡检(换源+补搜)。 */
    private final Set<Integer> playbackFailed = ConcurrentHashMap.newKeySet();
    /** 「仍在追看」判定的近期播放窗口:完结剧 7 天内有播放才参与资源维护,越窗回落每日轻查。 */
    private static final long RECENT_PLAY_WINDOW_MS = 7L * 24 * 3600_000;
    /** 封面预热去重(订阅 id):列表接口发现快照缺失时后台补拉,同订阅不堆积重复任务 */
    private final Set<Integer> coverPrewarmInFlight = ConcurrentHashMap.newKeySet();
    /** 缺集补搜关键词轮次(0=整季,1+=单集),内存态即可 */
    private final Map<Integer, Integer> gapSearchRounds = new ConcurrentHashMap<>();
    /** 主网盘补池搜索限频(订阅 id → 上次搜索时间):池内无该盘资源时主动搜索,至多每检查周期一次 */
    private final Map<Integer, Long> mainDriveSearchTime = new ConcurrentHashMap<>();
    /** 详情触发补线的限频(订阅 id → 上次触发时间),TVBox 每次打开详情都会装配线路,不能次次起后台探测 */
    private static final long DRIVE_LINE_KICK_THROTTLE_MS = 10 * 60_000L;
    private final Map<Integer, Long> driveLineKickTime = new ConcurrentHashMap<>();
    /** 撞上限流的网盘 → 解禁时间戳:退避期内不再试挂该盘候选,防连环触发把好源烧成 BAD */
    private final Map<String, Long> driveThrottleTime = new ConcurrentHashMap<>();
    /** 连续瞬时故障计数(资源 id → 次数):未识别错误默认按瞬时不下结论,streak 封顶防真死源每轮白吃探测预算 */
    private final Map<Integer, Integer> transientStreak = new ConcurrentHashMap<>();
    /** 播放后前瞻验证去重(订阅 id):在跑任务不堆积 */
    private final Set<Integer> preheatAheadInFlight = ConcurrentHashMap.newKeySet();
    /** 播放后前瞻验证限频(订阅 id → 上次触发时间):连播几集时不重复打探测 */
    private final Map<Integer, Long> preheatAheadTime = new ConcurrentHashMap<>();
    /** 前瞻发现死集后的补源冷却:探测每个限频窗口都跑,完整巡检(含补搜)一集最多 2h 触发一次 */
    private static final long AHEAD_RESCUE_COOLDOWN_MS = 2 * 3600_000L;
    private final Map<Integer, Long> aheadRescueTime = new ConcurrentHashMap<>();
    /** 字节级流探测客户端(默认 OkHttp 实现,单测注入桩) */
    private StreamProbeClient streamProbeClient = new StreamProbeClient.Default();
    /**
     * 订阅巡检执行池:并发度可配(checkConcurrency,默认 3),到期订阅并发检查、手动触发的
     * 检查/换源/刷新不再与定时 sweep 排同一条队。同订阅重入由 {@link #inFlight} 防护;
     * 源侧压力不随并发订阅数放大 —— 搜索五路池(searchExecutor)与玩偶/TG 内部池全局共享,天然限流。
     */
    private final ExecutorService executor;
    /** 多源搜索并发池(TG 聚合 + 玩偶/盘链/观影/蜗牛/盘聚 各一路):串行排队时总时长=各源之和(线上 37s),并发后=最慢一路 */
    private final ExecutorService searchExecutor = Executors.newFixedThreadPool(6, r -> {
        Thread thread = new Thread(r, "msub-search-" + SEARCH_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public MediaSubscriptionCheckService(MediaSubscriptionRepository subscriptionRepository,
                                         MediaSubscriptionResourceRepository resourceRepository,
                                         MediaSubscriptionEventRepository eventRepository,
                                         MediaSubscriptionEpisodeRepository episodeRepository,
                                         MediaSubscriptionEpisodeSourceRepository episodeSourceRepository,
                                         DeadLinkRepository deadLinkRepository,
                                         ShareRepository shareRepository,
                                         SiteRepository siteRepository,
                                         cn.har01d.alist_tvbox.entity.DriverAccountRepository driverAccountRepository,
                                         IndexTemplateRepository indexTemplateRepository,
                                         SettingRepository settingRepository,
                                         ShareService shareService,
                                         AListService aListService,
                                         TelegramService telegramService,
                                         WanouSearchService wanouSearchService,
                                         PanLianSearchService panLianSearchService,
                                         GuanYingSearchService guanYingSearchService,
                                         WoniuSearchService woniuSearchService,
                                         PanjuSearchService panjuSearchService,
                                         MetadataService metadataService,
                                         AutoUpdateExecutor autoUpdateExecutor,
                                         HistoryRepository historyRepository,
                                         AppProperties appProperties,
                                         ObjectMapper objectMapper,
                                         ObjectProvider<MediaSubscriptionTransferService> transferServiceProvider,
                                         MediaSubscriptionNotificationService notificationService) {
        this.transferServiceProvider = transferServiceProvider;
        this.notificationService = notificationService;
        this.subscriptionRepository = subscriptionRepository;
        this.resourceRepository = resourceRepository;
        this.eventRepository = eventRepository;
        this.episodeRepository = episodeRepository;
        this.episodeSourceRepository = episodeSourceRepository;
        this.deadLinkRepository = deadLinkRepository;
        this.shareRepository = shareRepository;
        this.siteRepository = siteRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.indexTemplateRepository = indexTemplateRepository;
        this.settingRepository = settingRepository;
        this.shareService = shareService;
        this.aListService = aListService;
        this.telegramService = telegramService;
        this.wanouSearchService = wanouSearchService;
        this.panLianSearchService = panLianSearchService;
        this.guanYingSearchService = guanYingSearchService;
        this.woniuSearchService = woniuSearchService;
        this.panjuSearchService = panjuSearchService;
        this.metadataService = metadataService;
        this.autoUpdateExecutor = autoUpdateExecutor;
        this.historyRepository = historyRepository;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        int threads = Math.max(1, appProperties.getSubscription().getCheckConcurrency());
        AtomicInteger checkSeq = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread thread = new Thread(r, "msub-check-" + checkSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 供裸实例测试直接提供转存服务。 */
    public MediaSubscriptionCheckService(MediaSubscriptionRepository subscriptionRepository,
                                         MediaSubscriptionResourceRepository resourceRepository,
                                         MediaSubscriptionEventRepository eventRepository,
                                         MediaSubscriptionEpisodeRepository episodeRepository,
                                         MediaSubscriptionEpisodeSourceRepository episodeSourceRepository,
                                         DeadLinkRepository deadLinkRepository,
                                         ShareRepository shareRepository,
                                         SiteRepository siteRepository,
                                         cn.har01d.alist_tvbox.entity.DriverAccountRepository driverAccountRepository,
                                         IndexTemplateRepository indexTemplateRepository,
                                         SettingRepository settingRepository,
                                         ShareService shareService,
                                         AListService aListService,
                                         TelegramService telegramService,
                                         WanouSearchService wanouSearchService,
                                         PanLianSearchService panLianSearchService,
                                         GuanYingSearchService guanYingSearchService,
                                         WoniuSearchService woniuSearchService,
                                         PanjuSearchService panjuSearchService,
                                         MetadataService metadataService,
                                         AutoUpdateExecutor autoUpdateExecutor,
                                         HistoryRepository historyRepository,
                                         AppProperties appProperties,
                                         ObjectMapper objectMapper,
                                         MediaSubscriptionTransferService transferService,
                                         MediaSubscriptionNotificationService notificationService) {
        this(subscriptionRepository, resourceRepository, eventRepository, episodeRepository,
                episodeSourceRepository, deadLinkRepository, shareRepository, siteRepository,
                driverAccountRepository, indexTemplateRepository, settingRepository, shareService,
                aListService, telegramService, wanouSearchService, panLianSearchService,
                guanYingSearchService, woniuSearchService, panjuSearchService, metadataService, autoUpdateExecutor,
                historyRepository, appProperties, objectMapper,
                fixedProvider(transferService), notificationService);
    }

    private static ObjectProvider<MediaSubscriptionTransferService> fixedProvider(
            MediaSubscriptionTransferService transferService) {
        if (transferService == null) {
            return null;
        }
        return new ObjectProvider<>() {
            @Override
            public MediaSubscriptionTransferService getObject() {
                return transferService;
            }
        };
    }

    /** 兼容旧签名(单测裸实例无转存联动)。 */
    public MediaSubscriptionCheckService(MediaSubscriptionRepository subscriptionRepository,
                                         MediaSubscriptionResourceRepository resourceRepository,
                                         MediaSubscriptionEventRepository eventRepository,
                                         MediaSubscriptionEpisodeRepository episodeRepository,
                                         MediaSubscriptionEpisodeSourceRepository episodeSourceRepository,
                                         DeadLinkRepository deadLinkRepository,
                                         ShareRepository shareRepository,
                                         SiteRepository siteRepository,
                                         cn.har01d.alist_tvbox.entity.DriverAccountRepository driverAccountRepository,
                                         IndexTemplateRepository indexTemplateRepository,
                                         SettingRepository settingRepository,
                                         ShareService shareService,
                                         AListService aListService,
                                         TelegramService telegramService,
                                         WanouSearchService wanouSearchService,
                                         PanLianSearchService panLianSearchService,
                                         GuanYingSearchService guanYingSearchService,
                                         WoniuSearchService woniuSearchService,
                                         PanjuSearchService panjuSearchService,
                                         MetadataService metadataService,
                                         AutoUpdateExecutor autoUpdateExecutor,
                                         HistoryRepository historyRepository,
                                         AppProperties appProperties,
                                         ObjectMapper objectMapper,
                                         MediaSubscriptionNotificationService notificationService) {
        this(subscriptionRepository, resourceRepository, eventRepository, episodeRepository,
                episodeSourceRepository, deadLinkRepository, shareRepository, siteRepository,
                driverAccountRepository, indexTemplateRepository, settingRepository, shareService,
                aListService, telegramService, wanouSearchService, panLianSearchService,
                guanYingSearchService, woniuSearchService, panjuSearchService, metadataService, autoUpdateExecutor,
                historyRepository, appProperties, objectMapper, (MediaSubscriptionTransferService) null,
                notificationService);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        searchExecutor.shutdownNow();
    }

    /** 每小时第 20 分钟扫描到期订阅(避开 :00/:30 分享校验高峰),jitter 后逐个提交到并发执行池。 */
    @Scheduled(cron = "0 20 * * * *")
    public void sweep() {
        if (!appProperties.getSubscription().isEnabled()) {
            return;
        }
        autoUpdateExecutor.scheduleWithJitter(this::sweepDue);
    }

    /**
     * 在播剧元数据短轮刷新(每小时第 35 分,避开 :20 巡检/:40 转存):
     * 官方日程/集数变化快(周更/日更),不等巡检长轮(checkIntervalHours≥12h)——
     * 下集播出时间、加更集数以 airingRefreshHours 节奏跟进,媒体详情页与时间轴保持新鲜。
     */
    @Scheduled(cron = "0 35 * * * *")
    public void refreshAiring() {
        if (!appProperties.getSubscription().isEnabled()) {
            return;
        }
        autoUpdateExecutor.scheduleWithJitter(this::refreshAiringDue);
    }

    void refreshAiringDue() {
        long ttl = Math.max(1, appProperties.getSubscription().getAiringRefreshHours()) * 3600_000L;
        long now = System.currentTimeMillis();
        List<MediaSubscription> due = new ArrayList<>();
        for (MediaSubscription subscription : subscriptionRepository.findAll()) {
            if (!MediaSubscription.STATUS_ACTIVE.equals(subscription.getStatus())
                    || !MetadataDetails.STATUS_RETURNING.equals(subscription.getOfficialStatus())
                    || StringUtils.isBlank(subscription.getMetaProvider())) {
                continue;
            }
            long last = subscription.getMetaSyncTime() == null ? 0 : subscription.getMetaSyncTime();
            if (now - last >= ttl) {
                due.add(subscription);
            }
        }
        if (due.isEmpty()) {
            return;
        }
        log.info("airing metadata refresh: {} subscriptions due (ttl {}h)", due.size(), ttl / 3600_000);
        for (MediaSubscription subscription : due) {
            int id = subscription.getId();
            executor.submit(() -> {
                try {
                    // 任务内取新实体:排队等待期间(巡检长轮可达数分钟)旧实体再整行 save
                    // 会把并发巡检刚写的集数/调度字段回滚覆盖
                    MediaSubscription current = subscriptionRepository.findById(id).orElse(null);
                    if (current == null || !MediaSubscription.STATUS_ACTIVE.equals(current.getStatus())) {
                        return;
                    }
                    refreshMetadata(current, ttl);
                    if (stopIfDeleted(id)) {
                        return;
                    }
                    subscriptionRepository.save(current);
                } catch (Exception e) {
                    log.warn("refresh airing metadata {} failed: {}", id, e.getMessage());
                }
            });
        }
    }

    void sweepDue() {
        int limit = appProperties.getSubscription().getMaxChecksPerRound();
        List<MediaSubscription> due = new ArrayList<>(subscriptionRepository
                .findByStatusAndNextCheckTimeLessThanEqualOrderByNextCheckTimeAsc(
                        MediaSubscription.STATUS_ACTIVE, System.currentTimeMillis(), PageRequest.of(0, limit))
                .getContent());
        // ENDED 订阅按到期时间参与轻量复查(官方加更/集数修正自动重开,每日节奏)
        due.addAll(subscriptionRepository
                .findByStatusAndNextCheckTimeLessThanEqualOrderByNextCheckTimeAsc(
                        MediaSubscription.STATUS_ENDED, System.currentTimeMillis(), PageRequest.of(0, limit))
                .getContent());
        log.info("media subscription sweep: {} due (limit {})", due.size(), limit);
        for (MediaSubscription subscription : due) {
            submitCheck(subscription.getId());
        }
        retryErrors();
        cleanupEventsDaily();
    }

    /** 上次事件清理时间(内存态,sweep 串行调度无需原子):sweep 每小时跑,清理本身每日一次足够。 */
    private volatile long lastEventCleanupTime;

    /**
     * 事件保留期清理(100+ 订阅规模):全局 90 天 + 每订阅最新 200 条,双条件先到先清。
     * 事件流是排障用流水,前端只展示每订阅最近 100 条,超额行纯属膨胀。
     */
    void cleanupEventsDaily() {
        long now = System.currentTimeMillis();
        if (now - lastEventCleanupTime < 24 * 3600_000L) {
            return;
        }
        lastEventCleanupTime = now;
        try {
            eventRepository.deleteByCreatedTimeLessThan(now - 90L * 24 * 3600_000);
            for (MediaSubscription subscription : subscriptionRepository.findAll()) {
                List<MediaSubscriptionEvent> latest = eventRepository
                        .findTop201BySubscriptionIdOrderByIdDesc(subscription.getId());
                if (latest.size() <= 200) {
                    continue;
                }
                List<Integer> doomed = latest.subList(200, latest.size()).stream()
                        .map(MediaSubscriptionEvent::getId)
                        .toList();
                eventRepository.deleteAllById(doomed);
            }
        } catch (Exception e) {
            log.warn("event retention cleanup failed: {}", e.getMessage());
        }
    }

    /** 提交单订阅检查到并发池:任务彼此隔离,一个订阅失败只记日志不拖累其它。 */
    private void submitCheck(int id) {
        executor.submit(() -> {
            try {
                check(id);
            } catch (Exception e) {
                log.warn("check subscription {} failed: {}", id, e.getMessage());
            }
        });
    }

    /**
     * 存量池季号清洗:标题明确标注其它季的资源行(换季前搜入的"末日地堡第一季"这类)逐轮清出 ——
     * 入池过滤只挡新搜索结果,旧行没人清会永久躺在候选列表里(用户改季后点检查,候选还全是第一季)。
     * 裸标题(无季标记)行不判:内容是哪季无从得知,交给挂载侧 season 口径的文件解析与 hollow 换源自愈。
     * 行删除不拉黑 link —— 资源没死,只是不属于本季,别的订阅(追其它季)照常可用。
     */
    void purgeForeignSeasonResources(MediaSubscription subscription) {
        if (subscription.getSeason() == null || subscription.getSeason() <= 0) {
            return;
        }
        List<MediaSubscriptionResource> resources = resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId());
        List<Integer> purgedIds = new ArrayList<>();
        List<String> purgedTitles = new ArrayList<>();
        boolean primaryPurged = false;
        for (MediaSubscriptionResource resource : resources) {
            Integer titleSeason = TextUtils.parseTitleSeason(resource.getTitle());
            if (titleSeason == null || titleSeason.equals(subscription.getSeason())) {
                continue;
            }
            if (StringUtils.isNotBlank(resource.getMountPath())) {
                unmountShareIfUnused(resource.getShareId(), subscription.getId());
            }
            episodeSourceRepository.deleteByResourceIdIn(List.of(resource.getId()));
            resourceRepository.delete(resource);
            purgedIds.add(resource.getId());
            purgedTitles.add(StringUtils.defaultIfBlank(resource.getTitle(), resource.getLink()));
            if (MediaSubscriptionResource.STATE_MOUNTED.equals(resource.getState())) {
                primaryPurged = true; // 主源被清:固定路径挂载已卸,shareId 置空走 ensureSource 重挂
            }
        }
        if (purgedIds.isEmpty()) {
            return;
        }
        if (primaryPurged) {
            subscription.setShareId(null);
        }
        forget(subscription.getId(), purgedIds);
        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_SOURCE_INVALID,
                "清理其它季资源 " + purgedIds.size() + " 条(当前订阅:第" + subscription.getSeason() + "季):"
                        + String.join("、", purgedTitles), false);
    }

    /**
     * 换季全量重置的 DB 部分(纯本地,可在事务内调用):清资源池/集源行/分集日历行,重置集数与
     * 元数据快照(封面/日程/追平标记都是旧季口径)。资源行的 shareId 卸载由调用方处理 ——
     * 远程卸载是 HTTP 往返,坐在编辑事务里会横跨行锁(与 delete 同规)。
     */
    void resetInventoryForSeason(MediaSubscription subscription, int newSeason) {
        int id = subscription.getId();
        List<Integer> resourceIds = resourceRepository.findBySubscriptionIdOrderByScoreDesc(id).stream()
                .map(MediaSubscriptionResource::getId)
                .toList();
        episodeSourceRepository.deleteByResourceIdIn(resourceIds);
        episodeRepository.deleteBySubscriptionId(id);
        resourceRepository.deleteBySubscriptionId(id);
        subscription.setShareId(null);
        subscription.setCoverUrl(null); // 封面/日程快照是旧季口径,清空让首轮巡检按新季重拉
        subscription.setMetaSyncTime(null);
        subscription.setCurrentEpisodes(0);
        subscription.setMaxEpisode(null);
        subscription.setStallCount(0);
        subscription.setCaughtUpEpisode(null); // 追平门槛按旧季观看进度累计,新季口径作废
        if (MediaSubscription.STATUS_ENDED.equals(subscription.getStatus())) {
            subscription.setStatus(MediaSubscription.STATUS_ACTIVE); // 换季=追新季,旧季完结状态随之作废
        }
        forget(id, resourceIds);
        addEvent(id, MediaSubscriptionEvent.TYPE_SOURCE_INVALID,
                "换季重置:已清空资源池与集数,按第" + newSeason + "季重新搜索", false);
    }

    /**
     * 改季残留检测:改季只 setSeason,旧季的集源行不清 —— 行挂在旧季 episode 行(season 列)上,
     * 而可用性聚合不按季过滤,旧季行继续冒领集号:播放列表顶着新季分集标题、点开却是旧季文件
     * (线上:末日地堡 S1→S3,逻辑线路第 1 集"你是谁?"播的是 S01E01)。命中即全量重置自愈,
     * 覆盖"改季发生在重置功能上线之前"的存量订阅 —— 点一次「检查」即恢复,无需删订重订。
     */
    boolean staleSeasonInventory(MediaSubscription subscription) {
        if (subscription.getSeason() == null || subscription.getSeason() <= 0) {
            return false;
        }
        List<MediaSubscriptionEpisodeSource> live = episodeSourceRepository
                .findBySubscriptionAndStatesIn(subscription.getId(), LIVE_STATES);
        if (live.isEmpty()) {
            return false;
        }
        Map<Integer, Integer> seasonByEpisodeId = new HashMap<>();
        for (MediaSubscriptionEpisode episode : episodeRepository.findBySubscriptionIdOrderByNumber(subscription.getId())) {
            seasonByEpisodeId.put(episode.getId(), episode.getSeason());
        }
        for (MediaSubscriptionEpisodeSource row : live) {
            Integer season = seasonByEpisodeId.get(row.getEpisodeId());
            if (season != null && season > 0 && season != subscription.getSeason()) {
                return true; // 特别篇 season=0 不算:合法的跨季附属内容
            }
        }
        return false;
    }

    /** 播放全源失败打标(播放期是信噪比最高的失效信号):对 ACTIVE 订阅无额外作用(下轮巡检本来就跑),对 ENDED 订阅则越过轻查短路跑一次完整巡检。 */
    public void markPlaybackFailure(int subscriptionId) {
        playbackFailed.add(subscriptionId);
    }

    /** 订阅删除入口(delete() 在卸载/删行前调用):打取消标记并清内存态,进行中的巡检
     * 在下一道阶段检查点({@link #stopIfDeleted})收工。 */
    public void onDeleted(int subscriptionId) {
        deleted.add(subscriptionId);
        forget(subscriptionId, null);
    }

    /** 巡检中止检查点:订阅已删即回收本轮残留并返回 true,调用方立即收工(单次 set 查询,零 DB)。 */
    boolean stopIfDeleted(int subscriptionId) {
        if (!deleted.contains(subscriptionId)) {
            return false;
        }
        cleanupDeleted(subscriptionId);
        return true;
    }

    /**
     * 删除竞态的残留回收:巡检在 delete() 清库后又写入的资源行/挂载 share(常驻非 temp,享受
     * 清理豁免)/集源行/事件行无人再清,成为永久孤儿 —— 用户视角是已删的剧还顶在 AList 目录里。
     * 幂等,无残留即空操作;标记不摘除(id 不复用,防御 cleanup 与 delete() 并发期间其它任务漏拦)。
     */
    void cleanupDeleted(int subscriptionId) {
        List<MediaSubscriptionResource> resources = resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscriptionId);
        for (MediaSubscriptionResource resource : resources) {
            if (resource.getShareId() != null) {
                unmountShareIfUnused(resource.getShareId(), subscriptionId);
            }
        }
        List<Integer> resourceIds = resources.stream().map(MediaSubscriptionResource::getId).toList();
        if (!resourceIds.isEmpty()) {
            episodeSourceRepository.deleteByResourceIdIn(resourceIds);
        }
        episodeRepository.deleteBySubscriptionId(subscriptionId);
        if (!resources.isEmpty()) {
            resourceRepository.deleteBySubscriptionId(subscriptionId);
        }
        eventRepository.deleteBySubscriptionId(subscriptionId);
        forget(subscriptionId, resourceIds);
        if (!resources.isEmpty()) {
            log.info("media subscription {} removed during check: cleaned {} orphan resources",
                    subscriptionId, resources.size());
        }
    }

    /** 尾部落库门禁:订阅已删则回收残留并跳过 save —— 实体无 @Version,detached merge
     * 对已删行会把整行 INSERT 复活。 */
    private void saveUnlessDeleted(int id, MediaSubscription subscription) {
        if (!stopIfDeleted(id)) {
            subscriptionRepository.save(subscription);
        }
    }

    /** 订阅删除后清理全部内存态(限频/轮次/冷却 Map 按订阅/资源 id 键控,不清理即无界泄漏)。 */
    public void forget(int subscriptionId, List<Integer> resourceIds) {
        inFlight.remove(subscriptionId);
        playbackFailed.remove(subscriptionId);
        coverPrewarmInFlight.remove(subscriptionId);
        preheatAheadInFlight.remove(subscriptionId);
        gapSearchRounds.remove(subscriptionId);
        mainDriveSearchTime.remove(subscriptionId);
        driveLineKickTime.remove(subscriptionId);
        preheatAheadTime.remove(subscriptionId);
        aheadRescueTime.remove(subscriptionId);
        if (resourceIds != null) {
            resourceIds.forEach(transientStreak::remove);
        }
    }

    /** ERROR 自愈(§10.6):每日自动重试一次;连续 7 天失败提示人工检查。 */
    private void retryErrors() {
        long now = System.currentTimeMillis();
        for (MediaSubscription subscription : subscriptionRepository.findAll()) {
            if (!MediaSubscription.STATUS_ERROR.equals(subscription.getStatus())) {
                continue;
            }
            long last = subscription.getLastCheckTime() == null ? 0 : subscription.getLastCheckTime();
            if (now - last < 20L * 3600_000) {
                continue;
            }
            if (subscription.getUpdatedTime() != null && now - subscription.getUpdatedTime() > 7L * 24 * 3600_000
                    && now - last < 26L * 3600_000) {
                addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "连续失败超过 7 天,请人工检查(关键词/源可用性)");
            }
            log.info("retry ERROR subscription {}", subscription.getId());
            submitCheck(subscription.getId());
        }
    }

    /** 手动触发检查(异步,前端刷新列表看结果)。 */
    public void checkAsync(int uid, int id) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new cn.har01d.alist_tvbox.exception.BadRequestException("订阅不存在: " + id);
        }
        executor.submit(() -> check(id));
    }

    /**
     * 播放后前瞻验证:用户正在追 = 接下来大概率连播后面的集,后台顺带把已上架的接下来 N 集
     * 最优源字节级探测一遍,提前发现死链并自动补源 —— 否则要等常规巡检(6~12h)的每源 1 集抽验
     * 慢慢轮到,或用户播到那一集当场卡住。播放请求线程只做去重/限频判断即返回,探测全在后台。
     */
    public void preheatAheadAsync(int uid, int subscriptionId, int playedEpisode) {
        if (!preheatAheadInFlight.add(subscriptionId)) {
            return;
        }
        long interval = Math.max(1, appProperties.getSubscription().getPreheatAheadIntervalHours()) * 3600_000L;
        long now = System.currentTimeMillis();
        Long last = preheatAheadTime.get(subscriptionId);
        if (last != null && now - last < interval) {
            preheatAheadInFlight.remove(subscriptionId);
            return;
        }
        preheatAheadTime.put(subscriptionId, now);
        try {
            executor.submit(() -> {
                try {
                    MediaSubscription subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
                    if (subscription == null || subscription.getUid() != uid) {
                        return;
                    }
                    preheatAhead(subscription, playedEpisode);
                    stopIfDeleted(subscriptionId); // 探测期间订阅被删:回收刚写的集源行,任务到此为止
                } catch (Exception e) {
                    log.warn("preheat ahead for subscription {} failed: {}", subscriptionId, e.getMessage());
                } finally {
                    preheatAheadInFlight.remove(subscriptionId);
                }
            });
        } catch (Exception e) {
            // 池已关闭等提交失败:释放去重标记,别把订阅永久卡死
            preheatAheadInFlight.remove(subscriptionId);
            log.debug("submit preheat ahead for subscription {} failed: {}", subscriptionId, e.getMessage());
        }
    }

    /**
     * 封面快照后台预热:列表接口只读本地(coverOf),缺失时由此异步拉一次 details 回填 cover_url,
     * 下次刷新即可见。不受 refreshMetadata 的 24h 节流限制 —— 冷启动(metaSyncTime 尚新但快照从未写过)也要能补。
     */
    public void prewarmCoverAsync(MediaSubscription subscription) {
        if (subscription == null || subscription.getId() == null
                || StringUtils.isBlank(subscription.getMetaProvider()) || StringUtils.isBlank(subscription.getMetaId())) {
            return;
        }
        int id = subscription.getId();
        if (!coverPrewarmInFlight.add(id)) {
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    MediaSubscription current = subscriptionRepository.findById(id).orElse(null);
                    if (current == null || StringUtils.isNotBlank(current.getCoverUrl())) {
                        return;
                    }
                    MetadataDetails details = metadataService.details(
                            current.getMetaProvider(), current.getMetaId(), current.getSeason());
                    if (details != null && StringUtils.isNotBlank(details.getCover())) {
                        subscriptionRepository.updateCoverUrl(id,
                                StringUtils.abbreviate(details.getCover(), 500)); // cover_url 列 VARCHAR(512)
                    }
                } catch (Exception e) {
                    log.debug("cover prewarm {} failed: {}", id, e.getMessage());
                } finally {
                    coverPrewarmInFlight.remove(id);
                }
            });
        } catch (Exception e) {
            // 池已关闭等提交失败:释放去重标记(与 preheatAheadAsync 同规),列表接口不能被 submit 异常炸掉
            coverPrewarmInFlight.remove(id);
            log.debug("submit cover prewarm for subscription {} failed: {}", id, e.getMessage());
        }
    }

    /** 手动激活候选池中的指定资源(异步换源)。 */
    /** 钉选:立即激活为主源并标记永久优先 —— 归属复核不再自动换走,换源候选序置顶;
     * 失效退役不清除钉选,恢复可用后优先回归。每订阅一个钉选位,钉新清旧。
     * 激活失败钉选保留(下轮换源仍优先试它),失败原因经 activateAsync 既有事件上报。 */
    public void pinAsync(int uid, int id, int resourceId) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new cn.har01d.alist_tvbox.exception.BadRequestException("订阅不存在: " + id);
        }
        MediaSubscriptionResource resource = resourceRepository.findById(resourceId).orElse(null);
        if (resource == null || resource.getSubscriptionId() != id) {
            throw new cn.har01d.alist_tvbox.exception.BadRequestException("候选资源不存在: " + resourceId);
        }
        applyPin(id, resourceId);
        addEvent(id, MediaSubscriptionEvent.TYPE_PINNED,
                "已钉选主源:" + StringUtils.defaultIfBlank(resource.getTitle(), resource.getLink())
                        + "(自动换源不再覆盖,取消钉选恢复自动)", false);
        activateAsync(uid, id, resourceId);
    }

    /** 取消钉选:只清标记,当前挂载不动,自动换源恢复。 */
    public void unpinAsync(int uid, int id, int resourceId) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new cn.har01d.alist_tvbox.exception.BadRequestException("订阅不存在: " + id);
        }
        MediaSubscriptionResource resource = resourceRepository.findById(resourceId).orElse(null);
        if (resource == null || resource.getSubscriptionId() != id) {
            throw new cn.har01d.alist_tvbox.exception.BadRequestException("候选资源不存在: " + resourceId);
        }
        if (Boolean.TRUE.equals(resource.getPinned())) {
            resource.setPinned(false);
            resourceRepository.save(resource);
        }
        addEvent(id, MediaSubscriptionEvent.TYPE_PINNED, "已取消钉选,恢复自动换源", false);
    }

    /** 钉选置位(同步):目标行置 true、同订阅其余行清 false(每订阅一个钉选位)。 */
    void applyPin(int id, int resourceId) {
        for (MediaSubscriptionResource r : resourceRepository.findBySubscriptionIdOrderByScoreDesc(id)) {
            boolean pin = r.getId().equals(resourceId);
            if (pin != Boolean.TRUE.equals(r.getPinned())) {
                r.setPinned(pin);
                resourceRepository.save(r);
            }
        }
    }

    public void activateAsync(int uid, int id, int resourceId) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new cn.har01d.alist_tvbox.exception.BadRequestException("订阅不存在: " + id);
        }
        MediaSubscriptionResource resource = resourceRepository.findById(resourceId).orElse(null);
        if (resource == null || resource.getSubscriptionId() != id) {
            throw new cn.har01d.alist_tvbox.exception.BadRequestException("候选资源不存在: " + resourceId);
        }
        executor.submit(() -> {
            if (!tryLock(id)) {
                return;
            }
            try {
                // 锁内取新实体:排队期间 doCheck/手动刷新可能已整行保存,旧实体再 save 会回滚覆盖
                MediaSubscription current = subscriptionRepository.findById(id).orElse(null);
                if (current == null) {
                    return;
                }
                activate(current, resource);
                if (stopIfDeleted(id)) {
                    return;
                }
                subscriptionRepository.save(current);
                scheduleTransferAfterCheck(current);
            } catch (Exception e) {
                log.warn("activate resource {} failed: {}", resourceId, e.getMessage());
                if (stopIfDeleted(id)) {
                    return; // 订阅已删:不走退役路径(retireResource 会把资源行 INSERT 复活成孤儿)
                }
                MediaSubscription current = subscriptionRepository.findById(id).orElse(subscription);
                if (isForeignShowRejection(e.getMessage())) {
                    retireAlienCandidate(current, resource); // 异剧不拉黑(链接没死,只是不属于本剧)
                } else if (isThrottleError(e.getMessage())) {
                    // 限流不是资源失效:退役+拉黑会把好源烧成 90 天黑名单,退避后再试即可
                    throttleDrive(driveOf(resource));
                    resource.setCheckedTime(System.currentTimeMillis());
                    resourceRepository.save(resource);
                } else {
                    retireResource(current, resource, e.getMessage(), true);
                }
                addEvent(id, MediaSubscriptionEvent.TYPE_ERROR, "手动换源失败:" + e.getMessage());
            } finally {
                inFlight.remove(id);
            }
        });
    }

    public void check(Integer id) {
        if (!tryLock(id)) {
            log.debug("subscription {} check already running", id);
            return;
        }
        try {
            // 锁内取新实体:排队等待期间其他任务可能已整行保存,旧实体再 save 会把
            // currentEpisodes/nextCheckTime 等字段整体回滚覆盖
            MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
            if (subscription == null || MediaSubscription.STATUS_PAUSED.equals(subscription.getStatus())) {
                return;
            }
            boolean playbackFailure = playbackFailed.remove(id);
            if (MediaSubscription.STATUS_ENDED.equals(subscription.getStatus())) {
                if (!playbackFailure && !reopenEnded(subscription) && !staleSeasonReopen(subscription)) {
                if (!watchingRecently(subscription)) {
                    // 完结看完:官方加重重开场景每日一查纯属浪费,拉长到每周(重开路 playEpisode
                    // 失败/加更/换季残留/异剧四条都由即时信号触发,不依赖这轮轻查)
                    subscription.setNextCheckTime(System.currentTimeMillis() + 7 * 24 * 3600_000L);
                    saveUnlessDeleted(id, subscription);
                    return;
                }
                    // 完结≠看完:仍在追看的完结剧,资源可播性照在播维护(轻查只看集数,发现不了死源)。
                    // 保持 ENDED 直接跑完整巡检 —— shouldAutoEnd 的 !ENDED 守卫不会重复写完结事件
                }
                if (playbackFailure) {
                    // 播放全源失败 = 资源可播性出问题,轻查(只看集数)永远发现不了 —— 回 ACTIVE 走完整巡检;
                    // 巡检尾部 shouldAutoEnd 会在资源恢复正常后重新完结,状态口径与其它重开路一致
                    subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
                    subscription.setStallCount(0);
                    addEvent(id, MediaSubscriptionEvent.TYPE_RESUMED, "播放失败,重开完整巡检检查资源");
                    log.info("subscription {} reopened: playback failure", id);
                }
            }
            doCheck(subscription);
            saveUnlessDeleted(id, subscription);
            scheduleTransferAfterCheck(subscription);
        } catch (Exception e) {
            MediaSubscription failed = subscriptionRepository.findById(id).orElse(null);
            if (failed == null) {
                // 删除与巡检并发的尾窗(尾部 save 撞上删行抛乐观锁):不是故障,回收本轮残留即收工
                log.info("check subscription {} aborted: subscription deleted", id);
                cleanupDeleted(id);
                return;
            }
            log.warn("check subscription {} failed: {}", id, e.getMessage(), e);
            failed.setStatus(MediaSubscription.STATUS_ERROR);
            failed.setUpdatedTime(System.currentTimeMillis());
            addEvent(id, MediaSubscriptionEvent.TYPE_ERROR, "巡检失败:" + e.getMessage());
            scheduleNext(failed);
            saveUnlessDeleted(id, failed);
        } finally {
            inFlight.remove(id);
        }
    }

    /** 巡检/手动换源完成后 TRANSFER 订阅立即排队增量转存(设计口径「发现新集后 copy」):
     *  此前只有每小时 :40 自愈 sweep 和手动按钮两个入口,新建订阅首轮巡检把源挂载齐后
     *  要空等最长一小时才轮到转存,用户侧观感即「建了订阅根本没转存」。
     *  transferAsync 自身幂等(目标已齐即空手而归,不占日配额),与 :40 sweep 的并发
     *  由转存单线程执行器串行化,无需去重。 */
    private void scheduleTransferAfterCheck(MediaSubscription subscription) {
        if (transferServiceProvider == null || !MediaSubscription.MODE_TRANSFER.equals(subscription.getMode())) {
            return;
        }
        MediaSubscriptionTransferService transferService = transferServiceProvider.getIfAvailable();
        if (transferService != null) {
            transferService.transferAsync(subscription.getUid(), subscription.getId());
        }
    }

    /**
     * ENDED 订阅的换季残留重开:旧季集源行冒领集数把 {@link #shouldReopen} 堵死
     * (本地 = 官方总数,永不满足"官方 > 本地"),主源复核对裸标题合集("2季全"解析不出季号)也放行 ——
     * 换季后订阅永远停在 ENDED,点「检查」只刷元数据就返回(线上:末日地堡 S1 改 S3,doCheck 从未执行)。
     * 残留检测命中即回 ACTIVE 走完整巡检,doCheck 开头的同一检测会全量重置并按本季重搜重挂。
     */
    boolean staleSeasonReopen(MediaSubscription subscription) {
        if (!staleSeasonInventory(subscription)) {
            return false;
        }
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setStallCount(0);
        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_RESUMED,
                "检测到换季资源残留,重开按本季重新搜索");
        log.info("subscription {} reopened: stale season inventory", subscription.getId());
        return true;
    }

    /** ENDED 订阅每日轻量复查(只刷元数据,不列源不搜索):官方已播/手填期望超过本地集数 = 加更或集数修正,自动回 ACTIVE。 */
    boolean reopenEnded(MediaSubscription subscription) {        refreshMetadata(subscription);
        if (!shouldReopen(subscription)) {
            // 异剧污染回滚:真人版同名资源把 currentEpisodes 撑过官方集数,反而把上面的重开条件堵死
            // (本地 37 > 官方 26 永不重开)。主源集号超范围 = 误挂异剧,重开走完整巡检
            // (doCheck 的归属复核会自动换正确源,集数快照/maxEpisode 随之归位)。
            MediaSubscriptionResource primary = primaryResource(subscription);
            if (primary != null && !Boolean.TRUE.equals(primary.getPinned())
                    && !belongsToShow(subscription, primary)) {
                subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
                subscription.setStallCount(0);
                addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_RESUMED,
                        "主源疑似误挂同名异剧,重开校正:" + StringUtils.defaultString(primary.getTitle()));
                log.info("subscription {} reopened: alien primary [{}]",
                        subscription.getId(), primary.getTitle());
                return true;
            }
            return false;
        }
        int local = subscription.getCurrentEpisodes() == null ? 0 : subscription.getCurrentEpisodes();
        int target = Math.max(
                subscription.getOfficialEpisodes() == null ? 0 : subscription.getOfficialEpisodes(),
                subscription.getExpectedEpisodes() == null ? 0 : subscription.getExpectedEpisodes());
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setStallCount(0);
        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_RESUMED,
                "官方集数上调(" + local + " → " + target + "),自动重开追更");
        log.info("subscription {} reopened: {} -> {}", subscription.getId(), local, target);
        return true;
    }

    /** 重开判定:官方已播或手填期望集数 > 本地集数。 */
    boolean shouldReopen(MediaSubscription subscription) {
        int local = liveEpisodeNumbers(subscription).size();
        Integer official = subscription.getOfficialEpisodes();
        Integer expected = subscription.getExpectedEpisodes();
        return (official != null && official > local) || (expected != null && expected > local);
    }

    private void doCheck(MediaSubscription subscription) {
        if (stopIfDeleted(subscription.getId())) {
            return;
        }
        subscription.setLastCheckTime(System.currentTimeMillis());
        purgeForeignSeasonResources(subscription);
        if (staleSeasonInventory(subscription)) {
            // 改季残留(改季发生在重置功能之前):旧季集源行冒领集号,先卸全部挂载再全量重置,
            // 随后 shareId=null 自然落 ensureSource 分支按本季重搜重挂
            List<Integer> shareIds = new ArrayList<>();
            for (MediaSubscriptionResource resource : resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId())) {
                if (resource.getShareId() != null) {
                    shareIds.add(resource.getShareId());
                }
            }
            if (subscription.getShareId() != null) {
                shareIds.add(subscription.getShareId());
            }
            int season = subscription.getSeason();
            resetInventoryForSeason(subscription, season);
            for (Integer shareId : new java.util.LinkedHashSet<>(shareIds)) {
                unmountShareIfUnused(shareId, subscription.getId());
            }
        }
        refreshMetadata(subscription);
        if (stopIfDeleted(subscription.getId())) {
            return;
        }

        if (subscription.getShareId() == null || shareRepository.findById(subscription.getShareId()).isEmpty()) {
            // 共享挂载收编:同路径已有别的订阅挂好的有效主源时直接复用,不搜索不换挂不补缺
            if (!adoptExistingMount(subscription)) {
                ensureSource(subscription);
            }
            if (subscription.getShareId() == null || shareRepository.findById(subscription.getShareId()).isEmpty()) {
                scheduleNext(subscription); // 连主源都挂不上:无目录可巡,等下轮再搜
                return;
            }
            // 首轮即挂上主源:不提前收工,继续走集源同步/缺集补全 —— 否则新订阅第一次打开只有
            // 主源口径(线上:主源仅尾部10集,缺集补全要等 24h 后的下一轮巡检才轮到)
        }

        // 新集判定基准:本轮任何写操作之前先快照"本地已有集"(集源行 LISTED/VERIFIED ∪)
        Set<Integer> previous = liveEpisodeNumbers(subscription);

        // 失效确认:列目录失败先静默重试一次(瞬时抖动);仍失败再探测 AList 健康,
        // 服务整体不可用时不能把失败归因于主源(防误杀好源+污染候选池);
        // 限流(如百度 errno -62)同样不是主源的错 —— 退避重试,不判失效
        TreeMap<Integer, EpisodeFile> files = null;
        String invalidReason = null;
        for (int attempt = 1; attempt <= 2 && files == null; attempt++) {
            try {
                files = listEpisodeFiles(subscription);
            } catch (Exception e) {
                invalidReason = e.getMessage();
                log.info("subscription {} primary listing failed (attempt {}): {}", subscription.getId(), attempt, e.getMessage());
            }
        }
        if (files == null) {
            if (isThrottleError(invalidReason)) {
                log.warn("subscription {} skipped: drive throttled, retry later", subscription.getId());
                subscription.setNextCheckTime(System.currentTimeMillis() + INVALID_RETRY_DELAY_MS);
                return;
            }
            if (!isAListHealthy()) {
                log.warn("subscription {} skipped: AList unavailable, retry later", subscription.getId());
                subscription.setNextCheckTime(System.currentTimeMillis() + INVALID_RETRY_DELAY_MS);
                return;
            }
            onInvalid(subscription, invalidReason);
            scheduleNext(subscription);
            return;
        }

        // 集源同步:主源行落库(新文件 LISTED、消失文件 MISSING),补缺挂载原位刷新,
        // 刷不出内容的死挂载就地退役 —— 缺陷 4"旧快照冒领集数"的数据层终结
        MediaSubscriptionResource primary = primaryResource(subscription);
        // 空壳主源:挂载列不出任何本季可识别文件 —— 换季后旧季合集的常态(「第一/二季」目录被
        // otherSeasonDir 拒入、S01Eyy 被 parseEpisode(season) 拒收),列目录不报错、失效探测也正常,
        // 唯一信号就是文件集为空。与误挂异剧同路换源,不让空壳拖到下轮。
        boolean primaryHollow = primary != null && files.isEmpty();
        boolean primaryBelongs = primary == null || belongsToShow(subscription, primary, files.keySet());
        if (primary != null && !primaryBelongs && !primaryHollow && Boolean.TRUE.equals(primary.getPinned())) {
            log.info("subscription {} pinned primary failed ownership recheck, kept (user override): {}",
                    subscription.getId(), primary.getTitle());
        }
        if (primary != null && shouldReplacePrimary(primary, primaryBelongs, primaryHollow)) {
            // 误挂异业主源(线上:「悬案解码」2025 顶在「悬案」2026 的固定路径上):列目录/流探测都正常,
            // 巡检没有天然失效信号 —— 用与候选池同一套归属+年份门禁就地复核(集号用本轮清洗后的
            // 文件集,防噪声剔除上线前的存量毒行 142 误判主体正确的主源),不符即换源;
            // activate 会把旧主源降级回候选池(行落 MISSING,不进黑名单:链接没死,只是不属于本剧)。
            String alienReason = primaryHollow ? "主源无可识别的本季剧集文件:" : "主源与剧集不符(误挂异剧):";
            if (!activateNextCandidate(subscription)) {
                fillPool(subscription, true, null);
                activateNextCandidate(subscription);
            }
            if (subscription.getShareId() == null || shareRepository.findById(subscription.getShareId()).isEmpty()) {
                // 先按清洗后的文件集重列主源行:存量毒行(26+142)落 MISSING,否则下轮复核仍旧误判,
                // 每轮都强制全量搜索+推一条错误事件,直到偶然召回同剧候选才解套
                syncInventory(subscription, primary, subscription.getMountPath(), files);
                addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR,
                        alienReason + StringUtils.defaultString(primary.getTitle()) + ",暂无同剧候选,待补池换源");
                scheduleNext(subscription);
                return;
            }
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_SOURCE_INVALID,
                    alienReason + StringUtils.defaultString(primary.getTitle()) + ",已自动换源");
            primary = primaryResource(subscription);
            try {
                files = listEpisodeFiles(subscription);
            } catch (Exception e) {
                // listEpisodeFiles 只抛错不返回 null:换源后新主源列不出目录走失效换源链,
                // 而不是把异常漏到 check() 整轮判 ERROR
                onInvalid(subscription, "换源后新主源列目录失败:" + e.getMessage());
                scheduleNext(subscription);
                return;
            }
            if (files.isEmpty()) {
                onInvalid(subscription, "换源后新主源目录为空");
                scheduleNext(subscription);
                return;
            }
        }
        if (stopIfDeleted(subscription.getId())) {
            return;
        }
        syncInventory(subscription, primary, subscription.getMountPath(), files);
        refreshAuxMounts(subscription);

        if (stopIfDeleted(subscription.getId())) {
            return;
        }
        Set<Integer> addedSoFar = liveEpisodeNumbers(subscription);
        addedSoFar.removeAll(previous);
        Set<Integer> brokenNew = preheatEpisodes(subscription, addedSoFar);
        Set<Integer> present = liveEpisodeNumbers(subscription);
        if (present.isEmpty() && !files.isEmpty() && primary == null) {
            // 主源资源行缺失(旧数据丢行):目录明明列得出,行无处归属 —— 以目录观测兜底,
            // 保证 currentEpisodes/缺集判定不塌零;行本身等 primaryResource 收养或 ensureSource 重建
            present = new TreeSet<>(files.keySet());
        }
        applyInventory(subscription, present, new ArrayList<>(addedSoFar));
        // 通知门槛(§11 / 验收场景 7):新集必须**取链验证通过**才通知,且仅通知已追平的用户
        notifyNewEpisodes(subscription, addedSoFar.stream().filter(e -> !brokenNew.contains(e)).toList(), present.size());

        // 缺集检测:官方已播集数是权威触发源(§4.8);无官方数据回退期望集数/观测范围
        if (stopIfDeleted(subscription.getId())) {
            return;
        }
        Set<Integer> missing = computeMissing(subscription, present);
        if (!missing.isEmpty()) {
            fillGaps(subscription, new TreeSet<>(missing));
            // 补缺挂载本轮已把 LISTED 行落库,而上面的 applyInventory 用的是补前快照:
            // 不刷新的话 currentEpisodes 停在主源口径,页面"已更新至X集"要等下轮巡检(可达 6-24h)才追平
            Set<Integer> afterFill = liveEpisodeNumbers(subscription);
            if (afterFill.size() > present.size()) {
                present = afterFill;
                subscription.setCurrentEpisodes(present.size());
                subscription.setMaxEpisode(present.stream().max(Integer::compareTo).orElse(null));
                subscription.setUpdatedTime(System.currentTimeMillis());
            }
        } else {
            retireCoveredAuxMounts(subscription, present);
            // 停滞多轮且池中无可用备胎 → 搜索补池(主源未失效不主动换源,避免频繁扰动播放列表)
            if (subscription.getStallCount() >= appProperties.getSubscription().getStallRoundsBeforeSearch()) {
                fillPool(subscription, false, null);
            }
            detectUpgrade(subscription, present);
        }
        if (stopIfDeleted(subscription.getId())) {
            return;
        }
        sampleMounted(subscription);
        // 采样/传染判死可能刚把主源退役(挂载已删):同轮立即换源重挂固定路径 ——
        // 列目录失效路径(onInvalid)自带换源,判死路径原先没有,固定路径会空到下轮巡检(退避可达 24h),
        // TVBox 详情 404。放在 ensureMainDrives 之前,让新主源优先占住最佳候选并计入主盘覆盖。
        if (stopIfDeleted(subscription.getId())) {
            return;
        }
        if (subscription.getShareId() == null || shareRepository.findById(subscription.getShareId()).isEmpty()) {
            ensureSource(subscription);
        }
        if (stopIfDeleted(subscription.getId())) {
            return;
        }
        ensureMainDrives(subscription, present);
        if (stopIfDeleted(subscription.getId())) {
            return;
        }
        ensureDriveLines(subscription, present);
        scheduleNext(subscription);
    }

    /**
     * 新集通知(§11 / 验收场景 7):必须同时满足两个条件才发。
     * <ol>
     *   <li><b>播放源已验证可用</b> —— 调用方只传 preheat 取链成功的集。"发现即通知"会先推
     *       "更新 第18集"、随后再补一条"链接验证失败(疑似被和谐)",等于放羊。</li>
     *   <li><b>用户已追平</b> —— 只有看到过上一个最新集的人才需要被叫醒;还差十集没看的人
     *       不需要为第 18 集响一次。</li>
     * </ol>
     */
    private void notifyNewEpisodes(MediaSubscription subscription, List<Integer> verified, int total) {
        if (verified.isEmpty()) {
            return;
        }
        int newest = verified.stream().max(Integer::compareTo).orElse(0);
        int watched = watchedEpisode(subscription);
        // 回看防护:History 只存当前进度,追平后跳回前面集会把 watched 拉低 —— 追平线以内的
        // 集都是看过的,追平线抬高 watched,否则回看用户被误判"没追上"而收不到新集推送。
        if (subscription.getCaughtUpEpisode() != null && subscription.getCaughtUpEpisode() > watched) {
            watched = subscription.getCaughtUpEpisode();
        }
        // 追平判定:已看集 >= 本次新增之前的最新集(= 新增里的最小集 - 1)
        int previousNewest = verified.stream().min(Integer::compareTo).orElse(newest) - 1;
        if (watched > 0 && watched < previousNewest) {
            log.debug("subscription {} new episodes {} not notified: watched {} < {}",
                    subscription.getId(), verified, watched, previousNewest);
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_NEW_EPISODE,
                    "更新 第" + joinNumbers(verified) + " 集(共 " + total + " 集)", false);
            return;
        }
        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_NEW_EPISODE,
                "更新 第" + joinNumbers(verified) + " 集(共 " + total + " 集)");
    }

    /**
     * 观看进度:读播放记录,不自行存储 —— 多端进度由播放记录同步天然合并。
     * <p>
     * 优先解析 {@code episodeUrl} 里的逻辑链接 {@code msubep-{订阅}-{集}}(它带着真实集号);
     * 解析不出(用户切到了分盘线路、播的是物理地址)时退回 {@code History.episode} 选集下标 +1。
     * 当前集进度不足(刚点开几十秒的试看)折算为前一集 —— 见 {@link #episodeOfHistory}。
     * 注意 {@code MediaSubscription.maxEpisode} 是<b>资源侧</b>最大集号,与观看进度无关。
     */
    int watchedEpisode(MediaSubscription subscription) {
        if (historyRepository == null) {
            return 0;
        }
        try {
            int watched = 0;
            String vodId = MediaSubscriptionService.VOD_ID_PREFIX + subscription.getId();
            for (History history : historyRepository.findByUidAndVodId(subscription.getUid(), vodId)) {
                watched = Math.max(watched, episodeOfHistory(history));
            }
            return watched;
        } catch (Exception e) {
            log.debug("read watch progress failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 播放行折算已看集号:当前集<b>进度不足不算看完</b>,折算为 前一集。
     * 线上形态:33 集只点开看了几十秒,旧口径按集号直接算看完 → 追平标记被试看抬到 33,
     * 用户回看时「还没看完的最后一集」从此不亮角标。时长已知看比例(≥70%,含跳片头片尾
     * 的完整观看;completed 上报会把 position 夹紧到 duration,天然覆盖);时长未知或过短
     * 用绝对门槛(≥5 分钟),按播放位置判断 —— 位置小只可能发生在片头附近。
     */
    private static final long MIN_WATCHED_POSITION_MS = 300_000;

    private static int episodeOfHistory(History history) {
        Matcher matcher = MSUBEP_EPISODE.matcher(StringUtils.defaultString(history.getEpisodeUrl()));
        int episode = matcher.find() ? Integer.parseInt(matcher.group(1))
                : history.getEpisode() > 0 ? history.getEpisode() + 1 : 0; // 选集下标从 0 起
        return substantiallyWatched(history) ? episode : Math.max(episode - 1, 0);
    }

    private static boolean substantiallyWatched(History history) {
        long duration = history.getDuration();
        long position = Math.max(history.getPosition(), 0);
        if (duration >= MIN_WATCHED_POSITION_MS) {
            return position * 10 >= duration * 7; // ≥70%
        }
        return position >= MIN_WATCHED_POSITION_MS;
    }

    /**
     * ENDED 订阅是否仍在追看:近 {@link #RECENT_PLAY_WINDOW_MS} 内有播放记录,且未看完
     * (观看进度 < 本地可用集数)。完结≠看完 —— 这类订阅的资源可播性须照在播维护,
     * 分享失效才能被巡检发现并换源;看完/越窗没再看则回落每日轻查,不为闲置完结剧花巡检开销。
     */
    boolean watchingRecently(MediaSubscription subscription) {
        if (historyRepository == null) {
            return false;
        }
        int watched = watchedEpisode(subscription);
        if (watched <= 0) {
            return false; // 没有观看进度 = 没在追看
        }
        int local = liveEpisodeNumbers(subscription).size();
        if (local > 0 && watched >= local) {
            return false; // 已看完
        }
        try {
            String vodId = MediaSubscriptionService.VOD_ID_PREFIX + subscription.getId();
            long threshold = System.currentTimeMillis() - RECENT_PLAY_WINDOW_MS;
            for (History history : historyRepository.findByUidAndVodId(subscription.getUid(), vodId)) {
                long time = history.getUpdatedAt() != null ? history.getUpdatedAt() : history.getCreateTime();
                if (time >= threshold) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("read play history of subscription {} failed: {}", subscription.getId(), e.getMessage());
        }
        return false;
    }

    /** 版本升级提醒(§10.7):主源无 4K 而池中出现 4K 完整候选 → 提示(不自动替换)。 */
    private void detectUpgrade(MediaSubscription subscription, Set<Integer> present) {
        if (subscription.getStallCount() < appProperties.getSubscription().getStallRoundsBeforeSearch() || present.isEmpty()) {
            return;
        }
        MediaSubscriptionResource active = primaryResource(subscription);
        if (active == null || hasUhd(active.getTitle())) {
            return;
        }
        MediaSubscriptionResource candidate = resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId()).stream()
                .filter(r -> MediaSubscriptionResource.STATE_CANDIDATE.equals(r.getState())
                        && episodeSourceRepository.countByResourceId(r.getId()) == 0 && hasUhd(r.getTitle()))
                .findFirst().orElse(null);
        if (candidate == null) {
            return;
        }
        try {
            probeShare(subscription, candidate);
            candidate.setCheckedTime(System.currentTimeMillis());
            resourceRepository.save(candidate);
            Set<Integer> coverage = coverageOf(candidate);
            if (!coverage.isEmpty() && coverage.containsAll(present)) {
                addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_UPGRADE_AVAILABLE,
                        "发现更优画质完整源:" + StringUtils.defaultIfBlank(candidate.getTitle(), candidate.getLink())
                                + "(" + coverage.size() + "集 · 4K),可在候选池手动启用");
            }
        } catch (Exception e) {
            log.debug("upgrade probe failed: {}", e.getMessage());
        }
    }

    private static boolean hasUhd(String title) {
        return StringUtils.containsIgnoreCase(title, "4K") || StringUtils.containsIgnoreCase(title, "2160");
    }

    // ---------- 元数据(§4.8) ----------

    /** 每日至多一次刷新官方集数/状态/下集播出时间;失败静默降级,不影响巡检。 */
    private void refreshMetadata(MediaSubscription subscription) {
        refreshMetadata(subscription, appProperties.getSubscription().getMetaRefreshIntervalHours() * 3600_000L);
    }

    /** minIntervalMs 内已刷过则跳过;日程全空的订阅不受间隔限制:provider 侧桥接能力升级(如豆瓣名称桥接)后,下一轮即能补上播出时间轴。 */
    private void refreshMetadata(MediaSubscription subscription, long minIntervalMs) {
        if (StringUtils.isBlank(subscription.getMetaProvider()) || StringUtils.isBlank(subscription.getMetaId())) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean noSchedule = subscription.getNextAirTime() == null && StringUtils.isBlank(subscription.getSchedule());
        if (subscription.getMetaSyncTime() != null && now - subscription.getMetaSyncTime() < minIntervalMs && !noSchedule) {
            return;
        }
        subscription.setMetaSyncTime(now);
        MetadataDetails details = metadataService.details(subscription.getMetaProvider(), subscription.getMetaId(), subscription.getSeason());
        if (details == null) {
            return;
        }
        applyMetadataSnapshot(subscription, details);
    }

    /** 详情页"刷新元数据":穿透缓存直取外网,无视节流立即重写订阅快照与 media_metadata 表。 */
    public void refreshMetadataAsync(int uid, int id) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new cn.har01d.alist_tvbox.exception.BadRequestException("订阅不存在: " + id);
        }
        executor.submit(() -> {
            try {
                MediaSubscription current = subscriptionRepository.findById(id).orElse(null);
                if (current == null || StringUtils.isBlank(current.getMetaProvider())
                        || StringUtils.isBlank(current.getMetaId())) {
                    return;
                }
                MetadataDetails details = metadataService.refreshDetails(
                        current.getMetaProvider(), current.getMetaId(), current.getSeason());
                if (details == null) {
                    return;
                }
                current.setMetaSyncTime(System.currentTimeMillis());
                applyMetadataSnapshot(current, details);
                if (stopIfDeleted(id)) {
                    return;
                }
                subscriptionRepository.save(current);
                log.info("media subscription {} metadata refreshed by user", id);
            } catch (Exception e) {
                log.warn("refresh metadata {} failed: {}", id, e.getMessage());
            }
        });
    }

    /**
     * 详情页"检查更新"(轻量,atv-player check_record 语义):刷新元数据 → 官方已播 vs 本地已有 → 结论进事件流。
     * 不做资源搜索/挂载(那是列表"检查"完整巡检的事),秒级完成。
     */
    public void checkUpdateAsync(int uid, int id) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new cn.har01d.alist_tvbox.exception.BadRequestException("订阅不存在: " + id);
        }
        executor.submit(() -> checkUpdateInternal(id));
    }

    /** TVBox 操作线路「检查更新」:同步执行轻量检查,返回结论文本(msg 回执)。 */
    public String checkUpdateNow(int uid, int id) {
        MediaSubscription subscription = subscriptionRepository.findById(id).orElse(null);
        if (subscription == null || subscription.getUid() != uid) {
            throw new cn.har01d.alist_tvbox.exception.BadRequestException("订阅不存在: " + id);
        }
        return checkUpdateInternal(id);
    }

    /** 轻量检查核心:刷新元数据 → 官方已播 vs 本地已有 → 结论进事件流并返回文本。 */
    private String checkUpdateInternal(int id) {
        try {
            MediaSubscription current = subscriptionRepository.findById(id).orElse(null);
            if (current == null) {
                return "订阅已删除";
            }
            if (StringUtils.isBlank(current.getMetaProvider()) || StringUtils.isBlank(current.getMetaId())) {
                return event(id, "未绑定元数据条目,无法检查官方更新");
            }
            MetadataDetails details = metadataService.refreshDetails(
                    current.getMetaProvider(), current.getMetaId(), current.getSeason());
            if (details == null) {
                return event(id, "检查更新失败:元数据源不可用,稍后重试");
            }
            current.setMetaSyncTime(System.currentTimeMillis());
            applyMetadataSnapshot(current, details);
            if (stopIfDeleted(id)) {
                return "订阅已删除";
            }
            subscriptionRepository.save(current);

            int official = details.getAiredEpisodes() == null ? 0 : details.getAiredEpisodes();
            if (official <= 0) {
                return event(id, "官方暂无已播集数信息(" + current.getMetaProvider() + "未提供)");
            }
            Set<Integer> local = liveEpisodeNumbers(current);
            List<Integer> missing = new ArrayList<>();
            for (int i = 1; i <= Math.min(official, 500); i++) {
                if (!local.contains(i)) {
                    missing.add(i);
                }
            }
            if (missing.isEmpty()) {
                return event(id, "官方已播至第 " + official + " 集,本地已全部同步");
            }
            String summary = missing.size() <= 8
                    ? missing.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("")
                    : missing.get(0) + "-" + missing.get(missing.size() - 1) + " 等 " + missing.size() + " 集";
            log.info("media subscription {} update checked by user: official={} missing={}",
                    id, official, missing.size());
            return event(id, "官方已播至第 " + official + " 集,本地缺第 " + summary + " 集(点列表「巡检」立即搜索挂载)");
        } catch (Exception e) {
            log.warn("check update {} failed: {}", id, e.getMessage());
            return "检查更新失败:" + e.getMessage();
        }
    }

    /** 结论写入 UPDATE_CHECK 事件流(web 详情页时间线)并返回原文(TVBox msg 回执)。 */
    private String event(int id, String message) {
        addEvent(id, MediaSubscriptionEvent.TYPE_UPDATE_CHECK, message);
        return message;
    }

    /** 元数据 → 订阅行快照(封面/官方集数/状态/日程/别名),refreshMetadata 与手动刷新共用。 */
    private void applyMetadataSnapshot(MediaSubscription subscription, MetadataDetails details) {
        if (StringUtils.isNotBlank(details.getCover())) {
            // 外部 URL 可能超 cover_url 列宽(VARCHAR 512):不截断会 22001 炸整轮巡检
            subscription.setCoverUrl(StringUtils.abbreviate(details.getCover(), 500)); // 封面快照:列表接口纯读库,不再实时查 provider
        }
        // provider 降级只覆盖部分字段时不能把已知快照洗掉:官方集数门禁(集号范围/标题宣称)与
        // ENDED 重开判定都依赖这两个值,null(未知)保留旧值,非 null(含修正)照常更新
        if (details.getAiredEpisodes() != null) {
            subscription.setOfficialEpisodes(details.getAiredEpisodes());
        }
        if (details.getTotalEpisodes() != null) {
            subscription.setOfficialTotal(details.getTotalEpisodes());
        }
        subscription.setOfficialStatus(details.getStatus());
        subscription.setNextAirTime(details.getNextAirTime());
        if (details.getAliases() != null) {
            // 别名快照(换行分隔):标题归属匹配用;单条过长/为空的丢弃,总量限幅。
            // 归一化为空的死别名(纯假名/西里尔/阿拉伯文等)必须一并丢弃:matchesTitle 对它们
            // 永不命中,白占 12 席会把「海贼王」这类常用旧译名挤出去,旧译名分享反被标题门禁误杀
            String joined = details.getAliases().stream()
                    .map(String::trim)
                    .filter(a -> a.length() >= 2 && a.length() <= 100)
                    .filter(a -> !normalizeForMatch(a).isBlank())
                    .distinct()
                    .limit(12)
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse(null);
            subscription.setAliases(joined);
        }
        // 播出日程快照(昨日 00:00 ~ +14 天窗口,播放时间轴用);provider 未提供日程时保留旧快照
        if (details.getUpcoming() != null) {
            java.time.ZoneId zone = java.time.ZoneId.of(cn.har01d.alist_tvbox.util.Constants.ZONE_ID);
            long windowStart = java.time.LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long windowEnd = System.currentTimeMillis() + 14L * 24 * 3600_000;
            List<EpisodeAirDate> windowed = details.getUpcoming().stream()
                    .filter(e -> e.getAirTime() >= windowStart && e.getAirTime() <= windowEnd)
                    .limit(60).toList();
            try {
                subscription.setSchedule(objectMapper.writeValueAsString(windowed));
            } catch (Exception e) {
                log.debug("serialize schedule failed: {}", e.getMessage());
            }
        }
        applyCustomAirClock(subscription);
    }

    /** "H:mm"/"HH:mm" 归一为 "HH:mm";空/非法返回 null(调用方决定拒绝或忽略)。 */
    static String normalizeAirClock(String clock) {
        if (StringUtils.isBlank(clock)) {
            return null;
        }
        Matcher matcher = AIR_CLOCK.matcher(clock.trim());
        if (!matcher.matches()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        if (hour > 23 || minute > 59) {
            return null;
        }
        return String.format("%02d:%02d", hour, minute);
    }

    /** 手动播出时刻重放:customAirClock("HH:mm")改写 schedule 快照与 nextAirTime 的时分(日期不动),
     *  nextAirTime 按改写后的日程重取第一个未来条目。只校正时刻不造日期 —— 无日程的剧改完仍无触发,
     *  由高峰档位兜底(scheduleNext)。挂在 applyMetadataSnapshot 尾部:每次刷新重写快照后重放,
     *  优先级 手动 > PlayScheduleBridge 平台桥 > 默认 20:00(天然覆盖前两者)。 */
    void applyCustomAirClock(MediaSubscription subscription) {
        String normalized = normalizeAirClock(subscription.getCustomAirClock());
        if (normalized == null) {
            return;
        }
        LocalTime time = LocalTime.parse(normalized);
        ZoneId zone = ZoneId.of(Constants.ZONE_ID);
        long now = System.currentTimeMillis();
        if (StringUtils.isNotBlank(subscription.getSchedule())) {
            try {
                List<EpisodeAirDate> entries = objectMapper.readValue(subscription.getSchedule(),
                        new TypeReference<List<EpisodeAirDate>>() {
                        });
                Long next = null;
                for (EpisodeAirDate entry : entries) {
                    entry.setAirTime(Instant.ofEpochMilli(entry.getAirTime()).atZone(zone).with(time)
                            .toInstant().toEpochMilli());
                    if (entry.getAirTime() > now && (next == null || entry.getAirTime() < next)) {
                        next = entry.getAirTime();
                    }
                }
                subscription.setSchedule(objectMapper.writeValueAsString(entries));
                if (next != null) {
                    subscription.setNextAirTime(next);
                } else if (subscription.getNextAirTime() != null) {
                    subscription.setNextAirTime(Instant.ofEpochMilli(subscription.getNextAirTime())
                            .atZone(zone).with(time).toInstant().toEpochMilli());
                }
            } catch (Exception e) {
                log.debug("apply custom air clock failed: {}", e.getMessage());
            }
        } else if (subscription.getNextAirTime() != null) {
            subscription.setNextAirTime(Instant.ofEpochMilli(subscription.getNextAirTime())
                    .atZone(zone).with(time).toInstant().toEpochMilli());
        }
    }

    /** 缺口 = 1..base 中本地没有的集;base = max(观测最大, 官方已播, 期望集数)。
     *  官方已播取 airedTarget 直播径(含 schedule 已到时刻的集):refresh 节流下 officialEpisodes
     *  滞后刚播的集,播后首查若按旧值算基准会判"不缺"、fillGaps 根本不搜新集。 */
    Set<Integer> computeMissing(MediaSubscription subscription, Set<Integer> present) {
        int base = present.stream().max(Integer::compareTo).orElse(0);
        // 官方已播/期望互选取大后被官方总集数夹住:已播数逻辑上不可能超过总集数,
        // 不夹则上游污染数据(瑞克 S9 官方总 10 完结/已播 11 系 S1 分集桥接污染)会让巡检
        // 每轮报缺不存在的集、fillGaps 空转攒 stallCount;观测最大集号不参与夹紧(官方滞后)
        int projected = Math.max(airedTarget(subscription, System.currentTimeMillis()),
                subscription.getExpectedEpisodes() == null ? 0 : subscription.getExpectedEpisodes());
        Integer total = subscription.getOfficialTotal();
        if (total != null && total > 0) {
            projected = Math.min(projected, total);
        }
        base = Math.max(base, projected);
        // base 上限保护:官方数据异常时不至于搜几千集(与网页清单 MAX_EPISODE_ROWS 同口径,
        // 旧值 500 把柯南这类 1200+ 集长番的缺集检测整轮废掉 —— 27 个真实缺口从未触发补缺)
        if (base <= 0 || base > MediaSubscriptionService.MAX_EPISODE_ROWS) {
            return Set.of();
        }
        Set<Integer> missing = new TreeSet<>();
        for (int i = 1; i <= base; i++) {
            if (!present.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    // ---------- 集源落库(episode_source 生命周期) ----------

    /**
     * 把一次真实目录列举同步进集源表:新文件 → LISTED 行,消失文件 → MISSING,
     * 换了路径的 FAILED 行 → 回 LISTED(新文件 = 新事实,值得再给一次机会)。
     * <p>
     * 这是"目录列举"与"可用性判定"的分界线:syncInventory 只写弱信号(LISTED),
     * VERIFIED/FAILED 只能由取链(preheat/抽样/播放)写入。
     */
    void syncInventory(MediaSubscription subscription, MediaSubscriptionResource resource, String mountPath,
                       TreeMap<Integer, EpisodeFile> files) {
        if (resource == null || files.isEmpty()) {
            return; // 主源资源行缺失:primaryResource 已尽力收养,仍缺则等 ensureSource 自愈
        }
        Map<Integer, MediaSubscriptionEpisodeSource> existing = new HashMap<>();
        Map<Integer, Integer> numberByEpisodeId = new HashMap<>();
        for (MediaSubscriptionEpisode episode : episodeRepository.findBySubscriptionIdOrderByNumber(subscription.getId())) {
            numberByEpisodeId.put(episode.getId(), episode.getNumber());
        }
        for (MediaSubscriptionEpisodeSource row : episodeSourceRepository.findByResourceId(resource.getId())) {
            Integer number = numberByEpisodeId.get(row.getEpisodeId());
            if (number != null) {
                existing.put(number, row);
            }
        }
        for (var entry : files.entrySet()) {
            int number = entry.getKey();
            EpisodeFile file = entry.getValue();
            String relPath = relativize(mountPath, file);
            MediaSubscriptionEpisodeSource row = existing.remove(number);
            if (row == null) {
                MediaSubscriptionEpisode episode = ensureEpisode(subscription, number);
                row = episodeSourceRepository.findByEpisodeIdAndResourceId(episode.getId(), resource.getId())
                        .orElseGet(() -> {
                            MediaSubscriptionEpisodeSource created = new MediaSubscriptionEpisodeSource();
                            created.setEpisodeId(episode.getId());
                            created.setResourceId(resource.getId());
                            return created;
                        });
            }
            String previousPath = row.getRelPath();
            // rel_path 列 VARCHAR(512):深嵌套目录+长 4K 文件名可超宽,不截断会 22001 炸整轮 syncInventory;
            // 截断的行取链必失败(路径不符)由采样判坏兜底,好过整轮巡检 ERROR
            row.setRelPath(StringUtils.abbreviate(relPath, 500));
            row.setFileSize(file.size());
            boolean failedLongAgo = row.getLastVerifiedTime() == null
                    || System.currentTimeMillis() - row.getLastVerifiedTime() > 7L * 24 * 3600_000;
            if (MediaSubscriptionEpisodeSource.STATE_MISSING.equals(row.getState())
                    // FAILED 行的翻案路径:换了文件(路径不同)= 新事实;或判决已过 7 天(对齐旧损坏登记的过期重试)
                    || (MediaSubscriptionEpisodeSource.STATE_FAILED.equals(row.getState())
                    && (!relPath.equals(previousPath) || failedLongAgo))) {
                row.setState(MediaSubscriptionEpisodeSource.STATE_LISTED);
            }
            episodeSourceRepository.save(row);
        }
        for (MediaSubscriptionEpisodeSource leftover : existing.values()) {
            if (LIVE_STATES.contains(leftover.getState())) {
                leftover.setState(MediaSubscriptionEpisodeSource.STATE_MISSING);
                episodeSourceRepository.save(leftover);
            }
        }
        resource.setEpisodesFound(files.size());
    }

    /** 分集实体按需建行;播出时间取自 subscription.schedule 快照(V30 不做迁移回填的运行时等价物)。 */
    private MediaSubscriptionEpisode ensureEpisode(MediaSubscription subscription, int number) {
        int season = subscription.getSeason() == null || subscription.getSeason() <= 0 ? 1 : subscription.getSeason();
        return episodeRepository.findBySubscriptionIdAndSeasonAndNumber(subscription.getId(), season, number)
                .orElseGet(() -> {
                    MediaSubscriptionEpisode episode = new MediaSubscriptionEpisode();
                    episode.setSubscriptionId(subscription.getId());
                    episode.setSeason(season);
                    episode.setNumber(number);
                    EpisodeAirDate air = scheduleOf(subscription).get(number);
                    if (air != null && air.getEpisode() == number) {
                        episode.setAirTime(air.getAirTime());
                        episode.setAired(air.getAirTime() <= System.currentTimeMillis());
                    }
                    return episodeRepository.save(episode);
                });
    }

    /** schedule 快照(JSON [{episode,airTime}])→ 集号→播出时间。 */
    private Map<Integer, EpisodeAirDate> scheduleOf(MediaSubscription subscription) {
        if (StringUtils.isBlank(subscription.getSchedule())) {
            return Map.of();
        }
        try {
            List<EpisodeAirDate> entries = objectMapper.readValue(subscription.getSchedule(),
                    new TypeReference<List<EpisodeAirDate>>() {
                    });
            Map<Integer, EpisodeAirDate> result = new HashMap<>();
            for (EpisodeAirDate entry : entries) {
                if (entry.getEpisode() > 0) {
                    result.putIfAbsent(entry.getEpisode(), entry);
                }
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 挂载点内相对路径(不含挂载前缀):换挂载点不失效,播放/转存时 mountPath + rel_path 还原。 */
    private static String relativize(String mountPath, EpisodeFile file) {
        String dir = file.dir();
        if (dir.length() > mountPath.length() && dir.startsWith(mountPath + "/")) {
            return dir.substring(mountPath.length() + 1) + "/" + file.name();
        }
        return file.name();
    }

    /** 本地已有集 = 全部挂载资源集源行 LISTED/VERIFIED 的并集(可用性派生口径)。 */
    Set<Integer> liveEpisodeNumbers(MediaSubscription subscription) {
        return new TreeSet<>(episodeSourceRepository
                .findNumbersBySubscriptionAndStatesIn(subscription.getId(), LIVE_STATES));
    }

    /** 某资源当前提供的集号(LIVE 行);探测覆盖快照的替代品。 */
    Set<Integer> coverageOf(MediaSubscriptionResource resource) {
        return new TreeSet<>(episodeSourceRepository
                .findNumbersByResourceIdAndStatesIn(resource.getId(), LIVE_STATES));
    }

    /** 候选池统一视图(探测/补缺/主盘/线路/换源全走这里):非挂载候选按分数降序;
     * 附年份门禁 —— 标题明确标注其它年份的资源在这里就出局,同名/前缀异剧
     * (「悬案」2026 vs「悬案解码 Dept. Q (2025)」)不再被逐个试挂;
     * 附标题宣称集数/版本词门禁 —— 宣称集数显著超出官方总集数(真人版「全37集」包)、
     * 动画订阅的显式「真人版」资源在此出局(探测前拦截,省一轮挂载试错);
     * 附盘白名单 —— 配置了主/扩展网盘后,白名单以外盘的存量候选不再被探测/换源/补线;
     * 附全局资源筛选(msub_pool_filter)—— 排除词/包含词/清晰度门槛对存量候选同样生效,
     * 配置收紧后池内已有资源不再被选为主源(已挂载主源不经此路径,靠自然失效换源淘汰)。 */
    List<MediaSubscriptionResource> candidatesOrdered(MediaSubscription subscription) {
        long now = System.currentTimeMillis();
        Integer metaYear = metaYear(subscription);
        List<String> names = matchNames(subscription);
        List<String> genres = metaGenres(subscription);
        Set<String> allowedDrives = allowedCandidateDrives(subscription);
        MediaSubscriptionPoolFilter global = poolFilterFor(subscription);
        return resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId()).stream()
                .filter(r -> !MediaSubscriptionResource.STATE_MOUNTED.equals(r.getState()))
                .filter(r -> MediaSubscriptionResource.STATE_CANDIDATE.equals(r.getState()) || isBadCooled(r, now))
                .filter(r -> titleYearMatches(metaYear, names, r.getTitle()))
                .filter(r -> !titleProgressForeign(subscription, r.getTitle(), genres) && !liveActionForeign(genres, r.getTitle()))
                .filter(r -> driveAllowed(allowedDrives, r.getType() == null ? null : DriveId.toDrive(r.getType())))
                .filter(r -> !matchesKeywords(r.getTitle(), global.getExcludeKeywords()))
                .filter(r -> globallyIncluded(global, r.getTitle()) && qualityAboveFloor(global, r.getTitle()))
                .toList();
    }

    /** 订阅元数据年份(门禁基准):provider 侧有缓存,取不到/未绑元数据返回 null(门禁关闭)。 */
    Integer metaYear(MediaSubscription subscription) {
        MetadataDetails details = metaDetails(subscription);
        if (details == null) {
            return null;
        }
        Matcher matcher = YEAR_MARK.matcher(StringUtils.defaultString(details.getYear()));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    /** 订阅元数据类型(版本词门禁基准):genres 缺失返回 null(门禁关闭)。 */
    List<String> metaGenres(MediaSubscription subscription) {
        MetadataDetails details = metaDetails(subscription);
        return details == null || details.getGenres() == null ? List.of() : details.getGenres();
    }

    /** 订阅元数据单集时长(分钟,时长门禁基准):取不到返回 null(门禁关闭)。 */
    Integer metaRuntimeMinutes(MediaSubscription subscription) {
        MetadataDetails details = metaDetails(subscription);
        return details == null ? null : details.getRuntimeMinutes();
    }

    /** 元数据详情(provider 缓存 + media_metadata 持久层,完结剧零网络);未绑/异常返回 null,门禁全部关闭。 */
    private MetadataDetails metaDetails(MediaSubscription subscription) {
        if (StringUtils.isBlank(subscription.getMetaProvider()) || StringUtils.isBlank(subscription.getMetaId())) {
            return null;
        }
        try {
            return metadataService.details(subscription.getMetaProvider(), subscription.getMetaId(),
                    subscription.getSeason());
        } catch (Exception e) {
            log.debug("meta details for subscription {} unavailable: {}", subscription.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 年份门禁:元数据年份缺失,或标题不标注年份 → 放行(零误伤);标题标注了年份但无一相符 →
     * 再看剧名命中方式 —— <b>整词命中</b>(归一化标题的独立词 == 剧名/别名)放行:动漫全系列包常标
     * 第一季年代(「鬼灭之刃 (2019)」装全部季),同名作年代歧义交给季过滤/探测定夺;
     * 仅<b>子串嵌在更长词里</b>(「悬案」⊂「悬案解码」)才是前缀异剧,拒。
     */
    static boolean titleYearMatches(Integer expected, List<String> names, String title) {
        if (expected == null || StringUtils.isBlank(title)) {
            return true;
        }
        Matcher matcher = YEAR_MARK.matcher(title);
        boolean anyYear = false;
        while (matcher.find()) {
            anyYear = true;
            if (Integer.parseInt(matcher.group(1)) == expected) {
                return true;
            }
        }
        if (!anyYear) {
            return true;
        }
        if (names != null) {
            Set<String> tokens = new TreeSet<>(List.of(normalizeForMatch(title).split(" ")));
            for (String name : names) {
                String n = normalizeForMatch(name);
                if (n.length() >= 2 && tokens.contains(n)) {
                    return true; // 同名作整词命中:年份标的是首季/系列年代,放行
                }
            }
        }
        return false;
    }

    /**
     * 集号范围门禁:资源提供的集号显著超出本季官方总集数 = 同名异剧。经典 IP 真人版/动画版
     * 同名同季(线上:真人版「仙剑奇侠传三 2160P」37 集顶在动画版 26 集订阅上,maxEpisode=37
     * 还误判 ENDED),标题无年份无类型词,标题/年份门禁对这种形态全部放行 —— 探测出的集号
     * 范围是挂上后唯一可靠的区分信号。本季已播完(登记集数全播完)后再冒的集号不可能是本剧,
     * 超出即拒;未播完按登记体量放大容差(每满 10 集容忍 1 集,下限 2)—— TMDB 登记滞后量级
     * 与剧集体量相关,千集级长寿动漫可落后数十集(线上:柯南登记总 1212,网盘实际更至 1270)。
     * 官方总集数未知/无集号 → 放行。
     */
    static boolean episodeNumbersForeign(MediaSubscription subscription, Collection<Integer> numbers) {
        Integer total = subscription.getOfficialTotal();
        if (total == null || total <= 0 || numbers == null || numbers.isEmpty()) {
            return false;
        }
        int max = numbers.stream().max(Integer::compareTo).orElse(0);
        int overflow = max - total;
        return overflow > 0 && (subscription.isSeasonAiredOut() || overflow > registrationLagTolerance(total));
    }

    /** 未播完时的集号溢出容差:短剧 1-2 集(排播登记滞后),长寿剧随体量放大。
     * 小体量区间(真人版 37 vs 动画版 26)容差仍是 2,原判别力不变。 */
    static int registrationLagTolerance(int officialTotal) {
        return Math.max(2, officialTotal / 10);
    }

    /** 非剧本内容(综艺/纪录/新闻/脱口秀):元数据对这类内容的季总集数登记天然不可靠
     * (随录随播、加更/删减常态),集号/宣称集数超出登记数不再是异剧信号 —— 集数类门禁豁免。
     * 与「真人版」门禁同款口径:只认 genres 正向证据,不做标题词兜底(「新闻女王」是剧本剧)。 */
    private static final Pattern NON_SCRIPTED_GENRE = Pattern.compile(
            "综艺|真人秀|脱口秀|访谈|纪录|纪实|新闻|Documentary|Reality|Talk|News", Pattern.CASE_INSENSITIVE);

    /** genres 任一命中非剧本类型 → 豁免;genres 缺失(豆瓣纯源)不豁免,门禁维持(零误伤方向)。 */
    static boolean nonScriptedContent(List<String> genres) {
        if (genres == null || genres.isEmpty()) {
            return false;
        }
        return genres.stream().anyMatch(g -> NON_SCRIPTED_GENRE.matcher(StringUtils.defaultString(g)).find());
    }

    /** 同 {@link #episodeNumbersForeign(MediaSubscription, Collection)},非剧本内容豁免:
     * 登记总集数不可靠,集号超界交给季过滤/时长门禁分辨。 */
    static boolean episodeNumbersForeign(MediaSubscription subscription, Collection<Integer> numbers, List<String> genres) {
        return !nonScriptedContent(genres) && episodeNumbersForeign(subscription, numbers);
    }

    /**
     * 文件级解析噪声剔除:目录里混入的<b>不相干文件</b>(线上:仙剑动画资源目录里被分享者塞进
     * 《都市仙医》S01E142,集号 142 撑爆详情分集列表,还会让资源级门禁把这个主体正确的资源
     * 整体误杀)。判据:超出官方总集数且<b>不在衔接链上</b>的集号 = 噪声(26→142 跳变,不可能
     * 是本剧延续);从 total+1 连续衔接的超范围尾部(真人版 1-37 / TMDB 登记滞后的真实新集)
     * 保留,交给 {@link #episodeNumbersForeign} 资源级门禁判定。官方总集数未知 → 不剔(零误伤)。
     */
    static void stripForeignEpisodeNoise(MediaSubscription subscription, TreeMap<Integer, EpisodeFile> files) {
        Integer total = subscription.getOfficialTotal();
        if (total == null || total <= 0 || files.isEmpty()) {
            return;
        }
        int next = total + 1; // 衔接链游标:官方范围外第一个可能的真集号
        Iterator<Integer> beyond = files.tailMap(total + 1).keySet().iterator();
        while (beyond.hasNext()) {
            if (beyond.next() == next) {
                next++; // 衔接链上的超范围尾部:保留,由资源级门禁判真伪
            } else {
                beyond.remove(); // 断裂跳号:不相干文件混入目录
            }
        }
    }

    /** 同 {@link #stripForeignEpisodeNoise(MediaSubscription, TreeMap)},非剧本内容豁免:
     * 综艺整季缺号/加更是常态,超登记范围的断裂跳号也可能是真集,不剔。 */
    static void stripForeignEpisodeNoise(MediaSubscription subscription, TreeMap<Integer, EpisodeFile> files,
                                         List<String> genres) {
        if (!nonScriptedContent(genres)) {
            stripForeignEpisodeNoise(subscription, files);
        }
    }

    /** 巡检口径的集文件清洗:先做年番全剧连续编号重映射(救回超界真集),再剔不相干噪声。
     * 重映射必须在噪声剔除之前 —— 连续编号的正片集号天然超出官方总集数,先剔会把好集全删光。 */
    void sanitizeEpisodeFiles(MediaSubscription subscription, TreeMap<Integer, EpisodeFile> files, String contextTitle) {
        remapAbsoluteNumbering(subscription, files, contextTitle);
        stripForeignEpisodeNoise(subscription, files, metaGenres(subscription));
    }

    /**
     * 标题宣称集数门禁:「全37集」等宣称(TITLE_PROGRESS 各形态最大值)显著超出官方总集数
     * (与探测集号同判据:已播完超出即拒/未播完按登记体量放大容差)—— 在入池/候选层就拦,不必等挂载探测。
     * 标题带季标记/合集词时跳过:多季合一包宣称的是<b>跨季总数</b>(「鬼灭之刃 全52集 合集」装
     * 全部季),与同名异剧在标题层无法区分,交给探测层季过滤/集号门禁。
     * <p>
     * 多季订阅(season&gt;1)同样跳过:年番文化下标题宣称的常是<b>全剧连续进度</b>(线上:
     * 「沧元图3 (2026)【更至81集】」= 全剧 81 而本季官方总 50),不是异剧信号 —— 放行给
     * 探测层,由季目录重映射后的集号门禁分辨。
     */
    static boolean titleProgressForeign(MediaSubscription subscription, String title) {
        Integer total = subscription.getOfficialTotal();
        if (total == null || total <= 0 || StringUtils.isBlank(title)) {
            return false;
        }
        if (SEASON_RANGE.matcher(title).find() || title.contains("合集") || parseTitleSeason(title) != null) {
            return false;
        }
        if (subscription.getSeason() != null && subscription.getSeason() > 1) {
            return false;
        }
        Integer claimed = parseTitleProgress(title);
        if (claimed == null || claimed <= total) {
            return false;
        }
        return subscription.isSeasonAiredOut() || claimed - total > registrationLagTolerance(total);
    }

    /** 同 {@link #titleProgressForeign(MediaSubscription, String)},非剧本内容豁免:
     * 「全N集」宣称对综艺无意义(登记滞后/加更是常态),不据宣称拒。 */
    static boolean titleProgressForeign(MediaSubscription subscription, String title, List<String> genres) {
        return !nonScriptedContent(genres) && titleProgressForeign(subscription, title);
    }

    /**
     * 单集时长门禁:资源文件时长(AList duration,夸克等驱动返回)与元数据单集时长
     * (runtimeMinutes,TMDB episode_run_time)显著不符 = 同名异剧。时长是内容属性不受码率影响
     * (线上:真人版单集 45min vs 动画版 20min;体积受码率干扰不可靠),差异 &gt;50% 判异剧 ——
     * 补集号门禁的残余盲区(未播完 + 容差内的真人版 1-28:集号合法但时长必不符)。
     * 双侧信号齐备才判:元数据无时长、或盘驱动不给 duration(覆盖不足半数/少于 3 个文件)→ 跳过零误伤。
     */
    static boolean episodeDurationForeign(Integer metaRuntimeMinutes, Collection<EpisodeFile> files) {
        if (metaRuntimeMinutes == null || metaRuntimeMinutes <= 0 || files == null || files.isEmpty()) {
            return false;
        }
        List<Long> durations = files.stream().map(EpisodeFile::duration)
                .filter(d -> d > 60).sorted().toList();
        if (durations.size() < 3 || durations.size() * 2 < files.size()) {
            return false; // duration 覆盖不足半数:该盘驱动不报时长,不判
        }
        double actualMinutes = durations.get(durations.size() / 2) / 60.0; // 中位数抗单集异常
        return Math.abs(actualMinutes - metaRuntimeMinutes) > metaRuntimeMinutes * 0.5;
    }

    /** 标题显式「真人版」标记(版本词门禁用;词形收窄防误伤)。 */
    private static final Pattern LIVE_ACTION_MARK = Pattern.compile("真人版|真人连续剧");

    /**
     * 版本词门禁(<b>单向</b>):动画订阅(genres 含动画,正向证据)拒标题显式标「真人版」的资源 ——
     * 同名 IP 双形态且集数/时长信号都缺时的最后防线(真人版集数 ≤ 动画版官方数、盘又不返回
     * duration 时)。反向(真人剧订阅拒「动画版」资源)不做:genres 缺失(豆瓣订阅)时会把
     * 正确的动画资源误伤。标题标「动画版/动漫版」且订阅是动画 → 天然放行,无需处理。
     */
    static boolean liveActionForeign(List<String> genres, String title) {
        if (genres == null || genres.isEmpty() || StringUtils.isBlank(title)) {
            return false;
        }
        boolean animation = genres.stream().anyMatch(g -> g != null && (g.contains("动画") || g.contains("动漫")));
        return animation && LIVE_ACTION_MARK.matcher(title).find();
    }

    /** 异剧拒绝消息识别(activate/probeShare 集号门禁抛出):调用方退役候选但不进失效黑名单。 */
    static boolean isForeignShowRejection(String message) {
        return message != null && message.contains(FOREIGN_SHOW_MARK);
    }

    /** 集号门禁拒绝消息(含 {@link #FOREIGN_SHOW_MARK} 标记,供调用方识别分流)。 */
    static String foreignShowReason(MediaSubscription subscription, int maxEpisode, String title) {
        return "集号超出官方范围(第" + maxEpisode + "集 > 官方" + subscription.getOfficialTotal()
                + "集)," + FOREIGN_SHOW_MARK + ":" + StringUtils.defaultString(title);
    }

    /** 已挂资源是否仍属于本剧:集号范围 + 标题归属 + 年份门禁(与候选入池同规)。
     * 误挂的异剧源列目录、流探测都正常,巡检没有天然失效信号,靠这套复核发现并纠正。 */
    boolean belongsToShow(MediaSubscription subscription, MediaSubscriptionResource resource) {
        // id 为 null(未落库的临时资源)时集号门禁无从查行,空集放行(与原短路语义一致)
        return belongsToShow(subscription, resource,
                resource.getId() == null ? java.util.Set.of() : coverageOf(resource));
    }

    /**
     * 同上,但集号门禁用<b>调用方给定的观测集</b>(而非 DB 旧行):噪声剔除上线前落库的毒行
     * (26+142)会把主体正确的主源误判异剧 —— doCheck 复核主源时应传本轮清洗后的文件集,
     * 行层面的毒数据由随后的 syncInventory 重列洗掉。
     */
    /** 主源复核是否须换源:空壳(列不出本季文件)必换 —— 挂不上内容的钉选没有意义,换季重置
     * 语义也依赖它;误挂异剧(归属门禁不符)换源但<b>钉选豁免</b>:钉选是用户对自动判定的否决,
     * 归属门禁的误杀止步于此(失效换源不受影响,链接真死照常换走,钉选保留待回归)。 */
    static boolean shouldReplacePrimary(MediaSubscriptionResource primary, boolean belongs, boolean hollow) {
        if (hollow) {
            return true;
        }
        return !belongs && !Boolean.TRUE.equals(primary.getPinned());
    }

    boolean belongsToShow(MediaSubscription subscription, MediaSubscriptionResource resource, Set<Integer> observedEpisodes) {
        if (resource.getId() != null && episodeNumbersForeign(subscription, observedEpisodes, metaGenres(subscription))) {
            return false; // 同名真人版等异剧:标题/年份门禁放行,集号超出官方总集数是唯一信号
        }
        String title = StringUtils.defaultString(resource.getTitle());
        if (title.isBlank()) {
            return true; // 无标题旧数据无从判定,保守放行
        }
        List<String> names = matchNames(subscription);
        if (!names.isEmpty() && !matchesTitle(names, title)) {
            return false;
        }
        Integer titleSeason = TextUtils.parseTitleSeason(title);
        if (subscription.getSeason() != null && subscription.getSeason() > 0
                && titleSeason != null && !titleSeason.equals(subscription.getSeason())) {
            return false; // 标题明确标注其它季:同剧不同季,对本订阅就是"异剧"(换季后旧季资源继续挂载/顶主源)
        }
        return titleYearMatches(metaYear(subscription), names, title);
    }

    /** 主源资源:挂在订阅固定路径上的 MOUNTED 资源;行丢失但 share 还在时按 shareId 收养自愈。 */
    MediaSubscriptionResource primaryResource(MediaSubscription subscription) {
        if (StringUtils.isBlank(subscription.getMountPath())) {
            return null;
        }
        List<MediaSubscriptionResource> resources = resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId());
        MediaSubscriptionResource primary = resources.stream()
                .filter(r -> MediaSubscriptionResource.STATE_MOUNTED.equals(r.getState())
                        && subscription.getMountPath().equals(r.getMountPath()))
                .findFirst().orElse(null);
        if (primary == null && subscription.getShareId() != null) {
            primary = resources.stream()
                    .filter(r -> subscription.getShareId().equals(r.getShareId()))
                    .findFirst().orElse(null);
            if (primary != null) {
                primary.setState(MediaSubscriptionResource.STATE_MOUNTED);
                primary.setMountPath(subscription.getMountPath());
                resourceRepository.save(primary);
                log.info("subscription {} adopted primary resource {} by shareId", subscription.getId(), primary.getId());
            }
        }
        return primary;
    }

    /** 补缺挂载列表:挂在 /追剧/.sources/ 下的 MOUNTED 资源。 */
    List<MediaSubscriptionResource> auxMounts(MediaSubscription subscription) {
        return resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId()).stream()
                .filter(r -> MediaSubscriptionResource.STATE_MOUNTED.equals(r.getState())
                        && StringUtils.isNotBlank(r.getMountPath())
                        && !subscription.getMountPath().equals(r.getMountPath()))
                .toList();
    }

    /**
     * 补缺挂载原位刷新(挂载原地增长):集源行重列同步;列不出任何文件 = 分享已死 → 就地退役。
     * 旧实现把覆盖快照留在资源行上、刷新失败也不清空,死源永久"冒领"集数(缺陷 4);
     * 现在行就是事实,刷不出来事实就归零,退役后缺口自动重新打开。
     */
    void refreshAuxMounts(MediaSubscription subscription) {
        for (MediaSubscriptionResource resource : auxMounts(subscription)) {
            migrateLegacyGapMount(subscription, resource);
            if (!MediaSubscriptionResource.STATE_MOUNTED.equals(resource.getState())
                    || StringUtils.isBlank(resource.getMountPath())) {
                continue; // 迁移失败已被重置为候选
            }
            // 先列目录取清洗后的文件集,再做归属复核:存量毒行(噪声剔除上线前的 142)会把
            // 主体正确的补缺挂载误判异剧白白卸载,行层面的毒数据随后面的 syncInventory 洗掉
            TreeMap<Integer, EpisodeFile> files = new TreeMap<>();
            try {
                collectEpisodeFiles(site(), subscription.getSeason(), resource.getMountPath(), 1, files,
                        episodeSizePolicy(subscription), true, metaYear(subscription));
            } catch (Exception e) {
                log.info("aux mount refresh failed, retire: {} {}", resource.getMountPath(), e.getMessage());
                retireResource(subscription, resource, e.getMessage(), false);
                continue;
            }
            sanitizeEpisodeFiles(subscription, files, resource.getTitle());
            if (files.isEmpty()) {
                retireResource(subscription, resource, "挂载目录已无任何剧集文件", false);
                continue;
            }
            if (!belongsToShow(subscription, resource, files.keySet())) {
                // 误挂异剧的补缺/线路挂载:其行会向"本地已有集"冒领错误集号,就地卸载回候选池
                // (不走 retireResource:链接没死,不进跨订阅黑名单)
                if (!unmountShareIfUnused(resource.getShareId(), subscription.getId())) {
                    // 卸载失败(AList 不可用):保留挂载状态待下轮重试,勿清 shareId
                    continue;
                }
                resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
                resource.setMountPath(null);
                resource.setShareId(null);
                resourceRepository.save(resource);
                for (MediaSubscriptionEpisodeSource row : episodeSourceRepository.findByResourceId(resource.getId())) {
                    if (LIVE_STATES.contains(row.getState())) {
                        row.setState(MediaSubscriptionEpisodeSource.STATE_MISSING);
                        episodeSourceRepository.save(row);
                    }
                }
                addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_SOURCE_INVALID,
                        "补缺源与剧集不符(误挂异剧)已卸载:" + resource.getTitle(), false);
                continue;
            }
            syncInventory(subscription, resource, resource.getMountPath(), files);
            resource.setCheckedTime(System.currentTimeMillis());
            resourceRepository.save(resource);
        }
    }

    /**
     * 退役资源:卸载 share(删不掉保留下轮重试)、状态 RETIRED、全部集源行判 FAILED、写失效黑名单。
     * <p>
     * 行统一落 FAILED(而非删除)是派生规则的锚点:liveEpisodeNumbers 不看资源状态,
     * "退役 ⇒ 无 LIVE 行"必须由这里保证,否则死源继续冒领集数。
     *
     * @param quiet true = 只记日志不发事件(探测失败/激活尝试失败这类高频路径,避免事件流刷屏)
     */
    void retireResource(MediaSubscription subscription, MediaSubscriptionResource resource, String reason, boolean quiet) {
        if (resource.getShareId() != null) {
            // 共享挂载:share 被其它订阅引用时不卸载(内容对别人仍有效),本订阅的资源行照常退役
            boolean referencedByOthers = subscriptionRepository.existsByShareIdAndIdNot(resource.getShareId(), subscription.getId())
                    || resourceRepository.existsByShareIdAndSubscriptionIdNot(resource.getShareId(), subscription.getId());
            if (!referencedByOthers) {
                try {
                    shareService.deleteShare(resource.getShareId());
                } catch (Exception e) {
                    log.warn("retire resource {} failed, keep for next round: {}", resource.getId(), e.getMessage());
                    return;
                }
            }
        }
        boolean primary = subscription.getMountPath() != null && subscription.getMountPath().equals(resource.getMountPath());
        resource.setState(MediaSubscriptionResource.STATE_RETIRED);
        resource.setShareId(null);
        resource.setMountPath(null);
        resource.setCheckedTime(System.currentTimeMillis());
        resourceRepository.save(resource);
        for (MediaSubscriptionEpisodeSource row : episodeSourceRepository.findByResourceId(resource.getId())) {
            if (LIVE_STATES.contains(row.getState())) {
                row.setState(MediaSubscriptionEpisodeSource.STATE_FAILED);
                episodeSourceRepository.save(row);
            }
        }
        markDeadLink(resource.getLink(), reason);
        if (!quiet) {
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_SOURCE_INVALID,
                    (primary ? "主源" : "补缺源") + "失效已退役:" + StringUtils.defaultIfBlank(resource.getTitle(), "候选源")
                            + "(" + StringUtils.defaultString(reason) + ")");
        }
        log.info("subscription {} retired resource {} ({}): {}", subscription.getId(), resource.getId(),
                primary ? "primary" : "aux", StringUtils.defaultString(reason));
    }

    /**
     * 异剧候选退役:卸载挂载、RETIRED 冷却重探、行落 FAILED。
     * 与 {@link #retireResource} 的差别:<b>不进跨订阅失效黑名单</b> —— 链接没死,只是不属于本剧
     * (真人版订阅可能正用着它);官方集数修正后冷却期满会重探自愈。
     */
    void retireAlienCandidate(MediaSubscription subscription, MediaSubscriptionResource resource) {
        if (resource.getShareId() != null) {
            // 共享挂载:share 被其它订阅引用时不卸载
            boolean referencedByOthers = subscriptionRepository.existsByShareIdAndIdNot(resource.getShareId(), subscription.getId())
                    || resourceRepository.existsByShareIdAndSubscriptionIdNot(resource.getShareId(), subscription.getId());
            if (!referencedByOthers) {
                try {
                    shareService.deleteShare(resource.getShareId());
                } catch (Exception e) {
                    log.warn("retire alien resource {} failed, keep for next round: {}", resource.getId(), e.getMessage());
                    return;
                }
            }
        }
        // 异剧不累计瞬时 streak:冷却期满重探若官方集数已修正应能通过门禁自愈,
        // streak 走到上限会被当"怪措辞死源"退役+拉黑,击穿异剧不进黑名单的不变量
        if (resource.getId() != null) {
            transientStreak.remove(resource.getId());
        }
        resource.setState(MediaSubscriptionResource.STATE_RETIRED);
        resource.setShareId(null);
        resource.setMountPath(null);
        resource.setCheckedTime(System.currentTimeMillis());
        resourceRepository.save(resource);
        for (MediaSubscriptionEpisodeSource row : episodeSourceRepository.findByResourceId(resource.getId())) {
            if (LIVE_STATES.contains(row.getState())) {
                row.setState(MediaSubscriptionEpisodeSource.STATE_FAILED);
                episodeSourceRepository.save(row);
            }
        }
    }

    /** 失效黑名单登记:跨订阅共享 —— 一个分享死了,所有订阅入池时都看得见。 */
    void markDeadLink(String link, String reason) {
        if (StringUtils.isBlank(link)) {
            return;
        }
        try {
            DeadLink dead = deadLinkRepository.findByLink(link).orElseGet(() -> {
                DeadLink created = new DeadLink();
                created.setLink(StringUtils.abbreviate(link, 1000)); // 列 VARCHAR(1024),外部链接无界
                return created;
            });
            dead.setReason(StringUtils.abbreviate(StringUtils.defaultString(reason), 250));
            dead.setFailCount(dead.getFailCount() + 1);
            dead.setTime(System.currentTimeMillis());
            deadLinkRepository.save(dead);
        } catch (Exception e) {
            log.debug("mark dead link failed: {}", e.getMessage());
        }
    }

    /**
     * 新集播放预热验证(atv-player V82/V85 思想):对新增集做字节级流探测({@link #verifyStream}),
     * 真出流 → VERIFIED;假页/死链 → FAILED + 失败传染判定(区分单集损坏与整源失效)。
     *
     * @return 本轮判定损坏的集(不可通知用户;整源已退役时集源行已无 LIVE,自然出局)
     */
    private Set<Integer> preheatEpisodes(MediaSubscription subscription, Set<Integer> added) {
        var config = appProperties.getSubscription();
        Set<Integer> brokenNew = new TreeSet<>();
        if (!config.isPreheatEnabled() || added == null || added.isEmpty()) {
            return brokenNew;
        }
        int probed = 0;
        Set<Integer> damaged = new TreeSet<>();
        for (Integer episode : added) {
            if (probed >= config.getPreheatMaxPerRound()) {
                break;
            }
            List<PlayCandidate> candidates = playCandidates(subscription, episode);
            if (candidates.isEmpty()) {
                continue;
            }
            probed++;
            PlayCandidate candidate = candidates.getFirst();
            StreamVerdict verdict = verifyStream(candidate.resource().getMountPath(), candidate.source());
            if (verdict == StreamVerdict.VERIFIED) {
                markVerified(candidate.source());
            } else if (verdict == StreamVerdict.FAILED) {
                log.info("subscription {} episode {} preheat failed: {}", subscription.getId(), episode, "stream dead");
                markFailed(candidate.source());
                boolean shareDead = contagion(subscription, candidate.resource(), candidate.source().getId());
                if (!shareDead) {
                    damaged.add(episode);
                }
            }
            // TRANSIENT(限流/网络抖动)与 INCONCLUSIVE(403 防盗链等):不下结论,下轮再来
        }
        if (!damaged.isEmpty()) {
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR,
                    "第" + joinNumbers(new ArrayList<>(damaged)) + " 集链接验证失败(疑似被和谐),已登记自动补源");
        }
        brokenNew.addAll(damaged);
        return brokenNew;
    }

    /**
     * 播放后前瞻验证核心(与 {@link #preheatEpisodes} 同构,探测对象从"新上架集"换成"用户即将看的集"):
     * 播放第 N 集成功后,把已上架的 N+1..N+K 各集最优候选行字节级探测一遍 ——
     * 真出流 → VERIFIED(刷新新鲜度,连播时首选行更可靠);假页/死链 → FAILED + 失败传染判定
     * (整源死退役换源,单集损坏走补源)。未上架集不在 LIVE 集号集合里,自然跳过。
     */
    void preheatAhead(MediaSubscription subscription, int playedEpisode) {
        int limit = Math.max(0, appProperties.getSubscription().getPreheatAheadEpisodes());
        if (limit == 0) {
            return;
        }
        List<Integer> upcoming = episodeSourceRepository
                .findNumbersBySubscriptionAndStatesIn(subscription.getId(), LIVE_STATES).stream()
                .filter(number -> number > playedEpisode)
                .sorted()
                .limit(limit)
                .toList();
        for (Integer episode : upcoming) {
            List<PlayCandidate> candidates = playCandidates(subscription, episode);
            if (candidates.isEmpty()) {
                continue;
            }
            PlayCandidate candidate = candidates.getFirst();
            StreamVerdict verdict = verifyStream(candidate.resource().getMountPath(), candidate.source());
            if (verdict == StreamVerdict.VERIFIED) {
                markVerified(candidate.source());
            } else if (verdict == StreamVerdict.FAILED) {
                log.info("subscription {} episode {} ahead probe failed: {}", subscription.getId(), episode, "stream dead");
                markFailed(candidate.source());
                contagion(subscription, candidate.resource(), candidate.source().getId());
            }
            // TRANSIENT(限流/网络抖动)与 INCONCLUSIVE(403 防盗链等):不下结论,下个窗口再来
        }
        rescueAheadDead(subscription, upcoming);
    }

    /**
     * 前瞻探测后存在已无任何可播候选的集(含被传染退役牵连的)→ 提交完整巡检补源(换源优先,池空才搜索)。
     * 带 2h 冷却:探测每个限频窗口都跑,死集补源一次即入巡检的既有节奏,不重复烧搜索配额。
     */
    private void rescueAheadDead(MediaSubscription subscription, List<Integer> upcoming) {
        List<Integer> dead = upcoming.stream()
                .filter(episode -> playCandidates(subscription, episode).isEmpty())
                .toList();
        if (dead.isEmpty()) {
            return;
        }
        int id = subscription.getId();
        long now = System.currentTimeMillis();
        Long last = aheadRescueTime.get(id);
        if (last != null && now - last < AHEAD_RESCUE_COOLDOWN_MS) {
            return;
        }
        aheadRescueTime.put(id, now);
        addEvent(id, MediaSubscriptionEvent.TYPE_ERROR,
                "第" + joinNumbers(dead) + " 集链接验证失败(疑似被和谐),已自动补源");
        submitCheck(id);
    }

    /**
     * 失败传染判定(Q11):某集取链失败后,对<b>同一资源的另一集</b>再取一次链 ——
     * 也失败 = 整源死(退役 + 黑名单,触发换源);成功 = 仅该集损坏(行 FAILED,其它集不受牵连)。
     * <p>
     * 没有其他可试的集 = 该资源实际已无可播内容,同样按整源死处理。
     *
     * @return true = 整源已退役
     */
    boolean contagion(MediaSubscription subscription, MediaSubscriptionResource resource, int failedRowId) {
        if (resource == null || !MediaSubscriptionResource.STATE_MOUNTED.equals(resource.getState())
                || StringUtils.isBlank(resource.getMountPath())) {
            return false; // 已在前一步退役
        }
        List<MediaSubscriptionEpisodeSource> live = episodeSourceRepository.findByResourceId(resource.getId()).stream()
                .filter(s -> LIVE_STATES.contains(s.getState()) && !s.getId().equals(failedRowId))
                .sorted(SOURCE_ORDER)
                .toList();
        if (live.isEmpty()) {
            retireResource(subscription, resource, "全部集取链失败", false);
            return true;
        }
        MediaSubscriptionEpisodeSource probe = live.getFirst();
        StreamVerdict verdict = verifyStream(resource.getMountPath(), probe);
        if (verdict == StreamVerdict.VERIFIED) {
            markVerified(probe);
            return false;
        }
        if (verdict == StreamVerdict.FAILED) {
            markFailed(probe);
            retireResource(subscription, resource, "二次探测仍失败,判定整源失效", false);
            return true;
        }
        // 瞬时(限流/网络)与无结论(403 防盗链等)不下结论:整源悬置,已落的失败行维持 FAILED
        log.info("contagion probe inconclusive for resource {}, no verdict", resource.getId());
        return false;
    }

    /** 每轮对每个挂载源抽 1 集 LISTED 行主动取链(Q11):覆盖"从未被播的集永远停在 LISTED"的盲区 ——
     * 缺陷 4 里那 9 集正是死于此。1 集/源·轮,开销上界 = 挂载数(≤ maxGapMounts+1)。 */
    void sampleMounted(MediaSubscription subscription) {
        for (MediaSubscriptionResource resource : mountedResources(subscription)) {
            if (StringUtils.isBlank(resource.getMountPath())) {
                continue;
            }
            MediaSubscriptionEpisodeSource sample = episodeSourceRepository.findByResourceId(resource.getId()).stream()
                    .filter(s -> MediaSubscriptionEpisodeSource.STATE_LISTED.equals(s.getState()))
                    .min(Comparator.comparing(s -> s.getLastVerifiedTime() == null ? 0L : s.getLastVerifiedTime()))
                    .orElse(null);
            if (sample == null) {
                continue; // 全 VERIFIED(健康)或全 FAILED(已由传染判定处理)
            }
            StreamVerdict verdict = verifyStream(resource.getMountPath(), sample);
            if (verdict == StreamVerdict.VERIFIED) {
                markVerified(sample);
            } else if (verdict == StreamVerdict.FAILED) {
                log.info("subscription {} sample probe failed on resource {}: {}", subscription.getId(), resource.getId(), "stream dead");
                markFailed(sample);
                contagion(subscription, resource, sample.getId());
            }
            // TRANSIENT/INCONCLUSIVE:不下结论,下轮抽到的仍可能是它(最久未验者优先)
        }
    }

    private void markVerified(MediaSubscriptionEpisodeSource row) {
        row.setState(MediaSubscriptionEpisodeSource.STATE_VERIFIED);
        row.setSuccessCount(row.getSuccessCount() + 1);
        row.setLastVerifiedTime(System.currentTimeMillis());
        episodeSourceRepository.save(row);
    }

    private void markFailed(MediaSubscriptionEpisodeSource row) {
        row.setState(MediaSubscriptionEpisodeSource.STATE_FAILED);
        row.setFailCount(row.getFailCount() + 1);
        row.setLastVerifiedTime(System.currentTimeMillis()); // 判决时间:7 天后同路径文件仍在则回 LISTED 重探
        episodeSourceRepository.save(row);
    }

    // ---------- 字节级流验证与故障分级(v3) ----------

    /** 探测失败分类:限流(盘退避,不下结论)/瞬时(不下结论,streak 封顶)/失效(判死) */
    enum ProbeFailure { THROTTLED, TRANSIENT, GONE }

    /** 字节级流验证结论:VERIFIED/FAILED 写行状态;INCONCLUSIVE 与 TRANSIENT 一律不下结论 */
    enum StreamVerdict { VERIFIED, FAILED, INCONCLUSIVE, TRANSIENT }

    void setStreamProbeClient(StreamProbeClient client) {
        this.streamProbeClient = client;
    }

    /**
     * 失败三分:限流 → 瞬时 → 失效。<b>未识别错误默认按瞬时</b> —— 误判瞬时只晚一轮再探,
     * 误判失效会 RETIRED + 跨订阅黑名单(dead_link 无过期),代价不对称,往安全方向倒;
     * streak 上限防"措辞怪异的真死源"被无限重试。
     */
    static ProbeFailure classifyProbeFailure(Throwable e) {
        String message = e == null ? "" : StringUtils.defaultString(e.getMessage());
        if (isThrottleError(message)) {
            return ProbeFailure.THROTTLED;
        }
        if (GONE_ERROR.matcher(message).find()) {
            return ProbeFailure.GONE;
        }
        return ProbeFailure.TRANSIENT;
    }

    /** 记一次瞬时故障;连续达上限返回 true(本次应按失效处理)并清零重新计时。 */
    boolean transientStreakReached(MediaSubscriptionResource resource) {
        int streak = transientStreak.merge(resource.getId(), 1, Integer::sum);
        int limit = Math.max(1, appProperties.getSubscription().getProbeTransientStreak());
        if (streak >= limit) {
            transientStreak.remove(resource.getId());
            log.info("resource {} transient streak reached {}, treating as gone", resource.getId(), limit);
            return true;
        }
        return false;
    }

    private void clearTransientStreak(MediaSubscriptionResource resource) {
        transientStreak.remove(resource.getId());
    }

    /** 探测结果分级(供调用方决定是否跳过同盘后续候选)。 */
    enum ProbeOutcome { PROBED, ALIEN, THROTTLED, TRANSIENT, DEAD }

    /**
     * 候选探测统一入口(与 {@link #activateNextCandidate} 的失败分级同口径):成功 → 刷新行时间戳并清 streak;
     * 异剧 → 就地退役不拉黑(probeShare 内部已退役过一次,这里幂等重试删挂载);限流 → 记盘限流退避,
     * <b>不退役不拉黑</b>(限流时资源是好的,烧掉即 90 天黑名单)且调用方应跳过该盘本轮后续候选;
     * 瞬时 → streak 未达上限不退役;其余(明确失效)→ 退役+拉黑。此前 fillGaps/主盘/线路三路各写一份
     * catch 且漂移缺失分级,限流/异剧直接落入退役+拉黑。
     */
    ProbeOutcome probeCandidateSafely(MediaSubscription subscription, MediaSubscriptionResource resource) {
        try {
            probeShare(subscription, resource);
            clearTransientStreak(resource);
            resource.setCheckedTime(System.currentTimeMillis());
            resourceRepository.save(resource);
            return ProbeOutcome.PROBED;
        } catch (Exception e) {
            log.info("probe candidate {} failed: {}", resource.getId(), e.getMessage());
            String message = e.getMessage();
            if (isForeignShowRejection(message)) {
                retireAlienCandidate(subscription, resource);
                return ProbeOutcome.ALIEN;
            }
            if (isThrottleError(message)) {
                String drive = driveOf(resource);
                throttleDrive(drive);
                resource.setCheckedTime(System.currentTimeMillis());
                resourceRepository.save(resource);
                log.info("drive {} throttled during probe, skip its candidates this round", drive);
                return ProbeOutcome.THROTTLED;
            }
            if (classifyProbeFailure(e) == ProbeFailure.TRANSIENT && !transientStreakReached(resource)) {
                return ProbeOutcome.TRANSIENT; // 瞬时故障:不退役不拉黑,下轮再探
            }
            retireResource(subscription, resource, message, true);
            return ProbeOutcome.DEAD;
        }
    }

    /** 本轮已撞限流(本调用方记录 ∪ 全局盘限流退避)的盘,后续候选直接跳过。 */
    boolean driveThrottledThisRound(String drive, Set<String> throttledDrives) {
        return drive == null || throttledDrives.contains(drive) || isDriveThrottled(drive);
    }

    /**
     * 字节级流验证:取链解析(AList getFile)后对直链发小段 Range 请求 —— "解析成功"≠"CDN 真出流"。
     * 判定保守:HTML 假页/404/410 才判死(FAILED 有 7 天复活兜底);401/403 可能是防盗链要求
     * (夸克 {@code #x-referer} 一类),无结论;代理型驱动无直链,退回解析级语义(维持原行为)。
     */
    StreamVerdict verifyStream(String mountPath, MediaSubscriptionEpisodeSource row) {
        cn.har01d.alist_tvbox.model.FsDetail detail;
        try {
            detail = aListService.getFile(site(), mountPath + "/" + row.getRelPath());
        } catch (Exception e) {
            String message = StringUtils.defaultString(e.getMessage());
            log.debug("stream resolve failed for row {}: {}", row.getId(), message);
            // "参数错误"同文案两义:真死链,或百度游客取链撞反爬瞬时窗口(线上案例:主源半小时前还在
            // 正常拉流,样本+传染两次探测 2.4s 内同错,被判死删挂载+90 天黑名单)。误杀(删挂载/黑名单)
            // 与误留(行降 FAILED、缺集重开、列目录失效路径仍会兜底)代价不对称 → 单独降级不下结论。
            if (message.contains("参数错误")) {
                return StreamVerdict.TRANSIENT;
            }
            return classifyProbeFailure(e) == ProbeFailure.GONE ? StreamVerdict.FAILED : StreamVerdict.TRANSIENT;
        }
        if (detail == null) {
            return StreamVerdict.TRANSIENT; // 解析无结果且无异常:无结论性证据,不下判
        }
        String rawUrl = detail.getRawUrl();
        if (StringUtils.isBlank(rawUrl)) {
            return StreamVerdict.VERIFIED; // 代理型驱动无直链:解析成功即验证,不倒退
        }
        int hash = rawUrl.indexOf('#');
        if (hash >= 0) {
            rawUrl = rawUrl.substring(0, hash); // 剥 quark #x-referer 一类 URL 片段
        }
        var config = appProperties.getSubscription();
        try {
            StreamProbeClient.ProbeResult probe = streamProbeClient.fetch(rawUrl, Constants.USER_AGENT,
                    config.getStreamProbeMaxBytes(), config.getStreamProbeTimeoutSeconds());
            return verdictOf(probe);
        } catch (Exception e) {
            log.debug("stream fetch failed for row {}: {}", row.getId(), e.getMessage());
            return classifyProbeFailure(e) == ProbeFailure.GONE ? StreamVerdict.FAILED : StreamVerdict.TRANSIENT;
        }
    }

    /** 判定矩阵:200/206 非假页=VERIFIED;HTML 假页/404/410=FAILED;401/403 与其它状态=无结论。 */
    static StreamVerdict verdictOf(StreamProbeClient.ProbeResult probe) {
        if (probe == null) {
            return StreamVerdict.TRANSIENT;
        }
        if (probe.status() == 200 || probe.status() == 206) {
            return isHtmlTrap(probe) ? StreamVerdict.FAILED : StreamVerdict.VERIFIED;
        }
        if (probe.status() == 404 || probe.status() == 410) {
            return StreamVerdict.FAILED;
        }
        return StreamVerdict.INCONCLUSIVE;
    }

    /** HTML 假页(和谐登录页/风控页):Content-Type 声明 html 且实体确含 html 标记,双证才判死。 */
    static boolean isHtmlTrap(StreamProbeClient.ProbeResult probe) {
        String contentType = StringUtils.defaultString(probe.contentType()).toLowerCase(java.util.Locale.ROOT);
        if (!contentType.contains("text/html")) {
            return false;
        }
        byte[] body = probe.body() == null ? new byte[0] : probe.body();
        String head = new String(body, 0, Math.min(body.length, 512), java.nio.charset.StandardCharsets.UTF_8)
                .toLowerCase(java.util.Locale.ROOT);
        return head.contains("<html") || head.contains("<!doctype html");
    }

    /** 转存校验发现"列得出、拷不过去"的集:该(集,资源)行判 FAILED —— 旧 broken_episodes 登记表的行级替代品。 */
    public void markTransferBroken(MediaSubscription subscription, Map<Integer, String> episodeToDir) {
        if (episodeToDir == null || episodeToDir.isEmpty()) {
            return;
        }
        List<MediaSubscriptionResource> mounted = mountedResources(subscription);
        for (var entry : episodeToDir.entrySet()) {
            MediaSubscriptionResource owner = mounted.stream()
                    .filter(r -> StringUtils.isNotBlank(r.getMountPath()) && entry.getValue().startsWith(r.getMountPath()))
                    .findFirst().orElse(null);
            if (owner == null) {
                continue;
            }
            episodeSourceRepository.findBySubscriptionAndNumber(subscription.getId(), entry.getKey()).stream()
                    .filter(row -> row.getResourceId() == owner.getId() && LIVE_STATES.contains(row.getState()))
                    .findFirst()
                    .ifPresent(this::markFailed);
        }
    }

    // ---------- 播放选源(episode_source 索引) ----------

    /** 播放候选:某集在某挂载资源里的那一行。转存副本(非资源)由调用方排在更前。 */
    public record PlayCandidate(MediaSubscriptionResource resource, MediaSubscriptionEpisodeSource source) {
    }

    private List<MediaSubscriptionResource> mountedResources(MediaSubscription subscription) {
        return resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId()).stream()
                .filter(r -> MediaSubscriptionResource.STATE_MOUNTED.equals(r.getState())
                        && StringUtils.isNotBlank(r.getMountPath()))
                .toList();
    }

    /** 某集的可播候选(集源行索引直查,不再逐挂载点递归列目录):
     * LIVE 行 × MOUNTED 资源,按 VERIFIED>LISTED、资源分降序、成功率降序、失败率升序。 */
    public List<PlayCandidate> playCandidates(MediaSubscription subscription, int episode) {
        Map<Integer, MediaSubscriptionResource> mounted = new HashMap<>();
        for (MediaSubscriptionResource resource : mountedResources(subscription)) {
            mounted.put(resource.getId(), resource);
        }
        return episodeSourceRepository.findBySubscriptionAndNumber(subscription.getId(), episode).stream()
                .filter(row -> LIVE_STATES.contains(row.getState()))
                .filter(row -> mounted.containsKey(row.getResourceId()))
                .map(row -> new PlayCandidate(mounted.get(row.getResourceId()), row))
                .sorted(Comparator.comparing(PlayCandidate::source, SOURCE_ORDER)
                        .thenComparing(c -> -(c.resource().getScore() == null ? 0 : c.resource().getScore()))
                        .thenComparing(c -> TextUtils.picturePenalty(c.source().getRelPath()))) // 同分优先非 DV 版,防绿屏
                .toList();
    }

    /** 播放取链成功:行升 VERIFIED,成功率 +1。 */
    public void recordPlaySuccess(MediaSubscriptionEpisodeSource row) {
        markVerified(row);
    }

    /** 播放取链失败:行降 FAILED、资源降分、失败传染判定(整源死则就地退役并触发换源条件)。 */
    public void recordPlayFailure(MediaSubscription subscription, PlayCandidate candidate) {
        markFailed(candidate.source());
        MediaSubscriptionResource resource = candidate.resource();
        resource.setScore((resource.getScore() == null ? 0 : resource.getScore()) - PLAY_FAILURE_PENALTY);
        resource.setCheckedTime(System.currentTimeMillis());
        resourceRepository.save(resource);
        log.info("subscription {} demoted resource {} after play failure", subscription.getId(), resource.getId());
        contagion(subscription, resource, candidate.source().getId());
    }

    // ---------- 缺集补搜与补缺挂载(需求 1) ----------

    /**
     * 探测候选池(临时挂载列集数并落集源行,用后即删),覆盖缺口的资源挂为"补缺"源(.sources/ 下,常驻,清理豁免)。
     * 已挂载的补缺源由 {@link #refreshAuxMounts} 在 doCheck 里原位刷新,不在此处理。
     * 挂载数达上限(maxGapMounts)后不再探测新候选;池耗尽仍缺 → 搜索:先整季关键词,再逐集降级(第N集)。
     */
    void fillGaps(MediaSubscription subscription, Set<Integer> missingStill) {
        int maxMounts = appProperties.getSubscription().getMaxGapMounts();
        int auxMounted = auxMounts(subscription).size();

        int probed = 0;
        int maxProbes = appProperties.getSubscription().getMaxGapProbesPerRound();
        Set<String> throttledDrives = new java.util.HashSet<>(); // 本轮已撞风控的盘,后续候选直接跳过
        for (MediaSubscriptionResource resource : candidatesOrdered(subscription)) {
            if (probed >= maxProbes || missingStill.isEmpty() || auxMounted >= maxMounts) {
                break;
            }
            if (driveThrottledThisRound(resource.getType() == null ? null : DriveId.toDrive(resource.getType()), throttledDrives)) {
                continue;
            }
            // 已探测过且不覆盖剩余缺口的跳过(行存在 = 探测过;LIVE 行集号 = 覆盖)
            if (episodeSourceRepository.countByResourceId(resource.getId()) > 0
                    && intersection(coverageOf(resource), missingStill).isEmpty()) {
                continue;
            }
            ProbeOutcome outcome = probeCandidateSafely(subscription, resource);
            if (outcome != ProbeOutcome.PROBED) {
                if (outcome == ProbeOutcome.THROTTLED) {
                    throttledDrives.add(driveOf(resource));
                }
                continue;
            }
            probed++;
            Set<Integer> useful = intersection(coverageOf(resource), missingStill);
            if (!useful.isEmpty()) {
                try {
                    if (mountAux(subscription, resource)) {
                        auxMounted++;
                        missingStill.removeAll(useful);
                        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_GAP_FILLED,
                                "补缺 第" + joinNumbers(new ArrayList<>(useful)) + " 集(来自 " + StringUtils.defaultIfBlank(resource.getTitle(), "候选源") + ")");
                        gapSearchRounds.remove(subscription.getId());
                    }
                } catch (Exception e) {
                    log.warn("mount gap source failed: {}", e.getMessage());
                    addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "补缺挂载失败:" + e.getMessage());
                }
            }
        }

        if (!missingStill.isEmpty()) {
            int round = gapSearchRounds.merge(subscription.getId(), 1, Integer::sum);
            String keyword = gapSearchKeyword(subscription, missingStill, round);
            if (keyword != null) {
                fillPool(subscription, true, keyword);
            }
        }
    }

    /** 补搜关键词决策:播出窗口内且缺口只含官方已播最新一集 = 资源大概率未上线,
     * 保持整季关键词且隔轮限频(空搜节制,窗口过后恢复逐集降级);其余场景整季(首轮)→单集降级。
     * @return null = 本轮跳过搜索(限频) */
    String gapSearchKeyword(MediaSubscription subscription, Set<Integer> missing, int round) {
        if (inPostAirWindow(subscription) && latestOnlyGap(subscription, missing)) {
            return round % 2 == 1 ? seasonKeyword(subscription) : null;
        }
        if (round == 1) {
            return seasonKeyword(subscription);
        }
        // 单集降级:逐次尝试不同缺失集
        List<Integer> list = new ArrayList<>(missing);
        int index = Math.min(round - 2, list.size() - 1);
        return subscription.getName() + " 第" + list.get(Math.max(index, 0)) + "集";
    }

    private String seasonKeyword(MediaSubscription subscription) {
        return StringUtils.defaultIfBlank(subscription.getKeyword(), subscription.getName());
    }

    /** 是否处于播出后短轮窗口:窗口内新集资源可能尚未上线,缺最新集不构成"资源缺失"。 */
    boolean inPostAirWindow(MediaSubscription subscription) {
        Long air = subscription.getNextAirTime();
        if (air == null || air <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        return now >= air && now < air + appProperties.getSubscription().getShortPollWindowHours() * 3600_000L;
    }

    /** 缺口只含官方已播的最新一集(老集都齐,只差刚播的这集)。 */
    boolean latestOnlyGap(MediaSubscription subscription, Set<Integer> missing) {
        Integer aired = subscription.getOfficialEpisodes();
        return aired != null && aired > 0 && !missing.isEmpty()
                && missing.stream().allMatch(episode -> episode.equals(aired));
    }

    /** 主网盘:订阅级 main_drives 覆盖 > 全局 Setting msub_main_drives(均为逗号分隔分享类型码,取前 2)。
     * 巡检保证该盘完整剧集覆盖,播放列表固定出该盘线路。subscription 为 null 时只看全局(preview 无订阅上下文)。 */
    List<String> mainDrives(MediaSubscription subscription) {
        String raw = subscription == null ? null : subscription.getMainDrives();
        if (StringUtils.isBlank(raw)) {
            raw = settingRepository.findById(MSUB_MAIN_DRIVES).map(s -> s.getValue()).orElse("");
        }
        if (StringUtils.isBlank(raw)) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(DriveId::toTypeOrNull)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .limit(2)
                .map(DriveId::toDrive)
                .toList();
    }

    /** 扩展网盘(全局 Setting msub_extended_drives,逗号分隔分享类型码):主网盘以外允许入候选池的盘。
     * 未配置时候选只收主网盘 —— 不再自动收录 115 等其它盘的分享源,必须配置才进候选。 */
    List<String> extendedDrives() {
        String raw = settingRepository.findById(MSUB_EXTENDED_DRIVES).map(s -> s.getValue()).orElse("");
        if (StringUtils.isBlank(raw)) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(DriveId::toTypeOrNull)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(DriveId::toDrive)
                .toList();
    }

    /** 候选盘白名单:主网盘 ∪ 扩展网盘。空 = 主/扩展均未配置,不限盘(兼容旧行为);
     * 配置了主网盘后白名单以外的盘不再入池/探测/换源/补线 —— 默认只有主网盘的源。 */
    Set<String> allowedCandidateDrives(MediaSubscription subscription) {
        Set<String> allowed = new java.util.LinkedHashSet<>(mainDrives(subscription));
        allowed.addAll(extendedDrives());
        return allowed;
    }

    /** 候选盘白名单判定:白名单为空不限盘;配置后无 type 的旧资源(判不了盘)视为域外。 */
    static boolean driveAllowed(Set<String> allowed, String drive) {
        return allowed.isEmpty() || (drive != null && allowed.contains(drive));
    }

    /** 全局资源筛选(Setting msub_pool_filter 单行 JSON):即读即用,坏配置/未配置回落空对象(全部门禁关闭),不炸巡检。 */
    MediaSubscriptionPoolFilter globalPoolFilter() {
        String raw = settingRepository.findById(MSUB_POOL_FILTER).map(s -> s.getValue()).orElse("");
        return parsePoolFilter(raw);
    }

    /**
     * 订阅生效的资源筛选:订阅人配置了用户级 msub_pool_filter:u{uid} 则以其为准(候选打分偏好,§3.1),
     * 否则回退全局键。用户级行不存在/为空/坏值均回退全局,与全局侧「坏配置回落空对象」口径一致。
     */
    MediaSubscriptionPoolFilter poolFilterFor(MediaSubscription subscription) {
        if (subscription != null && subscription.getUid() > 0) {
            String raw = settingRepository
                    .findById(SettingService.userSettingKey(MSUB_POOL_FILTER, subscription.getUid()))
                    .map(s -> s.getValue())
                    .orElse("");
            if (StringUtils.isNotBlank(raw)) {
                return parsePoolFilter(raw);
            }
        }
        return globalPoolFilter();
    }

    private MediaSubscriptionPoolFilter parsePoolFilter(String raw) {
        if (StringUtils.isBlank(raw)) {
            return new MediaSubscriptionPoolFilter();
        }
        try {
            MediaSubscriptionPoolFilter filter = objectMapper.readValue(raw, MediaSubscriptionPoolFilter.class);
            filter.normalize();
            return filter;
        } catch (Exception e) {
            log.warn("parse {} failed: {}", MSUB_POOL_FILTER, e.getMessage());
            return new MediaSubscriptionPoolFilter();
        }
    }

    /** 全局包含词硬门禁:配置非空时标题须至少包含其一;空 = 不限。 */
    static boolean globallyIncluded(MediaSubscriptionPoolFilter global, String title) {
        return global.getIncludeKeywords() == null || global.getIncludeKeywords().isEmpty()
                || matchesKeywords(title, global.getIncludeKeywords());
    }

    /** 清晰度门槛:仅拒标题<b>明确标注</b>低于门槛的资源;未标注放行(挂载前无从判断,避免误杀召回)。 */
    static boolean qualityAboveFloor(MediaSubscriptionPoolFilter global, String title) {
        String floor = MediaSubscriptionPoolFilter.normalizeQuality(global.getMinQuality());
        if (floor.isEmpty()) {
            return true;
        }
        String quality = titleQuality(title);
        return quality == null || QUALITY_RANK.get(quality) >= QUALITY_RANK.get(floor);
    }

    /** 标题标注的清晰度档位:uhd(4K/2160)> fhd(1080)> hd(720);未标注返回 null(门槛放行、打分不加)。 */
    static String titleQuality(String title) {
        if (StringUtils.containsIgnoreCase(title, "4K") || StringUtils.containsIgnoreCase(title, "2160")) {
            return "uhd";
        }
        if (StringUtils.containsIgnoreCase(title, "1080")) {
            return "fhd";
        }
        if (StringUtils.containsIgnoreCase(title, "720")) {
            return "hd";
        }
        return null;
    }

    /** 清晰度档位序:hd(720P)=1 < fhd(1080P)=2 < uhd(4K)=3 */
    private static final Map<String, Integer> QUALITY_RANK = Map.of("hd", 1, "fhd", 2, "uhd", 3);

    /** 当前主源所在盘(主源资源行的分享类型;旧数据无 type 返回 null)。 */
    String activeDrive(MediaSubscription subscription) {
        MediaSubscriptionResource primary = primaryResource(subscription);
        return primary != null && primary.getType() != null ? DriveId.toDrive(primary.getType()) : null;
    }

    /** 主网盘完整覆盖保障:观测全集(全部挂载资源集源并集)按盘核算,主网盘缺口从候选池**同盘**资源探则挂
     * (与 fillGaps 同机制但按盘约束,主源所在盘天然计为已覆盖)。池内无该盘资源不强制搜索——
     * driveTypes 偏好已让搜索召回偏向主网盘,靠常规搜索周期自然补池;转存副本不计入(自有事后校验保障)。
     * 分享挂载均为游客态(免登录);需登录态才稳定的盘探测会失败落 RETIRED,自然退出候选。 */
    void ensureMainDrives(MediaSubscription subscription, Set<Integer> primaryEpisodes) {
        List<String> mains = mainDrives(subscription);
        if (mains.isEmpty() || primaryEpisodes.isEmpty()) {
            return;
        }
        List<MediaSubscriptionResource> resources = resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId());
        MediaSubscriptionResource primary = primaryResource(subscription);
        String active = primary == null || primary.getType() == null ? null : DriveId.toDrive(primary.getType());
        int mounted = auxMounts(subscription).size();
        int maxMounts = appProperties.getSubscription().getMaxGapMounts();
        for (String drive : mains) {
            Set<Integer> coverage = new TreeSet<>();
            if (primary != null && drive.equals(active)) {
                coverage.addAll(coverageOf(primary));
            }
            for (MediaSubscriptionResource resource : auxMounts(subscription)) {
                if (resource.getType() != null && drive.equals(DriveId.toDrive(resource.getType()))) {
                    coverage.addAll(coverageOf(resource));
                }
            }
            Set<Integer> missing = new TreeSet<>(primaryEpisodes);
            missing.removeAll(coverage);
            if (missing.isEmpty()) {
                continue;
            }
            int probed = 0;
            int maxProbes = appProperties.getSubscription().getMaxGapProbesPerRound();
            for (MediaSubscriptionResource resource : resources) {
                if (missing.isEmpty() || probed >= maxProbes || mounted >= maxMounts) {
                    break;
                }
                if (MediaSubscriptionResource.STATE_MOUNTED.equals(resource.getState())
                        || resource.getType() == null || !drive.equals(DriveId.toDrive(resource.getType()))) {
                    continue;
                }
            if (episodeSourceRepository.countByResourceId(resource.getId()) > 0
                    && intersection(coverageOf(resource), missing).isEmpty()) {
                continue; // 已探测过且不覆盖主网盘缺口
            }
            if (isDriveThrottled(drive)) {
                break; // 该盘已撞限流:同盘后续候选必然连环触发,本轮放弃该盘
            }
            ProbeOutcome outcome = probeCandidateSafely(subscription, resource);
            if (outcome != ProbeOutcome.PROBED) {
                if (outcome == ProbeOutcome.THROTTLED) {
                    break; // 本轮该盘限流:同上
                }
                continue;
            }
            probed++;
                Set<Integer> useful = intersection(coverageOf(resource), missing);
                if (!useful.isEmpty()) {
                    try {
                        if (mountAux(subscription, resource)) {
                            mounted++;
                            missing.removeAll(useful);
                            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_GAP_FILLED,
                                    "主网盘[" + drive + "] 补齐 第" + joinNumbers(new ArrayList<>(useful)) + " 集(来自 "
                                            + StringUtils.defaultIfBlank(resource.getTitle(), "候选源") + ")");
                        }
                    } catch (Exception e) {
                        log.warn("mount main-drive source failed: {}", e.getMessage());
                        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "主网盘补缺挂载失败:" + e.getMessage());
                    }
                }
            }
            if (!missing.isEmpty()) {
                log.info("subscription {} main drive [{}] still missing episodes {} (pool has no covering candidate)",
                        subscription.getId(), drive, missing);
                // 池内无该盘资源:主动搜索完整源(限频每检查周期一次,与补缺搜索叠加至多 2 次/轮)
                long interval = Math.max(6, subscription.getCheckIntervalHours() == null
                        ? appProperties.getSubscription().getCheckIntervalHours() : subscription.getCheckIntervalHours()) * 3600_000L;
                long now = System.currentTimeMillis();
                Long last = mainDriveSearchTime.get(subscription.getId());
                if (last == null || now - last >= interval) {
                    mainDriveSearchTime.put(subscription.getId(), now);
                    log.info("subscription {} force search for main drive [{}] complete sources", subscription.getId(), drive);
                    fillPool(subscription, true, seasonKeyword(subscription));
                }
            }
        }
    }

    /**
     * 分盘线路保障:候选池里每个网盘(除主源盘)探测挂载至少一个覆盖观测集的源,让 TVBox 详情
     * 按盘出备用线路(百度/夸克/115…),不依赖用户配置主网盘 —— 主网盘机制是"完整覆盖保障",
     * 这里只要"该盘有线可用":整季源挂 1 个即满覆盖;单集源(115 每集一链)逐集挂,
     * 至 driveLineMountsPerDrive 上限。与补缺共用 maxGapMounts 挂载预算与探测限额
     * (fillGaps/ensureMainDrives 先行,缺集优先占预算);挂后由 retireCoveredAuxMounts
     * 的同盘冗余清理兜底,不被"主源已覆盖"整批回收。主源未集齐、无观测集时不出线。
     */
    void ensureDriveLines(MediaSubscription subscription, Set<Integer> present) {
        AppProperties.Subscription config = appProperties.getSubscription();
        if (!config.isDriveLinesEnabled() || present.isEmpty()) {
            return;
        }
        String active = activeDrive(subscription);
        List<MediaSubscriptionResource> aux = auxMounts(subscription);
        int mounted = aux.size();
        int maxMounts = config.getMaxGapMounts();
        int maxPerDrive = config.getDriveLineMountsPerDrive();
        Map<String, Set<Integer>> coverageByDrive = new LinkedHashMap<>();
        Map<String, Integer> mountsByDrive = new HashMap<>();
        for (MediaSubscriptionResource resource : aux) {
            if (resource.getType() == null) {
                continue;
            }
            String drive = DriveId.toDrive(resource.getType());
            coverageByDrive.computeIfAbsent(drive, key -> new TreeSet<>()).addAll(coverageOf(resource));
            mountsByDrive.merge(drive, 1, Integer::sum);
        }
        int probed = 0;
        int maxProbes = config.getMaxGapProbesPerRound();
        Set<String> throttledDrives = new java.util.HashSet<>(); // 本轮已撞风控的盘,后续候选直接跳过
        for (MediaSubscriptionResource resource : candidatesOrdered(subscription)) {
            if (probed >= maxProbes || mounted >= maxMounts) {
                break;
            }
            String drive = resource.getType() == null ? null : DriveId.toDrive(resource.getType());
            if (drive == null || drive.equals(active)) {
                continue;
            }
            if (driveThrottledThisRound(drive, throttledDrives)) {
                continue;
            }
            Set<Integer> lineCoverage = coverageByDrive.getOrDefault(drive, Set.of());
            if (lineCoverage.containsAll(present) || mountsByDrive.getOrDefault(drive, 0) >= maxPerDrive) {
                continue; // 该盘线路已满覆盖/达挂载上限
            }
            if (episodeSourceRepository.countByResourceId(resource.getId()) > 0
                    && intersection(coverageOf(resource), present).isEmpty()) {
                continue; // 已探测过且不覆盖任何观测集:出线无意义
            }
            ProbeOutcome outcome = probeCandidateSafely(subscription, resource);
            if (outcome != ProbeOutcome.PROBED) {
                if (outcome == ProbeOutcome.THROTTLED) {
                    throttledDrives.add(drive);
                }
                continue;
            }
            probed++;
            Set<Integer> useful = intersection(coverageOf(resource), present);
            if (!useful.isEmpty()) {
                try {
                    if (mountAux(subscription, resource)) {
                        mounted++;
                        mountsByDrive.merge(drive, 1, Integer::sum);
                        coverageByDrive.computeIfAbsent(drive, key -> new TreeSet<>()).addAll(useful);
                        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_DRIVE_LINE,
                                "分盘线路[" + DriveId.displayName(drive) + "] 挂载:覆盖 第"
                                        + joinNumbers(new ArrayList<>(useful)) + " 集(来自 "
                                        + StringUtils.defaultIfBlank(resource.getTitle(), "候选源") + ")");
                    }
                } catch (Exception e) {
                    log.warn("mount drive-line source failed: {}", e.getMessage());
                    addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "分盘线路挂载失败:" + e.getMessage());
                }
            }
        }
    }

    /** 详情装配时判断是否值得异步补线:存在未出线网盘的候选(主源盘除外)。 */
    boolean hasUnlinedDriveCandidates(MediaSubscription subscription, Set<String> linedDrives) {
        if (!appProperties.getSubscription().isDriveLinesEnabled()) {
            return false;
        }
        String active = activeDrive(subscription);
        return candidatesOrdered(subscription).stream()
                .anyMatch(r -> r.getType() != null
                        && !linedDrives.contains(DriveId.toDrive(r.getType()))
                        && !DriveId.toDrive(r.getType()).equals(active));
    }

    /** 详情触发分盘线路补挂(异步,限频 10min):TVBox 打开详情时线路未齐,下一次刷新即补上,不必等巡检周期。 */
    public void ensureDriveLinesAsync(int uid, int subscriptionId) {
        MediaSubscription subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (subscription == null || subscription.getUid() != uid
                || MediaSubscription.STATUS_PAUSED.equals(subscription.getStatus())
                || !appProperties.getSubscription().isDriveLinesEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = driveLineKickTime.putIfAbsent(subscriptionId, now);
        if (last != null) {
            if (now - last < DRIVE_LINE_KICK_THROTTLE_MS) {
                return;
            }
            driveLineKickTime.put(subscriptionId, now);
        }
        executor.submit(() -> {
            if (!tryLock(subscriptionId)) {
                return;
            }
            try {
                // 锁内取新实体:排队等锁期间完整巡检可能已整行保存(元数据/调度/状态),旧实体再
                // save 会把这些字段回滚覆盖 —— check(id)/refreshAiringDue 同款口径
                MediaSubscription current = subscriptionRepository.findById(subscriptionId).orElse(null);
                if (current == null || MediaSubscription.STATUS_PAUSED.equals(current.getStatus())) {
                    return;
                }
                resyncPrimaryInventory(current);
                ensureDriveLines(current, liveEpisodeNumbers(current));
                if (stopIfDeleted(subscriptionId)) {
                    return;
                }
                refreshEpisodeCounters(current);
            } catch (Exception e) {
                log.warn("ensure drive lines for subscription {} failed: {}", subscriptionId, e.getMessage());
            } finally {
                inFlight.remove(subscriptionId);
            }
        });
    }

    /** 轻刷集数快照(currentEpisodes/maxEpisode):分盘线路探测常把主源之外的全集行落库,
     * 不刷的话列表 remarks 停在旧口径直到下轮巡检(可达 6~24h)。只读行并集,不触碰状态机。 */
    void refreshEpisodeCounters(MediaSubscription subscription) {
        try {
            Set<Integer> live = liveEpisodeNumbers(subscription);
            if (live.isEmpty()) {
                return;
            }
            Integer max = live.stream().max(Integer::compareTo).orElse(null);
            Integer current = subscription.getCurrentEpisodes();
            if ((current == null || current != live.size()) || !java.util.Objects.equals(subscription.getMaxEpisode(), max)) {
                subscription.setCurrentEpisodes(live.size());
                subscription.setMaxEpisode(max);
                subscription.setUpdatedTime(System.currentTimeMillis());
                subscriptionRepository.save(subscription);
            }
        } catch (Exception e) {
            log.debug("refresh episode counters for subscription {} failed: {}", subscription.getId(), e.getMessage());
        }
    }

    /**
     * 详情触发的轻量自愈:重列主源同步集源行 —— 画质择优换版本文件后(DV 版 → SDR 版)rel_path 原位更新,
     * msubep 播放立即生效;不必等下一轮巡检(完结订阅退避 24h 甚至 ENDED 后不再完整巡检)。
     * 只列不判:失败静默跳过,失效确认仍归巡检。
     */
    private void resyncPrimaryInventory(MediaSubscription subscription) {
        try {
            if (StringUtils.isBlank(subscription.getMountPath()) || subscription.getShareId() == null) {
                return;
            }
            TreeMap<Integer, EpisodeFile> files = listEpisodeFiles(subscription);
            if (!files.isEmpty()) {
                syncInventory(subscription, primaryResource(subscription), subscription.getMountPath(), files);
            }
        } catch (Exception e) {
            log.debug("resync primary inventory failed: {}", e.getMessage());
        }
    }

    /** RETIRED/REJECTED 冷却超期 = 允许重探一次(历史误标自愈;重探再失败会刷新计时)。 */
    boolean isBadCooled(MediaSubscriptionResource resource, long now) {
        if (!MediaSubscriptionResource.STATE_RETIRED.equals(resource.getState())
                && !MediaSubscriptionResource.STATE_REJECTED.equals(resource.getState())) {
            return false;
        }
        Long checked = resource.getCheckedTime();
        long cooldown = appProperties.getSubscription().getBadCooldownDays() * 24L * 3600_000;
        return checked == null || now - checked >= cooldown;
    }

    /** 临时挂载候选列集数并落集源行(LISTED,rel_path 与挂载点无关,转正式挂载后依然有效),用后即删。
     * 列得出 ≠ 播得了:临时挂载窗口内抽一行做字节级取链,链死(过期/假页)以「链接已过期」上抛,
     * 按失效退役+黑名单 —— 不再把"分享页活着但文件链已和谐"的资源挂上来占名额,等下轮采样才发现。 */
    void probeShare(MediaSubscription subscription, MediaSubscriptionResource resource) {
        ShareLink shareLink = new ShareLink();
        shareLink.setLink(resource.getLink());
        shareLink.setCode(StringUtils.defaultString(resource.getPassword()));
        shareService.add(shareLink);
        Share probe = shareService.parseShareLink(resource.getLink());
        Share share = null;
        if (probe != null) {
            List<Share> temps = shareRepository.findByTypeAndShareIdAndTempTrue(probe.getType(), probe.getShareId());
            if (!temps.isEmpty()) {
                share = temps.get(temps.size() - 1);
            }
        }
        if (share == null) {
            throw new IllegalStateException("临时挂载失败:" + resource.getLink());
        }
        List<String> genres = metaGenres(subscription);
        try {
            TreeMap<Integer, EpisodeFile> files = new TreeMap<>();
            collectEpisodeFiles(site(), subscription.getSeason(), share.getPath(), 1, files,
                    episodeSizePolicy(subscription), true, metaYear(subscription));
            sanitizeEpisodeFiles(subscription, files, resource.getTitle());
            if (files.isEmpty()) {
                throw new IllegalStateException("资源无可识别的剧集文件:" + resource.getTitle());
            }
            if (episodeNumbersForeign(subscription, files.keySet(), genres)) {
                // 同名异剧:就地退役(RETIRED 冷却重探、不拉黑)再抛 —— 四路调用方(fillGaps/主盘/
                // 线路/升级探测)catch 后按未识别错误(瞬时)跳过,不退役的话每轮都会重复探测它
                retireAlienCandidate(subscription, resource);
                addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_SOURCE_INVALID,
                        "候选与剧集不符(" + FOREIGN_SHOW_MARK + ")已跳过:"
                                + StringUtils.defaultIfBlank(resource.getTitle(), resource.getLink()), false);
                throw new IllegalStateException(foreignShowReason(subscription, files.lastKey(), resource.getTitle()));
            }
            if (episodeDurationForeign(metaRuntimeMinutes(subscription), files.values())) {
                // 单集时长显著不符(真人版 45min vs 动画版 20min):补集号门禁未播完容差内的盲区
                retireAlienCandidate(subscription, resource);
                addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_SOURCE_INVALID,
                        "候选与剧集不符(" + FOREIGN_SHOW_MARK + ")已跳过:"
                                + StringUtils.defaultIfBlank(resource.getTitle(), resource.getLink()), false);
                throw new IllegalStateException(FOREIGN_SHOW_MARK + "(单集时长与官方不符):" + resource.getTitle());
            }
            syncInventory(subscription, resource, share.getPath(), files);
            // 线上:115 单集分享 errno 4100018 —— 列目录成功,文件链接已过期,挂载后下轮采样即死。
            // 瞬时(限流/参数错误)与无结论(403 防盗链)不拦,只有明确链死才判废。
            MediaSubscriptionEpisodeSource sample = episodeSourceRepository.findByResourceId(resource.getId()).stream()
                    .filter(s -> LIVE_STATES.contains(s.getState()))
                    .findFirst().orElse(null);
            if (sample != null && verifyStream(share.getPath(), sample) == StreamVerdict.FAILED) {
                throw new IllegalStateException("资源链接已过期(文件不可播):" + resource.getTitle());
            }
        } finally {
            try {
                shareService.deleteShare(share.getId());
            } catch (Exception e) {
                log.warn("delete probe share failed: {}", e.getMessage());
            }
        }
    }

    /** 旧版补缺挂载(/追剧/{mount}-补N 与主源并排暴露给用户)迁移到内部目录 /追剧/.sources/ 下;失败回候选池下轮重探。 */
    private void migrateLegacyGapMount(MediaSubscription subscription, MediaSubscriptionResource resource) {
        String legacyPrefix = subscription.getMountPath() + "-补";
        if (!resource.getMountPath().startsWith(legacyPrefix)) {
            return;
        }
        try {
            String slug = subscription.getMountPath().substring(cn.har01d.alist_tvbox.util.Constants.SUBSCRIPTION_MOUNT_ROOT.length());
            int n = 1;
            String path = GAP_SOURCES_ROOT + slug + "-补" + n;
            while (shareRepository.existsByPath(path)) {
                n++;
                path = GAP_SOURCES_ROOT + slug + "-补" + n;
            }
            Share old = shareRepository.findByPath(resource.getMountPath());
            if (old != null) {
                shareService.deleteShare(old.getId());
            }
            ShareLink shareLink = new ShareLink();
            shareLink.setLink(resource.getLink());
            shareLink.setCode(StringUtils.defaultString(resource.getPassword()));
            shareLink.setPath(path);
            shareService.add(shareLink);
            Share share = shareRepository.findByPath(path);
            if (share == null) {
                throw new IllegalStateException("迁移补缺挂载失败:" + resource.getLink());
            }
            resource.setMountPath(path);
            resource.setShareId(share.getId());
            resourceRepository.save(resource);
            log.info("migrated gap mount of subscription {} to {}", subscription.getId(), path);
        } catch (Exception e) {
            log.warn("migrate legacy gap mount failed, reset to candidate: {}", e.getMessage());
            // 旧挂载已删而新挂载失败:回候选池并清掉行,否则幽灵集源行会把这些集从缺口中扣除;
            // 下轮作普通候选重探自愈
            resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
            resource.setMountPath(null);
            resource.setShareId(null);
            resource.setCheckedTime(System.currentTimeMillis());
            resourceRepository.save(resource);
            try {
                episodeSourceRepository.deleteByResourceId(resource.getId());
            } catch (Exception ignored) {
                // 行删不掉时下轮 syncInventory 会按新事实覆盖
            }
        }
    }

    /** 挂补缺源到内部目录 /追剧/.sources/{slug}-补N(用户视角 /追剧/ 下每部剧只有一个入口;常驻非 temp,清理豁免)。@return 是否真正新挂载(false=已挂载) */
    private boolean mountAux(MediaSubscription subscription, MediaSubscriptionResource resource) {
        if (resource.getShareId() != null && StringUtils.isNotBlank(resource.getMountPath())) {
            return false; // 已挂载
        }
        String slug = subscription.getMountPath().substring(cn.har01d.alist_tvbox.util.Constants.SUBSCRIPTION_MOUNT_ROOT.length());
        int n = 1;
        String path = GAP_SOURCES_ROOT + slug + "-补" + n;
        while (shareRepository.existsByPath(path)) {
            n++;
            path = GAP_SOURCES_ROOT + slug + "-补" + n;
        }
        ShareLink shareLink = new ShareLink();
        shareLink.setLink(resource.getLink());
        shareLink.setCode(StringUtils.defaultString(resource.getPassword()));
        shareLink.setPath(path);
        shareService.add(shareLink);
        Share share = shareRepository.findByPath(path);
        if (share == null) {
            throw new IllegalStateException("补缺挂载失败:" + resource.getLink());
        }
        resource.setState(MediaSubscriptionResource.STATE_MOUNTED);
        resource.setMountPath(path);
        resource.setShareId(share.getId());
        resourceRepository.save(resource);
        return true;
    }

    /**
     * 补缺/线路挂载回收:只清"同盘冗余"—— 挂载目录刷空的死挂载已在 refreshAuxMounts 就地退役,
     * 这里回收 ①覆盖为空 ②全部集被主源覆盖且不构成线路价值 的挂载,腾出 maxGapMounts 预算。
     * 线路价值 = 分盘线路开启时,同盘按分数序保留至多 {@code driveLineMountsPerDrive} 个
     * "各有独占集"的挂载(主源换盘/失效时该盘线路不断供;覆盖是同盘已保留挂载子集的纯冗余仍退,
     * 防同盘整季源×N 吃光预算)。主网盘冗余豁免沿用(完整覆盖保障的常备线)。
     */
    void retireCoveredAuxMounts(MediaSubscription subscription, Set<Integer> present) {
        AppProperties.Subscription config = appProperties.getSubscription();
        int cap = config.getDriveLineMountsPerDrive();
        List<String> mains = mainDrives(subscription);
        String active = activeDrive(subscription);
        Map<String, List<MediaSubscriptionResource>> byDrive = new LinkedHashMap<>();
        for (MediaSubscriptionResource resource : auxMounts(subscription)) { // 仓序即分数降序
            String drive = resource.getType() == null ? "" : DriveId.toDrive(resource.getType());
            byDrive.computeIfAbsent(drive, key -> new ArrayList<>()).add(resource);
        }
        for (var entry : byDrive.entrySet()) {
            String drive = entry.getKey();
            boolean mainDrive = !drive.isEmpty() && mains.contains(drive) && !drive.equals(active);
            Set<Integer> kept = new TreeSet<>();
            int keptCount = 0;
            for (MediaSubscriptionResource resource : entry.getValue()) {
                Set<Integer> coverage = coverageOf(resource);
                boolean keep = !coverage.isEmpty()
                        && (!present.containsAll(coverage) || mainDrive
                        || (config.isDriveLinesEnabled() && !drive.isEmpty() && !drive.equals(active)
                        && keptCount < cap && !kept.containsAll(coverage)));
                if (keep) {
                    kept.addAll(coverage);
                    keptCount++;
                    continue;
                }
                // 共享挂载:share 被其它订阅引用时不卸载,行照常回候选池;卸载失败(AList 不可用)则
                // 保留挂载状态与 shareId 待下轮重试 —— 先清字段会把唯一重试凭据抹掉,挂载变永久孤儿
                if (!unmountShareIfUnused(resource.getShareId(), subscription.getId())) {
                    log.warn("subscription {} keep aux mount {} (unmount failed, retry next round)",
                            subscription.getId(), resource.getId());
                    continue;
                }
                resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
                resource.setMountPath(null);
                resource.setShareId(null);
                resourceRepository.save(resource);
                for (MediaSubscriptionEpisodeSource row : episodeSourceRepository.findByResourceId(resource.getId())) {
                    if (LIVE_STATES.contains(row.getState())) {
                        row.setState(MediaSubscriptionEpisodeSource.STATE_MISSING);
                        episodeSourceRepository.save(row);
                    }
                }
                log.info("subscription {} retired aux mount {} (covered, drive [{}])",
                        subscription.getId(), resource.getId(), drive);
            }
        }
    }

    // ---------- 换源 ----------

    /** 首次挂载或主源行丢失:搜一遍填池并激活最优候选。 */
    /**
     * 共享挂载守卫:share 仍被其它订阅(主源或资源行)引用时不卸载 —— 挂载模式下多用户共用
     * 同一路径背后的分享,谁的资源退役都不该把别人正在看的挂载摘掉。
     */
    /** @return false=AList 删除失败,挂载仍有效须保留资源行待重试;true=已删/被共享引用跳过/无 shareId */
    private boolean unmountShareIfUnused(Integer shareId, int subscriptionId) {
        if (shareId == null) {
            return true;
        }
        if (subscriptionRepository.existsByShareIdAndIdNot(shareId, subscriptionId)
                || resourceRepository.existsByShareIdAndSubscriptionIdNot(shareId, subscriptionId)) {
            log.debug("share {} still referenced by another subscription, skip unmount", shareId);
            return true;
        }
        try {
            shareService.deleteShare(shareId);
        } catch (Exception e) {
            log.warn("unmount share {} failed: {}", shareId, e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * 共享挂载收编:挂载(FOLLOW)模式下同一路径可能已有其它订阅挂好的主源(多用户追同一部剧)。
     * 列目录通过本订阅门禁(集号/异剧/时长)就把它收为本订阅主源 —— 用该 share 的分享链接建
     * 资源行(类型/标题从引用它的既有资源行借),不删不换、零补缺;门禁不过才走搜索-替换流程。
     */
    private boolean adoptExistingMount(MediaSubscription subscription) {
        Share share = shareRepository.findByPath(subscription.getMountPath());
        if (share == null || share.getId().equals(subscription.getShareId())) {
            return false;
        }
        TreeMap<Integer, EpisodeFile> files = new TreeMap<>();
        try {
            collectEpisodeFiles(site(), subscription.getSeason(), subscription.getMountPath(), 1, files,
                    episodeSizePolicy(subscription), true, metaYear(subscription));
        } catch (Exception e) {
            log.debug("adopt existing mount {} failed to list: {}", subscription.getMountPath(), e.getMessage());
            return false;
        }
        sanitizeEpisodeFiles(subscription, files, null);
        if (files.isEmpty()
                || episodeNumbersForeign(subscription, files.keySet(), metaGenres(subscription))
                || episodeDurationForeign(metaRuntimeMinutes(subscription), files.values())) {
            return false;
        }
        MediaSubscriptionResource twin = resourceRepository.findFirstByShareIdOrderByIdAsc(share.getId()).orElse(null);
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setSubscriptionId(subscription.getId());
        resource.setLink(StringUtils.abbreviate(share.getShareId(), 1000));
        resource.setPassword(share.getPassword());
        if (twin != null) {
            resource.setType(twin.getType());
            resource.setSource(twin.getSource());
            resource.setTitle(twin.getTitle());
            resource.setScore(twin.getScore());
        } else {
            resource.setTitle(StringUtils.abbreviate(subscription.getName(), 250));
        }
        resource.setEpisodesFound(files.size());
        resource.setState(MediaSubscriptionResource.STATE_MOUNTED);
        resource.setMountPath(subscription.getMountPath());
        resource.setShareId(share.getId());
        resource.setCreatedTime(System.currentTimeMillis());
        resourceRepository.save(resource);
        subscription.setShareId(share.getId());
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscriptionRepository.save(subscription);
        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_SOURCE_REPLACED,
                "已复用共享挂载(收编现有主源):" + StringUtils.defaultIfBlank(resource.getTitle(), subscription.getName()));
        log.info("subscription {} adopted shared mount {} (share {}): {} episodes",
                subscription.getId(), subscription.getMountPath(), share.getId(), files.size());
        return true;
    }

    private void ensureSource(MediaSubscription subscription) {
        fillPool(subscription, true, null);
        if (!activateNextCandidate(subscription)) {
            subscription.setStatus(MediaSubscription.STATUS_ERROR);
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "未找到可用资源,请检查关键词或稍后重试");
        }
    }

    private void onInvalid(MediaSubscription subscription, String reason) {
        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_SOURCE_INVALID, "主源失效:" + StringUtils.defaultString(reason));
        MediaSubscriptionResource primary = primaryResource(subscription);
        if (primary != null) {
            retireResource(subscription, primary, reason, true); // 事件已发,退役不再重复报
        }
        if (!activateNextCandidate(subscription)) {
            fillPool(subscription, true, null);
            if (!activateNextCandidate(subscription)) {
                subscription.setStatus(MediaSubscription.STATUS_ERROR);
                addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "主源失效且无可用候选");
            }
        }
    }

    /** 换源时的主源候选序,分数序之上两层提前:①钉选候选置顶(用户指定压过一切自动判定,
     * 失效换源后钉选行保留,恢复可用即优先回归);②集源行已知含「待看集」(watched+1)的候选提前
     * —— 主源刚失效,用户要续看的正是那一集,已知覆盖的确定性优先于高分候选的未知覆盖
     * (借鉴追更助手 coversExpectedEpisode 信号)。无人钉选且观看进度未知/无人已知覆盖/全部已知覆盖 →
     * 分数序不变;单集链接不受此影响被提前:usableAsPrimary 仍会把它挡在主源外。 */
    List<MediaSubscriptionResource> primaryCandidates(MediaSubscription subscription) {
        List<MediaSubscriptionResource> candidates = candidatesOrdered(subscription);
        if (candidates.size() < 2) {
            return candidates;
        }
        int nextWatch = watchedEpisode(subscription) + 1;
        List<MediaSubscriptionResource> pinned = new ArrayList<>();
        List<MediaSubscriptionResource> covering = new ArrayList<>();
        List<MediaSubscriptionResource> rest = new ArrayList<>();
        for (MediaSubscriptionResource resource : candidates) {
            if (Boolean.TRUE.equals(resource.getPinned())) {
                pinned.add(resource);
            } else if (nextWatch > 1 && coverageOf(resource).contains(nextWatch)) {
                covering.add(resource);
            } else {
                rest.add(resource);
            }
        }
        if (pinned.isEmpty() && (covering.isEmpty() || rest.isEmpty())) {
            return candidates;
        }
        if (!pinned.isEmpty()) {
            log.info("subscription {} pinned candidate tops primary order", subscription.getId());
        } else {
            log.info("subscription {} primary candidates: {} 个已知覆盖待看第{}集,提前于分数序",
                    subscription.getId(), covering.size(), nextWatch);
        }
        List<MediaSubscriptionResource> result = new ArrayList<>(candidates.size());
        result.addAll(pinned);
        result.addAll(covering);
        result.addAll(rest);
        return result;
    }

    /** 按分数依次尝试候选,失败退役换下一个;成功则重挂到同一固定路径。 */
    boolean activateNextCandidate(MediaSubscription subscription) {
        int current = liveEpisodeNumbers(subscription).size();
        Set<String> throttled = new java.util.HashSet<>(); // 本轮已撞风控的盘,后续候选直接跳过
        for (MediaSubscriptionResource resource : primaryCandidates(subscription)) {
            if (usableAsPrimary(resource, current)) {
                String drive = driveOf(resource);
                if (throttled.contains(drive) || isDriveThrottled(drive)) {
                    continue; // 该盘正在限流:再试必然失败,还会加深风控
                }
                try {
                    activate(subscription, resource);
                    clearTransientStreak(resource);
                    return true;
                } catch (Exception e) {
                    log.info("candidate {} invalid: {}", resource.getId(), e.getMessage());
                    if (isThrottleError(e.getMessage())) {
                        // 网盘限流不是资源失效:判 RETIRED 会让好源白白冷却 badCooldownDays 天,
                        // 而且同盘后续候选必然连环触发 —— 本轮跳过该盘,退避后再来。
                        throttled.add(drive);
                        throttleDrive(drive);
                        resource.setCheckedTime(System.currentTimeMillis());
                        resourceRepository.save(resource);
                        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR,
                                driveName(drive) + "限流,本轮跳过该网盘候选(不判失效)");
                        continue;
                    }
                    if (isForeignShowRejection(e.getMessage())) {
                        // 同名异剧(集号超出官方总集数):退役冷却但不拉黑 —— 链接没死,官方集数修正后重探自愈
                        retireAlienCandidate(subscription, resource);
                        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_SOURCE_INVALID,
                                "候选" + FOREIGN_SHOW_MARK + "已跳过:"
                                        + StringUtils.defaultIfBlank(resource.getTitle(), resource.getLink()), false);
                        continue;
                    }
                    if (classifyProbeFailure(e) == ProbeFailure.TRANSIENT && !transientStreakReached(resource)) {
                        continue; // 瞬时故障不下结论(误判失效会进跨订阅黑名单);连续达上限才按失效处理
                    }
                    retireResource(subscription, resource, e.getMessage(), true);
                }
            }
        }
        return false;
    }

    /** 网盘侧限流/风控错误 —— 与"分享失效"性质相反:资源是好的,只是此刻不能试。
     * 百度分享密码验证接口的 errno -62 是最常见的一种(短时间内连敲同一网盘必触发)。 */
    static boolean isThrottleError(String message) {
        return message != null && THROTTLE_ERROR.matcher(message).find();
    }

    /**
     * 入池资格判定:搜索源自带的有效性状态里,只有<b>明确判失效</b>的落 REJECTED(保留行防重复入池);
     * 其余一律 CANDIDATE。盘检只证明<b>链接可达</b>,不证明<b>挂得上</b> —— "已验证可用"
     * 这个结论只属于挂载成功那一刻(届时 state=MOUNTED,可用性由集源行说话)。
     */
    static String admissionState(String state) {
        if (StringUtils.isBlank(state)) {
            return MediaSubscriptionResource.STATE_CANDIDATE;
        }
        return INVALID_STATES.contains(state.trim().toUpperCase(java.util.Locale.ROOT))
                ? MediaSubscriptionResource.STATE_REJECTED
                : MediaSubscriptionResource.STATE_CANDIDATE;
    }

    private static String driveOf(MediaSubscriptionResource resource) {
        return resource.getType() == null ? "" : StringUtils.defaultString(DriveId.toDrive(resource.getType()));
    }

    private boolean isDriveThrottled(String drive) {
        Long until = driveThrottleTime.get(drive);
        return until != null && until > System.currentTimeMillis();
    }

    private void throttleDrive(String drive) {
        long minutes = appProperties.getSubscription().getDriveThrottleCooldownMinutes();
        driveThrottleTime.put(drive, System.currentTimeMillis() + minutes * 60_000L);
        log.info("drive {} throttled, skip for {} minutes", drive, minutes);
    }

    private static String driveName(String drive) {
        return StringUtils.isBlank(drive) ? "网盘" : drive;
    }

    /** 池枯竭时释放退役冷却:给之前被判死的候选一次重探机会(误判自愈)。
     * 本轮刚判死的不释放 —— 刚证明它挂不上就立刻重试毫无意义,还会掩盖真失效。 */
    private void releaseBadCooldown(List<MediaSubscriptionResource> existing) {
        long staleBefore = System.currentTimeMillis() - BAD_RELEASE_MIN_AGE_MS;
        int released = 0;
        for (MediaSubscriptionResource resource : existing) {
            if (!MediaSubscriptionResource.STATE_RETIRED.equals(resource.getState())
                    && !MediaSubscriptionResource.STATE_REJECTED.equals(resource.getState())) {
                continue;
            }
            Long checked = resource.getCheckedTime();
            if (checked != null && checked > staleBefore) {
                continue; // 刚判死的,跳过
            }
            resource.setState(MediaSubscriptionResource.STATE_CANDIDATE);
            resource.setCheckedTime(null);
            resourceRepository.save(resource);
            released++;
        }
        if (released > 0) {
            log.info("pool exhausted, released {} dead candidates for retry", released);
        }
    }

    /** 单集资源(每集一链)不挂主源:主源承载整季清单与固定挂载,换单集会把观测集数打回 1、
     * 触发全量缺集误判;单集链接只配做补缺。本地不足 2 集(新剧首集/电影)时不限制。 */
    boolean usableAsPrimary(MediaSubscriptionResource resource, int currentEpisodes) {
        if (currentEpisodes < 2) {
            return true;
        }
        if (coverageOf(resource).size() == 1) {
            return false;
        }
        return singleEpisodeOf(resource.getTitle()) == null;
    }

    /** activate 中段(已挂上新分享后)抛错前卸掉刚挂分享:固定路径不能残留孤儿挂载。 */
    private void deleteJustMountedShareQuietly(Share share, String reason) {
        try {
            shareService.deleteShare(share.getId());
        } catch (Exception e) {
            log.warn("delete just-mounted share failed ({}): {}", reason, e.getMessage());
        }
    }

    /** 换源核心:删旧挂载 → 同路径挂新分享 → 重列验证并落集源行。mount_path 不变,播放历史不断链。 */
    protected void activate(MediaSubscription subscription, MediaSubscriptionResource resource) {
        String mountPath = subscription.getMountPath();
        Share old = shareRepository.findByPath(mountPath);
        if (old != null) {
            shareService.deleteShare(old.getId());
        }
        ShareLink shareLink = new ShareLink();
        shareLink.setLink(resource.getLink());
        shareLink.setCode(StringUtils.defaultString(resource.getPassword()));
        shareLink.setPath(mountPath);
        shareService.add(shareLink);

        Share share = shareRepository.findByPath(mountPath);
        if (share == null) {
            throw new IllegalStateException("挂载失败:" + resource.getLink());
        }
        TreeMap<Integer, EpisodeFile> files = new TreeMap<>();
        try {
            collectEpisodeFiles(site(), subscription.getSeason(), mountPath, 1, files, episodeSizePolicy(subscription), true, metaYear(subscription));
        } catch (Exception e) {
            // 列目录失败同样要卸刚挂分享:固定路径不能残留孤儿挂载(追剧索引会收录它),
            // 且此时 resource.shareId 还没指向新 share,调用方退役删的是旧 share,孤儿没人清
            deleteJustMountedShareQuietly(share, "list after activate failed");
            throw e instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(e);
        }
        sanitizeEpisodeFiles(subscription, files, resource.getTitle());
        if (files.isEmpty()) {
            deleteJustMountedShareQuietly(share, "no recognizable episode files");
            // 带 FOREIGN_SHOW_MARK:换季后旧季资源挂上即空(季目录/集号全被 season 口径拒收),
            // 链接活着,走异剧分流退役不拉黑;按瞬时故障累积会把活链接烧成跨订阅黑名单
            throw new IllegalStateException(FOREIGN_SHOW_MARK + "(无可识别的本季剧集文件):" + resource.getTitle());
        }
        if (episodeNumbersForeign(subscription, files.keySet(), metaGenres(subscription))) {
            // 同名异剧(真人版集数>动画版官方总集数):卸掉刚挂的分享再抛 —— 固定路径不能残留异剧文件,
            // 否则换源失败兜底时段播放列表列的是异剧目录
            deleteJustMountedShareQuietly(share, "episode-number gate");
            throw new IllegalStateException(foreignShowReason(subscription, files.lastKey(), resource.getTitle()));
        }
        if (episodeDurationForeign(metaRuntimeMinutes(subscription), files.values())) {
            // 单集时长显著不符(真人版 45min vs 动画版 20min):同样卸载再抛,调用方分流退役
            deleteJustMountedShareQuietly(share, "episode-duration gate");
            throw new IllegalStateException(FOREIGN_SHOW_MARK + "(单集时长与官方不符):" + resource.getTitle());
        }

        subscription.setShareId(share.getId());
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        syncInventory(subscription, resource, mountPath, files);

        long now = System.currentTimeMillis();
        Integer supersededShareId = resource.getShareId();
        String supersededMountPath = resource.getMountPath();
        resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId()).forEach(r -> {
            if (r.getId().equals(resource.getId())) {
                r.setState(MediaSubscriptionResource.STATE_MOUNTED);
                r.setMountPath(mountPath);
                r.setShareId(share.getId());
                r.setCheckedTime(now);
            } else if (mountPath.equals(r.getMountPath())) {
                // 被顶替的旧主源:share 已被上面的删除让位,回候选池;行落 MISSING(文件没消失,只是不再从这里供)
                r.setState(MediaSubscriptionResource.STATE_CANDIDATE);
                r.setShareId(null);
                r.setMountPath(null);
                for (MediaSubscriptionEpisodeSource row : episodeSourceRepository.findByResourceId(r.getId())) {
                    if (LIVE_STATES.contains(row.getState())) {
                        row.setState(MediaSubscriptionEpisodeSource.STATE_MISSING);
                        episodeSourceRepository.save(row);
                    }
                }
            }
            resourceRepository.save(r);
        });
        // 转正资源原是补缺挂载:旧 .sources 挂载让位删除 —— 它常驻非 temp(清理豁免),不删就成
        // 无人认领的孤儿 AList 存储,还和新主源同链双挂导致目录重复
        if (supersededShareId != null && !mountPath.equals(supersededMountPath)) {
            try {
                shareService.deleteShare(supersededShareId);
            } catch (Exception e) {
                log.warn("delete superseded aux share failed: {}", e.getMessage());
            }
        }
        applyInventory(subscription, liveEpisodeNumbers(subscription), List.of());
        subscription.setStallCount(0); // applyInventory 的停滞计数在换源场景不适用
        String drive = resource.getType() == null ? "" : DriveId.toDrive(resource.getType());
        addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_SOURCE_REPLACED,
                "已挂载:" + StringUtils.defaultIfBlank(resource.getTitle(), resource.getLink())
                        + "(" + files.size() + "集 · " + drive + ")");
        ensureIndexTemplate();
    }

    /** 索引模板联动:首次成功挂载后确保"追剧"索引模板存在(增量,排除 .sources 内部目录,否则补缺源会造成索引重复条目);已存在的旧模板补上排除项。 */
    private void ensureIndexTemplate() {
        try {
            String root = cn.har01d.alist_tvbox.util.Constants.SUBSCRIPTION_MOUNT_ROOT;
            String excludePath = "-" + GAP_SOURCES_ROOT; // IndexService 约定:paths 中 "-" 前缀 = 排除
            var existing = indexTemplateRepository.findAll().stream()
                    .filter(t -> INDEX_TEMPLATE_NAME.equals(t.getName())).findFirst().orElse(null);
            if (existing != null) {
                IndexRequest request;
                try {
                    request = objectMapper.readValue(existing.getData(), IndexRequest.class);
                } catch (Exception e) {
                    return;
                }
                if (request.getPaths() == null || !request.getPaths().contains(excludePath)) {
                    List<String> paths = new ArrayList<>(request.getPaths() == null
                            ? List.of(root) : request.getPaths().stream().filter(p -> !p.startsWith("-")).toList());
                    paths.add(excludePath);
                    request.setPaths(paths);
                    existing.setData(objectMapper.writeValueAsString(request));
                    indexTemplateRepository.save(existing);
                    log.info("updated subscription index template excludes: {}", GAP_SOURCES_ROOT);
                }
                return;
            }
            IndexRequest request = new IndexRequest();
            request.setSiteId(1);
            request.setIndexName(INDEX_TEMPLATE_NAME);
            request.setIncremental(true);
            request.setMaxDepth(3);
            request.setPaths(new ArrayList<>(List.of(root, excludePath)));
            IndexTemplate template = new IndexTemplate();
            template.setSiteId(1);
            template.setName(INDEX_TEMPLATE_NAME);
            template.setData(objectMapper.writeValueAsString(request));
            template.setScheduled(true);
            template.setScrape(false);
            template.setScheduleTime("10,12,14,16,18,19,20,21,22,23");
            template.setCreatedTime(Instant.now());
            indexTemplateRepository.save(template);
            log.info("created subscription index template: {}", INDEX_TEMPLATE_NAME);
        } catch (Exception e) {
            log.warn("ensure index template failed: {}", e.getMessage());
        }
    }

    // ---------- 集数清单 ----------

    /** 主源挂载目录的 集→文件 映射(集源同步与失效确认共用)。目录不可访问抛异常。 */
    TreeMap<Integer, EpisodeFile> listEpisodeFiles(MediaSubscription subscription) {
        TreeMap<Integer, EpisodeFile> result = new TreeMap<>();
        collectEpisodeFiles(site(), subscription.getSeason(), subscription.getMountPath(), 1, result,
                episodeSizePolicy(subscription), true, metaYear(subscription));
        MediaSubscriptionResource primary = primaryResource(subscription);
        sanitizeEpisodeFiles(subscription, result, primary == null ? null : primary.getTitle());
        return result;
    }

    /** AList 健康探测:列根目录。用于区分"主源失效"与"服务整体不可用"(嵌入式 AList 重启/网络断)。 */
    private boolean isAListHealthy() {
        try {
            aListService.listFiles(site(), "/", 1, 0, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Site site() {
        return siteRepository.findById(1).orElseThrow();
    }

    /** 订阅的单集大小上限(字节):订阅级显式配置优先,否则回退全局 msub_pool_filter;均未配置返回 0 = 不限。 */
    long maxEpisodeBytes(MediaSubscription subscription) {
        MediaSubscriptionFilter filter = parseFilter(subscription);
        Integer maxMb = filter == null ? null : filter.getMaxEpisodeSizeMb();
        if (maxMb == null || maxMb <= 0) {
            maxMb = poolFilterFor(subscription).getMaxEpisodeSizeMb();
        }
        if (maxMb == null || maxMb <= 0) {
            return 0;
        }
        return (long) maxMb * 1024 * 1024;
    }

    /**
     * 订阅的集文件体积三段策略。过滤器里的「单集最小体积」({@code minEpisodeSizeMb})此前后端
     * 从未消费(只有 maxEpisodeSizeMb 接了线),用户手填 200MB 形同虚设 —— 现接成<b>偏好层</b>
     * 而非硬门:配置高于全局底线(默认 20MB,垃圾/样片防护)时,达标文件优先入选与同集择优,
     * 某集只有不达标文件时照收(实在找不到合规资源才忽略限制,线上:柯南主源 1173-1216 仅
     * 130-160MB、1217+ 有 4K 版,硬门会把前段整段丢掉);配置低于全局底线视为用户显式调低
     * 底线,直接覆盖。
     */
    EpisodeSizePolicy episodeSizePolicy(MediaSubscription subscription) {
        MediaSubscriptionPoolFilter global = poolFilterFor(subscription);
        // 全局下限(未配置沿用部署默认 20MB 垃圾/样片防护线)
        long floorMb = global.getMinEpisodeSizeMb() != null && global.getMinEpisodeSizeMb() > 0
                ? global.getMinEpisodeSizeMb() : appProperties.getSubscription().getMinEpisodeSizeMb();
        long floor = floorMb * 1024 * 1024;
        long preferred = 0;
        MediaSubscriptionFilter filter = parseFilter(subscription);
        Integer minMb = filter == null ? null : filter.getMinEpisodeSizeMb();
        if (minMb != null && minMb > 0) {
            long bytes = (long) minMb * 1024 * 1024;
            if (bytes <= floor) {
                floor = bytes;
            } else {
                preferred = bytes;
            }
        }
        return new EpisodeSizePolicy(floor, preferred, maxEpisodeBytes(subscription));
    }

    /** 集文件体积三段策略:floor 硬底线(垃圾/样片防护)/preferred 偏好线(用户配的
     * 最小体积:达标优先、缺额兜底,0=无偏好层)/max 单集上限(0=不限)。 */
    record EpisodeSizePolicy(long floorBytes, long preferredBytes, long maxBytes) {
        boolean hardRejected(long size) {
            return size < floorBytes;
        }

        boolean overMax(long size) {
            return maxBytes > 0 && size > maxBytes;
        }

        boolean preferredHit(long size) {
            return preferredBytes <= 0 || size >= preferredBytes;
        }
    }

    Set<Integer> walkEpisodes(Site site, Integer season, String path, EpisodeSizePolicy policy) {
        TreeSet<Integer> episodes = new TreeSet<>();
        walk(site, season, path, 1, episodes, policy);
        return episodes;
    }

    /** 任意挂载路径的集数清单(转存目录等非本订阅挂载点)。目录不存在/为空返回空集而非抛错。 */
    public Set<Integer> walkEpisodesAt(String path, Integer season, EpisodeSizePolicy policy) {
        try {
            return walkEpisodes(site(), season, path, policy);
        } catch (Exception e) {
            return new TreeSet<>();
        }
    }

    /** 集 → 文件信息(转存增量 copy 需要:目录 + 文件名)。主源 + 补缺挂载全量合并;
     * 已判 FAILED 的(集, 挂载点)组合跳过 —— 那些文件列得出、取不了链/拷不过去,让其他源供给。 */
    TreeMap<Integer, EpisodeFile> walkEpisodeFiles(MediaSubscription subscription, boolean includeAux) {
        Site site = site();
        EpisodeSizePolicy policy = episodeSizePolicy(subscription);
        Map<String, Set<Integer>> failedByMount = new HashMap<>();
        for (MediaSubscriptionResource resource : mountedResources(subscription)) {
            Set<Integer> failed = new TreeSet<>(episodeSourceRepository
                    .findNumbersByResourceIdAndStatesIn(resource.getId(), Set.of(MediaSubscriptionEpisodeSource.STATE_FAILED)));
            if (!failed.isEmpty()) {
                failedByMount.put(resource.getMountPath(), failed);
            }
        }
        TreeMap<Integer, EpisodeFile> result = new TreeMap<>();
        collectEpisodeFiles(site, subscription.getSeason(), subscription.getMountPath(), 1, result, policy, true, metaYear(subscription));
        remapAbsoluteNumbering(subscription, result, null);
        if (includeAux) {
            for (MediaSubscriptionResource resource : auxMounts(subscription)) {
                try {
                    TreeMap<Integer, EpisodeFile> aux = new TreeMap<>();
                    collectEpisodeFiles(site, subscription.getSeason(), resource.getMountPath(), 1, aux, policy, true, metaYear(subscription));
                    remapAbsoluteNumbering(subscription, aux, resource.getTitle());
                    aux.values().forEach(file -> preferPut(result, file, policy));
                } catch (Exception e) {
                    log.warn("walk aux files failed: {} {}", resource.getMountPath(), e.getMessage());
                }
            }
        }
        if (!failedByMount.isEmpty()) {
            result.entrySet().removeIf(entry -> {
                for (var failed : failedByMount.entrySet()) {
                    if (entry.getValue().dir().startsWith(failed.getKey()) && failed.getValue().contains(entry.getKey())) {
                        return true;
                    }
                }
                return false;
            });
        }
        return result;
    }

    /** 任意挂载路径的 集→文件 映射(转存目录/播放解析用);refresh=false 走 AList 列表缓存,轻量。 */
    public TreeMap<Integer, EpisodeFile> episodeFilesAt(String path, MediaSubscription subscription) {
        TreeMap<Integer, EpisodeFile> result = new TreeMap<>();
        try {
            collectEpisodeFiles(site(), subscription.getSeason(), path, 1, result,
                    episodeSizePolicy(subscription), false, metaYear(subscription));
            remapAbsoluteNumbering(subscription, result, null);
        } catch (Exception e) {
            log.debug("episodeFilesAt {} failed: {}", path, e.getMessage());
        }
        return result;
    }

    void collectEpisodeFiles(Site site, Integer season, String path, int depth, TreeMap<Integer, EpisodeFile> result,
                                     EpisodeSizePolicy policy, boolean refresh) {
        collectEpisodeFiles(site, season, path, depth, result, policy, refresh, null);
    }

    void collectEpisodeFiles(Site site, Integer season, String path, int depth, TreeMap<Integer, EpisodeFile> result,
                                     EpisodeSizePolicy policy, boolean refresh, Integer firstAirYear) {
        if (depth > appProperties.getSubscription().getMaxListDepth()) {
            return;
        }
        FsResponse response = aListService.listFiles(site, path, 1, 0, refresh);
        List<FsInfo> files = response.getFiles();
        if (files == null || files.isEmpty()) {
            throw new IllegalStateException("目录为空或不可访问: " + path);
        }
        for (FsInfo file : files) {
            if (file.getType() == 1) {
                continue;
            }
            if (policy.hardRejected(file.getSize()) || !isMediaFormat(file.getName()) || EXTRA.matcher(file.getName()).find()) {
                continue;
            }
            if (policy.overMax(file.getSize())) {
                continue; // 超过单集上限:过滤捆绑大文件/异常资源
            }
            int episode = parseEpisode(file.getName(), season);
            if (episode > 0) {
                preferPut(result, new EpisodeFile(episode, path, file.getName(), file.getSize(), file.getDuration()), policy);
            }
        }
        for (FsInfo file : files) {
            if (file.getType() == 1 && depth < appProperties.getSubscription().getMaxListDepth()
                    && !EXTRA.matcher(file.getName()).find()
                    && !otherSeasonDir(file.getName(), season, firstAirYear)
                    && !spinOffDir(file.getName(), season)) {
                collectEpisodeFiles(site, season, path + "/" + file.getName(), depth + 1, result, policy, refresh, firstAirYear);
            }
        }
    }

    /** @param duration 单集时长(秒,AList FsInfo;盘驱动不给时为 0,时长门禁跳过) */
    public record EpisodeFile(int episode, String dir, String name, long size, long duration) {
    }

    /** 同集多版本两层择优:体积门槛层优先(用户配的最小体积是质量偏好 —— 该集存在
     * 达标文件时不达标版本不得顶上,该集只有不达标文件时照收,"实在找不到才忽略限制"),
     * 同层内再按画质惩罚(HQ.DV/SDR 双压包两个季文件夹):列举顺序未定义,先到先得会
     * 选中 DV 版整屏泛绿;惩罚带目录上下文(标记常在季文件夹名上),兼容性差的版本被后来者替换。 */
    private static void preferPut(TreeMap<Integer, EpisodeFile> result, EpisodeFile file, EpisodeSizePolicy policy) {
        EpisodeFile current = result.get(file.episode());
        boolean candidatePreferred = policy.preferredHit(file.size());
        if (current == null
                || (candidatePreferred && !policy.preferredHit(current.size()))
                || (candidatePreferred == policy.preferredHit(current.size())
                    && TextUtils.picturePenalty(file.dir() + "/" + file.name())
                       < TextUtils.picturePenalty(current.dir() + "/" + current.name()))) {
            result.put(file.episode(), file);
        }
    }

    private void walk(Site site, Integer season, String path, int depth, Set<Integer> episodes, EpisodeSizePolicy policy) {
        if (depth > appProperties.getSubscription().getMaxListDepth()) {
            return;
        }
        FsResponse response = aListService.listFiles(site, path, 1, 0, true);
        List<FsInfo> files = response.getFiles();
        if (files == null || files.isEmpty()) {
            throw new IllegalStateException("目录为空或不可访问: " + path);
        }
        for (FsInfo file : files) {
            if (file.getType() == 1) {
                continue;
            }
            if (policy.hardRejected(file.getSize()) || !isMediaFormat(file.getName()) || EXTRA.matcher(file.getName()).find()) {
                continue;
            }
            if (policy.overMax(file.getSize())) {
                continue; // 超过单集上限:过滤捆绑大文件/异常资源
            }
            int episode = parseEpisode(file.getName(), season);
            if (episode > 0) {
                episodes.add(episode);
            }
        }
        for (FsInfo file : files) {
            if (file.getType() == 1 && depth < appProperties.getSubscription().getMaxListDepth()
                    && !EXTRA.matcher(file.getName()).find()
                    && !otherSeasonDir(file.getName(), season)
                    && !spinOffDir(file.getName(), season)) {
                walk(site, season, path + "/" + file.getName(), depth + 1, episodes, policy);
            }
        }
    }

    /**
     * 子目录名声明了别的季 → 整棵子树跳过。
     * <p>
     * 多季合集很常见(线上案例:第四季分享里带一个 {@code 第1-3季/} 目录,内含 52+26 集)。
     * 那些文件名多半只写"第01集"、不写 SxxEyy,靠 {@link #parseEpisode} 的文件名级季过滤挡不住,
     * 会直接冒充成目标季的集数。目录名是这里唯一可靠的季信号。
     * <p>
     * 只在<b>明确冲突</b>时跳过:目录声明单季且不等于目标季,或声明区间且目标季不在区间内。
     * 无季标记的目录一律进入(常见的"剧名/4K/"这类结构不能误伤)。
     */
    static boolean otherSeasonDir(String name, Integer season) {
        return otherSeasonDir(name, season, null);
    }

    static boolean otherSeasonDir(String name, Integer season, Integer firstAirYear) {
        if (season == null || season <= 0 || StringUtils.isBlank(name)) {
            return false;
        }
        Matcher range = SEASON_RANGE.matcher(name);
        if (range.find()) {
            int from = Integer.parseInt(range.group(1));
            int to = Integer.parseInt(range.group(2));
            return season < Math.min(from, to) || season > Math.max(from, to);
        }
        Integer declared = TextUtils.parseTitleSeason(name);
        if (declared != null) {
            return !declared.equals(season); // 显式季标记优先:声明目标季的目录不进年份门禁
        }
        return firstSeasonYearDir(name, season, firstAirYear);
    }

    /**
     * 衍生篇目目录(番外/前传/外传)对主季订阅整棵跳过:它们自成元数据条目、文件按全剧连续计数
     * (线上:沧元图 S3 订阅把「027-030 4K 东宁府番外篇」「060-066 元初山番外篇」的文件记成本季
     * 第 27-30 集),与正片在标题层无法区分。声明了季标记的目录不跳(「第2季&元初山番外篇」
     * 对 S2 订阅就是正片本体);season&lt;=1 的订阅自身可能就是衍生篇目条目,不跳。
     */
    static boolean spinOffDir(String name, Integer season) {
        if (season == null || season <= 1 || StringUtils.isBlank(name)) {
            return false;
        }
        if (TextUtils.parseTitleSeason(name) != null) {
            return false; // 显式季标记优先:声明目标季的目录即使带番外字样也是正片
        }
        return SPIN_OFF_DIR.matcher(name).find();
    }

    /**
     * 年番全剧连续编号 → 季内编号重映射。国产年番资源普遍按<b>全剧</b>连续集号组织
     * (线上:沧元图 S3 = 全剧 67-87,分享目录「067-更新中 4K 第三季」),而订阅按季内编号
     * (1..officialTotal)对齐 —— 原始集号全部超出官方总集数,会被 {@link #stripForeignEpisodeNoise}
     * 当噪声整段剔除,再新的资源也永远匹配不上。
     * <p>
     * 基准(全剧起点-1)只认两类锚,均要求 season&gt;1、官方总集数/已播数已知:
     * <ul>
     * <li>目录锚:文件路径中声明目标季的目录段自带起始集号文本(T1a,如「067-更新中」),显式声明
     * 最稳;声明季但无区间文本的目录(T1b,如「5）第3季 (2026)」)用段内最小集号起步,但要求
     * 集号连续、全部超出官方总集数、集数与已播数容差内吻合(防分享者删头几集后整体错位)。</li>
     * <li>标题锚:无季目录锚的散文件(T2),要求集号连续、终点与资源标题宣称进度一致
     * (「更至92集」= 92)、块长与已播数容差内吻合 —— 部分残缺分享(松散 85-92)块长远小于
     * 已播数,不满足即不重映射,宁缺毋错位。</li>
     * </ul>
     * 未重映射的文件维持原语义(相对编号/超界剔除)。不同分享的基准可以不同(有的从 67 起有的从
     * 70 起),各自映射回同一套季内编号后天然对齐 —— 所以基准必须按资源各自推断,不能全局统一。
     */
    static void remapAbsoluteNumbering(MediaSubscription subscription, TreeMap<Integer, EpisodeFile> files, String contextTitle) {
        Integer season = subscription.getSeason();
        Integer total = subscription.getOfficialTotal();
        Integer aired = subscription.getOfficialEpisodes();
        if (season == null || season <= 1 || total == null || total <= 0
                || aired == null || aired <= 0 || files.isEmpty()) {
            return;
        }
        int tolerance = registrationLagTolerance(aired);
        Map<Integer, Integer> remapped = new TreeMap<>(); // 原集号 → 季内集号
        Map<String, TreeSet<Integer>> anchoredSegments = new LinkedHashMap<>();
        for (Map.Entry<Integer, EpisodeFile> entry : files.entrySet()) {
            // 取最深一个声明目标季的目录段定锚:挂载路径名本身常带季字样(「/追剧/沧元图-第三季 S03」),
            // 浅段会遮蔽更深的显式区间目录(「067-更新中 4K 第三季」)
            String anchored = null;
            for (String segment : StringUtils.split(entry.getValue().dir(), "/")) {
                if (season.equals(TextUtils.parseTitleSeason(segment))) {
                    anchored = segment;
                }
            }
            if (anchored == null) {
                continue;
            }
            Matcher range = DIR_RANGE_START.matcher(anchored);
            if (range.find() && plausibleEpisodeNumber(Integer.parseInt(range.group(1)))) {
                int base = Integer.parseInt(range.group(1)) - 1; // T1a:「067-更新中」显式起点
                if (base > 0) {
                    remapped.put(entry.getKey(), entry.getKey() - base);
                    continue;
                }
            }
            anchoredSegments.computeIfAbsent(anchored, s -> new TreeSet<>()).add(entry.getKey()); // T1b 候选
        }
        // T1b:声明季但无区间文本的目录段,段内集号连续、全超出总集数、块长与已播容差吻合 → 最小集号起步
        for (TreeSet<Integer> numbers : anchoredSegments.values()) {
            Integer base = contiguousBlockBase(numbers, total);
            if (base != null && Math.abs(aired - numbers.size()) <= tolerance) {
                for (int number : numbers) {
                    remapped.put(number, number - base);
                }
            }
        }
        // T2:无季目录锚、超出总集数的散文件,连续块终点 == 标题宣称进度、块长与已播容差吻合 → 块尾倒推
        Integer claimed = parseTitleProgress(contextTitle);
        if (claimed != null) {
            int start = -1;
            int prev = -2;
            for (Map.Entry<Integer, EpisodeFile> entry : files.entrySet()) {
                int number = entry.getKey();
                if (remapped.containsKey(number) || number <= total || seasonAnchored(entry.getValue().dir(), season)) {
                    start = -1;
                    prev = -2;
                    continue;
                }
                if (number != prev + 1) {
                    start = number;
                }
                prev = number;
                if (number == claimed) {
                    int length = number - start + 1;
                    if (length >= 2 && Math.abs(aired - length) <= tolerance) {
                        for (int raw = start; raw <= number; raw++) {
                            remapped.put(raw, raw - start + 1);
                        }
                    }
                }
            }
        }
        if (remapped.isEmpty()) {
            return;
        }
        int limit = total + tolerance;
        TreeMap<Integer, EpisodeFile> result = new TreeMap<>();
        for (Map.Entry<Integer, Integer> mapping : remapped.entrySet()) {
            int episode = mapping.getValue();
            if (episode >= 1 && episode <= limit) {
                EpisodeFile file = files.get(mapping.getKey());
                result.put(episode, new EpisodeFile(episode, file.dir(), file.name(), file.size(), file.duration()));
            }
        }
        for (Map.Entry<Integer, EpisodeFile> entry : files.entrySet()) {
            if (!remapped.containsKey(entry.getKey())) {
                result.putIfAbsent(entry.getKey(), entry.getValue()); // 锚定文件优先,散件不抢占集位
            }
        }
        files.clear();
        files.putAll(result);
    }

    /** 路径上是否存在声明目标季的目录段(T2 散文件判定用)。 */
    private static boolean seasonAnchored(String dir, Integer season) {
        for (String segment : StringUtils.split(dir, "/")) {
            if (season.equals(TextUtils.parseTitleSeason(segment))) {
                return true;
            }
        }
        return false;
    }

    /** 集号集合是否构成「全部超出总集数的连续块」;成立返回基准(最小集号-1),否则 null。 */
    private static Integer contiguousBlockBase(TreeSet<Integer> numbers, int total) {
        if (numbers.isEmpty() || numbers.first() <= total) {
            return null;
        }
        int prev = -2;
        for (int number : numbers) {
            if (prev >= 0 && number != prev + 1) {
                return null;
            }
            prev = number;
        }
        return numbers.first() - 1;
    }

    /**
     * 首播年份目录门禁:目录名无季标记但标注了<b>剧集首播年份</b>,对 season&gt;1 的订阅整棵子树跳过。
     * <p>
     * 文件名只写裸集号的第 1 季打包资源(线上:末日地堡 S3 订阅挂载根下的
     * {@code M 末日地堡4K英语中英字幕2023/01-10.mp4},10 个文件全部顺着挂载语境冒领 S3 集号,
     * 未播的 S3E10 被假的 S1E10 顶上,观测集数冲到 10/10)靠 {@link #parseEpisode} 的文件级
     * 季过滤和目录季标记都挡不住 —— 目录名里的首播年份是唯一可靠信号。
     * <p>
     * 带<b>合集/全集</b>字样的目录豁免:全系列包常标第一季年代且确实装着当前季(「鬼灭之刃 (2019)
     * 全集」),误跳会丢当前季内容;当前季资源实际都标当前季年份(末日地堡 S3 资源标 2026)。
     */
    static boolean firstSeasonYearDir(String name, Integer season, Integer firstAirYear) {
        if (season == null || season <= 1 || firstAirYear == null || StringUtils.isBlank(name)) {
            return false;
        }
        if (name.contains("合集") || name.contains("全集")) {
            return false;
        }
        Matcher matcher = YEAR_MARK.matcher(name);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) == firstAirYear) {
                return true;
            }
        }
        return false;
    }

    int parseEpisode(String name, Integer season) {
        String base = name;
        int index = base.lastIndexOf('.');
        // 仅当末段是纯字母数字扩展名(mkv/mp4/4k…)才剥离,避免"剧名.更新至20集"这类无扩展名被截断
        if (index > 0 && index < base.length() - 1 && base.substring(index + 1).matches("[a-zA-Z0-9]{1,5}")) {
            base = base.substring(0, index);
        }
        base = stripTechBrackets(base);
        Matcher matcher = SEASON_EPISODE.matcher(base);
        if (matcher.find()) {
            int s = Integer.parseInt(matcher.group(1));
            int ep = Integer.parseInt(matcher.group(2));
            if (season != null && season > 0 && season != s) {
                return -1;
            }
            return ep >= 1 && ep <= 9999 ? ep : -1; // SxxEyy 是显式集标,四位集号直接信(柯南 S01E1173)
        }
        String cleaned = TECH_TAGS.matcher(base).replaceAll(" ");
        cleaned = DATE_STAMP.matcher(cleaned).replaceAll(" "); // 日期戳的月/日会被末号规则当成集号
        int episode = -1;
        Matcher numbers = NUMBER.matcher(cleaned);
        while (numbers.find()) {
            try {
                int value = Integer.parseInt(numbers.group(1));
                if (plausibleEpisodeNumber(value)) {
                    episode = value;
                }
            } catch (NumberFormatException ignored) {
                // 4 位以上已被 \d{1,4} + 范围过滤兜底
            }
        }
        return episode > 0 ? episode : chapterNumber(cleaned);
    }

    /** 末号规则的集号可信域:1-999 直取;1000-9999 只收非年份形态(1900-2099 视为年份弃用)。
     * 长寿动漫实际集号早已过千 —— 线上柯南(官方登记 1212 集)的百度主源 1173-1270 全部纯数字
     * 命名(1173.mp4/1178国语.mp4),999 上限曾让 189 个文件零识别、订阅只剩 1 集;而四位年份
     * (2024/2025)是文件名里唯一常见的四位数噪声,按年份区间排除即可两全。 */
    static boolean plausibleEpisodeNumber(int value) {
        if (value >= 1 && value <= 999) {
            return true;
        }
        return value >= 1000 && value <= 9999 && (value < 1900 || value > 2099);
    }

    /** 播放列表显示标题(fixName 已剥公共前后缀)解析集号:剥掉的是集号前的公共前缀,
     * 集号必在标题最前,取首个 1-999 数字即返回——文件名走 {@link #parseEpisode} 的"末个数字"规则,
     * 但标题里集号后残留的年份/50fps 等未被 TECH_TAGS 覆盖的数字会盖过集号
     * (如 "S01E15.2026.2160p.50fps.WEB-DL.H.265.AAC.mkv" 剥前缀后解析成 50,15-17 集全部丢失)。 */
    int parseEpisodeFromTitle(String title, Integer season) {
        String base = title;
        int index = base.lastIndexOf('.');
        if (index > 0 && index < base.length() - 1 && base.substring(index + 1).matches("[a-zA-Z0-9]{1,5}")) {
            base = base.substring(0, index);
        }
        base = stripTechBrackets(base);
        Matcher matcher = SEASON_EPISODE.matcher(base);
        if (matcher.find()) {
            int s = Integer.parseInt(matcher.group(1));
            int ep = Integer.parseInt(matcher.group(2));
            if (season != null && season > 0 && season != s) {
                return -1;
            }
            return ep >= 1 && ep <= 9999 ? ep : -1;
        }
        String cleaned = TECH_TAGS.matcher(base).replaceAll(" ");
        Matcher numbers = NUMBER.matcher(cleaned);
        while (numbers.find()) {
            try {
                int value = Integer.parseInt(numbers.group(1));
                if (plausibleEpisodeNumber(value)) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 4 位以上已被 \d{1,4} + 范围过滤兜底
            }
        }
        return chapterNumber(cleaned);
    }

    private boolean isMediaFormat(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            String suffix = name.substring(index + 1).toLowerCase();
            return appProperties.getFormats().contains(suffix) || "strm".equals(suffix) || "cas".equals(suffix);
        }
        return false;
    }

    /**
     * 剔除方括号技术标注段(画质/帧率/体积/转码模板 id,如 {@code [322155_maxplus_50fps_tv_6.45GB]})再扫集号:
     * 这类段的数字全是模板 id 与体积,末号规则会把 {@code 6.45GB} 的 45 当集号(线上三集迷你剧各成 45/60/72)。
     * 段内含技术信号才剔;写了显式集号({@code [第05集 1080P]})或纯内容段({@code [01]})保留。
     * fixName 剥公共后缀会把 {@code GB].mkv} 吃掉留下未闭合尾段({@code ...tv_6.72}),同样按信号剔到 '[' 为止。
     */
    static String stripTechBrackets(String name) {
        if (name == null || (name.indexOf('[') < 0 && name.indexOf('【') < 0)) {
            return name;
        }
        StringBuilder result = new StringBuilder();
        int last = 0;
        Matcher matcher = java.util.regex.Pattern.compile("[\\[【]([^\\[\\]【】]*)[\\]】]?").matcher(name);
        while (matcher.find()) {
            result.append(name, last, matcher.start());
            if (!isTechSegment(matcher.group(1))) {
                result.append(matcher.group());
            }
            last = matcher.end();
        }
        result.append(name, last, name.length());
        return result.toString();
    }

    private static boolean isTechSegment(String segment) {
        return segment != null && (TECH_TAGS.matcher(segment).find() || BRACKET_TECH_EXTRA.matcher(segment).find())
                && !BRACKET_EPISODE_MARK.matcher(segment).find();
    }

    /** 无数字集号时按「上/中/下(+集/篇/部)」推定集序:上=1 中=2 下=3;无章节标记返回 -1。 */
    static int chapterNumber(String name) {
        if (name == null) {
            return -1;
        }
        Matcher matcher = CHAPTER_MARK.matcher(name);
        return matcher.find() ? "上中下".indexOf(matcher.group(1)) + 1 : -1;
    }

    /** 对比快照:新集计数、停滞计数/退避;完结条件达标(见 {@link #shouldAutoEnd})自动完结。 */
    private void applyInventory(MediaSubscription subscription, Set<Integer> episodes, List<Integer> added) {
        boolean initial = subscription.getCurrentEpisodes() == null || subscription.getCurrentEpisodes() == 0;
        subscription.setCurrentEpisodes(episodes.size());
        subscription.setMaxEpisode(episodes.stream().max(Integer::compareTo).orElse(null));
        if (!initial && !added.isEmpty()) {
            subscription.setStallCount(0);
            subscription.setUpdatedTime(System.currentTimeMillis());
            // 通知不在这里发:此刻新集只是"目录里列得出",还没验证过能不能取到链。
            // 由 doCheck 在 preheat 之后对通过验证的集调 notifyNewEpisodes。
        } else if (initial && !episodes.isEmpty()) {
            subscription.setUpdatedTime(System.currentTimeMillis());
        } else {
            subscription.setStallCount(subscription.getStallCount() + 1);
        }

        if (shouldAutoEnd(subscription, episodes.size()) && !MediaSubscription.STATUS_ENDED.equals(subscription.getStatus())) {
            subscription.setStatus(MediaSubscription.STATUS_ENDED);
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ENDED, "已完结(共 " + episodes.size() + " 集)");
        }
    }

    /**
     * 自动完结:手填期望达标 / 官方剧级 ENDED 且集齐 / 本季已播完且集齐。
     * 第三条是多季剧专用 —— 剧级 status 恒 RETURNING(还有下一季),本季播完要看季口径
     * (已播 ≥ 总集数且无下集播出时间),否则瑞克和莫蒂这类续订剧的季订阅永远停在 ACTIVE 空巡检。
     */
    static boolean shouldAutoEnd(MediaSubscription subscription, int collected) {
        Integer expected = subscription.getExpectedEpisodes();
        boolean endedByExpected = expected != null && expected > 0 && collected >= expected;
        boolean endedByOfficial = MetadataDetails.STATUS_ENDED.equals(subscription.getOfficialStatus())
                && subscription.getOfficialEpisodes() != null && subscription.getOfficialEpisodes() > 0
                && collected >= subscription.getOfficialEpisodes();
        boolean endedBySeasonAired = subscription.isSeasonAiredOut()
                && collected >= subscription.getOfficialEpisodes();
        return endedByExpected || endedByOfficial || endedBySeasonAired;
    }

    // ---------- 候选池与打分 ----------

    /**
     * 多源聚合搜索(六路并发):TG 聚合(PanSou/TG-Search/网页,内部再并行 —— 追更一律走聚合,
     * 回退链"够用即停"会让配了盘搜的部署永远调不到另外两个源)之上,并入玩偶聚合站源
     * (玩偶/多多/木偶等 11 站,详情页直接提取网盘分享链接)、盘链/观影/蜗牛源(需用户自配
     * 账号/Cookie,未配置时静默关闭)与盘聚源(seedhub 系聚合站,免登录,Cloudflare 被拦时
     * 静默降级)。<b>六路同时发起</b> —— 原先站点源在 TG 全部返回后逐个
     * 串行排队,总时长 = 各源之和(线上 37s 级),并发后 = 最慢一路;各源内部自带超时/退避,
     * 外层 90s 硬顶兜底;任一源失败静默为空,按 link 天然去重,TG 结果在前(先见先得)。
     */
    private List<Message> searchAllSources(String keyword, int size, boolean cached) {
        CompletableFuture<List<Message>> telegram = searchAsync("telegram", keyword, () ->
                appProperties.getSubscription().isAggregateSearch()
                        ? telegramService.searchAggregated(keyword, size, cached)
                        : telegramService.search(keyword, size, false, cached));
        CompletableFuture<List<Message>> wanou = wanouSearchService != null && appProperties.getSubscription().isWanouEnabled()
                ? searchAsync("wanou", keyword, () -> wanouSearchService.search(keyword)) : null;
        CompletableFuture<List<Message>> panlian = panLianSearchService != null
                ? searchAsync("panlian", keyword, () -> panLianSearchService.search(keyword)) : null;
        CompletableFuture<List<Message>> guanying = guanYingSearchService != null
                ? searchAsync("guanying", keyword, () -> guanYingSearchService.search(keyword)) : null;
        CompletableFuture<List<Message>> woniu = woniuSearchService != null
                ? searchAsync("woniu", keyword, () -> woniuSearchService.search(keyword)) : null;
        CompletableFuture<List<Message>> panju = panjuSearchService != null && appProperties.getSubscription().isPanjuEnabled()
                ? searchAsync("panju", keyword, () -> panjuSearchService.search(keyword)) : null;

        List<Message> messages = new ArrayList<>(joinSearch("telegram", telegram));
        Set<String> links = new java.util.HashSet<>();
        for (Message message : messages) {
            links.add(message.getLink());
        }
        if (wanou != null) {
            mergeSource(messages, links, joinSearch("wanou", wanou), "wanou", keyword);
        }
        if (panlian != null) {
            mergeSource(messages, links, joinSearch("panlian", panlian), "panlian", keyword);
        }
        if (guanying != null) {
            mergeSource(messages, links, joinSearch("guanying", guanying), "guanying", keyword);
        }
        if (woniu != null) {
            mergeSource(messages, links, joinSearch("woniu", woniu), "woniu", keyword);
        }
        if (panju != null) {
            mergeSource(messages, links, joinSearch("panju", panju), "panju", keyword);
        }
        return messages;
    }

    /** 单源搜索任务:并发池执行 + 90s 硬顶(各源内部超时之外的兜底),失败静默为空不影响其它源。 */
    private CompletableFuture<List<Message>> searchAsync(String source, String keyword, Supplier<List<Message>> task) {
        return CompletableFuture.supplyAsync(task, searchExecutor)
                .orTimeout(90, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.warn("{} search failed for [{}]: {}", source, keyword, e.getMessage());
                    return List.of();
                });
    }

    private List<Message> joinSearch(String source, CompletableFuture<List<Message>> future) {
        try {
            return future.join();
        } catch (Exception e) { // exceptionally 已兜底,防御 join 意外(CancellationException 一类)
            log.warn("{} search join failed: {}", source, e.getMessage());
            return List.of();
        }
    }

    private void mergeSource(List<Message> messages, Set<String> links, List<Message> extra, String source, String keyword) {
        try {
            for (Message message : extra) {
                if (StringUtils.isNotBlank(message.getLink()) && links.add(message.getLink())) {
                    message.setSourceKind(source); // 站点源标记,供打分加权
                    messages.add(message);
                }
            }
        } catch (Exception e) {
            log.warn("{} search failed for [{}]: {}", source, keyword, e.getMessage());
        }
    }

    /**
     * 填充候选池:多源聚合搜索,按偏好打分取分层配额 TopN。
     *
     * @param keywordOverride 缺集补搜的单集关键词(空 = 默认订阅关键词)
     */
    void fillPool(MediaSubscription subscription, boolean force, String keywordOverride) {
        List<MediaSubscriptionResource> existing = resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId());
        long usable = existing.stream()
                .filter(r -> MediaSubscriptionResource.STATE_CANDIDATE.equals(r.getState()))
                .count();
        if (!force && usable >= 1) {
            return;
        }
        // 池枯竭(无任何可用候选):①搜索条数加倍,②一次性释放退役冷却 ——
        // "全退役"本身就说明之前的判定可能过严(盘检过≠挂得上、风控被当失效),
        // 与其守着一池死判定,不如给它们一次重探机会并把召回面拉宽。
        boolean exhausted = usable == 0;
        if (exhausted && !existing.isEmpty()) {
            releaseBadCooldown(existing);
        }

        String keyword = StringUtils.defaultIfBlank(keywordOverride,
                StringUtils.defaultIfBlank(subscription.getKeyword(), subscription.getName()));
        List<Message> messages;
        try {
            var config = appProperties.getSubscription();
            messages = searchAllSources(keyword,
                    exhausted ? config.getExhaustedSearchSize() : config.getSearchSize(), false);
        } catch (Exception e) {
            log.warn("subscription {} search failed: {}", subscription.getId(), e.getMessage());
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_ERROR, "搜索失败:" + e.getMessage());
            return;
        }

        MediaSubscriptionFilter filter = parseFilter(subscription);
        MediaSubscriptionPoolFilter global = poolFilterFor(subscription);
        List<String> names = matchNames(subscription);
        Integer metaYear = metaYear(subscription);
        List<String> genres = metaGenres(subscription);
        PoolDropAudit audit = new PoolDropAudit();
        Set<String> allowedDrives = allowedCandidateDrives(subscription);
        List<Scored> scored = new ArrayList<>();
        String activeLink = existing.stream()
                .filter(r -> MediaSubscriptionResource.STATE_MOUNTED.equals(r.getState())
                        && subscription.getMountPath() != null && subscription.getMountPath().equals(r.getMountPath()))
                .map(MediaSubscriptionResource::getLink).findFirst().orElse(null);
        for (Message message : messages) {
            if (StringUtils.isBlank(message.getLink()) || !PAN_TYPES.contains(StringUtils.defaultString(message.getType()))) {
                audit.drop(PoolDrop.NON_PAN);
                continue;
            }
            if (!driveAllowed(allowedDrives, driveKeyOf(message))) {
                audit.drop(PoolDrop.OFF_POOL); // 白名单以外的盘:默认只有主网盘的源,扩展盘须显式配置
                continue;
            }
            String title = StringUtils.defaultIfBlank(message.getName(), message.getLink());
            if (matchesKeywords(title, filter == null ? null : filter.getExcludeKeywords())
                    || matchesKeywords(title, global.getExcludeKeywords())) {
                audit.drop(PoolDrop.EXCLUDED, title); // 订阅级与全局排除词并集:命中任一即拒
                continue;
            }
            if (!globallyIncluded(global, title)) {
                audit.drop(PoolDrop.INCLUDE, title); // 全局包含词硬门禁:配置非空时标题须至少含其一
                continue;
            }
            if (!qualityAboveFloor(global, title)) {
                audit.drop(PoolDrop.QUALITY, title); // 标题明确标注低于全局清晰度门槛;未标注放行
                continue;
            }
            if (activeLink != null && activeLink.equals(message.getLink())) {
                continue;
            }
            if (!names.isEmpty() && !matchesTitle(names, title)) {
                audit.drop(PoolDrop.TITLE, title); // 标题与剧名/别名均不沾边,大概率是同名召回噪声,挡在池外省去挂载试错
                continue;
            }
            if (!titleYearMatches(metaYear, names, title)) {
                audit.drop(PoolDrop.YEAR, title); // 标题标注年份与元数据年份全不符,且剧名仅子串嵌入(前缀异剧)
                continue;
            }
            if (titleProgressForeign(subscription, title, genres) || liveActionForeign(genres, title)) {
                audit.drop(PoolDrop.FOREIGN, title); // 宣称集数显著超出官方总集数(真人版全集包)/动画订阅的显式「真人版」资源
                continue;
            }
            Integer titleSeason = parseTitleSeason(title);
            if (subscription.getSeason() != null && subscription.getSeason() > 0
                    && titleSeason != null && !titleSeason.equals(subscription.getSeason())) {
                audit.drop(PoolDrop.SEASON, title); // 标题明确标注其它季(常见同名剧前季资源)
                continue;
            }
            scored.add(score(subscription, message, title, filter));
        }
        scored.sort((a, b) -> Integer.compare(b.score, a.score));

        // 分层配额:主网盘的打分领先是结构性的(主网盘+15、百度免会员+15、盘偏好+20/-10),
        // 全局 top-N 必被主网盘包圆,把 N 调大也一样 —— 排序问题要用配额解,不是用加量解。
        // 每个主网盘保底若干席,其余盘共享一档席位,保证备用盘任何时候都有候选可换。
        PoolQuota quota = new PoolQuota(mainDrives(subscription), appProperties.getSubscription());
        int added = 0;
        Set<Integer> takenEpisodes = new java.util.HashSet<>();
        for (int i = 0; i < scored.size(); i++) {
            if (quota.exhausted()) {
                audit.drop(PoolDrop.TOTAL_QUOTA, scored.size() - i); // 分层配额全部坐满,剩余候选整体截断
                break;
            }
            Scored candidate = scored.get(i);
            String link = candidate.message.getLink();
            if (isDeadLink(link)) {
                audit.drop(PoolDrop.DEAD);
                continue; // 失效黑名单:别的订阅已用取链事实证明它死了,不再入池
            }
            String drive = driveKeyOf(candidate.message);
            if (!quota.take(drive)) {
                audit.drop(PoolDrop.DRIVE_QUOTA);
                continue; // 该盘席位已满:让位给还没有候选的盘
            }
            Integer bareEpisode = singleEpisodeOf(candidate.title);
            if (bareEpisode != null && takenEpisodes.contains(bareEpisode)) {
                quota.giveBack(drive);
                audit.drop(PoolDrop.EPISODE_DUP);
                continue; // 同集单集链接一席:席位留给不同集/整季资源,防 115 每集一链刷满池
            }
            if (resourceRepository.findBySubscriptionIdAndLink(subscription.getId(), link).isPresent()) {
                quota.giveBack(drive);
                audit.drop(PoolDrop.DUPLICATE);
                continue;
            }
            MediaSubscriptionResource resource = new MediaSubscriptionResource();
            resource.setSubscriptionId(subscription.getId());
            resource.setLink(StringUtils.abbreviate(link, 1000)); // 列 VARCHAR(1024),站点/TG 链接无界
            try {
                resource.setType(Integer.parseInt(candidate.message.getType()));
            } catch (NumberFormatException e) {
                resource.setType(null);
            }
            resource.setTitle(StringUtils.abbreviate(candidate.title, 250)); // 列 VARCHAR(255),TG 消息名可超长
            resource.setScore(candidate.score);
            resource.setState(admissionState(candidate.message.getValidityState()));
            resource.setCreatedTime(System.currentTimeMillis());
            resourceRepository.save(resource);
            if (bareEpisode != null) {
                takenEpisodes.add(bareEpisode);
            }
            added++;
        }
        if (added > 0) {
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_POOL_FILLED,
                    "候选池新增 " + added + " 个资源(" + keyword + ")" + audit.suffix());
        } else if (force) {
            addEvent(subscription.getId(), MediaSubscriptionEvent.TYPE_POOL_FILLED,
                    "搜索无新增候选(共 " + messages.size() + " 条结果)" + audit.suffix());
        }
    }

    /**
     * 失效黑名单查询:窗口内({@code deadLinkTtlDays},默认 90 天)的判死记录才拦截入池。
     * 链接失效是双向漂移(死链偶被分享主复活/审核回滚),过期记录保留 fail_count 历史,
     * 该链可重新试错一次 —— 再判死会刷新时间,黑名单从"永久"变"长冷却"。
     */
    private boolean isDeadLink(String link) {
        if (StringUtils.isBlank(link)) {
            return true;
        }
        try {
            return deadLinkRepository.findByLink(link)
                    .map(dead -> System.currentTimeMillis() - dead.getTime()
                            < appProperties.getSubscription().getDeadLinkTtlDays() * 24L * 3600_000)
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 候选消息的盘 key(用于配额分档);类型不可识别时归入"其它盘"档。 */
    private static String driveKeyOf(Message message) {
        try {
            return DriveId.toDrive(Integer.parseInt(StringUtils.defaultString(message.getType())));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 候选池的分层席位:每个主网盘一档独立配额,其余盘共享一档。
     * <p>
     * 纯按分数取 top-N 会让主网盘包圆全部席位(它们有 25~45 分的结构性领先),备用盘永远进不了池,
     * 主网盘一挂就无源可换。配额把"谁更优"和"谁有资格占位"拆开:排序仍按分数,但每档满了就让位。
     * 未配置主网盘时退化为单一全局档位(= 旧的 candidatePoolSize 行为)。
     */
    static final class PoolQuota {
        private final Map<String, Integer> remaining = new LinkedHashMap<>();
        private static final String OTHERS = "";

        PoolQuota(List<String> mainDrives, AppProperties.Subscription config) {
            if (mainDrives == null || mainDrives.isEmpty()) {
                remaining.put(OTHERS, config.getCandidatePoolSize());
                return;
            }
            for (String drive : mainDrives) {
                remaining.put(drive, config.getMainDrivePoolSize());
            }
            remaining.put(OTHERS, config.getOtherDrivePoolSize());
        }

        private String bucket(String drive) {
            return drive != null && remaining.containsKey(drive) ? drive : OTHERS;
        }

        boolean take(String drive) {
            String key = bucket(drive);
            int left = remaining.getOrDefault(key, 0);
            if (left <= 0) {
                return false;
            }
            remaining.put(key, left - 1);
            return true;
        }

        /** 席位已扣但候选最终没入池(重复链接/单集去重)时归还,否则会白白浪费配额。 */
        void giveBack(String drive) {
            String key = bucket(drive);
            remaining.put(key, remaining.getOrDefault(key, 0) + 1);
        }

        boolean exhausted() {
            return remaining.values().stream().allMatch(left -> left <= 0);
        }
    }

    /** 入池落选原因;标题类门禁留样例(缩略标题前 2 条),机械性原因(配额/死链/重复)只计数。 */
    enum PoolDrop {
        NON_PAN("非网盘结果", false),
        OFF_POOL("非白名单盘", false),
        EXCLUDED("命中排除词", true),
        INCLUDE("缺包含词", true),
        QUALITY("清晰度不足", true),
        TITLE("剧名不符", true),
        YEAR("年份不符", true),
        FOREIGN("异剧形态", true),
        SEASON("它季资源", true),
        DEAD("死链", false),
        DRIVE_QUOTA("盘席满", false),
        EPISODE_DUP("同集去重", false),
        DUPLICATE("已在池", false),
        TOTAL_QUOTA("总配额满", false);

        final String label;
        final boolean sampled;

        PoolDrop(String label, boolean sampled) {
            this.label = label;
            this.sampled = sampled;
        }
    }

    /**
     * 入池落选审计:POOL_FILLED 事件此前只报"过滤 N 条不相关结果",配额挤掉/死链/同集去重
     * 这些 drop 是黑盒,线上排障("为什么没选它")只能翻 DEBUG 日志。分原因计数 + 标题门禁
     * 样例直接写进事件详情,不参与任何入池决策。
     */
    static final class PoolDropAudit {
        private final EnumMap<PoolDrop, Integer> counts = new EnumMap<>(PoolDrop.class);
        private final Map<PoolDrop, List<String>> samples = new EnumMap<>(PoolDrop.class);

        void drop(PoolDrop reason) {
            counts.merge(reason, 1, Integer::sum);
        }

        void drop(PoolDrop reason, String title) {
            drop(reason);
            if (reason.sampled) {
                List<String> list = samples.computeIfAbsent(reason, k -> new ArrayList<>());
                if (list.size() < 2) {
                    list.add(StringUtils.abbreviate(title, 40));
                }
            }
        }

        void drop(PoolDrop reason, int count) {
            counts.merge(reason, count, Integer::sum);
        }

        /** 事件详情后缀:";拦截:剧名不符 3(例:xx、yy)、非白名单盘 2";零落选返回空串。 */
        String suffix() {
            if (counts.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder(";拦截:");
            for (PoolDrop reason : PoolDrop.values()) {
                int n = counts.getOrDefault(reason, 0);
                if (n <= 0) {
                    continue;
                }
                if (sb.length() > ";拦截:".length()) {
                    sb.append('、');
                }
                sb.append(reason.label).append(' ').append(n);
                List<String> list = samples.get(reason);
                if (list != null && !list.isEmpty()) {
                    sb.append("(例:").append(String.join("、", list)).append(')');
                }
            }
            return sb.toString();
        }
    }

    /** dry-run 预览(§10.2):按关键词+偏好即时搜索,返回候选与打分明细,不落库。
     * 盘白名单与 fillPool 同规(无订阅上下文,取全局主盘∪扩展盘),预览看到的即能入池的。 */
    public List<Map<String, Object>> preview(String keyword, Integer season, MediaSubscriptionFilter filter) {
        List<Message> messages;
        try {
            messages = searchAllSources(keyword, 50, true);
        } catch (Exception e) {
            return List.of(Map.of("error", StringUtils.defaultString(e.getMessage())));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> names = matchNames(keyword, keyword, null);
        Set<String> allowedDrives = allowedCandidateDrives(null);
        MediaSubscriptionPoolFilter global = globalPoolFilter();
        for (Message message : messages) {
            if (StringUtils.isBlank(message.getLink()) || !PAN_TYPES.contains(StringUtils.defaultString(message.getType()))) {
                continue;
            }
            if (!driveAllowed(allowedDrives, driveKeyOf(message))) {
                continue;
            }
            String title = StringUtils.defaultIfBlank(message.getName(), message.getLink());
            if (matchesKeywords(title, filter == null ? null : filter.getExcludeKeywords())
                    || matchesKeywords(title, global.getExcludeKeywords())) {
                continue;
            }
            if (!globallyIncluded(global, title) || !qualityAboveFloor(global, title)) {
                continue;
            }
            if (!names.isEmpty() && !matchesTitle(names, title)) {
                continue;
            }
            Integer titleSeason = parseTitleSeason(title);
            if (season != null && season > 0 && titleSeason != null && !titleSeason.equals(season)) {
                continue;
            }
            Scored scored = score(null, message, title, filter);
            result.add(Map.of(
                    "title", title,
                    "link", message.getLink(),
                    "drive", DriveId.toDrive(parseIntOr(message.getType(), 0)),
                    "score", scored.score,
                    "reasons", String.join(";", scored.reasons),
                    "validity", StringUtils.defaultIfBlank(message.getValidityState(), "UNKNOWN"),
                    "time", message.getTime() == null ? "" : message.getTime().toString()));
        }
        return result;
    }

    /**
     * 打分权重默认表(排序偏好,全部可通过 filter.weights 覆盖)。硬过滤不走这里。
     * <p>
     * Q14 的结论:候选筛选只有盘类型/关键词/体积是硬过滤,其余维度本质是<b>排序偏好</b> ——
     * 权重调 0 只是不再优先,不会像硬过滤那样把池筛空。key 与前端权重表一一对应。
     */
    static final Map<String, Integer> WEIGHT_DEFAULTS = java.util.Collections.unmodifiableMap(java.util.Map.ofEntries(
            Map.entry("recency.recent", 30),   // 30 天内发布
            Map.entry("recency.quarter", 15),  // 3 个月内
            Map.entry("recency.old", 5),       // 更早
            Map.entry("quality.uhd", 25),      // 4K/2160
            Map.entry("quality.fhd", 15),      // 1080P
            Map.entry("quality.hd", 8),        // 720P
            Map.entry("quality.prefer", 10),   // 命中订阅级「清晰度」关键词(此前后端从未消费,只存不读)
            Map.entry("drive.prefer", 20),     // 盘类型偏好(首位满分,每降一位 -5,下限 5)
            Map.entry("drive.outside", -10),   // 偏好之外的盘(降权不硬过滤)
            Map.entry("account", 8),           // 已配置该盘账号
            Map.entry("account.vip", 15),      // VIP 账号
            Map.entry("drive.main", 15),       // 主网盘候选
            Map.entry("baidu.free", 17),       // 百度分享免会员 15 + 夸克易和谐耐删加成 2(线上「重器」:夸克滚动窗分享说删就删)
            Map.entry("pan115", -10),          // 115 分享追更弱
            Map.entry("pack.complete", -6),    // 完结包不持续更新
            Map.entry("size.fit", 10),         // 单文件体积合理(1GB~2TB)
            Map.entry("keyword.include", 10),  // 命中包含词
            Map.entry("match.title", 15),      // 标题归属本剧
            Map.entry("match.season", 10),     // 季标记匹配
            Map.entry("progress.lead", 8),     // 标题集数领先本地
            Map.entry("progress.lag", -8),     // 标题集数落后本地
            Map.entry("single.episode", -40)   // 单集链接只配补缺
    ));

    /** 读权重:订阅/用户偏好覆盖 > 内置默认。 */
    static int weight(MediaSubscriptionFilter filter, String key) {
        Integer custom = filter == null || filter.getWeights() == null ? null : filter.getWeights().get(key);
        return custom != null ? custom : WEIGHT_DEFAULTS.get(key);
    }

    /** 元数据级打分(挂载前粗排):新近度 + 清晰度 + 盘偏好 + 账号/VIP感知 + 资源形态 + 体积合理 + 包含词。
     * 数值全部来自权重表({@link #weight});站点源加分走全局配置 siteSourceBonus(部署级开关,不按订阅调)。 */
    private Scored score(MediaSubscription subscription, Message message, String title, MediaSubscriptionFilter filter) {
        int result = 0;
        List<String> reasons = new ArrayList<>();
        boolean ongoing = subscription == null || isOngoing(subscription);
        int type = parseIntOr(StringUtils.defaultString(message.getType()), -1);
        Set<Integer> accountTypes = driveAccountTypes();
        Set<Integer> vipTypes = vipDriveTypes(accountTypes);
        if (message.getTime() != null) {
            Duration age = Duration.between(message.getTime(), Instant.now());
            if (age.toDays() <= 30) {
                int w = weight(filter, "recency.recent");
                result += w;
                reasons.add("近期资源+" + w);
            } else if (age.toDays() <= 90) {
                int w = weight(filter, "recency.quarter");
                result += w;
                reasons.add("3个月内+" + w);
            } else {
                int w = weight(filter, "recency.old");
                result += w;
                reasons.add("较旧+" + w);
            }
        }
        String quality = titleQuality(title);
        if (quality != null) {
            int w = weight(filter, "quality." + quality);
            result += w;
            reasons.add((quality.equals("uhd") ? "4K" : quality.equals("fhd") ? "1080P" : "720P") + "+" + w);
        }
        if (filter != null && filter.getQualities() != null) {
            for (String keyword : filter.getQualities()) {
                if (StringUtils.isNotBlank(keyword) && StringUtils.containsIgnoreCase(title, keyword)) {
                    int w = weight(filter, "quality.prefer");
                    result += w;
                    reasons.add("清晰度偏好+" + w);
                    break;
                }
            }
        }
        if (filter != null && filter.getDriveTypes() != null && message.getType() != null) {
            try {
                int index = filter.getDriveTypes().indexOf(Integer.parseInt(message.getType()));
                if (index >= 0) {
                    int bonus = Math.max(weight(filter, "drive.prefer") - index * 5, 5);
                    result += bonus;
                    reasons.add("盘偏好+" + bonus);
                } else {
                    int w = weight(filter, "drive.outside");
                    result += w; // 盘偏好之外的候选降权(不硬过滤,降级可用)
                    reasons.add("偏好外盘" + w);
                }
            } catch (NumberFormatException ignored) {
                // 非数字类型不会进入候选
            }
        }
        if (type >= 0 && accountTypes.contains(type)) {
            int w = weight(filter, "account");
            result += w;
            reasons.add("已配置账号+" + w);
            if (vipTypes.contains(type)) {
                int vip = weight(filter, "account.vip");
                result += vip;
                reasons.add("VIP账号+" + vip);
            }
        }
        if (subscription != null && type >= 0 && mainDrives(subscription).contains(DriveId.toDrive(type))) {
            int w = weight(filter, "drive.main"); // 主网盘候选优先入池(主网盘要维持完整覆盖,池里得先有该盘资源)
            result += w;
            reasons.add("主网盘+" + w);
        }
        if (type == 10 /* 百度,DriveId:分享本身免会员,人人可看 */) {
            int w = weight(filter, "baidu.free");
            result += w;
            reasons.add("百度分享免会员+" + w);
        }
        // 站点源(玩偶/盘链/观影/蜗牛/盘聚)的标题来自结构化卡片/详情页,剧名、季集、清晰度字段规整;
        // TG 频道消息是自由文本,防审查变形、装饰前缀、夹带广告都多,归属匹配与集数解析的误判率更高。
        if (StringUtils.isNotBlank(message.getSourceKind())) {
            result += appProperties.getSubscription().getSiteSourceBonus();
            reasons.add(message.getSourceKind() + "站点源+" + appProperties.getSubscription().getSiteSourceBonus());
        }
        if (ongoing) {
            if (type == 8 /* 115 分享码,见 DriveId */) {
                int w = weight(filter, "pan115");
                result += w;
                reasons.add("115分享追更弱" + w);
            }
            if (COMPLETE_PACK.matcher(title).find()) {
                int w = weight(filter, "pack.complete");
                result += w;
                reasons.add("完结包不更新" + w);
            }
        }
        Long size = message.getSize();
        if (size != null && size > 1024L * 1024 * 1024 && size < 2L * 1024 * 1024 * 1024 * 1024) {
            int w = weight(filter, "size.fit");
            result += w;
            reasons.add("体积合理+" + w);
        }
        if (filter != null && filter.getIncludeKeywords() != null) {
            for (String keyword : filter.getIncludeKeywords()) {
                if (StringUtils.isNotBlank(keyword) && StringUtils.containsIgnoreCase(title, keyword)) {
                    int w = weight(filter, "keyword.include");
                    result += w;
                    reasons.add("包含词+" + w);
                    break;
                }
            }
        }
        if (subscription != null) {
            List<String> names = matchNames(subscription);
            if (!names.isEmpty() && matchesTitle(names, title)) {
                int w = weight(filter, "match.title");
                result += w;
                reasons.add("标题归属+" + w);
            }
            Integer titleSeason = parseTitleSeason(title);
            if (subscription.getSeason() != null && subscription.getSeason() > 0
                    && titleSeason != null && titleSeason.equals(subscription.getSeason())) {
                int w = weight(filter, "match.season");
                result += w;
                reasons.add("季标记匹配+" + w);
            }
            Integer progress = parseTitleProgress(title);
            int current = subscription.getCurrentEpisodes() != null ? subscription.getCurrentEpisodes() : 0;
            if (progress != null && current > 0) {
                if (progress > current) {
                    int w = weight(filter, "progress.lead");
                    result += w;
                    reasons.add("集数领先+" + w);
                } else if (progress < current) {
                    int w = weight(filter, "progress.lag");
                    result += w;
                    reasons.add("集数落后" + w);
                }
            }
            if (current >= 2 && singleEpisodeOf(title) != null) {
                int w = weight(filter, "single.episode"); // 单集链接只配补缺,VIP/4K 加分不能把它抬成主源候选
                result += w;
                reasons.add("单集链接" + w);
            }
        }
        return new Scored(message, title, result, reasons);
    }

    /** 订阅是否仍在追更(未完结且未达期望)。 */
    private boolean isOngoing(MediaSubscription subscription) {
        if (MediaSubscription.STATUS_ENDED.equals(subscription.getStatus())) {
            return false;
        }
        if (cn.har01d.alist_tvbox.dto.MetadataDetails.STATUS_RETURNING.equals(subscription.getOfficialStatus())) {
            return true;
        }
        Integer expected = subscription.getExpectedEpisodes();
        return expected == null || expected <= 0
                || subscription.getCurrentEpisodes() == null || subscription.getCurrentEpisodes() < expected;
    }

    /** 系统已配置的网盘账号类型集合(账号全局,与订阅归属用户无关)。DriverType 枚举 → 分享类型码。 */
    private Set<Integer> driveAccountTypes() {
        Set<Integer> types = new java.util.HashSet<>();
        try {
            driverAccountRepository.findAll().forEach(account -> {
                int code = driveCode(account.getType());
                if (code >= 0) {
                    types.add(code);
                }
            });
        } catch (Exception e) {
            log.debug("load accounts failed: {}", e.getMessage());
        }
        return types;
    }

    /** DriverType → 分享类型码(DriveId);同系账号合并(OPEN115/QUARK_TV 等并入主盘),未知 -1。 */
    static int driveCode(cn.har01d.alist_tvbox.domain.DriverType type) {
        if (type == null) {
            return -1;
        }
        return switch (type) {
            case QUARK, QUARK_TV -> 5;
            case UC, UC_TV -> 7;
            case PAN115, OPEN115 -> 8;
            case PAN123, OPEN123 -> 3;
            case PAN139 -> 6;
            case CLOUD189 -> 9;
            case THUNDER -> 2;
            case BAIDU -> 10;
            case ALI -> 0;
            case GUANGYA -> 12;
            default -> -1;
        };
    }

    /** VIP 账号类型集合(Setting msub_vip_accounts 勾选的账号 id CSV)。 */
    private Set<Integer> vipDriveTypes(Set<Integer> accountTypes) {
        Set<Integer> vip = new java.util.HashSet<>();
        if (accountTypes.isEmpty()) {
            return vip;
        }
        try {
            String csv = settingRepository.findById("msub_vip_accounts").map(s -> s.getValue()).orElse("");
            Set<Integer> vipIds = new java.util.HashSet<>();
            for (String id : csv.split(",")) {
                if (StringUtils.isNotBlank(id)) {
                    vipIds.add(Integer.parseInt(id.trim()));
                }
            }
            if (vipIds.isEmpty()) {
                return vip;
            }
            driverAccountRepository.findAll().forEach(account -> {
                if (vipIds.contains(account.getId())) {
                    int code = driveCode(account.getType());
                    if (code >= 0) {
                        vip.add(code);
                    }
                }
            });
        } catch (Exception e) {
            log.debug("load vip accounts failed: {}", e.getMessage());
        }
        return vip;
    }

    private static boolean matchesKeywords(String title, List<String> keywords) {
        if (keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.isNotBlank(keyword) && StringUtils.containsIgnoreCase(title, keyword)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 标题归属匹配(§4.7 候选过滤) ----------

    /**
     * 候选标题归属匹配用的名称清单:剧名、搜索词、**裸剧名**(剥掉季号后缀)、元数据别名。
     * <p>
     * 裸剧名是关键:订阅名常带季号(片单条目名原样带入,如"诛仙 第四季"),而资源标题的季号写法
     * 五花八门(第四季/第4季/S04/4/不写)。拿带季号的全名做包含匹配等于要求写法逐字相同,
     * 会把绝大多数候选误判为不相关(线上曾出现 31 条召回全灭)。季号判定交给 parseTitleSeason 那一关,
     * 此处只管"是不是这部剧"。
     */
    static List<String> matchNames(String name, String keyword, String aliases) {
        List<String> names = new ArrayList<>();
        // 剧名/关键词的单字中文(如"蝉")必须纳入:否则只剩英文别名时,中文资源标题全被误判剧名不符
        addName(names, name, true);
        addName(names, keyword, true);
        // 裸剧名:剥掉"第N季/SN/Season N"后缀,让不同季号写法的候选都能命中
        addName(names, TextUtils.stripSeasonSuffix(name), true);
        addName(names, TextUtils.stripSeasonSuffix(keyword), true);
        if (StringUtils.isNotBlank(aliases)) {
            for (String alias : aliases.split("\\n")) {
                addName(names, alias, false);
            }
        }
        return names;
    }

    /**
     * 纳入匹配名单:去空白、去重;纯季号词(如"第四季")会命中任意同季别剧,必须排除。
     * 长度门槛 ≥2,仅正主剧名(allowSingleCjk)豁免单字中文;别名单字(如"短")子串误命中面大,维持门槛。
     */
    private static void addName(List<String> names, String value, boolean allowSingleCjk) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        String trimmed = value.trim();
        if ((trimmed.length() < 2 && !(allowSingleCjk && TextUtils.isChinese(trimmed)))
                || names.contains(trimmed) || TextUtils.isBareSeasonMarker(trimmed)) {
            return;
        }
        names.add(trimmed);
    }

    List<String> matchNames(MediaSubscription subscription) {
        return matchNames(subscription.getName(), subscription.getKeyword(), subscription.getAliases());
    }

    /** 归一化:小写、剥技术标签、非字母数字/汉字转空格、汉字间空格塌缩 —— 抵消 TG 标题的 .【】·等防审查写法。 */
    static String normalizeForMatch(String text) {
        if (text == null) {
            return "";
        }
        String s = TECH_TAGS.matcher(text.toLowerCase()).replaceAll(" ");
        s = s.replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", " ");
        s = TextUtils.collapseCjkSpaces(s);
        return s.trim().replaceAll("\\s+", " ");
    }

    /**
     * 标题归属匹配:候选资源标题是否属于本剧。归一化包含(剧名/搜索词/别名任一)为主;
     * 全部未命中时对中文名做编辑距离滑窗兜底(防审查变形字,如"蒼蘭訣"对"苍兰诀"差 2 字)。
     */
    static boolean matchesTitle(List<String> names, String title) {
        if (names == null || names.isEmpty()) {
            return true; // 无可用名称时不拦截,保持纯搜索召回
        }
        String normalized = normalizeForMatch(title);
        if (normalized.isBlank()) {
            return false;
        }
        for (String name : names) {
            String n = normalizeForMatch(name);
            if (n.isBlank()) {
                continue; // 纯假名/西里尔/阿拉伯文等别名归一化后为空:contains("") 恒真、isChinese("") 真空真,
                // 会放行一切标题把门禁整个打穿(线上:航海王别名 ワンピース/ون بيس 让「短剧更新目录」入池成主源)
            }
            if ((n.length() >= 2 || TextUtils.isChinese(n)) && normalized.contains(n)) {
                return true;
            }
        }
        return fuzzyChineseMatch(names, normalized);
    }

    /** 中文变形兜底:标题紧凑串中存在与某中文名编辑距离 ≤ max(1, len/4) 的滑窗即命中。 */
    private static boolean fuzzyChineseMatch(List<String> names, String normalizedTitle) {
        String compactTitle = normalizedTitle.replace(" ", "");
        for (String name : names) {
            String n = normalizeForMatch(name).replace(" ", "");
            if (!TextUtils.isChinese(n) || n.length() < 3 || n.length() > 20) {
                continue;
            }
            int tolerance = Math.max(1, n.length() / 4);
            for (int len = n.length() - tolerance; len <= n.length() + tolerance; len++) {
                for (int start = 0; start + len <= compactTitle.length(); start++) {
                    if (TextUtils.minDistance(n, compactTitle.substring(start, start + len)) <= tolerance) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 标题级季标记解析:返回标题明确标注的季号;无标记或多个不同季号(跨季合集)返回 null 不参与判定。 */
    static Integer parseTitleSeason(String title) {
        return TextUtils.parseTitleSeason(title);
    }

    /** 标题宣称的集数进度(TITLE_PROGRESS 各形态的最大值);无信息返回 null。 */
    static Integer parseTitleProgress(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        int max = -1;
        Matcher matcher = TITLE_PROGRESS.matcher(title);
        while (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                String group = matcher.group(i);
                if (group != null) {
                    max = Math.max(max, Integer.parseInt(group));
                }
            }
        }
        return max > 0 ? max : null;
    }

    /** 标题为"单集链接"(每集一链的分享,115 常见)时返回其集号,否则 null:
     * 进度信号仅来自裸 第N集/EPn/SxxEyy 标记(组5/6);含 更新至N/全N集/第A-B集 等整季形态(组1/2/3)按整季资源论。 */
    static Integer singleEpisodeOf(String title) {
        if (StringUtils.isBlank(title)) {
            return null;
        }
        Integer episode = null;
        Matcher matcher = TITLE_PROGRESS.matcher(title);
        while (matcher.find()) {
            if (matcher.group(5) == null && matcher.group(6) == null) {
                return null; // 整季形态,非单集链接
            }
            episode = Integer.valueOf(matcher.group(5) != null ? matcher.group(5) : matcher.group(6));
        }
        return episode;
    }

    // ---------- 调度 ----------

    void scheduleNext(MediaSubscription subscription) {
        Long air = subscription.getNextAirTime();
        long now = System.currentTimeMillis();
        Long recentAir = recentAiredTime(subscription, now);
        if (recentAir != null && behindAiredEpisodes(subscription, airedTarget(subscription, now))) {
            // 播后短轮(缺口驱动):已播集(官方口径含 schedule 已到时刻)仍有缺口时,窗口内每小时一查,
            // 资源常在播后 1~12h 上线;首查(播出+15min 槽位或跨播出时刻的检查)+30min 快速重试一次
            // (线上诉求:20:00 播、20:15 首查,未命中 20:45 再试一次)。
            // currentEpisodes 是本轮巡检尾部 applyInventory/补缺刷新后的快照 —— 追平即收工,
            // 落回下一集播出触发/常规间隔,不空转整窗
            boolean firstLook = subscription.getLastCheckTime() != null
                    && subscription.getLastCheckTime() < recentAir + 30 * 60_000L;
            subscription.setNextCheckTime(firstLook ? now + 30 * 60_000L : now + 3600_000L);
            return;
        }
        int hours = subscription.getCheckIntervalHours() != null && subscription.getCheckIntervalHours() > 0
                ? subscription.getCheckIntervalHours() : appProperties.getSubscription().getCheckIntervalHours();
        // 无更新退避 ×1.5/轮;追更中(官方 RETURNING)封顶收紧(重列主源零成本,不该隔一天才发现),
        // 完结/无元数据维持 24h
        int cap = MetadataDetails.STATUS_RETURNING.equals(subscription.getOfficialStatus())
                ? appProperties.getSubscription().getReturningBackoffCapHours() : 24;
        double factor = Math.min(Math.pow(1.5, Math.min(subscription.getStallCount(), 6)), 4);
        long interval = (long) (Math.min(hours * factor, cap) * 3600_000L);
        if (air != null && air > now) {
            if (!behindAiredEpisodes(subscription, airedTarget(subscription, now))) {
                // 播出前休眠:播出时刻 +15min 起查(上限 24h,防日程异常导致长眠漏检)
                subscription.setNextCheckTime(Math.min(air + 15 * 60_000L, now + 24 * 3600_000L));
                return;
            }
            // 已播集仍有缺口(线上:换到只留尾部几集的分享,缺45集却睡到播出前):缺口与播出
            // 日程无关,不让位长眠 —— 落到常规间隔让补缺尽早跑;但常规间隔可能睡穿播出时刻
            // (线上:11:00 开播、12h 间隔排到 20:25,新集发现晚 9h),取两者较早
            subscription.setNextCheckTime(Math.min(now + interval, air + 15 * 60_000L));
            return;
        }
        // 无日程/日程已过尽:常规间隔与高峰档位兜底取早 —— 高峰时段后自动加一轮检查,
        // 检查零成本(重列主源+缺集判定),无日程订阅的新集发现不再纯等固定周期
        long slot = nextPrimeCheckTime(now);
        subscription.setNextCheckTime(slot > 0 ? Math.min(now + interval, slot) : now + interval);
    }

    /** 下一个高峰检查档位(epoch ms):primeCheckTimes 里最近的未来时刻,只认 ≥now+1h 的档
     *  (避开刚查完的冗余轮),今天无可用档取明天首个;未配置返回 0。 */
    private long nextPrimeCheckTime(long now) {
        List<String> times = appProperties.getSubscription().getPrimeCheckTimes();
        if (times == null || times.isEmpty()) {
            return 0;
        }
        ZoneId zone = ZoneId.of(Constants.ZONE_ID);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        for (int day = 0; day <= 1; day++) {
            long best = 0;
            for (String candidate : times) {
                LocalTime time;
                try {
                    time = LocalTime.parse(candidate.trim());
                } catch (DateTimeParseException | NullPointerException e) {
                    continue;
                }
                long moment = today.plusDays(day).atTime(time).atZone(zone).toInstant().toEpochMilli();
                if (moment >= now + 3600_000L && (best == 0 || moment < best)) {
                    best = moment;
                }
            }
            if (best > 0) {
                return best;
            }
        }
        return 0;
    }

    /** 播后短轮锚点:最近一个已到时刻的播出(= 刚播的这集)。优先 schedule 快照 —— refreshMetadata
     *  一旦执行 nextAirTime 即前移到下一集(严格取未播集),快照(昨日 00:00 起的窗口)仍保留刚播条目,
     *  短轮不依赖 refresh 节流恰好没跑的巧合;快照缺失时退回节流未刷新的 stale nextAirTime。
     *  超出短轮窗口(播后 shortPollWindowHours 小时)返回 null,让位播出前休眠/常规退避。 */
    private Long recentAiredTime(MediaSubscription subscription, long now) {
        long window = appProperties.getSubscription().getShortPollWindowHours() * 3600_000L;
        long latest = 0;
        for (EpisodeAirDate entry : scheduleOf(subscription).values()) {
            if (entry.getAirTime() <= now && entry.getAirTime() > latest) {
                latest = entry.getAirTime();
            }
        }
        Long air = subscription.getNextAirTime();
        if (air != null && air > 0 && air <= now && air > latest) {
            latest = air;
        }
        return latest > 0 && now < latest + window ? latest : null;
    }

    /** 当前官方已播集数(搜索与调度共用的目标集数):officialEpisodes(上次刷新口径)与
     *  schedule 快照里播出时刻已到的最大集号(直播径,refresh 节流下不滞后)取大,
     *  再被官方总集数夹住(与 computeMissing 的瑞克 S1 桥接污染夹紧同口径)。 */
    int airedTarget(MediaSubscription subscription, long now) {
        int target = Math.max(
                subscription.getOfficialEpisodes() == null ? 0 : subscription.getOfficialEpisodes(),
                airedBySchedule(subscription, now));
        Integer total = subscription.getOfficialTotal();
        return total != null && total > 0 ? Math.min(target, total) : target;
    }

    /** schedule 快照(昨日 00:00 起的窗口)里播出时刻已到的最大集号;episode=0(集数未知)不计。 */
    private int airedBySchedule(MediaSubscription subscription, long now) {
        int max = 0;
        for (EpisodeAirDate entry : scheduleOf(subscription).values()) {
            if (entry.getEpisode() > max && entry.getAirTime() <= now) {
                max = entry.getEpisode();
            }
        }
        return max;
    }

    /** 已播集数(目标口径 {@link #airedTarget})仍落后于本地集数快照:缺已播集(新集或老集)。
     *  官方无数据/本地未知不判缺(维持休眠)。 */
    static boolean behindAiredEpisodes(MediaSubscription subscription, int target) {
        Integer current = subscription.getCurrentEpisodes();
        return target > 0 && current != null && target > current;
    }

    // ---------- 工具 ----------

    MediaSubscriptionFilter parseFilter(MediaSubscription subscription) {
        if (StringUtils.isBlank(subscription.getFilterConfig())) {
            return null;
        }
        try {
            return objectMapper.readValue(subscription.getFilterConfig(), MediaSubscriptionFilter.class);
        } catch (Exception e) {
            return null;
        }
    }

    List<Integer> parseEpisodeList(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Integer>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Set<Integer> intersection(Set<Integer> a, Set<Integer> b) {
        Set<Integer> result = new TreeSet<>(a);
        result.retainAll(b);
        return result;
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 集号列表文案:连续段(≥3)压成区间 —— 千集动漫"第1,2,3,…,1000 集"的动态长到没法看,压成"第1-1000 集"。 */
    static String joinNumbers(List<Integer> numbers) {
        List<Integer> sorted = numbers.stream().distinct().sorted().toList();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < sorted.size()) {
            int start = i;
            while (i + 1 < sorted.size() && sorted.get(i + 1) == sorted.get(i) + 1) {
                i++;
            }
            if (i - start >= 2) {
                if (!sb.isEmpty()) {
                    sb.append(",");
                }
                sb.append(sorted.get(start)).append("-").append(sorted.get(i));
            } else {
                for (int j = start; j <= i; j++) {
                    if (!sb.isEmpty()) {
                        sb.append(",");
                    }
                    sb.append(sorted.get(j));
                }
            }
            i++;
        }
        return sb.toString();
    }

    private boolean tryLock(Integer id) {
        return inFlight.add(id);
    }

    void addEvent(int subscriptionId, String type, String detail) {
        addEvent(subscriptionId, type, detail, true);
    }

    /** @param push false = 只落事件流(页面时间线可见),不外发通知 —— 用于"用户还没追平"的新集。 */
    void addEvent(int subscriptionId, String type, String detail, boolean push) {
        try {
            MediaSubscriptionEvent event = new MediaSubscriptionEvent();
            event.setSubscriptionId(subscriptionId);
            event.setType(type);
            event.setDetail(detail);
            event.setCreatedTime(System.currentTimeMillis());
            eventRepository.save(event);
            log.info("media subscription {} event: {} {}", subscriptionId, type, detail);
            if (push && notificationService != null) {
                notificationService.onEvent(subscriptionId, type);
            }
        } catch (Exception e) {
            log.warn("add event failed: {}", e.getMessage());
        }
    }

    private record Scored(Message message, String title, int score, List<String> reasons) {
    }
}
