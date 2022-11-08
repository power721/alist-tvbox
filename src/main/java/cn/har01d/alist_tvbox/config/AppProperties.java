package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.tvbox.Site;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Set;

@Data
@ConfigurationProperties("app")
public class AppProperties {
    private CacheConfig cache = new CacheConfig();
    private boolean sort;
    private Set<String> formats;
    private List<Site> sites;

    @Data
    public static class CacheConfig {
        private int size = 50;
        private Duration expire = Duration.ofHours(2);
    }
}
