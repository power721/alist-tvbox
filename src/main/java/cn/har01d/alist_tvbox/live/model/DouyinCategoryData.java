package cn.har01d.alist_tvbox.live.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class DouyinCategoryData {
    @JsonProperty("partition")
    private DouyinPartition partition;

    @JsonProperty("sub_partition")
    private DouyinSubPartition[] subPartition;

    @Data
    public static class DouyinPartition {
        @JsonProperty("id_str")
        private String idStr;

        @JsonProperty("type")
        private Integer type;

        @JsonProperty("title")
        private String title;
    }

    @Data
    public static class DouyinSubPartition {
        @JsonProperty("partition")
        private DouyinPartition partition;
    }
}
