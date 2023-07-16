package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliVideoInfo {
    private int numPages;
    private int numResults;
    private int page;
    private int pagesize;
    private List<Video> result = new ArrayList<>();

    @Data
    public static class Video {
        private String bvid;
        private String author;
        private String title;
        private String description;
        private String pic;
        private String pubdate;
        private String play;
        private long id;
        private long duration;
        private long favorites;
    }
}
