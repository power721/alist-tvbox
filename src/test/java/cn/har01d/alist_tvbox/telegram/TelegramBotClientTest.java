package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.dto.telegram.BotUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Bot API HTTP 封装:getUpdates 参数与解析、HTML parse_mode、键盘序列化、"not modified" 吞掉、错误上抛。 */
class TelegramBotClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate rest = new RestTemplate();
    private MockRestServiceServer server;
    private TelegramBotClient client;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(rest).build();
        client = new TelegramBotClient(objectMapper, rest);
    }

    @Test
    void getUpdatesSendsOffsetAndParsesResult() {
        server.expect(requestTo(URI.create("https://api.telegram.org/botTOKEN/getUpdates")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"offset\":11,\"timeout\":25,\"allowed_updates\":[\"message\",\"callback_query\"]}"))
                .andRespond(withSuccess("""
                        {"ok":true,"result":[
                          {"update_id":11,"message":{"message_id":1,"chat":{"id":100},"text":"/start"}},
                          {"update_id":12,"callback_query":{"id":"q1","data":"sub:3",
                            "message":{"message_id":2,"chat":{"id":100}}}}
                        ]}""", MediaType.APPLICATION_JSON));
        List<BotUpdate> updates = client.getUpdates("TOKEN", 11);
        server.verify();
        assertEquals(2, updates.size());
        assertEquals(11, updates.get(0).getUpdateId());
        assertEquals("/start", updates.get(0).getMessage().getText());
        assertEquals(100, updates.get(1).getCallbackQuery().getMessage().getChat().getId());
        assertEquals("sub:3", updates.get(1).getCallbackQuery().getData());
    }

    @Test
    void sendMessageCarriesHtmlModeAndKeyboard() {
        server.expect(requestTo(URI.create("https://api.telegram.org/botTOKEN/sendMessage")))
                .andExpect(content().json("""
                        {"chat_id":"100","text":"<b>hi</b>","parse_mode":"HTML",
                         "link_preview_options":{"is_disabled":true},
                         "reply_markup":{"inline_keyboard":[[{"text":"按钮","callback_data":"home"}]]}}"""))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"message_id\":77}}", MediaType.APPLICATION_JSON));
        long messageId = client.sendMessage("TOKEN", "100", "<b>hi</b>",
                List.of(List.of(new TelegramButton("按钮", "home"))));
        server.verify();
        assertEquals(77, messageId);
    }

    @Test
    void posterGoesThroughLinkPreviewNotSendPhoto() {
        // 文本消息不能被编辑成媒体消息,而 Bot 全程单锚点编辑 —— 海报走 link_preview_options.url,图不必进正文
        server.expect(requestTo(URI.create("https://api.telegram.org/botTOKEN/editMessageText")))
                .andExpect(content().json("""
                        {"chat_id":"100","message_id":5,"text":"详情",
                         "link_preview_options":{"url":"https://image.tmdb.org/p.jpg",
                          "prefer_large_media":true,"show_above_text":true}}"""))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{}}", MediaType.APPLICATION_JSON));
        client.editMessageText("TOKEN", "100", 5, "详情", null, "https://image.tmdb.org/p.jpg");
        server.verify();
    }

    @Test
    void nonHttpPosterFallsBackToDisabledPreview() {
        server.expect(requestTo(URI.create("https://api.telegram.org/botTOKEN/editMessageText")))
                .andExpect(content().json("{\"link_preview_options\":{\"is_disabled\":true}}"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{}}", MediaType.APPLICATION_JSON));
        client.editMessageText("TOKEN", "100", 5, "详情", null, "/images?url=x");
        server.verify();
    }

    @Test
    void messageNotModifiedIsSwallowed() {
        server.expect(requestTo(URI.create("https://api.telegram.org/botTOKEN/editMessageText")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\":false,\"description\":\"Bad Request: message is not modified\"}"));
        client.editMessageText("TOKEN", "100", 5, "text", null);
        server.verify();
    }

    @Test
    void otherApiErrorsPropagateWithDescription() {
        server.expect(requestTo(URI.create("https://api.telegram.org/botTOKEN/sendMessage")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\":false,\"description\":\"Forbidden: bot was blocked by the user\"}"));
        TelegramApiException e = assertThrows(TelegramApiException.class,
                () -> client.sendMessage("TOKEN", "100", "text", null));
        assertEquals("telegram sendMessage failed: Forbidden: bot was blocked by the user", e.getMessage());
    }

    @Test
    void answerCallbackQueryTruncatesLongText() {
        server.expect(requestTo(URI.create("https://api.telegram.org/botTOKEN/answerCallbackQuery")))
                .andExpect(content().json("{\"callback_query_id\":\"q9\",\"text\":\"" + "x".repeat(200) + "\"}"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":true}", MediaType.APPLICATION_JSON));
        client.answerCallbackQuery("TOKEN", "q9", "x".repeat(300));
        server.verify();
    }

    @Test
    void setMyCommandsRegistersEveryEntryCommand() {
        // 命令菜单是 TG 客户端「/」提示的唯一来源:新入口不登记这里,用户在菜单里看不到
        server.expect(requestTo(URI.create("https://api.telegram.org/botTOKEN/setMyCommands")))
                .andExpect(content().json("""
                        {"commands":[{"command":"start"},{"command":"subs"},
                         {"command":"search"},{"command":"piandan"},{"command":"calendar"}]}"""))
                .andRespond(withSuccess("{\"ok\":true,\"result\":true}", MediaType.APPLICATION_JSON));
        client.setMyCommands("TOKEN");
        server.verify();
    }
}
