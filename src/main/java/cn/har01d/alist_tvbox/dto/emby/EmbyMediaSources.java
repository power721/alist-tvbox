package cn.har01d.alist_tvbox.dto.emby;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class EmbyMediaSources {
    @JsonProperty("MediaSources")
    private List<MediaSources> items;

    @Data
    public static class MediaSources {
        @JsonProperty("DirectStreamUrl")
        private String url;
    }
}
