package cn.har01d.alist_tvbox.service.diagnostics;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.diagnostics.DiagnosticsFindingDto;
import cn.har01d.alist_tvbox.dto.diagnostics.DiagnosticsReportDto;
import cn.har01d.alist_tvbox.dto.diagnostics.DiagnosticsSectionDto;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionNotifyTask;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionNotifyTaskRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.AListLocalService;
import cn.har01d.alist_tvbox.service.sitesearch.SearchSourceThrottle;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 一键诊断报告(借鉴 MoviePilot doctor):只读聚合版本/运行时/数据库/存储/追剧/搜索源状态
 * 与近期错误日志摘要,产出一键可复制的脱敏文本,降低远程 issue 排障的沟通成本。
 * <ul>
 *   <li>每个区块独立容错:单区块查询失败只降级为该区块的告警条目,不拖垮整份报告;</li>
 *   <li>日志聚合从文件尾部采样(上限字节),按 logger+归一化消息聚类取 TopN;</li>
 *   <li>输出前统一脱敏:ANSI 码剥离、URL 查询参数中的凭证(token/sign/password/...)与
 *       Bearer 令牌打码——报告是给用户贴到公开 issue 用的,凭证绝不能出场。</li>
 * </ul>
 */
@Slf4j
@Service
public class DiagnosticsService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 日志尾部采样上限:够覆盖近几小时,又不至于大文件全量读。 */
    private static final int LOG_TAIL_BYTES = 512 * 1024;
    private static final int APP_LOG_TOP = 5;
    private static final int ALIST_LOG_TOP = 3;
    private static final int MESSAGE_MAX = 100;

    private final SettingRepository settingRepository;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcTemplate alistJdbcTemplate;
    private final AListLocalService aListLocalService;
    private final MediaSubscriptionRepository mediaSubscriptionRepository;
    private final MediaSubscriptionNotifyTaskRepository notifyTaskRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final SearchSourceThrottle searchSourceThrottle;
    private final ObjectProvider<Flyway> flywayProvider;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long startedAt = System.currentTimeMillis();

    public DiagnosticsService(SettingRepository settingRepository,
                              JdbcTemplate jdbcTemplate,
                              @Qualifier("alistJdbcTemplate") JdbcTemplate alistJdbcTemplate,
                              AListLocalService aListLocalService,
                              MediaSubscriptionRepository mediaSubscriptionRepository,
                              MediaSubscriptionNotifyTaskRepository notifyTaskRepository,
                              DriverAccountRepository driverAccountRepository,
                              SearchSourceThrottle searchSourceThrottle,
                              ObjectProvider<Flyway> flywayProvider,
                              AppProperties appProperties) {
        this.settingRepository = settingRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.alistJdbcTemplate = alistJdbcTemplate;
        this.aListLocalService = aListLocalService;
        this.mediaSubscriptionRepository = mediaSubscriptionRepository;
        this.notifyTaskRepository = notifyTaskRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.searchSourceThrottle = searchSourceThrottle;
        this.flywayProvider = flywayProvider;
        this.appProperties = appProperties;
    }

    public DiagnosticsReportDto buildReport() {
        DiagnosticsReportDto report = new DiagnosticsReportDto();
        report.setGeneratedAt(LocalDateTime.now().format(TIME_FORMAT));
        List<DiagnosticsSectionDto> sections = new ArrayList<>();
        List<DiagnosticsFindingDto> findings = new ArrayList<>();

        sections.add(systemSection(findings));
        sections.add(databaseSection(findings));
        sections.add(storageSection(findings));
        sections.add(driveSettingsSection(findings));
        sections.add(configSection(findings));
        sections.add(subscriptionSection(findings));
        sections.add(searchSourceSection(findings));
        sections.add(logSection(findings));

        report.setSections(sections);
        report.setFindings(findings);
        report.setErrorCount((int) findings.stream().filter(f -> DiagnosticsFindingDto.SEVERITY_ERROR.equals(f.getSeverity())).count());
        report.setWarnCount((int) findings.stream().filter(f -> DiagnosticsFindingDto.SEVERITY_WARN.equals(f.getSeverity())).count());
        report.setText(renderText(report));
        return report;
    }

    private String setting(String key, String fallback) {
        return settingRepository.findById(key).map(Setting::getValue).filter(v -> !v.isBlank()).orElse(fallback);
    }

    private boolean settingBool(String key, boolean fallback) {
        String value = setting(key, String.valueOf(fallback));
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private int settingInt(String key, int fallback) {
        try {
            return (int) Double.parseDouble(setting(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ 网盘设置

    /**
     * 网盘相关功能开关(秒传方向/分享直链/TV帐号/代理)。只报开关状态与「已配置/未配置」,
     * 凭证与代理地址的值绝不进报告。
     */
    private DiagnosticsSectionDto driveSettingsSection(List<DiagnosticsFindingDto> findings) {
        DiagnosticsSectionDto section = new DiagnosticsSectionDto("网盘");
        try {
            // key → 中文名;默认值与 AListLocalService 同步块一致
            String[][] rapidKeys = {
                    {"ali_to_115", "阿里→115"}, {"ali_to_123", "阿里→123"}, {"115_to_123", "115→123"},
                    {"quark_to_123", "夸克→123"}, {"uc_to_123", "UC→123"}, {"guangya_to_123", "光鸭→123"},
            };
            List<String> rapid = new ArrayList<>();
            for (String[] pair : rapidKeys) {
                if (settingBool(pair[0], false)) {
                    rapid.add(pair[1]);
                }
            }
            section.add("跨盘秒传", rapid.isEmpty() ? "全关" : String.join(", ", rapid));

            List<String> direct = new ArrayList<>();
            if (settingBool("quark_share_direct", true)) {
                direct.add("夸克");
            }
            if (settingBool("uc_share_direct", true)) {
                direct.add("UC");
            }
            if (settingBool("baidu_share_direct", false)) {
                direct.add("百度");
            }
            section.add("分享免转存直链", direct.isEmpty() ? "全关" : String.join(", ", direct));
            section.add("夸克/UC分享用TV帐号", onOff(settingBool("use_quark_tv", false)));
            section.add("夸父逐日", onOff(settingBool("quark_multi_account_proxy", false)));
            section.add("账号负载均衡", onOff(settingBool("driver_round_robin", false)));
            section.add("分享延迟校验", onOff(settingBool("ali_lazy_load", true)));
            section.add("自动清理失效资源", onOff(settingBool("clean_invalid_shares", false)));

            int deleteDelay = settingInt("delete_delay_time", 900);
            section.add("文件删除延时", deleteDelay <= 0 ? "不删除" : deleteDelay + " 秒");
            section.add("临时分享过期", settingInt("temp_share_expiration", 72) + " 小时");
            section.add("分享校验间隔", settingInt("validateSharesInterval", 4) + " 小时");
            section.add("离线下载", offlineDownloadSummary());

            section.add("本地代理", localProxySummary());
            section.add("GitHub代理", setting("github_proxy", "").isBlank() ? "未配置" : "已配置");
            section.add("TG代理", setting("msub_telegram_proxy", "").isBlank() ? "未配置" : "已配置");
        } catch (Exception e) {
            addUnavailable(section, findings, e);
        }
        return section;
    }

    /**
     * 离线下载配置(Setting offline_download_config JSON)+ 清理链路状态:开关/网盘类型/自动清理
     * 参数、任务行分布、上次清理时间——「设置了自动清理但文件不删」的排障全靠这一行定位
     * (候选恒空=行已 DONE 或换账号;上次清理久远=调度错过;账号 id 等敏感值不进报告)。
     */
    private String offlineDownloadSummary() {
        String value = setting("offline_download_config", "");
        if (value.isBlank()) {
            return "关";
        }
        try {
            JsonNode config = objectMapper.readTree(value);
            if (!config.path("enabled").asBoolean(false)) {
                return "关";
            }
            String driver = config.path("driverType").asText("PAN115");
            String name = switch (driver) {
                case "GUANGYA" -> "光鸭";
                case "THUNDER" -> "迅雷";
                case "PAN115" -> "115";
                default -> driver;
            };
            StringBuilder sb = new StringBuilder("开(").append(name).append(")");
            boolean autoDelete = config.path("autoDelete").asBoolean(false);
            boolean selfShare = config.path("selfShare").asBoolean(false);
            if (autoDelete || selfShare) {
                sb.append(", 自动清理: 开");
                if (config.hasNonNull("ttlHours")) {
                    sb.append("(通用保留 ").append(config.path("ttlHours").asInt()).append("h)");
                }
                if (selfShare) {
                    sb.append(", 固化: 开");
                }
                sb.append("; ").append(cleanupRowSummary());
            } else {
                sb.append(", 自动清理: 关");
            }
            return sb.toString();
        } catch (Exception e) {
            return "开(配置解析失败)";
        }
    }

    /** 清理任务行分布 + 上次清理距今;查询失败静默省略(离线行不存在的旧实例正常)。 */
    private String cleanupRowSummary() {
        try {
            Map<String, Long> counts = new LinkedHashMap<>();
            jdbcTemplate.query("select CLEANUP_STATE, count(*) CNT from OFFLINE_DOWNLOAD_TASK group by CLEANUP_STATE",
                    rs -> {
                        String state = rs.getString(1);
                        counts.put(state == null ? "pending" : state, rs.getLong(2));
                    });
            long done = counts.getOrDefault("DONE", 0L);
            long failed = counts.getOrDefault("FAILED", 0L);
            long pending = counts.values().stream().mapToLong(Number::longValue).sum() - done - failed;
            StringBuilder sb = new StringBuilder("已清 ").append(done).append(", 待清理 ").append(pending);
            if (failed > 0) {
                sb.append(", 清理失败 ").append(failed);
            }
            jdbcTemplate.query("select min(coalesce(COMPLETED_TIME, UPDATED_TIME, CREATED_TIME))"
                            + " from OFFLINE_DOWNLOAD_TASK where CLEANUP_STATE is null or CLEANUP_STATE <> 'DONE'",
                    rs -> {
                        if (rs.next() && rs.getTimestamp(1) != null) {
                            long hours = (System.currentTimeMillis() - rs.getTimestamp(1).getTime()) / 3600000L;
                            sb.append("(最早 ").append(hours >= 24 ? hours / 24 + " 天前" : hours + " 小时前").append(")");
                        }
                    });
            String lastRun = setting("offline_cleanup_last_run", "");
            if (!lastRun.isBlank()) {
                try {
                    long hours = (System.currentTimeMillis() - java.time.Instant.parse(lastRun).toEpochMilli()) / 3600000L;
                    sb.append("; 上次清理 ").append(hours <= 0 ? "刚刚" : hours >= 24 ? hours / 24 + " 天前" : hours + " 小时前");
                } catch (Exception ignore) {
                    // marker 损坏只省略时间
                }
            } else {
                sb.append("; 上次清理: 从未");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("offline cleanup row summary failed: {}", e.getMessage());
            return "";
        }
    }

    /** 本地代理只报启用的驱动名,URL/凭证明细不进报告;空或解析失败视为默认(全启用)。 */
    private String localProxySummary() {
        String value = setting("local_proxy_config", "");
        if (value.isBlank()) {
            return "默认(全启用)";
        }
        try {
            JsonNode map = objectMapper.readTree(value);
            List<String> enabled = new ArrayList<>();
            map.fieldNames().forEachRemaining(driver -> {
                JsonNode item = map.get(driver);
                if (item != null && item.path("enabled").asBoolean(true)) {
                    enabled.add(driver);
                }
            });
            return enabled.isEmpty() ? "全禁用" : String.join(", ", enabled);
        } catch (Exception e) {
            return "默认(配置解析失败)";
        }
    }

    private static String onOff(boolean value) {
        return value ? "开" : "关";
    }

    // ------------------------------------------------------------------ 系统

    private DiagnosticsSectionDto systemSection(List<DiagnosticsFindingDto> findings) {
        DiagnosticsSectionDto section = new DiagnosticsSectionDto("系统");
        try {
            String runtime = "true".equalsIgnoreCase(System.getenv("NATIVE")) ? "native" : "jvm";
            section.add("版本", setting("app_version", "dev"))
                    .add("模式", setting("install_mode", "unknown"))
                    .add("AList", setting("alist_version", "unknown"))
                    .add("运行时", runtime + " | Java " + Runtime.version().feature()
                            + " | " + System.getProperty("os.name") + " " + System.getProperty("os.arch"))
                    .add("已运行", formatDuration(System.currentTimeMillis() - startedAt));

            Runtime r = Runtime.getRuntime();
            long used = r.totalMemory() - r.freeMemory();
            long max = r.maxMemory();
            int memPercent = (int) (max > 0 ? used * 100 / max : 0);
            section.add("内存", used / 1048576 + "/" + max / 1048576 + "MB (" + memPercent + "%)");
            if (memPercent >= 90) {
                findings.add(new DiagnosticsFindingDto(DiagnosticsFindingDto.SEVERITY_WARN, "内存使用 " + memPercent + "%"));
            }

            Path dataDir = Utils.getDataPath();
            if (Files.exists(dataDir)) {
                long free = Files.getFileStore(dataDir).getUsableSpace();
                long total = Files.getFileStore(dataDir).getTotalSpace();
                long gb = 1024L * 1024 * 1024;
                section.add("磁盘(" + dataDir + ")", String.format(Locale.ROOT, "可用 %.1fGB / %.1fGB", free * 1.0 / gb, total * 1.0 / gb));
                if (total > 0 && free * 10 < total) {
                    findings.add(new DiagnosticsFindingDto(DiagnosticsFindingDto.SEVERITY_WARN,
                            "数据目录剩余空间不足 10%: " + String.format(Locale.ROOT, "%.1fGB", free * 1.0 / gb)));
                }
            }
        } catch (Exception e) {
            addUnavailable(section, findings, e);
        }
        return section;
    }

    // ------------------------------------------------------------------ 数据库

    private DiagnosticsSectionDto databaseSection(List<DiagnosticsFindingDto> findings) {
        DiagnosticsSectionDto section = new DiagnosticsSectionDto("数据库");
        try {
            try (var connection = jdbcTemplate.getDataSource().getConnection()) {
                var metadata = connection.getMetaData();
                section.add("产品", abbreviate(sanitize(metadata.getDatabaseProductName() + " "
                        + metadata.getDatabaseProductVersion()), MESSAGE_MAX));
            }
            // 走 Flyway API 而非裸 SQL:Flyway 11 在 H2 建的历史表是带引号小写标识符,
            // 无引号查询被 H2 折叠成大写即 BadSqlGrammar;MySQL 又默认不认双引号标识符——方言全交给 Flyway。
            Flyway flyway = flywayProvider.getIfAvailable();
            if (flyway != null) {
                MigrationInfo[] applied = flyway.info().applied();
                if (applied == null) {
                    applied = new MigrationInfo[0];
                }
                // isFailed() 同时覆盖 FAILED 与 MISSING_FAILED(脚本已不在 classpath 的失败历史行,实测形态)
                long failed = Arrays.stream(applied)
                        .filter(m -> m.getState().isFailed()).count();
                section.add("Flyway", "当前 " + latestSuccessfulVersion(applied) + ", 失败 " + failed);
                if (failed > 0) {
                    section.setStatus(DiagnosticsSectionDto.STATUS_ERROR);
                    findings.add(new DiagnosticsFindingDto(DiagnosticsFindingDto.SEVERITY_ERROR,
                            "Flyway 有 " + failed + " 条失败迁移,升级可能不完整"));
                }
            }
        } catch (Exception e) {
            addUnavailable(section, findings, e);
        }
        return section;
    }

    // ------------------------------------------------------------------ 存储

    private DiagnosticsSectionDto storageSection(List<DiagnosticsFindingDto> findings) {
        DiagnosticsSectionDto section = new DiagnosticsSectionDto("存储");
        try {
            int alistStatus = aListLocalService.getStatus();
            String statusText = alistStatus == 2 ? "运行中" : alistStatus == 1 ? "启动中" : "未运行";
            section.add("AList服务", statusText);
            if (alistStatus == 0) {
                section.setStatus(DiagnosticsSectionDto.STATUS_WARN);
                findings.add(new DiagnosticsFindingDto(DiagnosticsFindingDto.SEVERITY_WARN, "内嵌 AList 服务未运行"));
            }

            List<Map<String, Object>> storages = alistJdbcTemplate.queryForList(
                    "select driver, status, disabled from x_storages");
            Map<String, Integer> drivers = new TreeMap<>();
            int errored = 0;
            int disabled = 0;
            for (Map<String, Object> row : storages) {
                String driver = String.valueOf(row.get("driver"));
                drivers.merge(driver, 1, Integer::sum);
                if (truthy(row.get("disabled"))) {
                    disabled++;
                } else if (!"work".equalsIgnoreCase(String.valueOf(row.get("status")))) {
                    errored++;
                }
            }
            section.add("挂载", storages.size() + " 个(异常 " + errored + ", 停用 " + disabled + ")");
            section.add("驱动", drivers.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("无"));
            if (errored > 0) {
                section.setStatus(DiagnosticsSectionDto.STATUS_WARN);
                findings.add(new DiagnosticsFindingDto(DiagnosticsFindingDto.SEVERITY_WARN,
                        errored + " 个挂载状态异常(非 work),详见存储页"));
            }

            List<DriverAccount> accounts = driverAccountRepository.findAll();
            Map<String, Integer> types = new TreeMap<>();
            int accountDisabled = 0;
            for (DriverAccount account : accounts) {
                types.merge(account.getType().name(), 1, Integer::sum);
                if (account.isDisabled()) {
                    accountDisabled++;
                }
            }
            section.add("网盘账号", accounts.size() + " 个(停用 " + accountDisabled + "): "
                    + types.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                            .reduce((a, b) -> a + ", " + b).orElse("无"));
        } catch (Exception e) {
            addUnavailable(section, findings, e);
        }
        return section;
    }

    // ------------------------------------------------------------------ 全局配置

    /**
     * 设置页(ConfigView)的全局配置:安全/订阅/调试开关 + 数据版本 + TMDB 状态。
     * Key 名与 ConfigView.vue 保存口径一致;Key 类只报已配置否,值不进报告。
     */
    private DiagnosticsSectionDto configSection(List<DiagnosticsFindingDto> findings) {
        DiagnosticsSectionDto section = new DiagnosticsSectionDto("配置");
        try {
            section.add("强制登录AList", onOff(settingBool("alist_login", false)));
            section.add("安全订阅", onOff(settingBool("enabled_token", false)));
            section.add("亲友共享", onOff(settingBool("anonymous_access", false)));
            section.add("订阅HTTPS", onOff(settingBool("enable_https", false)));
            section.add("替换阿里token地址", onOff(settingBool("replace_ali_token", false)));
            section.add("调试日志", onOff(settingBool("debug_log", false)));
            section.add("AList调试模式", onOff(settingBool("alist_debug", false)));
            section.add("TMDB Key", setting("tmdb_api_key", "").isBlank() ? "未配置" : "已配置");
            section.add("TMDB 代理", setting("tmdb_api_host", "").isBlank() ? "默认" : setting("tmdb_api_host", ""));
            section.add("开放Token认证URL", setting("open_token_url", "").isBlank() ? "默认" : setting("open_token_url", ""));
            section.add("索引数据版本", setting("index_version", "无"));
            section.add("豆瓣数据版本", setting("movie_version", "无"));
            section.add("115索引版本", setting("index115.share_code", "").isBlank() ? "未下载" : setting("index115.share_code", ""));
        } catch (Exception e) {
            addUnavailable(section, findings, e);
        }
        return section;
    }

    // ------------------------------------------------------------------ 追剧

    private DiagnosticsSectionDto subscriptionSection(List<DiagnosticsFindingDto> findings) {
        DiagnosticsSectionDto section = new DiagnosticsSectionDto("追剧");
        try {
            long active = mediaSubscriptionRepository.countByStatus(MediaSubscription.STATUS_ACTIVE);
            long paused = mediaSubscriptionRepository.countByStatus(MediaSubscription.STATUS_PAUSED);
            long ended = mediaSubscriptionRepository.countByStatus(MediaSubscription.STATUS_ENDED);
            long error = mediaSubscriptionRepository.countByStatus(MediaSubscription.STATUS_ERROR);
            section.add("订阅", "追更 " + active + " / 暂停 " + paused + " / 完结 " + ended + " / 错误 " + error);
            if (error > 0) {
                section.setStatus(DiagnosticsSectionDto.STATUS_WARN);
                findings.add(new DiagnosticsFindingDto(DiagnosticsFindingDto.SEVERITY_WARN,
                        error + " 个订阅处于 ERROR 状态,连续巡检失败"));
            }

            long pending = notifyTaskRepository.countByStatus(MediaSubscriptionNotifyTask.STATUS_PENDING);
            long failed = notifyTaskRepository.countByStatus(MediaSubscriptionNotifyTask.STATUS_FAILED);
            section.add("通知", "待发 " + pending + ", 失败 " + failed);
            if (failed > 0) {
                section.setStatus(DiagnosticsSectionDto.STATUS_WARN);
                findings.add(new DiagnosticsFindingDto(DiagnosticsFindingDto.SEVERITY_WARN,
                        failed + " 条通知重试超限落入 FAILED(消息历史页可见)"));
            }
            addSubscriptionFilterLines(section, findings);
        } catch (Exception e) {
            addUnavailable(section, findings, e);
        }
        return section;
    }

    /**
     * 资源筛选配置入报告(2026-09-13 线上冬城猎凶:用户全局单集体积筛选拒收整目录 4K 候选,
     * 被误报「疑似同名异剧」连环退役,排障拿不到配置对不上号)。只报数值门槛与计数,
     * 包含/排除词的具体值不进报告(与凭证同款保守口径)。
     */
    private void addSubscriptionFilterLines(DiagnosticsSectionDto section, List<DiagnosticsFindingDto> findings) {
        long floorMb = appProperties.getSubscription().getMinEpisodeSizeMb();
        long maxMb = 0;
        String minQuality = "";
        int includeCount = 0;
        int excludeCount = 0;
        String raw = setting("msub_pool_filter", "");
        if (!raw.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(raw);
                long configured = node.path("minEpisodeSizeMb").asLong(0);
                floorMb = configured > 0 ? configured : floorMb; // 0/缺省回落部署默认底线
                maxMb = node.path("maxEpisodeSizeMb").asLong(0);
                minQuality = node.path("minQuality").asText("");
                includeCount = arraySize(node.path("includeKeywords"));
                excludeCount = arraySize(node.path("excludeKeywords"));
            } catch (Exception ignored) {
                // 坏配置按未配置口径报,不拖垮区块
            }
        }
        StringBuilder line = new StringBuilder("单集体积 下限 ").append(floorMb).append(" MB");
        if (maxMb > 0) {
            line.append(" / 上限 ").append(maxMb).append(" MB");
        }
        line.append(", 清晰度门槛 ").append(minQuality.isBlank() ? "无" : minQuality)
                .append(", 包含词 ").append(includeCount).append("/排除词 ").append(excludeCount);
        int overrides = countSubscriptionSizeOverrides();
        int userFilters = countUserPoolFilters();
        if (overrides > 0) {
            line.append(";订阅级体积覆盖 ").append(overrides).append(" 个");
        }
        if (userFilters > 0) {
            line.append(";用户级筛选 ").append(userFilters).append(" 份");
        }
        section.add("资源筛选", line.toString());
        if (floorMb >= 1500) {
            section.setStatus(DiagnosticsSectionDto.STATUS_WARN);
            findings.add(new DiagnosticsFindingDto(DiagnosticsFindingDto.SEVERITY_WARN,
                    "单集体积下限 " + floorMb + " MB 会拒收压缩版 4K 资源(常见 0.3~1.6GB/集),"
                            + "候选可能因此全部落选(网页「追剧设置-资源筛选」可调)"));
        }
    }

    /** 订阅级 filter_config 里配了单集体积上下限的订阅数(优先级高于全局,排障须知情)。 */
    private int countSubscriptionSizeOverrides() {
        try {
            int count = 0;
            for (MediaSubscription subscription : mediaSubscriptionRepository.findAll()) {
                String config = subscription.getFilterConfig();
                if (config == null || config.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = objectMapper.readTree(config);
                    if (node.path("minEpisodeSizeMb").asLong(0) > 0 || node.path("maxEpisodeSizeMb").asLong(0) > 0) {
                        count++;
                    }
                } catch (Exception ignored) {
                    // 单行坏值跳过
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 多用户部署的用户级 msub_pool_filter:u{uid} 行数(每份各有自己的体积口径)。 */
    private int countUserPoolFilters() {
        try {
            return (int) settingRepository.findAll().stream()
                    .filter(s -> s.getName() != null && s.getName().startsWith("msub_pool_filter:"))
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int arraySize(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }

    // ------------------------------------------------------------------ 搜索源

    private DiagnosticsSectionDto searchSourceSection(List<DiagnosticsFindingDto> findings) {
        DiagnosticsSectionDto section = new DiagnosticsSectionDto("搜索源");
        try {
            // 站点源开关(配置文件级,AppProperties.Subscription);盘链/观影/蜗牛无开关恒参与,只看凭证
            var subscription = appProperties.getSubscription();
            section.add("站点源", "玩偶 " + onOff(subscription.isWanouEnabled())
                    + ", 盘聚 " + onOff(subscription.isPanjuEnabled())
                    + ", 6V " + onOff(subscription.isXb6vEnabled())
                    + ", 123臻藏 " + onOff(subscription.isZencangEnabled())
                    + ", 123社区 " + onOff(subscription.isPan123communityEnabled())
                    + ", 夸父 " + onOff(subscription.isKuafuEnabled()));
            section.add("站点凭证", "盘链 " + panlianCredentialSummary()
                    + ", 观影 " + credentialConfigured("guanying_username", "guanying_password", "guanying_cookie")
                    + ", 蜗牛 " + credentialConfigured("woniu_cookie"));
            List<String> blocked = searchSourceThrottle.blockedSnapshot();
            section.add("退避中", blocked.isEmpty() ? "无" : String.join("; ", blocked));
        } catch (Exception e) {
            addUnavailable(section, findings, e);
        }
        return section;
    }

    /** 盘链账号池优先报账号数(数组长度),否则按散键报已配置;账号内容绝不进报告。 */
    private String panlianCredentialSummary() {
        String accounts = setting("panlian_accounts", "");
        if (!accounts.isBlank()) {
            try {
                JsonNode array = objectMapper.readTree(accounts);
                if (array.isArray() && !array.isEmpty()) {
                    return array.size() + " 个账号";
                }
            } catch (Exception ignored) {
                // 非法 JSON 落到散键判断
            }
        }
        return credentialConfigured("panlian_username", "panlian_password", "panlian_cookie");
    }

    /** 任意一键非空即视为已配置;只报配置状态,值不进报告。 */
    private String credentialConfigured(String... keys) {
        for (String key : keys) {
            if (!setting(key, "").isBlank()) {
                return "已配置";
            }
        }
        return "未配置";
    }

    // ------------------------------------------------------------------ 日志

    private DiagnosticsSectionDto logSection(List<DiagnosticsFindingDto> findings) {
        DiagnosticsSectionDto section = new DiagnosticsSectionDto("日志");
        List<String> appErrors = List.of();
        List<String> alistErrors = List.of();
        try {
            appErrors = aggregateErrors(Utils.getLogPath("app.log"), true, APP_LOG_TOP);
        } catch (Exception e) {
            log.debug("app log aggregation skipped: {}", e.toString());
        }
        try {
            alistErrors = aggregateErrors(Path.of(aListLocalService.getLogPath()), false, ALIST_LOG_TOP);
        } catch (Exception e) {
            log.debug("alist log aggregation skipped: {}", e.toString());
        }

        if (appErrors.isEmpty()) {
            section.add("app 错误摘要", "无");
        } else {
            section.add("app 错误摘要", appErrors.size() + " 类");
            appErrors.forEach(line -> section.add("", line));
        }
        if (!alistErrors.isEmpty()) {
            section.add("alist 错误摘要", alistErrors.size() + " 类");
            alistErrors.forEach(line -> section.add("", line));
        }
        if (!appErrors.isEmpty() || !alistErrors.isEmpty()) {
            section.setStatus(DiagnosticsSectionDto.STATUS_WARN);
            findings.add(new DiagnosticsFindingDto(DiagnosticsFindingDto.SEVERITY_WARN, "近期日志存在 ERROR(见摘要)"));
        }
        return section;
    }

    /**
     * 从日志文件尾部采样,按 logger+归一化消息聚类 ERROR,输出 "logger | 消息 (N次)" TopN。
     *
     * @param appFormat true=Spring Boot 格式(时间 ERROR pid --- [线程] logger : 消息);
     *                  false=AList/Go 格式(整行含 ERROR 即计入,按消息聚类)
     */
    static List<String> aggregateErrors(Path file, boolean appFormat, int top) throws IOException {
        if (file == null || !Files.isReadable(file)) {
            return List.of();
        }
        List<String> lines = tailLines(file, LOG_TAIL_BYTES);
        Map<String, long[]> counts = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = stripAnsi(raw);
            if (!isErrorLine(line, appFormat)) {
                continue;
            }
            String key;
            if (appFormat) {
                int[] ranges = loggerRange(line);
                if (ranges[0] < 0) {
                    key = "? | " + normalizeMessage(line);
                } else {
                    key = line.substring(ranges[0], ranges[1]) + " | " + normalizeMessage(line.substring(ranges[2]));
                }
            } else {
                key = normalizeMessage(line);
            }
            counts.computeIfAbsent(key, k -> new long[1])[0]++;
        }
        return counts.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(top)
                .map(e -> abbreviate(sanitize(e.getKey()), MESSAGE_MAX + 40) + " (" + e.getValue()[0] + "次)")
                .toList();
    }

    /** 读文件尾部若干字节按行拆分(跨过被截断的首行),避免大文件整读。 */
    static List<String> tailLines(Path file, int maxBytes) throws IOException {
        long size = Files.size(file);
        int read = (int) Math.min(size, maxBytes);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (size > maxBytes) {
                raf.seek(size - maxBytes);
            }
            byte[] buffer = new byte[read];
            raf.readFully(buffer);
            String text = new String(buffer, StandardCharsets.UTF_8);
            int start = size > maxBytes ? text.indexOf('\n') + 1 : 0;
            return text.substring(start).lines().toList();
        }
    }

    static boolean isErrorLine(String line, boolean appFormat) {
        if (line.isBlank()) {
            return false;
        }
        if (appFormat) {
            return "ERROR".equals(parseLevel(line));
        }
        // AList 是 logrus ForceColors 短等级码(ANSI 已剥离): ERRO[时间] 消息;兜底整词 ERROR
        return line.startsWith("ERRO[") || line.startsWith("FATA[") || line.contains("ERROR");
    }

    /** Spring Boot 行取等级:时间 ERROR pid --- [线程] logger : 消息(兼容无 pid 变体)。 */
    static String parseLevel(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length > 7 && "---".equals(parts[3])) {
            return parts[1];
        }
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            if ("ERROR".equals(parts[i]) || "WARN".equals(parts[i]) || "INFO".equals(parts[i]) || "DEBUG".equals(parts[i])) {
                return parts[i];
            }
        }
        return "";
    }

    /** 返回 [loggerStart, loggerEnd, messageStart];找不到 " : " 分隔时全 -1,由调用方兜底。 */
    static int[] loggerRange(String line) {
        int separator = line.indexOf(" : ");
        if (separator < 0) {
            return new int[]{-1, -1, -1};
        }
        int loggerStart = line.lastIndexOf(' ', separator - 1) + 1;
        return new int[]{loggerStart, separator, separator + 3};
    }

    /** 归一化消息用于聚类:URL、UUID、数字折叠,再截断。 */
    static String normalizeMessage(String message) {
        String normalized = message
                .replaceAll("https?://\\S+", "URL")
                .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "UUID")
                .replaceAll("\\d+", "N");
        return abbreviate(normalized, MESSAGE_MAX);
    }

    /** 输出脱敏:ANSI 剥离、URL 凭证参数与 Bearer 令牌打码。所有离开本服务的文本都过这里。 */
    static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        return stripAnsi(text)
                .replaceAll("(?i)(token|sign|passwd|password|pwd|secret|cookie|authorization|stoken|access_token|key)=([^&\\s\"'<>]+)", "$1=***")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9\\-._~+/]+=*", "Bearer ***")
                .trim();
    }

    static String stripAnsi(String text) {
        return text == null ? "" : text.replaceAll("\u001b\\[[0-9;]*m", "");
    }

    static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }

    /** JDBC 布尔列的多形态:H2/MySQL 驱动回 Boolean,SQLite 回 0/1 整数。 */
    static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "t".equals(text);
    }

    static String formatDuration(long millis) {
        long days = millis / 86400000L;
        long hours = millis / 3600000L % 24;
        long minutes = millis / 60000L % 60;
        if (days > 0) {
            return days + "天" + hours + "小时";
        }
        if (hours > 0) {
            return hours + "小时" + minutes + "分";
        }
        return minutes + "分";
    }

    /**
     * 最后成功应用的迁移版本;注意不能直接用 {@code info.current()}——它会把失败行也算进去。
     */
    static String latestSuccessfulVersion(MigrationInfo[] applied) {
        return Arrays.stream(applied)
                .filter(m -> !m.getState().isFailed())
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull)
                .max(MigrationVersion::compareTo)
                .map(MigrationVersion::getVersion)
                .orElse("无");
    }

    private void addUnavailable(DiagnosticsSectionDto section, List<DiagnosticsFindingDto> findings, Exception e) {
        section.setStatus(DiagnosticsSectionDto.STATUS_ERROR);
        // e.toString() 而非 getMessage():很多异常 message 为 null,toString 至少带异常类名
        String reason = abbreviate(sanitize(e.toString()), MESSAGE_MAX);
        section.add("状态", "查询失败: " + reason);
        findings.add(new DiagnosticsFindingDto(DiagnosticsFindingDto.SEVERITY_ERROR,
                section.getName() + " 区块查询失败: " + reason));
    }

    // ------------------------------------------------------------------ 文本渲染

    /** 紧凑纯文本:每区块一行(键值 " | " 连接),空键条目为上一行的缩进续行(日志摘要用)。 */
    private String renderText(DiagnosticsReportDto report) {
        StringBuilder sb = new StringBuilder("AList-TvBox 诊断报告 ").append(report.getGeneratedAt()).append('\n');
        for (DiagnosticsSectionDto section : report.getSections()) {
            String prefix = "[" + section.getName() + "] ";
            StringBuilder line = new StringBuilder();
            for (Map.Entry<String, String> item : section.getItems().entrySet()) {
                String value = sanitize(item.getValue());
                if (item.getKey().isEmpty()) {
                    if (line.length() > 0) {
                        sb.append(line).append('\n');
                        line.setLength(0);
                    }
                    sb.append("    ").append(value).append('\n');
                } else {
                    line.append(line.length() == 0 ? prefix : " | ").append(item.getKey()).append(": ").append(value);
                }
            }
            if (line.length() > 0) {
                sb.append(line).append('\n');
            }
        }
        if (!report.getFindings().isEmpty()) {
            sb.append("告警 (").append(report.getFindings().size()).append("):\n");
            for (DiagnosticsFindingDto finding : report.getFindings()) {
                sb.append("  [").append(finding.getSeverity()).append("] ")
                        .append(sanitize(finding.getMessage())).append('\n');
            }
        } else {
            sb.append("告警: 无\n");
        }
        sb.append("已脱敏(凭证参数/令牌已打码);请随 issue 附上本报告与问题截图。");
        return sb.toString();
    }
}
