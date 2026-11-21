package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.LiveFollowDto;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.live.web.LiveController;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.har01d.alist_tvbox.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DiagT4 {
    @Mock private cn.har01d.alist_tvbox.service.SubscriptionService subscriptionService;
    @Mock private TvBoxController tvBoxController;
    @Mock private PianDanController pianDanController;
    @Mock private MediaLibraryController mediaLibraryController;
    @Mock private BiliBiliController biliBiliController;
    @Mock private cn.har01d.alist_tvbox.youtube.YoutubeController youtubeController;
    @Mock private cn.har01d.alist_tvbox.youtube.YtdlpParseService ytdlpParseService;
    @Mock private EmbyController embyController;
    @Mock private JellyfinController jellyfinController;
    @Mock private FeiniuController feiniuController;
    @Mock private LiveController liveController;
    @Mock private TelegramController telegramController;
    @Mock private RemoteSearchController remoteSearchController;
    @Mock private PlayController playController;
    @Mock private cn.har01d.alist_tvbox.service.ParseService parseService;
    @Mock private cn.har01d.alist_tvbox.service.PluginWorkerService pluginWorkerService;
    @Mock private cn.har01d.alist_tvbox.entity.PluginRepository pluginRepository;
    @Mock private cn.har01d.alist_tvbox.service.T4DetailEnhancer t4DetailEnhancer;
    private T4Controller controller;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        controller = new T4Controller(subscriptionService, new AppProperties(), tvBoxController, pianDanController,
                mediaLibraryController, biliBiliController, youtubeController, ytdlpParseService, embyController, jellyfinController,
                feiniuController, liveController, telegramController, remoteSearchController,
                playController, parseService, pluginWorkerService, pluginRepository, new com.fasterxml.jackson.databind.ObjectMapper(), t4DetailEnhancer);
        lenient().when(subscriptionService.resolveT4Token(anyString())).thenAnswer(inv -> inv.getArgument(0));
        request = new MockHttpServletRequest("GET", "/t4/tok/v/csp_Push");
        response = new MockHttpServletResponse();
    }

    @Test
    void diag() throws Exception {
        Object result = controller.vod("tok", "csp_Push", null,
                "https://example.com/video/movie.mp4", null, null, null, null, null, null, request, response);
        System.out.println("DIAG result class=" + (result == null ? "null" : result.getClass()) + " value=" + result);
        if (result instanceof Map<?, ?> m) {
            System.out.println("DIAG keys=" + m.keySet());
        }
    }
}
