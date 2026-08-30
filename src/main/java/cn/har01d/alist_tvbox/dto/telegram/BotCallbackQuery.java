package cn.har01d.alist_tvbox.dto.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Telegram Bot API 的 callback_query 对象:inline keyboard 按钮点击。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BotCallbackQuery {
    private String id;
    private BotUser from;
    /** 按钮所在的消息(编辑锚点:editMessageText 用它的 chat + message_id)。 */
    private BotMessage message;
    private String data;
}
