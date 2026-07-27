package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.model.Filter;
import cn.har01d.alist_tvbox.model.FilterValue;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class PianDanServiceTest {
    @Mock
    private TelegramService telegramService;
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private RestTemplateBuilder builder;

    private RestTemplate restTemplate;
    private PianDanService service;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        when(builder.build()).thenReturn(restTemplate);
        service = new PianDanService(telegramService, settingRepository, builder, new ObjectMapper());
    }

    @Test
    void categoryPrefixesDoubanCategoriesAndPreservesFilters() {
        Category local = new Category();
        local.setType_id("local");
        local.setType_name("浏览");
        Filter filter = new Filter("sort", "排序", List.of(new FilterValue("热门", "hot")));
        CategoryList douban = new CategoryList();
        douban.setCategories(List.of(local));
        douban.getFilters().put("local", List.of(filter));
        when(telegramService.categoryDouban()).thenReturn(douban);

        CategoryList result = service.category();

        assertThat(result.getCategories())
                .extracting(Category::getType_id)
                .contains("douban:local", "tmdb:trending", "tmdb:discover_movie", "tmdb:discover_tv");
        assertThat(result.getCategories().get(0).getType_name()).isEqualTo("豆瓣·浏览");
        assertThat(result.getFilters().get("douban:local")).containsExactly(filter);
        assertThat(result.getFilters()).containsKeys("tmdb:trending", "tmdb:discover_movie", "tmdb:discover_tv");
        assertThat(result.getTotal()).isEqualTo(result.getCategories().size());
    }

    @Test
    void listDelegatesPrefixedDoubanCategoryAndFilters() {
        MovieList expected = new MovieList();
        when(telegramService.listDouban("local", "web", "score,desc", 2025, "剧情", "中国", 2, 30))
                .thenReturn(expected);

        MovieList result = service.list("douban:local", "web", 2, 30, Map.of(
                "sort", "score,desc",
                "year", "2025",
                "genre", "剧情",
                "region", "中国"
        ));

        assertThat(result).isSameAs(expected);
        verify(telegramService).listDouban("local", "web", "score,desc", 2025, "剧情", "中国", 2, 30);
    }

    @Test
    void tmdbDiscoverUsesConfiguredKeyAndMapsNavigationMetadata() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.of(new Setting("tmdb_api_key", "custom-key")));
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/3/discover/movie");
                    assertThat(request.getURI().getQuery()).contains(
                            "api_key=custom-key",
                            "language=zh-CN",
                            "region=CN",
                            "include_adult=false",
                            "page=2",
                            "sort_by=vote_average.desc",
                            "with_genres=18",
                            "with_origin_country=CN",
                            "primary_release_year=2025",
                            "vote_count.gte=200"
                    );
                })
                .andRespond(withSuccess("""
                        {
                          "page": 2,
                          "total_pages": 12,
                          "total_results": 231,
                          "results": [
                            {
                              "id": 42,
                              "title": "测试电影",
                              "poster_path": "/poster.jpg",
                              "release_date": "2025-04-01",
                              "vote_average": 8.26,
                              "overview": "剧情简介"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        MovieList result = service.list("tmdb:discover_movie", "", 2, 20, Map.of(
                "sort_by", "vote_average.desc",
                "with_genres", "18",
                "with_origin_country", "CN",
                "year", "2025"
        ));

        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getPagecount()).isEqualTo(12);
        assertThat(result.getTotal()).isEqualTo(231);
        assertThat(result.getList()).singleElement().satisfies(movie -> {
            assertThat(movie.getVod_id()).isEqualTo("tmdb:movie:42");
            assertThat(movie.getVod_name()).isEqualTo("测试电影");
            assertThat(movie.getVod_pic()).isEqualTo("https://image.tmdb.org/t/p/w500/poster.jpg");
            assertThat(movie.getVod_year()).isEqualTo("2025");
            assertThat(movie.getVod_remarks()).isEqualTo("2025 · 8.3");
            assertThat(movie.getVod_content()).isEqualTo("剧情简介");
        });
        server.verify();
    }

    @Test
    void tmdbTrendingSkipsPeopleAndIncompleteItems() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/3/trending/all/day");
                    assertThat(request.getURI().getQuery()).contains("api_key=77f111cda6c6fb55322f3d7f2b6ef71f");
                })
                .andRespond(withSuccess("""
                        {
                          "results": [
                            {"id": 1, "media_type": "person", "name": "演员"},
                            {"id": 2, "media_type": "movie"},
                            {"id": 3, "media_type": "tv", "name": "测试剧", "first_air_date": null, "poster_path": null}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        MovieList result = service.list("tmdb:trending", "", 1, 20, Map.of(
                "mediaType", "all",
                "time_window", "day"
        ));

        assertThat(result.getList()).singleElement().satisfies(movie -> {
            assertThat(movie.getVod_name()).isEqualTo("测试剧");
            assertThat(movie.getVod_pic()).isNull();
            assertThat(movie.getVod_year()).isEmpty();
        });
        server.verify();
    }
}
