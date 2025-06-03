package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.AliTokensResponse;
import cn.har01d.alist_tvbox.model.SettingResponse;
import cn.har01d.alist_tvbox.storage.Storage;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static cn.har01d.alist_tvbox.util.Constants.ALIST_RESTART_REQUIRED;
import static cn.har01d.alist_tvbox.util.Constants.ALIST_START_TIME;

@Slf4j
@Service

public class AListLocalService {

    private final SettingRepository settingRepository;
    private final SiteRepository siteRepository;
    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final Environment environment;

    private volatile int aListStatus;

    public AListLocalService(SettingRepository settingRepository, SiteRepository siteRepository, AppProperties appProperties, RestTemplateBuilder builder, Environment environment) {
        this.settingRepository = settingRepository;
        this.siteRepository = siteRepository;
        this.appProperties = appProperties;
        this.environment = environment;
        this.restTemplate = builder.rootUri("http://localhost:" + (appProperties.isHostmode() ? "5234" : "5244")).build();
    }

    @PostConstruct
    public void setup() {
        String port = appProperties.isHostmode() ? "5234" : environment.getProperty("ALIST_PORT", "5344");
        setSetting("external_port", port, "number");
        String url = settingRepository.findById("open_token_url").map(Setting::getValue).orElse("https://api.xhofe.top/alist/ali_open/token");
        setSetting("open_token_url", url, "string");
        String apiKey = settingRepository.findById("api_key").map(Setting::getValue).orElse("");
        setSetting("atv_api_key", apiKey, "string");
        String clientId = settingRepository.findById("open_api_client_id").map(Setting::getValue).orElse("");
        setSetting("open_api_client_id", clientId, "string");
        String clientSecret = settingRepository.findById("open_api_client_secret").map(Setting::getValue).orElse("");
        setSetting("open_api_client_secret", clientSecret, "string");
        String token = settingRepository.findById("token").map(Setting::getValue).orElse("");
        Utils.executeUpdate("UPDATE x_setting_items SET value = '" + StringUtils.isNotBlank(token) + "' WHERE key = 'sign_all'");
        String code = settingRepository.findById("delete_code_115").map(Setting::getValue).orElse("");
        setSetting("delete_code_115", code, "string");
        String time = settingRepository.findById("delete_delay_time").map(Setting::getValue).orElse("900");
        setSetting("delete_delay_time", time, "number");
        String aliTo115 = settingRepository.findById("ali_to_115").map(Setting::getValue).orElse("false");
        setSetting("ali_to_115", aliTo115, "bool");
        String roundRobin = settingRepository.findById("driver_round_robin").map(Setting::getValue).orElse("false");
        setSetting("driver_round_robin", roundRobin, "bool");
        String lazy = settingRepository.findById("ali_lazy_load").map(Setting::getValue).orElse("true");
        setSetting("ali_lazy_load", lazy, "bool");
    }

    public void setSetting(String key, String value, String type) {
        log.debug("set setting {}={}", key, value);
        Utils.executeUpdate(String.format("INSERT INTO x_setting_items (key,value,type,flag,\"group\") VALUES('%s','%s','%s',1,0)", key, value, type));
        int code = Utils.executeUpdate(String.format("UPDATE x_setting_items SET value = '%s' WHERE key = '%s'", value, key));
        log.info("update setting by SQL: {} {}", key, code);
    }

    public void updateSetting(String key, String value, String type) {
        if (checkStatus() >= 2) {
            log.debug("update setting {}={}", key, value);
            Map<String, Object> body = new HashMap<>();
            body.put("key", key);
            body.put("value", value);
            body.put("type", type);
            body.put("flag", 1);
            body.put("group", 0);
            body.put("help", "");
            body.put("options", "");
            HttpHeaders headers = new HttpHeaders();
            Site site = siteRepository.findById(1).orElseThrow();
            headers.set(HttpHeaders.AUTHORIZATION, site.getToken());
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            SettingResponse response = restTemplate.postForObject("/api/admin/setting/update", entity, SettingResponse.class);
            log.debug("update setting by API: {}", response);
        } else {
            setSetting(key, value, type);
        }
    }

    public SettingResponse getSetting(String key) {
        HttpHeaders headers = new HttpHeaders();
        Site site = siteRepository.findById(1).orElseThrow();
        headers.set(HttpHeaders.AUTHORIZATION, site.getToken());
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(null, headers);
        String url = "/api/admin/setting/get?key=" + key;
        ResponseEntity<SettingResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, SettingResponse.class);
        return response.getBody();
    }

    public void saveStorage(Storage storage) {
        String time = storage.getTime().atZone(ZoneId.systemDefault()).toLocalDateTime().toString();
        String sql = "INSERT INTO x_storages " +
                "(id,mount_path,\"order\",driver,cache_expiration,status,addition,modified,disabled,order_by,order_direction,extract_folder,web_proxy,webdav_policy) " +
                "VALUES (%d,'%s',0,'%s',%d,'work','%s','%s',%d,'name','asc','front',%d,'%s');";
        int code = Utils.executeUpdate(String.format(sql, storage.getId(), storage.getPath(), storage.getDriver(),
                storage.getCacheExpiration(), storage.getAddition(), time, storage.isDisabled() ? 1 : 0, storage.isWebProxy() ? 1 : 0, storage.getWebdavPolicy()));
        log.info("[{}] insert {} storage : {} result: {}", storage.getId(), storage.getDriver(), storage.getPath(), code);
    }

    public void setToken(Integer accountId, String key, String value) {
        if (StringUtils.isEmpty(value)) {
            log.warn("Token is empty: {} {} ", accountId, key);
            return;
        }
        String sql = "INSERT INTO x_tokens VALUES('%s','%s',%d,'%s')";
        Utils.executeUpdate(String.format(sql, key, value, accountId, OffsetDateTime.now()));
    }

    public void updateToken(Integer accountId, String key, String value) {
        if (StringUtils.isEmpty(value)) {
            log.warn("Token is empty: {} {} ", accountId, key);
            return;
        }
        if (checkStatus() >= 2) {
            String token = siteRepository.findById(1).orElseThrow().getToken();
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, token);
            Map<String, Object> body = new HashMap<>();
            body.put("key", key);
            body.put("value", value);
            body.put("accountId", accountId);
            body.put("modified", OffsetDateTime.now().toString());
            log.debug("updateTokenToAList: {}", body);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange("/api/admin/token/update", HttpMethod.POST, entity, String.class);
            log.debug("updateTokenToAList {} response: {}", key, response.getBody());
        } else {
            String sql = "INSERT INTO x_tokens VALUES('%s','%s',%d,'%s')";
            Utils.executeUpdate(String.format(sql, key, value, accountId, OffsetDateTime.now()));
        }
    }

    public AliTokensResponse getTokens() {
        try {
            String token = siteRepository.findById(1).orElseThrow().getToken();
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, token);
            HttpEntity<String> entity = new HttpEntity<>(null, headers);
            ResponseEntity<AliTokensResponse> response = restTemplate.exchange("/api/admin/token/list", HttpMethod.GET, entity, AliTokensResponse.class);
            log.trace("getTokens response: {}", response.getBody().getData());
            return response.getBody();
        } catch (Exception e) {
            log.warn("", e);
        }
        return new AliTokensResponse();
    }

    public void startAListServer() {
        if (aListStatus > 0) {
            return;
        }

        try {
            log.info("start AList server");
            ProcessBuilder builder = new ProcessBuilder();
            File outFile = new File("/opt/atv/log/app.log");
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile));
            builder.redirectError(ProcessBuilder.Redirect.appendTo(outFile));
            boolean debug = settingRepository.findById("alist_debug").map(Setting::getValue).orElse("").equals("true");
            if (debug) {
                builder.command("/opt/alist/alist", "server", "--no-prefix", "--debug");
            } else {
                builder.command("/opt/alist/alist", "server", "--no-prefix");
            }
            builder.directory(new File("/opt/alist"));
            Process process = builder.start();
            settingRepository.save(new Setting(ALIST_RESTART_REQUIRED, "false"));
            settingRepository.save(new Setting(ALIST_START_TIME, Instant.now().toString()));
            log.info("AList server starting, PID: {}", process.pid());
            aListStatus = 1;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void stopAListServer() {
        log.info("stop AList server");
        try {
            ProcessBuilder builder = new ProcessBuilder();
            File outFile = new File("/opt/atv/log/app.log");
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile));
            builder.redirectError(ProcessBuilder.Redirect.appendTo(outFile));
            builder.command("pkill", "-f", "/opt/alist/alist");
            builder.directory(new File("/opt/alist"));
            Process process = builder.start();
            process.waitFor(1, TimeUnit.SECONDS);
            aListStatus = 0;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void restartAListServer() {
        stopAListServer();
        startAListServer();
    }

    public void validateAListStatus() {
        checkStatus();
        if (aListStatus == 1) {
            throw new BadRequestException("AList服务启动中");
        }
        if (aListStatus == 0) {
            throw new BadRequestException("AList服务未启动");
        }
    }

    public int checkStatus() {
        try {
            ResponseEntity<SettingResponse> response = restTemplate.getForEntity("/api/public/settings", SettingResponse.class);
            if (response.getBody() != null) {
                if (response.getBody().getCode() == 200) {
                    aListStatus = 2;
                    return 2;
                } else if (response.getBody().getCode() == 500) {
                    aListStatus = 1;
                    return 1;
                }
            }
        } catch (Exception e) {
            log.warn("{}", e.getMessage());
        }
        aListStatus = 0;
        return 0;
    }

    public int getStatus() {
        return aListStatus;
    }

    public void updateStatus(int status) {
        aListStatus = status;
    }
}
