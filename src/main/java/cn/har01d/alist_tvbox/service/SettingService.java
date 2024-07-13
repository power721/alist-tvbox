package cn.har01d.alist_tvbox.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class SettingService {
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final AppProperties appProperties;
    private final TmdbService tmdbService;
    private final AListLocalService aListLocalService;
    private final SettingRepository settingRepository;

    public SettingService(JdbcTemplate jdbcTemplate, Environment environment, AppProperties appProperties, TmdbService tmdbService, AListLocalService aListLocalService, SettingRepository settingRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.appProperties = appProperties;
        this.tmdbService = tmdbService;
        this.aListLocalService = aListLocalService;
        this.settingRepository = settingRepository;
    }

    @PostConstruct
    public void setup() {
        settingRepository.save(new Setting("install_mode", environment.getProperty("INSTALL", "xiaoya")));
        appProperties.setMerge(settingRepository.findById("merge_site_source").map(Setting::getValue).orElse("").equals("true"));
        appProperties.setHeartbeat(settingRepository.findById("bilibili_heartbeat").map(Setting::getValue).orElse("").equals("true"));
        appProperties.setSupportDash(settingRepository.findById("bilibili_dash").map(Setting::getValue).orElse("").equals("true"));
        appProperties.setReplaceAliToken(settingRepository.findById("replace_ali_token").map(Setting::getValue).orElse("").equals("true"));
        appProperties.setEnableHttps(settingRepository.findById("enable_https").map(Setting::getValue).orElse("").equals("true"));
        appProperties.setMix(!settingRepository.findById("mix_site_source").map(Setting::getValue).orElse("").equals("false"));
        appProperties.setSearchable(!settingRepository.findById("bilibili_searchable").map(Setting::getValue).orElse("").equals("false"));
        settingRepository.findById("debug_log").ifPresent(this::setLogLevel);
    }

    public FileSystemResource exportDatabase() throws IOException {
        File out = backupDatabase();
        if (out == null) {
            throw new IOException("备份数据库失败");
        }
        return new FileSystemResource(out);
    }

    @Scheduled(cron = "0 0 6 * * *")
    public File backupDatabase() {
        try {
            jdbcTemplate.execute("SCRIPT TO '/tmp/script.sql' TABLE ACCOUNT, ALIST_ALIAS, CONFIG_FILE, ID_GENERATOR, INDEX_TEMPLATE, NAVIGATION, PIK_PAK_ACCOUNT, SETTING, SHARE, SITE, SUBSCRIPTION, TASK, USERS, TMDB, TMDB_META");
            File out = new File("/data/backup/database-" + LocalDate.now() + ".zip");
            out.createNewFile();
            try (FileOutputStream fos = new FileOutputStream(out);
                 ZipOutputStream zipOut = new ZipOutputStream(fos)) {
                File fileToZip = new File("/tmp/script.sql");
                Utils.zipFile(fileToZip, fileToZip.getName(), zipOut);
            }
            cleanBackups();
            return out;
        } catch (Exception e) {
            log.warn("backup database failed", e);
        }
        return null;
    }

    private void cleanBackups() {
        LocalDate day = LocalDate.now().minusDays(7);
        for (File file : Utils.listFiles("/data/backup", "zip")) {
            if (file.getName().startsWith("database-")) {
                try {
                    String name = file.getName().replace("database-", "").replace(".zip", "");
                    LocalDate date = LocalDate.parse(name);
                    if (date.isBefore(day)) {
                        file.delete();
                    }
                } catch (Exception e) {
                    log.warn("clean backup failed", e);
                }
            }
        }
    }

    public Map<String, String> findAll() {
        Map<String, String> map = settingRepository.findAll()
                .stream()
                .filter(e -> e.getName() != null && e.getValue() != null)
                .collect(Collectors.toMap(Setting::getName, Setting::getValue));
        map.remove("api_key");
        map.remove("bilibili_cookie");
        return map;
    }

    public Setting get(String name) {
        return settingRepository.findById(name).orElse(null);
    }

    public Setting update(Setting setting) {
        if ("merge_site_source".equals(setting.getName())) {
            appProperties.setMerge("true".equals(setting.getValue()));
        }
        if ("bilibili_heartbeat".equals(setting.getName())) {
            appProperties.setHeartbeat("true".equals(setting.getValue()));
        }
        if ("bilibili_searchable".equals(setting.getName())) {
            appProperties.setSearchable("true".equals(setting.getValue()));
        }
        if ("bilibili_dash".equals(setting.getName())) {
            appProperties.setSupportDash("true".equals(setting.getValue()));
        }
        if ("mix_site_source".equals(setting.getName())) {
            appProperties.setMix("true".equals(setting.getValue()));
        }
        if ("replace_ali_token".equals(setting.getName())) {
            appProperties.setReplaceAliToken("true".equals(setting.getValue()));
        }
        if ("enable_https".equals(setting.getName())) {
            appProperties.setEnableHttps("true".equals(setting.getValue()));
        }
        if ("tmdb_api_key".equals(setting.getName())) {
            tmdbService.setApiKey(setting.getValue());
        }
        if ("debug_log".equals(setting.getName())) {
            setLogLevel(setting);
        }
        if ("delete_delay_time".equals(setting.getName())) {
            aListLocalService.updateSetting("delete_delay_time", setting.getValue(), "number");
        }
        if ("ali_to_115".equals(setting.getName())) {
            aListLocalService.updateSetting("ali_to_115", setting.getValue(), "bool");
        }
        return settingRepository.save(setting);
    }

    private void setLogLevel(Setting setting) {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = loggerContext.getLogger("cn.har01d");
        if ("true".equals(setting.getValue())) {
            log.info("enable debug log");
            logger.setLevel(Level.DEBUG);
        } else {
            log.info("disable debug log");
            logger.setLevel(Level.INFO);
        }
    }
}
