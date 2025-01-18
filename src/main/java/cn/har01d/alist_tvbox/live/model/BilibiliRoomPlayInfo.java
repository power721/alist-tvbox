package cn.har01d.alist_tvbox.live.model;

import lombok.Data;

import java.util.List;

@Data
public class BilibiliRoomPlayInfo {
    private PlayInfo playurl_info;

    @Data
    public static class PlayInfo {
        private PlayUrl playurl;
    }

    @Data
    public static class PlayUrl {
        private List<QnDesc> g_qn_desc;
        private List<Stream> stream;
    }

    @Data
    public static class QnDesc {
        private String desc;
        private int qn;
    }

    @Data
    public static class Stream {
        private String protocol_name;
        private List<Format> format;
    }

    @Data
    public static class Format {
        private String format_name;
        private List<Codec> codec;
    }

    @Data
    public static class Codec {
        private String base_url;
        private String codec_name;
        private int current_qn;
        private List<UrlInfo> url_info;
    }

    @Data
    public static class UrlInfo {
        private String host;
        private String extra;
    }
}
