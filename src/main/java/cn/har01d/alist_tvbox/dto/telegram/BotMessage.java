package cn.har01d.alist_tvbox.dto.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Telegram Bot API 的 message 对象(仅交互所需的字段子集,其余忽略)。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BotMessage {
    @JsonProperty("message_id")
    private long messageId;
    private BotUser from;
    private BotChat chat;
    private int date;
    private String text;
}
