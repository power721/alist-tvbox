package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.PianDanService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PianDanControllerTest {
    @Mock
    private PianDanService pianDanService;
    @Mock
    private SubscriptionService subscriptionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PianDanController(pianDanService, subscriptionService)).build();
    }

    @Test
    void categoryUsesNavigationCategories() throws Exception {
        CategoryList categories = new CategoryList();
        categories.setTotal(3);
        when(pianDanService.category()).thenReturn(categories);

        mockMvc.perform(get("/pian-dan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));

        verify(subscriptionService).checkToken("");
        verify(pianDanService).category();
        verifyNoMoreInteractions(pianDanService);
    }

    @Test
    void homeUsesCombinedNavigationList() throws Exception {
        MovieList movies = new MovieList();
        movies.setTotal(20);
        when(pianDanService.home()).thenReturn(movies);

        mockMvc.perform(get("/pian-dan").param("t", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(20));

        verify(pianDanService).home();
    }

    @Test
    void listPassesPaginationAndFilters() throws Exception {
        MovieList movies = new MovieList();
        movies.setPage(2);
        when(pianDanService.list(
                eq("tmdb:discover_movie"),
                eq(null),
                eq(2),
                eq(20),
                eq(Map.of("t", "tmdb:discover_movie", "pg", "2", "sort_by", "popularity.desc"))))
                .thenReturn(movies);

        mockMvc.perform(get("/pian-dan")
                        .param("t", "tmdb:discover_movie")
                        .param("pg", "2")
                        .param("sort_by", "popularity.desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2));

        verify(pianDanService).list(
                "tmdb:discover_movie",
                null,
                2,
                20,
                Map.of("t", "tmdb:discover_movie", "pg", "2", "sort_by", "popularity.desc"));
    }

    @Test
    void tokenRouteChecksSubscriptionToken() throws Exception {
        when(pianDanService.category()).thenReturn(new CategoryList());

        mockMvc.perform(get("/pian-dan/token-1"))
                .andExpect(status().isOk());

        verify(subscriptionService).checkToken("token-1");
        verify(pianDanService).category();
    }
}
