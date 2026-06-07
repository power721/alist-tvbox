package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TgPrivateChannelSelectionRequest(@JsonProperty("channel_ids") List<Long> channelIds) {
}
