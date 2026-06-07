package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgProviderSearchItem(int id,
                                   @JsonProperty("telegram_message_id") Long telegramMessageId,
                                   String text,
                                   String date,
                                   @JsonProperty("channel_title") String channelTitle,
                                   @JsonProperty("channel_username") String channelUsername,
                                   List<TgProviderLink> links) {
}
