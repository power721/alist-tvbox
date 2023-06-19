package cn.har01d.alist_tvbox;

import cn.har01d.alist_tvbox.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
@SpringBootApplication
public class AListApplication {

    public static void main(String[] args) {
        SpringApplication.run(AListApplication.class, args);
    }

}
