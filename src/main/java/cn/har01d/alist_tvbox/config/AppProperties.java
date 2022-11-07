package cn.har01d.alist_tvbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

@Data
@ConfigurationProperties("app")
public class AppProperties {
    private CacheConfig cache = new CacheConfig();
    private boolean sort;
    private Set<String> formats;

    @Data
    public static class CacheConfig {
        private int size = 50;
        private Duration expire = Duration.ofHours(2);
    }
}
