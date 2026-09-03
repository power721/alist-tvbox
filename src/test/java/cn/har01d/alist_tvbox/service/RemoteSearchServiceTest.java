package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigDto;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties,
                restTemplateBuilder(restTemplate),
                objectMapper,
                mock(TelegramChannelRepository.class),
                mock(ShareService.class),
                mock(TvBoxService.class),
                offlineDownloadService,
                mock(SubscriptionSourceService.class),
                panSouClient(appProperties, restTemplate), panLinkCheck(appProperties, restTemplate)
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
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), offlineDownloadService, mock(SubscriptionSourceService.class),
                panSouClient(appProperties, restTemplate), panLinkCheck(appProperties, restTemplate));

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
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties,
                restTemplateBuilder(restTemplate),
                objectMapper,
                telegramChannelRepository,
                shareService,
                tvBoxService,
                offlineDownloadService,
                mock(SubscriptionSourceService.class),
                panSouClient(appProperties, restTemplate), panLinkCheck(appProperties, restTemplate)
        );

        server.expect(once(), requestTo("http://pansou.example/api/search"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"total":1,"results":[{"title":"肖申克的救赎","content":"肖申克的救赎","links":[{"type":"quark","url":"https://pan.quark.cn/s/abc123"}]}],"merged_by_type":{}}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            // search asks ShareService to cache the real title keyed by share link
            service.pansou("肖申克的救赎");
            verify(shareService).cacheShareTitle(eq("https://pan.quark.cn/s/abc123"), eq("肖申克的救赎"));

            // detail recovers the title via ShareService and passes it through so getPlaylist
            // does not fall back to the obfuscated storage folder name and break scraping
            when(shareService.resolveShareTitle(eq("https://pan.quark.cn/s/abc123"), isNull()))
                    .thenReturn("肖申克的救赎");
            service.detail("https://pan.quark.cn/s/abc123");

            verify(tvBoxService).getDetail(eq(""), eq("1$/mock/~playlist"), eq("肖申克的救赎"), isNull(), eq(0));
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
                mock(SubscriptionSourceService.class),
                panSouClient(appProperties, restTemplate), panLinkCheck(appProperties, restTemplate)
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
    void pansouGroupReturnsFolderPerDiskType() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouSource("all");
        TelegramChannelRepository telegramChannelRepository = mock(TelegramChannelRepository.class);
        when(telegramChannelRepository.findByEnabledTrue(any())).thenReturn(List.of());
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                telegramChannelRepository, mock(ShareService.class),
                mock(TvBoxService.class), offlineDownloadService, mock(SubscriptionSourceService.class),
                panSouClient(appProperties, restTemplate), panLinkCheck(appProperties, restTemplate));

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
    void targetedSearchSendsWhitelistCloudTypesWithOfflineAppended() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouSource("all");
        appProperties.setTgDrivers(List.of("10")); // 全局口径是百度:定向模式下被白名单替换
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), offlineDownloadService, mock(SubscriptionSourceService.class),
                panSouClient(appProperties, restTemplate), panLinkCheck(appProperties, restTemplate));

        server.expect(once(), requestTo("http://pansou.example/api/search"))
                .andExpect(content().json("""
                        {"kw":"movie","cloud_types":["quark","115","magnet","ed2k"]}
                        """))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":{"total":0,"results":[],"merged_by_type":{}}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        // 主∪扩展 = 夸克/115,磁力兜底生效 → cloud_types 按订阅定向并追加离线类型
        service.search("movie", List.of(),
                cn.har01d.alist_tvbox.domain.SearchTargets.of(java.util.Set.of("quark", "115"), true));

        server.verify();
    }

    @Test
    void targetedSearchWithoutWhitelistNeverSendsOfflineOnlyCloudTypes() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouSource("all");
        appProperties.setTgDrivers(List.of()); // 全局口径清空才会走到"pan 部分为空"分支(默认是全量)
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), offlineDownloadService, mock(SubscriptionSourceService.class),
                panSouClient(appProperties, restTemplate), panLinkCheck(appProperties, restTemplate));

        server.expect(once(), requestTo("http://pansou.example/api/search"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.cloud_types").doesNotExist())
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":{"total":0,"results":[],"merged_by_type":{}}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        // 白名单空 + 全局 tg.drivers 空:pan 部分为空 → 不发送 cloud_types
        //(单发 [magnet,ed2k] 会把网盘结果裁光;不限模式服务端本就返回离线类型,由本地门禁收口)
        service.search("movie", List.of(), cn.har01d.alist_tvbox.domain.SearchTargets.of(java.util.Set.of(), true));

        server.verify();
    }

    @Test
    void targetedSearchGatesOffWhitelistMergedResults() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouSource("all");
        appProperties.setTgDrivers(List.of("5", "10")); // 全局放行夸克+百度:白名单只认夸克
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), offlineDownloadService, mock(SubscriptionSourceService.class),
                panSouClient(appProperties, restTemplate), panLinkCheck(appProperties, restTemplate));

        server.expect(once(), requestTo("http://pansou.example/api/search"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"total":2,"results":[],"merged_by_type":{
                          "quark":[{"note":"剧A","url":"https://pan.quark.cn/s/a"}],
                          "baidu":[{"note":"剧B","url":"https://pan.baidu.com/s/b"}]}}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        List<cn.har01d.alist_tvbox.dto.tg.Message> messages = service.search("剧", List.of(),
                cn.har01d.alist_tvbox.domain.SearchTargets.of(java.util.Set.of("quark"), false));

        assertThat(messages).extracting(cn.har01d.alist_tvbox.dto.tg.Message::getLink)
                .containsExactly("https://pan.quark.cn/s/a");
        server.verify();
    }

    @Test
    void perSourceOverrideWinsOverGlobal() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouSource("all");
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, null, ""));
        SubscriptionSourceService subscriptionSourceService = mock(SubscriptionSourceService.class);
        when(subscriptionSourceService.getBuiltinExtend("csp_FishPanSou"))
                .thenReturn("{\"source\":\"tg\",\"filter_include\":\"1080, 4K\",\"filter_exclude\":\"枪版\"}");

        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), offlineDownloadService, subscriptionSourceService,
                panSouClient(appProperties, restTemplate), panLinkCheck(appProperties, restTemplate));

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
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, null, ""));
        SubscriptionSourceService subscriptionSourceService = mock(SubscriptionSourceService.class);
        when(subscriptionSourceService.getBuiltinExtend("csp_FishPanSou"))
                .thenReturn("{\"source\":\"tg\"}");

        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), offlineDownloadService, subscriptionSourceService,
                panSouClient(appProperties, restTemplate), panLinkCheck(appProperties, restTemplate));

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

    private PanSouClient panSouClient(AppProperties appProperties, RestTemplate restTemplate) {
        return new PanSouClient(appProperties, restTemplateBuilder(restTemplate));
    }

    private PanLinkCheckService panLinkCheck(AppProperties appProperties, RestTemplate restTemplate) {
        return new PanLinkCheckService(appProperties, restTemplateBuilder(restTemplate), objectMapper,
                panSouClient(appProperties, restTemplate));
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
