package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record TgPrivateChannelSelectionRequest(@JsonProperty("channel_ids") List<Long> channelIds,
                                               Map<Long, String> aliases) {
    public TgPrivateChannelSelectionRequest(List<Long> channelIds) {
        this(channelIds, null);
    }
}
