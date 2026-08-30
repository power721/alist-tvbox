package cn.har01d.alist_tvbox.service.metadata;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 元数据 provider 共用的 HTTP 客户端工厂。
 * 必须用 {@link RestTemplateBuilder} 构建:Spring Boot 4 的 Jackson2 支持在 spring-boot-jackson2 模块里,
 * 只有 builder 构建的 RestTemplate 才带自动配置的消息转换器 —— 裸 new RestTemplate() 无法序列化
 * ObjectNode/反序列化 JsonNode(表现为 "Type definition error: JsonNode",provider 全部静默空结果)。
 * 同时统一带超时(外部平台挂起不能卡死巡检线程)与项目的 SimpleClientHttpRequestFactory 定制。
 */
@Component
public class MetadataHttp {
    private final RestTemplateBuilder builder;

    public MetadataHttp(RestTemplateBuilder builder) {
        this.builder = builder;
    }

    public RestTemplate create() {
        return create(Duration.ofSeconds(15));
    }

    /** 长轮询等特殊调用需要更长的读超时(如 Telegram getUpdates 的 25s 挂起)。 */
    public RestTemplate create(Duration readTimeout) {
        RestTemplateBuilder base = builder != null ? builder : new RestTemplateBuilder();
        RestTemplate template = base
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(readTimeout)
                .build();
        // builder 配的超时会被 RestTemplateConfig 全局 customizer(60s 地板)的 setRequestFactory 覆盖
        // (customizer 在 build 时运行,JDK factory 无超时 getter 可透传)—— build 后自设 Simple
        // factory 收回主动权:消息转换器不受影响,巡检线程对挂起平台最多等 readTimeout 而非 60s×N 请求
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        template.setRequestFactory(factory);
        return template;
    }
}
