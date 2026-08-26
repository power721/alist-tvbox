package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.dto.DanmakuConfig;
import cn.har01d.alist_tvbox.tvbox.Site;
import cn.har01d.alist_tvbox.util.Constants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@ConfigurationProperties("app")
public class AppProperties {
    private boolean hostmode;
    private boolean supportDash;
    private boolean heartbeat;
    private boolean sort;
    private boolean merge;
    private boolean mix;
    private boolean replaceAliToken;
    private boolean searchable;
    private boolean enableHttps;
    private boolean cleanInvalidShares;
    private boolean enabledToken;
    // 直播平台首页热门展示方式:mix=热门直播间混排在分类文件夹前;folder=入口为"热门直播间"文件夹;none=仅分类文件夹
    private String liveHotMode = "folder";
    private boolean playbackSyncEnabled = false;
    // 同步分区粒度:uid(不分桶)/ token(按 vod token)/ subscription(按 vod token/id)
    private String playbackSyncScope = "token";
    // 同一身份 item 删除的生效限频(ms):异常/旧版客户端会把同一条删除每分钟重发,
    // 每次都携带新的 deletedAt,追杀其他端刚复活的记录;窗口内的重复删除按回声丢弃。0=关闭。
    private long playbackDeleteThrottleMs = 600_000;
    private int pageSize = 100;
    private int maxSearchResult = 60;
    private String secretKey;
    private List<String> qns = List.of();
    private List<String> tgDrivers = Arrays.asList(Constants.TG_DRIVERS.split(","));
    private List<String> tgDriverOrder = Arrays.asList(Constants.TG_DRIVERS.split(","));
    private String userAgent = Constants.USER_AGENT;
    private String tgSearch;
    private String tgSearchApiKey;
    private String tgSortField = "time";
    private boolean tgLogin;
    private String panCheckUrl;
    private Integer panCheckTimeoutMs;
    private String panSouUrl;
    private String panSouSource = "all";
    private String panSouChannels = "custom";
    private String panSouUsername;
    private String panSouPassword;
    private Boolean panSouAuthEnabled;
    private List<String> panSouPlugins;
    private boolean panSouLinkCheckEnabled;
    private int panSouLinkCheckMaxCount = 300;
    // 可检链接总数超过 maxCount 时不再整体跳过盘检,改为每种盘类型各取排序在前的前 N 条送检(总量仍受 maxCount 约束)
    private int panSouLinkCheckMaxPerTypeCount = 100;
    private List<String> panSouLinkCheckTypes;
    private Integer panSouConc;
    private Boolean panSouRefresh = false;
    private String panSouRes = "merge";
    private List<String> panSouFilterInclude;
    private List<String> panSouFilterExclude;
    private String systemId;
    private int tgTimeout = 5000;
    private int tempShareExpiration = 72;
    private int validateSharesInterval = 4;
    // 追剧订阅(自动追更)配置,详见 docs/media-subscription-design.md
    private Subscription subscription = new Subscription();
    private Set<String> formats;
    private Set<String> subtitles;
    private List<Site> sites;
    private List<String> excludedPaths;
    private Map<String, Map<String, Object>> localProxyConfig = defaultLocalProxyConfig();
    // 直播弹幕渲染配置,由 SettingService 从 Setting 表 danmaku_config 加载热缓存
    private DanmakuConfig danmakuConfig = new DanmakuConfig();

    public static Map<String, Map<String, Object>> defaultLocalProxyConfig() {
        Map<String, Map<String, Object>> map = new HashMap<>();
        map.put("ALI", localProxyItem(true, 20, 1024));
        map.put("QUARK", localProxyItem(true, 20, 1024));
        map.put("UC", localProxyItem(true, 10, 256));
        map.put("PAN115", localProxyItem(true, 2, 1024));
        map.put("PAN123", localProxyItem(true, 4, 256));
        map.put("PAN139", localProxyItem(true, 4, 256));
        map.put("BAIDU", localProxyItem(true, 5, 2048));
        map.put("GUANGYA", localProxyItem(true, 10, 256));
        return map;
    }

    @Data
    public static class Subscription {
        private boolean enabled = true;
        private int checkIntervalHours = 6;
        /** 每轮 sweep 取的到期订阅上限(按 nextCheckTime 升序,ACTIVE/ENDED 各取 N):100+ 订阅规模的吞吐上限,
         *  提高不怕积压 —— 到期早的天然优先,池满排队下小时继续,tryLock 防重复执行 */
        private int maxChecksPerRound = 30;
        private int candidatePoolSize = 5;
        /** 分层配额:每个主网盘保底席位(主网盘打分领先是结构性的,不给保底则备用盘永远进不了池) */
        private int mainDrivePoolSize = 3;
        /** 分层配额:非主网盘共享的席位数,保证主网盘全挂时仍有别盘可换 */
        private int otherDrivePoolSize = 3;
        /** 常规搜索取回的结果条数 */
        private int searchSize = 50;
        /** 追更搜索聚合模式:盘搜/TG-Search/电报网页同时跑并合并(而非回退链"够用即停") */
        private boolean aggregateSearch = true;
        /** 站点源(玩偶/盘链/观影/蜗牛)加分:标题结构化,归属匹配与集数解析比 TG 自由文本可靠 */
        private int siteSourceBonus = 12;
        /** 池枯竭(无任何可用候选)时的搜索条数:把召回面拉宽,别守着一池死判定 */
        private int exhaustedSearchSize = 150;
        /** 网盘限流后的退避(分钟):期内不再试挂该盘候选 */
        private int driveThrottleCooldownMinutes = 15;
        private int stallRoundsBeforeSearch = 3;
        private int minEpisodeSizeMb = 20;
        private int maxListDepth = 3;
        private int metaRefreshIntervalHours = 24;
        /** 在播剧(RETURNING)元数据短轮刷新间隔:官方日程/集数变化快,不等 metaRefreshIntervalHours 长轮;也是 media_metadata 表在播行的过期 TTL */
        private int airingRefreshHours = 6;
        /** 缺集补搜:每轮巡检最多临时挂载探测的候选数 */
        private int maxGapProbesPerRound = 3;
        /** 缺集补缺:每个订阅最多同时保留的补缺/线路挂载数(-补N) */
        private int maxGapMounts = 6;
        /** 分盘线路:候选池里每个网盘自动探测挂载出备用线路(用户按盘手动切换的逃生舱),无需配置主网盘 */
        private boolean driveLinesEnabled = true;
        /** 分盘线路:单个网盘最多保留的线路挂载数(整季源 1 个即满覆盖;115 每集一链逐集挂) */
        private int driveLineMountsPerDrive = 3;
        /** 自动转存:每日转存任务数上限(防配额/风控) */
        private int maxTransfersPerDay = 20;
        /** 自动转存:单轮转存等待 AList copy 任务完成的超时(分钟) */
        private int transferTimeoutMinutes = 30;
        /** 新集播放预热验证:发现新集时做链接解析探测,失败判损坏(被和谐)登记补源 */
        private boolean preheatEnabled = true;
        /** 新集播放预热验证:每轮最多探测的集数 */
        private int preheatMaxPerRound = 5;
        /** 播放后前瞻验证:播放某集成功后,后台顺带探测后续 N 集的最优源(用户即将看,提前发现死集) */
        private int preheatAheadEpisodes = 3;
        /** 播放后前瞻验证:同一订阅两次探测的最小间隔(小时),连播时不重复打探测 */
        private int preheatAheadIntervalHours = 1;
        /** 播出后短轮窗口(小时):窗口内每小时一查(网盘资源常在播出后 1~12h 才上线) */
        private int shortPollWindowHours = 12;
        /** 追更中(官方状态 RETURNING)无新集退避封顶(小时);完结/无元数据维持 24h */
        private int returningBackoffCapHours = 12;
        /** BAD 候选冷却(天):超期允许重探一次(误标自愈),再失败重新计时 */
        private int badCooldownDays = 7;
        /** 字节级流探测:对直链 Range 请求的字节上限(解析成功后再拉一小段,验证 CDN 真出流) */
        private int streamProbeMaxBytes = 4096;
        /** 字节级流探测:HTTP 超时(秒) */
        private int streamProbeTimeoutSeconds = 8;
        /** 连续瞬时故障次数上限:达到后按失效处理(未识别错误默认按瞬时,防真死源无限白吃探测预算) */
        private int probeTransientStreak = 3;
        /** 失效黑名单窗口(天):窗口外的判死记录不再拦截入池,该链可重新试错一次(再判死刷新时间) */
        private int deadLinkTtlDays = 90;
        /** 玩偶聚合搜索源(玩偶/多多/木偶等 11 站聚合,搜索+详情页提取分享链接):追剧候选池补充来源 */
        private boolean wanouEnabled = true;
        /** 玩偶聚合:站点最新域名监控接口(空=只用内置静态域名) */
        private String wanouMonitorUrl = "https://pan-site-monitor.douer.me/api/data";
        /** 玩偶聚合:单站每次搜索最多抓取的详情页数 */
        private int wanouMaxDetailPages = 3;
        /** 玩偶聚合:整源搜索总超时(秒) */
        private int wanouTimeoutSeconds = 45;
        /** 订阅巡检并发度:到期订阅并发检查,多个订阅的搜索不再互相排队(源侧压力不放大,见 searchExecutor) */
        private int checkConcurrency = 4;
    }

    public static Map<String, Map<String, Object>> copyLocalProxyConfig(Map<String, Map<String, Object>> source) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, Map<String, Object>> entry : source.entrySet()) {
            result.put(entry.getKey(), entry.getValue() == null ? new HashMap<>() : new HashMap<>(entry.getValue()));
        }
        return result;
    }

    private static Map<String, Object> localProxyItem(boolean enabled, int concurrency, int chunkSize) {
        Map<String, Object> item = new HashMap<>();
        item.put("enabled", enabled);
        item.put("concurrency", concurrency);
        item.put("chunk_size", chunkSize);
        return item;
    }
}
