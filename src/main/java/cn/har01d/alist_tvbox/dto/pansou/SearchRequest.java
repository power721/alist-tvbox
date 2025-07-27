package cn.har01d.alist_tvbox.dto.pansou;

import lombok.Data;

import java.util.List;

@Data
public class SearchRequest {
    private final String kw;
    private final List<String> channels;
    private final String src;
    private String res = "results";
    private List<String> plugins;
}
