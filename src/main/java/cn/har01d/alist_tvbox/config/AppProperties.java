package cn.har01d.alist_tvbox.config;

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
    private int pageSize = 100;
    private int maxSearchResult = 60;
    private String secretKey;
    private List<String> qns = List.of();
    private List<String> tgDrivers = Arrays.asList(Constants.TG_DRIVERS.split(","));
    private List<String> tgDriverOrder = Arrays.asList(Constants.TG_DRIVERS.split(","));
    private String userAgent = Constants.USER_AGENT;
    private String tgSearch;
    private String tgSortField = "time";
    private boolean tgLogin;
    private String panSouUrl;
    private String panSouSource = "all";
    private String panSouChannels = "custom";
    private String panSouUsername;
    private String panSouPassword;
    private Boolean panSouAuthEnabled;
    private List<String> panSouPlugins;
    private boolean panSouLinkCheckEnabled;
    private int panSouLinkCheckMaxCount = 30;
    private String systemId;
    private int tgTimeout = 5000;
    private int tgWebAccessCheckTimeout = 120000;
    private int tempShareExpiration = 72;
    private int validateSharesInterval = 4;
    private Set<String> formats;
    private Set<String> subtitles;
    private List<Site> sites;
    private List<String> excludedPaths;
    private Map<String, Map<String, Object>> localProxyConfig = defaultLocalProxyConfig();

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
