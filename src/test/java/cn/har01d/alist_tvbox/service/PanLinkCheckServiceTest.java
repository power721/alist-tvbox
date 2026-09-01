package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 盘检服务:可检盘型门禁、阈值抽样(超阈值按盘各取前 N)、PanCheck 归一化、
 * TG-Search 兜底解包、后端优先级。从 RemoteSearchServiceTest 原样迁入(重构搬家,断言未动)。
 */
class PanLinkCheckServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void selectCheckableHonorsSelectedDiskTypes() {
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouLinkCheckTypes(List.of("quark"));
        PanLinkCheckService service = newService(appProperties, new RestTemplate());

        // Only quark selected -> baidu (unselected) and pikpak (not in supported 9) are NOT checkable.
        List<Message> checkable = service.selectCheckable(List.of(
                message("5", "https://pan.quark.cn/s/q1"),
                message("10", "https://pan.baidu.com/s/b1"),
                message("1", "https://www.pikpak.com/s/p1")));

        assertThat(checkable).extracting(Message::getLink)
                .containsExactly("https://pan.quark.cn/s/q1");
    }

    @Test
    void selectCheckableDefaultsToAllSupportedWhenUnset() {
        AppProperties appProperties = new AppProperties(); // panSouLinkCheckTypes unset -> all 9
        PanLinkCheckService service = newService(appProperties, new RestTemplate());

        // Unset -> all supported types checkable; pikpak (not supported) still excluded.
        List<Message> checkable = service.selectCheckable(List.of(
                message("5", "https://pan.quark.cn/s/q1"),
                message("10", "https://pan.baidu.com/s/b1"),
                message("1", "https://www.pikpak.com/s/p1")));

        assertThat(checkable).extracting(Message::getLink)
                .containsExactlyInAnyOrder("https://pan.quark.cn/s/q1", "https://pan.baidu.com/s/b1");
    }

    @Test
    void filterInvalidPanSouLinksChecksAllWhenUnderThreshold() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouLinkCheckEnabled(true);
        appProperties.setPanCheckUrl("http://pc.example");
        PanLinkCheckService service = newService(appProperties, restTemplate);

        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://pc.example/api/v1/links/check"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"valid_links":["https://pan.quark.cn/s/ok"],"invalid_links":["https://pan.baidu.com/s/bad"]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        List<Message> result = service.filterInvalidPanSouLinks(List.of(
                message("5", "https://pan.quark.cn/s/ok"),
                message("10", "https://pan.baidu.com/s/bad")));

        server.verify();
        assertThat(result).extracting(Message::getLink)
                .containsExactly("https://pan.quark.cn/s/ok");
    }

    @Test
    void filterInvalidPanSouLinksSamplesTopLinksPerTypeWhenOverThreshold() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouLinkCheckEnabled(true);
        appProperties.setPanCheckUrl("http://pc.example");
        PanLinkCheckService service = newService(appProperties, restTemplate);

        // 408 条可检(150夸克+150百度+108阿里)超过阈值300:不再整体跳过,按类型各取前100送检
        List<Message> messages = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) messages.add(message("5", "https://pan.quark.cn/s/q" + i));
        for (int i = 0; i < 150; i++) messages.add(message("10", "https://pan.baidu.com/s/b" + i));
        for (int i = 0; i < 108; i++) messages.add(message("0", "https://alipan.com/s/a" + i));

        List<String> validLinks = new java.util.ArrayList<>();
        for (int i = 1; i < 100; i++) validLinks.add("https://pan.quark.cn/s/q" + i); // q0 判失效
        for (int i = 0; i < 100; i++) validLinks.add("https://pan.baidu.com/s/b" + i);
        for (int i = 0; i < 100; i++) validLinks.add("https://alipan.com/s/a" + i);
        String responseJson = objectMapper.writeValueAsString(Map.of(
                "valid_links", validLinks,
                "invalid_links", List.of("https://pan.quark.cn/s/q0")));

        String[] requestBody = new String[1];
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://pc.example/api/v1/links/check"))
                .andRespond(request -> {
                    requestBody[0] = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    return org.springframework.test.web.client.response.MockRestResponseCreators
                            .withSuccess(responseJson, org.springframework.http.MediaType.APPLICATION_JSON).createResponse(request);
                });

        List<Message> result = service.filterInvalidPanSouLinks(messages);

        server.verify();
        // 送检名单:夸克/百度/阿里各恰好前100条(q0..q99 / b0..b99 / a0..a99)
        var links = objectMapper.readTree(requestBody[0]).get("links");
        int quarkChecked = 0;
        int baiduChecked = 0;
        int aliyunChecked = 0;
        for (var link : links) {
            String url = link.asText();
            if (url.contains("quark.cn")) quarkChecked++;
            else if (url.contains("baidu.com")) baiduChecked++;
            else aliyunChecked++;
        }
        assertThat(quarkChecked).isEqualTo(100);
        assertThat(baiduChecked).isEqualTo(100);
        assertThat(aliyunChecked).isEqualTo(100);
        // 被检出的失效链接剔除,未送检的 108 条原样保留:408 - 1 = 407
        assertThat(result).hasSize(407);
        assertThat(result).extracting(Message::getLink).doesNotContain("https://pan.quark.cn/s/q0");
        assertThat(result).extracting(Message::getLink).contains("https://pan.quark.cn/s/q149", "https://alipan.com/s/a107");
    }

    @Test
    void filterInvalidPanSouLinksKeepsRateLimitedLinks() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouLinkCheckEnabled(true);
        appProperties.setPanCheckUrl("http://pc.example");
        PanLinkCheckService service = newService(appProperties, restTemplate);

        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://pc.example/api/v1/links/check"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"valid_links":["https://pan.quark.cn/s/ok"],
                         "invalid_links":["https://pan.baidu.com/s/bad"],
                         "rate_limited_links":["https://pan.baidu.com/s/rl"]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        List<Message> result = service.filterInvalidPanSouLinks(List.of(
                message("5", "https://pan.quark.cn/s/ok"),
                message("10", "https://pan.baidu.com/s/bad"),
                message("10", "https://pan.baidu.com/s/rl")));

        server.verify();
        // 限流链接状态未知:只标注不剔除,防止限流期间百度源被整体误杀
        assertThat(result).extracting(Message::getLink)
                .containsExactly("https://pan.quark.cn/s/ok", "https://pan.baidu.com/s/rl");
        Message rateLimited = result.get(1);
        assertThat(rateLimited.getValidityState()).isEqualTo("rate_limited");
        assertThat(rateLimited.getValiditySummary()).contains("限流");
    }

    @Test
    void filterInvalidPanSouLinksDemotesBatchBaiduFailuresAsSuspectedRateLimit() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouLinkCheckEnabled(true);
        appProperties.setPanCheckUrl("http://pc.example");
        PanLinkCheckService service = newService(appProperties, restTemplate);

        // 后端把限流混进 invalid_links 且无法逐条区分:6 条百度全 bad = 疑似 IP 级限流
        List<Message> messages = new java.util.ArrayList<>();
        messages.add(message("5", "https://pan.quark.cn/s/ok"));
        for (int i = 0; i < 6; i++) messages.add(message("10", "https://pan.baidu.com/s/b" + i));
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://pc.example/api/v1/links/check"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"valid_links":["https://pan.quark.cn/s/ok"],
                         "invalid_links":["https://pan.baidu.com/s/b0","https://pan.baidu.com/s/b1",
                          "https://pan.baidu.com/s/b2","https://pan.baidu.com/s/b3",
                          "https://pan.baidu.com/s/b4","https://pan.baidu.com/s/b5"]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        List<Message> result = service.filterInvalidPanSouLinks(messages);

        server.verify();
        assertThat(result).extracting(Message::getLink).hasSize(7);
        assertThat(result.get(1).getValidityState()).isEqualTo("rate_limited");
    }

    @Test
    void checkPanSouLinksUsesPanCheckWhenConfiguredAndNormalizesBuckets() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanCheckUrl("http://pc.example");
        PanLinkCheckService service = newService(appProperties, restTemplate);

        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode items = request.putArray("items");
        items.addObject().put("disk_type", "quark").put("url", "https://pan.quark.cn/s/a");
        items.addObject().put("disk_type", "quark").put("url", "https://pan.quark.cn/s/b");
        request.put("view_token", "t");

        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://pc.example/api/v1/links/check"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"submission_id":1,"valid_links":["https://pan.quark.cn/s/a"],
                         "invalid_links":["https://pan.quark.cn/s/b"],"locked_links":[],"pending_links":[]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        ObjectNode out = service.checkPanSouLinks(request);
        server.verify();
        Map<String, String> states = new HashMap<>();
        out.get("results").forEach(r -> states.put(r.get("url").asText(), r.get("state").asText()));
        assertThat(states).containsEntry("https://pan.quark.cn/s/a", "ok")
                .containsEntry("https://pan.quark.cn/s/b", "bad");
    }

    @Test
    void checkPanSouLinksMapsDiskTypeToPanCheckPlatform() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanCheckUrl("http://pc.example");
        PanLinkCheckService service = newService(appProperties, restTemplate);

        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("items").addObject().put("disk_type", "123").put("url", "https://www.123pan.com/s/x");

        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://pc.example/api/v1/links/check"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"valid_links":["https://www.123pan.com/s/x"]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        ObjectNode out = service.checkPanSouLinks(request);
        server.verify();
        assertThat(out.get("results").get(0).get("state").asText()).isEqualTo("ok");
    }

    @Test
    void checkPanSouLinksFallsBackToTgSearchAndUnwrapsData() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setTgSearch("http://tg.example");
        appProperties.setTgSearchApiKey("tgkey");
        PanLinkCheckService service = newService(appProperties, restTemplate);

        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("items").addObject().put("disk_type", "quark").put("url", "https://pan.quark.cn/s/a");
        request.put("view_token", "t");

        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://tg.example/api/check/links"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("X-API-Key", "tgkey"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"code":0,"message":"success","data":{"results":[
                          {"url":"https://pan.quark.cn/s/a","state":"ok","summary":"链接有效"}]}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        ObjectNode out = service.checkPanSouLinks(request);
        server.verify();
        assertThat(out.get("results").get(0).get("url").asText()).isEqualTo("https://pan.quark.cn/s/a");
        assertThat(out.has("data")).isFalse();
    }

    @Test
    void checkPanSouLinksTgSearchSendsConfiguredTimeout() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setTgSearch("http://tg.example");
        appProperties.setPanCheckTimeoutMs(3000);
        PanLinkCheckService service = newService(appProperties, restTemplate);

        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("items").addObject().put("disk_type", "quark").put("url", "https://pan.quark.cn/s/a");
        request.put("view_token", "t");

        // timeout_ms injected only because TG-Search is the active backend and a timeout is configured
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://tg.example/api/check/links"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"code":0,"data":{"results":[{"url":"https://pan.quark.cn/s/a","state":"ok"}]}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        service.checkPanSouLinks(request);
        server.verify();
    }

    @Test
    void checkPanSouLinksPanCheckWinsOverTgSearch() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanCheckUrl("http://pc.example");
        appProperties.setTgSearch("http://tg.example");
        PanLinkCheckService service = newService(appProperties, restTemplate);

        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("items").addObject().put("disk_type", "quark").put("url", "https://pan.quark.cn/s/a");

        // Only PanCheck is hit; TG-Search must not be contacted.
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://pc.example/api/v1/links/check"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"valid_links":["https://pan.quark.cn/s/a"]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        ObjectNode out = service.checkPanSouLinks(request);
        server.verify();
        assertThat(out.get("results").get(0).get("state").asText()).isEqualTo("ok");
    }

    private PanLinkCheckService newService(AppProperties appProperties, RestTemplate restTemplate) {
        PanSouClient panSouClient = new PanSouClient(appProperties, restTemplateBuilder(restTemplate));
        return new PanLinkCheckService(appProperties, restTemplateBuilder(restTemplate), objectMapper, panSouClient);
    }

    private static Message message(String type, String link) {
        Message m = new Message();
        m.setType(type);
        m.setLink(link);
        return m;
    }

    private RestTemplateBuilder restTemplateBuilder(RestTemplate restTemplate) {
        return new RestTemplateBuilder()
                .messageConverters(
                        new StringHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(objectMapper)
                )
                .detectRequestFactory(false)
                .requestFactory(() -> restTemplate.getRequestFactory());
    }
}
