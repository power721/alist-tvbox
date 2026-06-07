package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TgProviderWebAccessCheckItem(@JsonProperty("channel_id") long channelId,
                                           @JsonProperty("web_access") boolean webAccess,
                                           @JsonProperty("checked_at") String checkedAt) {
}
