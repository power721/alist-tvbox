package cn.har01d.alist_tvbox.live.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class KuaishouCategory {
    @JsonProperty("data")
    private KuaishouCategoryData data;

    @Data
    public static class KuaishouCategoryData {
        @JsonProperty("list")
        private List<KuaishouCategoryItem> list;
    }

    @Data
    public static class KuaishouCategoryItem {
        @JsonProperty("id")
        private String id;

        @JsonProperty("name")
        private String name;

        @JsonProperty("poster")
        private String poster;
    }
}
