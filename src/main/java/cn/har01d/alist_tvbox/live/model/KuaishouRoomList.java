package cn.har01d.alist_tvbox.live.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class KuaishouRoomList {
    @JsonProperty("data")
    private KuaishouRoomListData data;

    @Data
    public static class KuaishouRoomListData {
        @JsonProperty("list")
        private List<KuaishouRoomItem> list;
    }

    @Data
    public static class KuaishouRoomItem {
        @JsonProperty("caption")
        private String caption;

        @JsonProperty("poster")
        private String poster;

        @JsonProperty("watchingCount")
        private Integer watchingCount;

        @JsonProperty("author")
        private KuaishouAuthor author;

        @JsonProperty("gameInfo")
        private KuaishouGameInfo gameInfo;

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

            @JsonProperty("watchingCount")
            private Integer watchingCount;
        }
    }
}
