package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.dto.MediaSubscriptionDto;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.dto.telegram.BotCallbackQuery;
import cn.har01d.alist_tvbox.dto.telegram.BotChat;
import cn.har01d.alist_tvbox.dto.telegram.BotMessage;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务编排:搜索流(全源 metaSearch → 暂存 → 编辑)、追加订阅(元数据直绑参数 + 幂等短路)、
 * 退订确认、编辑失效降级新消息。
 */
class TelegramSubscriptionBotTest {

    private final MediaSubscriptionService subscriptionService = mock(MediaSubscriptionService.class);
    private final MediaSubscriptionCheckService checkService = mock(MediaSubscriptionCheckService.class);
    private final TelegramBotClient client = mock(TelegramBotClient.class);
    private TelegramSubscriptionBot bot;

    @BeforeEach
    void setUp() {
        bot = new TelegramSubscriptionBot(subscriptionService, checkService, client);
    }

    private MetadataSearchItem item(String provider, String id, String name) {
        MetadataSearchItem item = new MetadataSearchItem();
        item.setProvider(provider);
        item.setId(id);
        item.setName(name);
        item.setYear("2026");
        return item;
    }

    private BotCallbackQuery callback(long chatId, long messageId, String data) {
        BotCallbackQuery query = new BotCallbackQuery();
        query.setId("q1");
        BotMessage msg = new BotMessage();
        BotChat chat = new BotChat();
        chat.setId(chatId);
        msg.setChat(chat);
        msg.setMessageId(messageId);
        query.setMessage(msg);
        query.setData(data);
        return query;
    }

    private void runSearch(String keyword) {
        when(subscriptionService.metaSearch("", keyword)).thenReturn(Map.of(
                "items", List.of(item("tmdb", "42", "斗破苍穹"), item("douban", "99", "斗破苍穹 特别篇")),
                "errors", Map.of()));
        bot.runSearch("TOKEN", "100", 5, keyword, 55L);
    }

    @Test
    void runSearchEditsPromptIntoResults() {
        runSearch("斗破苍穹");
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("共 2 条"), any());
    }

    @Test
    void runSearchFailureEditsPromptIntoNotice() {
        when(subscriptionService.metaSearch("", "坏词")).thenThrow(new RuntimeException("provider down"));
        bot.runSearch("TOKEN", "100", 5, "坏词", 55L);
        // 提示消息仍有锚点:就地编辑成错误提示,不发新消息
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("搜索失败"), any());
    }

    @Test
    void keywordTooLongRejected() {
        bot.runSearch("TOKEN", "100", 5, "长".repeat(101), 55L);
        verify(subscriptionService, never()).metaSearch(anyString(), anyString());
    }

    @Test
    void addSubscriptionBindsMetadataDirectly() {
        runSearch("斗破苍穹"); // 暂存索引 0/1
        when(subscriptionService.isSubscribedTitle(5, "斗破苍穹")).thenReturn(false);
        MediaSubscriptionDto created = new MediaSubscriptionDto();
        created.setId(88);
        created.setName("斗破苍穹");
        when(subscriptionService.create(eq(5), any())).thenReturn(created);

        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "add:0"), TelegramCallbackData.parse("add:0"));

        ArgumentCaptor<cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest> captor = ArgumentCaptor.forClass(
                cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest.class);
        verify(subscriptionService).create(eq(5), captor.capture());
        assertEquals("斗破苍穹", captor.getValue().getName());
        assertEquals("tmdb", captor.getValue().getMetaProvider());
        assertEquals("42", captor.getValue().getMetaId());
        verify(checkService).checkAsync(5, 88);
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("已加入追剧"), any());
    }

    @Test
    void addSubscriptionIdempotentWhenTitleMatches() {
        runSearch("斗破苍穹");
        when(subscriptionService.isSubscribedTitle(5, "斗破苍穹")).thenReturn(true);
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "add:0"), TelegramCallbackData.parse("add:0"));
        verify(subscriptionService, never()).create(anyInt(), any());
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("已经在追剧列表"), any());
    }

    @Test
    void expiredSearchStateRedirectsToPrompt() {
        String result = bot.handleCallback("TOKEN", 5, callback(100L, 55L, "add:0"),
                TelegramCallbackData.parse("add:0"));
        assertEquals("搜索结果已过期,请重新搜索", result);
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("请直接输入剧名"), any());
    }

    @Test
    void deleteConfirmRemovesAndNotifies() {
        MediaSubscriptionDto dto = new MediaSubscriptionDto();
        dto.setId(7);
        dto.setName("测试剧");
        dto.setStatus(cn.har01d.alist_tvbox.entity.MediaSubscription.STATUS_ACTIVE);
        when(subscriptionService.detail(5, 7)).thenReturn(Map.of("subscription", dto));
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "subdel:7"), TelegramCallbackData.parse("subdel:7"));
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("确定退订"), any());

        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "subdelc:7"), TelegramCallbackData.parse("subdelc:7"));
        verify(subscriptionService).delete(5, 7);
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("已退订"), any());
    }

    @Test
    void checkCallbackReturnsToastAndStaysAsync() {
        String result = bot.handleCallback("TOKEN", 5, callback(100L, 55L, "subchk:7"),
                TelegramCallbackData.parse("subchk:7"));
        verify(checkService).checkAsync(5, 7);
        assertTrue(result.contains("巡检"));
    }

    @Test
    void lightCheckCallbackSurfacesText() {
        when(checkService.checkUpdateNow(5, 7)).thenReturn("官方已播 12 集,本地 10 集");
        String result = bot.handleCallback("TOKEN", 5, callback(100L, 55L, "subupd:7"),
                TelegramCallbackData.parse("subupd:7"));
        assertEquals("官方已播 12 集,本地 10 集", result);
    }

    @Test
    void editNotFoundFallsBackToSendMessage() {
        org.mockito.Mockito.doThrow(new TelegramApiException(
                "telegram editMessageText failed: Bad Request: message to edit not found"))
                .when(client).editMessageText(anyString(), anyString(), anyLong(), anyString(), any());
        bot.sendMenu("TOKEN", "100");
        verify(client).sendMessage(eq("TOKEN"), eq("100"), contains("追剧助手"), any());
    }

    @Test
    void pickShowsSubscribedState() {
        runSearch("斗破苍穹");
        when(subscriptionService.isSubscribedTitle(5, "斗破苍穹")).thenReturn(true);
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pick:0"), TelegramCallbackData.parse("pick:0"));
        // 详情文本独有「来源:」行,与结果列表区分;已订阅 → 按钮跳列表而非追加
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("来源:"), any());
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), anyString(),
                argThat(kb -> kb != null && kb.get(0).get(0).callbackData().startsWith("subs:")));
    }
}
