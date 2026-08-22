package cn.har01d.alist_tvbox.service.metadata;

import org.springframework.boot.restclient.RestTemplateBuilder;
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
        if (builder != null) {
            return builder
                    .connectTimeout(Duration.ofSeconds(10))
                    .readTimeout(Duration.ofSeconds(15))
                    .build();
        }
        // 单测/无上下文兜底(直接实例化的 builder 仍能探测 classpath 上的 Jackson2 转换器)
        return new RestTemplateBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(15))
                .build();
    }
}
