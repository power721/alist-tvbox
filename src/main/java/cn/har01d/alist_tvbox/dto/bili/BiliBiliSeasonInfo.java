package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

@Data
public class BiliBiliSeasonInfo {
    private int rank;
    private long season_id;
    private long aid;
    private long cid;
    private int id;
    private int sectionType;
    private String bvid;
    private String title;
    private String titleFormat;
    private String long_title;
    private String url;
    private String link;
    private String cover;
    private String badge;
    private String desc;
    private String rating;
    private Stats stat;

    @Data
    public static class Stats {
        private int danmaku;
        private int follow;
        private long view;
    }
}
