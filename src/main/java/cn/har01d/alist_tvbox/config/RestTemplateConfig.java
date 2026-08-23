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
 *
 * <p>Customizers run after the builder applied its request factory (including the
 * connect/read timeouts configured on it), so swapping in a bare factory silently drops
 * every timeout to "infinite" — a wedged server then blocks the caller's thread forever
 * (observed: single-threaded msub-check executor stuck 20+ minutes in a socket read).
 * The Jdk factory exposes no timeout getters to copy over, so the swap re-applies a 60s
 * floor — matching the only explicit configuration in the codebase (AListService 60s) and
 * safe for the rest (no builder user transfers large bodies).
 */
@Configuration
public class RestTemplateConfig {

    private static final int TIMEOUT_MILLIS = 60_000;

    @Bean
    public RestTemplateCustomizer simpleClientHttpRequestFactoryCustomizer() {
        return restTemplate -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(TIMEOUT_MILLIS);
            factory.setReadTimeout(TIMEOUT_MILLIS);
            restTemplate.setRequestFactory(factory);
        };
    }
}
