package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliHistoryResult {
    private Cursor cursor;
    private List<Video> list = new ArrayList<>();

    @Data
    public static class Cursor {
        private String business;
        private long max;
    }

    @Data
    public static class Video {
        private String author_name;
        private String title;
        private String cover;
        private String tag_name;
        private long duration;
        private long view_at;
        private History history;
    }

    @Data
    public static class History {
        private String bvid;
        private String part;
        private int page;
        private long cid;
        private long epid;
        private long oid;
    }
}
