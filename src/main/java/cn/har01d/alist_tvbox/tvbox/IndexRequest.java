package cn.har01d.alist_tvbox.tvbox;

import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class IndexRequest {
    private String site;
    private int maxDepth = 10;
    private Set<String> collection = new HashSet<>();
    private Set<String> single = new HashSet<>();
    private Set<String> excludes = new HashSet<>();
}
