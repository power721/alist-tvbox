package cn.har01d.alist_tvbox;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.config.NativeCaffeineFactoryFix;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.security.Security;
import java.util.Arrays;
import java.util.stream.Collectors;

@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
@SpringBootApplication
public class AListApplication {

    public static void main(String[] args) {
        NativeCaffeineFactoryFix.apply();
        allowStaticRsaCipherSuites();
        SpringApplication.run(AListApplication.class, args);
    }

    /**
     * 斗鱼弹幕服务器(danmuproxy.douyu.com:8506)只接受静态 RSA 密钥交换套件,JDK 默认把 TLS_RSA_* 整类禁用。
     * 该属性有解析缓存,必须在任何 TLS 握手发生前放宽;LiveDanmakuService 会另外为 OkHttp 补上套件白名单。
     * <p>
     * 只摘掉 TLS_RSA_* 这一项而不是整表覆写:覆写会连带解禁 TLSv1/TLSv1.1、ECDH 与 SHA-1 握手签名,
     * 那是全应用所有出站 HTTPS(网盘/TG/Emby)都受影响的安全降级,也会丢掉将来 JDK 新增的默认限制。
     */
    private static void allowStaticRsaCipherSuites() {
        String disabled = Security.getProperty("jdk.tls.disabledAlgorithms");
        if (disabled == null || !disabled.contains("TLS_RSA_")) {
            return;
        }
        Security.setProperty("jdk.tls.disabledAlgorithms", Arrays.stream(disabled.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty() && !item.startsWith("TLS_RSA_"))
                .collect(Collectors.joining(", ")));
    }

}
