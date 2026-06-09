package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TgWatchRuleRequest(@JsonProperty("channel_id") Long channelId,
                                 Boolean enabled,
                                 List<String> includes,
                                 List<String> excludes) {
    public TgWatchRuleRequest {
        includes = includes == null ? List.of() : includes;
        excludes = excludes == null ? List.of() : excludes;
    }
}
