package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.List;

@Data
public class SearchSetting {
    private List<String> files;
    private List<String> searchSources;
    private String excludedPaths;
}
