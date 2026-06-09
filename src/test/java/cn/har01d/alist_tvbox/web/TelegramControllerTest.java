package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannel;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannelSelectionRequest;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccountChannelSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccount;
import cn.har01d.alist_tvbox.dto.tg.TgProviderChannelSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderLoginResponse;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TelegramService;
import cn.har01d.alist_tvbox.service.TgPrivateChannelService;
import cn.har01d.alist_tvbox.service.TgProviderClient;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    @Mock
    private TgProviderClient tgProviderClient;
    @Mock
    private TgPrivateChannelService tgPrivateChannelService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TelegramController controller = new TelegramController(
                telegramChannelRepository,
                telegramService,
                subscriptionService,
                new ObjectMapper(),
                tgProviderClient,
                tgPrivateChannelService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void shouldReturnEmptyTelegramUserWhenProviderHasNoAccounts() throws Exception {
        when(tgProviderClient.accounts()).thenReturn(List.of());

        mockMvc.perform(get("/api/telegram/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(0))
                .andExpect(jsonPath("$.username").value(""))
                .andExpect(jsonPath("$.phone").value(""));
    }

    @Test
    void shouldReturnFirstProviderAccountAsTelegramUser() throws Exception {
        when(tgProviderClient.accounts()).thenReturn(List.of(
                new TgProviderAccount(9, "tester", "Test", "User", "+86138")));

        mockMvc.perform(get("/api/telegram/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.username").value("tester"))
                .andExpect(jsonPath("$.first_name").value("Test"))
                .andExpect(jsonPath("$.last_name").value("User"))
                .andExpect(jsonPath("$.phone").value("+86138"));
    }

    @Test
    void shouldProxyLoginRequestsToProvider() throws Exception {
        when(tgProviderClient.sendCode("+86138")).thenReturn(new TgProviderLoginResponse("LOGIN_REQUIRED", false));
        when(tgProviderClient.signIn("+86138", "1234")).thenReturn(new TgProviderLoginResponse("LOGIN_REQUIRED", true));

        mockMvc.perform(post("/api/telegram/login/send-code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"+86138\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOGIN_REQUIRED"));

        mockMvc.perform(post("/api/telegram/login/sign-in")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"+86138\",\"code\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password_required").value(true));
    }

    @Test
    void shouldDeleteFirstAccountOnLogout() throws Exception {
        when(tgProviderClient.accounts()).thenReturn(List.of(
                new TgProviderAccount(9, "tester", "Test", "User", "+86138")));

        mockMvc.perform(post("/api/telegram/logout"))
                .andExpect(status().isOk());

        verify(tgProviderClient).deleteAccount(9);
    }

    @Test
    void shouldUsePublicTelegramSearchForBrowserSearchEndpoint() throws Exception {
        Message legacyMessage = Message.fromProvider(2, "legacy", "名称：公开搜索", "https://pan.baidu.com/s/1abc", "2026-06-07T12:00:00Z");
        when(telegramService.search("短剧", 100, false, false)).thenReturn(List.of(legacyMessage));

        mockMvc.perform(get("/api/telegram/search").param("wd", "短剧"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].channel").value("legacy"));

        verify(telegramService).search(eq("短剧"), eq(100), eq(false), eq(false));
    }

    @Test
    void shouldExposePrivateChannelsAndSaveSelection() throws Exception {
        TgPrivateChannel channel = new TgPrivateChannel(7, 1, 1001, "VIP", "vip_share", "channel", 88, "2026-06-07T12:00:00Z", true, "2026-06-07T12:05:00Z", true);
        when(tgPrivateChannelService.channels()).thenReturn(List.of(channel));
        when(tgPrivateChannelService.saveChannels(new TgPrivateChannelSelectionRequest(List.of(7L), Map.of(7L, "短剧频道")))).thenReturn(List.of(channel));

        mockMvc.perform(get("/api/telegram/private/channels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].web_access").value(true))
                .andExpect(jsonPath("$[0].web_access_checked_at").value("2026-06-07T12:05:00Z"))
                .andExpect(jsonPath("$[0].enabled").value(true));

        mockMvc.perform(put("/api/telegram/private/channels")
                        .contentType(APPLICATION_JSON)
                        .content("{\"channel_ids\":[7],\"aliases\":{\"7\":\"短剧频道\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("vip_share"));
    }

    @Test
    void shouldCheckPrivateChannelWebAccess() throws Exception {
        TgPrivateChannel channel = new TgPrivateChannel(7, 1, 1001, "VIP", "vip_share", "channel", 88, "2026-06-07T12:00:00Z", true, "2026-06-07T12:05:00Z", true);
        when(tgPrivateChannelService.checkWebAccess(7L)).thenReturn(channel);
        when(tgPrivateChannelService.checkWebAccess()).thenReturn(List.of(channel));

        mockMvc.perform(post("/api/telegram/private/channels/7/web-access/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.web_access").value(true));

        mockMvc.perform(post("/api/telegram/private/channels/web-access/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].web_access").value(true));

        verify(tgPrivateChannelService).checkWebAccess(7L);
        verify(tgPrivateChannelService).checkWebAccess();
    }

    @Test
    void shouldSearchPrivateChannelsFromSeparateBrowserEndpoint() throws Exception {
        Message providerMessage = Message.fromProvider(1, "vip_share", "名称：私密短剧", "https://pan.quark.cn/s/private", "2026-06-07T12:00:00Z");
        when(tgPrivateChannelService.search("短剧", 100)).thenReturn(List.of(providerMessage));

        mockMvc.perform(get("/api/telegram/private/search").param("wd", "短剧"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].link").value("https://pan.quark.cn/s/private"));
    }

    @Test
    void shouldSyncPrivateChannelListFromProviderAccounts() throws Exception {
        when(tgPrivateChannelService.syncAccountChannels()).thenReturn(List.of(
                new TgProviderAccountChannelSyncResponse("3", "queued", List.of())));

        mockMvc.perform(post("/api/telegram/private/channels/sync-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].job_id").value("3"))
                .andExpect(jsonPath("$[0].status").value("queued"));
    }

    @Test
    void shouldSyncSinglePrivateChannel() throws Exception {
        when(tgPrivateChannelService.syncChannel(7L)).thenReturn(new TgProviderChannelSyncResponse(null, null, 3, 2));

        mockMvc.perform(post("/api/telegram/private/channels/7/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").value(3))
                .andExpect(jsonPath("$.links").value(2));
    }

    @Test
    void shouldRouteTgscLikeTgSearch() throws Exception {
        when(tgPrivateChannelService.searchMovies("短剧", 20)).thenReturn(new MovieList());
        when(tgPrivateChannelService.list("7", 2)).thenReturn(new MovieList());
        when(tgPrivateChannelService.category()).thenReturn(new CategoryList());

        mockMvc.perform(get("/tgsc").param("wd", "短剧"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/tgsc").param("t", "7").param("pg", "2"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/tgsc"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldCheckTokenForTgscTokenEndpoint() throws Exception {
        when(tgPrivateChannelService.searchMovies("短剧", 20)).thenReturn(new MovieList());

        mockMvc.perform(get("/tgsc/token123").param("wd", "短剧"))
                .andExpect(status().isOk());

        verify(subscriptionService).checkToken("token123");
    }
}
