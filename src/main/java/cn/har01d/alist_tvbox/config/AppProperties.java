package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.tvbox.Site;
import cn.har01d.alist_tvbox.util.Constants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
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
    private String userAgent = Constants.USER_AGENT;
    private String tgChannels = Constants.TG_CHANNELS;
    private String tgWebChannels = Constants.TG_WEB_CHANNELS;
    private String tgSearch;
    private String systemId;
    private int tgTimeout = 5000;
    private int tempShareExpiration = 24;
    private Set<String> formats;
    private Set<String> subtitles;
    private List<Site> sites;
    private List<String> excludedPaths;
}
