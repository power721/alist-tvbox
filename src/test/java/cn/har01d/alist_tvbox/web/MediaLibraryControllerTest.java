package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaLibraryControllerTest {
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private MediaSubscriptionService mediaSubscriptionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MediaLibraryController(subscriptionService, mediaSubscriptionService))
                .setControllerAdvice(new RestErrorHandler())
                .build();
        when(mediaSubscriptionService.resolveUid("token-a")).thenReturn(7);
    }

    private MovieList list(String vodId) {
        MovieList result = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(vodId);
        result.setList(List.of(detail));
        return result;
    }

    @Test
    void homeContentReturnsStatusCategories() throws Exception {
        mockMvc.perform(get("/media/token-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.class[0].type_id").value("all"))
                .andExpect(jsonPath("$.class[0].type_name").value("全部"))
                .andExpect(jsonPath("$.class[1].type_id").value("active"))
                .andExpect(jsonPath("$.class[2].type_id").value("ended"));
    }

    @Test
    void homeVideoContentReturnsAllSubscriptions() throws Exception {
        when(mediaSubscriptionService.contentList(7)).thenReturn(list("msub:1"));
        mockMvc.perform(get("/media/token-a").param("t", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_id").value("msub:1"));
    }

    @Test
    void categoryContentFiltersByStatus() throws Exception {
        when(mediaSubscriptionService.contentList(eq(7), eq("active"), isNull())).thenReturn(list("msub:2"));
        mockMvc.perform(get("/media/token-a").param("t", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_id").value("msub:2"));
    }

    @Test
    void searchContentMatchesKeyword() throws Exception {
        when(mediaSubscriptionService.contentList(eq(7), isNull(), eq("凡人"))).thenReturn(list("msub:3"));
        mockMvc.perform(get("/media/token-a").param("wd", "凡人"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_id").value("msub:3"));
    }

    @Test
    void detailContentDelegatesToSubscriptionDetail() throws Exception {
        when(mediaSubscriptionService.contentDetail(eq(7), eq(3), isNull(), isNull())).thenReturn(list("msub:3"));
        mockMvc.perform(get("/media/token-a").param("id", "msub:3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_id").value("msub:3"));
    }

    @Test
    void detailContentRejectsUnknownIdPrefix() throws Exception {
        mockMvc.perform(get("/media/token-a").param("id", "tg:123"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailContentRejectsMalformedId() throws Exception {
        mockMvc.perform(get("/media/token-a").param("id", "msub:abc"))
                .andExpect(status().isBadRequest());
    }
}
