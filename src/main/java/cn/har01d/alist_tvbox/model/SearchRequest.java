package cn.har01d.alist_tvbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class SearchRequest {
    private String keywords;
    private String password = "";
    private String parent = "/";
    private int page = 1;
    @JsonProperty("per_page")
    private int size = 100;
}
