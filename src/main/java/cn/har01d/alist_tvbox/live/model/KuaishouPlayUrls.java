package cn.har01d.alist_tvbox.live.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class KuaishouPlayUrls {
    @JsonProperty("h264")
    private H264Data h264;

    @Data
    public static class H264Data {
        @JsonProperty("adaptationSet")
        private AdaptationSet adaptationSet;
    }

    @Data
    public static class AdaptationSet {
        @JsonProperty("representation")
        private List<Representation> representation;
    }

    @Data
    public static class Representation {
        @JsonProperty("name")
        private String name;

        @JsonProperty("level")
        private Integer level;

        @JsonProperty("url")
        private String url;
    }
}
