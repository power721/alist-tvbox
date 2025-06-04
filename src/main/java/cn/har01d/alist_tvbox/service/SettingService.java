package cn.har01d.alist_tvbox.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
import java.util.Arrays;
import java.util.List;
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
        appProperties.setCleanInvalidShares(settingRepository.findById("clean_invalid_shares").map(Setting::getValue).orElse("").equals("true"));
        appProperties.setMix(!settingRepository.findById("mix_site_source").map(Setting::getValue).orElse("").equals("false"));
        appProperties.setSearchable(!settingRepository.findById("bilibili_searchable").map(Setting::getValue).orElse("").equals("false"));
        appProperties.setTgSearch(settingRepository.findById("tg_search").map(Setting::getValue).orElse(""));
        appProperties.setTempShareExpiration(settingRepository.findById("temp_share_expiration").map(Setting::getValue).map(Integer::parseInt).orElse(24));
        appProperties.setQns(settingRepository.findById("bilibili_qn").map(Setting::getValue).map(e -> e.split(",")).map(Arrays::asList).orElse(List.of()));
        settingRepository.findById("debug_log").ifPresent(this::setLogLevel);
        settingRepository.findById("user_agent").ifPresent(e -> appProperties.setUserAgent(e.getValue()));
        String value = settingRepository.findById("tg_channels").map(Setting::getValue).orElse("");
        if (StringUtils.isBlank(value)) {
            settingRepository.save(new Setting("tg_channels", appProperties.getTgChannels()));
        } else {
            appProperties.setTgChannels(value);
        }
        value = settingRepository.findById("tg_web_channels").map(Setting::getValue).orElse("");
        if (StringUtils.isBlank(value)) {
            settingRepository.save(new Setting("tg_web_channels", appProperties.getTgWebChannels()));
        } else {
            appProperties.setTgWebChannels(value);
        }
        value = settingRepository.findById("tg_timeout").map(Setting::getValue).orElse("");
        if (StringUtils.isBlank(value)) {
            settingRepository.save(new Setting("tg_timeout", String.valueOf(appProperties.getTgTimeout())));
        } else {
            appProperties.setTgTimeout(Integer.parseInt(value));
        }
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
            File out = Utils.getDataPath("backup", "database-" + LocalDate.now() + ".zip").toFile();
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
        for (File file : Utils.listFiles(Utils.getDataPath("backup"), "zip")) {
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
        if ("bilibili_qn".equals(setting.getName())) {
            appProperties.setQns(Arrays.asList(setting.getValue().split(",")));
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
        if ("clean_invalid_shares".equals(setting.getName())) {
            appProperties.setCleanInvalidShares("true".equals(setting.getValue()));
        }
        if ("temp_share_expiration".equals(setting.getName())) {
            appProperties.setTempShareExpiration(Integer.parseInt(setting.getValue()));
        }
        if ("tg_channels".equals(setting.getName())) {
            String value = StringUtils.isBlank(setting.getValue()) ? Constants.TG_CHANNELS : setting.getValue();
            setting.setValue(value);
            appProperties.setTgChannels(value);
        }
        if ("tg_web_channels".equals(setting.getName())) {
            String value = StringUtils.isBlank(setting.getValue()) ? Constants.TG_WEB_CHANNELS : setting.getValue();
            setting.setValue(value);
            appProperties.setTgWebChannels(value);
        }
        if ("tg_timeout".equals(setting.getName())) {
            appProperties.setTgTimeout(Integer.parseInt(setting.getValue()));
        }
        if ("tg_search".equals(setting.getName())) {
            appProperties.setTgSearch(setting.getValue());
        }
        if ("user_agent".equals(setting.getName())) {
            appProperties.setUserAgent(setting.getValue());
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
        if ("driver_round_robin".equals(setting.getName())) {
            aListLocalService.updateSetting("driver_round_robin", setting.getValue(), "bool");
        }
        if ("ali_lazy_load".equals(setting.getName())) {
            aListLocalService.updateSetting("ali_lazy_load", setting.getValue(), "bool");
        }
        if ("ali_to_115".equals(setting.getName())) {
            aListLocalService.updateSetting("ali_to_115", setting.getValue(), "bool");
        }
        if ("delete_code_115".equals(setting.getName())) {
            aListLocalService.updateSetting("delete_code_115", setting.getValue(), "string");
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
