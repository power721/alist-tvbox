package cn.har01d.alist_tvbox.dto.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Telegram Bot API getUpdates 的单个 update(消息或按钮回调,二者互斥)。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BotUpdate {
    @JsonProperty("update_id")
    private long updateId;
    private BotMessage message;
    @JsonProperty("callback_query")
    private BotCallbackQuery callbackQuery;
}
