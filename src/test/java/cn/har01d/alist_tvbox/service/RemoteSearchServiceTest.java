package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigDto;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
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
