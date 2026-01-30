package cn.har01d.alist_tvbox.live.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class DouyinRoomListResponse {
    @JsonProperty("data")
    private DouyinRoomListData data;

    @Data
    public static class DouyinRoomListData {
        @JsonProperty("data")
        private List<DouyinRoomItem> data;
    }

    @Data
    public static class DouyinRoomItem {
        @JsonProperty("web_rid")
        private String webRid;

        @JsonProperty("room")
        private DouyinRoom room;

        @JsonProperty("tag_name")
        private String tagName;

        @Data
        public static class DouyinRoom {
            @JsonProperty("title")
            private String title;

            @JsonProperty("cover")
            private DouyinUrlList cover;

            @JsonProperty("owner")
            private DouyinOwner owner;

            @JsonProperty("room_view_stats")
            private DouyinViewStats roomViewStats;

            @Data
            public static class DouyinUrlList {
                @JsonProperty("url_list")
                private String[] urlList;
            }

            @Data
            public static class DouyinOwner {
                @JsonProperty("nickname")
                private String nickname;

                @JsonProperty("avatar_thumb")
                private DouyinUrlList avatarThumb;
            }

            @Data
            public static class DouyinViewStats {
                @JsonProperty("display_value")
                private String displayValue;
            }
        }
    }
}
