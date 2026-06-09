package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgProviderAccountChannelSyncResponse(@JsonProperty("job_id") String jobId,
                                                   String status,
                                                   List<TgProviderChannel> items) {
    public TgProviderAccountChannelSyncResponse {
        items = items == null ? List.of() : items;
    }
}
