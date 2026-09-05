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
        /** 无播出日程订阅的高峰时段兜底检查点("HH:mm" 列表,北京时间,含 15min 上线缓冲):
         *  完全无日程的订阅除常规间隔外,取 min(常规间隔, 下一档位) 排程 —— 国产平台午间档 12:00 /
         *  黄金档 19:00-20:00 双高峰,国漫晨间簇 11:00(4567 实例 15 个有日程订阅实测分布)。 */
        private java.util.List<String> primeCheckTimes = java.util.List.of("11:15", "12:15", "19:15", "20:15");
        /** 完结剧(ENDED)凌晨巡检档位("HH:mm" 列表,北京时间):仍在追看的完结剧完整巡检取
         *  min(常规间隔, 下一凌晨档)、看完的每周轻查对齐凌晨档 —— 高峰档的"新集上线多查"语义
         *  对完结剧是反向负载(无上线时效,播放失败另有即时信号兜底),反而挤进晚间观看/播后短轮/
         *  网盘外部高峰;默认避开 06:00 清理与 22:00 索引构建。 */
        private java.util.List<String> nightCheckTimes = java.util.List.of("03:15");
        /** 追更中(官方状态 RETURNING)无新集退避封顶(小时);完结/无元数据维持 24h */
        private int returningBackoffCapHours = 12;
        /** BAD 候选冷却(天):超期允许重探一次(误标自愈),再失败重新计时 */
        private int badCooldownDays = 7;
        /** 瞬时失败候选的短冷却(小时):瞬时故障连击达上限退役的候选按此重探 —— 网盘窗口性抖动
         *  攒满连击不等于链接死,7 天冷却会把好源白白关在池外 */
        private int transientReprobeHours = 24;
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
        /** 盘聚搜索源(seedhub 系聚合站,免登录;Cloudflare 指纹门禁由 JDK TLS 天然通过,被拦时静默降级):追剧候选池补充来源 */
        private boolean panjuEnabled = true;
        /** 盘聚:每次搜索最多抓取的详情页数 */
        private int panjuMaxDetailPages = 2;
        /** 盘聚:每个详情页最多解析的站内中转链数(真实分享链要逐条请求中转页才拿到) */
        private int panjuMaxResolves = 8;
        /** 盘聚:整源搜索总超时(秒) */
        private int panjuTimeoutSeconds = 45;
        /** 6V磁力搜索源(xb6v.com,帝国CMS,免登录;磁力为主+少量网盘资源):追剧磁力兜底与候选池补充来源 */
        private boolean xb6vEnabled = true;
        /** 6V磁力:每次搜索最多抓取的详情页数 */
        private int xb6vMaxDetailPages = 3;
        /** 6V磁力:每个详情页最多产出的磁力条目数(长番详情页磁力可达数百条) */
        private int xb6vMaxMagnets = 50;
        /** 6V磁力:整源搜索总超时(秒) */
        private int xb6vTimeoutSeconds = 45;
        /** 123臻藏搜索源(123.qsxy.top,WordPress+Zibll,详情正文需 Cookie;123 盘为主混少量其它盘/磁力,
         *  仅订阅候选盘白名单包含 123 时参与搜索):追剧候选池补充来源 */
        private boolean zencangEnabled = true;
        /** 123臻藏:每次搜索最多抓取的详情页数 */
        private int zencangMaxDetailPages = 3;
        /** 123臻藏:整源搜索总超时(秒) */
        private int zencangTimeoutSeconds = 45;
        /** 123社区搜索源(123panfx.com/pan1.me 双站探活,Xiuno BBS 论坛;纯 123 盘产出(链接规范化收敛 123pan.cn),
         *  匿名可搜,「回复后可见」帖解锁需社区 Cookie;仅订阅候选盘白名单包含 123 时参与搜索):追剧候选池补充来源 */
        private boolean pan123communityEnabled = true;
        /** 123社区:每次搜索最多抓取的详情页数 */
        private int pan123communityMaxDetailPages = 3;
        /** 123社区:整源搜索总超时(秒) */
        private int pan123communityTimeoutSeconds = 45;
        /** 夸父搜索源(kfzy.net,Xiuno BBS「夸父资源社」;夸克为主混 UC/阿里/天翼/123/115/百度/迅雷,
         *  链接四级提取(锁贴泄漏 JSON-LD 匿名可抓),回复解锁需论坛 Cookie;仅订阅候选盘白名单包含夸克时参与搜索) */
        private boolean kuafuEnabled = true;
        /** 夸父:每次搜索最多抓取的详情页数 */
        private int kuafuMaxDetailPages = 3;
        /** 夸父:整源搜索总超时(秒) */
        private int kuafuTimeoutSeconds = 45;
        /** 订阅巡检并发度:到期订阅并发检查,多个订阅的搜索不再互相排队(源侧压力不放大,见 searchExecutor) */
        private int checkConcurrency = 4;
        /** 磁力兜底介入的补搜轮次门槛(转存优先):round 达到该值(单集词轮)且仍缺才用磁力,
         *  别抢在网盘源上线前烧离线配额 */
        private int magnetFallbackMinRound = 2;
        /** 磁力兜底:离线账号上未收割(PENDING)任务数上限,防一部剧把离线配额烧光 */
        private int magnetMaxPending = 2;
        /** 磁力兜底提交的同步等待时长(秒,默认与迅雷/光鸭一致 30):超时仍按 PENDING 落行等收割;
         *  手动离线下载不受此影响,走各盘默认等待(115=10 秒/迅雷、光鸭=30 秒) */
        private int magnetSubmitTimeoutSeconds = 30;
        /** 磁力兜底硬失败冷却(小时):候选全灭/提交被拒后的退避 */
        private int magnetCooldownHours = 24;
        /** 磁力兜底超时重查间隔(小时):SUBMITTED 任务网盘侧还在下载,到期前不再提交新磁力 */
        private int magnetPendingRecheckHours = 12;
        /** 采集源兜底(部署级默认;用户开关走 Setting msub_collection_fallback):候选源全灭时
         *  播放链路最后一级从 MacCMS 采集站(资源聚合精选 8 站)搜索直链补集「当前集+后3集」。
         *  结果只落 msub_episode_fallback 覆盖层,不改写原始追剧数据 */
        private boolean collectionFallbackEnabled = false;
        /** 采集源兜底:8 站并行搜索/单站详情请求总超时(秒) */
        private int collectionFallbackTimeoutSeconds = 10;
        /** 采集源兜底:搜索无结果/匹配失败的负缓存(分钟),窗口内不重搜 */
        private int collectionFallbackNegativeTtlMinutes = 30;
        /** 采集源兜底:补集行 TTL(小时),过期出局重新采集(永不续期) */
        private int collectionFallbackRowTtlHours = 72;
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
