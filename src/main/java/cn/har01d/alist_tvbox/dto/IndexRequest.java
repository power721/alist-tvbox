package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class IndexRequest {
    private Integer siteId;
    private String indexName = "index";
    private boolean excludeExternal;
    private boolean incremental;
    private boolean compress;
    private int maxDepth = 10;
    private Set<String> paths = new HashSet<>();
    private Set<String> stopWords = new HashSet<>();
    private Set<String> excludes = new HashSet<>();
}
