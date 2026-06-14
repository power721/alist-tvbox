package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TelegramService;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TelegramControllerTest {
    @Mock
    private TelegramChannelRepository telegramChannelRepository;
    @Mock
    private TelegramService telegramService;
    @Mock
    private SubscriptionService subscriptionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TelegramController controller = new TelegramController(
                telegramChannelRepository,
                telegramService,
                subscriptionService,
                new ObjectMapper()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void tgscCategoryUsesPrivateTgSearchCategoriesOnly() throws Exception {
        Category category = new Category();
        category.setType_id("type:5");
        category.setType_name("夸克");
        CategoryList categories = new CategoryList();
        categories.setCategories(List.of(category));
        categories.setTotal(1);
        categories.setLimit(1);
        when(telegramService.categoryTgSearch()).thenReturn(categories);

        mockMvc.perform(get("/tgsc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.class[0].type_id").value("type:5"))
                .andExpect(jsonPath("$.class[0].type_name").value("夸克"));

        verify(subscriptionService).checkToken("");
        verify(telegramService).categoryTgSearch();
        verifyNoMoreInteractions(telegramService);
    }

    @Test
    void tgscSearchPassesPaginationToPrivateTgSearch() throws Exception {
        MovieList movies = new MovieList();
        movies.setPage(2);
        movies.setLimit(30);
        when(telegramService.searchTgSearchMovies("ubuntu", 2, 30)).thenReturn(movies);

        mockMvc.perform(get("/tgsc")
                        .param("wd", "ubuntu")
                        .param("pg", "2")
                        .param("size", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.limit").value(30));

        verify(subscriptionService).checkToken("");
        verify(telegramService).searchTgSearchMovies("ubuntu", 2, 30);
        verifyNoMoreInteractions(telegramService);
    }

    @Test
    void tgscListPassesPaginationToPrivateTgSearch() throws Exception {
        MovieList movies = new MovieList();
        movies.setPage(3);
        movies.setLimit(20);
        when(telegramService.listTgSearch("type:5", 3, 20)).thenReturn(movies);

        mockMvc.perform(get("/tgsc")
                        .param("t", "type:5")
                        .param("pg", "3")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(3))
                .andExpect(jsonPath("$.limit").value(20));

        verify(subscriptionService).checkToken("");
        verify(telegramService).listTgSearch("type:5", 3, 20);
        verifyNoMoreInteractions(telegramService);
    }
}
