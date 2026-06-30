package cn.har01d.alist_tvbox.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the global {@code RestTemplateCustomizer} is applied by Spring Boot's
 * auto-configured {@link RestTemplateBuilder}, overriding the Boot 4 default
 * {@code JdkClientHttpRequestFactory} with {@link SimpleClientHttpRequestFactory}.
 * Regression guard for the Emby/Jellyfin {@code ZipException: incorrect header check}.
 */
class RestTemplateConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestTemplateAutoConfiguration.class))
            .withUserConfiguration(RestTemplateConfig.class);

    @Test
    void builderProducesSimpleClientHttpRequestFactory() {
        contextRunner.run(context -> {
            RestTemplateBuilder builder = context.getBean(RestTemplateBuilder.class);
            assertThat(builder.build().getRequestFactory())
                    .isInstanceOf(SimpleClientHttpRequestFactory.class);
        });
    }
}
