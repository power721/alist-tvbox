package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.service.BiliBiliService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.ProxyService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PlayControllerTest {
    @Mock
    private TvBoxService tvBoxService;
    @Mock
    private BiliBiliService biliBiliService;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private ProxyService proxyService;
    @Mock
    private MediaSubscriptionService mediaSubscriptionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PlayController controller = new PlayController(tvBoxService, biliBiliService, subscriptionService, proxyService, mediaSubscriptionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void playShouldRejectRequestsWithoutPlayableIdentifier() throws Exception {
        mockMvc.perform(get("/play/test-token"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(tvBoxService, biliBiliService, proxyService, mediaSubscriptionService);
    }

    @Test
    void playShouldResolveMediaSubscriptionEpisodeId() throws Exception {
        when(mediaSubscriptionService.resolveUid("test-token")).thenReturn(7);
        when(mediaSubscriptionService.playEpisode(7, 5, 2, null, null))
                .thenReturn(Map.of("url", "https://example.com/e2.mp4"));

        mockMvc.perform(get("/play/test-token").param("id", "msubep-5-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.com/e2.mp4"));
    }

    @Test
    void playShouldRejectMalformedMediaSubscriptionEpisodeId() throws Exception {
        mockMvc.perform(get("/play/test-token").param("id", "msubep-5"))
                .andExpect(status().isBadRequest());
    }
}
