package cn.har01d.alist_tvbox.live.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class DouyinRoomDetail {
    @JsonProperty("data")
    private List<DouyinRoomData> data;

    @JsonProperty("user")
    private DouyinUser user;

    @Data
    public static class DouyinRoomData {
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

            @JsonProperty("web_rid")
            private String webRid;

            @JsonProperty("avatar_thumb")
            private DouyinUrlList avatarThumb;

            @JsonProperty("signature")
            private String signature;
        }

        @Data
        public static class DouyinViewStats {
            @JsonProperty("display_value")
            private String displayValue;
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

    @Data
    public static class DouyinUser {
        @JsonProperty("nickname")
        private String nickname;

        @JsonProperty("avatar_thumb")
        private DouyinUrlList avatarThumb;
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
