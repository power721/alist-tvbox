package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigDto;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RemoteSearchServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void searchUsesPanSouBuiltinChannelsWhenConfigured() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouChannels("pansou");
        appProperties.setPanSouSource("all");
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties,
                restTemplateBuilder(restTemplate),
                objectMapper,
                mock(TelegramChannelRepository.class),
                mock(ShareService.class),
                mock(TvBoxService.class),
                offlineDownloadService,
                mock(SubscriptionSourceService.class)
        );

        server.expect(once(), requestTo("http://pansou.example/api/health"))
                .andRespond(withSuccess("""
                        {"channels":["builtin-a","builtin-b"],"channels_count":2,"auth_enabled":false}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://pansou.example/api/search"))
                .andExpect(content().json("""
                        {"kw":"movie","channels":["builtin-a","builtin-b"],"src":"all","res":"merge"}
                        """))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":{"total":0,"results":[],"merged_by_type":{}}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        service.search("movie", List.of("custom-a", "custom-b"));

        server.verify();
    }

    @Test
    void searchSendsNewParamsWhenConfigured() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouChannels("pansou");
        appProperties.setPanSouSource("all");
        appProperties.setPanSouConc(20);
        appProperties.setPanSouRefresh(true);
        appProperties.setPanSouRes("merge");
        appProperties.setPanSouFilterInclude(List.of("1080"));
        appProperties.setPanSouFilterExclude(List.of("枪版"));
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), offlineDownloadService, mock(SubscriptionSourceService.class));

        server.expect(once(), requestTo("http://pansou.example/api/health"))
                .andRespond(withSuccess("""
                        {"channels":["builtin-a"],"channels_count":1,"auth_enabled":false}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://pansou.example/api/search"))
                .andExpect(content().json("""
                        {"kw":"movie","src":"all","conc":20,"refresh":true,"res":"merge",
                         "filter":{"include":["1080"],"exclude":["枪版"]}}
                        """))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":{"total":0,"results":[],"merged_by_type":{}}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        service.search("movie", List.of());

        server.verify();
    }

    @Test
    void detailBackfillsSearchResultTitleForPanSou() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouSource("all");

        TelegramChannelRepository telegramChannelRepository = mock(TelegramChannelRepository.class);
        when(telegramChannelRepository.findByEnabledTrue(any())).thenReturn(List.of());
        ShareService shareService = mock(ShareService.class);
        when(shareService.add(any())).thenReturn("/mock");
        TvBoxService tvBoxService = mock(TvBoxService.class);
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties,
                restTemplateBuilder(restTemplate),
                objectMapper,
                telegramChannelRepository,
                shareService,
                tvBoxService,
                offlineDownloadService,
                mock(SubscriptionSourceService.class)
        );

        server.expect(once(), requestTo("http://pansou.example/api/search"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"total":1,"results":[{"title":"肖申克的救赎","content":"肖申克的救赎","links":[{"type":"quark","url":"https://pan.quark.cn/s/abc123"}]}],"merged_by_type":{}}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            // search captures the real title keyed by share link
            service.pansou("肖申克的救赎");
            // detail must pass that title through so getPlaylist does not fall back to
            // the obfuscated storage folder name and break metadata scraping
            service.detail("https://pan.quark.cn/s/abc123");

            verify(tvBoxService).getDetail(eq(""), eq("1$/mock/~playlist"), eq("肖申克的救赎"), eq(0));
            server.verify();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void getSearchChannelsUsesPanSouBuiltinChannelsWhenConfigured() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouChannels("pansou");

        RemoteSearchService service = new RemoteSearchService(
                appProperties,
                restTemplateBuilder(restTemplate),
                objectMapper,
                mock(TelegramChannelRepository.class),
                mock(ShareService.class),
                mock(TvBoxService.class),
                mock(OfflineDownloadService.class),
                mock(SubscriptionSourceService.class)
        );

        server.expect(once(), requestTo("http://pansou.example/api/health"))
                .andRespond(withSuccess("""
                        {"channels":["builtin-a","builtin-b"],"channels_count":2,"auth_enabled":false}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        org.assertj.core.api.Assertions.assertThat(service.getSearchChannels(List.of("custom-a", "custom-b")))
                .containsExactly("builtin-a", "builtin-b");

        server.verify();
    }

    @Test
    void selectCheckableHonorsSelectedDiskTypes() {
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouLinkCheckTypes(List.of("quark"));
        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(new RestTemplate()), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), mock(OfflineDownloadService.class), mock(SubscriptionSourceService.class));

        // Only quark selected -> baidu (unselected) and pikpak (not in supported 9) are NOT checkable.
        List<Message> checkable = service.selectCheckable(List.of(
                message("5", "https://pan.quark.cn/s/q1"),
                message("10", "https://pan.baidu.com/s/b1"),
                message("1", "https://www.pikpak.com/s/p1")));

        org.assertj.core.api.Assertions.assertThat(checkable).extracting(Message::getLink)
                .containsExactly("https://pan.quark.cn/s/q1");
    }

    @Test
    void selectCheckableDefaultsToAllSupportedWhenUnset() {
        AppProperties appProperties = new AppProperties(); // panSouLinkCheckTypes unset -> all 9
        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(new RestTemplate()), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), mock(OfflineDownloadService.class), mock(SubscriptionSourceService.class));

        // Unset -> all supported types checkable; pikpak (not supported) still excluded.
        List<Message> checkable = service.selectCheckable(List.of(
                message("5", "https://pan.quark.cn/s/q1"),
                message("10", "https://pan.baidu.com/s/b1"),
                message("1", "https://www.pikpak.com/s/p1")));

        org.assertj.core.api.Assertions.assertThat(checkable).extracting(Message::getLink)
                .containsExactlyInAnyOrder("https://pan.quark.cn/s/q1", "https://pan.baidu.com/s/b1");
    }

    @Test
    void pansouGroupReturnsFolderPerDiskType() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouSource("all");
        TelegramChannelRepository telegramChannelRepository = mock(TelegramChannelRepository.class);
        when(telegramChannelRepository.findByEnabledTrue(any())).thenReturn(List.of());
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                telegramChannelRepository, mock(ShareService.class),
                mock(TvBoxService.class), offlineDownloadService, mock(SubscriptionSourceService.class));

        server.expect(once(), requestTo("http://pansou.example/api/search"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"total":2,"results":[],"merged_by_type":{
                          "quark":[{"note":"电影A","url":"https://pan.quark.cn/s/a"}],
                          "uc":[{"note":"电影B","url":"https://pan.uc.cn/s/b"}]}}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            cn.har01d.alist_tvbox.tvbox.MovieList result = service.pansouGroup("电影");
            org.assertj.core.api.Assertions.assertThat(result.getList()).hasSize(2);
            org.assertj.core.api.Assertions.assertThat(result.getList())
                    .extracting(cn.har01d.alist_tvbox.tvbox.MovieDetail::getVod_tag)
                    .containsOnly("folder");
            org.assertj.core.api.Assertions.assertThat(result.getList())
                    .extracting(cn.har01d.alist_tvbox.tvbox.MovieDetail::getVod_name)
                    .containsExactlyInAnyOrder("夸克网盘", "UC网盘");
            org.assertj.core.api.Assertions.assertThat(result.getList())
                    .extracting(cn.har01d.alist_tvbox.tvbox.MovieDetail::getVod_remarks)
                    .containsOnly("1条结果");
            String quarkId = result.getList().stream()
                    .filter(m -> "夸克网盘".equals(m.getVod_name())).findFirst().orElseThrow().getVod_id();

            // folder click → list resources of that type
            cn.har01d.alist_tvbox.tvbox.MovieList list = service.pansouGroupList(quarkId, 1);
            org.assertj.core.api.Assertions.assertThat(list.getList()).hasSize(1);
            // vod_id is encodeUrl(link) → starts with the scheme; NOT a pgroup: id
            org.assertj.core.api.Assertions.assertThat(list.getList().get(0).getVod_id()).startsWith("https");
            org.assertj.core.api.Assertions.assertThat(list.getTotal()).isEqualTo(1);
            server.verify();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void perSourceOverrideWinsOverGlobal() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouSource("all");
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, ""));
        SubscriptionSourceService subscriptionSourceService = mock(SubscriptionSourceService.class);
        when(subscriptionSourceService.getBuiltinExtend("csp_FishPanSou"))
                .thenReturn("{\"source\":\"tg\",\"filter_include\":\"1080, 4K\",\"filter_exclude\":\"枪版\"}");

        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), offlineDownloadService, subscriptionSourceService);

        server.expect(once(), requestTo("http://pansou.example/api/search"))
                .andExpect(content().json("""
                        {"kw":"movie","src":"tg","filter":{"include":["1080","4K"],"exclude":["枪版"]}}
                        """))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":{"total":0,"results":[],"merged_by_type":{}}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        // 鱼佬盘搜 path: per-source override (source/包含词/排除词) all win over global
        service.search("movie", List.of(), "csp_FishPanSou");

        server.verify();
    }

    @Test
    void perSourceBlankFieldsFallBackToGlobal() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouSource("all");
        appProperties.setPanSouFilterInclude(List.of("1080"));
        appProperties.setPanSouFilterExclude(List.of("枪版"));
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, ""));
        SubscriptionSourceService subscriptionSourceService = mock(SubscriptionSourceService.class);
        when(subscriptionSourceService.getBuiltinExtend("csp_FishPanSou"))
                .thenReturn("{\"source\":\"tg\"}");

        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), offlineDownloadService, subscriptionSourceService);

        server.expect(once(), requestTo("http://pansou.example/api/search"))
                .andExpect(content().json("""
                        {"kw":"movie","src":"tg","filter":{"include":["1080"],"exclude":["枪版"]}}
                        """))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":{"total":0,"results":[],"merged_by_type":{}}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        // only source overridden; 包含词/排除词 inherit global
        service.search("movie", List.of(), "csp_FishPanSou");

        server.verify();
    }

    @Test
    void checkPanSouLinksUsesPanCheckWhenConfiguredAndNormalizesBuckets() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanCheckUrl("http://pc.example");
        RemoteSearchService service = newService(appProperties, restTemplate);

        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode items = request.putArray("items");
        items.addObject().put("disk_type", "quark").put("url", "https://pan.quark.cn/s/a");
        items.addObject().put("disk_type", "quark").put("url", "https://pan.quark.cn/s/b");
        request.put("view_token", "t");

        server.expect(once(), requestTo("http://pc.example/api/v1/links/check"))
                .andExpect(content().json("""
                        {"links":["https://pan.quark.cn/s/a","https://pan.quark.cn/s/b"],"selected_platforms":["quark"]}
                        """))
                .andRespond(withSuccess("""
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
        RemoteSearchService service = newService(appProperties, restTemplate);

        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("items").addObject().put("disk_type", "123").put("url", "https://www.123pan.com/s/x");

        server.expect(once(), requestTo("http://pc.example/api/v1/links/check"))
                .andExpect(content().json("""
                        {"selected_platforms":["pan123"]}
                        """))
                .andRespond(withSuccess("""
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
        RemoteSearchService service = newService(appProperties, restTemplate);

        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("items").addObject().put("disk_type", "quark").put("url", "https://pan.quark.cn/s/a");
        request.put("view_token", "t");

        server.expect(once(), requestTo("http://tg.example/api/check/links"))
                .andExpect(header("X-API-Key", "tgkey"))
                .andExpect(content().json("""
                        {"items":[{"disk_type":"quark","url":"https://pan.quark.cn/s/a"}]}
                        """))
                .andRespond(withSuccess("""
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
        RemoteSearchService service = newService(appProperties, restTemplate);

        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("items").addObject().put("disk_type", "quark").put("url", "https://pan.quark.cn/s/a");
        request.put("view_token", "t");

        // timeout_ms injected only because TG-Search is the active backend and a timeout is configured
        server.expect(once(), requestTo("http://tg.example/api/check/links"))
                .andExpect(content().json("""
                        {"items":[{"disk_type":"quark","url":"https://pan.quark.cn/s/a"}],"timeout_ms":3000}
                        """))
                .andRespond(withSuccess("""
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
        RemoteSearchService service = newService(appProperties, restTemplate);

        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("items").addObject().put("disk_type", "quark").put("url", "https://pan.quark.cn/s/a");

        // Only PanCheck is hit; TG-Search must not be contacted.
        server.expect(once(), requestTo("http://pc.example/api/v1/links/check"))
                .andRespond(withSuccess("""
                        {"valid_links":["https://pan.quark.cn/s/a"]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        ObjectNode out = service.checkPanSouLinks(request);
        server.verify();
        assertThat(out.get("results").get(0).get("state").asText()).isEqualTo("ok");
    }

    private RemoteSearchService newService(AppProperties appProperties, RestTemplate restTemplate) {
        return new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), mock(OfflineDownloadService.class), mock(SubscriptionSourceService.class));
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
