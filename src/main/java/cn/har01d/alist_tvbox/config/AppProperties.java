package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.tvbox.Site;
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
    private int pageSize = 100;
    private int maxSearchResult = 60;
    private String secretKey;
    private String tgChannels = "tianyirigeng,Quark_Movies,xx123pan1,xunleiyunpan,yunpanshare,XiangxiuNB,Aliyun_4K_Movies";
    private String tgWebChannels = "tianyirigeng,Quark_Movies";
    private int tgTimeout = 5000;
    private Set<String> formats;
    private Set<String> subtitles;
    private List<Site> sites;
}
