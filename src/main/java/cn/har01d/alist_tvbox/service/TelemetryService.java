package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.LiveFollowRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 匿名用量统计上报(公开、可审计、可关闭):每 ≥20h 心跳一次,或载荷变化时立即上报
 * (版本更新/驱动增减/功能开关变化——同载荷的崩溃循环重启仍受 20h 下闸约束)。
 * <p>
 * 载荷只含版本/模式/环境枚举与桶化计数,不含任何用户数据、内容或配置细节;关闭方式:
 * {@code app.telemetry.enabled=false}(配置文件或 ATV_TELEMETRY_ENABLED=false 环境变量)。
 * 上报端点默认空=完全静默,官方构建由 CI 注入 classpath:telemetry.properties。
 * 上报自带 0~N 分钟抖动(默认 30)分散新版本发布后的上报洪峰。
 */
@Slf4j
@Service
public class TelemetryService {

    private static final String LAST_REPORT_KEY = "telemetry_report_time";
    private static final String LAST_PAYLOAD_KEY = "telemetry_report_payload";
    private static final long MIN_INTERVAL_MS = Duration.ofHours(20).toMillis();

    private final AppProperties appProperties;
    private final SettingRepository settingRepository;
    private final JdbcTemplate alistJdbcTemplate;
    private final MediaSubscriptionRepository mediaSubscriptionRepository;
    private final LiveFollowRepository liveFollowRepository;
    private final UserRepository userRepository;
    private final Environment environment;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "telemetry");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 默认空=不上报;官方构建由 CI 注入 classpath:telemetry.properties(见 TelemetryConfiguration)
    @Value("${app.telemetry.url:}")
    private String reportUrl;

    @Value("${app.telemetry.enabled:true}")
    private boolean enabled;

    @Value("${app.telemetry.jitter-max-minutes:30}")
    private int jitterMaxMinutes;

    public TelemetryService(AppProperties appProperties,
                            SettingRepository settingRepository,
                            @Qualifier("alistJdbcTemplate") JdbcTemplate alistJdbcTemplate,
                            MediaSubscriptionRepository mediaSubscriptionRepository,
                            LiveFollowRepository liveFollowRepository,
                            UserRepository userRepository,
                            Environment environment) {
        this.appProperties = appProperties;
        this.settingRepository = settingRepository;
        this.alistJdbcTemplate = alistJdbcTemplate;
        this.mediaSubscriptionRepository = mediaSubscriptionRepository;
        this.liveFollowRepository = liveFollowRepository;
        this.userRepository = userRepository;
        this.environment = environment;
    }

    @PostConstruct
    void logStartupState() {
        String host = "";
        try {
            host = URI.create(reportUrl == null || reportUrl.isBlank() ? "http://x" : reportUrl).getHost();
        } catch (Exception ignored) {
        }
        log.debug("telemetry state: enabled={} urlHost={} systemId={}", telemetryEnabled(), host,
                appProperties.getSystemId() != null);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /** 触发周期默认 1h(首跳 3min≈启动上报);真正上报由载荷变化或 20h 心跳下闸控制(重启不清零) */
    @Scheduled(initialDelayString = "${app.telemetry.initial-delay:180000}",
            fixedDelayString = "${app.telemetry.interval:3600000}")
    public void tick() {
        if (!shouldReport()) {
            return;
        }
        long delayMinutes = jitterMaxMinutes > 0 ? ThreadLocalRandom.current().nextInt(jitterMaxMinutes) : 0;
        log.debug("telemetry tick: scheduling report in {} minutes", delayMinutes);
        scheduler.schedule(this::report, delayMinutes, TimeUnit.MINUTES);
    }

    boolean shouldReport() {
        if (!telemetryEnabled() || reportUrl == null || reportUrl.isBlank()) {
            return false;
        }
        String systemId = appProperties.getSystemId();
        if (systemId == null || systemId.isBlank()) {
            return false;
        }
        if (System.currentTimeMillis() - parseLastReport() < MIN_INTERVAL_MS) {
            // 载荷无变化才受 20h 下闸;版本/环境变化立即上报(启动首跳=更新即时信号)
            return payloadChanged();
        }
        return true;
    }

    void report() {
        boolean ok = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(reportUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildPayload()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ok = response.statusCode() >= 200 && response.statusCode() < 300;
            log.debug("telemetry report: {} {}", response.statusCode(), ok ? "ok" : "ignored");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("telemetry report failed: {}", e.toString());
        } finally {
            if (ok) {
                long now = System.currentTimeMillis();
                settingRepository.save(new Setting(LAST_REPORT_KEY, String.valueOf(now)));
                settingRepository.save(new Setting(LAST_PAYLOAD_KEY, buildPayload()));
            } else if (settingRepository.findById(LAST_PAYLOAD_KEY).isEmpty()) {
                // 首次上报尚无成功载荷时记录时间也不会抑制重试(payload 仍视为变化)。
                settingRepository.save(new Setting(LAST_REPORT_KEY, String.valueOf(System.currentTimeMillis())));
            }
        }
    }

    private boolean telemetryEnabled() {
        String override = environment.getProperty("ATV_TELEMETRY_ENABLED");
        return override == null || override.isBlank() ? enabled : Boolean.parseBoolean(override.trim());
    }

    String buildPayload() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("v", 1);
        fields.put("systemId", appProperties.getSystemId());
        fields.put("version", settingValue("app_version", "dev"));
        fields.put("mode", settingValue("install_mode", "xiaoya"));
        fields.put("runtime", "true".equalsIgnoreCase(environment.getProperty("NATIVE")) ? "native" : "jvm");
        fields.put("arch", normalizeArch(environment.getProperty("os.arch", "")));
        fields.put("java", String.valueOf(Runtime.version().feature()));
        fields.put("db", dbType());
        fields.put("alist", settingValue("alist_version", ""));
        fields.put("drivers", storageDrivers());
        fields.put("features", featureFlags());
        fields.put("subs", subscriptionBucket());
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean payloadChanged() {
        String last = settingRepository.findById(LAST_PAYLOAD_KEY).map(Setting::getValue).orElse("");
        return !buildPayload().equals(last);
    }

    private long parseLastReport() {
        try {
            return Long.parseLong(settingRepository.findById(LAST_REPORT_KEY)
                    .map(Setting::getValue)
                    .orElse("0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String settingValue(String key, String fallback) {
        return settingRepository.findById(key)
                .map(Setting::getValue)
                .filter(v -> !v.isBlank())
                .orElse(fallback);
    }

    private static String normalizeArch(String arch) {
        String value = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "amd64", "x86_64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            case "arm" -> "armv7";
            default -> value.length() > 16 ? value.substring(0, 16) : value;
        };
    }

    private String dbType() {
        String url = environment.getProperty("spring.datasource.jdbc-url");
        if (url == null || url.isBlank()) {
            url = environment.getProperty("spring.datasource.url");
        }
        if (url == null || !url.startsWith("jdbc:")) {
            return "";
        }
        String rest = url.substring(5);
        int colon = rest.indexOf(':');
        String name = colon > 0 ? rest.substring(0, colon) : rest;
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** 网盘驱动类型集合(仅类型名,无任何配置/挂载信息) */
    private String storageDrivers() {
        try {
            List<String> drivers = alistJdbcTemplate.queryForList(
                    "SELECT DISTINCT driver FROM x_storages", String.class);
            TreeSet<String> normalized = new TreeSet<>();
            for (String driver : drivers) {
                String token = driver == null ? "" : driver.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9_]", "");
                if (!token.isEmpty() && token.length() <= 16) {
                    normalized.add(token);
                }
                if (normalized.size() >= 12) {
                    break;
                }
            }
            return String.join(",", normalized);
        } catch (Exception e) {
            return "";
        }
    }

    private String featureFlags() {
        List<String> flags = new ArrayList<>();
        try {
            if (mediaSubscriptionRepository.count() > 0) {
                flags.add("sub");
            }
        } catch (Exception ignored) {
        }
        try {
            if (liveFollowRepository.count() > 0) {
                flags.add("live");
            }
        } catch (Exception ignored) {
        }
        try {
            if (userRepository.count() > 1) {
                flags.add("multiuser");
            }
        } catch (Exception ignored) {
        }
        return String.join(",", flags);
    }

    private String subscriptionBucket() {
        long count = 0;
        try {
            count = mediaSubscriptionRepository.count();
        } catch (Exception ignored) {
        }
        if (count <= 0) {
            return "0";
        }
        if (count <= 3) {
            return "1-3";
        }
        if (count <= 10) {
            return "4-10";
        }
        if (count <= 50) {
            return "11-50";
        }
        return "50+";
    }
}
