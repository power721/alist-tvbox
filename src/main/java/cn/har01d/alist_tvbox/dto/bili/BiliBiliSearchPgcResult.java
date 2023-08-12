package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliSearchPgcResult {
    private List<Video> result = new ArrayList<>();

    @Data
    public static class Video {
        private String title;
        private String cover;
        private String styles;
        private String index_show;
        private Integer season_id;
    }
}
