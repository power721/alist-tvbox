package cn.har01d.alist_tvbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@Data
@ConfigurationProperties("app")
public class AppProperties {
    private String url = "http://localhost:5422";
    private int cacheSize = 50;
    private boolean playlistSort;
    private Set<String> formats;
}
