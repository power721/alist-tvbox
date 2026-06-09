package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TgPrivateChannel(long id,
                               @JsonProperty("account_id") long accountId,
                               @JsonProperty("telegram_channel_id") long telegramChannelId,
                               String title,
                               String alias,
                               String username,
                               String type,
                               @JsonProperty("last_message_id") long lastMessageId,
                               @JsonProperty("last_sync_time") String lastSyncTime,
                               @JsonProperty("web_access") boolean webAccess,
                               @JsonProperty("web_access_checked_at") String webAccessCheckedAt,
                               boolean enabled) {
    public TgPrivateChannel(long id,
                            long accountId,
                            long telegramChannelId,
                            String title,
                            String username,
                            String type,
                            long lastMessageId,
                            String lastSyncTime,
                            boolean webAccess,
                            String webAccessCheckedAt,
                            boolean enabled) {
        this(id, accountId, telegramChannelId, title, null, username, type, lastMessageId, lastSyncTime, webAccess, webAccessCheckedAt, enabled);
    }

    public static TgPrivateChannel from(TgProviderChannel channel, boolean enabled) {
        return from(channel, enabled, channel.alias());
    }

    public static TgPrivateChannel from(TgProviderChannel channel, boolean enabled, String alias) {
        return new TgPrivateChannel(
                channel.id(),
                channel.accountId(),
                channel.telegramChannelId(),
                channel.title(),
                alias,
                channel.username(),
                channel.type(),
                channel.lastMessageId(),
                channel.lastSyncTime(),
                channel.webAccess(),
                channel.webAccessCheckedAt(),
                enabled);
    }
}
