package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgWatchRule(long id,
                          @JsonProperty("channel_id") long channelId,
                          boolean enabled,
                          List<String> includes,
                          List<String> excludes,
                          @JsonProperty("created_at") String createdAt,
                          @JsonProperty("updated_at") String updatedAt) {
    public TgWatchRule {
        includes = includes == null ? List.of() : includes;
        excludes = excludes == null ? List.of() : excludes;
    }
}
