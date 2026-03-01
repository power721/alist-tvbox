package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliSeasonsArchivesList {
    private ItemsLists items_lists;

    @Data
    public static class ItemsLists {
        private Page page;
        private List<SeasonListItem> seasons_list = new ArrayList<>();
        private List<SeriesListItem> series_list = new ArrayList<>();
    }

    @Data
    public static class SeasonListItem {
        private SeasonMeta meta;
        private List<Archive> archives = new ArrayList<>();
        private List<Long> recent_aids = new ArrayList<>();

        @Data
        public static class SeasonMeta {
            private String cover;
            private String description;
            private Long mid;
            private String name;
            private Integer ptime;
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

            @Data
            public static class Stat {
                private Long view;
                private Integer danmaku;
            }
        }
    }

    @Data
    public static class SeriesListItem {
        private SeriesMeta meta;
        private List<Archive> archives = new ArrayList<>();
        private List<Long> recent_aids = new ArrayList<>();

        @Data
        public static class SeriesMeta {
            private String cover;
            private String description;
            private Long mid;
            private String name;
            private Long series_id;
            private Integer total;
        }

        @Data
        public static class Archive {
            private Long aid;
            private String bvid;
            private String pic;
            private String title;
            private Integer duration;
            private Stat stat;

            @Data
            public static class Stat {
                private Long view;
                private Integer danmaku;
            }
        }
    }

    @Data
    public static class Page {
        private Integer page_num;
        private Integer page_size;
        private Integer total;
    }
}
