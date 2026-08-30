package cn.har01d.alist_tvbox.dto.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Telegram Bot API 的 chat 对象。私聊场景 chat.id == 对方 user.id,是绑定解析的锚点。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BotChat {
    private long id;
    private String type;
}
