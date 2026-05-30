package cn.har01d.alist_tvbox.dto.pansou;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SearchResponse {
    private int total;
    private List<SearchResult> results;
    @JsonProperty("merged_by_type")
    private Map<String, List<MergedLink>> mergedByType;
}
