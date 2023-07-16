package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

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
    private int videos;
    private String title;
    private boolean is_chargeable_season;
    private boolean is_season_display;
    private boolean is_story;
    private Stats stat;
    private Owner owner;

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
}
