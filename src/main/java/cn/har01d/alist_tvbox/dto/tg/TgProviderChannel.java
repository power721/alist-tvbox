package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgProviderChannel(long id,
                                @JsonProperty("account_id") long accountId,
                                @JsonProperty("telegram_channel_id") long telegramChannelId,
                                @JsonProperty("access_hash") long accessHash,
                                String title,
                                String username,
                                String type,
                                @JsonProperty("last_message_id") long lastMessageId,
                                @JsonProperty("last_sync_time") String lastSyncTime,
                                @JsonProperty("created_at") String createdAt,
                                @JsonProperty("updated_at") String updatedAt) {
}
