package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.TokenDto;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponents;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.har01d.alist_tvbox.util.Constants.ALI_SECRET;
import static cn.har01d.alist_tvbox.util.Constants.TOKEN;

@Slf4j
@Service
@SuppressWarnings("unchecked")
public class SubscriptionService {
    private final Environment environment;
    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SettingRepository settingRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SiteRepository siteRepository;

    private String token = "";

    public SubscriptionService(Environment environment,
                               AppProperties appProperties,
                               RestTemplateBuilder builder,
                               ObjectMapper objectMapper,
                               JdbcTemplate jdbcTemplate,
                               SettingRepository settingRepository,
                               SubscriptionRepository subscriptionRepository,
                               SiteRepository siteRepository) {
        this.environment = environment;
        this.appProperties = appProperties;
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, Constants.ACCEPT)
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.OK_USER_AGENT)
                .build();
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.settingRepository = settingRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.siteRepository = siteRepository;
    }

    @PostConstruct
    public void init() {
        token = settingRepository.findById(TOKEN)
                .map(Setting::getValue)
                .orElse("");

        List<Subscription> list = subscriptionRepository.findAll();
        if (list.isEmpty()) {
            Subscription sub = new Subscription();
            sub.setName("饭太硬");
            sub.setUrl("http://饭太硬.top/tv");
            subscriptionRepository.save(sub);
            sub = new Subscription();
            sub.setName("菜妮丝");
            sub.setUrl("https://tvbox.cainisi.cf");
            subscriptionRepository.save(sub);
        } else {
            fixId(list);
        }
    }

    private void fixId(List<Subscription> list) {
        String fixed = settingRepository.findById("fix_sub_id").map(Setting::getValue).orElse(null);
        if (fixed == null) {
            log.warn("fix subscription id");
            int id = 1;
            int max = 1;

            for (var item : list) {
                max = Math.max(max, item.getId());
                item.setId(id++);
            }

            if (max > list.size()) {
                subscriptionRepository.deleteAll();
                jdbcTemplate.execute("update id_generator set next_id=0 where entity_name = 'subscription';");
                subscriptionRepository.saveAll(list);
            }
            jdbcTemplate.execute("update id_generator set next_id=" + list.size() + " where entity_name = 'subscription';");
            settingRepository.save(new Setting("fix_sub_id", "true"));
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
        String sort = "";
        if (id > 0) {
            Subscription subscription = subscriptionRepository.findById(id).orElseThrow(NotFoundException::new);
            apiUrl = subscription.getUrl();
            override = subscription.getOverride();
            sort = subscription.getSort();
        }

        return subscription(apiUrl, override, sort);
    }

    public Map<String, Object> subscription(String apiUrl, String override, String sort) {
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

        sortSites(config, sort);

        if (StringUtils.isNotBlank(override)) {
            config = overrideConfig(config, override);
        }

        // should after overrideConfig
        handleWhitelist(config);
        removeBlacklist(config);

        try {
            replaceAliToken(config);
        } catch (Exception e) {
            log.warn("", e);
        }

        addSite(config);
        addRules(config);

        return config;
    }

    private void replaceAliToken(Map<String, Object> config) {
        if (!appProperties.isXiaoya()) {
            return;
        }
        List<Map<String, Object>> list = (List<Map<String, Object>>) config.get("sites");
        String path = "/ali/token/" + settingRepository.findById(ALI_SECRET).map(Setting::getValue).orElseThrow();
        String tokenUrl = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .replacePath(path)
                .replaceQuery("")
                .toUriString();
        for (Map<String, Object> site : list) {
            Object obj = site.get("ext");
            if (obj instanceof String) {
                String ext = (String) obj;
                String text = ext.replace("http://127.0.0.1:9978/file/tvfan/token.txt", tokenUrl)
                        .replace("http://127.0.0.1:9978/file/tvfan/tokengo.txt", tokenUrl)
                        .replace("http://127.0.0.1:9978/file/tvbox/token.txt", tokenUrl)
                        .replace("http://127.0.0.1:9978/file/cainisi/token.txt", tokenUrl)
                        .replace("http://127.0.0.1:9978/file/fatcat/token.txt", tokenUrl);
                if (!ext.equals(text)) {
                    log.info("replace token url in ext: {}", ext);
                    site.put("ext", text);
                }
            }
        }
    }

    private void sortSites(Map<String, Object> config, String sort) {
        List<Map<String, String>> list = (List<Map<String, String>>) config.get("sites");
        if (StringUtils.isNotBlank(sort)) {
            log.info("sort by filed {}", sort);
            list.sort(Comparator.comparing(a -> a.get(sort)));
        }
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
            if (obj1 == null) {
                obj1 = new ArrayList<String>();
            }
            Object obj2 = config.get("sites");
            if (obj1 instanceof List && obj2 instanceof List) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) obj2;
                Set<String> set = new HashSet<>((List<String>) obj1);
                set.add("Alist1");
                list = list.stream().filter(e -> !set.contains(e.get("key"))).collect(Collectors.toList());
                config.put("sites", list);
                log.info("remove sites: {}", set);
            }
            config.remove("sites-blacklist");
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private Map<String, Object> overrideConfig(Map<String, Object> config, String json) {
        try {
            json = Pattern.compile("^\\s*#.*\n?", Pattern.MULTILINE).matcher(json).replaceAll("");
            json = Pattern.compile("^\\s*//.*\n?", Pattern.MULTILINE).matcher(json).replaceAll("");
            json = json.replace("DOCKER_ADDRESS", readAlistAddress());
            json = json.replace("ATV_ADDRESS", readHostAddress());
            Map<String, Object> override = objectMapper.readValue(json, Map.class);
            overrideConfig(config, "", "", override);
            return replaceString(config, override);
        } catch (Exception e) {
            log.warn("", e);
        }
        return config;
    }

    private Map<String, Object> replaceString(Map<String, Object> config, Map<String, Object> override) {
        try {
            Object obj = override.get("replace");
            if (obj instanceof Map) {
                config.remove("replace");
                Map<Object, Object> replace = (Map<Object, Object>) obj;
                String configJson = objectMapper.writeValueAsString(config);
                for (Map.Entry<Object, Object> entry : replace.entrySet()) {
                    if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                        String key = (String) entry.getKey();
                        String value = (String) entry.getValue();
                        log.info("replace text '{}' by '{}'", key, value);
                        configJson = configJson.replace(key, value);
                    }
                }
                return objectMapper.readValue(configJson, Map.class);
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        return config;
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
                    String siteName = (String) site.get("name");
                    if (StringUtils.isNotBlank(siteName)) {
                        site.put("name", prefix + siteName);
                    }
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
                    if (api != null && !api.startsWith("http")) {
                        site.put("jar", spider);
                        log.debug("replace jar {}", spider);
                    }
                }
            }
        }
    }

    private void addSite(Map<String, Object> config) {
        int id = 0;
        List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
        try {
            String key = "Alist";
            Map<String, Object> site = buildSite("csp_AList", "AList");
            sites.removeIf(item -> key.equals(item.get("key")));
            sites.add(id++, site);
            log.debug("add AList site: {}", site);
        } catch (Exception e) {
            log.warn("", e);
        }

        if (appProperties.isXiaoya()) {
            try {
                for (Site site1 : siteRepository.findAll()) {
                    if (site1.isSearchable() && !site1.isDisabled()) {
                        Map<String, Object> site = buildSite("csp_XiaoYa", site1.getName());
                        sites.add(id++, site);
                        log.debug("add XiaoYa site: {}", site);
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("", e);
            }
        }

        if (appProperties.isXiaoya()) {
            try {
                Map<String, Object> site = buildSite("csp_BiliBili", "BiliBili");
                sites.add(id, site);
                log.debug("add BiliBili site: {}", site);
            } catch (Exception e) {
                log.warn("", e);
            }
        }
    }

    private Map<String, Object> buildSite(String key, String name) throws IOException {
        Map<String, Object> site = new HashMap<>();
        ServletUriComponentsBuilder builder = ServletUriComponentsBuilder.fromCurrentRequestUri();
        builder.replacePath("");
        site.put("key", key);
        site.put("api", key);
        site.put("name", name);
        site.put("type", 3);
        Map<String, String> map = new HashMap<>();
        map.put("api", builder.build().toUriString());
        map.put("apiKey", settingRepository.findById("api_key").map(Setting::getValue).orElse(""));
        String ext = objectMapper.writeValueAsString(map).replaceAll("\\s", "");
        ext = Base64.getEncoder().encodeToString(ext.getBytes());
        site.put("ext", ext);
        String jar = builder.build().toUriString() + "/spring.jar";
        site.put("jar", jar);
        site.put("changeable", 0);
        site.put("searchable", 1);
        site.put("quickSearch", 1);
        site.put("filterable", 1);
        return site;
    }

    private static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(text.getBytes());
            byte[] digest = md.digest();
            return DatatypeConverter.printHexBinary(digest).toLowerCase();
        } catch (Exception e) {
            log.warn("", e);
        }
        return text;
    }

    private static void addRules(Map<String, Object> config) {
        List<Map<String, Object>> rules = (List<Map<String, Object>>) config.get("rules");
        if (rules == null) {
            rules = new ArrayList<>();
            config.put("rules", rules);
        }

        Map<String, Object> rule = new HashMap<>();
        rule.put("name", "阿里云盘");
        rule.put("hosts", List.of("pdsapi.aliyundrive.com"));
        rule.put("regex", List.of("/redirect"));

        rule.put("host", "pdsapi.aliyundrive.com");
        rule.put("rule", List.of("/redirect"));
        rules.add(rule);

        rule = new HashMap<>();
        rule.put("name", "阿里云");
        rule.put("hosts", List.of("aliyundrive.net"));
        rule.put("regex", List.of("http((?!http).){12,}?\\\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a)\\\\?.*", "http((?!http).){12,}\\\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a)"));

        rule.put("host", "aliyundrive.net");
        rule.put("rule", List.of("http((?!http).){12,}?\\\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a)\\\\?.*", "http((?!http).){12,}\\\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a)"));
        rules.add(rule);

        rule = new HashMap<>();
        rule.put("name", "BiliBili");
        rule.put("hosts", List.of("bilivideo.cn"));
        rule.put("regex", List.of("https://.+bilivideo.cn.+\\.(mp4|m4s|m4a)\\?.*"));

        rule.put("host", "bilivideo.cn");
        rule.put("rule", List.of("https://.+bilivideo.cn.+\\.(mp4|m4s|m4a)\\?.*"));
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
                json = json.replaceAll("DOCKER_ADDRESS", readAlistAddress());
                json = json.replaceAll("ATV_ADDRESS", readHostAddress());
                return json;
            }
        } catch (IOException e) {
            log.warn("", e);
            return null;
        }
        return null;
    }

    private Integer getAlistPort() {
        if (appProperties.isHostmode()) {
            return 6789;
        }
        try {
            return Integer.parseInt(environment.getProperty("ALIST_PORT", "5344"));
        } catch (Exception e) {
            return 5344;
        }
    }

    private String readAlistAddress() throws IOException {
        UriComponents uriComponents = ServletUriComponentsBuilder.fromCurrentRequest()
                .port(getAlistPort())
                .replacePath("/")
                .build();
        String address = null;
        if (!isIntranet(uriComponents)) {
            address = getAddress();
            if (StringUtils.isBlank(address)) {
                File file = new File("/data/docker_address.txt");
                if (file.exists()) {
                    address = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
                    if (StringUtils.isNotBlank(address)) {
                        settingRepository.save(new Setting("docker_address", address));
                    }
                }
            }
        }

        if (StringUtils.isBlank(address)) {
            address = uriComponents.toUriString();
        }

        if (address.endsWith("/")) {
            address = address.substring(0, address.length() - 1);
        }

        return address;
    }

    private String readHostAddress() {
        return readHostAddress("");
    }

    private String readHostAddress(String path) {
        UriComponents uriComponents = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath(path)
                .build();
        return uriComponents.toUriString();
    }

    private boolean isIntranet(UriComponents uriComponents) {
        try {
            String host = uriComponents.getHost();
            return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.startsWith("192.168."));
        } catch (Exception e) {
            log.warn("{}", e.getMessage());
        }
        return false;
    }

    private String getAddress() {
        return settingRepository.findById("docker_address").map(Setting::getValue).orElse(null);
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

    public String repository(int id) {
        try {
            File file = new File("/www/tvbox/juhe.json");
            if (file.exists()) {
                String json = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
                String url = readHostAddress("/sub" + (StringUtils.isNotBlank(token) ? "/" + token : "") + "/" + id);
                json = json.replace("DOCKER_ADDRESS/tvbox/my.json", url);
                return json;
            }
        } catch (IOException e) {
            log.warn("", e);
            return null;
        }
        return null;
    }
}
