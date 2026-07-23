package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigDto;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties,
                restTemplateBuilder(restTemplate),
                objectMapper,
                mock(TelegramChannelRepository.class),
                mock(ShareService.class),
                mock(TvBoxService.class),
                offlineDownloadService
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
        appProperties.setPanSouRes("results");
        appProperties.setPanSouFilterInclude(List.of("1080"));
        appProperties.setPanSouFilterExclude(List.of("枪版"));
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), offlineDownloadService);

        server.expect(once(), requestTo("http://pansou.example/api/health"))
                .andRespond(withSuccess("""
                        {"channels":["builtin-a"],"channels_count":1,"auth_enabled":false}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://pansou.example/api/search"))
                .andExpect(content().json("""
                        {"kw":"movie","src":"all","conc":20,"refresh":true,"res":"results",
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
                offlineDownloadService
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
                mock(OfflineDownloadService.class)
        );

        server.expect(once(), requestTo("http://pansou.example/api/health"))
                .andRespond(withSuccess("""
                        {"channels":["builtin-a","builtin-b"],"channels_count":2,"auth_enabled":false}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        org.assertj.core.api.Assertions.assertThat(service.getSearchChannels(List.of("custom-a", "custom-b")))
                .containsExactly("builtin-a", "builtin-b");

        server.verify();
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
