package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliSeasonSeriesDetail {
    private SeasonMeta meta;
    private List<Archive> archives = new ArrayList<>();
    private Page page;

    @Data
    public static class SeasonMeta {
        private String cover;
        private String name;
        private String description;
        private Long season_id;
        private Long mid;
        private Integer total;
    }

    @Data
    public static class Archive {
        private Long aid;
        private String bvid;
        private String cover;
        private String title;
        private String length;
        private Long play;
        private Integer comment;
    }

    @Data
    public static class Page {
        private Integer num;
        private Integer size;
        private Integer total;
    }
}
