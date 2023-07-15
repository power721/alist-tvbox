package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliPlay {
    private String format;
    private int quality;
    private long timelength;
    private Dash dash = new Dash();
    private List<DUrl> durl = new ArrayList<>();

    @Data
    public static class DUrl {
        private long length;
        private long size;
        private String url;
    }

    @Data
    public static class Dash {
        private List<Audio> audio = new ArrayList<>();
        private List<Video> video = new ArrayList<>();
        @Data
        public static class Audio {
            private long id;
            private int bandwidth;
            private String codecs;
            private String mimeType;
            private String baseUrl;
            private List<String> backupUrl = new ArrayList<>();
        }

        @Data
        public static class Video {
            private long id;
            private int width;
            private int height;
            private int bandwidth;
            private String codecs;
            private String mimeType;
            private String baseUrl;
            private List<String> backupUrl = new ArrayList<>();
        }
    }
}
