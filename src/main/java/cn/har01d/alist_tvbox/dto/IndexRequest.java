package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
public class IndexRequest {
    private Integer siteId;
    private String indexName = "custom_index";
    private boolean excludeExternal;
    private boolean incremental;
    private boolean compress;
    private boolean scrape;
    private boolean includeFiles;
    private int maxDepth = 10;
    private int sleep = 2000;
    private List<String> paths = new ArrayList<>();
    private Set<String> stopWords = new HashSet<>();
    private Set<String> excludes = new HashSet<>();
}
