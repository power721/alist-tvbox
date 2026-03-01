package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliSeriesArchives {
    private List<Long> aids = new ArrayList<>();
    private Page page;
    private List<Archive> archives = new ArrayList<>();

    @Data
    public static class Archive {
        private Long aid;
        private String bvid;
        private String pic;
        private String title;
        private Integer duration;
        private Long view;
        private String desc;
        private Stat stat;

        @Data
        public static class Stat {
            private Long view;
            private Integer danmaku;
        }
    }

    @Data
    public static class Page {
        private Integer num;
        private Integer size;
        private Integer total;
    }
}
