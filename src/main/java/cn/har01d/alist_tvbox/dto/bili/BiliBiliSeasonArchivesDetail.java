package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliSeasonArchivesDetail {
    private List<Long> aids = new ArrayList<>();
    private List<Archive> archives = new ArrayList<>();
    private SeasonMeta meta;
    private Page page;

    @Data
    public static class SeasonMeta {
        private String cover;
        private String description;
        private Long mid;
        private String name;
        private Long season_id;
        private Integer total;
        private String title;
    }

    @Data
    public static class Archive {
        private Long aid;
        private String bvid;
        private String pic;
        private String title;
        private Integer duration;
        private Stat stat;
        private String description;

        @Data
        public static class Stat {
            private Long view;
            private Integer danmaku;
        }
    }

    @Data
    public static class Page {
        private Integer page_num;
        private Integer page_size;
        private Integer total;
    }
}
