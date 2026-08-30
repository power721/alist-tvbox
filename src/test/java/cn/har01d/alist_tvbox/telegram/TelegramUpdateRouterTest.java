package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.dto.telegram.BotCallbackQuery;
import cn.har01d.alist_tvbox.dto.telegram.BotChat;
import cn.har01d.alist_tvbox.dto.telegram.BotMessage;
import cn.har01d.alist_tvbox.dto.telegram.BotUpdate;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.entity.UserRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Update 分发:chat→uid 绑定解析(用户级/全局/未绑)、命令路由、搜索会话、限流、
 * callback answer 收口与越权(BadRequestException)统一 notFound 文案。
 */
class TelegramUpdateRouterTest {

    private final TelegramSubscriptionBot bot = mock(TelegramSubscriptionBot.class);
    private final TelegramBotClient client = mock(TelegramBotClient.class);
    private final SettingRepository settingRepository = mock(SettingRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private TelegramUpdateRouter router;

    @BeforeEach
    void setUp() {
        router = new TelegramUpdateRouter(bot, client, settingRepository, userRepository);
        User admin = new User();
        admin.setId(1);
        admin.setRole(Role.ADMIN);
        when(userRepository.findFirstByRoleOrderByIdAsc(Role.ADMIN)).thenReturn(Optional.of(admin));
        when(client.sendMessage(anyString(), anyString(), anyString(), any())).thenReturn(0L);
        // 绑定:chat 100 → uid 5;chat 200 → 全局 → admin 1;其余未绑定
        when(settingRepository.findByNameStartingWith("msub_telegram_chat_id")).thenReturn(List.of(
                new Setting("msub_telegram_chat_id:u5", "100"),
                new Setting("msub_telegram_chat_id", "200")));
        when(settingRepository.findById("msub_telegram_chat_id"))
                .thenReturn(Optional.of(new Setting("msub_telegram_chat_id", "200")));
    }

    private BotUpdate message(long chatId, String text) {
        BotUpdate update = new BotUpdate();
        BotMessage msg = new BotMessage();
        BotChat chat = new BotChat();
        chat.setId(chatId);
        msg.setChat(chat);
        msg.setText(text);
        update.setUpdateId(1);
        update.setMessage(msg);
        return update;
    }

    private BotUpdate callback(long chatId, long messageId, String data) {
        BotUpdate update = new BotUpdate();
        BotCallbackQuery query = new BotCallbackQuery();
        query.setId("q1");
        BotMessage msg = new BotMessage();
        BotChat chat = new BotChat();
        chat.setId(chatId);
        msg.setChat(chat);
        msg.setMessageId(messageId);
        query.setMessage(msg);
        query.setData(data);
        update.setUpdateId(2);
        update.setCallbackQuery(query);
        return update;
    }

    @Test
    void resolveUidFromUserLevelRow() {
        assertEqualsUid(100L, 5);
    }

    @Test
    void resolveGlobalRowToSmallestAdmin() {
        assertEqualsUid(200L, 1);
    }

    @Test
    void unboundChatResolvesNull() {
        org.junit.jupiter.api.Assertions.assertNull(router.resolveUid(999L));
    }

    private void assertEqualsUid(long chatId, int expected) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, router.resolveUid(chatId));
    }

    @Test
    void unboundChatGetsBindingGuideOnCommandOnly() {
        router.dispatch("TOKEN", message(999L, "/start"));
        verify(client).sendMessage(eq("TOKEN"), eq("999"), contains("Chat ID"), any());
        verify(bot, never()).sendMenu(anyString(), anyString());
    }

    @Test
    void unboundChatSilentlyIgnoresPlainText() {
        router.dispatch("TOKEN", message(999L, "你好"));
        verify(client, never()).sendMessage(anyString(), anyString(), anyString(), any());
    }

    @Test
    void boundStartShowsMenu() {
        router.dispatch("TOKEN", message(100L, "/start"));
        verify(bot).sendMenu("TOKEN", "100");
    }

    @Test
    void searchCommandThenTextRunsSearch() {
        when(bot.sendSearchPrompt("TOKEN", "100")).thenReturn(55L);
        router.dispatch("TOKEN", message(100L, "/search"));
        verify(bot).sendSearchPrompt("TOKEN", "100");
        router.dispatch("TOKEN", message(100L, "斗破苍穹"));
        verify(bot).runSearch("TOKEN", "100", 5, "斗破苍穹", 55L);
    }

    @Test
    void searchRateLimitedWithinCooldown() {
        when(bot.sendSearchPrompt("TOKEN", "100")).thenReturn(55L);
        router.dispatch("TOKEN", message(100L, "/search"));
        router.dispatch("TOKEN", message(100L, "第一次"));
        router.dispatch("TOKEN", message(100L, "/search"));
        router.dispatch("TOKEN", message(100L, "第二次"));
        verify(bot, org.mockito.Mockito.times(1)).runSearch(anyString(), anyString(), anyInt(), anyString(), anyLong());
    }

    @Test
    void searchCommandWithArgsRunsSearchDirectly() {
        when(bot.sendSearching("TOKEN", "100", "庆余年")).thenReturn(66L);
        router.dispatch("TOKEN", message(100L, "/search 庆余年"));
        verify(bot, never()).sendSearchPrompt(anyString(), anyString()); // 不进输入会话
        verify(bot).runSearch("TOKEN", "100", 5, "庆余年", 66L);
    }

    @Test
    void searchCommandArgsSurviveBotMentionAndExtraSpaces() {
        when(bot.sendSearching(anyString(), anyString(), anyString())).thenReturn(66L);
        router.dispatch("TOKEN", message(100L, "/search@my_bot   庆余年 "));
        verify(bot).runSearch("TOKEN", "100", 5, "庆余年", 66L);
    }

    @Test
    void searchCommandWithArgsRateLimitedWithinCooldown() {
        when(bot.sendSearching(anyString(), anyString(), anyString())).thenReturn(66L);
        router.dispatch("TOKEN", message(100L, "/search 庆余年"));
        router.dispatch("TOKEN", message(100L, "/search 斗破苍穹"));
        verify(bot, org.mockito.Mockito.times(1)).runSearch(anyString(), anyString(), anyInt(), anyString(), anyLong());
        verify(client).sendMessage(eq("TOKEN"), eq("100"), contains("操作太快"), any());
    }

    @Test
    void callbackFromUnboundChatAnsweredWithoutBusiness() {
        router.dispatch("TOKEN", callback(999L, 10, "subs:0"));
        verify(client).answerCallbackQuery("TOKEN", "q1", "未绑定账号");
        verify(bot, never()).handleCallback(anyString(), anyInt(), any(), any());
    }

    @Test
    void malformedCallbackAnsweredAndDropped() {
        router.dispatch("TOKEN", callback(100L, 10, "garbage:zzz"));
        verify(client).answerCallbackQuery("TOKEN", "q1", null);
        verify(bot, never()).handleCallback(anyString(), anyInt(), any(), any());
    }

    @Test
    void searchCallbackEditsPromptInPlace() {
        router.dispatch("TOKEN", callback(100L, 10, "search"));
        verify(bot).edit(eq("TOKEN"), eq("100"), eq(10L), any());
        verify(client).answerCallbackQuery("TOKEN", "q1", null);
    }

    @Test
    void cancelCallbackBacksToMenu() {
        router.dispatch("TOKEN", callback(100L, 10, "cancel"));
        verify(bot).edit(eq("TOKEN"), eq("100"), eq(10L), any());
    }

    @Test
    void ownershipViolationRendersNotFound() {
        when(bot.handleCallback(anyString(), anyInt(), any(), any()))
                .thenThrow(new BadRequestException("无权访问该订阅"));
        router.dispatch("TOKEN", callback(100L, 10, "sub:77"));
        verify(bot).edit(eq("TOKEN"), eq("100"), eq(10L),
                org.mockito.ArgumentMatchers.argThat(r -> r.text().contains("不存在或已删除")));
        verify(client).answerCallbackQuery("TOKEN", "q1", null);
    }

    @Test
    void callbackToastPropagatesToAnswer() {
        when(bot.handleCallback(anyString(), anyInt(), any(), any())).thenReturn("已开始巡检");
        router.dispatch("TOKEN", callback(100L, 10, "subchk:7"));
        verify(client).answerCallbackQuery("TOKEN", "q1", "已开始巡检");
    }

    @Test
    void pianDanCommandOpensCategories() {
        router.dispatch("TOKEN", message(100L, "/piandan"));
        router.dispatch("TOKEN", message(100L, "/pd"));
        verify(bot, org.mockito.Mockito.times(2)).sendPianDan("TOKEN", "100");
    }

    @Test
    void calendarCommandCarriesResolvedUid() {
        router.dispatch("TOKEN", message(100L, "/calendar"));
        router.dispatch("TOKEN", message(100L, "/cal"));
        verify(bot, org.mockito.Mockito.times(2)).sendCalendar("TOKEN", "100", 5);
    }
}
