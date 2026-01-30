package cn.har01d.alist_tvbox.live.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class KuaishouRecommendList {
    @JsonProperty("data")
    private KuaishouRecommendData data;

    @Data
    public static class KuaishouRecommendData {
        @JsonProperty("list")
        private List<RecommendItem> list;
    }

    @Data
    public static class RecommendItem {
        @JsonProperty("gameLiveInfo")
        private List<GameLiveInfo> gameLiveInfo;

        @Data
        public static class GameLiveInfo {
            @JsonProperty("liveInfo")
            private List<LiveInfo> liveInfo;

            @Data
            public static class LiveInfo {
                @JsonProperty("author")
                private KuaishouAuthor author;

                @JsonProperty("gameInfo")
                private KuaishouGameInfo gameInfo;

                @JsonProperty("watchingCount")
                private Integer watchingCount;

                @JsonProperty("playUrls")
                private KuaishouPlayUrls playUrls;
            }
        }
    }

    @Data
    public static class KuaishouAuthor {
        @JsonProperty("id")
        private String id;

        @JsonProperty("name")
        private String name;

        @JsonProperty("avatar")
        private String avatar;

        @JsonProperty("description")
        private String description;
    }

    @Data
    public static class KuaishouGameInfo {
        @JsonProperty("name")
        private String name;

        @JsonProperty("poster")
        private String poster;
    }
}
