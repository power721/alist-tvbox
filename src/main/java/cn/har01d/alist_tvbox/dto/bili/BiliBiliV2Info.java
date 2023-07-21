package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliV2Info {
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
    private int videos;
    private String title;
    private boolean is_chargeable_season;
    private boolean is_season_display;
    private boolean is_story;
    private Stats stat;
    private Owner owner;
    private SubtitleList subtitle;
    private List<PageInfo> pages = new ArrayList<>();

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
    public static class Owner {
        private long mid;
        private String name;
        private String face;
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
        private List<Subtitle> subtitles = new ArrayList<>();
    }

    @Data
    public static class Subtitle {
        private long id;
        private String lan;
        private String lan_doc;
        private String subtitle_url;
    }
}
