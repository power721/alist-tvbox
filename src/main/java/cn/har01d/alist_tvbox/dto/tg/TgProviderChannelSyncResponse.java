package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgProviderChannelSyncResponse(@JsonProperty("job_id") String jobId,
                                            String status,
                                            int messages,
                                            int links) {
}
