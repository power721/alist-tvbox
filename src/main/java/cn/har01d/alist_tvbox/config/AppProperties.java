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
    private String tgChannels = "xx123pan1,xunleiyunpan,tgsearchers,leoziyuan,ucpanpan,pan123pan,zyfb123,zyzhpd123,xx123pan,tianyirigeng,tyypzhpd,cloudtianyi,kuakeclound,clouddriveresources,NewQuark,guaguale115,Channel_Shares_115,dianyingshare,XiangxiuNB,yunpanpan,kuakeyun,zaihuayun,Quark_Movies,vip115hot,yunpanshare,shareAliyun,alyp_1,quanziyuanshe";
    private String tgWebChannels = "tgsearchers,leoziyuan,ucpanpan,pan123pan,zyfb123,zyzhpd123,xx123pan,tianyirigeng,tyypzhpd,cloudtianyi,kuakeclound,clouddriveresources,NewQuark,guaguale115,Channel_Shares_115,dianyingshare,XiangxiuNB,yunpanpan,kuakeyun,zaihuayun,Quark_Movies,vip115hot,yunpanshare,shareAliyun,alyp_1,quanziyuanshe";
    private int tgTimeout = 5000;
    private Set<String> formats;
    private Set<String> subtitles;
    private List<Site> sites;
}
