package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliSeriesMeta {
    private SeriesMeta meta;
    private List<Long> recent_aids = new ArrayList<>();

    @Data
    public static class SeriesMeta {
        private Long series_id;
        private Long mid;
        private String name;
        private String description;
        private Integer total;
    }
}
