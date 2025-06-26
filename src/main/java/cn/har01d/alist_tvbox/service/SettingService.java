package cn.har01d.alist_tvbox.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.dto.SearchSetting;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
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
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final DriverAccountRepository driverAccountRepository;

    public SettingService(JdbcTemplate jdbcTemplate,
                          Environment environment,
                          AppProperties appProperties,
                          TmdbService tmdbService,
                          AListLocalService aListLocalService,
                          SettingRepository settingRepository,
                          DriverAccountRepository driverAccountRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.appProperties = appProperties;
        this.tmdbService = tmdbService;
        this.aListLocalService = aListLocalService;
        this.settingRepository = settingRepository;
        this.driverAccountRepository = driverAccountRepository;
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
        appProperties.setEnabledToken(settingRepository.findById(Constants.ENABLED_TOKEN).map(Setting::getValue).orElse("").equals("true"));
        appProperties.setMix(!settingRepository.findById("mix_site_source").map(Setting::getValue).orElse("").equals("false"));
        appProperties.setSearchable(!settingRepository.findById("bilibili_searchable").map(Setting::getValue).orElse("").equals("false"));
        appProperties.setTgSearch(settingRepository.findById("tg_search").map(Setting::getValue).orElse(""));
        appProperties.setTgSortField(settingRepository.findById("tg_sort_field").map(Setting::getValue).orElse("time"));
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
        value = settingRepository.findById("tg_drivers").map(Setting::getValue).orElse("");
        if (StringUtils.isBlank(value)) {
            settingRepository.save(new Setting("tg_drivers", String.join(",", appProperties.getTgDrivers())));
        } else {
            appProperties.setTgDrivers(Arrays.asList(value.split(",")));
        }
        value = settingRepository.findById("tgDriverOrder").map(Setting::getValue).orElse("");
        if (StringUtils.isBlank(value)) {
            List<String> orders = new ArrayList<>(appProperties.getTgDrivers());
            for (int i = 0; i < appProperties.getTgDriverOrder().size(); i++) {
                if (i != 4 && !orders.contains(String.valueOf(i))) {
                    orders.add(String.valueOf(i));
                }
            }
            appProperties.setTgDriverOrder(orders);
            settingRepository.save(new Setting("tgDriverOrder", String.join(",", orders)));
        } else {
            appProperties.setTgDriverOrder(Arrays.asList(value.split(",")));
        }
        value = settingRepository.findById("tg_timeout").map(Setting::getValue).orElse("");
        if (StringUtils.isBlank(value)) {
            settingRepository.save(new Setting("tg_timeout", String.valueOf(appProperties.getTgTimeout())));
        } else {
            appProperties.setTgTimeout(Integer.parseInt(value));
        }
        value = settingRepository.findById("search_excluded_paths").map(Setting::getValue).orElse("");
        String old = "/电视剧/韩国,/电视剧/英国,/电视剧/港台,/电视剧/泰剧,/电视剧/欧美,/电视剧/日本,/电视剧/新加坡,/电视剧/中国/七米蓝";
        if (StringUtils.isBlank(value) || value.equals(old)) {
            value = "/电视剧/韩国,/电视剧/英国,/电视剧/港台,/电视剧/泰剧,/电视剧/欧美,/电视剧/日本,/电视剧/新加坡,/电视剧/俄罗斯/,/电视剧/法国/,/电视剧/德国/,/电视剧/中国/七米蓝";
            settingRepository.save(new Setting("search_excluded_paths", value));
        }
        appProperties.setExcludedPaths(Arrays.asList(value.split(",")));
        value = settingRepository.findById("system_id").map(Setting::getValue).orElse("");
        if (StringUtils.isBlank(value)) {
            value = UUID.randomUUID().toString();
            settingRepository.save(new Setting("system_id", value));
        }
        appProperties.setSystemId(value);
        log.info("system id: {}", value);
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
        //map.remove("api_key");
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
        if ("tg_drivers".equals(setting.getName())) {
            String value = StringUtils.isBlank(setting.getValue()) ? Constants.TG_DRIVERS : setting.getValue();
            setting.setValue(value);
            appProperties.setTgDrivers(Arrays.stream(value.split(",")).toList());
        }
        if ("tgDriverOrder".equals(setting.getName())) {
            String value = StringUtils.isBlank(setting.getValue()) ? Constants.TG_DRIVERS : setting.getValue();
            setting.setValue(value);
            appProperties.setTgDriverOrder(Arrays.stream(value.split(",")).toList());
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
        if ("tg_sort_field".equals(setting.getName())) {
            appProperties.setTgSortField(setting.getValue());
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

    public SearchSetting getSearchSetting() {
        SearchSetting searchSetting = new SearchSetting();
        searchSetting.setFiles(getIndexFiles());
        searchSetting.setSearchSources(getSearchSources());
        searchSetting.setExcludedPaths(settingRepository.findById("search_excluded_paths").map(Setting::getValue).orElse(""));
        return searchSetting;
    }

    public SearchSetting setSearchSetting(SearchSetting searchSetting) {
        setSearchSources(searchSetting.getSearchSources());
        setExcludedPaths(searchSetting.getExcludedPaths());
        return searchSetting;
    }

    private List<String> getIndexFiles() {
        List<String> list = new ArrayList<>();
        String base = Utils.getIndexPath() + "/";
        for (File file : Utils.listFiles(Utils.getIndexPath(), "txt")) {
            list.add(file.getAbsolutePath().replace(base, ""));
        }
        return list;
    }

    public List<String> getSearchSources() {
        List<String> sources = settingRepository.findById("search_index_source")
                .map(Setting::getValue)
                .map(e -> e.split(","))
                .map(Arrays::asList)
                .orElse(null);
        if (CollectionUtils.isEmpty(sources)) {
            sources = new ArrayList<>();
            Path index = Utils.getIndexPath("index.merged.txt");
            if (Files.exists(index)) {
                sources.add("index.merged.txt");
            } else {
                sources.add("index.video.txt");
            }

            if (driverAccountRepository.countByType(DriverType.PAN115) > 0) {
                Path index115 = Utils.getIndexPath("index.115.txt");
                if (Files.exists(index115)) {
                    sources.add("index.115.txt");
                }
            }
        } else {
            for (int i = 0; i < sources.size(); i++) {
                String source = sources.get(i);
                sources.set(i, source.replace("/data/index/", ""));
            }
        }
        log.debug("search sources: {}", sources);
        return sources;
    }

    private void setSearchSources(List<String> searchSources) {
        settingRepository.save(new Setting("search_index_source", String.join(",", searchSources)));
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        appProperties.setExcludedPaths(excludedPaths);
        settingRepository.save(new Setting("search_excluded_paths", String.join(",", excludedPaths)));
    }

    private void setExcludedPaths(String excludedPaths) {
        List<String> list = new ArrayList<>();
        for (String path : excludedPaths.split(",")) {
            path = path.trim();
            if (!path.startsWith("/")) {
                throw new BadRequestException("路径必须以/开头");
            }
            list.add(path);
        }
        appProperties.setExcludedPaths(list);
        settingRepository.save(new Setting("search_excluded_paths", String.join(",", list)));
    }
}
