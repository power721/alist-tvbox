package cn.har01d.alist_tvbox.tvbox;

import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class IndexRequest {
    private String site;
    private String path;
    private boolean includeFile;
    private Set<String> includes = new HashSet<>();
    private Set<String> excludes = new HashSet<>();
}
