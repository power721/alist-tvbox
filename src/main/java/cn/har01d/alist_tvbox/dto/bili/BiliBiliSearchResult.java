package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliSearchResult {
    private int numPages;
    private int numResults;
    private int page;
    private int pagesize;
    private List<Video> result = new ArrayList<>();

    @Data
    public static class Video {
        private long id;
        private long aid;
        private String bvid;
        private String author;
        private String duration;
        private String pic;
        private String title;
        private String description;
        private String tag;
        private String type;
        private String typeid;
        private String typename;
        private int like;
        private int play;
        private long pubdate;
    }
}
