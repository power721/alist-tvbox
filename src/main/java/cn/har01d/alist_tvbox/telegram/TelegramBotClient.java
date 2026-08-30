package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.dto.telegram.BotUpdate;
import cn.har01d.alist_tvbox.service.metadata.MetadataHttp;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Telegram Bot API 的最小 HTTP 封装(出站长轮询 + 交互回复),token 由调用方逐次传入以支持热切换。
 * <p>
 * 与 {@code MediaSubscriptionNotificationService.callTelegram} 同款裸 HTTP 模式:String 收发 + 注入的
 * Jackson2 ObjectMapper 手动解析(规避 Boot 4 裸 new RestTemplate() 挂 Jackson3 转换器的坑),不引入 Bot SDK。
 * 读超时须大于 getUpdates 的 timeout(25s 挂起),故不共用 15s 的 MetadataHttp 默认实例。
 * 交互文本统一 parse_mode=HTML,转义责任在 Renderer;token 一律不入日志。
 */
@Component
public class TelegramBotClient {
    private static final Logger log = LoggerFactory.getLogger(TelegramBotClient.class);
    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final long POLL_TIMEOUT_SECONDS = 25;

    private final ObjectMapper objectMapper;
    private final RestTemplate rest;

    @Autowired
    public TelegramBotClient(ObjectMapper objectMapper, MetadataHttp metadataHttp) {
        this(objectMapper, metadataHttp.create(Duration.ofSeconds(POLL_TIMEOUT_SECONDS + 10)));
    }

    /** 测试注入 RestTemplate 桩;生产走带超时的 MetadataHttp 工厂。 */
    TelegramBotClient(ObjectMapper objectMapper, RestTemplate rest) {
        this.objectMapper = objectMapper;
        this.rest = rest;
    }

    /** 拉取待处理 update(offset 为上轮最大 update_id+1;首轮 0)。allowed_updates 收窄防无关更新刷屏。 */
    public List<BotUpdate> getUpdates(String token, long offset) {
        var body = objectMapper.createObjectNode();
        body.put("offset", offset);
        body.put("timeout", POLL_TIMEOUT_SECONDS);
        return fetchUpdates(token, body);
    }

    /** 队尾快照:offset=-1 只回最后一条待处理 update、timeout=0 立即返回(不挂起等新命令),
     *  供会话启动时把 offset 直推队尾 —— 确认语义一次性丢弃全部离线积压,快照之后到达的照常下发。 */
    public List<BotUpdate> tailUpdates(String token) {
        var body = objectMapper.createObjectNode();
        body.put("offset", -1);
        body.put("timeout", 0);
        return fetchUpdates(token, body);
    }

    private List<BotUpdate> fetchUpdates(String token, com.fasterxml.jackson.databind.node.ObjectNode body) {
        body.putArray("allowed_updates").add("message").add("callback_query");
        String response = exchange(token, "getUpdates", body);
        try {
            List<BotUpdate> updates = new ArrayList<>();
            for (var node : objectMapper.readTree(response).path("result")) {
                updates.add(objectMapper.treeToValue(node, BotUpdate.class));
            }
            return updates;
        } catch (JsonProcessingException e) {
            throw new TelegramApiException("bad getUpdates response", e);
        }
    }

    /** 发送新消息(HTML),返回 message_id 供后续编辑锚定。 */
    public long sendMessage(String token, String chatId, String text, List<List<TelegramButton>> keyboard) {
        return sendMessage(token, chatId, text, keyboard, null);
    }

    /** poster 非空时挂 link preview 出海报(详情页),否则显式关预览防正文里的链接被抢镜。 */
    public long sendMessage(String token, String chatId, String text, List<List<TelegramButton>> keyboard,
                            String poster) {
        var body = objectMapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        linkPreview(body, poster);
        keyboard(keyboard, body);
        String response = exchange(token, "sendMessage", body);
        try {
            return objectMapper.readTree(response).path("result").path("message_id").asLong(0);
        } catch (JsonProcessingException e) {
            throw new TelegramApiException("bad sendMessage response", e);
        }
    }

    /** 编辑已有消息(翻页/进出详情复用同一条消息,防刷屏)。"message is not modified" 视为成功。 */
    public void editMessageText(String token, String chatId, long messageId, String text,
                                List<List<TelegramButton>> keyboard) {
        editMessageText(token, chatId, messageId, text, keyboard, null);
    }

    public void editMessageText(String token, String chatId, long messageId, String text,
                                List<List<TelegramButton>> keyboard, String poster) {
        var body = objectMapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        linkPreview(body, poster);
        keyboard(keyboard, body);
        exchange(token, "editMessageText", body);
    }

    /**
     * 海报走 link preview 而非 sendPhoto:文本消息不能被编辑成媒体消息,而本 Bot 全程单锚点编辑。
     * {@code link_preview_options.url} 让图不必出现在正文里(Bot API 7.0+);抓不到图 TG 只是不渲染,不报错。
     * 无海报的页面显式 {@code is_disabled},免得剧名/简介里的链接被 TG 抓来当预览。
     */
    private void linkPreview(com.fasterxml.jackson.databind.node.ObjectNode body, String poster) {
        var options = body.putObject("link_preview_options");
        if (poster == null || !poster.startsWith("http")) {
            options.put("is_disabled", true);
            return;
        }
        options.put("url", poster);
        options.put("prefer_large_media", true);
        options.put("show_above_text", true);
    }

    /** 应答回调(TG 客户端的转圈止于此);text 为弹出提示,可空。 */
    public void answerCallbackQuery(String token, String callbackQueryId, String text) {
        var body = objectMapper.createObjectNode();
        body.put("callback_query_id", callbackQueryId);
        if (text != null && !text.isBlank()) {
            // TG 限制 200 字符,超长静默截断
            body.put("text", text.length() > 200 ? text.substring(0, 200) : text);
        }
        exchange(token, "answerCallbackQuery", body);
    }

    /** 注册命令菜单(TG 客户端输入 / 时提示);失败只记日志,不影响轮询。 */
    public void setMyCommands(String token) {
        var body = objectMapper.createObjectNode();
        var commands = body.putArray("commands");
        commands.addObject().put("command", "start").put("description", "主菜单");
        commands.addObject().put("command", "subs").put("description", "我的追剧订阅");
        commands.addObject().put("command", "search").put("description", "搜索追剧,可带剧名:/search 庆余年");
        commands.addObject().put("command", "piandan").put("description", "片单追更(榜单挑剧)");
        commands.addObject().put("command", "calendar").put("description", "追更日历(今晚更新什么)");
        try {
            exchange(token, "setMyCommands", body);
        } catch (Exception e) {
            log.warn("telegram setMyCommands failed: {}", e.getMessage());
        }
    }

    private void keyboard(List<List<TelegramButton>> keyboard, com.fasterxml.jackson.databind.node.ObjectNode body) {
        if (keyboard == null) {
            return;
        }
        var markup = body.putObject("reply_markup");
        var rows = markup.putArray("inline_keyboard");
        for (List<TelegramButton> row : keyboard) {
            var array = rows.addArray();
            for (TelegramButton button : row) {
                var node = array.addObject();
                node.put("text", button.text());
                node.put("callback_data", button.callbackData());
            }
        }
    }

    private String exchange(String token, String method, com.fasterxml.jackson.databind.node.ObjectNode body) {
        URI uri = URI.create(API_BASE + token + "/" + method);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response;
        try {
            response = rest.exchange(uri, HttpMethod.POST, new HttpEntity<>(body.toString(), headers), String.class);
        } catch (HttpStatusCodeException e) {
            String description = errorDescription(e);
            // 内容未变的重复编辑(狂点同一按钮)是正常交互,吞掉
            if (description.contains("message is not modified")) {
                log.debug("telegram {} not modified", method);
                return "{\"ok\":true}";
            }
            throw new TelegramApiException("telegram " + method + " failed: " + description, e);
        }
        return response.getBody();
    }

    private String errorDescription(HttpStatusCodeException e) {
        try {
            String description = objectMapper.readTree(e.getResponseBodyAsString()).path("description").asText("");
            if (!description.isBlank()) {
                return description;
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体,回落原始文本
        }
        return e.getResponseBodyAsString().isBlank() ? e.getMessage() : e.getResponseBodyAsString();
    }
}
