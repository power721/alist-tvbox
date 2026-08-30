package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.model.Filter;
import cn.har01d.alist_tvbox.model.FilterValue;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(MockitoExtension.class)
class PianDanServiceTest {
    @Mock
    private TelegramService telegramService;
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private SubscriptionSourceService subscriptionSourceService;
    @Mock
    private RestTemplateBuilder builder;

    private RestTemplate restTemplate;
    private PianDanService service;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        when(builder.build()).thenReturn(restTemplate);
        // TmdbEndpoint 即读即用:未配置镜像回落官方直连,不改变测试里的 URL 断言
        lenient().when(settingRepository.findById("tmdb_api_host")).thenReturn(java.util.Optional.empty());
        service = new PianDanService(telegramService, subscriptionSourceService, builder, new ObjectMapper(), new TmdbEndpoint(settingRepository));
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
                .contains(
                        "douban:local",
                        "tmdb:trending",
                        "tmdb:movie_upcoming",
                        "tmdb:tv_on_the_air",
                        "tmdb:anime",
                        "tmdb:variety",
                        "tmdb:platform_tv",
                        "tmdb:platform_movie",
                        "tmdb:discover_movie",
                        "tmdb:discover_tv"
                );
        assertThat(result.getCategories().get(0).getType_name()).isEqualTo("豆瓣·浏览");
        assertThat(result.getCategories())
                .filteredOn(category -> "tmdb:anime".equals(category.getType_id()) || "tmdb:variety".equals(category.getType_id()))
                .extracting(Category::getType_name)
                .containsExactly("TMDB动漫片库", "TMDB综艺片库");
        assertThat(result.getFilters().get("douban:local")).containsExactly(filter);
        assertThat(result.getFilters().get("douban:hot_tv")).extracting(Filter::getKey).containsExactly("region");
        assertThat(result.getFilters().get("douban:hot_movie")).extracting(Filter::getKey).containsExactly("region");
        assertThat(result.getFilters()).containsKeys(
                "tmdb:trending",
                "tmdb:movie_upcoming",
                "tmdb:anime",
                "tmdb:variety",
                "tmdb:platform_tv",
                "tmdb:platform_movie",
                "tmdb:discover_movie",
                "tmdb:discover_tv"
        );
        assertThat(result.getFilters().get("tmdb:discover_movie"))
                .extracting(Filter::getKey)
                .containsExactly(
                        "origin_group",
                        "with_origin_country",
                        "sort_by",
                        "with_genres",
                        "year",
                        "with_original_language",
                        "vote_average.gte",
                        "vote_count.gte",
                        "runtime"
                );
        assertThat(result.getFilters().get("tmdb:tv_popular"))
                .extracting(Filter::getKey)
                .containsExactly("origin_group", "sort_by");
        assertThat(result.getTotal()).isEqualTo(result.getCategories().size());
    }

    @Test
    void categoryLiteModeMergesCategoriesAndKeepsBrowse() {
        when(subscriptionSourceService.getBuiltinExtend("csp_PianDan")).thenReturn("{\"mode\":\"lite\"}");
        CategoryList douban = new CategoryList();
        douban.setCategories(List.of(
                doubanCategory("hot_movie", "热门电影"),
                doubanCategory("hot_tv", "热门电视剧"),
                doubanCategory("local", "浏览"),
                doubanCategory("tv_domestic", "国产剧"),
                doubanCategory("movie_top250", "电影Top250")
        ));
        Filter browseFilter = new Filter("sort", "排序", List.of(new FilterValue("热门", "hot")));
        douban.getFilters().put("local", List.of(browseFilter));
        when(telegramService.categoryDouban()).thenReturn(douban);

        CategoryList result = service.category();

        assertThat(result.getCategories())
                .extracting(Category::getType_id)
                .containsExactly(
                        "douban:hot_movie", "douban:hot_tv",
                        "douban:category", "douban:billboard", "douban:local",
                        "tmdb:trending", "tmdb:movie", "tmdb:tv",
                        "tmdb:discover_movie", "tmdb:discover_tv", "tmdb:anime", "tmdb:variety"
                );
        assertThat(result.getCategories()).extracting(Category::getType_id)
                .doesNotContain("douban:tv_domestic", "douban:movie_top250", "tmdb:movie_popular", "tmdb:platform_tv");
        assertThat(result.getFilters().get("douban:local")).containsExactly(browseFilter);
        assertThat(result.getFilters().get("douban:hot_tv")).extracting(Filter::getKey).containsExactly("region");
        assertThat(result.getFilters().get("douban:category")).extracting(Filter::getKey).containsExactly("category");
        assertThat(result.getFilters().get("douban:billboard")).extracting(Filter::getKey).containsExactly("billboard");
        assertThat(result.getFilters().get("tmdb:movie")).extracting(Filter::getKey)
                .containsExactly("list", "origin_group", "sort_by");
        assertThat(result.getFilters().get("tmdb:tv")).extracting(Filter::getKey)
                .containsExactly("list", "origin_group", "sort_by");
        assertThat(result.getFilters()).containsKeys(
                "tmdb:trending", "tmdb:discover_movie", "tmdb:discover_tv", "tmdb:anime", "tmdb:variety");
        assertThat(result.getTotal()).isEqualTo(12);
    }

    @Test
    void listResolvesMergedTmdbMovieListFilter() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> assertThat(request.getURI().getPath()).isEqualTo("/3/movie/top_rated"))
                .andRespond(withSuccess("{\"page\":1,\"total_pages\":1,\"total_results\":0,\"results\":[]}", MediaType.APPLICATION_JSON));

        MovieList result = service.list("tmdb:movie", "", 1, 20, Map.of("list", "top_rated"));

        assertThat(result.getList()).isEmpty();
        server.verify();
    }

    @Test
    void listResolvesMergedDoubanCategoryFilter() {
        MovieList expected = new MovieList();
        expected.setList(List.of());
        when(telegramService.listDouban("tv_korean", "web", null, null, null, null, 1, 20)).thenReturn(expected);

        service.list("douban:category", "web", 1, 20, Map.of("category", "tv_korean"));

        verify(telegramService).listDouban("tv_korean", "web", null, null, null, null, 1, 20);
    }

    @Test
    void listRemovesDoubanFolderMetadataWithoutMutatingSharedResult() {
        MovieDetail folder = new MovieDetail();
        folder.setVod_id("s:测试电影");
        folder.setVod_name("测试电影");
        folder.setVod_tag("folder");
        folder.setCate(new CategoryList());
        MovieList expected = new MovieList();
        expected.setList(List.of(folder));
        when(telegramService.listDouban("local", "web", "score,desc", 2025, "剧情", "中国", 2, 30))
                .thenReturn(expected);

        MovieList result = service.list("douban:local", "web", 2, 30, Map.of(
                "sort", "score,desc",
                "year", "2025",
                "genre", "剧情",
                "region", "中国"
        ));

        assertThat(result).isNotSameAs(expected);
        assertThat(result.getList()).singleElement().satisfies(movie -> {
            assertThat(movie).isNotSameAs(folder);
            assertThat(movie.getVod_id()).isEqualTo("s:测试电影");
            assertThat(movie.getVod_name()).isEqualTo("测试电影");
            assertThat(movie.getVod_tag()).isNull();
            assertThat(movie.getCate()).isNull();
        });
        assertThat(folder.getVod_tag()).isEqualTo("folder");
        assertThat(folder.getCate()).isNotNull();
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
                            "vote_count.gte=50"
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
            assertThat(movie.getVod_remarks()).isEqualTo("8.3");
            assertThat(movie.getVod_content()).isEqualTo("剧情简介");
        });
        server.verify();
    }

    @Test
    void varietyMapsRegionAndTomorrowSchedule() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        String tomorrow = LocalDate.now().plusDays(1).toString();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/3/discover/tv");
                    assertThat(request.getURI().getQuery()).contains(
                            "sort_by=popularity.desc",
                            "with_genres=10764%7C10767",
                            "with_origin_country=KR",
                            "air_date.gte=" + tomorrow,
                            "air_date.lte=" + tomorrow
                    );
                })
                .andRespond(withSuccess("""
                        {"results":[{"id":71,"name":"明日综艺","first_air_date":"2026-08-01"}]}
                        """, MediaType.APPLICATION_JSON));

        MovieList result = service.list("tmdb:variety", "", 1, 20, Map.of(
                "region", "KR",
                "list_type", "tomorrow"
        ));

        assertThat(result.getList()).singleElement().satisfies(movie -> {
            assertThat(movie.getVod_id()).isEqualTo("tmdb:tv:71");
            assertThat(movie.getVod_name()).isEqualTo("明日综艺");
        });
        server.verify();
    }

    @Test
    void varietyWithoutFiltersUsesHotDefaults() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/3/discover/tv");
                    assertThat(request.getURI().getQuery()).contains(
                            "sort_by=popularity.desc",
                            "with_genres=10764%7C10767"
                    );
                })
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        MovieList result = service.list("tmdb:variety", "", 1, 20, Map.of());

        assertThat(result.getList()).isEmpty();
        server.verify();
    }

    @Test
    void platformCategoriesMapNetworksProvidersAndSorts() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        String futureLimit = LocalDate.now().plusDays(31).toString();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/3/discover/tv");
                    assertThat(request.getURI().getQuery()).contains(
                            "sort_by=first_air_date.desc",
                            "with_networks=1605",
                            "with_genres=16",
                            "include_null_first_air_dates=false",
                            "first_air_date.lte=" + futureLimit
                    );
                })
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/3/discover/movie");
                    assertThat(request.getURI().getQuery()).contains(
                            "sort_by=vote_average.desc",
                            "watch_region=KR",
                            "with_watch_providers=1899",
                            "include_video=false",
                            "vote_count.gte=50"
                    );
                })
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        service.list("tmdb:platform_tv", "", 1, 20, Map.of(
                "content", "anime",
                "network", "1605",
                "sort_by", "first_air_date.desc"
        ));
        service.list("tmdb:platform_movie", "", 1, 20, Map.of(
                "provider", "1899",
                "watch_region", "KR",
                "sort_by", "vote_average.desc"
        ));

        server.verify();
    }

    @Test
    void platformCategoriesRejectUnknownFilterValues() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> assertThat(request.getURI().getQuery()).contains(
                        "sort_by=popularity.desc",
                        "with_networks=2007",
                        "without_genres=16,10764,10767"
                ))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        service.list("tmdb:platform_tv", "", 1, 20, Map.of(
                "content", "unknown",
                "network", "../../network",
                "sort_by", "invalid"
        ));

        server.verify();
    }

    @Test
    void animeUsesDiscoverFiltersCapsPagesAndFallsBackToBackdrop() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/3/discover/movie");
                    assertThat(request.getURI().getQuery()).contains(
                            "page=500",
                            "sort_by=vote_average.desc",
                            "with_genres=16",
                            "with_origin_country=CN",
                            "primary_release_year=2026",
                            "vote_count.gte=20"
                    );
                })
                .andRespond(withSuccess("""
                        {
                          "page": 500,
                          "total_pages": 900,
                          "total_results": 20000,
                          "results": [
                            {
                              "id": 88,
                              "title": "测试动画",
                              "poster_path": null,
                              "backdrop_path": "/backdrop.jpg",
                              "release_date": "2026-01-02",
                              "vote_average": 9.0
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        MovieList result = service.list("tmdb:anime", "", 999, 20, Map.of(
                "kind", "movie",
                "anime_region", "CN",
                "sort_by", "vote_average.desc",
                "year", "2026"
        ));

        assertThat(result.getPage()).isEqualTo(500);
        assertThat(result.getPagecount()).isEqualTo(500);
        assertThat(result.getList()).singleElement().satisfies(movie -> {
            assertThat(movie.getVod_id()).isEqualTo("tmdb:movie:88");
            assertThat(movie.getVod_pic()).isEqualTo("https://image.tmdb.org/t/p/w500/backdrop.jpg");
        });
        server.verify();
    }

    @Test
    void trendingRejectsInvalidPathValuesAndCachesResult() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> assertThat(request.getURI().getPath()).isEqualTo("/3/trending/all/week"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        Map<String, String> filters = Map.of("mediaType", "../../movie", "time_window", "month");

        MovieList first = service.list("tmdb:trending", "", 1, 20, filters);
        MovieList second = service.list("tmdb:trending", "", 1, 20, filters);

        assertThat(second).isSameAs(first);
        server.verify();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tmdbFallsBackToStaleCacheWhenRefreshReturnsInvalidJson() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> assertThat(request.getURI().getPath()).isEqualTo("/3/tv/popular"))
                .andRespond(withSuccess("""
                        {
                          "results": [
                            {"id": 9, "name": "缓存剧集", "first_air_date": "2024-01-01"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), request -> assertThat(request.getURI().getPath()).isEqualTo("/3/tv/popular"))
                .andRespond(withSuccess("{", MediaType.APPLICATION_JSON));

        MovieList fresh = service.list("tmdb:tv_popular", "", 1, 20, Map.of());
        Cache<String, MovieList> freshCache = (Cache<String, MovieList>) ReflectionTestUtils.getField(service, "listCache");
        assertThat(freshCache).isNotNull();
        freshCache.invalidateAll();

        MovieList stale = service.list("tmdb:tv_popular", "", 1, 20, Map.of());

        assertThat(stale).isSameAs(fresh);
        assertThat(stale.getList()).singleElement()
                .satisfies(movie -> assertThat(movie.getVod_name()).isEqualTo("缓存剧集"));
        server.verify();
    }

    @Test
    void discoverMapsExtendedFilters() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> assertThat(request.getURI().getQuery()).contains(
                        "sort_by=revenue.desc",
                        "with_origin_country=JP%7CKR",
                        "with_original_language=ja",
                        "vote_average.gte=8",
                        "vote_count.gte=100",
                        "with_runtime.gte=90",
                        "with_runtime.lte=120"
                ))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        service.list("tmdb:discover_movie", "", 1, 20, Map.of(
                "sort_by", "revenue.desc",
                "origin_group", "JP|KR",
                "with_original_language", "ja",
                "vote_average.gte", "8",
                "vote_count.gte", "100",
                "runtime", "medium"
        ));

        server.verify();
    }

    @Test
    void tmdbRetriesTemporaryServerFailure() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> assertThat(request.getURI().getPath()).isEqualTo("/3/movie/upcoming"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), request -> assertThat(request.getURI().getPath()).isEqualTo("/3/movie/upcoming"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        MovieList result = service.list("tmdb:movie_upcoming", "", 1, 20, Map.of("region", "JP"));

        assertThat(result.getList()).isEmpty();
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

    @Test
    void subscriptionCategoryExcludesMovieCategoriesAndMovieFilterOptions() {
        CategoryList douban = new CategoryList();
        douban.setCategories(List.of(
                doubanCategory("hot_tv", "热门电视剧"),
                doubanCategory("hot_movie", "热门电影"),
                doubanCategory("movie_top250", "电影Top250"),
                doubanCategory("suggestion_movie", "电影推荐"),
                doubanCategory("tv_korean", "韩剧")
        ));
        when(telegramService.categoryDouban()).thenReturn(douban);

        CategoryList result = service.subscriptionCategory();

        assertThat(result.getCategories())
                .extracting(Category::getType_id)
                .doesNotContain(
                        "douban:hot_movie", "douban:movie_top250", "douban:suggestion_movie",
                        "tmdb:movie_popular", "tmdb:movie_top_rated", "tmdb:movie_now_playing", "tmdb:movie_upcoming",
                        "tmdb:platform_movie", "tmdb:discover_movie")
                .contains("douban:hot_tv", "douban:tv_korean", "tmdb:tv_popular", "tmdb:tv_on_the_air",
                        "tmdb:trending", "tmdb:anime", "tmdb:variety", "tmdb:platform_tv", "tmdb:discover_tv");
        assertThat(result.getCategories().get(0).getType_id()).isEqualTo("douban:hot_tv");
        assertThat(result.getTotal()).isEqualTo(result.getCategories().size());
        assertThat(result.getFilters().get("douban:hot_tv").get(0).getValue())
                .extracting(FilterValue::getV)
                .contains("", "中国大陆", "日本", "美国");
        assertThat(result.getFilters().get("tmdb:trending").get(0).getValue())
                .extracting(FilterValue::getV)
                .containsExactly("all", "tv");
        assertThat(result.getFilters().get("tmdb:anime").stream()
                .filter(filter -> "kind".equals(filter.getKey())).findFirst().orElseThrow().getValue())
                .extracting(FilterValue::getV)
                .containsExactly("tv");
    }

    @Test
    void subscriptionCategoryLiteModeExcludesMovieBillboardOptions() {
        when(subscriptionSourceService.getBuiltinExtend("csp_PianDan")).thenReturn("{\"mode\":\"lite\"}");
        CategoryList douban = new CategoryList();
        douban.setCategories(List.of(
                doubanCategory("hot_movie", "热门电影"),
                doubanCategory("hot_tv", "热门电视剧")
        ));
        when(telegramService.categoryDouban()).thenReturn(douban);

        CategoryList result = service.subscriptionCategory();

        assertThat(result.getCategories())
                .extracting(Category::getType_id)
                .doesNotContain("douban:hot_movie", "tmdb:movie", "tmdb:discover_movie")
                .contains("douban:hot_tv", "douban:billboard", "tmdb:tv", "tmdb:discover_tv");
        assertThat(result.getFilters().get("douban:billboard").get(0).getValue())
                .extracting(FilterValue::getV)
                .doesNotContain("movie_top250", "movie_real_time_hotest", "movie_weekly_best", "suggestion_movie")
                .contains("tv_real_time_hotest", "tv_chinese_best_weekly", "suggestion_tv", "tv_animation");
        assertThat(result.getTotal()).isEqualTo(result.getCategories().size());
    }

    @Test
    void fixedTmdbListWithoutFiltersKeepsNativeEndpoint() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/3/movie/popular");
                    assertThat(request.getURI().getQuery()).contains("region=CN");
                })
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        service.list("tmdb:movie_popular", "", 1, 20, Map.of());

        server.verify();
    }

    @Test
    void fixedTmdbListWithRegionAndSortUpgradesToDiscover() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        String futureLimit = LocalDate.now().plusDays(31).toString();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/3/discover/tv");
                    assertThat(request.getURI().getQuery()).contains(
                            "sort_by=first_air_date.desc",
                            "with_origin_country=JP%7CKR",
                            "first_air_date.lte=" + futureLimit
                    );
                })
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        service.list("tmdb:tv_popular", "", 1, 20, Map.of(
                "origin_group", "JP|KR",
                "sort_by", "first_air_date.desc"
        ));

        server.verify();
    }

    @Test
    void fixedTmdbListKeepsScheduleWindowAndNativeSort() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        LocalDate today = LocalDate.now();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/3/discover/movie");
                    assertThat(request.getURI().getQuery()).contains(
                            "sort_by=popularity.desc",
                            "with_origin_country=CN%7CHK%7CTW",
                            "primary_release_date.gte=" + today.minusDays(42),
                            "primary_release_date.lte=" + today
                    );
                })
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/3/discover/tv");
                    assertThat(request.getURI().getQuery()).contains(
                            "sort_by=popularity.desc",
                            "air_date.gte=" + today,
                            "air_date.lte=" + today
                    );
                })
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/3/discover/tv");
                    assertThat(request.getURI().getQuery()).contains(
                            "sort_by=vote_average.desc",
                            "vote_count.gte=50"
                    );
                })
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        service.list("tmdb:movie_now_playing", "", 1, 20, Map.of("origin_group", "CN|HK|TW"));
        service.list("tmdb:tv_airing_today", "", 1, 20, Map.of("sort_by", "popularity.desc"));
        service.list("tmdb:tv_top_rated", "", 1, 20, Map.of("origin_group", "US|GB"));

        server.verify();
    }

    private Category doubanCategory(String typeId, String name) {
        Category category = new Category();
        category.setType_id(typeId);
        category.setType_name(name);
        category.setType_flag(0);
        return category;
    }

    @Test
    void tmdbDetailCachedAcrossCallsWithinWindow() {
        when(settingRepository.findById("tmdb_api_key")).thenReturn(Optional.empty());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        String body = "{\"name\":\"外滩探秘\",\"overview\":\"简介\",\"first_air_date\":\"2024-01-01\","
                + "\"vote_average\":7.5,\"genres\":[{\"name\":\"纪录\"}],"
                + "\"credits\":{\"cast\":[{\"name\":\"甲\"},{\"name\":\"乙\"}]},"
                + "\"seasons\":[{\"season_number\":0},{\"season_number\":1,\"air_date\":\"2024-01-01\",\"episode_count\":8},"
                + "{\"season_number\":2,\"air_date\":null,\"episode_count\":0}]}"; // S2=已续订未开播占位季
        server.expect(once(), request -> assertThat(request.getURI().getPath()).isEqualTo("/3/tv/100757"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        MovieDetail first = service.tmdbDetail("tv", 100757);
        MovieDetail second = service.tmdbDetail("tv", 100757); // 「媒体信息」条目:命中缓存,不重打 TMDB

        server.verify(); // 只放行一次网络请求
        assertThat(second).isNotNull();
        assertThat(second.getVod_name()).isEqualTo("外滩探秘");
        assertThat(second.getType_name()).isEqualTo("纪录"); // 类型归 type_name,不再冒充演员
        assertThat(second.getVod_actor()).isEqualTo("甲 / 乙");
        assertThat(first).isSameAs(second);
        assertThat((List<?>) second.getExt()).singleElement().isEqualTo(1); // season 0(特典)与未开播占位季被滤掉
    }
}
