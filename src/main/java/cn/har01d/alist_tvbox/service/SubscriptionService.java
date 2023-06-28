package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.TokenDto;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Subscription;
import cn.har01d.alist_tvbox.entity.SubscriptionRepository;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.IdUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.har01d.alist_tvbox.util.Constants.TOKEN;

@Slf4j
@Service
@SuppressWarnings("unchecked")
public class SubscriptionService {
    private final Environment environment;
    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SettingRepository settingRepository;
    private final SubscriptionRepository subscriptionRepository;

    private String token = "";

    public SubscriptionService(Environment environment, AppProperties appProperties, RestTemplateBuilder builder,
                               ObjectMapper objectMapper,
                               SettingRepository settingRepository,
                               SubscriptionRepository subscriptionRepository) {
        this.environment = environment;
        this.appProperties = appProperties;
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, Constants.ACCEPT)
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.OK_USER_AGENT)
                .build();
        this.objectMapper = objectMapper;
        this.settingRepository = settingRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    @PostConstruct
    public void init() {
        token = settingRepository.findById(TOKEN)
                .map(Setting::getValue)
                .orElse("");

        if (subscriptionRepository.count() == 0) {
            Subscription sub = new Subscription();
            sub.setName("饭太硬");
            sub.setUrl("http://饭太硬.top/tv");
            subscriptionRepository.save(sub);
            sub = new Subscription();
            sub.setName("菜妮丝");
            sub.setUrl("https://tvbox.cainisi.cf");
            subscriptionRepository.save(sub);
        }
    }

    public String getToken() {
        return token;
    }

    public void deleteToken() {
        token = "";
        settingRepository.save(new Setting(TOKEN, token));
    }

    public String createToken(TokenDto dto) {
        if (StringUtils.isBlank(dto.getToken())) {
            token = IdUtils.generate(8);
        } else {
            token = dto.getToken();
        }

        settingRepository.save(new Setting(TOKEN, token));
        return token;
    }

    public List<String> getProfiles() {
        return Arrays.asList(environment.getActiveProfiles());
    }

    public List<Subscription> findAll() {
        List<Subscription> list = subscriptionRepository.findAll();
        Subscription sub = new Subscription();
        sub.setId(0);
        sub.setName("默认");
        sub.setUrl("");
        list.add(0, sub);
        return list;
    }

    public Map<String, Object> subscription(int id) {
        String apiUrl = "";
        String override = "";
        if (id > 0) {
            Subscription subscription = subscriptionRepository.findById(id).orElseThrow(NotFoundException::new);
            apiUrl = subscription.getUrl();
            override = subscription.getOverride();
        }

        return subscription(apiUrl, override);
    }

    public Map<String, Object> subscription(String apiUrl, String override) {
        Map<String, Object> config = new HashMap<>();
        for (String url : apiUrl.split(",")) {
            String[] parts = url.split("@", 2);
            String prefix = "";
            if (parts.length == 2) {
                prefix = parts[0].trim() + "@";
                url = parts[1].trim();
            }
            overrideConfig(config, fixUrl(url.trim()), prefix, getConfigData(url.trim()));
        }

        sortSites(config);

        if (StringUtils.isNotBlank(override)) {
            overrideConfig(config, override);
        }

        // should after overrideConfig
        handleWhitelist(config);
        removeBlacklist(config);

        addSite(config);
        addRules(config);

        return config;
    }

    private void sortSites(Map<String, Object> config) {
        List<Map<String, String>> list = (List<Map<String, String>>) config.get("sites");
        list.sort(Comparator.comparing(a -> a.get("name")));
    }

    private Map<String, Object> getConfigData(String apiUrl) {
        String configKey = null;
        String configUrl = apiUrl;
        String pk = ";pk;";
        if (apiUrl != null && apiUrl.contains(pk)) {
            String[] a = apiUrl.split(pk);
            configUrl = a[0];
            configKey = a[1];
        }
        if (StringUtils.isNotBlank(configUrl) && !configUrl.startsWith("http")) {
            configUrl = "http://" + configUrl;
        }

        String json = loadConfigJson(configUrl);

        return convertResult(json, configKey);
    }

    private void handleWhitelist(Map<String, Object> config) {
        try {
            Object obj1 = config.get("sites-whitelist");
            Object obj2 = config.get("sites");
            if (obj1 instanceof List && obj2 instanceof List) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) obj2;
                Set<String> set = new HashSet<>((List<String>) obj1);
                list = list.stream().filter(e -> set.contains(e.get("key"))).collect(Collectors.toList());
                config.put("sites", list);
            }
            config.remove("sites-whitelist");
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void removeBlacklist(Map<String, Object> config) {
        try {
            Object obj1 = config.get("sites-blacklist");
            Object obj2 = config.get("sites");
            if (obj1 instanceof List && obj2 instanceof List) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) obj2;
                Set<String> set = new HashSet<>((List<String>) obj1);
                list = list.stream().filter(e -> !set.contains(e.get("key"))).collect(Collectors.toList());
                config.put("sites", list);
                log.info("remove sites: {}", set);
            }
            config.remove("sites-blacklist");
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void overrideConfig(Map<String, Object> config, String json) {
        try {
            json = Pattern.compile("^\\s*#.*\n?", Pattern.MULTILINE).matcher(json).replaceAll("");
            json = Pattern.compile("^\\s*//.*\n?", Pattern.MULTILINE).matcher(json).replaceAll("");
            Map<String, Object> override = objectMapper.readValue(json, Map.class);
            overrideConfig(config, "", "", override);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private static void overrideConfig(Map<String, Object> config, String url, String prefix, Map<String, Object> override) {
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            try {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof Collection) {
                    String keyName = "name";
                    String spider = null;
                    if ("sites".equals(key)) {
                        keyName = "key";
                        spider = (String) override.get("spider");
                        if (StringUtils.isBlank(spider)) {
                            spider = (String) config.get("spider");
                        }
                        if (StringUtils.isNotBlank(spider) && StringUtils.isNotBlank(url)) {
                            if (spider.startsWith("./")) {
                                spider = url + spider.substring(1);
                            } else if (spider.startsWith("/")) {
                                spider = getRoot(url) + spider;
                            } else if (!spider.startsWith("http")) {
                                spider = url + spider;
                            }
                        }
                    }
                    overrideList(config, override, prefix, spider, key, keyName);
                } else {
                    config.put(key, value);
                }
            } catch (Exception e) {
                log.warn("", e);
            }
        }
    }

    private static String fixUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return url;
        }

        int index = url.lastIndexOf('/');
        String file = url.substring(index + 1);
        if (file.contains(".")) {
            return url.substring(0, index);
        }
        return url;
    }

    private static String getRoot(String path) {
        try {
            URL url = new URL(path);
            return url.getProtocol() + "://" + url.getHost();
        } catch (MalformedURLException e) {
            log.warn("", e);
        }
        return "";
    }

    private static void overrideList(Map<String, Object> config, Map<String, Object> override, String prefix, String spider, String name, String keyName) {
        try {
            List<Object> overrideList = (List<Object>) override.get(name);
            Object obj = config.get(name);
            if (obj == null) {
                if (name.equals("sites")) {
                    List<Map<String, Object>> sites = (List<Map<String, Object>>) override.get(name);
                    for (Map<String, Object> site : sites) {
                        site.put("name", prefix + site.get("name").toString());
                        if (StringUtils.isNotBlank(spider) && site.get("jar") == null && site.get("type").equals(3)) {
                            String api = (String) site.get("api");
                            if (!api.startsWith("http")) {
                                site.put("jar", spider);
                            }
                        }
                    }
                }
                config.put(name, overrideList);
            } else if (obj instanceof List) {
                List<Object> list = (List<Object>) config.get(name);
                if ((list.isEmpty() || list.get(0) instanceof String) || (overrideList.isEmpty() || overrideList.get(0) instanceof String)) {
                    list.addAll(overrideList);
                } else {
                    List<Map<String, Object>> configList = (List<Map<String, Object>>) config.get(name);
                    overrideCollection(configList, (List<Map<String, Object>>) override.get(name), prefix, spider, name, keyName);
                }
            } else {
                log.warn("type not match: {} {}", name, obj);
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private static void overrideCollection(List<Map<String, Object>> configList, List<Map<String, Object>> overrideList, String prefix, String spider, String name, String keyName) {
        Map<Object, Map<String, Object>> map = new HashMap<>();
        for (Map<String, Object> site : configList) {
            Object key = site.get(keyName);
            if (key != null) {
                map.put(key, site);
            }
        }

        int index = 0;
        for (Map<String, Object> site : overrideList) {
            Object key = site.get(keyName);
            if (key != null) {
                Map<String, Object> original = map.get(key);
                if (name.equals("sites")) {
                    site.put("name", prefix + site.get("name").toString());
                }

                if (original != null) {
                    original.putAll(site);
                    log.debug("override {}: {}", name, key);
                } else {
                    configList.add(index++, site);
                    log.debug("add {}: {}", name, key);
                }

                if (StringUtils.isNotBlank(spider) && site.get("jar") == null
                        && site.get("type") != null && site.get("type").equals(3)) {
                    String api = (String) site.get("api");
                    if (!api.startsWith("http")) {
                        site.put("jar", spider);
                    }
                }
            }
        }
    }

    private void addSite(Map<String, Object> config) {
        String key = "Alist";
        Map<String, Object> site = buildSite(key);
        List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
        sites.removeIf(item -> key.equals(item.get("key")));
        sites.add(0, site);
        log.debug("add AList site: {}", site);
    }

    private Map<String, Object> buildSite(String key) {
        Map<String, Object> site = new HashMap<>();
        ServletUriComponentsBuilder builder = ServletUriComponentsBuilder.fromCurrentRequestUri();
        builder.replacePath("/vod" + (StringUtils.isNotBlank(token) ? "/" + token : ""));
        site.put("key", key);
        site.put("name", "AList");
        site.put("type", 1);
        site.put("api", builder.build().toUriString());
        site.put("searchable", 1);
        site.put("quickSearch", 1);
        site.put("filterable", 1);
        return site;
    }

    private static void addRules(Map<String, Object> config) {
        List<Map<String, Object>> rules = (List<Map<String, Object>>) config.get("rules");
        if (rules == null) {
            rules = new ArrayList<>();
            config.put("rules", rules);
        }
        Map<String, Object> rule = new HashMap<>();
        rule.put("host", "pdsapi.aliyundrive.com");
        rule.put("rule", Collections.singletonList("/redirect"));
        rules.add(rule);

        rule = new HashMap<>();
        rule.put("host", "*");
        rule.put("rule", Collections.singletonList("http((?!http).){12,}?\\\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a)\\\\?.*"));
        rules.add(rule);

        rule = new HashMap<>();
        rule.put("host", "*");
        rule.put("rule", Collections.singletonList("http((?!http).){12,}\\\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a)"));
        rules.add(rule);
    }

    private String loadConfigJson(String url) {
        if (url == null || url.isEmpty()) {
            if (appProperties.isXiaoya()) {
                return loadConfigJsonXiaoya();
            }
            return null;
        }

        try {
            log.info("load json from {}", url);
            return restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            log.warn("load config json failed", e);
            return null;
        }
    }

    private String loadConfigJsonXiaoya() {
        try {
            File file = new File("/www/tvbox/my.json");
            if (file.exists()) {
                String json = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
                String address = readHostAddress();
                json = json.replaceAll("DOCKER_ADDRESS", address);
                return json;
            }
        } catch (IOException e) {
            log.warn("", e);
            return null;
        }
        return null;
    }

    private static String readHostAddress() throws IOException {
        String address;
        File file = new File("/data/docker_address.txt");
        if (file.exists()) {
            address = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
        } else {
            address = ServletUriComponentsBuilder.fromCurrentRequest().port(5244).replacePath("/").build().toUriString();
        }

        if (address.endsWith("/")) {
            address = address.substring(0, address.length() - 1);
        }

        return address;
    }

    public Map<String, Object> convertResult(String json, String configKey) {
        Map<String, Object> map = new HashMap<>();
        map.put("sites", new ArrayList<>());
        map.put("rules", new ArrayList<>());
        if (json == null || json.isEmpty()) {
            return map;
        }

        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            // ignore
        }

        try {
            String content = json;
            Pattern pattern = Pattern.compile("[A-Za-z0]{8}\\*\\*");
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                content = content.substring(content.indexOf(matcher.group()) + 10);
                content = new String(Base64.getDecoder().decode(content));
            }

            if (content.startsWith("2423")) {
                String data = content.substring(content.indexOf("2324") + 4, content.length() - 26);
                content = new String(toBytes(content)).toLowerCase();
                String key = rightPadding(content.substring(content.indexOf("$#") + 2, content.indexOf("#$")), "0", 16);
                String iv = rightPadding(content.substring(content.length() - 13), "0", 16);
                json = CBC(data, key, iv);
            } else if (configKey != null) {
                try {
                    return objectMapper.readValue(json, Map.class);
                } catch (Exception e) {
                    // ignore
                }
                json = ECB(content, configKey);
            } else {
                json = content;
            }

            int index = json.indexOf('{');
            if (index > 0) {
                json = json.substring(index);
            }

            json = Pattern.compile("^\\s*#.*\n?", Pattern.MULTILINE).matcher(json).replaceAll("");
            json = Pattern.compile("^\\s*//.*\n?", Pattern.MULTILINE).matcher(json).replaceAll("");

            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("", e);
            return map;
        }
    }

    public static String rightPadding(String key, String replace, int Length) {
        String strReturn;
        int curLength = key.trim().length();
        if (curLength > Length) {
            strReturn = key.trim().substring(0, Length);
        } else if (curLength == Length) {
            strReturn = key.trim();
        } else {
            strReturn = key.trim() + String.valueOf(replace).repeat((Length - curLength));
        }
        return strReturn;
    }

    public static String ECB(String data, String key) {
        try {
            key = rightPadding(key, "0", 16);
            byte[] data2 = toBytes(data);
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS7Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return new String(cipher.doFinal(data2));
        } catch (Exception e) {
            log.warn("", e);
        }
        return null;
    }

    public static String CBC(String data, String key, String iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "AES");
            AlgorithmParameterSpec paramSpec = new IvParameterSpec(iv.getBytes());
            cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec);
            return new String(cipher.doFinal(toBytes(data)));
        } catch (Exception e) {
            log.warn("", e);
        }
        return null;
    }

    private static byte[] toBytes(String src) {
        int l = src.length() / 2;
        byte[] ret = new byte[l];
        for (int i = 0; i < l; i++) {
            ret[i] = Integer.valueOf(src.substring(i * 2, i * 2 + 2), 16).byteValue();
        }
        return ret;
    }

}
