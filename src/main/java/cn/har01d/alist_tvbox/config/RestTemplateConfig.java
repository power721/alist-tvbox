package cn.har01d.alist_tvbox.config;

import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Pins every builder-built {@link org.springframework.web.client.RestTemplate} to
 * {@link SimpleClientHttpRequestFactory} (HttpURLConnection).
 *
 * <p>Spring Boot 4 (see commit "Spring boot 4 migration") switched
 * {@link org.springframework.boot.restclient.RestTemplateBuilder}'s default
 * {@code ClientHttpRequestFactory} to {@code JdkClientHttpRequestFactory}. Its
 * {@code DecompressingBodyHandler} reads the response {@code Content-Encoding} and wraps
 * the body in {@code GZIPInputStream} / {@code InflaterInputStream}; against some servers
 * (e.g. Emby/Jellyfin on ASP.NET/Kestrel) this raises
 * {@code java.util.zip.ZipException: incorrect header check}. HttpURLConnection
 * transparently and correctly decompresses gzip/deflate, matching the pre-Boot-4 behavior,
 * so this restores it globally for all {@code builder.build()} clients.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplateCustomizer simpleClientHttpRequestFactoryCustomizer() {
        return restTemplate -> restTemplate.setRequestFactory(new SimpleClientHttpRequestFactory());
    }
}
