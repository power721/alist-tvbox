package cn.har01d.alist_tvbox.dto.pansou;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SearchRequest {
    private final String kw;
    private final List<String> channels;
    private final String src;
    private String res = "merge";
    private List<String> plugins;
    @JsonProperty("cloud_types")
    private List<String> cloudTypes;
    private Filter filter;
    private Map<String, Object> ext;

    @Data
    public static class Filter {
        private final List<String> include;
        private final List<String> exclude;
    }
}
