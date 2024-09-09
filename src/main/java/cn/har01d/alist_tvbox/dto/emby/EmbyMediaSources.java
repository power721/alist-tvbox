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
        @JsonProperty("Name")
        private String name;

        @JsonProperty("DirectStreamUrl")
        private String url;

        @JsonProperty("MediaStreams")
        private List<MediaStreams> mediaStreams;
    }

    @Data
    public static class MediaStreams {
        @JsonProperty("Codec")
        private String codec;
        @JsonProperty("Type")
        private String type;
        @JsonProperty("Language")
        private String language;
        @JsonProperty("DisplayTitle")
        private String title;
        @JsonProperty("DeliveryUrl")
        private String url;
    }
}
