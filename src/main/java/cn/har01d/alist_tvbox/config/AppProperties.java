package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.tvbox.Site;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

@Data
@ConfigurationProperties("app")
public class AppProperties {
    private boolean xiaoya;
    private boolean sort;
    private int pageSize = 100;
    private Set<String> formats;
    private List<Site> sites;
}
