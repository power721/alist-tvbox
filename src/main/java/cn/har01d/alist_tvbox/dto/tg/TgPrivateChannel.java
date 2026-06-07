package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TgPrivateChannel(long id,
                               @JsonProperty("account_id") long accountId,
                               @JsonProperty("telegram_channel_id") long telegramChannelId,
                               String title,
                               String username,
                               String type,
                               @JsonProperty("last_message_id") long lastMessageId,
                               @JsonProperty("last_sync_time") String lastSyncTime,
                               @JsonProperty("web_access") boolean webAccess,
                               @JsonProperty("web_access_checked_at") String webAccessCheckedAt,
                               boolean enabled) {
    public static TgPrivateChannel from(TgProviderChannel channel, boolean enabled) {
        return new TgPrivateChannel(
                channel.id(),
                channel.accountId(),
                channel.telegramChannelId(),
                channel.title(),
                channel.username(),
                channel.type(),
                channel.lastMessageId(),
                channel.lastSyncTime(),
                channel.webAccess(),
                channel.webAccessCheckedAt(),
                enabled);
    }
}
