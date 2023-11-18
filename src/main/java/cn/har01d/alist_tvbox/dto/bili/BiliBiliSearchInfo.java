package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliSearchInfo {
    private Page page;
    private SearchList list;

    @Data
    public static class SearchList {
        private List<Video> vlist = new ArrayList<>();
    }

    @Data
    public static class Video {
        private String bvid;
        private String author;
        private String title;
        private String description;
        private String pic;
        private String length;
        private int play;
    }

    @Data
    public static class Page {
        private int count;
    }
}
