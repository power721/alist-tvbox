package cn.har01d.alist_tvbox.dto.pansou;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class PanSouSearchResponse {
    private Integer code;
    private String message;
    private SearchResponse data;
    private int total;
    private List<SearchResult> results;
    @JsonProperty("merged_by_type")
    private Map<String, List<MergedLink>> mergedByType;

    public SearchResponse getSearchResponse() {
        if (data != null) {
            return data;
        }
        SearchResponse response = new SearchResponse();
        response.setTotal(total);
        response.setResults(results);
        response.setMergedByType(mergedByType);
        return response;
    }
}
