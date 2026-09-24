package cn.har01d.alist_tvbox.dto.bili;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 稍后再看列表(GET /x/v2/history/toview/web):单接口全量返回,无游标无翻页。
 */
@Data
public class BiliBiliWatchLaterResult {
    private int count;
    private List<Video> list = new ArrayList<>();

    @Data
    public static class Video {
        private long aid;
        private String bvid;
        private String title;
        private String pic;
        private long duration;
        /** 已观看秒数,-1 表示已看完 */
        private long progress;
        @JsonProperty("add_at")
        private long addAt;
        private Owner owner;
    }

    @Data
    public static class Owner {
        private long mid;
        private String name;
    }
}
