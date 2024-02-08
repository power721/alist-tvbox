package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SettingService {
    private final JdbcTemplate jdbcTemplate;
    private final AppProperties appProperties;
    private final TmdbService tmdbService;
    private final SettingRepository settingRepository;

    public SettingService(JdbcTemplate jdbcTemplate, AppProperties appProperties, TmdbService tmdbService, SettingRepository settingRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.appProperties = appProperties;
        this.tmdbService = tmdbService;
        this.settingRepository = settingRepository;
    }

    @PostConstruct
    public void setup() {
        appProperties.setMerge(settingRepository.findById("merge_site_source").map(Setting::getValue).orElse("").equals("true"));
        appProperties.setHeartbeat(settingRepository.findById("bilibili_heartbeat").map(Setting::getValue).orElse("").equals("true"));
        appProperties.setSupportDash(settingRepository.findById("bilibili_dash").map(Setting::getValue).orElse("").equals("true"));
        appProperties.setReplaceAliToken(settingRepository.findById("replace_ali_token").map(Setting::getValue).orElse("").equals("true"));
        appProperties.setEnableHttps(settingRepository.findById("enable_https").map(Setting::getValue).orElse("").equals("true"));
        appProperties.setMix(!settingRepository.findById("mix_site_source").map(Setting::getValue).orElse("").equals("false"));
        appProperties.setSearchable(!settingRepository.findById("bilibili_searchable").map(Setting::getValue).orElse("").equals("false"));
    }

    public void exportDatabase() {
        jdbcTemplate.execute("SCRIPT TO '/data/data-h2.sql' TABLE ACCOUNT, ALIST_ALIAS, CONFIG_FILE, INDEX_TEMPLATE, PIK_PAK_ACCOUNT, SETTING, SHARE, SITE, SUBSCRIPTION, USERS");
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
        return settingRepository.save(setting);
    }

}
