package cn.har01d.alist_tvbox.dto.bili;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliInfo {
    private long aid;
    private String bvid;
    private long cid;
    private long duration;
    private long ctime;
    private long pubdate;
    private String pic;
    private String desc;
    private int tid;
    private String tname;
    private String tname_v2;
    private int videos;
    private String title;
    private boolean is_chargeable_season;
    private boolean is_season_display;
    private boolean is_story;
    private Stats stat;
    private User owner;
    private List<User> staff;
    //private SubtitleList subtitle;
    private List<PageInfo> pages;

    @Data
    public static class Stats {
        private int coin;
        private int danmaku;
        private int favorite;
        private int like;
        private int reply;
        private int share;
        private int view;
    }

    @Data
    public static class User {
        private long mid;
        private String name;
    }

    @Data
    public static class PageInfo {
        private long cid;
        private long duration;
        private int page;
        private String part;
    }

    @Data
    public static class SubtitleList {
        @JsonProperty("list")
        private List<BiliBiliV2Info.Subtitle> subtitles = new ArrayList<>();
    }
}
