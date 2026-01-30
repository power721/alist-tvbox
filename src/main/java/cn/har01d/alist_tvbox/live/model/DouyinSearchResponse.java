package cn.har01d.alist_tvbox.live.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class DouyinSearchResponse {
    @JsonProperty("data")
    private List<DouyinSearchItem> data;

    @JsonProperty("status_code")
    private Integer statusCode;

    @Data
    public static class DouyinSearchItem {
        @JsonProperty("lives")
        private DouyinLives lives;

        @Data
        public static class DouyinLives {
            @JsonProperty("rawdata")
            private String rawdata;
        }
    }
}
