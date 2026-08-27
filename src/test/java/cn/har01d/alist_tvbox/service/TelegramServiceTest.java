package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.TelegramChannel;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import org.jsoup.Jsoup;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TelegramServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void getTgSearchHealthUsesAuthorizationHeaderOnly() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setTgSearch("http://tg-search.example");
        appProperties.setTgSearchApiKey("secret-key");
        appProperties.setTgDrivers(List.of("5", "10", "0", "7", "2", "9", "8", "6", "1", "3", "12", "magnet", "ed2k"));
        TelegramService service = createService(appProperties, restTemplate, mock(TelegramChannelRepository.class));

        server.expect(once(), request -> {
                    assertThat(request.getURI().toString()).isEqualTo("http://tg-search.example/api/health");
                    assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("secret-key");
                    assertThat(request.getHeaders().containsHeader("X-API-Key")).isFalse();
                })
                .andRespond(withSuccess("""
                        {"service":"ok","version":"2.1.0"}
                        """, MediaType.APPLICATION_JSON));

        var health = service.getTgSearchHealth();

        assertThat(health.get("service").asText()).isEqualTo("ok");
        assertThat(health.get("version").asText()).isEqualTo("2.1.0");
        server.verify();
    }

    @Test
    void getTgSearchHealthDoesNotCallHttpWhenTgSearchUrlIsBlank() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setTgSearch("");
        appProperties.setTgSearchApiKey("secret-key");
        TelegramService service = createService(appProperties, restTemplate, mock(TelegramChannelRepository.class));

        var health = service.getTgSearchHealth();

        assertThat(health.get("service").asText()).isEqualTo("unconfigured");
        assertThat(health.get("message").asText()).isEqualTo("tg-search api url is blank");
        server.verify();
    }

    @Test
    void searchTgSearchMoviesDoesNotCallHttpWhenTgSearchUrlIsBlank() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setTgSearch("");
        appProperties.setTgSearchApiKey("secret-key");
        TelegramService service = createService(appProperties, restTemplate, mock(TelegramChannelRepository.class));

        var result = service.searchTgSearchMovies("ubuntu", 2, 30);

        assertThat(result.getList()).isEmpty();
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getPagecount()).isEqualTo(1);
        assertThat(result.getTotal()).isZero();
        assertThat(result.getLimit()).isEqualTo(30);
        server.verify();
    }

    @Test
    @Disabled
    void searchTgSearchMoviesUsesTgSearchApiAndMapsMediaToMovieDetail() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setTgSearch("http://tg-search.example");
        appProperties.setTgSearchApiKey("secret-key");
        TelegramService service = createService(appProperties, restTemplate, mock(TelegramChannelRepository.class));

        server.expect(once(), requestTo("http://tg-search.example/api/search"))
                .andExpect(request -> {
                    assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
                    assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("secret-key");
                    assertThat(request.getHeaders().containsHeader("X-API-Key")).isFalse();
                })
                .andExpect(content().json("""
                        {
                          "kw": "ubuntu",
                          "res": "merge",
                          "cloud_types": ["quark", "baidu", "aliyun", "uc", "xunlei", "tianyi", "115", "mobile", "pikpak", "123", "guangya", "magnet", "ed2k"],
                          "include_image": true,
                          "include_media_metadata": true,
                          "limit": 30,
                          "offset": 30
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "success",
                          "data": {
                            "total": 75,
                            "merged_by_type": {
                              "quark": [
                                {
                                  "url": "https://pan.quark.cn/s/abc",
                                  "note": "Ubuntu 2026 4K",
                                  "datetime": "2026-06-09T20:05:21Z",
                                  "images": ["/i/123?exp=1781131335&sig=signed"],
                                  "media": {
                                    "title": "Ubuntu",
                                    "year": "2026",
                                    "episode": "更新07集",
                                    "quality": "4K",
                                    "size": "12G",
                                    "tags": "linux test"
                                  }
                                }
                              ]
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = service.searchTgSearchMovies("ubuntu", 2, 30);

        assertThat(result.getList()).hasSize(1);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getPagecount()).isEqualTo(3);
        assertThat(result.getTotal()).isEqualTo(75);
        assertThat(result.getLimit()).isEqualTo(30);
        MovieDetail detail = result.getList().getFirst();
        assertThat(detail.getVod_id()).isEqualTo("https%3A%2F%2Fpan.quark.cn%2Fs%2Fabc");
        assertThat(detail.getVod_name()).isEqualTo("Ubuntu");
        assertThat(detail.getVod_pic()).isEqualTo("http://tg-search.example/i/123?exp=1781131335&sig=signed");
        assertThat(detail.getVod_remarks()).isEqualTo("更新07集 4K 12G");
        assertThat(detail.getVod_year()).isEqualTo("2026");
        assertThat(detail.getVod_content()).contains("Ubuntu 2026 4K", "linux test");
//        assertThat(detail.getExt()).isNotNull();
        server.verify();
    }

    @Test
    @Disabled
    void searchTgSearchMoviesAppendsPasswordWhenLinkHasNoExtractCode() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setTgSearch("http://tg-search.example");
        appProperties.setTgSearchApiKey("secret-key");
        appProperties.setTgDrivers(List.of("3", "10"));
        TelegramService service = createService(appProperties, restTemplate, mock(TelegramChannelRepository.class));

        server.expect(once(), requestTo("http://tg-search.example/api/search"))
                .andExpect(request -> assertThat(request.getMethod()).isEqualTo(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "kw": "qiaochu",
                          "res": "merge",
                          "cloud_types": ["123", "baidu"],
                          "include_image": true,
                          "include_media_metadata": true,
                          "limit": 20,
                          "offset": 0
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "success",
                          "data": {
                            "total": 2,
                            "merged_by_type": {
                              "123": [
                                {
                                  "url": "https://www.123865.com/s/IpPUVv-ymPdv",
                                  "password": "Qiye",
                                  "note": "123 item",
                                  "datetime": "2026-06-10T03:04:36Z",
                                  "images": ["/i/123"],
                                  "media": {
                                    "title": "123 item"
                                  }
                                }
                              ],
                              "baidu": [
                                {
                                  "url": "https://pan.baidu.com/s/13u6rtvR4Iz1i03BhIcpeNw?pwd=6666",
                                  "password": "9999",
                                  "note": "Baidu item",
                                  "datetime": "2026-06-10T01:49:57Z",
                                  "images": ["/i/baidu"],
                                  "media": {
                                    "title": "Baidu item"
                                  }
                                }
                              ]
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = service.searchTgSearchMovies("qiaochu", 1, 20);

        assertThat(result.getList())
                .extracting(MovieDetail::getVod_id)
                .containsExactly(
                        "https%3A%2F%2Fwww.123865.com%2Fs%2FIpPUVv-ymPdv%3Fpassword%3DQiye",
                        "https%3A%2F%2Fpan.baidu.com%2Fs%2F13u6rtvR4Iz1i03BhIcpeNw%3Fpwd%3D6666"
                );
        server.verify();
    }

    @Test
    @Disabled
    void searchTgSearchMoviesUsesTgSearchWhenPanSouIsAlsoConfigured() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setTgSearch("http://tg-search.example");
        appProperties.setTgSearchApiKey("secret-key");
        appProperties.setTgDrivers(List.of("5", "10"));
        RemoteSearchService remoteSearchService = mock(RemoteSearchService.class);
        TelegramService service = createService(appProperties, restTemplate, mock(TelegramChannelRepository.class), remoteSearchService);

        server.expect(once(), requestTo("http://tg-search.example/api/search"))
                .andExpect(request -> {
                    assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
                    assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("secret-key");
                })
                .andExpect(content().json("""
                        {
                          "kw": "ubuntu",
                          "res": "merge",
                          "cloud_types": ["quark", "baidu"],
                          "include_image": true,
                          "include_media_metadata": true,
                          "limit": 20,
                          "offset": 0
                        }
                        """))
                .andRespond(withSuccess("""
                        {"code":0,"message":"success","data":{"total":0,"merged_by_type":{}}}
                        """, MediaType.APPLICATION_JSON));

        service.searchTgSearchMovies("ubuntu", 1, 20);

        server.verify();
        verifyNoInteractions(remoteSearchService);
    }

    @Test
    @Disabled
    void listTgSearchUsesPaginationAndCloudTypeFilter() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setTgSearch("http://tg-search.example");
        appProperties.setTgSearchApiKey("secret-key");
        appProperties.setTgDrivers(List.of("5"));
        TelegramService service = createService(appProperties, restTemplate, mock(TelegramChannelRepository.class));

        server.expect(once(), requestTo("http://tg-search.example/api/search"))
                .andExpect(request -> {
                    assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
                    assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("secret-key");
                })
                .andExpect(content().json("""
                        {
                          "kw": "",
                          "res": "merge",
                          "cloud_types": ["quark"],
                          "include_image": true,
                          "include_media_metadata": true,
                          "limit": 20,
                          "offset": 40
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "message": "success",
                          "data": {
                            "total": 41,
                            "merged_by_type": {
                              "quark": [
                                {
                                  "url": "https://pan.quark.cn/s/page3",
                                  "note": "Page 3 item",
                                  "datetime": "2026-06-09T20:05:21Z",
                                  "images": ["/i/456?exp=1781131335&sig=signed"],
                                  "media": {
                                    "title": "Page 3 item"
                                  }
                                }
                              ]
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = service.listTgSearch("type:5", 3, 20);

        assertThat(result.getList()).hasSize(1);
        assertThat(result.getPage()).isEqualTo(3);
        assertThat(result.getPagecount()).isEqualTo(3);
        assertThat(result.getTotal()).isEqualTo(41);
        assertThat(result.getLimit()).isEqualTo(20);
        assertThat(result.getList().getFirst().getVod_name()).isEqualTo("Page 3 item");
        server.verify();
    }

    @Test
    void categoryTgSearchContainsOnlyDiskTypes() {
        AppProperties appProperties = new AppProperties();
        appProperties.setTgDrivers(List.of("5", "0", "magnet"));
        TelegramService service = createService(appProperties, new RestTemplate(), mock(TelegramChannelRepository.class));

        var result = service.categoryTgSearch();

        assertThat(result.getCategories())
                .extracting(category -> category.getType_id())
                .containsExactly("type:5", "type:0", "type:magnet");
        assertThat(result.getCategories())
                .extracting(category -> category.getType_name())
                .containsExactly("夸克", "阿里", "磁力");
        assertThat(result.getCategories())
                .noneMatch(category -> !category.getType_id().startsWith("type:"));
    }

    @Test
    void validateChannelsChecksTgSearchHealthInsteadOfLegacyValidateEndpoint() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setTgSearch("http://tg-search.example");
        appProperties.setTgSearchApiKey("secret-key");
        TelegramChannelRepository channelRepository = mock(TelegramChannelRepository.class);
        TelegramService service = createService(appProperties, restTemplate, channelRepository);

        server.expect(once(), request -> {
                    assertThat(request.getURI().toString()).isEqualTo("http://tg-search.example/api/health");
                    assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("secret-key");
                    assertThat(request.getHeaders().containsHeader("X-API-Key")).isFalse();
                })
                .andRespond(withSuccess("""
                        {"service":"ok","version":"2.1.0"}
                        """, MediaType.APPLICATION_JSON));

        service.validateChannels();

        server.verify();
    }

    @Test
    void parseWebMessagesParsesIdTextTimeAndCover() {
        TelegramService service = createService(new AppProperties(), new RestTemplate(), mock(TelegramChannelRepository.class));

        var messages = service.parseWebMessages(Jsoup.parse("""
                <div class="tgme_container"><div class="tgme_widget_message_wrap">
                  <a class="tgme_widget_message_photo_wrap" style="width:320px;background-image:url('https://cdn.example/a.jpg')"></a>
                  <div class="tgme_widget_message" data-post="chan/101">
                    <div class="tgme_widget_message_text">标题 https://pan.quark.cn/s/abc</div>
                    <time datetime="2026-08-24T10:00:00Z"></time>
                  </div>
                </div></div>
                """), "chan");

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getId()).isEqualTo(101);
        assertThat(messages.getFirst().getChannel()).isEqualTo("chan");
        assertThat(messages.getFirst().getContent()).contains("pan.quark.cn");
        assertThat(messages.getFirst().getTime()).isEqualTo(Instant.parse("2026-08-24T10:00:00Z"));
        assertThat(messages.getFirst().getCover()).isEqualTo("https://cdn.example/a.jpg");
    }

    @Test
    void parseWebMessagesSkipsWrapWithoutInnerMessageNode() {
        TelegramService service = createService(new AppProperties(), new RestTemplate(), mock(TelegramChannelRepository.class));

        var messages = service.parseWebMessages(Jsoup.parse("""
                <div class="tgme_container">
                  <div class="tgme_widget_message_wrap"></div>
                  <div class="tgme_widget_message_wrap">
                    <div class="tgme_widget_message" data-post="chan/102">
                      <div class="tgme_widget_message_text">https://pan.quark.cn/s/def</div>
                    </div>
                  </div>
                </div>
                """), "chan");

        assertThat(messages).extracting(cn.har01d.alist_tvbox.dto.tg.Message::getId).containsExactly(102);
    }

    @Test
    void parseWebMessagesSkipsMalformedDataPost() {
        TelegramService service = createService(new AppProperties(), new RestTemplate(), mock(TelegramChannelRepository.class));

        var messages = service.parseWebMessages(Jsoup.parse("""
                <div class="tgme_container">
                  <div class="tgme_widget_message_wrap">
                    <div class="tgme_widget_message" data-post="no-separator"></div>
                  </div>
                  <div class="tgme_widget_message_wrap">
                    <div class="tgme_widget_message" data-post="chan/not-a-number"></div>
                  </div>
                </div>
                """), "chan");

        assertThat(messages).isEmpty();
    }

    @Test
    void listDoubanWebRewritesCoverToRelativeImageProxy() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TelegramService service = createService(new AppProperties(), restTemplate, mock(TelegramChannelRepository.class));
        String body = """
                {"total":1,"items":[{"id":"36810153","title":"测试剧",
                  "pic":{"normal":"https://img3.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg"},
                  "rating":{"count":100,"value":7.5}}]}
                """;

        server.expect(once(), request -> assertThat(request.getURI().getPath()).endsWith("/subject/recent_hot/tv"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(once(), request -> assertThat(request.getURI().getPath()).endsWith("/subject/recent_hot/tv"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        var webResult = service.listDouban("hot_tv", "web", null, null, null, null, 1, 20);
        var spiderResult = service.listDouban("hot_tv", null, null, null, null, null, 1, 20);

        assertThat(webResult.getList()).singleElement().satisfies(movie ->
                assertThat(movie.getVod_pic()).isEqualTo("/images?url=https://img3.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg"));
        // TVBox spider 不带 ac,封面保持豆瓣直链
        assertThat(spiderResult.getList()).singleElement().satisfies(movie ->
                assertThat(movie.getVod_pic()).isEqualTo("https://img3.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg"));
        server.verify();
    }

    @Test
    void listDoubanHotTvRegionUsesRecommendApiPerRegion() {
        // 与生产一致走 RestTemplateBuilder:其 uriTemplateHandler 会把 URL 模板里的 % 二次编码,
        // 早期版本传 String URL 时 tags 被编成 %25XX 乱码、豆瓣返回 total=0(地区筛选全空)
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TelegramService service = createService(new AppProperties(), restTemplate, mock(TelegramChannelRepository.class));

        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).endsWith("/tv/recommend");
                    // tags=电视剧,日本 —— 未被二次编码(无 %25),且带近期热度排序
                    assertThat(request.getURI().getRawQuery()).contains("tags=%E7%94%B5%E8%A7%86%E5%89%A7%2C%E6%97%A5%E6%9C%AC");
                    assertThat(request.getURI().getRawQuery()).contains("sort=U");
                    assertThat(request.getURI().getRawQuery()).doesNotContain("%25");
                })
                .andRespond(withSuccess("""
                        {"total":500,"items":[{"id":"1","title":"日剧",
                          "pic":{"normal":"https://img1.doubanio.com/p1.jpg"},"rating":{"count":10,"value":8.0}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).endsWith("/tv/recommend");
                    // tags=电视剧,韩国 —— 不同地区各自请求,不落同一条缓存
                    assertThat(request.getURI().getRawQuery()).contains("tags=%E7%94%B5%E8%A7%86%E5%89%A7%2C%E9%9F%A9%E5%9B%BD");
                })
                .andRespond(withSuccess("""
                        {"total":500,"items":[{"id":"2","title":"韩剧",
                          "pic":{"normal":"https://img2.doubanio.com/p2.jpg"},"rating":{"count":20,"value":7.0}}]}
                        """, MediaType.APPLICATION_JSON));

        var japan = service.listDouban("hot_tv", "web", null, null, null, "日本", 1, 20);
        var korea = service.listDouban("hot_tv", "web", null, null, null, "韩国", 1, 20);

        assertThat(japan.getList()).singleElement().satisfies(movie -> {
            assertThat(movie.getVod_name()).isEqualTo("日剧");
            assertThat(movie.getVod_pic()).isEqualTo("/images?url=https://img1.doubanio.com/p1.jpg");
        });
        assertThat(korea.getList()).singleElement().satisfies(movie ->
                assertThat(movie.getVod_name()).isEqualTo("韩剧"));
        server.verify();
    }

    private TelegramService createService(AppProperties appProperties, RestTemplate restTemplate, TelegramChannelRepository channelRepository) {
        return createService(appProperties, restTemplate, channelRepository, mock(RemoteSearchService.class));
    }

    private TelegramService createService(AppProperties appProperties, RestTemplate restTemplate, TelegramChannelRepository channelRepository, RemoteSearchService remoteSearchService) {
        return new TelegramService(
                appProperties,
                channelRepository,
                mock(SettingRepository.class),
                mock(MovieRepository.class),
                mock(ShareService.class),
                mock(TvBoxService.class),
                remoteSearchService,
                new RestTemplateBuilder()
                        .messageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                        .detectRequestFactory(false)
                        .requestFactory(() -> restTemplate.getRequestFactory()),
                objectMapper
        );
    }
}
