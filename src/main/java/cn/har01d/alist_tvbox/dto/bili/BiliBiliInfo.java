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
    /** UP 主合集(view 接口随视频详情同包返回,零额外请求) */
    @JsonProperty("ugc_season")
    private UgcSeason ugcSeason;

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
    public static class UgcSeason {
        private long id;
        private String title;
        private String cover;
        private long mid;
        private List<Section> sections = new ArrayList<>();

        @Data
        public static class Section {
            private long id;
            private String title;
            private List<Episode> episodes = new ArrayList<>();
        }

        @Data
        public static class Episode {
            private long aid;
            private String bvid;
            private long cid;
            private String title;
            private Arc arc;

            /** 条目时长(秒):实测响应无顶层 duration 字段,取 arc.duration(与视频条目 duration 同口径) */
            public long getDuration() {
                return arc == null ? 0 : arc.getDuration();
            }
        }

        @Data
        public static class Arc {
            private long duration;
        }
    }

    @Data
    public static class SubtitleList {
        @JsonProperty("list")
        private List<BiliBiliV2Info.Subtitle> subtitles = new ArrayList<>();
    }
}
