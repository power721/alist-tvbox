package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * PanSou 传输客户端:登录换 token → Bearer POST、未启用认证走裸 POST、
 * 403「认证功能未启用」降级、TVBox 盘型代码 → cloud 名映射。
 * token 单点持有是搜索/盘检共用的前提(PanSou 登录轮换 token,双实例登录会互踢)。
 */
class PanSouClientTest {

    @Test
    void postLogsInAndSendsBearerToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouUsername("u");
        appProperties.setPanSouPassword("p");
        appProperties.setPanSouAuthEnabled(true);
        PanSouClient client = new PanSouClient(appProperties, builder(restTemplate));

        server.expect(once(), requestTo("http://pansou.example/api/auth/login"))
                .andRespond(withSuccess("{\"token\":\"t1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://pansou.example/api/x"))
                .andExpect(header("Authorization", "Bearer t1"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        String response = client.post("http://pansou.example/api/x", Map.of("k", "v"), String.class);

        assertThat(response).isEqualTo("ok");
        server.verify();
    }

    @Test
    void postWithoutAuthEnabledIsPlain() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouAuthEnabled(null); // 健康探测未确认前不启用
        PanSouClient client = new PanSouClient(appProperties, builder(restTemplate));

        server.expect(once(), requestTo("http://pansou.example/api/x"))
                .andRespond(withSuccess("plain", MediaType.TEXT_PLAIN));

        String response = client.post("http://pansou.example/api/x", Map.of(), String.class);

        assertThat(response).isEqualTo("plain");
        server.verify();
    }

    @Test
    void loginForbiddenWithAuthDisabledFallsBackToPlainPost() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouUsername("u");
        appProperties.setPanSouPassword("p");
        appProperties.setPanSouAuthEnabled(true);
        PanSouClient client = new PanSouClient(appProperties, builder(restTemplate));

        server.expect(once(), requestTo("http://pansou.example/api/auth/login"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(HttpStatus.FORBIDDEN).body("认证功能未启用").contentType(MediaType.TEXT_PLAIN));
        server.expect(once(), requestTo("http://pansou.example/api/x"))
                .andRespond(withSuccess("plain", MediaType.TEXT_PLAIN));

        String response = client.post("http://pansou.example/api/x", Map.of(), String.class);

        assertThat(response).isEqualTo("plain");
        assertThat(appProperties.getPanSouAuthEnabled()).isFalse();
        server.verify();
    }

    @Test
    void cloudTypeMapsTvBoxDriveCodes() {
        assertThat(PanSouClient.cloudType("5")).isEqualTo("quark");
        assertThat(PanSouClient.cloudType("10")).isEqualTo("baidu");
        assertThat(PanSouClient.cloudType("0")).isEqualTo("aliyun");
        assertThat(PanSouClient.cloudType("9")).isEqualTo("tianyi");
        assertThat(PanSouClient.cloudType("magnet")).isEqualTo("magnet");
        assertThat(PanSouClient.cloudType("4")).isNull();
        assertThat(PanSouClient.cloudType(null)).isNull();
    }

    private RestTemplateBuilder builder(RestTemplate restTemplate) {
        return new RestTemplateBuilder()
                .detectRequestFactory(false)
                .requestFactory(() -> restTemplate.getRequestFactory());
    }
}
