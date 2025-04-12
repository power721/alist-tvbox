package cn.har01d.alist_tvbox.dto.tg;

import lombok.Data;

import java.util.List;

@Data
public class SearchResponse {
    private int total;
    private List<String> errors;
    private List<SearchResult> messages;
}
