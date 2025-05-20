package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

@Data
public class BiliBiliSeasonInfo2 {
    private long season_id;
    private String title;
    private String cover;
    private String badge;
    private Rating rating;

    @Data
    public static class Rating {
        private double score;
    }
}
