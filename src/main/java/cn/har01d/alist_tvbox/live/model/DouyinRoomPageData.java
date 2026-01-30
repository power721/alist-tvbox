package cn.har01d.alist_tvbox.live.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class DouyinRoomPageData {
    @JsonProperty("roomStore")
    private DouyinRoomStore roomStore;

    @JsonProperty("userStore")
    private DouyinUserStore userStore;

    @Data
    public static class DouyinRoomStore {
        @JsonProperty("roomInfo")
        private DouyinRoomInfo roomInfo;

        @Data
        public static class DouyinRoomInfo {
            @JsonProperty("room")
            private DouyinRoom room;

            @JsonProperty("anchor")
            private DouyinAnchor anchor;

            @Data
            public static class DouyinRoom {
                @JsonProperty("id_str")
                private String idStr;

                @JsonProperty("title")
                private String title;

                @JsonProperty("status")
                private Integer status;

                @JsonProperty("cover")
                private DouyinUrlList cover;

                @JsonProperty("owner")
                private DouyinOwner owner;

                @JsonProperty("room_view_stats")
                private DouyinViewStats roomViewStats;

                @JsonProperty("stream_url")
                private DouyinStreamUrl streamUrl;

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

            @Data
            public static class DouyinAnchor {
                @JsonProperty("nickname")
                private String nickname;

                @JsonProperty("avatar_thumb")
                private DouyinUrlList avatarThumb;
            }
        }
    }

    @Data
    public static class DouyinUserStore {
        @JsonProperty("odin")
        private DouyinOdin odin;

        @Data
        public static class DouyinOdin {
            @JsonProperty("user_unique_id")
            private String userUniqueId;
        }
    }

    @Data
    public static class DouyinUrlList {
        @JsonProperty("url_list")
        private String[] urlList;
    }

    @Data
    public static class DouyinStreamUrl {
        @JsonProperty("flv_pull_url")
        private Object flvPullUrl;

        @JsonProperty("hls_pull_url_map")
        private Object hlsPullUrlMap;

        @JsonProperty("live_core_sdk_data")
        private DouyinLiveCoreSdkData liveCoreSdkData;

        @Data
        public static class DouyinLiveCoreSdkData {
            @JsonProperty("pull_data")
            private DouyinPullData pullData;

            @Data
            public static class DouyinPullData {
                @JsonProperty("options")
                private DouyinOptions options;

                @JsonProperty("stream_data")
                private Object streamData;

                @Data
                public static class DouyinOptions {
                    @JsonProperty("qualities")
                    private DouyinQuality[] qualities;

                    @Data
                    public static class DouyinQuality {
                        @JsonProperty("name")
                        private String name;

                        @JsonProperty("level")
                        private Integer level;

                        @JsonProperty("sdk_key")
                        private String sdkKey;
                    }
                }
            }
        }
    }
}
