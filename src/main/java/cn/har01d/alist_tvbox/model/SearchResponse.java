package cn.har01d.alist_tvbox.model;

import lombok.Data;

import java.util.List;

@Data
public class SearchResponse {
    private List<SearchResult> content;
    private int total;
}
